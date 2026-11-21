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
      val recursion = if typed.errors == 0 then Totality.check(tree, typed) else Nil
      unit.copy(symbols = typed.symbols, typing = Some(typed)).report(namerDiags ++ typed.diags ++ recursion)
    }

  /** Type every top-level `fn` body and check the declarations, the program's in its scope and the
    * prelude's in the prelude's; returns the finished state.
    */
  def typeBodies(tree: Tree, symbols: SymbolTable): TyperState =
    val items = tree match
      case Trees.CompilationUnit(items, _) => items
      case _                               => Nil
    val bodies = items.collect { case f: Trees.FnDecl => f }.foldLeft(TyperState(symbols))(typeFnBody)
    val withPrelude = inPrelude(bodies) { s =>
      val fns = Stdlib.prelude.collect { case f: Trees.FnDecl if f.body.isDefined => f }.foldLeft(s)(typeFnBody)
      Declarations.check(fns, preludeSatisfactions)
    }
    Declarations.check(withPrelude, items).finish

  /** The prelude's `satisfies` declarations; the backend lowers their bodies too. */
  lazy val preludeSatisfactions: List[Trees.SatisfiesDecl] =
    Stdlib.prelude.collect { case d: Trees.SatisfiesDecl => d }

  /** `f` on the prelude's code, which resolves names in the prelude's scope, finds members only in
    * the prelude's capabilities and resolves constructors only to the prelude's data types (Rule 8.6).
    */
  private def inPrelude(s: TyperState)(f: TyperState => TyperState): TyperState =
    val done = f(s.copy(symbols = s.symbols.copy(scope = s.symbols.prelude), inPrelude = true))
    done.copy(symbols = done.symbols.copy(scope = s.symbols.scope), inPrelude = false)

  private def typeFnBody(s: TyperState, fn: Trees.FnDecl): TyperState =
    s.symbols.scope.get(fn.name).filter(s.symbols(_).kind == SymKind.Fn) match
      case Some(id) => Typer.typedFnBody(s, id, fn)(using Context(Map.empty, s.symbols.scope, id))
      case None     => s
