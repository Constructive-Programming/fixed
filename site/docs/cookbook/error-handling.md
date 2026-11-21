# Error handling without data

Success and failure are capabilities, not `Either`-shaped data you
commit to up front — and the `Fail` effect gives you propagation that
doesn't thread a result type through every signature
([`spec/effects.md` §3](../effects.md)).

## Optional and Result as capabilities

Both are caps with constructor *requirements* (`Self.fn`) and a
destructor (`fold`). Because they are capabilities, you consume them
with `fold` — never `match`:

```
cap Optional extends Functor + Folding:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold(on_some: Part -> R, on_none: () -> R) -> R

cap Result of (E, A):
    Self.fn ok(value: A) -> Self
    Self.fn err(error: E) -> Self
    fn fold(on_ok: A -> R, on_err: E -> R) -> R
```

Concrete data provides them via `satisfies`, mapping data constructors
to cap constructors:

```
data Outcome of (E, A):
    Success(value: A)
    Failure(error: E)

Outcome satisfies Result:
    Success as ok
    Failure as err
```

With the satisfaction in scope, `Result.ok(5)` constructs an
`Outcome.Success` — and a function typed `is Result of (String, u64)`
accepts *any* provider, including a future `HttpResult` that logs
failures.

## Propagate with the `Fail` effect

`Fail` is an effect with one abortive operation (`-> !` never returns
to the call site). Inside a `with Fail of String` function, failing is
a plain call — no wrapping, no re-throwing:

```
effect Fail of E:
    fn fail(error: E) -> !

fn divide(a: u64, b: u64) -> is Result of (String, u64) =
    if b == 0:
        Result.err("division by zero")
    else:
        Result.ok(a / b)
```

## Handle to `Result`

At the boundary, one `handle` block turns the effect back into a value:

```
fn compute(input: String) -> is Result of (String, u64) =
    fn inner(input: String) -> u64 with Fail of String =
        let n = parse_u64(input).fold((v) -> v, (e) -> Fail.fail(e))
        let doubled = n * 2
        let result = divide(doubled, 3).fold((v) -> v, (e) -> Fail.fail(e))
        result

    // Handle the Fail effect, converting it back to Result
    handle inner(input):
        Fail.fail(e) => Result.err(e)
        return(v) => Result.ok(v)
```

The `handle` block is the conversion point: `Fail` disappears from the
row, and what comes out is a value again. Put the handlers at the
layer boundary, keep the layers themselves effectful.

## Optional as the "soft" failure

Reserve `Optional` for absence that isn't an error, and convert to
`Fail` when absence *becomes* one:

```
fn require_user_age(users: is Map of (String, u64), name: String)
    -> u64 with Fail of String =
    lookup_user_age(users, name).fold(
        (age) -> age,
        () -> Fail.fail("user not found: ".concat(name)),
    )
```

> Source: [`examples/03_option_result.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/03_option_result.fixed)
