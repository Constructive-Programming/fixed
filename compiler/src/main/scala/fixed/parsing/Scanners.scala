package fixed.parsing

import scala.collection.immutable.Queue
import scala.annotation.tailrec

import fixed.util.{Diagnostic, Reporter, Severity, SourceFile, Span}

/** The Fixed lexer. Pure-FP, single-pass; offset-based.
  *
  * Implements the indentation and line-continuation rules from
  * `spec/syntax_grammar.ebnf` v0.4.5 lines 156–217:
  *  - Lines whose indent strictly increases push an INDENT token.
  *  - Lines whose indent strictly decreases emit one DEDENT per level closed.
  *  - Inside `(`/`[`/`{` the off-side rule is suppressed by default; it
  *    re-engages inside `=>`/`->`/`=`/`:` bodies.
  *  - A NEWLINE between physical lines is suppressed if the previous line
  *    ended with a trailing-continuation token (see `Tokens.trailingContinuationKinds`)
  *    or if the next line starts with a leading-continuation token.
  *  - Tabs as indentation are an error; v0.4.5 mandates spaces only.
  *  - `//` comments run to end-of-line and are dropped before indent measurement.
  *
  * # Design
  *
  * The lexing core is fully pure. It is driven by:
  *
  *   - `ScannerState`: an immutable case class holding the cursor (`pos`),
  *     indent stack, bracket stack, last-emitted token, EOF flag, a queue of
  *     pending synthetic tokens, and accumulated diagnostics.
  *   - `step(state, source) -> (newState, token)`: a pure transition that
  *     advances by one token. Internally a chain of pattern-matched, tail-
  *     recursive helpers (no `var`, no `while`, no `mutable.*`).
  *   - `tokenize(state, source) -> (finalState, Vector[Token])`: drives the
  *     core via `LazyList.unfold` until EOF is emitted.
  *
  * The boundary with `Reporter` is the only impure surface. Errors live in
  * `ScannerState.errors` until the public API (`Scanner.tokenize` /
  * `Scanner#nextToken`) flushes them to the user-supplied `Reporter`. We
  * flush *as we emit each token* so error timing matches the previous
  * imperative implementation — tests asserting `rep.hasErrors` after a partial
  * pull stay well-behaved.
  *
  * The pull-style `class Scanner(source, reporter)` wrapper is itself
  * mutation-free in this file: it holds an `Iterator[(Token, Vector[Diagnostic])]`
  * derived from the lazy stream. The `Iterator`'s internal cursor is in
  * stdlib, not in our code.
  */
