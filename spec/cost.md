# Fixed Cost Contracts

**Status:** Draft v0.2.1
**Specifies:** what a cost contract bounds (steps, constructions, releases), how one is written (cost props, `fip`), the size index, how cost is inferred and verified, when a change to a contract is an ABI change, and how representation selection uses contracts and measured costs.
**Last revised:** 2026-11-20

**Changes from v0.2.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): the cost checker makes its own solver call and cost props skip the static prover (Rule CT6.2); the pool's satisfaction is resolved at the site that created the choice point, the link check is said to catch version skew and implicit satisfactions, and an open prop does not make a candidate non-viable (Rule CT8.1); "per satisfaction" replaces "per satisfying type".

**Changes from v0.1** (`docs/decisions/P6-resolution-round-2026-11-20.md`, D4, D5, D6, D10, D12).
- **The selector's score is usage × cost** (Rule CT8.2, rewritten). Usage per capability method and the cost of one call are measured where data exists (`spec/properties.md` Rule P7.9, `spec/compilation_model.md` Rule CM8.6); the bound-class table (Rule CT8.3) and the static weights are the fallback. `K < ε` now holds for the fallback only; measured changes are covered by the lock file's hysteresis (`spec/type_system.md` Rule 6.5.5). A tie no longer halts.
- **Viability moves to application link** for the choice points bound there (Rule CT8.1). Zero viable candidates is a link error. At package compile a call on a choice-point value costs the requirement's contract, and `?` where it states none (Rule CT6.1).
- **Lanes** (Rule CT6.5, new). A cost prop never justifies a decision that is wrong if the prop is false; contracts and measured costs feed only the measurement lane of `spec/properties.md` Rule P7.8. A `tested` exact bound is a measurement; an asymptotic bound stays `proved`, failed or `assumed`.
- **`clone`** (Rule CT4.5, new): the cost prop of a satisfaction's `clone` tells whether it shares or copies.
- **Candidates are satisfactions** (Rule CT8.1): two satisfactions of the same (type, capability) are never ranked against each other.
- Diagnostics are not steps (Rule CT3.5). A reference cost table shipped by a package is measurement, not contract (Rule CT7.1).

**Why this document exists.** In Fixed the programmer does not pick a data structure, so the source alone does not say what an operation costs: `get(i)` is constant on an array and linear on a list. Cost is therefore a **contract on capability members**. A representation that cannot meet a capability's contract cannot satisfy the capability; among those that can, the contracts, and measured costs where they exist, tell representation selection how they differ. The decisions behind this document are recorded in `docs/decisions/P3-cost-contracts.md`.

## 1. Scope

This document specifies:

- The three quantities a contract bounds, and why they do not depend on ownership, lifetime class or version (§3).
- Cost props and the `fip` modifier as the two ways to state a contract (§4).
- The size index and symbolic cost terms (§5).
- Inference of cost on Core, verification, and statuses (§6).
- Compatibility of contracts across versions of a package (§7).
- The use of contracts and measured costs in representation selection (§8).

It does **not** specify:

- A cost model for machine time. A step is an abstract unit (§3.1).
- Peak memory. `constructs` counts cells built, cumulatively; the high-water mark is a different analysis and is not specified (OQ-CT3).
- Contracts on plain functions beyond `fip`. They wait for function-level props (`spec/properties.md` §3.3).

## 2. Dependencies and prior art

- `spec/type_system.md` — §6.1 (capability members), §6.5 (representation selection), §7.4 (derived `fold` / `unfold`: the only loops).
- `spec/quantities.md` — §4.1 (demands, `exclusive` parts).
- `spec/memory_management.md` — §11 (reuse), §18 (`fip`), §20.3 (Core).
- `spec/properties.md` — §3 (where props appear), §7 (verification, statuses).
- `spec/compilation_model.md` — the interface tier that carries contracts (Rule CM7.1), ABI changes (Rule CM6.7), choice points and their binding at link (Rules CM8.1, CM8.2, CM8.4), usage summaries (Rule CM8.3), the lock file (Rule CM8.5), the profile file and measured costs (Rule CM8.6).
- `spec/properties.md` — Rules P7.8 (lanes) and P7.9 (measurement during testing).

