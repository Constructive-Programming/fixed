# Fixed Properties

**Status:** Draft v0.3.1
**Specifies:** `prop` declarations, `forall` / `implies` / `suchThat` constructs, the three verifiers (static prover, cost checker, property-based testing) and where each runs, verification statuses and the two lanes they feed, measurement during testing, property-based test generation, and how verified properties feed compiler decisions for Fixed v0.4.5 source.
**Last revised:** 2026-11-20

**Changes from v0.3.0.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): cost props skip the static prover and go to the cost checker (Rule P7.1); the viability of a refinement-capability candidate is a facts-lane decision (Rules P7.7.c, P7.8); a status is per (prop, satisfaction), and the reported status is the weakest over the satisfactions that ran (Rule P7.7.a); the failure report of a prop is printed by the diagnostic printer and is not a string literal (Rule P8.4); the test build keeps diagnostics (§4.2); "per satisfaction" replaces "per satisfying type".

**Changes from v0.2.3** (`docs/decisions/P6-resolution-round-2026-11-20.md`, D4, D6, D11, D12).
- **Three verifiers, placed where their inputs exist** (Rule P7.1, rewritten): the static prover on the typed tree right after the typer, the cost checker on Core, and property-based testing in the test build that `fixed verify` runs. A prop the package compile leaves undecided is *open*, which is not a status. `fixed compile` no longer runs property-based testing; the testing flags of Rule P7.6 belong to `fixed verify`.
- **A status is the weakest status of everything it rests on** (Rule P7.7). v0.2.3 let a `proved` prop rest on an inherited prop that was only `tested`, against Rule P7.7.c.
- **Two lanes** (new Rule P7.8). Decisions that are wrong if a claim is false take only `proved` and `assumed` props; decisions among correct alternatives take anything measured. This replaces v0.2.1's "only `proved` props are compiler assumptions": `assumed` props now enter the facts lane, and §9 and Rule P9.2 say so. In baseline code no facts-lane decision rests on a capability's prop whose satisfaction is not known there (Rule P7.8.c).
- **Measurement during testing** (new Rule P7.9): the test build records supply-side costs per (capability method, satisfaction, size class, target), machine facts and demand-side counters, for the profile file.
- **Binders range over satisfactions** (Rules P5.3, P5.3.b, P7.7.a), since a link unit may hold several satisfactions of one (type, capability).
- **Rule P7.4**: the cache key of a `tested` result includes the satisfactions it ran under, including the link-time bindings of the choice points it reaches; the content hash does not cover them.
- **Rule P8.4**: a counterexample is printed by the diagnostic printer (`spec/diagnostics.md` Rule DG3.4); values of unbounded type print as represented.
- Mechanical fixes from `docs/decisions/P0-spec-review-2026-11-20.md` §3: §3.4 no longer says other fn bodies carry postconditions; §2 no longer says this document resolves OQ7; Rule P4.2's reason no longer says prop bodies are checked at quantity 0; Rule P4.3 no longer calls a `prop` in an ordinary block a parse error; Rule P5.4 uses the grammar's nonterminals; Rule P5.3.b no longer credits Hypothesis and Hedgehog with sampling across satisfying types; the §10.3 example now implies its parent. The second "Rule P5.3" (generator availability) is renumbered Rule P5.3.d.

**Changes from v0.2.2.**
- **Cost props** (new Rule P3.5): a prop whose left side is a cost term states a cost contract (`spec/cost.md`). An asymptotic bound can be `proved` or `assumed`, never `tested`.
- **Prop bodies travel with a package** (§4.1): they are excluded from the binary but are part of the interface tier, because a package that satisfies an imported capability must verify its props (Rule P7.7.a).
- **Rule P5.3.c**: a test build may run a prop against the baseline version and a clone and compare.
- **Rule P7.4**: the cache key is the transitive content hash. The earlier key did not change when a function called by the prop body changed, so a `tested` result could be stale.
- **Rule P8.5**: a prop whose binders have few enough values is checked for all of them, and is then `proved`.
- New OQ-P7 (generators for types whose constructors are not public).

**Changes from v0.2.1.**
- **Verification status** (new Rule P7.7). Every accepted prop carries one of three statuses: `proved`, `tested` or `assumed`. `fixed verify` reports the count of each in one verdict line, and the new `--require-proved` flag rejects a build that has any prop below `proved`.
- **§9 restricted to `proved` props.** v0.2.1 let a prop accepted by PBT become a compiler assumption, and its examples (binary search, bounds-check elision, unchecked arithmetic) change program behaviour when the prop is false. That was unsound. Only `proved` props now license an optimisation whose correctness depends on the prop.

**Changes from v0.2.**
- **Function-result postconditions deferred** (re-opens OQ7 from `spec/type_system.md`). The §3.3 mechanism — a `prop` declaration at the top of a fn body, with `result` as an implicit binder for the return value — is removed from v0.4.5. Postconditions are reserved for a future release if user demand emerges. The grammar still admits `PropDecl` as an `Expr` alternative because refinement-cap fn bodies (§3.4) require it; the typer rejects `prop` declarations inside non-cap-returning fn bodies. OQ-P5 is moot under this deferral.
- See §3.3 below for the new (deferred) status note.

**Changes from v0.1.**
- `implies` is now **eager** (no automatic vacuous-truth discard in PBT) — it is plain propositional implication in both static and PBT contexts.
- New `suchThat` clause on `forall` (§5.4) is the dedicated **filter** for PBT: only values satisfying the `suchThat` expression count toward the iteration budget. This separates *logic* (`implies`) from *generator filtering* (`suchThat`) and makes test intent more explicit.
- §4.1 reframed: prop bodies are **excluded from the compiled artifact** rather than "checked at quantity 0". The semantic effect is the same; the framing accommodates PBT, which genuinely runs the prop body in a separate test build with normal quantity arithmetic.
- New §7.4 specifies a verification cache (deferred implementation) keyed on prop-body hash + obligation set.
- New Rule P7.6 specifies compiler flags for disabling PBT, treating PBT failure/timeout as errors, and overriding iteration count and time budget.
- All open questions (OQ-P1..P6) remain open.

