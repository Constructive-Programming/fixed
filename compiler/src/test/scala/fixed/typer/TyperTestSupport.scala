package fixed.typer

import fixed.ast.Tree
import fixed.core.SymbolTable
import fixed.parsing.Parser
import fixed.util.{DiagCode, SourceFile}

/** Shared helpers for the typer suites. */
trait TyperTestSupport:
  this: munit.FunSuite =>

  /** Parse and index `code`, which must have no parse or namer error. */
  protected def indexed(code: String): (Tree, SymbolTable) =
    val unit = Parser.parse(SourceFile.fromString("<unit>", code))
    assert(!unit.hasErrors, unit.diagnostics)
    val (table, namerDiags) = Namer.index(unit.tree)
    assertEquals(namerDiags, Nil)
    (unit.tree, table)

  /** Index and body-type a whole unit, as `TyperPhase` does. */
  protected def typedUnit(code: String): TyperState =
    val (tree, table) = indexed(code)
    TyperPhase.typeBodies(tree, table)

  protected def codes(s: TyperState): List[DiagCode] = s.diags.map(_.code)
