# P0 — Spec review before the compilation-model change (2026-11-20)

**Status:** Record. Lists what a full read of the specs and plans found when decisions P1–P5 were applied: text the decisions contradict, gaps they open, and contradictions that were already there. Each item says what was done. Line numbers refer to `main` at `5a0eedd`.

**Method.** Every file under `spec/` and `docs/plans/`, the README and the existing decision records were read start to end against the decision list (four independent readers, then the author of this record). Items marked **fixed** were edited in the same change. Items marked **open** were not, and are for the owner of the spec to decide.

## 1. Contradictions between the decisions and the existing text

| # | Where | Existing text | Decision | Resolution |
|---|---|---|---|---|
| 1 | `type_system.md` §8.4 (:1032); `implementation-plan.md` :631, :769; `phase-3-typer.md` :60, :966, :1033 | "There is **no global coherence requirement**." | P1: one satisfaction per (type, capability) per link unit | **fixed.** Visibility stays scoped by `use`; uniqueness is per link unit (`compilation_model.md` Rule CM6.6). |
| 2 | `memory_management.md` Rule MM18.3; `quantities.md` §7.5 (:381); `phase-3.4-qtt.md` :209 | "A `fip` function has no shared version", so callers copy. | P1: every function has an entry that is always callable | **fixed.** A `fip fn` keeps one body. Its shared entry is an adapter that copies the demanded part and calls the body (Rule CM5.5). No caller copies. |
| 3 | `type_system.md` Rules 7.4.b.1, 7.4.b.2 | Fusion and step inlining are guaranteed "after monomorphisation" and are "not profile-guided optimisation". | P2: clones are optional, under a budget, with a no-clone mode | **fixed.** They are *mandatory specialisations*: outside the budget, in every build mode, when the data type is statically known at the call site (Rule CM5.5). |
| 4 | `quantities.md` §4 (:101), Rule Q9.3 (:436), OQ-Q4, OQ-Q7; `memory_management.md` :7, Rule MM5.5 (:175), Rule PR5.5 (:384), Rule MM20.2 (:711), §20.3 (:723); `phase-3.4-qtt.md` :22, :50, :93, :167, :253, :268, :274 | Demands are inferred on **monomorphic** Core, "one instance per type". At most 4 versions; beyond the cap the caller copies. | P2: polymorphic baseline; one size budget; beyond it call the baseline | **fixed** in the specs. The plans are historical records of what 3.4 built; they get a pointer to `phase-4.1` instead of a rewrite. |
| 5 | `type_system.md` §6.5 (:542, :548, :612, :635, :641); `implementation-plan.md` :558, :901 | Selection is "for each value"; later passes "see only the concrete representation"; generic code is "monomorphised at each instantiation". | P2: the representation travels by witness | **fixed.** Selection happens at the site where a value originates; the bound set is closed over the package plus imported signatures. |
| 6 | `memory_management.md` Rule MM7.4 (:272–276), Rule MM7.3 (:252) | Witnesses exist only where the class is unknown; "large functions get one witness-taking version". | P2 | **fixed.** Witnesses are the default; they disappear only in clones. The large-function rule is the budget. |
| 7 | `memory_management.md` Rule MM5.2(a) (:134), Rule MM5.5 (:173), Rule PR5.5 (:386) | The **caller** evacuates when the interface says a parameter escapes. | P1: at the floor entry the callee evacuates | **fixed.** The floor entry evacuates in the callee through the witness. Exported versions may still rely on recorded escape facts. |
| 8 | `effects.md` §7.6 (:452) | "Monomorphisation of effect-polymorphic functions specialises these evidence-vector arguments per call site." | P2 | **fixed.** Evidence is passed in the baseline; a known handler is a fourth component of a clone key, charged to the same budget. |
| 9 | `effects.md` §7.1 (:411) | Evidence entries carry "type information sufficient for boxing args / return value". | P2: uniform 64-bit slot | **fixed.** Arguments and results are slots. |
| 10 | `type_system.md` Rule 8.2 (:996, :1006) | An `impossible` call compiles to a panic "at that call site"; a static check would need "whole-program analysis". | P1, P2 | **fixed.** The panic lives in the capability table entry. The rationale now says a link unit is a closed world for satisfactions, and leaves the static check as an option. |
| 11 | `type_system.md` Rule 6.5.4 (:601) | "PGO data may override per-call-site (within the per-call instantiation)." | P2 | **fixed.** Restated per origination site. Profile data also feeds clone weights (Rule CM5.4). |
| 12 | `type_system.md` Rule 7.3.b | `of` value parameters are "materialised as compile-time constants at access sites". | P2 | **fixed.** True in a clone. A baseline version that reads one receives it as a hidden argument (Rule CM4.4). Missed in the debate. |
| 13 | `stdlib.md` §4.11 (:240) | A caller with a shared value may "copy the demanded part itself". | P2 | **fixed.** |
| 14 | `quantities.md` Rule Q4.1 (:150) | "a function states a demand only through the `fip` contract". | P3: cost contracts | **fixed.** Cost props join `fip`. |
| 15 | `phase-3.4-qtt.md` :41; `phase-3-typer.md` :37, :982; `phase-4.0-backend-slice.md` :38 | Signature facts "live in the unit"; TASTy-equivalent serialisation and separate compilation deferred. | P4 | **fixed** by pointing at the interface and body tiers. |
| 16 | `implementation-plan.md` :743, :891, :907, :917–923; `phase-3-typer.md` :880 | "Vtable / monomorphized"; a Monomorphizer deliverable; LLVM struct layouts per type. | P2 | **fixed** in `implementation-plan.md`; Phase 4.1 added. |

