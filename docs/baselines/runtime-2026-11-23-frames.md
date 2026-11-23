# Runtime baseline, 2026-11-23: frames, first increment

Frame allocation for the scope of a function activation (`spec/memory_management.md` §3.1 `Frame(s)`,
§5.3, §6.1–§6.2), against the same commit with `--no-frames` and against `base3` (commit `d646b53`,
same machine and method).

- Commits: `aebdbb3` (runtime data stack), `62860e1` (placement)
- Machine: Linux 7.2.15-gentoo-x86_64 x86_64, AMD RYZEN AI MAX+ 395 w/ Radeon 8060S; `clang -O2`
- Instructions: one run (deterministic). Cycles: minimum of 5 runs, read from the CPU's counters.
  Allocations and peak RSS: `FIXED_RT_STATS=1`. Cycles move by about ±2% between identical binaries.

## What is placed where, and what decides it

`backend/Frames.scala`, after `Consume` and before `Aborts` and `Rc`, decides per function:

- **A constructor goes on its function's frame** when `Memory` classes it `Frame(f)` and a local use
  analysis proves it again. The analysis follows the value and every variable that holds it or a
  part of it (an alias, a counted field read, a branch's result). Each use must be one of:
  - a field read or a `match`;
  - a field of another cell placed on the same frame;
  - an argument of a direct call whose parameter is **not kept**.

  A parameter is kept if it, or a part of it, reaches anything else in the callee. This is a least
  fixed point over the call graph, so a parameter only passed round a recursion is not kept.
  Anything else keeps the value on the heap: returned, captured, passed to a closure, a table, the
  runtime or an abort, named as the cell of a reuse, or passed to a self call in tail position (a
  jump that resets the frame, Rule MM5.1).
- **A frame cell refers only to frame cells of its activation or to static cells**, whatever its
  field types (a slot field included). A reset owes no `drop`. A variable holding a frame cell holds
  one on every path, so a join of a frame cell and a heap cell keeps both on the heap, and `Rc` can
  skip the frame variables `Frames` lists.
- **Destinations as clones (Rule MM7.1).** A function whose results are all reference-free cells it
  builds itself (`Dest(result)` in `Memory`) gets a clone `g#frame`. The clone bumps those cells
  above its caller's mark and takes no mark of its own, so they land in the caller's frame. A call
  whose result passes the checks calls the clone. This is how `temps` places its `Box3`, built in
  `make` and read in the fold step.
- **Code generation.** A function with something placed loads `fx_frame_top` on entry. It calls
  `fx_frame_reset(mark)` before its single `ret` and before the jump of a self tail call. A placed
  constructor calls `fx_frame_alloc`.
- **Aborts.** An abort returns a zero up the stack, so every function it leaves resets its frame at
  its `ret`. The stack is then back at the handler's level. This is an equivalent strategy to Rule
  MM14.2's mark recorded at install, not its letter. A payload goes through `fx_abort`, a runtime
  call, so it is never a frame value, and neither is a handler's result.
- **Baseline versions take part, under the same rule.** Frame cells reach their slots, where the
  witness's `dup` and `drop` meet the sentinel in the count word and do nothing. This gives MM7.3's
  no-op `drop` for `Frame` with the one `ref` witness. `temps` places the same 2,000,000 cells in
  both modes.
- **Reuse (§11).** A cell named by a reuse is never placed, so today's heap reuse is untouched. No
  reuse pin changed: in the 11 benchmarks no reused cell became a frame cell. On `temps` and
  `acc_loop` the choice between reusing a heap cell and bumping a frame cell therefore never arose.
  `acc_loop`'s accumulator is carried and returned by the fold, and it stays on the heap.
- **Report.** `fixed compile --explain-memory` gives, per allocation, the class `Memory` computed
  (`class`) and where the code places it (`placed`: `Frame(f)`, `Heap`, or `Heap; in the caller's
  frame through g#frame`). Per function, it gives the calls that use a `#frame` clone
  (`frame_calls`). Placement is on by default; `--no-frames` turns it off, for measurement.

## Runtime layout

- **Chunks.** A list of chunks: 4 KiB first, each new one twice the last. Chunks above the top are
  cached.
- **Marks.** A mark is the top pointer, and a reset walks back to the mark's chunk.
- **Alignment.** Chunks are not aligned to their size (Rule MM6.1), and sizes are rounded to 8 bytes.
- **The frame cell keeps the full 16-byte header.** Its count word holds minus its size, which
  `fx_dup`, `fx_drop`, `fx_reuse` and a parent's drop leave alone, as they leave a static cell. It is
  needed because code that does not know the class reaches frame cells:
  - baseline slots;
  - a callee that owns and drops its parameter;
  - a counted field read that dups a child;
  - the join variables `Aborts` adds after placement.

  (Since the third round: a frame cell that only code compiled for it reaches has no count word,
  and the count word of the others holds the immortal sentinel; the `Aborts` join variables do not
  count frame cells.)
- **The checked mode `-DFX_CHECK_FRAME`** is on in every test build, so the whole test directory runs
  under it.
  - A reset does not rewind. It poisons the payload of the released cells and marks their count word
    dead.
  - `fx_dup` and `fx_drop` of a dead cell stop the program (exit 103), and a payload read returns
    `0xA5…`. A `match` on a dead cell's tag is not caught.
  - Dead runs are coalesced, so a loop that resets at every step walks each cell once.
  - (Since the third round: a check word in front of each frame cell holds this state instead of
    its count word, and a reset poisons the whole cell.)
  - EmitSuite asserts `frame_allocs == frame_frees` for every program in both modes. Unchecked,
    `frame_frees` is `frame_allocs` only if the stack is back at its bottom at exit.

## Measurement

| Program | Mode | Instructions on | off | `base3` | Cycles on | off | Heap allocations on | off | Frame allocations | Peak RSS KiB |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | default | 833,788,712 | 833,788,656 | 833,788,473 | 233.0 M | 232.8 M | 2,097,151 | 2,097,151 | 0 | 50568 |
| `bintrees` | `--no-specialise` | 869,440,259 | 869,440,225 | 869,440,047 | 243.3 M | 236.9 M | 2,097,151 | 2,097,151 | 0 | 50772 |
| `nat_loop` | default | 1,466,917,138 | 1,466,917,133 | 1,466,916,953 | 285.1 M | 283.3 M | 4,000,402 | 4,000,402 | 0 | 2192 |
| `nat_loop` | `--no-specialise` | 1,486,924,238 | 1,486,924,160 | 1,486,923,974 | 294.4 M | 293.2 M | 4,000,402 | 4,000,402 | 0 | 2152 |
| `list_pipeline` | default | 2,056,733,206 | 2,056,733,332 | 2,056,732,992 | 427.7 M | 430.5 M | 4,000,602 | 4,000,602 | 0 | 2164 |
| `list_pipeline` | `--no-specialise` | 2,186,744,715 | 2,186,744,644 | 2,186,744,436 | 509.7 M | 512.6 M | 4,000,602 | 4,000,602 | 0 | 2172 |
| `records` | default | 1,477,123,630 | 1,477,123,640 | 1,477,123,460 | 292.5 M | 291.5 M | 3,000,302 | 3,000,302 | 0 | 2444 |
| `records` | `--no-specialise` | 1,492,127,654 | 1,492,127,664 | 1,492,127,458 | 295.4 M | 300.2 M | 3,000,302 | 3,000,302 | 0 | 2668 |
| `fail_chain` | default | 1,386,614,168 | 1,386,614,180 | 1,386,613,973 | 285.3 M | 284.1 M | 4,000,602 | 4,000,602 | 0 | 2152 |
| `fail_chain` | `--no-specialise` | 1,470,595,809 | 1,470,595,819 | 1,470,595,612 | 327.6 M | 327.5 M | 4,000,602 | 4,000,602 | 0 | 2188 |
| `shared_map` | default | 1,169,533,781 | 1,169,533,794 | 1,169,533,596 | 225.2 M | 229.9 M | 2,020,404 | 2,020,404 | 0 | 2676 |
| `shared_map` | `--no-specialise` | 1,217,832,026 | 1,217,832,077 | 1,217,831,869 | 255.0 M | 258.2 M | 2,020,404 | 2,020,404 | 0 | 2672 |
| `fresh_map` | default | 1,707,433,669 | 1,707,433,661 | 1,707,433,479 | 349.0 M | 350.1 M | 4,020,603 | 4,020,603 | 0 | 2140 |
| `fresh_map` | `--no-specialise` | 1,801,734,105 | 1,801,734,140 | 1,801,733,927 | 398.5 M | 399.6 M | 4,020,603 | 4,020,603 | 0 | 2444 |
| `acc_loop` | default | 1,446,292,316 | 1,446,292,315 | 1,446,292,128 | 266.7 M | 273.1 M | 4,000,003 | 4,000,003 | 0 | 95008 |
| `acc_loop` | `--no-specialise` | 1,504,292,364 | 1,504,292,349 | 1,504,292,149 | 305.2 M | 306.9 M | 4,000,003 | 4,000,003 | 0 | 95100 |
| `points` | default | 1,435,311,280 | 1,435,311,327 | 1,435,311,128 | 277.7 M | 277.9 M | 3,000,602 | 3,000,602 | 0 | 2160 |
| `points` | `--no-specialise` | 1,446,320,879 | 1,446,320,876 | 1,446,320,714 | 282.5 M | 282.4 M | 3,000,602 | 3,000,602 | 0 | 2136 |
| `temps` | default | 1,488,292,233 | 1,610,292,244 | 1,610,292,082 | 287.0 M | 305.1 M | 4,000,002 | 6,000,002 | 2,000,000 | 95344 |
| `temps` | `--no-specialise` | 1,544,292,271 | 1,666,292,271 | 1,666,292,093 | 315.4 M | 332.7 M | 4,000,002 | 6,000,002 | 2,000,000 | 95112 |
| `scratch_tree` | default | 941,637,477 | 941,637,499 | 941,637,299 | 243.6 M | 243.1 M | 2,560,002 | 2,560,002 | 0 | 2916 |
| `scratch_tree` | `--no-specialise` | 985,257,488 | 985,257,504 | 985,257,301 | 248.4 M | 248.0 M | 2,560,002 | 2,560,002 | 0 | 2916 |

- **`temps`, the target.**
  - Allocations: 6,000,002 heap allocations become 4,000,002 plus 2,000,000 frame cells, in both
    modes.
  - Instructions fall by 122,000,000, 61 per step: −7.6% in the default mode, −7.3% with
    `--no-specialise`. Cycles fall by 5.9% and 5.2%.
  - What stays on the heap, per element of `upto`'s list: the list cell, and the cell `upto`'s unfold
    step returns through a closure call, which cannot call a `#frame` clone. Plus 2 per run.
  - A frame cell still costs two runtime calls per step (`fx_frame_alloc`, `fx_frame_reset`) and an
    `fx_drop` that returns at once (`volume` owns its parameter). Inlining the bump and reset fast
    paths in the emitted IR is the next gain; it was left out to keep the increment small.
  - Peak RSS moves by 0.2%.
- **`scratch_tree` gets nothing.** The derived `unfold` builds its tree from the results of recursive
  calls and of the step closure, reused into tree nodes. Placing it needs the destination passed
  through call depth and closures, and cells with references in a clone, or a `Region`.
- **The other benchmarks place nothing.**
  - Instructions with frames on and off differ by at most 130 per run (`acc_loop` by 1 and 15). Both
    are within 400 of `base3`: one chunk at start-up.
  - Cycles move within ±2%, except `bintrees --no-specialise` (+2.7% on) and `acc_loop` default
    (−2.4% on). Their instructions are identical, so this is noise.

### After the rebase onto `a272a68` (immediates, bounded-stack `free_cell`)

Default mode, instructions and heap allocations; frames on and off at the rebased branch, against
`a272a68` itself. Frame allocations are 0 everywhere except `temps` (2,000,000).

| Program | `a272a68` | frames off | frames on | Heap allocations, off → on |
|---|---:|---:|---:|---|
| `bintrees` | 831,691,366 | 831,691,558 | 831,691,499 | 2,097,151 |
| `nat_loop` | 1,452,915,668 | 1,452,915,723 | 1,452,915,723 | 4,000,402 |
| `list_pipeline` | 2,042,731,011 | 2,042,731,178 | 2,042,731,167 | 4,000,602 |
| `records` | 1,470,122,506 | 1,470,122,644 | 1,470,122,617 | 3,000,302 |
| `fail_chain` | 1,372,614,788 | 1,372,614,965 | 1,372,614,958 | 4,000,602 |
| `shared_map` | 1,161,462,195 | 1,161,462,375 | 1,161,462,367 | 2,020,404 |
| `fresh_map` | 1,693,361,476 | 1,693,361,636 | 1,693,361,641 | 4,020,603 |
| `acc_loop` | 1,432,292,122 | 1,432,292,293 | 1,432,292,301 | 4,000,003 |
| `points` | 864,654,519 | 864,654,692 | 864,654,716 | 2,000,602 |
| `temps` | 1,590,292,059 | 1,590,292,227 | 1,474,292,229 | 6,000,002 → 4,000,002 |
| `scratch_tree` | 938,977,300 | 938,977,472 | 938,977,563 | 2,560,002 |

`temps` loses 116,000,000 instructions (−7.3%), 58 per step. Elsewhere frames on and off are within
200 instructions of `a272a68`.

## Against the hypotheses of §21

- **Speed against pure Perceus.** Where frames apply, a frame cell saves 61 instructions against
  `malloc`, `free` and the count, which is about 6% of `temps`'s cycles. Most allocations leave by a
  closure result or through a recursive builder, which this increment does not reach.
- **`Memory`'s escape facts were not sound for frames on their own** (repaired in the second round
  below). On the test programs, most
  allocations it classes `Frame` stay on the heap. `--explain-memory` shows `class` against `placed`
  per site. Where its facts are too weak:
  - A field read does not flow back to the cell it was read from (`Memory.right`, `Rhs.Field`). A
    callee that returns or stores a part of a parameter gets escape `none` (`firstOf`/`viaPart` in
    `frame_more.fixed`; a derived `map` or `fold` handing elements to a closure is the same).
  - A reused cell does not take the escapes of the value built in it (`Rhs.Reuse`, Rule MM11.4).
  - "A call in tail position closes the caller's scope" (Rule MM5.1) is not modelled. The
    accumulators of `mapLoop` and `unfoldLoop` passed to the self tail call are `Frame` there.
  - A fold's carried value gets the step's parameter escape, but the fold returns the initial value
    on an empty input (`viaFold`; `acc_loop`'s `Acc(0, 0)`).

  `Frames` places these on the heap. `Memory.scala` is unchanged.
- **Evacuation:** not built. Nothing is evacuated.
- **The descriptor on frame cells (OQ-MM7):** kept, plus the count word. That costs a word per frame
  cell and no measurable RSS on `temps`, whose frame holds one cell at a time.
- **Chunk sizes (OQ-MM2):** no benchmark grows past its first chunk. FrameSuite grows and reuses
  chunks over 100,000 cells.
- **N versus relocation (OQ-MM3):** not reached; there are no growable stacks.

## Open questions for the owner

1. **The count word of a frame cell.** Rule MM3.1 says no count, and Rule PR3.1 says it is absent.
   Related: one `ref` witness serves every class (Rule MM7.3 has one per class).
   - **Recommendation:** keep the sentinel until every path that reaches a cell knows its class,
     which needs class witnesses.
   - The cost is a word per frame cell. A `fx_drop` call on a frame cell returns after one test.
   - **Since the third round below:** a frame cell that only code compiled for it reaches has no
     count word; the others keep the sentinel.
2. **Chunk alignment** (Rule MM6.1). **Recommendation:** defer to counted regions, the first user of
   pointer masking.
3. **Destinations by clone, for cells built in the function only.** Rule MM7.1 says "through any
   call depth", with a hidden parameter.
   - **Recommendation:** keep clones for direct calls (no ABI change, at a cost in code size). Next,
     pass the destination through a returned call. Decide the closure convention when destinations
     through closures are built: on `temps`, that is where the next allocations are.
4. **A frame cell refers only to its own frame or to static cells** (Rule MM4.2 allows borrowed or
   registered heap references). **Recommendation:** extend to borrowed heap references once
   borrowing is precise per variable; add drop lists only if a benchmark needs them.
5. **Frame cells are never reused.** Rules MM3.2 and PR6.3 reuse an exclusively owned frame cell
   unconditionally, and Rule PR6.3.a(2) requests an exclusive version for a frame argument.
   **Recommendation:** add it only together with reuse across regions (Rule MM11.4). No benchmark
   lost a reuse to frames.
6. **No evacuation** (Rule MM5.5). A value classed `Frame` that reaches a closure, a table or a
   callee that keeps it stays on the heap. **Recommendation:** stay with that until a benchmark shows
   values lost to the heap by one such use.
7. **`Memory`'s escape facts** (the four cases above). **Done** in the second round below: fixed
   in `Memory.scala`; the local check keeps only what a lifetime analysis does not compute.
8. **A non-self call in tail position** keeps the caller's scope open: the reset comes after the
   call. This is sound, but it holds memory longer than Rule MM5.1 allows, and the call stops being
   a sibling call. **Recommendation:** reset before such a call when no argument is a frame value,
   once a benchmark shows the retention.

## Second round: the lifetime analysis repaired

`Memory.scala` now states its soundness condition: `Frame(f)` promises that no reference to the
value is live once the activation of `f` that built it ends (its return, or a self call in tail
position, which is a jump that resets the frame). Each unsound case got a test in `MemorySuite`
that failed before its fix, and each fix was checked to be the one its test needs (the fix taken
out alone, the test fails):

| Case | Program in `MemorySuite` | Class before | After | Fix |
|---|---|---|---|---|
| A field read does not flow back to the cell read | `firstOf` returns a field of its parameter | `Frame(viaPart)` | `Dest(result)` | a counted `Field` result's escapes flow to the object read |
| The same, through a `match` binder used as an abort payload | `sub` aborts with the head of its list | `Frame(viaHandle)` | `Region` (long-lived) | the same fix |
| A reused cell does not take the escapes of the value built in it | `swap` reuses its parameter for its result | `Frame(viaReuse)` | `Dest(result)` | a `Reuse`'s cell gets the escapes of the result |
| A self call in tail position does not close the scope | `spin` passes a new box to itself | `Frame(spin#x…)` | `Heap`, "passed to a self call in tail position" | its arguments are `Carried` by the loop, always long-lived |
| A left fold returns its initial value on an empty input | step consumes `acc` exclusively, returns a new one | `Frame(viaFold)` | `Dest(result)` | the initial value also escapes where the fold's result does |
| A closure's call may return a capture (further case) | `let f = (n) -> p; f(0)`, `p` a part of a holder | `Frame(viaClos)` | `Dest(result)` | a counted `CallClosure` result's escapes flow to the closure |
| A closure's code may abort with a capture (further case) | `apply(f, i)`, `f` aborts with its capture | `Frame(viaClosAbort)` | `Heap` | a capture is `Unbounded` where the code's closure parameter is |

Checked and found sound, no change: aliases through `let` and branch joins (each flows the
variable's escapes to its sources), handler results (a clause's value flows like any branch),
values passed to a capability table call or a closure the compiler cannot see, or to a callee
outside the unit (`Unbounded`), abort payloads (`Unbounded`), values stored by a derived member (its
code is analysed like any function), and runtime calls (they take and return strings).

A call in tail position to another function is still a call that keeps the scope open until it
returns (open question 8); `Memory` models the code as built, and says so.

**`--explain-memory` and `examples/golden/01_basics.memory.json`.** No placement changed. The
classes and parameter escapes that changed, all in derived loops of the prelude and in `fibIter`:

- `unfoldLoop` (List and Nat): the cell passed on in tail position was `Frame` of the loop. It is
  `Heap` in the versions that call themselves (a jump), and `Dest(result)` in the versions that
  call another version of the loop instead (a call, which returns the result of the loop): the
  baseline versions and `List of A.unfoldLoop[R]#x[4:List of A#1,cell]`. The accumulator
  parameter becomes `into-result` in every version.
- `Nat.foldDown` (both instances): the cell passed to the self call in tail position was `Frame`; it
  is `Heap`.
- `reverseOnto`: its parameter's cells are reused for the cells passed round the loop and returned:
  `acc` becomes `into-result`, and the cell built becomes `Heap` (carried by the self tail call)
  instead of `Dest(result)`.
- `foldLeft`, `fold`, `foldUp`, `foldDown`, `unfold` of List and Nat: a parameter whose parts reach
  a closure the loop cannot see (the list `xs`; the steps, whose results are handed back to a step)
  becomes `unbounded`. So `fibIter`'s three closures become `Heap`, "kept by `Nat.unfold[R]` (or
  `Nat.fold[R]`) beyond the call". Closures are never placed, so nothing moved.
