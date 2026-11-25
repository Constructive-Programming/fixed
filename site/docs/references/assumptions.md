# Assumptions and limits

The specification is the normative description of Fixed. The compiler
implements a subset of it, and the site marks the gap rather than hiding it.
This page collects the assumptions a reader should hold while reading the
specification and the guides.

## The specification leads the compiler

`spec/` defines the language. The compiler is the reference implementation of
the parts it has reached. A rule may be marked **specified, not built** in a
guide or in the specification itself; the mark means the rule is decided and
the compiler does not implement it yet, not that the rule is provisional.

The current split, as of the status section of the repository:

- **Built**: the scanner, parser, base typer, capability closure, effect
  inference, quantity/demand/lifetime inference, the totality check, derived
  `para`, `fold`/`unfold`/`map` in bounded native stack for lists and `Nat`,
  derived `Show`/`Eq`/`Ord`, literal patterns, `satisfies` bodies, `Clone` on
  request, and a backend for examples `01`, `03`, `04` and `05`.
- **Built in part**: the static prover on a small fragment of props; there is
  no property-based testing.
- **Not built**: the application link and its lock file (`fixed link`), the
  release build (`fixed release`), the test build (`fixed verify`), composed
  commands `a + b`, representation selection, resumable effect operations,
  the arithmetic effects `Overflow` and `DivideByZero`, the `Decode` effect,
  and the diagnostic sinks `inspect` and `trace`.

The [implementation plan](https://github.com/Constructive-Programming/fixed/blob/main/docs/plans/implementation-plan.md)
and the [status section](https://github.com/Constructive-Programming/fixed#status)
of the repository carry the full phase-by-phase account.

## One package, no link step

The compiler currently compiles one package. There is no link stage, so the
parts of the specification that depend on the whole program — choice-point
selection, the lock file, clones built at link, the application-level
resolution of satisfactions — are specified but not exercised. Code that
relies on them does not compile yet, or compiles under the stand-in rules the
specification names.

## The runtime

The backend emits LLVM IR. Memory management is Perceus-style reference
counting with reuse; there is no tracing collector. `ship` invokes `clang` to
produce a native executable and links the runtime from
`compiler/src/main/resources/runtime`. The runtime's allocation, reuse, `dup`
and `drop` counters are deterministic: every run of a program produces the
same counts.

The runtime numbers on [Benchmarks](../benchmarks.md) are reproducible
*directional* data from one machine, not a cross-platform guarantee. The
allocation counts are the durable part; wall time and peak RSS move with the
host.

## Reading the examples

`examples/` is the living corpus the test suite parses, types and — for the
backend slice — compiles and runs. An example may type-check while the backend
does not compile it. The pinned outputs under `examples/golden/` say which
examples are expected to run and what they print; a change to the memory story
also shows up there as a diff.
