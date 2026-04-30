# Fixed Properties

**Status:** Draft v0.2
**Specifies:** `prop` declarations, `forall` / `implies` / `suchThat` constructs, the verification strategy (static-then-PBT), property-based test generation, and how verified properties feed compiler optimization for Fixed v0.4.4 source.
**Last revised:** 2026-04-30

**Changes from v0.1.**
- `implies` is now **eager** (no automatic vacuous-truth discard in PBT) — it is plain propositional implication in both static and PBT contexts.
- New `suchThat` clause on `forall` (§5.4) is the dedicated **filter** for PBT: only values satisfying the `suchThat` expression count toward the iteration budget. This separates *logic* (`implies`) from *generator filtering* (`suchThat`) and makes test intent more explicit.
- §4.1 reframed: prop bodies are **excluded from the compiled artifact** rather than "checked at quantity 0". The semantic effect is the same; the framing accommodates PBT, which genuinely runs the prop body in a separate test build with normal quantity arithmetic.
- New §7.4 specifies a verification cache (deferred implementation) keyed on prop-body hash + obligation set.
- New Rule P7.6 specifies compiler flags for disabling PBT, treating PBT failure/timeout as errors, and overriding iteration count and time budget.
- All open questions (OQ-P1..P6) remain open.

## 1. Scope

This document specifies Fixed's `prop` system end-to-end: where invariants can be declared, how their bodies are typed, the strategy by which the compiler verifies them, what falls out as compiler optimization, and how `prop` interacts with `extends` inheritance and the `result` postcondition binder. Concretely:

- The four contexts in which `prop` appears (§3): cap members, data members, fn-body postconditions, and `fn -> cap` (refinement-cap) returns.
- The expression sublanguage allowed inside a prop body (§4): quantity 0, no effects, `forall` and `implies` reachable here only.
- The `forall` quantifier (§5) and the `implies` connective (§6).
- The verification strategy: static-first, PBT-fallback (§7), with mechanics for generator derivation, shrinking, iteration, and counterexample reporting (§8).
- How verified props become compiler assumptions and drive optimisation (§9), including `unreachable` discharge.
- How props compose through `extends` (§10).

This document **resolves OQ7** from `spec/type_system.md` — the implicit `result` binder in fn-body postconditions is specified normatively here in §3.3.

It does **not** specify:

- The choice of SMT solver, its integration interface, or its proof-format details. The static prover is treated abstractly; concrete tooling is implementation-defined (see OQ-P1).
- The PBT generator framework's full API. This document specifies what properties generators must satisfy and the failure-reporting protocol; the surface API (custom generators, shrinkers, etc.) is implementation-defined.
- Quantity inference for prop bodies — already specified in `spec/quantities.md` Rule Q5.5.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.3 — `PropDecl`, `PropExpr`, `ForallExpr`, `ImpliesExpr`, `Binder`.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), Rule 6.1 (default cap method bodies), §6.4 (Marker class — refinement caps), §7.4.a (auto-derived `fold` referenced in invariant bodies), §11 OQ7 (this doc resolves).
- `spec/quantities.md` v0.1 — Rule Q5.5 (prop bodies at quantity 0), Rule Q5.9/Q5.10 (semiring usage in expressions).
- `spec/effects.md` v0.2 — effects forbidden in prop bodies (§4.2 here).
- `spec/pattern_matching.md` v0.2 — match expressions are allowed inside prop bodies but checked at quantity 0; refinement-aided exhaustiveness (Rule M6.2) and prop verification share the refinement-cap evidence machinery.

Theoretical basis:

- Hoare, *"An Axiomatic Basis for Computer Programming"*, CACM 1969 — preconditions/postconditions; the `result` binder in §3.3 is the standard postcondition pattern.
- Floyd, *"Assigning Meanings to Programs"*, AMS 1967 — verification conditions.
- Claessen & Hughes, *"QuickCheck: A Lightweight Tool for Random Testing of Haskell Programs"*, ICFP 2000 — the PBT model Fixed adopts for the fallback verifier.
- MacIver et al., Hypothesis (Python) — the shrinker model used in §8.2.
- Filliâtre & Paskevich, *Why3* — the static-prover-driven model Fixed's verification pipeline broadly resembles.

