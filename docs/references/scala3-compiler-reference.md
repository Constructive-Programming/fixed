# Scala 3 Compiler — Reference for `fixed`

A reading guide and design distillation of `dotc` (the Scala 3 / Dotty compiler), tailored to the parts that map cleanly onto Fixed: the **parser**, the **typer pipeline**, and the overall **phase architecture**. Pinned to tag `3.8.2`, checked out at `$HOME/workspace/opensource/scala-compiler`.

This document is not an implementation plan. It is a map of "where to look in `dotc` when you need to make a decision in `fixed`," with concrete file paths, the abstractions worth borrowing, and the abstractions worth resisting.

References below are written `<file>:<line>` against the local checkout (`$HOME/workspace/opensource/scala-compiler`).

---

## 1. Why `dotc` is a useful reference for Fixed

Fixed has four design pressures that map directly onto choices Scala 3 has already made:

| Fixed concern | `dotc` analogue | File / system |
|---|---|---|
| Capabilities — abstract interfaces with constructors and laws | Class types + traits + capture types | `core/Types.scala`, `cc/` |
| Refinement types via `prop` and `fn -> cap` | `RefinedType`, `MatchType`, dependent method types | `core/Types.scala`, `core/MatchTypes.scala` |
| QTT quantities (0/1/ω) and erasure soundness | Erased types, `Erased` flag, capture checker's "use" analysis | `core/Flags.scala`, `cc/CheckCaptures.scala` |
| Capability-driven representation selection | Type-class derivation, `derives`, monomorphization-via-inlining | `typer/Deriving.scala`, `transform/Inlining.scala` |
| `match` desugaring to `fold`, Perceus insertion | Pattern matcher, erasure as a retyper | `transform/PatternMatcher.scala`, `transform/Erasure.scala` |

The single most important pattern to copy is the **Recheck pattern** (`transform/Recheck.scala`, `cc/CheckCaptures.scala`): once base types are inferred, additional analyses (capture sets, QTT quantities, capability bounds, prop verification) are layered on as separate "rechecker" passes that re-traverse the typed tree and *refine* each node's type, without re-running the full typer. This is the cleanest mechanism we have for the layered analyses Fixed needs.

---

## 2. Architecture at 30,000 ft

### 2.1 The lifecycle: Driver → Compiler → Run → Phases → CompilationUnit

```
                Main / Driver                       (entry point, argv parsing)
                      │
                      ▼
                  Compiler                          (the phase plan)
                      │
                      ▼
                    Run                             (one pass over a phase plan)
                      │
                      ▼
        for each phase: phase.runOn(units)
                      │
                      ▼
          for each unit: phase work               (parse / type / transform / emit)
```

Source map:
- `Driver.scala`, `Main.scala` — argument parsing, settings, orchestration. Don't try to invent a custom CLI shape from this; Fixed already has its own (`fixed compile / verify / ship`).
- `Compiler.scala` — declares `phases: List[List[Phase]]` (line 29). This is the phase plan.
- `Run.scala` — owns one execution of the plan against a list of compilation units.
- `CompilationUnit.scala` — holds the per-source state: `untpdTree`, `tpdTree`, source file, comments, suspension status.

### 2.2 Phases are grouped, then *fused*

The signature `phases: List[List[Phase]]` is intentional. Each inner list is a **phase group**; phases inside a group that extend `MiniPhase` are fused into a single tree traversal (a `MegaPhase`). This keeps each phase tiny and single-purpose while still doing one walk over the AST per group.

- `Compiler.scala:29` — the four-tier plan: `frontendPhases ::: picklerPhases ::: transformPhases ::: backendPhases`.
- `transform/MegaPhase.scala:18-120` — `MiniPhase` exposes a `prepareForK` / `transformK` pair for every tree node kind (`Ident`, `Apply`, `If`, `Match`, `ValDef`, ...). Mini-phases never traverse trees themselves — they declare per-node transforms; `MegaPhase` runs the actual walk.
- `core/Phases.scala:77-135` — `fusePhases` is the fusion implementation: any sublist of more than one phase becomes a `MegaPhase`, otherwise the phase stands alone.

### 2.3 The four phase tiers (scala3 3.8.2)

From `Compiler.scala`:

| Tier | What it produces | Examples |
|---|---|---|
| `frontendPhases` (33-46) | Typed AST, ready for pickling | `Parser`, `TyperPhase`, `WInferUnion`, `CheckUnused.PostTyper`, `PostTyper`, `UnrollDefinitions` |
| `picklerPhases` (49-58) | TASTy on disk, inlining done | `Pickler`, `Inlining`, `Staging`, `Splicing`, `PickleQuotes` |
| `transformPhases` (61-148) | Lower-level trees, ready for backend | `FirstTransform`, `RefChecks`, `PatternMatcher`, `cc.Setup`, `cc.CheckCaptures`, `Erasure`, `Constructors`, `LambdaLift`, `Flatten` |
| `backendPhases` (152-155) | JVM bytecode (or SJSIR) | `GenSJSIR`, `GenBCode` |

Two structural rules:

1. **Constraint at line 18 of `Compiler.scala`:** "DenotTransformers that change the signature of their denotation's info must go after erasure." The reason is that `TermRef`s carry a signature, and changing it would invalidate references. After erasure, signatures freeze. — Fixed faces an analogous concern: once representation-selected, capability sets are frozen; further transforms must not re-classify.
2. **Phase IDs are bounded for some checks**: `core/Phases.scala:51` — `Recheck` phases must have id < 64 because they're tracked in a bitset. Don't proliferate them.

---

## 3. Parser

`dotc` uses a hand-written recursive-descent parser, no parser generator. This is also the right call for Fixed — capability-heavy syntax with `is`/`of`/`extends`/`satisfies`/`fn -> cap` will benefit from precise control over precedence and error recovery.

### 3.1 Files

| File | Role |
|---|---|
| `parsing/Tokens.scala` | The token enumeration |
| `parsing/Scanners.scala` | Lexer (significant whitespace, indentation tracking, regions) |
| `parsing/CharArrayReader.scala` | Low-level character scanner |
| `parsing/Parsers.scala` (~5,000 lines) | The grammar — recursive descent, manual precedence climbing for operators |
| `parsing/ParserPhase.scala` | Wraps the parser as a compiler phase |

### 3.2 ParserPhase — what a phase actually looks like

`parsing/ParserPhase.scala` is the cleanest example of a phase in the codebase. The full content (61 lines) defines:

```scala
class Parser extends Phase {
  override def phaseName: String = Parser.name
  override def isCheckable: Boolean = false           // skip TreeChecker before typer
  def parse(using Context): Boolean = monitor("parser") {
    val unit = ctx.compilationUnit
    unit.untpdTree =
      if (unit.isJava) new JavaParsers.JavaParser(unit.source).parse()
      else { val p = new Parsers.Parser(unit.source); p.parse() }
  }
  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val unitContexts = for unit <- units yield ctx.fresh.setCompilationUnit(unit).withRootImports
    val ok = for unitContext <- unitContexts; if parse(using unitContext) yield unitContext
    ok.map(_.compilationUnit)
}
```

Pattern to copy for Fixed:
- Each phase is **a class with `runOn(units)`** that iterates compilation units inside a per-unit context.
- Per-unit work writes its result into a **mutable field on `CompilationUnit`** (`untpdTree`, `tpdTree`, ...). The unit is the persistent carrier across phases.
- Errors during a phase do *not* abort the whole compile — they're reported via `ctx.reporter`, and downstream phases inspect `ctx.reporter.hasErrors` to decide whether to run.

### 3.3 Parser entry pattern — context-tracking enums

`parsing/Parsers.scala:46-77` introduces three enums that travel through the recursion:

```scala
enum Location(val inParens: Boolean, val inPattern: Boolean, val inArgs: Boolean):
  case InParens, InArgs, InColonArg, InPattern, InGuard, InPatternArgs, InBlock, ElseWhere

enum ParamOwner:
  case Class, CaseClass, Def, Type, Hk, Given, ExtensionPrefix, ExtensionFollow

enum ParseKind:
  case Expr, Type, Pattern
```

These let parser methods remain pure recursive-descent functions while still knowing "am I inside a pattern arg list?" or "is this parameter list for a type alias or a method?" Without them, you end up with parser-state booleans threaded everywhere.

For Fixed: introduce equivalents early. We will need at minimum:
- `Location` — distinguishes `is X`-position from value-position (e.g., is `between(0, 100)` a function call or a cap-generating call?).
- `CapBoundCtx` — distinguishes `is`-bounds in a function parameter (where `N is Numeric` introduces `N`) from `is`-bounds in a return type (where `N` must already be in scope).
- `ParseKind` — Fixed has `cap`-mode (`prop` expressions, `forall`, `implies`), `data`-mode (variant declarations), and ordinary expression mode. They share precedence but differ on what is legal.

