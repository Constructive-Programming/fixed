package fixed.parsing

import fixed.util.SourceFile
import munit.FunSuite

import scala.util.matching.Regex

/** Phase 2.1 M4 — trivia retention.
  *
  * Validates that line comments and blank lines are captured in the
  * `TriviaTable` and keyed on the start offset of the next real token.
  */
class TriviaSuite extends FunSuite:

  private def parse(input: String): ParseResult =
    Parser.parse(SourceFile.fromString("<test>", input))

  // ---- Hand-crafted shape tests ----

  test("leading line comment is recorded against the first real token"):
    val src = """// header
                |use Foo
                |""".stripMargin
    val pr = parse(src)
    val firstTokenStart = src.indexOf("use")
    val leading = pr.trivia.leadingFor(firstTokenStart)
    assertEquals(leading.size, 1)
    leading.head match
      case Trivia.LineComment(_, text) => assertEquals(text, "// header")
      case other => fail(s"expected LineComment, got $other")

  test("blank line between two decls is recorded against the second decl"):
    val src = """fn a() -> u64 = 1
                |
                |fn b() -> u64 = 2
                |""".stripMargin
    val pr = parse(src)
    val fnBStart = src.indexOf("fn b")
    val leading = pr.trivia.leadingFor(fnBStart)
    val blanks = leading.collect { case b: Trivia.BlankLines => b }
    assertEquals(blanks.size, 1, s"expected one BlankLines event, got $leading")
    assertEquals(blanks.head.count, 1, "one blank line between the two fns")

  test("comment + blank line + comment all attach to the next real token"):
    val src = """// first
                |// second
                |
                |// third
                |fn a() -> u64 = 1
                |""".stripMargin
    val pr = parse(src)
    val fnStart = src.indexOf("fn a")
    val leading = pr.trivia.leadingFor(fnStart)
    val comments = leading.collect { case c: Trivia.LineComment => c.text }
    val blanks = leading.collect { case b: Trivia.BlankLines => b }
    assertEquals(comments, List("// first", "// second", "// third"))
    assertEquals(blanks.size, 1, s"expected one blank-lines event, got $leading")

  test("trivia between method-call dots stays attached to the next ident"):
    // `.concat` chained over multiple lines — the comment should attach
    // to `concat`, not be silently dropped.
    val src = """fn f(s: String) -> String =
                |    s
                |        // a chained call
                |        .concat("x")
                |""".stripMargin
    val pr = parse(src)
    val concatStart = src.indexOf("concat")
    // The dot precedes `concat` immediately, so leading-trivia keys on
    // the dot's start offset, not concat's. Walk both candidates.
    val dotStart = src.indexOf(".concat")
    val merged = pr.trivia.leadingFor(dotStart) ++ pr.trivia.leadingFor(concatStart)
    val comments = merged.collect { case c: Trivia.LineComment => c.text }
    assertEquals(comments, List("// a chained call"))

  test("source with no comments and no blank lines yields an empty table"):
    val pr = parse("fn id(x: u64) -> u64 = x\n")
    assert(pr.trivia.isEmpty)

  // ---- Coverage check across examples ----

  test("trivia table captures every // comment in every example file"):
    val files = examplePaths()
    assume(files.nonEmpty, "examples/ not found — run from repo root")
    val LineCommentRe: Regex = """//[^\n]*""".r
    var totalSourceComments = 0
    var totalCapturedComments = 0
    for path <- files do
      val src = SourceFile.fromPath(path)
      val pr = Parser.parse(src)
      val sourceComments = LineCommentRe.findAllIn(src.content).toList
      val capturedComments =
        pr.trivia.all.flatMap(_._2).collect { case c: Trivia.LineComment => c }.toList
      totalSourceComments += sourceComments.size
      totalCapturedComments += capturedComments.size
      // Per-file assertion: every comment in the source is captured.
      // (We compare counts; deeper text equality is asserted on a
      // smaller hand-crafted input above.)
      assertEquals(
        capturedComments.size,
        sourceComments.size,
        s"$path: comment count mismatch (source $sourceComments vs captured $capturedComments)"
      )
    // Sanity: the corpus has comments to capture; if we ever drop to 0
    // something has clearly broken upstream.
    assert(totalSourceComments > 0)
    assertEquals(totalCapturedComments, totalSourceComments)

  test("trivia table captures every blank-line run in every example file"):
    val files = examplePaths()
    assume(files.nonEmpty, "examples/ not found — run from repo root")
    val BlankRunRe: Regex = """\n{2,}""".r
    for path <- files do
      val src = SourceFile.fromPath(path)
      val pr = Parser.parse(src)
      val sourceRuns = BlankRunRe.findAllMatchIn(src.content).toList
      val capturedRuns =
        pr.trivia.all.flatMap(_._2).collect { case b: Trivia.BlankLines => b }.toList
      assertEquals(
        capturedRuns.size,
        sourceRuns.size,
        s"$path: blank-run count mismatch (source ${sourceRuns.size} vs captured ${capturedRuns.size})"
      )

  // ---- Round-trip: reconstruct source from real-token spans + trivia ----

  test("byte-equivalent reconstruction of every example modulo inline whitespace"):
    val files = examplePaths()
    assume(files.nonEmpty, "examples/ not found — run from repo root")
    for path <- files do
      val src = SourceFile.fromPath(path)
      // Re-tokenize directly so we get every real token's full span.
      val rep = new fixed.util.Reporter(src)
      val scanner = new Scanner(src, rep)
      val tokens = scanner.tokenize().filter(t => t.kind match
        case TokenKind.Indent | TokenKind.Dedent | TokenKind.Newline | TokenKind.Eof => false
        case _ => true
      )
      val trivia = scanner.triviaTable
      val rebuilt = reconstruct(src.content, tokens, trivia)
      // Normalise inline whitespace: collapse runs of spaces/tabs that
      // are NOT at the start of a line. Comments, newlines, and indents
      // are preserved exactly.
      assertEquals(
        normaliseInlineWs(rebuilt),
        normaliseInlineWs(src.content),
        s"$path: reconstructed source diverges from original after inline-ws normalisation"
      )

  // Pull every real token's source slice plus its leading trivia.
  // Inline whitespace is whatever lies in the gap and is *not* trivia;
  // we replace it with a single space below in `normaliseInlineWs`.
  private def reconstruct(
      content: String,
      tokens: Seq[Token],
      trivia: TriviaTable
  ): String =
    val sb = new StringBuilder
    var prevEnd = 0
    for tok <- tokens do
      val gapStart = prevEnd
      val gapEnd = tok.span.start
      // Emit any leading trivia for this token, in source order, with
      // the inter-event whitespace lifted from the original gap.
      val leading = trivia.leadingFor(tok.span.start)
      if leading.nonEmpty then
        var cursor = gapStart
        for ev <- leading do
          val (start, end) = ev match
            case Trivia.LineComment(s, _) => (s.start, s.end)
            case Trivia.BlankLines(s, _) => (s.start, s.end)
          // Whitespace before the trivia event.
          sb.append(content.substring(cursor, start))
          sb.append(content.substring(start, end))
          cursor = end
        sb.append(content.substring(cursor, gapEnd))
      else
        sb.append(content.substring(gapStart, gapEnd))
      sb.append(content.substring(tok.span.start, tok.span.end))
      prevEnd = tok.span.end
    sb.append(content.substring(prevEnd))  // tail (typically a final \n)
    sb.toString

  // Replace runs of horizontal spaces/tabs that follow non-whitespace
  // on the same line with a single space. Leading-line whitespace
  // (indentation) is left untouched. This lets the round-trip ignore
  // *inline* whitespace which we deliberately don't capture as trivia.
  private def normaliseInlineWs(s: String): String =
    val sb = new StringBuilder
    var i = 0
    var atLineStart = true
    while i < s.length do
      val c = s.charAt(i)
      if c == '\n' then
        sb.append('\n'); atLineStart = true; i += 1
      else if (c == ' ' || c == '\t') && !atLineStart then
        // Collapse a run of inline spaces to one.
        while i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\t') do
          i += 1
        sb.append(' ')
      else
        sb.append(c); atLineStart = false; i += 1
    sb.toString

  private def examplePaths(): List[String] =
    val dir = new java.io.File("examples")
    if !dir.isDirectory then Nil
    else dir.listFiles().toList
      .filter(f => f.isFile && f.getName.endsWith(".fixed"))
      .map(_.getPath)
      .sorted

end TriviaSuite
