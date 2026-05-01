# Fixed Type System

**Status:** Draft v0.4.2
**Specifies:** typing of Fixed v0.4.4 source.
**Last revised:** 2026-05-01

**Changes from v0.4.1.** §8 reframed to make the *capabilities-declare-requirements, types-provide-constructors* distinction explicit (new §8 intro). §8.1 wording updated (Rule 8.1.a / 8.1.b refer to "constructor requirements"). §8.2 (`impossible`) example replaced with a self-contained `Either` / `AlwaysRight` pair to avoid ambiguity between the simplified `Optional` and the canonical one. **Rule 8.2 weakened**: `impossible` is no longer statically verified to be unreachable — calls to an impossible-mapped constructor compile to runtime panics rather than compile errors. Users who need static guarantees may add a `prop` excluding the offending path. Wording across §6.4, §6.6, §7.4.f tightened to "constructor requirements" instead of "constructors".

**Changes from v0.4.** `phantom` is no longer a keyword; phantom-ness of type parameters is inferred from usage (new §5.9). §6.5 representation-selection algorithm expanded from a one-paragraph sketch to a normative procedure with inputs, candidate pool, viability filter, scoring, ambiguity handling, phase placement, and caching. Rule 6.6 (priority) updated previously to put built-ins on equal footing with user-`satisfies` types — see §6.5.4 / 6.5.5 for how the equal priority resolves through scoring.

**Changes from v0.3.** Body introducer for fn/method definitions changed from `:` to `=` (block introducers `:` reserved for cap/data/effect/satisfies bodies and control-flow branches; definitions use `=`). Cap instance and static methods may carry default bodies (Rule 6.1). Lambda parameters gain optional types and defaults. EffectMember gains `WithClause?` and `TypeParamsHint?`. TupleExpr/TuplePattern simplified. Code examples updated.

**Changes from v0.2.** Code examples updated to the v0.3 indentation-based syntax (no block braces; `:` introduces blocks). No semantic rules changed.

**Changes from v0.1.** OQ1 resolved (multi-arg `Self of B`, Rule 5.4.b); OQ2 resolved (mutual data recursion, §7.5); OQ3 resolved (auto-derivation matrix, §7.4 expanded); OQ4 resolved (built-in representation candidates, §6.6); OQ5 resolved as deferred-to-v0.3 (§7.6); OQ6 resolved (type-alias `of`-distribution, §5.8); OQ7 pre-resolved and deferred to `spec/properties.md`.

## 1. Scope

This document defines the **typing rules** for Fixed: how the parser's untyped trees become typed trees, how capabilities and data types relate, how `of` application works, and how constructor calls resolve to concrete representations.

It does **not** define:

- QTT quantity inference (0/1/ω, erasure, linearity) — see `spec/quantities.md`.
- Effect-row inference and evidence-passing compilation — see `spec/effects.md`.
- `prop` static verification and PBT fallback — see `spec/properties.md`.
- `match → fold` desugaring and exhaustiveness checking — see `spec/pattern_matching.md`.
- Perceus reference-counting insertion — see `spec/perceus.md`.
- Capability-set narrowing into concrete representations (the algorithm) — touched in §4.5; full treatment lives with the compiler implementation, with PGO-driven decisions documented separately.

## 2. Dependencies

- `spec/syntax_grammar.ebnf` v0.2.3 — every syntactic form referenced here is defined there.
- `examples/01_basics.fixed` … `examples/11_properties.fixed` — every rule in this document is anchored to at least one example.

## 3. Two worlds: types and capabilities

Fixed makes a hard distinction between what the **programmer writes** and what the **compiler tracks internally**:

| Surface (programmer) | Internal (compiler) |
|---|---|
| **Capabilities** — what a value can *do* (`Sequencing`, `Numeric`, `Folding`) | **Types** — concrete representations the compiler chose |
| Capability bounds: `is Cap`, `N is Cap` | Type variables, type-class dictionaries |
| `data` declarations: closed sets of variants | Tagged unions / structs |
| `of` application | Type application |

A user never names "the type" in the conventional sense. The compiler computes a concrete representation from the union of capability bounds collected across a value's lifetime (see §4.5). For the rest of this document, "type" refers to the compiler's internal view; "capability" is the user-facing thing.

## 4. Identifier scoping

### 4.1 Type-variable introduction (resolves Q8)

**Rule 4.1.** An `UPPER_IDENT` in a type-binding position introduces a fresh type variable iff that name is not already bound in the enclosing scope. A **type-binding position** is any of:

- `IDENT is Bound` (named alias) — anywhere in a function signature, cap method signature, data variant field, or `of` argument list.
- A bare `UPPER_IDENT` in an `of` argument list (declares a type parameter; phantom-ness is inferred per §5.9).
- A free occurrence of `UPPER_IDENT` that does not refer to a globally-declared cap, data, type, or effect.

Subsequent occurrences of the same `UPPER_IDENT` within the same signature **refer to the same type variable**. Intra-signature shadowing is a **compile error**.

**Scope.**

- For `fn`: the entire signature (parameters + return type + `with`-clause).
- For `cap` / `data` / `effect`: the entire body (including `extends` clauses).
- For nested `fn`: each inner function has its own scope; type variables introduced in the outer signature are still visible.

**Examples.**

```
// `examples/01_basics.fixed:30`  —  N introduced once, referenced twice
fn fib_iter(n: N is Numeric) -> N: ...
//          ^introduces      ^refers

// `examples/05_phantom_types.fixed:110-112`  —  U introduced once, referenced 3x
fn add(a: is Quantity of U, b: is Quantity of U) -> is Quantity of U: ...
//                       ^introduces, then 2 refs

// `examples/02_collections.fixed:127-129`  —  N introduced inside the `of`
fn sum(numbers: is Folding of (N is Numeric)) -> N: ...

// Cap method type variables introduced by free UPPER_IDENT, no explicit
// `<R>` needed (the bare `R` is a fresh type variable per Rule 4.1):
cap Folding:
    fn fold(init: R, f: (R, Part) -> R) -> R   // R introduced once at first use
```

**Non-example.** The following is rejected:

```
fn bad(x: N is Numeric) -> N =
    fn inner(x: N is Numeric) -> N: ...      // ERROR: N already in scope
```

### 4.2 Term-variable scoping

`let pat = expr` binds the names in `pat` for the rest of its enclosing block. There is no `let-in`; bindings flow through the block sequentially. A function parameter is in scope for the entire function body (and for nested `fn` definitions, since nested functions can capture).

## 5. Type expressions

The grammar of type expressions is defined in `spec/syntax_grammar.ebnf` §10. This section defines their meaning.

### 5.1 Capability bounds (`is`)

`is Cap` denotes "satisfies the capability `Cap`."

Two forms:

| Form | Meaning |
|---|---|
| `is Cap` | Anonymous bound: each occurrence in a signature is a *fresh* type variable. |
| `IDENT is Cap` | Named alias: every occurrence of `IDENT` in the signature refers to the *same* type variable, all of which must satisfy `Cap`. |

**Composition (§10 grammar `CapBoundChain`):** `is C1 + C2` means "satisfies both `C1` and `C2`." `+` is associative and commutative; `is C1 + C2` ≡ `is C2 + C1`.

```
fn count(c: is Folding) -> u64                          // anonymous: any Folding
fn fib_iter(n: N is Numeric) -> N                       // named: input and output share type
fn even_doubled(numbers: C is Filtering of (N is Numeric) + Functor of N) -> C
//              ^reused C    ^reused N as element type
```

### 5.2 `of` application

`Cap of A` applies `A` to `Cap`'s first type parameter. Multi-parameter caps use a tuple: `Cap of (A, B)`.

```
cap Sized of (Part, Size is Numeric)           // declares two params
fn size_of(c: is Sized of (i64, u64)) -> u64    // applies both
```

`of` arguments may be:

- Type expressions (`i64`, `String`, `Cap of A`, `N is Numeric`, …). A bare `UPPER_IDENT` at a declaration site introduces a type parameter, whose phantom-ness is inferred per §5.9.
- Value expressions when the corresponding parameter is a value-param (see §6.3 for data, §5.4 for `Self of B`).
- Literal values at type-application sites (so `Bounded of (i64, 5, 10)` parses).

### 5.3 The `Part` pseudo-parameter

