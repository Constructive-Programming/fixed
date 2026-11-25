package fixed.backend

import java.nio.file.{Files, Path}

import fixed.ast.{Tree, Trees}
import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

class FormalExportSuite extends munit.FunSuite:
  private val fixture = Path.of("formal", "Fixed", "Integration", "CompilerExport.lean")

  private def mainBody(source: String): (CompilationUnit, Tree) =
    val unit = Compiler.compile(CompilationUnit(SourceFile.fromString("<formal-export>", source)))
    assert(!unit.hasErrors, unit.diagnostics)
    val main = unit.tree.toList.flatMap {
      case Trees.CompilationUnit(items, _) => items
      case _                               => Nil
    }.collectFirst { case fn: Trees.FnDecl if fn.name == "main" => fn }.get
    (unit, main.body.get)

  private def check(name: String, source: String, expected: String): Unit =
    val (unit, body) = mainBody(source)
    assertEquals(FormalExport.definition(name, unit, body), Right(expected))
    val lean = Files.readString(fixture)
    assert(lean.contains(expected), s"$name is not synchronized with $fixture")

  test("identity application exports the inferred one quantity"):
    check(
      "compilerIdentity",
      "fn main() -> bool =\n    ((x: bool) -> x)(true)\n",
      "def compilerIdentity : QTerm :=\n  (.app (.lam .one .bool (.var 0)) (.bool true))"
    )

  test("a capture exports de Bruijn indices and the unused parameter's zero quantity"):
    check(
      "compilerCapture",
      "fn main() -> bool =\n    ((x: bool) -> (y: ()) -> x)(false)(())\n",
      "def compilerCapture : QTerm :=\n  (.app (.app (.lam .many .bool (.lam .zero .unit (.var 1))) (.bool false)) .unit)"
    )

  test("higher-order reuse exports the inferred many quantity"):
    check(
      "compilerReuse",
      "fn main() -> bool =\n    ((f: bool -> bool) -> f(f(true)))((x: bool) -> x)\n",
      "def compilerReuse : QTerm :=\n  (.app (.lam .many (.arr .bool .bool) (.app (.var 0) (.app (.var 0) (.bool true)))) (.lam .one .bool (.var 0)))"
    )

  test("conditionals export both branches"):
    check(
      "compilerIf",
      "fn main() -> bool =\n    if true:\n        ((x: bool) -> x)(false)\n    else:\n        true\n",
      "def compilerIf : QTerm :=\n  (.ite (.bool true) (.app (.lam .one .bool (.var 0)) (.bool false)) (.bool true))"
    )

  test("unsupported nodes and free identifiers are rejected"):
    val (numeric, numericBody) = mainBody("fn main() -> u64 =\n    1\n")
    assert(FormalExport.definition("numeric", numeric, numericBody).isLeft)
    val (free, freeBody) = mainBody(
      "fn id(x: bool) -> bool = x\n\nfn main() -> bool =\n    id(true)\n"
    )
    assertEquals(
      FormalExport.definition("free", free, freeBody).left.toOption.exists(_.contains("free or top-level identifier")),
      true
    )