## 1. Scope

This document specifies Fixed's `prop` system end-to-end: where invariants can be declared, how their bodies are typed, the strategy by which the compiler verifies them, what falls out as compiler optimization, and how `prop` interacts with `extends` inheritance. Concretely:

- The three contexts in which `prop` appears (§3): cap members, data members, and `fn -> cap` (refinement-cap) returns. Function-result postconditions are deferred to a future release (see §3.3).
- The expression sublanguage allowed inside a prop body (§4): quantity 0, no effects, `forall` and `implies` reachable here only.
- The `forall` quantifier (§5) and the `implies` connective (§6).
- The three verifiers and where each runs, the statuses they give, the two lanes that consume them, and measurement during testing (§7); the mechanics of generator derivation, shrinking, iteration, and counterexample reporting (§8).
- How props in the facts lane drive optimisation (§9), including `unreachable` discharge.
- How props compose through `extends` (§10).

OQ7 from `spec/type_system.md` (the implicit `result` postcondition binder) was provisionally resolved in v0.2 of this document and is **re-deferred in v0.2.1** along with the broader fn-result postcondition feature; see §3.3.

It does **not** specify:

- The choice of SMT solver, its integration interface, or its proof-format details. The static prover is treated abstractly; concrete tooling is implementation-defined (see OQ-P1).
- The PBT generator framework's full API. This document specifies what properties generators must satisfy and the failure-reporting protocol; the surface API (custom generators, shrinkers, etc.) is implementation-defined.
- Quantity inference for prop bodies — already specified in `spec/quantities.md` Rule Q5.5.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.3 — `PropDecl`, `PropExpr`, `ForallExpr`, `ImpliesExpr`, `Binder`.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), Rule 6.1 (default cap method bodies), §6.4 (Marker class — refinement caps), §7.4.a (auto-derived `fold` referenced in invariant bodies), Rule 8.4 (satisfactions and `using`), §11 OQ7 (deferred here, §3.3).
- `spec/quantities.md` v0.1 — Rule Q5.5 (prop bodies at quantity 0), Rule Q5.9/Q5.10 (semiring usage in expressions).
- `spec/effects.md` v0.2 — effects forbidden in prop bodies (§4.2 here).
- `spec/pattern_matching.md` v0.2 — match expressions are allowed inside prop bodies; refinement-aided exhaustiveness (Rule M6.2) and prop verification share the refinement-cap evidence machinery.
- `spec/compilation_model.md` — the interface tier and content hash (Rule CM7.1), choice points (Rule CM8.1), selection at link (Rule CM8.4), the profile file (Rule CM8.6), clones at link (Rule CM8.7).
- `spec/cost.md` — cost props, Rule CT6.2 (their statuses), Rule CT6.5 (their lane).
- `spec/reports.md` — counters (Rule RP3.3), the lock file and the profile file (Rule RP5.3).
- `spec/diagnostics.md` — Rule DG3.2 (sinks), Rule DG3.4 (the printed form).

Theoretical basis:

- Hoare, *"An Axiomatic Basis for Computer Programming"*, CACM 1969 — preconditions/postconditions; the deferred `result` binder (§3.3) is the standard postcondition pattern, retained for future reintroduction.
- Floyd, *"Assigning Meanings to Programs"*, AMS 1967 — verification conditions.
- Claessen & Hughes, *"QuickCheck: A Lightweight Tool for Random Testing of Haskell Programs"*, ICFP 2000 — the PBT model Fixed adopts for its testing verifier.
- MacIver et al., Hypothesis (Python) — the shrinker model used in §8.2.
- Filliâtre & Paskevich, *Why3* — the static-prover-driven model Fixed's verification pipeline broadly resembles.

## 3. Where `prop` declarations appear

A `PropDecl` per the grammar has the form:

```
prop NAME: BODY
```

where `BODY` is a `PropExpr` (extends `Expr` with `forall` and `implies`, per §5–§6). `prop` declarations are valid in **three** syntactic positions, each with slightly different semantics. (A fourth context — fn-result postconditions — is deferred to a future release; see §3.3.)

### 3.1 Cap members (Rule P3.1)

A `prop` declared inside a `cap` body is an **interface invariant**: every type that satisfies the cap must uphold the property. The typer adds the prop to the cap's obligation set; verification runs for every satisfaction `T satisfies C`, named or not (`spec/type_system.md` Rule 8.4).

```
cap Sorted of (Part is Ord) extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)
```

Implicit bindings inside the body:

- `Self` — the satisfying type (any `T` with `T satisfies Sorted`).
- `Part` — the cap's first non-phantom type parameter (per type_system.md §5.3).
- Cap members declared above the prop — the prop body may call them.

### 3.2 Data members (Rule P3.2)

