package fixed.prover

import fixed.prover.Formula.{And, Cmp, Not, Or}
import fixed.prover.Term.{Add, Const, Mul, Sub, Var}

/** The static prover on obligations written directly. Every obligation is proved twice: the result and the step count
  * must be the same both times (Rule P7.1.a).
  */
class ProverSuite extends munit.FunSuite:

  private def x = Var("x")
  private def c(v: BigInt) = Const(v)

  /** The result for `ob`, after checking that a second run gives the same result and the same step count. */
  private def prove(ob: Obligation, budget: Long = Prover.defaultBudget): Result =
    val first = Prover.prove(ob, budget)
    assertEquals(Prover.prove(ob, budget), first)
    first

  private def proved(ob: Obligation) = prove(ob) match
    case r: Result.Proved => r.steps
    case other            => fail(s"expected proved, got $other")

  private def refuted(ob: Obligation) = prove(ob) match
    case Result.Refuted(values, _) => values
    case other                     => fail(s"expected a counterexample, got $other")

  private val upTo150 = List(Cmp(CmpOp.Le, x, c(150)))
  private val u8x = List("x" -> VarType.U8)

  test("the probe: 0 <= x <= 150 gives x + 100 <= 255, not x + 106 <= 255; a divisor >= 1 is not zero"):
    proved(Obligation(u8x, upTo150, Cmp(CmpOp.Le, Add(x, c(100)), c(255))))
    assertEquals(refuted(Obligation(u8x, upTo150, Cmp(CmpOp.Le, Add(x, c(106)), c(255)))), Map("x" -> BigInt(150)))
    proved(Obligation(List("d" -> VarType.U64), List(Cmp(CmpOp.Ge, Var("d"), c(1))), Cmp(CmpOp.Neq, Var("d"), c(0))))

  test("i64 division: the divisor is not zero and the operands are not MIN and -1 (Rule E3.6.d)"):
    val (a, b) = (Var("a"), Var("b"))
    val safe = And(Cmp(CmpOp.Neq, b, c(0)), Not(And(Cmp(CmpOp.Eq, a, c(VarType.I64.lo)), Cmp(CmpOp.Eq, b, c(-1)))))
    val vars = List("a" -> VarType.I64, "b" -> VarType.I64)
    proved(Obligation(vars, List(Cmp(CmpOp.Ge, b, c(1))), safe))
    val cex = refuted(Obligation(vars, List(Cmp(CmpOp.Neq, a, c(VarType.I64.lo))), safe))
    assertEquals(cex("b"), BigInt(0))

  test("boundaries of u64 and i64 do not overflow a Long"):
    val u64 = List("x" -> VarType.U64)
    proved(Obligation(u64, Nil, Cmp(CmpOp.Le, x, c(VarType.U64.hi))))
    assertEquals(
      refuted(Obligation(u64, Nil, Cmp(CmpOp.Le, Add(x, c(1)), c(VarType.U64.hi)))),
      Map("x" -> VarType.U64.hi)
    )
    assertEquals(VarType.U64.hi, BigInt("18446744073709551615"))
    val i64 = List("x" -> VarType.I64)
    proved(Obligation(i64, Nil, Cmp(CmpOp.Ge, x, c(VarType.I64.lo))))
    assertEquals(
      refuted(Obligation(i64, Nil, Cmp(CmpOp.Ge, Sub(x, c(1)), c(VarType.I64.lo)))),
      Map("x" -> VarType.I64.lo)
    )
    assert(refuted(Obligation(i64, Nil, Cmp(CmpOp.Le, Mul(c(2), x), c(VarType.I64.hi)))).apply("x") * 2 >
      VarType.I64.hi)

  test("every integer type's range, a product with the constant on either side, every connective"):
    VarType.values.foreach { t =>
      val in = Formula.And(Cmp(CmpOp.Ge, x, c(t.lo)), Formula.Not(Cmp(CmpOp.Gt, x, c(t.hi))))
      proved(Obligation(List("x" -> t), Nil, in))
      assertEquals(refuted(Obligation(List("x" -> t), Nil, Cmp(CmpOp.Lt, x, c(t.hi)))), Map("x" -> t.hi))
    }
    val doubled = Cmp(CmpOp.Eq, Mul(x, Add(c(1), c(1))), Add(x, x))
    proved(Obligation(u8x, Nil, Formula.Iff(doubled, Formula.Lit(true))))
    proved(Obligation(u8x, Nil, Formula.Implies(Cmp(CmpOp.Gt, x, c(200)), Cmp(CmpOp.Ge, Mul(x, c(-1)), c(-255)))))
    assertEquals(prove(Obligation(u8x ++ u8x, Nil, Formula.Lit(true))), Result.Open("a variable declared twice", 0))

  test("bool variables range over 0 and 1"):
    val b = Formula.BoolVar("b")
    proved(Obligation(List("b" -> VarType.Bool), Nil, Or(b, Not(b))))
    assertEquals(refuted(Obligation(List("b" -> VarType.Bool), Nil, b)), Map("b" -> BigInt(0)))

  /** Pigeonhole: eight values in 1..7 have two equal. Unsatisfiable when negated, and hard. */
  private val pigeons =
    val names = (0 to 7).map(i => s"x$i").toList
    val xs = names.map(Var(_))
    val distinct = for (a, i) <- xs.zipWithIndex; b <- xs.drop(i + 1) yield Cmp(CmpOp.Eq, a, b)
    Obligation(
      names.map(_ -> VarType.U8),
      xs.flatMap(v => List(Cmp(CmpOp.Ge, v, c(1)), Cmp(CmpOp.Le, v, c(7)))),
      distinct.reduce(Or(_, _))
    )

  test("a hard obligation exhausts a small budget: open, with the budget's steps; the default budget proves it"):
    assertEquals(prove(pigeons, 1000), Result.Open("budget exhausted", 1000))
    assert(proved(pigeons) > 1000)

  test("outside the fragment: a product of two variables, an undeclared variable"):
    val y = Var("y")
    val product = Obligation(List("x" -> VarType.U8, "y" -> VarType.U8), Nil, Cmp(CmpOp.Ge, Mul(x, y), c(0)))
    assertEquals(prove(product), Result.Open("product of two variables", 0))
    assertEquals(prove(Obligation(Nil, Nil, Cmp(CmpOp.Ge, x, c(0)))), Result.Open("undeclared variable x", 0))

  test("a counterexample is checked by evaluation"):
    val ob = Obligation(u8x, upTo150, Cmp(CmpOp.Le, Add(x, c(106)), c(255)))
    assert(ob.refutedBy(Map("x" -> BigInt(150))))
    assert(!ob.refutedBy(Map("x" -> BigInt(149))))
    assert(!ob.refutedBy(Map("x" -> BigInt(151))), "outside the hypothesis")
    assert(!ob.refutedBy(Map.empty), "a missing value")
