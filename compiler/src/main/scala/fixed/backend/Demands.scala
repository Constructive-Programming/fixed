package fixed.backend

import scala.collection.mutable

import fixed.backend.Expr.{Let, Ret}

/** What a function does with a value it receives (`spec/quantities.md` Rule Q4.1), ordered
  * `Zero < Read < Own < Excl`.
  */
enum Demand:
  case Zero, Read, Own, Excl

  infix def join(that: Demand): Demand = if ordinal >= that.ordinal then this else that

  def show: String = this match
    case Zero => "0"
    case Read => "read"
    case Own  => "own"
    case Excl => "exclusive"

/** A demand on a value and on its parts (Rule Q4.1.a). A part is a field slot of a type, keyed by
  * the type's rendering, so every cell of a recursive type shares its slots' demands.
  */
final case class Need(root: Demand, parts: Map[Need.Part, Demand]):
  infix def join(that: Need): Need =
    Need(root join that.root, (parts.keySet ++ that.parts.keySet).map(k => k -> (part(k) join that.part(k))).toMap)

  def part(k: Need.Part): Demand = parts.getOrElse(k, Demand.Zero)

  /** This need where the caller cannot hand over exclusive ownership: the shared version's need. */
  def shared: Need =
    def cap(d: Demand) = if d == Demand.Excl then Demand.Own else d
    Need(cap(root), parts.view.mapValues(cap).toMap)

object Demands:

  /** Demands of every function instance `lower` built. */
  def of(lower: Lower, fns: List[Fn], render: fixed.core.Type => String): DemandTable =
    analyse(lower, fns, render)._2

  /** The analysis over `fns` and its result. */
  def analyse(lower: Lower, fns: List[Fn], render: fixed.core.Type => String): (Demands, DemandTable) =
    val keyed = typesByKey(lower, render).get
    val d = Demands(
      fns,
      v => lower.types.get(v).map(render),
      (k, slot) => keyed(k).flatMap(lower.slotType(_, slot)).map(render),
      (k, tag) => keyed(k).flatMap(lower.cellSize(_, tag))
    )
    (d, d.run())

  /** Each type lowering recorded, by its part key rendering. */
  def typesByKey(lower: Lower, render: fixed.core.Type => String): Map[String, fixed.core.Type] =
    lower.types.values.map(t => render(t) -> t).toMap

object Need:
  type Part = (String, Int)
  val zero: Need = Need(Demand.Zero, Map.empty)
  val read: Need = Need(Demand.Read, Map.empty)
  val own: Need = Need(Demand.Own, Map.empty)
  val excl: Need = Need(Demand.Excl, Map.empty)

/** Inferred demands per function instance, and the reuse pairs that make a parameter exclusive:
  * for each function, constructor result variable to the variable whose cell it would reuse.
  */
final class DemandTable(val params: Map[String, List[Need]], val reuses: Map[String, Map[String, String]])

/** Demand inference over Core (`docs/plans/phase-3.4-qtt.md` §3.2). One backward walk per
  * function instance, repeated over the call graph until no parameter's demand changes; demands
  * only grow, and each part has four levels, so this terminates.
  *
  * A use is exclusive (Rule Q4.1.c) only where the value still has no other reference: it is the
  * value's last use on its path, no other argument of the same call overlaps it (Rule Q9.4), and
  * no earlier use on the path kept an overlapping value. Values are tracked by access path from a
  * root variable, so `xs.head` and `xs.tail` do not overlap each other, while both overlap `xs`.
  *
  * @param typeKey the type rendering of a variable lowering typed, if any
  * @param slotKey the type rendering of slot `slot` of a value of the given type key
  * @param cellSize the payload slot count of variant `tag` of a value of the given type key
  */
