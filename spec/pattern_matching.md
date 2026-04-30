# Fixed Pattern Matching

**Status:** Draft v0.1
**Specifies:** pattern syntax, type rules, exhaustiveness/redundancy checking, and match-expression semantics for Fixed v0.4.2 source.
**Last revised:** 2026-04-30

## 1. Scope

This document specifies pattern matching end-to-end — what a pattern is, where patterns can appear, how the typer assigns types and binds names, and how the compiler verifies that match expressions are exhaustive and that no arm is unreachable. Concretely:

- The pattern algebra (§3): wildcard, binder, literal, data-variant (qualified / bare / `Self.`), tuple, struct-destructure.
- Pattern type rules and binding semantics (§4).
- Match expressions (§5): scrutinee typing, arm-body unification, scoping.
- Exhaustiveness checking (§6) and redundancy/reachability checking (§7).
- The semantic relationship between `match` and the auto-derived `fold` of §7.4.a in type_system.md (§8) — they are *distinct* operations even though both are auto-derived for `data`.
- Pattern uses outside `match`: `let Pat = Expr` bindings (§9) and handler arm patterns (§10).

It does **not** specify:

- The compilation algorithm for the decision tree itself, beyond noting that Maranget's algorithm is the reference (the compiler implementation is free to choose any equivalent algorithm).
- Pattern matching on capability-bounded values (forbidden: caps don't expose variants — see §3.4 and `docs/plans/implementation-plan.md` "match vs fold").
- GADT-style per-arm type narrowing — deferred per `spec/type_system.md` §7.6.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.2 — `Pattern`, `LiteralPattern`, `DataVariantPattern`, `TuplePattern`, `StructDestructurePattern`, `FieldPattern`, `MatchExpr`, `MatchArm`, `HandlerArm`.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), §7.4.a (auto-derived `fold`), §7.6 (GADT deferred).
- `spec/quantities.md` v0.1 — Rule Q5.8 (pattern bindings).
- `spec/effects.md` v0.2 — handler arm patterns share the same algebra (§10).
- Maranget, *"Compiling Pattern Matching to Good Decision Trees"*, ML Workshop 2008 — the reference compilation algorithm for exhaustiveness and decision trees.
- Krauss, *"Pattern Matching in Theorem Proving"* — the reference for nested-pattern reasoning that informs §6.
- Pierce, *"Types and Programming Languages"*, ch. 11 — pattern-typing rules used in §4.

## 3. Patterns

### 3.1 Wildcard `_`

The wildcard pattern matches any value of any type and binds nothing.

```
match x:
    _ => "default"
```

### 3.2 Binder (`LOWER_IDENT`)

A bare lowercase identifier introduces a binding for the matched value at the pattern's static type.

```
match opt:
    Maybe.Just(v) => v + 1     // v is bound, type matches the variant's field type
    Maybe.Nothing => 0
```

A binder shadows enclosing-scope names of the same identifier inside the arm body. **Rule M3.2 (Binder uniqueness):** within a single pattern, the same binder name may not appear twice (`Maybe.Pair(x, x)` is a compile error). Linearity within a single pattern forces this.

### 3.3 Literal patterns

`LiteralPattern` matches a value equal to the literal:

| Literal | Matches |
|---|---|
| `INT_LITERAL` | A numeric value equal to the literal. Type inferred from the scrutinee. |
| `FLOAT_LITERAL` | A floating-point value equal to the literal. Equality is bitwise, not algebraic — `0.0` does not match `-0.0` and `NaN` does not match `NaN`. |
| `STRING_LITERAL` | A string value equal to the literal. |
| `CHAR_LITERAL` | A character value equal to the literal. |
| `BOOL_LITERAL` | `true` or `false`. |
| `UNIT_LITERAL` | `()` — matches the unique unit value. |

**Rule M3.3 (Literal type):** the literal's type must match the scrutinee's type. Numeric-literal polymorphism (per type_system.md §5.7) resolves the literal's type from the scrutinee.

### 3.4 Data-variant patterns

Three forms (all parse via `DataVariantPattern` per the grammar):

