package fixed.prover

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Props through the static prover, right after the typer (`spec/properties.md` Rule P7.1). */
class PropsSuite extends munit.FunSuite:

  private def compile(props: List[String], steps: Long = Prover.defaultBudget) =
    val code = ("cap Checked:" :: props.map("    " + _)).mkString("", "\n", "\nfn main() -> () = ()\n")
    Compiler.compile(CompilationUnit(SourceFile.fromString("<props>", code), proverSteps = steps))

  /** The results by prop name, after checking that a second compile gives the same results, steps included. */
  private def results(props: String*): Map[String, Result] =
    val unit = compile(props.toList)
    assertEquals(compile(props.toList).props, unit.props)
    unit.props.map(v => v.name.stripPrefix("Checked.") -> v.result).toMap

  private def isProved(r: Result) = r match
    case _: Result.Proved => true
    case _                => false

  test("a prop in the fragment is proved, refuted with E102 and its counterexample, or left open by the budget"):
    val fits = "prop fits: forall (x: u8) suchThat x <= 150 -> x + 100 <= 255"
    val r = results(fits, "prop tooMuch: forall (x: u8) suchThat x <= 140 -> x + 100 < 240")
    assert(isProved(r("fits")), r)
    assertEquals(r("tooMuch"), Result.Refuted(Map("x" -> BigInt(140)), r("tooMuch").steps))
    val unit = compile(List(fits, "prop tooMuch: forall (x: u8) suchThat x <= 140 -> x + 100 < 240"))
    assertEquals(
      unit.diagnostics.map(d => (d.code, d.message)),
      List((DiagCode.E102, "prop failed: Checked.tooMuch; counterexample: x = 140"))
    )
    assertEquals(compile(List(fits), steps = 0).props.map(_.result), List(Result.Open("budget exhausted", 0)))

  test("an operation that leaves its type's range is a counterexample, where it is evaluated (Rules P4.2, P5.4)"):
    val r = results(
      "prop wraps: forall (x: u8) -> x + 1 > x",
      "prop guarded: forall (x: u8) suchThat x < 255 -> x + 1 > x",
      "prop shortAnd: forall (x: u8) -> x < 200 && x + 55 <= 254 || x >= 200",
      "prop shortOr: forall (x: u8) -> x > 200 || x + 55 <= 255",
      "prop negMin: forall (x: i64) -> -x != 5 || x == -5",
      "prop neg: forall (x: i64) suchThat x > -9223372036854775808 -> -(-x) == x"
    )
    assertEquals(r("wraps"), Result.Refuted(Map("x" -> BigInt(255)), r("wraps").steps))
    assertEquals(r("negMin"), Result.Refuted(Map("x" -> VarType.I64.lo), r("negMin").steps))
    List("guarded", "shortAnd", "shortOr", "neg").foreach(n => assert(isProved(r(n)), n -> r(n)))
    val msg = compile(List("prop wraps: forall (x: u8) -> x + 1 > x")).diagnostics.map(_.message)
    assertEquals(
      msg,
      List("prop failed: Checked.wraps; counterexample: x = 255 (an integer operation leaves the range of its type)")
    )

  test("bool binders, bool equality, constants, and u64 at its top"):
    val r = results(
      "prop excluded: forall (a: bool, b: bool) -> (a == b) == (b == a) && (a || !a)",
      "prop closed: 2 * 3 + 1 == 7 || true",
      "prop top: forall (x: u64) -> x <= 18446744073709551615",
      "prop bool: forall (a: bool) -> a"
    )
    List("excluded", "top").foreach(n => assert(isProved(r(n)), n -> r(n)))
    assertEquals(r("closed"), Result.Open("a comparison of literals alone", 0))
    assertEquals(r("bool"), Result.Refuted(Map("a" -> BigInt(0)), r("bool").steps))
    assertEquals(
      compile(List("prop bool: forall (a: bool) -> a")).diagnostics.map(_.message),
      List("prop failed: Checked.bool; counterexample: a = false")
    )

  test("a prop outside the fragment stays open, without a prover step"):
    val r = results(
      "prop ofSelf: forall (s: Self) -> true",
      "prop mixed: forall (x: u8, y: i8) -> x + y >= 0",
      "prop wide: forall (x: u8) -> x <= 300",
      "prop div: forall (x: u8) -> x / 2 <= x",
      "prop unsignedNeg: forall (x: u8) -> -x <= 0",
      "prop free: forall (x: u8) -> x <= limit",
      "prop shadow: forall (x: u8) -> forall (x: u8) -> x >= 0",
      "prop product: forall (x: u8, y: u8) -> x * y >= 0",
      "prop constant: forall (x: i8) -> true || x < 64 * 2"
    )
    assertEquals(
      r,
      Map(
        "ofSelf" -> Result.Open("binder s of a type outside the fragment", 0),
        "mixed" -> Result.Open("operands of types u8 and i8", 0),
        "wide" -> Result.Open("literal 300 does not fit u8", 0),
        "div" -> Result.Open("BinOp is outside the fragment", 0),
        "unsignedNeg" -> Result.Open("UnaryOp is outside the fragment", 0),
        "free" -> Result.Open("`limit` is outside the fragment", 0),
        "shadow" -> Result.Open("a variable declared twice", 0),
        "product" -> Result.Open("product of two variables", 0),
        "constant" -> Result.Open("literal 128 does not fit i8", 0)
      )
    )

  test("props of data types, satisfactions and refinement bodies are found and named by their owner"):
    val code =
      """data Box:
        |    Box(v: u8)
        |    prop small: forall (x: i8) suchThat x < 60 && x > -60 -> x * 2 <= 120
        |cap Sized:
        |    fn size -> u64
        |Box satisfies Sized:
        |    fn size = 1
        |    prop sized: forall (x: u16) -> x >= 0
        |fn bounded() -> cap of i64 =
        |    prop low: forall (y: i32) -> y <= 2147483647
        |fn main() -> () = ()
        |""".stripMargin
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<props>", code)))
    assertEquals(unit.props.map(_.name), List("Box.small", "Box.Sized.sized", "bounded.low"))
    assert(unit.props.map(_.result).forall(isProved), unit.props)

  test("--prover-steps=N is taken from any position; a malformed one stays for the usage error"):
    assertEquals(
      fixed.Main.proverSteps(List("compile", "--prover-steps=500", "f.fixed")),
      (500L, List("compile", "f.fixed"))
    )
    assertEquals(fixed.Main.proverSteps(List("f.fixed")), (Prover.defaultBudget, List("f.fixed")))
    assertEquals(fixed.Main.proverSteps(List("--prover-steps=x")), (Prover.defaultBudget, List("--prover-steps=x")))

  test("E102 stops the compile before quantity inference; the count names proved and open props"):
    val failed = compile(List("prop wraps: forall (x: u8) -> x + 1 > x", "prop ofSelf: forall (s: Self) -> true"))
    assert(failed.hasErrors && failed.quantities.isEmpty)
    assertEquals(fixed.Main.props(failed), "props: 0 proved, 1 open")
    val passed = compile(List("prop fits: forall (x: u8) -> x <= 255", "prop k: forall (x: i8) -> x * (2 - 1) == x"))
    assert(!passed.hasErrors && passed.quantities.isDefined)
    assertEquals(fixed.Main.props(passed), "props: 2 proved, 0 open")