final class Demands(
    fns: List[Fn],
    private[backend] val typeKey: String => Option[String],
    slotKey: (String, Int) => Option[String],
    cellSize: (String, Int) => Option[Int]
):
  /** Where a variable's value lives: a root variable and the slot path below it. */
  final private[backend] case class Path(root: String, slots: List[Int]):
    def below(slot: Int): Path = Path(root, slots :+ slot)

    /** `this` is `that` or an ancestor of it. */
    def covers(that: Path): Boolean = root == that.root && that.slots.startsWith(slots)

    def overlaps(that: Path): Boolean = covers(that) || that.covers(this)

  /** Path facts flowing forward along one path of a function body. */
  final private[backend] case class Ctx(
      paths: Map[String, Path],
      keys: Map[String, String],
      kept: List[Path],
      tags: Map[String, Int],
      paired: Set[String],
      after: Set[String],
      moved: List[Path] = Nil
  ):
    def path(v: String): Path = paths.getOrElse(v, Path(v, Nil))

  private val byName = fns.map(f => f.name -> f).toMap
  private val signatures = mutable.HashMap.empty[String, List[Need]]
  private val reuses = mutable.HashMap.empty[String, mutable.Map[String, String]]

  def run(): DemandTable =
    fixpoint()
    DemandTable(signatures.toMap, reuses.view.mapValues(_.toMap).toMap)

  private def fixpoint(): Unit =
    val before = signatures.toMap
    fns.foreach(f =>
      reuses(f.name) = mutable.HashMap.empty
      val needs = walk(f.body, entry(f), f.name)
      signatures(f.name) = f.params.map(p => needs.getOrElse(p.name, Need.zero))
    )
    if signatures.toMap != before then fixpoint()

  /** The path facts at the start of `f`'s body. */
  private[backend] def entry(f: Fn): Ctx =
    Ctx(Map.empty, f.params.flatMap(p => typeKey(p.name).map(p.name -> _)).toMap, Nil, Map.empty, Set.empty, Set.empty)

  private def sig(f: String, i: Int): Need =
    signatures.get(f).flatMap(_.lift(i)).getOrElse(if byName.contains(f) then Need.zero else Need.own)

  private[backend] def names(e: Expr): Set[String] = Core.freeVars(e).map(_.name)

  private def join(a: Map[String, Need], b: Map[String, Need]): Map[String, Need] =
    Core.merge(a, b, Need.zero)(_ join _)

  private def walk(e: Expr, ctx: Ctx, fn: String): Map[String, Need] =
    e match
      case Ret(a)            => uses(List(a), Need.own)
      case Let(x, rhs, body) =>
        val live = (names(body) - x.name) ++ ctx.after
        val reused = reuse(x, rhs, live, ctx, fn)

        /** Branches first (they do not depend on what the rest demands of `x`): a cell a branch's
          * constructor reuses is not reused again after the `If` or `Switch`.
          */
        val before = reuses(fn).keySet.toSet
        val branches = Option.when(Core.branches(rhs).nonEmpty)(right(rhs, Need.zero, live, ctx, fn))
        val inBranches = reuses(fn).collect { case (y, v) if !before(y) => v }
        val inner = walk(body, forward(x, rhs, live, ctx).copy(paired = ctx.paired ++ reused ++ inBranches), fn)
        val xNeed = inner.getOrElse(x.name, Need.zero)
        val here = branches.getOrElse(right(rhs, xNeed, live, ctx, fn))
        join(join(here, inner - x.name), reused.map(_ -> Need.excl).toMap)
      case Expr.Dup(_, b)  => walk(b, ctx, fn)
      case Expr.Drop(_, b) => walk(b, ctx, fn)

  /** `ctx` after `let x = rhs`: `x`'s path and type, and the paths `rhs` keeps while they stay live. */
  private[backend] def forward(x: Atom.Var, rhs: Rhs, live: Set[String], ctx: Ctx): Ctx =
    val (path, key) = rhs match
      case Rhs.Field(o: Atom.Var, slot) =>
        (ctx.path(o.name).below(slot), ctx.keys.get(o.name).flatMap(slotKey(_, slot)))
      case Rhs.Move(v: Atom.Var) => (ctx.path(v.name), ctx.keys.get(v.name))
      case _                     => (Path(x.name, Nil), typeKey(x.name))

    /** A counted `If` or `Switch` result may be any value its branches use, or a part of one. */
    val used = Core.branches(rhs).flatMap(names)
    val aliased = if x.ctype.counted then used else Nil
    val keeps = (owned(rhs) ++ aliased).filter(v => live.exists(l => ctx.path(l).overlaps(ctx.path(v)))).map(ctx.path)

    /** An `If` or `Switch` hands its branches what they use; what is dead after it dies inside them (`Rc`). */
    val inBranches =
      if Core.branches(rhs).isEmpty then Nil
      else (Core.atoms(rhs).flatMap(Core.countedVar).map(_.name) ++ used).filterNot(live)
    ctx.copy(
      paths = ctx.paths.updated(x.name, path),
      keys = key.fold(ctx.keys)(k => ctx.keys.updated(x.name, k)),
      kept = keeps ++ ctx.kept,
      moved = (owned(rhs) ++ inBranches).map(ctx.path) ++ ctx.moved
    )

  /** Counted variables a right-hand side keeps (stores, or passes to a parameter that keeps it). */
  private def owned(rhs: Rhs): List[String] =
    def vars(as: List[Atom]) = as.flatMap(Core.countedVar).map(_.name)
    rhs match
      case Rhs.Con(_, fs)             => vars(fs)
      case Rhs.Closure(_, cs, _)      => vars(cs)
      case Rhs.CallClosure(c, as, _)  => vars(c :: as)
      case Rhs.CallTable(_, _, as, _) => vars(as)
      case Rhs.Call(f, as, _)         => keptBy(f, as)
      case Rhs.Catch(f, as)           => keptBy(f, as)
      case _                          => Nil

  /** The counted arguments of a call of `f` whose parameters keep them, or a part of them ([[keeps]]). */
  private def keptBy(f: String, as: List[Atom]): List[String] =
    as.zipWithIndex.collect {
      case (v: Atom.Var, i) if v.ctype.counted && keeps(sig(f, i)) => v.name
    }

  /** Whether a parameter of need `n` keeps (a part of) its argument: `tl(xs)` returns a part of `xs`
    * and reads only its root, and its result's path does not show that it aliases `xs`.
    */
  private def keeps(n: Need): Boolean = (n.root :: n.parts.values.toList).exists(_.ordinal >= Demand.Own.ordinal)

  /** Whether `v`, an argument among `args`, still has no other reference at this use. */
  private[backend] def exclusive(v: String, args: List[String], live: Set[String], ctx: Ctx): Boolean =
    val p = ctx.path(v)
    !live.exists(l => ctx.path(l).overlaps(p)) &&
    args.count(a => ctx.path(a).overlaps(p)) == 1 &&
    !ctx.kept.exists(_.overlaps(p))

  /** A constructor reuses the cell of a variable of matching size that died on this path (drop
    * guided, Rule MM11.5): the variable is then demanded exclusive. Its children may stay live. A
    * variable moved into another value or call earlier on the path did not die here: its cell
    * belongs to the new owner. Nor did one that died inside an earlier branch, which dropped it.
    * The variant of a candidate is known inside a `Switch` case, or always for single-variant types.
    */
  private def reuse(x: Atom.Var, rhs: Rhs, live: Set[String], ctx: Ctx, fn: String): Option[String] =
    rhs match
      case Rhs.Con(_, fields) if fields.nonEmpty =>
        val inFields = fields.flatMap(Core.countedVar).map(_.name).toSet
        val products = ctx.keys.collect { case (v, k) if cellSize(k, 1).isEmpty && cellSize(k, 0).isDefined => v -> 0 }
        (products ++ ctx.tags).toList.sortBy(_._1).collectFirst {
          case (v, tag)
              if !ctx.paired(v) && !inFields(v) &&
                ctx.keys.get(v).flatMap(cellSize(_, tag)).contains(fields.size) &&
                !live.exists(l => ctx.path(l).covers(ctx.path(v))) &&
                !ctx.kept.exists(_.covers(ctx.path(v))) &&
                !ctx.moved.exists(_.covers(ctx.path(v))) =>
            reuses(fn)(x.name) = v
            v
        }
      case _ => None

  /** What `rhs` demands of the variables it uses; `xNeed` is what the rest demands of its result. */
  private def right(rhs: Rhs, xNeed: Need, live: Set[String], ctx: Ctx, fn: String): Map[String, Need] =
    rhs match
      case Rhs.Move(v: Atom.Var)        => Map(v.name -> xNeed)
      case Rhs.Move(_)                  => Map.empty
      case Rhs.Prim(_, as)              => uses(as, Need.read)
      case Rhs.Call(f, as, false)       => call(f, as, live, ctx)
      case Rhs.Catch(f, as)             => call(f, as, live, ctx)
      case Rhs.Call(_, as, true)        => uses(as, Need.own)
      case Rhs.Con(_, fs)               => uses(fs, Need.own)
      case Rhs.Reuse(c, _, fs, _)       => uses(c :: fs, Need.own)
      case Rhs.Closure(_, cs, _)        => uses(cs, Need.own)
      case Rhs.CallClosure(c, as, _)    => uses(c :: as, Need.own)
      case Rhs.CallTable(t, _, as, _)   => join(uses(List(t), Need.read), uses(as, Need.own))
      case Rhs.Field(o: Atom.Var, slot) =>
        val key = ctx.keys.getOrElse(o.name, "?")
        Map(o.name -> Need(Demand.Read, xNeed.parts.updated((key, slot), xNeed.root join xNeed.part((key, slot)))))
      case Rhs.Field(_, _) => Map.empty
      case Rhs.If(c, t, f) =>
        val branch = ctx.copy(after = live)
        join(uses(List(c), Need.read), join(walk(t, branch, fn), walk(f, branch, fn)))
      case Rhs.Switch(s, cases) =>
        val tagged = (tag: Int) =>
          Core.countedVar(s).fold(ctx.copy(after = live))(v =>
            ctx.copy(after = live, tags = ctx.tags.updated(v.name, tag))
          )
        cases.foldLeft(uses(List(s), Need.read))((acc, c) => join(acc, walk(c._2, tagged(c._1), fn)))

  /** A call of user function `f`: each argument meets its parameter's need, or the shared
    * version's need where it is not exclusive at this call.
    */
  private def call(f: String, as: List[Atom], live: Set[String], ctx: Ctx): Map[String, Need] =
    val args = as.flatMap(Core.countedVar).map(_.name)
    as.zipWithIndex.collect { case (v: Atom.Var, i) =>
      val n = sig(f, i)
      Map(v.name -> (if !v.ctype.counted || exclusive(v.name, args, live, ctx) then n else n.shared))
    }.foldLeft(Map.empty[String, Need])(join)

  /** Every variable among `as`, scalars included (a used scalar is not erased), demanded `n`. */
  private def uses(as: List[Atom], n: Need): Map[String, Need] =
    as.collect { case v: Atom.Var => v.name -> n }.groupMapReduce(_._1)(_._2)(_ join _)
