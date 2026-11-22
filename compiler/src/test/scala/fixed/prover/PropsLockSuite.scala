package fixed.prover

import java.nio.file.{Files, Path}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** `props.lock` (`spec/properties.md` Rule P7.1.b): written after a compile, read before proving. */
class PropsLockSuite extends munit.FunSuite:

  /** A fresh directory under the build's target, never the system's temporary directory. */
  private def dir(name: String): Path =
    val d = Path.of("compiler/target/props-lock-test", name)
    if Files.exists(d) then scala.util.Using.resource(Files.list(d))(_.forEach(f => Files.delete(f)))
    Files.createDirectories(d)

  private def compile(file: Path, props: List[String], steps: Long = Prover.defaultBudget): CompilationUnit =
    val code = ("cap Checked:" :: props.map("    " + _)).mkString("", "\n", "\nfn main() -> () = ()\n")
    val _ = Files.writeString(file, code)
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromPath(file.toString), proverSteps = steps))
    PropsLock.record(unit)

  private val fits = "prop fits: forall (x: u8) suchThat x <= 150 -> x + 100 <= 255"
  private val small = "prop small: forall (x: i8) suchThat x < 60 && x > -60 -> x * 2 < 120"
  private val ofSelf = "prop ofSelf: forall (s: Self) -> true"

  private def recorded(unit: CompilationUnit) = unit.props.map(v => v.name -> v.recorded)

  test("a second compile calls the prover zero times; the file is sorted and stable"):
    val file = dir("second").resolve("a.fixed")
    val first = compile(file, List(small, fits, ofSelf))
    assertEquals(recorded(first).map(_._2), List(false, false, false))
    val text = Files.readString(file.resolveSibling("props.lock"))
    val lines = text.linesIterator.toList
    assert(lines.head.startsWith("# props.lock:"), text)
    assertEquals(
      lines.tail.map(_.split(" ").take(3).mkString(" ")),
      List(
        "a.fixed:Checked.fits proved " + first.props(1).result.steps,
        "a.fixed:Checked.ofSelf open 0",
        "a.fixed:Checked.small proved " + first.props(0).result.steps
      )
    )
    assert(lines(2).endsWith(s" 0 - ${Prover.version} 100000 binder s of a type outside the fragment"), text)
    val second = compile(file, List(small, fits, ofSelf))
    assertEquals(recorded(second), List("Checked.small" -> true, "Checked.fits" -> true, "Checked.ofSelf" -> false))
    assertEquals(second.props.map(_.result), first.props.map(_.result))
    assertEquals(Files.readString(file.resolveSibling("props.lock")), text)

  test("editing a prop's body invalidates its line; another budget invalidates every line"):
    val file = dir("edit").resolve("a.fixed")
    val _ = compile(file, List(fits, small))
    val edited = compile(file, List(fits.replace("150", "155"), small))
    assertEquals(recorded(edited), List("Checked.fits" -> false, "Checked.small" -> true))
    val budget = compile(file, List(fits.replace("150", "155"), small), steps = 5000)
    assertEquals(recorded(budget).map(_._2), List(false, false))
    assert(Files.readString(file.resolveSibling("props.lock")).contains(" 5000"))

  test("the lines of another file beside it are kept; a refuted prop has no line"):
    val d = dir("two")
    val _ = compile(d.resolve("a.fixed"), List(fits))
    val b = compile(d.resolve("b.fixed"), List("prop wraps: forall (x: u8) -> x + 1 > x", small))
    assert(b.hasErrors)
    val names = Files.readString(d.resolve("props.lock")).linesIterator.toList.tail.map(_.takeWhile(_ != ' '))
    assertEquals(names, List("a.fixed:Checked.fits", "b.fixed:Checked.small"))

  test("lines the compile does not own, readable or not, are kept as they are"):
    val file = dir("foreign").resolve("a.fixed")
    val lock = file.resolveSibling("props.lock")
    val _ = Files.writeString(lock, "# a note by hand\nz.fixed:Q.p tested 100 k v 7\n")
    val _ = compile(file, List(fits))
    val text = Files.readString(lock).linesIterator.toList
    assertEquals(text.tail.map(_.takeWhile(_ != ' ')), List("#", "a.fixed:Checked.fits", "z.fixed:Q.p"))