Prior art, cited from general knowledge and not re-checked for this draft:

- Hofmann and Jost; Hoffmann, Aehlig, Hofmann — automatic amortised resource analysis (AARA, Resource Aware ML): polynomial bounds inferred by solving linear constraints that typing generates. Fixed needs less machinery than that, because its only recursion is `fold` and `unfold` (§6.1); AARA is the place to look if the simple inference proves too weak.
- Wang, Wang, Chlipala — TiML: cost bounds as indices in types, discharged by an SMT solver. The model for §6.2's static path.
- Danielsson's `Thunk` monad and the Agda developments that follow it (cost monads, complexity signatures) — bounds certified by hand-written proofs. Fixed takes the idea of a bound in the interface and not the proof burden.
- Lorenzen, Leijen, Swierstra — FP²: `fip` and `fip(n)`, of which the allocation contract here is the symbolic generalisation.
- Okasaki — amortised bounds do not survive sharing. The reason Rule CT3.4 ties in-place bounds to exclusive ownership.
- The complexity requirements the C++ standard places on containers and iterator categories — cost as part of an interface.

## 3. What a contract bounds

### 3.1 Steps (Rule CT3.1)

A **step** is one of: a call of a function, method or closure; a primitive operation; a constructor application; a `match` or `if` dispatch.

`steps(m)` is the number of steps one call of `m` performs, including the steps of everything it calls.

### 3.2 Constructions (Rule CT3.2)

`constructs(m)` is the number of **cells** one call of `m` builds, including the cells built by everything it calls: constructor applications whose result is a reference (`spec/compilation_model.md` Rule CM3.3), closures with captures, and strings it creates. Nullary variants and immediates build no cell.

`constructs` does not say where a cell is placed. That is decided at each call site by the destination and its lifetime class (`spec/memory_management.md` §5, §7.1): the same construction is a bump of a data stack for one caller and a heap allocation for another.

### 3.3 Releases (Rule CT3.3)

For each parameter part on which `m` has an `exclusive` demand (`spec/quantities.md` Rule Q4.1.a), `releases(m)` counts the constructions of `m` that reuse a cell of that part when the caller exclusively owns it (`spec/memory_management.md` §11). `releases(m)` without a part is the sum over all parts.

By definition `releases(m) ≤ constructs(m)`.

### 3.4 Allocation at a call site (Rule CT3.4)

A contract states `constructs` and `releases`. Neither depends on what the caller owns. The compiler derives the cells a call actually takes from its destination:

| The caller passes the `exclusive` parts as | Fresh cells |
|---|---|
| exclusively owned | `constructs(m) − releases(m)`, guaranteed |
| shared, `Heap` | between that and `constructs(m)`, decided at run time by the `rc == 1` test (Rule PR6.3) |
| shared, any other class | `constructs(m)` |

This is why no quantity or ownership appears in the surface syntax (`spec/quantities.md` OQ-Q2): the contract is about the callee, and the call site supplies the rest.

A `fip` callee is a special case of the second and third rows: its shared entry first copies the demanded part of the shared input (`spec/compilation_model.md` Rule CM5.5), so the call takes the size of that part in addition to `constructs(m) − releases(m)`.

An in-place bound (`constructs(m) − releases(m) ≤ 0`) holds only for callers that exclusively own the input. For the same reason an amortised argument is valid only there: a shared value can be used again after the expensive step.

### 3.5 Contracts do not depend on the version (Rule CT3.5)

Witness operations (`spec/compilation_model.md` §4), reference-count operations and diagnostics (`spec/diagnostics.md` Rule DG3.3) are not steps and build no cell. A call that the link step binds to a chosen satisfaction (`spec/compilation_model.md` Rule CM8.2) costs what a direct call costs. A contract therefore says the same about a function's baseline version and about each of its clones. What a clone saves is the plumbing, which a contract does not count and the reports of `spec/memory_management.md` §19 do.

## 4. Stating a contract

### 4.1 Cost props (Rule CT4.1)

A **cost prop** is a `prop` (`spec/properties.md` §3) whose body is a comparison with a cost term on the left:

```
prop <name>: <cost term> <= <bound>

<cost term> ::= steps(<member>)
              | constructs(<member>)
              | releases(<member>)
              | constructs(<member>) - releases(<member>)
```