## 2. Gaps the decisions opened, and how they were closed

1. **Who chooses the representation of a result typed `is C`.** The text was inconsistent: Rule 4.1 and §5.1 read as universal (caller instantiates), §8.5 resolves in the callee, Rule 8.1.a and §6.5.6 leave the call abstract until selection. Closed by `compilation_model.md` Rule CM4.5: *caller-chosen* when every returned value comes from an abstract constructor-requirement call, *callee-chosen* otherwise, and the choice is a signature fact. This was not debated.
2. **Demand of a capability method requirement.** A requirement has no body. Closed by Rule CM4.6: a fixed convention for calls through a table (`own` everywhere on the shared pointer; whole-argument exclusivity on the exclusive pointer). **Cost:** a call through a table never borrows. Whether a requirement may state `read` is left open (OQ-CM8).
3. **`fip` on polymorphic code.** Closed by Rule CM4.7: a witness `dup` / `drop` / `reuse_test` counts as a reference-count operation, so `fip` is checked on the baseline version and must hold for every instantiation. Clones inherit it.
4. **The witness needed a `copy` operation.** `evacuate` is the identity for heap-to-heap, so the copy of the demanded part (Rule Q9.3) could not be done through a witness. Rule MM7.3 gains `copy` and `kind`.
5. **Class is a property of a value, not of a type.** Two arguments of the same parameter type may live in different classes. Rule CM4.1 keeps Rule MM7.3's two cases and says each such owned parameter gets its own value witness.
6. **A function larger than the budget loses its exclusive version.** Its exclusive callers fall back to the runtime `rc == 1` test (`Heap`) or to no reuse (`Frame`). Stated in Rule CM5.3. `fip fn` is not affected.
7. **Default method bodies across packages.** Rule 6.1.1 Phase 2 re-checked a default body against each satisfying type, which needs the body. Now: the satisfying type's methods are checked against the requirement (effect row, cost contracts) and the default body is compiled once against the table. Re-inference of demands happens only in clones. Decision X5's two-phase rule survives in that form.
8. **Evidence is not a witness.** Evidence is dynamically scoped and may be shadowed; a witness is static and coherent. They share a calling convention, not a mechanism (Rule CM4.3).
9. **Cost contracts need an attachment point.** The grammar puts a `prop` beside a method, not on it. Closed without a grammar change: a *cost prop* names the member (`prop get_cost: steps(get) <= O(1)`), `spec/cost.md` §4.
10. **Property-based testing cannot refute an asymptotic bound.** A bound written with `O(…)` can only be `proved` or `assumed`. A bound with explicit constants can be `tested`. `spec/cost.md` Rule CT6.2.
11. **"Latent cost like effect rows" was a bad analogy.** Rows are idempotent sets; cost is additive and scales with the number of calls. `spec/cost.md` §5 uses symbolic cost terms instead.
12. **Coverage statuses.** Statuses attach to props, not to code. `spec/reports.md` Rule RP3.2 defines the status of a region.
13. **The prop cache key was unsound for tested props.** Rule P7.4 hashed only the prop body's typed tree: a change in a function the prop calls did not invalidate a `tested` result. Now keyed on the content hash of Rule CM7.1, which is transitive.
14. **Generators for imported types.** Rule P8.1 derives a generator from a `data` type's fields; an importer of an opaque type sees none. Public `data` types expose their variants (Rule CM6.7), so derivation still works; a type whose constructors are not public needs an exported generator. Left open (`spec/properties.md` OQ-P7).
15. **Selection ties caused by a dependency.** A dependency that adds a satisfaction can create a tie (§6.5.5) in an importer on recompilation. That is a source-level change, not an ABI change; stated in Rule CM6.7.
16. **`K < ε` with derived dimensions.** A table from bound class to raw score does not keep the Lipschitz bound by itself. `spec/cost.md` Rule CT8.2 defines the aggregation so that one added bound moves a derived dimension by at most 0.6 raw points.

## 3. Contradictions that already existed (independent of the decisions)

Fixed in this change because they sit in text that was being edited:

- `quantities.md` :58 "1 Linear … enables in-place mutation (FBIP) and skips RC" against Rule Q4.1.d; :116 "dup/drop sites driven by quantity"; :114 selection consumes "Quantity context" against :102; :121 "call sites consume arguments at that quantity" against :178; :119 cites a §8.1 that does not exist; :29–:36 scope list ("linearity (§7)", quantity inference computing classes); :503 "in v0.2 of this doc"; :518–:520 cross-references marked "(TBD)".
- `memory_management.md` :36 and :822 say `fip` needs a grammar addition, :812 says it has one; :38 cites `quantities.md` v0.2 with the parallel rule "pending"; :15, :20, :332, :802 still say "quantity" where ownership is meant; :662 calls the Rule Q9.3 copy "an evacuation" against Rule MM5.5's definition.
- `memory_management.md` Rule MM7.3 (:262): `reuse_test` is false for `Region`, but Rule PR6.3 (:432) says exclusive reuse is unconditional "in any class". Fixed in favour of Rule PR6.3.
- `type_system.md` §6.5.8: the cache key omitted lifetime class although §6.5.1 makes it an input.

