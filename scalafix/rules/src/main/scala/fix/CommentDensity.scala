package fix

import scala.meta.*
import scala.meta.tokens.Token
import metaconfig.Configured
import scalafix.v1.*

/** Fails when comment-only lines exceed `maxPercent` of the file's non-blank lines. */
class CommentDensity(maxPercent: Int) extends SyntacticRule("CommentDensity"):
  def this() = this(30)

  override def withConfiguration(config: Configuration): Configured[Rule] =
    Configured.ok(CommentDensity(RuleConf.int(config, "CommentDensity", "maxPercent", 30)))

  override def fix(using doc: SyntacticDocument): Patch =
    val tokens = doc.tokens.toList
    def lines(t: Token) = t.pos.startLine to t.pos.endLine
    val commentLines = tokens.collect { case c: Token.Comment => lines(c) }.flatten.toSet
    val code = tokens.filterNot(t => t.is[Token.Trivia] || t.is[Token.BOF] || t.is[Token.EOF])
    val codeLines = code.flatMap(lines).toSet
    val nonBlank = (commentLines ++ codeLines).size
    val commentOnly = (commentLines -- codeLines).size
    code.headOption match
      case Some(first) if nonBlank > 0 && commentOnly * 100 > maxPercent * nonBlank =>
        val pct = commentOnly * 100 / nonBlank
        val msg = s"$pct% of non-blank lines are comments (max $maxPercent%)"
        Patch.lint(Diagnostic("", msg, first.pos))
      case _ => Patch.empty
