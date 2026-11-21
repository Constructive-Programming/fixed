# Fixed Language — Implementation Plan

## Overview

Fixed is a purely functional, capability-only programming language inspired by Quine's predicate functor logic and De Goes' "Kill Data" philosophy, compiling to LLVM IR via Perceus-style reference counting.

The compiler is bootstrapped in **Scala 3**, using `dotc` (the Scala 3 compiler) as its structural reference. See `docs/references/scala3-compiler-reference.md` for the pattern map — phase architecture, parser organization, typer pipeline, and the `cc/` capture checker as the closest analog to Fixed's capability checker.

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
fn between(min: N is Ord, max: N) -> cap of N =
    prop inRange: min <= Self && Self <= max
```

At use sites, a cap-generating function is called wherever a capability name would appear:

```
fn validateAge(age: N is Numeric + between(0, 150)) -> N

type Port = u16 + between(1, 65535)
type Username = String + minLength(3) + maxLength(32)
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
) -> N is constraint(lo, hi) =
    if value < lo: Fail.fail("too low")
    else if value > hi: Fail.fail("too high")
    else: value

// Compose cap generators
fn strictBetween(min: N is Ord, max: N) -> cap of N =
    prop inRange: min < Self && Self < max    // strict inequality

// Return a cap generator from a function
fn rangeFor(unit: String) -> fn(f64, f64) -> cap of f64 =
    match unit:
        "celsius" => between
        "kelvin" => (lo, hi) ->
            prop inRange: lo <= Self && Self <= hi
            prop absolute: Self >= 0.0
```

#### Symbolic value params

Value arguments can be literals or in-scope bindings — the compiler verifies props symbolically:

```
fn clamp(value: N is Numeric + Ord, lo: N, hi: N) -> N is between(lo, hi) =
    if value < lo: lo
    else if value > hi: hi
    else: value
```

#### `cap Name(params)` as sugar

A named capability with value params is sugar for a cap-generating function:

```
// These are equivalent:
cap Between(min: N is Ord, max: N) of N:
    prop inRange: min <= Self && Self <= max

fn between(min: N is Ord, max: N) -> cap of N =
    prop inRange: min <= Self && Self <= max
```

The `cap` form is convenient for simple, named refinements. The `fn` form is the general mechanism — use it when you need first-class cap generation, composition, or conditional logic.

#### Refinement capabilities are zero-cost

Whether defined via `cap(...)` or `fn -> cap`, refinement capabilities are **Marker** caps (no methods, zero-sized, compile-time only). They add zero constraint on representation — only prop obligations. A value `N is between(0, 100)` is still just an `N`.

### Quantities — how values interact with types and runtime

Fixed uses Quantitative Type Theory (QTT) to track how each binding is used. This is what makes dependent types (`fn -> cap`) sound with Perceus reference counting.

| Quantity | Meaning | Fixed example |
|----------|---------|---------------|
| **0** | Erased — type-level only, no runtime code | `fn -> cap` value params, `prop` expressions, inferred-phantom type params |
| **1** | Used exactly once | Single-shot `resume`, operations of a linear effect |
| **ω** | Used any number of times | General values, captured closures |

Quantities are **inferred by the compiler**, not written by the programmer. Reference counting and in-place reuse are not decided by quantity: they follow **demands** (`0`, `read`, `own`, `exclusive`) and **exclusive ownership**, also inferred (`spec/quantities.md` §4.1). A binding used once does not make its value exclusively owned. The programmer sees capabilities; the compiler decides which values need RC, which can be reused in-place, and which are erased entirely.

The soundness argument: in `fn clamp(value: N, lo: N, hi: N) -> N is between(lo, hi)`, the return type mentions `lo` and `hi`. QTT assigns quantity 0 to these type-level uses — they generate no runtime code. The runtime uses of `lo` and `hi` in the function body are quantity ω (they may be used multiple times in comparisons). Total quantity per binding: ω. Perceus manages their lifetime normally. The type-level mentions are erased.

This replaces three ad-hoc mechanisms with two inferred analyses, quantities and demands:

| Before QTT | After QTT |
|---|---|
| "Compile-time only" refinements (ad hoc) | Quantity 0 — erased by the type system |
| Perceus reuse analysis heuristic for FBIP | Exclusive ownership (demands) — reuse is unconditional for an exclusively owned value and follows a run-time `rc == 1` test for a shared `Heap` value (`spec/memory_management.md` Rule PR6.3) |
| Perceus RC for everything else | Demands — `read` borrows, `own` takes a counted reference (`spec/quantities.md` §4.1) |

### `Part` — the implicit element type

Inside cap definitions, `Part` refers to the element type without needing an explicit type parameter:

```
cap Folding:
    fn fold<R>(init: R, f: (R, Part) -> R) -> R
```

This eliminates `<A>` boilerplate. When a cap has multiple type parameters, they're named explicitly in `of`.

### `Self.fn` vs `fn` — static vs instance methods

```
cap Sequencing:
    fn head -> is Optional                  // instance method (called on a value)
    fn tail -> Self                          // instance method
    Self.fn cons(h: Part, t: Self) -> Self   // static/constructor (called on the type)

cap Empty:
    Self.fn empty -> Self                    // static constructor
```

Static methods are called via dot on the type alias: `C.empty`, `C.cons(x, acc)`.

`Self.fn` constructors are **abstract** — they define what must exist, not how it's built. Concrete data types provide implementations via `satisfies` declarations that map data constructors to `Self.fn` names using `as`.

### `extends` for supertrait relationships

```
cap Optional extends Functor + Folding:
    fn isDefined -> Boolean
    fn orElse(e: Part) -> Part
```

### `satisfies` for type-provides-cap implementation

`extends` is cap-to-cap (structural inheritance). `satisfies` is type-provides-cap (implementation provision):

| Keyword | Relationship | Example |
|---------|-------------|---------|
| `extends` | Cap inherits requirements from another cap | `cap Monad extends Functor` |
| `satisfies` | Type provides concrete implementation of a cap | `Maybe satisfies Optional: ...` |

A `satisfies` declaration maps data constructors to capability constructor *requirements* by name using `as` (the cap declares the requirement; the data type provides the implementation):

```
data Maybe of A:
    Just(value: A)
    Nothing

Maybe satisfies Optional:
    Just as some
    Nothing as none
```

Satisfactions are modular — brought into scope via `use`:

```
use shop.box.Box satisfies Optional

fn wrap(x: A) -> is Optional of A =
    Optional.some(x)   // a choice point: Box is a candidate through the imported satisfaction
```

`use Type satisfies Cap` does two things in one line: it imports the type *and* brings the satisfaction mapping into scope.

#### Several satisfactions and `using`

There is no coherence requirement. A package may declare a satisfaction for a type and a capability it does not own, and a link unit may hold several satisfactions of one (type, capability) pair. A satisfaction may be named, and `using` chooses one:

```
Money satisfies Ord named ByAmount:
    fn compare(other: Self) -> is Ordering = self.amount.compare(other.amount)

sort(prices) using ByAmount          // at the call site
fn byAmount(s: Set of Money using ByAmount) -> u64   // in a type
```

Resolution is innermost first: `using` at the call site, in a type, at file or package scope; the one satisfaction in scope by `use`. Otherwise the site is an error that suggests a named `using`, because two satisfactions of one pair differ in results, not in speed (`spec/type_system.md` Rule 8.4). For a bound on a data type's own parameter the satisfaction is part of the type (`Tree of Money[Ord = ByAmount]`, Rule 8.4.a), so a set built under one order cannot be searched under another. (`named` is a provisional keyword.)

Constructor requirements that the satisfying type does not implement are marked `impossible`. Calls to them through the cap lower to a runtime panic (see spec/type_system.md §8.2 for the precise rule):

```
u64 satisfies Optional of Self:
    Self as some
    impossible as none   // u64 is always present; `Optional.none` on a u64 panics at runtime
```

### `Self of B` for higher-kinded returns

```
cap Functor:
    fn map<B>(f: Part -> B) -> Self of B
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
fn binarySearch(sorted: C is ArrayLike of (A is Ordered), target: A) -> ...
```

#### Parameterized type aliases

Type aliases can take value parameters, making them functions from values to capability sets:

```
type PortRange(min: u16, max: u16) = u16 + between(min, max)
type BoundedString(max: u64) = String + maxLength(max)
type Clamped(lo: N is Numeric + Ord, hi: N) = N + between(lo, hi)
```

At use sites, they are called like cap-generating functions:

```
fn parsePort(s: String) -> N is PortRange(1024, 49151) with Fail of String: ...
fn readName() -> S is BoundedString(255) with Console: ...
```

This is consistent with the `fn -> cap` story: `type Name(params) = expr` is sugar for a function that returns a capability set. The compiler expands it at every use site, just like plain type aliases.

### Data declarations — planning structure

`data` is where structure gets planned. When you need a specific **closed set of variants** — enumerations, tagged unions, domain-specific value types — the `data` keyword lets you express the shape. The `data` keyword defines a general algebraic data type (GADT):

```
// Simple enumeration — all unit variants
data Direction:
    North
    South
    East
    West

// Mixed variants — some carry data, some don't.
// Capability constraints work inside data definitions.
data Color:
    Red
    Green
    Blue
    RGB(red: N is Numeric, green: N, blue: N)

// Recursive data — the compiler handles allocation
data Expr:
    Lit(value: f64)
    Add(left: Expr, right: Expr)
    Mul(left: Expr, right: Expr)
    Neg(inner: Expr)

