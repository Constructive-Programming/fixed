package fixed.backend

import fixed.backend.Atom.Var
import fixed.core.{SymbolId, SymbolTable, Type, render}
import fixed.util.Span

/** Members the compiler derives or generates as Core functions: Rule 7.4.a `fold` (through functors
  * per Rule 7.4.a.1), Rule 7.4.b `unfold`, Rule 7.4.c `map`, and for list-shaped data
  * `Folding.fold`, `length` and `find`. By default one instance is built per data type and
  * arguments; in baseline mode one version per data type, at its own parameters ([[member]]).
  * Arguments that are type parameters make a polymorphic member with hidden witness parameters
  * ([[hidden]]).
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
  protected def isStringType(t: Type): Boolean
  protected def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type]
  protected def dataOf(t: Type): Option[(SymbolId, List[Type])]

  /** Whether generated members are built once, at their data type's own parameters (Rule CM3.1). */
  protected def baseline: Boolean

  /** The baseline versions built, and the members still built per instance with the reason (`Lower`). */
  def baselines: collection.mutable.Set[String]
  def instances: collection.mutable.Map[String, String]

  /** `body`, or the first problem it reported, everything it built discarded (`Lower.attempt`). */
  protected def attempt[A](body: => A): Either[String, A]

  /** Report `what` as a problem of the code being lowered (`B005`). */
  protected def report(what: String): Unit

  /** Record `x`'s typer type for `Demands`; returns `x`. */
  protected def typed(x: Var, t: Type, span: Span): Var

  /** A closure over `body` taking `params` (see `Lower.closure`). */
  protected def closure(params: List[Var], body: Expr, ret: CType)(k: VarK): Expr

  /** Rule 7.4.a: `x.fold(on_v1, ..., on_vn)`, recursive fields folded first; Rule 7.4.a.1: a field
    * `F of ... Self` is mapped through `F`'s derived `map`, so the callback receives `F of ... R`.
    */
  protected def derivedFold(data: SymbolId, args: List[Type], r: Type, span: Span): String =
    val name = generatedName(data, args, "fold", shown(r))
    val ws = hidden(args :+ r)
    instance(name) {
      val x = typed(fresh("x", CType.Boxed), Type.TypeRef(data, args), span)
      val callbacks = symbols.variantsOf(data).toList.map(v => fresh(s"on_${v.name}", CType.Boxed))
      val rt = ctype(r, span)
      def through(t: Type, v: Atom)(k: K): Expr =
        t match
          case _ if Lower.isSelf(t, data) =>
            val rec = fresh("rec", rt)
            Expr.Let(rec, Rhs.Call(name, ws ++ (v :: callbacks), runtime = false), k(rec))
          case Type.TypeRef(f, fargs) if fargs.lastOption.exists(Lower.mentions(_, data)) && dataOf(t).isDefined =>
            val elem = fargs.last
            val param = fresh("e", ctype(elem, span))
            val folded = Lower.replace(elem, data, r)
            closure(List(param), through(elem, param)(Expr.Ret(_)), ctype(folded, span)) { c =>
              val mapped = fresh("mapped", CType.Boxed)
              val (map, ws) = member(f, fargs, Some(folded))((a, b) => derivedMap(f, a, b, span))
              Expr.Let(mapped, Rhs.Call(map, ws ++ List(v, c), runtime = false), k(mapped))
            }
          case _ => k(v)
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        rebuild(loaded, (t, v) => Option.when(Lower.mentions(t, data))(through(t, v)))(as =>
          result(Rhs.CallClosure(callbacks(tag), as), rt)
        )
      }
      Fn(name, ws ++ (x :: callbacks), rt, switch(x, branches, rt))
    }

  /** Rule 7.4.b: `D.unfold(seed, step)`. The step returns one layer whose recursive fields hold
    * seeds; each is unfolded in turn. (The layer is a separate allocation: fusion is Phase 4.)
    */
  protected def derivedUnfold(data: SymbolId, args: List[Type], seed: Type, span: Span): String =
    val seedType = ctype(seed, span)
    val name = generatedName(data, args, "unfold", if seed == Generated.R then "R" else seedType.toString)
    val ws = hidden(args :+ seed)
    instance(name) {
      val s = fresh("seed", seedType)
      val step = fresh("step", CType.Boxed)
      val layer = fresh("layer", CType.Boxed)
      val recurse = (t: Type, v: Var) =>
        Option.when(Lower.isSelf(t, data))(bound(Rhs.Call(name, ws ++ List(v, step), runtime = false), CType.Boxed))
          .flatten
      val branches = perVariant(data, args, layer, span, Some(seedType)) {
        (tag, loaded) => construction(tag, loaded, recurse)
      }
      Fn(
        name,
        ws ++ List(s, step),
        CType.Boxed,
        Expr.Let(layer, Rhs.CallClosure(step, List(s)), switch(layer, branches, CType.Boxed))
      )
    }

  /** Rule 7.4.c: `x.map(f)` over the rightmost type parameter; recursive fields are mapped too. */
  protected def derivedMap(data: SymbolId, args: List[Type], newElem: Type, span: Span): String =
    val name = generatedName(data, args, "map", shown(newElem))
    val ws = hidden(args :+ newElem)
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
              bound(Rhs.Call(name, ws ++ List(v, f), runtime = false), CType.Boxed)
            case _ => None
        construction(tag, loaded, mapField)
      }
      Fn(name, ws ++ List(x, f), CType.Boxed, switch(x, branches, CType.Boxed))
    }

  /** `Folding.fold(init, f)` on a list-shaped data type: a left fold. ponytail: generated here until
    * `satisfies` bodies in Fixed are lowered; spec §7.4 does not derive it. Same for `length` and
    * `find` below.
    */
  protected def foldLeft(data: SymbolId, args: List[Type], accType: Type, span: Span): String =
    val name = generatedName(data, args, "foldLeft", shown(accType))
    val ws = hidden(args :+ accType)
    instance(name) {
      val rt = ctype(accType, span)
      val (xs, init, f) = (listParam(data, args, span), fresh("init", rt), fresh("f", CType.Boxed))
      val branches = listBranches(data, args, xs, span, Expr.Ret(init)) { (elem, rest) =>
        val acc = fresh("acc", rt)
        Expr.Let(
          acc,
          Rhs.CallClosure(f, List(init, elem)),
          result(Rhs.Call(name, ws ++ List(rest, acc, f), runtime = false), rt)
        )
      }
      Fn(name, ws ++ List(xs, init, f), rt, switch(xs, branches, rt))
    }

  protected def length(data: SymbolId, args: List[Type], resultType: Type, span: Span): String =
    val name = generatedName(data, args, "length", symbols.render(resultType))
    val ws = hidden(args)
    instance(name) {
      val rt = ctype(resultType, span)
      val xs = listParam(data, args, span)
      val branches = listBranches(data, args, xs, span, Expr.Ret(Atom.IntC(0, rt))) { (_, rest) =>
        val n = fresh("n", rt)
        Expr.Let(
          n,
          Rhs.Call(name, ws :+ rest, runtime = false),
          result(Rhs.Prim(PrimOp.Add, List(n, Atom.IntC(1, rt))), rt)
        )
      }
      Fn(name, ws :+ xs, rt, switch(xs, branches, rt))
    }

  /** `find(p)`: the first element satisfying `p`, built with the constructors that `optional`'s
    * `some` and `none` map to on the result's representing data type.
    */
  protected def find(data: SymbolId, args: List[Type], optional: SymbolId, resultType: Type, span: Span): String =
    val name = generatedName(data, args, "find", symbols.render(resultType))
    val ws = hidden(args)
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
            result(
              Rhs.If(ok, found, result(Rhs.Call(name, ws ++ List(rest, p), runtime = false), CType.Boxed)),
              CType.Boxed
            )
          )
      }
      Fn(name, ws ++ List(xs, p), CType.Boxed, switch(xs, branches, CType.Boxed))
    }

  /** The witness parameter of type parameter `p` (`spec/compilation_model.md` Rule CM4.3). */
  protected def witnessOf(p: SymbolId): Var = Var(s"w.${symbols(p).name}.${p.toInt}", CType.Witness)

  /** The witness parameter of a type parameter: a declared one, or [[Generated.R]]. */
  protected def witnessVar(t: Type): Var =
    t match
      case Type.TypeRef(p, Nil) => witnessOf(p)
      case _                    => Var("w.R", CType.Witness)

  /** The witness of type `t` where the code being lowered runs: the witness parameter of a type
    * parameter, else the static witness of a known type (every value that is not an immediate is on
    * the heap).
    */
  protected def witnessAtom(t: Type): Atom =
    if isTypeParam(t) then witnessVar(t)
    else Atom.StaticWitness(if ctype(t, Span.NoSpan).counted then "ref" else "imm")

  /** The hidden witness parameters of a generated member at `types`: one per type parameter they
    * mention, in order of first occurrence. Empty for a member at concrete types.
    */
  protected def hidden(types: List[Type]): List[Var] = types.flatMap(typeParamsIn).distinct.map(witnessVar)

  /** The type parameters `t` mentions, in order of first occurrence, [[Generated.R]] included. */
  protected def typeParamsIn(t: Type): List[Type] =
    if isTypeParam(t) then List(t) else t.children.flatMap(typeParamsIn)

  /** Whether `t` is a type parameter: a declared one, or [[Generated.R]]. */
  protected def isTypeParam(t: Type): Boolean =
    t match
      case Type.TypeRef(p, Nil) => symbols(p).kind == fixed.core.SymKind.TypeParam
      case _                    => t == Generated.R

  /** Generated member `gen(args, r)` of `data`, with the hidden witnesses to call it with. In baseline
    * mode it is built once, at `data`'s own parameters and [[Generated.R]], and takes the witness of
    * each of `args` and of `r`; otherwise it is built at `args` and `r` and takes the witnesses of the
    * type parameters they mention.
    */
  protected def member(data: SymbolId, args: List[Type], r: Option[Type])(
      gen: (List[Type], Type) => String
  ): (String, List[Atom]) =
    lazy val perInstance = (gen(args, r.getOrElse(Type.UnitType)), hidden(args ++ r))
    if !baseline then perInstance
    else
      val own = symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil))
      attempt(gen(own, Generated.R)) match
        case Right(name) =>
          baselines += name
          (name, (args ++ r).map(witnessAtom))
        case Left(why) =>
          val (name, ws) = perInstance
          if ws.isEmpty then instances(name) = why else report(why)
          (name, ws)

  private def listParam(data: SymbolId, args: List[Type], span: Span): Var =
    typed(fresh("xs", CType.Boxed), Type.TypeRef(data, args), span)

  /** A copy of a value of `data` at `args` (`spec/quantities.md` Rule Q9.3): the cell is copied,
    * and so is every field at a part key in `parts` (keyed by `key` of the owning type); the other
    * fields are shared. Recursive fields share the copy function.
    */
  protected def copyOf(
      data: SymbolId,
      args: List[Type],
      parts: Set[(String, Int)],
      key: Type => String,
      span: Span
  ): String =
    val t = Type.TypeRef(data, args)
    val name = generatedName(data, args, "copy", parts.toList.sorted.map((k, s) => s"$k#$s").mkString(";"))
    instance(name) {
      val x = typed(fresh("x", CType.Boxed), t, span)
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        val slots = Layout.slots(loaded.map((ft, _) => ctype(ft, span)))
        val copyField = (ft: Type, v: Var) =>
          dataOf(ft).filter(_ => parts((key(t), slots(loaded.indexWhere(_._2 == v))))).flatMap((d, a) =>
            bound(Rhs.Call(copyOf(d, a, parts, key, span), List(v), runtime = false), CType.Boxed)
          )
        construction(tag, loaded, copyField)
      }
      Fn(name, List(x), CType.Boxed, switch(x, branches, CType.Boxed))
    }

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

  /** `t` as a generated member's name shows it. */
  private def shown(t: Type): String = if t == Generated.R then "R" else symbols.render(t)

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

object Generated:

  /** The type parameter of a generated member built once that is the member's own: the result of a
    * `fold`, the new element of a `map`, the seed of an `unfold`. The typer gives none of them a
    * symbol, so this reserved type variable stands for each, and its witness parameter is `w.R`.
    */
  val R: Type = Type.TypeVar(-1)
