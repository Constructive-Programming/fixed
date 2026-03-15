# Fixed Language — Implementation Plan

## Overview

Fixed is a purely functional, capability-only programming language inspired by Quine's predicate functor logic and De Goes' "Kill Data" philosophy, compiling to C via Perceus-style reference counting.

The programmer never defines concrete data types. All abstraction flows through **capabilities** (what other languages call traits/typeclasses). The compiler owns all representation decisions — it analyzes the set of capabilities a value must satisfy and selects the optimal concrete representation.

---

## Core Syntax Philosophy

Fixed's syntax is designed around the insight that if the programmer never names concrete types, the syntax for type parameters should be minimal and readable. Key principles:

### Capabilities, not types

The `cap` keyword defines a **capability** — what a value can *do*. There are no structs, enums, or classes. The word "type" in Fixed refers to the compiler's internal representation choice, never to something the programmer writes.

### The `is` keyword

Declares that a value satisfies a capability. Two forms:

```
// Named alias — N can be reused to relate parameters
fn fib(n: N is Numeric) -> N

// Anonymous — the compiler infers independently
fn count(collection: is Folding) -> u64
```

### The `of` keyword

Specifies what a capability operates over. Replaces angle brackets:

```
is Folding of i64           // a collection of i64s
is Folding of (N is Numeric) // a collection of some numeric type N
is Optional of u64           // an optional u64
```

Multi-parameter capabilities use tuple-style `of`:

```
cap Sized of (Part, Size is Numeric)
cap RandomAccess of (Part, Index is Numeric)
```

### Dependent types — functions that generate capabilities

A capability with value parameters is already a function from values to a constraint. Fixed makes this explicit: **`fn` can return `cap`**, making capabilities first-class and value-dependent.

#### The `fn -> cap` syntax

```
fn between(min: N is Ord, max: N) -> cap of N {
    prop in_range: min <= Self && Self <= max
}
```

At use sites, a cap-generating function is called wherever a capability name would appear:

```
fn validate_age(age: N is Numeric + between(0, 150)) -> N

type Port = u16 + between(1, 65535)
type Username = String + min_length(3) + max_length(32)
```

Constraints are declared at first use of the type variable (same rule as function params). `of N` names the type being refined. Bare `Self` in props refers to the constrained value (sugar for `forall (v: Self) -> v > 0`).

#### Cap-generating functions are first-class

Because `fn -> cap` produces a regular function, cap generators compose like any other value:

```
// Pass a cap generator as an argument
fn validate(
    value: N is Numeric,
    constraint: (N, N) -> cap of N,
    lo: N,
    hi: N,
) -> N is constraint(lo, hi) {
    if value < lo { Fail.fail("too low") }
    else if value > hi { Fail.fail("too high") }
    else { value }
}

// Compose cap generators
fn strict_between(min: N is Ord, max: N) -> cap of N {
    prop in_range: min < Self && Self < max    // strict inequality
}

// Return a cap generator from a function
fn range_for(unit: String) -> fn(f64, f64) -> cap of f64 {
    match unit {
        "celsius" => between,
        "kelvin" => fn(lo, hi) -> cap of f64 {
            prop in_range: lo <= Self && Self <= hi
            prop absolute: Self >= 0.0
        },
    }
}
```

#### Symbolic value params

Value arguments can be literals or in-scope bindings — the compiler verifies props symbolically:

```
fn clamp(value: N is Numeric + Ord, lo: N, hi: N) -> N is between(lo, hi) {
    if value < lo { lo }
    else if value > hi { hi }
    else { value }
}
```

#### `cap Name(params)` as sugar

A named capability with value params is sugar for a cap-generating function:

```
// These are equivalent:
cap Between(min: N is Ord, max: N) of N {
    prop in_range: min <= Self && Self <= max
}

fn between(min: N is Ord, max: N) -> cap of N {
    prop in_range: min <= Self && Self <= max
}
```

The `cap` form is convenient for simple, named refinements. The `fn` form is the general mechanism — use it when you need first-class cap generation, composition, or conditional logic.

#### Refinement capabilities are zero-cost

Whether defined via `cap(...)` or `fn -> cap`, refinement capabilities are **Marker** caps (no methods, zero-sized, compile-time only). They add zero constraint on representation — only prop obligations. A value `N is between(0, 100)` is still just an `N`.

### Quantities — how values interact with types and runtime

Fixed uses Quantitative Type Theory (QTT) to track how each binding is used. This is what makes dependent types (`fn -> cap`) sound with Perceus reference counting.

| Quantity | Meaning | Fixed example |
|----------|---------|---------------|
| **0** | Erased — type-level only, no runtime code | `fn -> cap` value params, `prop` expressions, `phantom` |
| **1** | Linear — used exactly once, no RC overhead | FBIP reuse, single-shot `resume`, unique handles |
| **ω** | Unrestricted — freely shared, Perceus RC | General values, captured closures |

Quantities are **inferred by the compiler**, not written by the programmer. The programmer sees capabilities; the compiler decides which values need RC, which can be reused in-place, and which are erased entirely.

The soundness argument: in `fn clamp(value: N, lo: N, hi: N) -> N is between(lo, hi)`, the return type mentions `lo` and `hi`. QTT assigns quantity 0 to these type-level uses — they generate no runtime code. The runtime uses of `lo` and `hi` in the function body are quantity ω (they may be used multiple times in comparisons). Total quantity per binding: ω. Perceus manages their lifetime normally. The type-level mentions are erased.

This replaces three ad-hoc mechanisms with one principled system:

