package fixed.typer

import fixed.core.SymbolId

/** What the typer records for the backend about a tree (slice plan §4.3): the candidate a method
  * call resolved to, or a nested `fn`'s symbol.
  */
enum Resolution:
  case Field(variant: SymbolId, index: Int)
  case CapMember(cap: SymbolId, member: SymbolId)
  case DerivedFold(data: SymbolId)
  case NestedFn(fn: SymbolId)
