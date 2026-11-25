{%
laika.excludeFromNavigation = false
%}

# Getting started

Fixed is a research compiler under active development. There is no release
tarball yet: build the compiler from source with sbt, then use it to check,
compile and ship Fixed programs.

## Prerequisites

- A JDK 17 or later (CI uses Temurin 25)
- [sbt](https://www.scala-sbt.org/) 1.12 or later
- `clang` on `PATH` for `ship` — the compiler emits LLVM IR, and clang links
  it with the runtime
- `git`

## Get the source

```sh
git clone https://github.com/Constructive-Programming/fixed.git
cd fixed
```

## Check a program

The root `run` task delegates to the compiler. With no mode it checks the
file, prints its diagnostics, and exits with the smallest error class among
them:

```sh
sbt "run -- examples/01_basics.fixed"
```

Exit code `0` means no errors. A parse error is `4`, a type error `6`; the
full table is in [Reports](reports.md).

## Your first program

Create `hello.fixed`:

```fixed
use std.io.Console

fn main() -> () with Console =
    Console.printLine("Hello, Fixed!")
```

Check it:

```sh
sbt "run -- hello.fixed"
```

`with Console` is not decoration: `Console.printLine` performs the `Console`
effect, and `main` must declare it. Remove the clause and the compiler rejects
the program with `T015`.

## Compile to LLVM IR

```sh
sbt "run -- compile hello.fixed -o hello.ll"
```

The output is textual LLVM IR. The compiler does not invoke a linker in this
mode.

## Build and run a native executable

```sh
sbt "run -- ship hello.fixed -o hello"
./hello
```

`ship` emits IR, invokes `clang`, and links the runtime from
`compiler/src/main/resources/runtime`. Build with `--stats` to compile the
runtime counters in, and run the executable with `FIXED_RT_STATS=1` to print
them.

## Look at the memory story

```sh
sbt "run -- compile examples/01_basics.fixed --explain-memory"
```

The report gives every function's demands, allocations, fork-site facts and
copies; `--format json` gives the same facts as the checked-in
`examples/golden/*.memory.json`. The model is in
[Memory management](memory_management.md).

## What runs today

The compiler parses every example in `examples/`. Examples `01`, `03`, `04`
and `05` compile to native code and run, in the default and `--no-specialise`
modes, against pinned output under `examples/golden/`. Examples `02` and `06`
type-check with no diagnostics, but the backend does not compile them yet.
Examples `07` to `11` still have type errors.

The [Assumptions and limits](references/assumptions.md) page lists what is
specified but not built; the repository's status section carries the
phase-by-phase account.

## Where to go next

- [Language Specifications](type_system.md) — start with the type system;
  every other spec page cross-references it.
- [Cookbook](cookbook/README.md) — runnable recipes for capability bounds,
  error handling, recursion schemes, phantom types, effects and properties.
- [Compiler Details](compilation_model.md) — the compilation model, cost
  contracts, diagnostics and reports.
- [Editor support](editors.md) — VS Code, Zed, Neovim/Helix, Pygments,
  highlight.js and Shiki.
- [References](references/README.md) — assumptions, the bibliography and the
  projects Fixed learns from.
