# Phase 3.4 — Quantities and lifetimes (M10)

**Status:** 3.4.M1 done. M2–M4 wait on decisions D1–D5 (§8)
**Parent:** `docs/plans/phase-3-typer.md` §1.3 (Part B, M10) and §5.7
**Runs:** after M7 and the Phase 4.0 slice, **before M9**
**Spec:** `spec/quantities.md` §3–§9 (semiring, Rules Q5.1–Q5.13, Q6.1, Q7.1),
`spec/memory_management.md` §3, §5, §13.1, §14.1, §18, §19, §20
**Measured against:** `docs/baselines/runtime-2026-11-19.md`

## 1. Goal

After M10, every binding and every function of a checked unit has the record of
`spec/memory_management.md` §20, and the backend uses the part of it that changes the baseline.

1. **Every binding has a quantity** (0, 1 or ω) by Rules Q5.1–Q5.12.
2. **`resume` is checked.** `E072` for two `resume`s on one path (Rule Q5.11). `E070` (Rule Q6.1)
   is deferred: for inferred quantities it cannot fire, because a binding used at runtime is not
   0. It needs declared-0 bindings (`of` value-params read at runtime, Rule Q6.2), which arrive
   with example `11` once it type-checks.
3. **Every binding has an escape set and a lifetime class** (Rules MM5.1–MM5.3), with a reason
   chain for every class other than `Frame`.
4. **Every function has signature facts** (Rule MM20.2): per-parameter quantity, mode (borrowed or
   owned) and escape; result destination; `fip` level.
5. **`fip fn` is checked** (Rule MM18.1, `E110`).
6. **`fixed compile --explain-memory [--format json]` prints the record** (§19).
7. **The backend consumes the record** (decision D1): erased parameters disappear, borrowed
   parameters lose their `dup` / `drop`, statically unique cells are reused or freed without a
   count test. The four baseline numbers are re-measured and committed.

## 2. Non-goals

- **`Region`, cohesive groups, packed blobs, growable-stack count N** (Rules MM5.4, MM6, MM16). No
  runtime exists to consume them before Phase 4 region lowering. M10 classifies such values as
  `Heap` with the reason `region candidate` (decision D3).
- **Lanes and fork decisions** (Rule Q5.13). No backend forks yet. Rule Q5.13.b.1 makes forking
  quantity-neutral, so nothing in M10 depends on it. The memory report says `fork: not
  implemented` per fold.
- **`Frame` placement in the backend.** The data stack, destinations and witnesses are Phase 4
  region lowering. M10 computes `Frame` and `Dest` classes and reports them. The backend still
  allocates them on the heap.
- **Linear effects** (Rule Q7.4). Contingent on OQ-Q5.
- **Quantity polymorphism** (OQ-Q4). Each function has one set of facts.
- **Separate compilation.** Signature facts live in the unit's typing, not in an interface file.

## 3. Design

### 3.1 Where the analysis runs: the typed tree, one walk

The parent plan sketches M10 as a Recheck phase with a `QtyEnv`. M6 and M7 replaced their recheck
walks with work inside the typer, because a second traversal roughly doubled per-node cost. M10
keeps a separate walk, for three reasons:

- **Quantities need whole bodies.** A `let`'s quantity is the sum over the rest of the block
  (Rule Q5.1), known only after the block is typed. Unification variables are solved by then, so
  the walk reads final types.
- **Escape facts need every function's facts** (borrow inference, Rule PR8.1, is a fixed point
  over the call graph). That cannot happen while bodies are still being typed one by one.
- **The walk is cheaper than typing.** It reads the type table and the resolution table and
  does no unification.

The walk runs over the same typed trees the backend lowers: the unit's functions and the prelude
function bodies, after `Declarations.check`. It is a new phase object, `QuantityPhase`, run by the
compiler after `TyperPhase` when the typer reports no errors.

The alternative, analysing Core after lowering, is simpler for escape facts (ANF, monomorphic),
but loses source spans for `E070` / `E110`, runs after M9 should have used the result, and would
have to be redone for separate compilation. It is rejected.

