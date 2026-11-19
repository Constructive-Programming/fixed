# Phase 3 Part A — typer conformance (v1)

What the base typer (Phase 3 M1–M5) enforces today, and what it does not. A downstream consumer
reading the typer's output should assume nothing outside the "Enforced" list. The rules behind each
item are in `docs/plans/phase-3-typer.md` §3.6–§3.9.

## Status

- Examples `01`–`06` compile with no diagnostics (`CompilerSuite`).
- Examples `07`–`11` still report errors. They use features outside Part A (props, struct literals,
  `forall` / `implies`, satisfies bodies and more) or need the gaps below closed. At M5 they show
  57 errors and 15 `T005` warnings in total.
- Performance: the typer takes ~0.85 ms per corpus pass against ~0.18 ms for the parser. That is
  about 4.8×, against the plan's 2× target. See "Performance" below.

## Enforced

- **Signatures** (Namer): every `fn`, `cap`, `data`, `effect` and `type` declaration and their
  members. Type variables follow Rule 4.1; rebinding an enclosing variable is `T004`, and a
  duplicate top-level definition is `T003`.
- **Name resolution**: values, types, capabilities, effects and variants (`T001`).
- **Body typing** for top-level and nested `fn` declarations against their declared return types:
  - literals (numeric literals are polymorphic and default to `i64` / `f64`);
  - arithmetic, comparison and logic operators;
  - tuples and list literals;
  - calls, with argument count (`T006`) and types (`T002`), generic signatures instantiated per
    call, and pipes;
  - lambdas, `if` / `else`, `let`, `do`, `match` and `handle` / `resume`;
  - method calls and static calls (see "Member resolution");
  - `unreachable`.
- **Member resolution**:
  - single-variant data fields;
  - capability methods through `satisfies`, a type variable's bound, type aliases and `extends`
    chains;
  - capability statics on caps and type variables;
  - effect operations and data constructors;
  - the derived `fold` and `unfold` of every data type.
- **Patterns**: variant (with arity, `T006`), struct-destructure, literal, tuple, binder, wildcard,
  or- and guarded patterns.
- **Top-level exhaustiveness** (`T020`): every variant of a data scrutinee is named by an unguarded
  case. A `bool` match needs `true` and `false`; other scrutinee types need a catch-all.
- **`Numeric`** for arithmetic operands and for type variables bounded by `Numeric` directly.
- **`resume`** only inside an operation clause (`T009`).

## Not enforced (gaps)

| Gap | Where it closes |
|---|---|
| ~~Capability satisfaction for values flowing into an `is ...` bound~~ — enforced since 3.1.M2 (`T010`). Still lenient: an abstract `is ...` value used where a concrete type is expected | M6 (3.1.M5 constructor resolution) |
| ~~`Numeric` reached through `extends`~~ — done in 3.1.M1 | — |
| ~~`satisfies` mappings and bodies, cap default bodies~~ — checked since 3.1.M3 / 3.1.M4. Still open: effect operation bodies (none in the corpus) and abstract-method coverage | M6 follow-up |
| Effect rows: `with` clauses are recorded but never checked; calling an effect operation needs no handler | M7 effect inference |
| `prop` bodies, `forall`, `implies` (reported as `T005` warnings) | M9 property verifier |
| Quantities (linearity, erasure) | M10 QTT |
| Representation: `Show`, `Eq` and `Clone` are assumed derivable for every type | M11 representation selection |
| Nested-pattern exhaustiveness, refinement-aided coverage, handler-clause coverage | Phase 3.x exhaustiveness |
| Reachability of match cases (Rule M7.1) and binder agreement across or-pattern alternatives (Rule M5.5.b) | Phase 3.x exhaustiveness |
| Struct literals (`T005` warning) | follow-up |
| `mod` declarations are not indexed; cyclic type aliases are not reported (`T050`) | follow-up |

## Typer readings where the spec is silent

These choices are the typer's own. Each should become a spec rule or be changed.

- **`|>`** has only a grammar entry. The typer reads `x |> f(a)` as `f(x, a)`.
- **`List.fold`** is used two ways in the corpus: `Folding.fold(init, f)` in `01` and the derived
  per-variant fold in `06`. When several members share a name, a call picks the first candidate
  whose arguments type-check.
- **`builtin.f(...)`** placeholders (host intrinsics in the examples) type their arguments and
  leave the result to inference.
- **The signature-only stdlib** (`compiler/src/main/resources/stdlib/`) adds members the examples
  need that `spec/stdlib.md` does not list: `Sequencing.length`, `Folding.find`, `repeat_str`,
  `join` and `Concat.concat`.

## Performance

JMH, corpus of 11 examples, `FrontEndBench`:

| Step | `typer` µs/op | × `parseOnly` |
|---|---|---|
| M5 as first landed | 2038 | 11.9× |
| Lazy members, cached type params, dense symbol table | 1807 | 10.2× |
| Cardinality + ponytail passes (no perf intent) | 1943 | 11× |
| Type side table: lazy zonk, then `IdentityHashMap` (plan §3.1/§3.5 hatch) | 1073 | 6.1× |
| Thunked candidates, unboxed unify / instantiate / sets, per-owner type-variable index, error counter | 920 | 5.1× |
| Presized identity table (2 forks) | 849 ± 28 | 4.8× |
| Precomputed capability member tables | 829 ± 48 | 4.7× |
| No redundant resolution in `unify` (identity fast path, loop `unifyAll`) | 779 ± 32 | 4.4× |
| Follow-up round: layered scopes (Namer and typer), unindexed top-level members, dense `Vector` unifier bindings, tuple-free member-table keys | ~5% below the previous row, thread-CPU A/B | ~4.2× |

The Namer is ~104 µs of `typer`. The remaining body-typing cost is spread across the persistent
unifier (`resolve`, `unify`, ~15%), the identity table (~8%), scope maps (`Context.bind`,
`Namer.Ctx.bind`), member lookup and symbol-table appends. No hotspot remains above ~8%.

Measured diagnosis (counters over one corpus pass): 2,769 expression nodes, 3,126 `unify` and
12,330 `resolve` calls. Only 2,893 of those resolutions follow a variable link, so unifier chains
are nearly flat: a mutable or union-find unifier has little to gain. There are 372 member lookups
over 61 distinct (type, name) pairs.

Allocation (`-prof gc`): the typer allocates ~4.0 MB per pass against 1.6 MB for the parser, and
GC takes ~0.4% of the time. `TyperState.copy` is 4.4% of the allocated bytes. Removing 5% of
the allocation (iterators instead of materialized member vectors) changed time by less than the
noise. A leaner `TyperState` is therefore not a clear win, and was not done.

What remains is a per-node constant factor (~250 ns per expression node) spread over many small
costs, plus the Namer (~13%, re-run per unit for user declarations).

The follow-up round's numbers are relative. The machine's load average was 5–11, which made
wall-clock JMH unreliable (±10–20%), so each change was checked with `TyperCpuBench` (thread CPU
time, which excludes time spent descheduled) alternating the baseline tree and the candidate tree.
The layered Namer scope was first *slower*: an iterator-based lookup on every name. A plain loop
fixed it. Dense `Vector` bindings cut `resolve` from 7.7% to 1.7% of samples. After the round no
single entry exceeds ~6% of samples: `mapAccum` overhead, scope lookups, the type table and
symbol entry.

Conclusion: tuning has taken the typer from ~12× to ~4.2× the parser. The profile is now flat.
Reaching 2× would need a different representation in the hot path (for example, typing into
mutable per-run arrays instead of threading persistent state through every node). That is a design
decision for Part B, not more tuning.
