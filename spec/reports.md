# Fixed Reports: Coverage, Cardinality and the Tool Interface

**Status:** Draft v0.2.9
**Specifies:** two analyses the compiler runs and reports — coverage of code by verification, and cardinality of types — the report of what the link step chose, the files a build reads besides its sources, the interface external tools use, and the exit codes of every command.
**Last revised:** 2026-11-22

**Changes from v0.2.8.** Rule RP4.1 is restated around the four-term carrier `a·ε₀ + b·ωⁿ + c·ω + d` (new Rules RP4.1.a–RP4.1.c). The intermediate `ωⁿ` tier separates a finite product of countably infinite spaces from a countable sum without recording exponents; `ε₀` caps unbounded multiplicative depth. Addition keeps coefficients, multiplication is bilinear over the tier table, and powers use repeated squaring. A function whose row contains a resumable effect now counts as `ε₀` times its pure count, replacing the former flat `ω` (`bool -> bool with Console` is `4ε₀`), so the coefficient distinguishes such functions. The count is exact only in the finite tier; above it the tiers are structural, not cardinal numbers. Rule RP4.4 prints the normal form, and the JSON form gives the four coefficients.

**Changes from v0.2.7.** Rule RP4.1: the count of a function type is exact only for a function without `Diverge` (`spec/type_system.md` Rule 7.4.h); how to count one with it is left to the owner. Later the same day the owner settled it: each abortive effect of the row, `Diverge` included, adds its outcomes to the count of the result. On 2026-11-22 the count became a value of four coordinates, `a·ε₀ + b·ωⁿ + c·ω + d`, and a function with a resumable effect counts `ε₀`.

**Changes from v0.2.6.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Recursion inside a capability"): Rule RP5.5 gives class `6` to `T028` (a default body of a capability that reaches itself through `self`, `spec/type_system.md` Rule 7.4.j).

**Changes from v0.2.5.** Applies the owner's decision of 2026-11-21, fourth round (P6, "No deferred sites"): the lock file (Rule RP5.3) and the link report (Rule RP5.4) hold no deferred satisfaction site, and class `7` of Rule RP5.5 arises at package compile only.

**Changes from v0.2.4.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Shadowing of prelude names"): `W` codes are warnings numbered in the decade of the document that defines them, as `E` codes are (`W060`, `spec/type_system.md` Rule 4.3.a).

**Changes from v0.2.3.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Integer overflow and division"). Rule RP5.5 gives classes to `T025` (constant arithmetic that does not fit, class `6`), `E107` (an integer refinement not proved where it is established, class `11`) and `T026` (`widen` at a 64-bit type, class `99`), and says that a program's abort on an unhandled arithmetic effect is the program's exit status, not a class.

**Changes from v0.2.2.** Applies the owner's decisions of 2026-11-21, fourth round (P6, "Owner's decisions (fourth round)"), decision 1. New Rule RP5.5: exit code `0` for no failure and one number per error class, for every command; the smallest class number wins when a run has several. The table is proposed by the author; the owner confirms the class list. Decision 2 adds `T024` (an ascription that names a bound, `spec/type_system.md` Rule 5.11) to the type errors.

**Changes from v0.2.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). Rule RP3.2: the specification status follows the order of Rule P7.7.

**Changes from v0.2.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): the link report shows props tested under another binding, decisions resting on `assumed` props and each satisfier's claims beside the contract (Rule RP5.4); the lock file's deferred satisfaction sites (Rule RP5.3); layout uses no count, since a tag is one byte and each type has one layout (Rule RP4.3); "per satisfaction" replaces "per satisfying type".

**Changes from v0.1** (`docs/decisions/P6-resolution-round-2026-11-20.md`, D4, D6, D10).
- **Counters are the measurement lane's input** (Rule RP3.3). Region and request counters are the demand side of `spec/properties.md` Rule P7.9; they go to the profile file and never license a fact.
- **The lock file and the profile file** (new Rule RP5.3) are build inputs and things tools read (Rule RP5.1). A build is a function of its sources, its dependencies, the lock file and the profile file; it never measures.
- **The link report** (new Rule RP5.4, `--explain-selection`): per choice point the candidates, their scores, the disambiguation rule that decided and whether the data was measured or static.
- **Cardinality of choice points** (Rule RP4.2): symbolic until link, a number after it. An unbounded type variable still admits no inspection, so the parametric count is unchanged.

**Why this document exists.** Both analyses were proposed as optional plugins. Both turned out to be things the compiler needs for itself: counters are the input of measured selection and specialisation (`spec/properties.md` Rule P7.9), and counting inhabitants is already done three times (exhaustiveness, generators, layout). They are therefore compiler passes with reports, and tools outside the compiler read those reports and the package interface. The decisions are recorded in `docs/decisions/P5-reports-and-tools.md`.

