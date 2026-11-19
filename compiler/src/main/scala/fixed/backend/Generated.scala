package fixed.backend

import fixed.backend.Atom.Var
import fixed.core.{SymbolId, SymbolTable, Type, render}
import fixed.util.Span

/** Members the compiler derives or generates as Core functions, one instance per data type and
  * concrete arguments: Rule 7.4.a `fold` (through functors per Rule 7.4.a.1), Rule 7.4.b `unfold`,
  * Rule 7.4.c `map`, and for list-shaped data `Folding.fold`, `length` and `find`.
  */
private[backend] trait Generated:
  protected type K = Atom => Expr
  protected type VarK = Var => Expr

  /** A field transform for [[rebuild]]: for a field that changes, how to bind its replacement. */
  protected type Transform = (Type, Var) => Option[K => Expr]

  protected def symbols: SymbolTable
  protected def instance(name: String)(build: => Fn): String
  protected def fresh(base: String, t: CType): Var
  protected def ctype(t: Type, span: Span): CType
  protected def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type]
  protected def dataOf(t: Type): Option[(SymbolId, List[Type])]

  /** Record `x`'s typer type for `Demands`; returns `x`. */
  protected def typed(x: Var, t: Type, span: Span): Var

  /** A closure over `body` taking `params` (see `Lower.closure`). */
  protected def closure(params: List[Var], body: Expr, ret: CType)(k: VarK): Expr

  /** Rule 7.4.a: `x.fold(on_v1, ..., on_vn)`, recursive fields folded first; Rule 7.4.a.1: a field
    * `F of ... Self` is mapped through `F`'s derived `map`, so the callback receives `F of ... R`.
    */
  protected def derivedFold(data: SymbolId, args: List[Type], r: Type, span: Span): String =
    val name = generatedName(data, args, "fold", symbols.render(r))
    instance(name) {
      val x = typed(fresh("x", CType.Boxed), Type.TypeRef(data, args), span)
      val callbacks = symbols.variantsOf(data).toList.map(v => fresh(s"on_${v.name}", CType.Boxed))
      val rt = ctype(r, span)
      def through(t: Type, v: Atom)(k: K): Expr =
        t match
          case _ if Lower.isSelf(t, data) =>
            val rec = fresh("rec", rt)
            Expr.Let(rec, Rhs.Call(name, v :: callbacks, runtime = false), k(rec))
          case Type.TypeRef(f, fargs) if fargs.lastOption.exists(Lower.mentions(_, data)) && dataOf(t).isDefined =>
            val elem = fargs.last
            val param = fresh("e", ctype(elem, span))
            val folded = Lower.replace(elem, data, r)
            closure(List(param), through(elem, param)(Expr.Ret(_)), ctype(folded, span)) { c =>
              val mapped = fresh("mapped", CType.Boxed)
              Expr.Let(mapped, Rhs.Call(derivedMap(f, fargs, folded, span), List(v, c), runtime = false), k(mapped))
            }
          case _ => k(v)
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        rebuild(loaded, (t, v) => Option.when(Lower.mentions(t, data))(through(t, v)))(as =>
          result(Rhs.CallClosure(callbacks(tag), as), rt)
        )
      }
      Fn(name, x :: callbacks, rt, switch(x, branches, rt))
    }

  /** Rule 7.4.b: `D.unfold(seed, step)`. The step returns one layer whose recursive fields hold
    * seeds; each is unfolded in turn. (The layer is a separate allocation: fusion is Phase 4.)
    */
  protected def derivedUnfold(data: SymbolId, args: List[Type], seed: Atom, span: Span): String =
    val seedType = seed.tpe
    val name = generatedName(data, args, "unfold", seedType.toString)
    instance(name) {
      val s = fresh("seed", seedType)
      val step = fresh("step", CType.Boxed)
      val layer = fresh("layer", CType.Boxed)
      val recurse = (t: Type, v: Var) =>
        Option.when(Lower.isSelf(t, data))(bound(Rhs.Call(name, List(v, step), runtime = false), CType.Boxed)).flatten
      val branches = perVariant(data, args, layer, span, Some(seedType)) {
        (tag, loaded) => construction(tag, loaded, recurse)
      }
      Fn(
        name,
        List(s, step),
        CType.Boxed,
        Expr.Let(layer, Rhs.CallClosure(step, List(s)), switch(layer, branches, CType.Boxed))
      )
    }

  /** Rule 7.4.c: `x.map(f)` over the rightmost type parameter; recursive fields are mapped too. */
  protected def derivedMap(data: SymbolId, args: List[Type], newElem: Type, span: Span): String =
    val name = generatedName(data, args, "map", symbols.render(newElem))
    instance(name) {
      val (x, f) = (typed(fresh("x", CType.Boxed), Type.TypeRef(data, args), span), fresh("f", CType.Boxed))
      val elemParam = symbols.typeParamsOf(data).lastOption
      val variants = symbols.variantsOf(data).toList
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        val declared = symbols.fieldsOf(variants(tag).id).map(_.info).toList
        val mapField = (_: Type, v: Var) =>
          declared(loaded.indexWhere(_._2 == v)) match
            case Type.TypeRef(p, Nil) if elemParam.contains(p) =>
              bound(Rhs.CallClosure(f, List(v)), ctype(newElem, span))
            case d if Lower.isSelf(d, data) =>
              bound(Rhs.Call(name, List(v, f), runtime = false), CType.Boxed)
            case _ => None
        construction(tag, loaded, mapField)
      }
      Fn(name, List(x, f), CType.Boxed, switch(x, branches, CType.Boxed))
    }

  /** `Folding.fold(init, f)` on a list-shaped data type: a left fold. ponytail: generated here until
    * `satisfies` bodies in Fixed are lowered; spec §7.4 does not derive it. Same for `length` and
    * `find` below.
    */
  protected def foldLeft(data: SymbolId, args: List[Type], accType: Type, span: Span): String =
    val name = generatedName(data, args, "foldLeft", symbols.render(accType))
    instance(name) {
      val rt = ctype(accType, span)
      val (xs, init, f) = (listParam(data, args, span), fresh("init", rt), fresh("f", CType.Boxed))
      val branches = listBranches(data, args, xs, span, Expr.Ret(init)) { (elem, rest) =>
        val acc = fresh("acc", rt)
        Expr.Let(
          acc,
          Rhs.CallClosure(f, List(init, elem)),
          result(Rhs.Call(name, List(rest, acc, f), runtime = false), rt)
        )
      }
      Fn(name, List(xs, init, f), rt, switch(xs, branches, rt))
    }

  protected def length(data: SymbolId, args: List[Type], resultType: Type, span: Span): String =
    val name = generatedName(data, args, "length", symbols.render(resultType))
    instance(name) {
      val rt = ctype(resultType, span)
      val xs = listParam(data, args, span)
      val branches = listBranches(data, args, xs, span, Expr.Ret(Atom.IntC(0, rt))) { (_, rest) =>
        val n = fresh("n", rt)
        Expr.Let(
          n,
          Rhs.Call(name, List(rest), runtime = false),
          result(Rhs.Prim(PrimOp.Add, List(n, Atom.IntC(1, rt))), rt)
        )
      }
      Fn(name, List(xs), rt, switch(xs, branches, rt))
    }

  /** `find(p)`: the first element satisfying `p`, built with the constructors that `optional`'s
    * `some` and `none` map to on the result's representing data type.
    */
  protected def find(data: SymbolId, args: List[Type], optional: SymbolId, resultType: Type, span: Span): String =
    val name = generatedName(data, args, "find", symbols.render(resultType))
    instance(name) {
      val (xs, p) = (listParam(data, args, span), fresh("p", CType.Boxed))
      val tag = (ctor: String) => capVariant(optional, ctor, resultType).getOrElse(0)
      val none = fresh("none", CType.Boxed)
      val branches = listBranches(data, args, xs, span, Expr.Let(none, Rhs.Con(tag("none"), Nil), Expr.Ret(none))) {
        (elem, rest) =>
          val (ok, some) = (fresh("ok", CType.Bool), fresh("some", CType.Boxed))
          val found = Expr.Let(some, Rhs.Con(tag("some"), List(elem)), Expr.Ret(some))
          Expr.Let(
            ok,
            Rhs.CallClosure(p, List(elem)),
            result(Rhs.If(ok, found, result(Rhs.Call(name, List(rest, p), runtime = false), CType.Boxed)), CType.Boxed)
          )
      }
      Fn(name, List(xs, p), CType.Boxed, switch(xs, branches, CType.Boxed))
    }

  private def listParam(data: SymbolId, args: List[Type], span: Span): Var =
    typed(fresh("xs", CType.Boxed), Type.TypeRef(data, args), span)

  /** Branches over a list-shaped value: `empty` for the variant without fields, `cell(elem, rest)` for the other. */
  private def listBranches(data: SymbolId, args: List[Type], xs: Var, span: Span, empty: Expr)(
      cell: (Var, Var) => Expr
  ): List[(Int, Expr)] =
    perVariant(data, args, xs, span) { (_, loaded) =>
      (loaded.find(l => !Lower.isSelf(l._1, data)), loaded.find(l => Lower.isSelf(l._1, data))) match
        case (Some((_, elem)), Some((_, rest))) => cell(elem, rest)
        case _                                  => empty
    }

  /** The variant tag that capability constructor `ctor` maps to on the data type representing `t`. */
  protected def capVariant(cap: SymbolId, ctor: String, t: Type): Option[Int] =
    dataOf(t).flatMap((d, _) =>
      symbols.constructorsOf(cap, ctor).find(_._1 == symbols(d).name).map(c =>
        symbols.variantsOf(d).indexWhere(_.name == c._2)
      )
    )

  /** Rebuild a variant's fields: each `transform`ed field is replaced by what its builder binds. */
  private def rebuild(loaded: List[(Type, Var)], transform: Transform)(
      finish: List[Atom] => Expr
  ): Expr =
    loaded match
      case Nil            => finish(Nil)
      case (t, v) :: rest =>
        val next = (a: Atom) => rebuild(rest, transform)(as => finish(a :: as))
        transform(t, v).fold(next(v))(_(next))

  /** Variant `tag` rebuilt from `loaded`, its fields transformed. */
  private def construction(tag: Int, loaded: List[(Type, Var)], transform: Transform): Expr =
    rebuild(loaded, transform)(as => result(Rhs.Con(tag, as), CType.Boxed))

  /** A field transform for [[rebuild]]: bind `rhs` and continue with the result. */
  private def bound(rhs: Rhs, t: CType): Option[K => Expr] =
    Some((k: K) => { val y = fresh("y", t); Expr.Let(y, rhs, k(y)) })

  private def result(rhs: Rhs, rt: CType): Expr =
    val res = fresh("r", rt)
    Expr.Let(res, rhs, Expr.Ret(res))

  private def generatedName(data: SymbolId, args: List[Type], member: String, at: String): String =
    s"${symbols.render(Type.TypeRef(data, args))}.$member[$at]"

  /** One `Switch` branch per variant of `data`: `obj`'s fields loaded, then `body(tag, fields)`.
    * Inside an unfold layer, recursive fields hold a seed of type `seed`.
    */
  private def perVariant(data: SymbolId, args: List[Type], obj: Var, span: Span, seed: Option[CType] = None)(
      body: (Int, List[(Type, Var)]) => Expr
  ): List[(Int, Expr)] =
    symbols.variantsOf(data).toList.zipWithIndex.map { (v, tag) =>
      val types = fieldTypes(data, args, v.id)
      val cts = types.map(t => seed.filter(_ => Lower.isSelf(t, data)).getOrElse(ctype(t, span)))
      val vars = cts.map(fresh("f", _))
      val loads = vars.zip(Layout.slots(cts))
      (tag, loads.foldRight(body(tag, types.zip(vars)))((l, acc) => Expr.Let(l._1, Rhs.Field(obj, l._2), acc)))
    }

  private def switch(obj: Var, branches: List[(Int, Expr)], rt: CType): Expr =
    val res = fresh("res", rt)
    Expr.Let(res, Rhs.Switch(obj, branches), Expr.Ret(res))

  /** Every variant has no fields, or one recursive field and one element field. */
  protected def listShaped(data: SymbolId): Boolean =
    symbols.variantsOf(data).forall { v =>
      val fs = symbols.fieldsOf(v.id).toList
      fs.isEmpty || (fs.size == 2 && fs.count(f => Lower.isSelf(f.info, data)) == 1)
    }
