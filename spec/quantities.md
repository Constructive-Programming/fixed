# Fixed Quantities (QTT)

**Status:** Draft v0.6.2
**Specifies:** quantity and demand inference for Fixed v0.4.4 source.
**Last revised:** 2026-11-20

**Changes from v0.6.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). Rule Q5.13.a: fork site 3 takes facts-lane props (`proved` or `assumed`); a closure carries a bit for captured evidence, and a fork site that would call a function-typed parameter tests it.

**Changes from v0.6.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): Rule Q4.1.e cites the table convention of `spec/compilation_model.md` Rule CM4.6 instead of restating it, and an effect operation has one pointer; a use in `inspect` or `trace` counts as an ordinary use where diagnostics are kept (§4); §4 and Rule Q5.5 say where a `proved` status obtained by enumeration in the test build reaches; wrong references to P7.9 and §6.2 corrected.

**Changes from v0.5.** Applies decision P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`, D6, D9, D15).
- §4: property verification is no longer one pass that runs before quantity inference. The static prover runs after the typer and before quantity inference; the cost checker runs on Core; property-based testing is a separate test build whose results never enter the facts lane (`spec/properties.md` Rule P7.8). This resolves the phase-order contradiction with `spec/memory_management.md` §20.3.
- Rule Q4.1.e: a call through a capability table follows the convention derived from the requirement's signature (`spec/compilation_model.md` Rule CM4.6); closures of unknown identity and effect operations keep `own` on every argument.
- Rule Q9.3: an exclusive version is requested only where the run-time `rc == 1` test cannot stand in (`fip fn` bodies and what they request; values with no count). The measurement behind this is under suspicion (open).
- Rule Q5.13: fork site 3 takes only `proved` props; whether an admissible site forks (granularity) is in the measurement lane.
- Rule Q7.1: the diagnostic codes are `E071` for a `fip` obligation, `E072` for `resume` and `E083` for a linear effect; the text had given `E071` for all three.
- The "Recheck-style" label applies to the quantity pass on the typed tree only, not to demand inference on Core.

**Changes from v0.4.**
- Demands are inferred on the **baseline version** of each function, which is polymorphic and shared (`spec/compilation_model.md` §3), and again on each clone. The statement that Core has one instance per type is withdrawn (§4).
- Rule Q9.3 rewritten: the **shared** version is the function's baseline and always exists; an **exclusive** version is a clone, built when the clone budget allows. The version cap and the rule that a caller copies beyond it are withdrawn; OQ-Q7 is closed. Copying the demanded part remains only in the shared entry of a `fip fn`.
- New Rule Q4.1.e: a capability method requirement has no body and so no inferred demand; calls through a capability table follow a fixed convention.
- Cost props join `fip` as the ways a program states something about ownership (Rule Q4.1, `spec/cost.md`).
- Rule Q5.12: a default body is analysed once; the per-satisfaction pass happens only in clones.
- Stale statements corrected: the semiring table no longer says quantity 1 enables in-place update; the pass table in §4 describes the quantity pass only; the scope list and cross-references no longer say prop bodies are "checked at quantity 0".

**Changes from v0.3.**
- **Demands** (§4.1) decide ownership at runtime: `0`, `read`, `own` or `exclusive`, per part of a value. A parameter's demand is a contract. A caller that cannot pass an exclusively owned value to an `exclusive` parameter calls the callee's **shared version** instead (§9.3). Quantities (§3, §5) stay, but only count occurrences: erasure, closure call counts, `resume` and diagnostics.
- §9 rewritten: an argument contributes according to where its value goes, not where its name appears. The v0.3 rule scaled every binder inside an argument by the parameter's quantity, which contradicted §10.1.
- New Rule Q9.2 fixes evaluation order (left to right; moves take effect when the call starts) and Rule Q9.4 forbids aliasing an exclusive argument.
- §7 reframed as exclusivity. Linearity errors (`E071`) arise only from declared obligations: `fip` parameters, `resume`, linear effects.
- Effectful arguments are evaluated even when their value is erased (Rule Q6.1.3).

**Changes from v0.2.**
- New Rule Q5.13 (parallel composition): where the compiler may evaluate parts of a `fold` / `unfold` in parallel lanes, and how quantities compose across lanes. There is no surface syntax; fork sites are found automatically.
- OQ-Q2 resolved: quantities stay inferred, and a function can demand in-place behaviour with the checked `fip fn` contract (§7.5, `spec/memory_management.md` §18).
- Placement moved out: quantity decides reference-count traffic only. Where a value lives is its lifetime class (`spec/memory_management.md`, which replaces `spec/perceus.md`).
- New OQ-Q6 (where associativity props live).

**Changes from v0.1.** Rule Q5.5 reframed: prop bodies are not "checked at quantity 0" — they are *excluded from the compiled artifact*. References inside prop bodies do not contribute to a binding's runtime quantity in the artifact, but PBT (`spec/properties.md` §7) may run prop bodies as code in a separate test build; that build computes its own quantities independently. The semantic effect on the compiled binary is the same as before; the framing now distinguishes *artifact* (program binary) from *test build* (PBT driver).

## 1. Scope

This document specifies how the compiler assigns **QTT quantities** (0, 1, ω) to every binding in a Fixed program, and what each quantity means for code generation. Concretely:

- What the three quantities are (§3) and how they compose (§9).
- How quantity inference works as a compiler pass (§4).
- What rule applies at every syntactic position where a binding is introduced (§5).
- What follows from them at run time — erasure (§6), exclusivity (§7), shared values (§8).
- How default-bodied cap methods (Rule 6.1 in type_system.md) get their quantities (Rule Q5.12).

It does **not** specify:

- Placement (lifetime classes, regions), the Perceus RC algorithm, its dup/drop placement, and reuse analysis — see `spec/memory_management.md`. Demand inference on Core (§4.1) also computes the escape facts and lifetime classes that document needs (`spec/memory_management.md` §20); their rules live there.
- Effect-handler semantics in detail — see `spec/effects.md`. (Only `resume`'s quantity is specified here.)
- Property-verification mechanics — see `spec/properties.md`. (Only that `prop` bodies are excluded from the compiled artifact is specified here, Rule Q5.5.)
- The baseline version, witnesses, clones and the clone budget — see `spec/compilation_model.md`. Cost contracts — see `spec/cost.md`.
- Pattern-matching desugaring — see `spec/pattern_matching.md`.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.10 — every syntactic form referenced.
- `spec/compilation_model.md` — the baseline version on which demands are inferred, and the clones Rule Q9.3 refers to.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), §5.9 (phantom inference), Rule 6.1 (default method bodies), §7.3.b (`of` value-params at the type level).
- Atkey, *"Syntax and Semantics of Quantitative Type Theory"*, LICS 2018. The mathematical model.
- Brady, *"Idris 2: Quantitative Type Theory in Practice"*, ECOOP 2021. A production implementation Fixed broadly follows for the inference shape.
- Reinking et al., *"Perceus: Garbage Free Reference Counting with Reuse"*, PLDI 2021. The RC interaction model that quantity ω feeds into.
- Barendsen and Smetsers, *"Uniqueness Typing for Functional Languages with Graph Rewriting Semantics"*, MSCS 1996 (Clean). Uniqueness as a property of the value, which `exclusive` demands are.
- Marshall, Vollmer and Orchard, *"Linearity and Uniqueness: An Entente Cordiale"*, ESOP 2022. Why a binding used once (linearity) is not a value owned once (uniqueness); §4.1 keeps the two apart.
- Ullrich and de Moura, *"Counting Immutable Beans"*, IFL 2019 (Lean 4). Inferred borrowed parameters; the `read` demand.
- Lorenzen, Leijen and Swierstra, *"FP²: Fully in-Place Functional Programming"*, ICFP 2023. The `fip` contract.

## 3. The quantity semiring

Three quantities form a commutative **semiring** over `{0, 1, ω}`:

| Symbol | Name | Runtime meaning |
|---|---|---|
| **0** | Erased | Generates no runtime code; exists only at the type level. |
| **1** | Linear | Used exactly once. This counts occurrences only: in-place reuse and the absence of RC depend on exclusive ownership (§4.1, Rule Q4.1.d), not on quantity 1. |
| **ω** | Unrestricted | Freely shared; reference-counted only if its lifetime class is `Heap` or `Region` (`spec/memory_management.md` §3.2). |

### 3.1 Addition (combining usages)

`+` combines usages across positions where a binding is observed (e.g., across both branches of a sequenced expression):

| `+` | 0 | 1 | ω |
|---|---|---|---|
| **0** | 0 | 1 | ω |
| **1** | 1 | ω | ω |
| **ω** | ω | ω | ω |

Identity: `0`. Monotonic in the lattice `0 < 1 < ω`.

### 3.2 Multiplication (scaling usages)

`·` scales a binding's quantity by a multiplicity context (e.g., a parameter is consumed by a function whose body uses it `q` times):

| `·` | 0 | 1 | ω |
|---|---|---|---|
| **0** | 0 | 0 | 0 |
| **1** | 0 | 1 | ω |
| **ω** | 0 | ω | ω |

Identity: `1`. `0` is annihilator.

### 3.3 Join (combining usages across exclusive branches)

`⊔` (lub) combines usages from mutually exclusive branches (the branches of an `if` or the cases of a `match` — exactly one runs):

| `⊔` | 0 | 1 | ω |
|---|---|---|---|
| **0** | 0 | 1 | ω |
| **1** | 1 | 1 | ω |
| **ω** | ω | ω | ω |

`⊔` is the lattice-theoretic least upper bound on `0 < 1 < ω`. **Note:** `+` and `⊔` differ at `1+1 = ω` versus `1 ⊔ 1 = 1`. The first reflects "both observations happen"; the second reflects "exactly one observation happens, but we don't know which."

## 4. Inference architecture

Two analyses run, with different inputs:

- **Quantities** (§3, §5) count occurrences on the typed tree, after the typer. They give erasure, closure call counts (Rule Q5.7), the `resume` check (Rule Q5.11), linear effects and the unused-binding warning, all with source positions. In a build that keeps diagnostics, a use in `inspect` or `trace` is an ordinary use: it counts for quantity, and its demand is `read`. A build that strips diagnostics removes them before this pass (`spec/diagnostics.md` Rule DG3.5).
- **Demands** (§4.1) are data flow over Core, the backend IR, after lowering. Core sees every allocation and every compiler-generated member (derived `fold`, `unfold`, `map`). Each function has one **baseline version** in Core, polymorphic in its type parameters (`spec/compilation_model.md` Rule CM3.1); demands are inferred on it, and inferred again on every clone the specialiser builds, where known types and ownership make them more precise (§9.3). Lowering carries source spans into Core, so demand diagnostics (`E110`) still point at source. Representation selection consumes demands and therefore also runs on Core (`spec/memory_management.md` §20.3).

Property verification is three things, placed where their inputs exist (P6 D6, `spec/memory_management.md` §20.3): the **static prover** on the typed tree, right after the typer and before quantity inference; the **cost checker** on Core, after demand inference; and **property-based testing** in a separate test build. A decision that is wrong if a claim is false (reuse justified by a prop, a parallel fork site) is in the **facts lane** and never takes a `tested` prop; results of the test build never enter the facts lane of the package compile that preceded it (`spec/properties.md` Rule P7.8.c). A `proved` status that the test build obtains by enumeration (`spec/properties.md` Rule P8.5) is a proof: it is recorded in the interface when `fixed verify` has run, and it reaches the facts lane of importers only. Decisions among alternatives that are all correct (fork granularity, clone weights) are in the measurement lane (Rule P7.8.b).

The rest of this section describes the **quantity** analysis. Quantity inference runs on the typed tree and is a **Recheck-style phase** (see `docs/references/scala3-compiler-reference.md` §5.4). Demand inference is a pass over Core and is not. Quantity inference runs:

| Runs after | Provides input |
|---|---|
| Base typer | Typed AST |
| Capability closure (`spec/type_system.md` §6.2) | Bound sets |
| Static prover (`spec/properties.md` §7) | `proved` props, available to every later pass (fork site 3, Rule Q5.13.a) |

| Runs before | Consumes output |
|---|---|
| Lowering to Core | Erased bindings (quantity 0) excluded; closure call counts (Rule Q5.7) |
| Demand inference on Core (§4.1) | Occurrence counts; the demands, not the quantities, then drive representation selection, reuse and `dup` / `drop` placement (`spec/memory_management.md` §9) |

The pass is **bidirectional**: the typer carries an *expected quantity* (a prototype) down the tree, and synthesises an *actual quantity* up. A binding's quantity is the actual quantity at the point of declaration. Mismatches between expected and actual yield typed errors per §6.1 and, for declared obligations, §7.1.

The pass is **whole-function**: it sees a function's body in its entirety to compute usage counts. Cross-function quantity propagation goes through the function signature: each parameter's inferred quantity is part of it. What a call site must hand over is the parameter's demand, not its quantity (Rule Q5.2).

### 4.1 Demands (Rule Q4.1)

A **demand** states what a function does with a value it receives, and so what its caller must hand over:

| Demand | The callee may | The caller passes |
|---|---|---|
| `0` | ignore the value | nothing; the value is erased (an effectful argument is still evaluated, Rule Q6.1.3) |
| `read` | read it during the call, never keep it | the value itself; no count change (borrowed, `spec/memory_management.md` §13) |
| `own` | keep it, once or several times | a counted reference, moved on the caller's last use |
| `exclusive` | overwrite it in place on some path | an exclusively owned value, or else it calls the shared version (§9.3) |

The demands are ordered `0 < read < own < exclusive`. Keeping a value twice needs nothing more from the caller than keeping it once, so "kept several times" is `own`; the extra reference is the callee's own `dup`.

**Rule Q4.1.a (per part).** A demand applies to a part of a value. A data parameter has one demand per field slot of its type, and a recursive field shares the demand of the slot it recurses to, so the description stays finite. `map` over a list demands `exclusive` on the list cells and `own` on the elements. `bump` (§9.3) demands `exclusive` on the first cell and `own` on its tail.

**Rule Q4.1.b (exclusive ownership).** A value is **exclusively owned** at a program point iff no other live reference to it can exist there. It is exclusively owned when it is:

1. a fresh constructor application, literal or call result;
2. a parameter received under an `exclusive` demand (the caller guaranteed it), on the parts that demand covers;
3. a field of an exclusively owned value that the enclosing `match` consumes, on the parts covered by (2) or (1);

and only until the first use that keeps it (`own`) while the binding stays live. After that it is shared.

**Rule Q4.1.c (demand of a parameter).** A parameter's demand is the greatest demand among its uses. A use counts as `exclusive` only where the parameter is still exclusively owned (Rule Q4.1.b): its last use on that path, with no earlier use that kept it. Otherwise the use counts as `own`.

**Rule Q4.1.d (relation to quantities).** Quantity 0 and demand `0` coincide. Otherwise the two are independent: a binding of quantity 1 may demand `read` (one comparison), and a binding of quantity ω may be passed to an `exclusive` parameter at its last use.

**Rule Q4.1.e (members without a body).** A capability method requirement has no body, so no demand can be inferred for it. A call through a capability table follows the convention derived from the requirement's signature (`spec/compilation_model.md` Rule CM4.6, which states it and gives its examples). A satisfier that must keep a `read` argument `dup`s it; one that ignores an `own` argument drops it. A call through a closure whose identity is unknown passes every argument under `own` on the shared pointer, and may use the exclusive pointer only when the caller exclusively owns every argument in every part (`spec/memory_management.md` Rule PR5.6). A call of an effect operation goes through the single pointer of its evidence entry and passes every argument under `own` (`spec/effects.md` §7.1, Rule E7.7). A satisfying type's method keeps its own inferred demands for callers that know the type; the table entry adapts it to the convention. The reason for a convention read off the signature: it is the only thing a caller and every satisfier, possibly in different packages, both see.

Demands are inferred, never written. A program states something about ownership only through a contract: `fip` (§7.5), which requires `exclusive` on the parts a function reuses, and the cost props of `spec/cost.md`, whose `releases` term is defined on `exclusive` parts.

## 5. Quantity inference rules

Each rule names the syntactic position it governs and the quantity assigned. `Q5.X.Y` is the citation form.

### 5.1 Local bindings (Rule Q5.1)

A `let x = expr` binding's quantity is the sum (using `+` from §3.1) of its usages in the rest of the enclosing block.

```
let x = compute()      // 1 use of x below
let y = x + 1          // x used once → contributes 1
y * 2                  // y used once → y has quantity 1
```

If `x` is used 0 times: quantity 0, with a "dead binding" warning unless the name begins with `_`.

### 5.2 Function parameters (Rule Q5.2)

A function parameter's quantity is the sum of its usages in the body, taken under the body's quantity context.

```
fn double(x: i64) -> i64 = x + x          // x used twice → quantity ω
fn identity(x: i64) -> i64 = x            // x used once → quantity 1
fn constZero(x: i64) -> i64 = 0          // x used zero times → quantity 0 (warning unless _x)
```

The inferred quantity is part of the function's signature. What the caller must hand over is the parameter's demand (Rule Q4.1.c), not its quantity: `identity` demands `own` of `x`, and `double` demands `read` of a scalar `x`. For unboxed scalars only `0` and non-`0` produce different code.

### 5.3 `of` value-params (Rule Q5.3)

`of` value-params (e.g., `lo: N = 16` in `data Bounded of (N is Numeric, lo: N = 16, hi: N = 24)`) are **always quantity 0** by default — type-level, erased at runtime. Resolves §7.3.b deferral from `spec/type_system.md`.

The compiler materialises them as compile-time constants at access sites: `b.lo` in arithmetic context becomes the statically known constant for `b`'s type. No runtime field access; no runtime layout cost.

A future extension may allow user-declared quantity-ω `of` value-params for cases where the value must be runtime-materialised; not in v0.4.1.

### 5.4 Inferred-phantom type params (Rule Q5.4)

A type parameter inferred phantom per type_system.md §5.9 is **always quantity 0**. Erasure follows trivially: the typer has already verified the parameter does not appear in any field, method signature, inherited member, or prop expression, so there are no usages at non-type positions to count.

### 5.5 `prop` bodies (Rule Q5.5)

Prop bodies are **excluded from the compiled artifact** (the program binary). References to bindings inside a prop body therefore do **not contribute to the binding's runtime quantity** in the compiled artifact. If a binding is used only inside prop bodies, it has runtime quantity 0 in the artifact and is erased.

Property-based testing (`spec/properties.md` §7) compiles prop bodies separately into a **test build** — for example, a PBT driver that runs the prop body on generated inputs. Inside that test build, bindings have their normal quantities determined by all uses (including the prop body itself); the test build genuinely runs the body and is subject to standard quantity arithmetic. The test build is **not** part of the program binary, and its results never enter the facts lane of the package compile that preceded it (`spec/properties.md` Rule P7.8.c); a `proved` status it obtains by enumeration reaches importers only (§4).

Operationally: when computing a binding's runtime quantity in the compiled artifact, the typer ignores uses inside prop bodies. Property verification is then handled separately, with its own compilation pipeline.

The earlier framing ("prop bodies are checked at quantity 0") was a shorthand for this artifact-exclusion rule; the v0.2 wording makes the distinction explicit because PBT involves real runtime evaluation and benefits from the clarification.

### 5.6 Lambda parameters (Rule Q5.6)

Same as Rule Q5.2: a lambda parameter's quantity is the sum of its usages in the body. Lambda *captures* of outer-scope bindings count toward the outer binding's quantity according to Rule Q5.7 below.

```
let inc = (x: i64) -> x + 1        // x is quantity 1 within inc's body
let byTwo = (x: i64) -> x + x     // x is quantity ω
```

### 5.7 Captures by closures (Rule Q5.7)

A closure captures a binding `y` from the enclosing scope. The closure's body uses `y` with some quantity `q`. The closure value itself is then used at the call site with some quantity `q'` (per Rule Q5.2). The total quantity contributed to `y` by the closure is `q · q'` (multiplication, §3.2).

```
let y = compute()                  // quantity TBD
let g = (x) -> x + y               // closure body uses y once → q = 1
g(1) + g(2)                        // g used twice → q' = ω; so y contributes 1·ω = ω
```

Because closures may be called arbitrarily many times by their own callers (their `q'` is generally `ω`), captured bindings typically end up at quantity ω. A closure called exactly once preserves its captured bindings' quantities.

