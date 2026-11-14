package fixed

import fixed.core.Phase
import fixed.parsing.ParserPhase
import fixed.typer.TyperPhase

/** The phase plan. Inner lists are groups that may later be fused into one tree walk; today each
  * group holds one phase. A unit stops at the first phase group that leaves it with errors.
  */
object Compiler:
  val phases: List[List[Phase]] = List(
    List(ParserPhase),
    List(TyperPhase)
  )

  def compile(unit: CompilationUnit): CompilationUnit =
    phases.flatten.foldLeft(unit)((u, phase) => if u.hasErrors then u else phase.run(u))
