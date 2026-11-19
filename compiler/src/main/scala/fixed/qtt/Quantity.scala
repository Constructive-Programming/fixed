package fixed.qtt

import scala.collection.immutable.IntMap

/** The quantity semiring of `spec/quantities.md` §3, ordered `Zero < One < Many`. */
enum Quantity:
  case Zero, One, Many

  /** Both observations happen (§3.1). */
  def +(that: Quantity): Quantity =
    if this == Zero then that else if that == Zero then this else Many

  /** Scaling by a multiplicity context (§3.2). */
  def *(that: Quantity): Quantity =
    if this == Zero || that == Zero then Zero else if this == One then that else Many

  /** Exactly one of two exclusive observations happens (§3.3). */
  infix def join(that: Quantity): Quantity = if ordinal >= that.ordinal then this else that

  def show: String = this match
    case Zero => "0"
    case One  => "1"
    case Many => "ω"

/** How often each binder (by its id) is used by an expression. Absent means zero, which is the
  * identity of both `+` and `join`, so both are a union of the two maps.
  */
final case class Usage(counts: IntMap[Quantity]):
  def apply(b: Int): Quantity = counts.getOrElse(b, Quantity.Zero)

  def +(that: Usage): Usage = union(that, _ + _)

  infix def join(that: Usage): Usage = union(that, _ join _)

  private def union(that: Usage, f: (Quantity, Quantity) => Quantity): Usage =
    Usage(counts.unionWith(that.counts, (_, x, y) => f(x, y)))

  def scale(q: Quantity): Usage =
    if q == Quantity.One || counts.isEmpty then this
    else if q == Quantity.Zero then Usage.none
    else Usage(counts.transform((_, _) => Quantity.Many))

  def without(bs: Iterable[Int]): Usage = Usage(counts -- bs)

object Usage:
  val none: Usage = Usage(IntMap.empty)

  def one(b: Int): Usage = Usage(IntMap(b -> Quantity.One))

  def sum(us: Iterable[Usage]): Usage = us.foldLeft(none)(_ + _)

  def joinAll(us: Iterable[Usage]): Usage = us.reduceOption(_ join _).getOrElse(none)
