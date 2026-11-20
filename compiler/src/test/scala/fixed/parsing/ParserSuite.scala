package fixed.parsing

import fixed.ast.Trees
import fixed.util.SourceFile
import munit.FunSuite

/** Parser: expressions, statements and corpus-triage regressions. */
class ParserSuite extends FunSuite with ParserTestSupport:

  test("empty file parses to empty CompilationUnit"):
    val (t, rep) = parse("")
    assert(!rep.hasErrors)
    t match
      case Trees.CompilationUnit(items, _) => assertEquals(items, Nil)
      case _                               => fail(s"expected CompilationUnit, got $t")

  test("single use decl"):
    val (t, rep) = parse("use std.io.Console\n")
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.UseDecl(path, Nil, None, _)), _) =>
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
          case _                   => fail(s"expected body Ident('x'), got $body")
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
              case _                             => false
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
          case _                        => fail(s"expected IfExpr, got $body")
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
          case Trees.MethodCall(_, "concat", _, _) => ()
          case other                               => fail(s"expected MethodCall, got $other")
      case _ => fail(s"expected FnDecl, got $t")

  test("static call"):
    val src = """fn f() -> u64 = Nat.unfold(0)
                |""".stripMargin
    val (t, rep) = parse(src)
    assert(!rep.hasErrors, rep.formatAll)
    t match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(body), _)), _) =>
        body match
          case Trees.StaticCall("Nat", "unfold", Some(_), _) => ()
          case other                                         => fail(s"expected StaticCall, got $other")
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
          case Trees.UnaryOp(Trees.UnaryOperator.Neg, _, _) => ()
          case other                                        => fail(s"expected UnaryOp, got $other")
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

  test("M4.8: handle with operation clause and return clause"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = handle inner(input):
        |    Fail.fail(e) => Result.err(e)
        |    return(v) => Result.ok(v)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(h: Trees.HandleExpr), _)), _) =>
        assertEquals(h.operationClauses.length, 1)
        h.operationClauses.head match
          case Trees.OperationClause("Fail", "fail", params, _, _) =>
            assertEquals(params.length, 1)
          case other => fail(s"expected OperationClause Fail.fail, got $other")
        assert(h.returnClause.isDefined, "expected returnClause")
      case other => fail(s"expected HandleExpr in fn body, got $other")

  test("M4.8: handle with parameterless effect op"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = handle task():
        |    Async.spawn(t) => resume(())
        |    Async.yieldNow() => resume(())
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(h: Trees.HandleExpr), _)), _) =>
        assertEquals(h.operationClauses.length, 2)
        h.operationClauses.last match
          case Trees.OperationClause("Async", "yieldNow", params, _, _) =>
            assertEquals(params, Nil)
          case other => fail(s"expected OperationClause Async.yieldNow, got $other")
      case other => fail(s"expected HandleExpr, got $other")

  test("M4.8: handle with multi-line block body for an clause"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = handle task():
        |    Async.spawn(t) =>
        |        let q = remaining.push(t)
        |        resume(())
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(h: Trees.HandleExpr), _)), _) =>
        h.operationClauses.head.body match
          case Trees.Block(stmts, _) => assert(stmts.length >= 2)
          case other                 => fail(s"expected Block body, got $other")
      case other => fail(s"expected HandleExpr, got $other")

  test("M4.7: do-block with binds and a pure-style tail"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = do:
        |    x <- safeDivide(100.0, 3.0)
        |    y <- safeDivide(x, 2.0)
        |    Optional.pure(x + y)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(d: Trees.DoExpr), _)), _) =>
        assertEquals(d.stmts.length, 3)
        d.stmts.take(2).foreach {
          case _: Trees.DoBind => ()
          case other           => fail(s"expected DoBind, got $other")
        }
        d.stmts.last match
          case _: Trees.StaticCall => ()
          case other               => fail(s"expected StaticCall (Optional.pure), got $other")
      case other => fail(s"expected DoExpr in fn body, got $other")

  test("M4.7: nested calls with `<-` inside a sub-expression are not bind"):
    /** Here the `<-` appears inside a parenthesised sub-expression — it
      * doesn't make the outer statement a DoBind. (Synthetic shape: a
      * tuple element bound shouldn't match.)
      */
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = do:
        |    M.pure(1)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(d: Trees.DoExpr), _)), _) =>
        assertEquals(d.stmts.length, 1)
        d.stmts.head match
          case _: Trees.StaticCall => ()
          case other               => fail(s"expected StaticCall, got $other")
      case other => fail(s"expected DoExpr, got $other")

  test("triage: `()` accepted as `of` argument (UnitType)"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "fn f(action: M is Monad of ()) -> M of () = action\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("triage: leading `.` on a continuation line (chained method call)"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f(s: String) -> String =
        |    s.concat("a")
        |     .concat("b")
        |     .concat("c")
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("triage: leading `|>` on a continuation line (pipeline)"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f(xs: List of u64) -> List of u64 =
        |    xs.find((x) -> x == 0)
        |        |> map((x) -> x + 1)
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("triage: multi-line lambda body inside an arg list closes at the outer comma"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = call(
        |    (value) ->
        |        target.fold(
        |            () -> a,
        |            () -> b,
        |        ),
        |    () -> c,
        |)
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("triage: multi-line do-block inside an arg list closes at the outer comma"):
    /** `do:` is a `:`-introduced block; it only re-engages off-side
      * *inside* an already-re-engaged context (e.g. a lambda body),
      * mirroring the real-world pattern from example 06.
      */
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = call(
        |    (head) -> do:
        |        x <- step1
        |        step2(x),
        |    () -> c,
        |)
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("triage: nested `fn` declaration in a block"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn outer(x: u64) -> u64 =
        |    fn inner(y: u64) -> u64 = y + 1
        |    inner(x)
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(Trees.Block(stmts, _)), _)), _) =>
        /** First stmt is the nested fn; second is the call to inner. */
        stmts.head match
          case fn: Trees.FnDecl => assertEquals(fn.name, "inner")
          case other            => fail(s"expected nested FnDecl, got $other")
      case other => fail(s"expected outer FnDecl with Block body, got $other")

  test("M5: else-if chain"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f(n: i64) -> i64 =
        |    if n < 0: 0
        |    else if n > 10: 10
        |    else: n
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: TypeParamsHint on instance method"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """cap Foldable:
        |    fn fold<R>(init: R, f: (R, Part) -> R) -> R
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        c.body.head match
          case im: Trees.InstanceMethod => assertEquals(im.typeParamsHint, List("R"))
          case other                    => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected CapDecl, got $other")

  test("M5: prop in data body and as fn-cap statement"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """data Bounded of (lo: u64, hi: u64):
        |    Bounded(value: u64)
        |
        |    prop inRange: lo <= Self && Self <= hi
        |
        |fn between(min: u64, max: u64) -> cap of u64 =
        |    prop inRange: min <= Self && Self <= max
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: parameterised cap declaration"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """cap Between(min: u64, max: u64) of u64:
        |    prop inRange: min <= Self && Self <= max
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.valueParams.map(_.name), List("min", "max"))
      case other => fail(s"expected CapDecl, got $other")

  test("M5: literal arguments in `of (...)`"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "fn f() -> Bounded of (u64, 0, 10) = Bounded.Bounded(0)\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: forall with suchThat in prop body"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """cap Stack:
        |    prop popDec: forall (s: Self) suchThat s.size > 0 ->
        |        s.pop().size == s.size - 1
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: struct literal `T { field: value, ... }`"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "fn point(x: u64, y: u64) -> Point of u64 = Point { x: x, y: y }\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(s: Trees.StructLit), _)), _) =>
        assertEquals(s.typeName, "Point")
        assertEquals(s.fields.map(_._1), List("x", "y"))
      case other => fail(s"expected StructLit body, got $other")

  test("M5: use group import `use a.b.{X, Y}`"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "use std.io.{Console, FileSystem}\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(u: Trees.UseDecl), _) =>
        assertEquals(u.path, List("std", "io"))
        assertEquals(u.selectors, List("Console", "FileSystem"))
      case other => fail(s"expected UseDecl, got $other")

  test("M5: leading `+` continues an arithmetic chain"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f(a: u64, b: u64, c: u64) -> u64 =
        |    a
        |    + b
        |    + c
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: bare-name single-ctor pattern `BoundingBox(mn, mx)`"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn f(b: BoundingBox) -> u64 = match b:
        |    BoundingBox(mn, mx) => 0
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: deeply nested handle (do: ...)"):
    /** This was the chase-down case for owedDedents — multi-line
      * lambda body inside multi-line lambda body inside do-block
      * inside handle subject, all collapsing to a single closing
      * `)` at the outer indent.
      */
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """fn main() -> () =
        |    handle (do:
        |        outer(() ->
        |            inner(() ->
        |                a
        |                b
        |            , 256)
        |        )
        |    ):
        |        Op.op(x) => resume(())
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
