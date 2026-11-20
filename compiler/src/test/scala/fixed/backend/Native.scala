package fixed.backend

import java.io.ByteArrayInputStream
import java.nio.file.Path

import scala.sys.process.{Process, ProcessLogger}

/** Running a shipped executable with the runtime's counters on. */
object Native:

  /** Exit code, standard output, standard error of `exe` run with `stdin`. */
  def run(exe: Path, stdin: String = ""): (Int, String, String) =
    val (out, err) = (new StringBuilder, new StringBuilder)
    val code =
      (Process(Seq(exe.toAbsolutePath.toString), None, "FIXED_RT_STATS" -> "1") #<
        ByteArrayInputStream(stdin.getBytes("UTF-8"))).!(
        ProcessLogger(l => { val _ = out.append(l).append('\n') }, l => { val _ = err.append(l).append('\n') })
      )
    (code, out.toString, err.toString)

  /** The runtime's counters, from the standard error of a run. */
  def counters(err: String): Map[String, Long] =
    "\"(\\w+)\":(\\d+)".r.findAllMatchIn(err).map(m => m.group(1) -> m.group(2).toLong).toMap
