# Fixed Memory Management

**Status:** Draft v0.5.1
**Specifies:** how the compiler places, frees and reference-counts runtime values for Fixed v0.4.4 source: lifetime classes, immediates, data-stack regions, counted regions, the Perceus heap, destinations, value witnesses, reuse (FBIP), borrowing, effect-handler memory, structured parallelism, the `fip` contract and the memory report.
**Last revised:** 2026-11-20

**Changes from v0.5.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): an `Inline` value that is not an immediate is copied out to a cell with a descriptor where it meets code that does not know its type (Rule MM3.1); a `Static` cell carries a count word holding the immortal sentinel, and a `Heap` cell holds counted references only to cells with a count (Rules PR3.1, PR7.3); the type table has a row for every kind of cell (Rules PR3.1, MM7.3); Rules PR5.5 and PR5.6 cite the table convention of Rule CM4.6 instead of restating it; §20.3 no longer gives the static prover `assumed` props, places `E071`, `E083`, `E085` and the deferred-site binding, and states where a `proved` status from enumeration reaches; "header" is the descriptor plus the count word, and stale phrases about tags and headers are corrected.

**Changes from v0.4.** Applies decision P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`, D6, D7, D9, D11, D15, D16). A data type instance that holds no reference and fits in 64 bits is an **immediate**: no cell, no header, no count (§3.3, `spec/compilation_model.md` Rule CM3.5). The value witness gains `width`, and its family is fixed: `imm1`, `imm2`, `imm4`, `imm8`, `ref` (Rule MM7.3); `copy` takes size and field kinds from the link-time type table. A flat container stores elements at the witness's width (Rule MM16.3). The cell tag is unique in the link unit and indexes the type table; every cell, in every class, carries its tag and reference map (provisional, OQ-MM7), and only a `Heap` cell carries a count (Rule PR3.1). Rule PR7.3 states what the runtime's drop does now and what it still owes. Ownership clones are requested only where the run-time `rc == 1` test cannot stand in (Rule PR6.3). Calls through a capability table follow the convention derived from the requirement's signature (Rule PR5.5). §20.3 follows the pipeline of package compile, application link and test build; only `proved` and `assumed` props may justify reuse. §22.2 says that its `map` is what the derived `map` does. The "Recheck-style" label on a pass over Core is withdrawn.

**Changes from v0.3.** The compilation model is now `spec/compilation_model.md`: every function has a polymorphic **baseline** version that receives witnesses, and specialised **clones** are built on top of it under one size budget. In this document: the class witness of Rule MM7.3 becomes the value witness, gains `copy` and `kind`, and is the default instead of a boundary case; Rule MM7.4 defers to the clone rules; the cell header carries a reference map (Rule PR3.1); the version cap and copying beyond it are withdrawn (Rule PR5.5); a `fip fn` gains a copying shared entry and may be polymorphic (§18); `fip(n)` and `fip` on method requirements are specified (Rule MM18.5, resolving OQ-MM1); the signature facts of Rule MM20.2 are the core of the package interface. The callee, not the caller, evacuates at a floor entry (Rule MM5.5). Stale wording that still said "quantity" where ownership is meant was corrected.

**Changes from v0.2.** Reference-count traffic and reuse are decided by **demands** and **exclusive ownership** (`spec/quantities.md` §4.1), not by quantity 1: a binding used once is not necessarily a value owned once. Reuse is hybrid: unconditional for exclusively owned values, after a runtime `rc == 1` test for shared `Heap` values (Rule PR6.3). A caller holding a shared value calls the callee's shared version, or copies the demanded part beyond the version cap and into `fip` functions (`spec/quantities.md` Rule Q9.3). Borrowing is the `read` demand (§13). Closures whose parameter is `exclusive` carry two code pointers (Rule PR5.6). Escape facts, classes and demands are computed on Core after lowering (§20.3).

**Changes from v0.1 (`spec/perceus.md`).** The document is renamed and widened. v0.1 specified a pure Perceus model in which stack allocation was keyed on quantity 1. v0.2 separates two axes: **quantity** (0 / 1 / ω, `spec/quantities.md`) decides reference-count traffic, and **lifetime class** (§3) decides placement. Most values live in headerless, refcount-free data-stack regions in the style of Goose; the Perceus heap and counted regions hold only values that are long-lived in the sense of Rule MM5.2. Perceus rules keep their `PR` identifiers so existing citations stay valid; new rules use `MM`. Rule PR3.1.a (SCC stack allocation gated on escape) is superseded by §5 and §15. OQ-PR2 is resolved for structured parallelism (§17).

## 1. Scope

This document specifies:

- The lifetime classes, the ownership × class matrix and immediates (§3).
- The region-containment invariant that keeps every pointer valid (§4).
- How the compiler assigns a class to every allocation, including the definition of "long-lived" and evacuation (§5).
- The region runtime: chunked data stacks, counted regions, the number of stacks (§6).
- Destinations, calling convention and value witnesses (§7).
- The Perceus heap: header, `dup` / `drop`, last use (§8), demand-driven simplification (§9), RC insertion (§10), reuse (§11), drop specialization (§12), borrowing (§13).
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

- `spec/syntax_grammar.ebnf` v0.4.10 (`FipModifier` on functions, method requirements and `satisfies` methods).
- `spec/type_system.md` v0.4.1 — §6.5 (representation selection), §6.6 (built-in candidates), §7.5 (mutual recursion / SCC).
- `spec/quantities.md` v0.6 — demands and exclusive ownership (§4.1), Rule Q6.1 (erasure soundness), Rule Q7.2 (FBIP), Rule Q9.3 (shared and exclusive versions), Rule Q5.13 (parallel composition, §17).
- `spec/compilation_model.md` — the baseline version, the travel and rest forms and immediates (Rules CM3.3, CM3.5, CM3.6), witnesses, clones, the package ABI and the application link (§8, Rule CM8.8 for the type table). §7 and §20.2 of this document are its memory side.
- `spec/cost.md` v0.1 — cost contracts, of which `fip` is the zero case (§18).
- `spec/effects.md` v0.3 — §6 handler semantics, §7 evidence passing, Rule E3.5 (linear effects), Rule E7.8 (evidence captured by closures).
- `spec/pattern_matching.md` v0.2 — §8 match compilation.
- `spec/properties.md` — Rule P7.7 (statuses) and Rule P7.8 (the facts lane: only `proved` and `assumed` props inform reuse and bounds-check elision).
- `spec/diagnostics.md` — `inspect` and `trace` read the type table of Rule PR3.1.

Theoretical basis and prior art (facts checked against the sources in November 2026):

- Reinking, Xie, de Moura, Leijen, *Perceus: Garbage Free Reference Counting with Reuse*, PLDI 2021 — the heap algorithm (§8–§13).
- Lorenzen, Leijen, *Reference Counting with Frame-Limited Reuse*, ICFP 2022 — reuse can break garbage-freedom; drop-guided reuse bounds the extra retention per call (§11.5).
- Lorenzen, Leijen, Swierstra, *FP²: Fully in-Place Functional Programming*, ICFP 2023 — `fip` means no (de)allocation and constant stack; `fbip` relaxes stack and deallocation; Koka checks uniqueness at runtime (§18).
- Ullrich, de Moura, *Counting Immutable Beans*, IFL 2019 — borrow inference, reset/reuse, persistent objects with no RC, multi-threaded marking (§3, §13, §17).
- Tofte, Talpin and the MLKit retrospective (Tofte, Birkedal, Elsman, Hallenberg, HOSC 2004); Hallenberg, Elsman, Tofte, *Combining Region Inference and Garbage Collection*, PLDI 2002 — the region leak in tail-recursive loops that Rule MM5.2(b) is designed to avoid, and the case for a collector behind regions.
- van Oortmerssen, Goose (`docs/goose_spec.md`, v4 draft) — data stacks, roots, destination construction, one growable on top, `return … from`.
- Vollmer et al., Gibbon (ECOOP 2017) and LoCal (PLDI 2019); Koparkar et al., parallel Gibbon (arXiv 2107.00522) — location calculus, chunked regions that double, per-region reference counts with outsets, indirections for sharing (§6, §16).
- vekatze, Neut — types compiled to closed resource functions `exp(selector, v)`; the model for value witnesses (§7.3).
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
| `Static` | static data | descriptor and a count word holding the immortal sentinel | never (persistent; Lean's "persistent objects") |
| `Frame(s)` | the scratch data stack, region of scope `s` | descriptor, no count | bump reset at the end of `s` |
| `Dest(p)` | the destination supplied for result or parameter `p` | that of the destination's class | the destination's owner |
| `Region(r)` | counted region `r` | descriptor; one count per region, not per value | region count reaching zero (§6.4) |
| `Heap` | Perceus heap | descriptor and count (Rule PR3.1) | Perceus |

`Dest(p)` is resolved at each call site to one of `Frame`, `Region` or `Heap` of the caller (§7.1). The **descriptor** is the tag and reference map that every cell carries (Rule PR3.1). The **header** is the descriptor plus the count word, where a cell has one: a `Heap` cell has a count, and a `Static` cell a count word that holds the immortal sentinel. An immediate (§3.3) is always `Inline`. For any other value, `Inline` is decided by representation selection (`spec/type_system.md` §6.5); M11 may demote any class to `Inline` but never promotes.

An `Inline` value that is not an immediate has no descriptor where it lives. Where it meets code that does not know its type (a slot of parameter type, a `ref` witness, a value printed by generic code), it is first copied out to a cell with a descriptor, in the class that use requires. These are the copy-out sites that representation selection records (§20.3); there is no other way for such a value to reach generic code.

### 3.2 Ownership × class (Rule MM3.2)

Ownership (`spec/quantities.md` §4.1) decides reference-count traffic and reuse; class decides placement. The two are independent: an exclusively owned value can still escape (be returned), and a shared value can still die with its scope.

| | `Frame` / `Dest` on a stack | `Region` | `Heap` | `Static` |
|---|---|---|---|---|
| **erased (demand `0`)** | erased | erased | erased | erased |
| **exclusively owned** | no RC; freed by reset; reused unconditionally | no RC; the single owner releases one region reference when it is consumed | no RC ops; freed or reused at its last use without a test | no RC |
| **shared** | no RC; freed by reset; no reuse | region-count `dup` / `drop`; no reuse | Perceus `dup` / `drop`; reuse after `rc == 1` | no RC |

A `read` demand adds nothing to any cell: the callee borrows (§13).

An immediate (§3.3) has no row in this matrix: it is copied with its slot, has no count, is never reused and is never a member of a region.

**Consequence (the goal of this model).** Per-value reference-count operations exist only for values that are both shared and `Heap`. Counts exist only on `Heap` cells and once per `Region`; the count word of a `Static` cell holds a sentinel that never changes.

### 3.3 Immediates (Rule MM3.3)

A data type instance that holds no reference and fits in 64 bits is an **immediate** (`spec/compilation_model.md` Rule CM3.5, which fixes the condition and the bit layout). It travels inside its slot (Rule CM3.3) and is stored inside a flat container at its width (Rule CM3.6, Rule MM16.3). It has no cell, no header and no count. The rule holds for every data type, parameterised or not; for a parameterised type it is a function of the witnesses of the arguments, so baseline code and clones agree on which instances are immediates. A type with a field that is always a reference, which includes every recursive type, is statically a cell.

Consequences in this document:

- An immediate is not an allocation. Class assignment (§5) does not see it, Rule MM4.1 does not constrain it, and it is never a member of a `Frame`, `Region` or cohesive group.
- `dup`, `drop`, `consume`, `reuse_test` and evacuation of an immediate are nothing; its value witness is `imm1`, `imm2`, `imm4` or `imm8` (Rule MM7.3).
- An immediate never yields a reuse token (§11) and never needs one. A `fip fn` that builds immediates allocates nothing (Rule MM18.1(1)).
- Where generic code holds a value of a parameterised type that may be an immediate, every operation that depends on a cell (field access, reuse, the reference map entry) takes the cell path only when the instance's witness is `ref`. Field access goes through the type's accessors (`spec/compilation_model.md` Rule CM3.7).

The reason is cost: a small value without references gains nothing from a cell and pays an allocation, a header and a count for it.

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
- passed to an exported version of a function of another package whose interface records escape `unbounded` for that parameter (§20.2). A call of a **floor entry** does not make the argument long-lived: there the callee evacuates what it stores (`spec/compilation_model.md` Rule CM6.3);
- shared with a lane and outliving the fork (§17).

**(b) Non-garbage-free churn.** It is the carried value of a `fold` or `unfold` (the accumulator or the seed) and the step is not **garbage-free** for it.

A step that turns old carried value `c` into new carried value `c'` is garbage-free for `c` iff every cell of `c` that becomes unreachable at the end of the step is reused by a constructor building `c'` (§11). Cells of `c` that stay reachable from `c'` cost nothing (sharing, append, cons onto it). Cells allocated in the step and reachable from `c'` are growth, which is allowed. Cells allocated in the step and not reachable from `c'` are per-step temporaries and get `Frame(step)`.

