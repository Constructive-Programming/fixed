# Runtime baseline, 2026-11-23: immediates

Immediates (`spec/compilation_model.md` Rule CM3.5) as built on branch `kryptt/s-imm`, commit
`c5214d2`, against the baseline of commit `d646b53` (`/var/tmp/fixed-bench/base3`, `base3-ns`).

- Machine: Linux 7.2.15-gentoo-x86_64, AMD RYZEN AI MAX+ 395; toolchain `clang -O2` (clang 22.1.8).
- Programs: the eleven of `bench/runtime/`, each built in both modes, default (clones by score) and
  `--no-specialise` (baseline versions only): `sbt "compiler/run ship bench/runtime/<p>.fixed -o <dir>/<p> [--no-specialise]"`.
  `points` builds a list of 5,000 `P(x: u32, y: u32)` by `unfold`, maps `shift` over it and folds
  `weight`, 200 times.
- Instructions and cycles: `perf stat -e instructions,cycles` of the whole process; instructions
  from one run (identical binaries differ by up to about ±200 between runs), cycles the minimum of
  5 runs. Base and new binaries were run in the same session.
- Allocations and peak RSS: the runtime's counters (`FIXED_RT_STATS=1 <binary>`).

## Layout measured (provisional, OQ-CM13)

The draft layout of Rule CM3.5, unchanged (details in its "Built today" paragraph). `P` of `points`
is `x` in bits 0–31 and `y` in bits 32–63, no tag: `shift` compiles to two `trunc`/`add` pairs (the
second after an `lshr` by 32), two `zext`s, a `shl` and an `or`, with no memory access.

## Results

### default

| Program | Instructions base | Instructions imm | Change | Min cycles base | Min cycles imm | Change | Allocations base → imm | Peak RSS KiB base → imm |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `bintrees` | 833,788,490 | 833,788,473 | -0.0% | 276,757,986 | 243,845,822 | -11.9% | 2,097,151 → 2,097,151 | 50276 → 50796 |
| `nat_loop` | 1,466,917,067 | 1,466,916,979 | -0.0% | 319,466,544 | 286,659,578 | -10.3% | 4,000,402 → 4,000,402 | 2172 → 2172 |
| `list_pipeline` | 2,056,733,102 | 2,056,733,021 | -0.0% | 479,433,208 | 450,560,267 | -6.0% | 4,000,602 → 4,000,602 | 2156 → 2188 |
| `records` | 1,477,123,513 | 1,477,123,481 | -0.0% | 295,817,550 | 312,926,061 | +5.8% | 3,000,302 → 3,000,302 | 2844 → 2696 |
| `fail_chain` | 1,386,614,040 | 1,386,613,979 | -0.0% | 313,326,441 | 292,511,343 | -6.6% | 4,000,602 → 4,000,602 | 2128 → 2136 |
| `shared_map` | 1,169,533,648 | 1,169,533,611 | -0.0% | 237,919,449 | 246,306,218 | +3.5% | 2,020,404 → 2,020,404 | 2672 → 2644 |
| `fresh_map` | 1,707,433,596 | 1,707,433,569 | -0.0% | 373,195,001 | 358,638,960 | -3.9% | 4,020,603 → 4,020,603 | 2132 → 2164 |
| `acc_loop` | 1,446,292,228 | 1,446,292,155 | -0.0% | 281,829,087 | 274,316,558 | -2.7% | 4,000,003 → 4,000,003 | 95336 → 95104 |
| `points` | 1,435,311,143 | 871,656,519 | -39.3% | 277,543,004 | 173,760,635 | -37.4% | 3,000,602 → 2,000,602 | 2184 → 2160 |
| `temps` | 1,610,292,085 | 1,610,292,122 | +0.0% | 303,894,988 | 322,554,384 | +6.1% | 6,000,002 → 6,000,002 | 95088 → 95096 |
| `scratch_tree` | 941,637,338 | 941,637,316 | -0.0% | 245,756,962 | 276,626,285 | +12.6% | 2,560,002 → 2,560,002 | 2936 → 2928 |

### `--no-specialise`

| Program | Instructions base | Instructions imm | Change | Min cycles base | Min cycles imm | Change | Allocations base → imm | Peak RSS KiB base → imm |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `bintrees` | 869,440,072 | 869,440,104 | +0.0% | 239,531,943 | 250,560,190 | +4.6% | 2,097,151 → 2,097,151 | 51048 → 50788 |
| `nat_loop` | 1,486,924,021 | 1,486,923,983 | -0.0% | 293,479,149 | 299,325,045 | +2.0% | 4,000,402 → 4,000,402 | 2140 → 2128 |
| `list_pipeline` | 2,186,744,472 | 2,186,744,553 | +0.0% | 512,301,963 | 517,880,456 | +1.1% | 4,000,602 → 4,000,602 | 2344 → 2184 |
| `records` | 1,492,127,499 | 1,492,127,498 | -0.0% | 299,149,897 | 326,657,341 | +9.2% | 3,000,302 → 3,000,302 | 2668 → 2736 |
| `fail_chain` | 1,470,595,653 | 1,470,595,604 | -0.0% | 328,218,046 | 331,358,348 | +1.0% | 4,000,602 → 4,000,602 | 2148 → 2188 |
| `shared_map` | 1,217,831,847 | 1,217,832,003 | +0.0% | 252,252,306 | 266,703,843 | +5.7% | 2,020,404 → 2,020,404 | 2672 → 2416 |
| `fresh_map` | 1,801,733,970 | 1,801,734,031 | +0.0% | 398,907,333 | 403,834,536 | +1.2% | 4,020,603 → 4,020,603 | 2188 → 2152 |
| `acc_loop` | 1,504,292,166 | 1,504,292,233 | +0.0% | 305,670,190 | 319,335,062 | +4.5% | 4,000,003 → 4,000,003 | 95316 → 95076 |
| `points` | 1,446,320,723 | 917,666,153 | -36.6% | 281,689,911 | 204,080,168 | -27.6% | 3,000,602 → 2,000,602 | 2156 → 2188 |
| `temps` | 1,666,292,136 | 1,666,292,128 | -0.0% | 335,887,399 | 342,222,560 | +1.9% | 6,000,002 → 6,000,002 | 94828 → 94340 |
| `scratch_tree` | 985,257,323 | 985,257,359 | +0.0% | 251,736,920 | 254,720,622 | +1.2% | 2,560,002 → 2,560,002 | 2912 → 2892 |

## Reading

- `points` is the only benchmark with an immediate type in its hot loop (`P`, 8 bytes): 39% fewer
  instructions by default, 37% fewer with `--no-specialise`; 1,000,000 fewer allocations (one `P`
  cell per point per round, 5,000 × 200), the rest being list cells, closures and the printed string
  (derived and pinned in `EmitSuite`, test "bench/runtime/points prints its expected result"). Its
  reuses after an `rc == 1` test fall by the same 1,000,000: 2,000,000 → 1,000,000 by default,
  4,000,200 → 3,000,200 with `--no-specialise` (`shift` no longer reuses a `P` cell).
- Every other benchmark's LLVM IR is byte-identical to the base build in both modes (checked with
  `diff` on the `.ll` files), so their instruction differences (at most 156) are run-to-run noise.
  `acc_loop` and `temps` (records of two and three `u64`, 16 and 24 bytes) stay cells.
- Cycles vary by up to about ±13% between identical binaries in this session (other work ran on the
  machine); only the `points` change is beyond that range. Peak RSS differences are noise.