| Before QTT | After QTT |
|---|---|
| "Compile-time only" refinements (ad hoc) | Quantity 0 — erased by the type system |
| Perceus reuse analysis heuristic for FBIP | Quantity 1 — guaranteed linearity, in-place mutation is sound |
| Perceus RC for everything else | Quantity ω — RC only where the type system says it's needed |

### `Part` — the implicit element type

Inside cap definitions, `Part` refers to the element type without needing an explicit type parameter:

```
cap Folding {
    fn fold<R>(init: R, f: (R, Part) -> R) -> R
}
```

This eliminates `<A>` boilerplate. When a cap has multiple type parameters, they're named explicitly in `of`.

### `Self.fn` vs `fn` — static vs instance methods

```
cap Sequencing {
    fn head -> is Optional          // instance method (called on a value)
    fn tail -> Self                  // instance method
    Self.fn cons(h: Part, t: Self) -> Self  // static/constructor (called on the type)
}

cap Empty {
    Self.fn empty -> Self           // static constructor
}
```

Static methods are called via dot on the type alias: `C.empty`, `C.cons(x, acc)`.

`Self.fn` constructors are **abstract** — they define what must exist, not how it's built. Concrete data types provide implementations via `satisfies` declarations that map data constructors to `Self.fn` names using `as`.

### `extends` for supertrait relationships

```
cap Optional extends Functor + Folding {
    fn isDefined -> Boolean
    fn orElse(e: Part) -> Part
}
```

### `satisfies` for type-provides-cap implementation

`extends` is cap-to-cap (structural inheritance). `satisfies` is type-provides-cap (implementation provision):

| Keyword | Relationship | Example |
|---------|-------------|---------|
| `extends` | Cap inherits requirements from another cap | `cap Monad extends Functor` |
| `satisfies` | Type provides concrete implementation of a cap | `Maybe satisfies Optional { ... }` |

A `satisfies` declaration maps data constructors to capability constructors by name using `as`:

```
data Maybe of A { Just(value: A), Nothing }

Maybe satisfies Optional {
    Just as some
    Nothing as none
}
```

Satisfactions are modular — brought into scope via `use`:

```
use std.maybe.Maybe satisfies Optional

fn wrap(x: A) -> is Optional of A {
    Optional.some(x)   // compiler resolves to Maybe.Just(x) via satisfaction
}
```

`use Type satisfies Cap` does two things in one line: it imports the type *and* brings the satisfaction mapping into scope.

Unreachable constructors are marked with `impossible`:

```
u64 satisfies Optional of Self {
    Self as some
    impossible as none   // u64 is always present — `none` is a compile error
}
```

### `Self of B` for higher-kinded returns

```
cap Functor {
    fn map<B>(f: Part -> B) -> Self of B
}
```

`Self` is the container shape, `of B` changes the element type. This replaces the `F for <_>` machinery with something more intuitive.

### Type aliases

`type` creates a shorthand for a set of capabilities. It does **not** create a new type — anywhere you write `is ArrayLike` the compiler sees the expanded capability set.

```
type ArrayLike = Sequencing + RandomAccess + Sized + Empty
type Collection = Sequencing + Functor + Folding + Filtering + Empty
```

Used in signatures just like any capability:

```
fn take(collection: C is ArrayLike, n: u64) -> C
fn binary_search(sorted: C is ArrayLike of (A is Ordered), target: A) -> ...
```

#### Parameterized type aliases

Type aliases can take value parameters, making them functions from values to capability sets:

```
type PortRange(min: u16, max: u16) = u16 + between(min, max)
type BoundedString(max: u64) = String + max_length(max)
type Clamped(lo: N is Numeric + Ord, hi: N) = N + between(lo, hi)
```

At use sites, they are called like cap-generating functions:

```
fn parse_port(s: String) -> N is PortRange(1024, 49151) with Fail of String { ... }
fn read_name() -> S is BoundedString(255) with Console { ... }
```

This is consistent with the `fn -> cap` story: `type Name(params) = expr` is sugar for a function that returns a capability set. The compiler expands it at every use site, just like plain type aliases.

### Data declarations — planning structure

`data` is where structure gets planned. When you need a specific **closed set of variants** — enumerations, tagged unions, domain-specific value types — the `data` keyword lets you express the shape. The `data` keyword defines a general algebraic data type (GADT):

```
// Simple enumeration — all unit variants
data Direction { North, South, East, West }

// Mixed variants — some carry data, some don't.
// Capability constraints work inside data definitions.
data Color { Red, Green, Blue, RGB(red: N is Numeric, green: N, blue: N) }

// Recursive data — the compiler handles allocation
data Expr {
    Lit(value: f64),
    Add(left: Expr, right: Expr),
    Mul(left: Expr, right: Expr),
    Neg(inner: Expr),
}

// Phantom-typed data
data Tagged of (phantom Tag, Value) {
    Tagged(value: Value)
}
```

Key properties:
- **The compiler still owns the representation** — `data` declares the shape (variants and fields), not the layout
- **Data types automatically satisfy capabilities** their shape supports (e.g., a multi-variant data gets `Folding` for free, a data with fields gets accessors)
- **Explicit `satisfies` declarations** map data constructors to capability constructors by name using `as`, bridging the gap between abstract `Self.fn` requirements and concrete data variants
- **Pattern matching** uses dot syntax: `Expr.Lit(v)`, `Color.RGB(r, g, b)`, `Direction.North`
- **`of` works on data** just like on caps: `data Tagged of (phantom Tag, Value)` introduces type parameters
- **Capability constraints inside data fields**: `RGB(red: N is Numeric, green: N, blue: N)` — all three fields share the same numeric type

### When to use `data` vs `cap`