1. **Qualified:** `Type.Variant` or `Type.Variant(field-patterns)` — explicit reference to a variant of a named data type.
2. **Bare:** `Variant` or `Variant(field-patterns)` — the typer disambiguates from the scrutinee's type.
3. **Self-qualified:** `Self.Variant(...)` — used inside `satisfies` blocks where `Self` refers to the satisfying type.

```
match d:
    Direction.North => "N"        // qualified
    East => "E"                     // bare; typer infers Direction.East from scrutinee
    Self.Just(v) => v               // (inside Maybe satisfies Optional, Self = Maybe)
    _ => "?"
```

**Rule M3.4.a (Variant resolution):** A bare `Variant` pattern resolves against the scrutinee's static type. If the scrutinee's type has a variant named `Variant`, the pattern matches it; otherwise the pattern is an error. If the bare name shadows a binder in scope, the pattern is treated as the binder iff the scrutinee's type has no such variant. This is the "binder-vs-variant" rule and is purely syntactic disambiguation by the typer.

**Rule M3.4.b (Field-pattern arity):** A variant pattern's parenthesised field patterns must match the variant's field count exactly. Trailing-field elision is **not** supported in v0.4.2 — `List.Cons(h, t)` requires both, and `List.Cons(h)` is a compile error. (The struct-destructure pattern §3.6 is the form for selective field binding by name.)

**Rule M3.4.c (Match on capability-bounded values is forbidden):** If the scrutinee's static type is a capability bound (`is Optional of A`, `C is Sequencing`, etc.) rather than a `data` type, `match` is a compile error. The user must call `value.fold(...)` explicitly. Capabilities are abstract — they do not expose variants — so pattern matching against variant names cannot type-check against an arbitrary satisfying type. See §8 for the precise relationship between `match` and `fold`.

### 3.5 Tuple patterns

`TuplePattern` matches a tuple of the same arity, element-wise:

```
let (a, b) = pair                                 // 2-tuple binding
let (single,) = unit_tuple                         // 1-tuple binding (mandatory trailing comma)
match triple:
    (0, _, _) => "starts with 0"
    (_, _, last) => last
```

**Rule M3.5 (Tuple arity):** the tuple pattern's arity must match the scrutinee's tuple arity. A 2-element tuple cannot be matched by a 3-element pattern. Note the mandatory trailing comma in 1-element tuples (per grammar `TupleExpr` / `TuplePattern`).

### 3.6 Struct-destructure patterns

`StructDestructurePattern` destructures a value of a single-variant `data` type by field name:

```
let Pair { first: x, second: y } = p
let Config { host, port, debug: d } = cfg     // shorthand: `host` ≡ `host: host`
```

**Rule M3.6.a (Coverage):** every field of the data type's single variant must appear in the pattern, **or** the pattern must end with `..` (TENTATIVE — see §11 OQ-M1). For v0.4.2 every field must be named.

**Rule M3.6.b (Type restriction):** struct-destructure patterns apply only to single-variant `data` types (declared either as `data T(field1, field2, ...)` or as `data T:` followed by a single-variant body with the same name as the type). Multi-variant data types require variant patterns (§3.4).

### 3.7 Pattern types

Every pattern has a **pattern type** equal to the scrutinee position's type. The typer threads pattern type through subpatterns:

- For a tuple pattern `(p1, p2, ..., pn)` against scrutinee type `(T1, T2, ..., Tn)`: each `pi` has pattern type `Ti`.
- For a variant pattern `Type.Variant(p1, ..., pk)` against scrutinee type `Type` (or `Type of Args`): each `pi` has pattern type `Type`'s i-th field type after type-argument substitution.
- For a struct destructure `Type { field1: p1, ... }`: each `pi` has pattern type matching the named field's declared type.

## 4. Pattern type rules

**Rule M4.1 (Pattern bindings carry quantity).** Every binder introduced by a pattern receives a quantity per `spec/quantities.md` Rule Q5.8 — its quantity is the sum of its usages in the arm body (or, for `let`-bindings, the rest of the enclosing block).

**Rule M4.2 (Refutability).** A pattern is **refutable** if some value of the scrutinee type does not match it. A pattern is **irrefutable** if every value matches.

