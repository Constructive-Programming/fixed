package fixed.parsing

import scala.annotation.tailrec

import cats.Monoid

import Parser.*

/** Declarations: top-level items, fn-like signatures and parameters, blocks, and data / cap / effect / satisfies / type-alias bodies. */
private[parsing] object DeclParsers:
  import ExprParsers.*
  import TypeParsers.*

  private[parsing] def parseTopItem(s: ParserState)(using reporter: Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwUse      => parseUseDecl(s)
    case TokenKind.KwFn       => parseFnLike(s, FnKind.Free)
    case TokenKind.KwFip      => parseFipFn(s)
    case TokenKind.KwCap      => s.afterKeyword(TokenKind.KwCap, "`cap`")(parseCapDeclRest)
    case TokenKind.KwData     => parseDataDecl(s)
    case TokenKind.KwEffect   => parseEffectDecl(s)
    case TokenKind.KwLinear   => parseEffectDecl(s)
    case TokenKind.KwType     => parseTypeAlias(s)
    case TokenKind.KwMod      => parseModDecl(s)
    case TokenKind.KwPub      => unsupported(s, "`pub` modifier")
    case TokenKind.UpperIdent => parseSatisfiesDecl(s)

    /** A primitive (LowerIdent) followed by `satisfies` is also a
      * satisfaction declaration (e.g. `u64 satisfies Optional of Self`).
      */
    case TokenKind.LowerIdent if s.peek(1).kind == TokenKind.KwSatisfies =>
      parseSatisfiesDecl(s)
    case _ =>
      /** Unknown token at top level. Emit a diagnostic, then synchronise
        * to the next decl introducer (or EOF). This collapses runs of
        * junk into a single Error item rather than one per stray token.
        */
      val startSpan = s.current.span
      reporter.error(
        DiagCode.P002,
        startSpan,
        s"unexpected token at top level: ${s.current.kind}${describeLexeme(s.current)}",
        Some("expected `use`, `fn`, `cap`, `data`, `effect`, `type`, `mod`, or `<TypeName> satisfies …`")
      )

      /** syncToAnchors stops at the next anchor (top-level keyword or EOF)
        * without consuming it, so the outer loop dispatches that token to
        * its proper handler.
        */
      val s1 = syncToAnchors(s)
      (s1, Trees.Error(Nil, span(startSpan, s1.current.span)))

  private[parsing] def unsupported(s: ParserState, what: String)(using reporter: Reporter): Parsed[Tree] =
    reporter.error(
      DiagCode.P099,
      s.current.span,
      s"$what is not yet implemented",
      Some("file an issue or extend Parsers.scala")
    )
    val (s1, tok) = consume(s)
    (s1, Trees.Error(Nil, tok.span))

  /** UseDecl ::= "use" Path ("satisfies" CapBound)?
    * Path     ::= Ident ("." Ident)* ("." "{" Ident ("," Ident)* ","? "}")?
    * The trailing brace-group is sugar for one import per listed name
    * sharing the dotted prefix; the parser stores them in `selectors`.
    */
  private[parsing] def parseUseDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwUse, "`use`") { (s1, startTok) =>
      val (s2, head) = parseIdentTextEither(s1)

      /** Walk `.Ident` segments, optionally terminated by `.{ name, ... }`. */
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

  private[parsing] def parseIdentTextEither(s: ParserState)(using Reporter): Parsed[String] =
    s.current.kind match
      case TokenKind.LowerIdent | TokenKind.UpperIdent =>
        val (s1, tok) = consume(s)
        (s1, tok.lexeme)
      case _ =>
        reportExpected(s, DiagCode.P003, "an identifier", None)
        (s, "<error>")

  /** FnDecl ::= "fn" LOWER_IDENT TypeParamsHint? FnParamList FnSigTail          (Free)
    * InstanceMethod ::= "fn" LOWER_IDENT TypeParamsHint? FnParamList? FnSigTail (Instance)
    * StaticMethod ::= "Self" "." "fn" LOWER_IDENT TypeParamsHint? FnParamList? FnSigTail (Static)
    */
  /** Which function-shaped declaration to parse, with its diagnostic noun and the node to build. */
  private[parsing] enum FnKind(
      val nameWhat: String,
      val build: (Trees.FnSig, Span) => Trees.FnLike
  ):
    case Free extends FnKind("function name", (sig, at) => Trees.FnDecl(sig, at))
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

  private[parsing] def parseFnLike(s0: ParserState, kind: FnKind)(using Reporter): Parsed[Trees.FnLike] =
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

  /** `fip fn ...` or `fip(strict) fn ...` (grammar v0.4.9 `FipModifier`); `strict` is contextual. */
  private[parsing] def parseFipFn(s0: ParserState)(using Reporter): Parsed[Trees.FnLike] =
    val (s1, _) = expect(s0, TokenKind.KwFip, "`fip`")
    val (s2, strict) =
      if s1.current.kind == TokenKind.LParen && s1.peek(1).lexeme == "strict" then
        val (s3, _) = expect(s1.advance, TokenKind.LowerIdent, "`strict`")
        val (s4, _) = expect(s3, TokenKind.RParen, "`)`")
        (s4, true)
      else (s1, false)
    val (s5, fn) = parseFnLike(s2, FnKind.Free)
    fn match
      case d: Trees.FnDecl => (s5, d.copy(fip = Some(if strict then Trees.Fip.Strict else Trees.Fip.Plain)))
      case other           => (s5, other)

  private[parsing] def parseFnParamList(s0: ParserState)(using Reporter): Parsed[List[Trees.FnParam]] =
    val (s1, (params, _)) = bracketedTyped(s0, Bracket.Paren)(parseFnParam)
    (s1, params)

  private[parsing] def parseFieldList(s0: ParserState)(using Reporter): Parsed[List[Trees.FieldDecl]] =
    val (s1, (fields, _)) = bracketedTyped(s0, Bracket.Paren)(parseBinder(_, "field name")(Trees.FieldDecl.apply))
    (s1, fields)

  private[parsing] def parseFnParam(s0: ParserState)(using Reporter): Parsed[Trees.FnParam] =
    parseBinder(s0, "parameter name")(Trees.FnParam.apply)

  /** `name : Type (= default)?` */
  private[parsing] def parseBinder[B <: Trees.Binder](s0: ParserState, what: String)(
      mk: (Trees.Binding, Span) => B
  )(using Reporter): Parsed[B] =
    val (s1, nameTok) = expect(s0, TokenKind.LowerIdent, what)
    val (s4, (ty, default)) = parseTypedBinderTail(s1)
    (s4, mk(Trees.Binding(nameTok.lexeme, ty, default), span(nameTok.span, s4.current.span)))

  /** Shared tail of fn decls and cap methods: `(-> TypeExpr)? WithClause? (= FnBody)?`. FnBody is an inline-or-block expression. */
  private[parsing] def parseFnSigTail(s0: ParserState)(using
      Reporter
  ): Parsed[(Option[Tree], Option[Tree], Option[Tree])] =
    val (s1, returnType) = s0.optionally(TokenKind.Arrow)(parseTypeExpr)
    val (s2, withClause) = parseOptionalWithClause(s1)
    val (s3, body) = s2.optionally(TokenKind.Eq)(parseInlineOrBlockExpr)
    (s3, (returnType, withClause, body))

  /** Shared tail of fn params, `of (...)` value params and data fields: `: TypeExpr (= Expr)?`. */
  private[parsing] def parseTypedBinderTail(s0: ParserState)(using Reporter): Parsed[(Tree, Option[Tree])] =
    val (s1, _) = expect(s0, TokenKind.Colon, "`:`")
    val (s2, ty) = parseTypeExpr(s1)
    val (s3, default) = s2.optionally(TokenKind.Eq)(parseExpr)
    (s3, (ty, default))

  private[parsing] def parseOptionalWithClause(s0: ParserState)(using Reporter): Parsed[Option[Tree]] =
    s0.optionally(TokenKind.KwWith): s1 =>
      val (s2, row) = parseEffectRow(s1)
      (s2, Trees.WithClause(row, row.span))

  private[parsing] def parseEffectRow(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, bounds) = parseSepBy1(s0, TokenKind.Plus)(parseEffectBound)
    if bounds.tail.isEmpty then (s1, bounds.head)
    else (s1, Trees.EffectRow(bounds.toList, span(bounds.head.span, bounds.last.span)))

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
  private[parsing] def parseBlock(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, stmts) = parseStatementList(s0, DiagCode.P011, "statement")(parseStatement)
    (s1, Trees.Block(stmts, span(s0.current.span, s1.current.span)))

  /** [[parseMemberList]] in source order. `step` parses one entry, or returns
    * `None` after reporting and recovering from one it rejects.
    */
  extension (s: ParserState)
    private[parsing] inline def memberList[A](inline step: Rule[Option[A]])(using Reporter): Parsed[List[A]] =
      val (s1, items) = parseMemberList(s, List.empty[A]) { (state, acc) =>
        val (state1, item) = step(state)
        (state1, item.fold(acc)(_ :: acc))
      }
      (s1, items.reverse)

  /** The body after the `:` of a `data`, `cap`, `effect` or `satisfies` declaration
    * (Rule 7.1.b): an indented block, or exactly one member on the same line.
    * Anything after that one member, on its line or on indented lines below,
    * is one `P001` and is skipped up to the next token in column 1. These
    * declarations are top-level only, so the skip takes INDENT/DEDENT pairs whole.
    */
  private[parsing] def parseMemberList[A](s0: ParserState, acc0: A)(
      step: (ParserState, A) => Parsed[A]
  )(using reporter: Reporter): Parsed[A] =
    if s0.current.kind == TokenKind.Indent then parseIndentedBody(s0, acc0)(step)
    else
      val (s1, acc) = withAnchors(s0, Anchors.blockBody)(step(_, acc0))
      val src = reporter.source
      val layout = KindSet(TokenKind.Newline, TokenKind.Indent, TokenKind.Dedent)
      def stray(st: ParserState) = st.current.kind != TokenKind.Eof &&
        (layout.contains(st.current.kind) || src.lineColumn(st.current.span.start)._2 > 1)
      @tailrec def skip(st: ParserState, p: ParserState => Boolean): ParserState =
        if p(st) then skip(st.advance, p) else st
      val next = skip(s1, st => layout.contains(st.current.kind))
      if !stray(next) then (s1, acc)
      else
        reportExpected(
          next,
          DiagCode.P001,
          "the end of a one-line body",
          Some("a body on the `:` line holds one member; end the line at `:` and indent each member on its own line")
        )
        (skip(next, stray), acc)

  /** INDENT item (NEWLINE item)* DEDENT, in source order. Each item must end
    * its line; trailing junk is reported as `code` ("unexpected … after
    * `what`"), skipped to the next anchor, and the item wrapped in
    * `Trees.Error`.
    */
  private[parsing] inline def parseStatementList(s0: ParserState, code: DiagCode, what: String)(
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

  /** Fixed has no statements — every block element is an Expr, with two
    * exceptions admissible in any block:
    *   - `fn name(...) = body` — nested local function.
    *   - `prop name: body` — invariant (used in `fn -> cap` bodies).
    */
  private[parsing] def parseStatement(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwProp => parsePropDecl(s)
    case TokenKind.KwFip  => parseFipFn(s)
    case TokenKind.KwFn   => parseFnLike(s, FnKind.Free)
    case _                => parseExpr(s)

  /** DataDecl ::= "data" UPPER_IDENT OfClause? (DataBody | CtorSugar)?
    *   OfClause   ::= "of" (TypeExpr | "(" TypeExpr ("," TypeExpr)* ")")
    *   DataBody   ::= ":" (DataBodyItem | INDENT DataBodyItem+ DEDENT)
    *   CtorSugar  ::= "(" FieldList ")"   — single-variant shorthand
    *
    *   DataBodyItem ::= DataVariant | PropDecl
    *   DataVariant ::= UPPER_IDENT ("(" FieldList ")")?
    *   FieldList   ::= FieldDecl ("," FieldDecl)* ","?
    *   FieldDecl   ::= LOWER_IDENT ":" TypeExpr ("=" Expr)?
    * Without a body the type is empty: no variant, no value (Rule 7.1.a).
    */
  private[parsing] def parseDataDecl(s0: ParserState)(using reporter: Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.KwData, "`data`")
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, "data type name")
    val (s3, ofParams) = parseOptionalDataOfClause(s2)
    if s3.current.kind == TokenKind.LParen then
      /** Single-ctor sugar — synthesise one variant with the type's name. */
      val (s4, fields) = parseFieldList(s3)
      val variant = Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s4.current.span))
      (s4, Trees.DataDecl(nameTok.lexeme, ofParams, List(variant), Nil, span(startTok.span, s4.current.span)))
    else
      val (s4, (variants, props)) = s3.optionalOrEmpty(TokenKind.Colon)(parseDataBody)
      (s4, Trees.DataDecl(nameTok.lexeme, ofParams, variants, props, span(startTok.span, s4.current.span)))

  private[parsing] def parseOptionalDataOfClause(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    accept(s0, TokenKind.KwOf) match
      case (s1, Some(_)) =>
        if s1.current.kind == TokenKind.LParen then
          val (s2, (params, _)) = bracketed(s1, Bracket.Paren)(parseDataOfItem)
          (s2, params)
        else
          /** Single-item form `of T` — just a TypeExpr; no need to
            * disambiguate OfValueParam (which only appears inside the
            * paren list).
            */
          val (s2, ty) = parseTypeExpr(s1)
          (s2, List(ty))
      case (s1, None) => (s1, Nil)

  /** Inside an `of (...)` clause, each item is one of:
    *   - OfValueParam: `name : TypeExpr (= default)?` — lowered to FnParam.
    *   - LiteralExpr: an Int / Float / String / Char / Bool literal
    *     used as a compile-time argument (e.g. `Bounded of (N, 0, 10)`).
    *   - TypeExpr: any of the type forms (incl. NamedAlias `N is C`).
    */
  private[parsing] def parseDataOfItem(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.LowerIdent && s.peek(1).kind == TokenKind.Colon then parseFnParam(s)
    else
      s.current.kind match
        case LiteralStart() =>
          parseAtomExpr(s)
        case _ =>
          parseTypeExpr(s)

  /** Internal accumulator for data-body parsing: variants and props are
    * partitioned at dispatch time so consumers never need to re-classify.
    */
  final private case class DataBodyAcc(
      variants: List[Trees.DataVariant],
      props: List[Trees.PropDecl]
  )

  /** Data bodies admit `DataVariant`s and `PropDecl`s. The parser
    * partitions them into separate lists at dispatch time so consumers
    * never need to re-classify.
    */
  private[parsing] def parseDataBody(
      s0: ParserState
  )(using Reporter): Parsed[(List[Trees.DataVariant], List[Trees.PropDecl])] =
    val (s1, acc) = parseMemberList(s0, DataBodyAcc(Nil, Nil)) { (state, acc) =>
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
    (s1, (acc.variants.reverse, acc.props.reverse))

  private[parsing] def parseDataVariant(s0: ParserState)(using Reporter): Parsed[Trees.DataVariant] =
    val (s1, nameTok) = expect(s0, TokenKind.UpperIdent, "variant name")
    val (s2, fields) = s1.whenAtOrEmpty(TokenKind.LParen)(parseFieldList)
    (s2, Trees.DataVariant(nameTok.lexeme, fields, span(nameTok.span, s2.current.span)))

  /** CapDecl   ::= "cap" UPPER_IDENT ValueParams? OfClause? ExtendsClause?
    *               (":" (CapMember | INDENT CapBody DEDENT))?
    *   ValueParams  ::= "(" FnParam ("," FnParam)* ","? ")"
    *   OfClause     ::= "of" (TypeExpr | "(" OfItem ("," OfItem)* ")")
    *   ExtendsClause::= "extends" CapBound ("+" CapBound)*
    *   CapBody      ::= CapMember+
    *   CapMember    ::= InstanceMethod | StaticMethod | PropDecl
    *
    * ValueParams is the `cap Between(min: N, max: N) of N: ...` sugar
    * form, which desugars to `fn between(min, max) -> cap of N: ...`.
    * Per v0.3: empty bodies omit the `:` entirely (`cap Locked`).
    * `UPPER_IDENT ValueParams?` — the head of `cap` and `type` declarations.
    */
  private[parsing] def parseNameAndValueParams(s1: ParserState, what: String)(using
      Reporter
  ): Parsed[(Token, List[Trees.FnParam])] =
    val (s2, nameTok) = expect(s1, TokenKind.UpperIdent, what)
    val (s3, valueParams) = s2.whenAtOrEmpty(TokenKind.LParen)(parseFnParamList)
    (s3, (nameTok, valueParams))

  /** After `cap` at top level: `Name ValueParams? OfClause? ExtendsClause? (: CapBody)?`. */
  private[parsing] def parseCapDeclRest(s1: ParserState, startTok: Token)(using Reporter): Parsed[Tree] =
    val (s3, (nameTok, valueParams)) = parseNameAndValueParams(s1, "capability name")
    val (s4, ofParams) = parseOptionalDataOfClause(s3)
    val (s5, extendsList) = s4.optionalOrEmpty(TokenKind.KwExtends)(parseCapBoundChain)
    val (s6, body) = s5.optionalOrEmpty(TokenKind.Colon)(parseCapBody)
    (
      s6,
      Trees.CapDecl(nameTok.lexeme, valueParams, ofParams, extendsList, body, span(startTok.span, s6.current.span))
    )

  private[parsing] def parseCapBody(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    s0.memberList { state =>
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

  /** TypeParamsHint ::= "<" UPPER_IDENT ("," UPPER_IDENT)* ","? ">"
    * Per Rule 4.1, Fixed type params are normally introduced at first
    * use; the explicit hint is only required when a method's type
    * parameters can't be inferred from its parameters or return type
    * (e.g. a fold whose result type R appears only in callbacks).
    */
  private[parsing] def parseOptionalTypeParamsHint(s0: ParserState)(using Reporter): Parsed[List[String]] =
    if s0.current.kind != TokenKind.Lt then (s0, Nil)
    else
      val (s1, (params, _)) = bracketedTyped(s0, Bracket.Angle) { st =>
        val (st1, tok) = expect(st, TokenKind.UpperIdent, "type-param name")
        (st1, tok.lexeme)
      }
      (s1, params)

  private[parsing] def parsePropDecl(s0: ParserState)(using Reporter): Parsed[Trees.PropDecl] =
    s0.afterKeyword(TokenKind.KwProp, "`prop`") { (s1, startTok) =>
      val (s2, nameTok) = expect(s1, TokenKind.LowerIdent, "prop name")
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after prop name")
      val (s4, body) = parseExpr(s3)
      (s4, Trees.PropDecl(nameTok.lexeme, body, span(startTok.span, body.span)))
    }

  /** TypeAlias ::= "type" UPPER_IDENT ValueParams? "=" CapBoundChain
    *   ValueParams ::= "(" FnParam ("," FnParam)* ","? ")"
    * The RHS chain is wrapped in `IsBound(caps)` when there are multiple
    * caps; a single-cap RHS is the cap node itself.
    */
  private[parsing] def parseTypeAlias(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwType, "`type`") { (s1, startTok) =>
      val (s3, (nameTok, valueParams)) = parseNameAndValueParams(s1, "type alias name")
      val (s4, _) = expect(s3, TokenKind.Eq, "`=`")
      val (s5, caps) = parseCapBoundChain(s4)
      val rhs =
        if caps.size == 1 then caps.head
        else Trees.IsBound(caps, span(caps.head.span, caps.last.span))
      (s5, Trees.TypeAlias(nameTok.lexeme, valueParams, rhs, span(startTok.span, s5.current.span)))
    }

  /** SatisfiesDecl ::= TypeName "satisfies" CapBound (":" (SatisfiesItem | INDENT SatisfiesItem+ DEDENT))?
    *   TypeName    ::= UPPER_IDENT | LOWER_IDENT          (primitives)
    *   SatisfiesItem ::= ConstructorMapping
    *                  | ImpossibleMapping
    *                  | InstanceMethod | StaticMethod | PropDecl
    *   ConstructorMapping ::= (UPPER_IDENT | "Self") "as" LOWER_IDENT   (Self → SelfMapping)
    *   ImpossibleMapping  ::= "impossible" "as" LOWER_IDENT
    */
  private[parsing] def parseSatisfiesDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
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

  private[parsing] def parseSatisfiesBody(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, items) = parseMemberList(s0, List.empty[Tree]) { (state, acc) =>
      val (state1, item) = parseSatisfiesItem(state)
      (state1, item :: acc)
    }
    (s1, items.reverse)

  /** Head of a `<head> as ctor` satisfies item, paired with the tree it builds
    * from (capCtor, span). `Self` is a head only when followed by `as`;
    * otherwise it starts a `Self.fn` static method.
    */
  private[parsing] object AsMappingHead:
    def unapply(s: ParserState): Option[(String, Span) => Tree] = s.current.kind match
      case TokenKind.KwImpossible => Some(Trees.ImpossibleMapping(_, _))

      /** `Self as ctor` — identity-shape mapping for primitives that are
        * themselves the cap's value (`u64 satisfies Optional of Self: Self as some`).
        */
      case TokenKind.KwSelf if s.peek(1).kind == TokenKind.KwAs => Some(Trees.SelfMapping(_, _))
      case TokenKind.UpperIdent => Some(Trees.ConstructorMapping(s.current.lexeme, _, _))
      case _                    => None

  private[parsing] def parseSatisfiesItem(s: ParserState)(using Reporter): Parsed[Tree] = s match
    case AsMappingHead(mk) =>
      val (s1, head) = consume(s)
      val (s2, _) = expect(s1, TokenKind.KwAs, "`as`")
      val (s3, ctorTok) = expect(s2, TokenKind.LowerIdent, "cap constructor name")
      (s3, mk(ctorTok.lexeme, span(head.span, ctorTok.span)))
    case _ => parseSatisfiesMember(s)

  /** Members shared by cap bodies and satisfies blocks: `fn`, `Self.fn`, `prop`. */
  private[parsing] object MethodMember:
    def unapply(k: TokenKind)(using Reporter): Option[Rule[Tree]] = k match
      case TokenKind.KwFn   => Some(parseFnLike(_, FnKind.Instance))
      case TokenKind.KwSelf => Some(parseFnLike(_, FnKind.Static))
      case TokenKind.KwProp => Some(parsePropDecl)
      case _                => None

  private[parsing] def parseSatisfiesMember(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case MethodMember(p) => p(s)
    case _               =>
      s.errorSync(
        DiagCode.P016,
        "a satisfies item",
        Some("items are `Variant as ctor`, `impossible as ctor`, `fn ...`, `Self.fn ...`, or `prop ...`")
      )

  /** EffectDecl ::= "linear"? "effect" UPPER_IDENT OfClause? (":" (EffectMember | INDENT EffectMember+ DEDENT))?
    *   EffectMember ::= "fn" LOWER_IDENT FnParamList? ("->" TypeExpr)? WithClause?
    * (Effect ops never have bodies; their semantics are supplied by handlers.)
    */
  private[parsing] def parseEffectDecl(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, linOpt) = accept(s0, TokenKind.KwLinear)
    val isLinear = linOpt.isDefined
    val (s2, _) = expect(s1, TokenKind.KwEffect, "`effect`")
    val (s3, nameTok) = expect(s2, TokenKind.UpperIdent, "effect name")
    val (s4, ofParams) = parseOptionalDataOfClause(s3)
    val (s5, members) = s4.optionalOrEmpty(TokenKind.Colon)(parseEffectMembers)
    (s5, Trees.EffectDecl(isLinear, nameTok.lexeme, ofParams, members, span(s0.current.span, s5.current.span)))

  private[parsing] def parseEffectMembers(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    s0.memberList { state =>
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

  private[parsing] def parseModDecl(s: ParserState)(using Reporter): Parsed[Tree] =
    unsupported(s, "`mod` declaration")

end DeclParsers