| Use `cap` (capability) when... | Use `data` when... |
|---|---|
| You want the compiler to choose the representation | You need a specific closed set of variants |
| Multiple representations could work | The shape itself is the point (e.g., Red/Green/Blue) |
| You want maximum reusability across callers | You want exhaustive pattern matching on known variants |
| You're defining *what something can do* | You're defining *what something is* |

Use `satisfies` to **bridge** between data and capabilities — mapping data constructors to capability constructor requirements. This lets functions written against capabilities (`Optional.some(x)`) resolve to concrete data constructors (`Maybe.Just(x)`) at compile time.

### `match` vs `fold` — data vs capabilities

**`match` only works on `data` types** (concrete, closed variants). Capabilities are abstract — they don't expose a shape, so pattern matching on capability-qualified names is not valid.

**Capabilities are destructured via `fold`** — an abstract eliminator declared by the cap. Each cap that wants to be "opened" declares `fn fold<R>(...)` with one callback per constructor. This is the only way to case-split on a capability value.

| Operation | Data types | Capabilities |
|---|---|---|
| **Destruction** (case-split) | `match json { Json.Null => ... }` | `opt.fold((v) -> ..., () -> ...)` |
| **Construction** | `Json.Null`, `List.Cons(x, xs)` | `Optional.some(x)`, `Result.err(e)` |
| **Constructor casing** | Capitalized: `Json.Null`, `List.Cons` | lowercase: `Optional.some`, `Expr.lit` |

Rules:
1. **`match` arms may only use data-qualified patterns** — `Json.Null`, `List.Cons(h, t)`, `Direction.North`, `Ordering.Equal`, etc.
2. **Capability destruction uses `fold`** — `opt.fold(on_some, on_none)`, `expr.fold(on_lit, on_var, ...)`, `result.fold(on_ok, on_err)`
3. **Construction via cap constructors is valid** — `Optional.some(x)` is resolved via `satisfies` to `Maybe.Just(x)` at compile time
4. **Data constructors are Capitalized** — `List.Cons`, `List.Nil`, `Ordering.Less`, `Json.Null`
5. **Cap constructors are lowercase** — `Optional.some(x)`, `Expr.lit(v)`, `Result.ok(v)` (these are `Self.fn` names)

**Stdlib data types**: `List`, `Ordering`, `Pair`, `Json` are **data types**, so matching on them is valid. Their constructors are capitalized: `List.Cons`, `List.Nil`, `Ordering.Less`, `Ordering.Equal`, `Ordering.Greater`, `Pair`, `Json.Null`, `Json.Bool`, etc.

### Everything is an expression

Fixed has **no statements**. Every construct — `let`, `if`, `match`, `handle`, `use` — is an expression that produces a value. There are no semicolons. Block expressions return the value of their last sub-expression.

```
// `let` introduces a binding, the block returns the last expression
let x = 5
let y = x + 1
y * 2              // this block evaluates to 12

// `if` is an expression
let abs_n = if n < 0 { 0 - n } else { n }

// `match` is an expression
let name = match direction {
    Direction.North => "North"
    Direction.South => "South"
}
```

### Functions are total — recursion lives in data

Functions in Fixed **cannot call themselves** — they are total. Only capabilities and `data` types can be recursive (via `Self` appearing in constructor parameters). All recursion is driven by structural operations on recursive data — primarily `fold` (catamorphism) and `unfold` (anamorphism).

This is a deliberate design choice:
- **Totality** — fold over a finite structure always terminates; every function returns
- Makes all recursion **explicit and structural** — you can see the recursion scheme being used
- Enables **fusion** — the compiler can fuse unfold-then-fold (hylomorphism) to eliminate intermediate structures
- Aligns with the capability philosophy — recursion is a property of data, not of functions

```
// This is a compile error — functions cannot be recursive:
// fn factorial(n: u64) -> u64 { if n == 0 { 1 } else { n * factorial(n - 1) } }

// Instead, use fold on a recursive data structure:
fn factorial(n: u64) -> u64 {
    to_nat(n).fold(
        () -> 1,
        (acc) -> (acc_index + 1) * acc
    )
}
```

### Additional syntax

| Feature | Syntax | Replaces |
|---|---|---|
| Arrow function types | `Part -> B`, `(R, Part) -> R` | `fn(Part) -> B`, `fn(R, Part) -> R` |
| Arrow lambdas | `(x, y) -> x + y` | Rust-style `\|x, y\| x + y` |
| Parenthesless zero-arg methods | `fn head -> is Optional` | `fn head() -> is Optional` |
| Block lambdas | `.map { value -> expr }` | `.map((value) -> expr)` |
| Dot static calls | `C.empty`, `C.cons(x, acc)` | `C.empty()`, `C.cons(x, acc)` |
| Type aliases | `type ArrayLike = Seq + RA + Sized + Empty` | Repeating capability bundles everywhere |
| Parameterized type aliases | `type PortRange(min, max) = u16 + between(min, max)` | Repeating parameterized refinements |
| Data declarations | `data Color { Red, Green, Blue, RGB(...) }` | No prior equivalent (escape hatch) |
| Satisfaction decl | `Maybe satisfies Optional { Just as some, Nothing as none }` | Haskell's `instance`, Rust's `impl Trait for Type` |
| Impossible mapping | `impossible as none` | Partial satisfaction — unreachable constructor |
| Satisfaction import | `use std.maybe.Maybe satisfies Optional` | Brings satisfaction mapping into scope |
| No semicolons | Newlines separate expressions | `;` statement terminators |
| Cap-generating functions | `fn between(min, max) -> cap of N { prop ... }` | Separate type-level and value-level abstractions |
| Cap sugar | `cap Between(min, max) of N { ... }` | `fn between(min, max) -> cap of N { ... }` (equivalent) |

