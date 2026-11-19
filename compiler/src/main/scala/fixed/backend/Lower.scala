package fixed.backend

import scala.collection.mutable

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.backend.Atom.Var
import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.typer.{Members, Resolution}
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Typed tree to Core (slice §4.1–§4.4), in continuation-passing style: `expr(t, sc)(k)` lowers `t`
  * and hands its atom to `k`, which builds the rest of the function. Starts at `main` and lowers
  * each function instance it reaches, monomorphised: `sc.sigma` maps the instance's type
  * parameters to concrete types. Derived members (`fold`, `unfold`, `Folding.fold`) are generated
  * as Core functions. Constructs beyond the current milestone are `B005`.
  */
final class Lower(unit: CompilationUnit):
  private type Env = Map[String, Var]
  private type Sigma = Map[SymbolId, Type]
  private type K = Atom => Expr

  /** Lexical bindings and the type substitution of the instance being lowered. */
  final private case class Scope(env: Env, sigma: Sigma):
    def bind(name: String, v: Var): Scope = copy(env = env.updated(name, v))

  private val ids = Iterator.from(1)
  private val problems = mutable.ArrayBuffer.empty[Diagnostic]
  private val symbols = unit.symbols
  private val fns = mutable.LinkedHashMap.empty[String, Fn]

  private val decls: Map[String, Trees.FnDecl] =
    unit.tree.toList
      .flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
      .collect { case f: Trees.FnDecl => f.name -> f }
      .toMap

  /** Every function instance reachable from `main`, or the problems found. */
  def program: Either[List[Diagnostic], List[Fn]] =
    if !decls.contains("main") then Left(List(problem(DiagCode.B003, Span.NoSpan, "no `main` function")))
    else
      val _ = instance("main")(function(decls("main"), Map.empty, "main"))
      if problems.isEmpty then Right(fns.values.toList) else Left(problems.toList)

  /** Build function `name` once. A recursive request meets the placeholder entered first. */
  private def instance(name: String)(build: => Fn): String =
    if !fns.contains(name) then
      fns(name) = Fn(name, Nil, CType.Unit, Expr.Ret(Atom.UnitC))
      fns(name) = build
    name

  private def function(decl: Trees.FnDecl, sigma: Sigma, name: String): Fn =
    val (paramTypes, ret) = symbols(symbols.scope(decl.name)).info match
      case Type.ArrowType(lhs, r, _) => (Members.params(lhs).map(Lower.subst(_, sigma)), Lower.subst(r, sigma))
      case other                     => (Nil, other)
    val params = decl.params.zip(paramTypes).map((p, t) => fresh(p.name, ctype(t, p.span)))
    val sc = Scope(decl.params.map(_.name).zip(params).toMap, sigma)
    Fn(name, params, ctype(ret, decl.span), decl.body.fold(Expr.Ret(Atom.UnitC))(tail(_, sc)))

  private def expr(t: Tree, sc: Scope)(k: K): Expr =
    t match
      case l: Trees.IntLit =>
        ctype(typeOf(l, sc), l.span) match
          case f: CType.Float => k(Atom.FloatC(BigDecimal(l.value), f))
          case c              => k(Atom.IntC(l.value, c))
      case l: Trees.FloatLit         => k(Atom.FloatC(l.value, ctype(typeOf(l, sc), l.span)))
      case Trees.BoolLit(v, _)       => k(Atom.BoolC(v))
      case Trees.CharLit(c, _)       => k(Atom.IntC(BigInt(c.toInt), CType.Int(32, signed = false)))
      case Trees.StringLit(s, _)     => k(Atom.StrC(s))
      case Trees.UnitLit(_)          => k(Atom.UnitC)
      case Trees.Ident(name, span)   => sc.env.get(name).fold(unsupported(span, s"`$name` as a value", k))(k)
      case Trees.TupleExpr(elems, _) => all(elems, sc)(as => bind(Rhs.Con(0, as), t, sc)(k))
      case Trees.Block(stmts, _)     => block(stmts, sc)(k)
      case b: Trees.BinOp            => binOp(b, sc)(k)
      case u: Trees.UnaryOp          => unaryOp(u, sc)(k)
      case i: Trees.IfExpr           =>
        expr(i.cond, sc)(c => bind(Rhs.If(c, tail(i.thenBranch, sc), tail(i.elseBranch, sc)), i, sc)(k))
      case a @ Trees.Apply(f @ Trees.Ident(name, span), args, _) if !sc.env.contains(name) && isFn(name) =>
        if decls.contains(name) then call(name, typeOf(f, sc), args, a, sc)(k)
        else unsupported(span, s"call of `$name`, which has no body (prelude bodies arrive in 4.0.M4)", k)
      case a @ Trees.Apply(fn, args, _) =>
        expr(fn, sc)(c => all(args, sc)(as => bind(Rhs.CallClosure(c, as), a, sc)(k)))
      case l: Trees.LambdaExpr => lambda(l, sc)(k)
      case m: Trees.MethodCall => method(m, sc)(k)
      case s: Trees.StaticCall => static(s, sc)(k)
      case m: Trees.MatchExpr  => matchExpr(m, sc)(k)
      case other               => unsupported(other.span, other.getClass.getSimpleName, k)

  private def tail(t: Tree, sc: Scope): Expr = expr(t, sc)(Expr.Ret(_))

  private def block(stmts: List[Tree], sc: Scope)(k: K): Expr =
    stmts match
      case Nil                        => k(Atom.UnitC)
      case (l: Trees.LetExpr) :: rest =>
        expr(l.init, sc)(a => bindPattern(l.pattern, a, typeOf(l.init, sc), sc)(block(rest, _)(k)))
      case last :: Nil => expr(last, sc)(k)
      case e :: rest   => expr(e, sc)(_ => block(rest, sc)(k))

  /** Bind `pat` to the value `a` of type `t`: binders, `_` and tuple patterns of those. */
  private def bindPattern(pat: Tree, a: Atom, t: Type, sc: Scope)(k: Scope => Expr): Expr =
    pat match
      case Trees.BinderPat(name, _) =>
        a match
          case v: Var => k(sc.bind(name, v))
          case c      =>
            val x = fresh(name, c.tpe)
            Expr.Let(x, Rhs.Move(c), k(sc.bind(name, x)))
      case Trees.WildcardPat(_)     => k(sc)
      case Trees.TuplePat(elems, _) =>
        val types = t match
          case Type.TupleType(ts) => ts
          case _                  => Nil
        fields(a, elems.zip(types), Layout.slots(types.map(ctype(_, pat.span))), sc)(k)
      case other => unsupportedThen(other.span, s"pattern ${other.getClass.getSimpleName}", k(sc))

  /** Load the fields `pats` bind from `obj`, at `slots`. */
  private def fields(obj: Atom, pats: List[(Tree, Type)], slots: List[Int], sc: Scope)(k: Scope => Expr): Expr =
    pats.zip(slots) match
      case Nil                     => k(sc)
      case ((p, ft), slot) :: rest =>
        p match
          case Trees.WildcardPat(_) => fields(obj, rest.map(_._1), rest.map(_._2), sc)(k)
          case _                    =>
            val x = fresh("f", ctype(ft, p.span))
            Expr.Let(
              x,
              Rhs.Field(obj, slot),
              bindPattern(p, x, ft, sc)(fields(obj, rest.map(_._1), rest.map(_._2), _)(k))
            )

  private def binOp(b: Trees.BinOp, sc: Scope)(k: K): Expr =
    import Trees.BinOperator.*
    b.op match
      case And =>
        expr(b.lhs, sc)(l => bind(Rhs.If(l, tail(b.rhs, sc), Expr.Ret(Atom.BoolC(false))), b, sc)(k))
      case Or =>
        expr(b.lhs, sc)(l => bind(Rhs.If(l, Expr.Ret(Atom.BoolC(true)), tail(b.rhs, sc)), b, sc)(k))
      case op =>
        expr(b.lhs, sc)(l =>
          expr(b.rhs, sc)(r =>
            (op, l.tpe) match
              case (Eq, CType.Boxed)  => bind(Rhs.Call("fx_str_eq", List(l, r), runtime = true), b, sc)(k)
              case (Neq, CType.Boxed) =>
                val eq = fresh("eq", CType.Bool)
                val ne = bind(Rhs.Prim(PrimOp.Not, List(eq)), b, sc)(k)
                Expr.Let(eq, Rhs.Call("fx_str_eq", List(l, r), runtime = true), ne)
              case (_, CType.Boxed) => unsupported(b.span, s"`${op.symbol}` on boxed values", k)
              case _                => bind(Rhs.Prim(Lower.prim(op), List(l, r)), b, sc)(k)
          )
        )

  private def unaryOp(u: Trees.UnaryOp, sc: Scope)(k: K): Expr =
    val op = u.op match
      case Trees.UnaryOperator.Neg => PrimOp.Neg
      case Trees.UnaryOperator.Not => PrimOp.Not
    expr(u.operand, sc)(a => bind(Rhs.Prim(op, List(a)), u, sc)(k))

  /** A call of top-level function `name`, instantiated at the concrete type of its reference. */
  private def call(name: String, fnType: Type, args: List[Tree], t: Tree, sc: Scope)(k: K): Expr =
    val id = symbols.scope(name)
    val params = symbols.typeParamsOf(id)
    val solved = Lower.solve(params.toSet, symbols(id).info, fnType, Map.empty)
    val sigma = params.map(p => p -> solved.getOrElse(p, Type.UnitType)).toMap
    val inst = if params.isEmpty then name else params.map(p => symbols.render(sigma(p))).mkString(s"$name[", ",", "]")
    val _ = instance(inst)(function(decls(name), sigma, inst))
    all(args, sc)(as => bind(Rhs.Call(inst, as, runtime = false), t, sc)(k))

  /** A lambda becomes a code function taking the closure first; its captures are the variables
    * the lowered body uses but does not bind, loaded from the closure on entry.
    */
  private def lambda(l: Trees.LambdaExpr, sc: Scope)(k: K): Expr =
    val (paramTypes, ret) = typeOf(l, sc) match
      case Type.ArrowType(lhs, r, _) => (Members.params(lhs), r)
      case _                         => (Nil, Type.UnitType)
    val params = l.params.zip(paramTypes).map((p, pt) => fresh(p.name, ctype(pt, p.span)))
    val inner = l.params.map(_.name).zip(params).foldLeft(sc)((s, np) => s.bind(np._1, np._2))
    val body = tail(l.body, inner)
    val captures = (Core.freeVars(body) -- params).toList.sortBy(_.name)
    val env = fresh("env", CType.Boxed)
    val slots = Layout.slots(captures.map(_.ctype), first = 1)
    val loaded = captures.zip(slots).foldRight(body)((cs, acc) => Expr.Let(cs._1, Rhs.Field(env, cs._2), acc))
    val code = s"lambda.${ids.next()}"
    fns(code) = Fn(code, env :: params, ctype(ret, l.span), loaded)
    bind(Rhs.Closure(code, captures), l, sc)(k)

  private def method(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val recvType = typeOf(m.receiver, sc)
    val args = m.args.getOrElse(Nil)
    unit.resolutionOf(m) match
      case Some(Resolution.Field(variant, index)) =>
        dataOf(recvType) match
          case Some((data, dargs)) =>
            val slot = Layout.slots(fieldTypes(data, dargs, variant).map(ctype(_, m.span)))(index)
            expr(m.receiver, sc)(r => bind(Rhs.Field(r, slot), m, sc)(k))
          case None => unsupported(m.span, s"field `${m.method}` of ${symbols.render(recvType)}", k)
      case Some(Resolution.DerivedFold(_)) =>
        val result = typeOf(m, sc)
        generatedCall(recvType, m, sc)(derivedFold(_, _, result, m.span))(k)
      case Some(Resolution.CapMember(cap, _)) =>
        (symbols(cap).name, m.method, args) match
          case ("Concat", "concat", List(arg)) =>
            expr(m.receiver, sc)(r => expr(arg, sc)(a => runtime("fx_str_concat", List(r, a), m, sc)(k)))
          case ("Show", "show", Nil) if Lower.scalar(recvType).isDefined || isStringType(recvType) =>
            expr(m.receiver, sc)(r => show(r, recvType, m, sc)(k))
          case ("Folding", "fold", List(_, _)) if dataOf(recvType).exists((d, _) => listShaped(d)) =>
            generatedCall(recvType, m, sc)((d, a) => foldLeft(d, a, typeOf(m, sc), m.span))(k)
          case (c, name, _) =>
            unsupported(m.span, s"member $c.$name on ${symbols.render(recvType)} (arrives in 4.0.M4)", k)
      case None => unsupported(m.span, s"member `${m.method}`", k)

  /** Call the function `gen` generates for the receiver's data type: receiver first, then arguments. */
  private def generatedCall(recvType: Type, m: Trees.MethodCall, sc: Scope)(gen: (SymbolId, List[Type]) => String)(
      k: K
  ): Expr =
    dataOf(recvType) match
      case Some((data, dargs)) =>
        val f = gen(data, dargs)
        expr(m.receiver, sc)(r =>
          all(m.args.getOrElse(Nil), sc)(as => bind(Rhs.Call(f, r :: as, runtime = false), m, sc)(k))
        )
      case None => unsupported(m.span, s"`${m.method}` on ${symbols.render(recvType)}", k)

  private def show(r: Atom, receiver: Type, m: Tree, sc: Scope)(k: K): Expr =
    def widened(name: String, to: CType) =
      if r.tpe == to then runtime(name, List(r), m, sc)(k)
      else
        val w = fresh("w", to)
        Expr.Let(w, Rhs.Prim(PrimOp.Widen, List(r)), runtime(name, List(w), m, sc)(k))
    r.tpe match
      case _ if receiver == Type.PrimType("char") => runtime("fx_show_char", List(r), m, sc)(k)
      case CType.Int(_, true)                     => widened("fx_show_i64", CType.Int(64, signed = true))
      case CType.Int(_, false)                    => widened("fx_show_u64", CType.Int(64, signed = false))
      case CType.Float(_)                         => widened("fx_show_f64", CType.Float(64))
      case CType.Bool                             => runtime("fx_show_bool", List(r), m, sc)(k)
      case CType.Unit                             => k(Atom.StrC("()"))
      case CType.Boxed                            => k(r)

  /** Data constructors, derived `unfold`, capability constructors resolved by the typer, and the
    * runtime effects. ponytail: `Console` is matched by name; effect resolution arrives with
    * handlers in 4.0.M4.
    */
  private def static(s: Trees.StaticCall, sc: Scope)(k: K): Expr =
    val args = s.args.getOrElse(Nil)
    val owner = symbols.scope.get(s.qualifier)
    val resolved = unit.typing.flatMap(_.constructors.find(_._1 eq s)).map((_, d, v) => (d, v))
    (s.qualifier, s.method, args) match
      case ("Console", "print_line", List(arg)) => expr(arg, sc)(a => runtime("fx_print_line", List(a), s, sc)(k))
      case ("Console", "read_line", Nil)        => runtime("fx_read_line", Nil, s, sc)(k)
      case _                                    =>
        owner.filter(symbols(_).kind == SymKind.Data).map(d => d -> s.method).orElse(resolved) match
          case Some((data, "unfold")) if !hasVariant(data, "unfold") =>
            all(args, sc) { as =>
              val f = derivedUnfold(data, dataOf(typeOf(s, sc)).fold(Nil)(_._2), as.head, s.span)
              bind(Rhs.Call(f, as, runtime = false), s, sc)(k)
            }
          case Some((data, variant)) if hasVariant(data, variant) =>
            val tag = symbols.variantsOf(data).indexWhere(_.name == variant)
            all(args, sc)(as => bind(Rhs.Con(tag, as), s, sc)(k))
          case _ => unsupported(s.span, s"`${s.qualifier}.${s.method}`", k)

  /** `match` on a data value: one `Switch` branch per variant, taken from the first case covering it. */
  private def matchExpr(m: Trees.MatchExpr, sc: Scope)(k: K): Expr =
    val t = typeOf(m.scrutinee, sc)
    (dataOf(t), m.cases.exists(_.guard.isDefined)) match
      case (Some((data, dargs)), false) =>
        expr(m.scrutinee, sc) { s =>
          val branches = symbols.variantsOf(data).toList.zipWithIndex.map { (v, tag) =>
            val branch = m.cases.find(c => Lower.covers(c.pattern, v.name)).fold(Expr.Ret(Atom.UnitC)) { c =>
              c.pattern match
                case Trees.DataVariantPat(_, _, pats, _) =>
                  val types = fieldTypes(data, dargs, v.id)
                  fields(s, pats.getOrElse(Nil).zip(types), Layout.slots(types.map(ctype(_, c.span))), sc)(
                    tail(c.body, _)
                  )
                case p => bindPattern(p, s, t, sc)(tail(c.body, _))
            }
            (tag, branch)
          }
          bind(Rhs.Switch(s, branches), m, sc)(k)
        }
      case _ => unsupported(m.span, s"`match` on ${symbols.render(t)} or with guards", k)

  /** Rule 7.4.a: `x.fold(on_v1, ..., on_vn)`, recursive fields folded first. */
  private def derivedFold(data: SymbolId, args: List[Type], r: Type, span: Span): String =
    val name = generatedName(data, args, "fold", symbols.render(r))
    instance(name) {
      val x = fresh("x", CType.Boxed)
      val callbacks = symbols.variantsOf(data).toList.map(v => fresh(s"on_${v.name}", CType.Boxed))
      val rt = ctype(r, span)
      val branches = perVariant(data, args, x, ctype(_, span)) { (tag, loaded) =>
        chain(loaded, data, rt)(f => Rhs.Call(name, f :: callbacks, runtime = false))(as =>
          Rhs.CallClosure(callbacks(tag), as)
        )
      }
      Fn(name, x :: callbacks, rt, switch(x, branches, rt))
    }

  /** Rule 7.4.b: `D.unfold(seed, step)`. The step returns one layer whose recursive fields hold
    * seeds; each is unfolded in turn. (The layer is a separate allocation: fusion is Phase 4.)
    */
  private def derivedUnfold(data: SymbolId, args: List[Type], seed: Atom, span: Span): String =
    val seedType = seed.tpe
    val name = generatedName(data, args, "unfold", seedType.toString)
    instance(name) {
      val s = fresh("seed", seedType)
      val step = fresh("step", CType.Boxed)
      val layer = fresh("layer", CType.Boxed)
      val branches = perVariant(data, args, layer, t => if Lower.isSelf(t, data) then seedType else ctype(t, span)) {
        (tag, loaded) =>
          chain(loaded, data, CType.Boxed)(f => Rhs.Call(name, List(f, step), runtime = false))(Rhs.Con(tag, _))
      }
      Fn(
        name,
        List(s, step),
        CType.Boxed,
        Expr.Let(layer, Rhs.CallClosure(step, List(s)), switch(layer, branches, CType.Boxed))
      )
    }

  /** `Folding.fold(init, f)` on a list-shaped data type: a left fold. ponytail: generated here until
    * prelude bodies in Fixed arrive (4.0.M4, slice §4.4); spec §7.4 does not derive it.
    */
  private def foldLeft(data: SymbolId, args: List[Type], accType: Type, span: Span): String =
    val name = generatedName(data, args, "foldLeft", symbols.render(accType))
    instance(name) {
      val rt = ctype(accType, span)
      val (xs, init, f) = (fresh("xs", CType.Boxed), fresh("init", rt), fresh("f", CType.Boxed))
      val branches = perVariant(data, args, xs, ctype(_, span)) { (_, loaded) =>
        (loaded.find(l => !Lower.isSelf(l._1, data)), loaded.find(l => Lower.isSelf(l._1, data))) match
          case (Some((_, elem)), Some((_, rest))) =>
            val (acc, res) = (fresh("acc", rt), fresh("res", rt))
            Expr.Let(
              acc,
              Rhs.CallClosure(f, List(init, elem)),
              Expr.Let(res, Rhs.Call(name, List(rest, acc, f), runtime = false), Expr.Ret(res))
            )
          case _ => Expr.Ret(init)
      }
      Fn(name, List(xs, init, f), rt, switch(xs, branches, rt))
    }

  private def generatedName(data: SymbolId, args: List[Type], member: String, at: String): String =
    s"${symbols.render(Type.TypeRef(data, args))}.$member[$at]"

  /** One `Switch` branch per variant of `data`: `obj`'s fields loaded, then `body(tag, fields)`. */
  private def perVariant(data: SymbolId, args: List[Type], obj: Var, fieldType: Type => CType)(
      body: (Int, List[(Type, Var)]) => Expr
  ): List[(Int, Expr)] =
    symbols.variantsOf(data).toList.zipWithIndex.map { (v, tag) =>
      val types = fieldTypes(data, args, v.id)
      val cts = types.map(fieldType)
      val vars = cts.map(fresh("f", _))
      val loads = vars.zip(Layout.slots(cts))
      (tag, loads.foldRight(body(tag, types.zip(vars)))((l, acc) => Expr.Let(l._1, Rhs.Field(obj, l._2), acc)))
    }

  /** Replace each recursive field by `recurse(field)`, then return `finish(fields)`. */
  private def chain(loaded: List[(Type, Var)], data: SymbolId, rt: CType)(recurse: Var => Rhs)(
      finish: List[Atom] => Rhs
  ): Expr =
    loaded match
      case Nil =>
        val res = fresh("r", rt)
        Expr.Let(res, finish(Nil), Expr.Ret(res))
      case (t, f) :: rest =>
        val tailOf = (done: Atom) => chain(rest, data, rt)(recurse)(as => finish(done :: as))
        if Lower.isSelf(t, data) then
          val rec = fresh("rec", if rt == CType.Boxed then CType.Boxed else rt)
          Expr.Let(rec, recurse(f), tailOf(rec))
        else tailOf(f)

  private def switch(obj: Var, branches: List[(Int, Expr)], rt: CType): Expr =
    val res = fresh("res", rt)
    Expr.Let(res, Rhs.Switch(obj, branches), Expr.Ret(res))

  /** Every variant has no fields, or one recursive field and one element field. */
  private def listShaped(data: SymbolId): Boolean =
    symbols.variantsOf(data).forall { v =>
      val fs = symbols.fieldsOf(v.id).toList
      fs.isEmpty || (fs.size == 2 && fs.count(f => Lower.isSelf(f.info, data)) == 1)
    }

  private def hasVariant(data: SymbolId, name: String): Boolean = symbols.variantsOf(data).exists(_.name == name)

  /** A data type and its arguments: a data reference, a tuple (the prelude's `Tuple`) or an unfold layer. */
  private def dataOf(t: Type): Option[(SymbolId, List[Type])] =
    t match
      case Type.TypeRef(id, args) if symbols(id).kind == SymKind.Data && !isStringType(t) => Some(id -> args)
      case Type.TupleType(ts)     => symbols.scope.get("Tuple").map(_ -> ts)
      case Type.Layer(d, args, _) => Some(d -> args)
      case _                      => None

  /** A variant's field types for data arguments `args`; inside a layer, recursive fields hold the seed. */
  private def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type] =
    val sigma = symbols.typeParamsOf(data).zip(args).toMap
    symbols.fieldsOf(variant).map(f => Lower.subst(f.info, sigma)).toList

  private def runtime(name: String, args: List[Atom], t: Tree, sc: Scope)(k: K): Expr =
    bind(Rhs.Call(name, args, runtime = true), t, sc)(k)

  private def all(ts: List[Tree], sc: Scope)(k: List[Atom] => Expr): Expr =
    ts match
      case Nil       => k(Nil)
      case t :: rest => expr(t, sc)(a => all(rest, sc)(as => k(a :: as)))

  /** Bind `rhs` to a fresh variable of `t`'s type and continue with it. */
  private def bind(rhs: Rhs, t: Tree, sc: Scope)(k: K): Expr =
    val x = fresh("t", ctype(typeOf(t, sc), t.span))
    Expr.Let(x, rhs, k(x))

  private def fresh(base: String, t: CType): Var = Var(s"$base.${ids.next()}", t)

  private def typeOf(t: Tree, sc: Scope): Type = Lower.subst(unit.typeOf(t).getOrElse(Type.NeverType), sc.sigma)

  private def isFn(name: String): Boolean = symbols.scope.get(name).exists(symbols(_).kind == SymKind.Fn)

  private def isStringType(t: Type): Boolean =
    t match
      case Type.TypeRef(id, Nil)   => symbols(id).name == "String" && symbols(id).owner == SymbolId.Prelude
      case Type.PrimType("String") => true
      case _                       => false

  private def ctype(t: Type, span: Span): CType =
    Lower.scalar(t).getOrElse(t match
      case _ if isStringType(t) || dataOf(t).isDefined => CType.Boxed
      case _: Type.ArrowType                           => CType.Boxed
      case _                                           =>
        problems += problem(DiagCode.B005, span, s"values of type ${symbols.render(t)} are not supported yet")
        CType.Unit)

  private def unsupportedThen(span: Span, what: String, rest: => Expr): Expr =
    problems += problem(DiagCode.B005, span, s"$what is not supported by the backend slice yet")
    rest

  private def unsupported(span: Span, what: String, k: K): Expr = unsupportedThen(span, what, k(Atom.UnitC))

  private def problem(code: DiagCode, span: Span, message: String): Diagnostic =
    Diagnostic(Severity.Error, code, span, message)

