# P2 — A polymorphic baseline, with specialisation on top

**Status:** Decided 2026-11-20 (design debate with the project owner). Specified in `spec/compilation_model.md` §3–§5.
**Superseded in part by P6** (`docs/decisions/P6-resolution-round-2026-11-20.md`): ownership clones ranked by score are replaced by P6 D15 (requested only where a run-time `rc == 1` test cannot stand in); the lexical weight of decision 5 is replaced by P6 D15 (the weight of the requesting function's own call sites, carried along call edges).
**Affects:** `spec/compilation_model.md` (new), `spec/memory_management.md` §7.3, §7.4, §8.1, §20.3, `spec/quantities.md` §4, §9.3, `spec/type_system.md` §6.5, §7.4, `spec/effects.md` §7.

## Problem

The backend monomorphised every function from `main` during lowering, and treated code it could not monomorphise as a special case. With a package boundary (P1) the special case is the general one: a public function over a capability bound has no type instance to be monomorphised for. The owner's requirement: the compiler must always be able to consume Neut-structured polymorphic code, and generate specialised versions where it can identify the types that matter, avoiding the plumbing cost there. That is also what profile-guided optimisation is for.

## Decisions

1. **Baseline.** Every function has one baseline version: polymorphic, witness-passing, shared-ownership, correct for every lifetime class. It always exists and is always a correct callee. The model inside a package and at its edge is the same; the edge only decides whether the specialiser can see a body.
2. **Uniform slot: 64 bits on every target.** A value of at most 64 bits is stored in the slot; anything wider is behind a pointer. (Rejected: witness-sized values with offsets computed at run time, as Swift does. It avoids boxing but makes every generic field access a computation, keeps values out of registers, and makes reuse fingerprints dynamic. Rejected: a pointer-width slot, which would box `u64` and `f64` on 32-bit targets.)
3. **Cloning never changes a layout.** Only representation selection does, and a representation is identified by its witness. Values therefore cross between baseline and clones without conversion, which is what makes "specialise up to a point" possible.
4. **One size budget per function.** Each clone of `f` is charged the size of `f`'s baseline; the sum stays under `B`. One number covers types, ownership, classes and handlers, and a function larger than `B` gets no clones. It replaces the version cap of 4 and the large-function rule. (Rejected: a count cap per function, which ignores size; a cap per axis, whose product is the worst case; a global size budget, under which an edit in one file can evict a clone in another.)
5. **A score chooses within the budget.** `score = witness operation sites removed × weight ÷ size`, with the weight from the `fold` nesting depth of the requesting call sites. Profile data later replaces the weight with measured counts; the formula stays. (Rejected: no clones without a profile; first come, first cloned, which depends on traversal order; a fixed rank list, which profile data would throw away.)
6. **A no-clone mode** (`--no-specialise`) for fast builds and as the reference behaviour clones are compared against.
7. **The body tier is polymorphic Core** (see P4).

## Corrections made while writing the spec

- Fusion and `fold`-step inlining (`type_system.md` Rules 7.4.b.1–2) and `fip fn` are **mandatory specialisations**: outside the budget, in every build mode.
- A known handler is a fourth component of a clone key (`effects.md` §7.6 used to attribute it to monomorphisation).
- An `of` value parameter that a baseline version reads is a hidden argument there.
- The cell header needs a reference map, because a slot of parameter type may hold an immediate or a reference.
- A call through a capability table passes every argument under `own` and so cannot borrow. A clone borrows again. Whether a requirement should be able to state `read` is open (OQ-CM8).

## Reference values (provisional)

`B = 512` Core bindings; weight base `W = 8`. Chosen before measurement (OQ-CM3).
