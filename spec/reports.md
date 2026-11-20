# Fixed Reports: Coverage, Cardinality and the Tool Interface

**Status:** Draft v0.1
**Specifies:** two analyses the compiler runs and reports — coverage of code by verification, and cardinality of types — and the interface external tools use.
**Last revised:** 2026-11-20

**Why this document exists.** Both analyses were proposed as optional plugins. Both turned out to be things the compiler needs for itself: counters are the input of profile-guided specialisation, and counting inhabitants is already done three times (exhaustiveness, generators, layout). They are therefore compiler passes with reports, and tools outside the compiler read those reports and the package interface. The decisions are recorded in `docs/decisions/P5-reports-and-tools.md`.

## 1. Scope

This document specifies coverage (§3), cardinality (§4) and the tool interface (§5). It does not specify the report of `spec/memory_management.md` §19, to which `spec/compilation_model.md` Rule CM5.8 and `spec/cost.md` Rule CT6.4 add; it only places the new reports in the same family.

## 2. Dependencies

- `spec/properties.md` — §7 (verification, statuses), §8 (generators), Rule P9.2 (`unreachable` discharge).
- `spec/compilation_model.md` — clones (§5), the interface tier (§7).
- `spec/pattern_matching.md` — §6 (exhaustiveness).
- `spec/type_system.md` — §6.4 (refinement capabilities), §6.5 (representation selection).
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
| `unreachable` | A `proved` prop discharges every path to it (Rule P9.2). It is not counted in any total. |
| `executed` | Its counter is non-zero after the property-based tests of the build ran. |
| `not executed` | Otherwise. |

**Is the declaration constrained by a property?** Each function and member has a *specification status*: the strongest status (`proved`, then `tested`, then `assumed`; Rule P7.7) among the accepted props whose body calls it, or calls a function that (transitively) calls it. A declaration no prop reaches in this way is `unconstrained`.

The two are independent. The static prover does not look inside function bodies (Rule P7.2), so a `proved` prop constrains a function without executing any region of it; and a region can be executed by a test that asserts nothing about it.

### 3.3 Counters (Rule RP3.3)

In a test build, and in a build instrumented for profiling, the compiler puts a counter at the entry of every region. Counters are inserted in the Core of the baseline version, before specialisation, so every clone carries the counters of the regions it copies, labelled with its key (`spec/compilation_model.md` Rule CM5.1).

The same build counts, per call site, which clone key the call requested. Region counters and request counters together are the profile that Phase 6 reads: the request counts are the measured `weight` of Rule CM5.4.

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

Substituting a number for the symbol over-counts the implementations of a polymorphic signature, because a polymorphic function can only use what its bounds provide. The report therefore gives two things for a signature with bounded type variables:

1. the **symbolic bound**: the expression of Rule RP4.1 with the symbols left in place;
2. the **parametric count**, only where it is finite: the number of distinct terms of the result type that can be built from the parameters and the members of the bounds. It is finite when no member of the bounds can be applied to its own result without end. Otherwise the report says `law-bounded` and gives no number: counting terms up to the capabilities' props is not decidable in general.

### 4.3 Who uses the count (Rule RP4.3)

Counting inhabitants is one service with several users inside the compiler:

- exhaustiveness of `match` and of handler clauses (`spec/pattern_matching.md` §6);
- generators: when the product of a prop's binder cardinalities is no larger than the iteration budget, the driver enumerates every combination instead of sampling (`spec/properties.md` Rule P8.5);
- layout: a sum with `n` variants needs `⌈log₂ n⌉` bits of tag, and a field whose type has unused bit patterns can hold the tag of an enclosing sum (representation selection, `spec/type_system.md` §6.5);
- the report below.

### 4.4 The report (Rule RP4.4)

`fixed compile --explain-cardinality` prints, per `data` declaration, its state space, and per public signature, its implementation space, each as a number, `ω`, or a symbolic expression with the parametric count where Rule RP4.2 allows one. `--format json` gives the same data. The symbolic cardinality of every public declaration is part of the interface tier (`spec/compilation_model.md` Rule CM7.1), so two versions of a package can be compared.

## 5. The tool interface

### 5.1 Tools read; they do not plug in (Rule RP5.1)

The compiler has no plugin interface. An external tool reads:

- the **interface tier** of a compiled package, and the **body tier** where a package ships one (`spec/compilation_model.md` §7);
- the **reports**: `--explain-memory`, `--explain-cardinality` and `verify --coverage`, each with `--format json`.

A tool that only reads cannot change what a program means or whether it compiles, and it does not break when a compiler pass changes.

### 5.2 When an analysis belongs in the compiler (Rule RP5.2)

An analysis is a compiler pass when a compiler decision consumes its result. Cardinality (Rule RP4.3) and cost (`spec/cost.md` §8) are passes for that reason. An analysis nothing in the compiler consumes stays outside it.

A way for a library to add a checked discipline to the type system (a third grading beside quantities and effect rows) is not specified.

## 6. Open questions

- **OQ-RP1 — Typed-tree access for tools.** A tool that needs the typed tree of a package's own source has no format to read (`spec/compilation_model.md` Rule CM7.4 serialises none). A JSON dump is the obvious candidate; wait for a tool that needs it.
- **OQ-RP2 — Specification status through capability tables.** A prop that calls a capability method constrains the requirement. Whether it also constrains each satisfying type's implementation is counted per (prop, satisfying type) pair when the prop is inherited (Rule P7.7.a), and not otherwise.
- **OQ-RP3 — Counter cost.** Region counters in fused loops cost a load and a store per step. Whether test builds need sampling instead is to be measured.
- **OQ-RP4 — Counting refinements.** Which refinement shapes the prover can count (intervals, finite sets) and which fall back to the base type.

## 7. Cross-references

| Document | Relationship |
|---|---|
| `spec/properties.md` | Statuses (Rule P7.7), `unreachable` (Rule P9.2), sampling (Rule P5.3.b), enumeration (Rule P8.5) |
| `spec/compilation_model.md` | Clone keys label counters; request counters are the measured weight of Rule CM5.4; the interface tier carries cardinality |
| `spec/pattern_matching.md` | §6 exhaustiveness shares the inhabitant count |
| `spec/type_system.md` | §6.5 uses the count for tags and packing |
| `spec/memory_management.md` | §19 is the first report of the family |
| `docs/decisions/P5-reports-and-tools.md` | The decision and the alternatives rejected |
