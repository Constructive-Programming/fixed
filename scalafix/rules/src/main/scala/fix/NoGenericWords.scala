package fix

import scala.meta.*
import metaconfig.Configured
import scalafix.v1.*

/** Flags defined names containing a banned generic word (e.g. `grainSelection`, `ParseStageLeg`).
  * Names split into words on camel/Pascal case, acronyms, digits and `_`; a word matches a banned word
  * or its plural. Only definition sites are checked; `allowedNames` exempts exact names.
  */
class NoGenericWords(words: Set[String], allowedNames: Set[String]) extends SyntacticRule("NoGenericWords"):
  def this() = this(NoGenericWords.defaultWords, Set.empty)

  override def withConfiguration(config: Configuration): Configured[Rule] =
    Configured.ok(
      NoGenericWords(
        RuleConf.strings(config, "NoGenericWords", "words", NoGenericWords.defaultWords.toList).map(_.toLowerCase).toSet,
        RuleConf.strings(config, "NoGenericWords", "allowedNames", Nil).toSet
      )
    )

  override def fix(using doc: SyntacticDocument): Patch =
    doc.tree
      .collect {
        case m: Member                  => List(m.name)
        case r: Defn.RepeatedEnumCase => r.cases
      }
      .flatten
      .filterNot(n => n.value.isEmpty || allowedNames(n.value))
      .distinctBy(_.pos.start)
      .flatMap(n => bannedWordIn(n.value).map(w => Patch.lint(Diagnostic("", message(n.value, w), n.pos))))
      .asPatch

  private def bannedWordIn(name: String): Option[String] =
    NoGenericWords.split(name).find(w => words(w) || (w.endsWith("s") && words(w.dropRight(1))))

  private def message(name: String, word: String) =
    s"`$name` contains the generic word `$word`; name what it is"

object NoGenericWords:
  val defaultWords: Set[String] = Set("grain", "leg", "arm", "stuff", "helper", "thing")

  private val wordPattern = "[A-Z]+(?=[A-Z][a-z]|[0-9_]|$)|[A-Z]?[a-z]+|[A-Z]+|[0-9]+".r

  /** Lower-cased words of an identifier: `HTTPLegCount` -> http, leg, count. */
  def split(name: String): List[String] = wordPattern.findAllIn(name).map(_.toLowerCase).toList
