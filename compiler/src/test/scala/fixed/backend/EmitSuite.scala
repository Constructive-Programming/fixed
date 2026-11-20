package fixed.backend

import java.nio.file.Path

import fixed.util.{DiagCode, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Programs (examples, benchmarks and small inline ones) through the whole slice pipeline: check, lower, emit, `clang`, run. Each run
  * compares standard output and checks the runtime's counters: every allocation is freed
  * (slice §4.8). These tests need `clang` and fail, not skip, without it.
  */
class EmitSuite extends munit.FunSuite:

  private val dir = Path.of("target", "emit-tests")

  /** The build modes every program runs in (Rule CM5.7). */
  private val modes = List(Mode.Default, Mode.NoSpecialise)

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
    Native.run(exe, stdin)

  /** Run `source` in every build mode (Rule CM5.7), expect exit 0, `expected` on stdout and no leak;
    * the runtime's counters per mode.
    */
  private def check(name: String, source: String, expected: String, stdin: String = ""): Map[Mode, Map[String, Long]] =
    modes.map { mode =>
      val (code, out, err) = run(name, source, stdin, mode)
      assertEquals(code, 0, s"$mode: $err")
      assertEquals(out, expected, mode)
      val counts = Native.counters(err)
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

  /** Every runtime benchmark in `bench/runtime`, and every generic program in
    * `compiler/src/test/fixed/modes` (a default build must compute what the baseline computes), each
    * against its committed result.
    */
  private val programs =
    List(Path.of("bench", "runtime"), Path.of("compiler", "src", "test", "fixed", "modes")).flatMap {
      dir =>
        java.nio.file.Files.list(dir).toArray.toList.map(_.toString).collect {
          case p if p.endsWith(".fixed") => dir -> Path.of(p).getFileName.toString.stripSuffix(".fixed")
        }.sorted
    }

  programs.foreach { (dir, name) =>
    test(s"$dir/$name prints its expected result"):
      val read = (ext: String) => java.nio.file.Files.readString(dir.resolve(s"$name.$ext"))
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
    modes.foreach { mode =>
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

  /** `q` dies inside a `match` and `r` inside an `if`, each dropped there: the cells built after
    * them must not reuse theirs. `swap` reuses its own argument's cell.
    */
  test("a cell that dies inside a branch is not reused after it; a generic swap reuses its argument"):
    val src = header + "data Pair of (A, B):\n    P(a: A, b: B)\n\n" +
      "fn swap(p: Pair of (A, B)) -> Pair of (B, A) =\n    match p:\n        Pair.P(x, y) => Pair.P(y, x)\n\n" +
      "fn main() -> () with Console =\n    let q = swap(Pair.P(\"a\".concat(\"b\"), 7))\n" +
      "    match q:\n        Pair.P(n, s) => Console.print_line(s.concat(n.show()))\n" +
      "    let r = Pair.P(\"c\".concat(\"d\"), 8)\n    if \"x\".concat(\"y\") == \"xy\":\n" +
      "        match r:\n            Pair.P(s, n) => Console.print_line(s)\n    else:\n        Console.print_line(\"no\")\n" +
      "    let t = Pair.P(\"e\".concat(\"f\"), 9)\n    match t:\n        Pair.P(s, n) => Console.print_line(s)\n"
    val counts = check("reuse_after_branch", src, "ab7\ncd\nef\n")
    modes.foreach(m => assert(counts(m).getOrElse("reuses", 0L) >= 1, s"$m: swap reuses no cell: ${counts(m)}"))

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

  test("one baseline version of a generic function runs at String and at i8, freeing every String (Rule CM3.1)"):
    val src = BaselineSuite.generic
    val counts = check("generic", src, "cd\n-100\nxy-7\n")
    assertEquals(counts(Mode.Default).getOrElse("wops", 0L), 0L)
    assert(counts(Mode.NoSpecialise)("wops") > 0, counts)
    val plain = ir("generic", src, Mode.NoSpecialise)
    assert(plain.contains("call i64 @\"fx_u_last\"(ptr @fx_w_ref,"), plain)
    assert(plain.contains("call i64 @\"fx_u_last\"(ptr @fx_w_imm,"), plain)

  test("with room for one clone, one program runs the clone of a function and its baseline version (Rule CM5.6)"):
    val src = header + "fn pick(xs: List of A, d: A) -> A = xs.fold(d, (acc, x) -> x)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(pick([\"a\".concat(\"b\")], \"c\"))\n" +
      "    Console.print_line([1, 2].fold(0, (s, i) -> pick([i], s)).show())\n"
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("one_clone.fixed", src)))
    val fns = Backend.analyse(unit).fold(ds => fail(s"$ds"), _.fns)
    val one = Mode(fns.find(_.name == "pick").fold(0)(f => Core.lets(f.body).size))
    val (code, out, err) = run("one_clone", src, "", one)
    assertEquals((code, out), (0, "ab\n2\n"), err)
    val counts = Native.counters(err)
    assertEquals(counts.get("allocs"), counts.get("frees"), err)
    assert(counts("wops") > 0, "the String call runs the baseline version")

  test("one baseline version of a Numeric function wraps at u8 and not at i64; comparisons follow the sign (4.1.M7b)"):
    val src = header + "fn grow(x: N is Numeric) -> N = x * 2 + 100\n\n" +
      "fn compare(a: N is Numeric, b: N) -> String =\n" +
      "    let lt = if a < b: \"<\" else: \"\"\n    let le = if a <= b: \"<=\" else: \"\"\n" +
      "    let gt = if a > b: \">\" else: \"\"\n    let ge = if a >= b: \">=\" else: \"\"\n" +
      "    let eq = if a == b: \"==\" else: \"\"\n    let ne = if a != b: \"!=\" else: \"\"\n" +
      "    lt.concat(le).concat(gt).concat(ge).concat(eq).concat(ne)\n\n" +
      "fn arith(a: N is Numeric, b: N) -> N = -(a / b) + a % b - 1\n\n" +
      "fn shown(x: N is Numeric + Show) -> String = (x + 1).show()\n\n" +
      "fn byte(x: u8) -> u8 = x\n\nfn sbyte(x: i8) -> i8 = x\n\n" +
      "fn main() -> () with Console =\n" +
      "    Console.print_line(grow(byte(100)).show())\n    Console.print_line(grow(100).show())\n" +
      "    Console.print_line(compare(byte(255), byte(1)))\n    Console.print_line(compare(sbyte(-1), sbyte(1)))\n" +
      "    Console.print_line(compare(7, 7))\n" +
      "    Console.print_line(arith(17, 5).show())\n    Console.print_line(arith(byte(17), byte(5)).show())\n" +
      "    Console.print_line(shown(byte(255)))\n    Console.print_line(shown(2.5))\n"
    val _ = check("numeric", src, "44\n300\n>>=!=\n<<=!=\n<=>===\n-2\n254\n0\n3.5\n")
    val plain = ir("numeric", src, Mode.NoSpecialise)
    List("grow", "compare", "arith", "shown").foreach { f =>
      assertEquals(s"define \\S+ @\"fx_u_$f[\"\\[]".r.findAllIn(plain).size, 1, s"$f:\n$plain")
    }
    assert(plain.contains("call i64 @\"fx_u_grow\"(ptr @fx_w_imm, ptr @\"fx_t_u8:Numeric\","), plain)
    assert(plain.contains("call i64 @\"fx_u_grow\"(ptr @fx_w_imm, ptr @\"fx_t_i64:Numeric\","), plain)
    assert(plain.contains("@\"fx_t_u8:Show\""), plain)

  test("one baseline version over an anonymous Folding bound runs at List of String, of u8 and of i64 (4.1.M7c)"):
    val src = BaselineSuite.anonymous
    val _ = check("anonymous", src, "2\n3\n44\n300\nxy, z\n")
    val plain = ir("anonymous", src, Mode.NoSpecialise)
    assert(plain.contains("@\"fx_t_List of u8:Folding\""), plain)
    assert(plain.contains("@\"fx_t_List of String:Folding\""), plain)

  /** Without the element type, `shows` would show each element as `()` and drop none. */
  test("a parameter with an anonymous bound passed on to another function: the element type reaches it"):
    val src = header +
      "fn shows(xs: is Folding of (A is Show)) -> String = xs.fold(\"\", (a, x) -> a.concat(x.show()))\n\n" +
      "fn byte(x: u8) -> u8 = x\n\nfn g8(xs: is Folding of u8) -> String = shows(xs)\n\n" +
      "fn gs(xs: is Folding of String) -> String = shows(xs)\n\n" +
      "fn main() -> () with Console =\n    Console.print_line(g8([byte(200), byte(7)]))\n" +
      "    Console.print_line(gs([\"a\".concat(\"b\"), \"c\"]))\n"
    check("anonymous_passed_on", src, "2007\nabc\n")

  /** `Stack` and `List` order their variants differently: read with the other type's tags, a cell
    * of one looks empty to the fold of the other.
    */
  private val stacks = header + "data Stack of A:\n    Bottom\n    Push(top: A, rest: Stack of A)\n\n" +
    "fn byte(x: u8) -> u8 = x\n\nfn id(x: A) -> A = x\n\n" +
    "fn fwd(a: is Folding of (A is Show), b: is Folding of (A is Show)) -> String = two(b, a)\n\n"
  private val stack12 = "Stack.Push(byte(1), Stack.Push(byte(2), Stack.Bottom))"

  test("two parameters with one anonymous bound at two data types keep their own types in the instance"):
    val src = stacks + "fn two(a: is Folding of (A is Show), b: is Folding of (A is Show)) -> String =\n" +
      "    a.fold(\"\", (s, x) -> s.concat(x.show())).concat(\"|\").concat(b.fold(\"\", (s, x) -> s.concat(x.show())))\n\n" +
      s"fn main() -> () with Console =\n    Console.print_line(fwd($stack12, [byte(3), byte(4)]))\n" +
      "    Console.print_line(two([byte(5)], Stack.Push(byte(6), Stack.Bottom)))\n"
    check("anonymous_two", src, "34|12\n5|6\n")

  /** Pending (`.fail`: expected to fail) on a question for the typer. It gives both occurrences of
    * `is Folding of (A is Show)` one type, so the backend cannot tell which one `id(a)` has (it
    * takes `b`'s, `List of u8`) and prints `|34`. Should the typer give each anonymous bound its
    * own type variable (`spec/type_system.md` §5.1), which would also reject `if c: a else: b`?
    */
  test("an expression typed by one of two parameters with one anonymous bound keeps that parameter's type".fail):
    val src = stacks + "fn two(a: is Folding of (A is Show), b: is Folding of (A is Show)) -> String =\n" +
      "    id(a).fold(\"\", (s, x) -> s.concat(x.show())).concat(\"|\").concat(id(b).fold(\"\", (s, x) -> s.concat(x.show())))\n\n" +
      s"fn main() -> () with Console =\n    Console.print_line(two($stack12, [byte(3), byte(4)]))\n"
    check("anonymous_id", src, "12|34\n")

  test("a recursive generic function passes its own witness and table to itself (4.1.M7d)"):
    val src = header + "fn rep(x: A, n: u64) -> A =\n    if n == 0:\n        x\n    else:\n        rep(x, n - 1)\n\n" +
      "fn spin(d: is Door of Locked, n: u64) -> is Door of Locked =\n    if n == 0:\n        Door.new(d.name)\n" +
      "    else:\n        spin(d, n - 1)\n\ncap Locked\n\ncap Door of (State):\n    Self.fn new(name: String) -> Self\n" +
      "    fn name -> String\n\nfn main() -> () with Console =\n" +
      "    Console.print_line(rep(\"a\".concat(\"b\"), 3))\n    Console.print_line(rep(7, 2).show())\n" +
      "    Console.print_line(spin(Door.new(\"front\".concat(\"!\")), 2).name)\n"
    val _ = check("recursive", src, "ab\n7\nfront!\n")

  test("a function bounded by Ord runs at String through an instance and at i64 through its baseline version"):
    val _ = check("partial", BaselineSuite.partial, "true\nfalse\n")

  test("a closure in a baseline version captures the witness of a slot it binds"):
    val src = header + "data Pair of (A, B):\n    P(a: A, b: B)\n\n" +
      "fn later(p: Pair of (A, B)) -> () -> u64 =\n    () -> match p:\n        Pair.P(x, y) => 7\n\n" +
      "fn ident(x: A) -> A = x\n\n" +
      "fn shown(x: A is Show) -> String =\n    let y = ident(x)\n    y.show()\n\n" +
      "fn main() -> () with Console =\n" +
      "    Console.print_line(later(Pair.P(\"a\".concat(\"b\"), 3))().show())\n" +
      "    Console.print_line(shown(\"c\".concat(\"d\")))\n"
    val _ = check("captured_witness", src, "7\ncd\n")

  test("an `is Ordering` result is the prelude's Order, folded by variant"):
    val src = header + "fn lo() -> is Ordering = Ordering.lt\n\nfn same() -> is Ordering = Ordering.eq\n\n" +
      "fn hi() -> is Ordering = Ordering.gt\n\nfn main() -> () with Console =\n" +
      "    Console.print_line(lo().fold(() -> \"lt\", () -> \"eq\", () -> \"gt\"))\n" +
      "    Console.print_line(same().fold(() -> \"lt\", () -> \"eq\", () -> \"gt\"))\n" +
      "    Console.print_line(hi().fold(() -> \"lt\", () -> \"eq\", () -> \"gt\"))\n"
    val _ = check("order", src, "lt\neq\ngt\n")

  test("two parameters with one anonymous bound run at u8 with String and at u8 with u8"):
    val _ = check("unshared", BaselineSuite.unshared, "7xy\n12\n")

  test("a failed baseline attempt keeps no callee: the instance that replaces it runs"):
    val _ = check("failed_attempt", BaselineSuite.failedAttempt, "1.5\n")

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

  /** Closures of every narrow scalar kind, built in `make`, stored in a cell and called from
    * `call_all`, which did not create them; `twice` captures one closure in another. In `scaled` a
    * closure returning an `f32` aborts, so its code widens the zero the abort path returns.
    */
  private val closures = header +
    "data Fns:\n    Fns(neg: i8 -> i8, big: u8 -> u8, half: f32 -> f32, flip: bool -> bool, " +
    "same: char -> char, unit: () -> (), wide: f64 -> f64, mix: (i8, u8, f32, bool, char, ()) -> String)\n\n" +
    "fn make(k: i8) -> Fns =\n    let c = 'q'\n" +
    "    Fns.Fns((x) -> x - k, (x) -> x + 100, (x) -> x * 0.5, (b) -> !b, (x) -> c, () -> (), (x) -> x * 2.0,\n" +
    "        (a, b, f, t, ch, u) -> a.show().concat(\" \").concat(b.show()).concat(\" \").concat(f.show())\n" +
    "            .concat(\" \").concat(t.show()).concat(\" \").concat(ch.show()))\n\n" +
    "fn call_all(fs: Fns) -> String =\n    match fs:\n        Fns.Fns(neg, big, half, flip, same, unit, wide, mix) =>\n" +
    "            let twice = (x: i8) -> neg(neg(x))\n            unit()\n" +
    "            mix(twice(-100), big(100), half(-3.0), flip(false), same('a'), ()).concat(\" \").concat(wide(1.25).show())\n\n" +
    "fn check(n: u64) -> f32 with Fail of String =\n    if n > 2:\n        Fail.fail(\"big\")\n    else:\n        1.5\n\n" +
    "fn scaled(xs: List of u64) -> String =\n    handle xs.fold(0.25, (acc, x) -> acc + check(x)):\n" +
    "        Fail.fail(e) => e\n        return(v) => v.show()\n\n" +
    "fn main() -> () with Console =\n    Console.print_line(call_all(make(5)))\n" +
    "    Console.print_line(scaled([1, 2]))\n    Console.print_line(scaled([1, 5]))\n"

  test("closures take and return narrow scalars in slots, across functions and through an abort (4.1.M3)"):
    check("closure_slots", closures, "-110 200 -1.5 true q 2.5\n3.25\nbig\n")

  /** Widening and narrowing cancel out in a round trip, so the convention itself is checked here.
    * A closure call names the instructions that widen its argument `i` as `%wd.<call>.<i>`.
    */
  test("a closure's code takes and returns 64-bit slots; the caller widens, the callee narrows (Rule CM3.3(3))"):
    val ir = lowered("closure_slots", closures).fold(ds => fail(s"$ds"), identity)
    val types = (args: String) => args.split(", ").map(_.takeWhile(_ != ' ')).toSet
    val calls = "call (\\S+) %code\\.\\d+\\(([^)]*)\\)".r.findAllMatchIn(ir).toList
    val defs = "define (\\S+) @\"fx_u_lambda[^\"]*\"\\(([^)]*)\\)".r.findAllMatchIn(ir).toList
    assert(calls.nonEmpty && defs.nonEmpty, ir)
    (calls ++ defs).foreach { m =>
      assert((types(m.group(2)) + m.group(1)).subsetOf(Set("i64", "ptr")), s"not a slot convention: ${m.matched}")
    }
    val (caller, callee) = ("%(\\S+)\\.s = call i64 %code\\.\\d+\\([^)]*\\)\n", "i64 %(\\S+)\\.s\\) \\{\nentry:\n")
    List(
      "%wd\\.\\d+\\.\\d+ = sext i8 " -> "no i8 argument is sign-extended",
      "%wd\\.\\d+\\.\\d+ = zext i8 " -> "no u8 argument is zero-extended",
      "%wd\\.\\d+\\.\\d+ = zext i32 (?!%wb)" -> "no char argument is zero-extended",
      "%wd\\.\\d+\\.\\d+ = zext i1 " -> "no bool argument is widened to 0 or 1",
      "%wb\\.(\\d+\\.\\d+) = bitcast float \\S+ to i32\n  %wd\\.\\1 = zext i32 " ->
        "no f32 argument crosses as its bits",
      "%wd\\.\\d+\\.\\d+ = bitcast double " -> "no f64 argument crosses as its bits",
      "%wd\\.ret = sext i8 " -> "no i8 result is sign-extended",
      caller + "  %\\1 = trunc i64 %\\1\\.s to i8\n" -> "no caller narrows an i8 result",
      callee + "  %\\1 = trunc i64 %\\1\\.s to i8\n" -> "no closure narrows an i8 parameter",
      caller + "  %\\1\\.b = trunc i64 %\\1\\.s to i32\n  %\\1 = bitcast i32 " ->
        "no caller narrows an f32 from its bits",
      callee + "  %\\1\\.b = trunc i64 %\\1\\.s to i32\n  %\\1 = bitcast i32 " ->
        "no closure narrows an f32 from its bits"
    ).foreach((re, what) => assert(re.r.findFirstIn(ir).isDefined, s"$what:\n$ir"))

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
