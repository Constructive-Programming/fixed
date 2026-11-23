# Properties

A `prop` declaration pins an invariant next to the type that carries
it — machine-checked documentation, discharged statically where the
verifier can and queued as property tests where it can't yet
([`spec/properties.md`](../properties.md)).

## Properties on capabilities

The invariant travels with the capability, so any value typed `C is
Sorted` already *is* sorted — no separate postcondition needed:

```
cap Sorted of (Part is Ord) extends Sequencing:
    prop sorted: fold(true, (acc, prev, curr) -> acc && prev <= curr)

cap NonEmpty extends Sequencing + Sized:
    prop non_empty: size > 0
```

Quantify with `forall`, constrain with `suchThat`:

```
cap Stack extends Sequencing + Sized:
    fn push(value: Part) -> Self
    fn pop -> Self

    prop push_pop_identity: forall (s: Self, x: Part) ->
        s.push(x).pop == s

    prop pop_decrements: forall (s: Self) suchThat s.size > 0 ->
        s.pop().size == s.size - 1
```

A function that returns `C is Sorted` has its sortedness obligation
checked at the call site — the result type *is* the contract:

```
fn sort(collection: C is Sequencing + Sized of (A is Ord)) -> C is Sorted =
    collection.fold_right(C.empty, (x, acc) -> insert_sorted(acc, x))
```

## Impossible cases

`satisfies` can mark a constructor `impossible` — a satisfaction that
can never fire, statically:

```
// u64 is always present — it can never be "none"
u64 satisfies Optional of Self:
    Self as some
    impossible as none
```

Now `u64 is Optional` means "always the some case": any code path that
would construct the `none` side on a `u64` is a compile error. The
same move makes `NonEmptyList` a `Sequencing` that can never be empty.

## Refinement types

`fn -> cap` declares a function that *returns* a capability — a
refinement. The parameters become type-level indices, so the
constraint holds without a wrapper value at runtime:

```
cap Bounded of (N is Numeric + Ord, lo: N, hi: N):
    Self.fn new(value: N) -> Self
    fn value -> N

    prop in_range: forall (b: Self) ->
        b.lo <= b.value && b.value <= b.hi
```

`Bounded of (i64, 0, 100)` and `Bounded of (i64, 16, 24)` are distinct
types with distinct bounds — and both erase to a bare `i64`. The
cap-generator form (`cap Name(params)`) and `type` aliases over
generators compose refinements like ordinary capabilities.

> Source: [`examples/11_properties.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/11_properties.fixed)
