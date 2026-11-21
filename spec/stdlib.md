# Fixed Standard Library — Core Types and Capabilities

**Status:** Draft v0.3.3
**Specifies:** the core data types and capabilities Fixed's stdlib provides; the cap-vs-data classification each one takes; the auto-derived satisfactions; the arithmetic effects and their handlers.
**Last revised:** 2026-11-21

**Changes from v0.3.2.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Integer overflow and division"). §4.9: the requirements of `Numeric` declare `Overflow` and `DivideByZero` (`spec/effects.md` Rule E3.6.c), and `Numeric` gains the members without effects (`wrapping…`, `saturating…`, `checked…`). New §4.13: the prelude's effects `Overflow` and `DivideByZero`, the alias `Arithmetic`, the data type `ArithmeticError`, the handlers `arithmeticOrNone` and `arithmeticOrError`, and the widening form `widen`. Not built.

**Changes from v0.3.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). `Clone` is derived only on request (§4.11). Naming: value-level names are camelCase, type-level names PascalCase (§1); the members of this document are renamed accordingly.

**Changes from v0.3.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): the prelude declares the diagnostic sinks `inspect` and `trace` (§5); the reference to struct-destructure patterns names Rule M3.6.b; a cost prop binds a satisfaction, not a satisfying type (§4.12).