Inside a `cap` body, the bare identifier `Part` refers to the cap's element-type parameter — the *first* parameter of the cap's `of` clause. If the cap declares no `of` clause, `Part` is the cap's single implicit element-type parameter.

```
cap Folding:                                   // no `of` — Part is the implicit element type
    fn fold(init: R, f: (R, Part) -> R) -> R

cap Sized of (Part, Size is Numeric):          // Part named explicitly in `of`
    fn size -> Size
```

**Rule 5.3.** For a `cap` declaration, `Part` resolves to the **first non-phantom type parameter** in the cap's `of` clause. If no such parameter exists, the cap is **`Part`-less**: using `Part` inside its body is a compile error.

### 5.4 `Self` and `Self of B`

Inside a `cap` body, `Self` refers to the type implementing this capability. Inside a `data` declaration, `Self` refers to the data type itself (with all its `of` arguments fixed). Inside a `satisfies` block, `Self` refers to the satisfying type.

`Self of B` denotes "the same container shape as `Self`, but with element type `B`" — used for higher-kinded returns:

```
cap Functor:
    fn map(f: Part -> B) -> Self of B          // B introduces a fresh type variable
```

**Rule 5.4.a (single-arg).** `Self of B` is well-formed only when `Self` has at least one non-phantom type parameter. The application substitutes `B` for `Self`'s **rightmost non-phantom type parameter** — the right-bias convention, consistent with Rule 7.4.c (Functor auto-derivation maps over the rightmost). Other parameters are preserved.

**Rule 5.4.b (multi-arg, resolves OQ1).** `Self of (B1, B2, ..., Bk)` substitutes `B1..Bk` positionally over the **last `k` non-phantom type parameters** of `Self` (right-aligned positional substitution). The arity `k` must be at most the number of non-phantom parameters of `Self`; supplying more is a compile error.

```
data Tuple of (A, B):
    Tuple(first: A, second: B)

// Tuple satisfies Functor (right-bias: over B):
//   fn map<B'>(f: B -> B') -> Self of B'
//   = fn map<B'>(f: B -> B') -> Tuple of (A, B')

data Triple of (X, Y, Z):
    Triple(a: X, b: Y, c: Z)

// `Self of (Y', Z')` substitutes the last 2 non-phantom params:
//   = Triple of (X, Y', Z')
```

**Rationale for right-bias.** Earlier drafts (≤ v0.4.4) substituted left (first non-phantom). That rule was inconsistent with Rule 7.4.c, which auto-derives Functor over the rightmost type parameter, and with the corresponding stdlib commitment (`Tuple satisfies Functor` over the second projection — see `spec/stdlib.md` §5). v0.4.5 unifies the conventions: every "active parameter" choice in Fixed is right-biased.

```
cap BiFunctor of (A, B):
    fn bimap<A2, B2>(f: A -> A2, g: B -> B2) -> Self of (A2, B2)
```

**Rule 5.4.c (the `self` receiver value).** Inside an *instance method body* (a `fn name(...)` with an optional default body declared in a cap, or an `fn name(...) = body` declared in a `satisfies` block), the lowercase identifier `self` is an **implicit binder for the receiver value** — the value the method was called on. Its static type is `Self` (the cap's abstract receiver type at cap-declaration sites; the satisfying type at `satisfies` sites). The capital `Self` continues to denote the *type*; lowercase `self` denotes the *value*.

`self` is **only** bound in instance method bodies. In static method bodies (`Self.fn name(...) = body`) there is no receiver, so `self` is unbound — using it is a compile error. Outside method bodies, `self` is a normal identifier with no special meaning.

```
cap Optional:
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    //                     ^^^^ receiver value, type Self

    Self.fn none -> Self                  // static — no `self` available
    fn map<B>(f: Part -> B) -> Self of B  // declaration only, no body
```

Inside a `match`/`fold` discriminator, `self` is the natural scrutinee:

```
Maybe satisfies Optional:
    fn isDefined = match self:
        Maybe.Just(_) => true
        Maybe.Nothing => false
```

This rule supersedes any earlier convention in the example corpus that used capital `Self` as a value (e.g., older versions of `examples/03_option_result.fixed`). Such uses were corrected in the v0.4.5 sweep; capital `Self` as a value-position identifier is no longer supported.

### 5.5 Arrow types and the unit type

| Syntax | Meaning |
|---|---|
| `A -> B` | Function from `A` to `B` |
| `(A, B) -> C` | Function from a 2-tuple `(A, B)` to `C` (curried by the typer if the call site curries) |
| `() -> R` | Function from unit to `R` (zero-arg function) |
| `A -> B with E` | Arrow type carrying effect row `E` (defined in `spec/effects.md`) |
| `()` | The unit type. The unit value is also written `()`. Single-element type and value. |

### 5.6 The never type `!` and `unreachable` (resolves Q16)

`!` is the **never type** — the type of expressions that do not return. It is a **subtype of every type**: an expression of type `!` is valid wherever an expression of any other type is expected.

Two ways to obtain `!`:

- `unreachable` — keyword expression. Reaching it at runtime is a panic.
- A function returning `!` — by convention, an effect operation that does not return (e.g., `Fail.fail`).

```
// examples/03_option_result.fixed:56  —  `Fail.fail` returns `!`
effect Fail of E:
    fn fail(error: E) -> !

// examples/11_properties.fixed:138  —  `unreachable` used where `N` is expected
fn median(...) -> N =
    collection.get(mid).fold((v) -> v, () -> unreachable)
```

### 5.7 Numeric literal polymorphism (resolves Q10)

`INT_LITERAL` carries the polymorphic type `is Numeric`. `FLOAT_LITERAL` likewise (further constrainable to a floating-point sub-cap if/when the standard library declares one).

The typer resolves the concrete representation from context:

1. The expected type at the literal's position (function parameter, return position, assignment LHS, comparison operand).
2. Operand-unification: in `a + 1`, `1` resolves to whatever `a`'s type is.

If still unresolved, **defaults** apply:

- `INT_LITERAL → i64`
- `FLOAT_LITERAL → f64`

### 5.8 Type-alias `of`-distribution (resolves OQ6)

A type alias `type T = C1 + C2 + ... + Cn` is a bag of capabilities. When applied with `of` arguments, the application **distributes** over each constituent.

**Rule 5.8.** `is T of (X1, X2, ..., Xk)` where `type T = C1 + ... + Cn` is equivalent to:

- For each `Ci` that has at least one non-phantom type parameter: `Ci of (X1, ..., Xk)` (positional substitution per Rule 5.4.b, capped at `Ci`'s declared arity).
- For each `Ci` that has no type parameters: `Ci` unchanged.

```
type ArrayLike = Sequencing + RandomAccess + Sized + Empty

// `is ArrayLike of A` distributes to:
//   Sequencing of A + RandomAccess of A + Sized of A + Empty
// (Empty has no type parameters; passes through unchanged.)
```

If a constituent `Ci` declares more type parameters than the alias receives, the unspecified parameters remain **abstract** (fresh type variables introduced at the use site, scoped to the same signature).

`of`-distribution applies before `extends`-flattening: an alias's `of` arguments are propagated to its constituent caps before those caps' inherited members are inlined into the obligation set.

### 5.9 Phantom type parameters (inferred)

A type parameter declared in the `of` clause of a `data`, `cap`, or `effect` declaration is **phantom** if it is unused at runtime — that is, if it does not appear in:

1. Any constructor field type (for `data`).
2. Any method-signature parameter or return type (for `cap` / `effect`).
3. The signature of any inherited member (transitively via `extends`).
4. Any `prop` expression in the declaration.
5. The signature of any default-bodied method's body (for `cap`'s default implementations per Rule 6.1).

Phantom type parameters are erased at runtime (quantity 0). They serve only to distinguish instances at the type level — e.g., `Door of Locked` vs `Door of Unlocked`.

**Rule 5.9 (Phantom inference).** Phantom-ness is **inferred by the typer**, not declared via syntax. The grammar carries no `phantom` keyword (removed in v0.4.1). A type parameter `X` is phantom iff none of (1)–(5) above transitively reference `X`.

**Distinction from quantity-0 `of` value-params (§7.3.b).** Both phantom type parameters and `of` value-params are quantity 0 (erased at runtime), but they live at different levels:

| | Phantom type parameter | `of` value parameter |
|---|---|---|
| What it is | A *type* | A *value* (of some type) |
| What you supply at the use site | A type: `Door of Locked` | A value: `Bounded of (i64, 5, 10)` |
| What you can extract | Nothing — the parameter has no associated value | A compile-time constant via `b.lo` |
| Runtime presence | None — purely a type-level discriminator | None — materialised on access as a static constant |

Both are erased; only the value-param has an extractable value.

**Examples.**

```
// State, Tag are phantom (unused at runtime); Value is non-phantom (used in Tagged's field).
data Tagged of (Tag, Value):
    Tagged(value: Value)

cap Door of (State):                            // State is phantom
    Self.fn new -> Self
    fn name -> String

cap Sized of (Part, Size is Numeric):           // both Part and Size are non-phantom
    fn size -> Size                              //   (Size appears in fn signature; Part is referenced
                                                 //    by `Part`-using inheritors via §5.3)
```

## 6. Capability declarations

### 6.1 Members

A `cap` declaration may contain three kinds of members (see grammar §4):

- **Instance methods** (`fn name(...) -> T`): called on a value satisfying the cap.
- **Static methods** (`Self.fn name(...) -> Self`): called on the cap or a satisfying type. Used to declare *constructor requirements*: any type satisfying the cap must provide these.
- **Properties** (`prop name: expr`): invariants the type must uphold (see `spec/properties.md`).

Instance and static methods may carry an optional body (v0.4):

```
cap Optional:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold<R>(on_some: Part -> R, on_none: () -> R) -> R

    // Default implementations — types satisfying Optional may override.
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    fn or_else(default: Part) -> Part = self.fold((v) -> v, () -> default)
```

**Rule 6.1 (Default method bodies / extension methods).** A cap method's optional body acts as both a *default implementation* — used when the satisfying type does not provide its own — and an *extension method*: every type satisfying the cap automatically gets the method as a callable, even without writing one in `satisfies`. Bodies may use `self` (the receiver value, lowercase — see Rule 5.4.c), `Self` (the receiver's type), `Part`, and other in-scope cap members (including the cap's `Self.fn` constructor *requirements* and abstract method *requirements*, which are guaranteed to be provided by the satisfying type).

#### 6.1.1 Default body typing (Rule 6.1.1) — resolves X5

A cap method's default body is type-checked in **two phases**, layered to balance early error detection against per-satisfaction precision.

**Phase 1 — Abstract-Self typing (declaration time).** The body is type-checked once when the cap is declared, treating `Self` as an opaque type with the cap's declared methods available, `Part` as an opaque element type, and abstract method requirements visible by their declared signatures. The body must type-check using only what the cap promises. This catches:

- Method-call mismatches against the cap's declared interface
- Type errors against the abstract `Self` (e.g. calling a method the cap does not declare)
- Argument-arity mismatches against `Self.fn` constructor requirements

A body that fails Phase 1 is a compile error against the cap declaration, not against any particular satisfying type.

**Phase 2 — Per-satisfaction Recheck (cap-closure phase).** When a `Type satisfies Cap` declaration is processed, the cap's default bodies are **re-checked** against the satisfying type's concrete signatures, but only for *quantities* (per `spec/quantities.md` §5.12) and *effects* (per `spec/effects.md`). Types are guaranteed compatible by cap-closure (the satisfaction is rejected at this phase if the satisfying type's signatures are not assignable to the cap's declared signatures). The re-check therefore catches:

- Quantity mismatches: the satisfying type's `fold` is quantity 1 in some position, and a default body's call expects quantity ω there (or vice versa).
- Effect-row mismatches: the satisfying type's method performs effects the default body's `with`-clause does not declare.

A body that passes Phase 1 but fails Phase 2 is a compile error against the *satisfaction* (not the cap declaration), with the satisfying type named in the diagnostic.

**Why two phases.** Phase 1 catches obvious bugs early and gives cap authors actionable errors at the cap-declaration site, without requiring N satisfying types to be in scope. Phase 2 covers the gap Phase 1 leaves — quantities and effect rows are not visible at the abstract-`Self` level, so per-satisfaction visibility is required to catch their mismatches. This pattern mirrors Scala 3's `cc/` capture-checker and is consistent with the existing Recheck-phase architecture for quantities (`spec/quantities.md` §5.12) and representation selection (§6.5.7 of this doc).

```
cap Optional:
    Self.fn some(v: Part) -> Self
    Self.fn none -> Self
    fn fold<R>(on_some: Part -> R, on_none: () -> R) -> R
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    //                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //   Phase 1 typing: self : Self (abstract); fold available with declared signature; OK.
    //   Phase 2 (per satisfaction): if Maybe.fold runs at quantity ω with no effects (typical),
    //     isDefined inherits the same and passes; if a satisfying type's fold runs with an
    //     effect, isDefined for that satisfaction is rejected unless `with` is widened.
```

### 6.2 `extends` inheritance

`cap A extends B + C` declares that any type satisfying `A` must also satisfy `B` and `C`. All members of `B` and `C` are inherited by `A`.

```
cap Optional extends Functor + Folding: ...    // examples/02_collections.fixed:60
```

**Rule 6.2 (Diamond resolution).** If `A` inherits the same member name from two different parents `B` and `C`, the inherited member's signatures must be compatible (one must be a subtype of the other under §7's subtyping). If they are not compatible, `A` must declare an explicit member of that name with a fresh signature.