## 3. Where `prop` declarations appear

A `PropDecl` per the grammar has the form:

```
prop NAME: BODY
```

where `BODY` is a `PropExpr` (extends `Expr` with `forall` and `implies`, per §5–§6). `prop` declarations are valid in **four** syntactic positions, each with slightly different semantics:

### 3.1 Cap members (Rule P3.1)

A `prop` declared inside a `cap` body is an **interface invariant**: every type that satisfies the cap must uphold the property. The typer adds the prop to the cap's obligation set; verification runs at every concrete `T satisfies C` declaration.

```
cap Sorted of (Part is Ord) extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)
```

Implicit bindings inside the body:

- `Self` — the satisfying type (any `T` with `T satisfies Sorted`).
- `Part` — the cap's first non-phantom type parameter (per type_system.md §5.3).
- Cap members declared above the prop — the prop body may call them.

### 3.2 Data members (Rule P3.2)

A `prop` declared inside a `data` body is a **data invariant**: every value of the data type must uphold the property. The typer verifies the invariant at every constructor call site (statically when possible; runtime checks elided when verified).

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi
```

Implicit bindings inside the body:

- `Self` — the data type itself (with all `of` arguments fixed).
- The data's `of` parameters (e.g., `lo`, `hi` above) — accessible as compile-time constants per Rule Q6.2.
- Constructor variants and their fields (via `forall` quantification: `forall (b: Self) -> ...`).

### 3.3 Function-body postconditions and the `result` binder (Rule P3.3) — resolves OQ7 in type_system.md

A `prop` declared at the top of a function body is a **postcondition** — a property the function's return value (and arguments) must satisfy after execution.

```
fn sort(collection: C is Sequencing + Sized of (A is Ord)) -> C is Sorted =
    prop result_same_size: result.size == collection.size
    collection.fold_right(C.empty, (x, acc) -> insert_sorted(acc, x))
```

**Rule P3.3 (the `result` binder).** Inside a fn-body `prop` declaration, the identifier `result` is an **implicit binder** for the function's declared return value at the function's declared return type. The binding is in scope **only inside that prop body**.

In other prop contexts (cap-member or data-member props, refinement-cap returns), the identifier `result` carries no special meaning — it is parsed as an ordinary identifier.

A function body may contain multiple postconditions; each is a separate `prop` declaration:

```
fn insert_sorted(sorted: C is Sorted, value: A) -> C is Sorted =
    prop grows_by_one: result.size == sorted.size + 1
    prop preserves_sortedness: sorted_check(result)
    ...
```

Implicit bindings: function parameters (in the prop body's outer scope), `result` (the implicit return-value binder).

### 3.4 `fn -> cap` returns — refinement caps (Rule P3.4)

A function whose return type is `cap of T` (or `cap extends C`) — a **cap-generating function** per type_system.md §6.3 — has a body composed entirely of `prop` declarations. The body defines the refinement cap's invariants:

```
fn between(min: N is Ord, max: N) -> cap of N =
    prop in_range: min <= Self && Self <= max

fn positive() -> cap of (N is Numeric + Ord) =
    prop positive: Self > 0
