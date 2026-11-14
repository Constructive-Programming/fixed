package fixed.parsing

import scala.annotation.tailrec
import scala.collection.immutable.Queue

import fixed.util.{DiagCode, Reporter, SourceFile, Span}

/** Single-pass lexer. The `Scanner` object holds the pure-functional
  * state and `step`; the `class Scanner` wrapper below adapts it to the
  * parser's pull `nextToken()` API. The only impurity is error emission
  * via the supplied `Reporter`. Indentation, line-continuation, and
  * bracket-suppression rules are normative in `spec/syntax_grammar.ebnf`.
  */

object Scanner:

  /** An open bracket and the indent-stack depth at which it opened. A case
    * class rather than a `(Char, Int)` tuple so neither field is boxed.
    */
  final case class OpenBracket(char: Char, depth: Int)

  /** `skipTrivia`'s result; a case class rather than `(ScannerState, Boolean)`
    * so the flag is not boxed.
    */
  final case class Skipped(state: ScannerState, crossedNewline: Boolean)

  final case class ScannerState(
      pos: Int,
      indentStack: List[Int],
      bracketStack: List[OpenBracket],
      /** TokenKind.NoStart before first emit */
      lastKind: TokenKind,
      eofEmitted: Boolean,
      pending: Queue[Token]
  ):
    /** True iff we're inside a bracket whose recorded indent depth still
      * equals `indentStack.length` — no body has been engaged since the
      * bracket opened, so off-side rule is suppressed.
      */
    def isInsideSuppressedBrackets: Boolean = bracketStack match
      case OpenBracket(_, depth) :: _ => indentStack.length == depth
      case Nil                        => false

    def currentIndent: Int = indentStack.head

    def enqueue(t: Token): ScannerState = copy(pending = pending.enqueue(t))
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

  def tokenize(source: SourceFile, reporter: Reporter): Seq[Token] =
    new Scanner(source, reporter).tokenize()

  /** Advance by one token. Idempotent at EOF.
    *
    * Allocation invariant: code paths that end with a real token return
    * a state whose `lastKind` already matches the token's kind —
    * `scanToken` folds it into its own one `state.copy`, so `step`
    * returns that state directly without a wrapping copy. The
    * synthetic-pending paths still copy because the emitted token
    * (a synthetic INDENT/DEDENT/NEWLINE) differs from the one just
    * scanned.
    */
  def step(
      state: ScannerState,
      source: SourceFile,
      reporter: Reporter
  ): (ScannerState, Token) =
    state.pending.dequeueOption match
      case Some((tok, rest)) =>
        (state.copy(pending = rest, lastKind = tok.kind), tok)
      case None =>
        /** Field reads, not `val Skipped(a, b) = …`: that pattern desugars through
          * a Tuple2 and would box the Boolean again.
          */
        val skipped = skipTrivia(state, source, crossedNewline = false, reporter)
        val st1 = skipped.state
        val crossedNewline = skipped.crossedNewline
        if st1.pos >= source.length then
          st1.pending.dequeueOption match
            case Some((tok, rest)) =>
              (st1.copy(pending = rest, lastKind = tok.kind), tok)
            case None =>
              if !st1.eofEmitted then step(closeIndentsAndQueueEof(st1), source, reporter)
              else (st1.copy(lastKind = TokenKind.Eof), Token.Eof(Span(st1.pos, st1.pos)))
        else
          val st2 = if crossedNewline then handleNewline(st1, source, reporter) else st1
          val (st3, tok) = scanToken(st2, source, reporter)
          if st3.pending.nonEmpty then
            /** A synthetic INDENT/DEDENT/NEWLINE was queued during indent
              * handling; emit it now and re-queue the real token at the tail.
              */
            val synth = st3.pending.head
            val rest = st3.pending.tail
            (st3.copy(pending = rest.enqueue(tok), lastKind = synth.kind), synth)
          else
            (st3, tok)

  private def closeIndentsAndQueueEof(state: ScannerState): ScannerState =
    val pos = state.pos
    val dedentCount = state.indentStack.length - 1
    val poppedStack = state.indentStack.drop(dedentCount)
    val dedents = Vector.fill(dedentCount)(Token.Dedent(Span(pos, pos)))
    val withDedents = dedents.foldLeft(state.pending)(_ enqueue _)
    val withEof = withDedents.enqueue(Token.Eof(Span(pos, pos)))
    state.copy(indentStack = poppedStack, pending = withEof, eofEmitted = true)

  /** Trivia consumption only mutates `pos`; `advanceTrivia` recurses
    * over a primitive Int and packs `(pos, crossedNewline)` into a Long
    * so the loop allocates nothing. One `state.copy` happens at the
    * boundary if pos changed.
    */
  private def skipTrivia(
      state: ScannerState,
      source: SourceFile,
      crossedNewline: Boolean,
      reporter: Reporter
  ): Skipped =
    val packed = advanceTrivia(state.pos, source, reporter, if crossedNewline then 1 else 0)
    val newPos = (packed & 0xffffffffL).toInt
    val crossed = (packed >>> 32) != 0
    if newPos == state.pos then Skipped(state, crossed)
    else Skipped(state.copy(pos = newPos), crossed)

  @tailrec
  private def advanceTrivia(
      pos: Int,
      source: SourceFile,
      reporter: Reporter,
      crossed: Int
  ): Long =
    if pos >= source.length then (crossed.toLong << 32) | (pos.toLong & 0xffffffffL)
    else
      val c = source.content.charAt(pos)
      if c == ' ' then advanceTrivia(pos + 1, source, reporter, crossed)
      else if c == '\t' then
        /** Tabs are forbidden as whitespace per the grammar. */
        reporter.error(
          DiagCode.E001,
          Span(pos, pos + 1),
          "tab character in source — Fixed v0.4.5 requires spaces for indentation",
          Some("replace this tab with spaces")
        )
        advanceTrivia(pos + 1, source, reporter, crossed)
      else if c == '\n' then advanceTrivia(pos + 1, source, reporter, 1)
      else if c == '\r' then advanceTrivia(pos + 1, source, reporter, crossed)
      else if c == '/' && peekChar(source, pos + 1) == '/' then
        advanceTrivia(skipUntilNewline(source, pos), source, reporter, crossed)
      else (crossed.toLong << 32) | (pos.toLong & 0xffffffffL)

  @tailrec
  private def skipUntilNewline(source: SourceFile, i: Int): Int =
    if i >= source.length || source.content.charAt(i) == '\n' then i
    else skipUntilNewline(source, i + 1)

  /** Called after crossing one or more '\n's. Decision order (top to bottom
    * — first match wins):
    *   1. dual-mode (= / -> / =>) + deeper indent  → push, INDENT (also
    *      re-engages off-side inside parens)
    *   2. inside un-engaged brackets               → suppressed, no token
    *   3. next line starts with leading-cont       → continuation, no token
    *   4. previous line ended with trailing-cont   → continuation, no token
    *   5. off-side: deeper + previous `:`          → push, INDENT
    *                shallower                      → pop levels, DEDENT(s)
    *                                                 (or collapse into
    *                                                 bracket-suppressed)
    *                same                           → NEWLINE
    */
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
      lastKind == TokenKind.Eq || lastKind == TokenKind.Arrow
        || lastKind == TokenKind.FatArrow || lastKind == TokenKind.Colon

    /** Opens every INDENT, including the `:` block case. */
    if isDualMode && newIndent > state.currentIndent then
      state
        .copy(indentStack = newIndent :: state.indentStack)
        .enqueue(Token.Indent(Span(pos, pos)))
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

      /** A deeper line after `:` (or `=`/`->`/`=>`) already opened an INDENT
        * above, so here a deeper line is a plain continuation NEWLINE.
        */
      if newIndent > curIndent then state.enqueue(Token.Newline(Span(pos, pos)))
      else if newIndent < curIndent then
        val st1 = popIndents(state, newIndent)
        if st1.isInsideSuppressedBrackets then st1
        else
          if st1.currentIndent != newIndent then
            reporter.error(
              DiagCode.E002,
              Span(pos, pos + 1),
              s"inconsistent indentation; closing dedent does not match any opening indent (got $newIndent, stack=${st1.indentStack.reverse.mkString(",")})",
              Some("align this line to one of the enclosing block's indent levels")
            )
          st1.enqueue(Token.Newline(Span(pos, pos)))
      else if lastKind != TokenKind.Newline then
        state.enqueue(Token.Newline(Span(pos, pos)))
      else state

  @tailrec
  private def popIndents(state: ScannerState, newIndent: Int): ScannerState =
    state.indentStack match
      case top :: rest if rest.nonEmpty && top > newIndent =>
        val pos = state.pos
        val st1 = state
          .copy(indentStack = rest)
          .enqueue(Token.Dedent(Span(pos, pos)))
        popIndents(st1, newIndent)
      case _ => state

  /** Stateless lookahead from `pos`. Skip horizontal whitespace, then check
    * whether the next significant character starts a leading-continuation
    * token: `->`, `.`, `|>`, `+`, or a keyword in
    * `leadingContinuationKinds`.
    */
  private def peekKindIsLeadingContinuation(source: SourceFile, pos: Int): Boolean =
    val content = source.content
    val len = source.length
    val i = skipWhile(content, pos, len)(isSpaceOrTab)
    if i >= len then false
    else
      val c = content.charAt(i)
      if c == '-' && peekChar(source, i + 1) == '>' then true
      else if c == '.' then true
      else if c == '|' && peekChar(source, i + 1) == '>' then true
      else if c == '+' then true
      else if c.isLetter || c == '_' then
        val j = skipWhile(content, i, len)(isIdentPart)
        Tokens.keywordAt(content, i, j).exists(kw => Tokens.leadingContinuationKinds.contains(kw.kind))
      else false

  /** First index at or after `i0` (capped at `len`) whose char fails `p`. */
  private inline def skipWhile(content: String, i0: Int, len: Int)(inline p: Char => Boolean): Int =
    @tailrec def loop(i: Int): Int = if i < len && p(content.charAt(i)) then loop(i + 1) else i
    loop(i0)

  private inline def isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'
  private inline def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  private def scanToken(
      state: ScannerState,
      source: SourceFile,
      reporter: Reporter
  ): (ScannerState, Token) =
    val start = state.pos
    source.content.charAt(start) match
      case '('                                       => openBracket(state, start, '(', Token.LParen(_))
      case ')'                                       => closeBracket(state, start, Token.RParen(_), '(', reporter)
      case '['                                       => openBracket(state, start, '[', Token.LBracket(_))
      case ']'                                       => closeBracket(state, start, Token.RBracket(_), '[', reporter)
      case '{'                                       => openBracket(state, start, '{', Token.LBrace(_))
      case '}'                                       => closeBracket(state, start, Token.RBrace(_), '{', reporter)
      case '_' if !isIdentStartAt(source, start + 1) => single(state, start)(Token.Underscore(_))
      case ch if isIdentStart(ch)                    => scanIdent(state, source, start)
      case ch if ch.isDigit                          => scanNumber(state, source, start)
      case '"'                                       => scanQuoted(state, source, start, reporter, Quote.Str)
      case '\''                                      => scanQuoted(state, source, start, reporter, Quote.Chr)
      case _                                         => scanOperator(state, source, start, reporter)

  /** Punctuation and operators. Split out of `scanToken` to keep that method
    * small enough for the JIT to compile and inline it well.
    */
  private def scanOperator(
      state: ScannerState,
      source: SourceFile,
      start: Int,
      reporter: Reporter
  ): (ScannerState, Token) =
    source.content.charAt(start) match
      case ',' => single(state, start)(Token.Comma(_))
      case ';' => single(state, start)(Token.Semicolon(_))
      case '@' => single(state, start)(Token.At(_))
      case ':' => single(state, start)(Token.Colon(_))
      case '.' => single(state, start)(Token.Dot(_))
      case '=' =>
        peekChar(source, start + 1) match
          case '>' => double(state, start)(Token.FatArrow(_))
          case '=' => double(state, start)(Token.EqEq(_))
          case _   => single(state, start)(Token.Eq(_))
      case '-' =>
        if peekChar(source, start + 1) == '>' then double(state, start)(Token.Arrow(_))
        else single(state, start)(Token.Minus(_))
      case '<' =>
        peekChar(source, start + 1) match
          case '-' => double(state, start)(Token.BackArrow(_))
          case '=' => double(state, start)(Token.Le(_))
          case _   => single(state, start)(Token.Lt(_))
      case '>' =>
        if peekChar(source, start + 1) == '=' then double(state, start)(Token.Ge(_))
        else single(state, start)(Token.Gt(_))
      case '!' =>
        if peekChar(source, start + 1) == '=' then double(state, start)(Token.Neq(_))
        else single(state, start)(Token.Bang(_))
      case '|' =>
        peekChar(source, start + 1) match
          case '>' => double(state, start)(Token.PipeForward(_))
          case '|' => double(state, start)(Token.OrOr(_))
          case _   => single(state, start)(Token.Pipe(_))
      case '&' =>
        if peekChar(source, start + 1) == '&' then double(state, start)(Token.AndAnd(_))
        else errorChar(state, start, DiagCode.E003, "stray `&` (did you mean `&&`?)", reporter)
      case '+'   => single(state, start)(Token.Plus(_))
      case '*'   => single(state, start)(Token.Star(_))
      case '/'   => single(state, start)(Token.Slash(_))
      case '%'   => single(state, start)(Token.Percent(_))
      case other => errorChar(state, start, DiagCode.E004, s"unexpected character ${describeChar(other)}", reporter)

  /** Open-bracket production: push the bracket with the current indent depth. */
  private def openBracket(state: ScannerState, start: Int, ch: Char, mk: Span => Token): (ScannerState, Token) =
    val tok = mk(Span(start, start + 1))
    val bracket = OpenBracket(ch, state.indentStack.length)
    (state.copy(pos = start + 1, bracketStack = bracket :: state.bracketStack, lastKind = tok.kind), tok)

  /** Fixed-width token of `len` chars at `start`. */
  private inline def emit(state: ScannerState, start: Int, len: Int)(inline mk: Span => Token): (ScannerState, Token) =
    val tok = mk(Span(start, start + len))
    (state.copy(pos = start + len, lastKind = tok.kind), tok)

  private inline def single(state: ScannerState, start: Int)(inline mk: Span => Token) = emit(state, start, 1)(mk)

  private inline def double(state: ScannerState, start: Int)(inline mk: Span => Token) = emit(state, start, 2)(mk)

  /** Report `code` at the char at `start` and emit it as a one-char Error token. */
  private def errorChar(
      state: ScannerState,
      start: Int,
      code: DiagCode,
      msg: String,
      reporter: Reporter
  ): (ScannerState, Token) =
    reporter.error(code, Span(start, start + 1), msg)
    single(state, start)(Token.Error(_))

  /** Close-bracket production. Folds pos / lastKind / bracketStack into
    * one `state.copy` per token.
    */
  private def closeBracket(
      state: ScannerState,
      start: Int,
      mk: Span => Token,
      expected: Char,
      reporter: Reporter
  ): (ScannerState, Token) =
    val nextStack = state.bracketStack match
      case OpenBracket(top, _) :: rest if top == expected => rest
      case _                                              =>
        val tag = state.bracketStack match
          case OpenBracket(top, _) :: _ => s"`$top`"
          case Nil                      => "empty"
        reporter.error(
          DiagCode.E005,
          Span(start, start + 1),
          s"unmatched closing bracket — expected to close `$expected` but bracket stack is $tag"
        )
        state.bracketStack
    val tok = mk(Span(start, start + 1))
    (state.copy(pos = start + 1, lastKind = tok.kind, bracketStack = nextStack), tok)

  private def scanIdent(
      state: ScannerState,
      source: SourceFile,
      start: Int
  ): (ScannerState, Token) =
    val end = skipWhile(source.content, start, source.length)(isIdentPart)
    val sp = Span(start, end)
    val tok = Tokens.keywordAt(source.content, start, end) match
      case Some(kw) => kw.make(sp)
      case None     =>
        val text = source.slice(start, end)
        if text.charAt(0).isUpper then Token.UpperIdent(sp, text) else Token.LowerIdent(sp, text)
    (state.copy(pos = end, lastKind = tok.kind), tok)

  private def scanNumber(
      state: ScannerState,
      source: SourceFile,
      start: Int
  ): (ScannerState, Token) =
    val intEnd = skipDigits(source, start)

    /** Float? '.' followed by another digit. Don't eat `5.foo` — that's
      * an integer 5 followed by `.foo`, not a float "5." followed by `foo`.
      */
    val sawDot =
      intEnd < source.length
        && source.content.charAt(intEnd) == '.'
        && intEnd + 1 < source.length
        && source.content.charAt(intEnd + 1).isDigit
    if sawDot then
      val fracEnd = skipDigits(source, intEnd + 1)
      val st = state.copy(pos = fracEnd, lastKind = TokenKind.FloatLit)
      (st, Token.FloatLit(Span(start, fracEnd), source.slice(start, fracEnd)))
    else
      val st = state.copy(pos = intEnd, lastKind = TokenKind.IntLit)
      (st, Token.IntLit(Span(start, intEnd), source.slice(start, intEnd)))

  @tailrec
  private def skipDigits(source: SourceFile, i: Int): Int =
    if i >= source.length || !source.content.charAt(i).isDigit then i
    else skipDigits(source, i + 1)

  /** Result of scanning a quoted (string or char) body. `closed = true` for
    * both the normal terminator-found case AND the bail-out cases (newline
    * mid-literal). EOF mid-literal returns `closed = false` so callers can
    * emit the "at end of input" variant of the error.
    */
  final private case class QuotedBody(pos: Int, lexeme: String, closed: Boolean)

  /** The local StringBuilder never escapes `loop`; it's a lexeme
    * accumulator frozen via `.toString` at each terminating branch. The
    * `(c :: acc).reverse.mkString` shape is equivalent but allocates a
    * List cell per character, which dominated the literal-heavy
    * benchmark before this hand-rolled accumulator.
    */
  private def scanQuotedBody(
      source: SourceFile,
      start: Int,
      i0: Int,
      terminator: Char,
      reporter: Reporter,
      unterminatedCode: DiagCode,
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
              reporter.error(DiagCode.E006, Span(i, i + 2), s"unknown escape sequence `\\$esc`")
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

  /** Per-literal settings for quoted tokens. */
  private enum Quote(
      val terminator: Char,
      val make: (Span, String) => Token,
      val code: DiagCode,
      val noun: String,
      val lineMessage: String,
      val lineSuggestion: Option[String]
  ):
    case Str
        extends Quote(
          '"',
          Token.StringLit(_, _),
          DiagCode.E007,
          "string",
          "unterminated string literal — closing `\"` missing on this line",
          Some("string literals must close on the same line in v0.4.5")
        )
    case Chr extends Quote('\'', Token.CharLit(_, _), DiagCode.E008, "char", "unterminated char literal", None)

    /** Literal-specific checks on the scanned body: a char literal holds exactly one character. */
    def checkBody(lexeme: String, span: Span, reporter: Reporter): Unit = this match
      case Str => ()
      case Chr =>
        if lexeme.length != 1 then
          reporter.error(
            DiagCode.E009,
            span,
            s"char literal must contain exactly one character (got ${lexeme.length})"
          )

  private def scanQuoted(
      state: ScannerState,
      source: SourceFile,
      start: Int,
      reporter: Reporter,
      q: Quote
  ): (ScannerState, Token) =
    val body = scanQuotedBody(
      source,
      start,
      i0 = start + 1,
      terminator = q.terminator,
      reporter = reporter,
      unterminatedCode = q.code,
      unterminatedLineMessage = q.lineMessage,
      unterminatedLineSuggestion = q.lineSuggestion
    )
    if !body.closed then
      reporter.error(q.code, Span(start, body.pos), s"unterminated ${q.noun} literal at end of input")
    q.checkBody(body.lexeme, Span(start, body.pos), reporter)
    val tok = q.make(Span(start, body.pos), body.lexeme)
    (state.copy(pos = body.pos, lastKind = tok.kind), tok)

  private def peekChar(source: SourceFile, i: Int): Char =
    if i < source.length then source.content.charAt(i) else ' '

  private def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'

  private def isIdentStartAt(source: SourceFile, i: Int): Boolean =
    i < source.length && isIdentStart(source.content.charAt(i))

  private def describeChar(c: Char): String =
    if c < ' ' then f"\\u${c.toInt}%04x"
    else s"`$c`"

end Scanner

/** Pull-style adapter over the pure functional core. The token stream
  * is produced once at construction by `Iterator.unfold` over
  * `ScannerState`, materialised eagerly into an immutable
  * `Vector[Token]`; an `Iterator[Token]` over that Vector serves the
  * parser's pull API. The iterator is the one documented boundary
  * mutation — it owns Scala's internal cursor and is required to
  * satisfy the `def nextToken(): Token` contract without redesigning
  * the Parser. Trivia extraction is a pure fold over the same Vector.
  *
  * Eager materialisation keeps reporter side effects firing exactly
  * once and gives `tokenize` and `triviaTable` a shared source of
  * truth.
  */
final class Scanner(
    val source: SourceFile,
    val reporter: Reporter,
    captureTrivia: Boolean = true
):
  import Scanner.{ScannerState, initialState, step}

  /** Termination: once a step has emitted EOF, the resulting state
    * carries `lastKind = TokenKind.Eof`, so the next iteration short-
    * circuits. Encoding "done" in the state itself avoids the per-step
    * `Option[ScannerState]` boxing the unfold would otherwise incur.
    * Materialised straight into an Array: the parser indexes it directly,
    * so a Vector would only be built to be copied. Never mutated after
    * construction; `tokenize()` exposes it through a read-only ArraySeq view.
    */
  private val tokens: Array[Token] =
    /** Presized (the examples corpus averages ~6.4 chars per token) so the
      * builder does not regrow while scanning.
      */
    val buf = Array.newBuilder[Token]
    buf.sizeHint(source.length / 5 + 16)
    Iterator
      .unfold[Token, ScannerState](initialState) { st =>
        if st.lastKind == TokenKind.Eof then None
        else
          val (next, tok) = step(st, source, reporter)
          Some((tok, next))
      }
      .foreach(buf.addOne)
    buf.result()

  /** The single boundary mutation: Scala's `Iterator` owns an internal
    * cursor over `tokens`. Past the materialised end we synthesise
    * further EOFs so `nextToken` is idempotent at EOF.
    */
  private val iter: Iterator[Token] = tokens.iterator

  def nextToken(): Token =
    if iter.hasNext then iter.next()
    else Token.Eof(Span(source.length, source.length))

  def tokenize(): Seq[Token] = scala.collection.immutable.ArraySeq.unsafeWrapArray(tokens)

  /** The token array itself, for the parser's O(1) indexing. Read-only by contract. */
  private[parsing] def tokenArray: Array[Token] = tokens

  /** Trivia table keyed on real-token start offsets. Returns
    * `TriviaTable.empty` when this scanner was constructed with
    * `captureTrivia = false`. */
  def triviaTable: TriviaTable =
    if !captureTrivia then TriviaTable.empty
    else
      val content = source.content
      val (_, table) = tokens.foldLeft((0, Map.empty[Int, List[Trivia]])) {
        case ((prevEnd, acc), tok) =>
          val s = tok.span.start
          val e = tok.span.end

          /** synthetic */
          if s >= e then (prevEnd, acc)
          else if s - prevEnd >= 2 then
            val events = collectTrivia(content, prevEnd, s, Nil)
            val acc1 = if events.nonEmpty then acc.updated(s, events) else acc
            (e, acc1)
          else (e, acc)
      }
      TriviaTable.from(table)

  /** Walk `[i, end)` of `content` collecting `//`-comments and runs of
    * two or more `\n`s. Inline whitespace is ignored (recoverable from
    * token spans). Single newlines are already represented by
    * NEWLINE / DEDENT tokens. The accumulator is reversed at the
    * terminating branch so events come out in source order.
    */
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
        val cmtEnd = Scanner.skipWhile(content, i, end)(_ != '\n')
        val ev = Trivia.LineComment(Span(i, cmtEnd), content.substring(i, cmtEnd))
        collectTrivia(content, cmtEnd, end, ev :: acc)
      else if c == '\n' then
        val nlEnd = Scanner.skipWhile(content, i, end)(_ == '\n')
        val nlCount = nlEnd - i

        /** Blank lines = newlines minus the one already represented by
          * the NEWLINE / DEDENT token. >= 1 blank line is worth recording.
          */
        val acc1 =
          if nlCount >= 2 then Trivia.BlankLines(Span(i, nlEnd), nlCount - 1) :: acc
          else acc
        collectTrivia(content, nlEnd, end, acc1)
      else
        collectTrivia(content, i + 1, end, acc)

end Scanner
