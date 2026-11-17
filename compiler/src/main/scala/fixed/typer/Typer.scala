package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Proto, SymKind, SymbolTable, Type, render}
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** A tree's identity in the type side table: its span and node class (§3.0 span-as-identity).
  * Cheap to hash, unlike the tree itself, whose structural hash walks the whole subtree.
  */
final case class TreeKey(start: Int, end: Int, node: Class[?])

object TreeKey:
  def of(tree: Tree): TreeKey = TreeKey(tree.span.start, tree.span.end, tree.getClass)

/** Threaded typer state (`docs/plans/phase-3-typer.md` §3.4): the type side table, the symbol
  * table (local bindings join it), the unifier and the diagnostics so far (newest first).
  */
final case class TyperState(
    types: Map[TreeKey, Type],
    symbols: SymbolTable,
    unifier: Unifier,
    diags: List[Diagnostic]
):
  def record(tree: Tree, t: Type): TyperState = copy(types = types.updated(TreeKey.of(tree), t))

  /** The recorded type of `tree`. */
  def typeOf(tree: Tree): Option[Type] = types.get(TreeKey.of(tree))

  /** Report an error once: a prototype re-checked by an enclosing node repeats the same finding. */
  def error(code: DiagCode, span: Span, message: String): TyperState =
    val d = Diagnostic(Severity.Error, code, span, message)
    if diags.contains(d) then this else copy(diags = d :: diags)

  /** A construct the typer does not handle yet (`T005`): a gap in the typer, not in the program. */
  def unsupported(span: Span, what: String): TyperState =
    copy(diags = Diagnostic(Severity.Warning, DiagCode.T005, span, s"$what is not supported by the typer yet") :: diags)

  /** Default unsolved literal variables, zonk every recorded type and order the diagnostics. */
  def finish: TyperState =
    val u = unifier.applyDefaults
    copy(types = types.view.mapValues(u.zonk).toMap, unifier = u, diags = diags.reverse)

object TyperState:
  def apply(symbols: SymbolTable): TyperState = TyperState(Map.empty, symbols, Unifier.empty, Nil)

/** The bidirectional body typer (§5.2). M3 covers literals, identifiers, arithmetic, comparison and
  * logic operators, tuples, function application and `let` in blocks; other expressions report
  * `T005` until M5. Each routine records the tree's type in the side table and returns it.
  */
