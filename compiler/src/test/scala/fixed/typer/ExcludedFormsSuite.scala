package fixed.typer

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Forms the rules exclude, each with its own error: a tuple of more than two elements (`T031`). */
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
