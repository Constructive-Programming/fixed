# L6 — Linear effect handler arm capturing a quantity-1 outer binding

**Status:** Open. Pick one option. **Contingent on OQ-Q5** (whether effect rows participate in QTT quantity).
**Affects:** spec/effects.md §3.5 (Proposal), spec/quantities.md Rule Q7.1, spec/perceus.md §9.

## Problem

```
let lock_token = make_token()        // quantity 1
handle (do: ... Lock.release ...):
    Lock.release() =>
        consume(lock_token)          // captured from outer scope
        resume(())
```

Lock is a `linear effect` — its `release` op is at-most-once per code path. The arm body captures `lock_token`, a quantity-1 outer binding. Per Rule Q7.1, quantity-1 bindings cannot be captured by a quantity-ω closure. Is the arm body quantity 1 or quantity ω?

## Options

### Option 1 — Linear-effect arm bodies inherit quantity 1

Arm bodies of a linear effect run at most once (because the effect itself is at-most-once per Rule E3.5.c). Capturing a quantity-1 outer binding into such an arm is therefore sound — total usage across outer scope + arm body ≤ 1 per the at-most-once invariant.

**Pros:** correct under the proposed semantics of linear effects. Matches Idris 2 + linear-types-with-effects research. Enables natural resource-acquisition patterns (lock token consumed in release arm).

**Cons:** requires OQ-Q5 to close in favor of "effect rows do participate in quantity." Only sound if the typer enforces ≤1 dispatch on linear effects.

### Option 2 — Forbid capture across handler-arm boundary

Arm bodies are conservatively quantity ω (closures, Rule Q5.7's multiplication rule). Capturing quantity-1 outer bindings always errors regardless of effect linearity.

**Pros:** simplest soundness story; doesn't depend on OQ-Q5.

**Cons:** rules out a common idiomatic pattern. Workaround (move the resource into the handler subject) is awkward.

### Option 3 — Require explicit annotation

The arm body declares its own linearity:

```
linear Lock.release() => consume(lock_token); resume(())
```

**Pros:** explicit, no inference needed.

**Cons:** new syntax, repeats info already on the effect declaration.

## Recommendation

**Option 1, contingent on OQ-Q5 closing in favor of effect-row quantity.**

If OQ-Q5 closes the other way (effect rows do *not* carry quantity), default to **Option 2**.

The choice is fundamentally tied to OQ-Q5: there is no consistent design where linear effects encode at-most-once *and* arm-body quantities don't pick that up.

Spec edits if Option 1 chosen:
- `quantities.md` add Rule Q7.x: "A handler arm body for a linear effect has quantity 1. It may capture quantity-1 outer bindings; the captured binding's contribution to outer-scope quantity is the join of arm-body usage and outer-scope usage, both bounded above by 1."
- `perceus.md` §9: clarify that quantity-1 captures into linear-effect arm bodies generate no `dup`.
- `effects.md` §3.5 (proposal section): add a "linear arm" example with capture.

Spec edits if Option 2 chosen (fallback):
- `quantities.md` Rule Q7.1: add "Handler-arm bodies are quantity-ω closures regardless of effect linearity. Capture of quantity-1 outer bindings into any handler arm is an error."
- `effects.md` §3.5: add note that quantity-1 outer bindings cannot flow into linear-effect handler arms; users must move the binding into the SUBJECT or use a different idiom.
