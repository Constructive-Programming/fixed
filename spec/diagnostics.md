# Fixed Diagnostics: `inspect`, `trace` and Printing Values

**Status:** Draft v0.1.1
**Specifies:** the diagnostic channel, the two prelude declarations that write to it (`inspect`, `trace`), interpolation of values into a diagnostic message, what a printed value looks like, how build modes treat diagnostics, and where the printer gets the names it prints.
**Last revised:** 2026-11-20

**Changes from v0.1.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): `inspect` and `trace` are declared in the prelude, and a user declaration of the same name shadows them (Rule DG3.1); the sinks are `inspect`, `trace` and the message of `unreachable`, and interpolation exists only in a sink's message, as a name or field path (Rule DG3.2); a use in `inspect` or `trace` is an ordinary use where diagnostics are kept, and the compile error for a diagnostic use after the last use is withdrawn (Rule DG3.5); the test build keeps diagnostics (OQ-DG3 closed); an immediate of a choice point known at the site prints with the chosen type (Rule DG3.4); OQ-DG4 closed.

**Why this document exists.** A programmer debugging generic code needs to see a value whose type is a parameter with no bound. Fixed cannot give that value a universal `show`: `show` on an unbounded type parameter is a type error (`docs/decisions/P6-resolution-round-2026-11-20.md` D10), because a universal `show` would let a program observe the representation the selector chose. Diagnostics resolve the conflict by being a **sink**: they print any value, and nothing they print comes back into the program. The decision is recorded in P6, D11.

## 1. Scope

This document specifies:

- `inspect(x)` and `trace("…")` (§3, Rules DG3.1 and DG3.2);
- the diagnostic sinks, and interpolation in their messages (Rule DG3.2);
- why diagnostics are not observable, and what order of output is guaranteed (Rule DG3.3);
- the printed form of a value (Rule DG3.4);
- release and development builds (Rule DG3.5);
- the type table that lets generic code name what it prints (Rule DG3.6);
- why `Show` is not the mechanism (§4).

It does **not** specify:

- The device behind the diagnostic channel. It is the standard error stream of a hosted program unless the runtime says otherwise; that is implementation-defined.
- Logging as program behaviour. A program that must write a log writes it through an effect (`spec/effects.md`), with a value it formats itself through `Show`.
- The surface grammar. `inspect` and `trace` are applications, and interpolation is a form of string literal; the productions belong in `spec/syntax_grammar.ebnf`.

## 2. Dependencies

- `spec/type_system.md` — §5.6 (`!` and `unreachable`), Rule 8.4 (satisfactions and `using`).
- `spec/effects.md` — rows, Rule E5.5.b, Rule E7.8.
- `spec/properties.md` — Rule P8.4 (the failure report of a prop), Rule P7.8 (the facts lane).
- `spec/compilation_model.md` — Rule CM3.5 (immediates), Rule CM8.1 (choice points), Rule CM8.8 (link-unique tags and the type table).
- `spec/memory_management.md` — Rule PR3.1 (the cell descriptor).
- `spec/cost.md` — Rule CT3.5 (what a contract does not count).
- `spec/stdlib.md` — §5 (the prelude declares `inspect` and `trace`).
- `spec/quantities.md` — §4 (how uses are counted).

## 3. Rules

### 3.1 `inspect` (Rule DG3.1)

```
inspect(x)
```

`inspect` takes one value of any type, with no bound on the type, writes a description of it to the **diagnostic channel** (Rule DG3.4), and returns `x` unchanged. Its type is that of `x`.

`inspect` is declared in the prelude (`spec/stdlib.md` §5), and so is `trace`. Being a sink is a property of those two declarations: the compiler supplies their bodies, which cannot be written in Fixed, because a Fixed function of type `T -> T` with `T` unbounded cannot look at its argument. A program that declares its own `inspect` or `trace` shadows the name and gets an ordinary function (`spec/syntax_grammar.ebnf` §13). `inspect` may stand wherever its argument could:

```
let total = inspect(xs).fold(0, (acc, x) -> acc + x)
```

`inspect` passes ownership through: `inspect(x)` uses `x` exactly as `x` would at that position.

### 3.2 `trace` and interpolation (Rule DG3.2)

```
trace("balance = {b}, acct = {a}")
```

