# Runtime, 2026-11-23: counters out of release builds, free lists, inlined fast paths

Three changes to the run-time system, each measured on its own, in the order they were made: the counters became a build switch (§1), cells come from free lists per size instead of `malloc` (§2), and the runtime's fast paths are compiled into the program so that `clang -O2` inlines them (§3). They answer items 1, 2 and 6 of `runtime-2026-11-23-debts.md` §2.

- Base commit: `e758b98` (branch `kryptt/u-runtime`); its benchmark binaries and numbers are `/var/tmp/fixed-bench/l1.txt`, measured with the counters in.
- Machine: Linux 7.2.15-gentoo-x86_64 x86_64, AMD RYZEN AI MAX+ 395 w/ Radeon 8060S, shared with other agents (load average 4 to 6). Toolchain: clang version 22.1.8, glibc 2.43.
- Method: every build of a comparison runs in one interleaved session, 7 rounds (default mode and `--no-specialise` are separate sessions). Instructions (user space, `perf_event_open` through `/var/tmp/fixed-hdr/tools/pc`) are deterministic and decide, median of the 7; cycles are the minimum of the first 5 rounds; wall time is the best of the 7, as the runtime measures it from `main` (`wall_ns`), in a separate run with `FIXED_RT_STATS=1`, which also gives peak RSS, the largest of the 7 (`peak_rss_kb`); text is `size`'s text column of the executable. Scripts: `/var/tmp/fixed-u-runtime/tools/` (`build.sh`, `meas.sh`, `table.py`), raw data `/var/tmp/fixed-u-runtime/meas-*.txt`. As in the earlier reports, cycle differences below about 2% are noise unless a control says otherwise.

## 1. Counters only when asked for

**Design.** Every counter of the runtime (`allocs`, `frees`, `dups`, `drops`, the three reuse counters, `wops`, `frame_allocs`, `frame_frees`, `frame_bytes`) is behind one compile-time switch, `-DFX_STATS`: each increment is written `STAT(n_x++)`, which is the increment with the switch and nothing without it, and the counter variables themselves exist only with it. `Backend.ship` takes `stats` beside `checks`; every test build passes `stats = true`, and `fixed ship --stats` (taken from any position, like the mode flags) builds a measured binary. A plain `fixed ship` builds without them. `FIXED_RT_STATS=1` on a binary without counters prints `fixed: this binary was built without counters (...)` and then `{"counters":false,"peak_rss_kb":…,"wall_ns":…}`: time and memory, which cost nothing during the run, and no zeros where counts would be. `bench/runtime/run.sh` builds with `--stats`.

The specified `fixed release` (`spec/compilation_model.md` §8) is not built; when it is, it is a build without `--stats`.

**Tests.** `RuntimeSuite`, "counters are compiled in only when asked for": the same program built with and without `stats` reports its counts in the first and says that it has none in the second (written first, against a `ship` without the parameter). Every other test that reads counters builds with them, and the reuse and allocation pins of `EmitSuite` did not move.

**Measurement.** Same IR, runtime with and without `-DFX_STATS`. Its IR is byte for byte that of the base binaries in `l1`, and the build with counters executes their instructions to within 22 (`l1` re-measured in the same session; against the numbers in `l1.txt`, within 65).

Default mode:

| Program | Instructions counters | none | Change | Cycles (min of 5) counters | none | Change | Best wall (ms) counters | none | Peak RSS (KiB) counters | none | Text (bytes) counters | none |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 831.69 M | 803.38 M | -3.4% | 227.9 M | 235.6 M | +3.4% | 54.9 | 53.1 | 51,064 | 51,080 | 13,222 | 13,146 |
| `nat_loop` | 1,452.92 M | 1,414.91 M | -2.6% | 283.7 M | 280.6 M | -1.1% | 58.8 | 57.2 | 2,200 | 2,184 | 19,902 | 19,826 |
| `list_pipeline` | 2,042.73 M | 1,984.72 M | -2.8% | 433.8 M | 426.9 M | -1.6% | 94.7 | 94.2 | 2,192 | 2,440 | 18,966 | 18,890 |
| `records` | 1,470.12 M | 1,434.12 M | -2.4% | 293.9 M | 296.9 M | +1.0% | 68.5 | 69.3 | 2,708 | 2,708 | 22,150 | 22,074 |
| `fail_chain` | 1,372.61 M | 1,338.62 M | -2.5% | 287.8 M | 281.8 M | -2.1% | 65.0 | 63.4 | 2,188 | 2,180 | 17,230 | 17,154 |
| `shared_map` | 1,161.46 M | 1,135.29 M | -2.3% | 231.7 M | 227.8 M | -1.7% | 53.8 | 53.3 | 2,704 | 2,704 | 19,142 | 19,066 |
| `fresh_map` | 1,693.36 M | 1,647.19 M | -2.7% | 351.3 M | 354.2 M | +0.8% | 79.0 | 78.3 | 2,180 | 2,448 | 19,222 | 19,146 |
| `acc_loop` | 1,432.29 M | 1,398.29 M | -2.4% | 277.7 M | 278.1 M | +0.2% | 71.5 | 73.0 | 95,336 | 95,348 | 16,382 | 16,306 |
| `points` | 864.65 M | 841.65 M | -2.7% | 172.3 M | 172.2 M | -0.1% | 38.1 | 37.8 | 2,192 | 2,196 | 21,014 | 20,938 |
| `temps` | 1,452.29 M | 1,414.29 M | -2.6% | 276.7 M | 272.4 M | -1.6% | 69.6 | 71.1 | 95,372 | 95,372 | 16,382 | 16,306 |
| `scratch_tree` | 938.98 M | 904.48 M | -3.7% | 241.1 M | 244.8 M | +1.5% | 51.3 | 50.9 | 2,960 | 2,956 | 17,414 | 17,338 |

