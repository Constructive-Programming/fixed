# Runtime baseline, 2026-11-23: fusion of `unfold` with `fold`

Guaranteed fusion (`spec/type_system.md` Rule 7.4.b.1) and the inlining it brings for the functions
of a fused site (Rule 7.4.b.2), against the base commit `e758b98` (`/var/tmp/fixed-bench/l1.txt`)
and against the same commit with `--no-fusion`.

- Commits: `5365740` (fusion, and the `Rc` fault review found), and this report
- Machine: Linux 7.2.15-gentoo-x86_64 x86_64, AMD RYZEN AI MAX+ 395 w/ Radeon 8060S; `clang -O2`
- Instructions: median of 5 runs (deterministic up to a few dozen). Cycles: minimum of 5 interleaved
  runs, read from the CPU's counters. Allocations and peak RSS: `FIXED_RT_STATS=1`. The base
  binaries (`/var/tmp/fixed-bench/l1`, and the base built with `--no-specialise`) were measured again
  in the same session, so their cycles and RSS differ a little from `l1.txt`.

## Design

`backend/Fusion.scala` is a pass over the lowered Core of the whole program, run by `Lower` after
`main` and everything it reaches are lowered, before `Demands`, `Versions`, `Frames`, `Aborts` and
`Rc`. It replaces a site by a call of generated functions (`fused.N`, `fused.N.up`) that are
ordinary Core: every later pass sees a loop, and reference counting, reuse and frame placement apply
to it as to any function. `Generated` records what each derived member is (`unfold`, `map`, left
fold, `fold`, and of which data type); `Lower.lambda` records which lambdas have an empty effect
row.

A site is a `let r = F(…, xs, …)` where `F` is a derived `fold` (Rule 7.4.a) or the left fold of a
list (`Folding.fold`, condition 3 of the rule), and these hold:

1. `xs` is bound by a derived `unfold(seed, step)` of the same data type, or by derived `map`s of a
   list over such an `unfold`, or by a call of a **builder**;
2. `xs`, and each list between the `unfold` and the fold, occurs exactly once among the operands of
   the function, closure captures included;
3. the step and every `map` function have an empty effect row (the typer's row of the lambda, or the
   arrow type of a function value); the fold's functions may have any effect;
4. the shape has a fused form: a left fold (one loop, with `map` stages); a `fold` of linear data whose
   one recursive variant holds only `Self`, as `Nat` (a loop that runs the steps and counts, then a
   loop that applies the callbacks: the unfused order); a `fold` of other data whose recursive fields
   are all `Self` itself, a tree (a recursive function over the seed, one native frame per level, as
   the unfused derived `fold` of a tree takes: Rule 7.4.b.3, second paragraph);
5. technical conditions, each of which leaves the site unfused: the seed's Core type is the inlined
   step's parameter type, a left fold's initial value has the result's Core type, a `map`'s element
   type is known, and the generated loop needs no variable but its parameters. No benchmark or test
   program meets one of them.

A **builder** is a function that is known (a call of a lowered function), monomorphic, does not
reach itself through calls or its closures, and whose body is `let`s of moves, scalar operations,
constructors, field reads or closures used once, then one derived `unfold` whose result it returns.
Its body, with the arguments for its parameters, is inlined at the fold: its `let`s are placed just
before the fold, and the `unfold` is fused there. This is the inlining step of
`docs/baselines/runtime-2026-11-23-debts.md` §3; nothing else is inlined.

The functions of a site: a lambda built in the same function (or in the builder) is **inlined**
(Rule 7.4.b.2). Its code's body is copied with its variables renamed, its captures become parameters
of the loop, and the copy's results are rewritten at each tail: where a step returns a constructor
(`List.Cons(i, i + 1)`), its fields go straight to the next step, and no layer cell exists. Any
other function value is called through its closure: one indirect call per step and, for a step, its
layer is a cell that the loop reads and drops (no list cell either way). An inlined closure is no
longer built where the call was its only use; used elsewhere too, it is still built there. A nested
`fn` as step or `map` function is not fused: only lambdas record an empty row.

