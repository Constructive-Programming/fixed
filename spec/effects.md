# Fixed Effects

**Status:** Draft v0.2
**Specifies:** algebraic effect declarations, effect-row inference, handler semantics, and evidence-passing compilation for Fixed v0.4.2 source.
**Last revised:** 2026-04-30

**Changes from v0.1.** Resolves OQ-E5 (linear effects → §3.5) and OQ-E6 (effect aliasing → §4.5). Removes Rule E6.5 (wildcard handler arms) per OQ-E2 decision. OQ-E1, OQ-E3, OQ-E4 marked as deferred decisions.

## 1. Scope

This document specifies Fixed's algebraic effect system end-to-end: declarations, the type-level effect-row machinery, the inference algorithm, the runtime semantics of handlers, and how all of the above is compiled to evidence vectors. It covers:

- How `effect E: fn op(...) -> T` declarations are typed (§3).
- What an **effect row** is and how `+` composes them (§4).
- The inference algorithm that derives a function's `with` clause from its body (§5).
- The operational semantics of `handle SUBJECT: arms` (§6).
- The runtime mechanism — explicit evidence vectors, indirect-call dispatch, continuation capture (§7).
- How effect rows participate in polymorphism (§8).

It does **not** specify:

- The base type system or capability machinery — see `spec/type_system.md`.
- Resume's quantity (single-shot vs multi-shot) beyond a brief recap — see `spec/quantities.md` Rule Q5.11.
- How Perceus interacts with handler-captured continuations — see `spec/perceus.md`.
- How `!` (the never type) is typed beyond the effect-relevant rules — see `spec/type_system.md` §5.6.

## 2. Dependencies and theoretical basis

- `spec/syntax_grammar.ebnf` v0.4.1 — `EffectDecl`, `EffectMember`, `WithClause`, `EffectRow`, `EffectBound`, `HandleExpr`, `HandlerArm`, `resume` keyword.
- `spec/type_system.md` v0.4.1 — Rule 4.1 (typevar introduction), §5.6 (`!` type), §9 (brief reference forwarding to this doc).
- `spec/quantities.md` v0.1 — Rule Q5.11 (resume single-shot).
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

An operation declared with return type `!` (the never type) is **abortive**: it transfers control to its handler arm and never returns to the call site.

```
effect Fail of E:
    fn fail(error: E) -> !
```

A handler arm for an abortive operation **must not** call `resume` — there is no continuation to resume. The arm body's value becomes the result of the surrounding `handle` block. The typer enforces this:

- Inside the arm body for an abortive operation, `resume` is a compile error.
- The arm body's value type must match the `handle` block's expected type.

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

### 3.5 Linear effects (Rule E3.5)

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

`Fail of String` and `Fail of i64` are distinct. `Fail of String` from `module a` and `Fail of String` from `module b` (declared separately) are also distinct — effect identity is nominal, not structural.

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

**Rule E4.5.b (Parameterised effect aliases).** Effect aliases may take value parameters using the existing parameterised-alias syntax:

```
type AppEffect(E) = Console + Fail of E + Log

fn run() -> () with AppEffect(String) = ...
// expands to: with Console + Fail of String + Log
```

**Rule E4.5.c (Aliases in handlers).** When a `handle` block subtracts effects from a row containing an alias, the alias is first expanded and then individual effects are subtracted per Rule E5.3:

```
type IO = Console + FileSystem

fn run() -> () with IO = ...

handle run():
    Console.print_line(s) => ...
    FileSystem.read_file(p) => ...
    return(v) => v
// SUBJECT row expands to Console + FileSystem; arms cover both → outer row is empty
```

**Rule E4.5.d (Composition).** Aliases compose freely:

```
type IO = Console + FileSystem
type RuntimeEffects = IO + Clock + Log
// expanded RuntimeEffects: Console + FileSystem + Clock + Log
```

A linear effect appearing inside an alias retains its linearity (Rule E3.5) when the alias is expanded — `type IO = Console + FileSystem` containing a linear `FileSystem` would propagate the linearity check to every use site of `IO`.

## 5. Effect inference

### 5.1 Inference algorithm (Rule E5.1)

For each function definition `fn f(args) -> T with R = body`, the typer computes the **inferred effect row** `R_inferred` of `body` as follows:

