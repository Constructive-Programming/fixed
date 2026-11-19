# Phase 4.0 — Naive backend slice

**Status:** 4.0.M1–M3 done; 4.0.M4 next
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
| `04_json` | No data type in the file satisfies `Optional`, and `get_field`, `navigate` and `find` all produce `is Optional of ...` values, so §4.3 finds no representation. (The first version of this table named a list-to-`Map` rule. That was wrong: `04` declares its own `Json` with `List` fields, so the prelude's `Map`-typed `Json` is not involved.) | example gap, or M11's built-in `Optional` |
| `05_phantom_types` | No data type satisfies `Door`, `TcpSocket` or `Quantity`; the file uses the product capabilities directly. That needs the auto-flat-struct candidate of `spec/type_system.md` §6.6. `TcpSocket` and `Quantity` then work (`new(port)` feeds `port`, `new(value)` feeds `value`). `Door` still does not: `new` takes no arguments, so `door.name` has no value to return | needs auto-flat-struct, plus an example gap |
| `02_collections` | Values exist only as capability bounds (`range(1, 11)` returns `is ...`). Members such as `init`, `last`, `get` and `size` come from built-in representation candidates, which is what M11 defines | **left out** until M11 (decided) |

Milestone 4.0.M1 completed the pass for `01`, `03`, `04` and `05`; the per-member results are in §8.

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

**Settled in 4.0.M1** (what the typer hands over, `CompilationUnit.typing`):

- **Types: enough.** Every expression node has its type recorded, and `typeOf` substitutes solved
  variables. A reference to a generic function records its *instantiated* arrow type, so matching
  it against the declared signature recovers the call's type arguments. Inside a generic body the
  types mention the body's own type parameters, which monomorphisation substitutes.
- **Resolution: not recorded.** Which member a method call chose is lost: `Members.Candidate` is a
  closure, and `typedMethodCall` keeps only the winner's type. The same holds for identifiers
  (which symbol) and for static calls other than capability constructors (`constructors` records
  those). Re-resolving in `LowerPhase` would duplicate the overload logic of `typedMethodCall`.
  **Decision:** the typer records a resolution side table (tree → symbol, and for members the
  satisfying type and member it came from), next to `TypeTable`. Added in 4.0.M3, its first
  consumer; `TyperCpuBench` A/B required, as for M7.

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
| `B005` | construct not supported by the backend slice yet (names the milestone that adds it where known) |

`B` codes are the backend's own, as `T` codes are the typer's. `B001`–`B003` and `B005` are limits of the
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

**4.0.M3 status: done.** `01_basics` matches its golden output. See §9.

**4.0.M2 status: done.** See §9.

**4.0.M1 status: done.** Hand-off in `CompilationUnit.typing` with a test in `CompilerSuite`;
§4.3 settled; audit in §8.

**M10 may start after 4.0.M5.** 4.0.M6 runs alongside later work.

**Perf:** the new phases must not slow the check-only path. `FrontEndBench` is re-run at 4.0.M2.

**Size (from the 4.0.M1 audit):** about 5 weeks for one contributor, 4 to 7.

| Milestone | Estimate | Driver |
|---|---|---|
| 4.0.M2 | 1 week | Core, emitter, C runtime, `clang` in CI |
| 4.0.M3 | 2 weeks | Monomorphisation, closures, derived `fold` / `unfold` including Rule 7.4.a.1, the resolution table, RC insertion |
| 4.0.M4 | 1 to 1.5 weeks | Capability members through `satisfies` mappings, prelude bodies in Fixed, checked abort |
| 4.0.M5 | 0.5 week | Five programs, runner, baseline record |

Calibrated against Phase 3 milestones, which took one to two weeks each.

## 7. Open questions

1. ~~**Runtime in C.**~~ **Decided:** one C file linked by `clang`. The compiler's only output
   stays LLVM IR. Moving the runtime into Fixed itself waits for an `extern` mechanism
   (implementation plan, open design question 5).
2. ~~**`06`: `flat_map` and `pure` for sum data.**~~ **Decided:** `06` is left out of the slice.
   Whether spec §7.4 gets a derivation rule for `Monad` members, or the example writes the bodies,
   is decided separately.
3. ~~**`04`: no `Optional` representation.**~~ **Decided:** the prelude gets `data Maybe` and
   `Maybe satisfies Optional` (`spec/stdlib.md` §3.5); `03` and `06` rename their own to `MyMaybe`
   to avoid the name-keyed `satisfies` table colliding. Done in 4.0.M3. Original options: Corrected by the 4.0.M1 audit (§3). Options: `04`
   declares `data Maybe` and `Maybe satisfies Optional` as `03` does (one small edit), or `04`
   waits for M11's built-in candidates. A prelude `Maybe` would not work: `03` and `06` declare
   their own, and two satisfying constructors are ambiguous (`T011`). Proposed: edit the example.
   `04` stays gated until then.
4. ~~**`05`: product capabilities with no satisfying data, and `Door.name`.**~~ **Decided:** the
   slice implements the auto-flat-struct candidate, and `Door` takes `new(name: String)`. The example
   change is done in 4.0.M3; the candidate lands with the `is C` rule in 4.0.M4. Original options: The auto-flat-struct
   candidate (§6.6) is small for a product capability with one constructor whose parameters name
   its accessors, so the slice could add it as a second stand-in next to §4.3. `Door` then still
   needs an example change (`new(name: String)`) or a rule for an accessor with no constructor
   parameter. `05` stays gated until then.
5. ~~**`02`.**~~ **Decided:** left out until M11 defines the built-in candidates and their members.
6. ~~**Integer overflow and division by zero.**~~ **Decided:** integer division by zero panics.
   Overflow wraps, as proposed. The spec states neither yet; both rules are written into
   `spec/stdlib.md` §4.9 at 4.0.M2, before any golden output depends on them.
7. **Derived `show` format.** Stdlib §4.10 gives one sentence. The golden files will fix the exact
   format (separators, nullary variants, strings quoted or not); it should be written into the
   spec at 4.0.M3.

## 8. Audit (4.0.M1)

Members reachable from `main`, by where their definition comes from. "Prelude body" means a body
written in Fixed in `resources/stdlib/prelude.fixed` (§4.4 source 2); "intrinsic" means the C
runtime (§4.4 source 3).

### `01_basics`: runnable

| Member | Source |
|---|---|
| `Console.print_line`, `Console.read_line` | intrinsic (runtime effect) |
| `String.concat` | intrinsic |
| `u64.show` | intrinsic (scalar formatting) |
| `+`, `-`, `==`, `>=` on `u64` and on `N` instantiated at `u64` | `Prim` |
| `Nat.unfold`, `List.unfold` | derived, Rule 7.4.b |
| `Nat.fold` with one callback per variant | derived, Rule 7.4.a |
| `List.fold((), (_, i) -> ...)` | resolves to `Folding.fold(init, f)`: the derived per-variant fold rejects `()` as its first argument. Spec §7.4 derives no `Folding.fold`, so it is a **prelude body** on `List satisfies Folding`, written with the derived fold |
| tuple `(b, a + b)`, `let (a, b) = acc`, `.first` | `Con` and `Field` on `Tuple`; `.first` is the accessor of Rule 7.4.d |
| `fib_iter` at `N = u64`, literals `0`, `1` | monomorphisation; literals are `u64` constants |

### `03_option_result`: runnable

| Member | Source |
|---|---|
| `Console.print_line`, `String.concat`, `u64.show` | intrinsic |
| `builtin.parse_u64(s)` | intrinsic, but its result is `is Result of (String, u64)`. The C side returns success and the number; `LowerPhase` builds the value with the constructors `Result.ok` / `Result.err` resolve to under §4.3 (`Outcome.Success`, `Outcome.Failure`). The runtime never builds a Fixed data value |
| `Result.ok`, `Result.err` | `Outcome.Success`, `Outcome.Failure` through the `satisfies` mapping (recorded in `TyperState.constructors`) |
| `Result.fold(on_ok, on_err)` on an `Outcome` | the derived fold of `Outcome`, with callbacks reordered **by constructor mapping** (`ok` → `Success`, `err` → `Failure`), not by position. Here the orders happen to agree |
| `Fail.fail` inside `inner`, `handle ... Fail.fail(e) => ..., return(v) => ...` | checked abort (§4.6) |
| `a / b`, `n * 2`, `b == 0` | `Prim`; integer division by zero panics (§7.6) |
| nested `fn inner` | lifted to a top-level function; it captures nothing |

Not reached from `main`: `Maybe`'s `satisfies` bodies, `unwrap_or`, `map_optional`, `and_then`,
`Map`, `lookup_user_age`, `require_user_age`. They are type-checked but never lowered.

### `04_json`: gated (§7.3)

| Member | Source |
|---|---|
| `Json.fold` with `List of R` and `List of (Tuple of (String, R))` callbacks | derived, Rule 7.4.a.1 (folds through the `List` and `Tuple` functors) |
| `List.length`, `Folding.find` | prelude bodies |
| `List.map` | derived, Rule 7.4.c |
| `repeat_str`, `join` | prelude bodies (a `Nat.unfold` then a fold; a fold) |
| `bool.show`, `f64.show` | intrinsic. `f64` format undecided (§7.7) |
| `String ==` | intrinsic |
| `.first`, `.second` on `Tuple` | accessors, Rule 7.4.d |
| `Optional.some`, `Optional.none`, `Optional.fold` | **blocker:** no satisfying data type in the file |

### `05_phantom_types`: gated (§7.4)

| Member | Source |
|---|---|
| `TcpSocket.new`, `.port`; `Quantity.new`, `.value` | **blocker:** product capabilities with no satisfying data; need auto-flat-struct |
| `Door.new`, `.name` | **blocker:** as above, and `new` has no parameter for `name` |
| `f64` arithmetic, `u16.show`, `f64.show` | `Prim`, intrinsic |
| phantom `State` / `Unit` parameters | erased by monomorphisation: `Quantity of Meters` and `Quantity of Seconds` share one layout |

### Findings that apply beyond the slice

1. **The typer does not check that a called member has a definition.** `01`–`06` all type-check
   while `02`, `04`, `05` and `06` call members with no body and no derivation rule. `B003` catches
   this at lowering. The typer-side check belongs with "abstract-method coverage" in
   `docs/Phase3Conformance.md`.
2. **Spec §7.4 derives the per-variant `fold` but not `Folding.fold(init, f)`.** Every `data` that
   satisfies `Folding` therefore needs a written body. The slice writes one for `List`. Whether a
   recursive data type should derive `Folding.fold` as a left fold is a spec question.
3. **A capability method implemented by a derived member is matched by constructor mapping**
   (`Result.fold` on `Outcome`). The spec should state this rule; today it is implicit in the typer.

## 9. Implementation notes

### 4.0.M2

- **Code:** `compiler/src/main/scala/fixed/backend/`: `Core` (IR), `Lower` (typed tree to Core, in
  continuation-passing style, worklist from `main`), `Rc` (§4.5), `EmitLLVM`, `Backend` (entry
  points and the `clang` call). Runtime: `compiler/src/main/resources/runtime/fixed_rt.c`, written
  next to the `.ll` file at `ship` time. CLI: `fixed compile <file> -o <out.ll>` and
  `fixed ship <file> -o <exe>`.
- **Covered:** integer, float, `bool`, `char` and `String` values; literals, arithmetic,
  comparisons, `&&` / `||` (short-circuit, lowered to `if`), unary `-` and `!`, `let` with a binder
  or `_`, blocks, `if`, calls of non-generic top-level functions, `concat`, `show` on scalars,
  `String ==` / `!=`, `Console.print_line`, `Console.read_line`. Everything else is `B005`.
- **Header layout.** Rule PR3.1's padding after the tag holds `nptr`, the number of boxed fields,
  which come first in the payload. One generic `fx_drop` then frees any object. String constants
  are static objects in the IR (count `-1`), never allocated, counted or freed.
- **`f64` format** (§7.7): the shortest decimal that reads back as the same value. Plain notation
  with at least one decimal for exponents -4 to 15 (`10.0`, `9.58`, `0.3333333333333333`),
  scientific otherwise (`1e+300`), and `inf`, `-inf`, `NaN`. Still to go into `spec/stdlib.md`.
- **Wrapping and panics** (§7.6): `+`, `-`, `*` wrap; integer `/` and `%` by zero exit with code 101
  and `fixed: panic: integer division by zero`. `ponytail:` signed `MIN / -1` is undefined in LLVM
  and traps on x86; it needs an explicit check if wrapping is to hold there too.
- **Built-ins matched by name** (`concat`, `show`, `Console.*`) until the resolution table of
  4.0.M3; `ponytail:` comments in `Lower` mark both places.
- **Tests:** `EmitSuite`, five programs, each run with the leak check. CI runs `clang --version`
  before the gate so a missing compiler fails loudly.
- **Perf:** `FrontEndBench` not rerun. The check-only path does no new work; `TyperPhase` only
  keeps a reference to the state it already built.

### 4.0.M3

- **Typer:** `Resolution` side table (`typer/Resolution.scala`, stored next to `TypeTable`): which
  candidate a method call chose (field, capability member, derived `fold`). `TyperCpuBench`: about
  1,000 µs per corpus pass after, 1,100 before (noise; no regression).
- **Core:** `Con`, `Field`, `Switch`, `Closure`, `CallClosure`. Layout per Rule PR3.1 with every field
  in an 8-byte slot, boxed fields first; a closure keeps its code pointer in slot 0 and is tagged
  `0xFFFFFFFF`. Nullary variants and closures without captures are static objects.
- **Reference counting:** `Field` borrows its object; the object is dropped after the read and a
  boxed result is dup'ed. Branches (`If`, `Switch`) take the variables their arms use.
- **Lowering:** monomorphisation by instance (`f[u64]`), solving type parameters by matching the
  declared signature against the reference's recorded type; lambdas as code functions with
  captures found from the lowered body; `match` on data with variant, binder and `_` patterns;
  tuple `let` patterns; tuples as the prelude's `Tuple`.
- **Generated members:** derived `fold` (Rule 7.4.a, recursive fields folded first), derived
  `unfold` (Rule 7.4.b, the layer is a separate allocation), and `Folding.fold(init, f)` as a left
  fold on list-shaped data. `ponytail:` the last is generated until prelude bodies land (4.0.M4).
  Rule 7.4.a.1 (folding through `List` and `Tuple`) is not implemented; `04` needs it.
- **`01_basics`:** 734 allocations, 734 frees, 651 `dup`s, 1,533 `drop`s for the golden input.
- **Examples:** prelude `Maybe` added, `03` / `06` renamed to `MyMaybe`, `05`'s `Door` takes a name.
