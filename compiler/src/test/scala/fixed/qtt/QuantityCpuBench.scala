package fixed.qtt
import fixed.CompilationUnit
import fixed.typer.TyperPhase
import fixed.util.SourceFile

/** Throwaway bench, same method as `TyperCpuBench`: thread CPU time per pass over the examples
  * that type-check, for the typer alone and for the quantity walk alone. Run via
  * `compiler/Test/runMain fixed.qtt.QuantityCpuBench`.
  */
object QuantityCpuBench:
  def main(args: Array[String]): Unit =
    val bean = java.lang.management.ManagementFactory.getThreadMXBean
    val parsed = new java.io.File("examples").listFiles(_.getName.endsWith(".fixed")).sorted.toList.map(f =>
      fixed.parsing.ParserPhase.run(CompilationUnit(SourceFile.fromPath(f.getPath)))
    )
    val typed = parsed.map(TyperPhase.run).filterNot(_.hasErrors)
    val ok = parsed.filter(p => typed.exists(_.source eq p.source))
    def median(pass: () => Unit): Double =
      (1 to 2000).foreach(_ => pass())
      (1 to 15).map { _ =>
        val t0 = bean.getCurrentThreadCpuTime
        (1 to 200).foreach(_ => pass())
        (bean.getCurrentThreadCpuTime - t0) / 200.0 / 1000.0
      }.sorted.apply(7)
    val typer = median(() => ok.foreach(TyperPhase.run))
    val qtt = median(() => typed.foreach(QuantityPhase.run))
    println(f"units=${typed.size} TYPER_US=$typer%.1f QTT_US=$qtt%.1f ratio=${qtt / typer * 100}%.1f%%")
