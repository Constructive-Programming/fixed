# Frame and heap memory safety

The first memory milestone is checked in Lean: **closing an innermost frame
leaves all surviving roots and object fields pointing only to live storage**.
This formalizes the nested-frame part of `spec/memory_management.md` MM4.1 and
normal scope exit in MM5.1.

The second milestone adds **heap ownership tickets, scoped borrows, and exact
release of frame drop obligations**, following MM4.2, PR3.2–PR3.4, PR5.5, and
PR8.2. A combined machine checks both protocols with synchronized scope exit.
The third milestone adds **immutable heap fields, recursive drops, and concrete
reference-count headers**, with a proved refinement from concrete execution to
the owned-field graph. These are safety and refinement proofs, not verification
of the runtime or memory-analysis pass.

Run `cd formal && lake build`. The default build checks the model, proofs,
regression examples, and axiom dependencies.

## Machine and checker

`Fixed/Memory/Frames.lean` defines a store indexed by logical addresses, nested
frame depths, a fresh-address counter, and scoped roots. Smaller depths outlive
larger depths. Frame 0 is the base scope and cannot be closed by an operation;
it does not represent Fixed's `Static` class. The initial state has no cells or
roots. Allocations use monotonically increasing identities, even across resets.
A new dynamic frame may reuse a depth but cannot revive a reclaimed address.

The five operations are:

- `enter`: open an inner frame.
- `reserve frame`: reserve a fresh address in any active frame. Choosing an outer
  frame models the placement aspect of a caller-supplied destination. The address
  is the state's old `next` counter.
- `init address fields`: initialize a reserved cell exactly once. Every target
  must already exist in the same frame or an outer frame. Completed fields are
  immutable. Reserving before initialization permits an older cell to point to
  a younger cell in the same frame, as MM4.1 requires.
- `root frame address`: register a pointer held by that scope. The target must
  exist and outlive its holder. This models the lifetime obligation of a local,
  returned reference, or externally held closure. Captures stored in an object
  use the ordinary field rule.
- `leave`: remove all cells and roots of the current inner frame and return to
  its parent. Heap references are absent from this isolated frame store;
  `Combined` pairs this operation with heap cleanup.

`Step` specifies permitted transitions through local premises. `execute` checks
those premises and returns a successor or `none`; `run` checks a finite list and
fails at the first rejection. Neither checks the global invariant by scanning
all allocated cells. `Valid` separately states freshness, live placement, and
containment for every object field and root.

## Guarantees

- `execute_iff`: the executable checker accepts exactly the transitions in `Step`.
- `accepted_preserves` and `run_preserves`: accepted operations preserve `Valid`.
- `reset_lookup_iff`: a cell survives reset exactly when it belongs to an outer
  frame, and its complete record is unchanged. `reset_removes` proves reclamation
  of current-frame cells.
- `reset_no_dangling`: every address reachable from a surviving root still
  designates the same live outer-frame cell.
- `accepted_no_dangling`: every successfully checked sequence from the initial
  state has no dangling root-reachable pointer. The stronger `Valid.edges`
  invariant also covers fields in objects not reachable from roots.
- `run_absent_stays_absent`: an absent address below the fresh counter stays
  absent through all subsequently accepted operations. Frame-depth reuse cannot
  turn a stale logical pointer into a pointer to a different allocation.

`Fixed/Memory/Examples.lean` checks accepted same-frame and outer-frame references,
destination placement, exact reset results, and rejection of escaping roots,
escaping fields, fabricated pointers, inactive frames, repeated initialization,
base-frame reset, and stale-address use after reopening a frame.

## Heap ownership and borrows

`Fixed/Memory/Heap/Model.lean` adds a separate machine with independently stored
heap liveness, monotonically fresh object addresses and ownership tickets, and
a list of active scoped borrows. Each ticket maps to an owner record containing
one object address and the frame responsible for releasing that obligation.
Several tickets can own the same object. A frame's drop list is the finite
projection of tickets whose owner record names that frame; it is not a second
mutable list that could fall out of sync with the ledger.

