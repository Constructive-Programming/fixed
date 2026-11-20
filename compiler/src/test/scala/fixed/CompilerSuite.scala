package fixed

import fixed.ast.Trees
import fixed.core.Type
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

  test("M4/M5: examples 01 through 06 compile with no diagnostics"):
    (1 to 6).foreach { i =>
      val f = new java.io.File("examples").listFiles(_.getName.startsWith(f"$i%02d_")).head
      assertEquals(compileFile(f.getPath).diagnostics, Nil, f.getName)
    }

  test("4.0.M1: the typer hands its recorded types to later phases"):
    val unit = compileFile("examples/01_basics.fixed")
    val main = unit.tree.toList
      .flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
      .collectFirst { case f: Trees.FnDecl if f.name == "main" => f }
      .get
    val stmts = main.body.collect { case Trees.Block(ss, _) => ss }.get
    val call = stmts.last match
      case a: Trees.Apply => a
      case other          => fail(s"expected `print_fibs(20)`, got $other")
    assertEquals(unit.typeOf(call), Some(Type.UnitType))

    /** `print_fibs(20)`: the literal's variable is solved to the parameter's type. */
    val literal = call.args.head
    assertEquals(unit.typeOf(literal), Some(Type.PrimType("u64")))

  test("a unit with parse errors stops before the typer"):
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<test>", "fn f(x: i64 -> i64 = x\n")))
    assert(unit.hasErrors)
    assert(unit.diagnostics.forall(_.code.toString.startsWith("P")), unit.diagnostics)

  test("4.1.M4: --no-specialise is taken from any position"):
    val plain = List("compile", "f.fixed", "-o", "out")
    assertEquals(Main.modeOf(plain), (backend.Mode.Default, plain))
    List(0, 1, 2, 4).foreach { i =>
      assertEquals(Main.modeOf(plain.patch(i, List("--no-specialise"), 0)), (backend.Mode.NoSpecialise, plain))
    }

  test("4.1.M8: --budget <n> is taken from any position; a malformed one stays for the usage error"):
    val plain = List("ship", "f.fixed", "-o", "out")
    List(0, 1, 2, 4).foreach { i =>
      assertEquals(Main.modeOf(plain.patch(i, List("--budget", "7"), 0)), (backend.Mode(7), plain))
    }
    assertEquals(Main.modeOf(plain :+ "--budget")._2, plain :+ "--budget")
    assertEquals(Main.modeOf(plain ++ List("--budget", "-1"))._2, plain ++ List("--budget", "-1"))
