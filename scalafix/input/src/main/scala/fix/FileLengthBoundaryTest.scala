/*
rule = FileLength
FileLength.warnLines = 12
FileLength.errorLines = 16
 */
package fix

/** Exactly `errorLines` lines: a warning, not yet an error. */
object FileLengthBoundaryTest:
  val a = 1
  val b = 2
  val c = 3
  val d = 4 // assert: FileLength
  val e = 5
  val f = 6
  val g = 7
