# Runtime baseline, 2026-11-20: Phase 4.1 against `main`

Phase 4.1 (`docs/plans/phase-4.1-baseline-specialiser.md`) changed the backend from monomorphising everything to one polymorphic **baseline** version per function plus **clones** chosen by a score within a size budget (`spec/compilation_model.md`). This report compares three builds of the five runtime benchmarks:

- **main** — commit `5a0eedd`: monomorphic lowering, at most four ownership versions per function.
- **default** — commit `f5e59ec`, default mode: baseline versions, plus clones by score within `B = 512`.
- **no-specialise** — the same commit with `--no-specialise`: baseline versions only.
- **budget-6** — the same commit with `--budget 6`, a budget small enough that some functions get no clone.

- Machine: Linux 7.2.15-gentoo-x86_64 x86_64, AMD RYZEN AI MAX+ 395 w/ Radeon 8060S. Toolchain: clang 22.1.8, `clang -O2`.
- Wall time is measured by the runtime from `main` start to end. Every build ran 25 times, interleaved (one run of each build per round), on an otherwise idle machine. Best and median are given; identical builds differ by about ±5% between sessions, so differences below that are not findings.
- Counts are deterministic.

## Results

| Program | Build | Result | Best wall (ms) | Median wall (ms) | Allocations | Reuses | Frees | dups | drops | Peak RSS (KiB) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | main | 2097151 | 53.7 | 59.3 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 51048 |
| `bintrees` | default | 2097151 | 54.6 | 58.8 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50812 |
| `bintrees` | budget-6 | 2097151 | 56.3 | 59.5 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 51052 |
| `bintrees` | no-specialise | 2097151 | 56.1 | 59.6 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50536 |
| `nat_loop` | main | 2000000 | 63.5 | 68.2 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2692 |
| `nat_loop` | default | 2000000 | 65.9 | 71.5 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2664 |
| `nat_loop` | budget-6 | 2000000 | 65.8 | 70.3 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2424 |
| `nat_loop` | no-specialise | 2000000 | 67.5 | 71.9 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2688 |
| `list_pipeline` | main | 29999000000 | 93.9 | 100.6 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2680 |
| `list_pipeline` | default | 29999000000 | 102.0 | 107.7 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2944 |
| `list_pipeline` | budget-6 | 29999000000 | 114.2 | 118.9 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2920 |
| `list_pipeline` | no-specialise | 29999000000 | 111.8 | 116.2 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2636 |
| `records` | main | 2700000.000000466 | 61.3 | 67.5 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3404 |
| `records` | default | 2700000.000000466 | 64.0 | 69.7 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3436 |
| `records` | budget-6 | 2700000.000000466 | 64.1 | 66.4 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3716 |
| `records` | no-specialise | 2700000.000000466 | 60.6 | 64.1 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3220 |
| `fail_chain` | main | 200 | 78.7 | 82.5 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2892 |
| `fail_chain` | default | 200 | 85.1 | 88.2 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2688 |
| `fail_chain` | budget-6 | 200 | 86.7 | 93.2 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2688 |
| `fail_chain` | no-specialise | 200 | 96.9 | 103.3 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2688 |

Witness calls (`wops`, a counter added in this phase: `dup` and `drop` calls made through a value witness), functions emitted and size of the LLVM IR:

| Program | main: functions / IR bytes | default: witness calls / functions / IR bytes | budget-6 | no-specialise |
|---|---|---|---|---|
| `bintrees` | 8 / 4957 | 0 / 10 / 8131 | 2097150 / 8 / 5429 | 2097150 / 8 / 5429 |
| `nat_loop` | 12 / 8205 | 0 / 16 / 13683 | 400 / 15 / 12410 | 2000600 / 12 / 9288 |
| `list_pipeline` | 11 / 6988 | 0 / 15 / 12885 | 8000400 / 12 / 9149 | 10000600 / 11 / 8217 |
| `records` | 19 / 14849 | 0 / 22 / 20623 | 3000300 / 17 / 14317 | 4000300 / 14 / 11871 |
| `fail_chain` | 11 / 11160 | 0 / 13 / 16245 | 4000400 / 12 / 14190 | 9995800 / 11 / 12525 |

