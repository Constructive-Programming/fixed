package fixed.core

/** Lexical typing context: the names in scope and the symbol that owns new local bindings. */
final case class Context(scope: Map[String, SymbolId], owner: SymbolId):
  def bind(name: String, id: SymbolId): Context = copy(scope = scope.updated(name, id))
