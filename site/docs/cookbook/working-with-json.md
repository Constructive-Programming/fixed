# Working with JSON

When a format genuinely is a sum of shapes, `data` is the right tool —
concrete, matchable variants — and the auto-derived `fold` still does
the heavy lifting ([`spec/type_system.md` §7.4](../type_system.md)).

## The type

Recursive positions are wrapped in `List` (and `Tuple` for objects) so
the derived `fold` pre-folds through them — the array callback receives
`List of R`, not raw JSON:

```fixed
data Json:
    Null
    Bool(value: bool)
    Number(value: f64)
    String(value: String)
    Array(elements: List of Json)
    Object(fields: List of (Tuple of (String, Json)))
```

## Fold to pretty-print

Functions are total, so `pretty` cannot self-recurse over the tree.
Instead each fold step produces a *function* `(u64) -> String`, and
the array/object callbacks pass `indent + 2` to their already-folded
children — the indent travels without an accumulator parameter:

```fixed
fn pretty(json: Json, indent: u64) -> String =
    json.fold(
        () -> (_) -> "null",                                   // Null
        (b) -> (_) -> b.show(),                                // Bool
        (n) -> (_) -> n.show(),                                // Number
        (s) -> (_) -> "\"".concat(s).concat("\""),             // String
        (elements: List of (u64 -> String)) ->                 // Array
            (i: u64) ->
                ...
        (fields: List of (Tuple of (String, u64 -> String))) -> // Object
            (i: u64) ->
                ...
    )(indent)
```

## Deep match, fold to navigate

`match` is for data types; `fold` for capabilities. Both appear in one
function — match the top shape, fold the `Optional` that comes back:

```fixed
fn get_field(json: Json, key: String) -> is Optional of Json =
    match json:
        Json.Object(fields) =>
            fields.find((pair) -> pair.first == key)
                |> map_optional((pair) -> pair.second)
        _ => Optional.none

// Navigate a path like ["users", "0", "name"]:
// left-fold the path, short-circuit to none on the first miss.
fn navigate(json: Json, path: List of String) -> is Optional of Json =
    path.fold(Optional.some(json), (acc, key) ->
        acc.fold(
            (current) -> get_field(current, key),
            () -> Optional.none,
        )
    )
```

Building a value reads exactly like the data it produces:

```fixed
let user = Json.Object([
    ("name", Json.String("Alice")),
    ("age", Json.Number(30.0)),
    ("tags", Json.Array([
        Json.String("admin"),
        Json.String("user"),
    ])),
    ("address", Json.Null),
])
```

For parsing, pair the type with the `Fail` effect: a parser is a
function `with Fail of String`, and the caller converts to whatever
error type it wants at the boundary — see
[Error handling](error-handling.md#handle-to-result).

> Source: [`examples/04_json.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/04_json.fixed)
