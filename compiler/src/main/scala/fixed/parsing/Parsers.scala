package fixed.parsing

import scala.annotation.tailrec

import cats.Monoid
import cats.data.NonEmptyList
import fixed.ast.{Tree, Trees}
import fixed.util.{DiagCode, Reporter, SourceFile, Span}

/** Hand-written recursive-descent parser over the token stream produced
  * by [[Scanner]]. The pure-functional core lives in `object Parser`:
  * every production is a `(ParserState, Reporter) -> (ParserState, A)`
  * function, with `ParserState` carrying the token cursor, recovery
  * anchor stack, and the off-side-implicit DEDENT counter as immutable
  * fields. The `class Parser` wrapper materialises the token stream
  * once and drives the pipeline — there is no internal mutable state.
  *
  * The only impurity is diagnostic emission via the supplied `Reporter`,
  * matching the [[Scanner]] discipline established in commits
  * `2fff493` and `7395346`.
  *
  * Some productions (`prop`, `forall`, struct literals, `mod`, `pub`)
  * are still stubbed in places — they emit an explicit "not yet
  * implemented" diagnostic and a `Trees.Error` gap node, leaving the
  * surrounding parse intact.
  *
  * Recovery: every production that owns a synchronisation context
  * pushes an anchor frame via `withAnchors` and may call `syncToAnchors`
  * on failure; see `spec/syntax_grammar.ebnf` Appendix A.1 for the
  * normative anchor set per context.
  */
final class Parser(scanner: Scanner, reporter: Reporter):
  import Parser.initialState

  // Reuse the Scanner's already-materialised token vector and project
  // it into an `Array[Token]` for cheap O(1) indexing. The array is
  // logically immutable: it is written once at parser construction and
  // never re-assigned or mutated. `current`/`peek` execute in every
  // cursor advance, so saving the Vector trie traversal pays back
  // across the whole parse pass.
  //
  // `Vector.toArray` walks the vector once to build the array. The
  // alternative — leaving the stream as `Vector[Token]` — re-pays the
  // trie cost on every read. The materialisation is the right trade.
  private val tokens: Array[Token] = scanner.tokenize().toArray

  /** Parse the whole compilation unit. */
  def parseCompilationUnit(): Tree =
    val (_, tree) = Parser.parseCompilationUnit(initialState(tokens))(using reporter)
    tree

  /** Parse a single expression; primarily used by tests. */
  def parseExpr(): Tree =
    val (_, tree) = Parser.parseExpr(initialState(tokens))(using reporter)
    tree

  /** Parse a single type expression; primarily used by tests. */
  def parseTypeExpr(): Tree =
    val (_, tree) = Parser.parseTypeExpr(initialState(tokens))(using reporter)
    tree

end Parser