### `prop` — invariants specified in place

Properties from property-based testing are built into the language as a form of **lightweight theorem proving**. The `prop` keyword declares invariants directly inside capability or data definitions. The compiler checks them — statically where possible, via property-based testing otherwise.

```
cap Sorted extends Sequencing {
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)
}

cap Stack extends Sequencing + Sized {
    prop push_increments: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop pop_decrements: forall (s: Self) ->
        s.size > 0 implies s.pop().size == s.size - 1
}
```

Key properties of `prop`:
- **Lives where the capability is defined** — not in a separate test file. Properties are part of the interface contract.
- **Machine-checked documentation** — properties serve as documentation that the compiler verifies.
- **Compiler optimization hints** — a `prop sorted` on a collection lets the compiler assume sortedness and choose binary search over linear scan.
- **Dependent verification** — props are checked at quantity 0 (erased at runtime). Combined with `fn -> cap`, they form Fixed's dependent type system.
- **Fallback to testing** — when static verification is insufficient, the compiler generates property-based tests automatically.

Properties can also appear on `data` types:

```
data Nat {
    Zero,
    Succ(pred: Nat),

    prop non_negative: fold(() -> true, (inner) -> inner)
}
```

And on functions, as postconditions:

```
fn sort(collection: C is Sequencing) -> C is Sorted {
    prop result_same_size: result.size == collection.size
    ...
}
```

---

## Agent-Friendly CLI and Compiler Output

The compiler is designed to produce clear, structured output for **humans** — which turns out to be exactly what AI harnesses need too. No special "machine-readable" mode; just good defaults.

### The CLI

Everything starts from the CLI. Four commands, composable:

| Command | What it does |
|---|---|
| `fixed compile` | Compiles Fixed source to C |
| `fixed verify` | Validates all `prop` invariants — static proofs where possible, property-based tests otherwise |
| `fixed ship` | Builds a static executable (compile → C compiler → binary) |
| `fixed verify + ship` | Incrementally does all three: verify props, compile to C, build static binary |

The `+` operator composes commands. Each stage is incremental — if source hasn't changed since the last run, that stage is skipped. `fixed verify + ship` is the standard development loop: prove your properties, then produce a binary.

### Output design principles

- **Structured errors**: every diagnostic has a code (e.g., `E042`), a location, and a human-readable explanation. Copy-pasteable suggestions where applicable (see ambiguity resolution below).
- **No noise**: successful compilation produces no output. Only errors and warnings print.
- **Incremental feedback**: long-running `verify` reports progress per `prop` — which properties passed, which are being tested, which failed.
- **Exit codes**: `0` = success, `1` = compile error, `2` = verification failure, `3` = build failure. Clean for CI and scripting.
- **JSON output**: `--format json` flag on any command for structured output. Same information, machine-parseable. Useful for editor integrations, AI agents, and CI pipelines.

### Why this matters for AI

An AI agent running `fixed verify + ship` gets:
1. Clear pass/fail per property (not buried in a test log)
2. Structured errors with exact locations and suggested fixes
3. Deterministic exit codes for control flow
4. The same output a human reads — no translation layer needed

The compiler treats the developer's terminal and an AI's stdin/stdout as the same interface.

---

## Right-Bias and Ambiguity Resolution

### Right-bias convention

When the compiler auto-derives capability instances for multi-parameter types, it uses a **right-bias** convention. The rightmost (last) type parameter is the "active" one:

- A **Tuple of (A, B)** is both a `BiFunctor` (mapping over both `A` and `B`) and a `Functor` (mapping over `B` only). The `Functor` instance is derived automatically because of right-bias — `B` is the rightmost parameter.
- A **Result of (E, A)** is a `Functor` over `A` (the success value, rightmost), not `E` (the error).
- A **Map of (K, V)** is a `Functor` over `V` (the value), not `K` (the key).

This matches Haskell's convention and the natural reading: the "main" content is the last parameter, context/metadata comes first.

### Ambiguity resolution via compiler suggestions

When the compiler encounters an **ambiguity** — multiple valid ways to derive a capability instance — it does **not** silently pick one. Instead, it:

1. **Halts compilation** with a clear error describing the ambiguity
2. **Lists all valid options** as concrete code snippets the developer can copy-paste
3. The developer picks one and adds it to their code, resolving the ambiguity explicitly

Example compiler output:

```
error[E042]: ambiguous Functor instance for Pair of (A, B)

  Pair satisfies Functor in multiple ways:

  Option 1 — Functor over B (right-biased, conventional):
  │  cap Pair of (A, B) extends Functor {
  │      fn map<C>(f: B -> C) -> Pair of (A, C) { Pair(first, f(second)) }
  │  }

  Option 2 — Functor over A (left-biased):
  │  cap Pair of (A, B) extends Functor {
  │      fn map<C>(f: A -> C) -> Pair of (C, B) { Pair(f(first), second) }
  │  }

  hint: Option 1 is the right-biased default. Copy one of the above
        into your code to resolve this ambiguity.
```

This design principle applies broadly:
- **Auto-derived capabilities** use right-bias as the default heuristic
- **When right-bias is insufficient** (e.g., a type with three parameters, or a non-obvious "natural" parameter), the compiler asks
- **The developer always has the final word** — the compiler suggests, never silently decides on ambiguous cases
- **Suggestions are copy-pasteable code**, not abstract descriptions

---

## Capability-Driven Representation Selection

This is the central compiler innovation. The programmer never names a data structure — they declare **what capabilities** they need, and the compiler selects the best concrete representation.

### How it works

