# Fixed Perceus Reference Counting

**Status:** Draft v0.1
**Specifies:** the reference-counting strategy, RC-insertion algorithm, reuse analysis (FBIP), drop specialization, borrowing inference, and effect-handler memory management for Fixed v0.4.4 source.
**Last revised:** 2026-04-30

## 1. Scope

This document specifies how the compiler manages heap memory for runtime values: when reference counts are incremented or decremented, when memory is reused in place, when allocations and frees are elided, and how all of the above interacts with QTT quantities, capabilities, and effects. Concretely:

- The Perceus model: refcount headers, `dup`/`drop` primitives, last-use semantics (§3).
- Quantity-driven simplification — how 0/1/ω quantities from `spec/quantities.md` collapse into "no allocation / no RC / full RC" buckets (§4).
- The RC-insertion algorithm: A-normal form, live-set computation, dup at share points, drop at last use (§5).
- Reuse analysis (FBIP): when a constructor application can reuse the memory of a soon-to-be-dropped value (§6).
- Drop specialization: per-type drop functions, inlining, recursive-drop tail-call optimization (§7).
- Borrowing inference: identifying parameters that are read but not consumed (§8).
- Effect-handler memory: continuation lifetimes, single-shot drop, linear-effect handler scoping (§9).
- Mutually recursive data types' co-allocation (§10).
- Phase placement and IR contract (§11).

It does **not** specify:

- The LLVM IR emission details — see the implementation plan's Phase 4 deliverables.
- Allocator choice (system malloc, slab, arena, etc.) — implementation-defined.
- Atomic-vs-non-atomic refcount selection — implementation-defined; the compiler must produce correct code for either.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.4.
- `spec/type_system.md` v0.4.1 — §6.5 (representation selection), §6.6 (built-in candidates), §7.5 (mutual recursion / SCC).
- `spec/quantities.md` v0.2 — Rule Q5.X family for quantity inference; Rule Q6.1 (erasure soundness); Rule Q7.2 (FBIP / in-place mutation hook).
- `spec/effects.md` v0.2 — §6 handler operational semantics; §7 evidence-passing compilation; Rule E3.5 (linear effects).
- `spec/pattern_matching.md` v0.2 — §8 match compilation (decision-tree input to RC insertion).
- `spec/properties.md` v0.2 — §9 (verified props inform reuse / bounds-check elision).

Theoretical basis:

- Reinking, Xie, de Moura, Leijen, *"Perceus: Garbage Free Reference Counting with Reuse"*, PLDI 2021 — the core algorithm Fixed adopts. Fixed's contribution over base Perceus is integration with QTT quantities (§4).
- Lorenzen, Leijen, *"Reference Counting with Frame-Limited Reuse"*, ICFP 2022 — refinement of Perceus's reuse story. Influences §6.
- Wadler, *"Linear Types Can Change the World"*, IFIP 1990 — the linear-types intuition behind Rule Q7.2.

## 3. The Perceus model

### 3.1 Heap-object layout (Rule PR3.1)

Every heap-allocated runtime value carries a header:

```
{
    rc: i64,            // refcount; high bit reserved for atomic-mode flag
    tag: i8,            // variant discriminator for sum types; 0 for product types
    pad: [N x i8],      // padding to natural alignment
    payload: <variant fields>
}
```

Stack-allocated and quantity-0 values have **no header**. Stack allocation occurs when:

- The value's quantity is 1 (linear) and the data fits within a stack-frame budget (size threshold implementation-defined).
- The value is a phantom-typed marker (zero-size).
- A future stack-allocated-recursive-data candidate (deferred per type_system.md §6.5.9) applies.

### 3.2 `dup` and `drop` primitives (Rule PR3.2)

The IR has two RC primitives:

- `dup(x)` — increment `x`'s refcount. No-op for stack values, quantity-0 values, and quantity-1 values (statically known by type).
- `drop(x)` — decrement `x`'s refcount; if zero, recursively drop `x`'s fields and free the memory. No-op for stack values and quantity-0 values; quantity-1 values use a specialized `consume(x)` (Rule PR3.5).

