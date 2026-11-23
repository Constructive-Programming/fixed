package fixed.backend

import fixed.backend.Expr.{Let, Ret}

/** Frame placement, the first increment of `spec/memory_management.md` §5.3: which cells the code
  * generator bumps on the data stack of a function activation instead of the heap, after
  * [[Consume]], before [[Aborts]] and [[Rc]].
  *
  * A constructor is placed on its function's frame (`Frame(f)`, Rule MM3.1) when [[Memory]] gives
  * it that class, which settles its lifetime; its uses must also show what a lifetime does not:
  * that only frame cells of the activation point to it (Rule MM4.1) and that no code reuses it (the
  * runtime reuses heap cells only). Of the value and of every variable that holds it or a part of
  * it (an alias, a field read, a branch's result), each use is a field read, a `match`, a field of
  * another cell placed on the same frame, or an argument of a direct call whose parameter is not
  * kept ([[kept]]). Anything else keeps the value on the heap. A frame cell refers only to frame
  * cells of its activation or static cells, so a reset owes no `drop` (Rule MM4.2 without a drop
  * list), and a variable holding a frame cell holds one on every path, so `Rc` skips it.
  *
  * A function `g` whose results are all reference-free cells it builds (`Dest(result)`) gets a
  * clone `g#frame` that bumps them in its caller's frame, with no mark of its own; a call of `g`
  * whose result [[Memory]] gives `Frame` of the caller and that passes the checks calls it (Rule
  * MM7.1, the destination as a clone, not a hidden parameter). Baseline versions take part alike:
  * frame cells with a count word reach slots, where its sentinel makes the witness's `dup` and
  * `drop` nothing (Rule MM7.3).
  *
  * '''Bare cells.''' A frame cell is allocated without its count word, as Rule MM3.1 has it, when
  * nothing can count it: every variable that may hold it, or a part of it, holds only bare cells,
  * and is only read, `match`ed, stored into another bare cell of the activation, or passed to a
  * parameter that takes a bare cell ([[bareParameters]]); a call that does so goes to a clone
  * `g#bare<indices>` compiled for it, whose reference counting skips every holder of those
  * parameters, slots of parameter type included. A cell with a reference field is bare only if
  * what it holds is, so a static cell (a full header) never meets a bare one in a variable. A
  * `#frame` clone's cells are bare if they hold no reference and every caller's result is.
  * Everything else keeps the count word, which holds the sentinel.
  *
  * @param fns the functions, with the clones and the calls that use them
  * @param frame per function, the variables that hold frame cells, which reference counting skips
  * @param bumped per function, the constructors allocated on the data stack
  * @param scoped the functions that mark the data stack on entry and reset it on exit
  * @param clones each `#frame` and `#bare` clone and the function it is a clone of
  * @param bare per function, the variables that hold bare cells, laid out without the count word
  */
final case class Frames(
    fns: List[Fn],
    frame: Map[String, Set[String]],
    bumped: Map[String, Set[String]],
    scoped: Set[String],
    clones: Map[String, String],
    bare: Map[String, Set[String]]
)

