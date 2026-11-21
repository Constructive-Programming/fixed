package fixed.backend

import java.nio.file.{Files, Path}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Baseline versions (`spec/compilation_model.md` Rule CM3.1) in the Core lowered with no clone
  * chosen (`--no-specialise`, and round 0 of the default mode): one version per polymorphic
  * function or generated member, whatever the number of instantiations, with its values of
  * parameter type in slots (Rule CM3.3). Each test would fail on a monomorphising backend. Where
  * noted, a test reads the default mode's Core instead.
  */
class BaselineSuite extends munit.FunSuite:

  private def unit(name: String, source: String): CompilationUnit =
    val u = Compiler.compile(CompilationUnit(SourceFile.fromString(s"$name.fixed", source)))
    assert(!u.hasErrors, u.diagnostics)
    u

  /** `source` lowered with no clone (`--no-specialise`), with the lowering's bookkeeping. */
  private def lowered(name: String, source: String): (Lower, List[Fn]) =
    val lower = Lower(unit(name, source))
    (lower, lower.program.fold(ds => fail(s"$name: ${ds.map(_.message)}"), identity))

  /** The functions the default mode builds for `source`. */
  private def specialised(name: String, source: String): List[Fn] =
    Backend.analyse(unit(name, source)).fold(ds => fail(s"$name: ${ds.map(_.message)}"), _.fns)

  private def file(path: String): String = Files.readString(Path.of(s"$path.fixed"))

  private def named(fns: List[Fn], name: String): Fn =
    fns.find(_.name == name).getOrElse(fail(s"no $name in ${fns.map(_.name)}"))

  /** The baseline versions `main` reaches. */
  private def reached(lower: Lower, fns: List[Fn]): Set[String] =
    Core.reach(Set("main"), fns.map(f => f.name -> f).toMap) & lower.baselines.toSet

  /** For each example and benchmark: how many baseline versions `--no-specialise` reaches. None of
    * them lowers a function per instance any more. A change in either is a change in what is
    * measured.
    */
  private val programs = List(
    "examples/01_basics" -> 5,
    "examples/03_option_result" -> 1,
    /** `04_json` declares its own `Json`; the prelude's `satisfies` bodies fill its list tables. */
    "examples/04_json" -> 12,
    "examples/05_phantom_types" -> 14,
    "bench/runtime/bintrees" -> 2,
    "bench/runtime/nat_loop" -> 4,
    "bench/runtime/list_pipeline" -> 3,
    "bench/runtime/records" -> 3,
    "bench/runtime/fail_chain" -> 2
  )

  test("every example and benchmark: the baseline versions reached, and no function lowered per instance"):
    programs.foreach { (path, baselines) =>
      val (lower, fns) = lowered(path, file(path))
      assertEquals(reached(lower, fns).size, baselines, s"$path: ${reached(lower, fns)}")
      assertEquals(lower.instances.toMap, Map.empty[String, String], path)
    }

  test("records folds a List of u64 and a List of Order through one foldLeft, its accumulator a slot"):
    val src = file("bench/runtime/records")
    val folds = (fns: List[Fn]) => fns.map(_.name).filter(_.contains(".foldLeft["))
    assertEquals(folds(specialised("records", src)).size, 3)
    val (_, fns) = lowered("records", src)
    assertEquals(folds(fns), List("List of A.foldLeft[R]"))
    val f = named(fns, "List of A.foldLeft[R]")
    assertEquals(f.params.map(_.ctype).take(2), List(CType.Witness, CType.Witness))
    assertEquals(f.params.map(_.ctype).drop(2), List(CType.Boxed, CType.Slot("w.R"), CType.Boxed))
    assertEquals(f.ret, CType.Slot("w.R"))

  test("a generic function called at String and at i8 has one version; every value of parameter type is a slot"):
    val (lower, fns) = lowered("generic", BaselineSuite.generic)
    assertEquals(lower.instances.toMap, Map.empty[String, String])
    List("pair", "last", "swap").foreach { f =>
      assert(lower.baselines(f), lower.baselines)
      assertEquals(fns.count(g => g.name == f || g.name.startsWith(s"$f[")), 1, fns.map(_.name))
    }
    val last = named(fns, "last")
    val w = last.params.head
    assertEquals(w.ctype, CType.Witness)
    assertEquals(last.params.tail.map(_.ctype), List(CType.Boxed, CType.Slot(w.name)))
    assertEquals(last.ret, CType.Slot(w.name))
    val variables = Core.lets(named(fns, "swap").body).map(_._1.ctype)
    assert(variables.count(_.slot) >= 2, variables)
    assert(!variables.exists { case _: CType.Int | _: CType.Float | CType.Bool => true; case _ => false }, variables)

  /** `f` and the code of every closure it builds, transitively. */
  private def withClosures(fns: List[Fn], f: String): List[Fn] =
    val fn = named(fns, f)
    fn :: Core.lets(fn.body).collect { case (_, Rhs.Closure(code, _, _)) => code }.flatMap(withClosures(fns, _))

  test("fibIter has one version, its N a slot; its arithmetic, literals and == are table calls"):
    val u = unit("01_basics", file("examples/01_basics"))
    val lower = Lower(u)
    val fns = lower.program.fold(ds => fail(s"${ds.map(_.message)}"), identity)
    assert(lower.baselines("fibIter"), lower.baselines)
    assertEquals(fns.map(_.name).filter(_.startsWith("fibIter")), List("fibIter"))
    val fib = named(fns, "fibIter")
    val (w, t) = (fib.params(0), fib.params(1))
    assertEquals(fib.params.map(_.ctype), List(CType.Witness, CType.Table, CType.Slot(w.name)))
    assertEquals(fib.ret, CType.Slot(w.name))
    val rhss = withClosures(fns, "fibIter").flatMap(f => Core.lets(f.body).map(_._2))
    assert(!rhss.exists { case _: Rhs.Prim => true; case _ => false }, rhss)
    val entries = Tables.members(u.symbols, u.symbols.scope("Numeric"))
    val called = rhss.collect { case Rhs.CallTable(`t`, i, _, _) => entries(i) }
    assertEquals(called.toSet, Set("fromI64", "eq", "sub", "add"))
    assertEquals(
      specialised("01_basics", file("examples/01_basics")).map(_.name).filter(_.startsWith("fibIter")).sorted,
      List("fibIter", "fibIter[u64]")
    )

  test("`String` has a full table of `Ord`: calls at `String` and at a number share the baseline"):
    val (lower, fns) = lowered("same_at_two", BaselineSuite.sameAtTwo)
    assert(lower.baselines("same"), lower.baselines)
    assertEquals(lower.instances.toMap, Map.empty[String, String])
    assertEquals(fns.map(_.name).filter(_.startsWith("same")), List("same"))

  test("a failed baseline attempt keeps none of what it built: the function is an instance, its callee a baseline"):
    val (lower, fns) = lowered("failed_attempt", BaselineSuite.failedAttempt)
    assertEquals(lower.instances.keySet.toSet, Set("half[f64]"))
    assert(lower.instances("half[f64]").contains("float literal"), lower.instances)
    assertEquals(lower.baselines.toSet, Set("ident"))
    assertEquals(
      fns.map(_.name).filter(n => n.startsWith("half") || n.startsWith("ident")).sorted,
      List("half[f64]", "ident")
    )

  test("join has one version: its parts a slot of an implicit type parameter, folded through its Folding table"):
    val (lower, fns) = lowered("04_json", file("examples/04_json"))
    assertEquals(fns.map(_.name).filter(_.startsWith("join")), List("join"))
    val join = named(fns, "join")
    val (w, t) = (join.params(0), join.params(1))
    assertEquals(join.params.map(_.ctype), List(CType.Witness, CType.Table, CType.Slot(w.name), CType.Boxed))
    val body = Core.lets(join.body).map(_._2)
    assert(body.exists { case Rhs.CallTable(`t`, 0, _, _) => true; case _ => false }, body)
    assert(!Core.callees(join.body).exists(_.contains("foldLeft")), Core.callees(join.body))
    assert(lower.baselines("mapOptional"), lower.baselines)
    assertEquals(named(fns, "mapOptional").ret, CType.Boxed, "`Optional.none` is typed apart: the callee chooses")

  test("count and sum over `is Folding of ...` have one version each, called at three list types"):
    val (lower, fns) = lowered("anonymous", BaselineSuite.anonymous)
    assertEquals(lower.instances.toMap, Map.empty[String, String])
    List("count", "sum").foreach(f => assertEquals(fns.map(_.name).filter(_.startsWith(f)), List(f)))
    val hidden = named(fns, "sum").params.take(4)
    assertEquals(hidden.map(_.ctype), List(CType.Witness, CType.Table, CType.Witness, CType.Table))
    assert(hidden(0).name.startsWith("w.N.") && hidden(2).name.startsWith("w.isFolding"), hidden)

  test("two parameters with one anonymous bound are two type parameters: one baseline serves both calls"):
    val (lower, fns) = lowered("unshared", BaselineSuite.unshared)
    assert(lower.instances.isEmpty, lower.instances)
    assert(lower.baselines("two"), lower.baselines)
    assertEquals(
      named(fns, "two").params.map(_.ctype).take(4),
      List(CType.Witness, CType.Table, CType.Witness, CType.Table)
    )

  test("`compare` on `String` has a table entry: a call through `Ord` at `String` is the baseline's"):
    val src = "fn less(a: T is Ord, b: T) -> bool = a.compare(b).fold(() -> true, () -> false, () -> false)\n\n" +
      "fn main() -> () =\n    let _n = less(\"a\".concat(\"b\"), \"c\")\n    ()\n"
    val lower = Lower(unit("less", src))
    val fns = lower.program.fold(ds => fail(s"$ds"), identity)
    assert(lower.baselines("less") && lower.instances.isEmpty, lower.instances)
    assert(fns.exists(_.name == "String.compare"), fns.map(_.name))

  test("a table entry with no generated member runs the prelude's satisfies body: List's foldRight"):
    val src = "fn right(xs: is Folding of u8) -> u64 = xs.foldRight(0, (x, n) -> n + 1)\n\n" +
      "fn byte(x: u8) -> u8 = x\n\nfn main() -> () =\n    let _n = right([byte(1), byte(2)])\n    ()\n"
    val (lower, fns) = lowered("right", src)
    assert(lower.baselines("right"), lower.baselines)
    assert(fns.exists(_.name == "List of A satisfies Folding: foldRight[R]"), fns.map(_.name))

  test("polymorphic recursion: a clone calls the baseline version at a deeper key (default mode)"):
    val fns = specialised("poly", file("compiler/src/test/fixed/modes/poly_recursion"))
    val leaves = fns.map(_.name).filter(_.startsWith("leaves")).sorted
    assertEquals(leaves, List("leaves", "leaves[i64]"))
    assert(Core.callees(named(fns, "leaves[i64]").body)("leaves"), "the clone calls the baseline version")

  test("a table built at run time for a growing tuple of three elements is B005, not a pair's table"):
    val src = "fn deepEq(x: T is Eq, y: T, n: u64) -> bool =\n    if n == 0: x == y\n" +
      "    else: deepEq((x, n, x), (x, n, y), n - 1)\n\nfn main() -> () =\n    let _b = deepEq(1, 2, 2)\n    ()\n"
    val ds = Lower(unit("deep_eq", src)).program.fold(identity, fns => fail(s"lowered: ${fns.map(_.name)}"))
    assert(ds.exists(_.message.contains("a tuple of 3 elements (only pairs have one)")), ds)

  test("polymorphic recursion with no baseline version is B005, not an endless instance chain"):
    val src = "data N of A:\n    Leaf(a: A)\n    Deep(n: N of List of A)\n\n" +
      "fn half(n: N of A, x: X is Numeric) -> X =\n    match n:\n        N.Leaf(_) => x * 0.5\n" +
      "        N.Deep(m) => half(m, x)\n\nfn main() -> () =\n    let h = half(N.Leaf(1), 3.0)\n    ()\n"
    val ds = Lower(unit("half", src)).program.fold(identity, fns => fail(s"lowered: ${fns.map(_.name)}"))
    assert(ds.exists(_.message.contains("polymorphic recursion of `half`")), ds)
    val finite = List(
      src.replace("half(m, x)", "half(N.Leaf([1]), x)"),
      "fn f(a: A, x: X is Numeric, n: u64) -> X =\n    if n == 0: x * 0.5 else: g(7, x, n - 1)\n\n" +
        "fn g(b: B, x: X is Numeric, n: u64) -> X =\n    if n == 0: x else: f([b], x, n - 1)\n\n" +
        "fn main() -> () =\n    let h = f(1, 1.5, 3)\n    ()\n"
    )
    finite.foreach(p => assert(Lower(unit("finite", p)).program.isRight, s"a finite chain of instances:\n$p"))

  test("05: a phantom type parameter gets no witness; a result built by a constructor requirement is the caller's"):
    val (_, fns) = lowered("05_phantom_types", file("examples/05_phantom_types"))
    List("inspect", "showQuantity").foreach { f =>
      val fn = named(fns, f)
      assertEquals(fn.params.map(_.ctype).take(3), List(CType.Witness, CType.Table, CType.Slot(fn.params.head.name)), f)
      assert(!fn.params.exists(_.name.startsWith("w.S.")) && !fn.params.exists(_.name.startsWith("w.U.")), fn.params)
    }
    val add = named(fns, "add")
    assertEquals(add.params.map(_.ctype).take(4), List(CType.Witness, CType.Table, CType.Witness, CType.Table))
    assert(!add.params.exists(_.name.startsWith("w.U.")), add.params)
    val unlock = named(fns, "unlock")
    val List(wIn, tIn, wOut, tOut, door) = unlock.params: @unchecked
    assertEquals(List(wIn, tIn, wOut, tOut).map(_.ctype), List(CType.Witness, CType.Table, CType.Witness, CType.Table))
    assertEquals((door.ctype, unlock.ret), (CType.Slot(wIn.name), CType.Slot(wOut.name)))
    val calls = Core.lets(unlock.body).collect { case (_, Rhs.CallTable(t, i, _, _)) => (t, i) }
    assertEquals(
      calls.toSet,
      Set[(Atom, Int)]((tIn, 1), (tOut, 0)),
      "`name` on the argument table, `new` on the result one"
    )
    val plain = specialised("05_phantom_types", file("examples/05_phantom_types"))
    assertEquals(named(plain, "inspect[Door of Locked]").params.map(_.ctype), List(CType.Boxed))

  test("with the reference budget, the default mode adds a clone per requested key to the baseline version"):
    val fns = specialised("generic", BaselineSuite.generic)
    assertEquals(fns.map(_.name).filter(_.startsWith("last")).sorted, List("last", "last[String]", "last[i8]"))