```

Implicit bindings inside the body:

- `Self` — the value being constrained (an `N` that satisfies the generated cap).
- The cap-generating function's parameters (`min`, `max` above) — accessed at quantity 0 per Rule Q5.3.

The body of a `fn -> cap` is the *only* fn body in which a non-prop expression at the trailing position would be unusual — a refinement cap's body is, in effect, a list of obligations. (Other fn bodies may carry `prop` postconditions but always end in a value-producing expression per Rule M9.1's logic; refinement-cap bodies do not produce a value.)

## 4. Prop body expressions

### 4.1 Prop bodies are excluded from the compiled artifact (recap)

Per `spec/quantities.md` Rule Q5.5 (v0.2), prop bodies are **not included in the compiled artifact** (the program binary). References to bindings inside a prop body therefore do not contribute to the binding's runtime quantity in the artifact — if a binding is used only inside props, it has quantity 0 in the artifact and is erased.

Property verification, when it requires running the body (§7's PBT path), compiles the body separately into a **test build** — a distinct compilation context from the program binary. Inside the test build, bindings have their normal quantities determined by all uses (the body genuinely runs at test time). The test build is not part of the production binary; it is the artifact a `fixed verify` invocation produces, separate from `fixed compile`.

The earlier draft's wording "checked at quantity 0" was a shorthand. The v0.2 framing makes the artifact distinction explicit because PBT-driven verification involves actual runtime evaluation.

### 4.2 No effects (Rule P4.2)

A prop body's effect row must be **empty**. Calls to effect operations (`Console.print_line`, `Fail.fail`, etc.) are forbidden inside prop bodies: `error[E100]: effect performed in prop body`.

This follows from Rule Q5.5: at quantity 0, no operation runs, so effect calls would be vacuous *and* break the erasure-soundness invariant of Rule Q6.1. The typer enforces effect-row emptiness as a separate check for clearer error messages.

### 4.3 `forall` and `implies` are reachable only inside prop bodies (Rule P4.3)

The grammar's `PropExpr` extends `Expr` with `ForallExpr` and `ImpliesExpr`. These two forms appear only inside prop body positions — inside an `if cond:` body in regular code, `forall` is a parse error.

This is by design: `forall` and `implies` carry compile-time-verification semantics that have no runtime meaning. Restricting them to props makes their semantic role unambiguous.

### 4.4 `Self` and `Part` references

Inside a prop body, the keywords `Self` and `Part` follow the same scoping rules as elsewhere in the type system (per `spec/type_system.md` §5.3, §5.4):

- Inside `cap C` props: `Self` is the satisfying type; `Part` is the cap's first non-phantom type parameter.
- Inside `data D` props: `Self` is the data type itself.
- Inside `fn -> cap` bodies: `Self` is the value being constrained.

### 4.5 `match` and `if` inside prop bodies

Pattern matching and conditional expressions are allowed inside prop bodies. They follow Rule Q5.9 (branching uses `⊔`) at quantity 0:

```
cap Sorted extends Sequencing:
    prop sorted_or_empty: match Self.head:
        Optional.none => true
        Optional.some(_) => fold(true, (acc, prev, curr) -> acc && prev <= curr)
```

Match arms inside prop bodies are checked at quantity 0, but the same exhaustiveness rules (Rule M6.1, Rule M6.2) apply — props must be total expressions producing a `bool`.

## 5. The `forall` quantifier

### 5.1 Syntax recap

```
ForallExpr = "forall" "(" Binder ( "," Binder )* ","? ")" "->" PropExpr
Binder     = LOWER_IDENT ":" TypeExpr
```

Each binder introduces a universally quantified variable.

### 5.2 Type rule (Rule P5.2)

`forall (x1: T1, ..., xn: Tn) -> body` is well-typed iff `body` has type `bool` under the extended environment in which each `xi` has type `Ti` at quantity 0. The whole expression has type `bool` and asserts that `body` is true for every combination of values in `T1 × ... × Tn`.

### 5.3 Domain enumerability for PBT (Rule P5.3)

A `forall` is verifiable iff every binder's type has a **PBT generator** in scope. A type has a generator if at least one of the following holds:

1. It is a primitive type with a built-in generator (`bool`, `i8`..`i128`, `u8`..`u128`, `f32`, `f64`, `String`, `char`).
2. It is a `data` type whose every field type has a generator (recursive — depth-bounded for the recursion case).
3. It is a tuple of types each with a generator.
4. It is a capability bound `is C` such that at least one in-scope `T satisfies C` has a generator.

Refinement caps (Marker class) restrict an underlying generator. `between(0, 10)` constrains an integer generator to `{0..10}`.

**Rule P5.3 (Generator availability).** If a `forall` binder's type has no generator and the prop body cannot be statically proven, the typer halts: `error[E101]: cannot verify <prop> — no generator for <type>`. The user resolves by providing a custom generator (mechanism deferred — see OQ-P2) or by restricting the binder's domain via a refinement cap.

### 5.4 `suchThat` (Rule P5.4)

A `forall` quantifier may carry an optional `suchThat` clause that filters generated values:

```
ForallExpr = "forall" "(" Binders ")" ( "suchThat" Expr )? "->" PropExpr
```

```
prop pop_decrements: forall (s: Self) suchThat s.size > 0 ->
    s.pop().size == s.size - 1
