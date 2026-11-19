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

  /** Some capability in `bounds` reaches `cap` through its `extends` closure. */
  def boundsReach(s: TyperState, bounds: List[Type], cap: SymbolId): Boolean =
    Members.expandAliases(s.symbols, bounds).exists {
      case Type.CapRef(c, _) => s.members.closure(c).contains(cap.toInt)
      case _                 => false
    }

  /** `t` satisfies `cap of args`: the state with the capability's arguments unified, if it does. */
  def holds(s: TyperState, t: Type, cap: SymbolId, args: List[Type]): Option[TyperState] =
    s.unifier.resolve(t) match
      case Type.NeverType                     => Some(s)
      case Type.TupleType(ts) if ts.size == 2 =>
        s.symbols.scope.get("Tuple").flatMap(tuple => holds(s, Type.TypeRef(tuple, ts), cap, args))
      case Type.TypeRef(id, recvArgs) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.TypeParam => Option.when(boundsReach(s, Members.boundsOf(s, id), cap))(s)
          case SymKind.Data      =>
            if declared(s, d.name, cap) || derived(s, id, cap) then withArgs(s, recvArgs, args) else None
          case _ => Some(s)
      case Type.PrimType(n) =>
        val numeric = Members.isNumeric(s, Type.PrimType(n)) &&
          s.symbols.scope.get("Numeric").exists(nu => s.members.closure(nu).contains(cap.toInt))
        val clone = primitives(n) && s.symbols(cap).name == "Clone"
        Option.when(numeric || clone || declared(s, n, cap))(s)
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
    val entries = bound match
      case Type.IsBound(bs) => Members.expandAliases(s.symbols, bs)
      case other            => List(other)
    entries.foldLeft(s) { (si, entry) =>
      entry match
        case Type.CapRef(c, args)   => holds(si, value, c, args).getOrElse(fail(si, value, entry, span))
        case _: Type.RefinementType => si
        case named                  =>
          si.unifier.unify(named, value, Members.isNumeric(si, _)) match
            case Right(u) => si.copy(unifier = u.copy(obligations = Nil))
            case Left(_)  => fail(si, value, entry, span)
    }

  private def fail(s: TyperState, value: Type, entry: Type, span: Span): TyperState =
    val r = s.symbols.render
    s.error(DiagCode.T010, span, s"${r(s.unifier.zonk(value))} does not satisfy ${r(s.unifier.zonk(entry))}")

  /** An unsolved variable satisfies what its instantiation bound reaches; an unbounded one, anything. */
  private def unsolved(s: TyperState, id: Int, cap: SymbolId): Option[TyperState] =
    val bounds = s.unifier.boundsOf(id)
    Option.when(bounds.isEmpty || boundsReach(s, bounds, cap))(s)

  /** An in-scope `name satisfies C'` with `cap` in `closure(C')`. */
  private def declared(s: TyperState, name: String, cap: SymbolId): Boolean =
    boundsReach(s, s.symbols.satisfies.getOrElse(name, Nil), cap)

  /** Rule 7.4: `Folding` for any data with variants, `Functor` over the rightmost type parameter
    * (covariant, never contravariant), and `Show` / `Eq` / `Clone` / `Ord` when every field type
    * has it (recursive fields assumed). A capability with `Self.fn` requirements is never derived.
    */
  private def derived(s: TyperState, data: SymbolId, cap: SymbolId): Boolean =
    val capName = s.symbols(cap).name
    lazy val variants = s.symbols.variantsOf(data).toList
    lazy val fields = variants.flatMap(v => s.symbols.fieldsOf(v.id).map(_.info))
    capName match
      case "Folding" => variants.nonEmpty
      case "Functor" =>
        s.symbols.typeParamsOf(data).lastOption.exists { p =>
          fields.exists(occurs(_, p, positive = true)) && !fields.exists(occurs(_, p, positive = false))
        }
      case name if fieldDerived(name) =>
        fields.forall {
          case Type.TypeRef(`data`, _)                                                                       => true
          case Type.TypeRef(id, _) if s.symbols(id).kind == SymKind.TypeParam && s.symbols(id).owner == data => true
          case f => holds(s, f, cap, Nil).isDefined
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
          acc.flatMap(si => si.unifier.unify(a, p, Members.isNumeric(si, _)).toOption.map(u => si.copy(unifier = u)))
        }
