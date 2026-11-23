package fixed.backend

import scala.collection.mutable

import fixed.backend.Expr.{Let, Ret}
import fixed.util.Span

/** Where a value flows (`spec/memory_management.md` Rule MM20.1). */
enum Escape:
  case Result
  case Field(into: String)
  case Closure(by: String)
  case Carried(loop: String)
  case LaneShared(fork: String)
  case Unbounded(why: String)

/** A lifetime class (Rule MM3.1). `Inline` is M11's; `Erased` values are not allocations. */
enum LifetimeClass:
  case Frame(scope: String)
  case Dest
  case Region(root: String)
  case Heap

  def show: String = this match
    case Frame(s)  => s"Frame($s)"
    case Dest      => "Dest(result)"
    case Region(r) => s"Region($r)"
    case Heap      => "Heap"

/** One allocation site: a constructor or a closure with captures, bound to `name`. */
final case class Allocation(name: String, cls: LifetimeClass, escapes: Set[Escape], reasons: List[String], span: Span)

/** A parameter's escape in a signature (Rule MM20.2). */
enum ParamEscape:
  case None, IntoResult, Unbounded

/** Whether a `fold` / `unfold` call may fork (Rule Q5.13.a, d), and why not if it may not. */
final case class ForkFact(call: String, loop: String, tree: Boolean, admissible: Boolean, reason: String, span: Span)

/** @param framed per function, the counted variables that are `Frame` of it: neither returned nor
  *   long-lived (Rule MM5.2), allocations and call results alike.
  */
final class MemoryTable(
    val allocations: Map[String, List[Allocation]],
    val escapes: Map[String, List[ParamEscape]],
    val forks: Map[String, List[ForkFact]],
    val framed: Map[String, Set[String]]
)

/** Escape sets, lifetime classes, cohesive groups, garbage-free carry and fork facts over
  * versioned Core (`docs/plans/phase-3.4-qtt.md` 3.4.M4).
  *
  * '''Soundness for frames.''' `Frame(f)` promises that no reference to the value, or to a part
  * of it, is live once the activation of `f` that built it ends: when `f` returns, or jumps to its
  * own start by a self call in tail position (Rule MM5.1; a call in tail position to another
  * function keeps the scope open until it returns, as the code is generated). The escapes below
  * over-approximate every flow that could keep one, so a part escaping makes the whole escape.
  * `Frame` says nothing about ownership: a frame value may still be counted, reused or stored by
  * code that does not know its class, which [[Frames]] rules out where it places one.
  *
  * Escapes are found by one backward walk per function, repeated over the call graph until no
  * parameter's escape changes (`None < IntoResult < Unbounded`). A value stored into another
  * escapes wherever that one does, so a field's escape set contains its container's; a value read
  * from another, or returned by a call of a closure, may be a part of it, so the container's
  * escape set contains the part's.
  *
  * Classes follow Rule MM5.3 in order: a long-lived allocation (Rule MM5.2: unbounded escape, or
  * carried by a loop whose step is not garbage-free) is `Region` if it belongs to a cohesive group
  * of this function (Rule MM5.4; the search stays within one function, OQ-MM4), else `Heap`; one
  * that escapes only into the result is `Dest`; any other is `Frame` of its function. A lambda's
  * body is its own function, so a fold step's temporaries are `Frame` of that step.
  *
  * ponytail: escape detail through Core only; continuation escapes do not occur until capturing
  * handlers lower (B002), and fork facts treat any closure call inside a step as impure.
  */