### 3.2 Output: a side table keyed by binder

`QuantityTable`, held in `CompilationUnit` next to `typing`:

- `binding: IdentityHashMap[Tree, BindingFacts]` keyed by the binder tree (`Param`, `LetStmt`
  pattern binder, lambda parameter, match-case binder). `BindingFacts(quantity, escape: Set[Escape],
  cls: LifetimeClass, unique: Boolean, reasons: List[Reason])`.
- `signature: Map[String, SignatureFacts]` keyed by function name, as `Lower.decls` is.
- `folds: IdentityHashMap[Tree, FoldFacts]` per `fold` / `unfold` call: garbage-free carry and
  the fork status.
- `handlers: IdentityHashMap[Tree, HandlerKind]` per `handle`.

The backend looks binders up by tree identity, the same way it reads types today. Lowering keeps
the binder tree for each Core variable it creates from one.

### 3.3 Quantities (Rules Q5.1–Q5.12)

The walk returns, per expression, a usage map `Map[Binder, Quantity]`:

- **Sequencing and arguments add** (Rules Q5.1, Q5.10). A call scales each argument's usage by the
  callee parameter's quantity (§9).
- **Branches join** (Rule Q5.9): `if`, `match` cases, `handle` clauses.
- **Closures multiply** (Rule Q5.7). The closure's own quantity is not known when its body is
  walked, so a capture counts as ω unless the closure binding is quantity 1. That needs the
  closure's uses first: closures bound by `let` are walked after the rest of the block. A lambda
  passed directly as an argument takes the callee parameter's quantity; derived `fold` / `unfold`
  / `map` call their function arguments once per element, so ω.
- **Pattern binders** follow Rule Q5.1 within their case (Rule Q5.8).
- **Prop bodies are skipped** (Rule Q5.5).
- **`of` value-params and phantom type params are 0** (Rules Q5.3, Q5.4).
- **Recursion.** A self-call scales by the parameter quantities of the function being inferred.
  Start every parameter at 0 and iterate to the fixed point. The lattice has height 3, so this
  terminates in at most 3 rounds per strongly connected component.
- **Derived and generated members** (Field, CapMember, DerivedFold resolutions; prelude bodies) get
  their facts from a fixed table in `QuantityPhase` (the receiver of `fold` is 1, the function
  arguments ω, a field read borrows its object).
- **Default-bodied cap methods** (Rule Q5.12) are walked once at ω skeletons. Re-checking at each
  satisfying type waits for `satisfies` method bodies to be lowered (slice §9 open item).

Unused bindings get a warning `T021` unless the name starts with `_` (Rule Q5.1). Examples
`01`–`06` raise none. `07`–`11` stop at typer errors before this phase runs.

**Arguments are evaluated once** (finding of 3.4.M1, decision D5). Read literally, §9 scales every
binder in an argument by the parameter's quantity. Then in §10.1's `go(i - 1, b, a + b)` the
binder `a` would be ω, because `a + b` goes to `go`'s ω parameter `b`; but §10.1 says `a` is 1.
Fixed is strict, so `a + b` is computed once and `a` is used once. The walk scales only a binder
passed as itself, and a lambda argument's captures. Quantity 0 erases the whole argument. This
gives both §9's example (`pair_double(y)`: `y` is ω) and §10.1's (`go` is `(ω, 1, ω)`).

**Cost.** Measured with `QuantityCpuBench` (thread CPU time, examples `01`–`06`): the walk costs
25.4% of typer time (108 µs against 424 µs). Integer binder ids and callee-first walking took it
there from 296%.

### 3.4 Uniqueness: a gap in the spec

Rules Q7.2, PR6.3 and MM3.2 say a quantity-1 binding may be reused "unconditionally" and "freed
on consume". Quantity 1 says the *binding* is used once. It does not say the *value* has no other
owner:

```
fn second(xs: List of u64) -> List of u64 =
    match xs:
        List.Cons(_, t) => drop_head(t)     // t is quantity 1
        List.Nil => List.Nil
```

If the caller still holds `xs`, then `t` is shared, and reusing `t`'s cell in `drop_head` without
a count test corrupts the caller's list. This is the difference between linearity and uniqueness
(Marshall, Vollmer and Orchard, "Linearity and Uniqueness: An Entente Cordiale", ESOP 2022).

M10 therefore computes a separate **unique** flag. A quantity-1 value is unique iff it comes from:

- a constructor application or a literal;
- a call result (results are owned and fresh, Rule MM7.1);
- a parameter whose caller passes unique arguments at every call site, or a `fip` function's
  quantity-1 parameter (callers evacuate otherwise, Rule MM18.3);
- a field of a unique value that is consumed by the same `match`.

Unconditional reuse and count-free freeing apply only to unique values. Other quantity-1 values
keep Perceus's runtime `rc == 1` test. Decision D2 asks to amend Rules Q7.2, PR6.3 and MM3.2 to
say this.

### 3.5 Escape sets and classes (Rules MM5.1–MM5.3, MM20.1)

The same walk records, per binder, where its value flows:

- `result` when it reaches the function's result;
- `field(b)` when it is stored into a value bound to `b`;
- `closure(c, escapes?)` when captured, with `escapes?` from `c`'s own escape set;
- `continuation(h, escapes?)` when reachable from a capturing clause of `h`;
- `carried(loop)` for the seed of an `unfold` and the accumulator of a `fold`;
- `unbounded` when passed to a parameter whose escape is `unbounded`.

`field(b)` and `closure(c, true)` propagate: the value escapes wherever `b` or `c` does. Classes
then follow Rule MM5.3 in order: `Erased`, `Static`, `Frame(s)` / `Dest(result)`, and `Heap` for
the rest. Every `Heap` gets the reason chain that sent it there.

**Garbage-free carry** (Rule MM5.2(b)) is checked at constructor level for each `fold` / `unfold`:
the step must build its new carried value from the cells of the old one, or from new cells reachable
from it. The corpus folds mostly carry scalars, which are trivially garbage-free.

**Handler kinds** (Rule MM14.1) come from the clause shapes the typer already checked (M7):
abortive, tail-resumptive, capturing-local, capturing-escaping.

### 3.6 Signature facts and borrowing (Rules MM20.2, PR8.1)