**Demands of captures.** A capture's demand is the closure body's demand on it. If the body demands `exclusive` but the closure may be called more than once (`q' = ω`), the capture is downgraded to `own`: a second call would find it already overwritten. Every closure carries **two code pointers**, one compiled for exclusive input and one for shared input; they coincide unless a parameter is `exclusive` (`spec/memory_management.md` Rule PR5.6). A call through an unknown closure picks the pointer statically from what the caller holds. This costs one word per closure and no runtime test.

### 5.8 Pattern bindings (Rule Q5.8)

Each binding introduced by a pattern (`let (a, b) = expr`, `match x: List.Cons(h, t) => …`) gets its quantity inferred per Rule Q5.1 from its usages in the scope of the binding.

For match cases specifically: a binding's quantity within the case body follows Q5.1; the binding does not exist outside the case.

### 5.9 Branching: `if`, `match`, `handle` (Rule Q5.9)

For an expression with **mutually exclusive branches** — `if c: A else: B`, or `match e: pat1 => a1 | pat2 => a2 | …`, or handler clauses in a `handle` — a free binding's contribution is the **join** (`⊔`, §3.3) over all branches, *not* the sum.

```
fn f(x: i64, c: bool) -> i64 =
    if c:
        x        // x used once in this branch
    else:
        x + x    // x used twice in this branch
// x's quantity contribution from the if = 1 ⊔ ω = ω
```

