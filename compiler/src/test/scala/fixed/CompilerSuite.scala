package fixed

import fixed.util.SourceFile

class CompilerSuite extends munit.FunSuite:

  private def compileFile(path: String) = Compiler.compile(CompilationUnit(SourceFile.fromPath(path)))

  test("every example runs through parser, Namer and Typer"):
    val examples = new java.io.File("examples").listFiles(_.getName.endsWith(".fixed")).sorted.toList
    assert(examples.nonEmpty)
    examples.foreach { f =>
      val unit = compileFile(f.getPath)
      assert(unit.tree.isDefined, f.getName)
      assert(unit.symbols.scope.nonEmpty, f.getName)
    }

  test("M4: 01_basics compiles with no diagnostics"):
    assertEquals(compileFile("examples/01_basics.fixed").diagnostics, Nil)

  test("a unit with parse errors stops before the typer"):
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<test>", "fn f(x: i64 -> i64 = x\n")))
    assert(unit.hasErrors)
    assert(unit.diagnostics.forall(_.code.toString.startsWith("P")), unit.diagnostics)
