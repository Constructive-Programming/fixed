# Phase 4.1 — Polymorphic Baseline and Specialiser

**Status:** Plan. Written 2026-11-20 from an inventory of the backend at `5a0eedd`.
**Goal:** the compiler complies with `spec/compilation_model.md`: one polymorphic, witness-passing **baseline** version per function, **clones** built on top of it under one size budget in score order, the shared version as the primary one, and a `--no-specialise` build mode.
**Decisions:** `docs/decisions/P1`–`P5`. Review findings: `docs/decisions/P0-spec-review-2026-11-20.md`.

## 1. Scope

In scope, in the order of §C below:

- the shared version keeps the function's name; exclusive versions are clones; one size budget replaces the cap of four; no caller copies; a `fip fn` gets a copying shared entry (Rules CM5.3, CM5.5, CM5.6);
- a reference map in the cell header and fields in declaration order (Rule CM3.4, `spec/memory_management.md` Rule PR3.1);
- 64-bit slots for closure arguments and results (Rule CM3.3);
- build modes and `--no-specialise` (Rule CM5.7);
- witnesses, capability tables and adapters (Rules CM4.1, CM4.2, CM4.6);
- baseline lowering (Rule CM3.1) and the specialiser on the type axis (Rules CM5.1, CM5.4);
- the clone report in `--explain-memory` (Rule CM5.8).

Not in scope:

- **Packages.** There is one package, a program with `main` as its only root (Rule CM6.2). Floor entries, exported versions, the interface and body tiers and link-unit coherence need a package system first.
- **Fusion and `fold`-step inlining** (Rule CM5.5(1)). The backend has neither yet; they stay Phase 4 work. Until they exist the two build modes differ only by clones.
- **Lifetime classes other than `Heap`.** The backend places every reference on the heap, so two value witnesses (immediate, reference) are enough. A witness per (type, class) arrives with region lowering.
- **Cost contracts** (`spec/cost.md`), coverage and cardinality (`spec/reports.md`), `fip` on members and `fip(n)`. Specified, not built here.
- **Representation selection** (M11). `Representation.scala` stays as its stand-in.

## 2. Deliberate shortcuts against the spec

Each is a known gap, to be closed by the phase named.

| Shortcut | Why | Closed by |
|---|---|---|
| Two value witnesses only (`fx_w_imm`, `fx_w_ref`) | every reference is `Heap` today | region lowering (Phase 4) |
| Ordering comparisons on a value of parameter type use compiler-provided `lt` / `le` entries in the built-in `Ord` tables | no data type satisfies `Ordering`, so `compare` has no result representation | the standard library (Phase 5) |
| The prelude's `Numeric` gains `add`, `sub`, `mul`, `div`, `rem`, `negate` | `spec/stdlib.md` §4.9 has them, the shipped prelude does not | this phase (4.1.M6) |
| A clone is produced by lowering the typed tree again with the types substituted | Rule CM5.1 allows it; substitution on Core is checked against it in tests | when the body tier ships Core |
| Witness records with a type-parameter argument are built only where an example needs one; otherwise `B005` | no example needs one yet | on demand |
| A float literal in a `Numeric` position has no baseline compilation | `from_f64` does not exist | the standard library |

## 3. What success means, and what failure looks like

- **Correctness gate.** After every milestone `sbt check test` passes, the four golden examples produce their committed output, and the five runtime benchmarks produce their committed result lines, in **both** build modes once the second mode exists. Allocations equal frees in every run.
- **Performance expectation.** In the default mode the benchmarks should stay within noise of the 2026-11-20 baseline: every function in them is small, so the budget covers the clones the monomorphic backend used to build. With `--no-specialise` they are expected to be slower, by the cost of witness calls, table calls that cannot borrow (Rule CM4.6) and runtime `rc == 1` tests. That difference is the measurement this phase exists to produce.
- **A negative result is a result.** If a milestone shows that a rule of `spec/compilation_model.md` cannot be implemented as written, the outcome is a note in this plan and a finding for the spec, not a workaround that hides it.
- **The gate that cannot fail.** "Both modes produce the same output" proves nothing while both modes run the same code. Before 4.1.M7 the two modes differ only on the ownership axis; each milestone states which code path the second mode exercises, and a test asserts that the baseline version really has no concrete type where the source has a parameter.

## 4. Milestone status

Updated as milestones land. Each entry records what was built, what was measured and what was left out.

