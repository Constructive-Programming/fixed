package fixed.core

import fixed.CompilationUnit

/** A compiler pass: a pure function from a unit to the unit with this phase's results added. */
trait Phase:
  def name: String
  def run(unit: CompilationUnit): CompilationUnit
