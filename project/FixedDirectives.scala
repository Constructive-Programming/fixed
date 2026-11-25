package fixed.build

import cats.data.NonEmptySet
import cats.syntax.all._
import laika.api.bundle.{BlockDirectives, DirectiveRegistry}
import laika.ast.{Block, BlockSequence, RawContent}

/** Laika directives for the Fixed site.
  *
  * `@:details(title)` wraps its body in an HTML `<details>` element, folded
  * shut by default. Markdown in the body is parsed normally, unlike a raw
  * HTML `<details>` block, which Laika's HTML parser treats as verbatim
  * content. The spec pages use it to move their status and change history to
  * the end of the document without losing the markup.
  */
object FixedDirectives extends DirectiveRegistry {

  private def escape(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")

  private val html: NonEmptySet[String] = NonEmptySet.of("html")

  val blockDirectives: Seq[BlockDirectives.Directive] = Seq(
    BlockDirectives.create("details") {
      import laika.api.bundle.BlockDirectives.dsl._
      (attribute(0).as[String], parsedBody).mapN { (title, body) =>
        BlockSequence(
          RawContent(html, "<details>"),
          RawContent(html, "<summary>" + escape(title) + "</summary>"),
          BlockSequence(body: Seq[Block]),
          RawContent(html, "</details>")
        )
      }
    }
  )

  val spanDirectives: Seq[laika.api.bundle.SpanDirectives.Directive] = Nil
  val templateDirectives: Seq[laika.api.bundle.TemplateDirectives.Directive] = Nil
  val linkDirectives: Seq[laika.api.bundle.LinkDirectives.Directive] = Nil
}