### 3.4 Significant indentation

`Scanners.scala` handles Scala 3's optional indentation syntax with a stack of `Region` values. Fixed has chosen a brace-and-newline style without significant indentation per the examples — so we can drop most of this complexity. Keep the `Region` concept anyway: it's the right abstraction for tracking nested `{ }`, `( )`, `[ ]`, and the `match`-arm boundaries used for indented-style match.

### 3.5 Single tree hierarchy, two type parameterizations

**This is the most important AST decision in `dotc`, and Fixed should adopt it directly.**

`ast/Trees.scala:22-56`:

```scala
type Untyped = Type | Null

abstract class Tree[+T <: Untyped] extends Positioned, ... {
  type ThisTree[T <: Untyped] <: Tree[T]
  protected var myTpe: T @uncheckedVariance = uninitialized
  final def tpe: T = if myTpe == null then throw UnAssignedTypeException(this) else myTpe
}
```

There is **one** tree hierarchy. Untyped trees have `tpe: Null`; typed trees have `tpe: Type`. Two singletons — `ast/untpd.scala` and `ast/tpd.scala` — pin `T` and add untyped-only or typed-only nodes (`untpd.ParsedTry`, `untpd.SymbolLit`, etc.).

The benefits:
- Parser and typer share 95% of node definitions; the typer only writes types into `myTpe` (copy-on-write).
- Phases can declare whether they expect untyped or typed trees in their type signatures.
- Untyped trees can host typed sub-trees via `untpd.TypedSplice` — used during typer when it needs to embed a re-typed expression back into a partially-untyped context.

For Fixed: parameterize the AST over `<T = Unit | TypeInfo | TypedInfo>` and use the same untpd/tpd split. Don't be tempted to make two separate AST types.

---

## 4. Typer pipeline

### 4.1 Files

| File | Role |
|---|---|
| `typer/TyperPhase.scala` | Phase wrapper, with three sub-phases |
| `typer/Namer.scala` (2,268 lines) | Builds symbols / denotations from untyped trees |
| `typer/Typer.scala` (5,309 lines) | The bidirectional typer — `typed(tree, pt)` is the central recursion |
| `typer/Applications.scala` (2,959 lines) | Application typing: argument selection, overload resolution, eta-expansion |
| `typer/ProtoTypes.scala` (1,091 lines) | Prototype types — the "expected type" passed down during checking |
| `typer/Inferencing.scala` | Type variable instantiation, fully-defined checks |
| `typer/Implicits.scala` | Implicit search and contextual abstraction |
| `typer/RefChecks.scala` | Override checks, abstract member checks |
| `typer/Deriving.scala` | `derives` clauses — how Mirror-based derivation gets wired |
| `typer/ProtoTypes.scala` | The bidirectional typing scaffolding |
| `typer/Synthesizer.scala` | Synthesis of compiler-provided implicits (Mirror, ClassTag, ...) |

### 4.2 The typer's two-pass structure

`typer/TyperPhase.scala:64-65`:

```scala
override val subPhases: List[SubPhase] = List(
  SubPhase("indexing"), SubPhase("typechecking"), SubPhase("checkingJava"))
```

Pass 1 — **indexing** (`Namer`): creates symbols for every top-level definition without typing their bodies. Bodies become *lazy completers*: `info: LazyType` that runs full typing when something else needs the result type. This is what makes mutual recursion work.

Pass 2 — **typechecking** (`Typer`): types all bodies. The lazy completers from pass 1 are forced as needed, so order-of-definition doesn't matter inside a compilation unit.

For Fixed: this is the right shape. **Do not** try to type bodies in declaration order. Mutual `cap`s and mutual `data`s are normal.

### 4.3 Bidirectional typing via prototypes

`typer/ProtoTypes.scala` is where Fixed should look for the inference machinery. The key insight: every time the typer types a sub-expression, it carries an **expected type (prototype)** as an argument. The signature (`typer/Typer.scala`) is roughly:

```scala
def typed(tree: untpd.Tree, pt: Type)(using Context): tpd.Tree
```