`<member>` is the name of a member of the enclosing `cap` or `data` declaration, including a member the data type gets from a `satisfies` block or by derivation. It is named, not called.

```
cap RandomAccess of (Part, Index is Numeric):
    fn get(index: Index) -> is Optional of Part

    prop get_steps: steps(get) <= O(1)

cap Functor:
    fn map(f: Part -> B) -> Self of B

    prop map_steps:    steps(map) <= O(size(self) * steps(f))
    prop map_cells:    constructs(map) <= size(self) + size(self) * constructs(f)
    prop map_in_place: constructs(map) - releases(map) <= size(self) * constructs(f)
```

Cost props need no grammar change: `steps(get)` is an application and `<=` a comparison (`spec/syntax_grammar.ebnf`, `AppExpr`). The names `steps`, `constructs`, `releases`, `size`, `O`, `log` and `max` have their cost meaning only in a prop whose left side is a cost term; elsewhere they are ordinary identifiers. This surface is provisional (OQ-CT1).

### 4.2 Bounds (Rule CT4.2)

A **bound** is built from:

- non-negative integer literals;
- size terms `size(self)` and `size(p)` for a parameter `p` of the member (§5.1);
- latent cost terms `steps(f)` and `constructs(f)` for a function-typed parameter `f` (§5.2);
- `+`, `*`, `log(e)` and `max(e1, e2)`.

A bound is **exact** when written bare (`<= size(self) + 1`) and **asymptotic** when wrapped (`<= O(size(self))`). `c <= O(e)` means there are constants `k` and `n0` such that `c ≤ k · e` whenever every size term is at least `n0`.

The **class** of a bound is the first of these that contains it: `O(1)`, `O(log n)`, `O(n)`, `O(n log n)`, `O(n²)`, *other*. With several size terms, `n` stands for their sum. Latent cost terms count as constants for the class: they are the caller's to bound.

### 4.3 `fip` is a contract (Rule CT4.3)

`fip fn` (`spec/memory_management.md` §18) states, among its other conditions, `constructs − releases ≤ 0` with every reused part exclusively owned. `fip(n) fn` states `constructs − releases ≤ n` for a literal `n`, and is otherwise `fip`. Cost props are the symbolic generalisation: a bound that depends on sizes, and bounds on steps.

The `fip` modifiers may be written on a capability **method requirement** and on a `satisfies` **method**, not only on a function declaration (`spec/syntax_grammar.ebnf` `FipModifier`). On a requirement it obliges every satisfaction. It is also what lets a `fip fn` call that method through a capability table (`spec/compilation_model.md` Rule CM4.7). This resolves `spec/memory_management.md` OQ-MM1.

### 4.4 Where a contract may be stated (Rule CT4.4)

| Place | Meaning |
|---|---|
| on a capability | An **obligation**: every satisfying type's member must meet it (§6.3). A caller that sees only the capability may rely on it. |
| on a `data` declaration | A **claim** about that type's member. It may be tighter than the capability's. Callers that know the type may rely on it. |
| on a function (`fip`, `fip(n)`, `fip(strict)`) | An obligation on that function. |

A member with no contract has an inferred cost (§6.1), which is reported and is not a promise.

### 4.5 `clone` (Rule CT4.5)

`clone` returns a value equal to its receiver (`spec/stdlib.md` §4.11). The satisfier chooses whether it **shares** (the result is the same cell, with its count raised) or **copies** (the result is new cells), and its cost prop says which:

```
Rope satisfies Clone:
    fn clone -> Self = self                       // shares
    prop clone_cells: constructs(clone) <= 0

Buffer satisfies Clone:
    fn clone -> Self = …                          // copies
    prop clone_cells: constructs(clone) <= size(self)
```

The capability `Clone` states only the copying bound, so that both kinds satisfy it. A caller that knows the type relies on the claim of its satisfaction (Rule CT4.4); the selector sees it as the cost of `clone` (Rule CT8.2). A satisfaction with no cost prop on `clone` has the inferred cost of its body. A type whose values must not be duplicated does not satisfy `Clone`.

## 5. Size and symbolic terms

