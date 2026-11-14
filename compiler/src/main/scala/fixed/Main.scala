package fixed

import fixed.ast.Trees
import fixed.util.{Reporter, SourceFile}

/** Command-line entry point: `fixed <file.fixed>...`. Compiles each file, prints its diagnostics
  * and a one-line summary of the parsed tree, and exits 1 if any file has errors.
  */
object Main:
  def main(args: Array[String]): Unit =
    val paths = args.toList.filterNot(_ == "--")
    if paths.isEmpty then
      Console.err.println("usage: fixed <file.fixed>...")
      sys.exit(2)
    val units = paths.map(p => Compiler.compile(CompilationUnit(SourceFile.fromPath(p))))
    units.foreach(report)
    sys.exit(if units.exists(_.hasErrors) then 1 else 0)

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