A `prop` declared inside a `data` body is a **data invariant**: every value of the data type must uphold the property. The static prover verifies the invariant at every constructor call site, right after the typer (Rule P7.1); a runtime check is elided where the invariant is in the facts lane (Rule P7.8).

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi
```

Implicit bindings inside the body:

- `Self` — the data type itself (with all `of` arguments fixed).
- The data's `of` parameters (e.g., `lo`, `hi` above) — accessible as compile-time constants per Rule Q6.2.
- Constructor variants and their fields (via `forall` quantification: `forall (b: Self) -> ...`).

### 3.3 Function-result postconditions — **deferred (was Rule P3.3)**

> **Status: deferred.** Function-result postconditions — `prop` declarations at the top of a fn body that use the implicit `result` binder for the return value — are not part of v0.4.5. They are reserved for a future release if user demand emerges.
>
> **Rationale.** The feature requires committing to (a) an implicit reserved-in-context identifier (`result`), (b) per-fn-call discharge of postcondition obligations at every call site, and (c) integration with the static prover for fn-call summaries. Each is independently a sizable spec + implementation surface. Without concrete user demand we prefer to ship invariants on caps and data (§3.1, §3.2) and refinement caps (§3.4), which already cover the common verification use cases via type-level obligations rather than fn-result postconditions.
>
> **Typer behaviour.** A `prop` declaration appearing at the top of a non-cap-returning fn body is a compile error: `error[E???]: function-result postconditions are deferred. Express the property as an invariant on a result type (e.g. `is Sorted`, `cap of A`) instead.`
>
> **Reintroduction path.** When this is re-enabled, the v0.2 specification is the starting point: the `result` identifier becomes contextually reserved inside fn-body prop declarations; a multi-prop fn body lists postconditions as separate `prop` declarations; static-prover discharge proceeds against the fn body's symbolic execution. See `docs/decisions/X6-result-binder.md` for the design space considered.

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

The body of a `fn -> cap` is the *only* fn body that consists of `prop` declarations — a refinement cap's body is, in effect, a list of obligations, and it produces no value. Every other fn body ends in a value-producing expression per Rule M9.1's logic and may not contain a `prop` (§3.3).

### 3.5 Cost props (Rule P3.5)

A prop in a `cap` or `data` declaration whose body is a comparison with a cost term on the left (`steps(m)`, `constructs(m)`, `releases(m)`, or `constructs(m) - releases(m)`, for a member `m` of the declaration) is a **cost prop**. It states a cost contract, defined in `spec/cost.md` §4.

A cost prop is a prop in every other respect: it is excluded from the compiled artifact (§4.1), inherited through `extends` (§10), verified per satisfaction, and given a status (Rule P7.7). Two things differ:

- Its body is not evaluated as an expression. `steps`, `constructs`, `releases`, `size`, `O`, `log` and `max` denote the cost vocabulary of `spec/cost.md` Rules CT4.1 and CT4.2, and the bound is compared with the member's inferred cost (`spec/cost.md` §6).
- A bound written with `O(…)` cannot be refuted by testing, so it is `proved`, rejected because the inferred cost exceeds it, or `assumed`; it is never `tested` (`spec/cost.md` Rule CT6.2). An exact bound can be `tested`: the test build counts the member's steps and constructions and compares. A `tested` cost prop is a measurement. Every cost prop, whatever its status, is used only in the measurement lane (Rule P7.8.b; `spec/cost.md` Rule CT6.5).

## 4. Prop body expressions

### 4.1 Prop bodies are excluded from the compiled artifact (recap)

Per `spec/quantities.md` Rule Q5.5 (v0.2), prop bodies are **not included in the compiled artifact** (the program binary). References to bindings inside a prop body therefore do not contribute to the binding's runtime quantity in the artifact — if a binding is used only inside props, it has quantity 0 in the artifact and is erased.

Property verification, when it requires running the body (step 4 of Rule P7.1), compiles the body separately into a **test build** — a distinct compilation context from the program binary. Inside the test build, bindings have their normal quantities determined by all uses (the body genuinely runs at test time). The test build is not part of the production binary; it is the artifact a `fixed verify` invocation produces, separate from `fixed compile`. It is a link unit of its own: its link step binds the choice points the prop bodies reach (`spec/compilation_model.md` Rule CM8.4), and may bind them differently from an application (Rule P7.4).

**Excluded from the binary, present in the interface.** A package's prop bodies are part of its interface tier, stored as typed expressions (`spec/compilation_model.md` Rule CM7.2). A package that declares a satisfaction for an imported capability needs them to verify the capability's props for its own type (Rule P7.7.a). The code a prop body calls is reached by linking against the declaring package; its bodies are not needed.

**What the test build compiles.** The test build compiles the same baseline versions as the program and, unless `--no-specialise` is given, the same clones (`spec/compilation_model.md` §5), each with region counters (`spec/reports.md` Rule RP3.3) and the measurement of Rule P7.9.

### 4.2 No effects (Rule P4.2)

A prop body's effect row must be **empty**. Calls to effect operations (`Console.print_line`, `Fail.fail`, etc.) are forbidden inside prop bodies: `error[E100]: effect performed in prop body`.

A prop body is a statement about values, and its truth must not depend on a handler: the static prover sees no handler, and the program binary does not contain the body at all (§4.1), so an effect in it would have no meaning in either place. The typer enforces effect-row emptiness as a separate check for clearer error messages.

`inspect` and `trace` are not effects (`spec/diagnostics.md` Rule DG3.3) and are allowed in a prop body. The static prover reads `inspect(e)` as `e` and `trace(…)` as `()`; the test build keeps them (`spec/diagnostics.md` Rule DG3.5).

### 4.3 `forall` and `implies` are reachable only inside prop bodies (Rule P4.3)

The grammar's `PropExpr` extends `Expr` with `ForallExpr` and `ImpliesExpr`, and only `PropDecl` reaches `PropExpr`. A bare `forall` in an ordinary expression (`if forall (x: T) -> …:`) is a parse error. A `prop` declaration does parse in any block, because the grammar admits `PropDecl` as a block item for refinement-cap bodies (§3.4); the typer rejects one outside a `cap`, a `data` or a refinement-cap body (§3.3).

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

Match cases inside prop bodies are checked at quantity 0, but the same exhaustiveness rules (Rule M6.1, Rule M6.2) apply — props must be total expressions producing a `bool`.

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
4. It is a capability bound `is C` such that at least one satisfaction of `C` in the test build's link unit is for a type that has a generator.

Refinement caps (Marker class) restrict an underlying generator. `between(0, 10)` constrains an integer generator to `{0..10}`.

**Rule P5.3.a (Refinement-narrowed binders) — resolves X2.** When a `forall` binder's type combines a base capability with one or more refinement caps (e.g., `forall (n: u64 + between(0, 10))` or `forall (xs: is Sequencing of (N is Numeric))` where the base is `Sequencing of N` and `N is Numeric` further refines `N`), the generator is constructed compositionally:

1. Generate inhabitants from the **base**: the base type's generator under (1)–(4) above.
2. **Filter** by the refinement caps' prop bodies, using the same `suchThat`-style filter semantics as Rule P5.4.c. Each refinement cap's prop body becomes a generator-side filter; values failing the prop are discarded toward the discard-rate guard (Rule P5.4.d).

A refinement-only binder type (a `forall` binder whose type is *only* refinement caps with no base) is a verification error: `error[E101a]: cannot verify <prop> — refinement-only binder type has no underlying generator`. The user must add a base capability or primitive type to the binder's bound set.

**Rule P5.3.b (Polymorphic binders — sampling across satisfactions) — resolves L3.** When a `forall` binder's type is `is C of (...)` and the test build's link unit holds several satisfactions of `C` (case 4 above with N > 1), the PBT runtime **samples** across the satisfactions rather than enumerating their cross-product. The binder ranges over **satisfactions, not types**: a link unit may hold several satisfactions of one (type, capability) (`spec/type_system.md` Rule 8.4), they can differ in results, and each is a separate sample point with its own status (Rule P7.7.a). The default sampling strategy is *uniform* across the satisfactions: each iteration picks one at random with equal probability, then generates a value of its type, operated on through that satisfaction.

For nested polymorphic binders (e.g., `is Sequencing of (N is Numeric)` with N satisfactions for `Sequencing` and M satisfactions for `Numeric`), the cross-product is `N × M` combinations of satisfactions; uniform sampling picks one such combination per iteration. The total iteration budget (Rule P7.6) is applied to *iterations*, not to satisfaction-pairs — so increasing the satisfaction count thins the per-pair coverage.

Users seeking deterministic per-pair coverage can name one satisfaction with `using` on the binder type (`spec/type_system.md` Rule 8.4), or provide a custom generator when OQ-P2 is resolved. The coverage report shows how many iterations each combination received (`spec/reports.md` Rule RP3.4). While it samples, the driver measures (Rule P7.9).

**Rule P5.3.c (Baseline against clone).** A clone of a function must behave as its baseline version (`spec/compilation_model.md` Rule CM5.2). Where the code a prop body calls has clones, a test build may evaluate the prop body twice per generated input — once calling baseline versions only, once calling the clones the program would call — and compare the two results. A difference is a compiler fault, reported as an internal error with the input, not a failed prop. This is a comparison of two results on one input, not a second sampling dimension: it does not divide the iteration budget.

A future weighting hint (`@sampling(weights = ...)` or similar attribute) is anticipated for cases where one satisfaction matters more than others — deferred until use cases motivate it.

**Rule P5.3.d (Generator availability).** If a `forall` binder's type has no generator and the static prover left the prop open, `fixed verify` halts: `error[E101]: cannot verify <prop> — no generator for <type>`. The user resolves by providing a custom generator (mechanism deferred — see OQ-P2) or by restricting the binder's domain via a refinement cap.

### 5.4 `suchThat` (Rule P5.4)

A `forall` quantifier may carry an optional `suchThat` clause that filters generated values:

```
ForallExpr     = "forall" "(" Binder ( "," Binder )* ","? ")" SuchThatClause? "->" PropExpr
SuchThatClause = "suchThat" Expr
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

