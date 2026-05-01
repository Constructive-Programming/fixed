# X3 — Handler-arm coverage vs match exhaustiveness

**Status:** Open. Pick one option.
**Affects:** spec/effects.md §5.3, spec/pattern_matching.md §10, the typer's coverage pass.

## Problem

Two coverage checks live in two specs:

- **`match` arms** (`pattern_matching.md` §6) verify *exhaustiveness*: every variant of the data type must appear (or be elided via `_`). The reference algorithm is Maranget's matrix coverage with usefulness analysis.
- **handler arms** (`effects.md` §5.3) verify *coverage*: every operation of every handled effect must appear, plus an optional `return` arm.

Both are conceptually the same: "every constructor of the discriminator must appear." Today they are described in two unrelated sections with no normative cross-reference. Implementations may end up with two separate algorithms.

Adjacent issue: Rule M3.4.c forbids `match` on cap-bound values. Handler arms dispatch on operation names — a kind of cap-bound dispatch — but `pattern_matching.md` doesn't address this.

## Options

### Option 1 — Share Maranget machinery; document both as instances of "constructor coverage"

Implement Maranget once. Match arms feed in data-variant patterns; handler arms feed in op-name "patterns" (depth-1 trivial cases). Spec adds a new common section (e.g., `pattern_matching.md` §11 or a new `coverage.md`) that states the algorithm; effects.md and pattern_matching.md both reference it.

**Pros:** one implementation, consistent error-message style, single normative source for usefulness analysis.

**Cons:** marginally over-engineered for handler arms (which are always single-position, no nesting). Mental model: "handler arms are degenerate match arms."

### Option 2 — Two separate algorithms

`match` exhaustiveness uses Maranget. Handler-arm coverage is a simple set-membership check ("for each handled effect, is every op-name covered?"). Spec keeps the two sections, no cross-reference.

**Pros:** simplest possible implementation per case. Handler-arm checking becomes ~10 lines.

**Cons:** two error-message styles, two places to maintain. If wildcard handler arms are ever revisited (currently closed per OQ-E2), they'd need their own usefulness story.

### Option 3 — Unify spec, but allow implementation latitude

Add one normative section that says: "Coverage is the obligation that every constructor of the discriminator type appears in the arm list. Implementations may share or split the underlying algorithm." Effects.md and pattern_matching.md cross-reference this section.

**Pros:** spec is unified without forcing implementation choice. Implementation can split for performance and unify for error-message UX, or vice versa.

**Cons:** weaker normative force — reviewers may ask "which one?"

## Recommendation

**Option 1.** Maranget is well-understood and the implementation is a once-and-done. Sharing the matrix means error messages are uniform and future extensions (or-patterns, guards in handler arms) compose cleanly. The "degenerate match" mental model is accurate and readable.

Spec edits if Option 1 chosen:
- New `pattern_matching.md` §11 "Coverage as a unified obligation"
- `effects.md` §5.3 cross-references §11; the op-name set for an effect is the constructor set