`pt` is the prototype. It's not always a real type — it can be a `FunProto` ("I am being applied to these arguments"), a `SelectionProto` ("I expect a member named `foo` here"), or `WildcardType` (no expectation). The typer checks `tp <:< pt` after normalization (`ProtoTypes.scala:57-78`):

```scala
def normalizedCompatible(tp: Type, pt: Type, keepConstraint: Boolean)(using Context): Boolean
```

Why this matters for Fixed:
- **`is` bounds at parameter sites are exactly prototypes.** When typing `let n = some_expr` where `some_expr` flows into a parameter `n: N is Numeric`, the prototype "must satisfy `Numeric`" travels into the typing of `some_expr`.
- **`fn -> cap` arguments need symbolic prototypes.** When `between(lo, hi)` is called with `lo` and `hi` whose values are not yet known, the prototype carries `lo` and `hi` as *symbolic terms* so the cap body's `prop in_range: min <= Self && Self <= max` can be checked against the actual call site.
- **Implicit/synthesizer search uses prototypes.** Fixed's `satisfies` resolution at constructor-call sites (`Optional.some(x)` → `Maybe.Just(x)`) is structurally the same problem as Scala's implicit search: given a prototype, find an in-scope satisfaction that matches.

The implicit-search machinery (`typer/Implicits.scala`, `typer/Synthesizer.scala`) is worth reading as a model for how Fixed will resolve `satisfies` declarations.

### 4.4 Symbols and denotations — the "phase-indexed view" of definitions

This is the abstraction that makes `dotc` work as a multi-phase compiler, and Fixed will need its own version.

- A **`Symbol`** (`core/Symbols.scala`) is just a unique identifier — no semantic content.
- A **`Denotation`** (`core/Denotations.scala`, `core/SymDenotations.scala`) is the symbol's content: name, type/info, owner, flags. It's **phase-indexed** — each symbol has a `denot` that is valid for a range of phases, and may be different in earlier/later phases.
- `atPhase(p) { sym.info }` lets you query "what was `foo`'s type after phase `p`?"

Worked example (`docs/_docs/contributing/architecture/time.md:36-62`):

```scala
def foo(b: Box)(x: b.X): List[b.X]
// after typer:   (b: Box)(x: b.X): List[b.X]
// after erasure: (b: Box, x: Object): List
```

For Fixed: this is exactly how to layer **base type → capability bounds → QTT quantities → concrete representation** without four parallel data structures. One symbol. Phase-indexed denotations:

| Phase | Denotation refinement |
|---|---|
| After typer | Base type and primary capability bounds |
| After capability check | Full capability set (with prop obligations, `extends` closure) |
| After quantity check | QTT quantity (0/1/ω) annotation |
| After representation selection | Concrete C-level representation |
| After Perceus | Reference-counting metadata (dup/drop sites) |

A function written against capabilities can be examined "at any phase" to ask the right question without needing global reanalysis.

### 4.5 Context

`core/Contexts.scala` defines the `Context` carried via `using Context` through every typer method. Properties (`docs/_docs/contributing/architecture/context.md`):

| Property | Meaning |
|---|---|
| `compilationUnit` | Current source file's unit |
| `phase` | Current phase |
| `period` | Current `(runId, phaseId)` pair — used to index denotations |
| `owner` | Current enclosing definition (the `Symbol`) |
| `scope` | Current local scope |
| `typer` | Current typer instance |
| `mode` | Type-checking mode flags (e.g., "in pattern", "in type", "expected proto") |
| `typerState` | Current type variable constraint set |
| `searchHistory` | Implicit search state |
| `reporter` | For emitting errors / warnings |

**The single most-bitten mistake in scala3 contributor history** (per `architecture/context.md:20-27`): capturing a `Context` in a closure that outlives a run. Contexts are heavy and reference half the world; a captured context = memory leak across runs. Convention: **never store `Context` in a long-lived object.** Pass it as `using Context` parameter so Scala's effect tracking surfaces accidental captures.

For Fixed: same rule. The compiler is intended to be invoked from an LSP / `fixed verify --watch` style harness; long-lived caches must hold *symbols*, not contexts.

### 4.6 Typer entry points and dispatch

`typer/Typer.scala` is dominated by a giant `typedX` family: `typedIdent`, `typedSelect`, `typedApply`, `typedTypeApply`, `typedBlock`, `typedIf`, `typedMatch`, `typedClass`, `typedDefDef`, ...

