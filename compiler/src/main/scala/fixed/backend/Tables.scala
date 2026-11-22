package fixed.backend

import fixed.core.{SymKind, SymbolId, SymbolTable, Type, render}
import fixed.typer.Members
import fixed.util.Span

/** Capability tables (`spec/compilation_model.md` Rule CM4.2) for the (type, capability) pairs code
  * asks for, built on demand. A table has one entry per member of the capability, in the canonical
  * order of [[Tables.members]]. Each entry is an adapter function (Rule CM4.6): it takes the table,
  * then the witnesses of the member's own type parameters (Rule CM4.3), then its arguments, all
  * owned; only the table calls it, so it has the slot convention.
  *
  * Primitive types and `String` have compiler-provided entries. Their members' arguments are scalars
  * or are consumed by the runtime function the entry calls, so no entry has a borrowed value to drop.
  * A scalar's other members are the program's `satisfies` bodies.
  * A data type's entries call members generated once, polymorphic in its own type parameters, and
  * pass them the static witnesses of the instance's arguments: the adapter is per instance, the
  * member's body is shared. A member a `satisfies` body defines calls that body ([[Satisfied]]):
  * the program's before anything generated, the prelude's where nothing is. An adapter reaches its
  * member through an ordinary call, so where the member only reads an argument, reference counting drops it in the adapter. An auto-flat-struct's
  * entries are its constructor and a field reader per field, which drops the struct it receives.
  *
  * A member of the language the backend does not build yet ([[Tables.unbuilt]]) gets a null entry;
  * no lowering asks for one. Any other member a type does not define makes the table an error. The
  * hidden entries `lt` and `le` stand in for `compare` (plan §2); a data type builds them from the
  * `compare`, the program's or the derived one ([[Satisfied.compareOf]]), `String` from the
  * runtime's order of strings ([[Satisfied.stringOrder]]), a scalar from a comparison.
  */