1. The compiler collects all capability bounds on a value across its entire lifetime
2. It builds a **capability set** — the intersection of all representations that satisfy those bounds
3. It selects the optimal representation from that set, considering performance characteristics

### Capability → representation mapping

| Capabilities requested | Compiler likely selects |
|---|---|
| `Folding` only | Anything — linked list, array, tree, stream |
| `Sequencing + Folding` | Linked list or array (both support cons/fold) |
| `RandomAccess + Sized` | Contiguous array (linked list cannot satisfy RandomAccess efficiently) |
| `Sequencing + RandomAccess + Sized + Functor + Filtering` | Resizable contiguous array (all constraints force this) |
| `Folding` with PGO showing sequential-only access | Contiguous array (PGO narrows further) |

### The key insight

The **more capabilities** you request, the **fewer representations** qualify. This is a feature: it means the programmer controls performance characteristics *indirectly* by expressing what operations they need, and the compiler handles the rest. A function that only needs `Folding` is maximally reusable; one that needs `RandomAccess + Sized` gets guaranteed O(1) indexing.

---

## Phase 0: Example Programs — COMPLETE

11 example `.fixed` programs stress-testing the language design:

| # | File | Exercises |
|---|---|---|
| 1 | `examples/01_basics.fixed` | `main`, `Console` effect, string literals, `is Numeric`, named/anonymous aliases |
| 2 | `examples/02_collections.fixed` | Capability-driven collections, type aliases, `data` declarations (Direction, Color, Tagged) |
| 3 | `examples/03_option_result.fixed` | `Optional`/`Result` caps, `Fail` effect, `satisfies`, fold for cap destruction |
| 4 | `examples/04_json.fixed` | `data Json` (6 variants), deep pattern matching on data types |
| 5 | `examples/05_phantom_types.fixed` | Phantom-typed state machines (Door, TcpSocket), unit-safe arithmetic (Quantity) |
| 6 | `examples/06_functor_monad.fixed` | HKTs, `Functor`/`Monad` hierarchy, `do` notation, `Optional extends Monad` |
| 7 | `examples/07_recursive_data.fixed` | Recursive data (Tree, Expr, Nat), BST ops, catamorphism/anamorphism/hylomorphism/paramorphism |
| 8 | `examples/08_effects_handlers.fixed` | Multiple effects (Fail, Log, Channel, Async), handler composition, concurrency as effects |
| 9 | `examples/09_interpreter.fixed` | `cap Expr` (8 constructors), fold-based eval, `satisfies` (AstNode), effects for eval errors |
| 10 | `examples/10_geometry.fixed` | `type` aliases, `data` declarations, geometry ops (area, perimeter, bounding box) |
| 11 | `examples/11_properties.fixed` | `prop` invariants, `forall`/`implies`, `impossible`, `fn -> cap` refinements, type aliases with refinements |

---

## Phase 1: Specification

Formal specification documents that pin down semantics before implementation.

### Deliverables

| File | Contents |
|---|---|
| `spec/syntax_grammar.ebnf` | Complete formal grammar covering all syntactic forms |
| `spec/type_system.md` | Capability classification rules, `of`/`Part`/`Self of B` semantics, representation selection, inference |
| `spec/effects.md` | Algebraic effects semantics, effect rows, evidence-passing compilation |
| `spec/pattern_matching.md` | Desugaring rules (`match` → `fold`), exhaustiveness checking algorithm |
| `spec/perceus.md` | Reference counting insertion, reuse analysis (FBIP), drop specialization, borrowing optimization |
| `spec/properties.md` | `prop` semantics, static verification vs property-based testing fallback, `forall`/`implies`, composability with `extends` |
| `spec/quantities.md` | QTT quantity inference (0/1/ω), interaction with Perceus RC, erasure rules for 0-quantity bindings, linearity checking for 1-quantity bindings, soundness argument for dependent types + RC |

### Key decisions to formalize

- **`of` and `Part` semantics**: How `Part` is resolved in multi-parameter capabilities. How `of` interacts with named aliases. How `Self of B` works for HKT returns.
- **`Self.fn` vs `fn`**: Static vs instance method distinction. Rules for when each is allowed. How static methods are dispatched via dot syntax on type aliases.
- **`extends` inheritance**: How capability extension works. Multiple inheritance via `+`. Diamond problem resolution.
- **Capability-driven representation selection**: The algorithm by which the compiler narrows representation choices from capability sets. How conflicting heuristics are resolved. PGO integration points.
- **Capability classification**: compiler-inferred categories (sum, product, recursive, capability-only, marker) based on signature shape
- **No statements**: everything is an expression; no semicolons; blocks return their last expression
- **Functions are total**: only capabilities and data can be recursive; all recursion is via fold/unfold on recursive data
- **No explicit layouts**: the programmer never writes references; the compiler handles all borrowing/moving/cloning via Perceus
- **`prop` semantics**: how properties are checked (static analysis vs property-based testing), what expressions are allowed inside `prop`, how `forall` quantification works, how properties on capabilities compose with `extends`
- **Dependent types via `fn -> cap`**: how cap-generating functions are type-checked, how symbolic value params are tracked through prop verification, equivalence between `cap Name(params)` sugar and `fn name(params) -> cap`, how first-class cap generators compose and are passed as arguments
- **QTT quantity inference**: how the compiler determines 0/1/ω for each binding. Type-level positions (cap params, props, phantoms) force 0. Single-use patterns yield 1. Everything else is ω. Interaction with Perceus: 0 → erased, 1 → no RC (direct reuse), ω → full RC
- **Erasure soundness**: 0-quantity bindings generate no runtime code. The compiler must verify they are never used at runtime — only in types, props, and phantom positions. This is what makes `fn -> cap` sound with RC.
- **Linearity and FBIP**: quantity-1 bindings enable guaranteed in-place mutation, replacing the heuristic reuse analysis with a type-level guarantee
- **Effect handler linearity**: `resume` is quantity 1 (single-shot). Multi-shot handlers (if supported) would need quantity ω
- **No explicit `self`**: instance methods don't declare a self parameter; the compiler infers it
- **Coherence rules**: how orphan rules work when there are no concrete types. Satisfaction declarations (`Type satisfies Cap`) are modular — they must be brought into scope via `use Type satisfies Cap` to be visible. No global coherence requirement.
- **Recursive capability detection**: Self in constructor parameter position
- **`satisfies` semantics**: How `Type satisfies Cap { Variant as constructor }` declarations map data constructors to capability constructors. How `impossible as constructor` marks unreachable constructors. How the compiler verifies soundness of partial satisfactions. How `use Type satisfies Cap` brings satisfaction mappings into scope.
- **Constructor resolution**: How the compiler selects among in-scope `satisfies` declarations when a `Self.fn` constructor is called on a capability. Context-based resolution via return types and assignment targets. Ambiguity errors with copy-pasteable suggestions.

