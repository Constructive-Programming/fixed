package fixed.util

/** Severity of a diagnostic. */
enum Severity:
  case Error, Warning, Info

/** Diagnostic codes. The case name is the rendered code. `E*`: lexical (scanner) and a few later
  * checks (`E072`; `E102`, a prop the static prover refutes; `E110`); `P*`: syntactic (parser);
  * `T*`: typer; `B*`: backend
  * (`docs/plans/phase-4.0-backend-slice.md` §5).
  */
enum DiagCode:
  case E001, E002, E003, E004, E005, E006, E007, E008, E009, E072, E102, E110
  case P001, P002, P003, P004, P005, P006, P007, P008, P010, P011, P012, P013, P014, P015, P016, P017, P018, P019, P099
  case T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T013, T014, T015, T016, T017, T018, T019, T020,
    T021, T022, T023, T027, T028, T029, T031, T032
  case B001, B002, B003, B004, B005

  /** The error class of `spec/reports.md` Rule RP5.5, which is the exit code of a run whose errors
    * have no smaller class. `0` for a code the compiler reports only as a warning (`T017`, and
    * `T021`, which the table does not list).
    */
  def errorClass: Int = this match
    case E001 | E002 | E003 | E004 | E005 | E006 | E007 | E008 | E009 => 3
    case P001 | P002 | P003 | P004 | P005 | P006 | P007 | P008 | P010 | P011 | P012 | P013 | P014 | P015 |
        P016 | P017 | P018 | P019 | P099 => 4
    case T001 | T003 | T004                                                                              => 5
    case T002 | T006 | T007 | T008 | T010 | T013 | T014 | T022 | T023 | T027 | T028 | T029 | T031 | T032 => 6
    case T011                                                                                            => 7
    case T009 | T015 | T016 | T018 | T019                                                                => 8
    case T020                                                                                            => 9
    case E102                                                                                            => 11
    case E072                                                                                            => 12
    case E110                                                                                            => 14
    case B004                                                                                            => 18
    case T005 | B001 | B002 | B003 | B005                                                                => 99
    case T017 | T021                                                                                     => 0

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

  /** Format a diagnostic as a single multi-line block suitable for terminals, at its span's file
    * (`Span.file`, a library file) or else this unit's; a span past the end of that file, which
    * only a fault makes, without a position.
    */
  def format(d: Diagnostic): String =
    val file = d.span.file.getOrElse(source)
    val at =
      if d.span.start > file.length then ""
      else
        val (line, col) = file.lineColumn(d.span.start)
        s":$line:$col"
    val sevTag = d.severity match
      case Severity.Error   => "error"
      case Severity.Warning => "warning"
      case Severity.Info    => "info"
    val head = s"${file.path}$at: $sevTag[${d.code}]: ${d.message}"
    d.suggestion match
      case Some(s) => s"$head\n  hint: $s"
      case None    => head

  /** All diagnostics, formatted, joined by newlines. */
  def formatAll: String = diagnostics.map(format).mkString("\n")
