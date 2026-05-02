# Fixed Compiler — Phase 2.1: Incremental & Resilient Parsing

**Status:** Draft v0.1 — pre-implementation
**Scope:** Make the parser functional, error-tolerant, and trivia-preserving so it can serve as the front-end for an LSP/IDE and the substrate for later incremental work.
**Prerequisite:** Phase 2 (Parser + AST, M5 milestone) — i.e. all 11 examples parse on a happy path.
**Position:** Inserted between Phase 2 (parser produces an AST) and Phase 3 (typer consumes it). Layer 1 of a two-layer plan; Layer 2 (lossless syntax tree + on-edit incremental reparse) is deferred to Phase 2.2.

## 1. Goals

The parser today (Phase 2 M5):

- Halts effectively on the first unrecognised production (`unsupported()` returns a synthetic `Ident("<error>")` and consumes one token).
- Mutates a caller-supplied `Reporter` for diagnostics — the parser's *return value* is the tree alone.
- Discards comments and blank lines inside `Scanner.skipTrivia`.
- Has no AST node for "I tried to parse X here but couldn't" beyond the placeholder identifier.

Each is a blocker for serving an LSP. Phase 2.1 fixes them in three deliberately small layers.

| # | Deliverable | What changes |
|---|---|---|
| 1 | Functional parser API | `Parser.parse(SourceFile): ParseResult` — tree + diagnostics + trivia, no caller-supplied `Reporter`. |
| 2 | Error recovery + AST gap nodes | Synchronisation points at decl/block/expr/type boundaries. New `Trees.Error` and `Trees.Missing` nodes carry recovered material. |
| 3 | Trivia retention | Comments and blank lines preserved in a side table keyed by token offset, so the AST stays unchanged but a printer/formatter/LSP hover can recover them. |

### 1.1 Non-goals (deferred to Phase 2.2 or later)

- Lossless concrete syntax tree (CST), green/red node split, content-addressed caching.
- On-edit incremental reparse with top-level decl cache invalidation.
- Doc-comment (`///`) classification and AST attachment.
- Stable cross-edit AST identifiers.
- Token-level "trailing trivia" slot distinct from leading trivia.

