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

## 2. Where the cost of `list_pipeline` and `records` goes (OQ-CM17)

**Answer.** The 4% against the monomorphising backend does not reproduce at the base commit: `list_pipeline` and `records` now take 8% and 6% fewer cycles than `5a0eedd`, while executing 14% and 4% more instructions. More than half of the instructions and cycles of these two programs are spent allocating and freeing cells, mostly inside glibc's `malloc` and `free`; much of the rest is reference counting reached through calls into a runtime the C compiler cannot inline. The generic drop now runs in constant native stack for any shape (Rule PR7.3), at no cost.

### Against the monomorphising backend

`5a0eedd` rebuilt from the commit (`git archive`), against the base commit; median instructions, minimum cycles of 11 interleaved runs:

| Program | `5a0eedd` instructions / cycles | `d646b53` instructions / cycles | Change |
|---|---:|---:|---:|
| `bintrees` | 916.10 M / 245.5 M | 833.79 M / 234.4 M | −9.0% / −4.5% |
| `nat_loop` | 1,282.88 M / 313.1 M | 1,466.92 M / 282.1 M | +14.3% / −9.9% |
| `list_pipeline` | 1,796.56 M / 467.1 M | 2,056.73 M / 428.9 M | +14.5% / −8.2% |
| `records` | 1,420.50 M / 308.5 M | 1,477.12 M / 291.3 M | +4.0% / −5.6% |
| `fail_chain` | 1,466.42 M / 387.9 M | 1,386.61 M / 282.9 M | −5.4% / −27.1% |

The profiles below show why both columns moved: the old recursive `fx_drop` (340 M instructions, 98 M cycles in `list_pipeline`) is gone, and the derived `map` and `unfold`, which now build the list reversed in a loop and then reverse it in place, add 6,000,200 reuses to `list_pipeline` (10,000,200 against 4,000,000) and 6 M `dup`s and `drop`s.

### Profiles

`perf record`, instructions and cycles by where they are spent (M = millions; shares in brackets):

| Where | `list_pipeline` `5a0eedd` | `list_pipeline` `d646b53` | `records` `5a0eedd` | `records` `d646b53` |
|---|---:|---:|---:|---:|
| allocation (`malloc` family, `fx_alloc`, `fx_alloc_at`) | 412 / 68 | 317 (15%) / 62 (14%) | 247 / 44 | 164 (11%) / 34 (12%) |
| free (glibc `free` family) | 640 / 156 | 895 (43%) / 185 (41%) | 565 / 122 | 675 (46%) / 125 (43%) |
| `free_cell` (child walk) | – | 51 (2%) / 8 (2%) | – | 103 (7%) / 19 (6%) |
| `dup` (`fx_dup`) | 79 / 30 | 101 (5%) / 25 (6%) | 72 / 16 | 70 (5%) / 15 (5%) |
| `drop` (`fx_drop`) | 340 / 98 | 109 (5%) / 15 (3%) | 290 / 63 | 44 (3%) / 9 (3%) |
| reuse (`fx_reuse`, `fx_reuse_excl`, with the test) | 35 / 18 | 311 (15%) / 76 (17%) | 55 / 20 | 221 (15%) / 44 (15%) |
| closure bodies (`lambda.*`) | 120 / 48 | 83 (4%) / 22 (5%) | 102 / 28 | 120 (8%) / 23 (8%) |
| loop bodies (other program functions) | 164 / 60 | 173 (8%) / 45 (10%) | 82 / 21 | 55 (4%) / 14 (5%) |
| kernel, loader, other | 7 / 2 | 18 (1%) / 9 (2%) | 9 / 3 | 25 (2%) / 11 (4%) |
| total | 1,797 / 481 | 2,057 / 446 | 1,420 / 317 | 1,477 / 294 |

Skid undercounts `fx_dup`: by the counters and the machine code (`fx_dup` is 10 instructions plus the call), `list_pipeline`'s 20,001,001 `dup`s cost about 220 M instructions, not 101 M. The reuse test itself is 4 instructions of each of the 10,000,200 reuses (40 M, §1); the rest of the reuse row is the call, the child walk of `reclaim`, and its counter.

### The generic drop in bounded native stack (Rule PR7.3)