## 1. Scope

This document specifies coverage (§3), cardinality (§4), and the tool interface with the files and reports it reads (§5). It does not specify the report of `spec/memory_management.md` §19, to which `spec/compilation_model.md` Rule CM5.8 and `spec/cost.md` Rule CT6.4 add; it only places the new reports in the same family.

## 2. Dependencies

- `spec/properties.md` — §7 (verification, statuses), Rule P7.8 (lanes), Rule P7.9 (measurement during testing), §8 (generators), Rule P9.2 (`unreachable` discharge).
- `spec/compilation_model.md` — clones (§5), the interface tier (Rule CM7.1), choice points (Rule CM8.1), usage summaries (Rule CM8.3), selection at link (Rule CM8.4), the lock file (Rule CM8.5), the profile file (Rule CM8.6).
- `spec/cost.md` — Rule CT8.2 (the score is usage × cost).
- `spec/pattern_matching.md` — §6 (exhaustiveness).
- `spec/type_system.md` — §6.4 (refinement capabilities), §6.5 (representation selection), Rule 6.5.5 (disambiguation).
- Implementation plan, Phase 6 (profile data).

## 3. Coverage

### 3.1 Regions (Rule RP3.1)

A **region** is a piece of source whose execution is worth counting: a function or method body; each arm of a `match`; each branch of an `if`; each handler clause; each lambda body.

Not regions: `prop` bodies (they are the tests, `spec/properties.md` §4.1); erased code (demand `0`); members the compiler derives.

A region is identified by its declaration and its ordinal within it, so its identity survives edits elsewhere in the file.

### 3.2 Two questions, two statuses (Rule RP3.2)

Line coverage answers one question badly. Fixed separates two.

**Was the region executed?** Each region has an *execution status*:

| Status | Meaning |
|---|---|
| `unreachable` | A prop in the facts lane (`proved` or `assumed`, `spec/properties.md` Rule P7.8) discharges every path to it (Rule P9.2). It is not counted in any total. |
| `executed` | Its counter is non-zero after the property-based tests of the build ran. |
| `not executed` | Otherwise. |

**Is the declaration constrained by a property?** Each function and member has a *specification status*: the strongest status (`proved`, then `assumed`, then `tested`, the order of `spec/properties.md` Rule P7.7) among the accepted props whose body calls it, or calls a function that (transitively) calls it. A declaration no prop reaches in this way is `unconstrained`.

The two are independent. The static prover does not look inside function bodies (Rule P7.2), so a `proved` prop constrains a function without executing any region of it; and a region can be executed by a test that asserts nothing about it.

### 3.3 Counters (Rule RP3.3)

In a test build, and in a build instrumented for profiling, the compiler puts a counter at the entry of every region. Counters are inserted in the Core of the baseline version, before specialisation, so every clone carries the counters of the regions it copies, labelled with its key (`spec/compilation_model.md` Rule CM5.1).

The same build counts, per call site, which clone key the call requested, and, per choice point (Rule CM8.1), how often each capability method was called on its values.

These counters are the **demand side of the measurement lane** (`spec/properties.md` Rules P7.8, P7.9). The build writes them to the profile file (Rule CM8.6): the request counts are the measured `weight` of Rule CM5.4, and the per-method counts are the measured `usage` of `spec/cost.md` Rule CT8.2. They choose among alternatives that are all correct; no count licenses a fact. A test build's counters rank below a production profile's (Rule P7.9).

Instrumentation must not disable a mandatory specialisation (Rule CM5.5): a fused loop keeps the counters of the step bodies it contains.

### 3.4 What the report shows (Rule RP3.4)

`fixed verify --coverage` prints, and with `--format json` emits:

- per region: execution status and count, in total and per version (baseline and each clone key);
- per declaration: specification status and the props that give it;
- per prop with a polymorphic binder: the number of iterations each combination of satisfactions received (`spec/properties.md` Rule P5.3.b says sampling thins them; this shows by how much);
- totals: regions executed over regions reachable, and declarations by specification status.

`fixed verify` is planned, not built (`docs/plans/implementation-plan.md`); the report is specified here so that the counters and the statuses have one definition.

### 3.5 Counters as a generator input (Rule RP3.5)

A property-based test driver may use region counters to prefer inputs that reach regions not yet executed. This is permitted, not required, and does not change what a status means.

## 4. Cardinality

### 4.1 The count (Rule RP4.1)

The **cardinality** of a type is the number of values it has. For a finite type the count is exact. Above the finite tier the report gives a **structural estimate**, not a cardinal number: every countably infinite type has the same cardinality, so the count records how the space is built — a sum, a product, a power, or an unbounded number of factors — because that is what the report compares. The estimate is the four-term polynomial of Rule RP4.1.a.