The heap operations are:

- `alloc frame`: create a fresh opaque object and one fresh ownership ticket.
  Their identities are the old `nextAddr` and `nextTicket` counters.
- `dup ticket frame`: create another ticket owning the same object, registered
  to the specified active frame. It does not copy the object.
- `move ticket frame`: change the responsible frame, preserving ticket identity,
  object address, and ownership count. Every active borrow tied to that ticket
  must still fit within its owner's lifetime.
- `borrow ticket frame`: register a borrow backed by that ticket. Its frame must
  be active and cannot outlive the owner. No ownership obligation is added.
- `drop ticket`: remove an existing, unborrowed ticket. Free its object exactly
  when no other ticket owns it.
- `enter` and `leave`: open a scope, or end its borrows, discharge its ownership
  obligations, and reclaim objects with no remaining owner. Base-scope exit is
  rejected, as in the frame machine.

Borrows last until their specified scope exits; there is no early-end operation
yet. Repeated borrows are allowed. A borrow is tied to a particular ticket, so
another alias keeping the object alive does not authorize dropping that ticket
or moving it to a scope shorter than the borrow. Moving ownership outward and
duplicating into an outer frame are supported ways to keep a heap result alive.
Ownership is independent of the 0/1/many quantity algebra.

As with frames, `Step` has local premises, and `execute_iff` proves the checker
accepts exactly those transitions. The separate `Valid` invariant requires
fresh/live tickets, live objects below the address counter, a live object
**iff** at least one ticket owns it, and a covering owner for every active borrow.
Collection queries the finite ledger to compute the final live heap. It is a
specification-level bulk operation, not a proposed runtime scanning algorithm.

## Heap guarantees and combined scope exit

- `Heap.accepted_preserves` and `Heap.run_preserves` establish invariant
  preservation for single operations and finite accepted sequences.
- `Heap.borrow_live` proves every registered borrow actually has a backing owner
  and a live target. `Heap.accepted_no_dangling` covers modeled owned and borrowed
  access from the empty state.
- `Heap.drop_live_iff` and `Heap.reset_live_iff` characterize exactly which objects
  survive cleanup. `Heap.last_owner_freed` proves reclamation at the last owner;
  other owners keep a shared object live.
- `Heap.reset_borrow_live` proves a surviving borrow keeps the same owner record
  and points to a live object after reset.
- `Heap.reset_exactly_once` proves the cleanup receipt list has no duplicates
  and contains exactly the tickets of the closing frame. `transfer_dropList`
  proves a moved ticket belongs to its new frame's drop list.
- `Heap.no_double_release` proves a released ticket cannot occur in any cleanup
  receipt list after a checked continuation. Both explicit drop and frame
  cleanup are instances of this collection theorem. Object identities also
  cannot revive (`run_heap_absent_stays_absent`).
- `Heap.addOwner_count`, `transfer_count`, `borrow_count`, and `drop_count` prove
  the expected effects on the number of owning tickets: +1 for allocation/dup,
  unchanged for move/borrow, and −1 for dropping an existing ticket.
  `collect_balance` proves per-object conservation: retained obligations plus
  released obligations equal the original count, including shared aliases.

`Fixed/Memory/Combined.lean` pairs the frame and heap machines and requires equal
scope depths. Its `enter` and `leave` act on both components. Individual component
commands may not change scope depth. `Combined.execute_iff` connects this checker
to the combined transition relation; `Combined.accepted_no_dangling` proves live
frame references, live heap accesses, and backing owners for all remaining
borrows in accepted mixed traces. Scope exit is atomic here: the final state
models ending local borrows and discharging heap obligations before reclaiming
frame storage. No intermediate runtime instruction ordering has been verified.

