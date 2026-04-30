# Fixed Quantities (QTT)

**Status:** Draft v0.1
**Specifies:** quantity inference for Fixed v0.4.1 source.
**Last revised:** 2026-04-30

## 1. Scope

This document specifies how the compiler assigns **QTT quantities** (0, 1, ω) to every binding in a Fixed program, and what each quantity means for code generation. Concretely:

- What the three quantities are (§3) and how they compose (§9).
- How quantity inference works as a compiler pass (§4).
- What rule applies at every syntactic position where a binding is introduced (§5).
- The runtime semantics of each quantity tier — erasure (§6), linearity (§7), unrestricted/RC (§8).
- How default-bodied cap methods (Rule 6.1 in type_system.md) inherit and re-check quantities at satisfying types (§10).

It does **not** specify:

- The Perceus RC algorithm itself, its dup/drop placement, or reuse analysis — see `spec/perceus.md`.
- Effect-handler semantics in detail — see `spec/effects.md`. (Only `resume`'s quantity is specified here.)
- Property-verification mechanics — see `spec/properties.md`. (Only that `prop` bodies are checked at quantity 0 is specified here.)
- Pattern-matching desugaring — see `spec/pattern_matching.md`.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.1 — every syntactic form referenced.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), §5.9 (phantom inference), Rule 6.1 (default method bodies), §7.3.b (`of` value-params at the type level).
- Atkey, *"Syntax and Semantics of Quantitative Type Theory"*, LICS 2018. The mathematical model.
- Brady, *"Idris 2: Quantitative Type Theory in Practice"*, ECOOP 2021. A production implementation Fixed broadly follows for the inference shape.
- Reinking et al., *"Perceus: Garbage Free Reference Counting with Reuse"*, PLDI 2021. The RC interaction model that quantity ω feeds into.

## 3. The quantity semiring

Three quantities form a commutative **semiring** over `{0, 1, ω}`:

| Symbol | Name | Runtime meaning |
|---|---|---|
| **0** | Erased | Generates no runtime code; exists only at the type level. |
| **1** | Linear | Used exactly once; enables in-place mutation (FBIP) and skips RC. |
| **ω** | Unrestricted | Freely shared; managed by Perceus reference counting. |

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

`⊔` (lub) combines usages from mutually exclusive branches (the arms of an `if` or `match` — exactly one runs):

| `⊔` | 0 | 1 | ω |
|---|---|---|---|
| **0** | 0 | 1 | ω |
| **1** | 1 | 1 | ω |
| **ω** | ω | ω | ω |

`⊔` is the lattice-theoretic least upper bound on `0 < 1 < ω`. **Note:** `+` and `⊔` differ at `1+1 = ω` versus `1 ⊔ 1 = 1`. The first reflects "both observations happen"; the second reflects "exactly one observation happens, but we don't know which."

## 4. Inference architecture

Quantity inference is a **Recheck-style phase** (see `docs/references/scala3-compiler-reference.md` §5.4). It runs:

| Runs after | Provides input |
|---|---|
| Base typer | Typed AST |
| Capability closure (§6.2) | Bound sets |
| Property verification | Static prop results (so quantity 0 contexts are verified) |

| Runs before | Consumes output |
|---|---|
| Representation selection (§6.5) | Quantity context |
| Match-to-fold desugaring | Quantity-aware desugar |
| Perceus insertion | dup/drop sites driven by quantity ω vs 1 vs 0 |
| Code generation | Erased bindings excluded |

The pass is **bidirectional**: the typer carries an *expected quantity* (a prototype) down the tree, and synthesises an *actual quantity* up. A binding's quantity is the actual quantity at the point of declaration. Mismatches between expected and actual yield typed errors per §6.1, §7.1, §8.1.

