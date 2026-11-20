package fixed.backend

import java.nio.file.{Files, Path}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler, Main}

/** Clones of the type axis chosen by score within one budget per function, shared with the
  * ownership axis (`spec/compilation_model.md` Rules CM5.3 to CM5.6, CM5.8). Every test reads the
  * Core of the default mode, which also holds every baseline version, so each fails on a backend
  * that clones every instantiation, or none.
  */
class SpecialiseSuite extends munit.FunSuite:

  private def unit(source: String): CompilationUnit =
    val u = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>.fixed", source)))
    assert(!u.hasErrors, u.diagnostics)
    u

  private def analyse(source: String, budget: Int = Versions.budget): Analysed =
    Backend.analyse(unit(source), budget).fold(ds => fail(s"lowering failed: ${ds.map(_.message)}"), identity)

  private def named(a: Analysed, f: String): Fn =
    a.fns.find(_.name == f).getOrElse(fail(s"no $f in ${a.fns.map(_.name)}"))

  /** The baseline size of `f`: the number of `let`s of its baseline version (Rule CM5.3). */
  private def size(source: String, f: String): Int = Core.lets(named(analyse(source), f).body).size

  /** Every call of user function `callee` in `f`, with its arguments. */
  private def calls(a: Analysed, f: String, callee: String): List[List[Atom]] =
    Core.lets(named(a, f).body).collect { case (_, Rhs.Call(`callee`, as, false)) => as }

  /** The closure codes `f` builds. */
  private def closures(a: Analysed, f: String): List[String] =
    Core.lets(named(a, f).body).collect { case (_, Rhs.Closure(c, _, _)) => c }

  private def outcome(a: Analysed, f: String): List[(String, String)] =
    a.candidates.getOrElse(f, Nil).filterNot(_.key.contains(":")).map(c => c.key -> c.reason)

  private val header = "use std.io.Console\n\nfn pick(xs: List of A, d: A) -> A = xs.fold(d, (acc, x) -> x)\n\n"

  /** `pick` at `String` from `main`, and at `i64`, whose key text sorts after, inside a fold step. */
  private val hot = header + "fn main() -> () with Console =\n" +
    "    Console.print_line(pick([\"a\".concat(\"b\")], \"c\"))\n" +
    "    Console.print_line([1, 2].fold(0, (s, i) -> pick([i], s)).show())\n"

  test("with room for one clone, the request inside a fold step is the clone and the other calls the baseline"):
    val a = analyse(hot, size(hot, "pick"))
    assertEquals(outcome(a, "pick"), List("String" -> "budget exhausted", "i64" -> ""))
    assertEquals(a.candidates("pick").map(c => c.key -> c.weight).toMap, Map("String" -> 1L, "i64" -> 8L))
    assertEquals(a.fns.map(_.name).filter(_.startsWith("pick")).sorted, List("pick", "pick[i64]"))
    val step = closures(a, "main").find(c => calls(a, c, "pick[i64]").nonEmpty)
    assert(step.isDefined, s"no step of main calls the clone: ${closures(a, "main")}")
    assertEquals(calls(a, "main", "pick").map(_.head), List(Atom.StaticWitness("ref")), "the baseline, with a witness")

  test("a function larger than B has no clone, reported as larger than B"):
    val a = analyse(hot, size(hot, "pick") - 1)
    assertEquals(outcome(a, "pick").map(_._2).distinct, List("larger than B"))
    assertEquals(a.fns.map(_.name).filter(_.startsWith("pick")), List("pick"))

  test("equal scores are built in key order"):
    val src = header + "fn main() -> () with Console =\n" +
      "    Console.print_line(pick([1, 2], 0).show())\n    Console.print_line(pick([\"a\"], \"b\"))\n"
    assertEquals(outcome(analyse(src, size(src, "pick")), "pick"), List("String" -> "", "i64" -> "budget exhausted"))

  test("a function whose baseline version has nothing for a key to resolve is not cloned"):
    val src = "use std.io.Console\n\nfn same(x: A) -> A = x\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(same(\"a\"))\n"
    val a = analyse(src)
    assertEquals(outcome(a, "same"), List("String" -> "removed = 0"))
    assertEquals(a.fns.map(_.name).filter(_.startsWith("same")), List("same"))

  /** `outer` calls `inner`, which is larger, at its own type parameter. */
  private val nested = "use std.io.Console\n\n" +
    "fn inner(xs: List of A, d: A) -> A = xs.fold(xs.fold(d, (a, x) -> x), (a, x) -> a)\n\n" +
    "fn outer(xs: List of A, d: A) -> A = inner(xs, d)\n\n" +
    "fn main() -> () with Console =\n    Console.print_line(outer([1, 2], 3).show())\n"

  test("a clone calls a clone of its callee if one was built, else the callee's baseline version"):
    assert(size(nested, "inner") > size(nested, "outer"))
    val roomy = analyse(nested)
    assertEquals(calls(roomy, "outer[i64]", "inner[i64]").size, 1, "requested from the clone, built in the next round")
    assertEquals(calls(roomy, "outer", "inner").size, 1, "the baseline passes its witness on")
    val tight = analyse(nested, size(nested, "outer"))
    assertEquals(outcome(tight, "inner"), List("i64" -> "larger than B"))
    assertEquals(calls(tight, "outer[i64]", "inner").map(_.head), List(Atom.StaticWitness("imm")))

  test("a fip body forces the clones it requests, whatever the budget, uncharged"):
    val src = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\n" +
      "fn left(x: A, y: A) -> A = x\n\nfip fn outer(xs: L) -> L = left(xs, L.E)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(match outer(L.C(1, L.E)):\n" +
      "        L.C(h, _) => h.show()\n        L.E => \"\")\n"
    val a = analyse(src, budget = 0)
    assertEquals(FipCheck(a), Nil)
    assertEquals(calls(a, "outer", "left[L]").size, 1)
    assertEquals(a.candidates("left").filter(_.key == "L").map(c => (c.reason, c.charged)), List(("", false)))

  test("the report has a score for a function with no let, whose only site is a witness drop"):
    val src = "use std.io.Console\n\nfn left(x: A, y: A) -> A = x\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(left(\"a\", \"b\"))\n"
    val report = MemoryReport.json(unit(src), analyse(src))
    assert(report.contains("\"key\":\"String\",\"removed\":1,\"weight\":1,\"score\":1"), report)

  test("polymorphic recursion below a fip fn forces one clone, not one per depth; the check reports the rest"):
    val src = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\ndata W of A:\n    W(a: A)\n\n" +
      "fn grow(x: A, n: L) -> L =\n    match n:\n        L.E => n\n        L.C(_, t) => grow(W.W(x), t)\n\n" +
      "fip fn outer(xs: L) -> L = grow(xs, xs)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(match outer(L.E):\n" +
      "        L.C(h, _) => h.show()\n        L.E => \"e\")\n"
    val a = analyse(src, budget = 0)
    assertEquals(a.fns.map(_.name).filter(_.startsWith("grow[")), List("grow[L]"))
    assert(FipCheck(a).exists(_.message.contains("`grow`")), FipCheck(a))

  /** For each program and budget: the clones of the type axis the report lists as built are those
    * in the Core, each called; and every function's clones in the Core, on both axes, are charged
    * no more than the budget, counted from the Core (none of these programs has a `fip fn`).
    */
  test("report and Core agree on the clones, and the clones in the Core fit B, on every example and benchmark"):
    val programs = List(
      "examples/01_basics",
      "examples/03_option_result",
      "examples/04_json",
      "examples/05_phantom_types"
    ) ++ List("bintrees", "nat_loop", "list_pipeline", "records", "fail_chain")
      .map("bench/runtime/" + _)
    for path <- programs; budget <- List(0, 8, Versions.budget) do
      val a = analyse(Files.readString(Path.of(s"$path.fixed")), budget)
      val byName = a.fns.map(f => f.name -> f).toMap
      val called = a.fns.flatMap(f => Core.callees(f.body)).toSet
      val listed = a.candidates.toList.flatMap((f, cs) =>
        cs.filter(c => c.reason.isEmpty && !c.key.contains(":")).map(c => f -> c.key)
      )
      val present = a.fns.flatMap(f => a.typeClones.get(f.name).map(f.name -> _))
      assert(present.forall((c, _) => called(c)), s"$path at $budget: a clone nobody calls: $present")
      assertEquals(listed.sorted, present.map(_._2).sorted, s"$path at $budget")
      val family = (f: String) => a.typeClones.get(f).fold(f)(_._1)
      val ownership = a.fns.filter(_.name.contains("#x[")).map(f => family(f.instance))
      (present.map(_._2._1) ++ ownership).groupMapReduce(identity)(_ => 1)(_ + _).foreach { (f, n) =>
        val charge = n * Core.lets(byName(f).body).size
        assert(charge <= budget, s"$path at $budget: $f has $n clones, $charge lets")
      }
      if budget == 0 then assertEquals(present, Nil, s"$path: a clone with no budget")

  /** `Mode` is a budget, so this holds by construction; it guards the parsing of `--budget`. */
  test("--budget 0 builds the same code as --no-specialise"):
    val src = Files.readString(Path.of("bench/runtime/records.fixed"))
    val (mode, rest) = Main.modeOf(List("ship", "--budget", "0", "f.fixed"))
    assertEquals(rest, List("ship", "f.fixed"))
    val ir = (m: Mode) => Backend.compile(unit(src), m).fold(ds => fail(s"$ds"), identity)
    assertEquals(ir(mode), ir(Main.modeOf(List("--no-specialise"))._1))
    assertNotEquals(ir(mode), ir(Mode.Default))