Rationale: at runtime exactly one branch executes, so the worst-case usage across branches is the safe over-approximation. Using `+` would over-count.

### 5.10 Sequencing (Rule Q5.10)

For a block `e1; e2; …; en` (sequenced expressions in a `Block`), each free binding's quantity is the **sum** (`+`) over its usages across the sequence.

```
fn g(x: i64) -> i64 =
    let a = x + 1     // x used once
    let b = x + 2     // x used once
    a + b
// x's quantity = 1 + 1 = ω
```

### 5.11 `resume` in handlers (Rule Q5.11)

`resume(v)` inside a handler clause body is **always quantity 1** (single-shot). The clause body must contain `resume(...)` exactly zero or one time on every code path; two or more `resume` calls reachable on a single path is a compile error (`E072`, Rule Q7.1).

```
handle action():
    Eff.op(x) =>
        let v = compute(x)
        resume(v)              // exactly one resume → OK
    Eff.fail(e) =>
        Result.err(e)          // zero resumes → OK; clause produces a final value
```

Multi-shot handlers (where `resume` may be called any number of times) are not supported in v0.4.1. Adding them would require relaxing this rule to allow `resume` at quantity ω, with corresponding changes in the runtime continuation representation. Tracked as **OQ-Q1** below.

### 5.12 Default-bodied cap methods (Rule Q5.12)

