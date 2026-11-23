# Runtime measurements, 2026-11-23: three open debts

This report settles three questions left open by `runtime-2026-11-20-phase-4.1.md`: whether the measurement behind ownership clones hides a compiler fault (`spec/compilation_model.md` OQ-CM16), where the cost of `list_pipeline` and `records` goes (OQ-CM17), and why `acc_loop` and `temps` peak near 95 MB.

- Base commit: `d646b53` (branch `kryptt/s-debts`). The older monomorphising backend is `5a0eedd`.
- Machine: Linux 7.2.15-gentoo-x86_64 x86_64, AMD RYZEN AI MAX+ 395 w/ Radeon 8060S, load average about 6 (other agents were building).
- Toolchain: clang version 22.1.8, `clang -O2`; glibc 2.43.
- Instructions and cycles are user space only, read through `perf_event_open` (`/var/tmp/fixed-hdr/tools/pc`). Instruction counts are deterministic (they vary by fewer than 10 between runs) and decide; cycles are the minimum of the runs stated, with the builds interleaved. Within one session, two links of the same code differed by up to 3.2 M cycles (about 1%); between sessions, and with code placement changed, differences reached 2.6%. Cycle differences below about 2% are therefore not findings unless a control in the same session says otherwise.
- Profiles are `perf record` samples (user space, 50 kHz, five runs summed per event), scaled to the deterministic totals. Sampling has skid: a few cycles after a call are charged to the caller, so a short runtime function (`fx_dup`) is undercounted by up to half. The large categories repeat to about ±10% between profiles.
- Variant runtimes were linked against the same LLVM IR the compiler emitted (`clang -O2 -std=c11 prog.ll variant.c -lm`, as `Backend.ship` does), so a comparison changes only what it names.

## 1. Is the measurement behind ownership clones sound? (OQ-CM16)

**Answer.** There is no compiler fault. The exclusive clones that the benchmarks rely on are called and run millions of times, the `rc == 1` test is in the code and runs, and the two paths differ by exactly four instructions per reuse. What the old measurement could not see was the cost of those four instructions: about 2 to 3 cycles per reuse in the shipped build, up to about 4% of the run time of the programs with reuses, which is inside the ±5% wall-time noise the earlier report worked with. With the runtime's fast paths inlined into the program, the test costs at most about 2 cycles per reuse, mostly nothing measurable.

### What an exclusive and a tested reuse site compile to

In the IR, the exclusive clone of a function is the baseline with one call changed. `List of u64.reverseOnto` in `list_pipeline`, baseline against the clone `#x[0:List of u64#1,cell]`, with SSA numbers removed:

```
<   %acc.tok1 = call ptr @fx_reuse(ptr %acc)
---
>   %acc.tok1 = call ptr @fx_reuse_excl(ptr %acc)
```

Every other line of the two bodies is the same: both `dup` the tail they read (`call void @fx_dup(ptr %f.59)`), both take the token and pass it to `fx_alloc_at`. The runtime is a separate C file that `clang` compiles on its own (`Backend.ship`), so it cannot see into `fx_reuse` from the program and cannot remove the test. In the machine code (`objdump -d` of `list_pipeline`), the passing path of `fx_reuse` is the path of `fx_reuse_excl` plus:

```
27cb:  mov    (%rdi),%rax        ; load the count
27ce:  cmp    $0x1,%rax          ; rc == 1 ?
27d2:  jne    2829               ; to the failing path
27eb:  jmp    284e               ; to the shared epilogue (layout)
```

Three instructions are the test; the fourth is a jump that block layout adds. Both paths then run the same `reclaim` (drop the children through the reference map, clear `refs`). The runtime is the same file in every build mode, so these are the only differences.

### Do the exclusive clones run?

A variant runtime recorded the return address of every `fx_reuse` and `fx_reuse_excl` call (linked `-no-pie`, resolved with `addr2line`; `clang` inlined each `reverseOnto` clone into its caller):

