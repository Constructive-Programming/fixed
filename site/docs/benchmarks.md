# Benchmarks

Two suites, both reproducible from the repository:

1. **[Runtime baseline](#runtime-baseline)** — compiled Fixed programs
   (LLVM IR, `clang -O2`): wall time, peak RSS, and the runtime's
   allocation / reuse / `dup` / `drop` counters.
2. **[Front-end suite](#front-end-suite)** — JMH over the compiler's
   own scanner / parser / Namer / typer, one op = one pass over all 11
   `examples/` files.

Absolute numbers vary by hardware — the *ratios* are the durable
signal, and the allocation counts are deterministic: every run of a
program produces the same allocations, reuses, `dup`s and `drop`s.

## Runtime baseline

From [`docs/baselines/runtime-2026-11-20.md`](https://github.com/Constructive-Programming/fixed/blob/main/docs/baselines/runtime-2026-11-20.md)
(commit `ae2e398`, M10 — demand inference, shared versions, hybrid
reuse), measured best-of-7 by the runtime itself from `main` start to
end, on Linux / AMD Ryzen AI MAX+ 395, `clang -O2`:

| Program | Size | Wall (ms) | Peak RSS (KiB) | Allocations | Reuses | Frees |
|---|---|---:|---:|---:|---:|---:|
| `bintrees` | complete tree of depth 20 (2,097,151 nodes) | 58.7 | 50,808 | 2,097,151 | 0 | 2,097,151 |
| `nat_loop` | Nat of depth 10,000, 200 times | 72.3 | 2,664 | 4,000,402 | 0 | 4,000,402 |
| `list_pipeline` | list of 10,000, 200 times | 108.8 | 2,636 | 4,000,602 | 4,000,000 | 4,000,602 |
| `records` | 10,000 orders, 100 times | 70.2 | 3,404 | 3,000,302 | 2,000,000 | 3,000,302 |
| `fail_chain` | list of 10,000, abort at element 9,991, 200 times | 85.6 | 2,348 | 4,000,602 | 0 | 4,000,602 |

Every program frees exactly what it allocates — that equality is
asserted by the test suite, not assumed.

### What reuse buys

The same programs, rebuilt and run interleaved against the previous
(4.0) baseline in one session — best of 15, alternating binaries:

| Program | Wall 4.0 (ms) | Wall M10 (ms) | Change | Allocations |
|---|---:|---:|---:|---|
| `bintrees` | 57.1 | 56.2 | −1% | unchanged |
| `nat_loop` | 63.6 | 66.6 | +5% (noise range) | unchanged |
| `list_pipeline` | 144.9 | 100.0 | **−31%** | 8.0 M → 4.0 M (4.0 M reuses) |
| `records` | 80.4 | 65.6 | **−18%** | 5.0 M → 3.0 M (2.0 M reuses) |
| `fail_chain` | 91.1 | 81.2 | −11% | unchanged |

The wins come from in-place reuse of exclusively-owned cells
([`spec/memory_management.md`](memory_management.md)): static reuse
(`fx_reuse_excl`) on `list_pipeline`'s `map`, dynamic reuse after
`rc == 1` (Koka's technique) on `records`' `Order`s. Where no cell is
exclusively owned, counts and time are unchanged — reuse is not
speculation.

## Front-end suite

[`bench/FrontEndBench.scala`](https://github.com/Constructive-Programming/fixed/blob/main/bench/src/main/scala/fixed/bench/FrontEndBench.scala)
measures the compiler pipeline over the `examples/` corpus (one op =
all 11 files). JMH 1.37, average time, 2 forks, 5×2 s warmup +
5×2 s measurement, `-prof gc`; run on 2026-11-21 at commit `d8e884a`
on the same machine as the runtime baseline. Full log:
[`docs/baselines/frontend-2026-11-21.txt`](https://github.com/Constructive-Programming/fixed/blob/main/docs/baselines/frontend-2026-11-21.txt).

| Benchmark | Time (µs/op) | Allocation (B/op) |
|---|---:|---:|
| `namer` | 101.6 ± 4.0 | 885,236 |
| `parseOnly` | 205.8 ± 65.8 | 1,630,788 |
| `scan` | 483.7 ± 109.0 | 3,420,970 |
| `scanAndParse` | 1,016.2 ± 306.4 | 5,862,444 |
| `typer` | 1,410.1 ± 402.2 | 4,815,678 |

Read these as reproducible *directional* data — the error half-widths
are honest (a shared desktop, not a tuned runner), so within-table
orderings matter more than absolutes. The front end is young; these
numbers are the floor future phases (incremental parsing, the M9
verifier) will be measured against.

## Memory facts, per function

The compiler records, for every function of the benchmark programs,
its demands, allocations, fork-site facts and copies — emitted as
`examples/golden/<name>.memory.json` and checked in, so a change to
the memory story shows up as a test diff, not a vibe. The same record
is what `--explain-memory` prints ([`spec/memory_management.md`
§19](memory_management.md)).

## Reproducing

```
sbt "bench/Jmh/run -prof gc"     # front-end suite (as measured above)
```

The runtime table comes from the benchmark programs built by the
backend slice and run under `FIXED_RT_STATS=1`; the methodology and
the raw counts per program are in the baseline files above. The
test-suite gate (`sbt check test`) re-verifies the golden outputs and
the allocs = frees invariant on every commit.
