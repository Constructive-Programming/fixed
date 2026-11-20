# Fixed Compilation Model

**Status:** Draft v0.2
**Specifies:** how Fixed v0.4.4 source becomes code: the baseline version of every function, the uniform slot, witnesses, specialisation (clones, budget, score, mandatory cases), build modes, packages and the package ABI, the interface and body tiers, and coherence per link unit.
**Last revised:** 2026-11-20

**Changes from v0.1.** Amended from the first implementation (`docs/plans/phase-4.1-baseline-specialiser.md`, `docs/baselines/runtime-2026-11-20-phase-4.1.md`): the clones a `fip fn` body requests are mandatory (Rule CM5.5); a clone that nothing reaches at the end is dropped and reported as `unused` (Rules CM5.4, CM5.8); the specialiser may repeat its pass until ownership facts are stable (Rule CM5.4); new OQ-CM10 to OQ-CM12 record what the measurements showed about the weight, the order of the axes and tables per instance.

**Why this document exists.** Until v0.3 of `spec/memory_management.md` the compilation model was implicit: lowering monomorphised every function from `main`, and code that could not be monomorphised (higher-order code, separate compilation) was a special case handled by class witnesses. This document reverses the default. Every function has one **baseline** version that is polymorphic and passes witnesses, in the style of Neut. Specialised versions are an optimisation added where the compiler can identify the types, ownership and classes that matter. The decisions behind it are recorded in `docs/decisions/P1`–`P5`.

## 1. Scope

This document specifies:

- The baseline version and its roots (§3).
- The uniform slot and the rule that cloning never changes a layout (§3.3, §3.4).
- Witnesses: value witnesses, capability tables, hidden parameters, result witnesses (§4).
- Specialisation: what a clone is, the budget, the score, mandatory specialisations, build modes (§5).
- Packages: the public surface, floor entries, exported versions, coherence per link unit, what is and is not an ABI change (§6).
- The interface tier and the body tier (§7).

It does **not** specify:

- The on-disk encoding of the interface and body tiers (OQ-CM2).
- Dynamic linking and symbol versioning beyond the naming rule of §6.5.
- How profile data is collected (implementation plan, Phase 6). §5 only says how it is used.
- Lifetime classes, regions and reference counting — `spec/memory_management.md`. Demands and ownership — `spec/quantities.md`. Cost contracts — `spec/cost.md`.

## 2. Dependencies and prior art

- `spec/type_system.md` — §4.1 (type variables), §5.9 (phantom parameters), §6.5 (representation selection), §7.3 (`of` value parameters), §7.4 (derived members), §8 (`satisfies`).
- `spec/quantities.md` — §4.1 (demands), §9.3 (versions).
- `spec/memory_management.md` — §3 (classes), §7 (destinations, class witnesses), Rule PR5.6 (closure entries), §18 (`fip`), §20.2 (signature facts).
- `spec/effects.md` — §7 (evidence passing).
- `spec/properties.md` — §7 (verification, statuses), Rule P7.4 (cache).
- `spec/cost.md` — cost contracts that travel in the interface tier.

Prior art, cited from general knowledge and **not** re-checked against the sources for this draft (unlike the list in `spec/memory_management.md` §2):

- vekatze, Neut — a type is compiled to a closed function `exp(selector, v)` that discards or copies a value; polymorphic code is compiled once and receives it. The model for §4.1.
- Swift — generic code is compiled unspecialised against witness tables and value witnesses; specialisation is an optimiser pass; a resilient library hides layouts, and `@inlinable` exposes a body to importers. The model for §5 and §6–§7.
- GHC — dictionary passing as the compilation of type classes; unfoldings in interface files allow cross-module specialisation.
- van Oortmerssen, Lobster — one fallback version for large functions instead of one copy per ownership combination. Subsumed by the budget of §5.3.

## 3. The baseline

### 3.1 Baseline version (Rule CM3.1)

Every function, method body, derived member (`spec/type_system.md` §7.4) and closure body that is reachable from a root (§3.2) has exactly one **baseline version**. The baseline version:

1. is compiled once, for all instantiations of its type parameters;
2. receives a witness for each type parameter (§4) and operates on values of parameter type through it;
3. is the **shared** version in the sense of `spec/quantities.md` Rule Q9.3: it is correct for arguments the caller does not exclusively own, and reuses a `Heap` cell only after a runtime `rc == 1` test (`spec/memory_management.md` Rule PR6.3);
4. is correct for every lifetime class of its owned parameters, which it handles through the value witness (§4.1).

The baseline version is always a correct callee. Every other version of the function (§5) is an optimisation and must behave as the baseline does (Rule CM5.2).

A `fip fn` is the one exception to item 3: see Rule CM5.5.

### 3.2 Roots (Rule CM3.2)

The roots of a package are:

- `main`, if the package has one;
- every declaration in the package's public surface (Rule CM6.2);
- in a test build, every `prop` body that verification runs (`spec/properties.md` §4.1).

Code that no root reaches is not compiled. A program that is a single package with `main` and no public surface therefore compiles exactly the code `main` reaches, as before.

### 3.3 The uniform slot (Rule CM3.3)

A **slot** is 64 bits wide on every target.

- A value whose type has at most 64 bits (`bool`, `char`, `()`, every integer type up to 64 bits, `f32`, `f64`) is stored in the slot itself. It is an **immediate**.
- Any other value (`i128`, `u128`, tuples, `data` values, closures, `String`) is a **reference**: the slot holds a pointer to the value, which lives where its lifetime class puts it (`spec/memory_management.md` §3).

The slot is the form of a value in three places:

1. a value of type-parameter type in a baseline version (variables, arguments, results);
2. every payload field of a data cell (`spec/memory_management.md` Rule PR3.1);
3. an argument or result of a call through a capability table (§4.2) or a closure.

**Widening.** A signed integer is sign-extended into the slot and an unsigned one zero-extended; `bool` is 0 or 1; `char` is its code point, zero-extended; `f64` is its bit pattern; `f32` is its bit pattern, zero-extended; `()` is 0.

The width is 64 bits on 32-bit targets too. `i64`, `u64` and `f64` are then immediates everywhere and a data cell has the same layout on every target. The price on a 32-bit target is four unused bytes in every field that holds a reference.

### 3.4 Cloning never changes a layout (Rule CM3.4)

A specialised version of a function (§5) uses the same memory layout for every value as the baseline version does. Layout changes only through **representation selection** (`spec/type_system.md` §6.5), and the selected representation of a value is identified by its witness (§4).

Consequences:

- A value built by a clone can be passed to a baseline version, and the reverse, with no conversion. The decision to clone one function never forces a decision about its callers or callees.
- A clone may keep an immediate of known type in a narrower register and widen it when it stores the value in a slot. That is not a layout change.
- `List of u8` built from the declared `data List` has one slot per element in every version. A packed layout for it is a different representation, chosen by representation selection, with its own witness.

**Reference map.** `drop` on a cell must not depend on which instantiation built it. The header of a cell therefore records which payload slots hold counted references (`spec/memory_management.md` Rule PR3.1). For a slot of parameter type the entry comes from the value witness (`kind`, §4.1) when a baseline version builds the cell, and is a constant in a clone.

## 4. Witnesses

A witness is what a baseline version needs to know about a type it cannot see. There are two kinds. Both are static data wherever the type is known, so passing one is passing a pointer.

### 4.1 Value witness (Rule CM4.1)

The **value witness** of a (type, lifetime class) pair is the class witness of `spec/memory_management.md` Rule MM7.3, a closed function after Neut:

```
witness(op, v, dest)    op ∈ { drop, dup, reuse_test, evacuate, copy, size, kind }
```

`drop`, `dup`, `reuse_test`, `evacuate` and `size` are as in Rule MM7.3. Two operations are added there for this document:

- `copy` returns an exclusively owned copy of the value in `dest`. It is what the shared entry of a `fip fn` uses (Rule CM5.5); `evacuate` cannot serve, because it is the identity when the value already lives where `dest` is.
- `kind` answers whether a slot of this type holds an immediate or a reference (Rule CM3.4).

The value witness of an immediate type is the **immediate witness**: `drop` is a no-op, `dup`, `evacuate` and `copy` return the 64 bits, `reuse_test` is false, `size` is 8 (the slot) and `kind` is immediate. It is the same for every lifetime class.

A value witness is passed:

1. for each non-phantom type parameter of a function, method or closure;
2. for each owned parameter whose class is not fixed by the callee's signature facts (the "class-polymorphic owned parameter" of Rule MM7.3).

It is passed per parameter, never per value: a container's witness covers its elements (Rule MM7.3).

A lifetime class belongs to a value, not to a type, so two arguments of the same parameter type may live in different classes. The witness of case 1 describes the class in which the function's own values of that type live (those it builds, and those it receives from table calls into its destination). An owned argument of that type that lives in another class gets its own witness under case 2.

### 4.2 Capability table (Rule CM4.2)

For each satisfaction `T satisfies C` (written, derived by `spec/type_system.md` §7.4, built in by §6.6, or provided by the compiler for a primitive type) the compiler emits one **capability table**: a static record with one entry per member of `C`. A table is emitted only for a satisfaction that some code uses as a witness; a member the satisfying type does not define is then a compile error at that use. The entries:

- each instance method requirement;
- each `Self.fn` constructor requirement;
- each default-bodied method (`spec/type_system.md` Rule 6.1): the entry is the satisfying type's override if there is one, otherwise the capability's own default body, which is compiled once as a baseline version that takes the witness of `Self`;
- the members `C` inherits through `extends`, flattened in the order of `spec/type_system.md` §6.2.

