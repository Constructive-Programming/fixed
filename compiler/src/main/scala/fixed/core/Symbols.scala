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
  case Fn, Method, StaticMethod, Cap, Data, Variant, Effect, Operation, TypeAlias, Import, TypeParam, ValueParam, Local

/** A symbol's current view: name, owner, kind, signature type and declaration span. */
final case class Denotation(
    id: SymbolId,
    name: String,
    owner: SymbolId,
    kind: SymKind,
    info: Type,
    span: Span
)

/** Every symbol of a compilation, by id. Persistent: entering or refining returns a new table. */
final case class SymbolTable(entries: IntMap[Denotation], nextId: Int):

  def apply(id: SymbolId): Denotation = entries(id.toInt)

  /** Enter a new symbol; returns the table and the fresh id. */
  def enter(name: String, owner: SymbolId, kind: SymKind, info: Type, span: Span): (SymbolTable, SymbolId) =
    val id = SymbolId(nextId)
    (SymbolTable(entries.updated(nextId, Denotation(id, name, owner, kind, info, span)), nextId + 1), id)

  /** Replace a symbol's signature type. */
  def refine(id: SymbolId, info: Type): SymbolTable =
    copy(entries = entries.updated(id.toInt, apply(id).copy(info = info)))

  /** All denotations in entry order. */
  def denotations: List[Denotation] = entries.values.toList

  /** Names visible at the top level: prelude and root-owned symbols, later entries shadowing earlier ones. */
  def topLevelScope: Map[String, SymbolId] =
    entries.valuesIterator
      .filter(d => d.owner == SymbolId.Root || d.owner == SymbolId.Prelude)
      .map(d => d.name -> d.id)
      .toMap

object SymbolTable:
  /** Ids 0 and 1 are reserved for the pseudo-owners [[SymbolId.Root]] and [[SymbolId.Prelude]]. */
  val empty: SymbolTable = SymbolTable(IntMap.empty, 2)

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
          case _: Type.ArrowType => s"(${render(l)})"
          case _                 => render(l)
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
