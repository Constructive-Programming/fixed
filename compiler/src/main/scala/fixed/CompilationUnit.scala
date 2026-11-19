package fixed

import fixed.ast.Tree
import fixed.core.{SymbolTable, Type}
import fixed.typer.TyperState
import fixed.util.{Diagnostic, SourceFile}

/** One source file moving through the phase pipeline: its tree (absent until `ParserPhase` runs), its symbols (filled by the Namer),
  * the typer's finished state (absent until `TyperPhase` runs; the backend reads types from it) and the diagnostics every phase so
  * far has emitted, in emission order.
  */
final case class CompilationUnit(
    source: SourceFile,
    tree: Option[Tree] = None,
    symbols: SymbolTable = SymbolTable.empty,
    diagnostics: List[Diagnostic] = Nil,
    typing: Option[TyperState] = None
):
  def hasErrors: Boolean = diagnostics.exists(_.isError)

  /** The type the typer recorded for `tree`, solved variables substituted. */
  def typeOf(tree: Tree): Option[Type] = typing.flatMap(_.typeOf(tree))

  /** This unit with `more` appended to its diagnostics. */
  def report(more: List[Diagnostic]): CompilationUnit = copy(diagnostics = diagnostics ++ more)
