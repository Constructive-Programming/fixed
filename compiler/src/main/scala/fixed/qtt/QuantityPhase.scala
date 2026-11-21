package fixed.qtt

import scala.collection.mutable

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.core.{Phase, SymKind, SymbolId}
import fixed.typer.{Members, Resolution, Stdlib}
import fixed.util.{DiagCode, Diagnostic, Severity}

/** Quantities of every binder and every function parameter (`docs/plans/phase-3.4-qtt.md`
  * 3.4.M1). Read by the backend by binder tree identity, as it reads types.
  */
final class QuantityTable(
    binders: java.util.IdentityHashMap[Tree, Quantity],
    signatures: java.util.IdentityHashMap[Trees.FnDecl, List[Quantity]],
    handlers: java.util.IdentityHashMap[Tree, HandlerKind]
):
  /** The kind of a `handle` install site (`spec/memory_management.md` Rule MM14.1). */
  def handler(h: Trees.HandleExpr): Option[HandlerKind] = Option(handlers.get(h))

  /** The quantity of a binder: a parameter, a pattern binder or a lambda parameter. */
  def of(binder: Tree): Option[Quantity] = Option(binders.get(binder))

  /** Per-parameter quantities of a function, top-level or nested. */
  def params(fn: Trees.FnDecl): Option[List[Quantity]] = Option(signatures.get(fn))

/** How a `handle` install treats its continuation (`spec/memory_management.md` Rule MM14.1). */
enum HandlerKind:
  case TailResumptive, Abortive, CapturingLocal, CapturingEscaping

object HandlerKind:
  private enum Shape:
    case NoResume, Tail, Other

  /** The kind of a handler whose resuming clauses have `bodies`; abortive clauses never resume.
    * `count` counts the `resume`s on one path, not those inside lambdas.
    */
  def of(bodies: List[Tree], count: Tree => Quantity): HandlerKind =
    def shape(t: Tree): Shape =
      t match
        case Trees.Resume(v, _)    => if v.exists(count(_) != Quantity.Zero) then Shape.Other else Shape.Tail
        case Trees.Block(stmts, _) =>
          if stmts.dropRight(1).exists(count(_) != Quantity.Zero) then Shape.Other
          else stmts.lastOption.fold(Shape.NoResume)(shape)
        case Trees.IfExpr(c, a, b, _)  => if count(c) != Quantity.Zero then Shape.Other else combine(shape(a), shape(b))
        case Trees.MatchExpr(s, cs, _) =>
          if count(s) != Quantity.Zero || cs.exists(_.guard.exists(count(_) != Quantity.Zero)) then Shape.Other
          else cs.map(c => shape(c.body)).foldLeft(Shape.NoResume)(combine)
        case other => if count(other) == Quantity.Zero then Shape.NoResume else Shape.Other
    def combine(a: Shape, b: Shape): Shape =
      if a == Shape.Other || b == Shape.Other then Shape.Other
      else if a == Shape.Tail || b == Shape.Tail then Shape.Tail
      else Shape.NoResume
    def captured(t: Tree): Boolean = Trees.subtrees(t).exists {
      case l: Trees.LambdaExpr => Trees.subtrees(l.body).exists { case _: Trees.Resume => true; case _ => false }
      case _                   => false
    }
    val shapes = bodies.map(shape)
    if bodies.exists(captured) then CapturingEscaping
    else if shapes.contains(Shape.Other) then CapturingLocal
    else if shapes.contains(Shape.Tail) then TailResumptive
    else Abortive

/** Runs after the typer, on the trees the backend lowers: the unit's functions and the prelude's,
  * each in its own scope.
  */
object QuantityPhase extends Phase:
  val name: String = "quantities"

  def run(unit: CompilationUnit): CompilationUnit =
    if unit.typing.isEmpty then unit
    else
      val walk = Quantities(unit)
      val diags = walk.run()
      unit.copy(quantities = Some(walk.table)).report(diags)

/** One walk per round; rounds repeat until no parameter quantity changes (recursion, Rule Q5.2).
  * Every quantity only grows, and the lattice has height 3, so this terminates.
  */
