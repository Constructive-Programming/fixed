// scalafix:off DisableSyntax.var
/** Benchmark/test loops: mutable counters are deliberate here. */
package fixed.parsing

import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

import scala.util.Random

import fixed.ast.Trees
import fixed.util.SourceFile
import munit.FunSuite

/** Corruption-resilience property test (fixed seed, so reproducible): `Iterations` times, insert a
  * random junk char into a random example file and parse it under a wall-clock timeout.
  *
  * Hard contract (fails the suite): no timeout, no exception, and a changed tree shape always comes
  * with a diagnostic. Soft contract: the top-level item count stays within ±1 of the reference
  * (`phase-2.1-incremental-parser.md` §7.4). Recovery does not cover fn signatures or patterns yet,
  * so only drift ≥ `MaxItemDrift` fails; the worst drift is printed.
  */
class CorruptionResilienceSuite extends FunSuite:

  /** ~ 1s budget total */
  private val Iterations = 200

  /** "fixed" in hex; change to widen coverage */
  private val Seed = 0xf1ed_f1edL
  private val PerParseTimeoutMs = 2000L
  private val JunkChars: Vector[Char] = Vector('@', ';', '}', '?', '$', '\\')

  /** Target 1; ~50 observed today. Tighten as recovery reaches patterns, match cases and props. */
  private val MaxItemDrift = 80

  private val ExampleDir = "examples"
  private val ExampleFiles: Vector[String] =
    val d = new java.io.File(ExampleDir)
    if !d.isDirectory then Vector.empty
    else
      d.listFiles().toVector
        .filter(f => f.isFile && f.getName.endsWith(".fixed"))
        .map(_.getPath)
        .sorted

  private val executor = Executors.newSingleThreadExecutor()

  override def afterAll(): Unit =
    val _ = executor.shutdownNow()

  /** Run `body` with a wall-clock timeout. Returns Right(value) or
    * Left(reason). Used to catch any future infinite-loop regression.
    */
  private def withTimeout[T](ms: Long)(body: => T): Either[String, T] =
    val fut = executor.submit(() => body)
    try Right(fut.get(ms, TimeUnit.MILLISECONDS))
    catch
      case _: TimeoutException =>
        fut.cancel(true)
        Left(s"timeout after ${ms}ms")
      case e: Exception =>
        Left(s"exception: ${e.getClass.getSimpleName}: ${e.getMessage}")

  private def itemCount(t: fixed.ast.Tree): Int = t match
    case Trees.CompilationUnit(items, _) => items.length
    case _                               => -1

  test("parser terminates and degrades gracefully under random single-byte corruption"):
    assume(ExampleFiles.nonEmpty, "examples/ directory not found or empty — run from repo root")
    val rng = new Random(Seed)
    val hardFailures = scala.collection.mutable.ListBuffer.empty[String]
    var driftSamples = 0
    var maxDrift = 0
    var totalDrift = 0
    for i <- 0 until Iterations do
      val path = ExampleFiles(rng.nextInt(ExampleFiles.length))
      val good = SourceFile.fromPath(path)
      val pos = if good.length == 0 then 0 else rng.nextInt(good.length + 1)
      val ch = JunkChars(rng.nextInt(JunkChars.length))
      val brokenContent =
        good.content.substring(0, pos) + ch + good.content.substring(pos)
      val broken = SourceFile.fromString(s"$path[corrupted@$pos]", brokenContent)

      val refResult = withTimeout(PerParseTimeoutMs) { Parser.parse(good) }
      val brokenResult = withTimeout(PerParseTimeoutMs) { Parser.parse(broken) }

      (refResult, brokenResult) match
        case (Right(ref), Right(br)) =>
          val refN = itemCount(ref.tree)
          val brN = itemCount(br.tree)
          val drift = math.abs(refN - brN)
          if br.diagnostics.isEmpty && refN != brN then
            /** Hard fail: tree shape changed but no diagnostic — silent
              * corruption is the worst possible outcome.
              */
            hardFailures += s"i=$i $path pos=$pos ch='$ch': silent shape change ($refN -> $brN)"
          else if drift >= MaxItemDrift then
            hardFailures += s"i=$i $path pos=$pos ch='$ch': extreme drift ($refN -> $brN), diags=${br.diagnostics.size}"
          else if drift > 1 then
            driftSamples += 1
            totalDrift += drift
            if drift > maxDrift then maxDrift = drift
        case (_, Left(reason)) =>
          /** Hard fail: timeout or exception during parsing — the parser
            * must always terminate gracefully.
            */
          hardFailures += s"i=$i $path pos=$pos ch='$ch': $reason"
        case (Left(reason), _) =>
          /** Reference parse couldn't complete — examples corpus issue,
            * not a parser regression. Still hard-fail so we notice.
            */
          hardFailures += s"i=$i $path: reference $reason"

    /** Soft-contract digest — printed even on success so the trend is
      * visible. Tighten `MaxItemDrift` as recovery lands in more
      * productions; ideal final state is `driftSamples == 0`.
      */
    val avgDrift = if driftSamples == 0 then 0.0 else totalDrift.toDouble / driftSamples
    println(
      f"[CorruptionResilienceSuite] iterations=$Iterations " +
        f"driftSamples=$driftSamples avgDrift=$avgDrift%.2f maxDrift=$maxDrift " +
        f"target=±1 ceiling=$MaxItemDrift"
    )

    if hardFailures.nonEmpty then
      val sample = hardFailures.take(10).mkString("\n  ")
      fail(s"${hardFailures.size}/${Iterations} hard contract violations:\n  $sample")

end CorruptionResilienceSuite