`dup` and `drop` are emitted by the compiler at points determined by the algorithm in §5; they are not user-visible.

### 3.3 Last-use semantics (Rule PR3.3)

The Perceus invariant: for every binding `x` introduced into a scope at program point P, the compiler emits exactly the dup/drop pairs needed to balance refcount changes between P and the end of `x`'s lifetime. The owning scope is responsible for dropping; transferring ownership (e.g., passing `x` to a function as a non-borrowed argument) transfers the drop obligation.

The "last use" of a binding is the last program point in source/IR order where the binding is referenced. The compiler emits `drop` immediately after the last use (or at branch joins, per §5).

### 3.4 Quantity ω is the standard case (Rule PR3.4)

Bindings with QTT quantity ω (`spec/quantities.md` §3) carry standard Perceus RC. They have headers, dup/drop pairs, and full reuse-analysis treatment. Most user code lives here.

### 3.5 Quantity 1 is `consume` (Rule PR3.5)

A quantity-1 binding has no `dup` and no refcount-aware `drop`. Its single use is a **`consume`** — the value is moved (not copied) at the use site, and the binding is dead afterward. If the value's representation is stack-allocated, `consume` is a no-op (the stack frame holds it); if heap-allocated (a quantity-1 binding may still be on the heap due to size), `consume` transfers ownership.

This is what makes FBIP guaranteed for quantity-1 bindings: at the consume site, the memory is unique to this code path.

## 4. Quantity-driven simplification

Fixed's per-quantity strategy collapses three runtime treatments:

| Quantity | Allocation | RC ops | Reuse |
|---|---|---|---|
| **0** | None | None | N/A — value doesn't exist at runtime. |
| **1** | Stack or heap (size-driven) | No `dup`; no refcount `drop`. `consume` at use site. | Guaranteed FBIP — last use is the only use. |
| **ω** | Heap with refcount header | `dup` at share, `drop` at last use. | Conditional FBIP — at runtime, reuse if `rc == 1`. |

This integration is the main value of pairing QTT with Perceus: only ω-quantity values incur the full RC overhead. The compiler statically proves quantity-0 erasure (Rule Q6.1) and quantity-1 linearity (Rule Q7.1), so those bindings skip RC entirely.

For comparison, a system without QTT (e.g., Koka pre-Perceus integration) treats every value as ω and emits dup/drop for everything, relying on optimizer passes to eliminate redundant pairs. Fixed's QTT pre-pass eliminates them statically.

## 5. RC insertion algorithm

The compiler implements the Reinking et al. (2021) algorithm with the QTT-driven simplifications above. This section describes the algorithm operationally.

### 5.1 Input form (Rule PR5.1)

The Perceus pass runs after match-desugaring (`spec/pattern_matching.md` §8) and consumes the resulting **decision tree** plus full quantity annotations from the prior pass (`spec/quantities.md` §4).

Input invariants:

- Every binding is annotated with its inferred quantity.
- Every value has a chosen representation (heap struct, stack struct, primitive, etc., per `spec/type_system.md` §6.5).
- Match expressions are decision trees, not pattern arms.

### 5.2 A-normal form (Rule PR5.2)

The pass first converts the IR to **A-normal form (ANF)**: every non-trivial subexpression is named via `let`. This makes every argument position a simple variable reference, simplifying live-set tracking.

```
// Before ANF:
g(f(x), h(x))

// After ANF:
let v1 = f(x)
let v2 = h(x)
g(v1, v2)
```

After ANF, dup/drop placement reduces to "at every binding's last use, drop; at every share point, dup."

### 5.3 Live-set computation (Rule PR5.3)

For each program point P, the **live set** is the set of bindings whose values may still be referenced after P. Computation is a backward dataflow:

