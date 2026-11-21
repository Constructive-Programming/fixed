package fixed.core

import scala.collection.immutable.{BitSet, IntMap}

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
  * members in entry order, the top-level scope and the `satisfies` declarations by satisfying type
  * (a data type's symbol, not its name, which a program's own declaration may shadow).
  * Persistent: entering or refining returns a new table.
  */
final case class SymbolTable(
    entries: Vector[Denotation],
    nextId: Int,
    children: IntMap[Vector[SymbolId]],
    typeParams: IntMap[List[SymbolId]],
    scope: Map[String, SymbolId],
    private[core] val satisfies: Map[Type, List[Type]],
    constructors: Map[(Int, String), List[(SymbolId, String)]],
    defaulted: scala.collection.immutable.BitSet
):

  def apply(id: SymbolId): Denotation = entries(id.toInt - SymbolTable.firstId)

  /** Enter a new symbol; returns the table and the fresh id. */
  def enter(name: String, owner: SymbolId, kind: SymKind, info: Type, span: Span): (SymbolTable, SymbolId) =
    val id = SymbolId(nextId)

    /** Locals and top-level / prelude declarations are never looked up as members, so they skip
      * the owner index.
      */
    val indexed =
      if kind == SymKind.Local || owner == SymbolId.Root || owner == SymbolId.Prelude then children
      else children.updated(owner.toInt, children.getOrElse(owner.toInt, Vector.empty) :+ id)
    val params =
      if kind == SymKind.TypeParam then typeParams.updated(owner.toInt, typeParams.getOrElse(owner.toInt, Nil) :+ id)
      else typeParams
    (
      copy(
        entries = entries :+ Denotation(id, name, owner, kind, info, span),
        nextId = nextId + 1,
        children = indexed,
        typeParams = params
      ),
      id
    )

  /** The type variables `owner` declared (`of` parameters, signature variables), in order. */
  def typeParamsOf(owner: SymbolId): List[SymbolId] = typeParams.getOrElse(owner.toInt, Nil)

  /** The symbols `owner` declared, in entry order. */
  def membersOf(owner: SymbolId): Iterator[Denotation] =
    children.getOrElse(owner.toInt, Vector.empty).iterator.map(apply)

  /** A data type's variants, in declaration order. */
  def variantsOf(data: SymbolId): Iterator[Denotation] = membersOf(data).filter(_.kind == SymKind.Variant)

  /** A variant's fields, in declaration order. */
  def fieldsOf(variant: SymbolId): Iterator[Denotation] = membersOf(variant).filter(_.kind == SymKind.Field)

  /** Record that field (or parameter) `id` declares a default value. */
  def markDefaulted(id: SymbolId): SymbolTable = copy(defaulted = defaulted.incl(id.toInt))

  def hasDefault(id: SymbolId): Boolean = defaulted.contains(id.toInt)

  /** The top-level data type called `name`, if any. */
  def dataNamed(name: String): Option[SymbolId] = scope.get(name).filter(apply(_).kind == SymKind.Data)

  /** Record `data satisfies cap` mapping `variant as ctor` (for constructor resolution). */
  def mapConstructor(cap: SymbolId, ctor: String, data: SymbolId, variant: String): SymbolTable =
    val key = (cap.toInt, ctor)
    copy(constructors = constructors.updated(key, constructors.getOrElse(key, Nil) :+ (data -> variant)))

  /** The data types (with the variant) that implement `cap`'s constructor requirement `ctor`. */
  def constructorsOf(cap: SymbolId, ctor: String): List[(SymbolId, String)] =
    constructors.getOrElse((cap.toInt, ctor), Nil)

  /** Record `t satisfies cap`. A data type is keyed by its symbol, its arguments ignored; any other
    * type (a primitive, or the Namer's `PrimType` for a name that resolves to no data type) by itself.
    */
  def satisfy(t: Type, cap: Type): SymbolTable =
    val key = SymbolTable.satisfier(t)
    copy(satisfies = satisfies.updated(key, satisfies.getOrElse(key, Nil) :+ cap))

  /** The capabilities `t`'s `satisfies` declarations name, in declaration order. */
  def satisfactionsOf(t: Type): List[Type] = satisfies.getOrElse(SymbolTable.satisfier(t), Nil)

  /** Replace a symbol's name. */
  def rename(id: SymbolId, name: String): SymbolTable = update(id, _.copy(name = name))

  /** Replace a symbol's signature type. */
  def refine(id: SymbolId, info: Type): SymbolTable = update(id, _.copy(info = info))

  private def update(id: SymbolId, f: Denotation => Denotation): SymbolTable =
    copy(entries = entries.updated(id.toInt - SymbolTable.firstId, f(apply(id))))

  /** All denotations in entry order. */
  def denotations: List[Denotation] = entries.toList

object SymbolTable:
  /** Ids 0 and 1 are reserved for the pseudo-owners [[SymbolId.Root]] and [[SymbolId.Prelude]];
    * real symbols are dense from 2, so `entries` is indexed by `id - firstId`.
    */
  val firstId: Int = 2

  /** The key of [[SymbolTable.satisfies]]: a data type without its arguments. */
  private def satisfier(t: Type): Type =
    t match
      case Type.TypeRef(id, _) => Type.TypeRef(id, Nil)
      case other               => other

  val empty: SymbolTable =
    SymbolTable(Vector.empty, firstId, IntMap.empty, IntMap.empty, Map.empty, Map.empty, Map.empty, BitSet.empty)

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
        else
          fx.effects
            .map(e =>
              if table(e.sym).kind == SymKind.TypeParam then render(e.args.head) else table(e.sym).name + args(e.args)
            )
            .mkString(" with ", " + ", "")
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