The dispatcher is a single `typed(tree, pt)` method that pattern-matches on the tree shape and calls the appropriate `typedX`. Each `typedX`:

1. Receives `(tree: untpd.X, pt: Type)`.
2. May modify `Context` (extending the scope, etc.).
3. Recurses into sub-trees with refined prototypes.
4. Produces a `tpd.X` with `myTpe` filled in.

For Fixed: this is the right structure. The temptation to produce a "pretty-printed" pattern-match-on-everything will lead to tangled code; instead, one method per node kind keeps each routine local and reviewable.

---

## 5. The transform tier

### 5.1 What it's for

After typer, the AST is correct but high-level. The transform tier rewrites it into progressively lower-level forms until it's essentially a structured form of bytecode. Fixed has the same problem: typed AST → match-desugared → Perceus-annotated → C-emission ready.

### 5.2 The miniphase / megaphase pattern in detail

`transform/MegaPhase.scala:18-120` defines `MiniPhase`. Every node kind has *two* hooks:

- `prepareForX(tree)(using Context): Context` — runs **on the way down** the tree. Returns a (possibly modified) Context that will be used for the children. This is how phases push scope/state down.
- `transformX(tree)(using Context): Tree` — runs **on the way up**. Returns the (possibly rewritten) tree. The tree's children have already been transformed.

The `MegaPhase` walks the tree once, calling each mini-phase's hooks in order. So a group of, say, six mini-phases produces *one* tree walk that does six logical transformations.

Implication for Fixed: many small, single-concern phases beat one big monolithic transform. Concretely:

- `MatchToFold` (desugar `match` to `fold` calls)
- `IfToBoolFold` (desugar `if` to `bool.fold`)
- `EraseQuantityZero` (drop bindings whose QTT quantity is 0)
- `InsertDup` (Perceus dup at sharing points)
- `InsertDrop` (Perceus drop at last-use points)
- `SelectReuse` (FBIP reuse-token threading)

These six can fuse into one walk. Each is independently reviewable, independently testable.

### 5.3 DenotTransformers — when phases change *types*

`core/DenotTransformers.scala` defines the contract for phases that change a symbol's `info` (its type). Examples:

- `Erasure` rewrites every type to its JVM erasure.
- `ExtensionMethods` adds extension methods to companion objects.
- `Constructors` moves field initializers into primary constructors.

The contract: a `DenotTransformer` produces a new denotation for a symbol valid from `phase.next` onward. The `atPhase(...)` machinery from §4.4 reads back the old denotation when querying "before this phase."

For Fixed, the analogous transformers are:

- **CapabilitySetClosurePhase** — closes capability sets under `extends`, computes prop-obligation lists.
- **RepresentationSelectionPhase** — assigns a concrete C representation to each symbol.
- **QuantityInferencePhase** — assigns 0/1/ω to each binding.

Each one extends `DenotTransformer`. After it runs, querying a symbol's "info at this phase" yields the refined view.

### 5.4 Recheck — the pattern Fixed should copy hardest

`transform/Recheck.scala` (683 lines) and `cc/CheckCaptures.scala` (2,290 lines) together implement Scala 3's **capture checker**: a re-typer that runs *after* the main typer, traverses the typed tree, and produces a *new* type for each node that includes capture-set information.

