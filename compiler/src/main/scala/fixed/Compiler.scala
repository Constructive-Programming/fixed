package fixed

import fixed.core.Phase
import fixed.parsing.ParserPhase
import fixed.qtt.QuantityPhase
import fixed.typer.TyperPhase

/** The phase plan. A unit stops at the first phase that leaves it with errors. */
object Compiler:
  val phases: List[Phase] = List(ParserPhase, TyperPhase, QuantityPhase)

  def compile(unit: CompilationUnit): CompilationUnit =
    phases.foldLeft(unit)((u, phase) => if u.hasErrors then u else phase.run(u))
