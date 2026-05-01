# Fixed Compiler — Phase 2: Parser + AST (Implementation Plan)

**Status:** Draft v0.1 — pre-implementation planning
**Scope:** Scala 3 bootstrap of the front-end. Lex Fixed source → tokens → AST.
**Prerequisite:** Spec frozen at v0.4.5 (commit `c7fbe8c`). Phase 1 complete.
**Reference:** `docs/references/scala3-compiler-reference.md` for dotc patterns.

## 1. Scope and milestones

### 1.1 What's in Phase 2
- Project skeleton: `build.sbt`, package layout, `Main`/`Driver`/`Compiler`/`Run`.
- **Lexer (Scanner)**: Source bytes → token stream, with synthetic `INDENT`/`DEDENT` and line-continuation collapse.
- **Parser**: Recursive-descent over the token stream, producing `untpd.Tree` instances.
- **AST**: Single `Tree[T <: Untyped]` hierarchy split into `untpd` (parser output) and `tpd` (post-typer; later phases populate).
- **ParserPhase**: Wraps the parser as a `Phase`; per-unit `runOn` populates `unit.untpdTree`.
- **Phase plan**: `Compiler.scala` exposing `phases: List[List[Phase]]` initially containing only `Parser` and a stub `TyperPhase`.
- **Tests**: round-trip lex tests + parse-success tests for `examples/01_basics.fixed` through `11_properties.fixed`.

### 1.2 What's NOT in Phase 2 (deferred to Phase 3+)
- Type checking (Typer, Namer).
- Capability closure / `satisfies` resolution.
- Quantity (QTT) inference.
- Representation selection.
- Effect inference, exhaustiveness, prop verification.
- Code generation.

### 1.3 Milestones (ordered)

| # | Milestone | Verification |
|---|-----------|--------------|
| M1 | Project compiles, `sbt test` passes empty test suite. | `sbt compile && sbt test` returns 0. |
| M2 | Scanner produces tokens for every `examples/*.fixed`. | A test dumps tokens for each example; no `ERROR` tokens; `INDENT`/`DEDENT` balanced. |
| M3 | Parser succeeds on `examples/01_basics.fixed` → produces an `untpd.Tree`. | Smallest example parses; `Tree` shape matches expected for `fn main`, `fn greet`, `fn fib_iter`, `fn print_fibs`. |
| M4 | Parser succeeds on examples 02–06. | Adds: data declarations, satisfaction decls, `match`/`fold`, do-notation, generic type params. |
| M5 | Parser succeeds on examples 07–11. | Adds: paramorphism (`para`), effects + handlers, refinement caps, `prop` declarations, parameterized type aliases. |
| M6 | Parser produces structurally identical ASTs across whitespace-equivalent inputs. | Property test: re-indented inputs that respect grammar produce isomorphic trees. |
| M7 | Phase plan executes: `Compiler` wires `Parser` → stub `Typer` → `Run`. | `sbt run -- examples/01_basics.fixed` exits 0; stub typer leaves `tpd.Tree` empty without error. |

The Phase-2 success criterion is **M5**: every example in the corpus parses to a well-formed AST.

## 2. Project structure

```
build.sbt
project/
  build.properties
  plugins.sbt
compiler/
  src/main/scala/fixed/
    Main.scala              — entry point
    Driver.scala            — argv parsing, settings
    Compiler.scala          — phase plan: List[List[Phase]]
    Run.scala               — one execution of the phase plan
    CompilationUnit.scala   — per-unit tree + diagnostics
    ast/
      Trees.scala           — single Tree[T <: Untyped] hierarchy
      untpd.scala           — type alias untpd.Tree = Tree[Untyped]
      tpd.scala             — type alias tpd.Tree = Tree[Type]
      Desugar.scala         — (Phase 3+) match → fold lowering, etc.
    core/
      Names.scala           — interned identifiers
      Symbols.scala         — (Phase 3+) symbol table
      Phases.scala          — Phase trait + base classes
      Contexts.scala        — Context object threading config + symbol table
    parsing/
      Tokens.scala          — Token enum + keyword table
      Scanners.scala        — Lexer
      Parsers.scala         — Recursive-descent parser
      ParserPhase.scala     — wraps Parsers as a Phase
      OffsetPositioned.scala — source positions + spans
    typer/
      TyperPhase.scala      — STUB in Phase 2 (Phase 3 deliverable)
    util/
      SourceFile.scala      — loaded source + line-offset table
      Reporter.scala        — diagnostic accumulator
  src/test/scala/fixed/
    parsing/
      ScannerSuite.scala
      ParserSuite.scala
      ExampleParseRoundTripSuite.scala  — parses every examples/*.fixed
    util/
      TestSourceFile.scala  — helpers
examples/                    — already exists; the parser test corpus
spec/                        — already exists; reference for the implementation
```

