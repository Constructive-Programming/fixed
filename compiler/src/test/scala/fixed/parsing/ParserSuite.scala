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

  // ---- M4.8: parseHandleExpr ----

  test("M4.8: handle with effect arm and return arm"):
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
        assertEquals(h.arms.length, 2)
        h.arms.head match
          case Trees.HandlerArm("Fail", "fail", params, _, _) =>
            assertEquals(params.length, 1)
          case other => fail(s"expected HandlerArm Fail.fail, got $other")
        h.arms.last match
          case _: Trees.ReturnArm => ()
          case other => fail(s"expected ReturnArm, got $other")
      case other => fail(s"expected HandleExpr in fn body, got $other")

  test("M4.8: handle with parameterless effect op"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = handle task():
        |    Async.spawn(t) => resume(())
        |    Async.yield_now() => resume(())
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(h: Trees.HandleExpr), _)), _) =>
        assertEquals(h.arms.length, 2)
        h.arms.last match
          case Trees.HandlerArm("Async", "yield_now", params, _, _) =>
            assertEquals(params, Nil)
          case other => fail(s"expected HandlerArm Async.yield_now, got $other")
      case other => fail(s"expected HandleExpr, got $other")

  test("M4.8: handle with multi-line block body for an arm"):
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
        h.arms.head match
          case Trees.HandlerArm(_, _, _, body, _) =>
            body match
              case Trees.Block(stmts, _) => assert(stmts.length >= 2)
              case other => fail(s"expected Block body, got $other")
          case other => fail(s"expected HandlerArm, got $other")
      case other => fail(s"expected HandleExpr, got $other")

  // ---- M4.7: parseDoExpr ----

  test("M4.7: do-block with binds and a pure-style tail"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f() -> u64 = do:
        |    x <- safe_divide(100.0, 3.0)
        |    y <- safe_divide(x, 2.0)
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
          case other => fail(s"expected DoBind, got $other")
        }
        d.stmts.last match
          case _: Trees.StaticCall => ()
          case other => fail(s"expected StaticCall (Optional.pure), got $other")
      case other => fail(s"expected DoExpr in fn body, got $other")

  test("M4.7: nested calls with `<-` inside a sub-expression are not bind"):
    // Here the `<-` appears inside a parenthesised sub-expression — it
    // doesn't make the outer statement a DoBind. (Synthetic shape: a
    // tuple element bound shouldn't match.)
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
          case other => fail(s"expected StaticCall, got $other")
      case other => fail(s"expected DoExpr, got $other")

  // ---- Triage fixes (M4 chase-down) ----

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
    // `do:` is a `:`-introduced block; it only re-engages off-side
    // *inside* an already-re-engaged context (e.g. a lambda body),
    // mirroring the real-world pattern from example 06.
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
        // First stmt is the nested fn; second is the call to inner.
        stmts.head match
          case fn: Trees.FnDecl => assertEquals(fn.name, "inner")
          case other => fail(s"expected nested FnDecl, got $other")
      case other => fail(s"expected outer FnDecl with Block body, got $other")

  // ---- M5 triage ----

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
          case other => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected CapDecl, got $other")

  test("M5: prop in data body and as fn-cap statement"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """data Bounded of (lo: u64, hi: u64):
        |    Bounded(value: u64)
        |
        |    prop in_range: lo <= Self && Self <= hi
        |
        |fn between(min: u64, max: u64) -> cap of u64 =
        |    prop in_range: min <= Self && Self <= max
        |""".stripMargin
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)

  test("M5: parameterised cap declaration"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      """cap Between(min: u64, max: u64) of u64:
        |    prop in_range: min <= Self && Self <= max
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
        |    prop pop_dec: forall (s: Self) suchThat s.size > 0 ->
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
    // This was the chase-down case for owedDedents — multi-line
    // lambda body inside multi-line lambda body inside do-block
    // inside handle subject, all collapsing to a single closing
    // `)` at the outer indent.
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

  // ---- M4.6: parseEffectDecl ----

  test("M4.6: simple effect with one op"):
    val src = SourceFile.fromString(
      "<test>",
      """effect Fail of E:
        |    fn fail(error: E) -> !
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.isLinear, false)
        assertEquals(e.name, "Fail")
        assertEquals(e.ofParams.length, 1)
        assertEquals(e.members.length, 1)
        e.members.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "fail")
          case other => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected EffectDecl, got $other")

  test("M4.6: linear effect"):
    val src = SourceFile.fromString(
      "<test>",
      """linear effect File:
        |    fn open(path: String) -> ()
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.isLinear, true)
      case other => fail(s"expected linear EffectDecl, got $other")

  test("M4.6: effect with no params, parameterless op"):
    val src = SourceFile.fromString(
      "<test>",
      """effect Channel of A:
        |    fn send(value: A) -> ()
        |    fn receive -> A
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.members.length, 2)
        e.members.last match
          case im: Trees.InstanceMethod =>
            assertEquals(im.name, "receive")
            assertEquals(im.params, Nil)
          case other => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected EffectDecl, got $other")

  // ---- M4.5: parseSatisfiesDecl ----

  test("M4.5: simple satisfies with constructor mappings"):
    val src = SourceFile.fromString(
      "<test>",
      """Maybe satisfies Optional:
        |    Just as some
        |    Nothing as none
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.typeName, "Maybe")
        s.capRef match
          case Trees.CapRef("Optional", _, _) => ()
          case other => fail(s"expected CapRef('Optional'), got $other")
        assertEquals(s.mappings.length, 2)
        assertEquals(
          s.mappings.collect { case Trees.ConstructorMapping(v, c, _) => (v, c) },
          List(("Just", "some"), ("Nothing", "none"))
        )
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: satisfies with `Self as` and `impossible as`"):
    val src = SourceFile.fromString(
      "<test>",
      """u64 satisfies Optional of Self:
        |    Self as some
        |    impossible as none
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.typeName, "u64")
        val ctors = s.mappings.collect { case Trees.ConstructorMapping(v, c, _) => (v, c) }
        val impossibles = s.mappings.collect { case Trees.ImpossibleMapping(c, _) => c }
        assertEquals(ctors, List(("Self", "some")))
        assertEquals(impossibles, List("none"))
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: satisfies with method override"):
    val src = SourceFile.fromString(
      "<test>",
      """Maybe satisfies Optional:
        |    fn isDefined = match self:
        |        Maybe.Nothing => false
        |        _ => true
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.mappings.length, 1)
        s.mappings.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "isDefined")
          case other => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: empty satisfies (no body)"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "Maybe satisfies Optional\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.mappings, Nil)
      case other => fail(s"expected SatisfiesDecl, got $other")

  // ---- M4.4: parseMatchExpr ----

  test("M4.4: simple match on data variants"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North => 1
        |    Direction.South => 2
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(fn: Trees.FnDecl), _) =>
        fn.body match
          case Some(m: Trees.MatchExpr) =>
            assertEquals(m.arms.length, 2)
            assertEquals(
              m.arms.map(_.pattern).collect {
                case Trees.DataVariantPat(Some(q), v, _, _, _) => s"$q.$v"
              },
              List("Direction.North", "Direction.South")
            )
          case other => fail(s"expected MatchExpr body, got $other")
      case other => fail(s"expected FnDecl, got $other")

  test("M4.4: wildcard arm + constructor arm"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Maybe of u64) -> u64 = match x:
        |    Maybe.Just(v) => v
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        m.arms.last.pattern match
          case _: Trees.WildcardPat => ()
          case other => fail(s"expected wildcard last arm, got $other")
      case other => fail(s"expected FnDecl with MatchExpr body, got $other")

  test("M4.4: or-pattern flattens left-associatively"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North | Direction.South | Direction.East => 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        // First arm should be OrPat(OrPat(N, S), E).
        m.arms.head.pattern match
          case Trees.OrPat(Trees.OrPat(_, _, _), _, _) => ()
          case other => fail(s"expected nested OrPat, got $other")
      case other => fail(s"expected MatchExpr, got $other")

  test("M4.4: guard clause"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: u64) -> u64 = match x:
        |    n if n == 0 => 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        assert(m.arms.head.guard.isDefined, "expected guard on first arm")
      case other => fail(s"expected MatchExpr, got $other")

  test("M4.4: arm with multi-line block body"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North =>
        |        let y = 1
        |        y + 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        m.arms.head.body match
          case Trees.Block(stmts, _) => assert(stmts.length >= 2)
          case other => fail(s"expected Block body for first arm, got $other")
      case other => fail(s"expected MatchExpr, got $other")

  // ---- M4.3: parseTypeAlias ----

  test("M4.3: simple chain alias"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "type Collection = Sequencing + Functor + Folding\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(t: Trees.TypeAlias), _) =>
        assertEquals(t.name, "Collection")
        assertEquals(t.valueParams, Nil)
        t.rhs match
          case Trees.IsBound(caps, _) =>
            assertEquals(
              caps.collect { case Trees.CapRef(n, _, _) => n },
              List("Sequencing", "Functor", "Folding")
            )
          case other => fail(s"expected IsBound chain, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  test("M4.3: single-cap alias is unwrapped"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "type Foo = Functor\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.TypeAlias(_, Nil, rhs, _)), _) =>
        rhs match
          case Trees.CapRef("Functor", _, _) => ()
          case other => fail(s"expected single CapRef rhs, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  test("M4.3: parameterised alias with refinement call"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "type PortRange(min: u16, max: u16) = u16 + between(min, max)\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(t: Trees.TypeAlias), _) =>
        assertEquals(t.valueParams.map(_.name), List("min", "max"))
        t.rhs match
          case Trees.IsBound(caps, _) =>
            assertEquals(caps.length, 2)
            caps(1) match
              case Trees.RefinementCall("between", args, _) => assertEquals(args.length, 2)
              case other => fail(s"expected RefinementCall, got $other")
          case other => fail(s"expected IsBound chain, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  // ---- M4.2: parseCapDecl ----

  test("M4.2: cap with instance and static methods"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sequencing:
        |    fn head -> is Optional of Part
        |    fn tail -> Self
        |    Self.fn cons(h: Part, t: Self) -> Self
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.name, "Sequencing")
        assertEquals(c.body.length, 3)
        c.body.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "head")
          case other => fail(s"expected InstanceMethod, got $other")
        c.body.last match
          case sm: Trees.StaticMethod =>
            assertEquals(sm.name, "cons")
            assertEquals(sm.params.length, 2)
          case other => fail(s"expected StaticMethod, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: cap extends another cap"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Optional extends Functor + Folding:
        |    fn isDefined -> Boolean
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.extendsList.length, 2)
        assertEquals(
          c.extendsList.collect { case Trees.CapRef(n, _, _) => n },
          List("Functor", "Folding")
        )
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: cap of-clause with multiple type params"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sized of (Part, Size is Numeric):
        |    fn size -> Size
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.ofParams.length, 2)
        c.ofParams(1) match
          case Trees.NamedAlias("Size", _, _) => ()
          case other => fail(s"expected NamedAlias for Size, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: empty cap (no `:` body)"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "cap Locked\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.name, "Locked")
        assertEquals(c.body, Nil)
      case other => fail(s"expected single empty CapDecl, got $other")

  test("M4.2: prop declaration in cap body"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sorted:
        |    prop sorted: true
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.body.length, 1)
        c.body.head match
          case p: Trees.PropDecl => assertEquals(p.name, "sorted")
          case other => fail(s"expected PropDecl, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  // ---- M4.1: parseDataDecl ----

  test("M4.1: simple unit-only enum"):
    val src = SourceFile.fromString(
      "<test>",
      """data Direction:
        |    North
        |    South
        |    East
        |    West
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Direction")
        assertEquals(d.ofParams, Nil)
        val vs = onlyVariants(d)
        assertEquals(vs.map(_.name), List("North", "South", "East", "West"))
        assert(vs.forall(_.fields.isEmpty))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: mixed-variants with fields and capability constraints"):
    val src = SourceFile.fromString(
      "<test>",
      """data Color:
        |    Red
        |    Green
        |    RGB(red: N is Numeric, green: N, blue: N)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        val vs = onlyVariants(d)
        assertEquals(vs.map(_.name), List("Red", "Green", "RGB"))
        val rgb = vs.find(_.name == "RGB").get
        assertEquals(rgb.fields.map(_.name), List("red", "green", "blue"))
        // The first field's type carries the `is Numeric` named alias.
        rgb.fields.head.typeAnn match
          case Trees.NamedAlias("N", caps, _) =>
            assert(caps.exists {
              case Trees.CapRef("Numeric", _, _) => true
              case _ => false
            })
          case other => fail(s"expected NamedAlias for first field, got $other")
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with single type param"):
    val src = SourceFile.fromString(
      "<test>",
      """data Maybe of A:
        |    Just(value: A)
        |    Nothing
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Maybe")
        assertEquals(d.ofParams.length, 1)
        d.ofParams.head match
          case Trees.TypeRef("A", None, _) => ()
          case other => fail(s"expected ofParam=TypeRef('A'), got $other")
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with paren tuple of type params"):
    val src = SourceFile.fromString(
      "<test>",
      """data Tagged of (Tag, Value):
        |    Tagged(value: Value)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.ofParams.length, 2)
        assertEquals(d.ofParams.collect { case Trees.TypeRef(n, _, _) => n }, List("Tag", "Value"))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: single-ctor sugar"):
    val src = SourceFile.fromString(
      "<test>",
      "data Config(host: String, port: u16, debug: bool)\n"
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Config")
        val vs = onlyVariants(d)
        assertEquals(vs.length, 1)
        val v = vs.head
        assertEquals(v.name, "Config")
        assertEquals(v.fields.map(_.name), List("host", "port", "debug"))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with OfValueParam (named value param + default)"):
    val src = SourceFile.fromString(
      "<test>",
      """data Bounded of (N is Numeric, lo: N = 0, hi: N = 10):
        |    Bounded(value: N)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.ofParams.length, 3)
        // First is a NamedAlias type expression (`N is Numeric`); the
        // other two are FnParam (lowered OfValueParam).
        d.ofParams.head match
          case _: Trees.NamedAlias => ()
          case other => fail(s"expected NamedAlias, got $other")
        d.ofParams.tail.foreach {
          case fp: Trees.FnParam => assert(fp.default.isDefined, "ofValueParam should carry default")
          case other => fail(s"expected FnParam, got $other")
        }
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: recursive references in fields"):
    val src = SourceFile.fromString(
      "<test>",
      """data Nat:
        |    Zero
        |    Succ(pred: Nat)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        val succ = onlyVariants(d).find(_.name == "Succ").get
        succ.fields.head.typeAnn match
          case Trees.TypeRef("Nat", None, _) => ()
          case other => fail(s"expected TypeRef('Nat'), got $other")
      case other => fail(s"expected single DataDecl, got $other")

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

  // DataDecl.variants holds variants and props mixed; tests usually
  // care only about the variants.
  private def onlyVariants(d: Trees.DataDecl): List[Trees.DataVariant] =
    d.variants.collect { case v: Trees.DataVariant => v }

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

  test("M2: stub `mod` declaration produces a Trees.Error item"):
    // `mod` is still unimplemented; the stub calls `unsupported(...)`
    // which returns a Trees.Error gap node.
    val pr = Parser.parse(SourceFile.fromString("<test>", "mod foo\n"))
    assert(pr.hasErrors)
    pr.tree match
      case Trees.CompilationUnit(items, _) =>
        assert(items.exists {
          case _: Trees.Error => true
          case _ => false
        }, s"expected at least one Trees.Error item, got: $items")
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
    assert(pr.trivia.isEmpty, "no comments or blank lines in this input")
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
