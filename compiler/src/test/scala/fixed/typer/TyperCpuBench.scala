// scalafix:off DisableSyntax.var, DisableSyntax.while
package fixed.typer
import fixed.parsing.Parser
import fixed.util.SourceFile

/** Throwaway typer micro-bench; not JMH. Measures thread CPU time per corpus pass (Namer + body
  * typing), which stays meaningful while the machine has other load. Run via
  * `compiler/Test/runMain fixed.typer.TyperCpuBench`.
  */
object TyperCpuBench:
  def main(args: Array[String]): Unit =
    val bean = java.lang.management.ManagementFactory.getThreadMXBean
    val trees = new java.io.File("examples").listFiles(_.getName.endsWith(".fixed")).sorted.toList.map(f =>
      Parser.parse(SourceFile.fromPath(f.getPath)).tree
    )
    def pass(): Unit = trees.foreach { t =>
      val (sy, _) = Namer.index(t); TyperPhase.typeBodies(t, sy)
    }
    (1 to 3000).foreach(_ => pass())
    val samples = (1 to 15).map { _ =>
      val t0 = bean.getCurrentThreadCpuTime
      (1 to 300).foreach(_ => pass())
      (bean.getCurrentThreadCpuTime - t0) / 300.0 / 1000.0
    }.sorted
    println(f"TYPER_CPU_US median=${samples(7)}%.1f min=${samples.head}%.1f max=${samples.last}%.1f")
