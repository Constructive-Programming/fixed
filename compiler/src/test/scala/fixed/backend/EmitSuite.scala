package fixed.backend

import java.io.ByteArrayInputStream
import java.nio.file.Path

import scala.sys.process.{Process, ProcessLogger}

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Programs (examples, benchmarks and small inline ones) through the whole slice pipeline: check, lower, emit, `clang`, run. Each run
  * compares standard output and checks the runtime's counters: every allocation is freed
  * (slice §4.8). These tests need `clang` and fail, not skip, without it.
  */
class EmitSuite extends munit.FunSuite:

  private val dir = Path.of("target", "emit-tests")

  /** The LLVM IR of `source` in build mode `mode`, or the backend's diagnostics. */
  private def lowered(name: String, source: String, mode: Mode = Mode.Default) =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString(s"$name.fixed", source)))
    assert(!unit.hasErrors, unit.diagnostics)
    Backend.compile(unit, mode)

  /** LLVM IR for `source` in build mode `mode`. */
  private def ir(name: String, source: String, mode: Mode): String =
    lowered(name, source, mode).fold(ds => fail(s"lowering failed: $ds"), identity)

  /** Exit code, standard output, standard error. */
  private def run(name: String, source: String, stdin: String, mode: Mode): (Int, String, String) =
    val exe = Backend.ship(ir(name, source, mode), dir.resolve(s"$name-$mode"), checks = true)
      .fold(d => fail(d.message), identity)
    val (out, err) = (new StringBuilder, new StringBuilder)
    val code =
      (Process(Seq(exe.toAbsolutePath.toString), None, "FIXED_RT_STATS" -> "1") #<
        ByteArrayInputStream(stdin.getBytes("UTF-8"))).!(
        ProcessLogger(l => { val _ = out.append(l).append('\n') }, l => { val _ = err.append(l).append('\n') })
      )
    (code, out.toString, err.toString)

  /** Run `source` in every build mode (Rule CM5.7), expect exit 0, `expected` on stdout and no leak;
    * the runtime's counters per mode.
    */
  private def check(name: String, source: String, expected: String, stdin: String = ""): Map[Mode, Map[String, Long]] =
    Mode.values.toList.map { mode =>
      val (code, out, err) = run(name, source, stdin, mode)
      assertEquals(code, 0, s"$mode: $err")
      assertEquals(out, expected, mode)
      val counts = "\"(\\w+)\":(\\d+)".r.findAllMatchIn(err).map(m => m.group(1) -> m.group(2).toLong).toMap
      assert(counts.contains("allocs") && counts.contains("frees"), s"$mode: no runtime counters: $err")
      assertEquals(counts.get("allocs"), counts.get("frees"), s"$mode: leak: $err")
      mode -> counts
    }.toMap

  private val header = "use std.io.Console\n\n"

  /** Examples with golden files (`examples/golden/<name>.in`, `.out`): the slice's required set. */
  private val goldens = List("01_basics", "03_option_result", "04_json", "05_phantom_types")

  goldens.foreach { name =>
    test(s"example $name matches its golden output"):
      val golden = (ext: String) => java.nio.file.Files.readString(Path.of("examples", "golden", s"$name.$ext"))
      check(name, java.nio.file.Files.readString(Path.of("examples", s"$name.fixed")), golden("out"), golden("in"))
  }

  /** Every runtime benchmark in `bench/runtime`, each against its committed result line. */
  private val benches =
    java.nio.file.Files.list(Path.of("bench", "runtime")).toArray.toList.map(_.toString).collect {
      case p if p.endsWith(".fixed") => Path.of(p).getFileName.toString.stripSuffix(".fixed")
    }.sorted

  benches.foreach { name =>
    test(s"benchmark $name prints its expected result"):
      val read = (ext: String) => java.nio.file.Files.readString(Path.of("bench", "runtime", s"$name.$ext"))
      check(name, read("fixed"), read("expected"))
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
    Mode.values.foreach { mode =>
      val (code, out, err) = run("divzero", src, "", mode)
      assertEquals(out, "false\n", mode)
      assertEquals(code, 101, mode)
      assert(err.contains("panic: integer division by zero"), s"$mode: $err")
    }

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

  test("past the clone budget the caller calls the shared version (Rule CM5.6)"):
    val src = header + "data L:\n    E\n    C(h: u64, t: L)\n\n" +
      "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
      "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n" +
      "fn three(a: L, b: L, c: L) -> u64 = first(bump(a)) + first(bump(b)) + first(bump(c))\n\n" +
      "fn drive(a: L, b: L, c: L) -> u64 =\n" +
      "    three(a, b, c) + three(a, b, L.E) + three(a, L.E, c) + three(L.E, b, c) + three(L.E, L.E, L.E)\n" +
      "        + first(a) + first(b) + first(c)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(drive(L.C(1, L.E), L.C(2, L.E), L.C(3, L.E)).show())\n"
    check("cap_copy", src, "33\n")

  test("a shared value passed to a fip function is copied by its shared entry, and the copy freed (Rule CM5.5)"):
    val src = header + "data L:\n    E\n    C(h: u64, t: L)\n\n" +
      "fip fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
      "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n" +
      "fn main() -> () with Console =\n    let a = L.C(1, L.C(2, L.E))\n" +
      "    Console.print_line(first(bump(a)).show())\n    Console.print_line(first(a).show())\n"
    check("fip_copy", src, "2\n1\n")

  private val cells = header + "data L:\n    E\n    C(h: u64, t: L)\n\n" +
    "fn bump(xs: L) -> L =\n    match xs:\n        L.C(h, t) => L.C(h + 1, t)\n        L.E => L.E\n\n" +
    "fn first(xs: L) -> u64 =\n    match xs:\n        L.C(h, _) => h\n        L.E => 0\n\n" +
    "fn rest(xs: L) -> L =\n    match xs:\n        L.C(_, t) => t\n        L.E => L.E\n\n"

  test("a value and a part of it passed to one call stay intact (3.4.M6, Rule Q9.4)"):
    val src = cells + "fn both(xs: L, ys: L) -> u64 = first(bump(xs)) + first(ys)\n\n" +
      "fn main() -> () with Console =\n    let a = L.C(1, L.C(5, L.E))\n    Console.print_line(both(a, rest(a)).show())\n"
    check("alias_part", src, "7\n")

  test("an exclusive value captured by a closure called twice is not overwritten (3.4.M6)"):
    val src = cells + "fn twice(f: () -> u64) -> u64 = f() + f()\n\n" +
      "fn main() -> () with Console =\n    let a = L.C(1, L.E)\n" +
      "    Console.print_line(twice(() -> first(bump(a))).show())\n"
    check("closure_twice", src, "4\n")

  test("map over a fresh list reuses its cells; with --no-specialise only after an `rc == 1` test (Rule CM5.7)"):
    val src = header + "fn upto(n: u64) -> List of u64 =\n    List.unfold(0, (i) ->\n        if i >= n:\n" +
      "            List.Nil\n        else:\n            List.Cons(i, i + 1)\n    )\n\n" +
      "fn main() -> () with Console =\n" +
      "    Console.print_line(upto(4).map((x) -> x * 2).fold(0, (a, x) -> a + x).show())\n"
    val (default, plain) = (ir("map_reuse", src, Mode.Default), ir("map_reuse", src, Mode.NoSpecialise))
    assert(default.contains("@\"fx_u_List of u64.map[u64]#x[") && default.contains("call ptr @fx_reuse_excl("))
    assert(!plain.contains("#x[") && !plain.contains("call ptr @fx_reuse_excl("), plain)
    assert(plain.contains("call ptr @fx_reuse("), plain)

    /** A reuse after a passing `rc == 1` test counts as a reuse too, so the counters agree. */
    val counts = check("map_reuse", src, "12\n")
    assert(counts(Mode.Default).getOrElse("reuses", 0L) >= 4, counts)
    assertEquals(counts(Mode.Default)("reuses"), counts(Mode.NoSpecialise)("reuses"))

  /** `P of u8` has a scalar in slot 0 and a reference in slot 1; `P of String` has references in
    * both, and its cell reuses the `P of u8` one. A wrong reference map leaks or frees a scalar.
    * `N` puts every narrow scalar kind in a slot.
    */
  private val slotted = header + "data P of A:\n    P(a: A, s: String)\n\n" +
    "data N:\n    N(a: i8, f: f32, b: bool, c: char, u: u16, s: String, d: i32)\n\n" +
    "fn small(p: P of u8) -> String =\n    match p:\n        P.P(a, s) => a.show().concat(s)\n\n" +
    "fn text(p: P of String) -> String =\n    match p:\n        P.P(a, s) => a.concat(s)\n\n" +
    "fn narrow(n: N) -> String =\n    match n:\n        N.N(a, f, b, c, u, s, d) =>\n" +
    "            a.show().concat(\" \").concat(f.show()).concat(\" \").concat(b.show()).concat(\" \")\n" +
    "                .concat(c.show()).concat(\" \").concat(u.show()).concat(s).concat(d.show())\n\n" +
    "fn main() -> () with Console =\n" +
    "    Console.print_line(small(P.P(255, \"!\".concat(\"?\"))))\n" +
    "    Console.print_line(text(P.P(\"x\".concat(\"y\"), \"s\".concat(\"t\"))))\n" +
    "    Console.print_line(narrow(N.N(-3, 1.5, true, 'z', 65535, \" \".concat(\"s\"), -70000)))\n"

  test("one reference map per instance: P of u8 and P of String, narrow scalars beside references (4.1.M2)"):
    check("slots", slotted, "255!?\nxyst\n-3 1.5 true z 65535 s-70000\n")

  /** A round trip through a slot cannot see how the slot was widened, so these check the IR. */
  test("fields in declaration order; every payload store writes a canonical slot (Rules CM3.3, CM3.4)"):
    assertEquals(Layout.slots(List(CType.Int(8, true), CType.Boxed)), List(0, 1))
    val ir = this.ir("slots", slotted, Mode.Default)
    val stored = "store (\\S+) [^,]+, ptr %st\\.".r.findAllMatchIn(ir).map(_.group(1)).toSet
    assertEquals(stored, Set("i64", "ptr"), ir)
    assert("%wd\\.\\d+ = sext i8 ".r.findFirstIn(ir).isDefined, s"the i8 field is not sign-extended:\n$ir")
    assert("= trunc i64 %\\S+\\.s to i8".r.findFirstIn(ir).isDefined, s"the i8 field is not narrowed:\n$ir")

  /** A data type `W` of `refs` String fields and then a `u64`, built and dropped. */
  private def wide(refs: Int) =
    val fields = (0 until refs).map(i => s"f$i: String") :+ "n: u64"
    val args = (0 until refs).map(i => s"\"a\".concat(\"$i\")") :+ "7"
    val pats = "a" +: List.fill(refs - 2)("_") :+ "z" :+ "n"
    header + s"data W:\n    W(${fields.mkString(", ")})\n\n" +
      s"fn ends(w: W) -> String =\n    match w:\n        W.W(${pats.mkString(", ")}) => a.concat(z).concat(n.show())\n\n" +
      s"fn main() -> () with Console =\n    Console.print_line(ends(W.W(${args.mkString(", ")})))\n"

  /** A closure capturing `refs` Strings, after its two code slots. */
  private def captures(refs: Int) =
    val lets = (0 until refs).map(i => s"    let s$i = \"a\".concat(\"$i\")\n").mkString
    header + "fn call(f: () -> String) -> String = f()\n\n" + "fn main() -> () with Console =\n" + lets +
      s"    Console.print_line(call(() -> ${(0 until refs).map(i => s"s$i").mkString(".concat(")}${")" * (refs - 1)}))\n"

  private val bits = Layout.refMapBits

  test("a cell or closure with references in every slot of its reference map is freed whole (4.1.M2)"):
    check("wide", wide(bits), s"a0a${bits - 1}7\n")
    check(
      "captures",
      captures(bits - Layout.closureCode),
      (0 until bits - Layout.closureCode).map(i => s"a$i").mkString + "\n"
    )

  test("a reference beyond the reference map is B005, not a leak (4.1.M2)"):
    List(wide(bits + 1) -> "wide_over", captures(bits - Layout.closureCode + 1) -> "captures_over").foreach {
      (src, name) =>
        val ds = lowered(name, src).fold(identity, _ => fail(s"$name lowered"))
        assert(ds.exists(d => d.code == DiagCode.B005 && d.message.contains(s"payload slot $bits")), ds)
    }