- `quantities.md` Rule Q4.1.b(1) makes a literal exclusively owned, and Rule PR6.3 allowed unconditional reuse "in any class". Together they allowed writing into static data. Rule PR6.3 now excepts `Static`.
- `type_system.md` Rule 6.5.4 claimed that `K < ε` keeps a clear winner from becoming a tie. It only keeps a clear winner from becoming a clear loser; the text now says so.
- `type_system.md` §3 and §1 cited a §4.5 that does not exist (now §6.5).

**Open** — not touched, listed for the owner. Marks added after decision P6 (2026-11-20) say where an item was resolved; unmarked items are still open.

- `quantities.md` :110 runs property verification before quantities; `memory_management.md` §20.3 runs it after M10 and M11. Fork site 3 (Rule Q5.13.a) needs `proved` props before it decides. **Resolved** by P6 D6: the static prover runs before quantity inference (fixed in `quantities.md` §4 and `memory_management.md` §20.3).
- `quantities.md` :343 cites `E072` for `resume`; :348 says all three obligations are `E071`. **Resolved:** fixed in `quantities.md` Rule Q7.1.
- "Recheck-style phase" (`quantities.md` :104, `memory_management.md` :733) names a typed-tree pattern but is applied to passes over Core. **Resolved:** fixed in `quantities.md` §4 and `memory_management.md`.
- `type_system.md` §8.5 (:1044) says zero matches is a type error; Rule 8.1.a (:947) and §6.5.6 keep an unresolved call abstract until selection. **Resolved** by P6 D2 (`type_system.md` §8.5, `compilation_model.md` Rule CM4.5).
- `type_system.md` Rule 4.1 lets a variant field introduce a type variable (`RGB(red: N is Numeric, …)` in a `data Color` with no `of`). Nothing says how `N` is quantified. `stdlib.md` :58–:59 has the same shape with anonymous bounds. (OQ-CM4.) **Resolved** by P6 D3: a choice point (`type_system.md` Rule 4.1, `compilation_model.md` Rule CM8.1).
- `type_system.md` :336 `or_else(default: Part) -> Part` against `stdlib.md` :103 `orElse(other: Self) -> Self`, for the same `Optional`. **Resolved:** fixed in `type_system.md` (both use `orElse(other: Self)`).
- `pattern_matching.md` :329 uses self-recursion (`count(l) + count(r)`), which functions cannot do. `pattern_matching.md` :197 and :301 use `E093` for two different errors. **Resolved:** fixed in `pattern_matching.md` v0.3 (the example folds; the or-pattern error is `E095`).
- `stdlib.md` :86–:91 gives `Maybe satisfies Optional` in the prelude; :277 says `Optional` has no canonical satisfaction. `stdlib.md` :268–:270 `bool satisfies Boolean of Self` applies `of` to a capability with no parameters and maps one non-variant to two requirements. `stdlib.md` :170 "`!=` desugars to `!.eq(other)`" is malformed. `stdlib.md` :238 lets `Clone` be either a deep copy or a count increment. **Resolved:** the first three fixed in `stdlib.md` (§3.5 and §5, canonical meaning rule 4 of P6 D5; `bool satisfies Boolean` built in; §4.7); `Clone` by P6 D10 (`stdlib.md` §4.11).
- `properties.md` :126 "Other fn bodies may carry `prop` postconditions" against §3.3; :45 "this doc resolves OQ7" against :34; the §10.3 override example does not imply its parent, so it breaks its own Rule E105; :301 lets a `proved` status rest on a merely `tested` inherited prop, against Rule P7.7.c; :146 says `forall` outside a prop is a parse error, but grammar :809 lets `prop x: forall …` parse in any block; :219 uses a `Binders` nonterminal the grammar lacks. **Resolved:** fixed in `properties.md` v0.3.0; the status rule by P6 D6 (Rule P7.7).
- `effects.md` :216 `with AppEffect(String)` does not parse; :99 makes `Fail of String` and `Fail of i64` distinct but a handler clause cannot name `of` arguments; :174 refers to a `module` construct that does not exist; :279 lists `E081` under errors and calls it a warning; Rule E5.5.a lets an effectful callback reach a pure `fold`, and nothing says how the callback gets its evidence or what happens when it outlives its handler. **Resolved** except :99: fixed in `effects.md` v0.3 (Rule E4.5.b, §4.2, `warning[E081]`); the callback by P6 D14 (Rules E7.8, E5.5.b). :99 is open as `effects.md` OQ-E10.
- `syntax_grammar.ebnf`: line 2 says v0.3, line 6 says v0.4.9; comments :46–:49 allow nested `fn` but `BlockTail` has no `FnDecl`; :877 recommends `(x) -> do:` against v0.3.1; :948 says bodies are colon-introduced; `mod` and `pub` are reserved and listed as recovery anchors but have no productions; `UseSel` is `UPPER_IDENT` only, so a function cannot be imported by name. **Resolved** except `UseSel`: fixed in `syntax_grammar.ebnf` v0.4.11 (one version number, `BlockStmt` admits `FnDecl`, the two comments corrected); `pub` has a production by P6 D13, and `mod` stays reserved without one because packages are not source syntax. `UseSel` is open as the grammar's N7.
- `README.md` :249 "(planned) Compiler implementation in Rust" (it is Scala 3); :255, :292, :306 describe Phase 0 as under way; the design-principles list is numbered 1, 2, 3, 4, 8, 6, 5, 7, 9, 10. `README.md` :25 and `implementation-plan.md` :138, :150, :628 still tie in-place update to quantity 1. **Resolved:** fixed in `README.md` and `implementation-plan.md`.
- `implementation-plan.md` :471 says "four commands" and lists three plus a combination; `verify` does not exist; :1013 says 16 example programs (there are 11); :854 says `04` and `05` are pending (done in 4.0.M6); :919 gives a layout the backend does not use. **Resolved:** fixed in `implementation-plan.md` (CLI section with `verify` marked planned, 11 examples, `04` and `05` running, the cell header as built).
- `phase-3-typer.md` :3 status omits M6, M7, M10; :28, :880, :996 speak of C-level output while the backend emits LLVM IR only; :804 against :883 on `--print`; it references four plan files that do not exist (`phase-3.3-properties.md`, `phase-3.5-repr.md`, `phase-3.x-exhaustiveness.md`, `phase-4-codegen.md`). **Resolved:** fixed in `phase-3-typer.md` (status, no C, conformance in `docs/Phase3Conformance.md`, the four files noted as not written).
- `Phase3Conformance.md` :14 says "about 4.8×", its own :94 and :121 say about 4.2×; :59 still lists quantities as not enforced.
- `phase-3.4-qtt.md` :91 names shared versions `#s`; :165 and the runtime baseline use `#n`. **Resolved:** fixed in `phase-3.4-qtt.md`.
- `memory_management.md` §22.2 shows `map` calling itself (`List.Cons(f(h), map(t, f))`) as a worked example, although functions cannot recurse; it is meant as what the derived `map` does. **Resolved:** fixed in `memory_management.md` §22.2.
- `properties.md` Rule P5.3.b says Hypothesis and Hedgehog sample across satisfying types; neither has such a feature. **Resolved:** fixed in `properties.md`.
- `effects.md` :576 and `properties.md` :162 still say prop bodies are "checked at quantity 0". **Resolved:** fixed in `effects.md` and `properties.md`.
- **The golden outputs of the examples were never committed.** `.gitignore` excludes `*.out`, so `examples/golden/*.out` exist only on the machine that produced them, and `sbt test` (hence the pre-commit gate) fails on a fresh checkout of `main`. This change regenerates them from `main`'s compiler and commits them. **Resolved:** committed in `e51ffed`.
- The typer accepts `show`, `==` and `clone` on a value whose type parameter has no bound; the monomorphising backend resolved them per instance. Under a baseline version that is a type error (`compilation_model.md` Rule CM4.3). Decided by P6 D10 (`type_system.md` Rule 5.1.3); the typer still accepts them.
- `stdlib.md` §4.9 gives `Numeric` arithmetic members; the prelude the compiler ships declares only `from_i64`. **Resolved:** fixed in the prelude (`compiler/src/main/resources/stdlib/prelude.fixed`, 4.1.M6).
- No data type in the prelude satisfies `Ordering`, so `Ord.compare -> is Ordering` returns a value with no representation. Ordering comparisons on a value of parameter type have nothing to dispatch to; Phase 4.1 uses compiler-provided comparison entries until the library settles this. Decided by P6 D17 (`stdlib.md` §3.6, `data Order`); the prelude does not have it yet.
- Several prelude satisfactions lack bodies for some members (`List satisfies Filtering`, `Json satisfies Show`), and the typer does not check that a satisfaction defines every member. Decided by P6 D17; neither the prelude nor the typer is changed yet.

