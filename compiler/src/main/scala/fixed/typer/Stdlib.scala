package fixed.typer

import scala.io.Source

import fixed.ast.{Tree, Trees}
import fixed.core.{SymbolId, SymbolTable}
import fixed.parsing.Parser
import fixed.util.SourceFile

/** The signature-only standard library under `resources/stdlib/`, parsed once. `prelude` is in
  * scope everywhere; each module is reachable through `use <module>.<Name>`.
  */
object Stdlib:
  lazy val prelude: List[Tree] = load("prelude")

  lazy val modules: Map[String, List[Tree]] = Map("std.io" -> load("io"))

  /** Top-level functions with bodies, by symbol: the prelude's, each found in the prelude's scope,
    * and `unit`'s, found in the program's.
    */
  def functions(unit: Option[Tree], symbols: SymbolTable): Map[SymbolId, Trees.FnDecl] =
    val own = unit.toList.flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
    def bodies(items: List[Tree], scope: Map[String, SymbolId]) =
      items.collect { case f: Trees.FnDecl if f.body.isDefined => scope.get(f.name).map(_ -> f) }.flatten
    (bodies(prelude, symbols.prelude) ++ bodies(own, symbols.scope)).toMap

  private def load(name: String): List[Tree] =
    val path = s"stdlib/$name.fixed"
    val source = Source.fromResource(path)
    val result =
      try Parser.parse(new SourceFile(path, source.mkString, library = true))
      finally source.close()
    require(!result.hasErrors, s"$path does not parse: ${result.diagnostics}")
    result.tree match
      case Trees.CompilationUnit(items, _) => items
      case other                           => List(other)
