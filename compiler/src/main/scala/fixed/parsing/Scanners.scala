package fixed.parsing

import scala.collection.immutable.Queue
import scala.annotation.tailrec

import fixed.util.{Diagnostic, Reporter, Severity, SourceFile, Span}

// Pure-FP single-pass lexer. The `Scanner` object holds the immutable
// core (`ScannerState`, `step`); the `class Scanner` wrapper below
// adapts it to the parser's pull-style `nextToken()` API. Indentation,
// line-continuation, and bracket-suppression rules: see
// `spec/syntax_grammar.ebnf` lines 156–217.

object Scanner:

  // ---- State ----

  final case class ScannerState(
      pos: Int,
      indentStack: List[Int],
      bracketStack: List[(Char, Int)],
      lastKind: TokenKind,                // last token's kind (TokenKind.NoStart before first emit)
      eofEmitted: Boolean,
      pending: Queue[Token]
  ):
    // Suppressed iff inside a bracket whose recorded indent depth still
    // matches `indentStack.length` — i.e. no body has been engaged since
    // the bracket opened. (Profiled alternatives — cached `indentDepth`
    // field, SoA `IArray` bracket stack — both regressed; List wins for
    // shallow, push/pop-heavy stacks.)
    def isInsideSuppressedBrackets: Boolean = bracketStack match
      case (_, depth) :: _ => indentStack.length == depth
      case Nil             => false

    def currentIndent: Int = indentStack.head

    def enqueue(t: Token): ScannerState = copy(pending = pending.enqueue(t))
    // We only ever read `.kind`, so store the kind directly — no `Some` box.
    def withLast(t: Token): ScannerState = copy(lastKind = t.kind)

  end ScannerState

  def initialState: ScannerState =
    ScannerState(
      pos = 0,
      indentStack = 0 :: Nil,
      bracketStack = Nil,
      lastKind = TokenKind.NoStart,
      eofEmitted = false,
      pending = Queue.empty
    )

  // Empty-errors singleton. Returning `NoErrors` from a helper that didn't
  // produce diagnostics is allocation-free (`Vector.empty` is interned).
  private val NoErrors: Vector[Diagnostic] = Vector.empty

  private inline def err(
      code: String,
      span: Span,
      message: String,
      suggestion: Option[String] = None
  ): Diagnostic =
    Diagnostic(Severity.Error, code, span, message, suggestion)

  // ---- Public API ----

  def tokenize(source: SourceFile, reporter: Reporter): Seq[Token] =
    new Scanner(source, reporter).tokenize()

  // ---- Transitions ----

  // Advance by one token. Returns the new state, the emitted token, and
  // diagnostics produced *by this step only* (not accumulated across the
  // whole scan). Idempotent at EOF. Each branch fuses pending+lastKind
  // updates into a single state.copy (ScannerState is the dominant
  // allocator per JFR; doubling up halves the per-step copies).
  def step(state: ScannerState, source: SourceFile): (ScannerState, Token, Vector[Diagnostic]) =
    state.pending.dequeueOption match
      case Some((tok, rest)) =>
        (state.copy(pending = rest, lastKind = tok.kind), tok, NoErrors)
      case None =>
        scanOne(state, source) match
          case ScanResult.RealToken(st1, tok, errs) =>
            // Synthetic tokens (INDENT/DEDENT/NEWLINE) drain ahead of the
            // real one; re-queue the real token at the tail.
            if st1.pending.nonEmpty then
              val synth = st1.pending.head
              val rest = st1.pending.tail
              (st1.copy(pending = rest.enqueue(tok), lastKind = synth.kind), synth, errs)
            else
              (st1.copy(lastKind = tok.kind), tok, errs)
          case ScanResult.OnlySynthetics(st1, errs) =>
            st1.pending.dequeueOption match
              case Some((tok, rest)) =>
                (st1.copy(pending = rest, lastKind = tok.kind), tok, errs)
              case None =>
                if !st1.eofEmitted then
                  val (next, tok, more) = step(closeIndentsAndQueueEof(st1), source)
                  (next, tok, errs ++ more)
                else
                  (st1, Token(TokenKind.Eof, Span(st1.pos, st1.pos)), errs)

  private def closeIndentsAndQueueEof(state: ScannerState): ScannerState =
    val pos = state.pos
    val dedentCount = state.indentStack.length - 1
    val poppedStack = state.indentStack.drop(dedentCount)
    val dedents = Vector.fill(dedentCount)(Token(TokenKind.Dedent, Span(pos, pos)))
    val withDedents = dedents.foldLeft(state.pending)(_ enqueue _)
    val withEof = withDedents.enqueue(Token(TokenKind.Eof, Span(pos, pos)))
    state.copy(indentStack = poppedStack, pending = withEof, eofEmitted = true)

  private enum ScanResult:
    case RealToken(state: ScannerState, token: Token, errors: Vector[Diagnostic])
    case OnlySynthetics(state: ScannerState, errors: Vector[Diagnostic])

  private def scanOne(state: ScannerState, source: SourceFile): ScanResult =
    val (st1, crossedNewline, e1) = skipTrivia(state, source, crossedNewline = false, NoErrors)
    if st1.pos >= source.length then ScanResult.OnlySynthetics(st1, e1)
    else
      val (st2, e2) = if crossedNewline then handleNewline(st1, source) else (st1, NoErrors)
      val (st3, tok, e3) = scanToken(st2, source)
      ScanResult.RealToken(st3, tok, mergeErrors(e1, e2, e3))

  private inline def mergeErrors(
      a: Vector[Diagnostic],
      b: Vector[Diagnostic],
      c: Vector[Diagnostic]
  ): Vector[Diagnostic] =
    if a.isEmpty && b.isEmpty then c
    else if a.isEmpty && c.isEmpty then b
    else if b.isEmpty && c.isEmpty then a
    else a ++ b ++ c

  @tailrec
  private def skipTrivia(
      state: ScannerState,
      source: SourceFile,
      crossedNewline: Boolean,
      errors: Vector[Diagnostic]
  ): (ScannerState, Boolean, Vector[Diagnostic]) =
    if state.pos >= source.length then (state, crossedNewline, errors)
    else
      val c = source.content.charAt(state.pos)
      if c == ' ' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, errors)
      else if c == '\t' then
        // Tabs forbidden as whitespace per grammar v0.3 (line 160).
        val tabErr = err(
          "E001",
          Span(state.pos, state.pos + 1),
          "tab character in source — Fixed v0.4.5 requires spaces for indentation",
          Some("replace this tab with spaces")
        )
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, errors :+ tabErr)
      else if c == '\n' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline = true, errors)
      else if c == '\r' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, errors)
      else if c == '/' && peekChar(source, state.pos + 1) == '/' then
        val newPos = skipUntilNewline(source, state.pos)
        skipTrivia(state.copy(pos = newPos), source, crossedNewline, errors)
      else
        (state, crossedNewline, errors)

  @tailrec
  private def skipUntilNewline(source: SourceFile, i: Int): Int =
    if i >= source.length || source.content.charAt(i) == '\n' then i
    else skipUntilNewline(source, i + 1)

  // ---- Indentation ----

  // Called after crossing one or more '\n's. Decision order (top to bottom
  // — first match wins):
  //   1. dual-mode (= / -> / =>) + deeper indent  → push, INDENT (also
  //      re-engages off-side inside parens)
  //   2. inside un-engaged brackets               → suppressed, no token
  //   3. next line starts with leading-cont       → continuation, no token
  //   4. previous line ended with trailing-cont   → continuation, no token
  //   5. off-side: deeper + previous `:`          → push, INDENT
  //                shallower                      → pop levels, DEDENT(s)
  //                                                 (or collapse into
  //                                                 bracket-suppressed)
  //                same                           → NEWLINE
  private def handleNewline(
      state: ScannerState,
      source: SourceFile
  ): (ScannerState, Vector[Diagnostic]) =
    val pos = state.pos
    val lineStart = source.content.lastIndexOf('\n', pos - 1) + 1
    val newIndent = pos - lineStart
    val lastKind = state.lastKind

    val isDualMode =
      lastKind == TokenKind.Eq || lastKind == TokenKind.Arrow || lastKind == TokenKind.FatArrow
    if isDualMode && newIndent > state.currentIndent then
      val st = state
        .copy(indentStack = newIndent :: state.indentStack)
        .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
      (st, NoErrors)
    else if state.isInsideSuppressedBrackets then
      (state, NoErrors)
    else if lastKind != TokenKind.NoStart && lastKind != TokenKind.Newline
            && peekKindIsLeadingContinuation(source, state.pos)
    then
      (state, NoErrors)
    else if Tokens.trailingContinuationKinds.contains(lastKind) then
      (state, NoErrors)
    else
      val curIndent = state.currentIndent
      if newIndent > curIndent then
        val st =
          if lastKind == TokenKind.Colon then
            state
              .copy(indentStack = newIndent :: state.indentStack)
              .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
          else
            state.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
        (st, NoErrors)
      else if newIndent < curIndent then
        val st1 = popIndents(state, newIndent)
        if st1.isInsideSuppressedBrackets then (st1, NoErrors)
        else
          val errs =
            if st1.currentIndent != newIndent then
              Vector(err(
                "E002",
                Span(pos, pos + 1),
                s"inconsistent indentation; closing dedent does not match any opening indent (got $newIndent, stack=${st1.indentStack.reverse.mkString(",")})",
                Some("align this line to one of the enclosing block's indent levels")
              ))
            else NoErrors
          (st1.enqueue(Token(TokenKind.Newline, Span(pos, pos))), errs)
      else if lastKind != TokenKind.Newline then
        (state.enqueue(Token(TokenKind.Newline, Span(pos, pos))), NoErrors)
      else (state, NoErrors)

  @tailrec
  private def popIndents(state: ScannerState, newIndent: Int): ScannerState =
    state.indentStack match
      case top :: rest if rest.nonEmpty && top > newIndent =>
        val pos = state.pos
        val st1 = state
          .copy(indentStack = rest)
          .enqueue(Token(TokenKind.Dedent, Span(pos, pos)))
        popIndents(st1, newIndent)
      case _ => state

  // Stateless lookahead from `pos`. Skip horizontal whitespace, then check
  // whether the next word is a leading-continuation keyword or `->`.
  private def peekKindIsLeadingContinuation(source: SourceFile, pos: Int): Boolean =
    val content = source.content
    val len = source.length
    val i = skipSpacesAndTabs(content, pos, len)
    if i >= len then false
    else
      val c = content.charAt(i)
      if c == '-' && peekChar(source, i + 1) == '>' then true
      else if c.isLetter || c == '_' then
        val j = skipIdentPart(content, i, len)
        val word = content.substring(i, j)
        Tokens.keywords.get(word).exists(Tokens.leadingContinuationKinds.contains)
      else false

  @tailrec
  private def skipSpacesAndTabs(content: String, i: Int, len: Int): Int =
    if i >= len then i
    else
      val c = content.charAt(i)
      if c == ' ' || c == '\t' then skipSpacesAndTabs(content, i + 1, len)
      else i

  @tailrec
  private def skipIdentPart(content: String, i: Int, len: Int): Int =
    if i >= len then i
    else
      val c = content.charAt(i)
      if c.isLetterOrDigit || c == '_' then skipIdentPart(content, i + 1, len)
      else i

  // ---- scanToken: dispatch on first character ----

  private def scanToken(
      state: ScannerState,
      source: SourceFile
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val start = state.pos
    val c = source.content.charAt(start)
    c match
      case '(' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('(', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LParen, Span(start, st.pos)), NoErrors)
      case ')' =>
        val (st, errs) = popBracket(state.copy(pos = start + 1), '(')
        (st, Token(TokenKind.RParen, Span(start, st.pos)), errs)
      case '[' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('[', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBracket, Span(start, st.pos)), NoErrors)
      case ']' =>
        val (st, errs) = popBracket(state.copy(pos = start + 1), '[')
        (st, Token(TokenKind.RBracket, Span(start, st.pos)), errs)
      case '{' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('{', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBrace, Span(start, st.pos)), NoErrors)
      case '}' =>
        val (st, errs) = popBracket(state.copy(pos = start + 1), '{')
        (st, Token(TokenKind.RBrace, Span(start, st.pos)), errs)

      case ',' => single(state, start, TokenKind.Comma)
      case ';' => single(state, start, TokenKind.Semicolon)
      case '@' => single(state, start, TokenKind.At)
      case '_' if !isIdentStartAt(source, start + 1) =>
        single(state, start, TokenKind.Underscore)

      case ':' => single(state, start, TokenKind.Colon)
      case '.' => single(state, start, TokenKind.Dot)
      case '=' =>
        peekChar(source, start + 1) match
          case '>' => double(state, start, TokenKind.FatArrow)
          case '=' => double(state, start, TokenKind.EqEq)
          case _   => single(state, start, TokenKind.Eq)
      case '-' =>
        if peekChar(source, start + 1) == '>' then double(state, start, TokenKind.Arrow)
        else single(state, start, TokenKind.Minus)
      case '<' =>
        peekChar(source, start + 1) match
          case '-' => double(state, start, TokenKind.BackArrow)
          case '=' => double(state, start, TokenKind.Le)
          case _   => single(state, start, TokenKind.Lt)
      case '>' =>
        if peekChar(source, start + 1) == '=' then double(state, start, TokenKind.Ge)
        else single(state, start, TokenKind.Gt)
      case '!' =>
        if peekChar(source, start + 1) == '=' then double(state, start, TokenKind.Neq)
        else single(state, start, TokenKind.Bang)
      case '|' =>
        peekChar(source, start + 1) match
          case '>' => double(state, start, TokenKind.PipeForward)
          case '|' => double(state, start, TokenKind.OrOr)
          case _   => single(state, start, TokenKind.Pipe)
      case '&' =>
        if peekChar(source, start + 1) == '&' then double(state, start, TokenKind.AndAnd)
        else
          val st = state.copy(pos = start + 1)
          val e = err("E003", Span(start, start + 1), "stray `&` (did you mean `&&`?)")
          (st, Token(TokenKind.Error, Span(start, st.pos)), Vector(e))
      case '+' => single(state, start, TokenKind.Plus)
      case '*' => single(state, start, TokenKind.Star)
      case '/' => single(state, start, TokenKind.Slash)
      case '%' => single(state, start, TokenKind.Percent)

      case ch if isIdentStart(ch) => scanIdent(state, source, start)
      case ch if ch.isDigit       => scanNumber(state, source, start)
      case '"'                    => scanString(state, source, start)
      case '\''                   => scanChar(state, source, start)

      case other =>
        val st = state.copy(pos = start + 1)
        val e = err("E004", Span(start, start + 1), s"unexpected character ${describeChar(other)}")
        (st, Token(TokenKind.Error, Span(start, st.pos)), Vector(e))

  private def single(
      state: ScannerState, start: Int, kind: TokenKind
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val st = state.copy(pos = start + 1)
    (st, Token(kind, Span(start, st.pos)), NoErrors)

  private def double(
      state: ScannerState, start: Int, kind: TokenKind
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val st = state.copy(pos = start + 2)
    (st, Token(kind, Span(start, st.pos)), NoErrors)

  private def popBracket(
      state: ScannerState, expected: Char
  ): (ScannerState, Vector[Diagnostic]) =
    state.bracketStack match
      case (top, _) :: rest if top == expected =>
        (state.copy(bracketStack = rest), NoErrors)
      case _ =>
        val tag = state.bracketStack match
          case (top, _) :: _ => s"`$top`"
          case Nil           => "empty"
        val e = err(
          "E005",
          Span(state.pos - 1, state.pos),
          s"unmatched closing bracket — expected to close `$expected` but bracket stack is $tag"
        )
        (state, Vector(e))

  // ---- Identifiers, numbers, strings, chars ----

  private def scanIdent(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val end = skipIdentPart(source.content, start, source.length)
    val text = source.slice(start, end)
    val st = state.copy(pos = end)
    val tok = Tokens.keywords.get(text) match
      case Some(kw) => Token(kw, Span(start, end), text)
      case None =>
        val kind =
          if text.charAt(0).isUpper then TokenKind.UpperIdent
          else TokenKind.LowerIdent
        Token(kind, Span(start, end), text)
    (st, tok, NoErrors)

  private def scanNumber(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val intEnd = skipDigits(source, start)
    // Float? '.' followed by another digit. Don't eat `5.foo` — that's
    // an integer 5 followed by `.foo`, not a float "5." followed by `foo`.
    val sawDot =
      intEnd < source.length
        && source.content.charAt(intEnd) == '.'
        && intEnd + 1 < source.length
        && source.content.charAt(intEnd + 1).isDigit
    if sawDot then
      val fracEnd = skipDigits(source, intEnd + 1)
      val st = state.copy(pos = fracEnd)
      (st, Token(TokenKind.FloatLit, Span(start, fracEnd), source.slice(start, fracEnd)), NoErrors)
    else
      val st = state.copy(pos = intEnd)
      (st, Token(TokenKind.IntLit, Span(start, intEnd), source.slice(start, intEnd)), NoErrors)

  @tailrec
  private def skipDigits(source: SourceFile, i: Int): Int =
    if i >= source.length || !source.content.charAt(i).isDigit then i
    else skipDigits(source, i + 1)

  // Result of scanning a quoted (string or char) body. `closed = true` for
  // both the normal terminator-found case AND the bail-out cases (newline
  // mid-literal, EOF mid-literal); the EOF variant is detected by callers
  // via `pos == source.length` after the loop.
  private final case class QuotedBody(
      pos: Int,
      lexeme: String,
      closed: Boolean,
      newErrors: Vector[Diagnostic]
  )

  private def scanQuotedBody(
      source: SourceFile,
      start: Int,
      i0: Int,
      terminator: Char,
      escapeChar: (Char, Int) => Either[(String, Span, String), Char],
      unterminatedCode: String,
      unterminatedLineMessage: String,
      unterminatedLineSuggestion: Option[String]
  ): QuotedBody =
    @tailrec
    def loop(i: Int, buf: StringBuilder, errs: Vector[Diagnostic]): QuotedBody =
      if i >= source.length then QuotedBody(i, buf.toString, closed = false, errs)
      else
        val c = source.content.charAt(i)
        if c == terminator then
          QuotedBody(i + 1, buf.toString, closed = true, errs)
        else if c == '\\' && i + 1 < source.length then
          val esc = source.content.charAt(i + 1)
          escapeChar(esc, i) match
            case Right(translated) =>
              buf += translated
              loop(i + 2, buf, errs)
            case Left((code, span, msg)) =>
              buf += esc
              loop(i + 2, buf, errs :+ Diagnostic(Severity.Error, code, span, msg))
        else if c == '\n' then
          val err = Diagnostic(
            Severity.Error,
            unterminatedCode,
            Span(start, i),
            unterminatedLineMessage,
            unterminatedLineSuggestion
          )
          QuotedBody(i, buf.toString, closed = true, errs :+ err)
        else
          buf += c
          loop(i + 1, buf, errs)
    loop(i0, new StringBuilder, Vector.empty)

  private def stdEscape(esc: Char, atPos: Int): Either[(String, Span, String), Char] =
    esc match
      case 'n'   => Right('\n')
      case 't'   => Right('\t')
      case 'r'   => Right('\r')
      case '\\'  => Right('\\')
      case '"'   => Right('"')
      case '\''  => Right('\'')
      case '0'   => Right(' ')
      case other =>
        Left(("E006", Span(atPos, atPos + 2), s"unknown escape sequence `\\$other`"))

  private def scanString(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val body = scanQuotedBody(
      source, start, i0 = start + 1, terminator = '"',
      escapeChar = stdEscape,
      unterminatedCode = "E007",
      unterminatedLineMessage = "unterminated string literal — closing `\"` missing on this line",
      unterminatedLineSuggestion = Some("string literals must close on the same line in v0.4.5")
    )
    val errs =
      if body.closed then body.newErrors
      else body.newErrors :+ err(
        "E007", Span(start, body.pos), "unterminated string literal at end of input"
      )
    val st = state.copy(pos = body.pos)
    (st, Token(TokenKind.StringLit, Span(start, body.pos), body.lexeme), errs)

  private def scanChar(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token, Vector[Diagnostic]) =
    val body = scanQuotedBody(
      source, start, i0 = start + 1, terminator = '\'',
      escapeChar = stdEscape,
      unterminatedCode = "E008",
      unterminatedLineMessage = "unterminated char literal",
      unterminatedLineSuggestion = None
    )
    val errs1 =
      if body.closed then body.newErrors
      else body.newErrors :+ err(
        "E008", Span(start, body.pos), "unterminated char literal at end of input"
      )
    val errs =
      if body.lexeme.length != 1 then
        errs1 :+ err(
          "E009", Span(start, body.pos),
          s"char literal must contain exactly one character (got ${body.lexeme.length})"
        )
      else errs1
    val st = state.copy(pos = body.pos)
    (st, Token(TokenKind.CharLit, Span(start, body.pos), body.lexeme), errs)

  // ---- Char-class helpers ----

  private def peekChar(source: SourceFile, i: Int): Char =
    if i < source.length then source.content.charAt(i) else ' '

  private def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'

  private def isIdentStartAt(source: SourceFile, i: Int): Boolean =
    i < source.length && isIdentStart(source.content.charAt(i))

  private def describeChar(c: Char): String =
    if c < ' ' then f"\\u${c.toInt}%04x"
    else s"`$c`"

end Scanner

// Pull-style adapter. One `var state` rotated per `nextToken` — the
// bounded boundary mutation. (A pure `LazyList.scanLeft` adapter cost
// ~70 ns/token from the multi-layer iterator wrapping; this `var`
// recovers it without touching the lexing core.)
final class Scanner(val source: SourceFile, val reporter: Reporter):
  import Scanner.{ScannerState, initialState, step}

  private var state: ScannerState = initialState

  def nextToken(): Token =
    val (next, tok, errs) = step(state, source)
    if errs.nonEmpty then
      errs.foreach(d => reporter.error(d.code, d.span, d.message, d.suggestion))
    state = next
    tok

  def tokenize(): Seq[Token] =
    @tailrec
    def loop(acc: Vector[Token]): Vector[Token] =
      val t = nextToken()
      val next = acc :+ t
      if t.kind == TokenKind.Eof then next else loop(next)
    loop(Vector.empty)

end Scanner
