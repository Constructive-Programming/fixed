package fixed.backend

import scala.collection.mutable

import fixed.backend.Expr.{Let, Ret}

/** Which parts of a value are exclusively owned (`spec/quantities.md` Rule Q4.1.b): its own cell
  * (`root`), and each part key (Rule Q4.1.a). Unlisted parts are exclusive iff `rest`; listed parts
  * are the exceptions.
  */
final case class Own(root: Boolean, listed: Set[Need.Part], rest: Boolean):
  def part(k: Need.Part): Boolean = listed(k) != rest

  /** Exclusive in every part. */
  def whole: Boolean = root && rest && listed.isEmpty

  /** A part is exclusive in the result iff it is exclusive in both. */
  infix def meet(that: Own): Own =
    val parts = (rest, that.rest) match
      case (true, true)   => Own(true, listed ++ that.listed, true)
      case (false, false) => Own(true, listed & that.listed, false)
      case (true, false)  => Own(true, that.listed -- listed, false)
      case (false, true)  => Own(true, listed -- that.listed, false)
    parts.copy(root = root && that.root)

  def without(k: Need.Part): Own = if rest then copy(listed = listed + k) else copy(listed = listed - k)

object Own:
  val all: Own = Own(root = true, Set.empty, rest = true)
  val shared: Own = Own(root = false, Set.empty, rest = false)

/** Shared versions and copies (`spec/quantities.md` Rule Q9.3; `docs/plans/phase-3.4-qtt.md`
  * 3.4.M3). Starting at `main`, each function instance is built once per ownership of its
  * exclusive parameters actually called: the version where every demanded part is exclusive keeps
  * the instance's name, the others get `#n`. A call passes an argument as exclusive only where
  * [[Demands.exclusive]] holds and the caller owns every demanded part, the same test demand
  * inference used. Beyond `cap` versions of one function, the caller copies the demanded part
  * (Neut's strategy, applied only here) and calls the exclusive version; a `fip fn` has no shared
  * version, so its callers always copy (Rule MM18.3). Every closure gets an
  * exclusive and a shared entry (Rule PR5.6), and a closure call picks one statically.
  *
  * A constructor paired with a dead cell by demand inference becomes a `Reuse`: unconditional where
  * this version owns the cell exclusively, else after a runtime `rc == 1` test (hybrid reuse, Rule
  * PR6.3).
  *
  * Result ownership per version is a summary: optimistic at first (fresh results are exclusive),
  * then recomputed until no summary changes. A value only becomes exclusive by construction, so
  * each round can only take exclusivity away.
  *
  * @param copy the copy function of a type key for a set of parts, if the type is data
  */