`--no-specialise`:

| Program | Instructions counters | none | Change | Cycles (min of 5) counters | none | Change | Best wall (ms) counters | none | Peak RSS (KiB) counters | none | Text (bytes) counters | none |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 867.34 M | 836.93 M | -3.5% | 243.8 M | 241.0 M | -1.2% | 56.0 | 55.0 | 51,080 | 51,080 | 12,782 | 12,706 |
| `nat_loop` | 1,472.92 M | 1,432.92 M | -2.7% | 296.1 M | 286.0 M | -3.4% | 61.9 | 59.8 | 2,188 | 2,212 | 14,918 | 14,842 |
| `list_pipeline` | 2,172.74 M | 2,098.73 M | -3.4% | 510.5 M | 503.3 M | -1.4% | 110.6 | 110.3 | 2,444 | 2,436 | 14,510 | 14,434 |
| `records` | 1,485.13 M | 1,443.12 M | -2.8% | 299.8 M | 296.1 M | -1.2% | 70.1 | 68.7 | 2,688 | 2,704 | 15,542 | 15,466 |
| `fail_chain` | 1,456.60 M | 1,414.60 M | -2.9% | 329.1 M | 326.2 M | -0.9% | 72.7 | 72.9 | 2,176 | 2,412 | 13,846 | 13,770 |
| `shared_map` | 1,209.76 M | 1,177.55 M | -2.7% | 260.1 M | 256.6 M | -1.4% | 59.9 | 59.9 | 2,856 | 2,700 | 14,694 | 14,618 |
| `fresh_map` | 1,787.66 M | 1,729.44 M | -3.3% | 403.9 M | 400.3 M | -0.9% | 89.4 | 88.0 | 2,188 | 2,440 | 14,774 | 14,698 |
| `acc_loop` | 1,490.29 M | 1,448.29 M | -2.8% | 312.1 M | 306.1 M | -1.9% | 75.3 | 75.8 | 95,364 | 95,360 | 13,510 | 13,434 |
| `points` | 910.66 M | 881.66 M | -3.2% | 200.4 M | 196.6 M | -1.9% | 43.3 | 42.2 | 2,184 | 2,188 | 14,750 | 14,674 |
| `temps` | 1,508.29 M | 1,462.29 M | -3.0% | 306.9 M | 305.4 M | -0.5% | 77.0 | 76.7 | 95,352 | 95,320 | 13,758 | 13,682 |
| `scratch_tree` | 982.60 M | 945.50 M | -3.8% | 252.7 M | 247.8 M | -1.9% | 51.8 | 51.3 | 2,956 | 2,956 | 14,358 | 14,282 |

The counters cost 2.3% to 3.8% of instructions, about one instruction per counted event (0.94 to 1.12 by the counts), and 76 bytes of text. In cycles, default mode shows no consistent effect (−2.1% to +3.4%, of both signs, mean −0.1%); `--no-specialise`, which counts more events (its witness calls), is faster without counters in all eleven programs, by 0.5% to 3.4% (mean about 1.5%), and in best wall time in nine. Peak RSS moves by up to about 270 KiB either way; that is the variation between runs of one program: `l1` and the build with counters, the same code, differ by 252 KiB on `fail_chain`. **From here on, every comparison states which build it uses and names its base. §2 and §3 compare builds without counters; the base of §2 is `bin/t3-n` (default) and `bin/t3-n-ns` (`--no-specialise`), the base commit's IR with the runtime of this section built without `--stats`.**

## 2. A free list per cell size

**Design.** A heap cell is a block of whole 8-byte words, header included. Blocks of up to 16 words (128 bytes) come from a free list per size, `free_list[words]`: `free_cell` puts a dead cell's block on the list of its size, and `fx_alloc` pops the list of the size it needs. An empty list is served by bumping through a chunk of 256 KiB from `malloc`; the unused tail of a chunk (under 128 bytes) is left. A larger block comes from `malloc` and goes back to `free`. Every kind of heap cell takes the same path by its size: constructors and closures have a fixed size per site, and strings, whose size varies, use the lists up to 103 bytes of text and `malloc` beyond; in the benchmarks the only string is the printed result. Memory on the lists, and the chunks, go back to the system only at exit: a program that frees many cells of one size and then allocates cells of another size keeps the memory of both. Accepted for now; no benchmark does it.

