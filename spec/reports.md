# Fixed Reports: Coverage, Cardinality and the Tool Interface

**Status:** Draft v0.2.5
**Specifies:** two analyses the compiler runs and reports — coverage of code by verification, and cardinality of types — the report of what the link step chose, the files a build reads besides its sources, the interface external tools use, and the exit codes of every command.
**Last revised:** 2026-11-21

**Changes from v0.2.4.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Shadowing of prelude names"): `W` codes are warnings numbered in the decade of the document that defines them, as `E` codes are (`W060`, `spec/type_system.md` Rule 4.3.a).

**Changes from v0.2.3.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Integer overflow and division"). Rule RP5.5 gives classes to `T025` (constant arithmetic that does not fit, class `6`), `E107` (an integer refinement not proved where it is established, class `11`) and `T026` (`widen` at a 64-bit type, class `13`), and says that a program's abort on an unhandled arithmetic effect is the program's exit status, not a class.

**Changes from v0.2.2.** Applies the owner's decisions of 2026-11-21, fourth round (P6, "Owner's decisions (fourth round)"), decision 1. New Rule RP5.5: exit code `0` for no failure and one number per error class, for every command; the smallest class number wins when a run has several. The table is proposed by the author; the owner confirms the class list. Decision 2 adds `T024` (an ascription that names a bound, `spec/type_system.md` Rule 5.11) to the type errors.

**Changes from v0.2.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). Rule RP3.2: the specification status follows the order of Rule P7.7.

**Changes from v0.2.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): the link report shows props tested under another binding, decisions resting on `assumed` props and each satisfier's claims beside the contract (Rule RP5.4); the lock file's deferred satisfaction sites (Rule RP5.3); layout uses no count, since a tag is one byte and each type has one layout (Rule RP4.3); "per satisfaction" replaces "per satisfying type".

**Changes from v0.1** (`docs/decisions/P6-resolution-round-2026-11-20.md`, D4, D6, D10).
- **Counters are the measurement lane's input** (Rule RP3.3). Region and request counters are the demand side of `spec/properties.md` Rule P7.9; they go to the profile file and never license a fact.
- **The lock file and the profile file** (new Rule RP5.3) are build inputs and things tools read (Rule RP5.1). A build is a function of its sources, its dependencies, the lock file and the profile file; it never measures.
- **The link report** (new Rule RP5.4, `--explain-selection`): per choice point the candidates, their scores, the disambiguation rule that decided and whether the data was measured or static.
- **Cardinality of choice points** (Rule RP4.2): symbolic until link, a number after it. An unbounded type variable still admits no inspection, so the parametric count is unchanged.

**Why this document exists.** Both analyses were proposed as optional plugins. Both turned out to be things the compiler needs for itself: counters are the input of measured selection and specialisation (`spec/properties.md` Rule P7.9), and counting inhabitants is already done three times (exhaustiveness, generators, layout). They are therefore compiler passes with reports, and tools outside the compiler read those reports and the package interface. The decisions are recorded in `docs/decisions/P5-reports-and-tools.md`.

## 1. Scope

This document specifies coverage (§3), cardinality (§4), and the tool interface with the files and reports it reads (§5). It does not specify the report of `spec/memory_management.md` §19, to which `spec/compilation_model.md` Rule CM5.8 and `spec/cost.md` Rule CT6.4 add; it only places the new reports in the same family.

## 2. Dependencies

- `spec/properties.md` — §7 (verification, statuses), Rule P7.8 (lanes), Rule P7.9 (measurement during testing), §8 (generators), Rule P9.2 (`unreachable` discharge).
- `spec/compilation_model.md` — clones (§5), the interface tier (Rule CM7.1), choice points (Rule CM8.1), usage summaries (Rule CM8.3), selection at link (Rule CM8.4), the lock file (Rule CM8.5), the profile file (Rule CM8.6).
- `spec/cost.md` — Rule CT8.2 (the score is usage × cost).
- `spec/pattern_matching.md` — §6 (exhaustiveness).
- `spec/type_system.md` — §6.4 (refinement capabilities), §6.5 (representation selection), Rule 6.5.5 (disambiguation).
- Implementation plan, Phase 6 (profile data).