The loop's seed and accumulator carry the typer types of what they stand for, so `Demands` offers
the accumulator's cell for reuse: a record accumulator rebuilt each step is reused in place. Clone
requests (Rule CM5.4) made in inlined code are copied to the loops it is inlined into, those of
removed calls are dropped, and `Specialise` and `Versions` count a fused loop as part of its caller,
one loop step deeper.

`--no-fusion` builds every site as before, for measurement; the test harness runs every program of
`bench/runtime` and `compiler/src/test/fixed/modes` both ways and compares the output.

## Decided not to fuse, and why

| Case | Why |
|---|---|
| A step or `map` function with an effect in its row | The steps would run between the fold's callbacks instead of before them, and output would change order. The rule says nothing about effects: question 1 below. |
| A right `fold` (Rule 7.4.a) of a list whose cells hold an element | The callbacks run from the last element; without the list the elements must be kept somewhere: native stack (against Rule 7.4.b.3) or a heap stack (an allocation per element). |
| A `fold` of a parameter: `count(make(20))` in `bintrees`, `total(orders(…))` in `records` | Condition 1 asks for the result of an `unfold` consumed by a `fold`; a call in between is neither. It would need the consumer inlined too, which is not built. |
| A right `fold` of linear data whose recursive variant holds more than `Self` (a list's, above); a `fold` of data that mentions itself other than as `Self` (a rose tree); `map`s before a derived `fold` | No fused form is built for these shapes. |
| A list used twice, returned, captured or passed on | Condition 2 of the rule. |
| `para` (Rule 7.4.g, outside Rule 7.4.b.1) | Its callbacks receive the original sub-structure. |
| A builder that is generic, recursive, or has more than `let`s and one `unfold` | The inlining stays a substitution of arguments with no growth beyond the `unfold`'s own code; a generic builder would have to be inlined after specialisation. |

## Soundness

- **Order.** The left fold applies its function to the elements in order; the `Nat` form applies the
  terminal callback after all steps, then the recursive one from the last layer to the first, as
  `foldDown`/`foldUp` do; the tree form recurses in field order and applies callbacks after their
  children, as the derived `fold` of a tree does. `fusion_order.fixed` and `fusion_shapes.fixed`
  check non-associative folds of each shape, and both run with and without fusion.
- **Aborts.** A fold function that aborts (`Fail`) under a handler stops the loop; the steps after it
  never run, which a step with an empty row cannot observe.
- **Panics and divergence.** A step's row does not show division by zero (Rule E3.6, not built),
  and an `unfold` that may not end has no `Diverge` (Rule 7.4.i, not built). Fused, the steps (and a
  builder's `let`s) run at the fold, not where the `unfold` was: a step that panics now does so after
  any output between the two, and, in the left-fold and tree forms, after the fold's earlier output,
  or not at all if the fold aborts first; an infinite `unfold` whose left fold aborts now ends. The
  `Nat` form runs every step before any callback, as unfused. See question 2 below.
- **Counts and frames.** The loops go through `Rc`, `Frames` and the runtime's checked modes like any
  function. Review found one fault on the way, older than fusion: a call that both consumes and
  borrows one variable left the borrowed alias without a count, so the callee could reuse the cell
  it still read. `Rc` now keeps a count across such a call (`alias_args.fixed`).

## Open questions for the owner

1. **Effects in a step or `map` function.** Rule 7.4.b.1 says nothing about effects, so as written it
   requires fusing a site whose step prints, which would change the order of output (all steps,
   then the fold's effects, becomes interleaved). Options: (a) add to the rule "the step of the
   `unfold`, and every `map` between it and the `fold`, has an empty effect row"; (b) let fusion
   reorder effects. **Recommendation:** (a), which is what is built.
2. **Panics and divergence.** Under the spec they are settled once Rules E3.6 and 7.4.i are built:
   division by zero is the effect `DivideByZero`, and an `unfold` that may not end has `Diverge`.
   **Recommendation:** with (a) of question 1, require also that the `unfold` call's own row has no
   `Diverge`; until E3.6 and 7.4.i are built, accept that a fused program may panic later, or end,
   where the unfused one panics earlier or runs out of memory.
3. **Condition 1 and builder functions.** The rule speaks of the result of an `unfold`; the inlining
   that brings `upto(n).fold(…)` under it is outside its wording. Proposed wording (*proposal*):
   "1. The result of an `unfold`, of a conversion the compiler derives from one, such as `toNat`, or
   of a call of a function declared in the same package that does not reach itself and whose body
   is `let`s without effects followed by such an expression, is consumed by exactly one `fold`."
   Built: only for a monomorphic function, and without a check of the package (every function of a
   program is in one unit today). This differs from the wording of the debts report ("the
   function's parameters as its only free variables") because `upto`'s step is a closure built in
   the body, not a parameter.

## Benchmarks

Instructions (M), minimum cycles (M), heap allocations, peak RSS (KiB). Base: `e758b98` (`l1`, and
built from it with `--no-specialise`). `--no-fusion` matches base in every program (instructions
within 50, allocations equal). Cycles under 2 M are given to two decimals.

| Program | Mode | Instructions base → fused | Cycles base → fused | Allocations base → fused | Peak RSS base → fused |
|---|---|---:|---:|---:|---:|
| `bintrees` | default | 831.69 → 831.69 | 233.1 → 233.9 | 2,097,151 → 2,097,151 | 50,764 → 51,060 |
| | `--no-specialise` | 867.34 → 867.34 | 242.2 → 243.2 | same | 51,060 → 51,028 |
| `nat_loop` | default | 1,452.92 → 0.19 | 286.1 → 0.26 | 4,000,402 → 1 | 2,168 → 1,928 |
| | `--no-specialise` | 1,472.92 → 0.19 | 295.7 → 0.24 | 4,000,402 → 1 | 2,136 → 1,904 |
| `list_pipeline` | default | 2,042.73 → 0.19 | 437.5 → 0.27 | 4,000,602 → 1 | 2,192 → 1,952 |
| | `--no-specialise` | 2,172.74 → 0.19 | 517.5 → 0.26 | 4,000,602 → 1 | 2,160 → 1,880 |
| `records` | default | 1,470.12 → 1,470.02 | 292.7 → 300.6 | 3,000,302 → 3,000,101 | 2,696 → 2,708 |
| | `--no-specialise` | 1,485.13 → 1,485.02 | 306.0 → 302.9 | 3,000,302 → 3,000,101 | 2,688 → 2,668 |
| `fail_chain` | default | 1,372.61 → 1,372.57 | 290.8 → 286.4 | 4,000,602 → 4,000,201 | 2,176 → 2,160 |
| | `--no-specialise` | 1,456.60 → 1,456.54 | 326.8 → 330.3 | 4,000,602 → 4,000,201 | 2,160 → 2,188 |
| `shared_map` | default | 1,161.46 → 1,161.42 | 230.4 → 234.4 | 2,020,404 → 2,020,002 | 2,652 → 2,676 |
| | `--no-specialise` | 1,209.76 → 1,209.71 | 255.9 → 254.5 | 2,020,404 → 2,020,002 | 2,640 → 2,696 |
| `fresh_map` | default | 1,693.36 → 1,686.68 | 352.3 → 350.6 | 4,020,603 → 4,000,201 | 2,428 → 2,164 |
| | `--no-specialise` | 1,787.66 → 1,780.68 | 398.3 → 401.8 | 4,020,603 → 4,000,201 | 2,128 → 2,160 |
| `acc_loop` | default | 1,432.29 → 88.19 | 278.5 → 16.3 | 4,000,003 → 2 | 94,860 → 1,912 |
| | `--no-specialise` | 1,490.29 → 96.19 | 313.1 → 16.5 | 4,000,003 → 2 | 95,096 → 1,896 |
| `points` | default | 864.65 → 5.69 | 171.3 → 1.64 | 2,000,602 → 1 | 2,184 → 1,924 |
| | `--no-specialise` | 910.66 → 5.69 | 202.1 → 1.65 | 2,000,602 → 1 | 1,908 → 1,936 |
| `temps` | default | 1,452.29 → 128.19 | 275.4 → 17.9 | 4,000,002 → 1 | 95,088 → 1,932 |
| | `--no-specialise` | 1,508.29 → 128.19 | 309.7 → 17.8 | 4,000,002 → 1 | 95,328 → 1,912 |
| `scratch_tree` | default | 938.98 → 922.06 | 244.2 → 232.4 | 2,560,002 → 2,520,001 | 2,944 → 1,928 |
| | `--no-specialise` | 982.60 → 965.12 | 253.9 → 245.9 | 2,560,002 → 2,520,001 | 2,652 → 1,900 |

Cycle differences under about 3% are within the noise of this machine (`--no-fusion` against base,
near-identical code, moves by up to 2.5%); RSS differences of a few hundred KiB are noise too
(`fresh_map`'s base 2,428 is one: its `--no-fusion` build gives 2,164).

**What moved.** Every `main` of the form `upto(n).fold(…)` is fused; so is every site in `nat_loop`,
`list_pipeline`, `points`, `acc_loop` and `temps`. In `nat_loop` and `list_pipeline` nothing is left
but arithmetic on the seed, and `clang` folds `main` to a constant (`fx_show_u64(i64 2000000)` and
`fx_show_u64(i64 29999000000)`): 0.19 M instructions are the process itself. These two programs no
longer measure lists; a benchmark that should keep measuring cells needs a fold the rule does not
fuse. `bintrees` is unchanged: its fold is in `count`, a function of its own. `records`,
`fail_chain` and `scratch_tree` lose their outer loop's list, 2n + 1 allocations; `shared_map` that
and the closure of its fold function, which captures `base` and is now inlined (402); `fresh_map`
both of `main`'s lists (401 + 20,001). Their inner folds consume a parameter and are not fused.

**Against the hand-written loops** of `docs/baselines/runtime-2026-11-23-debts.md` §3:

| Program | Hand-written: instructions / cycles / allocations / RSS | Fused (default) |
|---|---|---|
| `acc_loop` | 88.19 M / 16.3 M / 2 / 1,872 | 88.19 M / 16.3 M / 2 / 1,912 |
| `temps` | 284.19 M / 52.4 M / 2,000,001 / 1,876 | 128.19 M / 17.9 M / 1 / 1,932 |

`acc_loop` equals the hand-written loop to 0.01 M instructions (the estimate was measured on the
older base `d646b53`): the `Acc` is reused in place each step without a test. `temps` beats the
hand-written estimate because its `Box3` is now bumped in the loop's frame (`make#frame`, the frames
work), where the estimate kept it on the heap. No gap remains to explain.

## Not built

- Fusion of a fold of a parameter (`bintrees`: `count(make(20))`); the consumer would need inlining.
- A right fold of a list (no fused form within Rule 7.4.b.3 without an allocation per element).
- `para`; `unfoldN` (not built); no derived conversion such as `toNat` exists yet.
- A nested `fn` as step or `map` function (its row is not recorded).
- A named top-level function passed as a value: the backend does not lower one today.
- A step or fold function that is a function value not built in view still costs an indirect call
  per step, and a step's layer a cell per step.
- `Memory`'s fork facts and carried-accumulator facts recognise derived loops by name; a fused tree
  loop is not reported as a fork site, and an allocation kept by a fused loop is reported "at 0".
