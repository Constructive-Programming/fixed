package fixed.parsing

import fixed.ast.Tree
import fixed.util.Diagnostic

/** The functional result of a parse. The parser does not mutate any
  * caller-supplied Reporter; instead it returns its tree, the
  * diagnostics it accumulated, and the trivia it preserved.
  *
  * `tree` is always present, even on heavily-broken input — it may
  * contain `Trees.Error` / `Trees.Missing` gap nodes (introduced in
  * M2) standing in for productions that failed.
  *
  * `diagnostics` is in source order.
  *
  * `trivia` is empty in M1; the scanner starts populating it in M4.
  *
  * See `docs/plans/phase-2.1-incremental-parser.md` §2.
  */
final case class ParseResult(
    tree: Tree,
    diagnostics: List[Diagnostic],
    trivia: TriviaTable
):
  def hasErrors: Boolean = diagnostics.exists(_.isError)