final class Versions(
    d: Demands,
    table: DemandTable,
    fns: List[Fn],
    copy: (String, Set[Need.Part]) => Option[String],
    fip: String => Boolean
):
  private type Mask = List[Set[Option[Need.Part]]]

  private val cap = 4
  private val byName = fns.map(f => f.name -> f).toMap
  private val names = mutable.LinkedHashMap.empty[(String, Mask), String]
  private val results = mutable.HashMap.empty[String, Own]
  private val built = mutable.LinkedHashMap.empty[String, Fn]
  private val queue = mutable.Queue.empty[(String, Mask, String)]
  private val queued = mutable.HashSet.empty[String]

  /** The version being built: its reuse pairs come from demand inference (by instance name). */
  private val current = scala.util.DynamicVariable("")
  private val copies = Iterator.from(1)

  /** The functions of every version reachable from `main`. */
  def run(): List[Fn] =
    val before = results.toMap
    built.clear()
    queued.clear()
    val _ = request("main", Nil)
    drain()
    if results.toMap != before then run() else built.values.toList

  private def drain(): Unit =
    if queue.nonEmpty then
      val (f, mask, name) = queue.dequeue()
      build(f, mask, name)
      drain()

  /** The version name for `f` at `mask`, queued to be built if it is not yet. */
  private def request(f: String, mask: Mask): String =
    val name = if mask == full(f) then f else names.getOrElseUpdate((f, mask), s"$f#${versions(f)}")
    if queued.add(name) then queue.enqueue((f, mask, name))
    name

  private def versions(f: String): Int = 1 + names.keys.count(_._1 == f)

  /** Per parameter, the parts its demand marks exclusive (`None` is the value's own cell). */
  private def full(f: String): Mask =
    table.params.getOrElse(f, Nil).map(n =>
      (if n.root == Demand.Excl then Set(None) else Set.empty) ++ n.parts.collect { case (k, Demand.Excl) => Some(k) }
    )

  private def build(f: String, mask: Mask, name: String): Unit =
    val fn = byName(f)
    val owns = fn.params.zipWithIndex.map { (p, i) =>
      val m = mask.lift(i).getOrElse(Set.empty)
      p.name -> Own(m(None), m.flatten, rest = false)
    }.toMap
    val (body, result) = current.withValue(name)(walk(fn.body, d.entry(fn), owns))
    built(name) = fn.copy(name = name, body = body)
    results(name) = result

  /** A program point: the variables live after it, the path facts and the ownership so far. */
  final private case class At(live: Set[String], ctx: d.Ctx, owns: Map[String, Own]):
    /** Ownership of `a` here: shared if another live value or an earlier keep holds it, directly or
      * through an ancestor. A live part of `a` does not share `a`'s own cell; a call that would
      * overwrite that part is caught by the aliasing test ([[Demands.exclusive]]).
      */
    def own(a: Atom): Own =
      a match
        case v: Atom.Var if v.ctype.boxed =>
          val p = ctx.path(v.name)
          if live.exists(l => l != v.name && ctx.path(l).covers(p)) || ctx.kept.exists(_.covers(p)) then Own.shared
          else owns.getOrElse(v.name, Own.shared)
        case _ => Own.all

    def exclusive(v: String, args: List[String]): Boolean = d.exclusive(v, args, live, ctx)

  private def walk(e: Expr, ctx: d.Ctx, owns: Map[String, Own]): (Expr, Own) =
    e match
      case Ret(a)            => (e, At(ctx.after, ctx, owns).own(a))
      case Let(x, rhs, body) =>
        val live = (d.names(body) - x.name) ++ ctx.after
        val (pre, rhs2, ox) = right(x, rhs, At(live, ctx, owns))
        val (body2, r) = walk(body, d.forward(x, rhs, live, ctx), owns.updated(x.name, ox))
        (pre.foldRight(Let(x, rhs2, body2): Expr)((p, acc) => Let(p._1, p._2, acc)), r)
      case Expr.Dup(x, b) =>
        val (b2, r) = walk(b, ctx, owns)
        (Expr.Dup(x, b2), r)
      case Expr.Drop(x, b) =>
        val (b2, r) = walk(b, ctx, owns)
        (Expr.Drop(x, b2), r)

  /** `rhs` rewritten to call versions, the copies to bind before it, and its result's ownership. */
  private def right(x: Atom.Var, rhs: Rhs, at: At): (List[(Atom.Var, Rhs)], Rhs, Own) =
    val At(live, ctx, owns) = at
    def plain(o: Own) = (Nil, rhs, o)
    rhs match
      case Rhs.Move(a)                                  => plain(at.own(a))
      case Rhs.Call(f, as, false) if byName.contains(f) => call(f, as, at)(Rhs.Call(_, _, runtime = false))
      case Rhs.Catch(f, as) if byName.contains(f)       => call(f, as, at)(Rhs.Catch(_, _))
      case Rhs.Call(_, _, true) | _: Rhs.Prim           => plain(Own.all)
      case Rhs.Con(tag, fs)                             =>
        val made = construct(x, fs, at)
        table.reuses.getOrElse(Memory.base(current.value), Map.empty).get(x.name) match
          case Some(v) =>
            val cell = Atom.Var(v, CType.Boxed)
            (Nil, Rhs.Reuse(cell, tag, fs, unconditional = at.own(cell).root), made)
          case None => plain(made)
      case Rhs.Field(o, slot) =>
        val parent = at.own(o)
        val key = (Core.boxedVar(o).flatMap(v => ctx.keys.get(v.name)).getOrElse("?"), slot)
        plain(if parent.root then parent.copy(root = parent.part(key)) else Own.shared)
      case Rhs.Closure(code, cs, _) =>
        val exclusive = request(code, full(code))
        (
          Nil,
          Rhs.Closure(exclusive, cs, request(code, full(code).map(_ => Set.empty))),
          Own(root = true, Set.empty, rest = false)
        )
      case Rhs.CallClosure(c, as, _) =>
        val args = as.flatMap(Core.boxedVar).map(_.name)
        val ex = args.nonEmpty && as.forall {
          case v: Atom.Var if v.ctype.boxed => at.exclusive(v.name, args) && at.own(v).whole
          case _                            => true
        }
        (Nil, Rhs.CallClosure(c, as, ex), Own.shared)
      case Rhs.If(c, t, f) =>
        val branch = ctx.copy(after = live)
        val ((t2, o1), (f2, o2)) = (walk(t, branch, owns), walk(f, branch, owns))
        (Nil, Rhs.If(c, t2, f2), o1 meet o2)
      case Rhs.Switch(s, cases) =>
        val walked = cases.map { (tag, c) =>
          val tags = Core.boxedVar(s).fold(ctx.tags)(v => ctx.tags.updated(v.name, tag))
          (tag, walk(c, ctx.copy(after = live, tags = tags), owns))
        }
        (
          Nil,
          Rhs.Switch(s, walked.map((t, w) => (t, w._1))),
          walked.map(_._2._2).reduceOption(_ meet _).getOrElse(Own.all)
        )
      case _ => plain(Own.shared)

  /** A constructor result: its cell is fresh; a part is exclusive iff every field is exclusive in
    * it, and the field stored at that part is itself exclusive.
    */
  private def construct(x: Atom.Var, fs: List[Atom], at: At): Own =
    val key = d.typeKey(x.name).getOrElse("?")
    val others = (v: String) => fs.flatMap(Core.boxedVar).map(_.name).filter(_ != v).toSet
    fs.zip(Layout.slots(fs.map(_.tpe))).foldLeft(Own.all) {
      case (acc, (v: Atom.Var, slot)) if v.ctype.boxed =>
        val o = at.copy(live = at.live ++ others(v.name)).own(v)
        val met = acc meet o.copy(root = true)
        if o.root then met else met.without((key, slot))
      case (acc, _) => acc
    }

  /** A call of user function `f`: the version matching what the caller owns, or a copy first. */
  private def call(
      f: String,
      as: List[Atom],
      at: At
  )(make: (String, List[Atom]) => Rhs): (List[(Atom.Var, Rhs)], Rhs, Own) =
    val want = full(f)
    val args = as.flatMap(Core.boxedVar).map(_.name)
    val mask = as.zipWithIndex.map { (a, i) =>
      val w = want.lift(i).getOrElse(Set.empty)
      a match
        case v: Atom.Var if v.ctype.boxed && w.nonEmpty =>
          val o = at.own(v)
          if !o.root || !at.exclusive(v.name, args) then Set.empty[Option[Need.Part]]
          else w.filter(_.forall(o.part))
        case _ => w
    }
    if mask == want || (!fip(f) && (names.contains((f, mask)) || versions(f) < cap)) then
      val name = request(f, mask)
      (Nil, make(name, as), results.getOrElse(name, Own.all))
    else
      val copied = as.zipWithIndex.map { (a, i) =>
        (a, Core.boxedVar(a).filter(_ => mask(i) != want(i))) match
          case (_, Some(v)) =>
            at.ctx.keys.get(v.name).flatMap(copy(_, want(i).flatten)).map { cf =>
              val c: Atom.Var = Atom.Var(s"copy.${copies.next()}", CType.Boxed)
              (Some((c, Rhs.Call(cf, List(v), runtime = false): Rhs)), c: Atom)
            }.getOrElse((Option.empty[(Atom.Var, Rhs)], a))
          case _ => (Option.empty[(Atom.Var, Rhs)], a)
      }
      val pre = copied.flatMap(_._1)
      val name = request(f, if pre.size == as.indices.count(i => mask(i) != want(i)) then want else mask)
      (pre, make(name, copied.map(_._2)), results.getOrElse(name, Own.all))
