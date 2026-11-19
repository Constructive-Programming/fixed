# Fixed Memory Management

**Status:** Draft v0.2
**Specifies:** how the compiler places, frees and reference-counts runtime values for Fixed v0.4.4 source: lifetime classes, data-stack regions, counted regions, the Perceus heap, destinations, class witnesses, reuse (FBIP), borrowing, effect-handler memory, structured parallelism, the `fip` contract and the memory report.
**Last revised:** 2026-11-19

**Changes from v0.1 (`spec/perceus.md`).** The document is renamed and widened. v0.1 specified a pure Perceus model in which stack allocation was keyed on quantity 1. v0.2 separates two axes: **quantity** (0 / 1 / ω, `spec/quantities.md`) decides reference-count traffic, and **lifetime class** (§3) decides placement. Most values live in headerless, refcount-free data-stack regions in the style of Goose; the Perceus heap and counted regions hold only values that are long-lived in the sense of Rule MM5.2. Perceus rules keep their `PR` identifiers so existing citations stay valid; new rules use `MM`. Rule PR3.1.a (SCC stack allocation gated on escape) is superseded by §5 and §15. OQ-PR2 is resolved for structured parallelism (§17).

## 1. Scope

This document specifies:

- The lifetime classes and the quantity × class matrix (§3).
- The region-containment invariant that keeps every pointer valid (§4).
- How the compiler assigns a class to every allocation, including the definition of "long-lived" and evacuation (§5).
- The region runtime: chunked data stacks, counted regions, the number of stacks (§6).
- Destinations, calling convention and class witnesses (§7).
- The Perceus heap: header, `dup` / `drop`, last use (§8), quantity-driven simplification (§9), RC insertion (§10), reuse (§11), drop specialization (§12), borrowing (§13).
- Effect-handler memory: handler kinds, abort, fiber stacks (§14).
- Mutually recursive data (§15) and packed aggregates (§16).
- Structured parallelism (§17).
- The `fip` contract (§18) and the memory report (§19).
- What quantity inference (M10) records, and phase placement (§20).

It does **not** specify:

- LLVM IR emission details — see the implementation plan's Phase 4 deliverables.
- The system allocator behind heap cells and region chunks — implementation-defined.
- Unstructured concurrency. Atomic reference counting returns only if it is introduced (§17.4).
- GPU and unified-memory backends. §16 and §6 keep them possible (position-independent links, no address-space reservations) but do not design them.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.4. (`fip fn` needs a grammar addition; see OQ-MM1.)
- `spec/type_system.md` v0.4.1 — §6.5 (representation selection), §6.6 (built-in candidates), §7.5 (mutual recursion / SCC).
- `spec/quantities.md` v0.2 — Rule Q5.X family, Rule Q6.1 (erasure soundness), Rule Q7.2 (FBIP hook). The parallel composition rule referenced in §17 is pending there.
- `spec/effects.md` v0.2 — §6 handler semantics, §7 evidence passing, Rule E3.5 (linear effects).
- `spec/pattern_matching.md` v0.2 — §8 match compilation.
- `spec/properties.md` v0.2 — §9 (`proved` props, Rule P7.7, inform reuse and bounds-check elision).

Theoretical basis and prior art (facts checked against the sources in November 2026):

- Reinking, Xie, de Moura, Leijen, *Perceus: Garbage Free Reference Counting with Reuse*, PLDI 2021 — the heap algorithm (§8–§13).
- Lorenzen, Leijen, *Reference Counting with Frame-Limited Reuse*, ICFP 2022 — reuse can break garbage-freedom; drop-guided reuse bounds the extra retention per call (§11.5).
- Lorenzen, Leijen, Swierstra, *FP²: Fully in-Place Functional Programming*, ICFP 2023 — `fip` means no (de)allocation and constant stack; `fbip` relaxes stack and deallocation; Koka checks uniqueness at runtime (§18).
- Ullrich, de Moura, *Counting Immutable Beans*, IFL 2019 — borrow inference, reset/reuse, persistent objects with no RC, multi-threaded marking (§3, §13, §17).
- Tofte, Talpin and the MLKit retrospective (Tofte, Birkedal, Elsman, Hallenberg, HOSC 2004); Hallenberg, Elsman, Tofte, *Combining Region Inference and Garbage Collection*, PLDI 2002 — the region leak in tail-recursive loops that Rule MM5.2(b) is designed to avoid, and the case for a collector behind regions.
- van Oortmerssen, Goose (`docs/goose_spec.md`, v4 draft) — data stacks, roots, destination construction, one growable on top, `return … from`.
- Vollmer et al., Gibbon (ECOOP 2017) and LoCal (PLDI 2019); Koparkar et al., parallel Gibbon (arXiv 2107.00522) — location calculus, chunked regions that double, per-region reference counts with outsets, indirections for sharing (§6, §16).
- vekatze, Neut — types compiled to closed resource functions `exp(selector, v)`; the model for class witnesses (§7.3).
- Jane Street OxCaml locality — separate locals stack, regions per function and loop body, `exclave_`, a tail call closes the region (§5.1).
- van Oortmerssen, Lobster — compile-time ownership with borrows, ownership specialization with a single fallback for large functions (§7.4).
- Westrick, Yadav, Fluet, Acar, *Disentanglement in Nested-Parallel Programs*, POPL 2020 — per-task heaps; Fixed is disentangled by construction (§17).
- Shaikhha et al., *Destination-Passing Style for Efficient Memory Management*, FHPC 2017; Bour, Clément, Scherer, *Tail Modulo Cons* — destinations (§7.1).

## 3. Two axes: quantity and lifetime class

### 3.1 Lifetime classes (Rule MM3.1)

Every runtime allocation is assigned exactly one **lifetime class**:

| Class | Where it lives | Header | Freed by |
|---|---|---|---|
| `Erased` | nowhere (quantity 0, Rule Q6.1) | — | — |
| `Inline` | inside its parent value, or in registers / native stack slots | no | its parent, or the native frame |
| `Static` | static data | no | never (persistent; Lean's "persistent objects") |
| `Frame(s)` | the scratch data stack, region of scope `s` | no | bump reset at the end of `s` |
| `Dest(p)` | the destination supplied for result or parameter `p` | that of the destination's class | the destination's owner |
| `Region(r)` | counted region `r` | one count per region, not per value | region count reaching zero (§6.4) |
| `Heap` | Perceus heap | yes (Rule PR3.1) | Perceus |

`Dest(p)` is resolved at each call site to one of `Frame`, `Region` or `Heap` of the caller (§7.1). `Inline` is decided by representation selection (`spec/type_system.md` §6.5); M11 may demote any class to `Inline` but never promotes.

### 3.2 Quantity × class (Rule MM3.2)

Quantity decides reference-count traffic; class decides placement. The two are independent: a linear value can still escape (be returned), and a shared value can still die with its scope.

| | `Frame` / `Dest` on a stack | `Region` | `Heap` | `Static` |
|---|---|---|---|---|
| **0** | erased | erased | erased | erased |
| **1** | no RC; freed by reset | no RC; the single owner releases one region reference on consume | no RC ops; freed on consume (unique owner) | no RC |
| **ω** | no RC; freed by reset | region-count `dup` / `drop` | Perceus `dup` / `drop` | no RC |

**Consequence (the goal of this model).** Per-value reference-count operations exist only for values that are both quantity ω and `Heap`. Headers exist only on `Heap` values and once per `Region`.

## 4. The region-containment invariant

### 4.1 Rule MM4.1

A pointer stored in a value of region A may target a value of region B only if lifetime(B) ⊇ lifetime(A). "Region" here covers every class: each `Frame(s)` scope, each counted region, the heap (unbounded lifetime) and static data (unbounded).

- Within one `Frame` region, allocation order does not matter. All values in it die together, so a cell may point to a younger cell of the same region. The invariant is about region lifetimes, not about age.
- `Heap` values point only to `Heap`, `Static`, or into a `Region` through a counted edge (§6.4).
- `Region` values point into their own region, `Static`, `Heap` (owned, counted on the heap object), or into another region through a counted outset edge.
- `Frame` values point to the same or an outer `Frame` region, `Region`, `Heap` or `Static`.

Fixed values are immutable and are written once at construction, so the invariant is checked once, where a pointer is written: at constructor application and at reuse (§11.4). There is no later mutation that could break it.

### 4.2 Frame values that own heap references (Rule MM4.2)

A bump reset cannot run `drop`. A `Frame` value that holds a reference to a `Heap` or `Region` value must therefore hold it in one of two ways:

1. **Borrowed.** Some owner provably outlives the frame (§13). No RC operation, nothing to do at reset. This is the expected case.
2. **Owned, registered.** Otherwise the frame keeps a **drop list**: the owned heap and region references registered by values of this frame. The reset walks the drop list before restoring the stack mark. Cost is per registered reference, not per value.

A drop list is reported (§19) and disqualifies `fip` (§18).

## 5. Class assignment

### 5.1 Scopes (Rule MM5.1)

These constructs open a `Frame` scope:

- A function body.
- Each step of a `fold` or `unfold` (the generated step function). Per-step temporaries reset at the end of every step.
- The subject of a `handle` whose clauses may capture a continuation (it runs on fiber stacks, §14.3).
- Each lane of a parallel composition (§17).

A call in tail position closes the caller's scope before the callee runs (OxCaml's rule). Values reachable from the arguments of a tail call are therefore placed in the nearest enclosing scope that outlives the call, never in the closing scope.

### 5.2 Long-lived values (Rule MM5.2)

A value is **long-lived** iff at least one of these holds:

**(a) Unbounded escape.** No scope the analysis can name contains all of its uses. The causes are:

- stored into a `Heap` value;
- captured by a closure that escapes the scope the closure was created in;
- reachable from a continuation that escapes its `handle` (§14.3);
- passed across a separate-compilation boundary whose interface records escape `unbounded` for that parameter (§20.2);
- shared with a lane and outliving the fork (§17).

**(b) Non-garbage-free churn.** It is the carried value of a `fold` or `unfold` (the accumulator or the seed) and the step is not **garbage-free** for it.

A step that turns old carried value `c` into new carried value `c'` is garbage-free for `c` iff every cell of `c` that becomes unreachable at the end of the step is reused by a constructor building `c'` (§11). Cells of `c` that stay reachable from `c'` cost nothing (sharing, append, cons onto it). Cells allocated in the step and reachable from `c'` are growth, which is allowed. Cells allocated in the step and not reachable from `c'` are per-step temporaries and get `Frame(step)`.

If the step leaves garbage, putting the carried value on a data stack would keep every dead version alive until the loop ends. That is the MLKit tail-recursion leak. Such a carried value is long-lived and goes to `Heap`, so Perceus frees dead versions as they die.

The garbage-free check runs at constructor and variant level, the way FP² checks `fip` at source level. It does not need representation sizes; M11 only refines the reuse fingerprints (§11.2).

Duration is never a criterion. Totality guarantees termination, not short running time.

### 5.3 Class choice (Rule MM5.3)

For each allocation, in order:

1. Quantity 0: `Erased`.
2. A compile-time constant with no free runtime variables: `Static`.
3. Not long-lived, and every use lies within a scope `s`: `Frame(s)` for the innermost such `s`. If the only escape is into the enclosing function's result: `Dest(result)`.
4. Long-lived and a member of a **cohesive group** (§5.4): `Region(r)`.
5. Otherwise: `Heap`.

Representation selection may later demote the value to `Inline`.

### 5.4 Cohesive groups and `Region` (Rule MM5.4)

A set G of long-lived allocations is a **cohesive group** iff:

1. All of G is built within one dynamic extent: one call tree, or one `unfold` / `fold` run.
2. G has a single root value `r`, and every member of G is reachable from `r`.
3. No member of G except `r` is extracted to outlive `r`, unless it is copied out (§16.2).
4. The carried values inside G satisfy Rule MM5.2(b). Churn inside a region would leak in the same way as on a data stack.

A cohesive group is allocated in a fresh counted region `r` (§6.4). Typical case: a syntax tree built by an `unfold`, returned up several frames, kept for a long time and then dropped as a whole. Without `Region`, each of its cells would be a separate `Heap` object with its own header.

### 5.5 Evacuation (Rule MM5.5)

Values are immutable and have no identity, so the compiler may **copy** a value from one class to another without changing the meaning of the program. This is **evacuation**. The compiler inserts it where a value's assigned class turns out too short for a use that the analysis could not foresee at the allocation point, for example:

- a `Frame` value passed to a parameter whose interface escape is `unbounded`;
- a value passed to a `fip` function's quantity-1 parameter from a shared (ω) binding (§18.3);
- a subtree extracted from a packed blob to outlive it (§16.2).

Evacuation makes analysis imprecision a performance cost, never a soundness hole. It is reported per site (§19).

**Warning W110 (evacuation in hot code).** The compiler warns when it inserts an evacuation in hot code. Without profile data, hot means inside a `fold` / `unfold` step, or inside a handler clause that runs inside one. With profile data (Phase 6), the profile decides.

```
warning[W110]: value copied to the heap inside a fold step
  --> src/index.fixed:42:17
   |
42 |         let entry = make_entry(key, line)
   |                     ^^^^^^^^^^^^^^^^^^^^^ evacuated: `entry` is stored into `index` (Heap)
   = note: `index` is Heap because its fold step is not garbage-free (Rule MM5.2(b))
   = help: run `fixed compile --explain-memory` for the full reason chain
```

## 6. Region runtime

### 6.1 Chunked regions (Rule MM6.1)

All regions — data stacks, fiber stacks, lane stacks and counted regions — use one mechanism, after Gibbon:

- A region is a linked list of chunks. The first chunk is small (implementation-defined, for example 4 KiB). Each new chunk is twice the size of the previous one.
- Each chunk is aligned to its own size, so masking any interior pointer finds the chunk header, and through it the region descriptor.
- Allocation is a bump of the region's top pointer within the current chunk. When the chunk is full, allocation continues in a new chunk.

No large address-space reservation is required, so the same runtime works on 32-bit, embedded, GPU and unified-memory targets. Fibers (§14.3) and lanes (§17) are cheap because their first chunk is small.

### 6.2 Data stacks (Rule MM6.2)

Each thread, fiber and lane has one **scratch stack** and zero or more **growable stacks**:

- The scratch stack holds `Frame` values. It is strictly LIFO per scope. A mark is `(chunk, offset)`. Leaving a scope restores the mark and returns or caches the chunks above it.
- A growable stack holds one value under construction whose layout must stay contiguous: a contiguous array, or a packed blob (§16). Linked representations build cell by cell in any region and need no growable stack.

Per-step temporaries go on the scratch stack and the growing carried value on a growable stack, so step scratch never sits above the value that has to grow.

### 6.3 Growth and relocation (Rule MM6.3)

A value under construction is not observable until it is finished: data is immutable, and an `unfold` returns its result only when done. Relocating it while it grows is therefore safe:

- When a contiguous value under construction reaches the end of its chunk, it is copied into a chunk at least twice as large. Amortized cost O(1) per element.
- Two growables may share one growable stack. If the lower one must grow, it moves above the other and leaves a hole. Holes total at most the final size, so waste is bounded by 2×.