`trace` takes one string literal, which may interpolate names, writes the resulting text to the diagnostic channel, and returns `()`.

The **diagnostic sinks** are:

1. `inspect`, whose argument is a value (Rule DG3.1);
2. `trace`, whose argument is a message;
3. the optional message of `unreachable` (`unreachable("…")`, `spec/type_system.md` §5.6), printed when it is reached.

There is no `panic` construct and so no panic message. The failure report of a prop is not a sink and not a string literal: the test driver prints it with the printer of Rule DG3.4 (`spec/properties.md` Rule P8.4).

**Interpolation.** Interpolation exists only inside the message of a sink, and only as a name or field path, `{b}` or `{acct.owner.name}` (`spec/syntax_grammar.ebnf`, the note at `STRING_LITERAL`). There is no general string interpolation: in every other string literal `{` and `}` are ordinary characters. An interpolated path is printed by Rule DG3.4 whatever its type, bounded or not, so the text of a sink does not depend on which `Show` satisfaction is in scope. A program that wants its `Show` text in a diagnostic binds `let s = e.show()` and interpolates `{s}`.

A sink is the only context in which text about an unbounded value exists, and it is a context whose text the program cannot read (Rule DG3.3). That is what keeps it from breaking parametricity.

### 3.3 Not observable (Rule DG3.3)

- The text written by `inspect`, `trace` or a sink never becomes a `String` the program holds. No built-in reads the diagnostic channel.
- `inspect` and `trace` are not effects. They do not appear in rows, need no handler, and are allowed in a pure function, in a prop body, and in the body of a `fip fn`.
- A program's result therefore cannot depend on them. Two consequences hold because of this rule and only because of it: **parametricity** (a function of type `T -> T` with `T` unbounded still has one implementation), and the **independence of results from selection** (the representation the selector chose, which a printed value shows, cannot change what the program computes).

**Order.** Diagnostic output follows evaluation order where the code runs as written. Code the compiler removes (dead code, an erased value, a discharged `unreachable`), fuses (`spec/type_system.md` Rules 7.4.b.1 and 7.4.b.2) or runs in parallel lanes (`spec/quantities.md` fork sites) may print in a different order or not at all; code the test build evaluates twice (`spec/properties.md` Rule P5.3.c) prints twice. A diagnostic does not keep code alive: a `trace` in a branch the optimiser deletes is deleted with it. No program may rely on diagnostic output for its behaviour; a test that compares diagnostic output is comparing something unspecified.

Diagnostics are not steps and build no cell that a contract counts (`spec/cost.md` Rule CT3.5): formatting writes to the channel directly and allocates nothing the program sees. A `fip fn` that contains a `trace` is checked as if the `trace` were absent.

### 3.4 What is printed (Rule DG3.4)

A value is printed **as represented**:

| Value | Printed as |
|---|---|
| A data value | its variant name and its fields in declaration order, each printed by this rule: `Cons(1, Cons(2, Nil))`, `Point(x: 3, y: 4)` |
| A primitive | its literal form: `42`, `-1.5`, `true`, `'a'`, `"text"` |
| A function or closure | `<fn name @ file:line>`, with the name of the declaration or `lambda` for an anonymous function, and the location of its definition |
| A value of a choice point (Rule CM8.1) | the value as above, preceded by the concrete type the link step chose: `Vec: [1, 2, 3]` for a choice point bound to `Vec` |
| An immediate (Rule CM3.5) reached through generic code | a raw number: the 64-bit slot, in hexadecimal |

Where the compiler knows the type of an immediate at the print site, it prints the value by its type, not as a raw number. For an immediate that is a value of a choice point, that holds where the choice point is known at the site: the printer names the chosen type. A generic function does not know, and an immediate carries no descriptor (Rule DG3.6), so through generic code the raw slot is all it has.

Names are the declared names, qualified only where two printed types share a name. Fields whose type is a type parameter are printed by the same rule, through the type table (Rule DG3.6).

### 3.5 Build modes (Rule DG3.5)

- A **release build** strips every `inspect` and `trace`: `inspect(e)` becomes `e`, and `trace(…)` becomes `()`. A flag (`--keep-diagnostics`) keeps them.
- A **development build** and a **test build** keep them.
- The message of `unreachable` and the failure reports of props are kept in every mode; they are printed only when the program is already failing.