## 3. Coverage

### 3.1 Regions (Rule RP3.1)

A **region** is a piece of source whose execution is worth counting: a function or method body; each arm of a `match`; each branch of an `if`; each handler clause; each lambda body.

Not regions: `prop` bodies (they are the tests, `spec/properties.md` §4.1); erased code (demand `0`); members the compiler derives.

A region is identified by its declaration and its ordinal within it, so its identity survives edits elsewhere in the file.

### 3.2 Two questions, two statuses (Rule RP3.2)

Line coverage answers one question badly. Fixed separates two.

**Was the region executed?** Each region has an *execution status*:

| Status | Meaning |
|---|---|
| `unreachable` | A prop in the facts lane (`proved` or `assumed`, `spec/properties.md` Rule P7.8) discharges every path to it (Rule P9.2). It is not counted in any total. |
| `executed` | Its counter is non-zero after the property-based tests of the build ran. |
| `not executed` | Otherwise. |

**Is the declaration constrained by a property?** Each function and member has a *specification status*: the strongest status (`proved`, then `assumed`, then `tested`, the order of `spec/properties.md` Rule P7.7) among the accepted props whose body calls it, or calls a function that (transitively) calls it. A declaration no prop reaches in this way is `unconstrained`.

The two are independent. The static prover does not look inside function bodies (Rule P7.2), so a `proved` prop constrains a function without executing any region of it; and a region can be executed by a test that asserts nothing about it.

### 3.3 Counters (Rule RP3.3)

In a test build, and in a build instrumented for profiling, the compiler puts a counter at the entry of every region. Counters are inserted in the Core of the baseline version, before specialisation, so every clone carries the counters of the regions it copies, labelled with its key (`spec/compilation_model.md` Rule CM5.1).

The same build counts, per call site, which clone key the call requested, and, per choice point (Rule CM8.1), how often each capability method was called on its values.

These counters are the **demand side of the measurement lane** (`spec/properties.md` Rules P7.8, P7.9). The build writes them to the profile file (Rule CM8.6): the request counts are the measured `weight` of Rule CM5.4, and the per-method counts are the measured `usage` of `spec/cost.md` Rule CT8.2. They choose among alternatives that are all correct; no count licenses a fact. A test build's counters rank below a production profile's (Rule P7.9).

Instrumentation must not disable a mandatory specialisation (Rule CM5.5): a fused loop keeps the counters of the step bodies it contains.

### 3.4 What the report shows (Rule RP3.4)

`fixed verify --coverage` prints, and with `--format json` emits:

- per region: execution status and count, in total and per version (baseline and each clone key);
- per declaration: specification status and the props that give it;
- per prop with a polymorphic binder: the number of iterations each combination of satisfactions received (`spec/properties.md` Rule P5.3.b says sampling thins them; this shows by how much);
- totals: regions executed over regions reachable, and declarations by specification status.

`fixed verify` is planned, not built (`docs/plans/implementation-plan.md`); the report is specified here so that the counters and the statuses have one definition.

### 3.5 Counters as a generator input (Rule RP3.5)

A property-based test driver may use region counters to prefer inputs that reach regions not yet executed. This is permitted, not required, and does not change what a status means.

## 4. Cardinality

### 4.1 The count (Rule RP4.1)

The **cardinality** of a type is the number of values it has.