- Wildcard `_` — irrefutable.
- Binder — irrefutable (the binder binds whatever value is matched).
- Literal pattern — refutable.
- Variant pattern — refutable iff the type has more than one variant.
- Tuple pattern — irrefutable iff every element pattern is irrefutable.
- Struct-destructure pattern — irrefutable (single-variant data, all fields named).

**Rule M4.3 (Irrefutability obligation in `let`).** A `let Pat = Expr` binding requires `Pat` to be irrefutable. Refutable patterns in `let` are a compile error: `error[E090]: refutable pattern in let binding`. To handle refutable bindings, use `match`.

## 5. Match expressions

### 5.1 Form (recap)

```
match SCRUTINEE:
    Pattern_1 => Body_1
    Pattern_2 => Body_2
    ...
    Pattern_n => Body_n
```

Per `spec/syntax_grammar.ebnf` §13, arms are NEWLINE-separated (no commas), and each arm's `=>` may introduce a multi-line indented `BlockTail` body.

### 5.2 Scrutinee typing (Rule M5.2)

The scrutinee `SCRUTINEE` is type-checked under no expected type (synthesis mode). Its type must be a concrete `data` type (or a primitive type whose values can be enumerated as literal patterns: `bool`, finite-domain integers in special cases). Capability-bounded scrutinees are an error per Rule M3.4.c.

### 5.3 Arm-body type unification (Rule M5.3)

Every arm body must produce a value of the same type (or unifiable types). The match expression's type is the join of the arm bodies' types. The match expression's effect row is the union of:

- Each arm body's effect row.
- The scrutinee's effect row.

Arms whose bodies have type `!` (the never type, e.g., `Fail.fail(...)` or `unreachable`) do not contribute to the result type — they are valid in any expression position per type_system.md §5.6.

### 5.4 Arm scoping (Rule M5.4)

A pattern's binders are in scope only within that arm's body. Each arm has its own binding scope; binders do not leak between arms or to the surrounding context.

```
match x:
    Maybe.Just(v) => v + 1     // v in scope here
    Maybe.Nothing => v          // ERROR: v not in scope (different arm)
```

### 5.5 Arms must be ordered by specificity (informational, not normative)

Arms are matched **in declaration order**. The first arm whose pattern matches the scrutinee fires; subsequent arms are not tried for this match. This means an earlier wildcard arm can shadow later specific arms (which then become unreachable per §7).

## 6. Exhaustiveness checking

**Rule M6.1 (Exhaustiveness).** A match expression's set of arm patterns must cover every possible value of the scrutinee type. A match that is not exhaustive is a compile error: `error[E091]: non-exhaustive match` with at least one counterexample value (a value of the scrutinee type not matched by any arm).

The typer uses a standard *pattern-matrix algorithm* (Maranget 2008): the residual *uncovered space* after each arm is the complement of the arm's pattern within the previous uncovered space; if the final uncovered space is non-empty, the match is non-exhaustive and the algorithm constructs a witness.

### 6.1 Type-specific exhaustiveness

