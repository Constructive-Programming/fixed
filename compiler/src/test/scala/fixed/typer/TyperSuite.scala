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

  test("M4: if/else branches agree; the condition is bool"):
    assertEquals(typedUnit("fn f(x: u64) -> u64 =\n    if x > 1: x else: 0\n").diags, Nil)
    assertEquals(codes(typedUnit("fn f(x: u64) -> u64 =\n    if x: x else: 0\n")), List(DiagCode.T002))

  test("M4: members through satisfies, fields of single-variant data, and effect operations"):
    val s = typedUnit(
      "use std.io.Console\nfn f(n: u64, p: Tuple of (u64, String)) -> () with Console =\n    Console.print_line(n.show().concat(p.second))\n"
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
      "use std.io.Console\nfn greet(name: String) -> () with Console =\n    Console.print_line(\"Hi \".concat(name))\nfn main() -> () with Console =\n    greet(42)\n"
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
    val eff = "effect Ask:\n    fn ask(prompt: String) -> u64\nfn ignore_len(s: String) -> u64 = 0\n"
    val ok = typedUnit(
      eff +
        "fn run(f: () -> u64 with Ask) -> u64 =\n    handle f():\n        Ask.ask(p) => resume(p.show().concat(\"!\") |> ignore_len)\n        return(v) => v + 1\n"
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
      "cap Box of A:\n    Self.fn wrap(value: A) -> Self\n    fn flat_map(f: A -> Self of B) -> Self of B\n" +
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
    val decls = "fn first_u64(xs: is Folding of u64) -> u64 = xs.fold(0, (a, x) -> x)\n"
    assertEquals(codes(typedUnit(decls + "fn bad(xs: List of String) -> u64 = first_u64(xs)\n")), List(DiagCode.T010))
    val chain =
      "fn make() -> R is Folding + Functor = make()\nfn need_folding(x: is Folding) -> () = ()\nfn need_functor(x: is Functor) -> () = ()\n"
    assertEquals(
      typedUnit(chain + "fn use_both() -> () =\n    let x = make()\n    need_functor(x)\n    need_folding(x)\n").diags,
      Nil
    )

  test("3.1.M2: a type variable passed where its bound does not reach the capability is T010"):
    assertEquals(
      codes(typedUnit("fn need_num(x: is Numeric) -> () = ()\nfn bad(c: C is Show) -> () = need_num(c)\n")),
      List(DiagCode.T010)
    )
    assertEquals(
      typedUnit(
        "cap Ring extends Numeric\nfn need_num(x: is Numeric) -> () = ()\nfn ok(c: C is Ring) -> () = need_num(c)\n"
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
    val unknown =
      typedUnit(base + "Maybe satisfies Opt:\n    Just as some\n    Nothing as none\n    fn absent = true\n")
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
    val withDefault = cap + "data Simple(tasks: List of u64 = List.Nil)\nSimple satisfies Queue:\n    Simple as empty\n"
    assertEquals(typedUnit(withDefault).diags, Nil)
    val resolved = typedUnit(withDefault + "fn make() -> Simple = Queue.empty\n")
    assertEquals(resolved.diags, Nil)
    assertEquals(resolved.constructors.map((_, _, v) => v), List("Simple"))
    val noDefault = cap + "data Simple(tasks: List of u64)\nSimple satisfies Queue:\n    Simple as empty\n"
    assertEquals(codes(typedUnit(noDefault)), List(DiagCode.T010))

  test("Rule 8.1.a: a field may refine an is-bound parameter; it must satisfy the bound"):
    val nel = "data Nel of A (head: A, tail: List of A)\n"
    assertEquals(typedUnit(nel + "Nel satisfies Sequencing:\n    Nel as cons\n    impossible as empty\n").diags, Nil)
    val bad = "data Bad of A (head: A, tail: u64)\n"
    assertEquals(
      codes(typedUnit(bad + "Bad satisfies Sequencing:\n    Bad as cons\n    impossible as empty\n")),
      List(DiagCode.T010)
    )

  test("Rule 8.1.a: a resolved requirement call is typed against the variant's fields"):
    val nel =
      "data Nel of A (head: A, tail: List of A)\nNel satisfies Sequencing:\n    Nel as cons\n    impossible as empty\n"
    assertEquals(
      typedUnit(nel + "fn grow(x: u64, xs: List of u64) -> Nel of u64 = Sequencing.cons(x, xs)\n").diags,
      Nil
    )
    assertEquals(
      codes(typedUnit(nel + "fn grow(x: u64, n: Nel of u64) -> Nel of u64 = Sequencing.cons(x, n)\n")),
      List(DiagCode.T002)
    )
