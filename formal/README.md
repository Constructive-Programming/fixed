# Fixed in Lean: verified core and memory protocols

This is a starting point for formalizing Fixed, **not a proof of the full language
or its Scala compiler**. It is a standalone Lean 4.19.0 project with no external
library dependencies. The core supports variables, annotated functions,
application, unit, booleans, conditionals, and empty elimination. A separate
[memory model](Memory.md) proves reference safety for checked frame allocation,
construction, scoped roots, heap ownership, borrows, scope cleanup, immutable
heap fields, and recursive drops. It also refines concrete reference-count
headers to the owned-field graph. The frame and ticket machines compose with
synchronized scope exit. A resource-aware machine connects RC headers, frames,
closures, and recursive drops to the modeled core evaluator; neither machine is
connected to compiler lowering.

With [elan](https://github.com/leanprover/elan) installed:

```sh
cd formal
lake build
```

The default build includes the examples and axiom audit. `Fixed/Audit.lean` uses
`#guard_msgs` to check the exact axiom dependencies of the principal results;
introducing an admitted proof or a new axiom into those results fails the build.
The core results depend only on Lean's standard `propext`. The memory safety
results also use `Quot.sound`; some checker and accounting results additionally
use `Classical.choice`. These are standard Lean axioms, with exact dependencies
guarded in the audit. There are no `sorry`, `admit`, custom axioms, or external solver oracles.

## What is proved

- `Core.lean`: extrinsic typing over raw de Bruijn terms; capture-avoiding
  renaming and simultaneous substitution preserve typing. `Step` defines
  left-to-right call-by-value reduction independently of typing.
- `preservation`: one reduction preserves the type, including for open terms.
- `progress`: a closed typed term is a value or can reduce.
- `soundness`: every state reachable by finitely many reductions from a closed
  typed term is a value or can reduce. This is absence of stuck states, not
  termination.
- `consistency`: no closed core term has type `empty`. This uses a propositional
  model (`unit` and `bool` map to `True`, arrows to implication, `empty` to
  `False`), not a normalization argument. The model intentionally forgets boolean
  values; it proves uninhabitability, not operational correctness of evaluation.
- `Checker.lean`: the executable, annotation-directed `infer` accepts exactly
  the declarative typing relation (`infer_iff`). `accepted_safe` and
  `rejects_empty` connect the checker to the safety and consistency results.
- `Quantity.lean`: the tables in `spec/quantities.md` §3 form a commutative
  semiring; exclusive-branch join is associative, commutative and idempotent;
  its induced order has the least-upper-bound property, and addition and
  multiplication are monotone. These are algebraic results, not a proof of the
  quantity pass or memory management.
- `Examples.lean`: accepted and rejected terms, a beta step, substitution under
  nested binders, empty elimination, reusable functions, and branch versus
  sequential quantities.

The frame machine adds `Fixed.Memory.execute_iff`, `accepted_preserves`,
`reset_no_dangling`, `accepted_no_dangling`, and `run_absent_stays_absent`.
These connect an executable checker with local guards to preservation of a
separate global lifetime invariant, safe reclamation, and non-revival of stale
logical addresses.

`Memory/Heap/` adds an independent live heap, ownership tickets, scoped borrows,
transfer, duplication, explicit drop, and frame drop lists. It proves preservation,
borrow liveness, exact cleanup, no repeated release, and ownership-count accounting.
These counts are computed from the specification's tickets, not runtime headers.
`Memory/Combined.lean` checks mixed traces with synchronized frame and heap scopes.
`Memory/Graph/` adds immutable owned fields and recursive drops, proves cleanup
totality and determinism, and refines explicit RC headers to that graph.
`Memory/Resource/` adds frames, closure metadata, literal and closure allocation,
beta reduction, branch cleanup, and fueled core execution over that RC machine.
`executeStep_config` and `runSteps_config` preserve resource validity;
`executeStep_erasure` and `runSteps_erasure` relate execution to core `Step` and
`Steps`, with literal/closure allocation as stutters. Erasure requires closed core
terms and closed runtime values so replacement remains capture-safe.
`Memory/Resource/Lowering.lean` adds quantity-annotated terms and contexts,
sequential/exclusive usage accounting, and checked lowering. Q5.7 scales captures
by a closure's result usage while preserving captures of immediately called
closures. `lower_sound` proves typing and usage bounds; `lower_runSteps_config`
and `lower_runSteps_erasure` extend resource preservation and core simulation.
`Erasure.lean` separately implements Q6.1 for closed direct-call terms: pure
zero-argument calls with closed bodies are erased before execution, while
indirect calls and open-body redexes are rejected. `eraseZero_correct` justifies
the transformation by quantity-zero reduction steps, and examples check that an
unused closure argument allocates before erasure but not after.
`Progress.lean` proves `executeStep_progress` for typed configurations and
`runChecked_good`: a reachable well-typed run ends as a value or timeout, never
resource rejection or source-level stuckness.
`backend/FormalExport.scala` exports the compiler's pure single-parameter AST
fragment to `QTerm`. `Integration/CompilerExport.lean` checks the synchronized
identity, capture, reuse, and conditional definitions through Lean lowering and
execution; `FormalExportSuite` checks the same definitions against actual Scala
compiler output. The default build includes heap/combined, graph/RC, frame,
resource/core, lowering, and compiler-export regression examples. See
[the model and proof boundaries](Memory.md).

## Correspondence and boundaries

`Ty.unit`, `Ty.bool`, `Ty.empty`, and `Ty.arr` model `UnitType`,
`PrimType("bool")`, the return-value aspect of `NeverType`, and pure `ArrowType`
in `compiler/src/main/scala/fixed/core/Types.scala`. Function application and
lexical binding model the simply typed fragment of `spec/type_system.md` §4.2
and §5.5. Explicit `absurd` represents empty elimination (§5.6); there is no
implicit subtyping rule. The core is monomorphic and nonrecursive. Its functions
can be copied and discarded. It contains no universe or dependent types.

This correspondence is a design mapping, not a verified translation. Neither
`Typer.scala` nor its unifier calls this checker. The `Quantity` definitions
mirror `compiler/src/main/scala/fixed/qtt/Quantity.scala`; equivalence to the Scala
implementation is also not mechanically checked.

**Runtime safety and logical consistency need different scopes.** Fixed permits
`unreachable : !` and `impossible` constructor mappings that panic
(`spec/type_system.md` §5.6, §8.2). Thus the unrestricted source language already
has a closed expression of never type. That does not make its runtime type
system unsound, but rules out treating every source expression as a logical
proof. The core omits panic and general recursion. No termination theorem is
included yet. An empty effect row alone must not be assumed to certify totality
or panic-freedom in Fixed. `tested` and `assumed` properties must not enter the
logical context as proofs (`spec/properties.md` §7.7).

## Inspiration and next proof obligations

[BendTT](https://github.com/bendlang/bend/blob/main/paper/BendTT.pdf) ties its
metatheory to an executable checker and separates live from erased code. We
follow the checker-to-theorem connection. Its termination argument restricts
copying functions and recursive calls; Fixed permits reusable closures, so we
cannot import that argument or infer that `Type : Type` is safe here. We have
not ported or verified BendTT's mechanization.

[Koka's effect paper](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/koka-effects-2013.pdf)
connects effect typing with operational guarantees such as absence of unhandled
exceptions. The next extension should give Fixed computations explicit outcomes
(value, panic, operation request), latent arrow effects, and handler semantics;
prove preservation and progress with those outcomes. Fixed's rows are sets
(`spec/effects.md`), whereas this Koka paper permits duplicate labels. Row
operations must follow Fixed rather than silently adopting Koka's rules.

[Neut](https://vekatze.github.io/neut/overview.html) translates types into
copy/discard functions and uses them to linearize runtime use. For Fixed this
suggests a separate resource-aware target and a semantics-preserving translation
from the quantity-annotated core. The finite algebra and the small-core resource
machine are prerequisites; quantity-indexed elaboration and destination
construction still need proofs.

[Goose's specification](https://aardappel.github.io/goose/docs/goose_spec.html)
is the van Oortmerssen language cited in Fixed's memory spec, not the unrelated
Go verification project. Its stack, root, and outlives rules inform a later heap
and region invariant. The memory machines now formalize nested-frame containment,
heap ownership and borrow obligations, normal scope cleanup, recursive heap
fields, and concrete RC headers. Counted regions, frame/graph composition, and
compiler-generated memory operations remain outside these proofs.

The remaining work should proceed in independently checked extensions:

1. Products, sums, and positive recursive data with explicit folds. Prove their
   typing and reduction rules; separately prove normalization for the chosen
   logical fragment before admitting recursive proof definitions.
2. Effectful computations and handlers, including abortive `!` operations and
   single-shot resume. Resolve the quantity/effect interaction called out in
   OQ-Q5 before claiming resource preservation for handlers.
3. Capability dictionaries, `satisfies`, and representation selection. Show
   elaboration preserves types and selected representations fulfill obligations;
   address coherence before claiming equivalent elaborations behave the same.
4. Compose the verified graph/RC machine with scoped frames and ticket
   obligations, then add counted regions and reuse. Extend the checked
   quantity-indexed lowering to the actual compiler contexts and verify
   destination construction.
5. Refinements and dependent parameters with a defined total proof fragment and
   checked evidence for `proved` properties. PBT acceptance supplies no evidence.
6. A checked elaboration boundary from the actual compiler's typed trees to the
   modeled core, followed by preservation through lowering and code generation.

Each extension must restate the theorem scope and keep the checker, operational
semantics, and proof obligations aligned. The current verified slice deliberately
leaves these obligations open rather than postulating their correctness.