| Program | Exclusive reuses, and where | Tested reuses, and where |
|---|---|---|
| `nat_loop` | 2,000,000 in `Nat.unfoldLoop#x[2:Nat#0,cell]`, 200 in `List of u64.unfoldLoop#x[…]` | 2,000,000 in `Nat.foldDown` |
| `list_pipeline` | 2,000,200 in `List of u64.unfoldLoop#x[…]`, 4,000,000 in `mapLoop#x[2:List of u64#1,cell]` (its reverse) | 4,000,000 in `mapLoop#x[…]` (the input list) |
| `records` | 1,000,000 in `List of Order.unfoldLoop#x[…]`, 1,000,000 in `List of Order.mapLoop#x[…]`, 100 in `List of u64.unfoldLoop#x[…]` | 1,000,000 in `mapLoop#x[…]`, 1,000,000 in `lambda.158` |
| `fresh_map` | 2,010,200 in `unfoldLoop#x[…]`, 2,000,000 in `mapLoop#x[…]` | 2,000,000 in `mapLoop#x[…]` |
| `acc_loop` | 2,000,000 in `unfoldLoop#x[…]` | 2,000,000 in `main` (the accumulator) |
| `points` | 1,000,000 in `List of P.unfoldLoop#x[…]`, 1,000,000 in `List of P.mapLoop#x[…]`, 200 | 1,000,000 in `mapLoop#x[…]`, 1,000,000 in `lambda.148` |
| `temps` | 2,000,000 in `unfoldLoop#x[…]` | none |

With ownership clones off, every former exclusive site passes its `rc == 1` test and none fails (`list_pipeline`: 0 / 10,000,200 / 0; the table below), so no clone wrongly treats a shared cell as exclusive on these programs. The tested reuses are where Rule PR6.3.a puts them: an owned `Heap` argument (the list `map` receives, the accumulator of `fold`) requests no clone. Some exclusive clones are in the IR and never called: of the 4 to 8 functions per program that contain a `fx_reuse_excl` call, 2 to 4 are not reachable from `main` (all of them `reverseOnto` clones keyed on the outer cell only, `#x[0:cell]`, `#x[1:cell]`). That costs code size, not time; the measurement counts executions, not sites.

### Are the counters trustworthy, and what do they cost?

Each path increments exactly one counter (`incq n_reuse_excl`, `incq n_test_pass`, `incq n_test_fail`), so counting adds the same cost to both. The counters are compiled into every binary, including the measured ones; only the printing waits for `FIXED_RT_STATS`. A runtime with every counter increment removed, against the shipped one (11 interleaved runs):

| Program | Instructions saved | Cycles saved (min) |
|---|---:|---:|
| `bintrees` | 3.5% | 1.0% |
| `nat_loop` | 2.7% | 4.5% |
| `list_pipeline` | 2.9% | 1.6% |
| `records` | 2.4% | 1.2% |
| `fail_chain` | 2.6% | 3.6% |
| `shared_map` | 2.2% | 2.0% |
| `fresh_map` | 2.8% | 0.6% |
| `acc_loop` | 2.5% | 2.4% |
| `points` | 2.5% | 2.6% |
| `temps` | 3.1% | 5.1% |
| `scratch_tree` | 3.7% | 1.1% |

About one instruction per allocation, free, `dup`, `drop` and reuse (62 M events and 60 M instructions in `list_pipeline`); in cycles up to about 5%, but a re-run by a reviewer (7 runs) gave `fresh_map` 1.5% slower without counters and `temps` 6.1% faster, so cycle figures below about 2% here are noise.

### Three builds of each benchmark

Default mode, `--no-ownership-clones` (type clones only) and `--no-specialise`. Instructions (median of 5), minimum cycles of 5, reuses exclusive / passed / failed:

| Program | default | no ownership clones | `--no-specialise` | Reuses, default | Reuses, other two |
|---|---|---|---|---|---|
| `bintrees` | 833.79 M / 241.0 M | +0 / 238.4 M | +35.65 M / 240.2 M | 0 / 0 / 0 | 0 / 0 / 0 |
| `nat_loop` | 1,466.92 M / 289.6 M | +8.00 M / 293.0 M | +20.01 M / 290.3 M | 2,000,200 / 2,000,000 / 0 | 0 / 4,000,200 / 0 |
| `list_pipeline` | 2,056.73 M / 430.4 M | +24.00 M / 456.3 M | +130.01 M / 509.8 M | 6,000,200 / 4,000,000 / 0 | 0 / 10,000,200 / 0 |
| `records` | 1,477.12 M / 293.0 M | +8.00 M / 294.2 M | +15.00 M / 301.4 M | 2,000,100 / 2,000,000 / 0 | 0 / 4,000,100 / 0 |
| `fail_chain` | 1,386.61 M / 283.0 M | +8.00 M / 292.6 M | +83.98 M / 330.3 M | 2,000,200 / 0 / 0 | 0 / 2,000,200 / 0 |
| `shared_map` | 1,169.53 M / 226.9 M | +8.04 M / 237.4 M | +48.30 M / 256.6 M | 2,010,200 / 0 / 2,000,000 | 0 / 2,010,200 / 2,000,000 |
| `fresh_map` | 1,707.43 M / 353.4 M | +16.04 M / 362.1 M | +94.30 M / 396.6 M | 4,010,200 / 2,000,000 / 0 | 0 / 6,010,200 / 0 |
| `acc_loop` | 1,446.29 M / 270.3 M | +8.00 M / 274.8 M | +58.00 M / 306.5 M | 2,000,000 / 2,000,000 / 0 | 0 / 4,000,000 / 0 |
| `points` | 1,435.31 M / 275.7 M | +8.00 M / 285.7 M | +11.01 M / 284.9 M | 2,000,200 / 2,000,000 / 0 | 0 / 4,000,200 / 0 |
| `temps` | 1,610.29 M / 303.0 M | +8.00 M / 302.7 M | +56.00 M / 332.7 M | 2,000,000 / 0 / 0 | 0 / 2,000,000 / 0 |
| `scratch_tree` | 941.64 M / 242.8 M | +0.08 M / 236.4 M | +43.62 M / 250.6 M | 20,000 / 0 / 0 | 0 / 20,000 / 0 |

