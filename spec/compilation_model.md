# Fixed Compilation Model

**Status:** Draft v0.3.5
**Specifies:** how Fixed v0.4.4 source becomes code: the baseline version of every function, the travel form and the rest form of a value, immediates, accessors, witnesses, specialisation (clones, budget, score, mandatory cases), build modes, packages and the package ABI, visibility, satisfactions in a link unit, the interface and body tiers, and the application link (choice points, summaries, selection, the lock file, the profile file, clones at link, the type table).
**Last revised:** 2026-11-21

**Changes from v0.3.4.** §6 no longer says that a library's deferred satisfaction sites stay unbound until an application links it: there are none (P6, fourth round, "No deferred sites").

**Changes from v0.3.3.** Applies the owner's decision of 2026-11-21, fourth round (P6, "No deferred sites"). Rule CM6.6: every satisfaction site is resolved at package compile, and an ambiguous one is `E061` there (`spec/type_system.md` Rule 8.4.b); the deferred sites are withdrawn, and with them the resolution level `using` in the application root. The interface tier (Rule CM7.1), the application link (§8) and the lock file (Rule CM8.5) no longer record or resolve them; Rule CM6.7 no longer speaks of a link error for one. Rule CM8.4: a data type whose pair the site that created a choice point does not decide does not enter the pool, so selection resolves no satisfaction and its only error stays zero viable candidates. §8: a test build's application root is no longer a resolution level.

**Changes from v0.3.2.** Applies the owner's decisions of 2026-11-21, fourth round (P6, "Owner's decisions (fourth round)"). Rule CM8.1: a list literal whose type is not fixed is a choice point (`spec/type_system.md` Rule 5.10).

**Changes from v0.3.1.** Applies the owner's decisions of 2026-11-20, third round (P6, "Owner's decisions after the consistency review"). No witness travels with an anonymous result: its choice point is bound by symbol, and a requirement's anonymous result is one choice point shared by every implementation (Rules CM4.2, CM4.5). A link unit is an application; a library is not one (Rule CM6.1). The sixth resolution level is removed, and derived satisfactions get no preference (Rule CM6.6).

**Changes from v0.3.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): the table convention is one containment test with a table of examples (Rule CM4.6); an `Inline` value that is not an immediate is copied out before it meets a `ref` witness (Rules CM3.5, CM4.1); new accessor `T.variant` (Rule CM3.7); level 4 of satisfaction resolution, deferred sites (level 5 only) and private satisfactions follow `spec/type_system.md` Rules 8.4 and 8.6.a (Rule CM6.6); the test build's application root (§8); the pool's satisfaction, viability at link and the score cite their owners (Rule CM8.4); the type table has a row for every kind of cell (Rule CM8.8); "descriptor" replaces "header" for the tag and reference map.

**Changes from v0.2.** Applies `docs/decisions/P6-resolution-round-2026-11-20.md` (D1–D17). The 64-bit slot is the *travel form* (Rule CM3.3); new Rules CM3.5 (immediates: every data type instance with no reference that fits in 64 bits), CM3.6 (rest form and `width`) and CM3.7 (accessors for layouts not known at package compile). The value witness gains `width` and its family is fixed (Rule CM4.1). Witness records are built on the stack in baseline code and are static where the instance is known at link (Rule CM4.2). A closure captures its evidence; choice points are not hidden parameters (Rule CM4.3). The signature alone decides who picks the representation of a result; the caller-chosen / callee-chosen split is withdrawn (Rule CM4.5). The convention of a call through a table is derived from the requirement's signature, with an exclusive pointer narrowed to `own` parameters of type `Self` (Rule CM4.6). Ownership clones are requested only where a run-time test cannot stand in, and the static weight is carried along call edges (§5). Declarations are private unless marked `pub` (Rule CM6.2). Coherence per link unit is withdrawn: a link unit may hold several satisfactions of one pair, resolved by `using` (Rule CM6.6). Bodies ship by default (Rule CM7.3). New §8 specifies the application link. Open questions move to §9 and cross-references to §10; OQ-CM1, OQ-CM4, OQ-CM5, OQ-CM8, OQ-CM10, OQ-CM11 and OQ-CM12 are closed, and OQ-CM13 to OQ-CM18 are new.

**Changes from v0.1.** Amended from the first implementation (`docs/plans/phase-4.1-baseline-specialiser.md`, `docs/baselines/runtime-2026-11-20-phase-4.1.md`): the clones a `fip fn` body requests are mandatory (Rule CM5.5); a clone that nothing reaches at the end is dropped and reported as `unused` (Rules CM5.4, CM5.8); the specialiser may repeat its pass until ownership facts are stable (Rule CM5.4); new OQ-CM10 to OQ-CM12 record what the measurements showed about the weight, the order of the axes and tables per instance.

**Why this document exists.** Until v0.3 of `spec/memory_management.md` the compilation model was implicit: lowering monomorphised every function from `main`, and code that could not be monomorphised (higher-order code, separate compilation) was a special case handled by class witnesses. This document reverses the default. Every function has one **baseline** version that is polymorphic and passes witnesses, in the style of Neut. Specialised versions are an optimisation added where the compiler can identify the types, ownership and classes that matter. A package cannot know how the rest of the program uses an abstract type it declares, so a choice of representation that depends on that use is made when the application is linked, from summaries, and bound by the linker: **decide late, compile early** (§8). The decisions behind it are recorded in `docs/decisions/P1`–`P6`.

## 1. Scope

This document specifies:

- The baseline version and its roots (§3).
- The travel form, immediates, the rest form, accessors, and the rule that cloning never changes a layout (§3.3–§3.7).
- Witnesses: value witnesses, capability tables, hidden parameters, result witnesses, the convention of calls through a table (§4).
- Specialisation: what a clone is, the budget, the score, mandatory specialisations, build modes (§5).
- Packages: visibility and the public surface, floor entries, exported versions, symbol names, satisfactions in a link unit, what is and is not an ABI change (§6).
- The interface tier, including usage summaries, and the body tier (§7).
- The application link: choice points, binding by the linker, selection, the lock file, the profile file, clones at link, and the link-time type table (§8).

It does **not** specify:

- The on-disk encoding of the interface and body tiers, the lock file and the profile file (OQ-CM2).
- Binding at load time (shared libraries, plug-ins) and symbol versioning beyond the naming rule of §6.5.
- How measurements are taken during testing (`spec/properties.md` Rule P7.9). §5 and §8 only say how they are used.
- The surface of satisfaction names and `using` (`spec/type_system.md` Rule 8.4), and the diagnostic sinks `inspect` and `trace` (`spec/diagnostics.md`).
- Lifetime classes, regions and reference counting — `spec/memory_management.md`. Demands and ownership — `spec/quantities.md`. Cost contracts — `spec/cost.md`.

## 2. Dependencies and prior art

- `spec/type_system.md` — §4.1 (type variables), §5.9 (phantom parameters), §6.5 (representation selection; Rule 6.5.5, disambiguation), §7.3 (`of` value parameters), §7.4 (derived members), §8 (`satisfies`; Rule 8.4, satisfactions and `using`; Rule 8.4.a, the satisfaction is part of the type).
- `spec/quantities.md` — §4.1 (demands), §9.3 (versions).
- `spec/memory_management.md` — §3 (classes), §7 (destinations, value witnesses), Rule PR3.1 (cell layout), Rule PR5.6 (closure entries), §18 (`fip`), §20.2 (signature facts).
- `spec/effects.md` — §7 (evidence passing), Rule E7.8 (evidence captured by closures).
- `spec/properties.md` — §7 (verification, statuses), Rule P7.4 (cache), Rule P7.8 (the two lanes), Rule P7.9 (measurement during testing).
- `spec/cost.md` — cost contracts that travel in the interface tier; viability (Rule CT8.1).
- `spec/diagnostics.md` — Rules DG3.1–DG3.6 (diagnostic sinks; Rule DG3.6, what is printed for a value).
- `spec/reports.md` — Rule RP3.3 (counters, the demand side of measurement).

Prior art, cited from general knowledge and **not** re-checked against the sources for this draft (unlike the list in `spec/memory_management.md` §2):

- vekatze, Neut — a type is compiled to a closed function `exp(selector, v)` that discards or copies a value; polymorphic code is compiled once and receives it. The model for §4.1.
- Swift — generic code is compiled unspecialised against witness tables and value witnesses; specialisation is an optimiser pass; a resilient library hides layouts, and `@inlinable` exposes a body to importers. The model for §5 and §6–§7.
- GHC — dictionary passing as the compilation of type classes; unfoldings in interface files allow cross-module specialisation.
- van Oortmerssen, Lobster — one fallback version for large functions instead of one copy per ownership combination. Subsumed by the budget of §5.3.
- LLVM ThinLTO — per-module summaries combined at link into a whole-program decision without whole-program compilation. The model for §8.3.

## 3. The baseline

### 3.1 Baseline version (Rule CM3.1)

Every function, method body, derived member (`spec/type_system.md` §7.4) and closure body that is reachable from a root (§3.2) has exactly one **baseline version**. The baseline version:

1. is compiled once, for all instantiations of its type parameters;
2. receives a witness for each type parameter (§4) and operates on values of parameter type through it;
3. is the **shared** version in the sense of `spec/quantities.md` Rule Q9.3: it is correct for arguments the caller does not exclusively own, and reuses a `Heap` cell only after a runtime `rc == 1` test (`spec/memory_management.md` Rule PR6.3);
4. is correct for every lifetime class of its owned parameters, which it handles through the value witness (§4.1).

The baseline version is always a correct callee. Every other version of the function (§5) is an optimisation and must behave as the baseline does (Rule CM5.2).

A `fip fn` is the one exception to item 3: see Rule CM5.5. The baseline version of a private function is not emitted when every call of it uses a clone (Rule CM6.2); it is still the reference that those clones must match.

### 3.2 Roots (Rule CM3.2)

The roots of a package are:

- `main`, if the package has one;
- every declaration in the package's public surface (Rule CM6.2);
- in a test build, every `prop` body that verification runs (`spec/properties.md` §4.1).

Code that no root reaches is not compiled. A package with `main` and nothing marked `pub` therefore compiles exactly the code `main` reaches, as before.

### 3.3 The travel form (Rule CM3.3)

A **slot** is 64 bits wide on every target. A value **travels** in one slot: in a register, as an argument or result, and as an argument or result of a closure call or of a call through a capability table. The slot is the value's **travel form**.

- An **immediate** (Rule CM3.5) travels in the slot itself.
- Any other value is a **reference**: the slot holds a pointer to the value, which lives where its lifetime class puts it (`spec/memory_management.md` §3).

The slot is the form of a value in three places:

1. a value of type-parameter type in a baseline version (variables, arguments, results);
2. every payload field of a data cell (`spec/memory_management.md` Rule PR3.1);
3. an argument or result of a call through a capability table (§4.2) or a closure.

How a value is stored inside a flat container or inside an immediate is its rest form (Rule CM3.6), not its travel form.

**Widening.** Moving a value from its rest form into a slot widens it; moving it back narrows it. A signed integer is sign-extended into the slot and an unsigned one zero-extended; `bool` is 0 or 1; `char` is its code point, zero-extended; `f64` is its bit pattern; `f32` is its bit pattern, zero-extended; `()` is 0; an immediate data type instance is its bits as Rule CM3.5 lays them out, zero-extended. Narrowing keeps the low `width` bytes (Rule CM3.6).

The width is 64 bits on 32-bit targets too. `i64`, `u64` and `f64` are then immediates everywhere and a data cell has the same layout on every target. The price on a 32-bit target is four unused bytes in every field that holds a reference.

### 3.4 Cloning never changes a layout (Rule CM3.4)

A specialised version of a function (§5) uses the same memory layout for every value as the baseline version does. Layout changes only through **representation selection** (`spec/type_system.md` §6.5, §8 of this document), and the selected representation of a value is identified by its witness (§4).

Consequences:

- A value built by a clone can be passed to a baseline version, and the reverse, with no conversion. The decision to clone one function never forces a decision about its callers or callees.
- A clone may keep an immediate of known type in a narrower register and widen it when it stores the value in a slot. That is not a layout change.
- Whether an instance is an immediate, and its rest form, are pure functions of the type and the witnesses of its arguments (Rules CM3.5, CM3.6). A clone, where the arguments are constants, and the baseline, where it reads them from witnesses, apply the same function and therefore agree.
- `List of u8` built from the declared `data List` is a cell with one slot per element field in every version. A dense layout for a sequence of `u8` is a different representation, a satisfier that selection picks (Rule CM3.6), with its own witness.

**Reference map.** `drop` on a cell must not depend on which instantiation built it. The descriptor of a cell therefore records which payload slots hold counted references (`spec/memory_management.md` Rule PR3.1). For a slot of parameter type the entry comes from the value witness (`kind`, §4.1) when a baseline version builds the cell, and is a constant in a clone. Cells stay self-describing: `drop` needs no type information.

### 3.5 Immediates (Rule CM3.5)

A primitive value whose type has at most 64 bits (`bool`, `char`, `()`, every integer type up to 64 bits, `f32`, `f64`) is an immediate. So is an instance of **any** data type, parameterised or not, that holds no reference and fits in 64 bits. An immediate travels inside the slot: it has no cell, no header and no count.

The rule, *provisional* (OQ-CM13):

- A data type instance is an **immediate** iff no field of any variant is a reference, and `tag + max over variants of (sum of field widths) ≤ 8` bytes, where `tag` is 1 byte when the type has more than one variant and 0 otherwise, and the width of a field is the `width` of its type (Rule CM3.6).
- **Bit layout.** The tag, if any, sits in the low byte. The fields of the variant follow in declaration order, each at its width, from the byte after the tag (from byte 0 when there is no tag), with no padding. Unused high bits are zero.
- The **width** of an immediate instance is its size, as above, rounded up to 1, 2, 4 or 8 bytes. A type with no field and one variant has size 0 and width 1.
- A field whose type is always a reference (`String`, a closure, a type that is statically a cell) makes the type **statically a cell**. Every recursive type is statically a cell.

```
data Order: Less | Equal | Greater              // tag only: size 1, imm1
data Rgb: Rgb(red: u8, green: u8, blue: u8)     // one variant, no tag: size 3, imm4
data Pair of (A, B): Pair(first: A, second: B)  // Pair of (u8, u16): size 3, imm4
                                                // Pair of (u64, u8): size 9, a cell
                                                // Pair of (String, u8): a cell
```

For a parameterised type, the rule is a pure function of the witnesses of its arguments: a field of parameter type is a reference when the argument's witness is `ref`, and otherwise has the argument's `width`. Code that knows the type but not its arguments computes the layout from the witnesses it already receives (Rule CM3.7). A field whose type is a choice point (Rule CM8.1) has the width of the chosen type, known at link.

A value that representation selection makes `Inline` without being an immediate is not covered by this rule: it is copied out to a cell with a descriptor where it meets code that does not know its type (`spec/memory_management.md` Rule MM3.1).

Equality of two immediates of one type is not equality of their bits unless the type's `Eq` says so; the zero high bits only make the bits canonical.

