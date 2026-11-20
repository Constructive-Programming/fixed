# P4 — An interface tier and a body tier, instead of a serialised typed tree

**Status:** Decided 2026-11-20 (design debate with the project owner). Specified in `spec/compilation_model.md` §7.
**Affects:** `spec/compilation_model.md` (new), `spec/properties.md` §4.1, Rules P7.4 and P7.7.a, `spec/memory_management.md` Rule MM20.2.

## Problem

The proposal was an equivalent of Scala's TASTy, so that later stages of the compiler do not lose information. Two different needs were inside it.

- **Within one compilation** nothing has to be serialised for a later pass to see it: the typed tree, the type table and the resolution table stay in memory, and Core carries source spans. In Scala's own compiler, later phases do not read TASTy either; they keep typed trees.
- **Between compilations** a package needs to tell its importers what they may rely on, and, where it chooses to, give them enough to specialise its code.

## Decisions

1. **Interface tier, always shipped.** Signatures; the signature facts of Rule MM20.2 for the floor entry and each exported version; cost contracts and their statuses; capability table layouts; symbolic cardinality; the satisfactions declared; and a transitive content hash per declaration. It is the description of the ABI.
2. **Prop bodies are in the interface tier, as typed expressions.** A package that satisfies an imported capability must verify that capability's props for its own type, so they cannot be optional. The pure subset a prop body uses is small and stable.
3. **Body tier, optional per function: the polymorphic Core of the baseline version.** An importer can then clone the function as if it were its own. Shipping a body makes it part of the ABI. (Rejected: the typed tree, which keeps everything but changes with every typer milestone, needs its side tables shipped too, and makes each importer lower it again; Core only, with props lowered to Core, which loses what the prover and counterexample reports need; source text, which an importer would re-type in its own scope, where satisfactions can resolve differently.)
4. **The typed tree is not serialised.**

## Found while writing the spec

- The prop verification cache (Rule P7.4) was keyed on the prop body alone. A change in a function the prop calls did not invalidate a `tested` result. It is now keyed on the content hash.
- Default-bodied capability methods need no body tier to be usable: a satisfying type's table points at the compiled default.

## Open

The on-disk encoding (OQ-CM2), and how a function is marked for the body tier (OQ-CM1).
