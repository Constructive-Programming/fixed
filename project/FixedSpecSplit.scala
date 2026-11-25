package fixed.build

import cats.data.Kleisli
import cats.effect.IO
import laika.ast._
import laika.ast.Path.Root
import laika.io.model.ParsedTree
import laika.theme.Theme.TreeProcessor

import scala.collection.mutable.ListBuffer

/** Splits each spec document into one page per top-level `##` section.
  *
  * Sections with at most three top-level blocks stay on the previous page.
  * The original `spec/<name>.md` remains as an index page with a table of
  * contents and the folded status/changes block; the section pages live under
  * `/<name>/`. The `spec/` sources stay untouched.
  */
object FixedSpecSplit {

  final private case class Page(blocks: Seq[Block], path: Path, title: String)

  def processTree: OutputContext => TreeProcessor[IO] = _ =>
    Kleisli[IO, ParsedTree[IO], ParsedTree[IO]] { tree => IO.pure(split(tree)) }

  private def split(tree: ParsedTree[IO]): ParsedTree[IO] =
    tree.modifyTree { root =>
      val newContent = root.content.flatMap {
        case doc: Document if isSpec(doc.path) => splitDocument(doc)
        case other                             => Seq(other)
      }
      addPrevNext(root.replaceContent(newContent))
    }

  private def isSpec(path: Path): Boolean =
    path.parent == Root && FixedSite.specIndexes.contains(path.name)

  /** Appends the previous/next paragraph to every page, in reading order. */
  private def addPrevNext(root: DocumentTree): DocumentTree = {
    val order: Seq[(Path, String)] = FixedSite.readingOrder.flatMap { page =>
      val index = (Root / page.path, page.title)
      val sections =
        if (FixedSite.specIndexes.contains(page.path))
          sectionPages(root, page.path.stripSuffix(".md"))
        else Nil
      index +: sections
    }

    val navByPath: Map[String, Paragraph] = order.zipWithIndex.map { case ((path, _), i) =>
      val prev = if (i > 0) Some(order(i - 1)) else None
      val next = if (i + 1 < order.size) Some(order(i + 1)) else None
      path.toString -> navParagraph(prev, next)
    }.toMap

    root.modifyDocumentsRecursively { doc =>
      navByPath.get(doc.path.toString) match {
        case Some(nav) => doc.withContent(doc.content.withContent(doc.content.content :+ nav))
        case None      => doc
      }
    }
  }

  private def sectionPages(root: DocumentTree, spec: String): Seq[(Path, String)] =
    root.allDocuments
      .filter(d => d.path.parent.name == spec && d.path.name != "index.md")
      .sortBy(_.path.name)
      .map(d => (d.path, d.title.map(_.extractText).getOrElse(d.path.name)))

  private def navParagraph(prev: Option[(Path, String)], next: Option[(Path, String)]): Paragraph = {
    def link(page: (Path, String), text: String): SpanLink =
      SpanLink.internal(page._1)(Text(text))

    val links = Seq(
      prev.map(p => link(p, "← " + p._2)).getOrElse(SpanSequence.empty),
      next.map(p => link(p, p._2 + " →")).getOrElse(SpanSequence.empty)
    )
    Paragraph(links, Styles("prev-next"))
  }

  private def splitDocument(doc: Document): Seq[TreeContent] = {
    val blocks = doc.content.content
    val titleBlock = blocks.headOption.collect {
      case t: Title  => t
      case h: Header => h
    }

    titleBlock match {
      case None        => Seq(doc)
      case Some(title) =>
        val body = blocks.drop(1)
        val preamble = body.filterNot(_.isInstanceOf[Section])
        val (sections, status) = extractStatus(body.collect { case s: Section => s })
        val pages = group(doc.path.basename, sections)

        val pageDocs = pages.map { page =>
          Document(page.path, RootElement(page.blocks)).withConfig(doc.config)
        }

        val bullet = BulletFormat.StringBullet("*")
        val tocItems = pages.map { p =>
          BulletListItem(
            Seq(Paragraph(SpanLink.internal(p.path)(Text(p.title)))),
            bullet,
            Options.empty
          )
        }
        val toc = BulletList(tocItems, bullet, Options.empty)

        val indexRoot = RootElement(
          Seq(title) ++ preamble ++ Seq(Header(2, "Contents"), toc) ++ status
        )

        val built = DocumentTree.builder.addDocuments(pageDocs.toList).build(doc.config)
        val sectionTree = built.selectSubtree(doc.path.basename).getOrElse(built)

        Seq(doc.withContent(indexRoot), sectionTree)
    }
  }

  /** The status/changes block is the tail of the last section's content. */
  private def extractStatus(sections: Seq[Section]): (Seq[Section], Seq[Block]) = {
    val at = sections.lastIndexWhere(s => s.content.exists(hasDetails))
    if (at < 0) (sections, Nil)
    else {
      val section = sections(at)
      val detailAt = section.content.indexWhere(hasDetails)
      val status = section.content.drop(detailAt)
      val remaining = section.withContent(section.content.take(detailAt))
      (sections.updated(at, remaining), status)
    }
  }

  private def blockCount(blocks: Seq[Block]): Int =
    blocks.map {
      case s: Section => blockCount(s.content)
      case _: Header  => 0
      case _          => 1
    }.sum

  private def hasDetails(block: Block): Boolean = block match {
    case r: RawContent          => r.content.contains("<details>")
    case c: ElementContainer[_] =>
      c.collect { case r: RawContent if r.content.contains("<details>") => r }.nonEmpty
    case _ => false
  }

  private def group(spec: String, sections: Seq[Section]): Seq[Page] = {
    val groups = ListBuffer.empty[List[Section]]
    sections.foreach { section =>
      if (blockCount(section.content) <= 3 && groups.nonEmpty)
        groups(groups.length - 1) = groups.last :+ section
      else
        groups += List(section)
    }
    groups.toList.zipWithIndex.map { case (group, index) =>
      val first = group.head
      val title = first.header.extractText
      val label = if (group.size > 1) title + " …" else title
      val pageBlocks = Seq(Title(first.header.content, Styles("title"))) ++
        first.content ++
        group.tail.map(s => Section(s.header.copy(level = 2), s.content, s.options))
      Page(pageBlocks, Root / spec / fileName(title, index + 1), label)
    }
  }

  private def fileName(title: String, index: Int): String = {
    val numbered = "^([0-9]+)\\.\\s*(.*)$".r.findFirstMatchIn(title)
    val (number, rest) = numbered match {
      case Some(m) => (f"${m.group(1).toInt}%02d", m.group(2))
      case None    => (f"$index%02d", title)
    }
    val slug = rest
      .toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
    s"$number-${if (slug.isEmpty) "section" else slug}.md"
  }
}
