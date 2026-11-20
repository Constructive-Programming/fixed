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

  /** For each example and benchmark: how many baseline versions `--no-specialise` builds, and the
    * functions it still lowers per instance. A change in either is a change in what is measured.
    */
  private val programs = List(
    "examples/01_basics" -> (4, Set("fib_iter[u64]")),
    "examples/03_option_result" -> (1, Set()),
    "examples/04_json" ->
      (9, Set("join[List of String]", "map_optional[Tuple of (String, Json),Json,Maybe of Tuple of (String, Json)]")),
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
        )
      ),
    "bench/runtime/bintrees" -> (2, Set()),
    "bench/runtime/nat_loop" -> (4, Set()),
    "bench/runtime/list_pipeline" -> (3, Set()),
    "bench/runtime/records" -> (3, Set()),
    "bench/runtime/fail_chain" -> (2, Set())
  )

  test("every example and benchmark: the baseline versions built and the functions still lowered per instance"):
    programs.foreach { case (path, (baselines, instances)) =>
      val (lower, _) = lowered(path, file(path))
      assertEquals(lower.baselines.size, baselines, s"$path: ${lower.baselines}")
      assertEquals(lower.instances.keySet.toSet, instances, s"$path: ${lower.instances}")
      lower.instances.foreach((f, why) => assert(why.contains("(4.1.M7"), s"$path: $f: $why"))
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
