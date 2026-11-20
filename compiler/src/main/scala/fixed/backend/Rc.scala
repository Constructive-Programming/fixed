package fixed.backend

import fixed.backend.Atom.Var
import fixed.backend.Expr.{Drop, Dup, Let, Ret}

/** Reference-count insertion (`spec/memory_management.md` §10). A function owns its counted
  * parameters (references and slots, [[CType.counted]]), except the borrowed ones (`read` demand,
  * Rule PR8.2), and every counted `Let` result; a slot's `Dup` and `Drop` go through its witness.
  * A consuming use of a variable that is still live afterwards is preceded by a `Dup`, and so is
  * any consuming use of a borrowed parameter, which this function does not own; an owned variable
  * that is dead is dropped at the earliest point. A `Field` read and an argument passed to a
  * borrowed parameter borrow: the variable is dropped only after the use, and a counted `Field`
  * result is dup'ed to own it. A `Reuse` does not use its cell: the cell dies at its last other
  * use, and its drop there becomes the reuse token.
  *
  * ponytail: free variables are recomputed per node (quadratic in function size); cache them per
  * node if large functions make this show up.
  *
  * @param borrowed the indices of each function's borrowed parameters
  */
object Rc:

  def apply(fn: Fn, borrowed: String => Set[Int] = _ => Set.empty): Fn =
    val lent = fn.params.zipWithIndex.collect { case (p, i) if p.ctype.counted && borrowed(fn.name)(i) => p }.toSet
    val counted = Walk(borrowed, lent).go(fn.body, fn.params.filter(_.ctype.counted).toSet -- lent)
    fn.copy(body = tokens(counted, Iterator.from(1)))

  /** Drop-guided reuse (Rule MM11.5): where a cell that a later `Reuse` names is dropped, take a
    * token from it instead (`fx_reuse_excl` if every such `Reuse` is unconditional, else Koka's
    * `fx_reuse`), and let the `Reuse` consume the token. Taking the token releases the cell's
    * children, so a field moved on to an exclusive call is exclusive there.
    */
  private def tokens(e: Expr, ids: Iterator[Int]): Expr =
    e match
      case Drop(v, rest) =>
        reuses(rest, v) match
          case Nil   => Drop(v, tokens(rest, ids))
          case flags =>
            val tok: Var = Var(s"${v.name}.tok${ids.next()}", CType.Boxed)
            val take = if flags.forall(identity) then "fx_reuse_excl" else "fx_reuse"
            Let(tok, Rhs.Call(take, List(v), runtime = true), tokens(rename(rest, v, tok), ids))
      case Dup(v, rest)      => Dup(v, tokens(rest, ids))
      case Let(x, rhs, body) => Let(x, rhs.mapBranches(tokens(_, ids)), tokens(body, ids))
      case Ret(_)            => e

  /** The `unconditional` flags of the `Reuse`s of cell `v` in `e`. */
  private def reuses(e: Expr, v: Var): List[Boolean] =
    Core.lets(e).collect { case (_, Rhs.Reuse(c, _, _, u)) if c == v => u }

  private def rename(e: Expr, v: Var, tok: Var): Expr =
    e match
      case Let(x, Rhs.Reuse(c, tag, fs, u), body) if c == v => Let(x, Rhs.Reuse(tok, tag, fs, u), rename(body, v, tok))
      case Let(x, rhs, body) => Let(x, rhs.mapBranches(rename(_, v, tok)), rename(body, v, tok))
      case Dup(x, b)         => Dup(x, rename(b, v, tok))
      case Drop(x, b)        => Drop(x, rename(b, v, tok))
      case Ret(_)            => e

  final private class Walk(borrowed: String => Set[Int], lent: Set[Var]):

    def go(e: Expr, owned: Set[Var]): Expr =
      e match
        case Ret(a) =>
          val out = drops(owned -- Core.countedVar(a), e)
          Core.countedVar(a).filter(lent).fold(out)(Dup(_, out))
        case Let(x, rhs @ (_: Rhs.If | _: Rhs.Switch), body) =>
          val live = free(body)
          val handed = consumed(rhs).toSet -- lent
          val dead = owned -- live -- handed
          val rest = Let(x, rhs.mapBranches(go(_, handed)), go(body, (owned & live) ++ Core.countedVar(x)))
          drops(dead, handed.filter(live).foldLeft(rest: Expr)((acc, v) => Dup(v, acc)))
        case Let(x, rhs, body) =>
          val live = free(body)
          val uses = consumed(rhs).groupMapReduce(identity)(_ => 1)(_ + _)
          val read = reads(rhs)
          val dead = owned -- live -- uses.keySet -- read
          val deadAfter = (owned & read) -- live -- uses.keySet
          val inner = drops(deadAfter, go(body, (owned & live) ++ Core.countedVar(x)))
          val owning = rhs match
            case _: Rhs.Field => Core.countedVar(x).fold(inner)(Dup(_, inner))
            case _            => inner
          val dups = uses.toList.flatMap((v, n) => List.fill(n - 1 + (if live(v) || lent(v) then 1 else 0))(v))
          drops(dead, dups.foldLeft(Let(x, rhs, owning): Expr)((acc, v) => Dup(v, acc)))
        case Dup(_, _) | Drop(_, _) => e

    private def drops(vs: Set[Var], e: Expr): Expr = vs.toList.sortBy(_.name).foldRight(e)(Drop(_, _))

    private def counted(as: List[Atom]) = as.flatMap(Core.countedVar)

    /** The counted arguments of a call of `f`, split into consumed and borrowed. */
    private def args(f: String, as: List[Atom]): (List[Var], List[Var]) =
      val (lentArgs, owned) = as.zipWithIndex.partition((_, i) => borrowed(f)(i))
      (counted(owned.map(_._1)), counted(lentArgs.map(_._1)))

    /** Counted variables `rhs` consumes, with multiplicity. A branch consumes what its branches use. */
    private def consumed(rhs: Rhs): List[Var] =
      rhs match
        case Rhs.Move(a)                => counted(List(a))
        case Rhs.Prim(_, as)            => counted(as)
        case Rhs.Call(f, as, false)     => args(f, as)._1
        case Rhs.Call(_, as, true)      => counted(as)
        case Rhs.Con(_, fs)             => counted(fs)
        case Rhs.Closure(_, cs, _)      => counted(cs)
        case Rhs.CallClosure(c, as, _)  => counted(c :: as)
        case Rhs.CallTable(_, _, as, _) => counted(as)
        case Rhs.Catch(f, as)           => args(f, as)._1
        case Rhs.Reuse(_, _, fs, _)     => counted(fs)
        case Rhs.Field(_, _)            => Nil
        case Rhs.If(c, t, f)            => (counted(List(c)).toSet ++ free(t) ++ free(f)).toList
        case Rhs.Switch(s, cases)       => (counted(List(s)).toSet ++ cases.flatMap((_, c) => free(c))).toList

    /** Counted variables `rhs` only borrows. */
    private def reads(rhs: Rhs): Set[Var] =
      rhs match
        case Rhs.Field(o, _)        => Core.countedVar(o).toSet
        case Rhs.Call(f, as, false) => args(f, as)._2.toSet
        case Rhs.Catch(f, as)       => args(f, as)._2.toSet
        case _                      => Set.empty

    private def free(e: Expr): Set[Var] =
      e match
        case Let(x, rhs, body) => consumed(rhs).toSet ++ reads(rhs) ++ (free(body) - x)
        case Ret(a)            => Core.countedVar(a).toSet
        case Dup(x, b)         => free(b) + x
        case Drop(x, b)        => free(b) + x
