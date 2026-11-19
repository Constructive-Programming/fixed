package fixed.backend

import fixed.CompilationUnit
import fixed.core.{Denotation, SymKind, SymbolId, Type}
import fixed.typer.Members

/** The slice's stand-in for representation selection (M11; slice §4.3): a value typed only by
  * capability bounds is represented by the single data type in scope that satisfies all of them.
  * With none, a product capability (one constructor requirement) is its own auto-flat-struct
  * (`spec/type_system.md` §6.6): one field per constructor parameter, read by the method of the
  * same name. Both rules are deleted when M11 lands.
  */
final class Representation(unit: CompilationUnit):
  private val symbols = unit.symbols

  private val dataInScope: List[Denotation] =
    symbols.scope.values.toList.distinct.map(symbols(_)).filter(_.kind == SymKind.Data).sortBy(_.id.toInt)

  /** `t` with every capability bound replaced by its representation, where one exists. */
  def apply(t: Type): Type =
    t match
      case Type.IsBound(bs)         => caps(bs).getOrElse(t)
      case c: Type.CapRef           => caps(List(c)).getOrElse(t)
      case Type.ArrowType(l, r, fx) => Type.ArrowType(apply(l), apply(r), fx)
      case other                    => other.mapChildren(apply)

  private def caps(bounds: List[Type]): Option[Type] =
    val wanted = Members.expandAliases(symbols, bounds).collect { case Type.CapRef(c, args) => (c, args.map(apply)) }
    dataInScope.filter(d => wanted.forall((c, _) => satisfies(d, c))) match
      case List(d) if wanted.nonEmpty =>
        val params = symbols.typeParamsOf(d.id).size
        val args = wanted.map(_._2).find(_.nonEmpty).getOrElse(Nil).takeRight(params)
        Some(Type.TypeRef(d.id, List.fill(params - args.size)(Type.UnitType) ++ args))
      case Nil =>
        wanted match
          case List((c, args)) if constructor(c).isDefined => Some(Type.CapRef(c, args))
          case _                                           => None
      case _ => None

  private def satisfies(d: Denotation, cap: SymbolId): Boolean =
    val members = unit.typing.map(_.members)
    symbols.satisfies.getOrElse(d.name, Nil).exists {
      case Type.CapRef(declared, _) => declared == cap || members.exists(_.closure(declared).contains(cap.toInt))
      case _                        => false
    }

  /** A product capability's one constructor requirement, when no data type satisfies the capability. */
  def constructor(cap: SymbolId): Option[Denotation] =
    symbols.membersOf(cap).filter(_.kind == SymKind.StaticMethod).toList match
      case List(ctor) if !dataInScope.exists(satisfies(_, cap)) && symbols(cap).kind == SymKind.Cap => Some(ctor)
      case _                                                                                        => None

  /** The auto-flat-struct fields of product capability `cap` at arguments `args`: names and types. */
  def fields(cap: SymbolId, args: List[Type]): List[(String, Type)] =
    val sigma = symbols.typeParamsOf(cap).zip(args).toMap
    constructor(cap).toList.flatMap(ctor =>
      symbols.membersOf(ctor.id).filter(_.kind == SymKind.ValueParam).map(p => p.name -> Lower.subst(p.info, sigma))
    )
