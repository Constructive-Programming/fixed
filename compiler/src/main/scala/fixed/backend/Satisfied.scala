package fixed.backend

import fixed.ast.Trees
import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.typer.{Members, TyperPhase}

/** The method bodies of `satisfies` declarations (`spec/type_system.md` §8.3), lowered as functions
  * of their own: a call of the member on a data or scalar type that has one, and the member's entry in that
  * type's capability table, run the body. The program's bodies take the place of what the compiler
  * would generate; the prelude's fill only members it generates nothing for, since a generated
  * `fold`, `length` or `find` on a list computes what the prelude's body computes, in a loop.
  * Since prelude bodies are lowered in the prelude's scope, the trait also holds which top-level
  * scope the code being lowered runs in, for all of `Lower`.
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

  /** Whether the code being lowered is the prelude's: a prelude function or `satisfies` body. */
  protected lazy val inPrelude = scala.util.DynamicVariable(false)

  /** The top-level scope of the code being lowered. */
  protected def globals: Map[String, SymbolId] = symbols.scopeFor(inPrelude.value)

  /** The first capability of the type the typer gave `t`, aliases expanded. */
  protected def capOf(t: fixed.ast.Tree): Option[SymbolId] =
    Members.expandAliases(
      symbols,
      unit.typeOf(t) match
        case Some(Type.IsBound(bs)) => bs
        case other                  => other.toList
    ).collectFirst { case Type.CapRef(c, _) => c }

  /** A typed `satisfies` method body, its capability's arguments as declared, and whether it is the prelude's. */
  private case class Body(cap: SymbolId, capArgs: List[Type], fn: Trees.InstanceMethod, prelude: Boolean)

  /** The bodies by satisfying type (a data type at no arguments, or a scalar type) and the
    * capability member each defines; a program body replaces a prelude body for the same pair.
    */
  private lazy val satisfactionBodies: Map[(Type, SymbolId), Body] =
    val own = unit.tree.toList.flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
    val prelude = TyperPhase.preludeSatisfactions
    (for
      typing <- unit.typing.toList
      case d @ Trees.SatisfiesDecl(typeName, Trees.CapRef(capName, _, _), methods, _) <- prelude ++ own
      fromPrelude = prelude.exists(_ eq d)
      visible = symbols.scopeFor(fromPrelude)
      self <- visible.get(typeName).filter(symbols(_).kind == SymKind.Data).map(Type.TypeRef(_, Nil))
        .orElse(Some(Type.PrimType(typeName)).filter(Lower.scalar(_).isDefined)).toList
      cap <- visible.get(capName).filter(symbols(_).kind == SymKind.Cap).toList
      capArgs = symbols.satisfactionsOf(self).collectFirst { case Type.CapRef(`cap`, as) => as }
      case fn: Trees.InstanceMethod <- methods if fn.body.isDefined
      entry <- typing.members.lookup(cap, SymKind.Method, fn.name).headOption.toList
    yield (self, entry.member.id) -> Body(cap, capArgs.getOrElse(Nil), fn, fromPrelude)).toMap

  /** The type `satisfies` bodies of a value of type `t` are keyed by: its data type at no arguments, or a scalar type. */
  protected def satisfier(t: Type): Option[Type] =
    dataOf(t).map((d, _) => Type.TypeRef(d, Nil)).orElse(Option.when(Lower.scalar(t).isDefined)(t))

  /** Whether `t` has a `satisfies` body for `member` that is the prelude's exactly when `fromPrelude`
    * and that the compiler does not provide in its place on a scalar (`Tables.builtIn`).
    */
  protected def hasBody(t: Type, member: SymbolId, fromPrelude: Boolean): Boolean =
    satisfier(t).exists(s => satisfactionBodies.get((s, member)).exists(_.prelude == fromPrelude)) &&
      !builtIn(t, member)

  /** A call of `member`'s body on a value of type `t` that does not go through a generated member
    * ([[member]]): on a scalar type, or for a member with several type parameters of its own, which
    * the call's argument and result types fix. Returns the function and its hidden arguments.
    */
  protected def directBody(
      t: Type,
      member: SymbolId,
      argTypes: List[Type],
      result: Type
  ): Option[(String, List[Atom])] =
    val own = symbols.typeParamsOf(member)
    if dataOf(t).isDefined && own.sizeIs <= 1 then None
    else
      val rs = own.map(ownTypeArgs(member, argTypes, result).getOrElse(_, Type.UnitType))
      val args = dataOf(t).fold(Nil)(_._2)
      satisfier(t).flatMap(satisfactionBody(_, member, args, rs)).map(_ -> bodyHidden(args ++ rs))

  /** Whether `t` is the built-in `String`, which `==` compares in the runtime. */
  protected def isStringType(t: Type): Boolean =
    t match
      case Type.TypeRef(id, Nil) => symbols.prelude.get("String").contains(id)
      case _                     => false

  /** `l == r` on boxed values of type `t`: the runtime's string comparison for `String`, the body of
    * the program's `t satisfies Eq` for a data type, else its derived `eq` ([[derivedEq]]).
    */
  protected def equality(t: Type, l: Atom, r: Atom): Option[Rhs] =
    if isStringType(t) then Some(Rhs.Call("fx_str_eq", List(l, r), runtime = true))
    else bodyOrDerived(t, "Eq", "eq", derivedEq).map((f, ws) => Rhs.Call(f, ws ++ List(l, r), runtime = false))

  /** The derived member `gen` builds for data type `t` (Rule 7.4), with the hidden arguments to call
    * it with: none at known arguments; else those of the type parameters `t` mentions, whose tables
    * reach a field of a parameter's type.
    */
  protected def derived(t: Type, gen: (SymbolId, List[Type]) => String): Option[(String, List[Atom.Var])] =
    dataOf(t).map((data, args) => gen(data, args) -> typeParamsIn(t).flatMap(hiddenOf))

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
        bodyOrDerived(t, "Show", "show", derivedShow) match
          case Some((f, ws)) => Expr.Let(s, Rhs.Call(f, ws :+ v, runtime = false), k(s))
          case None          =>
            throughTable(t, "Show", "show", List(v), CType.Boxed)(k).getOrElse {
              report(s"`show` on ${symbols.render(t)}")
              k(Atom.StrC(""))
            }

  /** The function of member `cap.name` for data type `t`, with its hidden arguments: the program's
    * `satisfies` body, else the member `gen` derives.
    */
  private def bodyOrDerived(t: Type, cap: String, name: String, gen: (SymbolId, List[Type]) => String) =
    val m = symbols.prelude.get(cap).flatMap(symbols.membersOf(_).find(_.name == name))
    val body = m.filter(m => hasBody(t, m.id, fromPrelude = false)).flatMap(m =>
      dataOf(t).map((data, args) => member(data, args, None)(dataBody(m.id)(data, _, _)))
    )
    body.orElse(derived(t, gen))

  /** `v` of type `t` cloned (`spec/stdlib.md` §4.11): a scalar, `()` or a `String` is the value itself
    * (values are immutable); a data type by the program's `satisfies Clone` body or else its derived
    * `clone`, a type parameter through its table; anything else is a problem (`B005`).
    */
  protected def cloneValue(t: Type, v: Atom)(k: K): Expr =
    if !v.tpe.counted || isStringType(t) then k(v)
    else
      bodyOrDerived(t, "Clone", "clone", derivedClone) match
        case Some((f, ws)) =>
          val c = fresh("c", v.tpe)
          Expr.Let(c, Rhs.Call(f, ws :+ v, runtime = false), k(c))
        case None =>
          throughTable(t, "Clone", "clone", List(v), v.tpe)(k).getOrElse {
            report(s"`clone` on ${symbols.render(t)}")
            k(v)
          }

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
          throughTable(t, "Eq", "eq", List(a, b), CType.Bool)(k).getOrElse {
            report(s"`==` on ${symbols.render(t)}")
            k(Atom.BoolC(false))
          }

  /** Member `member` of capability `cap` called on `args`, values of type parameter `t`, through
    * its table of `cap`; `None` if `t` is not a type parameter or has no table of `cap` (its bound
    * set, or the capability a table built at run time assumes for it, `Tables.assumed`).
    */
  private def throughTable(t: Type, cap: String, member: String, args: List[Atom], rt: CType)(k: K): Option[Expr] =
    boundTable(t, cap, member).map { (table, i) =>
      val r = fresh("r", rt)
      Expr.Let(r, Rhs.CallTable(table, i, args), k(r))
    }

  /** The table of type parameter `t`'s bound set that holds the prelude capability `cap`'s
    * `member` (its own, or one whose capability extends `cap`), and the member's index in it.
    */
  private def boundTable(t: Type, cap: String, member: String): Option[(Atom, Int)] =
    for
      c <- symbols.prelude.get(cap) if isTypeParam(t)
      holder <- boundCaps(t).find(Tables.reached(symbols, _).contains(c))
    yield (tableOf(t, holder), Tables.members(symbols, holder).indexOf(member))

  /** The `satisfies` body of `member` for data type `d` at `args` and own type argument `r` (a generator for `member`). */
  protected def dataBody(member: SymbolId)(d: SymbolId, args: List[Type], r: Type): String =
    satisfactionBody(Type.TypeRef(d, Nil), member, args, List(r)).get

  /** The `compare` of values of data type `t` and its hidden arguments: the program's `satisfies
    * Ord` body, else the derived one ([[derivedCompare]]). Its result is the prelude's `Order` (P6 D17).
    */
  protected def compareOf(t: Type): Option[(String, List[Atom.Var])] =
    val compare = symbols.prelude.get("Ord").flatMap(symbols.membersOf(_).find(_.name == "compare")).map(_.id)
    dataOf(t).flatMap((data, args) =>
      compare.filter(hasBody(t, _, fromPrelude = false)) match
        case Some(c) => satisfactionBody(Type.TypeRef(data, Nil), c, args, Nil).map(_ -> bodyHidden(args))
        case None    => derived(t, derivedCompare)
    )

  /** `l op r` for a comparison operator `op` on values of data type `t` by its `compare`
    * ([[comparison]]): `>` and `>=` are `lt` and `le` with the operands swapped.
    */
  protected def ordered(t: Type, op: Trees.BinOperator, l: Atom, r: Atom): Option[Rhs] =
    import Trees.BinOperator.*
    val chosen = op match
      case Lt => Some(("lt", l, r))
      case Le => Some(("le", l, r))
      case Gt => Some(("lt", r, l))
      case Ge => Some(("le", r, l))
      case _  => None
    for
      (entry, a, b) <- chosen
      (f, ws) <- comparison(t, entry)
    yield Rhs.Call(f, ws ++ List(a, b), runtime = false)

  /** `lt` or `le` (`op`) on values of data type `t` (the hidden table entries of `Tables.ordEntries`)
    * by its `compare` ([[compareOf]]), read as `Less` for `lt` and as not `Greater` for `le`; with
    * the hidden arguments to call it with, which its name lists.
    */
  protected def comparison(t: Type, op: String): Option[(String, List[Atom.Var])] =
    compareOf(t).map { (f, ws) =>
      val name = s"${symbols.render(t)}.$op[compare][${ws.map(_.name).mkString(", ")}]"
      instance(name) {
        val (a, b, got) = (fresh("a", CType.Boxed), fresh("b", CType.Boxed), fresh("order", CType.Boxed))
        val (res, holds) = (fresh("holds", CType.Bool), (v: String) => v == "Less" || (op == "le" && v == "Equal"))
        val cases = orderVariants.zipWithIndex.map((v, tag) => tag -> Expr.Ret(Atom.BoolC(holds(v))))
        val body = Expr.Let(res, Rhs.Switch(got, cases), Expr.Ret(res))
        Fn(name, ws ++ List(a, b), CType.Bool, Expr.Let(got, Rhs.Call(f, ws ++ List(a, b), runtime = false), body))
      } -> ws
    }

  /** `k` given the `Order` that compares `a` with `b`, values of type `t`: units are equal; scalars
    * are compared (`Less` iff ordered-less, `Equal` iff ordered-equal, else `Greater`, so a NaN is
    * `Greater`, as `Tables.ordEntries` says), strings the same way by [[stringOrder]], and a type
    * parameter's values through its table's `lt` and `le`; a data type's by its `compare`; any
    * other type is a problem (`B005`).
    */
  protected def compareValues(t: Type, a: Atom, b: Atom)(k: K): Expr =
    def returned(v: String): Expr = order(v)(Expr.Ret(_))
    def choose(test: Rhs, yes: Expr, no: Expr)(k: K): Expr =
      val (flag, c) = (fresh("cmp", CType.Bool), fresh("order", CType.Boxed))
      Expr.Let(flag, test, Expr.Let(c, Rhs.If(flag, yes, no), k(c)))
    def byTests(lt: Rhs, le: Rhs): Expr =
      choose(lt, returned("Less"), choose(le, returned("Equal"), returned("Greater"))(Expr.Ret(_)))(k)
    if a.tpe == CType.Unit then order("Equal")(k)
    else if !a.tpe.counted then byTests(Rhs.Prim(PrimOp.Lt, List(a, b)), Rhs.Prim(PrimOp.Le, List(a, b)))
    else if isStringType(t) then
      stringOrder(a, b)(c => byTests(Rhs.Prim(PrimOp.Lt, List(c, zeroOrder)), Rhs.Prim(PrimOp.Le, List(c, zeroOrder))))
    else
      (boundTable(t, "Ord", "lt"), boundTable(t, "Ord", "le")) match
        case (Some((tb, lt)), Some((_, le))) =>
          byTests(Rhs.CallTable(tb, lt, List(a, b)), Rhs.CallTable(tb, le, List(a, b)))
        case _ =>
          compareOf(t) match
            case Some((f, ws)) =>
              val c = fresh("order", CType.Boxed)
              Expr.Let(c, Rhs.Call(f, ws ++ List(a, b), runtime = false), k(c))
            case None =>
              report(s"`compare` on ${symbols.render(t)}")
              order("Equal")(k)

  /** `k` given the order of strings `a` and `b` (`fx_str_cmp`, which consumes both): negative, zero
    * or positive as `a` is before, equal to or after `b`, bytewise, a prefix before the longer string.
    */
  protected def stringOrder(a: Atom, b: Atom)(k: Atom.Var => Expr): Expr =
    val c = fresh("cmp", zeroOrder.tpe)
    Expr.Let(c, Rhs.Call("fx_str_cmp", List(a, b), runtime = true), k(c))

  /** Zero as the type of [[stringOrder]]'s result. */
  protected val zeroOrder: Atom = Atom.IntC(0, CType.Int(32, signed = true))

  /** The function of the `satisfies` body that defines capability member `member` for satisfying
    * type `head` (a data type at no arguments, or a scalar type), built at the data type's
    * arguments `args` and at `types`, one per type parameter of the member's own: it takes the
    * witnesses and tables of the type parameters these mention ([[bodyHidden]]), `self`, then the
    * member's parameters. The
    * capability's own parameters are the arguments the declaration writes, where the member is the
    * capability's own rather than inherited.
    */
  protected def satisfactionBody(head: Type, member: SymbolId, args: List[Type], types: List[Type]): Option[String] =
    satisfactionBodies.get((head, member)).map { b =>
      val m = symbols(member)
      require(types.size >= symbols.typeParamsOf(member).size, s"${m.name} needs a type per own type parameter")
      val rs = symbols.typeParamsOf(member).zip(types)
      val (data, ownIds) = head match
        case Type.TypeRef(d, _) => (Some(d), symbols.typeParamsOf(d))
        case _                  => (None, Nil)
      val at = data.fold(head)(Type.TypeRef(_, args))
      val name = s"${symbols.render(at)} satisfies ${symbols(b.cap).name}: ${b.fn.name}" +
        rs.map((_, t) => s"[${shown(t)}]").mkString
      val fn = b.fn
      instance(name) {
        val own = ownIds.map(Type.TypeRef(_, Nil))
        val self = data.fold(head)(Type.TypeRef(_, own))
        val capParams = if m.owner == b.cap then symbols.typeParamsOf(b.cap) else Nil
        val mapping = Members.Subst.of(capParams.takeRight(b.capArgs.size), b.capArgs)
        val sub = Members.Subst(mapping, Set(m.owner), Some(self), own.lastOption)
        val (declared, ret) = TypeOps.signature(Members.instantiate(unit.typing.get, m.info, sub)._2)
        val params = (("self", fn.span) -> self) ::
          fn.params.map(p => (p.name, p.span)).zip(declared)
        val sigma = ownIds.zip(args).toMap ++ rs
        inPrelude.withValue(b.prelude)(code(name, params, ret, fn, sigma, Map.empty, bodyHidden(args ++ rs.map(_._2))))
      }
    }
