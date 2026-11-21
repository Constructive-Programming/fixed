# Fixed Type System

**Status:** Draft v0.5.5
**Specifies:** typing of Fixed source as defined by `spec/syntax_grammar.ebnf` v0.4.15.
**Last revised:** 2026-11-21

**Changes from v0.5.4.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Shadowing of prelude names"). New §4.3: the scope order (local binders, the package's declarations and `use`s, the prelude); a package may hide a prelude name, with `warning[W060]` (Rule 4.3.a), and so may a `use` (Rule 4.3.b); collisions inside a package are `T003` (Rule 4.3.c); a type-level local binder named like a prelude type is `T004` (Rule 4.3.d); the prelude is the package `std.prelude`, reached with `use std.prelude.List as PreludeList` (Rule 4.3.e, grammar v0.4.15); diagnostics print paths, never internal names (Rule 4.3.f); syntax and derivation always mean the prelude's entities (Rule 4.3.g); interactions with list literals, ascription, satisfactions, visibility and exports (Rule 4.3.h). New Rule 4.1.b states `T004` for every binder position. The example of Rule 8.4 imports a satisfaction from a program package (`shop.box`) instead of `std.maybe.Maybe`: `Maybe` is the prelude's, whose satisfactions are always in scope.

**Changes from v0.5.3.** Applies the owner's decision of 2026-11-21, fourth round (P6, "Integer overflow and division"). §5.7: what arithmetic does when a result leaves its type is `spec/effects.md` §3.6; constant arithmetic that does not fit is `T025` (§5.7, Rule 6.3.3). §8.5.1: a refinement on a bounded integer type is established only by `proved` or `assumed` (`E107`, `spec/effects.md` Rule E3.6.e). New OQ-T3 records the owner's note that dependent types may be a better answer to raising the integer size.

**Changes from v0.5.2.** Applies the owner's decisions of 2026-11-21, fourth round (P6, "Owner's decisions (fourth round)"). New Rule 5.10: a list literal is an abstract constructor call through `Sequencing`, and a choice point unless a concrete expected type or an ascription fixes its type; its bound set is `Sequencing of E` and what the context requires. New Rule 5.11: the type ascription `e is T` on any expression fixes its type and is not a conversion (`T024` when `T` is a bound). Rule 5.1.1 and Rule 5.7 refer to both. The example of Rule 7.3.c, which wrote `let x: T = …`, uses an ascription. New Rule 7.4.b.3: derived `fold`, `unfold` and `map` of linear data run in bounded native stack; trees do not yet (decision 3).

**Changes from v0.5.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). Rule 5.1.2: no witness travels with an anonymous result, and a requirement's anonymous result is one choice point shared by its implementations. Rule 8.4: the sixth resolution level is removed; a derived or unnamed satisfaction gets no preference over a named one, so the ambiguity error stands, and `using Money satisfies Ord` names such a satisfaction by its pair.

**Changes from v0.5.0.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): derived, built-in and prelude satisfactions are always in scope, and "sees" is defined (Rule 8.4); deferred sites are bound by level 5 (Rule 8.4.b); the test build's application root; private satisfactions (Rule 8.6.a); the pool's satisfaction is resolved at the site that created the choice point (§6.5.2); rule 4 of Rule 6.5.5 names the first capability of the bound set, and built-ins precede the standard library's source declarations; examples that used `let x: T = …` use parameter types (§5.1.1, §8.4, §8.5); new codes `E061`–`E065`; wrong references and "(TBD)" cross-references repaired.

**Changes from v0.4.3.** Applies decision record P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`). New Rule 5.1.1: an abstract type is bound per use, per link unit (a *choice point*) or per package. Rule 4.1: a type variable local to a `data` variant field, and an anonymous bound in a field, is a choice point (closes OQ-T2). New Rule 5.1.2: the signature decides who picks the representation of a result (`-> is C` against `-> R is C`). The inferred caller-chosen / callee-chosen split is withdrawn. New Rule 5.1.3: `show`, `==` and `clone` on an unbounded type parameter are type errors. New §8.6: visibility (`pub`). §6.5: selection is staged between package compile and application link. The score of Rule 6.5.4 is usage × cost, with the bound-class table and static weights as fallback. **Rule 6.5.5 is replaced**: a tie never halts. Five disambiguation rules decide, and the only error is zero viable candidates. §6.5.6–§6.5.8 give the commit at link and the lock file. §8.4 is rewritten as Rules 8.4, 8.4.a and 8.4.b. Coherence per link unit is withdrawn: a link unit may hold several satisfactions of one pair, which a `using` or the scope resolves, and a satisfaction on a type's own parameter becomes part of the type. §8.5 no longer contradicts Rule 8.1.a. Rule 8.2 refers to the table of a satisfaction, not of a type.

**Changes from v0.4.2.** Aligned with `spec/compilation_model.md` (baseline versions, witnesses, clones, packages) and `spec/cost.md` (cost contracts). §6.5: a representation is chosen where a value originates and travels by witness; the bound set is closed over the package and the signatures it imports; cost contracts are an input, a viability criterion (§6.5.3) and the source of two scoring dimensions (§6.5.4); the cache key includes the lifetime class. Rule 6.1.1 Phase 2 no longer re-checks a default body per satisfying type. Rules 7.4.b.1 and 7.4.b.2 are restated without "after monomorphisation": they are the mandatory specialisations of Rule CM5.5. Rule 7.3.b covers baseline code. Rule 8.2: the panic of an `impossible` constructor lives in the capability table. §8.4: satisfactions are coherent per link unit. §8.5 points to Rule CM4.5 for results typed by a capability bound. New OQ-T2 (type variables of variant fields).

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
- Memory management (lifetime classes, regions, Perceus reference counting) — see `spec/memory_management.md`.
- How choice points are bound at link, summaries, the lock file and the profile file — see `spec/compilation_model.md` §8. §6.5 gives the selection rules.
- How code is compiled for a value whose representation is not known where it is used (baseline versions, witnesses, clones), and the package ABI — see `spec/compilation_model.md`.
- Cost contracts on capability members — see `spec/cost.md`.

## 2. Dependencies

- `spec/syntax_grammar.ebnf` v0.4.12 — every syntactic form referenced here is defined there.
- `spec/compilation_model.md` — choice points and their binding at link (§8), witnesses (§4), packages (§6).
- `spec/diagnostics.md` — `inspect`, `trace` and interpolation in diagnostic sinks.
- `examples/01_basics.fixed` … `examples/11_properties.fixed` — every rule in this document is anchored to at least one example.

## 3. Two worlds: types and capabilities

Fixed makes a hard distinction between what the **programmer writes** and what the **compiler tracks internally**:

| Surface (programmer) | Internal (compiler) |
|---|---|
| **Capabilities** — what a value can *do* (`Sequencing`, `Numeric`, `Folding`) | **Types** — concrete representations the compiler chose |
| Capability bounds: `is Cap`, `N is Cap` | Type variables, type-class dictionaries |
| `data` declarations: closed sets of variants | Tagged unions / structs |
| `of` application | Type application |

A user never names "the type" in the conventional sense. Where the user of a declaration supplies the type, it is a type parameter (Rule 5.1.1). Where nobody does, the compiler chooses a concrete representation for the bound, once per program (a *choice point*, §6.5). Code that handles the value without knowing the representation receives a witness for it (`spec/compilation_model.md` §4). For the rest of this document, "type" refers to the compiler's internal view; "capability" is the user-facing thing.

## 4. Identifier scoping

### 4.1 Type-variable introduction (resolves Q8)

**Rule 4.1.** An `UPPER_IDENT` in a type-binding position introduces a fresh type variable iff that name is not already bound in the enclosing scope. A **type-binding position** is any of:

- `IDENT is Bound` (named alias) — anywhere in a function signature, cap method signature, data variant field, or `of` argument list.
- A bare `UPPER_IDENT` in an `of` argument list (declares a type parameter; phantom-ness is inferred per §5.9).
- A free occurrence of `UPPER_IDENT` that does not refer to a globally-declared cap, data, type, or effect.

Subsequent occurrences of the same `UPPER_IDENT` within the same signature **refer to the same type variable**. Intra-signature shadowing is a **compile error**.

**Rule 4.1.b (Re-binding).** In the binder positions (`IDENT is Bound`, a bare name in the `of` clause of a `data`, `cap` or `effect` declaration, and a row variable, `spec/effects.md` Rule E8.1), a name that an enclosing signature, the package or the prelude already binds is `error[T004]: <Name> is already bound in an enclosing scope`. A signature that states its own variable again adds bounds to it (`-> N is positive()`). In a position that is not a binder, a bound name is a reference: `x: List of u8` uses `List`. (Stated in v0.5.5 with §4.3; the compiler already reports `T004` for `IDENT is Bound`.)

**Scope.**

- For `fn`: the entire signature (parameters + return type + `with`-clause).
- For `cap` / `data` / `effect`: the entire body (including `extends` clauses).
- For nested `fn`: each inner function has its own scope; type variables introduced in the outer signature are still visible.

**Type variables of variant fields (Rule 4.1.a, closes OQ-T2).** A type variable introduced in a field of a `data` variant, and not declared in the data type's `of` clause, is local to the data declaration. The same holds for an anonymous bound written as a field's type. Such a variable is not a parameter of the data type. It is a **choice point** (Rule 5.1.1, item 2): one representation for every value of the data type, chosen once per link unit. No cell stores a witness for it.

```
data Color:
    Red
    RGB(red: N is Numeric, green: N, blue: N)   // N: one choice for all Colors

data Color2 of N is Numeric:                   // N: chosen by each user of Color2
    RGB2(red: N, green: N, blue: N)
```

A user who wants to choose writes the variable in `of`. Rules 5.9, 7.4.c and 7.4.d speak of `of` parameters, and a field-local variable is not one. It is never phantom, because it occurs in a field. It is never the target of an auto-derived `Functor`. An accessor of its field returns a value of the choice point's type.

Reason: a per-value variable would need a witness in every cell. A per-use variable is an `of` parameter, which the user can already write.

**Examples.**

```
// `examples/01_basics.fixed:30`  —  N introduced once, referenced twice
fn fibIter(n: N is Numeric) -> N: ...
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

### 4.3 Scopes and the prelude (Rule 4.3)

Decided by the owner (`docs/decisions/P6-resolution-round-2026-11-20.md`, fourth round, "Shadowing of prelude names"): a program may declare a name that the prelude declares and then means its own entity; the prelude's stays reachable by a path; syntax and derivation always mean the prelude's entities; the same holds for a name brought in by `use` (S1); a local type-level binder that would hide a prelude type is an error (S2). Choices the owner did not state are marked.

**Rule 4.3 (Scope order).** A name at a site means the first of these that declares it:

1. a **local binder**: a type variable of the enclosing signature or declaration (Rule 4.1), a row variable (`spec/effects.md` Rule E8.1), a parameter, a `let` or pattern binder, a lambda parameter, a nested `fn` (§4.2);
2. the **package**: its top-level declarations, in any of its files, and the names that the `use` declarations of the site's file bring in;
3. the **prelude**, the package `std.prelude` (Rule 4.3.e).

**Rule 4.3.a (Hiding a prelude name).** A package may declare at top level a name that the prelude declares: a data type, a capability, an effect, a type alias or a function. In the package the name then means the package's declaration (level 2). The compiler warns at the declaration: the option the owner chose included the warning, and the answer did not repeat the word)*: The warning `W060` is confirmed by the owner and is always given for a hidden name. Selecting a named satisfaction with `using` (Rule 8.4.c) hides no name and gives no warning.