Property verification is three things, each placed where its input exists. The static prover reads the typed tree, the cost checker reads Core, and property-based testing runs code, so it needs a build of its own.

### 7.1 Three verifiers (Rule P7.1)

| Verifier | Input | Runs in | Results |
|---|---|---|---|
| **Static prover** | the typed tree | the package compile, right after the typer and before quantity inference | props other than cost props: `proved`; falsified (`E102`); open |
| **Cost checker** | the Core of baseline versions | the package compile, after demand and memory inference (`spec/cost.md` Rule CT6.2) | cost props only: `proved`; failed (`E120`); open |
| **Property-based testing** | the test build (§4.1) | `fixed verify` | `tested`; `proved` by enumeration (Rule P8.5); counterexample (`E102`) |

For each prop:

1. **Static proof.** The static prover (choice deferred to OQ-P1) gets every prop that is not a cost prop, with a bounded time budget (default 5 seconds per prop, configurable). `proved` gives the status `proved`. A counterexample is `error[E102]: prop failed: <name>`, reported by Rule P8.4.
2. **Cost check.** A cost prop skips the static prover. The cost checker decides it on Core and makes its own solver call (`spec/cost.md` Rule CT6.2, step 1).
3. **Open.** A prop that steps 1 and 2 leave undecided is **open** when the package compile ends. Open is not a status: an open prop is not a fact (Rule P7.8), and `fixed compile` reports the number of open props.
4. **Testing.** The test build runs property-based testing (§8) on every open prop for at least 100 non-discarded iterations. A counterexample is `E102`. Otherwise the prop is `tested`, or `proved` when Rule P8.5 enumerated every value. A cost prop with an asymptotic bound is not tested (`spec/cost.md` Rule CT6.2) and becomes `assumed`.
5. **No testing.** With `fixed verify --no-pbt` step 4 is skipped and every open prop becomes `assumed` (`W107`).

The prover runs before quantity inference because quantity inference and the memory passes make facts-lane decisions (a fork site, `spec/quantities.md` Rule Q5.13.a; reuse justified by a prop) that need `proved` statuses as input. `fixed verify` records the results of steps 4 and 5 in the package's interface (`spec/compilation_model.md` Rule CM7.1). Recording them changes no code of the package: what the package compile decided, it decided from the statuses it had (Rule P7.8.c).

