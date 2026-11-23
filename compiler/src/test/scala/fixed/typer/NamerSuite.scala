package fixed.typer

import fixed.core.{SymKind, SymbolId, SymbolTable, Type, render}
import fixed.parsing.Parser
import fixed.util.{DiagCode, Diagnostic, SourceFile}

class NamerSuite extends munit.FunSuite:

  private def index(src: SourceFile): (SymbolTable, List[Diagnostic]) =
    val parsed = Parser.parse(src)
    assert(!parsed.hasErrors, parsed.diagnostics)
    Namer.index(parsed.tree)

  /** Every symbol outside the stdlib as `(name, kind, signature)`, in entry order. */
  private def triples(table: SymbolTable): List[(String, SymKind, String)] =
    def stdlib(owner: SymbolId): Boolean =
      owner == SymbolId.Prelude || (owner != SymbolId.Root && stdlib(table(owner).owner))
    table.denotations.filterNot(d => stdlib(d.owner)).map(d => (d.name, d.kind, table.render(d.info)))

  private def indexString(code: String) = index(SourceFile.fromString("<test>", code))

  test("M2: 01_basics symbol table"):
    val (table, diags) = index(SourceFile.fromPath("examples/01_basics.fixed"))
    assertEquals(diags, Nil)
    assertEquals(
      triples(table),
      List(
        ("greet", SymKind.Fn, "String -> () with Console"),
        ("fibIter", SymKind.Fn, "N -> N"),
        ("printFibs", SymKind.Fn, "u64 -> () with Console"),
        ("main", SymKind.Fn, "() -> () with Console"),
        ("name", SymKind.ValueParam, "String"),
        ("N", SymKind.TypeParam, "is Numeric"),
        ("n", SymKind.ValueParam, "N"),
        ("count", SymKind.ValueParam, "u64")
      )
    )

  test("M2: every example indexes without diagnostics"):
    new java.io.File("examples").listFiles(_.getName.endsWith(".fixed")).sorted.foreach { f =>
      assertEquals(index(SourceFile.fromPath(f.getPath))._2, Nil, f.getName)
    }

  test("Rule 4.1: re-stating a signature's own variable adds bounds"):
    val (table, diags) =
      indexString("fn abs(n: N is Numeric + Ord) -> N is positive() = n\nfn positive() -> cap of N = 0\n")
    assertEquals(diags, Nil)
    assert(triples(table).contains(("N", SymKind.TypeParam, "is Numeric + Ord + positive()")), triples(table))

  test("Rule 4.1: free names introduce variables; of-params scope over the body"):
    val (table, diags) = indexString("cap Functor of A:\n    fn map(f: A -> B) -> Self of B\n")
    assertEquals(diags, Nil)
    assert(triples(table).contains(("map", SymKind.Method, "(A -> B) -> Self of B")), triples(table))

  test("Rule 4.1: re-binding an enclosing variable is an error"):
    val (_, diags) = indexString("cap C of (N is Numeric):\n    fn f(x: N is Ord) -> N\n")
    assertEquals(diags.map(_.code), List(DiagCode.T004))

  test("data variants are constructors of the data type"):
    val (table, diags) = indexString("data Pair2 of (A, B):\n    Both(a: A, b: B)\n    Neither\n")
    assertEquals(diags, Nil)
    val t = triples(table)
    assert(t.contains(("Both", SymKind.Variant, "(A, B) -> Pair2 of (A, B)")), t)
    assert(t.contains(("Neither", SymKind.Variant, "Pair2 of (A, B)")), t)

  test("refinement calls and value args resolve to symbols"):
    val (table, diags) = indexString(
      "type Clamped(lo: N is Numeric + Ord, hi: N) = N + between(lo, hi)\nfn between(min: N is Ord, max: N) -> cap of N = 0\n"
    )
    assertEquals(diags, Nil)
    assert(triples(table).contains(("Clamped", SymKind.TypeAlias, "is N + between(lo, hi)")), triples(table))

  test("unknown capabilities are T001; an unknown name in a row is a row variable; duplicates are T003"):
    val (table, diags) = indexString("fn f(x: is Nope) -> () with Nada = ()\nfn f() -> () = ()\n")
    assertEquals(diags.map(_.code), List(DiagCode.T003, DiagCode.T001))
    assert(triples(table).exists((n, k, _) => n == "Nada" && k == SymKind.TypeParam), triples(table))

  test("use std.io.X binds the stdlib declaration; unknown modules stay opaque imports"):
    val (table, diags) = indexString("use std.io.Console\nuse acme.Widget\n")
    assertEquals(diags, Nil)
    assertEquals(table(table.scope("Console")).kind, SymKind.Effect)
    assertEquals(table(table.scope("Widget")).kind, SymKind.Import)
    assert(!table.scope.contains("FileSystem"))

  test("satisfies declarations are recorded by the satisfying type's symbol, not its name"):
    val (table, diags) =
      indexString(
        "data Perhaps of A:\n    Just(value: A)\n    Nothing\nPerhaps satisfies Optional\ndata Order:\n    Open\n"
      )
    assertEquals(diags, Nil)
    assertEquals(table.satisfactionsOf(Type.TypeRef(table.scope("Perhaps"), Nil)).map(table.render), List("Optional"))
    assert(table.satisfactionsOf(Type.PrimType("u64")).map(table.render).contains("Show"))

    /** The program's `Order` shadows the prelude's name, not its satisfactions; the prelude's is renamed. */
    assertEquals(table.satisfactionsOf(Type.TypeRef(table.scope("Order"), Nil)), Nil)
    val prelude = table.denotations.filter(_.name == "prelude.Order").map(d => Type.TypeRef(d.id, Nil))
    assert(prelude.exists(table.satisfactionsOf(_).map(table.render).contains("Ordering")), prelude)