**Changes from v0.2.** Applies decision record P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`). New §3.6: `data Order` (`Less`, `Equal`, `Greater`) satisfies `Ordering` and is an immediate (D17). §4.11: `clone` returns an equal value; the satisfier chooses whether it shares or copies, and says which in a cost prop; a type that must not be duplicated does not satisfy `Clone` (D10). The unexplained "deep copy or count increment" alternative is gone. §5: "canonical satisfaction" now means only disambiguation rule 4 of `spec/type_system.md` Rule 6.5.5. `Maybe satisfies Optional` and `Order satisfies Ordering` are canonical in that sense, and the old sentence that `Optional` and `Ordering` have none is withdrawn. Every declaration of the prelude is `pub` (§1). Malformed prelude text corrected: `bool satisfies Boolean of Self` is now a built-in satisfaction with no mapping, `!=` desugars to `!a.eq(b)`, the `Optional` member is spelled `orElse` (as in §4.1; `spec/type_system.md` §6.1 now agrees), and satisfactions list the members they need. `Json`'s fields carry `Show + Eq`, without which its derived `Show` and `Eq` would have nothing to call (`spec/type_system.md` Rule 5.1.3).

**Changes from v0.1.** New §4.12: provisional cost contracts for the core capabilities (`spec/cost.md`). §4.11 no longer says a caller copies the demanded part: only the shared entry of a `fip fn` does (`spec/quantities.md` Rule Q9.3). §3 states how a `data` type's layout reaches code that does not know it.

## 1. Scope

This document is the normative source for which built-in stdlib types are `data` types (closed sums or product records the programmer can match on by variant) and which are `cap`s (abstract capabilities the compiler picks a representation for). It also records the prelude's `satisfies` declarations that bridge them.

The prelude is a package. Every declaration this document gives is `pub` in it (`spec/type_system.md` §8.6), and the code below writes `pub` on each.

**Naming (Rule S1.1).** Value-level names are written in camelCase: functions, members of capabilities and of data types, effect operations, parameters, fields, bindings and props (`fromI64`, `isEmpty`, `foldRight`, `printLine`). Type-level names are written in PascalCase: data types, variants, capabilities, effects, type variables and named satisfactions. The standard library, the examples and this specification follow the convention without exception. The grammar fixes only the case of the first letter (`spec/syntax_grammar.ebnf`); the rest is a convention, which the compiler may report as a warning and never as an error.

It does **not** specify:
- The full method surface of each cap (see `stdlib/*.fixed` source once implemented in Phase 5).
- Effect-only stdlib items outside the prelude (`Console`, `FileSystem`, etc.) — see `spec/effects.md` and forthcoming `stdlib/io.fixed`. The prelude's arithmetic effects are in §4.13.

## 2. Classification (normative)

| Stdlib name   | Kind | Reason                                                                 |
|---------------|------|------------------------------------------------------------------------|
| `List`        | data | Closed sum (`Cons` + `Nil`) the programmer matches on by variant       |
| `Json`        | data | Closed sum (6 variants) the programmer matches on by variant           |
| `Tuple`       | data | Single-variant product type (the concrete carrier behind `Pair`)       |
| `Nat`         | data | Closed sum (`Zero` + `Succ`) — recursion-scheme example                |
| `Maybe`       | data | Closed sum (`Just` + `Nothing`); the prelude's satisfier of `Optional` (§3.5) |
| `Order`       | data | Closed sum (`Less` + `Equal` + `Greater`); the prelude's satisfier of `Ordering` (§3.6) |
| `ArithmeticError` | data | Closed sum (`Overflowed` + `DividedByZero`); the error of `arithmeticOrError` (§4.13) |
| `Optional`    | cap  | Abstract presence-or-absence; representation chosen per use            |
| `Result`      | cap  | Abstract success-or-failure; representation chosen per use             |
| `Pair`        | cap  | Abstract two-projection container; `Tuple` is the prelude's satisfier |
| `Ordering`    | cap  | Abstract `<`/`==`/`>` outcome; `Order` is the prelude's satisfier      |
| `Boolean`     | cap  | Abstract two-state with `fold`; backed by primitive `bool`             |
| `Empty`       | cap  | Abstract "has no inhabitants"                                          |
| `Eq`          | cap  | `==`/`!=` operator support (§4.7)                                       |
| `Ord`         | cap  | `<`/`<=`/`>`/`>=` operator support, extends `Eq` (§4.8)                 |
| `Numeric`     | cap  | `+`/`-`/`*`/`/`/`%` operator support, extends `Eq + Ord` (§4.9)         |
| `Show`        | cap  | `.show -> String` (§4.10)                                                |
| `Clone`       | cap  | Explicit `.clone()` returning an equal value; never needed for ownership (§4.11) |

> **Update from prior drafts.** Earlier drafts of `docs/plans/implementation-plan.md` listed `Pair` and `Ordering` as data types. The classification above supersedes that — they are caps, with `Tuple` (new) as the canonical concrete satisfier of `Pair`. See §6 for migration guidance.

## 3. Data declarations (normative shape)

These are the shapes programmers may match on. The compiler still owns the representation for each (e.g., `List` may be backed by linked list or array depending on use). A value carries its representation by witness to code that does not know it (`spec/compilation_model.md` §4), so the choice never becomes part of a package's ABI.

### 3.1 `data List`

```
pub data List of A:
    Cons(head: A, tail: List of A)
    Nil
```

### 3.2 `data Json`

```
pub data Json:
    Null
    Bool(value: bool)
    Number(value: f64)
    String(value: String)
    Array(elements: is Sequencing of Json + Show + Eq)
    Object(fields: is Map of (String, Json) + Show + Eq)
```

The two anonymous bounds are choice points (`spec/type_system.md` Rule 4.1.a): one representation for the elements of every `Json` array, and one for the fields of every object, chosen when the application is linked. They carry `Show + Eq` because the derived `Json satisfies Show` and `Json satisfies Eq` (§5) call `show` and `eq` on them (`spec/type_system.md` Rule 5.1.3).

### 3.3 `data Tuple`

```
pub data Tuple of (A, B):
    Tuple(first: A, second: B)
```

This is the single-constructor sugar form. Construction: `Tuple(x, y)`. Destructure: `let Tuple { first: a, second: b } = t` or pattern `Tuple(a, b)`.

### 3.4 `data Nat`

```
pub data Nat:
    Zero
    Succ(pred: Nat)
```

### 3.5 `data Maybe`

```
pub data Maybe of A:
    Just(value: A)
    Nothing

pub Maybe satisfies Optional:
    Just as some
    Nothing as none
```

The prelude's satisfier of `Optional` (§4.1), so a program that uses `Optional` without declaring a data type for it has a representation. It is declared in `Optional`'s own package, so it is the canonical satisfaction of `Optional` in the sense of §5: it wins a tie, and nothing else. A program that declares its own `cap Optional` hides the prelude's, together with this satisfaction. Added for the Phase 4.0 backend slice (`docs/plans/phase-4.0-backend-slice.md` §7.3); M11's built-in representation candidates may later make it unnecessary.

### 3.6 `data Order`

```
pub data Order:
    Less
    Equal
    Greater

pub Order satisfies Ordering:
    Less as lt
    Equal as eq
    Greater as gt
```

The prelude's satisfier of `Ordering` (§4.4), so `Ord.compare -> is Ordering` has a representation (P6 D17). It holds no reference and fits in one byte, so it is an immediate: it travels inside the slot, with no cell (`spec/compilation_model.md` Rule CM3.5). A program may `match` on an `Order` value. On an `is Ordering` value it uses `fold` (§4.4). Like `Maybe`, it is the canonical satisfaction of its capability in the sense of §5 only.

## 4. Capability declarations (normative shape)

### 4.1 `cap Optional`

```
pub cap Optional extends Functor + Folding:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold(onSome: Part -> R, onNone: () -> R) -> R
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    fn orElse(other: Self) -> Self = self.fold((_) -> self, () -> other)
```

### 4.2 `cap Result`

```
pub cap Result of (E, A):
    Self.fn ok(value: A) -> Self
    Self.fn err(error: E) -> Self
    fn fold(onOk: A -> R, onErr: E -> R) -> R
```

### 4.3 `cap Pair`

```
pub cap Pair of (A, B):
    Self.fn pair(a: A, b: B) -> Self
    fn first -> A
    fn second -> B
```

### 4.4 `cap Ordering`

```
pub cap Ordering:
    Self.fn lt -> Self
    Self.fn eq -> Self
    Self.fn gt -> Self
    fn fold(onLt: () -> R, onEq: () -> R, onGt: () -> R) -> R
```

Programmers do not `match` on an `is Ordering` value — they `.fold(...)`.

### 4.5 `cap Boolean`

```
pub cap Boolean:
    Self.fn yes -> Self
    Self.fn no -> Self
    fn fold(onYes: () -> R, onNo: () -> R) -> R
    fn not -> Self = self.fold(() -> Self.no, () -> Self.yes)
```

The primitive `bool` satisfies `Boolean` (the typer auto-bridges). Operator desugaring:

```
!a         ≡   a.not
```

Per the v0.4.6 grammar (`UnaryExpr`), prefix `!` is single-arity: `!!a` is not admitted; iterate via parens (`!(!a)`) or call methods directly.

### 4.6 `cap Empty`

```
pub cap Empty:
    Self.fn empty -> Self
```

A constructor-only cap. Common base for collection caps.

### 4.7 `cap Eq`

```
pub cap Eq:
    fn eq(other: Self) -> bool
```

Operator desugaring:

```
a == b     ≡   a.eq(b)
a != b     ≡   !a.eq(b)          // ≡ a.eq(b).not; `!` applies to the whole call (UnaryExpr)
```

Auto-derived for any `data` whose every field type satisfies `Eq` (compiler-generated component-wise equality).

### 4.8 `cap Ord`

```
pub cap Ord extends Eq:
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
pub cap Numeric extends Eq + Ord:
    Self.fn fromI64(n: i64) -> Self with Overflow
    fn add(other: Self) -> Self with Overflow
    fn sub(other: Self) -> Self with Overflow
    fn mul(other: Self) -> Self with Overflow
    fn div(other: Self) -> Self with Arithmetic   // truncating for integers, IEEE for floats
    fn rem(other: Self) -> Self with Arithmetic   // remainder; pairs with div
    fn negate -> Self with Overflow                // additive inverse — `-x ≡ x.negate`

    // Members without `Overflow` (spec/effects.md Rule E3.6.i)
    fn wrappingAdd(other: Self) -> Self
    fn wrappingSub(other: Self) -> Self
    fn wrappingMul(other: Self) -> Self
    fn wrappingNegate -> Self
    fn wrappingDiv(other: Self) -> Self with DivideByZero
    fn wrappingRem(other: Self) -> Self with DivideByZero
    fn saturatingAdd(other: Self) -> Self
    fn saturatingSub(other: Self) -> Self
    fn saturatingMul(other: Self) -> Self
    fn saturatingNegate -> Self
    fn saturatingDiv(other: Self) -> Self with DivideByZero
    fn checkedAdd(other: Self) -> is Optional of Self =
        handle self.add(other):
            Overflow.overflow() => Optional.none
            return(v) => Optional.some(v)
    fn checkedSub(other: Self) -> is Optional of Self = ...
    fn checkedMul(other: Self) -> is Optional of Self = ...
    fn checkedNegate -> is Optional of Self = ...
    fn checkedDiv(other: Self) -> is Optional of Self = ...
    fn checkedRem(other: Self) -> is Optional of Self = ...
```

Each `checked…` member has the default body shown for `checkedAdd`, around its own member; `checkedDiv` and `checkedRem` also map `DivideByZero.divideByZero()` to `Optional.none`. The body constructs the requirement's own anonymous result (`spec/type_system.md` Rule 5.1.2), which is why it does not call `arithmeticOrNone` (§4.13), whose result is another choice point.

**Effects.** The rows are those of `spec/effects.md` Rule E3.6.c. A satisfaction's member may perform less than its requirement declares: the integer types perform `Overflow` and `DivideByZero` exactly where Rule E3.6.b says, `f32` and `f64` perform neither, and an operation whose range is proved performs nothing (Rule E3.6.d). A call on a value of a type parameter has the requirement's row. `wrapping…` computes modulo 2^n, `saturating…` gives the nearest value of the range, and `checked…` gives `Optional.none` where the plain member would perform an effect; on `f32` and `f64` the wrapping and saturating members are the IEEE 754 operations. The `checked…` members have default bodies (below); a program's satisfaction of `Numeric` defines the `wrapping…` and `saturating…` members, which have none, since they depend on the range of the type *(author's choice; the owner's confirmed choice puts them on `Numeric`)*.

Operator desugaring:

```
a + b      ≡   a.add(b)
a - b      ≡   a.sub(b)
a * b      ≡   a.mul(b)
a / b      ≡   a.div(b)
a % b      ≡   a.rem(b)
-a         ≡   a.negate           // prefix unary, single-arity per v0.4.6 grammar
```

Per the v0.4.6 grammar (`UnaryExpr`), prefix `-` is single-arity: `--a` is not admitted; iterate via parens (`-(-a)`) or call `.negate` directly.

Numeric-literal polymorphism (`type_system.md` §5.7) goes through `Self.fn fromI64`: an integer literal in a `Numeric`-bounded position is desugared to `Self.fromI64(LITERAL)`. Float literals desugar to a `fromF64` overload (forthcoming; deferred until float-spec lands).

All built-in primitive numeric types — `i8`/`i16`/`i32`/`i64`/`i128`, `u8`/`u16`/`u32`/`u64`/`u128`, `f32`/`f64` — satisfy `Numeric` (compiler-provided).

**Built today.** The prelude's `Numeric` declares the seven arithmetic members without rows; `+`, `-`, `*` wrap, and none of the members without effects exist (`spec/effects.md` §3.6, "Built today").

### 4.10 `cap Show`

```
pub cap Show:
    fn show -> String
```

Convert a value to a human-readable `String`. `Show` is a lawful capability: `show` on a value of a type parameter needs `T is Show` (`spec/type_system.md` Rule 5.1.3). It is not the diagnostic mechanism. `inspect` and `trace` print a value of any type with no bound (`spec/diagnostics.md`). Auto-derived for `data` types whose every field type satisfies `Show` (component-wise concat with constructor name and parens).

All primitive types (`bool`, integer types, `f32`, `f64`, `String`, `char`, `()`) satisfy `Show` via compiler-built-in formatters.

### 4.11 `cap Clone`

```
pub cap Clone:
    fn clone -> Self
```

`clone` returns a value equal to the receiver (P6 D10): where `Self` also satisfies `Eq`, `x.clone == x`. `Clone` does not extend `Eq`, so the law is stated here and not as a `prop` of the capability. The satisfier chooses how. A **sharing** satisfier returns the same cells with their counts incremented, and states it in a cost prop:

```
    prop cloneShares: constructs(clone) <= 0
```

A **copying** satisfier builds fresh cells, so the result does not keep the receiver's cells alive, and states its bound, for example `prop cloneCopies: constructs(clone) <= size(self)`. Values are immutable, so the two are indistinguishable in results. They differ in cost and in how long the receiver's cells live, and the cost prop tells which one a caller gets (`spec/cost.md` §4).

A type that must not be duplicated does not satisfy `Clone`.

`clone` on a value of a type parameter needs `T is Clone` (`spec/type_system.md` Rule 5.1.3).

**Relationship with ownership inference.** `.clone()` is never needed to satisfy ownership. Demands (`spec/quantities.md` §4.1) decide what each call needs: a `read` parameter borrows, an `own` parameter takes a counted reference, and an `exclusive` parameter given a shared value makes the compiler call the callee's shared version (Rule Q9.3). Only the shared entry of a `fip fn` copies the demanded part. Values are immutable, so a copy is never observable and needs no `Clone`. `.clone()` remains for programs that want an independent value for their own reasons, for example a copying `clone` to bound how long a large structure is kept alive.

In current example code, `.clone()` was used defensively in places where borrow inference is expected to handle the duplication implicitly; the v0.4.5 sweep removed those calls.

**Derivation on request.** `Clone` is never derived silently. A `data` type whose every field type satisfies `Clone` gets the derived `clone` by declaring the satisfaction without a body (`Money satisfies Clone`). Without that declaration the type does not satisfy `Clone`, and that is how a type that must not be duplicated stays so. The prelude declares it for each of its own data types. The derived `clone` copies: it builds a fresh cell of the same variant and clones each field. It states `constructs(clone) <= 1 + ` the sum of what cloning each field builds. An immediate (`spec/compilation_model.md` Rule CM3.5) has no cell, so its derived `clone` builds nothing. Primitives satisfy `Clone` trivially. Capabilities do not auto-derive `Clone`, because the representation is not fixed. A satisfying type must opt in.

### 4.12 Cost contracts (provisional)

The core capabilities carry cost props (`spec/cost.md` §4). Every satisfaction must meet them (Rule CT6.3), and representation selection uses them (`spec/cost.md` §8). `n` is `size(self)` (`spec/cost.md` Rule CT5.1); `f` is the function argument, and `steps(f)` the cost of one call of it. The table is provisional until the library is written in Fixed (implementation plan, Phase 5).

| Capability | Member | `steps` | `constructs` | In place when exclusively owned |
|---|---|---|---|---|
| `Optional`, `Result`, `Pair`, `Ordering`, `Boolean` | constructors, accessors | `O(1)` | at most 1 | — |
| `Optional`, `Result`, `Boolean` | `fold` | `O(steps(f))` | `constructs(f)` | — |
| `Empty` | `empty`, `isEmpty` | `O(1)` | 0 | — |
| `Numeric` | arithmetic, `fromI64` | `O(1)` for the primitive types; no bound stated for user satisfactions | 0 for the primitive types | — |
| `Eq`, `Ord` | `eq`, `compare` | `O(n)` | 0 | — |
| `Show` | `show` | `O(n)` | `O(n)` | no |
| `Clone` | `clone` | at most `O(n)` plus the cost of cloning each element | at most `O(n)` plus what cloning each element builds; a sharing satisfier states `0` | no |
| `Folding` | `fold` | `O(n * steps(f))` | `n * constructs(f)` | — |
| `Functor` | `map` | `O(n * steps(f))` | at most `n + n * constructs(f)` | yes: `constructs(map) − releases(map) <= n * constructs(f)` |
| `Filtering` | `filter` | `O(n * steps(f))` | at most `n` | yes |
| `Sized` | `size` | `O(1)` | 0 | — |
| `RandomAccess` | `get` | `O(1)` | at most 1 | — |

Consequences already visible in `spec/type_system.md` §6.6: the built-in linked list does not satisfy `Sized` or `RandomAccess`, because it cannot meet these bounds, and the contiguous array does. `Clone` states the bound of a copying satisfier. A sharing satisfier is within it and says so with `constructs(clone) <= 0` (§4.11). `Map` and `Set` have no contracts yet (OQ-S2); a hash map needs a decision between its expected and its worst case (`spec/cost.md` OQ-CT4).

### 4.13 Arithmetic effects and their handlers

The prelude declares the effects of `spec/effects.md` Rule E3.6, their alias, and the handlers of Rule E3.6.h. The decision is the owner's (P6, fourth round, "Integer overflow and division"); the names `Arithmetic`, `ArithmeticError`, `arithmeticOrNone`, `arithmeticOrError` and `widen` are the author's choice.

```
pub effect Overflow:
    fn overflow -> !

pub effect DivideByZero:
    fn divideByZero -> !

pub type Arithmetic = Overflow + DivideByZero

pub data ArithmeticError:
    Overflowed
    DividedByZero

pub ArithmeticError satisfies Eq          // derived
pub ArithmeticError satisfies Show        // derived
pub ArithmeticError satisfies Clone

pub fn arithmeticOrNone(body: () -> A with Arithmetic + E) -> is Optional of A with E =
    handle body():
        Overflow.overflow() => Optional.none
        DivideByZero.divideByZero() => Optional.none
        return(v) => Optional.some(v)

pub fn arithmeticOrError(body: () -> A with Arithmetic + E) -> is Result of (ArithmeticError, A) with E =
    handle body():
        Overflow.overflow() => Result.err(ArithmeticError.Overflowed)
        DivideByZero.divideByZero() => Result.err(ArithmeticError.DividedByZero)
        return(v) => Result.ok(v)

pub fn widen(body: () -> A with E) -> A with Overflow + E = ...   // typed and compiled by the compiler
```

- **Rows.** `E` is a row variable (`spec/effects.md` Rule E8.2): the other effects of `body` pass through. A `body` that performs only one of the two arithmetic effects is accepted, since its row is a sub-row of the parameter's (Rule E4.4).
- **To an optional value.** The result of `arithmeticOrNone` is the function's choice point (`spec/type_system.md` Rule 5.1.2); `Maybe` is the prelude's canonical satisfier of `Optional` (§3.5).
- **To a result.** The prelude has no data type named `Either`; the result capability it names is `Result` (§4.2). `Result` has no canonical satisfier, so a program that calls `arithmeticOrError` declares a data type with a `pub` satisfaction of `Result`: the result is the choice point of a prelude function, for which a private satisfaction is no candidate (`spec/type_system.md` Rule 8.6.a).
- **`widen`** is a form, not a handler: it computes the arithmetic of `body` at the next wider integer type and narrows the result once (`spec/effects.md` Rule E3.6.h). Its body is supplied by the compiler, as `inspect`'s is (§5); at `i64` and `u64` it waits for OQ-CM7 (`T026`).
- **At `main`** both effects are handled by the runtime, which ends the program with the position of the operation (`spec/effects.md` Rule E3.6.j).

Not built: the prelude declares none of these (`docs/plans/implementation-plan.md`, Phase 4.2, item 22).

## 5. Prelude satisfactions

The prelude declares these satisfactions, all `pub`. A satisfaction must define every member of its capability that has no default body, by a mapping, a method, auto-derivation (`spec/type_system.md` §7.4) or a built-in. The typer rejects one that leaves a member out (`spec/type_system.md` Rule 6.1.1, P6 D17). The comments say where each member comes from.

```
pub List satisfies Sequencing:
    Cons as cons
    Nil as empty
    fn length -> u64 = ...                  // members of `cap Sequencing` without a default;
    fn head -> is Optional of Part = ...    // bodies arrive with the Phase 5 library
    fn tail -> Self = ...
pub List satisfies Folding              // auto-derived from variants (Rule 7.4.a)
pub List satisfies Functor              // auto-derived per Rule 7.4.c; the active type
                                        // parameter is `A` (List has only one)
pub List satisfies Filtering:           // not auto-derived: §7.4 derives no `Filtering`
    fn filter(pred: Part -> bool) -> Self = ...

pub Json satisfies Show                 // auto-derived (§4.10): every field satisfies Show (§3.2)
pub Json satisfies Eq                   // auto-derived (§4.7): every field satisfies Eq (§3.2)

pub Tuple satisfies Pair:
    Tuple as pair                       // `first` and `second` are the accessors of Rule 7.4.d
pub Tuple satisfies Functor             // auto-derived per Rule 7.4.c with right-bias:
                                        // the active type parameter is `B` (the second
                                        // projection). `tuple.map(f: B -> B') -> Tuple of (A, B')`.

pub Maybe satisfies Optional:           // §3.5
    Just as some
    Nothing as none                     // `fold` auto-derived (Rule 7.4.a); `map` (Rule 7.4.c)

pub Order satisfies Ordering:           // §3.6
    Less as lt
    Equal as eq
    Greater as gt                       // `fold` auto-derived (Rule 7.4.a)

pub bool satisfies Boolean              // built in: `yes` is `true`, `no` is `false`,
                                        // `fold` branches on the value

pub Nat satisfies Folding               // auto-derived from Zero + Succ

pub String satisfies Eq                 // built in, like the `Eq` and `Ord` that the primitive
pub String satisfies Ord                // numeric types have through `Numeric` (§4.9)
pub bool satisfies Eq
pub char satisfies Eq
pub char satisfies Ord

pub String satisfies Clone              // Clone is derived only on request (§4.11):
pub List satisfies Clone                // each declaration, without a body, asks for the
pub Maybe satisfies Clone               // derived `clone`. `Json` declares none until its
pub Tuple satisfies Clone               // fields carry `+ Clone`.
pub Order satisfies Clone
pub Nat satisfies Clone
```

`bool` has no variants, so no mapping can implement `Boolean`'s constructor requirements, and a `satisfies` block admits no `Self.fn` definition (`spec/syntax_grammar.ebnf` §9). The satisfaction is therefore built in, like the `Numeric` satisfactions of the primitive numeric types (§4.9). (Earlier drafts wrote `bool satisfies Boolean of Self` with `Self` mapped to both `yes` and `no`. `Boolean` has no `of` parameter, and one subject cannot implement two requirements.)

The right-bias commitment for `Tuple satisfies Functor` is **normative** (not commentary): per Rule 7.4.c, the active type parameter for auto-derived Functor on a multi-parameter `data` is the rightmost non-phantom one. Combined with Rule 5.4.a/b (right-aligned positional substitution for `Self of B`), this means `tuple.map(f)` consistently maps over the second projection across the spec, the example corpus, and the typer.

**Canonical satisfaction.** A satisfaction is *canonical* when it is declared in its capability's own package. The term means exactly rule 4 of the disambiguation in `spec/type_system.md` Rule 6.5.5: when representation selection finds two candidates of different data types with equal scores, the canonical one wins. It gives no other preference. A program that declares its own satisfying type competes with the canonical one on score. In the prelude, `Maybe` is canonical for `Optional`, `Order` for `Ordering` and `Tuple` for `Pair`. `Result` has no canonical satisfaction: a program that constructs a `Result` through the capability must declare a satisfying data type (`examples/03_option_result.fixed` shows the pattern), or the choice point has no viable candidate (`spec/type_system.md` §6.5.3).

Canonicity never chooses between two satisfactions of the same type and capability. Those differ in results, and only `using` and the scope decide between them (`spec/type_system.md` Rule 8.4). The prelude's satisfactions are always in scope (Rule 8.4).

**Diagnostic sinks.** The prelude also declares the two diagnostic sinks:

```
pub fn inspect(x: T) -> T = ...           // body supplied by the compiler
pub fn trace(message: String) -> () = ... // body supplied by the compiler
```

Being a sink is a property of these two declarations (`spec/diagnostics.md` Rules DG3.1, DG3.2): `inspect` takes a value of any type with no bound, and the argument of `trace` must be a string literal, whose names in braces are interpolated. A program that declares its own `inspect` or `trace` shadows the prelude's and gets an ordinary function.

## 6. Migration from earlier drafts

Earlier drafts (`docs/plans/implementation-plan.md` line 352) classified `List`, `Ordering`, `Pair`, `Json` all as data types. The corrected classification:

| Was        | Now                                                                 |
|------------|---------------------------------------------------------------------|
| `data List`     | unchanged (still data)                                             |
| `data Json`     | unchanged (still data)                                             |
| `data Pair`     | replaced by `cap Pair` + new `data Tuple` (its canonical satisfier, §5) |
| `data Ordering` | replaced by `cap Ordering`; `data Order` is its canonical satisfier (§3.6) |

### 6.1 What changes in user code

- **`Pair { first: a, second: b }` destructure patterns.** These are struct-destructure patterns (`spec/pattern_matching.md` Rule M3.6.b) and require a single-variant data type. Replace with `Tuple { first: a, second: b }` if the pair is a `data Tuple` value. If the binding is `is Pair of (A, B)` (a cap-bound), use the accessor methods instead: `let a = p.first` and `let b = p.second`.
- **`Pair(a, b)` construction.** Replace with `Tuple(a, b)` for direct construction. To construct via the cap (so the compiler can pick the representation), write `Pair.pair(a, b)`.
- **`is Pair of (A, B)`.** Continues to work — `Pair` is now a cap, so `is Pair` is the natural form.
- **`match ord: Ordering.Less => ... | Ordering.Equal => ... | Ordering.Greater => ...`.** No longer valid on a cap. Rewrite as `ord.fold((_) -> ..., (_) -> ..., (_) -> ...)` or, on a value whose type is `Order`, match on `Order.Less`, `Order.Equal` and `Order.Greater`.

### 6.2 Why the change

`Pair` and `Ordering` are abstract — there's no specific layout the compiler should commit to. Treating them as caps lets:
- a program pick another representation of `Ordering` than the prelude's `Order` where usage favours it (`spec/type_system.md` §6.5)
- a key-value map use whatever pair layout is most efficient for its backing storage
- foreign data types implement these caps without needing to be a literal `Tuple` or built-in enum

`List` and `Json` are *concrete* — programmers really do match on `Cons`/`Nil` and on the six JSON variants. They stay as data.

## 7. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | Auto-derivation rules (Rule 7.4); cap classification (§6.4); satisfaction resolution (§8) |
| `spec/pattern_matching.md` | Match restricted to data types (Rule M3.4.c); `Tuple` matchable, `Pair` not |
| `spec/effects.md` | I/O effects (`Console`, `FileSystem`, …) — separate module |
| `spec/cost.md` | The contract language of §4.12; viability and scoring of representations |
| `spec/compilation_model.md` | Capability tables are built from these declarations (Rule CM4.2); the standard library is a package (§6) |
| `docs/plans/implementation-plan.md` | Phase 5 deliverables in `stdlib/*.fixed` |
| `examples/03_option_result.fixed` | `data Maybe satisfies Optional`, `data Outcome satisfies Result` — typical user satisfactions |

## 8. Open questions

- **OQ-S1 — Built-in `Show`.** Should `bool`, `i64`, `f64`, etc. auto-satisfy `Show`? Most programs need `.show()` constantly; refusing the default is friction. Provisional yes, formalize once `cap Show` lands.
- **OQ-S2 — Map/Set.** `cap Map of (K, V)` and `cap Set of A` are mentioned in examples but not yet specified here. Defer to a Phase-5 stdlib doc.
- **OQ-S3 — `String`.** Currently ambiguous: a primitive in the grammar (`PrimitiveType`), but example 11 declares refinements (`type Username = String + minLength(3)`) that expect cap-like composition. Decision: `String` is a primitive *and* satisfies a `cap Stringlike` (or similar) for refinement purposes. Formalize separately.
