# Fixed Standard Library — Core Types and Capabilities

**Status:** Draft v0.1
**Specifies:** the core data types and capabilities Fixed's stdlib provides; the cap-vs-data classification each one takes; the auto-derived satisfactions.
**Last revised:** 2026-05-01

## 1. Scope

This document is the normative source for which built-in stdlib types are `data` types (closed sums or product records the programmer can match on by variant) and which are `cap`s (abstract capabilities the compiler picks a representation for). It also records the canonical `satisfies` declarations that bridge them.

It does **not** specify:
- The full method surface of each cap (see `stdlib/*.fixed` source once implemented in Phase 5).
- Effect-only stdlib items (`Console`, `FileSystem`, etc.) — see `spec/effects.md` and forthcoming `stdlib/io.fixed`.

## 2. Classification (normative)

| Stdlib name   | Kind | Reason                                                                 |
|---------------|------|------------------------------------------------------------------------|
| `List`        | data | Closed sum (`Cons` + `Nil`) the programmer matches on by variant       |
| `Json`        | data | Closed sum (6 variants) the programmer matches on by variant           |
| `Tuple`       | data | Single-variant product type (the concrete carrier behind `Pair`)       |
| `Nat`         | data | Closed sum (`Zero` + `Succ`) — recursion-scheme example                |
| `Optional`    | cap  | Abstract presence-or-absence; representation chosen per use            |
| `Result`      | cap  | Abstract success-or-failure; representation chosen per use             |
| `Pair`        | cap  | Abstract two-projection container; `Tuple` is the canonical satisfier |
| `Ordering`    | cap  | Abstract `<`/`==`/`>` outcome; representation chosen per use           |
| `Boolean`     | cap  | Abstract two-state with `fold`; backed by primitive `bool`             |
| `Empty`       | cap  | Abstract "has no inhabitants"                                          |
| `Eq`          | cap  | `==`/`!=` operator support (§4.7)                                       |
| `Ord`         | cap  | `<`/`<=`/`>`/`>=` operator support, extends `Eq` (§4.8)                 |
| `Numeric`     | cap  | `+`/`-`/`*`/`/`/`%` operator support, extends `Eq + Ord` (§4.9)         |
| `Show`        | cap  | `.show -> String` (§4.10)                                                |
| `Clone`       | cap  | Explicit `.clone()`; primary path is borrow inference (§4.11)            |

> **Update from prior drafts.** Earlier drafts of `docs/plans/implementation-plan.md` listed `Pair` and `Ordering` as data types. The classification above supersedes that — they are caps, with `Tuple` (new) as the canonical concrete satisfier of `Pair`. See §6 for migration guidance.

## 3. Data declarations (normative shape)

These are the shapes programmers may match on. The compiler still owns the representation for each (e.g., `List` may be backed by linked list or array depending on use).

### 3.1 `data List`

```
data List of A:
    Cons(head: A, tail: List of A)
    Nil
```

### 3.2 `data Json`

```
data Json:
    Null
    Bool(value: bool)
    Number(value: f64)
    String(value: String)
    Array(elements: is Sequencing of Json)
    Object(fields: is Map of (String, Json))
```

### 3.3 `data Tuple`

```
data Tuple of (A, B):
    Tuple(first: A, second: B)
```

This is the single-constructor sugar form. Construction: `Tuple(x, y)`. Destructure: `let Tuple { first: a, second: b } = t` or pattern `Tuple(a, b)`.

### 3.4 `data Nat`

```
data Nat:
    Zero
    Succ(pred: Nat)
```

## 4. Capability declarations (normative shape)

### 4.1 `cap Optional`

```
cap Optional extends Functor + Folding:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold(on_some: Part -> R, on_none: () -> R) -> R
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    fn orElse(other: Self) -> Self = self.fold((_) -> self, () -> other)
```

### 4.2 `cap Result`

```
cap Result of (E, A):
    Self.fn ok(value: A) -> Self
    Self.fn err(error: E) -> Self
    fn fold(on_ok: A -> R, on_err: E -> R) -> R
```

### 4.3 `cap Pair`

```
cap Pair of (A, B):
    Self.fn pair(a: A, b: B) -> Self
    fn first -> A
    fn second -> B
```

### 4.4 `cap Ordering`

```
cap Ordering:
    Self.fn lt -> Self
    Self.fn eq -> Self
    Self.fn gt -> Self
    fn fold(on_lt: () -> R, on_eq: () -> R, on_gt: () -> R) -> R
```

Programmers do not `match` on an `is Ordering` value — they `.fold(...)`.

### 4.5 `cap Boolean`

