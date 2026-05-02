package fixed.parsing

import fixed.util.Span

/** Trivia attached to a token: what the scanner skipped immediately
  * before the token's start offset. Trailing trivia is owned by the
  * *next* token's leading trivia — there is no separate "trailing" slot
  * in v0.4.7 (rust-analyzer convention; keeps lookups O(1)).
  *
  * Inline whitespace between tokens on the same line is NOT recorded
  * here — it's reconstructable from `source.slice(prevEnd, nextStart)`
  * minus the recorded trivia. Only `LineComment` and `BlankLines` are
  * preserved, because those are what tooling (formatters, LSP hover,
  * doc generators) need to recover.
  */
enum Trivia:
  case LineComment(span: Span, text: String)
  case BlankLines(span: Span, count: Int)

/** Side table holding trivia keyed by the start offset of the token
  * they precede. Empty in M1 — the scanner doesn't yet emit trivia
  * events; M4 wires that up. The shape is fixed now so callers (the
  * parser API, eventual LSP/formatter code) can depend on it.
  */
final class TriviaTable private (
    private val byTokenStart: Map[Int, List[Trivia]]
):
  def leadingFor(tokenStart: Int): List[Trivia] =
    byTokenStart.getOrElse(tokenStart, Nil)

  def all: Iterable[(Int, List[Trivia])] = byTokenStart

  def isEmpty: Boolean = byTokenStart.isEmpty

object TriviaTable:
  val empty: TriviaTable = new TriviaTable(Map.empty)

  /** Construct from a fully-built map. Used by the scanner once it
    * starts emitting trivia events (M4). */
  def from(byTokenStart: Map[Int, List[Trivia]]): TriviaTable =
    new TriviaTable(byTokenStart)
