package fixed.parsing

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile, Span}

/** Hand-written recursive-descent parser. Produces the untyped AST.
  *
  * Pull model: tokens are read from the [[Scanner]] one at a time and
  * buffered in a small lookahead window so productions that need >1 token
  * of lookahead (lambda-vs-tuple, RefinementCall vs FnCall, etc.) can peek.
  *
  * This implementation is scoped to the productions exercised by
  * `examples/01_basics.fixed`. Productions used only by examples 02–11
  * (`match`, `handle`, `cap`/`data`/`effect`/`satisfies` declarations,
  * `prop`, `forall`, struct literals, …) are stubbed with an explicit
  * "not yet implemented" error and will be filled in for milestones M4–M5.
  */
final class Parser(scanner: Scanner, reporter: Reporter):

  // ---- Token cursor / lookahead ----

  private val buf = scala.collection.mutable.Queue.empty[Token]

  private def fillTo(n: Int): Unit =
    while buf.length <= n do
      buf.enqueue(scanner.nextToken())

  /** The current token (next un-consumed token). Always available. */
  def current: Token =
    fillTo(0)
    buf.head

  /** Peek the token `n` ahead of `current` (n=0 means current). */
  def peek(n: Int): Token =
    fillTo(n)
    buf(n)

  /** Consume and return `current`. */
  private def consume(): Token =
    fillTo(0)
    buf.dequeue()

  /** If `current.kind == kind`, consume and return Some; else None. */
  private def accept(kind: TokenKind): Option[Token] =
    if current.kind == kind then Some(consume())
    else None

  /** Consume `current` if it matches `kind`; otherwise emit an error and
    * return a synthetic token with span at the current position so the
    * caller can continue building a tree.
    */
  private def expect(kind: TokenKind, what: String): Token =
    if current.kind == kind then consume()
    else
      reporter.error(
        "P001",
        current.span,
        s"expected $what (token ${kind}), got ${current.kind}${describeLexeme(current)}"
      )
      Token(kind, Span(current.span.start, current.span.start))

  private def describeLexeme(t: Token): String =
    if t.lexeme.isEmpty then "" else s" `${t.lexeme}`"

  // ---- Helpers ----

  /** Skip zero or more NEWLINEs at the current position. */
  private def skipNewlines(): Unit =
    while current.kind == TokenKind.Newline do
      val _ = consume()

  /** Combine two spans. */
  private def span(start: Span, end: Span): Span =
    Span(start.start, end.end)

  // ---- Recovery (Phase 2.1 M3) ----

  /** Stack of anchor frames, one per active production-with-recovery.
    * Each production pushes its own anchor set on entry and pops on exit;
    * `syncToAnchors` uses only the innermost frame so each production's
    * recovery is local. Outer recovery is the outer caller's job — it
    * has its own `withAnchors`/`syncToAnchors` pair. */
  private var activeAnchors: List[Set[TokenKind]] = Nil

  /** Push `anchors` onto the active stack for the duration of `body`.
    * Always pops, even on exceptional return. */
  private def withAnchors[T](anchors: Set[TokenKind])(body: => T): T =
    activeAnchors = anchors :: activeAnchors
    try body
    finally activeAnchors = activeAnchors.tail

  /** Skip tokens until `current.kind` is in the innermost active anchor
    * frame, or EOF. Does NOT consume the anchor — the caller decides
    * whether to resume or yield to its own caller. No-op when the active
    * stack is empty (recovery is only meaningful inside a `withAnchors`
    * scope). */
  private def syncToAnchors(): Unit =
    activeAnchors.headOption.foreach { inner =>
      while current.kind != TokenKind.Eof && !inner.contains(current.kind) do
        val _ = consume()
    }

  // Anchor sets, named by the context that owns them. Anchors are
  // *reached but not consumed*; productions decide whether to recover or
  // bubble up based on which anchor matched.
  private object Anchors:
    /** Top-level item starts: every keyword that begins a top-level
      * production, plus UpperIdent (head of a `T satisfies …` decl) and
      * EOF. NEWLINE is NOT included — it's a separator, not a starter. */
    val topLevel: Set[TokenKind] = Set(
      TokenKind.KwUse, TokenKind.KwFn, TokenKind.KwCap, TokenKind.KwData,
      TokenKind.KwEffect, TokenKind.KwLinear, TokenKind.KwType,
      TokenKind.KwMod, TokenKind.KwPub, TokenKind.UpperIdent,
      TokenKind.Eof
    )

    /** Block-body statement boundary: a NEWLINE between statements or a
      * DEDENT closing the block. EOF is implicitly an anchor for every
      * frame (see `syncToAnchors`). */
    val blockBody: Set[TokenKind] = Set(TokenKind.Newline, TokenKind.Dedent)

    /** Type-expression boundary: anything that can plausibly *follow* a
      * type. Per spec/syntax_grammar.ebnf Appendix A.1.2. Arrow is
      * included even though it's part of `T -> U` — when a type atom
      * fails partway through, sync-to-arrow lets `parseArrowType` see
      * the arrow and continue building. */
    val typeExpr: Set[TokenKind] = Set(
      TokenKind.Comma, TokenKind.RParen, TokenKind.RBracket,
      TokenKind.Newline, TokenKind.Dedent,
      TokenKind.Eq, TokenKind.Arrow, TokenKind.KwWith
    )
  end Anchors

  // ---- Compilation unit ----

  def parseCompilationUnit(): Tree =
    val startSpan = current.span
    skipNewlines()
    val items = scala.collection.mutable.ListBuffer.empty[Tree]
    withAnchors(Anchors.topLevel) {
      while current.kind != TokenKind.Eof do
        items += parseTopItem()
        // Items at top level are separated by NEWLINE; allow blank lines.
        skipNewlines()
    }
    val endSpan = current.span
    Trees.CompilationUnit(items.toList, span(startSpan, endSpan))

  // ---- Top items ----

  private def parseTopItem(): Tree = current.kind match
    case TokenKind.KwUse       => parseUseDecl()
    case TokenKind.KwFn        => parseFnDecl()
    case TokenKind.KwCap       => parseCapDecl()
    case TokenKind.KwData      => parseDataDecl()
    case TokenKind.KwEffect    => parseEffectDecl()
    case TokenKind.KwLinear    => parseEffectDecl()
    case TokenKind.KwType      => parseTypeAlias()
    case TokenKind.KwMod       => parseModDecl()
    case TokenKind.KwPub       => unsupported("`pub` modifier")
    case TokenKind.UpperIdent  => parseSatisfiesDecl()
    case _                      =>
      // Unknown token at top level. Emit a diagnostic, then synchronise
      // to the next decl introducer (or EOF). This collapses runs of
      // junk into a single Error item rather than one per stray token.
      val startSpan = current.span
      reporter.error(
        "P002",
        startSpan,
        s"unexpected token at top level: ${current.kind}${describeLexeme(current)}",
        Some("expected `use`, `fn`, `cap`, `data`, `effect`, `type`, `mod`, or `<TypeName> satisfies …`")
      )
      // syncToAnchors stops at the next anchor (top-level keyword or EOF)
      // without consuming it, so the outer loop dispatches that token to
      // its proper handler.
      syncToAnchors()
      Trees.Error(Nil, span(startSpan, current.span))

  private def unsupported(what: String): Tree =
    reporter.error(
      "P099",
      current.span,
      s"$what is not yet implemented in the Phase-2 parser (Phase-2.M3 scope)",
      Some("file an issue or extend Parsers.scala")
    )
    val tok = consume()
    Trees.Error(Nil, tok.span)

  // ---- UseDecl ----

  private def parseUseDecl(): Tree =
    val startTok = expect(TokenKind.KwUse, "`use`")
    val pathBuf = scala.collection.mutable.ListBuffer.empty[String]
    pathBuf += parseIdentTextEither()
    while current.kind == TokenKind.Dot do
      val _ = consume()
      pathBuf += parseIdentTextEither()
    val satisfies = accept(TokenKind.KwSatisfies).map { _ =>
      parseCapRef()
    }
    val endSpan = current.span
    Trees.UseDecl(pathBuf.toList, satisfies, span(startTok.span, endSpan))

  private def parseIdentTextEither(): String =
    current.kind match
      case TokenKind.LowerIdent | TokenKind.UpperIdent =>
        consume().lexeme
      case _ =>
        reporter.error(
          "P003",
          current.span,
          s"expected an identifier, got ${current.kind}${describeLexeme(current)}"
        )
        "<error>"

  // ---- FnDecl (top-level or nested) ----

  private def parseFnDecl(): Tree =
    val startTok = expect(TokenKind.KwFn, "`fn`")
    val nameTok = expect(TokenKind.LowerIdent, "function name")
    // TypeParamsHint <T1, T2, ...>: deferred — example 01 doesn't use the
    // explicit hint; type params are introduced via Rule 4.1.
    val params = parseFnParamList()
    val returnType =
      if accept(TokenKind.Arrow).isDefined then parseTypeExpr()
      else Trees.UnitType(current.span)
    val withClause = parseOptionalWithClause()
    val body =
      if accept(TokenKind.Eq).isDefined then Some(parseFnBody())
      else None
    Trees.FnDecl(
      name = nameTok.lexeme,
      typeParamsHint = Nil,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, current.span)
    )

  private def parseFnParamList(): List[Trees.FnParam] =
    val _ = expect(TokenKind.LParen, "`(`")
    val params = scala.collection.mutable.ListBuffer.empty[Trees.FnParam]
    if current.kind != TokenKind.RParen then
      params += parseFnParam()
      while current.kind == TokenKind.Comma do
        val _ = consume()
        if current.kind == TokenKind.RParen then ()  // trailing comma
        else params += parseFnParam()
    val _ = expect(TokenKind.RParen, "`)`")
    params.toList

  private def parseFnParam(): Trees.FnParam =
    val nameTok = expect(TokenKind.LowerIdent, "parameter name")
    val _ = expect(TokenKind.Colon, "`:`")
    val ty = parseTypeExpr()
    val default =
      if accept(TokenKind.Eq).isDefined then Some(parseExpr())
      else None
    Trees.FnParam(nameTok.lexeme, ty, default, span(nameTok.span, current.span))

  private def parseOptionalWithClause(): Option[Tree] =
    if accept(TokenKind.KwWith).isDefined then
      val row = parseEffectRow()
      Some(Trees.WithClause(row, row.span))
    else None

  private def parseEffectRow(): Tree =
    val first = parseEffectBound()
    if current.kind != TokenKind.Plus then first
    else
      val effects = scala.collection.mutable.ListBuffer[Tree](first)
      while accept(TokenKind.Plus).isDefined do
        effects += parseEffectBound()
      Trees.EffectRow(effects.toList, span(first.span, effects.last.span))

  private def parseEffectBound(): Tree =
    val nameTok = expect(TokenKind.UpperIdent, "effect name")
    val ofArg =
      if accept(TokenKind.KwOf).isDefined then Some(parseOfArg())
      else None
    Trees.EffectBound(nameTok.lexeme, ofArg, span(nameTok.span, current.span))

  // The body of a fn is either:
  //  - a single expression on the same line as `=`, or
  //  - an INDENTed block.
  private def parseFnBody(): Tree =
    if current.kind == TokenKind.Indent then parseBlock()
    else parseExpr()

  // ---- Block ----

  /** Parse `INDENT stmt (NEWLINE stmt)* DEDENT` as a Block. Recovery:
    * if a statement leaves the parser on a non-anchor token (i.e. it
    * didn't reach end-of-line), sync to NEWLINE/DEDENT/EOF and wrap
    * the partial statement in a `Trees.Error` so subsequent statements
    * still parse cleanly. */
  private def parseBlock(): Tree =
    val startTok = expect(TokenKind.Indent, "INDENT")
    val stmts = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent && current.kind != TokenKind.Eof do
        val stmtStart = current.span
        val stmt = parseStatement()
        if Anchors.blockBody.contains(current.kind) || current.kind == TokenKind.Eof then
          stmts += stmt
        else
          // Statement is malformed — didn't reach a statement boundary.
          // Emit a diagnostic, sync to the next boundary, and wrap
          // whatever we got in Error so subsequent statements are
          // unaffected.
          reporter.error(
            "P011",
            current.span,
            s"unexpected ${current.kind}${describeLexeme(current)} after statement; expected end of line",
            Some("split this expression onto its own line")
          )
          syncToAnchors()
          stmts += Trees.Error(List(stmt), span(stmtStart, current.span))
        skipNewlines()
    }
    val endTok = expect(TokenKind.Dedent, "DEDENT")
    Trees.Block(stmts.toList, span(startTok.span, endTok.span))

  /** A statement-level expression: in Fixed everything is an expression,
    * but at the top of a block we allow `let` and other Expr alternatives. */
  private def parseStatement(): Tree = parseExpr()

  // ---- Type expressions ----

  // TypeExpr ::= ArrowType
  // ArrowType ::= ArrowLhs ("->" ArrowType WithClause?)?
  //
  // Pushes a type-expression anchor frame so any inner parseTypeAtom
  // failure can sync to a token that plausibly follows a type.
  def parseTypeExpr(): Tree = withAnchors(Anchors.typeExpr) { parseArrowType() }

  private def parseArrowType(): Tree =
    val lhs = parseArrowLhs()
    if accept(TokenKind.Arrow).isDefined then
      val rhs = parseArrowType()
      val withClause = parseOptionalWithClause()
      Trees.ArrowType(lhs, rhs, withClause, span(lhs.span, current.span))
    else lhs

  private def parseArrowLhs(): Tree =
    if current.kind == TokenKind.LParen then
      // Either: `(T1, T2, ...)` → TupleArrowLhs/UnitType
      //         `(T)` → just T (parenthesised)
      //         `(T) of OfArg` → ParenTypeApp
      val startSpan = current.span
      val _ = consume()  // consume `(`
      if accept(TokenKind.RParen).isDefined then
        // `()` — unit type
        // Possibly followed by `of` — but unit-of-X isn't meaningful.
        // We just produce UnitType.
        Trees.UnitType(span(startSpan, current.span))
      else
        val first = parseTypeExpr()
        if accept(TokenKind.Comma).isDefined then
          // Multi-element tuple-arrow LHS. `first` is already past the
          // first comma; `parseDelimited` handles the tail with recovery.
          val tail = parseDelimited(TokenKind.RParen, ")")(() => parseTypeExpr())
          val _ = expect(TokenKind.RParen, "`)`")
          Trees.TupleArrowLhs(first :: tail, span(startSpan, current.span))
        else
          val _ = expect(TokenKind.RParen, "`)`")
          if accept(TokenKind.KwOf).isDefined then
            val arg = parseOfArg()
            Trees.ParenTypeApp(first, arg, span(startSpan, current.span))
          else first
    else parseTypeAtom()

  private def parseTypeAtom(): Tree = current.kind match
    case TokenKind.Bang =>
      val tok = consume()
      Trees.NeverType(tok.span)
    case TokenKind.KwSelf =>
      val tok = consume()
      val ofArg = if accept(TokenKind.KwOf).isDefined then Some(parseOfArg()) else None
      Trees.SelfType(ofArg, span(tok.span, current.span))
    case TokenKind.KwIs =>
      parseIsBound()
    case TokenKind.KwCap =>
      parseCapType()
    case TokenKind.UpperIdent =>
      // Could be NamedAlias (`N is C`), TypeRef (`T`, `T of A`).
      val nameTok = consume()
      if current.kind == TokenKind.KwIs then
        val _ = consume()
        val caps = parseCapBoundChain()
        Trees.NamedAlias(nameTok.lexeme, caps, span(nameTok.span, current.span))
      else
        val ofArg = if accept(TokenKind.KwOf).isDefined then Some(parseOfArg()) else None
        Trees.TypeRef(nameTok.lexeme, ofArg, span(nameTok.span, current.span))
    case TokenKind.LowerIdent =>
      val tok = consume()
      Trees.PrimitiveType(tok.lexeme, tok.span)
    case _ =>
      val startSpan = current.span
      reporter.error(
        "P004",
        startSpan,
        s"expected a type expression, got ${current.kind}${describeLexeme(current)}"
      )
      // Sync to a type-expression boundary so the surrounding parse
      // (e.g. `fn f(x: @ @ @) -> u64 = …`) keeps its shape rather than
      // cascading expect()-failures through the rest of the file.
      syncToAnchors()
      Trees.Error(Nil, span(startSpan, current.span))

  private def parseIsBound(): Tree =
    val startTok = expect(TokenKind.KwIs, "`is`")
    val caps = parseCapBoundChain()
    Trees.IsBound(caps, span(startTok.span, current.span))

  private def parseCapBoundChain(): List[Tree] =
    val caps = scala.collection.mutable.ListBuffer.empty[Tree]
    caps += parseCapBound()
    while accept(TokenKind.Plus).isDefined do
      caps += parseCapBound()
    caps.toList

  private def parseCapBound(): Tree = current.kind match
    case TokenKind.UpperIdent => parseCapRef()
    case TokenKind.LowerIdent =>
      val nameTok = consume()
      if current.kind == TokenKind.LParen then
        // RefinementCall.
        val args = parseArgList()
        Trees.RefinementCall(nameTok.lexeme, args, span(nameTok.span, current.span))
      else
        Trees.PrimitiveType(nameTok.lexeme, nameTok.span)
    case _ =>
      reporter.error(
        "P005",
        current.span,
        s"expected a capability bound, got ${current.kind}${describeLexeme(current)}"
      )
      Trees.Error(Nil, consume().span)

  private def parseCapRef(): Tree =
    val nameTok = expect(TokenKind.UpperIdent, "capability name")
    val ofArg = if accept(TokenKind.KwOf).isDefined then Some(parseOfArg()) else None
    Trees.CapRef(nameTok.lexeme, ofArg, span(nameTok.span, current.span))

  // OfArg can be: OfValueParam (decl-site) | LiteralExpr | TypeExpr.
  // For example 01 we only see OfArg = TypeExpr (e.g., `Folding of i64`)
  // and tuple-of-TypeExpr (e.g., `BiFunctor of (A, B)`). We keep the parser
  // permissive: parens enclose either a single TypeExpr or a comma-separated
  // list of OfArgs.
  private def parseOfArg(): Tree =
    if current.kind == TokenKind.LParen then
      val startSpan = current.span
      val _ = consume()
      val first = parseTypeExpr()
      if accept(TokenKind.Comma).isDefined then
        val tail = parseDelimited(TokenKind.RParen, ")")(() => parseTypeExpr())
        val _ = expect(TokenKind.RParen, "`)`")
        Trees.TupleArrowLhs(first :: tail, span(startSpan, current.span))
      else
        val _ = expect(TokenKind.RParen, "`)`")
        first
    else parseTypeExpr()

  private def parseCapType(): Tree =
    val startTok = expect(TokenKind.KwCap, "`cap`")
    val spec =
      if accept(TokenKind.KwOf).isDefined then
        Some(Trees.CapReturnSpec.OfArg(parseOfArg()))
      else if accept(TokenKind.KwExtends).isDefined then
        Some(Trees.CapReturnSpec.Extends(parseCapBoundChain()))
      else None
    Trees.CapType(spec, span(startTok.span, current.span))

  // ---- Expressions ----

  /** Top-level expression. */
  def parseExpr(): Tree = current.kind match
    case TokenKind.KwLet => parseLetExpr()
    case _ => parsePipeExpr()

  private def parseLetExpr(): Tree =
    val startTok = expect(TokenKind.KwLet, "`let`")
    val pat = parsePattern()
    val _ = expect(TokenKind.Eq, "`=`")
    val init = parsePipeExpr()
    Trees.LetExpr(pat, init, span(startTok.span, init.span))

  private def parsePipeExpr(): Tree =
    var lhs = parseOrExpr()
    while accept(TokenKind.PipeForward).isDefined do
      val rhs = parseOrExpr()
      lhs = Trees.Pipe(lhs, rhs, span(lhs.span, rhs.span))
    lhs

  private def parseOrExpr(): Tree =
    var lhs = parseAndExpr()
    while accept(TokenKind.OrOr).isDefined do
      val rhs = parseAndExpr()
      lhs = Trees.BinOp("||", lhs, rhs, span(lhs.span, rhs.span))
    lhs

  private def parseAndExpr(): Tree =
    var lhs = parseCmpExpr()
    while accept(TokenKind.AndAnd).isDefined do
      val rhs = parseCmpExpr()
      lhs = Trees.BinOp("&&", lhs, rhs, span(lhs.span, rhs.span))
    lhs

  private def parseCmpExpr(): Tree =
    val lhs = parseAddExpr()
    val cmpOp = current.kind match
      case TokenKind.EqEq => Some("==")
      case TokenKind.Neq  => Some("!=")
      case TokenKind.Lt   => Some("<")
      case TokenKind.Le   => Some("<=")
      case TokenKind.Gt   => Some(">")
      case TokenKind.Ge   => Some(">=")
      case _              => None
    cmpOp match
      case Some(op) =>
        val _ = consume()
        val rhs = parseAddExpr()
        Trees.BinOp(op, lhs, rhs, span(lhs.span, rhs.span))
      case None => lhs

  private def parseAddExpr(): Tree =
    var lhs = parseMulExpr()
    var continue = true
    while continue do
      val op = current.kind match
        case TokenKind.Plus  => Some("+")
        case TokenKind.Minus => Some("-")
        case _               => None
      op match
        case Some(o) =>
          val _ = consume()
          val rhs = parseMulExpr()
          lhs = Trees.BinOp(o, lhs, rhs, span(lhs.span, rhs.span))
        case None => continue = false
    lhs

  private def parseMulExpr(): Tree =
    var lhs = parseUnaryExpr()
    var continue = true
    while continue do
      val op = current.kind match
        case TokenKind.Star    => Some("*")
        case TokenKind.Slash   => Some("/")
        case TokenKind.Percent => Some("%")
        case _                 => None
      op match
        case Some(o) =>
          val _ = consume()
          val rhs = parseUnaryExpr()
          lhs = Trees.BinOp(o, lhs, rhs, span(lhs.span, rhs.span))
        case None => continue = false
    lhs

  private def parseUnaryExpr(): Tree = current.kind match
    case TokenKind.Minus =>
      val tok = consume()
      val operand = parseAppExpr()
      Trees.UnaryOp("-", operand, span(tok.span, operand.span))
    case TokenKind.Bang =>
      val tok = consume()
      val operand = parseAppExpr()
      Trees.UnaryOp("!", operand, span(tok.span, operand.span))
    case _ => parseAppExpr()

  private def parseAppExpr(): Tree =
    var receiver = parseAtomExpr()
    var continue = true
    while continue do
      current.kind match
        case TokenKind.LParen =>
          // Function application: `f(args)`.
          val args = parseArgList()
          receiver = receiver match
            case Trees.Ident(_, sp) =>
              // Bare ident applied — could be FnCall.
              Trees.Apply(receiver, args, span(sp, current.span))
            case other =>
              Trees.Apply(other, args, span(other.span, current.span))
        case TokenKind.Dot =>
          // Method call or field access.
          val _ = consume()
          val nameTok = current.kind match
            case TokenKind.LowerIdent | TokenKind.UpperIdent => consume()
            case _ =>
              reporter.error(
                "P006",
                current.span,
                s"expected method or field name after `.`, got ${current.kind}${describeLexeme(current)}"
              )
              consume()
          val (args, hasArgList) =
            if current.kind == TokenKind.LParen then
              (parseArgList(), true)
            else (Nil, false)
          receiver = receiver match
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) && hasArgList =>
              // `T.member(args)` — static call.
              Trees.StaticCall(qual, nameTok.lexeme, args, hasArgList, span(qspan, current.span))
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) && !hasArgList =>
              // `T.Variant` — bare data-variant or static-method-no-args.
              // Represent as StaticCall with empty args; the typer disambiguates.
              Trees.StaticCall(qual, nameTok.lexeme, Nil, hasArgList = false, span(qspan, current.span))
            case _ =>
              Trees.MethodCall(receiver, nameTok.lexeme, args, hasArgList, span(receiver.span, current.span))
        case _ => continue = false
    receiver

  private def parseArgList(): List[Tree] =
    val _ = expect(TokenKind.LParen, "`(`")
    val args = parseDelimited(TokenKind.RParen, ")")(() => parseExpr())
    val _ = expect(TokenKind.RParen, "`)`")
    args

  /** Parse a comma-separated list of items up to (but not including)
    * `closeKind`. Recovery: if `parseItem` leaves the parser on a token
    * that is neither `,` nor `closeKind`, emit a diagnostic, sync to the
    * next such token, and wrap the partial item in `Trees.Error`.
    * Trailing comma admitted. */
  private def parseDelimited(
      closeKind: TokenKind,
      closeLexeme: String
  )(parseItem: () => Tree): List[Tree] =
    val anchors = Set(TokenKind.Comma, closeKind)
    val items = scala.collection.mutable.ListBuffer.empty[Tree]
    def parseOne(): Unit =
      val itemStart = current.span
      val item = parseItem()
      if anchors.contains(current.kind) || current.kind == TokenKind.Eof then
        items += item
      else
        reporter.error(
          "P012",
          current.span,
          s"unexpected ${current.kind}${describeLexeme(current)} in list; expected `,` or `$closeLexeme`",
          Some(s"add `,` to continue or `$closeLexeme` to close the list")
        )
        syncToAnchors()
        items += Trees.Error(List(item), span(itemStart, current.span))
    withAnchors(anchors) {
      if current.kind != closeKind then
        parseOne()
        while accept(TokenKind.Comma).isDefined do
          if current.kind == closeKind then ()  // trailing comma
          else parseOne()
    }
    items.toList

  private def parseAtomExpr(): Tree = current.kind match
    case TokenKind.IntLit =>
      val tok = consume()
      Trees.IntLit(BigInt(tok.lexeme), tok.span)
    case TokenKind.FloatLit =>
      val tok = consume()
      Trees.FloatLit(BigDecimal(tok.lexeme), tok.span)
    case TokenKind.StringLit =>
      val tok = consume()
      Trees.StringLit(tok.lexeme, tok.span)
    case TokenKind.CharLit =>
      val tok = consume()
      Trees.CharLit(if tok.lexeme.nonEmpty then tok.lexeme.charAt(0) else ' ', tok.span)
    case TokenKind.KwTrue =>
      val tok = consume()
      Trees.BoolLit(true, tok.span)
    case TokenKind.KwFalse =>
      val tok = consume()
      Trees.BoolLit(false, tok.span)
    case TokenKind.KwUnreachable =>
      val tok = consume()
      Trees.Unreachable(tok.span)
    case TokenKind.KwResume =>
      val tok = consume()
      val arg =
        if accept(TokenKind.LParen).isDefined then
          val v = if current.kind == TokenKind.RParen then None else Some(parseExpr())
          val _ = expect(TokenKind.RParen, "`)`")
          v
        else None
      Trees.Resume(arg, span(tok.span, current.span))
    case TokenKind.KwSelf =>
      val tok = consume()
      Trees.Ident("Self", tok.span)
    case TokenKind.KwSelfLower =>
      val tok = consume()
      Trees.Ident("self", tok.span)
    case TokenKind.KwIf      => parseIfExpr()
    case TokenKind.KwLet     => parseLetExpr()
    case TokenKind.KwMatch   => unsupported("`match` expression")
    case TokenKind.KwHandle  => unsupported("`handle` expression")
    case TokenKind.KwDo      => unsupported("`do` expression")
    case TokenKind.KwForall  => unsupported("`forall` quantifier (only valid in prop bodies)")
    case TokenKind.LBracket  => parseListExpr()
    case TokenKind.LParen    => parseParenOrLambdaOrTuple()
    case TokenKind.LowerIdent =>
      val tok = consume()
      Trees.Ident(tok.lexeme, tok.span)
    case TokenKind.UpperIdent =>
      val tok = consume()
      Trees.Ident(tok.lexeme, tok.span)
    case _ =>
      reporter.error(
        "P007",
        current.span,
        s"expected an expression, got ${current.kind}${describeLexeme(current)}"
      )
      Trees.Error(Nil, consume().span)

  private def parseIfExpr(): Tree =
    val startTok = expect(TokenKind.KwIf, "`if`")
    val cond = parseExpr()
    val _ = expect(TokenKind.Colon, "`:` after if condition")
    val thenBranch = parseInlineOrBlockExpr()
    skipNewlines()
    val _ = expect(TokenKind.KwElse, "`else`")
    val _ = expect(TokenKind.Colon, "`:` after `else`")
    val elseBranch = parseInlineOrBlockExpr()
    Trees.IfExpr(cond, thenBranch, elseBranch, span(startTok.span, elseBranch.span))

  /** A branch body that's either a same-line expression or an INDENTed block. */
  private def parseInlineOrBlockExpr(): Tree =
    if current.kind == TokenKind.Indent then parseBlock()
    else parseExpr()

  private def parseListExpr(): Tree =
    val startTok = expect(TokenKind.LBracket, "`[`")
    val elements = parseDelimited(TokenKind.RBracket, "]")(() => parseExpr())
    val endTok = expect(TokenKind.RBracket, "`]`")
    Trees.ListExpr(elements, span(startTok.span, endTok.span))

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
  private def parseParenOrLambdaOrTuple(): Tree =
    if lookaheadIsLambda() then parseLambdaExpr()
    else parseParenExpr()

  /** Find the matching `)` for the `(` at `current` and check whether the
    * next non-NEWLINE token after it is `->`. */
  private def lookaheadIsLambda(): Boolean =
    require(current.kind == TokenKind.LParen)
    var depth = 0
    var i = 0
    var found = -1
    var bail = false
    while !bail do
      val t = peek(i)
      t.kind match
        case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace =>
          depth += 1
        case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace =>
          depth -= 1
          if depth == 0 then
            found = i
            bail = true
        case TokenKind.Eof =>
          bail = true
        case _ => ()
      i += 1
    if found < 0 then false
    else
      // Look at the token immediately after the matching `)`.
      val afterIdx = found + 1
      // Skip a possible trailing newline.
      val nextKind =
        if peek(afterIdx).kind == TokenKind.Newline then peek(afterIdx + 1).kind
        else peek(afterIdx).kind
      nextKind == TokenKind.Arrow

  private def parseLambdaExpr(): Tree =
    val startTok = expect(TokenKind.LParen, "`(`")
    val params = scala.collection.mutable.ListBuffer.empty[Trees.FnParam]
    if current.kind != TokenKind.RParen then
      params += parseLambdaParam()
      while accept(TokenKind.Comma).isDefined do
        if current.kind == TokenKind.RParen then ()
        else params += parseLambdaParam()
    val _ = expect(TokenKind.RParen, "`)`")
    val _ = expect(TokenKind.Arrow, "`->`")
    val body = parseInlineOrBlockExpr()
    Trees.LambdaExpr(params.toList, body, span(startTok.span, body.span))

  /** Lambda params allow optional type annotation and default. The simplest
    * form is just an identifier; a wildcard `_` is also allowed. */
  private def parseLambdaParam(): Trees.FnParam =
    val (name, startSpan) = current.kind match
      case TokenKind.LowerIdent =>
        val tok = consume(); (tok.lexeme, tok.span)
      case TokenKind.Underscore =>
        val tok = consume(); ("_", tok.span)
      case _ =>
        reporter.error(
          "P008",
          current.span,
          s"expected lambda parameter name, got ${current.kind}${describeLexeme(current)}"
        )
        ("<error>", consume().span)
    val typeAnn =
      if accept(TokenKind.Colon).isDefined then parseTypeExpr()
      else Trees.Ident("<inferred>", current.span)
    val default =
      if accept(TokenKind.Eq).isDefined then Some(parseExpr())
      else None
    Trees.FnParam(name, typeAnn, default, span(startSpan, current.span))

  private def parseParenExpr(): Tree =
    val startTok = expect(TokenKind.LParen, "`(`")
    if accept(TokenKind.RParen).isDefined then
      Trees.UnitLit(span(startTok.span, current.span))
    else
      val first = parseExpr()
      if accept(TokenKind.Comma).isDefined then
        // Tuple literal. The tail uses parseDelimited for recovery; we
        // already have `first` before the first comma, so prepend it.
        val tail = parseDelimited(TokenKind.RParen, ")")(() => parseExpr())
        val _ = expect(TokenKind.RParen, "`)`")
        Trees.TupleExpr(first :: tail, span(startTok.span, current.span))
      else
        val _ = expect(TokenKind.RParen, "`)`")
        first

  // ---- Patterns ----

  private def parsePattern(): Tree = current.kind match
    case TokenKind.Underscore =>
      val tok = consume()
      Trees.WildcardPat(tok.span)
    case TokenKind.LowerIdent =>
      val tok = consume()
      Trees.BinderPat(tok.lexeme, tok.span)
    case TokenKind.UpperIdent =>
      // Could be a DataVariantPat: `T.Variant(p1, ...)` or `T.Variant`.
      val nameTok = consume()
      if accept(TokenKind.Dot).isDefined then
        val variantTok = expect(TokenKind.UpperIdent, "variant name")
        if current.kind == TokenKind.LParen then
          val fields = parseInnerPatternList()
          Trees.DataVariantPat(Some(nameTok.lexeme), variantTok.lexeme, fields, hasArgList = true, span(nameTok.span, current.span))
        else
          Trees.DataVariantPat(Some(nameTok.lexeme), variantTok.lexeme, Nil, hasArgList = false, span(nameTok.span, variantTok.span))
      else
        // Bare uppercase ident as pattern: rare, but let's accept it as a
        // BinderPat-equivalent. Per Rule M3.4.a it's a no-qualifier variant
        // pattern; the typer resolves against scrutinee's type.
        Trees.DataVariantPat(None, nameTok.lexeme, Nil, hasArgList = false, nameTok.span)
    case TokenKind.LParen =>
      // Tuple pattern.
      val startTok = consume()
      val elements = parseDelimited(TokenKind.RParen, ")")(() => parsePattern())
      val endTok = expect(TokenKind.RParen, "`)`")
      Trees.TuplePat(elements, span(startTok.span, endTok.span))
    case TokenKind.IntLit | TokenKind.FloatLit | TokenKind.StringLit
       | TokenKind.CharLit | TokenKind.KwTrue | TokenKind.KwFalse =>
      Trees.LiteralPat(parseAtomExpr(), current.span)
    case _ =>
      reporter.error(
        "P010",
        current.span,
        s"expected a pattern, got ${current.kind}${describeLexeme(current)}"
      )
      Trees.WildcardPat(consume().span)

  private def parseInnerPatternList(): List[Tree] =
    val _ = expect(TokenKind.LParen, "`(`")
    val pats = parseDelimited(TokenKind.RParen, ")")(() => parsePattern())
    val _ = expect(TokenKind.RParen, "`)`")
    pats

  // ---- Stub productions for declarations not in example 01 ----

  private def parseCapDecl(): Tree     = unsupported("`cap` declaration")
  private def parseDataDecl(): Tree    = unsupported("`data` declaration")
  private def parseEffectDecl(): Tree  = unsupported("`effect` declaration")
  private def parseTypeAlias(): Tree   = unsupported("`type` alias")
  private def parseModDecl(): Tree     = unsupported("`mod` declaration")
  private def parseSatisfiesDecl(): Tree = unsupported("`satisfies` declaration")

end Parser

object Parser:

  /** Functional entry point: parse a whole source file and return its
    * tree, diagnostics, and trivia together. The parser does not
    * require a caller-supplied Reporter — diagnostics flow through the
    * returned [[ParseResult]].
    *
    * Internally we still allocate a private Reporter to drive the
    * Scanner/Parser plumbing; the diagnostics are extracted and the
    * Reporter is discarded. The hot path stays identical to the
    * pre-M1 implementation while the API surface is functional.
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
