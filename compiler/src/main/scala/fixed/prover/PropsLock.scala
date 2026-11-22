package fixed.prover

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import fixed.CompilationUnit

/** The record of statuses, `props.lock` (`spec/properties.md` Rule P7.1.b), beside the source file.
  *
  * One line per prop, sorted by name: the source file and the prop's qualified name, its status (`proved` or `open`),
  * the steps used, its key, the prover's version, the budget, and for an open prop the reason. A refuted prop is an
  * error and has no status, so it has no line. Today a package is one source file, and several may share a directory, so
  * one `props.lock` holds the lines of every file beside it, each name prefixed by its file.
  *
  * The key of a prop in the fragment is a hash of its obligation: the binders with their types, the range goals and the
  * body, which is everything the prover sees; the prover's version (which also names the version of the translation in
  * `Prover`) and the budget are compared beside it. The fragment references no declaration (a name that is not a binder
  * leaves the prop outside), so no other hash enters. A prop outside the fragment has the key `-`: no prover call is
  * saved for it. Lines this compile does not own, including ones it cannot read, are kept as they are.
  */
object PropsLock:
  /** A recorded line. */
  final case class Entry(name: String, result: Result, key: String, prover: String, budget: Long):
    def line: String =
      val (status, reason) = result match
        case Result.Proved(_)     => ("proved", "")
        case Result.Open(why, _)  => ("open", " " + why.replaceAll("\\s+", " "))
        case Result.Refuted(_, _) => ("failed", "")
      s"$name $status ${result.steps} $key $prover $budget$reason"

  /** The key of an obligation: its text hashed with SHA-256, cut to 128 bits. */
  def key(ob: Obligation): String =
    HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(ob.toString.getBytes(UTF_8))).take(32)

  /** The recorded results of `unit`'s props, by qualified name, for the prover's version and `budget`. */
  def recorded(unit: CompilationUnit): Map[String, (String, Result)] =
    val prefix = s"${fileOf(unit)}:"
    lines(unit).flatMap(parse).collect {
      case e if e.name.startsWith(prefix) && e.prover == Prover.version && e.budget == unit.proverSteps =>
        e.name.stripPrefix(prefix) -> (e.key, e.result)
    }.toMap

  /** `unit`, after rewriting the lock with its statuses and keeping every other line. Nothing is written when the
    * prover did not run (an earlier phase failed), or when there is no lock and no prop. A lock that cannot be written
    * is a warning on the standard error, not a failure of the compile.
    */
  def record(unit: CompilationUnit): CompilationUnit =
    path(unit).filter(_ => !unit.hasErrors || unit.props.nonEmpty).foreach { p =>
      val prefix = s"${fileOf(unit)}:"
      val mine = unit.props.flatMap { v =>
        v.result match
          case _: Result.Refuted => None
          case r                 => Some(Entry(prefix + v.name, r, v.key, Prover.version, unit.proverSteps).line)
      }
      val kept = lines(unit).filterNot(l => l == header || l.startsWith(prefix))
      if mine.nonEmpty || Files.exists(p) then
        try
          val _ = Files.writeString(p, (header :: (kept ++ mine).sorted).mkString("", "\n", "\n"), UTF_8)
        catch case NonFatal(e) => Console.err.println(s"warning: cannot write $p: $e")
    }
    unit

  private val header = "# props.lock: name status steps key prover budget [reason] (spec/properties.md Rule P7.1.b)"

  private def fileOf(unit: CompilationUnit): String = Path.of(unit.source.path).getFileName.toString

  /** The lock beside `unit`'s source, when the source is a file. */
  private def path(unit: CompilationUnit): Option[Path] =
    Some(Path.of(unit.source.path).toAbsolutePath).filter(Files.isRegularFile(_)).map(_.resolveSibling("props.lock"))

  /** The lock's lines; none when it does not exist or cannot be read. */
  private def lines(unit: CompilationUnit): List[String] =
    path(unit).filter(Files.exists(_)).toList.flatMap { p =>
      try Files.readAllLines(p, UTF_8).asScala.toList
      catch case NonFatal(_) => Nil
    }

  private def parse(line: String): Option[Entry] =
    line.split(" ", 7).toList match
      case name :: status :: steps :: key :: prover :: budget :: rest if !line.startsWith("#") =>
        for
          n <- steps.toLongOption
          b <- budget.toLongOption
          r <- status match
            case "proved" => Some(Result.Proved(n))
            case "open"   => Some(Result.Open(rest.mkString, n))
            case _        => None
        yield Entry(name, r, key, prover, b)
      case _ => None
