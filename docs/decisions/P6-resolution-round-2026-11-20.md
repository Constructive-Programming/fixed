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
- ~~**Deferred sites**~~ *(author's choice; withdrawn by the owner's decision "No deferred sites", fourth round, below: such a site is `E061` when the library is compiled)*. A site in a library that sees several satisfactions and has no `using` is not an error in the library: it is bound at link by the last two rules, and reported as a note. A site that sees none is a type error, as today.
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
- **M4 (refines D5, D12).** The satisfaction that enters the selector's pool for a data type is resolved at the site that created the choice point, by levels 1–4 there, and by levels 5–6 at link if that leaves several (§6.5.2, Rules CM8.4, CT8.1). (The part at link is superseded in the fourth round, "No deferred sites": a type the site does not decide is not a candidate.)
- **M5 (refines D12, D13).** A private satisfaction is visible only in its package: not for levels 5–6, not for other packages' deferred sites (both withdrawn in the fourth round), and a type that records it may not appear in a `pub` signature (Rule 8.6.a).
- **M6, M7 (refine D4).** Rule CM8.4 cites the score of Rule 6.5.4 instead of restating it. The cost-prop check at link catches version skew and implicit (derived, built-in) satisfactions, since a declared one that fails is rejected at package compile; an open prop does not make a candidate non-viable (Rule CT8.1).
- **M9, M10 (refine D6).** The static prover gives no `assumed` status (`--no-pbt` belongs to `fixed verify`). A `proved` status obtained by enumeration in the test build is a proof, recorded in the interface when `fixed verify` has run, and reaches importers only (Rule P8.5, `spec/quantities.md` §4, `spec/memory_management.md` §20.3). Whether other test-build results reach importers is left to the owner.
- **M11 (refines D9).** Effect operations keep `own` on every argument; table calls follow Rule CM4.6 (Rule E7.7).
- **M12 (refines D16).** A `Static` cell carries a count word holding the immortal sentinel, which `dup` and `drop` leave alone; a `Heap` cell never holds a counted reference to a cell of a class without a count (Rules PR3.1, PR7.3).
- **M13 (refines D11).** The type table has a row for every kind of cell (data variant, closure, string, flat container, blob), each saying what `copy`, `size` and the printer read (Rule CM8.8).
- **M14 (refines D8).** Generic code reads the variant of a value that may be an immediate through the accessor `T.variant(x, witnesses)` (Rule CM3.7, `spec/pattern_matching.md` §8.4).
- **M15 (refines D14).** Every evidence entry, passed or captured, has one layout holding the handler's marker, through which the frame is found (`spec/effects.md` §7.1, Rule E7.2).
- **M16, M17 (refine D11).** `inspect` and `trace` are declared in the prelude, and a user declaration of the same name shadows them. The sinks are `inspect`, `trace` and the optional message of `unreachable`; there is no panic construct, and the failure report of a prop is printed by the test driver with the same printer, not written as a string literal (Rules DG3.1, DG3.2, `spec/stdlib.md` §5).
- **M18.** The grammar is not extended to `let x: T = …`; examples use parameter types (Rule 8.4, §8.5).
- **M20 (refines D6, D12).** The test build of a package uses that package's `package.fixed` as its application root (`spec/compilation_model.md` §8, Rule 8.4). (Superseded in the fourth round, "No deferred sites": the application root is no longer a resolution level.)
- **M21 (refines D6, D12).** A status is per (prop, satisfaction); a satisfaction that received no run is open for that prop; the prop's reported status is the weakest over the satisfactions that ran, and the report lists the others (Rule P7.7.a).
- **M22, M23.** The link report adds props tested under another binding, decisions resting on `assumed` props and each satisfier's claims beside the contract; the lock file records deferred sites (Rules RP5.3, RP5.4; withdrawn in the fourth round, "No deferred sites"). Rule RP4.3 no longer narrows or shares tags.
- **Minors.** Built-in candidates count as declared in the standard library package, before its source declarations, and rule 4 of Rule 6.5.5 names the first capability of the bound set in canonical bound order. A test build keeps diagnostics (OQ-DG3 closed). A choice-point immediate prints with its chosen type where the choice point is known at the site, and as the raw slot through generic code (Rule DG3.4). Deferred sites are bound by level 5 only, since the link unit holds every satisfaction such a site sees (Rule 8.4.b; withdrawn in the fourth round, "No deferred sites").