### 2.1 Build configuration (target)

```scala
// build.sbt
ThisBuild / scalaVersion := "3.5.0"   // pin minor version
ThisBuild / scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wvalue-discard",
  "-source:3.5"
)

lazy val compiler = project.in(file("compiler"))
  .settings(
    name := "fixed-compiler",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test
    )
  )
```

Choice of MUnit (lightweight, Scala 3-native) over ScalaTest. Single project (`compiler`) for v0; split into `compiler-api` / `compiler-bootstrap` later if cross-build is needed.

## 3. Token inventory (from grammar v0.4.5)

### 3.1 Reserved words (36 keywords)

```
as          cap         data        do          effect      else
extends     false       fn          forall      handle      if
implies     impossible  in          is          let         linear
match       mod         of          prop        pub         resume
return      Self        satisfies   suchThat    true        type
unreachable use         with        self        Part
```

Note: `self` (lowercase) and `Part` (capital) are contextual — `self` is the receiver-value binder inside instance methods (Rule 5.4.c), `Part` is the cap's element-type pseudo-parameter (Rule 5.3). Both are reserved at the lexer level to avoid identifier clashes; the parser may still emit them as plain identifiers in non-binding positions.

(Originally the spec listed only 34 keywords; `self` is added in v0.4.5 per Rule 5.4.c. `Part` was always reserved per Rule 5.3 but is now explicit in the keyword set.)

### 3.2 Punctuation and operators

| Token | Lexeme | Notes |
|---|---|---|
| `LPAREN` | `(` | |
| `RPAREN` | `)` | |
| `LBRACE` | `{` | Reserved (no current production uses it; future) |
| `RBRACE` | `}` | Reserved |
| `LBRACKET` | `[` | List literal |
| `RBRACKET` | `]` | List literal |
| `COMMA` | `,` | |
| `COLON` | `:` | Block opener AND type annotation |
| `SEMICOLON` | `;` | Reserved (Fixed has no statements; future or error) |
| `DOT` | `.` | Method call, type qualifier; line-continuation per v0.3.1 |
| `EQUALS` | `=` | Definition introducer |
| `ARROW` | `->` | Function-type/lambda body |
| `FAT_ARROW` | `=>` | Match/handler arm body |
| `PIPE` | `\|` | Or-patterns (M5.5) |
| `PIPE_FORWARD` | `\|>` | Pipe operator |
| `PLUS` | `+` | Capability composition AND numeric add |
| `MINUS` | `-` | |
| `STAR` | `*` | |
| `SLASH` | `/` | |
| `PERCENT` | `%` | |
| `LT`, `LE`, `GT`, `GE`, `EQ_EQ`, `NEQ` | `<`, `<=`, `>`, `>=`, `==`, `!=` | Comparison operators |
| `AND_AND`, `OR_OR` | `&&`, `\|\|` | Boolean |
| `BANG` | `!` | Never type AND prefix-not |
| `UNDERSCORE` | `_` | Wildcard pattern |
| `AT` | `@` | Reserved (potential future use for prop annotations) |

### 3.3 Literal tokens

| Token | Pattern (informal) |
|---|---|
| `INT_LITERAL` | `[0-9]+` (with optional type suffix `i32`, `u64`, etc. — **deferred** to v0.5; for v0.4.5 the typer handles polymorphism) |
| `FLOAT_LITERAL` | `[0-9]+\.[0-9]+` (decimal float; scientific notation deferred) |
| `STRING_LITERAL` | `"..."` with `\n`, `\t`, `\\`, `\"`, `\0` escapes; no interpolation in v0.4.5 |
| `CHAR_LITERAL` | `'.'` single character or escape |
| `TRUE`, `FALSE` | Keyword-tokens for boolean literals |
| `UNIT_LITERAL` | `()` — already a punct sequence; produced by parser, not lexer |

### 3.4 Identifier tokens

| Token | Regex |
|---|---|
| `LOWER_IDENT` | `[a-z_][a-zA-Z_0-9]*` (after keyword check) |
| `UPPER_IDENT` | `[A-Z][a-zA-Z_0-9]*` (after keyword check — `Self` and `Part` caught here) |

