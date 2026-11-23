package fixed.backend

import fixed.CompilationUnit
import fixed.core.{Denotation, SymKind, SymbolId, Type}
import fixed.typer.{Members, Satisfaction}

/** The slice's stand-in for representation selection (M11; slice §4.3): a value typed only by
  * capability bounds is represented by the single data type that has all of them, declared or
  * derived, and declares one of them ([[declares]]). With no data type declaring it, a product
  * capability (one constructor requirement) is its own auto-flat-struct (`spec/type_system.md`
  * §6.6): one field per constructor parameter, read by the method of the same name. Both rules are
  * deleted when M11 lands.
  */
final class Representation(unit: CompilationUnit):
  private val symbols = unit.symbols

  /** Every data type of the program, the prelude and the stdlib modules, whether in scope or not:
    * a program's declaration of the same name hides a prelude type's name, not its satisfactions.
    */
  private val topLevelData: List[Denotation] =
    symbols.denotations.filter(d => d.kind == SymKind.Data && (d.owner == SymbolId.Root || d.owner == SymbolId.Prelude))

  /** `t` with every capability bound replaced by its representation, where one exists. */
  def apply(t: Type): Type =
    t match
      case Type.IsBound(bs)         => caps(bs).getOrElse(t)
      case c: Type.CapRef           => caps(List(c)).getOrElse(t)
      case Type.ArrowType(l, r, fx) => Type.ArrowType(apply(l), apply(r), fx)
      case other                    => other.mapChildren(apply)

  private def caps(bounds: List[Type]): Option[Type] =
    val wanted = Members.expandAliases(symbols, bounds).collect { case Type.CapRef(c, args) => (c, args.map(apply)) }

    /** `d` at the arguments the bounds give its rightmost parameters. */
    def at(d: Denotation): Type.TypeRef =
      val params = symbols.typeParamsOf(d.id).size
      val args = wanted.map(_._2).find(_.nonEmpty).getOrElse(Nil).takeRight(params)
      Type.TypeRef(d.id, List.fill(params - args.size)(Type.UnitType) ++ args)
    topLevelData.filter(d =>
      wanted.exists((c, _) => declares(at(d), c)) && wanted.forall((c, _) => holds(at(d), c))
    ) match
      case List(d) => Some(at(d))
      case Nil     =>
        wanted match
          case List((c, args)) if constructor(c).isDefined => Some(Type.CapRef(c, args))
          case _                                           => None
      case _ => None

  private def satisfies(d: Denotation, cap: SymbolId): Boolean =
    val members = unit.typing.map(_.members)
    symbols.satisfactionsOf(Type.TypeRef(d.id, Nil)).exists {
      case Type.CapRef(declared, _) => declared == cap || members.exists(_.closure(declared).contains(cap.toInt))
      case _                        => false
    }

  /** `d` has `cap`, declared (directly or through a capability that extends it) or derived (Rule 7.4). */
  private def holds(t: Type.TypeRef, cap: SymbolId): Boolean =
    unit.typing.exists(Satisfaction.holds(_, t, cap, Nil).isDefined)

  /** `d` declares `cap` (or a capability extending it), except where the declaration only names a
    * derivation of the prelude's: `Json satisfies Show`, which would otherwise make a bare `is Show`
    * ambiguous with `String`. A program's declarations all count, as do `Clone` (on request only)
    * and a type without variants, for which `derived` holds vacuously.
    */
  private def declares(t: Type.TypeRef, cap: SymbolId): Boolean =
    val d = symbols(t.sym)
    satisfies(d, cap) &&
    !(d.owner == SymbolId.Prelude && symbols.variantsOf(d.id).nonEmpty &&
      unit.typing.exists(s => !Satisfaction.onRequest(s, cap) && Satisfaction.derived(s, d.id, cap, t.args, Set.empty)))

  /** A product capability's one constructor requirement, when no data type satisfies the capability. */
  def constructor(cap: SymbolId): Option[Denotation] =
    symbols.membersOf(cap).filter(_.kind == SymKind.StaticMethod).toList match
      case List(ctor) if !topLevelData.exists(satisfies(_, cap)) && symbols(cap).kind == SymKind.Cap => Some(ctor)
      case _                                                                                         => None

  /** The auto-flat-struct fields of product capability `cap` at arguments `args`: names and types. */
  def fields(cap: SymbolId, args: List[Type]): List[(String, Type)] =
    val sigma = symbols.typeParamsOf(cap).zip(args).toMap
    constructor(cap).toList.flatMap(ctor =>
      symbols.membersOf(ctor.id).filter(_.kind == SymKind.ValueParam).map(p => p.name -> TypeOps.subst(p.info, sigma))
    )
