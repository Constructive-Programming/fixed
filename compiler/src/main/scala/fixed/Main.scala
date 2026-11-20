package fixed

import java.nio.file.{Files, Path}

import fixed.ast.Trees
import fixed.backend.{Backend, MemoryReport, Mode}
import fixed.util.{Diagnostic, Reporter, SourceFile}

/** Command-line entry point.
  *
  *   - `fixed <file.fixed>...` checks each file, prints its diagnostics and a one-line summary.
  *   - `fixed compile <file.fixed> -o <out.ll>` writes LLVM IR.
  *   - `fixed ship <file.fixed> -o <exe>` builds a native executable with `clang`.
  *   - `fixed compile <file.fixed> --explain-memory [--format json]` prints the memory report
  *     (`spec/memory_management.md` §19).
  *   - `--no-specialise`, in any position, builds only mandatory versions
  *     (`spec/compilation_model.md` Rule CM5.7); `--budget <n>`, in any position, sets the clone
  *     budget of Rule CM5.3 instead of the reference value, for measurement;
  *     `--no-ownership-clones`, in any position, keeps the clones of the type axis and builds
  *     none of the ownership axis but those a `fip` body needs, for measurement.
  *
  * Exit codes (implementation plan, "Agent-Friendly CLI"): 0 success, 1 compile error, 2 usage,
  * 3 build failure.
  */
object Main:
  def main(args: Array[String]): Unit =
    val (mode, rest) = modeOf(args.toList.filterNot(_ == "--"))
    rest match
      case "compile" :: file :: "-o" :: out :: Nil => sys.exit(backend(file, mode)(ir => write(Path.of(out), ir)))
      case "compile" :: file :: "--explain-memory" :: format if format == Nil || format == List("--format", "json") =>
        sys.exit(explain(file, json = format.nonEmpty, mode))
      case "ship" :: file :: "-o" :: out :: Nil =>
        sys.exit(backend(file, mode)(ir => Backend.ship(ir, Path.of(out)).fold(d => { print(file, d); 3 }, _ => 0)))
      case Nil | ("compile" | "ship") :: _ =>
        Console.err.println(
          "usage: fixed <file.fixed>... | fixed compile|ship <file.fixed> -o <out>" +
            " | fixed compile <file.fixed> --explain-memory [--format json];" +
            " --no-specialise, --budget <n> or --no-ownership-clones anywhere"
        )
        sys.exit(2)
      case paths =>
        val units = paths.map(p => Compiler.compile(CompilationUnit(SourceFile.fromPath(p))))
        units.foreach(report)
        sys.exit(if units.exists(_.hasErrors) then 1 else 0)

  /** The build mode `args` ask for, and the other arguments. */
  private[fixed] def modeOf(args: List[String]): (Mode, List[String]) =
    val (flags, others) = args.partition(a => a == "--no-specialise" || a == "--no-ownership-clones")
    val at = others.indexOf("--budget")
    val budget = if at < 0 then None else others.lift(at + 1).flatMap(_.toIntOption).filter(_ >= 0)
    val rest = if budget.isDefined then others.patch(at, Nil, 2) else others
    val mode = if flags.contains("--no-specialise") then Mode.NoSpecialise else budget.fold(Mode.Default)(Mode(_))
    (mode.copy(ownership = !flags.contains("--no-ownership-clones")), rest)

  /** Check `file`, lower it to LLVM IR and hand the IR to `use`; exit code 1 on any error. */
  private def backend(file: String, mode: Mode)(use: String => Int): Int =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromPath(file)))
    if unit.hasErrors then
      report(unit)
      1
    else
      Backend.compile(unit, mode) match
        case Left(ds) =>
          report(unit.report(ds))
          1
        case Right(ir) => use(ir)

  /** Print the memory report of `file`; exit code 1 on any error. */
  private def explain(file: String, json: Boolean, mode: Mode): Int =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromPath(file)))
    Option.when(!unit.hasErrors)(Backend.analyse(unit, mode.budget, mode.ownership)) match
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
