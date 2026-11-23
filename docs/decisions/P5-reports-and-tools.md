# P5 — Coverage and cardinality are compiler reports; tools read, they do not plug in

**Status:** Decided 2026-11-20; decision 6 added 2026-11-22. The owner decided that the cardinality of a capability-bounded type is symbolic; the rest was proposed during the debate, not objected to, and is the author's to revise. Specified in `spec/reports.md`.
**Affects:** `spec/reports.md` (new), `spec/properties.md` Rules P5.3.c and P8.5.

## Problem

Three additions were proposed as optional: code coverage of tested code, and plugins that use the type system to compute type cardinality and algorithmic complexity.

## Decisions

1. **Coverage shares its counters with profiling.** A counter per region, inserted before specialisation; request counters per call site. The same data is the coverage report and the profile that weights clones (P2). (The Scala 2 coverage tool was a compiler plugin; Scala 3 made it a compiler phase.)
2. **Coverage answers two questions.** Whether a region was executed (`executed`, `not executed`, `unreachable`), and whether a declaration is constrained by a property (`proved`, `tested`, `assumed`, `unconstrained`). A proof executes nothing, and an executed region may be asserted by nothing; one number would hide both.
3. **Cardinality is a compiler service.** Counting inhabitants already serves exhaustiveness, generators and layout; the report is a fourth user. A prop whose binders have few enough values is checked for all of them and is then `proved`.
4. **The cardinality of a capability-bounded type is symbolic.** The report gives the symbolic bound always, and a parametric count where the capabilities' members cannot be iterated; otherwise it says `law-bounded`, because counting terms up to props is not decidable in general.
5. **No plugin interface.** External tools read the interface tier and the JSON reports. An analysis is a compiler pass when a compiler decision consumes its result; cost (P3) and cardinality are passes for that reason. (Rejected for now: a user-supplied grading that the typer threads beside quantities and effect rows.)
6. **The count is a four-term polynomial.** (2026-11-22.) The count of a type is `a·ε₀ + b·ωⁿ + c·ω + d`, with `ωⁿ` the tier of any finite power of `ω` greater than one. The intermediate tier separates a product of countably infinite spaces (`ωⁿ`) from a countable sum (`2ω`) without recording exponents; `ε₀` caps unbounded multiplicative depth and does not absorb coefficients. Coefficients are kept, multiplication is bilinear over the tier table, and powers use repeated squaring. A function whose row contains a resumable effect is `ε₀` times its pure count, so `bool -> bool with Console` is `4ε₀`. The count is exact only in the finite tier; above it the tiers are structural, not cardinal numbers. Specified in `spec/reports.md` Rules RP4.1.a–RP4.1.c.

## Open

A format for tools that need the typed tree of a package's own source (OQ-RP1).