### 7.2 Static proof obligations (Rule P7.2)

The static prover sees:

- The prop body, with `inspect(e)` read as `e` and `trace(…)` as `()` (`spec/diagnostics.md` Rule DG3.3).
- Free bindings: `Self`, `Part`, fn parameters, data-`of`-params (per §3.2). (`result` is reserved for the deferred postcondition feature; see §3.3.)
- Type information for every binding.
- The props of inherited caps (per `extends`, §10) and of the caps that bound its binders, as **premises**, each with its status.

The prover does **not** see effect operations, runtime values, or the body of fn calls (these are typically opaque to first-order reasoning). A prop body referencing fn calls without static-evaluable outcomes typically stays open, and testing decides it. Because the prover sees no bodies, a `proved` result does not depend on which satisfaction a choice point is bound to at link.

### 7.3 Testing details

See §8 for generator/shrinking/iteration mechanics. Key invariant: a counterexample is a hard error; no counterexample after the iterations gives `tested`, which is weaker than proof (testing is incomplete: a false prop can pass, a true prop cannot fail).

### 7.4 Verification caching (Rule P7.4 — implementation guidance)

Property verification can be expensive — SMT calls and PBT iterations scale with prop count and complexity. The compiler **should cache** per-prop verification results across invocations.

The cache key for each prop is the tuple of:

- The **content hash** of the prop (`spec/compilation_model.md` Rule CM7.1): a canonical hash (Blake3 or equivalent) of the prop body's typed expression and, transitively, of everything its meaning depends on — the functions it calls and the functions those call, the satisfactions it resolves, the generators of its binder types, refinement caps applied to binder types, and inherited props (via `extends`).
- For a `tested` result, the **satisfactions it ran under**: the satisfaction of each polymorphic binder (Rule P5.3.b) and the binding of every choice point (`spec/compilation_model.md` Rule CM8.1) that the prop body reaches, as the test build's link bound it (Rule CM8.4). The content hash does not cover these, because a choice point is bound after the package is compiled, and the code the prop runs differs with the binding.
- The configured PBT iteration count and time budget (Rule P7.6 below).
- The prover version / SMT solver version (so prover upgrades invalidate cache).

The hash is transitive on purpose. A `tested` result depends on the bodies of the functions the prop calls, so a key over the prop body alone would return a stale result after one of them changed. A `proved` result does not depend on those bodies or on the bindings (Rule P7.2), so the transitive key invalidates it more often than necessary; an implementation may keep a narrower key for `proved` results.

The interface records a `tested` status together with the bindings it ran under. An application whose link binds one of those choice points to another satisfaction has no test result for the prop under its own binding: the link report (`spec/reports.md` Rule RP5.4) shows the prop as tested under another binding, until a test build of the application tests it. This does not change any decision, because a `tested` status never enters the facts lane (Rule P7.8).

A cached result stores the prop's verification status (Rule P7.7). A cache hit skips both static proof and PBT entirely. A cache miss runs the full verification. Cache invalidation occurs when any key component changes. The cache never supplies a status to a facts-lane decision of the package compile (Rule P7.8.c); otherwise the compiled code would depend on whether the cache existed.

This rule is **implementation guidance**, not a normative requirement: a conformant compiler may run verification fresh every time. Caching is strongly recommended for development ergonomics — without it, every full verification re-runs PBT iterations on every prop, which is unacceptably slow at scale.

Implementation deferred. A reasonable v1 cache is a per-project file-system store at `.fixed/cache/props/`, with one entry per (key, result) pair.

### 7.5 Interaction with `unreachable`

A prop in the facts lane (Rule P7.8) may discharge `unreachable` calls in code paths that the prop excludes. See §9.

### 7.6 Compiler flags (Rule P7.6)

The user controls verification behavior via the following flags. The testing flags apply to `fixed verify`, which builds and runs the test build; `fixed compile` and `fixed ship` run only the static prover and the cost checker, and accept `--require-proved`, `--no-prop-cache` and the prover budget.

| Flag | Effect |
|---|---|
| `--no-pbt` | **Disable PBT entirely.** Open props become `assumed` with `warning[W107]: prop <name> not statically verified; PBT disabled`. An `assumed` prop enters the facts lane of importers (Rule P7.8), so this flag is a decision to trust the open props, not only a way to save time. |
| `--strict-pbt` | Treat PBT *failure* AND *timeout* as errors (the default treats timeout as `warning[W103]`). Useful for release builds where any unverified prop should block. |
| `--prop-iterations=N` | Override default PBT iteration count (default: 100 non-discarded cases per prop). |
| `--prop-timeout=Ts` | Override default total time budget per prop (default: 30s). |
| `--prop-discard-threshold=R` | Override the discard-rate guard (Rule P5.4.d, default 0.9 — i.e., 90%). |
| `--no-measure` | Run the props without recording measurements (Rule P7.9). |
| `--no-prop-cache` | Disable the verification cache (Rule P7.4). Forces fresh verification on every invocation. Useful for debugging cache invalidation issues. |
| `--require-proved` | Treat every prop whose status is `tested` or `assumed` (Rule P7.7), and with `fixed compile` every open prop, as `error[E106]: prop <name> is not proved (status: <status>)`. |

`--no-pbt` is mutually exclusive with `--strict-pbt`; combining them is a CLI usage error.

When `--no-pbt` is set, props requiring PBT are not verified. The compiler emits W107 warnings for each. The compiled artifact still excludes prop bodies (per §4.1) — the only effect is that the *verification step* is skipped and its props are recorded as `assumed`.

### 7.7 Verification status (Rule P7.7)

Every accepted prop has exactly one **verification status**:

