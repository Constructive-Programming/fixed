package fixed.backend

import fixed.ast.Trees
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** The `fip` contract (`spec/memory_management.md` §18; `docs/plans/phase-3.4-qtt.md` 3.4.M5),
  * checked on what M10 inferred for each `fip fn` and every function it reaches:
  *
  *   1. no `Heap` or `Region` allocation (Rule MM18.1(1));
  *   2. no reference-count operation: no call of a shared version, no call of a closure the
  *      compiler cannot see (Rule MM18.1(2));
  *   3. no evacuation: no copy (Rule MM18.1(3));
  *   4. no drop list: none exists before Phase 4 region lowering, so nothing to check;
  *   5. bounded scratch: no `Frame` allocation in a function that calls itself, other than a
  *      derived loop, whose step resets per step (Rule MM18.1(5)).
  *
  * `fip(strict)` adds: every allocation reuses a consumed cell (Rule MM18.2(1)), and no tree fold,
  * which is not a loop (Rule MM18.2(2)).
  *
  * A reused parameter is `exclusive` by construction of demand inference, so Rule MM18.3's `E071`
  * cannot arise from inference; a caller holding a shared value copies into the `fip fn`, and that
  * copy is the caller's evacuation, reported against the caller if it is `fip` itself.
  */
object FipCheck:

  def apply(a: Analysed): List[Diagnostic] =
    a.fips.toList.sortBy(_._1).flatMap((fn, level) => violations(a, fn, level)).distinct

  private def violations(a: Analysed, fn: String, level: Trees.Fip): List[Diagnostic] =
    val byName = a.fns.map(f => f.name -> f).toMap
    val reached = reach(Set(fn), byName)
    def error(item: String, at: Span, why: String) =
      Diagnostic(Severity.Error, DiagCode.E110, at, s"fip contract of `$fn` violated: $item: $why")
    val allocations = reached.toList.sorted.flatMap(f => a.memory.allocations.getOrElse(f, Nil).map(f -> _))
    val heap = allocations.collect {
      case (f, x) if x.cls match { case LifetimeClass.Heap | _: LifetimeClass.Region => true; case _ => false } =>
        error(
          "heap allocation (Rule MM18.1(1))",
          x.span,
          s"`${x.name}` in `$f` is ${x.cls.show}; ${x.reasons.mkString("; ")}"
        )
    }
    val calls = reached.toList.sorted.flatMap(f =>
      byName.get(f).toList.flatMap(g => Core.lets(g.body).map((x, rhs) => f -> (x.name, rhs)))
    )
    val rc = calls.collect {
      case (f, (x, Rhs.Call(g, _, false))) if g.matches(".*#\\d+$") || g.contains(".copy[") =>
        if g.contains(".copy[") then
          error("evacuation (Rule MM18.1(3))", a.spans(x), s"`$f` copies a shared value with `$g`")
        else error("reference-count operation (Rule MM18.1(2))", a.spans(x), s"`$f` calls the shared version `$g`")
      case (f, (x, _: Rhs.CallClosure)) =>
        error("reference-count operation (Rule MM18.1(2))", a.spans(x), s"`$f` calls a closure the compiler cannot see")
    }
    val scratch = allocations.collect {
      case (f, x @ Allocation(_, _: LifetimeClass.Frame, _, _, _))
          if !loop(f) && reach(Core.callees(byName(f).body), byName)(f) =>
        error("unbounded scratch (Rule MM18.1(5))", x.span, s"`${x.name}` is allocated on every call of recursive `$f`")
    }
    val strict =
      if level != Trees.Fip.Strict then Nil
      else
        val reused = (f: String) => a.demands.reuses.getOrElse(Memory.base(f), Map.empty).keySet
        allocations.collect {
          case (f, x) if !reused(f)(x.name) =>
            error("allocation without reuse (Rule MM18.2(1))", x.span, s"`${x.name}` in `$f` reuses no consumed cell")
        } ++ reached.toList.sorted.flatMap(f => a.memory.forks.getOrElse(f, Nil)).collect {
          case k if k.tree =>
            error("not a loop (Rule MM18.2(2))", k.span, s"`${k.loop}` is a tree fold")
        }
    heap ++ rc ++ scratch ++ strict

  /** Every function reachable from `from`, closures' code included. */
  private def reach(from: Set[String], byName: Map[String, Fn]): Set[String] =
    val next = from ++ from.flatMap(f => byName.get(f).toList.flatMap(g => Core.callees(g.body)))
    if next == from then from else reach(next, byName)

  /** A derived loop: its recursion is structural, and its step's scratch resets per step. */
  private def loop(f: String): Boolean = Seq(".fold[", ".unfold[", ".foldLeft[", ".map[", ".copy[").exists(f.contains)
