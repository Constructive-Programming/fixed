package fixed.parsing

import scala.annotation.tailrec

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile, Span}

/** Hand-written recursive-descent parser over the token stream produced
  * by [[Scanner]]. Pulls one token at a time into a small lookahead
  * buffer so productions needing >1 token of lookahead (lambda-vs-tuple,
  * RefinementCall vs FnCall, …) can peek.
  *
  * Some productions (`match`, `handle`, `cap`/`data`/`effect`/`satisfies`
  * declarations, `prop`, `forall`, struct literals) are still stubbed —
  * they emit an explicit "not yet implemented" diagnostic and a
  * `Trees.Error` gap node, leaving the surrounding parse intact.
  *
  * Recovery: every production that owns a synchronisation context
  * pushes an anchor frame via `withAnchors` and may call
  * `syncToAnchors` on failure; see `spec/syntax_grammar.ebnf` Appendix
  * A.1 for the normative anchor set per context.
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

  // ---- Recovery ----

  /** Stack of anchor frames, one per active production. Each production
    * pushes its own anchor set on entry and pops on exit; `syncToAnchors`
    * skips to the innermost frame's nearest anchor, so each production's
    * recovery is local — outer recovery is the outer caller's job. */
  private var activeAnchors: List[Set[TokenKind]] = Nil

  private def withAnchors[T](anchors: Set[TokenKind])(body: => T): T =
    activeAnchors = anchors :: activeAnchors
    try body
    finally activeAnchors = activeAnchors.tail

  /** Skip until `current.kind` is in the innermost active anchor frame
    * or EOF. Does NOT consume the anchor — the caller resumes from it. */
  private def syncToAnchors(): Unit =
    activeAnchors.headOption.foreach { inner =>
      while current.kind != TokenKind.Eof && !inner.contains(current.kind) do
        val _ = consume()
    }

  /** True iff `k` is `,` / `)` / `]` / `}` AND some active anchor frame
    * is waiting for it. Used by indented-block productions (parseBlock,
    * parseMatchArms, parseDoStmts, parseHandlerArms) to terminate an
    * implicitly-opened off-side body when the enclosing bracketed
    * production would otherwise see noise after an item. See
    * `spec/syntax_grammar.ebnf` "Re-enabling the off-side rule inside
    * brackets" — closing punctuation at the body's indent ends the
    * body without waiting for the scanner's DEDENT. */
  private def isOuterBracketWaiter(k: TokenKind): Boolean =
    (k == TokenKind.Comma || k == TokenKind.RParen
      || k == TokenKind.RBracket || k == TokenKind.RBrace)
      && activeAnchors.exists(_.contains(k))

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
    // A primitive (LowerIdent) followed by `satisfies` is also a
    // satisfaction declaration (e.g. `u64 satisfies Optional of Self`).
    case TokenKind.LowerIdent if peek(1).kind == TokenKind.KwSatisfies =>
      parseSatisfiesDecl()
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
      s"$what is not yet implemented",
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
    // TypeParamsHint (`<T1, T2, ...>`) deferred; Fixed type params are
    // normally introduced at first use via Rule 4.1.
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

  // A fn body is either a same-line Expr after `=` or an INDENTed block.
  private def parseFnBody(): Tree =
    if current.kind == TokenKind.Indent then parseBlock()
    else parseExpr()

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
  private def parseBlock(): Tree =
    val startTok = expect(TokenKind.Indent, "INDENT")
    val stmts = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent
            && current.kind != TokenKind.Eof
            && !isOuterBracketWaiter(current.kind) do
        val stmtStart = current.span
        val stmt = parseStatement()
        if Anchors.blockBody.contains(current.kind)
           || current.kind == TokenKind.Eof
           || isOuterBracketWaiter(current.kind) then
          stmts += stmt
        else
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
    val endSpan = current.span
    // Consume DEDENT only if it's actually present. When the block was
    // implicitly terminated by an outer-bracket waiter, DEDENT comes
    // after that token and is drained by `parseDelimited`.
    if accept(TokenKind.Dedent).isEmpty then ()
    Trees.Block(stmts.toList, span(startTok.span, endSpan))

  // Fixed has no statements — every block element is an Expr, with one
  // exception: a nested `fn name(...) = body` declaration is admissible
  // in any block to introduce a local function.
  private def parseStatement(): Tree = current.kind match
    case TokenKind.KwFn => parseFnDecl()
    case _              => parseExpr()

  // ---- Type expressions ----

  // TypeExpr ::= ArrowType ::= ArrowLhs ("->" ArrowType WithClause?)?
  // The anchor frame lets a failed `parseTypeAtom` sync to whatever
  // plausibly follows a type.
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
      val _ = consume()
      if accept(TokenKind.RParen).isDefined then
        Trees.UnitType(span(startSpan, current.span))
      else
        val first = parseTypeExpr()
        if accept(TokenKind.Comma).isDefined then
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
    case TokenKind.KwPart =>
      // `Part` — the cap's element-type pseudo-parameter (Rule 5.3).
      // In type position it behaves like a TypeRef.
      val tok = consume()
      val ofArg = if accept(TokenKind.KwOf).isDefined then Some(parseOfArg()) else None
      Trees.TypeRef("Part", ofArg, span(tok.span, current.span))
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

  // OfArg ::= OfValueParam (decl-site) | LiteralExpr | TypeExpr. The
  // parser admits any TypeExpr or a parenthesised comma-list (for the
  // tuple-of-TypeExpr form `BiFunctor of (A, B)`); decl-site value
  // params and literal-expression OfArgs are disambiguated by the
  // typer.
  private def parseOfArg(): Tree =
    if current.kind == TokenKind.LParen then
      val startSpan = current.span
      val _ = consume()
      // `()` — unit type as `of` argument (e.g. `M is Monad of ()`).
      if accept(TokenKind.RParen).isDefined then
        Trees.UnitType(span(startSpan, current.span))
      else
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
          val args = parseArgList()
          receiver = receiver match
            case Trees.Ident(_, sp) =>
              Trees.Apply(receiver, args, span(sp, current.span))
            case other =>
              Trees.Apply(other, args, span(other.span, current.span))
        case TokenKind.Dot =>
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
              Trees.StaticCall(qual, nameTok.lexeme, args, hasArgList, span(qspan, current.span))
            case Trees.Ident(qual, qspan) if qual.headOption.exists(_.isUpper) && !hasArgList =>
              // Bare `T.Variant` (or static-method-no-args). Encoded as
              // StaticCall with empty args; the typer disambiguates.
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
    * that is neither `,` nor `closeKind`, emit a diagnostic, sync to
    * the next such token, and wrap the partial item in `Trees.Error`.
    * Trailing comma admitted.
    *
    * Off-side drain: an item that re-engaged off-side (e.g. a multi-
    * line lambda body) may leave a NEWLINE/DEDENT run pending after
    * the closing punctuation. Drain those between items so the next
    * `parseOne` starts on a real token. */
  private def parseDelimited(
      closeKind: TokenKind,
      closeLexeme: String
  )(parseItem: () => Tree): List[Tree] =
    val anchors = Set(TokenKind.Comma, closeKind)
    val items = scala.collection.mutable.ListBuffer.empty[Tree]
    def drainImpliedDedents(): Unit =
      while current.kind == TokenKind.Newline
            || current.kind == TokenKind.Dedent do
        val _ = consume()
    def parseOne(): Unit =
      val itemStart = current.span
      val item = parseItem()
      drainImpliedDedents()
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
          drainImpliedDedents()
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
    case TokenKind.KwMatch   => parseMatchExpr()
    case TokenKind.KwHandle  => parseHandleExpr()
    case TokenKind.KwDo      => parseDoExpr()
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

  // MatchExpr ::= "match" Expr ":" INDENT MatchArm+ DEDENT
  // MatchArm  ::= OrPattern ("if" Expr)? "=>" InlineOrBlockExpr
  private def parseMatchExpr(): Tree =
    val startTok = expect(TokenKind.KwMatch, "`match`")
    val scrutinee = parseExpr()
    val _ = expect(TokenKind.Colon, "`:` after match scrutinee")
    val arms = parseMatchArms()
    Trees.MatchExpr(scrutinee, arms, span(startTok.span, current.span))

  private def parseMatchArms(): List[Trees.MatchArm] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val arms = scala.collection.mutable.ListBuffer.empty[Trees.MatchArm]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent
            && current.kind != TokenKind.Eof
            && !isOuterBracketWaiter(current.kind) do
        arms += parseMatchArm()
        skipNewlines()
    }
    if accept(TokenKind.Dedent).isEmpty then ()
    arms.toList

  private def parseMatchArm(): Trees.MatchArm =
    val pat = parseOrPattern()
    val guard =
      if accept(TokenKind.KwIf).isDefined then Some(parseExpr())
      else None
    val _ = expect(TokenKind.FatArrow, "`=>` in match arm")
    val body = parseInlineOrBlockExpr()
    Trees.MatchArm(pat, guard, body, span(pat.span, body.span))

  private def parseOrPattern(): Tree =
    @tailrec
    def loop(acc: Tree): Tree =
      if accept(TokenKind.Pipe).isDefined then
        val rhs = parsePattern()
        loop(Trees.OrPat(acc, rhs, span(acc.span, rhs.span)))
      else acc
    loop(parsePattern())

  // DoExpr ::= "do" ":" INDENT DoStmt+ DEDENT
  // DoStmt ::= Pattern "<-" Expr   (DoBind)
  //          | Expr
  private def parseDoExpr(): Tree =
    val startTok = expect(TokenKind.KwDo, "`do`")
    val _ = expect(TokenKind.Colon, "`:` after `do`")
    val stmts = parseDoStmts()
    Trees.DoExpr(stmts, span(startTok.span, current.span))

  private def parseDoStmts(): List[Tree] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val stmts = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent
            && current.kind != TokenKind.Eof
            && !isOuterBracketWaiter(current.kind) do
        val stmtStart = current.span
        val stmt =
          if isDoBindAhead() then
            val pat = parsePattern()
            val _ = expect(TokenKind.BackArrow, "`<-`")
            val rhs = parseExpr()
            Trees.DoBind(pat, rhs, span(pat.span, rhs.span))
          else parseExpr()
        if Anchors.blockBody.contains(current.kind)
           || current.kind == TokenKind.Eof
           || isOuterBracketWaiter(current.kind) then
          stmts += stmt
        else
          reporter.error(
            "P018",
            current.span,
            s"unexpected ${current.kind}${describeLexeme(current)} after do statement; expected end of line",
            Some("split this expression onto its own line")
          )
          syncToAnchors()
          stmts += Trees.Error(List(stmt), span(stmtStart, current.span))
        skipNewlines()
    }
    if accept(TokenKind.Dedent).isEmpty then ()
    stmts.toList

  // True iff the current statement contains a top-level `<-` before
  // its terminating NEWLINE/DEDENT/EOF — i.e. it's a `pat <- expr`
  // bind rather than a plain expression. Tracks paren/bracket/brace
  // depth so a `<-` inside a nested expression is not mistaken for
  // the bind arrow.
  private def isDoBindAhead(): Boolean =
    @tailrec
    def loop(i: Int, depth: Int): Boolean =
      peek(i).kind match
        case TokenKind.BackArrow if depth == 0 => true
        case TokenKind.Newline | TokenKind.Dedent | TokenKind.Eof => false
        case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace =>
          loop(i + 1, depth + 1)
        case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace =>
          if depth == 0 then false else loop(i + 1, depth - 1)
        case _ => loop(i + 1, depth)
    loop(0, 0)

  // HandleExpr ::= "handle" Expr ":" INDENT HandlerArm+ DEDENT
  // HandlerArm ::= EffectArm | ReturnArm
  //   EffectArm ::= UPPER_IDENT "." LOWER_IDENT ("(" PatternList ")")? "=>" Body
  //   ReturnArm ::= "return" "(" Pattern ")" "=>" Body
  private def parseHandleExpr(): Tree =
    val startTok = expect(TokenKind.KwHandle, "`handle`")
    val subject = parseExpr()
    val _ = expect(TokenKind.Colon, "`:` after handle subject")
    val arms = parseHandlerArms()
    Trees.HandleExpr(subject, arms, span(startTok.span, current.span))

  private def parseHandlerArms(): List[Tree] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val arms = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent
            && current.kind != TokenKind.Eof
            && !isOuterBracketWaiter(current.kind) do
        arms += parseHandlerArm()
        skipNewlines()
    }
    if accept(TokenKind.Dedent).isEmpty then ()
    arms.toList

  private def parseHandlerArm(): Tree =
    val startSpan = current.span
    current.kind match
      case TokenKind.KwReturn =>
        val _ = consume()
        val _ = expect(TokenKind.LParen, "`(`")
        val pat = parsePattern()
        val _ = expect(TokenKind.RParen, "`)`")
        val _ = expect(TokenKind.FatArrow, "`=>` after return arm pattern")
        val body = parseInlineOrBlockExpr()
        Trees.ReturnArm(pat, body, span(startSpan, body.span))
      case TokenKind.UpperIdent =>
        val effectTok = consume()
        val _ = expect(TokenKind.Dot, "`.`")
        val opTok = expect(TokenKind.LowerIdent, "effect op name")
        val params =
          if current.kind == TokenKind.LParen then parseInnerPatternList()
          else Nil
        val _ = expect(TokenKind.FatArrow, "`=>` after effect op")
        val body = parseInlineOrBlockExpr()
        Trees.HandlerArm(effectTok.lexeme, opTok.lexeme, params, body, span(startSpan, body.span))
      case _ =>
        reporter.error(
          "P019",
          current.span,
          s"expected a handler arm, got ${current.kind}${describeLexeme(current)}",
          Some("each arm is `Effect.op(...) => body` or `return(p) => body`")
        )
        syncToAnchors()
        Trees.Error(Nil, span(startSpan, current.span))

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
      // Skip a NEWLINE between `)` and `->` (multi-line lambda).
      val afterIdx = found + 1
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
        // Tuple literal — `first` is already past the first comma; the
        // tail uses parseDelimited for recovery and is prepended.
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
        // Bare uppercase ident as pattern — a no-qualifier variant
        // pattern (per pattern_matching.md Rule 3.4.a). The typer
        // resolves against the scrutinee's type.
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

  // ---- DataDecl ----

  // DataDecl ::= "data" UPPER_IDENT OfClause? (DataBody | CtorSugar)?
  //   OfClause   ::= "of" (TypeExpr | "(" TypeExpr ("," TypeExpr)* ")")
  //   DataBody   ::= ":" INDENT DataVariant+ DEDENT
  //   CtorSugar  ::= "(" FieldList ")"   — single-variant shorthand
  //
  //   DataVariant ::= UPPER_IDENT ("(" FieldList ")")?
  //   FieldList   ::= FieldDecl ("," FieldDecl)* ","?
  //   FieldDecl   ::= LOWER_IDENT ":" TypeExpr ("=" Expr)?
  private def parseDataDecl(): Tree =
    val startTok = expect(TokenKind.KwData, "`data`")
    val nameTok = expect(TokenKind.UpperIdent, "data type name")
    val ofParams = parseOptionalDataOfClause()
    if current.kind == TokenKind.LParen then
      // Single-ctor sugar — synthesise one variant with the type's name.
      val fields = parseFieldList()
      val variant = Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, current.span))
      Trees.DataDecl(nameTok.lexeme, ofParams, List(variant), span(startTok.span, current.span))
    else if accept(TokenKind.Colon).isDefined then
      val variants = parseDataVariants()
      Trees.DataDecl(nameTok.lexeme, ofParams, variants, span(startTok.span, current.span))
    else
      reporter.error(
        "P013",
        current.span,
        s"`data ${nameTok.lexeme}` needs either a `(field: T, ...)` ctor sugar or a `:` block of variants",
        Some("add `: ...` for variants or `(field: T, ...)` for a single ctor")
      )
      Trees.DataDecl(nameTok.lexeme, ofParams, Nil, span(startTok.span, current.span))

  private def parseOptionalDataOfClause(): List[Tree] =
    if accept(TokenKind.KwOf).isDefined then
      if current.kind == TokenKind.LParen then
        val _ = consume()
        val params = parseDelimited(TokenKind.RParen, ")")(() => parseDataOfItem())
        val _ = expect(TokenKind.RParen, "`)`")
        params
      else
        List(parseDataOfItem())
    else Nil

  // Inside an `of (...)` clause, each item is either a TypeExpr (incl.
  // a NamedAlias like `N is Numeric + Ord`) or an `OfValueParam` —
  // `name: TypeExpr (= default)?` — used to make data types index
  // dependently on values (`data Bounded of (N is Numeric, lo: N = 16, hi: N = 24)`).
  // OfValueParam is lowered to a `FnParam` since the shape matches.
  private def parseDataOfItem(): Tree =
    if current.kind == TokenKind.LowerIdent && peek(1).kind == TokenKind.Colon then
      val nameTok = consume()
      val _ = expect(TokenKind.Colon, "`:`")
      val ty = parseTypeExpr()
      val default = if accept(TokenKind.Eq).isDefined then Some(parseExpr()) else None
      Trees.FnParam(nameTok.lexeme, ty, default, span(nameTok.span, current.span))
    else
      parseTypeExpr()

  private def parseDataVariants(): List[Trees.DataVariant] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val variants = scala.collection.mutable.ListBuffer.empty[Trees.DataVariant]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent && current.kind != TokenKind.Eof do
        if current.kind == TokenKind.UpperIdent then
          variants += parseDataVariant()
        else
          // Not a variant head — emit a diagnostic and sync to the next
          // statement boundary so the loop can make progress. (Props or
          // other in-body forms land here until their own productions
          // are wired in.)
          reporter.error(
            "P013",
            current.span,
            s"expected a variant name, got ${current.kind}${describeLexeme(current)}",
            Some("variants are introduced by `UpperIdent` at the data body's indent")
          )
          syncToAnchors()
        skipNewlines()
    }
    val _ = expect(TokenKind.Dedent, "DEDENT")
    variants.toList

  private def parseDataVariant(): Trees.DataVariant =
    val nameTok = expect(TokenKind.UpperIdent, "variant name")
    val fields =
      if current.kind == TokenKind.LParen then parseFieldList()
      else Nil
    Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, current.span))

  private def parseFieldList(): List[Trees.FieldDecl] =
    val _ = expect(TokenKind.LParen, "`(`")
    val fields = scala.collection.mutable.ListBuffer.empty[Trees.FieldDecl]
    if current.kind != TokenKind.RParen then
      fields += parseFieldDecl()
      while accept(TokenKind.Comma).isDefined do
        if current.kind == TokenKind.RParen then ()  // trailing comma
        else fields += parseFieldDecl()
    val _ = expect(TokenKind.RParen, "`)`")
    fields.toList

  private def parseFieldDecl(): Trees.FieldDecl =
    val nameTok = expect(TokenKind.LowerIdent, "field name")
    val _ = expect(TokenKind.Colon, "`:`")
    val ty = parseTypeExpr()
    val default = if accept(TokenKind.Eq).isDefined then Some(parseExpr()) else None
    Trees.FieldDecl(nameTok.lexeme, ty, default, span(nameTok.span, current.span))

  // ---- CapDecl ----

  // CapDecl   ::= "cap" UPPER_IDENT OfClause? ExtendsClause? (":" INDENT CapBody DEDENT)?
  //   OfClause     ::= "of" (TypeExpr | "(" OfItem ("," OfItem)* ")")
  //   ExtendsClause::= "extends" CapBound ("+" CapBound)*
  //   CapBody      ::= CapMember+
  //   CapMember    ::= InstanceMethod | StaticMethod | PropDecl
  //   InstanceMethod ::= "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause? ("=" FnBody)?
  //   StaticMethod   ::= "Self" "." "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause? ("=" FnBody)?
  //   PropDecl       ::= "prop" LOWER_IDENT ":" Expr
  //
  // Per v0.3: empty bodies omit the `:` entirely (`cap Locked`).
  private def parseCapDecl(): Tree =
    val startTok = expect(TokenKind.KwCap, "`cap`")
    val nameTok = expect(TokenKind.UpperIdent, "capability name")
    val ofParams = parseOptionalDataOfClause()
    val extendsList =
      if accept(TokenKind.KwExtends).isDefined then parseCapBoundChain()
      else Nil
    val body =
      if accept(TokenKind.Colon).isDefined then parseCapBody()
      else Nil
    Trees.CapDecl(nameTok.lexeme, ofParams, extendsList, body, span(startTok.span, current.span))

  private def parseCapBody(): List[Tree] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val members = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent && current.kind != TokenKind.Eof do
        current.kind match
          case TokenKind.KwFn   => members += parseInstanceMethod()
          case TokenKind.KwSelf => members += parseStaticMethod()
          case TokenKind.KwProp => members += parsePropDecl()
          case _ =>
            reporter.error(
              "P014",
              current.span,
              s"expected `fn`, `Self.fn`, or `prop`, got ${current.kind}${describeLexeme(current)}",
              Some("cap members are instance methods (`fn`), static methods (`Self.fn`), or `prop` declarations")
            )
            syncToAnchors()
        skipNewlines()
    }
    val _ = expect(TokenKind.Dedent, "DEDENT")
    members.toList

  private def parseInstanceMethod(): Trees.InstanceMethod =
    val startTok = expect(TokenKind.KwFn, "`fn`")
    val nameTok = expect(TokenKind.LowerIdent, "method name")
    val params =
      if current.kind == TokenKind.LParen then parseFnParamList()
      else Nil
    val returnType =
      if accept(TokenKind.Arrow).isDefined then parseTypeExpr()
      else Trees.UnitType(current.span)
    val withClause = parseOptionalWithClause()
    val body =
      if accept(TokenKind.Eq).isDefined then Some(parseFnBody())
      else None
    Trees.InstanceMethod(
      name = nameTok.lexeme,
      typeParamsHint = Nil,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, current.span)
    )

  private def parseStaticMethod(): Trees.StaticMethod =
    val startTok = expect(TokenKind.KwSelf, "`Self`")
    val _ = expect(TokenKind.Dot, "`.`")
    val _ = expect(TokenKind.KwFn, "`fn`")
    val nameTok = expect(TokenKind.LowerIdent, "static method name")
    val params =
      if current.kind == TokenKind.LParen then parseFnParamList()
      else Nil
    val returnType =
      if accept(TokenKind.Arrow).isDefined then parseTypeExpr()
      else Trees.UnitType(current.span)
    val withClause = parseOptionalWithClause()
    val body =
      if accept(TokenKind.Eq).isDefined then Some(parseFnBody())
      else None
    Trees.StaticMethod(
      name = nameTok.lexeme,
      typeParamsHint = Nil,
      params = params,
      returnType = returnType,
      withClause = withClause,
      body = body,
      span = span(startTok.span, current.span)
    )

  private def parsePropDecl(): Trees.PropDecl =
    val startTok = expect(TokenKind.KwProp, "`prop`")
    val nameTok = expect(TokenKind.LowerIdent, "prop name")
    val _ = expect(TokenKind.Colon, "`:` after prop name")
    val body = parseExpr()
    Trees.PropDecl(nameTok.lexeme, body, span(startTok.span, body.span))

  // ---- TypeAlias ----

  // TypeAlias ::= "type" UPPER_IDENT ValueParams? "=" CapBoundChain
  //   ValueParams ::= "(" FnParam ("," FnParam)* ","? ")"
  // The RHS chain is wrapped in `IsBound(caps)` when there are multiple
  // caps; a single-cap RHS is the cap node itself.
  private def parseTypeAlias(): Tree =
    val startTok = expect(TokenKind.KwType, "`type`")
    val nameTok = expect(TokenKind.UpperIdent, "type alias name")
    val valueParams =
      if current.kind == TokenKind.LParen then parseFnParamList()
      else Nil
    val _ = expect(TokenKind.Eq, "`=`")
    val caps = parseCapBoundChain()
    val rhs =
      if caps.size == 1 then caps.head
      else Trees.IsBound(caps, span(caps.head.span, caps.last.span))
    Trees.TypeAlias(nameTok.lexeme, valueParams, rhs, span(startTok.span, current.span))

  // ---- SatisfiesDecl ----

  // SatisfiesDecl ::= TypeName "satisfies" CapBound (":" INDENT SatisfiesItem+ DEDENT)?
  //   TypeName    ::= UPPER_IDENT | LOWER_IDENT          (primitives)
  //   SatisfiesItem ::= ConstructorMapping
  //                  | ImpossibleMapping
  //                  | InstanceMethod | StaticMethod | PropDecl
  //   ConstructorMapping ::= (UPPER_IDENT | "Self") "as" LOWER_IDENT
  //   ImpossibleMapping  ::= "impossible" "as" LOWER_IDENT
  private def parseSatisfiesDecl(): Tree =
    val startSpan = current.span
    val typeName = current.kind match
      case TokenKind.UpperIdent | TokenKind.LowerIdent => consume().lexeme
      case _ =>
        reporter.error(
          "P015",
          current.span,
          s"expected a type name to satisfy a capability, got ${current.kind}${describeLexeme(current)}",
          Some("`<TypeName> satisfies <CapName>: ...`")
        )
        consume().lexeme
    val _ = expect(TokenKind.KwSatisfies, "`satisfies`")
    val cap = parseCapBound()
    val items =
      if accept(TokenKind.Colon).isDefined then parseSatisfiesBody()
      else Nil
    Trees.SatisfiesDecl(typeName, cap, items, span(startSpan, current.span))

  private def parseSatisfiesBody(): List[Tree] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val items = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent && current.kind != TokenKind.Eof do
        items += parseSatisfiesItem()
        skipNewlines()
    }
    val _ = expect(TokenKind.Dedent, "DEDENT")
    items.toList

  private def parseSatisfiesItem(): Tree = current.kind match
    case TokenKind.KwImpossible =>
      val startTok = consume()
      val _ = expect(TokenKind.KwAs, "`as`")
      val ctorTok = expect(TokenKind.LowerIdent, "cap constructor name")
      Trees.ImpossibleMapping(ctorTok.lexeme, span(startTok.span, ctorTok.span))
    case TokenKind.KwSelf if peek(1).kind == TokenKind.KwAs =>
      // `Self as ctor` — identity-shape mapping for primitives that
      // are themselves the cap's value (`u64 satisfies Optional of
      // Self: Self as some`).
      val startTok = consume()
      val _ = expect(TokenKind.KwAs, "`as`")
      val ctorTok = expect(TokenKind.LowerIdent, "cap constructor name")
      Trees.ConstructorMapping("Self", ctorTok.lexeme, span(startTok.span, ctorTok.span))
    case TokenKind.UpperIdent =>
      val variantTok = consume()
      val _ = expect(TokenKind.KwAs, "`as`")
      val ctorTok = expect(TokenKind.LowerIdent, "cap constructor name")
      Trees.ConstructorMapping(variantTok.lexeme, ctorTok.lexeme, span(variantTok.span, ctorTok.span))
    case TokenKind.KwFn   => parseInstanceMethod()
    case TokenKind.KwSelf => parseStaticMethod()
    case TokenKind.KwProp => parsePropDecl()
    case _ =>
      reporter.error(
        "P016",
        current.span,
        s"expected a satisfies item, got ${current.kind}${describeLexeme(current)}",
        Some("items are `Variant as ctor`, `impossible as ctor`, `fn ...`, `Self.fn ...`, or `prop ...`")
      )
      val tok = current.span
      syncToAnchors()
      Trees.Error(Nil, span(tok, current.span))

  // ---- EffectDecl ----

  // EffectDecl ::= "linear"? "effect" UPPER_IDENT OfClause? (":" INDENT EffectMember+ DEDENT)?
  //   EffectMember ::= "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause?
  // (Effect ops never have bodies; their semantics are supplied by handlers.)
  private def parseEffectDecl(): Tree =
    val startSpan = current.span
    val isLinear = accept(TokenKind.KwLinear).isDefined
    val _ = expect(TokenKind.KwEffect, "`effect`")
    val nameTok = expect(TokenKind.UpperIdent, "effect name")
    val ofParams = parseOptionalDataOfClause()
    val members =
      if accept(TokenKind.Colon).isDefined then parseEffectMembers()
      else Nil
    Trees.EffectDecl(isLinear, nameTok.lexeme, ofParams, members, span(startSpan, current.span))

  private def parseEffectMembers(): List[Tree] =
    val _ = expect(TokenKind.Indent, "INDENT")
    val members = scala.collection.mutable.ListBuffer.empty[Tree]
    skipNewlines()
    withAnchors(Anchors.blockBody) {
      while current.kind != TokenKind.Dedent && current.kind != TokenKind.Eof do
        if current.kind == TokenKind.KwFn then
          members += parseInstanceMethod()
        else
          reporter.error(
            "P017",
            current.span,
            s"expected an effect op (`fn name(...) -> T`), got ${current.kind}${describeLexeme(current)}",
            Some("each effect op is declared like an instance method, without a body")
          )
          syncToAnchors()
        skipNewlines()
    }
    val _ = expect(TokenKind.Dedent, "DEDENT")
    members.toList

  // ---- Stub productions still pending ----

  private def parseModDecl(): Tree     = unsupported("`mod` declaration")

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