Relative to `main` (best wall time):

| Program | default | no-specialise |
|---|---:|---:|
| `bintrees` | +2% | +4% |
| `nat_loop` | +4% | +6% |
| `list_pipeline` | +9% | +19% |
| `records` | +4% | −1% |
| `fail_chain` | +8% | +23% |

## What the numbers say

1. **Allocations, reuses, frees, counted `dup`s and `drop`s are identical in all four builds.** The change of model moved no memory traffic. The whole cost of the baseline is in how operations are reached, not in how many there are.

2. **Baseline only (`--no-specialise`) costs up to 23%.** The cost follows the witness calls: `fail_chain` and `list_pipeline` make about ten million of them (each list element is `dup`ed and `drop`ped through a witness inside the derived `foldLeft`, `map` and `unfold`, which are compiled once for every element type), and they are the two that slow down most. `records` makes four million and does not slow down; `bintrees` makes two million and loses 4%. A witness call is an indirect call that the C compiler cannot inline, around a `dup` or `drop` that for a `u64` element does nothing.

3. **The default mode recovers the witness calls completely.** With `B = 512` every requested clone is built and `wops` is 0 in all five programs. Every function in these benchmarks has a baseline of at most 7 Core bindings, so any budget from 16 up gives the same code; the budget only bites below 8.

4. **The default mode is still 2% to 9% slower than `main`, and that is not the baseline's doing.** Building each milestone separately (best of 15, machine busier, so the absolute numbers are a little higher):

| Program | Build | Result | Best wall (ms) | Median wall (ms) | Allocations | Reuses | Frees | dups | drops | Peak RSS (KiB) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | main | 2097151 | 55.0 | 59.3 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 51072 |
| `bintrees` | M4 | 2097151 | 54.6 | 58.4 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50304 |
| `bintrees` | M2 | 2097151 | 55.5 | 58.6 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 51020 |
| `bintrees` | M3 | 2097151 | 55.9 | 58.9 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50796 |
| `bintrees` | M6 | 2097151 | 54.4 | 60.2 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50252 |
| `bintrees` | M7 | 2097151 | 55.7 | 58.7 | 2097151 | 0 | 2097151 | 8388601 | 12582907 | 50816 |
| `nat_loop` | main | 2000000 | 63.8 | 69.0 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2380 |
| `nat_loop` | M4 | 2000000 | 63.6 | 67.6 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2668 |
| `nat_loop` | M2 | 2000000 | 65.4 | 67.7 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2440 |
| `nat_loop` | M3 | 2000000 | 65.1 | 68.9 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2696 |
| `nat_loop` | M6 | 2000000 | 66.3 | 70.7 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2628 |
| `nat_loop` | M7 | 2000000 | 64.1 | 69.1 | 4000402 | 0 | 4000402 | 6000801 | 10002206 | 2668 |
| `list_pipeline` | main | 29999000000 | 95.5 | 100.7 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2940 |
| `list_pipeline` | M4 | 29999000000 | 96.7 | 102.0 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2924 |
| `list_pipeline` | M2 | 29999000000 | 101.9 | 108.3 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2944 |
| `list_pipeline` | M3 | 29999000000 | 105.4 | 108.5 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2636 |
| `list_pipeline` | M6 | 29999000000 | 103.1 | 107.3 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2940 |
| `list_pipeline` | M7 | 29999000000 | 103.6 | 107.9 | 4000602 | 4000000 | 4000602 | 14000801 | 18002806 | 2696 |
| `records` | main | 2700000.000000466 | 62.7 | 66.3 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3432 |
| `records` | M4 | 2700000.000000466 | 61.8 | 65.3 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3448 |
| `records` | M2 | 2700000.000000466 | 62.2 | 65.3 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3436 |
| `records` | M3 | 2700000.000000466 | 64.8 | 67.5 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3420 |
| `records` | M6 | 2700000.000000466 | 63.9 | 67.5 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3428 |
| `records` | M7 | 2700000.000000466 | 64.3 | 67.8 | 3000302 | 2000000 | 3000302 | 9000401 | 13001206 | 3460 |
| `fail_chain` | main | 200 | 79.0 | 82.1 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2948 |
| `fail_chain` | M4 | 200 | 80.1 | 84.2 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2636 |
| `fail_chain` | M2 | 200 | 85.9 | 89.3 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2936 |
| `fail_chain` | M3 | 200 | 85.0 | 86.9 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2936 |
| `fail_chain` | M6 | 200 | 84.7 | 89.9 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2636 |
| `fail_chain` | M7 | 200 | 85.6 | 90.4 | 4000602 | 0 | 4000602 | 5997601 | 9999006 | 2916 |

   `M4` (shared version primary, clone budget and score, `--no-specialise`) is indistinguishable from `main`. The step up is at `M2`, the reference map in the cell header with fields in declaration order: about 6% on `list_pipeline` and 8% on `fail_chain`, the two list-heavy programs. `M3` (64-bit slots for closure arguments) adds about 2% on `records`, whose closures pass an `f64` that now crosses as bits. `M5` to `M8` (witness constructs, tables, baseline lowering, the specialiser) add nothing in the default mode. The reference map is what makes one cell layout serve every type instance (Rule CM3.4); its cost is paid by monomorphic code too.

   An experiment replaced the recursive `fx_drop` by one that loops on a cell's last reference: `records` gained about 5% and `list_pipeline` about 2%, `fail_chain` nothing. So the walk over the map is part of the cost but not all of it; the rest has not been located. Neither `perf` nor `valgrind` is installed on this machine, so there are no instruction counts.

