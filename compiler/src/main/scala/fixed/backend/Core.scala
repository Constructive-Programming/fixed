package fixed.backend

/** Core: the backend's IR (`docs/plans/phase-4.0-backend-slice.md` §4.2). First order, in A-normal
  * form: every intermediate value is a named [[Atom.Var]]; a value of parameter type is a
  * [[CType.Slot]] handled through a witness parameter. Later passes are Core to Core.
  */
enum CType:
  case Int(bits: scala.Int, signed: Boolean)
  case Float(bits: scala.Int)
  case Bool
  case Unit

  /** A pointer to a heap object with a reference-count header. */
  case Boxed

  /** A value of parameter type in its 64-bit slot (Rule CM3.3); the variable `witness` holds its value witness. */
  case Slot(witness: String)

  /** Pointers to a value witness record and to a capability table: static data, never counted. */
  case Witness, Table

  /** An immediate data value (`spec/compilation_model.md` Rule CM3.5, `Immediates`): no cell, its
    * bits in an `i64`, zero above its `width` bytes. With `tagged`, the variant tag is the low byte
    * and the fields follow it; without, the fields start at bit 0. Each field takes its rest width
    * ([[Layout.width]]), in declaration order, with no padding ([[Layout.offsets]]).
    */
  case Imm(width: scala.Int, tagged: Boolean)

  def boxed: Boolean = this == CType.Boxed

  def slot: Boolean = this match
    case _: CType.Slot => true
    case _             => false

  def immediate: Boolean = this match
    case _: CType.Imm => true
    case _            => false

  /** May hold a counted reference: what reference counting and the analyses track. */
  def counted: Boolean = this match
    case CType.Boxed | _: CType.Slot => true
    case _                           => false

/** An operand: a variable or a constant. A string constant is a static object (never counted). */
enum Atom:
  case Var(name: String, ctype: CType)
  case IntC(value: BigInt, ctype: CType)
  case FloatC(value: BigDecimal, ctype: CType)
  case BoolC(value: Boolean)
  case UnitC
  case StrC(value: String)

  /** The runtime's value witness `fx_w_<name>`, `imm` or `ref`. */
  case StaticWitness(name: String)

  /** Table `key`: per entry its shared and exclusive code (or `""`); stored exclusive first (Rule CM4.2). */
  case StaticTable(key: String, entries: List[(String, String)])

  /** A table built at run time (`fx_table`): the entries of `layout`, then `captures`, the
    * witnesses and tables of the type's arguments, which the entries read as payload slots from
    * [[Layout.captured]] on. Equal contents give the same table.
    */
  case DynTable(layout: StaticTable, captures: List[Atom])

  def tpe: CType =
    this match
      case Var(_, t)        => t
      case IntC(_, t)       => t
      case FloatC(_, t)     => t
      case BoolC(_)         => CType.Bool
      case UnitC            => CType.Unit
      case StrC(_)          => CType.Boxed
      case _: StaticWitness => CType.Witness
      case _: StaticTable   => CType.Table
      case _: DynTable      => CType.Table

extension (rhs: Rhs)
  /** `rhs` with `f` applied to each branch of an `If` or `Switch`; other right-hand sides unchanged. */
  def mapBranches(f: Expr => Expr): Rhs =
    rhs match
      case Rhs.If(c, t, e)      => Rhs.If(c, f(t), f(e))
      case Rhs.Switch(s, cases) => Rhs.Switch(s, cases.map((tag, c) => (tag, f(c))))
      case other                => other

/** Scalar operations. `Widen` converts to the result variable's wider type of the same kind;
  * `Convert` converts an `i64` to the result's narrower integer or float type (`Numeric.fromI64`).
  */
enum PrimOp:
  case Add, Sub, Mul, Div, Rem, Eq, Ne, Lt, Le, Gt, Ge, Neg, Not, Widen, Convert

object PrimOp:

  /** The scalar operation of source operator `op`; `&&` and `||` never reach it, `Lower` makes them branches. */
  def of(op: fixed.ast.Trees.BinOperator): PrimOp =
    import fixed.ast.Trees.BinOperator as B
    op match
      case B.Add        => Add
      case B.Sub        => Sub
      case B.Mul        => Mul
      case B.Div        => Div
      case B.Mod        => Rem
      case B.Eq         => Eq
      case B.Neq        => Ne
      case B.Lt         => Lt
      case B.Le         => Le
      case B.Gt         => Gt
      case B.Ge         => Ge
      case B.And | B.Or => Eq

