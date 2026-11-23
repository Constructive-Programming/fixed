package fixed.backend

import java.nio.file.{Files, Path}

import scala.sys.process.{Process, ProcessLogger}

import fixed.CompilationUnit
import fixed.core.render
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Versioned Core and what M10 inferred about it; `fips` are the `fip fn` instances, `candidates`
  * the clone candidates of each function, on both axes, `typeClones` each clone of the type
  * axis with the function it is a clone of and its key, and `immediates` each data type lowered
  * with its layout if it is an immediate, and why (`Immediates`).
  */
final case class Analysed(
    fns: List[Fn],
    candidates: Map[String, List[Candidate]],
    demands: DemandTable,
    memory: MemoryTable,
    fips: Map[String, fixed.ast.Trees.Fip],
    spans: String => Span,
    typeClones: Map[String, (String, String)] = Map.empty,
    immediates: List[(String, Option[CType.Imm], String)] = Nil
)

/** A build mode (`spec/compilation_model.md` Rule CM5.7), by the clone budget `B` it gives each
  * function (Rule CM5.3). Every mode builds baseline versions (Rule CM3.1) and the mandatory
  * versions: `fip` bodies with their shared entries, and the clones a `fip` body needs. The
  * default mode adds clones chosen by score within the reference budget; `--no-specialise` has
  * budget 0, so it adds none; `--budget <n>` sets the budget for measurement;
  * `--no-ownership-clones` keeps the clones of the type axis and chooses none of the ownership
  * axis, so that the two can be compared; `--no-frames` keeps every cell on the heap ([[Frames]]).
  */
final case class Mode(budget: Int, ownership: Boolean = true, frames: Boolean = true)

object Mode:
  val Default: Mode = Mode(Versions.budget)
  val NoSpecialise: Mode = Mode(0)

/** The backend slice's entry points (`docs/plans/phase-4.0-backend-slice.md`): a checked unit to
  * LLVM IR, and LLVM IR plus the C runtime to a native executable through `clang`.
  */
