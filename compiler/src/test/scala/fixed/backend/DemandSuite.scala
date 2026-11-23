package fixed.backend

import fixed.core.render
import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Demand inference over Core (`docs/plans/phase-3.4-qtt.md` 3.4.M2, `spec/quantities.md`
  * Rule Q4.1): per parameter, per part, with exclusivity lost to later uses, keeps and aliasing.
  */
class DemandSuite extends munit.FunSuite:

  private val list = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\n" +
    "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
    "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n"

  /** The demands of every function instance of `code` whose `main` prints `body`. */
  private def demands(code: String, body: String): DemandTable =
    val src = code + s"fn main() -> () with Console =\n    Console.printLine(($body).show())\n"
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", src)))
    assert(!unit.hasErrors, unit.diagnostics)
    val lower = Lower(unit)
    val fns = lower.program.fold(ds => fail(s"lowering failed: $ds"), identity)
    Demands.analyse(lower, fns, unit.symbols.render(_))._2

  private def roots(t: DemandTable, fn: String): List[String] =
    t.params.find(_._1.startsWith(fn)).fold(fail(s"no $fn in ${t.params.keys}"))(_._2.map(_.root.show))

  test("a rebuilt cell is exclusive, the tail it keeps is own, a field read is read"):
    val t = demands(list, "first(bump(L.C(1, L.E)))")
    assertEquals(roots(t, "bump"), List("exclusive"))
    assertEquals(t.params("bump").head.parts, Map(("L", 0) -> Demand.Read, ("L", 1) -> Demand.Own))
    assertEquals(roots(t, "first"), List("read"))
    assert(t.reuses("bump").values.toList.forall(_.startsWith("xs.")), t.reuses("bump"))
    assertEquals(t.reuses("bump").size, 1)

  test("a value only stored is own; an unused parameter is 0; a used scalar is not 0"):
    val code = list + "fn push(xs: L, x: u64, _y: u64) -> L = L.C(x, xs)\n\n"
    val t = demands(code, "first(push(L.E, 1, 2))")
    assertEquals(roots(t, "push"), List("own", "own", "0"))

  test("exclusive passes through a last use, not through a later use, a keep or an alias"):
    val code = list +
      "fn pass(xs: L) -> L = bump(xs)\n\n" +
      "fn later(xs: L) -> u64 = first(bump(xs)) + first(xs)\n\n" +
      "fn two(a: L, _b: L) -> L = bump(a)\n\n" +
      "fn alias(xs: L) -> L = two(xs, xs)\n\n" +
      "fn apart(xs: L) -> L = two(xs, L.E)\n\n" +
      "fn keep(xs: L) -> L =\n    let c = L.C(1, xs)\n    two(bump(xs), c)\n\n"
    val t = demands(
      code,
      "first(pass(L.E)) + later(L.E) + first(alias(L.E)) + first(apart(L.E)) + first(keep(L.E))"
    )
    assertEquals(roots(t, "pass"), List("exclusive"))
    assertEquals(roots(t, "later"), List("own"))
    assertEquals(roots(t, "alias"), List("own"))
    assertEquals(roots(t, "apart"), List("exclusive"))
    assertEquals(roots(t, "keep"), List("own"))

  test("derived map: exclusive on the cell and the recursive slot, own on the element"):
    val code = "use std.io.Console\n\nfn upto(n: u64) -> List of u64 =\n" +
      "    List.unfold(0, (i) ->\n        if i >= n:\n            List.Nil\n        else:\n" +
      "            List.Cons(i, i + 1)\n    )\n\n"
    val src = code +
      "fn sum(xs: List of u64) -> u64 = xs.fold(0, (a, x) -> a + x)\n\n" +
      "fn main() -> () with Console =\n    Console.printLine(sum(upto(3).map((x) -> x + 1)).show())\n"
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", src)))

    /** The mapped list goes to `sum`, not to a `fold`, so it is built ([[Fusion]]). */
    val t = Backend.analyse(unit).fold(ds => fail(s"lowering failed: $ds"), _.demands)
    val map = t.params.get("List of u64.map[u64]").map(_.head).getOrElse(fail(s"${t.params.keys}"))
    assertEquals(map.root, Demand.Excl)
    assertEquals(map.parts, Map(("List of u64", 0) -> Demand.Own, ("List of u64", 1) -> Demand.Excl))

  test("a single-variant value rebuilt after its fields are read is reused without a match"):
    val code = "use std.io.Console\n\ndata P:\n    P(a: u64, b: String)\n\n" +
      "fn swap(p: P) -> P = P.P(p.a + 1, p.b)\n\nfn get(p: P) -> u64 = p.a\n\n"
    val t = demands(code, "get(swap(P.P(1, \"x\")))")
    assertEquals(roots(t, "swap"), List("exclusive"))
    assertEquals(roots(t, "get"), List("read"))

  test("every function of the run examples and the benchmarks gets demands"):
    val files = List("01_basics", "03_option_result", "04_json", "05_phantom_types").map(n => s"examples/$n.fixed") ++
      List("bintrees", "nat_loop", "list_pipeline", "records", "fail_chain").map(n => s"bench/runtime/$n.fixed")
    files.foreach { f =>
      val src = java.nio.file.Files.readString(java.nio.file.Path.of(f))
      val unit = Compiler.compile(CompilationUnit(SourceFile.fromString(f, src)))
      val lower = Lower(unit)
      val fns = lower.program.fold(ds => fail(s"$f: $ds"), identity)
      val t = Demands.analyse(lower, fns, unit.symbols.render(_))._2
      assertEquals(t.params.keySet, fns.map(_.name).toSet, f)
    }
