package fixed.parsing

import Parser.*

/** Type expressions: arrow types, type atoms, capability bounds and `of` arguments. */
private[parsing] object TypeParsers:
  import DeclParsers.*
  import ExprParsers.*

  /** TypeExpr ::= ArrowType ::= ArrowLhs ("->" ArrowType WithClause?)?
    * The anchor frame lets a failed `parseTypeAtom` sync to whatever
    * plausibly follows a type.
    */
  def parseTypeExpr(s: ParserState)(using Reporter): Parsed[Tree] =
    withAnchors(s, Anchors.typeExpr) { st => parseArrowType(st) }

  private[parsing] def parseArrowType(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, lhs) = parseArrowLhs(s0)
    accept(s1, TokenKind.Arrow) match
      case (s2, Some(_)) =>
        val (s3, rhs) = parseArrowType(s2)
        val (s4, withClause) = parseOptionalWithClause(s3)
        (s4, Trees.ArrowType(lhs, rhs, withClause, span(lhs.span, s4.current.span)))
      case (s2, None) => (s2, lhs)

  /** Shape of a parenthesised group whose `(` is already consumed. */
  private[parsing] enum Paren:
    case Empty(span: Span)
    case One(t: Tree)
    case Many(ts: List[Tree], span: Span)

  /** `)` | item `)` | item `,` items `)`. Always consumes the closing paren
    * (or reports it missing); `start` is the span of the opening `(`.
    */
  private[parsing] inline def parseParenBody(s1: ParserState, start: Span)(
      inline item: Rule[Tree]
  )(using Reporter): Parsed[Paren] =
    accept(s1, TokenKind.RParen) match
      case (s2, Some(_)) => (s2, Paren.Empty(span(start, s2.current.span)))
      case (s2, None)    =>
        val (s3, first) = item(s2)
        accept(s3, TokenKind.Comma) match
          case (s4, Some(_)) =>
            /** `first` is already past the first comma; the tail uses
              * parseDelimited for recovery and is prepended.
              */
            val (s5, tail) = parseDelimited(s4, TokenKind.RParen, ")")(item)
            val (s6, _) = expect(s5, TokenKind.RParen, "`)`")
            (s6, Paren.Many(first :: tail, span(start, s6.current.span)))
          case (s4, None) =>
            val (s5, _) = expect(s4, TokenKind.RParen, "`)`")
            (s5, Paren.One(first))

  private[parsing] def parseArrowLhs(s0: ParserState)(using Reporter): Parsed[Tree] =
    if s0.current.kind == TokenKind.LParen then
      /** Either: `(T1, T2, ...)` → TupleArrowLhs/UnitType
        *         `(T)` → just T (parenthesised)
        *         `(T) of OfArg` → ParenTypeApp
        */
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

  private[parsing] def parseTypeAtom(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
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
      /** NamedAlias (`N is C`) or TypeRef (`T`, `T of A`). `Part` is the cap's
        * element-type pseudo-parameter (Rule 5.3): it behaves like a TypeRef,
        * and `Part is C` lifts it into a NamedAlias so callers can constrain
        * Part by a capability chain (`cap Sorted of (Part is Ord)`).
        */
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

  private[parsing] def parseIsBound(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwIs, "`is`") { (s1, startTok) =>
      val (s2, caps) = parseCapBoundChain(s1)
      (s2, Trees.IsBound(caps, span(startTok.span, s2.current.span)))
    }

  private[parsing] def parseCapBoundChain(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, caps) = parseSepBy1(s0, TokenKind.Plus)(parseCapBound)
    (s1, caps.toList)

  /** `name(args)` with `name` already consumed and `current` at `(`. */
  private[parsing] def parseRefinementCall(s1: ParserState, nameTok: Token)(using Reporter): Parsed[Tree] =
    val (s2, args) = parseArgList(s1)
    (s2, Trees.RefinementCall(nameTok.lexeme, args, span(nameTok.span, s2.current.span)))

  private[parsing] def parseCapBound(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.UpperIdent =>
      /** `Name(args)` — invocation of a parameterised type alias or
        * cap-generating function whose name happens to be uppercase.
        * Same shape as `lower(args)` below; the typer disambiguates by
        * resolving the binding.
        */
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

  private[parsing] def parseCapRef(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseNamedRef(s0, "capability name")(Trees.CapRef.apply)

  private[parsing] def parseEffectBound(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseNamedRef(s0, "effect name")(Trees.EffectBound.apply)

  /** `Name (of OfArg)?`; `what` names the expected identifier in diagnostics. */
  private[parsing] def parseNamedRef(
      s0: ParserState,
      what: String
  )(mk: (String, Option[Tree], Span) => Trees.NamedRef)(using
      Reporter
  ): Parsed[Tree] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, what)
    val (s2, ofArg) = s1.optionally(TokenKind.KwOf)(parseOfArg)
    (s2, mk(nameTok.lexeme, ofArg, span(nameTok.span, s2.current.span)))

  /** OfArg ::= OfValueParam (decl-site) | LiteralExpr | TypeExpr. The
    * parser admits any TypeExpr or a parenthesised comma-list (for the
    * tuple-of-TypeExpr form `BiFunctor of (A, B)`); decl-site value
    * params and literal-expression OfArgs are disambiguated by the
    * typer.
    */
  private[parsing] def parseOfArg(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LParen then
      val startSpan = s.current.span
      val s1 = s.advance

      /** `()` is the unit type as `of` argument (e.g. `M is Monad of ()`).
        * Items use parseDataOfItem so literal arguments like `Bounded of (N,
        * 0, 10)` and OfValueParam-shaped forms work in any of-arg
        * position, not just on data declarations.
        */
      val (s2, p) = parseParenBody(s1, startSpan)(parseDataOfItem)
      p match
        case Paren.Empty(sp)    => (s2, Trees.UnitType(sp))
        case Paren.One(first)   => (s2, first)
        case Paren.Many(ts, sp) => (s2, Trees.TupleArrowLhs(ts, sp))
    else parseTypeExpr(s)

  /** CapType ::= "cap" CapReturnSpec?
    * CapReturnSpec ::= "of" OfArg
    *                 | "extends" CapBound ("+" CapBound)*
    * The grammar makes the two arms exclusive (spec/syntax_grammar.ebnf
    * §CapReturnSpec): a `cap` is followed by either `of …` or
    * `extends …`, never both — so `Option[CapReturnSpec]` fits.
    * After `cap` in type position: `of OfArg`, `extends CapBound (+ CapBound)*`, or bare.
    */
  private[parsing] def parseCapTypeRest(s1: ParserState, startTok: Token)(using Reporter): Parsed[Tree] =
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

end TypeParsers
