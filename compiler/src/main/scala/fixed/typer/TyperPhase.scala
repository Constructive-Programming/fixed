package fixed.typer

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Phase, SymKind, SymbolId, SymbolTable}

/** Namer, then Typer: types the body of every top-level `fn` against its declared return type,
  * then checks `satisfies` and capability declarations (3.1.M3).
  * Cap, effect and satisfies method bodies join with capability member resolution (M5).
  */
object TyperPhase extends Phase:
  val name: String = "typer"

  def run(unit: CompilationUnit): CompilationUnit =
    unit.tree.fold(unit) { tree =>
      val (symbols, namerDiags) = Namer.index(tree)
      val typed = typeBodies(tree, symbols)
      unit.copy(symbols = typed.symbols, typing = Some(typed)).report(namerDiags ++ typed.diags)
    }

  /** Type every top-level `fn` body; returns the finished state. */
  def typeBodies(tree: Tree, symbols: SymbolTable): TyperState =
    val items = tree match
      case Trees.CompilationUnit(items, _) => items
      case _                               => Nil
    val bodies = items.collect { case f: Trees.FnDecl => f }.foldLeft(TyperState(symbols))(typeFnBody)
    val withPrelude =
      Stdlib.prelude.collect { case f: Trees.FnDecl if f.body.isDefined => f }.foldLeft(bodies)(typePreludeFn)
    Declarations.check(withPrelude, preludeSatisfactions(symbols) ++ items).finish

  /** The prelude's `satisfies` declarations, whose method bodies are typed with the unit's and
    * lowered where no generated member stands in for them; none when the unit declares a top-level
    * name the prelude declares too, since the bodies are typed in the unit's scope.
    */
  def preludeSatisfactions(symbols: SymbolTable): List[Trees.SatisfiesDecl] =
    val prelude = symbols.denotations.filter(_.owner == SymbolId.Prelude).map(_.name.stripPrefix("prelude.")).toSet
    val shadowed = symbols.scope.exists((name, id) => symbols(id).owner == SymbolId.Root && prelude(name))
    if shadowed then Nil else Stdlib.prelude.collect { case d: Trees.SatisfiesDecl => d }

  /** A prelude function with a body, unless the unit declares its own of the same name. */
  private def typePreludeFn(s: TyperState, fn: Trees.FnDecl): TyperState =
    if s.symbols.scope.get(fn.name).exists(s.symbols(_).owner == SymbolId.Prelude) then typeFnBody(s, fn) else s

  private def typeFnBody(s: TyperState, fn: Trees.FnDecl): TyperState =
    s.symbols.scope.get(fn.name).filter(s.symbols(_).kind == SymKind.Fn) match
      case Some(id) => Typer.typedFnBody(s, id, fn)(using Context(Map.empty, s.symbols.scope, id))
      case None     => s