/** The right-hand side of a `Let`. Calls consume their counted arguments. */
enum Rhs:

  /** `a` under a new name; converted to or from slot form where exactly one side is a slot (Rule CM3.3). */
  case Move(a: Atom)
  case Prim(op: PrimOp, args: List[Atom])

  /** A call of a user function (`runtime = false`) or of a C runtime function. */
  case Call(fn: String, args: List[Atom], runtime: Boolean)
  case If(cond: Atom, thenBranch: Expr, elseBranch: Expr)

  /** A data value (or tuple): variant `tag` with `fields` in declaration order. A cell with no
    * fields is a static object; a value of a [[CType.Imm]] variable is its bits, no cell. Consumes
    * the fields.
    */
  case Con(tag: Int, fields: List[Atom])

  /** Read payload slot `slot` of `obj` (see [[Layout]]), or, where `obj` is an immediate, the field
    * at bit offset `slot` ([[Layout.fieldsOf]]). Borrows: `obj` is not consumed, and a counted result
    * is dup'ed by reference counting.
    */
  case Field(obj: Atom, slot: Int)

  /** Branch on `scrutinee`'s tag: a cell's header tag, an immediate's low byte (Rule CM3.5), or
    * variant 0 for an immediate with one variant. The last case is also the default.
    */
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

  /** Call entry `entry` of `table` through either pointer, like `CallClosure` (Rule CM4.6). */
  case CallTable(table: Atom, entry: Int, args: List[Atom], exclusive: Boolean = false)

  /** Call a `handle` subject function: like `Call`, but not followed by an abort test (`Aborts`). */
  case Catch(fn: String, args: List[Atom])

  /** `Con(tag, fields)` built in the memory of the dead cell `cell` (`spec/memory_management.md`
    * §11): unconditionally where the cell is exclusively owned, else after a runtime `rc == 1`
    * test, allocating fresh when it fails. Consumes the cell and the fields.
    */
  case Reuse(cell: Atom, tag: Int, fields: List[Atom], unconditional: Boolean)