Turning the ownership clones off adds exactly 4.0 instructions per exclusive reuse in every program (for example 24,000,818 / 6,000,200 in `list_pipeline`), and changes no allocation, `dup`, `drop` or free. The rest of the distance to `--no-specialise` is the type axis (witness calls).

### The test alone, cycles per reuse

Comparing builds also compares code layout. To isolate the test, a variant runtime makes `fx_reuse_excl` test `rc == 1` as `fx_reuse` does, linked with the same default-mode IR; the program code is identical, and the variant executes exactly the instructions of `--no-ownership-clones` (for example 2,080,733,839 in `list_pipeline`). Minimum of 15 interleaved runs; "noise" is the difference between two links of the unchanged runtime:

| Program | Exclusive reuses | Instructions per reuse | Cycles per reuse | Share of cycles | Noise (cycles per reuse) |
|---|---:|---:|---:|---:|---:|
| `nat_loop` | 2,000,200 | 4.0 | 0.6 | 0.4% | ±1.5 |
| `list_pipeline` | 6,000,200 | 4.0 | 3.6 | 5.1% | ±0.1 |
| `records` | 2,000,100 | 4.0 | 1.7 | 1.2% | ±1.3 |
| `fail_chain` | 2,000,200 | 4.0 | 2.8 | 2.0% | ±1.3 |
| `shared_map` | 2,010,200 | 4.0 | 4.2 | 3.8% | ±1.0 |
| `fresh_map` | 4,010,200 | 4.0 | 2.2 | 2.5% | ±0.1 |
| `acc_loop` | 2,000,000 | 4.0 | 3.3 | 2.4% | ±0.9 |
| `points` | 2,000,200 | 4.0 | 2.7 | 1.9% | ±0.2 |
| `temps` | 2,000,000 | 4.0 | −0.3 | −0.2% | ±1.6 |

The "noise" control has the same machine code as the base, so it measures run-to-run noise, not layout; the variant runtime moves `fx_reuse`, `fx_dup` and `fx_drop` by up to 0x70 bytes. A reviewer relinked both runtimes with `-falign-functions=64` (15 interleaved runs, `/var/tmp/fixed-s-debts/review-meas/align.txt`): `list_pipeline` 2.8, `shared_map` 2.1, `acc_loop` 2.2, `points` 2.1 cycles per reuse. The test costs about 2 to 3 cycles per reuse; up to 1 to 2 cycles of the larger figures above are placement.

The same experiment with the runtime's reuse, allocation, `dup` and `drop` forced inline into the program (link-time optimisation, `always_inline`): the test costs 3.0 to 5.5 instructions per reuse and between −0.1 and +1.8 cycles. No control link was made for these builds; against the noise of the shipped builds, only `points` (+0.7) and `temps` (+1.8) are at or above it, and neither by much. (Plain link-time optimisation without forcing inlines `fx_reuse_excl` but not the larger `fx_reuse`, which makes the exclusive path look up to 33 instructions cheaper per reuse in six of the programs; that is the inliner's size threshold, not exclusivity.)

### Conclusion for Rule PR6.3.a

No compiler fault: the clones run, the test is executed, the paths differ only by the test. Rule PR6.3.a's premise, that the run-time test runs as fast as unconditional reuse, holds only approximately in today's build: the test costs 4 instructions and about 2 to 3 cycles per reuse, up to about 4% of the run time of a program that does little else. With the runtime's fast paths inlined (§2) it costs at most about 2 cycles per reuse and mostly none measurable. Neither path uses what exclusivity could buy beyond the test: both `dup` the field they move out and `drop` it again in `reclaim`, about 20 instructions per reuse in two runtime calls. That saving is open to the tested path too, inside its `rc == 1` branch (as Koka does), so it is not an argument for ownership clones either. The rule does not need to change on this evidence; its stated reason should cite these numbers instead of "ran as fast".

