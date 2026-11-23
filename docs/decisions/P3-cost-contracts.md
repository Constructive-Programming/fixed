# P3 — Cost is a contract on capability members

**Status:** Decided 2026-11-20 (design debate with the project owner). Specified in `spec/cost.md`.
**Affects:** `spec/cost.md` (new), `spec/type_system.md` §6.5.3–§6.5.4, Rule 6.1.1, `spec/memory_management.md` §18, `spec/properties.md` Rule P3.5, `spec/stdlib.md` §4.12, `spec/syntax_grammar.ebnf` (`FipModifier`).

## Problem

The proposal was a plugin that derives algorithmic complexity from the type system. In Fixed the source alone has no complexity: the compiler picks the representation, and `get(i)` is constant on an array and linear on a list. The "access fit" score of representation selection was that same information, scored by hand.

## Decisions

1. **A contract, not a report.** A capability states bounds for its members; a satisfying type must meet them; representation selection consumes them. (Rejected: inferring cost after selection and only reporting it.)
2. **Steps and allocations, both from the start.** (Rejected: steps only; one blended number.)
3. **No quantity in the surface syntax.** The allocation contract states `constructs` and `releases`, which depend neither on what the caller owns nor on where the result is placed. The call site derives the fresh cells: `constructs − releases` when the caller exclusively owns the input, up to `constructs` otherwise. (Rejected: explicit quantity annotations on requirements; a contract with one bound per ownership.)
4. **`fip` is the zero case.** `fip`, `fip(n)` and cost props form one family, and the modifiers may be written on method requirements. This closes `spec/memory_management.md` OQ-MM1.
5. **In selection: a table from bound class to a raw score, inside the existing weighted sum.** A candidate that breaks a contract is not viable; the table only scores how far viable candidates beat the contracts. (Rejected: a dominance filter in front of the scoring. Viability already gives the hard guarantee, and the filter would break the stability requirement `K < ε`.)

## Settled while writing the spec

- **Surface.** A cost prop names the member: `prop getSteps: steps(get) <= O(1)`. No grammar change. Provisional (OQ-CT1).
- **Statuses.** Testing cannot refute an asymptotic bound, so a bound written with `O(…)` is `proved` or `assumed`. An exact bound can be `tested`.
- **Size index.** `size(x)` is the number of elements a `fold` visits. It is erased.
- **Callbacks.** The cost of a function argument is a symbolic term (`steps(f)`), not a row: cost is additive and scales with the number of calls.
- **Stability.** The derived dimensions are aggregated so that one added bound moves the total by at most 0.36, below the tie threshold.

## Prior art examined

The two Agda developments the owner cited (a cost monad after Danielsson; "complexity signatures" with a `Timed` monad) certify bounds by hand-written proofs; one reports that lists of unknown length were hard to handle. Fixed takes the bound in the interface and makes its inference syntax-directed instead: functions cannot recurse, so only `fold` and `unfold` introduce a size factor.