### 5.1 The size index (Rule CT5.1)

`size(x)` is the number of elements a `fold` over `x` visits. For a value of a recursive `data` type that is the number of cells along its recursive positions; for a non-recursive value it is 1.

The size index is **erased**: it has demand `0` and is never computed at run time. It exists so that a bound can mention it. A capability that also offers a `size` method (`Sized`) may relate the two with an ordinary prop.

For a value seen only through a capability bound, `size(x)` is abstract: the contract is stated in terms of it and each satisfying type interprets it by its own `fold`.

### 5.2 Cost of a function-typed parameter (Rule CT5.2)

The cost of calling a parameter `f` is not known where the member is declared. A bound mentions it symbolically: `steps(f)` and `constructs(f)` are the cost of **one** call of `f`. The member's own contract then says how many calls it makes (`size(self) * steps(f)`).

Cost terms are not like effect rows. A row is an idempotent set and is charged where a callback is passed (`spec/effects.md` Rule E5.5.a). Cost is additive and scales with the number of calls, so it stays a term in the bound until a call site supplies the callback and its cost.

### 5.3 Cost of an effect operation (Rule CT5.3)

The cost of performing `E.op` depends on the handler, which the performer cannot see. In a bound it is the symbolic term `steps(E.op)`. It is resolved where a `handle` supplies the clause. Installing a handler and capturing a continuation are steps and constructions of the `handle` expression, counted where it appears (`spec/memory_management.md` §14).

## 6. Inference and verification

### 6.1 Inferred cost (Rule CT6.1)

The compiler infers `steps`, `constructs` and `releases` for every function from the Core of its baseline version (`spec/memory_management.md` §20.3), by these rules:

- a sequence adds; a branch takes the maximum over its arms;
- a direct call adds the callee's inferred cost; a call through a capability table, and a call on a value of a choice point that the package does not bind itself (`spec/compilation_model.md` Rules CM8.1, CM8.2), add the requirement's **declared** contract, or the unknown cost `?` if it has none; a call of a function-typed parameter adds the symbolic term of §5.2;
- a derived `fold` over `x` with step `s` costs `size(x) · (cost(s) + k)` for a constant `k`; a derived `unfold` costs the same in the size of its result;
- a constructor application adds one construction; one that the reuse analysis pairs with a cell of an `exclusive` part (`spec/memory_management.md` §11) also adds one release.

Functions cannot call themselves, so the only source of a size factor is a `fold` or an `unfold`. The inference is therefore syntax-directed: it solves no recurrences.

A call on a choice-point value costs the contract and not the chosen satisfier's cost, because the choice is made after the package is compiled and the package is compiled once for every choice. Where the requirement states no contract the term is `?`, and a cost prop that depends on it continues at step 2 of Rule CT6.2: an exact bound may be `tested` in the test build, under the satisfaction that the test build's link chose (`spec/properties.md` Rule P7.4); an asymptotic bound is `assumed`. The link step reads no bodies and does not infer cost again; the link report (`spec/reports.md` Rule RP5.4) shows the chosen satisfier's claims beside the contract.

The size of a result is known where the member that built it says so (a `map` keeps the size; a constructor adds one). Where it is not known the result's size is a fresh symbol, and a bound that depends on it is `?`.

### 6.2 Verification and status (Rule CT6.2)

A cost prop is verified like any prop (`spec/properties.md` §7) and gets a status (Rule P7.7):

1. **Static.** The cost checker, on Core, compares the inferred cost with the declared bound symbolically; if that does not decide, it passes the comparison to the solver in a call of its own. Cost props do not go through the static prover of `spec/properties.md` Rule P7.1.
   - The inferred cost is within the bound: `proved`.
   - The inferred cost is known and exceeds the bound: the prop **fails**, `error[E120]: cost bound not met`, with the inferred cost and the construct that produced the excess. The inferred cost is an upper bound, so this can reject an implementation whose real cost is within the bound; the author then restructures it or states a weaker bound.
   - The inferred cost is unknown (`?`, Rule CT6.1): continue with step 2.
2. **Testing, exact bounds only.** A test build counts steps and constructions around a call. For generated inputs the driver evaluates the bound at the inputs' sizes and compares. No counterexample gives `tested`.
3. **Otherwise** the prop is `assumed`.