### 6.3 Sugar: `cap Name(params)`

A capability with value parameters is sugar for a function returning a capability:

```
cap Between(min: N is Ord, max: N) of N:
    prop in_range: min <= Self && Self <= max

// is equivalent to

fn between(min: N is Ord, max: N) -> cap of N =
    prop in_range: min <= Self && Self <= max
```

Both forms produce a *cap-generating function* (see `spec/properties.md` for the prop semantics; this document treats `fn -> cap` as a regular function whose return type happens to be a refinement capability).

#### 6.3.0 Use-site bridge: `cap of T` ↔ `RefinementCall` (Rule 6.3.0) — resolves C3

A function `fn f(args) -> cap of T` (or `fn f(args) -> cap extends C`) is a **cap-generating function**. Its declaration site uses the `cap`-prefixed return-type form (per grammar `FnReturnType` and `CapType`). Its **use sites** in `is`-bound position do **not** repeat the `cap` keyword — instead, they call the generator by name as a `RefinementCall`:

```
fn between(min: N is Ord, max: N) -> cap of N =
    prop in_range: min <= Self && Self <= max

// Use site — call the generator by name, no `cap` keyword:
fn validate_age(age: u8 is between(0, 150)) -> u8 = age
//                       ^^^^^^^^^^^^^^^^ RefinementCall (per grammar §10)
```

Per the grammar (`syntax_grammar.ebnf` §10 — `CapBound = CapRef | RefinementCall | PrimitiveType`), a `RefinementCall` is exactly `LOWER_IDENT "(" ArgList? ")"` — the lowercase generator name applied to value-args. The result is a refinement cap that the typer treats as if it were a Marker-classified cap with the generator's prop body as obligation.