---

## Phase 2: Parser + AST

Rust implementation of the front-end.

### Deliverables

| Component | Description |
|---|---|
| **Lexer** (`src/lexer/`) | Tokenizer targeting the EBNF grammar. Keywords: `cap`, `fn`, `is`, `of`, `extends`, `satisfies`, `impossible`, `as`, `effect`, `match`, `handle`, `with`, `let`, `if`, `else`, `do`, `use`, `mod`, `pub`, `phantom`, `Self`, `data`, `type`, `prop`, `forall`, `implies` |
| **Parser** (`src/parser/`) | Recursive descent or Pratt parser producing AST |
| **AST** (`src/ast/`) | Type definitions for all language constructs |

### AST node types needed

- **Items**: `CapDef`, `EffectDef`, `DataDef`, `TypeAlias` (optionally with `ValueParams`), `FnDef`, `PropDef`, `UseDecl`, `ModDecl`, `SatisfactionDecl`
- **Satisfaction**: `SatisfactionDecl` (`Type satisfies Cap { mappings... }`), `ConstructorMapping` (`Variant as cap_constructor`), `ImpossibleMapping` (`impossible as cap_constructor`)
- **Cap members**: `InstanceMethod`, `StaticMethod` (the `Self.fn` distinction)
- **Capability refs**: `IsCap` (anonymous), `NamedAlias` (`N is Numeric`), `OfApp` (`Folding of i64`), `SelfOf` (`Self of B`), `CapCall` (`between(10, 30)` — cap-generating function applied to args), `UseSatisfaction` (`use Type satisfies Cap` — satisfaction import)
- **Cap generation**: `CapReturnType` (`cap of N` as return type), `ValueParams` (definition-site `(min: N is Ord, max: N)`), `ValueArgs` (use-site `(10, 30)`)
- **Expressions**: `Match`, `If`, `Let`, `FnCall`, `MethodCall`, `StaticCall` (`C.empty`), `Closure`, `BlockLambda`, `Do`, `Handle`, `Block`, `Literal`, `BinaryOp`, `UnaryOp`, `FieldAccess`, `ListLiteral`, `Pipe`, `Forall`, `Implies`
- **Data**: `DataVariant` (unit variant, tuple variant with named fields), `PhantomParam`, `OfParams`
- **Patterns**: `DataVariantPat` (`Expr.Lit(v)`, `List.Cons(h, t)`) — only data-qualified patterns allowed in `match`; no `CapConstructorPat` (capabilities are destructured via `fold`, not `match`), `WildcardPat`, `BindingPat`, `LiteralPat`, `DestructurePat`, `GuardedPat`
- **Types**: `ArrowType` (`A -> B`), `TupleArrowType` (`(A, B) -> C`), `CapBound` (`is Cap + Cap`), `OfType` (`Cap of X`), `CapType` (`cap of N` — capability as return type)

### Milestone

All 16 example programs parse successfully into AST.

---

## Phase 3: Type Checker

### Deliverables

| Component | Description |
|---|---|
| **Capability classifier** (`src/types/classify.rs`) | Analyze capability signatures → sum/product/recursive/capability-only/marker |
| **Type alias expander** (`src/types/alias.rs`) | Expand `type` aliases to their underlying capability sets |
| **Data type analyzer** (`src/types/data.rs`) | Analyze `data` declarations: variant shapes, auto-derive capabilities, GADT type parameter constraints. Validate `satisfies` declarations: verify constructor arity/type compatibility, check `impossible` soundness (no reachable code path invokes the marked constructor) |
| **Type inference** (`src/types/infer.rs`) | Bidirectional inference with capability bounds, `Part` resolution, `of` application |
| **Representation selector** (`src/types/repr.rs`) | Analyze capability sets → narrow to viable concrete representations |
| **Effect inference** (`src/types/effects.rs`) | Infer and propagate effect rows, verify all effects handled |
| **Exhaustiveness checker** (`src/types/exhaustive.rs`) | Verify `match` arms cover all constructors |
| **Property verifier** (`src/types/props.rs`) | Static verification of `prop` invariants; generate property-based tests for those that can't be proven statically. Resolves value params in refinement capability props (e.g., `Between(lo, hi)` binds `min`/`max` to `lo`/`hi` in prop expressions) |
| **Quantity checker** (`src/types/quantities.rs`) | Infer QTT quantities (0/1/ω) for all bindings. Verify erasure: 0-quantity bindings used only in type/prop/phantom positions. Verify linearity: 1-quantity bindings used exactly once. Feed quantities into Perceus insertion (Phase 4) |

