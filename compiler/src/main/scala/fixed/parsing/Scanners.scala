package fixed.parsing

import scala.collection.immutable.Queue
import scala.annotation.tailrec

import fixed.util.{Reporter, SourceFile, Span}

// Single-pass lexer. The `Scanner` object holds the immutable state
// (`ScannerState`, `step`); the `class Scanner` wrapper below adapts it
// to the parser's pull-style `nextToken()` API. State and token threading
// are pure; the only side effect is error emission via the supplied
// `Reporter`. Indentation, line-continuation, and bracket-suppression
// rules: see `spec/syntax_grammar.ebnf` lines 156–217.

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

  // ---- Public API ----

  def tokenize(source: SourceFile, reporter: Reporter): Seq[Token] =
    new Scanner(source, reporter).tokenize()

  // ---- Transitions ----

  // Advance by one token. Errors detected mid-step are pushed directly to
  // the supplied `Reporter` — that's the one impurity the scanner has, and
  // it's bounded: state and token threading remain pure, only diagnostics
  // flow through the reporter side channel. Idempotent at EOF. Each branch
  // fuses pending+lastKind into one state.copy (ScannerState is the
  // dominant allocator per JFR; doubling up halves per-step copies).
  def step(
      state: ScannerState,
      source: SourceFile,
      reporter: Reporter
  ): (ScannerState, Token) =
    state.pending.dequeueOption match
      case Some((tok, rest)) =>
        (state.copy(pending = rest, lastKind = tok.kind), tok)
      case None =>
        scanOne(state, source, reporter) match
          case ScanResult.RealToken(st1, tok) =>
            // Synthetic tokens (INDENT/DEDENT/NEWLINE) drain ahead of the
            // real one; re-queue the real token at the tail.
            if st1.pending.nonEmpty then
              val synth = st1.pending.head
              val rest = st1.pending.tail
              (st1.copy(pending = rest.enqueue(tok), lastKind = synth.kind), synth)
            else
              (st1.copy(lastKind = tok.kind), tok)
          case ScanResult.OnlySynthetics(st1) =>
            st1.pending.dequeueOption match
              case Some((tok, rest)) =>
                (st1.copy(pending = rest, lastKind = tok.kind), tok)
              case None =>
                if !st1.eofEmitted then step(closeIndentsAndQueueEof(st1), source, reporter)
                else (st1, Token(TokenKind.Eof, Span(st1.pos, st1.pos)))

  private def closeIndentsAndQueueEof(state: ScannerState): ScannerState =
    val pos = state.pos
    val dedentCount = state.indentStack.length - 1
    val poppedStack = state.indentStack.drop(dedentCount)
    val dedents = Vector.fill(dedentCount)(Token(TokenKind.Dedent, Span(pos, pos)))
    val withDedents = dedents.foldLeft(state.pending)(_ enqueue _)
    val withEof = withDedents.enqueue(Token(TokenKind.Eof, Span(pos, pos)))
    state.copy(indentStack = poppedStack, pending = withEof, eofEmitted = true)

  private enum ScanResult:
    case RealToken(state: ScannerState, token: Token)
    case OnlySynthetics(state: ScannerState)

  private def scanOne(
      state: ScannerState,
      source: SourceFile,
      reporter: Reporter
  ): ScanResult =
    val (st1, crossedNewline) = skipTrivia(state, source, crossedNewline = false, reporter)
    if st1.pos >= source.length then ScanResult.OnlySynthetics(st1)
    else
      val st2 = if crossedNewline then handleNewline(st1, source, reporter) else st1
      val (st3, tok) = scanToken(st2, source, reporter)
      ScanResult.RealToken(st3, tok)

  @tailrec
  private def skipTrivia(
      state: ScannerState,
      source: SourceFile,
      crossedNewline: Boolean,
      reporter: Reporter
  ): (ScannerState, Boolean) =
    if state.pos >= source.length then (state, crossedNewline)
    else
      val c = source.content.charAt(state.pos)
      if c == ' ' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, reporter)
      else if c == '\t' then
        // Tabs forbidden as whitespace per grammar v0.3 (line 160).
        reporter.error(
          "E001",
          Span(state.pos, state.pos + 1),
          "tab character in source — Fixed v0.4.5 requires spaces for indentation",
          Some("replace this tab with spaces")
        )
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, reporter)
      else if c == '\n' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline = true, reporter)
      else if c == '\r' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline, reporter)
      else if c == '/' && peekChar(source, state.pos + 1) == '/' then
        val newPos = skipUntilNewline(source, state.pos)
        skipTrivia(state.copy(pos = newPos), source, crossedNewline, reporter)
      else
        (state, crossedNewline)

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
      source: SourceFile,
      reporter: Reporter
  ): ScannerState =
    val pos = state.pos
    val lineStart = source.content.lastIndexOf('\n', pos - 1) + 1
    val newIndent = pos - lineStart
    val lastKind = state.lastKind

    val isDualMode =
      lastKind == TokenKind.Eq || lastKind == TokenKind.Arrow || lastKind == TokenKind.FatArrow
    if isDualMode && newIndent > state.currentIndent then
      state
        .copy(indentStack = newIndent :: state.indentStack)
        .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
    else if state.isInsideSuppressedBrackets then
      state
    else if lastKind != TokenKind.NoStart && lastKind != TokenKind.Newline
            && peekKindIsLeadingContinuation(source, state.pos)
    then
      state
    else if Tokens.trailingContinuationKinds.contains(lastKind) then
      state
    else
      val curIndent = state.currentIndent
      if newIndent > curIndent then
        if lastKind == TokenKind.Colon then
          state
            .copy(indentStack = newIndent :: state.indentStack)
            .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
        else
          state.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
      else if newIndent < curIndent then
        val st1 = popIndents(state, newIndent)
        if st1.isInsideSuppressedBrackets then st1
        else
          if st1.currentIndent != newIndent then
            reporter.error(
              "E002",
              Span(pos, pos + 1),
              s"inconsistent indentation; closing dedent does not match any opening indent (got $newIndent, stack=${st1.indentStack.reverse.mkString(",")})",
              Some("align this line to one of the enclosing block's indent levels")
            )
          st1.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
      else if lastKind != TokenKind.Newline then
        state.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
      else state

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
      source: SourceFile,
      reporter: Reporter
  ): (ScannerState, Token) =
    val start = state.pos
    val c = source.content.charAt(start)
    c match
      case '(' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('(', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LParen, Span(start, st.pos)))
      case ')' =>
        val st = popBracket(state.copy(pos = start + 1), '(', reporter)
        (st, Token(TokenKind.RParen, Span(start, st.pos)))
      case '[' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('[', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBracket, Span(start, st.pos)))
      case ']' =>
        val st = popBracket(state.copy(pos = start + 1), '[', reporter)
        (st, Token(TokenKind.RBracket, Span(start, st.pos)))
      case '{' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('{', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBrace, Span(start, st.pos)))
      case '}' =>
        val st = popBracket(state.copy(pos = start + 1), '{', reporter)
        (st, Token(TokenKind.RBrace, Span(start, st.pos)))

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
          reporter.error("E003", Span(start, start + 1), "stray `&` (did you mean `&&`?)")
          val st = state.copy(pos = start + 1)
          (st, Token(TokenKind.Error, Span(start, st.pos)))
      case '+' => single(state, start, TokenKind.Plus)
      case '*' => single(state, start, TokenKind.Star)
      case '/' => single(state, start, TokenKind.Slash)
      case '%' => single(state, start, TokenKind.Percent)

      case ch if isIdentStart(ch) => scanIdent(state, source, start)
      case ch if ch.isDigit       => scanNumber(state, source, start)
      case '"'                    => scanString(state, source, start, reporter)
      case '\''                   => scanChar(state, source, start, reporter)

      case other =>
        reporter.error("E004", Span(start, start + 1), s"unexpected character ${describeChar(other)}")
        val st = state.copy(pos = start + 1)
        (st, Token(TokenKind.Error, Span(start, st.pos)))

  private def single(
      state: ScannerState, start: Int, kind: TokenKind
  ): (ScannerState, Token) =
    val st = state.copy(pos = start + 1)
    (st, Token(kind, Span(start, st.pos)))

  private def double(
      state: ScannerState, start: Int, kind: TokenKind
  ): (ScannerState, Token) =
    val st = state.copy(pos = start + 2)
    (st, Token(kind, Span(start, st.pos)))

  private def popBracket(
      state: ScannerState, expected: Char, reporter: Reporter
  ): ScannerState =
    state.bracketStack match
      case (top, _) :: rest if top == expected =>
        state.copy(bracketStack = rest)
      case _ =>
        val tag = state.bracketStack match
          case (top, _) :: _ => s"`$top`"
          case Nil           => "empty"
        reporter.error(
          "E005",
          Span(state.pos - 1, state.pos),
          s"unmatched closing bracket — expected to close `$expected` but bracket stack is $tag"
        )
        state

  // ---- Identifiers, numbers, strings, chars ----

  private def scanIdent(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token) =
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
    (st, tok)

  private def scanNumber(
      state: ScannerState, source: SourceFile, start: Int
  ): (ScannerState, Token) =
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
      (st, Token(TokenKind.FloatLit, Span(start, fracEnd), source.slice(start, fracEnd)))
    else
      val st = state.copy(pos = intEnd)
      (st, Token(TokenKind.IntLit, Span(start, intEnd), source.slice(start, intEnd)))

  @tailrec
  private def skipDigits(source: SourceFile, i: Int): Int =
    if i >= source.length || !source.content.charAt(i).isDigit then i
    else skipDigits(source, i + 1)

  // Result of scanning a quoted (string or char) body. `closed = true` for
  // both the normal terminator-found case AND the bail-out cases (newline
  // mid-literal). EOF mid-literal returns `closed = false` so callers can
  // emit the "at end of input" variant of the error.
  private final case class QuotedBody(pos: Int, lexeme: String, closed: Boolean)

  // The StringBuilder is local and never escapes — it's used purely as a
  // lexeme accumulator for the loop below, then frozen via `.toString`.
  // Equivalent to `(content.charAt(_) :: acc).reverse.mkString` but
  // avoids the per-character List cell allocation on the hot path.
  private def scanQuotedBody(
      source: SourceFile,
      start: Int,
      i0: Int,
      terminator: Char,
      reporter: Reporter,
      unterminatedCode: String,
      unterminatedLineMessage: String,
      unterminatedLineSuggestion: Option[String]
  ): QuotedBody =
    @tailrec
    def loop(i: Int, buf: StringBuilder): QuotedBody =
      if i >= source.length then QuotedBody(i, buf.toString, closed = false)
      else
        val c = source.content.charAt(i)
        if c == terminator then
          QuotedBody(i + 1, buf.toString, closed = true)
        else if c == '\\' && i + 1 < source.length then
          val esc = source.content.charAt(i + 1)
          stdEscape(esc) match
            case Some(translated) =>
              buf += translated
              loop(i + 2, buf)
            case None =>
              reporter.error("E006", Span(i, i + 2), s"unknown escape sequence `\\$esc`")
              buf += esc
              loop(i + 2, buf)
        else if c == '\n' then
          reporter.error(
            unterminatedCode,
            Span(start, i),
            unterminatedLineMessage,
            unterminatedLineSuggestion
          )
          QuotedBody(i, buf.toString, closed = true)
        else
          buf += c
          loop(i + 1, buf)
    loop(i0, new StringBuilder)

  private def stdEscape(esc: Char): Option[Char] = esc match
    case 'n'  => Some('\n')
    case 't'  => Some('\t')
    case 'r'  => Some('\r')
    case '\\' => Some('\\')
    case '"'  => Some('"')
    case '\'' => Some('\'')
    case '0'  => Some(' ')
    case _    => None

  private def scanString(
      state: ScannerState, source: SourceFile, start: Int, reporter: Reporter
  ): (ScannerState, Token) =
    val body = scanQuotedBody(
      source, start, i0 = start + 1, terminator = '"', reporter = reporter,
      unterminatedCode = "E007",
      unterminatedLineMessage = "unterminated string literal — closing `\"` missing on this line",
      unterminatedLineSuggestion = Some("string literals must close on the same line in v0.4.5")
    )
    if !body.closed then
      reporter.error("E007", Span(start, body.pos), "unterminated string literal at end of input")
    val st = state.copy(pos = body.pos)
    (st, Token(TokenKind.StringLit, Span(start, body.pos), body.lexeme))

  private def scanChar(
      state: ScannerState, source: SourceFile, start: Int, reporter: Reporter
  ): (ScannerState, Token) =
    val body = scanQuotedBody(
      source, start, i0 = start + 1, terminator = '\'', reporter = reporter,
      unterminatedCode = "E008",
      unterminatedLineMessage = "unterminated char literal",
      unterminatedLineSuggestion = None
    )
    if !body.closed then
      reporter.error("E008", Span(start, body.pos), "unterminated char literal at end of input")
    if body.lexeme.length != 1 then
      reporter.error(
        "E009", Span(start, body.pos),
        s"char literal must contain exactly one character (got ${body.lexeme.length})"
      )
    val st = state.copy(pos = body.pos)
    (st, Token(TokenKind.CharLit, Span(start, body.pos), body.lexeme))

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

