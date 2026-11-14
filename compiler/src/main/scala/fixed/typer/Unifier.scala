package fixed.typer

import scala.collection.immutable.IntMap

import fixed.core.{SymbolId, Type}

/** A persistent substitution over [[Type.TypeVar]]s, with the two constraints M3 needs: a variable
  * may be required to be `Numeric`, and a literal's variable carries the type it defaults to when
  * nothing else solves it (`i64` for integer literals, `f64` for float ones).
  *
  * `numeric` is supplied by the typer: it decides whether a solved type satisfies `Numeric`.
  */
final case class Unifier(bindings: IntMap[Type], numericVars: Set[Int], defaults: IntMap[Type], next: Int):

  def fresh(numeric: Boolean, default: Option[Type]): (Unifier, Type) =
    val v = Type.TypeVar(next)
    (
      copy(
        numericVars = if numeric then numericVars + next else numericVars,
        defaults = default.fold(defaults)(defaults.updated(next, _)),
        next = next + 1
      ),
      v
    )

  /** Follow variable bindings until an unbound variable or a non-variable type. */
  def resolve(t: Type): Type =
    t match
      case Type.TypeVar(id) => bindings.get(id).fold(t)(resolve)
      case _                => t

  /** `t` with every bound variable replaced, recursively. */
  def zonk(t: Type): Type =
    resolve(t) match
      case Type.ArrowType(l, r, fx) => Type.ArrowType(zonk(l), zonk(r), fx)
      case Type.TupleType(ts)       => Type.TupleType(ts.map(zonk))
      case Type.TypeRef(sym, args)  => Type.TypeRef(sym, args.map(zonk))
      case Type.CapRef(sym, args)   => Type.CapRef(sym, args.map(zonk))
      case Type.IsBound(bs)         => Type.IsBound(bs.map(zonk))
      case Type.CapType(r)          => Type.CapType(r.map(zonk))
      case Type.SelfType(of)        => Type.SelfType(of.map(zonk))
      case other                    => other

  /** Solve every still-unbound literal variable to its default. */
  def applyDefaults: Unifier =
    defaults.foldLeft(this) { case (u, (id, d)) =>
      u.resolve(Type.TypeVar(id)) match
        case Type.TypeVar(free) => u.copy(bindings = u.bindings.updated(free, d))
        case _                  => u
    }

  /** Require `t` to be `Numeric`. */
  def requireNumeric(t: Type, numeric: Type => Boolean): Either[String, Unifier] =
    resolve(t) match
      case Type.TypeVar(id)        => Right(copy(numericVars = numericVars + id))
      case Type.NeverType          => Right(this)
      case other if numeric(other) => Right(this)
      case _                       => Left("Numeric")

  /** Make `a` and `b` equal. `Left` carries no message: the caller reports the mismatch with both
    * zonked types. `is ...` bounds accept any type until capability checking lands (M6).
    */
  def unify(a: Type, b: Type, numeric: Type => Boolean): Either[Unit, Unifier] =
    (resolve(a), resolve(b)) match
      case (Type.NeverType, _) | (_, Type.NeverType)              => Right(this)
      case (Type.TypeVar(x), Type.TypeVar(y)) if x == y           => Right(this)
      case (Type.TypeVar(x), t)                                   => bind(x, t, numeric)
      case (t, Type.TypeVar(y))                                   => bind(y, t, numeric)
      case (_: Type.IsBound, _) | (_, _: Type.IsBound)            => Right(this)
      case (Type.ArrowType(l1, r1, _), Type.ArrowType(l2, r2, _)) =>
        unify(l1, l2, numeric).flatMap(_.unify(r1, r2, numeric))
      case (Type.TupleType(xs), Type.TupleType(ys)) if xs.size == ys.size => unifyAll(xs, ys, numeric)
      case (Applied(k1, s1, xs), Applied(k2, s2, ys)) if k1 == k2 && s1 == s2 && xs.size == ys.size =>
        unifyAll(xs, ys, numeric)
      case (x, y) if x == y => Right(this)
      case _                => Left(())

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
              defaults =
                defaults.get(id).filterNot(_ => defaults.contains(other)).fold(defaults)(defaults.updated(other, _))
            )
          )
        case _ if numericVars(id) && !numeric(t) => Left(())
        case _                                   => Right(bound)

  private def occurs(id: Int, t: Type): Boolean =
    resolve(t) match
      case Type.TypeVar(other)     => other == id
      case Type.ArrowType(l, r, _) => occurs(id, l) || occurs(id, r)
      case Type.TupleType(ts)      => ts.exists(occurs(id, _))
      case Type.TypeRef(_, args)   => args.exists(occurs(id, _))
      case Type.CapRef(_, args)    => args.exists(occurs(id, _))
      case _                       => false

/** `TypeRef` and `CapRef` as one shape: (is a cap, symbol, arguments). */
private object Applied:
  def unapply(t: Type): Option[(Boolean, SymbolId, List[Type])] =
    t match
      case Type.TypeRef(sym, args) => Some((false, sym, args))
      case Type.CapRef(sym, args)  => Some((true, sym, args))
      case _                       => None

object Unifier:
  val empty: Unifier = Unifier(IntMap.empty, Set.empty, IntMap.empty, 0)