1. At a function's exit point: live set = ∅.
2. At any other point P: live(P) = (live(successor) ∪ uses(P)) − defs(P).
3. At branch joins: live(P) = ⋃ live(successor_i) for each branch successor.

The pass uses live-set deltas to insert RC ops:

- A binding leaves the live set after its last use → emit `drop` after the last use.
- A binding is used in K positions before leaving → K−1 of those positions need `dup` (the last use is `consume`).

For quantity-0 bindings: skip — they do not exist at runtime.
For quantity-1 bindings: skip dup; rely on the linearity check (Rule Q7.1) to ensure exactly one use; emit `consume` at that use.
For quantity-ω bindings: standard algorithm.

### 5.4 Branching (Rule PR5.4)

At a branch point (if/match decision tree), for each binding `x` in the live set:

- Compute uses(`x`) per branch.
- If a branch does **not** use `x`: insert `drop x` at branch entry.
- If a branch uses `x`: emit dups within the branch as needed.

This ensures every branch's exit has the same "RC budget" — refcount differences are reconciled at branch entry, not exit.

### 5.5 Function calls (Rule PR5.5)

When a function call site `g(a, b, ...)` is reached:

- For each argument: if the call site is the argument's last use, transfer ownership (no dup at this position; the called function takes the drop obligation).
- If the call site is not the last use: emit `dup` at this position; the called function still takes a drop obligation, but the caller retains its own ref for later use.
- For borrowed parameters (per §8): no dup/drop transfer; the caller retains ownership.

### 5.6 Closures (Rule PR5.6)

A closure capturing a binding `y` from the enclosing scope:

- At closure construction: emit `dup y` for each capture (the closure stores a reference; the enclosing scope retains its own).
- At closure invocation: the closure's `dup`'d capture is consumed by the body per the body's quantity rules.
- At closure drop: each capture is `drop`'d.

For quantity-1 captures: the closure carries a unique reference; it cannot be invoked twice (per Rule Q7.1). The closure itself must be quantity 1 if any of its captures is quantity 1.

## 6. Reuse analysis (FBIP)

FBIP — *Functional But In Place* — converts allocation-then-free into reuse-of-the-same-memory. This is Perceus's signature optimization.

### 6.1 Reuse tokens (Rule PR6.1)

When the compiler sees `drop x` immediately preceding a constructor `Cons(args...)` of the **same heap shape** as `x`, it generates a **reuse token**: a pointer to `x`'s memory that can be passed to `Cons` to reuse rather than allocate.

```
// Original:
match list:
    List.Cons(h, t) => List.Cons(transform(h), recur(t))   // allocates new Cons; drops old
    List.Nil => List.Nil

// After Perceus + reuse:
match list:
    List.Cons(h, t):                                       // matches; binds h, t
        let token = drop_to_reuse(list)                    // reuse-or-drop list
        List.Cons.from_token(token, transform(h), recur(t))  // reuses list's memory
    List.Nil => List.Nil
```

The runtime check at `drop_to_reuse`: if `list.rc == 1`, return the memory pointer; else `drop` (decrement) and allocate fresh.

### 6.2 Reuse fingerprints (Rule PR6.2)

Two heap shapes match for reuse iff they have:

- The same byte size (after padding).
- The same alignment.
- The same field-type "kind" sequence (e.g., both contain a heap pointer at offset 8 — actual types may differ if the variant fields are pointer-sized).

The compiler computes a **reuse fingerprint** for each heap-allocated variant and matches by fingerprint, not by nominal type. This allows reuse across different variants of the same data type (e.g., `Cons(h, t)` reusing memory from another `Cons` whose payload differs in element type).

### 6.3 Quantity-driven reuse (Rule PR6.3)

For quantity-1 bindings, reuse is **unconditional** — linearity guarantees `rc == 1` (the static linearity check eliminates the runtime test). The compiler emits direct memory reuse without the conditional branch.

For quantity-ω bindings, reuse is **conditional** — the runtime check `rc == 1` decides; if false, the new constructor allocates fresh.