The pass is **whole-function**: it sees a function's body in its entirety to compute usage counts. Cross-function quantity propagation goes through the function signature (each parameter has its inferred quantity baked into the signature; call sites consume arguments at that quantity).

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
fn const_zero(x: i64) -> i64 = 0          // x used zero times → quantity 0 (warning unless _x)
```

The inferred quantity is part of the function's signature; call sites consume arguments at that quantity (§9).

### 5.3 `of` value-params (Rule Q5.3)

`of` value-params (e.g., `lo: N = 16` in `data Bounded of (N is Numeric, lo: N = 16, hi: N = 24)`) are **always quantity 0** by default — type-level, erased at runtime. Resolves §7.3.b deferral from `spec/type_system.md`.

The compiler materialises them as compile-time constants at access sites: `b.lo` in arithmetic context becomes the statically known constant for `b`'s type. No runtime field access; no runtime layout cost.

A future extension may allow user-declared quantity-ω `of` value-params for cases where the value must be runtime-materialised; not in v0.4.1.

### 5.4 Inferred-phantom type params (Rule Q5.4)

A type parameter inferred phantom per type_system.md §5.9 is **always quantity 0**. Erasure follows trivially: the typer has already verified the parameter does not appear in any field, method signature, inherited member, or prop expression, so there are no usages at non-type positions to count.

### 5.5 `prop` bodies (Rule Q5.5)

The body of a `prop name: expr` declaration is checked at **quantity 0**. All free occurrences of bindings inside a prop body contribute their usage at quantity 0 — i.e., they do not count for the binding's runtime quantity.

This makes the compile-time verification (or PBT generation per `spec/properties.md`) sound: no runtime code is generated for a prop, and references to runtime values inside a prop do not force those values into the runtime usage count.

### 5.6 Lambda parameters (Rule Q5.6)

Same as Rule Q5.2: a lambda parameter's quantity is the sum of its usages in the body. Lambda *captures* of outer-scope bindings count toward the outer binding's quantity according to Rule Q5.7 below.

```
let inc = (x: i64) -> x + 1        // x is quantity 1 within inc's body
let by_two = (x: i64) -> x + x     // x is quantity ω
```

### 5.7 Captures by closures (Rule Q5.7)

A closure captures a binding `y` from the enclosing scope. The closure's body uses `y` with some quantity `q`. The closure value itself is then used at the call site with some quantity `q'` (per Rule Q5.2). The total quantity contributed to `y` by the closure is `q · q'` (multiplication, §3.2).

```
let y = compute()                  // quantity TBD
let g = (x) -> x + y               // closure body uses y once → q = 1
g(1) + g(2)                        // g used twice → q' = ω; so y contributes 1·ω = ω
```

Because closures may be called arbitrarily many times by their own callers (their `q'` is generally `ω`), captured bindings typically end up at quantity ω. A closure called exactly once preserves its captured bindings' quantities.

### 5.8 Pattern bindings (Rule Q5.8)

Each binding introduced by a pattern (`let (a, b) = expr`, `match x: List.Cons(h, t) => …`) gets its quantity inferred per Rule Q5.1 from its usages in the scope of the binding.

For match arms specifically: a binding's quantity within the arm body follows Q5.1; the binding does not exist outside the arm.

### 5.9 Branching: `if`, `match`, `handle` (Rule Q5.9)

For an expression with **mutually exclusive branches** — `if c: A else: B`, or `match e: pat1 => a1 | pat2 => a2 | …`, or handler arms in a `handle` — a free binding's contribution is the **join** (`⊔`, §3.3) over all branches, *not* the sum.

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

`resume(v)` inside a handler arm body is **always quantity 1** (single-shot). The arm body must contain `resume(...)` exactly zero or one time on every code path; two or more `resume` calls reachable on a single path is a compile error.

```
handle action():
    Eff.op(x) =>
        let v = compute(x)
        resume(v)              // exactly one resume → OK
    Eff.fail(e) =>
        Result.err(e)          // zero resumes → OK; arm produces a final value
```

Multi-shot handlers (where `resume` may be called any number of times) are not supported in v0.4.1. Adding them would require relaxing this rule to allow `resume` at quantity ω, with corresponding changes in the runtime continuation representation. Tracked as **OQ-Q1** below.

### 5.12 Default-bodied cap methods (Rule Q5.12)

When a cap method carries a default body (Rule 6.1 in type_system.md), the body's quantity inference runs in two passes:

1. **At the cap declaration:** the body is analysed against the cap's abstract `Self`, treating each `Self.fn` constructor and abstract method as a function with worst-case (quantity-ω) parameter quantities. This produces a *quantity skeleton* for the default body.
2. **At each satisfying type:** the satisfying type's concrete method quantities are substituted into the skeleton, and the body is re-checked. If the satisfying type's methods have quantities tighter than ω, the default body may inherit tighter quantities for its captured arguments.

If a satisfying type provides an explicit `satisfies`-block override (an `InstanceMethodDef`), the default body is not used; quantities are computed entirely from the override.

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

Violations emit `error[E070]: erased binding used at runtime`, citing the offending position.

### 6.2 Materialisation (Rule Q6.2)

Some quantity-0 bindings carry compile-time-known values (e.g., `of` value-params, Rule Q5.3). When the typer encounters a runtime-position read of such a binding (e.g., `b.lo` in arithmetic context), the read is **materialised** as the compile-time constant — *not* a violation of erasure, because the binding contributes no runtime layout; the constant is inlined directly.

Materialisation is bounded to cases where the value is statically known. A quantity-0 binding without a static value (e.g., a phantom type, which has no value at all) cannot be materialised and remains pure type-level.

## 7. Linearity (quantity 1)

A quantity-1 binding is **used exactly once**. The compiler verifies this and emits errors for zero or multiple uses.

### 7.1 Linearity-check (Rule Q7.1)

For every quantity-1 binding `x`:

1. Across all reachable code paths, `x` is consumed exactly once. (Branches are checked using `⊔` per Rule Q5.9.)
2. `x` is not captured by a closure of quantity ω (since the closure may run multiple times). It may be captured by a quantity-1 closure.

Violations: `error[E071]: linear binding used <0 or 2+> times`.

### 7.2 FBIP / in-place mutation (Rule Q7.2)

A quantity-1 binding's last use is the only use, so the underlying memory may be **reused in place** rather than allocated fresh. This is the hook for Functional-But-In-Place (FBIP) optimization.

