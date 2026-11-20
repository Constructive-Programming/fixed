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

/** A clone candidate (`spec/compilation_model.md` Rule CM5.4): its canonical key, `removed` and
  * `weight`. `reason` is empty for a clone that was built, else why it was not (Rule CM5.8, plus
  * `unused` for a clone dropped because no call reached it, see [[Versions.run]]). A
  * clone a `fip` body needs is built whatever the budget and is not `charged`.
  */
final case class Candidate(key: String, removed: Int, weight: Long, reason: String, charged: Boolean)

/** Shared versions and exclusive clones on the ownership axis (`spec/quantities.md` Rule Q9.3,
  * `spec/compilation_model.md` §5). Every function instance reached from `main` has its shared
  * version, which keeps the instance's name. A call passes an argument as exclusive only where
  * [[Demands.exclusive]] holds and the caller owns every demanded part, the same test demand
  * inference used; that call requests the clone of its callee for that ownership mask.
  *
  * Clones are chosen by Rule CM5.4 in rounds: the requests of the versions built so far are
  * ranked per function, in the order of the function's name, by `removed × weight` (ties by key
  * text), and built while the function's charges stay within `budget` (Rule CM5.3); each clone is
  * charged the function's size in Core `let`s. `removed` counts the sites where the clone's walk
  * decides differently from the shared version's: a reuse without a test, a call that requests a
  * clone, a closure or table call through the exclusive entry. `weight` sums `8^d` over the requesting call
  * sites, `d` being the number of loop steps enclosing the site ([[depths]]). A call whose clone
  * was not built calls the shared version; nothing is copied (Rule CM5.6).
  *
  * A `fip fn` has one body, its exclusive version, under its own name, and a shared entry
  * `f#shared` that copies the demanded part of each argument and calls the body (Rule CM5.5);
  * neither is charged, nor are the clones the body requests: without them it would call versions
  * that test counts, which a `fip fn` may not. Every closure's primary entry is the shared version of its code; its
  * exclusive entry is a clone, requested by the closure's construction (Rule PR5.6). A closure
  * call picks one statically.
  *
  * A constructor paired with a dead cell by demand inference becomes a `Reuse`: unconditional where
  * this version owns the cell exclusively, else after a runtime `rc == 1` test (hybrid reuse, Rule
  * PR6.3).
  *
  * Result ownership per version is a summary: optimistic at first (fresh results are exclusive),
  * then recomputed until no summary changes ([[run]]).
  *
  * The budget is shared with the type axis ([[Specialise]]): a clone of the type axis is an
  * instance here, but its ownership clones are charged to the function it is a clone of, at the
  * size of that function's baseline version, after the type axis's own charges `spent`. That
  * baseline version is a root, like `main`, so that it is built even where every caller calls a
  * clone (Rule CM3.1).
  *
  * @param copy the copy function of a type key for a set of parts, if the type is data
  * @param budget the clone budget `B` of each function, in Core `let`s
  * @param typeClones each clone of the type axis, with the function it is a clone of and its key
  * @param spent per function, what its clones of the type axis are charged
  * @param known whether a type key names a type without type parameters ([[construct]])
  * @param ownership whether clones of the ownership axis are chosen (`--no-ownership-clones`); the
  *   mandatory ones a `fip` body forces are built either way
  */