Identifier classifications matter for the parser:
- Variant qualifier: `UPPER_IDENT . UPPER_IDENT` (e.g., `Json.Null`)
- Cap method on type: `UPPER_IDENT . LOWER_IDENT` (e.g., `Optional.some`)
- Method call on value: `LOWER_IDENT . LOWER_IDENT` (e.g., `list.fold`)

### 3.5 Synthetic tokens

| Token | When emitted |
|---|---|
| `INDENT` | First non-whitespace token on a line whose indentation strictly exceeds the enclosing block's indentation. |
| `DEDENT` | Closing of an indented block (one DEDENT per level closed). |
| `NEWLINE` | End-of-line that is not a line-continuation (see §5.2). |
| `EOF` | End of input. |

### 3.6 Comments

Single-line `//` comments are skipped by the lexer (not emitted as tokens). No multi-line comments in v0.4.5.

## 4. AST inventory (from grammar productions)

The AST is a single `Tree[T <: Untyped]` hierarchy, mirroring `dotty/tools/dotc/ast/Trees.scala`. `untpd.Tree = Tree[Untyped]` (Phase 2 output). `tpd.Tree = Tree[Type]` (Phase 3+ output, after typing).

Each node carries:
- A source span (start offset + end offset).
- An optional type slot (`null` in untpd; populated in tpd).

### 4.1 Top-level declarations

| AST node | Grammar production | Notes |
|---|---|---|
| `CompilationUnit` | `CompilationUnit` | Holds `List[TopItem]` plus the `SourceFile`. |
| `UseDecl` | `UseDecl` | `use std.io.Console`, `use Type satisfies Cap`. |
| `CapDecl` | `CapDecl` | Includes `extends`, `of (...)` params, body of methods/props. |
| `EffectDecl` | `EffectDecl` | `linear`-modifier flag + ops list. |
| `DataDecl` | `DataDecl` | Either multi-variant body or single-ctor sugar form. |
| `TypeAlias` | `TypeAlias` | Optional value-params, RHS bound chain or type expr. |
| `FnDecl` | `FnDecl` | Signature + body. Body may be `cap of T` returning. |
| `SatisfiesDecl` | `SatisfiesDecl` | Maps data ctors to cap ctor requirements via `as` / `impossible as`. |
| `ModDecl` | `ModDecl` | Module declaration. |

### 4.2 Cap members

| AST node | Production | Notes |
|---|---|---|
| `InstanceMethod` | `InstanceMethodDecl` | Optional body (= default impl). |
| `StaticMethod` | `StaticMethodDecl` | `Self.fn ...`. Optional body. |
| `PropDecl` | `PropDecl` | Body is a `PropExpr`. |

### 4.3 Type expressions

| AST node | Production | Notes |
|---|---|---|
| `ArrowType` | `ArrowType` | LHS + RHS + optional `with` clause. |
| `TypeAtom` | `TypeAtom` | Discriminated union of: `Never`, `SelfType`, `IsBound`, `NamedAlias`, `TypeRef`, `CapType`, `ParenTypeApp`. |
| `IsBound` | `IsBound` | `is C + D + ...`. |
| `NamedAlias` | `NamedAlias` | `N is C + ...`. |
| `CapBound` | `CapBound` | One of `CapRef`, `RefinementCall`, `PrimitiveType`. |
| `CapRef` | `CapRef` | `UPPER_IDENT (of OfArg)?`. |
| `RefinementCall` | `RefinementCall` | `LOWER_IDENT(args)`. |
| `OfArg` | `OfArg` | `OfValueParam` \| `LiteralExpr` \| `TypeExpr`. |
| `OfValueParam` | `OfValueParam` | Decl-site value parameter `name: type [= default]`. |
| `CapType` | `CapType` (new in v0.4.5) | `cap of T` or `cap extends C`. |
| `ParenTypeApp` | `ParenTypeApp` (new in v0.4.5) | `(TypeExpr) of OfArg`. |
| `SelfType` | `Self SelfOf?` | `Self`, `Self of B`. |
| `PrimitiveType` | `PrimitiveType` | `i64`, `String`, `bool`, `()`, etc. |
| `UnitType` | `()` | Sugar for the unit primitive. |
| `NeverType` | `!` | Per type_system §5.6. |

### 4.4 Expressions

