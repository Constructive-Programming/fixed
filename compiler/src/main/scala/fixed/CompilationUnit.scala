package fixed

import fixed.ast.Tree
import fixed.core.SymbolTable
import fixed.util.{Diagnostic, SourceFile}

/** One source file moving through the phase pipeline: its tree (absent until `ParserPhase` runs), its symbols (filled by the Namer)
  * and the diagnostics every phase so far has emitted, in emission order.
  */
final case class CompilationUnit(
    source: SourceFile,
    tree: Option[Tree] = None,
    symbols: SymbolTable = SymbolTable.empty,
    diagnostics: List[Diagnostic] = Nil
):
  def hasErrors: Boolean = diagnostics.exists(_.isError)

  /** This unit with `more` appended to its diagnostics. */
  def report(more: List[Diagnostic]): CompilationUnit = copy(diagnostics = diagnostics ++ more)
