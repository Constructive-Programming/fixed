package fixed.parsing

import scala.annotation.tailrec

import cats.Monoid
import cats.data.NonEmptyList
import fixed.ast.Tree
import fixed.util.{DiagCode, Reporter, SourceFile, Span}

/** Hand-written recursive-descent parser. The core is pure: every production is a
  * `ParserState => Parsed[A]` rule, with `Reporter` as a context parameter (the only impurity), and
  * `ParserState` carries the token cursor, the recovery-anchor stack and the owed-DEDENT count.
  * Productions live in `DeclParsers`, `TypeParsers` and `ExprParsers`; this file holds the state,
  * combinators, recovery and entry points. A production that owns a synchronisation context pushes
  * anchors with `withAnchors` and calls `syncToAnchors` on failure (`spec/syntax_grammar.ebnf`
  * Appendix A.1). Unfinished productions (`mod`, `pub`, …) report "not yet implemented" and leave a
  * `Trees.Error` gap.
  */
final class Parser(scanner: Scanner, reporter: Reporter):
  import Parser.initialState

  /** The Scanner's token array, shared (not copied): `current`/`peek`
    * index it on every cursor advance. Logically immutable — nothing
    * writes to it after the scanner builds it.
    */
  private[parsing] val tokens: Array[Token] = scanner.tokenArray

  /** Parse the whole compilation unit. */
  def parseCompilationUnit(): Tree =
    val (_, tree) = Parser.parseCompilationUnit(initialState(tokens))(using reporter)
    tree

  /** Parse a single expression; primarily used by tests. */
  def parseExpr(): Tree =
    val (_, tree) = ExprParsers.parseExpr(initialState(tokens))(using reporter)
    tree

  /** Parse a single type expression; primarily used by tests. */
  def parseTypeExpr(): Tree =
    val (_, tree) = TypeParsers.parseTypeExpr(initialState(tokens))(using reporter)
    tree

end Parser

