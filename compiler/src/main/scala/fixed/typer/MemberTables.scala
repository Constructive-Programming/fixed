package fixed.typer

import scala.collection.immutable.BitSet

import fixed.core.{Denotation, SymKind, SymbolId, SymbolTable, Type}

/** A member reached from a root capability: its declaring `cap`, the arguments `cap` had in the
  * `extends` clause that reached it (`None` at the root: use-site arguments), and the member.
  */
final case class MemberEntry(cap: SymbolId, written: Option[List[Type]], member: Denotation)

/** Each capability's methods and statics flattened over `extends` and aliases, own member first,
  * then supers depth first. Built on first lookup and memoized (stdlib capabilities across runs):
  * a pure function of signatures, which the Namer fixes before bodies are typed.
  */
final class MemberTables private (symbols: SymbolTable):
  private val built = scala.collection.mutable.HashMap.empty[Int, Map[(SymKind, String), List[MemberEntry]]]

  def lookup(cap: SymbolId, kind: SymKind, name: String): List[MemberEntry] =
    val cache = if symbols(cap).owner == SymbolId.Prelude then MemberTables.stdlib else built
    cache.getOrElseUpdate(cap.toInt, MemberTables.table(symbols, cap)).getOrElse((kind, name), Nil)

object MemberTables:
  def apply(symbols: SymbolTable): MemberTables = new MemberTables(symbols)

  /** Stdlib capabilities have the same ids and signatures in every unit (indexed once). */
  private val stdlib = scala.collection.concurrent.TrieMap.empty[Int, Map[(SymKind, String), List[MemberEntry]]]

  private[typer] def table(symbols: SymbolTable, root: SymbolId): Map[(SymKind, String), List[MemberEntry]] =
    reached(symbols, root, None, BitSet.empty)
      .flatMap { case (cap, written) =>
        symbols
          .membersOf(cap)
          .filter(m => m.kind == SymKind.Method || m.kind == SymKind.StaticMethod)
          .map(m => (m.kind, m.name) -> MemberEntry(cap, written, m))
      }
      .groupMap(_._1)(_._2)

  /** `cap` and what its `extends` chain reaches, depth first, with the arguments written there. */
  private def reached(symbols: SymbolTable, cap: SymbolId, written: Option[List[Type]], seen: BitSet)
      : List[(SymbolId, Option[List[Type]])] =
    val supers = symbols(cap).info match
      case Type.IsBound(bs) => Members.expandAliases(symbols, bs)
      case _                => Nil
    val ancestors = seen.incl(cap.toInt)
    (cap, written) :: supers.flatMap {
      case Type.CapRef(sup, args) if !ancestors.contains(sup.toInt) => reached(symbols, sup, Some(args), ancestors)
      case _                                                        => Nil
    }
