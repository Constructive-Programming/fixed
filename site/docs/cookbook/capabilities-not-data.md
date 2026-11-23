# Capabilities, not data

The core move of Fixed: functions declare what their arguments can
*do*, not which container holds them. No `List`, `Vec` or `Array`
appears anywhere — only capabilities, and the compiler chooses the
representation ([`spec/type_system.md` §6](../type_system.md)).

## Ask for the capability

A bound `is Folding of (N is Numeric)` admits any collection that can
fold — linked list, array, tree, anything:

```
// Works on ANY collection — linked list, array, tree, anything
fn sum(numbers: is Folding of (N is Numeric)) -> N {
    numbers.fold(0, (acc, x) -> acc + x)
}
```

The same function sums a cons-list and a contiguous array; nothing
about `sum` changes. Numeric polymorphism comes along for free: `N is
Numeric` works for `u64`, `i64`, `f64`.

`is` bounds stack with `+`, and `Self.fn` declarations make a
capability *constructible*, not just consumable:

```
cap Sequencing:
    fn head -> is Optional of Part
    fn tail -> Self
    Self.fn cons(h: Part, t: Self) -> Self

cap Optional extends Functor + Folding:
    Self.fn some(value: Part) -> Self
    Self.fn none -> Self
    fn fold(on_some: Part -> R, on_none: () -> R) -> R
```

Construction through the capability (`Optional.some(x)`) is resolved
to the concrete data type at the call site via `satisfies` — see
[Error handling](error-handling.md).

## Bundle capabilities

`type` aliases name capability bundles. They are pure aliases — no new
type, no wrapper:

```
type ArrayLike = Sequencing + RandomAccess + Sized + Empty

// Requires index-based access — compiler SHOULD choose a contiguous representation
fn binary_search(
    sorted: C is ArrayLike of (A is Ordered),
    target: A,
) -> is Optional of u64 {
    ...
}
```

The bundle is the *representation hint*. Request `Sequencing + Folding`
and the compiler may choose a linked list; add `RandomAccess + Sized`
and it should choose a contiguous array, because the capability set
narrows the representation space ([`spec/type_system.md`
§6.5](../type_system.md)). Ask for exactly what you use — the weakest
bound admits the most implementations and gives the compiler the
freest hand.

## Capabilities vs data

Use `data` only when you need concrete, matchable variants — sums with
named constructors, like a JSON AST or an expression tree. Even then,
consume them with the *auto-derived* `fold` where you can, and reach
for `match` only when the shapes genuinely matter:
[Working with JSON](working-with-json.md) shows both in one program.

> Source: [`examples/02_collections.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/02_collections.fixed),
> [`examples/01_basics.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/01_basics.fixed)
