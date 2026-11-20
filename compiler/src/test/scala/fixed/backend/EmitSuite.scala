package fixed.backend

import java.io.ByteArrayInputStream
import java.nio.file.Path

import scala.sys.process.{Process, ProcessLogger}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Small programs through the whole slice pipeline: check, lower, emit, `clang`, run. Each run
  * compares standard output and checks the runtime's counters: every allocation is freed
  * (slice §4.8). These tests need `clang` and fail, not skip, without it.
  */
class EmitSuite extends munit.FunSuite:

  private val dir = Path.of("target", "emit-tests")

  /** Exit code, standard output, standard error. */
  private def run(name: String, source: String, stdin: String = ""): (Int, String, String) =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString(s"$name.fixed", source)))
    assert(!unit.hasErrors, unit.diagnostics)
    val ir = Backend.compile(unit).fold(ds => fail(s"lowering failed: $ds"), identity)
    val exe = Backend.ship(ir, dir.resolve(name)).fold(d => fail(d.message), identity)
    val (out, err) = (new StringBuilder, new StringBuilder)
    val code =
      (Process(Seq(exe.toAbsolutePath.toString), None, "FIXED_RT_STATS" -> "1") #<
        ByteArrayInputStream(stdin.getBytes("UTF-8"))).!(
        ProcessLogger(l => { val _ = out.append(l).append('\n') }, l => { val _ = err.append(l).append('\n') })
      )
    (code, out.toString, err.toString)

  /** Run `source`, expect exit 0, `expected` on stdout and no leak. */
  private def check(name: String, source: String, expected: String, stdin: String = ""): Unit =
    val (code, out, err) = run(name, source, stdin)
    assertEquals(code, 0, err)
    assertEquals(out, expected)
    val counts = "\"(allocs|frees)\":(\\d+)".r.findAllMatchIn(err).map(m => m.group(1) -> m.group(2).toLong).toMap
    assertEquals(counts.get("allocs"), counts.get("frees"), s"leak: $err")

  private val header = "use std.io.Console\n\n"

  /** Examples with golden files (`examples/golden/<name>.in`, `.out`): the slice's required set. */
  private val goldens = List("01_basics", "03_option_result", "04_json", "05_phantom_types")

  goldens.foreach { name =>
    test(s"example $name matches its golden output"):
      val golden = (ext: String) => java.nio.file.Files.readString(Path.of("examples", "golden", s"$name.$ext"))
      check(name, java.nio.file.Files.readString(Path.of("examples", s"$name.fixed")), golden("out"), golden("in"))
  }

  test("hello world"):
    check(
      "hello",
      header + "fn main() -> () with Console =\n    Console.print_line(\"Hello, world!\")\n",
      "Hello, world!\n"
    )

  test("scalar arithmetic, comparisons and show"):
    val src = header +
      """|fn report(a: i64, b: u64) -> () with Console =
         |    Console.print_line((a * 6 - 50).show())
         |    Console.print_line((b / 2).show().concat(" ").concat((b % 2).show()))
         |    Console.print_line((2.5 * 4.0).show())
         |    Console.print_line((1.0 / 3.0).show())
         |    Console.print_line((a > 3 && b <= 3).show())
         |    Console.print_line((-a).show())
         |
         |fn main() -> () with Console =
         |    report(7, 3)
         |""".stripMargin
    check("arith", src, "-8\n1 1\n10.0\n0.3333333333333333\ntrue\n-7\n")

  test("calls, if, let, strings shared and dropped"):
    val src = header +
      """|fn twice(s: String) -> String =
         |    s.concat(s)
         |
         |fn ignore(s: String) -> u64 =
         |    1
         |
         |fn pick(c: bool, a: String, b: String) -> String =
         |    if c:
         |        a
         |    else:
         |        b.concat("!")
         |
         |fn main() -> () with Console =
         |    let greeting = "hi".concat(" there")
         |    Console.print_line(twice(greeting))
         |    Console.print_line(ignore(greeting).show())
         |    Console.print_line(pick(false, greeting, greeting))
         |    Console.print_line(pick(greeting == "hi there", "yes", "no"))
         |""".stripMargin
    check("strings", src, "hi therehi there\n1\nhi there!\nyes\n")

  test("read_line from standard input"):
    val src = header +
      """|fn greet(name: String) -> () with Console =
         |    Console.print_line("Hello, ".concat(name).concat("!"))
         |
         |fn main() -> () with Console =
         |    let name = Console.read_line()
         |    greet(name)
         |""".stripMargin
    check("stdin", src, "Hello, Ada!\n", stdin = "Ada\n")

  test("&& short-circuits; integer division by zero panics"):
    val src = header +
      """|fn div(a: i64, b: i64) -> i64 =
         |    a / b
         |
         |fn main() -> () with Console =
         |    Console.print_line((false && div(1, 0) == 0).show())
         |    Console.print_line(div(1, 0).show())
         |""".stripMargin
    val (code, out, err) = run("divzero", src)
    assertEquals(out, "false\n")
    assertEquals(code, 101)
    assert(err.contains("panic: integer division by zero"), err)

  test("data, match, tuples, closures with captures, generic instances"):
    val src = header +
      """|data Shape:
         |    Circle(r: f64)
         |    Rect(w: f64, h: f64)
         |    Named(label: String, inner: Shape)
         |
         |fn area(s: Shape) -> f64 =
         |    s.fold((r) -> 3.0 * r * r, (w, h) -> w * h, (_, a) -> a)
         |
         |fn describe(s: Shape) -> String =
         |    match s:
         |        Shape.Named(label, _) => label
         |        _ => "anonymous"
         |
         |fn swap(p: (A, B)) -> (B, A) =
         |    let (a, b) = p
         |    (b, a)
         |
         |fn upto(n: u64) -> List of u64 =
         |    List.unfold(0, (i) -> if i >= n: List.Nil else: List.Cons(i, i + 1))
         |
         |fn main() -> () with Console =
         |    let s = Shape.Named("box", Shape.Rect(2.0, 3.5))
         |    Console.print_line(describe(s).concat(" ").concat(area(s).show()))
         |    let suffix = "!"
         |    upto(3).fold((), (_, i) -> Console.print_line(i.show().concat(suffix)))
         |    let p = swap(("x", 7))
         |    Console.print_line(p.second.concat(p.first.show()))
         |""".stripMargin
    check("data", src, "box 7.0\n0!\n1!\n2!\nx7\n")

  test("abortive effect: the handler catches an abort raised inside a fold callback, and drops run"):
    val src = header +
      """|fn check(n: u64) -> u64 with Fail of String =
         |    if n > 2:
         |        Fail.fail("too big: ".concat(n.show()))
         |    else:
         |        n
         |
         |fn total(xs: List of u64) -> u64 with Fail of String =
         |    xs.fold(0, (acc, x) -> acc + check(x))
         |
         |fn run(xs: List of u64) -> String =
         |    handle total(xs):
         |        Fail.fail(e) => e
         |        return(v) => v.show()
         |
         |fn main() -> () with Console =
         |    Console.print_line(run([1, 2]))
         |    Console.print_line(run([1, 5, 2]))
         |""".stripMargin
    check("abort", src, "3\ntoo big: 5\n")

  test("arguments are evaluated left to right, before the call (spec/quantities.md Rule Q9.2)"):
    val src = header +
      "fn a() -> u64 with Console =\n    Console.print_line(\"a\")\n    1\n" +
      "fn b() -> u64 with Console =\n    Console.print_line(\"b\")\n    2\n" +
      "fn add(x: u64, y: u64) -> u64 with Console =\n    Console.print_line(\"call\")\n    x + y\n" +
      "fn main() -> () with Console =\n    Console.print_line(add(a(), b()).show())\n"
    check("order", src, "a\nb\ncall\n3\n")

  test("a shared list passed to a reusing function is left intact (shared version, 3.4.M3)"):
    val src = header + "data L:\n    E\n    C(h: u64, t: L)\n\n" +
      "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
      "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n" +
      "fn main() -> () with Console =\n    let a = L.C(1, L.C(2, L.E))\n    let b = bump(a)\n" +
      "    Console.print_line(first(a).show())\n    Console.print_line(first(b).show())\n"
    check("shared_bump", src, "1\n2\n")

  test("beyond the version cap the caller copies, and the copy is freed (3.4.M3)"):
    val src = header + "data L:\n    E\n    C(h: u64, t: L)\n\n" +
      "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
      "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n" +
      "fn three(a: L, b: L, c: L) -> u64 = first(bump(a)) + first(bump(b)) + first(bump(c))\n\n" +
      "fn drive(a: L, b: L, c: L) -> u64 =\n" +
      "    three(a, b, c) + three(a, b, L.E) + three(a, L.E, c) + three(L.E, b, c) + three(L.E, L.E, L.E)\n" +
      "        + first(a) + first(b) + first(c)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(drive(L.C(1, L.E), L.C(2, L.E), L.C(3, L.E)).show())\n"
    check("cap_copy", src, "33\n")
