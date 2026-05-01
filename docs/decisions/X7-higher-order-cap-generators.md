# X7 — Higher-order cap-generator types

**Status:** Largely resolved by S0.2 (CapType in TypeAtom). Remaining sub-question: cap identity.
**Affects:** spec/type_system.md §6.3, spec/syntax_grammar.ebnf (already updated).

## Problem

After S0.2 — adding `CapType` to `TypeAtom` — cap-generators can appear in higher-order positions:

```
fn validate_with(constraint: (N, N) -> cap of N, lo: N, hi: N) -> N is constraint(lo, hi) =
    ...
```

The grammar admits this; the typer must handle it.

The remaining question is **cap identity**: when two cap-generator calls produce caps with the same prop body, are they the same cap?

```
fn between(lo, hi) -> cap of N = prop in_range: lo <= Self && Self <= hi
fn alt_between(lo, hi) -> cap of N = prop in_range: lo <= Self && Self <= hi
let x: N is between(0, 10) = ...
let y: N is alt_between(0, 10) = ...
let z: N is between(0, 10) = x   // x : alt_between, target : between — does this assign?
```

## Options

### Option 1 — Structural identity

Two caps with the same prop body (after substitution of value-params) are the same cap. `between(0, 10)` and `alt_between(0, 10)` produce the same cap because their prop bodies are equivalent.

**Pros:** maximally permissive. Programmers don't need to track which generator produced a cap.

**Cons:** prop equivalence is undecidable in general; implementations would default to syntactic equality, which is fragile (one whitespace difference makes caps "different"). Hidden compatibility — refactoring a generator's body silently changes which assignments compile.

### Option 2 — Nominal at generator-name + value-args

Two caps are the same iff (a) they come from the same generator (`between` ≠ `alt_between`) and (b) their value-args are equal (literally, by symbolic comparison). Different generators always produce different caps even if their bodies are identical.

**Pros:** decidable, predictable. Refactoring a generator's body changes the prop checks but not the cap's identity. Matches how dependent types work in Idris (named type families are nominal).

**Cons:** stricter than necessary in the rare case that two generators are intentionally aliases. Workaround: declare one as `type` alias for the other.

### Option 3 — Fully nominal at generator-name only

Two caps are the same iff they come from the same generator name. Value-args are part of the prop obligation but not part of identity. `between(0, 10)` and `between(20, 30)` are the same cap (both refinements of `between`); their prop bodies differ only in obligations to discharge.

**Pros:** very simple identity rule.

**Cons:** wrong — `between(0, 10)` and `between(20, 30)` have disjoint inhabitant sets. Treating them as the same cap loses the refinement. Reject.

## Recommendation

**Option 2** (nominal at generator-name + value-args).

Rationale:
- Decidable and predictable.
- The user already reasons about generators by name (when they write `is between(0, 10)`); making identity follow naming matches their mental model.
- Refactoring a generator's body changes prop obligations but not call-site type compatibility — surprises are minimized.
- If two generators are *meant* to be aliases, the user can declare a `type alias`:
  ```
  type alt_between = between
  ```
  Then `alt_between(0, 10)` *is* `between(0, 10)` because they desugar to the same generator name.

Spec edits if Option 2 chosen:
- `type_system.md` §6.3 (cap-generating sugar): add "Cap identity. Two cap-generator applications are the same cap iff (a) they invoke the same generator (after `type`-alias expansion) and (b) their value-args are equal under symbolic comparison. Type-args are subject to the usual type equivalence."
- Add an example of identity vs prop-equivalence.

Note: this preserves the option to relax to Option 1 later if structural prop equivalence becomes practical (e.g., via SMT). Going from nominal to structural is a backwards-compatible widening.