| AST node | Production | Notes |
|---|---|---|
| `LetExpr` | `LetExpr` | `let p = e` (introduces a binding into the rest of the block). |
| `IfExpr` | `IfExpr` | `if cond: thenBranch else: elseBranch` — both branches required (no statement form). |
| `MatchExpr` | `MatchExpr` | Scrutinee + arms (each arm is `Pattern => body`, with optional guard or or-pattern). |
| `HandleExpr` | `HandleExpr` | Subject + handler arms. |
| `DoExpr` | `DoExpr` | Sequencing in monadic / effect-do contexts. |
| `BlockExpr` | `BlockTail` | Indented sequence of expressions, returns last. |
| `LambdaExpr` | `LambdaExpr` | `(p1, ...) -> body` — body may be single-expr or indented block. |
| `MethodCall` | call syntax | `recv.name(args)` — disambiguated from cap-static via the recv type. |
| `StaticCall` | call syntax | `C.name(args)` where `C` is uppercase. |
| `FnCall` | call syntax | `name(args)` — top-level fn call. |
| `Literal` | `LiteralExpr` | Integer, float, string, char, bool, unit. |
| `Ident` | `LOWER_IDENT` / `UPPER_IDENT` in expr position | Variable reference, type reference, etc. |
| `BinOp` | `OrExpr`/`AndExpr`/`CmpExpr`/`AddExpr`/`MulExpr` | Operator nodes. |
| `UnaryOp` | `UnaryExpr` | `!`, `-` prefix. |
| `Pipe` | `PipeExpr` | `x \|> f` ≡ `f(x)`. |
| `FieldAccess` | `.LOWER_IDENT` | Property access (no method call). |
| `TupleExpr` | `TupleExpr` | `(a, b, ...)` with optional trailing comma. |
| `ListExpr` | `ListLiteralExpr` | `[a, b, ...]`. |
| `StructLiteral` | `StructLiteralExpr` | `T { field: value, ... }` — single-variant data construction. |
| `Forall` | `ForallExpr` | Inside prop bodies only. |
| `Implies` | `ImpliesExpr` | Inside prop bodies only. |
| `SuchThat` | (clause on Forall) | Filter expression. |
| `Resume` | `resume(expr)` | Inside handler arms only. |
| `Unreachable` | `unreachable` | Per type_system §5.6. |

### 4.5 Patterns

| AST node | Production | Notes |
|---|---|---|
| `WildcardPat` | `_` | |
| `BinderPat` | `LOWER_IDENT` | |
| `LiteralPat` | literal in pattern position | |
| `DataVariantPat` | `T.Variant(p1, ...)` | Per Rule M3.4. Unit variants must NOT have parens. |
| `TuplePat` | `(p1, p2, ...)` | Trailing comma required for 1-tuples. |
| `StructDestructurePat` | `T { field: p, .. }` | Per Rule M3.6; `..` allowed at end. |
| `OrPat` | `p1 \| p2` | Per M5.5; binder agreement enforced by typer. |
| `GuardedPat` | `p if cond` | Per M5.6. |

### 4.6 Effect-related

| AST node | Production | Notes |
|---|---|---|
| `EffectMember` | `EffectMember` | Same shape as instance method per E3.1. |
| `WithClause` | `with R` | Effect row attached to fn signature/lambda. |
| `EffectRow` | `EffectRow` | `+`-composed effects. |
| `EffectBound` | `EffectBound` | `E of OfArg?`. |
| `HandlerArm` | `HandlerArm` | Op-name dispatch + `return(p) =>` arm. |

### 4.7 Untyped vs typed phasing

In Phase 2, every node is `untpd.Tree`. Type slots are `null`. Symbol resolution (Phase 3) populates `tpd.Tree`. The same node *classes* are used; `Tree[T]` is parameterized by what's stored in the type slot.

Single-hierarchy approach mirrors dotc — see `docs/references/scala3-compiler-reference.md` §3.5. Avoids the dual-hierarchy duplication seen in some compilers.

## 5. Scanner / Parser strategy

### 5.1 Recursive descent

Pure hand-written recursive descent. No parser combinator library. Rationale: dotc is hand-written; the grammar is small enough; debugging is easier; performance is predictable; future extensions (e.g., better error recovery) are easier.

The parser maintains:
- A scanner cursor (current token + lookahead buffer).
- An indentation stack (for INDENT/DEDENT synthesis).
- A `Reporter` for diagnostics.
- A `SourceFile` for span construction.

### 5.2 Indentation and line continuation