/** `Dup` and `Drop` of a [[CType.Slot]] variable go through its value witness. */
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

  /** The counted variable `a` is, if any: the only atoms reference counting tracks. */
  def countedVar(a: Atom): Option[Atom.Var] =
    a match
      case v: Atom.Var if v.ctype.counted => Some(v)
      case _                              => None

  /** Whether every value `e` returns is a slot exactly where `rt`, or the variable a branch binds,
    * is one; a unit stands for the value of dead code after an abort.
    */
  def returnsAs(e: Expr, rt: CType): Boolean =
    e match
      case Expr.Let(x, rhs, body) => branches(rhs).forall(returnsAs(_, x.ctype)) && returnsAs(body, rt)
      case Expr.Ret(a)            => a == Atom.UnitC || a.tpe.slot == rt.slot
      case Expr.Dup(_, b)         => returnsAs(b, rt)
      case Expr.Drop(_, b)        => returnsAs(b, rt)

  /** The slot variables `e` duplicates or drops, each a call of its witness. Reference counting is
    * applied with no borrowing, so a discarded slot is dropped by the function that discards it.
    */
  def witnessOps(e: Expr): List[Atom.Var] =
    def op(x: Atom.Var, b: Expr) = Core.witnessOf(x).map(_ => x).toList ++ witnessOps(b)
    e match
      case Expr.Dup(x, b)      => op(x, b)
      case Expr.Drop(x, b)     => op(x, b)
      case Expr.Let(_, rhs, b) => Core.branches(rhs).flatMap(witnessOps) ++ witnessOps(b)
      case Expr.Ret(_)         => Nil

  /** The witness variable of slot variable `v`. */
  def witnessOf(v: Atom.Var): Option[Atom.Var] =
    v.ctype match
      case CType.Slot(w) => Some(Atom.Var(w, CType.Witness))
      case _             => None

  /** The operands of `rhs` itself, not of its branches. */
  def atoms(rhs: Rhs): List[Atom] =
    rhs match
      case Rhs.Move(a)                => List(a)
      case Rhs.Prim(_, as)            => as
      case Rhs.Call(_, as, _)         => as
      case Rhs.If(c, _, _)            => List(c)
      case Rhs.Con(_, fs)             => fs
      case Rhs.Field(o, _)            => List(o)
      case Rhs.Switch(x, _)           => List(x)
      case Rhs.Closure(_, cs, _)      => cs
      case Rhs.CallClosure(c, as, _)  => c :: as
      case Rhs.CallTable(t, _, as, _) => t :: as
      case Rhs.Catch(_, as)           => as
      case Rhs.Reuse(c, _, fs, _)     => c :: fs

  /** The code of the closures and table entries `fns` name: called only indirectly, with slots (Rule CM3.3(3)). */
  def indirectEntries(fns: List[Fn]): Set[String] =
    fns.flatMap(f =>
      lets(f.body).collect { case (_, Rhs.Closure(c, _, x)) => List(c, x) }.flatten ++ tableEntries(f.body)
    )
      .toSet - ""

  /** The code of the tables `e` names anywhere, its results included. */
  def tableEntries(e: Expr): List[String] =
    def of(a: Atom): List[String] =
      a match
        case Atom.StaticTable(_, es) => es.flatMap((s, x) => List(s, x))
        case Atom.DynTable(l, cs)    => of(l) ++ cs.flatMap(of)
        case _                       => Nil
    operands(e).flatMap(of).filter(_.nonEmpty)

  def branches(rhs: Rhs): List[Expr] =
    rhs match
      case Rhs.If(_, t, f)      => List(t, f)
      case Rhs.Switch(_, cases) => cases.map(_._2)
      case _                    => Nil

  private def operands(e: Expr): List[Atom] =
    e match
      case Expr.Let(_, r, b) => atoms(r) ++ branches(r).flatMap(operands) ++ operands(b)
      case Expr.Ret(a)       => List(a)
      case Expr.Dup(_, b)    => operands(b)
      case Expr.Drop(_, b)   => operands(b)

  /** The user functions `e` calls, closures' and tables' code included. */
  def callees(e: Expr): Set[String] = calls(e) ++ tableEntries(e)

  private def calls(e: Expr): Set[String] =
    e match
      case Expr.Let(_, rhs, body) =>
        val own = rhs match
          case Rhs.Call(f, _, false)   => Set(f)
          case Rhs.Closure(code, _, s) => Set(code) ++ Option(s).filter(_.nonEmpty)
          case Rhs.Catch(f, _)         => Set(f)
          case _                       => Set.empty[String]
        own ++ branches(rhs).flatMap(calls) ++ calls(body)
      case Expr.Dup(_, b)  => calls(b)
      case Expr.Drop(_, b) => calls(b)
      case Expr.Ret(_)     => Set.empty

  /** Every function reachable from `from`, closures' code included. */
  def reach(from: Set[String], byName: collection.Map[String, Fn]): Set[String] =
    val next = from ++ from.flatMap(f => byName.get(f).toList.flatMap(g => callees(g.body)))
    if next == from then from else reach(next, byName)

  /** The variables atom `a` reads: itself, or the captures of a table built at run time. */
  def vars(a: Atom): List[Atom.Var] =
    a match
      case v: Atom.Var          => List(v)
      case Atom.DynTable(_, cs) => cs.flatMap(vars)
      case _                    => Nil

  /** Every variable `e` uses but does not bind (a slot's witness too): a lambda's captures. */
  def freeVars(e: Expr): Set[Atom.Var] =
    def vars(as: List[Atom]): Set[Atom.Var] = as.flatMap(Core.vars).flatMap(v => v :: witnessOf(v).toList).toSet
    e match
      case Expr.Let(x, r, body) => vars(atoms(r)) ++ branches(r).flatMap(freeVars) ++ (freeVars(body) - x)
      case Expr.Ret(a)          => vars(List(a))
      case Expr.Dup(x, b)       => freeVars(b) ++ vars(List(x))
      case Expr.Drop(x, b)      => freeVars(b) ++ vars(List(x))

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

  /** The payload slot of capture `i` of a table built at run time with `entries` entries
    * ([[Atom.DynTable]]): the entries take two pointers each from offset 0, a payload from 16.
    */
  def captured(entries: Int, i: Int): Int = 2 * entries - 2 + i

  /** The width of the header's reference map: only slots below it can hold a reference. */
  val refMapBits: Int = 32

  /** The slot of each field: declaration order, after `first` reserved slots. */
  def slots(fields: List[CType], first: Int = 0): List[Int] = fields.indices.map(first + _).toList

  /** The slots among `slots(fields, first)` that may hold counted references. */
  def refSlotsOf(fields: List[CType], first: Int = 0): List[Int] =
    slots(fields, first).zip(fields).collect { case (s, t) if t.counted => s }

  /** The rest width in bytes of a value of Core type `t` inside an immediate (`spec/compilation_model.md`
    * Rule CM3.6): a scalar's size (`bool` and `()` one byte), an immediate's width; none for a reference.
    */
  def width(t: CType): Option[Int] =
    t match
      case CType.Int(bits, _)      => Some(bits / 8)
      case CType.Float(bits)       => Some(bits / 8)
      case CType.Bool | CType.Unit => Some(1)
      case CType.Imm(w, _)         => Some(w)
      case _                       => None

  /** The bit offset of each field, of Core types `fields`, inside an immediate: from bit 8 after a
    * tag byte, else from bit 0, each at its rest width, in declaration order (Rule CM3.5).
    */
  def offsets(fields: List[CType], tagged: Boolean): List[Int] =
    fields.scanLeft(if tagged then 8 else 0) { (at, t) =>
      require(width(t).isDefined, s"a field of Core type $t inside an immediate")
      at + 8 * width(t).getOrElse(0)
    }.init

  /** Where each field, of Core types `fields`, of a value of Core type `obj` is, as `Rhs.Field`
    * reads it: its bit offset if `obj` is an immediate ([[offsets]]), else its payload slot ([[slots]]).
    */
  def fieldsOf(obj: CType, fields: List[CType]): List[Int] =
    obj match
      case CType.Imm(_, tagged) => offsets(fields, tagged)
      case _                    => slots(fields)

  /** The reference map's static bits (a slot's comes from its witness); overflow is `Lower`'s `B005`. */
  def refs(fields: List[CType], first: Int = 0): Int =
    slots(fields, first).zip(fields).foldLeft(0) {
      case (m, (s, t)) if t.counted =>
        require(s < refMapBits, s"a reference in payload slot $s is beyond the reference map")
        if t.boxed then m | 1 << s else m
      case (m, _) => m
    }