- A sum adds: a `data` type's cardinality is the sum over its variants.
- A product multiplies: a variant's cardinality is the product over its fields.
- A function type exponentiates: `|A -> B| = |B|^|A|`, evaluated in the carrier of Rule RP4.1.a. The expression is exact in Fixed for a function without `Diverge` in its row, because such a function is total and pure: there is no non-termination and no exception to add (apart from the known gap of `spec/type_system.md` OQ-T5, data that holds a function of itself, and an `unfold` until Rule 7.4.i is built).
- A row adds outcomes to the result (owner's decision, fourth round of P6): `|A -> B with R| = (|B| + outcomes(R))^|A|`. `outcomes(R)` is the sum over the abortive effects of `R`, and an abortive effect counts the sum, over its operations, of the count of the operation's arguments, since a call that performs the operation ends with those values in place of a result. So `Diverge` gives 1 (the call may not return, `spec/type_system.md` Rule 7.4.h), `Overflow` 1 and `DivideByZero` 1 (the position is hidden from the program, `spec/effects.md` Rule E3.6.j), `Fail of E` gives `|E|`, and `Decode` gives `|u64|`, the offset its handler receives (`spec/stdlib.md` Rule S3.7.a). `bool -> bool` counts 4, and `bool -> bool with Diverge` counts 9. This is the **pure count** of the function: the total pure results, including the abortive outcomes, before any resumable effect is applied; `outcomes(R)` counts only the abortive effects. An effect removed by proof (`spec/effects.md` Rules E3.6.e to E3.6.e.3) is not in the row and adds nothing, so the count falls when an effect is proved away.
- An effect adds to the result: `A -> B with Fail of E` counts as `A -> (B + E)`. A function whose row contains an effect with a resuming operation (`Console`) counts as `ε₀` times its pure count: the count of the same function type with the resumable effects removed from the row, abortive outcomes still included. Each resumable effect contributes a factor `ε₀`; since `ε₀ · ε₀ = ε₀`, the row contributes one such factor. So `bool -> bool with Console` is `4ε₀`, `bool -> bool with Console + Diverge` is `9ε₀`, and `String -> bool with Console` is `ε₀ · ω = ε₀`. A resuming operation may run and resume arbitrarily many times, so the handler's meaning is an unbounded sequence of pure results; that is the `ε₀` tier. The coefficient distinguishes functions whose pure count is finite. Where the pure count is infinite the coefficient is dropped and the function counts one `ε₀` (owner's decision, for now): this is a rule of this bullet, apart from the arithmetic of Rule RP4.1.b, where a product with `ε₀` keeps its coefficients.
- `String` counts as `ω` (countably infinite).
- A recursive type counts from its construction (Rule RP4.1.c): a productive recursion with a finite label space per step is `ω` (`Nat`), a product of two independent recursive values is `ωⁿ` (`(Nat, Nat)`), and a recursion whose depth multiplies an infinite label space is `ε₀` (`List of String`).
- A refinement capability (`spec/type_system.md` §6.4) lowers the finite part when the prover can count its inhabitants: `u8 + between(0, 150)` is 151. Otherwise the count of the base type stands as an upper bound.

The function bullet is exact as an expression; the tiers evaluate it structurally. Above the finite tier, `ω^ω` is the `ε₀` tier, not the cardinal `2^ℵ₀`: the report measures the space a finite program can build, not the set of all mathematical functions. Rule RP4.1.b gives the power rules.

#### 4.1.1 The carrier (Rule RP4.1.a)

A count is a four-term polynomial

```text
a·ε₀ + b·ωⁿ + c·ω + d        a, b, c, d >= 0
```

The terms are the four **tiers**, highest first: `ε₀`, `ωⁿ`, `ω`, and the finite part `d`. `ωⁿ` is the **intermediate power tier**: it stands for `ω^k` for any finite `k > 1`, without recording or comparing the exponent. `ω²`, `ω³` and `ω^(2^32)` are the same tier, and the report prints it as `ωⁿ`.

`ε₀` is the least fixed point of `α ↦ ω^α`, the tier above every finite power of `ω`. It caps a construction whose number of factors is unbounded. It is a countable ordinal, not an uncountable cardinal, and it is a marker: the report does not claim that a type in the `ε₀` tier has more values than one in the `ωⁿ` tier.

**Why these four terms.** The tiers are a structural scale, not cardinal numbers. `ω` is one countably infinite factor (or a sum of countably many alternatives); `ωⁿ` is a finite product of at least two countably infinite factors; `ε₀` is a construction with unbounded multiplicative depth. All three are countable in the finite-program reading, and the report does not claim that one tier has more values than another in the set-theoretic sense. The order is the order of the constructions, which is what the report compares. For example, a data type with two `String`-only variants is `2ω`, while a variant with two `String` fields is `ωⁿ`; without the intermediate tier both would read `ω`, and the report could not tell the tagged sum from the product.