If the step leaves garbage, putting the carried value on a data stack would keep every dead version alive until the loop ends. That is the MLKit tail-recursion leak. Such a carried value is long-lived and goes to `Heap`, so Perceus frees dead versions as they die.

The garbage-free check runs at constructor and variant level, the way FP² checks `fip` at source level. It does not need representation sizes; M11 only refines the reuse fingerprints (§11.2).

In a baseline version the check cannot see inside a value of parameter type. A step is garbage-free for a carried value of parameter type only if the capability member that rebuilds it carries an in-place contract (`constructs − releases ≤ 0`, `spec/cost.md` Rule CT3.4); otherwise the carried value is long-lived. A clone for a known type repeats the check on the type.

Duration is never a criterion. Totality guarantees termination, not short running time.

### 5.3 Class choice (Rule MM5.3)

For each allocation, in order (an immediate is not an allocation, §3.3):

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
- a value of parameter type that a baseline version stores into a longer-lived value: the callee evacuates it through the value witness (§7.3). This is also how a floor entry treats every owned parameter;
- a subtree extracted from a packed blob to outlive it (§16.2).

Evacuation makes analysis imprecision a performance cost, never a soundness hole. It is reported per site (§19).

Copying the demanded part of a shared value in the shared entry of a `fip fn` (§18.3) is a different operation: it produces a second, exclusively owned value in the same class, through the witness operation `copy`. It is reported with the evacuations and warned by W110 in the same way.

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
- `dup` / `drop` on a pointer into `r` updates `r`'s count. The region descriptor is found by masking the pointer (§6.1), so a value in `r` needs no count of its own.
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

A result of parameter type in a baseline version is returned as a slot (`spec/compilation_model.md` Rule CM3.3): an immediate in the slot itself, a reference as a pointer to a value built in the destination.

