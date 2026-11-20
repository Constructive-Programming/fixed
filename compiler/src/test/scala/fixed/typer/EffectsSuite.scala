package fixed.typer

import fixed.parsing.Parser
import fixed.util.{DiagCode, SourceFile}

/** Effect rows (`docs/plans/phase-3.2-effects.md`): declared-row checks, ambient pass-through,
  * handler subtraction, coverage and abortive `resume`.
  */
class EffectsSuite extends munit.FunSuite:

  private val ask = "effect Ask:\n    fn ask(prompt: String) -> u64\n"

  /** Diagnostic codes of a whole unit after `TyperPhase`, errors and warnings alike. */
  private def codes(code: String): List[DiagCode] =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    TyperPhase.typeBodies(unit.tree, table).report(namerDiags).diags.map(_.code)

  test("an operation performs its effect; the body must declare it"):
    assertEquals(codes(ask + "fn f() -> u64 with Ask = Ask.ask(\"q\")\n"), Nil)
    assertEquals(codes(ask + "fn f() -> u64 = Ask.ask(\"q\")\n"), List(DiagCode.T015))

  test("a declared effect never performed is a warning"):
    assertEquals(codes(ask + "fn f() -> u64 with Ask = 1\n"), List(DiagCode.T017))

  test("a call performs the callee's declared row"):
    val decls = ask + "fn f() -> u64 with Ask = Ask.ask(\"q\")\n"
    assertEquals(codes(decls + "fn g() -> u64 with Ask = f() + 1\n"), Nil)
    assertEquals(codes(decls + "fn g() -> u64 = f() + 1\n"), List(DiagCode.T015))

  test("a callback for a parameter without `with` passes its row to the caller (Rule E5.5.a)"):
    val decls = ask + "fn apply(f: () -> u64) -> u64 = f()\n"
    assertEquals(codes(decls + "fn g() -> u64 with Ask = apply(() -> Ask.ask(\"q\"))\n"), Nil)
    assertEquals(codes(decls + "fn g() -> u64 = apply(() -> Ask.ask(\"q\"))\n"), List(DiagCode.T015))

  test("a callback for a parameter with `with` is bounded by it, and its row stays there"):
    val decls = ask + "effect Other:\n    fn other -> u64\n" +
      "fn run(f: () -> u64 with Ask) -> u64 =\n    handle f():\n        Ask.ask(p) => resume(1)\n"
    assertEquals(codes(decls + "fn g() -> u64 = run(() -> Ask.ask(\"q\"))\n"), Nil)
    assertEquals(codes(decls + "fn g() -> u64 = run(() -> Other.other)\n"), List(DiagCode.T015))

  test("handle removes the effects its clauses name, and passes the rest"):
    val decls = ask + "effect Other:\n    fn other -> u64\n"
    val handled =
      "    handle (do:\n        Other.other\n        Ask.ask(\"q\")\n    ):\n        Ask.ask(p) => resume(1)\n"
    assertEquals(codes(decls + "fn g() -> u64 with Other =\n" + handled), Nil)
    assertEquals(codes(decls + "fn g() -> u64 =\n" + handled), List(DiagCode.T015))

  test("a handler covers every operation of each effect it names"):
    val two = "effect Two:\n    fn one -> u64\n    fn two -> u64\n"
    assertEquals(
      codes(two + "fn g() -> u64 =\n    handle Two.one:\n        Two.one() => resume(1)\n"),
      List(DiagCode.T018)
    )

  test("an abortive operation's clause cannot resume"):
    val body = "fn g() -> u64 =\n    handle Fail.fail(\"x\"):\n        Fail.fail(e) => resume(1)\n"
    assertEquals(codes(body), List(DiagCode.T019))

  test("a clause's parameters take the subject's effect arguments"):
    val decls = "fn inner() -> u64 with Fail of String = Fail.fail(\"x\")\nfn len(s: String) -> u64 = 0\n"
    assertEquals(codes(decls + "fn g() -> u64 =\n    handle inner():\n        Fail.fail(e) => len(e)\n"), Nil)
    val wrong = "fn inner() -> u64 with Fail of u64 = Fail.fail(1)\nfn len(s: String) -> u64 = 0\n"
    assertEquals(
      codes(wrong + "fn g() -> u64 =\n    handle inner():\n        Fail.fail(e) => len(e)\n"),
      List(DiagCode.T002)
    )

  test("main performs only effects the runtime handles (Rule E5.4)"):
    assertEquals(codes("use std.io.Console\nfn main() -> () with Console = Console.printLine(\"hi\")\n"), Nil)
    assertEquals(codes("fn main() -> () with Fail of String = Fail.fail(\"x\")\n"), List(DiagCode.T016))

  test("a row variable carries a callback's other effects to the receiver's row (Rule E8.2)"):
    val decls = ask + "effect Other:\n    fn other -> u64\n" +
      "fn run(f: () -> u64 with Ask + E) -> u64 with E =\n    handle f():\n        Ask.ask(p) => resume(1)\n"
    assertEquals(codes(decls + "fn g() -> u64 with Other = run(() -> Ask.ask(\"q\") + Other.other)\n"), Nil)
    assertEquals(codes(decls + "fn g() -> u64 = run(() -> Ask.ask(\"q\") + Other.other)\n"), List(DiagCode.T015))
    assertEquals(codes(decls + "fn g() -> u64 = run(() -> Ask.ask(\"q\"))\n"), Nil)

  test("inside its own body, a row variable is rigid"):
    val decls = ask + "fn each(f: () -> u64 with E) -> u64 with E = f()\n"
    assertEquals(codes(decls), Nil)
    assertEquals(codes(ask + "fn bad(f: () -> u64 with E) -> u64 = f()\n"), List(DiagCode.T015))
    assertEquals(
      codes(ask + "fn leak(f: () -> u64 with E) -> u64 with E = Ask.ask(\"q\")\n"),
      List(DiagCode.T015, DiagCode.T017)
    )
