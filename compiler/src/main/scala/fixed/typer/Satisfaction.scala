package fixed.typer

import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.util.{DiagCode, Span}

/** Does a type satisfy a capability? `docs/plans/phase-3.1-capability-closure.md` §3.3–§3.4.
  *
  * `holds` answers for one capability reference `C of args`: through an in-scope `satisfies`
  * declaration whose capability's closure contains `C`, through auto-derivation (Rule 7.4), or
  * through a type variable's or anonymous bound's own capabilities. Capability arguments are
  * unified with what the satisfying type provides (its element arguments, right-aligned); the
  * returned state carries those bindings.
  */
object Satisfaction:

  private val primitives = Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool", "char")

  /** Capabilities `Rule 7.4` derives from a data type's fields. */
  private val fieldDerived = Set("Show", "Eq", "Clone", "Ord")

  /** `Clone` is derived only on request: a data type has it only through `T satisfies Clone`. */
  def onRequest(s: TyperState, cap: SymbolId): Boolean = s.symbols(cap).name == "Clone"

  /** Some capability in `bounds` reaches `cap` through its `extends` closure. */
  def boundsReach(s: TyperState, bounds: List[Type], cap: SymbolId): Boolean =
    Members.expandAliases(s.symbols, bounds).exists {
      case Type.CapRef(c, _) => s.members.closure(c).contains(cap.toInt)
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
        s.symbols.scope.get("Tuple").flatMap(tuple => holds(s, Type.TypeRef(tuple, ts), cap, args, assumed))
      case Type.TupleType(ts) if fieldDerived(s.symbols(cap).name) =>
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
            if declared(s, Type.TypeRef(id, Nil), cap) && elements ||
              (!onRequest(s, cap) && derived(s, id, cap, recvArgs, assumed))
            then
              withArgs(s, recvArgs, args)
            else None
          case _ => Some(s)
      case Type.PrimType(n) =>
        val numeric = Members.isNumeric(s, Type.PrimType(n)) &&
          s.symbols.scope.get("Numeric").exists(nu => s.members.closure(nu).contains(cap.toInt))
        val clone = primitives(n) && onRequest(s, cap)
        Option.when(numeric || clone || declared(s, Type.PrimType(n), cap))(s)
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

  /** [[discharge]] as a test, which reports nothing and keeps no binding. */
  def satisfies(s: TyperState, value: Type, bound: Type): Boolean =
    entries(s, bound).forall {
      case Type.CapRef(c, args)   => holds(s, value, c, args).isDefined
      case _: Type.RefinementType => true
      case named                  => s.tryUnify(named, value).isDefined
    }

  private def entries(s: TyperState, bound: Type): List[Type] =
    bound match
      case Type.IsBound(bs) => Members.expandAliases(s.symbols, bs)
      case other            => List(other)

  private def fail(s: TyperState, value: Type, entry: Type, span: Span): TyperState =
    val r = s.symbols.render
    s.error(DiagCode.T010, span, s"${r(s.unifier.zonk(value))} does not satisfy ${r(s.unifier.zonk(entry))}")

  /** Constructor resolution (spec §8.5, §8.5.1): `Cap.ctor(args)` against the expected type. The
    * candidates are the data types whose `satisfies` maps `ctor`; a candidate fits when it
    * satisfies every capability of the expected bound (refinement calls stripped) or is the
    * expected data type.
    *
    * - **One fits:** the call constructs that variant and is recorded in `constructors`.
    * - **Several fit:** `T011`.
    * - **None fits, or the expected type is unknown:** `None`, so the call stays abstract until
    *   representation selection.
    */
  def constructor(s: TyperState, cap: SymbolId, call: fixed.ast.Trees.StaticCall, pt: fixed.core.Proto)
      : Option[(TyperState, Type)] =
    val expected = pt.expected.map(s.unifier.resolve)
    val candidates = s.symbols.constructorsOf(cap, call.method)
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
    fold || accessor || (s.symbols(m.owner).name != "Folding" && derived(s, data, m.owner, anyInstance, Set.empty))

  /** Rule 7.4: `Folding` for any data with variants, `Functor` over the rightmost type parameter
    * (covariant, never contravariant), and `Show` / `Eq` / `Clone` / `Ord` when every field type
    * has it (recursive fields assumed). A capability with `Self.fn` requirements is never derived.
    *
    * A field's type is read at the instance's arguments `args`, so `List of T` has `Show` only if
    * `T` has it. The shape test alone: it ignores declarations and `Clone`'s request ([[holds]]
    * applies both).
    */
  def derived(s: TyperState, data: SymbolId, cap: SymbolId, args: List[Type], assumed: Set[SymbolId]): Boolean =
    val capName = s.symbols(cap).name
    val params = s.symbols.typeParamsOf(data)
    lazy val variants = s.symbols.variantsOf(data).toList
    lazy val fields = variants.flatMap(v => s.symbols.fieldsOf(v.id).map(_.info))
    capName match
      case "Folding" => variants.nonEmpty
      case "Functor" =>
        params.lastOption.exists { p =>
          fields.exists(occurs(_, p, positive = true)) && !fields.exists(occurs(_, p, positive = false))
        }
      case name if fieldDerived(name) =>
        variants.forall { v =>
          val (s1, types) = Members.fieldTypes(s, data, args, v.id)
          types.forall(holds(s1, _, cap, Nil, assumed + data).isDefined)
        }
      case _ => false

  /** `p` occurs in `t` at the given polarity (function parameters flip it). */
  private def occurs(t: Type, p: SymbolId, positive: Boolean): Boolean =
    t match
      case Type.TypeRef(`p`, _)    => positive
      case Type.ArrowType(l, r, _) =>
        occurs(l, p, !positive) || occurs(r, p, positive)
      case other => other.children.exists(occurs(_, p, positive))

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
