package fixed.parsing

import scala.annotation.tailrec

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile, Span}

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
    val (_, tree) = Parser.parseCompilationUnit(initialState(tokens), reporter)
    tree

  /** Parse a single expression; primarily used by tests. */
  def parseExpr(): Tree =
    val (_, tree) = Parser.parseExpr(initialState(tokens), reporter)
    tree

  /** Parse a single type expression; primarily used by tests. */
  def parseTypeExpr(): Tree =
    val (_, tree) = Parser.parseTypeExpr(initialState(tokens), reporter)
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

  /** Consume `current` if it matches `kind`; otherwise emit an error
    * and return a synthetic token with span at the current position so
    * the caller can continue building a tree.
    */
  private def expect(
      s: ParserState,
      kind: TokenKind,
      what: String,
      reporter: Reporter
  ): Parsed[Token] =
    if s.current.kind == kind then consume(s)
    else
      reporter.error(
        "P001",
        s.current.span,
        s"expected $what (token ${kind}), got ${s.current.kind}${describeLexeme(s.current)}"
      )
      (s, Token(kind, Span(s.current.span.start, s.current.span.start)))

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
      case None => s
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
      reporter: Reporter,
      acc0: A
  )(parseStep: (ParserState, A) => Parsed[A]): Parsed[A] =
    val (s1, _) = expect(s0, TokenKind.Indent, "INDENT", reporter)
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
      TokenKind.KwUse, TokenKind.KwFn, TokenKind.KwCap, TokenKind.KwData,
      TokenKind.KwEffect, TokenKind.KwLinear, TokenKind.KwType,
      TokenKind.KwMod, TokenKind.KwPub, TokenKind.UpperIdent,
      TokenKind.Eof
    )

    val blockBody: Set[TokenKind] = Set(TokenKind.Newline, TokenKind.Dedent)

    /** Anything that can plausibly follow a type. Arrow is included so a
      * partial atom can sync forward and let `parseArrowType` resume. */
    val typeExpr: Set[TokenKind] = Set(
      TokenKind.Comma, TokenKind.RParen, TokenKind.RBracket,
      TokenKind.Newline, TokenKind.Dedent,
      TokenKind.Eq, TokenKind.Arrow, TokenKind.KwWith
    )
  end Anchors

  // ---- Compilation unit ----

  def parseCompilationUnit(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val startSpan = s0.current.span
    val s1 = skipNewlines(s0)
    val (s2, items) = withAnchors(s1, Anchors.topLevel) { st =>
      // Items at top level are separated by NEWLINE; allow blank lines.
      @tailrec
      def loop(state: ParserState, acc: List[Tree]): Parsed[List[Tree]] =
        if state.current.kind == TokenKind.Eof then (state, acc.reverse)
        else
          val (state1, item) = parseTopItem(state, reporter)
          val state2 = skipNewlines(state1)
          loop(state2, item :: acc)
      loop(st, Nil)
    }
    val endSpan = s2.current.span
    (s2, Trees.CompilationUnit(items, span(startSpan, endSpan)))

  // ---- Top items ----

  private def parseTopItem(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwUse       => parseUseDecl(s, reporter)
    case TokenKind.KwFn        => parseFnDecl(s, reporter)
    case TokenKind.KwCap       => parseCapDecl(s, reporter)
    case TokenKind.KwData      => parseDataDecl(s, reporter)
    case TokenKind.KwEffect    => parseEffectDecl(s, reporter)
    case TokenKind.KwLinear    => parseEffectDecl(s, reporter)
    case TokenKind.KwType      => parseTypeAlias(s, reporter)
    case TokenKind.KwMod       => parseModDecl(s, reporter)
    case TokenKind.KwPub       => unsupported(s, "`pub` modifier", reporter)
    case TokenKind.UpperIdent  => parseSatisfiesDecl(s, reporter)
    // A primitive (LowerIdent) followed by `satisfies` is also a
    // satisfaction declaration (e.g. `u64 satisfies Optional of Self`).
    case TokenKind.LowerIdent if s.peek(1).kind == TokenKind.KwSatisfies =>
      parseSatisfiesDecl(s, reporter)
    case _                      =>
      // Unknown token at top level. Emit a diagnostic, then synchronise
      // to the next decl introducer (or EOF). This collapses runs of
      // junk into a single Error item rather than one per stray token.
      val startSpan = s.current.span
      reporter.error(
        "P002",
        startSpan,
        s"unexpected token at top level: ${s.current.kind}${describeLexeme(s.current)}",
        Some("expected `use`, `fn`, `cap`, `data`, `effect`, `type`, `mod`, or `<TypeName> satisfies …`")
      )
      // syncToAnchors stops at the next anchor (top-level keyword or EOF)
      // without consuming it, so the outer loop dispatches that token to
      // its proper handler.
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(startSpan, s1.current.span)))

  private def unsupported(s: ParserState, what: String, reporter: Reporter): Parsed[Tree] =
    reporter.error(
      "P099",
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
  private def parseUseDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwUse, "`use`", reporter)
    val (s2, head) = parseIdentTextEither(s1, reporter)
    // Walk `.Ident` segments, optionally terminated by `.{ name, ... }`.
    @tailrec
    def loop(st: ParserState, path: List[String]): (ParserState, List[String], List[String]) =
      if st.current.kind != TokenKind.Dot then (st, path.reverse, Nil)
      else
        val st1 = st.advance
        if st1.current.kind == TokenKind.LBrace then
          val st2 = st1.advance
          val (st3, sels) =
            parseDelimitedTyped(st2, TokenKind.RBrace, "}", reporter)((s, r) =>
              parseIdentTextEither(s, r)
            )
          val (st4, _) = expect(st3, TokenKind.RBrace, "`}`", reporter)
          (st4, path.reverse, sels)
        else
          val (st2, seg) = parseIdentTextEither(st1, reporter)
          loop(st2, seg :: path)
    val (s3, pathOut, selectors) = loop(s2, head :: Nil)
    val (s4, satisfies) = accept(s3, TokenKind.KwSatisfies) match
      case (st, Some(_)) =>
        val (st1, c) = parseCapRef(st, reporter)
        (st1, Some(c))
      case (st, None) => (st, None)
    val endSpan = s4.current.span
    (s4, Trees.UseDecl(pathOut, selectors, satisfies, span(startTok.span, endSpan)))

  private def parseIdentTextEither(s: ParserState, reporter: Reporter): Parsed[String] =
    s.current.kind match
      case TokenKind.LowerIdent | TokenKind.UpperIdent =>
        val (s1, tok) = consume(s)
        (s1, tok.lexeme)
      case _ =>
        reporter.error(
          "P003",
          s.current.span,
          s"expected an identifier, got ${s.current.kind}${describeLexeme(s.current)}"
        )
        (s, "<error>")

  // ---- FnDecl (top-level or nested) ----

  private def parseFnDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwFn, "`fn`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, "function name", reporter)
    val (s3, typeParamsHint) = parseOptionalTypeParamsHint(s2, reporter)
    val (s4, params) = parseFnParamList(s3, reporter)
    val (s5, returnType) = accept(s4, TokenKind.Arrow) match
      case (st, Some(_)) => parseTypeExpr(st, reporter)
      case (st, None)    => (st, Trees.UnitType(st.current.span))
    val (s6, withClause) = parseOptionalWithClause(s5, reporter)
    val (s7, body) = accept(s6, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, b) = parseFnBody(st, reporter)
        (st1, Some(b))
      case (st, None) => (st, None)
    val tree = Trees.FnDecl(
      name = nameTok.lexeme,
      typeParamsHint = typeParamsHint,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, s7.current.span)
    )
    (s7, tree)

  private def parseFnParamList(s0: ParserState, reporter: Reporter): Parsed[List[Trees.FnParam]] =
    val (s1, _) = expect(s0, TokenKind.LParen, "`(`", reporter)
    val (s2, params) =
      parseDelimitedTyped(s1, TokenKind.RParen, ")", reporter)((s, r) => parseFnParam(s, r))
    val (s3, _) = expect(s2, TokenKind.RParen, "`)`", reporter)
    (s3, params)

  private def parseFnParam(s0: ParserState, reporter: Reporter): Parsed[Trees.FnParam] =
    val (s1, nameTok) = expect(s0, TokenKind.LowerIdent, "parameter name", reporter)
    val (s2, _) = expect(s1, TokenKind.Colon, "`:`", reporter)
    val (s3, ty) = parseTypeExpr(s2, reporter)
    val (s4, default) = accept(s3, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
      case (st, None) => (st, None)
    (s4, Trees.FnParam(nameTok.lexeme, ty, default, span(nameTok.span, s4.current.span)))

  private def parseOptionalWithClause(s0: ParserState, reporter: Reporter): Parsed[Option[Tree]] =
    accept(s0, TokenKind.KwWith) match
      case (s1, Some(_)) =>
        val (s2, row) = parseEffectRow(s1, reporter)
        (s2, Some(Trees.WithClause(row, row.span)))
      case (s1, None) => (s1, None)

  private def parseEffectRow(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, first) = parseEffectBound(s0, reporter)
    if s1.current.kind != TokenKind.Plus then (s1, first)
    else
      @tailrec
      def loop(st: ParserState, acc: List[Tree]): Parsed[List[Tree]] =
        accept(st, TokenKind.Plus) match
          case (st1, Some(_)) =>
            val (st2, e) = parseEffectBound(st1, reporter)
            loop(st2, e :: acc)
          case (st1, None) => (st1, acc.reverse)
      val (s2, rest) = loop(s1, Nil)
      val all = first :: rest
      (s2, Trees.EffectRow(all, span(first.span, all.last.span)))

  private def parseEffectBound(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, "effect name", reporter)
    val (s2, ofArg) = accept(s1, TokenKind.KwOf) match
      case (st, Some(_)) =>
        val (st1, a) = parseOfArg(st, reporter); (st1, Some(a))
      case (st, None) => (st, None)
    (s2, Trees.EffectBound(nameTok.lexeme, ofArg, span(nameTok.span, s2.current.span)))

  // A fn body is either a same-line Expr after `=` or an INDENTed block.
  private def parseFnBody(s: ParserState, reporter: Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.Indent then parseBlock(s, reporter)
    else parseExpr(s, reporter)

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
  private def parseBlock(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val startSpan = s0.current.span
    val (s1, stmts) = parseIndentedBody(s0, reporter, List.empty[Tree]) { (state, acc) =>
      val stmtStart = state.current.span
      val (state1, stmt) = parseStatement(state, reporter)
      if Anchors.blockBody.contains(state1.current.kind)
         || state1.current.kind == TokenKind.Eof
         || state1.isOuterBracketWaiter(state1.current.kind)
      then (state1, stmt :: acc)
      else
        reporter.error(
          "P011",
          state1.current.span,
          s"unexpected ${state1.current.kind}${describeLexeme(state1.current)} after statement; expected end of line",
          Some("split this expression onto its own line")
        )
        val state2 = syncToAnchors(state1)
        (state2, Trees.Error(List(stmt), span(stmtStart, state2.current.span)) :: acc)
    }
    (s1, Trees.Block(stmts.reverse, span(startSpan, s1.current.span)))

  // Fixed has no statements — every block element is an Expr, with two
  // exceptions admissible in any block:
  //   - `fn name(...) = body` — nested local function.
  //   - `prop name: body` — invariant (used in `fn -> cap` bodies).
  private def parseStatement(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwFn   => parseFnDecl(s, reporter)
    case TokenKind.KwProp =>
      val (s1, p) = parsePropDecl(s, reporter)
      (s1, p)
    case _                => parseExpr(s, reporter)

  // ---- Type expressions ----

  // TypeExpr ::= ArrowType ::= ArrowLhs ("->" ArrowType WithClause?)?
  // The anchor frame lets a failed `parseTypeAtom` sync to whatever
  // plausibly follows a type.
  def parseTypeExpr(s: ParserState, reporter: Reporter): Parsed[Tree] =
    withAnchors(s, Anchors.typeExpr) { st => parseArrowType(st, reporter) }

  private def parseArrowType(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseArrowLhs(s0, reporter)
    accept(s1, TokenKind.Arrow) match
      case (s2, Some(_)) =>
        val (s3, rhs) = parseArrowType(s2, reporter)
        val (s4, withClause) = parseOptionalWithClause(s3, reporter)
        (s4, Trees.ArrowType(lhs, rhs, withClause, span(lhs.span, s4.current.span)))
      case (s2, None) => (s2, lhs)

  private def parseArrowLhs(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    if s0.current.kind == TokenKind.LParen then
      // Either: `(T1, T2, ...)` → TupleArrowLhs/UnitType
      //         `(T)` → just T (parenthesised)
      //         `(T) of OfArg` → ParenTypeApp
      val startSpan = s0.current.span
      val s1 = s0.advance
      accept(s1, TokenKind.RParen) match
        case (s2, Some(_)) =>
          (s2, Trees.UnitType(span(startSpan, s2.current.span)))
        case (s2, None) =>
          val (s3, first) = parseTypeExpr(s2, reporter)
          accept(s3, TokenKind.Comma) match
            case (s4, Some(_)) =>
              val (s5, tail) =
                parseDelimited(s4, TokenKind.RParen, ")", reporter)((st, r) => parseTypeExpr(st, r))
              val (s6, _) = expect(s5, TokenKind.RParen, "`)`", reporter)
              (s6, Trees.TupleArrowLhs(first :: tail, span(startSpan, s6.current.span)))
            case (s4, None) =>
              val (s5, _) = expect(s4, TokenKind.RParen, "`)`", reporter)
              accept(s5, TokenKind.KwOf) match
                case (s6, Some(_)) =>
                  val (s7, arg) = parseOfArg(s6, reporter)
                  (s7, Trees.ParenTypeApp(first, arg, span(startSpan, s7.current.span)))
                case (s6, None) => (s6, first)
    else parseTypeAtom(s0, reporter)

  private def parseTypeAtom(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.Bang =>
      val (s1, tok) = consume(s)
      (s1, Trees.NeverType(tok.span))
    case TokenKind.KwSelf =>
      val (s1, tok) = consume(s)
      val (s2, ofArg) = accept(s1, TokenKind.KwOf) match
        case (st, Some(_)) =>
          val (st1, a) = parseOfArg(st, reporter); (st1, Some(a))
        case (st, None) => (st, None)
      (s2, Trees.SelfType(ofArg, span(tok.span, s2.current.span)))
    case TokenKind.KwPart =>
      // `Part` — the cap's element-type pseudo-parameter (Rule 5.3).
      // In type position it behaves like a TypeRef. `Part is C` lifts
      // it into a NamedAlias so callers can constrain Part by a
      // capability chain (`cap Sorted of (Part is Ord)`).
      val (s1, tok) = consume(s)
      if s1.current.kind == TokenKind.KwIs then
        val s2 = s1.advance
        val (s3, caps) = parseCapBoundChain(s2, reporter)
        (s3, Trees.NamedAlias("Part", caps, span(tok.span, s3.current.span)))
      else
        val (s2, ofArg) = accept(s1, TokenKind.KwOf) match
          case (st, Some(_)) =>
            val (st1, a) = parseOfArg(st, reporter); (st1, Some(a))
          case (st, None) => (st, None)
        (s2, Trees.TypeRef("Part", ofArg, span(tok.span, s2.current.span)))
    case TokenKind.KwIs =>
      parseIsBound(s, reporter)
    case TokenKind.KwCap =>
      parseCapType(s, reporter)
    case TokenKind.UpperIdent =>
      // Could be NamedAlias (`N is C`), TypeRef (`T`, `T of A`).
      val (s1, nameTok) = consume(s)
      if s1.current.kind == TokenKind.KwIs then
        val s2 = s1.advance
        val (s3, caps) = parseCapBoundChain(s2, reporter)
        (s3, Trees.NamedAlias(nameTok.lexeme, caps, span(nameTok.span, s3.current.span)))
      else
        val (s2, ofArg) = accept(s1, TokenKind.KwOf) match
          case (st, Some(_)) =>
            val (st1, a) = parseOfArg(st, reporter); (st1, Some(a))
          case (st, None) => (st, None)
        (s2, Trees.TypeRef(nameTok.lexeme, ofArg, span(nameTok.span, s2.current.span)))
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.PrimitiveType(tok.lexeme, tok.span))
    case _ =>
      val startSpan = s.current.span
      reporter.error(
        "P004",
        startSpan,
        s"expected a type expression, got ${s.current.kind}${describeLexeme(s.current)}"
      )
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(startSpan, s1.current.span)))

  private def parseIsBound(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwIs, "`is`", reporter)
    val (s2, caps) = parseCapBoundChain(s1, reporter)
    (s2, Trees.IsBound(caps, span(startTok.span, s2.current.span)))

  private def parseCapBoundChain(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, head) = parseCapBound(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: List[Tree]): Parsed[List[Tree]] =
      accept(st, TokenKind.Plus) match
        case (st1, Some(_)) =>
          val (st2, c) = parseCapBound(st1, reporter)
          loop(st2, c :: acc)
        case (st1, None) => (st1, acc.reverse)
    val (s2, rest) = loop(s1, Nil)
    (s2, head :: rest)

  private def parseCapBound(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.UpperIdent =>
      // `Name(args)` — invocation of a parameterised type alias or
      // cap-generating function whose name happens to be uppercase.
      // Same shape as `lower(args)` below; the typer disambiguates by
      // resolving the binding.
      if s.peek(1).kind == TokenKind.LParen then
        val (s1, nameTok) = consume(s)
        val (s2, args) = parseArgList(s1, reporter)
        (s2, Trees.RefinementCall(nameTok.lexeme, args, span(nameTok.span, s2.current.span)))
      else parseCapRef(s, reporter)
    case TokenKind.LowerIdent =>
      val (s1, nameTok) = consume(s)
      if s1.current.kind == TokenKind.LParen then
        val (s2, args) = parseArgList(s1, reporter)
        (s2, Trees.RefinementCall(nameTok.lexeme, args, span(nameTok.span, s2.current.span)))
      else
        (s1, Trees.PrimitiveType(nameTok.lexeme, nameTok.span))
    case _ =>
      reporter.error(
        "P005",
        s.current.span,
        s"expected a capability bound, got ${s.current.kind}${describeLexeme(s.current)}"
      )
      val (s1, tok) = consume(s)
      (s1, Trees.Error(Nil, tok.span))

  private def parseCapRef(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, "capability name", reporter)
    val (s2, ofArg) = accept(s1, TokenKind.KwOf) match
      case (st, Some(_)) =>
        val (st1, a) = parseOfArg(st, reporter); (st1, Some(a))
      case (st, None) => (st, None)
    (s2, Trees.CapRef(nameTok.lexeme, ofArg, span(nameTok.span, s2.current.span)))

  // OfArg ::= OfValueParam (decl-site) | LiteralExpr | TypeExpr. The
  // parser admits any TypeExpr or a parenthesised comma-list (for the
  // tuple-of-TypeExpr form `BiFunctor of (A, B)`); decl-site value
  // params and literal-expression OfArgs are disambiguated by the
  // typer.
  private def parseOfArg(s: ParserState, reporter: Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LParen then
      val startSpan = s.current.span
      val s1 = s.advance
      // `()` — unit type as `of` argument (e.g. `M is Monad of ()`).
      accept(s1, TokenKind.RParen) match
        case (s2, Some(_)) =>
          (s2, Trees.UnitType(span(startSpan, s2.current.span)))
        case (s2, None) =>
          // Use parseDataOfItem so literal arguments like `Bounded of (N,
          // 0, 10)` and OfValueParam-shaped forms work in any of-arg
          // position, not just on data declarations.
          val (s3, first) = parseDataOfItem(s2, reporter)
          accept(s3, TokenKind.Comma) match
            case (s4, Some(_)) =>
              val (s5, tail) =
                parseDelimited(s4, TokenKind.RParen, ")", reporter)((st, r) => parseDataOfItem(st, r))
              val (s6, _) = expect(s5, TokenKind.RParen, "`)`", reporter)
              (s6, Trees.TupleArrowLhs(first :: tail, span(startSpan, s6.current.span)))
            case (s4, None) =>
              val (s5, _) = expect(s4, TokenKind.RParen, "`)`", reporter)
              (s5, first)
    else parseTypeExpr(s, reporter)

  // CapType ::= "cap" CapReturnSpec?
  // CapReturnSpec ::= "of" OfArg
  //                 | "extends" CapBound ("+" CapBound)*
  // The grammar makes the two arms exclusive (spec/syntax_grammar.ebnf
  // §CapReturnSpec): a `cap` is followed by either `of …` or
  // `extends …`, never both — so `Option[CapReturnSpec]` fits.
  private def parseCapType(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwCap, "`cap`", reporter)
    val (s2, spec) = accept(s1, TokenKind.KwOf) match
      case (st, Some(_)) =>
        val (st1, a) = parseOfArg(st, reporter)
        (st1, Some(Trees.CapReturnSpec.OfArg(a)))
      case (st, None) =>
        accept(st, TokenKind.KwExtends) match
          case (st1, Some(_)) =>
            val (st2, caps) = parseCapBoundChain(st1, reporter)
            (st2, Some(Trees.CapReturnSpec.Extends(caps)))
          case (st1, None) => (st1, None)
    (s2, Trees.CapType(spec, span(startTok.span, s2.current.span)))

  // ---- Expressions ----

  def parseExpr(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwLet => parseLetExpr(s, reporter)
    case _ => parsePipeExpr(s, reporter)

  private def parseLetExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwLet, "`let`", reporter)
    val (s2, pat) = parsePattern(s1, reporter)
    val (s3, _) = expect(s2, TokenKind.Eq, "`=`", reporter)
    val (s4, init) = parsePipeExpr(s3, reporter)
    (s4, Trees.LetExpr(pat, init, span(startTok.span, init.span)))

  private def parsePipeExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseOrExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.PipeForward) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parseOrExpr(st1, reporter)
          loop(st2, Trees.Pipe(acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, lhs)

  private def parseOrExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseAndExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.OrOr) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parseAndExpr(st1, reporter)
          loop(st2, Trees.BinOp("||", acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, lhs)

  private def parseAndExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseCmpExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.AndAnd) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parseCmpExpr(st1, reporter)
          loop(st2, Trees.BinOp("&&", acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, lhs)

  // Non-chaining: at most one comparison operator per expression.
  // `a < b < c` parses as `(a < b) < c`'s LHS rejected by the typer
  // (no `<` at boolean type), not as `a < b && b < c`. The grammar
  // marks CmpExpr non-associative; that's why this production has no
  // outer loop, unlike its peers `parseAddExpr` / `parseMulExpr`.
  private def parseCmpExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseAddExpr(s0, reporter)
    val cmpOp = s1.current.kind match
      case TokenKind.EqEq => Some("==")
      case TokenKind.Neq  => Some("!=")
      case TokenKind.Lt   => Some("<")
      case TokenKind.Le   => Some("<=")
      case TokenKind.Gt   => Some(">")
      case TokenKind.Ge   => Some(">=")
      case _              => None
    cmpOp match
      case Some(op) =>
        val s2 = s1.advance
        val (s3, rhs) = parseAddExpr(s2, reporter)
        (s3, Trees.BinOp(op, lhs, rhs, span(lhs.span, rhs.span)))
      case None => (s1, lhs)

  private def parseAddExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseMulExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      val opt = st.current.kind match
        case TokenKind.Plus  => Some("+")
        case TokenKind.Minus => Some("-")
        case _               => None
      opt match
        case Some(o) =>
          val st1 = st.advance
          val (st2, rhs) = parseMulExpr(st1, reporter)
          loop(st2, Trees.BinOp(o, acc, rhs, span(acc.span, rhs.span)))
        case None => (st, acc)
    loop(s1, lhs)

  private def parseMulExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, lhs) = parseUnaryExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      val opt = st.current.kind match
        case TokenKind.Star    => Some("*")
        case TokenKind.Slash   => Some("/")
        case TokenKind.Percent => Some("%")
        case _                 => None
      opt match
        case Some(o) =>
          val st1 = st.advance
          val (st2, rhs) = parseUnaryExpr(st1, reporter)
          loop(st2, Trees.BinOp(o, acc, rhs, span(acc.span, rhs.span)))
        case None => (st, acc)
    loop(s1, lhs)

  private def parseUnaryExpr(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.Minus =>
      val (s1, tok) = consume(s)
      val (s2, operand) = parseAppExpr(s1, reporter)
      (s2, Trees.UnaryOp("-", operand, span(tok.span, operand.span)))
    case TokenKind.Bang =>
      val (s1, tok) = consume(s)
      val (s2, operand) = parseAppExpr(s1, reporter)
      (s2, Trees.UnaryOp("!", operand, span(tok.span, operand.span)))
    case _ => parseAppExpr(s, reporter)

  private def parseAppExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, recv) = parseAtomExpr(s0, reporter)
    @tailrec
    def loop(st: ParserState, receiver: Tree): Parsed[Tree] =
      st.current.kind match
        case TokenKind.LParen =>
          val (st1, args) = parseArgList(st, reporter)
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
            case _ =>
              reporter.error(
                "P006",
                st1.current.span,
                s"expected method or field name after `.`, got ${st1.current.kind}${describeLexeme(st1.current)}"
              )
              consume(st1)
          val (st3, args, hasArgList) =
            if st2.current.kind == TokenKind.LParen then
              val (st21, as) = parseArgList(st2, reporter)
              (st21, as, true)
            else (st2, Nil, false)
          val next = receiver match
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) && hasArgList =>
              Trees.StaticCall(qual, nameTok.lexeme, args, hasArgList, span(qspan, st3.current.span))
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) && !hasArgList =>
              // Bare `T.Variant` (or static-method-no-args). Encoded as
              // StaticCall with empty args; the typer disambiguates.
              Trees.StaticCall(qual, nameTok.lexeme, Nil, hasArgList = false, span(qspan, st3.current.span))
            case _ =>
              Trees.MethodCall(receiver, nameTok.lexeme, args, hasArgList, span(receiver.span, st3.current.span))
          loop(st3, next)
        case _ => (st, receiver)
    loop(s1, recv)

  private def parseArgList(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, _) = expect(s0, TokenKind.LParen, "`(`", reporter)
    val (s2, args) = parseDelimited(s1, TokenKind.RParen, ")", reporter)((st, r) => parseExpr(st, r))
    val (s3, _) = expect(s2, TokenKind.RParen, "`)`", reporter)
    (s3, args)

  /** Drain trivia tokens between comma-list items inside brackets.
    * NEWLINEs are noise inside parens — always drain. DEDENTs are ours
    * to consume only when the matching block exited bracket-aware
    * (`owedDedents > 0`); a real outer DEDENT would otherwise be
    * silently swallowed on a recovery path. */
  @tailrec
  private def drainListSeparators(s: ParserState): ParserState =
    s.current.kind match
      case TokenKind.Newline => drainListSeparators(s.advance)
      case TokenKind.Dedent if s.owedDedents > 0 =>
        drainListSeparators(s.advance.consumeOwed)
      case _ => s

  /** Parse a comma-separated list of items up to (but not including)
    * `closeKind`. Recovery: if `parseItem` leaves the parser on a token
    * that is neither `,` nor `closeKind`, emit a diagnostic, sync to
    * the next such token, and wrap the partial item in `Trees.Error`.
    * Trailing comma admitted. */
  private inline def parseDelimited(
      s0: ParserState,
      closeKind: TokenKind,
      closeLexeme: String,
      reporter: Reporter
  )(inline parseItem: (ParserState, Reporter) => Parsed[Tree]): Parsed[List[Tree]] =
    parseDelimitedLoop[Tree](s0, closeKind, closeLexeme, reporter) { (st, r, itemStart) =>
      val (st1, item) = parseItem(st, r)
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
      closeLexeme: String,
      reporter: Reporter
  )(inline parseItem: (ParserState, Reporter) => Parsed[A]): Parsed[List[A]] =
    parseDelimitedLoop[A](s0, closeKind, closeLexeme, reporter) { (st, r, _) =>
      val (st1, item) = parseItem(st, r)
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
      closeLexeme: String,
      reporter: Reporter
  )(inline parseItem: (ParserState, Reporter, Span) => (ParserState, A, A)): Parsed[List[A]] =
    val anchors = Set(TokenKind.Comma, closeKind)

    def parseOne(st: ParserState): Parsed[A] =
      val itemStart = st.current.span
      val (st1, ok, recovered) = parseItem(st, reporter, itemStart)
      val st2 = drainListSeparators(st1)
      if anchors.contains(st2.current.kind) || st2.current.kind == TokenKind.Eof then
        (st2, ok)
      else
        reporter.error(
          "P012",
          st2.current.span,
          s"unexpected ${st2.current.kind}${describeLexeme(st2.current)} in list; expected `,` or `$closeLexeme`",
          Some(s"add `,` to continue or `$closeLexeme` to close the list")
        )
        val st3 = syncToAnchors(st2)
        (st3, recovered)

    @tailrec
    def loopAfterComma(st: ParserState, acc: List[A]): Parsed[List[A]] =
      accept(st, TokenKind.Comma) match
        case (st1, Some(_)) =>
          val st2 = drainListSeparators(st1)
          if st2.current.kind == closeKind then (st2, acc.reverse)  // trailing comma
          else
            val (st3, item) = parseOne(st2)
            loopAfterComma(st3, item :: acc)
        case (st1, None) => (st1, acc.reverse)

    withAnchors(s0, anchors) { st =>
      if st.current.kind == closeKind then (st, Nil)
      else
        val (st1, head) = parseOne(st)
        loopAfterComma(st1, head :: Nil)
    }

  private def parseAtomExpr(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
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
              val (sx, e) = parseExpr(st, reporter); (sx, Some(e))
          val (st2, _) = expect(st1, TokenKind.RParen, "`)`", reporter)
          (st2, v)
        case (st, None) => (st, None)
      (s2, Trees.Resume(arg, span(tok.span, s2.current.span)))
    case TokenKind.KwSelf =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident("Self", tok.span))
    case TokenKind.KwSelfLower =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident("self", tok.span))
    case TokenKind.KwIf      => parseIfExpr(s, reporter)
    case TokenKind.KwLet     => parseLetExpr(s, reporter)
    case TokenKind.KwMatch   => parseMatchExpr(s, reporter)
    case TokenKind.KwHandle  => parseHandleExpr(s, reporter)
    case TokenKind.KwDo      => parseDoExpr(s, reporter)
    case TokenKind.KwForall  => parseForallExpr(s, reporter)
    case TokenKind.LBracket  => parseListExpr(s, reporter)
    case TokenKind.LParen    => parseParenOrLambdaOrTuple(s, reporter)
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.Ident(tok.lexeme, tok.span))
    case TokenKind.UpperIdent =>
      val (s1, tok) = consume(s)
      // `T { field: value, ... }` — struct literal for single-variant
      // data types (`data Point(x: N, y: N)`-style).
      if s1.current.kind == TokenKind.LBrace then parseStructLit(s1, tok, reporter)
      else (s1, Trees.Ident(tok.lexeme, tok.span))
    case _ =>
      reporter.error(
        "P007",
        s.current.span,
        s"expected an expression, got ${s.current.kind}${describeLexeme(s.current)}"
      )
      val (s1, tok) = consume(s)
      (s1, Trees.Error(Nil, tok.span))

  private def parseIfExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwIf, "`if`", reporter)
    val (s2, cond) = parseExpr(s1, reporter)
    val (s3, _) = expect(s2, TokenKind.Colon, "`:` after if condition", reporter)
    val (s4, thenBranch) = parseInlineOrBlockExpr(s3, reporter)
    val s5 = skipNewlines(s4)
    val (s6, _) = expect(s5, TokenKind.KwElse, "`else`", reporter)
    // `else if cond: ...` chains the next conditional as the else branch
    // — a right-associative if/else-if/else cascade. `else: ...` is the
    // base case.
    val (s7, elseBranch) =
      if s6.current.kind == TokenKind.KwIf then parseIfExpr(s6, reporter)
      else
        val (s6a, _) = expect(s6, TokenKind.Colon, "`:` after `else`", reporter)
        parseInlineOrBlockExpr(s6a, reporter)
    (s7, Trees.IfExpr(cond, thenBranch, elseBranch, span(startTok.span, elseBranch.span)))

  // MatchExpr ::= "match" Expr ":" INDENT MatchArm+ DEDENT
  // MatchArm  ::= OrPattern ("if" Expr)? "=>" InlineOrBlockExpr
  private def parseMatchExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwMatch, "`match`", reporter)
    val (s2, scrutinee) = parseExpr(s1, reporter)
    val (s3, _) = expect(s2, TokenKind.Colon, "`:` after match scrutinee", reporter)
    val (s4, arms) = parseMatchArms(s3, reporter)
    (s4, Trees.MatchExpr(scrutinee, arms, span(startTok.span, s4.current.span)))

  private def parseMatchArms(s0: ParserState, reporter: Reporter): Parsed[List[Trees.MatchArm]] =
    val (s1, arms) = parseIndentedBody(s0, reporter, List.empty[Trees.MatchArm]) { (st, acc) =>
      val (st1, arm) = parseMatchArm(st, reporter)
      (st1, arm :: acc)
    }
    (s1, arms.reverse)

  private def parseMatchArm(s0: ParserState, reporter: Reporter): Parsed[Trees.MatchArm] =
    val (s1, pat) = parseOrPattern(s0, reporter)
    val (s2, guard) = accept(s1, TokenKind.KwIf) match
      case (st, Some(_)) =>
        val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
      case (st, None) => (st, None)
    val (s3, _) = expect(s2, TokenKind.FatArrow, "`=>` in match arm", reporter)
    val (s4, body) = parseInlineOrBlockExpr(s3, reporter)
    (s4, Trees.MatchArm(pat, guard, body, span(pat.span, body.span)))

  private def parseOrPattern(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, head) = parsePattern(s0, reporter)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.Pipe) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parsePattern(st1, reporter)
          loop(st2, Trees.OrPat(acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, head)

  // DoExpr ::= "do" ":" INDENT DoStmt+ DEDENT
  // DoStmt ::= Pattern "<-" Expr   (DoBind)
  //          | Expr
  private def parseDoExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwDo, "`do`", reporter)
    val (s2, _) = expect(s1, TokenKind.Colon, "`:` after `do`", reporter)
    val (s3, stmts) = parseDoStmts(s2, reporter)
    (s3, Trees.DoExpr(stmts, span(startTok.span, s3.current.span)))

  private def parseDoStmts(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, stmts) = parseIndentedBody(s0, reporter, List.empty[Tree]) { (state, acc) =>
      val stmtStart = state.current.span
      val (state1, stmt) =
        if isDoBindAhead(state) then
          val (st1, pat) = parsePattern(state, reporter)
          val (st2, _) = expect(st1, TokenKind.BackArrow, "`<-`", reporter)
          val (st3, rhs) = parseExpr(st2, reporter)
          (st3, Trees.DoBind(pat, rhs, span(pat.span, rhs.span)))
        else parseExpr(state, reporter)
      if Anchors.blockBody.contains(state1.current.kind)
         || state1.current.kind == TokenKind.Eof
         || state1.isOuterBracketWaiter(state1.current.kind)
      then (state1, stmt :: acc)
      else
        reporter.error(
          "P018",
          state1.current.span,
          s"unexpected ${state1.current.kind}${describeLexeme(state1.current)} after do statement; expected end of line",
          Some("split this expression onto its own line")
        )
        val state2 = syncToAnchors(state1)
        (state2, Trees.Error(List(stmt), span(stmtStart, state2.current.span)) :: acc)
    }
    (s1, stmts.reverse)

  // True iff the current statement contains a top-level `<-` before
  // its terminating NEWLINE/DEDENT/EOF — i.e. it's a `pat <- expr`
  // bind rather than a plain expression. Tracks paren/bracket/brace
  // depth so a `<-` inside a nested expression is not mistaken for
  // the bind arrow.
  private def isDoBindAhead(s: ParserState): Boolean =
    @tailrec
    def loop(i: Int, depth: Int): Boolean =
      s.peek(i).kind match
        case TokenKind.BackArrow if depth == 0 => true
        case TokenKind.Newline | TokenKind.Dedent | TokenKind.Eof => false
        case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace =>
          loop(i + 1, depth + 1)
        case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace =>
          if depth == 0 then false else loop(i + 1, depth - 1)
        case _ => loop(i + 1, depth)
    loop(0, 0)

  // ForallExpr ::= "forall" "(" FnParam ("," FnParam)* ")"
  //                ("suchThat" Expr)? "->" InlineOrBlockExpr
  private def parseForallExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwForall, "`forall`", reporter)
    val (s2, binders) = parseFnParamList(s1, reporter)
    val (s3, suchThat) = accept(s2, TokenKind.KwSuchThat) match
      case (st, Some(_)) =>
        val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
      case (st, None) => (st, None)
    val (s4, _) = expect(s3, TokenKind.Arrow, "`->` after forall binders", reporter)
    val (s5, body) = parseInlineOrBlockExpr(s4, reporter)
    (s5, Trees.ForallExpr(binders, suchThat, body, span(startTok.span, body.span)))

  // HandleExpr ::= "handle" Expr ":" INDENT HandlerArm+ DEDENT
  // HandlerArm ::= EffectArm | ReturnArm
  //   EffectArm ::= UPPER_IDENT "." LOWER_IDENT ("(" PatternList ")")? "=>" Body
  //   ReturnArm ::= "return" "(" Pattern ")" "=>" Body
  // The optional return arm is partitioned out at parse time; multiple
  // return arms are admitted by the grammar but only the last one
  // survives (well-formed programs have at most one).
  private def parseHandleExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwHandle, "`handle`", reporter)
    val (s2, subject) = parseExpr(s1, reporter)
    val (s3, _) = expect(s2, TokenKind.Colon, "`:` after handle subject", reporter)
    val (s4, arms, returnArm) = parseHandlerArms(s3, reporter)
    (s4, Trees.HandleExpr(subject, arms, returnArm, span(startTok.span, s4.current.span)))

  // Internal accumulator for handler-arms parsing: we collect normal
  // arms and (possibly) a single return arm in lockstep so the body
  // loop stays a single fold over `parseIndentedBody`.
  private final case class HandlerAcc(
      arms: List[Trees.HandlerArm],
      returnArm: Option[Trees.ReturnArm]
  )

  private def parseHandlerArms(
      s0: ParserState,
      reporter: Reporter
  ): (ParserState, List[Trees.HandlerArm], Option[Trees.ReturnArm]) =
    val (s1, acc) = parseIndentedBody(s0, reporter, HandlerAcc(Nil, None)) { (st, acc) =>
      val (st1, armOpt) = parseHandlerArm(st, reporter)
      val acc1 = armOpt match
        case ra: Trees.ReturnArm  => acc.copy(returnArm = Some(ra))
        case ha: Trees.HandlerArm => acc.copy(arms = ha :: acc.arms)
        case _: Trees.Error       => acc  // recovery; diagnostic already emitted
        case _                    => acc
      (st1, acc1)
    }
    (s1, acc.arms.reverse, acc.returnArm)

  private def parseHandlerArm(s: ParserState, reporter: Reporter): Parsed[Tree] =
    val startSpan = s.current.span
    s.current.kind match
      case TokenKind.KwReturn =>
        val s1 = s.advance
        val (s2, _) = expect(s1, TokenKind.LParen, "`(`", reporter)
        val (s3, pat) = parsePattern(s2, reporter)
        val (s4, _) = expect(s3, TokenKind.RParen, "`)`", reporter)
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after return arm pattern", reporter)
        val (s6, body) = parseInlineOrBlockExpr(s5, reporter)
        (s6, Trees.ReturnArm(pat, body, span(startSpan, body.span)))
      case TokenKind.UpperIdent =>
        val (s1, effectTok) = consume(s)
        val (s2, _) = expect(s1, TokenKind.Dot, "`.`", reporter)
        val (s3, opTok) = expect(s2, TokenKind.LowerIdent, "effect op name", reporter)
        val (s4, params) =
          if s3.current.kind == TokenKind.LParen then parseInnerPatternList(s3, reporter)
          else (s3, Nil)
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after effect op", reporter)
        val (s6, body) = parseInlineOrBlockExpr(s5, reporter)
        (s6, Trees.HandlerArm(effectTok.lexeme, opTok.lexeme, params, body, span(startSpan, body.span)))
      case _ =>
        reporter.error(
          "P019",
          s.current.span,
          s"expected a handler arm, got ${s.current.kind}${describeLexeme(s.current)}",
          Some("each arm is `Effect.op(...) => body` or `return(p) => body`")
        )
        val s1 = syncToAnchors(s)
        (s1, Trees.Error(Nil, span(startSpan, s1.current.span)))

  /** A branch body that's either a same-line expression or an INDENTed block. */
  private def parseInlineOrBlockExpr(s: ParserState, reporter: Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.Indent then parseBlock(s, reporter)
    else parseExpr(s, reporter)

  private def parseListExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.LBracket, "`[`", reporter)
    val (s2, elements) =
      parseDelimited(s1, TokenKind.RBracket, "]", reporter)((st, r) => parseExpr(st, r))
    val (s3, endTok) = expect(s2, TokenKind.RBracket, "`]`", reporter)
    (s3, Trees.ListExpr(elements, span(startTok.span, endTok.span)))

  // StructLit ::= UPPER_IDENT "{" Field ("," Field)* ","? "}"
  //   Field   ::= LOWER_IDENT ":" Expr
  // The leading `UPPER_IDENT` has already been consumed; `typeNameTok`
  // carries it.
  private def parseStructLit(
      s0: ParserState,
      typeNameTok: Token,
      reporter: Reporter
  ): Parsed[Tree] =
    val (s1, _) = expect(s0, TokenKind.LBrace, "`{`", reporter)
    val (s2, fields) =
      parseDelimitedTyped(s1, TokenKind.RBrace, "}", reporter) { (st, r) =>
        val (st1, nameTok) = expect(st, TokenKind.LowerIdent, "field name", r)
        val (st2, _) = expect(st1, TokenKind.Colon, "`:` after field name", r)
        val (st3, value) = parseExpr(st2, r)
        (st3, (nameTok.lexeme, value))
      }
    val (s3, endTok) = expect(s2, TokenKind.RBrace, "`}`", reporter)
    (s3, Trees.StructLit(typeNameTok.lexeme, fields, span(typeNameTok.span, endTok.span)))

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
  private def parseParenOrLambdaOrTuple(s: ParserState, reporter: Reporter): Parsed[Tree] =
    if lookaheadIsLambda(s) then parseLambdaExpr(s, reporter)
    else parseParenExpr(s, reporter)

  /** Find the matching `)` for the `(` at `current` and check whether the
    * next non-NEWLINE token after it is `->`. */
  private def lookaheadIsLambda(s: ParserState): Boolean =
    require(s.current.kind == TokenKind.LParen)
    @tailrec
    def loop(i: Int, depth: Int): Int =
      s.peek(i).kind match
        case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace =>
          loop(i + 1, depth + 1)
        case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace =>
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

  private def parseLambdaExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.LParen, "`(`", reporter)
    val (s2, params) =
      parseDelimitedTyped(s1, TokenKind.RParen, ")", reporter)((st, r) => parseLambdaParam(st, r))
    val (s3, _) = expect(s2, TokenKind.RParen, "`)`", reporter)
    val (s4, _) = expect(s3, TokenKind.Arrow, "`->`", reporter)
    val (s5, body) = parseInlineOrBlockExpr(s4, reporter)
    (s5, Trees.LambdaExpr(params, body, span(startTok.span, body.span)))

  private def parseLambdaParam(s0: ParserState, reporter: Reporter): Parsed[Trees.FnParam] =
    val (s1, name, startSpan) = s0.current.kind match
      case TokenKind.LowerIdent =>
        val (st, tok) = consume(s0); (st, tok.lexeme, tok.span)
      case TokenKind.Underscore =>
        val (st, tok) = consume(s0); (st, "_", tok.span)
      case _ =>
        reporter.error(
          "P008",
          s0.current.span,
          s"expected lambda parameter name, got ${s0.current.kind}${describeLexeme(s0.current)}"
        )
        val (st, tok) = consume(s0); (st, "<error>", tok.span)
    val (s2, typeAnn) = accept(s1, TokenKind.Colon) match
      case (st, Some(_)) => parseTypeExpr(st, reporter)
      case (st, None)    => (st, Trees.Ident("<inferred>", st.current.span))
    val (s3, default) = accept(s2, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
      case (st, None) => (st, None)
    (s3, Trees.FnParam(name, typeAnn, default, span(startSpan, s3.current.span)))

  private def parseParenExpr(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.LParen, "`(`", reporter)
    accept(s1, TokenKind.RParen) match
      case (s2, Some(_)) =>
        (s2, Trees.UnitLit(span(startTok.span, s2.current.span)))
      case (s2, None) =>
        val (s3, first) = parseExpr(s2, reporter)
        accept(s3, TokenKind.Comma) match
          case (s4, Some(_)) =>
            // Tuple literal — `first` is already past the first comma; the
            // tail uses parseDelimited for recovery and is prepended.
            val (s5, tail) =
              parseDelimited(s4, TokenKind.RParen, ")", reporter)((st, r) => parseExpr(st, r))
            val (s6, _) = expect(s5, TokenKind.RParen, "`)`", reporter)
            (s6, Trees.TupleExpr(first :: tail, span(startTok.span, s6.current.span)))
          case (s4, None) =>
            val (s5, _) = expect(s4, TokenKind.RParen, "`)`", reporter)
            (s5, first)

  // ---- Patterns ----

  private def parsePattern(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
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
          val (s3, variantTok) = expect(s2, TokenKind.UpperIdent, "variant name", reporter)
          if s3.current.kind == TokenKind.LParen then
            val (s4, fields) = parseInnerPatternList(s3, reporter)
            (s4, Trees.DataVariantPat(Some(nameTok.lexeme), variantTok.lexeme, fields, hasArgList = true, span(nameTok.span, s4.current.span)))
          else
            (s3, Trees.DataVariantPat(Some(nameTok.lexeme), variantTok.lexeme, Nil, hasArgList = false, span(nameTok.span, variantTok.span)))
        case (s2, None) =>
          if s2.current.kind == TokenKind.LParen then
            val (s3, fields) = parseInnerPatternList(s2, reporter)
            (s3, Trees.DataVariantPat(None, nameTok.lexeme, fields, hasArgList = true, span(nameTok.span, s3.current.span)))
          else
            (s2, Trees.DataVariantPat(None, nameTok.lexeme, Nil, hasArgList = false, nameTok.span))
    case TokenKind.LParen =>
      // Tuple pattern.
      val (s1, startTok) = consume(s)
      val (s2, elements) =
        parseDelimited(s1, TokenKind.RParen, ")", reporter)((st, r) => parsePattern(st, r))
      val (s3, endTok) = expect(s2, TokenKind.RParen, "`)`", reporter)
      (s3, Trees.TuplePat(elements, span(startTok.span, endTok.span)))
    case TokenKind.IntLit | TokenKind.FloatLit | TokenKind.StringLit
       | TokenKind.CharLit | TokenKind.KwTrue | TokenKind.KwFalse =>
      val sp = s.current.span
      val (s1, lit) = parseAtomExpr(s, reporter)
      (s1, Trees.LiteralPat(lit, sp))
    case _ =>
      reporter.error(
        "P010",
        s.current.span,
        s"expected a pattern, got ${s.current.kind}${describeLexeme(s.current)}"
      )
      val (s1, tok) = consume(s)
      (s1, Trees.WildcardPat(tok.span))

  private def parseInnerPatternList(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, _) = expect(s0, TokenKind.LParen, "`(`", reporter)
    val (s2, pats) =
      parseDelimited(s1, TokenKind.RParen, ")", reporter)((st, r) => parsePattern(st, r))
    val (s3, _) = expect(s2, TokenKind.RParen, "`)`", reporter)
    (s3, pats)

  // ---- DataDecl ----

  // DataDecl ::= "data" UPPER_IDENT OfClause? (DataBody | CtorSugar)?
  //   OfClause   ::= "of" (TypeExpr | "(" TypeExpr ("," TypeExpr)* ")")
  //   DataBody   ::= ":" INDENT DataVariant+ DEDENT
  //   CtorSugar  ::= "(" FieldList ")"   — single-variant shorthand
  //
  //   DataVariant ::= UPPER_IDENT ("(" FieldList ")")?
  //   FieldList   ::= FieldDecl ("," FieldDecl)* ","?
  //   FieldDecl   ::= LOWER_IDENT ":" TypeExpr ("=" Expr)?
  private def parseDataDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwData, "`data`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, "data type name", reporter)
    val (s3, ofParams) = parseOptionalDataOfClause(s2, reporter)
    if s3.current.kind == TokenKind.LParen then
      // Single-ctor sugar — synthesise one variant with the type's name.
      val (s4, fields) = parseFieldList(s3, reporter)
      val variant = Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s4.current.span))
      (s4, Trees.DataDecl(nameTok.lexeme, ofParams, List(variant), Nil, span(startTok.span, s4.current.span)))
    else
      accept(s3, TokenKind.Colon) match
        case (s4, Some(_)) =>
          val (s5, variants, props) = parseDataBody(s4, reporter)
          (s5, Trees.DataDecl(nameTok.lexeme, ofParams, variants, props, span(startTok.span, s5.current.span)))
        case (s4, None) =>
          reporter.error(
            "P013",
            s4.current.span,
            s"`data ${nameTok.lexeme}` needs either a `(field: T, ...)` ctor sugar or a `:` block of variants",
            Some("add `: ...` for variants or `(field: T, ...)` for a single ctor")
          )
          (s4, Trees.DataDecl(nameTok.lexeme, ofParams, Nil, Nil, span(startTok.span, s4.current.span)))

  private def parseOptionalDataOfClause(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    accept(s0, TokenKind.KwOf) match
      case (s1, Some(_)) =>
        if s1.current.kind == TokenKind.LParen then
          val s2 = s1.advance
          val (s3, params) =
            parseDelimited(s2, TokenKind.RParen, ")", reporter)((st, r) => parseDataOfItem(st, r))
          val (s4, _) = expect(s3, TokenKind.RParen, "`)`", reporter)
          (s4, params)
        else
          // Single-item form `of T` — just a TypeExpr; no need to
          // disambiguate OfValueParam (which only appears inside the
          // paren list).
          val (s2, ty) = parseTypeExpr(s1, reporter)
          (s2, List(ty))
      case (s1, None) => (s1, Nil)

  // Inside an `of (...)` clause, each item is one of:
  //   - OfValueParam: `name : TypeExpr (= default)?` — lowered to FnParam.
  //   - LiteralExpr: an Int / Float / String / Char / Bool literal
  //     used as a compile-time argument (e.g. `Bounded of (N, 0, 10)`).
  //   - TypeExpr: any of the type forms (incl. NamedAlias `N is C`).
  private def parseDataOfItem(s: ParserState, reporter: Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LowerIdent && s.peek(1).kind == TokenKind.Colon then
      val (s1, nameTok) = consume(s)
      val (s2, _) = expect(s1, TokenKind.Colon, "`:`", reporter)
      val (s3, ty) = parseTypeExpr(s2, reporter)
      val (s4, default) = accept(s3, TokenKind.Eq) match
        case (st, Some(_)) =>
          val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
        case (st, None) => (st, None)
      (s4, Trees.FnParam(nameTok.lexeme, ty, default, span(nameTok.span, s4.current.span)))
    else s.current.kind match
      case TokenKind.IntLit | TokenKind.FloatLit | TokenKind.StringLit
         | TokenKind.CharLit | TokenKind.KwTrue | TokenKind.KwFalse =>
        parseAtomExpr(s, reporter)
      case _ =>
        parseTypeExpr(s, reporter)

  // Internal accumulator for data-body parsing: variants and props are
  // partitioned at dispatch time so consumers never need to re-classify.
  private final case class DataBodyAcc(
      variants: List[Trees.DataVariant],
      props: List[Trees.PropDecl]
  )

  // Data bodies admit `DataVariant`s and `PropDecl`s. The parser
  // partitions them into separate lists at dispatch time so consumers
  // never need to re-classify.
  private def parseDataBody(
      s0: ParserState,
      reporter: Reporter
  ): (ParserState, List[Trees.DataVariant], List[Trees.PropDecl]) =
    val (s1, acc) = parseIndentedBody(s0, reporter, DataBodyAcc(Nil, Nil)) { (state, acc) =>
      state.current.kind match
        case TokenKind.UpperIdent =>
          val (st1, v) = parseDataVariant(state, reporter)
          (st1, acc.copy(variants = v :: acc.variants))
        case TokenKind.KwProp =>
          val (st1, p) = parsePropDecl(state, reporter)
          (st1, acc.copy(props = p :: acc.props))
        case _ =>
          reporter.error(
            "P013",
            state.current.span,
            s"expected a variant name or `prop`, got ${state.current.kind}${describeLexeme(state.current)}",
            Some("data bodies hold `UpperIdent` variants and `prop` declarations")
          )
          (syncToAnchors(state), acc)
    }
    (s1, acc.variants.reverse, acc.props.reverse)

  private def parseDataVariant(s0: ParserState, reporter: Reporter): Parsed[Trees.DataVariant] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, "variant name", reporter)
    val (s2, fields) =
      if s1.current.kind == TokenKind.LParen then parseFieldList(s1, reporter)
      else (s1, Nil)
    (s2, Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s2.current.span)))

  private def parseFieldList(s0: ParserState, reporter: Reporter): Parsed[List[Trees.FieldDecl]] =
    val (s1, _) = expect(s0, TokenKind.LParen, "`(`", reporter)
    val (s2, fields) =
      parseDelimitedTyped(s1, TokenKind.RParen, ")", reporter)((st, r) => parseFieldDecl(st, r))
    val (s3, _) = expect(s2, TokenKind.RParen, "`)`", reporter)
    (s3, fields)

  private def parseFieldDecl(s0: ParserState, reporter: Reporter): Parsed[Trees.FieldDecl] =
    val (s1, nameTok) = expect(s0, TokenKind.LowerIdent, "field name", reporter)
    val (s2, _) = expect(s1, TokenKind.Colon, "`:`", reporter)
    val (s3, ty) = parseTypeExpr(s2, reporter)
    val (s4, default) = accept(s3, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, e) = parseExpr(st, reporter); (st1, Some(e))
      case (st, None) => (st, None)
    (s4, Trees.FieldDecl(nameTok.lexeme, ty, default, span(nameTok.span, s4.current.span)))

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
  private def parseCapDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwCap, "`cap`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, "capability name", reporter)
    val (s3, valueParams) =
      if s2.current.kind == TokenKind.LParen then parseFnParamList(s2, reporter)
      else (s2, Nil)
    val (s4, ofParams) = parseOptionalDataOfClause(s3, reporter)
    val (s5, extendsList) = accept(s4, TokenKind.KwExtends) match
      case (st, Some(_)) => parseCapBoundChain(st, reporter)
      case (st, None)    => (st, Nil)
    val (s6, body) = accept(s5, TokenKind.Colon) match
      case (st, Some(_)) => parseCapBody(st, reporter)
      case (st, None)    => (st, Nil)
    (s6, Trees.CapDecl(nameTok.lexeme, valueParams, ofParams, extendsList, body, span(startTok.span, s6.current.span)))

  private def parseCapBody(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, members) = parseIndentedBody(s0, reporter, List.empty[Tree]) { (state, acc) =>
      state.current.kind match
        case TokenKind.KwFn   =>
          val (st1, m) = parseInstanceMethod(state, reporter); (st1, m :: acc)
        case TokenKind.KwSelf =>
          val (st1, m) = parseStaticMethod(state, reporter); (st1, m :: acc)
        case TokenKind.KwProp =>
          val (st1, p) = parsePropDecl(state, reporter); (st1, p :: acc)
        case _ =>
          reporter.error(
            "P014",
            state.current.span,
            s"expected `fn`, `Self.fn`, or `prop`, got ${state.current.kind}${describeLexeme(state.current)}",
            Some("cap members are instance methods (`fn`), static methods (`Self.fn`), or `prop` declarations")
          )
          (syncToAnchors(state), acc)
    }
    (s1, members.reverse)

  private def parseInstanceMethod(s0: ParserState, reporter: Reporter): Parsed[Trees.InstanceMethod] =
    val (s1, startTok) = expect(s0, TokenKind.KwFn, "`fn`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, "method name", reporter)
    val (s3, typeParamsHint) = parseOptionalTypeParamsHint(s2, reporter)
    val (s4, params) =
      if s3.current.kind == TokenKind.LParen then parseFnParamList(s3, reporter)
      else (s3, Nil)
    val (s5, returnType) = accept(s4, TokenKind.Arrow) match
      case (st, Some(_)) => parseTypeExpr(st, reporter)
      case (st, None)    => (st, Trees.UnitType(st.current.span))
    val (s6, withClause) = parseOptionalWithClause(s5, reporter)
    val (s7, body) = accept(s6, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, b) = parseFnBody(st, reporter); (st1, Some(b))
      case (st, None) => (st, None)
    val tree = Trees.InstanceMethod(
      name = nameTok.lexeme,
      typeParamsHint = typeParamsHint,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, s7.current.span)
    )
    (s7, tree)

  private def parseStaticMethod(s0: ParserState, reporter: Reporter): Parsed[Trees.StaticMethod] =
    val (s1, startTok) = expect(s0, TokenKind.KwSelf, "`Self`", reporter)
    val (s2, _) = expect(s1, TokenKind.Dot, "`.`", reporter)
    val (s3, _) = expect(s2, TokenKind.KwFn, "`fn`", reporter)
    val (s4, nameTok) = expect(s3, TokenKind.LowerIdent, "static method name", reporter)
    val (s5, typeParamsHint) = parseOptionalTypeParamsHint(s4, reporter)
    val (s6, params) =
      if s5.current.kind == TokenKind.LParen then parseFnParamList(s5, reporter)
      else (s5, Nil)
    val (s7, returnType) = accept(s6, TokenKind.Arrow) match
      case (st, Some(_)) => parseTypeExpr(st, reporter)
      case (st, None)    => (st, Trees.UnitType(st.current.span))
    val (s8, withClause) = parseOptionalWithClause(s7, reporter)
    val (s9, body) = accept(s8, TokenKind.Eq) match
      case (st, Some(_)) =>
        val (st1, b) = parseFnBody(st, reporter); (st1, Some(b))
      case (st, None) => (st, None)
    val tree = Trees.StaticMethod(
      name = nameTok.lexeme,
      typeParamsHint = typeParamsHint,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, s9.current.span)
    )
    (s9, tree)

  // TypeParamsHint ::= "<" UPPER_IDENT ("," UPPER_IDENT)* ","? ">"
  // Per Rule 4.1, Fixed type params are normally introduced at first
  // use; the explicit hint is only required when a method's type
  // parameters can't be inferred from its parameters or return type
  // (e.g. a fold whose result type R appears only in callbacks).
  private def parseOptionalTypeParamsHint(s0: ParserState, reporter: Reporter): Parsed[List[String]] =
    if s0.current.kind != TokenKind.Lt then (s0, Nil)
    else
      val s1 = s0.advance  // `<`
      val (s2, params) = parseDelimitedTyped(s1, TokenKind.Gt, ">", reporter) { (st, r) =>
        val (st1, tok) = expect(st, TokenKind.UpperIdent, "type-param name", r)
        (st1, tok.lexeme)
      }
      val (s3, _) = expect(s2, TokenKind.Gt, "`>`", reporter)
      (s3, params)

  private def parsePropDecl(s0: ParserState, reporter: Reporter): Parsed[Trees.PropDecl] =
    val (s1, startTok) = expect(s0, TokenKind.KwProp, "`prop`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, "prop name", reporter)
    val (s3, _) = expect(s2, TokenKind.Colon, "`:` after prop name", reporter)
    val (s4, body) = parseExpr(s3, reporter)
    (s4, Trees.PropDecl(nameTok.lexeme, body, span(startTok.span, body.span)))

  // ---- TypeAlias ----

  // TypeAlias ::= "type" UPPER_IDENT ValueParams? "=" CapBoundChain
  //   ValueParams ::= "(" FnParam ("," FnParam)* ","? ")"
  // The RHS chain is wrapped in `IsBound(caps)` when there are multiple
  // caps; a single-cap RHS is the cap node itself.
  private def parseTypeAlias(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwType, "`type`", reporter)
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, "type alias name", reporter)
    val (s3, valueParams) =
      if s2.current.kind == TokenKind.LParen then parseFnParamList(s2, reporter)
      else (s2, Nil)
    val (s4, _) = expect(s3, TokenKind.Eq, "`=`", reporter)
    val (s5, caps) = parseCapBoundChain(s4, reporter)
    val rhs =
      if caps.size == 1 then caps.head
      else Trees.IsBound(caps, span(caps.head.span, caps.last.span))
    (s5, Trees.TypeAlias(nameTok.lexeme, valueParams, rhs, span(startTok.span, s5.current.span)))

  // ---- SatisfiesDecl ----

  // SatisfiesDecl ::= TypeName "satisfies" CapBound (":" INDENT SatisfiesItem+ DEDENT)?
  //   TypeName    ::= UPPER_IDENT | LOWER_IDENT          (primitives)
  //   SatisfiesItem ::= ConstructorMapping
  //                  | ImpossibleMapping
  //                  | InstanceMethod | StaticMethod | PropDecl
  //   ConstructorMapping ::= (UPPER_IDENT | "Self") "as" LOWER_IDENT
  //   ImpossibleMapping  ::= "impossible" "as" LOWER_IDENT
  private def parseSatisfiesDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val startSpan = s0.current.span
    val (s1, typeName) = s0.current.kind match
      case TokenKind.UpperIdent | TokenKind.LowerIdent =>
        val (st, tok) = consume(s0); (st, tok.lexeme)
      case _ =>
        reporter.error(
          "P015",
          s0.current.span,
          s"expected a type name to satisfy a capability, got ${s0.current.kind}${describeLexeme(s0.current)}",
          Some("`<TypeName> satisfies <CapName>: ...`")
        )
        val (st, tok) = consume(s0); (st, tok.lexeme)
    val (s2, _) = expect(s1, TokenKind.KwSatisfies, "`satisfies`", reporter)
    val (s3, cap) = parseCapBound(s2, reporter)
    val (s4, items) = accept(s3, TokenKind.Colon) match
      case (st, Some(_)) => parseSatisfiesBody(st, reporter)
      case (st, None)    => (st, Nil)
    (s4, Trees.SatisfiesDecl(typeName, cap, items, span(startSpan, s4.current.span)))

  private def parseSatisfiesBody(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, items) = parseIndentedBody(s0, reporter, List.empty[Tree]) { (state, acc) =>
      val (state1, item) = parseSatisfiesItem(state, reporter)
      (state1, item :: acc)
    }
    (s1, items.reverse)

  private def parseSatisfiesItem(s: ParserState, reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwImpossible =>
      val (s1, startTok) = consume(s)
      val (s2, _) = expect(s1, TokenKind.KwAs, "`as`", reporter)
      val (s3, ctorTok) = expect(s2, TokenKind.LowerIdent, "cap constructor name", reporter)
      (s3, Trees.ImpossibleMapping(ctorTok.lexeme, span(startTok.span, ctorTok.span)))
    case TokenKind.KwSelf if s.peek(1).kind == TokenKind.KwAs =>
      // `Self as ctor` — identity-shape mapping for primitives that
      // are themselves the cap's value (`u64 satisfies Optional of
      // Self: Self as some`).
      val (s1, startTok) = consume(s)
      val (s2, _) = expect(s1, TokenKind.KwAs, "`as`", reporter)
      val (s3, ctorTok) = expect(s2, TokenKind.LowerIdent, "cap constructor name", reporter)
      (s3, Trees.ConstructorMapping("Self", ctorTok.lexeme, span(startTok.span, ctorTok.span)))
    case TokenKind.UpperIdent =>
      val (s1, variantTok) = consume(s)
      val (s2, _) = expect(s1, TokenKind.KwAs, "`as`", reporter)
      val (s3, ctorTok) = expect(s2, TokenKind.LowerIdent, "cap constructor name", reporter)
      (s3, Trees.ConstructorMapping(variantTok.lexeme, ctorTok.lexeme, span(variantTok.span, ctorTok.span)))
    case TokenKind.KwFn   =>
      val (s1, m) = parseInstanceMethod(s, reporter); (s1, m)
    case TokenKind.KwSelf =>
      val (s1, m) = parseStaticMethod(s, reporter); (s1, m)
    case TokenKind.KwProp =>
      val (s1, p) = parsePropDecl(s, reporter); (s1, p)
    case _ =>
      reporter.error(
        "P016",
        s.current.span,
        s"expected a satisfies item, got ${s.current.kind}${describeLexeme(s.current)}",
        Some("items are `Variant as ctor`, `impossible as ctor`, `fn ...`, `Self.fn ...`, or `prop ...`")
      )
      val tok = s.current.span
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(tok, s1.current.span)))

  // ---- EffectDecl ----

  // EffectDecl ::= "linear"? "effect" UPPER_IDENT OfClause? (":" INDENT EffectMember+ DEDENT)?
  //   EffectMember ::= "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause?
  // (Effect ops never have bodies; their semantics are supplied by handlers.)
  private def parseEffectDecl(s0: ParserState, reporter: Reporter): Parsed[Tree] =
    val startSpan = s0.current.span
    val (s1, linOpt) = accept(s0, TokenKind.KwLinear)
    val isLinear = linOpt.isDefined
    val (s2, _) = expect(s1, TokenKind.KwEffect, "`effect`", reporter)
    val (s3, nameTok) = expect(s2, TokenKind.UpperIdent, "effect name", reporter)
    val (s4, ofParams) = parseOptionalDataOfClause(s3, reporter)
    val (s5, members) = accept(s4, TokenKind.Colon) match
      case (st, Some(_)) => parseEffectMembers(st, reporter)
      case (st, None)    => (st, Nil)
    (s5, Trees.EffectDecl(isLinear, nameTok.lexeme, ofParams, members, span(startSpan, s5.current.span)))

  private def parseEffectMembers(s0: ParserState, reporter: Reporter): Parsed[List[Tree]] =
    val (s1, members) = parseIndentedBody(s0, reporter, List.empty[Tree]) { (state, acc) =>
      if state.current.kind == TokenKind.KwFn then
        val (st1, m) = parseInstanceMethod(state, reporter); (st1, m :: acc)
      else
        reporter.error(
          "P017",
          state.current.span,
          s"expected an effect op (`fn name(...) -> T`), got ${state.current.kind}${describeLexeme(state.current)}",
          Some("each effect op is declared like an instance method, without a body")
        )
        (syncToAnchors(state), acc)
    }
    (s1, members.reverse)

  // ---- Stub productions still pending ----

  private def parseModDecl(s: ParserState, reporter: Reporter): Parsed[Tree] =
    unsupported(s, "`mod` declaration", reporter)

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
