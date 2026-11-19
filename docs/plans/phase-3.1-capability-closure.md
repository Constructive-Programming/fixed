# Phase 3.1 — Capability closure (M6)

**Status:** Approved v0.2 — in progress
**Parent:** `docs/plans/phase-3-typer.md` §1.3 (Part B, M6) and §5.3
**Spec:** `spec/type_system.md` §6.1–§6.2 (members, `extends`), §7.4 (auto-derivation), §8
(`satisfies`), §10.3–§10.4 (`is`-bound flow, subtyping)
**Closes these Part A gaps** (`docs/Phase3Conformance.md`):
- an `is ...` bound or a capability used as a type accepts any type;
- `Numeric` reached through `extends`;
- `satisfies` declaration bodies and mappings, and cap method bodies, are not typed.

## 1. Goal

Make capabilities mean something. After M6:

1. **Every capability has a closed set.** `closure(C) = {C} ∪ closure(D)` for each `D` that `C`
   extends (§6.2).
2. **Every `is C` bound a value flows into is checked.** Some satisfaction must bind the value's
   type to `C`: directly, through `extends`, through auto-derivation, or through a type
   variable's own bound (§10.3).
3. **Every `T satisfies C` declaration is checked:**
   - it covers `C`'s constructor requirements (§8.1);
   - its mappings fit the requirements' signatures;
   - `C`'s abstract methods are provided by the declaration, a default body or auto-derivation.
4. **`Cap.ctor(args)` resolves to a concrete constructor** through the expected type (§8.5),
   including the refinement strip of §8.5.1.
5. **Cap default bodies and `satisfies` method bodies are typed.** This is Rule 6.1.1, phase 1:
   against an abstract `Self`.

## 2. Non-goals

- Effects and quantities in the per-satisfaction recheck (Rule 6.1.1 phase 2) belong to M7 and
  M10.
- Prop obligations of refinement caps belong to M9. M6 only strips refinements and records them.
- Representation-level derivability belongs to M11. Example: whether `is Functor` can `show` is
  decided by the chosen representation. M6 keeps the Part A assumption for capability-bounded
  receivers and checks derivability only for concrete `data` types (fields satisfy the cap).
- Modular `use T satisfies C` across files. v1 is single-module: the prelude's and the unit's
  own `satisfies` declarations are in scope.
- Examples `07`–`11` beyond what M6 itself fixes. Their remaining errors are missing stdlib
  functions, auto-derived `para`, single-constructor data used as a value, struct literals and
  props. That is a separate corpus milestone.

## 3. Design

### 3.1 Where checks run: obligations, not a second tree walk (decided)

Parent plan §4.3 sketches M6 as a recheck phase that re-walks the typed tree. Two facts argue for
a different shape:

- **Satisfaction depends on inference.** At the moment a value meets an `is C` bound, its type is
  often an unsolved variable. A satisfaction check needs the *solved* type, and the solution
  arrives later in the same body.
- **Member resolution already depends on capabilities.** Body typing consults the same tables.
  Splitting the checks into a later walk would type every node twice. The tuning rounds showed
  per-node cost is the typer's main expense.

Proposal:

- **Record obligations during body typing.** Wherever unification today accepts a value against
  an `is ...` bound or a capability-as-type, record an obligation `(type, capability, span)` in
  `TyperState`. Unification still succeeds structurally, so inference is not blocked.
- **Discharge them in a capability-closure pass after the unit is typed.** Each obligation's type
  is zonked and checked against the satisfaction resolver (§3.3). Failures are `T010` *does not
  satisfy*. A type still unsolved at discharge is checked against its variable's recorded bounds.
- **Check declarations in the same pass.** `satisfies` declarations and cap bodies need no tree
  walk at all.

The parent plan's recheck contract (a fresh `TypeMap` per phase) is kept for the passes that
genuinely refine types (M10 quantities, M11 representation). M6 does not refine recorded types; it
only adds diagnostics and a resolved-constructor side table.

**Alternative:** the planned recheck walk. That is a second traversal, with each `CapRef` and
bound re-derived from the typed tree. It is simpler to describe, but it costs one more full pass,
and it cannot see the solved type of a variable without re-running inference.

