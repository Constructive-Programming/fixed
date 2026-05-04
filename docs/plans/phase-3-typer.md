# Fixed Compiler — Phase 3: Type Checker (Implementation Plan)

**Status:** Draft v0.1 — pre-implementation planning
**Scope:** Scala 3 implementation of the typer pipeline. Untyped AST (Phase 2 output) → typed AST → typed AST refined by capability closure / effect inference / quantity check / representation selection.
**Prerequisite:** Phase 2 + 2.1 complete (parser produces a usable AST with diagnostics, gap nodes, and trivia for every `examples/*.fixed`, even on syntactically broken input).
**Reference:** `docs/references/scala3-compiler-reference.md` §4 (typer pipeline) and §5.4 (recheck pattern). Read both end-to-end before starting M1.

## 1. Scope and milestones

### 1.1 What's in Phase 3

Phase 3 is split into Part A (this plan) and Part B (separate sub-plans, sketched in §1.3). The split is summarised in the milestone table; this section names the components.

#### 1.1.A — Part A (this plan, M1–M5)

- **Phase scaffolding** — `core/{Names,Symbols,Phases,Contexts,Denotations,Types,ProtoTypes}.scala`, plus `Compiler`/`Run`/`Driver`/`Main`. Today the parser runs from a test harness; Phase 3 introduces the actual compiler driver.
- **Typed-tree model** — keep `ast/Trees.scala` unchanged; typed information lives in a `Map[Tree, Type]` side table that relies on the §3.0 span-as-identity invariant (each parser-allocated tree has a unique span, so structural-equality keys coincide with identity). The `untpd`/`tpd` aliases ship as documentation.
- **Namer** (`typer/Namer.scala`) — signature pass that creates one `Symbol` per top-level declaration and types its full **signature** (parameters, return type, with-clause, etc.) without touching bodies. Mutual signature references resolve via a two-sub-pass fixpoint. Enables mutual recursion across `fn`, `cap`, `data`, `effect`, `type`, `satisfies`. (No `LazyType` completers — see §4.2 for why we deviate from dotc here.)
- **Typer** (`typer/Typer.scala`, `typer/ProtoTypes.scala`, `typer/Inferencing.scala`) — bidirectional type-checker over typed bodies. `typedX(s: TyperState, tree, pt: Proto)(using Context): (TyperState, Tree)` per node kind; four dispatchers (expressions, patterns, type expressions, declarations) per §5.2.
- **Exhaustiveness check** (`typer/Exhaustiveness.scala`) — top-level variant coverage, in-tree inside `typedMatch` (M5). Conformance gaps documented in §5.5.1 and deferred to `phase-3.x-exhaustiveness.md`.

#### 1.1.B — Part B (separate sub-plans, sketched here for context)

- **Capability closure recheck** (`caps/CapClosure.scala`, `phase-3.1-capability-closure.md`) — close capability sets under `extends`, resolve `satisfies` mappings in scope.
- **Effect inference** (`effects/EffectChecker.scala`, `phase-3.2-effects.md`) — infer effect rows for every expression; verify that all effects are handled before they reach `main`.
- **Property verifier** (`caps/PropVerifier.scala`, `phase-3.3-properties.md`) — discharge static `prop` obligations via Princess (per §5.6.1); queue PBT obligations for the rest.
- **Quantity checker** (`qtt/QuantityChecker.scala`, `phase-3.4-qtt.md`) — infer 0/1/ω quantities; verify erasure and linearity.
- **Representation selector** (`repr/RepresentationSelector.scala`, `phase-3.5-repr.md`) — assign concrete C-level representations per `spec/type_system.md` §6.5.

### 1.2 What's NOT in Phase 3 (deferred)

- **Code generation** — Phase 4 (LLVM IR emission).
- **Match → fold desugaring** — Phase 4 transform tier.
- **Perceus dup/drop insertion** — Phase 4.
- **Standard library** (`library/src/main/fixed/`) — Phase 5. The Phase 3 typer must accept stdlib-shaped capability declarations (`Optional`, `List`, `Numeric`, …) but the actual library bodies don't ship until later.
- **Inline expansion** — not a Fixed language feature; if needed at all, driven by PGO at Phase 6.
- **TASTy-equivalent serialisation** — premature before multi-module compilation.
- **LSP integration** — Phase 2.1's `ParseResult` API is the seam; extending it to a typed-result API is left to a future phase once a concrete LSP consumer exists.

### 1.3 Milestones (ordered)

Phase 3 is split into two parts: **Part A — Base typer (M1–M5, this plan)** and **Part B — Layered analyses (M6–M11, sub-plans)**. Part A is the Phase 3 deliverable owned by this document; Part B is sketched here for context and gets a dedicated sub-plan per recheck once Part A ships and the M5 output is stable to design against. The split mirrors how Phase 2.1 was carved off Phase 2.

#### Part A: Base typer (this plan)