final class Versions(
    d: Demands,
    table: DemandTable,
    fns: List[Fn],
    copy: (String, Set[Need.Part]) => Option[String],
    fip: String => Boolean,
    budget: Int,
    typeClones: Map[String, (String, String)] = Map.empty,
    spent: Map[String, Int] = Map.empty,
    known: String => Boolean = _ => false,
    ownership: Boolean = true
):
  private type Mask = List[Set[Option[Need.Part]]]
  private type Key = (String, Mask)

  private val byName = fns.map(f => f.name -> f).toMap
  private val results = mutable.HashMap.empty[String, Own]
  private val built = mutable.LinkedHashMap.empty[String, Fn]
  private val queue = mutable.Queue.empty[(String, Boolean)]
  private val queued = mutable.HashSet.empty[String]

  /** The clones built, those among them a `fip` body needs (not charged), and those dropped. */
  private val clones = mutable.LinkedHashSet.empty[Key]
  private val forced = mutable.HashSet.empty[Key]
  private val unused = mutable.HashSet.empty[Key]

  /** Per candidate: its weight so far, its `removed`, and why it was not built; per function, the
    * decisions of its shared version's sites.
    */
  private val weights = mutable.LinkedHashMap.empty[Key, Long]
  private val removals = mutable.HashMap.empty[Key, Int]
  private val reasons = mutable.HashMap.empty[Key, String]
  private val sharedSites = mutable.HashMap.empty[String, List[String]]

  /** The version being walked (its instance's reuse pairs come from demand inference) and what
    * the walk decides at each site and requests of its callees.
    */
  private val current = scala.util.DynamicVariable("")
  private val log = scala.util.DynamicVariable(Log())
  private val copies = Iterator.from(1)

  final private case class Log(
      sites: mutable.ListBuffer[String] = mutable.ListBuffer.empty,
      requests: mutable.ListBuffer[Key] = mutable.ListBuffer.empty
  )

  /** The functions of every version reachable from `main`. Each pass builds the shared versions
    * and the clones chosen so far, then runs the rounds; passes repeat until neither the clones
    * nor any result summary changes. Within this, clones are never removed and summaries only
    * lose exclusivity (each is met with its previous value), so it terminates, and in the last
    * pass every call site saw the final clones and summaries. A clone that no shared version or
    * `fip` body then reaches was chosen from a summary that later lost exclusivity: it is
    * dropped, never chosen again (reason `unused`) and the whole repeats, so that it holds no
    * budget. That set only grows, so this terminates too.
    */
  def run(): List[Fn] =
    val before = (results.toMap, clones.toSet)
    List(built, queued, forced, weights, removals, sharedSites, reasons).foreach(_.clear())
    ("main" :: typeClones.values.map(_._1).toList.distinct.sorted).foreach(ensure(_, Nil))
    drain()
    clones.toList.foreach(build)
    drain()
    rounds()
    if (results.toMap, clones.toSet) != before then run()
    else
      val live = Core.reach(built.values.filter(f => f.name == f.instance).map(_.name).toSet, built)
      val dead = clones.filterNot(k => live(cloneName(k)))
      if dead.isEmpty then built.values.toList
      else
        unused ++= dead
        clones --= dead
        run()

  /** Per function with candidates (a clone of the type axis's under its function), each candidate in key order. */
  def candidates: Map[String, List[Candidate]] = (weights.keySet ++ clones ++ unused).toList.groupMap(k =>
    family(k._1)
  ) { k =>
    val why = if clones(k) then "" else reasons.getOrElse(k, "unused")
    Candidate(keyText(k), removed(k), weights.getOrElse(k, 0L), why, charged = !forced(k))
  }.view.mapValues(_.sortBy(_.key)).toMap

  /** The function whose budget the clones of instance `f` are charged to. */
  private def family(f: String): String = typeClones.get(f).fold(f)(_._1)

  /** A candidate's canonical key: the type key of its instance, if a clone of the type axis, then
    * its ownership mask (Rule CM6.5).
    */
  private def keyText(k: Key): String = typeClones.get(k._1).fold("")(_._2 + " ") + text(k._2)

  /** Rule CM5.4's rounds: rank and choose per function, then build what was chosen. */
  private def rounds(): Unit =
    val chosen = weights.keys.map(k => family(k._1)).toList.distinct.sorted.flatMap(choose)
    chosen.foreach(k => if !clones(k) then build(k))
    drain()
    if chosen.nonEmpty then rounds()

  private def choose(f: String): List[Key] =
    val size = Core.lets(byName(f).body).size
    val open = weights.keys.filter(k => family(k._1) == f && !clones(k) && !unused(k)).toList
    val already = clones.count(c => family(c._1) == f && !forced(c)) * size + spent.getOrElse(f, 0)
    val (chosen, why) =
      if ownership then Versions.rank(open, removed, weights, keyText, size, already, budget)
      else (Nil, open.map(_ -> "no ownership clones").toMap)
    reasons ++= why
    chosen

  /** Sites whose decision differs between the clone for `k` and the shared version. */
  private def removed(k: Key): Int =
    val (f, mask) = k
    removals.getOrElseUpdate(k, sites(f, mask).zip(sharedSites.getOrElseUpdate(f, sites(f, shared(f)))).count(_ != _))

  private def sites(f: String, mask: Mask): List[String] =
    log.withValue(Log())(walkAt(f, mask)._3.sites.toList)

  private def shared(f: String): Mask = full(f).map(_ => Set.empty)

  /** The canonical text of an ownership mask: per parameter with exclusive parts, its index and
    * the parts (`cell` for its own cell).
    */
  private def text(mask: Mask): String =
    mask.zipWithIndex.collect {
      case (parts, i) if parts.nonEmpty =>
        s"$i:" + parts.toList.map(_.fold("cell")((k, slot) => s"$k#$slot")).sorted.mkString(",")
    }.mkString(";")

  /** What a call of `f` at `mask` resolves to (Rules CM5.5, CM5.6). */
  private def target(f: String, mask: Mask): String =
    if fip(f) then if mask == full(f) then f else entryName(f)
    else if clones((f, mask)) then cloneName((f, mask))
    else f

  /** A clone's symbol name: its function's name and its canonical key (Rule CM5.1). */
  private def cloneName(k: Key): String = s"${k._1}#x[${text(k._2)}]"

  private def entryName(f: String): String = s"$f#shared"

  /** Queue the version of `f` a call at `mask` needs whatever the budget: the shared version, or
    * for a `fip fn` its body or its shared entry.
    */
  private def ensure(f: String, mask: Mask): Unit =
    val adapter = fip(f) && mask != full(f)
    if queued.add(if adapter then entryName(f) else f) then queue.enqueue((f, adapter))

  private def drain(): Unit =
    if queue.nonEmpty then
      val (f, adapter) = queue.dequeue()
      if adapter then entry(f) else build(f, if fip(f) then full(f) else shared(f), f)
      drain()

  private def build(k: Key): Unit =
    clones += k
    build(k._1, k._2, cloneName(k))

  /** Build `f` for `mask` as `name`. Its requests queue the versions they need and weigh their
    * candidates; a `fip` body, or a clone it forced, forces the clones it requests instead, so
    * that it never calls a version that tests counts (Rule CM5.5).
    */
  private def build(f: String, mask: Mask, name: String): Unit =
    val (body, result, made) = log.withValue(Log())(walkAt(f, mask))
    built(name) = byName(f).copy(name = name, body = body, version = Some(Version(f, mask != full(f))))
    results(name) = results.get(name).fold(result)(_ meet result)
    val weight = Versions.weight(depths.getOrElse(f, 0))
    made.requests.foreach { (g, m) =>
      ensure(g, if fip(g) then m else shared(g))
      val k = (g, m)
      if fip(g) || !m.exists(_.nonEmpty) then ()
      else if fip(f) || forced((f, mask)) then
        forced += k
        if !clones(k) then build(k)
      else weights(k) = weights.getOrElse(k, 0L) + weight
    }

  /** The shared entry of `fip fn f`: copy the demanded part of each argument, then call the body. */
  private def entry(f: String): Unit =
    val fn = byName(f)
    val keys = d.entry(fn).keys
    val copied = fn.params.zip(full(f)).map { (p, want) =>
      keys.get(p.name).filter(_ => want.nonEmpty).flatMap(copy(_, want.flatten)).map { cf =>
        val c: Atom.Var = Atom.Var(s"copy.${copies.next()}", CType.Boxed)
        (c, Rhs.Call(cf, List(p), runtime = false))
      }
    }
    val r: Atom.Var = Atom.Var(s"copy.${copies.next()}", fn.ret)
    val args = fn.params.zip(copied).map((p, c) => c.fold(p: Atom)(_._1))
    val body = copied.flatten.foldRight(Let(r, Rhs.Call(f, args, runtime = false), Ret(r)): Expr)((c, acc) =>
      Let(c._1, c._2, acc)
    )
    built(entryName(f)) = fn.copy(name = entryName(f), body = body, version = Some(Version(f, conditional = false)))
    ensure(f, full(f))

  private lazy val depths: Map[String, Int] = Versions.depths(fns)

  /** Per parameter, the parts its demand marks exclusive (`None` is the value's own cell). */
  private def full(f: String): Mask =
    table.params.getOrElse(f, Nil).map(n =>
      (if n.root == Demand.Excl then Set(None) else Set.empty) ++ n.parts.collect { case (k, Demand.Excl) => Some(k) }
    )

  /** `f`'s body walked for ownership `mask`: the version's body, its result's ownership and the log. */
  private def walkAt(f: String, mask: Mask): (Expr, Own, Log) =
    val fn = byName(f)
    val owns = fn.params.zipWithIndex.map { (p, i) =>
      val m = mask.lift(i).getOrElse(Set.empty)
      p.name -> Own(m(None), m.flatten, rest = false)
    }.toMap
    Core.tableEntries(fn.body).filter(byName.contains).foreach(g => log.value.requests += ((g, shared(g))))
    val (body, result) = current.withValue(f)(walk(fn.body, d.entry(fn), owns))
    (body, result, log.value)

  /** A program point: the variables live after it, the path facts and the ownership so far. */
  final private case class At(live: Set[String], ctx: d.Ctx, owns: Map[String, Own]):
    /** Ownership of `a` here: shared if another live value or an earlier keep holds it, directly or
      * through an ancestor. A live part of `a` does not share `a`'s own cell; a call that would
      * overwrite that part is caught by the aliasing test ([[Demands.exclusive]]).
      */
    def own(a: Atom): Own =
      a match
        case v: Atom.Var if v.ctype.counted =>
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
        val (rhs2, ox) = right(x, rhs, At(live, ctx, owns))
        val (body2, r) = walk(body, d.forward(x, rhs, live, ctx), owns.updated(x.name, ox))
        (Let(x, rhs2, body2), r)
      case Expr.Dup(x, b) =>
        val (b2, r) = walk(b, ctx, owns)
        (Expr.Dup(x, b2), r)
      case Expr.Drop(x, b) =>
        val (b2, r) = walk(b, ctx, owns)
        (Expr.Drop(x, b2), r)

  /** `rhs` rewritten to call versions, and its result's ownership (an abort's payload: shared). */
  private def right(x: Atom.Var, rhs: Rhs, at: At): (Rhs, Own) =
    val At(live, ctx, owns) = at
    rhs match
      case Rhs.Move(a)                                  => (rhs, at.own(a))
      case Rhs.Call(f, as, false) if byName.contains(f) => call(f, as, at)(Rhs.Call(_, _, runtime = false))
      case Rhs.Catch(f, as) if byName.contains(f)       => call(f, as, at)(Rhs.Catch(_, _))
      case Rhs.Call("fx_abort_payload", _, true)        => (rhs, Own.shared)
      case Rhs.Call(_, _, true) | _: Rhs.Prim           => (rhs, Own.all)
      case Rhs.Con(tag, fs)                             =>
        val made = construct(x, fs, at)
        table.reuses.getOrElse(current.value, Map.empty).get(x.name) match
          case Some(v) =>
            val cell = Atom.Var(v, CType.Boxed)
            val unconditional = at.own(cell).root
            log.value.sites += s"reuse $unconditional"
            (Rhs.Reuse(cell, tag, fs, unconditional), made)
          case None => (rhs, made)
      case Rhs.Field(o, slot) =>
        val parent = at.own(o)
        val key = (Core.countedVar(o).flatMap(v => ctx.keys.get(v.name)).getOrElse("?"), slot)
        (rhs, if parent.root then parent.copy(root = parent.part(key)) else Own.shared)
      case Rhs.Closure(code, cs, _) =>
        val exclusive = target(code, full(code))
        log.value.requests += ((code, full(code)))
        (Rhs.Closure(code, cs, if exclusive == code then "" else exclusive), Own(root = true, Set.empty, rest = false))
      case Rhs.CallClosure(c, as, _) =>
        val ex = indirect(as, at)
        log.value.sites += s"closure $ex"
        (Rhs.CallClosure(c, as, ex), Own.shared)
      case Rhs.CallTable(t, entry, as, _) =>
        val ex = indirect(as, at)
        log.value.sites += s"table $ex"
        (Rhs.CallTable(t, entry, as, ex), Own.shared)
      case Rhs.If(c, t, f) =>
        val branch = ctx.copy(after = live)
        val ((t2, o1), (f2, o2)) = (walk(t, branch, owns), walk(f, branch, owns))
        (Rhs.If(c, t2, f2), o1 meet o2)
      case Rhs.Switch(s, cases) =>
        val walked = cases.map { (tag, c) =>
          val tags = Core.countedVar(s).fold(ctx.tags)(v => ctx.tags.updated(v.name, tag))
          (tag, walk(c, ctx.copy(after = live, tags = tags), owns))
        }
        (Rhs.Switch(s, walked.map((t, w) => (t, w._1))), walked.map(_._2._2).reduceOption(_ meet _).getOrElse(Own.all))
      case _ => (rhs, Own.shared)

  /** Whether a call through a closure or a table may take the exclusive pointer: this version
    * exclusively owns every counted argument in every part (Rules PR5.6, CM4.6).
    */
  private def indirect(as: List[Atom], at: At): Boolean =
    val args = as.flatMap(Core.countedVar).map(_.name)
    args.nonEmpty && as.forall {
      case v: Atom.Var if v.ctype.counted => at.exclusive(v.name, args) && at.own(v).whole
      case _                              => true
    }

  /** A constructor result: a fresh cell. A counted field `v` at part `(key, slot)` that is exclusive
    * and that no live value or other field overlaps (the test [[call]] makes before it trusts an
    * argument's parts) meets the result with its parts; any other field shares only its own slot,
    * or every part if the cell's key is unknown or names a type parameter (`List of A` in a
    * baseline version, which its callers read as `List of u64`). Sound: [[right]] reads claims only from the
    * root down through exclusive slots, so they cover the cell and the first kind's regions, where
    * each cell has one reference as long as `Demands` sees every alias between live values (paths
    * and keeps). Entering another field's region from there would give a cell a second reference,
    * so its parts never matter, whatever the types: a (type, slot) key it shares with the result is
    * only removed or met, never set.
    */
  private def construct(x: Atom.Var, fs: List[Atom], at: At): Own =
    val key = d.typeKey(x.name).filter(known)
    val fields = fs.flatMap(Core.countedVar).map(_.name)
    fs.zip(Layout.slots(fs.map(_.tpe))).foldLeft(Own.all) {
      case (acc, (v: Atom.Var, slot)) if v.ctype.counted =>
        val o = at.own(v)
        if o.root && at.exclusive(v.name, fields) then acc meet o
        else key.fold(acc meet Own(root = true, Set.empty, rest = false))(k => acc.without((k, slot)))
      case (acc, _) => acc
    }

  /** A call of user function `f`: it requests the version matching what the caller owns, and calls
    * the one it resolves to. Across it, a key naming a type parameter is trusted only if all is.
    */
  private def call(f: String, as: List[Atom], at: At)(make: (String, List[Atom]) => Rhs): (Rhs, Own) =
    val want = full(f)
    val args = as.flatMap(Core.countedVar).map(_.name)
    val mask = as.zipWithIndex.map { (a, i) =>
      val w = want.lift(i).getOrElse(Set.empty)
      a match
        case v: Atom.Var if v.ctype.counted && w.nonEmpty =>
          val o = at.own(v)
          if !o.root || !at.exclusive(v.name, args) then Set.empty[Option[Need.Part]]
          else w.filter(_.forall(k => o.part(k) && (known(k._1) || o.rest && o.listed.isEmpty)))
        case _ => w
    }
    log.value.sites += s"call ${text(mask)}"
    log.value.requests += ((f, mask))
    val r = results.getOrElse(if fip(f) then f else target(f, mask), Own.all)
    (make(target(f, mask), as), if r.listed.forall(k => known(k._1)) then r else Own(r.root, Set.empty, rest = false))

