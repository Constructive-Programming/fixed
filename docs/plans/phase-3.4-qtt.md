# Phase 3.4 — Quantities, demands and lifetimes (M10)

**Status:** done (3.4.M1–M6). New baseline: `docs/baselines/runtime-2026-11-20.md`. Design settled (decisions §8).
**Parent:** `docs/plans/phase-3-typer.md` §1.3 (Part B, M10) and §5.7
**Runs:** after M7 and the Phase 4.0 slice, **before M9**. The quantity walk runs after the typer;
everything else runs on Core after lowering.
**Spec:** `spec/quantities.md` v0.4 (§3–§5 quantities, §4.1 demands, §7 exclusivity, §9 arguments,
evaluation order, shared versions, aliasing), `spec/memory_management.md` v0.3 (§3, §5, §10, §11,
§13, §14.1, §17, §18, §19, §20)
**Measured against:** `docs/baselines/runtime-2026-11-19.md`

**Superseded in part (2026-11-20).** This plan is a record of what 3.4 built, and some of its statements no longer describe the target. Demands inferred on monomorphic Core with "one instance per type" are replaced by a polymorphic baseline version per function (`spec/compilation_model.md` §3). The cap of 4 versions, with the caller copying beyond it, is replaced by the clone budget of Rule CM5.3; copying remains only in the shared entry of a `fip fn` (Rule CM5.5), and "no shared version for a `fip fn`" is replaced by that shared entry (Rule CM5.5). "Signature facts live in the unit" is replaced by the interface tier (Rule CM7.1). The plan that changes the code is `docs/plans/phase-4.1-baseline-specialiser.md`.

## 1. Goal

After M10, every binding and every function of a checked unit has the record of
`spec/memory_management.md` §20, and the backend uses it, so the baseline numbers move.

1. **Occurrence quantities** (0, 1, ω) on the typed tree: `E072` (two `resume`s on one path),
   `T021` (unused binding). Done in 3.4.M1.
2. **Demands** per parameter and per part of a value (`0`, `read`, `own`, `exclusive`;
   `spec/quantities.md` Rule Q4.1), inferred on Core, with exclusive ownership per binding.
3. **Versions.** A call that passes a shared value to an `exclusive` parameter goes to the callee's
   shared version (Rule Q9.3). Versions are built only for the combinations called, capped per
   function; beyond the cap, and into `fip` functions, the caller copies the demanded part (the
   Neut strategy, applied only here). A closure with an `exclusive` parameter carries two code
   pointers (Rule PR5.6).
4. **Escape sets, lifetime classes, cohesive groups, garbage-free carry, handler kinds, fork-site
   facts** (Rules MM5.1–MM5.4, MM14.1, Q5.13), with reason chains.
5. **Signature facts** (Rule MM20.2) and the **`fip` check** (`E110`, `E071`).
6. **`fixed compile --explain-memory [--format json]`** prints the record, including every copy
   site (§19).
7. **The backend consumes it** and a new baseline is committed.

## 2. Non-goals

- **Region, frame and destination lowering in the backend.** M10 computes `Frame`, `Dest` and
  `Region` classes and cohesive groups and reports them; the data stacks, counted regions and
  destinations that would place values there are Phase 4 region lowering. The backend still
  allocates every boxed value on the heap.
- **Forking.** Fork sites are recorded (Rule Q5.13.d); no lane runs in parallel before Phase 4.
- **Linear effects** (Rule Q7.4). Contingent on OQ-Q5.
- **Separate compilation.** Signature facts live in the unit, not in an interface file.

## 3. Design

### 3.1 Two analyses, two IRs

- **Quantities on the typed tree** (3.4.M1, `fixed.qtt.QuantityPhase`). Occurrence counting needs
  source positions and nothing else. Its table is not consumed by the backend.
