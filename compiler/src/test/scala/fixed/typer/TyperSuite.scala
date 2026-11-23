package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Proto, SymbolId, Type, render}
import fixed.parsing.{Parser, Scanner}
import fixed.util.{DiagCode, Reporter, SourceFile}

class TyperSuite extends munit.FunSuite, TyperTestSupport:

  /** Index `decls`, then type `expr` against them; returns the finished state, the expression tree and its type. */
  private def typed(expr: String, decls: String = ""): (TyperState, Tree, String) =
    val (_, table) = indexed(decls)
    val src = SourceFile.fromString("<expr>", expr)
    val rep = new Reporter(src)
    val tree = new Parser(new Scanner(src, rep), rep).parseExpr()
    assert(!rep.hasErrors, rep.formatAll)
    given Context = Context(Map.empty, table.scope, SymbolId.Root)
    val (s, _) = Typer.typedExpr(TyperState(table), tree, Proto.Wildcard)
    val done = s.finish
    (done, tree, done.symbols.render(done.typeOf(tree).get))

  test("M3: 1 + 2 * 3 is i64 at every node"):
    val (s, tree, t) = typed("1 + 2 * 3")
    assertEquals(t, "i64")
    tree match
      case Trees.BinOp(Trees.BinOperator.Add, l, r, _) =>
        assertEquals(s.typeOf(l), s.typeOf(tree))
        assertEquals(s.typeOf(r), s.typeOf(tree))
      case other => fail(s"unexpected tree $other")

  test("literals default per kind; tuples and logic"):
    assertEquals(typed("1.5 * 2")._3, "f64")
    assertEquals(typed("(1, \"a\")")._3, "(i64, String)")
    assertEquals(typed("1 < 2 && !false")._3, "bool")

  test("identifiers resolve to their symbols; calls check arguments"):
    val decls = "fn half(n: u64) -> u64 = n\nfn greet(name: String) -> () = ()\n"
    assertEquals(typed("half(4) + 1", decls)._3, "u64")
    assertEquals(codes(typed("greet(4)", decls)._1), List(DiagCode.T002))
    assertEquals(codes(typed("half(1, 2)", decls)._1), List(DiagCode.T006))
    assertEquals(codes(typed("nope + 1", decls)._1), List(DiagCode.T001))

  test("generic functions instantiate per call; Numeric bounds are checked"):
    val decls = "fn twice(n: N is Numeric) -> N = n\n"
    assertEquals(typed("twice(3)", decls)._3, "i64")
    assertEquals(typed("twice(2.5)", decls)._3, "f64")
    assertEquals(codes(typed("twice(\"x\")", decls)._1), List(DiagCode.T002))

  /** Type the body of the last `fn` in `code`, with its parameters in scope. */
  private def typedBody(code: String): (TyperState, String) =
    val unit = Parser.parse(SourceFile.fromString("<body>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, _) = Namer.index(unit.tree)
    val (fn, body) = unit.tree match
      case Trees.CompilationUnit(items, _) =>
        items.collect { case f: Trees.FnDecl => f }.last match
          case f => (f, f.body.getOrElse(fail("no body")))
      case other => fail(s"unexpected $other")
    val fnId = table.scope(fn.name)
    val params = table.denotations.filter(d => d.owner == fnId).map(d => d.name -> d.id)
    given Context = Context(params.toMap, table.scope, fnId)
    val (st, ty) = Typer.typedExpr(TyperState(table), body, Proto.Wildcard)
    val done = st.finish
    (done, done.symbols.render(done.unifier.zonk(ty)))

  test("let binds names for the rest of the block, including tuple patterns"):
    val (s, t) = typedBody("fn f(y: u64) -> u64 =\n    let x = 2\n    let (a, b) = (x, true)\n    a * y\n")
    assertEquals(s.diags, Nil)
    assertEquals(t, "u64")

  test("let type mismatches surface at the use site"):
    val (s, _) = typedBody("fn f(y: u64) -> u64 =\n    let (a, b) = (1, \"s\")\n    b * y\n")
    assertEquals(codes(s), List(DiagCode.T002))

  test("inside a generic body, literals adopt the Numeric type variable"):
    val (s, t) = typedBody("fn inc(n: N is Numeric) -> N = n + 1\n")
    assertEquals(s.diags, Nil)
    assertEquals(t, "N")

  test("arithmetic on a non-Numeric type and calling a non-function are errors"):
    val (s1, _) = typedBody("fn f(s: String) -> String = s + s\n")
    assertEquals(codes(s1), List(DiagCode.T002))
    val (s2, _) = typedBody("fn f(x: u64) -> u64 = x(1)\n")
    assertEquals(codes(s2), List(DiagCode.T007))

  test("M4: lambdas take parameter types from the expected function type"):
    val s = typedUnit("fn apply(f: u64 -> u64, x: u64) -> u64 = f(x)\nfn g() -> u64 = apply((n) -> n * 2, 3)\n")
    assertEquals(s.diags, Nil)
    assertEquals(
      codes(typedUnit("fn apply(f: u64 -> u64, x: u64) -> u64 = f(x)\nfn g() -> u64 = apply((n) -> \"s\", 3)\n")),
      List(DiagCode.T002)
    )

  test("derived fold: only Self as declared is pre-folded, not an element or a field at other arguments (Rule 7.4.a)"):
    val tree = "data Tree of A:\n    Leaf\n    Node(left: Tree of A, value: A, right: Tree of A)\n\n" +
      "data M of (A, B):\n    Nil\n    X(a: A, r: M of (B, A))\n\n"
    val elem =
      (body: String) => typedUnit(tree + s"fn f(t: Tree of Tree of u64) -> u64 = t.fold(() -> 0, (l, v, r) -> $body)\n")
    assertEquals(elem("l + r").diags, Nil)
    assertEquals(codes(elem("l + v + r")), List(DiagCode.T002))
    val other =
      (body: String) => typedUnit(tree + s"fn g(m: M of (u64, u64)) -> u64 = m.fold(() -> 0, (a, r) -> $body)\n")
    assertEquals(other("a").diags, Nil)
    assert(codes(other("a + r")).contains(DiagCode.T002))

  test("M4: if/else branches agree; the condition is bool"):
    assertEquals(typedUnit("fn f(x: u64) -> u64 =\n    if x > 1: x else: 0\n").diags, Nil)
    assertEquals(codes(typedUnit("fn f(x: u64) -> u64 =\n    if x: x else: 0\n")), List(DiagCode.T002))

  test("M4: members through satisfies, fields of single-variant data, and effect operations"):
    val s = typedUnit(
      "use std.io.Console\nfn f(n: u64, p: Tuple of (u64, String)) -> () with Console =\n    Console.printLine(n.show().concat(p.second))\n"
    )
    assertEquals(s.diags, Nil)
    assertEquals(codes(typedUnit("fn f(n: u64) -> String = n.nope()\n")), List(DiagCode.T001))

  test("M4: derived unfold steps build layers; derived fold takes one function per variant"):
    val s = typedUnit(
      "fn depth(n: u64) -> u64 =\n    Nat.unfold(n, (k) ->\n        if k == 0: Nat.Zero\n        else: Nat.Succ(k - 1)\n    ).fold(() -> 0, (d) -> d + 1)\n"
    )
    assertEquals(s.diags, Nil)
    val bad =
      typedUnit("fn bad(n: u64) -> u64 =\n    Nat.unfold(n, (k) -> Nat.Succ(\"x\")).fold(() -> 0, (d) -> d + 1)\n")
    assertEquals(codes(bad), List(DiagCode.T002))

  test("M4: a wrong argument inside 01_basics-style code is caught"):
    val s = typedUnit(
      "use std.io.Console\nfn greet(name: String) -> () with Console =\n    Console.printLine(\"Hi \".concat(name))\nfn main() -> () with Console =\n    greet(42)\n"
    )
    assertEquals(codes(s), List(DiagCode.T002))

  test("M5: match binds variant fields and checks top-level exhaustiveness"):
    val decls = "data Shape:\n    Circle(r: u64)\n    Square(side: u64)\n    Dot\n"
    assertEquals(
      typedUnit(
        decls +
          "fn area(s: Shape) -> u64 =\n    match s:\n        Shape.Circle(r) => r * r\n        Shape.Square(x) => x * x\n        Shape.Dot => 0\n"
      ).diags,
      Nil
    )
    assertEquals(
      codes(typedUnit(decls +
        "fn area(s: Shape) -> u64 =\n    match s:\n        Shape.Circle(r) => r * r\n        Shape.Dot => 0\n")),
      List(DiagCode.T020)
    )
    assertEquals(
      typedUnit(decls +
        "fn area(s: Shape) -> u64 =\n    match s:\n        Shape.Circle(r) => r\n        _ => 0\n").diags,
      Nil
    )
    assertEquals(
      codes(typedUnit(decls +
        "fn area(s: Shape) -> u64 =\n    match s:\n        Shape.Circle(r) => \"no\"\n        _ => 0\n")),
      List(DiagCode.T002)
    )
    assertEquals(
      codes(typedUnit(decls +
        "fn area(s: Shape) -> u64 =\n    match s:\n        Shape.Circle(r, q) => r\n        _ => 0\n")),
      List(DiagCode.T006)
    )

  test("M5: matches on bool and numbers need full coverage or a catch-all"):
    assertEquals(typedUnit("fn f(b: bool) -> u64 =\n    match b:\n        true => 1\n        false => 0\n").diags, Nil)
    assertEquals(
      codes(typedUnit("fn f(n: u64) -> u64 =\n    match n:\n        0 => 1\n        1 => 0\n")),
      List(DiagCode.T020)
    )

  test("Rule M4.2: a tuple pattern of irrefutable patterns is a catch-all; one with a literal is not"):
    val f = "fn f(p: ((u64, bool), u64)) -> u64 =\n    match p:\n"
    assertEquals(typedUnit(f + "        ((a, _), z) => a + z\n").diags, Nil)
    assertEquals(typedUnit(f + "        ((0, true), z) => z\n        (q, z) => z\n").diags, Nil)
    val partial = f + "        ((a, true), z) => a + z\n"
    assertEquals(
      typedUnit(partial).diags.map(d => (d.code, d.span.start)),
      List((DiagCode.T020, partial.indexOf("match")))
    )

  test("spec 4.9: Numeric members on a numeric literal and N.fromI64 on a type parameter"):
    assertEquals(typed("3.add(4).mul(2)")._3, "i64")
    assertEquals(typed("2.5.add(1.0)")._3, "f64")
    assertEquals(List("3 + 2.5", "3.add(2.5)", "2.5 * 3").map(typed(_)._3), List("f64", "f64", "f64"))
    val floats = "fn a(x: u64) -> u64 = x + 2.5\nfn b(x: N is Numeric) -> N = x + 2.5\nfn c() -> u8 = 3.add(2.5)\n"
    val at = List(floats.indexOf("2.5"), floats.indexOf("3.add"))
    val ds = typedUnit(floats).diags
    assertEquals(
      (ds.map(d => (d.code, d.span.start)), ds.head.message),
      (at.map((DiagCode.T002, _)), "expected u64, got f64")
    )
    assertEquals(typedUnit("fn d(x: f32) -> f32 = x + 2.5 + 3\n").diags, Nil)
    val (s, t) = typedBody("fn f(x: N is Numeric) -> N = N.fromI64(300).add(x)\n")
    assertEquals((s.diags, t), (Nil, "N"))
    val code = "fn f() -> u64 = 3.concat(4)\n"
    assertEquals(
      typedUnit(code).diags.map(d => (d.code, d.span.start, d.message)),
      List((DiagCode.T001, code.indexOf("3.concat"), "not found: member concat of a numeric literal"))
    )

  test("M5: handle binds operation parameters; resume takes the operation's result"):
    val eff = "effect Ask:\n    fn ask(prompt: String) -> u64\nfn ignoreLen(s: String) -> u64 = 0\n"
    val ok = typedUnit(
      eff +
        "fn run(f: () -> u64 with Ask) -> u64 =\n    handle f():\n        Ask.ask(p) => resume(p.show().concat(\"!\") |> ignoreLen)\n        return(v) => v + 1\n"
    )
    assertEquals(ok.diags.filter(_.isError), Nil)
    val bad =
      typedUnit(eff + "fn run(f: () -> u64 with Ask) -> u64 =\n    handle f():\n        Ask.ask(p) => resume(\"no\")\n")
    assertEquals(codes(bad), List(DiagCode.T002))
    assertEquals(codes(typedUnit("fn f() -> u64 = resume(1)\n")), List(DiagCode.T009))

  test("M5: nested fns are typed and scoped to their block"):
    assertEquals(typedUnit("fn outer(x: u64) -> u64 =\n    fn inner(y: u64) -> u64 = y * 2\n    inner(x)\n").diags, Nil)
    assertEquals(
      codes(typedUnit("fn outer(x: u64) -> u64 =\n    fn inner(y: u64) -> u64 = \"s\"\n    inner(x)\n")),
      List(DiagCode.T002)
    )

  test("M5: cap statics, do-notation and higher-kinded type variables"):
    val s = typedUnit(
      "cap Box of A:\n    Self.fn wrap(value: A) -> Self\n    fn flatMap(f: A -> Self of B) -> Self of B\n" +
        "fn twice(b: M is Box of u64) -> M of u64 =\n    do:\n        x <- b\n        M.wrap(x * 2)\n"
    )
    assertEquals(s.diags.filter(_.isError), Nil)

  test("M5: overloaded fold picks the candidate whose arguments fit"):
    val s = typedUnit(
      "fn sum(xs: List of u64) -> u64 = xs.fold(0, (acc, x) -> acc + x)\nfn len(xs: List of u64) -> u64 = xs.fold((h, t) -> t + 1, () -> 0)\n"
    )
    assertEquals(s.diags, Nil)
    assertEquals(codes(typedUnit("fn bad(xs: List of u64) -> u64 = xs.fold(\"x\")\n")).headOption, Some(DiagCode.T006))

  test("M5: pipes insert the left side as the first argument"):
    assertEquals(typedUnit("fn add(a: u64, b: u64) -> u64 = a + b\nfn f(x: u64) -> u64 = x |> add(1)\n").diags, Nil)
    assertEquals(
      codes(typedUnit("fn add(a: u64, b: u64) -> u64 = a + b\nfn f(x: String) -> u64 = x |> add(1)\n")),
      List(DiagCode.T002)
    )

  private val optionalCap =
    "cap Opt:\n    Self.fn some(value: Part) -> Self\n    Self.fn none -> Self\ndata Maybe of A:\n    Just(value: A)\n    Nothing\n"

  test("3.1.M3: satisfies covers every constructor requirement exactly once"):
    assertEquals(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n").diags, Nil)
    assertEquals(codes(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Just as some\n")), List(DiagCode.T010))
    assertEquals(
      codes(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Just as some\n    Just as some\n    Nothing as none\n")),
      List(DiagCode.T014)
    )
    assertEquals(
      codes(typedUnit(optionalCap +
        "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n    Nothing as other\n")),
      List(DiagCode.T014)
    )
    assertEquals(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Just as some\n    impossible as none\n").diags, Nil)

  test("P6 D17: a satisfaction must define every member without a default (T010 names them)"):
    val cap = "cap Shape:\n    fn area -> u64\n    fn sides -> u64\n    fn big -> bool = self.area > 9\n"
    val data = "data Sq:\n    Sq(w: u64)\n    Dot\n"
    val none = typedUnit(cap + data + "Sq satisfies Shape\n")
    assertEquals(codes(none), List(DiagCode.T010))
    assertEquals(none.diags.head.message, "Sq does not satisfy Shape: missing area, sides")
    val one = typedUnit(cap + data + "Sq satisfies Shape:\n    fn area = 4\n")
    assertEquals(one.diags.map(_.message), List("Sq does not satisfy Shape: missing sides"))
    assertEquals(typedUnit(cap + data + "Sq satisfies Shape:\n    fn area = 4\n    fn sides = 4\n").diags, Nil)

  test("P6 D17: members inherited through extends are required too, unless another satisfaction covers them"):
    val caps =
      "cap Base:\n    fn base -> u64\ncap Top extends Base:\n    fn top -> u64\ndata D:\n    D(n: u64)\n    E\n"
    assertEquals(
      typedUnit(caps + "D satisfies Top:\n    fn top = 1\n").diags.map(_.message),
      List("D does not satisfy Top: missing base")
    )
    assertEquals(typedUnit(caps + "D satisfies Top:\n    fn top = 1\n    fn base = 2\n").diags, Nil)
    assertEquals(
      typedUnit(caps + "D satisfies Base:\n    fn base = 2\nD satisfies Top:\n    fn top = 1\n").diags,
      Nil
    )

  test("P6 D17: derivation defines members: fold, accessors, Functor.map, field-wise Show"):
    val folds =
      "cap Sum2:\n    Self.fn a -> Self\n    Self.fn b -> Self\n    fn fold(onA: () -> R, onB: () -> R) -> R\n" +
        "data AB:\n    A\n    B\nAB satisfies Sum2:\n    A as a\n    B as b\n"
    assertEquals(typedUnit(folds).diags, Nil)
    val acc = "cap Named:\n    fn name -> String\ndata P(name: String, n: u64)\nP satisfies Named\n"
    assertEquals(typedUnit(acc).diags, Nil)
    assertEquals(typedUnit("data Box of A:\n    Box(v: A)\nBox satisfies Functor\n").diags, Nil)
    assertEquals(typedUnit("data Pt:\n    Pt(x: u64)\nPt satisfies Show\n").diags, Nil)
    assertEquals(
      typedUnit("data Holder:\n    Holder(f: u64 -> u64)\nHolder satisfies Show\n").diags.map(_.message),
      List("Holder does not satisfy Show: missing show")
    )

  test("P6 D17: a covering satisfaction must name the capability; reaching it is not enough"):
    val caps =
      "cap Base:\n    fn base -> u64\ncap T1 extends Base:\n    fn t1 -> u64\ncap T2 extends Base:\n    fn t2 -> u64\n" +
        "data D:\n    D(n: u64)\n    E\n"
    val diags = typedUnit(caps + "D satisfies T1:\n    fn t1 = 1\nD satisfies T2:\n    fn t2 = 1\n").diags
    assertEquals(
      diags.map(_.message),
      List("D does not satisfy T1: missing base", "D does not satisfy T2: missing base")
    )
    val bt = "cap Base:\n    fn base -> u64\ncap Top extends Base:\n    fn top -> u64\ndata D:\n    D(n: u64)\n    E\n"
    assertEquals(
      typedUnit(bt + "D satisfies Base\nD satisfies Top:\n    fn top = 1\n").diags.map(_.message),
      List("D does not satisfy Base: missing base")
    )

  test("P6 D17: defaults and statics: a default needs no definition, an inherited constructor needs a body"):
    val d = "data D:\n    D(n: u64)\n    E\n"
    val dflt = "cap Base:\n    fn base -> u64 = 3\ncap Top extends Base:\n    fn top -> u64\n"
    assertEquals(typedUnit(dflt + d + "D satisfies Top:\n    fn top = 1\n").diags, Nil)
    val st = "cap Base:\n    Self.fn mk -> Self\ncap Top extends Base:\n    fn top -> u64\n" + d
    assertEquals(
      typedUnit(st + "D satisfies Top:\n    fn top = 1\n").diags.map(_.message),
      List("D does not satisfy Top: missing mk")
    )
    assertEquals(typedUnit(st + "D satisfies Top:\n    fn top = 1\n    Self.fn mk -> Self = D.E\n").diags, Nil)
    val own = "cap Z:\n    Self.fn zero -> Self\n    Self.fn one -> Self = Z.zero\n    fn m -> u64\n" + d
    assertEquals(typedUnit(own + "D satisfies Z:\n    E as zero\n    fn m = 1\n").diags, Nil)
    assertEquals(typedUnit(own + "D satisfies Z\n").diags.map(_.message), List("D does not satisfy Z: missing zero, m"))
    assertEquals(typedUnit(own + "D satisfies Z:\n    Self.fn zero -> Self = D.E\n    fn m = 1\n").diags, Nil)

  test("P6 D17: what derivation does not define"):
    val ab = "data AB:\n    A\n    B\n"
    assertEquals(
      typedUnit(ab + "AB satisfies Folding\n").diags.map(_.message),
      List("AB does not satisfy Folding: missing find, fold, foldRight")
    )
    assertEquals(
      typedUnit(ab + "cap K:\n    fn fold(x: u64) -> bool\nAB satisfies K\n").diags.map(_.message),
      List("AB does not satisfy K: missing fold")
    )
    val named = "cap Named:\n    fn name -> String\n"
    List(
      named + "data P(name: u64)\nP satisfies Named\n",
      named + "data P:\n    P(name: String)\n    Q(name: String)\nP satisfies Named\n"
    ).foreach(code =>
      assertEquals(typedUnit(code).diags.map(_.message), List("P does not satisfy Named: missing name"))
    )
    assertEquals(
      typedUnit("data F of A:\n    F(f: A -> u64)\nF satisfies Functor\n").diags.map(_.message),
      List("F does not satisfy Functor: missing map")
    )
    val h = "data H:\n    H(f: u64 -> u64)\n"
    assertEquals(typedUnit(h + "H satisfies Eq\n").diags.map(_.message), List("H does not satisfy Eq: missing eq"))
    assertEquals(
      typedUnit(h + "H satisfies Clone\n").diags.map(_.message),
      List("H does not satisfy Clone: missing clone")
    )
    assertEquals(
      typedUnit(h + "H satisfies Ord\n").diags.map(_.message),
      List("H does not satisfy Ord: missing compare, eq")
    )
    assertEquals(typedUnit(h + "H satisfies Show:\n    fn show = \"h\"\n").diags, Nil)
    assertEquals(
      typedUnit("data Pt:\n    Pt(x: u64)\nPt satisfies Eq\nPt satisfies Ord\nPt satisfies Clone\n").diags,
      Nil
    )

  test("Clone is derived on request: only a data type that declares `satisfies Clone` has it"):
    val p = "data P:\n    P(a: u64)\n"
    val bare = typedUnit(p + "fn f(x: P) -> P = x.clone()\n").diags
    assertEquals(bare.map(_.code), List(DiagCode.T010))
    assertEquals(bare.head.suggestion, Some("declare it: `P satisfies Clone`"))
    assertEquals(typedUnit(p + "P satisfies Clone\nfn f(x: P) -> P = x.clone()\n").diags, Nil)
    val need = "fn need(x: is Clone) -> () = ()\n"
    assertEquals(codes(typedUnit(p + need + "fn f(x: P) -> () = need(x)\n")), List(DiagCode.T010))
    assertEquals(typedUnit(p + need + "P satisfies Clone\nfn f(x: P) -> () = need(x)\n").diags, Nil)
    val box = typedUnit("data Box of A:\n    Box(v: A)\nfn f(x: Box of u64) -> Box of u64 = x.clone()\n").diags
    assertEquals(
      box.map(d => (d.message, d.suggestion)),
      List(("Box of u64 does not satisfy Clone", Some("declare it: `Box satisfies Clone`")))
    )
    val inP = Some("P inside it does not; declare `P satisfies Clone`")
    List(
      p + "fn f(x: List of List of P) -> List of List of P = x.clone()\n" -> inP,
      p + "fn f(x: (P, u64)) -> (P, u64) = x.clone()\n" -> inP,
      p + "data Box of A:\n    Box(v: A)\nBox satisfies Clone\nfn f(x: Box of P) -> Box of P = x.clone()\n" -> inP,
      "fn f(g: u64 -> u64) -> u64 -> u64 = g.clone()\n" -> None
    ).foreach((c, h) => assertEquals(typedUnit(c).diags.map(d => (d.code, d.suggestion)), List((DiagCode.T010, h)), c))
    assertEquals(
      typedUnit(p + "data Q:\n    Q(p: P)\nQ satisfies Clone\n").diags.map(_.message),
      List("Q does not satisfy Clone: missing clone")
    )
    assertEquals(typedUnit(p + "P satisfies Clone\ndata Q:\n    Q(p: P)\nQ satisfies Clone\n").diags, Nil)
    assertEquals(
      typedUnit(p +
        "fn f(x: P) -> String = x.show()\nfn need(x: is Eq) -> () = ()\nfn g(x: P) -> () = need(x)\n").diags,
      Nil
    )
    List(
      "fn f(xs: List of u64) -> List of u64 = xs.clone()\n",
      "fn f(x: u64) -> u64 = x.clone()\n",
      "fn f(x: (u64, String)) -> (u64, String) = x.clone()\n"
    ).foreach(code => assertEquals(typedUnit(code).diags, Nil, code))

  test("P6 D17: compiler-provided capabilities of primitive types need no definitions; others do"):
    assertEquals(
      typedUnit("cap Tw:\n    fn twice -> u64\nu64 satisfies Tw\n").diags.map(_.message),
      List("u64 does not satisfy Tw: missing twice")
    )
    assertEquals(typedUnit("cap Tw:\n    fn twice -> u64\nu64 satisfies Tw:\n    fn twice = self * 2\n").diags, Nil)
    assertEquals(typedUnit("u64 satisfies Show\nString satisfies Show\nString satisfies Ord\n").diags, Nil)
    assertEquals(
      typedUnit("cap Cc:\n    fn concat(other: Self) -> Self\nu64 satisfies Concat\n").diags.map(_.message),
      List("u64 does not satisfy Concat: missing concat")
    )
    assertEquals(
      typedUnit("char satisfies Boolean:\n    Self as yes\n    Self as no\n").diags.map(_.message),
      List("char does not satisfy Boolean: missing fold")
    )
    assertEquals(
      typedUnit("cap Tw:\n    fn twice -> u64\nString satisfies Tw\n").diags.map(_.message),
      List("String does not satisfy Tw: missing twice")
    )

  test("3.1.M3: a mapped variant's fields must fit the requirement's parameters"):
    assertEquals(
      codes(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Nothing as some\n    Just as none\n")),
      List(DiagCode.T010, DiagCode.T010)
    )
    assertEquals(
      codes(typedUnit(optionalCap + "Maybe satisfies Opt:\n    Jus as some\n    Nothing as none\n")),
      List(DiagCode.T001)
    )

  test("3.1.M3: a member inherited from two parents with different shapes is T013"):
    val caps = "cap A:\n    fn go(x: u64) -> u64\ncap B:\n    fn go -> u64\n"
    assertEquals(codes(typedUnit(caps + "cap C extends A + B\n")), List(DiagCode.T013))
    assertEquals(typedUnit(caps + "cap C extends A + B:\n    fn go -> u64\n").diags, Nil)

  test("P6 D17: the prelude's Order satisfies Ordering: compare results fold, Ordering.gt is Order.Greater"):
    val s = typedUnit(
      "fn lesser(a: u64, b: u64) -> bool = a.compare(b).fold(() -> true, () -> false, () -> false)\n" +
        "fn lo() -> is Ordering = Ordering.lt\nfn same() -> is Ordering = Ordering.eq\nfn hi() -> is Ordering = Ordering.gt\n"
    )
    assertEquals(s.diags, Nil)

    /** The prelude's own `satisfies` bodies resolve `Optional.some` and `none` to `Maybe`. */
    assertEquals(
      s.constructors.map((_, d, v) => s"${s.symbols(d).name}.$v").filterNot(Set("Maybe.Just", "Maybe.Nothing")).sorted,
      List("Order.Equal", "Order.Greater", "Order.Less")
    )

    /** A program's own Ordering satisfier now stands beside Order: an abstract result must name it. */
    val cmp = "data Cmp:\n    Lo\n    Mid\n    Hi\nCmp satisfies Ordering:\n    Lo as lt\n    Mid as eq\n    Hi as gt\n"
    assertEquals(codes(typedUnit(cmp + "fn f() -> is Ordering = Ordering.gt\n")), List(DiagCode.T011))
    val c = typedUnit(cmp + "fn g() -> Cmp = Ordering.gt\n")
    assertEquals(
      (c.diags, c.constructors.collect { case (_, d, v) if c.symbols(d).name != "Maybe" => v }),
      (Nil, List("Hi"))
    )

  test("P6 D17: Json's fields carry Show + Eq, and Json satisfies Show and Eq"):
    val typed = typedUnit(
      "fn s(j: Json) -> String = j.show()\nfn e(a: Json, b: Json) -> bool = a == b\n" +
        "fn f(j: Json) -> bool = match j:\n    Json.Array(es) => es.show() == \"\" && es == es\n" +
        "    Json.Object(fs) => fs.show() == \"\" && fs == fs\n    _ => false\n"
    )
    assertEquals(typed.diags, Nil)
    val json = Type.TypeRef(typed.symbols.scope("Json"), Nil)
    assertEquals(typed.symbols.satisfactionsOf(json).map(typed.symbols.render).toSet, Set("Show", "Eq"))

  test("Rule 7.4.c: no derived Functor where map cannot rebuild a field; the diagnostic names the field"):
    val w = "data W of A:\n    W(p: Tuple of (A, u64))\n"
    val why = Some("a derived map cannot rebuild field W.p: Tuple of (A, u64)")
    def one(code: String): (DiagCode, Int, Option[String]) =
      val d = typedUnit(code).diags
      assertEquals(d.size, 1, d)
      (d.head.code, d.head.span.start, d.head.suggestion)
    val call = w + "fn f(x: W of u64) -> W of u64 = x.map((v) -> v)\n"
    assertEquals(one(call), (DiagCode.T001, call.indexOf("x.map"), why))
    val decl = w + "W satisfies Functor\n"
    assertEquals(one(decl), (DiagCode.T010, decl.indexOf("W satisfies"), why))
    val pass = w + "fn g(xs: is Functor of u64) -> u64 = 0\nfn h(x: W of u64) -> u64 = g(x)\n"
    assertEquals(one(pass), (DiagCode.T010, pass.indexOf("g(x)") + 2, why))
    val m = "data M of (A, B):\n    Nil\n    X(a: A, r: M of (B, A))\nM satisfies Functor\n"
    assertEquals(
      one(m)._3,
      Some("a derived map cannot rebuild field X.r: M of (B, A)")
    )
    val bounded = "data Box of (K, A is Numeric):\n    Box(key: K, value: A)\nBox satisfies Functor\n"
    val boundedWhy = "map is not derived: the last type parameter of Box is bounded (A is Numeric), " +
      "and map must accept any result type"
    assertEquals(one(bounded)._3, Some(boundedWhy))
    val nested =
      "data Box of (A is Numeric):\n    Box(value: A)\ndata W of A:\n    W(b: Box of A)\nW satisfies Functor\n"

    /** `Box of A` is also rejected as written: `W` does not bound `A`. */
    assertEquals(
      typedUnit(nested).diags.map(d => (d.code, d.suggestion)),
      List(
        (DiagCode.T010, Some("a derived map cannot rebuild field W.b: Box of A")),
        (DiagCode.T010, None)
      )
    )

  test("3.1.M3: the stdlib's own satisfies declarations pass the checks"):
    val (table, _) = Namer.index(Parser.parse(SourceFile.fromString("<empty>", "")).tree)
    assertEquals(Declarations.check(TyperState(table), Stdlib.prelude).finish.diags, Nil)

  test("3.1.M4: satisfies method bodies are typed against the member they implement"):
    val base = optionalCap.replace("    Self.fn none -> Self\n", "    Self.fn none -> Self\n    fn present -> bool\n")
    val ok = typedUnit(
      base +
        "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n    fn present = match self:\n        Maybe.Just(_) => true\n        Maybe.Nothing => false\n"
    )
    assertEquals(ok.diags, Nil)
    val bad = typedUnit(base + "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n    fn present = 42\n")
    assertEquals(codes(bad), List(DiagCode.T002))
    val unknown = typedUnit(
      base +
        "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n    fn present = true\n    fn absent = true\n"
    )
    assertEquals(codes(unknown), List(DiagCode.T014))

  test("3.1.M4: cap default bodies see only what the capability promises"):
    val ok = typedUnit("cap Sized2:\n    fn size -> u64\n    fn empty -> bool = self.size == 0\n")
    assertEquals(ok.diags, Nil)
    val bad = typedUnit("cap Sized2:\n    fn size -> u64\n    fn empty -> bool = self.length == 0\n")
    assertEquals(codes(bad), List(DiagCode.T001))

  /** Constructor resolutions to the program's own data types. The prelude's `satisfies` bodies,
    * typed even where the program declares its own `Maybe`, add resolutions to the prelude's `Maybe`.
    */
  private def programConstructors(s: TyperState) =
    s.constructors.filter((_, d, _) => s.symbols(d).owner == SymbolId.Root)

  private val optSat = optionalCap + "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n"

  test("3.1.M5: Cap.ctor resolves through the expected type to the one satisfying constructor"):
    val s = typedUnit(optSat +
      "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\nfn direct(x: u64) -> Maybe of u64 = Opt.some(x)\n")
    assertEquals(s.diags, Nil)
    assertEquals(
      programConstructors(s).map((_, d, v) => s"${s.symbols(d).name}.$v").sorted,
      List("Maybe.Just", "Maybe.Just")
    )

  test("3.1.M5: refinement calls are stripped before resolving (spec 8.5.1)"):
    val s = typedUnit(optSat +
      "fn between(lo: N is Ord, hi: N) -> cap of N\nfn wrap(x: u64) -> is Opt of u64 + between(1, 99) = Opt.some(x)\n")
    assertEquals(s.diags, Nil)
    assertEquals(programConstructors(s).size, 1)

  test("3.1.M5: two satisfying constructors are ambiguous (T011); none in scope stays abstract"):
    val two = optSat +
      "data Box of A:\n    Full(value: A)\n    Hollow\nBox satisfies Opt:\n    Full as some\n    Hollow as none\n"
    assertEquals(codes(typedUnit(two + "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\n")), List(DiagCode.T011))
    val none = typedUnit(optionalCap + "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\n")
    assertEquals(none.diags, Nil)
    assertEquals(programConstructors(none), Nil)

  test("3.1.M5: a value built by a capability's constructor is not a type that lacks the capability"):
    val decls = "data Color:\n    Red\n    Green(n: u64)\nfn f(c: Color) -> u64 = 1\nfn h(n: u64) -> u64 = n\n" +
      "fn id(x: T) -> T = x\n"

    /** Each program passes an abstract constructor call, directly or through a variable, at `at`
      * (`o)` and `x)` find the argument of `f`).
      */
    val rejected = List(
      "fn g() -> u64 = f(Ordering.gt)\n" -> "Ordering.gt",
      "fn g() -> u64 = h(Ordering.lt)\n" -> "Ordering.lt",
      "fn g() -> u64 = f(id(Ordering.gt))\n" -> "id(Ordering.gt)",
      "fn g() -> u64 =\n    let o = Ordering.gt\n    f(o)\n" -> "o)",
      "fn g() -> Color = Optional.some(1)\n" -> "Optional.some(1)",
      "fn g(x: Ordering) -> u64 = f(x)\n" -> "x)"
    )
    rejected.foreach { (code, at) =>
      val ds = typedUnit(decls + code).diags
      assertEquals(ds.map(d => (d.code, d.span.start)), List((DiagCode.T010, (decls + code).lastIndexOf(at))), code)
    }
    assertEquals(typedUnit(decls + rejected.head._1).diags.map(_.message), List("Color does not satisfy Ordering"))

    /** The value meets a type that has the capability; the base compiler accepted these too. The
      * capability's arguments solve the literal's type before it defaults to `i64`.
      */
    List(
      "fn g() -> bool = Boolean.yes\n",
      "fn g() -> Maybe of u64 =\n    let o = Optional.some(7)\n    o\n",
      "fn g() -> (u64, u64) = Pair.pair(1, 2)\n",
      "fn o(x: is Ordering) -> u64 = 1\nfn g() -> u64 = o(Ordering.eq)\n"
    ).foreach(code => assertEquals(typedUnit(decls + code).diags, Nil, code))

  test("the integer literals of a list literal take the element type a bound asks for"):
    val code = "fn total(xs: is Folding of u64) -> u64 = xs.fold(0, (acc, x) -> acc + x)\n" +
      "fn g() -> u64 = total([1, 2, 3])\n"
    assertEquals(typedUnit(code).diags, Nil)

  test("Rule 8.1.a: trailing fields with defaults may be absent from the requirement"):
    val cap = "cap Queue:\n    Self.fn empty -> Self\n    fn size -> u64\n"
    val withDefault =
      cap +
        "data Simple(tasks: List of u64 = List.Nil)\nSimple satisfies Queue:\n    Simple as empty\n    fn size = 0\n"
    assertEquals(typedUnit(withDefault).diags, Nil)
    val resolved = typedUnit(withDefault + "fn make() -> Simple = Queue.empty\n")
    assertEquals(resolved.diags, Nil)
    assertEquals(
      resolved.constructors.collect { case (_, d, v) if resolved.symbols(d).name != "Maybe" => v },
      List("Simple")
    )
    val noDefault = cap +
      "data Simple(tasks: List of u64)\nSimple satisfies Queue:\n    Simple as empty\n    fn size = 0\n"
    assertEquals(codes(typedUnit(noDefault)), List(DiagCode.T010))

  /** `Sequencing`'s members that a `head`/`tail` product does not derive (its fields have other types). */
  private val seqBodies = "    fn length = 1\n    fn head = Optional.some(self.head)\n    fn tail = self\n"

  test("Rule 8.1.a: a field may refine an is-bound parameter; it must satisfy the bound"):
    val nel = "data Nel of A (head: A, tail: List of A)\n"
    assertEquals(
      typedUnit(nel + "Nel satisfies Sequencing:\n    Nel as cons\n    impossible as empty\n" + seqBodies).diags,
      Nil
    )
    val bad = "data Bad of A (head: A, tail: u64)\n"
    assertEquals(
      codes(typedUnit(bad +
        "Bad satisfies Sequencing:\n    Bad as cons\n    impossible as empty\n" + seqBodies)),
      List(DiagCode.T010)
    )

  test("Rule 8.1.a: a resolved requirement call is typed against the variant's fields"):
    val nel =
      "data Nel of A (head: A, tail: List of A)\nNel satisfies Sequencing:\n    Nel as cons\n    impossible as empty\n" +
        seqBodies
    assertEquals(
      typedUnit(nel + "fn grow(x: u64, xs: List of u64) -> Nel of u64 = Sequencing.cons(x, xs)\n").diags,
      Nil
    )
    assertEquals(
      codes(typedUnit(nel + "fn grow(x: u64, n: Nel of u64) -> Nel of u64 = Sequencing.cons(x, n)\n")),
      List(DiagCode.T002)
    )
