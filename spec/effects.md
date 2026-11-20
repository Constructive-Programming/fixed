# Fixed Effects

**Status:** Draft v0.3.1
**Specifies:** algebraic effect declarations, effect-row inference, handler semantics, and evidence-passing compilation for Fixed v0.4.4 source.
**Last revised:** 2026-11-20

**Changes from v0.3.** Applies the rulings of the consistency review of 2026-11-20 (amendments in P6): every evidence entry, passed or captured, has one layout that holds the handler's marker, through which the frame is found (§7.1, Rule E7.2); Rule E7.7 no longer says a table call passes every argument `own` (it follows `spec/compilation_model.md` Rule CM4.6); Rule E5.5.b cites the containment test of Rule CM4.6; wrong references to a diagnostics section and to a rule number for §7.6 corrected.

**Changes from v0.2.3.** Applies decision P6 (`docs/decisions/P6-resolution-round-2026-11-20.md`, D11, D14). A closure captures the evidence of the effects that its type does not mention, at the point where it is given that type; it captures the handler's marker, not a stack address (new Rule E7.8). A closure that captured evidence must not outlive the handler (new Rule E5.5.b, `E085`); the prose caveat of Rule E5.5.a about callbacks stored and invoked later is replaced by these two rules. `inspect` and `trace` are not effects and never appear in a row (new §4.6). Mechanical repairs from the review of 2026-11-20 (`docs/decisions/P0-spec-review-2026-11-20.md` §3): Rule E4.5.b no longer shows an alias application that does not parse; §4.2 says package instead of a `module` construct that does not exist; the unused declared effect is `warning[E081]`, listed as a warning; the cross-references no longer say prop bodies are "checked at quantity 0". §5.5 and §8.3 no longer say that a receiving function must declare its callback's effects. New OQ-E9 (parameterised effect aliases) and OQ-E10 (a handler clause cannot name an effect's `of` argument).

**Changes from v0.2.2.** §7 aligned with `spec/compilation_model.md`: operation arguments and results are 64-bit slots (§7.1); evidence is passed in every baseline version, and a known handler is one component of a clone key, not a consequence of monomorphisation (§7.6); new Rule E7.7 fixes what an operation call hands over and how an evidence vector is ordered.

**Changes from v0.2.1.** Adds Rule E5.5.a (ambient pass-through): a callback whose expected function type declares no `with` clause passes its effects to the enclosing body. Adds Rule E8.1.a (how row variables are solved) and OQ-E8 (row-polymorphic data).

**Changes from v0.2.** Re-opens OQ-E5: §3.5 (linear effects) is now marked **Proposal**, contingent on the resolution of OQ-Q5 in `spec/quantities.md` (whether effect rows participate in QTT quantity). The proposed semantics in §3.5 are unchanged in shape but no longer normative until OQ-Q5 is closed. No other semantic changes from v0.2; bump tracks the v0.4.4 source grammar.

**Changes from v0.1.** Resolved OQ-E6 (effect aliasing → §4.5). Removed Rule E6.5 (wildcard handler clauses) per OQ-E2 decision. OQ-E1, OQ-E3, OQ-E4 marked as deferred decisions.

## 1. Scope

This document specifies Fixed's algebraic effect system end-to-end: declarations, the type-level effect-row machinery, the inference algorithm, the runtime semantics of handlers, and how all of the above is compiled to evidence vectors. It covers:

- How `effect E: fn op(...) -> T` declarations are typed (§3).
- What an **effect row** is and how `+` composes them (§4).
- The inference algorithm that derives a function's `with` clause from its body (§5).
- The operational semantics of `handle SUBJECT: clauses` (§6).
- The runtime mechanism — explicit evidence vectors, indirect-call dispatch, continuation capture (§7).
- How effect rows participate in polymorphism (§8).

It does **not** specify:

- The base type system or capability machinery — see `spec/type_system.md`.
- Resume's quantity (single-shot vs multi-shot) beyond a brief recap — see `spec/quantities.md` Rule Q5.11.
- Memory of handler-captured continuations, abort resets and fiber stacks — see `spec/memory_management.md` §14.
- How `!` (the never type) is typed beyond the effect-relevant rules — see `spec/type_system.md` §5.6.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.4 — `EffectDecl`, `EffectMember`, `WithClause`, `EffectRow`, `EffectBound`, `HandleExpr`, `HandlerClause`, `resume` keyword.
- `spec/type_system.md` v0.4.x — Rule 4.1 (typevar introduction), §5.6 (`!` type), §9 (brief reference forwarding to this doc).
- `spec/quantities.md` v0.6 — Rule Q5.11 (resume single-shot), OQ-Q5 (open: effect-row × quantity interaction).
- `spec/compilation_model.md` — evidence as a hidden parameter (Rule CM4.3), clone keys (Rule CM5.1).
- `spec/memory_management.md` v0.5 — Rule MM4.1 (regions), §14 (handler memory), §20.1 (escape sets).
- `spec/diagnostics.md` — `inspect` and `trace`, which are not effects (Rule E4.6).
- Plotkin & Pretnar, *"Handlers of Algebraic Effects"*, ESOP 2009. The model.
- Leijen, *"Type Directed Compilation of Row-Typed Algebraic Effects"*, POPL 2017. The evidence-passing compilation strategy Fixed adopts.
- Xie & Leijen, *"Generalized Evidence Passing for Effect Handlers"*, ICFP 2021. Refinements used in Koka, the closest production reference.

## 3. Effect declarations

### 3.1 Syntactic form (recap)

```
effect E [of OfArgs]:
    fn op1(args) -> T1 [with EffectRow]
    fn op2(args) -> T2
    ...
```

Per the grammar, `EffectMember` has the same shape as `InstanceMethodDecl` (since v0.4): each operation may carry `TypeParamsHint?`, `FnParamList?`, a return type via `->`, and an optional `WithClause?`. Effect members are abstract — they declare what operations exist; the *handler* supplies the actual implementation.

### 3.2 Operation typing (Rule E3.2)

Each operation declared inside `effect E:` is treated as a method on the effect type. A call site `E.op(args)`:

1. Type-checks the arguments against the operation's parameter types.
2. Adds `E` (with its `of` arguments resolved against the call-site context) to the caller's inferred effect row (§5.1).
3. The expression's type is the operation's declared return type.

```
effect Console:
    fn print_line(msg: String) -> ()
    fn read_line -> String

// Console.print_line("hi")     : ()      with Console
// Console.read_line             : String  with Console
```

### 3.3 The `!` return type and abortive operations (Rule E3.3)

An operation declared with return type `!` (the never type) is **abortive**: it transfers control to its handler clause and never returns to the call site.

```
effect Fail of E:
    fn fail(error: E) -> !
```

A handler clause for an abortive operation **must not** call `resume` — there is no continuation to resume. The clause body's value becomes the result of the surrounding `handle` block. The typer enforces this:

- Inside the clause body for an abortive operation, `resume` is a compile error.
- The clause body's value type must match the `handle` block's expected type.

This is what makes `Fail` ergonomically usable as exceptions: throwing transfers control directly; the handler returns a final value without re-entering the failing path.

### 3.4 Parametrized effects

Effects may carry `of` type arguments (per `CapOfParams` in the grammar):

```
effect Fail of E:
    fn fail(error: E) -> !

effect Channel of A:
    fn send(value: A) -> ()
    fn receive -> A
```

`Fail of String` and `Fail of i64` are **distinct effects** for inference and handling purposes. Identity is by name × `of` arguments (§4.2).

### 3.5 Linear effects (Rule E3.5) — **Proposal, contingent on OQ-Q5**

> **Status note.** This section describes the *proposed* semantics for `linear effect`. Whether an effect row carries a QTT quantity at all (and therefore whether "quantity 1 in the row" is a meaningful claim) is the subject of OQ-Q5 in `spec/quantities.md`, which is open. The rules below stand as the design Fixed expects to adopt once OQ-Q5 is resolved; they are not yet normative. Implementations should treat §3.5 as an opt-in feature behind a flag until OQ-Q5 closes.

An effect declared with the `linear` modifier is a **linear effect** — its operations are subject to a usage constraint enforced by the typer:

```
linear effect FileHandle:
    fn read_all -> Bytes

linear effect Lock:
    fn release -> ()
```

A linear effect's row entry carries quantity 1 (per `spec/quantities.md` §3 semiring). In any function body where a linear effect E appears in the inferred row, the total usage of E across all reachable code paths is constrained to ≤ 1, computed using QTT's join (`⊔`, Rule Q5.9) across mutually exclusive branches and sum (`+`, Rule Q5.10) across sequenced expressions.

**Rule E3.5.a (Linearity check).** For each linear effect E in a function's inferred row (after handler subtraction per Rule E5.3), the typer verifies:

- Sequential ops on E in the same code path → usage `1 + 1 = ω` → **error[E083]: linear effect used multiple times in <fn>** with location of each call.
- Ops on E in mutually exclusive branches → usage `1 ⊔ 1 = 1` → OK.
- A function call whose body uses E propagates that callee's usage; if the caller is already at usage 1 for E, the call is rejected.

**Rule E3.5.b (Handler scoping).** Each `handle` block for a linear effect installs **one** usage permission for E within its SUBJECT. Nested handlers provide fresh permissions. After a `handle` block subtracts E from its scope (Rule E5.3), E is no longer in the row and the linearity counter resets for any outer scope that re-installs E.

**Rule E3.5.c (Semantics — at-most-once).** Fixed's `linear effect` is **at-most-once** in the standard linear-typing sense (technically *affine* — zero usage is also allowed). A function whose inferred row contains a linear effect E but whose body never calls any operation of E is valid (usage 0). This matches the common pattern where a linear capability is acquired but conditionally not exercised.

**Use case.** Linear effects encode resource lifecycles where double-use is a bug. For multi-step lifecycles (acquire + use* + release), the canonical approach is to **split each linear operation into its own `linear effect`**: each effect carries its own usage budget, tracked independently. This is intentional — keeping linearity at the *effect* granularity (not per-op within a multi-op effect) preserves the simplicity of Rule E3.5 and the QTT-row semantics of §4.1. Examples:

```
// One-shot lock release:
linear effect Lock:
    fn release -> ()

// Resource handle that must be closed exactly along one path:
linear effect Handle of R:
    fn close(resource: R) -> ()
```

**Compatibility.** A non-linear handler does not satisfy a linear-effect contract; declaring `linear effect E` requires every handler that handles E to be a `handle` block (handlers do not need to be marked `linear` — linearity is a property of the effect, not the handler).

**Exclusive captures across linear clause bodies (resolves L6).** Because a linear effect's clause body runs at most once per `handle` block, the clause body itself has quantity 1 — see `spec/quantities.md` Rule Q7.4. An exclusively owned outer value captured into a linear-effect clause body therefore stays exclusive:

```
let lock_token = make_token()        // quantity 1
handle (do: ... Lock.release):
    Lock.release =>
        consume(lock_token)          // exclusive: clause body is quantity 1, see Rule Q7.4
        resume(())
```

The same capture into a *non-linear* clause body (e.g. `Console.print_line`) is downgraded to `own`: the value is shared from then on (`spec/quantities.md` Rule Q5.7). It is not an error.

## 4. Effect rows

An **effect row** is an unordered, duplicate-free **set** of `EffectBound`s, written `with E1 + E2 + ...`.

### 4.1 Composition with `+` (Rule E4.1)

`+` on effect rows is set union: associative, commutative, idempotent.

```
with Console + Fail of String                    ≡  with Fail of String + Console
with Console + Console                            ≡  with Console
with Console + (Fail of String + Console)         ≡  with Console + Fail of String
```

### 4.2 Effect identity (Rule E4.2)

Two effect bounds `E of (X1, ..., Xn)` and `E of (Y1, ..., Yn)` are the **same effect** iff:

1. They share the same effect-name symbol (resolved per `use` imports).
2. Their `of` arguments are equal as types (per type_system.md's type equality, which is structural for primitives and nominal for declared `effect`/`cap`/`data`).

`Fail of String` and `Fail of i64` are distinct. A `Fail` declared in package `a` and a `Fail` declared separately in package `b` give two distinct `Fail of String` effects — effect identity is nominal, not structural.

### 4.3 The empty row

The empty row `with `(written by omitting the `with` clause entirely) is the identity for `+`. A function with no `with` clause is **pure** — it cannot call any effect operation directly.

### 4.4 Effect-row subtyping (Rule E4.4)

`E_1 ≤ E_2` (E_1 is a sub-row of E_2) iff `E_1 ⊆ E_2` as sets of effect bounds. A function expected to perform `with E_2` may be supplied a function with `with E_1` if `E_1 ≤ E_2` — the caller "promises more than is delivered," which is safe.

Combined with §3.3: `with !` (a row containing `!` alone, conceptually) is a sub-row of every other row, since `!` indicates the function does not return.

### 4.5 Effect aliasing (Rule E4.5)

The `type` syntax (per `spec/syntax_grammar.ebnf` §7) extends to effect rows. The typer determines whether a given `type X = R` is a **cap alias** or an **effect-row alias** by inspecting `R`:

- If every `+` chain element resolves to an `effect` declaration → effect-row alias, usable in `with` clauses.
- If every element resolves to a `cap` → cap alias, usable in `is` bounds.
- Mixed kinds are a compile error: `error[E084]: type alias mixes capabilities and effects`.

**Rule E4.5.a (Effect-alias expansion).** An effect alias `type X = E1 + E2 + ...` is expanded at every use site to its constituent effects:

```
effect Console:
    fn print_line(msg: String) -> ()
effect FileSystem:
    fn read_file(path: String) -> is Result of (String, String)
effect Clock:
    fn now -> u64

type IO = Console + FileSystem + Clock

fn run() -> () with IO = ...
// equivalent to:
// fn run() -> () with Console + FileSystem + Clock = ...
```

**Rule E4.5.b (No parameterised effect aliases).** An effect alias takes no type parameter. The grammar's `TypeAliasParams` are value parameters: `type AppEffect(E) = …` parses, but `E` is a value, not a type. An `EffectBound` in a row takes arguments only through `of`, so the use `with AppEffect(String)` does not parse. A row with a parameterised effect is written out:

```
type AppEffect = Console + Log

fn run() -> () with AppEffect + Fail of String = ...
// expands to: with Console + Log + Fail of String
```

Whether effect aliases should take type parameters (for example `type AppEffect of E = Console + Fail of E + Log`, used as `with AppEffect of String`) is open (OQ-E9).

**Rule E4.5.c (Aliases in handlers).** When a `handle` block subtracts effects from a row containing an alias, the alias is first expanded and then individual effects are subtracted per Rule E5.3:

```
type IO = Console + FileSystem

fn run() -> () with IO = ...

handle run():
    Console.print_line(s) => ...
    FileSystem.read_file(p) => ...
    return(v) => v
// SUBJECT row expands to Console + FileSystem; clauses cover both → outer row is empty
```

**Rule E4.5.d (Composition).** Aliases compose freely:

```
type IO = Console + FileSystem
type RuntimeEffects = IO + Clock + Log
// expanded RuntimeEffects: Console + FileSystem + Clock + Log
```

A linear effect appearing inside an alias retains its linearity (Rule E3.5) when the alias is expanded — `type IO = Console + FileSystem` containing a linear `FileSystem` would propagate the linearity check to every use site of `IO`.

**Rule E4.5.e (No `extends` in effect aliases) — resolves X4.** Effect aliases are **pure aliases** — textual expansion to a `+`-combined row of effects. The `extends` keyword is **not** valid in an `EffectAliasDecl`; an alias declaration of the form

```
type X extends Y = E1 + E2     // ❌ compile error
```

is rejected by the parser (and would have no useful semantics since aliases are not nominal types). Composition of effect rows is achieved by the `+` operator inside the RHS, not by inheritance:

```
type X = Y + E1 + E2           // ✅ correct — Y is expanded, then E1 + E2 added
```

The corresponding question for `effect` declarations (whether `effect MyConsole extends Console: ...` is allowed) remains deferred per OQ-E3.

### 4.6 Diagnostic sinks are not effects (Rule E4.6)

`inspect(x)` and `trace("…")` write to the diagnostic channel (`spec/diagnostics.md`). They are not effects: they do not appear in any row, a pure function may call them, and no `handle` can intercept them. The text they produce never becomes a value the program holds, so a program's result cannot depend on them; that is what lets them stay out of rows without breaking parametricity or the independence of results from selection. A release build strips them unless a flag keeps them.

## 5. Effect inference

### 5.1 Inference algorithm (Rule E5.1)

For each function definition `fn f(args) -> T with R = body`, the typer computes the **inferred effect row** `R_inferred` of `body` as follows:

1. Start with the empty row.
2. Walk the body's typed AST. For each:
   - Direct effect-operation call `E.op(args)`: add `E` (with its `of` args resolved at this call site) to the row.
   - Function call `g(args)` where `g`'s declared signature has `with R_g`: add `R_g` to the row.
   - `handle SUBJECT: clauses` block: compute SUBJECT's row, then **remove** every effect handled by the clauses.
   - Lambda body: the lambda's body has its own row; if the lambda is invoked at this position, its row is added.
3. The result is `R_inferred`.

### 5.2 `with` clause check (Rule E5.2)

The typer verifies `R_inferred ≤ R` (per Rule E4.4). Concretely, every effect performed in the body must be declared in the function's `with` clause.

Error:

- `error[E080]: undeclared effect <E>`: an effect appears in `R_inferred` but not in `R`.

Warning:

- `warning[E081]: unused declared effect <E>`: an effect is declared in `R` but does not appear in `R_inferred`. It is a warning because an unused declaration may be intentional, for forward compatibility.

### 5.3 Handler clause coverage and subtraction (Rule E5.3)

Handler-clause coverage — that every operation of every handled effect appears in the handler's clause list — is one instance of the unified constructor-coverage obligation defined in `spec/pattern_matching.md` Rule M6.4. The discriminator is the set of handled effects, the constructors are operation names (plus optional `return`), and the algorithm reuses the pattern-matrix machinery from `pattern_matching.md` §6.

A handler block with missing clauses emits `error[E093]: handler does not cover operations <list>` with a copy-pasteable suggestion adding the missing `Eff.op(args) => ...` clauses.

A `handle SUBJECT: clauses` block subtracts effects from SUBJECT's inferred row. The subtraction set is the union of the effects mentioned by the clauses:

- A clause `E.op(args) => body` handles `E`.
- A clause `return(v) => body` handles no effect (it intercepts normal completion).

If SUBJECT's row contains `E1 + E2 + E3` and the clauses handle `E1` and `E2`, the row outside the `handle` block is `E3`.

Multiple clauses for the same effect are permitted (one per operation of that effect); they all together "handle" the effect.

**Coverage:** a handler must provide a clause for every operation of each effect it claims to handle. Coverage is the unified constructor-coverage obligation per Rule M6.4 in `spec/pattern_matching.md`; missing clauses emit `error[E093]: handler does not cover operations <list>` with a copy-pasteable suggestion (Rule E6.5 wildcard clauses were removed per OQ-E2 in v0.2).

### 5.4 Top-level handling (Rule E5.4)

`fn main()` may declare any `with` clause. The compiler-installed runtime provides handlers for the **stdlib effect set**: `Console`, `FileSystem`, `Clock`, `Random`, `Async` — exact set defined by stdlib. Effects in `main`'s row that are not in the stdlib set are compile errors.

Other top-level entry points (test mains, library entry points) may declare different sets, configurable at the runtime.

### 5.5 Effect inference and lambdas (Rule E5.5)

A lambda's body has its own inferred effect row. The lambda's *type* is `(args) -> T with R_lambda`. When the lambda is *invoked*, its row is added to the invoking function's inferred row at that call site (per Rule E5.1).

This means a lambda passed as a callback can carry effects. Either the receiving function's parameter type names them, with a concrete row or a row variable (§8), and the receiving function declares them; or the parameter type names none, and the passing body accounts for them (Rule E5.5.a).

**Rule E5.5.a (Ambient pass-through).** A function-typed value with row `R_v` that meets an expected type behaves in one of two ways:

- **The expected type is a function type with a `with` clause `R_e`.** The value's row must be a sub-row of it (`R_v ≤ R_e`, Rule E4.4). `R_v` does not join the enclosing body's row: whoever invokes the value through that type handles or declares `R_e`.
- **Otherwise** (for example a parameter `f: A -> B`, or a type variable). `R_v` joins the enclosing body's row at that point, as if the value were invoked there.

Invoking a value adds the row in its type. A parameter declared `f: A -> B` therefore contributes nothing inside the callee, and the callee's caller has already accounted for the callback's effects. This is how the stdlib's higher-order members, written without row variables, accept effectful callbacks:

```
fn inner(input: String) -> u64 with Fail of String =
    parse_u64(input).fold((v) -> v, (e) -> Fail.fail(e))   // Fail joins inner's row here
```

The rule reads an unannotated function type in a parameter as sharing the enclosing body's effects. It gives the effect polymorphism of §8 without a written row variable.

At run time the effects of `R_v` that the expected type does not mention are performed with evidence that the value captured at the point where it met the expected type (Rule E7.8), not with evidence passed by whoever invokes it. The effects are charged where they are handled. A value that went through an unannotated function type must therefore not outlive the handlers whose evidence it captured: that is Rule E5.5.b. A callback that is meant to be stored and invoked later, under a handler of the invoker's choosing, is written with the row in its type (`List of (() -> () with Async)`) or with a row variable (§8); then the first case above applies, it captures nothing, and the invoker supplies the evidence.

**Rule E5.5.b (Confinement).** A closure that captured the evidence of a handler (Rule E7.8) must not outlive that handler's installation. A use that could let it do so is `error[E085]: closure outlives the handler of <E>`, with a help line that suggests writing the row in the type where the closure is stored.

- **The check is an escape check.** The dynamic extent of a handler installation is treated as a region (`spec/memory_management.md` Rule MM4.1, §14.1), and the closure as a value allocated in it. The existing escape analysis (`spec/memory_management.md` §20.1) decides whether the closure, or a value that contains it, flows out of the `handle` expression: through the `handle`'s result, into a continuation that escapes it, or into the argument of an operation whose handler is installed outside it. Any of these is `E085`.
- **Across a package edge it needs no body.** Every callee whose parameter has an unannotated function type is pure for that parameter's effects, and every Fixed function is total. Such a parameter can outlive the call only through the callee's result or through the argument of an effect operation the callee performs. The callee's signature shows both: whether a value of the parameter's type can become part of the result (the containment test of `spec/compilation_model.md` Rule CM4.6), and which operations the callee's row allows. A call to a function of another package, or through a capability table, is checked from its signature alone; the escape set recorded in the interface (`spec/memory_management.md` Rule MM20.2) may only make the check more precise.
- **The reason.** Evidence refers to a handler installation. If a closure could outlive it, invoking the closure would perform an operation with no handler, which the type system promised could not happen.

Examples. `Log` is handled inside each function shown.

```
effect Log:
    fn write(msg: String) -> ()

fn log_len(s: String) -> u64 with Log =
    Log.write(s)
    s.length()

fn log_id(s: String) -> String with Log =
    Log.write(s)
    s
```

```
// Safe by type. `List of B` cannot contain a value of type `A -> B`,
// so `f` dies with the call.
fn map(xs: List of A, f: A -> B) -> List of B

fn ok(xs: List of String) -> List of u64 with Console =
    handle map(xs, (s) -> log_len(s)):
        Log.write(m) => resume(Console.print_line(m))
```

```
// The result may contain `f` and `g`. The closure `compose` returns is
// placed in the same handler region as its arguments, and the caller's
// escape analysis continues on it.
fn compose(f: A -> B, g: B -> C) -> (A -> C) = (x) -> g(f(x))

fn also_ok(xs: List of String) -> List of u64 with Console =
    handle (do:
        let h = compose((s) -> log_id(s), (s) -> s.length())
        map(xs, h)):                     // `h` is used inside the handler only
        Log.write(m) => resume(Console.print_line(m))
```

```
// Rejected: the closure leaves the handler inside the returned list.
fn later(f: () -> ()) -> List of (() -> ()) = List.Cons(f, List.Nil)

fn bad() -> List of (() -> ()) with Console =
    handle later(() -> Log.write("late")):
        Log.write(m) => resume(Console.print_line(m))
// error[E085]: closure outlives the handler of `Log`
//   = help: write the row in the stored type: `List of (() -> () with Log)`,
//           and give `later` the parameter type `() -> () with Log`
```

With the help applied, the closure captures nothing, the list's type says that invoking its elements performs `Log`, and whoever invokes them handles or declares `Log`.

## 6. Handler semantics

### 6.1 Syntactic form (recap)

```
handle SUBJECT:
    E.op(args) => body
    F.op(args) => body
    return(v) => body          // optional
```

Per the grammar (`HandleExpr`), `SUBJECT` is a single `Expr`. For multi-line subject bodies, wrap in `do:` and parens: `handle (do: …): clauses`.

### 6.2 Operational semantics (Rule E6.2)

Evaluating `handle SUBJECT: clauses` proceeds:

1. Evaluate SUBJECT.
2. While SUBJECT runs, every `E.op(args)` call (where `E` is handled here) is **intercepted**:
   - The current continuation up to this `handle` block is captured.
   - Control transfers to the matching clause `E.op(p1, ..., pn) => body` with `p_i = args[i]`.
   - The clause body executes.
   - Inside the clause body, `resume(v)` resumes the captured continuation with value `v`. The operation's call site receives `v` as its result.
3. If SUBJECT completes normally with value `v`:
   - If a `return(v) => body` clause is present, evaluate the body with `v` bound; the body's value is the result of the `handle` block.
   - Otherwise, `v` is the result.
4. If SUBJECT calls an abortive operation (op with return `!`) and the matching clause runs to completion, the clause body's value is the result of the `handle` block. (No `resume` is called.)

### 6.3 The `return` clause (Rule E6.3)

An optional `return(v) => body` clause transforms the normal-completion value:

```
handle compute(input):
    Fail.fail(e) => Result.err(e)         // abortive: no resume
    return(v) => Result.ok(v)              // normal completion: wrap in Ok
```

Without a `return` clause, normal completion passes through unchanged.

### 6.4 Resume (Rule E6.4)

`resume(v)` resumes the captured continuation with value `v`, where `v: T` and `T` is the declared return type of the matching operation.

Per Rule Q5.11 in `spec/quantities.md`, `resume` is **always quantity 1** in v0.4.1 — exactly zero or one call per clause-body code path. Multi-shot resume is deferred (OQ-Q1 there, OQ-E1 here).

For abortive operations (`-> !`), `resume` is never present in the clause — the typer rejects it as a compile error.

### 6.5 Nested handlers (Rule E6.5)

`handle` blocks nest. Inner handlers run first; an effect bubbles outward until a handler claims it. Clauses in an inner handler do not see outer handlers' clauses — each `handle` has its own clause scope.

```
handle outer_subject:
    OuterEff.op() => ...
```

If `outer_subject` is itself `handle inner_subject: InnerEff.op() => ...`, then `InnerEff` is removed inside the inner handle and the outer handle sees only what `outer_subject` produces externally.

### 6.6 Handler types and rows (Rule E6.6)

**Result type.** The result type of a `handle` block is the join of:

- The `return` clause's body type (if present), or SUBJECT's value type (if absent).
- Each non-abortive clause's body type (after `resume`'s flow through the captured continuation).
- Each abortive clause's body type (which becomes a direct `handle` result).

All of these must be the same type (or unifiable).

**Result effect row.** The effect row of a `handle` block is the union of three sources, parallel to the type rule:

- The `return` clause's body row (if present), or SUBJECT's value type's row (if absent) — the *happy-path* row contributed when SUBJECT completes normally.
- Each clause body's row — effects performed by clause bodies running in the outer scope.
- SUBJECT's declared row **minus the effects handled here** (Rule E5.3) — SUBJECT's unhandled effects that pass through to the outer scope.

The first source mirrors the type rule's first bullet. The third source ensures effects performed *during* SUBJECT's evaluation that aren't intercepted by any clause still appear in the outer row.

## 7. Evidence-passing compilation

Fixed compiles handlers via the **evidence-passing** strategy of Leijen 2017 / Xie–Leijen 2021 (the Koka model). High-level summary:

### 7.1 Evidence vectors

Each operation of each effect is compiled to an indirect call through a per-handler **evidence vector**. The evidence vector contains, for each operation:

- A function pointer to the clause body.
- The handler installation's **marker** (Rule E7.8). The handler frame, which holds the continuation root, is found through it. Every entry has this one layout, whether it is passed as a hidden parameter or captured by a closure.
- Nothing about the types of arguments and results: they are passed as 64-bit slots, an immediate in the slot and anything wider behind a pointer (`spec/compilation_model.md` Rule CM3.3), so an entry needs no boxing information.

### 7.2 Operation dispatch (Rule E7.2)

A call `E.op(args)` compiles to:

1. Look up the current evidence for `E` from the caller's evidence-vector argument or, inside a closure body for an effect its type does not mention, from the evidence the closure captured (Rule E7.8).
2. Indirectly call the operation's pointer with `args` and the entry's marker; the clause reaches its handler frame through the marker.
3. Receive a result value (for non-abortive ops) or transfer control (for abortive ops).

### 7.3 Handler installation (Rule E7.3)

`handle SUBJECT: clauses` compiles to:

1. Allocate a handler frame on the stack (or arena), containing the clause-body pointers and a continuation root.
2. Install the frame into the calling function's evidence vector for the handled effects.
3. Invoke SUBJECT (which now sees the new evidence).
4. On normal completion: pop the frame and run the `return` clause (if present).
5. On operation interception: the clause body runs with access to `resume`, which restores the continuation root.

### 7.4 Continuation capture (Rule E7.4)

For non-abortive operations, the continuation up to the handler frame is captured **lazily**: the runtime preserves the stack between the operation call and the handler frame, and `resume(v)` restarts execution from the suspended point.

Quantity-1 resume (Rule Q5.11) means each captured continuation is consumed exactly once. The runtime may free continuation memory at the consumption point. Multi-shot would require copying or refcounting continuations.

### 7.5 Tail resume optimization (Rule E7.5)

When a clause body's only use of `resume` is a tail call — `resume(expr)` as the final expression of the body — the compiler may emit a *tail-resume* that avoids capturing and restoring the stack:

```
handle f():
    Channel.send(value) =>
        log_send(value)
        resume(())              // tail position → no continuation roundtrip
```

This is the common case in I/O handlers and is the primary performance optimization. Non-tail resume incurs the full continuation roundtrip.

### 7.6 Evidence threading

Every function whose row is not empty receives its evidence as a hidden parameter (`spec/compilation_model.md` Rule CM4.3), and a function parametric over an effect row (§8) receives the evidence for the row variable the same way. This is the function's baseline version: it performs an operation by a lookup and an indirect call. A parameter with an unannotated function type adds no evidence parameter; a callback passed to it carries its own (Rule E7.8).

Where a call site knows the handler, the specialiser may build a clone of the callee for that handler, in which the lookup and the indirect call become a direct call or inline code. A known handler is one component of a clone key, charged to the same budget and chosen by the same score as any other (`spec/compilation_model.md` Rules CM5.1, CM5.3, CM5.4). With `--no-specialise` no such clone is built.

### 7.7 What an operation call hands over (Rule E7.7)

- **Arguments.** The performer cannot see the clause, so it cannot know the clause's demands. It passes every argument under demand `own`. (A call through a capability table does not: it follows the convention of `spec/compilation_model.md` Rule CM4.6.) A clone for a known handler uses the clause's inferred demands.
- **Evidence is not a witness.** Both are hidden parameters that hold code pointers. Evidence is dynamically scoped: each `handle` installs an entry and may shadow an outer one (Rule E6.5), and an entry refers to a handler frame. A witness is static and the same throughout a link unit.
- **Order.** The entries of an evidence vector for a signature follow the canonical order of its effect row, so that a caller and a callee in different packages agree. How row variables and the library entry points of Rule E5.4 are laid out is open (`spec/compilation_model.md` OQ-CM9).
- **Cost.** The cost of performing an operation is that of the clause, which the performer cannot see; in a cost bound it is a symbolic term until a `handle` supplies the clause (`spec/cost.md` Rule CT5.3).
- **Captured evidence** is ordered the same way, by the canonical order of the captured effects, and travels in the closure's environment, not as a hidden parameter (Rule E7.8).

### 7.8 Evidence captured by closures (Rule E7.8)

A function-typed value receives, at each invocation, the evidence for the row written in the type through which it is invoked (§7.6). The effects of its body that this type does not mention are those that Rule E5.5.a let join the enclosing body's row. For them:

1. **Capture.** The closure captures the evidence of those effects at the point where it is given the type that does not mention them. For a lambda written in place, as an argument or in a field, that point is where it is created. A value that already exists and reaches an unannotated function type later is wrapped there in a new closure that captures the evidence and calls the value with it; the wrapper is created at that point.
2. **Pure higher-order functions take no evidence.** A function whose parameter is `f: A -> B` is compiled as if `f` were pure: it receives no hidden evidence for `f`, and invoking `f` passes none. It cannot intercept an effect its type does not mention, because it has no handle on the evidence `f` carries.
3. **Markers, not addresses.** Captured evidence names the handler installation by its **marker**, an identity assigned when the `handle` is installed, and not by a stack address. The closure finds the handler frame through the marker when it performs an operation. A continuation captured by an outer handler may be resumed on other fiber stacks (`spec/memory_management.md` §14.3), with the inner handler's frame at a new address; the marker still reaches the right one.
4. **Confinement.** The handler named by the marker is installed whenever the closure can run, because the closure does not outlive it (Rule E5.5.b).

The reason for capture over passing: a pure higher-order function in a library does not have to change, gain a hidden parameter or gain a row variable for its callers to pass effectful callbacks, and the code it was compiled to stays correct.

**Relation to dispatch and clones.** Rule E7.2 dispatches through the captured entry exactly as through a passed one; the entry has the same layout. Where the function that creates the closure knows the handler (it is itself a clone keyed on that handler, §7.6), the closure's code may be compiled for that handler and call the clause directly; the captured marker is then still needed for continuation capture and for `resume`. A closure that captured evidence and is passed through a capability table is subject to the same confinement check as any other argument: the requirement's signature is all the check needs (Rule E5.5.b).

**Relation to Rule E7.7.** An operation called from a closure body passes its arguments under `own`, as from any other performer. Captured evidence is not a witness: it is chosen at run time, by the handler installed where the closure was given its type, and a witness never is.

**Lanes.** A callback with captured evidence has a non-empty row, so a `fold` that receives it is not a fork site (`spec/quantities.md` Rule Q5.13.a). Captured evidence never crosses into a parallel lane.

**Alternative considered.** Koka binds effects dynamically: every higher-order function and every closure call is row-polymorphic and passes the current evidence vector as a hidden argument, so a closure performs its effects with the handlers in force where it runs. Closures stay fully first-class: one can be stored and invoked later under any handler. The price is a hidden argument on every higher-order function and closure call and a row variable in every such signature, with `mask` to keep a callee from intercepting what its type does not mention. Fixed keeps higher-order functions written without rows (Rule E5.5.a) and pays with Rule E5.5.b instead: a closure that relies on ambient effects is confined to its handler, and one that must outlive it says so with a row in its type (P6 D14).

## 8. Effect polymorphism

### 8.1 Effect-row variables (Rule E8.1)

A function may be polymorphic over an effect row, written with a row variable:

```
fn map<E>(list: is List of A, f: A -> B with E) -> is List of B with E
```

Here `E` is a row variable: any concrete row may be supplied. The function's row is exactly the row of the supplied callback.

Row variables are bound by the function and follow the same scoping as type variables (Rule 4.1 in `spec/type_system.md`). Like type variables, row variables are introduced at first use: an upper-case name in a `with` row that names no effect is a row variable. A row variable takes no `of` arguments.

**Rule E8.1.a (Solving row variables).** Inside the function's own body a row variable is rigid. It is an effect of its own, performed by calling anything whose row names it, and the body's `with` clause must name it too. At a call site each row variable is instantiated afresh. A callback whose row meets an expected row containing the variable adds the effects that the rest of the expected row does not name to the variable's solution. The solution is the union of what every such callback adds. A variable that no callback extends stands for the empty row.

### 8.2 Concrete-plus-row composition (Rule E8.2)

A function may have a concrete effect row plus a row variable:

```
fn map_console<E>(list: is List of A, f: A -> B with Console + E) -> is List of B with Console + E
```

Calls to this function must supply a callback whose row contains at least `Console`. The row variable `E` captures any additional effects.

### 8.3 Higher-order effect composition

Generic combinators like `map`, `filter` and `fold` accept effectful callbacks without row variables, through Rule E5.5.a and captured evidence (Rule E7.8), as long as the callback does not outlive its handler (Rule E5.5.b). A row variable is needed where the combinator's own type must say that it performs the callback's effects, for example because it stores the callback or because its caller must handle the effects.

```
fn each<E>(list: is List of A, f: A -> () with E) -> () with E =
    list.fold((), (_, x) -> f(x))
```

`each` itself performs no direct effects but inherits whatever the caller's `f` does.

## 9. Open questions

**Resolved in v0.2:**

- **OQ-E6 — Effect aliasing.** Resolved → §4.5. `type X = E1 + E2 + ...` introduces an effect-row alias; the typer detects effect-vs-cap kind from RHS contents.

**Deferred decisions (revisit when motivated by examples):**

- **OQ-E5 — Linear effects (re-opened in v0.2.1).** §3.5 sketches the proposed semantics: `linear effect E:` declares an effect whose ops are quantity 1 in the row. Closure depends on `spec/quantities.md` OQ-Q5 (whether effect rows participate in QTT quantity at all). Until OQ-Q5 closes, §3.5 is non-normative.
- **OQ-E1 — Multi-shot handlers (deferred to v0.5+).** Linked to `spec/quantities.md` OQ-Q1. v0.4.4 keeps `resume` at quantity 1 per Rule Q5.11 and Rule E6.4. Multi-shot would require continuation copying or persistent continuation representation, both with significant memory overhead.
- **OQ-E3 — Effect inheritance (deferred).** No `extends` for effects in v0.4.4. Adding `effect MyConsole extends Console: ...` is straightforward if stdlib needs it.
- **OQ-E8 — Row-polymorphic data (deferred).** A data type cannot take a row variable, so a structure that stores callbacks (a task queue) fixes their row: `List of (() -> () with Async)`. A callback with an extra rigid row variable cannot be stored in it. `examples/08_effects_handlers.fixed`'s `run_scheduler` hits this case and keeps one `T015` until it is resolved.
- **OQ-E9 — Parameterised effect aliases.** Rule E4.5.b: the grammar gives aliases value parameters only. An alias over an effect's `of` argument needs a type parameter on `TypeAlias` and a use form (`with AppEffect of String` would parse as an `EffectBound`).
- **OQ-E10 — Handler clauses for one effect at two `of` arguments.** `Fail of String` and `Fail of i64` are distinct effects (§3.4, Rule E4.2), but `HandlerClause` names only `E.op(…)` and cannot name the `of` argument. A `handle` whose subject performs both cannot say which one a clause handles. Needs a decision: a clause form with `of` (`Fail of String.fail(e) => …`), inference from the pattern's type, or a rule that one `handle` handles at most one instance of an effect.
- **OQ-E4 — Higher-rank effect polymorphism (deferred).** A function whose argument is itself effect-polymorphic (`fn higher(g: forall E. (A -> B with E) -> ...)`) is not in v0.4.4. Defer until benchmark code reveals the need.

**Closed (decided not to add):**

- **OQ-E2 — Wildcard handler clauses.** v0.4 specs no `_ => body` clause in `HandlerClause`. Decision: explicit per-op coverage is required; sandboxes/proxies that need wildcard catch-all should be expressed via effect aliasing (§4.5) or by an explicit list of clauses. Closed in v0.2.
- **OQ-E7 — Per-op linearity within a multi-op effect.** Decision: linearity stays at *effect* granularity. Multi-step resource lifecycles are expressed by splitting each linear operation into its own `linear effect`, each with its own usage budget. This keeps Rule E3.5 simple, preserves QTT-row semantics (§4.1), and makes the linearity contract visible in every signature that uses the resource. Closed in v0.2 — see §3.5's Use case for examples.

## 10. Worked examples

### 10.1 `examples/03_option_result.fixed` — `compute`

```
fn compute(input: String) -> is Result of (String, u64) =
    fn inner(input: String) -> u64 with Fail of String =
        let n = parse_u64(input).fold((v) -> v, (e) -> Fail.fail(e))
        let doubled = n * 2
        let result = divide(doubled, 3).fold((v) -> v, (e) -> Fail.fail(e))
        result

    handle inner(input):
        Fail.fail(e) => Result.err(e)
        return(v) => Result.ok(v)
```

Inference walk:

- `parse_u64`'s callback `(e) -> Fail.fail(e)` — the lambda's body calls abortive op `Fail.fail`, so its row is `Fail of String`.
- `inner`'s body passes these lambdas to `fold`, whose parameters have unannotated function types. By Rule E5.5.a the lambdas' row joins `inner`'s row where they are passed, and by Rule E7.8 each lambda captures `inner`'s evidence for `Fail` there; `fold` itself stays pure. So `inner`'s `R_inferred = Fail of String`. The lambdas do not outlive the call of `fold` (its result type cannot hold them), so Rule E5.5.b holds. Its declared `with Fail of String` matches.
- `compute`'s body wraps `inner(input)` in a `handle` whose clauses cover `Fail` (abortive — `Result.err(e)`) and `return` (transforms to `Result.ok(v)`). After subtraction, the row outside is empty. `compute` declares no `with` clause, which matches. ✓
- The `handle` block's result type joins:
  - `return` clause's body: `Result.ok(v): is Result of (String, u64)`.
  - `Fail.fail` clause's body: `Result.err(e): is Result of (String, u64)`.
- Both unify to `is Result of (String, u64)`, matching `compute`'s declared return type.

### 10.2 `examples/08_effects_handlers.fixed` — main with nested handlers

The deeply nested `handle (do: ... ): clauses` chain in `main` removes one effect per nesting level: `Fail`, then `Log`, then `Console`. Each level subtracts its handled effect from the row that the next outer level sees. The outermost `handle` for `Console` leaves `FileSystem`, which `read_config` performs and no clause handles. `main` declares `with FileSystem`, consistent with §5.4's rule that `main`'s row must be a subset of the runtime-installed stdlib effects: the runtime handles `FileSystem`.

### 10.3 `examples/06_functor_monad.fixed` — `sequence` (effect-poly callback)

```
fn sequence(list: is List of (M is Monad of A)) -> M of (is List of A) =
    match list:
        List.Cons(head, tail) =>
            do:
                a <- head
                rest <- sequence(tail)
                M.pure(List.Cons(a, rest))
        List.Nil() => M.pure(List.Nil())
```

`sequence` performs no direct effects — its `with` clause is empty. The Monad operations occur inside `M`, which the type system threads through but which is not an *effect* in the row sense (Monads in Fixed are caps with `do`-notation desugaring, not effects). This example confirms that Fixed's effect system and its monad-via-cap pattern coexist without overlap.

## 11. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` | All effect-related syntax |
| `spec/type_system.md` | Effect declarations as type-level constructs (§9 forwards here); `!` typing (§5.6) |
| `spec/compilation_model.md` | Evidence as a hidden parameter (Rule CM4.3), including evidence captured by closures (Rule E7.8); a known handler as a clone key component (Rule CM5.1); the convention of Rule E7.7 |
| `spec/quantities.md` | Rule Q5.11 (`resume` quantity 1); OQ-Q1 ↔ OQ-E1; Rule Q9.2 fixes the order in which argument effects are performed (left to right); fork sites need empty rows (Rule Q5.13.a) |
| `spec/memory_management.md` | §14: handler kinds, abort resets, fiber stacks for capturing handlers, continuation memory; Rule MM4.1 and §20.1 for the confinement check of Rule E5.5.b |
| `spec/diagnostics.md` | `inspect` and `trace`, which are not effects (Rule E4.6) |
| `spec/properties.md` | Effects in `prop` bodies are forbidden. Prop bodies are excluded from the compiled artifact (`spec/quantities.md` Rule Q5.5) and run only in the test build |
| `spec/pattern_matching.md` | Handler clauses reuse pattern syntax; coverage is Rule M6.4 |
| `docs/references/scala3-compiler-reference.md` | Recheck pattern; effect inference is a Recheck pass like quantity inference |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `effects/EffectChecker.scala` (effect inference); Phase 4: `effects/EvidencePasser.scala` (evidence-passing compilation) |
