package fixed.backend

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.core.Type
import fixed.qtt.Quantity
import fixed.typer.Members

/** Export the simply typed, pure expression fragment to the Lean `QTerm` model.
  * This is deliberately not a lowering for the full language: unsupported nodes
  * are rejected instead of being assigned approximate resource semantics.
  */
object FormalExport:
  private val leanName = "[A-Za-z_][A-Za-z0-9_]*".r

  def definition(name: String, unit: CompilationUnit, body: Tree): Either[String, String] =
    if !leanName.matches(name) then Left(s"invalid Lean definition name `$name`")
    else if unit.hasErrors then Left("cannot export a unit with compiler diagnostics")
    else
      term(unit, body, Nil).map { rendered =>
        s"def $name : QTerm :=\n  $rendered"
      }

  private def term(unit: CompilationUnit, tree: Tree, env: List[String]): Either[String, String] =
    tree match
      case Trees.UnitLit(_)             => Right(".unit")
      case Trees.BoolLit(value, _)      => Right(if value then "(.bool true)" else "(.bool false)")
      case Trees.Block(List(single), _) => term(unit, single, env)
      case Trees.Ident(name, _)         =>
        val index = env.indexOf(name)
        if index < 0 then Left(s"unsupported free or top-level identifier `$name`")
        else Right(s"(.var $index)")
      case Trees.LambdaExpr(List(param), body, _) =>
        for
          arrow <- required(unit.typeOf(tree), tree, "lambda type")
          (paramType, resultType) <- singleArrow(arrow, tree)
          quantity <- unit.quantities.flatMap(_.of(param)) match
            case Some(q) => Right(q)
            case None    => Left("missing inferred lambda quantity")
          renderedBody <- term(unit, body, param.name :: env)
          renderedParam <- ty(paramType, tree)
          _ <- ty(resultType, tree)
        yield s"(.lam ${renderQuantity(quantity)} $renderedParam $renderedBody)"
      case lambda: Trees.LambdaExpr      => Left(unsupported(lambda, "only single-parameter lambdas are supported"))
      case Trees.Apply(fn, List(arg), _) =>
        for
          renderedFn <- term(unit, fn, env)
          renderedArg <- term(unit, arg, env)
        yield s"(.app $renderedFn $renderedArg)"
      case apply: Trees.Apply => Left(unsupported(apply, "only single-argument application is supported"))
      case Trees.IfExpr(cond, thenBranch, elseBranch, _) =>
        for
          renderedCond <- term(unit, cond, env)
          renderedThen <- term(unit, thenBranch, env)
          renderedElse <- term(unit, elseBranch, env)
        yield s"(.ite $renderedCond $renderedThen $renderedElse)"
      case other => Left(unsupported(other, other.getClass.getSimpleName))

  private def ty(t: Type, tree: Tree): Either[String, String] =
    t match
      case Type.UnitType         => Right(".unit")
      case Type.PrimType("bool") => Right(".bool")
      case Type.NeverType        => Right(".empty")
      case arrow: Type.ArrowType =>
        singleArrow(arrow, tree).flatMap { (param, rhs) =>
          for
            renderedParam <- ty(param, tree)
            renderedResult <- ty(rhs, tree)
          yield s"(.arr $renderedParam $renderedResult)"
        }
      case other => Left(unsupported(tree, s"type ${other.getClass.getSimpleName}"))

  private def singleArrow(t: Type, tree: Tree): Either[String, (Type, Type)] =
    t match
      case Type.ArrowType(lhs, rhs, fx) if fx.effects.isEmpty =>
        Members.params(lhs) match
          case List(param) => Right((param, rhs))
          case _           => Left(unsupported(tree, "only single-parameter lambdas are supported"))
      case _ => Left(unsupported(tree, "lambda does not have a pure arrow type"))

  private def renderQuantity(q: Quantity): String =
    q match
      case Quantity.Zero => ".zero"
      case Quantity.One  => ".one"
      case Quantity.Many => ".many"

  private def required[A](value: Option[A], tree: Tree, what: String): Either[String, A] =
    value.toRight(unsupported(tree, s"missing $what"))

  private def unsupported(tree: Tree, what: String): String =
    s"unsupported formal export at ${tree.span}: $what"
