package fixed.typer

import scala.collection.immutable.IntMap

import fixed.core.{SymbolId, Type}

/** A persistent substitution over [[Type.TypeVar]]s. A variable may be required to be `Numeric`;
  * a literal's variable carries the type it defaults to when nothing else solves it (`i64` /
  * `f64`); a variable instantiated from a bounded type variable keeps that bound, so members
  * resolve on it before it is solved. `tuple` is the `Tuple` data type, which a 2-tuple equals.
  *
  * `numeric` is supplied by the typer: it decides whether a solved type satisfies `Numeric`.
  */
final case class Unifier(
    bindings: IntMap[Type],
    numericVars: Set[Int],
    defaults: IntMap[Type],
    varBounds: IntMap[List[Type]],
    tuple: Option[SymbolId],
    next: Int
):

  def fresh(numeric: Boolean, default: Option[Type], bounds: List[Type] = Nil): (Unifier, Type) =
    val v = Type.TypeVar(next)
    (
      copy(
        numericVars = if numeric then numericVars + next else numericVars,
        defaults = default.fold(defaults)(defaults.updated(next, _)),
        varBounds = if bounds.isEmpty then varBounds else varBounds.updated(next, bounds),
        next = next + 1
      ),
      v
    )

  /** The bound an unsolved variable was instantiated with. */
  def boundsOf(id: Int): List[Type] = varBounds.getOrElse(id, Nil)

  /** Follow variable bindings until an unbound variable or a non-variable type. */
  def resolve(t: Type): Type =
    t match
      case Type.TypeVar(id)          => bindings.get(id).fold(t)(resolve)
      case Type.AppliedVar(id, args) =>
        bindings.get(id).fold(t) { head =>
          resolve(Unifier.applyHead(resolve(head), args))
        }
      case _ => t

  /** `t` with every bound variable replaced, recursively. */
  def zonk(t: Type): Type = resolve(t).mapChildren(zonk)

  /** Solve every still-unbound literal variable to its default. */
  def applyDefaults: Unifier =
    defaults.foldLeft(this) { case (u, (id, d)) =>
      u.resolve(Type.TypeVar(id)) match
        case Type.TypeVar(free) => u.copy(bindings = u.bindings.updated(free, d))
        case _                  => u
    }

  /** Require `t` to be `Numeric`. */
  def requireNumeric(t: Type, numeric: Type => Boolean): Solved =
    resolve(t) match
      case Type.TypeVar(id)        => Right(copy(numericVars = numericVars + id))
      case Type.NeverType          => Right(this)
      case other if numeric(other) => Right(this)
      case _                       => Left(())

  /** Make `a` and `b` equal. `Left` carries no message: the caller reports the mismatch with both
    * zonked types. `is ...` bounds accept any type until capability checking lands (M6).
    */
  def unify(a: Type, b: Type, numeric: Type => Boolean): Either[Unit, Unifier] =
    (resolve(a), resolve(b)) match
      case (Type.NeverType, _) | (_, Type.NeverType)                                        => Right(this)
      case (Type.TypeVar(x), Type.TypeVar(y)) if x == y                                     => Right(this)
      case (Type.AppliedVar(x, xs), Type.AppliedVar(y, ys)) if x == y && xs.size == ys.size => unifyAll(xs, ys, numeric)
      case (Type.AppliedVar(x, xs), Applied(k, sym, ys))          => bindHead(x, k, sym, xs, ys, numeric)
      case (Applied(k, sym, ys), Type.AppliedVar(x, xs))          => bindHead(x, k, sym, xs, ys, numeric)
      case (Type.TypeVar(x), t)                                   => bind(x, t, numeric)
      case (t, Type.TypeVar(y))                                   => bind(y, t, numeric)
      case (Capabilities(xs), Capabilities(ys))                   => unifyShared(xs, ys, numeric)
      case (_: Type.IsBound, _) | (_, _: Type.IsBound)            => Right(this)
      case (Type.ArrowType(l1, r1, _), Type.ArrowType(l2, r2, _)) =>
        unify(l1, l2, numeric).flatMap(_.unify(r1, r2, numeric))
      case (Type.TupleType(xs), Type.TupleType(ys)) if xs.size == ys.size => unifyAll(xs, ys, numeric)

      /** A higher-kinded type variable used bare (`M`) and applied (`M of A`) names the same type. */
      case (Applied(k1, s1, xs), Applied(k2, s2, ys)) if k1 == k2 && s1 == s2 =>
        if xs.size == ys.size then unifyAll(xs, ys, numeric)
        else if xs.isEmpty || ys.isEmpty then Right(this) else Left(())
      case (Type.TupleType(List(a, b)), Type.TypeRef(t, List(x, y))) if tuple.contains(t) =>
        unifyAll(List(a, b), List(x, y), numeric)
      case (Type.TypeRef(t, List(x, y)), Type.TupleType(List(a, b))) if tuple.contains(t) =>
        unifyAll(List(x, y), List(a, b), numeric)

      /** A capability used as a type is an anonymous bound: checked by capability closure (M6). */
      case (_: Type.CapRef, _) | (_, _: Type.CapRef)                                          => Right(this)
      case (Type.Layer(d1, xs, s1), Type.Layer(d2, ys, s2)) if d1 == d2 && xs.size == ys.size =>
        unifyAll(xs :+ s1, ys :+ s2, numeric)
      case (x, y) if x == y => Right(this)
      case _                => Left(())

  /** Solve `?x of xs = sym of ys`: `?x` becomes the constructor `sym`, the arguments unify
    * right-aligned (an applied variable fixes only the trailing, element arguments).
    */
  private def bindHead(
      x: Int,
      cap: Boolean,
      sym: SymbolId,
      applied: List[Type],
      full: List[Type],
      numeric: Type => Boolean
  )
      : Solved =
    val prefix = full.dropRight(applied.size)
    val head = if cap then Type.CapRef(sym, prefix) else Type.TypeRef(sym, prefix)
    if applied.size > full.size then Left(())
    else copy(bindings = bindings.updated(x, head)).unifyAll(applied, full.takeRight(applied.size), numeric)

  /** Bounds are not yet checked against each other (M6), but a capability both sides name has
    * its arguments unified: `is Optional of A` against `is Optional of u64` solves `A`.
    */
  private def unifyShared(left: List[Type], right: List[Type], numeric: Type => Boolean): Solved =
    val pairs =
      for
        case Type.CapRef(c1, as) <- left
        case Type.CapRef(c2, bs) <- right
        if c1 == c2 && as.size == bs.size
      yield (as, bs)
    pairs.foldLeft[Either[Unit, Unifier]](Right(this)) { case (acc, (as, bs)) =>
      acc.flatMap(_.unifyAll(as, bs, numeric))
    }

  private def unifyAll(xs: List[Type], ys: List[Type], numeric: Type => Boolean): Either[Unit, Unifier] =
    xs.zip(ys).foldLeft[Either[Unit, Unifier]](Right(this)) { case (acc, (x, y)) =>
      acc.flatMap(_.unify(x, y, numeric))
    }

  private def bind(id: Int, t: Type, numeric: Type => Boolean): Either[Unit, Unifier] =
    if occurs(id, t) then Left(())
    else
      val bound = copy(bindings = bindings.updated(id, t))
      t match
        case Type.TypeVar(other) =>
          Right(
            bound.copy(
              numericVars = if numericVars(id) then numericVars + other else numericVars,
              varBounds =
                if boundsOf(id).isEmpty then varBounds else varBounds.updated(other, boundsOf(other) ++ boundsOf(id)),
              defaults =
                defaults.get(id).filterNot(_ => defaults.contains(other)).fold(defaults)(defaults.updated(other, _))
            )
          )
        case _ if numericVars(id) && !numeric(t) => Left(())
        case _                                   => Right(bound)

  private def occurs(id: Int, t: Type): Boolean =
    resolve(t) match
      case Type.TypeVar(other)      => other == id
      case Type.AppliedVar(v, args) => v == id || args.exists(occurs(id, _))
      case other                    => other.children.exists(occurs(id, _))