Property-based testing cannot refute an asymptotic bound: a finite sample fixes neither `k` nor `n0`. A bound written with `O(…)` is therefore `proved`, failed by step 1, or `assumed`; it is never `tested`. A `tested` exact bound is a measurement of the member on the generated inputs (`spec/properties.md` Rule P7.9) and belongs to the measurement lane (Rule CT6.5). For an `assumed` asymptotic bound the test build may still measure the member at doubling sizes and report **W120** (measured growth exceeds the declared class). W120 is a warning, because the measurement proves nothing.

A failed exact bound is an error with a counterexample, as for any prop (Rule P8.4).

### 6.3 Obligations of a satisfaction (Rule CT6.3)

When `T satisfies C` is processed, each member of `T` that implements a member of `C` must meet every cost prop `C` declares for it, and must be `fip` where the requirement is. The inferred cost of `T`'s member is checked against the bound by §6.2, per (prop, satisfaction) pair (Rule P7.7.a).

This check replaces the per-satisfaction re-check of default bodies for cost: a default body is compiled once against the capability's contracts (`spec/type_system.md` Rule 6.1.1).

### 6.4 Reporting (Rule CT6.4)

`fixed compile --explain-memory` (`spec/memory_management.md` §19) adds, per function: the inferred `steps`, `constructs` and `releases`, with `?` where unknown and the call that made it unknown; and per cost prop its status. `--format json` gives the same data.

### 6.5 Contracts are in the measurement lane (Rule CT6.5)

`spec/properties.md` Rule P7.8 separates decisions that are wrong if a claim is false (the facts lane) from choices among alternatives that are all correct (the measurement lane). Every use of a cost contract is in the measurement lane: viability (Rule CT8.1), the score (Rule CT8.2), clone weights and the budget. A wrong contract makes a program slower, never wrong. What code does at a call site (whether a cell is reused, whether a branch is removed) is decided from the inferred cost and the reuse analysis of the code itself (Rules CT3.4, CT6.1), never from a cost prop.

So the status of a cost prop never licenses a fact, and a `tested` cost prop, which is a measurement, is used as one. An asymptotic bound is `proved`, failed or `assumed` (Rule CT6.2); an `assumed` bound is taken as stated for selection, and W120 reports a measured growth that contradicts it.

`fip` is not a cost prop in this sense: it is checked on the code (Rule CT4.3), and its failure is an error, not a status.

## 7. Compatibility

### 7.1 Changing a contract (Rule CT7.1)

Contracts are part of a package's interface (`spec/compilation_model.md` Rule CM7.1).

- **Tightening** a bound, adding a cost prop to a `data` or `satisfies` block, or adding `fip` to a function is compatible.
- **Weakening** a bound, removing a cost prop, or removing `fip` is an ABI change: importers and their representation choices relied on it.
- **Adding** a cost prop or `fip` to a capability requirement is an ABI change for packages that satisfy the capability, and compatible for packages that only use it.
- A change of an **inferred** cost that no contract covers is compatible. It shows in a comparison of two interfaces as a performance change.
- A **reference cost table** that a package ships in its interface (Rule CT8.2) is measurement, not contract. It is labelled with its target, a change to it is compatible, and it is not part of the content hash of `spec/compilation_model.md` Rule CM7.1, so a package built on two machines has one interface.

## 8. Contracts and measured costs in representation selection

### 8.1 Viability (Rule CT8.1)

The selector's **candidates** at a choice point (`spec/compilation_model.md` Rule CM8.1) are satisfactions. For each data type that satisfies the bound set, the one satisfaction that the resolution order of `spec/type_system.md` Rule 8.4 picks is the candidate: at the site that created the choice point, by levels 1–4 there; if that leaves several, by levels 5–6 at link. Two satisfactions of the same (type, capability) are never ranked against each other, because they differ in results and not in speed.

A candidate that fails a cost prop of a capability in the bound set (Rule CT6.2, step 1) is **not viable**. A candidate whose cost prop is `tested` or `assumed` meets it, and an open prop (`spec/properties.md` Rule P7.1, step 3) does not make a candidate non-viable: a wrong cost is a performance fault, not an unsound program (Rule CT6.5).