The rule loses two things, accepted until a program shows they hurt: narrow fields are not packed inside a value that must be a cell (the allocator's rounding absorbs most of it), and nothing wider than 64 bits is an immediate (OQ-CM7).

### 3.6 Rest form and `width` (Rule CM3.6)

The **rest form** of a value is how it is stored inside a flat container or inside an immediate. It is separate from the travel form (Rule CM3.3).

- Every value witness has a **width**: 1, 2, 4 or 8 bytes. The width of a primitive type is its size (`bool`, `i8`, `u8` and `()` 1; `i16`, `u16` 2; `char`, `i32`, `u32`, `f32` 4; 64-bit types 8). The width of an immediate data type instance is given by Rule CM3.5. The width of a reference is 8 (the pointer).
- A **flat container** (for example a packed array, a satisfier of `Sequencing`) stores each element at its width. The stride comes from the element's witness in baseline code and is a constant in a clone.
- Storing an element narrows its slot to its width; loading it widens it again (Rule CM3.3).

A data cell is not a flat container: its payload fields are slots (`spec/memory_management.md` Rule PR3.1).

**Density by selection.** A dense sequence is a satisfier (a packed array) that the selector picks at link when usage favours it (Rule CM8.4). The compiler never repacks the cells of another type. This keeps one layout per type, so a value needs no conversion when it passes between code that knows its type and code that does not.

### 3.7 Accessors for layouts not known at package compile (Rule CM3.7)

A package constructs and reads a data type through **accessors**, per-type entry points, when the layout of the type is not known when the package is compiled. There are two kinds:

1. **Choice-point layouts.** The layout depends on a choice point (Rule CM8.1), for example a field `red: N is Numeric`. The accessors (`Color.RGB.make`, `Color.red`) are generated by the link step once the choice is made (Rule CM8.2).
2. **Parameterised layouts.** A parameterised type that could be an immediate for some arguments (Rule CM3.5). The accessors take the argument witnesses (`Pair.make(a, b, wA, wB)`, `Pair.first(p, wA, wB)`) and compute the layout from them. They are generated with the type, in the package that declares it. For a type with more than one variant they include `T.variant(x, witnesses)`, which returns the index of `x`'s variant: from the low byte of the slot when the instance is an immediate, and from the cell's tag less the type's base (Rule CM8.8) when it is a cell. A `match` in generic code reads the variant through it (`spec/pattern_matching.md` §8.4).

A call through an accessor in baseline code costs one direct call per construction or field read; this is accepted as a cost of development builds. A clone inlines the accessor. A type whose layout is statically known (every field's type known, or the type statically a cell) has no accessors, and code uses its fields directly.

Accessors are public symbols only for a `pub data` type. The package that declares a private data type reaches its fields itself and may inline the computation of the second kind; it still needs accessors of the first kind for a field whose layout depends on a choice point bound at link (Rule CM6.2).

## 4. Witnesses

A witness is what a baseline version needs to know about a type it cannot see. There are two kinds. Both are static data wherever the type is known, so passing one is passing a pointer.

### 4.1 Value witness (Rule CM4.1)

The **value witness** of a (type, lifetime class) pair is the class witness of `spec/memory_management.md` Rule MM7.3, a closed function after Neut:

```
witness(op, v, dest)    op ∈ { drop, dup, reuse_test, evacuate, copy, size, kind, width }
```

`drop`, `dup`, `reuse_test`, `evacuate` and `size` are as in Rule MM7.3. Three operations are added there for this document:

- `copy` returns an exclusively owned copy of the value in `dest`. It is what the shared entry of a `fip fn` uses (Rule CM5.5); `evacuate` cannot serve, because it is the identity when the value already lives where `dest` is. For a cell it takes the size and field kinds from the link-time type table (Rule CM8.8).
- `kind` answers whether a slot of this type holds an immediate or a reference (Rule CM3.4). A `ref` witness always meets a cell with a descriptor: an `Inline` value that is not an immediate is copied out to a cell before it reaches one (`spec/memory_management.md` Rule MM3.1).
- `width` is the rest width of Rule CM3.6.

**The family is fixed.** An immediate needs no `dup`, `drop` or `copy`, so the value witness of every instance of every type is one of:

| Witness | For | `drop` | `dup`, `evacuate`, `copy` | `reuse_test` | `size`, `width` | `kind` |
|---|---|---|---|---|---|---|
| `imm1`, `imm2`, `imm4`, `imm8` | an immediate of width 1, 2, 4, 8 | no-op | return the bits | false | 1, 2, 4, 8 | immediate |
| `ref` | a reference | Rule MM7.3 | Rule MM7.3 | Rule MM7.3 | size of the value; 8 | reference |

The immediate witnesses are the same for every lifetime class. The operations of `ref` depend on the class of the value, as in the table of Rule MM7.3, so `ref` stands for one static witness per lifetime class. Generic code picks a member of this family, for a parameterised instance by applying Rule CM3.5 to the argument witnesses; it never builds a value witness at run time.

A value witness is passed:

1. for each non-phantom type parameter of a function, method or closure;
2. for each owned parameter whose class is not fixed by the callee's signature facts (the "class-polymorphic owned parameter" of Rule MM7.3).

It is passed per parameter, never per value: a container's witness covers its elements (Rule MM7.3). No cell stores a witness.

A lifetime class belongs to a value, not to a type, so two arguments of the same parameter type may live in different classes. The witness of case 1 describes the class in which the function's own values of that type live (those it builds, and those it receives from table calls into its destination). An owned argument of that type that lives in another class gets its own witness under case 2.

### 4.2 Capability table (Rule CM4.2)

For each satisfaction `T satisfies C` (written, derived by `spec/type_system.md` §7.4, built in by §6.6, or provided by the compiler for a primitive type) the compiler emits one **capability table**: a static record with one entry per member of `C`. A table is emitted only for a satisfaction that some code uses as a witness. A satisfaction that does not define every member without a default body is a type error where it is declared (`spec/type_system.md` §8). The entries:

- each instance method requirement;
- each `Self.fn` constructor requirement;
- each default-bodied method (`spec/type_system.md` Rule 6.1): the entry is the satisfying type's override if there is one, otherwise the capability's own default body, which is compiled once as a baseline version that takes the witness of `Self`;
- the members `C` inherits through `extends`, flattened in the order of `spec/type_system.md` §6.2.

An `impossible` mapping (`spec/type_system.md` Rule 8.2) gives an entry that panics.

A link unit may hold several satisfactions of one pair (Rule CM6.6). Each has its own table; the table's symbol names the satisfaction (Rule CM6.5).

**Two code pointers per entry.** Every entry holds a shared and an exclusive code pointer, by the rule that already governs closures (`spec/memory_management.md` Rule PR5.6). The exclusive pointer is the same as the shared one unless an exclusive version of that member was built (§5). What each pointer assumes is Rule CM4.6.

**Refinement capabilities** (`Marker` class, `spec/type_system.md` §6.4) have no members and no table.

**Parameterised types.** The satisfaction of a parameterised data type (`List satisfies Functor`) has one table, whatever the type arguments. Its entries are baseline versions that are polymorphic in the data type's own parameters, so they need the witnesses of those parameters. The witness of an **instance** (`List of u8`, or `List of A` inside code polymorphic in `A`) is therefore a **witness record**: the value witness (Rule CM4.1), the tables, and the witness records of the type arguments. A call through a table passes the receiver's witness record to the entry.

- Where every argument is known when the package is compiled, the record is static data.
- Where an argument is a choice point (Rule CM8.1), the record is static data emitted at link, when the instance becomes known.
- Where an argument is a type parameter of the code that forms the instance, a baseline version builds the record **on the stack**, in its own frame, from the witnesses it holds. The record lives as long as that frame; a callee that receives it does not keep it beyond the call.

**Results of requirements.** A requirement whose result is an anonymous bound (`compare -> is Ordering`, `get -> is Optional of Part`) follows Rule CM4.5: the result is one choice point owned by the requirement, and the entry returns only the value.

### 4.3 Hidden parameters (Rule CM4.3)

A baseline version takes its witnesses as hidden parameters before its declared parameters, in this order:

1. for each non-phantom type parameter of the signature, in order of introduction (`spec/type_system.md` Rule 4.1): its value witness, then one capability table for each capability in its bound set, after `extends`-flattening has removed tables that another table already contains, in the canonical bound order of `spec/type_system.md` §6.5.8. Together these are the parameter's witness record (Rule CM4.2);
2. for each owned parameter whose class is not fixed (Rule CM4.1, case 2), in parameter order: its value witness;
3. the `of` value parameters the body reads (Rule CM4.4);
4. the evidence vector for the function's effect row (`spec/effects.md` §7);
5. the destination, if the result is built in one (`spec/memory_management.md` Rule MM7.1).

A method-level type parameter (`fold<R>`) is a type parameter of that method: its witness is passed at each call.

**Choice points are not hidden parameters.** The witness of a choice point (Rule CM8.1) is never passed. Code that uses a value of a choice-point type refers to its witness operations and table entries as symbols that the linker binds (Rule CM8.2).

**Closures capture their evidence.** A closure captures the evidence of the effects its body performs that its type does not mention (those Rule E5.5.a passes through), at the point where it is given that type (`spec/effects.md` Rule E7.8). Captured evidence is in the closure's environment, not a hidden parameter. The effects in the row of its type still arrive as evidence at each invocation, by item 4. A pure higher-order function takes no evidence for the effects of the closures it calls; it cannot intercept an effect its type does not mention.

A baseline version can do with a value of parameter type only what its witness provides: move it, and call the members of the capabilities in the parameter's bound set. `show`, `==` and `clone` on a value of an unbounded type parameter are type errors; the programmer writes `T is Show`, `T is Eq` or `T is Clone`. Printing such a value for diagnosis goes to a diagnostic sink (`spec/diagnostics.md`), which needs no witness (Rule CM8.8).

Evidence and witnesses share a calling convention (a hidden parameter that holds code pointers) and nothing else. Evidence is dynamically scoped: each `handle` installs a new entry and may shadow an outer one (`spec/effects.md` Rule E6.5), and an entry refers to a handler frame. A witness is static: it names one satisfaction, fixed where the code that passes it is compiled or linked. They are therefore separate parameters.

The layout of an evidence vector for a signature follows the canonical order of its effect row, so that two packages agree on it.

### 4.4 `of` value parameters in baseline code (Rule CM4.4)

An `of` value parameter (`spec/type_system.md` §7.3) is erased from every value's representation, as before. A baseline version that **reads** one (`b.lo`) cannot treat it as a compile-time constant, because it is compiled for all values of the parameter. It receives the value as a hidden argument with demand `read`. A clone for a known value uses the constant. A function that does not read the parameter receives nothing.

### 4.5 Who picks the representation of a result (Rule CM4.5)

The signature decides. The body cannot change the calling convention.

- **Anonymous result, `-> is C`.** The result is a choice point owned by the function (Rule CM8.1). No witness is passed or returned. The caller treats the value as opaque and uses it through the choice point's witness, which the link step binds by symbol (Rule CM8.2). Every value the body returns has the type of that one choice point: an abstract constructor call for the result is the choice point, and a body that constructs a concrete data type fixes it.
- **Named result, `-> R is C`, with `R` in no parameter type.** `R` is an ordinary type parameter. The caller passes its witness (Rule CM4.3). A body that returns a concrete data type where `R` is expected is a type error. An abstract constructor call for `R` stays abstract and is resolved by the caller: at a call site, `R` is a concrete type, a named type parameter of the caller (whose witness is passed on), or else a choice point of the caller (Rule CM8.1).
- **Requirements.** A requirement whose result is `-> is C` (`compare -> is Ordering`) owns one choice point, shared by every satisfaction's implementation of it. A call through a capability table therefore knows the type of the result without a witness. An implementation that constructs a concrete data type restricts the candidates of that choice point to that type; two implementations that restrict it to different types leave no viable candidate, which is the link error of Rule CM8.4.

```
fn parse(s: String) -> is Optional of u64      // choice point of parse; no witness travels
fn empty() -> R is Empty                       // caller passes R's witness
    Empty.empty()                              // stays abstract; resolved at the caller
```

This also settles `spec/type_system.md` §8.5 against its Rule 8.1.a: an abstract constructor call for an anonymous result is a choice point, which is never "zero matches" while the link unit holds a satisfier; for a named `R` it stays abstract.

The convention is read from the signature, so it is part of the interface tier (Rule CM7.1) and changes only with the signature (Rule CM6.7). A clone, or an importer that holds the body (§7.3), that knows the concrete type of an anonymous result uses the value at that type.

The reason: a convention inferred from the body made a body change an ABI change, and made the choice depend on whether the callee happened to see a constructor. A choice point is made once for the program, at link, where the uses are known (§8).

### 4.6 Calls through a table (Rule CM4.6)

A capability method **requirement** has no body, so it has no inferred demand (`spec/quantities.md` §4.1). The convention of a call through a capability table is derived from the requirement's signature, never from a body. This rule is the one statement of it; `spec/quantities.md` Rule Q4.1.e, `spec/memory_management.md` Rules PR5.5 and PR5.6 and `spec/effects.md` Rule E5.5.b cite it.

- **Containment.** A parameter, the receiver included, is **`own`** iff a value of the parameter's type can become part of the result, and **`read`** otherwise. For this test a value of type `Self` contains values of its capability's `of` parameters (`Part` included); a type variable, an anonymous bound included, contains only itself; and a function type is contained in nothing but a function type or a data type with a field of function type. A data type contains what its fields contain.
- **Shared pointer.** `read` parameters are borrowed. `own` parameters are passed as counted references, moved at the caller's last use; the callee may keep, drop or reuse-after-test each one.
- **Exclusive pointer.** As above, and the caller guarantees that it exclusively owns, in every part, each `own` parameter whose type is `Self`. Other arguments may stay shared. The caller picks this pointer statically, as for a closure (Rule PR5.6), and only when the guarantee holds.

```
cap Sequencing of Part:
    fn size(self) -> u64
    fn compare(self, other: Self) -> is Ordering
    fn push(self, x: Part) -> Self
    fn get(self, i: u64) -> Part

cap Folding:
    fn fold(init: R, f: (R, Part) -> R) -> R
```

| Requirement | Receiver | Other parameters | Why |
|---|---|---|---|
| `size(self) -> u64` | `read` | — | `u64` contains no `Self` |
| `compare(self, other: Self) -> is Ordering` | `read` | `other`: `read` | the anonymous bound contains only itself |
| `get(self, i: u64) -> Part` | `read` | `i`: `read` | the type variable `Part` contains only itself, not `Self` |
| `push(self, x: Part) -> Self` | `own` | `x`: `own` | `Self` contains `Self`, and contains `Part`; exclusive pointer: `self` only |
| `fold(init: R, f: (R, Part) -> R) -> R` | `read` | `init`: `own`; `f`: `read` | `R` contains only itself; a function type is not contained in a type variable |

`push` updates `self` in place through the exclusive pointer while `x` stays shared. `get` borrows `self` and returns a `dup` of the part.

A satisfying type's method whose inferred demands differ from the convention is entered through an adapter: it `dup`s a `read` argument the method keeps, and drops an `own` argument the method only reads or ignores. The adapter is the table entry; the method's own entry stays available to callers that know the type. A clone for a known type calls the method's own entry.

A call of an effect operation through evidence keeps the convention of `spec/effects.md` Rule E7.7: the performer cannot see the clause, so it passes every argument under `own`.

Arguments and results are slots (Rule CM3.3). Method-level type parameters pass their witnesses per call (Rule CM4.3).

The convention is provisional: before it is frozen, a benchmark with capability-bounded parameters measures it against passing every argument under `own` (OQ-CM18). The reason for it: under `own` everywhere a call through a table never borrowed, and the exclusive pointer needed exclusive ownership of the elements of a container as well as the container, so a polymorphic caller could not update a container in place while it shared the elements.

### 4.7 Witness operations and `fip` (Rule CM4.7)

In a baseline version, a `dup`, `drop`, `reuse_test`, `evacuate` or `copy` on a value of parameter type is a witness call, and the compiler does not know whether it touches a count. For the `fip` contract (`spec/memory_management.md` §18) such a call **is** a reference-count operation. A `fip fn` must therefore be in place for every instantiation: it may move values of parameter type, and may not duplicate or discard them. A call through a capability table is `fip` only if the requirement is declared `fip` (`spec/cost.md` §4.3).

`fip` is checked once, on the function's one body (Rule CM5.5). A clone can only remove operations, so it inherits the result.

## 5. Specialisation

### 5.1 Clones (Rule CM5.1)

A **clone** of a function is a copy of its baseline version compiled for a **key**: any combination of

1. a known witness record for one or more of its type parameters (which fixes the type, its satisfactions, its capability tables and, for value witnesses, the class), or a known value for an `of` value parameter it reads (Rule CM4.4);
2. an ownership mask: which of its `exclusive` parts the caller exclusively owns (`spec/quantities.md` Rule Q9.3);
3. a known class for an owned parameter or for the destination (`spec/memory_management.md` Rule MM7.4);
4. a known handler for an effect of its row (`spec/effects.md` §7.6), which turns an evidence lookup and an indirect call into a direct one.

Inside a clone, calls through a known witness become direct calls or inline code, a known immediate type stays in registers, accessors are inlined (Rule CM3.7), an exclusively owned cell is reused without a test, and a known class removes the witness call for `drop`.

An ownership mask is requested only where a run-time `rc == 1` test cannot stand in for it: in the bodies of `fip fn` and in what they request (Rule CM5.5), and for an exclusively owned value that has no count (a class whose `reuse_test` is static in Rule MM7.3: `Frame`, stack `Dest`, `Region`). A `Heap` value uses the baseline with its run-time test. The reason is a measurement: on the benchmarks of Phase 4.1 the test ran as fast as unconditional reuse. That measurement is under suspicion of hiding a compiler fault and must be checked before this rule is relied on (OQ-CM16).

Specialisation is defined on Core, the IR of `spec/memory_management.md` §20.3: a clone is the baseline's Core with the known witnesses substituted and simplified. An implementation may produce the same code another way (for example by lowering the typed tree again with the types substituted), provided the result is what substitution would give.

The symbol name of a clone is a function of the function's name and the canonical key (Rule CM6.5).

### 5.2 A clone behaves as the baseline (Rule CM5.2)

A clone must return the same value and perform the same effects, in the same order, as the baseline version called with the same arguments and the witnesses of its key. It may differ only in cost.

For a `fip fn`, whose one body takes exclusively owned input (Rule CM5.5), the comparison is with that body.

This gives verification a reference: in a test build the compiler may run a property against the baseline version and against a clone and compare the results (`spec/properties.md` Rule P5.3.c).

### 5.3 The clone budget (Rule CM5.3)

Each function has one **clone budget** `B`, in Core bindings.

- Every clone of `f` is charged the size of `f`'s baseline version, counted in Core `let` bindings. The size of the clone itself is not used, so the charge is known before the clone is built.
- The charges of all clones of `f` built in one package must not exceed `B`. Clones built at link are charged by Rule CM8.7.
- A closure body is a function of its own: it has its own baseline version, size and budget, and does not count toward the size of the function that creates it.
- A function whose baseline version is larger than `B` has no clones. Its callers that hold exclusively owned values then get the baseline's behaviour: reuse of a `Heap` cell after the runtime test, and no reuse of a `Frame` cell (`spec/memory_management.md` Rule PR6.3). A `fip fn` is not affected (Rule CM5.5).

`B` is one number for all the axes of Rule CM5.1. It replaces the version cap of earlier drafts (`spec/quantities.md` OQ-Q7) and the large-function rule of `spec/memory_management.md` Rule MM7.4.

The **reference value is `B = 512`**. It is provisional: it was chosen before measurement (`spec/memory_management.md` §21). With measured data (Rule CM8.6), `B` is multiplied for functions the measurements show to be hot; the multiplier is implementation-defined.

The budget is **per function**: no function competes with another for it. A change to one function can change another's clones only through the call sites it contains, that is, through the requests it makes (Rule CM5.4), never by taking budget away. That is the same stability goal as the Lipschitz bound of `spec/type_system.md` Rule 6.5.4.

Clones requested by call sites inside the package and versions exported by Rule CM6.4 are charged to the same budget.

### 5.4 The score (Rule CM5.4)

Every call site inside the package whose arguments determine a key for the callee is a **request** for that clone. The candidates for `f` are the keys requested. Each candidate has a score:

```
score(f, key) = removed(f, key) × weight(f, key) ÷ size(f)
```

- `removed(f, key)` — the number of sites in `f`'s baseline version that the key resolves: witness calls for `dup`, `drop`, `reuse_test`, `evacuate`, `copy`; calls through a capability table; accessor calls (Rule CM3.7); `rc == 1` tests that the ownership mask makes unconditional; evidence lookups and indirect operation calls that a known handler makes direct; **and** call sites in `f` at which the key lets the call request a clone of its callee or take an exclusive pointer. The last item is what gives a forwarding function its exclusive version: it removes nothing itself, but without it the function it forwards to could never be asked for one. `removed` is a count over the baseline's Core.
- `weight(f, key)` — the sum, over the call sites `s` that request the key, of `W^d(s) × heat(g)`, where `g` is the function that contains `s`, and `d(s)` is the number of `fold` / `unfold` steps (and handler clauses running inside one) that enclose `s` in `g`. The **heat** of a function carries the weight of its own call sites along call edges: `heat(g)` is the sum, over the direct call sites `t` of `g` in the package, of `W^d(t) × heat(g')` for the function `g'` that contains `t`; a function with no such call site has heat 1. Functions do not recurse, so the call graph is acyclic and `heat` is well defined. This is the notion of "hot" that Warning W110 already uses without a profile (`spec/memory_management.md` Rule MM5.5), extended across calls. The **reference value is `W = 8`**, provisional.
- `size(f)` — the size of the baseline version, as in Rule CM5.3. Within one function the divisor is the same for every candidate, so it does not change their order; it makes scores of different functions comparable, in the report (Rule CM5.8) and for the hot-function multiplier of Rule CM5.3.

Candidates are built in descending score order while the budget allows. Ties are broken by the canonical key order of Rule CM6.5. A candidate with `removed = 0` is never built.

**Rounds.** Requests come from call sites in baseline versions and in clones already chosen. The specialiser works in rounds:

1. Round 0 collects the requests of every call site in every baseline version.
2. In each round, for each function in the order of its name, the candidates not yet built are ranked and built while the function's budget lasts.
3. The call sites of the clones built in a round add their requests to the next round. A request raises the weight of a candidate that has not been built yet; a clone that has been built is never removed or re-ranked.
4. The specialiser stops after a round that builds nothing.

Budgets only shrink and clones are never removed, so this terminates, and the result depends only on the program.

Two refinements are permitted, because an implementation needs them. Ownership facts of results (`spec/quantities.md` Rule Q4.1.b) depend on which clones exist, so the specialiser may repeat the whole pass until those facts are stable; a clone chosen in an earlier pass stays chosen. A clone that no call reaches when the pass ends is not emitted and gives its charge back; the report lists it as `unused` (Rule CM5.8).

With measured data (Rule CM8.6), `weight` is the measured number of calls that requested the key, where it exists; the static weight above is the fallback. The formula and the budget do not change. Clone weights are a decision among alternatives that are all correct, so they take anything measured (`spec/properties.md` Rule P7.8).

### 5.5 Mandatory specialisations (Rule CM5.5)

These are built whenever their conditions hold. They are not charged to the budget and are built in every build mode (§5.7):

1. **Fusion and step inlining.** `spec/type_system.md` Rules 7.4.b.1 and 7.4.b.2: an `unfold` consumed by one `fold`, and a `fold` / `unfold` whose step is a lambda or a statically known function, when the data type is statically known at the call site. In a baseline version whose data type is a type parameter, the conditions do not hold and nothing is required; in a clone for a known type they hold again.
2. **`fip fn`.** A `fip fn` has one body: the version in which every part it reuses is exclusively owned (`spec/memory_management.md` Rule MM18.3). That version takes the place of the baseline version of Rule CM3.1, and its entry is the function's **exclusive entry**. Its **shared entry** is an adapter that copies the demanded part of each shared argument (`spec/quantities.md` Rule Q9.3) and then calls the body. Wherever these rules say a caller "calls the baseline version" because no clone fits, a caller of a `fip fn` calls the shared entry. The adapter is not `fip`; a caller that reaches a `fip fn` through its shared entry is not `fip` either, and the copy is reported (`spec/memory_management.md` §19) and warned in hot code (W110, Rule MM5.5).
3. **What a `fip fn` body calls.** A clone that a call site inside a `fip fn` body requests is built whatever the budget, and is not charged. Without it the body would call a baseline version, whose witness operations and count tests are reference-count operations (Rule CM4.7), and the function would fail its contract in `--no-specialise` mode or once a callee's budget is spent. This is bounded: a clone forced in this way requests further clones of its own function as an ordinary candidate, not as a forced one.

### 5.6 Beyond the budget (Rule CM5.6)

A call site whose key was not built calls the baseline version (for a `fip fn`, its shared entry). Nothing is copied at the call site.

Copying the demanded part of a shared value remains only inside the shared entry of a `fip fn` (Rule CM5.5). The rule of earlier drafts, that a caller copies once a version cap is reached, is withdrawn.

### 5.7 Build modes (Rule CM5.7)

| Mode | Clones built at package compile | At application link |
|---|---|---|
| default (`fixed compile`, `fixed ship`) | mandatory (Rule CM5.5), then by score within the budget (Rules CM5.3, CM5.4) | development build: symbols bound only (Rule CM8.2); release build: clones for the chosen types (Rule CM8.7) |
| `--no-specialise` | mandatory only | symbols bound only |
| `--no-ownership-clones` | as default, without ownership clones other than those a `fip fn` body requests | as default |
| with measured data (Rule CM8.6) | as default, with measured weights and the hot-function multiplier | as default, with measured weights |

`--no-specialise` builds faster and gives the reference behaviour that Rule CM5.2 compares clones against. `--no-ownership-clones` exists for measurement: it separates what the type axis buys from what the ownership axis buys (Rule CM5.1); the clone report (Rule CM5.8) gives "no ownership clones" as the reason for each request it refused. Every mode produces a correct program.

### 5.8 Reporting (Rule CM5.8)

`fixed compile --explain-memory` (`spec/memory_management.md` §19) lists, per function: the size of its baseline version; every clone built, with its key, `removed`, `weight`, score and charge, and whether the weight was static or measured; and every candidate not built, with the reason (budget exhausted, function larger than `B`, `removed = 0`, or `unused`: chosen, then reached by no call when the pass ended). `--format json` gives the same data.

## 6. Packages

### 6.1 Package and link unit (Rule CM6.1)

A **package** is the unit of separate compilation and of ABI: a set of source files compiled together, whose compiled form other packages link against without recompiling it. A **link unit** is the set of packages linked into one executable. Linking it is the **application link** (§8). A library is one or more packages and is not a link unit: its choice points that escape (Rule CM8.1) stay unbound until an application links it. It has no unresolved satisfaction site: every one is resolved when its package is compiled (Rule CM6.6).

Inside a package the compiler sees every body, so every call site can request clones (§5.4). Between packages a caller sees only the interface tier (§7.1), unless the callee's package ships the body (§7.3).

Packages depend on each other without cycles. Mutually recursive declarations (`spec/type_system.md` Rule 7.5, §10.1) live in one package.

### 6.2 Public surface (Rule CM6.2)

Declarations are **private** by default. `pub` makes a `fn`, `data`, `cap`, `effect`, type alias or `satisfies` declaration public. The **public surface** of a package is the set of declarations marked `pub`; only those may be named by other packages.

There is no opaque-type feature. `pub data` exposes its variants and fields. A package hides a representation by making the capability public and keeping the `data` private: values of the type then cross the package edge only as values of a choice point (Rule CM8.1).

Consequences for compilation:

- A public function has a floor entry (Rule CM6.3). A private function has none.
- The baseline version of a private function is not emitted when every call of it uses a clone (Rule CM3.1).
- The application link drops floor entries that nothing in the link unit references.
- A private data type has no accessor symbols (Rule CM3.7). Its own package still uses link-generated accessors for a field whose layout depends on a choice point bound at link.

A package with `main` and nothing marked `pub` has `main` as its only root (Rule CM3.2).

### 6.3 Floor entry (Rule CM6.3)

Every public function has a **floor entry**: one symbol, always present, with a convention that does not depend on the function's body.

- Every declared runtime parameter is passed, including one the body ignores (inferred demand `0`). Phantom type parameters and unread `of` value parameters are still not passed: they are erased by the signature, not by the body.
- Every part of every parameter is passed under demand `own`, shared.
- Every owned parameter comes with a value witness (Rule CM4.1). The callee uses it to `drop` what it does not keep and to `evacuate` what it stores beyond the call (`spec/memory_management.md` Rule MM5.5). The caller need not know the parameter's escape.
- Type parameters, effect evidence and the destination follow Rule CM4.3. Result witnesses follow Rule CM4.5.

The floor entry is the baseline version when the baseline's inferred demands are already these, and otherwise an adapter over it. The floor entry of a public `fip fn` is its copying shared entry (Rule CM5.5); its body is always exported as well, as the all-exclusive version (Rule CM6.4), so that a `fip fn` of another package can call it in place. A closure's shared pointer has the same convention as the floor entry, so a public function can be stored as a closure without a second adapter. A capability table entry follows the requirement's convention instead (Rule CM4.6), and is an adapter where the two differ.

Because the convention is fixed, a change to a body can never break a caller that uses the floor entry: turning a `read` parameter into `own`, storing a parameter that used to be only read, or starting to ignore one changes only what the adapter does.

### 6.4 Exported versions (Rule CM6.4)

A package may export further versions of a public function, each a clone in the sense of Rule CM5.1 or an entry with weaker demands (a `read` parameter that the caller keeps and does not hand over). The interface lists them with their keys and preconditions (Rule CM7.1).

- A package cannot see its importers' call sites. It builds, within the budget of Rule CM5.3 and in the score order of Rule CM5.4 with `weight = 1`, the versions it can name without them: entries with weaker demands, the classes `Frame` and `Heap` for owned parameters, and ownership masks where Rule CM5.1 allows them (for an owned parameter whose class has no count).
- Clones for a known type come from importers that hold the body (§7.3), from call sites inside the package, and from the application link (Rule CM8.7).
- A capability table entry and a closure offer two pointers only (Rule CM4.2). The exported versions beyond those two are reachable by direct calls.
- **An exported version never disappears.** If a later compilation of the package would no longer build it, its symbol remains as an alias of, or an adapter into, the floor entry. The adapter re-establishes the floor's convention from the version's precondition: it duplicates what the version's caller kept, and evacuates what the version's caller placed on the assumption that it would not escape.

### 6.5 Symbol names (Rule CM6.5)

The symbol of a version is a function of the package, the declaration's name and the **canonical key**: the type witnesses and `of` values in parameter order, then the ownership mask, then the classes, then the handlers in the canonical order of the effect row, each in a fixed textual form. The floor entry has the empty key. Canonical keys are totally ordered by that text; this is the tie-break order of Rule CM5.4.

The textual form of a type witness names the satisfactions it carries, not only the type: `Tree of Money[Ord = ByAmount]` (`spec/type_system.md` Rule 8.4.a). The **identity of a satisfaction** is the package that declares it and its name; an unnamed satisfaction is identified by its package and the pair it covers. A capability table's symbol is formed from the identity of its satisfaction. Two satisfactions of one pair in a link unit (Rule CM6.6) therefore give different tables and different clones, never one symbol for both.

Two packages that build the same clone of an imported function, under the same satisfactions, emit the same symbol, and the linker keeps one.

### 6.6 Satisfactions in a link unit (Rule CM6.6)

A link unit may hold several satisfactions of one pair (`data` type, capability with its `of` arguments). A package may declare a satisfaction for a type and a capability neither of which it declares. Coherence per link unit, which earlier drafts required, is withdrawn.

- **Resolution.** Which satisfaction a site uses is decided by `using`, innermost first, as `spec/type_system.md` Rule 8.4 specifies: at the call site, in a type, at file or package scope, the one satisfaction of the pair in scope (by `use`, by declaration in the site's package, or because it is always in scope). Otherwise it is an error. A derived satisfaction counts like a declared one, so a pair with a derived and a named satisfaction in scope is ambiguous until a `using` chooses. A private satisfaction counts only in its own package (`spec/type_system.md` Rule 8.6.a). Two satisfactions of one pair differ in results, not in speed, so the score, the lock file and declaration order never choose between them.
- **Resolved at package compile.** Every satisfaction site is resolved when its package is compiled. A site that sees (`spec/type_system.md` Rule 8.4) several satisfactions of the pair and that no `using` decides is `E061` there, in a library as in an application (`spec/type_system.md` Rule 8.4.b). The application link resolves no satisfaction (Rule CM8.4).
- **The satisfaction is part of the type.** For a bound declared on a type's own parameter (`data Tree of K is Ord`), the elaborated type records the satisfaction (`spec/type_system.md` Rule 8.4.a). This is what keeps a set built under one order from being searched under another, without a record of the satisfaction in the value. The witness record of the instance carries that satisfaction's tables (Rule CM4.2), and the symbols of its clones name it (Rule CM6.5).

Satisfactions the compiler derives (`spec/type_system.md` §7.4) or builds in (§6.6) are determined by the `data` declaration alone. Every package derives the same one, so they have one identity and the linker keeps one copy of the table (Rule CM6.5).

Each package's interface lists the satisfactions it declares, with their identities (Rule CM7.1), so the link step forms the pools of choice points (Rule CM8.4) without bodies.

### 6.7 What is an ABI change (Rule CM6.7)

Not an ABI change (importers need not be recompiled, and keep their meaning):

- any change to a function body that keeps its signature and declared contracts, including one that changes inferred demands, escapes, classes or the concrete type behind an anonymous result (Rules CM4.5, CM6.3);
- a change to a satisfying method's body or demands: the table convention depends on the requirement's signature only (Rule CM4.6);
- a change of the representation selected for a value whose type importers see only as a capability bound, including a choice point's choice at link (Rule CM8.4);
- any change to a private declaration, including the fields of a private data type whose values cross the edge only as a choice point (Rule CM6.2);
- which clones a package builds (Rule CM6.4);
- adding a declaration, or marking an existing one `pub`;
- for packages that only **use** a capability: adding a default-bodied method at its end;
- turning off the shipping of bodies (Rule CM7.3): importers then use floor entries and exported versions.

Adding a satisfaction is not an ABI change either, but it can change what an importer means when it is next compiled or linked: a second satisfaction of a pair that a site sees without `using` makes that site an error when the importer is next compiled (Rule CM6.6); at link it takes that type out of the pool of a choice point whose creating site does not decide between the two (Rule CM8.4), which can leave the choice point without a viable candidate. A satisfaction for another type adds a candidate to selection at link; that may change a choice point's choice, which shows as a diff of the lock file (Rule CM8.5) and never halts the build.

An ABI change:

- removing or renaming a public declaration, or making it private; renaming a satisfaction that importers name in `using`; changing a signature's types, effect row or `of` parameters (this includes turning `-> is C` into `-> R is C` or back, Rule CM4.5, and changing a requirement's signature, which changes its convention, Rule CM4.6);
- weakening a declared contract: removing `fip`, or loosening a cost bound (`spec/cost.md` Rule CT7.1). Tightening one is compatible;
- adding a requirement without a default body to a capability, or reordering a capability's members (the table layout of Rule CM4.2);
- for packages that **satisfy** a capability: adding any member to it, with or without a default body. The tables they compiled are one entry short;
- adding, removing or reordering a variant or field of a `pub data` type, or changing a field's type: its layout and whether it is an immediate depend on them (Rule CM3.5);
- changing a body that is in the body tier (§7.3), for importers that built clones of it into their own object code at package compile. Clones built at the application link are rebuilt from the current body (Rule CM8.7).

The interface tier is sufficient to detect each of these by comparing two versions of it.

## 7. The interface tier and the body tier

A compiled package carries two kinds of information for its importers and for the application link besides object code.

### 7.1 Interface tier (Rule CM7.1)

Always present. For every public declaration:

- its signature: types, bounds, effect row, `of` parameters; for a module-level `let` that signatures may use as a capability-generator argument (`spec/type_system.md` Rule 6.3.3), its literal value;
- the signature facts of `spec/memory_management.md` Rule MM20.2, for the floor entry and for each exported version: demand per part, escape, class-polymorphic or fixed, result destination and result witness (Rule CM4.5), stack demand, `fip` level, key and symbol;
- its declared cost contracts and their verification status (`spec/cost.md`);
- for capabilities: the member order of the capability table (Rule CM4.2), the convention of each requirement (Rule CM4.6), and every `prop` body (Rule CM7.2);
- for `data` types: variants, fields, the selected representation's witness symbol, whether the type is statically an immediate, statically a cell or decided from its argument witnesses (Rule CM3.5), its accessor symbols (Rule CM3.7), and the symbolic cardinality (`spec/reports.md` §4);
- for satisfactions: the identity (name, or package and pair) and the pair they cover (Rules CM6.5, CM6.6), the table symbol, and the status of each inherited `prop` (`spec/properties.md` Rule P7.7.a);
- a **content hash** per declaration, covering everything the declaration's meaning depends on, transitively. Verification caches key on it (`spec/properties.md` Rule P7.4).

And for the package as a whole:

- its **choice points** that the package's compilation did not decide (Rule CM8.1): the identity of each, its bound set, and the symbols its object code leaves undefined for it (Rule CM8.2);
- its **usage summaries** (Rule CM8.3): a usage vector per choice point, and per parameter of a public function a usage vector with the edges to other packages' functions;
- optionally, a **reference cost table**: measured costs of its satisfactions' members per size class and target (Rule CM8.6).

### 7.2 Props travel as typed expressions (Rule CM7.2)

`prop` bodies are part of the interface tier, not of the body tier, and are not optional. A package that declares `D satisfies C` for an imported `C` must verify `C`'s props for `D` (`spec/properties.md` Rule P7.7.a), so it needs them.

They are stored as **typed expressions**: the pure subset a prop body may use (`spec/properties.md` §4 — no effects, `forall`, `implies`, `match`, `if`, calls), with every name resolved and every node typed. The static prover reads this form directly. For property-based testing the importer lowers it to Core like any other code.

### 7.3 Body tier (Rule CM7.3)

Present by default. A package ships the **Core of the baseline version** of every function it compiles, public and private. An importer may build clones of a shipped function (§5) as if the function were its own, charged to the importer's budget for that function, and the application link builds its clones from it (Rule CM8.7).

- A switch in the package's manifest turns the body tier off. Importers then use floor entries and exported versions only, and the application link builds no clones of the package's functions.
- Shipping a body makes it part of the ABI for importers that built clones of it at package compile (Rule CM6.7).
- Default-bodied capability methods do not need the body tier to be usable: a satisfying type's table points at the compiled default (Rule CM4.2). They need it only to be specialised.

Bodies ship by default because the application link can only clone what it can read; a package that withholds them gives up specialisation across its edge, not correctness.

### 7.4 Nothing else is serialised (Rule CM7.4)

The typed tree is not part of either tier. Within one compilation the typed tree, the typer's type table (not the link-time table of Rule CM8.8) and the resolution table stay available to every later pass, and Core carries source spans (`spec/memory_management.md` §20.3), so no pass loses information by running after lowering. The compiler's own analyses (`spec/reports.md`, `spec/cost.md`) run in process on those structures. External tools read the interface tier and the compiler's reports (`spec/reports.md` §5).

## 8. Application link

A package is compiled once, to code that works for every choice of representation. Where a choice depends on how the rest of the program uses a type, it is made when the application is linked, from summaries, and bound by the linker. Nothing is recompiled when a choice changes. In a release build the specialiser then clones hot code for the choices made.

The three builds and what each decides:

| Build | Steps |
|---|---|
| Package compile | typer; static prover; quantity inference; lowering to polymorphic Core; demand and memory inference; cost checker; selection of the choice points that do not escape (Rule CM8.1); baseline code, clones (§5), interface tier, body tier, summaries |
| Application link | selection of the remaining choice points (Rule CM8.4); binding (Rule CM8.2); accessors (Rule CM3.7); the type table (Rule CM8.8); clones, in a release build (Rule CM8.7) |
| Test build | property-based testing, and measurement (`spec/properties.md` Rule P7.9) |

The application link resolves no satisfaction site: each was resolved when its package was compiled (Rule CM6.6).

A test build is a link unit of its own. It has no `main`, and it resolves no satisfaction: the sites of the package under test were resolved when it was compiled.

### 8.1 Choice points (Rule CM8.1)

An abstract type is bound at one of three times:

1. **Per use: a named type parameter.** `x: T is C`, `-> R is C`, `data D of T`. The user of the declaration picks; the witness is a hidden parameter (Rule CM4.3).
2. **Per link unit: a choice point.** One choice for the whole program, made at the application link (Rule CM8.4).
3. **Per package: a choice point whose values never leave the package**, by the escape facts of `spec/memory_management.md` §5. It is selected when the package is compiled, by the same rule. This is an optimisation of 2 and is not visible in the language.

There is no binding per value: no cell stores a witness.

A **choice point** is one of these sites:

- an anonymous bound in a result: `fn parse(s: String) -> is Optional of u64` (Rule CM4.5);
- an anonymous bound in a field: `xs: is Sequencing of Json`;
- a type variable introduced in a variant field without an `of` parameter: `data Color: RGB(red: N is Numeric, green: N, blue: N)`;
- an abstract constructor call whose type is not a named type parameter of the caller, which includes a list literal whose type no concrete expected type or ascription fixes (`spec/type_system.md` Rules 5.10, 5.11): `[1, 2, 3]` alone is a choice point bounded by `Sequencing of i64`.

A choice point in a data type gives one choice for all values of that type. A user who wants to choose per use writes the parameter: `data Color of N is Numeric`.

The **identity** of a choice point is its package, its declaration and its position in the declaration. Its **candidates** are satisfactions of its bound set by data types (`spec/type_system.md` §8). The values of a choice point carry no witness; the code that uses them refers to the choice point's symbols (Rule CM8.2).

The reason for binding per link unit: the package that declares an abstract type cannot know how the rest of the program uses it, and compiling the whole program at once does not scale.

### 8.2 Binding by the linker (Rule CM8.2)

The object code of a package refers to the witness operations, the table entries, the value witness and the accessors (Rule CM3.7) of each undecided choice point as **undefined symbols**, named from the choice point's identity. The application link defines each as an alias of the corresponding symbol of the chosen satisfaction, or emits it (accessors, and witness records that need the choice, Rule CM4.2).

- No hidden parameter carries the choice, no call goes through a pointer to reach it, and no package is recompiled when it changes.
- A choice point selected at package compile (Rule CM8.1, case 3) is bound in the package's own object code.
- "Late" means the application link. Binding at load time (shared libraries, plug-ins) is out of scope.

A development build only binds symbols. A release build also builds clones (Rule CM8.7). The build kind is chosen by the command: `fixed ship` makes a development build and `fixed release` makes a release build, so that the standard loops read `fixed verify + ship` and `fixed verify + release`. `--keep-diagnostics` and `--budget <n>` refine a release build. There is no `--release` flag. (Owner's decision, fourth round of P6.)

### 8.3 Usage summaries (Rule CM8.3)

The interface tier of a package (Rule CM7.1) carries summaries that the application link combines without reading any body:

- per choice point, a **usage vector**: which capability methods are called on its values, each with a weight; and the argument types of its abstract constructor calls, for the viability check of Rule CM8.4;
- per parameter of a public function, the same vector for what the function does with the argument, plus **edges** "passed on to `g`, parameter `i`" for the functions `g` of other packages that receive it.

A package adds vectors along its own internal edges before it writes its summaries, so the edges left in them cross package edges. Weights are static weights (`W^d × heat`, Rule CM5.4) or measured counts where they exist (Rule CM8.6).

At the application link, the link step adds vectors along the edges, from each choice point through every parameter its values reach, and gives the selector one usage vector per choice point. This is a whole-program decision without whole-program compilation, on the model of ThinLTO.

### 8.4 Selection at link (Rule CM8.4)

For each choice point the link step selects one candidate.

- **Candidates.** The satisfactions of the choice point's bound set in the link unit that count at the choice point (`spec/type_system.md` Rule 8.6.a), and the built-in candidates of `spec/type_system.md` §6.6, which count as declared in the standard library package (Rule 6.5.5). The selector chooses freely among satisfactions of different data types. Among several satisfactions of one pair it does not choose: the one that enters the pool is resolved at the site that created the choice point, by `spec/type_system.md` Rule 8.4 there. If it does not decide, that data type is not a candidate at this choice point (`spec/type_system.md` §6.5.2). The link resolves no satisfaction, and selection never meets two satisfactions of one pair.
- **Viability.** A candidate that fails a cost prop of the requirements of its capabilities is not viable (`spec/cost.md` Rule CT8.1, which also says what this check at link catches that the package compile did not). Nor is a candidate whose variant fields refine a requirement parameter (`spec/type_system.md` Rule 8.1.a) so that an argument of one of the choice point's abstract constructor calls is rejected; the argument types come from the summaries (Rule CM8.3). Zero viable candidates is a link error, which names the choice point and the candidate that came closest.
- **Score.** The score of `spec/type_system.md` Rule 6.5.4, with usage from the vector of Rule CM8.3 and cost from measurements (Rule CM8.6) where they exist, and otherwise the fallback of `spec/cost.md` Rule CT8.2.
- **Disambiguation.** The first rule that decides, in the order of `spec/type_system.md` Rule 6.5.5: a pin written by the user in the lock file, if the pinned candidate is viable; the choice recorded in the lock file, while its score is within ε of the best; the highest score; the satisfaction declared in the capability's own package; declaration order, then qualified name.

Selection never halts on a tie. The lock file gives hysteresis: a clear winner that becomes a tie neither halts the build nor flips the choice. The only selection error is zero viable candidates. Choice points selected at package compile use the same rule without the first two steps, since a package has no lock file.

Results do not depend on which candidate is selected, except through their declared props: the selection is a decision among alternatives that are all correct, so it takes anything measured (`spec/properties.md` Rule P7.8).

### 8.5 The lock file (Rule CM8.5)

The application link writes, for each choice point it selects, the identity of the choice point, the identity of the chosen satisfaction and its score, to a **lock file** that is checked in with the application.

- **Reproducible.** A build from an unchanged lock file and unchanged inputs makes the same choices. Continuous integration builds from the lock file and does not re-measure.
- **Pins.** The application may pin a choice in the lock file. A pin decides first (Rule CM8.4) while its candidate is viable; a pin whose candidate is not viable is reported, and selection continues with the next rule.
- **Diffs.** A choice that changes changes the lock file, so it shows in review as a diff.

The lock file records no satisfaction site: every site is resolved at package compile (Rule CM6.6), and the lock file never chooses between two satisfactions of one pair.

### 8.6 The profile file and measured costs (Rule CM8.6)

Decisions among alternatives that are all correct take measurements; decisions that are wrong if a claim is false never do (`spec/properties.md` Rule P7.8). In this document the first kind are selection at link (Rule CM8.4), clone weights (Rule CM5.4) and the hot-function multiplier of the budget (Rule CM5.3).

The test build measures, while it runs each prop across the satisfying types (`spec/properties.md` Rule P7.9):

- **supply side:** `steps`, `constructs`, `releases`, cycles, cache misses and branch misses per (capability method, satisfaction, size class, target), and machine facts (cache sizes, line size, page size, allocator granularity);
- **demand side:** region and request counters (`spec/reports.md` Rule RP3.3).

Measurements go to a **profile file** per target.

- Demand side, in order of priority: a production profile, then test-run usage, then the static weights of Rule CM5.4.
- Supply side: a package may ship a reference cost table in its interface tier (Rule CM7.1), and the application may re-measure on its own target, which replaces it. Without either, costs come from cost bounds (Rule CM8.4).

A measured value never enters a decision of the facts lane. A clone, a selection or a budget decided from measurements is correct whatever the measurements say (Rule CM5.2).

### 8.7 Clones at link (Rule CM8.7)

In a release build, once choice points are selected, the specialiser builds clones for the chosen types from the shipped polymorphic Core (Rule CM7.3). Requests come from the call sites in that Core whose key the choices make known, and are ranked by the score of Rule CM5.4. They are charged to the budget of Rule CM5.3 of the function cloned, counted over the link unit together with the clones its own package built. Clones are cached by (body hash, clone key), so a link rebuilds only the clones whose body or key changed.

A development build builds no clones at link; it binds symbols only (Rule CM8.2). A function whose package does not ship its body is never cloned at link.

### 8.8 Link-unique tags and the type table (Rule CM8.8)

The tag in a cell's descriptor (`spec/memory_management.md` Rule PR3.1) is unique in the link unit. It indexes a **type table**, built at the application link, with one row for every kind of cell:

| Row kind | One row per | The row holds | `copy` and `size` read | The printer reads |
|---|---|---|---|---|
| data variant | variant of a data type | type name, variant name, field kinds, size | size and field kinds from the row | type and variant names from the row; fields by their kinds |
| closure | closure body | function name and location, number of captures, capture kinds | size and capture kinds from the row | `<fn name @ file:line>` from the row |
| string | (one row) | the kind | the byte length from the cell | the text from the cell |
| flat container | flat container type and element witness (`imm1`, `imm2`, `imm4`, `imm8`, `ref`) | type name, element width and kind | the length from the cell (how its descriptor records it is `spec/memory_management.md` OQ-MM6), width and kind from the row | type name from the row; the elements by width and kind |
| blob | packed satisfier type | type name | the byte size from the blob (Rule MM16.1); a blob is copied as plain bytes | type name from the row and the byte size |

A field kind is a reference, an immediate of a width, or a slot of parameter type whose kind the cell's reference map gives.

- A `match` on a cell subtracts the type's base, a link-time constant, from the tag to obtain the variant's index.
- The witness operation `copy` takes the size and field kinds of a cell from its row (Rule CM4.1).
- The diagnostic sinks print a cell reached through generic code from its row (`spec/diagnostics.md` Rule DG3.6), so generic baseline code can name what it prints without a `Show` bound. An immediate carries no table index; how it prints is `spec/diagnostics.md` Rule DG3.4.

A release build strips `inspect` and `trace` unless a flag keeps them; the table is still built for `match` and `copy`. The layout of the table is provisional (OQ-CM13).

## 9. Open questions

- ~~**OQ-CM1 — Visibility and body-tier syntax.**~~ Closed in v0.3 by P6 D13: declarations are private unless marked `pub` (Rule CM6.2), and bodies ship by default with a manifest switch to turn them off (Rule CM7.3).
- **OQ-CM2 — Encoding.** The on-disk form of the two tiers, the lock file and the profile file, their versioning, and how the content hash is canonicalised. Resolves `spec/memory_management.md` OQ-MM5 when settled.
- **OQ-CM3 — Values of `B` and `W`.** Reference values 512 and 8 are guesses. To be measured with the hypotheses of `spec/memory_management.md` §21.
- ~~**OQ-CM4 — Type variables of `data` fields.**~~ Closed in v0.3 by P6 D3: such a variable, and an anonymous bound in a field, is a choice point with one choice for all values of the type, so no cell stores its witness (Rule CM8.1).
- ~~**OQ-CM5 — Orphan satisfactions.**~~ Closed in v0.3 by P6 D12: a package may declare a satisfaction for a type and a capability it does not declare, and several satisfactions of one pair are resolved by `using` (Rule CM6.6).
- **OQ-CM6 — Clones across packages and the budget.** Two importers each charge their own budget for the same clone, and the linker merges the result (Rule CM6.5). Rule CM8.7 counts link-time clones over the link unit; whether clones built by importers at package compile should be deduplicated in the same count is open.
- **OQ-CM7 — Wider immediates.** Whether a value wider than 64 bits that holds no reference (a 128-bit integer, a small tuple, a data type over 8 bytes) should travel in two slots in baseline code instead of behind a pointer. `i128` and `u128` are out of the language until this is built and measured (owner's decision, fourth round of P6; `spec/stdlib.md` §4.9).
- ~~**OQ-CM8 — Borrowing and exclusivity through a table.**~~ Closed in v0.3 by P6 D9: the convention is derived from the requirement's signature, so table calls borrow `read` parameters, and the exclusive pointer covers only `own` parameters of type `Self` (Rule CM4.6).
- **OQ-CM9 — Evidence across packages.** Rule CM4.3 fixes the order of an evidence vector by the canonical order of the effect row. Row variables, and the library entry points of `spec/effects.md` Rule E5.4, need the same treatment.
- ~~**OQ-CM10 — The weight is lexical.**~~ Closed in v0.3 by P6 D15: the static weight carries the heat of a function's own call sites along call edges, and measured request counts replace it where they exist (Rule CM5.4).
- ~~**OQ-CM11 — Order of the axes.**~~ Closed in v0.3 by P6 D15: ownership clones are requested only where a run-time test cannot stand in (Rule CM5.1), so they rarely compete with type clones for the budget.
- ~~**OQ-CM12 — Tables per instance.**~~ Closed in v0.3 by P6 D15: witness records are built on the stack in baseline code and are static where the instance is known at package compile or at link (Rule CM4.2).
- **OQ-CM13 — Layout of immediates and of the type table.** The bit layout of Rule CM3.5 (tag in the low byte, packed fields in declaration order) and the layout of the type table of Rule CM8.8 are provisional, to be confirmed by the implementation.
- **OQ-CM14 — The keyword `named`.** Closed by the owner (fourth round of P6): the word is `as`, `Money satisfies Ord as ByAmount:` (`spec/type_system.md` Rule 8.4). Rule CM6.5 needs only that a satisfaction can have a name.
- **OQ-CM15 — The 8-byte header.** A cell header of 8 bytes with a 32-bit count, as in Koka and Lean, is a hypothesis to measure, not a decision (`spec/memory_management.md` Rule PR3.1).
- **OQ-CM16 — The measurement behind ownership clones.** Rule CM5.1 requests ownership clones only where a run-time test cannot stand in, because the test measured as fast as unconditional reuse. The owner suspected a compiler fault behind that measurement. Measured again (`docs/baselines/runtime-2026-11-23-debts.md` §1): no fault; the test costs exactly 4 instructions and about 2 to 3 cycles per reuse in the shipped build (up to about 4% of a benchmark's run time), and at most about 2 cycles with the runtime's fast paths inlined.
- **OQ-CM17 — Unexplained cost.** About 4% on the `list_pipeline` and `records` benchmarks against `main` is unexplained. `fx_drop` still recurses on children other than the last (`spec/memory_management.md` Rule PR7.3 requires a loop).
- **OQ-CM18 — Freezing the table convention.** Rule CM4.6 is provisional until a benchmark with capability-bounded parameters measures it against passing every argument under `own`.

## 10. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | §6.5 selects representations and never clones; Rule 6.5.5 is the disambiguation order of Rule CM8.4; §7.4.b.1–2 are the mandatory specialisations of Rule CM5.5; Rule 8.4 resolves satisfactions by `using` (Rule CM6.6); Rule 8.4.a records the satisfaction in the type (Rules CM6.5, CM6.6); §8.5 constructor resolution, settled for results by Rule CM4.5 |
| `spec/quantities.md` | Demands are inferred on baseline Core and again on each clone; Rule Q9.3 defines the ownership axis of a key; Rule CM4.6 gives the convention where no demand can be inferred |
| `spec/memory_management.md` | Rule MM7.3 is the value witness; Rule PR3.1 is the cell layout, whose tag Rule CM8.8 makes link-unique; Rule PR5.6 is the two-pointer rule; §18 `fip`; Rule MM20.2 signature facts are the core of the interface tier; §19 carries the report of Rule CM5.8 |
| `spec/effects.md` | Evidence vectors are the hidden parameters of Rule CM4.3(4); Rule E7.8 makes a closure capture its evidence; Rule E7.7 keeps `own` for operation arguments |
| `spec/properties.md` | Rule P7.4 keys on the content hash; Rule P7.7.a needs Rule CM7.2; Rule P5.3.c uses Rule CM5.2; Rule P7.8 separates the facts lane from the measurement lane; Rule P7.9 produces the measurements of Rule CM8.6 |
| `spec/cost.md` | Contracts in the interface tier; weakening one is an ABI change; Rule CT8.1 is viability at link; Rule CT8.3 is the cost fallback of Rule CM8.4 |
| `spec/diagnostics.md` | Rules DG3.1–DG3.6: the diagnostic sinks; Rule DG3.6 prints through the type table of Rule CM8.8 |
| `spec/reports.md` | Coverage, cardinality and the tool interface built on §7; Rule RP3.3 counters are the demand side of Rule CM8.6 |
| `docs/decisions/P1`–`P6` | The decisions and the alternatives rejected; P6 is the source of v0.3 |