```

**Rule P5.4.a (Type and scope).** A `suchThat` expression must be a `bool`-typed expression in the binders' scope. It is checked under the same rules as the prop body: artifact-excluded per §4.1, no effects per §4.2.

**Rule P5.4.b (Static-proof semantics).** In static proof, `forall (xs) suchThat C -> body` is **logically equivalent** to `forall (xs) -> C implies body`. The static prover sees the same proof obligation either way.

**Rule P5.4.c (PBT semantics).** In PBT, `suchThat` is a **generator-side filter**: the test driver generates a value, evaluates the `suchThat` expression, and:

- If the expression is `true`: the value is used to test `body`. The case counts toward the iteration budget.
- If the expression is `false`: the value is **discarded** and a fresh value is generated. The case does *not* count toward the iteration budget.

This makes `suchThat` a precise tool for narrowing the test domain to inputs that actually exercise the property's interesting case. Compare with `implies` (§6.2), which evaluates eagerly and counts every generated case toward the budget.

**Rule P5.4.d (Discard-rate guard).** If the PBT driver discards more than 90% of generated values (default; configurable via `--prop-discard-threshold`), the test halts with a warning: `warning[W106]: prop <name> — high discard rate (<rate>%); consider widening generators or relaxing suchThat`. This catches `suchThat` clauses too restrictive to make practical PBT progress; the user typically responds by tightening the generator (via a more specific refinement cap on the binder type) or by switching to a static-only verification mode (see Rule P7.6).

## 6. The `implies` connective

### 6.1 Syntax recap

```
ImpliesExpr = OrExpr "implies" PropExpr
```

`implies` is **right-associative**: `A implies B implies C` ≡ `A implies (B implies C)`. It is a `bool`-valued binary connective.

### 6.2 Eager semantics (Rule P6.2)

`implies` is **eager** in both static proof and PBT contexts: `A implies B` evaluates both `A` and `B` and yields `(not A) or B`. There is no automatic vacuous-truth discard.

In **static proof**, this is standard propositional implication.

In **PBT**, every generated input contributes one case to the iteration counter — there is no implicit input filtering by `implies`. If most generated inputs satisfy `A` only rarely, the prop body is testing the implication on mostly-vacuous cases. To filter at the generator level (and use the iteration budget on inputs that actually exercise the property's interesting case), use `suchThat` (§5.4) instead.

### 6.3 When to use `implies` vs `suchThat`

| Goal | Use |
|---|---|
| State a logical relationship between two propositions, without filtering inputs | `implies` |
| Generate only inputs that satisfy a predicate, focusing the PBT iteration budget | `suchThat` (in `forall`) |

Example contrasting the two:

```
// `implies` — every generated stack tested; empty stacks pass vacuously
//             (they make the antecedent false, but PBT still counts the case).
prop pop_decrements_implies: forall (s: Self) ->
    s.size > 0 implies s.pop().size == s.size - 1

// `suchThat` — only non-empty stacks generated; PBT spends iterations on
//              the interesting case.
prop pop_decrements_filter: forall (s: Self) suchThat s.size > 0 ->
    s.pop().size == s.size - 1