- Coefficients are kept because a sum is tagged: two countable alternatives (`2ω`) are not one countable space (`ω`), and collapsing them would lose the case count. Addition is componentwise.
- Finite powers are collapsed because the exponent is not a decision the report makes. Every finite product of countably infinite spaces is countably infinite, and recording `2^32` or a symbolic exponent costs space without changing which type has the larger state space. The tier keeps the distinction that matters: a product of countables (`ωⁿ`) ranks above a countable sum (`2ω`), because it has more independent infinite choices to narrow. That is the order a state-space report needs.
- `ε₀` caps unbounded degree. A finite polynomial cannot represent a construction whose number of factors is itself unbounded (`List of String`); the least fixed point of `α ↦ ω^α` is the smallest tier above every finite power, and it is countable, so it does not turn the estimate into an uncountable claim.
- `ε₀` does not absorb the lower terms: `ε₀ + 1` is above `ε₀`, and `2ε₀` is above `ε₀`. Keeping the coefficients makes addition distributive and keeps `a` meaningful.

**Order (Rule RP4.1.a.1).** Two numeric counts compare lexicographically by `(a, b, c, d)`: `ε₀` first, then `ωⁿ`, then `ω`, then the finite part. So `ωⁿ > 2ω`, `ε₀ + 1 > ε₀`, `2ε₀ > ε₀`, and `4ε₀ > 3ε₀`. For a finite count this is the ordinary numeric order. For the infinite tiers it is structural, not a cardinal inequality: the tiers themselves are countable ordinals. A count with a symbolic coefficient (Rule RP4.2) is not ordered unless the coefficients are provably ordered.

**Rendering (Rule RP4.1.a.2).** The report prints the polynomial in descending tier order, omitting zero terms and a coefficient of one: `0`, `1`, `ω`, `2ω`, `ωⁿ`, `2ωⁿ`, `ε₀`, `2ε₀`, `ωⁿ + 2ω + 1`, `ε₀ + ω + 3`. A finite count prints as its decimal value. The JSON form gives the four coefficients as numbers, or as symbolic expressions under Rule RP4.2, beside the rendered form.

#### 4.1.2 Arithmetic (Rule RP4.1.b)

Addition is componentwise:

```text
(aε₀ + bωⁿ + cω + d) + (a'ε₀ + b'ωⁿ + c'ω + d')
  = (a+a')ε₀ + (b+b')ωⁿ + (c+c')ω + (d+d')
```

Coefficients are natural numbers for a closed count and expressions in symbols under Rule RP4.2; they are never absorbed. This is the **natural sum**, not ordinal addition: `1 + ω` and `ω + 1` are both `ω + 1`. Zero is the empty sum and annihilates a product (`0 · x = 0`); one is the finite `1` and preserves it (`1 · x = x`).

Multiplication is bilinear over the tier table:

| × | 1 | ω | ωⁿ | ε₀ |
| --- | --- | --- | --- | --- |
| 1 | 1 | ω | ωⁿ | ε₀ |
| ω | ω | ωⁿ | ωⁿ | ε₀ |
| ωⁿ | ωⁿ | ωⁿ | ωⁿ | ε₀ |
| ε₀ | ε₀ | ε₀ | ε₀ | ε₀ |

Multiply the coefficients and add every contribution to its output tier. Written out, the product of `aε₀ + bωⁿ + cω + d` and `a'ε₀ + b'ωⁿ + c'ω + d'` is:

```text
ε₀: a·a' + a·(b' + c' + d') + a'·(b + c + d)
ωⁿ: b·b' + b·c' + c·b' + c·c' + b·d' + d·b'
ω:  c·d' + d·c'
1:  d·d'
```

In particular `2ω · 3ω = 6ωⁿ`, `ωⁿ · ω = ωⁿ`, and `(ωⁿ + ω)·ω = 2ωⁿ`. The `ε₀` row retains the coefficient (`2ε₀ · 3ω = 6ε₀`), so distributivity holds there too. The table is the tier operation, not ordinal multiplication: `ε₀ · ε₀` stays `ε₀` (with the coefficients multiplied), because `ε₀` is the cap.

Powers use the same table by repeated squaring on the four coordinates:

- `x^0 = 1` and `x^1 = x` for every count `x`, including `x = ε₀`.
- `ω^k = ωⁿ` and `(ωⁿ)^k = ωⁿ` for a finite `k > 1`; `ε₀^k = ε₀` for `k > 0`.
- An infinite base raised to an infinite exponent is `ε₀`: `ω^ω = ε₀`, `(ωⁿ)^ω = ε₀`.
- A finite base greater than one raised to an infinite exponent is `ω`: `2^ω = ω`. A base of `0` or `1` follows the identities `0^x = 0` (`x > 0`) and `1^x = 1`.
- A mixed sum raised to a finite power is multiplied out with the table; do not select its highest tier. `(1 + ω)^2 = 1 + 2ω + ωⁿ`, which prints `ωⁿ + 2ω + 1`.
- A power whose exponent is a finite bound the analysis cannot evaluate stays an expression with that bound; the report does not replace it with a guessed coefficient.

