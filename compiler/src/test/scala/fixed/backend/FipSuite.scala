package fixed.backend

import java.nio.file.{Files, Path}

import fixed.ast.Trees
import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** The `fip` contract and the memory report (`docs/plans/phase-3.4-qtt.md` 3.4.M5,
  * `spec/memory_management.md` §18–§19).
  */
class FipSuite extends munit.FunSuite:

  private val list = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\ndata P:\n    P(a: u64, b: u64)\n\n" +
    "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n"

  private val bump = "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n"

  private def unit(src: String): CompilationUnit =
    val u = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", src)))
    assert(!u.hasErrors, u.diagnostics)
    u

  /** The `fip` violations of `code` whose `main` prints `body`, as their rule items. */
  private def violations(code: String, body: String): List[String] =
    val u = unit(code + s"fn main() -> () with Console =\n    Console.print_line(($body).show())\n")
    Backend.compile(u) match
      case Right(_) => Nil
      case Left(ds) =>
        assert(ds.forall(_.code == DiagCode.E110), ds)
        ds.map(d => "Rule MM18\\.[0-9]\\([0-9]\\)".r.findFirstIn(d.message).getOrElse(d.message))

  test("fip and fip(strict) parse as contracts on fn declarations"):
    val u =
      unit(list + "fip fn a(x: u64) -> u64 = x\n\nfip(strict) fn b(x: u64) -> u64 = x\n\nfn c(x: u64) -> u64 = x\n")
    val fips = Trees.subtrees(u.tree.get).collect { case f: Trees.FnDecl => f.name -> f.fip }.toMap
    assertEquals(fips("a"), Some(Trees.Fip.Plain))
    assertEquals(fips("b"), Some(Trees.Fip.Strict))
    assertEquals(fips("c"), None)

  test("a function that reuses its exclusive input meets fip and fip(strict)"):
    assertEquals(violations(list + "fip " + bump, "first(bump(L.C(1, L.E)))"), Nil)
    assertEquals(violations(list + "fip(strict) " + bump, "first(bump(L.C(1, L.E)))"), Nil)

  test("an allocation kept by an unknown closure breaks fip (heap, RC)"):
    val code = list + "fip fn bad(f: (P) -> u64, x: u64) -> u64 = f(P.P(x, x))\n\n"
    assertEquals(violations(code, "bad((p) -> p.a, 1)").toSet, Set("Rule MM18.1(1)", "Rule MM18.1(2)"))

  test("fip(strict) rejects an allocation that reuses nothing"):
    val code = list + "fip(strict) fn mk(x: u64) -> P = P.P(x, x)\n\nfn get(p: P) -> u64 = p.a\n\n"
    assertEquals(violations(code, "get(mk(1))"), List("Rule MM18.2(1)"))

  test("a caller holding a shared value copies into a fip function; a fip caller may not"):
    val twice = "fn twice(xs: L) -> u64 = first(bump(xs)) + first(xs)\n\n"
    val fns = Backend.core(unit(list + "fip " + bump + twice + "fn main() -> () with Console =\n" +
      "    Console.print_line(twice(L.C(1, L.E)).show())\n")).fold(ds => fail(s"$ds"), identity)
    assert(fns.find(_.name == "twice").exists(f => Core.callees(f.body).exists(_.contains(".copy["))))
    assert(!fns.exists(_.name.startsWith("bump#")), fns.map(_.name))
    assertEquals(violations(list + "fip " + bump + "fip " + twice, "twice(L.C(1, L.E))"), List("Rule MM18.1(3)"))

  test("example 01's memory report matches its golden file (regenerate with FIXED_REGEN=1)"):
    val path = Path.of("examples", "golden", "01_basics.memory.json")
    val u = unit(Files.readString(Path.of("examples", "01_basics.fixed")))
    val report = MemoryReport.json(u, Backend.analyse(u).fold(ds => fail(s"$ds"), identity)) + "\n"
    if sys.env.contains("FIXED_REGEN") then
      val _ = Files.writeString(path, report)
    assertEquals(report, Files.readString(path))
