package fixed.parsing

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile}
import munit.FunSuite

/** Parser: error recovery, ParseResult API and example-file integration. */
class ParserRecoverySuite extends FunSuite with ParserTestSupport:

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
          case other          => fail(s"expected Error type annotation, got $other")

        /** Return type and body must NOT have been swallowed by the bad type. */
        fn.returnType match
          case Some(Trees.PrimitiveType("u64", _)) => ()
          case other                               => fail(s"expected u64 return, got $other")
        fn.body match
          case Some(Trees.IntLit(n, _)) => assertEquals(n, BigInt(0))
          case other                    => fail(s"expected IntLit body, got $other")
      case other => fail(s"expected single FnDecl, got $other")

  test("M3.4: malformed return type leaves body intact"):
    val src = SourceFile.fromString("<test>", "fn f() -> @ @ = 0\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(fn: Trees.FnDecl), _) =>
        fn.returnType match
          case Some(_: Trees.Error) => ()
          case other                => fail(s"expected Error return type, got $other")
        fn.body match
          case Some(Trees.IntLit(n, _)) => assertEquals(n, BigInt(0))
          case other                    => fail(s"expected IntLit body, got $other")
      case other => fail(s"expected single FnDecl, got $other")

  test("M3.3: junk arg in middle of a call yields N args with one Error"):
    val src = SourceFile.fromString("<test>", "fn f() -> u64 = g(1, @, 3)\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.Apply(_, args, _) =>
            assertEquals(args.length, 3, s"expected 3 args, got: $args")
            assert(
              args.exists {
                case _: Trees.Error => true
                case _              => false
              },
              s"expected an Error among args: $args"
            )
          case other => fail(s"expected Apply, got $other")
      case other => fail(s"expected FnDecl, got $other")

  test("M3.3: extra tokens between an arg and `,` are absorbed into Error"):
    /** `g(1 @ @, 3)`: parseExpr returns `1`; the post-item check sees `@`
      * (not `,` or `)`), syncs to `,`, wraps in Error.
      */
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
              case other          => fail(s"expected Error as first arg, got $other")
            args.last match
              case Trees.IntLit(n, _) => assertEquals(n, BigInt(3))
              case other              => fail(s"expected IntLit(3) as last arg, got $other")
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
        /** Expect: 4 statements — let x, Error, let y, ident y.
          * (Pre-M3.2 behaviour produced one Error per `@`, i.e. 6
          * statements; the test below pins the collapsed count.)
          */
        assertEquals(stmts.length, 4, s"expected 4 statements, got: $stmts")
        val errs = stmts.collect { case e: Trees.Error => e }
        assertEquals(errs.length, 1, s"expected exactly one Error among statements: $stmts")

        /** Lets remain parseable on either side. */
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
        /** First statement is the broken let (wrapped in Error); second
          * is the bare `x` ident.
          */
        assertEquals(stmts.length, 2)
        stmts.last match
          case Trees.Ident("x", _) => ()
          case other               => fail(s"expected trailing Ident('x'), got $other")
      case other => fail(s"expected FnDecl with Block body, got $other")

  test("M3.1: junk between decls collapses into a single Error item"):
    /** Two valid fns sandwich three stray punctuation tokens. The
      * pre-M3 behaviour produced one Error per stray token (3 extra
      * items); after anchor-based sync we get exactly one Error
      * covering the run, plus the two FnDecls.
      */
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
    /** A leading `@` must be skipped; the following `fn foo` must parse. */
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

  test("M2: unexpected top-level token produces a Trees.Error item"):
    val src = SourceFile.fromString("<test>", "@@@\n")
    val pr = Parser.parse(src)
    assert(pr.hasErrors, "expected a diagnostic for the stray token")
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        assert(
          items.exists {
            case _: Trees.Error => true
            case _              => false
          },
          s"expected a Trees.Error item, got: $items"
        )
      case other => fail(s"expected CompilationUnit, got $other")

  test("M2: stub `mod` declaration produces a Trees.Error item"):
    /** `mod` is still unimplemented; the stub calls `unsupported(...)`
      * which returns a Trees.Error gap node.
      */
    val pr = Parser.parse(SourceFile.fromString("<test>", "mod foo\n"))
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        assert(
          items.exists {
            case _: Trees.Error => true
            case _              => false
          },
          s"expected at least one Trees.Error item, got: $items"
        )
      case other => fail(s"expected CompilationUnit, got $other")

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

  test("ParseResult: happy path returns tree and empty diagnostics"):
    val src = SourceFile.fromString("<test>", "fn id(x: u64) -> u64 = x\n")
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    assertEquals(pr.diagnostics, Nil)
    assert(pr.trivia.isEmpty, "no comments or blank lines in this input")
    pr.tree match
      case Trees.CompilationUnit(List(_: Trees.FnDecl), _) => ()
      case other                                           => fail(s"expected single FnDecl, got $other")

  test("ParseResult: diagnostics surface without a caller Reporter"):
    /** missing name */
    val src = SourceFile.fromString("<test>", "fn ()\n")
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

  test("parse examples/01_basics.fixed (M3 milestone)"):
    val (tree, rep) = parseFile("examples/01_basics.fixed")
    val errors = rep.diagnostics.filter(_.isError)
    val errorReport =
      if errors.isEmpty then ""
      else "\n" + errors.map(rep.format).mkString("\n")
    assert(errors.isEmpty, s"01_basics.fixed produced ${errors.size} parse errors:$errorReport")

    /** Top-level: 1 use + 4 fns (greet, fib_iter, print_fibs, main). */
    tree match
      case Trees.CompilationUnit(items, _) =>
        val uses = items.collect { case u: Trees.UseDecl => u }
        val fns = items.collect { case f: Trees.FnDecl => f }
        assertEquals(uses.length, 1, s"expected 1 use, got ${uses.length}")
        assertEquals(fns.length, 4, s"expected 4 fns, got ${fns.length}")
        assertEquals(fns.map(_.name), List("greet", "fib_iter", "print_fibs", "main"))
      case _ => fail(s"expected CompilationUnit, got $tree")

end ParserRecoverySuite
