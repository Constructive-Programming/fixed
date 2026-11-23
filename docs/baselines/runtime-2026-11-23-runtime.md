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

The counters cost 2.3% to 3.8% of instructions, about one instruction per counted event (0.94 to 1.12 by the counts), and 76 bytes of text. In cycles, default mode shows no consistent effect (−2.1% to +3.4%, of both signs, mean −0.1%); `--no-specialise`, which counts more events (its witness calls), is faster without counters in all eleven programs, by 0.5% to 3.4% (mean about 1.5%), and in best wall time in nine. Peak RSS moves by up to about 270 KiB either way; that is the variation between runs of one program: `l1` and the build with counters, the same code, differ by 252 KiB on `fail_chain`. **From here on, every comparison states which build it uses. §2 to §4 compare builds without counters; their base is `bin/t3-n` (default) and `bin/t3-n-ns` (`--no-specialise`), the base commit's IR with the runtime of this section built without `--stats`.**

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