/** The Core types of the typer's scalar types, and the runtime functions that show them. */
object Scalars:
  import fixed.core.Type

  private val ints =
    Map("i8" -> 8, "i16" -> 16, "i32" -> 32, "i64" -> 64, "u8" -> 8, "u16" -> 16, "u32" -> 32, "u64" -> 64)

  /** The runtime function that shows a scalar of Core type `t` (a `char` if `char`), and the type it takes. */
  def formatter(t: CType, char: Boolean): Option[(String, CType)] =
    t match
      case _ if char          => Some(("fx_show_char", t))
      case CType.Int(_, true) => Some(("fx_show_i64", CType.Int(64, signed = true)))
      case CType.Int(_, _)    => Some(("fx_show_u64", CType.Int(64, signed = false)))
      case CType.Float(_)     => Some(("fx_show_f64", CType.Float(64)))
      case CType.Bool         => Some(("fx_show_bool", CType.Bool))
      case _                  => None

  /** The Core type of a scalar typer type. */
  def scalar(t: Type): Option[CType] =
    t match
      case Type.PrimType(n) if ints.contains(n) => Some(CType.Int(ints(n), signed = n.startsWith("i")))
      case Type.PrimType("f32")                 => Some(CType.Float(32))
      case Type.PrimType("f64")                 => Some(CType.Float(64))
      case Type.PrimType("bool")                => Some(CType.Bool)
      case Type.PrimType("char")                => Some(CType.Int(32, signed = false))
      case Type.UnitType | Type.NeverType       => Some(CType.Unit)
      case _                                    => None
