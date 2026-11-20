package fixed.backend

import scala.collection.mutable

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.backend.Atom.Var
import fixed.core.{SymKind, SymbolId, SymbolTable, Type, render}
import fixed.typer.{Members, Resolution}
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Typed tree to Core (slice §4.1–§4.4), in continuation-passing style: `expr(t, sc)(k)` lowers `t`
  * and hands its atom to `k`, which builds the rest of the function. Starts at `main` and lowers
  * each function instance it reaches, monomorphised: `sc.sigma` maps the instance's type
  * parameters to concrete types. Derived members (`fold`, `unfold`, `Folding.fold`) are generated
  * as Core functions. Constructs beyond the current milestone are `B005`, and so is a cell with a
  * reference beyond its header's reference map (`refMapLimit`).
  */
final class Lower(unit: CompilationUnit) extends Generated:
  private type Env = Map[String, Var]
  private type Sigma = Map[SymbolId, Type]

  /** Lexical bindings and the type substitution of the instance being lowered. */
  final private case class Scope(env: Env, sigma: Sigma, bounds: Map[Type, Type] = Map.empty):
    def bind(name: String, v: Var): Scope = copy(env = env.updated(name, v))

  private val ids = Iterator.from(1)
  private val problems = mutable.ArrayBuffer.empty[Diagnostic]
  protected val symbols: SymbolTable = unit.symbols
  private val fns = mutable.LinkedHashMap.empty[String, Fn]
  private val represent = Representation(unit)

  /** Concrete typer type of each Core variable lowering could type (by variable name), and the
    * source span of each variable and function. Read by `Demands` (3.4.M2).
    */
  val types: mutable.Map[String, Type] = mutable.HashMap.empty
  val spans: mutable.Map[String, Span] = mutable.HashMap.empty

  /** The function instances declared `fip fn`, with their contract (3.4.M5). */
  val fips: mutable.Map[String, Trees.Fip] = mutable.HashMap.empty

  /** Top-level functions with bodies: the prelude's, then the unit's (which shadow them). */
  private val decls: Map[String, Trees.FnDecl] = fixed.typer.Stdlib.functions(unit.tree)

  /** Every function instance built so far, including copies requested after [[program]]. */
  def functions: List[Fn] = fns.values.toList

  /** A function copying a value of type `t`: cells at part keys `parts` copied, the rest shared. */
  def copy(t: Type, parts: Set[(String, Int)]): Option[String] =
    dataOf(t).map((d, args) => copyOf(d, args, parts, symbols.render(_), Span.NoSpan))

  /** Every function instance reachable from `main`, or the problems found. */
  def program: Either[List[Diagnostic], List[Fn]] =
    if !decls.contains("main") then Left(List(problem(DiagCode.B003, Span.NoSpan, "no `main` function")))
    else
      val _ = instance("main")(function(decls("main"), Map.empty, Map.empty, "main"))
      fns.values.foreach(refMapLimit)
      if problems.isEmpty then Right(fns.values.toList) else Left(problems.toList)

  /** A cell of `f` with a reference in a slot its header's reference map cannot mark is `B005`. */
  private def refMapLimit(f: Fn): Unit =
    Core.lets(f.body).map(_._2).foreach { r =>
      val refs = r match
        case Rhs.Con(_, fs)        => Layout.refSlotsOf(fs.map(_.tpe))
        case Rhs.Closure(_, cs, _) => Layout.refSlotsOf(cs.map(_.tpe), first = Layout.closureCode)
        case _                     => Nil
      refs.find(_ >= Layout.refMapBits).foreach { s =>
        problems += problem(
          DiagCode.B005,
          spans.getOrElse(f.name, Span.NoSpan),
          s"`${f.name}` builds a cell with a reference in payload slot $s; a cell can hold references " +
            s"only in its first ${Layout.refMapBits} slots (a closure: in its first " +
            s"${Layout.refMapBits - Layout.closureCode} captures)"
        )
      }
    }

  /** Build function `name` once. A recursive request meets the placeholder entered first. */
  protected def instance(name: String)(build: => Fn): String =
    if !fns.contains(name) then
      fns(name) = Fn(name, Nil, CType.Unit, Expr.Ret(Atom.UnitC))
      fns(name) = build
    name

  /** Instance `name` of `decl`: `sigma` for its type parameters, `bounds` for its capability-bound
    * parameter types (each replaced by the concrete argument type at the call).
    */
  private def function(decl: Trees.FnDecl, sigma: Sigma, bounds: Map[Type, Type], name: String): Fn =
    val (paramTypes, ret) = symbols(symbols.scope(decl.name)).info match
      case Type.ArrowType(lhs, r, _) =>
        (Members.params(lhs).map(concrete(_, sigma, bounds)), concrete(r, sigma, bounds))
      case other => (Nil, other)
    val params = decl.params.zip(paramTypes).map((p, t) => typed(fresh(p.name, ctype(t, p.span)), t, p.span))
    spans(name) = decl.span
    decl.fip.foreach(fips(name) = _)
    val sc = Scope(decl.params.map(_.name).zip(params).toMap, sigma, bounds)
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
      case Trees.Apply(fn, args, _) => application(fn, args, t, sc)(k)
      case Trees.ListExpr(elems, _) =>
        val listType = typeOf(t, sc)
        all(elems, sc)(as => listOf(as, listType, t)(k))
      case Trees.Pipe(lhs, Trees.Apply(fn, args, _), _) => application(fn, lhs :: args, t, sc)(k)
      case Trees.Pipe(lhs, fn, _)                       => application(fn, List(lhs), t, sc)(k)
      case l: Trees.LambdaExpr                          => lambda(l, sc)(k)
      case m: Trees.MethodCall                          => method(m, sc)(k)
      case s: Trees.StaticCall                          => static(s, sc)(k)
      case m: Trees.MatchExpr                           => matchExpr(m, sc)(k)
      case h: Trees.HandleExpr                          => handle(h, sc)(k)
      case other                                        => unsupported(other.span, other.getClass.getSimpleName, k)

  private def tail(t: Tree, sc: Scope): Expr = expr(t, sc)(Expr.Ret(_))

  /** `fn(args)`, typed as `result`: a top-level function instance, or a closure call. */
  private def application(fn: Tree, args: List[Tree], result: Tree, sc: Scope)(k: K): Expr =
    fn match
      case f @ Trees.Ident(name, span) if !sc.env.contains(name) && isFn(name) =>
        if decls.contains(name) then call(name, typeOf(f, sc), args, result, sc)(k)
        else unsupported(span, s"call of `$name`, which has no body", k)
      case _ => expr(fn, sc)(c => all(args, sc)(as => bind(Rhs.CallClosure(c, as), result, sc)(k)))

  /** A list literal: `Cons` cells built from the last element back, ending in the static `Nil`. */
  private def listOf(elems: List[Atom], listType: Type, t: Tree)(k: K): Expr =
    dataOf(listType) match
      case Some((list, _)) =>
        val cons = symbols.variantsOf(list).indexWhere(_.name == "Cons")
        val nil = fresh("nil", CType.Boxed)
        val cells = elems.foldRight((List.empty[(Var, Rhs)], nil: Atom)) { case (e, (lets, next)) =>
          val cell = fresh("cell", CType.Boxed)
          ((cell, Rhs.Con(cons, List(e, next))) :: lets, cell)
        }
        val (lets, head) = cells
        Expr.Let(
          nil,
          Rhs.Con(symbols.variantsOf(list).indexWhere(_.name == "Nil"), Nil),
          lets.reverse.foldRight(k(head))((l, acc) => Expr.Let(l._1, l._2, acc))
        )
      case None => unsupported(t.span, s"a list literal of type ${symbols.render(listType)}", k)

  private def block(stmts: List[Tree], sc: Scope)(k: K): Expr =
    stmts match
      case Nil                        => k(Atom.UnitC)
      case (l: Trees.LetExpr) :: rest =>
        expr(l.init, sc)(a => bindPattern(l.pattern, a, typeOf(l.init, sc), sc)(block(rest, _)(k)))
      case (f: Trees.FnDecl) :: rest => nestedFn(f, sc)(c => block(rest, sc.bind(f.name, c))(k))
      case last :: Nil               => expr(last, sc)(k)
      case e :: rest                 => expr(e, sc)(_ => block(rest, sc)(k))

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
            val x = typed(fresh("f", ctype(ft, p.span)), ft, p.span)
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
    val declared = symbols(id).info match
      case Type.ArrowType(lhs, _, _) => Members.params(lhs)
      case _                         => Nil
    val bounds = declared.zip(args.map(typeOf(_, sc))).collect {
      case (d @ (_: Type.IsBound | _: Type.CapRef), actual: Type.CapRef)                => d -> actual
      case (d @ (_: Type.IsBound | _: Type.CapRef), actual) if dataOf(actual).isDefined => d -> actual
    }.toMap
    val typeArgs = params.map(sigma) ++ declared.flatMap(bounds.get)
    val inst = if typeArgs.isEmpty then name else typeArgs.map(symbols.render(_)).mkString(s"$name[", ",", "]")
    val _ = instance(inst)(function(decls(name), sigma, bounds, inst))
    all(args, sc)(as => bind(Rhs.Call(inst, as, runtime = false), t, sc)(k))

  /** A lambda becomes a code function taking the closure first; its captures are the variables
    * the lowered body uses but does not bind, loaded from the closure on entry.
    */
  private def lambda(l: Trees.LambdaExpr, sc: Scope)(k: K): Expr =
    val (paramTypes, ret) = typeOf(l, sc) match
      case Type.ArrowType(lhs, r, _) => (Members.params(lhs), r)
      case _                         => (Nil, Type.UnitType)
    val (params, inner) = withParams(l.params.map(_.name).zip(paramTypes.map(ctype(_, l.span))), sc)
    params.zip(paramTypes).foreach((p, t) => typed(p, t, l.span))
    closure(params, tail(l.body, inner), ctype(ret, l.span))(k)

  /** Fresh variables for parameters `named`, and `sc` with them bound. */
  private def withParams(named: List[(String, CType)], sc: Scope): (List[Var], Scope) =
    val params = named.map((n, t) => fresh(n, t))
    (params, named.map(_._1).zip(params).foldLeft(sc)((s, np) => s.bind(np._1, np._2)))

  /** A closure over `body`: a code function taking the closure first, then `params`. Its captures
    * are the variables `body` uses but does not bind, loaded from the closure on entry.
    */
  protected def closure(params: List[Var], body: Expr, ret: CType)(k: Var => Expr): Expr =
    val captures = (Core.freeVars(body) -- params).toList.sortBy(_.name)
    val env = fresh("env", CType.Boxed)
    val slots = Layout.slots(captures.map(_.ctype), first = Layout.closureCode)
    val loaded = captures.zip(slots).foldRight(body)((cs, acc) => Expr.Let(cs._1, Rhs.Field(env, cs._2), acc))
    val code = s"lambda.${ids.next()}"
    fns(code) = Fn(code, env :: params, ret, loaded)
    val x = fresh("closure", CType.Boxed)
    Expr.Let(x, Rhs.Closure(code, captures), k(x))

  private def method(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val recvType = typeOf(m.receiver, sc)
    val args = m.args.getOrElse(Nil)
    (m.receiver, unit.resolutionOf(m)) match
      case (Trees.Ident("builtin", _), _) if !sc.env.contains("builtin") => intrinsic(m, sc)(k)
      case (_, None) if productField(recvType, m.method).isDefined       => fieldRead(recvType, m, sc)(k)
      case (_, resolution) => resolved(m, recvType, args, resolution, sc)(k)

  private def resolved(
      m: Trees.MethodCall,
      recvType: Type,
      args: List[Tree],
      resolution: Option[Resolution],
      sc: Scope
  )(
      k: K
  ): Expr =
    lazy val listLike = dataOf(recvType).exists((d, _) => listShaped(d))
    resolution match
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
          case ("Folding", "fold", List(_, _)) if listLike =>
            generatedCall(recvType, m, sc)((d, a) => foldLeft(d, a, typeOf(m, sc), m.span))(k)
          case (_, "fold", _) if mappedFold(cap, recvType, args.size).isDefined =>
            val order = mappedFold(cap, recvType, args.size).get
            val (data, dargs) = dataOf(recvType).get
            val f = derivedFold(data, dargs, typeOf(m, sc), m.span)
            expr(m.receiver, sc)(r =>
              all(args, sc)(as => bind(Rhs.Call(f, r :: order.map(as(_)), runtime = false), m, sc)(k))
            )
          case ("Functor", "map", List(_)) if dataOf(recvType).isDefined =>
            val newElem = dataOf(typeOf(m, sc)).flatMap(_._2.lastOption).getOrElse(Type.UnitType)
            generatedCall(recvType, m, sc)(derivedMap(_, _, newElem, m.span))(k)
          case ("Sequencing", "length", Nil) if listLike =>
            generatedCall(recvType, m, sc)(length(_, _, typeOf(m, sc), m.span))(k)
          case ("Folding", "find", List(_)) if listLike =>
            val raw = unit.typeOf(m).getOrElse(Type.NeverType)
            val optional = Members.expandAliases(
              symbols,
              raw match
                case Type.IsBound(bs) => bs
                case other            => List(other)
            ).collectFirst { case Type.CapRef(c, _) => c }
            optional match
              case Some(opt) => generatedCall(recvType, m, sc)(find(_, _, opt, typeOf(m, sc), m.span))(k)
              case None      => unsupported(m.span, s"`find` returning ${symbols.render(raw)}", k)
          case (_, name, Nil) if productField(recvType, name).isDefined => fieldRead(recvType, m, sc)(k)
          case (c, name, _)                                             =>
            unsupported(m.span, s"member $c.$name on ${symbols.render(recvType)} (arrives in 4.0.M4)", k)
      case None => unsupported(m.span, s"member `${m.method}`", k)

  /** A sum capability's `fold` on the data type representing it: callbacks follow the capability's
    * constructor requirements; the data's derived fold takes them in variant order. For each
    * variant, the index of the argument that handles it.
    */
  private def mappedFold(cap: SymbolId, recvType: Type, arity: Int): Option[List[Int]] =
    dataOf(recvType).flatMap { (data, _) =>
      val ctors = symbols.membersOf(cap).filter(_.kind == SymKind.StaticMethod).map(_.name).toList
      val variants = symbols.variantsOf(data).map(_.name).toList
      val variantOf = ctors.map(c => symbols.constructorsOf(cap, c).find(_._1 == symbols(data).name).map(_._2))
      Option.when(ctors.size == arity && variantOf.forall(_.isDefined) && variantOf.flatten.toSet == variants.toSet)(
        variants.map(v => variantOf.indexOf(Some(v)))
      )
    }

  private def fieldRead(recvType: Type, m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    expr(m.receiver, sc)(r => bind(Rhs.Field(r, productField(recvType, m.method).get), m, sc)(k))

  /** The slot of field `name` of an auto-flat-struct or single-variant data value. */
  private def productField(t: Type, name: String): Option[Int] =
    t match
      case Type.CapRef(c, args) =>
        val fs = represent.fields(c, args)
        Option(fs.indexWhere(_._1 == name)).filter(_ >= 0).map(Layout.slots(fs.map(f => ctype(f._2, Span.NoSpan)))(_))
      case _ =>
        dataOf(t).flatMap { (data, args) =>
          symbols.variantsOf(data).toList match
            case List(v) =>
              val names = symbols.fieldsOf(v.id).map(_.name).toList
              val types = fieldTypes(data, args, v.id)
              Option(names.indexOf(name)).filter(_ >= 0).map(Layout.slots(types.map(ctype(_, Span.NoSpan)))(_))
            case _ => None
        }

  /** `builtin.parse_u64(s)`: the C side parses; the result is built with the constructors that
    * `Result.ok` and `Result.err` map to on the representing data type.
    */
  private def intrinsic(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val result = typeOf(m, sc)
    (m.method, m.args.getOrElse(Nil), dataOf(result), symbols.scope.get("Result")) match
      case ("parse_u64", List(arg), Some((data, _)), Some(cap)) =>
        val tagOf = (ctor: String) =>
          symbols.constructorsOf(cap, ctor).find(_._1 == symbols(data).name)
            .map(c => symbols.variantsOf(data).indexWhere(_.name == c._2)).getOrElse(0)
        expr(arg, sc) { a =>
          val (v, ok) = (fresh("n", CType.Int(64, signed = false)), fresh("ok", CType.Bool))
          val (yes, no) = (fresh("yes", CType.Boxed), fresh("no", CType.Boxed))
          Expr.Let(
            v,
            Rhs.Call("fx_parse_u64", List(a), runtime = true),
            Expr.Let(
              ok,
              Rhs.Call("fx_parse_ok", Nil, runtime = true),
              bind(
                Rhs.If(
                  ok,
                  Expr.Let(yes, Rhs.Con(tagOf("ok"), List(v)), Expr.Ret(yes)),
                  Expr.Let(no, Rhs.Con(tagOf("err"), List(Atom.StrC("not a number"))), Expr.Ret(no))
                ),
                m,
                sc
              )(k)
            )
          )
        }
      case _ => unsupported(m.span, s"`builtin.${m.method}`", k)

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
            construct(tag, args, s, sc)(k)
          case _ =>
            owner.map(symbols(_)) match
              case Some(cap) if cap.kind == SymKind.Cap && capVariant(cap.id, s.method, typeOf(s, sc)).isDefined =>
                val tag = capVariant(cap.id, s.method, typeOf(s, sc)).get
                construct(tag, args, s, sc)(k)
              case Some(cap) if represent.constructor(cap.id).exists(_.name == s.method) =>
                construct(0, args, s, sc)(k)
              case Some(effect) if effect.kind == SymKind.Effect => operation(s, effect.id, args, sc)(k)
              case _ => unsupported(s.span, s"`${s.qualifier}.${s.method}`", k)

  private def construct(tag: Int, args: List[Tree], t: Tree, sc: Scope)(k: K): Expr =
    all(args, sc)(as => bind(Rhs.Con(tag, as), t, sc)(k))

  private def effectId(effect: SymbolId): Atom = Atom.IntC(BigInt(effect.toInt), CType.Int(64, signed = true))

  /** An abortive effect operation (`-> !`): record the effect and its payload, then abort (`Aborts`). */
  private def operation(s: Trees.StaticCall, effect: SymbolId, args: List[Tree], sc: Scope)(k: K): Expr =
    if !Members.abortive(symbols, effect, s.method) then
      problems +=
        problem(DiagCode.B002, s.span, s"`${s.qualifier}.${s.method}`: only abortive operations are supported")
      k(Atom.UnitC)
    else
      all(args, sc) { as =>
        val payload = as.headOption.getOrElse(Atom.StrC(""))
        if !payload.tpe.boxed then unsupported(s.span, "an abortive operation with a scalar payload", k)
        else
          val u = fresh("abort", CType.Unit)
          Expr.Let(
            u,
            Rhs.Call(
              "fx_abort",
              List(effectId(effect), payload),
              runtime = true
            ),
            k(Atom.UnitC)
          )
      }

  /** `handle subject: clauses` for abortive operations (slice §4.6). The subject runs as its own
    * function (a `Catch` call); each clause tests whether its effect aborted it.
    */
  private def handle(h: Trees.HandleExpr, sc: Scope)(k: K): Expr =
    val subjectType = typeOf(h.subject, sc)
    val body = tail(h.subject, sc)
    val captures = Core.freeVars(body).toList.sortBy(_.name)
    val code = s"handle.${ids.next()}"
    fns(code) = Fn(code, captures, ctype(subjectType, h.span), body)
    val v = fresh("subject", ctype(subjectType, h.span))
    val resultType = ctype(typeOf(h, sc), h.span)
    val returned =
      h.returnClause.fold(Expr.Ret(v): Expr)(rc => bindPattern(rc.param, v, subjectType, sc)(tail(rc.body, _)))
    val normal = guarded("fx_aborting", Expr.Ret(Atom.UnitC), returned, resultType)
    val dispatch = h.operationClauses.foldRight(normal) { (c, otherwise) =>
      symbols.scope.get(c.effect) match
        case Some(effect) =>
          val caught = Rhs.Call(
            "fx_abort_catch",
            List(effectId(effect)),
            runtime = true
          )
          val payload = fresh("payload", CType.Boxed)
          val clause = Expr.Let(
            payload,
            Rhs.Call("fx_abort_payload", Nil, runtime = true),
            c.params.headOption.fold(tail(c.body, sc))(p => bindPattern(p, payload, Type.UnitType, sc)(tail(c.body, _)))
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

  /** A nested `fn` is a closure bound to its name. */
  private def nestedFn(f: Trees.FnDecl, sc: Scope)(k: Var => Expr): Expr =
    symbols.denotations.find(d =>
      d.kind == SymKind.Fn && d.name == f.name && d.span.start >= f.span.start && d.span.end <= f.span.end
    ) match
      case Some(d) =>
        val ps = symbols.membersOf(d.id).filter(_.kind == SymKind.ValueParam).toList
        val ret = d.info match
          case Type.ArrowType(_, r, _) => concrete(r, sc.sigma, sc.bounds)
          case _                       => Type.UnitType
        val (params, inner) =
          withParams(ps.map(p => p.name -> ctype(concrete(p.info, sc.sigma, sc.bounds), f.span)), sc)
        closure(params, f.body.fold(Expr.Ret(Atom.UnitC))(tail(_, inner)), ctype(ret, f.span))(k)
      case None => unsupportedThen(f.span, s"nested fn `${f.name}`", k(fresh(f.name, CType.Boxed)))

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

  private def hasVariant(data: SymbolId, name: String): Boolean = symbols.variantsOf(data).exists(_.name == name)

  /** A data type and its arguments: a data reference, a tuple (the prelude's `Tuple`) or an unfold layer. */
  protected def dataOf(t: Type): Option[(SymbolId, List[Type])] =
    t match
      case Type.TypeRef(id, args) if symbols(id).kind == SymKind.Data && !isStringType(t) => Some(id -> args)
      case Type.TupleType(ts)     => symbols.scope.get("Tuple").map(_ -> ts)
      case Type.Layer(d, args, _) => Some(d -> args)
      case _                      => None

  /** A variant's field types for data arguments `args`; inside a layer, recursive fields hold the seed. */
  protected def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type] =
    val sigma = symbols.typeParamsOf(data).zip(args).toMap
    symbols.fieldsOf(variant).map(f => concrete(f.info, sigma)).toList

  private def runtime(name: String, args: List[Atom], t: Tree, sc: Scope)(k: K): Expr =
    bind(Rhs.Call(name, args, runtime = true), t, sc)(k)

  private def all(ts: List[Tree], sc: Scope)(k: List[Atom] => Expr): Expr =
    ts match
      case Nil       => k(Nil)
      case t :: rest => expr(t, sc)(a => all(rest, sc)(as => k(a :: as)))

  /** Bind `rhs` to a fresh variable of `t`'s type and continue with it. */
  private def bind(rhs: Rhs, t: Tree, sc: Scope)(k: K): Expr =
    val tt = typeOf(t, sc)
    val x = typed(fresh("t", ctype(tt, t.span)), tt, t.span)
    Expr.Let(x, rhs, k(x))

  /** Record `x`'s typer type and span; returns `x`. */
  protected def typed(x: Var, t: Type, span: Span): Var =
    types(x.name) = t
    spans(x.name) = span
    x

  /** The type of slot `slot` of a value of type `t` (fields in declaration order, `Layout.slots`),
    * when every variant that has the slot agrees on it.
    */
  def slotType(t: Type, slot: Int): Option[Type] =
    dataOf(t).flatMap { (data, args) =>
      val candidates = symbols.variantsOf(data).toList.flatMap(v => fieldTypes(data, args, v.id).lift(slot)).distinct
      candidates match
        case List(only) => Some(only)
        case _          => None
    }

  /** The number of payload slots of variant `tag` of a value of type `t`. */
  def cellSize(t: Type, tag: Int): Option[Int] =
    dataOf(t).flatMap((data, args) =>
      symbols.variantsOf(data).toList.lift(tag).map(v => fieldTypes(data, args, v.id).size)
    )

  protected def fresh(base: String, t: CType): Var = Var(s"$base.${ids.next()}", t)

  private def typeOf(t: Tree, sc: Scope): Type = concrete(unit.typeOf(t).getOrElse(Type.NeverType), sc.sigma, sc.bounds)

  /** `t` at the instance's concrete types, with capability bounds replaced by their representation. */
  private def concrete(t: Type, sigma: Sigma, bounds: Map[Type, Type] = Map.empty): Type =
    represent(Lower.subst(Lower.replaceAll(t, bounds), sigma))

  private def isFn(name: String): Boolean = symbols.scope.get(name).exists(symbols(_).kind == SymKind.Fn)

  private def isStringType(t: Type): Boolean =
    t match
      case Type.TypeRef(id, Nil)   => symbols(id).name == "String" && symbols(id).owner == SymbolId.Prelude
      case Type.PrimType("String") => true
      case _                       => false

  protected def ctype(t: Type, span: Span): CType =
    Lower.scalar(t).getOrElse(t match
      case _ if isStringType(t) || dataOf(t).isDefined             => CType.Boxed
      case _: Type.ArrowType                                       => CType.Boxed
      case Type.CapRef(c, _) if represent.constructor(c).isDefined => CType.Boxed
      case _                                                       =>
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

  /** `t` with each type that is a key of `by` replaced by its value. */
  def replaceAll(t: Type, by: Map[Type, Type]): Type =
    if by.isEmpty then t else by.getOrElse(t, t.mapChildren(replaceAll(_, by)))

  /** Whether `t` mentions data type `data` anywhere. */
  def mentions(t: Type, data: SymbolId): Boolean = isSelf(t, data) || t.children.exists(mentions(_, data))

  /** `t` with every occurrence of data type `data` replaced by `by`. */
  def replace(t: Type, data: SymbolId, by: Type): Type =
    if isSelf(t, data) then by else t.mapChildren(replace(_, data, by))

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
