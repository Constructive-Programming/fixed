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
- **Perceus reference counting** — compiles to C with precise, garbage-free RC. No GC pauses, deterministic deallocation.
- **FBIP (Functional But In-Place)** — reuse analysis lets the compiler mutate in place when a value is uniquely owned, giving functional code imperative performance.
- **Evidence passing** — effects compile to efficient evidence-vector lookups, not full delimited continuations.
- **C emission** — clean, readable generated C. No runtime beyond the RC primitives.

You will recognize some of these features from [Koka](https://koka-lang.github.io/koka/doc/index.html). Koka is a research language that got several hard things right and Fixed adopts all of them wholesale. The compilation target is C. Memory management is Perceus. Effects are algebraic with evidence passing. FBIP optimizes functional updates.

#### What Fixed changes

Where Koka still has `struct`, `enum`, and named data types, Fixed pushes the language up one level of abstraction:

| Koka | Fixed |
|---|---|
| `struct Point { x: int; y: int }` | `cap HasX { fn x -> Part }` + `cap HasY { fn y -> Part }` |
| `type list<a> { Cons(a, list<a>); Nil }` | `cap Sequencing { fn head -> is Optional ... }` |
| `type either<a,b> { Left(a); Right(b) }` | `cap Result of (E, A) { ... }` |
| Programmer picks the representation | Compiler picks the representation |

The programmer writes **capabilities** — what values can do — and the compiler decides how to lay them out in memory. Request `Sequencing + Folding`? The compiler might choose a linked list. Add `RandomAccess + Sized`? Now it *should* use a contiguous array. The capability set narrows the representation space.

This is the tagless final encoding applied to an entire language: programs are written against abstract interfaces, and the "interpreter" (the compiler) chooses the concrete semantics.

## Quick tour

### Hello world

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
fn binary_search(
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
    Lit(value: Part),
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
    let n = parse_u64(input) // Fail of NotANumber
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
    prop non_empty: size > 0
}

fn insert(sorted: S is Sorted, value: Part) -> S is Sorted {
    // compiler verifies the result still satisfies `sorted`
    ...
}
```

Properties live where the capability is defined — not in a separate test file. They serve as machine-checked documentation and as lightweight proofs that the compiler can use to optimize and verify.

```
cap Stack extends Sequencing + Sized {
    prop push_increments: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop pop_decrements: forall (s: Self) ->
        s.size > 0 implies s.pop().size == s.size - 1
}
```

## Design principles

1. **`cap` is the primary abstraction** — capabilities describe what values can do, not what they are
2. **`data` is where structure gets planned** — `data` lets you express the shape of your data when you need a specific closed set of variants
3. **`prop` invariants are specified in place** — properties (from property-based testing) are built into the language as a form of lightweight theorem proving
4. **Functions are total** — only capabilities and data can be recursive; all recursion is structural via fold/unfold
5. **No explicit layouts** — the programmer never writes references; the compiler handles borrowing/moving/cloning via Perceus
6. **Everything is an expression** — no statements, no semicolons; blocks return their last expression
7. **Algebraic effects for all side effects** — declared with `effect`, handled with `handle`/`resume`
8. **No mutation** — purely functional; the compiler optimizes in-place updates via FBIP
9. **Agent friendly** — the compiler produces clear, structured output designed for humans, which also makes it ideal for AI harnesses and automated tooling

## Theoretical foundation

Quine's five predicate functor operations map directly onto Fixed's capability combinators:

| Quine | Logic | Fixed |
|---|---|---|
| **Cropping** | Existential quantification (hide a variable) | Existential capabilities — return `is Cap`, hide the concrete type |
| **Padding** | Add a vacuous variable | Phantom type parameters — `data Tagged of (phantom Tag, Value)` |
| **Permutation** | Reorder arguments | Capability bounds are unordered — `A + B` = `B + A` |
| **Reflection** | Identify two variables | Named aliases — `N is Numeric` constrains multiple params to share a type |
| **Composition** | Conjoin predicates | Capability composition — `extends`, `+` bounds |


## Repository structure

```
examples/           15 example programs exercising the language design
docs/plans/         Implementation plan (phases 0–6)
spec/               (planned) Formal specification
stdlib/             (planned) Standard library in Fixed
src/                (planned) Compiler implementation in Rust
```

## Status

**Phase 0 (design) is under way.** The language design is explored through 15 example programs covering:

- Basic I/O, numeric polymorphism
- Capability-driven collections (Sequencing, Functor, Folding, RandomAccess, etc.)
- Optional/Result, error handling, the `Fail` effect
- JSON parsing with recursive capabilities
- Phantom-typed state machines
- Multiple effect composition and handler nesting
- Higher-kinded types, Monad, do-notation
- Binary search trees with fold-based traversals
- Unit-safe physics with phantom types
- A mini interpreter with eval effects
- Channel-based concurrency as effects
- Geometry with type aliases and data declarations
- Recursion schemes (catamorphism, anamorphism, hylomorphism, paramorphism)
- Property-based invariants (`prop`, `forall`, `implies`)

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
