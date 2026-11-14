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
