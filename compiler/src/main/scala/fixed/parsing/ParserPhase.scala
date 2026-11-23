package fixed.parsing

import fixed.CompilationUnit
import fixed.core.Phase

/** Runs the parser over a unit's source, attaching the tree and the parse diagnostics. */
object ParserPhase extends Phase:
  def run(unit: CompilationUnit): CompilationUnit =
    val result = Parser.parse(unit.source)
    unit.copy(tree = Some(result.tree)).report(result.diagnostics)