object Versions:

  /** The reference clone budget of Rule CM5.3, in Core `let`s. */
  val budget: Int = 512

  /** Rule CM5.4's choice among one function's `candidates`, of baseline size `size`, whose clones
    * already built are charged `spent`: in descending order of `removed × weight` (the divisor
    * `size` is the same for all), ties by key text, each chosen while the charges stay within
    * `budget` (Rule CM5.3). The candidates chosen, in that order, and why each other one was not.
    */
  def rank[K](
      candidates: List[K],
      removed: K => Int,
      weight: K => Long,
      text: K => String,
      size: Int,
      spent: Int,
      budget: Int
  ): (List[K], Map[K, String]) =
    candidates.sortBy(k => (-removed(k) * weight(k), text(k))).foldLeft((List.empty[K], Map.empty[K, String])) {
      case ((chosen, why), k) =>
        val reason =
          if removed(k) == 0 then "removed = 0"
          else if size > budget then "larger than B"
          else if spent + (chosen.size + 1) * size > budget then "budget exhausted"
          else ""
        if reason.isEmpty then (chosen :+ k, why) else (chosen, why.updated(k, reason))
    }

  /** Rule CM5.4's weight of one request made at depth `d`: `8^d`, `d` capped at 20 to fit a `Long`. */
  def weight(d: Int): Long = 1L << (3 * math.min(d, 20))

  /** Per function of `fns`, the number of loop steps that lexically enclose its body (Rule CM5.4's
    * `d`): a closure passed to a derived loop is one step deeper than the function that builds it,
    * any other closure is as deep. Functions that are not closures are at depth 0.
    */
  def depths(fns: List[Fn]): Map[String, Int] =
    val loops = Seq(".fold[", ".unfold[", ".foldLeft[", ".map[")
    val all = fns.flatMap(f => Core.lets(f.body).map(f.name -> _))
    def from(f: String, k: Int, seen: Set[String]): Map[String, Int] =
      val lets = all.collect { case (`f`, l) => l }
      val steps = lets.collect {
        case (_, Rhs.Call(g, as, false)) if loops.exists(g.contains) => as.flatMap(Core.countedVar).map(_.name)
      }.flatten.toSet
      lets.collect {
        case (x, Rhs.Closure(code, _, _)) if !seen(code) =>
          from(code, k + (if steps(x.name) then 1 else 0), seen + code)
      }.foldLeft(Map(f -> k))(_ ++ _)
    val closures = all.collect { case (_, (_, Rhs.Closure(code, _, _))) => code }.toSet
    fns.map(_.name).filterNot(closures).foldLeft(Map.empty[String, Int])((m, f) => m ++ from(f, 0, Set(f)))
