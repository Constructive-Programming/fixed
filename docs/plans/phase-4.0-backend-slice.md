# Phase 4.0 — Naive backend slice

**Status:** Draft, not started
**Parent:** `docs/plans/implementation-plan.md` (Phase 4.0) and `docs/plans/phase-3-typer.md` §1.3
(row 4.0)
**Runs:** after M7, **before M10**
**Spec:** `spec/memory_management.md` §8 and §10 (heap layout, RC insertion; nothing else of that
document), `spec/type_system.md` Rules 7.4.a–7.4.d (derived members), `spec/effects.md` §3.3
(abortive operations), `spec/stdlib.md` §4.10 (`Show`)

## 1. Goal

Produce running native code and a recorded baseline as cheaply as possible, so that M10, M11 and
Phase 4 proper are judged by what they change in measured numbers.

After this slice:

1. **A Fixed program compiles to LLVM IR, links and runs.** `fixed ship file.fixed -o out`.
2. **Every binary reports four numbers:** wall time, peak resident memory, allocation count and
   reference-count operation count.
3. **A baseline is committed** for a small set of benchmark programs.
4. **Reference counting is checked on every test run:** allocations equal frees at exit.

The slice is deliberately naive. It does not implement the guarantees of the spec (fold fusion and
specialisation, lifetime classes, reuse, representation selection, parallelism). Each of those is
a later milestone that must show its effect against this baseline.

## 2. Non-goals

- **Quantities, lifetime classes, regions, destinations, witnesses, evacuation.** M10 and Phase 4.
  Here every boxed value is `Heap` and quantity ω.
- **Reuse, borrowing, drop specialisation.** Phase 4 Perceus inserter.
- **Representation selection.** M11. The slice has one rule (§4.3).
- **Fold specialisation and fusion** (Rules 7.4.b.1, 7.4.b.2). Phase 4. Leaving them out is the
  point: the baseline must show their cost.
- **Capturing handlers** (a clause that stores or delays `resume`). Phase 4.
- **Evidence vectors.** Phase 4. The slice needs no evidence for what it supports (§4.6).
- **Parallelism, PGO, separate compilation, `prop` verification, `fip`.**
- **Comparisons against other languages.** The baseline is Fixed against itself. Numbers from a
  naive backend against Goose, Bend, Koka or Rust would say nothing useful yet.

## 3. Finding: most of examples 01–06 cannot run as written

Examples `01`–`06` type-check with no diagnostics. That is not the same as being runnable. The
examples are design documents, and the typer does not yet check that every member a program calls
has a definition (`docs/Phase3Conformance.md`, "abstract-method coverage"). A first pass over what
each `main` reaches found:

| Example | Blocker | Kind |
|---|---|---|
| `01_basics` | None found. Needs prelude members with no bodies (`show`, `concat`, numeric operators): §4.4 | runnable |
| `03_option_result` | None found on the path `main` reaches. `builtin.parse_u64` needs an intrinsic | runnable |
| `06_functor_monad` | `Maybe satisfies Optional` maps two constructors only. The comment says `fold`, `map`, `flat_map` and `pure` "are auto-derived from Maybe's structure". Spec §7.4 derives `fold`, `unfold`, `Functor`, accessors and `para`. It has no rule for `flat_map` or `pure` | spec gap; **left out** (decided) |
| `04_json` | `Json.Object([("name", ...), ...])` passes a list literal of pairs where the field is `is Map of (String, Json)`. No rule says how a list literal becomes a `Map`. `Json.Array([...])` needs the same for `is Sequencing` | spec gap |
| `05_phantom_types` | `cap Door` has `Self.fn new -> Self` and `fn name -> String`. `new` takes no arguments, so `door.name` has no value to return. `Quantity` is fine (`new(value)` feeds `value`) | example gap |
| `02_collections` | Values exist only as capability bounds (`range(1, 11)` returns `is ...`). Members such as `init`, `last`, `get` and `size` come from built-in representation candidates, which is what M11 defines | **left out** until M11 (decided) |

This pass was not exhaustive. Milestone 4.0.M1 makes it exhaustive and records a decision per
blocker.

