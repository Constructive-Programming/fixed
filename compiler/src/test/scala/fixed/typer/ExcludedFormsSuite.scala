package fixed.typer

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Forms the rules exclude, each with its own error: a tuple of more than two elements (`T031`), the
  * reserved names `i128` and `u128` (`T032`).
  */
class ExcludedFormsSuite extends munit.FunSuite:

  private def diags(code: String): List[(DiagCode, Int, String)] =
    Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", code))).diagnostics
      .map(d => (d.code, d.span.start, d.message))

  test("Rule 5.12: a tuple expression, pattern or type of three elements is T031; parameter lists and `of` are not"):
    val lists =
      "cap Three of (A, B, C):\n    fn a -> A\n" +
        "fn f(g: (u64, u64, u64) -> u64) -> u64 = g(1, 2, 3)\n" +
        "fn h(_x: is Three of (u64, u64, u64)) -> u64 = 0\n" +
        "fn p(x: u64) -> (u64, u64) =\n    match (x, x):\n        (a, b) => (a, b)\n" +
        "fn q(_g: () -> cap of (u64, u64, u64)) -> u64 = 0\n"
    assertEquals(diags(lists), Nil)
    val tuples =
      "fn k(_x: (u64, u64, u64)) -> u64 = 0\n" +
        "fn m(x: u64) -> u64 =\n    match x:\n        (a, b, c) => a\n" +
        "fn n() -> u64 =\n    let _t = (1, 2, 3, 4)\n    0\n"
    val t031 = "a tuple has two elements; declare a data type with named fields"
    assertEquals(
      diags(tuples),
      List("(u64, u64, u64)", "(a, b, c)", "(1, 2, 3, 4)").map(at => (DiagCode.T031, tuples.indexOf(at), t031))
    )

  test("stdlib §4.9: i128 and u128 are reserved, as a type and as a declared name (T032)"):
    val code =
      "fn a(_x: i128) -> u64 = 0\nfn u128() -> u64 = 0\nfn c(i128: u64) -> u64 = i128\ndata D:\n    D(u128: u64)\n" +
        "fn d() -> u64 =\n    let u128 = 3\n    let f = (i128) -> i128 + 1\n    f(u128)\n" +
        "fn e(x: u64) -> u64 =\n    fn i128() -> u64 = 1\n    match x:\n        u128 => u128 + i128()\n" +
        "fn g(_y: is u128) -> u64 = 0\nD satisfies Eq:\n    fn eq(i128: D) = true\nu128 satisfies Show:\n    fn show = \"\"\n"

    /** A declared function is reported at its `fn`, a `satisfies` declaration at its type's name. */
    val at = List(
      "i128) -> u64" -> "i128",
      "fn u128" -> "u128",
      "i128: u64" -> "i128",
      "u128: u64" -> "u128",
      "u128 = 3" -> "u128",
      "i128) -> i128" -> "i128",
      "fn i128" -> "i128",
      "u128 =>" -> "u128",
      "u128) -> u64" -> "u128",
      "i128: D" -> "i128",
      "u128 satisfies" -> "u128"
    )
    assertEquals(
      diags(code).sortBy(_._2),
      at.map((s, n) => (DiagCode.T032, code.indexOf(s), s"$n is reserved; the widest integer type is i64")).sortBy(_._2)
    )
