package fixed.typer

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.core.{Context, Phase, SymKind, SymbolTable}

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
      unit.copy(symbols = typed.symbols).report(namerDiags ++ typed.diags)
    }

  /** Type every top-level `fn` body; returns the finished state. */
  def typeBodies(tree: Tree, symbols: SymbolTable): TyperState =
    val items = tree match
      case Trees.CompilationUnit(items, _) => items
      case _                               => Nil
    val bodies = items.collect { case f: Trees.FnDecl => f }.foldLeft(TyperState(symbols))(typeFnBody)
    Declarations.check(bodies, items).finish

  private def typeFnBody(s: TyperState, fn: Trees.FnDecl): TyperState =
    s.symbols.scope.get(fn.name).filter(s.symbols(_).kind == SymKind.Fn) match
      case Some(id) => Typer.typedFnBody(s, id, fn)(using Context(Map.empty, s.symbols.scope, id))
      case None     => s