Fixed is **indentation-sensitive**, like Python and Scala 3:
- A `:` at end of line followed by an indented block opens a `BlockTail` (or cap/data/effect/satisfies body).
- The indented block ends when indentation returns to the enclosing level (DEDENT).
- Indentation must be consistent within a block (mix of tabs and spaces is an error per §3 of grammar comments).

**Line continuation** is normative in `syntax_grammar.ebnf` lines 186–200 (no further spec edit needed). The two rules are:

- **Trailing continuation** — if the line ends with one of these tokens, the next physical line continues the same logical line: `+`, `-`, `*`, `/`, `%`, `&&`, `||`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `|>`, `->`, `=>`, `=`, `<-`, `.`, `is`, `extends`, `implies`. The dual-mode tokens `=`/`->`/`=>` are *also* block-introducers — see §5.3.
- **Leading continuation** — if the next physical line begins with `with`, `extends`, or `->`, it continues the previous logical line.

Inside `(...)`/`[...]`/`{...}`, indentation is suppressed by default; off-side resumes inside arm/lambda/fn/block bodies opened with `=>`/`->`/`=`/`:` respectively (grammar lines 173–181).

The scanner emits NEWLINE only when the previous line did NOT end with a trailing-continuation token, the next line does NOT start with a leading-continuation token, and no `(`/`[`/`{` is open without a matching close.

### 5.3 INDENT/DEDENT synthesis

When the scanner encounters a NEWLINE (real, not suppressed):
- Compute the next line's indentation (count of leading spaces; tabs forbidden mid-block).
- Compare to the top of the indentation stack.
- If strictly greater AND the previous line ended in `:` (block opener) or specific continuation contexts (e.g., body of a fn-def `=` followed by indented block), push the new indent onto the stack and emit `INDENT`.
- If strictly less, pop levels until the new indent matches, emitting one `DEDENT` per pop.
- If equal, emit a NEWLINE between the two lines' tokens.