When a cap method carries a default body (Rule 6.1 in type_system.md), the body's quantity inference runs in two passes:

1. **At the cap declaration:** the body is analysed against the cap's abstract `Self`, treating each `Self.fn` constructor *requirement* and abstract method *requirement* as a function with worst-case (quantity-ω) parameter quantities. This produces a *quantity skeleton* for the default body.
2. **Once as a baseline version; again only in a clone:** the default body is compiled once, as a baseline version that calls the capability's requirements through the table of `Self` (`spec/compilation_model.md` Rule CM4.2). Its demands there are those of the convention (Rule Q4.1.e). Only when the specialiser clones the default body for a known satisfying type are the type's concrete method demands substituted and the body analysed again; the clone may then borrow or reuse where the baseline could not.

If a satisfying type provides an explicit `satisfies`-block override (an `InstanceMethodDef`), the default body is not used; quantities are computed entirely from the override.

### 5.13 Parallel composition (Rule Q5.13)

Fixed has no surface syntax for parallelism. The compiler may evaluate independent sub-computations of a `fold` or `unfold` in parallel **lanes**. Write `e1 ∥ … ∥ en` for such a composition in the lowered IR. The memory side (lane stacks, borrowed inputs, disjoint destinations, non-atomic counts) is `spec/memory_management.md` §17.