The arithmetic identities `b^0 = 1`, `b^1 = b`, `1^b = 1` and `0^b = 0` (`b` nonzero) hold for every tier.

#### 4.1.3 Collections and recursion (Rule RP4.1.c)

Count a collection from the model it denotes, not by iterating its recursive equation in the carrier. The equation alone cannot distinguish a fixed product from an unbounded number of factors; both eventually reach `ωⁿ`. The **label space** of a recursive step is the set of labels its constructors carry at that step — the element type of a `List`, the non-recursive field values of a variant. A finite label space and a finite number of recursive positions per step is `ω`; an infinite label space or an infinite number of recursive positions can reach `ε₀`.

- `List of A` is the type of finite sequences of `A`.
  - `List of !` is `1` (the empty list only).
  - `List of A` with `|A|` finite and at least `1` is `ω`: finitely many symbols per position, unbounded length.
  - `List of A` with `|A|` infinite is `ε₀`: the length is unbounded and each position ranges over an infinite set, so no finite power of `ω` bounds it.
- A tuple or a single-variant `data` type is a product: `(String, String)` is `ωⁿ`, a third `String` field in a variant does not change the tier, and `(bool, String)` is `2ω`.
- A `data` type whose variants are sums of products follows the same rules: a type with two `String`-only variants is `2ω`; a variant with two `String` fields is `ωⁿ`; a type with both contributes both terms.
- A concrete data type that denotes finite subsets or finite partial functions counts by that model. For a finite `A` with `n` values, the finite-subset type over `A` is `2^n`; for an infinite `A` it is `ω`, because a finite subset is a finite amount of data. A finite partial function with an infinite domain is `ω`, not the `ε₀` of the total function space. With a finite domain `D` and codomain `V`, each key is absent or holds a value, so the count is `(1 + |V|)^|D|`; a finite partial function from `bool` to `String` is `(1 + ω)^2 = ωⁿ + 2ω + 1`. A total function `A -> B` is the exponential of Rule RP4.1; it is not a finite partial function.
- A recursive type is counted by its construction. `Nat` (`Z` and `S(prev: Nat)`) is `ω`: each step adds one constructor and the label space is finite. `(Nat, Nat)` is `ωⁿ`, because it is the product of two independent recursive values; a type with one `Nat` field is `ω`, not `ε₀`.
- Do not promote a type to `ε₀` merely because a field is recursive. Promotion requires unbounded multiplicative depth: the recursive equation must multiply an infinite label space at each step, as `List of String` does. A settled `Nat` used once stays `ω`; two independent uses multiply to `ωⁿ`; a consumer of a recursive value does not collapse just because its child is recursive.

Worked examples:

| Type | Count |
| --- | --- |
| `bool` | `2` |
| `String` | `ω` |
| a `data` type with two `String`-only variants | `2ω` |
| a variant with two `String` fields | `ωⁿ` |
| a variant with three `String` fields | `ωⁿ` |
| `(bool, String)` | `2ω` |
| `(Maybe of String, Maybe of String)` | `ωⁿ + 2ω + 1` |
| `bool -> String` | `ωⁿ` |
| `String -> bool` | `ω` |
| `String -> String` | `ε₀` |
| `i64 -> String` | `ωⁿ` |
| `bool -> bool with Console` | `4ε₀` |
| `bool -> bool with Console + Diverge` | `9ε₀` |
| `String -> bool with Console` | `ε₀` |
| `List of !` | `1` |
| `List of bool` | `ω` |
| `List of String` | `ε₀` |
| `Nat` | `ω` |
| `(Nat, Nat)` | `ωⁿ` |

### 4.2 Capability-bounded types are symbolic (Rule RP4.2)

A type variable bounded by capabilities (`x: T is C`) has no number: the representation is not the programmer's, and across a package edge it is never known. Its cardinality is a **symbol**, and the cardinality of a signature that mentions it is an expression in such symbols.

This holds for a named type parameter (bound per use) and for a type variable whose bound makes it a choice point (bound per link unit, Rule CM8.1) alike, with one difference: a choice point gets a number at application link. Before link, a package's interface and the report give the symbol; after link, the link report (Rule RP5.4) gives the number of the chosen type. The number describes the chosen representation and does not change the symbolic bound in the interface, which must stay valid for every choice.

Substituting a number for the symbol over-counts the implementations of a polymorphic signature, because a polymorphic function can only use what its bounds provide. The report therefore gives two things for a signature with bounded type variables:

1. the **symbolic bound**: the expression of Rule RP4.1 with the symbols left in place;
2. the **parametric count**, only where it is finite: the number of distinct terms of the result type that can be built from the parameters and the members of the bounds. It is finite when no member of the bounds can be applied to its own result without end. Otherwise the report says `law-bounded` and gives no number: counting terms up to the capabilities' props is not decidable in general.

The four-term normal form of Rule RP4.1.a is the closed form of that expression; while symbols remain, the report prints the expression and does not claim a tier order.

**An unbounded type variable admits no inspection.** `show`, `==` and `clone` on a value of an unbounded type parameter are type errors (`docs/decisions/P6-resolution-round-2026-11-20.md` D10), and diagnostics, which print any value, return nothing to the program (`spec/diagnostics.md` Rule DG3.3). So `T -> T` with `T` unbounded has one implementation, and the parametric count is as above. The alternative debated was universal `show` and `==`: every type variable would then count as bounded by `Show + Eq`, the count would still exist but would be the count for that bound, and every signature's count would grow. It was rejected.

### 4.3 Who uses the count (Rule RP4.3)

Counting inhabitants is one service with several users inside the compiler:

- exhaustiveness of `match` and of handler clauses (`spec/pattern_matching.md` §6);
- generators: when the product of a prop's binder cardinalities is finite and no larger than the iteration budget, the driver enumerates every combination instead of sampling (`spec/properties.md` Rule P8.5); a count with a nonzero infinite tier is never enumerable;
- the report below.

Layout does not use the count. A type with more than one variant has a one-byte tag when it is an immediate (`spec/compilation_model.md` Rule CM3.5) and a link-unique tag in its descriptor when it is a cell, and each type has one layout (`spec/memory_management.md` Rule PR3.1): no tag is narrowed to `⌈log₂ n⌉` bits or shared with a field.

### 4.4 The report (Rule RP4.4)

`fixed compile --explain-cardinality` prints, per `data` declaration, its state space, and per public signature, its implementation space, each as a finite number or a four-term count (Rule RP4.1.a), or as a symbolic expression with the parametric count where Rule RP4.2 allows one. `--format json` gives the same data, with the four coefficients beside the rendered form. The symbolic cardinality of every public declaration is part of the interface tier (`spec/compilation_model.md` Rule CM7.1), so two versions of a package can be compared.

## 5. The tool interface

### 5.1 Tools read; they do not plug in (Rule RP5.1)

The compiler has no plugin interface. An external tool reads:

- the **interface tier** of a compiled package, and the **body tier** where a package ships one (`spec/compilation_model.md` §7);
- the **reports**: `--explain-memory`, `--explain-cardinality`, `--explain-selection` (Rule RP5.4) and `verify --coverage`, each with `--format json`;
- the **lock file** and the **profile file** (Rule RP5.3).
- the **exit code** of every command (Rule RP5.5).

A tool that only reads cannot change what a program means or whether it compiles, and it does not break when a compiler pass changes.

### 5.2 When an analysis belongs in the compiler (Rule RP5.2)

An analysis is a compiler pass when a compiler decision consumes its result. Cardinality (Rule RP4.3) and cost (`spec/cost.md` §8) are passes for that reason. An analysis nothing in the compiler consumes stays outside it.

A way for a library to add a checked discipline to the type system (a third grading beside quantities and effect rows) is not specified.

### 5.3 The lock file and the profile file (Rule RP5.3)

Two files besides the sources are inputs of an application build:

- The **lock file** (`spec/compilation_model.md` Rule CM8.5) holds, per choice point bound at link, the satisfaction chosen, its score, and any pin the user wrote. It records no satisfaction site: every site is resolved at package compile (`spec/type_system.md` Rule 8.4.b). It is checked in.
- The **profile file** (Rule CM8.6), one per target, holds the measurements of `spec/properties.md` Rule P7.9: the supply side (cost of one call per capability method, satisfaction, size class and target; machine facts) and the demand side (region, request and usage counters). Each record carries its source (production or test run) and its target label.

A build **reads** these files; it never measures. Measurement is a separate step (a test build, or a production run of an instrumented binary) that writes the profile file. A build is therefore a function of its sources, its dependencies, the lock file and the profile file: two machines with the same four inputs make the same choices and the same clones. A profile whose target label differs from the build's target is ignored, and the build uses static data (`spec/cost.md` Rule CT8.2). Continuous integration builds from the checked-in lock file and does not re-measure.

The link step writes the lock file. A choice that changes is a diff in it, and a pin in it is how a user overrides the selector (`spec/type_system.md` Rule 6.5.5). Whether the profile file is checked in is the project's decision; a build without it is reproducible from the other three inputs, with static weights.

Tools read both files as they read the reports. A tool that rewrites the lock file (to pin a choice) changes a build input, as editing a source does; it does not plug into the compiler (Rule RP5.1).