1. Start with the empty row.
2. Walk the body's typed AST. For each:
   - Direct effect-operation call `E.op(args)`: add `E` (with its `of` args resolved at this call site) to the row.
   - Function call `g(args)` where `g`'s declared signature has `with R_g`: add `R_g` to the row.
   - `handle SUBJECT: arms` block: compute SUBJECT's row, then **remove** every effect handled by the arms.
   - Lambda body: the lambda's body has its own row; if the lambda is invoked at this position, its row is added.
3. The result is `R_inferred`.

### 5.2 `with` clause check (Rule E5.2)

The typer verifies `R_inferred ≤ R` (per Rule E4.4). Concretely, every effect performed in the body must be declared in the function's `with` clause.

Errors:

- `error[E080]: undeclared effect <E>`: an effect appears in `R_inferred` but not in `R`.
- `error[E081]: unused declared effect <E>`: an effect is declared in `R` but does not appear in `R_inferred`. (Warning rather than error — unused declarations may be intentional for forward compatibility.)

### 5.3 Handler subtraction (Rule E5.3)

A `handle SUBJECT: arms` block subtracts effects from SUBJECT's inferred row. The subtraction set is the union of the effects mentioned by the arms:

- An arm `E.op(args) => body` handles `E`.
- An arm `return(v) => body` handles no effect (it intercepts normal completion).

If SUBJECT's row contains `E1 + E2 + E3` and the arms handle `E1` and `E2`, the row outside the `handle` block is `E3`.

Multiple arms for the same effect are permitted (one per operation of that effect); they all together "handle" the effect.

**Coverage:** a handler must provide an arm for every operation of each effect it claims to handle (or use a wildcard, see Rule E6.5). Otherwise: `error[E082]: incomplete handler for <E>`.

### 5.4 Top-level handling (Rule E5.4)

`fn main()` may declare any `with` clause. The compiler-installed runtime provides handlers for the **stdlib effect set**: `Console`, `FileSystem`, `Clock`, `Random`, `Async` — exact set defined by stdlib. Effects in `main`'s row that are not in the stdlib set are compile errors.

Other top-level entry points (test mains, library entry points) may declare different sets, configurable at the runtime.

### 5.5 Effect inference and lambdas (Rule E5.5)

A lambda's body has its own inferred effect row. The lambda's *type* is `(args) -> T with R_lambda`. When the lambda is *invoked*, its row is added to the invoking function's inferred row at that call site (per Rule E5.1).

This means a lambda passed as a callback can carry effects, which the receiving function must declare in its own `with` clause (typically via effect polymorphism, §8).

## 6. Handler semantics

### 6.1 Syntactic form (recap)

```
handle SUBJECT:
    E.op(args) => body
    F.op(args) => body
    return(v) => body          // optional
```

Per the grammar (`HandleExpr`), `SUBJECT` is a single `Expr`. For multi-line subject bodies, wrap in `do:` and parens: `handle (do: …): arms`.

### 6.2 Operational semantics (Rule E6.2)

Evaluating `handle SUBJECT: arms` proceeds:

1. Evaluate SUBJECT.
2. While SUBJECT runs, every `E.op(args)` call (where `E` is handled here) is **intercepted**:
   - The current continuation up to this `handle` block is captured.
   - Control transfers to the matching arm `E.op(p1, ..., pn) => body` with `p_i = args[i]`.
   - The arm body executes.
   - Inside the arm body, `resume(v)` resumes the captured continuation with value `v`. The operation's call site receives `v` as its result.
3. If SUBJECT completes normally with value `v`:
   - If a `return(v) => body` arm is present, evaluate the body with `v` bound; the body's value is the result of the `handle` block.
   - Otherwise, `v` is the result.
4. If SUBJECT calls an abortive operation (op with return `!`) and the matching arm runs to completion, the arm body's value is the result of the `handle` block. (No `resume` is called.)

### 6.3 The `return` clause (Rule E6.3)

An optional `return(v) => body` arm transforms the normal-completion value:

```
handle compute(input):
    Fail.fail(e) => Result.err(e)         // abortive: no resume
    return(v) => Result.ok(v)              // normal completion: wrap in Ok
```

Without a `return` clause, normal completion passes through unchanged.

### 6.4 Resume (Rule E6.4)

`resume(v)` resumes the captured continuation with value `v`, where `v: T` and `T` is the declared return type of the matching operation.

Per Rule Q5.11 in `spec/quantities.md`, `resume` is **always quantity 1** in v0.4.1 — exactly zero or one call per arm-body code path. Multi-shot resume is deferred (OQ-Q1 there, OQ-E1 here).