### 3.2 Closure and member tables

`MemberTables` (M5 tuning) already flattens each capability's members over `extends` and aliases.
M6 adds `closure(C)`, the capabilities `reached` visits, memoized beside the tables. It also adds
the diamond check of Rule 6.2: the same member name inherited from two parents with incompatible
signatures, and not redeclared, is `T013`.

### 3.3 Satisfaction resolver

`satisfies(T, C)`, for a solved type `T`:

| `T` | Holds when |
|---|---|
| `data D of ...` | an in-scope `D satisfies C'` with `C ∈ closure(C')`, or `C` is auto-derivable for `D` (§3.4) |
| primitive | a prelude `prim satisfies C'` with `C ∈ closure(C')`; numeric primitives satisfy `Numeric` |
| type variable `N` | `C ∈ closure(B)` for some `B` in `N`'s bound |
| `is B1 + ...` / capability `B` | `C ∈ closure(Bi)` for some `i` |
| tuple | as `Tuple of (A, B)` |
| `!` | always |
| function type | never, unless `C` is auto-derivable for functions (none in v1) |

Arguments matter. For example, `List of u64` satisfies `Folding of u64`, but not `Folding of
String`. The resolver unifies the obligation's capability arguments with those the satisfaction
provides; a mismatch is `T010` with both sides shown.

### 3.4 Auto-derivation (Rule 7.4)

| Capability | Derived for `data D` when |
|---|---|
| `Folding` | `D` has at least one variant (7.4.a) |
| `Functor of Xn` | 7.4.c: the rightmost type parameter is non-phantom, covariant and never contravariant |
| `Show`, `Eq`, `Clone` | every field type satisfies it (recursive fields assumed) |
| `Ord` | every field type satisfies it (lexicographic) |
| field accessors | already done in Part A (7.4.d) |
| `para`, `unfold` | members, not capabilities; `para` is in the corpus milestone |

Rule 7.4.f also applies: a capability with `Self.fn` constructor requirements is never
auto-derived; it needs an explicit `satisfies`.

### 3.5 `satisfies` declaration checks

For `T satisfies C`, mapped through `closure(C)`:

- **Rule 8.1.a:** each `Variant as req` names a variant of `T` and a `Self.fn` requirement
  somewhere in the closure. The variant's field types must unify with the requirement's parameter
  types, with `Part` bound to `T`'s element type.
- **Rule 8.1.b:** every `Self.fn` requirement is mapped exactly once, or mapped `impossible`.
  - A missing requirement is `T010` "type T does not satisfy C: missing ctor".
  - A duplicate is `T014`.
- **Abstract methods:** each must come from the `satisfies` block (§8.3), the capability's default
  body (Rule 6.1), or auto-derivation (§3.4). A missing method is `T010` "missing method".
- **Method bodies** in the block are typed against the requirement's signature, with
  `Self = T` (§3.6).

### 3.6 Bodies

- **Cap default bodies** (Rule 6.1.1 phase 1): `self: Self` is abstract. Members resolve through
  the capability's own table. `Part` is an opaque element type.
- **`satisfies` method bodies:** `self: T`, `Self = T`, and the return type comes from the
  requirement. Part A's `Self` / `Part` substitution already does most of this. The new piece is
  a `Context.self` that member lookup consults for `SelfType` receivers.

### 3.7 Constructor resolution (§8.5)

`Cap.ctor(args)` with an expected type:

1. Strip refinement bounds (§8.5.1).
2. Collect the in-scope satisfactions whose type fits the expected type, and that map `ctor`.
3. The result depends on how many there are:
   - **exactly one:** record `(call tree → variant)` in a resolved-constructor side table (for
     codegen) and type the call as that variant;
   - **none:** type the call as today (an anonymous `is Cap of ...`) and record nothing. The value
     stays abstract until representation selection (M11);
   - **several:** `T011` *ambiguous satisfaction*, listing the candidates.

## 4. Diagnostics

