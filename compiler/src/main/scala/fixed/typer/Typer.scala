package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Proto, SymKind, SymbolId, SymbolTable, Type, render}
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Threaded typer state (`docs/plans/phase-3-typer.md` §3.4): the type side table keyed by tree
  * (§3.0 span-as-identity), the symbol table (local bindings join it), the unifier and the
  * diagnostics so far (newest first).
  */
final case class TyperState(
    types: Map[Tree, Type],
    symbols: SymbolTable,
    unifier: Unifier,
    diags: List[Diagnostic]
):
  def record(tree: Tree, t: Type): TyperState = copy(types = types.updated(tree, t))

  def error(code: DiagCode, span: Span, message: String): TyperState =
    copy(diags = Diagnostic(Severity.Error, code, span, message) :: diags)

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

  private val numericPrims = Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64")
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
      case other => (s.error(DiagCode.T005, other.span, "not supported by the typer yet"), Type.NeverType)
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

  /** A primitive numeric type, or a type variable whose bound names `Numeric`. Transitive
    * `extends Numeric` joins with capability closure (M6).
    */
  private def isNumeric(s: TyperState, t: Type): Boolean =
    t match
      case Type.PrimType(n)    => numericPrims(n)
      case Type.TypeRef(id, _) =>
        s.symbols(id).info match
          case Type.IsBound(bs) => bs.exists {
              case Type.CapRef(c, _) => s.symbols(c).name == "Numeric"
              case _                 => false
            }
          case _ => false
      case _ => false

  private def typedIdent(s: TyperState, name: String, span: Span)(using ctx: Context): (TyperState, Type) =
    ctx.scope.get(name) match
      case None     => (s.error(DiagCode.T001, span, s"not found: $name"), Type.NeverType)
      case Some(id) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.ValueParam | SymKind.Local => (s, d.info)
          case SymKind.Fn                         => instantiate(s, id, d.info)
          case _ => (s.error(DiagCode.T008, span, s"$name is not a value"), Type.NeverType)

  /** Replace the type variables a function's signature introduced with fresh unification variables. */
  private def instantiate(s: TyperState, fn: SymbolId, t: Type): (TyperState, Type) =
    val params = typeParams(s, fn, t).distinct
    val (u, fresh) = params.foldLeft((s.unifier, Map.empty[SymbolId, Type])) { case ((u0, m), p) =>
      val (u1, v) = u0.fresh(numeric = isNumeric(s, Type.TypeRef(p, Nil)), default = None)
      (u1, m.updated(p, v))
    }
    (s.copy(unifier = u), substitute(t, fresh))

  private def typeParams(s: TyperState, fn: SymbolId, t: Type): List[SymbolId] =
    t match
      case Type.TypeRef(id, args) =>
        val own = if s.symbols(id).kind == SymKind.TypeParam && s.symbols(id).owner == fn then List(id) else Nil
        own ++ args.flatMap(typeParams(s, fn, _))
      case Type.ArrowType(l, r, _) => typeParams(s, fn, l) ++ typeParams(s, fn, r)
      case Type.TupleType(ts)      => ts.flatMap(typeParams(s, fn, _))
      case Type.CapRef(_, args)    => args.flatMap(typeParams(s, fn, _))
      case _                       => Nil

  private def substitute(t: Type, m: Map[SymbolId, Type]): Type =
    t match
      case Type.TypeRef(id, Nil) if m.contains(id) => m(id)
      case Type.TypeRef(id, args)                  => Type.TypeRef(id, args.map(substitute(_, m)))
      case Type.ArrowType(l, r, fx)                => Type.ArrowType(substitute(l, m), substitute(r, m), fx)
      case Type.TupleType(ts)                      => Type.TupleType(ts.map(substitute(_, m)))
      case Type.CapRef(id, args)                   => Type.CapRef(id, args.map(substitute(_, m)))
      case other                                   => other

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
    s1.unifier.resolve(fnType) match
      case Type.ArrowType(lhs, rhs, _) =>
        val params = lhs match
          case Type.UnitType      => Nil
          case Type.TupleType(ts) => ts
          case single             => List(single)
        if params.size != t.args.size then
          (s1.error(DiagCode.T006, t.span, s"expected ${params.size} argument(s), got ${t.args.size}"), rhs)
        else
          val s2 = params.zip(t.args).foldLeft(s1) { case (si, (p, a)) => typedExpr(si, a, Proto.ExpectedType(p))._1 }
          (s2, rhs)
      case Type.NeverType => (s1, Type.NeverType)
      case other          =>
        (
          s1.error(DiagCode.T007, t.fn.span, s"${s1.symbols.render(s1.unifier.zonk(other))} is not a function"),
          Type.NeverType
        )

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
      case other => (s.error(DiagCode.T005, other.span, "pattern not supported in let yet"), ctx)