## 4. Found while implementing Phase 4.1

Compiler faults that exist on `main` and were not fixed:

- A generic function that rebuilds a `data Pair of (A, B)` cell (a `swap`) fails the exclusive-reuse check in a test build; with `--no-specialise` it corrupts the heap.
  - **Fixed** (`Demands.forward`): a cell that died inside an earlier `match` or `if` was paired for reuse after it, where `Rc` had already dropped it; `swap` itself was not at fault.
- A parameter with an anonymous bound that is passed on to another function leaks.
  - **Fixed** (`Lower.call`): the callee's type parameters were solved after the caller's bound had replaced the anonymous type that holds them, so they fell back to `()`.
- Two parameters with the same anonymous bound, called at two different data types, both get the last argument's type in the instance path.
  - **Fixed**: in the backend for the parameters and every use of them by name (`Lower.call`, `function`, `typeOf`), and in the typer, which now gives each anonymous bound its own type variable (`spec/type_system.md` §5.1).
- `fx_drop` is recursive; a long list can exhaust the native stack (Rule PR7.3 requires a loop). **Fixed in part** in `fixed_rt.c` (`aff3d3c`, P6 D16): an out-of-line `free_cell` continues on the highest marked child instead of recursing, so a list is freed without recursion; it still recurses on the other children (OQ-CM17). The same change located the 6–8% that the first measurement charged to the reference-map header: it was this child walk sitting in a helper that `clang -O2` did not inline, not the map (`docs/baselines/runtime-2026-11-20-phase-4.1.md`, addendum).

Found and fixed while the faults above were being fixed (none was specific to Phase 4.1):