**Consequence for scope.** The slice's required set is `01` and `03` plus the benchmark programs of
§4.9, which are written in the same subset. `04` and `05` join when their blockers close (4.0.M6)
and do not hold up M10. `06` and `02` are out of the slice: `06` until a decision on `Monad`
members for sum data, `02` until M11.

## 4. Design

### 4.1 Pipeline and the typed hand-off

```
ParserPhase → TyperPhase → LowerPhase → EmitPhase → clang
```

- **`TyperPhase` must keep what it computes.** Today it returns the symbol table and diagnostics
  and drops `TyperState`, which holds the type side table (`TypeTable`) and the unifier needed to
  read it. `CompilationUnit` gains one field carrying both. Nothing else in the typer changes.
- **`LowerPhase`** turns the typed tree into Core (§4.2), starting at `main`.
- **`EmitPhase`** prints Core as textual LLVM IR and invokes `clang` to link it with the runtime.
- Both new phases run only for `fixed compile` / `fixed ship`. A bare `fixed file.fixed` keeps
  today's behaviour (check only), so the front-end benchmarks are unaffected.

### 4.2 Core: one small IR

A-normal form, first order, monomorphic:

- `Let`, `Lit`, `Prim` (arithmetic and comparison on scalars), `If`, `Switch` on a tag;
- `Con(tag, fields)`, `Field(value, index)`;
- `Call(function, args)`, `MakeClosure(function, captured)`, `CallClosure(closure, args)`;
- `Dup`, `Drop`;
- `Abort(effect, payload)` and `Handle` (§4.6), runtime calls.

Everything the surface has beyond this is removed by `LowerPhase`: `match` (to `Switch` and
`Field`), `do` (to `flat_map` calls), pipes, method calls (to `Call` of a resolved member), tuples
and list literals (to `Con`), derived members (§4.4).

Core is kept. The Phase 4 passes (fold specialiser, Perceus inserter, region lowering) are Core to
Core. That is the reason to have an IR at all in a naive slice.

### 4.3 Monomorphisation and the one representation rule

- **Worklist from `main`.** Each item is a function symbol plus concrete type arguments. Code that
  `main` does not reach is never lowered, so an undefined member in dead code blocks nothing.
- **Scalars are unboxed.** Integers, floats, `bool`, `char` are LLVM scalars. `()` carries no data.
- **Everything else is a pointer to a heap object:** `String`, every `data` value including nullary
  variants, tuples, closures. Layout is `spec/memory_management.md` Rule PR3.1: count, tag, fields.
- **A value typed only by a capability bound (`is C of ...`) is represented by the single data type
  in scope that satisfies `C`.** None in scope: `error[B001]`, which is exactly the `02` case.
  Several: `error[B001]` listing them. This rule is the slice's stand-in for M11 and is deleted
  when M11 lands.

**To verify in 4.0.M1:** the typer instantiates generic signatures per call and records resolved
constructor calls (`TyperState.constructors`). Whether the recorded types are enough to recover
each call's type arguments, and which `satisfies` member a method call resolved to, is not
confirmed. If they are not, `LowerPhase` re-resolves through `Members` and `Satisfaction` with the
concrete types.

### 4.4 Members with no body

The prelude (`resources/stdlib/prelude.fixed`) is signature-only. The slice needs definitions for
what `01`, `03` and the benchmarks reach. Three sources, in this order of preference:

1. **Derived by the compiler, where the spec has a rule:** `fold` (7.4.a), `unfold` (7.4.b),
   `Functor.map` (7.4.c), accessors (7.4.d), `Show` for data (stdlib §4.10: constructor name,
   parentheses, fields).
2. **Written in Fixed in the prelude,** where the member is expressible with derived members:
   `List`'s `Folding.fold(init, f)`, `fold_right`, `filter`, `length`, `find`, and `join`,
   `repeat_str`. The typer already checks `satisfies` bodies. This is the first part of the Phase 5
   standard library and is kept.
3. **Runtime intrinsics,** only for what cannot be written in Fixed: scalar arithmetic and
   comparison, scalar `show`, `String` (`concat`, `eq`, `compare`), `Console`, and
   `builtin.parse_u64`.

