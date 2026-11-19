package fixed.backend

import fixed.backend.Atom.Var
import fixed.backend.Expr.{Drop, Dup, Let, Ret}

/** Naive reference-count insertion (`spec/memory_management.md` §10; slice §4.5). Every function
  * owns its boxed parameters and every boxed `Let` result. A consuming use of a variable that is
  * still live afterwards is preceded by a `Dup`; an owned variable that is dead is dropped at the
  * earliest point. A `Field` read borrows its object: the object is dropped only after the read,
  * and a boxed result is dup'ed to own it. No reuse, no borrowing of parameters.
  *
  * ponytail: free variables are recomputed per node (quadratic in function size); cache them per
  * node if large functions make this show up.
  */
object Rc:

  def apply(fn: Fn): Fn = fn.copy(body = go(fn.body, fn.params.filter(_.ctype.boxed).toSet))

  private def go(e: Expr, owned: Set[Var]): Expr =
    e match
      case Ret(a) =>
        drops(owned -- Core.boxedVar(a), e)
      case Let(x, rhs @ (_: Rhs.If | _: Rhs.Switch), body) =>
        val live = free(body)
        val handed = consumed(rhs).toSet
        val dead = owned -- live -- handed
        val rest = Let(x, rhs.mapBranches(go(_, handed)), go(body, (owned & live) ++ Core.boxedVar(x)))
        drops(dead, handed.filter(live).foldLeft(rest: Expr)((acc, v) => Dup(v, acc)))
      case Let(x, rhs, body) =>
        val live = free(body)
        val uses = consumed(rhs).groupMapReduce(identity)(_ => 1)(_ + _)
        val read = reads(rhs)
        val dead = owned -- live -- uses.keySet -- read
        val deadAfter = (owned & read) -- live -- uses.keySet
        val inner = drops(deadAfter, go(body, (owned & live) ++ Core.boxedVar(x)))
        val owning = rhs match
          case _: Rhs.Field => Core.boxedVar(x).fold(inner)(Dup(_, inner))
          case _            => inner
        val dups = uses.toList.flatMap((v, n) => List.fill(n - 1 + (if live(v) then 1 else 0))(v))
        drops(dead, dups.foldLeft(Let(x, rhs, owning): Expr)((acc, v) => Dup(v, acc)))
      case Dup(_, _) | Drop(_, _) => e

  private def drops(vs: Set[Var], e: Expr): Expr = vs.toList.sortBy(_.name).foldRight(e)(Drop(_, _))

  /** Boxed variables `rhs` consumes, with multiplicity. A branch consumes what its arms use. */
  private def consumed(rhs: Rhs): List[Var] =
    def boxed(as: List[Atom]) = as.flatMap(Core.boxedVar)
    rhs match
      case Rhs.Move(a)            => boxed(List(a))
      case Rhs.Prim(_, as)        => boxed(as)
      case Rhs.Call(_, as, _)     => boxed(as)
      case Rhs.Con(_, fs)         => boxed(fs)
      case Rhs.Closure(_, cs)     => boxed(cs)
      case Rhs.CallClosure(c, as) => boxed(c :: as)
      case Rhs.Catch(_, as)       => boxed(as)
      case Rhs.Field(_, _)        => Nil
      case Rhs.If(c, t, f)        => (boxed(List(c)).toSet ++ free(t) ++ free(f)).toList
      case Rhs.Switch(s, cases)   => (boxed(List(s)).toSet ++ cases.flatMap((_, c) => free(c))).toList

  /** Boxed variables `rhs` only borrows. */
  private def reads(rhs: Rhs): Set[Var] =
    rhs match
      case Rhs.Field(o, _) => Core.boxedVar(o).toSet
      case _               => Set.empty

  private def free(e: Expr): Set[Var] =
    e match
      case Let(x, rhs, body) => consumed(rhs).toSet ++ reads(rhs) ++ (free(body) - x)
      case Ret(a)            => Core.boxedVar(a).toSet
      case Dup(x, b)         => free(b) + x
      case Drop(x, b)        => free(b) + x
