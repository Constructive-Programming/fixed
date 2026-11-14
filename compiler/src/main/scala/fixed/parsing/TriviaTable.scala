package fixed.parsing

import fixed.util.Span

/** What the scanner skipped before a token: line comments and blank-line runs (what formatters and
  * LSP tooling need). Trailing trivia belongs to the next token, keeping lookups O(1); inline
  * whitespace is recoverable from token spans.
  */
enum Trivia:
  case LineComment(span: Span, text: String)
  case BlankLines(span: Span, count: Int)

/** Trivia keyed by the start offset of the token it precedes. */
final class TriviaTable private (
    private val byTokenStart: Map[Int, List[Trivia]]
):
  def leadingFor(tokenStart: Int): List[Trivia] =
    byTokenStart.getOrElse(tokenStart, Nil)

  def all: Iterable[(Int, List[Trivia])] = byTokenStart

  def isEmpty: Boolean = byTokenStart.isEmpty

object TriviaTable:
  val empty: TriviaTable = new TriviaTable(Map.empty)

  def from(byTokenStart: Map[Int, List[Trivia]]): TriviaTable =
    new TriviaTable(byTokenStart)
