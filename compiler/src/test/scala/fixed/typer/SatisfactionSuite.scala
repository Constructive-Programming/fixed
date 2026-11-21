package fixed.typer

import fixed.core.{SymbolId, Type}
import fixed.parsing.Parser
import fixed.util.{DiagCode, SourceFile}

class SatisfactionSuite extends munit.FunSuite:

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
    val unit = Parser.parse(SourceFile.fromString("<sat>", decls))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, diags) = Namer.index(unit.tree)
    assertEquals(diags, Nil)
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

  /** Index and body-type a whole unit, as `TyperPhase` does. */
  private def typedUnit(code: String): TyperState =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    TyperPhase.typeBodies(unit.tree, table)

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
