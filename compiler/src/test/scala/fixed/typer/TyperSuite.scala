package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Proto, SymbolId, render}
import fixed.parsing.{Parser, Scanner}
import fixed.util.{DiagCode, Reporter, SourceFile}

class TyperSuite extends munit.FunSuite:

  /** Index `decls`, then type `expr` against them; returns the finished state, the expression tree and its type. */
  private def typed(expr: String, decls: String = ""): (TyperState, Tree, String) =
    val unit = Parser.parse(SourceFile.fromString("<decls>", decls))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    val src = SourceFile.fromString("<expr>", expr)
    val rep = new Reporter(src)
    val tree = new Parser(new Scanner(src, rep), rep).parseExpr()
    assert(!rep.hasErrors, rep.formatAll)
    given Context = Context(Map.empty, table.scope, SymbolId.Root)
    val (s, _) = Typer.typedExpr(TyperState(table), tree, Proto.Wildcard)
    val done = s.finish
    (done, tree, done.symbols.render(done.typeOf(tree).get))

  private def codes(s: TyperState): List[DiagCode] = s.diags.map(_.code)

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
    assertEquals(typed("(1, \"a\", true)")._3, "(i64, String, bool)")
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

  /** Index and body-type a whole unit, as `TyperPhase` does. */
  private def typedUnit(code: String): TyperState =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    TyperPhase.typeBodies(unit.tree, table)

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

  test("M5: a bounded type variable's missing member is an error, not a gap"):
    assertEquals(codes(typedUnit("fn f(c: C is Empty) -> u64 = c.size\n")), List(DiagCode.T001))

  test("P6 D10: show, ==, != and clone on an unbounded type parameter are T022"):
    List(
      "fn f(x: T) -> String = x.show()\n",
      "fn f(x: T, y: T) -> bool = x == y\n",
      "fn f(x: T, y: T) -> bool = x != y\n",
      "fn f(x: T, y: T) -> bool = x.eq(y)\n",
      "fn f(x: T) -> T = x.clone()\n",
      "fn f(x: T is Numeric) -> String = x.show()\n",
      "fn f(x: is Folding of u64) -> String = x.show()\n",
      "fn f(x: is Folding of u64) -> bool = x == x\n",
      "fn f(x: is Folding of u64) -> is Folding of u64 = x.clone()\n",
      "fn f(xs: F of u64) -> String = xs.show()\n",
      "type Printable = Show + Eq\nfn f(x: T is Printable) -> T = x.clone()\n",
      "cap Named:\n    fn name -> String\n    fn label -> String = self.show()\n"
    ).foreach(code => assertEquals(codes(typedUnit(code)), List(DiagCode.T022), code))

  test("P6 D10: show, == and < follow the derivation into a container's type arguments; < needs Ord"):
    val box = "data Box of A:\n    B(a: A)\n"
    List(
      "fn f(xs: List of T) -> String = xs.show()\n" -> ("xs.show()", "`T is Show`"),
      "fn f(xs: List of T, ys: List of T) -> bool = xs == ys\n" -> ("xs == ys", "`T is Eq`"),
      "fn f(xs: List of T) -> String = ((z) -> z.show())(xs)\n" -> ("z.show()", "`T is Show`"),
      (box + "fn f(b: Box of T) -> String = b.show()\n") -> ("b.show()", "`T is Show`"),
      "fn f(p: (T, u64)) -> String = p.show()\n" -> ("p.show()", "`T is Show`"),
      "fn f(x: T, y: T) -> bool = x < y\n" -> ("x < y", "`T is Ord`"),
      "fn f(x: T is Eq, y: T) -> bool = x >= y\n" -> ("x >= y", "`T is Eq + Ord`"),
      "data P of (A, B):\n    P(b: B)\nfn f(p: P of (T, U)) -> String = p.show()\n" -> ("p.show()", "`U is Show`"),
      "fn f(x: T) -> String = ((z) -> z.show())(List.Cons(x, List.Nil))\n" -> ("z.show()", "`T is Show`")
    ).foreach { case (code, (at, hint)) =>
      val ds = typedUnit(code).diags
      assertEquals(ds.map(_.code), List(DiagCode.T022), code)
      assertEquals(ds.head.span.start, code.indexOf(at), code)
      assertEquals(ds.head.suggestion, Some(s"write the bound: $hint"), code)
    }
    assertEquals(
      typedUnit("fn f(xs: List of T) -> String = xs.show()\n").diags.head.message,
      "`.show` on a value of type List of T needs the bound `Show` on T"
    )

    /** No bound would help: `T010`, with no hint. */
    List(
      "data Fn:\n    F(f: u64 -> u64)\nfn f(x: Fn) -> String = x.show()\n" -> "Fn does not satisfy Show",
      "fn f(a: bool, b: bool) -> bool = a < b\n" -> "bool does not satisfy Ord",
      "fn f(g: T -> u64) -> String = g.show()\n" -> "T -> u64 does not satisfy Show",
      "data G of A:\n    G(f: u64 -> u64, a: A)\nfn f(g: G of T) -> String = g.show()\n" ->
        "G of T does not satisfy Show"
    ).foreach { (code, message) =>
      val ds = typedUnit(code).diags
      assertEquals(ds.map(d => (d.code, d.message, d.suggestion)), List((DiagCode.T010, message, None)), code)
    }
    val mutual = "data A:\n    A1(b: B)\n    A0\ndata B:\n    B1(a: A)\n"
    assertEquals(typedUnit(mutual + "fn f(x: A, y: A) -> bool = x == y && x < y\n").diags, Nil)
    List(
      "fn f(xs: List of (T is Show)) -> String = xs.show()\n",
      "fn f(xs: List of (T is Eq), ys: List of T) -> bool = xs == ys\n",
      box + "fn f(b: Box of (T is Show)) -> String = b.show()\n",
      box + "fn f(b: Box of u64) -> String = b.show()\n",
      "fn f(p: (T is Show, u64)) -> String = p.show()\n",
      "fn f(x: T is Ord, y: T) -> bool = x < y\n",
      "fn f(xs: List of List of u64, ys: List of List of u64) -> bool = xs == ys\n",
      "fn f(x: u64, c: char, s: String) -> bool = x < 3 && c <= 'a' && c == 'b' && s > \"b\"\n",
      "fn f(p: (T is Show, u64, u64)) -> String = p.show()\n"
    ).foreach(code => assertEquals(typedUnit(code).diags, Nil, code))

  test("P6 D10: a receiver whose type is solved to a type parameter later is checked when the unit finishes"):
    List(
      "fn f(x: T) -> String = ((z) -> z.show())(x)\n",
      "fn f(x: T, y: T) -> bool = ((a, b) -> a == b)(x, y)\n",
      "fn app(f: A -> String, x: A) -> String = f(x)\nfn g(x: T) -> String = app((y) -> y.show(), x)\n"
    ).foreach(code => assertEquals(codes(typedUnit(code)), List(DiagCode.T022), code))
    assertEquals(typedUnit("fn f(x: T is Show) -> String = ((z) -> z.show())(x)\n").diags, Nil)
    assertEquals(typedUnit("fn f(x: u64) -> String = ((z) -> z.show())(x)\n").diags, Nil)

  test("P6 D10: a satisfies body is typed against the data type's own parameters, unbounded"):
    val decls = "cap Desc:\n    fn describe -> String\ndata Box of A:\n    B(value: A)\n"
    val body = "Box satisfies Desc:\n    fn describe = match self:\n        Box.B(v) => v.show()\n"
    assertEquals(codes(typedUnit(decls + body)), List(DiagCode.T022))
    assertEquals(typedUnit(decls + body).diags.head.suggestion, Some("write the bound: `data Box of (A is Show)`"))
    val fold = "cap Folder:\n    fn fold(init: R, f: (R, u64) -> R) -> R\ndata Nums:\n    Nums(n: u64)\n" +
      "Nums satisfies Folder:\n    fn fold(init: R, f: (R, u64) -> R) = init.clone()\n"
    assertEquals(
      typedUnit(fold).diags.map(_.suggestion),
      List(Some("write the bound: `R is Clone` in the signature of `Folder.fold`"))
    )
    val bounded = decls.replace("data Box of A:", "data Box of (A is Show):")
    assertEquals(typedUnit(bounded + body).diags, Nil)

  test("P6 D10: the T022 message names the missing bound and the hint writes it out"):
    val d = typedUnit("fn f(x: T is Numeric) -> String = x.show()\n").diags.head
    assert(d.message.contains("`Show`"), d.message)
    assertEquals(d.suggestion, Some("write the bound: `T is Numeric + Show`"))
    val eq = typedUnit("fn f(x: T, y: T) -> bool = x == y\n").diags.head
    assertEquals(eq.suggestion, Some("write the bound: `T is Eq`"))
    val anon = typedUnit("fn f(x: is Folding of u64) -> String = x.show()\n").diags.head
    assertEquals(anon.suggestion, Some("write the bound: `is Folding of u64 + Show`"))
    val ne = typedUnit("fn f(x: T, y: T) -> bool = x != y\n").diags.head
    assertEquals(ne.message, "`!=` on a value of type T needs the bound `Eq`")
    val dotEq = typedUnit("fn f(x: T, y: T) -> bool = x.eq(y)\n").diags.head
    assertEquals(dotEq.message, "`.eq` on a value of type T needs the bound `Eq`")
    val clone = typedUnit("fn f(x: T) -> T = x.clone()\n").diags.head
    assertEquals(clone.message, "`.clone` on a value of type T needs the bound `Clone`")
    assertEquals(clone.suggestion, Some("write the bound: `T is Clone`"))
    val alias = typedUnit("type NC = Numeric + Concat\nfn f(x: T is NC) -> String = x.show()\n").diags.head
    assertEquals(alias.suggestion, Some("write the bound: `T is NC + Show`"))
    val inCap = typedUnit("cap Named extends Eq:\n    fn label -> String = self.show()\n").diags.head
    assertEquals(inCap.suggestion, Some("write the bound: `cap Named extends Eq + Show`"))

  test("P6 D10: bounded parameters and concrete types keep show, == and clone"):
    List(
      "fn f(x: T is Show) -> String = x.show()\n",
      "fn f(x: T is Eq, y: T) -> bool = x == y\n",
      "fn f(x: T is Eq, y: T) -> bool = x != y\n",
      "fn f(x: T is Ord, y: T) -> bool = x == y\n",
      "fn f(x: T is Numeric, y: T) -> bool = x != y\n",
      "fn f(x: T is Clone) -> T = x.clone()\n",
      "fn f(x: T is Eq, y: T) -> bool = x.eq(y)\n",
      "cap Pretty extends Show\nfn f(x: T is Pretty) -> String = x.show()\n",
      "cap Pretty extends Eq\nfn f(x: T is Pretty, y: T) -> bool = x == y\n",
      "type Printable = Show + Eq\nfn f(x: T is Printable, y: T) -> bool = x != y\n",
      "type Printable = Show + Eq\nfn f(x: T is Printable) -> String = x.show()\n",
      "fn f(x: is Show) -> String = x.show()\n",
      "cap Pretty:\n    fn show -> String\nfn f(x: T is Pretty) -> String = x.show()\n",
      "cap Named extends Show:\n    fn label -> String = self.show()\n",
      "fn f(x: is Folding of u64 + Show) -> String = x.show()\n",
      "fn f(x: u64, y: u64) -> bool = x == y\n",
      "fn f(x: String) -> String = x.clone()\n",
      "fn f(xs: List of u64) -> String = xs.show()\n",
      "data P(a: u64)\nfn f(x: P, y: P) -> bool = x == y\n"
    ).foreach(code => assertEquals(typedUnit(code).diags, Nil, code))

  test("spec 5.1: each anonymous bound is its own type variable; a named one is shared"):
    val ifAnon = "fn f(c: bool, a: is Show, b: is Show) -> u64 =\n    let x = if c: a else: b\n    0\n"
    assertEquals(codes(typedUnit(ifAnon)), List(DiagCode.T002))
    assertEquals(typedUnit("fn f(c: bool, a: T is Show, b: T) -> T = if c: a else: b\n").diags, Nil)
    val two = "fn two(a: is Show, b: is Show) -> String = a.show().concat(b.show())\n"
    assertEquals(typedUnit(two + "fn g(n: u64) -> String = two(n, \"x\")\n").diags, Nil)
    assertEquals(
      codes(typedUnit(two + "data H:\n    H(f: u64 -> u64)\nfn g(h: H) -> String = two(1, h)\n")),
      List(DiagCode.T010)
    )
    val pretty = "cap Pretty extends Show\nfn p(x: is Pretty) -> String = x.show()\n"
    assertEquals(codes(typedUnit(pretty + "fn g() -> String = p(1)\n")), List(DiagCode.T010))
    val alias =
      "type Printable = Show + Eq\nfn r(x: is Printable, y: is Printable) -> String = x.show()\ndata H:\n    H(f: u64 -> u64)\n"
    assertEquals(typedUnit(alias + "fn g() -> String = r(1, \"x\")\n").diags, Nil)
    assertEquals(codes(typedUnit(alias + "fn g(h: H) -> String = r(1, h)\n")), List(DiagCode.T010, DiagCode.T010))

  test("Rule 5.1.1: a named bound or a bounded of parameter is checked where the declaration is used"):
    val decls = "cap Named:\n    fn name -> String\ndata Box:\n    B(n: u64)\n" +
      "fn greet(x: T is Named) -> String = x.name\n" +
      "data Tag of (A is Named):\n    Tag(a: A)\n    None\neffect Log of (A is Named):\n    fn log(a: A) -> ()\n" +
      "cap Coll of (A is Named):\n    Self.fn mk(a: A) -> Self\n    fn count -> u64\nTag satisfies Coll:\n" +
      "    Tag as mk\n    fn count = 1\n" +
      "cap Greeter:\n    fn hello(x: T is Named) -> String\nBox satisfies Greeter:\n    fn hello(x: T is Named) = x.name\n"

    /** Each call passes a `Box` where the bound asks for `Named`: rejected without a satisfaction,
      * accepted with one. The diagnostic sits at the call (`at`).
      */
    val boxCalls = List(
      "fn g(b: Box) -> String = greet(b)\n" -> "greet(b)",
      "fn g(b: Box) -> String = b |> greet\n" -> "b |> greet",
      "fn g(b: Box) -> String = ((y) -> greet(y))(b)\n" -> "((y) -> greet(y))(b)",
      "fn g(b: Box) -> String = b.hello(b)\n" -> "b.hello(b)",
      "fn g(b: Box) -> Tag of Box = Tag.Tag(b)\n" -> "Tag.Tag(b)",
      "fn g(b: Box) -> () with Log of Box = Log.log(b)\n" -> "Log.log(b)",
      "fn g(b: Box) -> u64 = Coll.mk(b).count\n" -> "Coll.mk(b)",
      "fn need(x: is Coll of Box) -> u64 = x.count\nfn g() -> u64 = need(Tag.None)\n" -> "need(Tag.None)"
    )

    /** Rejected whatever `Box` satisfies: a literal defaults to `i64`, an unbounded `T` has no bound. */
    val otherCalls = List(
      "fn g() -> String = greet(5)\n" -> "greet(5)",
      "fn g(x: T) -> String = greet(x)\n" -> "greet(x)"
    )
    (boxCalls ++ otherCalls).foreach { (call, at) =>
      val ds = typedUnit(decls + call).diags
      assertEquals(ds.map(_.code), List(DiagCode.T010), call)
      val site = (decls + call).indexOf(at)
      assert(ds.head.span.start >= site && ds.head.span.end <= site + at.length, s"$call: ${ds.head.span}")
    }
    val named = decls + "Box satisfies Named:\n    fn name = \"box\"\n"
    boxCalls.foreach((call, _) => assertEquals(typedUnit(named + call).diags, Nil, call))
    assertEquals(typedUnit(decls + "fn g(x: T is Named) -> String = greet(x)\n").diags, Nil)

  test("Rule 5.1.1: a literal meeting a named bound is checked once; a bounded argument not solved yet is Clone"):
    val nn = "cap Named:\n    fn name -> String\nfn nn(x: T is Numeric + Named) -> T = x\n" +
      "fn num(x: U is Numeric) -> U = x\nfn g() -> u8 = nn(num(5))\n"
    assertEquals(codes(typedUnit(nn)), List(DiagCode.T010))
    val clone = "cap Named:\n    fn name -> String\ndata Mb of (A is Named):\n    Just(value: A)\n    Nothing\n" +
      "Mb satisfies Clone\nfn need(x: is Clone) -> () = ()\nfn f() -> () = need(Mb.Nothing)\n"
    assertEquals(typedUnit(clone).diags, Nil)

  test("spec 5.1: a named type inside two anonymous bounds is shared"):
    val q = "cap Quantity of U:\n    Self.fn new(value: f64) -> Self\n    fn value -> f64\ncap Meters\ncap Seconds\n" +
      "fn add(a: is Quantity of U, b: is Quantity of U) -> is Quantity of U = Quantity.new(a.value + b.value)\n"
    val same = "fn g(m: is Quantity of Meters, n: is Quantity of Meters) -> is Quantity of Meters = add(m, n)\n"
    assertEquals(typedUnit(q + same).diags, Nil)
    val mixed = "fn g(m: is Quantity of Meters, s: is Quantity of Seconds) -> is Quantity of Meters = add(m, s)\n"
    assertEquals(codes(typedUnit(q + mixed)), List(DiagCode.T010))

  test("3.1.M2: values flowing into `is` bounds must satisfy them (T010)"):
    val sum = "fn total(xs: is Folding of (N is Numeric)) -> N = xs.fold(0, (a, x) -> a + x)\n"
    assertEquals(typedUnit(sum + "fn ok(xs: List of u64) -> u64 = total(xs)\n").diags, Nil)
    assertEquals(codes(typedUnit(sum + "fn bad(s: String) -> u64 = total(s)\n")), List(DiagCode.T010))
    val show = "fn render(x: is Show) -> String = x.show()\ndata Fn1:\n    Fn1(f: u64 -> u64)\n"
    assertEquals(typedUnit(show + "fn ok(n: u64) -> String = render(n)\n").diags, Nil)
    assertEquals(codes(typedUnit(show + "fn bad(f: Fn1) -> String = render(f)\n")), List(DiagCode.T010))

  test("3.1.M2: capability arguments are checked; bounded variables keep their own bounds"):
    val decls = "fn firstU64(xs: is Folding of u64) -> u64 = xs.fold(0, (a, x) -> x)\n"
    assertEquals(codes(typedUnit(decls + "fn bad(xs: List of String) -> u64 = firstU64(xs)\n")), List(DiagCode.T010))
    val chain =
      "fn make() -> R is Folding + Functor = make()\nfn needFolding(x: is Folding) -> () = ()\nfn needFunctor(x: is Functor) -> () = ()\n"
    assertEquals(
      typedUnit(chain + "fn useBoth() -> () =\n    let x = make()\n    needFunctor(x)\n    needFolding(x)\n").diags,
      Nil
    )

  test("3.1.M2: a type variable passed where its bound does not reach the capability is T010"):
    assertEquals(
      codes(typedUnit("fn needNum(x: is Numeric) -> () = ()\nfn bad(c: C is Show) -> () = needNum(c)\n")),
      List(DiagCode.T010)
    )
    assertEquals(
      typedUnit(
        "cap Ring extends Numeric\nfn needNum(x: is Numeric) -> () = ()\nfn ok(c: C is Ring) -> () = needNum(c)\n"
      ).diags,
      Nil
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
    List(
      p + "fn f(x: List of P) -> List of P = x.clone()\n",
      p + "fn f(x: (P, u64)) -> (P, u64) = x.clone()\n",
      p + "data Box of A:\n    Box(v: A)\nBox satisfies Clone\nfn f(x: Box of P) -> Box of P = x.clone()\n",
      "fn f(g: u64 -> u64) -> u64 -> u64 = g.clone()\n"
    ).foreach(code => assertEquals(codes(typedUnit(code)), List(DiagCode.T010), code))
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
    assertEquals(
      s.constructors.map((_, d, v) => s"${s.symbols(d).name}.$v").sorted,
      List("Order.Equal", "Order.Greater", "Order.Less")
    )

    /** A program's own Ordering satisfier now stands beside Order: an abstract result must name it. */
    val cmp = "data Cmp:\n    Lo\n    Mid\n    Hi\nCmp satisfies Ordering:\n    Lo as lt\n    Mid as eq\n    Hi as gt\n"
    assertEquals(codes(typedUnit(cmp + "fn f() -> is Ordering = Ordering.gt\n")), List(DiagCode.T011))
    val c = typedUnit(cmp + "fn g() -> Cmp = Ordering.gt\n")
    assertEquals((c.diags, c.constructors.map((_, _, v) => v)), (Nil, List("Hi")))

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

  private val optSat = optionalCap + "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n"

  test("3.1.M5: Cap.ctor resolves through the expected type to the one satisfying constructor"):
    val s = typedUnit(optSat +
      "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\nfn direct(x: u64) -> Maybe of u64 = Opt.some(x)\n")
    assertEquals(s.diags, Nil)
    assertEquals(s.constructors.map((_, d, v) => s"${s.symbols(d).name}.$v").sorted, List("Maybe.Just", "Maybe.Just"))

  test("3.1.M5: refinement calls are stripped before resolving (spec 8.5.1)"):
    val s = typedUnit(optSat +
      "fn between(lo: N is Ord, hi: N) -> cap of N\nfn wrap(x: u64) -> is Opt of u64 + between(1, 99) = Opt.some(x)\n")
    assertEquals(s.diags, Nil)
    assertEquals(s.constructors.size, 1)

  test("3.1.M5: two satisfying constructors are ambiguous (T011); none in scope stays abstract"):
    val two = optSat +
      "data Box of A:\n    Full(value: A)\n    Hollow\nBox satisfies Opt:\n    Full as some\n    Hollow as none\n"
    assertEquals(codes(typedUnit(two + "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\n")), List(DiagCode.T011))
    val none = typedUnit(optionalCap + "fn wrap(x: u64) -> is Opt of u64 = Opt.some(x)\n")
    assertEquals(none.diags, Nil)
    assertEquals(none.constructors, Nil)

  test("Rule 8.1.a: trailing fields with defaults may be absent from the requirement"):
    val cap = "cap Queue:\n    Self.fn empty -> Self\n    fn size -> u64\n"
    val withDefault =
      cap +
        "data Simple(tasks: List of u64 = List.Nil)\nSimple satisfies Queue:\n    Simple as empty\n    fn size = 0\n"
    assertEquals(typedUnit(withDefault).diags, Nil)
    val resolved = typedUnit(withDefault + "fn make() -> Simple = Queue.empty\n")
    assertEquals(resolved.diags, Nil)
    assertEquals(resolved.constructors.map((_, _, v) => v), List("Simple"))
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
