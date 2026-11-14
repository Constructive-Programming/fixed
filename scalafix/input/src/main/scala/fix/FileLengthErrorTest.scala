/*
rule = FileLength
FileLength.warnLines = 12
FileLength.errorLines = 16
 */
package fix

object FileLengthErrorTest:
  val a = 1
  val b = 2
  val c = 3
  val d = 4
  val e = 5
  val f = 6
  val g = 7
  val h = 8
  val i = 9 // assert: FileLength
  val j = 10