```
cap Boolean:
    Self.fn yes -> Self
    Self.fn no -> Self
    fn fold(on_yes: () -> R, on_no: () -> R) -> R
```

The primitive `bool` satisfies `Boolean` (the typer auto-bridges).

### 4.6 `cap Empty`

```
cap Empty:
    Self.fn empty -> Self
```

A constructor-only cap. Common base for collection caps.

### 4.7 `cap Eq`

```
cap Eq:
    fn eq(other: Self) -> bool
```

The `==` operator desugars to `.eq(other)`; `!=` desugars to `!.eq(other)`. Auto-derived for any `data` whose every field type satisfies `Eq` (compiler-generated component-wise equality).

### 4.8 `cap Ord`

```
cap Ord extends Eq:
    fn compare(other: Self) -> is Ordering
```

The relational operators `<`, `<=`, `>`, `>=` desugar via `compare` and `Ordering.fold`:

```
a < b      ≡   a.compare(b).fold(() -> true,  () -> false, () -> false)
a <= b     ≡   a.compare(b).fold(() -> true,  () -> true,  () -> false)
a > b      ≡   a.compare(b).fold(() -> false, () -> false, () -> true)
a >= b     ≡   a.compare(b).fold(() -> false, () -> true,  () -> true)
```

`Ord` extends `Eq`; any `Ord` satisfaction also provides `==`/`!=`. Auto-derived for `data` types whose every field type satisfies `Ord` (lexicographic by declaration order).

### 4.9 `cap Numeric`

```
cap Numeric extends Eq + Ord:
    Self.fn from_i64(n: i64) -> Self
    fn add(other: Self) -> Self
    fn sub(other: Self) -> Self
    fn mul(other: Self) -> Self
    fn div(other: Self) -> Self      // truncating for integers, IEEE for floats
    fn rem(other: Self) -> Self      // remainder; pairs with div
```

Operator desugaring:

```
a + b      ≡   a.add(b)
a - b      ≡   a.sub(b)
a * b      ≡   a.mul(b)
a / b      ≡   a.div(b)
a % b      ≡   a.rem(b)
```

Numeric-literal polymorphism (`type_system.md` §5.7) goes through `Self.fn from_i64`: an integer literal in a `Numeric`-bounded position is desugared to `Self.from_i64(LITERAL)`. Float literals desugar to a `from_f64` overload (forthcoming; deferred until float-spec lands).

All built-in primitive numeric types — `i8`/`i16`/`i32`/`i64`/`i128`, `u8`/`u16`/`u32`/`u64`/`u128`, `f32`/`f64` — satisfy `Numeric` (compiler-provided).

### 4.10 `cap Show`

```
cap Show:
    fn show -> String
```

Convert a value to a human-readable `String`. Auto-derived for `data` types whose every field type satisfies `Show` (component-wise concat with constructor name and parens).

All primitive types (`bool`, integer types, `f32`, `f64`, `String`, `char`, `()`) satisfy `Show` via compiler-built-in formatters.

### 4.11 `cap Clone`

```
cap Clone:
    fn clone -> Self
```

Produces a value of the same type whose lifetime is independent of the receiver. Implementations are expected to perform a deep copy when the underlying representation is heap-allocated (refcount-incremented copy is acceptable when the representation is a refcounted shared structure — see `spec/perceus.md`).

**Relationship with borrow inference.** Perceus's borrow inference (`spec/perceus.md` §8) is the *primary* mechanism for sharing values across multiple uses without allocation: when the typer can prove a value is read-only and not consumed, the call site reads in place rather than dup-then-drop. `.clone()` is the **explicit fallback** for cases where borrow inference cannot prove uniqueness (e.g., a value passed to a closure that may run multiple times) or where the program semantics genuinely require a separate copy (e.g., before-and-after comparisons, or staging an intermediate snapshot for later use).

In current example code, `.clone()` was used defensively in places where borrow inference is expected to handle the duplication implicitly; the v0.4.5 sweep removed those calls. Programs that *need* an explicit duplicate continue to call `.clone()`; the typer ensures the receiver type satisfies `Clone`.

**Auto-derivation.** `Clone` is auto-derived for any `data` type whose every field type satisfies `Clone`. Primitives satisfy `Clone` trivially. Capabilities do not auto-derive `Clone` because the underlying representation isn't fixed; satisfying types must opt in.

## 5. Canonical satisfactions

Brought into scope via the standard prelude:

```
List satisfies Sequencing:
    Cons as cons
    Nil as empty
List satisfies Folding              // auto-derived from variants
List satisfies Functor              // auto-derived per Rule 7.4.c; the active type
                                    // parameter is `A` (List has only one)
List satisfies Filtering            // auto-derived

Json satisfies Show
Json satisfies Eq

Tuple satisfies Pair:
    Tuple as pair
Tuple satisfies Functor             // auto-derived per Rule 7.4.c with right-bias:
                                    // the active type parameter is `B` (the second
                                    // projection). `tuple.map(f: B -> B') -> Tuple of (A, B')`.

bool satisfies Boolean of Self:
    Self as yes        // when bool is true
    Self as no         // when bool is false   (compiler auto-bridges via primitive)

Nat satisfies Folding               // auto-derived from Zero + Succ
```

The right-bias commitment for `Tuple satisfies Functor` is **normative** (not commentary): per Rule 7.4.c, the active type parameter for auto-derived Functor on a multi-parameter `data` is the rightmost non-phantom one. Combined with Rule 5.4.a/b (right-aligned positional substitution for `Self of B`), this means `tuple.map(f)` consistently maps over the second projection across the spec, the example corpus, and the typer.

`Optional`, `Result`, `Ordering` are abstract caps with no canonical built-in satisfaction. Programs that want a default backing data type write their own (`data Maybe satisfies Optional`, etc.) — example 03 shows the pattern.

## 6. Migration from earlier drafts

Earlier drafts (`docs/plans/implementation-plan.md` line 352) classified `List`, `Ordering`, `Pair`, `Json` all as data types. The corrected classification:

| Was        | Now                                                                 |
|------------|---------------------------------------------------------------------|
| `data List`     | unchanged (still data)                                             |
| `data Json`     | unchanged (still data)                                             |
| `data Pair`     | replaced by `cap Pair` + new `data Tuple` (the canonical satisfier) |
| `data Ordering` | replaced by `cap Ordering` (no built-in concrete satisfier)        |

### 6.1 What changes in user code

- **`Pair { first: a, second: b }` destructure patterns.** These are struct-destructure patterns (Rule M3.6) and require a single-variant data type. Replace with `Tuple { first: a, second: b }` if the pair is a `data Tuple` value. If the binding is `is Pair of (A, B)` (a cap-bound), use the accessor methods instead: `let a = p.first` and `let b = p.second`.
- **`Pair(a, b)` construction.** Replace with `Tuple(a, b)` for direct construction. To construct via the cap (so the compiler can pick the representation), write `Pair.pair(a, b)`.
- **`is Pair of (A, B)`.** Continues to work — `Pair` is now a cap, so `is Pair` is the natural form.
- **`match ord: Ordering.Less => ... | Ordering.Equal => ... | Ordering.Greater => ...`.** No longer valid on a cap. Rewrite as `ord.fold((_) -> ..., (_) -> ..., (_) -> ...)` or, if a concrete `data` type that satisfies `Ordering` is in scope, match on that type's data variants.

### 6.2 Why the change

`Pair` and `Ordering` are abstract — there's no specific layout the compiler should commit to. Treating them as caps lets:
- a sorted list use a packed `i8` tag for `Ordering` rather than a separate enum
- a key-value map use whatever pair layout is most efficient for its backing storage
- foreign data types implement these caps without needing to be a literal `Tuple` or built-in enum

`List` and `Json` are *concrete* — programmers really do match on `Cons`/`Nil` and on the six JSON variants. They stay as data.

## 7. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | Auto-derivation rules (Rule 7.4); cap classification (§6.4); satisfaction resolution (§8) |
| `spec/pattern_matching.md` | Match restricted to data types (Rule M3.4.c); `Tuple` matchable, `Pair` not |
| `spec/effects.md` | I/O effects (`Console`, `FileSystem`, …) — separate module |
| `docs/plans/implementation-plan.md` | Phase 5 deliverables in `stdlib/*.fixed` |
| `examples/03_option_result.fixed` | `data Maybe satisfies Optional`, `data Outcome satisfies Result` — typical user satisfactions |

## 8. Open questions

- **OQ-S1 — Built-in `Show`.** Should `bool`, `i64`, `f64`, etc. auto-satisfy `Show`? Most programs need `.show()` constantly; refusing the default is friction. Provisional yes, formalize once `cap Show` lands.
- **OQ-S2 — Map/Set.** `cap Map of (K, V)` and `cap Set of A` are mentioned in examples but not yet specified here. Defer to a Phase-5 stdlib doc.
- **OQ-S3 — `String`.** Currently ambiguous: a primitive in the grammar (`PrimitiveType`), but example 11 declares refinements (`type Username = String + min_length(3)`) that expect cap-like composition. Decision: `String` is a primitive *and* satisfies a `cap Stringlike` (or similar) for refinement purposes. Formalize separately.