object Lower:

  private val ints =
    Map("i8" -> 8, "i16" -> 16, "i32" -> 32, "i64" -> 64, "u8" -> 8, "u16" -> 16, "u32" -> 32, "u64" -> 64)

  /** The Core type of a scalar typer type. */
  def scalar(t: Type): Option[CType] =
    t match
      case Type.PrimType(n) if ints.contains(n) => Some(CType.Int(ints(n), signed = n.startsWith("i")))
      case Type.PrimType("f32")                 => Some(CType.Float(32))
      case Type.PrimType("f64")                 => Some(CType.Float(64))
      case Type.PrimType("bool")                => Some(CType.Bool)
      case Type.PrimType("char")                => Some(CType.Int(32, signed = false))
      case Type.UnitType | Type.NeverType       => Some(CType.Unit)
      case _                                    => None

  /** Replace type parameters by their concrete types. */
  def subst(t: Type, sigma: Map[SymbolId, Type]): Type =
    t match
      case Type.TypeRef(p, Nil) if sigma.contains(p) => sigma(p)
      case other                                     => other.mapChildren(subst(_, sigma))

  /** Match a declared signature against a concrete type, solving the declared type parameters. */
  def solve(params: Set[SymbolId], declared: Type, concrete: Type, acc: Map[SymbolId, Type]): Map[SymbolId, Type] =
    declared match
      case Type.TypeRef(p, Nil) if params(p)         => if acc.contains(p) then acc else acc.updated(p, concrete)
      case _ if declared.ordinal == concrete.ordinal =>
        declared.children.zip(concrete.children).foldLeft(acc)((a, dc) => solve(params, dc._1, dc._2, a))
      case _ => acc

  def isSelf(t: Type, data: SymbolId): Boolean =
    t match
      case Type.TypeRef(`data`, _) => true
      case _                       => false

  /** Whether case pattern `p` covers variant `variant`. */
  def covers(p: Tree, variant: String): Boolean =
    p match
      case Trees.DataVariantPat(_, v, _, _)          => v == variant
      case _: Trees.WildcardPat | _: Trees.BinderPat => true
      case Trees.OrPat(l, r, _)                      => covers(l, variant) || covers(r, variant)
      case _                                         => false

  private def prim(op: Trees.BinOperator): PrimOp =
    import Trees.BinOperator.*
    op match
      case Add      => PrimOp.Add
      case Sub      => PrimOp.Sub
      case Mul      => PrimOp.Mul
      case Div      => PrimOp.Div
      case Mod      => PrimOp.Rem
      case Eq       => PrimOp.Eq
      case Neq      => PrimOp.Ne
      case Lt       => PrimOp.Lt
      case Le       => PrimOp.Le
      case Gt       => PrimOp.Gt
      case Ge       => PrimOp.Ge
      case And | Or => PrimOp.Eq
