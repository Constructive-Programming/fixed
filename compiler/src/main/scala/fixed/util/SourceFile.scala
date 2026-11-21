package fixed.util

import scala.collection.Searching

/** A loaded source file: bytes + path + lazy line-offset table.
  *
  * Spans into a SourceFile are character offsets (0-indexed). Line/column
  * conversion is on demand. A `library` file's spans name it (`Span.file`).
  */
final class SourceFile(val path: String, val content: String, val library: Boolean = false):

  /** Offsets of the first character of each line. Line N (1-indexed) starts
    * at offsets(N - 1). offsets(0) == 0. The table includes a synthetic
    * entry one past the end so `lineOf(content.length)` is the last line.
    */
  lazy val lineOffsets: IndexedSeq[Int] =
    (0 +: content.indices.filter(content.charAt(_) == '\n').map(_ + 1)) :+ (content.length + 1)

  /** Returns the (line, column) pair for an offset, both 1-indexed. */
  def lineColumn(offset: Int): (Int, Int) =
    require(offset >= 0 && offset <= content.length, s"offset $offset out of bounds for $path")

    /** Binary search the line: last table entry <= offset. */
    val table = lineOffsets
    val lo = table.search(offset) match
      case Searching.Found(i)          => i
      case Searching.InsertionPoint(i) => i - 1
    val line = lo + 1
    val column = offset - table(lo) + 1
    (line, column)

  /** Returns the (1-indexed) line for an offset. */
  def lineOf(offset: Int): Int = lineColumn(offset)._1

  /** Returns the substring at the given span. */
  def slice(start: Int, end: Int): String =
    require(
      start >= 0 && end >= start && end <= content.length,
      s"bad span [$start, $end] for $path of length ${content.length}"
    )
    content.substring(start, end)

  /** Total length in characters. */
  def length: Int = content.length

object SourceFile:
  def fromString(path: String, content: String): SourceFile =
    new SourceFile(path, content)

  def fromPath(path: String): SourceFile =
    val content = scala.io.Source.fromFile(path).mkString
    new SourceFile(path, content)

/** A half-open span [start, end) into `file` (a library file) or else the unit's. */
final case class Span(start: Int, end: Int, file: Option[SourceFile] = None):
  require(start >= 0 && end >= start, s"invalid span [$start, $end]")

  def length: Int = end - start

  def union(other: Span): Span = copy(start = start min other.start, end = end max other.end)

  def contains(offset: Int): Boolean = offset >= start && offset < end

object Span:
  val NoSpan: Span = Span(0, 0)
