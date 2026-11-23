package fixed.backend

import java.nio.file.{Files, Path}

import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Frame cells: the data stack (`spec/memory_management.md` Rules MM6.1, MM6.2) in the runtime,
  * driven by hand-written LLVM IR (bump allocation over several chunks, a mark and a reset, the
  * counters, the checked build's poison), and what [[Frames]] places on it, in both build modes.
  */
class FrameSuite extends munit.FunSuite:

  private val dir = Path.of("target", "frame-tests")

  /** `fx_u_main` allocating `n` frame cells of three slots after a mark, resetting to the mark, and then `after`. */
  private def program(n: Int, after: String) =
    s"""|@fx_frame_top = external global ptr
        |declare ptr @fx_frame_alloc(i32, i32, i64)
        |declare void @fx_frame_reset(ptr)
        |declare void @fx_drop(ptr)
        |declare void @fx_dup(ptr)
        |declare void @fx_panic_div()
        |
        |define i8 @"fx_u_main"() {
        |entry:
        |  %m = load ptr, ptr @fx_frame_top
        |  br label %loop
        |loop:
        |  %i = phi i64 [ 0, %entry ], [ %n, %loop ]
        |  %c = call ptr @fx_frame_alloc(i32 7, i32 0, i64 24)
        |  call void @fx_dup(ptr %c)
        |  call void @fx_drop(ptr %c)
        |  call void @fx_drop(ptr %c)
        |  %n = add i64 %i, 1
        |  %done = icmp eq i64 %n, $n
        |  br i1 %done, label %out, label %loop
        |out:
        |  call void @fx_frame_reset(ptr %m)
        |$after
        |  ret i8 0
        |}
        |""".stripMargin

  private def run(name: String, ir: String, checks: Boolean): (Int, String, String) =
    val exe = Backend.ship(ir, dir.resolve(s"$name-$checks"), checks, stats = true).fold(d => fail(d.message), identity)
    Native.run(exe)

  /** A second activation after the first one's reset, as large again in bigger cells: it reuses
    * the chunks the first one grew, and allocates past them.
    */
  private val again =
    """|  %m2 = load ptr, ptr @fx_frame_top
       |  br label %again
       |again:
       |  %j = phi i64 [ 0, %out ], [ %k, %again ]
       |  %d = call ptr @fx_frame_alloc(i32 7, i32 0, i64 40)
       |  %k = add i64 %j, 1
       |  %fin = icmp eq i64 %k, 100000
       |  br i1 %fin, label %end, label %again
       |end:
       |  call void @fx_frame_reset(ptr %m2)""".stripMargin

  test("frame cells span chunks, ignore dup and drop, and are all released by the reset"):
    List(false, true).foreach { checks =>
      val (code, _, err) = run("chunks", program(100000, again), checks)
      assertEquals(code, 0, err)
      val counts = Native.counters(err)
      assertEquals(counts.get("frame_allocs"), Some(200000L), err)
      assertEquals(counts.get("frame_frees"), Some(200000L), err)
      assertEquals(counts.get("allocs"), Some(0L), err)
    }

  test("a loop that resets to its mark at every step, around a nested scope, walks each cell once"):
    val ir =
      """|@fx_frame_top = external global ptr
         |declare ptr @fx_frame_alloc(i32, i32, i64)
         |declare void @fx_frame_reset(ptr)
         |
         |define i8 @"fx_u_main"() {
         |entry:
         |  %m = load ptr, ptr @fx_frame_top
         |  br label %loop
         |loop:
         |  %i = phi i64 [ 0, %entry ], [ %n, %loop ]
         |  %a = call ptr @fx_frame_alloc(i32 7, i32 0, i64 8)
         |  %inner = load ptr, ptr @fx_frame_top
         |  %b = call ptr @fx_frame_alloc(i32 7, i32 0, i64 16)
         |  %c = call ptr @fx_frame_alloc(i32 7, i32 0, i64 8)
         |  call void @fx_frame_reset(ptr %inner)
         |  %d = call ptr @fx_frame_alloc(i32 7, i32 0, i64 8)
         |  call void @fx_frame_reset(ptr %m)
         |  %n = add i64 %i, 1
         |  %done = icmp eq i64 %n, 200000
         |  br i1 %done, label %out, label %loop
         |out:
         |  ret i8 0
         |}
         |""".stripMargin
    List(false, true).foreach { checks =>
      val (code, _, err) = run("steps", ir, checks)
      assertEquals(code, 0, err)
      assertEquals(Native.counters(err).get("frame_frees"), Some(800000L), err)
    }

  test("an unchecked build that leaves a cell above the bottom does not count it released"):
    val (code, _, err) = run("unreset", program(10, "  %e = call ptr @fx_frame_alloc(i32 7, i32 0, i64 8)"), false)
    assertEquals(code, 0, err)
    assertEquals(Native.counters(err).get("frame_frees"), Some(0L), err)

  test("a checked build stops on a frame cell dropped after its scope was reset"):
    val (code, _, err) = run("dangling", program(10, "  call void @fx_drop(ptr %c)"), true)
    assertEquals(code, 103, err)
    assert(err.contains("frame cell used after its scope ended"), err)

  test("a checked build poisons the payload of a released frame cell"):
    val read =
      """|  %p = getelementptr inbounds i8, ptr %c, i64 16
         |  %v = load i64, ptr %p
         |  %bad = icmp ne i64 %v, -6510615555426900571
         |  br i1 %bad, label %fail, label %ok
         |fail:
         |  call void @fx_panic_div()
         |  unreachable
         |ok:""".stripMargin
    val (code, _, err) = run("poison", program(3, read), true)
    assertEquals(code, 0, err)

  /** A bare cell (`fx_frame_alloc_bare`, 24 bytes of payload) and a cell with a count word; the bare
    * cell's tag is read at offset 0 and 42 stored and read back at offset 8, its first field; then
    * `between`, then the reset.
    */
  private def bare(between: String) =
    s"""|@fx_frame_top = external global ptr
        |declare ptr @fx_frame_alloc(i32, i32, i64)
        |declare ptr @fx_frame_alloc_bare(i32, i32, i64)
        |declare void @fx_frame_reset(ptr)
        |declare void @fx_drop(ptr)
        |declare void @fx_panic_div()
        |
        |define i8 @"fx_u_main"() {
        |entry:
        |  %m = load ptr, ptr @fx_frame_top
        |  %b = call ptr @fx_frame_alloc_bare(i32 7, i32 0, i64 24)
        |  %c = call ptr @fx_frame_alloc(i32 7, i32 0, i64 24)
        |  %p = getelementptr inbounds i8, ptr %b, i64 8
        |  store i64 42, ptr %p
        |  %t = load i32, ptr %b
        |  %v = load i64, ptr %p
        |  %tag = icmp ne i32 %t, 7
        |  %val = icmp ne i64 %v, 42
        |  %bad = or i1 %tag, %val
        |  br i1 %bad, label %fail, label %ok
        |fail:
        |  call void @fx_panic_div()
        |  unreachable
        |ok:
        |$between
        |  call void @fx_frame_reset(ptr %m)
        |  ret i8 0
        |}
        |""".stripMargin

  test("a bare frame cell is its descriptor and payload, 8 bytes less, and no count operation may reach it"):
    List(false, true).foreach { checks =>
      val (code, _, err) = run("bare", bare(""), checks)
      assertEquals(code, 0, err)
      assertEquals(Native.counters(err).get("frame_bytes"), Some(32L + 40L), err)
    }
    val (code, _, err) = run("counted", bare("  call void @fx_drop(ptr %b)"), true)
    assertEquals(code, 103, err)
    assert(err.contains("a count operation on a frame cell without a count word"), err)

  /** Output, runtime counters and LLVM IR of the test program `name` built in `mode`, checked or not. */
  private def ran(name: String, mode: Mode, checks: Boolean, dir: String = "compiler/src/test/fixed/modes") =
    val ir = Backend.compile(unit(Path.of(dir, s"$name.fixed")), mode).fold(ds => fail(s"$ds"), identity)
    val (code, out, err) = run(s"$name-$mode", ir, checks)
    assertEquals(code, 0, err)
    (out, Native.counters(err), ir)

  List(Mode.Default, Mode.NoSpecialise).foreach { mode =>
    test(s"frame cells that only code compiled for them reaches have no count word ($mode)"):
      /** Every cell of `frame_bare` is bare: 8 bytes of descriptor and 8 per field, 8 bytes less
        * than with the count word: 6,015 cells, 128,400 bytes.
        */
      List(false, true).foreach { checks =>
        val (out, counts, ir) = ran("frame_bare", mode, checks)
        assertEquals(out, Files.readString(Path.of("compiler/src/test/fixed/modes/frame_bare.expected")))
        assertEquals((counts("frame_allocs"), counts("frame_bytes")), (6015L, 128400L), checks)
        assert(!ir.contains("call ptr @fx_frame_alloc("), ir)
      }

      /** `temps`'s 2,000,000 boxes: 32 bytes each instead of 40. */
      val (_, temps, _) = ran("temps", mode, checks = false, dir = "bench/runtime")
      assertEquals((temps("frame_allocs"), temps("frame_bytes")), (2000000L, 64000000L))

    test(s"frame cells that code not compiled for them may count keep their count word ($mode)"):
      /** 21 cells with the count word, 16 bytes of header and 8 per field each, and one bare cell,
        * `grow#frame`'s result, read only by `sum#bare0`: 752 + 32 bytes.
        */
      val (out, counts, ir) = ran("frame_counted", mode, checks = true)
      assertEquals(out, Files.readString(Path.of("compiler/src/test/fixed/modes/frame_counted.expected")))
      assertEquals((counts("frame_allocs"), counts("frame_bytes")), (22L, 784L))
      assertEquals("call ptr @fx_frame_alloc_bare\\(".r.findAllIn(ir).size, 1, ir)
  }

  /** The placement of the test program `name` in build mode `mode`. */
  private def placed(name: String, mode: Mode): Frames =
    val a = Backend.analyse(unit(Path.of("compiler", "src", "test", "fixed", "modes", s"$name.fixed")), mode.budget)
      .fold(ds => fail(s"$ds"), identity)
    Frames(Consume(a)._1, a.memory)

  /** Whether `f` places anything on a frame: a constructor, or a call of a `#frame` clone. */
  private def places(p: Frames, f: String): Boolean =
    p.bumped.getOrElse(f, Set.empty).nonEmpty ||
      p.fns.find(_.name == f).exists(g =>
        Core.lets(g.body).exists { case (_, Rhs.Call(c, _, false)) => c.endsWith(Frames.suffix); case _ => false }
      )

  List(Mode.Default, Mode.NoSpecialise).foreach { mode =>
    test(s"values that leave their scope are not placed on its frame ($mode)"):
      val escapes = placed("frame_escapes", mode)
      List("viaResult", "viaField", "viaClosure", "viaAbort", "caught", "collect", "pick").foreach(f =>
        assert(!places(escapes, f), f)
      )
      val more = placed("frame_more", mode)
      List("viaTable", "viaPart", "viaNested", "viaFold", "viaPara", "viaSatisfies", "wrapSum").foreach(f =>
        assert(!places(more, f), f)
      )
      val adv = placed("frame_adv", mode)
      assert(adv.bumped.values.forall(_.isEmpty) && adv.clones.isEmpty, adv.bumped)

    test(s"temporaries that stay in their scope are placed on its frame ($mode)"):
      assert(places(placed("frame_escapes", mode), "main"))
      val more = placed("frame_more", mode)
      List("local", "spin", "main").foreach(f => assert(places(more, f) && more.scoped(f), f))
      assertEquals(more.bumped.get("make#frame").map(_.size), Some(1))
      val imm = placed("frame_imm", mode)
      assert(imm.scoped("extract") && imm.bumped.get("mkBig#frame").exists(_.nonEmpty), imm.bumped)
      assert(!more.scoped("make#frame"))
  }

  /** The checked unit of the program at `path`. */
  private def unit(path: Path) =
    Compiler.compile(CompilationUnit(SourceFile.fromString(path.getFileName.toString, Files.readString(path))))

  test("--no-frames keeps every cell on the heap"):
    val (mode, rest) = fixed.Main.modeOf(List("ship", "--no-frames", "x.fixed"))
    assertEquals((mode.frames, rest), (false, List("ship", "x.fixed")))
    val temps = unit(Path.of("bench", "runtime", "temps.fixed"))
    val ir = (m: Mode) => Backend.compile(temps, m).fold(ds => fail(s"$ds"), identity)
    assert(ir(Mode.Default).contains("call ptr @fx_frame_alloc"))
    val off = ir(Mode.Default.copy(frames = false))
    assert(!off.contains("call ptr @fx_frame_alloc") && !off.contains("call void @fx_frame_reset"), off)

  test("the memory report gives where the code places each allocation, and the calls of #frame clones"):
    val more = unit(Path.of("compiler", "src", "test", "fixed", "modes", "frame_more.fixed"))
    val a = Backend.analyse(more).fold(ds => fail(s"$ds"), identity)
    val report = MemoryReport.text(more, a)
    assert(report.contains("placed: Frame(local)"), report)
    assert(report.contains("placed: Heap; in the caller's frame through `make#frame`"), report)
    assert(report.contains("call: make#frame"), report)
    val bare = unit(Path.of("compiler", "src", "test", "fixed", "modes", "frame_bare.fixed"))
    val bareReport = MemoryReport.text(bare, Backend.analyse(bare).fold(ds => fail(s"$ds"), identity))
    assert(bareReport.contains("placed: Frame(local), no count word"), bareReport)
    assert(bareReport.contains("in the caller's frame through `make#frame`, no count word"), bareReport)
    assert(!MemoryReport.text(more, a, frames = false).contains("placed: Frame("))
