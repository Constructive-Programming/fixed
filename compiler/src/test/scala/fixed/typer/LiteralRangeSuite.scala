package fixed.typer

import fixed.parsing.Parser
import fixed.util.{DiagCode, SourceFile}

/** An integer literal outside the range of the type it takes is `T023` at the literal; a literal in a
  * variant pattern makes the case not cover its variant (`T020`).
  */
class LiteralRangeSuite extends munit.FunSuite:

  private def diags(code: String) =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    TyperPhase.typeBodies(unit.tree, table).diags.map(d => (d.code, d.span.start))

  private val decls = "fn byte(x: u8) -> u8 = x\nfn small(x: i8) -> i8 = x\n"

  test("each integer type's bounds, the most negative signed value, and types solved late"):
    val bad = List(
      "fn a() -> u8 = byte(256)\n" -> "256",
      "fn a() -> i8 = small(128)\n" -> "128",
      "fn a() -> i8 = small(-129)\n" -> "-129",
      "fn a() -> u8 = byte(-1)\n" -> "-1",
      "fn a() -> u64 = 18446744073709551616\n" -> "18446744073709551616",
      "fn a() -> i64 = 9223372036854775808\n" -> "9223372036854775808",
      "fn a() -> i64 = -9223372036854775809\n" -> "-9223372036854775809",
      "fn a() -> u8 =\n    let y = 300\n    byte(y)\n" -> "300",
      "fn a(x: N is Numeric) -> N = x + 9223372036854775808\n" -> "9223372036854775808",
      "fn a(x: u8) -> u8 = match x:\n    300 => 1\n    _ => 2\n" -> "300",
      /** The sign is part of the literal: `-1` is checked at `u8`, whatever is applied to it. */
      "fn a() -> u8 = byte(-(-1))\n" -> "-1)"
    )
    bad.foreach((code, at) =>
      assertEquals(diags(decls + code), List((DiagCode.T023, (decls + code).indexOf(at))), code)
    )
    val good = List(
      "fn a() -> u8 = byte(255)\n",
      "fn a() -> i8 = small(-128)\n",
      "fn a() -> u64 = 18446744073709551615\n",
      "fn a() -> i64 = -9223372036854775808\n",
      "fn a() -> f32 = 100000000000\n",
      "fn a() -> u8 =\n    let y = 200\n    byte(y)\n"
    )
    good.foreach(code => assertEquals(diags(decls + code), Nil, code))

  test("a literal that is already a type mismatch is not also out of range"):
    val code = "fn a() -> String = 99999999999999999999\n"
    assertEquals(diags(code), List((DiagCode.T002, code.indexOf("9"))))

  test("a variant pattern holding a literal does not cover its variant: T020 at the match"):
    val src = "fn g(m: Maybe of u64) -> u64 =\n    match m:\n        Maybe.Just(0) => 0\n        Maybe.Nothing => 1\n"
    assertEquals(diags(src), List((DiagCode.T020, src.indexOf("match"))))
