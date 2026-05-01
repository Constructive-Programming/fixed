# L7 — Mutual data SCC auto-derivation

**Status:** Open. Pick one option.
**Affects:** spec/type_system.md Rule 7.4 (auto-derivation), §7.5 (SCC handling).

## Problem

Two mutually recursive `data` types form an SCC:

```
data Tree:
    Leaf
    Branch(node: Node)

data Node:
    N(tree: Tree, value: i64)
```

Neither has a type parameter. Rule 7.4.c says "auto-derive Functor over the rightmost type parameter, iff non-phantom." Neither member qualifies. But Rule 7.4.a says "auto-derive `fold` for any sum type" — Tree has variants, Node has variants, both qualify for `fold`. The SCC complicates this because they share representation (`type_system.md` §6.5.8: SCCs are co-allocated).

Two questions:
1. Does auto-derivation apply per-member or at the SCC level?
2. If per-member, are the auto-derived methods mutually consistent across the SCC?

## Options

### Option 1 — Per-member rules apply independently

Each member's auto-derivations follow Rule 7.4 looking only at that member's declaration. Tree gets `fold` (sum over its variants), Node gets `fold` (sum over its single variant). Neither gets Functor (no type parameters).

**Pros:** simplest. Predictable: same rule everywhere.

**Cons:** `Tree.fold` consumes a `Tree` and returns... what? Each variant carries fields whose types include `Node`. So `Tree.fold`'s callback for `Branch` takes a `Node` argument. The user can then call `Node.fold` inside that callback. Mutual recursion is expressible but requires the user to interleave folds.

### Option 2 — SCC-level mutual fold

The compiler emits a *mutual fold* `(Tree.fold, Node.fold)` pair: each takes callbacks for both types' variants. Calling `Tree.fold(...)` automatically threads the Node callbacks where Tree variants contain Nodes.

**Pros:** matches the catamorphism-on-mutual-recursive-types theory. User writes one combined fold call, gets both types collapsed.

**Cons:** more complex spec. Auto-generation of "mutual fold" must handle arbitrary SCC sizes (3, 4, 5 mutually recursive types). API surface explodes.

### Option 3 — Refuse and require explicit

Auto-derivation does not apply to data types in non-singleton SCCs. Users must write explicit `satisfies` / `fold` declarations for mutually recursive types.

**Pros:** avoids the design question entirely.

**Cons:** mutually recursive data is a real use case (ASTs with multiple node kinds, parse trees). Forcing manual `fold` for every SCC is friction.

## Recommendation

**Option 1** (per-member rules apply independently).

Rationale:
- Mutual fold (Option 2) is theoretically clean but the spec / implementation cost is high. Most real-world mutually recursive types have small SCCs (often 2 members) where Option 1's interleaving is fine.
- Option 1 is forwards-compatible — Option 2 can be added later as a *convenience* on top of Option 1's primitives without breaking anything.
- Option 3 fights the language's "everything that can auto-derive, does" philosophy.

Spec edits if Option 1 chosen:
- `type_system.md` Rule 7.4: explicit clarification: "Auto-derivation rules apply per-data-decl. SCC membership and co-allocation (§6.5.8) affect representation but not auto-derivation. Mutually recursive data types each get their own `fold`/etc. according to their individual shape; combined recursion patterns (mutumorphisms) are expressed by interleaving the per-type folds."
- Add example: a Tree/Node SCC, separate `fold` calls, manual interleaving.
- New OQ entry: "Mutual-fold convenience (deferred). May be added as a generated `(Tree, Node).fold(...)` combinator if usage warrants. Backwards-compatible extension."
