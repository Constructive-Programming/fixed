package fixed.backend

import java.nio.file.{Files, Path}

import scala.sys.process.{Process, ProcessLogger}

import fixed.CompilationUnit
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** The backend slice's entry points (`docs/plans/phase-4.0-backend-slice.md`): a checked unit to
  * LLVM IR, and LLVM IR plus the C runtime to a native executable through `clang`.
  */
object Backend:

  /** LLVM IR for `unit`, which must have passed the typer without errors. */
  def compile(unit: CompilationUnit): Either[List[Diagnostic], String] =
    Lower(unit).program.map(fns => EmitLLVM().program(Aborts(fns).map(Rc(_))))

  /** Write `ir` next to `out`, then link it with the runtime into the executable `out`. */
  def ship(ir: String, out: Path): Either[Diagnostic, Path] =
    val dir = Option(out.toAbsolutePath.getParent).getOrElse(Path.of("."))
    val _ = Files.createDirectories(dir)
    val ll = dir.resolve(s"${out.getFileName}.ll")
    val rt = dir.resolve("fixed_rt.c")
    val _ = Files.writeString(ll, ir)
    val _ = Files.write(rt, runtimeSource)
    val log = new StringBuilder
    val cmd = Seq("clang", "-O2", "-std=c11", "-o", out.toString, ll.toString, rt.toString, "-lm")
    val code =
      try Process(cmd).!(ProcessLogger(l => { val _ = log.append(l).append('\n') }))
      catch case _: java.io.IOException => -1
    if code == 0 then Right(out)
    else
      val why = if code == -1 then "`clang` not found on PATH" else s"`clang` failed:\n$log"
      Left(Diagnostic(Severity.Error, DiagCode.B004, Span.NoSpan, why))

  private def runtimeSource: Array[Byte] =
    val in = getClass.getResourceAsStream("/runtime/fixed_rt.c")
    try in.readAllBytes()
    finally in.close()
