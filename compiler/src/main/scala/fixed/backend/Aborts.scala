package fixed.backend

import fixed.backend.Expr.Let

/** Checked abort for abortive effect operations (slice §4.6). `fx_abort` records the effect and
  * its payload; after every call that may reach it, the caller tests `fx_aborting` and returns a
  * zero of its result type, so drops still run on the way out. A `Catch` call (a `handle`
  * subject) is not tested: the handler inspects the flag itself.
  *
  * ponytail: tests after every user call, closure call and branch once the program aborts anywhere;
  * effect rows could limit the tests to calls whose row holds an abortive effect.
  */
object Aborts:

  def apply(fns: List[Fn]): List[Fn] =
    if !fns.exists(f => aborts(f.body)) then fns
    else
      val ids = Iterator.from(1)
      fns.map(f => f.copy(body = go(f.body, f.ret, ids)))

  private def go(e: Expr, rt: CType, ids: Iterator[Int]): Expr =
    e match
      case Let(x, rhs, body) =>
        val inner = rhs.mapBranches(go(_, x.ctype, ids))
        val rest = go(body, rt, ids)
        Let(x, inner, if mayAbort(rhs) && !passesThrough(rhs, x, body) then check(rt, rest, ids.next()) else rest)
      case Expr.Dup(x, b)  => Expr.Dup(x, go(b, rt, ids))
      case Expr.Drop(x, b) => Expr.Drop(x, go(b, rt, ids))
      case Expr.Ret(_)     => e

  private def check(rt: CType, rest: Expr, n: Int): Expr =
    val flag: Atom.Var = Atom.Var(s"aborting.$n", CType.Bool)
    val out: Atom.Var = Atom.Var(s"exit.$n", rt)
    Let(
      flag,
      Rhs.Call("fx_aborting", Nil, runtime = true),
      Let(out, Rhs.If(flag, Expr.Ret(Atom.UnitC), rest), Expr.Ret(out))
    )

  /** Whether `x = rhs` is returned as it is and needs no test: on abort a user function, a closure,
    * a table entry or a branch already returns a zero, which is then returned unchanged; a runtime
    * call (`fx_abort`) keeps its test. Without the test a call stays in tail position, where
    * `EmitLLVM` turns a self call into a jump.
    */
  private def passesThrough(rhs: Rhs, x: Atom.Var, body: Expr): Boolean =
    body == Expr.Ret(x) &&
      (rhs match
        case Rhs.Call(_, _, runtime) => !runtime
        case _                       => true)

  private def mayAbort(rhs: Rhs): Boolean =
    rhs match
      case Rhs.Call(f, _, runtime)                                           => !runtime || f == "fx_abort"
      case _: Rhs.CallClosure | _: Rhs.CallTable | _: Rhs.If | _: Rhs.Switch => true
      case _                                                                 => false

  private def aborts(e: Expr): Boolean =
    e match
      case Let(_, rhs, body) =>
        val here = rhs match
          case Rhs.Call("fx_abort", _, true) => true
          case Rhs.If(_, t, f)               => aborts(t) || aborts(f)
          case Rhs.Switch(_, cases)          => cases.exists((_, c) => aborts(c))
          case _                             => false
        here || aborts(body)
      case Expr.Dup(_, b)  => aborts(b)
      case Expr.Drop(_, b) => aborts(b)
      case Expr.Ret(_)     => false
