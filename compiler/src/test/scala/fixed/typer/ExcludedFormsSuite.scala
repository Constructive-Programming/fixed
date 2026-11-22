package fixed.typer

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Forms the rules exclude, each with its own error: a tuple of more than two elements (`T031`), the
  * reserved names `i128` and `u128` (`T032`), an empty list literal of unknown element type (`T029`),
  * a type applied to a number of arguments it does not take (`T006`).
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

  test("Rule 5.12: doubled parentheses keep a tuple inside an `of` argument list or a parameter list"):
    val triples = "data Box of A: B(v: A)\nfn k(_x: Box of ((u64, u64, u64))) -> u64 = 0\n" +
      "fn m(_g: ((u64, u64, u64)) -> u64) -> u64 = 0\n"
    val t031 = "a tuple has two elements; declare a data type with named fields"
    assertEquals(
      diags(triples),
      List(triples.indexOf("(u64, u64, u64)"), triples.lastIndexOf("(u64, u64, u64)")).map((DiagCode.T031, _, t031))
    )
    val pairs = "data Box of A: B(v: A)\nfn f(g: ((u64, u64)) -> u64) -> u64 = g((1, 2))\n" +
      "fn h(b: Box of ((u64, u64))) -> (u64, u64) = match b: B(v) => v\n" +
      "fn d(x: ((u64, u64))) -> (u64, u64) = x\nfn u(g: (()) -> u64) -> u64 = g(())\n"
    assertEquals(diags(pairs), Nil)

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

  test("Rule 5.10: [] whose element type nothing fixes is T029; a parameter, field, result or element fixes it"):
    val fixedBy =
      "fn n(xs: List of u8) -> u64 = xs.length()\nfn b() -> u64 = n([])\nfn c() -> List of String = []\n" +
        "fn e() -> u64 =\n    let ys = []\n    n(ys)\nfn f(_x: A) -> List of A = []\nfn g() -> bool = [] == [1]\n" +
        "fn m(xs: List of List of u8) -> u64 = xs.length()\nfn i() -> u64 = m([[], [1]])\n" +
        "data Box(xs: List of u8)\nfn j() -> u64 =\n    let _b = Box.Box([])\n    0\n" +
        "fn l() -> List of ! = []\nfn o() -> u64 = l().length()\n"
    assertEquals(diags(fixedBy), Nil)

    val open = "fn a() -> u64 = [].length()\nfn h() -> u64 = [[]].length()\n"
    val t029 = "cannot infer the element type of []; ascribe a type"
    assertEquals(diags(open), List(open.indexOf("[]"), open.indexOf("[[]]") + 1).map((DiagCode.T029, _, t029)))
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", open)))
    assertEquals(unit.diagnostics.head.suggestion, Some("for example `[] is List of u8`"))

    /** A literal that already has an error gets no second one. */
    val mismatch = "fn k() -> u64 = []\n"
    assertEquals(diags(mismatch).map(_._1), List(DiagCode.T002))

  test(
    "T006: a data type or effect takes the arguments it declares; a capability at most those (Rules 5.3, 5.4.b, 7.3.a)"
  ):
    val decls = "effect Log of A:\n    fn log(a: A) -> ()\ndata V of (n: u64, A):\n    V(a: A)\n" +
      "data D of (A, lo: u64 = 0):\n    D(a: A)\ndata E of (lo: u64 = 0, A):\n    E(a: A)\n" +
      "cap Between(min: N is Ord, max: N) of N:\n    fn lo -> N\n"
    val wrong = List(
      "fn g(_t: Tuple of (u8, u8, u8)) -> u64 = 1\n" -> ("Tuple of (u8, u8, u8)", "Tuple takes 2 argument(s), given 3"),
      "fn g(_t: List of (u8, u8)) -> u64 = 1\n" -> ("List of (u8, u8)", "List takes 1 argument(s), given 2"),
      "fn g(_t: Maybe of List of (u8, u8)) -> u64 = 1\n" -> ("List of (u8, u8)", "List takes 1 argument(s), given 2"),
      "fn g(_t: List) -> u64 = 1\n" -> ("List)", "List takes 1 argument(s), given 0"),
      "fn g(_t: V of u8) -> u64 = 1\n" -> ("V of u8", "V takes 2 argument(s), given 1"),
      "fn g() -> u64 with Log of (u8, u8) = 1\n" -> ("Log of (u8, u8)", "Log takes 1 argument(s), given 2"),
      "fn g(_x: is Sized of (u8, u64, u8)) -> u64 = 1\n" ->
        ("Sized of (u8, u64, u8)", "Sized takes at most 2 argument(s), given 3"),
      "fn g(_x: is Show of (u8, u8)) -> u64 = 1\n" -> ("Show of (u8, u8)", "Show takes at most 1 argument(s), given 2"),
      "fn g(_x: is Between of (u8, u8)) -> u64 = 1\n" ->
        ("Between of (u8, u8)", "Between takes at most 1 argument(s), given 2"),
      "fn g(_x: D) -> u64 = 1\n" -> ("D)", "D takes 1 to 2 argument(s), given 0"),
      "fn g(_x: D of (u8, 1, 2)) -> u64 = 1\n" -> ("D of (u8, 1, 2)", "D takes 1 to 2 argument(s), given 3"),
      "fn g(_x: E of u8) -> u64 = 1\n" -> ("E of u8", "E takes 2 argument(s), given 1"),
      "fn g() -> u64 with Log = 1\n" -> ("Log =", "Log takes 1 argument(s), given 0"),
      "data Num of (A is Numeric):\n    Num(a: A)\nfn g(_x: Num of (String, u8)) -> u64 = 1\n" ->
        ("Num of (String, u8)", "Num takes 1 argument(s), given 2")
    )

    /** Only the `T006`: an effect declared and not performed is also `T017`, a warning. */
    wrong.foreach { case (code, (at, message)) =>
      assertEquals(
        diags(decls + code).filter(_._1 != DiagCode.T017),
        List((DiagCode.T006, (decls + code).indexOf(at), message)),
        code
      )
    }

    /** A trailing value parameter with a default may be left out; a capability takes fewer
      * (`Sized of u8`), one with no `of` its `Part`; an alias distributes, each capability taking
      * what it declares. A variable named only in a parameter's bound (`K`) is not a parameter.
      */
    val right = "fn a(_t: Tuple of (u8, u8), _v: V of (3, u8)) -> u64 = 1\nfn b(_x: is Sized of u8) -> u64 = 1\n" +
      "fn c(_x: is Folding of u64) -> u64 = 1\ntype SS = Sized + Show\nfn d(_x: is SS of (u8, u64, u8)) -> u64 = 1\n" +
      "fn e(_x: D of u8, _y: D of (u8, 1), _z: E of (1, u8), _b: is Between of u8) -> u64 = 1\n" +
      "data W of (A is Map of (u8, K)):\n    W(a: A)\nfn f(_w: W of u8, _p: (Maybe) of u8) -> u64 = 1\n"
    assertEquals(diags(decls + right), Nil)

  test("§5.6: a branch, element or other value of type ! decides no type"):
    /** A value of type `!` fits every type and fixes nothing: `x`, `z` and the elements of `ys` are
      * `String`s, which `need` does not take.
      */
    val never = "fn need(n: u64) -> u64 = n\nfn p(c: bool) -> u64 =\n    let x = if c: unreachable else: \"s\"\n" +
      "    let ys = [unreachable, \"t\"]\n    let z = match c:\n        true => unreachable\n        false => \"u\"\n" +
      "    need(x) + need(z) + ys.fold(0, (acc, y) -> acc + need(y))\n"
    val string = "expected u64, got String"
    assertEquals(diags(never), List("x) +", "z) +", "y))").map(at => (DiagCode.T002, never.indexOf(at), string)))

    /** A `!` that is not written as an argument of a type fixes no variable: `y` and `g`'s parameter stay free. */
    val free = "fn need(n: u64) -> u64 = n\nfn takes(_f: ! -> u64) -> u64 = 0\nfn p(c: bool) -> u64 =\n" +
      "    let f = (y) -> if c: need(unreachable + y) else: need(y)\n    let g = (y) -> y\n" +
      "    let _a = takes(g)\n    f(\"s\") + need(g(\"t\"))\n"
    assertEquals(diags(free).map(_._1), List(DiagCode.T002, DiagCode.T002))