**Where the size comes from.** The descriptor does not give it: the reference map marks only the slots that hold references, so a scalar slot, or any slot past 31, leaves no bit, and the tag is a variant index, not a type. The size is therefore stored, without a new word: the 32-bit tag word of the header is now a 16-bit tag and a 16-bit size (`words`; 0 for a block from `malloc`). The emitted `match` loads the tag as an `i16` and widens it (`movzwl` instead of `mov`); between the two builds measured below, the IR differs only in that load, whose cost was not measured on its own. Tags must fit 16 bits (variant indices, and −1 for closures), which `EmitLLVM` checks where it emits one. Static cells and bare frame cells are emitted with the same 16-bit tag and a size of 0, so the layout holds on any byte order; neither is ever freed. A reuse writes only the tag, so a reused block keeps its size.

**Invariants.**
- A block on a free list is linked through its first payload slot; every block has at least three words (`MIN_WORDS`), so the slot exists. The count word is not the link: checked builds poison it, and `pending` uses it.
- `pending` links a cell through its count word between the moment its count reaches zero and the moment `free_cell` takes it; `free_cell` lists a block only after it has read every child slot. So no cell is on `pending` and on a free list at once, and the list's write of slot 0 never destroys a child not yet dropped.
- A reused cell never passes through a list: `fx_reuse` and `fx_reuse_excl` empty it in place and hand it to a constructor; an unused token is dropped and freed like any cell.
- Static cells (count −1) and frame cells (count −1, or no count word) are never freed, so they never reach a list.
- The leak check of the tests counts cells, not chunks: `allocs` counts every `fx_alloc`, `frees` every cell `free_cell` gives back.
- Checked builds (`-DFX_CHECK_FREE`, added to `checks`): a listed block's count is `FREED`, `-(2^60)`, which no live count (≥ 1), the immortal sentinel (−1), a `pending` link (a pointer, or 0) or the frame poison (`0xA5` bytes, −6,510,615,555,426,900,571) can be. `dup`, `drop`, both reuses and the child walk of `free_cell` stop the program on it (exit 103), and `fx_alloc` checks that a block it takes still holds it. Once a block is allocated again it is live, so a stale reference is caught only until then; the `FX_MALLOC` build below catches the rest.
- `-DFX_MALLOC`: no lists; every cell from `malloc` and back to `free`, so that AddressSanitizer sees each one.
- One set of lists for the one thread. Parallel lanes (`spec/memory_management.md` §17) need a set per lane and a rule for a cell freed by a lane that did not allocate it; noted in the runtime, not built.

**Tests.** `RuntimeSuite`: a freed cell's block is what the next allocation of its size returns, after an allocation of another size that does not get it (glibc would give it to that one, so the test fails on `malloc`), in checked and unchecked builds, with `allocs` = `frees`; a checked build stops with "a count operation on a freed cell" on a `dup`, a `drop` and an `fx_reuse` of a freed cell (the `dup` case, run against the runtime of §1, exits 0), and on the drop of a cell whose child was freed; a cell of 1,000 bytes of payload takes the `malloc` path and is freed. Every test program, the adversarial ones of `compiler/src/test/fixed/modes/` included, runs in the checked build; the reuse and allocation pins of `EmitSuite` did not move, and every benchmark prints the same counts as before (allocations, frees, `dup`s, `drop`s, reuses).

**Measurement.** Both builds without counters; base `bin/t3-n` (§1), against the same IR with the free lists (`bin/t1-n`).

Default mode:

| Program | Instructions base | lists | Change | Cycles (min of 5) base | lists | Change | Best wall (ms) base | lists | Peak RSS (KiB) base | lists | Text (bytes) base | lists |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 803.38 M | 462.13 M | -42.5% | 244.9 M | 253.9 M | +3.7% | 58.0 | 58.0 | 51,052 | 34,628 | 13,146 | 13,358 |
| `nat_loop` | 1,414.91 M | 722.44 M | -48.9% | 280.7 M | 178.3 M | -36.5% | 58.8 | 36.2 | 2,192 | 2,196 | 19,826 | 20,070 |
| `list_pipeline` | 1,984.72 M | 1,270.47 M | -36.0% | 427.1 M | 312.6 M | -26.8% | 95.0 | 64.7 | 2,240 | 2,188 | 18,890 | 19,134 |
| `records` | 1,434.12 M | 780.62 M | -45.6% | 293.0 M | 168.5 M | -42.5% | 68.4 | 35.6 | 2,704 | 2,692 | 22,074 | 22,374 |
| `fail_chain` | 1,338.62 M | 640.35 M | -52.2% | 280.7 M | 156.7 M | -44.2% | 64.3 | 32.7 | 2,532 | 2,188 | 17,154 | 17,366 |
| `shared_map` | 1,135.29 M | 507.61 M | -55.3% | 231.4 M | 123.5 M | -46.6% | 54.5 | 26.1 | 2,732 | 2,440 | 19,066 | 19,294 |
| `fresh_map` | 1,647.19 M | 937.44 M | -43.1% | 352.9 M | 229.4 M | -35.0% | 80.9 | 48.3 | 2,180 | 2,184 | 19,146 | 19,374 |
| `acc_loop` | 1,398.29 M | 724.27 M | -48.2% | 279.1 M | 171.2 M | -38.7% | 74.8 | 43.1 | 95,356 | 64,360 | 16,306 | 16,534 |
| `points` | 841.65 M | 490.38 M | -41.7% | 172.3 M | 110.5 M | -35.9% | 37.4 | 22.6 | 2,168 | 2,196 | 20,938 | 21,182 |
| `temps` | 1,414.29 M | 744.27 M | -47.4% | 270.7 M | 164.9 M | -39.1% | 74.1 | 41.9 | 95,372 | 64,396 | 16,306 | 16,534 |
| `scratch_tree` | 904.48 M | 543.45 M | -39.9% | 241.4 M | 156.3 M | -35.2% | 49.5 | 32.0 | 2,948 | 2,440 | 17,338 | 17,566 |