An `impossible` mapping (`spec/type_system.md` Rule 8.2) gives an entry that panics.

**Two code pointers per entry.** Every entry holds a shared and an exclusive code pointer, by the rule that already governs closures (`spec/memory_management.md` Rule PR5.6). The exclusive pointer is the same as the shared one unless an exclusive version of that member was built (§5).

**Refinement capabilities** (`Marker` class, `spec/type_system.md` §6.4) have no members and no table.

**Parameterised types.** The satisfaction of a parameterised data type (`List satisfies Functor`) has one table, whatever the type arguments. Its entries are baseline versions that are polymorphic in the data type's own parameters, so they need the witnesses of those parameters. The witness of an **instance** (`List of u8`, or `List of A` inside code polymorphic in `A`) is therefore a **witness record**: the value witness, the tables, and the witness records of the type arguments. A witness record is static data when every argument is a known type. Where an argument is itself a type parameter, the code that forms the instance builds the record from the witness it holds, in its own frame. A call through a table passes the receiver's witness record to the entry.

**Results of requirements.** A requirement whose result is typed by a capability bound (`compare -> is Ordering`, `get -> is Optional of Part`) is always callee-chosen through a table (Rule CM4.5): the entry returns the witness record with the value.

### 4.3 Hidden parameters (Rule CM4.3)

A baseline version takes its witnesses as hidden parameters before its declared parameters, in this order:

1. for each non-phantom type parameter of the signature, in order of introduction (`spec/type_system.md` Rule 4.1): its value witness, then one capability table for each capability in its bound set, after `extends`-flattening has removed tables that another table already contains, in the canonical bound order of `spec/type_system.md` §6.5.8. Together these are the parameter's witness record (Rule CM4.2);
2. for each owned parameter whose class is not fixed (Rule CM4.1, case 2), in parameter order: its value witness;
3. the `of` value parameters the body reads (Rule CM4.4);
4. the evidence vector for the function's effect row (`spec/effects.md` §7);
5. the destination, if the result is built in one (`spec/memory_management.md` Rule MM7.1).

A method-level type parameter (`fold<R>`) is a type parameter of that method: its witness is passed at each call.

A baseline version can do with a value of parameter type only what its witness provides: move it, and call the members of the capabilities in the parameter's bound set. An operation that needs a capability the bound set does not contain (`show`, `==`, `clone` on an unbounded parameter) is a type error, not something the backend resolves per instance.

Evidence and witnesses share a calling convention (a hidden parameter that holds code pointers) and nothing else. Evidence is dynamically scoped: each `handle` installs a new entry and may shadow an outer one (`spec/effects.md` Rule E6.5), and an entry refers to a handler frame. A witness is static and, by Rule CM6.6, the same everywhere in a link unit. They are therefore separate parameters.

The layout of an evidence vector for a signature follows the canonical order of its effect row, so that two packages agree on it.

### 4.4 `of` value parameters in baseline code (Rule CM4.4)

An `of` value parameter (`spec/type_system.md` §7.3) is erased from every value's representation, as before. A baseline version that **reads** one (`b.lo`) cannot treat it as a compile-time constant, because it is compiled for all values of the parameter. It receives the value as a hidden argument with demand `read`. A clone for a known value uses the constant. A function that does not read the parameter receives nothing.

### 4.5 Who chooses the representation of a result (Rule CM4.5)

Let `R` be a type variable of a signature that occurs in the result type and in no parameter type, bounded by capabilities (`-> R is C`, or the anonymous `-> is C`).