| Code | Meaning |
|---|---|
| `T010` | type X does not satisfy C (missing satisfaction, constructor or method; argument mismatch) |
| `T011` | ambiguous satisfaction of C for a constructor call |
| `T013` | incompatible member inherited from two parents (Rule 6.2) |
| `T014` | duplicate or unknown mapping in a `satisfies` block |

## 5. Milestones

| # | Milestone | Verification |
|---|---|---|
| 3.1.M1 ✅ | Closure and satisfaction resolver; transitive `Numeric` | Unit tests of `satisfies(T, C)` over the stdlib and corpus types |
| 3.1.M2 ✅ | Obligations during typing, discharged after the unit; the `is`-bound and capability-as-type leniency removed from the unifier | `01`–`06` still clean; negative tests (`String` into `is Numeric`, `List of String` into `is Folding of u64`) report `T010` |
| 3.1.M3 ✅ | `satisfies` declaration checks (coverage, mappings, abstract methods, diamond) | Corpus `satisfies` blocks pass; negative tests for each rule |
| 3.1.M4 | Cap default bodies and `satisfies` method bodies typed | `03`'s `Maybe satisfies Optional` bodies type-check; a bad body reports against the declaration |
| 3.1.M5 | Constructor resolution with refinement strip | `Optional.some(x)` against a `Maybe`-backed expected type resolves; an ambiguity reports `T011` |

Perf: `TyperCpuBench` A/B at each milestone. Obligation discharge must stay under 10% of typer
time.

## 6. Open questions

1. ~~Recheck walk or obligations (§3.1).~~ **Decided:** obligations.
2. ~~`Show` / `Eq` / `Clone` on capability-bounded receivers.~~ **Decided:** keep the Part A
   assumption until M11; concrete `data` types are checked field by field.
3. **User redeclarations of prelude capabilities.** `02` and `03` declare their own `Optional`.
   A user declaration shadows the prelude one, so the prelude's `List satisfies Folding` names
   the prelude `Folding`, not a user one. Proposal: satisfactions are keyed by capability symbol,
   so shadowing is exact, and examples that redeclare stdlib caps keep working on their own
   declarations.

## 7. Implementation notes

- **3.1.M1:** `MemberTables.closure`; `Satisfaction.holds(s, t, cap, args)`, where arguments
  unify with the satisfying type's trailing arguments; auto-derivation per §3.4.
- **3.1.M2:**
  - The unifier records `(value, bound)` when an *expected* `is ...` bound or capability meets a
    value; `Typer.solve` tags it with the span; `TyperState.finish` discharges after literal
    defaults.
  - A variable that already has a bound, or is a numeric literal, gets an obligation instead of
    being bound to the expected bound, which would forget its own constraints.
  - Still lenient, until constructor resolution (3.1.M5): an *abstract* value met where a
    concrete type is expected.
  - Cost: thread-CPU A/B against 3.1.M1 is about +1.5%.
- **3.1.M3:**
  - `Declarations.check` runs after body typing:
    - mappings name constructor requirements the capability *itself* declares (inherited
      requirements are covered by their own satisfactions or derivation, so `Maybe satisfies
      Optional` need not map Monad's `pure`), at most once (`T014`);
    - constructor mappings' fields unify with the requirement's parameters (`T010`);
    - missing requirements are `T010`;
    - Rule 6.2 diamonds are compared by parameter count (`T013`).
  - Abstract-method coverage is deferred until bodies are typed (3.1.M4): auto-derivation of
    `map` / `flat_map` / `pure` in the corpus goes beyond Rule 7.4 as written.
  - Prelude: `Sequencing` gains `Self.fn empty`; `List satisfies Sequencing` and
    `Tuple satisfies Pair` gain their mappings (`spec/stdlib.md` §5).
  - `03`'s `Maybe satisfies Optional` gains `Just as some` / `Nothing as none`, which Rule 8.1.b
    requires now that its `Optional` declares them.
  - Real findings in the out-of-scope examples, each a Rule 8.1.a mismatch:
    - `08`: `SimpleQueue as empty` maps a 1-field constructor to a 0-parameter requirement;
    - `09`: `VarMap as empty`, the same;
    - `11`: `NonEmptyList as cons` — its `tail: List of A` does not fit `cons`'s `Self`.