When a constructor is applied to a quantity-1 receiver of the same shape, Perceus may emit a reuse rather than allocate. Details in `spec/perceus.md`.

### 7.3 No RC overhead (Rule Q7.3)

A quantity-1 binding has no `dup`/`drop` operations inserted by Perceus. The single use consumes the value; there is no need to count references.

## 8. Unrestricted (quantity ω)

A quantity-ω binding is **freely shared**. The Perceus pass inserts:

1. **`dup` operations** at every share point (when a binding is used in more than one downstream position).
2. **`drop` operations** at every consumption point that is not the last use.
3. **Reuse opportunities** at the last use, when the value's representation matches a constructor application of the same shape.

Quantity ω is the default for most user code and bears no programmer obligation.

## 9. Composition under operations

When a binding is consumed at a function-call argument position, the binding's contribution to the call site is `q_arg · q_param`, where:

- `q_arg` is the quantity at which the argument is observed in the caller's context.
- `q_param` is the quantity inferred for the parameter from the callee's body (Rule Q5.2).

```
fn pair_double(x: i64) -> i64 = x + x       // x: ω

fn caller(y: i64) -> i64 =
    pair_double(y)                            // y consumed at quantity 1 (one arg position)
                                              // ·  pair_double's param x has quantity ω
                                              // = y contributes ω to caller
```

For a chain of nested calls, quantities multiply along the chain.

## 10. Worked examples (from `examples/*.fixed`)

### 10.1 `examples/01_basics.fixed` — `fib_iter`

```
fn fib_iter(n: N is Numeric) -> N =
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

So `go`'s parameters have quantities `(i: ω, a: 1, b: ω)`. `a` is linear; `i` and `b` are unrestricted.

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
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi

let x: Bounded of (i64, 0, 10) = Bounded.Bounded(5)
let result = x.value + x.lo                     // x.value: runtime read; x.lo: compile-time constant
```

- `lo` and `hi` are `of` value-params → quantity 0 (Rule Q5.3).
- `x.lo` in `x.value + x.lo` is a quantity-0 read in a runtime arithmetic context. Per Rule Q6.2, the typer **materialises** `x.lo` as the compile-time constant `0` (from the type `Bounded of (i64, 0, 10)`). The arithmetic becomes `x.value + 0`, which the optimiser may further simplify.
- `x.value` is a regular runtime field of quantity ω (or 1 if `x` is consumed exactly once after).

## 11. Open questions

- **OQ-Q1 — Multi-shot handlers.** Rule Q5.11 fixes `resume` at quantity 1. Real-world non-determinism handlers (probabilistic programming, backtracking, async schedulers with replay) need quantity-ω resume. Lifting requires a continuation representation that survives multiple invocations and a quantity inference that admits `resume` at ω. Defer to v0.5+ when a use case lands.

- **OQ-Q2 — User-declared quantities on parameters.** Rule Q5.2 infers parameter quantities from body usage. Should the user be able to declare `linear` parameters explicitly, both as documentation and as a way to demand FBIP? Trade-off: explicit annotations reduce inference surprise but leak QTT into the surface syntax. Fixed has so far avoided this. Revisit once examples reveal a need.

- **OQ-Q3 — Closure-of-closure quantities.** Rule Q5.7's multiplication composes through one closure layer. For nested closures (a closure that returns a closure that captures the outer scope), the composition is straightforward by induction, but worked examples will reveal corner cases. Add concrete worked examples in v0.2 of this doc once the typer is implemented.

- **OQ-Q4 — Quantity polymorphism.** A truly generic `id : T -> T` could be quantity-polymorphic — the parameter's quantity equals the call site's. Idris 2 has limited quantity polymorphism; Fixed has so far inferred everything. Decision deferred until benchmark code shows the need.

- **OQ-Q5 — Effect operations and quantities.** Effect ops (e.g., `Console.print_line`) currently follow Rule Q5.2. Whether *effect rows* themselves participate in quantity (e.g., a "linear effect" that may be performed at most once) is a separate research question, related to OQ-Q1.

## 12. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | Syntactic forms whose bindings are governed by these rules |
| `spec/type_system.md` | Rule 4.1, §5.9, Rule 6.1, §7.3.b — type-level rules referenced here |
| `spec/properties.md` (TBD) | Static-vs-PBT verification of `prop` bodies; this doc only states they're checked at quantity 0 (Rule Q5.5) |
| `spec/effects.md` (TBD) | Effect-row composition; this doc fixes only `resume`'s quantity (Rule Q5.11) |
| `spec/pattern_matching.md` (TBD) | Match desugaring; pattern bindings (Rule Q5.8) follow standard rules |
| `spec/perceus.md` (TBD) | Consumes quantity-ω bindings → dup/drop; quantity-1 bindings → reuse-in-place; quantity-0 bindings → erased before Perceus runs |
| `docs/references/scala3-compiler-reference.md` | The Recheck pattern (§5.4) that this pass adopts |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `qtt/QuantityChecker.scala` (Recheck phase) |