- **Caller-chosen.** If every value of type `R` that the body returns comes from an abstract constructor-requirement call (`C.ctor(…)` left unresolved by `spec/type_system.md` §8.5), or from a callee whose own result is caller-chosen, then the function is polymorphic in `R`. The caller passes the witness of `R` like any other (Rule CM4.3), and the representation is selected in the caller's package, where the later uses of the value are visible.
- **Callee-chosen.** Otherwise the body fixes the type (a direct data construction, or a constructor call that §8.5 resolved in the callee's scope). The function returns the witness of `R` as a hidden result next to the value. The caller treats the value as opaque and uses it through that witness.

Which of the two holds is a signature fact (`spec/memory_management.md` Rule MM20.2) and part of the package interface (Rule CM7.1). Changing it is an ABI change (Rule CM6.7).

A clone, or an importer that holds the body (§7.3), sees the concrete type of a callee-chosen result and drops the witness.

### 4.6 Calls through a table (Rule CM4.6)

A capability method **requirement** has no body, so it has no inferred demand (`spec/quantities.md` §4.1). A call through a capability table follows a fixed convention instead:

- **Shared pointer.** The receiver and every argument are passed under demand `own`: a counted reference, moved at the caller's last use. The callee may keep, drop or reuse-after-test each one.
- **Exclusive pointer.** As above, and the caller guarantees that it exclusively owns the receiver and every argument, in every part. The caller picks this pointer statically, exactly as for a closure (Rule PR5.6), and only when the guarantee holds for all of them.

The same convention holds for a call of an effect operation through evidence: the performer cannot see the clause, so it passes every argument under `own`.

**Cost of the convention.** A call through a table never borrows: a method that only reads its receiver still receives a counted reference and drops it. A clone for a known type calls the method's own entry and borrows again. Whether a requirement should be able to state `read` is open (OQ-CM8).

A satisfying type's method whose inferred demands are weaker (`read`, or `0`) is entered through an adapter that restores the convention: it calls the method and then drops what the method only read. The adapter is the table entry; the method's own entry stays available to callers that know the type.

Arguments and results are slots (Rule CM3.3). Method-level type parameters pass their witnesses per call (Rule CM4.3).

### 4.7 Witness operations and `fip` (Rule CM4.7)

In a baseline version, a `dup`, `drop`, `reuse_test`, `evacuate` or `copy` on a value of parameter type is a witness call, and the compiler does not know whether it touches a count. For the `fip` contract (`spec/memory_management.md` §18) such a call **is** a reference-count operation. A `fip fn` must therefore be in place for every instantiation: it may move values of parameter type, and may not duplicate or discard them. A call through a capability table is `fip` only if the requirement is declared `fip` (`spec/cost.md` §4.3).

`fip` is checked once, on the function's one body (Rule CM5.5). A clone can only remove operations, so it inherits the result.

## 5. Specialisation

### 5.1 Clones (Rule CM5.1)

A **clone** of a function is a copy of its baseline version compiled for a **key**: any combination of

1. a known witness record for one or more of its type parameters (which fixes the type, its capability tables and, for value witnesses, the class), or a known value for an `of` value parameter it reads (Rule CM4.4);
2. an ownership mask: which of its `exclusive` parts the caller exclusively owns (`spec/quantities.md` Rule Q9.3);
3. a known class for an owned parameter or for the destination (`spec/memory_management.md` Rule MM7.4);
4. a known handler for an effect of its row (`spec/effects.md` §7.6), which turns an evidence lookup and an indirect call into a direct one.

Inside a clone, calls through a known witness become direct calls or inline code, a known immediate type stays in registers, an exclusively owned cell is reused without a test, and a known class removes the witness call for `drop`.

Specialisation is defined on Core, the IR of `spec/memory_management.md` §20.3: a clone is the baseline's Core with the known witnesses substituted and simplified. An implementation may produce the same code another way (for example by lowering the typed tree again with the types substituted), provided the result is what substitution would give.

The symbol name of a clone is a function of the function's name and the canonical key (Rule CM6.5).

### 5.2 A clone behaves as the baseline (Rule CM5.2)

A clone must return the same value and perform the same effects, in the same order, as the baseline version called with the same arguments and the witnesses of its key. It may differ only in cost.

For a `fip fn`, whose one body takes exclusively owned input (Rule CM5.5), the comparison is with that body.

This gives verification a reference: in a test build the compiler may run a property against the baseline version and against a clone and compare the results (`spec/properties.md` Rule P5.3.c).

### 5.3 The clone budget (Rule CM5.3)

Each function has one **clone budget** `B`, in Core bindings.

- Every clone of `f` is charged the size of `f`'s baseline version, counted in Core `let` bindings. The size of the clone itself is not used, so the charge is known before the clone is built.
- The charges of all clones of `f` built in one package must not exceed `B`.
- A closure body is a function of its own: it has its own baseline version, size and budget, and does not count toward the size of the function that creates it.
- A function whose baseline version is larger than `B` has no clones. Its callers that hold exclusively owned values then get the baseline's behaviour: reuse of a `Heap` cell after the runtime test, and no reuse of a `Frame` cell (`spec/memory_management.md` Rule PR6.3). A `fip fn` is not affected (Rule CM5.5).

`B` is one number for all the axes of Rule CM5.1. It replaces the version cap of earlier drafts (`spec/quantities.md` OQ-Q7) and the large-function rule of `spec/memory_management.md` Rule MM7.4.

The **reference value is `B = 512`**. It is provisional: it was chosen before measurement (`spec/memory_management.md` §21). With profile data, `B` is multiplied for functions the profile shows to be hot (implementation plan, Phase 6); the multiplier is implementation-defined.

The budget is **per function**: no function competes with another for it. A change to one function can change another's clones only through the call sites it contains, that is, through the requests it makes (Rule CM5.4), never by taking budget away. That is the same stability goal as the Lipschitz bound of `spec/type_system.md` Rule 6.5.4.

Clones requested by call sites inside the package and versions exported by Rule CM6.4 are charged to the same budget.

### 5.4 The score (Rule CM5.4)

Every call site inside the package whose arguments determine a key for the callee is a **request** for that clone. The candidates for `f` are the keys requested. Each candidate has a score:

```
score(f, key) = removed(f, key) × weight(f, key) ÷ size(f)
```

- `removed(f, key)` — the number of sites in `f`'s baseline version that the key resolves: witness calls for `dup`, `drop`, `reuse_test`, `evacuate`, `copy`; calls through a capability table; `rc == 1` tests that the ownership mask makes unconditional; evidence lookups and indirect operation calls that a known handler makes direct; **and** call sites in `f` at which the key lets the call request a clone of its callee or take an exclusive pointer. The last item is what gives a forwarding function its exclusive version: it removes nothing itself, but without it the function it forwards to could never be asked for one. `removed` is a count over the baseline's Core.
- `weight(f, key)` — the sum, over the call sites that request the key, of `W^d`, where `d` is the number of `fold` / `unfold` steps (and handler clauses running inside one) that enclose the call site. This is the notion of "hot" that Warning W110 already uses without a profile (`spec/memory_management.md` Rule MM5.5). The **reference value is `W = 8`**, provisional.
- `size(f)` — the size of the baseline version, as in Rule CM5.3. Within one function the divisor is the same for every candidate, so it does not change their order; it makes scores of different functions comparable, in the report (Rule CM5.8) and for the hot-function multiplier of Rule CM5.3.

Candidates are built in descending score order while the budget allows. Ties are broken by the canonical key order of Rule CM6.5. A candidate with `removed = 0` is never built.

**Rounds.** Requests come from call sites in baseline versions and in clones already chosen. The specialiser works in rounds:

1. Round 0 collects the requests of every call site in every baseline version.
2. In each round, for each function in the order of its name, the candidates not yet built are ranked and built while the function's budget lasts.
3. The call sites of the clones built in a round add their requests to the next round. A request raises the weight of a candidate that has not been built yet; a clone that has been built is never removed or re-ranked.
4. The specialiser stops after a round that builds nothing.

Budgets only shrink and clones are never removed, so this terminates, and the result depends only on the program.

Two refinements are permitted, because an implementation needs them. Ownership facts of results (`spec/quantities.md` Rule Q4.1.b) depend on which clones exist, so the specialiser may repeat the whole pass until those facts are stable; a clone chosen in an earlier pass stays chosen. A clone that no call reaches when the pass ends is not emitted and gives its charge back; the report lists it as `unused` (Rule CM5.8).

With profile data, `weight` is the measured number of calls that requested the key. The formula and the budget do not change.

### 5.5 Mandatory specialisations (Rule CM5.5)

These are built whenever their conditions hold. They are not charged to the budget and are built in every build mode (§5.7):

1. **Fusion and step inlining.** `spec/type_system.md` Rules 7.4.b.1 and 7.4.b.2: an `unfold` consumed by one `fold`, and a `fold` / `unfold` whose step is a lambda or a statically known function, when the data type is statically known at the call site. In a baseline version whose data type is a type parameter, the conditions do not hold and nothing is required; in a clone for a known type they hold again.
3. **What a `fip fn` body calls.** A clone that a call site inside a `fip fn` body requests is built whatever the budget, and is not charged. Without it the body would call a baseline version, whose witness operations and count tests are reference-count operations (Rule CM4.7), and the function would fail its contract in `--no-specialise` mode or once a callee's budget is spent. This is bounded: a clone forced in this way requests further clones of its own function as an ordinary candidate, not as a forced one.
2. **`fip fn`.** A `fip fn` has one body: the version in which every part it reuses is exclusively owned (`spec/memory_management.md` Rule MM18.3). That version takes the place of the baseline version of Rule CM3.1, and its entry is the function's **exclusive entry**. Its **shared entry** is an adapter that copies the demanded part of each shared argument (`spec/quantities.md` Rule Q9.3) and then calls the body. Wherever these rules say a caller "calls the baseline version" because no clone fits, a caller of a `fip fn` calls the shared entry. The adapter is not `fip`; a caller that reaches a `fip fn` through its shared entry is not `fip` either, and the copy is reported (`spec/memory_management.md` §19) and warned in hot code (W110, Rule MM5.5).

### 5.6 Beyond the budget (Rule CM5.6)

A call site whose key was not built calls the baseline version (for a `fip fn`, its shared entry). Nothing is copied at the call site.

Copying the demanded part of a shared value remains only inside the shared entry of a `fip fn` (Rule CM5.5). The rule of earlier drafts, that a caller copies once a version cap is reached, is withdrawn.

### 5.7 Build modes (Rule CM5.7)

| Mode | Clones built |
|---|---|
| default (`fixed compile`, `fixed ship`) | mandatory (Rule CM5.5), then by score within the budget (Rules CM5.3, CM5.4) |
| `--no-specialise` | mandatory only |
| with profile data (Phase 6) | as default, with measured weights and the hot-function multiplier |

`--no-specialise` builds faster and gives the reference behaviour that Rule CM5.2 compares clones against. Every mode produces a correct program.

### 5.8 Reporting (Rule CM5.8)

`fixed compile --explain-memory` (`spec/memory_management.md` §19) lists, per function: the size of its baseline version; every clone built, with its key, `removed`, `weight`, score and charge; and every candidate not built, with the reason (budget exhausted, function larger than `B`, `removed = 0`, or `unused`: chosen, then reached by no call when the pass ended). `--format json` gives the same data.

## 6. Packages

### 6.1 Package and link unit (Rule CM6.1)

A **package** is the unit of separate compilation and of ABI: a set of source files compiled together, whose compiled form other packages link against without recompiling it. A **link unit** is the set of packages linked into one executable or library.

Inside a package the compiler sees every body, so every call site can request clones (§5.4). Between packages a caller sees only the interface tier (§7.1), unless the callee's package ships the body (§7.3).

Packages depend on each other without cycles. Mutually recursive declarations (`spec/type_system.md` Rule 7.5, §10.1) live in one package.

### 6.2 Public surface (Rule CM6.2)

The public surface of a package is the set of declarations other packages may name: functions, capabilities, `data` types, effects, type aliases and satisfactions. Fixed has no visibility syntax today (OQ-CM1). Until it has:

- a package that declares `main` is a **program**: it has no public surface, and `main` is its only root (Rule CM3.2);
- a package without `main` is a **library**: every top-level declaration is public.

### 6.3 Floor entry (Rule CM6.3)

Every public function has a **floor entry**: one symbol, always present, with a convention that does not depend on the function's body.

- Every declared runtime parameter is passed, including one the body ignores (inferred demand `0`). Phantom type parameters and unread `of` value parameters are still not passed: they are erased by the signature, not by the body.
- Every part of every parameter is passed under demand `own`, shared (Rule CM4.6, shared pointer).
- Every owned parameter comes with a value witness (Rule CM4.1). The callee uses it to `drop` what it does not keep and to `evacuate` what it stores beyond the call (`spec/memory_management.md` Rule MM5.5). The caller need not know the parameter's escape.
- Type parameters, effect evidence and the destination follow Rule CM4.3. Result witnesses follow Rule CM4.5.

The floor entry is the baseline version when the baseline's inferred demands are already these, and otherwise an adapter over it (the adapter of Rule CM4.6). The floor entry of a public `fip fn` is its copying shared entry (Rule CM5.5); its body is always exported as well, as the all-exclusive version (Rule CM6.4), so that a `fip fn` of another package can call it in place. A capability table entry and a closure's shared pointer have the same convention, so a public function can be put in either without a second adapter.

Because the convention is fixed, a change to a body can never break a caller that uses the floor entry: turning a `read` parameter into `own`, storing a parameter that used to be only read, or starting to ignore one changes only what the adapter does.

### 6.4 Exported versions (Rule CM6.4)

A package may export further versions of a public function, each a clone in the sense of Rule CM5.1 or an entry with weaker demands (a `read` parameter that the caller keeps and does not hand over). The interface lists them with their keys and preconditions (Rule CM7.1).

- A package cannot see its importers' call sites. It builds, within the budget of Rule CM5.3 and in the score order of Rule CM5.4 with `weight = 1`, the versions it can name without them: ownership masks, and the classes `Frame` and `Heap` for owned parameters. Up to the budget, that is every combination of exclusive and shared input.
- Clones for a known type come from importers that hold the body (§7.3), or from call sites inside the package.
- A capability table entry and a closure offer two pointers only (Rule CM4.2). The exported versions beyond those two are reachable by direct calls.
- **An exported version never disappears.** If a later compilation of the package would no longer build it, its symbol remains as an alias of, or an adapter into, the floor entry. The adapter re-establishes the floor's convention from the version's precondition: it duplicates what the version's caller kept, and evacuates what the version's caller placed on the assumption that it would not escape.

### 6.5 Symbol names (Rule CM6.5)

The symbol of a version is a function of the package, the declaration's name and the **canonical key**: the type witnesses and `of` values in parameter order, then the ownership mask, then the classes, then the handlers in the canonical order of the effect row, each in a fixed textual form. The floor entry has the empty key. Canonical keys are totally ordered by that text; this is the tie-break order of Rule CM5.4.

Two packages that build the same clone of an imported function therefore emit the same symbol, and the linker keeps one.

### 6.6 Coherence per link unit (Rule CM6.6)

Within one link unit there is at most one satisfaction for each pair (`data` type, capability with its `of` arguments). The pair is formed from the data type's declaration, not from an instance: `List satisfies Show` is one satisfaction for `List of u8` and `List of String` alike. Two packages that each **declare** `T satisfies C` for the same pair cannot be linked together: `error[T012]`, naming both.

Satisfactions the compiler derives (`spec/type_system.md` §7.4) or builds in (§6.6) are determined by the `data` declaration alone. Every package derives the same one, so they never conflict; the linker keeps one copy of the table (Rule CM6.5).

Visibility stays scoped (`spec/type_system.md` §8.4): a satisfaction is usable only where a `use` brings it into scope. Coherence adds that the satisfaction found is the same one everywhere. This is what makes it safe for a value to carry no record of the satisfaction it was built under: a `Set` built with one `Ordered` cannot later be queried with another.

Each package's interface lists the satisfactions it declares, so the check needs no bodies.

### 6.7 What is an ABI change (Rule CM6.7)

Not an ABI change (importers need not be recompiled, and keep their meaning):

- any change to a function body that keeps its signature and declared contracts, including one that changes inferred demands, escapes or classes (Rule CM6.3). The one exception is a change that turns a caller-chosen result into a callee-chosen one or back, listed below;
- a change of the representation selected for a value whose type importers see only as a capability bound;
- which clones a package builds (Rule CM6.4);
- adding a declaration;
- for packages that only **use** a capability: adding a default-bodied method at its end.

Adding a satisfaction is not an ABI change either, but it can change what an importer's source means when it is next compiled: a new candidate may tie with the one representation selection used to pick (`spec/type_system.md` §6.5.5), and the importer then has to disambiguate.

An ABI change:

- removing or renaming a public declaration; changing a signature's types, effect row or `of` parameters;
- weakening a declared contract: removing `fip`, or loosening a cost bound (`spec/cost.md` Rule CT7.1). Tightening one is compatible;
- changing a result from caller-chosen to callee-chosen or back (Rule CM4.5);
- adding a requirement without a default body to a capability, or reordering a capability's members (the table layout of Rule CM4.2);
- for packages that **satisfy** a capability: adding any member to it, with or without a default body. The tables they compiled are one entry short;
- adding a variant or field to a `data` type whose constructors are public;
- changing a body that is in the body tier (§7.3), for importers that specialised it.

The interface tier is sufficient to detect each of these by comparing two versions of it.

## 7. The interface tier and the body tier

A compiled package carries two kinds of information for its importers besides object code.

### 7.1 Interface tier (Rule CM7.1)

Always present. For every public declaration:

- its signature: types, bounds, effect row, `of` parameters; for a module-level `let` that signatures may use as a capability-generator argument (`spec/type_system.md` Rule 6.3.3), its literal value;
- the signature facts of `spec/memory_management.md` Rule MM20.2, for the floor entry and for each exported version: demand per part, escape, class-polymorphic or fixed, result destination and result witness (Rule CM4.5), stack demand, `fip` level, key and symbol;
- its declared cost contracts and their verification status (`spec/cost.md`);
- for capabilities: the member order of the capability table (Rule CM4.2), and every `prop` body (Rule CM7.2);
- for `data` types: variants, fields, the selected representation's witness symbol, and the symbolic cardinality (`spec/reports.md` §4);
- for satisfactions: the pair they cover (Rule CM6.6), the table symbol, and the status of each inherited `prop` (`spec/properties.md` Rule P7.7.a);
- a **content hash** per declaration, covering everything the declaration's meaning depends on, transitively. Verification caches key on it (`spec/properties.md` Rule P7.4).

### 7.2 Props travel as typed expressions (Rule CM7.2)

`prop` bodies are part of the interface tier, not of the body tier, and are not optional. A package that declares `D satisfies C` for an imported `C` must verify `C`'s props for `D` (`spec/properties.md` Rule P7.7.a), so it needs them.

They are stored as **typed expressions**: the pure subset a prop body may use (`spec/properties.md` §4 — no effects, `forall`, `implies`, `match`, `if`, calls), with every name resolved and every node typed. The static prover reads this form directly. For property-based testing the importer lowers it to Core like any other code.

### 7.3 Body tier (Rule CM7.3)

Optional, per function. A function in the body tier ships the **Core of its baseline version**, together with the baseline Core of every non-public function it calls. An importer may then build clones of it (§5) as if the function were its own, charged to the importer's budget for that function.

- Shipping a body makes it part of the ABI for importers that specialised it (Rule CM6.7).
- How a package marks a function for the body tier is open (OQ-CM1).
- Default-bodied capability methods do not need the body tier to be usable: a satisfying type's table points at the compiled default (Rule CM4.2). They need it only to be specialised.

### 7.4 Nothing else is serialised (Rule CM7.4)

The typed tree is not part of either tier. Within one compilation the typed tree, the type table and the resolution table stay available to every later pass, and Core carries source spans (`spec/memory_management.md` §20.3), so no pass loses information by running after lowering. The compiler's own analyses (`spec/reports.md`, `spec/cost.md`) run in process on those structures. External tools read the interface tier and the compiler's reports (`spec/reports.md` §5).

## 8. Open questions

- **OQ-CM1 — Visibility and body-tier syntax.** There is no `pub` and no marker for "ship this body". Until there is, Rule CM6.2 makes every top-level declaration public and no body is shipped. A package-level setting, a declaration modifier, or both are possible.
- **OQ-CM2 — Encoding.** The on-disk form of the two tiers, its versioning, and how the content hash is canonicalised. Resolves `spec/memory_management.md` OQ-MM5 when settled.
- **OQ-CM3 — Values of `B` and `W`.** Reference values 512 and 8 are guesses. To be measured with the hypotheses of `spec/memory_management.md` §21.
- **OQ-CM4 — Type variables of `data` fields.** How a type variable introduced in a variant field without an `of` parameter (`spec/type_system.md` Rule 4.1) is quantified decides whether its witness is stored in the cell. See `spec/type_system.md` §11.
- **OQ-CM5 — Orphan satisfactions.** Rule CM6.6 rejects duplicates at link time. Whether a package may declare a satisfaction when it owns neither the type nor the capability is open; forbidding it would turn most link errors into compile errors.
- **OQ-CM6 — Clones across packages and the budget.** Two importers each charge their own budget for the same clone, and the linker merges the result (Rule CM6.5). Whether a link-time view should deduplicate the charge is open.
- **OQ-CM7 — Wider immediates.** Whether a pair of 64-bit values (for example `u128`, or a small tuple) should travel in two slots in baseline code instead of behind a pointer.
- **OQ-CM8 — Borrowing and exclusivity through a table.** Rule CM4.6 passes every argument under `own`, and offers the exclusive pointer only to a caller that exclusively owns every argument in every part. Both are coarse. A requirement that could state `read` for its receiver would let table calls borrow; an exclusive pointer per parameter would let a polymorphic caller update a container in place while it shares the elements. Demands are otherwise never written (`spec/quantities.md` OQ-Q2), so this needs a measured cost before it gets syntax (`spec/memory_management.md` §21).
- **OQ-CM10 — The weight is lexical.** Rule CM5.4 counts the `fold` / `unfold` steps that enclose a call site in its own function. A function that is called from inside a step but contains none requests its callees with weight 1. In the `records` benchmark the request that runs a million times ties with one that runs a hundred times and wins only by key order. Carrying the depth of a function's own call sites into the requests it makes would fix it; profile data makes the question moot.
- **OQ-CM11 — Order of the axes.** Rule CM5.4 ranks all of a function's candidates together. The first implementation chooses type clones first and gives ownership clones what is left of the budget, because type clones are produced by lowering again while ownership clones need demand inference on the result. No benchmark shows a difference yet.
- **OQ-CM12 — Tables per instance.** Rule CM4.2 gives a parameterised type one table and builds witness records for its instances. The first implementation emits one table per instance used and builds no record at run time; a call that would need one is compiled as an instance. Witness records are needed before a package can export a polymorphic function over a parameterised type.
- **OQ-CM9 — Evidence across packages.** Rule CM4.3 fixes the order of an evidence vector by the canonical order of the effect row. Row variables, and the library entry points of `spec/effects.md` Rule E5.4, need the same treatment.

## 9. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | §6.5 selects representations and never clones; §7.4.b.1–2 are the mandatory specialisations of Rule CM5.5; §8.4 scoped visibility, with coherence from Rule CM6.6 |
| `spec/quantities.md` | Demands are inferred on baseline Core and again on each clone; Rule Q9.3 defines the ownership axis of a key; Rule CM4.6 gives the convention where no demand can be inferred |
| `spec/memory_management.md` | Rule MM7.3 is the value witness; Rule PR5.6 is the two-pointer rule; §18 `fip`; Rule MM20.2 signature facts are the core of the interface tier; §19 carries the report of Rule CM5.8 |
| `spec/effects.md` | Evidence vectors are the hidden parameters of Rule CM4.3(3) |
| `spec/properties.md` | Rule P7.4 keys on the content hash; Rule P7.7.a needs Rule CM7.2; Rule P5.3.c uses Rule CM5.2 |
| `spec/cost.md` | Contracts in the interface tier; weakening one is an ABI change |
| `spec/reports.md` | Coverage, cardinality and the tool interface built on §7 |
| `docs/decisions/P1`–`P5` | The decisions and the alternatives rejected |
