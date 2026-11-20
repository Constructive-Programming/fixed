# P1 — Packages are the ABI boundary

**Status:** Decided 2026-11-20 (design debate with the project owner). Specified in `spec/compilation_model.md` §6–§7.
**Affects:** `spec/compilation_model.md` (new), `spec/type_system.md` §8.4, `spec/memory_management.md` §7, §18, §20.2, `spec/quantities.md` §9.3.

## Problem

The compiler picks representations from how a value is used, which reads as a whole-program decision. Libraries then could not be compiled ahead of their users, and every change to a library would force its users to recompile. Industry use needs an ABI boundary.

## Decisions

1. **The boundary is the package.** A package is compiled once and linked against without recompilation. Declarations are opaque by default: an importer sees signatures and facts, not bodies. (Rejected: a boundary per `pub` function, which is too fine to version; no boundary, with whole-program selection forever.)
2. **A floor entry per public function.** One symbol always exists, with a convention that does not depend on the body: every part passed under `own`, shared, with a value witness per owned parameter, and the callee evacuates what it stores. A body change can therefore never break a caller. (Rejected: explicit quantity annotations at the edge. Quantities and demands stay unwritten, `spec/quantities.md` OQ-Q2.)
3. **Further versions are exported and never disappear.** Ownership combinations and classes, within the clone budget (P2). A version a later build would not produce stays as an alias or an adapter into the floor entry. (The owner asked for the full matrix of conventions; under demands the caller's only choice is on `exclusive` parts, so the matrix is the `2^e` ownership combinations, times the classes of its owned parameters, bounded by the budget.)
4. **Capability tables follow the closure rule.** Two code pointers per entry, shared and exclusive, chosen statically by the caller (`spec/memory_management.md` Rule PR5.6).
5. **Coherence per link unit.** At most one satisfaction per (data type, capability) pair in a link unit; visibility stays scoped by `use`. (Rejected: tagging every value with the satisfaction it was built under.)
6. **Fixed keeps reference counting.** Neut's copy-on-share is used in one place only: the shared entry of a `fip fn`.

## What this buys

A library may change the representation of any value its users see only through a capability, without an ABI break: no user ever knew it. Weakening a declared contract is an ABI change that a comparison of two interfaces detects.

## Consequences and open points

- `type_system.md` §8.4 said there was no coherence requirement; it now has one.
- A `fip fn` had no shared version; it now has a copying shared entry, so that the floor always exists.
- Visibility syntax and the marker for shipping a body do not exist yet (OQ-CM1). Until they do every top-level declaration is public.
- Whether a package may declare a satisfaction for a type and capability it does not own is open (OQ-CM5).
