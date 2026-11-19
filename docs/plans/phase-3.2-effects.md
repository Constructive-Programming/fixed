# Phase 3.2 — Effect inference (M7)

**Status:** Draft, for review
**Parent:** `docs/plans/phase-3-typer.md` §1.3 (Part B, M7) and §5.4
**Spec:** `spec/effects.md` §3.2–§3.3 (operations, abortive `!`), §4.1–§4.4 (rows), §5 (inference,
`with` check, handler subtraction and coverage, `main`), §6.3–§6.6 (handlers)
**Closes these Part A gaps** (`docs/Phase3Conformance.md`):
- `with` clauses are parsed and resolved, but never checked against the body;
- `handle` removes nothing and checks no coverage;
- `resume` is accepted in a clause for an abortive (`-> !`) operation.

## 1. Goal

Make `with` clauses mean something. After M7:

1. **Every body has an effect row.** It is the union of what the body performs (Rule E5.1):
   - effect operations;
   - calls of functions, methods and function-typed values whose type carries a row;
   - the rows that `handle` blocks let through.
2. **Every declared row is checked (Rule E5.2).**
   - An effect that the body performs but the declaration omits is an error.
   - An effect that the declaration names but the body never performs is a warning.
3. **`handle` subtracts what it handles (Rule E5.3).** Its clauses cover every operation of each
   effect they name. Its own row is the rest of the subject's row plus the rows of the clause
   bodies (Rule E6.6).
4. **`resume` respects abortive operations (Rule E3.3).**
5. **`main` performs only runtime effects (Rule E5.4).** Its row must be a subset of the stdlib
   effect set: the effects `std.io` declares.

## 2. Non-goals

- **Explicit row variables** (§8.1–§8.2, `EffectRow.rest`). Neither the corpus nor the stdlib
  writes one. Section 3.2 covers the higher-order case they exist for.
- **Effect aliases** (§4.5, `E084`). No example declares one.
- **Linear effects** (§3.5). These are non-normative until OQ-Q5 closes.
- **Evidence passing** (§7). That is Phase 4.
- **One coverage algorithm for match cases and handler clauses** (Rule M6.4). That is M8'. M7
  checks handler coverage per effect.
- **`resume` quantity** (Rule Q5.11). That is M10.
- **Recorded rows for codegen.** Beyond a lambda's row in its `ArrowType`, nothing is recorded
  until Phase 4 names a consumer.

## 3. Design

### 3.1 Where rows are computed: during body typing, not a second walk

The parent plan (§5.4) sketches M7 as a recheck phase. M6 replaced its own recheck with
obligations, and the same two facts apply to M7:

- **Rows need inference.** In `Fail.fail(e)`, the effect `Fail of ?a` is only known once `e`'s
  type is solved. The same holds for a lambda's row and for a method's row. The body typer
  already computes all of these.
- **Each node costs.** Another full traversal would roughly double the per-node cost that the M5
  tuning rounds measured as the typer's main expense.

Proposal:

- **Accumulate rows in `TyperState`.** `TyperState` gains `row: List[(EffectBound, Span)]`, the
  effects the current body has performed so far.
  - Typing a call adds the callee's row.
  - Typing an effect operation adds its effect, with the arguments it was instantiated with.
  - Lambdas, nested `fn`s and `handle` subjects save the outer row, type with an empty row, then
    restore the outer row.
- **Check rows when the unit finishes.** At the end of a body, record a row check: the inferred
  row, the declared row, the owner and the span. `TyperState.finish` runs the checks after literal
  defaults, next to M6's obligations, so every effect argument is solved by then.
- **Keep `with` declarations authoritative.** A call adds the callee's *declared* row. No
  signature is inferred, so recursion needs no fixpoint. Only lambdas have inferred rows.

**Alternative:** the planned recheck walk. It is a second traversal that re-derives each callee's
type from the typed tree. It costs a pass and gives the same answer.

### 3.2 Function-typed parameters without `with`: ambient pass-through (to decide)

The stdlib writes no row variables. Example: `fn fold(on_some: Part -> R, on_none: () -> R) -> R`.
Read literally (§4.3), those callbacks are pure. Then `03`'s `inner` could not pass
`(e) -> Fail.fail(e)` to `fold`. Spec §10.1 says this example type-checks because `fold` "takes
the row of its second argument". That is effect polymorphism without a written variable.

Proposal (Rule E5.5.a, new, Frank-style ambient effects):

- **A lambda passed where the parameter's arrow declares no `with` contributes its row to the
  enclosing body, at the call.** The callee never sees the effect, and the caller must declare or
  handle it. This is what an implicit row variable shared by the callback and the callee would
  give, without the variable.
- **A parameter arrow that declares a row checks the lambda against it** (Rule E4.4, sub-row).
  The lambda's row does not reach the caller. For example, `08`'s
  `run_scheduler(task: () -> () with Async)` handles `Async` itself.
- **Calling a value of function type adds the row in its type.** Inside `fold`, calling
  `on_some(x)` adds nothing, because the caller has already accounted for that row. Calling
  `task()` adds `Async`.
