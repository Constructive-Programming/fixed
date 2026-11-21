package fixed.typer

import scala.collection.immutable.IntMap

import fixed.ast.{Tree, Trees}
import fixed.core.{Context, EffectBound, EffectRow, Proto, SymKind, SymbolId, SymbolTable, Type, render}
import fixed.util.{DiagCode, Diagnostic, Severity, Span, mapAccum}

/** The type side table, keyed by tree identity.
  *
  * Escape hatch (plan §3.1, §3.5): a `java.util.IdentityHashMap` behind a pure-looking interface.
  * Persistent maps keyed by a structural tree (or its packed span) dominated typer time. Writes
  * are only ever overwrites of the same key with the newer type: a failed overload trial types the
  * same argument trees the winning attempt then re-records, so no stale entry survives a call.
  */
final class TypeTable private (
    entries: java.util.IdentityHashMap[Tree, Type],
    resolutions: java.util.IdentityHashMap[Tree, Resolution]
):
  def updated(tree: Tree, t: Type): TypeTable =
    val _ = entries.put(tree, t)
    this

  def get(tree: Tree): Option[Type] = Option(entries.get(tree))

  /** Record which member a call resolved to (the winner among candidates). */
  def resolved(tree: Tree, r: Resolution): TypeTable =
    val _ = resolutions.put(tree, r)
    this

  def resolution(tree: Tree): Option[Resolution] = Option(resolutions.get(tree))

object TypeTable:
  def empty: TypeTable =
    TypeTable(java.util.IdentityHashMap[Tree, Type](1024), java.util.IdentityHashMap[Tree, Resolution](256))

/** `op` on a value of type `value`, typed in `owner`, needs capability `cap` if `value` is abstract
  * (P6 D10).
  */
final case class BoundCheck(value: Type, cap: SymbolId, op: String, span: Span, owner: SymbolId)

/** Threaded typer state (`docs/plans/phase-3-typer.md` §3.4): the type side table, the symbol
  * table (local bindings join it), the unifier and the diagnostics so far (newest first).
  * `rigidBounds` are the bounds of a capability member's own type parameters while one of its
  * bodies is typed, with `Self`, `Part` and the capability's arguments substituted
  * (`Declarations.memberBody`); the unifier's `paramBounds` reads them too. Member bodies do not
  * nest, so the map is empty outside one.
  */
