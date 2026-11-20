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

- a parameter is `own` if a type variable of its type occurs in the result type, and `read` otherwise;
- the exclusive pointer of a table entry requires exclusive ownership only of the `own` parameters whose type is `Self`.

So `size(self) -> u64` and `compare(self, other) -> is Ordering` borrow; `push(self, x: Part) -> Self` owns both and updates `self` in place through the exclusive pointer while `x` stays shared; `get(self, i) -> Part` borrows `self` and `dup`s the part. A satisfier that must keep a `read` argument `dup`s it; one that ignores an `own` argument drops it. Before the convention is frozen, a benchmark with capability-bounded parameters measures it against `own` everywhere.

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
- **Resolution**, innermost first: `using` at the call site (`sort(xs) using ByAmount`); `using` in a type (`Set of Money using ByAmount`); `using` at file or package scope; the one satisfaction imported by `use`; `using` in the application root, as the default of the link unit; the only one in the link unit. Otherwise it is an error.
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