5. **On the ownership axis a clone buys nothing measurable.** At `M4` the default mode (exclusive versions reuse without a test) and `--no-specialise` (shared versions reuse after `rc == 1`) ran in the same time on all five programs, and their counts are identical because the runtime counts both kinds of reuse alike. The static proof of exclusivity is worth having for `fip`, where it is a guarantee; as an optimisation it has not shown a gain here.

6. **A table call costs more than a witness call, but no benchmark makes one.** None of the five has a parameter bounded by a capability. `examples/01_basics` does (`fib_iter(n: N is Numeric)`): with `--no-specialise` its arithmetic goes through `Numeric`'s table and its three closures become heap cells because they capture the table (60 more allocations in that run). The benchmarks therefore understate the cost of the baseline for code written against capabilities, which is the code Fixed asks people to write. A benchmark with bounded parameters is the first thing to add.

7. **The score has a blind spot the budget hides.** The weight counts only the `fold` steps that lexically enclose a call site. In `records` the request that runs a million times has the same weight as one that runs a hundred times, and wins only by the order of the key text (`spec/compilation_model.md` OQ-CM10). With the reference budget both are built, so it costs nothing today.

## Code size

The default mode emits more code than `main` (IR bytes +39% to +84% on these programs): it keeps the baseline version of every function that has clones, as Rule CM3.1 requires, where `main` emitted only the instances. In a program that is one package with `main` and no importer, a baseline version that every call site bypasses is dead; removing it there is an optimisation the spec allows (Rule CM3.2 compiles what a root reaches) and this phase did not do.

## Not measured

- Compile time. Each round of the specialiser lowers the whole program again.
- Programs larger than a page. The largest function here has 7 Core bindings; the budget of 512 was never under pressure.
- 32-bit targets, where the 64-bit slot costs four bytes per reference field.