object Frames:
  val suffix = "#frame"

  /** Every cell on the heap (`--no-frames`). */
  def none(fns: List[Fn]): Frames = Frames(fns, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty)

  /** How a use treats the value of a variable: `Into(x)`, variable `x` holds it or a part of it
    * (an alias, a counted field read, a branch's result); `In(x)`, a field of the new cell `x`;
    * `Arg`, argument `i` of a direct call of `f`; `Read`, a `match` or a read of a field that is
    * not a reference; `Result`, the function's result; `Out`, anything else.
    */
  private enum Use:
    case Into(x: String)
    case In(x: String)
    case Arg(f: String, i: Int)
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

    /** Whether every variable of `group` holds only what `held` holds. */
    def only(group: Set[String], held: Set[String]): Boolean =
      group.forall(z => sources.getOrElse(z, Nil).forall(held))

    /** Whether every use of `group` keeps nothing: it is held by another variable (which is in the
      * group), read, `match`ed or lent to a call whose parameter `lent` says is not kept, or `more`
      * accepts it.
      */
    def harmless(group: Set[String], lent: (String, Int) => Boolean)(more: Use => Boolean): Boolean =
      uses.forall { (v, u) =>
        !group(v) ||
        (u match
          case Use.Into(_) | Use.Read => true
          case Use.Arg(g, i)          => lent(g, i)
          case other                  => more(other))
      }

  def apply(fns: List[Fn], memory: MemoryTable): Frames =
    val facts = fns.map(f => f.name -> Facts(f, uses(f.body, None), lets(f))).toMap
    val keeps = kept(facts)
    val notKept = (f: String, i: Int) => facts.contains(f) && !keeps((f, i))
    val dests = facts.values.flatMap(fs => results(fs, memory, notKept).map(fs.fn.name -> _)).toMap
    val placed = fns.map(f => f.name -> place(facts(f.name), memory, notKept, dests.keySet)).toMap
    val called = placed.values.flatMap(_._2.map(_._2)).toSet
    val clones = fns.filter(f => called(f.name)).map(g => g.copy(name = g.name + suffix))
    def frameOf(c: Fn) = facts(c.name.stripSuffix(suffix)).group(dests(c.name.stripSuffix(suffix)))
    val caught = fns.flatMap(f => Core.lets(f.body).collect { case (_, Rhs.Catch(g, _)) => g }).toSet
    val takes = bareParameters(facts, dests.keySet ++ caught ++ Core.indirectEntries(fns) + "main")
    val (bareRoots, bareDests) = bareCells(facts, placed, dests, takes)
    val bare = fns.map(f => f.name -> facts(f.name).group(bareRoots(f.name))).toMap ++
      clones.collect { case c if bareDests(c.name.stripSuffix(suffix)) => c.name -> frameOf(c) }
    val to = (f: String) => placed(f)._2.map(_._1)
    val renamed = fns.map(f => f.copy(body = rename(f.body, to(f.name), bare(f.name))))
    val bareOf = bareClones(fns.map(f => f.name -> bare(f.name)), facts, bare)
    val made = bareOf.map { case (c, (g, vs)) =>
      facts(g).fn.copy(name = c, body = rename(facts(g).fn.body, to(g), vs))
    }
    val frame = placed.map((f, p) => f -> facts(f).group(p._1)) ++ clones.map(c => c.name -> frameOf(c))
    val bumped = placed.map((f, p) => f -> (p._1 -- p._2.map(_._1))) ++
      clones.map(c => c.name -> dests(c.name.stripSuffix(suffix)))
    val scoped = placed.collect { case (f, p) if p._1.nonEmpty => f }.toSet
    Frames(
      renamed ++ clones ++ made,
      frame ++ bareOf.map { case (c, (g, vs)) => c -> (frame(g) ++ vs) },
      bumped ++ bareOf.map { case (c, (g, _)) => c -> bumped(g) },
      scoped ++ bareOf.collect { case (c, (g, _)) if scoped(g) => c },
      clones.map(c => c.name -> c.name.stripSuffix(suffix)).toMap ++ bareOf.map { case (c, (g, _)) => c -> g },
      bare ++ bareOf.map { case (c, (_, vs)) => c -> vs }
    )

  /** `f` applied to `a` until nothing changes. */
  private def fix[A](a: A)(f: A => A): A =
    val next = f(a)
    if next == a then a else fix(next)(f)

  /** `roots` and every variable `edges` reaches from them. */
  private def closure(roots: Set[String], edges: Map[String, List[String]]): Set[String] =
    val next = roots ++ roots.flatMap(edges.getOrElse(_, Nil))
    if next == roots then roots else closure(next, edges)

  private def lets(f: Fn): Map[String, Rhs] = Core.lets(f.body).map((x, r) => x.name -> r).toMap

  private def vars(as: List[Atom]): List[String] = as.flatMap(Core.countedVar).map(_.name)

  /** The uses in `e` of counted variables; `into` is the variable a `Ret` flows into, if a branch's. */
  private def uses(e: Expr, into: Option[String]): List[(String, Use)] =
    e match
      case Ret(a)            => vars(List(a)).map(_ -> into.fold(Use.Result)(Use.Into(_)))
      case Expr.Dup(_, b)    => uses(b, into)
      case Expr.Drop(_, b)   => uses(b, into)
      case Let(x, rhs, body) =>
        def all(as: List[Atom], u: Use) = vars(as).map(_ -> u)
        def args(f: String, as: List[Atom]) = as.zipWithIndex.flatMap((a, i) => vars(List(a)).map(_ -> Use.Arg(f, i)))
        def branch(b: Expr) = uses(b, Some(x.name))
        val here = rhs match
          case Rhs.Move(a)            => all(List(a), Use.Into(x.name))
          case Rhs.Field(o, _)        => all(List(o), if x.ctype.counted then Use.Into(x.name) else Use.Read)
          case Rhs.Switch(s, cases)   => all(List(s), Use.Read) ++ cases.flatMap(c => branch(c._2))
          case Rhs.If(_, t, f)        => branch(t) ++ branch(f)
          case Rhs.Con(_, fs)         => all(fs, Use.In(x.name))
          case Rhs.Call(f, as, false) => args(f, as)
          case Rhs.Catch(f, as)       => args(f, as)
          case other                  => all(Core.atoms(other), Use.Out)
        here ++ uses(body, into)

  /** The parameters a function may keep beyond a call: those that reach anything but a read, a
    * `match` or another call's parameter that is not kept. The least such set, grown from none over
    * the call graph, so a parameter only passed round a recursion is not kept.
    */
  private def kept(facts: Map[String, Facts]): Set[(String, Int)] =
    fix(Set.empty[(String, Int)]) { keeps =>
      keeps ++ facts.values.flatMap { fs =>
        fs.fn.params.zipWithIndex.collect {
          case (p, i)
              if p.ctype.counted && !keeps((fs.fn.name, i)) &&
                !fs.harmless(fs.group(Set(p.name)), (g, j) => facts.contains(g) && !keeps((g, j)))(_ => false) =>
            (fs.fn.name, i)
        }
      }
    }

  /** The parameters that may take a bare cell: those whose every holder (the parameter, its aliases,
    * the parts read from it) holds nothing else and is only read, `match`ed or passed to a parameter
    * that takes a bare cell too; the greatest such set. A function in `excluded` takes
    * none: one called through a closure, a table or a `handle`, one with a `#frame` clone, `main`.
    */
  private def bareParameters(facts: Map[String, Facts], excluded: Set[String]): Set[(String, Int)] =
    val all = facts.values.toList.filterNot(fs => excluded(fs.fn.name)).flatMap(fs =>
      fs.fn.params.indices.map(fs.fn.name -> _)
    )
    fix(all.toSet)(takes =>
      takes.filter { (f, i) =>
        val fs = facts(f)
        val group = fs.group(Set(fs.fn.params(i).name))
        fs.only(group, group) && fs.harmless(group, (g, j) => takes((g, j)))(_ => false)
      }
    )

  /** The frame roots of each function that are bare cells, and the functions whose `#frame` clone
    * bumps bare cells; the greatest such sets. A root holds only bare cells in its reference
    * fields; its holders hold only bare cells and are only read, `match`ed, stored into a bare root
    * or passed to a parameter in `takes`. A `#frame` clone's cells hold no reference, their holders
    * are only read, `match`ed or returned, and the result of every call of the clone is bare.
    */
  private def bareCells(
      facts: Map[String, Facts],
      placed: Map[String, (Set[String], Set[(String, String)])],
      dests: Map[String, Set[String]],
      takes: Set[(String, Int)]
  ): (Map[String, Set[String]], Set[String]) =
    fix((placed.map((f, p) => f -> p._1), dests.keySet)) { (roots, ds) =>
      val next = roots.map { (f, rs) =>
        val (fs, held, calls) = (facts(f), facts(f).group(rs), placed(f)._2.toMap)
        f -> rs.filter { r =>
          val group = fs.group(Set(r))
          calls.get(r).fold(vars(Core.atoms(fs.defs(r))).forall(held))(ds) && fs.only(group, held) &&
          fs.harmless(group, (g, i) => takes((g, i))) {
            case Use.In(x) => rs(x)
            case _         => false
          }
        }
      }
      val cells = ds.filter { g =>
        val fs = facts(g)
        val group = fs.group(dests(g))
        dests(g).forall(c => vars(Core.atoms(fs.defs(c))).isEmpty) && fs.only(group, group) &&
        fs.harmless(group, (_, _) => false)(_ == Use.Result) &&
        placed.forall((f, p) => p._2.forall((x, h) => h != g || next(f)(x)))
      }
      (next, cells)
    }

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
        fs.harmless(fs.group(Set(c)), notKept)(_ == Use.Result)
    )
    Option.when(cells.nonEmpty && shapes && fine && fs.only(fs.group(cells), fs.group(cells) ++ fs.statics))(cells)

  /** The frame roots of `fs`'s function (its constructors and the results of `#frame` calls that
    * [[Memory]] gives `Frame` of it), and among them the calls, by result variable, as a greatest
    * fixed point.
    */
  private def place(
      fs: Facts,
      memory: MemoryTable,
      notKept: (String, Int) => Boolean,
      dests: Set[String]
  ): (Set[String], Set[(String, String)]) =
    val framed = memory.framed.getOrElse(fs.fn.name, Set.empty)
    val cons = fs.defs.collect { case (x, Rhs.Con(_, as)) if as.nonEmpty && framed(x) => x }
    val calls = fs.defs.collect { case (x, Rhs.Call(g, _, false)) if dests(g) && framed(x) => x -> g }
    def ok(r: String, roots: Set[String], held: Set[String]): Boolean =
      val group = fs.group(Set(r))
      val fields = fs.defs.get(r).fold(Nil)(rhs => if calls.contains(r) then Nil else vars(Core.atoms(rhs)))
      fields.forall(v => held(v) || fs.statics(v)) &&
      fs.only(group, held ++ fs.statics) &&
      fs.harmless(group, notKept) {
        case Use.In(x) => roots(x)
        case _         => false
      }
    val roots = fix(cons.toSet ++ calls.keySet)(roots => roots.filter(ok(_, roots, fs.group(roots))))
    (roots, calls.filter((x, _) => roots(x)).toSet)

  /** `e` with the call bound to each variable of `to` going to the `#frame` clone of its function,
    * and each other call that passes a bare cell, a variable of `bare`, to the `#bare` clone for
    * those arguments.
    */
  private def rename(e: Expr, to: Set[String], bare: Set[String]): Expr =
    e match
      case Let(x, Rhs.Call(g, as, false), body) =>
        val callee = if to(x.name) then g + suffix else bareName(g, bareArgs(as, bare))
        Let(x, Rhs.Call(callee, as, false), rename(body, to, bare))
      case Let(x, rhs, body) => Let(x, rhs.mapBranches(rename(_, to, bare)), rename(body, to, bare))
      case Expr.Dup(x, b)    => Expr.Dup(x, rename(b, to, bare))
      case Expr.Drop(x, b)   => Expr.Drop(x, rename(b, to, bare))
      case Ret(_)            => e

  private def bareArgs(as: List[Atom], bare: Set[String]): List[Int] =
    as.zipWithIndex.collect { case (a, i) if vars(List(a)).exists(bare) => i }

  private def bareName(g: String, at: List[Int]): String = if at.isEmpty then g else s"$g#bare${at.mkString(",")}"

  /** The `#bare` clones that the calls of `fns` (each with its variables that hold bare cells), and
    * of the clones themselves, need: by name, the function each is a clone of and its variables that
    * hold bare cells, those of the function's own bare cells and of the parameters passed bare.
    */
  private def bareClones(
      fns: List[(String, Set[String])],
      facts: Map[String, Facts],
      bare: Map[String, Set[String]]
  ): Map[String, (String, Set[String])] =
    fix(Map.empty[String, (String, Set[String])]) { done =>
      (for
        (f, vs) <- fns ++ done.values
        case (_, Rhs.Call(g, as, false)) <- Core.lets(facts(f).fn.body)
        at = bareArgs(as, vs) if at.nonEmpty
      yield bareName(g, at) -> (g, bare(g) ++ facts(g).group(at.map(facts(g).fn.params(_).name).toSet))).toMap
    }
