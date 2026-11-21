package fixed.typer

import fixed.core.{SymbolId, Type}
import fixed.parsing.Parser
import fixed.util.SourceFile

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