- A sum adds: a `data` type's cardinality is the sum over its variants.
- A product multiplies: a variant's cardinality is the product over its fields.
- A function type exponentiates: `|A -> B| = |B|^|A|`. This is exact in Fixed, because functions are total and pure: there is no non-termination and no exception to add.
- An effect adds to the result: `A -> B with Fail of E` counts as `A -> (B + E)`. A function whose row contains an effect with a resuming operation counts as `ω`, because its meaning depends on the handler.
- A recursive type, and `String`, count as `ω` (countably infinite).
- A refinement capability (`spec/type_system.md` §6.4) lowers the count when the prover can count its inhabitants: `u8 + between(0, 150)` is 151. Otherwise the count of the base type stands as an upper bound.

### 4.2 Capability-bounded types are symbolic (Rule RP4.2)

A type variable bounded by capabilities (`x: T is C`) has no number: the representation is not the programmer's, and across a package edge it is never known. Its cardinality is a **symbol**, and the cardinality of a signature that mentions it is an expression in such symbols.

This holds for a named type parameter (bound per use) and for a type variable whose bound makes it a choice point (bound per link unit, Rule CM8.1) alike, with one difference: a choice point gets a number at application link. Before link, a package's interface and the report give the symbol; after link, the link report (Rule RP5.4) gives the number of the chosen type. The number describes the chosen representation and does not change the symbolic bound in the interface, which must stay valid for every choice.

Substituting a number for the symbol over-counts the implementations of a polymorphic signature, because a polymorphic function can only use what its bounds provide. The report therefore gives two things for a signature with bounded type variables:

1. the **symbolic bound**: the expression of Rule RP4.1 with the symbols left in place;
2. the **parametric count**, only where it is finite: the number of distinct terms of the result type that can be built from the parameters and the members of the bounds. It is finite when no member of the bounds can be applied to its own result without end. Otherwise the report says `law-bounded` and gives no number: counting terms up to the capabilities' props is not decidable in general.

**An unbounded type variable admits no inspection.** `show`, `==` and `clone` on a value of an unbounded type parameter are type errors (`docs/decisions/P6-resolution-round-2026-11-20.md` D10), and diagnostics, which print any value, return nothing to the program (`spec/diagnostics.md` Rule DG3.3). So `T -> T` with `T` unbounded has one implementation, and the parametric count is as above. The alternative debated was universal `show` and `==`: every type variable would then count as bounded by `Show + Eq`, the count would still exist but would be the count for that bound, and every signature's count would grow. It was rejected.

### 4.3 Who uses the count (Rule RP4.3)

Counting inhabitants is one service with several users inside the compiler:

- exhaustiveness of `match` and of handler clauses (`spec/pattern_matching.md` §6);
- generators: when the product of a prop's binder cardinalities is no larger than the iteration budget, the driver enumerates every combination instead of sampling (`spec/properties.md` Rule P8.5);
- the report below.

Layout does not use the count. A type with more than one variant has a one-byte tag when it is an immediate (`spec/compilation_model.md` Rule CM3.5) and a link-unique tag in its descriptor when it is a cell, and each type has one layout (`spec/memory_management.md` Rule PR3.1): no tag is narrowed to `⌈log₂ n⌉` bits or shared with a field.

### 4.4 The report (Rule RP4.4)

`fixed compile --explain-cardinality` prints, per `data` declaration, its state space, and per public signature, its implementation space, each as a number, `ω`, or a symbolic expression with the parametric count where Rule RP4.2 allows one. `--format json` gives the same data. The symbolic cardinality of every public declaration is part of the interface tier (`spec/compilation_model.md` Rule CM7.1), so two versions of a package can be compared.

## 5. The tool interface

### 5.1 Tools read; they do not plug in (Rule RP5.1)

The compiler has no plugin interface. An external tool reads:

- the **interface tier** of a compiled package, and the **body tier** where a package ships one (`spec/compilation_model.md` §7);
- the **reports**: `--explain-memory`, `--explain-cardinality`, `--explain-selection` (Rule RP5.4) and `verify --coverage`, each with `--format json`;
- the **lock file** and the **profile file** (Rule RP5.3).
- the **exit code** of every command (Rule RP5.5).

