package fixed.backend

import java.nio.file.{Files, Path}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Baseline versions (`spec/compilation_model.md` Rule CM3.1) in the Core that `--no-specialise`
  * lowers: one version per polymorphic function or generated member, whatever the number of
  * instantiations, with its values of parameter type in slots (Rule CM3.3). Each test would fail on
  * a monomorphising backend.
  */
class BaselineSuite extends munit.FunSuite:

  private def unit(name: String, source: String): CompilationUnit =
    val u = Compiler.compile(CompilationUnit(SourceFile.fromString(s"$name.fixed", source)))
    assert(!u.hasErrors, u.diagnostics)
    u

  /** `source` lowered in the given mode, with the lowering's bookkeeping. */
  private def lowered(name: String, source: String, baseline: Boolean = true): (Lower, List[Fn]) =
    val lower = Lower(unit(name, source), baseline)
    (lower, lower.program.fold(ds => fail(s"$name: ${ds.map(_.message)}"), identity))

  private def file(path: String): String = Files.readString(Path.of(s"$path.fixed"))

  private def named(fns: List[Fn], name: String): Fn =
    fns.find(_.name == name).getOrElse(fail(s"no $name in ${fns.map(_.name)}"))

  /** The baseline versions `main` reaches. */
  private def reached(lower: Lower, fns: List[Fn]): Set[String] =
    Core.reach(Set("main"), fns.map(f => f.name -> f).toMap) & lower.baselines.toSet

  /** For each example and benchmark: how many baseline versions `--no-specialise` reaches, the
    * functions it still lowers per instance, and what the reason for each says. A change in any of
    * them is a change in what is measured.
    */
  private val programs = List(
    "examples/01_basics" -> (5, Set(), ""),
    "examples/03_option_result" -> (1, Set(), ""),
    "examples/04_json" -> (11, Set(), ""),
    "examples/05_phantom_types" ->
      (
        0,
        Set(
          "unlock[Door of Locked]",
          "lock[Door of Unlocked]",
          "open[Door of Unlocked]",
          "close[Door of Open]",
          "inspect[(),Door of Locked]",
          "bind[TcpSocket of Closed]",
          "accept[TcpSocket of Listening]",
          "send[TcpSocket of Established]",
          "disconnect[TcpSocket of Established]",
          "divide_units[Quantity of Meters,Quantity of Seconds]",
          "add[(),Quantity of ?29,Quantity of ?29]",
          "add[(),Quantity of Meters,Quantity of Meters]",
          "force[Quantity of Kilograms,Quantity of MetersPerSecondSq]",
          "kinematic_distance[Quantity of MetersPerSecond,Quantity of MetersPerSecondSq,Quantity of Seconds]",
          "show_quantity[(),Quantity of MetersPerSecond]",
          "show_quantity[(),Quantity of ?29]",
          "show_quantity[(),Quantity of Newtons]",
          "show_quantity[(),Quantity of Meters]"
        ),
        "no table of `"
      ),
    "bench/runtime/bintrees" -> (2, Set(), ""),
    "bench/runtime/nat_loop" -> (4, Set(), ""),
    "bench/runtime/list_pipeline" -> (3, Set(), ""),
    "bench/runtime/records" -> (3, Set(), ""),
    "bench/runtime/fail_chain" -> (2, Set(), "")
  )

  test("every example and benchmark: the baseline versions reached and the functions still lowered per instance"):
    programs.foreach { case (path, (baselines, instances, reason)) =>
      val (lower, fns) = lowered(path, file(path))
      assertEquals(reached(lower, fns).size, baselines, s"$path: ${reached(lower, fns)}")
      assertEquals(lower.instances.keySet.toSet, instances, s"$path: ${lower.instances}")
      lower.instances.foreach((f, why) => assert(why.contains(reason), s"$path: $f: $why"))
    }

  test("records folds a List of u64 and a List of Order through one foldLeft, its accumulator a slot"):
    val src = file("bench/runtime/records")
    val folds = (fns: List[Fn]) => fns.map(_.name).filter(_.contains(".foldLeft["))
    assertEquals(folds(lowered("records", src, baseline = false)._2).size, 2)
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

  test("fib_iter has one version, its N a slot; its arithmetic, literals and == are table calls"):
    val u = unit("01_basics", file("examples/01_basics"))
    val lower = Lower(u, baseline = true)
    val fns = lower.program.fold(ds => fail(s"${ds.map(_.message)}"), identity)
    assert(lower.baselines("fib_iter"), lower.baselines)
    assertEquals(fns.map(_.name).filter(_.startsWith("fib_iter")), List("fib_iter"))
    val fib = named(fns, "fib_iter")
    val (w, t) = (fib.params(0), fib.params(1))
    assertEquals(fib.params.map(_.ctype), List(CType.Witness, CType.Table, CType.Slot(w.name)))
    assertEquals(fib.ret, CType.Slot(w.name))
    val rhss = withClosures(fns, "fib_iter").flatMap(f => Core.lets(f.body).map(_._2))
    assert(!rhss.exists { case _: Rhs.Prim => true; case _ => false }, rhss)
    val entries = Tables.members(u.symbols, u.symbols.scope("Numeric"))
    val called = rhss.collect { case Rhs.CallTable(`t`, i, _, _) => entries(i) }
    assertEquals(called.toSet, Set("from_i64", "eq", "sub", "add"))
    assertEquals(
      lowered("01_basics", file("examples/01_basics"), baseline = false)._2.map(_.name)
        .filter(_.startsWith("fib_iter")),
      List("fib_iter[u64]")
    )

  test("a call at a type without a full table of the bound calls an instance; another call shares the baseline"):
    val (lower, fns) = lowered("partial", BaselineSuite.partial)
    assert(lower.baselines("same"), lower.baselines)
    assertEquals(lower.instances.keySet.toSet, Set("same[String]"))
    assert(lower.instances("same[String]").contains("`String` does not define `Ord.lt`"), lower.instances)
    assertEquals(fns.map(_.name).filter(_.startsWith("same")).sorted, List("same", "same[String]"))

  test("join has one version: its parts a slot of an implicit type parameter, folded through its Folding table"):
    val (lower, fns) = lowered("04_json", file("examples/04_json"))
    assertEquals(fns.map(_.name).filter(_.startsWith("join")), List("join"))
    val join = named(fns, "join")
    val (w, t) = (join.params(0), join.params(1))
    assertEquals(join.params.map(_.ctype), List(CType.Witness, CType.Table, CType.Slot(w.name), CType.Boxed))
    val body = Core.lets(join.body).map(_._2)
    assert(body.exists { case Rhs.CallTable(`t`, 0, _, _) => true; case _ => false }, body)
    assert(!Core.callees(join.body).exists(_.contains("foldLeft")), Core.callees(join.body))
    assert(lower.baselines("map_optional"), lower.baselines)

  test("count and sum over `is Folding of ...` have one version each, called at three list types"):
    val (lower, fns) = lowered("anonymous", BaselineSuite.anonymous)
    assertEquals(lower.instances.toMap, Map.empty[String, String])
    List("count", "sum").foreach(f => assertEquals(fns.map(_.name).filter(_.startsWith(f)), List(f)))
    val hidden = named(fns, "sum").params.take(4)
    assertEquals(hidden.map(_.ctype), List(CType.Witness, CType.Table, CType.Witness, CType.Table))
    assert(hidden(0).name.startsWith("w.N.") && hidden(2).name.startsWith("w.is."), hidden)

  test("two parameters with one anonymous bound at different types call an instance; at one type, the baseline"):
    val lower = Lower(unit("unshared", BaselineSuite.unshared), baseline = true)
    val ds = lower.program.fold(identity, fns => fail(s"lowered: ${fns.map(_.name)}"))
    assert(lower.instances("two[u8,String]").contains("at different types"), lower.instances)
    assert(lower.baselines("two"), lower.baselines)
    assert(ds.forall(_.message.contains("is Show")), ds)
    assert(Lower(unit("unshared", BaselineSuite.unshared)).program.isLeft, "the default mode rejects it too")

  test("a table entry the backend does not build is never called: the call is an instance, here B005"):
    val src = "fn right(xs: is Folding of u8) -> u64 = xs.fold_right(0, (x, n) -> n + 1)\n\n" +
      "fn byte(x: u8) -> u8 = x\n\nfn main() -> () =\n    let n = right([byte(1), byte(2)])\n    ()\n"
    val lower = Lower(unit("right", src), baseline = true)
    val ds = lower.program.fold(identity, fns => fail(s"lowered: ${fns.map(_.name)}"))
    assert(ds.exists(_.message.contains("fold_right")), ds)
    assert(lower.instances("right[List of u8]").contains("no `Folding.fold_right` the backend builds"), lower.instances)

  test("in the default mode the same program is lowered per instance"):
    val (_, fns) = lowered("generic", BaselineSuite.generic, baseline = false)
    assertEquals(fns.map(_.name).filter(_.startsWith("last")).sorted, List("last[String]", "last[i8]"))

