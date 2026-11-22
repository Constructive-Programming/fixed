package fixed.backend

import java.nio.file.Path

/** The data stack of `Frame` cells (`spec/memory_management.md` Rules MM6.1, MM6.2) in the runtime,
  * driven by hand-written LLVM IR: bump allocation over several chunks, a mark and a reset, the
  * counters, and the checked build's poison.
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
    val exe = Backend.ship(ir, dir.resolve(s"$name-$checks"), checks).fold(d => fail(d.message), identity)
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