// Pull-style adapter. The token stream is produced once at construction
// time by a pure `Iterator.unfold` over `ScannerState`, materialised
// eagerly into an immutable `Vector[Token]`; an `Iterator[Token]` over
// that Vector serves the parser's pull API. The iterator is the *one
// documented boundary mutation* — it owns Scala's internal cursor and is
// required to satisfy the `def nextToken(): Token` contract without
// redesigning the Parser. Everything else (trivia extraction, table
// construction) is a pure fold over the same Vector.
//
// Eager materialisation keeps reporter side effects firing exactly once
// (the parser will drive to EOF anyway, so laziness buys nothing) and
// keeps both `tokenize` and `triviaTable` reading from one shared source
// of truth.
//
// Trivia retention (Phase 2.1 M4): real tokens (those with non-empty
// spans) carry the gap information needed for trivia. `triviaTable`
// folds over the same Vector, walking each `[prevEnd, start)` gap for
// `//` comments and blank-line runs. Synthetic tokens (INDENT / DEDENT
// / NEWLINE / EOF) have zero-length spans and are filtered out — they're
// structural markers, not source positions.
final class Scanner(
    val source: SourceFile,
    val reporter: Reporter,
    captureTrivia: Boolean = true
):
  import Scanner.{ScannerState, initialState, step}

  // Pure unfold over ScannerState, materialised eagerly. The fold
  // terminates after emitting the first EOF (underlying `step` is
  // idempotent at EOF but we don't want an infinite stream of them).
  private val tokens: Vector[Token] =
    Iterator
      .unfold[Token, Option[ScannerState]](Some(initialState)) {
        case None => None
        case Some(st) =>
          val (next, tok) = step(st, source, reporter)
          if tok.kind == TokenKind.Eof then Some((tok, None))
          else Some((tok, Some(next)))
      }
      .toVector

  // The single boundary mutation: Scala's `Iterator` owns an internal
  // cursor over `tokens`. Once EOF is consumed we synthesise further
  // EOFs (idempotent at end, matching the previous `var state` semantics).
  private val iter: Iterator[Token] = tokens.iterator

  def nextToken(): Token =
    if iter.hasNext then iter.next()
    else Token(TokenKind.Eof, Span(source.length, source.length))

  def tokenize(): Seq[Token] = tokens

  /** Build the trivia table from the recorded real-token spans. Walks
    * each gap once; only allocates per-event when a `//` comment or
    * blank-line run is actually present. Returns `TriviaTable.empty`
    * when this scanner was constructed with `captureTrivia = false`. */
  def triviaTable: TriviaTable =
    if !captureTrivia then TriviaTable.empty
    else
      val content = source.content
      // Fold once over the memoized token stream. Real tokens are the
      // ones with non-empty spans; synthetics (zero-length) are skipped.
      // `prevEnd` threads through; `acc` is the immutable result map.
      val (_, table) = tokens.foldLeft((0, Map.empty[Int, List[Trivia]])) {
        case ((prevEnd, acc), tok) =>
          val s = tok.span.start
          val e = tok.span.end
          if s >= e then (prevEnd, acc)  // synthetic
          else if s - prevEnd >= 2 then
            val events = collectTrivia(content, prevEnd, s, Nil)
            val acc1 = if events.nonEmpty then acc.updated(s, events) else acc
            (e, acc1)
          else (e, acc)
      }
      TriviaTable.from(table)

  // Walk `[from, end)` of `content` collecting `//`-comments and runs
  // of two or more `\n`s. Inline whitespace is ignored (recoverable
  // from token spans). Single newlines are ignored — they're already
  // represented by NEWLINE / DEDENT tokens. Tail-recursive build with
  // a List accumulator reversed at the boundary; events are pushed in
  // discovery order so the post-reverse output is source-order.
  @tailrec
  private def collectTrivia(
      content: String,
      i: Int,
      end: Int,
      acc: List[Trivia]
  ): List[Trivia] =
    if i >= end then acc.reverse
    else
      val c = content.charAt(i)
      if c == '/' && i + 1 < end && content.charAt(i + 1) == '/' then
        val cmtEnd = scanLineCommentEnd(content, i, end)
        val ev = Trivia.LineComment(Span(i, cmtEnd), content.substring(i, cmtEnd))
        collectTrivia(content, cmtEnd, end, ev :: acc)
      else if c == '\n' then
        val nlEnd = scanNewlineRunEnd(content, i, end)
        val nlCount = nlEnd - i
        // Blank lines = newlines minus the one already represented by
        // the NEWLINE / DEDENT token. >= 1 blank line is worth recording.
        val acc1 =
          if nlCount >= 2 then Trivia.BlankLines(Span(i, nlEnd), nlCount - 1) :: acc
          else acc
        collectTrivia(content, nlEnd, end, acc1)
      else
        collectTrivia(content, i + 1, end, acc)

  @tailrec
  private def scanLineCommentEnd(content: String, i: Int, end: Int): Int =
    if i >= end || content.charAt(i) == '\n' then i
    else scanLineCommentEnd(content, i + 1, end)

  @tailrec
  private def scanNewlineRunEnd(content: String, i: Int, end: Int): Int =
    if i >= end || content.charAt(i) != '\n' then i
    else scanNewlineRunEnd(content, i + 1, end)

end Scanner