final class Memory(fns: List[Fn], demands: DemandTable, spans: String => Span):
  private val signatures = mutable.HashMap.empty[String, List[ParamEscape]]
  private val byName = fns.map(f => f.name -> f).toMap
  private val escapeOf = mutable.HashMap.empty[String, Map[String, Set[Escape]]]
  private val effects = Set("fx_print_line", "fx_read_line", "fx_abort")

  def run(): MemoryTable =
    fixpoint()
    MemoryTable(
      fns.map(f => f.name -> classes(f)).toMap,
      signatures.toMap,
      fns.map(f => f.name -> forks(f)).toMap,
      fns.map(f => f.name -> framed(f)).toMap
    )

  private def fixpoint(): Unit =
    val before = signatures.toMap
    fns.foreach { f =>
      val esc = walk(f.body, Set(Escape.Result), f.name, tail = true)
      escapeOf(f.name) = esc
      signatures(f.name) = f.params.map(p => summary(esc.getOrElse(p.name, Set.empty)))
    }
    if signatures.toMap != before then fixpoint()

  private def summary(es: Set[Escape]): ParamEscape =
    if es.exists { case _: Escape.Unbounded => true; case _ => false } then ParamEscape.Unbounded
    else if es(Escape.Result) then ParamEscape.IntoResult
    else ParamEscape.None

  private def param(f: String, i: Int): ParamEscape =
    if byName.contains(f) then signatures.get(f).flatMap(_.lift(i)).getOrElse(ParamEscape.None)
    else ParamEscape.Unbounded

  private def merge(a: Map[String, Set[Escape]], b: Map[String, Set[Escape]]): Map[String, Set[Escape]] =
    Core.merge(a, b, Set.empty[Escape])(_ ++ _)

  /** The escapes of every variable `e` uses; a `Ret` flows to `target`, and `tail` says whether
    * `e`'s result is `fn`'s. Bound variables keep their entries, so the result also gives each
    * allocation's escape set.
    */
  private def walk(e: Expr, target: Set[Escape], fn: String, tail: Boolean): Map[String, Set[Escape]] =
    e match
      case Ret(a)            => flows(List(a), target)
      case Let(x, rhs, body) =>
        val inner = walk(body, target, fn, tail)
        merge(inner, right(x, rhs, inner.getOrElse(x.name, Set.empty), fn, tail && body == Ret(x)))
      case Expr.Dup(_, b)  => walk(b, target, fn, tail)
      case Expr.Drop(_, b) => walk(b, target, fn, tail)

  private def flows(as: List[Atom], es: Set[Escape]): Map[String, Set[Escape]] =
    as.flatMap(Core.countedVar).map(v => v.name -> es).toMap

  /** What `let x = rhs` contributes, given `x`'s own escapes `ex`; `last` says whether `x` is
    * `fn`'s result. A counted field read, and the result of a closure's call (which may be one of
    * its captures), may be a part of the value read or called, so that value escapes where `x`
    * does: a part escaping makes the whole escape.
    */
  private def right(x: Atom.Var, rhs: Rhs, ex: Set[Escape], fn: String, last: Boolean): Map[String, Set[Escape]] =
    val where = spans(x.name)
    rhs match
      case Rhs.Move(a)                      => flows(List(a), ex)
      case Rhs.Field(o, _)                  => flows(List(o), ex)
      case Rhs.Con(_, fs)                   => flows(fs, ex + Escape.Field(x.name))
      case Rhs.Reuse(cell, _, fs, _)        => merge(flows(List(cell), ex), flows(fs, ex + Escape.Field(x.name)))
      case Rhs.Closure(code, cs, exclusive) =>
        val keeps = List(code, exclusive).filter(_.nonEmpty).exists(param(_, 0) == ParamEscape.Unbounded)
        val kept = Option.when(keeps)(Escape.Unbounded(s"kept by the code of closure `${x.name}`, at ${where.start}"))
        flows(cs, ex + Escape.Closure(x.name) ++ kept)
      case Rhs.CallClosure(c, as, _) =>
        val unseen = Escape.Unbounded(s"passed to a closure the compiler cannot see, at ${where.start}")
        merge(flows(List(c), ex), flows(as, Set(unseen)))
      case Rhs.CallTable(_, _, as, _) =>
        flows(as, Set(Escape.Unbounded(s"passed through a capability table, at ${where.start}")))
      case Rhs.Call(f, as, true) if f == "fx_abort" =>
        flows(as, Set(Escape.Unbounded(s"an abort payload, at ${where.start}")))
      case Rhs.Call(f, as, false) if last && f == fn =>
        merge(call(f, as, ex, where, fn), flows(as, Set(Escape.Carried(fn))))
      case Rhs.Call(f, as, false) => call(f, as, ex, where, fn)
      case Rhs.Catch(f, as)       => call(f, as, ex, where, fn)
      case Rhs.If(_, t, e)        => merge(walk(t, ex, fn, last), walk(e, ex, fn, last))
      case Rhs.Switch(_, cases)   => cases.map(c => walk(c._2, ex, fn, last)).foldLeft(Map.empty)(merge)
      case _                      => Map.empty

  /** A call's arguments escape as `f`'s parameters do. A loop's carried value instead escapes as
    * its step's parameter does, when the step is a closure built here: the loop itself only hands
    * it to the step and returns what the step returns, or, a left fold on an empty input, the
    * initial value itself.
    */
  private def call(f: String, as: List[Atom], ex: Set[Escape], where: Span, fn: String): Map[String, Set[Escape]] =
    def by(e: ParamEscape, who: String) = e match
      case ParamEscape.None       => Set.empty[Escape]
      case ParamEscape.IntoResult => ex
      case ParamEscape.Unbounded  => Set[Escape](Escape.Unbounded(s"kept by `$who` beyond the call, at ${where.start}"))
    as.zipWithIndex.collect {
      case (v: Atom.Var, i) if v.ctype.counted =>
        if !carried(f, i) then Map(v.name -> by(param(f, i), f))
        else
          val step = as.drop(i + 1).flatMap(Core.countedVar).flatMap(s => closureMap(fn).get(s.name)).headOption
          val returned = if f.contains(".foldLeft[") then ex else Set.empty
          val viaStep = step.fold(by(ParamEscape.Unbounded, f))(code => by(param(code, 1), code))
          Map(v.name -> (viaStep ++ returned + Escape.Carried(f)))
    }.foldLeft(Map.empty[String, Set[Escape]])(merge)

  private val closureMaps = mutable.HashMap.empty[String, Map[String, String]]

  /** The closures function `fn` builds: variable to code function. */
  private def closureMap(fn: String): Map[String, String] =
    closureMaps.getOrElseUpdate(
      fn,
      byName.get(fn).fold(Map.empty)(f =>
        Core.lets(f.body).collect { case (x, Rhs.Closure(code, _, _)) => x.name -> code }.toMap
      )
    )

  /** The seed of a derived `unfold` and the accumulator of a left fold are carried by the loop. */
  private def carried(f: String, i: Int): Boolean =
    (f.contains(".unfold[") && i == 0) || (f.contains(".foldLeft[") && i == 1)

  private def classes(f: Fn): List[Allocation] =
    val all = Core.lets(f.body)
    val esc = escapeOf.getOrElse(f.name, Map.empty)
    val sites = all.collect {
      case (x, Rhs.Con(_, fs)) if fs.nonEmpty && x.ctype.boxed => x.name
      case (x, _: Rhs.Reuse)                                   => x.name
      case (x, Rhs.Closure(_, cs, _)) if cs.nonEmpty           => x.name
    }
    val frame = framed(f)
    val longLived = sites.flatMap(x => long(x, esc.getOrElse(x, Set.empty), f.name).map(x -> _)).toMap
    val group = groups(longLived.keySet, esc)
    sites.map { x =>
      val es = esc.getOrElse(x, Set.empty)
      val (cls, why) = longLived.get(x) match
        case Some(reason) =>
          group.get(x).fold((LifetimeClass.Heap, reason))(r =>
            (LifetimeClass.Region(r), s"$reason; in the cohesive group of `$r`")
          )
        case None if frame(x) => (LifetimeClass.Frame(f.name), "")
        case None             => (LifetimeClass.Dest, "returned")
      Allocation(x, cls, es, Option(why).filter(_.nonEmpty).toList, spans(x))
    }

  /** The counted variables of `f` that are `Frame` of it: neither returned nor long-lived. */
  private def framed(f: Fn): Set[String] =
    val esc = escapeOf.getOrElse(f.name, Map.empty)
    Core.lets(f.body).collect {
      case (x, _)
          if x.ctype.counted && !esc.getOrElse(x.name, Set.empty)(Escape.Result) &&
            long(x.name, esc.getOrElse(x.name, Set.empty), f.name).isEmpty =>
        x.name
    }.toSet

  /** Why allocation `x` is long-lived (Rule MM5.2), if it is. A value passed to a self call in
    * tail position is carried by that loop past the scope that built it (Rule MM5.1).
    */
  private def long(x: String, es: Set[Escape], fn: String): Option[String] =
    es.collectFirst { case Escape.Unbounded(why) => why }.orElse(
      es.collectFirst {
        case Escape.Carried(loop) if loop == fn =>
          "passed to a self call in tail position, a jump that ends this scope (Rule MM5.1)"
        case Escape.Carried(loop) if !garbageFree(loop, x, fn) =>
          s"carried by `$loop`, whose step is not garbage-free for it (Rule MM5.2(b))"
      }
    )

  /** A step is garbage-free for its carried value (its parameter 1, after the closure itself) if
    * it reuses it (`exclusive`) or keeps it in its result; an unknown step is not.
    */
  private def garbageFree(loop: String, x: String, fn: String): Boolean =
    val calls = byName.get(fn).toList.flatMap(f => Core.lets(f.body)).collect { case (_, Rhs.Call(g, as, false)) =>
      (g, as)
    }
    calls.find((g, as) => g == loop && as.exists(Core.countedVar(_).exists(_.name == x))).exists { (_, as) =>
      as.flatMap(Core.countedVar).flatMap(v => closureMap(fn).get(v.name)).exists { code =>
        val i = 1
        demands.params.get(code).flatMap(_.lift(i)).exists(_.root == Demand.Excl) ||
        signatures.get(code).flatMap(_.lift(i)).contains(ParamEscape.IntoResult)
      }
    }

  /** Cohesive groups (Rule MM5.4) among this function's long-lived allocations: a root stored in
    * nothing, and the allocations stored only into the group. Groups of one are not regions. An
    * escape set inherits its container's `Field`s, so the direct container is the one whose own set
    * accounts for all the others.
    */
  private def groups(longLived: Set[String], esc: Map[String, Set[Escape]]): Map[String, String] =
    def into(x: String) = esc.getOrElse(x, Set.empty).collect { case Escape.Field(b) => b }
    def direct(x: String) = into(x).filter(b => into(x) - b == into(b))
    def rootOf(x: String, seen: Set[String]): Option[String] =
      direct(x).toList match
        case Nil                                                  => Some(x)
        case List(b) if longLived(b) && !seen(b) && !carriedIn(x) => rootOf(b, seen + x)
        case _                                                    => None
    def carriedIn(x: String) = esc.getOrElse(x, Set.empty).exists { case _: Escape.Carried => true; case _ => false }
    val roots = longLived.toList.flatMap(x => rootOf(x, Set.empty).map(x -> _)).toMap
    roots.filter((_, r) => roots.values.count(_ == r) >= 2)

  /** Fork facts for each derived `fold` / `unfold` and left fold `f` calls (Rule Q5.13). A loop's
    * calls of itself are its lanes, not fork sites of their own.
    */
  private def forks(f: Fn): List[ForkFact] =
    val closures = closureMap(f.name)
    Core.lets(f.body).collect {
      case (x, Rhs.Call(g, as, false))
          if g != f.name && (g.contains(".fold[") || g.contains(".unfold[") || g.contains(".foldLeft[")) =>
        val steps = as.drop(1).flatMap(Core.countedVar)
        val isTree = !g.contains(".foldLeft[") && tree(g)
        val (ok, why) =
          if g.contains(".foldLeft[") then
            (false, "a sequence fold: needs a proved associative operation (Rule Q5.13.a.3, M9)")
          else if !isTree then (false, "one recursive position: not a tree")
          else
            steps.find(v => !closures.get(v.name).exists(pure(_, Set.empty))) match
              case Some(v) => (false, s"`${v.name}` may perform an effect or is not known")
              case None    => (true, "tree with pure steps")
        ForkFact(x.name, g, isTree, ok, why, spans(x.name))
    }

  /** Some branch of the loop recurses at least twice. */
  private def tree(loop: String): Boolean =
    byName.get(loop).exists(l =>
      branches(l.body).exists(b => Core.callees(b).contains(loop) && selfCalls(b, loop) >= 2)
    )

  private def branches(e: Expr): List[Expr] =
    Core.lets(e).collect { case (_, Rhs.Switch(_, cases)) => cases.map(_._2) }.flatten

  private def selfCalls(e: Expr, loop: String): Int =
    Core.lets(e).count { case (_, Rhs.Call(g, _, false)) => g == loop; case _ => false }

  /** A function that performs no effect, calls no closure it cannot see, and calls only pure functions. */
  private def pure(fn: String, seen: Set[String]): Boolean =
    seen(fn) || byName.get(fn).exists { f =>
      Core.lets(f.body).forall {
        case (_, Rhs.Call(g, _, true))  => !effects(g)
        case (_, Rhs.Call(g, _, false)) => pure(g, seen + fn)
        case (_, Rhs.Catch(g, _))       => pure(g, seen + fn)
        case (_, _: Rhs.CallClosure)    => false
        case (_, _: Rhs.CallTable)      => false
        case _                          => true
      }
    }
