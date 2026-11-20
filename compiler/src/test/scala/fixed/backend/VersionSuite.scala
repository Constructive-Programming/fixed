package fixed.backend

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Shared versions, exclusive clones under the clone budget, and closure entries
  * (`spec/quantities.md` Rule Q9.3, `spec/compilation_model.md` Rules CM5.3–CM5.6,
  * `spec/memory_management.md` Rule PR5.6).
  */
class VersionSuite extends munit.FunSuite:

  private val list = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\n" +
    "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
    "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n"

  /** The analysed Core of `code` whose `main` prints `body`, with clone budget `budget`. */
  private def analyse(code: String, body: String, budget: Int = Versions.budget, ownership: Boolean = true): Analysed =
    val src = code + s"fn main() -> () with Console =\n    Console.printLine(($body).show())\n"
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", src)))
    assert(!unit.hasErrors, unit.diagnostics)
    Backend.analyse(unit, budget, ownership).fold(ds => fail(s"lowering failed: $ds"), identity)

  private def core(code: String, body: String): List[Fn] = analyse(code, body).fns

  /** The user functions `fn` calls. */
  private def calls(fns: List[Fn], fn: String): Set[String] =
    fns.find(_.name == fn).fold(fail(s"no $fn in ${fns.map(_.name)}"))(f => Core.callees(f.body))

  private def clone(fns: List[Fn], f: String): String =
    fns.map(_.name).find(_.startsWith(s"$f#x[")).getOrElse(fail(s"no clone of $f in ${fns.map(_.name)}"))

  test("the shared version keeps the name; a last use of a fresh value calls an exclusive clone"):
    val code = list +
      "fn pass(xs: L) -> L = bump(xs)\n\nfn later(xs: L) -> u64 = first(bump(xs)) + first(xs)\n\n"
    val fns = core(code, "first(pass(L.C(1, L.E))) + later(L.C(2, L.E))")
    assert(calls(fns, "main").contains(clone(fns, "pass")), calls(fns, "main"))
    assert(calls(fns, clone(fns, "pass")).contains(clone(fns, "bump")), calls(fns, clone(fns, "pass")))
    assert(calls(fns, "later").contains("bump"), calls(fns, "later"))
    assertEquals(fns.map(_.name).filter(_.startsWith("bump")).sorted, List("bump", clone(fns, "bump")))
    assert(fns.find(_.name == "bump").exists(f => f.version.exists(_.conditional)))

  test("a parameter received shared is passed on shared"):
    val code = list + "fn pass(xs: L) -> L = bump(xs)\n\nfn twice(xs: L) -> u64 = first(pass(xs)) + first(xs)\n\n"
    val fns = core(code, "twice(L.C(1, L.E))")
    assert(calls(fns, "twice").contains("pass"), calls(fns, "twice"))
    assert(calls(fns, "pass").contains("bump"), calls(fns, "pass"))

  private val three = list +
    "fn three(a: L, b: L, c: L) -> u64 = first(bump(a)) + first(bump(b)) + first(bump(c))\n\n" +
    "fn drive(a: L, b: L, c: L) -> u64 =\n" +
    "    three(a, b, c) + three(a, b, L.E) + three(a, L.E, c) + three(L.E, b, c) + three(L.E, L.E, L.E)\n" +
    "        + first(a) + first(b) + first(c)\n\n"
  private val drive = "drive(L.C(1, L.E), L.C(2, L.E), L.C(3, L.E))"

  test("past the budget a caller calls the shared version and copies nothing; charges stay within B"):
    val size = Core.lets(core(three, drive).find(_.name == "three").get.body).size
    val a = analyse(three, drive, budget = 2 * size)

    /** Rule CM5.4: `0:cell;1:cell;2:cell` removes three tests, each single key one; ties by key text. */
    val outcome = a.candidates("three").map(c => c.key -> c.reason)
    assertEquals(
      outcome,
      List(
        "0:cell" -> "",
        "0:cell;1:cell;2:cell" -> "",
        "1:cell" -> "budget exhausted",
        "2:cell" -> "budget exhausted"
      )
    )
    assertEquals(a.fns.map(_.name).count(_.startsWith("three#x[")), 2)
    assert(calls(a.fns, "drive").contains("three"), calls(a.fns, "drive"))
    assert(!a.fns.exists(f => Core.callees(f.body).exists(_.contains(".copy["))), a.fns.map(_.name))
    a.candidates.foreach { (f, cs) =>
      val charge = Core.lets(a.fns.find(_.name == f).get.body).size
      assert(cs.count(_.reason.isEmpty) * charge <= 2 * size, s"$f: $cs")
    }

  test("a request inside a fold step weighs 8 times one outside it"):
    val code = list + "fn two(a: L, b: L) -> u64 = first(bump(a)) + first(bump(b))\n\n" +
      "fn go(x: L) -> u64 = two(L.C(1, L.E), x) + [1, 2].fold(0, (s, i) -> s + two(x, L.C(i, L.E))) + first(x)\n\n"
    val body = "go(L.C(5, L.E))"
    val size = Core.lets(core(code, body).find(_.name == "two").get.body).size
    val a = analyse(code, body, budget = size)
    assertEquals(
      a.candidates("two").map(c => (c.key, c.weight, c.reason)),
      List(
        ("0:cell", 1L, "budget exhausted"),
        ("1:cell", 8L, "")
      )
    )

  test("a function larger than B gets no clone"):
    val small = analyse(three, drive, budget = 1)
    assert(small.candidates("three").forall(_.reason == "larger than B"), small.candidates("three"))
    assert(!small.fns.exists(_.name.contains("#x[")), small.fns.map(_.name))

  test("a candidate that removes nothing is not built"):
    /** The clone of `tail` for an exclusive tail part alone cannot use it: the cell above is shared. */
    val code = list + "fn tail(xs: L) -> L =\n    match xs:\n        L.C(_, t) => bump(t)\n        L.E => L.E\n\n"
    val a = analyse(code, "first(tail(L.C(1, L.C(2, L.E))))")
    assertEquals(a.candidates("tail").map(_.reason), List("removed = 0"))
    assert(!a.fns.exists(_.name.startsWith("tail#")), a.fns.map(_.name))

  /** `grab`'s result is first taken as exclusive (no summary yet), so `user` asks for `bump`'s clone;
    * once `grab` is built its result is a part of the shared `xs`, and no call reaches the clone.
    */
  test("a clone chosen early that no call reaches in the end is dropped and holds no budget"):
    val code = list + "fn grab(xs: L, n: u64) -> L =\n    if n == 0:\n        match xs:\n" +
      "            L.C(_, t) => t\n            L.E => L.E\n    else:\n        grab(xs, n - 1)\n\n" +
      "fn user(n: u64) -> u64 =\n    let xs = L.C(1, L.C(2, L.E))\n    first(bump(grab(xs, n))) + first(xs)\n\n"
    val a = analyse(code, "user(1)")
    assertEquals(a.candidates("bump").map(c => c.key -> c.reason), List("0:cell" -> "unused"))
    assert(!a.fns.exists(_.name.startsWith("bump#")), a.fns.map(_.name))

  /** Each `Cons(order, tail)` that `unfold` builds keeps its tail part exclusive though `order`, a
    * closure's result, is shared (`Versions.construct`), so `map`'s clone for it is reached.
    */
  test("records: the clone of map for an exclusive tail part is chosen and reached"):
    val src = java.nio.file.Files.readString(java.nio.file.Path.of("bench", "runtime", "records.fixed"))
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("records.fixed", src)))
    val map = "List of A.map[R]"
    val size = Core.lets(Backend.core(unit).fold(ds => fail(s"$ds"), identity).find(_.name == map).get.body).size

    /** Room for the clone of the type axis at `Order` and one clone of the ownership axis. */
    val a = Backend.analyse(unit, 2 * size).fold(ds => fail(s"$ds"), identity)
    assertEquals(
      a.candidates(map).map(c => c.key -> c.reason),
      List("Order,Order" -> "", "Order,Order 0:List of Order#1,cell" -> "")
    )
    val names = a.fns.map(_.name).toSet
    assert(a.fns.forall(f => Core.callees(f.body).forall(g => names(g) || !g.contains("#"))), names)

  test("with ownership clones off, a fip body still forces its clones; other candidates say why not"):
    val code = list + "fn pass(xs: L) -> L = bump(xs)\n\nfip fn outer(xs: L) -> L = pass(xs)\n\n" +
      "fn inc(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 2, t)\n        L.E => L.E\n\n"
    val a = analyse(code, "first(outer(L.C(1, L.E))) + first(inc(L.C(2, L.E)))", ownership = false)
    assertEquals(FipCheck(a), Nil)
    assert(calls(a.fns, "outer").contains(clone(a.fns, "pass")), calls(a.fns, "outer"))
    assert(calls(a.fns, "main").contains("inc"), calls(a.fns, "main"))
    assertEquals(a.candidates("inc").map(_.reason), List("no ownership clones"))

  test("a fip body forces the clones it requests, uncharged, so it passes its check with no budget"):
    val code = list + "fn pass(xs: L) -> L = bump(xs)\n\nfip fn outer(xs: L) -> L = pass(xs)\n\n"
    val a = analyse(code, "first(outer(L.C(1, L.E)))", budget = 0)
    assertEquals(FipCheck(a), Nil)
    assert(calls(a.fns, "outer").contains(clone(a.fns, "pass")), calls(a.fns, "outer"))
    assertEquals(a.candidates("pass").map(c => (c.reason, c.charged)), List(("", false)))

  test("every closure's primary entry is its shared code; an exclusive entry is a built clone"):
    val code = "use std.io.Console\n\nfn apply(f: u64 -> u64, x: u64) -> u64 = f(x)\n\n"
    val fns = core(code, "apply((x) -> x + 1, 2)")
    val closures = fns.flatMap(f => closuresIn(f.body))
    assert(closures.nonEmpty)
    assert(closures.forall(c => fns.exists(_.name == c.code) && c.exclusive.isEmpty), closures)
    val excl = core(list, "[L.C(1, L.E)].map((xs) -> bump(xs)).fold(0, (a, x) -> a + first(x))")
    val built = excl.flatMap(f => closuresIn(f.body)).map(_.exclusive).filter(_.nonEmpty)
    assert(built.nonEmpty && built.forall(e => excl.exists(_.name == e)), excl.map(_.name))

  private def closuresIn(e: Expr): List[Rhs.Closure] =
    e match
      case Expr.Let(_, c: Rhs.Closure, b)    => c :: closuresIn(b)
      case Expr.Let(_, Rhs.If(_, t, f), b)   => closuresIn(t) ++ closuresIn(f) ++ closuresIn(b)
      case Expr.Let(_, Rhs.Switch(_, cs), b) => cs.flatMap(c => closuresIn(c._2)) ++ closuresIn(b)
      case Expr.Let(_, _, b)                 => closuresIn(b)
      case _                                 => Nil