For abortive operations (`-> !`), `resume` is never present in the arm — the typer rejects it as a compile error.

### 6.5 Nested handlers (Rule E6.5)

`handle` blocks nest. Inner handlers run first; an effect bubbles outward until a handler claims it. Arms in an inner handler do not see outer handlers' arms — each `handle` has its own arm scope.

```
handle outer_subject:
    OuterEff.op() => ...
```

If `outer_subject` is itself `handle inner_subject: InnerEff.op() => ...`, then `InnerEff` is removed inside the inner handle and the outer handle sees only what `outer_subject` produces externally.

### 6.6 Handler types and rows (Rule E6.6)

**Result type.** The result type of a `handle` block is the join of:

- The `return` arm's body type (if present), or SUBJECT's value type (if absent).
- Each non-abortive arm's body type (after `resume`'s flow through the captured continuation).
- Each abortive arm's body type (which becomes a direct `handle` result).

All of these must be the same type (or unifiable).

**Result effect row.** The effect row of a `handle` block is the union of three sources, parallel to the type rule:

- The `return` arm's body row (if present), or SUBJECT's value type's row (if absent) — the *happy-path* row contributed when SUBJECT completes normally.
- Each arm body's row — effects performed by arm bodies running in the outer scope.
- SUBJECT's declared row **minus the effects handled here** (Rule E5.3) — SUBJECT's unhandled effects that pass through to the outer scope.

The first source mirrors the type rule's first bullet. The third source ensures effects performed *during* SUBJECT's evaluation that aren't intercepted by any arm still appear in the outer row.

## 7. Evidence-passing compilation

Fixed compiles handlers via the **evidence-passing** strategy of Leijen 2017 / Xie–Leijen 2021 (the Koka model). High-level summary:

### 7.1 Evidence vectors

Each operation of each effect is compiled to an indirect call through a per-handler **evidence vector**. The evidence vector contains, for each operation:

- A function pointer to the arm body.
- A handler-frame reference (the captured continuation root).
- Type information sufficient for boxing args / return value when needed.

### 7.2 Operation dispatch (Rule E7.2)

A call `E.op(args)` compiles to:

1. Look up the current evidence for `E` from the caller's evidence-vector argument.
2. Indirectly call the operation's pointer with `args` and the handler-frame reference.
3. Receive a result value (for non-abortive ops) or transfer control (for abortive ops).

### 7.3 Handler installation (Rule E7.3)

`handle SUBJECT: arms` compiles to:

1. Allocate a handler frame on the stack (or arena), containing the arm-body pointers and a continuation root.
2. Install the frame into the calling function's evidence vector for the handled effects.
3. Invoke SUBJECT (which now sees the new evidence).
4. On normal completion: pop the frame and run the `return` arm (if present).
5. On operation interception: the arm body runs with access to `resume`, which restores the continuation root.

### 7.4 Continuation capture (Rule E7.4)

For non-abortive operations, the continuation up to the handler frame is captured **lazily**: the runtime preserves the stack between the operation call and the handler frame, and `resume(v)` restarts execution from the suspended point.

Quantity-1 resume (Rule Q5.11) means each captured continuation is consumed exactly once. The runtime may free continuation memory at the consumption point. Multi-shot would require copying or refcounting continuations.

### 7.5 Tail resume optimization (Rule E7.5)

When an arm body's only use of `resume` is a tail call — `resume(expr)` as the final expression of the body — the compiler may emit a *tail-resume* that avoids capturing and restoring the stack:

```
handle f():
    Channel.send(value) =>
        log_send(value)
        resume(())              // tail position → no continuation roundtrip
```

This is the common case in I/O handlers and is the primary performance optimization. Non-tail resume incurs the full continuation roundtrip.

### 7.6 Evidence threading

Polymorphic functions parametric over effect rows (§8) receive evidence vectors as additional implicit arguments. Monomorphisation of effect-polymorphic functions specialises these evidence-vector arguments per call site.

## 8. Effect polymorphism

### 8.1 Effect-row variables (Rule E8.1)

A function may be polymorphic over an effect row, written with a row variable:

```
fn map<E>(list: is List of A, f: A -> B with E) -> is List of B with E
```

Here `E` is a row variable: any concrete row may be supplied. The function's row is exactly the row of the supplied callback.

Row variables are bound by the function and follow the same scoping as type variables (Rule 4.1 in `spec/type_system.md`). Like type variables, row variables are introduced at first use.