**Higher-order positions.** When a cap-generator value is passed as an argument or stored as a binding, its *type* requires the `cap of T` form (per Rule S0.2 / grammar's `CapType` admitted as a `TypeAtom`):

```
fn validate_with(
    value: N is Numeric,
    constraint: (N, N) -> cap of N,    // higher-order parameter type
    lo: N,
    hi: N,
) -> N is constraint(lo, hi) = ...
```

The parameter `constraint` has type `(N, N) -> cap of N`. When applied at the use site (`is constraint(lo, hi)`), the call is a `RefinementCall` with `constraint` as the (now value-bound) generator name.

Rule 6.3.0 establishes the equivalence:

```
fn f(...) -> cap of T = body      ≡      f as a value of type (...) -> cap of T
```

Both forms type-check identically; the `cap`-prefixed return form is preferred when declaring a generator directly, the higher-order `cap of T` form is required only when a function value (parameter, binding, return) carries a generator's *type*.

#### 6.3.1 Cap identity (Rule 6.3.1) — resolves X7

Two cap-generator applications produce the **same cap** iff:

1. They invoke the same generator (after `type`-alias expansion to the underlying generator), and
2. Their value-args are equal under symbolic comparison (literal equality for literals; equal-binding identity for in-scope bindings, conservatively).

Different generators always produce different caps even if their prop bodies are syntactically identical. Equality is **nominal** at the (generator-name, value-args) tuple, not structural over prop bodies.

```
fn between(lo: N is Ord, hi: N) -> cap of N = prop r: lo <= Self && Self <= hi
fn alt_between(lo: N is Ord, hi: N) -> cap of N = prop r: lo <= Self && Self <= hi
// Even though the bodies match, `between(0, 10)` and `alt_between(0, 10)`
// are *different* caps. A value of one is not assignable to a binding of the other.

type alt_between = between
// `alt_between` is now a type-alias for `between`, so `alt_between(0, 10)` *is*
// `between(0, 10)` (same generator after alias expansion).
```

Type-arg equivalence follows the standard type-equivalence rules (§5).

This nominal rule is decidable, predictable, and forward-compatible: a future relaxation to structural prop equivalence (e.g., via SMT) would only widen the equivalence relation and never break existing assignment compatibility.

#### 6.3.2 Auto-derivation on cap-generator returns (Rule 6.3.2) — resolves L4

Cap-generator returns are **Marker** classification (per §6.4 — no methods, only `prop` obligations). Auto-derivations from §7.4 (Functor, Folding, accessors, etc.) **never apply** to a cap-generator's return cap. Auto-derivation operates on the *base type* the refinement narrows, not on the refinement cap itself.

```
// `is Functor of (i64 + between(0, 10))` — Functor auto-derives over the
// underlying `i64`'s structure (well, `i64` isn't a Functor-bearing type;
// substitute a List-of-i64 here). The `between(0, 10)` refinement adds an
// obligation but does not change which auto-derivations apply.

// `is pair_constraint(0, 100)` where `pair_constraint(lo, hi) -> cap of (X, Y)`
// returns a multi-param refinement — does **not** auto-derive Functor.
// Programs needing Functor over a multi-param refinement must declare a
// named cap with explicit type parameters; Rule 7.4.c then applies normally.
```

Right-bias (Rule 7.4.c) is therefore irrelevant to cap-generator returns: there is no auto-derivation site to apply it to.

#### 6.3.3 Termination of cap-generator value-args (Rule 6.3.3) — resolves L5

Type-checking a signature that mentions `f(arg1, arg2, ...)` (a cap-generator call in `is`-bound position) requires evaluating the value-args at compile time so the resulting prop obligations can be discharged. Termination is guaranteed by restricting what may appear as a value-arg.

**v0 rule (Fixed v0.4.5).** A cap-generator value-arg expression is admissible iff every leaf is one of these forms and every internal node is a closed-form arithmetic operator over them:

| Admissible leaf | Example |
|---|---|
| `LiteralExpr` per the grammar — primitive literals (`0`, `1024`, `"abc"`, `true`, `'c'`, `()`) | `between(0, 100)` |
| `LOWER_IDENT` bound by a `let` whose initializer is itself a v0-admissible expression (transitively) | `let upper = 150` then `between(0, upper)` |
| `LOWER_IDENT` bound by a `let` whose initializer is another v0-admissible binding | `let lo = 0; let upper = 150; between(lo, upper)` |

| Admissible internal node | Example |
|---|---|
| Closed-form arithmetic over admissible operands: `+`, `-`, `*`, `/`, `%` (compile-time constant-foldable) | `between(0, 2 * upper)` |

Not admitted in v0:
- Function calls (`config.max_age()`)
- Field access on bindings (`config.max`)
- `match`/`if` expressions
- Capability-method calls
- Module-level constant references that aren't covered by the above (a `let` at module scope with a literal initializer **is** admitted; anything else isn't)

Programs that need richer computed bounds must lift the computation to a `let` whose initializer satisfies the v0 rule, or wait for v1 (totality-gated, below).

**v1 rule (target — Phase 3 totality).** Once the function-totality checker lands in Phase 3, the v0 rule relaxes to: any expression whose evaluation is *total* per the function-totality rule may be a cap-generator value-arg. Because Fixed already requires every `fn` to be total (recursion-via-fold-only, no self-recursion), this relaxation makes every fn call a safe cap-generator value-arg.

The v0→v1 promotion is purely a relaxation; programs valid under v0 are valid under v1.

```
// v0 — accepted:
fn validate_age(age: u8 is between(0, 150)) -> u8 = age
let upper = 150
fn validate_age2(age: u8 is between(0, upper)) -> u8 = age   // upper is a literal-initialized binding

// v0 — rejected (computed bound):
let upper = config.max_age()
fn validate_age3(age: u8 is between(0, upper)) -> u8 = age   // error: not a literal

// v1 (when totality lands):
// All three forms are accepted; the totality checker certifies `config.max_age()`.
```

Tracking gate: see implementation-plan.md Phase 3 totality-checker delivery.

### 6.4 Capability classification

Every cap declaration is assigned exactly one **classification** based on its member shape. The classification drives representation selection (§4.5) and the legal operations on values bounded by the cap.

| Classification | Detection rule | Examples |
|---|---|---|
| **Sum** | At least one `Self.fn ctor(...) -> Self` constructor requirement plus one `fn fold<R>(...)` member | `Optional`, `Result`, `Expr` |
| **Product** | Exactly one `Self.fn ctor(...) -> Self` constructor requirement, plus accessor methods (`fn field -> T`) and no `fold` | `Config` (when expressed as a cap), `Message` |
| **Recursive** | Any `Self.fn` constructor requirement whose parameter list contains `Self` | `Sequencing` (via `cons(h, t: Self) -> Self`) |
| **Capability-only** | No `Self.fn` constructor requirements; only instance methods | `Folding`, `Functor`, `Filtering`, `Sized`, `RandomAccess` |
| **Marker** | No methods at all (possibly with `prop` declarations) | `Locked`, `Open`, `Meters`, `Seconds`; refinement caps `between(lo, hi)` |

A cap may belong to multiple of these categories transitively via `extends` (e.g., `Sequencing` is recursive *and* capability-only via the inherited members of `Folding`). The classification of the **declaring** cap is the union: the compiler treats it as "having sum/product/recursive shape" exactly when its own declared members trigger the rule.

**Note.** Refinement caps generated by `fn name(...) -> cap of T` are always **Marker** caps. They add `prop` obligations but no methods, and impose no representation constraints. A value `N is between(0, 100)` is still represented as `N`.

### 6.5 Representation selection

The representation-selection pass determines, for each value (binding, parameter, return), the concrete representation the compiler will use to lay out that value at runtime. It runs as a **Recheck-style phase** (see `docs/references/scala3-compiler-reference.md` §5.4 for the pattern) after capability closure and quantity inference, and before code generation.

#### 6.5.1 Inputs

For each value, the pass collects:

- **Bound set** — the union of all `is`-bounds the value must satisfy across its lifetime: declaration site, assignment LHS, parameter positions, return positions. Closed under `extends` (§6.2) and alias distribution (§5.8).
- **Quantity context** — the QTT quantity assigned to the value (§7.3.b, `spec/quantities.md`). Quantity 1 (linear) opens stack-allocation possibilities; quantity ω requires representations compatible with Perceus reference counting.
- **PGO data** — when available, profile observations from prior runs of the program or its predecessors.

#### 6.5.2 Candidate pool

The pool aggregates two sources:

- **User-defined data types.** For every `T satisfies C` declaration in scope, `T` is a candidate when C ∈ bound set. (Or transitively, when C is inherited by some C' ∈ bound set.)
- **Built-in candidates** — the §6.6 pool: linked list, contiguous array, hash map, auto-tagged-union, auto-flat-struct. Each carries a precondition expressing which bound sets it can satisfy.

Per Rule 6.6, built-in and user-`satisfies` candidates are at **equal priority** at this stage; the choice is made by the scoring function in §6.5.4.

#### 6.5.3 Viability filter

A candidate is **viable** for the bound set iff every C ∈ bound set is satisfied by:

- (a) An in-scope `satisfies` declaration on the candidate (data types), or
- (b) The candidate's inherent structural properties (built-ins), or
- (c) An auto-derivation rule of §7.4 (`Folding`, `Functor`, accessors, …, for data types), or
- (d) A default body declared on the cap method itself (Rule 6.1 — extension methods).

Refinement caps (Marker class, §6.4) are viable iff their `prop` obligations verify under the candidate's representation. Verification follows `spec/properties.md`'s static-then-PBT pipeline; a failed prop disqualifies the candidate.

If zero candidates are viable, emit `error[E060]: no representation satisfies <bound set>`, listing each cap and the candidate(s) that came closest.

#### 6.5.4 Scoring

For each viable candidate, compute a **performance score** as a weighted linear combination over these dimensions:

- **Allocation cost** — bytes per instance and allocator pressure. Stack > arena > heap-with-RC-header.
- **Access fit** — how well the candidate's strengths match the operations actually invoked on the value: `RandomAccess + Sized` ⇒ array beats list; sequential-fold ⇒ either acceptable; key-lookup ⇒ hash-map beats list.
- **Memory footprint** — total bytes including headers, padding, and discriminator tags, per typical instance.
- **Locality** — contiguous layouts score higher for fold-heavy use; pointer-chased layouts lose cache lines and amortise across allocator slabs.
- **PGO hit rate** — when profile data is present, weight the operations the profile shows dominate.

**Normative weights and tie threshold (Rule 6.5.4 — resolves C4).** The default weights for the scoring function are fixed at the spec level for cross-implementation determinism:

| Dimension | Weight | Range of raw score |
|---|---|---|
| Allocation cost | 0.30 | 0–10 (lower allocation cost ⇒ higher raw score) |
| Access fit | 0.30 | 0–10 (perfect match ⇒ 10; mismatch with workaround ⇒ 0–5) |
| Memory footprint | 0.15 | 0–10 (smaller is higher) |
| Locality | 0.15 | 0–10 (cache-friendly ⇒ higher) |
| PGO hit rate | 0.10 | 0–10 (more profile coverage ⇒ higher; 0 if no PGO data) |

Each candidate's raw scores per dimension are normalised to `[0, 10]` and combined as a weighted sum. The maximum possible total is `10.0`.

The **tie threshold ε** is fixed at `ε = 0.5` (5% of the maximum total). Two candidates whose scores differ by less than ε are treated as tied; ties trigger the ambiguity error described in §6.5.5.

The **Lipschitz bound** on the scoring function is `K ≤ 0.4` per single-bound addition: extending a candidate's bound set by one additional capability bound shifts its score by at most 0.4 points. Because `K < ε`, a single-bound extension cannot flip a clear winner into a tie or vice versa — this is what makes representation selection stable under small program changes.

PGO data may override per-call-site (within the per-call instantiation) but never globally; the global default weights and ε remain fixed regardless of profile data.

Implementations are conformant if they (a) use these defaults for unprofiled code and (b) document any per-call-site PGO override that can deviate from them. The weights and ε are subject to revision in future spec versions; programs that depend on a specific representation choice should pin it via explicit type annotation rather than relying on scoring.

#### 6.5.5 Pick or halt

- If exactly one candidate has the maximum score with margin ≥ ε (the threshold fixed in Rule 6.5.4 above), select it.
- If two or more candidates tie within ε, halt compilation with an ambiguity error per `docs/plans/implementation-plan.md` §"Ambiguity resolution via compiler suggestions": list every tied candidate with its score and a copy-pasteable disambiguation suggestion. The user resolves via explicit type annotation (per §8.5 — type annotation forces a specific candidate).

#### 6.5.6 Commit

The selected representation is recorded on the symbol's denotation as a phase-indexed view (`docs/references/scala3-compiler-reference.md` §4.4). Subsequent passes — match-to-fold desugaring, Perceus insertion, code generation — see only the concrete representation.

#### 6.5.7 Phase placement

| Runs after | Provides input to selection |
|---|---|
| Namer (§10.1) | Symbol table |
| Base typer | Bound sets per value |
| Capability closure (§6.2) | `extends`-flattened bound sets |
| Alias distribution (§5.8) | Alias-expanded bound sets |
| QTT quantity inference (§7.3.b) | Quantity context |
| Property verification | Refinement-cap viability |

| Runs before | Consumes selection output |
|---|---|
| Match-to-fold desugaring | Emits auto-derived `fold` per chosen rep |
| Perceus insertion | Knows heap-vs-stack from chosen rep |
| Code generation | Emits LLVM IR per chosen rep |

#### 6.5.8 Caching and SCC unification

Bound sets are **canonicalised** (sorted, deduplicated, alias-expanded, `extends`-flattened) and hashed. Two values with the same canonical bound set and quantity context share their selection result — selection is computed once per canonical key.

Mutually recursive `data` types (§7.5) form a strongly-connected component on the data-dependency graph. Selection runs **once per SCC**, not once per member; all members of a recursive group adopt a unified representation strategy (heap-allocated co-recursive group with shared refcount header). A future relaxation may allow per-member rep within an SCC when the inter-member references are tail-position only.

#### 6.5.9 Open questions

- **Cross-function rep flow.** A generic function's representation is determined by its signature; concrete reps are monomorphised at each instantiation. Whether two call sites share a monomorphisation is an implementation detail; this spec promises only that selection is consistent within a single function instantiation.
- ~~**Heuristic stability.**~~ Resolved in v0.4.5 — the Lipschitz bound `K ≤ 0.4 < ε = 0.5` is now part of Rule 6.5.4 (normative weights). Single-bound extensions cannot flip a clear winner.
- **Stack-allocated recursive data** (deferred). Recursive `data` declarations whose `of` value-params are concrete and bound the recursion depth could be added as a sixth built-in candidate (stack-flat struct), competing alongside the heap-allocated co-recursive group. Targeted for v0.5+ once the pass is implemented and the win is measured against a representative workload.

### 6.6 Built-in representation candidates (resolves OQ4)

The compiler maintains a set of **built-in representation candidates** — concrete types known to the compiler that satisfy specific capability combinations without any user-written `satisfies` declaration. These are part of the candidate pool during representation selection (§6.5).

The illustrative set for v0.2:

| Built-in representation | Satisfies (when type parameters are filled) |
|---|---|
| Linked list of `T` | `Sequencing of T + Folding of T + Empty + Filtering + Functor` |
| Contiguous array of `T` | `Sequencing of T + Folding of T + RandomAccess of (T, u64) + Sized of (T, u64) + Empty + Filtering + Functor` |
| Hash map of `K × V` | `Map of (K, V)` (when `K` satisfies `Hash + Eq`) |
| Auto-tagged-union for a sum cap | The sum cap's full method set (auto-implemented from the cap's `Self.fn` constructor requirements) |
| Auto-flat-struct for a product cap | The product cap's full method set |

**Rule 6.6 (priority).** During representation selection, built-in candidates have **equal priority** to user-`satisfies`-mapped data types. If a user-defined data type can satisfy the same bound set, it competes alongside the built-in candidates; the compiler picks among them by **allocation and performance heuristics**, with PGO refinement when profile data is available.

The full list of built-ins is implementation-defined and may grow with the compiler's capability-recognition library; the table above is normative only for the categories listed.

## 7. Data declarations

### 7.1 Variants and fields

A `data` declaration introduces a closed set of named variants. Two forms (grammar §6):

```
data Direction:                              // unit variants (indented form)
    North
    South
    East
    West

data Color:                                  // mixed variants
    Red
    Green
    Blue
    RGB(red: N is Numeric, green: N, blue: N)

data Config(host: String, port: u16, debug: bool)              // single-ctor sugar (paren form)
```

Fields may have **default values**:

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    // ...
```

A variant constructor call passes arguments **positionally**, with trailing arguments using their declared defaults if omitted.

### 7.2 Recursive data

A `data` declaration is recursive iff at least one variant has a field whose type mentions the data type itself (transitively). Recursive data is heap-allocated by default (see `spec/perceus.md`).

```
data Tree of A:
    Leaf(value: A)
    Branch(left: Tree of A, value: A, right: Tree of A)
    Empty
```

### 7.3 `of` value-params at the type level (resolves N1)

Non-`phantom` `of` parameters with a type annotation (e.g., `lo: N = 16`) are **type-level indices**: different supplied values produce **distinct types**.

```
data Bounded of (N is Numeric + Ord, lo: N = 16, hi: N = 24):
    Bounded(value: N)
    prop in_range: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi

// Two distinct types — values are not assignable across them:
//   Bounded of (i64, 0, 10)
//   Bounded of (i64, 5, 100)
```

**Rule 7.3.a (Defaults at type-application sites).** Missing trailing args at a type-application site take their declared defaults; positional only.

```
Bounded of N                ≡  Bounded of (N, 16, 24)       // both defaults
Bounded of (N, 5)           ≡  Bounded of (N, 5, 24)        // hi default
Bounded of (N, 5, 30)       — exact application
Bounded of (N, , 30)        — INVALID: no positional skip in v0.2
Bounded of (N, hi=30)       — INVALID: no named application in v0.2
```

Omitting an arg with no declared default is a compile error.

**Rule 7.3.b (QTT for of value-params).** `of` value-params are quantity 0 by default — type-level, erased at runtime. The compiler materialises them as compile-time constants at access sites (so `b.lo` in `b.lo <= b.value` becomes the statically known constant for `b`'s type). See `spec/quantities.md` for the full rule. Note that the "non-phantom" qualifier present in earlier drafts was redundant — `phantom` only ever applied to type parameters; all `of` value-params are erased uniformly.

**Rule 7.3.c (Constructor calls).** A variant constructor call passes only the variant's **declared fields** positionally. The values of the data type's `of` value-params come from the type at which the variant is being constructed:

```
let x: Bounded of (i64, 0, 10) = Bounded.Bounded(5)
//                                ^value=5, lo=0, hi=10 from type
```

### 7.4 Auto-derived capabilities (resolves OQ3)

A `data` declaration automatically satisfies certain capabilities based on its variant shape and field types. Auto-derivation requires no `satisfies` declaration; the compiler synthesises the implementation.

**Rule 7.4.a (Folding).** Every `data` declaration with at least one variant gets an auto-derived `fold<R>` method. The fold takes one callback per variant, in declaration order, with parameter list mirroring the variant's field list. Recursive `Self`-typed fields are pre-folded to `R` before the callback runs.

**Rule 7.4.a.1 (Functor-mediated recursion in `fold`).** A field of type `F of Self` (or transitively `F1 of (F2 of (... of Self))`) where every `Fi` satisfies `Functor` (auto-derived per Rule 7.4.c, or explicit via `satisfies`) is also pre-folded: the callback receives `F of R` (resp. `F1 of (F2 of (... of R))`) — every element in the wrapping `Functor` chain has been folded element-wise to `R`. This generalises catamorphism through composed-Functor-wrapped recursive positions.

```
data Json:
    Null
    Bool(value: bool)
    Number(value: f64)
    String(value: String)
    Array(elements: List of Json)                          // List satisfies Functor
    Object(fields: List of (Tuple of (String, Json)))      // List ∘ (Tuple of (String, _)) — both Functors

// Auto-derived fold:
//   fn fold<R>(
//       on_null:   () -> R,
//       on_bool:   (b: bool) -> R,
//       on_number: (n: f64) -> R,
//       on_string: (s: String) -> R,
//       on_array:  (elements: List of R) -> R,
//       on_object: (fields:   List of (Tuple of (String, R))) -> R,
//   ) -> R
```

The Functor composition is computed by the typer at fold-derivation time: for each recursive position, walk outward through Functor-satisfying type constructors until either `Self` is reached (apply 7.4.a.1) or a non-Functor wrapper is encountered (the field is left unfolded — the callback receives the original type and recursive descent must be expressed by the user via stdlib helpers or other folds).

```
data Expr:
    Lit(value: f64)
    Add(left: Expr, right: Expr)
    Mul(left: Expr, right: Expr)
    Neg(inner: Expr)

// Auto-derived:
//   fn fold<R>(
//       on_lit: f64 -> R,
//       on_add: (R, R) -> R,
//       on_mul: (R, R) -> R,
//       on_neg: R -> R,
//   ) -> R
```

Auto-derived `fold` is the canonical eliminator and the destructuring path for any `is`-bounded view of the data. `match` works directly on `data` values too (per `spec/pattern_matching.md`); `fold` is what the typer emits for capability-bounded views.

**Rule 7.4.b (Unfolding).** A recursive `data` declaration (any variant has at least one field of type `Self`, transitively) gets an auto-derived `unfold<S>` method (anamorphism). Given a seed `s: S` and a step function returning one variant of `Self` with seeds in recursive positions, `unfold` builds the structure:

```
fn unfold<S>(seed: S, step: S -> Self) -> Self
```

The compiler iterates `step` until no recursive seeds remain. The combination of `fold` (Rule 7.4.a) and `unfold` enables hylomorphism (`unfold` then `fold`) and the compiler is permitted to fuse the intermediate structure away.

**Rule 7.4.c (Functor over the rightmost type parameter).** A *named* `data T of (X1, ..., Xn)` declaration is auto-derived as `Functor of Xn` (the **rightmost type parameter** — right-bias convention) iff:

1. `Xn` is non-phantom and is a type parameter (not a value parameter).
2. `Xn` appears in **covariant** position in at least one variant field.
3. `Xn` does **not** appear in contravariant position (e.g., as a function-parameter type) in any variant field.

This rule applies to **named** data types only. Anonymous caps produced by cap-generator calls (e.g., `pair_constraint(0, 100)`) are never auto-derived per Rule 6.3.2 — refinement caps add prop obligations, not method surfaces.

The auto-derived `map` is implemented as a `fold` that re-builds each variant with the function applied to fields of type `Xn`, leaving fields of other types untouched. The map's range type substitutes `Xn` with the supplied target type:

```
data Maybe of A:
    Just(value: A)
    Nothing
// Auto-derived:
//   fn map<B>(f: A -> B) -> Maybe of B   (= Self of B)
```

**Rule 7.4.d (Accessors for product-shaped data).** A single-variant `data T(field1: T1, ..., fieldn: Tn)` (or equivalently `data T:` followed by an indented `T(field1: T1, ..., fieldn: Tn)` variant) automatically gets accessor methods:

```
fn field1 -> T1
fn field2 -> T2
...
```

These accessors are auto-implemented and require no `satisfies` declaration.

**Rule 7.4.e (Marker / refinement caps are never auto-derived).** Marker caps (no methods) and refinement caps generated by `fn -> cap` impose `prop` obligations only; they are never auto-derived. Satisfaction is by static verification (see `spec/properties.md`), not by code synthesis.

**Rule 7.4.g (Paramorphism — `para`).** Every recursive `data` declaration (any variant has at least one field of type `Self`, transitively) gets an auto-derived `para<R>` method (paramorphism). The `para` shape mirrors `fold` (Rule 7.4.a) one callback per variant, recursive positions pre-folded to `R` — *plus* each recursive field's original subtree is also passed to the callback alongside the folded result.

```
data Tree of A:
    Leaf(value: A)
    Branch(left: Tree of A, value: A, right: Tree of A)
    Empty

// Auto-derived:
//   fn para<R>(
//       on_leaf:   (value: A) -> R,
//       on_branch: (left_orig: Tree of A, left_folded: R,
//                    value: A,
//                    right_orig: Tree of A, right_folded: R) -> R,
//       on_empty:  () -> R,
//   ) -> R
```

Paramorphism subsumes catamorphism (the original-subtree arguments may simply be ignored) but enables structural decisions that depend on the *original* shape, not just the folded result. Canonical use: peephole optimizers that rewrite based on local subtree shape (`Neg(Neg(x))` → `x`), or BST `insert` where one subtree is rebuilt and the other is preserved verbatim.

Fixed's auto-derived `para` is sound for any total-recursion `data` declaration; it produces no infinite descent because it dispatches on one constructor layer per call and the recursion is driven by the data's structure (just like `fold`).

**Rule 7.4.f (Caps with `Self.fn` constructor requirements require explicit `satisfies`).** A cap declaring any `Self.fn` constructor *requirement* (sum or product classification, §6.4) is **never** auto-derived. An explicit `satisfies` mapping is required to bind data variants to those requirements:

```
data Maybe of A:
    Just(value: A)
    Nothing
// `Functor of A` is auto-derived (Rule 7.4.c).
// `Optional` requires an explicit mapping because it declares Self.fn some / Self.fn none:
Maybe satisfies Optional:
    Just as some
    Nothing as none
```

**Right-bias and ambiguity.** When right-bias does not uniquely determine the active type parameter (e.g., a cap declares no preferred parameter, or the data type has equally-positioned candidates), the compiler halts with the standard ambiguity error and copy-pasteable disambiguation suggestions per `docs/plans/implementation-plan.md` §"Right-bias and ambiguity resolution."

### 7.5 Mutual recursion across data declarations (resolves OQ2)

Two or more `data` declarations may recursively reference each other:

```
data Tree:
    Branch(node: Node)
    Leaf

