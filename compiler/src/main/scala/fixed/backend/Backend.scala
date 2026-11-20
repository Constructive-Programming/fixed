package fixed.backend

import java.nio.file.{Files, Path}

import scala.sys.process.{Process, ProcessLogger}

import fixed.CompilationUnit
import fixed.core.render
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** The backend slice's entry points (`docs/plans/phase-4.0-backend-slice.md`): a checked unit to
  * LLVM IR, and LLVM IR plus the C runtime to a native executable through `clang`.
  */
/** Versioned Core and what M10 inferred about it; `fips` are the `fip fn` instances, `candidates`
  * the clone candidates of each function instance.
  */
final case class Analysed(
    fns: List[Fn],
    candidates: Map[String, List[Candidate]],
    demands: DemandTable,
    memory: MemoryTable,
    fips: Map[String, fixed.ast.Trees.Fip],
    spans: String => Span
)

/** A build mode (`spec/compilation_model.md` Rule CM5.7), by the clone budget it gives each
  * function. With `--no-specialise` the budget is 0, so only mandatory versions are built: shared
  * versions, `fip` bodies with their shared entries, and the clones a `fip` body needs; and
  * polymorphic code is lowered as baseline versions (Rule CM3.1) where it can be. The default
  * mode still lowers one instance per type instantiation.
  */
enum Mode(val budget: Int):
  case Default extends Mode(Versions.budget)
  case NoSpecialise extends Mode(0)

  /** Whether polymorphic code is lowered as baseline versions: in `NoSpecialise`. */
  def baseline: Boolean = this == NoSpecialise

object Backend:

  /** LLVM IR for `unit`, which must have passed the typer without errors. */
  def compile(unit: CompilationUnit, mode: Mode = Mode.Default): Either[List[Diagnostic], String] =
    analyse(unit, mode.budget, mode.baseline).flatMap(emit)

  /** LLVM IR for analysed Core: the `fip` check, erasure and borrowing, abort tests, reference counting. */
  def emit(a: Analysed): Either[List[Diagnostic], String] =
    FipCheck(a) match
      case Nil =>
        val (fns, borrowed) = Consume(a)
        Right(EmitLLVM().program(Aborts(fns).map(Rc(_, f => borrowed.getOrElse(f, Set.empty)))))
      case problems => Left(problems)

  /** Core for `unit`: lowered, then split into the versions demand inference calls for (3.4.M3). */
  def core(unit: CompilationUnit): Either[List[Diagnostic], List[Fn]] = analyse(unit).map(_.fns)

  /** Versioned Core with its demands and memory facts (3.4.M2–M4); `budget` is each function's
    * clone budget (`spec/compilation_model.md` Rule CM5.3); with `baseline`, polymorphic code is
    * lowered as baseline versions where it can be (Rule CM3.1).
    */
  def analyse(
      unit: CompilationUnit,
      budget: Int = Versions.budget,
      baseline: Boolean = false
  ): Either[List[Diagnostic], Analysed] =
    val lower = Lower(unit, baseline)
    lower.program.map { fns =>
      val render = (t: fixed.core.Type) => unit.symbols.render(t)
      val (demands, table) = Demands.analyse(lower, fns, render)
      val byKey = Demands.typesByKey(lower, render)
      val copy = (k: String, parts: Set[Need.Part]) => byKey.get(k).flatMap(lower.copy(_, parts))
      val versions = Versions(demands, table, fns, copy, lower.fips.contains, budget)
      val all = versions.run() ++ lower.functions.filter(_.name.contains(".copy["))
      val spans = (v: String) => lower.spans.getOrElse(v, Span.NoSpan)
      Analysed(all, versions.candidates, table, Memory(all, table, spans).run(), lower.fips.toMap, spans)
    }

  /** Hand-built Core with no source types (tests): its versions, demands and memory facts. */
  def analyseCore(fns: List[Fn], fips: Map[String, fixed.ast.Trees.Fip] = Map.empty): Analysed =
    val demands = Demands(fns, _ => None, (_, _) => None, (_, _) => None)
    val table = demands.run()
    val versions = Versions(demands, table, fns, (_, _) => None, fips.contains, Versions.budget)
    val all = versions.run()
    Analysed(all, versions.candidates, table, Memory(all, table, _ => Span.NoSpan).run(), fips, _ => Span.NoSpan)

  /** Write `ir` next to `out`, then link it with the runtime into the executable `out`. With
    * `checks`, the runtime verifies every unconditional reuse (test builds).
    */
  def ship(ir: String, out: Path, checks: Boolean = false): Either[Diagnostic, Path] =
    val dir = Option(out.toAbsolutePath.getParent).getOrElse(Path.of("."))
    val _ = Files.createDirectories(dir)
    val ll = dir.resolve(s"${out.getFileName}.ll")
    val rt = dir.resolve("fixed_rt.c")
    val _ = Files.writeString(ll, ir)
    val _ = Files.write(rt, runtimeSource)
    val log = new StringBuilder
    val cmd = Seq("clang", "-O2", "-std=c11") ++ Option.when(checks)("-DFX_CHECK_EXCL") ++
      Seq("-o", out.toString, ll.toString, rt.toString, "-lm")
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
