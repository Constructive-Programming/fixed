package fixed

import java.nio.file.{Files, Path}

import fixed.ast.Trees
import fixed.backend.{Backend, MemoryReport}
import fixed.util.{Diagnostic, Reporter, SourceFile}

/** Command-line entry point.
  *
  *   - `fixed <file.fixed>...` checks each file, prints its diagnostics and a one-line summary.
  *   - `fixed compile <file.fixed> -o <out.ll>` writes LLVM IR.
  *   - `fixed ship <file.fixed> -o <exe>` builds a native executable with `clang`.
  *   - `fixed compile <file.fixed> --explain-memory [--format json]` prints the memory report
  *     (`spec/memory_management.md` §19).
  *
  * Exit codes (implementation plan, "Agent-Friendly CLI"): 0 success, 1 compile error, 2 usage,
  * 3 build failure.
  */
object Main:
  def main(args: Array[String]): Unit =
    args.toList.filterNot(_ == "--") match
      case "compile" :: file :: "-o" :: out :: Nil => sys.exit(backend(file)(ir => write(Path.of(out), ir)))
      case "compile" :: file :: "--explain-memory" :: format if format == Nil || format == List("--format", "json") =>
        sys.exit(explain(file, json = format.nonEmpty))
      case "ship" :: file :: "-o" :: out :: Nil =>
        sys.exit(backend(file)(ir => Backend.ship(ir, Path.of(out)).fold(d => { print(file, d); 3 }, _ => 0)))
      case Nil | ("compile" | "ship") :: _ =>
        Console.err.println(
          "usage: fixed <file.fixed>... | fixed compile|ship <file.fixed> -o <out>" +
            " | fixed compile <file.fixed> --explain-memory [--format json]"
        )
        sys.exit(2)
      case paths =>
        val units = paths.map(p => Compiler.compile(CompilationUnit(SourceFile.fromPath(p))))
        units.foreach(report)
        sys.exit(if units.exists(_.hasErrors) then 1 else 0)

  /** Check `file`, lower it to LLVM IR and hand the IR to `use`; exit code 1 on any error. */
  private def backend(file: String)(use: String => Int): Int =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromPath(file)))
    if unit.hasErrors then
      report(unit)
      1
    else
      Backend.compile(unit) match
        case Left(ds) =>
          report(unit.report(ds))
          1
        case Right(ir) => use(ir)

  /** Print the memory report of `file`; exit code 1 on any error. */
  private def explain(file: String, json: Boolean): Int =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromPath(file)))
    Option.when(!unit.hasErrors)(Backend.analyse(unit)) match
      case Some(Right(a)) =>
        println(if json then MemoryReport.json(unit, a) else MemoryReport.text(unit, a))
        0
      case Some(Left(ds)) =>
        report(unit.report(ds))
        1
      case None =>
        report(unit)
        1

  private def write(out: Path, ir: String): Int =
    val _ = Files.writeString(out, ir)
    0

  private def print(file: String, d: Diagnostic): Unit =
    println(new Reporter(SourceFile.fromString(file, "")).format(d))

  private def report(unit: CompilationUnit): Unit =
    val reporter = new Reporter(unit.source)
    unit.diagnostics.foreach(reporter.add)
    if unit.diagnostics.nonEmpty then println(reporter.formatAll)
    println(s"${unit.source.path}: ${summary(unit)}")

  private def summary(unit: CompilationUnit): String =
    unit.tree match
      case Some(Trees.CompilationUnit(items, _)) => s"parsed ${items.size} top-level items"
      case Some(other)                           => s"parsed ${other.getClass.getSimpleName}"
      case None                                  => "not parsed"
