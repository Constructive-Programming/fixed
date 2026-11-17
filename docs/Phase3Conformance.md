# Phase 3 Part A — typer conformance (v1)

What the base typer (Phase 3 M1–M5) enforces today, and what it does not. A downstream consumer
reading the typer's output should assume nothing outside the "Enforced" list. The rules behind each
item are in `docs/plans/phase-3-typer.md` §3.6–§3.9.

## Status

- Examples `01`–`06` compile with no diagnostics (`CompilerSuite`).
- Examples `07`–`11` still report errors. They use features outside Part A (props, struct literals,
  `forall` / `implies`, satisfies bodies and more) or need the gaps below closed. At M5 they show
  57 errors and 15 `T005` warnings in total.
- Performance: the typer takes ~1.8 ms per corpus pass against ~0.18 ms for the parser. That is
  about 10×, against the plan's 2× target. See "Performance" below.

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
| Capability satisfaction: an `is ...` bound or a capability used as a type accepts any type; only arguments of a capability named on both sides are unified | M6 capability closure |
| `Numeric` reached through `extends` (only a direct `Numeric` bound counts) | M6 |
| `satisfies` declaration bodies and mappings, and cap / effect method bodies, are not typed | M6 |
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

The M5 checkpoint (JMH, 1 fork, corpus of 11 examples) measured `typer` at 1807 µs per pass (the
Namer is 104 µs of that) against 177 µs for `parseOnly`. Two fixes went in during Part A:

- keying the type side table by span and node class instead of whole trees, which saved ~1.1 ms;
- caching the indexed stdlib and laziness in member lookup.

The remaining cost is spread across persistent-map operations: recording types, unifier
resolve / zonk and symbol lookups. Closing the gap is a dedicated tuning pass, still open.
