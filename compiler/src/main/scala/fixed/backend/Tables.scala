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
  * A data type's entries call members generated once, polymorphic in its own type parameters, and
  * pass them the static witnesses of the instance's arguments: the adapter is per instance, the
  * member's body is shared. An adapter reaches its member through an ordinary call, so where the
  * member only reads an argument, reference counting drops it in the adapter.
  *
  * A member of the language the backend does not build yet ([[Tables.unbuilt]]) gets a null entry;
  * no lowering asks for one. Any other member a type does not define makes the table an error. The
  * hidden entries `lt` and `le` stand in for `compare` (plan §2).
  */
private[backend] trait Tables extends Generated:

  protected def mappedFold(cap: SymbolId, recvType: Type, arity: Int): Option[List[Int]]
  protected def functionNamed(name: String): Fn

  /** The table of capability `cap` at type `t`, or why there is none. */
  def table(t: Type, cap: String): Either[String, Atom.StaticTable] =
    val known = Lower.scalar(t).isDefined || dataOf(t).isDefined || isStringType(t)
    (Option.when(known)(ctype(t, Span.NoSpan)), symbols.scope.get(cap).filter(symbols(_).kind == SymKind.Cap)) match
      case _ if typeParamsIn(t).nonEmpty =>
        Left(s"a table at `${symbols.render(t)}` needs a witness record built at run time (4.1.M7c)")
      case (Some(c), Some(id)) =>
        val (missing, entries) = Tables.members(symbols, id).partitionMap(m => entry(t, c, id, m).toRight(m))
        missing.headOption match
          case Some(m) => Left(s"`${symbols.render(t)}` does not define `$cap.$m`")
          case None    => Right(Atom.StaticTable(s"${symbols.render(t)}:$cap", entries.map((_, ""))))
      case _ => Left(s"no table of `$cap` at `${symbols.render(t)}`")

  /** The entry of `member`: its adapter, `""` for a null entry, `None` if `t` does not define it. */
  private def entry(t: Type, c: CType, cap: SymbolId, member: String): Option[String] =
    val built = dataOf(t) match
      case Some((data, args)) => dataEntry(t, data, args, cap, member)
      case None               => primitive(symbols.render(t), c, member)
    built.orElse(Option.when(Tables.unbuilt(member))(""))

  /** A data type's entry: an adapter over a generated member, or a constructor. */
  private def dataEntry(t: Type, data: SymbolId, args: List[Type], cap: SymbolId, member: String): Option[String] =
    val own = symbols.typeParamsOf(data).map(p => Type.TypeRef(p, Nil))
    val list = listShaped(data)
    val decl = Tables.reached(symbols, cap).flatMap(symbols.membersOf).find(_.name == member)
    val r = decl.flatMap(d =>
      typeParamsIn(d.info).collectFirst { case Type.TypeRef(p, _) if symbols(p).owner == d.id => Generated.R }
    )
    val arity = decl.map(_.info).collect { case Type.ArrowType(lhs, _, _) => Members.params(lhs).size }.getOrElse(0)
    val name = s"${symbols.render(t)}:${symbols(cap).name}.$member"
    (decl.map(d => symbols(d.owner).name), member, r) match
      case (_, ctor, _) if decl.exists(_.kind == SymKind.StaticMethod) =>
        capVariant(cap, ctor, t).map(tag => constructor(name, tag, data, args))
      case (_, "fold", Some(r)) if mappedFold(cap, t, arity).isDefined =>
        mappedFold(cap, t, arity).map(order => adapter(name, derivedFold(data, own, r, Span.NoSpan), args, own, order))
      case (Some("Folding"), "fold", Some(r)) if list =>
        Some(adapter(name, foldLeft(data, own, r, Span.NoSpan), args, own))
      case (Some("Folding"), "find", _) if list =>
        /** ponytail: the result is the prelude's `Maybe`; a callee-chosen result returns its witness record later. */
        val maybe = symbols.scope.get("Maybe").map(Type.TypeRef(_, own.lastOption.toList))
        maybe.map(m => adapter(name, find(data, own, cap, m, Span.NoSpan), args, own))
      case (Some("Sequencing"), "length", _) if list =>
        Some(adapter(name, length(data, own, Type.PrimType("u64"), Span.NoSpan), args, own))
      case (Some("Functor"), "map", Some(b)) => Some(adapter(name, derivedMap(data, own, b, Span.NoSpan), args, own))
      case _                                 => None

  /** An entry over generated member `g`, built at the data type's own parameters `own`. It takes
    * the table, the member's own witnesses, the receiver, then `g`'s callbacks in the capability's
    * order, where `variantOrder(v)` is the capability position of `g`'s callback `v` (identity if
    * empty). `g` gets the static witness of each argument in `args` for its parameter in `own`.
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
      val (ws, params) = fn.params.span(_.ctype == CType.Witness)
      val fixed = own.zip(args).collect { case (Type.TypeRef(p, _), a) => witnessOf(p) -> witnessAtom(a) }.toMap
      val callbacks = params.drop(1)
      val inCapOrder =
        if variantOrder.isEmpty then callbacks
        else callbacks.indices.map(i => callbacks(variantOrder.indexOf(i))).toList
      val result: Atom.Var = Atom.Var("r", fn.ret)
      val call = Rhs.Call(g, ws.map(w => fixed.getOrElse(w, w)) ++ params, runtime = false)
      val entryParams = table :: ws.filterNot(fixed.contains) ++ params.take(1) ++ inCapOrder
      Fn(name, entryParams, fn.ret, Expr.Let(result, call, Expr.Ret(result)))
    }

  /** A constructor requirement mapped to variant `tag`: the variant built from the arguments. */
  private def constructor(name: String, tag: Int, data: SymbolId, args: List[Type]): String =
    instance(name) {
      val variant = symbols.variantsOf(data).toList(tag).id
      val vs =
        fieldTypes(data, args, variant).zipWithIndex.map((ft, i) => Atom.Var(s"a$i", ctype(ft, Span.NoSpan)): Atom.Var)
      val result: Atom.Var = Atom.Var("r", CType.Boxed)
      Fn(name, table :: vs, CType.Boxed, Expr.Let(result, Rhs.Con(tag, vs), Expr.Ret(result)))
    }

  private def table: Atom.Var = Atom.Var("table", CType.Table)

  /** A compiler-provided entry of the primitive type or `String` named `t`, of Core type `c`. */
  private def primitive(t: String, c: CType, member: String): Option[String] =
    val self: Atom.Var = Atom.Var("self", c)
    val other: Atom.Var = Atom.Var("other", c)
    val numeric = t != "char" && (c match { case CType.Int(_, _) | CType.Float(_) => true; case _ => false })
    def of(params: List[Atom.Var], rhs: Rhs, rt: CType) =
      val res: Atom.Var = Atom.Var("r", rt)
      (params, rt, Expr.Let(res, rhs, Expr.Ret(res)))
    val binary = (op: PrimOp, rt: CType) => of(List(self, other), Rhs.Prim(op, List(self, other)), rt)
    val runtime = (f: String, as: List[Atom.Var]) => of(as, Rhs.Call(f, as, runtime = true), CType.Boxed)
    val body = (member, c) match
      case ("from_i64", CType.Int(64, _)) =>
        val n: Atom.Var = Atom.Var("n", c)
        Some((List(n), c, Expr.Ret(n)))
      case ("from_i64", _) if numeric =>
        val n: Atom.Var = Atom.Var("n", CType.Int(64, signed = true))
        Some(of(List(n), Rhs.Prim(PrimOp.Convert, List(n)), c))
      case ("negate", _) if numeric => Some(of(List(self), Rhs.Prim(PrimOp.Neg, List(self)), c))
      case (op, _) if numeric && Tables.arithmetic.contains(op) => Some(binary(Tables.arithmetic(op), c))
      case ("eq", CType.Boxed)                                  => Some(runtime("fx_str_eq", List(self, other)))
      case ("eq", _)                                            => Some(binary(PrimOp.Eq, CType.Bool))
      case ("lt", _) if numeric || t == "char"                  => Some(binary(PrimOp.Lt, CType.Bool))
      case ("le", _) if numeric || t == "char"                  => Some(binary(PrimOp.Le, CType.Bool))
      case ("concat", CType.Boxed)                              => Some(runtime("fx_str_concat", List(self, other)))
      case ("show", CType.Boxed)                                => Some((List(self), c, Expr.Ret(self)))
      case ("show", _)                                          =>
        Lower.formatter(c, t == "char").map { (f, to) =>
          if to == c then runtime(f, List(self))
          else
            val w: Atom.Var = Atom.Var("w", to)
            val (_, rt, call) = runtime(f, List(w))
            (List(self), rt, Expr.Let(w, Rhs.Prim(PrimOp.Widen, List(self)), call))
        }
      case _ => None
    body.map((params, rt, e) => instance(s"$t.$member")(Fn(s"$t.$member", table :: params, rt, e)))

