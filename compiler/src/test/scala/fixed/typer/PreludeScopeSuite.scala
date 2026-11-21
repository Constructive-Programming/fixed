package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.SymbolTable
import fixed.parsing.Parser
import fixed.util.{DiagCode, SourceFile}

/** The prelude is resolved in its own scope: a program's declaration of a prelude name shadows it
  * in the program, and changes nothing the prelude's code, literals or built-in rules mean.
  */
class PreludeScopeSuite extends munit.FunSuite:

  private def typed(code: String): (Tree, SymbolTable, TyperState) =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    (unit.tree, table, TyperPhase.typeBodies(unit.tree, table))

  test("a program that takes every top-level name of the prelude leaves the prelude's code well typed"):
    val names = "String" :: Stdlib.prelude.collect {
      case d: Trees.DataDecl   => d.name
      case c: Trees.CapDecl    => c.name
      case e: Trees.EffectDecl => e.name
    }
    val functions = Stdlib.prelude.collect { case f: Trees.FnDecl => s"fn ${f.name}() -> u64 = 1\n" }
    val program = names.map(n => s"data $n:\n    Own$n(n: u64)\n").mkString + functions.mkString +
      "fn main() -> u64 = [1, 2].fold(0, (a, x) -> a + x)\n"
    val (_, table, s) = typed(program)
    assertEquals(s.diags, Nil)
    assert(table.denotations.exists(_.name == "prelude.repeatStr"), "the prelude's repeatStr is renamed")

    /** The prelude's `satisfies` bodies were typed: their `Optional.some` resolves to its own `Maybe`. */
    assert(s.constructors.exists((_, d, _) => table(d).name == "prelude.Maybe"), s.constructors)

  test("a program's own cap Show is not derived: a data type without a body for show lacks it"):
    val (_, _, s) = typed("cap Show:\n    fn show -> String\ndata Box:\n    Empty\nBox satisfies Show\n")
    assertEquals(s.diags.map(_.code), List(DiagCode.T010))

  test("a member of the program's capability shadows the prelude's member of the same name"):
    val (tree, table, s) =
      typed(
        "cap Show:\n    fn show -> String\nu64 satisfies Show:\n    fn show = \"mine\"\nfn f(x: u64) -> String = x.show()\n"
      )
    assertEquals(s.diags, Nil)
    val call = tree match
      case Trees.CompilationUnit(items, _) => items.collectFirst { case f: Trees.FnDecl if f.name == "f" => f.body.get }
      case _                               => None
    s.types.resolution(call.get) match
      case Some(Resolution.CapMember(cap, _)) => assertEquals(cap, table.scope("Show"))
      case other                              => fail(s"resolved to $other")

  test("a member of the program's capability on an unbounded parameter asks for the program's bound"):
    /** `own` declares the program's `Show`, which has `show` itself or through `extends`. */
    def program(own: String, bound: String) =
      own + s"data W of ($bound):\n    W(a: A)\nW satisfies Show:\n    fn show = self.a.show()\n" +
        s"fn g(x: $bound) -> String = x.show()\n"
    val direct = "cap Show:\n    fn show -> String\n"
    val inherited = "cap Pretty:\n    fn show -> String\ncap Show extends Pretty:\n    fn other -> u64 = 0\n"
    List(direct, inherited).foreach { own =>
      val code = program(own, "A")
      val (_, _, s) = typed(code)
      assertEquals(
        s.diags.map(d => (d.code, d.span.start, d.message, d.suggestion)).sortBy(_._2),
        List(
          (
            DiagCode.T022,
            code.indexOf("self.a.show"),
            "`.show` on a value of type A needs the bound `Show`",
            Some("write the bound: `data W of (A is Show)`")
          ),
          (
            DiagCode.T022,
            code.indexOf("x.show"),
            "`.show` on a value of type A needs the bound `Show`",
            Some("write the bound: `A is Show`")
          )
        )
      )
      val (_, _, ok) = typed(program(own, "A is Show"))
      assertEquals(ok.diags, Nil)
    }

  test("a program's satisfier of the prelude's Optional leaves the prelude's own Optional.some unambiguous"):
    val (_, _, s) = typed(
      "data Opt2 of A:\n    Yes(v: A)\n    No\nOpt2 satisfies Optional:\n    Yes as some\n    No as none\n" +
        "    fn foldRight(init: R, f: (Part, R) -> R) = init\n    fn find(pred: Part -> bool) = self\n"
    )
    assertEquals(s.diags, Nil)

  test("list and string literals are the prelude's List and String whatever the program declares"):
    val (_, _, s) = typed(
      "data List:\n    Empty\ndata String:\n    Word\n" +
        "fn xs() -> is Folding of i64 = [1, 2]\nfn s() -> bool = \"a\".concat(\"b\") == \"ab\"\n"
    )
    assertEquals(s.diags, Nil)