| Status | How a prop gets it | What it guarantees |
|---|---|---|
| `proved` | The static prover reported `proved`, or the test build enumerated every value (Rule P8.5); and everything it rests on is `proved`. | The prop holds for every value of its binders. |
| `tested` | Testing ran without a counterexample. The record keeps the number of non-discarded iterations, including the reduced count of a `W103` budget overrun, and the satisfactions it ran under (Rule P7.4). | No counterexample among the generated inputs. The prop may still be false. |
| `assumed` | Nobody checked the prop: `--no-pbt` left it open (`W107`), or it is an asymptotic cost bound that the cost checker could not decide (`spec/cost.md` Rule CT6.2). | Nothing. Whoever ran the build accepted it. |

A prop that fails (Rule P7.1) is an error and has no status. A prop still open after the package compile has no status yet (Rule P7.1, step 3).

By what they guarantee the statuses are ordered `assumed` < `tested` < `proved`. **The status of a prop is the weakest status of everything it rests on**: its own check, and every premise the prover used (Rule P7.2). A proof whose premise is an inherited prop that is only `tested` for this satisfaction gives `tested`; a proof whose premise is `assumed` gives `assumed`. A proof never makes a prop stronger than its premises.

**Rule P7.7.a (inheritance and satisfactions).** A prop inherited through `extends` (§10) is verified per satisfaction (`spec/type_system.md` Rule 8.4), so its status is recorded per (prop, satisfaction) pair. A link unit may hold several satisfactions of one (type, capability); each is its own pair. An override (Rule P10.3) has its own status; proving the override proves the parent's version, because the override implies it.

The pair is verified in the package that declares the satisfaction, which may not be the package that declares the capability. The status is recorded in that package's interface (`spec/compilation_model.md` Rule CM7.1). An importer takes a recorded status as it stands and does not verify the prop again.

A satisfaction that received no run for a prop (one that sampling never picked, Rule P5.3.b, or one added after the run) is open for that prop. The status reported for the prop as a whole is the weakest over the satisfactions that were run, and the report (Rule P7.7.b) lists the satisfactions that were not. `--require-proved` applies to the props verified in the package being built; the verdict line of Rule P7.7.b counts the statuses recorded by dependencies separately.

**Rule P7.7.b (verdict).** `fixed verify` ends with one verdict line giving the count of each status and of failures:

```
props: 41 proved, 6 tested, 1 assumed, 0 failed
```

With `--format json` the same counts appear together with one record per prop: name, location, status and, for `tested`, the iteration count and the satisfactions it ran under. The exit code is `2` when any prop failed, or when `--require-proved` is set and any prop is `tested` or `assumed`.

**Rule P7.7.c (what a status licenses).** A status decides which lane a prop may enter (Rule P7.8): `proved` and `assumed` props enter the facts lane, `tested` props do not. Every accepted prop, whatever its status, is still an obligation on every satisfaction. A refinement relies on its props, since later code takes them as true of the value; so a refinement-cap candidate is viable only by `proved` and `assumed` props (`spec/type_system.md` §6.5.3), a facts-lane decision (Rule P7.8.a).

### 7.8 Two lanes (Rule P7.8)

A decision the compiler makes from a prop belongs to one of two lanes.

**Rule P7.8.a (facts lane).** A decision that is **wrong if the claim is false** takes only `proved` and `assumed` props. `tested` never enters. The decisions in this lane are:

- reuse or in-place update justified by a prop;
- discharge of `unreachable` (Rule P9.2) and every other optimisation of §9.1;
- a parallel fork site that relies on a prop (`spec/quantities.md` Rule Q5.13.a);
- the viability of a candidate by refinement caps (`spec/type_system.md` §6.5.3).

A `tested` prop is excluded because it looks like evidence while guaranteeing nothing: nobody chose to trust it, and a false one would turn a passing test suite into undefined behaviour without anyone having decided anything. An `assumed` prop is admitted because it is an explicit, visible act of trust. It is the only way a property that no prover and no generator can reach (OQ-P4) can drive an optimisation; whoever ran the build accepted it (`--no-pbt`, Rule P7.6); the verdict line counts it; the link report lists the decisions that rest on it; and `--require-proved` rejects it.

**Rule P7.8.b (measurement lane).** A decision **among alternatives that are all correct** takes anything measured while code runs, and every status. The decisions in this lane are candidate selection (`spec/cost.md` §8), clone weights (`spec/compilation_model.md` Rule CM5.4), the clone budget (Rule CM5.3), fork granularity, and the viability of a candidate by cost contracts. A wrong input makes the program slower, not wrong. Cost props are used only here (`spec/cost.md` Rule CT6.5).

**Rule P7.8.c (which statuses a decision can see).** A facts-lane decision uses the statuses known where it is made:

- In the **package compile**: `proved` from this compile's static prover, and the statuses recorded in the interfaces of dependencies. Not statuses from this package's own test build, from the verification cache, or from a profile; the compiled code would otherwise depend on whether `fixed verify` had run.
- **Premises across a satisfaction.** A prop of a capability holds for a value with the status of the value's satisfaction, which can differ per satisfaction. Where the satisfaction is not known at the decision (a value of a type parameter or of a choice point in baseline code), a facts-lane decision must not rest on that capability's props, because the code must be correct for every satisfaction, including ones added later. It may in a clone for a known type, and in a clone built at link (`spec/compilation_model.md` Rule CM8.7), using the status the chosen satisfaction's package recorded.

### 7.9 Measurement during testing (Rule P7.9)

The test build already runs each prop with a polymorphic binder across the satisfactions in its link unit (Rule P5.3.b). While it does, it measures.

**Rule P7.9.a (supply side).** Per (capability method, satisfaction, size class, target), the driver records `steps`, `constructs`, `releases`, cycles, cache misses and branch misses for each call it makes. A **size class** is the size of the receiver (`spec/cost.md` Rule CT5.1) rounded down to a power of two. With them it records the **machine facts** of the target: cache sizes, cache line size, page size and allocator granularity.