In a build that keeps diagnostics, a use in `inspect` or `trace` is an ordinary use: it counts for quantity, and its demand is `read` (`spec/quantities.md` §4). A value used only there is computed and printed. In a build that strips diagnostics they are removed before quantity inference, so such a value may be erased, and reuse may differ between the two builds. Results are identical in both builds (Rule DG3.3).

### 3.6 Where the names come from (Rule DG3.6)

- **Where the type is known** at the print site, the compiler generates the printer for it, as it generates any derived member.
- **In generic baseline code** the type is a parameter and the printer reads the value:
  - A cell is self-describing (`spec/memory_management.md` Rule PR3.1). The tag in its descriptor is unique in the link unit and indexes the **type table** of `spec/compilation_model.md` Rule CM8.8, whose row for each kind of cell (data variant, closure, string, flat container, blob) says what the printer reads. For a data variant that is the type name, the variant name and the kind of each field; the printer walks the fields with it and recurses through references.
  - An immediate has no descriptor, so generic code cannot name it (Rule DG3.4). An `Inline` value that is not an immediate reaches generic code only as a copied-out cell (`spec/memory_management.md` Rule MM3.1).
  - A function value is a closure cell; its row gives `name @ file:line`.
  - A value of a choice point is printed with the type the link step chose; the type table entry of its cell gives the name.

The table is built at link, the time when the tags are assigned (Rule CM8.8). A release build that strips diagnostics still needs the table for the witness operation `copy` (P6, D11); the location table of functions may be dropped with the diagnostics.

## 4. Why `Show` is not used

- `Show` is a **lawful capability whose output is a program value**. Its `String` can be compared, stored and branched on. Printing through `Show` would put text about a value back into the program, which is exactly what Rule DG3.3 forbids for values of unbounded type.
- An unbounded type parameter has **no `Show`** (P6, D10). A diagnostic built on `Show` could not print the values generic code is debugged for.
- A **derived default** `Show` for every type would make representation selection observable: the text of a value differs between a list and a vector, and a program could branch on it. Results would then depend on a choice the language promises does not change them.

`Show` therefore stays the capability for text a program uses, and diagnostics are a separate sink for text only a person reads.

## 5. Open questions

- **OQ-DG1 — Structured output for tools.** Rule DG3.4 specifies text. A tool may want each record as JSON (location, value tree, chosen types). The format and a flag (`--diagnostics-format json`) are not specified.
- **OQ-DG2 — Depth and size limits.** A long list or a deep tree prints without end in practice. A default limit on depth and on elements, with an elision mark, and a flag to change it, are not specified.
- ~~**OQ-DG3 — `inspect` in a `prop` body.**~~ Closed in v0.1.1: the test build keeps diagnostics (Rule DG3.5). The static prover reads `inspect(e)` as `e` and `trace(…)` as `()`.
- ~~**OQ-DG4 — Grammar.**~~ Closed in v0.1.1: interpolation in a sink's message and the message of `unreachable` are in `spec/syntax_grammar.ebnf` v0.4.12; there is no panic form.

## 6. Cross-references

| Document | Relationship |
|---|---|
| `spec/type_system.md` | §5.6 `unreachable`, whose message is a sink; D10's type errors for `show`, `==`, `clone` without a bound |
| `spec/effects.md` | Diagnostics are not effects and do not appear in rows |
| `spec/properties.md` | Rule P8.4 prints counterexamples by Rule DG3.4; the static prover ignores diagnostics |
| `spec/compilation_model.md` | Rule CM3.5 immediates; Rule CM8.1 choice points; Rule CM8.8 tags and the type table |
| `spec/memory_management.md` | Rule PR3.1 the descriptor the printer reads |
| `spec/cost.md` | Rule CT3.5: diagnostics are not steps and build no counted cell |
| `spec/syntax_grammar.ebnf` | Interpolation in a sink's message (note at `STRING_LITERAL`); the message of `unreachable` (`AtomExpr`) |
| `spec/stdlib.md` | §5: the prelude declares `inspect` and `trace` |
| `docs/decisions/P6-resolution-round-2026-11-20.md` | D10 and D11 |