**Rule Q5.13.a (fork sites).** A parallel composition may be introduced only at one of these sites:

1. **Tree fold.** An auto-derived `fold` (`spec/type_system.md` Rule 7.4.a) at a variant with two or more recursive fields. The sub-folds of those fields are the lanes. Required: every function argument of the `fold` has an empty effect row (`spec/effects.md` §4.3).
2. **Tree unfold.** An auto-derived `unfold` (Rule 7.4.b) whose step returns a variant with two or more recursive seeds. The sub-unfolds of those seeds are the lanes. Required: the step has an empty effect row.
3. **Sequence fold over a proved associative operation.** A `fold(init, step)` over a value with one recursive position, where `step` has the form `(acc, x) -> op(acc, g(x))` with `op: (R, R) -> R`, all effect rows are empty, and props with status `proved` (`spec/properties.md` Rule P7.7) state that `op` is associative and that `init` is its identity. The sequence is split into contiguous runs. Each run is a lane, and the lane results are combined with `op` in sequence order.

Sites 1 and 2 need no props. Each lane computes exactly the sub-result that sequential evaluation computes, and the node function combines the sub-results in the same order. Site 3 changes the association order, so its result differs from the sequential one unless `op` is associative. That is why it needs props of the facts lane, `proved` or `assumed`: a `tested` prop does not qualify (Rule P7.7.c). The props come from the static prover, which runs before this pass (§4); a `tested` status, which only the test build can give, could not arrive in time and is excluded from the facts lane in any case (`spec/properties.md` Rule P7.8). Floating-point addition is the standard case that is rejected: it is not associative, so the prop is never `proved`, and the fold stays sequential unless the programmer writes the assumption (`assume prop`, `spec/properties.md` Rule P7.7) and so accepts a result that may differ from the sequential one.

An effect row is a typing fact (`spec/effects.md` §5), not a prop, so "pure" needs no verification status. A callback that reaches a `fold` through an unannotated function type (`spec/effects.md` Rule E5.5.a) has an empty row in that type and may still carry captured evidence (Rule E7.8), so the type cannot tell whether calling it is pure. A closure therefore carries one bit, set when it holds captured evidence. A fork site whose lanes would call a function-typed parameter tests the bit before it forks and runs sequentially when the bit is set; in a clone where the callback is known the test is decided at compile time. Captured evidence never crosses into a lane.

**Rule Q5.13.b (quantities across lanes).** Every lane runs, so a free binding's contribution is the **sum** (`+`, §3.1) of its usages over the lanes, as for sequencing (Rule Q5.10):

1. `e1 ∥ e2` gives every binding the same quantity as `e1; e2`. Whether the compiler forks at an admissible site therefore never changes an inferred quantity or any diagnostic of this document.
2. A quantity-1 binding is used by at most one lane, since `1 + 1 = ω`.
3. A binding used by two or more lanes is quantity ω. It gets the escape fact `lane-shared(fork)` (`spec/memory_management.md` Rule MM20.1), and the lanes borrow it for the duration of the fork.
4. The folded value itself: when it is exclusively owned (Rule Q4.1.b), its recursive fields are separate pattern bindings (Rule Q5.8) and each lane consumes exactly one of them, so in-place reuse (§7.2) stays available inside every lane. When it is shared, the lanes borrow it (`read`).
5. Each lane's result is fresh, hence exclusively owned, at the join. It is consumed once, by the node function (sites 1 and 2) or by `op` (site 3).

The function arguments of a `fold` are closures that run once per node, so the bindings they capture are already scaled by ω under Rule Q5.7. Forking adds nothing to that.

**Rule Q5.13.c (same result).** A forked evaluation must produce the result of sequential evaluation. Empty effect rows and totality make the order of lanes unobservable. If more than one lane panics (`unreachable`, or an `impossible` mapping per `spec/type_system.md` §8.2), the panic reported is that of the first such lane in sequential order.

**Rule Q5.13.d (the fork decision).** Whether an admissible site actually forks is a cost decision of the compiler and runtime (granularity), observable only as timing. It is in the measurement lane (`spec/properties.md` Rules P7.8, P7.9): it may use costs measured in the test build or a profile (`spec/compilation_model.md` Rule CM8.6), because every choice is correct. The memory report (`spec/memory_management.md` §19) states for every `fold` / `unfold` whether it is a fork site and, when it is not, the reason: the effect that makes a row non-empty, or the prop that is not `proved` and its status.

## 6. Erasure (quantity 0)

A quantity-0 binding generates **no runtime code**. The compiler removes it from the runtime AST before code generation.

### 6.1 Erasure-soundness check (Rule Q6.1)

Before code generation, the typer verifies:

1. Every quantity-0 binding `x` is referenced **only** in:
   - Type expressions (e.g., `is Cap of x`).
   - Prop expressions (Rule Q5.5).
   - Other quantity-0 bindings' definitions (transitively).
   - Phantom positions (§5.4).