Before: `free_cell` looped on the highest marked slot and called `fx_drop` on every other child, one native frame per level for a tree that is deep in another child. The new test `compiler/src/test/fixed/modes/deep_drops.fixed` builds a tree of depth 1,000,000 leaning left (the deep child is not the last reference), one leaning right, and a list of 1,000,000 cells each holding a cell, and drops each through the runtime. With the old runtime it dies with `SIGSEGV` inside `free_cell` (`gdb`: thousands of nested `free_cell` frames). With the new one it passes in both build modes with no leak, and also with the stack limited to 256 KiB (`ulimit -s 256`).

The fix: a child other than the last whose count reaches zero is pushed on a work list linked through its own count field, which nothing reads once the count is zero; the loop takes the next cell from the list when a chain ends. A list is still freed in the same loop without touching the work list. The work list is a static, not a local, so `free_cell` keeps no extra register across `free`: the first version with a local cost 0.3% to 0.9% more instructions, this one costs fewer than before. Base commit against the fix, median instructions, minimum cycles of 9 interleaved runs:

| Program | Instructions before | after | Change | Cycles before | after |
|---|---:|---:|---:|---:|---:|
| `bintrees` | 833.79 M | 831.69 M | −0.25% | 241.9 M | 241.0 M |
| `nat_loop` | 1,466.92 M | 1,452.92 M | −0.95% | 285.5 M | 285.1 M |
| `list_pipeline` | 2,056.73 M | 2,042.73 M | −0.68% | 440.7 M | 434.7 M |
| `records` | 1,477.12 M | 1,470.12 M | −0.47% | 298.9 M | 298.1 M |
| `fail_chain` | 1,386.61 M | 1,372.62 M | −1.01% | 286.4 M | 287.5 M |
| `shared_map` | 1,169.53 M | 1,161.46 M | −0.69% | 224.5 M | 234.0 M |
| `fresh_map` | 1,707.43 M | 1,693.36 M | −0.82% | 349.7 M | 352.6 M |
| `acc_loop` | 1,446.29 M | 1,432.29 M | −0.97% | 279.9 M | 279.1 M |
| `points` | 1,435.31 M | 1,429.31 M | −0.42% | 277.4 M | 281.7 M |
| `temps` | 1,610.29 M | 1,590.29 M | −1.24% | 302.9 M | 302.1 M |
| `scratch_tree` | 941.64 M | 938.98 M | −0.28% | 250.9 M | 250.8 M |

Allocations, frees, `dup`s, `drop`s and reuses are unchanged in every program, and no pinned reuse count moved. The cycle differences are within the noise of this session except `shared_map` (+4%). A second session of 15 interleaved runs with a second link of the base as a control: `shared_map` −0.2% (the two links of the base differed by 2.6%), `fail_chain` +0.5% (control 1.3%), `points` +1.7% (control 0.0%). So `points` takes about 1.7% more cycles with fewer instructions; the cause (code placement, or the store of the work list) was not found.

### The remaining cost centres

Three experiments relinked the default-mode IR of every benchmark with a changed runtime or environment; the counter experiment is in §1. Median instructions and minimum cycles of 7 interleaved runs, change against the shipped runtime:

| Program | Runtime inlined into the program (`-flto`): instructions / cycles | glibc's per-size cache raised from its default to 65,535 entries: instructions / cycles |
|---|---:|---:|
| `bintrees` | −22.4% / −35.2% | −1.1% / +0.5% |
| `nat_loop` | −19.0% / −37.1% | −35.1% / −27.2% |
| `list_pipeline` | −24.1% / −26.7% | −25.5% / −20.4% |
| `records` | −15.1% / −27.6% | −34.6% / −30.6% |
| `fail_chain` | −15.6% / −35.2% | −37.9% / −31.8% |
| `shared_map` | −19.4% / −38.3% | −45.0% / −37.2% |
| `fresh_map` | −22.1% / −37.1% | −30.9% / −24.0% |
| `acc_loop` | −15.5% / −25.2% | −0.6% / −2.7% |
| `points` | −15.8% / −25.2% | −35.7% / −29.8% |
| `temps` | −14.5% / −25.1% | −0.6% / −1.7% |
| `scratch_tree` | −24.2% / −43.6% | −26.7% / −21.9% |

(The cache experiment sets `GLIBC_TUNABLES=glibc.malloc.tcache_count=65535`. It ran in its own session, and its percentages are against the `def` figures of the LTO session, not against interleaved runs; the large effects stand, the cycle figures of `bintrees`, `acc_loop` and `temps` are noise.)

