# X5 — Default cap-method body typing

**Status:** Open. Pick one option.
**Affects:** spec/type_system.md §6.1, spec/quantities.md §5.12.

## Problem

A cap may declare a default body for an instance method, e.g.

```
cap Optional:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold(on_some: Part -> R, on_none: () -> R) -> R
    fn isDefined -> bool = self.fold((_) -> true, () -> false)   // default body
    fn orElse(other: Self) -> Self = self.fold((_) -> self, () -> other)
```

`isDefined`'s body uses `self.fold(...)` — but `Self` is abstract here. The typer doesn't yet know which satisfying type will provide `fold`. At declaration time, `self` has type "the abstract Self of this cap, with the cap's declared methods available."

`quantities.md` §5.12 already proposes a two-pass quantity inference (declaration-time abstract pass, then per-satisfaction re-check). Question: does *typing* follow the same shape, or is it abstract-only?

## Options

### Option 1 — Abstract-Self typing only

Type the body once at declaration time, treating `Self` as an opaque type with the cap's methods, `Part` as an opaque element type. The body must type-check using only what the cap promises.

If a satisfying type's actual signatures end up incompatible (e.g., the cap promised `fn fold(R) -> R` but the satisfaction's `fold` has a slightly different signature), that's caught in *cap-closure* (Recheck phase, type_system.md §6.5.7) — the satisfaction itself is rejected.

**Pros:** simplest, predictable, matches Scala 3's `cc/` capture-checker pattern (type once at declaration, refine at use). No N× compile-time cost.

**Cons:** abstract Self may permit bodies that pass per-cap checks but break at specific satisfactions in subtle ways (e.g., effect-row mismatches). Cap-closure must be thorough.

### Option 2 — Re-check at each satisfying type

Type the body N times (N = number of satisfactions in scope). Catches more (per-satisfaction effect rows, quantity mismatches, prop violations).

**Pros:** maximum soundness; per-satisfaction issues surface early.

**Cons:** N× cost. Error attribution unclear: a default body errors against satisfaction T — does the cap author or the satisfaction author own the fix?

### Option 3 — Both (layered)

Abstract typing at declaration (Option 1's pass) catches obvious bugs against the cap's interface. A separate Recheck phase re-checks each default body in the context of each satisfying type — but only for *quantities* and *effect rows*, not types (which are guaranteed compatible by cap-closure).

**Pros:** covers the gap Option 1 leaves (effects and quantities) without paying full N× typing cost. Aligns with the existing Recheck-phase architecture (type_system.md §6.5.7, quantities.md §5.12).

**Cons:** spec needs to enumerate what re-check catches (effects, quantities) and what it doesn't (types — already settled).

## Recommendation

**Option 3.** It's already implicitly the design — `quantities.md` §5.12 proposes per-satisfaction quantity re-check, and that pattern generalizes naturally to effects. Types are settled at cap-closure (the satisfaction is rejected if signatures mismatch); quantities and effects need per-satisfaction visibility.

Spec edits if Option 3 chosen:
- `type_system.md` new §6.1.x: "Default body typing." States: declaration-time typing is abstract-Self; cap-closure rejects type-incompatible satisfactions; per-satisfaction quantity/effect re-check happens in the existing Recheck phases.
- Cross-reference from `quantities.md` §5.12 and `effects.md` §5.x.