### 6.4 Cross-arm reuse

A match arm may reuse the matched value's memory iff the arm's body constructs a value of a matching fingerprint. If the arm doesn't, the compiler still emits `drop` for the matched value at arm entry.

## 7. Drop specialization

### 7.1 Per-type drop functions (Rule PR7.1)

For each `data` type, the compiler synthesizes a **drop function** `drop_T(x: T) -> ()`:

- For sum types: `switch(x.tag) { case 0: drop_field_0_0; ...; case 1: drop_field_1_0; ...; ... }`. Each variant's drop sequence drops its fields and then frees the memory.
- For product types: drop each field in declaration order, then free.
- For recursive types: drop the recursive fields (tail-call optimized — see §7.3).
- For phantom-only types: no-op.

`drop_T` is emitted as an LLVM function and called by the runtime `drop` primitive (Rule PR3.2). The compiler may inline it at known-type call sites.

### 7.2 Inline drops (Rule PR7.2)

When `drop x` is emitted at a static call site where `x`'s type is fully known, the compiler **inlines** the drop sequence rather than calling `drop_T`:

```
// drop x where x: List of i64, x is List.Cons:
//   inlined sequence:
let h = x.head
let t = x.tail
drop_int(h)         // primitive drop — no-op for i64
drop_T_list(t)      // recursive call (tail call eligible)
free(x)
```

For unknown-type drops (e.g., behind a capability bound), the runtime dispatch via vtable.

### 7.3 Recursive drops as tail calls (Rule PR7.3)

A drop function that drops a recursive field as its last operation is a **tail call**:

```
fn drop_list(x: List of A) -> () =
    match x:
        List.Nil => free(x)
        List.Cons(h, t) =>
            drop(h)
            free(x)             // free first…
            drop_list(t)        // …then tail-call to drop tail; constant stack
```

Wait — order matters. The standard Perceus drop reverses field order: drop fields after freeing the parent's slot. The actual sequence for `Cons`:

```
let t = x.tail
drop(h)
free(x)
drop_list(t)        // tail call; stack-bounded
```

This bounds drop-stack usage even for arbitrarily long lists. The compiler must guarantee the tail-call optimization.

## 8. Borrowing (inferred)

### 8.1 Borrow analysis (Rule PR8.1)

A function parameter `p` is **borrowed** iff:

- The function body never **stores** `p` in a heap-allocated structure (no `Cons(_, p)` or `someStruct { x: p, ... }` etc.).
- The function body never **returns** `p` (or a value that contains `p`).
- The function body never **passes `p` to a non-borrowed parameter** of another function.

The compiler runs this check as a fixed-point analysis on the call graph: a parameter is borrowed if all its uses fit the above pattern. Mutually recursive functions are analyzed together.

### 8.2 Borrow ABI (Rule PR8.2)

Borrowed parameters use a different calling convention:

- At the call site: **no `dup`** — the caller retains its ref.
- In the callee: **no `drop`** at exit — the parameter was never owned.

This eliminates a dup/drop pair per call where borrowing applies. Common cases: predicates (`is_empty(list)`), accessors (`size_of(list)`), pure inspectors that don't propagate the value.

### 8.3 Borrow inference is an optimization

Borrowing is an internal compiler optimization. There is no surface syntax for `borrow` in v0.4.4 (deferred — see OQ-PR1). The compiler conservatively treats parameters as owned (with full dup/drop) when borrow analysis is unable to prove the borrowed property.

## 9. Effect handler memory

### 9.1 Continuation memory (Rule PR9.1)

Per `spec/effects.md` §7.4, non-abortive effect operations capture the continuation up to the handler frame. Each captured continuation is a **heap object** with:

- A refcount header (per Rule PR3.1).
- The captured stack-frame snapshot or stack-segment pointer.
- The handler-frame backreference.

The continuation's quantity is **1** by construction (Rule Q5.11) — `resume` consumes the continuation exactly once per code path. Therefore:

