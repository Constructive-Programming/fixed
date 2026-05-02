package fixed.parsing

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile}
import munit.FunSuite

class ParserSuite extends FunSuite:

  private def parse(input: String): (Tree, Reporter) =
    val src = SourceFile.fromString("<test>", input)
    val rep = new Reporter(src)
    val tree = Parser.parse(src, rep)
    (tree, rep)

  private def parseFile(path: String): (Tree, Reporter) =
    val src = SourceFile.fromPath(path)
    val rep = new Reporter(src)
    val tree = Parser.parse(src, rep)
    (tree, rep)

  // ---- Smoke tests for individual productions ----

  test("empty file parses to empty CompilationUnit"):
    val (t, rep) = parse("")
    assert(!rep.hasErrors)
    t match
      case Trees.CompilationUnit(items, _) => assertEquals(items, Nil)
      case _ => fail(s"expected CompilationUnit, got $t")

  test("single use decl"):
    val (t, rep) = parse("use std.io.Console\n")
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.UseDecl(path, None, _)), _) =>
        assertEquals(path, List("std", "io", "Console"))
      case _ => fail(s"expected single UseDecl, got $t")

  test("simplest fn"):
    val src = """fn id(x: u64) -> u64 = x
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(name, _, params, _, None, Some(body), _)), _) =>
        assertEquals(name, "id")
        assertEquals(params.length, 1)
        assertEquals(params.head.name, "x")
        body match
          case Trees.Ident("x", _) => ()
          case _ => fail(s"expected body Ident('x'), got $body")
      case _ => fail(s"expected FnDecl, got $t")

  test("fn with effect row"):
    val src = """fn shout() -> () with Console = ()
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, Some(_), Some(_), _)), _) => ()
      case _ => fail(s"expected FnDecl with WithClause, got $t")

  test("named alias parameter"):
    val src = """fn foo(n: N is Numeric) -> N = n
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, params, _, _, _, _)), _) =>
        params.head.typeAnn match
          case Trees.NamedAlias("N", caps, _) =>
            assert(caps.exists {
              case Trees.CapRef("Numeric", _, _) => true
              case _ => false
            })
          case other => fail(s"expected NamedAlias, got $other")
      case _ => fail(s"expected FnDecl, got $t")

  test("if expression"):
    val src = """fn f(x: u64) -> u64 = if x == 0: 1 else: 0
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.IfExpr(_, _, _, _) => ()
          case _ => fail(s"expected IfExpr, got $body")
      case _ => fail(s"expected FnDecl, got $t")

  test("let expression with tuple pattern"):
    val src = """fn f() -> u64 =
                |    let (a, b) = (1, 2)
                |    a
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.Block(stmts, _)), _)), _) =>
        stmts.head match
          case Trees.LetExpr(Trees.TuplePat(els, _), _, _) =>
            assertEquals(els.length, 2)
          case other => fail(s"expected let with tuple pattern, got $other")
      case _ => fail(s"expected FnDecl with block body, got $t")

  test("method call chain"):
    val src = """fn f(x: String) -> String = x.concat("a").concat("b")
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.MethodCall(_, "concat", _, _, _) => ()
          case other => fail(s"expected MethodCall, got $other")
      case _ => fail(s"expected FnDecl, got $t")

  test("static call"):
    val src = """fn f() -> u64 = Nat.unfold(0)
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.StaticCall("Nat", "unfold", _, true, _) => ()
          case other => fail(s"expected StaticCall, got $other")
      case _ => fail(s"expected FnDecl, got $t")

  test("lambda expression"):
    val src = """fn f(g: (u64) -> u64) -> u64 = g(5)
                |""".stripMargin
    val (_, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)

  test("inline lambda argument"):
    val src = """fn f() -> u64 = call((x) -> x + 1)
                |""".stripMargin
    val (_, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)

  test("unary minus"):
    val src = """fn f(x: i64) -> i64 = -x
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.UnaryOp("-", _, _) => ()
          case other => fail(s"expected UnaryOp, got $other")
      case _ => fail(s"expected FnDecl, got $t")

  test("integer literal"):
    val (t, rep) = parse("fn f() -> u64 = 42\n")
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.IntLit(n, _)), _)), _) =>
        assertEquals(n, BigInt(42))
      case _ => fail(s"expected FnDecl with IntLit body, got $t")

  test("string literal"):
    val (t, rep) = parse("fn f() -> String = \"hello\"\n")
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.StringLit(s, _)), _)), _) =>
        assertEquals(s, "hello")
      case _ => fail(s"expected FnDecl with StringLit body, got $t")

  // ---- M2: gap nodes ----

  test("M2: unexpected top-level token produces a Trees.Error item"):
    val src = SourceFile.fromString("<test>", "@@@\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors, "expected a diagnostic for the stray token")
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        assert(items.exists {
          case _: Trees.Error => true
          case _ => false
        }, s"expected a Trees.Error item, got: $items")
      case other => fail(s"expected CompilationUnit, got $other")

  test("M2: stub `match` expression produces a Trees.Error in body"):
    // `match` is unimplemented in the Phase-2 parser scope; the stub
    // calls `unsupported(...)` which now returns a Trees.Error gap node
    // rather than a placeholder Ident. The single-token skip leaves
    // the trailing `x` as a separate top-level Error — anchor-based
    // recovery (M3) will fix that, but for M2 we only assert the body.
    val src = SourceFile.fromString(
      "<test>",
      "fn f(x: u64) -> u64 = match x\n"
    )
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    val items = pr.tree match
      case Trees.CompilationUnit(items, _) => items
      case other => fail(s"expected CompilationUnit, got $other")
    val fn = items.collectFirst { case f: Trees.FnDecl => f }
      .getOrElse(fail(s"expected an FnDecl among $items"))
    fn.body match
      case Some(_: Trees.Error) => ()
      case other => fail(s"expected Trees.Error body, got $other")

  test("M2: no Ident nodes named '<error>' or '<unsupported>' remain in the tree"):
    val srcs = Seq(
      "@@@\n",
      "fn f(x: u64) -> u64 = match x\n",
      "fn f(x: u64) -> u64 = handle x\n"
    )
    for s <- srcs do
      val pr = Parser.parse(SourceFile.fromString("<test>", s))
      val flat = collectIdents(pr.tree)
      assert(
        !flat.exists(n => n == "<error>" || n == "<unsupported>"),
        s"placeholder ident leaked into tree for input `${s.trim}`: $flat"
      )

  // Walk the tree and collect every Ident's name. Used by the no-leak
  // test above; deliberately exhaustive over the productions the
  // smoke-test inputs can produce.
  private def collectIdents(t: Tree): List[String] =
    t match
      case Trees.Ident(n, _) => List(n)
      case Trees.CompilationUnit(items, _) => items.flatMap(collectIdents)
      case Trees.FnDecl(_, _, params, ret, _, body, _) =>
        params.flatMap(p => collectIdents(p.typeAnn) ++ p.default.toList.flatMap(collectIdents)) ++
          collectIdents(ret) ++ body.toList.flatMap(collectIdents)
      case Trees.Block(stmts, _) => stmts.flatMap(collectIdents)
      case Trees.LetExpr(p, init, _) => collectIdents(p) ++ collectIdents(init)
      case Trees.IfExpr(c, t1, e, _) => collectIdents(c) ++ collectIdents(t1) ++ collectIdents(e)
      case Trees.BinOp(_, l, r, _) => collectIdents(l) ++ collectIdents(r)
      case Trees.UnaryOp(_, op, _) => collectIdents(op)
      case Trees.Apply(fn, args, _) => collectIdents(fn) ++ args.flatMap(collectIdents)
      case Trees.MethodCall(r, _, args, _, _) => collectIdents(r) ++ args.flatMap(collectIdents)
      case Trees.StaticCall(_, _, args, _, _) => args.flatMap(collectIdents)
      case Trees.Error(rec, _) => rec.flatMap(collectIdents)
      case _ => Nil

  // ---- M1 smoke: functional ParseResult API ----

  test("ParseResult: happy path returns tree and empty diagnostics"):
    val src = SourceFile.fromString("<test>", "fn id(x: u64) -> u64 = x\n")
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    assertEquals(pr.diagnostics, Nil)
    assert(pr.trivia.isEmpty, "M1 trivia table is always empty; M4 populates it")
    pr.tree match
      case Trees.CompilationUnit(List(_: Trees.FnDecl), _) => ()
      case other => fail(s"expected single FnDecl, got $other")

  test("ParseResult: diagnostics surface without a caller Reporter"):
    val src = SourceFile.fromString("<test>", "fn ()\n")  // missing name
    val pr = Parser.parse(src)
    assert(pr.hasErrors, "expected at least one error diagnostic")
    assert(pr.diagnostics.nonEmpty)

  test("legacy reporter adapter mirrors ParseResult diagnostics"):
    val src = SourceFile.fromString("<test>", "fn ()\n")
    val rep = new Reporter(src)
    val tree = Parser.parse(src, rep)
    val pr = Parser.parse(src)
    assertEquals(rep.diagnostics.toList, pr.diagnostics)
    assertEquals(tree.toString, pr.tree.toString)

  // ---- Integration: example 01 ----

  test("parse examples/01_basics.fixed (M3 milestone)"):
    val (tree, rep) = parseFile("examples/01_basics.fixed")
    val errors = rep.diagnostics.filter(_.isError)
    val errorReport =
      if errors.isEmpty then ""
      else "\n" + errors.map(rep.format).mkString("\n")
    assert(errors.isEmpty, s"01_basics.fixed produced ${errors.size} parse errors:$errorReport")

    // Top-level: 1 use + 4 fns (greet, fib_iter, print_fibs, main).
    tree match
      case Trees.CompilationUnit(items, _) =>
        val uses = items.collect { case u: Trees.UseDecl => u }
        val fns = items.collect { case f: Trees.FnDecl => f }
        assertEquals(uses.length, 1, s"expected 1 use, got ${uses.length}")
        assertEquals(fns.length, 4, s"expected 4 fns, got ${fns.length}")
        assertEquals(fns.map(_.name), List("greet", "fib_iter", "print_fibs", "main"))
      case _ => fail(s"expected CompilationUnit, got $tree")

end ParserSuite