Viability is decided where the choice is made:

- **At package compile**, for a choice point whose values never leave the package (`spec/compilation_model.md` Rule CM8.4).
- **At application link**, for every other choice point. The link step checks each candidate against the cost props that the interface tier records for the requirements of the capabilities in the bound set, with the status the candidate's package recorded. It reads no bodies. A declared satisfaction that fails a cost prop is already rejected when its package is compiled (Rule CT6.3, `E120`), so the check at link catches what no package compile could: version skew (a candidate compiled against another version of the capability than the one linked) and implicit satisfactions (derived and built-in) that no package compile checked against these props.

Zero viable candidates at a choice point bound at link is a **link error** that names the choice point, the bound set and, per candidate, the cost prop it failed. It is the only error selection can produce (`spec/type_system.md` Rule 6.5.5).

This is where contracts decide. If `RandomAccess` promises `steps(get) <= O(1)`, a linked list never competes for a value bounded by `RandomAccess`: its `get` is a `fold` over the list, its inferred cost is `O(n)`, and the prop fails.

### 8.2 The score is usage × cost (Rule CT8.2)

Among viable candidates, the selector ranks by **expected cost** (`spec/type_system.md` Rule 6.5.4):

```
expected(p, r) = Σ_m usage(p, m) × unit(m, r)
```

over the capability methods `m` called on values of the choice point `p`. Lower is better.

- `usage(p, m)` is how often `m` is called on values of `p`. The link step adds the usage vectors of the summaries along the edges of `spec/compilation_model.md` Rule CM8.3. Its source is the first that exists, in this order: a **production profile**; the **test-run usage** recorded by `spec/properties.md` Rule P7.9; the **static weights** of the summaries (the `W^d` weights of Rule CM5.4).
- `unit(m, r)` is the cost of one call of `r`'s member `m` at the size class the profile shows for `p`. Its source is the first that exists: a measurement of the application on its own target (the profile file, Rule CM8.6); the **reference cost table** that `r`'s package ships in its interface, if its target label matches; otherwise none, and the static fallback below applies.

Measured and static numbers are never mixed in one comparison. When some viable candidate at `p` has no measured `unit` for a method `p` uses, every candidate at `p` is scored by the **static fallback**: the derived dimensions below, combined with the other dimensions of Rule 6.5.4.

**Static fallback.** Let the **written bounds** be the capabilities of the bound set as the program states them, before `extends`-closure. For a candidate `r` and a written bound `c`, let `steps_fit(c, r)` be the usage-weighted mean, over the members of `c` (inherited ones included) that `p` calls, of the table value (Rule CT8.3) of `r`'s bound on `steps` of that member; if `p` calls no member of `c`, the lowest such value over all members of `c`. `cells_fit(c, r)` is the same for the bounds on `constructs − releases`. A member with no bound counts as *other*. A written bound with no members (a refinement capability) is skipped.

```
access_fit(r)      = max(0, 10 − 0.6 × Σ_c (10 − steps_fit(c, r)) / 10)
contract_cells(r)  = max(0, 10 − 0.6 × Σ_c (10 − cells_fit(c, r)) / 10)
allocation_cost(r) = min(class_score(r), contract_cells(r))
```

`class_score(r)` is the placement score of the value's lifetime class (`spec/memory_management.md` §3.1). Provisional values: `Inline` 10, `Static` 9, `Frame` and stack `Dest` 8, `Region` 5, `Heap` 3, less the charge for expected copy-out of a packed candidate (§16.2).

`steps_fit` and `cells_fit` are means of values in `[0, 10]`, so each written bound still moves each derived dimension by at most 0.6 raw points, whatever the usage, the other candidates and the capabilities the bound inherits. With the weights of Rule 6.5.4 (0.30 each), adding one bound moves a candidate's total by at most `0.36`, which keeps `K < ε`.

**Stability.** `K < ε` holds for the static fallback, and says that one added bound cannot turn a clear winner into a clear loser. It can turn a clear winner into a tie; a tie does not halt, and the choice recorded in the lock file stays while its score is within `ε` of the best (`spec/type_system.md` Rule 6.5.5; `spec/compilation_model.md` Rule CM8.5). Measured scores have no Lipschitz bound: a new profile can move them by any amount. The lock file's hysteresis is the only thing that keeps them from flipping a choice on noise, and a build that reads the lock file and no new profile makes the same choices (`spec/reports.md` Rule RP5.3).

