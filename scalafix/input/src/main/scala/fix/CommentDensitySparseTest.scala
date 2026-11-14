/*
rule = CommentDensity
CommentDensity.maxPercent = 30
 */
package fix

object CommentDensitySparseTest:
  val a = 1 // a trailing comment is a code line
  val b = 2 // so is this one
  val c = 3 // and this one
  val d = 4
  val e = 5
  val f = 6
  val g = 7
  val h = 8