2. No quantity-0 binding flows into a quantity-1 or quantity-ω position.
3. Erasing an argument (demand `0`) removes its value, not its evaluation. An argument expression with a non-empty effect row is still evaluated for its effects (`spec/effects.md` §5); only a pure argument is removed entirely.

Violations emit `error[E070]: erased binding used at runtime`, citing the offending position.

### 6.2 Materialisation (Rule Q6.2)

Some quantity-0 bindings carry compile-time-known values (e.g., `of` value-params, Rule Q5.3). When the typer encounters a runtime-position read of such a binding (e.g., `b.lo` in arithmetic context), the read is **materialised** as the compile-time constant — *not* a violation of erasure, because the binding contributes no runtime layout; the constant is inlined directly.

Materialisation is bounded to cases where the value is statically known. A quantity-0 binding without a static value (e.g., a phantom type, which has no value at all) cannot be materialised and remains pure type-level.

## 7. Exclusivity

Quantity 1 says a binding is used once. That does not make the value exclusively owned (Rule Q4.1.d). In-place reuse depends on the value, so the rules of this section are stated for `exclusive` demands and exclusively owned values.

### 7.1 Declared obligations (Rule Q7.1)

Inference alone cannot be violated: a binding used twice simply has quantity ω and demand at most `own`. Errors arise only where something is **declared**:

1. a `fip fn` parameter that the function reuses must be `exclusive` (§7.5, `spec/memory_management.md` Rule MM18.3);
2. `resume` runs at most once on every path (Rule Q5.11, `E072`);
3. a linear effect is performed at most once (Rule Q7.4, contingent on OQ-Q5).

Capturing an exclusively owned value in a closure that may run several times is not an error. The capture is downgraded to `own` (Rule Q5.7), and the value is shared from then on.

Violations: `error[E071]: <obligation> not met` for item 1, `error[E072]` for item 2 (two `resume`s reachable on one path), and `error[E083]` for item 3 (`spec/effects.md` Rule E3.5.a). Each names the obligation and the locations involved.

### 7.2 FBIP / in-place reuse (Rule Q7.2)

An exclusively owned value's memory may be **reused in place** at its last use rather than freed and allocated again. This is Functional-But-In-Place (FBIP). Where the value is exclusively owned, reuse is unconditional and needs no count test. A shared `Heap` value may still be reused after a runtime test (`rc == 1`, Koka's technique; `spec/memory_management.md` Rule PR6.3). Fixed is therefore hybrid: static where exclusivity is proved, dynamic for shared heap values.

### 7.3 No RC overhead (Rule Q7.3)

An exclusively owned value needs no `dup` or count-aware `drop`. Its last use moves it, and its final owner frees or reuses it without testing a count (`spec/memory_management.md` Rule PR3.5).

### 7.4 Linear-effect handler clauses (Rule Q7.4) — **Proposal, contingent on OQ-Q5; resolves L6**

> **Status note.** Like `spec/effects.md` §3.5, this rule is a proposal contingent on OQ-Q5 closing in favor of effect rows participating in QTT quantity. Implementations should treat Rule Q7.4 as opt-in behind the same flag that gates §3.5.

A handler clause body for a `linear effect` (per `spec/effects.md` §3.5) is itself a **quantity-1 closure**. The clause runs at most once per `handle` block (Rule E3.5.c — at-most-once semantics), so the body's quantity inherits from the effect's linearity rather than defaulting to ω as a regular closure body would.

Consequence: an exclusively owned outer value captured by a linear-effect clause body **stays exclusive** there, because the clause runs at most once (Rule Q5.7 with `q' = 1`). The capture's contribution to the outer scope's usage of that binding is the join of (clause-body usage) and (other-paths usage), bounded above by 1.

```
let lockToken = makeToken()        // quantity 1
handle (do: ... Lock.release):
    Lock.release =>
        consume(lockToken)          // captured from outer scope — OK under Rule Q7.4
        resume(())
// total quantity of lockToken across the handle block: 1 (consumed in the clause)
```

A non-linear clause body may run once per operation performed, so the same capture there is downgraded to `own` (Rule Q5.7): `lockToken` becomes shared and `consume` gets the shared version. This is not an error (Rule Q7.1); it costs the in-place reuse.

If OQ-Q5 closes the other way (effect rows do not participate in QTT quantity), Rule Q7.4 is withdrawn and every handler clause downgrades its exclusive captures.

### 7.5 Demanding exclusivity: `fip fn`

Quantities and demands are inferred, so most programs never state one. A function that must run in place states that with the `fip fn` contract (`spec/memory_management.md` §18). The compiler then rejects the function (`error[E110]`) if inference finds an allocation on the heap, a reference-count operation or an unbounded data-stack use. Every parameter such a function reuses must be `exclusive` (Rule MM18.3), which is where Rule Q7.1.1 gets a declared obligation to check. A `fip` function has one body and no shared body; a caller holding a shared value calls its shared entry, which copies the demanded part and then calls the body (§9.3). This resolves OQ-Q2.

## 8. Shared values

A value that is not exclusively owned is **shared**: a quantity-ω binding that kept it, a parameter received under `own`, or a field of a shared value. Whether it costs reference counting depends on its lifetime class (`spec/memory_management.md` §3.2): values that live in a data-stack region carry no RC. For `Heap` and `Region` values, the Perceus pass inserts:

1. **`dup` operations** at every share point (when a binding is used in more than one downstream position).
2. **`drop` operations** at every consumption point that is not the last use.
3. **Reuse opportunities** at the last use, when the value's representation matches a constructor application of the same shape, after a runtime `rc == 1` test (Rule Q7.2).

Sharing is the default for most user code and bears no programmer obligation.

## 9. Arguments and calls

### 9.1 Where an argument's value goes (Rule Q9.1)

At a call, each binding contributes according to where its **value** goes, not where its name appears in the argument:

