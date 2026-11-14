package fixed.typer

import fixed.CompilationUnit
import fixed.core.Phase
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Placeholder for the Namer + Typer subphases (Phase 3 M2–M5): reports that typing is not yet
  * implemented and leaves the unit unchanged otherwise.
  */
object TyperPhase extends Phase:
  val name: String = "typer"

  def run(unit: CompilationUnit): CompilationUnit =
    unit.report(List(Diagnostic(Severity.Info, DiagCode.T000, Span.NoSpan, "typer not yet implemented")))
