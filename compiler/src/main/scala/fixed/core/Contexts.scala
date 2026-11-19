package fixed.core

/** Lexical typing context: body bindings (`local`) over the top-level scope (`global`), so a
  * binding copies only the small local map. `resume` is set inside a handler clause.
  */
final case class Context(
    local: Map[String, SymbolId],
    global: Map[String, SymbolId],
    owner: SymbolId,
    resume: Option[(Type, Type)] = None
):
  def bind(name: String, id: SymbolId): Context = copy(local = local.updated(name, id))

  def lookup(name: String): Option[SymbolId] =
    val found = local.get(name)
    if found.isDefined then found else global.get(name)

  /** The scope innermost first, for signatures typed inside a body. */
  def frames: List[Map[String, SymbolId]] = List(local, global)
