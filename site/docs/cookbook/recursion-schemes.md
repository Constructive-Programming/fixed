# Recursion schemes

Functions in Fixed are **total**: they never self-recurse. All
recursion lives in the data type's auto-derived `fold` / `unfold` /
`para` ([`spec/type_system.md` §7.4](../type_system.md)) — which is
what makes termination checkable and lets the compiler fuse, fork, or
run recursion in-place.

## Fold (catamorphism)

`fold` collapses a structure bottom-up: each variant gets a callback,
and recursive positions arrive **already folded** to the result type:

```fixed
data Expr:
    Lit(value: f64)
    Add(left: Expr, right: Expr)
    Mul(left: Expr, right: Expr)
    Neg(inner: Expr)

fn eval(expr: Expr) -> f64 =
    expr.fold(
        (v) -> v,                        // Lit: just the value
        (left, right) -> left + right,   // Add: children already folded to f64
        (left, right) -> left * right,   // Mul: children already folded to f64
        (inner) -> -inner,               // Neg: child already folded to f64
    )
```

No recursion, no accumulator gymnastics — the depth of `eval` is one
expression, however deep the tree is.

## Unfold (anamorphism)

The dual: build a structure top-down from a seed. The step function
returns one *layer*, with new seeds where the recursive children go:

```fixed
fn to_nat(n: u64) -> Nat =
    Nat.unfold(
        n,
        (remaining) ->
            if remaining == 0:
                Nat.Zero
            else:
                Nat.Succ(remaining - 1)
    )
```

Unfold-then-fold is a **hylomorphism** — the intermediate structure is
fusable and never needs to exist in memory. Factorial as a hylomorphism:

```fixed
fn factorial(n: u64) -> u64 =
    let sequence = ListF.unfold(
        n,
        (remaining) ->
            if remaining == 0:
                ListF.Nil
            else:
                ListF.Cons(remaining, remaining - 1)
    )
    sequence.fold(
        () -> 1,
        (head, tail) -> head * tail
    )
```

## Para (paramorphism)

A plain `fold` gives you the folded children with no way back to the
originals. When a step must *keep* one subtree verbatim — inserting
into a binary search tree rebuilds the descent path and preserves the
other branch — use the auto-derived `para`, which provides each
recursive position as `(original, folded)`
([`spec/type_system.md` Rule 7.4.g](../type_system.md)):

```fixed
fn insert(tree: Tree of (A is Ord), value: A) -> Tree of A =
    tree.para(
        (v) ->
            value.compare(v).fold(
                () -> Tree.Branch(Tree.Leaf(value), v, Tree.Empty),  // lt
                () -> Tree.Leaf(v),                                   // eq — no duplicates
                () -> Tree.Branch(Tree.Empty, v, Tree.Leaf(value)),  // gt
            ),
        (left, left_ins, v, right, right_ins) ->
            value.compare(v).fold(
                () -> Tree.Branch(left_ins, v, right),    // lt: rebuild left, keep right verbatim
                () -> Tree.Branch(left, v, right),        // eq: no change
                () -> Tree.Branch(left, v, right_ins),    // gt: keep left verbatim, rebuild right
            ),
        () -> Tree.Leaf(value),                            // Empty
    )
```

`para` also pays off for search: choosing which already-folded result
to propagate at each `Branch` keeps the descent on one path.

## When you need a loop

Because functions can't self-recurse, an unbounded `while` loop is a
type error, not a missing feature. Bound the loop by folding a `Nat`
unfolded from an iteration count — the chat-room loops in
[Effects and handlers](effects-and-handlers.md#concurrency-as-effects)
show the pattern. A codata story for truly unbounded loops is the
open question this works around
([implementation plan, OQ12](https://github.com/Constructive-Programming/fixed/blob/main/docs/plans/implementation-plan.md)).

> Source: [`examples/07_recursive_data.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/07_recursive_data.fixed)