`Fixed/Memory/Heap/Examples.lean` checks 40 heap/combined cases: shared aliases,
last-owner reclamation, ownership returned to a caller or moved into a callee,
borrow lifetimes, repeated borrows, moved drop obligations, exact receipts, stale
tickets, invalid scopes, and paired frame/heap cleanup. These are kernel-checked
`by decide` examples, included in the default build alongside the theorem audit.

## Heap fields, recursive drops, and concrete headers

`Fixed/Memory/Graph/Model.lean` defines an independent immutable heap DAG. Every
root occurrence and every field occurrence owns one reference; duplicate fields
therefore have multiplicity. Construction requires rooted, already completed
children, and validity requires every edge to point to a lower live address.
`Release` and `Releases` specify last-owner recursive cleanup, while `releaseFuel`
is the executable left-to-right traversal.

`Fixed/Memory/Graph/Safety.lean` proves exact root-multiplicity accounting,
validity and subheap preservation, deterministic release, and `release_total`:
the `next` fuel bound suffices for every valid rooted DAG. `execute_drop_iff`
connects executable and declarative drops, and `accepted_no_dangling` covers
roots after accepted finite traces.

`Fixed/Memory/Graph/Rc.lean` gives each cell an independently stored natural-
number `count`. `Valid.headers` requires this count to equal the exact number of
incoming root and field references. Allocation increments each child once per
field occurrence, `dup` increments one root target, shared drop decrements one
count, and last-owner unlink transfers outgoing edges to temporary roots without
changing child counts. Execution compares only the local header with one; it
does not scan the heap to decide whether to free.

`Fixed/Memory/Graph/RcRefinement.lean` proves that concrete allocation,
duplication, and fueled recursive drop erase to the corresponding graph
operations while preserving concrete validity. `Rc.accepted_refines` extends
this to finite runs from the empty state. `Rc.accepted_release` connects a
concrete drop to declarative graph release, and `Rc.accepted_no_dangling` shows
that every remaining concrete root has a live cell. The graph and RC regression
modules check shared aliases, duplicate fields, branching and recursive cleanup,
exact counts, and stale-root rejection.

The RC model uses unbounded natural numbers and logical addresses. It does not
model finite-header overflow, tags, byte layout, alignment, allocation failure,
static negative counts, or exclusive-versus-shared specialization.

## Resource-aware core execution

`Fixed/Memory/Resource/` combines concrete RC cells with scoped frames, closure
code metadata, and core terms containing runtime values. `executeStep` implements
literal/closure allocation, left-to-right application, beta substitution,
conditional cleanup, and empty-elimination congruence. `runSteps` canonicalizes
each resulting frame and stops when the one-step checker returns `none`.

`executeStep_config` and `runSteps_config` preserve `ConfigValid`: exact frame
roots, live RC cells, complete closure metadata, and coherent current-frame slots.
`executeStep_erasure` and `runSteps_erasure` connect accepted execution to the
core's `Step`/`Steps`; allocation stutters while the other rules mirror core
reduction. `ofCore_runSteps_config` and `ofCore_runSteps_erasure` specialize these
results to source terms from the empty state. `Lowering.lean` adds quantity-
annotated terms and contexts. Its checked `lower` erases annotations, invokes the
verified core type checker, and accepts only locally and externally bounded
usage. `lower_sound`, `lower_runSteps_config`, and `lower_runSteps_erasure` connect
that checker to typing, resource preservation, and core `Steps`. Quantity usage
is a static bound, not a claim about exact transient RC counts; this is not a
proof of the Scala quantity pass. Capture usage follows Q5.7: a returned closure
scales captured uses by its result quantity, while an immediately called closure
runs once. `Erasure.lean` separately implements Q6.1 for closed direct-call
terms. Pure zero-argument calls with closed bodies are removed by `eraseZero`;
`eraseZero_correct` proves quantity-zero reduction, and `eraseLower` checks
closedness and direct calls. Indirect applications and open-body redexes are
rejected rather than assigned de Bruijn-unsafe semantics. The erasure examples
show an unused closure argument allocated before erasure and no allocation after.
`Progress.lean` proves `executeStep_progress` for typed configurations and
`runChecked_good`, with `runChecked_ne_rejected` and `runChecked_ne_stuck` as
corollaries. This is progress, not merely preservation: a reachable well-typed
configuration is a value or can step. Fuel exhaustion remains a separate
`timeout`, distinct from resource rejection and source-level stuckness.
`backend/FormalExport.scala` exports only the
actual compiler's pure unit/bool fragment with single-parameter lambdas,
application, and conditionals. `Integration/CompilerExport.lean` contains the
synchronized `QTerm` definitions and execution proofs; `FormalExportSuite` fails
if Scala output and that fixture diverge. Unsupported syntax and free or
top-level identifiers are rejected. This checks the AST-to-`QTerm` boundary, not
backend lowering, generated LLVM, or the C runtime. The erasure proof requires
closed core terms and closed runtime values, since runtime replacement does not
rename inside closure values. Fuel-bounded execution is not a termination proof,
The older `runSteps` helper remains fuel-bounded and conflates normal completion
with rejection; `checkedStep` and `runChecked` make value, timeout, stuck, and
rejected outcomes explicit.