```
warning[W060]: `List` hides the prelude's data type `std.prelude.List` in this package
  --> shapes.fixed:3:6
   = help: the prelude's stays reachable: `use std.prelude.List as PreludeList`
```

A warning sets no exit code (`spec/reports.md` Rule RP5.5). The declaration is valid, `pub` or private.

**Rule 4.3.b (A `use` that brings in a prelude name; S1).** A name that a `use` brings into a file for an entity other than the prelude's own, and that the prelude also declares, means the imported entity in that file, with `W060` at the `use`. `use geometry.List as Polyline` brings the entity in under another name and hides nothing.

**Rule 4.3.c (One meaning per name in a package).** The specs said nothing about collisions inside level 2 *(author's choice)*:

- Two top-level declarations of a package with one name are `error[T003]: duplicate definition`, as today.
- A top-level declaration of the package and a name that a `use` brings into one of its files: `T003` at the `use`, naming both.
- Two `use` declarations of one file that bring in one name for two different entities: `T003` at the second. Bringing in the same entity twice is allowed.

A `use … as …` resolves each case. A named satisfaction's name is a type-level name of its package (Rule 8.4) and follows these rules.

**Rule 4.3.d (Local binders; S2).** A type-level local binder (a type variable introduced by Rule 4.1, an `of` parameter, a row variable) whose name the prelude declares as a data type, capability, effect or type alias is `error[T004]: <Name> is already bound in an enclosing scope`, by Rule 4.1.b, as re-binding a name of the package or of an enclosing signature is: the prelude is the outermost enclosing scope. So `fn f(x: List is Show)`, `data Box of Maybe` and a row `with Order` are errors; `fn f(x: List of u8)` is a use of `List`, not a binder. Value-level binders are outside this rule *(author's choice)*: a parameter, `let` or pattern binder named like a prelude function hides it in its scope, as it hides a function of the package (§4.2, `spec/pattern_matching.md` §3.2), with no warning.

**Rule 4.3.e (The prelude's path).** The prelude is the package `std.prelude`, beside the standard modules such as `std.io` (`README.md`, `use std.io.Console`), and every prelude declaration has the path `std.prelude.<Name>` *(author's choice of the path)*. A package that hides a prelude name reaches the prelude's entity by importing it under another name (grammar v0.4.15, `UseRename`):

```
data List of A:                          // warning[W060]: hides std.prelude.List
    Node(items: is Sequencing of A)

use std.prelude.List as PreludeList

fn toPrelude(xs: List of u8) -> PreludeList of u8 = ...
```

`use std.prelude.List` without `as` in that package is `T003` (Rule 4.3.c). A `use` of a prelude name that nothing hides is allowed and changes nothing. A type is never written with a path in a signature or an expression: `use … as` is the one way to name a hidden entity *(author's choice: the grammar's type references are single names, and a path in an expression would read as a field access)*.

**Rule 4.3.f (Names in diagnostics).** A diagnostic names an entity by the name that means it at the site. Where that name means another entity there, it prints the entity's path: `std.prelude.List` for the prelude's, `geometry.List` for another package's. It never prints a name of the compiler's own making. Where a fix needs an entity the site cannot name, the help line gives the `use … as …` that makes it nameable:

```
error[T022]: `show` needs the bound std.prelude.Show on T
   = help: `use std.prelude.Show as PreludeShow`, then write `T is PreludeShow`
```

**Rule 4.3.g (Syntax means the prelude).** There is no rebindable syntax. Whatever is in scope:

- a list literal is built through `std.prelude.Sequencing` (Rule 5.10); a string literal is a `String`, a character literal a `char`, `true` and `false` are `bool`, a tuple is the prelude's tuple, `()` is the unit, and an integer or float literal takes a primitive type or goes through `std.prelude.Numeric`'s `fromI64` (Rule 5.7);
- `==` and `!=` call `std.prelude.Eq`; `<`, `<=`, `>` and `>=` call `std.prelude.Ord` and fold its `std.prelude.Ordering`; `+`, `-`, `*`, `/`, `%` and prefix `-` call `std.prelude.Numeric`; prefix `!` calls `std.prelude.Boolean` (`spec/stdlib.md` §4.5–§4.9); arithmetic performs `std.prelude.Overflow` and `std.prelude.DivideByZero` (`spec/effects.md` Rule E3.6);
- the built-in `show` of the primitive types is `std.prelude.Show`, and the derivation rules (§7.4; `spec/stdlib.md` §4.7, §4.8, §4.10, §4.11) derive the prelude's capabilities (`Folding`, `Functor`, `Show`, `Eq`, `Ord`, `Clone`) and nothing else;
- the diagnostic sinks are the prelude's `inspect` and `trace` (`spec/diagnostics.md` Rule DG3.1); a package's own `inspect` is an ordinary function.

Consequences:

- **`==` on a value of a package's type** finds the satisfaction of (`T`, `std.prelude.Eq`): derived for a data type whose fields satisfy it (§7.4), or declared. A package with its own `cap Eq` declares it as `T satisfies PreludeEq` after `use std.prelude.Eq as PreludeEq`. There, `T satisfies Eq` satisfies the package's own `Eq`, which `==` never calls. The same holds for `<` and `Ord`, arithmetic and `Numeric`, `show` and `Show`.
- **A package's own capability named like a prelude capability is never derived**, and never connects to syntax.
- **A package's own `effect Overflow`** is not what arithmetic performs, and a clause for it handles no arithmetic.

**Rule 4.3.h (Interactions).**

- **List literals.** A package's own sequence type, named `List` or otherwise, takes list literals only by satisfying `std.prelude.Sequencing`: it is then a candidate at a literal's choice point (Rule 5.10, case 3) and an expected type a literal can have (case 1). Declaring `List` does not make literals the package's lists.
- **Ascription.** The type in `e is T` is resolved by Rule 4.3 like any name: `[1, 2] is List of u8` in a package that declares `List` names the package's `List`, and is valid only if that type satisfies `std.prelude.Sequencing` (otherwise `T010`, Rule 5.10, case 1). `[1, 2] is PreludeList of u8` names the prelude's.
- **Satisfactions and `using`.** The capability of a `satisfies` declaration, of a `use … satisfies` tail and of `using T satisfies C` is resolved by Rule 4.3, so it is the package's capability where the package hides the prelude's. The prelude's satisfactions stay always in scope (Rule 8.4) whether or not their names are hidden: hiding changes what a name means, not which satisfactions exist. `Maybe satisfies Optional` keeps serving `std.prelude.Optional`, and never a package's own `cap Optional`.
- **Visibility.** A declaration hides a prelude name in its own package whether it is `pub` or private (§8.6); `pub` decides only who else may name it.
- **Exports.** A package that exports `pub data List` hides nothing in its importers. An importer means the prelude's `List` by `List` until it writes `use geometry.List`, which hides the prelude's there with `W060` (Rule 4.3.b), or `use geometry.List as Polyline`, which hides nothing. A signature of `geometry` that mentions its `List` means `geometry.List` in the importer whatever the importer's scope, and the importer's diagnostics print `geometry.List` where `List` means another entity (Rule 4.3.f).

**Built today** (`docs/decisions/P0-spec-review-2026-11-20.md` §4). Built: the prelude is typed, walked and lowered in its own scope (`SymbolTable.prelude`), so the order of Rule 4.3 holds and Rule 4.3.g holds, including that a program's own `cap Show` or `cap Functor` is not derived; `Namer` reports `T004` for a named type variable (`IDENT is Bound`) that re-binds any bound name, prelude names included. Not built: `T004` for a bare `of` parameter and for a row variable (the namer reads a bound name there as a reference or as an effect); `W060` (hiding is silent, for declarations and for `use`); the path `std.prelude` and `use … as` (the parser has no `as` in a `use`, and no module is named `std.prelude`); Rule 4.3.f (a diagnostic or a hint can print the compiler's internal name `prelude.<name>`, which a program cannot write); Rule 4.3.c for `use`d names (the namer gives `T003` only to a declaration that follows a `use` of a name it could not resolve; otherwise the later binding wins). `docs/plans/implementation-plan.md`, Phase 4.2, item 23.

## 5. Type expressions

The grammar of type expressions is defined in `spec/syntax_grammar.ebnf` §10. This section defines their meaning.

### 5.1 Capability bounds (`is`)

`is Cap` denotes "satisfies the capability `Cap`."

Two forms:

| Form | Meaning |
|---|---|
| `is Cap` | Anonymous bound: each occurrence in a signature is a *fresh* abstract type. In a parameter position it is a type parameter; in a result or field position it is a choice point (Rule 5.1.1). |
| `IDENT is Cap` | Named alias: every occurrence of `IDENT` in the signature refers to the *same* type variable, all of which must satisfy `Cap`. |

**Composition (§10 grammar `CapBoundChain`):** `is C1 + C2` means "satisfies both `C1` and `C2`." `+` is associative and commutative; `is C1 + C2` ≡ `is C2 + C1`.

```
fn count(c: is Folding) -> u64                          // anonymous: any Folding
fn fibIter(n: N is Numeric) -> N                       // named: input and output share type
fn evenDoubled(numbers: C is Filtering of (N is Numeric) + Functor of N) -> C
//              ^reused C    ^reused N as element type
```

#### 5.1.1 Binding times (Rule 5.1.1)

An abstract type is bound at one of three times.

1. **Per use: a type parameter.** A named bound (`x: T is C`, `-> R is C`), an `of` parameter (`data D of T`), and an anonymous bound in a parameter position (`c: is Folding`). The user of the declaration picks the type: the caller of a function, or the writer of `D of u8`. The witness is a hidden parameter (`spec/compilation_model.md` Rule CM4.3).
2. **Per link unit: a choice point** (Rule CM8.1). An anonymous bound in a result position (`-> is C`) or in a field (`xs: is Sequencing of Json`). A type variable local to a `data` variant field (Rule 4.1.a). An abstract constructor call whose type is not a type parameter of the enclosing function (`Optional.some(42)` where `is Optional of u64` is expected, §8.5); a list literal whose type no concrete expected type or ascription fixes is one (Rule 5.10). There is one choice for the whole program. It is made at application link from summaries (Rule CM8.4) and bound by the linker (Rule CM8.2).
3. **Per package: a choice point whose values never leave the package.** This is decided when the package is compiled (§6.5). It is an optimisation of item 2 and is not visible in the language: the program means the same thing either way.

Nothing is bound per value: no cell stores a witness.

Reason: whoever supplies a value of a type parameter knows its type. A choice point has no such user. The package that declares it cannot see how the rest of the program uses it, so the choice waits for the link step, which sees the summaries of every package.

#### 5.1.2 Result types (Rule 5.1.2)

The signature decides who picks the representation of a result. The body cannot change the calling convention.

- **`-> is C` (anonymous).** The result is a choice point owned by the function. The caller treats the value as opaque: it may use only the members of `C`. No witness travels with it: the witness of the choice point is bound at link (`spec/compilation_model.md` Rule CM4.5). All values the body returns have one type. If the body constructs data directly, that data type is the choice point's only candidate. An abstract constructor call for the result is the choice point (§8.5).
- **`-> R is C` (named, `R` in no parameter type).** `R` is an ordinary type parameter: the caller picks it and passes its witness. A body that returns a concrete data type where `R` is expected is a type error: `Maybe.Just(x)` is a `Maybe`, not an `R`. An abstract constructor call for `R` stays abstract. It resolves at the caller, through the capability table in `R`'s witness.
- **A requirement whose result is `-> is C`** (`fn compare(other: Self) -> is Ordering`) follows the first case, with one difference: the choice point is owned by the requirement and shared by every satisfaction's implementation of it, so that a call through a capability table knows the type of the result without a witness. An implementation that constructs data directly restricts the choice point to that data type; two implementations that restrict it to different types leave no viable candidate (`E060`, §6.5.3).

```
fn parse(s: String) -> is Optional of u64 = ...     // the function's choice point
fn emptyOf() -> R is Optional of u64 = Optional.none // the caller picks R
fn wrong() -> R is Optional of u64 = Maybe.Nothing    // ERROR: Maybe is not R
```

Reason: if the convention followed from the body, a change to a body could change the ABI (`spec/compilation_model.md` Rule CM6.7).

#### 5.1.3 Operations on a type parameter (Rule 5.1.3)

A value whose type is a type parameter or a choice point has the members of its bounds and nothing else. `show`, `==`, `!=`, `<`, `<=`, `>`, `>=` and `clone` are members of `Show`, `Eq`, `Ord` and `Clone` (`spec/stdlib.md` §4). On a value of an unbounded type parameter, each of them is a type error, `error[T022]: <member> needs a bound on <T>`. Write `T is Show`, `T is Eq`, `T is Ord` or `T is Clone`. The same holds inside a type: `show` on a `List of T` needs `T is Show`, because the derived `Show` of `List` calls the `Show` of its element.

A value of an unbounded type parameter can only be moved: bound to a name, passed, returned, stored in a field or captured. Nothing else may be done with it, except to hand it to a diagnostic sink (below).

```
fn keep(x: T) -> T = x                          // allowed: moves x
fn label(x: T) -> String = x.show               // ERROR: T has no bound Show
fn label(x: T is Show) -> String = x.show       // allowed
```

Diagnostics have their own mechanism. `inspect(x)` and `trace("…{x}…")` accept a value of any type, with no bound. They write to the diagnostic channel, and their text never becomes a value the program holds (`spec/diagnostics.md`, Rules DG3.1–DG3.6). `Show` stays a lawful capability and is not the diagnostic mechanism.

Reason: a function of type `T -> T` has one implementation only if it cannot inspect `T`. Baseline code has no table to call for an unbounded `T` (`spec/compilation_model.md` Rule CM4.3).

### 5.2 `of` application

`Cap of A` applies `A` to `Cap`'s first type parameter. Multi-parameter caps use a tuple: `Cap of (A, B)`.

```
cap Sized of (Part, Size is Numeric)           // declares two params
fn sizeOf(c: is Sized of (i64, u64)) -> u64    // applies both
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

- `unreachable` — keyword expression, with an optional message: `unreachable("…")` (`spec/syntax_grammar.ebnf` §13). Reaching it at runtime is a panic. The message is a diagnostic sink (`spec/diagnostics.md` Rule DG3.2).
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

1. The expected type at the literal's position (function parameter, return position, an ascription `e is T` (Rule 5.11), the element type of a list literal (Rule 5.10), comparison operand).
2. Operand-unification: in `a + 1`, `1` resolves to whatever `a`'s type is.

If still unresolved, **defaults** apply:

- `INT_LITERAL → i64`
- `FLOAT_LITERAL → f64`

An integer literal that meets a float literal takes the float type: `3 + 2.5` is `f64`. A float literal at an integer type is a type error (`T002`).

An integer literal outside the range of the integer type it resolves to is an error, `error[T023]`, reported at the literal; at a type parameter the range is that of `i64`, because the literal reaches the type through `fromI64` (`spec/stdlib.md` §4.9). A leading `-` belongs to the literal for this check, so the most negative value of a signed type is valid. This is a fact about the source text. Arithmetic whose operands are all constants is evaluated at compile time, and a result outside the range of its type, or a constant division by zero, is `error[T025]` at the operation, for the same reason (`spec/effects.md` Rule E3.6.d, item 1). What arithmetic does at run time when a result leaves the range is `spec/effects.md` §3.6: it performs the effect `Overflow`, or `DivideByZero` for a zero divisor.

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

### 5.10 List literals (Rule 5.10)

**Rule 5.10.** A list literal `[e1, …, en]` builds a sequence of its elements, in order, through the constructor requirements `cons` and `empty` of the prelude capability `Sequencing`. It means `S.cons(e1, S.cons(e2, … S.cons(en, S.empty)))`, where `S` is its type, and every inner call is at `S` too, although the prelude types the tail of `cons` as `is Sequencing of Part` (`compiler/src/main/resources/stdlib/prelude.fixed`; `spec/stdlib.md` does not list `Sequencing` yet). It is an abstract constructor call (§8.5), and its type is decided as for one:

1. **A concrete expected type** `T`: a parameter, field or result typed `List of u8`, or an ascription (Rule 5.11). The literal has type `T` and is built through the satisfaction of (`T`, `Sequencing`) that Rule 8.4 resolves (§8.5, case 1). A `T` with no such satisfaction is a type error (`T010`).
2. **A type parameter `R` of the enclosing function.** The literal stays abstract and is built through the capability table in `R`'s witness (§8.5, case 2). The bounds of `R` must include `Sequencing` (`T010` otherwise).
3. **Otherwise**: an anonymous bound in a result or a field, a bound on a parameter of the function the literal is passed to (`total([1, 2, 3])` with `xs: is Folding of u64`; `binarySearch(sorted, 7)` with `sorted: C is ArrayLike of (A is Ord)`), or no requirement at all. The literal is a **choice point** (Rule 5.1.1, item 2; `spec/compilation_model.md` Rule CM8.1). Its bound set is `Sequencing of E`, where `E` is the element type, together with every capability the context requires of it. Selection binds it at application link by the rules of §6.5, like every other choice point, or at package compile when its values never leave the package (Rule 5.1.1, item 3). With no requirement from context the bound set is `Sequencing of E`: the least a literal needs to be built, since `Sequencing` is the prelude capability that declares both `empty` and `cons` (`Empty` declares only `empty`).

**What the context requires.** The typer gathers it within the enclosing function, as obligations on the literal's type: the expected type at the literal, and the bounds of every parameter its value reaches through `let` bindings and calls. `let sorted = [1, 3, 5]` followed by `binarySearch(sorted, 7)` requires `ArrayLike of i64`. A member called on the value adds no bound: it must be a member of the bound set (Rule 5.1.3). A program that needs a member of another capability passes the value where that capability is required, or ascribes a type. An ascription on a value bound to the literal fixes it as an ascription on the literal does: in `let xs = [1, 2]` followed by `f(xs is List of u8)`, the literal is a `List of u8` (case 1). With no requirement, the value has only the members of `Sequencing`: `let xs = [1, 2, 3]` followed by `xs.fold(0, (a, x) -> a + x)` alone is a type error, since `fold` belongs to `Folding`, while `total(xs)` with `xs: is Folding of i64` adds `Folding` to the bound set. Where the typer unifies the types of several literals (the branches of an `if`, the elements of a list of lists), they are one choice point, with the union of their bound sets. A literal that a function with an anonymous result returns is that function's choice point (Rule 5.1.2, case 3 of §8.5): its bound set is the signature's together with `Sequencing of E`, and uses inside the function add nothing, since the result is opaque.

**Elements.** Each element is typed against the element type `E`, and is evaluated left to right, as arguments are (`spec/quantities.md` Rule Q9.2). Integer and float literals among the elements take `E` (Rule 5.7): in `[1, 2, 3] is List of u8` each element is a `u8`, and in `[1, 256] is List of u8` the `256` is `T023`. If nothing fixes `E`, the defaults of Rule 5.7 apply, so `[1, 2, 3]` alone is a choice point bounded by `Sequencing of i64`. An empty literal `[]` whose element type nothing fixes takes `E = ()` *(author's choice)*: it holds no element and nothing inspects one, so the choice changes no result.

**Built today.** Not built. The typer gives every list literal the type `List of E`, whatever the context, so a literal where the context requires a capability `List` lacks (`Sized`, `RandomAccess`) is a type error. Examples 02 and 11 were edited for that reason: they take their collection from a function whose result type the caller picks (`range`, `sample`). They return to literals once the choice point is built (`docs/plans/implementation-plan.md`, Phase 4.2, item 19).

Reason: a list literal names no type, so the representation of its value has no user, which is what a choice point is (Rule 5.1.1). Fixing it to `List` would make every literal a linked list whatever the program does with it.

### 5.11 Type ascription (Rule 5.11)

**Rule 5.11.** `e is T` (grammar `AscribedExpr`, `spec/syntax_grammar.ebnf` §13) fixes the type of the expression `e` to `T`.

1. **Typing.** `e` is checked against `T` as its expected type (§10.2), and `e is T` has the type `T`. `T` must fit the context as the type of any expression must (§10.4): equal to a concrete expected type, a satisfier of an expected anonymous bound (`T010` otherwise), or, where the expected type is a type parameter `R`, `R` itself and nothing else (Rule 5.1.2: `[1] is List of u8` where `R is Sequencing of u8` is expected is `T002`).
2. **Not a conversion.** An ascription generates no code and changes no value. It decides only what inference would otherwise leave open: the defaults of Rule 5.7, a choice point of the enclosing function that selection would decide (§6.5), and the `of` arguments of a constructor call, which come from the type it is constructed at (Rule 7.3.c). An `e` whose type is already another type is a mismatch (`T002`): `xs is Vec of u8`, where `xs` is a `List of u8`, is an error, not a copy. So is an opaque value of another function's choice point: `parse(s) is Maybe of u64`, where `parse` returns `-> is Optional of u64`, is `T002`, because only the link step chooses that representation (Rule 5.1.2).
3. **`T` is a type, not a bound.** `T` is a data type or a primitive type with its `of` arguments, a tuple or function type, `!`, or a type parameter in scope. An anonymous bound (`is C`), a named bound (`A is C`), `cap …`, or the name of a capability or of a type alias of capabilities is `error[T024]: an ascription names a type, not a bound` *(author's choice: a requirement on a value is written in a signature, where it already has a meaning)*.
4. **One rule for every expression.** The ascription is not a form for lists:
   - a list literal: `[1, 2, 3] is List of u8` is case 1 of Rule 5.10, and no choice point is made;
   - an integer or float literal: `255 is u8`, `1.5 is f32`; the type replaces the default of Rule 5.7, and the range check of `T023` applies at it; `-128 is i8` is valid, because the ascription applies to the negated literal (grammar §13);
   - a string, character or boolean literal: each has one type (`String`, `char`, `bool`), so an ascription can only restate it, and another type is `T002`;
   - an abstract constructor call: `Optional.some(1) is Maybe of u64` resolves by §8.5, case 1, instead of becoming a choice point;
   - any other expression: the ascription checks its type, and fixes the literals inside it that take their type from it (`(n + 1) is u8` needs `n` to be a `u8`).
5. **Ascriptions and pins.** An ascription fixes a type in the source and removes the choice point. A pin in the lock file (Rule 6.5.5, rule 1) fixes the choice of a choice point at link and leaves the source generic. A program that depends on a representation uses one or the other.

Not built: the grammar has had no annotation on an expression before v0.4.14 (`let x: T = …` is not admitted, P6 amendment M18), and the parser and typer do not know the form yet (`docs/plans/implementation-plan.md`, Phase 4.2, item 19).

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
    fn fold<R>(onSome: Part -> R, onNone: () -> R) -> R

    // Default implementations — types satisfying Optional may override.
    fn isDefined -> bool = self.fold((_) -> true, () -> false)
    fn orElse(other: Self) -> Self = self.fold((_) -> self, () -> other)
```

**Rule 6.1 (Default method bodies / extension methods).** A cap method's optional body acts as both a *default implementation* — used when the satisfying type does not provide its own — and an *extension method*: every type satisfying the cap automatically gets the method as a callable, even without writing one in `satisfies`. Bodies may use `self` (the receiver value, lowercase — see Rule 5.4.c), `Self` (the receiver's type), `Part`, and other in-scope cap members (including the cap's `Self.fn` constructor *requirements* and abstract method *requirements*, which are guaranteed to be provided by the satisfying type).

#### 6.1.1 Default body typing (Rule 6.1.1) — resolves X5

A cap method's default body is type-checked in **two phases**, layered to balance early error detection against per-satisfaction precision.

**Phase 1 — Abstract-Self typing (declaration time).** The body is type-checked once when the cap is declared, treating `Self` as an opaque type with the cap's declared methods available, `Part` as an opaque element type, and abstract method requirements visible by their declared signatures. The body must type-check using only what the cap promises. This catches:

- Method-call mismatches against the cap's declared interface
- Type errors against the abstract `Self` (e.g. calling a method the cap does not declare)
- Argument-arity mismatches against `Self.fn` constructor requirements

A body that fails Phase 1 is a compile error against the cap declaration, not against any particular satisfying type.

**Phase 2 — The satisfaction meets the requirements (cap-closure phase).** When a `Type satisfies Cap` declaration is processed, the satisfying type's members are checked against the cap's **requirements**, not against the cap's default bodies:

- Coverage: every member of the cap that has no default body must be defined in the `satisfies` block, mapped to a variant (Rule 8.1.b), auto-derived (§7.4) or built in. A satisfaction that leaves one out is rejected (P6 D17).
- Types: the satisfying type's signatures must be assignable to the cap's declared signatures (cap-closure rejects the satisfaction otherwise).
- Effects: a satisfying method's effect row must be contained in the row its requirement declares. A default body calls the requirement through the capability table (`spec/compilation_model.md` Rule CM4.2), so an effect the requirement does not declare would be performed behind the default body's back.
- Contracts: a satisfying method must meet the cost props and the `fip` modifier of its requirement (`spec/cost.md` Rule CT6.3).

A satisfaction that fails Phase 2 is a compile error against the *satisfaction*, with the satisfying type named in the diagnostic.

The default body itself is not checked again. It is compiled once, as a baseline version that takes the witness of `Self`, with the demands of the table convention (`spec/quantities.md` Rule Q4.1.e, Rule Q5.12). A clone of it for a known satisfying type is analysed again by the specialiser, which may then borrow or reuse where the baseline could not; that is an optimisation and cannot fail.

**Why two phases.** Phase 1 catches obvious bugs early and gives cap authors actionable errors at the cap-declaration site, without requiring N satisfying types to be in scope. Phase 2 states what a default body may assume about every satisfying type, so a package can satisfy a capability of another package without seeing that capability's default bodies. Earlier drafts re-checked each default body per satisfying type for quantities and effects; that needed the body wherever a satisfaction was declared.

```
cap Optional:
    Self.fn some(v: Part) -> Self
    Self.fn none -> Self
    fn fold<R>(onSome: Part -> R, onNone: () -> R) -> R
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
    prop inRange: min <= Self && Self <= max

// is equivalent to

fn between(min: N is Ord, max: N) -> cap of N =
    prop inRange: min <= Self && Self <= max
```

Both forms produce a *cap-generating function* (see `spec/properties.md` for the prop semantics; this document treats `fn -> cap` as a regular function whose return type happens to be a refinement capability).

#### 6.3.0 Use-site bridge: `cap of T` ↔ `RefinementCall` (Rule 6.3.0) — resolves C3

A function `fn f(args) -> cap of T` (or `fn f(args) -> cap extends C`) is a **cap-generating function**. Its declaration site uses the `cap`-prefixed return-type form (per grammar `FnReturnType` and `CapType`). Its **use sites** in `is`-bound position do **not** repeat the `cap` keyword — instead, they call the generator by name as a `RefinementCall`:

```
fn between(min: N is Ord, max: N) -> cap of N =
    prop inRange: min <= Self && Self <= max

// Use site — call the generator by name, no `cap` keyword:
fn validateAge(age: u8 is between(0, 150)) -> u8 = age
//                       ^^^^^^^^^^^^^^^^ RefinementCall (per grammar §10)
```

Per the grammar (`syntax_grammar.ebnf` §10 — `CapBound = CapRef | RefinementCall | PrimitiveType`), a `RefinementCall` is exactly `LOWER_IDENT "(" ArgList? ")"` — the lowercase generator name applied to value-args. The result is a refinement cap that the typer treats as if it were a Marker-classified cap with the generator's prop body as obligation.

**Higher-order positions.** When a cap-generator value is passed as an argument or stored as a binding, its *type* requires the `cap of T` form (per the grammar's `CapType` admitted as a `TypeAtom`):

```
fn validateWith(
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
fn altBetween(lo: N is Ord, hi: N) -> cap of N = prop r: lo <= Self && Self <= hi
// Even though the bodies match, `between(0, 10)` and `altBetween(0, 10)`
// are *different* caps. A value of one is not assignable to a binding of the other.

type altBetween = between
// `altBetween` is now a type-alias for `between`, so `altBetween(0, 10)` *is*
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

// `is pairConstraint(0, 100)` where `pairConstraint(lo, hi) -> cap of (X, Y)`
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
| Closed-form arithmetic over admissible operands: `+`, `-`, `*`, `/`, `%` (compile-time constant-foldable; a result that does not fit its type, or a division by zero, is `T025`, §5.7) | `between(0, 2 * upper)` |

Not admitted in v0:
- Function calls (`config.maxAge()`)
- Field access on bindings (`config.max`)
- `match`/`if` expressions
- Capability-method calls
- Module-level constant references that aren't covered by the above (a `let` at module scope with a literal initializer **is** admitted; anything else isn't)

Programs that need richer computed bounds must lift the computation to a `let` whose initializer satisfies the v0 rule, or wait for v1 (totality-gated, below).

**v1 rule (target — Phase 3 totality).** Once the function-totality checker lands in Phase 3, the v0 rule relaxes to: any expression whose evaluation is *total* per the function-totality rule may be a cap-generator value-arg. Because Fixed already requires every `fn` to be total (recursion-via-fold-only, no self-recursion), this relaxation makes every fn call a safe cap-generator value-arg.

The v0→v1 promotion is purely a relaxation; programs valid under v0 are valid under v1.

```
// v0 — accepted:
fn validateAge(age: u8 is between(0, 150)) -> u8 = age
let upper = 150
fn validateAge2(age: u8 is between(0, upper)) -> u8 = age   // upper is a literal-initialized binding

// v0 — rejected (computed bound):
let upper = config.maxAge()
fn validateAge3(age: u8 is between(0, upper)) -> u8 = age   // error: not a literal

// v1 (when totality lands):
// All three forms are accepted; the totality checker certifies `config.maxAge()`.
```

Tracking gate: see implementation-plan.md Phase 3 totality-checker delivery.

### 6.4 Capability classification

Every cap declaration is assigned exactly one **classification** based on its member shape. The classification drives representation selection (§6.5) and the legal operations on values bounded by the cap.

| Classification | Detection rule | Examples |
|---|---|---|
| **Sum** | At least one `Self.fn ctor(...) -> Self` constructor requirement plus one `fn fold<R>(...)` member | `Optional`, `Result`, `Expr` |
| **Product** | Exactly one `Self.fn ctor(...) -> Self` constructor requirement, plus accessor methods (`fn field -> T`) and no `fold` | `Config` (when expressed as a cap), `Message` |
| **Recursive** | Any `Self.fn` constructor requirement whose parameter list contains `Self` or a bound on the declaring cap itself | `Sequencing` (via `cons(h: Part, t: is Sequencing of Part) -> Self`) |
| **Capability-only** | No `Self.fn` constructor requirements; only instance methods | `Folding`, `Functor`, `Filtering`, `Sized`, `RandomAccess` |
| **Marker** | No methods at all (possibly with `prop` declarations) | `Locked`, `Open`, `Meters`, `Seconds`; refinement caps `between(lo, hi)` |

A cap may belong to multiple of these categories transitively via `extends` (e.g., `Sequencing` is recursive *and* capability-only via the inherited members of `Folding`). The classification of the **declaring** cap is the union: the compiler treats it as "having sum/product/recursive shape" exactly when its own declared members trigger the rule.

**Note.** Refinement caps generated by `fn name(...) -> cap of T` are always **Marker** caps. They add `prop` obligations but no methods, and impose no representation constraints. A value `N is between(0, 100)` is still represented as `N`.

### 6.5 Representation selection

The representation-selection pass decides each **choice point** (Rule 5.1.1, item 2; `spec/compilation_model.md` Rule CM8.1): which concrete representation a bound with no user gets.

Selection is **staged**. A choice point whose values never leave the package is decided when the package is compiled (Rule 5.1.1, item 3). Every other choice point is decided when the application is linked, from the summaries of every package (`spec/compilation_model.md` Rules CM8.3, CM8.4). The linker then binds the choice (Rule CM8.2). A package is compiled once, to code that works for every choice, and nothing is recompiled when a choice changes. The procedure below is the same at both stages; where they differ, the text says so.

**Where a representation is chosen (Rule 6.5.0).** Each choice point is decided once, for every value it covers: all results of one function with an anonymous result type, all values of one data type for a field-local variable, all values built by one abstract constructor call with an anonymous type. Code that knows the choice uses the layout directly. All other code (a baseline version, `spec/compilation_model.md` Rule CM3.1; another package) uses the value through its witness, or through per-type accessors when the layout of a data type depends on the choice (Rule CM3.7). Selection never changes because a function was cloned, and cloning never changes a layout (Rule CM3.4). A type parameter is never selected: its user supplies it (Rule 5.1.1, item 1).

#### 6.5.1 Inputs

For each choice point, the pass collects:

- **Bound set.** The bounds declared at the choice point, closed under `extends` (§6.2) and alias distribution (§5.8). Its values are opaque to their users (Rule 5.1.2), so no use can add a bound. The one exception is a list literal that is a choice point of its own (Rule 5.10, case 3): it declares nothing, and its bound set is `Sequencing of E` together with what the uses in its enclosing function require.
- **Usage vector.** Which members of the bound set are called on its values, with what weight. At link, the usage vectors in the summaries are added along the edges "passed on to `g`, parameter `i`" (`spec/compilation_model.md` Rule CM8.3), so usage is whole-program, and no body is read. The weight is the first available of: a production profile, the usage measured in test runs (`spec/properties.md` Rule P7.9), and the static weights of the summary.
- **Cost contracts.** The cost props of the capabilities in the bound set, and the claims each candidate makes for its members (`spec/cost.md` §4.4).
- **Measured costs.** Where they exist, the cost of each member per (satisfaction, size class, target), from the profile file of the target or the reference cost table a package ships in its interface (`spec/compilation_model.md` Rule CM8.6). These are measurements, so they enter only decisions among correct alternatives (`spec/properties.md` Rule P7.8).
- **Quantity context and lifetime class**, as the package compile records them (§7.3.b, `spec/quantities.md`; `spec/memory_management.md` §5, §20). The class, not the quantity, decides placement: only `Heap` and `Region` values need representations compatible with reference counting. The pass may demote any class to `Inline`, never promote it; an `Inline` value that is not an immediate is copied out to a cell where code that does not know its type meets it (`spec/memory_management.md` Rule MM3.1).

#### 6.5.2 Candidate pool

The candidates are **satisfactions**, from two sources:

- **Declared satisfactions.** A `T satisfies C` for some C in the bound set, or inherited by one. At package compile, the pool is the satisfactions the package can see. At link, it is the satisfactions of the link unit that count at the choice point (Rule 8.6).
- **Built-in candidates**: the §6.6 pool. Each carries a precondition expressing which bound sets it can satisfy.

**Two satisfactions of the same pair are never ranked.** When the link unit holds several satisfactions of one (type, capability), Rule 8.4 picks the one that serves the choice point: at the site that created the choice point, by levels 1–4 there; if that leaves several, by level 5 at link (`spec/compilation_model.md` Rule CM8.4, `spec/cost.md` Rule CT8.1). Only that satisfaction enters the pool for that type. Two satisfactions of one pair differ in results, not in speed, so the score must not choose between them. The pool therefore holds at most one candidate per data type and capability.

Per Rule 6.6, built-in and declared candidates have **equal priority**; the score of Rule 6.5.4 and the disambiguation of Rule 6.5.5 choose.

#### 6.5.3 Viability filter

A candidate is **viable** for the bound set iff every C ∈ bound set is satisfied by:

- (a) a satisfaction of the candidate that Rule 8.4 resolves at the choice point (data types), or
- (b) the candidate's inherent structural properties (built-ins), or
- (c) an auto-derivation rule of §7.4 (`Folding`, `Functor`, accessors, …, for data types), or
- (d) a default body declared on the cap method itself (Rule 6.1 — extension methods).

Refinement caps (Marker class, §6.4) are viable iff their `prop` obligations verify under the candidate's representation. Only `proved` and `assumed` props count. A `tested` prop does not make a candidate viable (`spec/properties.md` Rule P7.8).

**Cost contracts.** A candidate is not viable if one of its members fails a cost prop of a capability in the bound set (`spec/cost.md`). This is the decisive use of contracts: a capability that promises a constant-time `get` is never satisfied by a candidate whose `get` is linear.

**Refined fields.** A candidate whose variant refines a constructor-requirement parameter (Rule 8.1.a) is viable only if every abstract constructor call of the choice point passes arguments of the refined field types. The package records those argument types in the summary of the choice point, so that the link step can check them without bodies.

If zero candidates are viable, the choice point has no representation: `error[E060]: no representation satisfies <bound set>`, listing each cap and the candidates that came closest. At package compile this is a compile error. At link it is a link error (`spec/compilation_model.md` Rule CM8.4). **This is the only error selection can report.**

#### 6.5.4 Scoring (Rule 6.5.4)

Each viable candidate gets a **score** in `[0, 10]`; higher is better.

**Measured score.** The expected cost of candidate `r` at choice point `p` is

```
expected(p, r) = Σ over members m of the bound set:  usage(p, m) × cost(m, r)
```

with `usage` from §6.5.1 and `cost` the measured cost of `m` under `r`, in the size class and target of the build (steps, cells built net of cells released, cycles and cache misses, aggregated as `spec/cost.md` §8 states). The score is `10 × min over r' of expected(p, r') / expected(p, r)`: the cheapest candidate scores 10.

**Fallback score.** If some viable candidate has no measured cost for a member with nonzero usage, every candidate at that choice point gets the fallback score instead. Measured and fallback figures are never mixed within one choice point. The fallback is a weighted linear combination over these dimensions:

- **Allocation cost**: the lower of two scores (`spec/cost.md` §8). One is the placement score of the value's lifetime class (`spec/memory_management.md` §3.1: `Inline` > `Static` > `Frame` / stack `Dest` > `Region` > `Heap`, with packed candidates charged for expected copy-out of escaping subtrees, §16.2). The other is derived from the candidate's bounds on `constructs − releases` for the members of the bound set, each weighted by its usage.
- **Access fit**: derived from the candidate's bounds on `steps` for the members of the bound set, each weighted by its usage (the bound-class table, `spec/cost.md` §8). It measures how far the candidate beats the contracts. A candidate that breaks one is already gone (§6.5.3).
- **Memory footprint**: total bytes including headers, padding, and discriminator tags, per typical instance.
- **Locality**: a property of the candidate's layout. Contiguous layouts score higher. Pointer-chased layouts lose cache lines and amortise across allocator slabs.

**Provisional weights and tie threshold (resolves C4).** The scoring *mechanism* is normative: a measured score where data exists, a fallback weighted sum otherwise, a tie threshold ε, and a Lipschitz bound `K < ε` on the fallback score. The *numeric values* below are **provisional**. They were chosen before any backend or benchmark existed and become normative only once calibrated against measured workloads. Until then every implementation uses them, so that two implementations choose alike:

| Dimension | Weight | Range of raw score |
|---|---|---|
| Allocation cost | 0.30 | 0–10 (derived: `spec/cost.md` §8; lower allocation cost ⇒ higher raw score) |
| Access fit | 0.30 | 0–10 (derived: `spec/cost.md` §8) |
| Memory footprint | 0.15 | 0–10 (smaller is higher) |
| Locality | 0.15 | 0–10 (cache-friendly ⇒ higher) |

Each candidate's raw scores per dimension are normalised to `[0, 10]` and combined as a weighted sum. The PGO dimension of earlier drafts, weight 0.10, is gone: measured data now replaces the fallback score instead of adding to it. The other weights are unchanged, so the maximum fallback total is `9.0`.

**The derived dimensions keep `K < ε`.** `spec/cost.md` §8 computes allocation cost and access fit so that one added bound, with everything it inherits, moves each by at most 0.6 raw points, 0.36 in the weighted total. Memory footprint and locality depend on the candidate only.

The **tie threshold ε** is `ε = 0.5`. It is the band within which Rule 6.5.5 keeps the choice recorded in the lock file.

**Stability.** A choice is recorded in the lock file (`spec/compilation_model.md` Rule CM8.5) and kept while its score stays within ε of the best (Rule 6.5.5, rule 2). A change to the program never halts the build, and it does not flip a choice that stays within ε of the best. A choice that does change shows as a diff of the lock file.

For the fallback score this is also bounded. The **Lipschitz bound** is `K ≤ 0.4` per single-bound addition: extending the bound set by one capability bound, with everything it inherits, shifts a candidate's fallback score by at most 0.4 (`spec/cost.md` §8 bounds each derived dimension so). Suppose the recorded choice won with a margin of at least ε. After one added bound it trails the best by at most `2K − ε < ε`, so rule 2 keeps it. A measured score has no such bound: new measurements can move it arbitrarily, and only the hysteresis of rule 2 covers them.

A revision of the numeric values must keep `K < ε`. A program that depends on a specific representation pins it in the lock file (Rule 6.5.5, rule 1), or constructs the data type directly (§8.5).

#### 6.5.5 Disambiguation (Rule 6.5.5)

Selection never halts on a tie. Among the viable candidates, the first rule that decides is applied:

1. a **pin** written by the user in the lock file, if the pinned candidate is viable;
2. the **choice recorded** in the lock file, while its score is within ε of the best score;
3. the **highest score**;
4. the satisfaction declared in the **capability's own package**, where the capability is the first capability of the bound set in canonical bound order (§6.5.8);
5. **declaration order**, for candidates declared in one package; otherwise the qualified name.

"Canonical satisfaction" means rule 4 and nothing more. Built-in candidates (§6.6) count as declared in the standard library package, in the order of the §6.6 table, before the source declarations of that package.

These rules rank candidates of *different* data types. Two satisfactions of the same (type, capability) never reach them (§6.5.2, Rule 8.4).

Rules 1 and 2 apply at link, where the lock file is. A choice point decided at package compile has no lock file, and rules 3–5 decide it. Such a choice is invisible to the program (Rule 5.1.1, item 3).

The only error is zero viable candidates (§6.5.3).

Reason: a tie between correct candidates is a question of speed, not of meaning, so stopping the build for it gives the user work without protecting anything. The lock file makes the outcome reproducible and visible instead.

#### 6.5.6 Commit

An **early choice** (package compile) is compiled into the package's code: code that knows it uses the layout directly.

A **late choice** (application link) is committed by the link step. The object code of each package refers to the witness operations, table entries and accessors of the choice point as undefined symbols. The linker binds them to the chosen satisfaction (`spec/compilation_model.md` Rules CM8.2, CM3.7), with no hidden parameter, no indirect call and no recompilation. The link step records the choice in the lock file (Rule CM8.5). In a release build the specialiser then builds clones for the chosen types (Rule CM8.7).

Either way, the choice is recorded with its witness: the value witness and the capability tables that code without knowledge of the representation uses (`spec/compilation_model.md` §4). Later passes see the concrete representation where the choice is known to them, and the witness elsewhere.

The arguments of every abstract constructor call bound to the choice point fit the chosen variant's fields. Viability guarantees this (§6.5.3, refined fields), so a commit never reports a type error.

#### 6.5.7 Phase placement

At **package compile**, selection of the choice points that do not escape runs after:

| Runs after | Provides input to selection |
|---|---|
| Namer (§10.1), typer | Symbol table, choice points and their bound sets |
| Capability closure (§6.2), alias distribution (§5.8) | Closed bound sets |
| Static prover (`spec/properties.md`) | Refinement-cap viability |
| QTT quantity inference (§7.3.b) | Quantity context |
| Lowering to polymorphic Core; demand and memory inference | Lifetime classes, escape facts |
| Cost checker (`spec/cost.md`) | Cost contracts met by each satisfaction |

It writes the summaries of the remaining choice points into the interface tier.

At **application link**, selection of the remaining choice points runs on summaries, before the generation of accessors (`spec/compilation_model.md` Rule CM3.7) and of clones (Rule CM8.7).

| Consumes selection output | How |
|---|---|
| Specialisation (`spec/compilation_model.md` §5, Rule CM8.7) | Clones are keyed by the witnesses of the chosen representations; selection is not repeated for a clone |
| Match-to-fold desugaring, Perceus insertion, code generation | Use the representation where the choice is early; use the witness and the symbols bound at link where it is late |

#### 6.5.8 Caching, the lock file and SCC unification

Bound sets are **canonicalised**: sorted, deduplicated, alias-expanded and `extends`-flattened. Selection depends on the choice point's canonical bound set, usage vector, quantity context, lifetime class and pool, and on the measured costs. Two choice points that agree on all of these get the same result. A choice point is never re-scored when none of them changed. The canonical order of a bound set is also the order of capability tables among a function's hidden parameters (`spec/compilation_model.md` Rule CM4.3).

The lock file records, per choice point of the application, the chosen satisfaction and its score. It also holds any pin the user wrote (`spec/compilation_model.md` Rule CM8.5). Continuous integration builds from the lock file and does not re-measure.

Mutually recursive `data` types (§7.5) form a strongly-connected component on the data-dependency graph. Their field-local choice points are selected **once per SCC**, not once per member. All members of a recursive group adopt one representation strategy: a co-recursive group with one shared header layout when its values are `Heap`. A future relaxation may allow per-member representations within an SCC when the inter-member references are in tail position only.

#### 6.5.9 Open questions

- ~~**Cross-function rep flow.**~~ Resolved in v0.4.3. A generic function has no representation of its own (`spec/compilation_model.md` §3, §5).
- ~~**Ties created by a dependency.**~~ Resolved in v0.5.0. A tie no longer halts (Rule 6.5.5). A satisfaction that a dependency adds changes a choice only if it wins by more than ε, and the change shows in the lock file.
- ~~**Heuristic stability.**~~ Resolved: the lock file's hysteresis (Rule 6.5.5, rule 2), and `K < ε` for the fallback score (Rule 6.5.4).
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

**Rule 6.6 (priority).** During representation selection, built-in candidates have **equal priority** to user-`satisfies`-mapped data types. If a user-defined data type can satisfy the same bound set, it competes alongside the built-in candidates. The score of Rule 6.5.4 and the disambiguation of Rule 6.5.5 choose among them. For rule 4 of Rule 6.5.5, a built-in candidate counts as declared in the standard library package.

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

A `data` declaration is recursive iff at least one variant has a field whose type mentions the data type itself (transitively). Recursive data gets a lifetime class like any other value; recursion alone does not force heap allocation (see `spec/memory_management.md` §5).

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
    prop inRange: forall (b: Self) -> b.lo <= b.value && b.value <= b.hi

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

**Rule 7.3.b (QTT for of value-params).** `of` value-params are quantity 0 by default — type-level, erased at runtime: no value stores them. Where the value of the parameter is statically known, the compiler materialises it as a compile-time constant at access sites (so `b.lo` in `b.lo <= b.value` becomes the constant for `b`'s type). A baseline version that is compiled for every value of the parameter and reads it receives it as a hidden argument instead (`spec/compilation_model.md` Rule CM4.4). See `spec/quantities.md` for the full rule. Note that the "non-phantom" qualifier present in earlier drafts was redundant — `phantom` only ever applied to type parameters; all `of` value-params are erased uniformly.

**Rule 7.3.c (Constructor calls).** A variant constructor call passes only the variant's **declared fields** positionally. The values of the data type's `of` value-params come from the type at which the variant is being constructed:

```
let x = Bounded.Bounded(5) is Bounded of (i64, 0, 10)
//                 ^value=5, lo=0, hi=10 from the ascribed type (Rule 5.11)
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
//       onNull:   () -> R,
//       onBool:   (b: bool) -> R,
//       onNumber: (n: f64) -> R,
//       onString: (s: String) -> R,
//       onArray:  (elements: List of R) -> R,
//       onObject: (fields:   List of (Tuple of (String, R))) -> R,
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
//       onLit: f64 -> R,
//       onAdd: (R, R) -> R,
//       onMul: (R, R) -> R,
//       onNeg: R -> R,
//   ) -> R
```

Auto-derived `fold` is the canonical eliminator and the destructuring path for any `is`-bounded view of the data. `match` works directly on `data` values too (per `spec/pattern_matching.md`); `fold` is what the typer emits for capability-bounded views.

**Rule 7.4.b (Unfolding).** A recursive `data` declaration (any variant has at least one field of type `Self`, transitively) gets an auto-derived `unfold<S>` method (anamorphism). Given a seed `s: S` and a step function returning one variant of `Self` with seeds in recursive positions, `unfold` builds the structure:

```
fn unfold<S>(seed: S, step: S -> Self) -> Self
```

The compiler iterates `step` until no recursive seeds remain. The combination of `fold` (Rule 7.4.a) and `unfold` enables hylomorphism (`unfold` then `fold`).

**Rule 7.4.b.1 (Guaranteed fusion).** Function recursion is not available (functions are total), so every loop in a Fixed program is a `fold`, and a counted loop is an `unfold` followed by a `fold`. The compiler therefore **must** remove the intermediate structure, with no allocation for it, when all of these hold:

1. The result of an `unfold` (or of a conversion the compiler derives from one, such as `toNat`) is consumed by exactly one `fold`.
2. That result is used once (quantity 1, `spec/quantities.md` §5): nothing else observes it. Exclusive ownership is not required, because the structure is never built.
3. Both calls resolve to auto-derived `fold` / `unfold` (Rules 7.4.a, 7.4.b) of a data type that is statically known at the call site.

Outside this class the compiler is permitted, but not required, to fuse.

**Rule 7.4.b.2 (Guaranteed specialisation).** When an argument of an auto-derived `fold` or `unfold` of a statically known data type is a lambda or a statically known function, the compiler **must** compile the call with that argument inlined: no closure allocation and no indirect call per step.

These two guarantees are **mandatory specialisations** (`spec/compilation_model.md` Rule CM5.5). They are part of code generation, not of profile-guided optimisation: they are not charged to the clone budget and hold in every build mode, including `--no-specialise`. In a baseline version whose data type is a type parameter, the data type is not statically known and neither guarantee applies; in a clone of it for a known type both apply again.

**Rule 7.4.b.3 (Native stack of derived members).** The derived `fold` (Rule 7.4.a), `unfold` (Rule 7.4.b) and `map` (Rule 7.4.c) of **linear data** run in bounded native stack, whatever the length of the value. Data is linear when it has a variant without fields and a variant with a field of type `Self`, each variant's field of type `Self` is its last field, and no other field mentions the data type: `List` and `Nat` are linear. The result is the one the recursive definition gives; the method is the implementation's. `unfold` and `map` build the cells in order in a loop. `fold` combines from the last cell (Rule 7.4.a); the form adopted reverses the spine and then folds from the end in a loop. The reversal is in place only where the spine is exclusively owned; a shared spine is reversed into a copy, which is not `fip` (Rule MM18.1), since `fold` only borrows `self` (`spec/compilation_model.md` Rule CM4.6).

The derived members of other recursive data (trees, rose trees, snoc lists; a mutually recursive group derives none, Rule 7.5.a) take one native frame per level of the value. Bounded native stack for them is not specified yet (`spec/memory_management.md` OQ-PR5).

Built: `unfold` and `map` of linear data, and the generated `length` of list-shaped data. Not built: the loop form of `fold`, adopted by the owner (P6, fourth round, decision 3; `docs/plans/implementation-plan.md`, Phase 4.2, item 20); it measured +8.1% instructions and −23% best cycles on `nat_loop` (`docs/decisions/P0-spec-review-2026-11-20.md` §4).

**Rule 7.4.c (Functor over the rightmost type parameter).** A *named* `data T of (X1, ..., Xn)` declaration is auto-derived as `Functor of Xn` (the **rightmost type parameter** — right-bias convention) iff:

1. `Xn` is non-phantom and is a type parameter (not a value parameter).
2. `Xn` has no bound. `map` may produce any element type, and `Functor`'s signature has no place for a bound on it.
3. Every occurrence of `Xn` in a variant field is **reachable**: it is `Xn` itself, or it lies inside the last argument of a data type that derives `Functor` by this rule, with no other argument of that type mentioning `Xn`. A recursive occurrence of `T` at its own parameters is reachable.

A type that fails condition 2 or 3 derives no `Functor`, and the diagnostic names the field that blocks the derivation. The types excluded by condition 3 are: `Xn` in an argument that is not the last (`Tuple of (Xn, u64)`), `Xn` as the result of a function (`u64 -> Xn`), `Xn` as a function parameter, and a type that refers to itself at other arguments (`M of (B, A)` inside `M of (A, B)`). The user may write `T satisfies Functor:` by hand for any of them. Deriving through a function result, by composing `f` after the field, is planned (`docs/plans/implementation-plan.md`, Phase 4.2). (Owner's decision, fourth round of P6.)

This rule applies to **named** data types only. Anonymous caps produced by cap-generator calls (e.g., `pairConstraint(0, 100)`) are never auto-derived per Rule 6.3.2 — refinement caps add prop obligations, not method surfaces.

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
//       onLeaf:   (value: A) -> R,
//       onBranch: (leftOrig: Tree of A, leftFolded: R,
//                    value: A,
//                    rightOrig: Tree of A, rightFolded: R) -> R,
//       onEmpty:  () -> R,
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

Representation: data types in a strongly-connected component of the data dependency graph are co-allocated. The compiler treats them as a single recursive group; when its values are `Heap`, refcount headers share one layout across the group, and the compiler may emit a unified tag space across the group when this enables better packing. Placement follows the lifetime class of each value. See `spec/memory_management.md` §15.

Self-recursion (a single data type referencing itself) is the trivial 1-element SCC and is handled by the same rule.

**Rule 7.4.h (Recursion between functions).** A named function that can reach itself in the static call graph of named functions is **recursive**: a direct call to itself, a cycle through other functions, or a call from a nested function or a lambda inside it that names it. A call through a capability table, or through a closure value that the function receives, is not an edge of that graph. The prelude and the generated members obey the same rule. A recursive function is accepted in two cases and is otherwise an error.

1. **Structural recursion.** Every recursive call of the function to itself passes, in one fixed parameter position, a **strict sub-value** of that parameter: a variable bound by a pattern of a `match` (or of the parameter's own pattern) on the parameter, or on a strict sub-value of it. Such a function is total, and its row is unchanged. This is how a user writes a fold that the derivation does not provide: a traversal of a type that refers to itself at other arguments (`M of (B, A)` inside `M of (A, B)`), or through a position that the derived `fold` passes unfolded (Rule 7.4.a.1). The recursive call may be at other type arguments than the caller's.

2. **`Diverge`.** A function whose row has the effect `Diverge` may recurse without restriction: `fn spin(n: u64) -> ! with Diverge = spin(n + 1)`. `Diverge` says that the function may not return. It has no operations and no handler removes it; it propagates to every caller, `main` included. A function with `Diverge` in its row is not total: its props are not proved by enumeration over it, it is never a fork site, and a cost bound on it is a bound per step, not on the call.

A recursive function that is neither is `error[T027]: <f> calls itself` (or `<f> and <g> call each other`, naming the cycle). The diagnostic must show the ways out, the one that fits the shape of the recursion first:

- `fold` (Rule 7.4.a), when every recursive call is on a field of the matched parameter and only its result is used;
- `para` (Rule 7.4.g), when the body also uses that field itself;
- `unfold`, when the function builds a structure from a seed;
- matching on the parameter, when the call would be structural if the argument were bound by a pattern;
- `with Diverge`, when the recursion is not on a sub-value at all.

```
fn total(xs: L) -> u64 =                    // accepted: structural on xs
    match xs:
        L.C(h, t) => h + total(t)
        L.E => 0
fn total(xs: L) -> u64 =                    // the same with fold; runs in bounded stack (Rule 7.4.b.3)
    xs.fold((h, rest) -> h + rest, () -> 0)

fn size(m: M of (A, B)) -> u64 =            // accepted: structural, at other type arguments
    match m:
        M.Leaf(a, b) => 1
        M.Flip(r) => 1 + size(r)

fn deep(p: T is Show, n: u64) -> String =   // ERROR T027: `n - 1` is not a sub-value of `n`
    if n == 0: p.show() else: deep(Pair.Pair(p, p), n - 1)
fn deep(p: T is Show, n: u64) -> String with Diverge = …   // accepted
fn deep(p: T is Show, n: Nat) -> String =   // accepted: structural on n
    match n:
        Nat.Zero => p.show()
        Nat.Succ(k) => deep(Pair.Pair(p, p), k)
```

*(Author's choices, owner to confirm.)* A cycle through several functions is structural only when every call on the cycle passes a strict sub-value in the position that the cycle returns to; otherwise it needs `Diverge`. A structurally recursive function takes one native frame per level; the derived members of linear data do not (Rule 7.4.b.3), so `fold` remains the form to prefer for long lists. A function of type `-> !` that recurses needs `Diverge` like any other.

Not built: the compiler accepts every recursion today. (Owner's decisions, fourth round of P6: first "enforce", then structural recursion and `Diverge` admitted.)

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
//       fn fold<R>(onBranch: ..., onLeaf: ...) -> R = ...
//   Node satisfies Folding: ...
```

**OQ-T1** (deferred, listed in §11): a *mutual-fold convenience* — generated `(Tree, Node).fold(...)` combinators emitted automatically for SCCs — is a backwards-compatible extension on top of Rule 7.5.a. Revisit if mutually-recursive data types become common in real programs and explicit `satisfies` proves to be friction.

### 7.6 GADT-style equality constraints (deferred to v0.3, resolves OQ5)

Fixed v0.2 `data` declarations are **strictly parametric**: every variant of a `data T of (params)` declaration produces values of the same `T of (params)` instantiation.

If a variant field's type uses a non-parametric `of` argument (e.g., a `data Expr of A:` declaration whose `Add` variant is `Add(left: Expr of i64, right: Expr of i64)`), the field's type is interpreted **literally** — the variant constructs `Expr of A` containing an `Expr of i64` child, regardless of `A`. The typer does **not** narrow `A` to `i64` when matching the `Add` variant.

Per-variant type narrowing in match cases (the GADT feature in Haskell, OCaml, and similar languages) is queued for v0.3. When introduced, it will require explicit syntax distinguishing parametric from existentially-narrowed parameters (proposed direction: `data Expr where { LitInt :: Int -> Expr Int; ... }`-style or equivalent Fixed-flavoured syntax).

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

(`Optional` here is the full sum cap from `examples/06_functor_monad.fixed`, declaring `Self.fn some(value: Part) -> Self` and `Self.fn none -> Self` as constructor requirements. The simplified `Optional` in `examples/02_collections.fixed` has no `Self.fn` requirements and is satisfied by auto-derivation alone — see §7.4.)

**Rule 8.1.a (Variant-to-requirement).** Each `Variant as name` line provides a data variant as the implementation of one of the cap's `Self.fn` constructor requirements. The variant's field types must match (or be a refinement of) the corresponding requirement's parameter types, position by position. Trailing fields that declare a default value may be absent from the requirement: calling the requirement constructs the variant with those defaults.

A field may *refine* its parameter: `cons(h: Part, t: is Sequencing of Part)` may map to a variant whose `tail` is `List of A`, because `List` satisfies `Sequencing`. This stays sound because a call is typed against the variant it resolves to (§8.5): `Sequencing.cons(x, t)` resolving to `List.Cons` checks `t` against `List of A`, at the call site. A requirement call for a type parameter stays abstract and resolves at the caller. A requirement call that is a choice point admits only candidates whose fields its arguments fit (§6.5.3).

```
cap TaskQueue:
    Self.fn empty -> Self

data SimpleQueue(tasks: List of (() -> ()) = List.Nil)

SimpleQueue satisfies TaskQueue:
    SimpleQueue as empty          // TaskQueue.empty constructs SimpleQueue(List.Nil)
```

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
    fn fold<X>(onLeft: L -> X, onRight: R -> X) -> X

// A data type that is always the right side — for instance, a parse result
// from a trusted source where failure is impossible by construction.
data AlwaysRight of (L, R) (value: R)

AlwaysRight satisfies Either of (L, R):
    AlwaysRight as right
    impossible as left
```

Calling `Either.right(v)` at a position whose expected type resolves to `AlwaysRight` selects the `right` mapping and constructs an `AlwaysRight(v)`. Calling `Either.left(err)` at the same position has no implementation in `AlwaysRight` — the compiler emits a **runtime panic** at that call site.

**Rule 8.2 (Impossible — runtime panic semantics).** Mapping `impossible as name` records that this satisfaction provides no implementation of the constructor requirement `name`. The compiler does **not** statically verify that the requirement is unreachable. The capability table of the satisfaction holds, for `name`, an entry that panics with `panic: impossible constructor 'name' invoked on <SatisfyingType>`, followed by the satisfaction's name if it has one (`spec/compilation_model.md` Rule CM4.2). A call to `Cap.name(...)` that reaches this satisfaction, directly or through a witness, therefore panics. Another satisfaction of the same pair may implement `name` (Rule 8.4).

Static guarantees that the panic is unreachable are the user's responsibility, expressible as `prop` declarations on the surrounding code per `spec/properties.md`. For instance:

```
fn unwrapRight(x: AlwaysRight of (L, R)) -> R =
    prop neverCallsLeft: ...     // user-supplied invariant ruling out Either.left
    x.fold((_) -> unreachable, (r) -> r)
```

Why runtime panic rather than static rejection? Static reachability analysis across `satisfies` declarations is non-trivial. The satisfactions are a closed set only once the link unit is known, so a static check would have to run when packages are linked, over calls made through witnesses. Runtime panic gives the same operational guarantee (the impossible constructor never returns a real value) without forcing the typer into expensive reachability reasoning. Users who want a static guarantee can add a `prop` (`spec/properties.md`); a verified prop discharges the panic per Rule P9.2.

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

### 8.4 Satisfactions, names and `using`

**Rule 8.4 (Satisfactions, names and `using`).** A package may declare a satisfaction for any data type and capability, including ones it does not own. A link unit may hold several satisfactions of one pair (data type, capability with its `of` arguments). Coherence per link unit, required by earlier drafts, is withdrawn.

A satisfaction may be **named**:

```
Money satisfies Ord named ByAmount:
    fn compare(other: Self) -> is Ordering = self.amount.compare(other.amount)
```

(`named` is provisional, `spec/syntax_grammar.ebnf` §9.) A satisfaction's name lives with the type names of its package and is imported by `use` like one (`use shop.money.ByAmount`). An unnamed satisfaction can be chosen only by the levels of the resolution order below that name no satisfaction (`use` of the pair, the only one in the link unit).

A satisfaction is **in scope** at a site where a `use` brings it in, or when it is declared in the site's own package. Derived satisfactions (§7.4), built-in ones (§6.6, and those the compiler provides for primitive types) and the prelude's satisfactions (`spec/stdlib.md` §5) are always in scope. A site **sees** a satisfaction that is in scope there; every other document uses "sees" in this sense. A `use` brings a satisfaction in with a `satisfies` clause:

```
use shop.box.Box satisfies Optional           // the type and the satisfaction
use shop.money.Money satisfies Ord named ByAmount
```

Without the `satisfies` clause, a `use` imports only the type, and its satisfactions are not in scope, except those that are always in scope.

**Resolution.** A site that needs the satisfaction of a pair (`T`, `C`) finds it by this order, innermost first. A site needs one when it calls a member of `C` on a value of type `T`, passes a `T` where `C` is a bound (the satisfaction becomes the witness), calls a constructor of `C` that resolves to `T` (§8.5), or applies a data type whose parameter is bound by `C` to `T` (Rule 8.4.a). The first level that names a satisfaction of the pair decides.

1. `using` at the call site: `sort(xs) using ByAmount`;
2. `using` in a type: `Set of Money using ByAmount`;
3. `using` at file or package scope;
4. the one satisfaction of the pair in scope: by `use`, by declaration in the site's package, or because it is always in scope;
5. `using` in the application root, as the default of the link unit.

Otherwise the site is ambiguous, and that is an error (`E061`). (Earlier drafts had a sixth level, "the only satisfaction of the pair in the link unit". It could never decide: a site that sees one satisfaction is decided at level 4, and a site that sees several is not unique in the link unit.)

**Derived and unnamed satisfactions get no preference.** A derived satisfaction (§7.4, `spec/stdlib.md` §5) counts like a declared one, and an unnamed satisfaction like a named one. A pair that has a derived satisfaction and a named one in scope has two, and a site that no `using` decides is ambiguous. Declaring a named satisfaction for a pair that already has one therefore makes every site that sees both ambiguous until a `using` chooses. A `using` names a named satisfaction by its name, and an unnamed or derived one by its pair: `using Money satisfies Ord`.

A `using` names satisfactions, and each name determines its pair. At the call site it covers every resolution made in the call expression, including its arguments, unless an inner `using` decides first. In a type it covers the whole type expression it is written in (`spec/syntax_grammar.ebnf` §10). A top-level `using` declaration in a source file applies to that file. Package scope is a `using` declaration in the file `package.fixed` at the root of the package. The application root is that file in the package that declares `main`; in a test build it is the `package.fixed` of the package under test (`spec/compilation_model.md` §8). In the application package that file is therefore both its package scope (level 3) and the application root (level 5); for a site of the application package level 3 decides first, and level 5 matters only for deferred sites of other packages. A level that names two satisfactions of one pair is itself ambiguous.

**The ambiguity error halts on purpose.** Two satisfactions of one pair differ in results, not in speed. The score, the lock file and declaration order (Rule 6.5.5) therefore never choose between them. The message states the level at which the ambiguity arose, suggests a named `using`, and lists as examples the satisfactions of the pair in scope:

```
error[E061]: two satisfactions of (Money, Ord) are in scope, and nothing chooses between them
  --> report.fixed:12:5   (level 4: in scope by `use`)
   |   shop.money.ByAmount   (use at report.fixed:2)
   |   shop.money.ByDate     (use at report.fixed:3)
  help: write `using ByAmount` at the call, in the type, or at the top of the file
```

**Rule 8.4.a (The satisfaction is part of the type).** For a bound declared on a data type's own parameter (`data Tree of K is Ord`), the elaborated type records the satisfaction that served the bound: `Tree of Money[Ord = ByAmount]`. The record is invisible when the pair has only one satisfaction in the link unit. Two types that differ only in the recorded satisfaction are different types. Mixing them is a type error, `error[E062]`, that names both satisfactions and suggests `using`.

Reason: a value built under one satisfaction must not be used under another. A set ordered by one `Ord` and searched by another gives wrong answers, and its cells carry no record of the order. The type carries it instead.

A bound on a function's type parameter (`fn sort(xs: is Sequencing of (T is Ord))`) is not recorded in a type. The satisfaction is the witness passed for `T`, chosen at each call by the resolution order. A function whose result must keep its argument's satisfaction says so through a data type: `fn insert(s: Set of K, k: K) -> Set of K` returns the same `Set of K[Ord = …]` it received.

A field-local choice point (Rule 4.1.a) is bound once per link unit, so a satisfaction recorded inside it is fixed for the whole program. A `using` in a type can name a satisfaction only for the type's parameters, never for its field-local choice points.

**Rule 8.4.b (Deferred sites).** A site in a library that sees several satisfactions of a pair and has no `using` is not an error in the library. It is bound at link by level 5, and reported as a note. If level 5 does not decide, the link fails with the ambiguity error (`E061`), naming the library site. A site that sees no satisfaction of the pair is a type error, as before. A site in the application package that levels 1–4 do not decide is resolved the same way, at its link.

*(Rule 8.4.b is the author's choice in P6 D12.)*

**Worked examples.**

```
data Money(amount: i64, date: Date)

Money satisfies Ord named ByAmount:
    fn compare(other: Self) -> is Ordering = self.amount.compare(other.amount)
Money satisfies Ord named ByDate:
    fn compare(other: Self) -> is Ordering = self.date.compare(other.date)

fn biggest(xs: is Sequencing of Money) -> is Sequencing of Money =
    sort(xs) using ByAmount                  // level 1

fn merge(a: Set of Money using ByAmount, b: Set of Money using ByAmount) -> Set of Money using ByAmount = ...

fn combine(
    byAmount: Set of Money using ByAmount,   // level 2: Set of Money[Ord = ByAmount]
    byDate: Set of Money using ByDate,       //          Set of Money[Ord = ByDate]
) -> Set of Money using ByAmount =
    merge(byAmount, byDate)
// error[E062]: Set of Money[Ord = ByDate] is not Set of Money[Ord = ByAmount];
//        help: pass a set built `using ByAmount`, or convert it explicitly
```

An application sets a default for a dependency. A library `reports` sorts `Money` with no `using`, and sees both satisfactions. The site is deferred (Rule 8.4.b). The application's `package.fixed` holds

```
use shop.money.ByAmount
using ByAmount                           // level 5: the default of the link unit
```

and the link binds the library's site to `ByAmount`, with a note naming the site.

**Rule 8.4.c (A satisfaction where one already exists).** A package's satisfaction of a (type, capability) pair that already has a built-in satisfaction (`i64 satisfies Eq`) or a satisfaction declared by the prelude (`List satisfies Show`) must carry a name:

```
i64 satisfies Eq named Loose:
    fn eq(other: i64) = self / 10 == other / 10
```

An unnamed one is `error[E066]: <type> already satisfies <capability>; add `named X``. The rule covers every type of the prelude, not only the scalar types.

1. Where no `using` applies, the built-in or prelude satisfaction is the one in force. It is the default of its pair and is not ambiguous with a named one. (For a package's own types Rule 8.4.a is unchanged: a derived and a named satisfaction of one pair are ambiguous without a `using`.)
2. The named satisfaction is selected by `using` in every form of Rule 8.4: at a call site, in a type (`Set of i64[Eq = Loose]`), at file scope, at package scope (`package.fixed`) and at the application root. Scalar types have no narrower rule.
3. Where it is selected, the operators follow it. `==` and `!=` call its `eq`; `<`, `<=`, `>` and `>=` are built from its `compare`; every table entry of the capability is built from it. An operator and a table cannot disagree.
4. A specialised version in which it is selected calls its bodies in place of the machine instruction. Code where it is not selected is unchanged.
5. Selecting it is the stated intent of the program and gives no warning.

Not built: the compiler today accepts an unnamed body on a scalar and uses it in part (`docs/decisions/P0-spec-review-2026-11-20.md` §4). (Owner's decision, fourth round of P6.)

### 8.5 Constructor resolution

A call `Cap.ctor(args...)` resolves by the **expected type** at the call site: the function's return type, a field type, or the parameter type at a call.

1. **A concrete data type** `T` (`fn f() -> Maybe of u64 = Optional.some(42)`). The call resolves to the variant that the satisfaction of (`T`, `Cap`) maps to `ctor`. Rule 8.4 picks the satisfaction. If no satisfaction of the pair is in scope, the call is a type error.
2. **A type parameter** `R` of the enclosing function (`-> R is Optional of u64`). The call stays abstract. It resolves at the caller, through the capability table in `R`'s witness (Rule 5.1.2).
3. **An anonymous bound** (`-> is Optional of u64`, a field `xs: is Optional of u64`), a bound on a parameter of the function the call is passed to, or no requirement at all (Rule 5.1.1, item 2). The call is a choice point (Rule 5.1.1, item 2), and representation selection decides it (§6.5). If the body also constructs a data type directly for the same result, that type is the choice (Rule 5.1.2). Several satisfying types are not an ambiguity here: they are the candidates. The only failure is zero viable candidates (§6.5.3).

The call's arguments are typed against the requirement's parameters. In case 1 they are also typed against the variant's fields, which may refine the parameters (Rule 8.1.a). In case 3 that check is part of viability (§6.5.3). In case 2 the caller's type fixes the variant.

Direct data construction (`Maybe.Just(42)`) bypasses resolution: it always produces a value of the named data type, regardless of which caps are in scope.

#### 8.5.1 Refinement-narrowed expected types (Rule 8.5.1) — resolves X1, L1

When the expected type contains refinement caps (Marker classification, per §6.4) — typically produced by cap-generator calls per §6.3 — resolution proceeds in two stages: **strip, resolve, re-apply**.

**Stage A — Strip refinement caps.** Partition the expected bound set `E` into:
- `E_base` — caps with constructor requirements (sum, product, recursive, capability-only).
- `E_ref` — refinement caps (Marker class, including caps produced by cap-generators per Rule 6.3.0).

**Stage B — Resolve against the residual.** Run §8.5's resolution against `E_base` only. Refinement caps are obligation-only and have no satisfactions to filter against; including them in the expected type of §8.5 leaves no satisfying type.

**Stage C — Re-apply refinements as obligations.** The chosen constructor's result is a value of the satisfying type, which by virtue of having satisfied `E_base` is well-typed. The refinement caps in `E_ref` become **prop obligations** on that result, discharged by the verifier per `spec/properties.md` §7. For a refinement on a bounded integer type the obligation must be `proved` or `assumed` (`spec/effects.md` Rule E3.6.e, `E107`). If `E_base` is empty (the expected type is *only* refinement caps), resolution fails with `error[E065]: refinement caps have no constructors; the expected type must include a base capability with a constructor requirement`.

```
fn tryMake(i: u64) -> is Optional of u64 + between(1, 99) =
    Optional.some(i)
//  ^^^^^^^^^^^^^^^^
//  Stage A: E_base = { Optional of u64 }, E_ref = { between(1, 99) }
//  Stage B: resolve `Optional.some(i)` against `is Optional of u64` →
//           a choice point (§8.5, case 3); say selection picks Maybe,
//           giving Maybe.Just(i).
//  Stage C: Maybe.Just(i) is a value of `Maybe of u64`. The `between(1, 99)`
//           prop becomes an obligation on i (i.e. 1 <= i && i <= 99). The
//           prop verifier discharges it from the call site's context.
```

This rule extends to nested refinements (e.g. `is Optional of (u64 + between(1, 99))` — the inner refinement `between(1, 99)` is stripped from the *type-arg* before resolving against `is Optional of u64`, then re-applied as an obligation on the contained value at use sites).

A refinement-only expected type (e.g. `is between(0, 10)` alone) is rejected: refinement caps must compose with at least one base capability (or a primitive type) so a constructor exists.

### 8.6 Visibility (Rule 8.6)

A declaration is private to its package by default. `pub` makes a `fn`, `data`, `cap`, `effect`, type alias or `satisfies` declaration public: other packages may name it (`spec/compilation_model.md` Rule CM6.2). A `use` declaration and a `using` declaration are never public.

There is no opaque-type feature. `pub data` exposes its variants and their fields. To hide a representation, make the capability public, keep the `data` private, and let its values leave the package as a choice point (`-> is C`, Rule 5.1.2):

```
pub cap Stack:
    Self.fn empty -> Self
    fn push(x: i64) -> Self

data ArrayStack(items: is Sequencing of i64)    // private
ArrayStack satisfies Stack: ...                  // private

pub fn newStack() -> is Stack = Stack.empty     // the caller sees only Stack
```

**Rule 8.6.a (Private names in public signatures).** A public declaration must not name a private one in its signature, in the fields of its variants, or in its bounds (`error[E063]`). A public function that returns a value of a private data type declares the result as `-> is C`. Reason: an importer could not name the type, and an importer is compiled from the interface alone.

A private satisfaction is visible only in its package: it counts as a candidate for the package's own choice points (§6.5.2) and in the resolution of Rule 8.4 at the package's own sites, and nowhere else. It does not count for level 5 of Rule 8.4, nor for the deferred sites of other packages. A type that records it (Rule 8.4.a) may not appear in a `pub` signature, as a private data type may not (`E063`). A `pub` satisfaction of a private data type is allowed. It makes the type a candidate at choice points of the whole link unit, and code outside the package then uses it only through the capability.

A private function needs no floor entry. A private data type needs no accessors (`spec/compilation_model.md` Rule CM3.7).

## 9. Effects (brief)

`effect E: fn op(...) -> T` declares a set of effect operations. A function that performs effects declares them in its `with` clause: `fn read() -> String with FileSystem`. Effect rows compose with `+`. This document does not specify effect inference, evidence-passing compilation, or handler semantics; see `spec/effects.md`.

For typing purposes here, an effect's operations are first-class methods on the effect type (e.g., `Console.printLine(s)` is a method call returning `()` and adding `Console` to the caller's effect row).

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
2. Resolves the satisfaction of (`T`, `C`) by Rule 8.4 (directly, transitively via `extends`, or via auto-derivation §7.4).
3. Records the satisfaction so subsequent method calls on `e` can resolve to `C`'s methods.

An expression whose type is a choice point of its own (a list literal, Rule 5.10, case 3) has no natural type to synthesize. Its type is an inference variable until the enclosing function is typed; each bound it meets is recorded as an obligation on it, and at the end the obligations are its bound set.

### 10.4 Subtyping

Subtyping in Fixed is intentionally narrow:

- **`!` is a subtype of every type.** Expressions of type `!` are valid in any expression position.
- **A type satisfying a cap is a subtype of that cap-bound.** `Maybe of i64` is a subtype of `is Optional of i64` (assuming the satisfaction is in scope).
- **Capability bounds compose by intersection.** `is C1 + C2` is a subtype of `is C1` and of `is C2`.
- **Refinement caps strengthen, not weaken.** `N is Numeric + between(0, 100)` is a subtype of `N is Numeric`.
- **No structural subtyping on `data`.** Two `data` declarations with identical structure are distinct types.

## 11. Open questions

(All OQ1–OQ6 from v0.1 are resolved in this version; their resolutions are folded into the relevant sections above. OQ7 is pre-resolved and deferred to `spec/properties.md`.)

- **OQ-T1 — Mutual-fold convenience** (deferred). Generated combinators over the members of a mutually recursive SCC (§7.5); a backwards-compatible extension of Rule 7.5.a.
- ~~**OQ-T2 — Type variables of variant fields.**~~ Closed in v0.5.0 by Rule 4.1.a: such a variable, and an anonymous bound in a field, is a choice point bound once per link unit (P6 D3).
- **OQ-T4 — Totality of `unfold`.** Nothing says what a derived `unfold` means when its step never yields a variant without a seed: the function then does not terminate, and Rule 7.4.h does not see it. Open for the owner. With `Diverge` (Rule 7.4.h) a candidate rule exists: an `unfold` whose termination is not proved has `Diverge` in its row.
- **OQ-T3 — Dependent types for integer ranges** (open direction, not a decision). The owner's note on the arithmetic effects (P6, fourth round): "we might need to introduce dependent types as a better solution to 'raise integer size'. It might even start simplifying other parts of our system." The widening form `widen` (`spec/effects.md` Rule E3.6.h) fixes one wider type; a type that carried its range would let the result of `a * b` have the range the operands give it. The specs already show three places where values meet types: refinement capabilities with value parameters (§6.3, `cap Between(min, max)`, `fn between(min, max) -> cap of N`), `boundedPositive(lo, hi)` in `examples/11_properties.fixed`, and the static prover removing `Overflow` from an operation whose result range it proves from those refinements (`spec/effects.md` Rule E3.6.d). Nothing is designed.

**Pre-resolved deferrals (lift into the indicated spec doc when drafted):**

- **OQ7 → spec/properties.md (deferred in v0.4.5).** Function-body postconditions are **deferred to a future release** per `spec/properties.md` §3.3 (re-opened in v0.2.1 of that doc). The original v0.4.4 resolution — that `result` is implicitly bound to the return value inside fn-body `prop` declarations — is no longer normative; the worked example below is retained for reference but does **not** type-check in v0.4.5. When postconditions are reintroduced, this OQ will be re-resolved and the `result` binder restored.

  ```
  fn sort(collection: ...) -> C is Sorted =
      prop resultSameSize: result.size == collection.size
      //                     ^^^^^^ implicitly bound to the fn's return value
      collection.foldRight(...)
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
| `spec/quantities.md` | QTT inference, erasure, linearity, FBIP |
| `spec/effects.md` | Effect rows, evidence passing, handler semantics |
| `spec/properties.md` | `prop` semantics, static verification, PBT fallback, `forall`/`implies` |
| `spec/pattern_matching.md` | `match → fold` desugaring, exhaustiveness |
| `spec/memory_management.md` | Lifetime classes, regions, Perceus RC insertion, drop specialization; consumes §6.5 output |
| `spec/compilation_model.md` | Witnesses, choice points, binding at link, lock file, packages; consumes §5.1.1, §6.5, §8.4 |
| `spec/diagnostics.md` | `inspect`, `trace`, interpolation in diagnostic sinks (Rule 5.1.3) |
| `docs/references/scala3-compiler-reference.md` | Reference patterns from dotc — bidirectional typing, Recheck pattern |
| `docs/plans/implementation-plan.md` | Phase plan, open design questions migrated above |
