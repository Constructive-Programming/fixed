package fixed.qtt

import java.nio.file.{Files, Path}

import fixed.ast.{Tree, Trees}
import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Quantity inference (`docs/plans/phase-3.4-qtt.md` 3.4.M1): one test per rule of
  * `spec/quantities.md` §5, and the worked examples of §10.
  */
class QuantityPhaseSuite extends munit.FunSuite:

  private def compile(code: String): CompilationUnit =
    Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", code)))

  /** Quantities of the parameters of every function named `name` (nested ones included). */
  private def params(code: String, name: String): List[String] =
    val unit = compile(code)
    assert(!unit.hasErrors, unit.diagnostics)
    val table = unit.quantities.get
    fns(unit.tree.get).filter(_.name == name).flatMap(f => table.params(f).toList.flatten.map(_.show))

  /** Quantity of the binder named `name`, wherever it is in the unit. */
  private def binder(code: String, name: String): String =
    val unit = compile(code)
    val table = unit.quantities.get
    trees(unit.tree.get).collectFirst {
      case b: Trees.BinderPat if b.name == name   => table.of(b)
      case b: Trees.LambdaParam if b.name == name => table.of(b)
    }.flatten.fold("?")(_.show)

  private def fns(t: Tree): List[Trees.FnDecl] = trees(t).collect { case f: Trees.FnDecl => f }

  private def trees(t: Tree): List[Tree] =
    t ::
      (t match
        case p: Product =>
          p.productIterator.toList.flatMap {
            case c: Tree                        => trees(c)
            case Some(c: Tree)                  => trees(c)
            case Some(cs: List[?])              => cs.flatMap { case c: Tree => trees(c); case _ => Nil }
            case s: Trees.Selection             => s.args.toList.flatten.flatMap(trees)
            case Trees.FnSig(_, _, ps, _, _, b) => ps.flatMap(trees) ++ b.toList.flatMap(trees)
            case cs: List[?] => cs.flatMap { case c: Tree => trees(c); case (_, c: Tree) => trees(c); case _ => Nil }
            case _           => Nil
          })

  private def codes(code: String): List[DiagCode] = compile(code).diagnostics.map(_.code)

  test("Rule Q5.2: a parameter's quantity is its use count; §10.1 fibIter's go is (ω, 1, ω)"):
    assertEquals(params("fn double(x: i64) -> i64 = x + x\n", "double"), List("ω"))
    assertEquals(params("fn identity(x: i64) -> i64 = x\n", "identity"), List("1"))
    val fib = "fn fibIter(n: N is Numeric) -> N =\n    fn go(i: N, a: N, b: N) -> N =\n" +
      "        if i == 0:\n            a\n        else:\n            go(i - 1, b, a + b)\n    go(n, 0, 1)\n"
    assertEquals(params(fib, "go"), List("ω", "1", "ω"))
    assertEquals(params(fib, "fibIter"), List("ω"))

  test("Rule Q5.1 and Q5.10: a let binder sums its uses in the rest of the block"):
    assertEquals(binder("fn f(x: i64) -> i64 =\n    let y = x + 1\n    y * 2\n", "y"), "1")
    assertEquals(binder("fn f(x: i64) -> i64 =\n    let y = x + 1\n    y * y\n", "y"), "ω")
    assertEquals(params("fn g(x: i64) -> i64 =\n    let a = x + 1\n    let b = x + 2\n    a + b\n", "g"), List("ω"))

  test("Rule Q5.9: exclusive branches join"):
    val code = "fn f(x: i64, c: bool) -> i64 =\n    if c:\n        x\n    else:\n        x + 1\n"
    assertEquals(params(code, "f"), List("1", "1"))
    assertEquals(params(code.replace("x + 1", "x + x"), "f"), List("ω", "1"))

  test("§9: a call scales each argument by the callee's parameter quantity"):
    val code = "fn pairDouble(x: i64) -> i64 = x + x\nfn caller(y: i64) -> i64 = pairDouble(y)\n"
    assertEquals(params(code, "caller"), List("ω"))
    assertEquals(params("fn k(x: i64, _y: i64) -> i64 = x\nfn c(z: i64) -> i64 = k(1, z)\n", "c"), List("0"))

  test("Rule Q5.7: a let-bound lambda's captures scale by the closure's own quantity"):
    val once = "fn f(y: i64) -> i64 =\n    let g = (x: i64) -> x + y\n    g(1)\n"
    assertEquals(params(once, "f"), List("1"))
    assertEquals(params(once.replace("g(1)", "g(1) + g(2)"), "f"), List("ω"))
    assertEquals(binder(once, "x"), "1")

  test("Rule Q5.8: match binders are counted within their case"):
    val code = "data P:\n    P(a: i64, b: i64)\nfn f(p: P) -> i64 =\n    match p:\n        P.P(a, b) => a + a\n"
    assertEquals(binder(code, "a"), "ω")
    assertEquals(params(code, "f"), List("1"))

  test("Rule Q5.1: an unused binder warns (T021) unless named with a leading underscore"):
    assertEquals(codes("fn f(x: i64) -> i64 = 1\n"), List(DiagCode.T021))
    assertEquals(codes("fn f(_x: i64) -> i64 = 1\n"), Nil)

  test("Rule Q5.11: two resumes on one path are E072; one per branch is fine"):
    val ask = "effect Ask:\n    fn ask -> u64\n"
    val twice = ask + "fn f() -> u64 =\n    handle Ask.ask + 1:\n        Ask.ask => resume(1) + resume(2)\n"
    assert(codes(twice).contains(DiagCode.E072))
    val branches = ask + "fn f(c: bool) -> u64 =\n    handle Ask.ask + 1:\n" +
      "        Ask.ask => if c: resume(1) else: resume(2)\n"
    assert(!codes(branches).contains(DiagCode.E072))

  test("every example gets quantities and no new errors"):
    Files.list(Path.of("examples")).toArray.map(_.toString).filter(_.endsWith(".fixed")).sorted.foreach { f =>
      val unit = Compiler.compile(CompilationUnit(SourceFile.fromString(f, Files.readString(Path.of(f)))))
      assert(!unit.diagnostics.exists(_.code == DiagCode.E072), s"$f: ${unit.diagnostics}")
    }