object BaselineSuite:

  /** A generic function at a reference type and at a narrow signed scalar. */
  val generic = "use std.io.Console\n\n" +
    "fn pair(x: A, y: A) -> List of A = [x, y]\n\n" +
    "fn last(xs: List of A, d: A) -> A = xs.fold(d, (acc, x) -> x)\n\n" +
    "fn swap(p: (A, B)) -> (B, A) =\n    let (a, b) = p\n    (b, a)\n\n" +
    "fn byte(x: i8) -> i8 = x\n\n" +
    "fn main() -> () with Console =\n" +
    "    Console.printLine(last(pair(\"a\".concat(\"b\"), \"c\".concat(\"d\")), \"none\"))\n" +
    "    Console.printLine(last(pair(byte(-3), byte(-100)), byte(0)).show())\n" +
    "    let p = swap((byte(-7), \"x\".concat(\"y\")))\n" +
    "    Console.printLine(p.first.concat(p.second.show()))\n"

  /** `same` at `i64` and at `String`, which both have an `Ord` table. */
  val sameAtTwo = "use std.io.Console\n\nfn same(a: N is Ord, b: N) -> bool = a == b\n\n" +
    "fn main() -> () with Console =\n    Console.printLine(same(\"x\", \"x\").show())\n" +
    "    Console.printLine(same(\"x\", \"y\").show())\n" +
    "    Console.printLine(same(3, 4).show())\n"

  /** `half`'s baseline attempt calls `ident`, then fails on the float literal: `half` is an instance. */
  val failedAttempt = "use std.io.Console\n\nfn ident(x: A) -> A = x\n\n" +
    "fn half(x: N is Numeric) -> N =\n    let y = ident(x)\n    y * 0.5\n\n" +
    "fn main() -> () with Console =\n    Console.printLine(half(3.0).show())\n"

  /** Functions over an anonymous `Folding` bound, at `List of String`, `List of u8` and `List of i64`. */
  val anonymous = "use std.io.Console\n\nfn count(xs: is Folding of A) -> u64 = xs.fold(0, (n, x) -> n + 1)\n\n" +
    "fn sum(zero: N is Numeric, xs: is Folding of N) -> N = xs.fold(zero, (a, x) -> a + x)\n\n" +
    "fn byte(x: u8) -> u8 = x\n\nfn main() -> () with Console =\n" +
    "    Console.printLine(count([\"a\".concat(\"b\"), \"c\".concat(\"d\")]).show())\n" +
    "    Console.printLine(count([byte(1), byte(2), byte(3)]).show())\n" +
    "    Console.printLine(sum(byte(0), [byte(200), byte(100)]).show())\n" +
    "    Console.printLine(sum(0, [200, 100]).show())\n" +
    "    Console.printLine(join([\"x\".concat(\"y\"), \"z\"], \", \"))\n"

  /** `two` at one type for both parameters and at two: each anonymous bound is a type parameter of
    * its own (`spec/type_system.md` §5.1), so one baseline version serves both calls.
    */
  val unshared = "use std.io.Console\n\nfn two(a: is Show, b: is Show) -> String = a.show().concat(b.show())\n\n" +
    "fn byte(x: u8) -> u8 = x\n\nfn main() -> () with Console =\n" +
    "    Console.printLine(two(byte(7), \"x\".concat(\"y\")))\n" +
    "    Console.printLine(two(byte(1), byte(2)))\n"
