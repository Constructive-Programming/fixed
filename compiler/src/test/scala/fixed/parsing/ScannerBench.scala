package fixed.parsing

import fixed.util.{Reporter, SourceFile}

// Throwaway scanner micro-bench; not JMH. Run via `compiler/Test/runMain
// fixed.parsing.ScannerBench [iters]`. Loads every example, warms up the
// JIT, then tokenizes each file `iters` times and prints per-file and
// corpus-aggregate means in microseconds-per-run and ns-per-token.
object ScannerBench:

  private val examples: List[String] = List(
    "01_basics", "02_collections", "03_option_result", "04_json",
    "05_phantom_types", "06_functor_monad", "07_recursive_data",
    "08_effects_handlers", "09_interpreter", "10_geometry", "11_properties"
  )

  def main(args: Array[String]): Unit =
    val iters: Int = args.headOption.flatMap(_.toIntOption).getOrElse(2000)
    val warmup: Int = (iters / 10).max(50)

    // Load once.
    val loaded: List[(String, SourceFile)] = examples.map { name =>
      val src = SourceFile.fromPath(s"examples/$name.fixed")
      (name, src)
    }

    // Total token count for one full corpus pass.
    val tokenCountPerPass: Long =
      loaded.map { case (_, src) =>
        val rep = new Reporter(src)
        Scanner.tokenize(src, rep).length.toLong
      }.sum

    // Warmup.
    var w = 0
    while w < warmup do
      loaded.foreach { case (_, src) =>
        val rep = new Reporter(src)
        val _ = Scanner.tokenize(src, rep)
      }
      w += 1

    // Measure per-file means.
    val results: List[(String, Long, Long, Int)] = loaded.map { case (name, src) =>
      val srcLen = src.length
      val tokenCount =
        val rep = new Reporter(src)
        Scanner.tokenize(src, rep).length
      val tStart = System.nanoTime()
      var i = 0
      while i < iters do
        val rep = new Reporter(src)
        val _ = Scanner.tokenize(src, rep)
        i += 1
      val elapsed = System.nanoTime() - tStart
      (name, elapsed, srcLen.toLong, tokenCount)
    }

    val totalElapsed: Long = results.map(_._2).sum
    val totalIters: Long = results.size.toLong * iters

    println(s"=== ScannerBench (iters=$iters, warmup=$warmup) ===")
    println(f"${"file"}%-22s ${"chars"}%6s ${"toks"}%5s ${"total ms"}%10s ${"µs/run"}%10s ${"ns/tok"}%10s ${"ns/char"}%10s")
    results.foreach { case (name, elapsed, srcLen, toks) =>
      val totalMs = elapsed / 1_000_000.0
      val usPerRun = elapsed / 1000.0 / iters
      val nsPerTok = elapsed.toDouble / iters / toks
      val nsPerChar = elapsed.toDouble / iters / srcLen
      println(f"$name%-22s $srcLen%6d $toks%5d $totalMs%10.2f $usPerRun%10.2f $nsPerTok%10.1f $nsPerChar%10.1f")
    }
    println("---")
    println(f"corpus total: ${totalElapsed / 1_000_000.0}%.2f ms over ${totalIters} runs (${tokenCountPerPass} tokens/pass)")
    println(f"  mean per run:   ${totalElapsed / 1_000.0 / totalIters}%.2f µs")
    println(f"  mean per token: ${totalElapsed.toDouble / totalIters / tokenCountPerPass * results.size}%.1f ns")

end ScannerBench
