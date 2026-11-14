package fixed.parsing

import fixed.ast.Tree
import fixed.util.Diagnostic

/** A parse's result: the tree (always present; failed productions become `Trees.Error` /
  * `Trees.Missing` gap nodes), its diagnostics in source order, and the preserved trivia.
  */
final case class ParseResult(
    tree: Tree,
    diagnostics: List[Diagnostic],
    trivia: TriviaTable
):
  def hasErrors: Boolean = diagnostics.exists(_.isError)
