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
- **QTT** — Quantitative Type Theory tracks how often each binding is used: 0 (erased, type-level only), 1 or ω (many). Erasure is what makes dependent types sound with reference counting. In-place update (**FBIP, Functional But In-Place**) and the Perceus counts follow a separate inferred fact, ownership: a value owned exclusively is updated in place, a shared one only after a run-time `rc == 1` test. Using a binding once does not make its value exclusively owned.
- **Evidence passing** — effects compile to evidence-vector lookups, not full delimited continuations (specified; today only operations that abort are compiled, as direct calls and checked aborts).

You will recognize some of these features from [Koka](https://koka-lang.github.io/koka/doc/index.html). Koka is a research language that got several hard things right and Fixed adopts all of them wholesale. The compilation target is LLVM IR. Memory management is Perceus. Effects are algebraic with evidence passing. FBIP optimizes functional updates.

#### What Fixed changes

Where Koka still has `struct`, `enum`, and named data types, Fixed pushes the language up one level of abstraction:

| Koka | Fixed |
|---|---|
| `struct Point { x: int; y: int }` | `cap HasX` with the member `fn x -> f64`, and `cap HasY` with `fn y -> f64`; a function asks for `P is HasX + HasY` |
| `type list<a> { Cons(a, list<a>); Nil }` | `cap Sequencing`, with `fn head -> is Optional of Part` and `Self.fn cons(h: Part, t: is Sequencing of Part) -> Self` |
| `type either<a,b> { Left(a); Right(b) }` | `cap Result of (E, A)`, with `Self.fn ok(value: A) -> Self` and `Self.fn err(error: E) -> Self` |
| Programmer picks the representation | Compiler picks the representation |

The programmer writes **capabilities** — what values can do — and the compiler decides how to lay them out in memory. Request `Sequencing + Folding`? The compiler might choose a linked list. Add `RandomAccess + Sized`? Now it *should* use a contiguous array. The capability set narrows the representation space.

The choice is made late. A package is compiled once, to code that works for every representation; where a representation depends on how the rest of the program uses a type, it is chosen when the application is linked, from usage summaries, and recorded in a lock file. Nothing is recompiled when a choice changes. Declarations are private unless marked `pub`. Several satisfactions of one capability by one type may coexist (`Money satisfies Ord as ByAmount`), and `using` chooses between them (`sort(xs) using ByAmount`); an ambiguity that no `using` settles is an error where it occurs. (Specified in `spec/compilation_model.md` §8 and `spec/type_system.md` Rules 8.4 and 8.6; the link step, `pub`, `as` names for satisfactions and `using` are not built yet.)

This is the tagless final encoding applied to an entire language: programs are written against abstract interfaces, and the "interpreter" (the compiler) chooses the concrete semantics.

## Quick tour

Every sample in this tour is one of two kinds. A sample without a mark was compiled and run with the compiler in this repository, and the output shown is what it printed. A sample marked **Specified, not built** follows the grammar (`spec/syntax_grammar.ebnf`) and the rule it names, and the compiler does not implement that rule yet. Longer working programs are in `examples/`; examples `01`, `03`, `04` and `05` compile and run.

The compiled samples use no integer arithmetic. Under a rule that is specified and not built (`spec/effects.md` §3.6), an integer operation that is not proved to stay in range performs the effect `Overflow`, and a function that does such arithmetic will have to declare it. Arithmetic on `f64` follows IEEE 754 and performs no effect.

### Hello world

```fixed
use std.io.Console

fn greet(name: String) -> () with Console =
    Console.printLine("Hello, ".concat(name).concat("!"))

fn main() -> () with Console =
    Console.printLine("What is your name?")
    let name = Console.readLine()
    greet(name)
```

With `Ada` as input it prints `What is your name?` and `Hello, Ada!`. `with Console` declares the effect: a function that does input or output says so in its type. A body follows `=`, on the same line or indented below it, and the last expression of a block is its value. The specification makes every `String` valid UTF-8 (`spec/stdlib.md` Rule S3.7). Under Rule S3.7.a, which is specified and not built, `Console.readLine` performs the effect `Decode` when its input is not valid UTF-8, and this `main` will then declare `with Console + Decode`. Today `readLine` passes its input through unchecked.

### Capabilities, not types

A function states the capabilities it needs from its arguments, not the data structure that holds them:

```fixed
use std.io.Console

// A bundle of capabilities, named with a type alias.
type Label = Show + Ord

// Works on any collection that can be folded: a list, a tree, an array.
fn describe(items: is Folding of (T is Label)) -> String =
    items.fold("", (text, item) -> text.concat(item.show()).concat(" "))

// `T` names one type: the elements and `first` share it.
fn largest(items: is Folding of (T is Label), first: T) -> T =
    items.fold(first, (best, item) -> if item > best: item else: best)

// An anonymous result: the signature does not say which type is returned.
fn ifPositive(n: i64) -> is Optional of i64 =
    if n > 0: Optional.some(n) else: Optional.none

fn main() -> () with Console =
    Console.printLine(describe([3, 12, 40]))
    Console.printLine(largest(["pear", "fig", "plum"], "apple"))
    Console.printLine(ifPositive(-4).fold((n) -> n.show(), () -> "none"))
```

It prints `3 12 40 `, `plum` and `none`. `T is Label` names the element type, so that `largest` can take a `first` of the same type; a bound without a name (`is Optional of i64`) leaves the type unnamed. The caller picks the type of a named parameter. The type of an anonymous result is chosen for the whole program when the application is linked (`spec/compilation_model.md` §8). That link step is not built: today a stand-in rule gives such a result the one data type in the program that has all the capabilities.

### The `is` / `of` syntax

Not having to name the data structure is the main reason Fixed exists:

```fixed
use std.io.Console

// The type of the collection is never named.
fn joined(words: is Folding of String) -> String =
    words.fold("", (text, word) -> text.concat(word))

fn main() -> () with Console =
    Console.printLine(joined(["is", " ", "of"]))
```

It prints `is of`. `is` introduces a bound and `of` gives a capability its type arguments. The type of `words` appears nowhere; the function still knows that it holds strings and can be folded.

### Data types: planning structure

When a closed set of variants is needed, `data` declares the shape, one variant per line:

```fixed
use std.io.Console

data Color:
    Red
    Green
    Blue
    Rgb(red: u8, green: u8, blue: u8)

data Expr:
    Lit(value: f64)
    Add(left: Expr, right: Expr)
    Mul(left: Expr, right: Expr)
    Neg(inner: Expr)

// `fold` is derived from the shape of `Expr`: one function per variant,
// and each recursive field arrives already evaluated.
fn eval(e: Expr) -> f64 =
    e.fold(
        (v) -> v,
        (l, r) -> l + r,
        (l, r) -> l * r,
        (x) -> -x,
    )

fn main() -> () with Console =
    Console.printLine(Color.Rgb(255, 128, 0).show())
    let e = Expr.Add(Expr.Lit(1.5), Expr.Mul(Expr.Lit(2.0), Expr.Neg(Expr.Lit(3.0))))
    Console.printLine(eval(e).show())
```

It prints `Rgb(255, 128, 0)` and `-4.5`. Neither type declares `fold` or `show`: the compiler derives `fold`, `unfold`, `para`, `map`, `show`, `==` and `compare` from the shape. `data` is where structure gets planned. The compiler still owns the representation: the program declares the shape, not the layout.

### Algebraic effects

```fixed
use std.io.Console

fn ratio(a: f64, b: f64) -> f64 with Fail of String =
    if b == 0.0: Fail.fail("division by zero") else: a / b

// The handler removes `Fail` from the row: `report` performs no effect.
fn report(a: f64, b: f64) -> String =
    handle ratio(a, b):
        Fail.fail(e) => "error: ".concat(e)
        return(v) => "ratio: ".concat(v.show())

fn main() -> () with Console =
    Console.printLine(report(3.0, 4.0))
    Console.printLine(report(1.0, 0.0))
```

It prints `ratio: 0.75` and `error: division by zero`. `Fail` is declared in the prelude, and its operation `fail` returns `!`: it never returns, so the handler clause gives the value of the whole `handle`. An effect that is not handled before `main` is a compile error: a `main` that calls `ratio` without a handler is rejected with `error[T015]: effect Fail of String is not declared by main`. The runtime handles a few effects at `main` itself, such as `Console`. `Diverge`, the effect of a function that may not return, has no handler. Only operations that abort, like `fail`, are built; an operation that returns to its caller through `resume` is specified and not built.

**Integer overflow and division by zero.** **Specified, not built** (`spec/effects.md` §3.6, Rules E3.6.b, E3.6.c and E3.6.h):

```fixed
fn sum(numbers: is Folding of (N is Numeric)) -> N with Overflow =
    numbers.fold(0, (acc, x) -> acc + x)

fn describeSum(numbers: is Folding of u8) -> String =
    arithmeticOrNone(() -> sum(numbers)).fold((n) -> n.show(), () -> "does not fit a u8")

fn scale(a: u32, b: u32, c: u32) -> u32 with Arithmetic =
    widen(() -> a * b / c)
```

Integer `+`, `-`, `*` and negation perform the effect `Overflow` when the result leaves the range of the type, and so do `MIN / -1`, `MIN % -1` and a conversion to a narrower type. `/` and `%` perform `DivideByZero` when the divisor is zero. `Arithmetic` names both. Nothing wraps silently. The effect is absent from an operation's type where the static prover or a refinement shows the result in range, or where every operand is a constant. Generic code over `N is Numeric` always declares `Overflow`, even when it is called at `f64`. The prelude handles the effect into an optional value (`arithmeticOrNone`) or a result (`arithmeticOrError`), and `widen` computes a block at the next wider integer type and narrows once at the end. Unhandled at `main`, the program stops with the position of the operation and exit status `101`. Today the compiler has none of this: `+`, `-` and `*` wrap (`200 * 7` at `u8` gives `120`), and an integer division by zero stops the program with `fixed: panic: integer division by zero` and status `101`.

### Properties: lightweight theorem proving

`prop` declares an invariant next to the capability or data type it governs. The static prover decides a small fragment today (props over bounded integers and `bool`); these two samples are outside it and are **specified, not built** (`spec/properties.md` Rules P3.1, P3.2 and P5.4; the second sample is the worked example of §11.1):

```fixed
cap Sorted of (Part is Ord) extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)

data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop inRange: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi
```

```fixed
cap Stack extends Sequencing + Sized:
    fn push(value: Part) -> Self
    fn pop -> Self

    prop pushIncrements: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop popDecrements: forall (s: Self) suchThat s.size > 0 ->
        s.pop().size == s.size - 1

    prop pushPopIdentity: forall (s: Self, x: Part) ->
        s.push(x).pop == s
```

The static prover checks a prop where it can. Property-based testing in a separate test build (`fixed verify`) checks the others. Only a proved prop, or one the program states with `assume prop` (Rule P7.7), steers what the compiler does; a prop that was only tested is evidence, not a fact the compiler relies on. The compiler today sends a prop to the static prover when its body is integer and `bool` arithmetic over `forall` binders of the integer types and `bool` (`proved`, `error[E102]` with a counterexample, or open), leaves every other prop open, and does not parse `assume`.

### Refinements: functions that generate capabilities

A function can return a capability, which refines a type with a prop. **Specified, not built** (`spec/properties.md` Rule P3.4, `spec/type_system.md` Rule 6.3.0, and `TypeAliasParams` in §7 of the grammar):

```fixed
fn between(min: N is Ord, max: N) -> cap of N =
    prop inRange: min <= Self && Self <= max

fn validateAge(age: N is Numeric + between(0, 150)) -> N = age

type Port = u16 + between(1, 65535)
type PortRange(min: u16, max: u16) = u16 + between(min, max)
```

A capability generator is used in a bound by calling it (`between(0, 150)`); as a value, for example a parameter, it has the type `(N, N) -> cap of N`. A type alias can take value parameters; it is expanded where it is used. The arguments of a refinement are constants (Rule 6.3.3): `PortRange(1024, 2048)` is legal, a bound that names a value of the run is not. Types that depend on run-time values (dependent types) are an open direction, not a feature (`spec/type_system.md` OQ-T3). A refinement costs nothing at run time: it is checked at compile time, nothing wraps the value, and the value keeps its type. `cap Between(min: N is Ord, max: N) of N` is sugar for the function `between` (`spec/type_system.md` §6.3). The compiler today accepts these declarations and checks no refinement.

## Design principles

1. **`cap` is the primary abstraction** — capabilities describe what values can do, not what they are
2. **`data` is where structure gets planned** — `data` lets you express the shape of your data when you need a specific closed set of variants
3. **`prop` invariants are specified in place** — properties (from property-based testing) are built into the language as a form of lightweight theorem proving
4. **Functions are total unless they declare `Diverge`** — recursion goes through `fold`, `para` and `unfold` on recursive data, or is structural (each recursive call passes, in the same parameter position, a strict sub-value bound by a `match` on that parameter); other recursion between functions needs `with Diverge`, otherwise it is an error (`T027`); a capability's default bodies may not recurse through `self` at all (`T028`)
5. **No mutation** — purely functional; the compiler optimizes in-place updates via FBIP
6. **Everything is an expression** — no statements, no semicolons; blocks return their last expression
7. **No explicit layouts** — the programmer never writes references; the compiler handles borrowing/moving/cloning via Perceus
8. **Algebraic effects for all side effects** — declared with `effect`, handled with `handle`/`resume`
9. **Quantities and ownership are inferred, not written** — the compiler infers how often each binding is used (0, 1, ω) and which values are borrowed, owned or exclusively owned. The programmer writes capabilities; the compiler infers resource usage
10. **Agent friendly** — the compiler produces clear, structured output designed for humans, which also makes it ideal for AI harnesses and automated tooling

## Theoretical foundation

Quine's five predicate functor operations map directly onto Fixed's capability combinators. The last row adds quantities, which are not one of Quine's operations:

| Quine | Logic | Fixed |
|---|---|---|
| **Cropping** | Existential quantification (hide a variable) | Existential capabilities — return `is Cap`, hide the concrete type |
| **Padding** | Add a vacuous variable | Phantom type parameters — `data Tagged of (Tag, Value)` with the one variant `Tagged(value: Value)`: `Tag` appears in no field |
| **Permutation** | Reorder arguments | Capability bounds are unordered — `A + B` = `B + A` |
| **Reflection** | Identify two variables | Named aliases — `N is Numeric` constrains multiple params to share a type |
| **Composition** | Conjoin predicates | Capability composition — `extends`, `+` bounds |
| **Quantities** | Multiplicity of variable usage | QTT quantities — 0 (erased), 1, ω. Quantity 0 is erased; reference counting and reuse follow inferred ownership |


## Repository structure

```
examples/           11 example programs exercising the language design
spec/               Formal specification (type system, grammar, effects, memory, ...)
docs/               Implementation plan, phase plans, decisions, baselines
docs/diagrams/      Mermaid diagrams of memory layouts and value flow
compiler/           Compiler front end and backend (Scala 3)
scalafix/           Custom Scalafix rules + testkit harness
bench/              JMH benchmarks for the front end
editors/            VS Code, Zed, tree-sitter, Pygments, highlight.js, Laika support
site/               Laika + Helium sources for fixed.constructive.dev
```

Editor and highlighter artifacts are documented in `editors/README.md`; the
site has an [editor support page](https://fixed.constructive.dev/editors.html).

## Running the compiler

```
sbt "run -- examples/01_basics.fixed"                          # check; prints diagnostics, exit code by error class
sbt "run -- compile examples/01_basics.fixed -o 01.ll"         # LLVM IR
sbt "run -- ship examples/01_basics.fixed -o 01"               # native executable, via clang
sbt "run -- compile examples/01_basics.fixed --explain-memory" # memory and clone report
```

`--no-specialise` builds no clones beyond the mandatory ones. There is one package and no link step yet.

Specified and not built: `fixed link` (the application link and its lock file), `fixed release` (the release executable: clones built at link, `inspect` and `trace` stripped unless `--keep-diagnostics`; `fixed ship` stays the development build, and there is no `--release` flag), `fixed verify` (the test build), and composed commands `a + b`. The exit code is the smallest error class among the errors of the run (`spec/reports.md` Rule RP5.5): `4` for a parse error, `6` for a type error, `18` when `clang` fails, `99` for a form the compiler does not support yet, and so on; `2` is a usage error.

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

Phases 0 (design), 2 and 2.1 (parser), 4.0 (naive backend) and most of 4.1 (polymorphic baseline and specialiser) are done. Phase 1 (specification) exists as drafts under `spec/`. Of the type checker (Phase 3), the base typer, capability closure, effect inference and quantity, demand and lifetime inference are done; property verification is built in part (the static prover on a small fragment of props; no property-based testing) and representation selection is not. Examples `01`, `03`, `04` and `05` compile to native code and run, in the default and `--no-specialise` modes, against pinned output (`examples/golden/`). Examples `02` and `06` type-check with no diagnostics, but the backend does not compile them yet. Examples `07` to `11` still have type errors: `07`, `09` and `11` mostly on names the prelude does not provide yet (`max`, `concat`, `reverse`), `08` and `10` on type mismatches. The test suite runs them through the parser and the type checker.

Phase 4.2, the decisions of `docs/decisions/P6-resolution-round-2026-11-20.md`, is in progress. Built: the totality check (structural recursion and recursion under `Diverge` are accepted, other recursion is `T027`, a cycle among a capability's default bodies is `T028`); derived `para`; `fold`, `para`, `unfold` and `map` of lists and `Nat` in bounded native stack (trees still take one frame per level); derived `Show`, `Eq` and `Ord`; a derived `Functor` only where `map` can reach every element, with a diagnostic that names the field that blocks it, and no declaration needed; literal patterns; `satisfies` bodies lowered and satisfactions keyed by symbol; `show`, `==` and `clone` on an unbounded type parameter as an error (`T022`); `Clone` on request, where the derived `clone` is a share of the receiver and a written body runs as written; camelCase names; exit codes by error class; one member, `match` case or `handle` clause on the line after `:`; `data` with no body as an empty type; the errors `T029` (empty `[]` of unknown element type), `T031` (a tuple has two elements), `T032` (`i128` and `u128` are reserved) and `T006` (wrong number of `of` arguments); the static prover (the Princess library inside the compiler, a budget in steps set with `--prover-steps=N`, a prop in its fragment is proved or fails with `E102` and a counterexample, the others stay open, statuses recorded in `props.lock`). Specified and not built: the application link, visibility, `using` and named satisfactions, list literals as choice points and the ascription `e is T`, `inspect` and `trace`, `assume prop`, the warning `W060` and `use … as` for hidden prelude names, the check of input for valid UTF-8 and the effect `Decode`, composed commands `a + b`, `fixed release`, and the arithmetic effects `Overflow` and `DivideByZero` (today `+`, `-` and `*` wrap; division by zero aborts with status `101`). Each item's state is in Phase 4.2 of the implementation plan.

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

The full annotated bibliography, grouped by the part of the language each
source informs, is on the site:
[References](https://fixed.constructive.dev/references/bibliography.html).

Core sources:

- [Death of Data](https://degoes.net/articles/kill-data) — De Goes on eliminating premature type commitment
- [Quine's Predicate Functor Logic](https://en.wikipedia.org/wiki/Predicate_functor_logic) — variable-free predicate logic
- [Tagless Final](https://okmij.org/ftp/tagless-final/) — encoding programs against abstract interfaces
- [Koka language](https://koka-lang.github.io/koka/doc/index.html) — algebraic effects, Perceus RC, FBIP, evidence passing
- [Quantitative Type Theory](https://bentnib.org/quantitative-type-theory.html) — Atkey, 2018. Dependent types with resource tracking via usage quantities
- [Perceus: Garbage Free Reference Counting with Reuse](https://doi.org/10.1145/3453483.3454032) — Reinking et al., PLDI 2021
- [FP²: Fully in-Place Functional Programming](https://doi.org/10.1145/3607840) — Lorenzen, Leijen, Swierstra, ICFP 2023
- [Counting Immutable Beans](https://doi.org/10.1145/3412932.3412935) — Ullrich, de Moura, IFL 2019
- [The Goose Language — Specification](https://aardappel.github.io/goose/docs/goose_spec.html) — data stacks, destinations, regions
- [Neut](https://vekatze.github.io/neut/) — closed resource functions and value witnesses
- [Capturing Types](https://doi.org/10.1145/3618003) — Scala 3's capture checker, the closest analog to capabilities
- [Rethinking Data and Codata through Matrix Transposition](https://hackernoon.com/rethinking-data-and-codata-through-matrix-transposition) — data/codata duality and its implications for language design

## License

TBD