object Tables:

  private val arithmetic =
    Map("add" -> PrimOp.Add, "sub" -> PrimOp.Sub, "mul" -> PrimOp.Mul, "div" -> PrimOp.Div, "rem" -> PrimOp.Rem)

  /** Members of the language the backend builds no entry for yet: `compare` needs a data type that
    * satisfies `Ordering`, and the others are not derived (`find` only for list-shaped data).
    */
  private val unbuilt = Set("compare", "fold_right", "head", "tail", "find")

  /** The hidden entries every table of a capability that extends `Ord` ends with (plan §2). */
  private val ordEntries = List("lt", "le")

  /** The canonical, frozen member order of capability `cap`'s tables: its own members in
    * declaration order, then those of each capability its `extends` chain reaches, depth first in
    * the order written (`spec/type_system.md` §6.2), each name once, at its first occurrence;
    * then `lt` and `le` if `Ord` is among them.
    */
  def members(symbols: SymbolTable, cap: SymbolId): List[String] =
    val caps = reached(symbols, cap)
    val declared = caps.flatMap(symbols.membersOf).filter(m => Set(SymKind.Method, SymKind.StaticMethod)(m.kind))
    (declared.map(_.name) ++ (if caps.exists(symbols(_).name == "Ord") then ordEntries else Nil)).distinct

  /** `cap` and the capabilities its `extends` chain reaches, depth first in the order written. */
  private def reached(symbols: SymbolTable, cap: SymbolId): List[SymbolId] =
    fixed.typer.MemberTables.reached(symbols, cap, None, scala.collection.immutable.BitSet.empty).map(_._1)
