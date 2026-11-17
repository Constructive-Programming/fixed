package fixed.typer

import scala.io.Source

import fixed.ast.{Tree, Trees}
import fixed.parsing.Parser
import fixed.util.SourceFile

/** The signature-only standard library under `resources/stdlib/`, parsed once. `prelude` is in
  * scope everywhere; each module is reachable through `use <module>.<Name>`.
  */
object Stdlib:
  lazy val prelude: List[Tree] = load("prelude")

  lazy val modules: Map[String, List[Tree]] = Map("std.io" -> load("io"))

  private def load(name: String): List[Tree] =
    val path = s"stdlib/$name.fixed"
    val source = Source.fromResource(path)
    val result =
      try Parser.parse(SourceFile.fromString(path, source.mkString))
      finally source.close()
    require(!result.hasErrors, s"$path does not parse: ${result.diagnostics}")
    result.tree match
      case Trees.CompilationUnit(items, _) => items
      case other                           => List(other)