**Number of growable stacks (N).** The compiler colours values under construction that are live at the same time, giving each the lowest free index (Goose's walk). Sharing a stack through relocation lets the compiler lower N at the cost of the bounded holes; the trade-off is implementation-defined until measured (§21). M10 records simultaneous growth as a requirement; M11 commits N once it knows which values are contiguous (§20.1).

### 6.4 Counted regions (Rule MM6.4)

A `Region(r)` has a descriptor holding a count and an **outset**: the set of other counted regions that values in `r` point into.

- The count is the number of references to `r`'s root from outside `r`, plus the number of outset edges from other regions into `r`.
- `dup` / `drop` on a pointer into `r` updates `r`'s count. The descriptor is found by masking the pointer (§6.1), so interior pointers need no header of their own.
- When a value of `r` is constructed with a pointer into region `q`, the edge `r → q` is added to `r`'s outset and `q`'s count is incremented once per edge, not once per pointer. Data is written once, so the outset only grows during construction and never changes afterwards.
- When the count reaches zero, all of `r`'s chunks are freed and every region in the outset is decremented.
- Owned `Heap` references held by values of `r` go on the region's drop list (Rule MM4.2), walked when the count reaches zero.

Retention: one live reference into `r` keeps all of `r` alive. Rule MM5.4(3) and copy-out (§16.2) bound this.

## 7. Destinations and calling convention

### 7.1 Destinations (Rule MM7.1)

A function whose result escapes only by being returned takes a hidden **destination** parameter and builds its result there, through any call depth and through the selected branch. This matches Goose and destination-passing style. At the call site the caller supplies its own region:

- `let x = f(…)` where `x` is `Frame(s)`: the caller's scratch stack at scope `s`.
- the result flows into the caller's own result: the caller passes its own destination through.
- `x` is `Region(r)` or `Heap`: that region, or the heap.

Constructors in tail position fill a destination hole (tail modulo constructor), so `map` over a list writes each cell straight into its final place.

### 7.2 Borrowed parameters are uniform (Rule MM7.2)

A borrowed parameter (§13) needs no RC operation and is never stored beyond the call, so the argument's class does not matter in the callee. Borrowed parameters use one calling convention for every class.

### 7.3 Class witnesses (Rule MM7.3)

An owned parameter whose class is not fixed by the callee's signature facts (§20.2) receives a hidden **class witness**: the pair (type, class) compiled into a closed function, after Neut's types-as-functions.

```
witness(op, v, dest)    op ∈ { drop, dup, reuse_test, evacuate, size }
```

| op | `Frame` / stack `Dest` | `Region` | `Heap` | `Static` |
|---|---|---|---|---|
| `drop` | no-op | region decrement | Perceus decrement | no-op |
| `dup` | identity | region increment | Perceus increment | identity |
| `reuse_test` | unique iff quantity 1 (static) | false | `rc == 1` | false |
| `evacuate(dest)` | copy into `dest` | copy into `dest` unless `dest` is this region | identity if `dest` is the heap, else copy | identity |
| `size` | byte size | byte size | byte size | byte size |

- A witness is passed per type parameter or per class-polymorphic owned parameter, not per value. A container's witness covers its elements, because Rule MM4.1 lets a `Frame` container hold `Heap` elements but fixes the element class per instantiation.
- Witnesses replace any address-range test for "is this on a stack". Chunked regions (§6.1) have no fixed address range, so a test would need tag bits; the witness does not.
- Neut copies on every share. Fixed copies only on evacuation and keeps reference counting for `Heap` values.

### 7.4 Specialization (Rule MM7.4)

1. If M10 knows the class at a call site (the common case under whole-program compilation), the callee is monomorphized for it and no witness exists.
2. If the class is unknown (higher-order code, separate compilation), a witness is passed and its operations are indirect calls.
3. With profile data (Phase 6), call sites where one witness dominates become guarded direct code, for example `if w == FRAME_W { /* no-op */ } else { w(drop, v) }`, or full specializations. This extends the plan's "dynamic dispatch → monomorphized" item to classes.

Large functions get one witness-taking version instead of per-class copies, as Lobster does for large and dynamically dispatched functions. The size threshold is implementation-defined.

## 8. The Perceus heap

### 8.1 Heap-object layout (Rule PR3.1)

Every `Heap` value carries a header:

```
{
    rc: i64,            // refcount
    tag: i8,            // variant discriminator for sum types; 0 for product types
    pad: [N x i8],      // padding to natural alignment
    payload: <variant fields>
}
```

`Frame`, `Dest` on a stack, `Static`, `Inline` and `Erased` values have **no header**. `Region` values have no per-value header; the region descriptor carries the count (§6.4). Sum-type values outside the heap carry their tag as part of their representation, chosen by representation selection.

The v0.1 rules that tied stack allocation to quantity 1 and a size budget, and Rule PR3.1.a (SCC stack allocation gated on escape analysis), are superseded by §5. Size alone never sends a value to the heap.

### 8.2 `dup` and `drop` primitives (Rule PR3.2)

- `dup(x)` — increment `x`'s count. No-op unless `x` is quantity ω and `Heap` or `Region`.
- `drop(x)` — decrement `x`'s count; at zero, drop its fields and free the memory (heap) or free the region (§6.4). No-op for `Frame`, stack `Dest`, `Static`, `Inline` and `Erased`. Quantity-1 values use `consume` (Rule PR3.5).

`dup` and `drop` are emitted by the compiler (§10); they are not user-visible. Where the class is unknown they are witness calls (§7.3).

### 8.3 Last-use semantics (Rule PR3.3)

For every binding `x` introduced at program point P, the compiler emits exactly the `dup` / `drop` pairs needed to balance count changes between P and the end of `x`'s lifetime. The owning scope is responsible for dropping; passing `x` to an owned parameter transfers the drop obligation.

The last use of a binding is the last program point in IR order where the binding is referenced. The compiler emits `drop` immediately after the last use (or at branch entry, per §10.4).

### 8.4 Quantity ω (Rule PR3.4)

Quantity-ω bindings of class `Heap` or `Region` carry Perceus RC: `dup` at share points, `drop` at last use, conditional reuse (§11). Quantity-ω bindings of any other class carry no RC; their region frees them.

### 8.5 Quantity 1 is `consume` (Rule PR3.5)

A quantity-1 binding has no `dup` and no count-aware `drop`. Its single use is a **`consume`**: the value is moved, and the binding is dead afterwards. For `Frame` and stack `Dest` values, `consume` is a no-op. For `Heap` values, `consume` transfers ownership, and the final owner frees without testing a count. For `Region` values, the final owner releases its single region reference.

This is what makes FBIP guaranteed for quantity-1 bindings: at the consume site, the memory is unique to this code path.

## 9. Quantity-driven simplification

| Quantity | Allocation | RC ops | Reuse |
|---|---|---|---|
| **0** | None | None | N/A — the value does not exist at runtime. |
| **1** | By class (§5). | No `dup`; no count `drop`; `consume` at the use site. | Guaranteed — the last use is the only use. |
| **ω** | By class (§5). | `dup` / `drop` only if `Heap` or `Region`. | `Heap`: conditional on `rc == 1`. `Frame`: none (§11.3). |

Quantity removes RC operations for 0 and 1; class removes them for ω values outside the heap. Together they confine per-value RC to ω ∧ `Heap` (Rule MM3.2).

## 10. RC insertion algorithm

The compiler implements the Reinking et al. (2021) algorithm, restricted to bindings of class `Heap` or `Region` and with the quantity simplifications above.

### 10.1 Input form (Rule PR5.1)

The pass runs after match desugaring (`spec/pattern_matching.md` §8) and consumes the decision tree plus, per binding, its quantity and class (§20).

Input invariants:

- Every binding is annotated with its quantity and lifetime class.
- Every value has a chosen representation (`spec/type_system.md` §6.5).
- Match expressions are decision trees, not match cases.

### 10.2 A-normal form (Rule PR5.2)

The pass first converts the IR to A-normal form: every non-trivial subexpression is named via `let`, so every argument position is a variable reference.

```
// Before ANF:
g(f(x), h(x))

// After ANF:
let v1 = f(x)
let v2 = h(x)
g(v1, v2)
```

### 10.3 Live-set computation (Rule PR5.3)

For each program point P, the live set is the set of bindings whose values may still be referenced after P. Backward dataflow:

1. At a function's exit point: live set = ∅.
2. At any other point P: live(P) = (live(successor) ∪ uses(P)) − defs(P).
3. At branch joins: live(P) = ⋃ live(successor_i).

A binding leaving the live set after its last use gets `drop`. A binding used in K positions gets K−1 `dup`s.

- Quantity-0 bindings: skipped.
- Quantity-1 bindings: no `dup`; `consume` at the single use (Rule Q7.1 guarantees one use).
- Quantity-ω bindings of class `Heap` or `Region`: the standard algorithm.
- Quantity-ω bindings of any other class: skipped; the region frees them.

### 10.4 Branching (Rule PR5.4)

At a branch point, for each RC-tracked binding `x` in the live set: if a branch does not use `x`, insert `drop x` at branch entry; if it does, emit `dup`s within the branch as needed. Every branch exits with the same count budget.

### 10.5 Function calls (Rule PR5.5)

At a call `g(a, b, …)`:

- For an owned parameter: at the argument's last use, ownership transfers (no `dup`); otherwise `dup` at this position.
- For a borrowed parameter (§13): no `dup` / `drop` transfer.
- If the parameter's interface escape is `unbounded` and the argument is not `Heap`, `Region` or `Static`: evacuate (Rule MM5.5).

### 10.6 Closures (Rule PR5.6)

A closure capturing a binding `y`:

- At construction: `dup y` per capture if `y` is RC-tracked.
- At invocation: the capture is consumed by the body per the body's quantity rules.
- At closure drop: each RC-tracked capture is dropped.

A closure whose environment does not escape its creation scope is itself `Frame` (no header). An escaping closure is `Heap` and its captures follow Rule MM4.1: a `Frame` capture of an escaping closure is long-lived by Rule MM5.2(a) and is placed accordingly.

For quantity-1 captures, the closure must itself be quantity 1 (Rule Q7.1).

## 11. Reuse analysis (FBIP)

### 11.1 Reuse tokens (Rule PR6.1)

When `drop x` (or `consume x`) precedes a constructor of the same heap shape, the compiler generates a reuse token for `x`'s memory and passes it to the constructor.

```
// Original:
match list:
    List.Cons(h, t) => List.Cons(transform(h), recur(t))
    List.Nil => List.Nil

// After Perceus + reuse:
match list:
    List.Cons(h, t):
        let token = drop_to_reuse(list)
        List.Cons.from_token(token, transform(h), recur(t))
    List.Nil => List.Nil
```

For quantity ω, `drop_to_reuse` returns the memory if `rc == 1` and otherwise decrements and returns a null token, so the constructor allocates fresh.

### 11.2 Reuse fingerprints (Rule PR6.2)

Two shapes match for reuse iff they have the same byte size after padding, the same alignment and the same field kind sequence. Matching is by fingerprint, not nominal type, so variants of different types can reuse each other's memory.

### 11.3 Quantity-driven reuse (Rule PR6.3)

- Quantity 1: reuse is unconditional, in any class. Linearity guarantees uniqueness statically.
- Quantity ω, `Heap`: reuse is conditional on `rc == 1` at runtime.
- Quantity ω, `Frame` or `Region`: no reuse. Without a count, uniqueness is unknown, and the memory is freed with its region anyway.

### 11.4 Reuse across regions (Rule MM11.4)

A reuse token carries the class of the cell it reuses. Reuse is legal only if the new fields satisfy Rule MM4.1 for the **reused cell's** region:

- Reusing a `Heap` cell requires every new pointer field to target `Heap`, `Static` or a counted `Region` edge.
- Reusing a `Frame(s)` cell requires every new pointer field to target `Frame(s)`, an outer `Frame` region, `Region`, `Heap` or `Static`.

If the check fails, the token is dropped and the constructor allocates fresh in the class assigned to its result.

### 11.5 Drop-guided reuse (Rule MM11.5)

Reuse can keep memory alive longer than Perceus would without it (Lorenzen and Leijen 2022). The compiler uses their **drop-guided** placement: a reuse token is created only where the original `drop` would have been, and the extra retention per call is bounded by a constant. The naive "any earlier drop of a matching shape" placement is not conformant.

### 11.6 Cross-case reuse

A match case may reuse the matched value's memory iff its body constructs a value of a matching fingerprint, subject to §11.4. Otherwise the matched value is dropped at case entry.

## 12. Drop specialization

### 12.1 Per-type drop functions (Rule PR7.1)

For each `data` type the compiler synthesizes `drop_T(x: T) -> ()`:

- Sum types: switch on the tag, drop each field of the variant, then free.
- Product types: drop each field in declaration order, then free.
- Recursive types: drop the recursive fields with tail calls (§12.3).
- Phantom-only types: no-op.

Drop functions apply to `Heap` values. Fields that are not RC-tracked are skipped. A field of class `Region` releases its region reference.

### 12.2 Inline drops (Rule PR7.2)

When the type is fully known at a `drop` site, the drop sequence is inlined:

```
// drop x where x: List of i64, x is List.Cons:
let h = x.head
let t = x.tail
drop_int(h)         // primitive drop — no-op for i64
drop_T_list(t)      // recursive call (tail call eligible)
free(x)
```

Where the type or class is unknown, the drop goes through the class witness (§7.3).

### 12.3 Recursive drops as tail calls (Rule PR7.3)

A drop function that drops a recursive field last makes it a tail call:

```
fn drop_list(x: List of A) -> () =
    match x:
        List.Nil => free(x)
        List.Cons(h, t) =>
            let t_local = x.tail   // hoist the tail before x is freed
            drop(h)
            free(x)
            drop_list(t_local)     // tail call; constant stack
```

The compiler must guarantee tail-call optimization for compiler-emitted drop functions.

## 13. Borrowing (inferred)

### 13.1 Borrow analysis (Rule PR8.1)

A function parameter `p` is **borrowed** iff the body never stores `p` in a constructed value, never returns `p` (or a value containing `p`), and never passes `p` to an owned parameter. The analysis is a fixed point over the call graph.

Lean's heuristic also applies: a parameter that is the subject of a reuse token is owned, because borrowing it would block the reuse.

### 13.2 Borrow ABI (Rule PR8.2)

- At the call site: no `dup`.
- In the callee: no `drop` at exit.
- The calling convention is the same for every class (Rule MM7.2).

### 13.3 Borrows and regions (Rule MM13.3)

A borrowed reference may cross region boundaries in any direction for the duration of the call, because the callee cannot store it. This is the mechanism that lets a `Frame` value read a `Heap` value without registering it on a drop list (Rule MM4.2(1)).

### 13.4 Borrow inference is an optimization

There is no surface syntax for `borrow` (OQ-PR1). When borrowing cannot be proven, the parameter is owned.

## 14. Effect-handler memory

### 14.1 Handler kinds (Rule MM14.1)

At each `handle` install site, the compiler classifies the handler from the shapes of its clauses:

| Kind | Clause shape | Data-stack treatment |
|---|---|---|
| tail-resumptive | every clause ends in `resume(e)` in tail position, or is abortive | none: the clause runs on top of the current stacks (Rule PR9.3) |
| abortive | the operation returns `!`; the clause never resumes | reset to the handler's marks (§14.2) |
| capturing, local | some clause resumes in non-tail position, and the continuation does not escape the `handle` expression | subject runs on fiber stacks (§14.3) |
| capturing, escaping | some clause stores the continuation (for example, inside a closure) in a value that outlives the `handle` expression | as above, plus promotion of the reachable free `Frame` values (§14.3) |

The kind is local to the install site. Effect-polymorphic code that performs the operation does not need to know it.

### 14.2 Abortive operations (Rule MM14.2)

Installing a handler records a mark on every data stack in use. When an abortive clause completes, control returns to the handler, and every frame between the operation and the handler is left.

- Each frame left runs its epilogue on the way out: owned `Heap` and `Region` references are dropped and drop lists are walked. Frames check a hidden per-frame discriminant, as Goose does for `return … from` and as Koka does with yield bubbling. There is no separate unwinder.
- Every data stack is reset to the handler's mark. Partial results built by the frames left are abandoned.
- The clause's value is built in the handler's destination.

### 14.3 Capturing handlers and fiber stacks (Rule MM14.3)

The subject of a capturing handler runs on **fiber data stacks** owned by the handler installation: a fresh scratch stack and fresh growable stacks, small first chunks (§6.1), drawn from a per-thread pool.

- Capturing the continuation freezes the native stack segment and the fiber stacks together.
- The continuation object is quantity 1 (Rule Q5.11) and `Heap`. It owns its fiber stacks. `resume` continues on them; when the resumed computation finishes, or the continuation is dropped unresumed, the fiber stacks return to the pool.
- **Local capture.** The continuation cannot outlive the `handle` expression, and the `handle` expression lies inside the scopes of every outer `Frame` region. Suspended frames may therefore keep borrowing outer `Frame` values.
- **Escaping capture.** A `Frame` value of an outer scope that the continuation can reach, and whose scope may end before the continuation is dropped, is long-lived by Rule MM5.2(a). It is promoted to its long-lived class, or evacuated into the fiber at install time.
- The subject's result, and values flowing to the handler's result, are built in the handler's destination or evacuated there when the handler returns.

Example: the scheduler in `examples/08_effects_handlers.fixed` runs `remaining.push(() -> resume(()))`. The continuation is stored in a queue that outlives the fold step, so the handler is capturing-escaping. The task closure comes from the queue (`Heap`), and the fiber holds the task's own temporaries.

Multi-shot resume (OQ-Q1) would break single ownership of the fiber stacks and require copying them. Keeping OQ-Q1 closed keeps this rule simple.

### 14.4 Continuation memory (Rule PR9.1)

The continuation object (§14.3) has a header and is quantity 1: no `dup`, `consume` at `resume`, freed (or reused for another continuation) after the resume.

### 14.5 Linear effect handlers (Rule PR9.2)

A linear effect (`spec/effects.md` §3.5) allows at most one operation per code path. Its handler frame lives only during the subject's evaluation; linearity prevents a residual continuation from outliving the handler.

**Quantity-1 captures across linear clause bodies (Rule PR9.2.a).** Per Rule Q7.4, a linear-effect clause body has quantity 1, so a quantity-1 outer binding captured into it generates no `dup`. Non-linear clause bodies remain quantity-ω closures (Rule Q5.7); capturing a quantity-1 binding into one is a Rule Q7.1 violation. Rules Q7.4 and PR9.2.a are contingent on OQ-Q5 and gated by the same flag as `linear effect`.

### 14.6 Tail-resume (Rule PR9.3)

When a clause's `resume(expr)` is in tail position (`spec/effects.md` §7.5), no continuation is allocated, the stacks are preserved, the clause runs in place of the operation call, and there is no RC traffic on a continuation. This is the common case for I/O handlers.

## 15. Mutually recursive data

Mutually recursive data types form an SCC (`spec/type_system.md` §7.5). SCC membership no longer forces heap allocation: each value of an SCC type gets its class by §5 like any other value. When values of an SCC are `Heap`:

### 15.1 Shared header layout (Rule PR10.1)

All members of an SCC share a unified header layout with a tag space wide enough for every variant of every member. Drop functions for SCC members may share fingerprints, which enables reuse across members.

```
data Tree:
    Branch(node: Node)
    Leaf

data Node:
    N(tree: Tree, value: i64)

// Dropping a Tree.Branch and constructing a Node.N of the same fingerprint
// may reuse the Branch's memory.
```

### 15.2 SCC drop ordering (Rule PR10.2)

Drop functions for SCC members are emitted as a mutually recursive group with tail calls across the group (Rule PR7.3), so drop runs in bounded stack.

## 16. Packed aggregates

### 16.1 Blobs (Rule MM16.1)

When representation selection picks a packed layout (nested data inline, variable-size variants, narrow relative-offset links), the whole packed value is one **blob**:

- A `Frame` or stack `Dest` blob has no header.
- A `Heap` blob has one header for the whole blob. A `Region` blob uses its region's count.
- References to interior subtrees are borrows bounded by the blob owner's lifetime. They never carry their own count.

Relative-offset links make a blob position-independent: it can be relocated (§6.3), evacuated or copied to another memory pool as plain bytes.

### 16.2 Copy-out (Rule MM16.2)

A subtree that must outlive the blob's owner is **copied out** (evacuated) into the class its use requires. It is never kept as an interior pointer. This prevents a small subtree from keeping a large blob alive.

Representation scoring must account for this. `spec/type_system.md` §6.5.4 "access fit" charges packed candidates for the expected volume of escaping subtree extraction, so the selector does not pick a packed layout for a workload that would copy out constantly.

## 17. Structured parallelism

Fork sites and the quantity rule for lanes are `spec/quantities.md` Rule Q5.13. This section fixes the memory side only.

### 17.1 Lanes (Rule MM17.1)

Each lane of a fork-join composition runs on its own scratch and growable stacks (§6.2), with small first chunks.

- **Inputs** shared by several lanes are borrowed for the duration of the fork. Values are immutable, so lanes perform no RC operation on them.
- **Outputs** are built into disjoint destinations supplied by the parent, in the parent's regions. A lane's result is transferred to the parent at join, never shared during the fork.
- **Lane temporaries** are `Frame` values of the lane and are freed when the lane ends.

Lanes never observe each other's allocations. Fixed has no mutation, so it is disentangled in the MPL sense by construction.

### 17.2 Non-atomic reference counts (Rule MM17.2)

Under §17.1, no two threads ever `dup` or `drop` the same object at the same time: shared inputs are only borrowed, and outputs move by ownership transfer. Reference counts are therefore **non-atomic** in all code produced by structured parallelism. This resolves OQ-PR2 for every program Fixed can currently express. The header bit that v0.1 reserved for an atomic mode is not needed.

### 17.3 Deferred dups (Rule MM17.3)

A lane output may need to point into a shared `Heap` input whose owner does not provably outlive the output. Incrementing the input's count from several lanes would race. Instead, each lane counts such increments locally, and the parent adds them to the objects' counts at join. This rule is a hypothesis to validate (§21).

### 17.4 Unstructured concurrency

If unstructured concurrency is ever added, values that cross between threads need atomic counts or Lean-style marking, where every object reachable from a value is tagged multi-threaded before the value is sent. That is out of scope here.

## 18. The `fip` contract

### 18.1 `fip fn` (Rule MM18.1)

A function declared `fip fn` is rejected unless inference shows that the function and everything it calls:

1. perform no `Heap` or `Region` allocation;
2. perform no RC operation (`dup`, `drop`, `reuse_test`, region count update);
3. insert no evacuation;
4. register nothing on a drop list;
5. use **bounded** data-stack space: the number of bytes bumped per call has a static upper bound. Bumps inside a `fold` / `unfold` step count once, because they reset per step (Rule MM5.1).

Allowed: writing into destinations supplied by the caller, `Frame` temporaries within the bound, and unconditional reuse of quantity-1 cells. Native stack use is bounded by the fold structure (a `fold` over a tree uses depth proportional to the tree's depth).

### 18.2 `fip(strict) fn` (Rule MM18.2)

The FP² meaning. In addition to Rule MM18.1:

1. no data-stack bump at all: every constructed value either reuses a consumed cell or is `Inline`;
2. constant native stack: every fold the function performs compiles to a loop (list-shaped folds, tail modulo constructor).

### 18.3 Arguments of `fip` functions (Rule MM18.3)

A parameter that a `fip` function reuses must be quantity 1 in its signature, so reuse is unconditional and needs no count test. Koka checks uniqueness at runtime and falls back to copying; Fixed proves it statically. A caller that passes a quantity-ω binding to such a parameter gets an evacuation inserted at the call site. The caller is then not `fip` itself, and the evacuation is reported (and warned in hot code, W110).

### 18.4 Diagnostics (Rule MM18.4)

A violation is `error[E110]: fip contract violated`. The error names the rule item violated and prints the reason chain (§19) of the offending allocation or RC operation.

```
error[E110]: fip contract violated: heap allocation (Rule MM18.1(1))
  --> src/tree.fixed:31:13
   |
31 |             Node(l, k, v, r)
   |             ^^^^^^^^^^^^^^^^ allocates `Heap`
   = note: no reuse token: the matched `Leaf` has a different fingerprint (Rule PR6.2)
   = note: the result is `Heap` because it is stored into `cache` (Heap) at src/tree.fixed:12:9
```

## 19. The memory report

`fixed compile --explain-memory` prints, per binding and per allocation site:

- quantity, lifetime class, and whether RC operations are emitted;
- a **reason chain** for every class other than `Frame` and `Inline`, for example: "`Heap`: reachable from a continuation that escapes `handle` at 08:187 via `remaining.push`";
- evacuation sites, drop lists, counted regions and their roots;
- per function: the signature facts of §20.2 and the verified `fip` level;
- per `fold` / `unfold`: whether it is a fork site and, when it is not, the reason (`spec/quantities.md` Rule Q5.13.d).

`--format json` gives the same data machine-readably (implementation plan, "Agent-Friendly CLI"). An AI agent reads the reason chains directly instead of reverse-engineering placement from generated code.

## 20. What M10 records, and phase placement

### 20.1 Per binding and per construct (Rule MM20.1)

Quantity inference (M10, `docs/plans/phase-3-typer.md` §5.7) computes, in the same walk that counts usages:

- **Per binding:** quantity; **escape set** ⊆ {`result`, `field(b)`, `closure(c, escapes?)`, `continuation(h, escapes?)`, `carried(loop)`, `lane-shared(fork)`, `unbounded`}; lifetime class (§5.3), with `Inline` left to M11; the reason chain (§19).
- **Per scope:** the allocations reset at its end; drop-list registrations.
- **Per `fold` / `unfold`:** whether the step is garbage-free for the carried value (Rule MM5.2(b)).
- **Per cohesive group:** its root and members (Rule MM5.4).
- **Per handler install:** its kind (Rule MM14.1).
- **Per function:** the signature facts below, and the verified `fip` level.
- **Requirements for M11:** values under construction that grow at the same time (for N, Rule MM6.3), and packed-subtree extractions that would need copy-out (Rule MM16.2). M11 resolves both when it commits representations.

Escape facts are data flow and do not depend on representation, so all of the above except the two M11 requirements is final after M10.

### 20.2 Signature facts (Rule MM20.2)

These facts appear in every function signature, and in module interfaces for separate compilation:

- **Per parameter:** quantity; mode (borrowed or owned); escape ∈ {`none`, `into-result`, `unbounded`}; class-polymorphic or fixed (decides whether a witness is passed, §7.3).
- **Result:** destination-polymorphic or a fixed class.
- **Stack demand:** the maximum number of growable stacks in use at once, and the static bound on scratch bumps if one exists.
- **`fip` level:** none, `fip` or `fip(strict)`.

Lean ships borrow annotations in its interface files in the same way.

### 20.3 Phase placement

| Runs after | Provides |
|---|---|
| Quantity inference, M10 (`spec/quantities.md` §4) | Quantities, escape sets, lifetime classes, signature facts (§20) |
| Representation selection, M11 (`spec/type_system.md` §6.5) | Representations, `Inline` demotions, N, copy-out sites, fingerprints |
| Match desugaring (`spec/pattern_matching.md` §8) | Decision-tree IR |
| Property verification (`spec/properties.md` §7) | Verified facts (informs reuse) |

| Runs before | Consumes |
|---|---|
| Code generation (LLVM IR emission) | IR with `dup`, `drop`, `consume`, reuse tokens, destinations, region marks and resets, witnesses and evacuations |

The pass is a Recheck-style phase (`docs/references/scala3-compiler-reference.md` §5.4). It does not change types or values.

## 21. Hypotheses to measure

Fixed has no backend yet. Every performance statement in this document is a hypothesis until measured. In particular:

- Overall speed and memory relative to pure Perceus and to Goose-style placement. Goose's own claim of about 1.13–1.19× over safe Rust is unverified and says nothing about Fixed.
- Witness call overhead at polymorphic boundaries, before and after PGO specialization (§7.4).
- How often evacuation fires on real programs (a performance cliff risk), and how often W110 fires.
- Whether Rule MM5.2(b) sends too many carried values to the heap, and whether the garbage-free check stays simple. MLKit's equivalent analysis was described by its authors as "unpleasantly complex".
- Fiber-stack cost per capturing handler install (§14.3).
- Relocation-on-growth waste versus a larger N (§6.3).
- Retention by counted regions and blobs, and the copy-out volume (§6.4, §16.2).
- Deferred dups in lanes (§17.3).

## 22. Worked examples

### 22.1 Simple let-binding (quantity ω, `Frame`)

```
fn count_pairs(x: i64, y: i64) -> i64 =
    let pair = make_pair(x, y)        // built in this function's Frame region (Dest at make_pair)
    pair.first + pair.second           // two borrowed reads
```

`pair` is quantity ω but `Frame`: no header, no `dup`, no `drop`. The region frees it at return. Under v0.1, this would have been a heap allocation with a `dup` / `drop` pair that borrow analysis then removed.

### 22.2 Match with reuse (FBIP)

```
fn map(list: List of A, f: A -> B) -> List of B =
    match list:
        List.Cons(h, t) => List.Cons(f(h), map(t, f))
        List.Nil => List.Nil
```

If `list` is quantity 1, each `Cons` cell is reused unconditionally in whatever class it lives in, provided the new fields satisfy Rule MM11.4. If `list` is ω and `Heap`, reuse is conditional on `rc == 1`. If `list` is ω and `Frame`, the result is built in the destination and the old cells die with their region.

### 22.3 Fold with per-step temporaries

```
fn total_len(words: List of String) -> u64 =
    words.fold(0, (acc, w) -> acc + w.trim().length())
```

`w.trim()` is a per-step temporary: `Frame(step)`, reset after every step. `acc` is `Inline`. The loop allocates nothing that survives a step.

### 22.4 Churn sent to the heap

```
fn index(lines: List of String) -> Map of (String, u64) =
    lines.fold(Map.empty, (m, line) -> m.insert(key_of(line), line.length()))
```

If `m` is quantity 1 and `insert` reuses the path it rebuilds, the step is garbage-free (Rule MM5.2(b)). `m` then lives in the caller's destination and is updated in place. If the old map version is shared elsewhere, the replaced path nodes become garbage each step, so `m` is long-lived and goes to `Heap`. The report states which.

### 22.5 Linear effect handler

```
linear effect Lock:
    fn release -> ()

fn critical_section(work: () -> () with Lock) -> () =
    work()
    Lock.release
```

The `Lock` handler is installed once and consumed by `Lock.release`. Its continuation is quantity 1, with no `dup`, consumed by `resume`. The frame is freed right after `Lock.release` returns. No RC traffic.

## 23. Open questions

- **OQ-PR1 — Borrow surface syntax.** Borrowing is inferred (§13.4). Defer a `borrow` keyword until inference proves insufficient.
- **OQ-PR2 — Atomic vs non-atomic counts.** Resolved for structured parallelism by Rule MM17.2. Reopens only if unstructured concurrency is added (§17.4).
- **OQ-PR3 — Cyclic data.** Fixed data cannot form cycles. Deferred until mutability is in scope (it is not planned).
- **OQ-PR4 — Borrow leakage through closures.** A borrowed parameter captured by a closure must not outlive the borrow. Rule MM5.2(a) and Rule PR5.6 cover escaping closures; the details belong to the borrow analyzer.
- **OQ-PR5 — Tail-call guarantees.** Rule PR7.3 requires TCO for drop functions, and `fip(strict)` needs loops for list-shaped folds. The contract for general code is still open.
- **OQ-PR6 — Reuse fingerprint algorithm.** Implementation-defined until a second compiler exists.
- **OQ-MM1 — Grammar for `fip`.** `fip fn` and `fip(strict) fn` are in `spec/syntax_grammar.ebnf` v0.4.9 (`FipModifier` on `FnDecl`). Still open: FP²'s `fip(n)` (at most n allocations), and whether a capability method requirement or a `satisfies` method may carry the modifier.
- **OQ-MM2 — Chunk sizes and pools.** First-chunk size, the growth factor and fiber/lane pool policy are implementation-defined until measured.
- **OQ-MM3 — N versus relocation.** Whether the compiler should prefer more growable stacks or relocation with holes (§6.3).
- **OQ-MM4 — Cohesive-group detection.** Rule MM5.4 states the conditions; how far M10 searches for groups (one function, one call tree, across modules) is open.
- **OQ-MM5 — Region and witness facts across modules.** Separate compilation needs §20.2 in interface files; their format is open.

## 24. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | `fip fn` needs a grammar addition (OQ-MM1); otherwise this pass runs on lowered IR |
| `spec/type_system.md` | §6.5 consumes quantity and escape facts and decides `Inline`; §6.5.4 scoring uses class eligibility and copy-out volume; §7.5 SCCs (§15) |
| `spec/quantities.md` | Quantities drive §9; M10 also computes the facts in §20; the pending parallel composition rule underlies §17 |
| `spec/effects.md` | §6 and §7 drive §14; Rule E3.5 drives §14.5; §7.5 drives §14.6 |
| `spec/pattern_matching.md` | §8 decision trees are the input to §10 |
| `spec/properties.md` | `proved` props (Rule P7.7) inform reuse and bounds-check elision |
| `spec/stdlib.md` | `.clone()` is the explicit copy; borrow inference (§13) is the implicit mechanism |
| `docs/references/scala3-compiler-reference.md` | Recheck pattern (§5.4) |
| `docs/plans/implementation-plan.md` | Phase 4 deliverables (`perceus/PerceusInserter.scala`, region and witness emission); Phase 6 PGO witness specialization |
| `docs/plans/phase-3-typer.md` | M10 (§5.7) computes §20 |