1. **Passed as itself.** In `f(y)`, `y` meets the parameter's demand.
2. **Stored inside a composite argument.** In `f(List.Cons(y, rest))`, `y` meets the demand of the field it lands in, within the parameter's demand (Rule Q4.1.a). If `f` overwrites only the outer cell, `y` is merely `own`.
3. **Read to compute a new value.** In `go(i - 1, b, a + b)`, `+` reads `a`; the demand on `go`'s parameter applies to the sum, not to `a`.
4. **Captured by a lambda argument.** The closure body's demand on the capture, scaled by how often the callee calls the closure (Rule Q5.7).
5. **Demand `0`.** The argument's value is erased; its evaluation remains if it has effects (Rule Q6.1.3).

For quantities, the same rule applies: an argument expression is evaluated once, so a binding used inside a composite argument is counted once, while a binding passed as itself, and a lambda argument's captures, are scaled by the parameter's quantity. The v0.3 rule scaled every binding inside an argument, which in a strict language counts work that never happens twice and contradicted §10.1.

```
fn pairDouble(x: String) -> String = x ++ x     // x: own (kept twice)

fn caller(y: String) -> String =
    pairDouble(y)                                // y passed as itself: own
```

### 9.2 Evaluation order (Rule Q9.2)

Arguments are evaluated **left to right**, and a call's receiver before its arguments. A move into an `own` or `exclusive` parameter takes effect **when the call starts**, after every argument has been evaluated. A read of a binding in a sibling argument therefore happens before the binding is moved. This rule also fixes the order in which argument effects are performed.

### 9.3 Shared versions (Rule Q9.3)

Every function has a **shared version**: its baseline version (`spec/compilation_model.md` Rule CM3.1), compiled for input the caller does not exclusively own. The shared version allocates the cells it rebuilds, shares the parts it does not touch, and for `Heap` input may still reuse after a runtime `rc == 1` test (Rule Q7.2). It is always a correct callee.

An **exclusive version** is a clone of it, compiled for a caller that exclusively owns some of the `exclusive` parts: there reuse is unconditional. When a caller passes an exclusively owned value to an `exclusive` parameter and that clone exists, it calls the clone; otherwise it calls the shared version. The caller knows statically which kind of value it holds, so the choice is made at compile time. A call through a capability table or a closure makes the same choice between two code pointers (Rule Q4.1.e).

**Which exclusive versions are requested (Rule Q9.3.a).** A caller requests an exclusive version only where the run-time `rc == 1` test of the shared version cannot stand in for the static proof (`spec/memory_management.md` Rule PR6.3.a):

1. the body of a `fip fn` and every clone it requests (mandatory, `spec/compilation_model.md` Rule CM5.5);
2. an exclusively owned argument that has no per-value count (`Frame`, stack `Dest`, `Region`), which the shared version cannot reuse.

An exclusively owned `Heap` argument goes to the shared version, whose run-time test succeeds on it. The reason is the first measurement, in which the test ran as fast as unconditional reuse. **Open:** the project owner suspects that this measurement hides a compiler fault; until that is ruled out (`spec/memory_management.md` §21), the rule is provisional.

```
fn bump(xs: List of u64) -> List of u64 =
    match xs:
        List.Cons(h, t) => List.Cons(h + 1, t)     // xs: exclusive on the first cell, own on t
        List.Nil => List.Nil

fn main() -> () with Console =
    let a = upto(3)                                 // fresh: exclusively owned
    let b = bump(a)                                 // a is used again below: shared version
    Console.printLine(a.show())
```

Had `main` printed `a` before calling `bump`, the call would be `a`'s last use and `a` would still be exclusively owned. If `a` is `Heap`, the shared version's `rc == 1` test succeeds and `a`'s cell is overwritten in place. If `a` is `Frame`, the call requests the exclusive version (Rule Q9.3.a), which overwrites the cell if it was built.

**Which exclusive versions exist.** An exclusive version is one axis of a clone key, beside known types and classes (`spec/compilation_model.md` Rule CM5.1). Clones are built for the combinations that call sites request under Rule Q9.3.a, in the order of their score, while the function's clone budget lasts (Rules CM5.3, CM5.4). A package also exports the ownership combinations of its public functions within the same budget, for classes with no per-value count only (Rule CM6.4). A combination that was not built costs nothing but the opportunity: the caller calls the shared version. Earlier drafts capped the number of versions at four and made the caller copy beyond the cap; both are withdrawn.

**Copying.** A `fip fn` has no shared body, because a body that tested counts or allocated would not be `fip`. Its shared entry **copies the demanded part** of a shared argument and calls the one body: the cells the demand covers are copied, the parts it does not cover are shared (`spec/compilation_model.md` Rule CM5.5). Copying is the strategy Neut applies to every share (`spec/memory_management.md` §7.3); Fixed applies it only here. Values are immutable, so a copy is never observable. Every copy is reported by `--explain-memory` (`spec/memory_management.md` §19).

### 9.4 Aliasing (Rule Q9.4)

An argument meets an `exclusive` demand only if no other argument or capture of the same call reaches the same value or a part of it. `f(a, a)`, `f(xs, xs.tail)`, and `f(a, () -> a.length())` with `f`'s first parameter `exclusive` all call the shared version (or, through a table or closure, the shared pointer): otherwise the callee could overwrite what it reads through the other argument.

## 10. Worked examples (from `examples/*.fixed`)

### 10.1 `examples/01_basics.fixed` — `fibIter`

```
fn fibIter(n: N is Numeric) -> N =
    fn go(i: N, a: N, b: N) -> N =
        if i == 0:
            a                           // a used once
        else:
            go(i - 1, b, a + b)          // i, b, a all used once
    go(n, 0, 1)
```

Quantity walk for `go`'s parameters under Rule Q5.9 (branching uses join):

- `i` in then-branch: 0; in else-branch: 1 (used in `i - 1`). Join: 1. But `i == 0` also uses `i` — that's outside the if; the `i == 0` is in the condition, so `i` is used once there. Total: `1 + (0 ⊔ 1) = 1 + 1 = ω`.
- `a` in then-branch: 1; in else-branch: 1 (in `a + b`). Join: 1. Total in body: `0 + (1 ⊔ 1) = 1`.
- `b` in then-branch: 0; in else-branch: 2 (in `b` and in `a + b`). Join: ω. Total: `0 + (0 ⊔ ω) = ω`.

