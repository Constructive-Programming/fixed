package fixed.backend

import fixed.backend.Atom.Var
import fixed.backend.Expr.{Drop, Dup, Let, Ret}

/** Naive reference-count insertion (`spec/memory_management.md` §10; slice §4.5). Every function
  * owns its boxed parameters and every boxed `Let` result. A consuming use of a variable that is
  * still live afterwards is preceded by a `Dup`; an owned variable that is dead is dropped at the
  * earliest point. No reuse, no borrowing.
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
      case Let(x, Rhs.If(c, t, f), body) =>
        val live = free(body)
        val handed = free(t) ++ free(f)
        val dead = owned -- live -- handed
        val branches = Rhs.If(c, go(t, handed), go(f, handed))
        val rest = Let(x, branches, go(body, (owned & live) ++ Core.boxedVar(x)))
        drops(dead, handed.filter(live).foldLeft(rest: Expr)((acc, v) => Dup(v, acc)))
      case Let(x, rhs, body) =>
        val live = free(body)
        val uses = consumed(rhs).groupMapReduce(identity)(_ => 1)(_ + _)
        val dead = owned -- live -- uses.keySet
        val rest: Expr = Let(x, rhs, go(body, (owned & live) ++ Core.boxedVar(x)))
        val dups = uses.toList.flatMap((v, n) => List.fill(n - 1 + (if live(v) then 1 else 0))(v))
        drops(dead, dups.foldLeft(rest)((acc, v) => Dup(v, acc)))
      case Dup(_, _) | Drop(_, _) => e

  private def drops(vs: Set[Var], e: Expr): Expr = vs.toList.sortBy(_.name).foldRight(e)(Drop(_, _))

  private def consumed(rhs: Rhs): List[Var] =
    rhs match
      case Rhs.Move(a)        => Core.boxedVar(a).toList
      case Rhs.Prim(_, as)    => as.flatMap(Core.boxedVar)
      case Rhs.Call(_, as, _) => as.flatMap(Core.boxedVar)
      case Rhs.If(c, t, f)    => Core.boxedVar(c).toList ++ free(t) ++ free(f)

  private def free(e: Expr): Set[Var] =
    e match
      case Let(x, rhs, body) => consumed(rhs).toSet ++ (free(body) - x)
      case Ret(a)            => Core.boxedVar(a).toSet
      case Dup(x, b)         => free(b) + x
      case Drop(x, b)        => free(b) + x