- `fibIter`'s baseline step `lambda.141` hands parts of `acc` to the `Numeric` table: `acc` becomes
  `unbounded`; its `u64` instance `lambda.215` reuses `acc` for its result: `into-result`.

**The local check in `Frames`.** With the lifetime in `Memory`, `Frames` takes its roots from
`Memory` alone: a constructor or a `#frame` call result is a candidate only if `Memory` gives the
variable `Frame` of the function (`MemoryTable.framed`, which covers call results as well as
allocations). Removed from `Frames`: the test for a self call in tail position (the `jump` flag and
the tail position threaded through its walk). Evidence: without the jump test and without
`Memory`'s tail fix, `frame_more` stops in the checked build ("a frame cell used after its scope
ended"); with `Memory`'s fix alone it passes.

What `Frames` keeps is what a lifetime does not say, so `Memory` by its nature does not compute it:

- **Containment (Rule MM4.1).** A frame cell may be pointed to only by frame cells of its
  activation: a field of a cell that is not placed, a capture, or a parameter that a callee stores
  or captures keeps the value on the heap, even where the container dies in the activation.
- **Reuse.** The runtime reuses only heap cells: a value named by a `Reuse`, or passed to a
  parameter that a callee may reuse, stays on the heap.
- **Joins for `Rc`.** A variable that holds a frame cell holds one (or a static cell) on every path.

