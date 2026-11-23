package fixed.backend

import fixed.backend.Expr.{Let, Ret}

/** Frame placement, the first increment of `spec/memory_management.md` §5.3: which cells the code
  * generator bumps on the data stack of a function activation instead of the heap, after
  * [[Consume]], before [[Aborts]] and [[Rc]].
  *
  * A constructor is placed on its function's frame (`Frame(f)`, Rule MM3.1) when [[Memory]] gives
  * it that class and every use of the value proves it again here, locally: of the value and of
  * every variable that holds it or a part of it (an alias, a field read, a branch's result), each
  * use is a field read, a `match`, a field of another cell placed on the same frame, or an argument
  * of a direct call whose parameter is not kept ([[kept]]); a self call in tail position is a jump
  * that resets the frame. Anything else keeps the value on the heap. A frame cell refers only to
  * frame cells of its activation or static cells, so a reset owes no `drop` (Rule MM4.2 without a
  * drop list), and a variable holding a frame cell holds one on every path, so `Rc` skips it.
  *
  * A function `g` whose results are all reference-free cells it builds (`Dest(result)`) gets a
  * clone `g#frame` that bumps them in its caller's frame, with no mark of its own; a call of `g`
  * whose result passes the checks calls it (Rule MM7.1, the destination as a clone, not a hidden
  * parameter). Baseline versions take part alike: frame cells reach slots, where the sentinel in
  * their count word makes the witness's `dup` and `drop` nothing (Rule MM7.3).
  *
  * @param fns the functions, with the clones and the calls that use them
  * @param frame per function, the variables that hold frame cells, which reference counting skips
  * @param bumped per function, the constructors allocated on the data stack
  * @param scoped the functions that mark the data stack on entry and reset it on exit
  * @param clones each `#frame` clone and the function it is a clone of
  */
final case class Frames(
    fns: List[Fn],
    frame: Map[String, Set[String]],
    bumped: Map[String, Set[String]],
    scoped: Set[String],
    clones: Map[String, String]
)