### 8.2 Concrete-plus-row composition (Rule E8.2)

A function may have a concrete effect row plus a row variable:

```
fn map_console<E>(list: is List of A, f: A -> B with Console + E) -> is List of B with Console + E
```

Calls to this function must supply a callback whose row contains at least `Console`. The row variable `E` captures any additional effects.

### 8.3 Higher-order effect composition

This is what makes effect polymorphism essential. Without row variables, generic combinators like `map`, `filter`, `fold` would be unable to accept effectful callbacks without committing to a specific effect set.

```
fn each<E>(list: is List of A, f: A -> () with E) -> () with E =
    list.fold((), (_, x) -> f(x))
```

`each` itself performs no direct effects but inherits whatever the caller's `f` does.

## 9. Open questions

**Resolved in v0.2:**

- **OQ-E5 — Linear effects.** Resolved → §3.5. `linear effect E:` declares an effect whose ops are quantity 1 in the row; usage check enforces ≤ 1 per code path. Useful for resource management.
- **OQ-E6 — Effect aliasing.** Resolved → §4.5. `type X = E1 + E2 + ...` introduces an effect-row alias; the typer detects effect-vs-cap kind from RHS contents.

**Deferred decisions (revisit when motivated by examples):**

- **OQ-E1 — Multi-shot handlers (deferred to v0.5+).** Linked to `spec/quantities.md` OQ-Q1. v0.4.2 keeps `resume` at quantity 1 per Rule Q5.11 and Rule E6.4. Multi-shot would require continuation copying or persistent continuation representation, both with significant memory overhead.
- **OQ-E3 — Effect inheritance (deferred).** No `extends` for effects in v0.4.2. Adding `effect MyConsole extends Console: ...` is straightforward if stdlib needs it.
- **OQ-E4 — Higher-rank effect polymorphism (deferred).** A function whose argument is itself effect-polymorphic (`fn higher(g: forall E. (A -> B with E) -> ...)`) is not in v0.4.2. Defer until benchmark code reveals the need.

**Closed (decided not to add):**

- **OQ-E2 — Wildcard handler arms.** v0.4 specs no `_ => body` arm in `HandlerArm`. Decision: explicit per-op coverage is required; sandboxes/proxies that need wildcard catch-all should be expressed via effect aliasing (§4.5) or by an explicit list of arms. Closed in v0.2.
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
- `inner`'s body calls these lambdas via `fold`, which itself takes the row of its second argument. So `inner`'s `R_inferred = Fail of String`. Its declared `with Fail of String` matches.
- `compute`'s body wraps `inner(input)` in a `handle` whose arms cover `Fail` (abortive — `Result.err(e)`) and `return` (transforms to `Result.ok(v)`). After subtraction, the row outside is empty. `compute` declares no `with` clause, which matches. ✓
- The `handle` block's result type joins:
  - `return` arm's body: `Result.ok(v): is Result of (String, u64)`.
  - `Fail.fail` arm's body: `Result.err(e): is Result of (String, u64)`.
- Both unify to `is Result of (String, u64)`, matching `compute`'s declared return type.

### 10.2 `examples/08_effects_handlers.fixed` — main with nested handlers

The deeply nested `handle (do: ... ): arms` chain in `main` removes one effect per nesting level: `Fail`, then `Log`, then `Console`. Each level subtracts its handled effect from the row that the next outer level sees. The outermost `handle` for `Console` reduces the row to empty — `main` declares no `with` clause, consistent with §5.4's rule that `main`'s row must be a subset of the runtime-installed stdlib effects (here, the runtime needn't install anything since `main` is fully self-handled).

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
| `spec/quantities.md` | Rule Q5.11 (`resume` quantity 1); OQ-Q1 ↔ OQ-E1 |
| `spec/perceus.md` (TBD) | Continuation memory management; refcount interaction with captured arms |
| `spec/properties.md` (TBD) | Effects in `prop` bodies are forbidden — props are checked at quantity 0 (Rule Q5.5), and `prop` bodies are compile-time only |
| `spec/pattern_matching.md` (TBD) | Handler arms reuse pattern syntax; not effect-specific |
| `docs/references/scala3-compiler-reference.md` | Recheck pattern; effect inference is a Recheck pass like quantity inference |
| `docs/plans/implementation-plan.md` | Phase 3 deliverable: `effects/EffectChecker.scala` (effect inference); Phase 4: `effects/EvidencePasser.scala` (evidence-passing compilation) |
