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
  * each function it reaches. A polymorphic function is lowered once under its own name where it
  * can be (Rule CM3.1, [[baselineOf]]): it takes a witness and the tables of the bound set per type
  * parameter, and a value of a type parameter is a slot named by its witness. Derived members
  * (`fold`, `unfold`, `Folding.fold`) are generated as Core functions ([[Generated.member]]). A call
  * whose type arguments are all known requests the clone for them ([[specialised]], Rule CM5.4):
  * it calls that clone, the function lowered again at those types (`sc.sigma` maps its type
  * parameters to them), if the key is in `chosen` ([[Specialise]]), and else the baseline version
  * with static witnesses and tables (Rule CM5.6). A function whose baseline version cannot be
  * built is lowered per instance. Constructs beyond the current milestone are `B005`, and so is a
  * cell with a reference beyond its header's reference map (`refMapLimit`).
  */
final class Lower(private[backend] val unit: CompilationUnit, chosen: Set[(String, String)] = Set.empty)
    extends Cases, Immediates:
  private type Env = Map[String, Var]
  private type Sigma = Map[SymbolId, Type]

  /** Lexical bindings and the type substitution of the instance being lowered. */
  final private[backend] case class Scope(env: Env, sigma: Sigma, bounds: Map[Type, Type]):
    def bind(name: String, v: Var): Scope = copy(env = env.updated(name, v))

  private val ids = Iterator.from(1)
  private val problems = mutable.ArrayBuffer.empty[Diagnostic]
  private[backend] val symbols: SymbolTable = unit.symbols
  private val fns = mutable.LinkedHashMap.empty[String, Fn]
  private val represent = Representation(unit)

  /** Concrete typer type of each Core variable lowering could type (by variable name), and the
    * source span of each variable and function. Read by `Demands` (3.4.M2).
    */
  val types: mutable.Map[String, Type] = mutable.HashMap.empty
  val spans: mutable.Map[String, Span] = mutable.HashMap.empty

  /** The function instances declared `fip fn`, with their contract (3.4.M5). */
  val fips: mutable.Map[String, Trees.Fip] = mutable.HashMap.empty

  /** Top-level functions with bodies, the prelude's and the unit's, by the name of their symbol. */
  private val decls: Map[String, (SymbolId, Trees.FnDecl)] =
    fixed.typer.Stdlib.functions(unit.tree, symbols).map((id, f) => symbols(id).name -> (id, f))

  /** The baseline versions built: functions and generated members compiled once for all
    * instantiations of their type parameters (Rule CM3.1).
    */
  val baselines: mutable.Set[String] = mutable.LinkedHashSet.empty

  /** The polymorphic functions and generated members lowered per instance, each with the reason
    * its baseline version could not be built.
    */
  val instances: mutable.Map[String, String] = mutable.LinkedHashMap.empty

  /** Whether each polymorphic function tried has a baseline version, or why not. */
  private val tried = mutable.Map.empty[String, Either[String, List[(Type, Type)]]]

  /** The anonymous bound each implicit type parameter stands for ([[baselineOf]]). */
  private[backend] val implicitBounds: mutable.Map[Type, Type] = mutable.HashMap.empty

  /** The type parameters, each with its key ([[baselineOf]]), of each baseline version being built:
    * what a recursive call passes; a finished version drops its phantom ones unless one reached it ([[recursed]]).
    */
  private val building = mutable.Map.empty[String, List[(Type, Type)]]
  private val recursed = mutable.Set.empty[String]

  /** The entries of its tables each type parameter's code calls, directly or by passing the table on. */
  private val calledEntries = mutable.Map.empty[Type, Set[String]].withDefaultValue(Set.empty)

  /** The call sites that request a clone (Rule CM5.4), in the order lowered; and each clone built,
    * with the baseline version it specialises and its key.
    */
  val requests: mutable.ListBuffer[Request] = mutable.ListBuffer.empty
  val typeClones: mutable.Map[String, (String, String)] = mutable.LinkedHashMap.empty

  /** The function whose code is being lowered, which owns the requests its call sites make. */
  private val owner = scala.util.DynamicVariable("")

  /** Each type parameter of the clones and instances being lowered, as what it derives from ([[TypeOps.derived]]). */
  private val frame = scala.util.DynamicVariable(Map.empty[SymbolId, Type])

  /** Every function instance built so far, including copies and table entries requested after [[program]]. */
  def functions: List[Fn] = fns.values.toList

  private[backend] def functionNamed(name: String): Fn = fns(name)

  /** A function copying a value of type `t`: cells at part keys `parts` copied, the rest shared. */
  def copy(t: Type, parts: Set[(String, Int)]): Option[String] =
    cellData(t).map((d, args) => copyOf(d, args, parts))

  /** Whether `t` mentions no type parameter: the part keys of its values are the same everywhere. */
  def known(t: Type): Boolean = typeParamsIn(t).isEmpty

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

  /** Build function `name` once. A request made while `build` runs meets a placeholder with no
    * parameters, which [[code]] replaces with the function's signature before it lowers a body.
    */
  private[backend] def instance(name: String)(build: => Fn): String =
    if !fns.contains(name) then
      fns(name) = Fn(name, Nil, CType.Unit, Expr.Ret(Atom.UnitC))
      fns(name) = owner.withValue(name)(build)
    name

  /** `body`, or why it failed: the first problem it reported. On failure the functions it built, the
    * problems it reported and what it recorded about baseline versions are discarded, so that the
    * caller can lower the same code another way. (Implicit type parameters an abandoned attempt
    * made stay in `implicitBounds`, unused.)
    */
  private[backend] def attempt[A](body: => A): Either[String, A] =
    val (before, count, seen) = (fns.keySet.toSet, problems.size, tried.keySet.toSet)
    val (built, instead, entries) = (baselines.toSet, instances.keySet.toSet, calledEntries.toMap)
    val (asked, cloned) = (requests.size, typeClones.keySet.toSet)
    val result = body
    if problems.size == count then Right(result)
    else
      val why = problems(count).message
      fns.keySet.toList.filterNot(before).foreach(fns.remove)
      problems.remove(count, problems.size - count)
      tried.keySet.toList.filterNot(seen).foreach(tried.remove)
      baselines.filterInPlace(built)
      instances.keySet.toList.filterNot(instead).foreach(instances.remove)
      calledEntries.clear()
      calledEntries ++= entries
      requests.dropRightInPlace(requests.size - asked)
      typeClones.filterInPlace((c, _) => cloned(c))
      Left(why)

  /** Instance `name` of `decl`: `sigma` for its type parameters, `bounds` for its anonymously bound
    * parameter types (each replaced by the argument type at the call, or in a baseline version by
    * its implicit type parameter), and the witnesses and tables `hidden` before the declared
    * parameters.
    */
  private def function(
      fn: (SymbolId, Trees.FnDecl),
      sigma: Sigma,
      bounds: Map[Type, Type],
      name: String,
      hidden: List[Var] = Nil
  ): Fn =
    val (id, decl) = fn
    val (paramTypes, ret) = TypeOps.signature(symbols(id).info)
    decl.fip.foreach(fips(name) = _)
    inPrelude.withValue(symbols(id).owner == SymbolId.Prelude)(
      code(name, decl.params.map(p => (p.name, p.span)).zip(paramTypes), ret, decl, sigma, bounds, hidden)
    )

  /** Function `name` over `body`: `hidden`, then `params` at their types made concrete. Run inside
    * `instance(name)`, it records the signature in `fns` before lowering the body, which may need a table
    * with an entry over this very function (`Tables.adapter` reads the parameters and result).
    */
  private[backend] def code(
      name: String,
      params: List[((String, Span), Type)],
      ret: Type,
      body: Trees.FnLike,
      sigma: Sigma,
      bounds: Map[Type, Type],
      hidden: List[Var]
  ): Fn =
    val vars = params.map { case ((p, span), t) =>
      val at = concrete(t, sigma, bounds)
      typed(fresh(p, ctype(at, span)), at, span)
    }
    spans(name) = body.span
    val sc = Scope(params.map(_._1._1).zip(vars).toMap, sigma, bounds)
    val rt = ctype(concrete(ret, sigma, bounds), body.span)
    fns(name) = Fn(name, hidden ++ vars, rt, Expr.Ret(Atom.UnitC))
    Fn(name, hidden ++ vars, rt, body.body.fold(Expr.Ret(Atom.UnitC))(tail(_, sc)))

  private[backend] def expr(t: Tree, sc: Scope)(k: K): Expr =
    t match
      case l: Trees.IntLit   => intLit(l.value, l, sc)(k)
      case l: Trees.FloatLit =>
        ctype(typeOf(l, sc), l.span) match
          case c: CType.Float => k(Atom.FloatC(l.value, c))
          case _ => unsupported(l.span, "a float literal at a type other than `f32` or `f64` (no `fromF64`)", k)
      case Trees.BoolLit(v, _)       => k(Atom.BoolC(v))
      case Trees.CharLit(c, _)       => k(Atom.IntC(BigInt(c.toInt), CType.Int(32, signed = false)))
      case Trees.StringLit(s, _)     => k(Atom.StrC(s))
      case Trees.UnitLit(_)          => k(Atom.UnitC)
      case Trees.Ident(name, span)   => sc.env.get(name).fold(unsupported(span, s"`$name` as a value", k))(k)
      case Trees.TupleExpr(elems, _) => all(elems, sc)(as => bind(Rhs.Con(0, as), t, sc)(k))
      case Trees.Block(stmts, _)     => block(stmts, sc)(k)
      case b: Trees.BinOp            => binOp(b, sc)(k)
      case Trees.UnaryOp(Trees.UnaryOperator.Neg, l: Trees.IntLit, _) if l.value != 0 => intLit(-l.value, l, sc)(k)
      case u: Trees.UnaryOp                                                           => unaryOp(u, sc)(k)
      case i: Trees.IfExpr                                                            =>
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

  private[backend] def tail(t: Tree, sc: Scope): Expr = expr(t, sc)(Expr.Ret(_))

  /** `fn(args)`, typed as `result`: a top-level function instance, or a closure call. */
  private def application(fn: Tree, args: List[Tree], result: Tree, sc: Scope)(k: K): Expr =
    fn match
      case f @ Trees.Ident(name, span) if !sc.env.contains(name) && fnNamed(name).isDefined =>
        fnNamed(name).map(symbols(_).name).filter(decls.contains) match
          case Some(key) => call(key, f, args, result, sc)(k)
          case None      => unsupported(span, s"call of `$name`, which has no body", k)
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
  private[backend] def bindPattern(pat: Tree, a: Atom, t: Type, sc: Scope)(k: Scope => Expr): Expr =
    pat match
      case Trees.BinderPat(name, _) =>
        a match
          case v: Var => k(sc.bind(name, v))
          case c      =>
            val x = fresh(name, c.tpe)
            Expr.Let(x, Rhs.Move(c), k(sc.bind(name, x)))
      case Trees.WildcardPat(_)     => k(sc)
      case Trees.TuplePat(elems, _) =>
        /** `(A, B)` or `Tuple of (A, B)`; the typer has checked the arity. */
        val types = dataOf(t).fold(Nil)(_._2)
        fields(a, elems.zip(types), Layout.fieldsOf(ctype(t, pat.span), types.map(ctype(_, pat.span))), sc)(k)
      case other => unsupportedThen(other.span, s"pattern ${other.getClass.getSimpleName}", k(sc))

  /** Load the fields `pats` bind from `obj`, at `slots`. */
  private[backend] def fields(
      obj: Atom,
      pats: List[(Tree, Type)],
      slots: List[Int],
      sc: Scope
  )(k: Scope => Expr): Expr =
    pats.zip(slots) match
      case Nil                     => k(sc)
      case ((p, ft), slot) :: rest =>
        p match
          case Trees.WildcardPat(_) => fields(obj, rest.map(_._1), rest.map(_._2), sc)(k)
          case _                    =>
            loadField(obj, slot, ft, p)(bindPattern(p, _, ft, sc)(fields(obj, rest.map(_._1), rest.map(_._2), _)(k)))

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
              case (_, _: CType.Slot)                     => slotOp(op, l, r, typeOf(b.lhs, sc), b, sc)(k)
              case (Eq | Neq, CType.Boxed | _: CType.Imm) =>
                val (v, t) = (fresh("eq", CType.Bool), typeOf(b.lhs, sc))
                def result = if op == Eq then k(v) else bind(Rhs.Prim(PrimOp.Not, List(v)), b, sc)(k)
                equality(t, l, r).fold(unsupported(b.span, s"`${op.symbol}` on ${symbols.render(t)}", k))(
                  Expr.Let(v, _, result)
                )
              case (Lt | Le | Gt | Ge, CType.Boxed) if isStringType(typeOf(b.lhs, sc)) =>
                stringOrder(l, r)(c => bind(Rhs.Prim(PrimOp.of(op), List(c, zeroOrder)), b, sc)(k))
              case (Lt | Le | Gt | Ge, CType.Boxed | _: CType.Imm) =>
                ordered(typeOf(b.lhs, sc), op, l, r)
                  .fold(unsupported(b.span, s"`${op.symbol}` on boxed values", k))(bind(_, b, sc)(k))
              case (_, CType.Boxed | _: CType.Imm) => unsupported(b.span, s"`${op.symbol}` on data values", k)
              case _                               => bind(Rhs.Prim(PrimOp.of(op), List(l, r)), b, sc)(k)
          )
        )

  /** `l op r` on values of type parameter `t`, through the tables of its bound set (Rule CM4.1):
    * `>` and `>=` are `lt` and `le` with the operands swapped (plan §2), `!=` is `eq` negated.
    */
  private def slotOp(op: Trees.BinOperator, l: Atom, r: Atom, t: Type, b: Tree, sc: Scope)(k: K): Expr =
    import Trees.BinOperator.*
    val call =
      (cap: String, member: String, args: List[Atom]) => tableCall(t, symbols.prelude(cap), member, args, b, sc)
    op match
      case Add      => call("Numeric", "add", List(l, r))(k)
      case Sub      => call("Numeric", "sub", List(l, r))(k)
      case Mul      => call("Numeric", "mul", List(l, r))(k)
      case Div      => call("Numeric", "div", List(l, r))(k)
      case Mod      => call("Numeric", "rem", List(l, r))(k)
      case Eq       => call("Eq", "eq", List(l, r))(k)
      case Neq      => call("Eq", "eq", List(l, r))(e => bind(Rhs.Prim(PrimOp.Not, List(e)), b, sc)(k))
      case Lt       => call("Ord", "lt", List(l, r))(k)
      case Le       => call("Ord", "le", List(l, r))(k)
      case Gt       => call("Ord", "lt", List(r, l))(k)
      case Ge       => call("Ord", "le", List(r, l))(k)
      case And | Or => unsupported(b.span, s"`${op.symbol}` on a value of parameter type", k)

  /** Member `member` of capability `cap`, called with `args` (the receiver first, Rule CM4.6)
    * through the table of type parameter `t`'s bound set that holds `cap`'s members; the result is
    * bound as the value of `tree`. A table that holds another capability's member under this name
    * (Optional's `fold` shadows Folding's) does not serve the call.
    */
  private def tableCall(t: Type, cap: SymbolId, member: String, args: List[Atom], tree: Tree, sc: Scope)(k: K): Expr =
    val found =
      if isTypeParam(t) then
        boundCaps(t).find(c => Tables.reached(symbols, c).contains(cap) && declared(c, member).forall(_.owner == cap))
          .map(c => (tableOf(t, c), Tables.members(symbols, c).indexOf(member)))
      else None
    found match
      case Some((table, i)) if i >= 0 =>
        calledEntries(t) += member
        bind(Rhs.CallTable(table, i, args), tree, sc)(k)
      case _ =>
        val what = s"`${symbols(cap).name}.$member` on a value of type ${symbols.render(t)}"
        unsupported(tree.span, s"$what without a bound whose table holds it", k)

  /** Integer literal `l` lowered with value `v`, which includes a leading `-` (but not on `0`, whose
    * negation is a float's `-0.0`), so that at a type parameter `-9223372036854775808` reaches
    * `fromI64` as the most negative `i64` instead of wrapping before it is negated.
    */
  private def intLit(v: BigInt, l: Trees.IntLit, sc: Scope)(k: K): Expr =
    val t = typeOf(l, sc)
    ctype(t, l.span) match
      case f: CType.Float => k(Atom.FloatC(BigDecimal(v), f))
      case _: CType.Slot  =>
        tableCall(t, symbols.prelude("Numeric"), "fromI64", List(Atom.IntC(v, CType.Int(64, signed = true))), l, sc)(k)
      case c => k(Atom.IntC(v, c))

  private def unaryOp(u: Trees.UnaryOp, sc: Scope)(k: K): Expr =
    val op = u.op match
      case Trees.UnaryOperator.Neg => PrimOp.Neg
      case Trees.UnaryOperator.Not => PrimOp.Not
    expr(u.operand, sc)(a =>
      if a.tpe.slot && op == PrimOp.Neg then
        tableCall(typeOf(u.operand, sc), symbols.prelude("Numeric"), "negate", List(a), u, sc)(k)
      else bind(Rhs.Prim(op, List(a)), u, sc)(k)
    )

  /** A call of function `ref` at its type, or of the baseline under polymorphic recursion ([[TypeOps.derived]]). */
  private def call(name: String, ref: Tree, args: List[Tree], t: Tree, sc: Scope)(k: K): Expr =
    val id = decls(name)._1
    val params = symbols.typeParamsOf(id)
    val solved = TypeOps.solve(params.toSet, symbols(id).info, typeOf(ref, sc), Map.empty, Members.boundsOf(symbols, _))
    val sigma = params.map(p => p -> solved.getOrElse(p, Type.UnitType)).toMap
    val (declared, ret) = TypeOps.signature(symbols(id).info)
    val argTypes = args.map(typeOf(_, sc))
    val bounds = declared.zip(argTypes).collect {
      case (d, actual: Type.CapRef) if TypeOps.anonymous(d)                => d -> actual
      case (d, actual) if TypeOps.anonymous(d) && dataOf(actual).isDefined => d -> actual
    }.toMap
    val typeArgs = params.map(sigma) ++ declared.flatMap(bounds.get)
    if building.contains(name) then recursed += name
    val version = baselineOf(name, params, declared, ret).map(_.flatMap { typeParams =>
      /** A declared type parameter is keyed by itself; an implicit one by the bound it stands for. */
      val actuals = typeParams.map { (key, p) =>
        if key == p then p -> List(TypeOps.subst(p, sigma))
        else
          val at = declared.zip(argTypes).collect { case (`key`, a) => a } ++ Option.when(key == ret)(typeOf(t, sc))
          p -> at.distinct
      }
      actuals.collectFirst { case (p, as) if as.size > 1 => p } match
        case Some(p) => Left(s"positions bounded by `${symbols.render(implicitBounds(p))}` at different types")
        case None    =>
          val pairs = actuals.map((p, as) => p -> as.head)
          checkEntries(pairs).flatMap(_ => hiddenArgs(pairs)).map(ws => (typeParams.map(_._1).zip(pairs.map(_._2)), ws))
    })
    val (inner, deeper) =
      TypeOps.derived(params, symbols(id).info, unit.typeOf(ref), frame.value, typeParamsIn(_).nonEmpty)
    version match
      case Some(Left(why)) if typeArgs.exists(typeParamsIn(_).nonEmpty) =>
        unsupported(t.span, s"a call of `$name` at a type parameter, which has no baseline version ($why)", k)
      case Some(Left(why)) if deeper =>
        unsupported(t.span, s"polymorphic recursion of `$name`, which has no baseline version ($why)", k)
      case _ =>
        /** The callee, its hidden arguments, and the bounds to build it at if it is an instance. */
        val (callee, ws, at) = version match
          case Some(Right((keyed, ws))) =>
            (if deeper then None else specialised(name, keyed.map(_._2))) match
              case Some(key) =>
                /** The clone: this function lowered again at the key's types (Rule CM5.1). */
                typeClones(s"$name[$key]") = (name, key)
                (s"$name[$key]", Nil, Some(bounds ++ keyed.filter((d, _) => TypeOps.anonymous(d))))
              case None => (name, ws, None)
          case other =>
            val anonymousArgs = declared.zip(argTypes).collect { case (d, a) if TypeOps.anonymous(d) => a }
            val shown = if typeArgs.isEmpty && other.isDefined then anonymousArgs else typeArgs
            val inst = if shown.isEmpty then name else shown.map(symbols.render(_)).mkString(s"$name[", ",", "]")
            other.foreach(_.left.foreach(instances(inst) = _))
            (inst, Nil, Some(bounds))
        at.foreach(b => frame.withValue(inner)(instance(callee)(function(decls(name), sigma, b, callee))))
        all(args, sc)(as => bind(Rhs.Call(callee, ws ++ as, runtime = false), t, sc)(k))

  /** That no table among the hidden arguments `pairs` has a null entry ([[Tables.unbuilt]])
    * where the callee's code calls one, or why: such a call then goes to an instance.
    */
  private def checkEntries(pairs: List[(Type, Type)]): Either[String, Unit] =
    pairs.foreach((p, t) => if isTypeParam(t) then calledEntries(t) ++= calledEntries(p))
    val missing =
      for
        (p, t) <- pairs if !isTypeParam(t)
        cap <- boundCaps(p)
        table <- layoutOf(t, cap).toList
        (m, e) <- Tables.members(symbols, cap).zip(table.entries) if e._1.isEmpty && calledEntries(p)(m)
      yield s"`${symbols.render(t)}` has no `${symbols(cap).name}.$m` the backend builds"
    missing.headOption.toLeft(())

  /** Whether a call site of the code being lowered, which asks for baseline version `of` at the
    * type arguments `types`, calls the clone for them: if they are all known, the site requests
    * the clone (Rule CM5.4), and it calls it if the key is chosen. The key is the types' text.
    */
  private[backend] def specialised(of: String, types: List[Type]): Option[String] =
    Option.when(types.nonEmpty && types.forall(typeParamsIn(_).isEmpty))(types.map(symbols.render(_)).mkString(","))
      .flatMap { key =>
        requests += Request(owner.value, of, key)
        Option.when(chosen((of, key)))(key)
      }

  /** For a polymorphic function `name` with type parameters `params`, declared parameter types
    * `declared` and result type `ret`: its baseline version (Rule CM3.1), built once under its own
    * name, or why it has none; `None` for a monomorphic function. With the version come its type
    * parameters that are not phantom, each keyed by itself, or, for an implicit one, by the
    * anonymous bound it stands for.
    *
    * An implicit type parameter stands for each distinct anonymous bound among `declared` and
    * `ret` (`is Folding of String`): a reserved type variable, substituted for the bound as the
    * instance path substitutes the argument type. One for the result makes the result chosen by
    * the caller (Rule CM4.5): a constructor requirement of its bound becomes a call through the
    * table the caller passes. That holds only if every value the function returns has the
    * result's slot form; where one does not (`Optional.none`, which the typer types apart), the
    * version is built again with the result chosen by `Representation`, as in the callee.
    *
    * The version takes a witness per type parameter, each followed by a table per capability of
    * its bound set, the implicit ones after the declared ones ([[Tables.hiddenOf]], Rule CM4.3),
    * except for a phantom type parameter, whose witness and tables the code never uses; a
    * recursive call, made while the version is being built, passes them all ([[building]]). A call
    * whose type arguments have no full table of a bound (an entry the backend does not build) calls
    * an instance.
    */
  private def baselineOf(
      name: String,
      params: List[SymbolId],
      declared: List[Type],
      ret: Type
  ): Option[Either[String, List[(Type, Type)]]] =
    val resultImplicit = TypeOps.anonymous(ret) && !declared.contains(ret)
    def build(callerChosen: Boolean): Either[String, List[(Type, Type)]] =
      val anonymous = (declared ++ Option.when(callerChosen)(ret)).filter(TypeOps.anonymous).distinct
      val implicits = anonymous.map { d =>
        val p = Type.TypeVar(-2 - implicitBounds.size)
        implicitBounds(p) = d
        d -> p
      }
      val all = params.map(p => Type.TypeRef(p, Nil)).map(p => p -> p) ++ implicits
      val hidden = all.flatMap((_, p) => hiddenOf(p))
      val before = fns.keySet.toSet
      building(name) = all
      val built = attempt {
        val _ = instance(name)(function(decls(name), Map.empty, implicits.toMap, name, hidden))
        if callerChosen then
          fns.values.filterNot(f => before(f.name)).find(f => !Core.returnsAs(f.body, f.ret)).foreach { f =>
            report(s"`${f.name}` returns a value whose slot form differs from its result's")
          }
      }
      val _ = building.remove(name)
      built.map { _ =>
        baselines += name
        val f = fns(name)
        val used = Core.freeVars(f.body) ++ (f.params ++ Core.lets(f.body).map(_._1)).flatMap(Core.witnessOf) ++
          Core.witnessOf(Atom.Var("result", f.ret))
        val kept = if recursed(name) then all else all.filter((_, p) => hiddenOf(p).exists(used))
        fns(name) = f.copy(params = kept.flatMap((_, p) => hiddenOf(p)) ++ f.params.drop(hidden.size))
        kept
      }
    val polymorphic = params.nonEmpty || declared.exists(TypeOps.anonymous)
    def version = if resultImplicit then build(true).orElse(build(false)) else build(false)
    Option.when(polymorphic)(building.get(name).fold(tried.getOrElseUpdate(name, version))(Right(_)))

  /** A lambda becomes a code function taking the closure first; its captures ([[closure]]) are
    * loaded from the closure on entry.
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
    * ([[captures]]) are loaded from the closure on entry.
    */
  private[backend] def closure(params: List[Var], body: => Expr, ret: CType)(k: Var => Expr): Expr =
    val code = s"lambda.${ids.next()}"
    val lowered = owner.withValue(code)(body)
    val captures = this.captures(lowered, params)
    val env = fresh("env", CType.Boxed)
    val slots = Layout.slots(captures.map(_.ctype), first = Layout.closureCode)
    val loaded = captures.zip(slots).foldRight(lowered)((cs, acc) => Expr.Let(cs._1, Rhs.Field(env, cs._2), acc))
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

    /** Whether the receiver is a scalar, a `String` or a data value, whose `show` and `compare` the backend builds. */
    lazy val known = Scalars.scalar(recvType).isDefined || isStringType(recvType) || dataOf(recvType).isDefined
    resolution match
      case Some(Resolution.Field(variant, index)) =>
        dataOf(recvType) match
          case Some((data, dargs)) =>
            val at = Layout.fieldsOf(ctype(recvType, m.span), fieldTypes(data, dargs, variant).map(ctype(_, m.span)))
            expr(m.receiver, sc)(r => bind(Rhs.Field(r, at(index)), m, sc)(k))
          case None => unsupported(m.span, s"field `${m.method}` of ${symbols.render(recvType)}", k)
      case Some(Resolution.DerivedFold(_, para)) =>
        generatedCall(recvType, m, sc, Some(typeOf(m, sc)))(derivedFold(_, _, _, m.span, para))(k)
      case Some(Resolution.CapMember(_, member)) if isTypeParam(recvType) =>
        tableMethod(m, member, recvType, args, sc)(k)
      case Some(Resolution.CapMember(_, member)) if hasBody(recvType, member, fromPrelude = false) =>
        bodyCall(m, member, recvType, sc)(k)
      case Some(Resolution.CapMember(cap, member)) =>
        (symbols.preludeName(cap), m.method, args) match
          case ("Concat", "concat", List(arg)) =>
            expr(m.receiver, sc)(r => expr(arg, sc)(a => runtime("fx_str_concat", List(r, a), m, sc)(k)))
          case ("Show", "show", Nil) if known         => expr(m.receiver, sc)(r => showValue(recvType, r)(k))
          case ("Clone", "clone", Nil) if known       => expr(m.receiver, sc)(k)
          case ("Ord", "compare", List(arg)) if known =>
            expr(m.receiver, sc)(r => expr(arg, sc)(a => compareValues(recvType, r, a)(k)))
          case ("Eq", "eq", List(arg)) if dataOf(recvType).isDefined || isStringType(recvType) =>
            expr(m.receiver, sc)(r =>
              expr(arg, sc)(a =>
                equality(
                  recvType,
                  r,
                  a
                ).fold(unsupported(m.span, s"`eq` on ${symbols.render(recvType)}", k))(bind(_, m, sc)(k))
              )
            )
          case ("Numeric", "negate", Nil) if Scalars.scalar(recvType).isDefined =>
            expr(m.receiver, sc)(r => bind(Rhs.Prim(PrimOp.Neg, List(r)), m, sc)(k))
          case (c, name, List(arg)) if Scalars.scalar(recvType).isDefined && Tables.scalarBinary.contains((c, name)) =>
            val op = Tables.scalarBinary((c, name))
            expr(m.receiver, sc)(r => expr(arg, sc)(a => bind(Rhs.Prim(op, List(r, a)), m, sc)(k)))
          case ("Folding", "fold", List(_, _)) if listLike =>
            generatedCall(recvType, m, sc, Some(typeOf(m, sc)))(foldLeft(_, _, _, m.span))(k)
          case (_, "fold", _) if mappedFold(cap, recvType, args.size).isDefined =>
            val order = mappedFold(cap, recvType, args.size)
            generatedCall(recvType, m, sc, Some(typeOf(m, sc)), order)(derivedFold(_, _, _, m.span))(k)
          case ("Functor", "map", List(_)) if dataOf(recvType).isDefined =>
            val newElem = dataOf(typeOf(m, sc)).flatMap(_._2.lastOption).getOrElse(Type.UnitType)
            generatedCall(recvType, m, sc, Some(newElem))(derivedMap(_, _, _, m.span))(k)
          case ("Sequencing", "length", Nil) if listLike =>
            generatedCall(recvType, m, sc)((d, a, _) => length(d, a, typeOf(m, sc), m.span))(k)
          case ("Folding", "find", List(_)) if listLike =>
            capOf(m) match
              case Some(opt) =>
                val dargs = dataOf(recvType).fold(Nil)(_._2)
                generatedCall(recvType, m, sc)((d, a, _) =>
                  find(d, a, opt, TypeOps.replaceAll(typeOf(m, sc), dargs.zip(a).toMap), m.span)
                )(k)
              case None =>
                unsupported(m.span, s"`find` returning ${symbols.render(unit.typeOf(m).getOrElse(Type.NeverType))}", k)
          case (_, name, Nil) if productField(recvType, name).isDefined => fieldRead(recvType, m, sc)(k)
          case _ if hasBody(recvType, member, fromPrelude = true)       => bodyCall(m, member, recvType, sc)(k)
          case (c, name, _)                                             =>
            unsupported(m.span, s"member $c.$name on ${symbols.render(recvType)} (arrives in 4.0.M4)", k)
      case None | Some(_: Resolution.NestedFn) => unsupported(m.span, s"member `${m.method}`", k)

  /** `m`, resolved to capability member `member`, on a receiver of type parameter `t`, through the
    * table of its bound set that holds the member: the witness of each of the member's own type
    * parameters, solved from the argument and result types, then the receiver and the arguments
    * (Rule CM4.3). A member type parameter the types do not solve is `B005`.
    */
  private def tableMethod(m: Trees.MethodCall, member: SymbolId, t: Type, args: List[Tree], sc: Scope)(k: K): Expr =
    val d = symbols(member)
    val own = symbols.typeParamsOf(member)
    val solved = ownTypeArgs(member, args.map(typeOf(_, sc)), typeOf(m, sc))
    own.find(!solved.contains(_)) match
      case Some(q) => unsupported(m.span, s"`${m.method}` whose `${symbols(q).name}` the call does not determine", k)
      case None    =>
        val witnesses = own.map(solved andThen witnessAtom)
        expr(m.receiver, sc)(r =>
          all(args, sc)(as => tableCall(t, d.owner, m.method, witnesses ++ (r :: as), m, sc)(k))
        )

  /** `m`, a call of capability `member` on `recvType`, through its `satisfies` body. */
  private def bodyCall(m: Trees.MethodCall, member: SymbolId, recvType: Type, sc: Scope)(k: K): Expr =
    val (argTypes, args) = (m.args.getOrElse(Nil).map(typeOf(_, sc)), m.args.getOrElse(Nil))
    directBody(recvType, member, argTypes, typeOf(m, sc)) match
      case Some((f, ws)) =>
        expr(m.receiver, sc)(rv => all(args, sc)(as => bind(Rhs.Call(f, ws ++ (rv :: as), runtime = false), m, sc)(k)))
      case None =>
        generatedCall(recvType, m, sc, ownTypeArg(member, argTypes, typeOf(m, sc)))(dataBody(member))(k)

  private def fieldRead(recvType: Type, m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    expr(m.receiver, sc)(r => bind(Rhs.Field(r, productField(recvType, m.method).get), m, sc)(k))

  /** The slot of field `name` of an auto-flat-struct or single-variant data value. */
  private def productField(t: Type, name: String): Option[Int] =
    t match
      case Type.CapRef(_, _) => productFields(t).map(_._2.indexWhere(_._1 == name)).filter(_ >= 0)
      case _                 =>
        dataOf(t).flatMap { (data, args) =>
          symbols.variantsOf(data).toList match
            case List(v) =>
              val names = symbols.fieldsOf(v.id).map(_.name).toList
              val types = fieldTypes(data, args, v.id)
              val at = Layout.fieldsOf(ctype(t, Span.NoSpan), types.map(ctype(_, Span.NoSpan)))
              Option(names.indexOf(name)).filter(_ >= 0).map(at)
            case _ => None
        }

  /** `builtin.parseU64(s)`: the C side parses; the result is built with the constructors that
    * `ok` and `err` of the result's capability (a `Result`) map to on the representing data type.
    */
  private def intrinsic(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val result = typeOf(m, sc)
    (m.method, m.args.getOrElse(Nil), dataOf(result), capOf(m)) match
      case ("parseU64", List(arg), Some(_), Some(cap)) =>
        val tagOf = (ctor: String) => capVariant(cap, ctor, result).getOrElse(0)
        expr(arg, sc) { a =>
          val (v, ok) = (fresh("n", CType.Int(64, signed = false)), fresh("ok", CType.Bool))
          val (yes, no) = (fresh("yes", ctype(result, m.span)), fresh("no", ctype(result, m.span)))
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

  /** Call the member `gen` generates for the receiver's data type ([[member]]), whose own type
    * argument is `r` if it has one: its witnesses, the receiver, then the arguments, in the order
    * `order` gives (the argument handling each variant) if any.
    */
  private def generatedCall(
      recvType: Type,
      m: Trees.MethodCall,
      sc: Scope,
      r: Option[Type] = None,
      order: Option[List[Int]] = None
  )(gen: (SymbolId, List[Type], Type) => String)(k: K): Expr =
    dataOf(recvType) match
      case Some((data, dargs)) =>
        val (f, ws) = member(data, dargs, r)(gen(data, _, _))
        expr(m.receiver, sc)(rv =>
          all(m.args.getOrElse(Nil), sc)(as =>
            bind(Rhs.Call(f, ws ++ (rv :: order.fold(as)(_.map(as(_)))), runtime = false), m, sc)(k)
          )
        )
      case None => unsupported(m.span, s"`${m.method}` on ${symbols.render(recvType)}", k)

  /** Data constructors, derived `unfold`, capability constructors resolved by the typer (through the
    * table of a type parameter when the value is one, Rule CM4.5), and `std.io`'s `Console`.
    */
  private def static(s: Trees.StaticCall, sc: Scope)(k: K): Expr =
    val args = s.args.getOrElse(Nil)
    lazy val result = typeOf(s, sc)
    val owner = globals.get(s.qualifier)
    val resolved = unit.typing.flatMap(_.constructors.find(_._1 eq s)).map((_, d, v) => (d, v))
    (owner.exists(symbols.stdIo("Console").contains), s.method, args) match
      case (true, "printLine", List(arg)) => expr(arg, sc)(a => runtime("fx_print_line", List(a), s, sc)(k))
      case (true, "readLine", Nil)        => runtime("fx_read_line", Nil, s, sc)(k)
      case _                              =>
        owner.filter(symbols(_).kind == SymKind.Data).map(d => d -> s.method).orElse(resolved) match
          case Some((data, "unfold")) if !hasVariant(data, "unfold") =>
            val seed = args.headOption.fold(Type.UnitType)(typeOf(_, sc))
            all(args, sc) { as =>
              val (f, ws) = member(data, dataOf(typeOf(s, sc)).fold(Nil)(_._2), Some(seed))(
                derivedUnfold(data, _, _, s.span)
              )
              bind(Rhs.Call(f, ws ++ as, runtime = false), s, sc)(k)
            }
          case Some((data, variant)) if hasVariant(data, variant) =>
            val tag = symbols.variantsOf(data).indexWhere(_.name == variant)
            construct(tag, args, s, sc)(k)
          case _ =>
            owner.map(symbols(_)) match
              case Some(cap) if cap.kind == SymKind.Cap && isTypeParam(result) =>
                all(args, sc)(as => tableCall(result, cap.id, s.method, as, s, sc)(k))
              case Some(cap) if cap.kind == SymKind.Cap && capVariant(cap.id, s.method, result).isDefined =>
                construct(capVariant(cap.id, s.method, result).getOrElse(0), args, s, sc)(k)
              case Some(cap) if represent.constructor(cap.id).exists(_.name == s.method) =>
                construct(0, args, s, sc)(k)
              case Some(effect) if effect.kind == SymKind.Effect => operation(s, effect.id, args, sc)(k)

              /** `N.fromI64(n)`, `N is Numeric`: a new scalar (a `u64` is unsigned), a conversion, or its table. */
              case None if s.method == "fromI64" && unit.typeOf(s).exists(numericParam) =>
                expr(args.head, sc)(n =>
                  ctype(result, s.span) match
                    case CType.Int(64, _)              => bind(Rhs.Move(n), s, sc)(k)
                    case _: CType.Int | _: CType.Float => bind(Rhs.Prim(PrimOp.Convert, List(n)), s, sc)(k)
                    case _ => tableCall(result, symbols.prelude("Numeric"), "fromI64", List(n), s, sc)(k)
                )
              case _ => unsupported(s.span, s"`${s.qualifier}.${s.method}`", k)

  private def construct(tag: Int, args: List[Tree], t: Tree, sc: Scope)(k: K): Expr =
    all(args, sc)(as => bind(Rhs.Con(tag, as), t, sc)(k))

  private def effectId(effect: SymbolId): Atom = Atom.IntC(BigInt(effect.toInt), CType.Int(64, signed = true))

  /** An abortive effect operation (`-> !`): record the effect and its payload, then abort (`Aborts`).
    * The runtime keeps the payload in a pointer-sized field and never reads or drops it, so an
    * immediate or a slot travels there as its 64 bits, and the handler clause takes it back at its
    * type ([[handle]]).
    */
  private def operation(s: Trees.StaticCall, effect: SymbolId, args: List[Tree], sc: Scope)(k: K): Expr =
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
  private def handle(h: Trees.HandleExpr, sc: Scope)(k: K): Expr =
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

  /** What a function lifted out of `body`, with parameters `params`, takes from its creator: the
    * variables `body` uses but does not bind, and the witnesses of the slots among `params` and the
    * variables `body` binds, through which reference counting may drop them.
    */
  private def captures(body: Expr, params: List[Var]): List[Var] =
    val bound = params ++ Core.lets(body).map(_._1)

    /** Witnesses first: captures load in order, and copying a slot needs its witness loaded. */
    (Core.freeVars(body) ++ bound.flatMap(Core.witnessOf) -- params).toList.sortBy(v =>
      (v.ctype != CType.Witness, v.name)
    )

  /** A nested `fn` is a closure bound to its name. */
  private def nestedFn(f: Trees.FnDecl, sc: Scope)(k: Var => Expr): Expr =
    unit.resolutionOf(f).collect { case Resolution.NestedFn(id) => symbols(id) } match
      case Some(d) =>
        val ps = symbols.membersOf(d.id).filter(_.kind == SymKind.ValueParam).toList
        val ret = d.info match
          case Type.ArrowType(_, r, _) => concrete(r, sc.sigma, sc.bounds)
          case _                       => Type.UnitType
        val (params, inner) =
          withParams(ps.map(p => p.name -> ctype(concrete(p.info, sc.sigma, sc.bounds), f.span)), sc)
        closure(params, f.body.fold(Expr.Ret(Atom.UnitC))(tail(_, inner)), ctype(ret, f.span))(k)
      case None => unsupportedThen(f.span, s"nested fn `${f.name}`", k(fresh(f.name, CType.Boxed)))

  private def hasVariant(data: SymbolId, name: String): Boolean = symbols.variantsOf(data).exists(_.name == name)

  /** The constructor and the fields of the auto-flat-struct `t` represents, if it is one. */
  protected def productFields(t: Type): Option[(String, List[(String, Type)])] =
    t match
      case Type.CapRef(c, args) => represent.constructor(c).map(_.name -> represent.fields(c, args))
      case _                    => None

  /** A data type and its arguments: a data reference, a tuple (the prelude's `Tuple`) or an unfold layer. */
  private[backend] def dataOf(t: Type): Option[(SymbolId, List[Type])] =
    t match
      case Type.TypeRef(id, args) if symbols(id).kind == SymKind.Data && !isStringType(t) => Some(id -> args)
      case Type.TupleType(ts)     => symbols.prelude.get("Tuple").map(_ -> ts)
      case Type.Layer(d, args, _) => Some(d -> args)
      case _                      => None

  /** A variant's field types for data arguments `args`; inside a layer, recursive fields hold the seed. */
  private[backend] def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type] =
    val sigma = symbols.typeParamsOf(data).zip(args).toMap
    symbols.fieldsOf(variant).map(f => concrete(f.info, sigma)).toList

  private def runtime(name: String, args: List[Atom], t: Tree, sc: Scope)(k: K): Expr =
    bind(Rhs.Call(name, args, runtime = true), t, sc)(k)

  private def all(ts: List[Tree], sc: Scope)(k: List[Atom] => Expr): Expr =
    ts match
      case Nil       => k(Nil)
      case t :: rest => expr(t, sc)(a => all(rest, sc)(as => k(a :: as)))

  /** Bind `rhs` to a fresh variable of `t`'s type and continue with it. */
  private[backend] def bind(rhs: Rhs, t: Tree, sc: Scope)(k: K): Expr =
    val tt = typeOf(t, sc)
    val x = typed(fresh("t", ctype(tt, t.span)), tt, t.span)
    Expr.Let(x, rhs, k(x))

  /** Record `x`'s typer type and span; returns `x`. */
  private[backend] def typed(x: Var, t: Type, span: Span): Var =
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

  /** The number of payload slots of variant `tag` of a value of type `t`; none for an immediate,
    * which has no cell to reuse.
    */
  def cellSize(t: Type, tag: Int): Option[Int] =
    cellData(t).flatMap((data, args) =>
      symbols.variantsOf(data).toList.lift(tag).map(v => fieldTypes(data, args, v.id).size)
    )

  private[backend] def fresh(base: String, t: CType): Var = Var(s"$base.${ids.next()}", t)

  private[backend] def typeOf(t: Tree, sc: Scope): Type =
    concrete(unit.typeOf(t).getOrElse(Type.NeverType), sc.sigma, sc.bounds)

  /** `t` at the instance's concrete types, with capability bounds replaced by their representation. */
  private def concrete(t: Type, sigma: Sigma, bounds: Map[Type, Type] = Map.empty): Type =
    represent(TypeOps.subst(TypeOps.replaceAll(t, bounds), sigma))

  /** The top-level function that `name` refers to in the scope of the code being lowered. */
  private def fnNamed(name: String): Option[SymbolId] = globals.get(name).filter(symbols(_).kind == SymKind.Fn)

  private[backend] def ctype(t: Type, span: Span): CType =
    Scalars.scalar(t).getOrElse(t match
      /** Pointer or immediate: Rule CM3.5 as [[Immediates]] applies it to the data type. */
      case _ if isStringType(t) || dataOf(t).isDefined             => immediate(t).getOrElse(CType.Boxed)
      case _ if isTypeParam(t)                                     => CType.Slot(witnessVar(t).name)
      case _: Type.ArrowType                                       => CType.Boxed
      case Type.CapRef(c, _) if represent.constructor(c).isDefined => CType.Boxed
      case _                                                       =>
        problems += problem(DiagCode.B005, span, s"values of type ${symbols.render(t)} are not supported yet")
        CType.Unit)

  /** Report `what` as a problem of the code being lowered (`B005`). */
  private[backend] def report(what: String): Unit = problems += problem(DiagCode.B005, Span.NoSpan, what)

  private def unsupportedThen(span: Span, what: String, rest: => Expr): Expr =
    problems += problem(DiagCode.B005, span, s"$what is not supported by the backend slice yet")
    rest

  private[backend] def unsupported(span: Span, what: String, k: K): Expr = unsupportedThen(span, what, k(Atom.UnitC))

  /** A problem in the prelude's code is at its span in the prelude's file; one with no span says
    * where it is.
    */
  private def problem(code: DiagCode, span: Span, message: String): Diagnostic =
    val where = if inPrelude.value && span == Span.NoSpan then "in the prelude: " else ""
    Diagnostic(Severity.Error, code, span, where + message)
