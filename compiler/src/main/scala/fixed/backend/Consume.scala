package fixed.backend

/** The backend's use of M10's facts that is not reuse (`docs/plans/phase-3.4-qtt.md` §3.6):
  * demand-`0` parameters are erased from function and calls, and `read` parameters are borrowed
  * (`Rc` neither transfers nor drops them, Rule PR8.2) when some caller keeps the argument live
  * after the call. Where every call is the argument's last use, borrowing saves nothing and moves
  * the freeing into a second pass after the callee (measured: +18% on `bintrees`), so the
  * parameter stays owned and the callee frees as it reads. Both apply only to functions called
  * directly: a closure's code is called through `CallClosure`, whose convention is fixed, and
  * `main` is called by the runtime. Arguments are atoms, already evaluated by earlier `Let`s, so
  * erasing one never removes an effect (Rule Q6.1.3). A table entry is called only through its
  * table, so it is as indirect as a closure's code.
  */
object Consume:

  /** The rewritten functions and each function's borrowed parameter indices (after erasure). */
  def apply(a: Analysed): (List[Fn], Map[String, Set[Int]]) =
    val indirect = Core.indirectEntries(a.fns) + "main"
    val needs = (f: Fn) => if indirect(f.name) then Nil else a.demands.params.getOrElse(f.instance, Nil)
    val erased =
      a.fns.map(f =>
        f.name -> needs(f).zipWithIndex.collect { case (n, i) if n.root == Demand.Zero && !witness(f, i) => i }.toSet
      ).toMap
    val keptAfter = a.fns.flatMap(f => liveAfter(f.body, Set.empty)).toSet
    val borrowed = a.fns.map { f =>
      val kept = f.params.indices.filterNot(erased(f.name))
      f.name -> kept.zipWithIndex.collect {
        case (i, j) if !loops(f) && needs(f).lift(i).exists(_.root == Demand.Read) && keptAfter((f.name, i)) => j
      }.toSet
    }.toMap
    val drop =
      (f: String, as: List[Atom]) => as.zipWithIndex.filterNot((_, i) => erased.getOrElse(f, Set.empty)(i)).map(_._1)
    val fns = a.fns.map(f =>
      f.copy(params = drop(f.name, f.params).collect { case v: Atom.Var => v }, body = calls(f.body, drop))
    )
    (fns, borrowed)

  /** Whether `f` calls itself in tail position, which `EmitLLVM` emits as a jump. Such a function
    * borrows nothing: an argument it passes on to a borrowed parameter of its own would be
    * dropped after the call, which would then no longer be in tail position.
    */
  private def loops(f: Fn): Boolean =
    def in(e: Expr): Boolean =
      e match
        case Expr.Let(x, Rhs.Call(g, _, false), Expr.Ret(y)) if g == f.name && y == x => true
        case Expr.Let(x, rhs, body) => (body == Expr.Ret(x) && Core.branches(rhs).exists(in)) || in(body)
        case Expr.Dup(_, b)         => in(b)
        case Expr.Drop(_, b)        => in(b)
        case Expr.Ret(_)            => false
    in(f.body)

  /** A witness parameter is never erased: `Rc` adds `dup` and `drop` calls through it after demand inference. */
  private def witness(f: Fn, i: Int): Boolean = f.params(i).ctype == CType.Witness

  /** The (callee, parameter index) pairs whose argument stays live after some direct call. */
  private def liveAfter(e: Expr, after: Set[String]): List[(String, Int)] =
    e match
      case Expr.Let(x, rhs, body) =>
        val live = Core.freeVars(body).map(_.name) - x.name ++ after
        val here = rhs match
          case Rhs.Call(f, as, false) => kept(f, as, live)
          case Rhs.Catch(f, as)       => kept(f, as, live)
          case other                  => Core.branches(other).flatMap(liveAfter(_, live))
        here ++ liveAfter(body, after)
      case Expr.Dup(_, b)  => liveAfter(b, after)
      case Expr.Drop(_, b) => liveAfter(b, after)
      case Expr.Ret(_)     => Nil

  private def kept(f: String, as: List[Atom], live: Set[String]): List[(String, Int)] =
    as.zipWithIndex.flatMap((a, i) => Core.countedVar(a).filter(v => live(v.name)).map(_ => (f, i)))

  private def calls(e: Expr, drop: (String, List[Atom]) => List[Atom]): Expr =
    e match
      case Expr.Let(x, rhs, body) =>
        val rhs2 = rhs match
          case Rhs.Call(f, as, false) => Rhs.Call(f, drop(f, as), runtime = false)
          case Rhs.Catch(f, as)       => Rhs.Catch(f, drop(f, as))
          case other                  => other.mapBranches(calls(_, drop))
        Expr.Let(x, rhs2, calls(body, drop))
      case Expr.Dup(x, b)  => Expr.Dup(x, calls(b, drop))
      case Expr.Drop(x, b) => Expr.Drop(x, calls(b, drop))
      case Expr.Ret(_)     => e
