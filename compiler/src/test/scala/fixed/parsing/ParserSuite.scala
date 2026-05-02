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

  // ---- M3.4: type-expression recovery ----

  test("M3.4: malformed param type leaves param shape and body intact"):
    val src = SourceFile.fromString("<test>", "fn f(x: @ @ @) -> u64 = 0\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(fn: Trees.FnDecl), _) =>
        assertEquals(fn.name, "f")
        assertEquals(fn.params.length, 1)
        fn.params.head.typeAnn match
          case _: Trees.Error => ()
          case other => fail(s"expected Error type annotation, got $other")
        // Return type and body must NOT have been swallowed by the bad type.
        fn.returnType match
          case Trees.PrimitiveType("u64", _) => ()
          case other => fail(s"expected u64 return, got $other")
        fn.body match
          case Some(Trees.IntLit(n, _)) => assertEquals(n, BigInt(0))
          case other => fail(s"expected IntLit body, got $other")
      case other => fail(s"expected single FnDecl, got $other")

  test("M3.4: malformed return type leaves body intact"):
    val src = SourceFile.fromString("<test>", "fn f() -> @ @ = 0\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(fn: Trees.FnDecl), _) =>
        fn.returnType match
          case _: Trees.Error => ()
          case other => fail(s"expected Error return type, got $other")
        fn.body match
          case Some(Trees.IntLit(n, _)) => assertEquals(n, BigInt(0))
          case other => fail(s"expected IntLit body, got $other")
      case other => fail(s"expected single FnDecl, got $other")

  // ---- M3.3: delimited-list recovery (args, lists, tuples) ----

  test("M3.3: junk arg in middle of a call yields N args with one Error"):
    val src = SourceFile.fromString("<test>", "fn f() -> u64 = g(1, @, 3)\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.Apply(_, args, _) =>
            assertEquals(args.length, 3, s"expected 3 args, got: $args")
            assert(args.exists {
              case _: Trees.Error => true
              case _ => false
            }, s"expected an Error among args: $args")
          case other => fail(s"expected Apply, got $other")
      case other => fail(s"expected FnDecl, got $other")

  test("M3.3: extra tokens between an arg and `,` are absorbed into Error"):
    // `g(1 @ @, 3)`: parseExpr returns `1`; the post-item check sees `@`
    // (not `,` or `)`), syncs to `,`, wraps in Error.
    val src = SourceFile.fromString("<test>", "fn f() -> u64 = g(1 @ @, 3)\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.Apply(_, args, _) =>
            assertEquals(args.length, 2, s"expected 2 args (Error + IntLit), got: $args")
            args.head match
              case _: Trees.Error => ()
              case other => fail(s"expected Error as first arg, got $other")
            args.last match
              case Trees.IntLit(n, _) => assertEquals(n, BigInt(3))
              case other => fail(s"expected IntLit(3) as last arg, got $other")
          case other => fail(s"expected Apply, got $other")
      case other => fail(s"expected FnDecl, got $other")

  test("M3.3: list literal with bad element parses with Error in place"):
    val src = SourceFile.fromString("<test>", "fn f() -> u64 = [1, @, 3].first\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    val errs = collectAllErrors(pr.tree)
    assert(errs.nonEmpty, "expected an Error gap node somewhere in the tree")

  test("M3.3: tuple with bad element retains the other elements"):
    val src = SourceFile.fromString("<test>", "fn f() -> u64 = (1, @, 3).first\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    val errs = collectAllErrors(pr.tree)
    assert(errs.nonEmpty)

  // Walk the tree gathering every Trees.Error. Used by the recovery tests
  // above; the flattening-vs-shape distinction is tested elsewhere.
  private def collectAllErrors(t: Tree): List[Trees.Error] =
    t match
      case e: Trees.Error =>
        e :: e.recovered.flatMap(collectAllErrors)
      case Trees.CompilationUnit(items, _) => items.flatMap(collectAllErrors)
      case Trees.FnDecl(_, _, params, ret, _, body, _) =>
        params.flatMap(p => collectAllErrors(p.typeAnn)) ++
          collectAllErrors(ret) ++ body.toList.flatMap(collectAllErrors)
      case Trees.Block(stmts, _) => stmts.flatMap(collectAllErrors)
      case Trees.LetExpr(p, init, _) => collectAllErrors(p) ++ collectAllErrors(init)
      case Trees.IfExpr(c, t1, e, _) => collectAllErrors(c) ++ collectAllErrors(t1) ++ collectAllErrors(e)
      case Trees.BinOp(_, l, r, _) => collectAllErrors(l) ++ collectAllErrors(r)
      case Trees.UnaryOp(_, op, _) => collectAllErrors(op)
      case Trees.Apply(fn, args, _) => collectAllErrors(fn) ++ args.flatMap(collectAllErrors)
      case Trees.MethodCall(r, _, args, _, _) => collectAllErrors(r) ++ args.flatMap(collectAllErrors)
      case Trees.StaticCall(_, _, args, _, _) => args.flatMap(collectAllErrors)
      case Trees.TupleExpr(es, _) => es.flatMap(collectAllErrors)
      case Trees.ListExpr(es, _) => es.flatMap(collectAllErrors)
      case _ => Nil

  // ---- M3.2: block-body recovery ----

  test("M3.2: garbage statement is wrapped in Error; surrounding lets parse"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 =
        |    let x = 1
        |    @ @ @
        |    let y = 2
        |    y
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.Block(stmts, _)), _)), _) =>
        // Expect: 4 statements — let x, Error, let y, ident y.
        // (Pre-M3.2 behaviour produced one Error per `@`, i.e. 6
        // statements; the test below pins the collapsed count.)
        assertEquals(stmts.length, 4, s"expected 4 statements, got: $stmts")
        val errs = stmts.collect { case e: Trees.Error => e }
        assertEquals(errs.length, 1, s"expected exactly one Error among statements: $stmts")
        // Lets remain parseable on either side.
        val lets = stmts.collect { case l: Trees.LetExpr => l }
        assertEquals(lets.length, 2)
      case other => fail(s"expected FnDecl with Block body, got $other")

  test("M3.2: trailing junk on one line doesn't poison subsequent lines"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 =
        |    let x = 1 @ @
        |    x
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.Block(stmts, _)), _)), _) =>
        // First statement is the broken let (wrapped in Error); second
        // is the bare `x` ident.
        assertEquals(stmts.length, 2)
        stmts.last match
          case Trees.Ident("x", _) => ()
          case other => fail(s"expected trailing Ident('x'), got $other")
      case other => fail(s"expected FnDecl with Block body, got $other")

  // ---- M3.1: top-level recovery ----

  test("M3.1: junk between decls collapses into a single Error item"):
    // Two valid fns sandwich three stray punctuation tokens. The
    // pre-M3 behaviour produced one Error per stray token (3 extra
    // items); after anchor-based sync we get exactly one Error
    // covering the run, plus the two FnDecls.
    val src = SourceFile.fromString(
      "<test>",
      """fn a() -> u64 = 1
        |@ @ @
        |fn b() -> u64 = 2
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        val fns = items.collect { case f: Trees.FnDecl => f }
        val errs = items.collect { case e: Trees.Error => e }
        assertEquals(fns.map(_.name), List("a", "b"))
        assertEquals(errs.length, 1, s"expected one collapsed Error, got items: $items")
      case other => fail(s"expected CompilationUnit, got $other")

  test("M3.1: unknown token before a real decl recovers without consuming the decl head"):
    // A leading `@` must be skipped; the following `fn foo` must parse.
    val src = SourceFile.fromString("<test>", "@\nfn foo() -> u64 = 0\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        val fns = items.collect { case f: Trees.FnDecl => f }
        assertEquals(fns.map(_.name), List("foo"))
      case other => fail(s"expected CompilationUnit, got $other")

  test("M3.1: trailing junk after a valid decl yields a single Error item"):
    val src = SourceFile.fromString(
      "<test>",
      """fn a() -> u64 = 1
        |@@@@
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        val fns = items.collect { case f: Trees.FnDecl => f }
        val errs = items.collect { case e: Trees.Error => e }
        assertEquals(fns.map(_.name), List("a"))
        assertEquals(errs.length, 1, s"expected one Error after the fn, got: $items")
      case other => fail(s"expected CompilationUnit, got $other")

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
