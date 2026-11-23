# Fixed

A purely functional programming language that operates at the
**capability level** — tagless final encoding applied to an entire
language, taking everything possible from
[Koka](https://koka-lang.github.io/koka/doc/index.html) and pushing the
whole language to work in the style of Quine's
[predicate functor logic](https://en.wikipedia.org/wiki/Predicate_functor_logic).

## Capabilities, not data

Most functional languages make you commit early to concrete data
representations — `List`, `Vec`, `HashMap`, `Either`. Fixed programs
declare *capabilities*: what values can do, not what container holds
them. The compiler picks the representation.

```
// Works on ANY collection — linked list, array, tree, anything
fn sum(numbers: is Folding of (N is Numeric)) -> N {
    numbers.fold(0, (acc, x) -> acc + x)
}
```

Request `Sequencing + Folding`? The compiler might choose a linked
list. Add `RandomAccess + Sized`? Now it *should* use a contiguous
array. The capability set narrows the representation space.

## Effects, quantities, evidence

- **Algebraic effect handlers** — effects are declared, tracked in
  types, and handled with composable handlers. No monads, no
  transformers, no colored functions.
- **QTT** — Quantitative Type Theory tracks how each value is used:
  erased (type-level only), linear (used once, no reference
  counting), or shared (full RC). Dependent types stay sound with RC,
  and linear values get guaranteed in-place mutation (FBIP).
- **Evidence passing** — effects compile to efficient evidence-vector
  lookups, not full delimited continuations.

The compilation target is LLVM IR; memory management is Perceus.

## Hello, world

```
use std.io.Console

fn greet(name: String) -> () with Console {
    Console.print_line("Hello, ".concat(name).concat("!"))
}

fn main() -> () with Console {
    Console.print_line("What is your name?")
    let name = Console.read_line()
    greet(name)
}
```

`with Console` declares the effect. No hidden I/O — it's in the type.

## The specification

The language is specified in these documents, served straight from
`spec/` and kept in lockstep with the compiler:

- [Type system](type_system.md)
- [Syntax grammar (EBNF)](syntax_grammar.ebnf)
- [Effects](effects.md)
- [Pattern matching](pattern_matching.md)
- [Properties](properties.md)
- [Quantities (QTT)](quantities.md)
- [Memory management](memory_management.md)
- [Standard library](stdlib.md)

## Cookbook

[Runnable recipes](cookbook/README.md) for the questions that come up
most often — capability bounds, error handling with effects, recursion
schemes, phantom-typed state machines, concurrency as algebraic
effects, properties — each lifted from the tested `examples/` corpus.

## Status

The compiler is under active development. The roadmap lives in the
[implementation plan][plan] on GitHub.

## Where to go next

- [Cookbook](cookbook/README.md) — worked recipes, one page per
  feature cluster, with the example program and spec section each
  recipe draws on.
- [Benchmarks](benchmarks.md) — the runtime baseline (wall time, peak
  RSS, allocation and reuse counts per program), the front-end JMH
  suite, and how to reproduce both.
- [The specification](type_system.md) — start with the type system;
  every other spec page cross-references it.
- [Source, tests and quality gates](https://github.com/Constructive-Programming/fixed)
  — the README documents the toolchain (scalafmt, scalafix, CPD,
  JMH) and how to run it. The 11 example programs under `examples/`
  are the living corpus the whole suite checks against.
- [implementation plan][plan] — phases, milestones and open questions.

[plan]: https://github.com/Constructive-Programming/fixed/blob/main/docs/plans/implementation-plan.md