### 7.2 Borrowed parameters are uniform (Rule MM7.2)

A borrowed parameter (§13) needs no RC operation and is never stored beyond the call, so the argument's class does not matter in the callee. Borrowed parameters use one calling convention for every class.

### 7.3 Value witnesses (Rule MM7.3)

A baseline version (`spec/compilation_model.md` §3) receives a hidden **value witness** for each of its non-phantom type parameters, and for each owned parameter whose class its signature facts (§20.2) do not fix (`spec/compilation_model.md` Rule CM4.1). Earlier drafts called it the class witness and passed it only at higher-order and separate-compilation boundaries; it is the default, and it disappears only in a clone (§7.4).

**The family is fixed.** A value witness is one of `imm1`, `imm2`, `imm4`, `imm8` (an immediate of that width, §3.3) or `ref` (a cell). An immediate needs no `dup`, `drop` or `copy`, so its witness does not depend on its type or class. A `ref` witness does not depend on the type either: what is specific to a type (size, field kinds) is read from the cell's descriptor and the link-time type table (Rule PR3.1, `spec/compilation_model.md` Rule CM8.8). `ref` has one static instance per class that can hold a cell (`Frame` and stack `Dest`, `Region`, `Heap`, `Static`), because `drop` and `reuse_test` differ by class. Generic code picks a witness from this family, for a parameterised instance as a function of the witnesses of its arguments (Rule CM3.5); it never builds a value witness at run time. The reason is that a witness per type instance would have to be built where the instance first appears, which baseline code cannot do without allocating.

```
witness(op, v, dest)    op ∈ { drop, dup, reuse_test, evacuate, copy, size, width, kind }
```

For `ref`:

| op | `Frame` / stack `Dest` | `Region` | `Heap` | `Static` |
|---|---|---|---|---|
| `drop` | no-op | region decrement | Perceus decrement | no-op |
| `dup` | identity | region increment | Perceus increment | identity |
| `reuse_test` | true iff exclusively owned (static) | true iff exclusively owned (static) | true if exclusively owned (static), else `rc == 1` | false |
| `evacuate(dest)` | copy into `dest` | copy into `dest` unless `dest` is this region | identity if `dest` is the heap, else copy | identity |
| `copy(dest)` | a new exclusively owned value in `dest` | the same | the same | the same |
| `size` | byte size, from the type table | the same | the same | the same |
| `width` | 8 | 8 | 8 | 8 |
| `kind` | reference | reference | reference | reference |

For `immk` (k ∈ {1, 2, 4, 8}), in every class: `drop` is a no-op; `dup`, `evacuate` and `copy` return the slot; `reuse_test` is false; `size` and `width` are k; `kind` is immediate.

- A witness is passed per type parameter or per class-polymorphic owned parameter, not per value. A container's witness covers its elements, because Rule MM4.1 lets a `Frame` container hold `Heap` elements but fixes the element class for the whole container.
- `reuse_test` follows Rule PR6.3: exclusively owned cells are reusable in every class except `Static`.
- `copy` copies the whole value: a witness cannot know which part a callee demands. It reads the cell's tag, finds the cell's row in the type table (`spec/compilation_model.md` Rule CM8.8, which says what each kind of row gives), and takes the kind of each slot of parameter type from the cell's reference map. The shared entry of a `fip fn` (§18.3) copies a demanded part of known type with code for that type and that part, and uses the witness's `copy` only for a part of parameter type. `evacuate` cannot serve, because it is the identity when the value already lives where `dest` is.
- `width` is the number of bytes an element takes in a flat container (Rule MM16.3).
- `kind` fills the reference map of a cell that a baseline version builds (Rule PR3.1).
- Witnesses replace any address-range test for "is this on a stack". Chunked regions (§6.1) have no fixed address range, so a test would need tag bits; the witness does not.
- Neut copies on every share. Fixed copies only on evacuation and keeps reference counting for `Heap` values.

### 7.4 Specialization (Rule MM7.4)

1. A baseline version always takes its witnesses, and their operations are indirect calls.
2. A **clone** (`spec/compilation_model.md` Rule CM5.1) compiled for a known class has no witness for it: `drop` of a `Frame` value is nothing, `drop` of a `Heap` value is the inline Perceus decrement.
3. Which clones exist is decided by the score within the clone budget (Rules CM5.3, CM5.4). With profile data (Phase 6) the measured request counts replace the static weights, and a baseline version may also test for a dominant witness inline, for example `if w == FRAME_W { /* no-op */ } else { w(drop, v) }`.

A function larger than the budget has no clones and keeps its one witness-taking version. This is the rule Lobster applies to large and dynamically dispatched functions, now a consequence of the budget instead of a separate threshold.

## 8. The Perceus heap

### 8.1 Heap-object layout (Rule PR3.1)

Every cell carries a **descriptor**; a `Heap` or `Static` cell also carries a count word in front of it, and the two together are its **header**:

```
{
    rc: i64,            // refcount in Heap cells; the immortal sentinel in Static cells; absent otherwise
    tag,                // unique in the link unit; indexes the type table
    refs: <map>,        // which payload slots hold counted references
    pad: [N x i8],      // padding to natural alignment
    payload: <variant fields in declaration order, one 64-bit slot each>
}
```

**Tag.** The tag is unique in the link unit, for every variant of every data type, product types included, and for every other kind of cell (closures, strings, flat containers, blobs). It indexes a link-time **type table** with one row for every kind of cell (`spec/compilation_model.md` Rule CM8.8). The link step assigns each type a base; a `match` subtracts the type's base, a link-time constant, and then switches as before. The table gives `copy` and `size` (Rule MM7.3) what a type-independent witness lacks, and gives `inspect` and `trace` the names they print (`spec/diagnostics.md`). The reason the tag is global and not per type: generic baseline code holds cells whose type it does not know.

**Reference map.** `drop` must free a cell without knowing which type instance built it, so the descriptor says which payload slots hold counted references (`spec/compilation_model.md` Rule CM3.4). A slot of a known type has a fixed entry. A slot of parameter type gets its entry from the value witness (`kind`, Rule MM7.3) when a baseline version builds the cell, and a constant in a clone. The widths of `tag` and `refs` and the encoding of the map are implementation-defined. A count of leading reference slots is not enough once a cell has a slot of parameter type, because the order of fields would then depend on the type instance.

