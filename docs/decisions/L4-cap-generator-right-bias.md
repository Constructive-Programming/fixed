# L4 — Right-bias on cap-generator multi-param returns

**Status:** Open. Pick one option.
**Affects:** spec/type_system.md Rule 7.4.c, docs/plans/implementation-plan.md "Right-Bias and Ambiguity Resolution."

## Problem

Rule 7.4.c says auto-derived Functor maps over the rightmost type parameter of a *named* `data T of (X1, ..., Xn)`. But cap-generators may return multi-parameter caps:

```
fn pair_constraint(lo: i64, hi: i64) -> cap of (i64, i64) =
    prop bounded: Self.first >= lo && Self.second <= hi
```

The cap returned by `pair_constraint(0, 100)` is anonymous and has two type params. If a user later writes `is pair_constraint(0, 100)`, can the compiler auto-derive a Functor instance? Over which parameter?

## Options

### Option 1 — Forbid auto-derivation on cap-generator returns

Cap-generator returns are refinement caps (Marker class per §6.4) — they carry only prop obligations, no methods. Auto-derived Functor doesn't apply to them; it applies to the *base type* the refinement narrows. If the user wants a multi-param cap with auto-derived Functor, they declare a named cap.

**Pros:** simplest. Matches the existing Marker classification: refinement caps add obligations, not methods.

**Cons:** programmer who tries to use `is pair_constraint(0, 100)` polymorphically gets an error and must declare a named cap.

### Option 2 — Apply right-bias by position

The rightmost parameter of `cap of (X1, ..., Xn)` is "active." Auto-derived Functor maps over it.

**Pros:** consistent with named-cap rule (Rule 7.4.c).

**Cons:** cap-generators are anonymous — there's no declaration site to attach right-bias intent to. Two generators returning `cap of (X, Y)` may have semantically swapped roles for X and Y; right-bias-by-position is arbitrary.

### Option 3 — Annotate at the generator declaration

Generator declares which param is "active":

```
fn pair_constraint(lo, hi) -> cap of (X, Y) functor_over Y =
    prop bounded: ...
```

**Pros:** explicit, never surprises.

**Cons:** new keyword (`functor_over`), unfamiliar territory. Programmers must remember to annotate.

## Recommendation

**Option 1** (forbid auto-derivation on cap-generator returns).

Rationale:
- Cap-generators almost always produce refinement caps (Marker class). Refinement caps have no methods of their own — Functor auto-derivation operates on what the *base* type provides.
- A user wanting Functor + a refinement should write `is Functor of (i64 + bounded)` — auto-derivation operates on `i64`'s underlying representation, the refinement is just an obligation.
- For the rare case of a multi-param refinement that *does* want auto-derived Functor, the user should declare a named cap with explicit type parameters; Rule 7.4.c then applies normally.
- Avoids inventing new syntax for cap-generators.

Spec edits if Option 1 chosen:
- `type_system.md` Rule 7.4.c: clarify "applies to named caps and named data types only. Auto-derivation does not apply to anonymous caps produced by cap-generator calls; refinement obligations on a base type do not change which auto-derivations apply to that base type."
- Add example showing `is Functor of (i64 + bounded(0, 10))` works (Functor over `i64`), and `is pair_constraint(0, 100)` does not auto-derive Functor (anonymous).