```

Static proof of either form is equivalent — the prover sees `s.size > 0 → s.pop().size == s.size - 1` in both cases. The difference is purely in PBT iteration accounting.

## 7. Verification strategy

The compiler verifies each prop using a **static-first** strategy. PBT runs as a fallback when static proof is inconclusive.

### 7.1 Static-first decision rule (Rule P7.1)

For each `prop`, the compiler attempts:

1. **Static proof.** Submit the prop to the static prover (typically an SMT solver — choice deferred to OQ-P1) with a bounded time budget (default 5 seconds per prop, configurable).
2. **PBT fallback.** If the static prover reports `unknown` (no proof, no counterexample) or times out, run PBT (§8) for at least 100 non-discarded iterations.
3. **Failure path.** If the static prover reports `falsified` (a counterexample exists) OR PBT finds a counterexample, emit `error[E102]: prop failed: <name>` with the counterexample and a copy-pasteable test snippet.
4. **Success path.** If the static prover reports `proved` OR PBT runs to completion without counterexample, the prop is accepted and recorded as a verified compiler assumption (§9).

### 7.2 Static proof obligations (Rule P7.2)

The static prover sees:

- The prop body.
- Free bindings: `Self`, `Part`, fn parameters, `result` (per §3.3), data-`of`-params (per §3.2).
- Type information for every binding.
- Verified props of inherited caps (per `extends`, §10).

The prover does **not** see effect operations, runtime values, or the body of fn calls (these are typically opaque to first-order reasoning). A prop body referencing fn calls without static-evaluable outcomes typically reports `unknown` from the prover; PBT is then used.

### 7.3 PBT fallback details

See §8 for generator/shrinking/iteration mechanics. Key invariant: PBT failure is a hard error; PBT non-failure-after-iteration is acceptance (with the understanding that PBT is incomplete — false positives are possible, false negatives are not).

### 7.4 Verification caching (Rule P7.4 — implementation guidance)

Property verification can be expensive — SMT calls and PBT iterations scale with prop count and complexity. The compiler **should cache** per-prop verification results across invocations.

The cache key for each prop is the tuple of:

- The canonical hash (Blake3 or equivalent) of the prop body's typed AST.
- The relevant in-scope obligations: refinement caps applied to binder types, `satisfies` declarations referenced, inherited props (via `extends`).
- The configured PBT iteration count and time budget (Rule P7.6 below).
- The prover version / SMT solver version (so prover upgrades invalidate cache).

A cache hit skips both static proof and PBT entirely. A cache miss runs the full verification. Cache invalidation occurs when any key component changes.

This rule is **implementation guidance**, not a normative requirement: a conformant compiler may run verification fresh every time. Caching is strongly recommended for development ergonomics — without it, every full build re-runs PBT iterations on every prop, which is unacceptably slow at scale.

Implementation deferred. A reasonable v1 cache is a per-project file-system store at `.fixed/cache/props/`, with one entry per (key, result) pair.

### 7.5 Failure mode interaction with `unreachable`

If a prop is statically proven, the typer may use the prop to discharge `unreachable` calls in code paths that the prop excludes. See §9.

### 7.6 Compiler flags (Rule P7.6)

The user controls verification behavior via the following flags on `fixed verify`, `fixed compile`, and `fixed ship`:

| Flag | Effect |
|---|---|
| `--no-pbt` | **Disable PBT entirely.** Props that the static prover reports `unknown` are accepted with `warning[W107]: prop <name> not statically verified; PBT disabled`. Useful when CI time is constrained or when PBT is known to be flaky for a given prop set. |
| `--strict-pbt` | Treat PBT *failure* AND *timeout* as errors (the default treats timeout as `warning[W103]`). Useful for release builds where any unverified prop should block. |
| `--prop-iterations=N` | Override default PBT iteration count (default: 100 non-discarded cases per prop). |
| `--prop-timeout=Ts` | Override default total time budget per prop (default: 30s). |
| `--prop-discard-threshold=R` | Override the discard-rate guard (Rule P5.4.d, default 0.9 — i.e., 90%). |
| `--no-prop-cache` | Disable the verification cache (Rule P7.4). Forces fresh verification on every invocation. Useful for debugging cache invalidation issues. |

`--no-pbt` is mutually exclusive with `--strict-pbt`; combining them is a CLI usage error.

When `--no-pbt` is set, props requiring PBT are not verified. The compiler emits W107 warnings for each. The compiled artifact still excludes prop bodies (per §4.1) — the only effect is that the *verification step* is skipped.

## 8. Property-based test generation

When PBT runs for a prop, the compiler synthesises a test driver per the algorithm below.

### 8.1 Generator derivation (Rule P8.1)

For each binder type `Ti` in a `forall`, derive a generator:

| Type | Generator |
|---|---|
| `bool` | uniform random in `{true, false}` |
| `iNN`, `uNN` | uniform random over the type's range; or refinement-cap-narrowed range when applicable |
| `f32`, `f64` | a mix of small integers, special values (0.0, ±∞, NaN), and uniform random |
| `String` | random-length sequences of random chars; refinement-cap-narrowed length when applicable |
| `char` | uniform random Unicode code point (BMP biased) |
| `data T` | choose a variant with weighted probability (more recursive variants down-weighted at greater depths); recursively generate fields |
| Tuple | generate each component independently |
| `is C` | pick an in-scope `satisfies` instance with a generator; defer to that |

Recursive `data` types use a depth-bounded generator (default depth 5; configurable).

### 8.2 Shrinking (Rule P8.2)

When a counterexample is found, the engine **shrinks** the input toward a minimal failing case:

- For numeric types: try smaller magnitude, then zero, then sign-flip.
- For strings: try shorter prefixes.
- For data: try variants in declaration order; recursively shrink each field.
- For tuples: shrink each component independently.

Shrinking continues until no smaller failing input is found (within a step budget). The reported counterexample is the shrunken minimum.

### 8.3 Iteration count and timeout (Rule P8.3)

Default iteration count: 100 non-discarded cases per prop. Default total time budget: 30 seconds per prop.

Both are configurable via:

- A compiler flag (`--prop-iterations=N`, `--prop-timeout=Ts`).
- A per-prop annotation (mechanism deferred — see OQ-P3).

If the time budget elapses before `N` non-discarded cases, the typer warns: `warning[W103]: prop <name> verified by N′ iterations (budget exceeded)`. The prop is accepted but the user is informed.

### 8.4 Counterexample reporting (Rule P8.4)

When a counterexample is found, the error message includes:

- The prop name and source location.
- The shrunken counterexample input — concrete values for every binder.
- A copy-pasteable snippet that the user can place in a test file to reproduce the failure deterministically.
- A suggestion to either fix the prop or fix the implementation that violated it.

The format of this error message follows the project-wide "Agent-Friendly CLI and Compiler Output" guidelines from `docs/plans/implementation-plan.md` — structured (parseable as JSON via `--format json`), with explicit error codes.

## 9. Verified props as compiler assumptions

A prop verified by either static proof or PBT becomes a **compiler assumption**. This unlocks optimisations:

### 9.1 Examples (informational)

| Prop | Optimisation enabled |
|---|---|
| `prop sorted: ...` on a `Sequencing` | `find(x)` lowers to binary search rather than linear scan. |
| `prop non_empty: size > 0` | `head` returns `Part` directly instead of `is Optional of Part`; `fold`'s empty case is unreachable and can be elided. |
| `prop in_range: lo <= value <= hi` (Bounded) | Bounds checks at use sites are elided; arithmetic that would have required checked overflow uses unchecked. |
| `prop positive: Self > 0` | `0 - n` becomes guaranteed-non-negative; `if n > 0` branches compile to unconditional. |

The mechanism: each verified prop annotates the symbol's denotation (per `docs/references/scala3-compiler-reference.md` §4.4) with a *fact* the optimiser may consult. Optimisations are guided by these facts but never depend on them for correctness — a missing fact is simply a missed optimisation.

### 9.2 `unreachable` discharge (Rule P9.2)

A statically-verified prop may discharge `unreachable` calls. If the prop excludes the value-domain that would reach `unreachable`, the optimiser can elide the unreachable branch entirely:

```
fn median(collection: C is SortedNonEmpty of (N is Numeric)) -> N =
    let mid = collection.size / 2
    collection.get(mid).fold(
        (v) -> v,
        () -> unreachable,    // statically discharged: NonEmpty proves get(mid) is some
    )
