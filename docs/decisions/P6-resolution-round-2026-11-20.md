# P6 — Resolution round: late binding at link, layout, satisfactions, visibility

**Status:** Decided 2026-11-20 (second design debate with the project owner, after Phase 4.1 was measured). To be written into the specs listed under "Affects". Where a detail was chosen by the author of this record and not debated, it is marked *(author's choice)*.
**Affects:** `spec/compilation_model.md`, `spec/type_system.md`, `spec/memory_management.md`, `spec/quantities.md`, `spec/effects.md`, `spec/properties.md`, `spec/cost.md`, `spec/reports.md`, `spec/stdlib.md`, `spec/syntax_grammar.ebnf`, new `spec/diagnostics.md`, `docs/plans/implementation-plan.md`.
**Supersedes:** in P1, "one satisfaction per (type, capability) per link unit"; in P0 §2 item 1 and `spec/compilation_model.md` Rule CM4.5, the inferred caller-chosen / callee-chosen split; Rule CM4.6's `own`-everywhere convention; `spec/type_system.md` Rule 6.5.5 (halt on a tie).

## The change of direction

P1 put every decision at the package edge. Two of the questions below showed that this cannot work for representation: the package that declares an abstract type cannot know how the rest of the program uses it. Whole-program compilation was the owner's first answer and is rejected, because it does not scale.

The resolution: **decide late, compile early.** A package is compiled once, to code that works for every choice. The choice itself is made when the application is linked, from summaries, and bound by the linker. Nothing is recompiled when a choice changes; in a release build the specialiser then clones hot code for the choices made.

## Decisions

### D1. Three binding times for an abstract type

1. **Per use: a named type parameter.** `x: T is C`, `-> R is C`, `data D of T`. The user of the declaration picks; the witness is a hidden parameter.
2. **Per link unit: a choice point.** An anonymous bound (`-> is C`, a field `xs: is Sequencing of Json`), a type variable local to a `data` field (`red: N is Numeric`), and an abstract constructor call whose type is not a named parameter of the caller. One choice for the whole program, made at application link.
3. **Per package: a choice point whose values never leave the package** (escape facts). Decided when the package is compiled. This is an optimisation of 2 and is not visible in the language.

There is no binding per value: no cell stores a witness.

### D2. Who picks the representation of a result (replaces Rule CM4.5)

The signature decides. The body cannot change the calling convention.

- `-> is C` (anonymous): the result is a choice point owned by the function. The caller treats the value as opaque and receives its witness with it.
- `-> R is C` (named, `R` in no parameter type): `R` is an ordinary type parameter. The caller passes its witness. A body that returns a concrete data type where `R` is expected is a type error; an abstract constructor call for `R` stays abstract and resolves at the caller.
- A requirement whose result is `-> is C` (`compare -> is Ordering`) follows the first case.

This also closes `spec/type_system.md` §8.5 against Rule 8.1.a: an abstract constructor for an anonymous result is a choice point (it never is "zero matches" as long as the link unit has a satisfier); for a named `R` it stays abstract.

### D3. Type variables in `data` fields

A type variable introduced in a variant field without an `of` parameter, and an anonymous bound in a field, is a choice point (D1.2): one choice for all values of the data type. A user who wants to choose writes `data Color of N is Numeric`. Closes OQ-T2 and OQ-CM4.

### D4. Binding at application link

- "Late" means application link. Load time (shared libraries, plug-ins) is out of scope.
- The object code of a package refers to the witness operations and table entries of a choice point as undefined symbols. The link step binds them to the chosen satisfaction. No hidden parameter, no indirect call, no recompilation of the package.
- **Summaries.** The interface tier of a package carries, per choice point, a usage vector (which capability methods are called on its values, with what weight), and per function parameter the same vector plus the edges "passed on to `g`, parameter `i`". The link step adds vectors along the edges and runs the selector. It reads no bodies. The model is ThinLTO's: a whole-program decision without whole-program compilation.
- **Lock file.** The link step writes the choice made at each choice point to a lock file that is checked in. Builds are reproducible from it. The application may pin a choice in it. A choice that changes shows as a diff.
- **Clones at link.** In a release build the specialiser builds clones for the chosen types from the shipped polymorphic Core, under the budget of Rule CM5.3, cached by (body hash, clone key). A development build binds symbols only.
- **Viability at link.** A candidate that does not meet the cost props of the capability's requirements is not viable. Zero viable candidates is a link error.

### D5. Selection never halts on a tie (replaces Rule 6.5.5)

Disambiguation, first rule that decides:

1. a pin written by the user in the lock file, if the candidate is viable;
2. the choice recorded in the lock file, while its score is within ε of the best;
3. the highest score;
4. the satisfaction declared in the capability's own package;
5. declaration order, then qualified name.

"Canonical satisfaction" means rule 4 and nothing more. The lock file gives hysteresis, so a clear winner that becomes a tie neither halts the build nor flips the choice. The only error is zero viable candidates.

This applies to candidates of *different* data types. Two satisfactions of the same (type, capability) are never ranked (D12).

### D6. Measurement: two lanes

Property verification is three things, placed where their inputs exist: the **static prover** (typed tree, right after the typer), the **cost checker** (Core), and **property-based testing** (a separate test build).

- **Facts lane.** A decision that is wrong if a claim is false (reuse justified by a prop, discharge of `unreachable`, a parallel fork site) takes only `proved` and `assumed` props. A `tested` prop never enters. A prop's status is the weakest status of everything it rests on (fixes `spec/properties.md` §7 against Rule P7.7.c).
- **Measurement lane.** A decision among alternatives that are all correct (candidate selection, clone weights, the budget, fork granularity) takes anything measured while code runs.

What the test driver measures, since it already runs each prop across the satisfying types (Rule P5.3.b):

- supply side: `steps`, `constructs`, `releases`, cycles, cache misses and branch misses per (capability method, satisfaction, size class, target), and machine facts (cache sizes, line size, page size, allocator granularity);
- demand side: region and request counters (`spec/reports.md` Rule RP3.3).

The selector's score is usage × cost, both measured where data exists; the bound-class table and the static weights are the fallback. Measurements go to a profile file per target. Demand-side priority: a production profile, then test-run usage, then static weights. Supply side: a package may ship a reference cost table in its interface, and the application may re-measure on its own target. Continuous integration builds from the lock file and does not re-measure.

Pipeline:

- *Package compile:* typer; static prover; quantity inference; lowering to polymorphic Core; demand and memory inference; cost checker; selection of choice points that do not escape; baseline code, interface tier, body tier, summaries.
- *Application link:* selection of the remaining choice points; accessors (D8); clones (release).
- *Test build:* property-based testing and measurement.

### D7. Layout: travel form and rest form

The owner rejected "public data types have a fixed layout of 64-bit slots". What was agreed:

- **Travel form.** A value travels (registers, parameters, results, closure arguments) in a 64-bit slot. Unchanged.
- **Rest form.** How a value is stored inside a flat container is separate. The value witness gains `width` (1, 2, 4 or 8 bytes). A flat container stores each element at that width; the stride comes from the witness in baseline code and is a constant in a clone. Widening on load and narrowing on store are Rule CM3.3's.
- **Immediates.** A data type instance that holds no reference and fits in 64 bits travels inside the slot: no cell, no header, no count. This holds for **every** data type, parameterised or not. For a parameterised type the rule is a pure function of the witnesses of its arguments, so code that knows the type but not its arguments computes the layout from the witnesses it already receives. Clones and baseline apply the same rule and therefore agree.
- **The value witness family is fixed.** An immediate needs no `dup`, `drop` or `copy`, so the value witness of any instance is one of `imm1`, `imm2`, `imm4`, `imm8`, `ref`. Generic code picks one; it never builds a value witness at run time.
- **Density by selection.** A dense sequence is a satisfier (a packed array) that the selector picks at link when usage favours it. The compiler does not repack the cells of another type.

*(Author's choice, to be confirmed when written into the spec.)* The immediate rule: a data type instance is an immediate iff no field of any variant is a reference and `tag + max over variants of (sum of field widths)` is at most 8 bytes, where the tag takes one byte when the type has more than one variant and none otherwise. The tag sits in the low byte, fields follow in declaration order, unused high bits are zero, and the width of the instance is its size rounded up to 1, 2, 4 or 8. A type with a field that is always a reference (every recursive type) is statically a cell.

Accepted as lost until a program shows it hurts: packing narrow fields inside a value that must be a cell (the allocator's rounding absorbs most of it), and immediates wider than 64 bits (OQ-CM7). An 8-byte header (32-bit count, as in Koka and Lean) is a hypothesis to measure, not a decision.

### D8. Accessors for layouts not known at package compile

A package that constructs or reads a data type whose layout depends on a choice point, or a parameterised type that could be an immediate, does so through per-type entry points (`Color.RGB.make`, `Color.red`; `Pair.first(p, wA, wB)`). The link step generates the first kind once the choice is made; the second kind is generated with the type and computes from the witnesses. A baseline call costs one direct call per construction or field read, accepted as a cost of development builds; a clone inlines it. A type whose layout is statically known (all fields known, or statically a cell) keeps direct field access.

### D9. Calls through a capability table (replaces Rule CM4.6, closes OQ-CM8)

The convention of a requirement is derived from its signature, not from any body:

- a parameter is `own` iff a value of the parameter's type can become part of the result, and `read` otherwise; for this test a value of type `Self` contains values of its capability's `of` parameters, a type variable contains only itself, and a function type is contained in nothing but a function type or a data type with a function field (corrected after the consistency review; the earlier wording, "a type variable of its type occurs in the result type", made `push`'s `x` `read`);
- the exclusive pointer of a table entry requires exclusive ownership only of the `own` parameters whose type is `Self`.

So `size(self) -> u64` and `compare(self, other) -> is Ordering` borrow; `push(self, x: Part) -> Self` owns both and updates `self` in place through the exclusive pointer while `x` stays shared; `get(self, i) -> Part` borrows `self` and `dup`s the part; `fold(init: R, f) -> R` borrows `self` and `f` and owns `init`. The rule is stated once, in `spec/compilation_model.md` Rule CM4.6. A satisfier that must keep a `read` argument `dup`s it; one that ignores an `own` argument drops it. Before the convention is frozen, a benchmark with capability-bounded parameters measures it against `own` everywhere.

### D10. `show`, `==` and `clone` need a bound

`show`, `==` and `clone` on a value of an unbounded type parameter are type errors: write `T is Show`, `T is Eq`, `T is Clone`. `Show` stays a lawful capability and is not the diagnostic mechanism. `clone` returns an equal value; the satisfier chooses whether it shares or copies, and a cost prop tells which; a type that must not be duplicated does not satisfy `Clone`.

### D11. Diagnostics are a sink

```
inspect(x)                          // any value, any type, no bound; returns x
trace("balance = {b}, acct = {a}")
```

- `inspect` and `trace` write to the diagnostic channel. The text never becomes a `String` the program holds. Interpolating a value of unbounded type is allowed only inside a diagnostic sink: `trace`, the message of a panic or `unreachable`, and the failure report of a prop.
- They are not effects and do not appear in rows. A program's result cannot depend on them, so parametricity and the independence of results from selection hold.
- Output shows the value as represented: variant names and fields, `<fn name @ file:line>` for a function, the chosen concrete type for a choice-point value.
- A release build strips `inspect` and `trace`; a flag keeps them.
- *(Author's choice.)* So that generic baseline code can name what it prints, the tag in a cell header is unique in the link unit and indexes a link-time table of (type name, variant name, field kinds, size). A `match` subtracts the type's base, a link-time constant. The same table gives the witness operation `copy` the size and field kinds it lacked (P0 §4). An immediate reached through generic code prints as a raw number.

### D12. Satisfactions: no coherence, `using`

Coherence per link unit is withdrawn. A package may declare a satisfaction for a type and a capability it does not own, and a link unit may hold several satisfactions of one (type, capability).

- **Names.** A satisfaction may be named: `Money satisfies Ord named ByAmount:` *(the keyword `named` is the author's choice)*.
- **Resolution**, innermost first: `using` at the call site (`sort(xs) using ByAmount`); `using` in a type (`Set of Money using ByAmount`); `using` at file or package scope; the one satisfaction of the pair in scope, by `use`, by declaration in the site's package, or because it is always in scope (corrected after the consistency review); `using` in the application root, as the default of the link unit; the only one in the link unit. Otherwise it is an error.
- **The error halts on purpose.** Two satisfactions of the same pair differ in results, not in speed, so the score, the lock file and declaration order never choose between them. The message states the level at which the ambiguity arose and suggests a named `using`, with the satisfactions in scope as examples.
- **Deferred sites** *(author's choice)*. A site in a library that sees several satisfactions and has no `using` is not an error in the library: it is bound at link by the last two rules, and reported as a note. A site that sees none is a type error, as today.
- **The satisfaction is part of the type.** For a bound declared on a type's own parameter (`data Tree of K is Ord`), the elaborated type records the satisfaction: `Tree of Money[Ord = ByAmount]`. It is invisible when there is only one. Mixing two is a type error that names both and suggests `using`. This is what keeps a set built under one order from being searched under another.
- The selector's candidates are satisfactions. It chooses freely among satisfactions of different types; among satisfactions of the same pair only the resolution order above decides.

### D13. Visibility

Declarations are private by default. `pub` (already reserved) makes a `fn`, `data`, `cap`, `effect`, type alias or `satisfies` public. There is no opaque-type feature: `pub data` exposes its variants, and a representation is hidden by making the capability public and keeping the `data` private, so that its values cross the edge as a choice point. A package ships the polymorphic Core of its bodies by default; a manifest switch turns that off, and importers then use floor entries and exported versions only.

A private function needs no floor entry, and its baseline version is dropped when every call uses a clone; the application link drops unreferenced floor entries. A private data type needs no accessors (D8).

### D14. Effectful callbacks capture their evidence

A closure captures the evidence of the effects its body performs where it is created. A pure higher-order function is compiled as today and takes no hidden evidence; it cannot intercept an effect its type does not mention. (Koka's row-polymorphic, dynamically bound alternative was considered: it keeps closures fully first-class, at the price of a hidden argument on every higher-order function and closure call, and of row variables in every such signature.)

- **Confinement.** A closure that captured evidence must not outlive that handler. Violations are compile errors, with the hint to write the row in the stored type. The check treats the handler's extent as a region (Rule MM4.1) and uses the existing escape analysis; across a package edge it needs no body, because a parameter of a pure, total function can outlive the call only through the result or through the argument of an effect operation.
- The closure captures the handler's identity (a marker), not a stack address, so a continuation resumed under a re-installed handler reaches the right one.

### D15. The specialiser

- Ownership clones are requested only where a run-time `rc == 1` test cannot stand in: the bodies of `fip fn` and what they request (mandatory, unchanged), and values that have no count. A `Heap` value uses the baseline with the run-time test. OQ-CM11 goes away. **Open:** the owner suspects that the measurement behind this ("the test ran as fast as unconditional reuse") hides a compiler fault; it must be ruled out before the rule is relied on.
- The static weight of a request carries the weight of the requesting function's own call sites along call edges (closes OQ-CM10). Measured request counts replace it where they exist.
- Witness records for a parameterised instance (Rule CM4.2) are built on the stack in baseline code and are static where the instance is known at link (closes OQ-CM12). *(Author's choice.)*

### D16. The cell header

The 6–8% cost of the reference-map header was a call, not the map: the child walk sat in a helper that `clang -O2` did not inline. Fixed in the runtime (`fx_drop` fast path, out-of-line `free_cell` that loops on the highest marked child). The header, declaration-order fields and one layout per type stay. Cells stay self-describing: `drop` needs no type information. The limit of 32 reference slots stays.

### D17. Prelude and typer repairs (not debated)

`data Order: Less | Equal | Greater` satisfies `Ordering`; the typer rejects a satisfaction that does not define every member without a default; the malformed prelude text listed in P0 §3 is corrected; the compiler faults of P0 §4 are fixed.

## Amendments after the consistency review

An independent consistency review of the specs (2026-11-20, after this record was written into them) found contradictions that these decisions left open. The coordinator's rulings, as written into the specs:

- **B1 (refines D9).** The table convention is one containment test: a parameter is `own` iff a value of its type can become part of the result (`Self` contains its capability's `of` parameters; a type variable contains only itself; a function type only a function type or a data type with a function field). It is stated once, with a table of examples, in Rule CM4.6, and stays provisional (OQ-CM18).
- **B2 (refines D6).** The viability of a refinement-capability candidate is a facts-lane decision: only `proved` and `assumed` props count (Rules P7.7.c, P7.8.a).
- **B5 (refines D6).** Cost props skip the static prover; the cost checker runs on Core and makes its own solver call (Rules P7.1, CT6.2).
- **B6 (refines D11).** A use in `inspect` or `trace` is an ordinary use (quantity counted, demand `read`) in a build that keeps diagnostics; a build that strips them removes them before quantity inference, so erasure and reuse may differ while results do not. The compile error for a diagnostic use after the last use is withdrawn (Rule DG3.5, `spec/quantities.md` §4).
- **B7 (refines D7).** An `Inline` value that is not an immediate is copied out to a cell with a descriptor where it meets code that does not know its type (Rule MM3.1).
- **B8 (refines D11).** Interpolation exists only in the message of a diagnostic sink, as a name or field path; there is no general string interpolation (Rule DG3.2, grammar note at `STRING_LITERAL`).
- **B9 (refines D12).** A comma-separated `using` list is admitted bare only in the top-level `using` declaration; after a call or a type it needs parentheses (grammar v0.4.12).
- **M2 (refines D12).** Derived, built-in and prelude satisfactions are always in scope; a site *sees* a satisfaction that is in scope there (Rule 8.4).
- **M3 (refines D12).** Level 4 is "the one satisfaction of the pair in scope, by `use`, by declaration in the site's package, or because it is always in scope" (Rule 8.4, Rule CM6.6); D12 above is corrected to match.
- **M4 (refines D5, D12).** The satisfaction that enters the selector's pool for a data type is resolved at the site that created the choice point, by levels 1–4 there, and by levels 5–6 at link if that leaves several (§6.5.2, Rules CM8.4, CT8.1).
- **M5 (refines D12, D13).** A private satisfaction is visible only in its package: not for levels 5–6, not for other packages' deferred sites, and a type that records it may not appear in a `pub` signature (Rule 8.6.a).
- **M6, M7 (refine D4).** Rule CM8.4 cites the score of Rule 6.5.4 instead of restating it. The cost-prop check at link catches version skew and implicit (derived, built-in) satisfactions, since a declared one that fails is rejected at package compile; an open prop does not make a candidate non-viable (Rule CT8.1).
- **M9, M10 (refine D6).** The static prover gives no `assumed` status (`--no-pbt` belongs to `fixed verify`). A `proved` status obtained by enumeration in the test build is a proof, recorded in the interface when `fixed verify` has run, and reaches importers only (Rule P8.5, `spec/quantities.md` §4, `spec/memory_management.md` §20.3). Whether other test-build results reach importers is left to the owner.
- **M11 (refines D9).** Effect operations keep `own` on every argument; table calls follow Rule CM4.6 (Rule E7.7).
- **M12 (refines D16).** A `Static` cell carries a count word holding the immortal sentinel, which `dup` and `drop` leave alone; a `Heap` cell never holds a counted reference to a cell of a class without a count (Rules PR3.1, PR7.3).
- **M13 (refines D11).** The type table has a row for every kind of cell (data variant, closure, string, flat container, blob), each saying what `copy`, `size` and the printer read (Rule CM8.8).
- **M14 (refines D8).** Generic code reads the variant of a value that may be an immediate through the accessor `T.variant(x, witnesses)` (Rule CM3.7, `spec/pattern_matching.md` §8.4).
- **M15 (refines D14).** Every evidence entry, passed or captured, has one layout holding the handler's marker, through which the frame is found (`spec/effects.md` §7.1, Rule E7.2).
- **M16, M17 (refine D11).** `inspect` and `trace` are declared in the prelude, and a user declaration of the same name shadows them. The sinks are `inspect`, `trace` and the optional message of `unreachable`; there is no panic construct, and the failure report of a prop is printed by the test driver with the same printer, not written as a string literal (Rules DG3.1, DG3.2, `spec/stdlib.md` §5).
- **M18.** The grammar is not extended to `let x: T = …`; examples use parameter types (Rule 8.4, §8.5).
- **M20 (refines D6, D12).** The test build of a package uses that package's `package.fixed` as its application root (`spec/compilation_model.md` §8, Rule 8.4).
- **M21 (refines D6, D12).** A status is per (prop, satisfaction); a satisfaction that received no run is open for that prop; the prop's reported status is the weakest over the satisfactions that ran, and the report lists the others (Rule P7.7.a).
- **M22, M23.** The link report adds props tested under another binding, decisions resting on `assumed` props and each satisfier's claims beside the contract; the lock file records deferred sites (Rules RP5.3, RP5.4). Rule RP4.3 no longer narrows or shares tags.
- **Minors.** Built-in candidates count as declared in the standard library package, before its source declarations, and rule 4 of Rule 6.5.5 names the first capability of the bound set in canonical bound order. A test build keeps diagnostics (OQ-DG3 closed). A choice-point immediate prints with its chosen type where the choice point is known at the site, and as the raw slot through generic code (Rule DG3.4). Deferred sites are bound by level 5 only, since the link unit holds every satisfaction such a site sees (Rule 8.4.b).

Items B3, B4, M1, M8, M19 and the remaining part of M10 waited for the owner; they are decided below.

## Owner's decisions after the consistency review (third round, 2026-11-20)

The writers of the specs and the reviewer left thirteen questions for the owner. The answers:

- **A. Descriptors on every cell.** Accepted: every cell, in every class, carries its tag and reference map; only a `Heap` cell adds a count. This gives up the headerless stack cells of the first memory design. Omitting the descriptor from a cell that provably never reaches code that does not know its type stays open as an optimisation (`spec/memory_management.md` OQ-MM7).
- **B. `assumed` is written, never inferred.** A prop has the status `assumed` only when the programmer writes `assume prop` (the word is provisional). A prop that nobody checked is *open* and steers no compiler decision: `--no-pbt` and an undecided asymptotic cost bound leave a prop open. The order of statuses is open < `tested` < `assumed` < `proved`; the facts lane takes the last two. This settles B3 (a fork site takes `proved` or `assumed` props), M8 (an undecided asymptotic bound is open) and M10 (no test-build result other than a proof by enumeration reaches a facts lane, and that one reaches importers only).
- **C. Package scope.** `using` at package scope is written in the file `package.fixed` at the root of the package; the application root is that file in the package that declares `main`.
- **D. The table-call rule** as restated in Rule CM4.6 (amendment B1).
- **E. One `ref` witness per lifetime class.** The family of value witnesses stays fixed.
- **F. No witness travels with an anonymous result.** The choice point is bound by symbol at link. *(Author's consequence, not debated:)* the anonymous result of a requirement is therefore one choice point owned by the requirement and shared by every implementation; two implementations that construct different concrete types leave it without a viable candidate, which is a link error. This settles M1.
- **G. A link unit is an application.** A library is not a link unit; its escaping choice points and deferred sites stay unbound until an application links it.
- **H. `Clone` is derived only on request**, by a satisfaction declared without a body.
- **I. Naming.** camelCase for every value-level name, PascalCase for every type-level name (`spec/stdlib.md` Rule S1.1). The specs are renamed; the prelude, the examples, the benchmarks and the compiler's built-in member names are not yet.
- **J. Captured evidence and parallel lanes.** A closure carries one bit that is set when it holds captured evidence; a fork site that would call a function-typed parameter tests it and stays sequential when it is set. This settles B4.
- **K. `use` accepts lower-case names**, so a `pub fn` can be imported (M19).
- **L. The sixth resolution level is removed.** It could never decide.
- **M. Derived and named satisfactions.** No preference: a pair with a derived or unnamed satisfaction and a named one in scope is ambiguous until a `using` chooses (`E061`). *(Author's consequence:)* a `using` must then be able to name an unnamed or derived satisfaction, so `SatisfactionRef` also takes the pair form, `using Money satisfies Ord`.

The error for an unbounded `show`, `==` or `clone` is `T022`: the compiler's catalogue numbers typer errors `T0xx`, and the spec's first choice, `E064`, was withdrawn. Still not decided: the exit code of `fixed verify`; how a release build is requested; minors 7, 8 and 14 of the review.

## Owner's decisions (fourth round, 2026-11-21)

Three questions left open by the earlier rounds and by `docs/decisions/P0-spec-review-2026-11-20.md` §4. The owner's words are quoted; the rest is how they were written into the specs.

1. **Exit codes.** "Exit code: 0 no failure, then let's give each error class a number, we have space for 64 error numbers." One table for every command (`spec/reports.md` Rule RP5.5): `0` for no failure, `1` for an internal compiler error, `2` for a usage error, and one number per error class from `3` on (twenty classes so far), numbered in the order of the pipeline, at most 64 classes, clear of the codes the shell reserves (`126` and above). A run with errors of several classes exits with the smallest class number, which is the class of the first phase that failed. `a + b` stops at the first stage that fails and returns its code. Rule P7.7.b refers to the table. This settles the exit code of `fixed verify`, left open in the third round. *(Author's choices, owner to confirm: the class list and its numbers; `1` for an internal error, since the JVM already returns it for an uncaught exception; `2` kept for usage errors, as the shell and the compiler use it today; the smallest-number rule.)*

## Corrections to statements made in this debate

- "The reference map costs 6–8%" was wrong about the cause (D16).
- "A parameterised cell such as `Pair of (u8, u8)` must keep 64-bit slots, or the layout needs run-time records per instance" was wrong: the layout is a function of the argument witnesses (D7).
- "With universal `show` and `==` the parametric count becomes meaningless" was overstated: every type variable would behave as `is Show + Eq`; `T -> T` keeps one implementation.
- "Under implicit evidence parameters a callee can intercept an effect its type does not mention" is not true of a faithful row-polymorphic design; Koka prevents it with row variables and `mask`.
- "A private data type gets a packed layout" was loose: it gets direct field access; narrow-field packing inside cells is among the accepted losses.

## Open after this round

- The exact bit layout of immediates and the tag table (D7, D11).
- Whether `named` is the keyword (D12).
- The 8-byte header (D7).
- The suspected fault behind the ownership-clone measurement (D15).
- About 4% on `list_pipeline` and `records` against `main` is unexplained; `fx_drop` still recurses on children other than the last.
- OQ-CM1 is closed by D13; OQ-CM5 by D12; OQ-CM8 by D9; OQ-CM4 and OQ-T2 by D3.