- **Demands and memory facts on Core.** Demands are data flow over values. They need every
  allocation, the generated members (derived `fold`, `unfold`, `map`, list members), and one
  instance per type, where versions are chosen. Core has all three; the typed tree has none. Lean
  and Koka run borrow and reuse inference on their core IR for the same reasons.

Core today carries no source positions. **Lowering records a span on every `Let`, every function
and every parameter** (3.4.M2), so `E110`, `E071` and the memory report point at source.

Consequence for M11: representation selection reads these facts, so it also runs on Core after
lowering (`spec/memory_management.md` §20.3), not as a typed-tree recheck.

### 3.2 Demand inference (Rule Q4.1)

Per function instance, a backward walk over Core computes, for each boxed variable, the demand of
its uses:

- `Field` reads, `Prim` operands, `If` and `Switch` scrutinees: `read`.
- `Con` fields, `Ret`, `Closure` captures: `own`.
- `Call` and `CallClosure` arguments: the callee parameter's demand (Rule Q9.1.1); a lambda
  argument's captures scaled by the call count (Rule Q9.1.4).
- A `Con` whose layout matches a dead variable's cell, on the same path, after that variable's last
  use: the variable is `exclusive` there (the reuse site, drop-guided per Rule MM11.5).

Per part (Rule Q4.1.a): one demand per field slot of the parameter's data type; a recursive field
shares the slot it recurses to. A field read `x.f` followed by a use of the result propagates the
result's demand to `x`'s slot `f`.

A parameter's demand is the greatest of its uses, with `exclusive` downgraded to `own` where an
earlier use kept the value (Rule Q4.1.c). Recursion and mutual recursion: start every parameter at
`0`, iterate the call graph to the fixed point. The lattice has height 4 per slot.

**Ownership per binding** (Rule Q4.1.b): fresh `Con` and call results are exclusive; parameters
are exclusive on the parts their demand covers (in the exclusive version) or shared (in the shared
version); a `Field` of an exclusive, consumed value is exclusive on covered parts; a use that keeps
a still-live value makes it shared. **Aliasing** (Rule Q9.4): an argument is exclusive at a call
only if no other argument or capture of that call reaches it.

**Evaluation order** (Rule Q9.2): lowering already evaluates arguments left to right and calls
after the last argument. 3.4.M2 adds a test that pins this.

### 3.3 Versions (Rule Q9.3)

The lowering's instance names gain an ownership suffix: `bump[u64]` and `bump[u64]#s` (shared on
the first exclusive parameter). Instances are requested on demand by call sites, as type instances
are today, so only called combinations exist. Cap: 4 versions per function; a call beyond it
copies the demanded part with a generated `copy_T` (one per data type, copying the slots the
demand covers and `dup`-ing the rest) and calls the exclusive version. Calls into `fip` functions
always copy.

Closures: a lambda whose parameter is `exclusive` is compiled twice; the closure object holds
both code pointers (slot 0 and slot 1). `CallClosure` picks the slot from the argument's ownership.

### 3.4 Escape, classes and the rest (Rules MM5.1–MM5.4, MM14.1, Q5.13)

The same walk records escape sets (`result`, `field(b)`, `closure(c, escapes?)`,
`continuation(h, escapes?)`, `carried(loop)`, `lane-shared(fork)`, `unbounded`) and assigns classes
in the order of Rule MM5.3: `Erased`, `Static`, `Frame(s)` / `Dest(result)`, `Region(r)` for
cohesive groups (Rule MM5.4; search limited to one call tree per OQ-MM4), `Heap`. Garbage-free carry
is checked at constructor level per `fold` / `unfold`. Handler kinds come from clause shapes. Fork
sites: tree folds and unfolds with empty rows are admissible; sequence folds wait for M9's proved
associativity (recorded as conditional). Every non-`Frame` class gets its reason chain.

### 3.5 `fip` (Rule MM18.1)

Checked item by item on the facts above; reused parameters must be `exclusive` (`E071` otherwise);
violations are `E110` with the reason chain.

### 3.6 Backend consumption