object Scanner:

  // -----------------------------------------------------------------
  //  Pure scanner state
  // -----------------------------------------------------------------

  /** All scanner state, immutable.
    *
    *  - `pos`            character cursor.
    *  - `indentStack`    head = current level. Always non-empty; bottom is 0.
    *                     We store it head-first (newest on the front) so push
    *                     is O(1) cons and pop is O(1) tail.
    *  - `bracketStack`   head-first stack of `(bracketChar, indentDepthAtOpen)`
    *                     where indentDepthAtOpen is the *length* of
    *                     `indentStack` at the moment the bracket opened.
    *  - `lastEmitted`    most recent non-synthetic? Actually most recent
    *                     overall; used for trailing-continuation and
    *                     dual-mode-block-introducer decisions, just like
    *                     the imperative version.
    *  - `eofEmitted`     becomes true after the synthetic Eof token is queued.
    *  - `pending`        FIFO queue of synthetic tokens (INDENT/DEDENT/NEWLINE/
    *                     EOF) that must drain before the next real token.
    *  - `errors`         accumulated diagnostics, in source order.
    */
  final case class ScannerState(
      pos: Int,
      indentStack: List[Int],
      bracketStack: List[(Char, Int)],
      lastEmitted: Option[Token],
      eofEmitted: Boolean,
      pending: Queue[Token],
      errors: Vector[Diagnostic]
  ):
    /** True iff we are inside an open bracket and no body has been engaged
      * since the bracket opened. */
    def isInsideSuppressedBrackets: Boolean = bracketStack match
      case (_, depth) :: _ => indentStack.length == depth
      case Nil             => false

    /** Top of the indent stack (current logical column). */
    def currentIndent: Int = indentStack.head

    /** Append an error, returning the updated state. */
    def withError(
        code: String,
        span: Span,
        message: String,
        suggestion: Option[String] = None
    ): ScannerState =
      copy(errors = errors :+ Diagnostic(Severity.Error, code, span, message, suggestion))

    /** Enqueue a synthetic token. */
    def enqueue(t: Token): ScannerState =
      copy(pending = pending.enqueue(t))

    /** Set the last-emitted token. */
    def withLast(t: Token): ScannerState =
      copy(lastEmitted = Some(t))

  end ScannerState

  /** Initial state: cursor at zero, indent stack at the top-level column 0. */
  def initialState: ScannerState =
    ScannerState(
      pos = 0,
      indentStack = 0 :: Nil,
      bracketStack = Nil,
      lastEmitted = None,
      eofEmitted = false,
      pending = Queue.empty,
      errors = Vector.empty
    )

  // -----------------------------------------------------------------
  //  Public API
  // -----------------------------------------------------------------

  /** Tokenize a whole source file in one shot, flushing diagnostics to the
    * supplied `Reporter` along the way. Returns the token sequence
    * terminated by EOF. */
  def tokenize(source: SourceFile, reporter: Reporter): Seq[Token] =
    new Scanner(source, reporter).tokenize()

  /** The pure core's reporter-flushing helper. Pushes any errors that
    * appeared in `curr` but not in `prev` to the reporter. This is the
    * *one* mutation site in the whole module — the boundary between our
    * pure core and the user's mutable diagnostic sink. */
  private[parsing] def flushNewErrors(
      prev: ScannerState,
      curr: ScannerState,
      reporter: Reporter
  ): Unit =
    val before = prev.errors.length
    val after = curr.errors.length
    if after > before then
      curr.errors.slice(before, after).foreach { d =>
        reporter.error(d.code, d.span, d.message, d.suggestion)
      }

  // -----------------------------------------------------------------
  //  The transition: state -> (state, token)
  // -----------------------------------------------------------------

  /** Advance the scanner by one token. Invariant: every call returns exactly
    * one token (real or synthetic). At EOF the function is idempotent — it
    * returns Eof from a state in which `eofEmitted` is true. */
  def step(state: ScannerState, source: SourceFile): (ScannerState, Token) =
    state.pending.dequeueOption match
      case Some((tok, rest)) =>
        // Drain a previously-enqueued synthetic token first.
        val st1 = state.copy(pending = rest).withLast(tok)
        (st1, tok)
      case None =>
        scanOne(state, source) match
          case ScanResult.RealToken(st1, tok) =>
            // scanOne may have enqueued synthetic tokens (INDENT/DEDENT/NEWLINE)
            // *before* the real token. If so, the synthetic ones must drain
            // first; the real token gets re-queued at the back.
            if st1.pending.nonEmpty then
              val (synth, rest) = (st1.pending.head, st1.pending.tail)
              val st2 = st1.copy(pending = rest.enqueue(tok)).withLast(synth)
              (st2, synth)
            else
              val st2 = st1.withLast(tok)
              (st2, tok)
          case ScanResult.OnlySynthetics(st1) =>
            // No real token; scanOne enqueued zero or more synthetics for us.
            // Drain one if present, else continue (recurse).
            st1.pending.dequeueOption match
              case Some((tok, rest)) =>
                val st2 = st1.copy(pending = rest).withLast(tok)
                (st2, tok)
              case None =>
                // EOF path: close any remaining indent levels, queue Eof.
                if !st1.eofEmitted then
                  val st2 = closeIndentsAndQueueEof(st1)
                  step(st2, source)
                else
                  // Already emitted EOF and queue is empty — shouldn't happen
                  // (runAll stops the unfold first), but stay safe and just
                  // return another Eof.
                  (st1, Token(TokenKind.Eof, Span(st1.pos, st1.pos)))

  /** Close any remaining indent levels and enqueue the Eof token, returning
    * a state with `eofEmitted = true`. */
  private def closeIndentsAndQueueEof(state: ScannerState): ScannerState =
    val pos = state.pos
    val dedentCount = state.indentStack.length - 1
    val poppedStack = state.indentStack.drop(dedentCount)
    val dedents = Vector.fill(dedentCount)(Token(TokenKind.Dedent, Span(pos, pos)))
    val withDedents = dedents.foldLeft(state.pending)(_ enqueue _)
    val withEof = withDedents.enqueue(Token(TokenKind.Eof, Span(pos, pos)))
    state.copy(
      indentStack = poppedStack,
      pending = withEof,
      eofEmitted = true
    )

  // -----------------------------------------------------------------
  //  scanOne: skip whitespace / comments, then either produce a real
  //  token or discover end-of-input.
  // -----------------------------------------------------------------

  private enum ScanResult:
    case RealToken(state: ScannerState, token: Token)
    case OnlySynthetics(state: ScannerState)

  /** Skip whitespace/comments. If we cross a newline, decide indentation
    * tokens for the new line. Then either scan a real token or report
    * end-of-input. */
  private def scanOne(state: ScannerState, source: SourceFile): ScanResult =
    val (st1, crossedNewline) = skipTrivia(state, source, crossedNewline = false)
    if st1.pos >= source.length then ScanResult.OnlySynthetics(st1)
    else
      val st2 = if crossedNewline then handleNewline(st1, source) else st1
      val (st3, tok) = scanToken(st2, source)
      ScanResult.RealToken(st3, tok)

  /** Tail-recursively skip horizontal whitespace, line comments, CRs, and
    * newlines. Returns the state at the first non-trivia char (or at EOF)
    * along with whether we crossed at least one '\n'. */
  @tailrec
  private def skipTrivia(
      state: ScannerState,
      source: SourceFile,
      crossedNewline: Boolean
  ): (ScannerState, Boolean) =
    if state.pos >= source.length then (state, crossedNewline)
    else
      val c = source.content.charAt(state.pos)
      if c == ' ' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline)
      else if c == '\t' then
        // Tabs are forbidden as whitespace per grammar v0.3 (line 160).
        // Emit an error but advance so we make progress.
        val st1 = state
          .withError(
            "E001",
            Span(state.pos, state.pos + 1),
            "tab character in source — Fixed v0.4.5 requires spaces for indentation",
            Some("replace this tab with spaces")
          )
          .copy(pos = state.pos + 1)
        skipTrivia(st1, source, crossedNewline)
      else if c == '\n' then
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline = true)
      else if c == '\r' then
        // Tolerate \r\n by skipping the CR.
        skipTrivia(state.copy(pos = state.pos + 1), source, crossedNewline)
      else if c == '/' && peekChar(source, state.pos + 1) == '/' then
        // Line comment: run to end of line (do NOT consume the newline).
        val newPos = skipUntilNewline(source, state.pos)
        skipTrivia(state.copy(pos = newPos), source, crossedNewline)
      else
        (state, crossedNewline)

  /** Advance to the next '\n' (or EOF), returning the index of the '\n' (or
    * `source.length`). Tail-recursive. */
  @tailrec
  private def skipUntilNewline(source: SourceFile, i: Int): Int =
    if i >= source.length || source.content.charAt(i) == '\n' then i
    else skipUntilNewline(source, i + 1)

  // -----------------------------------------------------------------
  //  Indentation handling
  // -----------------------------------------------------------------

  /** Called when we've crossed at least one '\n' between content lines. The
    * cursor is at the first non-whitespace, non-comment char of the new line
    * (i.e. `state.pos`). Decide INDENT/DEDENT/NEWLINE per the off-side rule
    * and return the updated state. */
  private def handleNewline(state: ScannerState, source: SourceFile): ScannerState =
    val pos = state.pos
    // Compute the new line's indent.
    val lineStart = source.content.lastIndexOf('\n', pos - 1) + 1
    val newIndent = pos - lineStart
    val lastKind = state.lastEmitted.map(_.kind)

    // Dual-mode block-introducers (= / -> / =>). Must be checked first because
    // a body opener inside parens is exactly what re-engages the off-side rule.
    val isDualMode = lastKind.exists(k =>
      k == TokenKind.Eq || k == TokenKind.Arrow || k == TokenKind.FatArrow
    )
    if isDualMode && newIndent > state.currentIndent then
      state
        .copy(indentStack = newIndent :: state.indentStack)
        .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
    else if state.isInsideSuppressedBrackets then
      // Off-side suppressed inside un-engaged brackets — no token.
      state
    else if state.lastEmitted.exists(t => t.kind != TokenKind.Newline)
            && peekKindIsLeadingContinuation(source, state.pos)
    then
      // Leading-continuation: next line starts with `with`/`extends`/`->`.
      state
    else if state.lastEmitted.exists(t => Tokens.trailingContinuationKinds.contains(t.kind))
    then
      // Trailing-continuation suppresses the newline.
      state
    else
      // Off-side rule: compare to indent stack.
      val curIndent = state.currentIndent
      if newIndent > curIndent then
        // Push a new level only if previous token was `:`. Otherwise emit
        // NEWLINE (visual continuation; parser may reject if unexpected).
        val previouslyColon = state.lastEmitted.exists(_.kind == TokenKind.Colon)
        if previouslyColon then
          state
            .copy(indentStack = newIndent :: state.indentStack)
            .enqueue(Token(TokenKind.Indent, Span(pos, pos)))
        else
          state.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
      else if newIndent < curIndent then
        // Pop levels until we match, or until we collapse into a bracket-
        // suppressed region.
        val st1 = popIndents(state, newIndent)
        if st1.isInsideSuppressedBrackets then
          // Engaged body just closed; we're back inside un-engaged brackets.
          // No NEWLINE; subsequent content rejoins the bracket-suppressed flow.
          st1
        else
          val st2 =
            if st1.currentIndent != newIndent then
              st1.withError(
                "E002",
                Span(pos, pos + 1),
                s"inconsistent indentation; closing dedent does not match any opening indent (got $newIndent, stack=${st1.indentStack.reverse.mkString(",")})",
                Some("align this line to one of the enclosing block's indent levels")
              )
            else st1
          st2.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
      else
        // Same indent — emit NEWLINE separator unless previous token already
        // was a NEWLINE (avoid duplicates).
        if !state.lastEmitted.exists(_.kind == TokenKind.Newline) then
          state.enqueue(Token(TokenKind.Newline, Span(pos, pos)))
        else state

  /** Pop indent levels until the head <= newIndent (or only the bottom 0
    * level remains), enqueueing one DEDENT per pop. Tail-recursive. */
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

  /** Stateless lookahead from `pos`: skip horizontal whitespace, then check
    * whether the next word is a leading-continuation keyword (`with`,
    * `extends`) or `->`. */
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

  /** Tail-recursively advance past spaces/tabs. */
  @tailrec
  private def skipSpacesAndTabs(content: String, i: Int, len: Int): Int =
    if i >= len then i
    else
      val c = content.charAt(i)
      if c == ' ' || c == '\t' then skipSpacesAndTabs(content, i + 1, len)
      else i

  /** Tail-recursively advance over identifier characters. */
  @tailrec
  private def skipIdentPart(content: String, i: Int, len: Int): Int =
    if i >= len then i
    else
      val c = content.charAt(i)
      if c.isLetterOrDigit || c == '_' then skipIdentPart(content, i + 1, len)
      else i

  // -----------------------------------------------------------------
  //  scanToken: dispatch on the first character.
  // -----------------------------------------------------------------

  /** Scan one non-whitespace token. Caller has ensured `state.pos` points
    * at a real character. Returns updated state and the token. */
  private def scanToken(state: ScannerState, source: SourceFile): (ScannerState, Token) =
    val start = state.pos
    val c = source.content.charAt(start)
    c match
      // ---- Brackets ----
      case '(' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('(', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LParen, Span(start, st.pos)))
      case ')' =>
        val st0 = state.copy(pos = start + 1)
        val st = popBracket(st0, '(')
        (st, Token(TokenKind.RParen, Span(start, st.pos)))
      case '[' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('[', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBracket, Span(start, st.pos)))
      case ']' =>
        val st0 = state.copy(pos = start + 1)
        val st = popBracket(st0, '[')
        (st, Token(TokenKind.RBracket, Span(start, st.pos)))
      case '{' =>
        val st = state.copy(
          pos = start + 1,
          bracketStack = ('{', state.indentStack.length) :: state.bracketStack
        )
        (st, Token(TokenKind.LBrace, Span(start, st.pos)))
      case '}' =>
        val st0 = state.copy(pos = start + 1)
        val st = popBracket(st0, '{')
        (st, Token(TokenKind.RBrace, Span(start, st.pos)))

      // ---- Single-character punctuation ----
      case ',' => single(state, start, TokenKind.Comma)
      case ';' => single(state, start, TokenKind.Semicolon)
      case '@' => single(state, start, TokenKind.At)
      case '_' if !isIdentStartAt(source, start + 1) =>
        single(state, start, TokenKind.Underscore)

      // ---- Multi-character operators with shared prefix ----
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
          val st = state
            .withError("E003", Span(start, start + 1), "stray `&` (did you mean `&&`?)")
            .copy(pos = start + 1)
          (st, Token(TokenKind.Error, Span(start, st.pos)))
      case '+' => single(state, start, TokenKind.Plus)
      case '*' => single(state, start, TokenKind.Star)
      case '/' => single(state, start, TokenKind.Slash)
      case '%' => single(state, start, TokenKind.Percent)

      // ---- Identifiers / keywords ----
      case ch if isIdentStart(ch) => scanIdent(state, source, start)

      // ---- Numeric literals ----
      case ch if ch.isDigit => scanNumber(state, source, start)

      // ---- String / char literals ----
      case '"'  => scanString(state, source, start)
      case '\'' => scanChar(state, source, start)

      // ---- Unknown ----
      case other =>
        val st = state
          .withError(
            "E004",
            Span(start, start + 1),
            s"unexpected character ${describeChar(other)}"
          )
          .copy(pos = start + 1)
        (st, Token(TokenKind.Error, Span(start, st.pos)))

  /** Single-character token. */
  private def single(state: ScannerState, start: Int, kind: TokenKind): (ScannerState, Token) =
    val st = state.copy(pos = start + 1)
    (st, Token(kind, Span(start, st.pos)))

  /** Two-character token. */
  private def double(state: ScannerState, start: Int, kind: TokenKind): (ScannerState, Token) =
    val st = state.copy(pos = start + 2)
    (st, Token(kind, Span(start, st.pos)))

  /** Pop the expected bracket from the stack, or report an unmatched-bracket
    * error. Pos has already been advanced past the closing bracket; the
    * error span points at the closer's offset. */
  private def popBracket(state: ScannerState, expected: Char): ScannerState =
    state.bracketStack match
      case (top, _) :: rest if top == expected =>
        state.copy(bracketStack = rest)
      case _ =>
        val tag = state.bracketStack match
          case (top, _) :: _ => s"`$top`"
          case Nil           => "empty"
        state.withError(
          "E005",
          Span(state.pos - 1, state.pos),
          s"unmatched closing bracket — expected to close `$expected` but bracket stack is $tag"
        )

  // -----------------------------------------------------------------
  //  Identifiers, numbers, strings, chars
  // -----------------------------------------------------------------

  private def scanIdent(
      state: ScannerState,
      source: SourceFile,
      start: Int
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
      state: ScannerState,
      source: SourceFile,
      start: Int
  ): (ScannerState, Token) =
    val intEnd = skipDigits(source, start)
    // Float? '.' followed by another digit (don't eat e.g. `5.foo` — that's
    // a method call on integer 5, not "5." float literal followed by `foo`).
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

  /** Tail-recursively advance over digit characters. */
  @tailrec
  private def skipDigits(source: SourceFile, i: Int): Int =
    if i >= source.length || !source.content.charAt(i).isDigit then i
    else skipDigits(source, i + 1)

  /** Outcome of a string/char body scan. */
  private final case class QuotedBody(
      pos: Int,
      lexeme: String,
      closed: Boolean,
      newErrors: Vector[Diagnostic]
  )

  /** Tail-recursively scan the body of a quoted literal up to `terminator`,
    * handling escapes and unterminated cases. The opening quote is at
    * `start`; `i0` is the position *after* the opening quote.
    *
    *  - On reaching `terminator`, returns `closed=true`, pos *past* the
    *    terminator.
    *  - On reaching '\n', returns `closed=true` (early bailout per the
    *    imperative version) and pushes E007/E008 — pos points at the newline.
    *  - On hitting EOF, returns `closed=false` — caller will push the
    *    "at end of input" variant of the error.
    *
    * The `unterminatedCode` is "E007" for strings, "E008" for chars; the
    * `unterminatedMessage` is the line-bailout message (the EOF variant is
    * appended by the caller).
    */
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
    def loop(
        i: Int,
        buf: StringBuilder,
        errs: Vector[Diagnostic]
    ): QuotedBody =
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
              loop(
                i + 2,
                buf,
                errs :+ Diagnostic(Severity.Error, code, span, msg)
              )
        else if c == '\n' then
          // Bail out; do not consume the newline.
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

  /** Common escape-char dispatcher used by both string and char scans. Both
    * `\"` and `\'` are accepted in either literal kind, matching the
    * imperative reference. */
  private def stdEscape(esc: Char, atPos: Int): Either[(String, Span, String), Char] =
    esc match
      case 'n'  => Right('\n')
      case 't'  => Right('\t')
      case 'r'  => Right('\r')
      case '\\' => Right('\\')
      case '"'  => Right('"')
      case '\'' => Right('\'')
      case '0'  => Right(' ')
      case other =>
        Left(
          (
            "E006",
            Span(atPos, atPos + 2),
            s"unknown escape sequence `\\$other`"
          )
        )

  private def scanString(
      state: ScannerState,
      source: SourceFile,
      start: Int
  ): (ScannerState, Token) =
    val body = scanQuotedBody(
      source,
      start = start,
      i0 = start + 1,
      terminator = '"',
      escapeChar = stdEscape,
      unterminatedCode = "E007",
      unterminatedLineMessage =
        "unterminated string literal — closing `\"` missing on this line",
      unterminatedLineSuggestion =
        Some("string literals must close on the same line in v0.4.5")
    )
    val errs1 = body.newErrors
    val errs2 =
      if body.closed then errs1
      else
        errs1 :+ Diagnostic(
          Severity.Error,
          "E007",
          Span(start, body.pos),
          "unterminated string literal at end of input"
        )
    val st = state.copy(pos = body.pos, errors = state.errors ++ errs2)
    (st, Token(TokenKind.StringLit, Span(start, body.pos), body.lexeme))

  private def scanChar(
      state: ScannerState,
      source: SourceFile,
      start: Int
  ): (ScannerState, Token) =
    val body = scanQuotedBody(
      source,
      start = start,
      i0 = start + 1,
      terminator = '\'',
      escapeChar = stdEscape,
      unterminatedCode = "E008",
      unterminatedLineMessage = "unterminated char literal",
      unterminatedLineSuggestion = None
    )
    val errs1 = body.newErrors
    val errs2 =
      if body.closed then errs1
      else
        errs1 :+ Diagnostic(
          Severity.Error,
          "E008",
          Span(start, body.pos),
          "unterminated char literal at end of input"
        )
    val errs3 =
      if body.lexeme.length != 1 then
        errs2 :+ Diagnostic(
          Severity.Error,
          "E009",
          Span(start, body.pos),
          s"char literal must contain exactly one character (got ${body.lexeme.length})"
        )
      else errs2
    val st = state.copy(pos = body.pos, errors = state.errors ++ errs3)
    (st, Token(TokenKind.CharLit, Span(start, body.pos), body.lexeme))

  // -----------------------------------------------------------------
  //  Char-class helpers
  // -----------------------------------------------------------------

  private def peekChar(source: SourceFile, i: Int): Char =
    if i < source.length then source.content.charAt(i) else ' '

  private def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'

  private def isIdentStartAt(source: SourceFile, i: Int): Boolean =
    i < source.length && isIdentStart(source.content.charAt(i))

  private def describeChar(c: Char): String =
    if c < ' ' then f"\\u${c.toInt}%04x"
    else s"`$c`"

