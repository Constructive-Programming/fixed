package fixed.typer

import fixed.core.{EffectBound, EffectRow, SymKind, SymbolId, Type, render}
import fixed.util.{DiagCode, Span}

/** A body's performed effects against what it may perform, checked when the unit finishes
  * (`docs/plans/phase-3.2-effects.md` §3.1). `owner` names the declaration in messages; `unused`
  * warns about declared effects the body never performs; `runtime` restricts the declared row to
  * the effects the runtime handles (`main`).
  */
final case class RowCheck(
    performed: List[(EffectBound, Span)],
    declared: EffectRow,
    owner: String,
    span: Span,
    unused: Boolean,
    runtime: Boolean
)

/** Effect rows during body typing (`spec/effects.md` §5): what a body performs accumulates in
  * [[TyperState.row]]; bodies, lambdas and `handle` subjects each start from an empty row.
  */
object Effects:

  /** The effects the runtime installs handlers for: those `std.io` declares (Rule E5.4). */
  private lazy val runtimeNames: Set[String] =
    Stdlib.modules.values.flatten.collect { case e: fixed.ast.Trees.EffectDecl => e.name }.toSet

  /** Calling something of row `fx` at `span` performs it. */
  def perform(s: TyperState, fx: EffectRow, span: Span): TyperState =
    if fx.effects.isEmpty then s else s.copy(row = fx.effects.map(_ -> span) ++ s.row)

  /** Run `body` with an empty row; the result, its row, and `s`'s row restored. */
  def isolated[A](s: TyperState)(body: TyperState => (TyperState, A)): (TyperState, A, List[(EffectBound, Span)]) =
    val (s1, a) = body(if s.row.isEmpty then s else s.copy(row = Nil))
    (if s1.row eq s.row then s1 else s1.copy(row = s.row), a, s1.row)

  /** A row as an [[EffectRow]], duplicates dropped. */
  def rowOf(performed: List[(EffectBound, Span)]): EffectRow =
    if performed.isEmpty then EffectRow.empty else EffectRow(performed.map(_._1).distinct)

  /** A value of function type `actual` meets `expected` (Rule E5.5.a, plan §3.2). An expected
    * function type with a `with` clause bounds the value's row; anything else lets the row pass
    * through to the enclosing body, which must declare or handle it.
    */
  def meet(s: TyperState, span: Span, expected: Type, actual: Type): TyperState =
    actual match
      case Type.ArrowType(_, _, fx) if fx.effects.nonEmpty =>
        s.unifier.resolve(expected) match
          case Type.ArrowType(_, _, bound) if bound.effects.nonEmpty =>
            s.checkRow(RowCheck(fx.effects.map(_ -> span), bound, "the expected function type", span, false, false))
          case _ => perform(s, fx, span)
      case _ => s

  /** Rule E5.2: every performed effect is declared (`T015`); a declared one never performed is a
    * warning (`T017`); `main` declares only runtime effects (`T016`, Rule E5.4).
    */
  def discharge(s: TyperState, c: RowCheck): TyperState =
    val declared = c.declared.effects
    val checked = c.performed.reverse.foldLeft(s) { case (si, (e, span)) =>
      declared.iterator.flatMap(d => if d.sym == e.sym then unifyArgs(si, d, e) else None).nextOption().getOrElse {
        si.error(DiagCode.T015, span, s"effect ${show(si, e)} is not declared by ${c.owner}")
      }
    }
    val unused = if c.unused then declared.filterNot(d => c.performed.exists(_._1.sym == d.sym)) else Nil
    val warned = unused.foldLeft(checked) { (si, d) =>
      si.warn(DiagCode.T017, c.span, s"${c.owner} declares effect ${show(si, d)} but never performs it")
    }
    val foreign = if c.runtime then declared.filterNot(d => isRuntime(s, d.sym)) else Nil
    foreign.foldLeft(warned) { (si, d) =>
      si.error(DiagCode.T016, c.span, s"main performs ${show(si, d)}, which the runtime does not handle")
    }

  private def unifyArgs(s: TyperState, d: EffectBound, e: EffectBound): Option[TyperState] =
    s.tryUnify(Type.TupleType(d.args), Type.TupleType(e.args))

  private def isRuntime(s: TyperState, effect: SymbolId): Boolean =
    val d = s.symbols(effect)
    d.owner == SymbolId.Prelude && d.kind == SymKind.Effect && runtimeNames(d.name)

  def show(s: TyperState, e: EffectBound): String =
    s.symbols.render(s.unifier.zonk(Type.TypeRef(e.sym, e.args)))

  /** Rule E5.3: a handler names an effect only by covering every one of its operations (`T018`). */
  def coverage(s: TyperState, clauses: List[(SymbolId, String)], span: Span): TyperState =
    val missing = clauses.map(_._1).distinct.flatMap { effect =>
      s.symbols.membersOf(effect).filter(_.kind == SymKind.Operation).map(_.name).filterNot(op =>
        clauses.contains(effect -> op)
      )
        .map(op => s"${s.symbols(effect).name}.$op")
    }
    if missing.isEmpty then s
    else s.error(DiagCode.T018, span, s"handler does not cover ${missing.mkString(", ")}")