A parameter is **borrowed** iff the body never stores it, never returns it, and never passes it to
an owned parameter (Rule PR8.1). This is a fixed point over the call graph: start with every
parameter borrowed, demote on evidence, repeat until nothing changes. A parameter that is the
subject of a reuse is owned (Lean's rule). Escape per parameter is `none`, `into-result` or
`unbounded`.

### 3.7 `fip` (Rule MM18.1)

With classes and uniqueness known, `fip fn` is checked item by item. Items 1–4 read the facts of
the function and everything it calls. Item 5 (bounded scratch) counts `Frame` allocations outside
fold steps. `fip(strict)` adds "no scratch" and "every fold is a loop" (list-shaped folds only).
Violations are `E110` with the reason chain. A reused parameter that is not quantity 1 is `E071`
(Rule MM18.3).

### 3.8 Backend consumption (decision D1)

Three changes, each small, each measured:

1. **Erasure.** Quantity-0 parameters and bindings are not lowered. Call sites drop the argument.
2. **Borrowed parameters** (Rule PR8.2). No `dup` at the call site, no `drop` at callee exit. `Rc`
   gains a set of borrowed parameters that it never owns.
3. **Static uniqueness.** A `Field` read from a unique, consumed value moves the field instead of
   `dup` + later `drop` of the parent. The parent's cell is then freed without dropping its
   children, or reused by a constructor of the same layout in the same branch (Rule PR6.1, drop
   guided per Rule MM11.5).

Expected effect, before measuring: `bintrees` and `list_pipeline` lose most `dup`s, since every
node is read once by the derived `fold` and is unique when built by `unfold`. `records` gains
reuse (`discount` rebuilds an `Order` from a unique `Order`). Allocations fall only where reuse
fires. Wall time is a hypothesis.

Runtime reuse for non-unique values (`rc == 1` tests) and drop specialisation stay in the Phase 4
Perceus inserter.

## 4. Milestones

| # | Content | Done when |
|---|---|---|
| 3.4.M1 ✅ | `QuantityPhase`, `QuantityTable`, quantities (§3.3), `E072`, `T021` | `QuantityPhaseSuite`: §10.1's `go` is `(ω, 1, ω)`, one test per rule; examples `01`–`06` raise no new diagnostics |
| 3.4.M2 | Uniqueness (§3.4), escape sets, classes, reason chains, garbage-free carry, handler kinds (§3.5), signature facts and borrowing (§3.6) | Suite tests per escape kind and class; every example gets a full record; no binding without a class |
| 3.4.M3 | `--explain-memory`, `--format json` (§19); `fip` check, `E110`, `E071` (§3.7) | Golden report for `01`; `fip` accept / reject tests per Rule MM18.1 item |
| 3.4.M4 | Backend consumption (§3.8); new baseline record | Goldens and leak checks still pass; `docs/baselines/runtime-<date>.md` with the four numbers per program and the difference to the 4.0 baseline |

Each milestone ends with the full gate (tests, lint, duplicate detector) and the typer benchmark.
The walk must not add more than 25% to typer time on the corpus; if it does, the exit ramp of
`phase-3-typer.md` §4.1.2 applies (fold into the typer's final pass).

## 5. Tests

- `QuantityPhaseSuite`: per rule of §3.3, per escape kind, per class, per handler kind, per `fip`
  item; each from a short Fixed snippet, asserting facts by binder name.
- `EmitSuite` (existing): goldens and leak checks catch any unsound borrow or reuse. Add one
  program per §3.8 change that fails if the change is unsound (a shared list whose tail is passed
  to a reusing function).
- Corpus: `examples/01`–`11` produce no new errors.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Unsound uniqueness frees a shared cell | §3.4 rules are conservative; the leak checker plus `FIXED_RT_STATS` double-free detection (add a freed-cell poison in debug builds) |
| Closure quantities need the closure's uses first, which reorders the walk | Walk `let`-bound lambdas after the block's remainder; lambdas in argument position use the parameter's quantity |
| Generated Core members (fold, map) have no typed tree to walk | Fixed fact table (§3.3), checked by the leak checker on every benchmark |
| The 25% budget is exceeded | Exit ramp, §4 |

## 7. Spec amendments this plan needs

- `spec/quantities.md` Rule Q7.2 and `spec/memory_management.md` Rules PR6.3, MM3.2: "quantity 1"
  becomes "quantity 1 and unique" (D2).
- `spec/memory_management.md` §20.1: cohesive groups and lane facts are recorded by the phase that
  first consumes them (D3).
- New codes: `E072` (two `resume`s on one path), `T021` (unused binding). `E070`, `E071`, `E110`
  already exist in the spec.
- `spec/quantities.md` §9: an argument expression is evaluated once; only a binder passed as itself
  and a lambda argument's captures scale by the parameter's quantity (D5).

## 8. Decisions for review

- **D1 — Backend consumption inside M10.** Recommended: yes, the three changes of §3.8, so the
  baseline moves at M10. Alternative: M10 is analysis only and Phase 4 consumes it.
- **D2 — Uniqueness separate from quantity 1** (§3.4). Recommended: yes, amend the three rules.
- **D3 — Defer `Region`, cohesive groups and lanes** to the phase that consumes them. Recommended:
  yes.
- **D4 — Typed-tree walk after the typer** (§3.1), not inside it and not on Core. Recommended: yes.
  3.4.M1 is built this way.
- **D5 — Strict argument scaling** (§3.3). Recommended: yes, amend §9 as in §7.