object Parser:

  // ---- State ----

  /** Parser state. All mutation is replaced by `copy` of this case
    * class. `tokens` is the full materialised token stream; `idx` is
    * the cursor; `anchors` is the recovery frame stack (innermost
    * first); `owedDedents` counts off-side DEDENTs that were exited
    * bracket-aware but not yet drained (see comment on `addOwed`
    * below). */
  final case class ParserState(
      tokens: Array[Token],
      idx: Int,
      anchors: List[Set[TokenKind]],
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
    def pushAnchors(a: Set[TokenKind]): ParserState =
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
      * by indented-block productions (parseBlock, parseMatchArms,
      * parseDoStmts, parseHandlerArms) to terminate an
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

  // ---- Cursor primitives ----

  private inline def consume(s: ParserState): Parsed[Token] =
    (s.advance, s.current)

  private inline def accept(s: ParserState, kind: TokenKind): (ParserState, Option[Token]) =
    if s.current.kind == kind then
      val (s1, t) = consume(s)
      (s1, Some(t))
    else (s, None)

  /** `kind rest`: expect the leading keyword, then hand the state after it
    * and the keyword token (for spans) to `rest`.
    */
  extension (s: ParserState)
    private inline def afterKeyword[A](kind: TokenKind, what: String)(inline rest: (ParserState, Token) => Parsed[A])(
        using Reporter
    ): Parsed[A] =
      val (s1, tok) = expect(s, kind, what)
      rest(s1, tok)

  // Optional pieces that default to the monoid's empty value (e.g. `Nil`).
  extension [A: Monoid](s: ParserState)
    /** `kind p`?, consuming `kind`; empty when `kind` is absent. */
    private inline def optionalOrEmpty(kind: TokenKind)(inline p: Rule[A]): Parsed[A] =
      val (s1, a) = s.optionally(kind)(p)
      (s1, a.getOrElse(Monoid[A].empty))

    /** `p` if `current` is `kind` (not consumed; `p` starts there), else empty. */
    private inline def whenAtOrEmpty(kind: TokenKind)(inline p: Rule[A]): Parsed[A] =
      if s.current.kind == kind then p(s) else (s, Monoid[A].empty)

  /** item (sep item)* — one or more, so the result is non-empty by type. */
  private inline def parseSepBy1[A](s0: ParserState, sep: TokenKind)(inline item: Rule[A]): Parsed[NonEmptyList[A]] =
    parseSepBy1Until(s0, sep)(item)(identity, _ => false)

  /** `parseSepBy1` with hooks run after each separator: `afterSep` adjusts the
    * state (e.g. drains trivia), and `stop` ends the list there (e.g. a
    * trailing `,` before the closing bracket).
    */
  private inline def parseSepBy1Until[A](s0: ParserState, sep: TokenKind)(inline item: Rule[A])(
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
    private inline def optionally[A](kind: TokenKind)(
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
  private def expect(
      s: ParserState,
      kind: TokenKind,
      what: String
  )(using Reporter): Parsed[Token] =
    if s.current.kind == kind then consume(s)
    else
      reportExpected(s, DiagCode.P001, s"$what (token $kind)", None)
      (s, Token.of(kind, Span(s.current.span.start, s.current.span.start)))

  /** Report "expected `what`, got …" at `current`. */
  private def reportExpected(
      s: ParserState,
      code: DiagCode,
      what: String,
      hint: Option[String]
  )(using reporter: Reporter): Unit =
    reporter.error(code, s.current.span, s"expected $what, got ${s.current.kind}${describeLexeme(s.current)}", hint)

  /** `reportExpected`, then skip that one token and return its span for the caller's placeholder node. */
  extension (s: ParserState)
    private def errorSkip(code: DiagCode, what: String)(using Reporter): Parsed[Span] =
      reportExpected(s, code, what, None)
      (s.advance, s.current.span)

  /** `reportExpected`, then sync to the next anchor and return an `Error` node spanning the skipped region. */
  extension (s: ParserState)
    private def errorSync(code: DiagCode, what: String, hint: Option[String] = None)(using Reporter): Parsed[Tree] =
      reportExpected(s, code, what, hint)
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(s.current.span, s1.current.span)))

  private def describeLexeme(t: Token): String =
    if t.lexeme.isEmpty then "" else s" `${t.lexeme}`"

  /** Combine two spans. */
  private def span(start: Span, end: Span): Span =
    Span(start.start, end.end)

  // ---- Helpers ----

  /** Skip zero or more NEWLINEs at the current position. */
  @tailrec
  private def skipNewlines(s: ParserState): ParserState =
    if s.current.kind == TokenKind.Newline then skipNewlines(s.advance)
    else s

  /** Run `body` with `anchors` pushed; pop the frame on the way out. */
  private inline def withAnchors[A](
      s: ParserState,
      anchors: Set[TokenKind]
  )(body: ParserState => Parsed[A]): Parsed[A] =
    val (s1, a) = body(s.pushAnchors(anchors))
    (s1.popAnchors, a)

  /** Skip until `current.kind` is in the innermost active anchor frame
    * or EOF. Does NOT consume the anchor — the caller resumes from it. */
  @tailrec
  private def syncToAnchors(s: ParserState): ParserState =
    s.anchors.headOption match
      case None        => s
      case Some(inner) =>
        if s.current.kind == TokenKind.Eof || inner.contains(s.current.kind) then s
        else syncToAnchors(s.advance)

  // Count of DEDENT tokens that an inner block-style parser exited
  // bracket-aware (without consuming a matching DEDENT). The scanner
  // will eventually emit those DEDENTs when the indent stack collapses
  // — typically several blocks later, when a less-indented physical
  // line fires the line-end transition. Block-style parsers drain
  // these owed DEDENTs *before* expecting their own, otherwise the
  // owed DEDENTs masquerade as the wrong block's terminator and a
  // real DEDENT leaks out into a paren-expecting context.
  @tailrec
  private def absorbOwedDedents(s: ParserState): ParserState =
    if s.owedDedents > 0 && s.current.kind == TokenKind.Dedent then
      absorbOwedDedents(s.advance.consumeOwed)
    else s

  /** Drive an `INDENT body DEDENT` block: parse the INDENT, run
    * `parseStep` for each item, then drain owed DEDENTs and either
    * consume the matching DEDENT or owe it. The body terminates on
    * DEDENT, EOF, or an outer-bracket waiter (the off-side body was
    * implicit inside an arg list and the enclosing `parseDelimited`
    * needs the comma/close). NEWLINEs between items are skipped.
    *
    * Used by every indented-body production: `parseBlock`,
    * `parseDoStmts`, `parseMatchArms`, `parseHandlerArms`,
    * `parseDataBody`, `parseCapBody`, `parseSatisfiesBody`,
    * `parseEffectMembers`. Body productions that own dispatch logic
    * (variant-vs-prop, fn-vs-Self.fn-vs-prop) put it inside parseStep;
    * productions with per-statement validity checks (`parseBlock`,
    * `parseDoStmts`) emit their own P011/P018 diagnostics from inside
    * parseStep, since the post-statement legality check is per-item.
    *
    * `parseStep` takes the current state and current accumulator and
    * returns the next state + new accumulator. Returning `acc` unchanged
    * is how a recovery branch records that no item was produced (the
    * dispatch caller still emitted its diagnostic and synced). */
  private def parseIndentedBody[A](
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

  private object Anchors:
    /** Top-level item starts: keywords that begin a top-level production
      * plus UpperIdent (head of a `T satisfies …` decl) and EOF. NEWLINE
      * is a separator, not a starter, so it is not an anchor here. */
    val topLevel: Set[TokenKind] = Set(
      TokenKind.KwUse,
      TokenKind.KwFn,
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

    val blockBody: Set[TokenKind] = Set(TokenKind.Newline, TokenKind.Dedent)

    /** Anything that can plausibly follow a type. Arrow is included so a
      * partial atom can sync forward and let `parseArrowType` resume. */
    val typeExpr: Set[TokenKind] = Set(
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

  // ---- Compilation unit ----

  def parseCompilationUnit(s0: ParserState)(using Reporter): Parsed[Tree] =
    val s1 = skipNewlines(s0)
    val (s2, items) = withAnchors(s1, Anchors.topLevel) { st =>
      // Items at top level are separated by NEWLINE; allow blank lines.
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

  // ---- Top items ----

  private def parseTopItem(s: ParserState)(using reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwUse      => parseUseDecl(s)
    case TokenKind.KwFn       => parseFnLike(s, FnKind.Free)
    case TokenKind.KwCap      => s.afterKeyword(TokenKind.KwCap, "`cap`")(parseCapDeclRest)
    case TokenKind.KwData     => parseDataDecl(s)
    case TokenKind.KwEffect   => parseEffectDecl(s)
    case TokenKind.KwLinear   => parseEffectDecl(s)
    case TokenKind.KwType     => parseTypeAlias(s)
    case TokenKind.KwMod      => parseModDecl(s)
    case TokenKind.KwPub      => unsupported(s, "`pub` modifier")
    case TokenKind.UpperIdent => parseSatisfiesDecl(s)
    // A primitive (LowerIdent) followed by `satisfies` is also a
    // satisfaction declaration (e.g. `u64 satisfies Optional of Self`).
    case TokenKind.LowerIdent if s.peek(1).kind == TokenKind.KwSatisfies =>
      parseSatisfiesDecl(s)
    case _ =>
      // Unknown token at top level. Emit a diagnostic, then synchronise
      // to the next decl introducer (or EOF). This collapses runs of
      // junk into a single Error item rather than one per stray token.
      val startSpan = s.current.span
      reporter.error(
        DiagCode.P002,
        startSpan,
        s"unexpected token at top level: ${s.current.kind}${describeLexeme(s.current)}",
        Some("expected `use`, `fn`, `cap`, `data`, `effect`, `type`, `mod`, or `<TypeName> satisfies …`")
      )
      // syncToAnchors stops at the next anchor (top-level keyword or EOF)
      // without consuming it, so the outer loop dispatches that token to
      // its proper handler.
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(startSpan, s1.current.span)))

  private def unsupported(s: ParserState, what: String)(using reporter: Reporter): Parsed[Tree] =
    reporter.error(
      DiagCode.P099,
      s.current.span,
      s"$what is not yet implemented",
      Some("file an issue or extend Parsers.scala")
    )
    val (s1, tok) = consume(s)
    (s1, Trees.Error(Nil, tok.span))

  // ---- UseDecl ----

  // UseDecl ::= "use" Path ("satisfies" CapBound)?
  // Path     ::= Ident ("." Ident)* ("." "{" Ident ("," Ident)* ","? "}")?
  // The trailing brace-group is sugar for one import per listed name
  // sharing the dotted prefix; the parser stores them in `selectors`.
  private def parseUseDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwUse, "`use`") { (s1, startTok) =>
      val (s2, head) = parseIdentTextEither(s1)
      // Walk `.Ident` segments, optionally terminated by `.{ name, ... }`.
      @tailrec
      def loop(st: ParserState, path: List[String]): (ParserState, List[String], List[String]) =
        if st.current.kind != TokenKind.Dot then (st, path.reverse, Nil)
        else
          val st1 = st.advance
          if st1.current.kind == TokenKind.LBrace then
            val (st2, (sels, _)) = bracketedTyped(st1, Bracket.Brace)(parseIdentTextEither)
            (st2, path.reverse, sels)
          else
            val (st2, seg) = parseIdentTextEither(st1)
            loop(st2, seg :: path)
      val (s3, pathOut, selectors) = loop(s2, head :: Nil)
      val (s4, satisfies) = s3.optionally(TokenKind.KwSatisfies)(parseCapRef)
      val endSpan = s4.current.span
      (s4, Trees.UseDecl(pathOut, selectors, satisfies, span(startTok.span, endSpan)))
    }

  private def parseIdentTextEither(s: ParserState)(using Reporter): Parsed[String] =
    s.current.kind match
      case TokenKind.LowerIdent | TokenKind.UpperIdent =>
        val (s1, tok) = consume(s)
        (s1, tok.lexeme)
      case _ =>
        reportExpected(s, DiagCode.P003, "an identifier", None)
        (s, "<error>")

  // ---- FnDecl (free fns and cap/effect/satisfies methods) ----

  // FnDecl ::= "fn" LOWER_IDENT TypeParamsHint? FnParamList FnSigTail          (Free)
  // InstanceMethod ::= "fn" LOWER_IDENT TypeParamsHint? FnParamList? FnSigTail (Instance)
  // StaticMethod ::= "Self" "." "fn" LOWER_IDENT TypeParamsHint? FnParamList? FnSigTail (Static)
  /** Which function-shaped declaration to parse, with its diagnostic noun and the node to build. */
  private enum FnKind(
      val nameWhat: String,
      val build: (Trees.FnSig, Span) => Trees.FnLike
  ):
    case Free extends FnKind("function name", Trees.FnDecl.apply)
    case Instance extends FnKind("method name", Trees.InstanceMethod.apply)
    case Static extends FnKind("static method name", Trees.StaticMethod.apply)

    /** The leading keyword(s): `fn`, or `Self . fn` for statics. Returns the first token, for the span. */
    def head(s0: ParserState)(using Reporter): Parsed[Token] = this match
      case Static =>
        val (s1, selfTok) = expect(s0, TokenKind.KwSelf, "`Self`")
        val (s2, _) = expect(s1, TokenKind.Dot, "`.`")
        val (s3, _) = expect(s2, TokenKind.KwFn, "`fn`")
        (s3, selfTok)
      case Free | Instance => expect(s0, TokenKind.KwFn, "`fn`")

    /** Free fns require a parameter list; methods may omit it. */
    def params(s: ParserState)(using Reporter): Parsed[List[Trees.FnParam]] = this match
      case Free              => parseFnParamList(s)
      case Instance | Static => s.whenAtOrEmpty(TokenKind.LParen)(parseFnParamList)

  private def parseFnLike(s0: ParserState, kind: FnKind)(using Reporter): Parsed[Trees.FnLike] =
    val (s1, startTok) = kind.head(s0)
    val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, kind.nameWhat)
    val (s3, typeParamsHint) = parseOptionalTypeParamsHint(s2)
    val (s4, params) = kind.params(s3)
    val (s5, (returnType, withClause, body)) = parseFnSigTail(s4)
    (
      s5,
      kind.build(
        Trees.FnSig(nameTok.lexeme, typeParamsHint, params, returnType, withClause, body),
        span(startTok.span, s5.current.span)
      )
    )

  private def parseFnParamList(s0: ParserState)(using Reporter): Parsed[List[Trees.FnParam]] =
    val (s1, (params, _)) = bracketedTyped(s0, Bracket.Paren)(parseFnParam)
    (s1, params)

  private def parseFieldList(s0: ParserState)(using Reporter): Parsed[List[Trees.FieldDecl]] =
    val (s1, (fields, _)) = bracketedTyped(s0, Bracket.Paren)(parseBinder(_, "field name")(Trees.FieldDecl.apply))
    (s1, fields)

  private def parseFnParam(s0: ParserState)(using Reporter): Parsed[Trees.FnParam] =
    parseBinder(s0, "parameter name")(Trees.FnParam.apply)

  // `name : Type (= default)?`
  private def parseBinder[B <: Trees.Binder](s0: ParserState, what: String)(
      mk: (Trees.Binding, Span) => B
  )(using Reporter): Parsed[B] =
    val (s1, nameTok) = expect(s0, TokenKind.LowerIdent, what)
    val (s4, (ty, default)) = parseTypedBinderTail(s1)
    (s4, mk(Trees.Binding(nameTok.lexeme, ty, default), span(nameTok.span, s4.current.span)))

  // Shared tail of fn decls and cap methods: `(-> TypeExpr)? WithClause? (= FnBody)?`. FnBody is an inline-or-block expression.
  private def parseFnSigTail(s0: ParserState)(using Reporter): Parsed[(Option[Tree], Option[Tree], Option[Tree])] =
    val (s1, returnType) = s0.optionally(TokenKind.Arrow)(parseTypeExpr)
    val (s2, withClause) = parseOptionalWithClause(s1)
    val (s3, body) = s2.optionally(TokenKind.Eq)(parseInlineOrBlockExpr)
    (s3, (returnType, withClause, body))

  // Shared tail of fn params, `of (...)` value params and data fields: `: TypeExpr (= Expr)?`.
  private def parseTypedBinderTail(s0: ParserState)(using Reporter): Parsed[(Tree, Option[Tree])] =
    val (s1, _) = expect(s0, TokenKind.Colon, "`:`")
    val (s2, ty) = parseTypeExpr(s1)
    val (s3, default) = s2.optionally(TokenKind.Eq)(parseExpr)
    (s3, (ty, default))

  private def parseOptionalWithClause(s0: ParserState)(using Reporter): Parsed[Option[Tree]] =
    s0.optionally(TokenKind.KwWith): s1 =>
      val (s2, row) = parseEffectRow(s1)
      (s2, Trees.WithClause(row, row.span))

  private def parseEffectRow(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, bounds) = parseSepBy1(s0, TokenKind.Plus)(parseEffectBound)
    if bounds.tail.isEmpty then (s1, bounds.head)
    else (s1, Trees.EffectRow(bounds.toList, span(bounds.head.span, bounds.last.span)))

  // ---- Block ----

  /** Parse `INDENT stmt (NEWLINE stmt)* DEDENT` as a Block. Two ways
    * the block can end:
    *   (1) DEDENT — the normal case for top-level fn bodies.
    *   (2) An outer-bracket waiter (`,` / `)` / `]` / `}`) at the body
    *       indent — the body was a re-engaged off-side region inside a
    *       paren-suppressed context (e.g. a multi-line lambda body
    *       inside an arg list). The scanner has not yet emitted DEDENT
    *       at that point; the enclosing `parseDelimited` will drain
    *       the DEDENT once the comma/close is consumed.
    *
    * Recovery: a statement that leaves the parser on a non-anchor,
    * non-bracket-waiter token gets a P011 diagnostic, syncs to the next
    * boundary, and is wrapped in `Trees.Error`. */
  private def parseBlock(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, stmts) = parseStatementList(s0, DiagCode.P011, "statement")(parseStatement)
    (s1, Trees.Block(stmts, span(s0.current.span, s1.current.span)))

  /** Indented list in source order. `step` parses one entry, or returns
    * `None` after reporting and recovering from one it rejects.
    */
  extension (s: ParserState)
    private inline def indentedList[A](inline step: Rule[Option[A]])(using Reporter): Parsed[List[A]] =
      val (s1, items) = parseIndentedBody(s, List.empty[A]) { (state, acc) =>
        val (state1, item) = step(state)
        (state1, item.fold(acc)(_ :: acc))
      }
      (s1, items.reverse)

  /** INDENT item (NEWLINE item)* DEDENT, in source order. Each item must end
    * its line; trailing junk is reported as `code` ("unexpected … after
    * `what`"), skipped to the next anchor, and the item wrapped in
    * `Trees.Error`.
    */
  private inline def parseStatementList(s0: ParserState, code: DiagCode, what: String)(
      inline item: Rule[Tree]
  )(using reporter: Reporter): Parsed[List[Tree]] =
    val (s1, stmts) = parseIndentedBody(s0, List.empty[Tree]) { (state, acc) =>
      val stmtStart = state.current.span
      val (state1, stmt) = item(state)
      if Anchors.blockBody.contains(state1.current.kind)
        || state1.current.kind == TokenKind.Eof
        || state1.isOuterBracketWaiter(state1.current.kind)
      then (state1, stmt :: acc)
      else
        reporter.error(
          code,
          state1.current.span,
          s"unexpected ${state1.current.kind}${describeLexeme(state1.current)} after $what; expected end of line",
          Some("split this expression onto its own line")
        )
        val state2 = syncToAnchors(state1)
        (state2, Trees.Error(List(stmt), span(stmtStart, state2.current.span)) :: acc)
    }
    (s1, stmts.reverse)

  // Fixed has no statements — every block element is an Expr, with two
  // exceptions admissible in any block:
  //   - `fn name(...) = body` — nested local function.
  //   - `prop name: body` — invariant (used in `fn -> cap` bodies).
  private def parseStatement(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwFn   => parseFnLike(s, FnKind.Free)
    case TokenKind.KwProp => parsePropDecl(s)
    case _                => parseExpr(s)

  // ---- Type expressions ----

  // TypeExpr ::= ArrowType ::= ArrowLhs ("->" ArrowType WithClause?)?
  // The anchor frame lets a failed `parseTypeAtom` sync to whatever
  // plausibly follows a type.
  def parseTypeExpr(s: ParserState)(using Reporter): Parsed[Tree] =
    withAnchors(s, Anchors.typeExpr) { st => parseArrowType(st) }

  private def parseArrowType(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, lhs) = parseArrowLhs(s0)
    accept(s1, TokenKind.Arrow) match
      case (s2, Some(_)) =>
        val (s3, rhs) = parseArrowType(s2)
        val (s4, withClause) = parseOptionalWithClause(s3)
        (s4, Trees.ArrowType(lhs, rhs, withClause, span(lhs.span, s4.current.span)))
      case (s2, None) => (s2, lhs)

  /** Shape of a parenthesised group whose `(` is already consumed. */
  private enum Paren:
    case Empty(span: Span)
    case One(t: Tree)
    case Many(ts: List[Tree], span: Span)

  // `)` | item `)` | item `,` items `)`. Always consumes the closing paren
  // (or reports it missing); `start` is the span of the opening `(`.
  private inline def parseParenBody(s1: ParserState, start: Span)(
      inline item: Rule[Tree]
  )(using Reporter): Parsed[Paren] =
    accept(s1, TokenKind.RParen) match
      case (s2, Some(_)) => (s2, Paren.Empty(span(start, s2.current.span)))
      case (s2, None)    =>
        val (s3, first) = item(s2)
        accept(s3, TokenKind.Comma) match
          case (s4, Some(_)) =>
            // `first` is already past the first comma; the tail uses
            // parseDelimited for recovery and is prepended.
            val (s5, tail) = parseDelimited(s4, TokenKind.RParen, ")")(item)
            val (s6, _) = expect(s5, TokenKind.RParen, "`)`")
            (s6, Paren.Many(first :: tail, span(start, s6.current.span)))
          case (s4, None) =>
            val (s5, _) = expect(s4, TokenKind.RParen, "`)`")
            (s5, Paren.One(first))

  private def parseArrowLhs(s0: ParserState)(using Reporter): Parsed[Tree] =
    if s0.current.kind == TokenKind.LParen then
      // Either: `(T1, T2, ...)` → TupleArrowLhs/UnitType
      //         `(T)` → just T (parenthesised)
      //         `(T) of OfArg` → ParenTypeApp
      val startSpan = s0.current.span
      val s1 = s0.advance
      val (s2, p) = parseParenBody(s1, startSpan)(parseTypeExpr)
      p match
        case Paren.Empty(sp)    => (s2, Trees.UnitType(sp))
        case Paren.Many(ts, sp) => (s2, Trees.TupleArrowLhs(ts, sp))
        case Paren.One(first)   =>
          accept(s2, TokenKind.KwOf) match
            case (s3, Some(_)) =>
              val (s4, arg) = parseOfArg(s3)
              (s4, Trees.ParenTypeApp(first, arg, span(startSpan, s4.current.span)))
            case (s3, None) => (s3, first)
    else parseTypeAtom(s0)

  private def parseTypeAtom(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.Bang =>
      val (s1, tok) = consume(s)
      (s1, Trees.NeverType(tok.span))
    case TokenKind.KwSelf =>
      val (s1, tok) = consume(s)
      val (s2, ofArg) = s1.optionally(TokenKind.KwOf)(parseOfArg)
      (s2, Trees.SelfType(ofArg, span(tok.span, s2.current.span)))
    case TokenKind.KwIs =>
      parseIsBound(s)
    case TokenKind.KwCap =>
      s.afterKeyword(TokenKind.KwCap, "`cap`")(parseCapTypeRest)
    case TokenKind.KwPart | TokenKind.UpperIdent =>
      // NamedAlias (`N is C`) or TypeRef (`T`, `T of A`). `Part` is the cap's
      // element-type pseudo-parameter (Rule 5.3): it behaves like a TypeRef,
      // and `Part is C` lifts it into a NamedAlias so callers can constrain
      // Part by a capability chain (`cap Sorted of (Part is Ord)`).
      val (s1, tok) = consume(s)
      val name = if tok.kind == TokenKind.KwPart then "Part" else tok.lexeme
      if s1.current.kind == TokenKind.KwIs then
        val (s2, caps) = parseCapBoundChain(s1.advance)
        (s2, Trees.NamedAlias(name, caps, span(tok.span, s2.current.span)))
      else
        val (s2, ofArg) = s1.optionally(TokenKind.KwOf)(parseOfArg)
        (s2, Trees.TypeRef(name, ofArg, span(tok.span, s2.current.span)))
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.PrimitiveType(tok.lexeme, tok.span))
    case _ => s.errorSync(DiagCode.P004, "a type expression")

  private def parseIsBound(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwIs, "`is`") { (s1, startTok) =>
      val (s2, caps) = parseCapBoundChain(s1)
      (s2, Trees.IsBound(caps, span(startTok.span, s2.current.span)))
    }

  private def parseCapBoundChain(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, caps) = parseSepBy1(s0, TokenKind.Plus)(parseCapBound)
    (s1, caps.toList)

  // `name(args)` with `name` already consumed and `current` at `(`.
  private def parseRefinementCall(s1: ParserState, nameTok: Token)(using Reporter): Parsed[Tree] =
    val (s2, args) = parseArgList(s1)
    (s2, Trees.RefinementCall(nameTok.lexeme, args, span(nameTok.span, s2.current.span)))

  private def parseCapBound(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.UpperIdent =>
      // `Name(args)` — invocation of a parameterised type alias or
      // cap-generating function whose name happens to be uppercase.
      // Same shape as `lower(args)` below; the typer disambiguates by
      // resolving the binding.
      if s.peek(1).kind == TokenKind.LParen then
        val (s1, nameTok) = consume(s)
        parseRefinementCall(s1, nameTok)
      else parseCapRef(s)
    case TokenKind.LowerIdent =>
      val (s1, nameTok) = consume(s)
      if s1.current.kind == TokenKind.LParen then parseRefinementCall(s1, nameTok)
      else (s1, Trees.PrimitiveType(nameTok.lexeme, nameTok.span))
    case _ =>
      val (s1, sp) = s.errorSkip(DiagCode.P005, "a capability bound")
      (s1, Trees.Error(Nil, sp))

  private def parseCapRef(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseNamedRef(s0, "capability name")(Trees.CapRef.apply)

  private def parseEffectBound(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseNamedRef(s0, "effect name")(Trees.EffectBound.apply)

  // `Name (of OfArg)?`; `what` names the expected identifier in diagnostics.
  private def parseNamedRef(s0: ParserState, what: String)(mk: (String, Option[Tree], Span) => Trees.NamedRef)(using
      Reporter
  ): Parsed[Tree] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, what)
    val (s2, ofArg) = s1.optionally(TokenKind.KwOf)(parseOfArg)
    (s2, mk(nameTok.lexeme, ofArg, span(nameTok.span, s2.current.span)))

  // OfArg ::= OfValueParam (decl-site) | LiteralExpr | TypeExpr. The
  // parser admits any TypeExpr or a parenthesised comma-list (for the
  // tuple-of-TypeExpr form `BiFunctor of (A, B)`); decl-site value
  // params and literal-expression OfArgs are disambiguated by the
  // typer.
  private def parseOfArg(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LParen then
      val startSpan = s.current.span
      val s1 = s.advance
      // `()` is the unit type as `of` argument (e.g. `M is Monad of ()`).
      // Items use parseDataOfItem so literal arguments like `Bounded of (N,
      // 0, 10)` and OfValueParam-shaped forms work in any of-arg
      // position, not just on data declarations.
      val (s2, p) = parseParenBody(s1, startSpan)(parseDataOfItem)
      p match
        case Paren.Empty(sp)    => (s2, Trees.UnitType(sp))
        case Paren.One(first)   => (s2, first)
        case Paren.Many(ts, sp) => (s2, Trees.TupleArrowLhs(ts, sp))
    else parseTypeExpr(s)

  // CapType ::= "cap" CapReturnSpec?
  // CapReturnSpec ::= "of" OfArg
  //                 | "extends" CapBound ("+" CapBound)*
  // The grammar makes the two arms exclusive (spec/syntax_grammar.ebnf
  // §CapReturnSpec): a `cap` is followed by either `of …` or
  // `extends …`, never both — so `Option[CapReturnSpec]` fits.
  // After `cap` in type position: `of OfArg`, `extends CapBound (+ CapBound)*`, or bare.
  private def parseCapTypeRest(s1: ParserState, startTok: Token)(using Reporter): Parsed[Tree] =
    val (s2, spec) = accept(s1, TokenKind.KwOf) match
      case (st, Some(_)) =>
        val (st1, a) = parseOfArg(st)
        (st1, Some(Trees.CapReturnSpec.OfArg(a)))
      case (st, None) =>
        accept(st, TokenKind.KwExtends) match
          case (st1, Some(_)) =>
            val (st2, caps) = parseCapBoundChain(st1)
            (st2, Some(Trees.CapReturnSpec.Extends(caps)))
          case (st1, None) => (st1, None)
    (s2, Trees.CapType(spec, span(startTok.span, s2.current.span)))

  // ---- Expressions ----

  def parseExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwLet => parseLetExpr(s)
    case _               => parsePipeExpr(s)

  private def parseLetExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwLet, "`let`") { (s1, startTok) =>
      val (s2, pat) = parsePattern(s1)
      val (s3, _) = expect(s2, TokenKind.Eq, "`=`")
      val (s4, init) = parsePipeExpr(s3)
      (s4, Trees.LetExpr(pat, init, span(startTok.span, init.span)))
    }

  /** operand (op operand)*, folded left. `opOf` picks out this level's
    * operators; `mk` builds the node.
    */
  private inline def parseLeftAssoc[Op](s0: ParserState)(inline operand: Rule[Tree])(
      inline opOf: TokenKind => Option[Op]
  )(inline mk: (Op, Tree, Tree, Span) => Tree): Parsed[Tree] =
    val (s1, lhs) = operand(s0)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      opOf(st.current.kind) match
        case Some(op) =>
          val (st1, rhs) = operand(st.advance)
          loop(st1, mk(op, acc, rhs, span(acc.span, rhs.span)))
        case None => (st, acc)
    loop(s1, lhs)

  private def parsePipeExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseOrExpr) {
      case TokenKind.PipeForward => Some(())
      case _                     => None
    }((_, lhs, rhs, sp) => Trees.Pipe(lhs, rhs, sp))

  private def parseOrExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseAndExpr) {
      case TokenKind.OrOr => Some(Trees.BinOperator.Or)
      case _              => None
    }(Trees.BinOp.apply)

  private def parseAndExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseCmpExpr) {
      case TokenKind.AndAnd => Some(Trees.BinOperator.And)
      case _                => None
    }(Trees.BinOp.apply)

  // Non-chaining: at most one comparison operator per expression.
  // `a < b < c` parses as `(a < b) < c`'s LHS rejected by the typer
  // (no `<` at boolean type), not as `a < b && b < c`. The grammar
  // marks CmpExpr non-associative; that's why this production has no
  // outer loop, unlike its peers `parseAddExpr` / `parseMulExpr`.
  private def parseCmpExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, lhs) = parseAddExpr(s0)
    val cmpOp = s1.current.kind match
      case TokenKind.EqEq => Some(Trees.BinOperator.Eq)
      case TokenKind.Neq  => Some(Trees.BinOperator.Neq)
      case TokenKind.Lt   => Some(Trees.BinOperator.Lt)
      case TokenKind.Le   => Some(Trees.BinOperator.Le)
      case TokenKind.Gt   => Some(Trees.BinOperator.Gt)
      case TokenKind.Ge   => Some(Trees.BinOperator.Ge)
      case _              => None
    cmpOp match
      case Some(op) =>
        val s2 = s1.advance
        val (s3, rhs) = parseAddExpr(s2)
        (s3, Trees.BinOp(op, lhs, rhs, span(lhs.span, rhs.span)))
      case None => (s1, lhs)

  private def parseAddExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseMulExpr) {
      case TokenKind.Plus  => Some(Trees.BinOperator.Add)
      case TokenKind.Minus => Some(Trees.BinOperator.Sub)
      case _               => None
    }(Trees.BinOp.apply)

  private def parseMulExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseUnaryExpr) {
      case TokenKind.Star    => Some(Trees.BinOperator.Mul)
      case TokenKind.Slash   => Some(Trees.BinOperator.Div)
      case TokenKind.Percent => Some(Trees.BinOperator.Mod)
      case _                 => None
    }(Trees.BinOp.apply)

  private object UnaryPrefix:
    def unapply(k: TokenKind): Option[Trees.UnaryOperator] = k match
      case TokenKind.Minus => Some(Trees.UnaryOperator.Neg)
      case TokenKind.Bang  => Some(Trees.UnaryOperator.Not)
      case _               => None

  private def parseUnaryExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case UnaryPrefix(op) =>
      val (s1, tok) = consume(s)
      val (s2, operand) = parseAppExpr(s1)
      (s2, Trees.UnaryOp(op, operand, span(tok.span, operand.span)))
    case _ => parseAppExpr(s)

  private def parseAppExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, recv) = parseAtomExpr(s0)
    @tailrec
    def loop(st: ParserState, receiver: Tree): Parsed[Tree] =
      st.current.kind match
        case TokenKind.LParen =>
          val (st1, args) = parseArgList(st)
          val next = receiver match
            case Trees.Ident(_, sp) =>
              Trees.Apply(receiver, args, span(sp, st1.current.span))
            case other =>
              Trees.Apply(other, args, span(other.span, st1.current.span))
          loop(st1, next)
        case TokenKind.Dot =>
          val st1 = st.advance
          val (st2, nameTok) = st1.current.kind match
            case TokenKind.LowerIdent | TokenKind.UpperIdent => consume(st1)
            case _                                           =>
              reportExpected(st1, DiagCode.P006, "method or field name after `.`", None)
              consume(st1)
          val (st3, args) =
            if st2.current.kind == TokenKind.LParen then
              val (st21, as) = parseArgList(st2)
              (st21, Some(as))
            else (st2, None)
          val selection = Trees.Selection(nameTok.lexeme, args)
          val next = receiver match
            // `T.member(...)`, or bare `T.Variant` / static-method-no-args
            // (`args` = None); the typer disambiguates.
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) =>
              Trees.StaticCall(qual, selection, span(qspan, st3.current.span))
            case _ =>
              Trees.MethodCall(receiver, selection, span(receiver.span, st3.current.span))
          loop(st3, next)
        case _ => (st, receiver)
    loop(s1, recv)

  private def parseArgList(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, (args, _)) = bracketed(s0, Bracket.Paren)(parseExpr)
    (s1, args)

  /** Drain trivia tokens between comma-list items inside brackets.
    * NEWLINEs are noise inside parens — always drain. DEDENTs are ours
    * to consume only when the matching block exited bracket-aware
    * (`owedDedents > 0`); a real outer DEDENT would otherwise be
    * silently swallowed on a recovery path. */
  @tailrec
  private def drainListSeparators(s: ParserState): ParserState =
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
  private enum Bracket(val open: TokenKind, val openLexeme: String, val close: TokenKind, val closeLexeme: String):
    case Paren extends Bracket(TokenKind.LParen, "(", TokenKind.RParen, ")")
    case Square extends Bracket(TokenKind.LBracket, "[", TokenKind.RBracket, "]")
    case Brace extends Bracket(TokenKind.LBrace, "{", TokenKind.RBrace, "}")
    case Angle extends Bracket(TokenKind.Lt, "<", TokenKind.Gt, ">")
    val openWhat: String = s"`$openLexeme`"
    val closeWhat: String = s"`$closeLexeme`"

  /** open item ("," item)* ","? close. Returns the items and the span from
    * open to close. Partial items are wrapped in `Trees.Error`.
    */
  private inline def bracketed(s0: ParserState, b: Bracket)(
      inline item: Rule[Tree]
  )(using Reporter): Parsed[(List[Tree], Span)] =
    inBrackets(s0, b)(parseDelimited(_, b.close, b.closeLexeme)(item))

  /** `bracketed` for non-`Tree` items; partial items are kept verbatim. */
  private inline def bracketedTyped[A](s0: ParserState, b: Bracket)(
      inline item: Rule[A]
  )(using Reporter): Parsed[(List[A], Span)] =
    inBrackets(s0, b)(parseDelimitedTyped(_, b.close, b.closeLexeme)(item))

  private inline def inBrackets[A](s0: ParserState, b: Bracket)(
      inline items: ParserState => Parsed[List[A]]
  )(using Reporter): Parsed[(List[A], Span)] =
    val (s1, openTok) = expect(s0, b.open, b.openWhat)
    val (s2, xs) = items(s1)
    val (s3, closeTok) = expect(s2, b.close, b.closeWhat)
    (s3, (xs, span(openTok.span, closeTok.span)))

  private inline def parseDelimited(
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
  private inline def parseDelimitedTyped[A](
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String
  )(inline parseItem: Rule[A])(using Reporter): Parsed[List[A]] =
    parseDelimitedLoop[A](s0, closeKind, closeLexeme) { (st, _) =>
      val (st1, item) = parseItem(st)
      (st1, item, item)
    }

  /** Shared loop body. `parseItem` returns `(state, ok, recovered)`:
    * the value to store on a clean parse and the value to store after
    * a P012 sync. The `itemStart` span lets `Tree` callers wrap the
    * partial item in `Trees.Error`.
    *
    * Marked `inline` (with `inline parseItem`) so each call site gets
    * its own monomorphic copy of the loop. The previous shared-body
    * version had a megamorphic `Function3` apply — many call sites
    * (parseArgList, parseFnParamList, parseFieldList, parseStructLit,
    * parseUseDecl selectors, parseLambdaExpr params, parseListExpr,
    * parseInnerPatternList, parseOptionalDataOfClause,
    * parseOptionalTypeParamsHint, …) made the JIT fall back to vtable
    * dispatch, blocking escape analysis on the `(state, ok, recovered)`
    * tuple and forcing per-call lambda allocation. Inlining specialises
    * the loop per call site, the tuple is scalar-replaced, and the
    * lambda goes away. */
  private inline def parseDelimitedLoop[A](
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String
  )(using reporter: Reporter)(inline parseItem: (ParserState, Span) => (ParserState, A, A)): Parsed[List[A]] =
    val anchors = Set(TokenKind.Comma, closeKind)

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
          _.current.kind == closeKind // trailing comma
        )
        (st1, items.toList)
    }

  private def parseAtomExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.IntLit =>
      val (s1, tok) = consume(s)
      (s1, Trees.IntLit(BigInt(tok.lexeme), tok.span))
    case TokenKind.FloatLit =>
      val (s1, tok) = consume(s)
      (s1, Trees.FloatLit(BigDecimal(tok.lexeme), tok.span))
    case TokenKind.StringLit =>
      val (s1, tok) = consume(s)
      (s1, Trees.StringLit(tok.lexeme, tok.span))
    case TokenKind.CharLit =>
      val (s1, tok) = consume(s)
      (s1, Trees.CharLit(if tok.lexeme.nonEmpty then tok.lexeme.charAt(0) else ' ', tok.span))
    case TokenKind.KwTrue =>
      val (s1, tok) = consume(s)
      (s1, Trees.BoolLit(true, tok.span))
    case TokenKind.KwFalse =>
      val (s1, tok) = consume(s)
      (s1, Trees.BoolLit(false, tok.span))
    case TokenKind.KwUnreachable =>
      val (s1, tok) = consume(s)
      (s1, Trees.Unreachable(tok.span))
    case TokenKind.KwResume =>
      val (s1, tok) = consume(s)
      val (s2, arg) = accept(s1, TokenKind.LParen) match
        case (st, Some(_)) =>
          val (st1, v) =
            if st.current.kind == TokenKind.RParen then (st, None)
            else
              val (sx, e) = parseExpr(st); (sx, Some(e))
          val (st2, _) = expect(st1, TokenKind.RParen, "`)`")
          (st2, v)
        case (st, None) => (st, None)
      (s2, Trees.Resume(arg, span(tok.span, s2.current.span)))
    case TokenKind.KwSelf =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident("Self", tok.span))
    case TokenKind.KwSelfLower =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident("self", tok.span))
    case TokenKind.KwIf       => parseIfExpr(s)
    case TokenKind.KwLet      => parseLetExpr(s)
    case TokenKind.KwMatch    => parseMatchExpr(s)
    case TokenKind.KwHandle   => parseHandleExpr(s)
    case TokenKind.KwDo       => parseDoExpr(s)
    case TokenKind.KwForall   => parseForallExpr(s)
    case TokenKind.LBracket   => parseListExpr(s)
    case TokenKind.LParen     => parseParenOrLambdaOrTuple(s)
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident(tok.lexeme, tok.span))
    case TokenKind.UpperIdent =>
      val (s1, tok) = consume(s)
      // `T { field: value, ... }` — struct literal for single-variant
      // data types (`data Point(x: N, y: N)`-style).
      if s1.current.kind == TokenKind.LBrace then parseStructLit(s1, tok)
      else (s1, Trees.Ident(tok.lexeme, tok.span))
    case _ =>
      val (s1, sp) = s.errorSkip(DiagCode.P007, "an expression")
      (s1, Trees.Error(Nil, sp))

  private def parseIfExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwIf, "`if`") { (s1, startTok) =>
      val (s2, cond) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after if condition")
      val (s4, thenBranch) = parseInlineOrBlockExpr(s3)
      val s5 = skipNewlines(s4)
      val (s6, _) = expect(s5, TokenKind.KwElse, "`else`")
      // `else if cond: ...` chains the next conditional as the else branch
      // — a right-associative if/else-if/else cascade. `else: ...` is the
      // base case.
      val (s7, elseBranch) =
        if s6.current.kind == TokenKind.KwIf then parseIfExpr(s6)
        else
          val (s6a, _) = expect(s6, TokenKind.Colon, "`:` after `else`")
          parseInlineOrBlockExpr(s6a)
      (s7, Trees.IfExpr(cond, thenBranch, elseBranch, span(startTok.span, elseBranch.span)))
    }

  // MatchExpr ::= "match" Expr ":" INDENT MatchArm+ DEDENT
  // MatchArm  ::= OrPattern ("if" Expr)? "=>" InlineOrBlockExpr
  private def parseMatchExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwMatch, "`match`") { (s1, startTok) =>
      val (s2, scrutinee) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after match scrutinee")
      val (s4, arms) = parseMatchArms(s3)
      (s4, Trees.MatchExpr(scrutinee, arms, span(startTok.span, s4.current.span)))
    }

  private def parseMatchArms(s0: ParserState)(using Reporter): Parsed[List[Trees.MatchArm]] =
    val (s1, arms) = parseIndentedBody(s0, List.empty[Trees.MatchArm]) { (st, acc) =>
      val (st1, arm) = parseMatchArm(st)
      (st1, arm :: acc)
    }
    (s1, arms.reverse)

  private def parseMatchArm(s0: ParserState)(using Reporter): Parsed[Trees.MatchArm] =
    val (s1, pat) = parseOrPattern(s0)
    val (s2, guard) = s1.optionally(TokenKind.KwIf)(parseExpr)
    val (s3, _) = expect(s2, TokenKind.FatArrow, "`=>` in match arm")
    val (s4, body) = parseInlineOrBlockExpr(s3)
    (s4, Trees.MatchArm(pat, guard, body, span(pat.span, body.span)))

  private def parseOrPattern(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, head) = parsePattern(s0)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.Pipe) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parsePattern(st1)
          loop(st2, Trees.OrPat(acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, head)

  // DoExpr ::= "do" ":" INDENT DoStmt+ DEDENT
  // DoStmt ::= Pattern "<-" Expr   (DoBind)
  //          | Expr
  private def parseDoExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwDo, "`do`") { (s1, startTok) =>
      val (s2, _) = expect(s1, TokenKind.Colon, "`:` after `do`")
      val (s3, stmts) = parseDoStmts(s2)
      (s3, Trees.DoExpr(stmts, span(startTok.span, s3.current.span)))
    }

  private def parseDoStmts(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    parseStatementList(s0, DiagCode.P018, "do statement")(parseDoStmt)

  // `pat <- expr` bind, or a plain expression.
  private def parseDoStmt(s: ParserState)(using Reporter): Parsed[Tree] =
    if isDoBindAhead(s) then
      val (s1, pat) = parsePattern(s)
      val (s2, _) = expect(s1, TokenKind.BackArrow, "`<-`")
      val (s3, rhs) = parseExpr(s2)
      (s3, Trees.DoBind(pat, rhs, span(pat.span, rhs.span)))
    else parseExpr(s)

  // True iff the current statement contains a top-level `<-` before
  // its terminating NEWLINE/DEDENT/EOF — i.e. it's a `pat <- expr`
  // bind rather than a plain expression. Tracks paren/bracket/brace
  // depth so a `<-` inside a nested expression is not mistaken for
  // the bind arrow.
  /** Opening / closing bracket kinds, for depth-tracking lookahead. */
  private object Opener:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace => true
      case _                                                        => false

  /** Token kinds that begin a literal (pattern or compile-time `of` argument). */
  private object LiteralStart:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.IntLit | TokenKind.FloatLit | TokenKind.StringLit | TokenKind.CharLit | TokenKind.KwTrue |
          TokenKind.KwFalse => true
      case _ => false

  private object Closer:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace => true
      case _                                                        => false

  private def isDoBindAhead(s: ParserState): Boolean =
    @tailrec
    def loop(i: Int, depth: Int): Boolean =
      s.peek(i).kind match
        case TokenKind.BackArrow if depth == 0                    => true
        case TokenKind.Newline | TokenKind.Dedent | TokenKind.Eof => false
        case Opener()                                             =>
          loop(i + 1, depth + 1)
        case Closer() =>
          if depth == 0 then false else loop(i + 1, depth - 1)
        case _ => loop(i + 1, depth)
    loop(0, 0)

  // ForallExpr ::= "forall" "(" FnParam ("," FnParam)* ")"
  //                ("suchThat" Expr)? "->" InlineOrBlockExpr
  private def parseForallExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwForall, "`forall`") { (s1, startTok) =>
      val (s2, binders) = parseFnParamList(s1)
      val (s3, suchThat) = s2.optionally(TokenKind.KwSuchThat)(parseExpr)
      val (s4, _) = expect(s3, TokenKind.Arrow, "`->` after forall binders")
      val (s5, body) = parseInlineOrBlockExpr(s4)
      (s5, Trees.ForallExpr(binders, suchThat, body, span(startTok.span, body.span)))
    }

  // HandleExpr ::= "handle" Expr ":" INDENT HandlerArm+ DEDENT
  // HandlerArm ::= EffectArm | ReturnArm
  //   EffectArm ::= UPPER_IDENT "." LOWER_IDENT ("(" PatternList ")")? "=>" Body
  //   ReturnArm ::= "return" "(" Pattern ")" "=>" Body
  // The optional return arm is partitioned out at parse time; multiple
  // return arms are admitted by the grammar but only the last one
  // survives (well-formed programs have at most one).
  private def parseHandleExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwHandle, "`handle`") { (s1, startTok) =>
      val (s2, subject) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after handle subject")
      val (s4, arms, returnArm) = parseHandlerArms(s3)
      (s4, Trees.HandleExpr(subject, arms, returnArm, span(startTok.span, s4.current.span)))
    }

  // Internal accumulator for handler-arms parsing: we collect normal
  // arms and (possibly) a single return arm in lockstep so the body
  // loop stays a single fold over `parseIndentedBody`.
  final private case class HandlerAcc(
      arms: List[Trees.HandlerArm],
      returnArm: Option[Trees.ReturnArm]
  )

  private def parseHandlerArms(
      s0: ParserState
  )(using Reporter): (ParserState, List[Trees.HandlerArm], Option[Trees.ReturnArm]) =
    val (s1, acc) = parseIndentedBody(s0, HandlerAcc(Nil, None)) { (st, acc) =>
      val (st1, armOpt) = parseHandlerArm(st)
      val acc1 = armOpt match
        case ra: Trees.ReturnArm  => acc.copy(returnArm = Some(ra))
        case ha: Trees.HandlerArm => acc.copy(arms = ha :: acc.arms)
        case _: Trees.Error       => acc // recovery; diagnostic already emitted
        case _                    => acc
      (st1, acc1)
    }
    (s1, acc.arms.reverse, acc.returnArm)

  private def parseHandlerArm(s: ParserState)(using Reporter): Parsed[Tree] =
    val startSpan = s.current.span
    s.current.kind match
      case TokenKind.KwReturn =>
        val s1 = s.advance
        val (s2, _) = expect(s1, TokenKind.LParen, "`(`")
        val (s3, pat) = parsePattern(s2)
        val (s4, _) = expect(s3, TokenKind.RParen, "`)`")
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after return arm pattern")
        val (s6, body) = parseInlineOrBlockExpr(s5)
        (s6, Trees.ReturnArm(pat, body, span(startSpan, body.span)))
      case TokenKind.UpperIdent =>
        val (s1, effectTok) = consume(s)
        val (s2, _) = expect(s1, TokenKind.Dot, "`.`")
        val (s3, opTok) = expect(s2, TokenKind.LowerIdent, "effect op name")
        val (s4, params) = s3.whenAtOrEmpty(TokenKind.LParen)(parseInnerPatternList)
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after effect op")
        val (s6, body) = parseInlineOrBlockExpr(s5)
        (s6, Trees.HandlerArm(effectTok.lexeme, opTok.lexeme, params, body, span(startSpan, body.span)))
      case _ =>
        s.errorSync(
          DiagCode.P019,
          "a handler arm",
          Some("each arm is `Effect.op(...) => body` or `return(p) => body`")
        )

  /** A branch body that's either a same-line expression or an INDENTed block. */
  private def parseInlineOrBlockExpr(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.Indent then parseBlock(s)
    else parseExpr(s)

  private def parseListExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, (elements, sp)) = bracketed(s0, Bracket.Square)(parseExpr)
    (s1, Trees.ListExpr(elements, sp))

  // StructLit ::= UPPER_IDENT "{" Field ("," Field)* ","? "}"
  //   Field   ::= LOWER_IDENT ":" Expr
  // The leading `UPPER_IDENT` has already been consumed; `typeNameTok`
  // carries it.
  private def parseStructLit(
      s0: ParserState,
      typeNameTok: Token
  )(using Reporter): Parsed[Tree] =
    val (s1, (fields, sp)) = bracketedTyped(s0, Bracket.Brace) { st =>
      val (st1, nameTok) = expect(st, TokenKind.LowerIdent, "field name")
      val (st2, _) = expect(st1, TokenKind.Colon, "`:` after field name")
      val (st3, value) = parseExpr(st2)
      (st3, (nameTok.lexeme, value))
    }
    (s1, Trees.StructLit(typeNameTok.lexeme, fields, span(typeNameTok.span, sp)))

  /** Parse `( ... )` — could be:
    *   - `()`        unit literal
    *   - `(expr)`    grouped expression
    *   - `(expr, ...)`   tuple expression
    *   - `(name) -> ...`  one-arg lambda
    *   - `(name: T) -> ...`  one-arg lambda with type
    *   - `(name, ...) -> ...`  multi-arg lambda
    *
    * Strategy: lookahead for the matching `)`; if `->` follows, treat as
    * lambda. Otherwise parse as expression(s).
    */
  private def parseParenOrLambdaOrTuple(s: ParserState)(using Reporter): Parsed[Tree] =
    if lookaheadIsLambda(s) then parseLambdaExpr(s)
    else parseParenExpr(s)

  /** Find the matching `)` for the `(` at `current` and check whether the
    * next non-NEWLINE token after it is `->`. */
  private def lookaheadIsLambda(s: ParserState): Boolean =
    require(s.current.kind == TokenKind.LParen)
    @tailrec
    def loop(i: Int, depth: Int): Int =
      s.peek(i).kind match
        case Opener() =>
          loop(i + 1, depth + 1)
        case Closer() =>
          val d1 = depth - 1
          if d1 == 0 then i
          else loop(i + 1, d1)
        case TokenKind.Eof => -1
        case _             => loop(i + 1, depth)
    val found = loop(0, 0)
    if found < 0 then false
    else
      // Skip a NEWLINE between `)` and `->` (multi-line lambda).
      val afterIdx = found + 1
      val nextKind =
        if s.peek(afterIdx).kind == TokenKind.Newline then s.peek(afterIdx + 1).kind
        else s.peek(afterIdx).kind
      nextKind == TokenKind.Arrow

  private def parseLambdaExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, (params, sp)) = bracketedTyped(s0, Bracket.Paren)(parseLambdaParam)
    val (s2, _) = expect(s1, TokenKind.Arrow, "`->`")
    val (s3, body) = parseInlineOrBlockExpr(s2)
    (s3, Trees.LambdaExpr(params, body, span(sp, body.span)))

  private def parseLambdaParam(s0: ParserState)(using Reporter): Parsed[Trees.LambdaParam] =
    val (s1, name, startSpan) = s0.current.kind match
      case TokenKind.LowerIdent =>
        val (st, tok) = consume(s0); (st, tok.lexeme, tok.span)
      case TokenKind.Underscore =>
        val (st, tok) = consume(s0); (st, "_", tok.span)
      case _ =>
        reportExpected(s0, DiagCode.P008, "lambda parameter name", None)
        val (st, tok) = consume(s0); (st, "<error>", tok.span)
    val (s2, typeAnn) = s1.optionally(TokenKind.Colon)(parseTypeExpr)
    val (s3, default) = s2.optionally(TokenKind.Eq)(parseExpr)
    (s3, Trees.LambdaParam(name, typeAnn, default, span(startSpan, s3.current.span)))

  private def parseParenExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.LParen, "`(`")
    val (s2, p) = parseParenBody(s1, startTok.span)(parseExpr)
    p match
      case Paren.Empty(sp)    => (s2, Trees.UnitLit(sp))
      case Paren.One(e)       => (s2, e)
      case Paren.Many(es, sp) => (s2, Trees.TupleExpr(es, sp))

  // ---- Patterns ----

  private def parsePattern(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.Underscore =>
      val (s1, tok) = consume(s)
      (s1, Trees.WildcardPat(tok.span))
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.BinderPat(tok.lexeme, tok.span))
    case TokenKind.UpperIdent =>
      // Forms:
      //   `T.Variant(p1, ...)`  qualified data-variant pattern
      //   `T.Variant`           qualified unit-variant pattern
      //   `T(p1, ...)`          single-ctor sugar pattern (typical for
      //                         `data Point(x: N, y: N)`-style decls)
      //   `T`                   no-qualifier variant pattern (Rule 3.4.a)
      val (s1, nameTok) = consume(s)
      accept(s1, TokenKind.Dot) match
        case (s2, Some(_)) =>
          val (s3, variantTok) = expect(s2, TokenKind.UpperIdent, "variant name")
          if s3.current.kind == TokenKind.LParen then
            val (s4, fields) = parseInnerPatternList(s3)
            (
              s4,
              Trees.DataVariantPat(
                Some(nameTok.lexeme),
                variantTok.lexeme,
                Some(fields),
                span(nameTok.span, s4.current.span)
              )
            )
          else
            (
              s3,
              Trees.DataVariantPat(
                Some(nameTok.lexeme),
                variantTok.lexeme,
                None,
                span(nameTok.span, variantTok.span)
              )
            )
        case (s2, None) =>
          if s2.current.kind == TokenKind.LParen then
            val (s3, fields) = parseInnerPatternList(s2)
            (
              s3,
              Trees.DataVariantPat(None, nameTok.lexeme, Some(fields), span(nameTok.span, s3.current.span))
            )
          else
            (s2, Trees.DataVariantPat(None, nameTok.lexeme, None, nameTok.span))
    case TokenKind.LParen =>
      // Tuple pattern.
      val (s1, (elements, sp)) = bracketed(s, Bracket.Paren)(parsePattern)
      (s1, Trees.TuplePat(elements, sp))
    case LiteralStart() =>
      val sp = s.current.span
      val (s1, lit) = parseAtomExpr(s)
      (s1, Trees.LiteralPat(lit, sp))
    case _ =>
      val (s1, sp) = s.errorSkip(DiagCode.P010, "a pattern")
      (s1, Trees.WildcardPat(sp))

  private def parseInnerPatternList(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, (pats, _)) = bracketed(s0, Bracket.Paren)(parsePattern)
    (s1, pats)

  // ---- DataDecl ----

  // DataDecl ::= "data" UPPER_IDENT OfClause? (DataBody | CtorSugar)?
  //   OfClause   ::= "of" (TypeExpr | "(" TypeExpr ("," TypeExpr)* ")")
  //   DataBody   ::= ":" INDENT DataVariant+ DEDENT
  //   CtorSugar  ::= "(" FieldList ")"   — single-variant shorthand
  //
  //   DataVariant ::= UPPER_IDENT ("(" FieldList ")")?
  //   FieldList   ::= FieldDecl ("," FieldDecl)* ","?
  //   FieldDecl   ::= LOWER_IDENT ":" TypeExpr ("=" Expr)?
  private def parseDataDecl(s0: ParserState)(using reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwData, "`data`")
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, "data type name")
    val (s3, ofParams) = parseOptionalDataOfClause(s2)
    if s3.current.kind == TokenKind.LParen then
      // Single-ctor sugar — synthesise one variant with the type's name.
      val (s4, fields) = parseFieldList(s3)
      val variant = Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s4.current.span))
      (s4, Trees.DataDecl(nameTok.lexeme, ofParams, List(variant), Nil, span(startTok.span, s4.current.span)))
    else
      accept(s3, TokenKind.Colon) match
        case (s4, Some(_)) =>
          val (s5, variants, props) = parseDataBody(s4)
          (s5, Trees.DataDecl(nameTok.lexeme, ofParams, variants, props, span(startTok.span, s5.current.span)))
        case (s4, None) =>
          reporter.error(
            DiagCode.P013,
            s4.current.span,
            s"`data ${nameTok.lexeme}` needs either a `(field: T, ...)` ctor sugar or a `:` block of variants",
            Some("add `: ...` for variants or `(field: T, ...)` for a single ctor")
          )
          (s4, Trees.DataDecl(nameTok.lexeme, ofParams, Nil, Nil, span(startTok.span, s4.current.span)))

  private def parseOptionalDataOfClause(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    accept(s0, TokenKind.KwOf) match
      case (s1, Some(_)) =>
        if s1.current.kind == TokenKind.LParen then
          val (s2, (params, _)) = bracketed(s1, Bracket.Paren)(parseDataOfItem)
          (s2, params)
        else
          // Single-item form `of T` — just a TypeExpr; no need to
          // disambiguate OfValueParam (which only appears inside the
          // paren list).
          val (s2, ty) = parseTypeExpr(s1)
          (s2, List(ty))
      case (s1, None) => (s1, Nil)

  // Inside an `of (...)` clause, each item is one of:
  //   - OfValueParam: `name : TypeExpr (= default)?` — lowered to FnParam.
  //   - LiteralExpr: an Int / Float / String / Char / Bool literal
  //     used as a compile-time argument (e.g. `Bounded of (N, 0, 10)`).
  //   - TypeExpr: any of the type forms (incl. NamedAlias `N is C`).
  private def parseDataOfItem(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LowerIdent && s.peek(1).kind == TokenKind.Colon then parseFnParam(s)
    else
      s.current.kind match
        case LiteralStart() =>
          parseAtomExpr(s)
        case _ =>
          parseTypeExpr(s)

  // Internal accumulator for data-body parsing: variants and props are
  // partitioned at dispatch time so consumers never need to re-classify.
  final private case class DataBodyAcc(
      variants: List[Trees.DataVariant],
      props: List[Trees.PropDecl]
  )

  // Data bodies admit `DataVariant`s and `PropDecl`s. The parser
  // partitions them into separate lists at dispatch time so consumers
  // never need to re-classify.
  private def parseDataBody(
      s0: ParserState
  )(using Reporter): (ParserState, List[Trees.DataVariant], List[Trees.PropDecl]) =
    val (s1, acc) = parseIndentedBody(s0, DataBodyAcc(Nil, Nil)) { (state, acc) =>
      state.current.kind match
        case TokenKind.UpperIdent =>
          val (st1, v) = parseDataVariant(state)
          (st1, acc.copy(variants = v :: acc.variants))
        case TokenKind.KwProp =>
          val (st1, p) = parsePropDecl(state)
          (st1, acc.copy(props = p :: acc.props))
        case _ =>
          reportExpected(
            state,
            DiagCode.P013,
            "a variant name or `prop`",
            Some("data bodies hold `UpperIdent` variants and `prop` declarations")
          )
          (syncToAnchors(state), acc)
    }
    (s1, acc.variants.reverse, acc.props.reverse)

  private def parseDataVariant(s0: ParserState)(using Reporter): Parsed[Trees.DataVariant] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, "variant name")
    val (s2, fields) = s1.whenAtOrEmpty(TokenKind.LParen)(parseFieldList)
    (s2, Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s2.current.span)))

  // ---- CapDecl ----

  // CapDecl   ::= "cap" UPPER_IDENT ValueParams? OfClause? ExtendsClause?
  //               (":" INDENT CapBody DEDENT)?
  //   ValueParams  ::= "(" FnParam ("," FnParam)* ","? ")"
  //   OfClause     ::= "of" (TypeExpr | "(" OfItem ("," OfItem)* ")")
  //   ExtendsClause::= "extends" CapBound ("+" CapBound)*
  //   CapBody      ::= CapMember+
  //   CapMember    ::= InstanceMethod | StaticMethod | PropDecl
  //
  // ValueParams is the `cap Between(min: N, max: N) of N: ...` sugar
  // form, which desugars to `fn between(min, max) -> cap of N: ...`.
  // Per v0.3: empty bodies omit the `:` entirely (`cap Locked`).
  // `UPPER_IDENT ValueParams?` — the head of `cap` and `type` declarations.
  private def parseNameAndValueParams(s1: ParserState, what: String)(using
      Reporter
  ): Parsed[(Token, List[Trees.FnParam])] =
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, what)
    val (s3, valueParams) = s2.whenAtOrEmpty(TokenKind.LParen)(parseFnParamList)
    (s3, (nameTok, valueParams))

  // After `cap` at top level: `Name ValueParams? OfClause? ExtendsClause? (: CapBody)?`.
  private def parseCapDeclRest(s1: ParserState, startTok: Token)(using Reporter): Parsed[Tree] =
    val (s3, (nameTok, valueParams)) = parseNameAndValueParams(s1, "capability name")
    val (s4, ofParams) = parseOptionalDataOfClause(s3)
    val (s5, extendsList) = s4.optionalOrEmpty(TokenKind.KwExtends)(parseCapBoundChain)
    val (s6, body) = s5.optionalOrEmpty(TokenKind.Colon)(parseCapBody)
    (
      s6,
      Trees.CapDecl(nameTok.lexeme, valueParams, ofParams, extendsList, body, span(startTok.span, s6.current.span))
    )

  private def parseCapBody(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    s0.indentedList { state =>
      state.current.kind match
        case MethodMember(p) =>
          val (st1, m) = p(state); (st1, Some(m))
        case _ =>
          reportExpected(
            state,
            DiagCode.P014,
            "`fn`, `Self.fn`, or `prop`",
            Some("cap members are instance methods (`fn`), static methods (`Self.fn`), or `prop` declarations")
          )
          (syncToAnchors(state), None)
    }

  // TypeParamsHint ::= "<" UPPER_IDENT ("," UPPER_IDENT)* ","? ">"
  // Per Rule 4.1, Fixed type params are normally introduced at first
  // use; the explicit hint is only required when a method's type
  // parameters can't be inferred from its parameters or return type
  // (e.g. a fold whose result type R appears only in callbacks).
  private def parseOptionalTypeParamsHint(s0: ParserState)(using Reporter): Parsed[List[String]] =
    if s0.current.kind != TokenKind.Lt then (s0, Nil)
    else
      val (s1, (params, _)) = bracketedTyped(s0, Bracket.Angle) { st =>
        val (st1, tok) = expect(st, TokenKind.UpperIdent, "type-param name")
        (st1, tok.lexeme)
      }
      (s1, params)

  private def parsePropDecl(s0: ParserState)(using Reporter): Parsed[Trees.PropDecl] =
    s0.afterKeyword(TokenKind.KwProp, "`prop`") { (s1, startTok) =>
      val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, "prop name")
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after prop name")
      val (s4, body) = parseExpr(s3)
      (s4, Trees.PropDecl(nameTok.lexeme, body, span(startTok.span, body.span)))
    }

  // ---- TypeAlias ----

  // TypeAlias ::= "type" UPPER_IDENT ValueParams? "=" CapBoundChain
  //   ValueParams ::= "(" FnParam ("," FnParam)* ","? ")"
  // The RHS chain is wrapped in `IsBound(caps)` when there are multiple
  // caps; a single-cap RHS is the cap node itself.
  private def parseTypeAlias(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwType, "`type`") { (s1, startTok) =>
      val (s3, (nameTok, valueParams)) = parseNameAndValueParams(s1, "type alias name")
      val (s4, _) = expect(s3, TokenKind.Eq, "`=`")
      val (s5, caps) = parseCapBoundChain(s4)
      val rhs =
        if caps.size == 1 then caps.head
        else Trees.IsBound(caps, span(caps.head.span, caps.last.span))
      (s5, Trees.TypeAlias(nameTok.lexeme, valueParams, rhs, span(startTok.span, s5.current.span)))
    }

  // ---- SatisfiesDecl ----

  // SatisfiesDecl ::= TypeName "satisfies" CapBound (":" INDENT SatisfiesItem+ DEDENT)?
  //   TypeName    ::= UPPER_IDENT | LOWER_IDENT          (primitives)
  //   SatisfiesItem ::= ConstructorMapping
  //                  | ImpossibleMapping
  //                  | InstanceMethod | StaticMethod | PropDecl
  //   ConstructorMapping ::= (UPPER_IDENT | "Self") "as" LOWER_IDENT   (Self → SelfMapping)
  //   ImpossibleMapping  ::= "impossible" "as" LOWER_IDENT
  private def parseSatisfiesDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, typeName) = s0.current.kind match
      case TokenKind.UpperIdent | TokenKind.LowerIdent =>
        val (st, tok) = consume(s0); (st, tok.lexeme)
      case _ =>
        reportExpected(
          s0,
          DiagCode.P015,
          "a type name to satisfy a capability",
          Some("`<TypeName> satisfies <CapName>: ...`")
        )
        val (st, tok) = consume(s0); (st, tok.lexeme)
    val (s2, _) = expect(s1, TokenKind.KwSatisfies, "`satisfies`")
    val (s3, cap) = parseCapBound(s2)
    val (s4, items) = s3.optionalOrEmpty(TokenKind.Colon)(parseSatisfiesBody)
    (s4, Trees.SatisfiesDecl(typeName, cap, items, span(s0.current.span, s4.current.span)))

  private def parseSatisfiesBody(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, items) = parseIndentedBody(s0, List.empty[Tree]) { (state, acc) =>
      val (state1, item) = parseSatisfiesItem(state)
      (state1, item :: acc)
    }
    (s1, items.reverse)

  // Head of a `<head> as ctor` satisfies item, paired with the tree it builds
  // from (capCtor, span). `Self` is a head only when followed by `as`;
  // otherwise it starts a `Self.fn` static method.
  private object AsMappingHead:
    def unapply(s: ParserState): Option[(String, Span) => Tree] = s.current.kind match
      case TokenKind.KwImpossible => Some(Trees.ImpossibleMapping(_, _))
      // `Self as ctor` — identity-shape mapping for primitives that are
      // themselves the cap's value (`u64 satisfies Optional of Self: Self as some`).
      case TokenKind.KwSelf if s.peek(1).kind == TokenKind.KwAs => Some(Trees.SelfMapping(_, _))
      case TokenKind.UpperIdent => Some(Trees.ConstructorMapping(s.current.lexeme, _, _))
      case _                    => None

  private def parseSatisfiesItem(s: ParserState)(using Reporter): Parsed[Tree] = s match
    case AsMappingHead(mk) =>
      val (s1, head) = consume(s)
      val (s2, _) = expect(s1, TokenKind.KwAs, "`as`")
      val (s3, ctorTok) = expect(s2, TokenKind.LowerIdent, "cap constructor name")
      (s3, mk(ctorTok.lexeme, span(head.span, ctorTok.span)))
    case _ => parseSatisfiesMember(s)

  /** Members shared by cap bodies and satisfies blocks: `fn`, `Self.fn`, `prop`. */
  private object MethodMember:
    def unapply(k: TokenKind)(using Reporter): Option[Rule[Tree]] = k match
      case TokenKind.KwFn   => Some(parseFnLike(_, FnKind.Instance))
      case TokenKind.KwSelf => Some(parseFnLike(_, FnKind.Static))
      case TokenKind.KwProp => Some(parsePropDecl)
      case _                => None

  private def parseSatisfiesMember(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case MethodMember(p) => p(s)
    case _               =>
      s.errorSync(
        DiagCode.P016,
        "a satisfies item",
        Some("items are `Variant as ctor`, `impossible as ctor`, `fn ...`, `Self.fn ...`, or `prop ...`")
      )

  // ---- EffectDecl ----

  // EffectDecl ::= "linear"? "effect" UPPER_IDENT OfClause? (":" INDENT EffectMember+ DEDENT)?
  //   EffectMember ::= "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause?
  // (Effect ops never have bodies; their semantics are supplied by handlers.)
  private def parseEffectDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, linOpt) = accept(s0, TokenKind.KwLinear)
    val isLinear = linOpt.isDefined
    val (s2, _) = expect(s1, TokenKind.KwEffect, "`effect`")
    val (s3, nameTok) = expect(s2, TokenKind.UpperIdent, "effect name")
    val (s4, ofParams) = parseOptionalDataOfClause(s3)
    val (s5, members) = s4.optionalOrEmpty(TokenKind.Colon)(parseEffectMembers)
    (s5, Trees.EffectDecl(isLinear, nameTok.lexeme, ofParams, members, span(s0.current.span, s5.current.span)))

  private def parseEffectMembers(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    s0.indentedList { state =>
      if state.current.kind == TokenKind.KwFn then
        val (st1, m) = parseFnLike(state, FnKind.Instance); (st1, Some(m))
      else
        reportExpected(
          state,
          DiagCode.P017,
          "an effect op (`fn name(...) -> T`)",
          Some("each effect op is declared like an instance method, without a body")
        )
        (syncToAnchors(state), None)
    }

  // ---- Stub productions still pending ----

  private def parseModDecl(s: ParserState)(using Reporter): Parsed[Tree] =
    unsupported(s, "`mod` declaration")

  // ---- Public entry-point convenience overloads ----

  /** Functional entry point: parse a whole source file and return its
    * tree, diagnostics, and trivia together. The parser does not
    * require a caller-supplied Reporter — diagnostics flow through the
    * returned [[ParseResult]].
    *
    * Internally we still allocate a private Reporter to drive the
    * Scanner/Parser plumbing; the diagnostics are extracted and the
    * Reporter is discarded. The hot path stays identical to the
    * pre-M1 implementation; the API surface itself is functional.
    *
    * See `docs/plans/phase-2.1-incremental-parser.md` §2.
    */
  def parse(source: SourceFile): ParseResult =
    val privateReporter = new Reporter(source)
    val scanner = new Scanner(source, privateReporter)
    val parser = new Parser(scanner, privateReporter)
    val tree = parser.parseCompilationUnit()
    // Scanner's trivia builder is populated incrementally as the parser
    // pulls tokens; by the time parseCompilationUnit returns it has
    // observed every real token and the table is complete.
    ParseResult(tree, privateReporter.diagnostics.toList, scanner.triviaTable)

  /** Adapter: parse and push diagnostics into the supplied reporter.
    * Retained so existing callers and tests keep working unchanged.
    * Removed in Phase 3 once every caller is on the [[ParseResult]]
    * API.
    */
  def parse(source: SourceFile, reporter: Reporter): Tree =
    val result = parse(source)
    result.diagnostics.foreach(reporter.add)
    result.tree

end Parser