`--no-specialise`:

| Program | Instructions base | lists | Change | Cycles (min of 5) base | lists | Change | Best wall (ms) base | lists | Peak RSS (KiB) base | lists | Text (bytes) base | lists |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 836.93 M | 495.68 M | -40.8% | 244.9 M | 265.8 M | +8.5% | 57.0 | 58.1 | 51,060 | 34,700 | 12,706 | 12,918 |
| `nat_loop` | 1,432.92 M | 740.44 M | -48.3% | 286.1 M | 181.0 M | -36.7% | 60.2 | 36.6 | 2,188 | 2,188 | 14,842 | 15,070 |
| `list_pipeline` | 2,098.73 M | 1,384.48 M | -34.0% | 507.9 M | 382.5 M | -24.7% | 110.2 | 78.4 | 2,408 | 2,188 | 14,434 | 14,662 |
| `records` | 1,443.12 M | 789.63 M | -45.3% | 293.6 M | 178.8 M | -39.1% | 68.3 | 37.0 | 2,808 | 2,704 | 15,466 | 15,678 |
| `fail_chain` | 1,414.60 M | 716.33 M | -49.4% | 321.4 M | 201.1 M | -37.4% | 72.6 | 42.1 | 2,428 | 2,176 | 13,770 | 13,982 |
| `shared_map` | 1,177.55 M | 549.87 M | -53.3% | 258.3 M | 157.0 M | -39.2% | 58.8 | 31.9 | 2,696 | 2,444 | 14,618 | 14,830 |
| `fresh_map` | 1,729.44 M | 1,019.70 M | -41.0% | 400.2 M | 273.1 M | -31.8% | 88.4 | 55.8 | 2,172 | 2,188 | 14,698 | 14,910 |
| `acc_loop` | 1,448.29 M | 774.27 M | -46.5% | 307.3 M | 201.8 M | -34.3% | 79.7 | 48.3 | 95,344 | 64,368 | 13,434 | 13,646 |
| `points` | 881.66 M | 530.39 M | -39.8% | 197.9 M | 137.5 M | -30.5% | 43.4 | 28.5 | 2,188 | 2,172 | 14,674 | 14,886 |
| `temps` | 1,462.29 M | 792.27 M | -45.8% | 306.2 M | 199.1 M | -35.0% | 80.3 | 49.3 | 95,368 | 64,384 | 13,682 | 13,894 |
| `scratch_tree` | 945.50 M | 584.47 M | -38.2% | 254.3 M | 168.6 M | -33.7% | 50.8 | 35.2 | 2,956 | 2,444 | 14,282 | 14,494 |

The lists remove 34% to 55% of instructions and, except in `bintrees`, 25% to 47% of cycles and 29% to 52% of wall time. That is more than glibc's raised per-size cache gave in `runtime-2026-11-23-debts.md` §2 (−25% to −45% of instructions there, against `d646b53` with counters; a reviewer re-ran that experiment against `bin/t3-n`: −26.5% to −46.4% on eight programs, about −1% on `bintrees`, `acc_loop` and `temps`), and the lists also help the three programs where the cache did nothing: a push or a pop is a few instructions, where glibc's path for a free that misses its cache (`_int_free`, about 225 instructions per cell in the debts report) runs on every free past the cache's capacity.