```

Per `prop non_empty: size > 0` on `NonEmpty`, the typer proves `collection.get(mid)` is `Optional.some(_)` for `mid < size`; the `Optional.none` arm is unreachable and the call to `unreachable` is dead code. The compiler emits no runtime check for this case.

PBT-verified props do **not** discharge `unreachable` (PBT is incomplete; a counterexample at runtime would convert dead-code elision into undefined behavior). Only statically-proven props gain this capability.

## 10. Inheritance through `extends`

When `cap A extends B`, every prop of `B` becomes an obligation on `A`'s satisfying types in addition to `A`'s own props. The full obligation set is the union of all transitively inherited props.

### 10.1 Simple inheritance (Rule P10.1)

```
cap NonEmpty extends Sequencing + Sized:
    prop non_empty: size > 0

cap Sorted of (Part is Ord) extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)

cap SortedNonEmpty of (Part is Ord) extends Sorted + NonEmpty
```

`SortedNonEmpty` inherits both `prop sorted` (from `Sorted`) and `prop non_empty` (from `NonEmpty`). A `T satisfies SortedNonEmpty` declaration must verify both.

### 10.2 Same-named props from different parents (Rule P10.2)

If two parents declare a prop with the same name (rare in practice), the child cap must redeclare the prop with a body that **strengthens** both parents' bodies — i.e., the child's prop must imply each parent's prop (covariance).

Failure to redeclare in the diamond case is `error[E104]: ambiguous prop <name> inherited from <P1> and <P2>`; the user resolves by adding an explicit redeclaration in the child cap.

### 10.3 Override semantics (Rule P10.3)

A child cap may redeclare a prop already present in a parent. The redeclaration must imply the parent's version (the child's body, conjuncted with the parent's body, must equal the child's body — i.e., the child strengthens):

```
cap Eq:
    prop eq_reflexive: forall (a: Self) -> a == a