data Node:
    N(tree: Tree, value: i64)
```

**Rule 7.5.** Mutual recursion across `data` declarations is supported. The Namer pass enters all data symbols with lazy completers; the Typer pass forces them as needed, breaking cycles via fixed-point iteration on the data dependency graph (the same mechanism used for mutual `cap` declarations).

Representation: data types in a strongly-connected component of the data dependency graph are co-allocated. The compiler treats them as a single recursive group requiring heap allocation; refcount headers are shared per-node, and the compiler may emit a unified tag space across the group when this enables better packing. See `spec/perceus.md` for refcount details.

Self-recursion (a single data type referencing itself) is the trivial 1-element SCC and is handled by the same rule.

**Rule 7.5.a (Auto-derivation does not apply across non-singleton SCCs) — resolves L7.** Auto-derivations from §7.4 (`fold`/Rule 7.4.a, `unfold`/Rule 7.4.b, `Functor`/Rule 7.4.c, accessors/Rule 7.4.d, `para`/Rule 7.4.g) **are disabled** for `data` declarations whose dependency graph forms an SCC of size ≥ 2. Each member of such an SCC requires explicit `satisfies` declarations for any capabilities it must satisfy.

Rationale: auto-derivation operates on a single declaration's variants and fields; mutually recursive types require interleaved fold/para callbacks (Tree's fold needs Node's fold, and vice versa). Specifying that interleaving as a generated mutual-fold combinator is non-trivial and not justified by current example code; refusing auto-derivation for v0.4.5 keeps the spec simple. Singleton SCCs (a single self-recursive data type) continue to receive auto-derivations under §7.4 — including paramorphism (Rule 7.4.g) for self-recursive types like `Tree` or `Expr` in `examples/07_recursive_data.fixed`.

```
data Tree:
    Branch(node: Node)
    Leaf

data Node:
    N(tree: Tree, value: i64)

// Tree and Node form a 2-element SCC. Rule 7.5.a disables auto-derived `fold`
// on both. To consume them, the user writes explicit satisfaction:
//
//   Tree satisfies Folding:
//       fn fold<R>(on_branch: ..., on_leaf: ...) -> R = ...
//   Node satisfies Folding: ...
```

OQ-T1 (deferred): a *mutual-fold convenience* — generated `(Tree, Node).fold(...)` combinators emitted automatically for SCCs — is a backwards-compatible extension on top of Rule 7.5.a. Revisit if mutually-recursive data types become common in real programs and explicit `satisfies` proves to be friction.

### 7.6 GADT-style equality constraints (deferred to v0.3, resolves OQ5)

Fixed v0.2 `data` declarations are **strictly parametric**: every variant of a `data T of (params)` declaration produces values of the same `T of (params)` instantiation.

If a variant field's type uses a non-parametric `of` argument (e.g., a `data Expr of A:` declaration whose `Add` variant is `Add(left: Expr of i64, right: Expr of i64)`), the field's type is interpreted **literally** — the variant constructs `Expr of A` containing an `Expr of i64` child, regardless of `A`. The typer does **not** narrow `A` to `i64` when matching the `Add` variant.

Per-variant type narrowing in pattern arms (the GADT feature in Haskell, OCaml, and similar languages) is queued for v0.3. When introduced, it will require explicit syntax distinguishing parametric from existentially-narrowed parameters (proposed direction: `data Expr where { LitInt :: Int -> Expr Int; ... }`-style or equivalent Fixed-flavoured syntax).

## 8. `satisfies`

A capability declares **what must exist**, not what does exist. The `Self.fn ctor(...) -> Self` declarations inside a cap body are **constructor requirements** — they specify that any type satisfying the cap must provide an implementation. Capabilities do not have constructors of their own; construction is always grounded in a concrete `data` type. A `satisfies` declaration is the mapping that names which data variants implement which cap requirements.

The same distinction applies to abstract methods: a cap declares `fn name(...) -> T` as a method requirement; the satisfying type provides the implementation (auto-derived where Rule 7.4 applies, or explicit per §8.3).

### 8.1 Mapping form

```
data Maybe of A:
    Just(value: A)
    Nothing

Maybe satisfies Optional:
    Just as some
    Nothing as none
```

(`Optional` here is the canonical sum cap from `examples/06_functor_monad.fixed`, declaring `Self.fn some(value: Part) -> Self` and `Self.fn none -> Self` as constructor requirements. The simplified `Optional` in `examples/02_collections.fixed` has no `Self.fn` requirements and is satisfied by auto-derivation alone — see §7.4.)

**Rule 8.1.a (Variant-to-requirement).** Each `Variant as name` line provides a data variant as the implementation of one of the cap's `Self.fn` constructor requirements. The variant's field types must match (or be a refinement of) the corresponding requirement's parameter types.

**Rule 8.1.b (Coverage).** A complete `satisfies` declaration must map every `Self.fn` constructor requirement of the cap to a data variant (or, in the partial-satisfaction case below, to `impossible`):

```
data Outcome of (E, A):
    Success(value: A)
    Failure(error: E)

Outcome satisfies Result:
    Success as ok
    Failure as err
```

Both of `Result`'s `Self.fn` constructor requirements (`ok` and `err`) are mapped; the satisfaction is total. Missing or duplicate mappings are compile errors.

### 8.2 `impossible` — partial satisfaction

A satisfying type may declare that a particular constructor requirement does not apply to it by mapping `impossible as name`. The satisfying type then provides no implementation for that requirement; calls to it through the capability surface compile to **runtime panics**.

A self-contained example. Define a sum-style cap with two requirements:

```
cap Either of (L, R):
    Self.fn left(value: L) -> Self
    Self.fn right(value: R) -> Self
    fn fold<X>(on_left: L -> X, on_right: R -> X) -> X

// A data type that is always the right side — for instance, a parse result
// from a trusted source where failure is impossible by construction.
data AlwaysRight of (L, R) (value: R)

AlwaysRight satisfies Either of (L, R):
    AlwaysRight as right
    impossible as left
```

Calling `Either.right(v)` at a position whose expected type resolves to `AlwaysRight` selects the `right` mapping and constructs an `AlwaysRight(v)`. Calling `Either.left(err)` at the same position has no implementation in `AlwaysRight` — the compiler emits a **runtime panic** at that call site.

**Rule 8.2 (Impossible — runtime panic semantics).** Mapping `impossible as name` records that the satisfying type provides no implementation of the constructor requirement `name`. The compiler does **not** statically verify that the requirement is unreachable on this satisfying type. Calls to `Cap.name(...)` whose resolution targets this satisfying type compile to a runtime panic of the form `panic: impossible constructor 'name' invoked on <SatisfyingType>`.

Static guarantees that the panic is unreachable are the user's responsibility, expressible as `prop` declarations on the surrounding code per `spec/properties.md`. For instance:

```
fn unwrap_right(x: AlwaysRight of (L, R)) -> R =
    prop never_calls_left: ...     // user-supplied invariant ruling out Either.left
    x.fold((_) -> unreachable, (r) -> r)
```

Why runtime panic rather than static rejection? Static reachability analysis across `satisfies` declarations is non-trivial — a closed-world reachability check would constrain how third-party `satisfies` declarations interact, and an open-world check requires whole-program analysis. Runtime panic gives the same operational guarantee (the impossible constructor never returns a real value) without forcing the typer into expensive reachability reasoning. Users who want a static guarantee can add a `prop` (`spec/properties.md`); a verified prop discharges the panic per Rule P9.2.

### 8.3 Method-body form

A `satisfies` block may also provide explicit method definitions:

```
Maybe satisfies Optional:
    fn isDefined = match Self:
        Maybe.Nothing => false
        Maybe.Just(_) => true
    ...
