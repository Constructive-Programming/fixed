package fixed.typer

import fixed.util.{DiagCode, Diagnostic, SourceFile}
import fixed.{CompilationUnit, Compiler}

/** Rule 7.4.h: a named function that can reach itself in the static call graph of named functions
  * is accepted when its recursion is structural (every call passes, in one position, a strict
  * sub-value of that parameter bound by a pattern) or when its row has `Diverge`; otherwise it is
  * T027, at the first call of the cycle in its first function, with the ways out, the fitting one
  * first. Each rejected shape has an accepted twin.
  */
class TotalitySuite extends munit.FunSuite:
  import TotalitySuite.*

  private val list = "data L:\n    E\n    C(h: u64, t: L)\n\n"

  private def at(src: String, call: String): Int = src.indexOf(call)

  /** The first way out the hint names. */
  private def first(d: Diagnostic): String = d.suggestion.getOrElse("").split(". Other ways out").head

  test("structural recursion is accepted: on a field, through a nested match, at other type arguments"):
    accepted(list + "fn total(xs: L) -> u64 =\n    match xs:\n        L.C(h, t) => h + total(t)\n        L.E => 0\n")
    accepted(list + "fn pairs(xs: L) -> u64 =\n    match xs:\n        L.C(h, t) =>\n            match t:\n" +
      "                L.C(_, u) => h + pairs(u)\n                L.E => h\n        L.E => 0\n")
    accepted(
      "data M of (A, B):\n    Leaf(a: A, b: B)\n    Flip(r: M of (B, A))\n\n" +
        "fn size(m: M of (A, B)) -> u64 =\n    match m:\n        M.Leaf(a, b) => 1\n        M.Flip(r) => 1 + size(r)\n"
    )
    accepted("fn deep(p: T is Show, n: Nat) -> String =\n    match n:\n        Nat.Zero => p.show()\n" +
      "        Nat.Succ(k) => deep(Tuple.Tuple(p, p), k)\n")

  test("a structural cycle, and a satisfies body that recurses on a field of self, are accepted"):
    accepted(
      "data A:\n    End\n    A1(x: u64, b: B)\n\ndata B:\n    B1(y: u64, a: A)\n\n" +
        "fn suma(a: A) -> u64 =\n    match a:\n        A.A1(x, b) => x + sumb(b)\n        A.End => 0\n\n" +
        "fn sumb(b: B) -> u64 =\n    match b:\n        B.B1(y, a) => y + suma(a)\n"
    )
    accepted("data T:\n    Leaf\n    N(l: T, v: u64)\n\nT satisfies Show:\n    fn show() = match self:\n" +
      "        T.N(l, v) => l.show().concat(v.show())\n        T.Leaf => \"\"\n")

  test("a count down a u64 is T027 with `with Diverge` first; the Diverge and Nat twins pass"):
    val src = "fn sum(n: u64, acc: u64) -> u64 = if n == 0: acc else: sum(n - 1, acc + n)\n"
    val List(d) = rejected(src): @unchecked
    assertEquals((d.message, d.span.start), ("`sum` calls itself", at(src, "sum(n - 1")))
    assert(first(d).contains("declare `with Diverge`"), d)
    accepted(src.replace("-> u64 =", "-> u64 with Diverge ="))
    accepted(
      "fn sum(n: Nat, acc: u64) -> u64 =\n    match n:\n        Nat.Zero => acc\n        Nat.Succ(k) => sum(k, acc + 1)\n"
    )

  test("Diverge propagates: a caller without it is T015, `main` may declare it"):
    val src = "fn spin(n: u64) -> u64 with Diverge = spin(n + 1)\n"
    val ds = compile(src + "fn user() -> u64 = spin(0)\n").diagnostics.filter(_.isError)
    assertEquals(ds.map(_.code), List(DiagCode.T015))
    accepted(src + "fn main() -> () with Diverge =\n    let _x = spin(0)\n    ()\n")

  test("a `-> !` function that calls itself needs Diverge"):
    val src = "fn spin(n: u64) -> ! = spin(n + 1)\n"
    val List(d) = rejected(src): @unchecked
    assert(first(d).contains("`with Diverge`"), d)
    accepted(src.replace("-> ! =", "-> ! with Diverge ="))

  test("a call on the whole parameter is not structural"):
    val src = list + "fn loop(xs: L) -> u64 =\n    match xs:\n        L.C(h, t) => h + loop(xs)\n        L.E => 0\n"
    val List(d) = rejected(src): @unchecked
    assertEquals(d.span.start, at(src, "loop(xs)"))
    assert(first(d).contains("`with Diverge`"), d)

  test("sub-values in different positions in two calls are not structural"):
    val src = list + "fn zip(a: L, b: L) -> u64 =\n    match a:\n        L.C(h, t) =>\n            match b:\n" +
      "                L.C(k, u) => zip(t, b) + zip(a, u)\n                L.E => h\n        L.E => 0\n"
    val List(d) = rejected(src): @unchecked
    assertEquals(d.span.start, at(src, "zip(t, b)"))
    accepted(src.replace("zip(t, b) + zip(a, u)", "zip(t, u) + zip(t, u)"))

  test("a cycle that does not decrease on every call is T027, naming the functions"):
    val src = list +
      "fn f(xs: L) -> u64 = g(xs)\n\nfn g(xs: L) -> u64 =\n    match xs:\n        L.C(_, t) => f(t)\n        L.E => 0\n"
    val List(d) = rejected(src): @unchecked
    assertEquals((d.message, d.span.start), ("`f` and `g` call each other", at(src, "g(xs)")))
    val three = "fn f(n: u64) -> u64 = if n == 0: 0 else: g(n - 1)\n" +
      "fn g(n: u64) -> u64 = h(n)\nfn h(n: u64) -> u64 = f(n)\nfn k(n: u64) -> u64 = f(n)\n"
    assertEquals(rejected(three).map(_.message), List("`f`, `g` and `h` call each other"))

  test("recursion through a lambda over a field: `fold` first, and its twin"):
    val rose = "data Rose of A:\n    R(v: A, kids: List of Rose of A)\n\nRose satisfies Functor\n\n"
    val src = rose +
      "fn sum(r: Rose of u64) -> u64 =\n    match r:\n        Rose.R(v, ks) => ks.fold(v, (n, k) -> n + sum(k))\n"
    val List(d) = rejected(src): @unchecked
    assertEquals(d.span.start, at(src, "sum(k)"))
    assert(first(d).contains("with `fold`") && d.suggestion.exists(_.contains("r.fold((v, ks) -> …)")), d)
    accepted(rose + "fn sum(r: Rose of u64) -> u64 = r.fold((v, ks) -> ks.fold(v, (n, k) -> n + k))\n")

  test("a field used as it is beside the call through a lambda: `para` first"):
    val rose = "data Rose:\n    R(v: u64, kids: List of Rose)\n\n"
    val src = rose +
      "fn f(r: Rose) -> u64 =\n    match r:\n        Rose.R(v, ks) => ks.fold(v, (n, k) -> n + f(k)) + ks.length()\n"
    val List(d) = rejected(src): @unchecked
    assert(first(d).contains("with `para`"), d)
    accepted(rose + "fn f(r: Rose) -> u64 = r.para((v, ks, sums) -> sums.fold(v, (n, s) -> n + s) + ks.length())\n")

  test("a call on a member of the parameter: a `match` on the parameter first"):
    val src = "fn len(xs: List of u64) -> u64 =\n    match xs:\n        List.Cons(_, _) => 1 + len(xs.tail())\n" +
      "        List.Nil => 0\n"
    val List(d) = rejected(src): @unchecked
    assert(first(d).contains("match on the parameter"), d)
    accepted(src.replace("List.Cons(_, _) => 1 + len(xs.tail())", "List.Cons(_, t) => 1 + len(t)"))

  test("a function that builds data from a seed: `unfold` first"):
    val src = list + "fn upto(i: u64, n: u64) -> L = if i >= n: L.E else: L.C(i, upto(i + 1, n))\n"
    val List(d) = rejected(src): @unchecked
    assertEquals(d.span.start, at(src, "upto(i + 1"))
    assert(first(d).contains("with `unfold`"), d)
    accepted(list + "fn upto(i: u64, n: u64) -> L = L.unfold(i, (k) -> if k >= n: L.E else: L.C(k, k + 1))\n")

  test("a nested fn, a lambda, a value use, a satisfies body and a default body are all edges"):
    val nested = "fn f(n: u64) -> u64 =\n    fn go(i: u64) -> u64 = if i == 0: 0 else: go(i - 1)\n    go(n)\n"
    assertEquals(rejected(nested).map(_.message), List("`go` calls itself"))
    val outer = "fn f(n: u64) -> u64 =\n    fn go(i: u64) -> u64 = if i == 0: 0 else: f(i - 1)\n    go(n)\n"
    val List(o) = rejected(outer): @unchecked
    assertEquals((o.message, o.span.start), ("`f` and `go` call each other", at(outer, "go(n)")))
    val lambda = "fn f(n: u64) -> u64 =\n    let g = (i: u64) -> if i == 0: 0 else: f(i - 1)\n    g(n)\n"
    assertEquals(rejected(lambda).map(_.message), List("`f` calls itself"))
    val value = "fn apply(g: u64 -> u64, n: u64) -> u64 = g(n)\nfn f(n: u64) -> u64 = apply(f, n)\n"
    val List(v) = rejected(value): @unchecked
    assertEquals((v.message, v.span.start), ("`f` calls itself", at(value, "f, n)")))
    val tree = "data T:\n    Leaf\n    N(l: T, v: u64)\n\n"
    val show = tree +
      "T satisfies Show:\n    fn show() = match self:\n        T.N(l, v) => self.show()\n        T.Leaf => \"\"\n"
    val List(d) = rejected(show): @unchecked
    assertEquals((d.message, d.span.start), ("`T.show` calls itself", at(show, "self.show()")))
    val eq = tree + "T satisfies Eq:\n    fn eq(other: T) = self == other\n"
    assertEquals(rejected(eq).map(_.message), List("`T.eq` calls itself"))
    val default = "cap Sz:\n    fn sz() -> u64 = count(Box.B(0))\n\ndata Box:\n    B(v: u64)\n\n" +
      "Box satisfies Sz\n\nfn count(b: Box) -> u64 = b.sz()\n"
    assertEquals(rejected(default).map(_.message), List("`Sz.sz` and `count` call each other"))

  test("a call through a capability table, a closure received, or a local of the same name is no edge"):
    accepted(
      "data T:\n    Leaf\n    N(l: T)\n\nT satisfies Show:\n    fn show() = shown(self)\n\n" +
        "fn shown(x: X is Show) -> String = x.show()\n\nfn twice(f: u64 -> u64, x: u64) -> u64 = f(f(x))\n"
    )
    accepted("fn f(n: u64) -> u64 =\n    let f = (i: u64) -> i + 1\n    f(n)\n")
    accepted("fn main() -> () = ()\n")

  test("a pipe passes its left side first; an or-pattern binds a sub-value only if both sides do"):
    accepted(list +
      "fn total(xs: L) -> u64 =\n    match xs:\n        L.C(h, t) => h + (t |> total)\n        L.E => 0\n")
    val swapped = list +
      "fn f(a: L, b: L) -> u64 =\n    match a:\n        L.C(h, t) => L.C(1, b) |> f(t)\n        L.E => 0\n"
    assertEquals(rejected(swapped).map(_.message), List("`f` calls itself"))
    val or = list + "fn f(a: L) -> u64 =\n    match a:\n        L.C(_, t) | t => f(t)\n"
    assertEquals(rejected(or).map(_.message), List("`f` calls itself"))

  test("a static satisfies body that calls itself is an edge"):
    val src = "cap Mk:\n    Self.fn mk(n: u64) -> Self\n\ndata D:\n    D(n: u64)\n\n" +
      "D satisfies Mk:\n    Self.fn mk(n: u64) -> D = D.mk(n + 1)\n"
    val List(d) = rejected(src): @unchecked
    assertEquals((d.message, d.span.start), ("`D.mk` calls itself", at(src, "D.mk(n + 1)")))

  test("a call inside a callback of the parameter's own fold: a `match` on the parameter first"):
    val src = "data K:\n    Leaf\n    Wrap(p: Tuple of (K, u64))\n\n" +
      "fn total(k: K) -> u64 =\n    k.fold(\n        () -> 1,\n        (p) -> match p:\n" +
      "            Tuple.Tuple(inner, n) => total(inner) * n\n    )\n"
    val List(d) = rejected(src): @unchecked
    assert(first(d).contains("match on the parameter"), d)
    accepted(src.replace("-> u64 =", "-> u64 with Diverge ="))

  test("`>` compares its right side with its left: a call on `self` is not structural"):
    val tree = "data T:\n    Leaf\n    N(l: T)\n\nT satisfies Ord:\n    fn compare(other: T) = match self:\n"
    val loop = tree + "        T.N(l) => if l > self: Order.Less else: Order.Greater\n        T.Leaf => Order.Equal\n"
    assertEquals(rejected(loop).map(_.message), List("`T.compare` calls itself"))
    accepted(loop.replace("l > self", "self > l"))

  test("swapped sub-values do not suggest `fold`"):
    val src = list + "fn f(a: L, b: L) -> u64 =\n    match a:\n        L.C(h, t) =>\n            match b:\n" +
      "                L.C(k, u) => f(u, t)\n                L.E => h\n        L.E => 0\n"
    val List(d) = rejected(src): @unchecked
    assert(first(d).contains("in other positions"), d)

  /** Rule 7.4.j: the T028 diagnostics of `source`, which has no other error. */
  private def throughSelf(source: String): List[Diagnostic] =
    val ds = compile(source).diagnostics.filter(_.isError)
    assert(ds.nonEmpty && ds.forall(_.code == DiagCode.T028), ds)
    ds

  private def leaveWithout(d: Diagnostic, member: String): Unit =
    assert(d.suggestion.exists(h => h.contains(s"leave `$member`") && h.contains("without a default body")), d)

  test("a default body that calls its own member on `self` is T028, with Diverge and without"):
    val src = "cap Sz:\n    fn sz() -> u64 = self.sz()\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals((d.message, d.span.start), ("`Sz.sz` calls itself through `self`", at(src, "self.sz()")))
    leaveWithout(d, "Sz.sz")
    assertEquals(throughSelf(src.replace("-> u64 =", "-> u64 with Diverge =")).map(_.code), List(DiagCode.T028))
    accepted("cap Sz:\n    fn sz() -> u64\n    fn twice() -> u64 = self.sz() + self.sz()\n")

  test("two default bodies that call each other on `self` or on a value of type `Self` are T028"):
    val src = "cap Two:\n    fn a() -> u64 = self.b()\n    fn b() -> u64 = self.a()\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals((d.message, d.span.start), ("`Two.a` and `Two.b` call each other through `self`", at(src, "self.b()")))
    val other = "cap Two:\n    fn a(o: Self) -> u64 = o.b(self)\n    fn b(o: Self) -> u64 = self.a(o)\n"
    assertEquals(throughSelf(other).map(_.message), List("`Two.a` and `Two.b` call each other through `self`"))
    accepted("cap Two:\n    fn a() -> u64 = self.b() + self.c()\n    fn b() -> u64 = self.c()\n    fn c() -> u64\n")

  test("a cycle through the default body of an extended capability is T028"):
    val src = "cap Base:\n    fn a() -> u64\n    fn c() -> u64 = self.a()\n\n" +
      "cap Sz extends Base:\n    fn a() -> u64 = self.c()\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals((d.message, d.span.start), ("`Base.c` and `Sz.a` call each other through `self`", at(src, "self.a()")))
    accepted("cap Base:\n    fn a() -> u64\n    fn c() -> u64 = self.a()\n\n" +
      "cap Sz extends Base:\n    fn a() -> u64 = 1\n    fn s() -> u64 = self.c()\n")

  test("a structurally smaller argument does not admit a cycle through `self`"):
    val src = "cap Sz:\n    fn sz(n: Nat) -> u64 =\n        match n:\n            Nat.Succ(k) => self.sz(k)\n" +
      "            Nat.Zero => 0\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals(d.span.start, at(src, "self.sz(k)"))
    leaveWithout(d, "Sz.sz")
    accepted(
      "fn sz(n: Nat) -> u64 =\n    match n:\n        Nat.Succ(k) => sz(k)\n        Nat.Zero => 0\n"
    )

  test("an operator on `Self` in a default body is a call through the table"):
    val src = "cap E2 extends Eq:\n    fn eq(o: Self) -> bool = self == o\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals((d.message, d.span.start), ("`E2.eq` calls itself through `self`", at(src, "self == o")))
    accepted("cap E2 extends Eq:\n    fn same(o: Self) -> bool = self == o\n")
    accepted("cap E2 extends Eq:\n    fn eq(o: Self) -> bool = self.g(1)\n    fn g(n: u64) -> bool = n == 2\n")

  test("a static member called by the capability's own name is a call through the table"):
    val src = "cap Mk:\n    Self.fn mk() -> Self = Mk.mk()\n"
    val List(d) = throughSelf(src): @unchecked
    assertEquals((d.message, d.span.start), ("`Mk.mk` calls itself through `self`", at(src, "Mk.mk()")))
    accepted("cap Mk:\n    Self.fn mk() -> Self\n    Self.fn mk2() -> Self = Mk.mk()\n")
    val restated = "cap Base:\n    Self.fn w() -> Self\n    Self.fn z() -> Self = Base.w()\n\n" +
      "cap Sz extends Base:\n    Self.fn w() -> Self = Sz.z()\n"
    assertEquals(throughSelf(restated).map(_.code), List(DiagCode.T028))

  test("a nested fn or lambda in a default body that calls back through `self` is T028"):
    val nested = "cap Sz:\n    fn sz() -> u64 =\n        fn go() -> u64 = self.sz()\n        go()\n"
    assertEquals(throughSelf(nested).map(_.code), List(DiagCode.T028))
    val lambda = "cap Sz:\n    fn sz() -> u64 =\n        let g = (i: u64) -> self.sz() + i\n        g(1)\n"
    assertEquals(throughSelf(lambda).map(_.code), List(DiagCode.T028))

  test("a satisfaction that overrides the member does not break a cycle of the capability"):
    val src = "cap Sz:\n    fn sz() -> u64 = self.sz()\n\ndata B:\n    B(v: u64)\n\n" +
      "B satisfies Sz:\n    fn sz() -> u64 = 0\n"
    assertEquals(throughSelf(src).map(_.message), List("`Sz.sz` calls itself through `self`"))

  test("a call on another value bounded by the capability is not through `self`"):
    accepted("cap Sz:\n    fn sz() -> u64\n    fn plus(x: T is Sz) -> u64 = x.sz() + self.sz()\n")
    accepted("cap Sz:\n    fn sz() -> u64 = 1\n    fn plus(x: is Sz) -> u64 = x.plus(x)\n")

  test("a satisfies body that calls the same member on `self` is T027, for data, generic data and scalars"):
    val q = "cap Q:\n    fn q(o: Self) -> u64\n\n"
    val data = q + "data W of A:\n    W(a: A)\n\nW satisfies Q:\n    fn q(o: Self) -> u64 = o.q(self)\n"
    assertEquals(rejected(data).map(_.message), List("`W.q` calls itself"))
    val scalar = q + "i64 satisfies Q:\n    fn q(o: Self) -> u64 = self.q(o)\n"
    assertEquals(rejected(scalar).map(_.message), List("`i64.q` calls itself"))

object TotalitySuite:

  def compile(source: String): CompilationUnit =
    Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>.fixed", source)))

  /** The T027 diagnostics of `source`, which has no other error. */
  def rejected(source: String): List[Diagnostic] =
    val ds = compile(source).diagnostics.filter(_.isError)
    assert(ds.nonEmpty && ds.forall(_.code == DiagCode.T027), ds)
    ds

  def accepted(source: String): Unit =
    val u = compile(source)
    assert(!u.hasErrors, u.diagnostics)