## Boundaries and next extension

The frame, ticket, and owned-field graph machines remain separate from the core
term evaluator; the resource machine is the checked core connection described
above. Every externally held frame pointer must be represented by a scoped root for the
root-reachability theorem to cover it. There is no proof yet that compiler roots,
closure captures, destination arguments, or emitted operations satisfy this
representation. Manually constructed states need a `Valid` proof; only states
reached from `initial` inherit that guarantee automatically.

The frame and ticket stores remain disjoint, and the ticket machine's objects
remain opaque. The new graph/RC machine is independent rather than another field
on `Heap.State`; it is not composed with scoped borrows or synchronized frame
exit. Scoped heap owners and borrows represent external handles, including
obligations that a future lowering might attach to frame values. There is no
representation theorem connecting any of these machines to pointer slots in
frame objects, mixed aggregates, or closures. Heap-to-frame exclusion and mixed
frame/heap aggregates still need a composed model.

Ticket identities are specification-level capabilities in a global machine, not
source variable bindings or an ABI. Moving a ticket changes its responsible scope;
the model does not prove that a compiler invalidates the former source binding.
It checks lifetime and release discipline for operations supplied to the machine,
not borrow inference or caller access authority.

Reserved frame cells are allocated storage and may be referenced during construction.
This proves address lifetime safety, not that a program only reads fully
initialized, correctly typed values. Frame graph cycles and sharing are allowed;
there is no alias-sensitive mutation or exclusive reuse. Fresh identities
abstract away physical address reuse,
finite-address overflow, byte layout, alignment, allocation failure, and allocator
correctness. Normal frame exit is modeled; tail calls, panic unwinding, handlers,
continuations and parallel lanes are not.

The ticket machine's ownership count is still derived from its ledger; the RC
refinement targets the owned-field graph, not a proved translation of that
ledger. Cleanup receipts specify which ticket obligations are released once, not
execution of destructors or a concrete frame drop-list traversal. Neither model
proves that programs eventually release all owners: the base scope can retain
tickets, graph roots can remain live, and executions can keep running. This is
not whole-program leak-freedom. Concrete RC correctness is proved for the Lean
machine, not for `Rc.scala`, generated LLVM, or `fixed_rt.c`.

The next extensions are frame/heap composition, counted regions, evacuation,
exclusive reuse, and checked connection to compiler lowering. The current C
runtime uses recursive native calls for field drops; neither tail-call behavior
nor its header operations are verified here. Address ordering is only a
construction-model termination device, not a language restriction on future
destinations or reuse. Core execution still assumes no active borrows and uses
RC-heap closures; it does not establish Fixed's mostly headerless, borrow-heavy
execution model. Full-language memory safety and compiler-to-native-runtime
simulation remain open.