// Phantom-typed data — Tag is inferred phantom because it doesn't appear
// in any field (see spec/type_system.md §5.9).
data Tagged of (Tag, Value):
    Tagged(value: Value)

// Single-constructor sugar — when there's only one variant,
// the indented body and repeated name are unnecessary:
data Config(host: String, port: u16, debug: bool)
// Equivalent to:
//     data Config:
//         Config(host: String, port: u16, debug: bool)
// Construction: Config("localhost", 8080, false)
// Pattern match: Config(h, p, d) => ...
```

Key properties:
- **The compiler still owns the representation** — `data` declares the shape (variants and fields), not the layout
- **Data types automatically satisfy capabilities** their shape supports (e.g., a multi-variant data gets `Folding` for free, a data with fields gets accessors)
- **Explicit `satisfies` declarations** map data constructors to capability constructor *requirements* by name using `as`, bridging the gap between abstract `Self.fn` requirements and concrete data variants
- **Pattern matching** uses dot syntax: `Expr.Lit(v)`, `Color.RGB(r, g, b)`, `Direction.North`
- **`of` works on data** just like on caps: `data Tagged of (Tag, Value)` introduces type parameters (Tag is inferred phantom; see type_system.md §5.9)
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
| **Destruction** (case-split) | `match json: Json.Null => ...` | `opt.fold((v) -> ..., () -> ...)` |
| **Construction** | `Json.Null`, `List.Cons(x, xs)` | `Optional.some(x)`, `Result.err(e)` |
| **Constructor casing** | Capitalized: `Json.Null`, `List.Cons` | lowercase: `Optional.some`, `Expr.lit` |

Rules:
1. **`match` cases may only use data-qualified patterns** — `Json.Null`, `List.Cons(h, t)`, `Direction.North`, `Tuple(a, b)`, etc.
2. **Capability destruction uses `fold`** — `opt.fold(onSome, onNone)`, `expr.fold(onLit, onVar, ...)`, `result.fold(onOk, onErr)`, `ord.fold(onLt, onEq, onGt)`
3. **Construction through a cap's constructor *requirement* is valid** — `Optional.some(x)` is resolved via `satisfies` to a satisfying type's actual constructor (e.g., `Maybe.Just(x)`) at compile time
4. **Data constructors are Capitalized** — `List.Cons`, `List.Nil`, `Tuple`, `Json.Null`
5. **Cap constructors are lowercase** — `Optional.some(x)`, `Expr.lit(v)`, `Result.ok(v)`, `Ordering.lt`, `Pair.pair(a, b)` (these are `Self.fn` names)

**Stdlib classification** (see `spec/stdlib.md` for the normative version):

| Stdlib name | Kind | Notes |
|---|---|---|
| `List`, `Json`, `Tuple`, `Nat` | **data** | Matchable; `List.Cons`/`List.Nil`, `Json.Null`/…, `Tuple(a, b)`, `Nat.Zero`/`Nat.Succ` |
| `Optional`, `Result`, `Pair`, `Ordering`, `Boolean`, `Empty` | **cap** | Destructure via `fold`; cap constructors lowercase (`Optional.some`, `Pair.pair`, `Ordering.lt`, …) |

`Pair` is a cap (abstract two-projection container); `Tuple` is the canonical concrete `data` type that satisfies it. `Ordering` is a cap; its canonical satisfier is the prelude's `data Order: Less | Equal | Greater` (`spec/stdlib.md` §3.6).

### Everything is an expression

Fixed has **no statements**. Every construct — `let`, `if`, `match`, `handle`, `use` — is an expression that produces a value. There are no semicolons. Block expressions return the value of their last sub-expression.

```
// `let` introduces a binding, the block returns the last expression
let x = 5
let y = x + 1
y * 2              // this block evaluates to 12

// `if` is an expression
let absN = if n < 0: 0 - n else: n

// `match` is an expression
let name = match direction:
    Direction.North => "North"
    Direction.South => "South"
```

### Functions are total — recursion lives in data

Functions in Fixed are **total** unless their row says otherwise (`spec/type_system.md` Rule 7.4.h). Recursion is driven by structural operations on recursive data — primarily `fold` (catamorphism), `para` (paramorphism) and `unfold` (anamorphism). A function may also call itself when every recursive call passes a strict sub-value of one parameter (structural recursion, still total); any other recursion needs the effect `Diverge` in the function's row, and without it is `T027`, whose diagnostic shows the rewrite with `fold`, `para`, `unfold`, a `match` or `with Diverge`. A default body of a capability may never reach itself through `Self` (`T028`, Rule 7.4.j).

This is a deliberate design choice:
- **Totality** — fold over a finite structure always terminates; a function without `Diverge` returns, apart from two known gaps: data that holds a function of itself (OQ-T5, accepted for now) and an `unfold` until Rule 7.4.i is built
- Makes recursion **explicit and structural** — you can see the recursion scheme being used
- Enables **fusion** — the compiler can fuse unfold-then-fold (hylomorphism) to eliminate intermediate structures
- Aligns with the capability philosophy — recursion follows the shape of data unless a row says `Diverge`

```
// T027 — `n - 1` is not a sub-value of `n`:
// fn factorial(n: u64) -> u64 = if n == 0: 1 else: n * factorial(n - 1)

// Accepted: structural recursion on a `Nat` (the arithmetic effect is specified, not built: `spec/effects.md` §3.6)
fn factorial(n: Nat, k: u64) -> u64 with Overflow =
    match n:
        Nat.Zero => 1
        Nat.Succ(m) => k * factorial(m, k - 1)

// Accepted: general recursion, declared
fn collatz(n: u64) -> u64 with Diverge + Overflow =
    if n <= 1: 0 else: 1 + collatz(if n % 2 == 0: n / 2 else: 3 * n + 1)
```

### Additional syntax

| Feature | Syntax | Replaces |
|---|---|---|
| Arrow function types | `Part -> B`, `(R, Part) -> R` | `fn(Part) -> B`, `fn(R, Part) -> R` |
| Arrow lambdas | `(x, y) -> x + y` | Rust-style `\|x, y\| x + y` |
| Parenthesless zero-arg methods | `fn head -> is Optional` | `fn head() -> is Optional` |
| Multi-line lambdas | `.map((x) ->` + indented body | Removed brace block lambdas in v0.3 |
| Dot static calls | `C.empty`, `C.cons(x, acc)` | `C.empty()`, `C.cons(x, acc)` |
| Type aliases | `type ArrayLike = Seq + RA + Sized + Empty` | Repeating capability bundles everywhere |
| Parameterized type aliases | `type PortRange(min, max) = u16 + between(min, max)` | Repeating parameterized refinements |
| Data declarations | `data Color:` + indented variants `Red`, `Green`, `Blue`, `RGB(...)` | No prior equivalent (escape hatch) |
| Single-constructor data sugar | `data Config(host: String, port: u16)` | `data Config:` + `Config(host: String, port: u16)` |
| Satisfaction decl | `Maybe satisfies Optional:` + `Just as some`, `Nothing as none` | Haskell's `instance`, Rust's `impl Trait for Type` |
| Impossible mapping | `impossible as none` | Partial satisfaction — requirement with no implementation; calls panic at runtime per type_system.md §8.2 |
| Satisfaction import | `use shop.box.Box satisfies Optional` | Brings satisfaction mapping into scope |
| No semicolons | Newlines separate expressions | `;` statement terminators |
| Cap-generating functions | `fn between(min, max) -> cap of N:` + indented `prop ...` | Separate type-level and value-level abstractions |
| Cap sugar | `cap Between(min, max) of N:` + indented `prop ...` | `fn between(min, max) -> cap of N:` + indented prop (equivalent) |

### Visibility

Declarations are private to their package by default. `pub` makes a `fn`, `data`, `cap`, `effect`, type alias or `satisfies` declaration public (`spec/type_system.md` Rule 8.6). There is no opaque-type feature: `pub data` exposes its variants, and a package hides a representation by making the capability public and keeping the `data` private, so that its values leave the package as a choice point (`-> is C`).

### Diagnostics: `inspect` and `trace`

```
inspect(x)                          // any value, any type, no bound; returns x
trace("balance = {b}, acct = {a}")
```

`inspect` and `trace` write to the diagnostic channel; the text never becomes a `String` the program holds. They are not effects and appear in no row. A value of unbounded type may be interpolated only inside a diagnostic sink. `show`, `==` and `clone` on a value of an unbounded type parameter are type errors: write `T is Show`, `T is Eq`, `T is Clone`. A release build strips `inspect` and `trace` unless `--keep-diagnostics` is given (`spec/diagnostics.md`).

### `prop` — invariants specified in place

Properties from property-based testing are built into the language as a form of **lightweight theorem proving**. The `prop` keyword declares invariants directly inside capability or data definitions. The compiler checks them — statically where possible, via property-based testing otherwise.

```
cap Sorted extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)

cap Stack extends Sequencing + Sized:
    prop pushIncrements: forall (s: Self, x: Part) ->
        s.push(x).size == s.size + 1

    prop popDecrements: forall (s: Self) ->
        s.size > 0 implies s.pop().size == s.size - 1