end Scanner

// ---------------------------------------------------------------------
//  Pull-style wrapper for `Parser` and tests that want `nextToken()`.
// ---------------------------------------------------------------------

/** A pull-style adapter over the pure scanner core.
  *
  * The wrapper itself contains no mutable state in this file — it owns a
  * `scala.collection.Iterator` derived from the lazy token stream. The
  * `Iterator`'s internal cursor lives in stdlib, *not* in our code, which
  * keeps this file free of `var`/`mutable.*` per the FP-rewrite mandate.
  *
  * Diagnostics are flushed to the supplied `Reporter` as each token is
  * pulled, matching the timing of the previous imperative implementation.
  */
final class Scanner(val source: SourceFile, val reporter: Reporter):
  import Scanner.{ScannerState, initialState, step}

  /** Lazy stream of (state-after, token) pairs. We pair each token with the
    * *new* state so the consumer's iterator can compare consecutive states
    * to flush incremental errors. The first element's "previous state" is
    * the seed `initialState`. */
  private val stateTokenStream: LazyList[(ScannerState, Token)] =
    LazyList.unfold[(ScannerState, Token), ScannerState](initialState) { st =>
      if st.eofEmitted && st.pending.isEmpty then None
      else
        val (nextSt, tok) = step(st, source)
        Some(((nextSt, tok), nextSt))
    }

  /** A stream that flushes new errors to the reporter on each pull, then
    * yields the token. Built once; iterated via `tokens.next()`. */
  private val tokens: Iterator[Token] =
    stateTokenStream
      .scanLeft((initialState, Option.empty[Token])) { case ((prev, _), (curr, tok)) =>
        Scanner.flushNewErrors(prev, curr, reporter)
        (curr, Some(tok))
      }
      .iterator
      .collect { case (_, Some(t)) => t }

  /** Pull the next token. Idempotent at EOF: once Eof is emitted, every
    * subsequent call returns the same Eof token. */
  def nextToken(): Token =
    if tokens.hasNext then tokens.next()
    else Token(TokenKind.Eof, Span(source.length, source.length))

  /** Drain all tokens up to and including EOF. Pulls one token at a time
    * via `nextToken()`; the recursion is tail-call-optimized by Scala. */
  def tokenize(): Seq[Token] =
    @tailrec
    def loop(acc: Vector[Token]): Vector[Token] =
      val t = nextToken()
      val next = acc :+ t
      if t.kind == TokenKind.Eof then next else loop(next)
    loop(Vector.empty)

end Scanner
