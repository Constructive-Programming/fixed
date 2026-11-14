/*
rule = ScaladocOnly
ScaladocOnly.allowedPrefixes = ["scalafix:", "rule =", "assert:"]
 */
package fix

object ScaladocOnlyBlockTest:
  /* a block comment is not allowed */ val a = 1 // assert: ScaladocOnly
  val b = 2 /* nor a trailing block */ // assert: ScaladocOnly
  /**/ val c = 3 // assert: ScaladocOnly
  val d = 4