Items B3, B4, M1, M8, M19 and the remaining part of M10 waited for the owner; they are decided below.

## Owner's decisions after the consistency review (third round, 2026-11-20)

The writers of the specs and the reviewer left thirteen questions for the owner. The answers:

- **A. Descriptors on every cell.** Accepted: every cell, in every class, carries its tag and reference map; only a `Heap` cell adds a count. This gives up the headerless stack cells of the first memory design. Omitting the descriptor from a cell that provably never reaches code that does not know its type stays open as an optimisation (`spec/memory_management.md` OQ-MM7).
- **B. `assumed` is written, never inferred.** A prop has the status `assumed` only when the programmer writes `assume prop` (the word is provisional). A prop that nobody checked is *open* and steers no compiler decision: `--no-pbt` and an undecided asymptotic cost bound leave a prop open. The order of statuses is open < `tested` < `assumed` < `proved`; the facts lane takes the last two. This settles B3 (a fork site takes `proved` or `assumed` props), M8 (an undecided asymptotic bound is open) and M10 (no test-build result other than a proof by enumeration reaches a facts lane, and that one reaches importers only).
- **C. Package scope.** `using` at package scope is written in the file `package.fixed` at the root of the package; the application root is that file in the package that declares `main`.
- **D. The table-call rule** as restated in Rule CM4.6 (amendment B1).
- **E. One `ref` witness per lifetime class.** The family of value witnesses stays fixed.
- **F. No witness travels with an anonymous result.** The choice point is bound by symbol at link. *(Author's consequence, not debated:)* the anonymous result of a requirement is therefore one choice point owned by the requirement and shared by every implementation; two implementations that construct different concrete types leave it without a viable candidate, which is a link error. This settles M1.
- **G. A link unit is an application.** A library is not a link unit; its escaping choice points stay unbound until an application links it. (Its deferred sites did too, until the fourth round withdrew them: "No deferred sites".)
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
2. **List literals.** "A choice point the compiler picks unless the user overrides it e.g. `[1, 2, 3] is List of u8`." A list literal `[e1, …, en]` is an abstract constructor call through `Sequencing.cons` and `Sequencing.empty`. Unless a concrete expected type or an ascription fixes its type, it is a choice point (D1.2) whose bound set is what its context requires together with `Sequencing of E`, the least capability that has both `empty` and `cons`; selection binds it at application link (D4, D5). Elements take the element type (Rule 5.7). The user fixes the type with the ascription `e is T`, new in grammar v0.4.14, which is one rule for every expression (list, integer, float and string literals, abstract constructor calls, any expression): it fixes the type, the type must satisfy the context's bound, and it is not a conversion (`spec/type_system.md` Rules 5.10, 5.11; `spec/compilation_model.md` Rule CM8.1). Not built: the typer gives every list literal the type `List`, and examples 02 and 11 keep their stopgap until the choice point is built. *(Author's choices: the ascription binds tighter than `*` and looser than prefix `-`; it names a type, never a bound (`T024`); requirements are gathered within the enclosing function, and a member call adds none; literals whose types unify are one choice point; `[]` with nothing fixing its element type takes `()`; an ascription on an opaque result of another function's choice point is `T002`, since only the link step chooses it (D2, F); the only use-derived bound set is a literal's own (`spec/type_system.md` §6.5.1).)* The examples of Rule 7.3.c and `spec/quantities.md` §10.3, which wrote `let x: T = …` against M18, now use an ascription.
3. **Derived `fold` of linear data.** "Yes, of course use the loop form." The derived `fold`, `unfold` and `map` of linear data (a variant without fields, a variant with a field of type `Self`, each variant's field of type `Self` its last field, no other field mentioning the type: `List`, `Nat`) run in bounded native stack; trees, rose trees and snoc lists do not yet (`spec/type_system.md` Rule 7.4.b.3, `spec/memory_management.md` OQ-PR5). The loop form of `fold` reverses the spine (in place only where it is exclusively owned, since `fold` borrows `self`) and folds from the end; it measured +8.1% instructions and −23% best cycles on `nat_loop` (P0 §4). The code is being written separately. *(Author's choice: the definition of linear data is the compiler's present one.)*

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

### Derived `Functor` (fourth round, 2026-11-21)

Owner: "A now, B planned." A derived `map` rebuilds a value only where it can reach every occurrence of the rightmost parameter: the parameter itself, or the last argument of a data type that itself derives `Functor`, and only when the parameter is unbounded (`spec/type_system.md` Rule 7.4.c). Other types derive no `Functor` and the diagnostic names the blocking field. Planned: derivation through a function result (`u64 -> A`) by composition. Not planned: a map over any parameter position.

### Integer overflow and division (fourth round, 2026-11-21)

Owner: "C with prover or refinement removes Effect. Division with Effect. We ship standard effects that turn into maybe, either, raises integer size."

- Arithmetic on a bounded integer type that can leave the type's range performs the effect `Overflow`. Division and remainder perform an effect when the divisor can be zero. `MIN / -1` and `MIN % -1` are overflow.
- The effect is absent from an operation's type where the prover or a refinement shows the result in range (or the divisor non-zero). This is a facts-lane decision (`spec/properties.md` Rule P7.8.a): only `proved` and `assumed` remove it.
- The standard library ships handlers that turn the effect into a `Maybe`, into an `Either`, and one that raises the integer size.
- Nothing wraps silently and nothing is undefined behaviour.

Author's choices, confirmed by the owner on 2026-11-21:

1. Two effects: `Overflow` (`+`, `-`, `*`, negation, `MIN / -1`, `MIN % -1`, narrowing conversions) and `DivideByZero` (`/`, `%`).
2. Floating-point arithmetic performs neither; it follows IEEE 754.
3. An operation on literals, or one whose range is proved, has no effect in its type, so `i + 1` inside a `fold` over a bounded range stays pure.
4. Explicit members with no effect stay available on `Numeric`: `wrappingAdd`, `saturatingAdd` and their kin. A hash function says what it means.
5. An unhandled `Overflow` or `DivideByZero` at `main` aborts with the position of the operation.
6. "Raises integer size" cannot be an ordinary handler: it changes the type of the result. It is a form that computes the block at the next wider integer type and narrows at the end, performing `Overflow` once if the final value does not fit. Beyond 64 bits it needs the wider immediates of OQ-CM7.

Not built. Today `+`, `-` and `*` wrap, and `MIN / -1` and `MIN % -1` are undefined behaviour in the emitted code. (This record first said the same of division by zero. That was wrong: the emitted code checks the divisor and exits with status `101` and `fixed: panic: integer division by zero`.)

Written into the specs as `spec/effects.md` §3.6 (Rules E3.6–E3.6.j), `spec/stdlib.md` §4.9 and §4.13, `spec/type_system.md` §5.7 (`T025`), `spec/properties.md` Rules P4.2 and P7.8.a, and `spec/reports.md` Rule RP5.5. The further choices made there, each marked *(author's choice)* in the spec:

- Both operations are abortive: a handler that supplies a value would make `a + b` depend on the handler in force, and would make every operation a possible continuation capture; the handlers asked for do not resume, and the explicit members give a chosen value.
- An operation on a value of a type parameter has the row of `Numeric`'s requirement, whatever type is supplied: generic numeric code declares `Overflow` even when called at `f64`. A literal at a type parameter performs `Overflow` through `fromI64`.
- Constant arithmetic is evaluated at compile time; a result that does not fit is `T025`.
- A `tested` or open premise removes nothing; rows are fixed at the package compile. The arithmetic effects of a body are checked after the static prover has decided its range goals, under a deterministic resource limit, so a row never depends on the machine.
- Every refinement on a bounded integer type is established only by `proved` or `assumed`, since a caller cannot see whether a body removed an effect by it; otherwise `E107` *(owner to confirm; a run-time check was rejected because it would put prop bodies into the binary)*.
- Every `fn`, private ones included, declares the effects (Rule E5.2 unchanged); the alias `Arithmetic` names both.
- Derived members perform no arithmetic effect of their own; a callback's effect joins the row of the function that passes it. In a prop body an arithmetic effect is allowed and is a counterexample when it happens.
- Handlers `arithmeticOrNone` (to `is Optional`) and `arithmeticOrError` (to `is Result of (ArithmeticError, A)`; the prelude names its result capability `Result`, not `Either`). `widen(body)` is a prelude function the compiler types, so the grammar does not change; at `i64` and `u64` it is `T026` until OQ-CM7.
- The members without effects are on `Numeric`: `wrapping…`, `saturating…` (no default bodies) and `checked…` (default bodies through `arithmeticOrNone`).
- At `main` the runtime prints `fixed: panic: arithmetic overflow at <file>:<line>:<column>` and the program exits with status `101`. The position is a hidden argument that program handlers do not see. A run-time abort is not a compiler exit code.

**Open direction, not a decision.** The owner added: "we might need to introduce dependent types as a better solution to 'raise integer size'. It might even start simplifying other parts of our system." Recorded as `spec/type_system.md` OQ-T3, which names where the specs already meet the idea (refinement capabilities with value parameters, `boundedPositive(lo, hi)`, the prover removing `Overflow` by range) and designs nothing.

### No deferred sites (fourth round, 2026-11-21)

Owner: "B. Error at library".

The option as it was put: an ambiguity is an error where it occurs, `E061` at package compile. The library's author writes a `using`, or takes the satisfaction as an explicit bound (`fn sortAll(xs: List of (T is Ord))`), so that the caller chooses and the choice travels as an ordinary witness, the per-use binding of D1. There is no deferral mechanism at all, and the link stage resolves no satisfaction site. It follows answer M of the third round: an ambiguity between satisfactions is an error on purpose.

The deferred sites of D12 (an author's choice) are withdrawn, and with them their mentions in M5, M22/M23, the minors and answer G above. Written into `spec/type_system.md` Rule 8.4.b (rewritten as the error rule, with both ways out in the diagnostic) and Rule 8.6.a, `spec/compilation_model.md` Rules CM6.6, CM6.7, CM7.1, CM8.5 and §8, `spec/memory_management.md` §20, `spec/reports.md` Rules RP5.3, RP5.4 and RP5.5 (class `7`), `spec/cost.md` Rule CT8.1, `spec/syntax_grammar.ebnf` (`UsingDecl`) and `docs/plans/implementation-plan.md` (the CLI, Phase 4.2 item 4, the pipeline). *(Author's consequences, owner to confirm:)*

- The fifth resolution level, `using` in the application root, is removed, as the sixth was (answer L): it decided only sites left open for the link. In the application package the application root is the package scope, level 3. A test build's application root is no longer a resolution level (refines M20).
- The satisfaction of a data type that enters a choice point's pool is resolved at the site that created the choice point (M4); if the resolution there does not decide between several satisfactions of the pair, that type is not a candidate at the choice point. Selection therefore never meets two satisfactions of one pair, and its only error stays zero viable candidates (D5).

### Recursion inside a capability (fourth round, 2026-11-21)

Owner: "make recursion strictly forbidden inside capability".

A default body of a capability that reaches itself through calls to members of the capability on `self` (or on another value of type `Self`), directly or through other default bodies of the capability or of the capabilities it extends, is an error in every case: neither a structural argument nor `Diverge` admits it. The check of Rule 7.4.h did not see such a cycle, because the call goes through the table: `cap Sz: fn sz() -> u64 = self.sz()` was accepted and loops forever. Written into `spec/type_system.md` Rule 7.4.j and `spec/reports.md` Rule RP5.5, and built in `typer/Totality.scala`. *(Author's choices, owner to confirm:)*

- A new code, `T028`, rather than `T027`: Rule 7.4.h requires `T027` to show `with Diverge` and structural recursion as ways out, and here neither is one. Its ways out are to leave the member without a default body, to write the default with members that do not call back, or to use `fold` or `para` of the data the capability exposes. Class `6`, type error.
- The bodies of a `satisfies` block stay ordinary functions under Rule 7.4.h: structural recursion on the data is how a user writes a fold. A call of the same member on `self` unchanged in such a body was already `T027`, for data, generic data and scalar types, because the receiver's type is known.
- The check reads the capability's declarations alone: a cycle among its default bodies is an error even if a satisfaction gives a body that would break it.
- A call on `Self` in an extended capability reaches the default bodies by which extending capabilities restate the member, and restatements by two capabilities that extend one base are read together. Calls covered: member calls, comparison operators on `Self`, a static member by the capability's own name, and calls from nested functions and lambdas. Not seen: a cycle through a `satisfies` body back into a default body, and one through a function that takes the value at a bound.

No program of the prelude, the examples or the test programs had such a cycle.

### Data that holds a function of itself (fourth round, 2026-11-21)

Owner: "Allow for now. Let's see if this becomes a problem later, not now". A data type that holds a function of itself (`data Rec: R(f: Rec -> u64)`) admits non-termination that no cycle of named functions shows. It is accepted, as a known gap (`spec/type_system.md` Rule 7.4.h, OQ-T5).


### Shadowing of prelude names (fourth round, 2026-11-21)

Owner: "B Allow and give prelude a path. S1: same rule. S2: yes, error is fine."

The option as it was put: a program may declare a top-level name that the prelude also declares (data type, capability, effect, function); in the program the name then means the program's entity; the compiler warns at the declaration; the prelude's entity stays reachable by a qualified path the program can write, and diagnostics print that path; literals, operators, built-in `show` and the derivation rules always mean the prelude's entities (no rebindable syntax); a program's own capability named like a prelude capability is not derived. S1: the same rule for a name a `use` brings in. S2: a type parameter, or another local binder, that would hide a prelude type is an error, as re-binding an enclosing variable already is.

Written into `spec/type_system.md` §4.3 (Rules 4.3–4.3.h), grammar v0.4.15 (`UseRename`), `spec/stdlib.md` §1, §3.5, §5 and `spec/diagnostics.md` Rule DG3.1. The scope order is local binders, then the package's declarations and its files' `use`s, then the prelude. Author's choices, each marked in the spec:

- The warning is kept (`warning[W060]`); confirmed by the owner.
- The prelude is the package `std.prelude`; a hidden entity is reached with `use std.prelude.List as PreludeList`. `use` gains renaming (`as` was already reserved); types are never written with a path in a signature or an expression.
- Diagnostics print the name that means the entity at the site, otherwise its path (`std.prelude.List`, `geometry.List`), never `prelude.<name>`, and suggest the `use … as` that makes it nameable.
- The specs said nothing about collisions inside a package: a declaration and a `use`d name, or two `use`s of different entities, with one name, are `T003`; the same entity twice is allowed.
- S2 is `T004`, the code the compiler already gives a type variable that re-binds a bound name; new Rule 4.1.b states it for every binder position: named type variables, `of` parameters and row variables; value-level binders keep hiding a function in their scope, prelude or package, without a warning.
- The prelude's satisfactions stay always in scope when their names are hidden: hiding changes what a name means, not which satisfactions exist. A program's `List` takes list literals only by satisfying `std.prelude.Sequencing`, and `[1, 2] is List of u8` names the program's `List`. `==` always needs `std.prelude.Eq`. A `pub data List` hides nothing in an importer until the importer `use`s it.

Built: the prelude's own scope, syntax and derivation naming the prelude, no derivation of a program's capability (P0 §4), and `T004` for `IDENT is Bound` (`Namer.introduce`). Not built: `W060`, the path and `use … as`, paths in diagnostics, `T003` for `use`d names, `T004` for a bare `of` parameter and a row variable (`docs/plans/implementation-plan.md`, Phase 4.2, item 23).

### Confirmations and further decisions (fourth round, 2026-11-21)

- Arithmetic effects: "T is Numeric function always declares Overflow, even when called at f64. Simplest sound rule. Yes." "Refinements on bounded integers must be proved or assumed. Yes." (`E107`.)
- Hiding of prelude names: "Keep Shadowing warning (unless a named using is present of course)", clarified as "I just meant using of named satisfaction": `W060` is always given for a hidden name; selecting a named satisfaction with `using` gives no warning.
- A satisfaction where one already exists: "A, with file-scope and package-scope using allowed". A package's satisfaction of a pair that has a built-in or prelude satisfaction must be `named`; it is selected by `using` at any scope; where selected, operators and tables follow it (`spec/type_system.md` Rule 8.4.c, `E066`).
- Totality: "A. Enforce spec, and suggest in the error how to rephrase using para". Recursion between named functions is an error whose diagnostic shows the rewrite with `fold`, `para` or `unfold` (`spec/type_system.md` Rule 7.4.h, `T027`). The totality of a derived `unfold` is open (OQ-T4).
- Exit codes: the owner confirmed the class list with one change, "not supported yet = 99". Number `13` is free.
- Totality, amended the same day after the first enforcement run showed what the strict rule makes inexpressible (traversal of non-regular types, polymorphic recursion, deliberate non-termination). Owner: "we need to allow structured recursion so users can define their own folds … recursive call is legal only on a strict sub-value of a matched parameter. Still total." and "let's now allow general recursion as effect. fn f(…) with Diverge". Rule 7.4.h now accepts structural recursion (total, no effect) and any recursion under the effect `Diverge`; everything else is `T027`.
- Owner: "confirm all 5". `Diverge` has no operations and no handler; a function with it is not total (no proof by enumeration over it, never a fork site, cost bound per step); a mutual cycle is structural only if every call on it passes a strict sub-value; structural recursion takes one native frame per level; an `unfold` whose termination is not `proved` or `assumed` has `Diverge` in its row (Rules 7.4.h and 7.4.i).
- Release build: the owner first chose a flag, then replaced it ("No, sorry, let's do B"): a separate command. `fixed ship` builds the development executable, `fixed release` the release executable; there is no `--release` flag.
- `assume`: spelling `assume prop name: …` confirmed. "The keyword simply states the program wishes to treat the property as if it were proven." The compiler relies on `proved` and `assumed`, not on `tested` or open; evidence is reported separately and may be anything. `fixed verify` still tests an assumed prop: a proof gives a warning that `assume` is unnecessary (`W108`); a counterexample gives a warning, not an error (`W109`), and the prop stays relied on.
- Owner, on the consequences of "No deferred sites" and of Rule 7.4.j: the removal of the resolution level "`using` in the application root" is accepted; `satisfies` bodies stay ordinary functions under Rule 7.4.h; default bodies of two capabilities that extend one base are checked together. Still to be settled: whether a cycle among default bodies is an error when a satisfaction would break it.
- Anonymous result in a requirement: owner chose "A". A requirement `fn keys -> is Sequencing of Key` in a `cap` is one choice point, shared by every satisfaction of the capability and bound once at application link; each satisfaction builds the result through the bound's constructors. A capability whose satisfactions need different result types names the type as a member (as `Part` and `Key` are).
- Cycle among default bodies: owner chose "Reading 1, with the possibility of reading 2 in the future". The check reads the capability alone (Rule 7.4.j); a per-satisfaction check is open question OQ-T6.
- Naming an unnamed satisfaction: owner replaced the pair form. "Instead of a pair form we can introduce an automatic name 'Derived'.DataRef.CapRef, e.g. 'DerivedMoneyOrd'." `spec/type_system.md` Rule 8.4.d; `SatisfactionRef` is a name only.
- Layout of immediates and of the link-time type table (OQ-CM13): owner leaves both open until they are built and measured. "Layouts are measurement-lane matters; deciding before any code exists gains nothing." The drafts in `spec/compilation_model.md` stay provisional.
- Strings: owner chose code-point order ("B") and added "we should standardize strings to utf-8". A `String` is valid UTF-8; `Ord` is code-point order, `Eq` compares code points without normalisation (`spec/stdlib.md` Rule S3.7).
- Input that is not valid UTF-8: owner chose the effect, "A. + readBytes -> Bytes + String.fromUtf8(bytes)". `readLine` and `String.fromUtf8` perform the abortive effect `Decode`; `readBytes` returns raw `Bytes` (`spec/stdlib.md` Rule S3.7.a).
- `length` and `Overflow`: owner chose to declare it. "A. Declare it. This then points us the prover being able to eliminate Effects as well." `Sequencing.length -> u64 with Overflow`; the prover removes the effect at a call where it shows the operation cannot overflow (`spec/effects.md` Rule E3.6.e.1); the general case is OQ-E11.
- Removing effects (OQ-E11): owner rejected the framing as a fixed list. "We will get better at removing more effects as the system improves; including eventually removing Console from user specification when the body doesn't actually use it. This means, for now we can start with A, just arithmetic." On what a callee exports: "Let's start with Z, and a limited form of X where we can also warn the user the effect is not actually observable in the code and the compiler will remove it anyway." Recorded as `spec/effects.md` Rules E3.6.e.1 (arithmetic only, for now), E3.6.e.2 (a second function with a refined parameter and no effect) and E3.6.e.3 (`W086`: a declared effect the body cannot perform is removed). A `pub` function keeps the declared effect in its exported row: confirmed, "keep for pub, this is actually useful for semantic versioning later on".
- Automatic names, details (Rule 8.4.d): capitalised primitive names agreed. Two prefixes: "DefaultMoneyOrd. When only a derived instance is present it gets both names, when only the hand written version is present DerivedMoneyOrd is not present, when both exist they are separate." Capability arguments: "when the Part is specified, then the name expands, e.g. DerivedListSequencingOfU8". Author's choices left: spelling of several and nested arguments, built-in satisfactions count as derived, scope and collision of automatic names.
- Written and derived satisfaction of one pair: confirmed, "better worded as Default shadows Derived, users should generally use Default".
- List literal, details (Rule 5.10): a member called on the literal's value adds the member's capability to the bound set ("B"); an empty `[]` whose element type nothing fixes is an error, `T029` ("Do we have variance in Fixed yet? B. Error … for now if we do not"; the language has none); literals whose types unify are one choice point ("A").
- Confirmed by the owner: several capability arguments in an automatic name follow in written order with no separator; a built-in satisfaction counts as derived and has both names, a prelude satisfaction with a written body has `Default…` only (Rule 8.4.d). "1A, 2A, 3A" on `spec/stdlib.md` Rule S3.7.a and §3.7: the effect is `Decode` with the operation `invalidUtf8`; `Bytes` is a named prelude type; `String` equality compares code points with no normalisation.
- Cardinality and effects (`spec/reports.md` Rule RP4.1): owner chose "A, extended to abortive effects: |A -> B with R| = (|B| + outcomes(R))^|A|, where Diverge gives 1, Overflow 1, DivideByZero 1, Fail of E gives |E|, Decode gives count of u64 offset (or 1 if position is not value of program). Resumable effects (Console) stay outside count." The offset of `Decode` reaches its handler, so it counts `|u64|`. General form: an abortive effect counts the arguments of its operations.
- Keyword for the name of a satisfaction: owner chose `as` ("B"), accepting that `as` renames in a `use` and declares here. `Money satisfies Ord as ByAmount:`. `named` is withdrawn (closes `spec/compilation_model.md` OQ-CM14). Author's choice: the tail `use a.b.T satisfies C named N` is removed and not respelled, since `as` there would read as a rename; a named satisfaction is imported by its name (`use shop.money.ByAmount`).
- Derived `Functor`: owner chose "A". No declaration is needed (Rule 7.4.c as written); that the compiler asks for `T satisfies Functor` is a fault (P0 §4).
