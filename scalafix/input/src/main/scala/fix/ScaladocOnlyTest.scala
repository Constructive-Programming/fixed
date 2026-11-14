/*
rule = ScaladocOnly
ScaladocOnly.allowedPrefixes = ["scalafix:", "rule ="]
 */
package fix

/** Scaladoc on a definition is allowed. Here `assert:` is NOT an allowed
  * prefix, so each `// assert` comment below is itself the offending line
  * comment — the rule must flag exactly those lines.
  */
object ScaladocOnlyTest:
  /** Multi-line scaladoc
    * is allowed too.
    */
  val doc = 1
  // scalafix:off ScaladocOnly
  // suppressed by the directive above
  // scalafix:on ScaladocOnly
  val trailing = 2 // assert: ScaladocOnly
  // assert: ScaladocOnly
  val inString = "// a string is not a comment"
  val inBlockString = "/* nor is this */"