The split is deliberate: Layer 1 unblocks LSP today (via "reparse the whole file on every keystroke" — fast enough at the current Scanner's 80 ns/token) without committing the typer/codegen to a CST view.

## 2. Functional parser API

### 2.1 Public surface (target)

```scala
package fixed.parsing

final case class ParseResult(
    tree: Tree,                          // CompilationUnit, possibly containing Error/Missing
    diagnostics: List[Diagnostic],       // emitted in source order
    trivia: TriviaTable                  // keyed by token start offset
)

object Parser:
  def parse(source: SourceFile): ParseResult
```

The legacy `parse(source, reporter): Tree` overload is kept for one release as a thin adapter (`reporter` receives `result.diagnostics`), then removed in Phase 3.

### 2.2 Internal threading

Two viable shapes for the implementation; we pick the one with the smaller diff and equal performance.

| Shape | Implementation cost | Hot-path cost | Decision |
|---|---|---|---|
| Pure `State` monad — diagnostics in `ScannerState` | High — every `step` and `scanXxx` returns a richer tuple | Possible regression on per-step copy | Defer |
| Private mutable buffer inside Parser/Scanner; immutable result at API boundary | Low — drop-in over current `Reporter` | Identical to today | **Adopt** |

Rationale: "functional by default" is a contract about how callers see the parser, not a prescription that every internal helper return immutable state. The Scanner has been profile-driven down to 80 ns/token (commit `29b45d3`); we are not going to spend that budget threading a `State`. The seam is the public API; internals stay imperative.

### 2.3 `Reporter`'s new role

`Reporter` becomes the **cross-phase** diagnostic accumulator (typer, capability checker, codegen all push into it). The parser no longer constructs or consumes one. A typical driver wires it up:

```scala
val pr       = Parser.parse(src)
val reporter = new Reporter(src)
pr.diagnostics.foreach(reporter.add)   // new helper, equivalent to error/warning/info
val tpd      = Typer.check(pr.tree, reporter)
```

This eliminates the implicit "the parser knows about a Reporter that lives outside it" coupling. The `Reporter.add(d: Diagnostic): Unit` helper is added.

## 3. Error recovery

### 3.1 Synchronisation points (anchors)

When a production fails, the parser:

1. Emits a `Diagnostic` with code, span, message, suggestion.
2. Wraps the partial work in `Trees.Error`.
3. Skips tokens until it reaches an *anchor* appropriate to its current context, then resumes parsing the surrounding production.

Anchors, by context (closed sets — every Phase 2 production declares which it owns):

| Context | Anchor tokens (resumes at, does not consume) |
|---|---|
| Top-level item | `KwUse`, `KwFn`, `KwCap`, `KwData`, `KwEffect`, `KwLinear`, `KwType`, `KwMod`, `KwPub`, `UpperIdent` *(satisfies-decl head)*, `Eof` |
| Block body | `Newline` at the block's indent, `Dedent`, any top-level anchor |
| Argument list / tuple / list literal | `Comma`, matching `RParen` / `RBracket` / `RBrace` |
| Type expression | `Comma`, `RParen`, `Newline`, `Eq`, `Arrow`, `KwWith` |
| Pattern | `FatArrow`, `Comma`, `RParen` |
| Match / handle arms | `FatArrow` (recover the body), `Newline` at the arm's indent |
| `prop` / `forall` / `implies` body | `Newline` at the body's indent, `Dedent` |

Anchors are **ranked**: the parser bails to the highest-ranked anchor in scope, never deeper. This prevents a missing `)` from eating the rest of the file — the `Comma` and matching `RParen` inside the call beat the outer `KwFn`.

### 3.2 New AST nodes

```scala
// In ast/Trees.scala

/** A recoverable parse failure. `recovered` holds whatever subtrees the
  * parser managed to build before bailing out (often empty). The diagnostic
  * with the same span carries the human-readable explanation. */
final case class Error(
    recovered: List[Tree],
    span: Span
) extends Tree

/** A required production that was missing entirely (e.g. `fn` with no name).
  * Span is zero-length, located where the missing token was expected. */
final case class Missing(
    expected: String,           // grammar-level description, e.g. "function name"
    span: Span
) extends Tree
```

The current `Trees.Ident("<error>", _)` and `Trees.Ident("<unsupported>", _)` placeholders are removed; every site that produces them produces an `Error` or `Missing` instead. Existing match arms in tests and the (future) typer must add cases for both.

### 3.3 Recovery contract

Three invariants Phase 2.1 tests enforce:

1. **No-regression on the happy path.** Every input that parsed cleanly under Phase 2 M5 produces the *same* tree under Phase 2.1, with `diagnostics.isEmpty`.
2. **Bounded skip.** Token-skip per recovery attempt is bounded by the distance to the nearest anchor; the parser does not loop or restart from the beginning.
3. **Top-level resilience.** For every `examples/*.fixed` corrupted with one syntactic error per top-level decl, the resulting `CompilationUnit.items.length` matches the un-corrupted parse and `diagnostics.length` matches the number of injected errors.

## 4. Trivia retention

### 4.1 Side-table design

```scala
package fixed.parsing

/** Trivia attached to a token: what the scanner skipped immediately
  * before the token's start offset. Trailing trivia is owned by the
  * *next* token's leading trivia — there is no separate "trailing" slot.
  * (The rust-analyzer convention; keeps lookups O(1).) */
enum Trivia:
  case LineComment(span: Span, text: String)
  case BlankLines(span: Span, count: Int)

final class TriviaTable:
  def leadingFor(tokenStart: Int): List[Trivia]
  def all: Iterable[(Int, List[Trivia])]
```

We do **not** record inline whitespace (single spaces between tokens on a line); it is reconstructable from `source.slice(prevTokenEnd, currentTokenStart)` minus the recorded comments and blank lines.

### 4.2 Scanner change

`Scanner.skipTrivia` currently advances `pos` and discards. The new version emits each `LineComment` / `BlankLines` event into the table before advancing. The hot path gains one allocation per non-trivia token boundary that has trivia attached; for source files dominated by code (the common case) this is negligible.

We benchmark with `ScannerBench` to confirm the regression is < 5%; if larger, gate trivia capture behind a `ParseOptions.captureTrivia` flag (default `true` for the `Parser` API, `false` for the Scanner-only benchmark suite).

### 4.3 What we don't store yet

- `///` doc-comment classification — Phase 2.2.
- Trailing trivia separated from leading — Phase 2.2.
- Token-internal whitespace within multi-line string/char literals — not applicable in v0.4.5 (no multi-line literals).

## 5. AST changes (summary)

| Change | File | Reason |
|---|---|---|
| Add `Trees.Error`, `Trees.Missing` | `ast/Trees.scala` | Gap nodes for recovery (§3.2). |
| Remove `Trees.Ident("<error>", _)` and `Trees.Ident("<unsupported>", _)` placeholders | `parsing/Parsers.scala` | Replaced by `Error` / `Missing`. |
| Make `Tree` a non-sealed trait (drop `sealed`) | `ast/Trees.scala` | Forward-compatibility with Phase 2.2's CST view, which will introduce a new `Tree` implementor outside this file. Existing case classes still extend `Tree`; pattern matching in the typer is unaffected. |

The third item is the only design escape hatch we open in Phase 2.1 for Phase 2.2; everything else is additive.

## 6. Spec changes

### 6.1 `spec/syntax_grammar.ebnf` — new appendix

A normative implementation appendix defining recovery anchors (§3.1) and the trivia retention contract (§4). The grammar productions themselves do not change; the appendix exists so two conformant Fixed parsers cannot disagree on what an erroneous file means or whether trivia is observable.

### 6.2 `docs/plans/phase-2-parser-ast.md` §5.5

Change "Error-recovery synchronization … is deferred to Phase 3" → "lifted into Phase 2.1; see `phase-2.1-incremental-parser.md`".

### 6.3 `docs/plans/implementation-plan.md`

Add a new principle in the "Output design principles" list under "Agent-Friendly CLI and Compiler Output":

> **Incremental and partial-result-tolerant by default.** Every phase consumes possibly-incomplete input and emits as much output as it can. The parser produces an AST with explicit gap nodes when a production fails, instead of halting; the typer types as much of the AST as it can, leaving holes for unresolvable references; capability closure proceeds with whatever satisfaction declarations resolved. This is the precondition for LSP / IDE integration and for incremental compilation, and it makes the compiler usable mid-edit rather than only at quiescence.

## 7. Test strategy

### 7.1 Happy-path regression

`ParserSuite` and the `examples/*.fixed` parse tests are run against the new API. Assertions about `tree` shape carry over unchanged; assertions that read from `Reporter` are rewritten to read from `ParseResult.diagnostics`.

### 7.2 Recovery corpus

A new `compiler/src/test/resources/recovery/` directory mirrors `examples/`. Each entry pairs `<name>.fixed` (valid) with `<name>.broken.fixed` (one syntactic error injected per top-level decl). Test:

```
for every (good, broken) pair:
  parse(good).diagnostics.isEmpty
  parse(broken).diagnostics.length == numberOfInjectedErrors
  parse(broken).tree.items.length == parse(good).tree.items.length
```

Plus per-context tests:

- Missing `)` in a fn signature recovers at NEWLINE; the following `fn` parses cleanly.
- Garbage inside a match arm recovers at next `=>` or NEWLINE; the following arms parse cleanly.
- Unknown keyword at top level recovers at next decl-introducer.
- A comment between two consecutive `fn`s appears in `trivia.leadingFor(secondFnNameStart)`.

### 7.3 Trivia round-trip

A minimal pretty-printer that replays `tree + trivia` produces text byte-equivalent to the input modulo normalised inline whitespace. Inline whitespace is normalised because we don't capture it.

### 7.4 Property: corruption resilience

Property test (hand-rolled via munit, ~1000 iterations):

- Take a random `examples/*.fixed`.
- Insert a random extraneous token at a random position.
- Assert: parser produces ≥ 1 diagnostic, terminates, and `tree.items.length` is within ±1 of the unmodified parse.

## 8. Milestones

| # | Milestone | Verification |
|---|---|---|
| 2.1.M1 | `ParseResult` shipped; `Parser.parse(source)` returns it; old `Reporter`-based API kept as adapter. | All Phase 2 tests pass with no behaviour change. |
| 2.1.M2 | `Trees.Error` / `Trees.Missing` introduced; placeholder `Ident`s removed. | Recovery corpus parses with correct item counts. |
| 2.1.M3 | Synchronisation points wired across all productions per §3.1. | Property test (§7.4) passes 1000 random corruptions. |
| 2.1.M4 | `TriviaTable` populated; pretty-printer round-trips trivia. | §7.3 round-trip test passes for all `examples/*.fixed`; `ScannerBench` regression < 5%. |
| 2.1.M5 | Spec appendix added; phase-2 plan §5.5 updated; implementation-plan principle added. | Cross-references resolved; `git grep "halt parsing"` returns no current-doc hits. |

## 9. Risks

- **Recovery cascades.** A single missing `)` could fool recovery into eating the rest of the file. *Mitigation:* anchor ranking (§3.1) — the parser bails to the highest-ranked anchor in scope, never deeper.
- **Trivia table memory.** A pathological all-comments file allocates `O(commentCount)` extra. *Mitigation:* in practice bounded by file size; re-evaluate if LSP profile shows otherwise. The `captureTrivia = false` escape hatch exists for benchmarks.
- **AST consumers depending on the absence of `Error`/`Missing`.** None today — Phase 3 is unimplemented. We lock in the contract that every Phase 3 visitor must handle these two cases (typically by emitting an "unresolved due to parse error" diagnostic and skipping the subtree).
- **Sealed-trait removal blast radius.** Dropping `sealed` from `Tree` means non-exhaustive match warnings disappear at the call site. *Mitigation:* keep an explicit `def matchExhaustive[T](onCu: ..., onFn: ..., …): T` helper (or a sealed enum view) added for the typer, so Phase 3 can still rely on coverage checking.

## 10. Effort estimate

Roughly two weeks of focused work:

| Sub-milestone | Effort |
|---|---|
| 2.1.M1 (functional API) | 1–2 days |
| 2.1.M2 (gap nodes) | 1 day |
| 2.1.M3 (recovery anchors across all productions) | 4–5 days |
| 2.1.M4 (trivia + round-trip) | 2 days |
| 2.1.M5 (spec edits) | 1 day |

Total: **~2 weeks**. Phase 2.2 (CST + on-edit incremental reparse) is a separate 3–5 weeks and only worth doing once a concrete LSP consumer is driving requirements.

## 11. Cross-references

| Document | Relationship |
|---|---|
| `docs/plans/phase-2-parser-ast.md` | Phase 2 — produced the AST; §5.5 is amended by this plan. |
| `docs/plans/implementation-plan.md` | Adds the incremental-by-default principle. |
| `spec/syntax_grammar.ebnf` | Gains the recovery-anchor and trivia appendix. |
| Future: `docs/plans/phase-2.2-incremental-cst.md` | Layer 2 — green/red CST and on-edit incremental reparse. |
