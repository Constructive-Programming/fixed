package fixed.backend

import scala.collection.mutable

import fixed.backend.Atom.Var
import fixed.backend.Fusion.Kind
import fixed.core.{SymbolId, Type}
import fixed.util.Span

/** Guaranteed fusion (`spec/type_system.md` Rule 7.4.b.1) as a pass over the lowered Core, before
  * any analysis: a derived `unfold` whose result is consumed by one derived `fold`, or by the left
  * fold of a list, is replaced by a loop over the seed that builds no structure. Every later pass
  * sees an ordinary function.
  *
  * A site is fused when all of these hold:
  *   1. `let xs = D.unfold(seed, step)`, or `let xs = b(args)` for a builder `b` ([[builder]]),
  *      optionally followed by derived `map`s of linear data, each `let ys = xs.map(g)`, and the
  *      last of them consumed as the receiver of a derived `fold` (Rule 7.4.a) or a left fold of
  *      the same data type `D`;
  *   2. each of `xs`, `ys`, … occurs exactly once in the function (its receiver position);
  *   3. the step and every `map` function have an empty effect row: they are lambdas typed pure,
  *      or function values whose arrow type has no effect. The steps then run at the fold, between
  *      its callbacks, and stop where it stops; the fold's functions may have effects, which run in
  *      the unfused order. What a row does not show still moves: a step that panics (division by
  *      zero, not an effect today) or never ends now does so after the fold's earlier effects, or
  *      not at all if the fold aborts first;
  *   4. the shape is one of: a left fold of list-shaped data (a loop with the accumulator); a
  *      `fold` of linear data whose one recursive variant has no field but `Self` (`Nat`: a loop
  *      that counts, then a loop that applies the callback); a `fold` of other data whose
  *      recursive fields are all `Self` itself (a tree: a recursive function, one native frame per
  *      level, Rule 7.4.b.3). `map` stages are fused into a left fold only.
  *
  * A function argument that is a closure built in view (the site, or the builder) is inlined
  * (Rule 7.4.b.2): its captures become parameters of the loop, and a step that returns a
  * constructor hands its fields on with no layer cell. Another function value is called through
  * its closure, and its layer is a cell read and dropped each step.
  */
