package fixed.backend

/** Core: the backend's IR (`docs/plans/phase-4.0-backend-slice.md` §4.2). First order, monomorphic,
  * in A-normal form: every intermediate value is a named [[Atom.Var]]. Later Phase 4 passes (fold
  * specialiser, Perceus, region lowering) are Core to Core.
  */
enum CType:
  case Int(bits: scala.Int, signed: Boolean)
  case Float(bits: scala.Int)
  case Bool
  case Unit

  /** A pointer to a heap object with a reference-count header. */
  case Boxed

  def boxed: Boolean = this == CType.Boxed

/** An operand: a variable or a constant. A string constant is a static object (never counted). */
enum Atom:
  case Var(name: String, ctype: CType)
  case IntC(value: BigInt, ctype: CType)
  case FloatC(value: BigDecimal, ctype: CType)
  case BoolC(value: Boolean)
  case UnitC
  case StrC(value: String)

  def tpe: CType =
    this match
      case Var(_, t)    => t
      case IntC(_, t)   => t
      case FloatC(_, t) => t
      case BoolC(_)     => CType.Bool
      case UnitC        => CType.Unit
      case StrC(_)      => CType.Boxed

/** Scalar operations. `Widen` converts to the result variable's wider type of the same kind. */
enum PrimOp:
  case Add, Sub, Mul, Div, Rem, Eq, Ne, Lt, Le, Gt, Ge, Neg, Not, Widen

/** The right-hand side of a `Let`. Calls consume their boxed arguments. */
enum Rhs:
  case Move(a: Atom)
  case Prim(op: PrimOp, args: List[Atom])

  /** A call of a user function (`runtime = false`) or of a C runtime function. */
  case Call(fn: String, args: List[Atom], runtime: Boolean)
  case If(cond: Atom, thenBranch: Expr, elseBranch: Expr)

enum Expr:
  case Let(x: Atom.Var, rhs: Rhs, body: Expr)
  case Ret(a: Atom)
  case Dup(x: Atom.Var, body: Expr)
  case Drop(x: Atom.Var, body: Expr)

final case class Fn(name: String, params: List[Atom.Var], ret: CType, body: Expr)

object Core:

  /** The boxed variable `a` is, if any: the only atoms reference counting tracks. */
  def boxedVar(a: Atom): Option[Atom.Var] =
    a match
      case v: Atom.Var if v.ctype.boxed => Some(v)
      case _                            => None

  /** The user functions `e` calls. */
  def callees(e: Expr): Set[String] =
    e match
      case Expr.Let(_, rhs, body) =>
        val own = rhs match
          case Rhs.Call(f, _, false) => Set(f)
          case Rhs.If(_, t, f)       => callees(t) ++ callees(f)
          case _                     => Set.empty[String]
        own ++ callees(body)
      case Expr.Dup(_, b)  => callees(b)
      case Expr.Drop(_, b) => callees(b)
      case Expr.Ret(_)     => Set.empty