private[backend] trait Tables extends Satisfied:
  this: Lower =>

  /** A sum capability's `fold` on the data type representing it: callbacks follow the capability's
    * constructor requirements; the data's derived fold takes them in variant order. For each
    * variant, the index of the argument that handles it.
    */
  protected def mappedFold(cap: SymbolId, recvType: Type, arity: Int): Option[List[Int]] =
    dataOf(recvType).flatMap { (data, _) =>
      val ctors = symbols.membersOf(cap).filter(_.kind == SymKind.StaticMethod).map(_.name).toList
      val variants = symbols.variantsOf(data).map(_.name).toList
      val variantOf = ctors.map(c => symbols.constructorsOf(cap, c).find(_._1 == data).map(_._2))
      Option.when(ctors.size == arity && variantOf.forall(_.isDefined) && variantOf.flatten.toSet == variants.toSet)(
        variants.map(v => variantOf.indexOf(Some(v)))
      )
    }

  /** The table of capability `cap` at type `t`, or why there is none. */
  def table(t: Type, cap: SymbolId): Either[String, Atom.StaticTable] =
    if typeParamsIn(t).nonEmpty then Left(s"no static table at `${symbols.render(t)}`, which has type parameters")
    else entries(t, cap)

  /** The table of `cap` at `t` with an entry per member, or why there is none. */
  private def entries(t: Type, cap: SymbolId): Either[String, Atom.StaticTable] =
    val known = Lower.scalar(t).isDefined || dataOf(t).isDefined || isStringType(t) || productFields(t).isDefined
    val name = symbols(cap).name
    Option.when(known)(ctype(t, Span.NoSpan)) match
      case Some(c) =>
        val (missing, entries) = Tables.members(symbols, cap).partitionMap(m => entry(t, c, cap, m).toRight(m))
        missing.headOption match
          case Some(m) => Left(s"`${symbols.render(t)}` does not define `$name.$m`")
          case None    => Right(Atom.StaticTable(s"${symbols.render(t)}:$name", entries.map((_, ""))))
      case None => Left(s"no table of `$name` at `${symbols.render(t)}`")

  /** The number of entries and the captured hidden variables of the run-time table being built. */
  private val capturing = scala.util.DynamicVariable((0, List.empty[Atom.Var]))

  /** The capability a data type's own parameters have while its run-time table is built: the
    * derived `show`, `eq` and `compare` reach a field of a parameter's type through its table of it. */
  private val assumed = scala.util.DynamicVariable(Map.empty[Type, SymbolId])

  /** The table of `cap` at `t`, a data type whose arguments mention type parameters, built at run
    * time from the hidden arguments of its own parameters ([[Atom.DynTable]]): their witnesses and
    * tables, the table of `cap` itself where it is `Show`, `Eq` or `Ord`, which the derived member needs.
    * A type that grows under polymorphic recursion has no static table to enumerate. The entries
    * are built once, at the data type's own parameters; a member they call that needs a hidden
    * parameter the table does not hold makes it an error.
    */
  private def dynamicTable(t: Type, cap: SymbolId): Either[String, Atom.DynTable] =
    dataOf(t).toRight(s"no table at `${symbols.render(t)}`, which has type parameters").flatMap { (data, args) =>
      val own = symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil))
      val derivedCap = Option.when(Set("Show", "Eq", "Ord")(symbols.preludeName(cap)))(cap)
      assumed.withValue(derivedCap.fold(Map.empty)(c => own.map(_ -> c).toMap)) {
        val layout = own.flatMap(hiddenOf)
        val tooMany = s"a table at `${symbols.render(t)}` holds more than ${Tables.maxCaptures} witnesses and tables"
        for
          captures <- hiddenArgs(own.zip(args))
          _ <- Either.cond(captures.size <= Tables.maxCaptures, (), tooMany)
          table <-
            capturing.withValue((Tables.members(symbols, cap).size, layout))(entries(Type.TypeRef(data, own), cap))
          entryFns = table.entries.map(_._1).filter(_.nonEmpty)
          _ <- (entryFns ++ entryFns.flatMap(e => Core.callees(functionNamed(e).body))).distinct.map(functionNamed)
            .collectFirst {
              case f if (Core.freeVars(f.body) -- f.params).nonEmpty =>
                s"`${f.name}` needs ${(Core.freeVars(f.body) -- f.params).map(_.name).mkString(", ")}, which it does not take"
            }.toLeft(())
        yield Atom.DynTable(table, captures)
      }
    }

  /** The entries of the table of `cap` at `t`: static, or built at run time where `t` mentions type parameters. */
  protected def layoutOf(t: Type, cap: SymbolId): Option[Atom.StaticTable] =
    if typeParamsIn(t).nonEmpty then dynamicTable(t, cap).toOption.map(_.layout) else table(t, cap).toOption

  /** The capabilities of type parameter `p`'s bound set that get a table (Rule CM4.3): aliases
    * expanded, in the order written; a capability without members has none, and neither has one
    * that another capability of the set extends, whose table already holds its members.
    */
  protected def boundCaps(p: Type): List[SymbolId] =
    val bounds = p match
      case Type.TypeRef(id, Nil) => symbols(id).info
      case _                     => implicitBounds.getOrElse(p, Type.IsBound(Nil))
    match
      case Type.IsBound(bs) => bs
      case c                => List(c)
    val caps =
      (Members.expandAliases(symbols, bounds).collect { case Type.CapRef(c, _) => c } ++
        assumed.value.get(p)).distinct
    val tabled = caps.filter(Tables.members(symbols, _).nonEmpty)
    tabled.filterNot(c => tabled.exists(d => d != c && Tables.reached(symbols, d).contains(c)))

  /** Whether `N.fromI64` on type parameter `p` is `Numeric`'s: `p` is bounded by the prelude's
    * `Numeric` and by no other capability that declares a `fromI64` of its own.
    */
  protected def numericParam(p: Type): Boolean =
    val (numeric, others) = boundCaps(p).partition(Tables.reached(symbols, _).contains(symbols.prelude("Numeric")))
    numeric.nonEmpty && !others.exists(Tables.members(symbols, _).contains("fromI64"))

  /** The table parameter of type parameter `p` for capability `cap`. */
  protected def tableOf(p: Type, cap: SymbolId): Atom.Var =
    Atom.Var(s"t.${witnessVar(p).name.stripPrefix("w.")}.${symbols(cap).name}", CType.Table)

  /** The hidden parameters of type parameter `p`: its witness, then a table per capability of its
    * bound set (Rule CM4.3).
    */
  protected def hiddenOf(p: Type): List[Atom.Var] = witnessVar(p) :: boundCaps(p).map(tableOf(p, _))

  /** The hidden parameters of a `satisfies` body at `types`: the witness and the tables of the
    * declared bounds (not those [[assumed]] adds) of each type parameter they mention.
    */
  private[backend] def bodyHidden(types: List[Type]): List[Atom.Var] =
    assumed.withValue(Map.empty)(types.flatMap(typeParamsIn).distinct.flatMap(hiddenOf))

  /** Each hidden parameter of type parameter `p` with its argument at type `a`, built when asked for. */
  private def hiddenFor(p: Type, a: Type): List[(Atom.Var, () => Either[String, Atom])] =
    (witnessVar(p) -> (() => Right(witnessAtom(a)))) :: boundCaps(p).map(c => tableOf(p, c) -> (() => tableAtom(a, c)))

  /** The arguments for hidden parameters `params` of a function built at `own`, called at `args`. */
  private def hiddenAt(params: List[Atom.Var], own: List[Type], args: List[Type]): List[Atom] =
    val at = own.zip(args).flatMap(hiddenFor).toMap
    params.map(p => at(p)().fold(why => { report(why); Atom.UnitC }, identity))

  /** [[hiddenAt]] for `f`; a generated member still being built takes only witnesses. */
  protected def hiddenArgsOf(f: String, own: List[Type], args: List[Type]) =
    hiddenParams(f).fold(args.map(witnessAtom))(hiddenAt(_, own, args))

  /** The hidden arguments of a call that instantiates each type parameter of `params` at its type:
    * per parameter in the order of [[hiddenOf]], the witness of its type and its tables; or why a
    * table cannot be had.
    */
  protected def hiddenArgs(params: List[(Type, Type)]): Either[String, List[Atom]] =
    val (whys, args) = params.flatMap(hiddenFor).map(_._2()).partitionMap(identity)
    whys.headOption.toLeft(args)

  /** The table of capability `cap` at type `t` where the code being lowered runs: the table
    * parameter of a type parameter whose bound set has `cap`, the static table of a known type,
    * else a table built at run time.
    */
  protected def tableAtom(t: Type, cap: SymbolId): Either[String, Atom] =
    if isTypeParam(t) then
      Either.cond(
        boundCaps(t).contains(cap),
        tableOf(t, cap),
        s"no table of `${symbols(cap).name}` for `${symbols.render(t)}`, whose bound set has none of its own"
      )
    else if typeParamsIn(t).nonEmpty then dynamicTable(t, cap)
    else table(t, cap)

  /** The entry of `member`: its adapter, `""` for a null entry, `None` if `t` does not define it.
    * Compiler-provided and null entries are for members of the prelude's capabilities (and the
    * hidden `lt` and `le`), not for a program's capability that declares a member of the same name.
    * A scalar's other entries are the program's `satisfies` bodies ([[builtIn]]).
    */
  private def entry(t: Type, c: CType, cap: SymbolId, member: String): Option[String] =
    val name = s"${symbols.render(t)}:${symbols(cap).name}.$member"
    val preludeMember = declared(cap, member).forall(d => symbols.preludeName(d.owner).nonEmpty)
    val built = (dataOf(t), productFields(t)) match
      case (Some((data, args)), _)   => dataEntry(t, data, args, cap, member, name)
      case (_, Some((ctor, fields))) => productEntry(ctor, fields, member, name)
      case _ if preludeMember        =>
        bodyEntry(t, Nil, Nil, cap, member, name, fromPrelude = false).orElse(primitive(symbols.render(t), c, member))
      case _ => bodyEntry(t, Nil, Nil, cap, member, name, fromPrelude = false)
    built.orElse(Option.when(preludeMember && Tables.unbuilt(member))(""))

  /** The entry `name` over the `satisfies` body of `member` for `t` (keyed by its [[satisfier]]),
    * built at the data type's own parameters `own`, the program's or the prelude's as `fromPrelude` says. A
    * member with one type parameter of its own is built at [[Generated.R]]; one with several is
    * built at those parameters, and the entry takes a witness for each.
    */
  private def bodyEntry(
      t: Type,
      own: List[Type],
      args: List[Type],
      cap: SymbolId,
      member: String,
      name: String,
      fromPrelude: Boolean
  ): Option[String] =
    for
      d <- declared(cap, member) if hasBody(t, d.id, fromPrelude)
      head <- satisfier(t)
      types = symbols.typeParamsOf(d.id) match
        case List(_) => List(Generated.R)
        case ps      => ps.map(Type.TypeRef(_, Nil))
      g <- satisfactionBody(head, d.id, own, types)
    yield adapter(name, g, args, own)

  /** The declaration of `member` in `cap` or the nearest capability its `extends` chain reaches. */
  protected def declared(cap: SymbolId, member: String): Option[fixed.core.Denotation] =
    Tables.reached(symbols, cap).flatMap(symbols.membersOf).find(_.name == member)

  /** A data type's entry `name`: an adapter over a generated member, or a constructor. */
  private def dataEntry(
      t: Type,
      data: SymbolId,
      args: List[Type],
      cap: SymbolId,
      member: String,
      name: String
  ): Option[String] =
    val own = symbols.typeParamsOf(data).map(p => Type.TypeRef(p, Nil))
    val list = listShaped(data)
    val decl = declared(cap, member)
    val r = decl.flatMap(d =>
      typeParamsIn(d.info).collectFirst { case Type.TypeRef(p, _) if symbols(p).owner == d.id => Generated.R }
    )
    val arity = decl.map(_.info).collect { case Type.ArrowType(lhs, _, _) => Members.params(lhs).size }.getOrElse(0)

    /** The entry over a `satisfies` body: the program's before a generated member, the prelude's after. */
    def body(fromPrelude: Boolean) = bodyEntry(t, own, args, cap, member, name, fromPrelude)
    body(fromPrelude = false).orElse((decl.map(d => symbols.preludeName(d.owner)), member, r) match
      case (_, ctor, _) if decl.exists(_.kind == SymKind.StaticMethod) =>
        capVariant(cap, ctor, t).map(tag =>
          constructor(name, tag, fieldTypes(data, args, symbols.variantsOf(data).toList(tag).id))
        )
      case (_, "fold", Some(r)) if mappedFold(cap, t, arity).isDefined =>
        mappedFold(cap, t, arity).map(order => adapter(name, derivedFold(data, own, r, Span.NoSpan), args, own, order))
      case (Some("Folding"), "fold", Some(r)) if list =>
        Some(adapter(name, foldLeft(data, own, r, Span.NoSpan), args, own))
      case (Some("Folding"), "find", _) if list =>
        /** ponytail: the result is the prelude's `Maybe`; a callee-chosen result returns its witness record later. */
        val maybe = symbols.prelude.get("Maybe").map(Type.TypeRef(_, own.lastOption.toList))
        maybe.map(m => adapter(name, find(data, own, cap, m, Span.NoSpan), args, own))
      case (Some("Sequencing"), "length", _) if list =>
        Some(adapter(name, length(data, own, Type.PrimType("u64"), Span.NoSpan), args, own))
      case (Some("Functor"), "map", Some(b)) => Some(adapter(name, derivedMap(data, own, b, Span.NoSpan), args, own))

      /** Every cell is a heap cell, so the derived `clone` shares the receiver (`spec/stdlib.md` §4.11). */
      case (Some("Clone"), "clone", _) =>
        Some(instance(name) {
          val self: Atom.Var = Atom.Var("self", CType.Boxed)
          Fn(name, List(table, self), CType.Boxed, Expr.Ret(self))
        })
      case (None, "lt" | "le", _) => comparison(t, member).map((g, _) => adapter(name, g, args, own))
      case (Some(c), m, _) if derivers.contains((c, m)) =>
        derived(t, derivers((c, m))).map(_._1).map(adapter(name, _, args, own))
      case _ => body(fromPrelude = true))

  /** The derived members of Rule 7.4 by prelude capability and member name. */
  private lazy val derivers: Map[(String, String), (SymbolId, List[Type]) => String] =
    Map(
      ("Show", "show") -> derivedShow,
      ("Eq", "eq") -> derivedEq,
      ("Ord", "compare") -> derivedCompare
    )

  /** An auto-flat-struct's entry `name` (`spec/type_system.md` §6.6): its constructor `ctor` builds
    * the struct from `fields`, and a member named like a field reads it.
    */
  private def productEntry(ctor: String, fields: List[(String, Type)], member: String, name: String): Option[String] =
    if member == ctor then Some(constructor(name, 0, fields.map(_._2)))
    else
      Option(fields.indexWhere(_._1 == member)).filter(_ >= 0).map { i =>
        instance(name) {
          val self: Atom.Var = Atom.Var("self", CType.Boxed)
          val r: Atom.Var = Atom.Var("r", ctype(fields(i)._2, Span.NoSpan))
          Fn(name, List(table, self), r.ctype, Expr.Let(r, Rhs.Field(self, i), Expr.Ret(r)))
        }
      }

  /** An entry over generated member `g`, built at the data type's own parameters `own`. It takes
    * the table, the member's own witnesses, the receiver, then `g`'s callbacks in the capability's
    * order, where `variantOrder(v)` is the capability position of `g`'s callback `v` (identity if
    * empty). `g` gets the static witness and tables of each of `args` for its parameter in `own`.
    */
  private def adapter(
      name: String,
      g: String,
      args: List[Type],
      own: List[Type],
      variantOrder: List[Int] = Nil
  ): String =
    instance(name) {
      val fn = functionNamed(g)
      val (ws, params) = fn.params.splitAt(hiddenParams(g).fold(0)(_.size))
      val moved = own.zip(args).filter((p, a) => p != a)
      val movedHidden = ws.filter(w => moved.exists((p, _) => hiddenOf(p).contains(w)))
      val fixed = movedHidden.zip(hiddenAt(movedHidden, moved.map(_._1), moved.map(_._2))).toMap
      val (entries, captured) = capturing.value
      val callbacks = params.drop(1)
      val inCapOrder =
        if variantOrder.isEmpty then callbacks
        else callbacks.indices.map(i => callbacks(variantOrder.indexOf(i))).toList
      val result: Atom.Var = Atom.Var("r", fn.ret)
      val call = Rhs.Call(g, ws.map(w => fixed.getOrElse(w, w)) ++ params, runtime = false)
      val entryParams = table :: ws.filterNot(w => fixed.contains(w) || captured.contains(w)) ++ params.take(1) ++
        inCapOrder
      val loaded = captured.zipWithIndex.filter((v, _) => ws.contains(v) && !fixed.contains(v)).foldRight(
        Expr.Let(result, call, Expr.Ret(result))
      ) { case ((v, i), body) => Expr.Let(v, Rhs.Field(table, Layout.captured(entries, i)), body) }
      Fn(name, entryParams, fn.ret, loaded)
    }

  /** A constructor requirement mapped to variant `tag`, whose fields have types `fields`: the
    * variant built from the arguments.
    */
  private def constructor(name: String, tag: Int, fields: List[Type]): String =
    instance(name) {
      val vs = fields.zipWithIndex.map((ft, i) => Atom.Var(s"a$i", ctype(ft, Span.NoSpan)): Atom.Var)
      val result: Atom.Var = Atom.Var("r", CType.Boxed)
      Fn(name, table :: vs, CType.Boxed, Expr.Let(result, Rhs.Con(tag, vs), Expr.Ret(result)))
    }

  private def table: Atom.Var = Atom.Var("table", CType.Table)

  /** A compiler-provided entry of the primitive type or `String` named `t`, of Core type `c`. */
  private def primitive(t: String, c: CType, member: String): Option[String] =
    primitiveBody(t, c, member).map((params, rt, e) =>
      instance(s"$t.$member")(Fn(s"$t.$member", table :: params, rt, e))
    )

  /** Whether the compiler provides member `member` of a prelude capability on scalar type `t`: a
    * program's `satisfies` body for it is not used, so that `==`, `show` and the table entry agree.
    * `compare` is not: a program's `satisfies Ord` body on a scalar defines it, as before the
    * compiler provided one (whether a program may do so is open).
    */
  protected def builtIn(t: Type, member: SymbolId): Boolean =
    Lower.scalar(t).exists(c =>
      symbols.preludeName(symbols(member).owner).nonEmpty && symbols(member).name != "compare" &&
        primitiveBody(symbols.render(t), c, symbols(member).name).isDefined
    )

  /** The parameters, result type and body of [[primitive]]'s entry, if the compiler provides one. */
  private def primitiveBody(t: String, c: CType, member: String): Option[(List[Atom.Var], CType, Expr)] =
    val self: Atom.Var = Atom.Var("self", c)
    val other: Atom.Var = Atom.Var("other", c)
    val numeric = t != "char" && (c match { case CType.Int(_, _) | CType.Float(_) => true; case _ => false })
    def of(params: List[Atom.Var], rhs: Rhs, rt: CType) =
      val res: Atom.Var = Atom.Var("r", rt)
      (params, rt, Expr.Let(res, rhs, Expr.Ret(res)))
    val binary = (op: PrimOp, rt: CType) => of(List(self, other), Rhs.Prim(op, List(self, other)), rt)
    val runtime = (f: String, as: List[Atom.Var]) => of(as, Rhs.Call(f, as, runtime = true), CType.Boxed)
    (member, c) match
      case ("fromI64", CType.Int(64, _)) =>
        val n: Atom.Var = Atom.Var("n", c)
        Some((List(n), c, Expr.Ret(n)))
      case ("fromI64", _) if numeric =>
        val n: Atom.Var = Atom.Var("n", CType.Int(64, signed = true))
        Some(of(List(n), Rhs.Prim(PrimOp.Convert, List(n)), c))
      case ("negate", _) if numeric => Some(of(List(self), Rhs.Prim(PrimOp.Neg, List(self)), c))
      case (op, _) if numeric && Tables.arithmetic.contains(op) => Some(binary(Tables.arithmetic(op), c))
      case ("eq", CType.Boxed)                                  =>
        Some(of(List(self, other), Rhs.Call("fx_str_eq", List(self, other), runtime = true), CType.Bool))
      case ("eq", _)                           => Some(binary(PrimOp.Eq, CType.Bool))
      case ("lt", _) if numeric || t == "char" => Some(binary(PrimOp.Lt, CType.Bool))
      case ("le", _) if numeric || t == "char" => Some(binary(PrimOp.Le, CType.Bool))
      case ("lt" | "le", _) if t == "String"   =>
        val op = if member == "lt" then PrimOp.Lt else PrimOp.Le
        Some((
          List(self, other),
          CType.Bool,
          stringOrder(self, other)(c => of(Nil, Rhs.Prim(op, List(c, zeroOrder)), CType.Bool)._3)
        ))
      case ("compare", _) if numeric || t == "char" || t == "String" =>
        val at = if t == "String" then Type.TypeRef(symbols.prelude("String"), Nil) else Type.PrimType(t)
        Some((List(self, other), CType.Boxed, compareValues(at, self, other)(Expr.Ret(_))))
      case ("concat", CType.Boxed) => Some(runtime("fx_str_concat", List(self, other)))
      case ("show", CType.Boxed)   => Some((List(self), c, Expr.Ret(self)))
      case ("clone", _)            => Some((List(self), c, Expr.Ret(self)))
      case ("show", _)             =>
        Lower.formatter(c, t == "char").map { (f, to) =>
          if to == c then runtime(f, List(self))
          else
            val w: Atom.Var = Atom.Var("w", to)
            val (_, rt, call) = runtime(f, List(w))
            (List(self), rt, Expr.Let(w, Rhs.Prim(PrimOp.Widen, List(self)), call))
        }
      case _ => None

