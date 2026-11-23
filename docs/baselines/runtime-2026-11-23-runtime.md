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