A trailing partial dedent (indent doesn't match any stack level) is a lex error.

### 5.4 Lookahead

The parser uses **single-token lookahead** for most productions. Exceptions where 2-token lookahead is needed:
- Disambiguating `LOWER_IDENT (` as either a `RefinementCall` (in `is`-bound position) or a `FnCall` (in expression position). Context-sensitive: the parser knows which it expects.
- Disambiguating `(`-prefix as either `ArrowLhs` (tuple-for-arrow) or `ParenTypeApp` (`(TypeExpr) of`) or grouped expression. Lookahead for the closing `)` followed by `of` distinguishes `ParenTypeApp`.
- Disambiguating `UPPER_IDENT (` as either a single-arg variant pattern, a tuple-arity-1 variant pattern, or a static-method call. Production-context disambiguates.

For these cases the parser uses bounded peek (≤ 2 tokens). No backtracking parser combinators.

### 5.5 Error recovery

Phase 2 minimum: emit one diagnostic with file + line + column + suggestion, then halt parsing. Per `implementation-plan.md` §"Agent-Friendly CLI and Compiler Output": every error has a code (`E001`, `E002`, …), a span, and a copy-pasteable suggestion when applicable.

Error-recovery synchronization (parse-as-much-as-possible-after-error) is **deferred** to Phase 3 — it requires a richer error-tree representation than Phase 2 needs.

## 6. Indentation: nuances and edge cases

### 6.1 The do-block in handle

`handle (do: ... ): arms` — the `(do: ... )` form is a parenthesized `DoExpr`. Inside the parens, the `do:` opens an indented block, and the closing `)` matches the opening `(`. INDENT/DEDENT inside parens is handled normally (the indent stack tracks parens-open state separately).

Example:
```
handle (do:
    let x = compute()
    use_x(x)
):
    Console.print_line(msg) => ...
```

The parser sees: `LPAREN do COLON INDENT let ... DEDENT RPAREN COLON INDENT Console.print_line ... DEDENT`.

### 6.2 Multi-line lambdas

`(x) ->` followed by an indented body:
```
list.map((x) ->
    let y = x * 2
    y + 1
)
```

The `->` at end of line is a continuation token; the next line's indent opens a block under `->`. The block ends when indent returns; the closing `)` of `map` follows.

### 6.3 Single-line `=` then block vs same-line

```
fn f(x: u64) -> u64 = x + 1                     // single-line: body is the expression x + 1
fn g(x: u64) -> u64 =                            // multi-line: `=` ends with continuation, body is indented
    let y = x * 2
    y + 1
```

The lexer/parser must accept both: `=` followed by same-line expression OR `=` followed by NEWLINE + INDENT + block + DEDENT.

### 6.4 Trailing commas

Trailing commas are admitted in: `OfArgList`, `ArgList`, `FnParamList`, `FieldDeclList`, `PatternList`, `TupleExpr`, `ListLiteralExpr`. The parser tolerates `,` immediately before a closing bracket.

### 6.5 Comments between tokens

`//` to end of line. Inside multi-line forms, comments are silently dropped — no AST representation in v0.4.5. (Future: doc-comment retention for `///`-style is deferred.)

## 7. Critical disambiguations

### 7.1 `cap of T` in type position

After v0.4.5 grammar adds `CapType` to `TypeAtom`, the lexer emits `cap` as a keyword. The parser admits `cap` at the start of:
- `FnReturnType` (legacy form): `fn f(...) -> cap of T = ...`
- `TypeAtom` (anywhere a TypeExpr is expected): `(N, N) -> cap of N` parameter types.

Both are equivalent per Rule 6.3.0.

### 7.2 `(M is Monad) of (List of B)` (ParenTypeApp)

When the parser sees `LPAREN` in TypeExpr position, it parses an expression-list-or-grouped-type. After the matching `RPAREN`:
- If next token is `of`, it's `ParenTypeApp` — apply the parsed type to the `of` argument.
- If next token is `->`, it's the LHS of an `ArrowType` (tuple-of-types arrow input).
- Otherwise it's a parenthesized grouping (single TypeExpr).

Two-token lookahead suffices.

### 7.3 `LOWER_IDENT (` in is-bound vs expr position

In an `is`-bound chain (`is C + D + ...`), each `CapBound` is one of:
- `CapRef`: `UPPER_IDENT (of OfArg)?`
- `RefinementCall`: `LOWER_IDENT (args)`
- `PrimitiveType`: `LOWER_IDENT` (alone, no parens)

The disambiguator: in is-bound position, a bare `LOWER_IDENT` is `PrimitiveType`; `LOWER_IDENT(` is `RefinementCall`. In expression position, `LOWER_IDENT(args)` is `FnCall`. The parsing context (is-bound vs expr) drives the choice; no lookahead needed.

### 7.4 Variant pattern parens vs unit variant

Per Rule M3.4.b (updated in v0.4.5): unit variants are matched by *bare name only* — `List.Nil`, never `List.Nil()`. The parser:
- `T.Variant ( PatternList )` — variant pattern with field-arity matching.
- `T.Variant` (bare) — unit variant pattern OR a binder-shadowing-the-variant (resolved by Rule M3.4.a — variant takes precedence if scrutinee's static type has it).

The parser produces `DataVariantPat(qualifier, name, fields=Nil)` for the bare form. A trailing `()` after a unit-variant declaration is a parse error: `error[E???]: unit variant 'X.Nil' has no fields; remove the trailing parens`.

### 7.5 `Self of B` vs `(M is C) of B`

`Self` is a keyword — the parser dispatches `Self of B` as a `SelfType` node. `(M is C) of B` matches `ParenTypeApp` per §7.2 above.

### 7.6 Operator precedence

Normative in `syntax_grammar.ebnf` lines 709–720 (no further spec edit needed). Productions `PipeExpr → OrExpr → AndExpr → CmpExpr → AddExpr → MulExpr → AppExpr → AtomExpr` encode precedence from lowest to highest:

| Production | Operators | Associativity |
|---|---|---|
| `PipeExpr` | `\|>` | Left |
| `OrExpr` | `\|\|` | Left |
| `AndExpr` | `&&` | Left |
| `CmpExpr` | `==`, `!=`, `<`, `<=`, `>`, `>=` | Non-associative (`?` in grammar — at most one comparison per expr) |
| `AddExpr` | `+`, `-` | Left |
| `MulExpr` | `*`, `/`, `%` | Left |
| `AppExpr` | `f(args)`, `e.m(args)`, `e.field` | (call/access) |
| `AtomExpr` | literals, idents, `(...)`, lambdas, `if`, `match`, `handle`, `do` | (atom) |

**No prefix unary operators in v0.4.5.** Negation is expressed via `0 - x` or `n.neg()` per `cap Numeric`; logical not via `!cond` is **not in the grammar** and the examples don't use it. If unary needs are real, that's a small grammar extension for a future revision.

Capability composition `+` (`Folding + Filtering`) and capability extension `+` (`extends Functor + Monad`) reuse the `+` lexeme but appear only in is-bound / extends contexts (productions `CapBoundChain`, `CapBound ( "+" CapBound )*`). The parser disambiguates by syntactic position — is-bound `+` cannot reach `AddExpr`'s production.

### 7.7 `is` as identifier vs keyword

`is` is reserved. In `N is Numeric`, `is` opens a `NamedAlias`. Outside named-alias / is-bound / satisfies positions, `is` is reserved (parse error if used as an identifier).

## 8. Test strategy

### 8.1 Scanner tests

Per-keyword: scanning the keyword in isolation produces the right token kind. Per-punctuation: same. Per-literal: integer, float, string with escapes, char, bool. **Indentation tests**: a 3-line indented block produces INDENT, line tokens, NEWLINE, line tokens, DEDENT. A line-continuation case (line ending in `->`) produces no NEWLINE between physical lines.

Each example file in `examples/` is dumped to a token stream — golden-tested against a `.tokens` snapshot file under `compiler/src/test/resources/golden/`.

### 8.2 Parser tests

For each example file: parse → produce AST → assert top-level shape matches expected. Initial assertions:
- Top-level item kinds (count of `FnDecl`, `CapDecl`, `DataDecl`, etc.).
- For specific examples: assert specific structural patterns (e.g., example 07 contains a `fn optimize` with a `MethodCall` whose method is `para` and 4 lambda arguments).

A "round-trip" property test: reparse the printed AST → identical AST. (Pretty-printer is required for this; ship a minimal one that handles every node kind. Indentation choices should canonicalize.)

### 8.3 Negative tests

A `negative/` test corpus with deliberately invalid inputs:
- `let Self = 5` (cannot use `Self` as a binder).
- `fn f() -> u64 = let x = 5` (let without trailing expression — block must end in expr).
- `match x:` with non-data-typed scrutinee — but this is a typer error, not a parser error; parser accepts.
- Unit variant with empty parens — `error[E???]`.
- Invalid `extends` in effect alias — parser rejects per Rule E4.5.e.
- Mid-paragraph `prop` in a non-cap-of-returning fn body — **typer error, not parser**; parser accepts.

### 8.4 Coverage target

Minimum: every grammar production is exercised by at least one example or test. The `examples/` corpus already covers most productions; missing cases (e.g., `linear effect`, multi-arg `Self of (B1, B2)`) get dedicated tests under `compiler/src/test/scala/fixed/parsing/grammar-coverage/`.

## 9. Risks and dependencies

### 9.1 Scanner risks

- **Indentation rules under nested parens**: when a line continuation crosses multiple paren levels, the indent stack must track paren nesting separately from block indent. Plan: parens-stack and block-indent-stack as two independent stacks; INDENT/DEDENT only emitted at the block-indent level, ignored inside parens.
- **Tab-vs-space confusion**: forbid tab characters mid-line in indentation contexts. Spec §3 of grammar header expects this; lexer enforces.
- **String-literal escapes**: `\n`, `\t`, `\\`, `\"`, `\0`. **No interpolation** (`"hello $name"` style) in v0.4.5 — defer to v0.5.

### 9.2 Parser risks

- **`fn f(x) = body` body delimitation**: `=` opens a body; the body is either same-line (single Expr) or NEWLINE-then-INDENT-block. The lexer detects which by peeking at the next non-space token. Track explicitly to avoid premature NEWLINE emission.
- **Nested cap-generators in is-bound**: `is between(0, 100) + multiple_of(2)` — both elements are RefinementCall. The `+` here is is-bound composition. Parser tracks "is-bound mode" and treats `+` accordingly.
- **`Self of B'` substitution at parse time**: parser does not perform substitution; it produces a `SelfType` node with the of-arg unsubstituted. Substitution happens in the typer.

### 9.3 Spec dependencies

The parser depends on these spec sections being settled:
- `spec/syntax_grammar.ebnf` v0.4.5 (frozen — commit `c7fbe8c`).
- `spec/type_system.md` Rule 5.4.a/b (right-bias substitution, frozen).
- `spec/pattern_matching.md` Rule M3.4.b (unit-variant no-parens, frozen).

If any of these change during Phase 2 implementation, the parser must be updated.

### 9.4 Cross-Phase dependencies

The parser produces *raw* untyped trees. It does not:
- Resolve identifiers (Phase 3 Namer).
- Validate `match` exhaustiveness (Phase 3 Typer).
- Reject `prop` in non-cap-returning fn bodies (Phase 3 Typer per §3.3 deferral).
- Compute capabilities, quantities, or representations.

Conversely, the typer *requires* the parser to:
- Tag every node with its grammar production (the AST node class).
- Tag every node with a span.
- Distinguish `untpd` from `tpd` (the type slot is `null` post-parse).

## 10. Phase 2 deliverable checklist

Implementation proceeds bottom-up. Roughly 4 sub-milestones inside Phase 2:

### 10.1 Scanner (M1–M2)
- [ ] `Tokens.scala` — Token enum + keyword table.
- [ ] `Scanners.scala` — Lexer with INDENT/DEDENT + line-continuation collapse.
- [ ] `SourceFile.scala`, `OffsetPositioned.scala` — span and source utilities.
- [ ] `Reporter.scala` — diagnostic accumulator.
- [ ] `ScannerSuite.scala` — unit tests + golden tests for each example.

### 10.2 AST (M3)
- [ ] `Trees.scala` — single `Tree[T <: Untyped]` hierarchy with all node classes from §4.
- [ ] `untpd.scala`, `tpd.scala` — type aliases.
- [ ] Pretty-printer (minimal — for round-trip tests).

### 10.3 Parser (M3–M5)
- [ ] `Parsers.scala` — recursive-descent for every grammar production.
- [ ] `ParserPhase.scala` — wraps parser as a `Phase`, populates `unit.untpdTree`.
- [ ] `ParserSuite.scala` — per-grammar-production unit tests.
- [ ] `ExampleParseRoundTripSuite.scala` — every `examples/*.fixed` parses without error.

### 10.4 Phase wiring (M6–M7)
- [ ] `Phases.scala` — `Phase` trait, base class.
- [ ] `Compiler.scala` — `phases: List[List[Phase]]` with `Parser` and stub `Typer`.
- [ ] `Driver.scala` — argv parsing, settings.
- [ ] `Main.scala` — entry point.
- [ ] `Run.scala` — single execution of the phase plan.
- [ ] `CompilationUnit.scala` — per-unit state.

## 11. Open issues to defer to Phase 3+

These are intentionally NOT resolved in Phase 2:

- **Comment retention for documentation** — defer; v0.4.5 silently drops comments.
- **Better error recovery** — Phase 2 halts on first error; richer recovery (synchronization at top-level decl boundaries) is a Phase 3 enhancement.
- **String interpolation** — explicitly out of scope for v0.4.5; deferred.
- **Numeric literal type suffixes** (`5i32`, `1u64`) — deferred.
- **Doc-comment AST representation** — deferred.

## 12. Time and effort estimate

Rough order-of-magnitude (single implementer, focused work):

| Sub-milestone | Estimated effort |
|---|---|
| 10.1 Scanner | 1–2 weeks |
| 10.2 AST (declarations + tests) | 2–3 days |
| 10.3 Parser | 2–3 weeks |
| 10.4 Phase wiring | 2–3 days |

Total: **3–5 weeks of focused work** for a compiler that parses the example corpus to AST.

Risks that could extend timeline:
- Indentation edge cases (especially under nested parens + line continuation).
- Disambiguation contexts that require more than 2 tokens of lookahead (none anticipated; if discovered, the parser may need a rewrite of the affected sections).
- Operator precedence details (the table in §7.6 is correct as I read it, but the spec doesn't explicitly state precedence; the implementation may need to commit a normative table).

## 13. Cross-references

| Document | Relationship |
|---|---|
| `spec/syntax_grammar.ebnf` v0.4.5 | The grammar this parser implements. |
| `spec/type_system.md` | Disambiguation context for is-bound vs expression, `Self of B`, etc. |
| `spec/pattern_matching.md` | Pattern syntax and Rule M3.4.b (unit-variant no-parens). |
| `docs/references/scala3-compiler-reference.md` | dotc patterns: phase architecture (§2), parser organization (§3), single-tree-hierarchy (§3.5). |
| `docs/plans/implementation-plan.md` | Master plan; Phase 2 entries (this doc expands those). |
| `examples/01..11_*.fixed` | The parser test corpus. |

## 14. Next steps after Phase 2

Phase 3 (Type Checker) consumes the `untpd.Tree` produced here and emits `tpd.Tree`. Specifically Phase 3 needs:
- A complete AST (every grammar production has an AST node — this doc).
- Spans on every node (for diagnostics — this doc).
- An untyped/typed split (Tree[Untyped] vs Tree[Type] — this doc).
- A `ParserPhase` integrated into the `Compiler` plan (this doc).

Once Phase 2 is M5-complete, Phase 3 work can begin in parallel with Phase 2 polish (better error messages, performance work).
