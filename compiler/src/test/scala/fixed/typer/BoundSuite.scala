package fixed.typer

import fixed.util.DiagCode

/** Capability bounds: a value of a type parameter has the members of its bounds and no others,
  * so `show`, `==`, `<` and `clone` need a bound (spec Rule 5.1.3, P6 D10, `T022`); anonymous and
  * named bounds (spec 5.1, Rule 5.1.1); and values flowing into `is` bounds (3.1.M2, `T010`).
  */
class BoundSuite extends munit.FunSuite, TyperTestSupport:

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
      "fn f(xs: List of T) -> List of T = xs.clone()\n" -> ("xs.clone()", "`T is Clone`"),
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
      "fn f(x: u64, c: char, s: String) -> bool = x < 3 && c <= 'a' && c == 'b' && s > \"b\"\n"
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

    /** A program that writes `Tag of Box`, `Log of Box` or `Coll of Box` is also rejected there. */
    (boxCalls ++ otherCalls).foreach { (call, at) =>
      val ds = typedUnit(decls + call).diags
      val site = (decls + call).indexOf(at)
      val (atCall, elsewhere) = ds.partition(d => d.span.start >= site && d.span.end <= site + at.length)
      assertEquals(atCall.map(_.code), List(DiagCode.T010), call)
      val written = List("Tag of Box", "Log of Box", "Coll of Box").find(call.contains)
      assertEquals(
        elsewhere.map(d => (d.code, d.span.start)),
        written.map(w => (DiagCode.T010, decls.length + call.indexOf(w))).toList,
        call
      )
    }
    val named = decls + "Box satisfies Named:\n    fn name = \"box\"\n"
    boxCalls.foreach((call, _) => assertEquals(typedUnit(named + call).diags, Nil, call))
    assertEquals(typedUnit(decls + "fn g(x: T is Named) -> String = greet(x)\n").diags, Nil)

  test("Rule 5.1.1: the arguments of every written type are checked against the bounds of its parameters"):
    val decls = "cap Named:\n    fn name -> String\ndata Box:\n    B(n: u64)\n" +
      "data Tag of (A is Named):\n    Tag(a: A)\neffect Log of (A is Named):\n    fn log(a: A) -> ()\n" +
      "data Num of (N is Numeric):\n    Num(n: N)\n"

    /** Each program writes one type whose argument lacks the parameter's bound, at `at`. */
    val written = List(
      "fn g(t: Tag of Box) -> u64 = 1\n" -> "Tag of Box",
      "fn g() -> Tag of Box = g()\n" -> "Tag of Box",
      "fn g() -> u64 with Log of Box = 1\n" -> "Log of Box",
      "data W of A:\n    W(b: Num of A)\n" -> "Num of A",
      "fn g(t: T) -> Num of T = g(t)\n" -> "Num of T",
      "fn g() -> u64 =\n    let f = (t: Tag of Box) -> 1\n    1\n" -> "Tag of Box",
      "fn g() -> u64 =\n    fn h(t: Tag of Box) -> u64 = 1\n    1\n" -> "Tag of Box",
      "fn g(x: is Sized of (u8, String)) -> u64 = 1\n" -> "Sized of (u8, String)",
      "Box satisfies Sized of (u8, String):\n    fn size = 1\n" -> "Sized of (u8, String)",
      "fn g(t: List of Tag of Box) -> u64 = 1\n" -> "Tag of Box",
      "type SS = Sized + Show\nfn g(x: is SS of (u8, String)) -> u64 = 1\n" -> "SS of (u8, String)",
      "data V of (n: u64, A is Named):\n    V(a: A)\nfn g(v: V of (3, Box)) -> u64 = 1\n" -> "V of (3, Box)",
      "data R of (lo: u64, N is Numeric):\n    R(n: N)\ndata O of (k: u64, M):\n    O(r: R of (k, M))\n" ->
        "R of (k, M)"
    )
    written.foreach { (code, at) =>
      val ds = typedUnit(decls + code).diags.filter(_.isError)
      assertEquals(ds.map(d => (d.code, d.span.start)), List((DiagCode.T010, (decls + code).lastIndexOf(at))), code)
    }
    assert(typedUnit(decls + written.head._1).diags.exists(
      _.message == "Tag of Box: Box does not satisfy Named, the bound of Tag's parameter A"
    ))

    /** The same types, written at arguments that satisfy the bounds. */
    val named = decls + "Box satisfies Named:\n    fn name = \"box\"\n"
    List(
      "fn g(t: Tag of Box) -> Tag of Box = t\n",
      "fn g(b: Box) -> () with Log of Box = Log.log(b)\n",
      "data W of (A is Numeric + Show):\n    W(b: Num of A)\n",
      "fn g(t: T is Numeric) -> Num of T = Num.Num(t)\n",
      "fn g() -> u64 =\n    let f = (t: Tag of Box) -> 1\n    1\n",
      "fn g(x: is Sized of (u8, u64)) -> u64 = 1\n",
      "fn g(t: List of Tag of Box) -> List of Tag of Box = t\n",
      "type SS = Sized + Show\nfn g(x: is SS of (u8, u64)) -> u64 = 1\n",
      "data V of (n: u64, A is Named):\n    V(a: A)\nfn g(v: V of (3, Box)) -> u64 = 1\n",
      "data R of (lo: u64, N is Numeric):\n    R(n: N)\ndata O of (k: u64, M is Numeric):\n    O(r: R of (k, M))\n",
      "fn f(u8: u64, x: u8) -> u8 = x\nfn g() -> u8 = f(1, 2)\n",
      "data P of (A, u8: u64 = 0):\n    P(a: A, b: u8)\nfn g() -> P of u64 = P.P(1, 2)\n"
    ).foreach(code => assertEquals(typedUnit(named + code).diags.filter(_.isError), Nil, code))

  test("Rule 5.1.1: a literal meeting a named bound is checked once; a bounded argument not solved yet is Clone"):
    val nn = "cap Named:\n    fn name -> String\nfn nn(x: T is Numeric + Named) -> T = x\n" +
      "fn num(x: U is Numeric) -> U = x\nfn g() -> u8 = nn(num(5))\n"
    assertEquals(codes(typedUnit(nn)), List(DiagCode.T010))
    val clone = "cap Named:\n    fn name -> String\ndata Mb of (A is Named):\n    Just(value: A)\n    Nothing\n" +
      "Mb satisfies Clone\nfn need(x: is Clone) -> () = ()\nfn f() -> () = need(Mb.Nothing)\n"
    assertEquals(typedUnit(clone).diags, Nil)

  test("spec 5.1: anonymous bounds in member parameters are distinct type parameters"):
    /** `if c: a else: rhs` needs `a` and `rhs` to have one type. Each program types it in another
      * kind of body: a capability's default method, a satisfaction's method (typed against the
      * capability's signature) and a capability's default static method.
      */
    val pick = "if c: a else: rhs"
    def programs(params: String) = List(
      s"cap Picker:\n    fn pick(c: bool, $params) -> u64 =\n        let x = $pick\n        0\n",
      s"cap Picker:\n    fn pick(c: bool, $params) -> u64\ndata P:\n    P(n: u64)\n" +
        s"P satisfies Picker:\n    fn pick(c: bool, $params) =\n        let x = $pick\n        0\n",
      s"cap Picker:\n    Self.fn pick(c: bool, $params) -> u64 =\n        let x = $pick\n        0\n"
    )
    programs("a: is Show, rhs: is Show").foreach { code =>
      val ds = typedUnit(code).diags
      assertEquals(ds.map(_.code), List(DiagCode.T002), code)
      assertEquals(ds.head.span.start, code.indexOf(pick) + pick.indexOf("rhs"), code)
      assertEquals(
        ds.head.message,
        "expected the type of `a` of `Picker.pick` (is Show), got the type of `rhs` of `Picker.pick` (is Show)",
        code
      )
    }
    programs("a: T is Show, rhs: T").foreach(code => assertEquals(typedUnit(code).diags, Nil, code))

    /** Not yet: a nested `fn` and a bound inside a function-typed parameter keep the bound as written. */
    val unchanged = List(
      s"fn outer() -> u64 =\n    fn inner(c: bool, a: is Show, rhs: is Show) -> u64 =\n        let x = $pick\n        0\n    0\n",
      s"fn hof(c: bool, a: (is Show) -> u64, rhs: (is Show) -> u64) -> u64 =\n    let x = $pick\n    0\n"
    )
    unchanged.foreach(code => assertEquals(typedUnit(code).diags, Nil, code))

  test("spec 5.1: two anonymous bounds that print the same are named by their parameters in a mismatch"):
    val messages = List(
      "fn f(c: bool, a: is Show, b: is Show) -> u64 =\n    let x = if c: a else: b\n    0\n" ->
        "expected the type of `a` (is Show), got the type of `b` (is Show)",
      "fn f(c: bool, a: List of is Show, b: List of is Show) -> u64 =\n    let x = if c: a else: b\n    0\n" ->
        "expected List of the `is Show` in the type of `a`, got List of the `is Show` in the type of `b`",
      "fn f(c: bool, a: (is Show, is Show)) -> u64 =\n    match a:\n        (p, q) => if c: p else: q\n    0\n" ->
        "expected the `is Show #1` in the type of `a`, got the `is Show #2` in the type of `a`"
    )
    messages.foreach((code, message) => assertEquals(typedUnit(code).diags.map(_.message), List(message), code))

  test("spec 5.1: a member's bound that names Self, Part or the capability's parameter is read at the satisfying type"):
    val merge =
      "cap Merge:\n    fn merge(o: is Folding of Self) -> Self\ndata M1:\n    M1(n: u64)\ndata M2:\n    M2(s: String)\n" +
        "M2 satisfies Merge:\n    fn merge(o: is Folding of Self) = self\n" +
        "fn firstOr(d: M1, ys: is Folding of M1) -> M1 = ys.fold(d, (acc, x) -> x)\n"
    def m1(body: String) = merge + s"M1 satisfies Merge:\n    fn merge(o: is Folding of Self) =\n        $body\n"
    List(
      "cap Coll of E:\n    fn addAll(xs: is Folding of E) -> Self\ndata Bag of E:\n    Bag(x: E)\n" +
        "Bag satisfies Coll:\n    fn addAll(xs: is Folding of E) = xs.fold(self, (acc, x) -> Bag.Bag(x))\n",
      "cap Coll of E:\n    fn addAll(xs: is Folding of E) -> u64\nfn total(ys: is Folding of u64) -> u64 = 0\n" +
        "data Bag:\n    Bag(n: u64)\nBag satisfies Coll of u64:\n    fn addAll(xs: is Folding of u64) = total(xs)\n",
      "cap Grow:\n    fn grow(xs: is Folding of Part) -> Self\ndata Box of A:\n    Box(x: A)\n" +
        "Box satisfies Grow:\n    fn grow(xs: is Folding of Part) = xs.fold(self, (acc, x) -> Box.Box(x))\n",
      m1("o.fold(self, (acc, x) -> x)"),
      m1("firstOr(self, o)"),
      m1("M2.M2(\"a\").merge(List.Cons(M2.M2(\"b\"), List.Nil))\n        self"),
      "cap Merge:\n    fn merge(o: T is Folding of Self) -> Self\ndata M:\n    M(n: u64)\n" +
        "M satisfies Merge:\n    fn merge(o: T is Folding of Self) = o.fold(self, (acc, x) -> x)\n"
    ).foreach(code => assertEquals(typedUnit(code).diags, Nil, code))
    val wrong = m1("let bad = M2.M2(\"a\").merge(List.Cons(M1.M1(1), List.Nil))\n        self")
    val ds = typedUnit(wrong).diags
    assertEquals(ds.map(_.code), List(DiagCode.T010), wrong)
    assertEquals(ds.head.message, "List of M1 does not satisfy Folding of M2")

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