object Typer:

  private val i64 = Type.PrimType("i64")
  private val f64 = Type.PrimType("f64")
  private val bool = Type.PrimType("bool")

  /** Type an expression against prototype `pt`. */
  def typedExpr(s: TyperState, tree: Tree, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val (s1, t) = tree match
      case _: Trees.IntLit           => literal(s, i64)
      case _: Trees.FloatLit         => literal(s, f64)
      case _: Trees.StringLit        => (s, ctx.scope.get("String").fold(Type.PrimType("String"))(Type.TypeRef(_, Nil)))
      case _: Trees.CharLit          => (s, Type.PrimType("char"))
      case _: Trees.BoolLit          => (s, bool)
      case _: Trees.UnitLit          => (s, Type.UnitType)
      case Trees.Ident(name, span)   => typedIdent(s, name, span)
      case Trees.TupleExpr(elems, _) =>
        val (s2, ts) = typedAll(s, elems)
        (s2, Type.TupleType(ts))
      case t: Trees.BinOp                    => typedBinOp(s, t, pt)
      case t: Trees.UnaryOp                  => typedUnaryOp(s, t, pt)
      case t: Trees.Apply                    => typedApply(s, t)
      case Trees.Block(stmts, _)             => typedBlock(s, stmts, pt)
      case t: Trees.LetExpr                  => (typedLet(s, t)._1, Type.UnitType)
      case _: Trees.Error | _: Trees.Missing => (s, Type.NeverType)
      case t: Trees.IfExpr                   => typedIf(s, t, pt)
      case t: Trees.LambdaExpr               => typedLambda(s, t, pt)
      case t: Trees.MethodCall               => typedMethodCall(s, t)
      case t: Trees.StaticCall               => typedStaticCall(s, t, pt)
      case other => (s.unsupported(other.span, other.getClass.getSimpleName), Type.NeverType)
    (check(s1, tree.span, t, pt).record(tree, t), t)

  /** Reconcile an inferred type with the prototype. */
  private def check(s: TyperState, span: Span, t: Type, pt: Proto): TyperState =
    pt match
      case Proto.Wildcard        => s
      case Proto.ExpectedType(e) => unify(s, span, e, t)

  private def unify(s: TyperState, span: Span, expected: Type, actual: Type): TyperState =
    solve(
      s,
      span,
      s.unifier.unify(expected, actual, isNumeric(s, _))
    )(s"expected ${show(s, expected)}, got ${show(s, actual)}")

  /** Adopt the unifier from `result`, or report `T002` with `message`. */
  private def solve(s: TyperState, span: Span, result: Either[?, Unifier])(message: => String): TyperState =
    result.fold(_ => s.error(DiagCode.T002, span, message), u => s.copy(unifier = u))

  private def show(s: TyperState, t: Type): String = s.symbols.render(s.unifier.zonk(t))

  /** Numeric literals are polymorphic (`spec/type_system.md`): a fresh `Numeric` variable defaulting to `default`. */
  private def literal(s: TyperState, default: Type): (TyperState, Type) =
    val (u, v) = s.unifier.fresh(numeric = true, default = Some(default))
    (s.copy(unifier = u), v)

  private def isNumeric(s: TyperState, t: Type): Boolean = Members.isNumeric(s, t)

  private def typedIdent(s: TyperState, name: String, span: Span)(using ctx: Context): (TyperState, Type) =
    ctx.scope.get(name) match
      case None     => (s.error(DiagCode.T001, span, s"not found: $name"), Type.NeverType)
      case Some(id) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.ValueParam | SymKind.Local => (s, d.info)
          case SymKind.Fn                         => Members.instantiate(s, d.info, Members.Subst(Map.empty, Set(id)))
          case _ => (s.error(DiagCode.T008, span, s"$name is not a value"), Type.NeverType)

  private def typedAll(s: TyperState, trees: List[Tree])(using Context): (TyperState, List[Type]) =
    val (s1, acc) = trees.foldLeft((s, List.empty[Type])) { case ((si, acc0), t) =>
      val (si1, ty) = typedExpr(si, t, Proto.Wildcard)
      (si1, ty :: acc0)
    }
    (s1, acc.reverse)

  private def typedBinOp(s: TyperState, t: Trees.BinOp, pt: Proto)(using Context): (TyperState, Type) =
    import Trees.BinOperator.*
    t.op match
      case Add | Sub | Mul | Div | Mod =>
        val (s1, l) = typedExpr(s, t.lhs, pt)
        val s2 = numeric(s1, t.lhs.span, l)
        val rhsProto = if s2.diags.size > s1.diags.size then Proto.Wildcard else Proto.ExpectedType(l)
        (typedExpr(s2, t.rhs, rhsProto)._1, l)
      case Lt | Le | Gt | Ge | Eq | Neq =>
        val (s1, l) = typedExpr(s, t.lhs, Proto.Wildcard)
        val (s2, _) = typedExpr(s1, t.rhs, Proto.ExpectedType(l))
        (s2, bool)
      case And | Or =>
        val (s1, _) = typedExpr(s, t.lhs, Proto.ExpectedType(bool))
        val (s2, _) = typedExpr(s1, t.rhs, Proto.ExpectedType(bool))
        (s2, bool)

  private def typedUnaryOp(s: TyperState, t: Trees.UnaryOp, pt: Proto)(using Context): (TyperState, Type) =
    t.op match
      case Trees.UnaryOperator.Neg =>
        val (s1, ty) = typedExpr(s, t.operand, pt)
        (numeric(s1, t.operand.span, ty), ty)
      case Trees.UnaryOperator.Not =>
        val (s1, _) = typedExpr(s, t.operand, Proto.ExpectedType(bool))
        (s1, bool)

  private def numeric(s: TyperState, span: Span, t: Type): TyperState =
    solve(s, span, s.unifier.requireNumeric(t, isNumeric(s, _)))(s"expected a Numeric operand, got ${show(s, t)}")

  private def typedApply(s: TyperState, t: Trees.Apply)(using Context): (TyperState, Type) =
    val (s1, fnType) = typedExpr(s, t.fn, Proto.Wildcard)
    applyArgs(s1, fnType, Some(t.args), t.span)

  /** Apply a function type to `args`; `None` is a member selected without `(...)`, which reads a
    * zero-parameter member.
    */
  private def applyArgs(s: TyperState, fnType: Type, args: Option[List[Tree]], span: Span)(using
      Context
  ): (TyperState, Type) =
    (s.unifier.resolve(fnType), args) match
      case (Type.ArrowType(Type.UnitType, rhs, _), None) => (s, rhs)
      case (other, None)                                 => (s, other)
      case (Type.ArrowType(lhs, rhs, _), Some(as))       =>
        val params = Members.params(lhs)
        if params.size != as.size then
          (s.error(DiagCode.T006, span, s"expected ${params.size} argument(s), got ${as.size}"), rhs)
        else (params.zip(as).foldLeft(s) { case (si, (p, a)) => typedExpr(si, a, Proto.ExpectedType(p))._1 }, rhs)
      case (Type.NeverType, _) => (s, Type.NeverType)
      case (other, _)          => (s.error(DiagCode.T007, span, s"${show(s, other)} is not a function"), Type.NeverType)

  /** `if c: a else: b` — `c` is `bool`; both branches meet the prototype (or the first branch's type). */
  private def typedIf(s: TyperState, t: Trees.IfExpr, pt: Proto)(using Context): (TyperState, Type) =
    val (s1, _) = typedExpr(s, t.cond, Proto.ExpectedType(bool))
    val (s2, thenType) = typedExpr(s1, t.thenBranch, pt)
    val elseProto = pt match
      case Proto.Wildcard => Proto.ExpectedType(thenType)
      case expected       => expected
    (typedExpr(s2, t.elseBranch, elseProto)._1, thenType)

  /** A lambda. Against an expected function type of the same arity, parameters take its parameter
    * types and the body its result type; otherwise they are fresh variables.
    */
  private def typedLambda(s: TyperState, t: Trees.LambdaExpr, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val expected = pt match
      case Proto.ExpectedType(e) => s.unifier.resolve(e)
      case Proto.Wildcard        => Type.NeverType
    val (expectedParams, expectedResult) = expected match
      case Type.ArrowType(lhs, rhs, _) =>
        val ps = Members.params(lhs)
        (Option.when(ps.size == t.params.size)(ps), Some(rhs))
      case _ => (None, None)
    val (s1, inner, paramTypes) = t.params.zipWithIndex.foldLeft((s, ctx, List.empty[Type])) {
      case ((si, ci, acc), (p, i)) =>
        val (si1, declared) = expectedParams.map(_(i)) match
          case Some(e) => (si, e)
          case None    =>
            val (u, v) = si.unifier.fresh(numeric = false, default = None)
            (si.copy(unifier = u), v)
        val si2 = p.typeAnn.fold(si1) { ann =>
          val (table, annotated, diags) = Namer.translate(si1.symbols, ci.scope, ci.owner, ann)
          unify(si1.copy(symbols = table, diags = diags.reverse ++ si1.diags), p.span, annotated, declared)
        }
        val (table, id) = si2.symbols.enter(p.name, ci.owner, SymKind.Local, declared, p.span)
        (si2.copy(symbols = table).record(p, declared), ci.bind(p.name, id), acc :+ declared)
    }
    val bodyProto = expectedResult.fold(Proto.Wildcard)(Proto.ExpectedType(_))
    val (s2, bodyType) = typedExpr(s1, t.body, bodyProto)(using inner)

    /** The body was already checked against an expected result; report its mismatch once. */
    val result = expectedResult.getOrElse(bodyType)
    (s2, Type.ArrowType(Members.product(paramTypes), result, fixed.core.EffectRow.empty))

  /** `recv.m(args)` / `recv.m`: an instance member of the receiver's type. */
  private def typedMethodCall(s: TyperState, t: Trees.MethodCall)(using Context): (TyperState, Type) =
    val (s1, recv) = typedExpr(s, t.receiver, Proto.Wildcard)
    s1.unifier.resolve(recv) match
      case Type.NeverType  => (s1, Type.NeverType)
      case _: Type.TypeVar => (s1.unsupported(t.span, s"`.${t.method}` on a receiver of unknown type"), Type.NeverType)
      case resolved        =>
        Members.instance(s1, resolved, t.method) match
          case Some((s2, member)) => applyArgs(s2, member, t.args, t.span)
          case None               =>
            (s1.error(DiagCode.T001, t.span, s"not found: member ${t.method} of ${show(s1, resolved)}"), Type.NeverType)

  /** `T.m(args)` / `T.m`: an effect operation, a data constructor or derived `unfold`. */
  private def typedStaticCall(s: TyperState, t: Trees.StaticCall, pt: Proto)(using ctx: Context): (TyperState, Type) =
    ctx.scope.get(t.qualifier) match
      case None        => (s.error(DiagCode.T001, t.span, s"not found: ${t.qualifier}"), Type.NeverType)
      case Some(owner) =>
        val layer = pt match
          case Proto.ExpectedType(e) =>
            s.unifier.resolve(e) match
              case l: Type.Layer => Some(l)
              case _             => None
          case Proto.Wildcard => None
        val kind = s.symbols(owner).kind
        Members.static(s, owner, t.method, layer) match
          case Some((s1, member))                                     => applyArgs(s1, member, t.args, t.span)
          case None if kind == SymKind.Effect || kind == SymKind.Data =>
            (s.error(DiagCode.T001, t.span, s"not found: ${t.qualifier}.${t.method}"), Type.NeverType)
          case None => (s.unsupported(t.span, s"`${t.qualifier}.${t.method}`"), Type.NeverType)

  /** Statements in order; `let` extends the scope of what follows. The block's type is its last
    * expression's, or `()` when it ends in a `let` or is empty.
    */
  private def typedBlock(s: TyperState, stmts: List[Tree], pt: Proto)(using ctx: Context): (TyperState, Type) =
    val (s1, _, last) = stmts.zipWithIndex.foldLeft((s, ctx, Type.UnitType: Type)) { case ((si, ci, _), (stmt, i)) =>
      stmt match
        case let: Trees.LetExpr =>
          val (si1, ci1) = typedLet(si, let)(using ci)
          (si1.record(let, Type.UnitType), ci1, Type.UnitType)
        case e =>
          val expect = if i == stmts.size - 1 then pt else Proto.Wildcard
          val (si1, ty) = typedExpr(si, e, expect)(using ci)
          (si1, ci, ty)
    }
    (s1, last)

  /** `let pat = init`: types `init` and binds the pattern's names. */
  private def typedLet(s: TyperState, let: Trees.LetExpr)(using ctx: Context): (TyperState, Context) =
    val (s1, t) = typedExpr(s, let.init, Proto.Wildcard)
    bindPattern(s1, let.pattern, t)

  /** Bind a `let` pattern against type `t`: binders, wildcards and tuples (M3); the rest join with
    * pattern typing (M5).
    */
  private def bindPattern(s: TyperState, pat: Tree, t: Type)(using ctx: Context): (TyperState, Context) =
    pat match
      case Trees.WildcardPat(_)        => (s.record(pat, t), ctx)
      case Trees.BinderPat(name, span) =>
        val (table, id) = s.symbols.enter(name, ctx.owner, SymKind.Local, t, span)
        (s.copy(symbols = table).record(pat, t), ctx.bind(name, id))
      case Trees.TuplePat(elems, span) =>
        val (u, vars) = elems.foldLeft((s.unifier, List.empty[Type])) { case ((u0, acc), _) =>
          val (u1, v) = u0.fresh(numeric = false, default = None)
          (u1, v :: acc)
        }
        val tuple = Type.TupleType(vars.reverse)
        val s1 = unify(s.copy(unifier = u), span, tuple, t).record(pat, tuple)
        elems.zip(vars.reverse).foldLeft((s1, ctx)) { case ((si, ci), (p, v)) => bindPattern(si, p, v)(using ci) }
      case other => (s.unsupported(other.span, "this let pattern"), ctx)
