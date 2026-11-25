# Bibliography

The sources behind Fixed's design, grouped by the part of the language they
inform. Each entry says what Fixed takes from it. The specification's own
“Dependencies” sections cite the same works where a rule depends on them; the
last section maps those citations back to the pages here.

## Data, abstraction and logic

- [Death of Data](https://degoes.net/articles/kill-data) — John De Goes on why
  committing to `List`, `Either` and friends too early costs composition, and
  what it means to program against capabilities instead.
- [Variables Explained Away](https://bpb-us-w2.wpmucdn.com/voices.uchicago.edu/dist/9/177/files/2008/05/quine-variables-explained-away.pdf)
  — W. V. O. Quine, *Proceedings of the American Philosophical Society* 104
  (1960), 343–347. The variable-free predicate functor logic that suggested
  capabilities as predicate functors over the type universe.
- [Tagless Final](https://okmij.org/ftp/tagless-final/) — Oleg Kiselyov's
  collected notes on encoding programs against abstract interfaces, the
  encoding Fixed applies to an entire language.
- [Finally Tagless, Partially Evaluated](https://doi.org/10.1017/S0956796809007205)
  — Jacques Carette, Oleg Kiselyov and Chung-chieh Shan, APLAS 2009 / JFP 2009.
  Typed tagless interpreters and the staging discipline behind the encoding.
- [How to Make Ad-hoc Polymorphism Less Ad Hoc](https://doi.org/10.1145/75277.75283)
  — Philip Wadler and Stephen Blott, POPL 1989. Type classes: the satisfaction
  relation that Fixed's capabilities generalise.
- [Fun with Phantom Types](https://doi.org/10.1007/978-1-349-91518-7_12) — Ralf
  Hinze, 2003. Phantom parameters and type-level state; the inference of
  phantom-ness in `spec/type_system.md` §5.9.
- [Rethinking Data and Codata through Matrix Transposition](https://hackernoon.com/rethinking-data-and-codata-through-matrix-transposition)
  — the data/codata duality and its consequences for language design.

## Effects and handlers

- [Handlers of Algebraic Effects](https://doi.org/10.1007/978-3-642-00590-9_7)
  — Gordon Plotkin and Matija Pretnar, ESOP 2009. The algebraic-effect and
  handler semantics `spec/effects.md` builds on.
- [Koka: Programming with Row Polymorphic Effect Types](https://doi.org/10.4204/EPTCS.153.8)
  — Daan Leijen, MSFP 2014. Row-typed effects and the Koka language.
- [Algebraic Effects for Functional Programming](https://www.microsoft.com/en-us/research/publication/algebraic-effects-for-functional-programming/)
  — Daan Leijen, MSR-TR-2016-29. The design report behind Koka's effect system.
- [Type Directed Compilation of Row-Typed Algebraic Effects](https://doi.org/10.1145/3009837.3009872)
  — Daan Leijen, POPL 2017. Evidence passing and the compilation of effect
  rows, the model for `spec/effects.md` §7.
- [Generalized Evidence Passing for Effect Handlers](https://doi.org/10.1145/3473576)
  — Ningning Xie and Daan Leijen, ICFP 2021. Evidence vectors and the
  efficient compilation of handlers to C; the baseline Fixed's effect rows
  follow.
- [Effect Handlers in Scope](https://doi.org/10.1145/2633357.2633358) —
  Nicolas Wu, Tom Schrijvers and Ralf Hinze, Haskell 2014. Scoped handlers and
  handler extent; the confinement rule for captured evidence.

## Quantities, linearity and erasure

- [The Syntax and Semantics of Quantitative Type Theory](https://doi.org/10.1145/3209108.3209189)
  — Robert Atkey, LICS 2018. The 0/1/ω quantities and the semiring that
  `spec/quantities.md` uses.
- [Idris 2: Quantitative Type Theory in Practice](https://doi.org/10.4230/LIPIcs.ECOOP.2021.9)
  — Edwin Brady, ECOOP 2021. A production implementation of QTT; the inference
  shape Fixed broadly follows.
- [Linear Haskell: Practical Linearity in a Higher-Order Polymorphic Language](https://doi.org/10.1145/3158093)
  — Jean-Philippe Bernardy, Mathieu Boespflug, Ryan R. Newton, Simon Peyton
  Jones and Arnaud Spiwack, POPL 2018. Linearity in a higher-order language
  with polymorphism.
- [Counting Immutable Beans](https://doi.org/10.1145/3412932.3412935) —
  Sebastian Ullrich and Leonardo de Moura, IFL 2019. Lean 4's reference
  counting, borrow inference and reset/reuse; the `read` demand and the
  borrowed-parameter model.
- [Uniqueness Typing for Functional Languages with Graph Rewriting Semantics](https://doi.org/10.1017/S0960129500070109)
  — Erik Barendsen and Sjaak Smetsers, MSCS 1996. Uniqueness as a property of
  the value, which `exclusive` demands are.
- [Linearity and Uniqueness: An Entente Cordiale](https://doi.org/10.1007/978-3-030-99336-8_13)
  — Daniel Marshall, Michael Vollmer and Dominic Orchard, ESOP 2022. Why a
  binding used once (linearity) is not a value owned once (uniqueness);
  `spec/quantities.md` §4.1 keeps the two apart.

## Memory management, reuse and destinations

- [Perceus: Garbage Free Reference Counting with Reuse](https://doi.org/10.1145/3453483.3454032)
  — Alex Reinking, Ningning Xie, Leonardo de Moura and Daan Leijen, PLDI 2021.
  The heap algorithm, `dup`/`drop` placement and reuse analysis of
  `spec/memory_management.md`.
- [Reference Counting with Frame-Limited Reuse](https://doi.org/10.1145/3547634)
  — Anton Lorenzen and Daan Leijen, ICFP 2022. Why reuse can break
  garbage-freedom, and how drop-guided reuse bounds retention.
- [FP²: Fully in-Place Functional Programming](https://doi.org/10.1145/3607840)
  — Anton Lorenzen, Daan Leijen and Wouter Swierstra, ICFP 2023. `fip` and
  `fip(n)`, the allocation and stack contract of `spec/memory_management.md`
  §18.
- [The Goose Language — Specification (v4 draft)](https://aardappel.github.io/goose/docs/goose_spec.html)
  — Wouter van Oortmerssen. Data stacks, roots, destination construction and
  `return … from`; the Goose-style regions of `spec/memory_management.md`.
- [Neut](https://vekatze.github.io/neut/) — vekatze. Types compiled to closed
  resource functions `exp(selector, v)`; the model for value witnesses and
  copy-on-share.
- [Destination-Passing Style for Efficient Memory Management](https://doi.org/10.1145/3122948.3122949)
  — Amir Shaikhha et al., FHPC 2017. Callers reserve destinations and callees
  write into them; `spec/memory_management.md` §7.1.
- [Tail Modulo Cons](https://arxiv.org/abs/2102.09823) — François Bour, Basile
  Clément and Gabriel Scherer, 2021. Constructors in tail position fill a
  destination hole; the tail-modulo-constructor form.
- [Region-Based Memory Management](https://doi.org/10.1006/inco.1996.2613) —
  Mads Tofte and Jean-Pierre Talpin, *Information and Computation* 132 (1997).
  Regions and region inference, the ancestor of Fixed's region classes.
- [Combining Region Inference and Garbage Collection](https://doi.org/10.1145/512529.512547)
  — Niels Hallenberg, Martin Elsman and Mads Tofte, PLDI 2002. The region leak
  in tail-recursive loops that `spec/memory_management.md` Rule MM5.2(b)
  avoids.
- [Compiling Tree Transforms to Operate on Packed Representations](https://doi.org/10.4230/LIPIcs.ECOOP.2017.26)
  — Michael Vollmer et al., ECOOP 2017. Gibbon's location calculus and packed
  regions; chunked regions with per-region counts in
  `spec/memory_management.md` §6 and §16.
- [LoCal: A Language for Programs Operating on Serialized Data](https://doi.org/10.1145/3314221.3314631)
  — Michael Vollmer et al., PLDI 2019. Serialized-data traversal and region
  ownership.
- [Efficient Tree-Traversals: Reconciling Parallelism and Dense Data Representations](https://arxiv.org/abs/2107.00522)
  — Chaitanya Koparkar et al., ICFP 2021. Parallel traversal of packed
  representations.
- [Disentanglement in Nested-Parallel Programs](https://doi.org/10.1145/3371115)
  — Sam Westrick, Rohan Yadav, Matthew Fluet and Umut Acar, POPL 2020.
  Per-task heaps; Fixed is disentangled by construction
  (`spec/memory_management.md` §17).
- [OxCaml locality](https://oxcaml.org/) — Jane Street's separate locals
  stack, per-function and per-loop regions, `exclave_`, and a tail call that
  closes the region; `spec/memory_management.md` §5.1.
- [Lobster](https://strlen.com/lobster/) — Wouter van Oortmerssen's
  compile-time ownership with borrows and ownership specialization; the
  fallback for large functions in `spec/memory_management.md` §7.4.

## Pattern matching and coverage

- [Compiling Pattern Matching to Good Decision Trees](https://doi.org/10.1145/1411304.1411311)
  — Luc Maranget, ML Workshop 2008. The decision-tree and exhaustiveness
  algorithm `spec/pattern_matching.md` names as its reference.
- [Warnings for Pattern Matching](https://doi.org/10.1017/S0956796807006223) —
  Luc Maranget, *Journal of Functional Programming* 17(3), 2007. Usefulness,
  redundancy and the pattern-matrix algorithm.
- Alexander Krauss, *Pattern Matching in Theorem Proving* — nested-pattern
  reasoning and coverage, the reference for `spec/pattern_matching.md` §6.
- Benjamin C. Pierce, *Types and Programming Languages*, MIT Press, 2002,
  ch. 11 — the pattern-typing rules of `spec/pattern_matching.md` §4.

## Properties, refinement and testing

- [An Axiomatic Basis for Computer Programming](https://doi.org/10.1145/363235.363259)
  — C. A. R. Hoare, *Communications of the ACM* 12(10), 1969.
  Preconditions/postconditions; the deferred `result` binder of
  `spec/properties.md` §3.3.
- [Assigning Meanings to Programs](https://doi.org/10.1090/psapm/019/0235771)
  — Robert W. Floyd, 1967. Verification conditions, the ancestor of the
  static prover's obligations.
- [QuickCheck: A Lightweight Tool for Random Testing of Haskell Programs](https://doi.org/10.1145/351240.351266)
  — Koen Claessen and John Hughes, ICFP 2000. The property-based testing model
  of `spec/properties.md` §8.
- [Hypothesis](https://hypothesis.readthedocs.io/en/latest/) — David MacIver
  et al. The shrinking model used for the PBT fallback.
- [Why3 — Where Programs Meet Provers](https://doi.org/10.1007/978-3-642-37036-6_8)
  — Jean-Christophe Filliâtre and Andrei Paskevich, ESOP 2013. The
  prover-driven verification pipeline Fixed's property verifier resembles.
- [Refinement Types for Haskell](https://doi.org/10.1145/2628136.2628161) —
  Niki Vazou, Eric L. Seidel, Ranjit Jhala, Dimitrios Vytiniotis and Simon
  Peyton Jones, ICFP 2014. Liquid Haskell; refinement types as the model for
  `prop`-constrained types.
- [A Constraint Sequent Calculus for First-Order Logic with Linear Integer Arithmetic](https://doi.org/10.1007/978-3-540-89439-1_20)
  — Philipp Rümmer, LPAR 2008. The Princess prover Fixed uses for static
  obligations (`spec/properties.md` OQ-P1).

## Cardinality and inhabitation

- [Counting Type Inhabitants](https://web.archive.org/web/20181221193229/https://alexknvl.com/posts/counting-type-inhabitants.html)
  — Alex Knvl, 2018. The sum/product/exponential arithmetic and the infinite
  tiers; the counting model of `spec/reports.md` §4.
- [Which Simple Types Have a Unique Inhabitant?](https://doi.org/10.1145/2784731.2784757)
  — Gabriel Scherer and Didier Rémy, ICFP 2015. Uniqueness of inhabitation
  modulo βη equivalence; the implementation-space side of the report.
- [Deciding Equivalence with Sums and the Empty Type](https://doi.org/10.1145/3009837.3009901)
  — Gabriel Scherer, POPL 2017. βη equivalence with sums and the empty type.
- [Inhabitation in Typed Lambda-Calculi (A Syntactic Approach)](https://doi.org/10.1007/3-540-62688-3_47)
  — Paweł Urzyczyn, TLCA 1997. Inhabitation is undecidable for the
  second-order λ-calculus; why a polymorphic count above one needs an exact
  reduction, never a guess.
- [Feat: Functional Enumeration of Algebraic Types](https://doi.org/10.1145/2364506.2364515)
  — Jonas Duregård, Patrik Jansson and Meng Wang, Haskell Symposium 2012.
  Enumerating the inhabitants a count reports.
- [The Algebra of Algebraic Data Types](https://web.archive.org/web/20140222124650/chris-taylor.github.io/blog/2013/02/10/the-algebra-of-algebraic-data-types)
  — Chris Taylor, 2013. The sum/product/exponential reading for a general
  audience.
- [Theorems for Free!](https://doi.org/10.1145/99370.99404) — Philip Wadler,
  FPCA 1989. Parametricity and the free theorems that constrain what a
  polymorphic implementation can do.
- John C. Reynolds, *Types, Abstraction and Parametric Polymorphism*,
  Information Processing 83 (IFIP), 1983. The original statement of
  parametricity.

## Recursion schemes and data/codata

- [Functional Programming with Bananas, Lenses, Envelopes and Barbed Wire](https://doi.org/10.1007/3540543961_7)
  — Erik Meijer, Maarten Fokkinga and Ross Paterson, FPCA 1991. Catamorphisms,
  anamorphisms and the recursion-scheme vocabulary of the cookbook.
- [Rethinking Data and Codata through Matrix Transposition](https://hackernoon.com/rethinking-data-and-codata-through-matrix-transposition)
  — data/codata duality and its implications for language design.

## Compiler architecture and Scala 3

- [Simplicitly: Foundations and Applications of Implicit Function Types](https://doi.org/10.1145/3158130)
  — Martin Odersky et al., POPL 2018. Contextual abstractions and implicit
  function types; the Scala 3 machinery behind capabilities.
- [Type-Level Programming with Match Types](https://doi.org/10.1145/3498698)
  — David Blanvillain, Philipp Brachthäuser, Martin Kjaer and Martin Odersky,
  POPL 2022. Match types; the dotc type-level machinery Fixed's reference
  notes read.
- [Capturing Types](https://doi.org/10.1145/3618003) — Martin Odersky et al.,
  *ACM Transactions on Programming Languages and Systems* 45(4), 2023. Scala 3's
  capture checker; the closest existing analog to Fixed's capability checker.
- [The Scala 3 compiler (`dotc`)](https://github.com/scala/scala3) — the
  reference implementation whose parser, typer pipeline, Recheck phases and
  capture checker are mapped in `docs/references/scala3-compiler-reference.md`.
- [An Overview of TASTy](https://docs.scala-lang.org/scala3/guides/tasty-overview.html)
  — the typed-tree serialisation behind Scala 3's separate compilation; the
  comparison point for Fixed's interface and body tiers.

## Related projects and sites

- [Koka language](https://koka-lang.github.io/koka/doc/index.html) — algebraic
  effects with evidence passing, Perceus reference counting, FBIP and reuse.
  Fixed adopts the model wholesale and pushes the representation choice into
  the compiler.
- [eo](https://eo.constructive.dev/) — existential optics for Scala 3, and the
  source of the site pipeline.
- [Cardinality](https://cardinality.constructive.dev/) — the sister
  calculator: a type is a state space, and the build can measure it.
- [Constructive](https://www.constructive.dev/) — the consultancy behind the
  project.

## Where the specification cites these

- `spec/compilation_model.md` §2 — Neut (value witnesses).
- `spec/cost.md` §2 — FP² (the `fip` contract).
- `spec/memory_management.md` §2 — Perceus, frame-limited reuse, FP²,
  Counting Immutable Beans, the MLKit region work, Goose, Gibbon/LoCal,
  Neut, OxCaml locality, Lobster, disentanglement, destination-passing style
  and tail modulo cons.
- `spec/quantities.md` §2 — Atkey (QTT), Brady (Idris 2), Perceus,
  Barendsen/Smetsers (uniqueness), Marshall/Vollmer/Orchard
  (linearity vs uniqueness), Ullrich/de Moura and FP².
- `spec/pattern_matching.md` §2 — Maranget's decision-tree algorithm;
  Krauss on nested-pattern reasoning; Pierce, *Types and Programming
  Languages*, ch. 11.
- `spec/properties.md` §2 — Hoare, Floyd, QuickCheck, Hypothesis, Why3; the
  static prover is Princess (`OQ-P1`).
- `spec/reports.md` §4 — the cardinality and inhabitation sources above.
- `docs/references/scala3-compiler-reference.md` — dotc, TASTy and the capture
  checker.