```

This is required when the auto-derivation rules (§7.4) cannot uniquely produce the cap's required methods (e.g., when multiple right-bias choices are available; see `docs/plans/implementation-plan.md` §"Right-bias and ambiguity").

### 8.4 Modular import

A `satisfies` declaration is **modular**: it must be brought into scope at use sites via `use`:

```
use std.maybe.Maybe satisfies Optional
```

This single declaration imports both `Maybe` (the type) and the satisfaction binding it to `Optional`. Without the `satisfies` clause, only the type is imported; satisfaction is invisible.

There is **no global coherence requirement**. Different modules can map the same data type to different capabilities, or even map the same `Cap.ctor` differently — as long as no single use site has more than one resolution in scope (otherwise §8.5 ambiguity applies).

### 8.5 Constructor resolution

A call `Cap.ctor(args...)` resolves to a concrete data constructor as follows:

1. Collect the in-scope `satisfies` declarations for `Cap`.
2. Filter by **expected type** at the call site:
   - Function return type: `fn f(...) -> is Optional of A: Optional.some(x)` — the call resolves against types that satisfy `Optional of A`.
   - Assignment LHS type: `let y: is Optional of u64 = Optional.some(42)`.
   - Argument expected type at a function call.
3. If exactly one satisfying type matches, resolution succeeds.
4. If zero match, the call is a type error.
5. If multiple match, the typer halts with an ambiguity error and emits copy-pasteable disambiguation suggestions per `docs/plans/implementation-plan.md` §"Ambiguity resolution via compiler suggestions."

Direct data construction (`Maybe.Just(42)`) bypasses resolution: it always produces a value of the named data type, regardless of which caps are in scope.

#### 8.5.1 Refinement-narrowed expected types (Rule 8.5.1) — resolves X1, L1

When the expected type contains refinement caps (Marker classification, per §6.4) — typically produced by cap-generator calls per §6.3 — resolution proceeds in two stages: **strip, resolve, re-apply**.

**Stage A — Strip refinement caps.** Partition the expected bound set `E` into:
- `E_base` — caps with constructor requirements (sum, product, recursive, capability-only).
- `E_ref` — refinement caps (Marker class, including caps produced by cap-generators per Rule 6.3.0).

**Stage B — Resolve against the residual.** Run §8.5's resolution against `E_base` only. Refinement caps are obligation-only and have no satisfactions to filter against; including them in step 2 of §8.5 produces empty filter results.

**Stage C — Re-apply refinements as obligations.** The chosen constructor's result is a value of the satisfying type, which by virtue of having satisfied `E_base` is well-typed. The refinement caps in `E_ref` become **prop obligations** on that result, discharged by the verifier per `spec/properties.md` §7. If `E_base` is empty (the expected type is *only* refinement caps), resolution fails with `error[E???]: refinement caps have no constructors; the expected type must include a base capability with a constructor requirement`.

```
fn try_make(i: u64) -> is Optional of u64 + between(1, 99) =
    Optional.some(i)
//  ^^^^^^^^^^^^^^^^
//  Stage A: E_base = { Optional of u64 }, E_ref = { between(1, 99) }
//  Stage B: resolve `Optional.some(i)` against `is Optional of u64` →
//           e.g. Maybe.Just(i) via in-scope satisfaction.
//  Stage C: Maybe.Just(i) is a value of `Maybe of u64`. The `between(1, 99)`
//           prop becomes an obligation on i (i.e. 1 <= i && i <= 99). The
//           prop verifier discharges it from the call site's context.
```

This rule extends to nested refinements (e.g. `is Optional of (u64 + between(1, 99))` — the inner refinement `between(1, 99)` is stripped from the *type-arg* before resolving against `is Optional of u64`, then re-applied as an obligation on the contained value at use sites).

A refinement-only expected type (e.g. `is between(0, 10)` alone) is rejected: refinement caps must compose with at least one base capability (or a primitive type) so a constructor exists.

## 9. Effects (brief)

`effect E: fn op(...) -> T` declares a set of effect operations. A function that performs effects declares them in its `with` clause: `fn read() -> String with FileSystem`. Effect rows compose with `+`. This document does not specify effect inference, evidence-passing compilation, or handler semantics; see `spec/effects.md`.

For typing purposes here, an effect's operations are first-class methods on the effect type (e.g., `Console.print_line(s)` is a method call returning `()` and adding `Console` to the caller's effect row).

## 10. Type inference architecture

The typer is **bidirectional** with **prototype types** carried as expected-type arguments. The high-level shape is documented in `docs/references/scala3-compiler-reference.md` §4.3; this section records Fixed-specific decisions.

### 10.1 Two passes

1. **Index pass (Namer).** Walk all top-level declarations; create symbols for `cap`, `data`, `effect`, `type`, `fn`, and `satisfies` declarations with **lazy completers** for their bodies. This enables mutual recursion across declarations: a `cap A extends B` and `cap B extends A` (if both type-checkable) can refer to each other before either body is fully processed.
2. **Type pass (Typer).** Type each function body, forcing the lazy completers as needed.

### 10.2 Bidirectional checking

Every typed expression carries an **expected type (prototype)** flowing from the surrounding context. The typer's main entry is roughly:

```
typed(expr, pt) → typed expression of type T such that T <: pt
```

Where `<:` is subtyping (§10.4). Prototypes may be:

- A concrete type (`u64`, `is Optional of A`, …)
- A function-application prototype (`FunProto`): "I am being called with these arguments."
- A selection prototype (`SelectionProto`): "I expect a member named `foo` here."
- The wildcard `?` (no expectation; pure type synthesis).

### 10.3 `is`-bound flow

When typing an expression `e` against an expected type that includes a capability bound `is C`, the typer:

1. Synthesizes `e`'s natural type `T`.
2. Verifies that some satisfaction binds `T` to `C` (directly, transitively via `extends`, or via auto-derivation §7.4).
3. Records the satisfaction so subsequent method calls on `e` can resolve to `C`'s methods.

### 10.4 Subtyping

Subtyping in Fixed is intentionally narrow:

- **`!` is a subtype of every type.** Expressions of type `!` are valid in any expression position.
- **A type satisfying a cap is a subtype of that cap-bound.** `Maybe of i64` is a subtype of `is Optional of i64` (assuming the satisfaction is in scope).
- **Capability bounds compose by intersection.** `is C1 + C2` is a subtype of `is C1` and of `is C2`.
- **Refinement caps strengthen, not weaken.** `N is Numeric + between(0, 100)` is a subtype of `N is Numeric`.
- **No structural subtyping on `data`.** Two `data` declarations with identical structure are distinct types.

## 11. Open questions

(All OQ1–OQ6 from v0.1 are resolved in this version; their resolutions are folded into the relevant sections above. OQ7 is pre-resolved and deferred to `spec/properties.md`.)

**Pre-resolved deferrals (lift into the indicated spec doc when drafted):**

- **OQ7 → spec/properties.md (deferred in v0.4.5).** Function-body postconditions are **deferred to a future release** per `spec/properties.md` §3.3 (re-opened in v0.2.1 of that doc). The original v0.4.4 resolution — that `result` is implicitly bound to the return value inside fn-body `prop` declarations — is no longer normative; the worked example below is retained for reference but does **not** type-check in v0.4.5. When postconditions are reintroduced, this OQ will be re-resolved and the `result` binder restored.

  ```
  fn sort(collection: ...) -> C is Sorted =
      prop result_same_size: result.size == collection.size
      //                     ^^^^^^ implicitly bound to the fn's return value
      collection.fold_right(...)
  ```

**Carried over from `docs/plans/implementation-plan.md` (Open Design Questions, not resolved here):**

- Effect handler composition syntax (plan ODQ #6). Defer to `spec/effects.md`.
- FFI boundary semantics (plan ODQ #5). Out of scope for the type system spec.
- Codata as a third declaration form alongside `data` and `cap` (plan ODQ #12). Defer to a future spec doc.
- Error-message wording rules (plan ODQ #4). Implementation-side, not normative for typing.

## 12. Cross-references

| Document | What it adds |
|---|---|
| `spec/syntax_grammar.ebnf` | All syntactic forms cited above |
| `spec/quantities.md` (TBD) | QTT inference, erasure, linearity, FBIP |
| `spec/effects.md` (TBD) | Effect rows, evidence passing, handler semantics |
| `spec/properties.md` (TBD) | `prop` semantics, static verification, PBT fallback, `forall`/`implies` |
| `spec/pattern_matching.md` (TBD) | `match → fold` desugaring, exhaustiveness |
| `spec/perceus.md` (TBD) | Perceus RC insertion, drop specialization |
| `docs/references/scala3-compiler-reference.md` | Reference patterns from dotc — bidirectional typing, Recheck pattern |
| `docs/plans/implementation-plan.md` | Phase plan, open design questions migrated above |
