package fixed.backend

import fixed.ast.Trees
import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.typer.{Members, TyperPhase}

/** The method bodies of `satisfies` declarations (`spec/type_system.md` §8.3), lowered as functions
  * of their own: a call of the member on a data type that has one, and the member's entry in that
  * type's capability table, run the body. The program's bodies take the place of what the compiler
  * would generate; the prelude's fill only members it generates nothing for, since a generated
  * `fold`, `length` or `find` on a list computes what the prelude's body computes, in a loop.
  */
private[backend] trait Satisfied extends Generated:
  this: Lower =>

  /** The types a call with argument types `args` and result type `result` gives `member`'s own type parameters. */
  protected def ownTypeArgs(member: SymbolId, args: List[Type], result: Type): Map[SymbolId, Type] =
    val (declared, ret) = TypeOps.signature(symbols(member).info)
    val own = symbols.typeParamsOf(member).toSet
    (declared.zip(args) :+ (ret -> result)).foldLeft(Map.empty[SymbolId, Type])((acc, dc) =>
      TypeOps.solve(own, dc._1, dc._2, acc)
    )

  /** The type such a call gives `member`'s own type parameter, if any; `()` where nothing fixes it. */
  protected def ownTypeArg(member: SymbolId, args: List[Type], result: Type): Option[Type] =
    symbols.typeParamsOf(member).headOption.map(ownTypeArgs(member, args, result).getOrElse(_, Type.UnitType))

  /** A typed `satisfies` method body, its capability's arguments as declared, and whether it is the prelude's. */
  private case class Body(cap: SymbolId, capArgs: List[Type], fn: Trees.InstanceMethod, prelude: Boolean)

  /** The bodies by satisfying data type and the capability member each defines; a program body
    * replaces a prelude body for the same pair. Bodies on primitive types are not indexed, and
    * neither are members with several type parameters of their own: their calls stay `B005`.
    */
  private lazy val satisfactionBodies: Map[(SymbolId, SymbolId), Body] =
    val own = unit.tree.toList.flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
    val prelude = TyperPhase.preludeSatisfactions(symbols)
    (for
      typing <- unit.typing.toList
      case d @ Trees.SatisfiesDecl(typeName, Trees.CapRef(capName, _, _), methods, _) <- prelude ++ own
      data <- symbols.dataNamed(typeName).toList
      cap <- symbols.scope.get(capName).filter(symbols(_).kind == SymKind.Cap).toList
      capArgs = symbols.satisfactionsOf(Type.TypeRef(data, Nil)).collectFirst { case Type.CapRef(`cap`, as) => as }
      case fn: Trees.InstanceMethod <- methods if fn.body.isDefined
      entry <- typing.members.lookup(cap, SymKind.Method, fn.name).headOption.toList
      if symbols.typeParamsOf(entry.member.id).sizeIs <= 1
    yield (data, entry.member.id) -> Body(cap, capArgs.getOrElse(Nil), fn, prelude.exists(_ eq d))).toMap

  /** Whether `t` has a `satisfies` body for `member` that is the prelude's exactly when `fromPrelude`. */
  protected def hasBody(t: Type, member: SymbolId, fromPrelude: Boolean): Boolean =
    dataOf(t).exists((d, _) => satisfactionBodies.get((d, member)).exists(_.prelude == fromPrelude))

  /** Whether `t` is the built-in `String`, which `==` compares in the runtime. */
  protected def isStringType(t: Type): Boolean =
    t match
      case Type.TypeRef(id, Nil)   => symbols(id).name == "String" && symbols(id).owner == SymbolId.Prelude
      case Type.PrimType("String") => true
      case _                       => false

  /** `l == r` on boxed values of type `t`: the runtime's string comparison for `String`, the body of
    * the program's `t satisfies Eq` for a data type, else its derived `eq` ([[derivedEq]]).
    */
  protected def equality(t: Type, l: Atom, r: Atom): Option[Rhs] =
    if isStringType(t) then Some(Rhs.Call("fx_str_eq", List(l, r), runtime = true))
    else
      val body =
        for
          (data, args) <- dataOf(t)
          eq <- symbols.scope.get("Eq").flatMap(symbols.membersOf(_).find(_.name == "eq"))
          if hasBody(t, eq.id, fromPrelude = false)
        yield
          val (f, ws) = member(data, args, None)((a, _) => satisfactionBody(data, eq.id, a, Type.UnitType).get)
          Rhs.Call(f, ws ++ List(l, r), runtime = false)
      body.orElse(knownDerived(t, derivedEq).map(Rhs.Call(_, List(l, r), runtime = false)))

  /** The derived member `gen` builds for data type `t` (Rule 7.4), at known arguments only: a field
    * of a type parameter's type would need that parameter's `Show` or `Eq` table, which these
    * members do not take.
    */
  protected def knownDerived(t: Type, gen: (SymbolId, List[Type]) => String): Option[String] =
    dataOf(t).filter(_ => typeParamsIn(t).isEmpty).map(gen.tupled)

  /** `v` of type `t` shown (Rule 7.4, [[derivedShow]]): a scalar by the runtime, `()` as `"()"`, a
    * `String` as it is, a data type by the program's `satisfies Show` body or else its derived
    * `show`; anything else is a problem (`B005`).
    */
  protected def showValue(t: Type, v: Atom)(k: K): Expr =
    val s = fresh("s", CType.Boxed)
    def call(f: String, a: Atom) = Expr.Let(s, Rhs.Call(f, List(a), runtime = true), k(s))
    Lower.formatter(v.tpe, t == Type.PrimType("char")) match
      case Some((f, to)) if to == v.tpe => call(f, v)
      case Some((f, to))                =>
        val w = fresh("w", to)
        Expr.Let(w, Rhs.Prim(PrimOp.Widen, List(v)), call(f, w))
      case None if v.tpe == CType.Unit => k(Atom.StrC("()"))
      case None if isStringType(t)     => k(v)
      case None                        =>
        val show = symbols.scope.get("Show").flatMap(symbols.membersOf(_).find(_.name == "show"))
        val body = show.filter(m => hasBody(t, m.id, fromPrelude = false)).flatMap(m =>
          dataOf(t).map((data, args) =>
            member(data, args, None)((a, _) => satisfactionBody(data, m.id, a, Type.UnitType).get)
          )
        )
        body.orElse(knownDerived(t, derivedShow).map(_ -> Nil)) match
          case Some((f, ws)) => Expr.Let(s, Rhs.Call(f, ws :+ v, runtime = false), k(s))
          case None          =>
            report(s"`show` on ${symbols.render(t)}")
            k(Atom.StrC(""))

  /** Whether `a` and `b` of type `t` are equal: units always, scalars by a comparison, else by
    * [[equality]]; a type it cannot compare is a problem (`B005`).
    */
  protected def equalValues(t: Type, a: Atom, b: Atom)(k: K): Expr =
    val r = fresh("eq", CType.Bool)
    if a.tpe == CType.Unit then k(Atom.BoolC(true))
    else if !a.tpe.counted then Expr.Let(r, Rhs.Prim(PrimOp.Eq, List(a, b)), k(r))
    else
      equality(t, a, b) match
        case Some(rhs) => Expr.Let(r, rhs, k(r))
        case None      =>
          report(s"`==` on ${symbols.render(t)}")
          k(Atom.BoolC(false))

  /** The function of the `satisfies` body that defines capability member `member` for `data`,
    * built at `data`'s arguments `args` and, for a member with a type parameter of its own, at `r`
    * (else `r` is ignored): it takes the witnesses of the type parameters these mention, `self`, then
    * the member's parameters. The capability's own parameters are the arguments the declaration
    * writes, where the member is the capability's own rather than inherited.
    */
  protected def satisfactionBody(data: SymbolId, member: SymbolId, args: List[Type], r: Type): Option[String] =
    satisfactionBodies.get((data, member)).map { b =>
      val m = symbols(member)
      val rs = symbols.typeParamsOf(member).map(_ -> r)
      val name = s"${symbols.render(Type.TypeRef(data, args))} satisfies ${symbols(b.cap).name}: ${b.fn.name}" +
        rs.map((_, t) => s"[${shown(t)}]").mkString
      val fn = b.fn
      instance(name) {
        val own = symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil))
        val self = Type.TypeRef(data, own)
        val capParams = if m.owner == b.cap then symbols.typeParamsOf(b.cap) else Nil
        val mapping = Members.Subst.of(capParams.takeRight(b.capArgs.size), b.capArgs)
        val sub = Members.Subst(mapping, Set(m.owner), Some(self), own.lastOption)
        val (declared, ret) = TypeOps.signature(Members.instantiate(unit.typing.get, m.info, sub)._2)
        val params = (("self", fn.span) -> self) ::
          fn.params.map(p => (p.name, p.span)).zip(declared)
        val sigma = symbols.typeParamsOf(data).zip(args).toMap ++ rs
        code(name, params, ret, fn, sigma, Map.empty, hidden(args ++ rs.map(_._2)))
      }
    }