The Recheck pattern:
1. The recheck phase implicitly extends `transform/PreRecheck.scala`, which prepares the world (decoupling symbol denotations so re-typing doesn't permanently mutate them).
2. The recheck phase walks the typed tree, calling per-node-kind methods (`recheckIdent`, `recheckApply`, ...).
3. Each method returns a *refined* type. The original tree's `tpe` field stays put; refined types are stashed in an attachment (`Recheck.RecheckedTypes` at `Recheck.scala:32`).
4. Optionally, after rechecking, `addRecheckedTypes` (line 38) walks the tree and replaces each node's `tpe` with the refined version.

Why this is the right pattern for Fixed:
- The base type-checker only needs to handle *types*, not capability sets, not QTT quantities, not prop obligations.
- Each layered analysis is its own Recheck pass with its own per-node logic.
- Failures in capability checking don't poison the base type-checker's symbol table — the recheck attachment can be discarded.
- Multiple Recheck phases can run sequentially:
  - Recheck 1: close capability sets, verify `satisfies` coherence.
  - Recheck 2: verify `prop` invariants (static where possible, queue testing where not).
  - Recheck 3: infer QTT quantities, verify erasure soundness.
  - Recheck 4: select representations.
- Each recheck phase has its own ID (`Phases.scala:51` — bitset capped at 64; plenty of room).

`cc/CheckCaptures.scala:43-82` shows the per-pass *environment* the recheck carries:

```scala
case class Env(
    owner: Symbol,
    kind: EnvKind,
    captured: CaptureSet,
    outer0: Env | Null,
    nestedClosure: Symbol = NoSymbol)
```

For Fixed's QTT pass: an analogous `QtyEnv` carries the current quantity context (which bindings are 0, 1, ω) and the linearity obligations seen so far.

### 5.5 Pattern matcher and erasure as worked examples

- `transform/PatternMatcher.scala` (1,150 lines) — turns `Match` trees into `If`/`Block`/`Switch` trees by compiling the match decision tree. Includes exhaustivity and reachability checks. **Fixed's `match → fold` desugaring is the same shape**: pattern-match on data variants → call sites of the data's auto-derived `fold`.
- `transform/Erasure.scala` (1,066 lines) — walks the typed tree and re-types it using JVM type rules. This is interesting because *it is implemented as a Recheck-like phase* — same pattern as the capture checker. **Fixed's representation selection is structurally identical**: walk the typed tree and re-type each node with concrete C-level representations.

If you read only one transform phase end-to-end, read `Erasure.scala`. It's the cleanest example of "traverse a typed tree and re-derive every node's type under a different type system."

---

## 6. The capture checker (`cc/`) — the closest analog to Fixed's capabilities

Scala 3's capture checker tracks **what capabilities a value captures from its surrounding scope**. It's experimental but heavily developed in 3.8. Its problem shape is closer to Fixed than any other part of `dotc`:

- A function's type carries a *capture set* describing what runtime resources (file handles, IO contexts, ...) it touches.
- The capability hierarchy from `cc/Capability.scala:30-50`:

```
Capability --+-- RootCapability ----+-- GlobalCap
             |                      +-- FreshCap
             |                      +-- ResultCap
             |
             +-- CoreCapability ----+-- ObjectCapability --+-- TermRef
             |                      |                      +-- ThisType
             |                      |                      +-- TermParamRef
             |                      |
             |                      +-- SetCapability -----+-- TypeRef
             |                                             +-- TypeParamRef
             |
             +-- DerivedCapability -+-- Reach
                                    +-- Only
                                    +-- ReadOnly
                                    +-- Maybe
```

This is **directly relevant to Fixed**: where dotc has "capability" we have "capability"; where dotc has `RootCapability` we have stdlib effect handlers (Console, FileSystem, ...); where dotc has `DerivedCapability` (Reach, ReadOnly) we have refinement caps (`between(0, 100)`, `Sorted`).

### 6.1 Files worth reading

| File | What it teaches |
|---|---|
| `cc/Capability.scala` (~700 lines) | The capability ADT itself; how capabilities are represented as types |
| `cc/CaptureSet.scala` | Sets of capabilities as a lattice with subset constraints |
| `cc/Setup.scala` | Pre-processing: decorating the typed tree with capture-set placeholders before recheck |
| `cc/CheckCaptures.scala` | The recheck phase: walks trees, propagates capture sets, checks containment |
| `cc/SepCheck.scala` | Separation checking: "this capability isn't shared with that one" |
| `cc/Mutability.scala` | Read-only / mutable distinction tracked on capabilities |
| `docs/_docs/internals/cc/use-design.md` | The `@use` annotation design — closely related to Fixed's QTT linearity |

### 6.2 The `@use` design — a direct parallel to Fixed's quantity-1 bindings

`docs/_docs/internals/cc/use-design.md` describes a design for `@use`-annotated parameters: parameters that are *consumed* rather than just *referenced*. The rules (excerpted):

> 1. Have `@use` annotation on type parameters and value parameters of regular methods (not anonymous functions).
> 2. In `markFree`, keep track whether a capture set variable or reach capability is used directly in the method where it is defined, or in a nested context.
> 3. Disallow charging a reach capability `xs*` to the environment of the method where `xs` is a parameter unless `xs` is declared `@use`.

Fixed's QTT quantity-1 bindings have the same shape: a parameter that is consumed (used exactly once) gets stronger guarantees (no RC needed, in-place reuse). The design constraint that `@use` cannot appear on anonymous-function parameters is also relevant: closures with linear captures need careful treatment.

`@use` inference rules (from the same doc):

> - `@use` is implied for a term parameter `x` of a method if `x`'s type contains a boxed cap and `x` or `x*` is not referred to in the result type of the method.
> - `@use` is implied for a capture set parameter `C` of a method if `C` is not referred to in the result type of the method.

Translated to Fixed: a parameter is quantity-1 (linear) if it's consumed in the body and not mentioned in the return type. This is a recipe — copy it.

---

## 7. Concrete recommendations for `fixed`'s compiler

Where the dotc patterns translate cleanly to Fixed, and where they don't.

### 7.1 Adopt directly

| Pattern | Why |
|---|---|
| **`Compiler` class with `phases: List[List[Phase]]`** | Plain, extensible, debuggable. Trivial to add or remove phases. |
| **`MiniPhase`/`MegaPhase` fusion** | Fixed will accumulate many small transforms (match desugar, Perceus, evidence passing, ...). Keep them small; let fusion give us one walk per group. |
| **Phase-indexed denotations on a single symbol** | One `Symbol` per definition with phase-keyed views. Avoids the trap of parallel `Map[Symbol, ...]` per analysis. |
| **Single `Tree[T <: Untyped]` hierarchy with untpd/tpd split** | Don't build two AST types. |
| **Bidirectional typing with prototype types** | Fixed's `is` bounds, `fn -> cap` symbolic args, and `satisfies` resolution all want this. |
| **Recheck pattern for layered analyses** | Capability closure, prop verification, QTT inference, representation selection — each is a separate Recheck phase. |
| **Two-pass typer (Namer index → Typer typecheck)** | Mutual recursion across `cap`s and `data`s requires it. |
| **`using Context` parameter convention** | Avoid global state; avoid capturing context in long-lived objects. |
| **Per-node-kind `prepareForX` / `transformX` hooks** | Cheap to add a new analysis as a mini-phase; expensive to refactor a monolithic visitor. |

### 7.2 Resist or adapt

| Pattern | Why we should not copy it as-is |
|---|---|
| **TASTy pickling between frontend and transforms** | Scala 3 needs TASTy for separate compilation and incremental builds. Fixed's `verify + ship` model can defer TASTy until we have multi-module compilation; before that it's premature. |
| **Inline expansion as a phase** | Scala's `inline` is a language feature. Fixed has no surface-level inline; if we need it for performance, drive it from PGO data, not source annotation. |
| **The full `Type` ADT (RefinedType, RecType, MatchType, AppliedType, TypeBounds, ...)** | This complexity comes from Scala's commitment to subtyping + path-dependent types + match types + variance + GADTs. Fixed has structurally simpler types — a flat capability set + an optional refinement. Don't import the complexity. |
| **JVM erasure semantics** | Irrelevant; we target C. |
| **Implicit search** | Fixed's `satisfies` resolution is *much* more constrained than implicit search (modular, no global coherence, scope-controlled via `use`). Read `Implicits.scala` for inspiration but don't port the full algorithm. |
| **`MegaPhase` having a hook for every node kind** | We'll only have ~20 node kinds, not Scala's ~50. The mini-phase API can be smaller. |
| **PolyType / MethodType / ExprType three-way split** | Scala separates these for legacy reasons (curried methods, by-name parameters). Fixed has uniform `fn` — a single function type suffices. |

### 7.3 Bootstrap: Scala 3 (decided)

Scala 3 is the bootstrap host language. The implementation plan (`docs/plans/implementation-plan.md`, Phase 2) still specifies Rust file paths and needs revision to reflect this.

What this means for the patterns above:

- `Phase`, `MiniPhase`, `MegaPhase`, `Tree[T <: Untyped]`, `SymDenotation`, `Context`, `ProtoTypes`, `Recheck` — all lift directly. Many will be near-verbatim ports.
- `using Context` is a Scala 3 native feature; no threading required.
- The capture checker (`cc/`) is the closest existing reference for Fixed's capability checker — read it first when implementing Phase 3.

Project layout to plan toward (sbt-based):

```
compiler/src/fixed/        ← analogous to dotc/src/dotty/tools/dotc/
  ast/                     ← Trees, untpd, tpd, Desugar
  core/                    ← Symbols, Types, Phases, Contexts
  parsing/                 ← Scanners, Parsers, ParserPhase
  typer/                   ← Namer, Typer, ProtoTypes
  transform/               ← MiniPhase + concrete phases
  caps/                    ← Capability closure, satisfies resolution (analog of cc/)
  qtt/                     ← Quantity inference (analog of cc/'s @use design)
  repr/                    ← Representation selection
  perceus/                 ← Dup/drop insertion
  backend/                 ← LLVM IR emission
  Compiler.scala           ← The phase plan
library/src/fixed/         ← stdlib in .fixed
```

Open follow-up questions tracked separately:
1. **JVM vs Scala Native** for distribution — JVM is the obvious default for the prototype; Scala Native becomes interesting once iteration slows and `fixed ship` distribution matters.
2. **Codegen target** — current plan says C; the LLVM direction (textual `.ll` first, in-process bindings later) was discussed but not committed.

---

## 8. Reading order — first pass

If you have one afternoon, read in this order:

1. `docs/_docs/contributing/architecture/lifecycle.md` — orientation (10 min)
2. `docs/_docs/contributing/architecture/phases.md` — phase categories (10 min)
3. `Compiler.scala:29-155` — the actual phase plan (20 min — read every phase comment, even if only to mentally skip the JS-specific ones)
4. `parsing/ParserPhase.scala` (full file, 67 lines) — the simplest phase (5 min)
5. `transform/MegaPhase.scala:18-120` — `MiniPhase` API (15 min)
6. `typer/TyperPhase.scala` (full file, 128 lines) — Namer/Typer subphases (15 min)
7. `typer/ProtoTypes.scala:30-100` — bidirectional typing (20 min)
8. `transform/Recheck.scala:1-115` — the recheck pattern (15 min)
9. `cc/Capability.scala:30-80` — capability hierarchy (15 min)
10. `docs/_docs/internals/cc/use-design.md` (full file, 70 lines) — `@use` design (10 min)

That's ~2 hours and gives you a working mental model of the full compiler.

For a deeper second pass, read `transform/Erasure.scala` end-to-end as the canonical example of a re-typer.

---

## 9. Things I noticed but didn't dig into

Recording these so they're not lost:

- **`transform/Inlining.scala` and the staging phases** (`Staging`, `Splicing`, `PickleQuotes`) — Scala 3's quote/splice metaprogramming, with the Phase Consistency Principle. Likely irrelevant to Fixed but worth a glance if we ever consider compile-time evaluation of `prop` expressions.
- **`transform/init/`** — initialization checker. Catches uses of fields before they're initialized. Could be relevant once Fixed has `data` recursion that needs careful initialization order.
- **`transform/patmat/`** — pattern matcher's exhaustivity engine. Uses Space-based subtyping checks. Definitely relevant once we implement Fixed's exhaustivity check on `data` types.
- **`semanticdb/`** — emits an analysis-friendly file format. Fixed's "structured JSON output" goal could borrow this idea (or, more pragmatically, just emit JSON directly).
- **`presentation-compiler/`** — keeps a long-running typed view of a workspace, used by IDEs. Worth studying when Fixed needs LSP support.
- **`bench/` directory** — micro-benchmarks of the compiler itself. We'll want one of these eventually.

---

## Appendix: Where the code lives

```
$HOME/workspace/opensource/scala-compiler/
├── compiler/src/dotty/tools/dotc/        ← all of the above
│   ├── ast/                               ← Trees, untpd, tpd, Desugar
│   ├── core/                              ← Symbols, Types, Phases, Contexts
│   ├── parsing/                           ← Scanners, Parsers, ParserPhase
│   ├── typer/                             ← Namer, Typer, ProtoTypes, Implicits
│   ├── transform/                         ← Mini/MegaPhase + 100+ phases
│   ├── cc/                                ← Capture checker (closest to Fixed's caps)
│   ├── backend/                           ← Bytecode emission
│   ├── Compiler.scala                     ← The phase plan
│   ├── Run.scala, Main.scala, Driver.scala
│   └── CompilationUnit.scala
├── library/                               ← Standard library
├── docs/_docs/contributing/architecture/  ← Official architectural docs (read these!)
├── docs/_docs/internals/cc/               ← Capture-checker design notes
└── tests/                                 ← Compiler tests, including .check files
```