**Rule P7.9.b (demand side).** The same run records the region, request and usage counters of `spec/reports.md` Rule RP3.3.

**Rule P7.9.c (where the data goes).** Measurements go to the profile file of the target (`spec/compilation_model.md` Rule CM8.6), each record labelled with its source (test run) and its target. A build reads the profile file and never measures (`spec/reports.md` Rule RP5.3).

**Rule P7.9.d (priority of sources).** For the demand side the selector takes the first source that exists: a **production profile** (counters from an instrumented build run on real inputs), then **test-run usage**, then the **static weights** of the summaries (`spec/compilation_model.md` Rule CM8.3). For the supply side a package may ship a **reference cost table** in its interface, measured by its author and labelled with the target; the application may **re-measure** on its own target, and its own measurement takes precedence. Continuous integration builds from the lock file and does not re-measure.

**Rule P7.9.e (known weaknesses).** The data is used only in the measurement lane (Rule P7.8.b) because of these:

- Generator inputs are not production inputs. A generator favours small and boundary values; usage counted on them can rank methods differently from real use. A production profile therefore outranks a test run.
- Measurements are noisy: cycles and cache misses vary between runs and with machine load. The lock file's hysteresis (`spec/type_system.md` Rule 6.5.5) keeps noise from flipping a choice.
- Cross-compilation: a profile measured on one target says little about another. A profile carries its target label; a build whose target differs ignores it and uses static data (`spec/cost.md` Rule CT8.2).

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
| `is C` | pick a satisfaction in the test build's link unit whose type has a generator (Rule P5.3.b); defer to that |

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

If the time budget elapses before `N` non-discarded cases, the driver warns: `warning[W103]: prop <name> verified by N′ iterations (budget exceeded)`. The prop is accepted but the user is informed.

### 8.4 Counterexample reporting (Rule P8.4)

When a counterexample is found, the error message includes:

- The prop name and source location.
- The shrunken counterexample input — concrete values for every binder, and for a polymorphic binder the satisfaction it ran under. The failure report is not a string literal: the test driver prints it with the diagnostic printer, values as represented by `spec/diagnostics.md` Rule DG3.4, so a value whose type is an unbounded parameter needs no `Show`, and a choice-point value shows its chosen type.
- A copy-pasteable snippet that the user can place in a test file to reproduce the failure deterministically.
- A suggestion to either fix the prop or fix the implementation that violated it.

The format of this error message follows the project-wide "Agent-Friendly CLI and Compiler Output" guidelines from `docs/plans/implementation-plan.md` — structured (parseable as JSON via `--format json`), with explicit error codes.

### 8.5 Enumeration (Rule P8.5)

When every binder of a prop has a finite number of values and the product of those numbers (`spec/reports.md` Rule RP4.1) is no larger than the iteration budget, the driver evaluates the prop for **every** combination instead of sampling. Values excluded by `suchThat` are skipped. If no combination fails, the prop holds for every value of its binders, and its status is `proved`.

For a polymorphic binder (Rule P5.3.b) enumeration is per combination of satisfactions: a combination whose values were all checked is `proved` for that combination, and the status is recorded per (prop, satisfaction) pair as Rule P7.7.a already requires. A satisfaction another package adds later is a new pair with its own status.

Enumeration runs in the test build, after the package compile. A `proved` status obtained this way is recorded in the interface and reaches the facts lane of importers, not of the package compile it followed (Rule P7.8.c).

## 9. Props in the facts lane

A prop with status `proved` or `assumed` (Rule P7.7) is in the **facts lane** (Rule P7.8) and becomes a **compiler assumption**, within the limits of Rule P7.8.c. This unlocks optimisations:

### 9.1 Examples (informational)

| Prop | Optimisation enabled |
|---|---|
| `prop sorted: ...` on a `Sequencing` | `find(x)` lowers to binary search rather than linear scan. |
| `prop non_empty: size > 0` | `head` returns `Part` directly instead of `is Optional of Part`; `fold`'s empty case is unreachable and can be elided. |
| `prop in_range: lo <= value <= hi` (Bounded) | Bounds checks at use sites are elided; arithmetic that would have required checked overflow uses unchecked. |
| `prop positive: Self > 0` | `0 - n` becomes guaranteed-non-negative; `if n > 0` branches compile to unconditional. |

The mechanism: each prop in the facts lane annotates the symbol's denotation (per `docs/references/scala3-compiler-reference.md` §4.4) with a *fact* the optimiser may consult. A missing fact is simply a missed optimisation.

Every optimisation in the table changes what the program does when the prop is false: binary search over an unsorted sequence returns a wrong answer, and an elided bounds check reads out of range. They are therefore facts-lane decisions, available for `proved` and `assumed` props only. A `tested` prop produces no fact. The compiler may use it only in the measurement lane, for a choice that leaves behaviour unchanged when the prop is false, such as a scoring hint in representation selection.

### 9.2 `unreachable` discharge (Rule P9.2)

A prop in the facts lane may discharge `unreachable` calls. If the prop excludes the value-domain that would reach `unreachable`, the optimiser can elide the unreachable branch entirely:

```
fn median(collection: C is SortedNonEmpty of (N is Numeric)) -> N =
    let mid = collection.size / 2
    collection.get(mid).fold(
        (v) -> v,
        () -> unreachable,    // statically discharged: NonEmpty proves get(mid) is some
    )
```

Per `prop non_empty: size > 0` on `NonEmpty`, the typer proves `collection.get(mid)` is `Optional.some(_)` for `mid < size`; the `Optional.none` case is unreachable and the call to `unreachable` is dead code. The compiler emits no runtime check for this case.