### Capability classification rules

| Category | Detection rule | Representation |
|---|---|---|
| **Sum** | Multiple `Self.fn(...) -> Self` constructors + `fold` method | Tagged union |
| **Product** | Single `Self.fn(...) -> Self` constructor + accessor methods | Flat struct |
| **Recursive** | `Self` appears in constructor parameter types | Heap-allocated (optimizable) |
| **Capability-only** | No constructors returning `Self` (only instance methods) | Vtable / monomorphized |
| **Marker** | No methods at all | Zero-sized, compile-time only |
| **Refinement** | No methods, has `prop` + value params | Zero-sized, compile-time only (subtype of Marker) |

### `Self.fn` constructors and the cap-to-data bridge

`Self.fn` declarations in capabilities define **constructor requirements** — abstract construction interfaces where `Self` is not yet a concrete type. Functions can summon values (`Optional.some(x)`, `C.empty`) so construction must be expressible at the capability level.

**`satisfies` declarations** connect abstract constructors to concrete data types. The mapping is by name using `as`:

```
data Maybe of A { Just(value: A), Nothing }

Maybe satisfies Optional {
    Just as some
    Nothing as none
}
// fold is auto-derived from the two variants
```

Key design rules:
- **`extends` vs `satisfies`**: `extends` is cap-to-cap inheritance (`cap Monad extends Functor`). `satisfies` is type-provides-cap implementation (`Maybe satisfies Optional`).
- **Satisfaction declarations are separate** from data definitions — they can live in the same module or a different one. Third parties can write them.
- **Brought into scope via `use`**: `use std.maybe.Maybe satisfies Optional` makes the satisfaction mapping available at the use site.
- **Multiple satisfactions possible**: different modules can map the same data to different caps.
- **No orphan surprises**: satisfactions don't leak across module boundaries.

### Constructor resolution

When a `Self.fn` constructor is called on a capability (e.g., `Optional.some(x)`), the compiler resolves to a concrete data constructor using `satisfies` declarations in scope.

**Context-based resolution** — the compiler infers the backing type from context:

```
// Return type provides context:
fn wrap(x: A) -> is Optional of A {
    Optional.some(x)   // resolves via in-scope satisfaction
}

// Assignment target constrains:
let x: is Optional of u64 = Optional.some(42)
```

**Ambiguity errors** — when multiple satisfactions are in scope:

```
let y = Optional.some(42)
// error[E051]: ambiguous constructor for Optional.some
//
//   Multiple types in scope satisfy Optional:
//
//   Option 1 — Maybe of u64:
//   │  Maybe.Just(42)
//
//   Option 2 — Nullable of u64:
//   │  Nullable.Present(42)
//
//   hint: Add a type annotation to disambiguate:
//   │  let y: is Maybe of u64 = Optional.some(42)
```

**Direct data construction always works** — no resolution needed:

```
let z = Maybe.Just(42)
```

### Partial satisfaction with `impossible`

Any type can satisfy a cap, marking unreachable constructors with `impossible`:

```
u64 satisfies Optional of Self {
    Self as some
    impossible as none
}
```

Meaning: `u64` is always present (`some` = identity), never absent (`none` = unreachable). The compiler verifies soundness — any code path that would invoke `none` on a `u64 is Optional` is a compile error.

```
data NonEmptyList of A { Cons(head: A, tail: is List of A) }

NonEmptyList satisfies Sequencing {
    Cons as cons
    impossible as empty
}
```

### Capability-set representation narrowing

New compiler pass: given all capabilities a value must satisfy, determine which concrete representations are viable:

1. Collect all `is` bounds on a value across its entire usage scope
2. Collect all `satisfies` declarations in scope for each bound
3. For each candidate representation (data types with matching `satisfies` declarations, plus compiler-known built-in representations), check if it can satisfy all bounds
4. Rank remaining candidates by performance (PGO data if available, heuristics otherwise)
5. Select the best candidate

### Milestone

All 16 example programs type-check with correct capability classifications, representation selection, and effect tracking.

---

## Phase 4: Code Generation (to C)

### Compilation pipeline

```
Typed AST
  → Capability-set analysis (collect all bounds per value)
  → Representation selection (capability set → concrete layout)
  → Monomorphization (specialize generic functions)
  → Match desugaring (match → fold calls)
  → Perceus insertion (dup/drop/reuse token placement)
  → Evidence passing (effect operations → evidence vector lookups)
  → C emission (structs, tagged unions, function pointers, refcount ops)
```

### Deliverables

| Component | Description |
|---|---|
| **Capability analyzer** (`src/codegen/caps.rs`) | Collect and resolve capability sets per value |
| **Representation selector** (`src/codegen/repr.rs`) | Map capability sets to C layouts |
| **Monomorphizer** (`src/codegen/mono.rs`) | Specialize generic functions for concrete type params |
| **Match desugarer** (`src/codegen/desugar.rs`) | Convert `match` to `fold` calls, `if` to `bool.fold` |
| **Perceus inserter** (`src/codegen/perceus.rs`) | Insert `dup`/`drop` operations guided by QTT quantities. 0-quantity bindings: erased (no code). 1-quantity bindings: no RC, direct reuse. ω-quantity bindings: full Perceus RC (dup on share, drop on last use, reuse tokens on unique) |
| **Evidence passer** (`src/codegen/evidence.rs`) | Compile effects to evidence vector lookups |
| **C emitter** (`src/codegen/emit_c.rs`) | Generate readable C code |

### C representation mapping