```

Key properties of `prop`:
- **Lives where the capability is defined** — not in a separate test file. Properties are part of the interface contract.
- **Machine-checked documentation** — properties serve as documentation that the compiler verifies.
- **Compiler optimization hints** — a `prop sorted` on a collection lets the compiler assume sortedness and choose binary search over linear scan. Only `proved` and `assumed` props justify such a decision; a `tested` one never does (`spec/properties.md` Rule P7.8).
- **Dependent verification** — prop bodies are excluded from the compiled artifact. Combined with `fn -> cap`, they form Fixed's dependent type system.
- **Fallback to testing** — the static prover runs at package compile; a prop it leaves open is tested by property-based testing in a separate test build, which `fixed verify` builds and runs.

Properties can also appear on `data` types:

```
data Nat:
    Zero
    Succ(pred: Nat)

    prop nonNegative: fold(() -> true, (inner) -> inner)
```

And on functions, as postconditions:

```
fn sort(collection: C is Sequencing) -> C is Sorted =
    prop resultSameSize: result.size == collection.size
    ...
```

---

## Agent-Friendly CLI and Compiler Output

The compiler is designed to produce clear, structured output for **humans** — which turns out to be exactly what AI harnesses need too. No special "machine-readable" mode; just good defaults.

### The CLI

Everything starts from the CLI. The compiler works in two stages with a separate test build (`spec/compilation_model.md` §8): a **package compile**, done once per package, produces code that works for every choice of representation; the **application link** makes the choices that depend on how the whole program uses a type, from usage summaries, and binds them; the **test build** runs property-based testing and measures.

| Command | What it does | State |
|---|---|---|
| `fixed <file>...` | Checks each file and prints its diagnostics | built |
| `fixed compile` | Package compile to LLVM IR: typer, static prover, quantity inference, polymorphic Core, demand and memory inference, cost checker, baseline code and clones, interface tier, body tier, usage summaries | built for one package, without the static prover, the cost checker, the tiers and the summaries |
| `fixed link` | Application link: selects the remaining choice points from the summaries, writes the **lock file**, binds the choice points' symbols, generates accessors and the type table; a release build also builds clones for the chosen types | planned (Phase 4.2) |
| `fixed release` | Builds the release executable: application link with link-time clones, `inspect` and `trace` stripped unless `--keep-diagnostics` (`spec/compilation_model.md` §8). `fixed ship` builds the development executable | planned |
| `fixed ship` | Builds a native executable (compile, link, `clang`) | built; there is no link step yet, the IR goes straight to `clang` |
| `fixed verify` | Builds and runs the test build: property-based testing of the props the static prover left open, and measurement into the **profile file** (`spec/properties.md` Rules P7.6, P7.9) | planned |
| `fixed verify + ship` | Verify props, then build the executable | planned |

The application link reads and writes two files kept with the application (`spec/compilation_model.md` Rules CM8.5, CM8.6; their encoding is OQ-CM2):

- the **lock file**: the choice made at each choice point (no satisfaction site: each is resolved at package compile, `spec/type_system.md` Rule 8.4.b). It is checked in; a build from it is reproducible, the application may pin a choice in it, and a changed choice shows as a diff;
- the **profile file**, one per target: what the test build or a production run measured. Continuous integration builds from the lock file and does not re-measure.

Flags named in the specs:

| Flag | On | Effect | State |
|---|---|---|---|
| `--no-specialise` | `compile`, `ship` | Only mandatory specialisations, no clones by score; the reference behaviour clones are compared against (Rule CM5.7) | built |
| `--explain-memory [--format json]` | `compile` | Memory report, with the clone report of Rule CM5.8 | built |
| `--explain-selection` | `link`, and `compile` / `ship` of an application | Per choice point: candidates, scores, the disambiguation rule that decided, measured or static (`spec/reports.md` Rule RP5.4) | planned |
| `--explain-cardinality` | `compile` | State and implementation spaces (`spec/reports.md` §4) | planned |
| `--coverage` | `verify` | Coverage report (`spec/reports.md`) | planned |
| `--keep-diagnostics` | `fixed release` | Keeps `inspect` and `trace` (`spec/diagnostics.md` Rule DG3.5) | planned |
| `--no-measure` | `verify` | Runs the props without recording measurements (`spec/properties.md` Rule P7.9) | planned |
| `--no-pbt`, `--strict-pbt`, `--require-proved`, `--prop-…` | `verify` (and `--require-proved` on `compile`, `ship`) | `spec/properties.md` Rule P7.6 | planned |

The compiler also takes `--budget <n>`, which sets the clone budget of Rule CM5.3 instead of the reference value, for measurement, and `--no-ownership-clones`, which builds no ownership clones but those a `fip` body needs; the specs name neither. A release build is requested by the command `fixed release`; there is no `--release` flag (P6, fourth round).

The `+` operator composes commands. Each stage is incremental — if source hasn't changed since the last run, that stage is skipped. `fixed verify + ship` is the standard development loop: prove your properties, then produce a binary.

### Output design principles

- **Structured errors**: every diagnostic has a code (e.g., `E042`), a location, and a human-readable explanation. Copy-pasteable suggestions where applicable (see ambiguity resolution below).
- **No noise**: successful compilation produces no output. Only errors and warnings print.
- **Incremental feedback**: long-running `verify` reports progress per `prop` — which properties passed, which are being tested, which failed.
- **Exit codes**: `0` for no failure and one number per error class, for every command, in the one table of `spec/reports.md` Rule RP5.5 (P6, fourth round; the class list is confirmed by the owner, with `99` for "not supported yet"). Not built: `Main` today implements only `1` = compile error, `2` = usage error, `3` = build failure (Phase 4.2, item 18).
- **JSON output**: `--format json` flag on any command for structured output. Same information, machine-parseable. Useful for editor integrations, AI agents, and CI pipelines.
- **Incremental and partial-result-tolerant by default**: every phase consumes possibly-incomplete input and emits as much output as it can. The parser produces an AST with explicit gap nodes when a production fails, instead of halting; the typer types as much of the AST as it can, leaving holes for unresolvable references; capability closure proceeds with whatever satisfaction declarations resolved. This is the precondition for LSP / IDE integration and for incremental compilation, and it makes the compiler usable mid-edit rather than only at quiescence. See `docs/plans/phase-2.1-incremental-parser.md` for the parser's instantiation of this contract.

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

1. The compiler collects all capability bounds on a value across its entire lifetime. A value whose type is a named type parameter gets its representation from the user of the declaration and is used through its witness (`spec/compilation_model.md` Rule CM3.4). A value whose type is an anonymous bound (`-> is C`, a field `xs: is Sequencing`) is a **choice point**, with one choice for the whole program: made at package compile when its values never leave the package, and otherwise at the application link, from the usage summaries of every package, the lock file and the profile file (Rules CM8.1, CM8.3, CM8.4)
2. It builds a **capability set** — the intersection of all representations that satisfy those bounds
3. It selects the optimal representation from that set, considering performance characteristics

### Capability → representation mapping

| Capabilities requested | Compiler likely selects |
|---|---|
| `Folding` only | Anything — linked list, array, tree, stream |
| `Sequencing + Folding` | Linked list or array (both support cons/fold) |
| `RandomAccess + Sized` | Contiguous array (linked list cannot satisfy RandomAccess efficiently) |
| `Sequencing + RandomAccess + Sized + Functor + Filtering` | Resizable contiguous array (all constraints force this) |
| `Folding` with a profile showing sequential-only access | Contiguous array (measurements narrow further, through selection at link, never by cloning code; `spec/compilation_model.md` Rules CM3.4, CM8.4) |

### The key insight

The **more capabilities** you request, the **fewer representations** qualify. This is a feature: it means the programmer controls performance characteristics *indirectly* by expressing what operations they need, and the compiler handles the rest. A function that only needs `Folding` is maximally reusable; one that needs `RandomAccess + Sized` gets guaranteed O(1) indexing.

---

## Phase status

| Phase | State |
|---|---|
| 0 Example programs | done |
| 1 Specification | drafts under `spec/`, amended by decisions P1–P6 (`docs/decisions/`) |
| 2, 2.1 Parser | done |
| 3 Type checker | base typer (M1–M5), capability closure (M6), effect inference (M7) and quantities, demands and lifetimes (M10) done; property verifier (M9) and representation selector (M11) not started (`docs/plans/phase-3-typer.md`) |
| 4.0 Naive backend slice | done |
| 4.1 Polymorphic baseline and specialiser | 4.1.M0–M8 done, 4.1.M9 (cleanup) open; parts of it superseded by P6 (`docs/plans/phase-4.1-baseline-specialiser.md`) |
| 4.2 Resolution round P6 | in progress: items 10, 12, 17, 20, 25–27 done, 5, 13, 16, 23, 24 in part, the rest not started |
| 4 Code generation | partial: LLVM IR emission and Perceus-style counting exist; fusion, region lowering and evidence passing do not |
| 5 Standard library, 6 PGO | not started |

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
| 9 | `examples/09_interpreter.fixed` | `cap Expr` (8 constructor requirements), fold-based eval, `satisfies` (AstNode), effects for eval errors |
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
| `spec/memory_management.md` | Lifetime classes and regions, class witnesses, reference counting insertion, reuse analysis (FBIP), drop specialization, borrowing, `fip` contract (formerly `spec/perceus.md`) |
| `spec/properties.md` | `prop` semantics, static verification vs property-based testing fallback, `forall`/`implies`, composability with `extends` |
| `spec/quantities.md` | QTT quantity inference (0/1/ω), interaction with Perceus RC, erasure rules for 0-quantity bindings, linearity checking for 1-quantity bindings, soundness argument for dependent types + RC |

### Key decisions to formalize

- **`of` and `Part` semantics**: How `Part` is resolved in multi-parameter capabilities. How `of` interacts with named aliases. How `Self of B` works for HKT returns.
- **`Self.fn` vs `fn`**: Static vs instance method distinction. Rules for when each is allowed. How static methods are dispatched via dot syntax on type aliases.
- **`extends` inheritance**: How capability extension works. Multiple inheritance via `+`. Diamond problem resolution.
- **Capability-driven representation selection**: The algorithm by which the compiler narrows representation choices from capability sets. How conflicting heuristics are resolved. PGO integration points.
- **Capability classification**: compiler-inferred categories (sum, product, recursive, capability-only, marker) based on signature shape
- **No statements**: everything is an expression; no semicolons; blocks return their last expression
- **Functions are total**: recursion is via fold/para/unfold on recursive data, structural recursion on a strict sub-value, or recursion under the effect `Diverge` (`spec/type_system.md` Rule 7.4.h)
- **No explicit layouts**: the programmer never writes references; the compiler handles all borrowing/moving/cloning via Perceus
- **`prop` semantics**: how properties are checked (static analysis vs property-based testing), what expressions are allowed inside `prop`, how `forall` quantification works, how properties on capabilities compose with `extends`
- **Dependent types via `fn -> cap`**: how cap-generating functions are type-checked, how symbolic value params are tracked through prop verification, equivalence between `cap Name(params)` sugar and `fn name(params) -> cap`, how first-class cap generators compose and are passed as arguments
- **QTT quantity inference**: how the compiler determines 0/1/ω for each binding. Type-level positions (cap params, props, inferred-phantom type params) force 0. Single-use patterns yield 1. Everything else is ω. Interaction with Perceus: 0 → erased; reference counting and reuse follow demands and exclusive ownership, not quantity 1 (`spec/quantities.md` §4.1)
- **Erasure soundness**: 0-quantity bindings generate no runtime code. The compiler must verify they are never used at runtime — only in types, props, and inferred-phantom positions. This is what makes `fn -> cap` sound with RC.
- **Exclusivity and FBIP**: an exclusively owned value is reused in place unconditionally, a shared `Heap` value after a run-time `rc == 1` test (`spec/memory_management.md` Rule PR6.3); linearity obligations come only from `fip`, `resume` and linear effects
- **Effect handler linearity**: `resume` is quantity 1 (single-shot). Multi-shot handlers (if supported) would need quantity ω
- **No explicit `self`**: instance methods don't declare a self parameter; the compiler infers it
- **Coherence rules**: there is no coherence requirement. Satisfaction declarations (`Type satisfies Cap`) are modular — they must be brought into scope via `use Type satisfies Cap` to be visible, and are visible only where a `use` imports them or in the declaring package. A link unit may hold several satisfactions of one (data type, capability) pair; `using` chooses (`spec/type_system.md` Rule 8.4, `spec/compilation_model.md` Rule CM6.6).
- **Recursive capability detection**: Self in constructor parameter position
- **`satisfies` semantics**: How `Type satisfies Cap:` + indented `Variant as name` declarations map data constructors to capability constructor *requirements* (caps declare requirements; types provide constructors — see spec/type_system.md §8). How `impossible as name` records that the satisfying type provides no implementation, lowering to a runtime panic per Rule 8.2. How `use Type satisfies Cap` brings satisfaction mappings into scope.
- **Constructor resolution**: How the compiler selects among in-scope `satisfies` declarations when a `Self.fn` constructor is called on a capability. Context-based resolution via return types and assignment targets. Ambiguity errors with copy-pasteable suggestions.

---

## Phase 2: Parser + AST

Scala 3 implementation of the front-end. Structural model: `dotc` at `compiler/src/dotty/tools/dotc/`. See `docs/references/scala3-compiler-reference.md` §3 for parser patterns and §3.5 for the single-`Tree[T <: Untyped]` hierarchy we adopt directly.

### Project layout (sbt)

```
build.sbt
project/
compiler/src/main/scala/fixed/
  ast/                 — Trees, untpd, tpd, Desugar
  core/                — Names, Symbols, Phases, Contexts, Types, Denotations
  parsing/             — Tokens, Scanners, Parsers, ParserPhase
  typer/               — Namer, Typer, ProtoTypes, Inferencing  (Phase 3)
  caps/                — Capability closure, satisfies resolution, prop verifier  (Phase 3)
  qtt/                 — Quantity inference  (Phase 3)
  repr/                — Representation selection  (Phase 3 / 4)
  transform/           — MiniPhase + concrete phases (match→fold, etc.)  (Phase 4)
  perceus/             — Dup/drop/reuse insertion  (Phase 4)
  effects/             — Effect inference, evidence passing  (Phase 3 / 4)
  backend/             — LLVM IR emission  (Phase 4)
  Compiler.scala       — phase plan: List[List[Phase]]
  Driver.scala         — argv parsing, settings
  Main.scala           — entry point
  Run.scala            — one execution of the phase plan
  CompilationUnit.scala