A tool that only reads cannot change what a program means or whether it compiles, and it does not break when a compiler pass changes.

### 5.2 When an analysis belongs in the compiler (Rule RP5.2)

An analysis is a compiler pass when a compiler decision consumes its result. Cardinality (Rule RP4.3) and cost (`spec/cost.md` §8) are passes for that reason. An analysis nothing in the compiler consumes stays outside it.

A way for a library to add a checked discipline to the type system (a third grading beside quantities and effect rows) is not specified.

### 5.3 The lock file and the profile file (Rule RP5.3)

Two files besides the sources are inputs of an application build:

- The **lock file** (`spec/compilation_model.md` Rule CM8.5) holds, per choice point bound at link, the satisfaction chosen, its score, and any pin the user wrote, and per deferred satisfaction site (`spec/type_system.md` Rule 8.4.b) the satisfaction it was bound to. It is checked in.
- The **profile file** (Rule CM8.6), one per target, holds the measurements of `spec/properties.md` Rule P7.9: the supply side (cost of one call per capability method, satisfaction, size class and target; machine facts) and the demand side (region, request and usage counters). Each record carries its source (production or test run) and its target label.

A build **reads** these files; it never measures. Measurement is a separate step (a test build, or a production run of an instrumented binary) that writes the profile file. A build is therefore a function of its sources, its dependencies, the lock file and the profile file: two machines with the same four inputs make the same choices and the same clones. A profile whose target label differs from the build's target is ignored, and the build uses static data (`spec/cost.md` Rule CT8.2). Continuous integration builds from the checked-in lock file and does not re-measure.

The link step writes the lock file. A choice that changes is a diff in it, and a pin in it is how a user overrides the selector (`spec/type_system.md` Rule 6.5.5). Whether the profile file is checked in is the project's decision; a build without it is reproducible from the other three inputs, with static weights.

Tools read both files as they read the reports. A tool that rewrites the lock file (to pin a choice) changes a build input, as editing a source does; it does not plug into the compiler (Rule RP5.1).

### 5.4 The link report (Rule RP5.4)

`fixed link --explain-selection` (and the same flag on `fixed compile` and `fixed ship` of an application) prints, and with `--format json` emits, per choice point bound at link:

- the choice point: its declaration, the bound set and the usage vector the link step summed (Rule CM8.3), with the source of each usage number (production profile, test run or static weight);
- each candidate satisfaction: viable or not, and for a non-viable one the cost prop it failed (`spec/cost.md` Rule CT8.1); for a viable one its score and, per used method, the cost of one call, each marked **measured** (with the profile's target label) or **static** (from the bound-class table), and the satisfier's own cost claims beside the capability's contract (`spec/cost.md` Rule CT6.1);
- the chosen satisfaction and the **disambiguation rule that decided** (`spec/type_system.md` Rule 6.5.5): a pin, the choice recorded in the lock file within `ε`, the highest score, the capability's own package, or declaration order;
- whether the choice differs from the one in the lock file before this link;
- the cardinality of the chosen type (Rule RP4.2);
- deferred satisfaction sites bound at link (`spec/type_system.md` Rule 8.4.b), as notes;
- props whose `tested` status was obtained under another binding of a choice point than this link's (`spec/properties.md` Rule P7.4);
- the facts-lane decisions that rest on `assumed` props (`spec/properties.md` Rule P7.8.a), with the props.

The report is the place to see why a representation was chosen; it is not needed to reproduce the choice, which the lock file records.

### 5.5 Exit codes (Rule RP5.5)

**Status: class list confirmed by the owner (2026-11-21), with `not supported yet` moved to `99`; number `13` is free** (`docs/decisions/P6-resolution-round-2026-11-20.md`, fourth round, decision 1). Not built: the compiler today exits `1` on any compile error, `2` on a usage error and `3` when `clang` fails.

The exit code is the first thing a tool reads from a run, so it is specified here, with the other things tools read. No spec specifies the command line as a whole (`spec/properties.md` Rule P7.6 gives the verification flags); `docs/plans/implementation-plan.md` lists the commands and flags.

Every command uses this one table: the checker `fixed <file>...`, `fixed compile`, `fixed link`, `fixed ship`, `fixed verify`, and a composition `a + b`. Exit code `0` means no failure. Every other exit code is the number of one **error class**. A command returns only the classes of the phases it runs.

| Exit code | Class | Where it arises | Diagnostic codes |
|---|---|---|---|
| `0` | no failure | — | none; warnings (`W…`, `T005` where the typer reports a gap, `T017`, `warning[E081]`) and notes never set an exit code, unless a flag makes one an error (`--strict-pbt`, class `11`) |
| `1` | internal compiler error | any command | none: the compiler itself faulted (an uncaught exception, a failed assertion); it is also what the JVM returns for an uncaught exception |
| `2` | usage error | the command line, before any phase runs | none: an unknown command or flag, a missing or extra argument, flags that exclude each other (`--no-pbt` with `--strict-pbt`, `spec/properties.md` Rule P7.6), an input file that cannot be read |
| `3` | scan error | package compile: scanner | `E001`–`E009` |
| `4` | parse error | package compile: parser | `P001`–`P099` |
| `5` | name error | package compile: namer and typer | `T001`, `T003`, `T004`; `E063` |
| `6` | type error | package compile: typer | `T002`, `T006`, `T007`, `T008`, `T010`, `T013`, `T014`, `T022`, `T023`, `T024`, `T025`, `T027`; `E062`, `E065`, `E066` |
| `7` | ambiguous satisfaction | package compile: typer; application link: deferred sites | `E061`; `T011` |
| `8` | effect error | package compile: typer (effect inference) | `E080`, `E084`, `E093`; `T009`, `T015`, `T016`, `T018`, `T019` |
| `9` | pattern error | package compile: typer | `E090`, `E091`, `E092`, `E094`, `E095`; `T020` |
| `10` | malformed prop | package compile: typer and static prover | `E100`, `E104`, `E105` |
| `11` | prop failed | package compile: static prover; test build | `E102`; `E107` (an integer refinement not proved where it is established); under `--strict-pbt`, a budget overrun (`W103`) |
| `12` | quantity error | package compile: quantity inference | `E070`, `E072`, `E083` |
| `14` | `fip` contract or confinement violated | package compile and the clones of a release link: demand and memory inference | `E071`, `E110`, `E085` |
| `15` | cost bound not met | package compile: cost checker | `E120` |
| `16` | no viable representation | selection, at package compile or application link | `E060` |
| `17` | link error | application link | none yet: a package or symbol the link unit lacks, a lock file or profile file that cannot be read |
| `18` | toolchain failure | `ship`, `link`: the native toolchain | `B004` (`clang` missing or failed); an output file that cannot be written |
| `19` | no generator | test build | `E101`, `E101a` |
| `20` | prop not proved | after the verifiers, under `--require-proved` | `E106` |
| `99` | not supported yet | package compile: a limit of the compiler, found by the namer, the typer or lowering | `T005` where the namer reports it as an error; `T026`; `B001`, `B002`, `B003`, `B005` |

**Several classes in one run.** The exit code is the smallest class number among the errors the run reported. The rule is deterministic and does not depend on the order in which diagnostics are printed. A phase that fails stops the phases after it, so the errors of one run come from the first phase that failed, or from the front end (scanner, parser, typer), which keeps going on partial input; the classes of errors in the program that the front end finds (`3` to `10`) come first and in front-end order, so the code names the first phase that failed. A limit of the compiler that the namer finds (`T005`, class `13`) ranks below them: an error in the program is fixed first. The application link and the test build run only after a package compile that succeeded, and among their own classes the number decides. An internal compiler error outranks everything the run reported; a usage error stops the run before any phase.

**Composed commands.** `a + b` runs `a`, then `b` only when `a` exited `0`. Its exit code is that of the first stage that did not exit `0`, or `0`. `fixed verify + ship` with a failed prop exits `11` and builds nothing.

**Range.** Classes take the numbers `1` to `64`; `21` to `64` are free for classes added later, and `65` to `125` are not used. The shell reserves `126` and above (`126` cannot execute, `127` not found, `128 + n` killed by signal `n`), so a class never collides with them. A run killed by a signal shows the shell's code, not a class. The exit code of a program that `fixed ship` built is the program's own and is not covered here: a run-time abort, such as an unhandled `Overflow` at `main` (status `101`, `spec/effects.md` Rule E3.6.j), is not a class.

**Classes and diagnostic codes.** A class is not computed from a code. Codes are grouped by origin: in the compiler, `E` for the scanner and a few later checks (`E072`, `E110`), `P` for the parser, `T` for the typer and `B` for the backend (`compiler/src/main/scala/fixed/util/Reporter.scala`); in the specs, `E` codes, and `W` codes for warnings, by decade per document (`E06x` and `W060` satisfactions, selection and scope, `E07x` quantities, `E08x` effects, `E09x` patterns, `E10x` props, `E11x` memory, `E12x` cost). A class gathers codes by what the user must fix, which cuts across both: the `T` family spans five classes, and the `B` family two. Where the compiler and the specs number the same error differently (`T015` and `E080` for an undeclared effect, `T020` and `E091` for a match that is not exhaustive), both are in one class. Every code that is an error belongs to exactly one class, and a new code is given its class in this table when it is introduced.

## 6. Open questions

- **OQ-RP1 — Typed-tree access for tools.** A tool that needs the typed tree of a package's own source has no format to read (`spec/compilation_model.md` Rule CM7.4 serialises none). A JSON dump is the obvious candidate; wait for a tool that needs it.
- **OQ-RP2 — Specification status through capability tables.** A prop that calls a capability method constrains the requirement. Whether it also constrains each satisfying type's implementation is counted per (prop, satisfaction) pair when the prop is inherited (Rule P7.7.a), and not otherwise.
- **OQ-RP3 — Counter cost.** Region counters in fused loops cost a load and a store per step. Whether test builds need sampling instead is to be measured.
- **OQ-RP4 — Counting refinements.** Which refinement shapes the prover can count (intervals, finite sets) and which fall back to the base type.
- **OQ-RP5 — Format and merging of the profile file.** How several runs (test runs on several machines of one target, production samples) are merged, and how old records age out, is not specified.

## 7. Cross-references

| Document | Relationship |
|---|---|
| `spec/properties.md` | Statuses (Rule P7.7), the verdict and its exit code (Rule P7.7.b, Rule RP5.5), lanes (Rule P7.8), measurement (Rule P7.9), `unreachable` (Rule P9.2), sampling (Rule P5.3.b), enumeration (Rule P8.5) |
| `spec/compilation_model.md` | Clone keys label counters; request counters are the measured weight of Rule CM5.4; the interface tier carries cardinality; choice points (Rule CM8.1), summaries (Rule CM8.3), the lock file (Rule CM8.5), the profile file (Rule CM8.6) |
| `spec/cost.md` | Rule CT8.2 reads the counters as usage; Rule CT8.1 viability is shown in the link report |
| `spec/diagnostics.md` | Rule DG3.3 keeps diagnostics out of the parametric count |
| `spec/pattern_matching.md` | §6 exhaustiveness shares the inhabitant count |
| `spec/type_system.md` | §6.4 refinement capabilities lower the count; Rule 6.5.5 names the disambiguation rule the link report shows |
| `spec/memory_management.md` | §19 is the first report of the family |
| `docs/decisions/P5-reports-and-tools.md` | The decision and the alternatives rejected |
