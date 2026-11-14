/*
rule = NoGenericWords
NoGenericWords.words = [grain, leg, arm, stuff, helper, thing]
NoGenericWords.allowedNames = [SideArm]
 */
package fix

object NoGenericWordsTest:
  def grainSelection(): Int = 1 // assert: NoGenericWords
  class ParseStageLeg // assert: NoGenericWords
  val leg = 1 // assert: NoGenericWords
  val grain_size = 2 // assert: NoGenericWords
  type HTTPLeg = Int // assert: NoGenericWords
  def walk(legs: Int): Int = legs // assert: NoGenericWords
  def pick(o: Option[Int]): Int = o match { case Some(armCount) => armCount; case None => 0 } // assert: NoGenericWords
  enum Kind { case Plain, StuffKind } // assert: NoGenericWords
  def sort[ThingT](x: ThingT): ThingT = x // assert: NoGenericWords
  val alarm = 3
  val legacy = 4
  val harmonic = 5
  val legal = 6
  val stuffed = 7
  final case class SideArm(x: Int)
  val selected = grainSelection()