| Scrutinee type | Exhaustiveness check |
|---|---|
| `bool` | `true` and `false` both covered, or a wildcard arm. |
| `()` | A single arm matching `()` or a wildcard. |
| `data T` (single-variant) | The variant's fields' patterns are jointly exhaustive at their types. |
| `data T` (multi-variant) | Every variant has an arm (or the variant's pattern is implied by a wildcard / binder). For each variant, its field patterns are jointly exhaustive. |
| Tuple `(T1, ..., Tn)` | Element-wise: each position's patterns across arms are jointly exhaustive at `Ti`. |
| Integer / float / string / char | A wildcard or binder arm is required (the value space is too large for literal coverage). |

### 6.2 Exhaustiveness with refinement caps

When the scrutinee's type carries a refinement cap (e.g., `N is between(1, 3)`), the static value-set is narrowed. The typer **may** use refinement information to prove exhaustiveness with literal patterns alone:

```
fn name(d: u8 is between(1, 3)) -> String =
    match d:
        1 => "one"
        2 => "two"
        3 => "three"
    // exhaustive — the refinement cap proves d ∈ {1, 2, 3}
```

**Rule M6.2 (Refinement-aided exhaustiveness):** the typer may, but is not required to, use refinement-cap evidence to certify exhaustiveness. Implementations should at minimum support compile-time-known finite literal sets.

### 6.3 Counterexamples

The error message for a non-exhaustive match must include at least one **uncovered value** — a concrete value of the scrutinee type that is not matched by any arm. Per `docs/plans/implementation-plan.md` "Agent-Friendly CLI and Compiler Output", error messages include copy-pasteable suggestions; a non-exhaustive-match error suggests adding the missing arm verbatim.

## 7. Redundancy / reachability

**Rule M7.1 (Reachability).** Each arm must be **reachable**: at least one value of the scrutinee type matches the arm's pattern but no earlier arm's pattern. An unreachable arm is a compile error: `error[E092]: unreachable match arm`. The error names the arm's pattern and points to the earlier arm(s) that subsume it.

The check is the dual of exhaustiveness: an arm `K` is unreachable iff the *uncovered space before arm K* (per the pattern-matrix algorithm) is empty. Implementation re-uses the matrix machinery from §6.

```
match x:
    Maybe.Just(v) => v + 1
    _ => 0                        // wildcard
    Maybe.Nothing => -1           // ERROR: unreachable, _ above subsumes Nothing
```

## 8. `match` and `fold` — the relationship (Rule M8)

Both `match` and the auto-derived `fold` of type_system.md §7.4.a destructure a `data` value, but they have **different semantics**. The distinction matters for recursive data.

### 8.1 Match semantics

A match arm receives the **raw fields** of the matched variant. For recursive types, recursive subtrees are *not* pre-folded — the user can recurse manually if they choose:

```
fn count(t: Tree of A) -> u64 =
    match t:
        Tree.Empty => 0
        Tree.Leaf(_) => 1
        Tree.Branch(l, _, r) => 1 + count(l) + count(r)   // l, r are raw subtrees
```

### 8.2 Fold semantics (recap from type_system.md §7.4.a)

A fold callback for a recursive variant receives **pre-folded** results for `Self`-typed fields and raw values for non-`Self` fields. The fold is the structural catamorphism:

```
fn count(t: Tree of A) -> u64 =
    t.fold(
        () -> 0,                                              // Empty
        (_) -> 1,                                             // Leaf
        (l_count, _, r_count) -> 1 + l_count + r_count,       // Branch (subtrees pre-folded)
    )
```

### 8.3 Equivalence on non-recursive data (Rule M8.3)

When the data type has **no recursive variants** (no field of type `Self`), `match` and `fold` are operationally equivalent — pre-folding and raw-field access coincide because there is nothing to pre-fold.

```
data Direction:
    North
    South
    East
    West

// Equivalent:
match d:                         d.fold(
    Direction.North => "N"           () -> "N",
    Direction.South => "S"           () -> "S",
    Direction.East  => "E"           () -> "E",
    Direction.West  => "W"           () -> "W",
                                 )
```

For non-recursive data, the compiler is free to compile `match` via the auto-derived `fold` or via a direct decision-tree dispatch — both produce the same observable behavior.

### 8.4 Compilation strategy (informative)

The compiler's `MatchDesugar` pass (per `docs/plans/implementation-plan.md` Phase 4) compiles `match` via Maranget's decision-tree algorithm. For non-recursive `data`, this strategy is equivalent to a fold-based switch and the implementation may choose either lowering. For recursive data, the decision-tree compilation preserves raw subtree access (per §8.1) — `match` is **not** desugared to `fold` in that case. (The implementation plan's "match → fold" terminology is shorthand for this lowering; it does not imply catamorphic pre-folding.)

## 9. Pattern bindings in `let`

`let Pat = Expr` introduces the binders of `Pat` into the rest of the enclosing block.

**Rule M9.1.** `Pat` must be irrefutable per Rule M4.3. Common irrefutable patterns:

- Binder: `let x = expr`
- Tuple: `let (a, b) = pair`
- Struct destructure: `let Pair { first: key, second: value } = pair`

For refutable patterns, use `match`:

```
// REFUTABLE — use match:
match opt:
    Maybe.Just(v) => use_v(v)
    Maybe.Nothing => default

// IRREFUTABLE — let is fine:
let (key, value) = pair
let Pair { first: key, second: value } = pair
```

**Rule M9.2.** A `let Pat = Expr` binding's quantities are inferred per Rule Q5.8 in `spec/quantities.md`: each binder's quantity is the sum of its usages in the rest of the block.

## 10. Pattern bindings in handler arms

Per `spec/effects.md` §6, handler arms have their own pattern syntax distinct from match arms but sharing the binder rules:

```
HandlerArm
    = UPPER_IDENT "." LOWER_IDENT "(" PatternList? ")" "=>" BlockTail
    | "return" "(" Pattern ")" "=>" BlockTail
```

The `(p1, p2, ...)` parameter list of an effect-operation arm uses the same pattern algebra as match arms, but with two restrictions:

- Each `pi` is matched against the operation's i-th declared parameter type (synthesised from the effect declaration).
- The patterns must be **irrefutable** — handler arms intercept *every* call to the operation, so refutable patterns make no semantic sense. (`Eff.op(0)` as a handler arm matching only when the arg is `0` is not supported in v0.4.2 — see OQ-M2.)

The `return(Pat) => ...` arm's `Pat` is similarly irrefutable, matching the SUBJECT's normal-completion value.

## 11. Open questions

**Open (no decision):**

- **OQ-M1 — Field elision via `..`.** Struct-destructure patterns currently require every field to be named (Rule M3.6.a). A trailing `..` to ignore unmentioned fields (`let Pair { first: x, .. } = p`) is a common convenience in Rust/Haskell-family languages. Add when stdlib data types reveal a need; defer until then.
- **OQ-M2 — Refutable handler arm parameters.** Rule §10 forbids refutable patterns in handler arms. A pattern like `Eff.op(Just(v)) => ...` to handle only specific argument shapes would require a fall-through to a default arm. Useful for protocol/state-machine handlers; defer until a use case appears.
- **OQ-M3 — Or-patterns.** `Pat1 | Pat2 => body` (matching either pattern, with the same body) reduces duplication in match arms. Pure syntactic sugar; binder names must agree across alternatives. Add when an example shows redundancy that or-patterns would clean up.
- **OQ-M4 — Guards.** `Pat if cond => body` — Pat matches and `cond` evaluates true. Useful when the value's type alone is insufficient to discriminate. Common in ML-family languages. Defer until needed.
- **OQ-M5 — View patterns / smart constructors.** A pattern that runs a user-defined function (`is_even(x)` matches when `x` is even). Powerful but interacts non-trivially with exhaustiveness checking (the compiler can't reason about arbitrary user code). Out of scope for v0.4.2.
- **OQ-M6 — Range patterns for literals.** `1..=10 => ...` matching integer ranges. Useful for character-class dispatch (parsers, lexers). Defer.

**Resolved by reference (cross-document):**

- GADT-style per-variant type narrowing — deferred per `spec/type_system.md` §7.6.

## 12. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | All pattern syntax (Pattern, MatchArm, HandlerArm) |
| `spec/type_system.md` | Rule 4.1 (typevars), §5.7 (numeric literal polymorphism in literal patterns), §7.4.a (auto-derived fold), §7.6 (GADT deferred) |
| `spec/quantities.md` | Rule Q5.8 (pattern binder quantities), Rule Q5.9 (branching uses `⊔`) |
| `spec/effects.md` | §6, §10 — handler arm pattern algebra |
| `spec/properties.md` (TBD) | Match arms inside `prop` bodies are checked at quantity 0 — same rules apply |
| `spec/perceus.md` (TBD) | Match-bound values' RC: each arm's binders consume the scrutinee per their inferred quantity |
| `docs/references/scala3-compiler-reference.md` | The dotc PatternMatcher transform (`transform/PatternMatcher.scala`) — Maranget's algorithm; reference implementation |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `typer/Exhaustiveness.scala`; Phase 4: `transform/MatchDesugar.scala` |