- No `dup` on continuation values.
- `consume` at the `resume` call site (no refcount check).
- The continuation's memory is freed (or reused for another continuation) after the resume.

### 9.2 Linear effect handlers (Rule PR9.2)

A linear effect (`spec/effects.md` §3.5) imposes a usage budget of ≤ 1 op per code path. The handler installs a single permission per nesting; once the op is intercepted, the permission is consumed.

Memory implication: the handler-frame for a linear effect has lifetime bounded by the SUBJECT's evaluation. After the handler exits, the frame is freed (no residual continuation references can outlive the handler since linearity prevents capturing an unused permission).

### 9.3 Tail-resume optimization (Rule PR9.3)

Per `spec/effects.md` §7.5, when an arm body's `resume(expr)` is in tail position, the compiler emits a tail-resume that avoids the full continuation roundtrip. From a memory perspective:

- The continuation is **not heap-allocated**; the stack is preserved across the operation interception.
- The arm body executes "in place" of the operation call.
- No RC traffic on the continuation.

This is why tail-resume is the common case in practice (`Console.print_line(s) => emit(s); resume(())`) and is important for performance.

## 10. Mutually recursive data — co-allocation

Per `spec/type_system.md` §7.5, mutually recursive data types form an SCC and are co-allocated. From a Perceus perspective:

### 10.1 Shared header layout (Rule PR10.1)

All members of an SCC share a unified header layout (a tag space wide enough to discriminate every variant of every member). Drop functions for SCC members may share fingerprints (per Rule PR6.2), enabling reuse across SCC members:

```
// Tree and Node form an SCC:
data Tree:
    Branch(node: Node)
    Leaf

data Node:
    N(tree: Tree, value: i64)

// Reuse: when dropping a Tree.Branch and constructing a new Node.N of the
// same fingerprint, the compiler may reuse the Branch's memory for the new N.
```

### 10.2 SCC drop ordering (Rule PR10.2)

The drop functions for SCC members are emitted as a **mutually recursive group**. Tail-call optimization (Rule PR7.3) extends across the group: a Tree drop calling a Node drop calling a Tree drop... is a single mutually-recursive tail-call chain, with stack-bounded execution.

## 11. Phase placement

The Perceus pass runs:

| Runs after | Provides |
|---|---|
| Quantity inference (`spec/quantities.md` §4) | Per-binding quantities (drives §4 simplification) |
| Representation selection (`spec/type_system.md` §6.5) | Concrete representations (drives §3.1 and §6.2 fingerprints) |
| Match desugaring (`spec/pattern_matching.md` §8) | Decision-tree IR (input to §5) |
| Property verification (`spec/properties.md` §7) | Verified facts (informs reuse: e.g., `non_empty` implies `tail` is non-null) |

| Runs before | Consumes |
|---|---|
| Code generation (LLVM IR emission) | Final IR with all `dup`, `drop`, `reuse_token`, `consume` annotations |

The pass is a **Recheck-style phase** (`docs/references/scala3-compiler-reference.md` §5.4) over the typed/desugared/quantity-annotated AST. It does not change types or values — it inserts RC operations and reuse tokens. Its output is a valid Fixed IR (with RC ops) ready for codegen.

## 12. Worked examples

### 12.1 Simple let-binding (quantity ω)

```
fn count_pairs(x: i64, y: i64) -> i64 =
    let pair = make_pair(x, y)        // allocates Pair on heap; pair has rc=1
    pair.first + pair.second           // two uses of `pair`; emits dup before first use
```

After Perceus:

```
fn count_pairs(x: i64, y: i64) -> i64 =
    let pair = make_pair(x, y)        // alloc; rc=1
    dup(pair)                          // rc=2
    let r = pair.first + pair.second  // both uses consume one ref; rc=0; free
    drop(pair)                         // already rc=0 from consumes — actually, this drop pairs with the dup
    r
```

