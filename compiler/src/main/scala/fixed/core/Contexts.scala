package fixed.core

/** Lexical typing context. `resume` is set inside a handler clause: (operation result, handler result). */
final case class Context(
    scope: Map[String, SymbolId],
    owner: SymbolId,
    resume: Option[(Type, Type)] = None
):
  def bind(name: String, id: SymbolId): Context = copy(scope = scope.updated(name, id))