### 5.4 The link report (Rule RP5.4)

`fixed link --explain-selection` (and the same flag on `fixed compile` and `fixed ship` of an application) prints, and with `--format json` emits, per choice point bound at link:

- the choice point: its declaration, the bound set and the usage vector the link step summed (Rule CM8.3), with the source of each usage number (production profile, test run or static weight);
- each candidate satisfaction: viable or not, and for a non-viable one the cost prop it failed (`spec/cost.md` Rule CT8.1); for a viable one its score and, per used method, the cost of one call, each marked **measured** (with the profile's target label) or **static** (from the bound-class table), and the satisfier's own cost claims beside the capability's contract (`spec/cost.md` Rule CT6.1);
- the chosen satisfaction and the **disambiguation rule that decided** (`spec/type_system.md` Rule 6.5.5): a pin, the choice recorded in the lock file within `ε`, the highest score, the capability's own package, or declaration order;
- whether the choice differs from the one in the lock file before this link;
- the cardinality of the chosen type (Rule RP4.2);
- props whose `tested` status was obtained under another binding of a choice point than this link's (`spec/properties.md` Rule P7.4);
- the facts-lane decisions that rest on `assumed` props (`spec/properties.md` Rule P7.8.a), with the props.

The report is the place to see why a representation was chosen; it is not needed to reproduce the choice, which the lock file records.

### 5.5 Exit codes (Rule RP5.5)

**Status: class list confirmed by the owner (2026-11-21), with `not supported yet` moved to `99`; number `13` is free** (`docs/decisions/P6-resolution-round-2026-11-20.md`, fourth round, decision 1). Built for the classes the compiler can reach today (`DiagCode.errorClass`, `Main.exitCode`); not built: the sum of commands `a + b`, and the classes of phases that do not exist yet.

The exit code is the first thing a tool reads from a run, so it is specified here, with the other things tools read. No spec specifies the command line as a whole (`spec/properties.md` Rule P7.6 gives the verification flags); `docs/plans/implementation-plan.md` lists the commands and flags.

Every command uses this one table: the checker `fixed <file>...`, `fixed compile`, `fixed link`, `fixed ship`, `fixed verify`, and a composition `a + b`. Exit code `0` means no failure. Every other exit code is the number of one **error class**. A command returns only the classes of the phases it runs.

| Exit code | Class | Where it arises | Diagnostic codes |
|---|---|---|---|
| `0` | no failure | — | none; warnings (`W…`, `T005` where the typer reports a gap, `warning[E081]`) and notes never set an exit code, unless a flag makes one an error (`--strict-pbt`, class `11`) |
| `1` | internal compiler error | any command | none: the compiler itself faulted (an uncaught exception, a failed assertion); it is also what the JVM returns for an uncaught exception |
| `2` | usage error | the command line, before any phase runs | none: an unknown command or flag, a missing or extra argument, flags that exclude each other (`--no-pbt` with `--strict-pbt`, `spec/properties.md` Rule P7.6), an input file that cannot be read |
| `3` | scan error | package compile: scanner | `E001`–`E009` |
| `4` | parse error | package compile: parser | `P001`–`P099` |
| `5` | name error | package compile: namer and typer | `T001`, `T003`, `T004`; `E063` |
| `6` | type error | package compile: typer | `T002`, `T006`, `T007`, `T008`, `T010`, `T013`, `T014`, `T022`, `T023`, `T024`, `T025`, `T027`, `T028`, `T029`, `T031`, `T032`; `E062`, `E065`, `E066` |
| `7` | ambiguous satisfaction | package compile: typer (`spec/type_system.md` Rule 8.4.b) | `E061`; `T011` |
| `8` | effect error | package compile: typer (effect inference) | `E080`, `E084`, `E093`; `T009`, `T015`, `T016`, `T018`, `T019` |
| `9` | pattern error | package compile: typer | `E090`, `E091`, `E092`, `E094`, `E095`; `T020` |
| `10` | malformed prop | package compile: typer and static prover | `E100`, `E104`, `E105` |
| `11` | prop failed | package compile: static prover; test build | `E102`; `E107` (an integer refinement not proved where it is established); under `--strict-pbt`, a budget overrun (`W103`) |
| `12` | quantity error | package compile: quantity inference | `E070`, `E072`, `E083` |
| `14` | `fip` contract or confinement violated | package compile and the clones of a release link: demand and memory inference | `E071`, `E110`, `E085` |
| `15` | cost bound not met | package compile: cost checker | `E120` |
| `16` | no viable representation | selection, at package compile or application link | `E060` |
| `17` | link error | application link | none yet: a package or symbol the link unit lacks, a lock file or profile file that cannot be read |
| `18` | toolchain failure | `ship`, `link`: the native toolchain | `B004` (`clang` missing or failed); an output file that cannot be written |
| `19` | no generator | test build | `E101`, `E101a` |
| `20` | prop not proved | after the verifiers, under `--require-proved` | `E106` |
| `99` | not supported yet | package compile: a limit of the compiler, found by the namer, the typer or lowering | `T005` where the namer reports it as an error; `T026`; `B002`, `B003`, `B005` |

**Several classes in one run.** The exit code is the smallest class number among the errors the run reported. The rule is deterministic and does not depend on the order in which diagnostics are printed. A phase that fails stops the phases after it, so the errors of one run come from the first phase that failed, or from the front end (scanner, parser, typer), which keeps going on partial input; the classes of errors in the program that the front end finds (`3` to `10`) come first and in front-end order, so the code names the first phase that failed. A limit of the compiler that the namer finds (`T005`, class `99`) ranks below them: an error in the program is fixed first. The application link and the test build run only after a package compile that succeeded, and among their own classes the number decides. An internal compiler error outranks everything the run reported; a usage error stops the run before any phase.

**Composed commands.** `a + b` runs `a`, then `b` only when `a` exited `0`. Its exit code is that of the first stage that did not exit `0`, or `0`. `fixed verify + ship` with a failed prop exits `11` and builds nothing.

**Range.** Classes take the numbers `1` to `64`; `21` to `64` are free for classes added later, and `65` to `125` are not used. The shell reserves `126` and above (`126` cannot execute, `127` not found, `128 + n` killed by signal `n`), so a class never collides with them. A run killed by a signal shows the shell's code, not a class. The exit code of a program that `fixed ship` built is the program's own and is not covered here: a run-time abort, such as an unhandled `Overflow` at `main` (status `101`, `spec/effects.md` Rule E3.6.j), is not a class.

**Classes and diagnostic codes.** A class is not computed from a code. Codes are grouped by origin: in the compiler, `E` for the scanner and a few later checks (`E072`, `E110`), `P` for the parser, `T` for the typer and `B` for the backend (`compiler/src/main/scala/fixed/util/Reporter.scala`); in the specs, `E` codes, and `W` codes for warnings, by decade per document (`E06x` and `W060` satisfactions, selection and scope, `E07x` quantities, `E08x` effects, `E09x` patterns, `E10x` props, `E11x` memory, `E12x` cost). A class gathers codes by what the user must fix, which cuts across both: the `T` family spans six classes, and the `B` family two. Where the compiler and the specs number the same error differently (`T015` and `E080` for an undeclared effect, `T020` and `E091` for a match that is not exhaustive), both are in one class. Every code that is an error belongs to exactly one class, and a new code is given its class in this table when it is introduced.

## 6. Open questions

- **OQ-RP1 — Typed-tree access for tools.** A tool that needs the typed tree of a package's own source has no format to read (`spec/compilation_model.md` Rule CM7.4 serialises none). A JSON dump is the obvious candidate; wait for a tool that needs it.
- **OQ-RP2 — Specification status through capability tables.** A prop that calls a capability method constrains the requirement. Whether it also constrains each satisfying type's implementation is counted per (prop, satisfaction) pair when the prop is inherited (Rule P7.7.a), and not otherwise.
- **OQ-RP3 — Counter cost.** Region counters in fused loops cost a load and a store per step. Whether test builds need sampling instead is to be measured.
- **OQ-RP4 — Counting refinements.** Which refinement shapes the prover can count (intervals, finite sets) and which fall back to the base type.
- **OQ-RP5 — Format and merging of the profile file.** How several runs (test runs on several machines of one target, production samples) are merged, and how old records age out, is not specified.

## 7. Cross-references

| Document | Relationship |
|---|---|
| `spec/properties.md` | Statuses (Rule P7.7), the verdict and its exit code (Rule P7.7.b, Rule RP5.5), lanes (Rule P7.8), measurement (Rule P7.9), `unreachable` (Rule P9.2), sampling (Rule P5.3.b), enumeration (Rule P8.5) |
| `spec/compilation_model.md` | Clone keys label counters; request counters are the measured weight of Rule CM5.4; the interface tier carries cardinality; choice points (Rule CM8.1), summaries (Rule CM8.3), the lock file (Rule CM8.5), the profile file (Rule CM8.6) |
| `spec/cost.md` | Rule CT8.2 reads the counters as usage; Rule CT8.1 viability is shown in the link report |
| `spec/diagnostics.md` | Rule DG3.3 keeps diagnostics out of the parametric count |
| `spec/pattern_matching.md` | §6 exhaustiveness shares the inhabitant count |
| `spec/type_system.md` | §6.4 refinement capabilities lower the count; Rule 6.5.5 names the disambiguation rule the link report shows |
| `spec/memory_management.md` | §19 is the first report of the family |
| `docs/decisions/P5-reports-and-tools.md` | The decision and the alternatives rejected |