Or, equivalently after the optimizer notices the dup/drop pair: the dup is unnecessary because both reads borrow rather than consume — borrow analysis (§8) eliminates the dup/drop pair entirely.

### 12.2 Match with reuse (FBIP)

```
fn map(list: List of A, f: A -> B) -> List of B =
    match list:
        List.Cons(h, t) => List.Cons(f(h), map(t, f))
        List.Nil => List.Nil
```

After Perceus + reuse:

```
fn map(list: List of A, f: A -> B) -> List of B =
    match list:
        List.Cons(h, t):
            let token = drop_to_reuse(list)            // releases or reuses list's memory
            List.Cons.from_token(token, f(h), map(t, f))
        List.Nil => List.Nil
```

Effect: when `list.rc == 1` at runtime, the new `Cons` reuses `list`'s memory — no allocation, no free. The list is transformed in place. This is FBIP.

### 12.3 Quantity-1 (linear) effect handler

```
linear effect Lock:
    fn release -> ()

fn critical_section(work: () -> () with Lock) -> () =
    work()                            // Lock effect not yet released
    Lock.release                       // exactly one usage of Lock per Rule E3.5.a
```

After Perceus:

- `Lock`'s row entry is quantity 1 (linear effect).
- The handler-frame for `Lock` is installed once; consumed by `Lock.release`.
- Continuation passed to `Lock.release` is quantity 1; no dup; consumed by resume.
- The frame is freed immediately after `Lock.release` returns.

No RC traffic on the lock or its continuation.

## 13. Open questions

- **OQ-PR1 — Borrow surface syntax.** Borrowing is currently inferred (§8.3). A future `borrow` keyword on parameters (à la Koka) would let users force borrowing for documentation or to avoid inference fragility. Defer until inference proves insufficient in practice.
- **OQ-PR2 — Atomic vs non-atomic refcount selection.** The header reserves a high bit (Rule PR3.1) for atomic-mode flag. The selection rule (when does the compiler emit atomic vs non-atomic operations?) is implementation-defined for v0.1. Future spec needs to nail this down once the threading model is specified.
- **OQ-PR3 — Cyclic data.** Reference counting cannot collect cycles. Fixed's `data` declarations don't admit cycles structurally (every value is finite per the data declaration), but mutable data with later cycles (if/when introduced) would need a separate mechanism. Defer until mutability is in scope.
- **OQ-PR4 — Borrow leakage through closures.** A borrowed parameter passed to a closure captures the borrow. The closure cannot outlive the borrow's scope. Inference and lifetime tracking interact here; details deferred to the implementation's concrete borrow analyzer.
- **OQ-PR5 — Tail-call optimization guarantees.** Rule PR7.3 requires TCO for drop functions. The TCO contract for general user code is not specified; a future doc may pin it down for Fixed's ambient totality stance.
- **OQ-PR6 — Reuse fingerprint algorithm.** Rule PR6.2 specifies the criteria; the exact algorithm (size, alignment, kind sequence) is implementation-defined. A precise specification would help cross-implementation conformance once a second compiler exists.

## 14. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | No direct dependency — Perceus runs on lowered IR |
| `spec/type_system.md` | §6.5 (representation selection) drives §3.1 layouts; §7.5 (SCC) drives §10 |
| `spec/quantities.md` | Rule Q5.X and Q6.1 / Q7.X drive §4 simplification; Q5.11 drives §9.1 |
| `spec/effects.md` | §6 / §7 drive §9; Rule E3.5 drives §9.2; §7.5 (tail-resume) drives §9.3 |
| `spec/pattern_matching.md` | §8 (decision-tree compilation) is the immediate input to §5 |
| `spec/properties.md` | Verified props inform reuse decisions (e.g., bounds elision, non_empty discharge) |
| `docs/references/scala3-compiler-reference.md` | Recheck pattern (§5.4) for the pass's structure |
| `docs/plans/implementation-plan.md` | Phase 4 deliverable: `perceus/PerceusInserter.scala` |