- A generated member of a data type nested in itself (`List of List of String`, `Tree of Tree of u64`) took the element for the rest of the structure in a clone, and returned its initial value. The default build printed a wrong result where the baseline build was right. A field is now recursive only when its declared type is the data type at its own parameters (`Generated`, and the typer's derived `fold`). The suite now runs a set of generic programs in both build modes against pinned results (`compiler/src/test/fixed/modes/`).
- A reuse token could be filled twice: the code after an `if` or `match` was analysed before its branches, so a cell a branch had reused was reused again after it. A branch that did not use the token leaked it.
- Several cases in which the default build wrote into a cell that was still referenced: a result of an `if` or `match` that is one of the values its branches use; the payload of an abortive effect operation taken as fresh; a part held by a live variable while the cell around it was taken as exclusive.
- Only 100 of the 2,000,000 reuses in `records` took the exclusive path (`Versions.construct`); see the second addendum of `docs/baselines/runtime-2026-11-20-phase-4.1.md`.
- The derived `unfold` and `map` were not tail-recursive: one native frame per element, so `upto(2000000)` overflowed an 8 MiB stack.
  - **Fixed in part** (`Generated.loopStep`, `EmitLLVM`, `Aborts`, `Consume`): derived `unfold` and `map` of linear data (a list, `Nat`) run as loops that build in reverse and then reverse in place. `EmitLLVM` emits a self call in tail position as a jump, so the bound does not depend on the optimiser. Trees, rose trees, snoc lists, the derived `fold` and the generated `length` still take one native frame per level. The default build reuses `map`'s cells after an `rc == 1` test: `Demands` treats the tail of `x` as overlapping the cell reused from `x`. Measured against the commit before (instructions; best cycles of nine runs): `nat_loop` +10%, +19%; `list_pipeline` +23%, -8%; `records` +12%, -3%; `fail_chain` -2%, -26%; `bintrees` unchanged. The second pass is what destination passing would remove (`docs/plans/implementation-plan.md`, Phase 4.2).
- Derived `unfold` through a functor field (a rose tree with `List of Rose`) crashed, and derived `map` did not map through such a field, in both build modes.
  - **Fixed** (`Generated`): one walk, decided on the declared field types, serves `fold`, `unfold` and `map`.
- The typer and the backend disagreed on a derived `fold` through a type argument that is not the last (`Pair of (Self, u64)`), and on `Functor` for non-regular data.
  - **Fixed** (`Members.foldedSelf`, `through`, `functor`, `unmapped`): the typer and the backend share one rule for where a derived member reaches `Self` or the element: the parameter itself, or the last argument of a data type whose rightmost parameter is unbounded and itself rebuildable. `Self` anywhere else (`Pair of (Self, u64)`, `u64 -> Self`) is passed unfolded. `Functor` is derived only under that rule, which is narrower than Rule 7.4.c as written: non-regular `M of (A, B)`, `Tuple of (A, u64)`, `u64 -> A` and a bounded last parameter lose it, and the diagnostic names the field. The spec text of Rules 7.4.c and 7.4.a.1 is open for the owner.
- A polymorphically recursive type such as `N of List of A` made the compiler overflow its own stack in the default mode: a clone asked for itself at an ever larger key without end.
  - **Fixed** (`Lower.call`, `TypeOps.derived`): a call whose type argument nests the callee's own type parameter calls the baseline version, or is `B005` where there is none; finite chains keep their clones. Both modes also miscompiled such a function when it did not use its type parameter: its baseline version dropped a witness that its recursive call still passed. `EmitLLVM.direct` now requires the argument count to equal the parameter count.
- The default build reused a shared cell when the result of an `if` or `match` was a value that is still live after the join, or held a part of one (`Versions.walk`, the `Ret` case): invariant I1 did not hold there.
  - **Fixed**: a branch result runs the same `Demands.exclusive` test as a call argument. Found by adversarial programs in the checked runtime mode (`FX_CHECK_EXCL`), in which every untested reuse first asserts that the cell is exclusively owned; every `EmitSuite` run uses it. Closures, abort payloads, handler results, fields of fields, polymorphic results and `satisfies` bodies held under attack (126 runs over three modes).
- Satisfactions were looked up by the name of the type, not by its symbol: a program that declared its own `data Order` picked up the prelude's `Order satisfies Ordering` and crashed the backend.
  - **Fixed** (`Symbols.satisfactionsOf`, `Namer`, `Tables.table`): satisfactions and constructor mappings are keyed by the satisfying type's symbol, with no fall-back to the name. A shadowed prelude data type is named `prelude.<name>`, so its generated members do not collide with the program's type.
- The backend did not lower the bodies of `satisfies` blocks; they were type-checked only.
  - **Fixed** (`backend/Satisfied.scala`): the bodies are lowered and serve direct calls, table entries and `==`, in both build modes. A program's body takes precedence over a generated member; the prelude's bodies fill the members for which nothing is generated. `==` on a boxed value other than `String` called the runtime's string comparison; it now calls the `Eq` body or is `B005`. A closure that captured a value of a type parameter could copy it before loading its witness, which was invalid IR.
- The backend built no derived `Show` or `Eq` for a data type. **Fixed** (`Generated.derivedShow`, `derivedEq`) at known type arguments; a program's `satisfies` body takes precedence. `Json` still cannot use them: its field `is Map of (String, Json)` has no representing data type, so lowering fails with `B005`.
- A named bound `T is C` is not checked where the function is called.
  - **Fixed** (`Members.go`, `Unifier.bind`, `Members.freshArgs`, `TyperState.tryUnify`): every variable instantiated from a bounded type parameter, named or anonymous, raises an obligation when it is solved. A bounded `of` parameter of a data type, effect or capability does too, and a trial unification fails if it breaks a bound. Examples 02 and 11 passed a `List` where `ArrayLike` / `Sized + Sorted` was required and were corrected.
- `show` and `==` on `List of T` with an unbounded `T`, and `<` on an unbounded `T`, were accepted.
  - **Fixed** (`Satisfaction.derived`, `Typer.requireBound`): a derived `Show`, `Eq` or `Ord` is decided at the instance's arguments, and `<`, `<=`, `>`, `>=` require `Ord`. Example 02's `Color` field needs `N is Numeric + Show`. The prelude gains `char satisfies Eq` and `char satisfies Ord`, which `spec/stdlib.md` does not list.
- Anonymous bounds in the parameters of capability methods, static methods and effect operations were one shared type.
  - **Fixed** (`Namer.member`, `Declarations.memberBody`). A nested `fn` and a bound inside a function-typed parameter (`f: (is C) -> u64`) keep the bound as written: the backend cannot compile a generic nested function, and nothing reports a type that the caller of a function-typed parameter leaves unpicked. An anonymous result (`-> is C`) is a structural type, not yet a choice point (P6 D2, Phase 4.2).
- `Numeric` and `Eq` members called by name on a primitive (`x.negate`, `x.add(y)`, `x.eq(y)`) failed with `B005`.
  - **Fixed** (`Lower.resolved`).
- A derived `map` through a bounded last parameter did not check the new element type. **Fixed**: `Functor` is not derived over a bounded last parameter.
- `Json` had no `Show` or `Eq` in the prelude. **Fixed**: its fields carry `Show + Eq` as `spec/stdlib.md` §3.2 says, and `Json` satisfies both again in the typer; the backend part is the missing derived `Show` and `Eq` above.
- A prelude name was resolved through the program's scope: the types `Tuple`, `List`, `String`, `Nat`, `Maybe` and `Result`, capabilities matched by name in the typer, the derivation rules and the backend, prelude functions by name, and prelude bodies typed in the program's scope. A program that declared one of those names broke typing, crashed the diagnostic formatter, silently used its own entity (`cap Show` with `u64 satisfies Show` printed `5`), or lost all the prelude's `satisfies` bodies.
  - **Fixed** (`SymbolTable.prelude`, `TyperPhase.inPrelude`, `Namer.unshadowed`, the `inPrelude`/`globals` scope in `backend/Satisfied.scala`, `QuantityPhase`): the prelude is typed, walked and lowered in its own scope. Literals, tuples, operators, compiler-provided table entries and the derivation rules name the prelude's entities. Every shadowed prelude declaration is renamed `prelude.<name>`. A program's own `cap Show` or `cap Functor` is not derived (example 06 now uses the prelude's `Functor`). In the program, a member of a program capability is preferred over a prelude capability's member of the same name. In the prelude's code only prelude capabilities and data types are candidates (Rule 8.6). A problem in prelude code is labelled "in the prelude:" without a span, and a span past the end of the file is formatted without a position. **Decided** by the owner (P6 fourth round, "Shadowing of prelude names"), and written into `spec/type_system.md` §4.3: the readings above are the rule (scope order, syntax and derivation mean the prelude's entities, a program capability is not derived), except the preference of a program capability's member over a prelude capability's member of the same name, which the decision does not cover and stays open; a hidden prelude entity is reachable as `std.prelude.<Name>` through `use … as`, the compiler warns (`W060`), a `use` follows the same rule, and a type-level local binder named like a prelude type is `T004`. Not built: `W060`, the path and `use … as`, paths in diagnostics instead of `prelude.<name>` (`docs/plans/implementation-plan.md`, Phase 4.2, item 23).
- Bounds of `of` parameters were not checked in written types (`Tag of Box`, `with Ping of Box`, a field `Box of A` with `A` unbounded).
  - **Fixed**: `Namer` records every applied type it resolves in `SymbolTable.written`; `Satisfaction.checkWritten` reports `T010` at the written type when the unit finishes.
- Constructor resolution with no fitting candidate left the call abstract, and the unifier accepted an `is C` value wherever a concrete type was expected (`f(Ordering.gt)` with `f` taking a `Color` printed a pointer).
  - **Fixed** (`Unifier`): the expected type must satisfy `C` (`T010`). `Satisfaction.adopt` lets obligations that hold solve capability arguments before literals default, which also makes `total([1, 2, 3])` with `xs: is Folding of u64` type-check.
- Tuple patterns in `match` never matched.
  - **Fixed** (`Lower.covers`, `Lower.bindPattern`, `Patterns.exhaustive`, Rule M4.2).
- `3.add(4)` was `T005`, and `N.fromI64(...)` on a type parameter was typed `N of ?0` and refused by the backend.
  - **Fixed** (`Members.candidates`, `Members.static`, `Lower.static`).
- Two anonymous bounds printed the same in a type mismatch ("expected is Show, got is Show").
  - **Fixed** (`Typer.mismatch`): each is named by the parameter it comes from.
- A branch that returned a call to a `-> !` function failed at clang with a phi type error.
  - **Fixed** (`EmitLLVM.value`): every unit result is the zero of the type the join or return expects. A `-> !` self call stays a jump.
- A generic function that recursed at a growing data type through a table (`deep(Pair.Pair(p, p), n - 1)` with `T is Show`) overflowed the compiler's stack or exhausted its heap.
  - **Fixed** (`Atom.DynTable`, `fx_table` in the runtime, `TypeOps.derived`): the table of a growing type is built at run time from a static layout and the witnesses and tables of the type's arguments, and interned, so equal contents give one table. Derived `show` and `eq` can be built at arguments that are type parameters.
- A `satisfies` body on a primitive type was not lowered; a member with several type parameters of its own was `B005`; `Ord` through a table did not build `lt` and `le` from a user's `compare`; `Maybe` had no `Folding.fold`.
  - **Fixed** (`Satisfied.satisfier`, `directBody`, `Tables`, the prelude's `Maybe satisfies Folding`). A call of Folding's `fold` through an `is Optional` table used Optional's `fold` slot and crashed with `--no-specialise`; `Lower.tableCall` now refuses a slot of another capability's member of the same name.
- A derived `Ord` was not built.
  - **Fixed** (`Generated`): variants in declared order, then fields left to right; the result is the prelude's `Order`; a program's `satisfies Ord` body takes precedence. The comparison of the last field is a tail call, so two long lists compare in a loop.
- The generated `length` of a list took one native frame per element.
  - **Fixed** (`lengthLoop`).
- The arguments of a capability bound were not compared when the satisfying type had none (`Bytes satisfies Sized of (u8, u16)` was accepted for `is Sized of (u8, u64)`).
  - **Fixed** (`Satisfaction.withDeclared`, `reaching`): a declared satisfaction provides the arguments it writes, on its `satisfies` clause or through `extends`; one that writes none provides the receiver's.
- `3 + 2.5` and `3.add(2.5)` reached clang with an invalid constant.
  - **Fixed** (`Unifier.bind`, `applyDefaults`): an integer literal that meets a float literal takes its type. A float literal at an integer primitive is `T002`; an integer instance of a generic float literal is `B005`. A mismatch prints an unsolved literal as its default.
- The typer rejected `x.compare(y)` on a data value (`T001`).
  - **Fixed** (`Members.autoDerived` gains `Ord`). `eq` by name on a data value or `String`, and `compare` by name on scalars and unit, now lower (`Lower.resolved`). `lt` and `le` are not members of `Ord` and stay `T001`.
- In a program with its own `cap Show`, `T022` asked for the bound `prelude.Show`, which a program cannot write.
  - **Fixed** (`Members.lackedBound`).
- Spans carried no file: a diagnostic in prelude code was shown at a wrong position or crashed the formatter.
  - **Fixed** (`Span.file`, set for library files): prelude diagnostics and memory-report positions print the prelude's file, line and column.
- `Console`, the runtime's effects and nested functions were resolved by name; a program's own `effect Console` was lowered to the runtime's.
  - **Fixed** (`SymbolTable.modules`, `Resolution.NestedFn`).
- Integer literals were not range-checked (`256` as a `u8` was 0).
  - **Fixed** (`Typer.literalRanges`, new code `T023`), for every integer type of 8 to 64 bits; the most negative value of a signed type stays valid.
- With `--no-specialise`, a `satisfies` body that called a generic function at its own data type crashed the compiler.
  - **Fixed** (`Lower.code`): a function's real signature is recorded before its body is lowered.
- A `handle` with no `return` clause around a `-> !` subject failed at clang.
  - **Fixed** (`Typer.typedHandle`, `Unifier.diverging`, `EmitLLVM.argument`): the handle takes the type its clauses give; a never-typed value in dead code is passed as the zero of the parameter's type.
- The backend lowered no literal pattern.
  - **Fixed** (`backend/Cases.scala`): integers, `bool`, `char` and `String`, also nested in tuple and variant patterns, tried in order. A variant pattern that holds a literal no longer counts as covering its variant (`T020`); before, such a match compiled and crashed.
- Strings had no order, and `compare` had no table entry for scalars and `String`.
  - **Fixed** (`fx_str_cmp` in the runtime, `Tables`): bytewise, unsigned, a prefix first. The `String` `eq` table entry returned `ptr` where the runtime returns `bool`, which read unequal strings as equal with `--no-specialise`; fixed with it.
- A `satisfies` body of a data type with a bounded parameter did not take the parameter's table (invalid IR in a static table, `B005` through a run-time table).
  - **Fixed** (`Tables.bodyHidden`).
- Tuples of three or more elements were lowered as pairs (`(1, 2, 3) == (1, 2, 4)` was true), and `(T, T, T)` gave invalid IR.
  - **Fixed** (`Lower.fieldTypes`, `typeParamsIn`).
- With `--no-specialise`, `x + -9223372036854775808` at a float instance applied `fromI64` before negating.
  - **Fixed**: a minus applied to a nonzero integer literal is lowered as one literal.
- The derived `fold` of linear data took one native frame per element.
  - **Fixed** (`Generated.foldDown`, `foldUp`; owner's decision, Rule 7.4.b.3): two loops, reversing in place where the value is exclusively owned. A shared value is copied from its first shared cell on, one allocation per cell, and its stack stays bounded. Measured on `nat_loop`: +8.1% instructions, -22.7% best cycles; the other four benchmarks are unchanged.

Found and not fixed:

- A type that refers to itself at other arguments gets only baseline versions of its generated members.
- The result of a closure call is always taken as shared. Measured: 4.0 instructions per tested reuse, at most 0.30% on `records`. Left as it is.
- The stand-in representation rule cannot see what a function returns: `fn l() -> is Show = [1, 2]` miscompiles.
- The type of a list literal is not specified. The typer gives it `List`; examples 02 and 11 assumed a collection that the compiler picks. **Decided** by the owner (P6, fourth round, decision 2): a list literal is a choice point bounded by `Sequencing of E` and what its context requires, bound at link like every other choice point, and the ascription `[1, 2, 3] is List of u8` fixes its type (`spec/type_system.md` Rules 5.10, 5.11; grammar v0.4.14). Not built (`docs/plans/implementation-plan.md`, Phase 4.2, item 19); examples 02 and 11 keep their stopgap until it is.
- Signed `MIN / -1` and `MIN % -1` emit a bare `sdiv`/`srem`, which is undefined behaviour, and `+`, `-`, `*` wrap. **Decided** (P6 fourth round): overflow and division by zero are the abortive effects `Overflow` and `DivideByZero`, removed where the prover or a refinement shows the range (`spec/effects.md` §3.6). Not built (`docs/plans/implementation-plan.md`, Phase 4.2, item 22). Division by zero itself is checked today and exits with status `101`.
- `Json` cannot use the derived `show` and `==`: its field `is Map of (String, Json)` has no representing data type, so lowering fails with `B005`.
- `i128` and `u128` do not satisfy `Numeric`, though `spec/stdlib.md` §4.9 says they do. The backend has no 128-bit integers: `Lower.scalar` stops at 64 bits, the runtime formats only 64-bit integers, and there is no boxed integer. Blocked on OQ-CM7.
- An abstract value of another origin (a `-> is C` result, a field `x: is Show`, a parameter typed with a bare capability `C`) still reaches a concrete type that has `C` and runs with a wrong value: Rule 5.1.2 is not enforced.
- Trees, rose trees and snoc lists still take one native frame per level in every derived member.
- A program's `satisfies` body for a member that the compiler provides on a scalar (`i64 satisfies Eq: fn eq = …`) is ignored, in both modes. **Decided** (Rule 8.4.c): such a satisfaction must be `named` and is selected by `using`; not built.
- Tables built at run time for growing types are interned and kept until exit, where Rule CM4.2 says a stack record per frame: a closure can capture the table and outlive the frame.
- A hint can name `prelude.X`, which a program cannot write, when the program's own `X` lacks the member, for `==` and `<` with a program `Eq` or `Ord`, and on a concrete receiver over a parameter. `spec/type_system.md` Rule 4.3.f now requires the path `std.prelude.X` and a `use … as` hint; not built.
- The type variables of a `satisfies` clause are fresh parameters, not the data type's; the typer maps them by name when it compares arguments.
- Escape reasons in `--explain-memory` print raw character offsets.
- A problem with no span in prelude code prints at the program's 1:1 with the prefix "in the prelude:".
- `bool` does not satisfy `Ord` in the typer, though `Declarations.provided` says every primitive does.
- A negated literal at a mismatched type gets two `T002`s; lambda-parameter variables print as `?N` in mismatches.
- Float literal patterns are `B005` (`spec/pattern_matching.md` §3.3 asks for bitwise equality; the backend has no bitcast), and so is a literal in a `let` pattern.
- `spec/stdlib.md` fixes no order for `String`; the runtime orders bytewise. Open for the owner.
- A program's `satisfies Ord` body on a scalar defines `compare`, but `<` and the table's `lt` and `le` stay built in, so they can disagree. Open, with the other built-in scalar members.
- The typer accepts a tuple of three or more elements where `Pair of (A, B)` is required, and the program crashes; `Members.candidates` resolves `Tuple`'s two-field members on tuples of any size. A run-time table, or a generated member's baseline, at a tuple of other than two elements is `B005`.
- Exhaustiveness is more conservative than §6.1 of `spec/pattern_matching.md`: `W(n, true)` / `W(n, false)` is `T020`.
- An out-of-range literal that reaches its type through a type parameter (`order(18446744073709551615, 1)` with `T is Ord`) defaults to `i64` and wraps.
- `Box of (A is Eq)` reached from `T is Ord` through a growing type is `B005` ("no table of `Eq`").
- The scanner reads the escape `\0` as a space.
- The compiler accepts a function that calls itself, which the spec makes a compile error (totality); test programs rely on it. **Decided** (Rule 7.4.h, `T027`): enforce, with a diagnostic that shows the rewrite; `para` is specified and not built, and the check depends on it. In progress.

Limits and gaps of the first implementation, against the spec:

- A cell can hold references only in its first 32 payload slots (30 captures for a closure); beyond that the compiler reports `B005`. Kept as the rule by P6 D16 (`memory_management.md` Rule PR3.1).
- One reference witness serves every reference type, so the witness operation `copy` cannot be implemented (the header holds neither size nor field types). It needs the per-type witness the spec describes. **Resolved** in the spec by P6 D7 and D11: the witness family is fixed and `copy` reads size and field kinds from the link-time type table (`compilation_model.md` Rule CM8.8); not built.
- A wrong integer extension when a narrow scalar enters a slot is invisible at run time while every reader narrows again; only tests on the emitted IR catch it.
- The typer has no phantom inference; the backend decides phantom-ness from the lowered code.
- A result is treated as caller-chosen only where every returned value already has the result's slot form; functions polymorphic only in their result are compiled as instances. The rule this was measured against is replaced by P6 D2 (`compilation_model.md` Rule CM4.5); the compiler still implements the old split.
- See `spec/compilation_model.md` OQ-CM10 to OQ-CM12. **Resolved:** closed by P6 D15.

## 5. Corrections to statements made during the debate

- "Amortised bounds are sound at quantity 1" should have been "where the value is exclusively owned" (Rule Q4.1.d).
- "The README says the target is C" was wrong: `README.md` :28 says LLVM IR. The C references are in `phase-3-typer.md`.
- "Verification of contracts comes late, after Perceus" was wrong: demands are inferred on Core before representation selection.
- The count of versions per function was first given as `3^k × 2`. Only `exclusive` parts give the caller a choice, so the ownership axis is `2^e`.
