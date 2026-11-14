package fixed

import fixed.ast.Trees
import fixed.util.{DiagCode, Severity, SourceFile}

class CompilerSuite extends munit.FunSuite:

  private def compileFile(path: String) = Compiler.compile(CompilationUnit(SourceFile.fromPath(path)))

  test("M1: every example runs parser then stub typer with only the T000 body-typing note"):
    val examples = new java.io.File("examples").listFiles(_.getName.endsWith(".fixed")).sorted.toList
    assert(examples.nonEmpty)
    examples.foreach { f =>
      val unit = compileFile(f.getPath)
      assert(unit.tree.exists { case _: Trees.CompilationUnit => true; case _ => false }, f.getName)
      assertEquals(unit.diagnostics.map(d => (d.severity, d.code)), List((Severity.Info, DiagCode.T000)), f.getName)
    }

  test("M1: a unit with parse errors stops before the typer"):
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<test>", "fn f(x: i64 -> i64 = x\n")))
    assert(unit.hasErrors)
    assert(!unit.diagnostics.exists(_.code == DiagCode.T000))