object BaselineSuite:

  /** A generic function at a reference type and at a narrow signed scalar. */
  val generic = "use std.io.Console\n\n" +
    "fn pair(x: A, y: A) -> List of A = [x, y]\n\n" +
    "fn last(xs: List of A, d: A) -> A = xs.fold(d, (acc, x) -> x)\n\n" +
    "fn swap(p: (A, B)) -> (B, A) =\n    let (a, b) = p\n    (b, a)\n\n" +
    "fn byte(x: i8) -> i8 = x\n\n" +
    "fn main() -> () with Console =\n" +
    "    Console.print_line(last(pair(\"a\".concat(\"b\"), \"c\".concat(\"d\")), \"none\"))\n" +
    "    Console.print_line(last(pair(byte(-3), byte(-100)), byte(0)).show())\n" +
    "    let p = swap((byte(-7), \"x\".concat(\"y\")))\n" +
    "    Console.print_line(p.first.concat(p.second.show()))\n"

  /** `same` at `i64`, which has an `Ord` table, and at `String`, which has none (no `lt`). */
  val partial = "use std.io.Console\n\nfn same(a: N is Ord, b: N) -> bool = a == b\n\n" +
    "fn main() -> () with Console =\n    Console.print_line(same(\"x\", \"x\").show())\n" +
    "    Console.print_line(same(3, 4).show())\n"

  /** Functions over an anonymous `Folding` bound, at `List of String`, `List of u8` and `List of i64`. */
  val anonymous = "use std.io.Console\n\nfn count(xs: is Folding of A) -> u64 = xs.fold(0, (n, x) -> n + 1)\n\n" +
    "fn sum(zero: N is Numeric, xs: is Folding of N) -> N = xs.fold(zero, (a, x) -> a + x)\n\n" +
    "fn byte(x: u8) -> u8 = x\n\nfn main() -> () with Console =\n" +
    "    Console.print_line(count([\"a\".concat(\"b\"), \"c\".concat(\"d\")]).show())\n" +
    "    Console.print_line(count([byte(1), byte(2), byte(3)]).show())\n" +
    "    Console.print_line(sum(byte(0), [byte(200), byte(100)]).show())\n" +
    "    Console.print_line(sum(0, [200, 100]).show())\n" +
    "    Console.print_line(join([\"x\".concat(\"y\"), \"z\"], \", \"))\n"

  /** `two` at one type for both parameters, which shares the baseline version, and at two types,
    * where the instance, like the default mode, cannot represent `is Show` at a scalar (`B005`).
    */
  val unshared = "use std.io.Console\n\nfn two(a: is Show, b: is Show) -> String = a.show().concat(b.show())\n\n" +
    "fn byte(x: u8) -> u8 = x\n\nfn main() -> () with Console =\n" +
    "    Console.print_line(two(byte(7), \"x\".concat(\"y\")))\n" +
    "    Console.print_line(two(byte(1), byte(2)))\n"
