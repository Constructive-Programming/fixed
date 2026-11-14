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
  /** A `use` declaration. `path` is the dotted prefix; if the import is
    * a brace-group like `use a.b.{X, Y}`, `selectors` lists the
    * grouped names — semantically equivalent to one UseDecl per name
    * sharing the prefix. Empty `selectors` means the path itself is the
    * single imported name. */
  final case class UseDecl(
      path: List[String],
      selectors: List[String],
      satisfies: Option[Tree],
      span: Span
  ) extends Tree

  /** A function-shaped declaration: the shape shared by free fns and
    * cap / effect / satisfies methods. The concrete type says where it sits.
    */
  sealed trait FnLike extends Tree:
    def sig: FnSig
    def name: String = sig.name
    def typeParamsHint: List[String] = sig.typeParamsHint
    def params: List[FnParam] = sig.params
    def returnType: Tree = sig.returnType
    def withClause: Option[Tree] = sig.withClause
    def body: Option[Tree] = sig.body

  /** The fields every `FnLike` shares. */
  final case class FnSig(
      name: String,
      typeParamsHint: List[String],
      params: List[FnParam],
      returnType: Tree,
      withClause: Option[Tree],
      body: Option[Tree]
  )

  /** Flat extractor over `FnSig`, so `FnLike` nodes match field by field:
    * `case FnDecl(name, hints, params, ret, w, body, span) =>`.
    */
  sealed abstract class FnLikeCompanion[T <: FnLike]:
    def unapply(t: T): (String, List[String], List[FnParam], Tree, Option[Tree], Option[Tree], Span) =
      (t.name, t.typeParamsHint, t.params, t.returnType, t.withClause, t.body, t.span)

  /** Top-level or block-local `fn`. */
  final case class FnDecl(sig: FnSig, span: Span) extends FnLike
  object FnDecl extends FnLikeCompanion[FnDecl]

  /** A `cap` declaration. `valueParams` is the optional `(min: N, max: N)`
    * sugar (`cap Between(min: N is Ord, max: N) of N: ...`) that
    * desugars to `fn between(min: N is Ord, max: N) -> cap of N: ...`.
    * `ofParams` is the `of (...)` clause (type-level indices). */
  final case class CapDecl(
      name: String,
      valueParams: List[FnParam],
      ofParams: List[Tree],
      extendsList: List[Tree],
      body: List[Tree],
      span: Span
  ) extends Tree

  /** A `data` declaration. Variants and props are stored separately —
    * the parser knows which is which at dispatch time, so the AST
    * reflects that statically rather than mixing them in one List[Tree]
    * that consumers have to re-partition. */
  final case class DataDecl(
      name: String,
      ofParams: List[Tree],
      variants: List[DataVariant],
      props: List[PropDecl],
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

  /** `name : Type (= default)?` — the shape shared by parameters and fields. */
  sealed trait Binder extends Tree:
    def binding: Binding
    def name: String = binding.name
    def typeAnn: Tree = binding.typeAnn
    def default: Option[Tree] = binding.default

  /** The fields every `Binder` shares. */
  final case class Binding(name: String, typeAnn: Tree, default: Option[Tree])

  /** Flat extractor over `Binding`: `case FnParam(name, typeAnn, default, span) =>`. */
  sealed abstract class BinderCompanion[T <: Binder]:
    def unapply(t: T): (String, Tree, Option[Tree], Span) = (t.name, t.typeAnn, t.default, t.span)

  /** A fn / lambda / `of (...)` value parameter. */
  final case class FnParam(binding: Binding, span: Span) extends Binder
  object FnParam extends BinderCompanion[FnParam]

  // ---- Cap members ----

  /** `fn` member of a cap, effect or satisfies block. */
  final case class InstanceMethod(sig: FnSig, span: Span) extends FnLike
  object InstanceMethod extends FnLikeCompanion[InstanceMethod]

  /** `Self.fn` member of a cap or satisfies block. */
  final case class StaticMethod(sig: FnSig, span: Span) extends FnLike
  object StaticMethod extends FnLikeCompanion[StaticMethod]

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

  /** A data-variant field. */
  final case class FieldDecl(binding: Binding, span: Span) extends Binder
  object FieldDecl extends BinderCompanion[FieldDecl]

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

  /** A `handle` expression. The optional `return(p) => …` arm is
    * stored separately from the effect-op arms — well-formed input
    * has at most one. */
  final case class HandleExpr(
      subject: Tree,
      arms: List[HandlerArm],
      returnArm: Option[ReturnArm],
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
      params: List[LambdaParam],
      body: Tree,
      span: Span
  ) extends Tree

  /** `name (: Type)? (= default)?` — a lambda parameter; `typeAnn` is `None`
    * when the type is left to inference.
    */
  final case class LambdaParam(name: String, typeAnn: Option[Tree], default: Option[Tree], span: Span) extends Tree

  /** `x.method(args)` (`args` = `Some(...)`), or `x.method` with no `(...)` (`args` = `None`) —
    * the grammar's zero-arg member form. The concrete type says what `x` is.
    */
  sealed trait MemberCall extends Tree:
    def selection: Selection
    def method: String = selection.method
    def args: Option[List[Tree]] = selection.args

  /** The fields every `MemberCall` shares: `.method(args)`. */
  final case class Selection(method: String, args: Option[List[Tree]])

  /** Flat extractor over `Selection`, given what the call is made on (`H`):
    * `case MethodCall(receiver, method, args, span) =>`.
    */
  sealed abstract class MemberCallCompanion[H, T <: MemberCall](target: T => H):
    def unapply(t: T): (H, String, Option[List[Tree]], Span) = (target(t), t.method, t.args, t.span)

  /** `recv.method(args)` / `recv.method` — a value receiver. */
  final case class MethodCall(receiver: Tree, selection: Selection, span: Span) extends MemberCall
  object MethodCall extends MemberCallCompanion[Tree, MethodCall](_.receiver)

  /** `T.member(args)` / `T.member` — the qualifier is a type name; the typer
    * decides between a static-method call and a variant construction.
    */
  final case class StaticCall(qualifier: String, selection: Selection, span: Span) extends MemberCall
  object StaticCall extends MemberCallCompanion[String, StaticCall](_.qualifier)

  /** Plain `f(args)` — a value-level function call. */
  final case class Apply(fn: Tree, args: List[Tree], span: Span) extends Tree

  /** Binary operators, by precedence level (lowest first). `symbol` is the source spelling. */
  enum BinOperator(val symbol: String):
    case Or extends BinOperator("||")
    case And extends BinOperator("&&")
    case Eq extends BinOperator("==")
    case Neq extends BinOperator("!=")
    case Lt extends BinOperator("<")
    case Le extends BinOperator("<=")
    case Gt extends BinOperator(">")
    case Ge extends BinOperator(">=")
    case Add extends BinOperator("+")
    case Sub extends BinOperator("-")
    case Mul extends BinOperator("*")
    case Div extends BinOperator("/")
    case Mod extends BinOperator("%")

  /** Prefix operators. `symbol` is the source spelling. */
  enum UnaryOperator(val symbol: String):
    case Neg extends UnaryOperator("-")
    case Not extends UnaryOperator("!")

  final case class BinOp(
      op: BinOperator,
      lhs: Tree,
      rhs: Tree,
      span: Span
  ) extends Tree

  final case class UnaryOp(
      op: UnaryOperator,
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
      fields: Option[List[Tree]],
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

  /** `Name of A` — a named reference, optionally with a type-arg. */
  sealed trait NamedRef extends Tree:
    def name: String
    def ofArg: Option[Tree]

  /** `C of A` — a capability reference. */
  final case class CapRef(name: String, ofArg: Option[Tree], span: Span) extends NamedRef

  /** `E of A` — one effect in an `EffectRow` / `WithClause`. */
  final case class EffectBound(name: String, ofArg: Option[Tree], span: Span) extends NamedRef

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

end Trees

// Convenience type aliases that mirror dotc's untpd / tpd split. In Phase 2
// every produced tree is "untyped"; the typer (Phase 3) will populate type
// slots through a side mechanism, leaving these aliases unchanged.
object untpd:
  type Tree = fixed.ast.Tree

object tpd:
  type Tree = fixed.ast.Tree
