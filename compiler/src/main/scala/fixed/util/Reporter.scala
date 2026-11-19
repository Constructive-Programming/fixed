package fixed.util

/** Severity of a diagnostic. */
enum Severity:
  case Error, Warning, Info

/** Diagnostic codes. The case name is the rendered code. `E*`: lexical
  * (scanner); `P*`: syntactic (parser); `T*`: typer.
  */
enum DiagCode:
  case E001, E002, E003, E004, E005, E006, E007, E008, E009
  case P001, P002, P003, P004, P005, P006, P007, P008, P010, P011, P012, P013, P014, P015, P016, P017, P018, P019, P099
  case T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T013, T014, T020

/** A diagnostic: code, source span, explanation and an optional copy-pasteable suggestion. */
final case class Diagnostic(
    severity: Severity,
    code: DiagCode,
    span: Span,
    message: String,
    suggestion: Option[String] = None
):
  def isError: Boolean = severity == Severity.Error

/** Accumulates the diagnostics of one compilation; single-threaded. */
final class Reporter(val source: SourceFile):
  private val buf = scala.collection.mutable.ArrayBuffer.empty[Diagnostic]

  def error(code: DiagCode, span: Span, message: String, suggestion: Option[String] = None): Unit =
    buf += Diagnostic(Severity.Error, code, span, message, suggestion)

  /** Append a pre-built diagnostic, e.g. one folded in from a phase's functional result. */
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
