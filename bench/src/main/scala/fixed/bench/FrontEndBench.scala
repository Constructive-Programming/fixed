// scalafix:off DisableSyntax.var, DisableSyntax.while, DisableSyntax.asInstanceOf
// JMH state and hot loops: mutable fields and while loops are deliberate.
package fixed.bench

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import fixed.parsing.{Parser, Scanner, Token}
import fixed.util.{Reporter, SourceFile}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Scanner / parser throughput over the `examples/` corpus. One op = one
  * pass over all 11 example files.
  *
  * Run: `sbt "bench/Jmh/run -prof gc fixed.bench.FrontEndBench"`.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
class FrontEndBench:

  private var sources: Array[SourceFile] = Array.empty
  private var tokens: Array[Array[Token]] = Array.empty

  @Setup
  def setup(): Unit =
    val examples = FrontEndBench.examplesDir
    sources = Files
      .list(examples)
      .toArray
      .map(_.asInstanceOf[Path])
      .filter(_.toString.endsWith(".fixed"))
      .sortBy(_.toString)
      .map(p => SourceFile.fromString(p.toString, Files.readString(p)))
    tokens = sources.map(src => new Scanner(src, new Reporter(src)).tokenize().toArray)

  /** Scanner only: source text -> token vector. */
  @Benchmark
  def scan(bh: Blackhole): Unit =
    sources.foreach(src => bh.consume(new Scanner(src, new Reporter(src)).tokenize()))

  /** Parser only, over pre-scanned tokens. */
  @Benchmark
  def parseOnly(bh: Blackhole): Unit =
    var i = 0
    while i < tokens.length do
      given Reporter = new Reporter(sources(i))
      bh.consume(Parser.parseCompilationUnit(Parser.initialState(tokens(i))))
      i += 1

  /** Scan + parse, the public entry point. */
  @Benchmark
  def scanAndParse(bh: Blackhole): Unit =
    sources.foreach(src => bh.consume(Parser.parse(src)))

object FrontEndBench:
  /** `examples/` under the repository root, found by walking up from the working directory. */
  def examplesDir: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("examples"))
      .find(Files.isDirectory(_))
      .getOrElse(sys.error("examples/ not found above the working directory"))