Derived `fold` and `unfold` compile to recursive functions. A fold over a list of length n uses
native stack depth n. Benchmark sizes stay within the default stack; the limit is recorded with the
baseline. Fusion and tail calls in Phase 4 remove it.

### 4.5 Reference counting

Perceus insertion without its optimisations (`spec/memory_management.md` §10 only):

- Every function owns its arguments.
- A binding is `dup`ed at each use except its last, and `drop`ped on any path where it is not used.
- No reuse tokens, no borrowed parameters, no specialised drops. `drop` reaching zero frees the
  object and drops its fields through one generic, tag-driven routine in the runtime.

This is precise: nothing outlives its last use, so the baseline's memory numbers are meaningful.
The same pass, extended with reuse and borrowing, is the Phase 4 Perceus inserter.

### 4.6 Effects

Only what `01`, `03` and the benchmarks need:

- **Runtime effects (`Console`, `FileSystem`)** are direct calls into the runtime. `main`'s row
  holds only these (Rule E5.4).
- **Abortive operations (`-> !`, for example `Fail.fail`)** use a checked abort. `Abort` stores the
  payload and the target handler in the runtime and returns. After every call to a function whose
  effect row contains an effect with an abortive operation, the caller tests the flag, drops its
  live bindings and returns. The `handle` that owns the target runs its clause. Effect rows are in
  the types, so the compiler knows statically which calls need the test.
  - **Alternative:** `setjmp` / `longjmp`. Less code to emit, but it skips the drops, so every
    abort leaks and the memory numbers are wrong.
- **Tail-resumptive user handlers** are added only if a benchmark needs one: the clause is a
  closure in a handler frame, and `resume(v)` in tail position is `return v`.
- **Anything else** (a clause that does not resume in tail position and is not abortive) is
  `error[B002]`.

### 4.7 Runtime

One file, `runtime/fixed_rt.c`, compiled by the same `clang` invocation that links the program:
allocation, `dup` / `drop` / free, strings, console, scalar formatting and parsing, the abort
state, and the counters.

The runtime keeps four counters and prints them as one JSON line on standard error at exit when
`FIXED_RT_STATS=1`: allocations, frees, `dup`s, `drop`s, plus peak resident memory and wall time.
Writing the runtime in C does not make C a compilation target: the compiler emits LLVM IR only
(decided, open question 1).

### 4.8 Command line, toolchain and tests

- **CLI:** `fixed compile file.fixed -o out.ll` and `fixed ship file.fixed -o out`, the two
  commands the implementation plan already names. `ship` runs `clang -O2 out.ll fixed_rt.c`.
- **Toolchain:** `clang` on `PATH`. The development machine has LLVM 22. Opaque pointers only.
  CI (`ubuntu-latest`) needs `clang`; to confirm in 4.0.M2. Tests that need `clang` fail loudly
  when it is missing instead of being skipped, so CI cannot pass without running them.
- **Tests:**
  - `EmitSuite`: one small program per Core construct, compared on standard output.
  - `ExampleRunSuite`: golden standard output per example for a fixed standard input
    (`examples/golden/<name>.in`, `.out`).
  - **Leak check in both:** allocations equal frees at exit. This checks RC insertion on every run.
  - Build products go under `target/`, never the system temporary directory.

### 4.9 Benchmarks and the baseline

Programs live in `bench/runtime/` and use only the subset `01` and `03` use. Each stresses one
thing a later milestone claims to improve:

| Program | Shape | Later milestone it measures |
|---|---|---|
| `bintrees` | `unfold` a tree of depth d, `fold` it to a count | regions, packed layout, tree-fold parallelism |
| `nat_loop` | `Nat.unfold(n)` then `fold`, as `fib_iter` in `01` | fold fusion (Rule 7.4.b.1) |
| `list_pipeline` | `unfold` a list, `map`, `filter`, `fold` | fold specialisation, reuse |
| `records` | list of a product `data`, `map` rebuilding one field | reuse, `Inline` layout |
| `fail_chain` | `Result` and a `Fail` handler inside a fold | abort cost, later evidence passing |