| Fixed concept | C representation |
|---|---|
| Sum capability (e.g., `Optional`) | `struct { uint8_t tag; union { ... } data; }` |
| Data type (e.g., `Color`, `Expr`) | `struct { uint8_t tag; union { ... } data; }` (same as sum, but shape is programmer-defined) |
| Product capability (e.g., `Config`) | `struct { field1_t field1; field2_t field2; ... }` |
| Recursive capability (e.g., `Sequencing` backed by linked list) | Heap-allocated node with refcount header |
| Capability satisfied by contiguous array (e.g., `RandomAccess + Sized`) | `struct { size_t len; size_t cap; T* data; }` |
| Closure | `struct { fn_ptr code; env_ptr env; }` |
| Block lambda | Same as closure (syntactic difference only) |
| Effect operation | Evidence vector lookup + indirect call |
| `resume` | Direct function call (single-shot) |
| Refcount | `struct { atomic_int rc; ... }` header on heap objects |

### Milestone

All 16 example programs compile to C, the C compiles with gcc/clang, and the binaries run correctly.

---

## Phase 5: Standard Library

Written in Fixed itself. The standard library defines the core capabilities that the compiler uses for representation selection.

### Deliverables

| File | Contents |
|---|---|
| `stdlib/core.fixed` | `Optional`, `Result`, `Pair`, `Ordering`, `Boolean`, `Empty` |
| `stdlib/capabilities.fixed` | `Eq`, `Ord`, `Show`, `Clone`, `Default`, `Hash`, `Numeric`, `String` |
| `stdlib/collections.fixed` | `Sequencing`, `Functor`, `Folding`, `Filtering`, `Sized`, `RandomAccess`, `Map`, `Set` |
| `stdlib/io.fixed` | `Console`, `FileSystem`, `Clock`, `Random` effects + `Fail` |

### Notes

- Primitives (`i8`..`i128`, `u8`..`u128`, `f32`, `f64`, `bool`, `char`, `()`) are compiler-provided and satisfy relevant capabilities
- The compiler has built-in knowledge of stdlib capabilities for representation selection (e.g., `RandomAccess` implies contiguous storage is preferred)
- `String` is a capability, not a concrete type — the compiler chooses the backing representation

---

## Phase 6: PGO + Optimization

Profile-guided optimization for representation selection.

### Deliverables

| Component | Description |
|---|---|
| **Instrumented mode** | Compiler flag that inserts profiling counters into generated C |
| **Profile reader** | Reads profile data and feeds it back into compilation |
| **Representation optimizer** | Narrows representation choices further based on profile data |
| **Hot-path specializer** | Inline frequently-called closures, eliminate dynamic dispatch on hot paths |

### PGO-driven decisions

- Sequential-only fold access → contiguous array (even if capabilities didn't require it)
- Heap allocation → stack allocation (when lifetime is short and bounded)
- Dynamic dispatch → monomorphized (when callee is statically known in practice)
- Closure allocation → inlined (when closure is called exactly once)
- Linked list → contiguous array (when profiling shows random access patterns despite only `Sequencing` being required)

---

## Open Design Questions

1. **`Part` resolution in multi-param capabilities**: `Sized of (Part, Size is Numeric)` — is `Part` always the first parameter? Can it be renamed? How does it interact with `extends`?

2. **Coherence with capability-only types**: Orphan rules when there are no concrete types. Current direction: compiler auto-generates implementations; users write blanket impls constrained by other capabilities.

3. **Mutual recursion**: Two capabilities referencing each other's `Self`. The compiler must detect cycles and co-allocate.

4. **Error messages**: Must speak in capability terms, never expose generated struct names. "Expected `is Folding of i64`, found `is Folding of String`."

5. **FFI boundary**: C interop requires concrete types. `extern` blocks declare C functions with concrete primitive types; the compiler generates wrapper functions.

6. **Effect handler composition syntax**: Nested `handle` blocks work but are verbose. Consider a cleaner composition syntax.

7. **`Self of B` vs `F for <_>`**: The new `Self of B` syntax is more intuitive for single-param HKTs. Does `F for <_>` survive for cases where you need to abstract over the container itself (e.g., `fn sequence<M is Monad for <_>>`)?

8. **Block lambda vs closure syntax**: When does `{ x -> expr }` apply vs `(x) -> expr`? Are both supported, or does block lambda replace closures entirely?

9. **Data auto-deriving capabilities**: Which capabilities does a `data` type automatically satisfy? Multi-variant → `Folding`? With accessors → `Functor`? Recursive → auto-heap-allocation? Right-bias determines which parameter `Functor` maps over. Need clear rules for the full derivation matrix.

10. **GADT type equality constraints**: `data Expr of (A) { Lit(value: A), Add(left: Expr of i64, right: Expr of i64) }` — do we support type-level constraints per variant (true GADT power)? Or is `of` on data purely parametric?

11. **Type alias `of` interaction**: `is ArrayLike of (A is Ordered)` — how does `of` distribute across the expanded capabilities? Does it apply to all of them, or only those that accept `of`?

---

## Verification Plan

1. Parse all 16 example programs → AST
2. Type-check all 16 programs (capability classification, representation selection, effect tracking)
3. Verify QTT quantity inference: 0-quantity bindings in type/prop/phantom positions only, 1-quantity bindings used exactly once, ω-quantity bindings get RC
4. Verify pattern match exhaustiveness on sum capabilities
5. Verify effect tracking catches unhandled effects
6. Verify capability-set narrowing selects correct representations
7. Compile to C → compile C → run binaries → verify output
8. Verify erasure: 0-quantity bindings produce no runtime code in generated C
9. Verify linearity: 1-quantity bindings have no dup/drop in generated C
10. Benchmark against equivalent Koka programs
11. Test PGO: verify representation changes improve performance on profiled workloads
