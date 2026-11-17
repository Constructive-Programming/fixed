package fixed.core

import scala.collection.immutable.IntMap

import fixed.util.Span

/** Identity of a definition; carries no semantic content. */
opaque type SymbolId = Int

object SymbolId:
  /** Owner of top-level declarations. */
  val Root: SymbolId = 0

  /** Owner of the built-in standard-library names. */
  val Prelude: SymbolId = 1

  def apply(i: Int): SymbolId = i

  extension (id: SymbolId) def toInt: Int = id

/** What a symbol denotes. */
enum SymKind:
  case Fn, Method, StaticMethod, Cap, Data, Variant, Effect, Operation, TypeAlias, Import, TypeParam, ValueParam, Field,
    Local

/** A symbol's current view: name, owner, kind, signature type and declaration span. */
final case class Denotation(
    id: SymbolId,
    name: String,
    owner: SymbolId,
    kind: SymKind,
    info: Type,
    span: Span
)

/** Every symbol of a compilation, by id, plus what the Namer learns alongside: each owner's
  * members in entry order, the top-level scope and the `satisfies` declarations by type name.
  * Persistent: entering or refining returns a new table.
  */
final case class SymbolTable(
    entries: Vector[Denotation],
    nextId: Int,
    children: IntMap[Vector[SymbolId]],
    scope: Map[String, SymbolId],
    satisfies: Map[String, List[Type]]
):

  def apply(id: SymbolId): Denotation = entries(id.toInt - SymbolTable.firstId)

  /** Enter a new symbol; returns the table and the fresh id. */
  def enter(name: String, owner: SymbolId, kind: SymKind, info: Type, span: Span): (SymbolTable, SymbolId) =
    val id = SymbolId(nextId)
    val siblings = children.getOrElse(owner.toInt, Vector.empty)
    (
      copy(
        entries = entries :+ Denotation(id, name, owner, kind, info, span),
        nextId = nextId + 1,
        children = children.updated(owner.toInt, siblings :+ id)
      ),
      id
    )

  /** The symbols `owner` declared, in entry order. */
  def membersOf(owner: SymbolId): Vector[Denotation] = children.getOrElse(owner.toInt, Vector.empty).map(apply)

  /** Record `typeName satisfies cap`. */
  def satisfy(typeName: String, cap: Type): SymbolTable =
    copy(satisfies = satisfies.updated(typeName, satisfies.getOrElse(typeName, Nil) :+ cap))

  /** Replace a symbol's signature type. */
  def refine(id: SymbolId, info: Type): SymbolTable =
    copy(entries = entries.updated(id.toInt - SymbolTable.firstId, apply(id).copy(info = info)))

  /** All denotations in entry order. */
  def denotations: List[Denotation] = entries.toList

object SymbolTable:
  /** Ids 0 and 1 are reserved for the pseudo-owners [[SymbolId.Root]] and [[SymbolId.Prelude]];
    * real symbols are dense from 2, so `entries` is indexed by `id - firstId`.
    */
  val firstId: Int = 2

  val empty: SymbolTable = SymbolTable(Vector.empty, firstId, IntMap.empty, Map.empty, Map.empty)

extension (table: SymbolTable)
  /** Source-like rendering of `t`, with symbol names resolved through `table`. */
  def render(t: Type): String =
    def args(as: List[Type]): String =
      as match
        case Nil      => ""
        case a :: Nil => s" of ${render(a)}"
        case _        => as.map(render).mkString(" of (", ", ", ")")
    def term(st: SymbolicTerm): String =
      st match
        case SymbolicTerm.BoundVar(sym) => table(sym).name
        case SymbolicTerm.LitString(v)  => s"\"$v\""
        case SymbolicTerm.LitChar(v)    => s"'$v'"
        case SymbolicTerm.LitInt(v)     => v.toString
        case SymbolicTerm.LitFloat(v)   => v.toString
        case SymbolicTerm.LitBool(v)    => v.toString
    t match
      case Type.PrimType(name)      => name
      case Type.UnitType            => "()"
      case Type.NeverType           => "!"
      case Type.ArrowType(l, r, fx) =>
        val row = if fx.effects.isEmpty then ""
        else fx.effects.map(e => table(e.sym).name + args(e.args)).mkString(" with ", " + ", "")
        val lhs = l match
          case Type.TupleType(List(p: (Type.ArrowType | Type.TupleType))) => s"(${render(p)})"
          case Type.TupleType(List(p))                                    => render(p)
          case other                                                      => render(other)
        s"$lhs -> ${render(r)}$row"
      case Type.TupleType(elems)        => elems.map(render).mkString("(", ", ", ")")
      case Type.IsBound(bounds)         => bounds.map(render).mkString("is ", " + ", "")
      case Type.CapType(ret)            => ret.fold("cap")(r => s"cap of ${render(r)}")
      case Type.TypeRef(sym, as)        => table(sym).name + args(as)
      case Type.CapRef(sym, as)         => table(sym).name + args(as)
      case Type.SelfType(of)            => of.fold("Self")(o => s"Self of ${render(o)}")
      case Type.PartType                => "Part"
      case Type.RefinementType(g, vals) => vals.map(term).mkString(s"${table(g).name}(", ", ", ")")
      case Type.ValueArg(v)             => term(v)
      case Type.TypeVar(id)             => s"?$id"
      case Type.AppliedVar(id, as)      => s"?$id${args(as)}"
      case Type.Layer(d, as, seed)      => s"${table(d).name}${args(as)} layer over ${render(seed)}"