So `go`'s parameters have quantities `(i: ω, a: 1, b: ω)`. Under Rule Q9.1 the else-branch's `a + b` counts `a` once even though `go`'s parameter `b` is ω: the sum is computed once.

The parameters are unboxed scalars, so their demands are `read` (`i`, compared and subtracted) and `own` (`a`, `b`, returned or passed on), and the code is the same for every non-zero demand.

### 10.2 `examples/05_phantom_types.fixed` — `Door` and `Quantity`

```
cap Door of (State):                  // State inferred phantom (§5.9)
    Self.fn new -> Self
    fn name -> String

fn unlock(door: is Door of Locked) -> is Door of Unlocked =
    Door.new
```

- `State` is phantom → quantity 0 (Rule Q5.4). Erased entirely.
- `door` parameter: used 0 times in body → quantity 0 with `_door` warning (or named `_` if rename desired). The phantom-type-change is reflected at the type level only; the runtime value of `door` is not consulted.

This is the canonical case where QTT's erasure makes phantom-typed state machines truly zero-cost: the runtime data is identical to a single-state structure, with type-level distinction enforced at compile time.

### 10.3 `examples/11_properties.fixed` — `Bounded` constructor

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop inRange: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi

let x: Bounded of (i64, 0, 10) = Bounded.Bounded(5)
let result = x.value + x.lo                     // x.value: runtime read; x.lo: compile-time constant
```

- `lo` and `hi` are `of` value-params → quantity 0 (Rule Q5.3).
- `x.lo` in `x.value + x.lo` is a quantity-0 read in a runtime arithmetic context. Per Rule Q6.2, the typer **materialises** `x.lo` as the compile-time constant `0` (from the type `Bounded of (i64, 0, 10)`). The arithmetic becomes `x.value + 0`, which the optimiser may further simplify.
- `x.value` is a regular runtime field of quantity ω (or 1 if `x` is consumed exactly once after).

## 11. Open questions

- **OQ-Q1 — Multi-shot handlers.** Rule Q5.11 fixes `resume` at quantity 1. Real-world non-determinism handlers (probabilistic programming, backtracking, async schedulers with replay) need quantity-ω resume. Lifting requires a continuation representation that survives multiple invocations and a quantity inference that admits `resume` at ω. Defer to v0.5+ when a use case lands.

- ~~**OQ-Q2 — User-declared quantities on parameters.**~~ Resolved in v0.3. Parameters stay unannotated. The way to demand FBIP is the function-level `fip fn` contract (§7.5), which the compiler checks against the inferred quantities. No quantity appears in the surface syntax.

- **OQ-Q3 — Closure-of-closure quantities.** Rule Q5.7's multiplication composes through one closure layer. For nested closures (a closure that returns a closure that captures the outer scope), the composition is straightforward by induction, but worked examples will reveal corner cases. Add concrete worked examples in v0.2 of this doc once the typer is implemented.

- **OQ-Q4 — Quantity polymorphism.** A truly generic `id : T -> T` could be quantity-polymorphic — the parameter's quantity equals the call site's. Idris 2 has limited quantity polymorphism; Fixed has so far inferred everything. The shared baseline with exclusive clones (Rule Q9.3) gives ownership polymorphism without syntax; whether anything beyond that is needed waits for benchmark code.
- ~~**OQ-Q7 — Version cap.**~~ Closed in v0.5. There is no version cap: exclusive versions are clones under the one size budget of `spec/compilation_model.md` Rule CM5.3, and a function larger than the budget has only its shared version. The budget's value is to be measured (OQ-CM3).

- **OQ-Q5 — Effect operations and quantities.** Effect ops (e.g., `Console.printLine`) currently follow Rule Q5.2. Whether *effect rows* themselves participate in quantity (e.g., a "linear effect" that may be performed at most once) is a separate research question, related to OQ-Q1. The proposed semantics live in `spec/effects.md` §3.5 (re-opened OQ-E5 in v0.2.1) and stand non-normative pending closure of this question.

- **OQ-Q6 — Where associativity props live.** Fork site 3 (Rule Q5.13.a) needs `proved` props that an operation is associative and has an identity. `spec/stdlib.md` has no capability carrying such props today: `Numeric` declares none. Until one exists (a monoid-like capability, or props on `Numeric.add` for the integer types), only tree folds and tree unfolds fork. Whether the static prover can discharge associativity for fixed-width integer arithmetic also depends on OQ-P1.

## 12. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | Syntactic forms whose bindings are governed by these rules |
| `spec/type_system.md` | Rule 4.1, §5.9, Rule 6.1, §7.3.b — type-level rules referenced here |
| `spec/properties.md` | Static-vs-PBT verification of `prop` bodies; this doc only states they are excluded from the compiled artifact (Rule Q5.5) |
| `spec/effects.md` | Effect-row composition; this doc fixes only `resume`'s quantity (Rule Q5.11) |
| `spec/pattern_matching.md` | Match desugaring; pattern bindings (Rule Q5.8) follow standard rules |
| `spec/memory_management.md` | Demand and exclusivity × lifetime class decide RC and reuse: exclusive → move and reuse in place; shared ∧ `Heap`/`Region` → dup/drop and `rc == 1` reuse; `0` → erased. M10 also computes the escape facts and classes of §20 there |
| `spec/compilation_model.md` | The baseline version demands are inferred on; clones (Rule Q9.3); the table convention (Rule Q4.1.e) |
| `spec/cost.md` | Cost props: `releases` is defined on `exclusive` parts |
| `docs/references/scala3-compiler-reference.md` | The Recheck pattern (§5.4) that this pass adopts |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `qtt/QuantityChecker.scala` (Recheck phase) |