| Milestone | Status |
|---|---|
| 4.1.M0 | Done. The golden outputs were committed in `e51ffed`. Each runtime benchmark's result line is in `bench/runtime/<program>.expected`, taken from a run at `d4ef8e7`. `EmitSuite` ships the five benchmarks with `FX_CHECK_EXCL` and compares their output with those files and allocations with frees; there is no separate `BenchRunSuite`, because `EmitSuite` already has the ship, run and leak-check harness, and the five runs add about one second, so they stay in the normal test run. Counts at `d4ef8e7` (allocations, reuses, frees, dups, drops): `bintrees` 2,097,151 / 0 / 2,097,151 / 8,388,601 / 12,582,907; `nat_loop` 4,000,402 / 0 / 4,000,402 / 6,000,801 / 10,002,206; `list_pipeline` 4,000,602 / 4,000,000 / 4,000,602 / 14,000,801 / 18,002,806; `records` 3,000,302 / 2,000,000 / 3,000,302 / 9,000,401 / 13,001,206; `fail_chain` 4,000,602 / 0 / 4,000,602 / 5,997,601 / 9,999,006. |
| 4.1.M1 | Done. **Built.** `Versions` builds the shared version of every function reached from `main` under the function's name, and exclusive clones named `f#x[<key>]`, where the key is the canonical text of the ownership mask (for example `bump#x[0:cell]`). The cap of four is replaced by the clone budget of Rule CM5.3 (`Versions.budget = 512`, a parameter of the pass and of `Backend.analyse`). Clones are chosen in the rounds of Rule CM5.4: candidates are ranked per function, in name order, by `removed × weight` with ties broken by key text, a candidate with `removed = 0` is never built, and each clone is charged the function's size in Core `let`s. `removed` is the number of sites at which the clone's walk decides differently from the shared version's walk: a reuse made unconditional, a call that requests a clone of its callee, and a closure call that takes the exclusive pointer. `weight` sums `8^d` over the requesting sites. A call whose clone was not built calls the shared version, and no caller copies any more. A `fip fn` keeps its one body under its own name and gets a shared entry `f#shared` that copies the demanded part of each argument and calls the body; neither is charged. The clones a `fip` body requests (and those they request) are built whatever the budget and are not charged either, because without them the body would call a version that tests counts and fail its contract; Rule CM5.5 does not list them, so this is an interpretation of its intent (see Found). A closure's primary entry (`Rhs.Closure.code`) is the shared version of its code, and `exclusive` names the clone or is empty; the cell layout is unchanged (exclusive pointer at offset 16, shared at 24). `Fn` gained `version: Option[Version]` (the instance it is a version of, and whether it reuses only after a count test), and `Memory.base` with its `#\d+` regex is gone. `--explain-memory` gives, on each function's own entry, `baseline_size`, `clones` (key, removed, weight, score, charge) and `candidates` (key, removed, weight, score, reason). The golden `01_basics.memory.json` changed in three ways only, all intended: the new fields; `lambda.77` is now the shared version and the exclusive one is `lambda.77#x[1:cell]` (their contents are exactly those of the former `lambda.77#1` and `lambda.77`); and that clone is listed last, because clones are built after the shared versions. **Measured.** The default-mode counts of all five benchmarks are identical to those at `d4ef8e7` (see 4.1.M0). They are also identical with a budget of 0, because the runtime counts a reuse after a successful `rc == 1` test the same as an unconditional one: the counters cannot see whether a clone was used. The difference is visible in the IR instead: `list_pipeline` has one `fx_reuse_excl` site with the default budget and none with budget 0, and `records` has two and none. `list_pipeline` now also contains the shared version of `List of u64.map[u64]`, which the old code did not build because no call reached it; the spec requires it (Rule CM3.1). **Deviations from the plan.** `d` is computed in `Versions` from Core, not by a `Lower` side table: a closure passed to a derived `fold`, `unfold`, `foldLeft` or `map` is one step deeper than the function that builds it, and other closures are as deep. The request for a closure's exclusive entry is made by the closure's construction, so its weight is that of the construction site. Result ownership summaries need a fixpoint, which Rule CM5.4's single pass of rounds does not have, so `Versions` repeats the pass (shared versions, the clones chosen so far, then the rounds) until neither the clones nor any summary changes. Clones are never removed and each summary is met with its previous value, so this terminates. A clone chosen in an early pass from a summary that later lost exclusivity can end up called by nothing; it is then dropped, never chosen again, and the whole choice is repeated so that it holds no budget. It is reported with the reason `unused`, which is not one of the three reasons of Rule CM5.8. In `records` this drops the clone of `List of Order.map[Order]` for the whole mask, which only called itself; with a budget of one clone of `map`, the useful clone `0:cell` is then still built (`VersionSuite`). **Found.** A `fip fn` whose body calls a non-`fip` function with an exclusively owned argument depends on that function's exclusive clone; under a clone budget alone, a large callee or a smaller budget would make the `fip fn` fail with `E110`, so a spec amendment should say that those clones are mandatory. A caller that owns a value and its tail exclusively, calling a function that demands only the tail exclusive, requests a mask with the tail part but not the cell; inside the clone the shared cell makes the tail shared again, so that candidate has `removed = 0` and is not built. The old code built that version under the function's name and it behaved as the shared one, so nothing is lost, but the mask is imprecise (`VersionSuite`, "a candidate that removes nothing is not built"). **Left out.** Loop and copy functions are still recognised by name substrings in `Memory` and `FipCheck` (4.1.M9). |
| 4.1.M2 | Done. A cell's fields sit in declaration order, one 64-bit slot each, for data values, tuples and closure captures (after the closure's two code slots); `Layout.slots` is the identity plus that offset. The header is `{i64 rc, u32 tag, u32 refs}`: the 32-bit word that held `nptr` now holds the reference map, bit i set when payload slot i holds a counted reference. That keeps the header at 16 bytes, keeps the `u32` tag (so `Switch`, the closure tag `-1`, nullary statics and string statics are unchanged) and needs no `nslots`, because no runtime routine needs the slot count: reuse matches by field count in the compiler, and the `.copy[...]` functions are generated Core, not a runtime shallow copy. `fx_drop` and `reclaim` walk the map's set bits through one `drop_children`; the closure special case in `fx_drop` is gone. The limit is 32 reference slots (30 captures in a closure, after its code slots); a reference in slot 32 or later is `B005`, reported by `Lower.refMapLimit`, and `Layout.refs` stops with an internal error if one reaches it some other way (a `.copy[...]` function can build a variant the program never builds). Scalars narrower than 64 bits are stored in canonical form (Rule CM3.3) and truncated on load; the benchmarks hold only 64-bit scalars and references, so their IR changed only in offsets and map constants. Counts (allocations, frees, dups, drops, reuses) are identical before and after in every benchmark: bintrees 2097151 / 2097151 / 8388601 / 12582907 / 0; nat_loop 4000402 / 4000402 / 6000801 / 10002206 / 0; list_pipeline 4000602 / 4000602 / 14000801 / 18002806 / 4000000; records 3000302 / 3000302 / 9000401 / 13001206 / 2000000; fail_chain 4000602 / 4000602 / 5997601 / 9999006 / 0. The emitted `.ll` sizes are unchanged to the byte (4957, 8205, 6988, 14849, 11160), because each changed constant kept its number of digits. The golden memory report changed only in `List of u64.foldLeft`'s part keys (`#0` is now the head, `#1` the tail); two `DemandSuite` expectations swapped slots the same way. Left out: run-time maps for parameter-typed slots (4.1.M5), an overflow encoding beyond 32 slots (no program needs one), and Rule PR6.2's field-kind fingerprints; reuse still matches by field count, which the rewritten map makes safe. Plan corrections: the spec no longer fixes the tag at `i8` (Rule PR3.1 leaves the widths implementation-defined), and canonical slots apply to every narrow field, not only parameter-typed ones, as Rule CM3.3(2) requires. |
| 4.1.M3 | Done. A closure's code function takes the closure as a `ptr` and every declared argument as a 64-bit slot, and returns a slot (Rule CM3.3(3)): a reference crosses as its `ptr`, every other value as an `i64` in canonical form (signed integers sign-extended, unsigned ones, `char` and `bool` zero-extended, `f32` as its bits zero-extended, `f64` as its bits, `()` as 0). The caller of `CallClosure` widens its arguments and narrows the result; the code narrows its parameters on entry and widens its result. Direct calls keep their typed convention. The conversion lives in the emitter alone, not in new Core constructs: `EmitLLVM.function` and the `CallClosure` case reuse the `widen` and `narrow` helpers that 4.1.M2 wrote for payload slots, and `Core.closureEntries` (both entries of every `Rhs.Closure`, the set `Consume` already computed to exclude closure code from erasure and borrowing) tells the emitter which functions take slots. Core keeps its typed variables, so `Rc`, `Demands`, `Versions`, `Memory`, `Consume` and `Aborts` see no change and still know from each atom's `CType` which arguments are references. `ToSlot` / `FromSlot` would have added a `Let` and a variable of a slot type around every closure call and closure parameter, and every analysis would have had to learn that a slot variable can hold a reference before 4.1.M5 introduces `CType.Slot`; they are deferred to the milestone that needs them (the `Substitute` check of §E.2, or baseline code that converts between a slot and a known type). The emitter now refuses a direct call of closure code with an internal error, because the two conventions no longer match. One side effect: `f64` no longer counts as its own slot form, so an `f64` payload field is stored and loaded as an `i64` with a `bitcast`; the bytes in memory are the same. Counts (allocations, frees, dups, drops, reuses) are identical before and after in every benchmark: bintrees 2097151 / 2097151 / 8388601 / 12582907 / 0; nat_loop 4000402 / 4000402 / 6000801 / 10002206 / 0; list_pipeline 4000602 / 4000602 / 14000801 / 18002806 / 4000000; records 3000302 / 3000302 / 9000401 / 13001206 / 2000000; fail_chain 4000602 / 4000602 / 5997601 / 9999006 / 0. The emitted `.ll` of bintrees, nat_loop, list_pipeline and fail_chain is identical to the byte (4957, 8205, 6988, 11160), because their closures take and return only `u64` and references; the hottest closure call in list_pipeline (`List of u64.foldLeft[u64]`) gained no instruction. records grew from 14849 to 15399 bytes: its hottest closure call (`List of Order.foldLeft[f64]`) gained four `bitcast`s per element (the `f64` accumulator into a slot and the result out of it at the caller, the parameter and the result in `lambda.47`), and every `f64` field access gained one. Tests: a runtime test builds closures over `i8`, `u8`, `f32`, `bool`, `char`, `()` and `f64` in one function, stores them in a cell and calls them from another, with one closure captured by another and an `f32` closure that aborts; an IR test checks that every closure code signature and every closure call uses only `i64` and `ptr`, that an `i8` is sign-extended at the call and truncated in the callee and in the caller, and that `f32` and `f64` cross as bits. Breaking the callee side on purpose (typed parameters, slot arguments) makes the runtime test print garbage for `f32`, `bool`, `char` and `f64` and fail. Replacing `sext` by `zext` for signed integers makes only the IR tests fail: the runtime test still passes, because every consumer truncates the slot back to its type. No runtime test in a monomorphic backend can observe the extension of an integer; the IR tests are the guard until baseline code reads slots as 64-bit values. Left out: `ToSlot` / `FromSlot` (above); closure code for a value of parameter type, which needs `CType.Slot` (4.1.M5) and for which `widen` and `narrow` must then be the identity. Plan correction: the 4.1.M3 row of §C lists `Lower.scala`, `Generated.scala` and `Core.scala` (`ToSlot` / `FromSlot`); none of them needed a change beyond the one shared helper in `Core`. |
| 4.1.M4 | Done. **Built.** `Mode` (in `Backend.scala`) has two values, `Default` (clone budget 512) and `NoSpecialise` (clone budget 0, so only mandatory versions are built: shared versions, `fip` bodies with their shared entries, and the clones a `fip` body needs). `Backend.compile` takes the mode. `Main` takes `--no-specialise` out of the arguments wherever it stands and matches the rest as before, so the flag works in any position for `compile` (also with `--explain-memory`) and for `ship`; the other flags keep their fixed order. `EmitSuite.check` runs every program in both modes, with the same expected output and allocations equal to frees in each: the four golden examples, the five benchmarks and every small program; the division-by-zero program also runs in both modes. The memory report golden (`01_basics.memory.json`) is checked in the default mode only. The suite went from about 13 to about 21 seconds. `bench/runtime/run.sh` takes `MODE=default` or `MODE=no-specialise` (anything else exits 2), passes the flag, fails if a result line differs from `bench/runtime/<program>.expected`, and writes `docs/baselines/runtime-<date>-<mode>.md`. A `CompilerSuite` test covers the flag in every position. **The gate that cannot fail.** Because both modes give the same counters (see 4.1.M1), the test that the modes run different code compares their IR: for a `map` over a fresh list, the default IR has the clone `List of u64.map[u64]#x[…]` and a `fx_reuse_excl` call, and the `--no-specialise` IR has no clone and no `fx_reuse_excl`, only `fx_reuse`. Its reuse counts are equal, and the test asserts that, because the runtime counts a reuse after a passing `rc == 1` test like an unconditional one; the brief's expectation that the reuse count would differ does not hold with these counters. **Measured.** With `--no-specialise` the counts (allocations, reuses, frees, dups, drops) of all five benchmarks are identical to the default mode's, and the IR has no clone: `list_pipeline` has 11 functions and one `fx_reuse` site, `records` has 16 functions and two `fx_reuse` sites (19 functions and two `fx_reuse_excl` sites in the default mode). **Left out.** No `--budget` flag or `B` variable for `run.sh` (plan E.3); `Backend.analyse` takes a budget, which the tests use. |
| 4.1.M5 | Done. **Built.** Core can express values of parameter type and calls through capability tables; `Lower` builds none of it yet. Every example and benchmark gives the same output and counts in both modes; their IR changed only at indirect calls (see the calling convention below). `CType` gained `Slot(witness)` (a 64-bit slot whose value witness is held by the variable named `witness`), `Witness` and `Table` (pointers to static data, never counted). `CType.counted` (references and slots) replaces `boxed` wherever a pass decides reference counting, and `Core.countedVar` replaces `boxedVar`; `boxed` keeps its meaning, "statically a reference", for the static part of a reference map. `Atom` gained `StaticWitness(name)` (`@fx_w_imm`, `@fx_w_ref`) and `StaticTable(key, entries)`, which carries each entry's shared and exclusive code function, so that every pass finds the code a table makes reachable the way it finds a closure's, wherever the table appears, results included (`Core.tableEntries`, `Core.indirectEntries`, `Core.callees`). `Rhs` gained `CallTable(table, entry, args, exclusive)`. `Dup` and `Drop` of a slot variable go through its witness. **Layouts.** A value witness record is `{i64 kind, ptr dup, ptr drop, ptr copy}`: `kind` is 1 for a reference and 0 for an immediate, so that it is the slot's reference-map bit as it stands, and later operations (`reuse_test`, `evacuate`, `size`) are appended without moving these. The runtime defines `fx_w_imm` (no-op `dup` and `drop`, identity `copy`) and `fx_w_ref` (`fx_dup` and `fx_drop` on the pointer). A witness `dup` or `drop` is an indirect call through the record (Rule MM7.4(1)), emitted inline as a load and a call, not a runtime helper, so that LLVM can see a constant witness. A table is a static `[N x {ptr exclusive, ptr shared}]`, the closure's order, entry `i` at offset `16 i`. An entry is called like closure code: it receives the table pointer first, then every argument as an `i64` slot, and returns an `i64` slot; `EmitLLVM` widens and narrows at the call (shared with `CallClosure`), and an entry's definition narrows its parameters and widens its result, because `indirectEntries` gives it the slot convention. A reference now crosses an indirect call as an `i64` too (`ptrtoint` and `inttoptr`), where 4.1.M3 passed it as a `ptr`: code compiled for a slot parameter takes an `i64`, and a caller that knows the argument is a reference must not call it with a `ptr`, which LLVM leaves undefined. Passing the table lets the entries of a parameterised satisfaction read the witness records of the type arguments, which 4.1.M6 or 4.1.M7 can append after the entries at offsets the capability fixes. `Versions` picks the exclusive pointer by the closure rule (every counted argument exclusively owned in every part) and requests the shared version of every entry a table names. A cell built with slot fields gets its reference map at run time: the static bits of its reference fields or'ed with each slot field's `kind << slot`, loaded from its witness (`EmitLLVM.refs`, for constructors, reuses and closure captures). A slot field counts against the 32-slot limit like a reference. **Passes.** `Rc`: slots are owned, consumed, dup'ed after a `Field` read and dropped like references; `CallTable` consumes its arguments. `Demands`: `CallTable` reads the table and demands `own` of its arguments, which it keeps. `Versions`: `counted` in ownership, construction and call masks; `CallTable` picks its pointer and logs a `table` site, which counts towards `removed`. `Memory`: slots flow like references; `CallTable` arguments escape `Unbounded`; a step that calls through a table is not pure. `Consume`: table entries are indirect, so never erased or borrowed, and a witness parameter is never erased, because `Rc` adds `dup` and `drop` calls through it after demand inference. `Aborts`: a table call may abort. `FipCheck`: a `CallTable` breaks the contract (no requirement is declared `fip`), and so does a `dup` or `drop` of a slot (Rule CM4.7); since the check runs before reference counting, it applies `Rc` without borrowing to every function the `fip fn` reaches and looks for slot `dup`s and `drop`s. `MemoryReport` needed no change. `EmitLLVM` keeps its operand aliases per function, because two versions of one function share variable names and may now narrow a parameter differently. `Core.freeVars` returns a slot variable's witness with it, so a closure that captures a slot captures its witness. `Lower` reports `show` on the new types as `B005`. **Tests.** `WitnessSuite` builds Core by hand, runs it through `Backend.analyseCore` and `Backend.emit` (now separate from `Backend.compile`), ships it with `FX_CHECK_EXCL` and checks output and counters: an identity and a store-read-drop round trip on a slot, with `fx_w_imm` on an `i8` and `fx_w_ref` on a fresh string; a cell with two slot fields built and dropped with each witness; the same cell built with the immediate witness for a reference, which leaks exactly one allocation, so the witness is what decides; a one-entry table called through both pointers (output names the entry; the IR has two indirect calls through `@"fx_t_demo"` and no direct call of either entry); a slot its callee does not use, dropped by the caller through `fx_w_ref` (the witness `drop` at offset 16); a closure that captures a witness and a slot, reads both back and frees the slot with the closure; and the `fip` violations. Two deliberate breakages were run against it: an emitter that ignores the run-time map bit fails the round trip and the cell tests by a leak, and an emitter that ignores the witness `dup` and `drop` fails them by the `dups` count, which the tests pin because that breakage is a use-after-free the allocation counters do not see. **Deviations from the plan.** No `WitnessKind` right-hand side: a slot's type names its witness, so the emitter computes the map bit where the cell is built. No `ToSlot` / `FromSlot`: a `Move` between a slot and a concrete type converts (Rule CM3.3), and every pass already treats a `Move` as the ownership transfer it is. No `fx_slot_dup` / `fx_slot_drop` runtime helpers (see above). Table entries are ordered exclusive first, as closures are. **Found.** `copy` cannot be implemented in `fx_w_ref`: a cell's header holds neither its size nor its field types, and one witness serves every reference type, so `fx_w_ref.copy` stops with an internal error. A copy of a value of parameter type needs a witness per type whose `copy` is that type's generated copy function (the spec's witness per (type, class), Rule CM4.1), or a size in the header. Nothing asks for one yet: a baseline cannot reuse a cell of parameter type, so no demand marks a part of parameter type exclusive and the shared entry of a `fip fn` never copies one. Core therefore has no witness copy, and Rule CM4.7's `copy` has nothing to check yet; the record keeps the field so that its layout is final. **Left out.** A table entry that is itself a `fip fn`: `Versions` would build its shared entry `f#shared` while the table still names `f`, so 4.1.M6 must put an adapter around a `fip` member. Exclusive clones of table entries (the exclusive pointer is whatever the table names); `size`, `reuse_test` and `evacuate` in the witness; witness records for parameterised types. |
| 4.1.M6 | Done. **Built.** `Tables` (a trait of `Lower`, beside `Generated`) builds the table of a (type, capability) pair when asked (`Lower.table(t, cap)`), with an adapter function per entry; `Lower` itself asks for none yet, so the output and counts of every example and benchmark are unchanged in both modes, and their IR is identical to 4.1.M5's except for one effect identifier in `03_option_result` (`Fail` is prelude symbol 167 instead of 156, because the prelude gained eleven symbols). **Canonical member order** (`Tables.members`): the capability's own members in declaration order, then those of each capability its `extends` chain reaches, depth first in the order written, each name once at its first occurrence (Rule 6.2 merges a name inherited twice, and an own member shadows an inherited one: `Optional` has one `fold`), then the hidden entries `lt` and `le` if `Ord` is among them. A test freezes the order of `Numeric` (`from_i64, add, sub, mul, div, rem, negate, eq, compare, lt, le`), `Ord`, `Optional`, `Folding` and `Show`. **Table and entry.** A table is the static `[N x {ptr exclusive, ptr shared}]` of 4.1.M5, named `fx_t_<type>:<capability>`; both pointers are the same, since no exclusive version of an entry is built yet. An entry takes the table, then the witnesses of the member's own type parameters in order of introduction (`R` of `fold`, `B` of `map`; Rule CM4.3), then the receiver and the arguments, and is called with the slot convention of 4.1.M5; every argument is owned (Rule CM4.6). A member `t` does not define makes `table` return an error for the use that asked ("`bool` does not define `Numeric.from_i64`", "`List of u64` does not define `Eq.eq`"), and so does an instance whose argument is a type parameter, which needs a witness record built at run time (4.1.M7c). **Primitive types and `String`** (compiler-provided): for `i8` to `u64`, `f32` and `f64`, `Numeric`'s arithmetic (`Prim` on the narrowed arguments, so a `u8` add wraps exactly as the direct one; division by zero panics as it does directly), `negate` and `from_i64` (a new `PrimOp.Convert`, truncation or `sitofp`; at 64-bit integers the entry returns its argument); `eq`, `lt` and `le` for those and `char`, `eq` for `bool`; `show` through the same runtime formatter as a direct `show` (`Lower.formatter`, now shared). `String` has `show` (the identity), `eq` (`fx_str_eq`) and `concat` (`fx_str_concat`). Each consumes what its runtime function consumes, so no adapter has a borrowed argument to drop. `compare` has a null entry while no data type satisfies `Ordering` (the shortcut of §2). **Data types.** An entry is a per-instance adapter over a member generated once, polymorphic in the data type's own type parameters and in the member's: `List of A.foldLeft[R]` (`Folding.fold`), `List of A.find[Maybe of A]`, `List of A.length[u64]`, `List of A.map[B]`, and the derived `fold` of a sum type for its capability's `fold`, whose adapter reorders the callbacks from the capability's constructor order to the variant order (`mappedFold`, as `Lower` does at a direct call). A constructor requirement mapped by `satisfies` (`Cons as cons`, `Good as ok`) is an adapter that builds the variant. To build the polymorphic members, `Lower.ctype` maps a type parameter to `Slot("w.<name>.<id>")` and each generated member takes one hidden witness parameter per type parameter its type arguments mention, in order of first occurrence (`Generated.hidden`), passing them on in its recursive calls; at concrete types the list is empty and nothing changes. The adapter passes the static witness of each of the instance's arguments (`fx_w_imm` or `fx_w_ref`) and its own method witnesses. The IR of the tests shows one `List of A.foldLeft[R]` body called by the `List of u64` and the `List of String` adapters with `@fx_w_imm` and `@fx_w_ref`. An entry is chosen by the capability that declares the member, so `Optional`'s `map` is `Functor`'s. Members of the language the backend does not build (`compare`, `fold_right`, `head`, `tail`, and `find` on data that is not list-shaped) get null entries. **Prelude.** `Numeric` gained `add`, `sub`, `mul`, `div`, `rem` and `negate` (`spec/stdlib.md` §4.9). The diagnostics of all eleven examples are identical before and after (dumped and compared), and the typer, quantity and compiler suites pass. **Tests** (`WitnessSuite`, hand-built Core over a `Lower` of a small unit, every call through a table pointer, leak check and `FX_CHECK_EXCL`): `add` and `show` at `u8` (200 + 100 prints 44 through the table and directly), `i64` and `f64`; `eq`, unsigned and signed `lt`, `le` on `f64`, `from_i64` (300 at `u8` is 44) and `negate`; `String`'s `eq`, `concat` and `show`; on `List of u64` a `fold` with an immediate and with a reference result, `length`, `map` then `fold`, and `find` whose `Maybe` result is folded through the `Optional` table; a `List of String` fold, which needs the reference witness for its elements (passing the immediate witness instead fails the test); a `Res` whose variants are in the other order than `Result`'s constructors, built with the `ok` and `err` entries and folded through the reordering entry; a `Cmp` whose variants `Gt, Lt, Same` map to `Ordering`'s `lt, eq, gt`, a three-way permutation that is not its own inverse, so applying the inverse instead fails the test (tried); the frozen order and the error cases. **Deviations from the plan and the spec.** Rule CM4.2 gives a parameterised data type one table whatever its arguments, whose entries read the witness records of the arguments. Here a table is per instance: the adapter is per instance and carries the static witnesses, while the member it calls is the one shared polymorphic body. That needs no run-time record and is enough while every instance is known; a table for an instance whose argument is a type parameter of the caller needs the record built at run time (4.1.M7c). `find` returns its `Maybe of A` without the witness record Rule CM4.2 asks for a callee-chosen result. A member the backend does not build has a null entry rather than being a compile error, because those members do exist in the language. The plan's `TyperCpuBench` A/B for the prelude growth was not run: wall-clock comparisons are the coordinator's. **Left out.** Exclusive versions of entries; an adapter around a `fip` member (none is reached); `String`'s `Ord` (no runtime comparison); `from_f64`; `i128` and `u128`. |
| 4.1.M7a | Done. **Built.** `Mode.NoSpecialise` now also sets `Lower`'s `baseline` flag; the default mode lowers exactly as before (the emitted `.ll` of all nine programs is identical to the byte, checked against `75701bf`). In baseline mode a polymorphic function whose type parameters are all unbounded and whose parameters have no anonymous bound is lowered once under its own name (`Lower.baselineOf`), with one witness parameter per type parameter in order of introduction (Rule CM4.3), its values of parameter type as `Slot`s, and each call passing the static witness of a known type argument or the caller's own witness (`witnessAtom`). Every generated member (derived `fold`, `unfold`, `map`, and `foldLeft`, `length`, `find` on list-shaped data) is built once, at its data type's own parameters, by `Generated.member`, which the direct calls in `Lower` and the table adapters of 4.1.M6 now share: `List of A.foldLeft[R]`, `List of A.map[R]`, `Tree.fold[R]`, `Nat.unfold[R]`, and inside `Json.fold[R]` the functor mapping calls `List of A.map[R]` and `Tuple of (A, B).map[R]` with witnesses. The typer gives the result of a derived `fold` and the seed of a derived `unfold` no symbol, so the member's own type parameter (also the new element of `map`) is `Generated.R`, a reserved type variable shown as `R`, with the witness parameter `w.R`. A closure, and a `handle` subject function, captures the witness of every slot among its parameters and the variables its body binds, as well as those its body uses, because reference counting may drop such a slot through its witness (a probe with a `match` on a captured generic pair inside a lambda failed to link before). A direct call converts at the boundary, as closure calls do since 4.1.M3: `EmitLLVM.direct` widens an argument of known type into a slot parameter and narrows a slot result into the caller's type, so Core keeps the caller's types and no analysis changed. `Rc` now waits to drop a dead slot until its witness is bound (a closure loads its captured witnesses on entry). Where a baseline version cannot be built, `Lower.attempt` discards what the attempt built (functions, problems, and the record of baseline versions and instances, so that a callee whose baseline version was built inside a failed attempt is built again when called) and the function is lowered per instance, as in the default mode, with the reason recorded in `Lower.instances`; a call at a type parameter of a function that has no baseline version is `B005`. A literal, an operator, `==` and `show` on a value of parameter type are `B005` (and so make the attempt fail) until 4.1.M7b. The runtime gained the counter `wops`, the number of `dup` and `drop` calls made through a value witness. **What is baseline** (`--no-specialise`, baseline versions / functions still lowered per instance): `01_basics` 4 / 1 (`fib_iter[u64]`: bounded parameter, 4.1.M7b); `03_option_result` 1 / 0; `04_json` 9 / 2 (`join[List of String]` and `map_optional[...]`: anonymous bounds, 4.1.M7c); `05_phantom_types` 0 / 18 (every function but `main` has an anonymous bound, 4.1.M7c and 4.1.M7d); `bintrees` 2 / 0; `nat_loop` 4 / 0; `list_pipeline` 3 / 0; `records` 3 / 0 (one `List of A.foldLeft[R]` serves the folds over `List of u64` and `List of Order`); `fail_chain` 2 / 0. `BaselineSuite` asserts these numbers, the names of the remaining instances, and that each reason names the milestone that removes it. **Measured** (`--no-specialise`; allocations / frees / dups / drops / reuses / witness calls): bintrees 2097151 / 2097151 / 8388601 / 12582907 / 0 / 2097150; nat_loop 4000402 / 4000402 / 6000801 / 10002206 / 0 / 2000600; list_pipeline 4000602 / 4000602 / 14000801 / 18002806 / 4000000 / 10000600; records 3000302 / 3000302 / 9000401 / 13001206 / 2000000 / 4000300; fail_chain 4000602 / 4000602 / 5997601 / 9999006 / 0 / 9995800. The first five counts equal the default mode's: a witness `dup` or `drop` of a reference is the same `fx_dup` or `fx_drop` (Rule CM4.1 with the two witnesses of §2), one of an immediate is a no-op the old counters do not see, and the reuses already went through the `rc == 1` test in this mode (Rule PR6.3, since 4.1.M4). The cost of the baseline is the witness calls, all of them indirect calls of a no-op `imm` entry or of `fx_dup` / `fx_drop` (Rules CM4.1, MM7.4(1)): in `bintrees` one per `Tree.fold` result, in `list_pipeline` the element and accumulator slots of `map` and `foldLeft`. Emitted functions and `.ll` bytes (default / `--no-specialise`): `01_basics` 14 / 13, 12127 / 12199; `03_option_result` 14 / 13, 14238 / 13205; `04_json` 56 / 46, 67772 / 56028; `05_phantom_types` 19 / 19, 17791 / 17791; bintrees 8 / 8, 5069 / 5429; nat_loop 12 / 12, 8417 / 9288; list_pipeline 12 / 11, 8442 / 8217; records 19 / 14, 16043 / 11871; fail_chain 11 / 11, 11274 / 12525. **Tests.** `BaselineSuite`: the per-program numbers above; `records` has two `foldLeft`s in the default mode and one, `List of A.foldLeft[R]` with a slot accumulator and result, with `--no-specialise`; a program calling `pair`, `last` and `swap` at `String` and at `i8` has one version of each, every parameter of parameter type a slot and no scalar variable in `swap`, and the default mode has `last[String]` and `last[i8]`. `EmitSuite` runs that program in both modes (output, leak check, `FX_CHECK_EXCL`; `-3` and `-100` survive the slot, the Strings are freed through `fx_w_ref`), checks that the default mode makes no witness call and `--no-specialise` some, and that the IR calls `last` with `@fx_w_ref` and with `@fx_w_imm`. A second program has a lambda that matches on a captured `Pair of (A, B)` (its slots need the captured witnesses) and a function `shown` whose baseline attempt fails on `show` of a value of parameter type after building `ident`'s baseline version, so that its instance `shown[String]` must find `ident` again. **Deviations from the plan.** No `ToSlot` / `FromSlot` and no `Move` at calls (above). Generated member names keep their data type with its own parameters (`List of A.map[R]`), so a baseline name contains `[`, against §E.2's "no function name contains `[` except generated members". **Found.** A program the correctness review wrote (`data Pair of (A, B)`, a generic `swap` that rebuilds the pair, called on a fresh pair and on a pair holding a swapped pair) fails in both modes at `75701bf` already: the default mode stops on `reused a cell that is not exclusively owned` under `FX_CHECK_EXCL`, and `--no-specialise` corrupts the heap. It is not caused by this milestone and is left for a separate fix. **Left out.** The method parameter of a `satisfies` method body (none is lowered); a phantom parameter still gets a witness (4.1.M7d). |
| 4.1.M7b – 4.1.M9 | Pending. |

---

The rest of this document is the inventory the plan was derived from: how the backend works at `5a0eedd` (§A), what changes for each target (§B), the milestones (§C), what can proceed in parallel (§D) and the test strategy (§E). File and line references are to `5a0eedd`. Where it says "the spec does not say", the spec was amended after the inventory was taken (`docs/decisions/P0-spec-review-2026-11-20.md`): slot widening is Rule CM3.3, witness records are Rule CM4.2, specialiser rounds and the definition of `removed` are Rule CM5.4.

## A. How it works today

### A.1 Pipeline (`Backend.scala`)

- `compile` (L:26–33): `analyse`, then `FipCheck`, then `Consume`, then `Aborts`, then `Rc` per function, then `EmitLLVM`.
- `analyse` (L:39–50):
  - `Lower(unit).program`;
  - `Demands.analyse` on the lowered instances;
  - `Versions(...).run()`, given a `copy` callback (L:45) that asks `Lower` for `T.copy[...]` functions;
  - the generated copy functions are appended (L:47);
  - `Memory` runs on the versioned Core.
- `Main.scala` L:22–37 matches the argument list exactly: `compile f -o out`, `compile f --explain-memory [--format json]`, `ship f -o out`. Any extra flag is a usage error (exit 2), so there is nowhere to put a `--no-specialise` flag today.

### A.2 `Lower.scala`: typed tree to Core

**Monomorphisation from `main`.**
- `program` (L:52–56) lowers `main` with an empty substitution.
- `instance` (L:59–63) builds each instance name once. A placeholder is entered first, so recursion terminates.
- `Scope(env, sigma, bounds)` (L:23):
  - `sigma` maps the instance's type parameters to concrete types;
  - `bounds` maps each capability-bound parameter type to the concrete argument type.
- `call` (L:207–222) instantiates the callee from the type the reference was recorded at:
  - `Lower.solve` (L:661) matches the declared signature against the instantiated arrow type;
  - an unsolved parameter becomes `UnitType` (L:211). Phantom parameters are erased this way;
  - anonymous bounds (`is C`, a `CapRef`) in parameter position are **specialised per call** to the argument's concrete type (L:215–218). The instance name records it, for example `join[List of String]` (L:219–220).
- Consequence: every Core function is fully monomorphic, and the typer type of every variable is concrete.

**Concrete types.**
- `typeOf` (L:606) is `concrete(unit.typeOf(t))`.
- `concrete` (L:609–610) is `represent(subst(replaceAll(t, bounds), sigma))`.

**Type parameters.**
- They never reach `ctype`, because `concrete` has already substituted them.
- If one did, `ctype` (L:620–627) would report `B005` ("values of type … are not supported yet") and return `CType.Unit`.
- There is no Core type for "a value of unknown type".

**Capability bounds.**
- `Representation.apply` (`Representation.scala` L:20–38) replaces every `IsBound` / `CapRef` with the single data type in scope that satisfies all the capabilities (L:29–33).
- With no such data type, a product capability becomes its own auto-flat-struct `CapRef` (L:34–37, `constructor` L:48–51, `fields` L:54–58).
- The rule runs everywhere `concrete` runs: parameters, results and locals.
- When the bound is ambiguous (`is Folding of String`, satisfied by `List` and by `Maybe` through `Optional`), the bound stays unrepresented, and the per-call `bounds` map is what makes the instance concrete.

**Member resolution.** `method` (L:253–259) reads `unit.resolutionOf(m)`. That is `TypeTable.resolution`, an identity-keyed side table filled by the typer (`typer/Typer.scala` L:16–31). Its values are the `Resolution` enum (`typer/Resolution.scala` L:6–9): `Field(variant, index)`, `CapMember(cap, member)`, `DerivedFold(data)`. `resolved` (L:261–315) then dispatches:
- `Field` → `Rhs.Field` at `Layout.slots(...)(index)` (L:272–277).
- `DerivedFold` → `Generated.derivedFold` (L:278–280).
- `CapMember` is dispatched on (capability name, method name, arity), as strings:
  - `Concat.concat` → runtime `fx_str_concat` (L:283);
  - `Show.show` only on scalars and `String` (L:285);
  - `Folding.fold` on list-shaped data → generated `foldLeft` (L:287);
  - any sum capability's `fold` → the derived fold with callbacks reordered by constructor mapping (`mappedFold` L:289–295, L:321–329);
  - `Functor.map` → `derivedMap` (L:296);
  - `Sequencing.length` (L:299) and `Folding.find` (L:301) → generated;
  - a product accessor → `fieldRead` (L:312);
  - anything else is `B005` (L:313).
- There is no generic "call the satisfying type's method" path. **`satisfies` method bodies are never lowered** (`phase-4.0-backend-slice.md` §9, 4.0.M4 "Not done").
- Static calls (`static`, L:414–439):
  - `Console` is matched by name (L:419);
  - derived `unfold` (L:423);
  - data constructors (L:428);
  - capability constructors through `constructorsOf` on the representing data (`capVariant` L:433);
  - an auto-flat-struct constructor (L:436);
  - abortive effect operations (L:438, `operation` L:447).
- Resolved constructor calls come from `TyperState.constructors` (L:417).

**Literals and bounded numerics.**
- An integer literal is typed by the typer as a fresh numeric variable with default `i64` (`Typer.scala` L:129, L:188–190).
- `Lower` turns it into `Atom.IntC(v, ctype(typeOf(l)))` (L:81–84). Inside `fib_iter[u64]` the literal `0` is therefore a `u64` constant, because `sigma` maps `N` to `u64`.
- Binary operators are typed structurally (`Typer.scala` L:207–222). The typer records no resolution for them and no `from_i64` desugaring for literals.
- `Lower.binOp` (L:179–198) emits `Rhs.Prim` on scalars. On *any* boxed operand, `==` / `!=` become `fx_str_eq` (L:190–194). That is only correct for `String`: `==` on two data values would call `fx_str_eq` on cells. This is an existing hazard. The typer accepts `==` on any type.
- `prelude.fixed` L:87–88 declares `Numeric` with `from_i64` only. `spec/stdlib.md` §4.9 also has `add`, `sub`, `mul`, `div`, `rem` and `negate`, and the prelude does not.

**Derived and generated members** (`Generated.scala`): one Core function per (data type, concrete arguments, result type).
- Names come from `generatedName` (L:233–234), which renders the concrete types, for example `List of u64.foldLeft[()]`. `derivedUnfold` names its instance with `seedType.toString` (L:67), a `CType` rendering.
- Members built: `derivedFold` (L:34–60, including Rule 7.4.a.1 through `derivedMap`), `derivedUnfold` (L:65–83), `derivedMap` (L:86–104), `foldLeft` / `length` / `find` on list-shaped data (L:110–162), and `copyOf` for the Q9.3 copy (L:171–191).
- `perVariant` (L:239–248) loads fields at `Layout.slots(cts)`, using concrete `CType`s.
- `find` builds `some` / `none` through `capVariant` on the result's representing data (L:145–151).

**Closures.**
- `closure` (L:243–251) lifts the body into `lambda.N`, taking `env :: params`.
- Captures are the body's free variables (L:244). They are loaded from slots `Layout.slots(captureTypes, first = closureCode)` (L:246–247).
- `Rhs.Closure(code, captures)` is emitted with an empty shared entry; `Versions` fills it in.
- Lambda parameter and result types are concrete `CType`s (L:228–233). A `u64 -> u64` closure is called as `i64 (ptr, i64)`.
- Nested `fn`s are closures (L:510–522). A `handle` subject is a separate function called with `Catch` (L:472–500).

**Side tables `Lower` exports.**
- `types` (variable name → concrete typer type) and `spans` (L:35–36), read by `Demands`, `Memory` and `MemoryReport`.
- `fips` (L:39).
- `slotType` (L:584–596) and `cellSize` (L:599–602) feed `Demands` part keys.

### A.3 `CType`, `Atom`, `Rhs`, `Layout` (`Core.scala`)

- `CType` (L:7–16) is `Int(bits, signed) | Float(bits) | Bool | Unit | Boxed`.
  - `boxed` (L:16) is the single question every pass asks.
  - It assumes every value's kind is known statically: there is no "slot of unknown kind", no witness type and no table type.
- `Rhs` (L:49–88): `Move`, `Prim`, `Call(fn, args, runtime)`, `If`, `Con`, `Field`, `Switch`, `Closure(code, captures, shared)`, `CallClosure(closure, args, exclusive)`, `Catch`, `Reuse(cell, tag, fields, unconditional)`.
  - There is no witness construct, no table call and no slot conversion.
- `Core.boxedVar` (L:117–121) identifies the RC-tracked atoms. `Demands`, `Versions`, `Memory`, `Rc` and `Consume` all use it.
- `Layout` (L:165–179):
  - every field takes 8 bytes, but **boxed fields come first** (`slots` L:174–177). A field's slot depends on the boxedness of every field's *concrete* type;
  - `boxedCount` gives the header's `nptr`;
  - a closure uses slots 0 and 1 for its exclusive and shared code pointers (`closureCode = 2`, L:171) and has tag `-1` (L:168).
- Consequence: the layout of `Cons(head: A, tail: List of A)` differs by instantiation. With `A = u64`, `tail` is slot 0 and `head` slot 1. With `A = String`, `head` is slot 0. That violates Rule CM3.4 directly.

### A.4 Runtime header and `fx_drop` (`fixed_rt.c`)

- Header (L:23–27): `{ int64 rc; uint32 tag; uint32 nptr; }`, 16 bytes, payload at offset 16.
- `fx_alloc(tag, nptr, payload)` (L:44–52).
- `fx_drop` (L:100–112):
  - decrements; a negative count means a static object, which is never freed;
  - at zero it drops the first `nptr` payload words as pointers, skipping 2 words for a closure (`tag == UINT32_MAX`, L:107), and frees;
  - it is recursive (`ponytail:` L:99).
- `reclaim` (L:60–66) drops the `nptr` children and sets `nptr = 0`.
  - `fx_reuse_excl` (L:69–74) asserts `rc == 1` under `FX_CHECK_EXCL`;
  - `fx_reuse` (L:77–81) is Koka's dynamic path;
  - `fx_alloc_at` (L:84–91) refills a token.
- `fx_dup` tolerates `NULL`, the zero result of an aborted call (L:94).
- Strings (L:29–33) are `{header, len, bytes}` with `nptr = 0`. String constants are emitted as static objects with `rc = -1` (`EmitLLVM.scala` L:192–197).
- Scalars stored in payload slots are written at their **native width** (`EmitLLVM.store` L:122–127 stores `i8` / `i32` / `float` into the 8-byte slot) and read back at native width (`Field` L:79–82). The upper bytes of a slot holding a `u8` are garbage. A baseline that reads the slot as `i64` would see them.

### A.5 What each analysis assumes about monomorphic input

**`Demands`** (`Demands.scala`):
- Part keys are `(type rendering, slot)` (L:21–24, L:53–54). Renderings come from `Lower.types`, concrete by construction.
- `slotKey` and `cellSize` (L:83–85) assume one layout per type rendering.
- Only `Core.boxedVar` atoms carry ownership (L:166, L:242–245).
- An unknown callee (`!byName.contains(f)`) defaults to `Need.own` (L:129–130).
- A `CallClosure` uses `own` for everything (L:223). This matches Rule CM4.6 already.
- `reuse` (L:194–209) pairs a `Con` with a dead variable of the same *slot count*.

**`Versions`** (`Versions.scala`):
- Cap of 4 (L:61). Starts from `main` (L:74–80).
- The version where every demanded part is exclusive **keeps the name**; shared ones are `f#n` (L:89–94).
- Beyond the cap, or into a `fip fn`, the caller copies with `T.copy[...]` and calls the exclusive version (L:224–239).
- Every closure gets an exclusive and a shared entry (L:163–169).
- `CallClosure` picks the exclusive entry only if every boxed argument is exclusive and whole (L:170–176).
- `construct` computes per-part ownership through `Layout.slots(fs.map(_.tpe))` (L:196–205), so it assumes field kinds are known.
- Result ownership is iterated to a fixpoint (L:80).

**`Memory`** (`Memory.scala`):
- Escapes per variable through `Core.boxedVar` (L:105–106).
- A `CallClosure` argument escapes `Unbounded` (L:116–117), so anything passed to an unknown closure is `Heap` (Rule MM5.2).
- Carried values are recognised **by name substring** (`.unfold[`, `.foldLeft[`, L:155–156). So are fork facts (L:228) and the effect set (L:66).
- `Memory.base` strips the version suffix with the regex `#\d+$` (L:269).

**`Rc`** (`Rc.scala`):
- Owns boxed parameters except borrowed ones (L:22–25).
- `Field` borrows its object and dups a boxed result (L:77–79).
- `Dup` and `Drop` are emitted only for `CType.Boxed` variables. Nothing exists for a value whose kind is known only at run time.
- Drop-guided tokens (L:32–43) choose `fx_reuse_excl` or `fx_reuse`.

**`Consume`** (`Consume.scala`):
- Erases demand-0 parameters and borrows `read` parameters, for functions called directly.
- It excludes closure code and `main` as "indirect" because their convention is fixed (L:17–20).
- Table entries would need the same exclusion.

**`Aborts`** (`Aborts.scala`):
- Inserts an `fx_aborting` test after every user call, closure call and branch (L:40–44).
- It is type-agnostic. A future `CallTable` must join `mayAbort`.

**`FipCheck`** (`FipCheck.scala`):
- An RC operation is a call of a name matching `#\d+$` (a shared version) or a `CallClosure` (L:47–53).
- An evacuation is a call of a `.copy[` function (L:48–49).
- Loops are recognised by name substring (L:78).
- It has no notion of witness operations.

**`MemoryReport`** (`MemoryReport.scala`):
- Per function: `fip`, parameters with demand, parts and escape, allocations, forks, copies (L:33–83). Copies are recognised by the `.copy[` substring (L:68–71).
- No size, clone or candidate information.
- The golden `examples/golden/01_basics.memory.json` pins function names, including `lambda.77#1`, and part keys such as `"List of u64#0"`. Under the boxed-first layout, slot 0 of `Cons` at `u64` is `tail`.

**`EmitLLVM`** (`EmitLLVM.scala`):
- `Switch` loads an `i32` tag at offset 8 (L:54–55).
- Nullary statics are `<{i64 -1, i32 tag, i32 0}>` (L:60–65). Strings are similar (L:196).
- `Con` calls `fx_alloc(tag, boxedCount, 8*n)` and stores fields at `Layout.slots` (L:66–70). `Reuse` is the same through `fx_alloc_at` (L:71–78).
- `Field` loads at `16 + 8*slot` with the variable's native LLVM type (L:79–82).
- Closures store the two code pointers at offsets 16 and 24 (L:90–97). `CallClosure` loads offset 16 (exclusive) or 24 (shared) and calls with the native argument types (L:98–104).
- Function names are `@"fx_u_<instance name>"` (L:213).

### A.6 Tests and data that pin behaviour

- `EmitSuite` (L:45–51) runs examples `01`, `03`, `04` and `05` against `examples/golden/<name>.out`.
  - **Those `.out` files are not in the repository.** `.gitignore` L:4 ignores `*.out`, and `git log --all -- '*.out'` is empty. `Files.readString` throws, so the four golden tests cannot pass on a fresh clone or in CI.
  - The same suite runs 15 small programs with `FX_CHECK_EXCL` and a leak check (allocations == frees, L:39).
- `VersionSuite` pins version naming and the cap:
  - `pass` calls the exclusive `bump`, and `later` calls `bump#…` (L:26–32);
  - "beyond four versions the caller copies" (L:40–48);
  - every closure has two entries (L:50–55).
- `FipSuite` L:55–59 pins "a caller holding a shared value copies into a fip function" and "no `bump#` exists".
- `bench/runtime/run.sh` (L:13–14) builds each program with `compiler/run ship <file> -o <out>`. It has no flag hook and writes `docs/baselines/runtime-<date>.md` (L:18).

---

## B. Changes per target item

### Shared design decisions (used by items 1–7)

**Core types** (`Core.scala`):

```scala
enum CType:
  case Int(bits, signed); case Float(bits); case Bool; case Unit; case Boxed
  case Slot(witness: String)  // 64-bit value of parameter type; `witness` names the Var holding its value witness
  case Witness                // ptr to a value witness record
  case Table                  // ptr to a capability table (or table instance, see below)
  def boxed: Boolean = this == Boxed      // unchanged meaning: statically a reference
  def counted: Boolean = boxed || isInstanceOf[Slot]  // may hold a counted reference
```

- Every pass that asks `boxed` for "RC-tracked" must ask `counted` instead.
- Naming the witness variable inside `Slot` lets `Rc` emit `Dup` / `Drop` through the right witness with no extra table.
- Substitution for a clone is then `Slot(w) ↦ concrete CType` plus deleting the witness parameter.

**New `Rhs` cases:**
- `ToSlot(a: Atom)` and `FromSlot(a: Atom, to: CType)`: canonical widening and narrowing (encoding below). For `Boxed`, `ptrtoint` / `inttoptr`.
- `CallTable(table: Atom, entry: Int, args: List[Atom], exclusive: Boolean)`: like `CallClosure`. Arguments and result are slots or statically known `CType`s per the requirement's signature (`Self` and parameter types are slots). Hidden method-level witnesses come first (Rule CM4.3).
- `WitnessKind(w: Atom)`: an `i64` 0 or 1, used to build a reference map at run time.
- `Atom.StaticWitness(key: String)` and `Atom.StaticTable(key: String)`: constants naming emitted globals (`@fx_w_imm`, `@fx_w_ref`, `@"fx_t_<type>:<cap>"`).
- `Expr.Dup` / `Expr.Drop` on a `Slot` variable mean "through its witness". No new node is needed.

**Canonical slot encoding.** The spec does not give one; see F.5. Proposal:
- signed integers are sign-extended to 64 bits, unsigned ones zero-extended;
- `bool` is 0 or 1; `char` is zero-extended; `()` is 0;
- `f64` is its raw bits; `f32` is its raw 32 bits, zero-extended (not converted to `f64`);
- a reference is the pointer.

This makes `==` on slots of the same type a 64-bit compare for every integer type, and a clone that stores a narrower register must apply the same extension.

**Value witness.** Today the backend puts every boxed value on the heap (no regions or frames are lowered yet), so there are exactly two value witnesses:

```c
typedef struct fx_witness { uint8_t kind; /* 0 imm, 1 ref */ uint8_t size;
  void (*dup)(int64_t); void (*drop)(int64_t); int64_t (*copy)(int64_t); } fx_witness;
extern const fx_witness fx_w_imm, fx_w_ref;   // ref: dup/drop = fx_dup/fx_drop on the pointer; copy = shallow cell copy + dup children
```

- `reuse_test` and `evacuate` have no consumer until region lowering (Phase 4). Add them then, without a layout change, by appending fields.
- A per-(type, class) witness appears only when `Frame` / `Region` lowering lands. Until then the witness of *any* reference type, including `List of A` built inside a baseline, is the constant `fx_w_ref`.
- This is the biggest simplification available. Composite value witnesses never need run-time construction.
- In emitted IR, a witness `dup` / `drop` is `call @fx_slot_dup(ptr %w, i64 %x)`, an inline `kind` test followed by `fx_dup`.

**Capability table and table instance.**
- A **table** is a static record per (type constructor, capability): `[N x {ptr excl, ptr shared}]`, members ordered as `MemberTables.table` flattens them (`typer/MemberTables.scala` L:40–51; the "own member first, then supers depth first" order must be frozen as the canonical order of Rule CM4.2).
- A satisfaction of a *parameterised* data type whose members need the element type's witness or tables (`List of A satisfies Folding`, or derived `Show` for `List of A`) cannot be a static record per Rule CM4.2. See F.3.
- Proposal: a **table instance** is `{ptr table; ptr arg_witnesses[k]; ptr arg_tables[m]}`.
  - It is static when the arguments are known (`List of u64`).
  - It is built on the caller's frame when they are not (inside a baseline of `f<A>`): a `Rhs.TableInstance(table, witnesses, tables)` allocation, `Frame`-class, so it needs no reference count.
  - An entry receives the instance as its first hidden argument and reads its type arguments' witnesses from it.
  - This is GHC's dictionary with superclass/context fields, and it keeps the "two code pointers per entry" rule.
- Building instances with run-time arguments can be deferred until a test needs it. With today's examples, every bound type argument at a call site inside a baseline is either known (`join(parts)` with `parts: List of String`) or passed straight through. Start with static instances and `B005` the rest.

**Calling convention of a table entry** (Rule CM4.6):
- `entry(instance, [method witnesses], receiver: slot, args: slots…) -> slot`.
- Every reference argument is passed under `own`. The exclusive pointer is chosen statically, as `Versions` already does for `CallClosure` (L:170–176).
- The entry is an **adapter** over the member's own function. It widens or narrows, calls, and drops what the member only `read`.
- Adapters are generated per (satisfying type, member), exactly as closure code is. `Consume` must treat them as indirect: no erasure, no borrowing.

**Hidden parameter order** (Rule CM4.3): per non-phantom type parameter in order of introduction, its witness, then one table instance per bound after `extends`-flattening. No `of` value parameters, evidence or destinations are lowered today.
- Phantom-ness: the typer has no phantom table the backend can read. `Lower.solve` defaulting an unsolved parameter to `UnitType` is the current proxy.
- A parameter that no parameter type, result type or body use mentions is phantom. That can be computed in `Lower` from the signature: does the type parameter occur in a value position? `05_phantom_types` needs exactly this: `Door of Locked`, `Quantity of U`.

**How baseline code does arithmetic, literals, `show`, `==` and `fold` on a slot:**
- **Arithmetic** on `N is Numeric`: `a + b` becomes `CallTable(tNumeric, add, [a, b])`.
  - The prelude `Numeric` must gain `add`, `sub`, `mul`, `div`, `rem` and `negate` (spec `stdlib.md` §4.9). Prelude `Numeric` has only `from_i64` (L:87–88).
  - The built-in tables for `i8…u64`, `f32` and `f64` are compiler-provided (stdlib L:218). Their entries are tiny emitted functions: `narrow, prim, widen`. Division by zero calls `fx_panic_div` as today.
  - `<`, `<=`, `>` and `>=` have no requirement to dispatch to except `Ord.compare -> is Ordering`, and nothing in the prelude satisfies `Ordering`. See F.2. Proposal: compiler-provided hidden entries `lt` / `le` (with `gt` / `ge` swapped) in the built-in `Ord` tables, and `eq` from `Eq`.
- **Literals** of type `N`: `CallTable(tNumeric, from_i64, [IntC(v, i64)])`, as `stdlib.md` :216 specifies. A literal above `i64::MAX` in a `u64`-instantiated baseline cannot pass through `from_i64`. Float literals have no `from_f64` (stdlib :216 "deferred"). No example needs either in a baseline position.
- **`show`** on a slot: `CallTable(tShow, show, [x]) -> Boxed`. The built-in entries call `fx_show_u64` / `fx_show_i64` / `fx_show_f64` / `fx_show_bool` / `fx_show_char` after narrowing. The `String` entry is the identity.
- **`==` / `!=`**: `CallTable(tEq, eq, [a, b])`. The `String` entry is `fx_str_eq`. Once `==` on a slot goes through `Eq`, the `fx_str_eq`-on-any-boxed shortcut (`Lower.scala` L:190) must be restricted to `String`.
- **Derived `fold`** on a value whose data type is known but whose element is a parameter (`List of A`): generate `List of A.fold[R]` once, with `A` and `R` as slots. The callbacks are closures with the slot convention.
  - A `fold` on a value whose *data type* is a parameter (`x: C is Folding of N`) is `CallTable(tFolding, fold, …)`. The `List` entry is the slot-typed `foldLeft`.
  - A sum capability's `fold` with reordered callbacks (`mappedFold`) becomes an adapter in the table entry. The reordering moves from the call site into the adapter.

**Where the baseline gets the result type of `is C`.**
- In one package with the `Representation` stand-in, every `is C` result or local resolves statically to one data type or an auto-flat-struct. Those results are callee-chosen with a known witness, so no hidden result witness is needed until M11 or packages exist.
- The one case that needs the caller-chosen path (Rule CM4.5) is `05_phantom_types`: `unlock` builds its result with `Door.new(...)`, an abstract constructor requirement. In baseline mode, keep `Representation` choosing the representation at the caller and passing it as a hidden witness plus table. Or defer it with `B005` in baseline mode and let `05` run only after M7d.

### Item 1: baseline version per function

**Files changed:**
- `Lower.scala`, the bulk.
- `Generated.scala`, so that generated members accept type arguments containing parameters.
- `Representation.scala`: stop applying it to parameter positions.
- `Core.scala`.
- `EmitLLVM.scala`.
- The analyses, for `counted` instead of `boxed`.
- Runtime: witnesses, `fx_slot_*`, built-in tables.
- `prelude.fixed`: the `Numeric` members.

**Concrete changes in `Lower`:**
- `Scope` gains `witnesses: Map[SymbolId, Var]` and `tables: Map[(SymbolId, SymbolId), Var]` (type parameter, capability).
- `function` (L:68–77) builds the hidden parameters first. Instance names stay the bare declaration name for the baseline (`fib_iter`). Clones keep today's naming (`fib_iter[u64]`), which is already a canonical key in the sense of Rule CM6.5 for the type axis.
- `concrete` keeps type parameters as `TypeRef(param)`, and `ctype` maps them to `Slot(witnessVarName)`.
- Anonymous bounds in a parameter position (`join(parts: is Folding of String)`, `unlock(door: is Door of Locked)`) become implicit type parameters: they get a witness and table instances, are not represented, and are not added to `bounds`.
- `call` (L:207–222) passes witnesses and tables:
  - a known type argument gets a static witness (`fx_w_imm` / `fx_w_ref`) and a static table instance;
  - a type argument that is the caller's own parameter passes the caller's witness or table through;
  - a composite argument (`List of A`) gets `fx_w_ref` and, for tables, a `TableInstance`.
- Every point where a slot meets a concrete type gets `ToSlot` / `FromSlot`: arguments of parameter type passed to a known callee, results, field reads and stores of parameter-typed fields, and closure arguments and results.
- `binOp`, `unaryOp`, `IntLit` / `FloatLit` and `show` on a slot become table calls.
- `resolved` (L:261–315) gets a single new arm for a receiver of parameter type: `CapMember(cap, member)` → `CallTable(scope.tables(param, rootCap), index(member), …)`.
- Constructors with a parameter-typed field compute the reference map at run time: a static OR of `WitnessKind(w) << i` per slot field.
- Closures created in a baseline capture the witnesses their body uses (`memory_management.md` :423). That is automatic if witnesses are ordinary `Var`s, because `Core.freeVars` collects them.

**Analyses.**
- `Demands`, `Versions`, `Memory`, `Rc` and `Consume` swap `boxedVar` for a `countedVar` that includes `Slot`. Part keys for `Slot` types render with the parameter's name. That is coarse but conservative: parts with the same rendering only merge.
- `Memory` treats `CallTable` arguments as `Unbounded` escapes, as for `CallClosure`.
- `Aborts.mayAbort` adds `CallTable`.
- `Rc.Walk.consumed` adds `CallTable` (consumes table, args) and `ToSlot` / `FromSlot` (a move).

**Hard parts:**
1. **The `Representation` / `bounds` interplay.** Today the ambiguity of `is Folding of String` is resolved *by* specialisation. In the baseline it has to become a table, so `join`'s body `parts.fold(…)` dispatches through `Folding.fold`. The `List` entry is the generated `foldLeft`, but the `Maybe` entry has no definition at all (prelude L:131). Tables must therefore be built only for pairs actually passed (F.4).
2. **Generated members with partly abstract arguments** (`List of A.foldLeft[R]`). Names must be canonical with parameters in them. `derivedUnfold` names its instance by `CType.toString` (L:67), which would give `Slot(w.12)`, with a fresh variable name inside the instance name. Change it to the typer rendering.
3. **The narrow-width store and load of fields.** Moving to canonical 64-bit slot stores for parameter-typed fields is a correctness prerequisite (M2).
4. **The typer's auto-derived fallback.** `Members.scala` L:153–154 makes `x.show()`, `x == y` and `x.clone()` legal on an *unbounded* type parameter, but no table is passed for an undeclared bound (F.6).

### Item 2: specialiser

**New file `Specialise.scala`.** It runs after `Demands` on baseline Core, per `memory_management.md` §20.3, and re-runs `Demands` and `Memory` on the clones.

**Data:**

```scala
final case class Key(types: List[Option[Type]], mask: List[Set[Option[Need.Part]]])  // canonical text = Rule CM6.5
final case class Request(caller: String, site: String, depth: Int)                    // depth = fold nesting, from Lower
final case class Candidate(fn: String, key: Key, removed: Int, weight: Long, score: Double, built: Boolean, why: String)
```

**Inputs:**
- `size(f) = Core.lets(baseline f).size`.
- `removed(f, key)` counts, in the baseline, the `Dup` / `Drop` on `Slot` variables whose witness the key fixes, the `CallTable` on fixed tables, and the `Reuse(unconditional = false)` made unconditional by the mask. See F.7 for why calls that select a better callee must also count.
- `weight` is the sum of `8^depth`. `depth` needs a new `Lower` side table `depth: Map[fnName, Int]`, incremented when a lambda is lowered as an argument of a call that resolves to a `fold` / `unfold` / `foldLeft` (a derived member or a `Folding` table entry).

**Order:**
- Candidates are sorted by score descending, with ties broken by canonical key text.
- Each built clone is charged `size(f)`. Stop at `B = 512` (a `Specialise.B` constant; also accept `-Dfixed.B`, or an env var, for measurement).
- A candidate with `removed == 0` is not built.

**Building a clone of the type axis:** call today's monomorphic `Lower.function(decl, sigma, bounds, name)`. This is the route allowed by Rule CM5.1, "lowering the typed tree again with the types substituted". Then rewrite the requesting call sites from `f` plus witness arguments to `f[key]` without them.

**Rounds:** clones create new requests. Iterate:
1. collect requests from all functions built so far;
2. decide new candidates per function within the budget left;
3. build them;
4. repeat until no new candidate is built.

The process is monotone (a decision is never undone) and terminates, because each function's budget is finite. F.8 explains why the spec must state this.

**Hard parts:**
- Proving "clone = substitution into the baseline" when the clone is re-lowered (E.2).
- The ownership axis lives in `Versions`, the type axis in `Specialise`; they must share one budget per function. Cleanest: `Versions` reports its candidates to `Specialise` rather than deciding on its own.
- `Demands` must run on clones *after* they exist. Today it runs once on everything (`Backend.scala` L:43).

### Item 3: mandatory specialisations

- **Fusion and step inlining** (Rules 7.4.b.1, 7.4.b.2) **do not exist** in the code today (`phase-4.0-backend-slice.md` §2; `Generated.derivedUnfold` allocates every layer).
  - There is nothing to keep in `--no-specialise` mode except one property. Derived members are generated *per data type*, so "data type statically known at the call site" already holds for every derived `fold` / `unfold` on a known data type.
  - The rule to write into the 4.1 plan: the future `FoldSpecializer` runs on baseline Core and on clones in **both** modes, and its input is the derived-member call whose data type is known. It is not part of the budget.
- **`fip fn`:**
  - Its one body is the exclusive version, under the function's own name.
  - Its shared entry is a generated adapter `f#shared` (Rule CM5.5): it calls `copyOf` for each demanded part of each `exclusive` parameter, then the body. `copyOf` exists already (`Generated.scala` L:171–191). For a part of parameter type, copy through the witness `copy`.
  - Change `Versions.call` (L:224–239): delete the call-site copy, and point shared callers at `f#shared`.
  - Change `FipCheck` (L:47–50): the evacuation item becomes "calls `f#shared`" instead of "calls `.copy[`". The RC item adds `Dup` / `Drop` on `Slot` variables and `CallTable` (Rule MM18.1(2), Rule CM4.7).
  - Neither `fip` version is charged to the budget.

### Item 4: shared version primary, exclusive versions are clones

- **`Versions.scala`:**
  - `request` (L:89–92) inverts the naming. The all-shared mask keeps the name `f`. Other masks get a canonical suffix, for example `f#x[0:L#1]`, from the mask text, not a counter, so that names are stable and sortable (Rule CM6.5).
  - The cap (L:61, L:224) becomes the budget (item 2).
  - Delete the copy branch (L:227–239), except for the `fip` shared entry.
  - Walk from `main` over the *shared* version first. That makes `main`'s callees shared, which is correct but changes nothing when `main` holds exclusive values, because the walk still requests exclusive versions where arguments are exclusive.
  - Exclusive closure entries also become clones. `Rhs.Closure(code, cs, shared)` swaps which field is primary: `code` should be the shared entry and `exclusive` optional. Rename the field to keep `EmitLLVM` (L:85–95) readable.
- **`Memory.base`** (L:269) and **`FipCheck`** (L:47): replace the regex with a structured name. Better: give `Fn` a field `version: Option[Key]` so no pass parses names.
- **Behaviour change:**
  - Past the old cap, the caller called a copy plus the exclusive version; now it calls the shared version. Fewer allocations, more `rc == 1` tests.
  - Exclusive versions with `removed = 0` are not built.
  - The golden stdout cannot change. The memory JSON golden and the bench counts do.

### Item 5: `--no-specialise`

- **`Main.scala`:** replace the exact-list patterns (L:22–37) with "command, file, then flags in any order": `-o <out>`, `--explain-memory`, `--format json`, `--no-specialise`. Passing a `Mode` is about 30 lines.
- **`Backend`:** `compile(unit, mode: Mode = Mode.Default)` and `analyse(unit, mode)`. In `NoSpecialise` mode, `Specialise` is skipped and `Versions` builds only shared versions plus `fip` bodies and shared entries.
- **Before the baseline lands (M4):** `--no-specialise` already means "ownership budget 0" and is useful immediately.
- **After M7:** it means "baseline only".
- **Tests:** every `EmitSuite` program and every golden runs in both modes (E.1). `run.sh` takes `MODE` (E.3).

### Item 6: reference map in the header

Header proposal, which keeps 16 bytes, the payload at offset 16, and every `Field` offset:

```c
typedef struct fx_header { int64_t rc; uint16_t tag; uint16_t nslots; uint32_t refs; } fx_header;
```

- `refs` bit i set means payload slot i holds a counted reference.
- 32 slots per cell is plenty: no data type in the repository has more than 6 fields. Report `B005` above 31.
- Closures: tag `0xFFFF`; the two code slots have bit 0. The closure special case in `fx_drop` (L:107) disappears.
- Strings: `refs = 0`, as today.
- `fx_drop` iterates over the set bits (`__builtin_ctz`). `reclaim` clears `refs`.
- `fx_alloc(tag, nslots, refs)` and `fx_alloc_at(token, tag, nslots, refs)`.
- **`Layout.slots` becomes declaration order**, identical to the field index (closures +2). `boxedCount` becomes `refMask(fields)`.
- `EmitLLVM`:
  - `Switch` loads an `i16` tag;
  - static headers become `<{i64 -1, i16 tag, i16 0, i32 0}>`;
  - `Con` / `Reuse` pass a constant mask, or a computed one OR'ed from `WitnessKind` for parameter-typed slots in a baseline;
  - fields of a declared type that is a type parameter of the data type are stored and loaded as `i64` canonical slots. Fields of known scalar types can stay at native width, which keeps the clone and baseline layouts identical.
- `Demands` part keys and `Versions.construct` use the new slot numbering. `examples/golden/01_basics.memory.json` must be regenerated (`FIXED_REGEN=1`). Expect part keys such as `"List of u64#0"` to change meaning.
- The PR3.1 text (`memory_management.md` L:301–311) keeps `rc: i64, tag: i8`; this proposal uses `tag: u16`. The spec allows an implementation-defined map encoding (L:311) but fixes the tag width at `i8`. Either widen the spec's tag or pack `tag: u8, pad: u8`.

### Item 7: `--explain-memory`

- `MemoryReport.model` (L:33–83) gains per function:
  - `"baseline_size": n`;
  - `"clones": [{key, removed, weight, score, charge}]`;
  - `"candidates": [{key, removed, weight, score, reason}]`, with reason ∈ {budget exhausted, larger than B, removed = 0}.
- `Analysed` (`Backend.scala` L:15–21) carries a `SpecialisationTable`.
- The text and JSON renderers are generic already.
- The golden memory JSON changes. Regenerate it in the same milestone and review the diff by eye.

---

## C. Milestones

Each milestone must end with `sbt check test` green, every golden stdout unchanged, and every bench program running with an unchanged result line. The golden memory report (`01_basics.memory.json`) is *expected* to change in M1, M2 and M8; each regeneration is reviewed in the diff.

| # | Scope | Files | Tests to add | What could go wrong | Size (lines changed) |
|---|---|---|---|---|---|
| **4.1.M0** | Make "unchanged" checkable: commit the golden `.out` files (`!examples/golden/*.out` in `.gitignore`); add `bench/runtime/<p>.expected` with each program's result line; a `BenchRunSuite` that ships every bench program and compares (sizes as committed, about 2 s total at `-O2`, or tag it slow) | `.gitignore`, `examples/golden/*.out`, `bench/runtime/*.expected`, `EmitSuite.scala` (built as benchmark tests in `EmitSuite` instead of a separate suite) | the suite itself | Golden content must come from the current compiler; regenerate once from `5a0eedd` and review | 60 + data |
| **4.1.M1** | Shared version primary, budget instead of cap 4 (ownership axis only); no copy past the budget; `fip` shared entry `f#shared`; `Fn.version` metadata replaces the name regexes; `Lower.depth` side table; report baseline size, clones and candidates for the ownership axis | `Versions.scala`, `FipCheck.scala`, `Memory.scala` (`base`), `MemoryReport.scala`, `Backend.scala`, `Core.scala` (`Fn` field, `Closure` primary entry), `EmitLLVM.scala` (closure pointer order), `Lower.scala` (depth only) | `VersionSuite`: rename and invert expectations; "past the budget calls the shared version, no copy"; "`removed = 0` builds nothing"; "charges ≤ B". `EmitSuite cap_copy` keeps its output but asserts no `.copy[` call; `fip_copy` asserts the copy sits in `bump#shared`. Regenerate the memory JSON | The "local `removed`" rule kills ownership propagation through wrappers (`pass(xs) = bump(xs)`, `map`'s step closures): bench `list_pipeline` and `records` lose reuse. Count call sites that select an exclusive callee version as removed (F.7). Watch the `reuses` counter in the bench run | 250 |
| **4.1.M2** | Reference map header and declaration-order layout, still monomorphic; canonical 64-bit stores for fields whose declared type is a data type parameter | `fixed_rt.c`, `Core.scala` (`Layout`), `EmitLLVM.scala`, `Lower.scala` and `Generated.scala` (`Layout.slots` callers keep working; check `slotType`), `Versions.construct` | Runtime test: a `data P of A: P(a: A, s: String)` built at `u8` and at `String`, both leak-free; a cell with an `i8` field next to a slot; regenerate the memory JSON (part keys renumber) | A missed `nptr` assumption: `reclaim`, `fx_alloc_at`, string statics, the closure tag compare (`-1` versus `0xFFFF`). Leak checks catch most; `FX_CHECK_EXCL` runs in every `EmitSuite` run | 150 |
| **4.1.M3** | Slot calling convention for closures (Rule CM3.3(3)): closure code takes and returns `i64` slots; `CallClosure` widens and narrows. Monomorphic still | `Lower.scala` (`closure`, `lambda`, `nestedFn`), `Generated.scala` (callbacks), `EmitLLVM.scala` (`CallClosure`, closure definitions), `Core.scala` (`ToSlot` / `FromSlot`) | `EmitSuite`: closures over `u8`, `f32`, `bool` and `char` round-trip; `f64` bits survive | Float widening (bits versus `fpext`); `f32` in `records` uses `f64` only. Small perf cost per closure call (`zext` / `trunc`); measure `list_pipeline` | 120 |
| **4.1.M4** | CLI and modes: flag parsing, `Mode`, `--no-specialise` = ownership budget 0; `EmitSuite` and goldens in both modes; `run.sh MODE=` | `Main.scala`, `Backend.scala`, `EmitSuite.scala`, `bench/runtime/run.sh` | Every existing `EmitSuite` program in both modes, output and leak; a CLI test for flag order | Doubling `EmitSuite` time (about 20 clang links → 40). Acceptable; else run the both-modes loop only for goldens and bench | 120 |
| **4.1.M5** | Core witness constructs and runtime witnesses, no `Lower` change: `CType.Slot / Witness / Table`, `CallTable`, `WitnessKind`, `StaticWitness` / `StaticTable`; `Dup` / `Drop` on slots; `counted` in every analysis; emitter support; `fx_w_imm`, `fx_w_ref`, `fx_slot_dup` / `drop`; `Aborts`, `Consume`, `Memory` and `FipCheck` know `CallTable` | `Core.scala`, `EmitLLVM.scala`, `Rc.scala`, `Demands.scala`, `Versions.scala`, `Memory.scala`, `Consume.scala`, `Aborts.scala`, `FipCheck.scala`, `fixed_rt.c` | New `WitnessSuite`: hand-built Core functions (an identity on a slot with both witnesses; a table with one entry; a cell with a run-time reference map) emitted, linked and run with the leak check | Every analysis must be audited for `boxed` used to mean "counted"; a missed one leaks or double-frees (leak check, `FX_CHECK_EXCL`) | 300 |
| **4.1.M6** | Capability tables and adapters for (concrete type, capability) pairs on demand: built-in `Numeric` / `Eq` / `Ord` / `Show` tables for scalars (hidden `lt`, `le`); `String` `Show` / `Eq` / `Concat`; adapters for derived `fold` (reordered), `foldLeft`, `length`, `find`, `map`; prelude `Numeric` members per `stdlib.md` §4.9; canonical member order | new `Tables.scala`, `Generated.scala`, `prelude.fixed`, `EmitLLVM.scala` (table globals) | `WitnessSuite`: call `Numeric.add` and `Show.show` through tables at `u8` / `i64` / `f64`; a `Folding.fold` through the `List` entry; typer tests still green after the prelude change; `TyperCpuBench` A/B for the prelude growth | Adding prelude members changes typer resolution if a user type has an `add` method; check `examples/` and the typer suites | 350 |
| **4.1.M7a** | Baseline lowering behind `--no-specialise`, step 1: unbounded type parameters (`swap`, polymorphic `upto`-style helpers, list-polymorphic prelude bodies). Hidden witnesses, slots, run-time reference maps, `fx_w_ref` for composites | `Lower.scala`, `Generated.scala` (canonical names with parameters) | `EmitSuite` programs pass in both modes; a Core test that the baseline `swap` has no concrete type in any variable | Generated-member name collisions between the baseline and clones | 300 |
| **4.1.M7b** | Step 2: bounded scalar parameters (`fib_iter(n: N is Numeric)`): binary operators, literals via `from_i64`, `==` / `<` via tables, `show` | `Lower.scala` | `01_basics` golden in both modes; a test with `u8` wrap-around through a baseline `add` | Comparison results and literal widths: `u64` literal above `i64::MAX` | 250 |
| **4.1.M7c** | Step 3: anonymous bounds in parameter position (`join`, `03`'s `Result.fold`, `04`'s `is Optional` callers) as implicit type parameters; tables passed at calls; `Representation` limited to non-parameter positions | `Lower.scala`, `Representation.scala` | `03` and `04` goldens in both modes | Ambiguous bounds (`Folding`) that are resolved today by `bounds`; table pairs with no definition (`Maybe`'s `Folding`) must not be built | 300 |
| **4.1.M7d** | Step 4: caller-chosen results (Rule CM4.5) for `05`'s product capabilities; phantom parameters get no witness | `Lower.scala`, `Representation.scala` | `05` golden in both modes | Phantom detection without a typer phantom table | 200 |
| **4.1.M8** | Specialiser on the type axis and one budget for both axes; the default pipeline becomes baseline + specialiser; complete `--explain-memory` report | new `Specialise.scala`, `Versions.scala`, `Backend.scala`, `MemoryReport.scala`, `Lower.scala` (clone entry point) | `SpecialiseSuite`: a hot fold-nested call outranks a cold one; budget exhaustion is reported; `removed = 0` is never built; ties by key text; every golden and bench program in both modes; bench report in both modes | Clone-equals-baseline bugs (E.2); bench regressions where the old monomorphic code was better (any function above B = 512 Core lets now runs through witnesses); compile time | 450 |
| **4.1.M9** | Cleanup: delete the `bounds` specialisation path and the old monomorphic-only entry, name-substring detection in `Memory` / `FipCheck` (use `Fn` metadata), remaining `ponytail:` marks the change makes stale; record `docs/baselines/runtime-<date>.md` in both modes; write the plan notes | `Lower.scala`, `Memory.scala`, `FipCheck.scala`, docs | Existing suites | Removing a path some test still reaches | −200 / +100 |

**Total:** about 3,000 lines changed, roughly 6–8 weeks for one contributor at the Phase 3 / 4.0 calibration of one to two weeks per milestone. M7 is the largest risk to the estimate.

---

## D. Parallel versus sequential work

**Hot files, and the milestones that touch them:**
- `Lower.scala`: M1 (depth only), M3, M7a–d, M8, M9.
- `EmitLLVM.scala`: M1, M2, M3, M5, M6.
- `Core.scala`: M1, M2, M3, M5.
- `Versions.scala`: M1, M2 (one line), M5, M8.

**Can run in parallel** (no shared file beyond trivial lines):
- **M0** first: one day, unblocks everything.
- Then, side by side:
  - **M1** (`Versions`, `FipCheck`, `Memory.base`, `MemoryReport`);
  - **M2** (runtime, `Layout`, `EmitLLVM` header code);
  - **M4** (`Main`, `Backend` signature, test harness, `run.sh`).
  - M1 and M2 both touch `EmitLLVM` (closure pointer order versus header literals) and `Versions.construct` (one line). Merge the second one by hand; the conflicts are small.
- **M6's runtime and table-emission parts** can start on a branch as soon as M5's `Core` types are merged. Land those types first as a 50-line "M5a: Core types only" so M5 and M6 proceed in parallel.

**Must be sequential:**
- M2 before M3: same emitter code, and slot stores depend on the declaration-order layout.
- M3 and M5 before M7a: baseline closures and witnesses.
- M6 before M7b: tables.
- M7a → M7b → M7c → M7d: all in `Lower`, each building on the previous.
- M7 and M1 before M8: one budget for both axes.
- M8 before M9.

**Maximum width:** two to three engineers until M5. After that the spine through `Lower` is one engineer. A second can own `Tables.scala` (M6) and the test and bench harness (E) in parallel.

---

## E. Test strategy

### E.1 Run every golden and bench program in both modes

- `EmitSuite.check` (L:34–40) gains a `modes = List(Mode.Default, Mode.NoSpecialise)` loop.
- Each mode must give the same stdout as the golden, and allocations == frees.
- `FX_CHECK_EXCL` stays on in both modes (`Backend.ship(checks = true)`).
- The benchmark tests in `EmitSuite` (M0) do the same for the five bench programs against `bench/runtime/<p>.expected`.
- Before M7, both modes still lower monomorphically. The test then checks the *ownership* axis (M1/M4).
- After M7, it checks Rule CM5.2 for the type axis on every program the repository runs.

### E.2 Core-level tests

- **Baseline shape.**
  - For `swap`, `fib_iter`, `join` and the generated `List of A.fold`: in `NoSpecialise` mode the function exists under its bare name, and no variable of it has a concrete type where the source has a parameter.
  - No function name contains `[` except generated members, which carry their data type.
- **Clone = substitution.** Rule CM5.1 allows re-lowering only "provided the result is what substitution would give". Differential execution gives evidence for that; a cheap structural check strengthens it:
  - a test-only `Substitute` pass takes the baseline Core of `f` and a key, replaces `Slot(w)` by the concrete `CType`, `StaticWitness` / `StaticTable` by constants, `CallTable` on a static table by a direct `Call` of the entry's target, and `FromSlot ∘ ToSlot` by the identity;
  - it renames variables canonically and compares the result with the re-lowered clone `f[key]`;
  - run it over every clone built for the goldens and bench programs;
  - expect noise: re-lowering takes different but equivalent paths, such as `Prim` versus an inlined table entry. Normalise `CallTable(builtin add)` to `Prim(Add)` in `Substitute`. If the noise stays high, keep this test only for a fixed list of small functions and rely on E.1.
- **Budget and score** (`SpecialiseSuite`):
  - synthetic programs where `f` is requested at depth 0 from one site and at depth 1 from another: the depth-1 key is built first;
  - a function with baseline size > 512 gets no clone, reason "larger than B";
  - two keys of equal score are ordered by key text;
  - charges never exceed B.
- **`fip`:** a polymorphic `fip fn` that drops a value of parameter type is rejected with Rule MM18.1(2); the shared entry contains the copy, and the caller does not.

### E.3 Measuring with `bench/runtime/run.sh`

- Add `MODE` (`default` | `no-specialise`) and `B` (budget override) environment variables.
- The `sbt` command becomes `compiler/run ship ${MODE_FLAG} bench/runtime/$p.fixed -o $out/$p-$MODE`, with `MODE_FLAG=--no-specialise` when set. `B` is passed as `-Dfixed.B=$B` through `JAVA_TOOL_OPTIONS`, or as a `--budget` flag if that is preferred to a system property.
- The report name gets the mode: `docs/baselines/runtime-<date>-<mode>.md`.
- Add a column comparing the result line with `bench/runtime/<p>.expected`. Fail the script on a mismatch.
- A one-line `bench/runtime/compare.sh a.md b.md` prints the per-program ratio of wall time, allocations and RC operations. That is the "performance difference" the spec's Rule CM5.7 implies.
- Record three runs at 4.1.M8: `default`, `no-specialise`, and `default` with `B=0`. The last should equal `no-specialise`; that is a free consistency check.

---