/** An anonymous bound or a single capability, as its list of capabilities. */
private object Capabilities:
  def unapply(t: Type): Option[List[Type]] =
    t match
      case Type.IsBound(bs) => Some(bs)
      case c: Type.CapRef   => Some(List(c))
      case _                => None

/** `TypeRef` and `CapRef` as one shape: (is a cap, symbol, arguments). */
private object Applied:
  def unapply(t: Type): Option[(Boolean, SymbolId, List[Type])] =
    t match
      case Type.TypeRef(sym, args) => Some((false, sym, args))
      case Type.CapRef(sym, args)  => Some((true, sym, args))
      case _                       => None

/** Unification's outcome: the extended unifier, or a mismatch the caller reports. */
type Solved = Either[Unit, Unifier]

object Unifier:
  /** A type constructor (a variable's solution) applied to `args`: `M := Maybe` makes `M of A` `Maybe of A`. */
  def applyHead(head: Type, args: List[Type]): Type =
    head match
      case Type.TypeRef(sym, _)                  => Type.TypeRef(sym, args)
      case Type.CapRef(sym, _)                   => Type.CapRef(sym, args)
      case Type.IsBound(List(Type.CapRef(c, _))) => Type.IsBound(List(Type.CapRef(c, args)))
      case Type.TypeVar(id)                      => Type.AppliedVar(id, args)
      case Type.AppliedVar(id, _)                => Type.AppliedVar(id, args)
      case other                                 => other

  val empty: Unifier = Unifier(IntMap.empty, Set.empty, IntMap.empty, IntMap.empty, None, 0)
