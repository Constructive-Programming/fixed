package fixed.typer

import fixed.core.{SymbolId, Type}
import fixed.util.DiagCode

class SatisfactionSuite extends munit.FunSuite, TyperTestSupport:

  private val decls =
    """|data Shape:
       |    Circle(r: u64)
       |    Dot
       |data Box of A:
       |    Box(value: A)
       |data Sink of A:
       |    Sink(consume: A -> ())
       |data Paired of A:
       |    Paired(p: Tuple of (A, u64))
       |data Forest of A:
       |    Forest(value: A, kids: List of Forest of A)
       |data Ma of A:
       |    Ma(b: Mb of A)
       |data Mb of A:
       |    Nb
       |    Mb(a: Ma of A, x: A)
       |data Holder:
       |    Holder(f: u64 -> u64)
       |cap Container extends Folding
       |cap Ring extends Numeric
       |fn uses(n: N is Ring, c: C is Container) -> u64 = 0
       |""".stripMargin

  private val state: TyperState =
    val (_, table) = indexed(decls)
    TyperState(table)

  private def sym(name: String): SymbolId = state.symbols.scope(name)
  private def tref(name: String, args: Type*): Type = Type.TypeRef(sym(name), args.toList)
  private def param(fn: String, name: String): Type =
    Type.TypeRef(state.symbols.typeParamsOf(sym(fn)).find(state.symbols(_).name == name).get, Nil)
  private def holds(t: Type, cap: String, args: Type*): Boolean =
    Satisfaction.holds(state, t, sym(cap), args.toList).isDefined

  private val u64 = Type.PrimType("u64")

  test("declared satisfactions, through the capability's extends closure"):
    assert(holds(tref("List", u64), "Folding"))
    assert(holds(tref("String"), "Concat"))
    assert(holds(tref("String"), "Eq"))
    assert(!holds(tref("String"), "Folding"))

  test("capability arguments must match the satisfying type's element"):
    assert(holds(tref("List", u64), "Folding", u64))
    assert(!holds(tref("List", u64), "Folding", tref("String")))

  test("primitives: numeric closure, declared Show, Clone"):
    assert(holds(u64, "Numeric"))
    assert(holds(u64, "Ord"))
    assert(holds(u64, "Show"))
    assert(holds(u64, "Clone"))
    assert(!holds(Type.PrimType("bool"), "Numeric"))

  test("auto-derivation: Folding, field-wise Show, Functor over a covariant rightmost parameter"):
    assert(holds(tref("Shape"), "Folding"))
    assert(holds(tref("Shape"), "Show"))
    assert(holds(tref("Box", u64), "Functor"))
    assert(!holds(tref("Sink", u64), "Functor"))
    assert(!holds(tref("Paired", u64), "Functor"))
    assert(holds(tref("Forest", u64), "Functor"))
    assert(holds(tref("Ma", u64), "Functor"))
    assert(!holds(tref("Holder"), "Show"))
    assert(!holds(tref("Shape"), "Optional"))

  test("type variables satisfy what their bound reaches; Numeric through extends"):
    assert(holds(param("uses", "N"), "Numeric"))
    assert(holds(param("uses", "C"), "Folding"))
    assert(!holds(param("uses", "C"), "Numeric"))
    assert(Members.isNumeric(state, param("uses", "N")))

  test("a bound's capability arguments must unify with the ones a declared satisfaction writes"):
    /** `t` is written in, or reaches, every satisfaction but `C`'s; each call needs `u64` there.
      * `D` writes no arguments for `Bytes`: its own argument, `t`, is `Bytes`'s.
      */
    def program(t: String) =
      s"""|data B:
          |    B(n: u64)
          |B satisfies Sized of (u8, $t):
          |    fn size = 0
          |cap Bytes of (T is Numeric) extends Sized of (u8, T):
          |    fn raw -> u64
          |u32 satisfies Bytes of $t:
          |    fn raw = 0
          |    fn size = 0
          |data C:
          |    C(n: u64)
          |C satisfies Bytes:
          |    fn raw = 0
          |    fn size = 0
          |data D of (T is Numeric):
          |    D(n: T)
          |D satisfies Bytes:
          |    fn raw = 0
          |    fn size = 0
          |cap Q extends R + Sized of (u8, $t):
          |    fn q -> u64
          |cap R extends Q:
          |    fn r -> u64
          |i16 satisfies Q:
          |    fn q = 0
          |    fn r = 0
          |    fn size = 0
          |data Store of V:
          |    Store(v: V)
          |Store satisfies Map of (String, V):
          |    fn get(key: String) = Optional.some(self.v)
          |cap Coll extends Sized of (Part, u64):
          |    fn raw -> u64
          |data Bag of A:
          |    Bag(a: A)
          |Bag satisfies Coll:
          |    fn raw = 0
          |    fn size = 0
          |fn need(_b: is Sized of (u8, u64)) -> u64 = 0
          |fn look(_m: is Map of (String, u64)) -> u64 = 0
          |fn whole(_b: is Sized of (u64, u64)) -> u64 = 0
          |fn sz(b: is Sized of (u8, S is Numeric)) -> S = b.size()
          |fn f(x: u32, y: $t, z: i16) -> u64 =
          |    need(B.B(1)) + need(x) + need(z) + look(Store.Store(y)) + whole(Bag.Bag(y)) + need(D.D(y)) +
          |        need(C.C(1))
          |fn g() -> u64 = sz(B.B(1))
          |""".stripMargin
    val bad = program("u16")
    val calls = List(
      "need(B.B(1))",
      "need(x)",
      "need(z)",
      "look(Store.Store(y))",
      "whole(Bag.Bag(y))",
      "need(D.D(y))",
      "sz(B.B(1))"
    )
    assertEquals(
      typedUnit(bad).diags.map(d => (d.code, d.span.start)),
      calls.map(c => (DiagCode.T010, bad.indexOf(c) + c.indexOf('(') + 1)),
      bad
    )
    assertEquals(typedUnit(program("u64")).diags, Nil)

  test("Ord's compare is found by name on a data value that derives Ord, and needs Ord there or on a type parameter"):
    val ok =
      "data P:\n    P(a: u64)\nfn f(x: P, y: P) -> bool = x.compare(y).fold(() -> true, () -> false, () -> false)\n"
    assertEquals(typedUnit(ok).diags, Nil)
    val bad =
      "data H:\n    H(f: u64 -> u64)\nfn f(x: H) -> bool = x.compare(x).fold(() -> true, () -> false, () -> false)\n" +
        "fn g(x: T) -> bool = x.compare(x).fold(() -> true, () -> false, () -> false)\n" +
        "fn h(x: H) -> bool = x.lt(x)\n"
    assertEquals(
      typedUnit(bad).diags.map(d => (d.code, d.span.start)),
      List(
        (DiagCode.T010, bad.indexOf("x.compare")),
        (DiagCode.T022, bad.lastIndexOf("x.compare")),
        (DiagCode.T001, bad.indexOf("x.lt"))
      )
    )

  test("Rule 7.4.c: map is found with no Functor declaration, beside another capability's map"):
    val box = "data Box of A:\n    Box(v: A)\nfn f(b: Box of u64) -> Box of bool = b.map((v) -> v == 1)\n"
    val mappy = "cap Mappy:\n    fn map(n: u64) -> u64\nBox satisfies Mappy:\n    fn map(n: u64) -> u64 = n\n"
    assertEquals(typedUnit(box).diags, Nil)
    assertEquals(typedUnit(box + mappy).diags, Nil)

  test("Rule 6.1.1: the types a satisfies body writes are resolved and must fit the requirement's"):
    val d =
      "data D:\n    D(n: u64)\ncap Greet:\n    fn greet(t: T, n: u64) -> u64\ncap Shout:\n    fn shout(t: T is Show) -> u64\n" +
        "data P of (A is Ord, B):\n    P(a: A, b: B)\ncap Fold2:\n    fn fold2(init: R, f: (R, Part) -> R) -> R\n"
    val wrong = List(
      "D satisfies Eq:\n    fn eq(o: Strng) = true\n" -> ("Strng", DiagCode.T002, "expected D, got Strng"),
      "D satisfies Eq:\n    fn eq(o: u64) = true\n" -> ("u64) =", DiagCode.T002, "expected D, got u64"),
      "D satisfies Eq:\n    fn eq(o: i128) = true\n" ->
        ("i128", DiagCode.T032, "i128 is reserved; the widest integer type is i64"),
      "D satisfies Eq:\n    fn eq(o: D) -> u64 = true\n" -> ("u64 =", DiagCode.T002, "expected bool, got u64"),
      "D satisfies Eq:\n    fn eq(o: D) -> X = true\n" -> ("X =", DiagCode.T002, "expected bool, got X"),
      "P satisfies Ord:\n    fn compare(other: P of (B, A)) = Order.Equal\n" ->
        ("P of (B, A)", DiagCode.T002, "expected P of (A, B), got P of (B, A)"),
      "D satisfies Greet:\n    fn greet(t: T, n: String) = 1\n" ->
        ("String) =", DiagCode.T002, "expected u64, got String"),
      "D satisfies Greet:\n    fn greet(t: X, n: X) = 1\n" -> ("X) =", DiagCode.T002, "expected u64, got T"),
      "P satisfies Ord:\n    fn compare(other: P of A) = Order.Equal\n" ->
        ("P of A", DiagCode.T006, "P takes 2 argument(s), given 1"),
      "D satisfies Fold2:\n    fn fold2(init: S is Show, f: (S, u64) -> S) = init\n" ->
        ("S is Show", DiagCode.T010, "R does not satisfy Show")
    )

    /** One error each, but for two written types that are wrong twice: `P of A` also differs from the
      * requirement's `P of (A, B)` (`T002`), and `P of (B, A)` gives `B` where `P` needs `Ord` (`T010`).
      */
    wrong.foreach { case (code, (at, c, message)) =>
      val ds = typedUnit(d + code).diags.filter(_.isError).map(x => (x.code, x.span.start, x.message))
      assertEquals(ds.filter(_._1 == c), List((c, (d + code).indexOf(at), message)), code)
      assertEquals(ds.size, if at == "P of A" || at == "P of (B, A)" then 2 else 1, code)
    }

    /** `Self`, the data type at its parameters (`B` is `P`'s `Part`), `Part`, the requirement's type
      * variables under any name, with a bound they already have, and an anonymous bound that holds.
      */
    val right = "P satisfies Ord:\n    fn compare(other: P of (A, B)) = other.a.compare(self.a)\n" +
      "D satisfies Eq:\n    fn eq(o: Self) -> bool = o.n == self.n\nD satisfies Greet:\n    fn greet(t: X, n: u64) = n\n" +
      "P satisfies Fold2:\n    fn fold2(init: S, f: (S, B) -> S) -> S = init\n" +
      "data L of A:\n    Nil\n    Cons(h: A, t: L of A)\nL satisfies Fold2:\n    fn fold2(init: R, f: (R, Part) -> R) = init\n" +
      "L satisfies Show:\n    fn show() -> is Show = \"l\"\nD satisfies Shout:\n    fn shout(t: X is Show) = 1\n"
    assertEquals(typedUnit(d + right).diags.filter(_.isError), Nil)