final case class TyperState(
    types: TypeTable,
    symbols: SymbolTable,
    unifier: Unifier,
    diags: List[Diagnostic],
    errors: Int,
    members: MemberTables,
    obligations: List[(Type, Type, Span)],
    constructors: List[(Trees.StaticCall, SymbolId, String)],
    row: List[(EffectBound, Span)],
    rowChecks: List[RowCheck],
    boundChecks: List[BoundCheck],
    rigidBounds: IntMap[List[Type]] = IntMap.empty
):
  def record(tree: Tree, t: Type): TyperState = copy(types = types.updated(tree, t))

  /** A fresh unconstrained unification variable. */
  def fresh: (TyperState, Type) =
    val (u, v) = unifier.fresh(numeric = false, default = None)
    (copy(unifier = u), v)

  /** `n` fresh unconstrained variables. */
  def freshVars(n: Int): (TyperState, List[Type]) = mapAccum(this, List.fill(n)(()))((si, _) => si.fresh)

  /** Unify `expected` with `actual` without reporting: the extended state, if they unify and every
    * type the unification gave a bounded variable satisfies its bound.
    */
  def tryUnify(expected: Type, actual: Type): Option[TyperState] =
    unifier.unify(expected, actual, Members.isNumeric(this, _)).toOption.flatMap { u =>
      val solved = copy(unifier = u.copy(obligations = Nil))
      Option.when(u.obligations.forall((value, bound) => Satisfaction.satisfies(solved, value, bound)))(solved)
    }

  /** The recorded type of `tree`, with every solved variable substituted. */
  def typeOf(tree: Tree): Option[Type] = types.get(tree).map(unifier.zonk)

  /** Report an error once: a prototype re-checked by an enclosing node repeats the same finding. */
  def error(code: DiagCode, span: Span, message: String, hint: Option[String] = None): TyperState =
    val d = Diagnostic(Severity.Error, code, span, message, hint)
    if diags.contains(d) then this else copy(diags = d :: diags, errors = errors + 1)

  /** Merge diagnostics produced outside the typer (the Namer's), in source order. */
  def report(more: List[Diagnostic]): TyperState =
    copy(diags = more.reverse ++ diags, errors = errors + more.count(_.isError))

  def warn(code: DiagCode, span: Span, message: String): TyperState =
    copy(diags = Diagnostic(Severity.Warning, code, span, message) :: diags)

  /** A construct the typer does not handle yet (`T005`): a gap in the typer, not in the program. */
  def unsupported(span: Span, what: String): TyperState =
    warn(DiagCode.T005, span, s"$what is not supported by the typer yet")

  /** Check a body's effect row when the unit finishes. */
  def checkRow(c: RowCheck): TyperState = copy(rowChecks = c :: rowChecks)

  /** Check the effect rows recorded while typing (their arguments may solve literal variables),
    * default unsolved literal variables, discharge the capability obligations, and order the
    * diagnostics. Recorded types stay as typed; `typeOf` substitutes on read.
    */
  def finish: TyperState =
    val rows = rowChecks.reverse.foldLeft(this)(Effects.discharge)
    val defaulted = rows.copy(unifier = rows.unifier.applyDefaults)
    val checked = obligations.reverse.foldLeft(defaulted) { case (si, (value, bound, span)) =>
      Satisfaction.discharge(si, value, bound, span)
    }
    val bounded = boundChecks.reverse.foldLeft(checked.copy(boundChecks = Nil))(Typer.requireBound(_, _, last = true))
    bounded.copy(diags = bounded.diags.reverse)

object TyperState:
  def apply(symbols: SymbolTable): TyperState =
    TyperState(
      TypeTable.empty,
      symbols,
      Unifier.empty.copy(tuple = symbols.scope.get("Tuple"), paramBounds = Members.boundsOf(symbols, _)),
      Nil,
      0,
      MemberTables(symbols),
      Nil,
      Nil,
      Nil,
      Nil,
      Nil
    )

/** The bidirectional body typer (§5.2, §3.7–§3.9). Each routine records the tree's type in the
  * side table and returns it; constructs not handled yet report a `T005` warning.
  */
