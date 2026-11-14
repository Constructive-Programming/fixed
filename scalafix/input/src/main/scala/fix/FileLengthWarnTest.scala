/*
rule = FileLength
FileLength.warnLines = 12
FileLength.errorLines = 16
 */
package fix

object FileLengthWarnTest:
  val a = 1
  val b = 2
  val c = 3
  val d = 4
  val e = 5 // assert: FileLength
  val f = 6
