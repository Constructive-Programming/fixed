# L5 — Cap-generator type-level evaluation termination

**Status:** Open. Pick one option.
**Affects:** spec/type_system.md §6.3 (cap-generating sugar), spec/properties.md §5/§6, the typer.

## Problem

Type-checking signatures with cap-generator applications requires evaluating their value-args at compile time:

```
fn use_it(x: u64 is weird(loop_const(5))) -> u64 = x
```

If `loop_const(5)` involves `fold` over recursive data, the typer must evaluate it. What's the termination story?

## Options

### Option 1 — Restrict to literals + in-scope bindings only

Cap-generator value-args must be literal expressions (`0`, `"abc"`) or in-scope bindings (`lo`, `hi`). No function calls.

**Pros:** trivial to implement. Always terminates.

**Cons:** cannot pre-compute bounds (`between(0, 2 * config.max)` is forbidden). For any computed bound the user must lift the computation to a `let` binding above the signature, then pass the binding.

### Option 2 — Bounded evaluation depth

Evaluate up to depth N (e.g., N=64). On exceeded depth, emit `error[E???]: cap-generator value-arg evaluation exceeded depth N`.

**Pros:** pragmatic; allows reasonable computed bounds.

**Cons:** boundary error is hard to explain to users. Different depths in different builds (e.g., debug vs release) could affect what compiles.

### Option 3 — Totality check on arg expressions

Every value passed to a cap-generator must be either (a) a literal, (b) an in-scope binding, or (c) a call to a function whose body is provably *total* (no recursion via fn-call; recursion only through data folds; no loops).

This is the same totality requirement Fixed already imposes on user-written `fn`s (per the totality decision in C1: functions in Fixed cannot self-recurse, all recursion via fold/unfold). So in the limit, cap-generator args can be *any* function call — because every fn call in Fixed is already total.

**Pros:** matches the broader totality story. The user can write `between(0, 2 * config.max())` and it just works because `config.max()` is total like every other Fixed fn.

**Cons:** depends on the totality checker actually being implemented (Phase 3). Until then, behavior under arbitrary fn calls is undefined.

### Option 4 — Lazy evaluation with caching

Don't evaluate during type-check; instead, evaluate on demand at prop-verification time and cache. The typer treats `weird(loop_const(5))` as a symbolic cap until verification needs the value.

**Pros:** doesn't force eager evaluation; allows complex expressions.

**Cons:** still doesn't bound termination (the verifier itself might loop). Pushes the problem to a different phase.

## Recommendation

**Option 3, with Option 1 as a v0 fallback.**

- v0 (parser through early typer): require literals + bindings only (Option 1). This is enough for every example in the corpus today.
- v1 (when totality checker lands in Phase 3): relax to Option 3. The totality checker on fn bodies makes every fn call safe to use as a cap-generator arg, because totality guarantees termination.

Rationale:
- The eventual right answer is Option 3 — it's a single uniform totality story across the language. Cap-generator args are no different from any other fn call.
- Option 1 is a strict subset of Option 3's behavior, so promoting from v0 to v1 is purely a relaxation (no breaking changes).
- Avoids the boundary cliff of Option 2.
- Avoids the deferred-undefined of Option 4.

Spec edits:
- `type_system.md` §6.3: add "Termination of cap-generator value-args."
  - v0 rule: literals + in-scope bindings only.
  - v1 rule (target): any expression whose evaluation is total per the function-totality rule. Forward-reference to the totality spec when written.
- Add an OQ entry tracking the v0→v1 promotion gate (linked to Phase 3 totality checker delivery).
