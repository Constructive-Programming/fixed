package fixed.core

/** The typer's internal view of a type (`docs/plans/phase-3-typer.md` §3.3). Names resolve to
  * [[SymbolId]]s, so a `Type` is meaningful only together with its [[SymbolTable]].
  */
enum Type:
  case PrimType(name: String)
  case UnitType
  case NeverType
  case ArrowType(lhs: Type, rhs: Type, fx: EffectRow)

  /** A multi-parameter arrow left-hand side `(A, B)`. */
  case TupleType(elems: List[Type])

  /** `is C + D`; also a type variable's bound, a cap's `extends` chain or an alias chain `u16 + between(1, 9)`. */
  case IsBound(bounds: List[Type])

  /** `cap of T`, `cap extends C` (as `Some(IsBound(...))`) or bare `cap`. */
  case CapType(retType: Option[Type])

  /** A resolved data, type-alias, effect, import or type-variable reference. */
  case TypeRef(sym: SymbolId, args: List[Type])
  case CapRef(sym: SymbolId, args: List[Type])
  case SelfType(of: Option[Type])
  case PartType

  /** `between(0, hi)`; value arguments stay symbolic for the prop verifier (M9). */
  case RefinementType(generator: SymbolId, valueArgs: List[SymbolicTerm])

  /** A compile-time value in an `of` list: the `0` of `Bounded of (N, 0, 10)`. */
  case ValueArg(term: SymbolicTerm)

  /** One layer of `data`, recursive fields holding `seed`: a derived `unfold` step's result. */
  case Layer(data: SymbolId, args: List[Type], seed: Type)

  case TypeVar(id: Int)

  /** A type-constructor variable applied to arguments (`M of B` while `M` is unsolved). */
  case AppliedVar(id: Int, args: List[Type])

  /** The types this one is built from. */
  def children: List[Type] =
    this match
      case ArrowType(l, r, _)   => List(l, r)
      case TupleType(ts)        => ts
      case IsBound(bs)          => bs
      case CapType(r)           => r.toList
      case TypeRef(_, args)     => args
      case CapRef(_, args)      => args
      case SelfType(of)         => of.toList
      case Layer(_, args, seed) => args :+ seed
      case AppliedVar(_, args)  => args
      case _                    => Nil

  /** This type with `f` applied to each of its children. */
  def mapChildren(f: Type => Type): Type =
    this match
      case ArrowType(l, r, fx)  => ArrowType(f(l), f(r), fx)
      case TupleType(ts)        => TupleType(ts.map(f))
      case IsBound(bs)          => IsBound(bs.map(f))
      case CapType(r)           => CapType(r.map(f))
      case TypeRef(sym, args)   => TypeRef(sym, args.map(f))
      case CapRef(sym, args)    => CapRef(sym, args.map(f))
      case SelfType(of)         => SelfType(of.map(f))
      case Layer(d, args, seed) => Layer(d, args.map(f), f(seed))
      case AppliedVar(id, args) => AppliedVar(id, args.map(f))
      case other                => other

final case class EffectBound(sym: SymbolId, args: List[Type])

/** A `with` row. Effect inference (M7) adds an open-row variable. */
final case class EffectRow(effects: List[EffectBound])

object EffectRow:
  val empty: EffectRow = EffectRow(Nil)

enum SymbolicTerm:
  case LitInt(value: BigInt)
  case LitFloat(value: BigDecimal)
  case LitChar(value: Char)
  case LitBool(value: Boolean)
  case LitString(value: String)
  case BoundVar(sym: SymbolId)
