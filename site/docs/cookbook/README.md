# Cookbook

Fixed in the small: runnable patterns for the questions that come up
most often, one page per feature cluster. Every recipe is lifted from
the [`examples/`](https://github.com/Constructive-Programming/fixed/tree/main/examples)
corpus — programs the compiler's own test suite parses, types and (for
the backend slice) compiles and runs on every build — so the syntax is
exactly what the current toolchain accepts.

1. **[Capabilities, not data](capabilities-not-data.md)** — the core
   move: declare what values can *do* with `is` bounds, bundle
   capabilities with type aliases, and let the compiler pick the
   representation.
2. **[Error handling without data](error-handling.md)** — `Optional`
   and `Result` as capabilities, the `Fail` effect for propagation,
   and `handle` to convert between them.
3. **[Recursion schemes](recursion-schemes.md)** — functions in Fixed
   are total and never self-recurse; `fold`, `unfold`, `para` and
   hylomorphisms carry all the recursion.
4. **[Working with JSON](working-with-json.md)** — a recursive `data`
   type, deep pattern matching, and folding to pretty-print without
   recursion.
5. **[Phantom types](phantom-types.md)** — state machines and
   unit-safe arithmetic enforced at compile time, zero runtime cost.
6. **[Effects and handlers](effects-and-handlers.md)** — effect rows,
   nested handlers, and concurrency as ordinary algebraic effects —
   no async/await, no built-in scheduler.
7. **[Properties](properties.md)** — `prop`, `forall` and `implies`:
   invariants as machine-checked documentation, and refinement types
   via cap-generating functions.

If you arrive with a task rather than a feature in mind, start here:

| I want to… | Recipe |
|------------|--------|
| write one function over any collection | [Ask for the capability](capabilities-not-data.md#ask-for-the-capability) |
| force a contiguous representation | [Bundle capabilities](capabilities-not-data.md#bundle-capabilities) |
| propagate errors without `Either` chains | [Propagate with the `Fail` effect](error-handling.md#propagate-with-the-fail-effect) |
| turn an effect back into a value | [Handle to `Result`](error-handling.md#handle-to-result) |
| traverse a recursive structure | [Fold it](recursion-schemes.md#fold-catamorphism) |
| build a structure from a seed | [Unfold it](recursion-schemes.md#unfold-anamorphism) |
| rebuild one path of a tree, keep the rest | [Use a paramorphism](recursion-schemes.md#para-paramorphism) |
| parse / pretty-print a nested format | [Working with JSON](working-with-json.md) |
| make an illegal state unrepresentable | [Track state in the type](phantom-types.md#state-machines-in-the-type) |
| stop meters being added to seconds | [Tag units](phantom-types.md#unit-safe-arithmetic) |
| compose I/O, logging and failure | [Stack handlers](effects-and-handlers.md#nested-handlers) |
| spawn tasks without async/await | [Concurrency as effects](effects-and-handlers.md#concurrency-as-effects) |
| pin an invariant next to the type | [Declare a `prop`](properties.md#properties-on-capabilities) |
| constrain values without wrapper types | [Refinements via `fn -> cap`](properties.md#refinement-types) |

These are worked examples, not a tutorial — for the semantics behind
each feature see the [specification](../type_system.md) it cites.
