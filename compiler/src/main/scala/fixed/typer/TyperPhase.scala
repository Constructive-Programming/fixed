package fixed.typer

import fixed.CompilationUnit
import fixed.core.Phase
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Namer + Typer. M2 runs the Namer (signature pass); body typing (M3–M5) is not implemented yet
  * and is reported as an info diagnostic.
  */
object TyperPhase extends Phase:
  val name: String = "typer"

  def run(unit: CompilationUnit): CompilationUnit =
    unit.tree.fold(unit) { tree =>
      val (symbols, diagnostics) = Namer.index(tree)
      unit
        .copy(symbols = symbols)
        .report(diagnostics :+ Diagnostic(Severity.Info, DiagCode.T000, Span.NoSpan, "body typing not yet implemented"))
    }
