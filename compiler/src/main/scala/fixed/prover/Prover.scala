package fixed.prover

import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import ap.basetypes.IdealInt
import ap.parameters.GoalSettings
import ap.parser.{IBoolLit, IConstant, IFormula, IIntLit, ITerm, ITimes, InputAbsy2Internal}
import ap.proof.{ConstraintSimplifier, ModelSearchProver, SimpleSimplifier}
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.{ConstantTerm, TermOrder}
import ap.util.{Debug, LRUCache, Timeout}

/** The static prover (`spec/properties.md` Rule P7.1), over Princess's lower interface, on the calling thread.
  *
  * The budget counts steps (Rule P7.1.a): a step is one call of Princess's check point (`Timeout.check`), counted by the
  * checker this object installs. The count depends only on the obligation and the library's version, never on the
  * machine, the clock or what was proved before. Three things keep it so: each call gets a new `ModelSearchProver` (the
  * shared one keeps a cache of results), the global simplifier's cache is emptied before each call, and the static data
  * Princess computes in class initialisers is computed at start-up (the `locally` block below). Princess's convenience
  * interface (`SimpleAPI`) proves on another thread, which the checker does not reach, so it is not used.
  */
object Prover:
  /** The library and its version; part of the key of a recorded status (Rule P7.1.b). */
  val version: String = "princess-2025-06-25"

  /** The default budget in steps (Rule P7.1.a). */
  val defaultBudget: Long = 100000

  /** Proves `ob` within `budget` steps. A budget that runs out, an obligation outside the fragment (a product of two
    * variables, a variable not declared or declared twice) and a failure of the library are all `Open`, never an
    * exception. One call at a time: Princess's simplifier cache is global.
    */
  def prove(ob: Obligation, budget: Long): Result = synchronized {
    val consts = ob.vars.map((n, _) => n -> new ConstantTerm(n))
    val env = consts.toMap
    val undeclared = Obligation.mentioned(ob) -- env.keySet
    val ranges = ob.vars.map((n, t) => IConstant(env(n)) >= lit(t.lo) & IConstant(env(n)) <= lit(t.hi))
    def claim =
      for
        hyps <- all(ob.hypotheses.map(formula(_, env)))
        goal <- formula(ob.goal, env)
      yield (ranges ++ hyps).foldLeft[IFormula](IBoolLit(true))(_ & _) ==> goal
    if undeclared.nonEmpty then Result.Open(s"undeclared variable ${undeclared.toList.sorted.mkString(", ")}", 0)
    else if env.size < consts.size then Result.Open("a variable declared twice", 0)
    else claim.fold(Result.Open(_, 0), search(ob, _, TermOrder.EMPTY.extend(consts.map(_._2)), consts, budget max 0))
  }

  /** Searches a countermodel of `claim` within `budget` steps. */
  private def search(
      ob: Obligation,
      claim: IFormula,
      order: TermOrder,
      consts: List[(String, ConstantTerm)],
      budget: Long
  ): Result =
    clearCaches()
    val steps = new AtomicLong(0)
    def used = math.min(steps.get, budget)
    try
      val countermodel = Debug.enabledAssertions.withValue((_, _) => false) {
        Timeout.withChecker { () =>
          if steps.incrementAndGet() > budget && !initialising then Timeout.raise
        }(new ModelSearchProver(GoalSettings.DEFAULT)(InputAbsy2Internal(claim, order), order))
      }
      if steps.get > budget then Result.Open("budget exhausted", used)
      else if countermodel.isFalse then Result.Proved(used)
      else
        val eqs = countermodel.arithConj.positiveEqs.toMap
        val values = consts.flatMap { (n, c) =>
          eqs.get(c).filter(solved(c, _)).map(lc => n -> -BigInt(lc.constant.bigIntValue))
        }.toMap
        if ob.refutedBy(values) then Result.Refuted(values, used)
        else Result.Open("the countermodel did not check", used)
    catch
      case _: Timeout  => Result.Open("budget exhausted", used)
      case NonFatal(e) => Result.Open(s"prover failure: ${e.getMessage}", used)

  /** Whether the equation `lc = 0` reads `c + k = 0`, giving `c` the value `-k`. */
  private def solved(c: ConstantTerm, lc: LinearCombination): Boolean =
    lc.leadingTerm == c && lc.leadingCoeff.isOne && lc.size == (if lc.constant.isZero then 1 else 2)

  /** Empties the result cache of Princess's global constraint simplifier, which would otherwise carry work from one
    * obligation to the next and make the step count depend on what was proved before.
    */
  private def clearCaches(): Unit =
    val cache = classOf[SimpleSimplifier].getDeclaredField("cache")
    cache.setAccessible(true)
    val backend = classOf[LRUCache[?, ?]].getDeclaredField("backend")
    backend.setAccessible(true)
    val map = backend.get(cache.get(ConstraintSimplifier.FAIR_SIMPLIFIER))
    val _ = map.getClass.getMethod("clear").invoke(map)

  private val walker = StackWalker.getInstance()

  /** Whether one of Princess's class initialisers is running on this thread. The budget is not enforced there: a
    * `Timeout` raised in an initialiser would leave its class unusable for the rest of the process. The steps still
    * count, so the result is open all the same.
    */
  private def initialising: Boolean =
    walker.walk(_.anyMatch(f => f.getMethodName == "<clinit>" && f.getClassName.startsWith("ap.")))

  private[prover] def all[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) => x.flatMap(a => acc.map(a :: _)))

  private def lit(v: BigInt): ITerm = IIntLit(IdealInt(v.bigInteger))

  /** `t` in Princess's terms; `Left` with the reason when `t` is outside the fragment. */
  private def term(t: Term, env: Map[String, ConstantTerm]): Either[String, ITerm] = t match
    case Term.Var(n)    => Right(IConstant(env(n)))
    case Term.Const(v)  => Right(lit(v))
    case Term.Add(l, r) => both(term(l, env), term(r, env))(_ + _)
    case Term.Sub(l, r) => both(term(l, env), term(r, env))(_ - _)
    case Term.Mul(l, r) =>
      (Obligation.constant(l), Obligation.constant(r)) match
        case (Some(k), _) => term(r, env).map(ITimes(IdealInt(k.bigInteger), _))
        case (_, Some(k)) => term(l, env).map(ITimes(IdealInt(k.bigInteger), _))
        case _            => Left("product of two variables")

  private def both[A, B](l: Either[String, A], r: Either[String, A])(f: (A, A) => B): Either[String, B] =
    for a <- l; b <- r yield f(a, b)

  private def formula(f: Formula, env: Map[String, ConstantTerm]): Either[String, IFormula] = f match
    case Formula.Lit(b)        => Right(IBoolLit(b))
    case Formula.BoolVar(n)    => Right(IConstant(env(n)) === lit(1))
    case Formula.Cmp(op, l, r) =>
      both(term(l, env), term(r, env)) { (a, b) =>
        op match
          case CmpOp.Eq  => a === b
          case CmpOp.Neq => a =/= b
          case CmpOp.Lt  => a < b
          case CmpOp.Le  => a <= b
          case CmpOp.Gt  => a > b
          case CmpOp.Ge  => a >= b
      }
    case Formula.Not(g)        => formula(g, env).map(!_)
    case Formula.And(l, r)     => both(formula(l, env), formula(r, env))(_ & _)
    case Formula.Or(l, r)      => both(formula(l, env), formula(r, env))(_ | _)
    case Formula.Implies(l, r) => both(formula(l, env), formula(r, env))(_ ==> _)
    case Formula.Iff(l, r)     => both(formula(l, env), formula(r, env))((a, b) => (a ==> b) & (b ==> a))

  /** Princess computes static data in class initialisers, once per process, on the thread that first needs it; those
    * steps would count against that obligation alone. Proving these at start-up, with no budget, keeps them out of every
    * count.
    */
  locally {
    val (x, b) = (Term.Var("x"), Formula.BoolVar("b"))
    val vars = List("x" -> VarType.I64, "b" -> VarType.Bool)
    val small = Formula.Cmp(CmpOp.Lt, Term.Mul(Term.Const(2), x), Term.Const(5))
    List(Formula.Or(b, Formula.Not(b)), Formula.Or(b, small)).foreach(g =>
      prove(Obligation(vars, Nil, g), Long.MaxValue)
    )
  }
