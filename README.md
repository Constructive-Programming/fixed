# Fixed

A purely functional programming language that operates at the capability (typeclass) level — taking everything possible from [Koka](https://koka-lang.github.io/koka/doc/index.html) but pushing the entire language to work in the style of tagless final encoding and Quine's predicate functor logic.

## Motivation

### The problem with data

Most functional languages ask programmers to commit early to concrete data representations: `List`, `Vec`, `HashMap`, `Either<L, R>`. This creates a tension — you pick a structure, then discover it doesn't compose, doesn't perform, or locks you into an API. Refactoring means rewriting.

John De Goes articulated this in ["Death of Data"](https://degoes.net/articles/kill-data): functions that traffic in concrete types like `Either` commit prematurely to representations. The alternative is to work with *capabilities* — declare what operations you need, not what container holds your values.

W.V.O. Quine showed a parallel result in logic: the full expressive power of first-order predicate logic survives without bound variables, using only five structural combinators on predicates ([predicate functor logic](https://en.wikipedia.org/wiki/Predicate_functor_logic)). No names for "slots" are needed — only operations that crop, pad, permute, reflect, and compose predicates.

Fixed takes both insights seriously:

> **Just as Quine showed logic doesn't need bound variables,
> Fixed shows programming doesn't need bound data types.**

Capabilities *are* predicate functors over the type universe. Cropping = existential types. Padding = phantom parameters. Reflection = type equality constraints. Composition = capability bounds. The five operations are complete — nothing is lost.

### Keeping performance without explicit data

- **Algebraic effect handlers** — effects are declared, tracked in types, and handled with composable handlers. No monads, no transformers, no colored functions.
- **QTT** — Quantitative Type Theory tracks how often each binding is used: 0 (erased, type-level only), 1 or ω. Erasure is what makes dependent types sound with reference counting. In-place update (**FBIP, Functional But In-Place**) and the Perceus counts follow a separate inferred fact, ownership: a value owned exclusively is updated in place, a shared one only after a run-time `rc == 1` test. Using a binding once does not make its value exclusively owned.
- **Evidence passing** — effects compile to efficient evidence-vector lookups, not full delimited continuations.

You will recognize some of these features from [Koka](https://koka-lang.github.io/koka/doc/index.html). Koka is a research language that got several hard things right and Fixed adopts all of them wholesale. The compilation target is LLVM IR. Memory management is Perceus. Effects are algebraic with evidence passing. FBIP optimizes functional updates.

#### What Fixed changes

Where Koka still has `struct`, `enum`, and named data types, Fixed pushes the language up one level of abstraction:

| Koka | Fixed |
|---|---|
| `struct Point { x: int; y: int }` | `cap HasX { fn x -> Part }` + `cap HasY { fn y -> Part }` |
| `type list<a> { Cons(a, list<a>); Nil }` | `cap Sequencing { fn head -> is Optional ... }` |
| `type either<a,b> { Left(a); Right(b) }` | `cap Result of (E, A) { ... }` |
| Programmer picks the representation | Compiler picks the representation |

The programmer writes **capabilities** — what values can do — and the compiler decides how to lay them out in memory. Request `Sequencing + Folding`? The compiler might choose a linked list. Add `RandomAccess + Sized`? Now it *should* use a contiguous array. The capability set narrows the representation space.

The choice is made late. A package is compiled once, to code that works for every representation; where a representation depends on how the rest of the program uses a type, it is chosen when the application is linked, from usage summaries, and recorded in a lock file. Nothing is recompiled when a choice changes. Declarations are private unless marked `pub`. Several satisfactions of one capability by one type may coexist (`Money satisfies Ord named ByAmount`), and `using` chooses between them (`sort(xs) using ByAmount`).

This is the tagless final encoding applied to an entire language: programs are written against abstract interfaces, and the "interpreter" (the compiler) chooses the concrete semantics.

## Quick tour

### Hello world

```
use std.io.Console

fn greet(name: String) -> () with Console {
    Console.printLine("Hello, ".concat(name).concat("!"))
}

fn main() -> () with Console {
    Console.printLine("What is your name?")
    let name = Console.readLine()
    greet(name)
}
```

`with Console` declares the effect. No hidden I/O — it's in the type.

### Capabilities, not types

Functions declare what they need. The compiler picks the data structure:

```
// Works on ANY collection — linked list, array, tree, anything
fn sum(numbers: is Folding of (N is Numeric)) -> N {
    numbers.fold(0, (acc, x) -> acc + x)
}

// Capability bundles via type aliases
type ArrayLike = Sequencing + RandomAccess + Sized + Empty

// Requires index based access — compiler SHOULD choose a contiguous representation
fn binarySearch(
    sorted: C is ArrayLike of (A is Ordered),
    target: A,
) -> R is Optional of u64 + Empty {
    ...
}
```

The output types themselves are also left for the compiler to select.

### The `is` / `of` syntax

Not having to specify the data structure is ergonomic, feels natural and is the
main reason Fixed exists:

```
// The type of the collection isn't needed here
fn sum(collection: is Folding of Numeric) -> u64 {
  collection.fold(0, (acc, n) -> acc + n)
}
```

the type of `collection` (**C**) above doesn't even show up in the previous
example. However, we still know it contains numbers and can be folded over.


### Data types — planning structure

When you need a specific closed set of variants, `data` lets you express the shape:

```
data Color { Red, Green, Blue, RGB(red: N is Numeric, green: N, blue: N) }

data Expr {
    Lit(value: V),
    Add(left: Expr, right: Expr),
    Mul(left: Expr, right: Expr),
    Neg(inner: Expr),
}
```

`data` is where structure gets planned. The compiler still owns the representation — you declare the shape, not the layout.

### Algebraic effects

```
effect Fail of E {
    fn fail(error: E) -> !
}

fn compute(input: String) -> u64 with Fail of (NotANumber | DivisionByZero) { //explictly specifying the Parts of the Fail are not necessary
    let n = parseU64(input) // Fail of NotANumber
    divide(n * 2, 3) // Fail of DivisionByZero
}

// Handle the effect, converting to Result
handle compute("42") {
    Fail.fail(e) => Result.err(e),
    return(v) => Result.ok(v),
}
```

Effects are declared, tracked, and handled — never implicit. Unhandled effects are compile errors.

### Properties — lightweight theorem proving

`prop` declares invariants directly alongside the code they govern. Properties are checked by the compiler — via static analysis where possible, and via property-based testing otherwise:

```
cap Sorted extends Sequencing {
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)
}

cap NonEmpty extends Sequencing {
    prop nonEmpty: size > 0
}

fn insert(sorted: S is Sorted, value: Part) -> S is Sorted {
    // compiler verifies the result still satisfies `sorted`
    ...
}
```

Properties live where the capability is defined — not in a separate test file. They serve as machine-checked documentation and as lightweight proofs that the compiler can use to optimize and verify.

```
cap Stack extends Sequencing + Sized {
    prop pushIncrements: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop popDecrements: forall (s: Self) ->
        s.size > 0 implies s.pop().size == s.size - 1
}
```

### Dependent types — functions that generate capabilities

Functions can return capabilities, making type refinement first-class:

```
// A function that generates a capability
fn between(min: N is Ord, max: N) -> cap of N {
    prop inRange: min <= Self && Self <= max
}

fn validateAge(age: N is Numeric + between(0, 150)) -> N { age }

type Port = u16 + between(1, 65535)
type Username = String + minLength(3) + maxLength(32)
```

Cap generators are first-class — pass them as arguments, return them from functions, compose them:

```
fn validateWith(
    value: N is Numeric,
    constraint: fn(N, N) -> cap of N,
    lo: N, hi: N,
) -> N is constraint(lo, hi) with Fail of String {
    if value < lo { Fail.fail("too low") }
    else if value > hi { Fail.fail("too high") }
    else { value }
}
```

Type aliases can take value parameters too, bundling refinements into reusable names:

```
type PortRange(min: u16, max: u16) = u16 + between(min, max)
type BoundedString(max: u64) = String + maxLength(max)

fn parsePort(s: String) -> N is PortRange(1024, 49151) with Fail of String { ... }
```

Refinement capabilities are zero-cost: compile-time only, no wrapping, the value stays its original type. `cap Name(params)` is sugar for `fn name(params) -> cap`.

## Design principles

1. **`cap` is the primary abstraction** — capabilities describe what values can do, not what they are
2. **`data` is where structure gets planned** — `data` lets you express the shape of your data when you need a specific closed set of variants
3. **`prop` invariants are specified in place** — properties (from property-based testing) are built into the language as a form of lightweight theorem proving
4. **Functions are total** — only capabilities and data can self-reference; all recursion is structural via fold/unfold
5. **No mutation** — purely functional; the compiler optimizes in-place updates via FBIP
6. **Everything is an expression** — no statements, no semicolons; blocks return their last expression
7. **No explicit layouts** — the programmer never writes references; the compiler handles borrowing/moving/cloning via Perceus
8. **Algebraic effects for all side effects** — declared with `effect`, handled with `handle`/`resume`
9. **Quantities and ownership are inferred, not written** — the compiler infers how often each binding is used (0, 1, ω) and which values are borrowed, owned or exclusively owned. The programmer writes capabilities; the compiler infers resource usage
10. **Agent friendly** — the compiler produces clear, structured output designed for humans, which also makes it ideal for AI harnesses and automated tooling

## Theoretical foundation

Quine's five predicate functor operations map directly onto Fixed's capability combinators:

| Quine | Logic | Fixed |
|---|---|---|
| **Cropping** | Existential quantification (hide a variable) | Existential capabilities — return `is Cap`, hide the concrete type |
| **Padding** | Add a vacuous variable | Phantom type parameters — `data Tagged of (phantom Tag, Value)` |
| **Permutation** | Reorder arguments | Capability bounds are unordered — `A + B` = `B + A` |
| **Reflection** | Identify two variables | Named aliases — `N is Numeric` constrains multiple params to share a type |
| **Composition** | Conjoin predicates | Capability composition — `extends`, `+` bounds |
| **Quantities** | Multiplicity of variable usage | QTT quantities — 0 (erased), 1, ω. Quantity 0 is erased; reference counting and reuse follow inferred ownership |


## Repository structure

```
examples/           11 example programs exercising the language design
docs/plans/         Implementation plan and per-phase plans
docs/decisions/     Design decision records
spec/               Specification (drafts)
compiler/           Compiler implementation in Scala 3, with its C runtime and prelude
bench/              JMH front-end benchmarks and runtime benchmark programs
scalafix/           Custom lint rules
```

## Running the compiler

```
sbt "run -- examples/01_basics.fixed"                          # check; prints diagnostics, exits 1 on errors
sbt "run -- compile examples/01_basics.fixed -o 01.ll"         # LLVM IR
sbt "run -- ship examples/01_basics.fixed -o 01"               # native executable, via clang
sbt "run -- compile examples/01_basics.fixed --explain-memory" # memory and clone report
```

`--no-specialise` builds no clones beyond the mandatory ones. There is one package and no link step yet.

## Quality checks

```
sbt check   # scalafmt + scalafix (lint, unused, import order) + PMD CPD (copy-paste); fails on any finding
sbt fix     # apply scalafix rewrites and scalafmt formatting
```

The same gate (`sbt check test`) runs as a **pre-commit hook** (`.githooks/pre-commit`;
loading the sbt build sets `git config core.hooksPath .githooks` automatically) and in
**CI** (`.github/workflows/ci.yml`, on pushes to `main` and on pull requests).

### Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) with a required scope,
checked by `.githooks/commit-msg` and again in CI:

```
perf(scanner): remove boxing on the hot path

Optional body, wrapped at 72 columns.
```

- **types:** `feat` `fix` `perf` `refactor` `test` `docs` `build` `ci` `chore` `revert`
- **scopes:** `ast` `scanner` `parser` `typer` `backend` `runtime` `driver` `util` `spec` `examples` `docs` `build` `bench`
  (several: `feat(parser,spec): …`; breaking change: `refactor(ast)!: …`)
- subject ≤ 72 chars, no trailing period, blank line before the body.

Config: `.scalafmt.conf`, `.scalafix.conf`; CPD threshold is `cpdMinTokens` in `build.sbt`.

Benchmarks (JMH, `bench/`): `sbt "bench/Jmh/run -prof gc fixed.bench.FrontEndBench"` —
scanner, parser and end-to-end throughput over `examples/`, with allocation per op.

## Status

Phases 0 (design), 2 and 2.1 (parser), 4.0 (naive backend) and most of 4.1 (polymorphic baseline and specialiser) are done. Phase 1 (specification) exists as drafts under `spec/`. Of the type checker (Phase 3), the base typer, capability closure, effect inference and quantity, demand and lifetime inference are done; property verification and representation selection are not. Examples `01`, `03`, `04` and `05` compile to native code and run. The application link, visibility, `using` and the other decisions of `docs/decisions/P6-resolution-round-2026-11-20.md` are specified but not built.

The language design is explored through 11 example programs covering:

- Basic I/O, numeric polymorphism, named/anonymous capability aliases
- Capability-driven collections (Sequencing, Functor, Folding, RandomAccess, etc.)
- Optional/Result, error handling, the `Fail` effect, `satisfies` bridge
- JSON parsing with recursive data types and deep pattern matching
- Phantom-typed state machines and unit-safe arithmetic
- Higher-kinded types, Monad, do-notation
- Recursive data types, BST operations, recursion schemes (catamorphism, anamorphism, hylomorphism, paramorphism)
- Multiple effect composition, handler nesting, channel-based concurrency as effects
- A mini interpreter with fold-based eval on capabilities
- Geometry with type aliases and data declarations
- Property-based invariants (`prop`, `forall`, `implies`) and dependent types (`fn -> cap`, refinement types)

See [`docs/plans/implementation-plan.md`](docs/plans/implementation-plan.md) for the full roadmap.

## References

- [Death of Data](https://degoes.net/articles/kill-data) — De Goes on eliminating premature type commitment
- [Quine's Predicate Functor Logic](https://en.wikipedia.org/wiki/Predicate_functor_logic) — variable-free predicate logic
- [Tagless Final](https://okmij.org/ftp/tagless-final/) — encoding programs against abstract interfaces
- [Koka language](https://koka-lang.github.io/koka/doc/index.html) — algebraic effects, Perceus RC, FBIP, evidence passing
- [Quantitative Type Theory](https://bentnib.org/quantitative-type-theory.html) — Atkey, 2018. Dependent types with resource tracking via usage quantities
- [Perceus: Garbage Free Reference Counting with Reuse](https://www.microsoft.com/en-us/research/publication/perceus-garbage-free-reference-counting-with-reuse/) — Reinking et al., 2021
- [Rethinking Data and Codata through Matrix Transposition](https://hackernoon.com/rethinking-data-and-codata-through-matrix-transposition) — data/codata duality and its implications for language design

## License

TBD