final private class Quantities(unit: CompilationUnit):
  import Quantity.*

  private enum Bound:
    case Local(binder: Tree)
    case LocalFn(decl: Trees.FnDecl)

  private type Env = Map[String, Bound]

  private val symbols = unit.symbols
  private val binders = java.util.IdentityHashMap[Tree, Quantity]()
  private val ids = java.util.IdentityHashMap[Tree, Integer]()
  private val signatures = java.util.IdentityHashMap[Trees.FnDecl, List[Quantity]]()
  private val diags = mutable.ArrayBuffer.empty[Diagnostic]

  /** Whether a prelude function is being walked: it reports nothing, and resolves names in the prelude's scope. */
  private val inPrelude = scala.util.DynamicVariable(false)

  /** Walk state per function this round: absent, in progress (`false`) or done (`true`). */
  private val walked = java.util.IdentityHashMap[Trees.FnDecl, java.lang.Boolean]()

  /** Signatures read while their function was still being walked (recursion), as read. */
  private val early = mutable.ArrayBuffer.empty[(Trees.FnDecl, List[Quantity])]

  private val tops: Map[SymbolId, Trees.FnDecl] = Stdlib.functions(unit.tree, symbols)

  /** The top-level scope of the function being walked: the prelude's for a prelude function. */
  private def scope: Map[String, SymbolId] = symbols.scopeFor(inPrelude.value)

  def table: QuantityTable = QuantityTable(binders, signatures, handlers)

  private val handlers = java.util.IdentityHashMap[Tree, HandlerKind]()

  /** Walk every function, callees first. A recursive call reads the signature as it stands; if
    * that read turns out wrong, walk again (the reads then see the previous round's result).
    */
  def run(): List[Diagnostic] =
    diags.clear()
    early.clear()
    walked.clear()
    tops.values.foreach(top)
    if early.exists((d, read) => signatures.get(d) != read) then run() else diags.toList

  private def top(d: Trees.FnDecl): Unit =
    if !walked.containsKey(d) then inPrelude.withValue(Stdlib.prelude.exists(_ eq d))(fn(d, Map.empty)): Unit

  /** The parameter quantities of `d`, walking it first if no one has yet. */
  private def signature(d: Trees.FnDecl, isTop: Boolean): List[Quantity] =
    if isTop then top(d)
    val now = Option(signatures.get(d)).getOrElse(d.params.map(_ => Zero))
    if !walked.get(d) then early += d -> now
    now

  /** Record `fn`'s parameter quantities; return what its body uses of enclosing binders. A
    * self-recursive function that read its own signature wrongly is walked again on the spot.
    */
  private def fn(decl: Trees.FnDecl, env: Env): Usage =
    val _ = walked.put(decl, false)
    val (reads, reported) = (early.size, diags.size)
    val body = decl.body.fold(Usage.none)(expr(_, env ++ locals(decl.params)))
    val qs = decl.params.map(p => body(id(p)))
    val _ = signatures.put(decl, qs)
    val own = early.drop(reads).filter(_._1 eq decl)
    early --= own
    if own.exists(_._2 != qs) then
      diags.dropRightInPlace(diags.size - reported)
      fn(decl, env)
    else
      val _ = walked.put(decl, true)
      record(decl.params, body)

  private def id(b: Tree): Int =
    Option(ids.get(b)).fold {
      val n = ids.size
      val _ = ids.put(b, n)
      n
    }(_.intValue)

  private def locals(bs: List[Tree]): Env = bs.flatMap(b => name(b).map(_ -> Bound.Local(b))).toMap

  private def name(b: Tree): Option[String] = b match
    case p: Trees.FnParam     => Some(p.name)
    case p: Trees.LambdaParam => Some(p.name)
    case p: Trees.BinderPat   => Some(p.name)
    case _                    => None

  /** Store the quantity of each binder in `bs` and remove them from `u`. */
  private def record(bs: List[Tree], u: Usage): Usage =
    bs.foreach(b =>
      val q = u(id(b))
      val _ = binders.put(b, q)
      if q == Zero then unused(b)
    )
    u.without(bs.map(id))

  /** `T021` for a binder used nowhere, unless its name starts with `_` (Rule Q5.1). */
  private def unused(b: Tree): Unit =
    name(b).filterNot(n => n.startsWith("_") || inPrelude.value).foreach(n =>
      diags += Diagnostic(Severity.Warning, DiagCode.T021, b.span, s"`$n` is never used; name it `_$n` if intended")
    )

  /** What `t` uses of the binders in `env`. Prop bodies use nothing at runtime (Rule Q5.5). */
  private def expr(t: Tree, env: Env): Usage =
    t match
      case Trees.Ident(n, _) =>
        env.get(n) match
          case Some(Bound.Local(b)) => Usage.one(id(b))
          case _                    => Usage.none
      case Trees.BinOp(_, l, r, _) => expr(l, env) + expr(r, env)
      case Trees.UnaryOp(_, a, _)  => expr(a, env)
      case _: (Trees.IntLit | Trees.FloatLit | Trees.StringLit | Trees.CharLit | Trees.BoolLit | Trees.UnitLit) =>
        Usage.none
      case Trees.Block(stmts, _)    => block(stmts, env)
      case Trees.IfExpr(c, a, b, _) => expr(c, env) + (expr(a, env) join expr(b, env))
      case m: Trees.MatchExpr       => expr(m.scrutinee, env) + Usage.joinAll(m.cases.map(matchCase(_, env)))
      case h: Trees.HandleExpr      => handle(h, env)
      case l: Trees.LambdaExpr      => lambda(l, env).scale(Many)
      case Trees.Apply(f, args, _)  => application(f, args, env)
      case Trees.Pipe(l, Trees.Apply(f, as, _), _) => application(f, l :: as, env)
      case Trees.Pipe(l, f, _)                     => application(f, List(l), env)
      case m: Trees.MethodCall                     => methodCall(m, env)
      case s: Trees.StaticCall                     => staticCall(s, env)
      case Trees.DoExpr(stmts, _)                  => doBlock(stmts, env)
      case _: Trees.ForallExpr | _: Trees.Implies  => Usage.none
      case other                                   => Usage.sum(children(other).map(expr(_, env)))

  /** A block, from its end: a `let` binder's quantity is its usage in the rest (Rule Q5.1). A
    * `let`-bound lambda's captures scale by the binder's quantity (Rule Q5.7).
    */
  private def block(stmts: List[Tree], env: Env): Usage =
    stmts match
      case Nil                        => Usage.none
      case (l: Trees.LetExpr) :: rest =>
        val bs = binderPats(l.pattern)
        val after = block(rest, env ++ locals(bs))
        val init = (l.init, bs) match
          case (lam: Trees.LambdaExpr, List(b)) => lambda(lam, env).scale(after(id(b)))
          case (e, _)                           => expr(e, env)
        init + record(bs, after)
      case (f: Trees.FnDecl) :: rest =>
        val inner = env + (f.name -> Bound.LocalFn(f))
        fn(f, inner).scale(Many) + block(rest, inner)
      case e :: rest => expr(e, env) + block(rest, env)

  /** `do` binds run the rest once per value the monad yields, so the rest scales by ω. */
  private def doBlock(stmts: List[Tree], env: Env): Usage =
    stmts match
      case Trees.DoBind(p, e, _) :: rest =>
        val bs = binderPats(p)
        expr(e, env) + record(bs, doBlock(rest, env ++ locals(bs))).scale(Many)
      case other => block(other, env)

  /** A lambda's use of enclosing binders, once per call. */
  private def lambda(l: Trees.LambdaExpr, env: Env): Usage =
    record(l.params, expr(l.body, env ++ locals(l.params)))

  private def matchCase(c: Trees.MatchCase, env: Env): Usage =
    val bs = binderPats(c.pattern)
    val inner = env ++ locals(bs)
    record(bs, c.guard.fold(Usage.none)(expr(_, inner)) + guardOf(c.pattern, inner) + expr(c.body, inner))

  private def guardOf(p: Tree, env: Env): Usage = p match
    case Trees.GuardedPat(_, cond, _) => expr(cond, env)
    case _                            => Usage.none

  /** Clauses that resume may run once per operation performed, so they scale by ω (Rules Q5.7,
    * Q7.4). Abortive clauses and the return clause are exclusive: at most one of them runs.
    */
  private def handle(h: Trees.HandleExpr, env: Env): Usage =
    val (abortive, resuming) = h.operationClauses.partition(abortiveOp)
    val _ = handlers.put(h, HandlerKind.of(resuming.map(_.body), resumes))
    val ret = h.returnClause.toList.map(r => clause(List(r.param), r.body, env))
    expr(h.subject, env) +
      Usage.joinAll(resuming.map(c => clause(c.params, c.body, env))).scale(Many) +
      Usage.joinAll(abortive.map(c => clause(c.params, c.body, env)) ++ ret)

  private def clause(params: List[Tree], body: Tree, env: Env): Usage =
    val bs = params.flatMap(binderPats)
    if resumes(body) == Many && !inPrelude.value then
      diags += Diagnostic(Severity.Error, DiagCode.E072, body.span, "`resume` may run twice on one path (Rule Q5.11)")
    record(bs, expr(body, env ++ locals(bs)))

  private def abortiveOp(c: Trees.OperationClause): Boolean =
    scope.get(c.effect).exists(Members.abortive(symbols, _, c.op))

  /** How often `resume` runs on one path through `t`. A lambda's `resume` is a continuation
    * capture, not a path (M2 records it as an escape); a nested `handle` has its own clauses.
    */
  private def resumes(t: Tree): Quantity =
    t match
      case Trees.Resume(v, _)        => One + v.fold(Zero)(resumes)
      case _: Trees.LambdaExpr       => Zero
      case h: Trees.HandleExpr       => resumes(h.subject)
      case Trees.IfExpr(c, a, b, _)  => resumes(c) + (resumes(a) join resumes(b))
      case Trees.MatchExpr(s, cs, _) =>
        resumes(s) + cs.map(c => c.guard.fold(Zero)(resumes) + resumes(c.body)).reduceOption(_ join _).getOrElse(Zero)
      case other => children(other).map(resumes).foldLeft(Zero)(_ + _)

  /** A call: a known function scales each argument by its parameter's quantity (§9); anything
    * else (a closure, a function without a body) may use its arguments any number of times.
    */
  private def application(f: Tree, args: List[Tree], env: Env): Usage =
    val callee = f match
      case Trees.Ident(n, _) =>
        env.get(n) match
          case Some(Bound.LocalFn(d)) => Some(signature(d, isTop = false))
          case None                   => scope.get(n).flatMap(tops.get).map(signature(_, isTop = true))
          case _                      => None
      case _ => None
    callee match
      case Some(qs) =>
        Usage.sum(args.zipAll(qs, Trees.UnitLit(f.span), Many).map((a, q) => arg(a, q, env)))
      case None => expr(f, env) + Usage.sum(args.map(arg(_, Many, env)))

  /** An argument passed at quantity `q` (§9). Evaluation is strict: a composite argument is
    * computed once, so only a binder passed as itself, or captured by a lambda argument, is
    * scaled. Quantity 0 erases the argument whatever its form.
    */
  private def arg(a: Tree, q: Quantity, env: Env): Usage =
    a match
      case _ if q == Zero        => Usage.none
      case _: Trees.Ident        => expr(a, env).scale(q)
      case lam: Trees.LambdaExpr => lambda(lam, env).scale(q)
      case _                     => expr(a, env)

  /** A field read and a derived `fold` use their receiver once; other members are abstract here
    * and count as ω (Rule Q5.12). Arguments of any member count as ω.
    */
  private def methodCall(m: Trees.MethodCall, env: Env): Usage =
    val once = unit.resolutionOf(m).exists {
      case _: Resolution.Field | _: Resolution.DerivedFold => true
      case _                                               => false
    }
    arg(m.receiver, if once then One else Many, env) + Usage.sum(m.args.toList.flatten.map(arg(_, Many, env)))

  /** A constructor stores each argument once; a derived `unfold` takes its seed once. Effect
    * operations and other statics are abstract: ω.
    */
  private def staticCall(s: Trees.StaticCall, env: Env): Usage =
    val raw = s.args.getOrElse(Nil)
    val owner = scope.get(s.qualifier)
    val isData = owner.exists(symbols(_).kind == SymKind.Data)
    val construct =
      owner.exists(o => symbols.membersOf(o).exists(v => v.kind == SymKind.Variant && v.name == s.method)) ||
        unit.typing.exists(_.constructors.exists(_._1 eq s))
    val once = if construct then raw.size else if isData && s.method == "unfold" then 1 else 0
    Usage.sum(raw.zipWithIndex.map((a, i) => arg(a, if i < once then One else Many, env)))

  /** The binders a pattern introduces. Both sides of an or-pattern bind the same names; the
    * left side's binders stand for them.
    */
  private def binderPats(p: Tree): List[Tree] =
    p match
      case b: Trees.BinderPat                      => List(b)
      case Trees.TuplePat(es, _)                   => es.flatMap(binderPats)
      case Trees.DataVariantPat(_, _, fs, _)       => fs.toList.flatten.flatMap(binderPats)
      case Trees.StructDestructurePat(_, fs, _, _) => fs.flatMap(f => binderPats(f._2))
      case Trees.OrPat(l, _, _)                    => binderPats(l)
      case Trees.GuardedPat(q, _, _)               => binderPats(q)
      case _                                       => Nil

  /** Direct subtrees, for the forms whose usage is the sum of their parts. */
  private def children(t: Tree): List[Tree] = Trees.children(t)