object Parser:
  import DeclParsers.parseTopItem

  /** The vocabulary every parser file needs, re-exported so `DeclParsers`, `TypeParsers` and `ExprParsers` import only `Parser.*`. */
  export fixed.ast.{Tree, Trees}
  export fixed.util.{DiagCode, Reporter, Span}

  /** Parser state. All mutation is replaced by `copy` of this case
    * class. `tokens` is the full materialised token stream; `idx` is
    * the cursor; `anchors` is the recovery frame stack (innermost
    * first); `owedDedents` counts off-side DEDENTs that were exited
    * bracket-aware but not yet drained (see comment on `addOwed`
    * below). */
  final case class ParserState(
      tokens: Array[Token],
      idx: Int,
      anchors: List[KindSet],
      owedDedents: Int
  ):
    /** The current (next un-consumed) token. Always available — the
      * scanner has appended a final EOF to `tokens`. */
    inline def current: Token =
      if idx < tokens.length then tokens(idx)
      else tokens(tokens.length - 1)

    /** Peek the token `n` ahead of `current` (n=0 means current). */
    inline def peek(n: Int): Token =
      val j = idx + n
      if j < tokens.length then tokens(j)
      else tokens(tokens.length - 1)

    inline def advance: ParserState = copy(idx = idx + 1)

    /** Push a new anchor frame for a production. */
    def pushAnchors(a: KindSet): ParserState =
      copy(anchors = a :: anchors)

    /** Pop the innermost anchor frame on production exit. */
    def popAnchors: ParserState = anchors match
      case _ :: rest => copy(anchors = rest)
      case Nil       => this

    /** Increment the implicit-dedent counter — see the field comment
      * for the bookkeeping invariant. */
    def addOwed: ParserState = copy(owedDedents = owedDedents + 1)

    /** Decrement the implicit-dedent counter when a draining DEDENT
      * is consumed. */
    def consumeOwed: ParserState = copy(owedDedents = owedDedents - 1)

    /** True iff `k` is `,` / `)` / `]` / `}` AND an *outer* anchor
      * frame (any frame except the innermost) is waiting for it. Used
      * by indented-block productions (parseBlock, parseMatchCases,
      * parseDoStmts, parseHandlerClauses) to terminate an
      * implicitly-opened off-side body when the enclosing bracketed
      * production would otherwise see noise after an item. See
      * `spec/syntax_grammar.ebnf` "Re-enabling the off-side rule
      * inside brackets".
      *
      * `.drop(1)` skips the innermost frame — the one belonging to
      * *this* production — so the function answers "does some
      * enclosing production need this token?" rather than "does
      * anyone, including me?". */
    def isOuterBracketWaiter(k: TokenKind): Boolean =
      (k == TokenKind.Comma || k == TokenKind.RParen
        || k == TokenKind.RBracket || k == TokenKind.RBrace)
        && anchors.drop(1).exists(_.contains(k))

  end ParserState

  /** Result of a single production: the new state plus the produced value. */
  type Parsed[A] = (ParserState, A)

  /** A production: parse from a state, reporting into `Reporter`. */
  type Rule[A] = ParserState => Parsed[A]

  def initialState(tokens: Array[Token]): ParserState =
    ParserState(tokens = tokens, idx = 0, anchors = Nil, owedDedents = 0)

  private[parsing] inline def consume(s: ParserState): Parsed[Token] =
    (s.advance, s.current)

  private[parsing] inline def accept(s: ParserState, kind: TokenKind): (ParserState, Option[Token]) =
    if s.current.kind == kind then
      val (s1, t) = consume(s)
      (s1, Some(t))
    else (s, None)

  /** `kind rest`: expect the leading keyword, then hand the state after it
    * and the keyword token (for spans) to `rest`.
    */
  extension (s: ParserState)
    private[parsing] inline def afterKeyword[A](
        kind: TokenKind,
        what: String
    )(inline rest: (ParserState, Token) => Parsed[A])(
        using Reporter
    ): Parsed[A] =
      val (s1, tok) = expect(s, kind, what)
      rest(s1, tok)

  /** Optional pieces that default to the monoid's empty value (e.g. `Nil`). */
  extension [A: Monoid](s: ParserState)
    /** `kind p`?, consuming `kind`; empty when `kind` is absent. */
    private[parsing] inline def optionalOrEmpty(kind: TokenKind)(inline p: Rule[A]): Parsed[A] =
      val (s1, a) = s.optionally(kind)(p)
      (s1, a.getOrElse(Monoid[A].empty))

    /** `p` if `current` is `kind` (not consumed; `p` starts there), else empty. */
    private[parsing] inline def whenAtOrEmpty(kind: TokenKind)(inline p: Rule[A]): Parsed[A] =
      if s.current.kind == kind then p(s) else (s, Monoid[A].empty)

  /** item (sep item)* — one or more, so the result is non-empty by type. */
  private[parsing] inline def parseSepBy1[A](
      s0: ParserState,
      sep: TokenKind
  )(inline item: Rule[A]): Parsed[NonEmptyList[A]] =
    parseSepBy1Until(s0, sep)(item)(identity, _ => false)

  /** `parseSepBy1` with hooks run after each separator: `afterSep` adjusts the
    * state (e.g. drains trivia), and `stop` ends the list there (e.g. a
    * trailing `,` before the closing bracket).
    */
  private[parsing] inline def parseSepBy1Until[A](s0: ParserState, sep: TokenKind)(inline item: Rule[A])(
      inline afterSep: ParserState => ParserState,
      inline stop: ParserState => Boolean
  ): Parsed[NonEmptyList[A]] =
    val (s1, head) = item(s0)
    @tailrec
    def loop(st: ParserState, acc: List[A]): Parsed[List[A]] =
      accept(st, sep) match
        case (st1, Some(_)) =>
          val st2 = afterSep(st1)
          if stop(st2) then (st2, acc.reverse)
          else
            val (st3, a) = item(st2)
            loop(st3, a :: acc)
        case (st1, None) => (st1, acc.reverse)
    val (s2, tail) = loop(s1, Nil)
    (s2, NonEmptyList(head, tail))

  /** `kind p`?  If `current` is `kind`, consume it and run `p`; else no-op. */
  extension (s: ParserState)
    private[parsing] inline def optionally[A](kind: TokenKind)(
        inline p: Rule[A]
    ): Parsed[Option[A]] =
      accept(s, kind) match
        case (s1, Some(_)) =>
          val (s2, a) = p(s1); (s2, Some(a))
        case (s1, None) => (s1, None)

  /** Consume `current` if it matches `kind`; otherwise emit an error
    * and return a synthetic token with span at the current position so
    * the caller can continue building a tree.
    */
  private[parsing] def expect(
      s: ParserState,
      kind: TokenKind,
      what: String
  )(using Reporter): Parsed[Token] =
    if s.current.kind == kind then consume(s)
    else
      reportExpected(s, DiagCode.P001, s"$what (token $kind)", None)
      (s, Token.of(kind, Span(s.current.span.start, s.current.span.start)))

  /** Report "expected `what`, got …" at `current`. */
  private[parsing] def reportExpected(
      s: ParserState,
      code: DiagCode,
      what: String,
      hint: Option[String]
  )(using reporter: Reporter): Unit =
    reporter.error(code, s.current.span, s"expected $what, got ${s.current.kind}${describeLexeme(s.current)}", hint)

  /** `reportExpected`, then skip that one token and return its span for the caller's placeholder node. */
  extension (s: ParserState)
    private[parsing] def errorSkip(code: DiagCode, what: String)(using Reporter): Parsed[Span] =
      reportExpected(s, code, what, None)
      (s.advance, s.current.span)

  /** `reportExpected`, then sync to the next anchor and return an `Error` node spanning the skipped region. */
  extension (s: ParserState)
    private[parsing] def errorSync(code: DiagCode, what: String, hint: Option[String] = None)(using
        Reporter
    ): Parsed[Tree] =
      reportExpected(s, code, what, hint)
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(s.current.span, s1.current.span)))

  private[parsing] def describeLexeme(t: Token): String =
    if t.lexeme.isEmpty then "" else s" `${t.lexeme}`"

  /** Combine two spans. */
  private[parsing] def span(start: Span, end: Span): Span =
    Span(start.start, end.end)

  /** Skip zero or more NEWLINEs at the current position. */
  @tailrec
  private[parsing] def skipNewlines(s: ParserState): ParserState =
    if s.current.kind == TokenKind.Newline then skipNewlines(s.advance)
    else s

  /** Run `body` with `anchors` pushed; pop the frame on the way out. */
  private[parsing] inline def withAnchors[A](
      s: ParserState,
      anchors: KindSet
  )(body: ParserState => Parsed[A]): Parsed[A] =
    val (s1, a) = body(s.pushAnchors(anchors))
    (s1.popAnchors, a)

  /** Skip until `current.kind` is in the innermost active anchor frame
    * or EOF. Does NOT consume the anchor — the caller resumes from it. */
  @tailrec
  private[parsing] def syncToAnchors(s: ParserState): ParserState =
    s.anchors.headOption match
      case None        => s
      case Some(inner) =>
        if s.current.kind == TokenKind.Eof || inner.contains(s.current.kind) then s
        else syncToAnchors(s.advance)

  /** Count of DEDENT tokens that an inner block-style parser exited
    * bracket-aware (without consuming a matching DEDENT). The scanner
    * will eventually emit those DEDENTs when the indent stack collapses
    * — typically several blocks later, when a less-indented physical
    * line fires the line-end transition. Block-style parsers drain
    * these owed DEDENTs *before* expecting their own, otherwise the
    * owed DEDENTs masquerade as the wrong block's terminator and a
    * real DEDENT leaks out into a paren-expecting context.
    */
  @tailrec
  private[parsing] def absorbOwedDedents(s: ParserState): ParserState =
    if s.owedDedents > 0 && s.current.kind == TokenKind.Dedent then
      absorbOwedDedents(s.advance.consumeOwed)
    else s

  /** Drive an `INDENT item* DEDENT` body: `parseStep` folds each item into the accumulator
    * (returning `acc` unchanged records a recovered, dropped item). Ends on DEDENT, EOF or an
    * outer-bracket waiter (an implicit off-side body inside an argument list); NEWLINEs between items
    * are skipped and owed DEDENTs drained. Item dispatch and per-item diagnostics (P011, P018) live in
    * the callers' `parseStep`.
    */
  private[parsing] def parseIndentedBody[A](
      s0: ParserState,
      acc0: A
  )(parseStep: (ParserState, A) => Parsed[A])(using Reporter): Parsed[A] =
    val (s1, _) = expect(s0, TokenKind.Indent, "INDENT")
    val s2 = skipNewlines(s1)
    val (s3, acc) = withAnchors(s2, Anchors.blockBody) { st =>
      @tailrec
      def loop(state: ParserState, acc: A): Parsed[A] =
        if state.current.kind == TokenKind.Dedent
          || state.current.kind == TokenKind.Eof
          || state.isOuterBracketWaiter(state.current.kind)
        then (state, acc)
        else
          val (state1, acc1) = parseStep(state, acc)
          val state2 = skipNewlines(state1)
          loop(state2, acc1)
      loop(st, acc0)
    }
    val s4 = absorbOwedDedents(s3)
    val (s5, dedOpt) = accept(s4, TokenKind.Dedent)
    val s6 = if dedOpt.isDefined then s5 else s5.addOwed
    (s6, acc)

  private[parsing] object Anchors:
    /** Top-level item starts: keywords that begin a top-level production
      * plus UpperIdent (head of a `T satisfies …` decl) and EOF. NEWLINE
      * is a separator, not a starter, so it is not an anchor here. */
    val topLevel: KindSet = KindSet(
      TokenKind.KwUse,
      TokenKind.KwFn,
      TokenKind.KwFip,
      TokenKind.KwCap,
      TokenKind.KwData,
      TokenKind.KwEffect,
      TokenKind.KwLinear,
      TokenKind.KwType,
      TokenKind.KwMod,
      TokenKind.KwPub,
      TokenKind.UpperIdent,
      TokenKind.Eof
    )

    val blockBody: KindSet = KindSet(TokenKind.Newline, TokenKind.Dedent)

    /** Anything that can plausibly follow a type. Arrow is included so a
      * partial atom can sync forward and let `parseArrowType` resume. */
    val typeExpr: KindSet = KindSet(
      TokenKind.Comma,
      TokenKind.RParen,
      TokenKind.RBracket,
      TokenKind.Newline,
      TokenKind.Dedent,
      TokenKind.Eq,
      TokenKind.Arrow,
      TokenKind.KwWith
    )
  end Anchors

  def parseCompilationUnit(s0: ParserState)(using Reporter): Parsed[Tree] =
    val s1 = skipNewlines(s0)
    val (s2, items) = withAnchors(s1, Anchors.topLevel) { st =>
      /** Items at top level are separated by NEWLINE; allow blank lines. */
      @tailrec
      def loop(state: ParserState, acc: List[Tree]): Parsed[List[Tree]] =
        if state.current.kind == TokenKind.Eof then (state, acc.reverse)
        else
          val (state1, item) = parseTopItem(state)
          val state2 = skipNewlines(state1)
          loop(state2, item :: acc)
      loop(st, Nil)
    }
    val endSpan = s2.current.span
    (s2, Trees.CompilationUnit(items, span(s0.current.span, endSpan)))

  /** Drain trivia tokens between comma-list items inside brackets.
    * NEWLINEs are noise inside parens — always drain. DEDENTs are ours
    * to consume only when the matching block exited bracket-aware
    * (`owedDedents > 0`); a real outer DEDENT would otherwise be
    * silently swallowed on a recovery path. */
  @tailrec
  private[parsing] def drainListSeparators(s: ParserState): ParserState =
    s.current.kind match
      case TokenKind.Newline                     => drainListSeparators(s.advance)
      case TokenKind.Dedent if s.owedDedents > 0 =>
        drainListSeparators(s.advance.consumeOwed)
      case _ => s

  /** Parse a comma-separated list of items up to (but not including)
    * `closeKind`. Recovery: if `parseItem` leaves the parser on a token
    * that is neither `,` nor `closeKind`, emit a diagnostic, sync to
    * the next such token, and wrap the partial item in `Trees.Error`.
    * Trailing comma admitted. */
  /** Bracket pairs that delimit comma lists. `what` strings are the
    * `expect` descriptions, built once per case.
    */
  private[parsing] enum Bracket(
      val open: TokenKind,
      val openLexeme: String,
      val close: TokenKind,
      val closeLexeme: String
  ):
    case Paren extends Bracket(TokenKind.LParen, "(", TokenKind.RParen, ")")
    case Square extends Bracket(TokenKind.LBracket, "[", TokenKind.RBracket, "]")
    case Brace extends Bracket(TokenKind.LBrace, "{", TokenKind.RBrace, "}")
    case Angle extends Bracket(TokenKind.Lt, "<", TokenKind.Gt, ">")
    val openWhat: String = s"`$openLexeme`"
    val closeWhat: String = s"`$closeLexeme`"

  /** open item ("," item)* ","? close. Returns the items and the span from
    * open to close. Partial items are wrapped in `Trees.Error`.
    */
  private[parsing] inline def bracketed(s0: ParserState, b: Bracket)(
      inline item: Rule[Tree]
  )(using Reporter): Parsed[(List[Tree], Span)] =
    inBrackets(s0, b)(parseDelimited(_, b.close, b.closeLexeme)(item))

  /** `bracketed` for non-`Tree` items; partial items are kept verbatim. */
  private[parsing] inline def bracketedTyped[A](s0: ParserState, b: Bracket)(
      inline item: Rule[A]
  )(using Reporter): Parsed[(List[A], Span)] =
    inBrackets(s0, b)(parseDelimitedTyped(_, b.close, b.closeLexeme)(item))

  private[parsing] inline def inBrackets[A](s0: ParserState, b: Bracket)(
      inline items: ParserState => Parsed[List[A]]
  )(using Reporter): Parsed[(List[A], Span)] =
    val (s1, openTok) = expect(s0, b.open, b.openWhat)
    val (s2, xs) = items(s1)
    val (s3, closeTok) = expect(s2, b.close, b.closeWhat)
    (s3, (xs, span(openTok.span, closeTok.span)))

  private[parsing] inline def parseDelimited(
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String
  )(inline parseItem: Rule[Tree])(using Reporter): Parsed[List[Tree]] =
    parseDelimitedLoop[Tree](s0, closeKind, closeLexeme) { (st, itemStart) =>
      val (st1, item) = parseItem(st)
      (st1, item, Trees.Error(List(item), span(itemStart, st1.current.span)))
    }

  /** Comma-separated list whose item type is *not* `Tree`. On recovery
    * the partial item is kept verbatim (no `Trees.Error` wrapper) and
    * the diagnostic is still emitted; most A-typed items contain their
    * own internal `Trees.Error` subtrees on partial parses, so the
    * failure remains visible to downstream consumers. */
  private[parsing] inline def parseDelimitedTyped[A](
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String
  )(inline parseItem: Rule[A])(using Reporter): Parsed[List[A]] =
    parseDelimitedLoop[A](s0, closeKind, closeLexeme) { (st, _) =>
      val (st1, item) = parseItem(st)
      (st1, item, item)
    }

  /** Shared loop for comma lists. `parseItem` returns `(state, ok, recovered)`: the value for a clean
    * parse and the value after a P012 sync (`itemStart` lets `Tree` callers wrap the partial item in
    * `Trees.Error`). `inline` gives each call site a monomorphic loop; the shared version's
    * megamorphic Function3 blocked escape analysis and allocated a lambda per call.
    */
  private[parsing] inline def parseDelimitedLoop[A](
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String
  )(using reporter: Reporter)(inline parseItem: (ParserState, Span) => (ParserState, A, A)): Parsed[List[A]] =
    val anchors = KindSet.empty + TokenKind.Comma + closeKind

    def parseOne(st: ParserState): Parsed[A] =
      val itemStart = st.current.span
      val (st1, ok, recovered) = parseItem(st, itemStart)
      val st2 = drainListSeparators(st1)
      if anchors.contains(st2.current.kind) || st2.current.kind == TokenKind.Eof then
        (st2, ok)
      else
        reporter.error(
          DiagCode.P012,
          st2.current.span,
          s"unexpected ${st2.current.kind}${describeLexeme(st2.current)} in list; expected `,` or `$closeLexeme`",
          Some(s"add `,` to continue or `$closeLexeme` to close the list")
        )
        val st3 = syncToAnchors(st2)
        (st3, recovered)

    withAnchors(s0, anchors) { st =>
      if st.current.kind == closeKind then (st, Nil)
      else
        val (st1, items) = parseSepBy1Until(st, TokenKind.Comma)(parseOne)(
          drainListSeparators,
          /** trailing comma */
          _.current.kind == closeKind
        )
        (st1, items.toList)
    }

  /** Parse a whole file into a [[ParseResult]] (tree, diagnostics, trivia). Uses a private Reporter
    * internally, so callers need none.
    */
  def parse(source: SourceFile): ParseResult =
    val privateReporter = new Reporter(source)
    val scanner = new Scanner(source, privateReporter)
    val parser = new Parser(scanner, privateReporter)
    val tree = parser.parseCompilationUnit()

    ParseResult(tree, privateReporter.diagnostics.toList, scanner.triviaTable)

  /** Adapter for callers that still pass a Reporter: parse and push the diagnostics into it. */
  def parse(source: SourceFile, reporter: Reporter): Tree =
    val result = parse(source)
    result.diagnostics.foreach(reporter.add)
    result.tree

end Parser
