package fixed.backend

import java.nio.file.Path

/** The C runtime's heap and build switches, driven by hand-written LLVM IR: counters compiled in
  * only when asked for (`ship --stats`), the free lists per cell size, and the checked build's
  * poison of a freed cell.
  */
class RuntimeSuite extends munit.FunSuite:

  private val dir = Path.of("target", "runtime-tests")

  /** `fx_u_main` running `body`. */
  private def program(body: String) =
    s"""|declare ptr @fx_alloc(i32, i32, i64)
        |declare void @fx_dup(ptr)
        |declare void @fx_drop(ptr)
        |declare ptr @fx_reuse(ptr)
        |declare void @fx_panic_div()
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

  /** Allocate cell `a` (two slots, 32 bytes), free it, then allocate `c` of three slots and `b` of
    * two: `b` is `a`'s block, taken from the free list of its size, and `c` is not. (glibc's
    * `malloc` gives `a`'s block to `c`, the next request in the same bin of 48 bytes.)
    */
  test("a freed cell goes on the free list of its size and the next allocation of that size takes it"):
    val ir = program(
      """|  %a = call ptr @fx_alloc(i32 1, i32 0, i64 16)
         |  call void @fx_drop(ptr %a)
         |  %c = call ptr @fx_alloc(i32 3, i32 0, i64 24)
         |  %b = call ptr @fx_alloc(i32 2, i32 0, i64 16)
         |  %same = icmp eq ptr %a, %b
         |  %other = icmp ne ptr %a, %c
         |  %ok = and i1 %same, %other
         |  br i1 %ok, label %fine, label %bad
         |bad:
         |  call void @fx_panic_div()
         |  unreachable
         |fine:
         |  call void @fx_drop(ptr %b)
         |  call void @fx_drop(ptr %c)""".stripMargin
    )
    List(false, true).foreach { checks =>
      val (code, _, err) = run(s"lists-$checks", ir, checks, stats = true)
      assertEquals(code, 0, err)
      val counts = Native.counters(err)
      assertEquals((counts.get("allocs"), counts.get("frees")), (Some(3L), Some(3L)), err)
    }

  /** A cell freed, then reached by `op`: a checked build stops; the cell is on a free list. */
  List("call void @fx_dup(ptr %a)", "call void @fx_drop(ptr %a)", "%t = call ptr @fx_reuse(ptr %a)").foreach { op =>
    test(s"a checked build stops on a freed cell reached by: $op"):
      val ir = program(s"  %a = call ptr @fx_alloc(i32 1, i32 0, i64 16)\n  call void @fx_drop(ptr %a)\n  $op")
      val (code, _, err) = run(s"freed-${op.hashCode.abs}", ir, checks = true, stats = true)
      assertEquals(code, 103, err)
      assert(err.contains("a count operation on a freed cell"), err)
  }

  test("a checked build stops on a cell whose freed child its drop reaches"):
    val ir = program(
      """|  %a = call ptr @fx_alloc(i32 1, i32 0, i64 16)
         |  %p = call ptr @fx_alloc(i32 1, i32 1, i64 8)
         |  %s = getelementptr inbounds i8, ptr %p, i64 16
         |  store ptr %a, ptr %s
         |  call void @fx_drop(ptr %a)
         |  call void @fx_drop(ptr %p)""".stripMargin
    )
    val (code, _, err) = run("freed-child", ir, checks = true, stats = true)
    assertEquals(code, 103, err)
    assert(err.contains("a count operation on a freed cell"), err)

  test("a cell larger than the free lists' sizes comes from malloc and is freed"):
    val (code, _, err) = run("large", program(allocDrop.replace("i64 16", "i64 1000")), checks = true, stats = true)
    assertEquals(code, 0, err)
    assertEquals((Native.counters(err).get("allocs"), Native.counters(err).get("frees")), (Some(1L), Some(1L)), err)