| # | Milestone | Verification |
|---|---|---|
| M1 | Phase scaffolding compiles. `Compiler` runs `ParserPhase` → stub `TyperPhase` end-to-end. `Driver`/`Main` accept a file path on argv. Tree-id field added to every `Trees.X` (per §3 option D). | `sbt run -- examples/01_basics.fixed` exits 0; stub typer emits one diagnostic noting "typer not yet implemented" and the parsed tree is reported. Parser tests still pass with the `id` field added. |
| M2 | `Names`/`Symbols`/`Denotations`/`Phases`/`Contexts`/`Types`/`ProtoTypes` machinery in place. `Namer` types every top-level decl's **signature** in `examples/01_basics.fixed`. `TyperBench` introduced and baselined. Perf checkpoint: corpus ≤ 2× the parser's per-token cost. | A test asserts the symbol table for `01_basics.fixed` matches an expected list of `(name, kind, signature-Type)` triples. `TyperBench` reports a corpus mean. |
| M3 | Type ADT + ProtoTypes + Typer skeleton + Unifier. Literals, identifiers, arithmetic, function calls, and `let` bindings type-check in isolation. | `typedExpr("1 + 2 * 3")` yields a tpd tree whose root is `BinOp("+")` with children typed `i64`. Idents in scope resolve to their symbols. |
| M4 | Decl body typing — `fn`, `cap`, `data`, `type`, `effect`, `satisfies` declaration bodies all type-check. Mutual recursion across decls works (signatures from M2's Namer pass cover it). | `examples/01_basics.fixed` and `02_collections.fixed` type-check end-to-end with no diagnostics; symbol table has correct types for every binding. |
| M5 | Expression typing — `if`, `match` (with in-tree top-level-variant exhaustiveness — see §5.5.1 for the conformance gaps deferred to Phase 3.x), `handle`, `do`, `forall`, `implies`, `lambda`, struct literals, method calls, static calls, `Resume`, `Unreachable`. Pattern typing — variant patterns, literal patterns, or-patterns, guards, struct-destructure (top-level only). Perf checkpoint: corpus mean within 2× of M2 baseline. | `examples/03_option_result.fixed` through `examples/06_functor_monad.fixed` type-check with no diagnostics. `TyperBench` reports a corpus mean. |

**Part A success criterion:** every example in `examples/01..06.fixed` type-checks, the symbol table has a complete signature view for every cap/data/fn, `TyperBench` is within 2× of the parser's per-token cost, AND the v1 conformance gaps (top-level-only exhaustiveness per §5.5.1; no Part B layered analyses) are documented in a `Phase3Conformance.md` artifact emitted by M5 — so a downstream consumer reading the typer output knows what is and isn't enforced. Shipping a known-incomplete typer is the right v1 trade; shipping it without surfacing what's incomplete is not.

#### Part B: Layered analyses (separate sub-plans)

| # | Milestone | Sub-plan | Sketch |
|---|---|---|---|
| M6 | Capability closure recheck | `phase-3.1-capability-closure.md` | `extends` chain computed; `satisfies` scope resolved at every use site; cross-module coherence per §7.4.1 |
| M7 | Effect inference recheck | `phase-3.2-effects.md` | With-clauses inferred; `do`/`handle` rows propagate; unhandled effect at `main` is `error[T015]` |
| M9 | Property verifier recheck | `phase-3.3-properties.md` | Princess (per §5.6.1) discharges QF_LIA + propositional; rest queued as PBT obligations |
| M10 | QTT recheck | `phase-3.4-qtt.md` | 0/1/ω inference; linearity + erasure check; runs **before** M9 to feed it |
| M11 | Representation selector recheck | `phase-3.5-repr.md` | `ReprMap` per `spec/type_system.md` §6.5; consumes M6 + M10 |
| M8' | Conformance gaps for exhaustiveness | `phase-3.x-exhaustiveness.md` | Nested-pattern + refinement-cap-aided + handler-arm coverage per `spec/pattern_matching.md` §6 |

**Phase 3 (overall) success criterion:** every example in `examples/01..11.fixed` type-checks AND has a complete capability/effect/quantity/representation picture ready for Phase 4 codegen.

#### Why the split now

Carving Part B into sub-plans up front (rather than at "the cut" later) buys three concrete things:

1. Each sub-plan can be authored against the **actual** M5 output, not an imagined one.
2. The base typer is independently shippable — Part A alone is enough for a pre-Perceus prototype that runs on the corpus.
3. Each Part B sub-plan gets its own document-review pass (the cross-cutting decisions in M9 / M10 / M11 are large enough to warrant individual scrutiny, not buried in a §5.x subsection).

## 2. Project structure

```
compiler/src/main/scala/fixed/
  Main.scala                        — entry point (currently absent)
  Driver.scala                      — argv parsing, settings (currently absent)
  Compiler.scala                    — phase plan: List[List[Phase]] (currently absent)
  Run.scala                         — one execution of the phase plan (currently absent)
  CompilationUnit.scala             — per-unit tree + diagnostics (currently absent)
  ast/
    Trees.scala                     — extend with typed-tree slot (see §3)
    untpd.scala                     — type alias for parser-output trees
    tpd.scala                       — type alias for typed trees
    Desugar.scala                   — (Phase 4) match → fold lowering, etc.
  core/
    Names.scala                     — interned identifiers (NEW)
    Symbols.scala                   — symbol table (NEW)
    Denotations.scala               — phase-indexed symbol info (NEW)
    Types.scala                     — Type ADT, prototype types (NEW)
    Phases.scala                    — Phase trait + base classes (NEW)
    Contexts.scala                  — Context object, `using Context` plumbing (NEW)
  parsing/
    ParserPhase.scala               — wraps Parser as a Phase (NEW; today Parser is bare)
  typer/
    TyperPhase.scala                — Namer + Typer subphases (NEW)
    Namer.scala                     — index pass (NEW)
    Typer.scala                     — bidirectional typer (NEW)
    ProtoTypes.scala                — prototype types (NEW)
    Inferencing.scala               — type variable instantiation (NEW)
    Exhaustiveness.scala            — match coverage (NEW; M8)
  caps/
    CapClosure.scala                — capability set closure recheck (NEW; M6)
    PropVerifier.scala              — prop discharge / obligation queueing (NEW; M9)
    SatisfiesResolver.scala         — `satisfies` lookup at use sites (NEW; M6 helper)
  effects/
    EffectChecker.scala             — effect inference recheck (NEW; M7)
  qtt/
    QuantityChecker.scala           — QTT inference recheck (NEW; M10)
  repr/
    RepresentationSelector.scala    — representation recheck (NEW; M11)
  util/
    SourceFile.scala                — already exists
    Reporter.scala                  — already exists; gains T-coded typer diagnostics
compiler/src/test/scala/fixed/
  core/
    SymbolsSuite.scala              — symbol table operations
    TypesSuite.scala                — type construction + subtyping
  typer/
    NamerSuite.scala                — index pass per example
    TyperSuite.scala                — per-feature type-checking tests
    ExampleTypeCheckSuite.scala     — types every examples/*.fixed end-to-end
  caps/
    CapClosureSuite.scala
    PropVerifierSuite.scala
  effects/
    EffectCheckerSuite.scala
  qtt/
    QuantityCheckerSuite.scala
  repr/
    RepresentationSelectorSuite.scala
```

### 2.1 No new build dependencies

Munit + Scala 3 standard library cover the typer the same way they covered the parser. No external `cats`/`zio`/`scalaz`. The FP discipline established for the Scanner and Parser (zero `var`, zero `while`, zero `mutable.*` in user code) extends to the typer — see §3.5.

## 3. Typed-tree model

This is the central design decision for Phase 3. dotc parameterises every tree by its type-slot type: `Tree[T <: Untyped]`, with `untpd.Tree = Tree[Untyped]` and `tpd.Tree = Tree[Type]`. Fixed's `ast/Trees.scala` today is a flat untyped trait of `final case class` nodes. We need a way to attach a `Type` to each node without breaking the FP discipline (no mutable field) or rewriting every parser/test site (no `Tree[T]` parameterisation).

Three shapes are viable; we evaluate each:

| # | Shape | Pros | Cons | Decision |
|---|---|---|---|---|
| A | Parameterise `Tree[T]` with a typed-slot field. | Mirrors dotc; type-state is a compile-time guarantee. | Every parser site, test, and pattern match becomes parameterised. ~1500 lines of churn. | Reject. |
| B | Side table `Map[Tree, Type]` keyed on case-class equality. | Zero churn to AST. Pure (no mutable field). | Relies on the invariant that no two parser-allocated trees compare equal — see "Span-as-identity invariant" below. | **Adopt.** |
| C | Mutable `tpe: Type \| Null` field, set once during typing. | What dotc does. Cheap lookup. | Violates the FP discipline (zero `var` / `mutable.*`); extending the case class is a breaking change for every pattern match. | Reject. |

### 3.0 Span-as-identity invariant (load-bearing)

Every `Trees.X` case class is a `final case class`, so Scala synthesises structural `equals`/`hashCode` over its fields — including `span: Span`. `Span = (start: Int, end: Int)` is unique per source range within a single `SourceFile`. The parser allocates **one tree per source range** — every node's `span` covers a distinct portion of the source. Therefore:

> Within a single parse pass, `(caseClass, fields-including-span)` jointly identifies every tree. Two distinct nodes never compare equal.

This makes `Map[Tree, Type]` work as identity-equivalent: two structurally-equal trees ARE the same tree (same source range, same shape — same node).

**Why this works in practice (per-node-kind audit):**

| Kind | Why distinct nodes have distinct spans |
|---|---|
| Literals (`IntLit`, `FloatLit`, `StringLit`, `CharLit`, `BoolLit`, `UnitLit`) | Each literal token has a unique offset; `span` differs. |
| `Ident`, `WildcardPat`, `BinderPat`, `PrimitiveType`, `TypeRef`, `CapRef`, `SelfType`, `PartType`, etc. | Each occurrence is a distinct token; `span` differs. |
| `BinOp`, `UnaryOp`, `Pipe`, `Apply`, `MethodCall`, `StaticCall` | `span` covers the operator and its operands — distinct call sites have distinct ranges. |
| `IfExpr`, `MatchExpr`, `LetExpr`, `LambdaExpr`, `Block`, `DoExpr`, `HandleExpr`, `ForallExpr`, `Implies` | Distinct keyword positions → distinct spans. |
| `TupleExpr`, `ListExpr`, `StructLit` | Distinct opening bracket positions. |
| `MatchArm`, `HandlerArm`, `ReturnArm`, `DoBind`, `FnParam`, `FieldDecl`, `DataVariant`, etc. | Each arm/binding/field/variant is at a distinct position within its parent. |
| Top-level decls (`FnDecl`, `CapDecl`, `DataDecl`, `EffectDecl`, `TypeAlias`, `SatisfiesDecl`, `UseDecl`, `ModDecl`) | Distinct decl positions. |
| Recovery nodes (`Trees.Error`, `Trees.Missing`) | The parser advances at least one token per recovery emission (per `syncToAnchors` semantics), so two recovery nodes never share a position. The `Missing.expected` string differs between distinct missing-production sites; `Error.recovered` differs by content. |

The audit holds for the parser as it stands at HEAD (`compiler/src/main/scala/fixed/parsing/Parsers.scala`). The single exception worth flagging: if a future change makes the parser emit two recovery nodes at the same offset with empty `recovered` and the same `expected` string, they would collide. The fix at that point is local — give one of them a sentinel offset, or audit to confirm the pattern doesn't actually occur.

### 3.1 The TypeMap

```scala
package fixed.ast

object Typed:
  /** Type information for a typed AST, keyed by tree case-class equality.
    * The §3.0 span-as-identity invariant guarantees no parser-allocated
    * trees ever collide as map keys, so structural-equality `Map` keys
    * coincide with identity. */
  opaque type TypeMap = Map[Tree, Type]

  object TypeMap:
    val empty: TypeMap = Map.empty
    extension (m: TypeMap)
      def get(t: Tree): Option[Type]            = (m: Map[Tree, Type]).get(t)
      def updated(t: Tree, ty: Type): TypeMap   = (m: Map[Tree, Type]).updated(t, ty)
```

The typer's central `typed` returns `(TyperState, Tree)` per §3.4, with the `TypeMap` carried inside `TyperState`.

**Constraint on Recheck implementations (load-bearing)**. A Recheck phase that synthesises a *new* tree node (not present in the parser output) MUST give it a span that doesn't collide with any existing parser-allocated node. The simplest discipline: synthesised nodes use a sentinel `Span(-1, -1)` plus a position-encoding embedded in `caseClass + fields` so they remain mutually distinct. Phase 3 / Part A does no such synthesis — the typer rewrites types, not trees. Phase 4 desugaring (`match → fold`, etc.) will be where this constraint first bites; the sub-plan author handles it then. If the constraint becomes load-bearing earlier, the §3.5 escape-hatch table absorbs the pattern.

**Storage shape across phases**. Each Recheck phase produces a new `TypeMap`. We do **not** retain the prior phase's map by default — the next phase reads what it needs from its input map and writes its own refinements into a fresh map. Working set is O(tree-count × 2) at any moment, not O(tree-count × phase-count). Should a downstream phase need historical views, promote `TypeMap` to `Map[PhaseId, TypeMap]` then.

**Why not IdentityHashMap?** `java.util.IdentityHashMap` would also work and would not depend on the §3.0 invariant — but it is mutable, which would be the only mutable structure in the typer's hot path and would force a §3.5 escape-hatch entry. The structural-equality `Map` is pure and sufficient under the audit above. If TyperBench (M2 perf checkpoint) shows hashing case classes is the bottleneck, the §3.5 table absorbs an `IdentityHashMap`-based replacement behind the same `TypeMap` interface.

### 3.2 Symbol and Denotation

```scala
package fixed.core

/** Globally unique identity for a definition. No semantic content. */
opaque type SymbolId = Int

object SymbolId:
  def fresh(): SymbolId = ???  // monotone counter, threaded through Context

/** Per-phase view of a symbol's content. Keyed by SymbolId × PhaseId. */
final case class Denotation(
    id: SymbolId,
    name: Names.Name,
    owner: SymbolId,           // enclosing decl, or RootSymbol for top-level
    flags: Long,               // is_method | is_static | is_data_variant | …
    info: Type,                // base type after typer; refined by Recheck phases
    span: Span                 // declaration source span
)
```

A `SymbolTable` (in `core/Symbols.scala`) is `IntMap[Denotation]` — one denotation per symbol, the current view. Recheck phases that refine a symbol's `info` produce a new `SymbolTable` with the entry replaced. We do **not** keep the per-phase chain of denotations by default — no concrete consumer in Phase 3 needs to consult an earlier-phase view of a symbol. (Phase 4 codegen reads the final post-recheck state.)

If a future recheck phase or codegen does need the historical view (the "what did this symbol look like at phase N" property dotc's `docs/_docs/contributing/architecture/time.md` describes), promote `SymbolTable` to `IntMap[Vector[Denotation]]` keyed by `(SymbolId, PhaseId)`. The migration is local — every read/write goes through the `SymbolTable` API, not the underlying map. Adopting now would be generality without need.

### 3.3 Type ADT

The Fixed type system is smaller than Scala's, but not as small as the v0.1 draft of this plan claimed. The set below is the result of a use-site trace against `spec/type_system.md` §5 + §6, `spec/effects.md`, `spec/properties.md`, and the v0.4.8 grammar productions; each case is justified by at least one feature it must represent (column "Used by").

```scala
package fixed.core

enum Type:
  // Primitives
  case PrimType(name: String)                        // i64, u64, f64, bool, String, char
  case UnitType                                      // ()
  case NeverType                                     // !

  // Constructed
  case ArrowType(lhs: Type, rhs: Type, fx: EffectRow)
  case TupleType(elems: List[Type])
  case ListType(elem: Type)

  // Capability bounds (anonymous)
  case IsBound(caps: List[CapBound])                 // is C + D + ...
  case CapType(retType: Option[Type])                // cap of T  (return-type position;
                                                     //   spec Rule 6.3.0)

  // References (resolved by Namer)
  case TypeRef(sym: SymbolId, args: List[Type])      // resolved type-name reference,
                                                     //   incl. NamedAlias (`N is C` becomes
                                                     //   TypeRef(N) + scope binding to IsBound)
  case CapRef(sym: SymbolId, args: List[Type])       // resolved cap-name reference
  case SelfType(of: Option[Type])                    // Self / Self of B (resolved against
                                                     //   ctx.selfType in the cap context)
  case PartType                                      // Part — kept separate from SelfType
                                                     //   because it has its own scope rule
                                                     //   (cap's element-type pseudo-param)

  // Refinement-cap calls (spec Rule 6.3.1)
  case RefinementType(generator: SymbolId,
                      valueArgs: List[SymbolicTerm]) // e.g. between(0, 10) — value-args carried
                                                     //   as symbolic terms so M9 prop verifier
                                                     //   can key obligations on call site
  case CapGeneratorType(params: List[Type],          // (N, N) -> cap of N — higher-order parameter
                        result: CapBound)            //   types per spec §6.3.0

  // Inference (M3-M5 only)
  case TypeVar(id: Int)                              // unification variable

final case class CapBound(sym: SymbolId,
                          args: List[Type],
                          props: List[PropObligation])

final case class EffectRow(effects: List[EffectBound],
                            rest: Option[TypeVar])    // open row: effects + ?ε

enum SymbolicTerm:                                    // for RefinementType.valueArgs
  case LitInt(value: BigInt)
  case LitBool(value: Boolean)
  case LitString(value: String)
  case BoundVar(sym: SymbolId)                        // a binding in scope at the call site
```

#### Use-site validation

Each `Type` case is justified by at least one source obligation, chosen so a future reviewer can trace why each exists:

| Case | Used by |
|---|---|
| `PrimType` | numeric/string/bool/char primitives (spec §5.0) |
| `UnitType` | `()` literal and unit type (spec §5.5) |
| `NeverType` | `!` (spec §5.6) |
| `ArrowType` | every fn signature; lambda body type |
| `TupleType` | `(a, b)` expressions; tuple patterns |
| `ListType` | `[a, b]` literals; list pattern |
| `IsBound` | parameter type `n: is Numeric` (anonymous) |
| `CapType` | `fn f -> cap of T` return type (spec Rule 6.3.0) |
| `TypeRef` | every resolved data / type-alias name; subsumes `NamedAlias` once Namer binds the name |
| `CapRef` | resolved cap name in a bound chain; `Functor`, `Optional`, etc. |
| `SelfType` | `Self`, `Self of B` inside cap and satisfies bodies (spec §5.4) |
| `PartType` | `Part` inside cap bodies; auto-derived `fold` element type (spec §5.3) |
| `RefinementType` | `is Between(0, 10)`, `cap of N` returning `Bounded(min, max)` (spec Rule 6.3.1) |
| `CapGeneratorType` | `(N, N) -> cap of N` parameter types (spec Rule 6.3.0) |
| `TypeVar` | unification placeholder during M3-M5; eliminated post-M5 |

`NamedAlias` from a previous draft is intentionally **dropped** — once the Namer binds `N` into the local scope, the rest of the signature uses `TypeRef(N)` and looks the name up. No separate enum case needed.

`ProtoType` from a previous draft is also dropped — prototypes are inference state, not types. They live in their own ADT in `core/ProtoTypes.scala` (see §3.4).

#### Helpers, not types

`CapBound` and `EffectRow` are **not** members of the `Type` enum — they are standalone helper records carried *inside* `Type` cases (`IsBound.caps: List[CapBound]`, `ArrowType.fx: EffectRow`). The base typer (M3–M5) treats `EffectRow` as an opaque slot; the effect-inference recheck (M7) is what actually populates and reasons about effect rows. `SymbolicTerm` is the symbolic-arg ADT consumed by `RefinementType.valueArgs` and by M9's prop verifier.

#### Growth governance

The case set above is the **v1 closed set**. New cases require:
1. An entry in §10 (Open questions) documenting the language-feature driver.
2. An update to every Recheck phase's per-node logic (M6 closure, M7 effects, M8 exhaustiveness, M9 props, M10 QTT, M11 repr).

Promote to a separate ADR document only when a second cross-cutting decision warrants the discipline.

`Type` is `enum` (Scala 3 ADT) — exhaustively pattern-matchable. Recheck phases extend with refinements via separate side tables; `Type` itself stays at this size.

### 3.4 Prototypes (bidirectional typing)

Per dotc `typer/ProtoTypes.scala`, every recursive call to the typer carries an expected type ("prototype"). Fixed adopts the same idea, with one canonical signature for every typer routine:

```scala
// In core/ProtoTypes.scala — Proto is its own ADT, NOT a Type case.
enum Proto:
  case ExpectedType(t: Type)            // "I expect this concrete type"
  case Wildcard                         // "no expectation; infer freely"
  case Fun(args: List[Proto], result: Proto)        // "I am being applied"
  case Selection(name: Names.Name, result: Proto)   // "I expect a member named X"
  case IsBound(caps: List[CapBound])                // Fixed-specific:
                                                    //   "passed to a param whose type is `is C + D`"

// In typer/Typer.scala — the canonical typer entry signature.
def typed(state: TyperState, tree: Tree, pt: Proto)
         (using ctx: Context): (TyperState, Tree)
```

Where `TyperState` packages the threaded state in one record (so callers don't manage three return values):

```scala
final case class TyperState(
    types: TypeMap,                   // Map[Tree, Type] per §3.1
    symbols: SymbolTable              // SymbolId ↦ Denotation
)
```

Every `typedX` is `(TyperState, Trees.X, Proto) => (TyperState, Tree)`. `Context` (§4.5) carries phase-static information (current owner, lexical scope, current cap's Self, reporter handle) and is threaded with `using`.

Why prototypes matter for Fixed (per the reference doc §4.3):

- `is` bounds at parameter sites *are* prototypes. `let x = some_expr` flowing into `n: N is Numeric` types `some_expr` with `pt = Proto.IsBound([CapBound(Numeric, Nil, Nil)])`.
- `fn -> cap` arguments use prototypes that carry the call-site value as a `SymbolicTerm` (see `RefinementType` in §3.3), so the cap body's `prop in_range: min <= Self && Self <= max` can be checked against the actual call.
- `satisfies` resolution at constructor-call sites (`Optional.some(x)` → `Maybe.Just(x)`) is a prototype-driven search: given `pt = ExpectedType(TypeRef(Optional, [A]))`, find an in-scope satisfaction whose ctor arity matches.

The three-tuple-return design of an earlier draft (`(tpdTree, refinedTypeMap, refinedSymbolTable)`) is replaced by the single-`TyperState` shape above. One canonical signature; every typer routine matches it.

### 3.5 FP discipline carried over (with documented escape hatches)

The Scanner and Parser are hard-FP (zero `var`, zero `while`, zero `mutable.*`). The Typer **extends the same discipline by default** — but with explicit escape hatches in places where pure-FP is known to cost too much against the inference workload.

**Default discipline** — applies everywhere unless an escape hatch below is invoked:

- `Context` is an immutable case class threaded via `using Context`. Updates are `ctx.copy(...)` or pure helpers like `ctx.withOwner(sym)` / `ctx.withScope(scope)`.
- `SymbolTable` is `IntMap[Denotation]` — adding/replacing a denotation returns a new table.
- `TypeMap` is the immutable `IntMap[Type]` from §3.
- Every typer routine returns `(TyperState, Tree)` per §3.4. State threading is explicit; nothing escapes via `var`.
- `inline def` cursor primitives, `IntMap` over `Map[K, _]` where K is `Int`-shaped, JFR-tuned hot paths — all the moves used in the Parser apply.

**Documented escape hatches** — pre-approved zones where mutability lives behind a pure interface. Each is justified by a workload mismatch with persistent FP:

| Component | Mutability | Why |
|---|---|---|
| `Reporter.add(d)` | `private val buf: ListBuffer[Diagnostic]` | Already in use; diagnostics are an output stream, not a value to fold over. (Inherited unchanged from Phase 2.) |
| `Unifier` (M3) | `mutable.LongMap[Type]` for the `TypeVar.id ↦ resolved Type` substitution | Persistent union-find is 5–20× slower than mutable in published benchmarks. The substitution map is local to one typed-call subtree and is forgotten when the call returns; no observer outside the substitution sees the mutation. The exposed surface is a pure `def unify(a: Type, b: Type): Either[UnifyError, Type]`. |

**Removal criterion**: each entry must remain justifiable under measurement. The Unifier entry is removed iff TyperBench corpus mean (release, JFR-tuned) at M5 shows substitution-map operations (`unifier.bind`, `unifier.find`) account for **<2 % of typer wall time** AND a persistent `IntMap[Type]` alternative submitted in the same PR shows **<10 % regression** on TyperBench. Without those two measurements meeting both thresholds, the entry stays. The bar for *adding* a hatch and for *keeping* one is the same; the table is small on purpose.

**Committed-but-deferred entry**: `PropSolver` (M9, Part B) — Princess (per §5.6.1) has its own internal mutability. We don't write the solver, we use it; the `trait ObligationSolver` adapter exposes a pure interface. The entry will be formalised in `phase-3.3-properties.md` when that sub-plan is authored. M9 is a committed Part B milestone; this is a scheduled future entry, not a contingent one.

**Future-Phase entry to watch**: Tree-node synthesis in Phase 4 desugaring — when `match → fold` lowering creates new tree nodes, those nodes need spans that don't collide with parser-allocated ones (per §3.1's load-bearing constraint). The simplest discipline is sentinel-span synthesis; if it bites, a Phase 4 ADR adds a counter-based variant.

**Perf-recovery milestones** are budgeted at M2 (post-Namer) and M5 (post-base typer) — the two points where the FP-vs-perf cost is most likely to bite in Part A. Each runs `TyperBench` (a typer-level analogue of `ParserBench`, introduced in M2) and applies JFR-driven tuning until corpus mean is within 2× of the equivalent dotc throughput per typed AST node. Part B sub-plans (M9 prop verifier, M11 representation selector) carry their own perf-recovery checkpoints in their respective sub-plan documents.

## 4. Phase pipeline

### 4.1 Phase plan

```scala
// Compiler.scala
class Compiler:
  def phases: List[List[Phase]] = List(
    List(ParserPhase),                // Phase 2
    List(NamerPhase),                 // M2
    List(TyperPhase),                 // M3-M5  (Exhaustiveness check runs in-tree
                                      //         inside typedMatch, not as a phase)
    List(CapClosurePhase),            // M6 (Recheck)
    List(EffectCheckerPhase),         // M7 (Recheck)
    List(QuantityCheckerPhase),       // M10 → executed BEFORE M9 to feed prop verifier
    List(PropVerifierPhase),          // M9
    List(RepresentationSelectorPhase) // M11 (Recheck; consumes M10 quantities + M6 cap sets)
  )
```

The outer `List[List[Phase]]` is dotc's MegaPhase grouping — phases in the same inner list are fused into one tree walk. We start each phase as a separate group and fuse later only if profiling justifies it. We end up with **6 typer-side phase classes**, not 8 — `ExhaustivenessPhase` was removed because §5.5 places exhaustiveness inside `typedMatch`. (M8 remains as a *milestone* gating the diagnostic quality of that check, but it is not its own `Phase`.)

#### 4.1.1 Recheck dependency graph

The milestone numbering (M6–M11) is the **implementation order** — but execution order follows the dependency graph below. Each edge `A → B` means "phase B reads phase A's refined types/symbols":

```
              CapClosure (M6)
                 │
                 ├──────────────┬──────────────┐
                 ▼              ▼              │
          EffectChecker (M7)   QuantityChecker (M10)
                 │              │              │
                 │              ├──────────────┤
                 │              ▼              ▼
                 │         PropVerifier (M9) ◄─┘  (M6 directly feeds M9
                 │              │                   for cap-classification)
                 │              │
                 └──────────────┴────────────────┐
                                                 ▼
                                  RepresentationSelector (M11)
```

| Edge | Why the dependency exists |
|---|---|
| M6 → M7 | Effect inference needs the closed cap set to type `with` clauses against effect caps. |
| M6 → M10 | QTT needs the **closed extends chain** to count uses through transitively-inherited cap methods (a single `m()` call may invoke a method declared in any super-cap; without closure M10 would miscount linearity). Cap-vs-value classification alone is a Namer fact, NOT what M6 contributes here. |
| M6 → M11 | Representation selection consumes the closed cap set per `spec/type_system.md` §6.5. |
| M6 → M9 | Prop verifier needs the closed cap set to translate `Self` in prop bodies into a solver domain (Sum cap → finite enumeration; Refinement cap → numeric range; Recursive cap → inductively-defined). Without M6's classification (per `spec/type_system.md` §6.4), `prop nonneg: Self >= 0` cannot be cast as a Princess formula. |
| M7 → M11 | Effect-row info determines whether a fn body needs a continuation slot (handler-arm reentry). |
| M10 → M9 (PBT branch only) | M9 has two **execution branches** (see §5.6): (a) **static-discharge** — Princess attempts compile-time discharge; the three prop *forms* §5.6 lists (Static-decidable / Compile-time-undecidable / Trivially-false) all enter through this branch and are routed by Princess's reply (`Unsat` / `Unknown` / `Sat`); (b) **PBT-queue** — `Unknown` results are deferred to codegen-time property-based testing. The PBT branch should skip 0-quantity bindings (no runtime PBT for erased terms). The **static-discharge branch runs unconditionally** — a 0-quantity binding can carry a soundness-relevant compile-time prop (e.g. `prop nonneg: phantom-N >= 0`) whose discharge guarantees a type-level invariant even though no runtime value exists. |
| M10 → M11 | Representation selection elides 0-quantity bindings entirely. |

The dependency graph is a DAG. The execution order in the `phases` list above (M6, M7, M10, M9, M11) is one valid topological sort.

**Why the M10 → M9 edge is one-way**: M9's static-discharge sub-pass needs *no* M10 input — but it does need M6's classification (per the M6 → M9 edge above). If sub-plan authoring shows the PBT-skip optimisation is small (most props turn out to be statically discharged anyway), M9 can run in parallel with M10 — both consuming M6 + M7 output, neither blocking the other. Authoring `phase-3.3-properties.md` should measure this on `examples/11_properties.fixed` before locking the order.

M8 (exhaustiveness) sits inside M5; M7 may run in parallel with M10 if profiling justifies it, but the default is sequential.

#### 4.1.2 Falsification: when is "six rechecks" wrong?

Six layered Recheck phases is a known-thin pattern — dotc has exactly one Recheck-style phase in production (CheckCaptures), still officially experimental. Stress test: if any of the following surfaces during Phase 3 implementation, we collapse:

- **A recheck phase needs to mutate a symbol's pre-recheck `info` to make a downstream recheck work** → fuse the two phases (the dependency wasn't independent after all).
- **Two consecutive rechecks each touch every node in the typed tree without sharing intermediate state** → fuse into a MegaPhase group (one walk, two transforms).
- **Diagnostic quality requires a recheck to know what an upcoming recheck would say** → re-order or fuse.
- **The Env-state composition across rechecks becomes more code than the rechecks themselves** → consider a single combined post-typer pass with phase-static dispatch.

Exit ramp: at any point M6–M11, fold M10/M11 into a single "quantities + repr" pass, and/or fold M9 into M11 (props at codegen time). The core typer (M1–M5) is not affected. Documenting this exit ramp now means a mid-phase pivot does not require a plan rewrite.

### 4.2 Two-pass typer (no LazyType)

`TyperPhase` runs Namer (signature pass) then Typer (body pass), per dotc `typer/TyperPhase.scala`. dotc uses `LazyType` completers in the Namer's denotations — thunks that run full body typing when forced — because dotc's mutable `info: Type` field can be re-assigned in place. Under our FP discipline (§3.5) that pattern would force every typer routine to thread an updated `SymbolTable` outward, which is exactly the closure-allocation hot spot the FP-discipline cost analysis warns about. We choose a different split.

#### 4.2.1 Pass structure

1. **Indexing (Namer)** — the **signature pass**. Walks every top-level decl and types its **signature** completely: parameter types, return type, with-clause, cap of-params, cap return-spec, data variant field types, type-alias RHS chain, satisfies cap-bound. **Bodies are not touched.** Output: a complete `SymbolTable` where every `Denotation.info` is a real `Type` (no `LazyType`).

2. **Typechecking (Typer)** — the **body pass**. Walks every body. Body typing uses signatures from the symbol table — never another body — so order is irrelevant and pure threading of `(TyperState, _)` is sufficient. Mutual recursion across `cap`/`data`/`fn` works for free because the Namer already populated all signatures.

The cost is that signatures must be self-contained: a fn signature can't depend on inferring the type of another fn's body. This matches the language design (Fixed has explicit return-type annotations on every fn that isn't `let`-bound) and is therefore not a constraint.

#### 4.2.2 Inputs to the signature pass — what counts as "signature-equivalent"

A v0.2 draft of this section claimed "signatures only reference other signatures, never bodies; the second pass converges in one round." That claim was wrong. The Fixed language has three signature-pass inputs that are not pure decl signatures:

1. **Module-scope `let` initialisers** — the v0.4.8 grammar admits a top-level `let upper = 150` whose value can appear in a refinement-cap call inside another decl's signature: `fn validate_age(age: u8 is between(0, upper)) -> u8 = age`. Typing the `validate_age` signature requires knowing `upper`'s type AND folding its initialiser to a literal `SymbolicTerm`.

2. **Refinement-cap value-args** — when a signature mentions `is Between(0, 10)`, the value-args `0` / `10` are folded to `SymbolicTerm.LitInt`. When they're `is Between(0, upper)` where `upper` is a module-let, both the let initialiser and the resulting symbolic term must be ready before the signature is complete.

3. **Mutually-recursive type aliases** — `type T1 = T2 of A; type T2 = T1 of B` cannot be resolved in one pass; `type T = … T …` is the simplest case.

The Namer's "signature pass" therefore operates on the **signature-input universe**: top-level decl signatures *plus* admissible (literal-folded) module-scope `let` initialisers. Both kinds become entries in the same dependency graph.

#### 4.2.3 Algorithm: SCC-based signature resolution

```
namerPass(decls: List[Tree], reporter: Reporter): SymbolTable =
  // (1) Enter every top-level name with `SignaturePending` (placeholder Type).
  //     This includes fn / cap / data / type-alias / effect / satisfies / mod
  //     decls AND admissible module-scope `let` bindings.
  let pending = decls.foldLeft(SymbolTable.empty)(enterPending)

  // (2) Build the signature dependency graph. For each pending symbol,
  //     traverse its signature syntax (recursively, NOT through bodies) and
  //     record every *other* pending symbol it mentions. The graph nodes
  //     are SymbolIds; edges are "needs the resolved Type of".
  let graph = pending.symbols.map(s => s -> deps(s)).toMap

  // (3) Tarjan SCC pass over the graph. Each SCC is either a singleton
  //     (resolved in one step) or a true cycle (a recursive type alias,
  //     mutually-recursive lets, etc.).
  let sccs = tarjanScc(graph)  // returns List[Set[SymbolId]] in topo order

  // (4) Process SCCs in topological order:
  sccs.foldLeft(pending) { (table, scc) =>
    if scc.size == 1 then
      val sym = scc.head
      typeSignatureOf(sym, table)  // produces a refined Denotation
    else
      // True cycle. Recursive type aliases are admissible iff every back-edge
      // in the SCC is interposed by a nominal data/cap constructor (i.e. the
      // back-edge appears strictly under at least one TypeRef whose generator
      // is a `data` or `cap` symbol — `type T = is C of (T,)` is admissible
      // because `C` interposes; `type T = T of A` is not). The admissibility
      // predicate is the same shape spec/type_system.md §7.5 uses for `data`
      // mutual recursion; the spec rule for `type` aliases is being authored
      // alongside this milestone (tracked in §10).
      //
      // For an admissible SCC: every member's signature already uses
      // TypeRef(otherSym) by SymbolId, so no fixpoint is required — the
      // SymbolIds were assigned at enterPending and the references resolve
      // through the table. We simply mark each member resolved.
      if scc.allBackEdgesAreNominallyInterposed then
        scc.foldLeft(table)(_.markResolved(_))
      else
        scc.foreach(s => reporter.error("T050", s.span,
          s"cyclic signature: ${scc.map(_.name).mkString(\" -> \")}"))
        scc.foldLeft(table)(_.markErrored(_))   // mark each member errored;
                                                // body pass treats them as Type.NeverType
  }
```

Termination: Tarjan SCC is O(V + E) where V = signature symbol count, E = total inter-signature references. One pass; no fixpoint iteration.

Cyclic-signature reporting (`error[T050]`) lists the full cycle in source order, so a user gets `cyclic signature: T1 -> T2 -> T1` rather than an "iteration limit reached" message. Genuine recursive type aliases (admitted by `spec/type_system.md`) are detected as SCCs but not reported as errors — they are typed with placeholder TypeRefs that close on themselves.

#### 4.2.4 Why not just iterate-to-fixpoint?

A naive worklist (`while changed: re-type each pending; check for change`) is O(N²) on cyclic-looking-but-actually-acyclic inputs and produces no helpful diagnostic on real cycles. SCC is one walk plus an explicit cycle topology, which both runs faster and gives users the cycle path.

### 4.3 Recheck phases

M6, M7, M9, M10, M11 are Recheck phases (per dotc `transform/Recheck.scala`); M8 is in-tree (§5.5). Each Recheck phase:

1. Walks the typed tree it received as input.
2. Calls per-node-kind `recheckX` methods with an `Env` carrying phase-specific state (capability set under construction, current quantity context, current representation choices). The `cc/CheckCaptures.scala:43-82` `Env` pattern is the canonical example.
3. Produces a fresh `TypeMap` containing the refined types it computed. The input `TypeMap` is dropped after the recheck completes — only the latest map is retained in memory at any time.
4. Optionally produces a fresh `SymbolTable` if the recheck refined any symbol's `info` (e.g. M6 closes the cap set, M10 attaches the QTT quantity).

The single-current-map model (point 3 + point 4 above) is the same generality-without-need decision as for Symbols: dotc keeps every prior phase's view because erasure / explicit-outer / etc. genuinely consult them. Fixed has no such consumer in Phase 3 or planned Phase 4. If that changes, promote `TypeMap` to `Map[PhaseId, IntMap[Type]]` keyed by phase.

**Recovery semantics in rechecks.** Every recheck phase MUST handle `Trees.Error` and `Trees.Missing` subtrees by treating them as opaque holes:

| Phase | `Error` / `Missing` behaviour |
|---|---|
| M6 CapClosure | Empty closed cap set; do not emit cap-coherence diagnostics for the subtree. |
| M7 EffectChecker | Empty effect row; do not emit unhandled-effect diagnostics. |
| M9 PropVerifier | No prop obligations contributed; no PBT obligations queued. |
| M10 QuantityChecker | Quantity ω (unrestricted); no linearity / erasure diagnostics. |
| M11 RepresentationSelector | `Repr.Unknown`; codegen will skip it. |

The parser already reported the underlying error; a Recheck phase emitting cascading diagnostics from the same hole would just be noise.

## 5. Per-component design

### 5.1 Namer (M2)

| Method | Inputs | Output |
|---|---|---|
| `indexCompilationUnit(cu, ctx)` | parsed `Trees.CompilationUnit`, `Context` | (`SymbolTable`, `Context`) |
| `enterFnDecl(fn, ctx)` | `Trees.FnDecl`, `Context` | new `Symbol` whose `info: Type` is the fully typed signature (param types + return type + with-clause). Body typing is the Typer's job (M4–M5), not the Namer's. |
| `enterCapDecl(cap, ctx)` | `Trees.CapDecl`, `Context` | new `Symbol`; method members entered as nested decls owned by the cap symbol |
| `enterDataDecl(data, ctx)` | `Trees.DataDecl`, `Context` | new `Symbol` for the type; one symbol per variant |
| `enterEffectDecl(effect, ctx)` | `Trees.EffectDecl`, `Context` | new `Symbol`; one per effect op |
| `enterTypeAlias(ta, ctx)` | `Trees.TypeAlias`, `Context` | new `Symbol` referencing the RHS bound chain |
| `enterSatisfiesDecl(s, ctx)` | `Trees.SatisfiesDecl`, `Context` | satisfaction record entered into a `SatisfiesScope` (looked up at use sites in M6) |
| `enterUseDecl(u, ctx)` | `Trees.UseDecl`, `Context` | binds path / selectors / `satisfies` import into the local scope |
| `enterModDecl(m, ctx)` | `Trees.ModDecl`, `Context` | nested `Context` for the module's contents |

The Namer does **not** look at expression bodies. It does look at signatures — fn parameter types, cap return-spec, data-variant fields — typing each signature fully so that downstream body typing can resolve cross-references without re-entering the Namer. See §4.2 for the convergence story.

### 5.2 Typer (M3–M5)

Per dotc, one `typedX` per node kind. Four dispatchers — one each for expressions, patterns, type expressions, declarations — keep each routine focused on its category. Trees that are arms or sub-bindings (MatchArm, HandlerArm, ReturnArm, DoBind, FnParam, FieldDecl, DataVariant, ConstructorMapping, ImpossibleMapping, InstanceMethod, StaticMethod, PropDecl) are handled directly by the parent's `typedX` rather than as top-level dispatch — they have no meaning outside their parent.

```scala
// typer/Typer.scala — expression dispatcher
def typedExpr(s: TyperState, tree: Tree, pt: Proto)
             (using Context): (TyperState, Tree) = tree match
  // Literals
  case t: Trees.IntLit         => typedIntLit(s, t, pt)
  case t: Trees.FloatLit       => typedFloatLit(s, t, pt)
  case t: Trees.StringLit      => typedStringLit(s, t, pt)
  case t: Trees.CharLit        => typedCharLit(s, t, pt)
  case t: Trees.BoolLit        => typedBoolLit(s, t, pt)
  case t: Trees.UnitLit        => typedUnitLit(s, t, pt)
  // References and applications
  case t: Trees.Ident          => typedIdent(s, t, pt)
  case t: Trees.Apply          => typedApply(s, t, pt)
  case t: Trees.MethodCall     => typedMethodCall(s, t, pt)
  case t: Trees.StaticCall     => typedStaticCall(s, t, pt)        // see §7.x call resolution
  // Bindings and control
  case t: Trees.LetExpr        => typedLet(s, t, pt)
  case t: Trees.IfExpr         => typedIf(s, t, pt)
  case t: Trees.MatchExpr      => typedMatch(s, t, pt)             // includes M8 exhaustiveness
  case t: Trees.HandleExpr     => typedHandle(s, t, pt)
  case t: Trees.DoExpr         => typedDo(s, t, pt)
  case t: Trees.LambdaExpr     => typedLambda(s, t, pt)
  case t: Trees.Block          => typedBlock(s, t, pt)
  // Operators
  case t: Trees.BinOp          => typedBinOp(s, t, pt)
  case t: Trees.UnaryOp        => typedUnaryOp(s, t, pt)
  case t: Trees.Pipe           => typedPipe(s, t, pt)              // see §7.x call resolution
  // Aggregates
  case t: Trees.TupleExpr      => typedTuple(s, t, pt)
  case t: Trees.ListExpr       => typedList(s, t, pt)
  case t: Trees.StructLit      => typedStructLit(s, t, pt)
  // Specials
  case t: Trees.ForallExpr     => typedForall(s, t, pt)            // prop bodies only
  case t: Trees.Implies        => typedImplies(s, t, pt)           // prop bodies only
  case t: Trees.Resume         => typedResume(s, t, pt)            // handler arms only
  case t: Trees.Unreachable    => typedUnreachable(s, t, pt)
  // Recovery
  case t: Trees.Error          => typedError(s, t, pt)             // pass-through (gap node)
  case t: Trees.Missing        => typedMissing(s, t, pt)
  case other                   => failExpr(s, other, pt)
```

```scala
// typer/Typer.scala — pattern dispatcher (called from typedMatch / typedHandle)
def typedPattern(s: TyperState, pat: Tree, scrutineeTy: Type)
                (using Context): (TyperState, Tree) = pat match
  case t: Trees.WildcardPat         => typedWildcardPat(s, t, scrutineeTy)
  case t: Trees.BinderPat           => typedBinderPat(s, t, scrutineeTy)
  case t: Trees.LiteralPat          => typedLiteralPat(s, t, scrutineeTy)
  case t: Trees.TuplePat            => typedTuplePat(s, t, scrutineeTy)
  case t: Trees.DataVariantPat      => typedDataVariantPat(s, t, scrutineeTy)
  case t: Trees.StructDestructurePat => typedStructDestructurePat(s, t, scrutineeTy)
  case t: Trees.OrPat               => typedOrPat(s, t, scrutineeTy)
  case t: Trees.GuardedPat          => typedGuardedPat(s, t, scrutineeTy)
  case t: Trees.Error | t: Trees.Missing => (s, t)                 // pass-through
  case other                        => failPat(s, other, scrutineeTy)
```

```scala
// typer/Typer.scala — type-expression dispatcher (called from Namer signatures
// and from typedX where a type appears in expression position)
def typedTypeExpr(s: TyperState, te: Tree)
                 (using Context): (TyperState, Type) = te match
  case t: Trees.PrimitiveType  => (s, Type.PrimType(t.name))
  case t: Trees.UnitType       => (s, Type.UnitType)
  case t: Trees.NeverType      => (s, Type.NeverType)
  case t: Trees.ArrowType      => typedArrowType(s, t)             // includes attached WithClause's EffectRow
  case t: Trees.TupleArrowLhs  => typedTupleArrowLhs(s, t)         // tuple-of-types LHS
  case t: Trees.TypeRef        => typedTypeRef(s, t)
  case t: Trees.CapRef         => typedCapRef(s, t)
  case t: Trees.IsBound        => typedIsBound(s, t)
  case t: Trees.NamedAlias     => typedNamedAlias(s, t)            // binds name into scope, returns Type.IsBound
  case t: Trees.RefinementCall => typedRefinementCall(s, t)        // → Type.RefinementType
  case t: Trees.SelfType       => typedSelfType(s, t)
  case t: Trees.PartType       => (s, Type.PartType)               // Part inside cap bodies (spec §5.3)
  case t: Trees.CapType        => typedCapType(s, t)
  case t: Trees.ParenTypeApp   => typedParenTypeApp(s, t)
  case t: Trees.Error | t: Trees.Missing => (s, Type.NeverType)    // recovery
  case other                   => failType(s, other)
```

`Trees.WithClause`, `Trees.EffectRow`, `Trees.EffectBound` are **not** dispatched by `typedTypeExpr` — they are sub-fragments consumed inline by `typedArrowType` (which reads its `withClause` field directly) and by `typedFnSignature` in the Namer (for fn-decl `with` clauses). They never appear at the top of a type expression and therefore have no place in this dispatcher; the v0.2 draft listed them by mistake.

```scala
// typer/Namer.scala — declaration dispatcher (signature pass)
def enterDecl(s: TyperState, decl: Tree)
             (using Context): TyperState = decl match
  case d: Trees.FnDecl         => enterFnSignature(s, d)
  case d: Trees.CapDecl        => enterCapDeclSignature(s, d)
  case d: Trees.DataDecl       => enterDataDeclSignature(s, d)
  case d: Trees.EffectDecl     => enterEffectDeclSignature(s, d)
  case d: Trees.TypeAlias      => enterTypeAliasSignature(s, d)
  case d: Trees.SatisfiesDecl  => enterSatisfiesDecl(s, d)
  case d: Trees.UseDecl        => enterUseDecl(s, d)
  case d: Trees.ModDecl        => enterModDecl(s, d)
  case d: Trees.PropDecl       => enterTopLevelPropDecl(s, d)      // rare; usually nested
  case d: Trees.Error | d: Trees.Missing => s                      // skip; parser already reported
  case other                   => failDecl(s, other)
```

Each `typedX` / `enterX` is short (< 100 lines target); long routines fan out to private helpers.

**Recovery contract.** `typedError` and `typedMissing` (and the analogous pattern / type-expr / decl handlers) produce a tpd node whose type is `Type.NeverType` (unconstrained — assignable to any expected type, useful as the type of `unreachable` too) and whose `id` carries the original Error/Missing's id. The recheck phases handle these per §4.3's recovery-semantics table.

### 5.3 Capability closure (M6 — recheck)

Per `spec/type_system.md` §6.2, the typed cap reference `cap C extends D + E` has a `D + E` immediate-supertraits list. The closure phase computes the *full* cap set:

1. For every `CapRef` in the typed tree, look up its symbol's `extends` list.
2. Recursively close: `closure(C) = {C} ∪ ⋃ closure(D) for D in extends(C)`.
3. Record the closed set as a refined `Type` in the M6-keyed `TypeMap`.
4. Verify `satisfies` declarations: for every `T satisfies C`, check that every member of `closure(C)` has a corresponding mapping (or `impossible as ctor` declaration). Surface unmapped requirements as diagnostics.

Operates as a single forward Recheck pass. `Env` carries the set-being-built and the current `SatisfiesScope`.

### 5.4 Effect inference (M7 — recheck)

Per `spec/effects.md`, every expression has an effect row. The inference rules:

- A literal has empty effect row.
- A primitive operation has empty effect row.
- A function call has effect row = callee's `with` clause + args' effect rows.
- A `do { ... }` block has effect row = union of stmts' effect rows.
- A `handle e: { Op.x => ...; return(p) => ... }` removes effect `Op` from `e`'s row, adding any effects raised by handler arms.
- An unhandled effect at `main` (or in a non-cap-of-returning fn body) is `error[T015]`.

Recheck pass; per-node `recheckX` methods compute the row and store it in the M7-keyed `TypeMap`. The base typer (M3–M5) treats `with` clauses as opaque type slots — M7 fills them in.

### 5.5 Exhaustiveness check (M8 — in-tree at M5, not a Phase)

**Naming convention.** `M8` denotes the **in-tree top-level variant coverage** check that runs as part of M5's `typedMatch`. The `phase-3.x-exhaustiveness.md` sub-plan covering nested-pattern, refinement-cap-aided, and unified handler-arm coverage is referred to as `M8'` (M8-prime) — that work is Part B and ships only after M7 (so its handler-arm coverage can consume effect-row info). Two distinct things, two distinct names.

Per `spec/pattern_matching.md`, every `match` must cover every variant of the scrutinee's data type. The check uses dotc's Space algebra (`transform/patmat/Space.scala`):

1. Classify the scrutinee's typed type → set of variants.
2. Each arm contributes a "covered space" (the set of values it accepts).
3. Union the covered spaces; if the result is not the full scrutinee space, list the missing variants.

Because this check requires only the M5 type information (not closure / effects / quantities), it runs in-tree as part of M5's `typedMatch` — `ExhaustivenessPhase` is **not** in the §4.1 phase list. M8 remains as a milestone gating the diagnostic quality of the in-tree check (the assertion in §1.3 is that `examples/09_interpreter.fixed` type-checks AND deleting one match arm produces the expected diagnostic). The `Exhaustiveness.scala` module is its own file for testability.

#### 5.5.1 Conformance gaps in v1 (deferred)

`spec/pattern_matching.md` §6 mandates more than top-level variant coverage:

| Spec rule | Mandate | v1 status |
|---|---|---|
| §6.1 | Nested-pattern jointly-exhaustive coverage (`Pair(Some(_), Some(_))` must consider all four `Some/None` quadrants) | **Deferred** to Phase 3.x — v1 ships top-level variant coverage only |
| §6.2 (M6.2) | Refinement-cap-aided exhaustiveness (a `match` on `Bounded(0, 3)` need only cover 0/1/2/3, not the full `u64`) | **Deferred** — v1 demands wildcards for refinement-narrowed scrutinees |
| §6.4 (M6.4) | Unified match-arm and handler-arm coverage under one Space algorithm | **Deferred** — v1 handler-arm coverage runs inside M7 EffectChecker with its own per-effect check |

A v1 typer that ships only top-level variant coverage is **non-conformant** per Rule M6.4.b ("an implementation that ignores refinement-cap evidence and demands wildcards even when the reachable domain is fully covered by literals is non-conformant"). This plan accepts the non-conformance for v1 and creates `docs/plans/phase-3.x-exhaustiveness.md` as the closing sub-plan. Authoring that plan is itself a Phase 3.x milestone (estimated 2–3 weeks of design + implementation).

The v1 conformance level is documented in the compiler's `--print:conformance` output (a small piece of M11 scaffolding) so users can see what's not enforced.

### 5.6 Property verifier (M9 — recheck)

Per `spec/properties.md`, `prop` declarations come in three forms:

1. **Static-decidable**: e.g. `prop nonneg: Self >= 0` where `Self` is a refinement-cap with a known finite range. Discharged at compile time via an SMT solver (see §5.6.1).
2. **Compile-time-undecidable, runtime-checkable**: e.g. `prop sorted: ...` for arbitrary lists. Queued as a property-based test obligation: a `PropTest` record with the prop body, the cap's domain, and an "N samples" budget for codegen-time generation.
3. **Trivially false**: counterexample found at compile time. `error[T030]: prop X: counterexample <example>`.

Recheck pass; the `PropVerifier` walks every prop, attempts (1), falls through to (2), reports (3).

#### 5.6.1 SMT solver: Princess

The static decision procedure is **Princess** (`io.github.uuverifiers:princess_2.13`, BSD-3-Clause), wrapped behind a thin internal trait. Princess is chosen over the alternatives surveyed for these reasons:

| Solver | Verdict | Why |
|---|---|---|
| **Princess** | **Adopt** | Pure-JVM (no JNI/native dep); BSD-3 license; actively maintained (2025-11-17 release); built for Presburger + uninterpreted predicates — exactly QF_LIA + propositional + light UF, our floor and ceiling; native Scala API integrates cleanly via `CrossVersion.for3Use2_13`. |
| Z3 (via JavaSMT) | Reject for v1 | Best-in-class perf, but native dep means shipping `.so`/`.dll`/`.dylib` and platform-specific cold-start cost. Document as Future Option. |
| CVC5 | Reject for v1 | Same native-dep issue as Z3; Java bindings lag C++. |
| SMTInterpol | Reject | LGPL-3 — workable but adds license-compatibility friction for downstream consumers. |
| MathSAT5 | Reject | Commercial license. |
| jSMTLIB / CafeSat | Reject | Not solvers (parser only) / abandoned. |

**Adapter trait:**

```scala
package fixed.caps

trait ObligationSolver:
  def check(formula: PropFormula): SolverResult

enum SolverResult:
  case Sat(model: Map[SymbolId, SymbolicTerm])      // counterexample
  case Unsat                                         // discharged
  case Unknown(reason: String)                       // timeout, fragment outside theory

final class PrincessSolver extends ObligationSolver:
  // wraps ap.SimpleAPI — see Princess docs for the standard usage pattern
  ...
```

`PropVerifier` (M9) builds a `PropFormula` per prop obligation (form: `forall (bindings). pre ==> body`), passes it to `solver.check`, and:

- `Unsat` → discharged; emit nothing.
- `Sat(model)` → trivially false; emit `error[T030]: prop X: counterexample <model>`.
- `Unknown` → fall through to a `PropTest` obligation queued for codegen-time PBT.

**Future option.** If Fixed ever needs bitvector theories at scale, nonlinear integer arithmetic (QF_NIA), or interpolation/optimization, switch to JavaSMT (`org.sosy-lab:java-smt`, Apache-2.0) and back its facade with Z3 / CVC5. Migration is one new adapter behind the trait; the rest of the typer doesn't change.

### 5.7 QTT recheck (M10)

Per `spec/quantities.md`, every binding has a quantity `0` (erased — type/prop/inferred-phantom positions only), `1` (linear — used exactly once), or `ω` (unrestricted — the default).

The `QtyEnv` (per dotc cc/CheckCaptures.scala:43-82 model) carries the current quantity context: which symbols are 0, 1, or ω. The Recheck per-node methods consume and produce environments. Linearity violations and erasure violations are diagnostics.

### 5.8 Representation selector (M11 — recheck)

Per `spec/type_system.md` §6.5, every cap classifier (Sum / Product / Recursive / Capability-only / Marker / Refinement) has a default representation choice. The selector walks the typed tree and assigns:

- Sum cap → tagged union (small variants → 1 byte tag + variant payload; large → boxed).
- Product cap → flat struct (field layout per spec §6.6).
- Recursive cap → heap-allocated; auto-derived `fold` → tail-recursive C function.
- Capability-only cap → vtable OR monomorphised (per call-site profiling input — initially monomorphised).
- Marker / Refinement cap → zero-sized at runtime; type-only.

Output: a `ReprMap: Map[SymbolId, Repr]`. Phase 4 codegen consumes it directly. Verification at M11 is via `RepresentationSelectorSuite` rather than a CLI flag — a `--print:X` family is a separate scoping question and not required for Phase 3.

## 6. Test strategy

### 6.1 Per-component unit tests

Each new module gets a Munit suite:

- `core/SymbolsSuite` — fresh-id monotonicity, denotation lookup, phase-keyed retrieval.
- `core/TypesSuite` — type construction, equality, free-variable lookup, simple subtyping (every `Type` case ↔ itself reflexively).
- `typer/NamerSuite` — index every example; assert the symbol table has the expected (name, kind) pairs.
- `typer/TyperSuite` — per-feature: literals, idents, arith, calls, let, if, match, lambda, struct lit, etc. Each test parses a snippet and asserts the typed tree's root type.
- `caps/CapClosureSuite` — closure of `extends` chains; satisfies-coherence checks.
- `effects/EffectCheckerSuite` — effect-row inference for every effect-producing form.
- `qtt/QuantityCheckerSuite` — quantity inference for trivial cases; linearity / erasure violations.
- `repr/RepresentationSelectorSuite` — every cap classifier case → expected representation.

### 6.2 Per-example end-to-end tests

`ExampleTypeCheckSuite` runs the full pipeline (parse → name → type → all rechecks) on every `examples/*.fixed`. Asserts:

1. No diagnostics.
2. Top-level symbol table populated.
3. Effect rows of all `main`-reachable functions are empty (handled).
4. QTT quantities present for every binding.
5. Representation map populated for every cap symbol.

### 6.3 Negative tests

A `negative-typed/` corpus mirroring the parser's `recovery/` corpus. Each entry pairs `<name>.fixed` (valid) with `<name>.broken.fixed` (one type error injected). Test asserts the expected diagnostic code, span, and message-prefix appear in the output.

Minimum coverage:

- Identifier resolution failure (`error[T001]: not found: foo`).
- Type mismatch in arith (`error[T002]: expected i64, got String`).
- Missing cap implementation (`error[T010]: type X does not satisfy Numeric: missing zero`).
- Conflicting `satisfies` (`error[T011]: ambiguous satisfaction of Optional for Maybe`).
- Unhandled effect (`error[T015]: unhandled effect Console`).
- Non-exhaustive match (`error[T020]: missing case Json.Null`).
- False prop (`error[T030]: prop in_range: counterexample n=11`).
- Linearity violation (`error[T041]: 1-quantity binding x used twice`).
- Erasure violation (`error[T040]: 0-quantity binding y used at runtime`).

### 6.4 Property tests

Two property tests, both Munit-hand-rolled:

- **Index stability**: for every example, parse → name → name again from a fresh Context yields the same SymbolTable up to id renaming.
- **Type idempotence**: for every example, type → re-type the resulting tpd tree (with the same SymbolTable). The two type maps are equal.

### 6.5 Perf checks

After each milestone, run `ParserBench` (no-op for typer code path but catches accidental parser regressions) AND a new `TyperBench` that times Namer + Typer over the corpus. Report the corpus mean ns/token before asking for a push (per the saved `feedback_perf_check_before_push` rule).

## 7. Critical disambiguations

### 7.1 `is`-bound at parameter site vs in expression position

The parser produces `Trees.NamedAlias("N", caps)` for `N is Numeric` in type position. In expression position, `is` is reserved and emitting it is a parse error (already enforced).

The typer's job: when a parameter has type `is Numeric` (anonymous bound), convert that to a fresh `TypeVar` constrained by `Numeric`. When the same parameter is `N is Numeric`, allocate a `TypeVar` and bind name `N` into the local scope so subsequent occurrences in the same signature unify with it.

### 7.2 `Self` and `Self of B` resolution

`Self` is contextual. Inside a `cap C: ...` body, `Self` resolves to a fresh `TypeVar` representing the implementing type. Inside a `T satisfies C: ...` body, `Self` resolves to `T` directly. Outside both contexts, `Self` is an error.

`Self of B` resolves to `Self` applied to type-arg `B`. The typer threads the cap context through the `Context` (`ctx.selfType: Option[Type]`).

### 7.3 `cap of T` as return type

`fn f(...) -> cap of T = ...` — the function returns a *cap* (a value of capability type), not a value of type `T`. The typer treats this as a special return-type form: the body must produce a cap value (via `cap C: { ... }` literal or by returning a cap-valued expression).

Per `spec/type_system.md` §6.3.0, the same is allowed at any TypeExpr position — `(N, N) -> cap of N` is a valid parameter type.

### 7.4 `satisfies` resolution scope

`use T satisfies C` introduces a satisfaction binding into the current scope. At a use site (e.g. `Optional.some(5)`), the typer searches:

1. Locally bound satisfactions (direct `use ... satisfies ...`).
2. Imports (`use M.{X, Y}` which transitively brings in M's satisfactions).
3. Module scope.
4. Top-level / "prelude" scope.

The first match wins. Multiple matches in the same scope are an ambiguity error (M6, T011). Unlike Scala implicit search, there is no global coherence check — a module can have its own conflicting satisfactions as long as they don't co-exist in the same use site's scope.

#### 7.4.1 Soundness of local-scope satisfies

The "no global coherence" choice is a real divergence from Scala/Haskell/Rust. Haskell's orphan-instance problem and Rust's coherence rules exist precisely because a value of type `Maybe[Int]` viewed through `Optional` in module A and viewed through a *different* `Optional` satisfaction in module B can satisfy different invariants — passing the value across the module boundary breaks reasoning. Fixed must address this; the choice is not free.

**The Fixed soundness model (v1):**

1. **Public function signatures pin satisfactions at the boundary.** When a function with parameter type `M is Optional` is called from somewhere whose scope has a *different* in-scope `Optional` satisfaction, the *caller's* satisfaction is the one used for the call site's resolution. The callee body — typed under *its own* satisfaction — sees the same operations (constructors, methods) but the runtime mappings can differ. This is sound because every cap method's signature is fixed; the implementation is opaque to the caller.

2. **Value passing across satisfaction boundaries is by cap interface, not by satisfaction.** A `Maybe[Int]` value carries no "I was created under satisfaction S" tag; it's just data. If two reachable scopes within one compilation unit export the same `(T, C)` pair with different satisfactions, the importing scope gets `error[T012]: incoherent satisfaction of C for T from scopes A and B`. M6 enforces this within a compilation unit by walking the transitive `use` graph.

3. **"No global coherence" is bounded**: within a single module's transitive imports, satisfactions must agree on shape. The "first match wins" rule in §7.4 applies only when multiple bindings give the *same* satisfaction (e.g. via different import paths) — not when they give different satisfactions.

4. **What v1 actually checks** (single-module, no separate compilation): M6's coherence check operates on the in-memory `SatisfiesScope` graph built from the module's `use` declarations and stdlib prelude. With one source file in v1, the check fires on stdlib-import disagreements and on multiple `use ... satisfies ...` bindings within the same file. There are no compiled-module boundaries to traverse — this is **intra-compilation-unit coherence**, not cross-module coherence in the separate-compilation sense.

5. **Separate compilation, deferred**: Phase 5+ adds separate compilation. At that point M6 will need either (a) to bake the satisfaction identity into the compiled cap-value representation (one bit per satisfaction binding per cap) so cross-module value flows can be checked at link time, or (b) to require all modules in a build to agree on a single satisfaction per `(T, C)` pair (Rust's choice). The decision is deferred. The v1 check above is the intra-unit substrate that will compose with whichever choice is made.

References: Haskell orphan-instance discussion (`OverlappingInstances`, `IncoherentInstances`); Rust RFC 1023 (Rebalancing Coherence); `spec/type_system.md` §6.2 on `extends` resolution.

### 7.5 Refinement cap value-param resolution

`cap Between(min: N, max: N) of N: prop in_range: min <= Self && Self <= max` — at a use site `between(0, 10)`, the value-params `min`/`max` are bound to the literal arguments. The prop expression `min <= Self && Self <= max` becomes `0 <= Self && Self <= 10`, which the prop verifier (M9) attempts to discharge for the Self-type's domain.

The typer (M5) handles the binding; the prop verifier (M9) handles the proof obligation.

### 7.6 Phantom type parameters

Per `spec/type_system.md` §5.9, phantom type parameters are *inferred* — there is no surface `phantom` keyword. A type parameter is phantom if it appears nowhere in the value-position arguments and only in marker / refinement caps. The QTT pass (M10) detects this by computing each type parameter's quantity: 0 → phantom, ω otherwise.

The `phantom` keyword was removed from the language in v0.4.1; the parser doesn't recognise it. The typer's job is the inference; the codegen (Phase 4) elides phantom parameters at the C level.

## 8. Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Type representation grows beyond the §3.3 closed set as edge cases surface. | High | Add new cases via an entry in §10 (Open questions) of this plan — every new `Type` case requires updating every recheck phase's per-node logic. Promote to a separate ADR document only when a second cross-cutting decision warrants the discipline. |
| Bidirectional inference is hard to get right for `cap of T` returns and `fn -> cap` arguments. | Medium | M3 ships with a small set of fully-worked test cases (prototype tests in `TyperSuite`). Don't move on until those pass. |
| Capability closure performance — recursive `extends` could blow up. | Low | Memoise per-cap-symbol; the closure is a fixpoint and converges in one pass for finite cap hierarchies. |
| Prop verifier scope creep — full SMT solver is months of work. | High | M9 ships with **only** linear-arithmetic + boolean propositions discharged statically. Everything else queued as runtime PBT obligations. The static decision procedure can grow in Phase 6. |
| QTT recheck and capability closure interact (a 0-quantity cap binding has no runtime presence; M11 must skip it). | Medium | Order phases as M6 → M10 → M11 so QTT info is available before representation selection. |
| Memory growth from per-phase TypeMap accumulation. | Low | Default storage (§4.3) keeps only the current phase's `IntMap[Type]` in memory; prior phases are dropped after their successors read what they need. Worst-case footprint = O(tree-count × 2) at any moment. Promote to phase-keyed only when a downstream consumer demands historical views. |
| FP discipline causing perf regression as in Parser. | Medium | JFR-driven recovery milestones built into M2 (post-Namer), M5 (post-base typer), M11 (post all rechecks). Same playbook as commit `daab53c`. |

## 9. Effort estimate

Two budgets — one for Part A (this plan), one for the Part B sub-plans (covered separately).

#### Part A — Base typer (this plan)

| Milestone | Effort | Depends on | Notes |
|---|---|---|---|
| M1 (scaffolding) | 2–3 days | Phase 2.1 | No AST changes — Trees stays as-is; the §3.0 span-as-identity invariant is documented and an audit test is added that asserts no two parser-allocated trees compare equal across the corpus |
| M2 (Namer + core machinery + TyperBench baseline + perf checkpoint #1) | 1.5 weeks | M1 | The "two-sub-pass Namer" pattern is new; budget extra for getting fixpoint right |
| M3 (Type ADT + ProtoTypes + Typer skeleton + Unifier) | 1.5 weeks | M2 | Includes the §10.1 type-equality decision (structural vs interned) made on TyperBench data |
| M4 (decl body typing) | 1 week | M3 | Mutual recursion handled by M2's signatures |
| M5 (expression + pattern typing + in-tree top-level exhaustiveness + perf checkpoint #2) | 2 weeks | M4 | The largest single milestone; ~25 typer routines with non-trivial logic each |
| Buffer A (integration, perf recovery, negative-test corpus authoring, doc updates) | 1.5 weeks | — | Two perf-recovery cycles in M2 + M5 are separately budgeted; this absorbs the rest |

**Part A subtotal: ~6.5 weeks** for one focused contributor. Range: 5–8 weeks depending on how many of the §10 deferred decisions surface during M3 (Type equality) and M5 (recovery semantics). Down ~1 week from v0.2 because dropping the tree-id field eliminates the parser refactor + test-pattern churn.

Earlier Phase 2 + 2.1 milestones each took 1–2 weeks of calendar time when measured against committed work; this estimate is calibrated against that.

#### Part B — Layered analyses (separate sub-plans, sketched here)

| Sub-plan | Estimate | Risk drivers |
|---|---|---|
| `phase-3.1-capability-closure.md` (M6) | 1.5–2 weeks | Cross-module coherence check (§7.4.1) is new design |
| `phase-3.2-effects.md` (M7) | 1.5–2 weeks | Effect-row unification + handler reentry semantics |
| `phase-3.3-properties.md` (M9) | 2–3 weeks | Princess integration + counterexample formatting + the `forall (bindings). pre ==> body` translation |
| `phase-3.4-qtt.md` (M10) | 1.5–2 weeks | Linearity + erasure check, 0-quantity inference for phantom params |
| `phase-3.5-repr.md` (M11) | 1 week | Mostly mechanical given the 6 classifier cases |
| `phase-3.x-exhaustiveness.md` (M8') | 2–3 weeks | Maranget-style nested + refinement-cap-aided + handler-arm unification |

**Part B subtotal: ~10–14 weeks** if executed sequentially. Some sub-plans (M9, M10) can run in parallel after M6 + M7 land.

#### Total

**Phase 3 overall: ~16–22 weeks** for one focused contributor across Part A + Part B. The base typer (Part A) is the single deliverable owned by this document; Part B sub-plans extend it.

Comparison: dotc's `Typer.scala` is 5,309 LoC, `Namer.scala` 2,268, `Applications.scala` 2,959 (plus the rechecks). We are producing functioning analogues at perhaps 1/30 the LoC for a much smaller surface area. The estimate accounts for that scaling but not for fundamental research (e.g. proving completeness of the Recheck dependency graph from §4.1.1).

## 10. Open questions (deferred decisions)

These are genuine open questions that should be resolved during M1/M2 implementation rather than now:

1. **`Type` equality**: structural? Or interned via a hash-cons table? Decision driven by M5 perf data — the M5 perf checkpoint scheduled in §8 (FP-discipline-perf risk row) is where this measurement lands.
2. **QTT for type parameters vs term parameters**: spec is unambiguous (`spec/quantities.md` §3) but the recheck implementation differs. Confirm both paths in M10.
3. **Exhaustiveness for nested patterns**: dotc's Space algebra is the gold standard but heavy. Initially: only top-level variant coverage; nested-pattern exhaustiveness deferred to `phase-3.x-exhaustiveness.md`.
4. **Whether to materialise `untpd` / `tpd` type aliases** or just use `Tree` everywhere with the type lookup via `TypeMap`. Plan: ship type aliases as documentation but treat them as `type Tree = Tree` in v1 (no enforcement).

(The "roll our own SMT solver vs use external" question that appeared in v0.1 is resolved in §5.6.1: Princess.)

## 11. Cross-references

| Document | Relationship |
|---|---|
| `docs/plans/phase-2-parser-ast.md` | Phase 2 — produced the AST consumed here. AST §4.7 anticipates the typed-tree split. |
| `docs/plans/phase-2.1-incremental-parser.md` | Phase 2.1 — ParseResult + gap nodes. The typer must handle `Trees.Error` / `Trees.Missing` per §3.2 of that plan. |
| `docs/plans/implementation-plan.md` | Phase 3 entry — this plan supersedes its terse Phase 3 section. |
| `docs/references/scala3-compiler-reference.md` | §4 (typer pipeline) and §5.4 (Recheck pattern) are the canonical sources for the design adopted here. |
| `spec/type_system.md` | The normative type system this typer implements. |
| `spec/effects.md` | M7 reference. |
| `spec/pattern_matching.md` | M5 (matching) + M8 (exhaustiveness) reference. |
| `spec/properties.md` | M9 reference. |
| `spec/quantities.md` | M10 reference. |
| Future: `docs/plans/phase-4-codegen.md` | Phase 4 will consume the typed-tree + recheck refinements (capability sets, effect rows, quantities, representations) emitted here. |