object Frames:
  val suffix = "#frame"

  /** Every cell on the heap (`--no-frames`). */
  def none(fns: List[Fn]): Frames = Frames(fns, Map.empty, Map.empty, Set.empty, Map.empty)

  /** How a use treats the value of a variable: `Into(x)`, variable `x` holds it or a part of it
    * (an alias, a counted field read, a branch's result); `In(x)`, a field of the new cell `x`;
    * `Arg`, argument `i` of a direct call of `f`, `jump` if a self call in tail position; `Read`,
    * a `match` or a read of a field that is not a reference; `Result`, the function's result;
    * `Out`, anything else.
    */
  private enum Use:
    case Into(x: String)
    case In(x: String)
    case Arg(f: String, i: Int, jump: Boolean)
    case Read, Result, Out

  /** A function's uses, the variables each variable flows into, its `let`s and its static cells. */
  final private case class Facts(fn: Fn, uses: List[(String, Use)], defs: Map[String, Rhs]):
    private val flows = uses.collect { case (v, Use.Into(x)) => (v, x) }
    val into: Map[String, List[String]] = flows.groupMap(_._1)(_._2)
    val sources: Map[String, List[String]] = flows.groupMap(_._2)(_._1)
    val statics: Set[String] = defs.collect {
      case (x, Rhs.Con(_, Nil))        => x
      case (x, Rhs.Closure(_, Nil, _)) => x
    }.toSet

    /** `roots` and every variable that holds one of them or a part of one. */
    def group(roots: Set[String]): Set[String] = closure(roots, into)

    /** Whether every variable of `group` holds only what `held` holds, or a static cell: a
      * variable that holds a frame cell on one path holds one on every path.
      */
    def pure(group: Set[String], held: Set[String]): Boolean =
      group.forall(z => sources.getOrElse(z, Nil).forall(s => held(s) || statics(s)))

    /** Whether every use of `group` keeps nothing: it is held by another variable (which is in the
      * group), read, `match`ed or lent to a call whose parameter `lent` says is not kept, or `more`
      * accepts it.
      */
    def harmless(group: Set[String], lent: (String, Int, Boolean) => Boolean)(more: Use => Boolean): Boolean =
      uses.forall { (v, u) =>
        !group(v) ||
        (u match
          case Use.Into(_) | Use.Read => true
          case Use.Arg(g, i, jump)    => lent(g, i, jump)
          case other                  => more(other))
      }

  def apply(fns: List[Fn], memory: MemoryTable): Frames =
    val facts = fns.map(f => f.name -> Facts(f, uses(f.body, None, tail = true, f.name), lets(f))).toMap
    val keeps = kept(facts)
    val notKept = (f: String, i: Int) => facts.contains(f) && !keeps((f, i))
    val dests = facts.values.flatMap(fs => results(fs, memory, notKept).map(fs.fn.name -> _)).toMap
    val placed = fns.map(f => f.name -> place(facts(f.name), memory, notKept, dests.keySet)).toMap
    val called = placed.values.flatMap(_._2.map(_._2)).toSet
    val clones = fns.filter(f => called(f.name)).map(g => g.copy(name = g.name + suffix))
    val renamed = fns.map(f => f.copy(body = rename(f.body, placed(f.name)._2.map(_._1))))
    Frames(
      renamed ++ clones,
      placed.map((f, p) => f -> facts(f).group(p._1)) ++
        clones.map(c => c.name -> facts(c.name.stripSuffix(suffix)).group(dests(c.name.stripSuffix(suffix)))),
      placed.map((f, p) => f -> (p._1 -- p._2.map(_._1))) ++
        clones.map(c => c.name -> dests(c.name.stripSuffix(suffix))),
      placed.collect { case (f, p) if p._1.nonEmpty => f }.toSet,
      clones.map(c => c.name -> c.name.stripSuffix(suffix)).toMap
    )

  /** `roots` and every variable `edges` reaches from them. */
  private def closure(roots: Set[String], edges: Map[String, List[String]]): Set[String] =
    val next = roots ++ roots.flatMap(edges.getOrElse(_, Nil))
    if next == roots then roots else closure(next, edges)

  private def lets(f: Fn): Map[String, Rhs] = Core.lets(f.body).map((x, r) => x.name -> r).toMap

  private def vars(as: List[Atom]): List[String] = as.flatMap(Core.countedVar).map(_.name)

  /** The uses in `e` of counted variables; `into` is the variable a `Ret` flows into, if a branch's,
    * `tail` whether `e`'s result is the function's.
    */
  private def uses(e: Expr, into: Option[String], tail: Boolean, self: String): List[(String, Use)] =
    e match
      case Ret(a)            => vars(List(a)).map(_ -> into.fold(Use.Result)(Use.Into(_)))
      case Expr.Dup(_, b)    => uses(b, into, tail, self)
      case Expr.Drop(_, b)   => uses(b, into, tail, self)
      case Let(x, rhs, body) =>
        val last = tail && body == Ret(x)
        def all(as: List[Atom], u: Use) = vars(as).map(_ -> u)
        def args(f: String, as: List[Atom], jump: Boolean) =
          as.zipWithIndex.flatMap((a, i) => vars(List(a)).map(_ -> Use.Arg(f, i, jump)))
        def branch(b: Expr) = uses(b, Some(x.name), last, self)
        val here = rhs match
          case Rhs.Move(a)            => all(List(a), Use.Into(x.name))
          case Rhs.Field(o, _)        => all(List(o), if x.ctype.counted then Use.Into(x.name) else Use.Read)
          case Rhs.Switch(s, cases)   => all(List(s), Use.Read) ++ cases.flatMap(c => branch(c._2))
          case Rhs.If(_, t, f)        => branch(t) ++ branch(f)
          case Rhs.Con(_, fs)         => all(fs, Use.In(x.name))
          case Rhs.Call(f, as, false) => args(f, as, last && f == self)
          case Rhs.Catch(f, as)       => args(f, as, jump = false)
          case other                  => all(Core.atoms(other), Use.Out)
        here ++ uses(body, into, tail, self)

  /** The parameters a function may keep beyond a call: those that reach anything but a read, a
    * `match` or another call's parameter that is not kept. The least such set, grown from none over
    * the call graph, so a parameter only passed round a recursion is not kept.
    */
  private def kept(facts: Map[String, Facts]): Set[(String, Int)] =
    def step(keeps: Set[(String, Int)]): Set[(String, Int)] =
      keeps ++ facts.values.flatMap { fs =>
        fs.fn.params.zipWithIndex.collect {
          case (p, i)
              if p.ctype.counted && !keeps((fs.fn.name, i)) &&
                !fs.harmless(fs.group(Set(p.name)), (g, j, _) => facts.contains(g) && !keeps((g, j)))(_ => false) =>
            (fs.fn.name, i)
        }
      }
    def fix(keeps: Set[(String, Int)]): Set[(String, Int)] =
      val next = step(keeps)
      if next == keeps then keeps else fix(next)
    fix(Set.empty)

  /** The cells of `fs`'s function that its `#frame` clone bumps: those it returns, if every value
    * it returns is one of them or a static cell. Each is `Dest(result)` in [[Memory]], holds no
    * reference, and is otherwise only read or lent to calls that do not keep it.
    */
  private def results(fs: Facts, memory: MemoryTable, notKept: (String, Int) => Boolean): Option[Set[String]] =
    val returned = closure(fs.uses.collect { case (v, Use.Result) => v }.toSet, fs.sources)
    val dest = (x: String) =>
      memory.allocations.getOrElse(fs.fn.name, Nil).exists(a => a.name == x && a.cls == LifetimeClass.Dest)
    val cells = returned.filter(x =>
      fs.defs.get(x).exists { case Rhs.Con(_, as) => as.nonEmpty; case _ => false }
    )
    val shapes = returned.forall(x =>
      fs.statics(x) || cells(x) || fs.defs.get(x).exists {
        case _: Rhs.Move | _: Rhs.If | _: Rhs.Switch => true
        case _                                       => false
      }
    )
    val fine = cells.forall(c =>
      dest(c) && vars(Core.atoms(fs.defs(c))).forall(fs.statics) &&
        fs.harmless(fs.group(Set(c)), (g, i, _) => notKept(g, i))(_ == Use.Result)
    )
    Option.when(cells.nonEmpty && shapes && fine && fs.pure(fs.group(cells), fs.group(cells)))(cells)

  /** The frame roots of `fs`'s function (its constructors and the results of `#frame` calls), and
    * among them the calls, by result variable, as a greatest fixed point.
    */
  private def place(
      fs: Facts,
      memory: MemoryTable,
      notKept: (String, Int) => Boolean,
      dests: Set[String]
  ): (Set[String], Set[(String, String)]) =
    val name = fs.fn.name
    val framed = memory.allocations.getOrElse(name, Nil).collect {
      case a if a.cls == LifetimeClass.Frame(name) && fs.defs.get(a.name).exists {
            case _: Rhs.Con => true; case _ => false
          } =>
        a.name
    }
    val calls = fs.defs.collect { case (x, Rhs.Call(g, _, false)) if dests(g) => x -> g }
    def ok(r: String, roots: Set[String], held: Set[String]): Boolean =
      val group = fs.group(Set(r))
      val fields = fs.defs.get(r).fold(Nil)(rhs => if calls.contains(r) then Nil else vars(Core.atoms(rhs)))
      fields.forall(v => held(v) || fs.statics(v)) &&
      fs.pure(group, held) &&
      fs.harmless(group, (g, i, jump) => !jump && notKept(g, i)) {
        case Use.In(x) => roots(x)
        case _         => false
      }
    def fix(roots: Set[String]): Set[String] =
      val held = fs.group(roots)
      val next = roots.filter(ok(_, roots, held))
      if next == roots then roots else fix(next)
    val roots = fix(framed.toSet ++ calls.keySet)
    (roots, calls.filter((x, _) => roots(x)).toSet)

  /** `e` with the call bound to each variable of `to` going to the `#frame` clone of its function. */
  private def rename(e: Expr, to: Set[String]): Expr =
    e match
      case Let(x, Rhs.Call(g, as, false), body) if to(x.name) =>
        Let(x, Rhs.Call(g + suffix, as, false), rename(body, to))
      case Let(x, rhs, body) => Let(x, rhs.mapBranches(rename(_, to)), rename(body, to))
      case Expr.Dup(x, b)    => Expr.Dup(x, rename(b, to))
      case Expr.Drop(x, b)   => Expr.Drop(x, rename(b, to))
      case Ret(_)            => e