object Backend:

  /** LLVM IR for `unit`, which must have passed the typer without errors. */
  def compile(unit: CompilationUnit, mode: Mode = Mode.Default): Either[List[Diagnostic], String] =
    analyse(unit, mode.budget, mode.ownership).flatMap(emit(_, mode.frames))

  /** LLVM IR for analysed Core: the `fip` check, erasure and borrowing, frame placement unless
    * `frames` is off, abort tests, reference counting.
    */
  def emit(a: Analysed, frames: Boolean = true): Either[List[Diagnostic], String] =
    FipCheck(a) match
      case Nil =>
        val (fns, borrowed) = Consume(a)
        val placed = if frames then Frames(fns, a.memory) else Frames.none(fns)
        val lent = (f: String) => borrowed.getOrElse(placed.clones.getOrElse(f, f), Set.empty)
        val counted = Aborts(placed.fns).map(f => Rc(f, lent, placed.frame.getOrElse(f.name, Set.empty)))
        Right(EmitLLVM(placed.bumped, placed.scoped, placed.bare).program(counted))
      case problems => Left(problems)

  /** Core for `unit`: lowered, then split into the versions demand inference calls for (3.4.M3). */
  def core(unit: CompilationUnit): Either[List[Diagnostic], List[Fn]] = analyse(unit).map(_.fns)

  /** Versioned Core with its demands and memory facts (3.4.M2–M4): baseline versions, clones of the
    * type axis ([[Specialise]]), then shared versions and clones of the ownership axis
    * ([[Versions]]), within one clone budget `budget` per function (`spec/compilation_model.md`
    * Rule CM5.3). A clone of the type axis that no call reaches once the ownership axis is chosen is
    * reported as `unused`.
    */
  def analyse(
      unit: CompilationUnit,
      budget: Int = Versions.budget,
      ownership: Boolean = true
  ): Either[List[Diagnostic], Analysed] =
    Specialise(unit, budget).map { s =>
      val (lower, fns) = (s.lower, s.fns)
      val render = (t: fixed.core.Type) => unit.symbols.render(t)
      val (demands, table) = Demands.analyse(lower, fns, render)
      val byKey = Demands.typesByKey(lower, render)
      val copy = (k: String, parts: Set[Need.Part]) => byKey.get(k).flatMap(lower.copy(_, parts))
      val typeClones = lower.typeClones.toMap
      val known = (k: String) => byKey.get(k).exists(lower.known)
      val versions =
        Versions(demands, table, fns, copy, lower.fips.contains, budget, typeClones, s.spent, known, ownership)
      val all = versions.run() ++ lower.functions.filter(_.name.contains(".copy["))
      val present = all.map(_.name).toSet
      val cloneNamed = typeClones.map((c, k) => k -> c)
      val typeAxis = s.candidates.map((f, cs) =>
        f -> cs.map(c =>
          if c.reason.isEmpty && !cloneNamed.get((f, c.key)).exists(present) then c.copy(reason = "unused") else c
        )
      )
      val candidates = Core.merge(typeAxis, versions.candidates, Nil)(_ ++ _).view.mapValues(_.sortBy(_.key)).toMap
      val spans = (v: String) => lower.spans.getOrElse(v, Span.NoSpan)
      val memory = Memory(all, table, spans).run()
      Analysed(all, candidates, table, memory, lower.fips.toMap, spans, typeClones, lower.immediates)
    }

  /** Hand-built Core with no source types (tests): its versions, demands and memory facts. */
  def analyseCore(fns: List[Fn], fips: Map[String, fixed.ast.Trees.Fip] = Map.empty): Analysed =
    val demands = Demands(fns, _ => None, (_, _) => None, (_, _) => None)
    val table = demands.run()
    val versions = Versions(demands, table, fns, (_, _) => None, fips.contains, Versions.budget)
    val all = versions.run()
    Analysed(all, versions.candidates, table, Memory(all, table, _ => Span.NoSpan).run(), fips, _ => Span.NoSpan)

  /** Write `ir` next to `out`, then link it with the runtime into the executable `out`. The runtime
    * is compiled to LLVM bitcode and linked into the program's module before it is optimised
    * (`-mlink-bitcode-file`), so that `clang -O2` inlines its fast paths (`dup`, `drop`, reuse,
    * allocation) into the program; only `clang` is needed. With
    * `checks`, the runtime verifies every unconditional reuse and poisons every frame cell its
    * scope releases and every heap cell it frees (test builds). With `stats`, it keeps the counters `FIXED_RT_STATS` prints
    * (test builds, `fixed ship --stats`); without, it reports only time and memory.
    */
  def ship(ir: String, out: Path, checks: Boolean = false, stats: Boolean = false): Either[Diagnostic, Path] =
    val dir = Option(out.toAbsolutePath.getParent).getOrElse(Path.of("."))
    val _ = Files.createDirectories(dir)
    val ll = dir.resolve(s"${out.getFileName}.ll")
    val rt = dir.resolve("fixed_rt.c")
    val _ = Files.writeString(ll, ir)
    val _ = Files.write(rt, runtimeSource)
    val bc = dir.resolve(s"${out.getFileName}.rt.bc")
    val log = new StringBuilder
    val runtime = Seq("clang", "-O2", "-std=c11", "-c", "-emit-llvm") ++
      (if checks then Seq("-DFX_CHECK_EXCL", "-DFX_CHECK_FRAME", "-DFX_CHECK_FREE") else Nil) ++
      (if stats then Seq("-DFX_STATS") else Nil) ++ Seq("-o", bc.toString, rt.toString)
    val program = Seq("clang", "-O2", "-Wno-override-module", "-o", out.toString, ll.toString) ++
      Seq("-Xclang", "-mlink-bitcode-file", "-Xclang", bc.toString, "-lm")
    val run = (cmd: Seq[String]) => Process(cmd).!(ProcessLogger(l => { val _ = log.append(l).append('\n') }))
    val code =
      try
        val compiled = run(runtime)
        if compiled != 0 then compiled else run(program)
      catch case _: java.io.IOException => -1
    if code == 0 then Right(out)
    else
      val why = if code == -1 then "`clang` not found on PATH" else s"`clang` failed:\n$log"
      Left(Diagnostic(Severity.Error, DiagCode.B004, Span.NoSpan, why))

  private def runtimeSource: Array[Byte] =
    val in = getClass.getResourceAsStream("/runtime/fixed_rt.c")
    try in.readAllBytes()
    finally in.close()
