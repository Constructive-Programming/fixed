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

### What we take from Koka

[Koka](https://koka-lang.github.io/koka/doc/index.html) is a research language that got several hard things right:

- **Algebraic effect handlers** — effects are declared, tracked in types, and handled with composable handlers. No monads, no transformers, no colored functions.
- **Perceus reference counting** — compiles to C with precise, garbage-free RC. No GC pauses, deterministic deallocation.
- **FBIP (Functional But In-Place)** — reuse analysis lets the compiler mutate in place when a value is uniquely owned, giving functional code imperative performance.
- **Evidence passing** — effects compile to efficient evidence-vector lookups, not full delimited continuations.
- **C emission** — clean, readable generated C. No runtime beyond the RC primitives.

Fixed adopts all of these wholesale. The compilation target is C. Memory management is Perceus. Effects are algebraic with evidence passing. FBIP optimizes functional updates.

### What Fixed changes

Where Koka still has `struct`, `enum`, and named data types, Fixed pushes the language up one level of abstraction:

| Koka | Fixed |
|---|---|
| `struct Point { x: int; y: int }` | `trait HasX { fn x -> Part }` + `trait HasY { fn y -> Part }` |
| `type list<a> { Cons(a, list<a>); Nil }` | `trait Sequencing { fn head -> is Optional; ... }` |
| `type either<a,b> { Left(a); Right(b) }` | `trait Result of (E, A) { ... }` |
| Programmer picks the representation | Compiler picks the representation |
| Algebraic data types | Capabilities (typeclasses) + `data` escape hatch |

The programmer writes **capabilities** — what values can do — and the compiler decides how to lay them out in memory. Request `Sequencing + Folding`? The compiler might choose a linked list. Add `RandomAccess + Sized`? Now it *must* use a contiguous array. The capability set narrows the representation space.

This is the tagless final encoding applied to an entire language: programs are written against abstract interfaces, and the "interpreter" (the compiler) chooses the concrete semantics.

## Quick tour

### Hello world

```
use std::io::Console;

fn greet(name: String) -> () with Console {
    Console::print_line("Hello, ".concat(name).concat("!"));
}

fn main() -> () with Console {
    Console::print_line("What is your name?");
    let name = Console::read_line();
    greet(name);
}
```

`with Console` declares the effect. No hidden I/O — it's in the type.

### Capabilities, not types

Functions declare what they need. The compiler picks the data structure:

```
// Works on ANY collection — linked list, array, tree, anything
fn sum(numbers: is Folding of (N is Numeric)) -> N {
    numbers.fold(0, |acc, x| acc + x)
}

// Requires O(1) indexing — compiler MUST choose a contiguous representation
fn binary_search(
    sorted: C is ArrayLike of (A is Ordered),
    target: A,
) -> R is Optional of u64 + Empty {
    ...
}
```

### The `is` / `of` syntax

```
// Named alias — N is reused to relate parameters
fn fib(n: N is Numeric) -> N { ... }

// Anonymous — compiler infers independently
fn count(collection: is Folding) -> u64 { ... }

// Capability bundles via type aliases
type ArrayLike = Sequencing + RandomAccess + Sized + Empty
```

### Data types (when you need them)

Capabilities are the default, but sometimes a closed set of variants is the right tool:

```
data Color { Red, Green, Blue, RGB(red: N is Numeric, green: N, blue: N) }

data Expr {
    Lit(value: f64),
    Add(left: Expr, right: Expr),
    Mul(left: Expr, right: Expr),
    Neg(inner: Expr),
}
```

`data` is the escape hatch. The compiler still owns the representation — you declare the shape, not the layout.

### Algebraic effects

```
effect trait Fail<E> {
    fn fail(error: E) -> !;
}

fn compute(input: String) -> u64 with Fail<String> {
    let n = parse_u64(input)?;    // ? desugars to fold + Fail
    divide(n * 2, 3)?
}

// Handle the effect, converting to Result
handle compute("42") {
    Fail::fail(e) => Result::err(e),
    return(v) => Result::ok(v),
}
```

Effects are declared, tracked, and handled — never implicit. Unhandled effects are compile errors.

## Design principles

1. **`trait` is the primary abstraction** — capabilities describe what values can do, not what they are
2. **`data` is the escape hatch** — for closed variant sets when capabilities aren't enough
3. **The compiler owns all representation decisions** — the programmer never specifies heap/stack, boxing, contiguous/linked
4. **Algebraic effects for all side effects** — declared with `effect trait`, handled with `handle`/`resume`
5. **Perceus memory management** — compiles to C with precise RC, reuse analysis, drop specialization
6. **No mutation** — purely functional; the compiler optimizes in-place updates via FBIP
7. **Right-bias convention** — multi-parameter types derive Functor over the rightmost parameter; when ambiguous, the compiler suggests options for the developer to choose from
8. **No `&` operator** — the programmer never writes references; the compiler handles borrowing/moving/cloning

## Theoretical foundation

Quine's five predicate functor operations map directly onto Fixed's capability combinators:

| Quine | Logic | Fixed |
|---|---|---|
| **Cropping** | Existential quantification (hide a variable) | Existential capabilities — return `is Trait`, hide the concrete type |
| **Padding** | Add a vacuous variable | Phantom type parameters — `data Tagged of (phantom Tag, Value)` |
| **Permutation** | Reorder arguments | Capability bounds are unordered — `A + B` = `B + A` |
| **Reflection** | Identify two variables | Named aliases — `N is Numeric` constrains multiple params to share a type |
| **Composition** | Conjoin predicates | Capability composition — `extends`, `+` bounds |

These five operations are **complete**: any data type expressible with concrete `struct`/`enum` can be equivalently expressed as a capability using only these operations.

## Project structure

```
examples/           13 example programs exercising the language design
docs/plans/         Implementation plan (phases 0–6)
spec/               (planned) Formal specification
stdlib/             (planned) Standard library in Fixed
src/                (planned) Compiler implementation in Rust
```

## Status

**Phase 0 (design) is complete.** The language design is explored through 13 example programs covering:

- Basic I/O, recursion, numeric polymorphism
- Capability-driven collections (Sequencing, Functor, Folding, RandomAccess, etc.)
- Optional/Result, error handling, the `?` operator
- JSON parsing with recursive capabilities
- Phantom-typed state machines
- Multiple effect composition and handler nesting
- Higher-kinded types, Monad, do-notation
- Binary search trees with fold-based traversals
- Unit-safe physics with phantom types
- A mini interpreter with eval effects
- Channel-based concurrency as effects
- Geometry with type aliases and data declarations

Next: formal specification (Phase 1) and parser implementation (Phase 2).

See [`docs/plans/implementation-plan.md`](docs/plans/implementation-plan.md) for the full roadmap.

## References

- [Koka language](https://koka-lang.github.io/koka/doc/index.html) — algebraic effects, Perceus RC, FBIP, evidence passing
- [Perceus: Garbage Free Reference Counting with Reuse](https://www.microsoft.com/en-us/research/publication/perceus-garbage-free-reference-counting-with-reuse/) — Reinking et al., 2021
- [Quine's Predicate Functor Logic](https://en.wikipedia.org/wiki/Predicate_functor_logic) — variable-free predicate logic
- [Death of Data](https://degoes.net/articles/kill-data) — De Goes on eliminating premature type commitment
- [Tagless Final](https://okmij.org/ftp/tagless-final/) — encoding programs against abstract interfaces

## License

TBD