private[backend] trait Fusion:
  this: Lower =>

  /** The derived members built, by name: what each is, and of which data type. */
  private[backend] val derivedKinds = mutable.Map.empty[String, (Kind, SymbolId)]

  /** The code of each lambda whose effect row is empty. */
  private[backend] val pureCode = mutable.Set.empty[String]

  /** The fused loops each closure's code is inlined into. */
  private val inlinedInto = mutable.Map.empty[String, Set[String]].withDefaultValue(Set.empty)

  /** A function argument of a site: `arg` at the caller; with `code`, the closure's code, its
    * captured variables and its body after loading them, inlined; else called through the
    * parameter `params.head`. `params` are the loop's parameters it needs, with the caller's
    * argument for each.
    */
  final private case class Bound(arg: Atom, code: Option[(Fn, List[Var], Expr)], params: List[(Var, Atom)])

  /** The structure feeding a fold: seed and step of its `unfold`, the variable bound to the
    * unfold's result, the `map` functions after it with the variables their results are bound to,
    * the variables whose lets the rewrite removes, and the lets of an inlined builder.
    */
  final private case class Producer(
      seed: Atom,
      step: Atom,
      unfolded: Var,
      maps: List[(Atom, Var)],
      dropped: Set[String],
      prefix: List[(Var, Rhs)]
  )

  final private case class Site(
      fold: Var,
      kind: Kind,
      data: SymbolId,
      args: List[Type],
      seed: Atom,
      seedC: CType,
      step: Bound,
      stages: List[(Bound, CType)],
      consumer: List[Bound],
      init: Atom,
      dropped: Set[String],
      prefix: List[(Var, Rhs)]
  ):
    def rt: CType = fold.ctype
    def bounds: List[Bound] = step :: stages.map(_._1) ++ consumer

  /** A function's lets by variable, and how often each variable occurs. */
  final private case class View(defs: Map[String, Rhs], uses: Map[String, Int]):
    def once(v: Var): Boolean = uses.get(v.name).contains(1)

  /** Fuse every site, then move the clone requests (Rule CM5.4) of inlined code to the loops it is
    * inlined into, and forget those of the calls fusion removed: of code `main` no longer reaches,
    * and of a call no longer made.
    */
  private[backend] def fuse(): Unit =
    if fuseAll(found = false) then
      val live = Core.reach(Set("main"), fns)
      val moved = requests.toList.flatMap(r => (r.owner :: inlinedInto(r.owner).toList).map(o => r.copy(owner = o)))
      requests.clear()
      requests ++= moved.filter(r =>
        live(r.owner) && fns.get(r.owner).exists(f =>
          Core.callees(f.body).exists(g => g == r.of || typeClones.get(g).contains((r.of, r.key)))
        )
      )

  /** Fuse sites until none is left, in the loops built too (a fold inside a fold's function); whether any was. */
  @annotation.tailrec
  final private def fuseAll(found: Boolean): Boolean =
    fns.values.toList.iterator.map(f => f.name -> fused(f)).collectFirst { case (n, Some(g)) => n -> g } match
      case Some((n, g)) =>
        fns(n) = g
        fuseAll(found = true)
      case None => found

  /** `f` with its first fusible site fused, if it has one. */
  private def fused(f: Fn): Option[Fn] =
    val lets = Core.lets(f.body)
    val view = View(lets.map((x, r) => x.name -> r).toMap, counts(f.body))
    lets.iterator.flatMap((r, rhs) => site(r, rhs, view)).flatMap(rewrite(f, _)).nextOption()

  private def counts(e: Expr): Map[String, Int] =
    Core.operands(e).flatMap(Core.vars).groupMapReduce(_.name)(_ => 1)(_ + _)

  private def site(r: Var, rhs: Rhs, view: View): Option[Site] =
    rhs match
      case Rhs.Call(g, as, false) =>
        derivedKinds.get(g).filter((k, _) => k == Kind.Fold || k == Kind.FoldLeft).flatMap { (kind, data) =>
          val arity = if kind == Kind.FoldLeft then 3 else 1 + symbols.variantsOf(data).size
          as.drop(as.size - arity) match
            case (xs: Var) :: rest if view.once(xs) =>
              producer(xs, data, view).flatMap(p => assemble(r, kind, data, p, rest, view))
            case _ => None
        }
      case _ => None

  private def assemble(
      r: Var,
      kind: Kind,
      data: SymbolId,
      p: Producer,
      rest: List[Atom],
      view: View
  ): Option[Site] =
    val all = view.defs ++ p.prefix.map((x, rhs) => x.name -> rhs)
    val step = bind(p.step, all)
    val stages = p.maps.map((g, out) => (bind(g, all), types.get(out.name).flatMap(element)))
    val (init, fs) = if kind == Kind.FoldLeft then (rest.head, rest.tail) else (Atom.UnitC, rest)
    val consumer = fs.map(bind(_, all))
    val seedC = step.code.fold(p.seed.tpe)(_._1.params(1).ctype)
    val inlined = (step :: stages.map(_._1) ++ consumer).filter(_.code.isDefined).map(_.arg).collect {
      case v: Var => v.name
    }
    val ok = pure(step) && stages.forall((g, out) => pure(g) && out.isDefined) && shaped(kind, data, stages.nonEmpty) &&
      p.seed.tpe == seedC && (kind != Kind.FoldLeft || init.tpe == r.ctype)
    types.get(p.unfolded.name).flatMap(dataOf).filter(_ => ok).collect { case (`data`, args) =>
      Site(
        r,
        kind,
        data,
        args,
        p.seed,
        seedC,
        step,
        stages.map((g, out) => (g, out.getOrElse(CType.Unit))),
        consumer,
        init,
        p.dropped ++ inlined.filter(n => view.uses.get(n).contains(1)),
        p.prefix.filterNot((x, _) => inlined.contains(x.name))
      )
    }

  /** The Core type of the elements of a `map`'s result type. */
  private def element(t: Type): Option[CType] = dataOf(t).flatMap(_._2.lastOption).map(ctype(_, Span.NoSpan))

  /** Whether `kind` of `data` has a fused form (condition 4 of [[Fusion]]). */
  private def shaped(kind: Kind, data: SymbolId, stages: Boolean): Boolean =
    val self = declaredSelf(data)
    val fields = declaredFields(data)
    kind match
      case Kind.FoldLeft     => true
      case _ if stages       => false
      case _ if linear(data) => fields.filter(_.contains(self)) == List(List(self))
      case _                 => fields.flatten.forall(t => t == self || !mentions(data, t))

  private def producer(xs: Var, data: SymbolId, view: View): Option[Producer] =
    view.defs.get(xs.name).collect { case Rhs.Call(g, as, false) => (g, as) }.flatMap { (g, as) =>
      (derivedKinds.get(g), as.takeRight(2)) match
        case (Some((Kind.Unfold, `data`)), List(seed, step)) => Some(Producer(seed, step, xs, Nil, Set(xs.name), Nil))
        case (Some((Kind.Map, `data`)), List(ys: Var, f)) if view.once(ys) =>
          producer(ys, data, view).map(p => p.copy(maps = p.maps :+ (f, xs), dropped = p.dropped + xs.name))
        case (Some(_), _) => None
        case (None, _)    => builder(g, as, data).map(_.copy(dropped = Set(xs.name)))
    }

  /** A builder: a known, monomorphic, non-recursive function `g` whose body is lets of `Move`,
    * `Prim`, `Con`, `Field` or of a closure used once, then one derived `unfold` of `data` whose
    * result it returns. Its body, with `args` for its parameters, is inlined at the fold: the lets
    * before the fold (so they too run there), the `unfold` fused. Rule 7.4.b.1 speaks of the result
    * of an `unfold`; this inlining, which changes no meaning, is what brings `upto(n).fold(…)` under it.
    */
  private def builder(g: String, args: List[Atom], data: SymbolId): Option[Producer] =
    fns.get(g).filter(b =>
      b.params.size == args.size && !b.params.exists(hidden) && !Core.reach(Core.callees(b.body), fns).contains(g)
    ).flatMap { b =>
      val body = rename(b.body, b.params.zip(args).toMap)
      val uses = counts(body)
      def chain(e: Expr, lets: List[(Var, Rhs)]): Option[Producer] =
        e match
          case Expr.Let(t, Rhs.Call(u, as, false), Expr.Ret(y))
              if y == t && derivedKinds.get(u).contains((Kind.Unfold, data)) && as.sizeIs >= 2 =>
            Some(Producer(as(as.size - 2), as.last, t, Nil, Set.empty, lets.reverse))
          case Expr.Let(x, rhs @ (_: Rhs.Move | _: Rhs.Prim | _: Rhs.Con | _: Rhs.Field), next) =>
            chain(next, (x, rhs) :: lets)
          case Expr.Let(x, rhs: Rhs.Closure, next) if uses.get(x.name).contains(1) => chain(next, (x, rhs) :: lets)
          case _                                                                   => None
      chain(body, Nil)
    }

  private def hidden(v: Var): Boolean = v.ctype == CType.Witness || v.ctype == CType.Table

  /** `a` as a function argument of a site: inlined if it is a closure built in view whose code loads its captures first. */
  private def bind(a: Atom, defs: Map[String, Rhs]): Bound =
    val closure = a match
      case v: Var => defs.get(v.name).collect { case Rhs.Closure(c, cs, _) => (c, cs) }
      case _      => None
    closure.flatMap((c, cs) =>
      fns.get(c).flatMap(code =>
        strip(code, cs.size).map((vs, body) =>
          Bound(a, Some((code, vs, body)), vs.zip(cs).map((v, x) => (if hidden(v) then v else copyOf(v)) -> x))
        )
      )
    ).getOrElse(Bound(a, None, List(fresh("fn", CType.Boxed) -> a)))

  /** The variables a closure's code loads its `n` captures into, and the body after the loads. */
  private def strip(code: Fn, n: Int): Option[(List[Var], Expr)] =
    def go(e: Expr, left: Int, got: List[Var]): Option[(List[Var], Expr)] =
      e match
        case _ if left == 0 => Option.when(!Core.freeVars(e).contains(code.params.head))((got.reverse, e))
        case Expr.Let(c, Rhs.Field(env, _), b) if env == code.params.head => go(b, left - 1, c :: got)
        case _                                                            => None
    code.params.headOption.flatMap(_ => go(code.body, n, Nil))

  /** Condition 3 of [[Fusion]]. */
  private def pure(b: Bound): Boolean =
    (b.code, b.arg) match
      case (Some((code, _, _)), _) => pureCode(code.name)
      case (None, v: Var)          =>
        types.get(v.name).exists {
          case Type.ArrowType(_, _, fx) => fx.effects.isEmpty
          case _                        => false
        }
      case _ => false

  /** `f` with site `s` fused: the loop gets the witnesses and tables `f` has, and the captures. */
  private def rewrite(f: Fn, s: Site): Option[Fn] =
    def loaded(e: Expr): List[Var] =
      e match
        case Expr.Let(x, _: Rhs.Field, b) => x :: loaded(b)
        case _                            => Nil
    val witnesses = (f.params ++ loaded(f.body)).filter(hidden).map(v => v -> (v: Atom))
    val caps = (witnesses ++ s.bounds.flatMap(_.params)).distinctBy(_._1.name)
    loops(s, caps).map(call => f.copy(body = edit(f.body, s, call)))

  /** `e` without the lets of `s.dropped`, and with the fold bound to `call` after the builder's lets. */
  private def edit(e: Expr, s: Site, call: Rhs): Expr =
    e match
      case Expr.Let(x, _, b) if s.dropped(x.name) => edit(b, s, call)
      case Expr.Let(x, _, b) if x == s.fold       =>
        s.prefix.foldRight(Expr.Let(x, call, edit(b, s, call)): Expr)((l, acc) => Expr.Let(l._1, l._2, acc))
      case Expr.Let(x, rhs, b) => Expr.Let(x, rhs.mapBranches(edit(_, s, call)), edit(b, s, call))
      case _                   => e

  /** The fused functions of `s`, registered if their bodies need nothing beyond their parameters,
    * and the call of the first.
    */
  private def loops(s: Site, caps: List[(Var, Atom)]): Option[Rhs] =
    val seedOf = s.step.code.fold(s.seed)(_._1.params(1))
    val (name, seed, acc) = (s"fused.${ids.next()}", like("seed", s.seedC, seedOf), like("acc", s.rt, s.fold))
    def again(to: String, ps: List[(Var, Atom)], next: Map[Var, Atom]): Expr =
      result(Rhs.Call(to, ps.map((p, _) => next.getOrElse(p, p)), runtime = false), s.rt)
    val parts = s.kind match
      case Kind.FoldLeft =>
        val ps = (seed -> s.seed) :: (acc -> s.init) :: caps
        val body = layer(s, seed) { (tag, fs) =>
          val rec = recursiveField(s.data, tag)
          if rec < 0 then Expr.Ret(acc)
          else
            staged(s.stages, fs(1 - rec), s.rt) { y =>
              apply(s.consumer.head, List(acc, y), s.rt, s.rt)(value(_)(v =>
                again(name, ps, Map(seed -> fs(rec), acc -> v))
              ))
            }
        }
        List((name, ps, body))
      case _ if linear(s.data) =>
        val u64 = CType.Int(64, signed = false)
        val (n, m, up, one) = (fresh("n", u64), fresh("n", u64), s"$name.up", Atom.IntC(1, u64))
        val rec = symbols.variantsOf(s.data).toList.indices.find(recursiveField(s.data, _) >= 0).getOrElse(0)
        val (downs, ups) = ((seed -> s.seed) :: (n -> Atom.IntC(0, u64)) :: caps, (m -> m) :: (acc -> acc) :: caps)

        /** The callback of variant `tag` on `args`, then `up` with `count` layers left. */
        def upward(tag: Int, args: List[Atom], count: Atom) =
          apply(s.consumer(tag), args, s.rt, s.rt)(value(_)(v => again(up, ups, Map(m -> count, acc -> v))))
        val down = layer(s, seed) { (tag, fs) =>
          if tag == rec then
            bound(Rhs.Prim(PrimOp.Add, List(n, one)), u64)(k => again(name, downs, Map(seed -> fs.head, n -> k)))
          else upward(tag, fs, n)
        }
        val more = bound(Rhs.Prim(PrimOp.Sub, List(m, one)), u64)(upward(rec, List(acc), _))
        val finished = bound(Rhs.Prim(PrimOp.Eq, List(m, Atom.IntC(0, u64))), CType.Bool)(z =>
          result(Rhs.If(z, Expr.Ret(acc), more), s.rt)
        )
        List((name, downs, down), (up, ups, finished))
      case _ =>
        val ps = (seed -> s.seed) :: caps
        val self = declaredSelf(s.data)
        val body = layer(s, seed) { (tag, fs) =>
          val declared = declaredFields(s.data)(tag)
          def go(rest: List[(Atom, Type)], done: List[Atom]): Expr =
            rest match
              case Nil                 => apply(s.consumer(tag), done.reverse, s.rt, s.rt)(value(_)(Expr.Ret(_)))
              case (a, `self`) :: more =>
                bound(Rhs.Call(name, ps.map((p, _) => if p == seed then a else p), runtime = false), s.rt)(r =>
                  go(more, r :: done)
                )
              case (a, _) :: more => go(more, a :: done)
          go(fs.zip(declared), Nil)
        }
        List((name, ps, body))
    Option.when(parts.forall((_, ps, body) => (Core.freeVars(body) -- ps.map(_._1)).isEmpty)) {
      parts.foreach((n, ps, body) => fns(n) = Fn(n, ps.map(_._1), s.rt, body))
      val codes = s.bounds.flatMap(_.code).map(_._1.name)
      codes.foreach(c => inlinedInto(c) = inlinedInto(c) ++ parts.map(_._1))
      Rhs.Call(name, parts.head._2.map(_._2), runtime = false)
    }

  /** One layer of `s`'s step from `seed`, its variant and fields handed to `on`: the fields of the
    * constructor an inlined step returns, else those loaded from the layer it returns.
    */
  private def layer(s: Site, seed: Var)(on: (Int, List[Atom]) => Expr): Expr =
    apply(s.step, List(seed), CType.Boxed, s.rt) {
      case Expr.Let(_, Rhs.Con(tag, fs), _) => on(tag, fs)
      case t                                =>
        value(t) {
          case v: Var =>
            switch(
              v,
              perVariant(s.data, s.args, v, Span.NoSpan, Some(s.seedC))((tag, ls) => on(tag, ls.map(_._2))),
              s.rt
            )
          case _ => t
        }
    }

  /** The `map` stages applied to `x` in order, the last result handed to `k`. */
  private def staged(stages: List[(Bound, CType)], x: Atom, rt: CType)(k: Atom => Expr): Expr =
    stages match
      case Nil              => k(x)
      case (g, out) :: rest => apply(g, List(x), out, rt)(value(_)(staged(rest, _, rt)(k)))

  /** `b` applied to `args`, each result (of type `own`) handed to `k` as the expression that
    * returns it, at each tail of an inlined body; `rt` is the type of what `k` builds.
    */
  private def apply(b: Bound, args: List[Atom], own: CType, rt: CType)(k: Expr => Expr): Expr =
    b.code match
      case None =>
        val v = fresh("v", own)
        Expr.Let(v, Rhs.CallClosure(b.params.head._1, args), k(Expr.Ret(v)))
      case Some((code, captured, body)) =>
        val moved = code.params.tail.zip(args).map { (p, a) =>
          if p.ctype == a.tpe then (p, a, None) else { val q = copyOf(p); (p, q, Some(q -> a)) }
        }
        val sub = captured.zip(b.params.map(_._1)).toMap ++ moved.map((p, a, _) => p -> a)
        moved.flatMap(_._3).foldRight(tails(rename(body, sub), code.ret, rt)(k))((qa, e) =>
          Expr.Let(qa._1, Rhs.Move(qa._2), e)
        )

  /** `e` with `k` applied to each tail, a `Ret` or a constructor returned at once; a unit returned
    * where `own` is not unit is dead code after an abort and stays.
    */
  private def tails(e: Expr, own: CType, rt: CType)(k: Expr => Expr): Expr =
    e match
      case Expr.Ret(Atom.UnitC) if own != CType.Unit                             => e
      case Expr.Ret(_)                                                           => k(e)
      case Expr.Let(x, _: Rhs.Con, Expr.Ret(y)) if y == x                        => k(e)
      case Expr.Let(x, rhs @ (_: Rhs.If | _: Rhs.Switch), Expr.Ret(y)) if y == x =>
        val res = fresh("res", rt)
        Expr.Let(res, rhs.mapBranches(tails(_, own, rt)(k)), Expr.Ret(res))
      case Expr.Let(x, rhs, b) => Expr.Let(x, rhs, tails(b, own, rt)(k))
      case _                   => e

  /** `k` of the value the tail `t` returns. */
  private def value(t: Expr)(k: Atom => Expr): Expr =
    t match
      case Expr.Let(x, rhs, _) => Expr.Let(x, rhs, k(x))
      case Expr.Ret(a)         => k(a)
      case _                   => t

  /** `e` with `sub` applied and every variable it binds renamed, but witnesses and tables. */
  private def rename(e: Expr, sub: Map[Var, Atom]): Expr =
    def at(a: Atom): Atom =
      a match
        case v: Var               => sub.getOrElse(v, v)
        case Atom.DynTable(l, cs) => Atom.DynTable(l, cs.map(at))
        case _                    => a
    e match
      case Expr.Let(x, rhs, b) =>
        val y = if hidden(x) then x else copyOf(x)
        Expr.Let(y, rhs.mapAtoms(at).mapBranches(rename(_, sub)), rename(b, sub + (x -> y)))
      case Expr.Ret(a) => Expr.Ret(at(a))
      case _           => e

  /** A fresh variable like `x`, with its type and span. */
  /** A fresh variable of Core type `t` with the typer type of `from`, if it has one: what makes its
    * cell a candidate for reuse (`Demands`).
    */
  private def like(base: String, t: CType, from: Atom): Var =
    val y = fresh(base, t)
    Core.vars(from).headOption.flatMap(v => types.get(v.name)).foreach(types(y.name) = _)
    y

  private def copyOf(x: Var): Var =
    val y = fresh(x.name.takeWhile(_ != '.'), x.ctype)
    types.get(x.name).foreach(types(y.name) = _)
    spans.get(x.name).foreach(spans(y.name) = _)
    y

object Fusion:

  /** Whether `callee`, called from `caller`, is a fused loop other than `caller` itself. */
  def loop(callee: String, caller: String): Boolean = callee.startsWith("fused.") && callee != caller

  /** The derived members fusion recognises. */
  enum Kind:
    case Unfold, Map, FoldLeft, Fold