These are why the parameter summary `kept` stays: it is the least set of parameters a callee may
store, capture, reuse or hand to code that may, which is an ownership and containment fact.
`Memory`'s `none` allows a callee to reuse its parameter or store it in a cell that dies with it.

**The mutant experiment, re-run.** Both mutants are still caught by the test programs:

- every use allowed (`harmless` always true): 21 tests fail. Eight programs, `frame_adv` and
  `derived_show_eq` among them, abort in `free()` (a frame cell named by a local `Reuse` gets a
  count of 1 and is later freed); ten stop with "reused a cell that is not exclusively owned" (a
  callee's exclusive version reuses a frame cell); `acc_loop`'s reuse pins and FrameSuite's two
  placement assertions fail.
- no parameter kept (`kept` empty): 14 tests fail: eleven programs stop with "reused a cell that
  is not exclusively owned", plus `acc_loop`'s pins and FrameSuite's two assertions.

Before the repair these mutants were caught by values that escaped; now they are caught by
containment and reuse, the part of the check that remains.

## Third round: the count word of frame cells

Rule MM3.1 gives a `Frame` cell a descriptor and no count. Until now every frame cell kept the
count word, holding minus its size, so that code that does not know a cell's class leaves it alone.

### Who can touch the count word of a frame cell

| Code | Reaches a frame cell today? | What decides it |
|---|---|---|
| A callee that owns its parameter and drops it | Yes: `temps`'s `volume` drops its parameter, 2,000,000 `fx_drop` calls that return at the sentinel | Rc drops every owned parameter at its last use |
| A field read that dups a child | Yes, in a callee: Rc dups a counted field read of a parameter (`frame_counted`'s `firstOr`) | In the placing function Rc skips every holder of a frame cell |
| A baseline version, through a value witness | Yes: a frame cell that a baseline version reads into a slot (`shape` reads the boxes of a `P2 of A` with `--no-specialise`: 4 witness calls before this round) | `ref_dup` and `ref_drop` call `fx_dup` and `fx_drop` |
| Derived members, `clone` | As any callee: they are functions like any other | the same as a callee |
| The join variables `Aborts` adds | No count operation: they hold the function's result, which Rc returns and never dups or drops; a frame cell is a result only in a `#frame` clone | `Aborts` runs after `Frames`; `exit.n` is returned |
| `fx_drop` of a heap parent with a frame child | Cannot exist: a frame value stored into a cell needs that cell placed too (`In(x)` needs `x` a root), and a callee that stores its parameter keeps it (`kept`) | Rule MM4.1, checked in `Frames` |
| Reuse (`fx_reuse`, `fx_reuse_excl`, `fx_alloc_at`) | Never: a reuse's cell, and a parameter a callee may reuse, keep the value on the heap | `Frames`' local check (second round) |
| Capability tables, closures, runtime calls | Never: an argument of a table or closure call stays on the heap; the runtime takes strings only | `Use.Out` |
| The checked mode | Yes: `frame_live` read the count word on every `fx_dup`, `fx_drop` and child drop, and a reset walked the cells by it | the runtime, `FX_CHECK_FRAME` |
| A static cell in the same variable | Yes: a static cell (`L.E`) always has the full header, and a variable that may hold one is read at the full layout's offsets | `EmitLLVM` picks one layout per variable |
| Diagnostics (`inspect`, `trace`) | Not built | |

So a frame cell may lose its count word only where no callee, witness or static cell can meet it,
unless the callee is compiled for it.

### The condition, as built

A frame cell is **bare** (descriptor, then payload: 8 bytes less) when, checked per allocation site
by `Frames` as a greatest fixed point:

1. every variable that may hold it or a part of it (an alias, a branch's result, a field read, a
   `match` binder) holds only bare cells on every path: no join with a static cell, a heap value or
   a frame cell with a count word;
2. each such variable is only read, `match`ed, stored into another bare cell of the activation, or
   passed to a parameter that **takes a bare cell**: one whose holders in the callee meet 1 and 2
   and are never returned, of a function called directly (not a closure's code, a table entry, a
   `handle` subject, `main`, or a function with a `#frame` clone). A call that passes bare cells goes
   to a clone `g#bare<indices>` whose reference counting skips every holder of those parameters;
3. its reference fields hold bare cells only;
4. a `#frame` clone's cells are bare if they hold no reference, are only read or returned in the
   clone, and every caller's result is bare (one clone serves every caller).

Every other frame cell keeps the count word with the sentinel: there are two frame layouts.

The owner's example condition had "it never enters a slot of parameter type". The code shows it is
not needed: a slot is counted only by a `dup` or `drop` that Rc adds, and a `#bare` clone's Rc skips
every holder, slots included; generic code can do nothing else with a slot but hand it to a table or
closure, which keeps the value on the heap. `frame_bare`'s `viaSlot` is bare with
`--no-specialise`, where `shape`'s baseline version reads its boxes into slots, with no witness call
on them.

**Where Rule MM3.1 cannot be met as written.** For a frame cell that code not compiled for its
class reaches (a callee that joins the parameter with another value, a closure's code, a table
entry, a `handle` subject, a cell next to a static cell, a `#frame` clone with one such caller), the
count word stays, because that code tests it. Meeting the rule there needs the class witnesses of
Rule MM7.3 (a `Frame` `ref` witness whose `dup` and `drop` are nothing, chosen by class) or more
clones.

### What is built

- `Frames`: `bareParameters`, `bareCells`, the `#bare` clones (`bareClones`), and `bare`, the
  variables per function that hold bare cells.
- `EmitLLVM`: a bare cell is allocated by `fx_frame_alloc_bare`; a field of a variable of `bare` is
  at `8 + 8 * slot` instead of `16 + 8 * slot`, its tag at 0 instead of 8.
- `Rc`: a parameter that holds a frame cell (a `#bare` clone's) is not owned.
- Runtime: `fx_frame_alloc_bare` writes the descriptor only; the counter `frame_bytes` (bytes of
  frame cells, without the check word below). A frame cell with a count word holds the immortal
  sentinel, −1, there, as a static cell does. The checked mode now puts a check word in front of
  every frame cell, holding its size and whether it is live, released or bare; the program never
  sees it. `fx_dup`, `fx_drop`, a parent's drop and `fx_reuse` / `fx_reuse_excl` stop the program
  (exit 103) on a frame cell that is released or bare. A reset poisons the whole cell, descriptor
  included, and walks the cells by their check words.
- `--explain-memory`: `placed` says `Frame(f), no count word` for a bare cell.

### The tests that prove it

- **Runtime (FrameSuite):** a hand-written IR program bumps a bare cell and a cell with the count
  word, both with 24 bytes of payload, and reads the bare cell's tag at offset 0 and its first field
  at 8: `frame_bytes` is 32 + 40, in both builds. The checked build stops on `fx_drop` of the bare
  cell ("a count operation on a frame cell without a count word").
- **Removal (FrameSuite, both build modes, checked and not):** `modes/frame_bare.fixed` prints its
  expected output and allocates 6,015 frame cells in 128,400 bytes: each is 8 bytes of descriptor
  and 8 per field, 48,120 bytes less than with count words, and its IR has no `fx_frame_alloc` call.
  `temps` allocates its 2,000,000 boxes in 64,000,000 bytes, 32 each instead of 40.
- **Cells that keep the word (FrameSuite and EmitSuite):** `modes/frame_counted.fixed` has a callee
  that joins its parameter with a cell of its own (it drops it), one that joins a part of its
  parameter with one (it dups the part), a box in a list ending in the static `E`, `make#frame` with
  one caller whose result goes to such a callee, the parameter of a function with a `#frame` clone
  and of a `handle` subject (both dropped), a cell joined with the static `None`, a `#frame` clone's
  cell that holds the static `Z`, and one that the clone hands to `sum` before returning it. Of its
  22 frame cells, 21 keep the count word and one is bare (`grow#frame`'s result, which only
  `sum#bare0` reads): 752 + 32 bytes.
- **Mutants**, each a condition of `Frames` taken out, run on the test programs (EmitSuite, checked
  builds):

  | Condition taken out | Caught by |
  |---|---|
  | all of them: every placed cell bare, no `#bare` clones | `temps`, `frame_bare`, `frame_counted`, `frame_more`: "a count operation on a frame cell without a count word"; `frame_escapes` prints wrong numbers |
  | a parameter's holders hold nothing else | `frame_counted` prints wrong numbers (a heap cell read at the bare layout) |
  | a root's holders hold only bare cells | `frame_counted` prints 50 for 51 (the static `None` read at the bare layout) |
  | a root's reference fields hold bare cells | `frame_counted`, `frame_more`: segmentation fault (a static `E` matched at the bare layout) |
  | a `#frame` clone's every caller has a bare result | `frame_counted`, `frame_more`: "a count operation on a frame cell without a count word" |
  | a `#frame` clone's cells hold no reference | `frame_counted`: segmentation fault (`single`'s static `Z`) |
  | a `#frame` clone's cells are passed to no call | `frame_counted` prints 9 for 12 (`mk#frame` calls `sum`, not `sum#bare0`) |
  | no `#bare` clone of a function with a `#frame` clone | `frame_counted`: "a count operation…" (`grow#frame` drops its parameter) |
  | no `#bare` clone of a `handle` subject | `frame_counted`: "a count operation…" (`peek` drops its parameter) |

  Two mutants survive because another check implies what they take out: a bare parameter's holders
  never returned, stored or passed to unknown code (a parameter that is, is kept, so no frame cell
  reaches it), and a `#frame` clone's cells only read or returned (`results` allows nothing else).
  A first version also required every holder to be a reference, not a slot; its mutant survived
  too, which is how the slot condition was found unnecessary and removed.

### Measurement

Default and `--no-specialise`, against the commit before this work (`fin`, the binaries in
`/var/tmp/fixed-bench/fin*`), measured in one session. Cycles: minimum of 5. Heap allocations,
frame allocations, frame bytes and peak RSS: `FIXED_RT_STATS=1` (`fin`'s runtime has no
`frame_bytes`; its allocations are the same).

| Program | Mode | Instructions `fin` | now | Cycles `fin` | now | Heap allocations | Frame allocations | Frame bytes | Peak RSS KiB `fin` / now |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `bintrees` | default | 831,691,529 | 831,691,534 | 235.3 M | 234.9 M | 2,097,151 | 0 | 0 | 50540 / 51028 |
| `bintrees` | `--no-specialise` | 867,343,109 | 867,343,104 | 242.3 M | 240.5 M | 2,097,151 | 0 | 0 | 51036 / 51044 |
| `nat_loop` | default | 1,452,915,756 | 1,452,915,813 | 288.6 M | 286.2 M | 4,000,402 | 0 | 0 | 2144 / 2192 |
| `nat_loop` | `--no-specialise` | 1,472,922,804 | 1,472,922,766 | 298.3 M | 295.5 M | 4,000,402 | 0 | 0 | 2156 / 2176 |
| `list_pipeline` | default | 2,042,731,195 | 2,042,731,217 | 431.5 M | 432.2 M | 4,000,602 | 0 | 0 | 2164 / 2132 |
| `list_pipeline` | `--no-specialise` | 2,172,742,719 | 2,172,742,657 | 512.5 M | 510.5 M | 4,000,602 | 0 | 0 | 2156 / 2176 |
| `records` | default | 1,470,122,668 | 1,470,122,649 | 293.6 M | 291.2 M | 3,000,302 | 0 | 0 | 2680 / 2672 |
| `records` | `--no-specialise` | 1,485,126,685 | 1,485,126,675 | 298.1 M | 301.0 M | 3,000,302 | 0 | 0 | 2664 / 2668 |
| `fail_chain` | default | 1,372,614,986 | 1,372,614,986 | 286.0 M | 286.4 M | 4,000,602 | 0 | 0 | 2160 / 2392 |
| `fail_chain` | `--no-specialise` | 1,456,596,633 | 1,456,596,650 | 328.6 M | 328.2 M | 4,000,602 | 0 | 0 | 1876 / 2176 |
| `shared_map` | default | 1,161,462,479 | 1,161,462,402 | 233.3 M | 233.2 M | 2,020,404 | 0 | 0 | 2668 / 2676 |
| `shared_map` | `--no-specialise` | 1,209,760,636 | 1,209,760,655 | 260.6 M | 257.2 M | 2,020,404 | 0 | 0 | 2748 / 2668 |
| `fresh_map` | default | 1,693,361,685 | 1,693,361,725 | 354.2 M | 352.4 M | 4,020,603 | 0 | 0 | 2132 / 2160 |
| `fresh_map` | `--no-specialise` | 1,787,662,124 | 1,787,662,115 | 401.6 M | 403.3 M | 4,020,603 | 0 | 0 | 1916 / 2160 |
| `acc_loop` | default | 1,432,292,350 | 1,432,292,342 | 277.0 M | 273.8 M | 4,000,003 | 0 | 0 | 94592 / 95372 |
| `acc_loop` | `--no-specialise` | 1,490,292,357 | 1,490,292,365 | 311.1 M | 309.6 M | 4,000,003 | 0 | 0 | 95092 / 95116 |
| `points` | default | 864,654,719 | 864,654,730 | 171.6 M | 173.3 M | 2,000,602 | 0 | 0 | 2168 / 2160 |
| `points` | `--no-specialise` | 910,664,364 | 910,664,348 | 201.1 M | 200.0 M | 2,000,602 | 0 | 0 | 2188 / 2176 |
| `temps` | default | 1,474,292,250 | 1,452,292,283 | 285.7 M | 274.8 M | 4,000,002 | 2,000,000 | 64,000,000 | 95096 / 95096 |
| `temps` | `--no-specialise` | 1,530,292,288 | 1,508,292,302 | 317.3 M | 309.7 M | 4,000,002 | 2,000,000 | 64,000,000 | 95352 / 95360 |
| `scratch_tree` | default | 938,977,491 | 938,977,552 | 240.6 M | 245.4 M | 2,560,002 | 0 | 0 | 2944 / 2908 |
| `scratch_tree` | `--no-specialise` | 982,597,511 | 982,597,518 | 252.6 M | 252.2 M | 2,560,002 | 0 | 0 | 2904 / 2904 |

- **`temps`:** its 2,000,000 boxes lose the count word in both modes: 16,000,000 bytes less of data
  stack (8 per box; its frame holds one box at a time, so peak RSS does not move). Instructions fall
  by 22,000,000, 11 per step (−1.5% in both modes): the box is no longer written a count, and
  `volume#bare0` does not drop its parameter (`drops` 14,000,007 → 12,000,007). Cycles −3.8%
  (default) and −2.4% (`--no-specialise`); identical binaries move by about ±2%.
- **The other benchmarks** place no frame cell. Their instructions are within 80 of `fin` (the
  process environment), and their cycles move by at most 2.0% either way, which is noise.