A runner builds each program, runs it several times and writes
`docs/baselines/runtime-<date>.md`: per program the best wall time, peak resident memory,
allocations and RC operations, with the machine, the LLVM version and the input sizes.

## 5. Diagnostics

| Code | Meaning |
|---|---|
| `B001` | no single data type in scope represents `is C` here (none, or several) |
| `B002` | handler clause kind not supported by the backend slice |
| `B003` | member `m` is called but has no definition (no body, no derivation rule, no intrinsic) |
| `B004` | `clang` not found or failed; its output is attached |

`B` codes are the backend's own, as `T` codes are the typer's. `B001`–`B003` are limits of the
slice, not errors in the program, and each disappears with the milestone that lifts the limit.

## 6. Milestones

| # | Milestone | Verification |
|---|---|---|
| 4.0.M1 | Audit and hand-off. Every member reachable from `main` in `01`, `03`, `04` and `05` is listed with its source of definition (§4.4) or as a blocker with a decision. `CompilationUnit` carries the typed information. The §4.3 "to verify" item is settled. Produces the size estimate for the rest | Audit table in §8 of this document; a test reads a recorded type through `CompilationUnit` |
| 4.0.M2 | Core, emitter, runtime, toolchain. Literals, scalar arithmetic, `let`, `if`, calls, strings, `Console` | A hello-world program ships and runs; `EmitSuite` green locally and in CI |
| 4.0.M3 | Data and closures: constructors, `match`, tuples, derived `fold` / `unfold` / accessors / `show`, lambdas, monomorphisation, reference counting with counters | `01_basics` matches its golden output; leak check passes |
| 4.0.M4 | `satisfies` mappings and bodies, the `is C` rule, prelude bodies in Fixed, abortive effects and `handle` | `03_option_result` matches its golden output; leak check passes, including on the abort path |
| 4.0.M5 | Benchmarks and baseline | `docs/baselines/runtime-<date>.md` committed; rerunning reproduces allocation and RC counts exactly |
| 4.0.M6 | Gated examples `04` and `05`, each when its blocker closes | Golden output per example, or the example stays listed with its open blocker |

**M10 may start after 4.0.M5.** 4.0.M6 runs alongside later work.

**Perf:** the new phases must not slow the check-only path. `FrontEndBench` is re-run at 4.0.M2.

**Size:** not estimated. 4.0.M1 is the sizing step; the earlier Phase 3 milestones each took one
to two weeks, and this slice has five required milestones of uneven size.

## 7. Open questions

1. ~~**Runtime in C.**~~ **Decided:** one C file linked by `clang`. The compiler's only output
   stays LLVM IR. Moving the runtime into Fixed itself waits for an `extern` mechanism
   (implementation plan, open design question 5).
2. ~~**`06`: `flat_map` and `pure` for sum data.**~~ **Decided:** `06` is left out of the slice.
   Whether spec §7.4 gets a derivation rule for `Monad` members, or the example writes the bodies,
   is decided separately.
3. **`04`: a list literal where a `Map` or `Sequencing` bound is expected.** Needs a spec rule
   (which capability a literal can construct, and through which members), or a change to the
   example. `04` stays gated until then (agreed).
4. **`05`: `Door.name`.** Change the example (`new(name: String)`), or define what an accessor
   with no constructor argument returns. `05` stays gated until then (agreed).
5. ~~**`02`.**~~ **Decided:** left out until M11 defines the built-in candidates and their members.
6. ~~**Integer overflow and division by zero.**~~ **Decided:** integer division by zero panics.
   Overflow wraps, as proposed. The spec states neither yet; both rules are written into
   `spec/stdlib.md` §4.9 at 4.0.M2, before any golden output depends on them.
7. **Derived `show` format.** Stdlib §4.10 gives one sentence. The golden files will fix the exact
   format (separators, nullary variants, strings quoted or not); it should be written into the
   spec at 4.0.M3.

## 8. Audit (filled by 4.0.M1)

Empty until 4.0.M1. One row per member reachable from `main`, per example: the member, where its
definition comes from (derived, prelude body, intrinsic), or the blocker and its decision.