In order of size, for `list_pipeline` (2,056.7 M instructions, about 430 M cycles):

1. **The allocator: about 58% of instructions and 55% of cycles** (allocation 15%, glibc `free` 43%). A list of 10,000 cells freed by `fold` and allocated again by the next `unfold` overflows glibc's per-thread cache of freed chunks, and every free past it takes the general path (`_int_free_merge_chunk`, `unlink_chunk`): about 225 instructions per free. Raising the cache removes 25% of the instructions and 20% of the cycles here, 35% and 31% on `records`. *Missing optimisation*: a free list per cell size in the runtime (a cell's size is fixed by its type, so `fx_alloc` and `free_cell` could keep one), or an allocator built for this pattern (Koka uses mimalloc). Not inherent in the model; the heap traffic itself is (4,000,602 allocations).
2. **Every `dup`, `drop`, reuse and constructor is a call into a runtime `clang` cannot see: inlining it removes 14% to 24% of instructions and 25% to 44% of cycles on the eleven programs.** (An upper bound on the cost of the calls themselves: link-time optimisation also optimises across them.) `Backend.ship` compiles `fixed_rt.c` as a separate unit, so `fx_dup` (10 instructions) and the fast path of `fx_drop` are never inlined into program code. Rule PR7.3 says the fast path of `fx_drop` "is kept short so that it is inlined at every drop site"; it is inlined only into the runtime's own callers. *Missing optimisation* (and a statement in the spec that is not true of the build): link-time optimisation, or emitting the fast paths as LLVM IR with the program.
3. **Fields moved out at a reuse: 10,000,200 `dup`s and 10,000,200 `drop`s, about 19 instructions per reuse, about 190 M (9%).** Each reuse `dup`s the field it reads (the tail) and `reclaim` then `drop`s it from the old cell, which returns the count to where it was. *Missing optimisation*: Perceus's reuse specialisation (Rules PR6.1 and PR7.2): where the cell is exclusive, or inside the `rc == 1` branch, move the fields out without touching their counts.
4. **The step of `unfold` is called through a closure and its layer is a cell: about 2,000,000 of the 4,000,602 allocations, and an indirect call per element.** The derived `unfoldLoop` calls the step through its code pointer (`%layer.49.s = call i64 %code.81(ptr %step.48, i64 %seed.47)`), gets back the layer `List.Cons(i, i + 1)` as a fresh cell, reads it, `drop`s it and allocates the real cell; `upto` allocates the step's closure on every call. *Not built*, though Rule 7.4.b.2 requires it in every build mode (a mandatory specialisation, `spec/compilation_model.md` Rule CM5.5): a lambda argument of a derived `unfold` is to be inlined, with no closure allocation and no indirect call per step. With the step inlined, the layer is a constructor matched at once and needs no cell. Related count traffic: 8,000,000 closure `dup`s and as many environment `drop`s (one per step of each `unfold`, `map` and `fold`; 6,000,000 of them on static closures whose count never changes), about 160 M instructions (8%). `map` and the left `fold` (`Folding`) are not derived `fold`s, so Rule 7.4.b.2 does not cover them; for them it is a *missing optimisation* (inlining a known lambda, or a borrowed closure parameter).
5. **`map` and `unfold` build reversed and then reverse: 6,000,200 extra reuses.** Rule 7.4.b.3 says "`unfold` and `map` build the cells in order in a loop"; the reversal pass is the backend's way of staying in bounded stack without a destination. *Not built as specified*: building in order into a destination (Rule MM7.1, tail recursion modulo cons) needs no second pass.
6. **The counters: 2.2% to 3.7% of instructions and 0.6% to 5.1% of cycles** (§1). Measurement overhead compiled into every binary; a build without them is a missing option, not a fault.
7. **The fold of `List.unfold` builds the whole list first** (§3): not a cost in `list_pipeline` (the list is 10,000 cells), but the whole 95 MB of `acc_loop` and `temps`.

What remains is count traffic the model asks for (the `dup` of the tail read by `fold`, 2,000,000, and the `drop`s that free cells) and the loop and closure bodies themselves (12%).

For `records` the order is the same: allocator 57% of instructions (glibc `free` alone 46%), runtime calls (−15% instructions, −28% cycles inlined), then reuse and count traffic (13,000,501 `dup`s and 17,001,507 `drop`s for 3,000,302 allocations).

