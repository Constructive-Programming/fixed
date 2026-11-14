package fix

import scala.meta.tokens.Token
import metaconfig.Configured
import scalafix.v1.*

/** Only scaladoc (`/** */`) comments are allowed, plus comments whose text starts with an allowed prefix. */
class ScaladocOnly(allowedPrefixes: List[String]) extends SyntacticRule("ScaladocOnly"):
  def this() = this(List("scalafix:"))

  override def withConfiguration(config: Configuration): Configured[Rule] =
    Configured.ok(ScaladocOnly(RuleConf.strings(config, "ScaladocOnly", "allowedPrefixes", List("scalafix:"))))

  override def fix(using doc: SyntacticDocument): Patch =
    doc.tokens.collect {
      case c: Token.Comment if !isScaladoc(c) && !allowedPrefixes.exists(c.value.trim.startsWith) =>
        Patch.lint(Diagnostic("", "only scaladoc (/** */) comments are allowed", c.pos))
    }.asPatch

  private def isScaladoc(c: Token.Comment): Boolean =
    c.text.startsWith("/**") && !c.text.startsWith("/**/")
