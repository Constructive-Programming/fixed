package fixed.typer

import scala.collection.immutable.{BitSet, IntMap}

import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.util.{DiagCode, Span}

/** Does a type satisfy a capability? `docs/plans/phase-3.1-capability-closure.md` §3.3–§3.4.
  *
  * `holds` answers for one capability reference `C of args`: through an in-scope `satisfies`
  * declaration whose capability's closure contains `C`, through auto-derivation (Rule 7.4), or
  * through a type variable's or anonymous bound's own capabilities. Capability arguments are
  * unified with what the satisfying type provides: the arguments its `satisfies` declaration
  * writes, or else its element arguments, right-aligned. The returned state carries those
  * bindings.
  */
object Satisfaction:

  private[typer] val primitives =
    Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool", "char")

  /** Capabilities `Rule 7.4` derives from a data type's fields. */
  private val fieldDerived = Set("Show", "Eq", "Clone", "Ord")

  /** `Clone` is derived only on request: a data type has it only through `T satisfies Clone`. */
  def onRequest(s: TyperState, cap: SymbolId): Boolean = s.symbols.preludeName(cap) == "Clone"

  /** Some capability in `bounds` reaches `cap` through its `extends` closure. */
  def boundsReach(s: TyperState, bounds: List[Type], cap: SymbolId): Boolean =
    Members.expandAliases(s.symbols, bounds).exists {
      case Type.CapRef(c, _) => reaches(s, c, cap)
      case _                 => false
    }

  /** `t` satisfies `cap of args`: the state with the capability's arguments unified, if it does.
    * `assumed` are the data types whose derivation of `cap` is being decided: met again inside
    * their own fields, they are taken to have it (recursion, direct or through other types).
    */
  def holds(s: TyperState, t: Type, cap: SymbolId, args: List[Type], assumed: Set[SymbolId] = Set.empty)
      : Option[TyperState] =
    s.unifier.resolve(t) match
      case Type.NeverType                     => Some(s)
      case Type.TupleType(ts) if ts.size == 2 =>
        s.symbols.prelude.get("Tuple").flatMap(tuple => holds(s, Type.TypeRef(tuple, ts), cap, args, assumed))
      case Type.TupleType(ts) if fieldDerived(s.symbols.preludeName(cap)) =>
        Option.when(ts.forall(holds(s, _, cap, Nil, assumed).isDefined))(s)
      case Type.TypeRef(id, _) if assumed(id) => Some(s)
      case Type.TypeRef(id, recvArgs)         =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.TypeParam => Option.when(boundsReach(s, Members.boundsOf(s, id), cap))(s)
          case SymKind.Data      =>
            /** A requested `Clone` holds at an instance whose arguments are `Clone` too. An argument
              * not solved yet is not refused for the bound it was instantiated with, which lists
              * what it must satisfy, not all it may satisfy.
              */
            def element(a: Type) = s.unifier.resolve(a) match
              case Type.TypeVar(_) => true
              case solved          => holds(s, solved, cap, Nil, assumed).isDefined
            val elements = !onRequest(s, cap) || recvArgs.forall(element)
            val self = Type.TypeRef(id, Nil)
            if declared(s, self, cap) && elements then withDeclared(s, self, recvArgs, cap, args)
            else if !onRequest(s, cap) && derived(s, id, cap, recvArgs, assumed) then withArgs(s, recvArgs, args)
            else None
          case _ => Some(s)
      case p @ Type.PrimType(n) =>
        val numeric = Members.isNumeric(s, p) && s.symbols.prelude.get("Numeric").exists(reaches(s, _, cap))
        val clone = primitives(n) && onRequest(s, cap)
        if numeric || clone then Some(s)
        else if declared(s, p, cap) then withDeclared(s, p, Nil, cap, args)
        else None
      case Type.IsBound(bs)       => Option.when(boundsReach(s, bs, cap))(s)
      case c @ Type.CapRef(_, _)  => Option.when(boundsReach(s, List(c), cap))(s)
      case Type.TypeVar(id)       => unsolved(s, id, cap)
      case Type.AppliedVar(id, _) => unsolved(s, id, cap)
      case _: Type.ArrowType      => None
      case _                      => Some(s)

  /** Check that `value` satisfies every entry of `bound` (an `is ...` chain or a capability):
    * capabilities through [[holds]], primitives and named types by unification; refinement calls
    * are prop obligations for M9. Failures are `T010`.
    */
  def discharge(s: TyperState, value: Type, bound: Type, span: Span): TyperState =
    entries(s, bound).foldLeft(s) { (si, entry) =>
      entry match
        case Type.CapRef(c, args)   => holds(si, value, c, args).getOrElse(fail(si, value, entry, span))
        case _: Type.RefinementType => si
        case named                  =>
          si.tryUnify(named, value).getOrElse(fail(si, value, entry, span))
    }

  /** `s` with the arguments of each capability in `bound` unified with what `value` provides,
    * where `value` satisfies it; nothing is reported. It runs before literal variables take their
    * defaults, so a satisfying type solves them: `Optional.some(7)` passed as a `Maybe of u64` holds a
    * `u64`, not an `i64`.
    */
  def adopt(s: TyperState, value: Type, bound: Type): TyperState =
    entries(s, bound).foldLeft(s) {
      case (si, Type.CapRef(c, args)) => holds(si, value, c, args).getOrElse(si)
      case (si, _)                    => si
    }

  /** [[discharge]] as a test, which reports nothing and keeps no binding. */
  def satisfies(s: TyperState, value: Type, bound: Type): Boolean =
    entries(s, bound).forall {
      case Type.CapRef(c, args)   => holds(s, value, c, args).isDefined
      case _: Type.RefinementType => true
      case named                  => s.tryUnify(named, value).isDefined
    }

  /** `T006`: type `t`, written at `span` as `D of args` (a data type, effect, capability or alias of
    * capabilities), gives `D` a number of arguments it does not take ([[arity]]). Else `T010`: it
    * has an argument that lacks a bound of `D`'s type parameter at its position, with the other
    * parameters in the bound replaced by their arguments. A type parameter as an argument has the
    * bounds it was declared with (`data W of A: W(b: Box of A)` needs `A is Numeric` when `Box`
    * does). Value arguments, literals or value parameters, are skipped.
    */
  def checkWritten(s: TyperState, t: Type, span: Span): TyperState =
    t match
      /** Rule 5.8: an alias's arguments are distributed, each capability taking what it declares. */
      case Type.TypeRef(head, _) if s.symbols(head).kind == SymKind.TypeAlias =>
        Members.expandAliases(s.symbols, List(t)).foldLeft(s) {
          case (si, d @ Applied(h, args)) => arguments(si, d, h, args, span)
          case (si, _)                    => si
        }
      case Applied(head, args) =>
        arity(s, head, args.size).fold(arguments(s, t, head, args, span))(s.error(DiagCode.T006, span, _))
      case _ => s

  /** `D of args`: a data type, effect or capability applied to its arguments. */
  private object Applied:
    def unapply(t: Type): Option[(SymbolId, List[Type])] = PartialFunction.condOpt(t) {
      case Type.TypeRef(head, args) => (head, args)
      case Type.CapRef(head, args)  => (head, args)
    }

  /** Why `count` arguments do not fit `head`'s `of` clause. A data type or effect takes its `of`
    * parameters, type and value, where trailing value parameters with a default may be left out
    * (Rule 7.3.a). A capability takes at most its parameters (Rule 5.4.b), fewer written directly
    * as through an alias (Rule 5.8), and one with no `of` clause its implicit `Part` (Rule 5.3).
    * A type parameter applied to arguments (`F of A`) is not checked.
    */
  private def arity(s: TyperState, head: SymbolId, count: Int): Option[String] =
    val d = s.symbols(head)
    s.symbols.arityOf(head).flatMap { (fewest, declared) =>
      val (least, most) = if d.kind == SymKind.Cap then (0, declared.max(1)) else (fewest, declared)
      lazy val takes = if least == most then s"$most" else if least == 0 then s"at most $most" else s"$least to $most"
      Option.when(count < least || count > most)(s"${d.name} takes $takes argument(s), given $count")
    }

  private def arguments(s: TyperState, t: Type, head: SymbolId, args: List[Type], span: Span): TyperState =
    val params = s.symbols.typeParamsOf(head)
    val types = args.filter {
      case _: Type.ValueArg => false
      case _                => true
    }
    val sub = Members.Subst(Members.Subst.of(params, types), Set(head))
    val r = s.symbols.render
    params.zip(types).foldLeft(s) { case (si, (p, a)) =>
      Members.boundsOf(si.symbols, p).foldLeft(si) { (sj, declared) =>
        val (sk, bound) = Members.instantiate(sj, declared, sub)
        lazy val message = s"${r(t)}: ${r(a)} does not satisfy ${r(bound)}, " +
          s"the bound of ${s.symbols(head).name}'s parameter ${s.symbols(p).name}"
        if satisfies(sk, a, bound) then sj else sj.error(DiagCode.T010, span, message)
      }
    }

  private def entries(s: TyperState, bound: Type): List[Type] =
    bound match
      case Type.IsBound(bs) => Members.expandAliases(s.symbols, bs)
      case other            => List(other)

  private def fail(s: TyperState, value: Type, entry: Type, span: Span): TyperState =
    val r = s.symbols.render
    val hint = entry match
      case Type.CapRef(c, _) => Members.notFunctor(s.symbols, s.unifier.zonk(value), c)
      case _                 => None

    /** Arguments not solved yet say nothing: `Ordering`, not `Ordering of ?2` (`Members.static`). */
    val shown = s.unifier.zonk(entry) match
      case Type.CapRef(c, args) if args.forall { case _: Type.TypeVar => true; case _ => false } => Type.CapRef(c, Nil)
      case other                                                                                 => other
    s.error(DiagCode.T010, span, s"${r(s.unifier.zonk(value))} does not satisfy ${r(shown)}", hint)

  /** Constructor resolution (spec §8.5, §8.5.1): `Cap.ctor(args)` against the expected type. The
    * candidates are the data types whose `satisfies` maps `ctor`; a candidate fits when it
    * satisfies every capability of the expected bound (refinement calls stripped) or is the
    * expected data type.
    *
    * - **One fits:** the call constructs that variant and is recorded in `constructors`.
    * - **Several fit:** `T011`.
    * - **None fits, or the expected type is unknown:** `None`, so the call stays abstract until
    *   representation selection. A type the abstract value meets must satisfy the capability
    *   (`Unifier`): `f(Ordering.gt)` where `f` takes a data type without `Ordering` is `T010`.
    */
  def constructor(s: TyperState, cap: SymbolId, call: fixed.ast.Trees.StaticCall, pt: fixed.core.Proto)
      : Option[(TyperState, Type)] =
    val expected = pt.expected.map(s.unifier.resolve)

    /** In the prelude's code, only the prelude's data types are candidates (Rule 8.6). */
    val candidates = s.symbols.constructorsOf(cap, call.method)
      .filter((data, _) => !s.inPrelude || s.symbols(data).owner == SymbolId.Prelude)
    def fits(data: SymbolId): Boolean =
      expected match
        case Some(Type.TypeRef(d, _)) if s.symbols(d).kind == SymKind.Data => d == data
        case Some(Type.IsBound(bs))                                        =>
          val (s1, args) = Members.freshArgs(s, data)
          Members.expandAliases(s.symbols, bs).forall {
            case Type.CapRef(c, cargs) => holds(s1, Type.TypeRef(data, args), c, cargs).isDefined
            case _                     => true
          }
        case Some(Type.CapRef(c, cargs)) =>
          val (s1, args) = Members.freshArgs(s, data)
          holds(s1, Type.TypeRef(data, args), c, cargs).isDefined
        case _ => false
    candidates.filter((data, _) => fits(data)) match
      case List((data, variant)) =>
        val arity =
          s.members.lookup(cap, SymKind.StaticMethod, call.method).headOption.fold(0)(e => Members.arity(e.member.info))
        Members.static(s.copy(constructors = (call, data, variant) :: s.constructors), data, variant, None).map {

          /** The call has the requirement's shape: defaulted trailing fields are filled in (Rule 8.1.a). */
          case (s1, Type.ArrowType(lhs, result, fx)) =>
            val fields = Members.params(lhs)
            if arity == 0 then (s1, result)
            else (s1, Type.ArrowType(Type.TupleType(fields.take(arity)), result, fx))
          case other => other
        }
      case Nil     => None
      case several =>
        val names = several.map((d, v) => s"${s.symbols(d).name}.$v").mkString(", ")
        Some((
          s.error(
            DiagCode.T011,
            call.span,
            s"ambiguous satisfaction for ${s.symbols(cap).name}.${call.method}: $names"
          ),
          Type.NeverType
        ))

  /** An unsolved variable satisfies what its instantiation bound reaches; an unbounded one, anything. */
  private def unsolved(s: TyperState, id: Int, cap: SymbolId): Option[TyperState] =
    val bounds = s.unifier.boundsOf(id)
    Option.when(bounds.isEmpty || boundsReach(s, bounds, cap))(s)

  /** A `t satisfies C'` with `cap` in `closure(C')`. */
  private def declared(s: TyperState, t: Type, cap: SymbolId): Boolean =
    boundsReach(s, s.symbols.satisfactionsOf(t), cap)

  /** Data type `data` provides capability member `m` without a body: the derived `fold` (Rule
    * 7.4.a), for a `fold` that takes one function per variant; the accessor of a single-variant
    * data type's field (Rule 7.4.d), for a parameterless member whose result type the field has; a
    * member of `Functor`, `Show`, `Eq`, `Clone` or `Ord` when Rule 7.4 derives that capability.
    */
  def derives(s: TyperState, data: SymbolId, m: fixed.core.Denotation): Boolean =
    val variants = s.symbols.variantsOf(data).toList
    val own = s.symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil))
    val sub =
      Members.Subst(Members.Subst.of(Nil, Nil), Set(m.id, m.owner), Some(Type.TypeRef(data, own)), own.lastOption)
    val (s1, sig) = Members.instantiate(s, m.info, sub)
    val result = sig match
      case Type.ArrowType(_, r, _) => r
      case value                   => value
    val params = Members.paramTypes(sig)
    val fold = m.name == "fold" && params.size == variants.size && params.forall {
      case _: Type.ArrowType => true
      case _                 => false
    }
    val accessor = params.isEmpty && variants.sizeIs == 1 &&
      s.symbols.fieldsOf(variants.head.id).exists(f => f.name == m.name && s1.tryUnify(result, f.info).isDefined)

    /** `!` satisfies every capability: the parameters are assumed to have it, so the question is
      * whether some instance derives it.
      */
    val anyInstance = s.symbols.typeParamsOf(data).map(_ => Type.NeverType)
    fold || accessor ||
    (s.symbols.preludeName(m.owner) != "Folding" && derived(s, data, m.owner, anyInstance, Set.empty))

  /** Rule 7.4: `Folding` for any data with variants, `Functor` over the rightmost type parameter
    * when a derived `map` can rebuild it (`Members.functor`), and `Show` / `Eq` / `Clone` / `Ord`
    * when every field type has it (recursive fields assumed). A capability with `Self.fn`
    * requirements is never derived.
    *
    * A field's type is read at the instance's arguments `args`, so `List of T` has `Show` only if
    * `T` has it. The shape test alone: it ignores declarations and `Clone`'s request ([[holds]]
    * applies both).
    */
  def derived(s: TyperState, data: SymbolId, cap: SymbolId, args: List[Type], assumed: Set[SymbolId]): Boolean =
    val capName = s.symbols.preludeName(cap)
    lazy val variants = s.symbols.variantsOf(data).toList
    capName match
      case "Folding"                  => variants.nonEmpty
      case "Functor"                  => Members.functor(s.symbols, data)
      case name if fieldDerived(name) =>
        variants.forall { v =>
          val (s1, types) = Members.fieldTypes(s, data, args, v.id)
          types.forall(holds(s1, _, cap, Nil, assumed + data).isDefined)
        }
      case _ => false

  /** Unify the obligation's capability arguments with the satisfying type's element arguments. */
  private def withArgs(s: TyperState, recvArgs: List[Type], capArgs: List[Type]): Option[TyperState] =
    if capArgs.isEmpty then Some(s)
    else
      val provided = recvArgs.takeRight(capArgs.size)
      if provided.size < capArgs.size then Some(s)
      else
        provided.zip(capArgs).foldLeft(Option(s)) { case (acc, (p, a)) =>
          acc.flatMap(_.tryUnify(a, p))
        }

  /** [[withArgs]] through the declared satisfactions of `self` that reach `cap`, `recvArgs` being
    * the receiver's arguments. One that writes `cap`'s arguments, on its `satisfies` clause or on
    * the `extends` clauses through which it reaches `cap`, provides those: `Bytes satisfies Sized
    * of (u8, u16)` does not satisfy `Sized of (u8, u64)`. One that writes none provides the
    * receiver's arguments. The obligation holds if it holds through one of them.
    */
  private def withDeclared(s: TyperState, self: Type, recvArgs: List[Type], cap: SymbolId, capArgs: List[Type])
      : Option[TyperState] =
    val dataParams = self match
      case Type.TypeRef(d, _) => s.symbols.typeParamsOf(d).map(s.symbols(_).name).zip(recvArgs).toMap
      case _                  => Map.empty[String, Type]
    def introduced(t: Type): List[SymbolId] = t match
      case Type.TypeRef(id, as) if s.symbols(id).kind == SymKind.TypeParam => id :: as.flatMap(introduced)
      case other                                                           => other.children.flatMap(introduced)

    /** The Namer owns a `satisfies` clause's type variables at the root (or the prelude): each is
      * the data type's parameter of the same name, or else fresh.
      */
    def provided(clause: Type.CapRef): (TyperState, List[Type]) =
      val named = introduced(clause).flatMap(p => dataParams.get(s.symbols(p).name).map(p.toInt -> _))
      val sub = Members.Subst(IntMap.from(named), Set(SymbolId.Root, SymbolId.Prelude), part = recvArgs.lastOption)
      val (s1, at) = Members.instantiate(s, clause, sub)
      reaching(s1, clause.sym, at.children, cap, recvArgs, BitSet.empty).getOrElse((s, recvArgs))
    if capArgs.isEmpty then Some(s)
    else
      Members.expandAliases(s.symbols, s.symbols.satisfactionsOf(self)).iterator
        .collect { case clause @ Type.CapRef(c, _) if reaches(s, c, cap) => provided(clause) }
        .flatMap((si, args) => withArgs(si, args, capArgs))
        .nextOption()

  /** The arguments `c of args` gives `cap`, itself or through the `extends` clauses of capabilities
    * not in `seen`, if they are written. Where `c`'s arguments are not, they are the receiver's
    * (`recvArgs`, right-aligned), or fresh where it has too few.
    */
  private def reaching(
      s: TyperState,
      c: SymbolId,
      args: List[Type],
      cap: SymbolId,
      recvArgs: List[Type],
      seen: BitSet
  ): Option[(TyperState, List[Type])] =
    if c == cap then Option.when(args.nonEmpty)((s, args))
    else
      val params = s.symbols.typeParamsOf(c)
      val filled = if args.nonEmpty || recvArgs.size < params.size then args else recvArgs.takeRight(params.size)
      val sub = Members.Subst(Members.Subst.of(params, filled), Set(c), part = recvArgs.lastOption)
      val supers = s.symbols(c).info match
        case Type.IsBound(bs) => Members.expandAliases(s.symbols, bs)
        case _                => Nil
      supers.iterator
        .collect { case sup @ Type.CapRef(id, _) if reaches(s, id, cap) && !seen(id.toInt) => sup }
        .flatMap { sup =>
          val (s1, at) = Members.instantiate(s, sup, sub)
          reaching(s1, sup.sym, at.children, cap, recvArgs, seen + c.toInt)
        }
        .nextOption()

  /** `c`'s `extends` closure contains `cap`. */
  private def reaches(s: TyperState, c: SymbolId, cap: SymbolId): Boolean = s.members.closure(c).contains(cap.toInt)
