package fixed.prover

/** The type of a variable: a bounded integer type of Fixed with its range, or `bool`, which ranges over 0 (false) and 1
  * (true).
  */
enum VarType(val lo: BigInt, val hi: BigInt):
  case I8 extends VarType(-(BigInt(1) << 7), (BigInt(1) << 7) - 1)
  case I16 extends VarType(-(BigInt(1) << 15), (BigInt(1) << 15) - 1)
  case I32 extends VarType(-(BigInt(1) << 31), (BigInt(1) << 31) - 1)
  case I64 extends VarType(-(BigInt(1) << 63), (BigInt(1) << 63) - 1)
  case U8 extends VarType(0, (BigInt(1) << 8) - 1)
  case U16 extends VarType(0, (BigInt(1) << 16) - 1)
  case U32 extends VarType(0, (BigInt(1) << 32) - 1)
  case U64 extends VarType(0, (BigInt(1) << 64) - 1)
  case Bool extends VarType(0, 1)

  /** The Fixed spelling: `i8`, `u64`, `bool`. */
  def spelling: String = toString.toLowerCase

  def contains(v: BigInt): Boolean = lo <= v && v <= hi

object VarType:
  /** The type spelled `name` in Fixed, if it is one of these. */
  def named(name: String): Option[VarType] = values.find(_.spelling == name)

/** An integer term over mathematical integers: no wrap-around. */
enum Term:
  case Var(name: String)
  case Const(value: BigInt)
  case Add(l: Term, r: Term)
  case Sub(l: Term, r: Term)

  /** In the fragment only when one side has no variable. */
  case Mul(l: Term, r: Term)

enum CmpOp:
  case Eq, Neq, Lt, Le, Gt, Ge

  /** Whether the comparison holds of two values whose `compare` gives `sign`. */
  def holds(sign: Int): Boolean = this match
    case Eq  => sign == 0
    case Neq => sign != 0
    case Lt  => sign < 0
    case Le  => sign <= 0
    case Gt  => sign > 0
    case Ge  => sign >= 0

enum Formula:
  case Lit(value: Boolean)

  /** A variable of type `bool`. */
  case BoolVar(name: String)
  case Cmp(op: CmpOp, l: Term, r: Term)
  case Not(f: Formula)
  case And(l: Formula, r: Formula)
  case Or(l: Formula, r: Formula)
  case Implies(l: Formula, r: Formula)
  case Iff(l: Formula, r: Formula)

/** A proof obligation: for every value of `vars` in its type's range, the `hypotheses` imply the `goal`. */
final case class Obligation(vars: List[(String, VarType)], hypotheses: List[Formula], goal: Formula):

  /** Whether `values` is a counterexample: every variable in its range, the hypotheses true and the goal false. A variable
    * missing from `values`, or a variable the obligation does not declare, makes this false.
    */
  def refutedBy(values: Map[String, BigInt]): Boolean =
    vars.forall((n, t) => values.get(n).exists(t.contains)) &&
      Obligation.mentioned(this).forall(values.contains) &&
      hypotheses.forall(Obligation.eval(_, values)) && !Obligation.eval(goal, values)

object Obligation:
  /** The value of `t`; every variable of `t` must be in `values`. */
  def eval(t: Term, values: Map[String, BigInt]): BigInt = t match
    case Term.Var(n)    => values(n)
    case Term.Const(v)  => v
    case Term.Add(l, r) => eval(l, values) + eval(r, values)
    case Term.Sub(l, r) => eval(l, values) - eval(r, values)
    case Term.Mul(l, r) => eval(l, values) * eval(r, values)

  def eval(f: Formula, values: Map[String, BigInt]): Boolean = f match
    case Formula.Lit(b)        => b
    case Formula.BoolVar(n)    => values(n) == 1
    case Formula.Cmp(op, l, r) => op.holds(eval(l, values).compare(eval(r, values)))
    case Formula.Not(g)        => !eval(g, values)
    case Formula.And(l, r)     => eval(l, values) && eval(r, values)
    case Formula.Or(l, r)      => eval(l, values) || eval(r, values)
    case Formula.Implies(l, r) => !eval(l, values) || eval(r, values)
    case Formula.Iff(l, r)     => eval(l, values) == eval(r, values)

  /** The value of `t` when it has no variable. */
  def constant(t: Term): Option[BigInt] = t match
    case Term.Var(_)    => None
    case Term.Const(v)  => Some(v)
    case Term.Add(l, r) => constant(l).zip(constant(r)).map(_ + _)
    case Term.Sub(l, r) => constant(l).zip(constant(r)).map(_ - _)
    case Term.Mul(l, r) => constant(l).zip(constant(r)).map(_ * _)

  /** Every variable name `ob` mentions in its hypotheses and goal. */
  def mentioned(ob: Obligation): Set[String] =
    def term(t: Term): Set[String] = t match
      case Term.Var(n)    => Set(n)
      case Term.Const(_)  => Set.empty
      case Term.Add(l, r) => term(l) ++ term(r)
      case Term.Sub(l, r) => term(l) ++ term(r)
      case Term.Mul(l, r) => term(l) ++ term(r)
    def formula(f: Formula): Set[String] = f match
      case Formula.Lit(_)        => Set.empty
      case Formula.BoolVar(n)    => Set(n)
      case Formula.Cmp(_, l, r)  => term(l) ++ term(r)
      case Formula.Not(g)        => formula(g)
      case Formula.And(l, r)     => formula(l) ++ formula(r)
      case Formula.Or(l, r)      => formula(l) ++ formula(r)
      case Formula.Implies(l, r) => formula(l) ++ formula(r)
      case Formula.Iff(l, r)     => formula(l) ++ formula(r)
    (ob.goal :: ob.hypotheses).flatMap(formula).toSet

/** What the prover found, with the number of steps it used. */
enum Result:
  case Proved(steps: Long)

  /** Values for every variable under which the hypotheses hold and the goal does not; checked by evaluation. */
  case Refuted(values: Map[String, BigInt], steps: Long)

  /** Undecided: the budget ran out, or the obligation is outside the fragment. */
  case Open(reason: String, steps: Long)

  def steps: Long
