package fixed

import fixed.ast.Trees
import fixed.core.Type
import fixed.util.{DiagCode, SourceFile}

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
      case other          => fail(s"expected `printFibs(20)`, got $other")
    assertEquals(unit.typeOf(call), Some(Type.UnitType))

    /** `printFibs(20)`: the literal's variable is solved to the parameter's type. */
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

  test("--no-ownership-clones is taken from any position and combines with --budget"):
    val plain = List("ship", "f.fixed", "-o", "out")
    List(0, 1, 2, 4).foreach { i =>
      val off = Main.modeOf(plain.patch(i, List("--no-ownership-clones"), 0))
      assertEquals(off, (backend.Mode.Default.copy(ownership = false), plain))
    }
    assertEquals(
      Main.modeOf(plain ++ List("--budget", "7", "--no-ownership-clones"))._1,
      backend.Mode(7, ownership = false)
    )
    assertEquals(Main.modeOf(List("--no-ownership-clones", "--no-specialise"))._1, backend.Mode(0, ownership = false))

  test("--no-fusion is taken from any position and combines with --no-specialise"):
    val plain = List("ship", "f.fixed", "-o", "out")
    List(0, 2, 4).foreach { i =>
      assertEquals(
        Main.modeOf(plain.patch(i, List("--no-fusion"), 0)),
        (backend.Mode.Default.copy(fusion = false), plain)
      )
    }
    assertEquals(Main.modeOf(List("--no-fusion", "--no-specialise"))._1, backend.Mode(0, fusion = false))

  test("4.1.M8: --budget <n> is taken from any position; a malformed one stays for the usage error"):
    val plain = List("ship", "f.fixed", "-o", "out")
    List(0, 1, 2, 4).foreach { i =>
      assertEquals(Main.modeOf(plain.patch(i, List("--budget", "7"), 0)), (backend.Mode(7), plain))
    }
    assertEquals(Main.modeOf(plain :+ "--budget")._2, plain :+ "--budget")
    assertEquals(Main.modeOf(plain ++ List("--budget", "-1"))._2, plain ++ List("--budget", "-1"))

  test("Rule 7.1.a: no expression builds a value of a type with no body"):
    val unit = Compiler.compile(
      CompilationUnit(SourceFile.fromString("<test>", "data Metres\nfn mk() -> Metres = Metres.Metres\n"))
    )
    assertEquals(unit.diagnostics.map(_.code), List(DiagCode.T001))

  test("Rule RP5.5: every diagnostic code has a class of the table; codes that are only warnings have 0"):
    val classes = Set(0) ++ (3 to 20).filterNot(_ == 13) + 99
    DiagCode.values.foreach(c => assert(classes.contains(c.errorClass), s"$c has class ${c.errorClass}"))
    assertEquals(DiagCode.values.filter(_.errorClass == 0).toList, List(DiagCode.E081, DiagCode.T021))

  test("Rule RP5.5: the exit code is the smallest class among the errors of the run"):
    def exit(sources: String*) =
      Main.exitCode(sources.toList.flatMap(s =>
        Compiler.compile(CompilationUnit(SourceFile.fromString("<t>", s))).diagnostics
      ))
    assertEquals(exit("fn f() -> u64 = 0\n"), 0)
    assertEquals(exit("fn f(x: u64) -> u64 = 0\n"), 0, "a warning (T021) sets no exit code")
    assertEquals(exit("fn f(x: i64 -> i64 = x\n"), 4)
    assertEquals(exit("fn f() -> u64 = true\n"), 6)
    assertEquals(exit("fn f() -> u64 = true\nfn g() -> u64 = nope\n"), 5)
    assertEquals(exit("fn f() -> u64 = true\n", "fn g(x: i64 -> i64 = x\n"), 4)
    val unsupported = "fn h(_x: ((u64, u64)) of u64) -> u64 = 0\n"
    assertEquals(exit(unsupported), 99)
    assertEquals(exit(unsupported, "fn f() -> u64 = true\n"), 6, "a limit of the compiler ranks below a type error")
    val b004 = util.Diagnostic(util.Severity.Error, DiagCode.B004, util.Span.NoSpan, "`clang` not found on PATH")
    assertEquals(Main.exitCode(List(b004)), 18)
