package fix

import scala.meta.Position
import metaconfig.Configured
import scalafix.lint.LintSeverity
import scalafix.v1.*

/** Warns when a file exceeds `warnLines` physical lines and fails above `errorLines`. */
class FileLength(warnLines: Int, errorLines: Int) extends SyntacticRule("FileLength"):
  def this() = this(800, 1000)

  override def withConfiguration(config: Configuration): Configured[Rule] =
    Configured.ok(
      FileLength(
        RuleConf.int(config, "FileLength", "warnLines", 800),
        RuleConf.int(config, "FileLength", "errorLines", 1000)
      )
    )

  override def fix(using doc: SyntacticDocument): Patch =
    val input = doc.input
    val lines = input.text.linesIterator.size
    def lineStart(limit: Int) =
      val offset = input.text.linesWithSeparators.take(limit).map(_.length).sum
      Position.Range(input, offset, offset)
    if lines > errorLines then
      val msg = s"file has $lines lines (max $errorLines); split it"
      Patch.lint(Diagnostic("", msg, lineStart(errorLines), "", LintSeverity.Error))
    else if lines > warnLines then
      val msg = s"file has $lines lines (soft max $warnLines); consider splitting it"
      Patch.lint(Diagnostic("", msg, lineStart(warnLines), "", LintSeverity.Warning))
    else Patch.empty
