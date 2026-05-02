package fixed.util

/** Severity of a diagnostic. */
enum Severity:
  case Error, Warning, Info

/** A single diagnostic message with location, code, and explanation.
  *
  * Per the project's "Agent-Friendly CLI and Compiler Output" guidelines,
  * every diagnostic carries:
  *  - a numeric error code (e.g. E001)
  *  - a span pointing into the source
  *  - a human-readable explanation
  *  - an optional copy-pasteable suggestion
  */
final case class Diagnostic(
    severity: Severity,
    code: String,
    span: Span,
    message: String,
    suggestion: Option[String] = None
):
  def isError: Boolean = severity == Severity.Error

/** Accumulates diagnostics during a single compilation. Single-threaded;
  * the typer and later phases each have their own Reporter view (or share).
  */
final class Reporter(val source: SourceFile):
  private val buf = scala.collection.mutable.ArrayBuffer.empty[Diagnostic]

  def error(code: String, span: Span, message: String, suggestion: Option[String] = None): Unit =
    buf += Diagnostic(Severity.Error, code, span, message, suggestion)

  def warning(code: String, span: Span, message: String, suggestion: Option[String] = None): Unit =
    buf += Diagnostic(Severity.Warning, code, span, message, suggestion)

  def info(code: String, span: Span, message: String, suggestion: Option[String] = None): Unit =
    buf += Diagnostic(Severity.Info, code, span, message, suggestion)

  /** Append a pre-built diagnostic. Used when a phase (e.g. the parser)
    * accumulates its own diagnostics functionally and the driver later
    * folds them into a shared Reporter. */
  def add(d: Diagnostic): Unit =
    buf += d

  def diagnostics: Seq[Diagnostic] = buf.toSeq

  def hasErrors: Boolean = buf.exists(_.isError)

  def errorCount: Int = buf.count(_.isError)

  /** Format a diagnostic as a single multi-line block suitable for terminals. */
  def format(d: Diagnostic): String =
    val (line, col) = source.lineColumn(d.span.start)
    val sevTag = d.severity match
      case Severity.Error   => "error"
      case Severity.Warning => "warning"
      case Severity.Info    => "info"
    val head = s"${source.path}:$line:$col: $sevTag[${d.code}]: ${d.message}"
    d.suggestion match
      case Some(s) => s"$head\n  hint: $s"
      case None    => head

  /** All diagnostics, formatted, joined by newlines. */
  def formatAll: String = diagnostics.map(format).mkString("\n")