cap StrictlyEq extends Eq:
    prop eq_reflexive: forall (a: Self, b: Self) ->
        a == b implies hash(a) == hash(b)
    // Override must imply parent — `eq_reflexive` here strengthens by adding a hash invariant
```

The typer verifies the override implies the parent. Failure is `error[E105]: prop <name> override does not imply parent`.

## 11. Worked examples

### 11.1 `examples/11_properties.fixed` — `Stack` invariants

```
cap Stack extends Sequencing + Sized:
    fn push(value: Part) -> Self
    fn pop -> Self

    prop push_increments: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop pop_decrements: forall (s: Self) suchThat s.size > 0 ->
        s.pop().size == s.size - 1

    prop push_pop_identity: forall (s: Self, x: Part) ->
        s.push(x).pop == s
```

Verification walk:

- `push_increments` — static prover: with `s` and `x` quantified, `s.push(x)` is a function call whose body is opaque to the prover. Likely `unknown`; PBT runs. Generator: pick a `Self` from a satisfying type's generator (e.g., a `List` or `Vec` that provides `push`); pick a `Part`; check the equality.
- `pop_decrements` — `suchThat s.size > 0` filters generated stacks at the PBT generator level. Empty stacks are discarded by the driver and never counted toward the iteration budget. The body is then evaluated only on non-empty stacks. Compare to a hypothetical `implies` formulation, where every generated stack would count toward the budget but only non-empty ones would actually exercise the property.
- `push_pop_identity` — the strongest invariant. PBT with the same generators verifies. If it passes, the optimiser may use this as a peephole: `s.push(x).pop()` ⇒ `s` (eliminating the round-trip).

### 11.2 `Bounded` data type

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi
```