- **A lambda's type records its row.** A lambda bound by `let` and called later then adds its row
  at the call.

**Known gap:** a callback stored and invoked after the callee returns. Example: a queue of
closures that a handler drains. The row moves to the caller at the call where the callback was
passed, which is earlier than the effect runs. This is sound when the effect happens in the same
dynamic extent. Explicit row variables (§8) would close the gap when an example needs it.

**Alternative:** implement §8's explicit row variables now and annotate every higher-order stdlib
member. That needs grammar and stdlib changes and adds a third kind of unification variable, and
nothing in the corpus needs it.

### 3.3 Effect operations and identity

- `E.op(args)` adds `E of (as)`, where `as` are the fresh variables that `Members.static` creates
  for `E`'s `of` parameters, plus the operation's own `with` row, if any.
- **Identity follows Rule E4.2: symbol × arguments.**
  - Rows are deduplicated at check time, after zonking.
  - `Fail of String` and `Fail of i64` are distinct entries.
- **Sub-row check (E4.4):** every inferred entry must equal some declared entry with the same
  symbol, comparing arguments by unification. A declared entry that matches no inferred entry is
  the unused-effect warning.

### 3.4 `handle`

Typing `handle subject: clauses`:

1. **Type the subject with an empty row.** Call the result `R_s`.
2. **Bind each clause `E.op(ps)` to the entry in `R_s` with symbol `E`.**
   - The operation's parameter types are instantiated with that entry's arguments. This is new:
     today they are fresh, so `e` in `Fail.fail(e) => Result.err(e)` is untyped.
   - No entry in `R_s`: the clause is instantiated fresh, as today.
   - Several entries with symbol `E`: see open question 2.
3. **Check coverage (E5.3).** Every operation of every effect that a clause names has a clause.
   Missing operations are an error that lists them.
4. **Check `resume` (E3.3).** Inside the clause of an operation whose result is `!`, `resume` is an
   error. Today it type-checks against `!`.
5. **Compute the handle's row (E6.6).** The outer row gains the union of:
   - `R_s` minus every entry whose symbol a clause names;
   - each clause body's row;
   - the `return` clause body's row.

### 3.5 Bodies checked

| Body | Declared row |
|---|---|
| top-level and nested `fn` | its `with` clause |
| `satisfies` method | the implemented member's row, instantiated (Rule 6.1.1 phase 2, effects part) |
| cap default method | the member's own `with` clause |
| lambda | none: its row is inferred (§3.2) |
| `main` | its `with` clause, which must also be a subset of the stdlib effect set (§5.4) |

## 4. Diagnostics

| Code | Spec | Meaning |
|---|---|---|
| `T015` | E080 | effect `E` is performed but not declared or handled (the parent plan's "unhandled effect") |
| `T016` | E5.4 | `main` declares effect `E`, which the runtime does not handle |
| `T017` | E081 | warning: declared effect `E` is never performed |
| `T018` | E093 | handler does not cover operations `E.op, ...` |
| `T019` | E3.3 | `resume` in the clause of abortive operation `E.op` |

The typer keeps its own `T` codes, as M6 did. The spec's `E` code is named in each message's
documentation.

## 5. Milestones

| # | Milestone | Verification |
|---|---|---|
| 3.2.M1 | Rows accumulate for operations, calls, method calls and lambdas (§3.2, §3.3); declared-row check at `finish`; `main`'s runtime set | `01`–`06` stay clean; negative tests for `T015`, `T016` and `T017`; the §3.2 pass-through and declared-row cases each have a test |
| 3.2.M2 | `handle`: subtraction, clause binding to the subject's row, coverage, abortive `resume`, the handle's row | `03`'s `compute` is clean with its effects checked; `08`'s nested handler chain and `09`'s handlers report no effect errors; negative tests for `T018` and `T019` |
| 3.2.M3 | `satisfies` and cap default bodies against member rows; spec Rule E5.5.a; conformance doc | A `satisfies` body performing an undeclared effect reports `T015`; `spec/effects.md` and `docs/Phase3Conformance.md` updated |

**Perf:** `TyperCpuBench` A/B at each milestone. Row tracking must stay under 5% of typer time. Most
calls have an empty row, so accumulation should be close to free.

## 6. Open questions

1. **Ambient pass-through (§3.2).** Proposed: adopt it as Rule E5.5.a. The alternative is
   explicit row variables now (§8). The proposal costs a spec addition; the alternative costs
   stdlib annotations and a new kind of unification variable.
2. **A clause for a parameterised effect when the subject performs several instances**
   (`Fail of String` and `Fail of i64`). Proposed: the clause handles every instance, its
   parameters unify with all of them, and a mismatch is `T002`. That makes such a handler
   ill-typed unless the instances agree. The alternative is a `T011`-style ambiguity error. The
   spec is silent; no example hits this case.
3. **A clause naming an effect the subject does not perform.** Proposed: no diagnostic, because
   the clause is dead code but harmless. The alternative is a warning.
