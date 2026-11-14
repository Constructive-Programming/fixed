package fixed.parsing

import scala.annotation.tailrec

import cats.Monoid

import Parser.*

/** Expressions and patterns: operators, application, `if` / `match` / `do` / `handle`, lambdas, literals. */
private[parsing] object ExprParsers:
  import DeclParsers.*
  import TypeParsers.*

  def parseExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.KwLet => parseLetExpr(s)
    case _               => parsePipeExpr(s)

  private[parsing] def parseLetExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwLet, "`let`") { (s1, startTok) =>
      val (s2, pat) = parsePattern(s1)
      val (s3, _) = expect(s2, TokenKind.Eq, "`=`")
      val (s4, init) = parsePipeExpr(s3)
      (s4, Trees.LetExpr(pat, init, span(startTok.span, init.span)))
    }

  /** operand (op operand)*, folded left. `opOf` picks out this level's
    * operators; `mk` builds the node.
    */
  private[parsing] inline def parseLeftAssoc[Op](s0: ParserState)(inline operand: Rule[Tree])(
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

  private[parsing] def parsePipeExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseOrExpr) {
      case TokenKind.PipeForward => Some(())
      case _                     => None
    }((_, lhs, rhs, sp) => Trees.Pipe(lhs, rhs, sp))

  private[parsing] def parseOrExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseAndExpr) {
      case TokenKind.OrOr => Some(Trees.BinOperator.Or)
      case _              => None
    }(Trees.BinOp.apply)

  private[parsing] def parseAndExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseCmpExpr) {
      case TokenKind.AndAnd => Some(Trees.BinOperator.And)
      case _                => None
    }(Trees.BinOp.apply)

  /** Non-chaining: at most one comparison operator per expression.
    * `a < b < c` parses as `(a < b) < c`'s LHS rejected by the typer
    * (no `<` at boolean type), not as `a < b && b < c`. The grammar
    * marks CmpExpr non-associative; that's why this production has no
    * outer loop, unlike its peers `parseAddExpr` / `parseMulExpr`.
    */
  private[parsing] def parseCmpExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
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

  private[parsing] def parseAddExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseMulExpr) {
      case TokenKind.Plus  => Some(Trees.BinOperator.Add)
      case TokenKind.Minus => Some(Trees.BinOperator.Sub)
      case _               => None
    }(Trees.BinOp.apply)

  private[parsing] def parseMulExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    parseLeftAssoc(s0)(parseUnaryExpr) {
      case TokenKind.Star    => Some(Trees.BinOperator.Mul)
      case TokenKind.Slash   => Some(Trees.BinOperator.Div)
      case TokenKind.Percent => Some(Trees.BinOperator.Mod)
      case _                 => None
    }(Trees.BinOp.apply)

  private[parsing] object UnaryPrefix:
    def unapply(k: TokenKind): Option[Trees.UnaryOperator] = k match
      case TokenKind.Minus => Some(Trees.UnaryOperator.Neg)
      case TokenKind.Bang  => Some(Trees.UnaryOperator.Not)
      case _               => None

  private[parsing] def parseUnaryExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case UnaryPrefix(op) =>
      val (s1, tok) = consume(s)
      val (s2, operand) = parseAppExpr(s1)
      (s2, Trees.UnaryOp(op, operand, span(tok.span, operand.span)))
    case _ => parseAppExpr(s)

  private[parsing] def parseAppExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
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
            /** `T.member(...)`, or bare `T.Variant` / static-method-no-args
              * (`args` = None); the typer disambiguates.
              */
            case Trees.Ident(qual, qspan) if qual.nonEmpty && qual.charAt(0).isUpper =>
              Trees.StaticCall(qual, selection, span(qspan, st3.current.span))
            case _ =>
              Trees.MethodCall(receiver, selection, span(receiver.span, st3.current.span))
          loop(st3, next)
        case _ => (st, receiver)
    loop(s1, recv)

  private[parsing] def parseArgList(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, (args, _)) = bracketed(s0, Bracket.Paren)(parseExpr)
    (s1, args)

  private[parsing] def parseAtomExpr(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
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

      /** `T { field: value, ... }` — struct literal for single-variant
        * data types (`data Point(x: N, y: N)`-style).
        */
      if s1.current.kind == TokenKind.LBrace then parseStructLit(s1, tok)
      else (s1, Trees.Ident(tok.lexeme, tok.span))
    case _ =>
      val (s1, sp) = s.errorSkip(DiagCode.P007, "an expression")
      (s1, Trees.Error(Nil, sp))

  private[parsing] def parseIfExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwIf, "`if`") { (s1, startTok) =>
      val (s2, cond) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after if condition")
      val (s4, thenBranch) = parseInlineOrBlockExpr(s3)
      val s5 = skipNewlines(s4)
      val (s6, _) = expect(s5, TokenKind.KwElse, "`else`")

      /** `else if cond: ...` chains the next conditional as the else branch
        * — a right-associative if/else-if/else cascade. `else: ...` is the
        * base case.
        */
      val (s7, elseBranch) =
        if s6.current.kind == TokenKind.KwIf then parseIfExpr(s6)
        else
          val (s6a, _) = expect(s6, TokenKind.Colon, "`:` after `else`")
          parseInlineOrBlockExpr(s6a)
      (s7, Trees.IfExpr(cond, thenBranch, elseBranch, span(startTok.span, elseBranch.span)))
    }

  /** MatchExpr ::= "match" Expr ":" INDENT MatchCase+ DEDENT
    * MatchCase ::= OrPattern ("if" Expr)? "=>" InlineOrBlockExpr
    */
  private[parsing] def parseMatchExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwMatch, "`match`") { (s1, startTok) =>
      val (s2, scrutinee) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after match scrutinee")
      val (s4, cases) = parseMatchCases(s3)
      (s4, Trees.MatchExpr(scrutinee, cases, span(startTok.span, s4.current.span)))
    }

  private[parsing] def parseMatchCases(s0: ParserState)(using Reporter): Parsed[List[Trees.MatchCase]] =
    val (s1, cases) = parseIndentedBody(s0, List.empty[Trees.MatchCase]) { (st, acc) =>
      val (st1, matchCase) = parseMatchCase(st)
      (st1, matchCase :: acc)
    }
    (s1, cases.reverse)

  private[parsing] def parseMatchCase(s0: ParserState)(using Reporter): Parsed[Trees.MatchCase] =
    val (s1, pat) = parseOrPattern(s0)
    val (s2, guard) = s1.optionally(TokenKind.KwIf)(parseExpr)
    val (s3, _) = expect(s2, TokenKind.FatArrow, "`=>` in match case")
    val (s4, body) = parseInlineOrBlockExpr(s3)
    (s4, Trees.MatchCase(pat, guard, body, span(pat.span, body.span)))

  private[parsing] def parseOrPattern(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, head) = parsePattern(s0)
    @tailrec
    def loop(st: ParserState, acc: Tree): Parsed[Tree] =
      accept(st, TokenKind.Pipe) match
        case (st1, Some(_)) =>
          val (st2, rhs) = parsePattern(st1)
          loop(st2, Trees.OrPat(acc, rhs, span(acc.span, rhs.span)))
        case (st1, None) => (st1, acc)
    loop(s1, head)

  /** DoExpr ::= "do" ":" INDENT DoStmt+ DEDENT
    * DoStmt ::= Pattern "<-" Expr   (DoBind)
    *          | Expr
    */
  private[parsing] def parseDoExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwDo, "`do`") { (s1, startTok) =>
      val (s2, _) = expect(s1, TokenKind.Colon, "`:` after `do`")
      val (s3, stmts) = parseDoStmts(s2)
      (s3, Trees.DoExpr(stmts, span(startTok.span, s3.current.span)))
    }

  private[parsing] def parseDoStmts(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    parseStatementList(s0, DiagCode.P018, "do statement")(parseDoStmt)

  /** `pat <- expr` bind, or a plain expression. */
  private[parsing] def parseDoStmt(s: ParserState)(using Reporter): Parsed[Tree] =
    if isDoBindAhead(s) then
      val (s1, pat) = parsePattern(s)
      val (s2, _) = expect(s1, TokenKind.BackArrow, "`<-`")
      val (s3, rhs) = parseExpr(s2)
      (s3, Trees.DoBind(pat, rhs, span(pat.span, rhs.span)))
    else parseExpr(s)

  /** True iff the current statement contains a top-level `<-` before
    * its terminating NEWLINE/DEDENT/EOF — i.e. it's a `pat <- expr`
    * bind rather than a plain expression. Tracks paren/bracket/brace
    * depth so a `<-` inside a nested expression is not mistaken for
    * the bind arrow.
    */
  /** Opening / closing bracket kinds, for depth-tracking lookahead. */
  private[parsing] object Opener:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.LParen | TokenKind.LBracket | TokenKind.LBrace => true
      case _                                                        => false

  /** Token kinds that begin a literal (pattern or compile-time `of` argument). */
  private[parsing] object LiteralStart:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.IntLit | TokenKind.FloatLit | TokenKind.StringLit | TokenKind.CharLit | TokenKind.KwTrue |
          TokenKind.KwFalse => true
      case _ => false

  private[parsing] object Closer:
    def unapply(k: TokenKind): Boolean = k match
      case TokenKind.RParen | TokenKind.RBracket | TokenKind.RBrace => true
      case _                                                        => false

  private[parsing] def isDoBindAhead(s: ParserState): Boolean =
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

  /** ForallExpr ::= "forall" "(" FnParam ("," FnParam)* ")"
    *                ("suchThat" Expr)? "->" InlineOrBlockExpr
    */
  private[parsing] def parseForallExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwForall, "`forall`") { (s1, startTok) =>
      val (s2, binders) = parseFnParamList(s1)
      val (s3, suchThat) = s2.optionally(TokenKind.KwSuchThat)(parseExpr)
      val (s4, _) = expect(s3, TokenKind.Arrow, "`->` after forall binders")
      val (s5, body) = parseInlineOrBlockExpr(s4)
      (s5, Trees.ForallExpr(binders, suchThat, body, span(startTok.span, body.span)))
    }

  /** HandleExpr ::= "handle" Expr ":" INDENT HandlerClause+ DEDENT
    * HandlerClause ::= OperationClause | ReturnClause
    *   OperationClause ::= UPPER_IDENT "." LOWER_IDENT ("(" PatternList ")")? "=>" Body
    *   ReturnClause ::= "return" "(" Pattern ")" "=>" Body
    * The optional return clause is partitioned out at parse time; multiple
    * return clauses are admitted by the grammar but only the last one
    * survives (well-formed programs have at most one).
    */
  private[parsing] def parseHandleExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    s0.afterKeyword(TokenKind.KwHandle, "`handle`") { (s1, startTok) =>
      val (s2, subject) = parseExpr(s1)
      val (s3, _) = expect(s2, TokenKind.Colon, "`:` after handle subject")
      val (s4, operationClauses, returnClause) = parseHandlerClauses(s3)
      (s4, Trees.HandleExpr(subject, operationClauses, returnClause, span(startTok.span, s4.current.span)))
    }

  /** Internal accumulator for handler-clause parsing: we collect normal
    * operation clauses and (possibly) a single return clause in lockstep so the body
    * loop stays a single fold over `parseIndentedBody`.
    */
  final private case class HandlerAcc(
      operationClauses: List[Trees.OperationClause],
      returnClause: Option[Trees.ReturnClause]
  )

  private[parsing] def parseHandlerClauses(
      s0: ParserState
  )(using Reporter): (ParserState, List[Trees.OperationClause], Option[Trees.ReturnClause]) =
    val (s1, acc) = parseIndentedBody(s0, HandlerAcc(Nil, None)) { (st, acc) =>
      val (st1, parsed) = parseHandlerClause(st)
      val acc1 = parsed match
        case ra: Trees.ReturnClause    => acc.copy(returnClause = Some(ra))
        case ha: Trees.OperationClause => acc.copy(operationClauses = ha :: acc.operationClauses)

        /** recovery; diagnostic already emitted */
        case _: Trees.Error => acc
        case _              => acc
      (st1, acc1)
    }
    (s1, acc.operationClauses.reverse, acc.returnClause)

  private[parsing] def parseHandlerClause(s: ParserState)(using Reporter): Parsed[Tree] =
    val startSpan = s.current.span
    s.current.kind match
      case TokenKind.KwReturn =>
        val s1 = s.advance
        val (s2, _) = expect(s1, TokenKind.LParen, "`(`")
        val (s3, pat) = parsePattern(s2)
        val (s4, _) = expect(s3, TokenKind.RParen, "`)`")
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after return clause pattern")
        val (s6, body) = parseInlineOrBlockExpr(s5)
        (s6, Trees.ReturnClause(pat, body, span(startSpan, body.span)))
      case TokenKind.UpperIdent =>
        val (s1, effectTok) = consume(s)
        val (s2, _) = expect(s1, TokenKind.Dot, "`.`")
        val (s3, opTok) = expect(s2, TokenKind.LowerIdent, "effect op name")
        val (s4, params) = s3.whenAtOrEmpty(TokenKind.LParen)(parseInnerPatternList)
        val (s5, _) = expect(s4, TokenKind.FatArrow, "`=>` after effect op")
        val (s6, body) = parseInlineOrBlockExpr(s5)
        (s6, Trees.OperationClause(effectTok.lexeme, opTok.lexeme, params, body, span(startSpan, body.span)))
      case _ =>
        s.errorSync(
          DiagCode.P019,
          "a handler clause",
          Some("each clause is `Effect.op(...) => body` or `return(p) => body`")
        )

  /** A branch body that's either a same-line expression or an INDENTed block. */
  private[parsing] def parseInlineOrBlockExpr(s: ParserState)(using Reporter): Parsed[Tree] =
    if s.current.kind == TokenKind.Indent then parseBlock(s)
    else parseExpr(s)

  private[parsing] def parseListExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, (elements, sp)) = bracketed(s0, Bracket.Square)(parseExpr)
    (s1, Trees.ListExpr(elements, sp))

  /** StructLit ::= UPPER_IDENT "{" Field ("," Field)* ","? "}"
    *   Field   ::= LOWER_IDENT ":" Expr
    * The leading `UPPER_IDENT` has already been consumed; `typeNameTok`
    * carries it.
    */
  private[parsing] def parseStructLit(
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
  private[parsing] def parseParenOrLambdaOrTuple(s: ParserState)(using Reporter): Parsed[Tree] =
    if lookaheadIsLambda(s) then parseLambdaExpr(s)
    else parseParenExpr(s)

  /** Find the matching `)` for the `(` at `current` and check whether the
    * next non-NEWLINE token after it is `->`. */
  private[parsing] def lookaheadIsLambda(s: ParserState): Boolean =
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
      /** Skip a NEWLINE between `)` and `->` (multi-line lambda). */
      val afterIdx = found + 1
      val nextKind =
        if s.peek(afterIdx).kind == TokenKind.Newline then s.peek(afterIdx + 1).kind
        else s.peek(afterIdx).kind
      nextKind == TokenKind.Arrow

  private[parsing] def parseLambdaExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, (params, sp)) = bracketedTyped(s0, Bracket.Paren)(parseLambdaParam)
    val (s2, _) = expect(s1, TokenKind.Arrow, "`->`")
    val (s3, body) = parseInlineOrBlockExpr(s2)
    (s3, Trees.LambdaExpr(params, body, span(sp, body.span)))

  private[parsing] def parseLambdaParam(s0: ParserState)(using Reporter): Parsed[Trees.LambdaParam] =
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

  private[parsing] def parseParenExpr(s0: ParserState)(using Reporter): Parsed[Tree] =
    val (s1, startTok) = expect(s0, TokenKind.LParen, "`(`")
    val (s2, p) = parseParenBody(s1, startTok.span)(parseExpr)
    p match
      case Paren.Empty(sp)    => (s2, Trees.UnitLit(sp))
      case Paren.One(e)       => (s2, e)
      case Paren.Many(es, sp) => (s2, Trees.TupleExpr(es, sp))

  private[parsing] def parsePattern(s: ParserState)(using Reporter): Parsed[Tree] = s.current.kind match
    case TokenKind.Underscore =>
      val (s1, tok) = consume(s)
      (s1, Trees.WildcardPat(tok.span))
    case TokenKind.LowerIdent =>
      val (s1, tok) = consume(s)
      (s1, Trees.BinderPat(tok.lexeme, tok.span))
    case TokenKind.UpperIdent =>
      /** Forms:
        *   `T.Variant(p1, ...)`  qualified data-variant pattern
        *   `T.Variant`           qualified unit-variant pattern
        *   `T(p1, ...)`          single-ctor sugar pattern (typical for
        *                         `data Point(x: N, y: N)`-style decls)
        *   `T`                   no-qualifier variant pattern (Rule 3.4.a)
        */
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
      /** Tuple pattern. */
      val (s1, (elements, sp)) = bracketed(s, Bracket.Paren)(parsePattern)
      (s1, Trees.TuplePat(elements, sp))
    case LiteralStart() =>
      val sp = s.current.span
      val (s1, lit) = parseAtomExpr(s)
      (s1, Trees.LiteralPat(lit, sp))
    case _ =>
      val (s1, sp) = s.errorSkip(DiagCode.P010, "a pattern")
      (s1, Trees.WildcardPat(sp))

  private[parsing] def parseInnerPatternList(s0: ParserState)(using Reporter): Parsed[List[Tree]] =
    val (s1, (pats, _)) = bracketed(s0, Bracket.Paren)(parsePattern)
    (s1, pats)

end ExprParsers
