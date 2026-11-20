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

extension (rhs: Rhs)
  /** `rhs` with `f` applied to each branch of an `If` or `Switch`; other right-hand sides unchanged. */
  def mapBranches(f: Expr => Expr): Rhs =
    rhs match
      case Rhs.If(c, t, e)      => Rhs.If(c, f(t), f(e))
      case Rhs.Switch(s, cases) => Rhs.Switch(s, cases.map((tag, c) => (tag, f(c))))
      case other                => other

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

  /** A data value (or tuple): variant `tag` with `fields` in declaration order. A value with no
    * fields is a static object. Consumes the fields.
    */
  case Con(tag: Int, fields: List[Atom])

  /** Read payload slot `slot` of `obj` (see [[Layout]]). Borrows: `obj` is not consumed, and a
    * boxed result is dup'ed by reference counting.
    */
  case Field(obj: Atom, slot: Int)

  /** Branch on `scrutinee`'s tag. The last case is also the default. */
  case Switch(scrutinee: Atom, cases: List[(Int, Expr)])

  /** A closure of code function `code`, whose first parameter is the closure itself. A closure
    * with no captures is a static object. Consumes the captures. `code` is the shared entry, the
    * primary one; `exclusive`, if not empty, the exclusive one (`spec/memory_management.md` Rule PR5.6).
    */
  case Closure(code: String, captures: List[Atom], exclusive: String = "")

  /** Call a closure through its exclusive entry or its shared one (Rule PR5.6). Consumes the
    * closure and the arguments.
    */
  case CallClosure(closure: Atom, args: List[Atom], exclusive: Boolean = false)

  /** Call a `handle` subject function: like `Call`, but not followed by an abort test (`Aborts`). */
  case Catch(fn: String, args: List[Atom])

  /** `Con(tag, fields)` built in the memory of the dead cell `cell` (`spec/memory_management.md`
    * §11): unconditionally where the cell is exclusively owned, else after a runtime `rc == 1`
    * test, allocating fresh when it fails. Consumes the cell and the fields.
    */
  case Reuse(cell: Atom, tag: Int, fields: List[Atom], unconditional: Boolean)

enum Expr:
  case Let(x: Atom.Var, rhs: Rhs, body: Expr)
  case Ret(a: Atom)
  case Dup(x: Atom.Var, body: Expr)
  case Drop(x: Atom.Var, body: Expr)

/** A function; `version` is set on what [[Versions]] builds, and `instance` is what it is a version of. */
final case class Fn(name: String, params: List[Atom.Var], ret: CType, body: Expr, version: Option[Version] = None):
  def instance: String = version.fold(name)(_.of)

/** A version of `of`, `conditional` if built for less than every part `of` demands exclusive (it
  * then reuses only after an `rc == 1` test; a `fip` shared entry copies instead and is not).
  */
final case class Version(of: String, conditional: Boolean)

object Core:

  /** Every `let` of `e`, branches included, in order. */
  def lets(e: Expr): List[(Atom.Var, Rhs)] =
    e match
      case Expr.Let(x, rhs, body) =>
        val inner = rhs match
          case Rhs.If(_, t, f)      => lets(t) ++ lets(f)
          case Rhs.Switch(_, cases) => cases.flatMap(c => lets(c._2))
          case _                    => Nil
        (x, rhs) :: inner ++ lets(body)
      case Expr.Dup(_, b)  => lets(b)
      case Expr.Drop(_, b) => lets(b)
      case Expr.Ret(_)     => Nil

  /** `a` and `b` merged by key, `zero` standing for an absent entry. */
  def merge[V](a: Map[String, V], b: Map[String, V], zero: V)(f: (V, V) => V): Map[String, V] =
    (a.keySet ++ b.keySet).map(k => k -> f(a.getOrElse(k, zero), b.getOrElse(k, zero))).toMap

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
          case Rhs.Call(f, _, false)   => Set(f)
          case Rhs.Closure(code, _, s) => Set(code) ++ Option(s).filter(_.nonEmpty)
          case Rhs.Catch(f, _)         => Set(f)
          case Rhs.If(_, t, f)         => callees(t) ++ callees(f)
          case Rhs.Switch(_, cases)    => cases.flatMap((_, c) => callees(c)).toSet
          case _                       => Set.empty[String]
        own ++ callees(body)
      case Expr.Dup(_, b)  => callees(b)
      case Expr.Drop(_, b) => callees(b)
      case Expr.Ret(_)     => Set.empty

  /** Every function reachable from `from`, closures' code included. */
  def reach(from: Set[String], byName: collection.Map[String, Fn]): Set[String] =
    val next = from ++ from.flatMap(f => byName.get(f).toList.flatMap(g => callees(g.body)))
    if next == from then from else reach(next, byName)

  /** Every variable `e` uses but does not bind, of any type: a lambda's captures. */
  def freeVars(e: Expr): Set[Atom.Var] =
    def atoms(as: List[Atom]): Set[Atom.Var] = as.collect { case v: Atom.Var => v }.toSet
    def rhs(r: Rhs): Set[Atom.Var] =
      r match
        case Rhs.Move(a)               => atoms(List(a))
        case Rhs.Prim(_, as)           => atoms(as)
        case Rhs.Call(_, as, _)        => atoms(as)
        case Rhs.If(c, t, f)           => atoms(List(c)) ++ freeVars(t) ++ freeVars(f)
        case Rhs.Con(_, fs)            => atoms(fs)
        case Rhs.Field(o, _)           => atoms(List(o))
        case Rhs.Switch(x, cases)      => atoms(List(x)) ++ cases.flatMap((_, c) => freeVars(c))
        case Rhs.Closure(_, cs, _)     => atoms(cs)
        case Rhs.CallClosure(c, as, _) => atoms(c :: as)
        case Rhs.Catch(_, as)          => atoms(as)
        case Rhs.Reuse(c, _, fs, _)    => atoms(c :: fs)
    e match
      case Expr.Let(x, r, body) => rhs(r) ++ (freeVars(body) - x)
      case Expr.Ret(a)          => atoms(List(a))
      case Expr.Dup(x, b)       => freeVars(b) + x
      case Expr.Drop(x, b)      => freeVars(b) + x

/** Payload layout of a heap object (`spec/memory_management.md` Rule PR3.1, `spec/compilation_model.md`
  * Rules CM3.3 and CM3.4): every field takes one 64-bit slot, in declaration order, whatever the
  * type instance. The header's reference map has bit i set when slot i holds a counted reference.
  * A closure's slots 0 and 1 hold its code pointers; its captures follow.
  */
object Layout:

  /** The tag that marks a closure. */
  val closureTag: Int = -1

  /** Payload slots a closure spends on code pointers: the exclusive entry, then the shared one. */
  val closureCode: Int = 2

  /** The width of the header's reference map: only slots below it can hold a reference. */
  val refMapBits: Int = 32

  /** The slot of each field: declaration order, after `first` reserved slots. */
  def slots(fields: List[CType], first: Int = 0): List[Int] = fields.indices.map(first + _).toList

  /** The slots among `slots(fields, first)` that hold counted references. */
  def refSlotsOf(fields: List[CType], first: Int = 0): List[Int] =
    slots(fields, first).zip(fields).collect { case (s, t) if t.boxed => s }

  /** The header's reference map, negative when slot 31 holds a reference; `Lower` reports overflow as `B005`. */
  def refs(fields: List[CType], first: Int = 0): Int =
    refSlotsOf(fields, first).foldLeft(0) { (m, s) =>
      require(s < refMapBits, s"a reference in payload slot $s is beyond the reference map")
      m | 1 << s
    }
