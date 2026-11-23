package fixed.util

import munit.FunSuite

class SourceFileSuite extends FunSuite:

  test("lineColumn maps offsets across lines, including line starts and EOF"):
    val sf = SourceFile("t", "ab\ncd\n\nx")
    assertEquals(sf.lineOffsets, IndexedSeq(0, 3, 6, 7, 9))
    assertEquals(sf.lineColumn(0), (1, 1))

    /** the '\n' itself */
    assertEquals(sf.lineColumn(2), (1, 3))
    assertEquals(sf.lineColumn(3), (2, 1))

    /** empty line */
    assertEquals(sf.lineColumn(6), (3, 1))

    /** EOF */
    assertEquals(sf.lineColumn(8), (4, 2))
    assertEquals(SourceFile("e", "").lineColumn(0), (1, 1))

  test("a diagnostic whose span is in a library file is formatted at that file's line and column"):
    val library = SourceFile("lib", "x\nyz\n", library = true)
    val unit = Reporter(SourceFile("t", "abcdefgh"))
    assertEquals(
      unit.format(Diagnostic(Severity.Error, DiagCode.B005, Span(3, 4, Some(library)), "m")),
      "lib:2:2: error[B005]: m"
    )
    assertEquals(unit.format(Diagnostic(Severity.Error, DiagCode.B005, Span(3, 4), "m")), "t:1:4: error[B005]: m")
    assertEquals(unit.format(Diagnostic(Severity.Error, DiagCode.B005, Span(40, 41), "m")), "t: error[B005]: m")
