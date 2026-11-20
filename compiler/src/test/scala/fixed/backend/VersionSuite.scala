package fixed.backend

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Shared versions, copies beyond the cap and closure entries (`docs/plans/phase-3.4-qtt.md`
  * 3.4.M3, `spec/quantities.md` Rule Q9.3, `spec/memory_management.md` Rule PR5.6).
  */
class VersionSuite extends munit.FunSuite:

  private val list = "use std.io.Console\n\ndata L:\n    E\n    C(h: u64, t: L)\n\n" +
    "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
    "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n"

  /** The versioned Core of `code` whose `main` prints `body`. */
  private def core(code: String, body: String): List[Fn] =
    val src = code + s"fn main() -> () with Console =\n    Console.print_line(($body).show())\n"
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", src)))
    assert(!unit.hasErrors, unit.diagnostics)
    Backend.core(unit).fold(ds => fail(s"lowering failed: $ds"), identity)

  /** The user functions `fn` calls. */
  private def calls(fns: List[Fn], fn: String): Set[String] =
    fns.find(_.name == fn).fold(fail(s"no $fn in ${fns.map(_.name)}"))(f => Core.callees(f.body))

  test("a last use of a fresh value calls the exclusive version; a later use calls the shared one"):
    val code = list +
      "fn pass(xs: L) -> L = bump(xs)\n\nfn later(xs: L) -> u64 = first(bump(xs)) + first(xs)\n\n"
    val fns = core(code, "first(pass(L.C(1, L.E))) + later(L.C(2, L.E))")
    assert(calls(fns, "pass").contains("bump"), calls(fns, "pass"))
    assert(calls(fns, "later").exists(_.startsWith("bump#")), calls(fns, "later"))
    assertEquals(fns.map(_.name).count(_.startsWith("bump")), 2)

  test("a parameter received shared is passed on shared"):
    val code = list + "fn pass(xs: L) -> L = bump(xs)\n\nfn twice(xs: L) -> u64 = first(pass(xs)) + first(xs)\n\n"
    val fns = core(code, "twice(L.C(1, L.E))")
    val shared = calls(fns, "twice").find(_.startsWith("pass#")).getOrElse(fail(s"${calls(fns, "twice")}"))
    assert(calls(fns, shared).exists(_.startsWith("bump#")), calls(fns, shared))

  test("beyond four versions of a function the caller copies the demanded part"):
    val code = list +
      "fn three(a: L, b: L, c: L) -> u64 = first(bump(a)) + first(bump(b)) + first(bump(c))\n\n" +
      "fn drive(a: L, b: L, c: L) -> u64 =\n" +
      "    three(a, b, c) + three(a, b, L.E) + three(a, L.E, c) + three(L.E, b, c) + three(L.E, L.E, L.E)\n" +
      "        + first(a) + first(b) + first(c)\n\n"
    val fns = core(code, "drive(L.C(1, L.E), L.C(2, L.E), L.C(3, L.E))")
    assert(fns.map(_.name).count(_.startsWith("three")) <= 4, fns.map(_.name))
    assert(calls(fns, "drive").exists(_.contains(".copy[")), calls(fns, "drive"))

  test("every closure has an exclusive and a shared entry"):
    val code = "use std.io.Console\n\nfn apply(f: u64 -> u64, x: u64) -> u64 = f(x)\n\n"
    val fns = core(code, "apply((x) -> x + 1, 2)")
    val closures = fns.flatMap(f => closuresIn(f.body))
    assert(closures.nonEmpty)
    assert(closures.forall(c => c.shared.nonEmpty && fns.exists(_.name == c.shared)), closures)

  private def closuresIn(e: Expr): List[Rhs.Closure] =
    e match
      case Expr.Let(_, c: Rhs.Closure, b)    => c :: closuresIn(b)
      case Expr.Let(_, Rhs.If(_, t, f), b)   => closuresIn(t) ++ closuresIn(f) ++ closuresIn(b)
      case Expr.Let(_, Rhs.Switch(_, cs), b) => cs.flatMap(c => closuresIn(c._2)) ++ closuresIn(b)
      case Expr.Let(_, _, b)                 => closuresIn(b)
      case _                                 => Nil
