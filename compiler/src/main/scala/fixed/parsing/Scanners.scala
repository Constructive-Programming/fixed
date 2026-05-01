package fixed.parsing

import fixed.util.{Reporter, SourceFile, Span}

/** The Fixed lexer. Single-pass, hand-written; offset-based.
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
  */
final class Scanner(val source: SourceFile, val reporter: Reporter):

  // ---- Mutable scanner state ----

  /** Source-character cursor. */
  private var pos: Int = 0

  /** Pending tokens to emit before scanning the next real one — used when
    * a single physical event produces multiple synthetic tokens (e.g. one
    * NEWLINE that closes 3 indent levels emits 3 DEDENTs). */
  private val queue = scala.collection.mutable.Queue.empty[Token]

  /** Indent stack. Always begins with 0 (top-level column). Push when
    * entering an indented block, pop on closing. */
  private val indentStack = scala.collection.mutable.ArrayBuffer[Int](0)

  /** Bracket stack: each entry is the bracket char + the length of
    * `indentStack` at the moment the bracket opened. Inside the bracket,
    * off-side is suppressed by default and re-engages only when a body
    * opener (`:`/`=`/`->`/`=>`) inside the bracket pushes a new level
    * onto `indentStack`. Concretely, `isInsideSuppressedBrackets` checks
    * `indentStack.length == bracketStack.last._2` — no new levels since
    * the bracket opened. */
  private val bracketStack = scala.collection.mutable.ArrayBuffer.empty[(Char, Int)]

  /** Last non-trivial token emitted — used to decide trailing continuation. */
  private var lastEmitted: Option[Token] = None

  /** True once EOF has been emitted. */
  private var eofEmitted: Boolean = false

  // ---- Public-ish: read the next token ----

  /** Pop and return the next token. Idempotent at EOF (always returns Eof). */
  def nextToken(): Token =
    if queue.nonEmpty then
      val t = queue.dequeue()
      lastEmitted = Some(t)
      t
    else
      scanOne() match
        case Some(t) if queue.nonEmpty =>
          // scanOne enqueued synthetic tokens (INDENT/DEDENT/NEWLINE) before
          // this real token. The synthetic tokens must be emitted first;
          // enqueue the real token at the tail and recurse.
          queue.enqueue(t)
          nextToken()
        case Some(t) =>
          lastEmitted = Some(t)
          t
        case None =>
          // No real token; scanOne enqueued zero or more synthetic tokens
          // for us. Recurse to drain the queue (or emit EOF if at end).
          if queue.nonEmpty then nextToken()
          else if !eofEmitted then
            eofEmitted = true
            // Close any remaining indent levels before EOF.
            while indentStack.length > 1 do
              val _ = indentStack.remove(indentStack.length - 1)
              queue.enqueue(synthetic(TokenKind.Dedent))
            queue.enqueue(synthetic(TokenKind.Eof))
            nextToken()
          else
            synthetic(TokenKind.Eof)

  /** Drain all tokens up to and including EOF as a sequence. */
  def tokenize(): Seq[Token] =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Token]
    var done = false
    while !done do
      val t = nextToken()
      buf += t
      if t.kind == TokenKind.Eof then done = true
    buf.toSeq

  // ---- Internal scanning loop ----

  /** Scan one source token. Returns:
    *  - `Some(t)` if a real token was scanned (and possibly enqueued
    *    synthetic INDENT/DEDENT/NEWLINE before it; the returned `t` is
    *    always the *real* token, queued *after* the synthetics).
    *  - `None` if no real token was scanned (whitespace/comment-only line
    *    consumed; caller should retry).
    *
    * Synthetic tokens are emitted via the queue, so the caller drains them
    * first.
    */
  private def scanOne(): Option[Token] =
    // Skip horizontal whitespace and comments. Track whether we crossed a
    // newline so we can emit indentation tokens at the next non-blank line.
    val startedAt = pos
    var crossedNewline = false

    while pos < source.length do
      val c = source.content.charAt(pos)
      if c == ' ' then
        pos += 1
      else if c == '\t' then
        // Tabs are forbidden as whitespace per grammar v0.3 (line 160).
        // Emit an error but advance so we make progress.
        reporter.error(
          "E001",
          Span(pos, pos + 1),
          "tab character in source — Fixed v0.4.5 requires spaces for indentation",
          Some("replace this tab with spaces")
        )
        pos += 1
      else if c == '\n' then
        crossedNewline = true
        pos += 1
      else if c == '\r' then
        // Tolerate \r\n by skipping the CR.
        pos += 1
      else if c == '/' && peek(1) == '/' then
        // Line comment: run to end of line (do NOT consume the newline).
        while pos < source.length && source.content.charAt(pos) != '\n' do
          pos += 1
      else
        // Real content.
        if crossedNewline then
          handleNewline()
        return Some(scanToken())
    end while

    // EOF reached. Don't emit a NEWLINE for the trailing whitespace —
    // any open indent levels are closed via the EOF branch of nextToken,
    // and emitting a final NEWLINE before EOF would be spurious.
    val _ = (startedAt, crossedNewline)
    None

  /** Called when we've crossed at least one '\n' between content lines.
    * Computes the new line's indent (already past the newline; cursor is
    * at the first non-whitespace char of the new line, i.e. at `pos`),
    * then decides INDENT/DEDENT/NEWLINE per the off-side rule. */
  private def handleNewline(): Unit =
    // Compute the new line's indent: we've already skipped through whitespace
    // up to `pos` (which is the first non-space, non-tab, non-newline char of
    // the new line). The indent is the column of `pos` minus the start of
    // the line.
    val lineStart = source.content.lastIndexOf('\n', pos - 1) + 1
    val newIndent = pos - lineStart

    val lastKind: Option[TokenKind] = lastEmitted.map(_.kind)

    // First: dual-mode block-introducers (= / -> / =>) open an indented
    // body when the next line is more indented. This MUST happen before
    // suppression and continuation checks because a body opener inside
    // parens is exactly what re-engages the off-side rule.
    val isDualMode = lastKind.exists(k =>
      k == TokenKind.Eq || k == TokenKind.Arrow || k == TokenKind.FatArrow
    )
    if isDualMode && newIndent > indentStack.last then
      indentStack += newIndent
      queue.enqueue(synthetic(TokenKind.Indent))
      return

    // If we're inside un-engaged brackets, off-side is suppressed: no
    // NEWLINE/INDENT/DEDENT; tokens flow straight through.
    if isInsideSuppressedBrackets then return

    // Leading-continuation: next line starts with `with`/`extends`/`->`.
    if lastEmitted.exists(t => t.kind != TokenKind.Newline)
       && peekKindIsLeadingContinuation
    then
      return

    // Trailing-continuation: previous token is a continuation token.
    // (Dual-mode body-intros were already handled above; remaining
    // trailing-continuations like `+`/`,`/`.` are pure continuations.)
    if lastEmitted.exists(t => Tokens.trailingContinuationKinds.contains(t.kind)) then
      return

    // Off-side rule: compare to indent stack.
    val curIndent = indentStack.last
    if newIndent > curIndent then
      // Push a new indent level. Per the grammar, INDENT only opens after
      // `:` (or a dual-mode block-introducer handled above). If the previous
      // token is `:`, it's a block opener; otherwise this is a stray indent.
      val previouslyColon = lastEmitted.exists(_.kind == TokenKind.Colon)
      if previouslyColon then
        indentStack += newIndent
        queue.enqueue(synthetic(TokenKind.Indent))
      else
        // Not a block opener — emit NEWLINE (treat the increased indent as
        // visual continuation; the parser may still reject if it expected a
        // top-level alignment).
        queue.enqueue(synthetic(TokenKind.Newline))
    else if newIndent < curIndent then
      // Pop levels until we match — or until we re-enter suppressed-bracket
      // territory (indentStack collapsed to [0] inside an open bracket),
      // in which case the new line's actual indent is whitespace inside
      // the bracket and no further indent processing applies.
      while indentStack.length > 1 && indentStack.last > newIndent do
        val _ = indentStack.remove(indentStack.length - 1)
        queue.enqueue(synthetic(TokenKind.Dedent))
      if isInsideSuppressedBrackets then
        // Engaged body just closed and we're back inside un-engaged
        // brackets. No NEWLINE; subsequent content rejoins the bracket-
        // suppressed token flow.
        ()
      else
        if indentStack.last != newIndent then
          reporter.error(
            "E002",
            Span(pos, pos + 1),
            s"inconsistent indentation; closing dedent does not match any opening indent (got $newIndent, stack=${indentStack.mkString(",")})",
            Some("align this line to one of the enclosing block's indent levels")
          )
        queue.enqueue(synthetic(TokenKind.Newline))
    else
      // Same indent — emit a NEWLINE separator unless the previous token
      // already was a newline (avoid duplicates).
      if !lastEmitted.exists(_.kind == TokenKind.Newline) then
        queue.enqueue(synthetic(TokenKind.Newline))

  /** True iff we're inside an open bracket and no body has been engaged
    * since the bracket opened. */
  private def isInsideSuppressedBrackets: Boolean =
    bracketStack.nonEmpty && indentStack.length == bracketStack.last._2

  /** Peek whether the next non-whitespace token will be a leading-continuation
    * keyword. Stateless lookahead — does not advance `pos`. */
  private def peekKindIsLeadingContinuation: Boolean =
    var i = pos
    while i < source.length && (source.content.charAt(i) == ' ' || source.content.charAt(i) == '\t') do
      i += 1
    if i >= source.length then return false
    val c = source.content.charAt(i)
    if c == '-' && peekAt(i + 1) == '>' then true
    else if c.isLetter || c == '_' then
      // Read word.
      var j = i
      while j < source.length && (source.content.charAt(j).isLetterOrDigit || source.content.charAt(j) == '_') do
        j += 1
      val word = source.content.substring(i, j)
      Tokens.keywords.get(word).exists(Tokens.leadingContinuationKinds.contains)
    else false

  /** Scan one non-whitespace token starting at `pos`. Caller has already
    * skipped whitespace/comments and (possibly) emitted indent tokens. */
  private def scanToken(): Token =
    val start = pos
    val c = source.content.charAt(pos)
    c match
      // Brackets
      case '(' =>
        pos += 1
        bracketStack += (('(', indentStack.length))
        tokenAt(TokenKind.LParen, start)
      case ')' =>
        pos += 1
        popBracket('(')
        tokenAt(TokenKind.RParen, start)
      case '[' =>
        pos += 1
        bracketStack += (('[', indentStack.length))
        tokenAt(TokenKind.LBracket, start)
      case ']' =>
        pos += 1
        popBracket('[')
        tokenAt(TokenKind.RBracket, start)
      case '{' =>
        pos += 1
        bracketStack += (('{', indentStack.length))
        tokenAt(TokenKind.LBrace, start)
      case '}' =>
        pos += 1
        popBracket('{')
        tokenAt(TokenKind.RBrace, start)

      // Single-character punctuation
      case ',' =>
        pos += 1
        tokenAt(TokenKind.Comma, start)
      case ';' =>
        pos += 1
        tokenAt(TokenKind.Semicolon, start)
      case '@' =>
        pos += 1
        tokenAt(TokenKind.At, start)
      case '_' if !isIdentStartAt(pos + 1) =>
        pos += 1
        tokenAt(TokenKind.Underscore, start)

      // Multi-character operators with shared prefix
      case ':' =>
        pos += 1
        engageOffside()
        tokenAt(TokenKind.Colon, start)
      case '.' =>
        pos += 1
        tokenAt(TokenKind.Dot, start)
      case '=' =>
        if peek(1) == '>' then
          pos += 2
          engageOffside()
          tokenAt(TokenKind.FatArrow, start)
        else if peek(1) == '=' then
          pos += 2
          tokenAt(TokenKind.EqEq, start)
        else
          pos += 1
          engageOffside()
          tokenAt(TokenKind.Eq, start)
      case '-' =>
        if peek(1) == '>' then
          pos += 2
          engageOffside()
          tokenAt(TokenKind.Arrow, start)
        else
          pos += 1
          tokenAt(TokenKind.Minus, start)
      case '<' =>
        if peek(1) == '-' then
          pos += 2
          tokenAt(TokenKind.BackArrow, start)
        else if peek(1) == '=' then
          pos += 2
          tokenAt(TokenKind.Le, start)
        else
          pos += 1
          tokenAt(TokenKind.Lt, start)
      case '>' =>
        if peek(1) == '=' then
          pos += 2
          tokenAt(TokenKind.Ge, start)
        else
          pos += 1
          tokenAt(TokenKind.Gt, start)
      case '!' =>
        if peek(1) == '=' then
          pos += 2
          tokenAt(TokenKind.Neq, start)
        else
          pos += 1
          tokenAt(TokenKind.Bang, start)
      case '|' =>
        if peek(1) == '>' then
          pos += 2
          tokenAt(TokenKind.PipeForward, start)
        else if peek(1) == '|' then
          pos += 2
          tokenAt(TokenKind.OrOr, start)
        else
          pos += 1
          tokenAt(TokenKind.Pipe, start)
      case '&' =>
        if peek(1) == '&' then
          pos += 2
          tokenAt(TokenKind.AndAnd, start)
        else
          reporter.error("E003", Span(start, start + 1), "stray `&` (did you mean `&&`?)")
          pos += 1
          tokenAt(TokenKind.Error, start)
      case '+' =>
        pos += 1
        tokenAt(TokenKind.Plus, start)
      case '*' =>
        pos += 1
        tokenAt(TokenKind.Star, start)
      case '/' =>
        pos += 1
        tokenAt(TokenKind.Slash, start)
      case '%' =>
        pos += 1
        tokenAt(TokenKind.Percent, start)

      // Identifiers / keywords
      case ch if isIdentStart(ch) => scanIdent(start)

      // Numeric literals
      case ch if ch.isDigit => scanNumber(start)

      // String literal
      case '"' => scanString(start)

      // Char literal
      case '\'' => scanChar(start)

      case other =>
        reporter.error(
          "E004",
          Span(start, start + 1),
          s"unexpected character ${describeChar(other)}"
        )
        pos += 1
        tokenAt(TokenKind.Error, start)

  // ---- Helpers ----

  private def peek(offset: Int): Char =
    if pos + offset < source.length then source.content.charAt(pos + offset) else ' '

  private def peekAt(absOffset: Int): Char =
    if absOffset < source.length then source.content.charAt(absOffset) else ' '

  private def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'
  private def isIdentPart(c: Char): Boolean  = c.isLetterOrDigit || c == '_'

  private def isIdentStartAt(offset: Int): Boolean =
    offset < source.length && isIdentStart(source.content.charAt(offset))

  private def synthetic(kind: TokenKind): Token =
    Token(kind, Span(pos, pos))

  private def tokenAt(kind: TokenKind, start: Int): Token =
    Token(kind, Span(start, pos))

  private def tokenAtWithLexeme(kind: TokenKind, start: Int): Token =
    Token(kind, Span(start, pos), source.slice(start, pos))

  /** No-op kept for callsite readability — body engagement is tracked
    * implicitly via `indentStack` (any push above the base [0] level
    * indicates an engaged body). */
  private def engageOffside(): Unit = ()

  private def popBracket(expected: Char): Unit =
    if bracketStack.nonEmpty && bracketStack.last._1 == expected then
      val _ = bracketStack.remove(bracketStack.length - 1)
    else
      reporter.error(
        "E005",
        Span(pos - 1, pos),
        s"unmatched closing bracket — expected to close `$expected` but bracket stack is ${if bracketStack.isEmpty then "empty" else s"`${bracketStack.last._1}`"}"
      )

  private def scanIdent(start: Int): Token =
    while pos < source.length && isIdentPart(source.content.charAt(pos)) do
      pos += 1
    val text = source.slice(start, pos)
    Tokens.keywords.get(text) match
      case Some(kw) => Token(kw, Span(start, pos), text)
      case None =>
        val kind =
          if text.charAt(0).isUpper then TokenKind.UpperIdent
          else TokenKind.LowerIdent
        Token(kind, Span(start, pos), text)

  private def scanNumber(start: Int): Token =
    while pos < source.length && source.content.charAt(pos).isDigit do
      pos += 1
    // Float? '.' followed by another digit (don't eat e.g. `5.foo` — that's
    // a method call on integer 5, not "5." float literal followed by `foo`).
    if pos < source.length && source.content.charAt(pos) == '.'
       && pos + 1 < source.length && source.content.charAt(pos + 1).isDigit
    then
      pos += 1 // consume '.'
      while pos < source.length && source.content.charAt(pos).isDigit do
        pos += 1
      tokenAtWithLexeme(TokenKind.FloatLit, start)
    else
      tokenAtWithLexeme(TokenKind.IntLit, start)

  private def scanString(start: Int): Token =
    pos += 1 // consume opening "
    val buf = new StringBuilder
    var closed = false
    while pos < source.length && !closed do
      val c = source.content.charAt(pos)
      if c == '"' then
        pos += 1
        closed = true
      else if c == '\\' && pos + 1 < source.length then
        val esc = source.content.charAt(pos + 1)
        val translated = esc match
          case 'n'  => '\n'
          case 't'  => '\t'
          case 'r'  => '\r'
          case '\\' => '\\'
          case '"'  => '"'
          case '\'' => '\''
          case '0'  => ' '
          case other =>
            reporter.error(
              "E006",
              Span(pos, pos + 2),
              s"unknown escape sequence `\\$other`"
            )
            other
        buf += translated
        pos += 2
      else if c == '\n' then
        reporter.error(
          "E007",
          Span(start, pos),
          "unterminated string literal — closing `\"` missing on this line",
          Some("string literals must close on the same line in v0.4.5")
        )
        closed = true // bail out of loop without consuming newline
      else
        buf += c
        pos += 1
    if !closed then
      reporter.error(
        "E007",
        Span(start, pos),
        "unterminated string literal at end of input"
      )
    Token(TokenKind.StringLit, Span(start, pos), buf.toString)

  private def scanChar(start: Int): Token =
    pos += 1 // consume opening '
    val buf = new StringBuilder
    var closed = false
    while pos < source.length && !closed do
      val c = source.content.charAt(pos)
      if c == '\'' then
        pos += 1
        closed = true
      else if c == '\\' && pos + 1 < source.length then
        val esc = source.content.charAt(pos + 1)
        val translated = esc match
          case 'n'  => '\n'
          case 't'  => '\t'
          case 'r'  => '\r'
          case '\\' => '\\'
          case '\'' => '\''
          case '"'  => '"'
          case '0'  => ' '
          case other =>
            reporter.error(
              "E006",
              Span(pos, pos + 2),
              s"unknown escape sequence `\\$other`"
            )
            other
        buf += translated
        pos += 2
      else if c == '\n' then
        reporter.error("E008", Span(start, pos), "unterminated char literal")
        closed = true
      else
        buf += c
        pos += 1
    if !closed then
      reporter.error("E008", Span(start, pos), "unterminated char literal at end of input")
    if buf.length != 1 then
      reporter.error(
        "E009",
        Span(start, pos),
        s"char literal must contain exactly one character (got ${buf.length})"
      )
    Token(TokenKind.CharLit, Span(start, pos), buf.toString)

  private def describeChar(c: Char): String =
    if c < ' ' then f"\\u${c.toInt}%04x"
    else s"`$c`"

end Scanner

object Scanner:

  /** Convenience: tokenize a whole source file in one shot. */
  def tokenize(source: SourceFile, reporter: Reporter): Seq[Token] =
    new Scanner(source, reporter).tokenize()
