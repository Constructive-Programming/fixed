# X6 — `result` binder in cap-method postconditions

**Status:** Open. Pick one option.
**Affects:** spec/properties.md §3.3, OQ-P5.

## Problem

`properties.md` §3.3 (resolves OQ7) makes `result` an implicit binder for the return value — but only in fn-body postconditions:

```
fn sort(c: C is Sequencing) -> C is Sorted =
    prop result_same_size: result.size == c.size
    ...
```

In a cap-method postcondition, `result` is currently "a regular identifier" (per §3.3's deferred clause, OQ-P5). But a cap may declare a method with a postcondition:

```
cap Optional:
    fn fold(on_some: Part -> R, on_none: () -> R) -> R
        prop ???   // is `result` available here?
```

Today the spec is silent on the temporary rule. Implementation must commit to something before Phase 3.

## Options

### Option 1 — Implicit `result` binder in any postcondition position

`result` is a contextual keyword in any postcondition position (fn body, cap method, satisfaction's method override, default cap-method body). Always binds the return value of the enclosing method/function.

**Pros:** uniform, easy to teach. One rule, applies everywhere a postcondition can appear.

**Cons:** `result` becomes a *contextual* reserved word. Programmers cannot use `result` as a regular identifier inside any postcondition expression — must rename. Acceptable since postconditions are rare.

### Option 2 — Named return values

User declares a return-value binder explicitly:

```
cap Optional:
    fn fold(on_some, on_none) -> R as r
        prop r_is_R: ...      // explicit binder name
```

Idris-like / Lean-like. The `as` clause names the result.

**Pros:** never reserves an identifier. User picks the name.

**Cons:** verbose. A common pattern (just-call-it-result) takes more syntax. Unfamiliar to most users.

### Option 3 — Defer to per-call-site

`result` binds at each *call site*, not at the cap-method declaration site. The cap-method body can refer to `result`; during prop verification at each call site, `result` is bound to the actual return value of that call.

**Pros:** no static binding; `result` is purely an obligation-side identifier.

**Cons:** prop verification gets harder — verifier must instantiate `result` at every call site. Conflicts with the static-verification goal of `prop`.

## Recommendation

**Option 1.** It's the predictable extension of the current fn-body rule. The cost (a contextual reserved word inside postcondition expressions) is real but small — postconditions are bounded, and any conflict can be renamed locally. Idris 2 and Liquid Haskell both work this way, and the pattern is easy to teach.

Resolves OQ-P5.

Spec edits if Option 1 chosen:
- `properties.md` §3.3: extend "fn-body postcondition" rule to "any postcondition position." `result` is the implicit binder for the return value of the enclosing method/function/lambda.
- Note: `result` is **contextual** — only reserved inside postcondition expressions. Elsewhere it's a normal identifier.
- Mark OQ-P5 resolved in `properties.md` §11.