`bintrees` executes 42% fewer instructions and takes as many cycles and as much wall time as before (+3.7% cycles in default mode, +8.5% with `--no-specialise`, equal best wall time; a reviewer's six interleaved runs gave 234–253 M for the base and 227–249 M for the lists). Measured, one `perf stat` run each: page faults fall from 12,363 to 8,267 and L1 data misses from 1.60 M to 1.06 M; a reviewer measured last-level misses rising from 208 k to 355 k, and counted with an instrumented runtime that 1,048,576 of its 2,097,151 cells are taken from a list. Why the cycles do not fall is not explained; the order in which a LIFO list hands blocks back is a candidate for the extra last-level misses. Peak RSS falls where many cells are live at once, because a 32-byte cell takes 32 bytes instead of glibc's 48: `bintrees` 51 MB to 35 MB, `acc_loop` and `temps` 95 MB to 64 MB, and `scratch_tree` by about 500 KiB in both modes; elsewhere it moves within about 350 KiB either way. Text grows by 212 to 300 bytes (the list code, the 16-bit tag loads, the checks).

**Sanitizers.** The whole suite (548 tests) was run with `clang -fsanitize=address,undefined -fno-sanitize-recover=all` on the runtime and on the program (a wrapper `clang` first on `PATH`, `/var/tmp/fixed-u-runtime/asan-bin/clang`, marks every function of the emitted IR `sanitize_address`; UndefinedBehaviorSanitizer instruments only the C runtime, since its checks are inserted by the C front end; `ASAN_OPTIONS=detect_leaks=0`, because the tests that expect exit 103 leave cells live at `exit`, and the counters check leaks). With the free lists: all pass. With `-DFX_MALLOC` (every cell from `malloc`): all pass except four tests that are meant to fail there: the free-list test (no lists), and the three freed-cell tests, where AddressSanitizer stops the program first with `heap-use-after-free` in `check_live` instead of the runtime's exit 103, which is what that build is for.

## 3. The runtime's fast paths inside the program

**Options.** (a) Compile `fixed_rt.c` to LLVM bitcode and link it into the program's module before optimisation, so that `clang -O2` sees both; (b) emit the fast paths as IR from `EmitLLVM` and keep the slow paths in C; (c) a `static inline` C prelude compiled with the program, which does not apply: the program is LLVM IR, not C. Option (a) can be had two ways. Plain `-flto` is one flag, but it needs a linker that does link-time optimisation: on this machine GNU `ld` has LLVM's plugin (`/usr/lib/llvm/22/lib64/LLVMgold.so`) and `lld` is installed, but both are packaged apart from `clang` on common systems. `clang` alone can link bitcode into the module it compiles, with the front-end option `-mlink-bitcode-file` (`-Xclang`; its sibling `-mlink-builtin-bitcode` is how CUDA and OpenCL link their device libraries): two `clang` invocations, no linker plugin, no other tool. Option (b) would duplicate in Scala every fast path, its checked-build checks and its counters, and keep them in step with the C. **Chosen: (a), through `-mlink-bitcode-file`, because it needs only `clang`.** What `-flto` gives beyond it is measured below.

**Design.** `Backend.ship` runs `clang -O2 -std=c11 -c -emit-llvm [-DFX_CHECK_* -DFX_STATS] fixed_rt.c -o <out>.rt.bc`, then `clang -O2 -Wno-override-module <out>.ll -Xclang -mlink-bitcode-file -Xclang <out>.rt.bc -lm -o <out>`. Nothing else changes: the runtime's functions keep their names and external linkage, so hand-written IR in the tests links as before, and the build macros (checks, counters) are applied to the bitcode. In a plain `fixed ship` the inliner takes `fx_dup`, `fx_drop`'s decrement, both reuses with their child walk, `fx_alloc`'s list pop, `fx_alloc_at` and the frame bumps into the program: in `bin/t2-n` and `bin/t2-n-ns` no call to any of them is left in any program. With checks or counters the fast paths are larger and the inliner keeps many calls (a checked build of `list_pipeline` keeps 61 calls of `fx_drop`, 9 of `fx_reuse` and 6 of `fx_reuse_excl`; with `--stats`, `fx_reuse` stays a call in 10 of the 11 programs). The free-list link is read and written with `memcpy`, so that the C's type-based alias information cannot order it against the field of another type that the slot held or will hold, now that both are in one module (no change in instructions). The slow paths stay out of line: `free_cell` (`noinline`, as before, for the 6–8% the phase 4.1 report measured), `block_new` (`noinline`: a chunk refill or a `malloc`), `frame_next` (now `noinline, cold`) and `fx_die` (now `noreturn, cold`). Rule PR7.3's sentence that the fast path of `fx_drop` "is inlined at every drop site" is now true of a plain `fixed ship`, not of test builds.

**Tests.** Every test builds through the new `ship`. Since the checked build now differs from the plain one in what is inlined, `EmitSuite` also builds every program it runs (examples, benchmarks, the programs of `compiler/src/test/fixed/modes/`) the way a plain `fixed ship` does, without checks or counters, in both modes, and requires the same output. The allocation, reuse and frame pins did not move, and every benchmark built with counters prints exactly the counts of §2. Sanitizers, as in §2, on this build: with the free lists the whole suite passes; with `-DFX_MALLOC` all pass but the five runtime tests meant to fail there (the free-list test, and four freed-cell tests where AddressSanitizer reports `heap-use-after-free` first).

**Measurement.** Both builds without counters; base `bin/t1-n` (§2), against the IR of the same programs linked with the runtime inside (`bin/t2-n`). The two IRs differ only in how static cells spell their tag (`i32 tag` in `t1-n`, `i16 tag, i16 0` after review of §2, the same bytes); a reviewer linked `t2-n`'s IR with the runtime compiled apart and got `t1-n`'s instructions to within 67, so the difference below is the link.

Default mode:

| Program | Instructions separate | inlined | Change | Cycles (min of 5) separate | inlined | Change | Best wall (ms) separate | inlined | Peak RSS (KiB) separate | inlined | Text (bytes) separate | inlined |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 462.13 M | 330.01 M | -28.6% | 229.8 M | 150.0 M | -34.7% | 53.0 | 38.1 | 34,700 | 34,676 | 13,358 | 14,736 |
| `nat_loop` | 722.44 M | 442.42 M | -38.8% | 176.1 M | 96.4 M | -45.2% | 36.1 | 19.7 | 2,188 | 2,192 | 20,070 | 22,412 |
| `list_pipeline` | 1,270.47 M | 770.45 M | -39.4% | 317.2 M | 197.0 M | -37.9% | 65.1 | 40.3 | 2,192 | 2,184 | 19,134 | 22,204 |
| `records` | 780.62 M | 533.64 M | -31.6% | 169.4 M | 94.4 M | -44.3% | 34.4 | 19.3 | 2,732 | 2,712 | 22,374 | 25,697 |
| `fail_chain` | 640.35 M | 416.35 M | -35.0% | 157.2 M | 93.0 M | -40.8% | 32.7 | 19.2 | 2,188 | 2,196 | 17,366 | 19,556 |
| `shared_map` | 507.61 M | 326.64 M | -35.7% | 124.7 M | 77.9 M | -37.5% | 24.9 | 16.2 | 2,448 | 2,452 | 19,294 | 22,340 |
| `fresh_map` | 937.44 M | 588.44 M | -37.2% | 229.5 M | 145.8 M | -36.5% | 47.1 | 30.1 | 2,184 | 2,196 | 19,374 | 22,356 |
| `acc_loop` | 724.27 M | 512.27 M | -29.3% | 168.8 M | 112.9 M | -33.1% | 44.0 | 31.6 | 64,396 | 64,352 | 16,534 | 18,380 |
| `points` | 490.38 M | 314.35 M | -35.9% | 117.7 M | 73.3 M | -37.7% | 24.2 | 15.5 | 2,196 | 2,200 | 21,182 | 24,768 |
| `temps` | 744.27 M | 508.27 M | -31.7% | 181.9 M | 111.2 M | -38.9% | 43.9 | 30.7 | 64,380 | 64,400 | 16,534 | 18,188 |
| `scratch_tree` | 543.45 M | 380.87 M | -29.9% | 161.7 M | 76.0 M | -53.0% | 35.1 | 15.7 | 2,444 | 2,448 | 17,566 | 19,676 |

`--no-specialise`:

| Program | Instructions separate | inlined | Change | Cycles (min of 5) separate | inlined | Change | Best wall (ms) separate | inlined | Peak RSS (KiB) separate | inlined | Text (bytes) separate | inlined |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 495.68 M | 355.17 M | -28.3% | 247.5 M | 169.9 M | -31.3% | 59.5 | 40.8 | 34,680 | 34,700 | 12,918 | 14,072 |
| `nat_loop` | 740.44 M | 460.43 M | -37.8% | 190.6 M | 112.5 M | -41.0% | 41.6 | 24.0 | 2,176 | 2,184 | 15,070 | 16,432 |
| `list_pipeline` | 1,384.48 M | 892.46 M | -35.5% | 385.7 M | 309.5 M | -19.8% | 80.3 | 65.9 | 2,188 | 2,184 | 14,662 | 16,012 |
| `records` | 789.63 M | 576.64 M | -27.0% | 199.2 M | 122.4 M | -38.6% | 37.2 | 24.5 | 2,680 | 2,700 | 15,678 | 16,633 |
| `fail_chain` | 716.33 M | 466.35 M | -34.9% | 203.8 M | 135.9 M | -33.3% | 43.6 | 29.2 | 2,184 | 2,184 | 13,982 | 15,552 |
| `shared_map` | 549.87 M | 366.91 M | -33.3% | 155.2 M | 113.6 M | -26.8% | 32.9 | 24.2 | 2,444 | 2,440 | 14,830 | 16,124 |
| `fresh_map` | 1,019.70 M | 674.70 M | -33.8% | 279.9 M | 225.4 M | -19.5% | 60.9 | 50.6 | 2,188 | 2,188 | 14,910 | 16,160 |
| `acc_loop` | 774.27 M | 562.27 M | -27.4% | 202.1 M | 154.4 M | -23.6% | 56.2 | 39.5 | 64,396 | 64,396 | 13,646 | 14,756 |
| `points` | 530.39 M | 357.37 M | -32.6% | 138.9 M | 113.5 M | -18.3% | 29.7 | 23.6 | 2,184 | 2,188 | 14,886 | 16,304 |
| `temps` | 792.27 M | 558.27 M | -29.5% | 203.3 M | 151.4 M | -25.5% | 49.2 | 40.7 | 64,392 | 64,376 | 13,894 | 14,932 |
| `scratch_tree` | 584.47 M | 411.85 M | -29.5% | 190.1 M | 85.5 M | -55.0% | 40.3 | 18.0 | 2,444 | 2,444 | 14,494 | 15,828 |

Inlining removes 27% to 39% of instructions and 18% to 55% of cycles. In absolute instructions that is about what the `-flto` experiment of the debts report removed from a runtime with `malloc` and counters (`list_pipeline` 500 M here, about 496 M there; `bintrees` 132 M against 186 M), so the percentages are larger mostly because the base is smaller. Text grows by 0.95 to 3.6 KB per program (6% to 17%): a copy of the fast paths at every site, and an out-of-line copy of every runtime function, which keeps its external linkage. Peak RSS does not change.

**What `-flto` would add.** The same IR (`bin/t2-n`), linked with `clang -O2 -flto … fixed_rt.c` (GNU `ld` with the plugin), one run each, default mode: 4% to 39% fewer instructions again (`bintrees` 330.0 M to 208.4 M, `scratch_tree` 380.9 M to 233.4 M, `shared_map` 326.6 M to 252.4 M, `list_pipeline` 770.4 M to 738.4 M), and a third of the text (`list_pipeline` 22.2 KB to 6.7 KB). Most of it is not the runtime: link-time optimisation makes every function internal, and with the program's functions internal the inliner and the dead-argument passes do more. Marking the emitted functions other than `fx_u_main` `internal` and keeping this branch's link gives about the same (`bintrees` 201.6 M, `scratch_tree` 225.0 M, `shared_map` 290.6 M, `list_pipeline` 766.4 M instructions; text 13 to 15 KB). That is a change to `EmitLLVM` (and to tests that match `define <type> @…`) outside this work, and the other agent is changing `EmitLLVM`; it is left as the next step, measured but not built.

## 4. All three together

Base: the base commit's binaries, with counters (`l1`, default mode; `bin/t3-s-ns`, the same runtime and IR, for `--no-specialise`), as `l1.txt` was measured; against the end of this branch built without counters, a plain `fixed ship`. One session per mode.

Default mode:

| Program | Instructions base | now | Change | Cycles (min of 5) base | now | Change | Best wall (ms) base | now | Peak RSS (KiB) base | now | Text (bytes) base | now |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 831.69 M | 330.01 M | -60.3% | 242.2 M | 161.8 M | -33.2% | 56.9 | 36.9 | 51,064 | 34,424 | 13,222 | 14,736 |
| `nat_loop` | 1,452.92 M | 442.42 M | -69.5% | 287.4 M | 96.0 M | -66.6% | 60.1 | 20.7 | 2,220 | 2,196 | 19,902 | 22,412 |
| `list_pipeline` | 2,042.73 M | 770.45 M | -62.3% | 434.7 M | 196.5 M | -54.8% | 96.2 | 40.4 | 2,424 | 2,196 | 18,966 | 22,204 |
| `records` | 1,470.12 M | 533.64 M | -63.7% | 295.4 M | 94.0 M | -68.2% | 73.3 | 20.0 | 2,744 | 2,708 | 22,150 | 25,697 |
| `fail_chain` | 1,372.62 M | 416.35 M | -69.7% | 289.5 M | 92.7 M | -68.0% | 66.7 | 19.5 | 2,416 | 2,192 | 17,230 | 19,556 |
| `shared_map` | 1,161.46 M | 326.64 M | -71.9% | 233.2 M | 77.7 M | -66.7% | 54.6 | 16.2 | 2,676 | 2,448 | 19,142 | 22,340 |
| `fresh_map` | 1,693.36 M | 588.44 M | -65.3% | 355.0 M | 145.0 M | -59.2% | 78.6 | 29.6 | 2,192 | 2,188 | 19,222 | 22,356 |
| `acc_loop` | 1,432.29 M | 512.27 M | -64.2% | 277.7 M | 112.9 M | -59.3% | 70.1 | 30.0 | 95,372 | 64,388 | 16,382 | 18,380 |
| `points` | 864.65 M | 314.35 M | -63.6% | 173.2 M | 72.4 M | -58.2% | 38.2 | 14.9 | 2,168 | 2,192 | 21,014 | 24,768 |
| `temps` | 1,452.29 M | 508.27 M | -65.0% | 273.9 M | 108.5 M | -60.4% | 69.3 | 29.0 | 95,360 | 64,424 | 16,382 | 18,188 |
| `scratch_tree` | 938.98 M | 380.87 M | -59.4% | 244.3 M | 75.5 M | -69.1% | 51.4 | 15.3 | 2,984 | 2,436 | 17,414 | 19,676 |

`--no-specialise`:

| Program | Instructions base | now | Change | Cycles (min of 5) base | now | Change | Best wall (ms) base | now | Peak RSS (KiB) base | now | Text (bytes) base | now |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | 867.34 M | 355.17 M | -59.1% | 243.6 M | 177.4 M | -27.2% | 56.1 | 37.8 | 51,080 | 34,688 | 12,782 | 14,072 |
| `nat_loop` | 1,472.92 M | 460.43 M | -68.7% | 299.1 M | 108.7 M | -63.7% | 61.8 | 22.4 | 2,176 | 2,188 | 14,918 | 16,432 |
| `list_pipeline` | 2,172.74 M | 892.46 M | -58.9% | 514.0 M | 307.8 M | -40.1% | 112.6 | 64.8 | 2,436 | 2,184 | 14,510 | 16,012 |
| `records` | 1,485.13 M | 576.64 M | -61.2% | 300.9 M | 113.9 M | -62.1% | 72.1 | 24.0 | 2,696 | 2,696 | 15,542 | 16,633 |
| `fail_chain` | 1,456.60 M | 466.35 M | -68.0% | 328.0 M | 133.9 M | -59.2% | 74.6 | 27.6 | 2,188 | 2,212 | 13,846 | 15,552 |
| `shared_map` | 1,209.76 M | 366.91 M | -69.7% | 257.2 M | 109.3 M | -57.5% | 60.0 | 23.4 | 2,700 | 2,440 | 14,694 | 16,124 |
| `fresh_map` | 1,787.66 M | 674.70 M | -62.3% | 401.5 M | 222.5 M | -44.6% | 88.8 | 46.4 | 2,440 | 2,188 | 14,774 | 16,160 |
| `acc_loop` | 1,490.29 M | 562.27 M | -62.3% | 307.8 M | 150.9 M | -51.0% | 80.4 | 39.1 | 95,336 | 64,344 | 13,510 | 14,756 |
| `points` | 910.66 M | 357.37 M | -60.8% | 199.2 M | 111.6 M | -44.0% | 44.0 | 22.5 | 2,188 | 2,188 | 14,750 | 16,304 |
| `temps` | 1,508.29 M | 558.27 M | -63.0% | 312.0 M | 150.2 M | -51.9% | 81.2 | 39.9 | 95,352 | 64,396 | 13,758 | 14,932 |
| `scratch_tree` | 982.60 M | 411.85 M | -58.1% | 249.1 M | 81.4 M | -67.3% | 54.0 | 17.5 | 2,980 | 2,448 | 14,358 | 15,828 |

**Instructions with and without counters, at the end of this branch.** With the fast paths inlined, the counters cost far more than in §1 (2.4% to 4.0% on this table's basis, the build without counters as denominator): the number of increments is the same, but with `-DFX_STATS` the fast paths are larger and the inliner leaves `fx_reuse` a call in 10 of the 11 programs (and some `fx_reuse_excl`), where the build without counters has none; the rest of the difference was not traced. Comparisons must state which build they use; for counts, build with `--stats`; for time, without.

| Program | Default, with counters | without | Counters cost | `--no-specialise`, with counters | without | Counters cost |
|---|---:|---:|---:|---:|---:|---:|
| `bintrees` | 359,368,064 | 330,007,947 | +8.9% | 384,533,883 | 355,173,756 | +8.3% |
| `nat_loop` | 510,430,430 | 442,424,994 | +15.4% | 556,439,455 | 460,431,826 | +20.9% |
| `list_pipeline` | 892,458,646 | 770,448,799 | +15.8% | 1,118,476,111 | 892,464,466 | +25.3% |
| `records` | 603,652,279 | 533,638,046 | +13.1% | 681,660,200 | 576,644,960 | +18.2% |
| `fail_chain` | 450,347,201 | 416,346,963 | +8.2% | 536,353,846 | 466,352,810 | +15.0% |
| `shared_map` | 388,795,340 | 326,639,256 | +19.0% | 467,237,778 | 366,908,321 | +27.3% |
| `fresh_map` | 666,617,966 | 588,439,497 | +13.3% | 823,062,442 | 674,702,002 | +22.0% |
| `acc_loop` | 550,270,885 | 512,270,850 | +7.4% | 636,270,923 | 562,270,887 | +13.2% |
| `points` | 353,362,415 | 314,353,978 | +12.4% | 431,377,653 | 357,367,220 | +20.7% |
| `temps` | 546,270,827 | 508,270,790 | +7.5% | 632,270,866 | 558,270,847 | +13.3% |
| `scratch_tree` | 416,689,319 | 380,869,295 | +9.4% | 448,029,356 | 411,849,330 | +8.8% |

**What the coordinator's scripts must change.** `/var/tmp/fixed-bench/compare.sh` reads `allocs`, `frees`, `dups`, `drops` and `peak_rss_kb` and `wall_ns` from `FIXED_RT_STATS=1`. Built as the README says (`compiler/run ship bench/runtime/$p.fixed -o D/$p`), a binary now has no counters: `wall_ns` and `peak_rss_kb` still come out, the count columns are empty. To keep the count columns, add `--stats` to the build line (`compiler/run ship --stats …`), and say in each comparison which build it is; a wall-time comparison of release builds should use builds without it. The README should also say that a build directory holds `<p>.rt.bc` beside `<p>.ll`. (`compare.sh` reads `reuses`, a field the runtime no longer prints; it prints `reuse_excl`, `reuse_test_pass` and `reuse_test_fail`.) `bench/runtime/run.sh` builds with `--stats` (§1).

## What is not built, and what was not verified

- `fixed release` (`spec/compilation_model.md` §8): a plain `fixed ship` is the build without counters and with the runtime inlined.
- Free lists per lane for parallel lanes (§17): one set for the one thread; noted in the runtime.
- Returning memory to the system before exit; moving blocks between size lists.
- Internal linkage for the program's functions (§3, "What `-flto` would add"): measured, not built.
- The 8-byte header (`spec/memory_management.md` §21): not this work; the free lists need the block size, which now lives in the tag word.
- Not verified: why `bintrees` takes as many cycles with the free lists as without (§2); whether the counters cost more inlined because they block the folding of count operations (above); the sanitizer build instruments the program's IR with AddressSanitizer only (UndefinedBehaviorSanitizer has no checks to insert into IR that the C front end did not produce); whether `-mlink-bitcode-file` behaves the same with other `clang` versions than 22.1.8 (it is a front-end option, `-Xclang`, not a driver option).