`tested` props do **not** discharge `unreachable` (testing is incomplete; a counterexample at runtime would convert dead-code elision into undefined behavior). `proved` and `assumed` props do, as for every other assumption in this section; an `assumed` prop that is false makes the discharge wrong on the responsibility of whoever accepted it (Rule P7.8.a). A `NonEmpty` bound on a type parameter in baseline code does not discharge it unless the prop holds for every satisfaction (Rule P7.8.c); in a clone for a known type it does.

## 10. Inheritance through `extends`

When `cap A extends B`, every prop of `B` becomes an obligation on every satisfaction of `A` in addition to `A`'s own props. The full obligation set is the union of all transitively inherited props.

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
        a == a && (a == b implies hash(a) == hash(b))
    // Override implies parent: its first conjunct is the parent's body
```

The static prover verifies that the override implies the parent. Failure is `error[E105]: prop <name> override does not imply parent`.

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
- `push_pop_identity` — the strongest invariant. PBT with the same generators verifies, giving the prop status `tested`. The peephole `s.push(x).pop()` ⇒ `s` (eliminating the round-trip) is a facts-lane decision (Rule P7.8.a), so it is not applied on the strength of PBT alone.

### 11.2 `Bounded` data type

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi
```

Verification walk: each `Bounded.Bounded(v)` constructor call site triggers a verification check that `lo <= v <= hi` for the type's `lo` and `hi`. Per Rule Q5.3, `lo` and `hi` are quantity-0 of-params, so they are statically known at every constructor site. The static prover handles this in the package compile: a single SMT query suffices. No PBT needed.

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
- **OQ-P5 — `result` in non-postcondition contexts.** Moot in v0.2.1 — fn-body postconditions themselves are deferred (§3.3). When postconditions are re-enabled, the question of `result` in `cap`-method postconditions resurfaces and OQ-P5 will be revisited.
- **OQ-P7 — Generators for types whose constructors are not public.** Rule P8.1 derives a generator for a `data` type from its fields. An importer sees the variants of a `pub data` type (`spec/compilation_model.md` Rule CM6.7). Declarations are private by default (P6, D13), and a private `data` type crosses a package edge only as a choice-point value of a public capability; its package must export a generator for it, or an importer cannot test props over it.
- **OQ-P8 — Explicit and implicit `assumed`.** Rule P7.8.a admits `assumed` to the facts lane as an act of trust, but `assumed` also arises without one: from `--no-pbt`, a flag a user may pass to save time, and from an asymptotic cost bound the checker could not decide. Under Rule P7.8, `--no-pbt` turns a prop that testing would have left out of the facts lane, or refuted, into a fact for importers. Splitting the status into an explicit `trusted` (a user-written assumption, OQ-P4) that enters the facts lane and an `unchecked` that does not would remove this; it needs a decision.
- **OQ-P9 — Facts on capability props in baseline code.** Rule P7.8.c forbids, in baseline code, a facts-lane decision resting on a capability's prop whose satisfaction is unknown there. The alternative is to record the props such decisions rest on in the summaries (`spec/compilation_model.md` Rule CM8.3) and have the link step make a satisfaction whose status for one of them is `tested` not viable at that choice point; for a named type parameter the caller would check its type argument. It would keep the optimisations in baseline code at the price of a link-time and call-site check.
- **OQ-P6 — Temporal / safety properties.** Properties about traces of operations (e.g., "every `acquire` is eventually followed by `release`") are out of scope for v0.4.3. Linear effects (`spec/effects.md` §3.5) provide a partial alternative for resource lifecycles. Revisit when language matures.

## 13. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | `PropDecl`, `PropExpr`, `ForallExpr`, `ImpliesExpr` syntax |
| `spec/type_system.md` | §5.3 (`Part`), §5.4 (`Self`), §6.4 (Marker class — refinement caps), §7.4.a (auto-derived `fold`); Rule 8.4 (satisfactions and `using`) behind Rules P5.3.b and P7.7.a; Rule 6.5.5 (the lock file's hysteresis) behind Rule P7.9.e; OQ7 deferred per §3.3 |
| `spec/quantities.md` | Rule Q5.5 (prop bodies at quantity 0), Q5.9 / Q5.10 (semiring usage); fork sites (Rule Q5.13.a) are facts-lane decisions (Rule P7.8.a) |
| `spec/cost.md` | Cost props (Rule P3.5): the contract language, inference, the statuses an asymptotic bound can have, and their lane (Rule CT6.5); measured costs in selection (Rule CT8.2) |
| `spec/compilation_model.md` | Prop bodies in the interface tier (Rule CM7.2); the content hash (Rule CM7.1) behind Rule P7.4; Rule CM5.2 behind Rule P5.3.c; choice points and their binding at link (Rules CM8.1, CM8.4) behind Rule P7.4; summaries (Rule CM8.3); the profile file (Rule CM8.6) behind Rule P7.9; clones at link (Rule CM8.7) behind Rule P7.8.c |
| `spec/reports.md` | Coverage statuses built on Rule P7.7; enumeration (Rule P8.5) uses the cardinality count; counters (Rule RP3.3) are the demand side of Rule P7.9; the lock file and the profile file (Rule RP5.3); the link report (Rule RP5.4) |
| `spec/diagnostics.md` | The failure report of a prop is a diagnostic sink (Rule DG3.2) and prints by Rule DG3.4; the prover ignores diagnostics (Rule DG3.3) |
| `spec/effects.md` | Effects forbidden in props (§4.2 here); linear effects (§3.5 there) as resource-lifecycle alternative to OQ-P6 |
| `spec/pattern_matching.md` | Rule M6.2 (refinement-aided exhaustiveness) shares prop-evidence machinery |
| `spec/memory_management.md` | Props in the facts lane (Rule P7.8) inform reuse decisions and bounds-check elision |
| `docs/references/scala3-compiler-reference.md` | Phase-indexed denotations (§4.4) — verified props annotate symbols |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `caps/PropVerifier.scala`; structured error reporting per the project-wide CLI guidelines |
