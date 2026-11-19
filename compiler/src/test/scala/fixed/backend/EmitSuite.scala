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
