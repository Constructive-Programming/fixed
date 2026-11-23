# Phantom types

Unit `data` types — one zero-sized variant, no fields — used as a
phantom type parameter give you compile-time state tracking and unit
safety for free ([`spec/type_system.md` §5.9](../type_system.md)).
Tags are data, not caps: a cap declares requirements, and a tag
provides nothing to satisfy ([`spec/type_system.md`
§6.4](../type_system.md)).

## State machines in the type

A `State` parameter that appears in the `of` clause but never in a
field or method signature is inferred **phantom**. Transitions are
just functions between the phantom instantiations; the invalid ones
don't exist, so they can't be called:

```
data Locked:
    Locked

data Unlocked:
    Unlocked

data Open:
    Open

cap Door of (State):
    Self.fn new(name: String) -> Self
    fn name -> String

fn unlock(door: is Door of Locked) -> is Door of Unlocked =
    Door.new(door.name)

fn open(door: is Door of Unlocked) -> is Door of Open =
    Door.new(door.name)

// open on a locked door is not a runtime error — there is no
// function that accepts it.
```

Each transition consumes the door in one state and returns it in
another, so the old state is gone. In the runtime representation the
phantom parameter is zero-sized: `unlock` compiles to essentially a
move, and the compiler can reuse the cell in place
([`spec/memory_management.md`](../memory_management.md)).

A function polymorphic over the state works on any door — the same
type variable constrains input and output (Quine's "reflection"):

```
fn inspect(door: is Door of S) -> String =
    "Door: ".concat(door.name)
```

The pattern scales to protocol states — `Closed → Listening →
Established` for a TCP socket, with `send` only accepting
`Established` — exactly as `Locked → Unlocked → Open` above.

## Unit-safe arithmetic

Tag a quantity with its unit the same way:

```
data Meters:
    Meters

data Seconds:
    Seconds

data MetersPerSecond:
    MetersPerSecond

cap Quantity of (Unit):                    // Unit inferred phantom
    Self.fn new(value: f64) -> Self
    fn value -> f64

// Only valid when units match: U is a free variable shared by both arguments.
fn add(a: is Quantity of U, b: is Quantity of U) -> is Quantity of U =
    Quantity.new(a.value + b.value)

fn divide_units(
    distance: is Quantity of Meters,
    time: is Quantity of Seconds,
) -> is Quantity of MetersPerSecond =
    Quantity.new(distance.value / time.value)
```

`add(meters, seconds)` is a compile error; `divide_units` produces a
*different* phantom instantiation, so speed can't flow back into a
distance. All of it erases: `Quantity of Meters` is one `f64` at
runtime.

> Source: [`examples/05_phantom_types.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/05_phantom_types.fixed)
