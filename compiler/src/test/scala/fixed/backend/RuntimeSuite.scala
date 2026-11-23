package fixed.backend

import java.nio.file.Path

/** The C runtime's heap and build switches, driven by hand-written LLVM IR: counters compiled in
  * only when asked for (`ship --stats`).
  */
class RuntimeSuite extends munit.FunSuite:

  private val dir = Path.of("target", "runtime-tests")

  /** `fx_u_main` running `body`. */
  private def program(body: String) =
    s"""|declare ptr @fx_alloc(i32, i32, i64)
        |declare void @fx_dup(ptr)
        |declare void @fx_drop(ptr)
        |declare ptr @fx_reuse(ptr)
        |
        |define i8 @"fx_u_main"() {
        |entry:
        |$body
        |  ret i8 0
        |}
        |""".stripMargin

  private def run(name: String, ir: String, checks: Boolean, stats: Boolean): (Int, String, String) =
    val exe = Backend.ship(ir, dir.resolve(name), checks, stats).fold(d => fail(d.message), identity)
    Native.run(exe)

  private val allocDrop =
    "  %c = call ptr @fx_alloc(i32 1, i32 0, i64 16)\n  call void @fx_dup(ptr %c)\n" +
      "  call void @fx_drop(ptr %c)\n  call void @fx_drop(ptr %c)"

  test("counters are compiled in only when asked for, and a build without them says so"):
    val (code, _, err) = run("stats", program(allocDrop), checks = false, stats = true)
    assertEquals(code, 0, err)
    val counts = Native.counters(err)
    assertEquals(List("allocs", "frees", "dups", "drops").map(counts.get), List(1L, 1L, 1L, 2L).map(Some(_)), err)
    List(false, true).foreach { checks =>
      val (code2, _, err2) = run(s"nostats-$checks", program(allocDrop), checks, stats = false)
      assertEquals(code2, 0, err2)
      assert(err2.contains("built without counters"), err2)
      assert(!Native.counters(err2).contains("allocs"), err2)
      assert(Native.counters(err2).contains("wall_ns"), err2)
    }