**What stays.** Fields stay in declaration order, one 64-bit slot each, and each type has one cell layout. Cells stay self-describing: `drop` needs no type information. A cell can hold references only in its first 32 payload slots (30 captures for a closure); beyond that the compiler reports `B005`. Narrow fields inside a cell are not packed (accepted as lost in P6 D7; the allocator's rounding absorbs most of it).

**Every cell is self-describing.** `copy` and `evacuate` of a cell reached through a `ref` witness, and `inspect` of a value of parameter type, read the descriptor whatever the cell's class. `Frame` cells, stack `Dest` cells and `Region` cells therefore carry the descriptor and no count; the count of a `Region` is in its region descriptor (§6.4). `Inline` values, immediates (§3.3) and `Erased` values have no descriptor; an immediate holds its variant index, not a link-unique tag, in its low byte (Rule CM3.5), and an `Inline` value that is not an immediate gets a descriptor when it is copied out (Rule MM3.1).

**Counted references and the sentinel.** A `Static` cell carries a count word holding the **immortal sentinel**, a negative value. `dup` and `drop` leave it alone; the runtime's generic drop (Rule PR7.3) tests for it. A `Heap` cell holds counted references only to cells that have a count word (`Heap` and `Static` cells) and into a `Region` through the region's count (§6.4). A cell of a class without a count (`Frame`, stack `Dest`) is never the target of a counted reference from a `Heap` cell: Rule MM4.1 forbids the pointer. The reference map therefore needs no second bit to tell counted children from uncounted ones.

**Measured cost.** The first measurement charged this header with 6–8% on list-heavy programs. A bisection showed that the cost was a call, not the map: the child walk sat in a runtime helper that `clang -O2` did not inline. The runtime was fixed (Rule PR7.3); the header, the layout and the map stayed (`docs/baselines/runtime-2026-11-20-phase-4.1.md`, addendum). An 8-byte header with a 32-bit count, as in Koka and Lean, is a hypothesis (§21), not a decision.

The v0.1 rules that tied stack allocation to quantity 1 and a size budget, and Rule PR3.1.a (SCC stack allocation gated on escape analysis), are superseded by §5. Size alone never sends a value to the heap.

### 8.2 `dup` and `drop` primitives (Rule PR3.2)

- `dup(x)` — increment `x`'s count. No-op unless `x` is shared and `Heap` or `Region`.
- `drop(x)` — decrement `x`'s count; at zero, drop its fields and free the memory (heap) or free the region (§6.4). No-op for `Frame`, stack `Dest`, `Static`, `Inline` and `Erased`. Exclusively owned values use `consume` (Rule PR3.5).

`dup` and `drop` are emitted by the compiler (§10); they are not user-visible. Where the class is unknown they are witness calls (§7.3).

### 8.3 Last-use semantics (Rule PR3.3)

For every binding `x` introduced at program point P, the compiler emits exactly the `dup` / `drop` pairs needed to balance count changes between P and the end of `x`'s lifetime. The owning scope is responsible for dropping; passing `x` to an owned parameter transfers the drop obligation.

The last use of a binding is the last program point in IR order where the binding is referenced. The compiler emits `drop` immediately after the last use (or at branch entry, per §10.4).

### 8.4 Shared values (Rule PR3.4)

Shared values of class `Heap` or `Region` carry Perceus RC: `dup` at share points, `drop` at last use, reuse after a runtime test (§11). Shared values of any other class carry no RC; their region frees them.

### 8.5 Exclusive ownership is `consume` (Rule PR3.5)

An exclusively owned value (`spec/quantities.md` Rule Q4.1.b) has no `dup` and no count-aware `drop`. Its last use is a **`consume`**: the value is moved, and the binding is dead afterwards. For `Frame` and stack `Dest` values, `consume` is a no-op. For `Heap` values, `consume` transfers ownership, and the final owner frees without testing a count. For `Region` values, the final owner releases its single region reference.

This is what makes FBIP guaranteed for exclusively owned values: at the consume site, the memory is unique to this code path. Quantity 1 alone does not give this: a binding used once may hold a shared value.

## 9. Demand-driven simplification

| Value | Allocation | RC ops | Reuse |
|---|---|---|---|
| **Erased** (demand `0`) | None | None | N/A — the value does not exist at runtime. |
| **Exclusively owned** | By class (§5). | No `dup`; no count `drop`; `consume` at the last use. | Guaranteed, no test. |
| **Shared** | By class (§5). | `dup` / `drop` only if `Heap` or `Region`. | `Heap`: after `rc == 1`. `Frame`, `Region`: none (§11.3). |

A `read` demand costs nothing at the call (§13). Exclusivity removes RC operations; class removes them for shared values outside the heap. Together they confine per-value RC to shared ∧ `Heap` (Rule MM3.2).

## 10. RC insertion algorithm

The compiler implements the Reinking et al. (2021) algorithm, restricted to bindings of class `Heap` or `Region` and with the demand simplifications above. On a value of parameter type in a baseline version, `dup` and `drop` are calls of the value witness (Rule MM7.3).

### 10.1 Input form (Rule PR5.1)

The pass runs after match desugaring (`spec/pattern_matching.md` §8) and consumes the decision tree plus, per binding, its ownership and class (§20).

Input invariants:

- Every binding is annotated with its ownership (exclusive or shared, per part), and every parameter with its demand.
- Every binding is annotated with its lifetime class.
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

- Erased bindings: skipped.
- Exclusively owned bindings: no `dup`; `consume` at the last use. A use that keeps the value while the binding stays live makes it shared from there on (`spec/quantities.md` Rule Q4.1.b).
- Shared bindings of class `Heap` or `Region`: the standard algorithm.
- Shared bindings of any other class: skipped; the region frees them.

### 10.4 Branching (Rule PR5.4)

At a branch point, for each RC-tracked binding `x` in the live set: if a branch does not use `x`, insert `drop x` at branch entry; if it does, emit `dup`s within the branch as needed. Every branch exits with the same count budget.

### 10.5 Function calls (Rule PR5.5)

At a call `g(a, b, …)`, arguments evaluated left to right and moved when the call starts (`spec/quantities.md` Rule Q9.2):

- For a `read` parameter (§13): no `dup` / `drop` transfer.
- For an `own` parameter: at the argument's last use, ownership transfers (no `dup`); otherwise `dup` at this position.
- For an `exclusive` parameter: if the argument is exclusively owned, this is its last use, no other argument or capture of the call reaches it (Rule Q9.4), the argument has no per-value count or the callee is `fip` (Rule PR6.3.a), and the exclusive version of `g` was built (`spec/compilation_model.md` Rule CM5.3), it is moved into that version. Otherwise the call goes to `g`'s baseline version, which is the shared one. For a `fip` callee the shared entry copies the demanded part first (Rule CM5.5). No caller copies.
- For a call through a capability table: the convention derived from the requirement's signature (`spec/compilation_model.md` Rule CM4.6, with its table of examples).
- For a call through a closure whose identity is unknown, and for an effect operation (`spec/effects.md` Rule E7.7): every argument is passed under `own`.
- For a `0` parameter: no value is passed; an effectful argument is still evaluated.
- If the callee is an exported version of another package whose interface records escape `unbounded` for the parameter, and the argument is not `Heap`, `Region` or `Static`: evacuate (Rule MM5.5). A floor entry needs nothing from the caller: it evacuates in the callee.

### 10.6 Closures (Rule PR5.6)

A closure capturing a binding `y`:

- At construction: `dup y` per capture if `y` is RC-tracked.
- At invocation: the capture is consumed by the body per the body's quantity rules.
- At closure drop: each RC-tracked capture is dropped.

A closure whose environment does not escape its creation scope is itself `Frame` (no count). An escaping closure is `Heap` and its captures follow Rule MM4.1: a `Frame` capture of an escaping closure is long-lived by Rule MM5.2(a) and is placed accordingly.

An exclusively owned capture stays exclusive only if the closure is called at most once; otherwise it is downgraded to `own` and shared (`spec/quantities.md` Rule Q5.7).

**Two entry points.** A closure whose parameter is `exclusive` carries two code pointers: the closure body compiled for exclusive input, and compiled for shared input. A call through a closure whose identity is unknown at compile time (a function-typed parameter, a closure taken from a data structure) picks the pointer statically from what the caller holds. It may take the exclusive pointer only when it exclusively owns every argument in every part, because it cannot see which parameters the closure's body reuses; otherwise it takes the shared pointer. There is no runtime test. Because a caller cannot tell from a closure it did not create whether it has a second entry, **every** closure carries both pointers. The **shared** pointer is the primary one; the exclusive pointer is the same function unless an exclusive version of the body was built (`spec/compilation_model.md` Rule CM5.3). The cost is one word per closure (closures without captures are static objects) and the second body where it exists.

Every entry of a capability table also carries two pointers (Rule CM4.2); what each assumes is Rule CM4.6. A closure created in a baseline version captures the witnesses its body uses, and the evidence of the effects its type does not mention (`spec/effects.md` Rule E7.8).

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

For an exclusively owned `x`, `drop_to_reuse` returns the memory unconditionally. For a shared `Heap` `x`, it returns the memory if `rc == 1` and otherwise decrements and returns a null token, so the constructor allocates fresh. This dynamic path is Koka's technique; it catches values that were shared but whose other owners have died by the time of the reuse, which no static analysis can see in general.

### 11.2 Reuse fingerprints (Rule PR6.2)

Two shapes match for reuse iff they have the same byte size after padding, the same alignment and the same field kind sequence. Matching is by fingerprint, not nominal type, so variants of different types can reuse each other's memory.

A slot of parameter type is a field kind of its own: it matches only another slot of parameter type. Fingerprints therefore do not depend on the type instance, and a baseline version reuses cells as its clones do. For a parameterised type that may be an immediate, a baseline version takes the reuse path only when the instance's witness is `ref` (§3.3); a clone knows statically.

### 11.3 Ownership-driven reuse (Rule PR6.3)

Reuse is **hybrid**: static where exclusivity is proved, dynamic for shared heap values.

- Exclusively owned (`spec/quantities.md` Rule Q4.1.b): reuse is unconditional, in any class except `Static` (static data is never written). Exclusivity is proved statically, through `exclusive` demands and the exclusive versions of Rule Q9.3. Quantity 1 alone is not enough.
- Shared, `Heap`: reuse is conditional on `rc == 1` at runtime.
- Shared, `Frame` or `Region`: no reuse. Without a count, uniqueness is unknown, and the memory is freed with its region anyway.
- Immediates: no reuse; there is no cell (§3.3).

**Rule PR6.3.a (which ownership clones are requested).** A call site requests an exclusive version of its callee (an ownership clone, `spec/compilation_model.md` Rule CM5.1) only where the run-time `rc == 1` test cannot stand in for the static proof:

1. the body of a `fip fn`, and every clone it requests (mandatory, Rule CM5.5);
2. an argument that has no per-value count: a `Frame`, stack `Dest` or `Region` value, which the baseline cannot reuse at all.

A `Heap` argument does not request one: the baseline version tests `rc == 1` and reuses. The reason is the first measurement: on five benchmarks the run-time test ran as fast as unconditional reuse (`docs/baselines/runtime-2026-11-20-phase-4.1.md`, finding 5), so a clone for a `Heap` value spent budget for nothing. That measurement is suspected of hiding a compiler fault (§21); until it is ruled out, this rule is provisional.

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

- Sum types: switch on the tag minus the type's base (Rule PR3.1), drop each field of the variant, then free.
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

In a baseline version, and wherever else the type or class is not known, the drop goes through the value witness (§7.3).

### 12.3 Drop in bounded native stack (Rule PR7.3)

Dropping a value must run in bounded native stack, whatever the shape of the value. A deep structure must not exhaust the stack when its last reference dies.

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

**The runtime's generic drop.** A drop through a witness, or of a cell whose type the code does not know, is done by the runtime from the reference map (Rule PR3.1). Today it is two pieces: `fx_drop` is a short fast path that leaves a count holding the immortal sentinel alone (Rule PR3.1), and otherwise decrements the count and returns; a count that reaches zero calls one out-of-line `free_cell`, which drops the marked children, frees the cell and continues on the highest marked child instead of recursing. A list, whose tail is its highest reference slot, is therefore freed in a loop. The fast path is kept short so that it is inlined at every drop site; the first implementation lost 6–8% by placing the child walk in a helper that was not inlined (Rule PR3.1, "Measured cost").

**Still owed.** `free_cell` still recurses on every marked child except the highest. A tree that is deep in a child other than the last (a list built in the other field, or a left-leaning tree) can exhaust the native stack. A conforming runtime keeps an explicit work list of cells whose count reached zero, for example threaded through the payload of cells already being freed, so that drop uses constant native stack for every shape. Until then the runtime does not conform to this rule.

## 13. Borrowing (inferred)

### 13.1 Borrow analysis (Rule PR8.1)

A function parameter `p` is **borrowed** iff its demand is `read` (`spec/quantities.md` Rule Q4.1): the body never stores `p` in a constructed value, never returns `p` (or a value containing `p`), and never passes `p` to an `own` or `exclusive` parameter. The analysis is a fixed point over the call graph, the same fixed point that infers every demand.

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

The dynamic extent of a handler installation is also a region in the sense of Rule MM4.1, for one purpose: a closure that captured the handler's evidence must not outlive it (`spec/effects.md` Rules E5.5.b, E7.8). The check uses the escape sets of §20.1; it allocates nothing in that region.

### 14.2 Abortive operations (Rule MM14.2)

Installing a handler records a mark on every data stack in use. When an abortive clause completes, control returns to the handler, and every frame between the operation and the handler is left.

- Each frame left runs its epilogue on the way out: owned `Heap` and `Region` references are dropped and drop lists are walked. Frames check a hidden per-frame discriminant, as Goose does for `return … from` and as Koka does with yield bubbling. There is no separate unwinder.
- Every data stack is reset to the handler's mark. Partial results built by the frames left are abandoned.
- The clause's value is built in the handler's destination.

### 14.3 Capturing handlers and fiber stacks (Rule MM14.3)

The subject of a capturing handler runs on **fiber data stacks** owned by the handler installation: a fresh scratch stack and fresh growable stacks, small first chunks (§6.1), drawn from a per-thread pool.

- Capturing the continuation freezes the native stack segment and the fiber stacks together.
- The continuation object is resumed at most once (Rule Q5.11), exclusively owned and `Heap`. It owns its fiber stacks. `resume` continues on them; when the resumed computation finishes, or the continuation is dropped unresumed, the fiber stacks return to the pool.
- **Local capture.** The continuation cannot outlive the `handle` expression, and the `handle` expression lies inside the scopes of every outer `Frame` region. Suspended frames may therefore keep borrowing outer `Frame` values.
- **Escaping capture.** A `Frame` value of an outer scope that the continuation can reach, and whose scope may end before the continuation is dropped, is long-lived by Rule MM5.2(a). It is promoted to its long-lived class, or evacuated into the fiber at install time.
- The subject's result, and values flowing to the handler's result, are built in the handler's destination or evacuated there when the handler returns.

Example: the scheduler in `examples/08_effects_handlers.fixed` runs `remaining.push(() -> resume(()))`. The continuation is stored in a queue that outlives the fold step, so the handler is capturing-escaping. The task closure comes from the queue (`Heap`), and the fiber holds the task's own temporaries.

Multi-shot resume (OQ-Q1) would break single ownership of the fiber stacks and require copying them. Keeping OQ-Q1 closed keeps this rule simple.

### 14.4 Continuation memory (Rule PR9.1)

The continuation object (§14.3) has a header and is exclusively owned (it is resumed at most once, Rule Q5.11): no `dup`, `consume` at `resume`, freed (or reused for another continuation) after the resume.

### 14.5 Linear effect handlers (Rule PR9.2)

A linear effect (`spec/effects.md` §3.5) allows at most one operation per code path. Its handler frame lives only during the subject's evaluation; linearity prevents a residual continuation from outliving the handler.

**Exclusive captures across linear clause bodies (Rule PR9.2.a).** Per Rule Q7.4, a linear-effect clause body has quantity 1, so an exclusively owned outer value captured into it stays exclusive and generates no `dup`. Non-linear clause bodies remain quantity-ω closures (Rule Q5.7); an exclusive capture into one is downgraded to `own` and shared, which is not an error. Rules Q7.4 and PR9.2.a are contingent on OQ-Q5 and gated by the same flag as `linear effect`.

### 14.6 Tail-resume (Rule PR9.3)

When a clause's `resume(expr)` is in tail position (`spec/effects.md` §7.5), no continuation is allocated, the stacks are preserved, the clause runs in place of the operation call, and there is no RC traffic on a continuation. This is the common case for I/O handlers.

## 15. Mutually recursive data

Mutually recursive data types form an SCC (`spec/type_system.md` §7.5). SCC membership no longer forces heap allocation: each value of an SCC type gets its class by §5 like any other value. When values of an SCC are `Heap`:

### 15.1 Shared header layout (Rule PR10.1)

All members of an SCC share one header layout; their tags, unique in the link unit (Rule PR3.1), already distinguish every variant of every member. Drop functions for SCC members may share fingerprints, which enables reuse across members.

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

The compiler never changes the layout of a declared data type: each type has one cell layout (Rule PR3.1), and narrow fields inside a cell are not packed. Density comes from selection instead. A packed or dense representation is a satisfier of a capability (a packed array, a packed tree), and the selector picks it at application link when usage favours it (`spec/compilation_model.md` Rule CM8.4). The compiler does not repack the cells of another type.

### 16.1 Blobs (Rule MM16.1)

When the selected satisfier has a packed layout (nested data inline, variable-size variants, narrow relative-offset links), the whole packed value is one **blob**:

- A `Frame` or stack `Dest` blob has one descriptor (Rule PR3.1) and no count.
- A `Heap` blob has one header (count and descriptor) for the whole blob. A `Region` blob uses its region's count.
- References to interior subtrees are borrows bounded by the blob owner's lifetime. They never carry their own count.

Relative-offset links make a blob position-independent: it can be relocated (§6.3), evacuated or copied to another memory pool as plain bytes.

### 16.2 Copy-out (Rule MM16.2)

A subtree that must outlive the blob's owner is **copied out** (evacuated) into the class its use requires. It is never kept as an interior pointer. This prevents a small subtree from keeping a large blob alive.

Representation scoring must account for this. `spec/type_system.md` §6.5.4 "access fit" charges packed candidates for the expected volume of escaping subtree extraction, so the selector does not pick a packed layout for a workload that would copy out constantly.

### 16.3 Flat containers and the rest form (Rule MM16.3)

A value travels in a 64-bit slot (`spec/compilation_model.md` Rule CM3.3). How it is stored inside a **flat container** (an array or any satisfier that lays its elements out contiguously) is its **rest form** (Rule CM3.6): each element takes the `width` of its value witness (Rule MM7.3), 1, 2, 4 or 8 bytes. An immediate is stored at its width; a cell is stored as an 8-byte reference.

- In baseline code the stride comes from the witness; in a clone it is a constant.
- Widening on load and narrowing on store are those of Rule CM3.3.
- A container of immediates holds no reference to drop, whatever the element type. How the descriptor of a container with more than 32 reference elements describes them is open (OQ-MM6): the reference map of Rule PR3.1 covers 32 slots.

The reason: an array of `u8` or of a two-byte enumeration should take one or two bytes per element, and generic code that only moves elements can still do so from the witness alone.

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
2. perform no RC operation (`dup`, `drop`, `reuse_test`, region count update). A witness call for any of these on a value of parameter type counts as one (`spec/compilation_model.md` Rule CM4.7);
3. insert no evacuation;
4. register nothing on a drop list;
5. use **bounded** data-stack space: the number of bytes bumped per call has a static upper bound. Bumps inside a `fold` / `unfold` step count once, because they reset per step (Rule MM5.1).

Allowed: writing into destinations supplied by the caller, `Frame` temporaries within the bound, and unconditional reuse of exclusively owned cells. Native stack use is bounded by the fold structure (a `fold` over a tree uses depth proportional to the tree's depth).

### 18.2 `fip(strict) fn` (Rule MM18.2)

The FP² meaning. In addition to Rule MM18.1:

1. no data-stack bump at all: every constructed value either reuses a consumed cell, is an immediate (§3.3) or is `Inline`;
2. constant native stack: every fold the function performs compiles to a loop (list-shaped folds, tail modulo constructor).

### 18.3 Arguments of `fip` functions (Rule MM18.3)

A parameter that a `fip` function reuses must be `exclusive` in its signature (`spec/quantities.md` §4.1), so reuse is unconditional and needs no count test. Koka checks uniqueness at runtime and falls back to allocating; for `fip` functions Fixed proves it statically.

A `fip fn` has **one body**: the version in which every part it reuses is exclusively owned. It has no shared body. Its **shared entry** is an adapter that copies the demanded part of each shared argument (the witness operation `copy`, `spec/quantities.md` Rule Q9.3) and then calls the body (`spec/compilation_model.md` Rule CM5.5). A caller holding a shared value calls that entry; it does not copy at the call site. The adapter is not `fip`, so such a caller is not `fip` either, and the copy is reported (and warned in hot code, W110).

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

### 18.5 Polymorphic `fip`, `fip(n)` and `fip` on members (Rule MM18.5)

- **Polymorphic.** `fip` is checked on the function's one body, with every witness operation on a value of parameter type counted as an RC operation (Rule MM18.1(2)). A `fip fn` is therefore in place for every instantiation: it may move values of parameter type and may not duplicate or discard them. A call through a capability table is allowed only if the requirement is itself `fip`. Clones inherit the result.
- **`fip(n) fn`.** As `fip fn`, except that item 1 of Rule MM18.1 allows at most `n` fresh `Heap` or `Region` cells per call, for a literal `n`. This is FP²'s `fip(n)`.
- **On members.** The modifiers may be written on a capability method requirement, where they oblige every satisfying type, and on a `satisfies` method.

`fip` is the zero case of the allocation contract of `spec/cost.md` (Rule CT4.3), which also states bounds that depend on sizes. This resolves OQ-MM1.

## 19. The memory report

`fixed compile --explain-memory` prints, per binding and per allocation site:

- quantity, lifetime class, and whether RC operations are emitted;
- a **reason chain** for every class other than `Frame` and `Inline`, for example: "`Heap`: reachable from a continuation that escapes `handle` at 08:187 via `remaining.push`";
- evacuation sites, drop lists, counted regions and their roots;
- per function: the signature facts of §20.2 and the verified `fip` level;
- per function: the size of its baseline version, every clone built with its key, score and charge, and every candidate not built with the reason (`spec/compilation_model.md` Rule CM5.8);
- per function: the inferred `steps`, `constructs` and `releases`, and the status of each cost prop (`spec/cost.md` Rule CT6.4);
- per `fold` / `unfold`: whether it is a fork site and, when it is not, the reason (`spec/quantities.md` Rule Q5.13.d).

`--format json` gives the same data machine-readably (implementation plan, "Agent-Friendly CLI"). An AI agent reads the reason chains directly instead of reverse-engineering placement from generated code.

## 20. What M10 records, and phase placement

### 20.1 Per binding and per construct (Rule MM20.1)

M10 (`docs/plans/phase-3.4-qtt.md`) computes, in one walk over Core after lowering, together with demands:

- **Per binding:** quantity; ownership (exclusive or shared, per part); **escape set** ⊆ {`result`, `field(b)`, `closure(c, escapes?)`, `continuation(h, escapes?)`, `carried(loop)`, `lane-shared(fork)`, `unbounded`}; lifetime class (§5.3), with `Inline` left to M11; the reason chain (§19).
- **Per scope:** the allocations reset at its end; drop-list registrations.
- **Per `fold` / `unfold`:** whether the step is garbage-free for the carried value (Rule MM5.2(b)).
- **Per cohesive group:** its root and members (Rule MM5.4).
- **Per handler install:** its kind (Rule MM14.1).
- **Per function:** the signature facts below, and the verified `fip` level.
- **Requirements for M11:** values under construction that grow at the same time (for N, Rule MM6.3), and packed-subtree extractions that would need copy-out (Rule MM16.2). M11 resolves both when it commits representations.

Escape facts are data flow and do not depend on representation, so all of the above except the two M11 requirements is final after M10.

### 20.2 Signature facts (Rule MM20.2)

These facts appear in every function signature. For a public function they are stated for its floor entry and for each exported version, and they are the core of the package's interface tier (`spec/compilation_model.md` Rules CM6.3, CM6.4, CM7.1):

- **Per parameter:** demand per part (`0`, `read`, `own`, `exclusive`; `spec/quantities.md` Rule Q4.1.a), which replaces the separate quantity and borrowed/owned mode; escape ∈ {`none`, `into-result`, `unbounded`}; class-polymorphic or fixed (decides whether a witness is passed, §7.3).
- **Versions:** the clones built, each with its key (`spec/compilation_model.md` Rule CM5.1); for a public function, the exported versions and whether each is a body, an alias or an adapter (Rule CM6.4).
- **Witnesses:** the hidden parameters and their order (Rule CM4.3).
- **Result:** destination-polymorphic or a fixed class. Who picks its representation follows from the signature (Rule CM4.5): for `-> R is C` the caller passes `R`'s witness; for `-> is C` the result is a choice point owned by the function and its witness travels with the value.
- **Stack demand:** the maximum number of growable stacks in use at once, and the static bound on scratch bumps if one exists.
- **Contracts:** the `fip` level (none, `fip(n)`, `fip` or `fip(strict)`) and the cost props with their statuses (`spec/cost.md`).

Lean ships borrow annotations in its interface files in the same way.

### 20.3 Phase placement

The work is split over three builds (P6 D6). Property verification is three separate things, each placed where its input exists: the static prover, the cost checker and property-based testing.

**Package compile**, in order:

| Step | Provides |
|---|---|
| Typer | Typed tree |
| Static prover (`spec/properties.md` Rule P7.1), on the typed tree | `proved` props other than cost props; the rest stay open for `fixed verify` |
| Quantity inference (`spec/quantities.md` §4), on the typed tree | Occurrence quantities; `E072` (`resume`), `E083` (linear effect), `T021` |
| Lowering to Core, carrying source spans; match desugaring (`spec/pattern_matching.md` §8) | Polymorphic ANF with decision trees: one baseline version per function, witnesses explicit, every allocation and generated member visible |
| Demand and memory inference, M10, on Core | Demands, ownership, escape sets, lifetime classes, signature facts (§20), for each baseline version; `E071` (`fip` parameter, `spec/quantities.md` Rule Q7.1), `E110` (Rule MM18.4), `E085` (confinement, `spec/effects.md` Rule E5.5.b) |
| Cost checker (`spec/cost.md`), on Core | Statuses of cost props |
| Representation selection, M11 (`spec/type_system.md` §6.5), for choice points whose values do not leave the package | Representations at origination sites and their witnesses, `Inline` demotions, N, copy-out sites, fingerprints. Decided on the facts of baseline versions; never repeated for a clone |
| Code generation | Baseline code, interface tier, body tier, summaries (`spec/compilation_model.md` §6, §8) |

**Application link** (`spec/compilation_model.md` §8): resolution of deferred satisfaction sites (Rule CM6.6), selection of the remaining choice points (Rule CM8.4), binding (Rule CM8.2), accessors (Rule CM3.7), the type table and link-unique tags (Rule CM8.8, Rule PR3.1), and in a release build the clones (Rule CM8.7), on each of which M10 runs again.

**Test build:** property-based testing and measurement (`spec/properties.md` Rule P7.9). Its results never enter the facts lane of the package compile that preceded it. A `proved` status obtained there by enumeration (`spec/properties.md` Rule P8.5) is a proof: it is recorded in the interface when `fixed verify` has run, and it reaches the facts lane of importers only (Rule P7.8.c).

**What may justify reuse.** A reuse, a bounds-check elision or any other decision that is wrong if a claim is false is in the **facts lane** (`spec/properties.md` Rule P7.8): it takes only `proved` and `assumed` props, never a `tested` one. Choices among alternatives that are all correct (clone weights, the budget, selection) are in the measurement lane and may use anything measured.

| Runs before | Consumes |
|---|---|
| Code generation (LLVM IR emission) | IR with `dup`, `drop`, `consume`, reuse tokens, destinations, region marks and resets, witnesses and evacuations |

M10 is a pass over Core. It does not change types or values.

## 21. Hypotheses to measure

Every performance statement in this document is a hypothesis until measured; the first measurements are in `docs/baselines/runtime-2026-11-20-phase-4.1.md`. In particular:

- Overall speed and memory relative to pure Perceus and to Goose-style placement. Goose's own claim of about 1.13–1.19× over safe Rust is unverified and says nothing about Fixed.
- Witness call overhead at polymorphic boundaries, before and after PGO specialization (§7.4).
- How often evacuation fires on real programs (a performance cliff risk), and how often W110 fires.
- Whether Rule MM5.2(b) sends too many carried values to the heap, and whether the garbage-free check stays simple. MLKit's equivalent analysis was described by its authors as "unpleasantly complex".
- Fiber-stack cost per capturing handler install (§14.3).
- Relocation-on-growth waste versus a larger N (§6.3).
- Retention by counted regions and blobs, and the copy-out volume (§6.4, §16.2).
- Deferred dups in lanes (§17.3).
- Baseline overhead: run time and reference-count traffic with `--no-specialise` against the default build, per benchmark (`spec/compilation_model.md` Rule CM5.7). First measurement, `docs/baselines/runtime-2026-11-20-phase-4.1.md`: up to 23% on the five runtime benchmarks, all of it witness calls; allocations, reuses and counted `dup` / `drop` are identical in both modes.
- Whether unconditional reuse beats the runtime `rc == 1` test. First measurement: no measurable difference on the five benchmarks. Rule PR6.3.a rests on it. **Open:** the project owner suspects that the measurement hides a compiler fault (for example, exclusive versions that were not reached, or a test that the C compiler removed); it must be ruled out, with the emitted IR and instruction counts, before Rule PR6.3.a is relied on.
- How much of that overhead the score recovers under the reference budget `B = 512` and weight base `W = 8`, how many clones a function gets, how often a function exceeds `B`, and the code-size cost (OQ-CM3).
- The convention of calls through capability tables (Rule CM4.6): before it is frozen, a benchmark with capability-bounded parameters measures it against passing every argument `own`.
- The header (Rule PR3.1): an 8-byte header with a 32-bit count, as in Koka and Lean, against the present count plus descriptor. A hypothesis, not a decision.
- The descriptor on `Frame` and `Region` cells (OQ-MM7): memory and time against headerless placement.
- About 4% on `list_pipeline` and `records` against the monomorphising backend remains unexplained after the runtime fix of Rule PR7.3.
- How often the dynamic `rc == 1` path (§11.1) reuses a cell that static exclusivity missed. If it is rare, the shared version could drop the test.

## 22. Worked examples

### 22.1 Simple let-binding (quantity ω, `Frame`)

```
fn count_pairs(x: i64, y: i64) -> i64 =
    let pair = make_pair(x, y)        // built in this function's Frame region (Dest at make_pair)
    pair.first + pair.second           // two borrowed reads
```

`pair` holds two 64-bit fields, so it is too wide to be an immediate (§3.3). It is quantity ω (read twice) and `Frame`: a descriptor but no count, no `dup`, no `drop`. Both reads are `read` demands, so `pair` stays exclusively owned throughout. The region frees it at return. Under v0.1, this would have been a heap allocation with a `dup` / `drop` pair that borrow analysis then removed.

### 22.2 Match with reuse (FBIP)

```
fn map(list: List of A, f: A -> B) -> List of B =
    match list:
        List.Cons(h, t) => List.Cons(f(h), map(t, f))
        List.Nil => List.Nil
```

Fixed functions cannot call themselves. The code above is what the auto-derived `map` of `List` does (`spec/type_system.md` §7.4), written out by hand to show the cells; a user writes `list.map(f)`.

`map` demands `exclusive` on the list cells and `own` on the elements. For a `Heap` list the call goes to the baseline version whether or not the caller owns the list exclusively: each `Cons` cell is reused after the run-time `rc == 1` test, which succeeds for an exclusively owned list (Rule PR6.3.a). For an exclusively owned `Frame` or `Region` list, which has no count to test, the caller requests the exclusive version; where the clone budget built it (`spec/compilation_model.md` Rule CM5.3), each cell is reused unconditionally, provided the new fields satisfy Rule MM11.4. Otherwise, and for a shared `Frame` list, the result is built in the destination and the old cells die with their region.

### 22.3 Fold with per-step temporaries

```
fn total_len(words: List of String) -> u64 =
    words.fold(0, (acc, w) -> acc + w.trim().length())
```

`w.trim()` is a per-step temporary: `Frame(step)`, reset after every step. `acc` is a `u64`, an immediate (§3.3). The loop allocates nothing that survives a step.

### 22.4 Churn sent to the heap

```
fn index(lines: List of String) -> Map of (String, u64) =
    lines.fold(Map.empty, (m, line) -> m.insert(key_of(line), line.length()))
```

If `m` is exclusively owned and `insert` reuses the path it rebuilds, the step is garbage-free (Rule MM5.2(b)). `m` then lives in the caller's destination and is updated in place. If the old map version is shared elsewhere, the replaced path nodes become garbage each step, so `m` is long-lived and goes to `Heap`. The report states which.

### 22.5 Linear effect handler

```
linear effect Lock:
    fn release -> ()

fn critical_section(work: () -> () with Lock) -> () =
    work()
    Lock.release
```

The `Lock` handler is installed once and consumed by `Lock.release`. Its continuation is exclusively owned, with no `dup`, consumed by `resume`. The frame is freed right after `Lock.release` returns. No RC traffic.

## 23. Open questions

- **OQ-PR1 — Borrow surface syntax.** Borrowing is inferred (§13.4). Defer a `borrow` keyword until inference proves insufficient.
- **OQ-PR2 — Atomic vs non-atomic counts.** Resolved for structured parallelism by Rule MM17.2. Reopens only if unstructured concurrency is added (§17.4).
- **OQ-PR3 — Cyclic data.** Fixed data cannot form cycles. Deferred until mutability is in scope (it is not planned).
- **OQ-PR4 — Borrow leakage through closures.** A borrowed parameter captured by a closure must not outlive the borrow. Rule MM5.2(a) and Rule PR5.6 cover escaping closures; the details belong to the borrow analyzer.
- **OQ-PR5 — Tail-call guarantees.** Rule PR7.3 requires TCO for drop functions, and `fip(strict)` needs loops for list-shaped folds. The contract for general code is still open.
- **OQ-PR6 — Reuse fingerprint algorithm.** Implementation-defined until a second compiler exists.
- ~~**OQ-MM1 — Grammar for `fip`.**~~ Resolved in v0.4. `fip`, `fip(n)` and `fip(strict)` are in `spec/syntax_grammar.ebnf` v0.4.10 (`FipModifier`), on function declarations, capability method requirements and `satisfies` methods (Rule MM18.5).
- **OQ-MM2 — Chunk sizes and pools.** First-chunk size, the growth factor and fiber/lane pool policy are implementation-defined until measured.
- **OQ-MM3 — N versus relocation.** Whether the compiler should prefer more growable stacks or relocation with holes (§6.3).
- **OQ-MM4 — Cohesive-group detection.** Rule MM5.4 states the conditions; how far M10 searches for groups (one function, one call tree, across modules) is open.
- **OQ-MM5 — Region and witness facts across packages.** What the interface carries is settled (`spec/compilation_model.md` Rule CM7.1). Its on-disk encoding is open there (OQ-CM2).
- **OQ-MM6 — Descriptors of flat containers.** A flat container (Rule MM16.3) must stay self-describing for `drop`, `copy` and `inspect`, but its element count is not bounded by the 32 slots of a reference map. A length field and a flag "every element is a reference" in the descriptor would do; it is not decided.
- **OQ-MM7 — Descriptors outside the heap.** Rule PR3.1 puts a descriptor on every cell, including `Frame` and `Region` cells, because a type-independent `ref` witness must read it. That is a word per cell that the Goose-style regions of v0.2 did not pay. A clone cannot omit it, because cloning never changes a layout (`spec/compilation_model.md` Rule CM3.4). Whether the cost matters is to be measured (§21).

## 24. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | `FipModifier` on functions, method requirements and `satisfies` methods; otherwise this pass runs on lowered IR |
| `spec/compilation_model.md` | The baseline version and its witnesses (§7.3), clones (§7.4), `fip` entries (§18), the interface tier built on §20.2 |
| `spec/cost.md` | `fip` is the zero case of the allocation contract; §19 carries the cost report |
| `spec/type_system.md` | §6.5 consumes quantity and escape facts and decides `Inline`; §6.5.4 scoring uses class eligibility and copy-out volume; §7.5 SCCs (§15) |
| `spec/quantities.md` | Demands and exclusive ownership (§4.1) drive §9–§11; shared and exclusive versions (Rule Q9.3); evaluation order (Rule Q9.2); M10 also computes the facts in §20; the parallel composition rule underlies §17 |
| `spec/effects.md` | §6 and §7 drive §14; Rule E3.5 drives §14.5; §7.5 drives §14.6 |
| `spec/pattern_matching.md` | §8 decision trees are the input to §10 |
| `spec/properties.md` | The facts lane (Rule P7.8): `proved` and `assumed` props inform reuse and bounds-check elision; test-build measurement (Rule P7.9) feeds only the measurement lane |
| `spec/diagnostics.md` | `inspect` and `trace` read the cell tag and the type table (Rule PR3.1) |
| `spec/stdlib.md` | `.clone()` is the explicit copy; borrow inference (§13) is the implicit mechanism |
| `docs/plans/implementation-plan.md` | Phase 4 deliverables (`perceus/PerceusInserter.scala`, region and witness emission); Phase 6 PGO witness specialization |
| `docs/plans/phase-3-typer.md` | M10 (§5.7) computes §20 |