compiler/src/test/scala/fixed/
library/src/main/fixed/   — stdlib in .fixed (Phase 5)
examples/                  — already exists
spec/                      — Phase 1 deliverables
```

### Deliverables

| Component | Description |
|---|---|
| **Tokens & Scanner** (`parsing/Tokens.scala`, `parsing/Scanners.scala`) | Token enum + lexer. Keywords: `cap`, `fn`, `is`, `of`, `extends`, `satisfies`, `impossible`, `as`, `effect`, `match`, `handle`, `with`, `let`, `if`, `else`, `do`, `use`, `mod`, `pub`, `Self`, `data`, `type`, `prop`, `forall`, `implies` (`phantom` removed in v0.4.1 — inferred from usage) |
| **Parser** (`parsing/Parsers.scala`) | Hand-written recursive descent producing untyped trees. Mirrors `dotc`'s `Location`/`ParamOwner`/`ParseKind` enums for context tracking |
| **ParserPhase** (`parsing/ParserPhase.scala`) | Wraps the parser as a `Phase`; per-unit `runOn` populates `unit.untpdTree` |
| **AST** (`ast/Trees.scala`, `ast/untpd.scala`, `ast/tpd.scala`) | Single `Tree[T <: Untyped]` hierarchy split into untpd (parser-output) and tpd (post-typer) |
| **Phase plan** (`Compiler.scala`) | `phases: List[List[Phase]]` with `frontendPhases` initially containing only `Parser` and a stub `TyperPhase` |

### AST node types needed

- **Items**: `CapDef`, `EffectDef`, `DataDef`, `TypeAlias` (optionally with `ValueParams`), `FnDef`, `PropDef`, `UseDecl`, `ModDecl`, `SatisfactionDecl`
- **Satisfaction**: `SatisfactionDecl` (`Type satisfies Cap:` followed by indented mappings), `ConstructorMapping` (`Variant as capConstructor`), `ImpossibleMapping` (`impossible as capConstructor`)
- **Cap members**: `InstanceMethod`, `StaticMethod` (the `Self.fn` distinction)
- **Capability refs**: `IsCap` (anonymous), `NamedAlias` (`N is Numeric`), `OfApp` (`Folding of i64`), `SelfOf` (`Self of B`), `CapCall` (`between(10, 30)` — cap-generating function applied to args), `UseSatisfaction` (`use Type satisfies Cap` — satisfaction import)
- **Cap generation**: `CapReturnType` (`cap of N` as return type), `ValueParams` (definition-site `(min: N is Ord, max: N)`), `ValueArgs` (use-site `(10, 30)`)
- **Expressions**: `Match`, `If`, `Let`, `FnCall`, `MethodCall`, `StaticCall` (`C.empty`), `Closure`, `BlockLambda`, `Do`, `Handle`, `Block`, `Literal`, `BinaryOp`, `UnaryOp`, `FieldAccess`, `ListLiteral`, `Pipe`, `Forall`, `Implies`
- **Data**: `DataDef` (multi-variant with `:` + indented variants, or single-constructor sugar with paren-form fields), `DataVariant` (unit variant, tuple variant with named fields), `PhantomParam`, `OfParams`
- **Patterns**: `DataVariantPat` (`Expr.Lit(v)`, `List.Cons(h, t)`) — only data-qualified patterns allowed in `match`; no `CapConstructorPat` (capabilities are destructured via `fold`, not `match`), `WildcardPat`, `BindingPat`, `LiteralPat`, `DestructurePat`, `GuardedPat`
- **Types**: `ArrowType` (`A -> B`), `TupleArrowType` (`(A, B) -> C`), `CapBound` (`is Cap + Cap`), `OfType` (`Cap of X`), `CapType` (`cap of N` — capability as return type)

### Milestone

All 11 example programs parse successfully into AST.

---

## Phase 2.1: Incremental & Resilient Parsing

Phase 2 produces an AST on the happy path. Phase 2.1 makes that parser **functional, error-tolerant, and trivia-preserving** so the same front-end can serve an LSP/IDE without a separate "tooling parser". Three small layers:

- **Functional API** — `Parser.parse(SourceFile): ParseResult` returns tree + diagnostics + trivia. No caller-supplied `Reporter`. Internals stay imperative for performance; the seam is the public API.
- **Error recovery with gap nodes** — synchronisation points at decl/block/expression/type boundaries. New `Trees.Error` and `Trees.Missing` nodes replace today's `Ident("<error>")` placeholder. A syntax error in one top-level decl no longer destroys the rest of the file.
- **Trivia retention** — comments and blank lines preserved in a side table keyed by token offset. The AST is unchanged; printers, formatters, and LSP hover can recover original-source detail.

Layer 2 (lossless concrete syntax tree, on-edit incremental reparse) is deferred to **Phase 2.2** and gated on a concrete LSP consumer driving requirements.

See `docs/plans/phase-2.1-incremental-parser.md` for the full plan, milestones, and recovery anchor table.

### Milestone

Every `examples/*.fixed` corrupted with one syntactic error per top-level decl produces a `CompilationUnit` whose `items.length` matches the un-corrupted parse, with one diagnostic per injected error and no token-skip cascades.

---

## Phase 3: Type Checker

### Deliverables

| Component | Description |
|---|---|
| **Namer** (`typer/Namer.scala`) | Index pass: enter symbols for every top-level definition with lazy completers (mirrors `dotc/typer/Namer.scala`). Enables mutual recursion across `cap` / `data` / `fn`. |
| **Typer** (`typer/Typer.scala`, `typer/ProtoTypes.scala`, `typer/Inferencing.scala`) | Bidirectional base typing via prototypes — `is` bounds at parameter sites, `fn -> cap` symbolic args, and `satisfies` resolution all flow through `pt: Type` |
| **Capability classifier** (`caps/Classifier.scala`) | Analyze capability signatures → sum/product/recursive/capability-only/marker |
| **Type alias expander** (`typer/TypeAliasExpander.scala`) | Expand `type` aliases to their underlying capability sets |
| **Data type analyzer** (`typer/DataAnalyzer.scala`) | Analyze `data` declarations: variant shapes, auto-derive capabilities, GADT type parameter constraints. Validate `satisfies` declarations: verify constructor arity/type compatibility, check `impossible` soundness (no reachable code path invokes the marked constructor) |
| **Capability closure** (`caps/CapClosure.scala`) | Recheck phase: close capability sets under `extends`, resolve `satisfies` mappings in scope, verify coherence |
| **Effect inference** (`effects/EffectChecker.scala`) | Infer and propagate effect rows, verify all effects handled |
| **Exhaustiveness checker** (`typer/Exhaustiveness.scala`) | Verify `match` cases cover all constructors of the matched data type |
| **Property verifier** (`caps/PropVerifier.scala`) | Static verification of `prop` invariants; generate property-based tests for those that can't be proven statically. Resolves value params in refinement capability props (e.g., `Between(lo, hi)` binds `min`/`max` to `lo`/`hi` in prop expressions) |
| **Quantity checker** (`qtt/QuantityChecker.scala`) | Recheck phase: infer QTT quantities (0/1/ω) for all bindings. Verify erasure: 0-quantity bindings used only in type/prop/inferred-phantom positions. Verify linearity obligations (`fip`, `resume`, linear effects). Reference counting and reuse follow demands, inferred on Core, not quantities (`spec/quantities.md` §4.1) |
| **Representation selector** (`repr/RepresentationSelector.scala`) | Recheck phase: analyze capability sets → narrow to viable concrete representations |

Implementation note: capability closure, prop verification, quantity checking, and representation selection are layered as separate **Recheck phases** (the pattern from `dotc/transform/Recheck.scala` + `dotc/cc/CheckCaptures.scala`). Each re-traverses the typed tree and refines node types without re-running the base typer. See `docs/references/scala3-compiler-reference.md` §5.4.

### Capability classification rules

| Category | Detection rule | Representation |
|---|---|---|
| **Sum** | Multiple `Self.fn(...) -> Self` constructors + `fold` method | Tagged union |
| **Product** | Single `Self.fn(...) -> Self` constructor + accessor methods | Flat struct |
| **Recursive** | `Self` appears in constructor parameter types | Heap-allocated (optimizable) |
| **Capability-only** | No constructors returning `Self` (only instance methods) | Witness passing in the baseline version; direct code in a clone |
| **Marker** | No methods at all | Zero-sized, compile-time only |
| **Refinement** | No methods, has `prop` + value params | Zero-sized, compile-time only (subtype of Marker) |

### `Self.fn` constructors and the cap-to-data bridge

`Self.fn` declarations in capabilities define **constructor requirements** — abstract construction interfaces where `Self` is not yet a concrete type. Functions can summon values (`Optional.some(x)`, `C.empty`) so construction must be expressible at the capability level.

**`satisfies` declarations** connect abstract constructors to concrete data types. The mapping is by name using `as`:

```
data Maybe of A:
    Just(value: A)
    Nothing

Maybe satisfies Optional:
    Just as some
    Nothing as none
// fold is auto-derived from the two variants
```

Key design rules:
- **`extends` vs `satisfies`**: `extends` is cap-to-cap inheritance (`cap Monad extends Functor`). `satisfies` is type-provides-cap implementation (`Maybe satisfies Optional`).
- **Satisfaction declarations are separate** from data definitions — they can live in the same module or a different one. Third parties can write them.
- **Brought into scope via `use`**: `use shop.box.Box satisfies Optional` makes the satisfaction mapping available at the use site.
- **Multiple satisfactions possible**: different packages can map the same data to different caps, and to the same cap more than once; `using` chooses between satisfactions of one pair (`spec/type_system.md` Rule 8.4).
- **No orphan surprises**: a satisfaction is visible only where a `use` imports it or in its own package. Where several satisfactions of one pair are visible and nothing chooses, the site is an error when its package is compiled, in a library as in an application (Rule 8.4.b).

### Constructor resolution

When a `Self.fn` constructor is called on a capability (e.g., `Optional.some(x)`), the compiler resolves to a concrete data constructor using `satisfies` declarations in scope.

**Context-based resolution** — the compiler infers the backing type from context:

```
// Return type provides context:
fn wrap(x: A) -> is Optional of A =
    Optional.some(x)   // resolves via in-scope satisfaction

// A field or parameter type constrains (there is no `let x: T`, P6 M18):
fn answer() -> is Optional of u64 = Optional.some(42)
```

**Several satisfying types are not an ambiguity.** When the expected type is an anonymous bound (`-> is Optional of u64`, a field `y: is Optional of u64`), the call is a choice point and the satisfying types are its candidates, selected at package compile or at link (`spec/type_system.md` §8.5). **Two satisfactions of the same pair are**: the site is an error unless `using` chooses (Rule 8.4):

```
error: two satisfactions of (Money, Ord) are in scope, and nothing chooses between them
  --> report.fixed:12:5   (level 4: in scope by `use`)
   |   shop.money.ByAmount   (use at report.fixed:2)
   |   shop.money.ByDate     (use at report.fixed:3)
  help: write `using ByAmount` at the call, in the type, or at the top of the file
```

**Direct data construction always works** — no resolution needed:

```
let z = Maybe.Just(42)
```

### Partial satisfaction with `impossible`

Any type can satisfy a cap; constructor *requirements* the type does not implement are marked `impossible` (calls to them lower to runtime panics — see spec/type_system.md §8.2):

```
u64 satisfies Optional of Self:
    Self as some
    impossible as none
```

Meaning: `u64` is always present (`some` = identity); the `none` requirement has no implementation (`impossible`). Calls to `Optional.none` whose resolution targets `u64` lower to a runtime panic — see spec/type_system.md §8.2 (Rule 8.2) for the runtime-panic semantics. The compiler does not statically verify reachability; users who need that guarantee add a `prop` excluding the offending path (per spec/properties.md, Rule P9.2 — verified props discharge the panic).

```
data NonEmptyList of A (head: A, tail: is List of A)

NonEmptyList satisfies Sequencing:
    Cons as cons
    impossible as empty
```

### Capability-set representation narrowing

New compiler pass: given all capabilities a value must satisfy, determine which concrete representations are viable:

1. Collect all `is` bounds on a value across its entire usage scope
2. Collect all `satisfies` declarations in scope for each bound
3. For each candidate representation (data types with matching `satisfies` declarations, plus compiler-known built-in representations), check if it can satisfy all bounds
4. Rank remaining candidates by score: usage × cost, measured where a profile exists, from cost bounds otherwise
5. Select the best candidate; a tie never halts the build (lock file, then the capability's own package, then declaration order; `spec/type_system.md` Rule 6.5.5). Where the choice depends on other packages, steps 1–5 run at the application link (`spec/compilation_model.md` Rule CM8.4)

### Milestone

All 11 example programs type-check with correct capability classifications, representation selection, and effect tracking.

---

## Phase 4.0: Naive backend slice (before M10)

Runs after M7 and **before** M10. Fixed's memory model (`spec/memory_management.md` §21) and representation scoring (`spec/type_system.md` §6.5.4, provisional weights) are hypotheses until measured, and nothing can be measured without running code. This slice produces running code and a baseline as cheaply as possible, so that M10, M11 and Phase 4 proper are each judged by what they change in the numbers.

### Scope

Sub-plan: `docs/plans/phase-4.0-backend-slice.md`.

Programs compile to LLVM IR, link and run with correct output. The required set is examples `01` and `03` plus a small set of benchmark programs. Examples `01`–`06` all type-check with no diagnostics (`docs/Phase3Conformance.md`), but `02`, `04`, `05` and `06` could not run as written: each called a member that no rule defined yet (sub-plan §3). `04` and `05` joined the slice when their blockers closed and run since 4.0.M6. `06` and `02` are left out: `06` pending a decision on `Monad` members for sum data, `02` until M11.

The slice is deliberately naive, and does not conform to the spec's guarantees:

| Concern | Slice behaviour | Replaced by |
|---|---|---|
| Quantities | Every binding treated as ω | M10 |
| Placement | Every boxed value is `Heap` with a header | M10 lifetime classes, Phase 4 region lowering |
| Reference counting | `dup` at every additional use, `drop` at last use. No reuse, no borrowing | Phase 4 Perceus inserter |
| Representation | One fixed default per capability classification (the rule table in `phase-3-typer.md` §5.8). No scoring, no packed layouts | M11 |
| Folds | Closure call per step, intermediate structures allocated. Rules 7.4.b.1 and 7.4.b.2 not implemented | Phase 4 fold specializer |
| Effects | Runtime effects as direct calls, abortive operations by checked abort. No capturing handlers | Phase 4 evidence passer |
| Parallelism | None | Phase 4 |

### Deliverables

| Component | Description |
|---|---|
| Core IR, minimal monomorphizer and lowering | Only what the required set exercises. Abortive effects by checked abort; no evidence vectors yet |
| Naive RC inserter | The table above |
| **LLVM IR emitter** (`backend/EmitLLVM.scala`) and a small runtime (allocation, counts, console) | Kept and extended by Phase 4 |
| `ExampleRunSuite` | Golden standard output per example for a fixed standard input, plus a leak check (allocations equal frees) |
| Runtime benchmarks (`bench/runtime/`) | Five programs, each stressing one thing a later milestone claims to improve: binary trees, a counted loop, a list pipeline, a record-heavy workload, an abort-heavy loop (sub-plan §4.9) |
| Baseline record (`docs/baselines/`) | Per benchmark: wall time, peak resident memory, allocation count, RC operation count. The runtime keeps the two counters |

### Milestone

`ExampleRunSuite` passes for `01` and `03`, and the baseline record is committed (sub-plan milestone 4.0.M5). M10 may start then. Every later milestone that claims a runtime effect reports the same four numbers against the baseline.

---

## Phase 4.1: Polymorphic Baseline and Specialiser

The goal is a compiler that complies with `spec/compilation_model.md`. Every function reachable from a root gets one polymorphic baseline version that receives witnesses for its type parameters (value witnesses, capability tables, evidence) and operates on values of parameter type through them. Specialised clones, keyed by known type, ownership, lifetime class and handler, are built on top of the baseline under one size budget per function and in the order of their score, and the `--no-specialise` build mode builds only the mandatory specialisations. The monomorphic lowering of Phase 4.0 is replaced.

The detailed plan and milestones are in `docs/plans/phase-4.1-baseline-specialiser.md`. The milestone: every example that ran before still produces the same output in both build modes, and the runtime benchmarks are re-measured against the previous baseline in `docs/baselines/`.

Decision P6 superseded some of the rules this phase was built against (ownership clones by score, the lexical weight, tables per instance, the caller-chosen / callee-chosen result rule, coherence); the sub-plan's note lists them.

---

## Phase 4.2: Resolution round P6

In progress. What must be built for the compiler to meet decision P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`) as written into the specs. Ordered by dependency; each entry says what it depends on and opens with its state: **Done**, **In part** (with what remains) or **Not started**. What was found and fixed on the way is listed in `docs/decisions/P0-spec-review-2026-11-20.md` §4. Several entries need **packages** (more than one package compiled separately, floor entries, the interface and body tiers, `spec/compilation_model.md` §6–§7), which no phase has built: Phase 4.1 compiles one program as one package.

| # | Work | Spec | Depends on |
|---|---|---|---|
| 1 | **Not started. Table-call convention derived from signatures.** A requirement's parameter is `own` iff a value of its type can become part of the result, `read` otherwise (a value of type `Self` contains its capability's `of` parameters, a type variable only itself, a function type only a function type or a data type with a function field); the exclusive pointer covers only `own` parameters of type `Self`. Today every table call passes every argument under `own` | Rule CM4.6, P6 D9 and amendment B1 | the capability tables and adapters of 4.1.M6 (built) |
| 2 | **Not started. Benchmark with capability-bounded parameters**, measuring the convention of 1 against `own` everywhere, before the convention is frozen | OQ-CM18, P6 D9 | 1, with the current convention kept selectable |
| 3 | **Not started. `pub`.** A production for `Visibility`, private by default, and the typer's check that another package names only public declarations. The parser reports `pub` as unsupported today. Dropping private baselines and unreferenced floor entries needs packages | Rule 8.6, Rule CM6.2, grammar §2 | parser and typer only; its compilation consequences, packages |
| 4 | **Not started. `using`, named satisfactions, and the satisfaction recorded in a type.** Grammar (`using`, `named`), resolution in four levels replacing the coherence check of capability closure, `E061` at package compile for a site that levels 1–4 do not decide, with both ways out in its help (Rule 8.4.b), the automatic name of an unnamed satisfaction (`DerivedMoneyOrd`, Rule 8.4.d), `E066` for an unnamed satisfaction of a pair that has a built-in or prelude one, and operators and tables that follow a named one where it is selected (Rule 8.4.c; today a program's `i64 satisfies Eq` body is ignored), `Tree of Money[Ord = ByAmount]` in type equality, and table and clone symbols formed from the satisfaction's identity. Satisfactions are already keyed by symbol (17) | Rules 8.4, 8.4.a–8.4.d, CM6.5, CM6.6 | capability closure (Phase 3.1, built); the satisfaction in a choice point's pool, on 7 |
| 5 | **In part. `inspect` and `trace`, and bounds for `show`, `==` and `clone`.** Done: `show`, `==` and `clone` on an unbounded type parameter are `T022` (P6 D10). Not started: the prelude bindings of `inspect` and `trace`, interpolation allowed for unbounded types only inside a diagnostic sink, a diagnostic channel in the runtime, and stripping by `fixed release` | `spec/diagnostics.md`, Rule CM8.8, P6 D10, D11 | typer and runtime; printing a cell reached through generic code, on 8 |
| 6 | **Not started. Immediates, `width` and accessors.** The value witness family `imm1`, `imm2`, `imm4`, `imm8`, `ref` (two witnesses, `imm` and `ref`, exist today), the immediate rule for every data type, flat containers at the witness's width, and accessors for types whose layout depends on argument witnesses. The bit layout of immediates stays provisional until it is built and measured (OQ-CM13) | Rules CM3.5, CM3.6, CM3.7, MM7.3, P6 D7, D8 | the witnesses of 4.1 (built); accessors for a layout that depends on a choice point, on 7 |
| 7 | **Not started. The link stage.** Choice points, usage summaries in the interface tier, selection at link with viability by cost props, the lock file, the profile file, binding of undefined symbols, and clones built at link from shipped Core by `fixed release`. The link stage resolves no satisfaction site | §8, Rules CM8.1–CM8.7, P6 D1, D4, D5, D6 | packages and both tiers; representation selection (M11, not started; `Representation.scala` stands in); cost bounds (`spec/cost.md`, not built); the test build of `fixed verify` for measured costs (not built) |
| 8 | **Not started. Link-unique tags and the type table.** Tags assigned at link, a `match` subtracting the type's base, `copy` reading size and field kinds from the table. The table's layout stays provisional until it is built and measured (OQ-CM13) | Rule CM8.8, Rule PR3.1, P6 D11 | 7 |
| 9 | **Not started. Evidence captured by closures, and the confinement check.** A closure captures the evidence (the handler's marker) of the effects its type does not mention; a closure that captured evidence must not outlive the handler (`E085`) | Rules E7.8, E5.5.b, P6 D14 | evidence passing (Phase 4, not built); the escape analysis of Phase 3.4 (built) for the check |

Added by the owner's decisions after the consistency review (P6, third round), in the order they should be done:

| # | Work | Spec | Depends on |
|---|---|---|---|
| 10 | **Done (`087327e`). camelCase rename.** The prelude, the examples, the benchmarks, the test programs and the compiler's built-in member names use camelCase for value-level names; 157 names, golden outputs unchanged | `spec/stdlib.md` Rule S1.1 | — |
| 11 | **Not started. `assume prop` and the open state.** The parser accepts `assume` before `prop`; a prop nobody checked is open, not `assumed`; only `proved` and `assumed` props reach a facts-lane decision; `fixed verify` warns `W108` when it proves an assumed prop and `W109` when it refutes one | `spec/properties.md` Rules P7.1, P7.7, P7.8 | nothing; `fixed verify` does not exist yet, so today this is the parser and the status type |
| 12 | **Done. `Clone` on request.** The typer derives `clone` only for a type that declares `T satisfies Clone` without a body (`Satisfaction.onRequest`); the prelude declares it for its data types and `String` | `spec/stdlib.md` §4.11 | — |
| 13 | **In part. `use` of a lower-case name**, so that a `pub fn` can be imported. The parser and the namer already take a lower-case segment; importing a `pub fn` from another package needs packages | grammar `UseSel` | 3 |
| 14 | **Not started. No witness with an anonymous result; one choice point per requirement.** The backend today returns nothing of the kind, but treats a result as caller-chosen by inference from the body; the rule is now read from the signature, and a requirement's anonymous result is one choice point shared by every satisfaction (P6, fourth round) | Rule CM4.5 | 7 for the binding; the signature rule itself needs only the typer |
| 15 | **Not started. Captured-evidence bit on closures**, tested by a fork site | Rules E7.8, Q5.13.a | 9, and parallel lanes, which are not built |
| 16 | **In part. Bounded native stack for derived `unfold` and `map`.** Done for linear data (a list, `Nat`): both run as loops that build in reverse and then reverse in place (`Generated.loopStep`), and the generated `length` is a loop. Not started: trees, rose trees and snoc lists still take one native frame per level, and the second pass over linear data is what destination passing would remove. Destination passing needs a constructor with an unfilled slot and a later store into it, understood by demand inference, ownership versions, reference counting, abort handling and the emitter; data with several recursive fields needs a heap work list of (seed, slot) pairs in addition | `spec/type_system.md` Rule 7.4.b.3, `spec/memory_management.md` §7.1, Rule PR7.3, OQ-PR5 | nothing; it is a change to how constructors flow through every backend pass |
| 17 | **Done. Satisfactions keyed by symbol**, and the bodies of `satisfies` blocks lowered by the backend (`Symbols.satisfactionsOf`, `backend/Satisfied.scala`), in both build modes | `spec/type_system.md` Rule 8.4 | — |

Added by the owner's decisions of the fourth round (P6, "Owner's decisions (fourth round)" and the sections after it):

| # | Work | Spec | Depends on |
|---|---|---|---|
| 18 | **Not started. Exit codes by error class.** `Main` maps the errors of a run to the classes of the table (confirmed by the owner, with `99` for "not supported yet") and exits with the smallest class number; every diagnostic code is given its class; `a + b` stops at the first stage that fails. Today `Main` exits `1` for a compile error, `2` for a usage error and `3` for a build failure | `spec/reports.md` Rule RP5.5 | nothing; classes of commands not built yet (`link`, `verify`, `release`) come with them |
| 19 | **Not started. List literals as choice points, and type ascription.** The parser's `AscribedExpr` (`e is T`); the typer types a list literal as an abstract constructor call through `Sequencing` (today it gives every literal `List of E`), gathers the context's requirements into the bound of its choice point, and checks ascriptions (`T024`); then examples 02 and 11 return to literals (`[1, 3, 5, …]`, `[5, 2, 8, 1, 9, 3]`) | `spec/type_system.md` Rules 5.10, 5.11, grammar v0.4.14 | the parser and typer for the ascription and for cases 1 and 2 of Rule 5.10; a choice point bound at link (case 3), on 7 |
| 20 | **Done (`0d864ba`). Loop form of the derived `fold` of linear data**, adopted by the owner: the spine reversed (in place only where exclusively owned, into a copy otherwise), then folded from the end in a loop (`Generated.foldDown`), so `fold`, `para`, `unfold` and `map` of linear data all run in bounded native stack. Trees keep one frame per level (16) | `spec/type_system.md` Rule 7.4.b.3 | — |
| 21 | **Not started. Derived `Functor` through a function result** (`u64 -> A`): `map` composes `f` after the field. Built: `Functor` derived only where `map` reaches every occurrence of the rightmost parameter, with a diagnostic naming the blocking field | `spec/type_system.md` Rule 7.4.c; P6 fourth round, "Derived `Functor`" | — |
| 22 | **Not started. `Overflow` and `DivideByZero` effects** on integer arithmetic (P6 fourth round, confirmed). The prelude's effects, `Arithmetic`, `ArithmeticError`, `arithmeticOrNone`, `arithmeticOrError`; the rows of `Numeric`'s requirements and its `wrapping…`, `saturating…` and `checked…` members; checked operations in the emitter (`llvm.*.with.overflow`, a divisor test, `MIN / -1`) branching to the operation; `T025` for constant arithmetic; the runtime's handler at `main` (position, status `101`); `widen` in the typer (`T026` at 64 bits); `E107` where an integer refinement is established without a proof (there is no run-time check). Today `+`, `-`, `*` wrap, `MIN / -1` is undefined behaviour, and division by zero exits `101`. Once built, the examples, benchmarks and test programs need `with Overflow` or a handler wherever they do integer arithmetic on unproved ranges | `spec/effects.md` §3.6, `spec/stdlib.md` §4.9, §4.13, `spec/type_system.md` §5.7 | effect inference (M7, built) and abortive operations in the backend (built); evidence passing for handlers other than `main`'s (Phase 4, not built); removing the effect by range needs the static prover (M9, not started), so until then every operation on unproved operands keeps the effect |
| 23 | **In part. Prelude names: hiding, a path, renaming imports** (P6 fourth round). Done: the prelude's own scope, syntax and derivation naming the prelude's entities, no derivation of a program's capability, and `T004` for `IDENT is Bound`. Not started: `W060` where a declaration or a `use` hides a prelude name; the package `std.prelude` and `use … as …` (grammar `UseRename`) in the parser and namer; diagnostics and hints that print `std.prelude.<Name>` or another package's path instead of the internal `prelude.<name>`; `T003` for a declaration and a `use`d name, or two `use`s, with one name; `T004` for a bare `of` parameter and a row variable named like a bound name. Most examples hide prelude names and will get `W060` (02, 03 and 06 their own `Optional` and other capabilities, 03 and 08 `effect Fail`, 04 `data Json`, 05 `fn inspect`, 07 `data Nat`) | `spec/type_system.md` §4.3, grammar v0.4.15 | the prelude scope of 4.1 (built); exports to importers need packages (3, 13) |
| 24 | **In part. Totality.** Done (`typer/Totality.scala`): recursion between named functions is accepted when structural or under the prelude's effect `Diverge`, and is otherwise `T027` with the ways out (`fold`, `para`, `unfold`, a `match`, `with Diverge`); a cycle among a capability's default bodies through `Self` is `T028`. Not started: `Diverge` in the row of an `unfold` whose termination is not `proved` or `assumed` (Rule 7.4.i, OQ-T4); the three shapes the check rejects though they terminate (a nested `fn` on a sub-value, a `match` on a tuple of parameters, `t > t2` inside `compare`) | `spec/type_system.md` Rules 7.4.h, 7.4.i, 7.4.j | — |
| 25 | **Done. Derived `para`**, in both build modes; for linear data in bounded native stack, keeping every original sub-value alive (one allocation per cell, no reuse in place) | `spec/type_system.md` Rule 7.4.g | 20 |
| 26 | **Done. Derived `Show`, `Eq` and `Ord`** in the backend (`Generated.derivedShow`, `derivedEq`, the derived `compare`), at known type arguments, `show` and `eq` also through tables built at run time; a program's `satisfies` body takes precedence. `Json` still cannot use them (`B005`: its field `is Map of (String, Json)` has no representing data type) | `spec/stdlib.md` §3.2, P6 D10 | 17 |
| 27 | **Done. Literal patterns** in the backend (`backend/Cases.scala`): integers, `bool`, `char` and `String`, also nested in tuple and variant patterns. Float literal patterns and a literal in a `let` pattern are `B005` | `spec/pattern_matching.md` §3.3 | — |
| 28 | **UTF-8 strings.** Check input where it enters; effect `Decode`, `Bytes`, `Console.readBytes`, `String.fromUtf8` and the handlers to `Optional` and `Result` (`spec/stdlib.md` Rules S3.7, S3.7.a) | Not started | Order and equality by code point are built; `readLine` does not check |

Also open from P6 and not listed as work: the suspected compiler fault behind the ownership-clone measurement (OQ-CM16), the unexplained 4% on `list_pipeline` and `records` and `fx_drop`'s recursion on children other than the last (OQ-CM17), and the 8-byte header hypothesis (OQ-CM15).

---

## Phase 4: Code Generation (to LLVM IR)

### Compilation pipeline

```
Package compile (once per package)
  Typed + capability-closed + quantity-checked AST; static prover
  → Lowering to polymorphic baseline Core (one version per function, witnesses as hidden parameters)
  → Demand and memory inference; cost checker
  → Selection of the choice points whose values never leave the package
  → Specialisation (mandatory specialisations, then clones by score under the per-function budget; `spec/compilation_model.md` §5)
  → Match desugaring (match → fold calls)
  → Fold specialization and fusion (guaranteed; `spec/type_system.md` Rules 7.4.b.1, 7.4.b.2)
  → Perceus insertion (dup/drop/reuse token placement, guided by demands, exclusive ownership and lifetime classes)
  → Region lowering (data-stack marks and resets, destinations, class witnesses, evacuations)
  → Evidence passing (effect operations → evidence vector lookups)
  → LLVM IR emission (textual .ll for the prototype; in-process bindings later if needed),
    interface tier, body tier (polymorphic Core), usage summaries

Application link
  → Selection of the remaining choice points (summaries, lock file, profile file)
  → Binding of the choice points' undefined symbols; accessors; type table and link-unique tags
  → Release build: clones for the chosen types from the shipped Core
  → llc / clang → native binary

Test build
  → Property-based testing and measurement → profile file
```

Capability sets and quantities are pinned by the time codegen starts. The representation of a value of parameter type travels by witness (`spec/compilation_model.md` Rule CM3.4); a choice point is selected at package compile when its values never leave the package and at the application link otherwise (Rule CM8.1). Today the compiler runs the package compile only, for a single package, and hands the IR to `clang`.

### Deliverables

| Component | Description |
|---|---|
| **Baseline lowering** | Lower each function reachable from a root (Rule CM3.2) to one polymorphic baseline version in Core, with value witnesses, capability tables and evidence as hidden parameters (`spec/compilation_model.md` §3–§4). Built in Phase 4.1 |
| **Specialiser** | Build clones of baseline versions by key, in score order and within the per-function budget, plus the mandatory specialisations (`spec/compilation_model.md` §5). Built in Phase 4.1 |
| **Match desugarer** (`transform/MatchDesugar.scala`) | Convert `match` to `fold` calls, `if` to `bool.fold`. Implemented as a `MiniPhase` |
| **Fold specializer** (`transform/FoldSpecializer.scala`) | Guaranteed by the spec, not an optimization level: a mandatory specialisation, outside the clone budget and built in every build mode (`spec/compilation_model.md` Rule CM5.5). Inlines lambda and statically known function arguments of auto-derived `fold` / `unfold` (Rule 7.4.b.2), and fuses an `unfold` consumed by exactly one `fold` into a loop with no intermediate structure (Rule 7.4.b.1). `toNat(n).fold(...)` compiles to a counted loop. Runs after match desugaring, because desugaring produces more `fold` calls, and before Perceus, so the fused structure never gets reference counts |
| **Perceus inserter** (`perceus/PerceusInserter.scala`) | Insert `dup`/`drop` operations guided by demand × lifetime class (`spec/memory_management.md` §3.2). 0-quantity bindings: erased. `read` borrows; exclusively owned values are reused without a test; Perceus RC only when `Heap` or `Region`; other classes are freed by their region |
| **Region lowering** (`backend/Regions.scala`) | Chunked data stacks, scope marks and resets, drop lists, counted regions, destinations, class witnesses and evacuations per `spec/memory_management.md` §6–§7 |
| **Evidence passer** (`effects/EvidencePasser.scala`) | Compile effects to evidence vector lookups |
| **LLVM IR emitter** (`backend/EmitLLVM.scala`) | Emit textual LLVM IR (.ll). Prototype strategy: shell out to `llc` / `clang` for native code generation. |

### LLVM IR representation mapping

| Fixed concept | LLVM IR representation |
|---|---|
| Value of at most 64 bits (`bool`, `char`, `()`, integers up to 64 bits, `f32`, `f64`) | Immediate in a 64-bit slot (`spec/compilation_model.md` Rule CM3.3) |
| Data type instance that holds no reference and fits in 64 bits (`Order`, `Pair of (u8, u16)`) | Immediate in the slot: tag in the low byte, fields packed in declaration order, no cell (`spec/compilation_model.md` Rule CM3.5). Not built: today every data value is a cell |
| Data cell (sum, product or recursive: `Optional`, `Color`, `Expr`, `Config`, a list cell) | `ptr` to a cell: a header (count, tag, reference map: a bit per payload slot that holds a counted reference) followed by 8-byte payload slots, one per field in declaration order (`spec/compilation_model.md` Rule CM3.3, `spec/memory_management.md` Rule PR3.1). As built, the header is `{i64 rc, u32 tag, u32 refs}` and the tag is the variant's index within its type; the spec makes the tag unique in the link unit, indexing a type table (Rule CM8.8). The layout is the same in the baseline version and in every clone (Rule CM3.4) |
| Capability satisfied by contiguous array (e.g., `RandomAccess + Sized`) | `{ i64 len, i64 cap, ptr data }` |
| Closure | `{ ptr code, ptr env }` (opaque pointers, LLVM 15+) |
| Block lambda | Same as closure (syntactic difference only) |
| Effect operation | Evidence vector load (`getelementptr` + `load`) + `call ptr` indirect call |
| `resume` | Direct `call` (single-shot) |
| Refcount | `{ i64 rc, ... }` header on `Heap` values only; non-atomic ops (`spec/memory_management.md` §17.2) |

### Milestone

All example programs compile to LLVM IR (`.ll`), `llc` / `clang` produce a native binary, and the binaries run correctly. The emitted IR for a fused `unfold`-then-`fold` contains no allocation for the intermediate structure, and the IR for a `fold` with a lambda argument contains no indirect call per step.

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

Profile-guided optimization for representation selection and specialisation. Measurement happens first in the **test build**: `fixed verify` already runs each prop across the satisfying types, and records supply-side costs (`steps`, `constructs`, `releases`, cycles, cache and branch misses per capability method, satisfaction, size class and target) and demand-side counters into a **profile file** per target (`spec/compilation_model.md` Rule CM8.6, `spec/properties.md` Rule P7.9). A production profile, where one exists, takes priority on the demand side. Measurements feed only decisions among alternatives that are all correct: selection at link, clone weights and the hot-function multiplier of the budget. They never enter a decision that would be wrong if a claim were false (`spec/properties.md` Rule P7.8).

### Deliverables

| Component | Description |
|---|---|
| **Measurement in the test build** | Counters and machine facts recorded while props run, written to the profile file; `--no-measure` turns it off |
| **Instrumented mode** | Compiler flag that inserts profiling counters into generated LLVM IR (or delegates to LLVM's built-in PGO instrumentation pass), for a production profile |
| **Profile reader** | Reads the profile file and feeds it to the application link and the specialiser |
| **Representation optimizer** | Selection at link scores candidates by usage × cost, both measured where data exists (`spec/compilation_model.md` Rule CM8.4). A representation change driven by a profile goes through selection, never through code cloning (Rule CM3.4) |
| **Profile-weighted specialiser** | Measured request counts replace the static weight in the clone score, and the clone budget is multiplied for functions the profile shows to be hot (`spec/compilation_model.md` Rules CM5.3, CM5.4) |
| **Hot-path specializer** | Inline frequently-called closures, eliminate dynamic dispatch on hot paths. Covers only what Phase 4's guaranteed fold specialization does not: closures passed to user-defined higher-order functions and calls whose target is known only from a profile |

### PGO-driven decisions

- Sequential-only fold access → contiguous array (even if capabilities didn't require it)
- Dynamic dispatch → a clone for the known type, within the budget (when callee is statically known in practice)
- Class witness → guarded direct code or specialization (when one lifetime class dominates a call site, `spec/memory_management.md` §7.4)
- Hot-code evacuation (warning W110) judged by profile instead of the static fold-step heuristic
- Closure allocation → inlined (when closure is called exactly once)
- Linked list → contiguous array (when profiling shows random access patterns despite only `Sequencing` being required)

---

## Open Design Questions

1. **`Part` resolution in multi-param capabilities**: `Sized of (Part, Size is Numeric)` — is `Part` always the first parameter? Can it be renamed? How does it interact with `extends`?

2. ~~**Coherence with capability-only types**~~: **Resolved by P6 D12.** No coherence: a package may declare a satisfaction for a type and capability it does not own, several may coexist, and `using` chooses (`spec/type_system.md` Rule 8.4).

3. **Mutual recursion**: Two capabilities referencing each other's `Self`. The compiler must detect cycles and co-allocate.

4. **Error messages**: Must speak in capability terms, never expose generated struct names. "Expected `is Folding of i64`, found `is Folding of String`."

5. **FFI boundary**: C interop requires concrete types. `extern` blocks declare C functions with concrete primitive types; the compiler generates wrapper functions.

6. **Effect handler composition syntax**: Nested `handle` blocks work but are verbose. Consider a cleaner composition syntax.

7. **`Self of B` vs `F for <_>`**: The new `Self of B` syntax is more intuitive for single-param HKTs. Does `F for <_>` survive for cases where you need to abstract over the container itself (e.g., `fn sequence<M is Monad for <_>>`)?

8. ~~Block lambda vs closure syntax~~: **Resolved in v0.3.** Block lambdas (`{ x -> body }`) removed. Single-line: `(x) -> expr`. Multi-line: `(x) ->` followed by an indented body.

9. **Data auto-deriving capabilities**: Which capabilities does a `data` type automatically satisfy? Multi-variant → `Folding`? With accessors → `Functor`? Recursive → auto-heap-allocation? Right-bias determines which parameter `Functor` maps over. Need clear rules for the full derivation matrix.

10. **GADT type equality constraints**: `data Expr of (A) { Lit(value: A), Add(left: Expr of i64, right: Expr of i64) }` — do we support type-level constraints per variant (true GADT power)? Or is `of` on data purely parametric?

11. **Type alias `of` interaction**: `is ArrayLike of (A is Ordered)` — how does `of` distribute across the expanded capabilities? Does it apply to all of them, or only those that accept `of`?

12. **Codata**: Fixed has `data` (inductive types defined by constructors) and `cap` (capability interfaces). Neither covers **codata** — coinductive types defined by observations/destructors rather than constructors. Codata could fill the gap for infinite streams, processes, and lazy structures that are concrete types (unlike `cap`) but defined by what you can observe (unlike `data`). See [Rethinking Data and Codata through Matrix Transposition](https://hackernoon.com/rethinking-data-and-codata-through-matrix-transposition) for the theoretical framing.

---

## Verification Plan

1. Parse all 11 example programs → AST
2. Type-check all 11 example programs (capability classification, representation selection, effect tracking)
3. Verify QTT quantity inference: 0-quantity bindings in type/prop/inferred-phantom positions only, 1-quantity obligations (`fip`, `resume`, linear effects) met
4. Verify pattern match exhaustiveness on sum capabilities
5. Verify effect tracking catches unhandled effects
6. Verify capability-set narrowing selects correct representations
7. Compile to LLVM IR → `llc` / `clang` produce native binary → run binaries → verify output
8. Verify erasure: 0-quantity bindings produce no runtime code in generated LLVM IR
9. Verify ownership: exclusively owned values have no dup/drop and are reused without a run-time test in generated LLVM IR
10. Benchmark against equivalent Koka programs
11. Test PGO: verify representation changes improve performance on profiled workloads