1. **Erasure.** Demand-`0` parameters are not passed; an effectful argument is still evaluated.
2. **Borrowing.** `read` parameters: no `dup` at the call, no `drop` in the callee.
3. **Exclusive versions.** Exclusively owned values: field reads move instead of `dup`, the cell
   is reused unconditionally by a matching `Con` on the same path, or freed without dropping moved
   fields.
4. **Shared versions.** Shared `Heap` values: reuse after `rc == 1` (`fx_reuse` in the runtime;
   Koka's path, Rule PR6.3), else allocate.
5. **Copies** at the sites §3.3 names.

## 4. Milestones

| # | Content | Done when |
|---|---|---|
| 3.4.M1 ✅ | Occurrence quantities on the typed tree; `E072`, `T021` | `QuantityPhaseSuite` (§10.1's `go` is `(ω, 1, ω)`); examples `01`–`06` raise no new diagnostics; walk costs 25.4% of typer time |
| 3.4.M2 ✅ | Spans and types per Core variable; demand inference per part, with exclusivity lost to later uses, keeps and aliasing; reuse pairs; evaluation-order test | `DemandSuite` (6 tests) and `EmitSuite`'s order test; every Core function of examples `01`–`05` and the benchmarks has demands |
| 3.4.M3 ✅ | Versions and copies; two-pointer closures | `VersionSuite` (4 tests); `EmitSuite` runs a shared list through a reusing function and a program past the cap, both leak-free; goldens unchanged |
| 3.4.M4 ✅ | Escape sets, classes, cohesive groups, garbage-free carry, handler kinds, fork facts, reason chains | `MemorySuite` (7 tests); every allocation of the run examples and benchmarks has a class |
| 3.4.M5 ✅ | `--explain-memory`, `--format json`; `fip` check | `FipSuite` (6 tests) with a golden JSON report for `01` (`examples/golden/01_basics.memory.json`, regenerate with `FIXED_REGEN=1`); `EmitSuite` runs a shared value copied into a `fip fn` |
| 3.4.M6 ✅ | Backend consumption (§3.6); new baseline | Goldens and leak checks pass with every unconditional reuse verified at runtime in test builds (`FX_CHECK_EXCL`); aliasing, closure-called-twice, shared-list and copy programs stay correct; `docs/baselines/runtime-2026-11-20.md`: `list_pipeline` −31%, `records` −18%, allocations −40–50% there |

Each milestone ends with the full gate. Core analyses are timed against lowering plus emission on
the benchmarks and reported; there is no fixed budget until a first measurement exists.

### 4.1 Notes from 3.4.M2

- **Spans and types live in side tables of `Lower`** (`types`, `spans`, by variable and function
  name), not in `Let` nodes. Variable names are unique, so this needs no change to Core's shape.
  Generated members type their main parameter; other untyped variables get their type from
  `slotType` through field reads, or fall into the part key `?`, which only merges demands
  (conservative).
- **Variant known for reuse:** inside a `Switch` case, or always for a single-variant type, so
  `discount` in `records` reuses its `Order` without a `match`.
- **Results on the benchmarks:** derived `fold` and the left folds only `read` their input (the
  whole `bintrees` traversal borrows); each `map` is `exclusive` on its cells and recursive slot and
  reuses the cell; `discount` and the `records` map lambda are `exclusive` on their `Order`.
- **Open for 3.4.M3:** a function passing values to a closure it cannot see demands only `own` of
  them (`map`'s elements), so the closure's exclusive entry point (A2) would never be chosen there.
  Choosing versions by element ownership as well, under the cap, is the fix to evaluate on
  `records`.
- **Ownership per binding** for version selection is computed in 3.4.M3 with the same predicate
  that demand inference uses (`Demands.exclusive`), so the two cannot disagree.

### 4.2 Notes from 3.4.M3

- **`Versions`** (`backend/Versions.scala`) runs after demand inference, from `main`. Ownership per
  binding and per part (`Own`) is tracked forward; the exclusivity test at calls is
  `Demands.exclusive`, so versions and demands agree. Result ownership per version is a summary
  computed to a fixed point, starting optimistic. Shared versions are named `f#n`.
- **Copies** are generated per type and part set (`T.copy[...]`, `Generated.copyOf`): the cell and
  the fields at demanded parts are copied, the rest shared. They are used only past the cap of 4.
- **Two code pointers on every closure**, not only on closures with an exclusive parameter: a
  caller cannot tell from a closure it did not create whether a second entry exists. Spec updated
  (Rule PR5.6, Rule Q5.7). Closures grow by one word; closures without captures are static.
- **Element ownership through closures stays open.** Unfold builds list elements from the step
  closure's result, which is treated as shared, so `records` cannot reuse its `Order` elements
  statically until fold specialisation (Rule 7.4.b.2) makes the step known. The dynamic
  `rc == 1` path of 3.4.M6 covers that case meanwhile. `map`'s cells and recursive slot are
  exclusive in every benchmark pipeline.
- Versions change no behaviour yet: every version has the same body until 3.4.M6 consumes
  ownership.

### 4.3 Notes from 3.4.M4

- **`Memory`** (`backend/Memory.scala`) runs on versioned Core. Escapes per variable come from one
  backward walk per function, iterated over the call graph for parameter escapes (`None`,
  `IntoResult`, `Unbounded`). A stored value inherits its container's escapes. Classes follow
  Rule MM5.3; a lambda's body is its own scope, so fold-step temporaries are `Frame` of the step.
- **Carried values** escape as the step's parameter does when the step is a closure built at the
  call; garbage-free means the step reuses the carried value (`exclusive`) or keeps it in its
  result. An unknown step makes the value long-lived.
- **Cohesive groups** are searched within one function (OQ-MM4): a root stored in nothing and the
  allocations stored only into the group, at least two. A list literal handed to an unknown
  closure is one `Region`.
- **Handler kinds** are classified on the typed tree (`HandlerKind`, in `QuantityPhase`), where
  clause shapes are visible; Core has only abortive handlers until capturing ones lower.
- **Fork facts** per user call of a derived `fold` / `unfold` or left fold: tree-shaped (some branch
  recurses twice) with steps that are closures built at the call and transitively effect-free.
  Left folds wait for M9's proved associativity. Effects in Core are the runtime's I/O and abort
  calls; any closure call inside a step counts as impure.
- **On the benchmarks:** every allocation is `Dest(result)` or `Frame`; nothing is `Heap`.
  `bintrees`' `fold` and `unfold` are fork sites.

### 4.4 Notes from 3.4.M5

- **Front end:** `fip` is a keyword; `fip fn` and `fip(strict) fn` parse at top level and in
  blocks (grammar v0.4.9) into `FnDecl.fip`. Lowering records `fip` instances.
- **`FipCheck`** reports `E110` per violated item, at the offending site, naming the `fip fn`:
  heap or region allocation, reference-count operation (a call of a shared version, or of a
  closure the compiler cannot see), evacuation (a copy), and scratch allocated by a recursive
  function that is not a derived loop. `fip(strict)` adds allocations that reuse no cell, and tree
  folds. Item 4 (drop lists) has nothing to check before Phase 4 region lowering.
- **No shared version for a `fip fn`:** `Versions` makes its callers copy (Rule MM18.3), so the copy
  is the caller's evacuation and breaks the caller's contract only if the caller is `fip` itself.
- **`E071` cannot arise from inference:** a parameter is reused only where demand inference made it
  `exclusive`. The code stays defined in the spec for a future declared-demand form. **`W110`**
  (evacuation in hot code) is not emitted yet: it needs the hot-code notion of Phase 6 or a fold-step
  marker on Core functions.
- **Report:** `fixed compile <file> --explain-memory [--format json]` prints, per function, the
  `fip` contract, parameter demands per part and escapes, allocations with class, escapes and
  reasons, fork facts and copy sites, then handler kinds, with `line:column` positions. Closures
  built by lowering carry no span yet (`-`).

### 4.5 Notes from 3.4.M6

- **`Reuse`** is a Core form produced by `Versions` from demand inference's reuse pairs:
  unconditional where the version owns the cell exclusively, dynamic otherwise. `Rc` takes the
  token where the cell would be dropped (drop guided, Rule MM11.5): `fx_reuse_excl` or Koka's
  `fx_reuse`, which release the cell's children and leave an empty cell; the constructor fills it
  (`fx_alloc_at`), or it is dropped like any value. Taking the token before the recursive call is
  what makes the moved tail exclusive in the callee.
- **Two soundness bugs found by the runtime check** (`FX_CHECK_EXCL`, on in every `EmitSuite` run):
  a value moved into another value was taken as a dead cell (fixed: moved values are not
  candidates), and a live sibling field made its parent look shared (fixed: ownership counts only
  live ancestors; descendant conflicts stay with the aliasing test).
- **`Consume`** erases demand-`0` parameters and borrows `read` parameters, for functions called
  directly. Borrowing everything slowed `bintrees` by 18% (freeing moved to a second pass), so a
  parameter is borrowed only where some caller keeps the argument live after the call.
- **Not done, for Phase 4 Perceus:** drop specialisation (moving fields out of an exclusive cell
  rather than dup + release), a borrowing closure-call convention, typing the derived `unfold`'s
  layer so its cell can be reused. These are where the remaining `dup`s are.

## 5. Tests

- `QuantityPhaseSuite` (M1), `DemandSuite` (M2–M3), `MemorySuite` (M4–M5): short Fixed snippets
  lowered to Core, asserting facts by function and parameter name.
- `EmitSuite`: goldens and leak checks catch unsound reuse. Add programs that would corrupt memory
  if exclusivity were wrong: a shared list passed to a reusing function and printed afterwards;
  `f(xs, xs.tail)` with an exclusive first parameter; an exclusive capture in a closure called
  twice.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Unsound exclusivity frees or overwrites a shared cell | Conservative rules (§3.2); the corruption programs of §5; debug runtime poisons freed cells |
| Versions multiply code size | Cap of 4; report version counts per function; `spec/memory_management.md` §21 hypothesis |
| Per-part demands on mutually recursive data blow up | One demand per field slot of each type in the SCC; shared slots for recursive fields |
| Spans make Core larger and slower | One `Span` per `Let`; measured in M2 |

## 7. Spec amendments made

`spec/quantities.md` v0.4 and `spec/memory_management.md` v0.3 (2026-11-20), plus
`spec/type_system.md` (Rules 6.1.1, 7.4.b.1), `spec/pattern_matching.md` §12, `spec/stdlib.md`
§4.11 and `spec/effects.md` (§3.5, cross-references).

## 8. Decisions

| # | Decision |
|---|---|
| D1 | The backend consumes M10's facts inside M10 (§3.6) |
| D2 | A quantity-1 parameter is a contract; a caller with a shared value calls a shared version (b), and copies only beyond the cap and into `fip` functions |
| D3 | `Region`, cohesive groups and fork facts are analysed now (M4); runtime lowering stays in Phase 4 |
| D4 | Hybrid: occurrence quantities on the typed tree, demands and memory facts on Core with spans |
| D5 | Demands per part (`0`, `read`, `own`, `exclusive`); arguments count by where their value goes (Rule Q9.1) |
| A1 | `own` and `exclusive` are separate demands |
| A2 | Closures with an `exclusive` parameter carry two code pointers |
| A3 | Cap versions per function; beyond it, copy the demanded part (Neut's strategy) and report it |
| A4 | M11 runs on Core after lowering |
| A5 | Reuse is hybrid: static for exclusive values, `rc == 1` for shared `Heap` values (Rule PR6.3) |