Verification walk: each `Bounded.Bounded(v)` constructor call site triggers a verification check that `lo <= v <= hi` for the type's `lo` and `hi`. Per Rule Q5.3, `lo` and `hi` are quantity-0 of-params, so they are statically known at every constructor site. The static prover handles this: a single SMT query suffices. No PBT needed.

When verification succeeds, the runtime constructor is a plain assignment with no bounds check.

### 11.3 `fn -> cap` refinement caps

```
fn between(min: N is Ord, max: N) -> cap of N =
    prop in_range: min <= Self && Self <= max

fn parse_port(s: String) -> N is PortRange(1024, 49151) with Fail of String =
    let n = parse_u16(s)
    if n < 1024: Fail.fail("port too low")
    else if n > 49151: Fail.fail("port too high")
    else: n
```

`PortRange(1024, 49151)` expands (per parameterised type aliases) to a refinement cap with `prop in_range: 1024 <= Self && Self <= 49151`. The body's branches handle the violating cases by `Fail.fail` (which has type `!` and so satisfies any return type per §5.6 of type_system.md). The remaining `else: n` branch is verified statically: `n >= 1024 && n <= 49151` follows from the negated branch conditions.

## 12. Open questions

**Open (no decision yet):**

- **OQ-P1 — Static prover choice.** The static prover is treated abstractly in this doc; concrete choice (Z3, CVC5, custom Datalog/SMT hybrid, etc.) is implementation-defined. Decide before the bootstrap compiler is implemented; the choice constrains what props are statically provable vs PBT-deferred.
- **OQ-P2 — Custom generators.** Rule P5.3 requires every `forall` binder type to have a generator. Users may want to provide custom generators for domain types (e.g., a generator for "valid email strings"). Mechanism deferred — likely a `gen` declaration parallel to `satisfies` mapping a type to a generator function.
- **OQ-P3 — Per-prop verification annotations.** §8.3 mentions "per-prop annotation" for iteration count and timeout. Concrete syntax not yet specified; candidates include attribute-style markers (`@iterations(1000)`) or a structured `prop` modifier (`prop ... limits { iterations: 1000 }`). Defer until iterations matter in practice.
- **OQ-P4 — User-supplied static proofs.** When the prover reports `unknown` and PBT is unsuitable (e.g., the binder type has no generator), the user could supply an explicit proof. Mechanism deferred until needed.
- **OQ-P5 — `result` in non-postcondition contexts.** Rule P3.3 makes `result` an implicit binder only in fn-body postconditions. Whether `result` should also have meaning inside `cap`-method postconditions (where the cap has implicit return-typed methods) is open; defer until a use case appears.
- **OQ-P6 — Temporal / safety properties.** Properties about traces of operations (e.g., "every `acquire` is eventually followed by `release`") are out of scope for v0.4.3. Linear effects (`spec/effects.md` §3.5) provide a partial alternative for resource lifecycles. Revisit when language matures.

## 13. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | `PropDecl`, `PropExpr`, `ForallExpr`, `ImpliesExpr` syntax |
| `spec/type_system.md` | §5.3 (`Part`), §5.4 (`Self`), §6.4 (Marker class — refinement caps), §7.4.a (auto-derived `fold`); resolves OQ7 |
| `spec/quantities.md` | Rule Q5.5 (prop bodies at quantity 0), Q5.9 / Q5.10 (semiring usage) |
| `spec/effects.md` | Effects forbidden in props (§4.2 here); linear effects (§3.5 there) as resource-lifecycle alternative to OQ-P6 |
| `spec/pattern_matching.md` | Rule M6.2 (refinement-aided exhaustiveness) shares prop-evidence machinery |
| `spec/perceus.md` (TBD) | Verified props inform reuse decisions and bounds-check elision |
| `docs/references/scala3-compiler-reference.md` | Phase-indexed denotations (§4.4) — verified props annotate symbols |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `caps/PropVerifier.scala`; structured error reporting per the project-wide CLI guidelines |
