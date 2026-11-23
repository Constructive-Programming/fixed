package fixed.backend

import fixed.ast.{Tree, Trees}
import fixed.core.SymbolId
import fixed.typer.Members
import fixed.util.DiagCode

/** Abortive effect operations and `handle` (slice §4.6): `fx_abort` records the effect and its
  * payload, and the `fx_aborting` tests that [[Aborts]] inserts after calls unwind to the handler.
  */
private[backend] trait Handlers:
  this: Lower =>

  private def effectId(effect: SymbolId): Atom = Atom.IntC(BigInt(effect.toInt), CType.Int(64, signed = true))

  /** An abortive effect operation (`-> !`): record the effect and its payload, then abort (`Aborts`).
    * The runtime keeps the payload in a pointer-sized field and never reads or drops it, so an
    * immediate or a slot travels there as its 64 bits, and the handler clause takes it back at its
    * type ([[handle]]).
    */
  private[backend] def operation(s: Trees.StaticCall, effect: SymbolId, args: List[Tree], sc: Scope)(k: K): Expr =
    if !Members.abortive(symbols, effect, s.method) then
      problems +=
        problem(DiagCode.B002, s.span, s"`${s.qualifier}.${s.method}`: only abortive operations are supported")
      k(Atom.UnitC)
    else
      all(args, sc) { as =>
        val (payload, u) = (as.headOption.getOrElse(Atom.StrC("")), fresh("abort", CType.Unit))
        def abort(p: Atom) = Expr.Let(u, Rhs.Call("fx_abort", List(effectId(effect), p), runtime = true), k(Atom.UnitC))
        if payload.tpe.boxed then abort(payload)
        else if payload.tpe.immediate || payload.tpe.slot then
          val bits = fresh("payload", CType.Boxed)
          Expr.Let(bits, Rhs.Move(payload), abort(bits))
        else unsupported(s.span, "an abortive operation with a scalar payload", k)
      }

  /** `handle subject: clauses` for abortive operations (slice §4.6). The subject runs as its own
    * function (a `Catch` call); each clause tests whether its effect aborted it.
    */
  private[backend] def handle(h: Trees.HandleExpr, sc: Scope)(k: K): Expr =
    val subjectType = typeOf(h.subject, sc)
    val code = s"handle.${ids.next()}"
    val body = owner.withValue(code)(tail(h.subject, sc))
    val captures = this.captures(body, Nil)
    fns(code) = Fn(code, captures, ctype(subjectType, h.span), body)
    val v = fresh("subject", ctype(subjectType, h.span))
    val resultType = ctype(typeOf(h, sc), h.span)
    val returned =
      h.returnClause.fold(Expr.Ret(v): Expr)(rc => bindPattern(rc.param, v, subjectType, sc)(tail(rc.body, _)))
    val normal = guarded("fx_aborting", Expr.Ret(Atom.UnitC), returned, resultType)
    val dispatch = h.operationClauses.foldRight(normal) { (c, otherwise) =>
      globals.get(c.effect) match
        case Some(effect) =>
          val caught = Rhs.Call(
            "fx_abort_catch",
            List(effectId(effect)),
            runtime = true
          )
          val payload = fresh("payload", CType.Boxed)

          /** The payload at its type: a cell as it is, else from its 64 bits ([[operation]]). */
          def bound(p: Tree) =
            val (pt, ct) = (typeOf(p, sc), ctype(typeOf(p, sc), p.span))
            if ct.boxed then bindPattern(p, payload, pt, sc)(tail(c.body, _))
            else
              val v = fresh("caught", ct)
              Expr.Let(v, Rhs.Move(payload), bindPattern(p, v, pt, sc)(tail(c.body, _)))
          val clause = Expr.Let(
            payload,
            Rhs.Call("fx_abort_payload", Nil, runtime = true),
            c.params.headOption.fold(tail(c.body, sc))(bound)
          )
          guardedBy(caught, clause, otherwise, resultType)
        case None => unsupportedThen(c.span, s"effect `${c.effect}`", otherwise)
    }
    Expr.Let(v, Rhs.Catch(code, captures), bind(Rhs.If(Atom.BoolC(true), dispatch, Expr.Ret(Atom.UnitC)), h, sc)(k))

  private def guarded(test: String, yes: Expr, no: Expr, rt: CType): Expr =
    guardedBy(Rhs.Call(test, Nil, runtime = true), yes, no, rt)

  private def guardedBy(test: Rhs, yes: Expr, no: Expr, rt: CType): Expr =
    val (flag, out) = (fresh("flag", CType.Bool), fresh("out", rt))
    Expr.Let(flag, test, Expr.Let(out, Rhs.If(flag, yes, no), Expr.Ret(out)))