object Typer:

  private val i64 = Type.PrimType("i64")
  private val f64 = Type.PrimType("f64")
  private[typer] val bool = Type.PrimType("bool")

  /** Type an expression against prototype `pt`. */
  def typedExpr(s: TyperState, tree: Tree, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val (s1, t) = tree match
      case _: Trees.IntLit           => literal(s, i64)
      case _: Trees.FloatLit         => literal(s, f64)
      case _: Trees.StringLit        => (s, ctx.lookup("String").fold(Type.PrimType("String"))(Type.TypeRef(_, Nil)))
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
      case Trees.ListExpr(elems, _)          => typedList(s, elems)
      case t: Trees.IfExpr                   => typedIf(s, t, pt)
      case Trees.DoExpr(stmts, _)            => typedBlock(s, stmts, pt)
      case t: Trees.MatchExpr                => Patterns.typedMatch(s, t, pt)
      case t: Trees.HandleExpr               => typedHandle(s, t, pt)
      case Trees.Resume(value, span)         => typedResume(s, value, span)
      case _: Trees.Unreachable              => (s, Type.NeverType)
      case Trees.Pipe(lhs, rhs, span)        => typedPipe(s, lhs, rhs, span)
      case t: Trees.LambdaExpr               => typedLambda(s, t, pt)
      case t: Trees.MethodCall               => typedMethodCall(s, t)
      case t: Trees.StaticCall               => typedStaticCall(s, t, pt)
      case other => (s.unsupported(other.span, other.getClass.getSimpleName), Type.NeverType)
    (check(s1, tree.span, t, pt).record(tree, t), t)

  /** Reconcile an inferred type with the prototype; a function value's row meets the expected one. */
  private def check(s: TyperState, span: Span, t: Type, pt: Proto): TyperState =
    pt.expected.fold(s)(e => Effects.meet(unify(s, span, e, t), span, e, t))

  private[typer] def unify(s: TyperState, span: Span, expected: Type, actual: Type): TyperState =
    solve(
      s,
      span,
      s.unifier.unify(expected, actual, isNumeric(s, _))
    )(s"expected ${show(s, expected)}, got ${show(s, actual)}")

  /** Adopt the unifier from `result`, moving the obligations it recorded to the state with `span`,
    * or report `T002` with `message`.
    */
  private def solve(s: TyperState, span: Span, result: Either[?, Unifier])(message: => String): TyperState =
    result.fold(
      _ => s.error(DiagCode.T002, span, message),
      u =>
        if u.obligations.isEmpty then s.copy(unifier = u)
        else
          s.copy(
            unifier = u.copy(obligations = Nil),
            obligations = u.obligations.map((v, b) => (v, b, span)) ++ s.obligations
          )
    )

  private def show(s: TyperState, t: Type): String = s.symbols.render(s.unifier.zonk(t))

  /** Numeric literals are polymorphic (`spec/type_system.md`): a fresh `Numeric` variable defaulting to `default`. */
  private def literal(s: TyperState, default: Type): (TyperState, Type) =
    val (u, v) = s.unifier.fresh(numeric = true, default = Some(default))
    (s.copy(unifier = u), v)

  private def isNumeric(s: TyperState, t: Type): Boolean = Members.isNumeric(s, t)

  private def typedIdent(s: TyperState, name: String, span: Span)(using ctx: Context): (TyperState, Type) =
    ctx.lookup(name) match
      case None     => (s.error(DiagCode.T001, span, s"not found: $name"), Type.NeverType)
      case Some(id) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.ValueParam | SymKind.Local => (s, d.info)
          case SymKind.Fn => Members.instantiate(s, d.info, Members.Subst(IntMap.empty, Set(id)))
          case _          => (s.error(DiagCode.T008, span, s"$name is not a value"), Type.NeverType)

  private def typedAll(s: TyperState, trees: List[Tree])(using Context): (TyperState, List[Type]) =
    mapAccum(s, trees)(typedExpr(_, _, Proto.Wildcard))

  private def typedBinOp(s: TyperState, t: Trees.BinOp, pt: Proto)(using ctx: Context): (TyperState, Type) =
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

        /** `==` and `!=` are `Eq.eq`; `<`, `<=`, `>` and `>=` are `Ord.compare` (`spec/stdlib.md` §4.7, §4.8). */
        val cap = s2.symbols.scope.get(if t.op == Eq || t.op == Neq then "Eq" else "Ord")
        (cap.fold(s2)(c => requireBound(s2, BoundCheck(l, c, t.op.symbol, t.span, ctx.owner))), bool)
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
      case (Type.ArrowType(Type.TupleType(Nil), rhs, fx), None) => (Effects.perform(s, fx, span), rhs)
      case (other, None)                                        => (s, other)
      case (Type.ArrowType(lhs, rhs, fx), Some(as))             =>
        val params = Members.params(lhs)
        val s1 = Effects.perform(s, fx, span)
        if params.size != as.size then
          (s1.error(DiagCode.T006, span, s"expected ${params.size} argument(s), got ${as.size}"), rhs)
        else (params.zip(as).foldLeft(s1) { case (si, (p, a)) => typedExpr(si, a, Proto.ExpectedType(p))._1 }, rhs)
      case (Type.NeverType, _) => (s, Type.NeverType)
      case (other, _)          => (s.error(DiagCode.T007, span, s"${show(s, other)} is not a function"), Type.NeverType)

  /** `[a, b, ...]`: a `List` of the elements' common type. */
  private def typedList(s: TyperState, elems: List[Tree])(using ctx: Context): (TyperState, Type) =
    val (s0, elem) = s.fresh
    val s1 = elems.foldLeft(s0)((si, e) => typedExpr(si, e, Proto.ExpectedType(elem))._1)
    (s1, ctx.lookup("List").fold(Type.NeverType)(Type.TypeRef(_, List(elem))))

  /** `if c: a else: b` — `c` is `bool`; both branches meet the prototype (or the first branch's type). */
  private def typedIf(s: TyperState, t: Trees.IfExpr, pt: Proto)(using Context): (TyperState, Type) =
    val (s1, _) = typedExpr(s, t.cond, Proto.ExpectedType(bool))
    val (s2, thenType) = typedExpr(s1, t.thenBranch, pt)
    (typedExpr(s2, t.elseBranch, pt.orExpect(Some(thenType)))._1, pt.orElse(thenType))

  /** A lambda. Against an expected function type of the same arity, parameters take its parameter
    * types and the body its result type; otherwise they are fresh variables.
    */
  private def typedLambda(s: TyperState, t: Trees.LambdaExpr, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val expected = pt.expected.fold(Type.NeverType)(s.unifier.resolve)
    val (expectedParams, expectedResult) = expected match
      case Type.ArrowType(lhs, rhs, _) =>
        val ps = Members.params(lhs)
        (Option.when(ps.size == t.params.size)(ps), Some(rhs))
      case _ => (None, None)
    val withExpected = expectedParams.fold(t.params.map(_ -> Option.empty[Type]))(ps => t.params.zip(ps.map(Some(_))))
    val (s1, inner, paramTypes) = withExpected.foldLeft((s, ctx, List.empty[Type])) {
      case ((si, ci, acc), (p, expectedParam)) =>
        val (si1, declared) = expectedParam match
          case Some(e) => (si, e)
          case None    =>
            si.fresh
        val si2 = p.typeAnn.fold(si1) { ann =>
          val (table, annotated, diags) = Namer.translate(si1.symbols, ci.frames, ci.owner, ann)
          unify(si1.copy(symbols = table).report(diags), p.span, annotated, declared)
        }
        val (table, id) = si2.symbols.enter(p.name, ci.owner, SymKind.Local, declared, p.span)
        (si2.copy(symbols = table).record(p, declared), ci.bind(p.name, id), acc :+ declared)
    }
    val bodyProto = expectedResult.fold(Proto.Wildcard)(Proto.ExpectedType(_))
    val (s2, bodyType, row) = Effects.isolated(s1)(typedExpr(_, t.body, bodyProto)(using inner))

    /** The body was already checked against an expected result; report its mismatch once. */
    val result = expectedResult.getOrElse(bodyType)
    (s2, Type.ArrowType(Type.TupleType(paramTypes), result, Effects.rowOf(row)))

  /** `recv.m(args)` / `recv.m`: an instance member of the receiver's type. With several
    * candidates (a capability `fold` and a data type's derived `fold`), the first whose argument
    * typing reports nothing wins.
    */
  private def typedMethodCall(s: TyperState, t: Trees.MethodCall)(using ctx: Context): (TyperState, Type) =
    t.receiver match
      case Trees.Ident("builtin", _) if ctx.lookup("builtin").isEmpty => intrinsic(s, t)
      case receiver                                                   =>
        val (s1, recv) = typedExpr(s, receiver, Proto.Wildcard)
        s1.unifier.resolve(recv) match
          case Type.NeverType => (s1, Type.NeverType)
          case resolved       =>
            val needs = (cap: SymbolId) => BoundCheck(resolved, cap, s".${t.method}", t.span, ctx.owner)
            Members.candidates(s1, resolved, t.method) match
              case Nil =>
                resolved match
                  case Type.TypeVar(id) if s1.unifier.boundsOf(id).isEmpty =>
                    (s1.unsupported(t.span, s"`.${t.method}` on a receiver of unknown type"), Type.NeverType)
                  case _ =>
                    Members.autoDerivedCap(s1, t.method) match
                      case Some(cap) if Members.abstractType(s1, resolved) =>
                        (requireBound(s1, needs(cap)), Type.NeverType)
                      case Some(cap) if Satisfaction.onRequest(s1, cap) =>
                        (notCloned(s1, resolved, t.span), Type.NeverType)
                      case _ =>
                        (
                          s1.error(DiagCode.T001, t.span, s"not found: member ${t.method} of ${show(s1, resolved)}"),
                          Type.NeverType
                        )
              case first :: rest =>
                val errors = s1.errors
                def attempt(c: Members.Candidate) =
                  val (si, member) = c()
                  applyArgs(si, member, t.args, t.span)
                val firstAttempt = attempt(first)
                val (winner, (s2, result)) =
                  if rest.isEmpty || firstAttempt._1.errors == errors then (first, firstAttempt)
                  else
                    rest.iterator.map(c => (c, attempt(c))).find(_._2._1.errors == errors)
                      .getOrElse((first, firstAttempt))
                val s3 = winner.origin match
                  case Resolution.CapMember(cap, _)
                      if Satisfaction.onRequest(s2, cap) && !Members.abstractType(s2, resolved) =>
                    if Satisfaction.holds(s2, resolved, cap, Nil).isDefined then s2 else notCloned(s2, resolved, t.span)
                  case Resolution.CapMember(cap, _) if Members.autoDerivedCap(s2, t.method).contains(cap) =>
                    requireBound(s2, needs(cap))
                  case _ => s2
                (s3.copy(types = s3.types.resolved(t, winner.origin)), result)

  /** `T010`: `clone` on a concrete type that is not `Clone`; a data type has it only on request. */
  private def notCloned(s: TyperState, t: Type, span: Span): TyperState =
    val data = t match
      case Type.TypeRef(id, _) => s.symbols.dataNamed(s.symbols(id).name).filter(_ == id)
      case _                   => None
    val hint = data.map(id => s"declare it: `${s.symbols(id).name} satisfies Clone`")
    s.error(DiagCode.T010, span, s"${show(s, t)} does not satisfy Clone", hint)

  /** P6 D10: `c.op` needs `c.cap` of `c.value`. A type that still holds unsolved variables is
    * checked again when the unit finishes; then a variable never solved is left alone. A type that
    * lacks the capability is `T022` when bounds on abstract types inside it would give it the
    * capability (`List of T` and `Show`: the hint names `T`), and `T010` otherwise.
    */
  private[typer] def requireBound(s: TyperState, c: BoundCheck, last: Boolean = false): TyperState =
    val t = s.unifier.zonk(c.value)
    def lacks(u: Type) = Satisfaction.holds(s, u, c.cap, Nil).isEmpty
    def unsolved(u: Type): Boolean = u match
      case Type.TypeVar(_) | Type.AppliedVar(_, _) => true
      case other                                   => other.children.exists(unsolved)
    def abstracts(u: Type): List[Type] =
      if Members.abstractType(s, u) then List(u).filter(lacks) else u.children.flatMap(abstracts)

    /** `u` with each of `ps` replaced by `!`, which has every capability. */
    def assuming(ps: List[Type])(u: Type): Type = if ps.contains(u) then Type.NeverType else u.mapChildren(assuming(ps))
    t match
      case Type.TypeVar(_) | Type.AppliedVar(_, _) if last => s
      case _ if !last && unsolved(t)                       => s.copy(boundChecks = c :: s.boundChecks)
      case _ if !lacks(t)                                  => s
      case _                                               =>
        val ps = abstracts(t).distinct
        if ps.nonEmpty && !lacks(assuming(ps)(t)) then
          /** The parameter that, alone without the bound, still makes the type lack it. */
          val needed = ps.find(p => lacks(assuming(ps.filterNot(_ == p))(t))).getOrElse(ps.head)
          missingBound(s, c.copy(value = t), needed)
        else s.error(DiagCode.T010, c.span, s"${show(s, t)} does not satisfy ${s.symbols(c.cap).name}")

  /** `T022`, with the bound written out as the hint: on `param` (the type parameter or anonymous
    * bound that lacks it, `c.value` itself or a type inside it), or, for `self` in a capability's
    * own default body, on the capability's `extends` clause.
    */
  private def missingBound(s: TyperState, c: BoundCheck, param: Type): TyperState =
    val r = s.symbols.render
    def written(bounds: List[Type]) = (bounds :+ Type.CapRef(c.cap, Nil)).map(r).mkString(" + ")
    def declared(id: SymbolId) = s.symbols(id).info match
      case Type.IsBound(bs) => bs
      case _                => Nil
    val hint = param match
      case Type.TypeRef(id, _) if Members.anonymousParam(s, id) => s"`is ${written(declared(id))}`"
      case Type.TypeRef(id, _)                                  =>
        val (param, owner) = (s"${s.symbols(id).name} is ${written(declared(id))}", s.symbols(s.symbols(id).owner))
        owner.kind match
          case SymKind.Data                          => s"`data ${owner.name} of ($param)`"
          case SymKind.Method | SymKind.StaticMethod =>
            s"`$param` in the signature of `${s.symbols(owner.owner).name}.${owner.name}`"
          case _ => s"`$param`"
      case Type.IsBound(List(Type.CapRef(cap, _))) if cap == c.owner =>
        s"`cap ${s.symbols(cap).name} extends ${written(declared(cap))}`"
      case Type.IsBound(bs) => s"`is ${written(bs)}`"
      case other            => s"`is ${written(List(other))}`"
    s.error(
      DiagCode.T022,
      c.span,
      s"`${c.op}` on a value of type ${show(s, c.value)} needs the bound `${s.symbols(c.cap).name}`" +
        (if param == c.value then "" else s" on ${show(s, param)}"),
      Some(s"write the bound: $hint")
    )

  /** `x |> f(a, ...)` is `f(x, a, ...)`; `x |> f` is `f(x)`. The spec gives `|>` only a grammar
    * (`spec/syntax_grammar.ebnf` PipeExpr); first-argument insertion is the typer's reading.
    */
  private def typedPipe(s: TyperState, lhs: Tree, rhs: Tree, span: Span)(using Context): (TyperState, Type) =
    rhs match
      case Trees.Apply(fn, args, _) =>
        val (s1, fnType) = typedExpr(s, fn, Proto.Wildcard)
        applyArgs(s1, fnType, Some(lhs :: args), span)
      case other =>
        val (s1, fnType) = typedExpr(s, other, Proto.Wildcard)
        applyArgs(s1, fnType, Some(List(lhs)), span)

  /** `builtin.f(args)`: a host intrinsic placeholder used by the examples; its arguments are typed,
    * its result is left to inference.
    */
  private def intrinsic(s: TyperState, t: Trees.MethodCall)(using Context): (TyperState, Type) =
    val (s1, _) = typedAll(s, t.args.getOrElse(Nil))
    s1.fresh

  /** `T.m(args)` / `T.m`: an effect operation, a data constructor or derived `unfold`. */
  private def typedStaticCall(s: TyperState, t: Trees.StaticCall, pt: Proto)(using ctx: Context): (TyperState, Type) =
    ctx.lookup(t.qualifier) match
      case None        => (s.error(DiagCode.T001, t.span, s"not found: ${t.qualifier}"), Type.NeverType)
      case Some(owner) =>
        val layer = pt.expected.map(s.unifier.resolve).collect { case l: Type.Layer => l }
        val kind = s.symbols(owner).kind
        val resolved = if kind == SymKind.Cap then Satisfaction.constructor(s, owner, t, pt) else None
        resolved.orElse(Members.static(s, owner, t.method, layer)) match
          case Some((s1, member))             => applyArgs(s1, member, t.args, t.span)
          case None if kind != SymKind.Import =>
            (s.error(DiagCode.T001, t.span, s"not found: ${t.qualifier}.${t.method}"), Type.NeverType)
          case None => (s.unsupported(t.span, s"`${t.qualifier}.${t.method}`"), Type.NeverType)

  /** Statements in order; `let`, nested `fn` and (in `do:`) `pat <- m` extend the scope of what
    * follows. `pat <- m` binds the pattern to the element type of `m` (monadic sequencing). The
    * block's type is its last expression's, or `()` when it ends in a binding or is empty.
    */
  private def typedBlock(s: TyperState, stmts: List[Tree], pt: Proto)(using ctx: Context): (TyperState, Type) =
    val lastStmt = stmts.lastOption
    val (s1, _, last) = stmts.foldLeft((s, ctx, Type.UnitType: Type)) { case ((si, ci, _), stmt) =>
      stmt match
        case b @ Trees.DoBind(pat, expr, _) =>
          val (si1, m) = typedExpr(si, expr, Proto.Wildcard)(using ci)
          val (si2, elem) = Members.elementOf(si1, m).fold(si1.fresh)((si1, _))
          val (si3, ci1) = Patterns.bind(si2.record(b, m), pat, elem)(using ci)
          (si3, ci1, m)
        case let: Trees.LetExpr =>
          val (si1, ci1) = typedLet(si, let)(using ci)
          (si1.record(let, Type.UnitType), ci1, Type.UnitType)
        case fn: Trees.FnDecl =>
          val (si1, ci1) = Patterns.nestedFn(si, fn)(using ci)
          (si1, ci1, Type.UnitType)
        case e =>
          val expect = if lastStmt.exists(_ eq stmt) then pt else Proto.Wildcard
          val (si1, ty) = typedExpr(si, e, expect)(using ci)
          (si1, ci, ty)
    }
    (s1, last)

  /** `let pat = init`: types `init` and binds the pattern's names. */
  private def typedLet(s: TyperState, let: Trees.LetExpr)(using ctx: Context): (TyperState, Context) =
    val (s1, t) = typedExpr(s, let.init, Proto.Wildcard)
    Patterns.bind(s1, let.pattern, t)

  /** A `fn` body against its declared return type, its parameters and type variables in scope. */
  def typedFnBody(s: TyperState, id: fixed.core.SymbolId, fn: Trees.FnLike)(using ctx: Context): TyperState =
    fn.body.fold(s) { body =>
      val params = s.symbols.membersOf(id).filter(d => d.kind == SymKind.ValueParam || d.kind == SymKind.TypeParam)
      val inner = Context(ctx.local ++ params.map(d => d.name -> d.id), ctx.global, id)
      val d = s.symbols(id)
      val (expected, fx) = d.info match
        case Type.ArrowType(_, ret, fx) => (Proto.ExpectedType(ret), fx)
        case _                          => (Proto.Wildcard, EffectRow.empty)
      val (s1, _, row) = Effects.isolated(s)(typedExpr(_, body, expected)(using inner))
      val main = d.name == "main" && d.owner == SymbolId.Root
      s1.checkRow(RowCheck(row, fx, d.name, fn.span, unused = true, runtime = main))
    }

  /** A method body whose parameter types come from elsewhere (a capability's signature): `locals`
    * become `Local` bindings (with `self`), `scoped` are type variables already in the table.
    */
  def typedMethodBody(
      s: TyperState,
      body: Tree,
      owner: fixed.core.SymbolId,
      locals: List[(String, Type)],
      scoped: List[(String, fixed.core.SymbolId)],
      ret: Type,
      fx: EffectRow,
      name: String
  ): TyperState =
    val start = Context(scoped.toMap, s.symbols.scope, owner)
    val (s1, ctx) = locals.foldLeft((s, start)) { case ((si, ci), (name, t)) =>
      val (table, id) = si.symbols.enter(name, owner, SymKind.Local, t, body.span)
      (si.copy(symbols = table), ci.bind(name, id))
    }
    val (s2, _, row) = Effects.isolated(s1)(typedExpr(_, body, Proto.ExpectedType(ret))(using ctx))
    s2.checkRow(RowCheck(row, fx, name, body.span, unused = false, runtime = false))

  /** `handle subject: clauses` (`spec/effects.md` §6). Operation clauses bind the operation's
    * parameters; inside them `resume(v)` takes the operation's result type and yields the
    * handler's. The handler's type is the `return` clause's (else the subject's). Its row is the
    * subject's minus the effects the clauses name, plus the clause bodies' rows (Rule E6.6).
    */
  private def typedHandle(s: TyperState, h: Trees.HandleExpr, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val (s1, subject, raw) = Effects.isolated(s)(typedExpr(_, h.subject, Proto.Wildcard))

    /** ponytail: row variables solved after the subject (by a later callback) bypass this handler. */
    val performed = raw.flatMap((e, span) => Effects.expand(s1, List(e)).map(_ -> span))
    val (s2, result) = (h.returnClause, pt) match
      case (None, _)                        => (s1, subject)
      case (Some(_), Proto.ExpectedType(e)) => (s1, e)
      case (Some(_), Proto.Wildcard)        =>
        s1.fresh
    val s3 = h.returnClause.fold(s2) { rc =>
      val (si, ci) = Patterns.bind(s2, rc.param, subject)
      typedExpr(si, rc.body, Proto.ExpectedType(result))(using ci)._1
    }
    val (s4, handled) = mapAccum(s3, h.operationClauses)(typedClause(_, _, result, performed))
    val names = handled.flatten
    val passed = performed.filterNot((e, _) => names.exists(_._1 == e.sym))
    (Effects.coverage(s4.copy(row = passed ++ s4.row), names, h.span), result)

  /** An operation clause, its parameters bound to the operation's arguments for each instance of
    * the effect the subject performs; the (effect, operation) it handles.
    */
  private def typedClause(s: TyperState, c: Trees.OperationClause, result: Type, performed: List[(EffectBound, Span)])(
      using ctx: Context
  ): (TyperState, Option[(SymbolId, String)]) =
    ctx.lookup(c.effect).filter(s.symbols(_).kind == SymKind.Effect) match
      case None         => (s.error(DiagCode.T001, c.span, s"not found: effect ${c.effect}"), None)
      case Some(effect) =>
        Members.static(s, effect, c.op, None) match
          case Some((s1, Type.ArrowType(lhs, opResult, fx))) =>
            val params = Members.params(lhs)
            val instance = Type.TupleType(fx.effects.lastOption.fold(Nil)(_.args))
            val s2 = performed.foldLeft(s1) { case (si, (e, _)) =>
              if e.sym == effect then unify(si, c.span, Type.TupleType(e.args), instance) else si
            }
            val s3 =
              if params.size != c.params.size then
                s2.error(
                  DiagCode.T006,
                  c.span,
                  s"${c.effect}.${c.op} takes ${params.size} parameter(s), the clause binds ${c.params.size}"
                )
              else
                val (si, inner) = c.params.zip(params).foldLeft((s2, ctx)) { case ((sj, cj), (p, t)) =>
                  Patterns.bind(sj, p, t)(using cj)
                }
                typedExpr(si, c.body, Proto.ExpectedType(result))(using
                  inner.copy(resume = Some((opResult, result)))
                )._1
            (s3, Some(effect -> c.op))
          case _ => (s.error(DiagCode.T001, c.span, s"not found: operation ${c.effect}.${c.op}"), None)

  /** `resume(v)` inside an operation clause; an abortive operation (`-> !`) has nothing to resume. */
  private def typedResume(s: TyperState, value: Option[Tree], span: Span)(using ctx: Context): (TyperState, Type) =
    ctx.resume match
      case None => (s.error(DiagCode.T009, span, "resume outside a handler's operation clause"), Type.NeverType)
      case Some((Type.NeverType, _)) =>
        (
          s.error(DiagCode.T019, span, "resume in the clause of an abortive operation (its result is !)"),
          Type.NeverType
        )
      case Some((opResult, result)) =>
        val s1 =
          value.fold(unify(s, span, opResult, Type.UnitType))(v => typedExpr(s, v, Proto.ExpectedType(opResult))._1)
        (s1, result)
