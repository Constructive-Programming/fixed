package fixed.ast

import fixed.util.Span

/** The Fixed AST. Single hierarchy; every node carries a source span.
  *
  * In Phase 2 every node is "untyped" — i.e. type slots aren't populated.
  * When the typer (Phase 3) lands we'll thread types via a side table keyed
  * on tree identity, or extend this hierarchy with a Tree[T <: Untyped]
  * variant per dotc. For now: minimal, plain.
  *
  * `Tree` is intentionally NOT sealed — Phase 2.2 will introduce a
  * concrete-syntax-tree (CST) view that produces a different `Tree`
  * implementor outside this file. Keeping the trait open today avoids a
  * breaking change later. See `docs/plans/phase-2.1-incremental-parser.md`
  * §5. (Until Phase 3, exhaustive `match` on `Tree` is not relied on; every
  * call site already has a `case _ => fail(...)` fallback.)
  */
trait Tree:
  def span: Span

object Trees:

  // ---- Recovery gap nodes (Phase 2.1) ----

  /** A production that began but could not complete. `recovered` holds
    * whatever subtrees were built before the parser bailed; often empty.
    * The matching diagnostic carries the human-readable explanation.
    *
    * See spec/syntax_grammar.ebnf Appendix A.1 and
    * docs/plans/phase-2.1-incremental-parser.md §3.2. */
  final case class Error(recovered: List[Tree], span: Span) extends Tree

  /** A required production that was absent entirely (e.g. `fn` with no
    * name). Span is zero-length, located where the missing token was
    * expected. `expected` is a grammar-level description.
    *
    * See spec/syntax_grammar.ebnf Appendix A.1.1. */
  final case class Missing(expected: String, span: Span) extends Tree

  // ---- Compilation unit ----

  final case class CompilationUnit(items: List[Tree], span: Span) extends Tree

  // ---- Top-level declarations ----

  /** `use <dotted path> [satisfies <CapRef>]`. The path is a list of
    * identifier segments (not yet resolved). */
  final case class UseDecl(
      path: List[String],
      satisfies: Option[Tree],
      span: Span
  ) extends Tree

  final case class FnDecl(
      name: String,
      typeParamsHint: List[String],
      params: List[FnParam],
      returnType: Tree,
      withClause: Option[Tree],
      body: Option[Tree],
      span: Span
  ) extends Tree

  final case class CapDecl(
      name: String,
      ofParams: List[Tree],
      extendsList: List[Tree],
      body: List[Tree],
      span: Span
  ) extends Tree

  final case class DataDecl(
      name: String,
      ofParams: List[Tree],
      variants: List[DataVariant],
      span: Span
  ) extends Tree

  final case class EffectDecl(
      isLinear: Boolean,
      name: String,
      ofParams: List[Tree],
      members: List[Tree],
      span: Span
  ) extends Tree

  final case class TypeAlias(
      name: String,
      valueParams: List[FnParam],
      rhs: Tree,
      span: Span
  ) extends Tree

  final case class SatisfiesDecl(
      typeName: String,
      capRef: Tree,
      mappings: List[Tree],
      span: Span
  ) extends Tree

  final case class ModDecl(
      name: String,
      items: List[Tree],
      span: Span
  ) extends Tree

  // ---- Function-related ----

  final case class FnParam(
      name: String,
      typeAnn: Tree,
      default: Option[Tree],
      span: Span
  ) extends Tree

  // ---- Cap members ----

  final case class InstanceMethod(
      name: String,
      typeParamsHint: List[String],
      params: List[FnParam],
      returnType: Tree,
      withClause: Option[Tree],
      body: Option[Tree],
      span: Span
  ) extends Tree

  final case class StaticMethod(
      name: String,
      typeParamsHint: List[String],
      params: List[FnParam],
      returnType: Tree,
      withClause: Option[Tree],
      body: Option[Tree],
      span: Span
  ) extends Tree

  final case class PropDecl(
      name: String,
      body: Tree,
      span: Span
  ) extends Tree

  // ---- Data variants ----

  final case class DataVariant(
      name: String,
      fields: List[FieldDecl],
      span: Span
  ) extends Tree

  final case class FieldDecl(
      name: String,
      typeAnn: Tree,
      default: Option[Tree],
      span: Span
  ) extends Tree

  // ---- Satisfaction mappings ----

  final case class ConstructorMapping(
      variant: String,
      capCtor: String,
      span: Span
  ) extends Tree

  final case class ImpossibleMapping(
      capCtor: String,
      span: Span
  ) extends Tree

  // ---- Expressions ----

  final case class Block(stmts: List[Tree], span: Span) extends Tree

  final case class LetExpr(pattern: Tree, init: Tree, span: Span) extends Tree

  final case class IfExpr(
      cond: Tree,
      thenBranch: Tree,
      elseBranch: Tree,
      span: Span
  ) extends Tree

  final case class MatchExpr(
      scrutinee: Tree,
      arms: List[MatchArm],
      span: Span
  ) extends Tree

  final case class MatchArm(
      pattern: Tree,
      guard: Option[Tree],
      body: Tree,
      span: Span
  ) extends Tree

  final case class HandleExpr(
      subject: Tree,
      arms: List[HandlerArm],
      span: Span
  ) extends Tree

  final case class HandlerArm(
      effect: String,
      op: String,
      params: List[Tree],
      body: Tree,
      span: Span
  ) extends Tree

  final case class ReturnArm(param: Tree, body: Tree, span: Span) extends Tree

  final case class DoExpr(stmts: List[Tree], span: Span) extends Tree

  final case class DoBind(pattern: Tree, expr: Tree, span: Span) extends Tree

  /** Lambda. Params may carry optional type annotations and defaults. */
  final case class LambdaExpr(
      params: List[FnParam],
      body: Tree,
      span: Span
  ) extends Tree

  /** `recv.method(args)` or `recv.method` (the latter has no `(...)` per the
    * grammar — a zero-arg method-style call). */
  final case class MethodCall(
      receiver: Tree,
      method: String,
      args: List[Tree],
      hasArgList: Boolean,
      span: Span
  ) extends Tree

  /** `T.member(args)` or `T.member` — same shape as MethodCall but with
    * the qualifier guaranteed to be an Ident referring to a type. The
    * typer will distinguish static-method calls from variant constructions. */
  final case class StaticCall(
      qualifier: String,
      method: String,
      args: List[Tree],
      hasArgList: Boolean,
      span: Span
  ) extends Tree

  /** Plain `f(args)` — a value-level function call. */
  final case class Apply(fn: Tree, args: List[Tree], span: Span) extends Tree

  final case class BinOp(
      op: String,
      lhs: Tree,
      rhs: Tree,
      span: Span
  ) extends Tree

  final case class UnaryOp(
      op: String,
      operand: Tree,
      span: Span
  ) extends Tree

  final case class Pipe(lhs: Tree, rhs: Tree, span: Span) extends Tree

  final case class Ident(name: String, span: Span) extends Tree

  final case class IntLit(value: BigInt, span: Span) extends Tree
  final case class FloatLit(value: BigDecimal, span: Span) extends Tree
  final case class StringLit(value: String, span: Span) extends Tree
  final case class CharLit(value: Char, span: Span) extends Tree
  final case class BoolLit(value: Boolean, span: Span) extends Tree
  final case class UnitLit(span: Span) extends Tree

  final case class TupleExpr(elements: List[Tree], span: Span) extends Tree
  final case class ListExpr(elements: List[Tree], span: Span) extends Tree

  final case class StructLit(
      typeName: String,
      fields: List[(String, Tree)],
      span: Span
  ) extends Tree

  final case class Resume(value: Option[Tree], span: Span) extends Tree

  final case class Unreachable(span: Span) extends Tree

  final case class ForallExpr(
      binders: List[FnParam],
      suchThat: Option[Tree],
      body: Tree,
      span: Span
  ) extends Tree

  final case class Implies(
      antecedent: Tree,
      consequent: Tree,
      span: Span
  ) extends Tree

  // ---- Patterns ----

  final case class WildcardPat(span: Span) extends Tree
  final case class BinderPat(name: String, span: Span) extends Tree
  final case class LiteralPat(value: Tree, span: Span) extends Tree
  final case class TuplePat(elements: List[Tree], span: Span) extends Tree

  /** `T.Variant(p1, p2, ...)` or, for a unit variant, `T.Variant` with no
    * fields — `hasArgList` distinguishes the two so the parser preserves
    * the source-level "no parens after a unit variant" rule. */
  final case class DataVariantPat(
      qualifier: Option[String],
      variant: String,
      fields: List[Tree],
      hasArgList: Boolean,
      span: Span
  ) extends Tree

  final case class StructDestructurePat(
      typeName: String,
      fields: List[(String, Tree)],
      hasRest: Boolean,
      span: Span
  ) extends Tree

  final case class OrPat(left: Tree, right: Tree, span: Span) extends Tree
  final case class GuardedPat(pat: Tree, cond: Tree, span: Span) extends Tree

  // ---- Type expressions ----

  /** `is C + D + ...` (anonymous capability bound). */
  final case class IsBound(caps: List[Tree], span: Span) extends Tree

  /** `N is C + D + ...` (named capability bound — introduces type variable N). */
  final case class NamedAlias(
      name: String,
      caps: List[Tree],
      span: Span
  ) extends Tree

  /** `C of A` — a capability reference, optionally with a type-arg. */
  final case class CapRef(name: String, ofArg: Option[Tree], span: Span) extends Tree

  /** `lower_ident(args)` in is-bound position — a refinement-cap call. */
  final case class RefinementCall(
      name: String,
      args: List[Tree],
      span: Span
  ) extends Tree

  /** Primitive type ref: `i64`, `String`, `bool`, etc. */
  final case class PrimitiveType(name: String, span: Span) extends Tree

  /** Bare type reference (uppercase ident, optionally with `of`). */
  final case class TypeRef(name: String, ofArg: Option[Tree], span: Span) extends Tree

  /** Function type `Lhs -> Rhs [with R]`. Lhs is either a single
    * TypeAtom-shape or a parenthesized type list. */
  final case class ArrowType(
      lhs: Tree,
      rhs: Tree,
      withClause: Option[Tree],
      span: Span
  ) extends Tree

  /** `(A, B, ...)` as the LHS of an arrow type. Distinct from a tuple type
    * because Fixed (v0.4.5) doesn't have first-class tuple types — the
    * parens-with-comma form only appears as an arrow LHS. */
  final case class TupleArrowLhs(types: List[Tree], span: Span) extends Tree

  /** The unit type `()` — written as a 0-arity tuple in some positions. */
  final case class UnitType(span: Span) extends Tree

  /** The never type `!`. */
  final case class NeverType(span: Span) extends Tree

  /** `Self` or `Self of B`. */
  final case class SelfType(ofArg: Option[Tree], span: Span) extends Tree

  /** `cap of T` or `cap extends C` in any TypeExpr position. */
  final case class CapType(returnSpec: Option[CapReturnSpec], span: Span) extends Tree

  enum CapReturnSpec:
    case OfArg(arg: Tree)
    case Extends(caps: List[Tree])

  /** `(TypeExpr) of OfArg` — the explicit-binding HKT-application form
    * added in v0.4.5. */
  final case class ParenTypeApp(
      inner: Tree,
      ofArg: Tree,
      span: Span
  ) extends Tree

  // ---- Effect-related ----

  final case class WithClause(row: Tree, span: Span) extends Tree

  /** `E + F + G` — a row of effects. */
  final case class EffectRow(effects: List[Tree], span: Span) extends Tree

  /** `E of A` — single-effect bound, optionally with type-arg. */
  final case class EffectBound(
      name: String,
      ofArg: Option[Tree],
      span: Span
  ) extends Tree

end Trees

// Convenience type aliases that mirror dotc's untpd / tpd split. In Phase 2
// every produced tree is "untyped"; the typer (Phase 3) will populate type
// slots through a side mechanism, leaving these aliases unchanged.
object untpd:
  type Tree = fixed.ast.Tree

object tpd:
  type Tree = fixed.ast.Tree
