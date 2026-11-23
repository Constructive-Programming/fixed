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
- **The checked mode `-DFX_CHECK_FRAME`** is on in every test build, so the whole test directory runs
  under it.
  - A reset does not rewind. It poisons the payload of the released cells and marks their count word
    dead.
  - `fx_dup` and `fx_drop` of a dead cell stop the program (exit 103), and a payload read returns
    `0xA5…`. A `match` on a dead cell's tag is not caught.
  - Dead runs are coalesced, so a loop that resets at every step walks each cell once.
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

## Against the hypotheses of §21

- **Speed against pure Perceus.** Where frames apply, a frame cell saves 61 instructions against
  `malloc`, `free` and the count, which is about 6% of `temps`'s cycles. Most allocations leave by a
  closure result or through a recursive builder, which this increment does not reach.
- **`Memory`'s escape facts are not sound for frames on their own.** On the test programs, most
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
7. **`Memory`'s escape facts** (the four cases above). **Recommendation:** fix them in
   `Memory.scala` in a separate commit, and keep the local proof in `Frames` as a check.
8. **A non-self call in tail position** keeps the caller's scope open: the reset comes after the
   call. This is sound, but it holds memory longer than Rule MM5.1 allows, and the call stops being
   a sibling call. **Recommendation:** reset before such a call when no argument is a frame value,
   once a benchmark shows the retention.
