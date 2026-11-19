package fixed.typer

import fixed.core.SymbolId

/** Which candidate a method call resolved to, recorded for the backend (slice plan §4.3). */
enum Resolution:
  case Field(variant: SymbolId, index: Int)
  case CapMember(cap: SymbolId, member: SymbolId)
  case DerivedFold(data: SymbolId)