object Tables:

  private val arithmetic =
    Map("add" -> PrimOp.Add, "sub" -> PrimOp.Sub, "mul" -> PrimOp.Mul, "div" -> PrimOp.Div, "rem" -> PrimOp.Rem)

  /** The (capability, member) pairs that are one binary primitive operation on a scalar. */
  private[backend] val scalarBinary: Map[(String, String), PrimOp] =
    arithmetic.map((name, op) => ("Numeric", name) -> op) + (("Eq", "eq") -> PrimOp.Eq)

  /** The most witnesses and tables a table built at run time holds (`fx_table` in the runtime). */
  private val maxCaptures = 64

  /** Members of the language the backend builds no entry for yet where no `satisfies` body defines
    * them and nothing derives them (`find` only for list-shaped data).
    */
  private val unbuilt = Set("foldRight", "head", "tail", "find")

  /** The hidden entries every table of a capability that extends `Ord` ends with (plan §2): the
    * comparison operators. They mean what `compare` means through `Order`: `lt(a, b)` is
    * `a.compare(b)` being `Order.Less`, `le(a, b)` its not being `Order.Greater`. For floats this
    * fixes `compare`: `Less` iff ordered-less, `Equal` iff ordered-equal, else `Greater` (a NaN on
    * either side is `Greater`), as the `fcmp olt` / `ole` entries compute.
    */
  private val ordEntries = List("lt", "le")

  /** The canonical, frozen member order of capability `cap`'s tables: its own members in
    * declaration order, then those of each capability its `extends` chain reaches, depth first in
    * the order written (`spec/type_system.md` §6.2), each name once, at its first occurrence;
    * then `lt` and `le` if `Ord` is among them.
    */
  def members(symbols: SymbolTable, cap: SymbolId): List[String] =
    val caps = reached(symbols, cap)
    val declared = caps.flatMap(symbols.membersOf).filter(m => Set(SymKind.Method, SymKind.StaticMethod)(m.kind))
    (declared.map(_.name) ++ (if caps.exists(symbols.preludeName(_) == "Ord") then ordEntries else Nil)).distinct

  /** `cap` and the capabilities its `extends` chain reaches, depth first in the order written. */
  def reached(symbols: SymbolTable, cap: SymbolId): List[SymbolId] =
    fixed.typer.MemberTables.reached(symbols, cap, None, scala.collection.immutable.BitSet.empty).map(_._1)