### 8.3 Table of classes (Rule CT8.3)

| Class | Raw value |
|---|---:|
| `O(1)` | 10 |
| `O(log n)` | 8 |
| `O(n)` | 5 |
| `O(n log n)` | 3 |
| `O(n²)` | 1 |
| other, or no bound | 0 |

The table is the static fallback of Rule CT8.2; measured costs replace it where they exist. The values are provisional, like the weights of Rule 6.5.4. Rule CT8.2's factor `0.6` must be lowered if the weights of the two derived dimensions are raised, so that `K < ε` survives.

### 8.4 Across a package edge (Rule CT8.4)

A package cannot see how an importer uses a value it returns, nor how a dependency implements a capability it calls. What crosses the edge: a callee's declared bounds, a capability's obligations and a `data` type's claims, in the interface tier; per choice point and per parameter, the usage vectors of the summaries (`spec/compilation_model.md` Rule CM8.3); and, optionally, a reference cost table. The package selects for the choice points whose values never leave it. The link step selects for the rest, from these summaries and the profile file, and writes the result to the lock file.

## 9. Standard library

`spec/stdlib.md` §4.12 lists the contracts of the core capabilities. They are provisional until the library is written in Fixed (implementation plan, Phase 5).

## 10. Open questions

- **OQ-CT1 — Surface.** Cost props reuse the `prop` form and seven contextual names. A dedicated clause on the member (`fn get(…) -> … cost steps O(1)`) would bind a contract to its member syntactically. Wait for use.
- **OQ-CT2 — Size relations.** Rule CT6.1 knows the size of a result only for derived members. A way to state `size(result)` in terms of the inputs (it is a postcondition, `spec/properties.md` §3.3) would let more bounds be `proved`.
- **OQ-CT3 — Peak memory.** A bound on live cells composes by maximum and needs liveness. Not specified.
- **OQ-CT4 — Expected versus worst case.** A hash map's lookup is constant in expectation and linear in the worst case. A contract states one bound; which one `Map` should state is open (`spec/stdlib.md` OQ-S2).
- **OQ-CT5 — Contracts on functions.** Needs function-level props.
- **OQ-CT7 — Unit of a measured cost.** Rule CT8.2 compares measured costs of one call; whether the unit is cycles, or a weighted sum of cycles, cache misses and allocations, is not fixed. Cycles alone hide the cost a candidate imposes on the rest of the program through the cache.
- **OQ-CT6 — How often `?` appears.** If most inferred costs are unknown, the static path of Rule CT6.2 proves little. To be measured on the examples once inference exists.

## 11. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | §6.5.3 viability, Rule 6.5.4 (score) and Rule 6.5.5 (disambiguation) use §8; Rule 8.4 resolves which satisfaction is a candidate; Rule 6.1.1 uses Rule CT6.3 |
| `spec/quantities.md` | `releases` is defined on `exclusive` parts (Rule Q4.1.a); Rule Q4.1 lists cost props beside `fip` as the ways to state a demand |
| `spec/memory_management.md` | §11 reuse pairs give `releases`; §18 `fip` is the zero case; §19 carries the report |
| `spec/properties.md` | Cost props are props: §3 placement, §7 verification, Rule P7.7 statuses; Rule P7.8 lanes; Rule P7.9 measurement |
| `spec/compilation_model.md` | Contracts travel in the interface tier; Rule CM6.7 makes weakening an ABI change; Rule CM4.7 on `fip` through a table; Rules CM8.1–CM8.6 on choice points, summaries, selection at link, the lock file and the profile file |
| `spec/reports.md` | Rule RP5.3 (the lock file and the profile file as build inputs), Rule RP5.4 (the link report) |
| `spec/diagnostics.md` | Rule DG3.3: diagnostics are not steps |
| `spec/stdlib.md` | §4.12 contracts of the core capabilities |
| `docs/decisions/P3-cost-contracts.md` | The decision and the alternatives rejected |
