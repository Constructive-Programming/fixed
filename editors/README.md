# Fixed editor and highlighter support

Public install page:
[fixed.constructive.dev/editors.html](https://fixed.constructive.dev/editors.html).

Six artifacts, each scoped to one ecosystem. They share the lexical surface of
`spec/syntax_grammar.ebnf` (v0.4.16): `//` comments, `"…"` strings, `'…'` chars,
decimal integers/floats with `_` separators, 35 reserved words, and the
contextual words `assume` and `strict`.

| Artifact | Ecosystem | Entry point |
|---|---|---|
| `vscode/` | VS Code, TextMate, Sublime, Nova, Shiki | `syntaxes/fixed.tmLanguage.json` (`source.fixed`) |
| `zed/` | Zed | `extension.toml`, `languages/fixed/*.scm` |
| `tree-sitter-fixed/` | Neovim, Helix, Zed, Emacs 29+ | `grammar.js`, `queries/*.scm` |
| `pygments/` | Sphinx, MkDocs, Jupyter, `pygmentize` | `fixed_pygments.lexer:FixedLexer` |
| `highlightjs/` | highlight.js web pages | `fixed.js` |
| `../project/FixedSyntax.scala` | fixed.constructive.dev (Laika) | `SyntaxHighlighting.withSyntax(FixedSyntax)` |

## Find and install

| Tool | Artifact | Install / use |
|---|---|---|
| VS Code | `vscode/` | `cd editors/vscode && npx @vscode/vsce package`; install the `.vsix`. Marketplace/Open VSX once published. |
| Zed | `zed/` | `zed: install dev extension` → `editors/zed`; registry once published. |
| Neovim | `tree-sitter-fixed/` | `:TSInstall fixed` after the nvim-treesitter parser PR; use the local parser config until then. |
| Helix | `tree-sitter-fixed/` | Add `[[grammar]]` and `[[language]]` entries; queries in `queries/`. |
| Emacs 29+ | `tree-sitter-fixed/` | Add the grammar to `treesit-language-source-alist`; see the tree-sitter README. |
| Pygments | `pygments/` | `python -m pip install -e editors/pygments`; then `pygmentize -l fixed`. |
| highlight.js | `highlightjs/` | `hljs.registerLanguage("fixed", require("./editors/highlightjs/fixed.js"))`. |
| Shiki | `vscode/syntaxes/fixed.tmLanguage.json` | Load the grammar with `createHighlighter({ langs: [...] })`. |
| Laika | `../project/FixedSyntax.scala` | Already used by `sbt docs/tlSite`; no install step. |
| TextMate | `vscode/syntaxes/fixed.tmLanguage.json` | Bundle/extension; GitHub highlighting uses Linguist. |

## Upstream status

| Target | Status |
|---|---|
| VS Code Marketplace / Open VSX | Extension is packaged in `vscode/`; publish pending. |
| Zed extension registry | Extension is scaffolded in `zed/`; registry PR pending. Pin the grammar `commit` before publishing. |
| nvim-treesitter | Parser and queries are ready; parser PR pending. |
| Helix | Queries are ready; language/grammar PR pending. |
| Pygments core | Local lexer works; core PR deferred by Pygments’ community-size expectation. |
| highlight.js | Local module works; `SUPPORTED_LANGUAGES.md` listing PR pending. |
| Shiki bundled | TextMate grammar is ready; bundled-registry PR deferred by the popularity/Linguist gate. |
| GitHub Linguist | TextMate grammar is ready; PR deferred until the extension meets the 2,000-file usage threshold. |
| Laika upstream | Optional; the site uses the local highlighter. |

The Laika highlighter is not portable: Laika 1.3 ships its own highlighter
registry and has no highlight.js integration, so the docs site needs the Scala
`SyntaxHighlighter`. It lives in `project/` because `laikaExtensions` is
evaluated on the build-definition classpath.

## tree-sitter capabilities

`tree-sitter-fixed/` ships the full editor query set:

- `queries/highlights.scm` — contextual captures (`@keyword.operator` for
  `is`/`of`/`with`/`as`/`extends`, `@keyword.import` for `use`/`using`,
  `@keyword.function`, `@keyword.conditional`, `@keyword.exception`),
  declarations, calls, parameters, properties, types, operators, punctuation.
- `queries/locals.scm` — scopes and definitions for functions, lambdas,
  blocks, `let`, `do` binds, and `forall` binders.
- `queries/folds.scm` — fold regions for declarations and indented bodies.
- `queries/indents.scm` — Neovim `@indent.begin`/`@indent.end` and Helix
  `@indent`/`@outdent`.
- `queries/textobjects.scm` — function/class/parameter/block/comment objects.
- `queries/tags.scm` — definitions plus call/module references for
  `tree-sitter tags` and symbol navigation.
- `test/corpus/core.txt` — corpus cases for functions, caps/data,
  effects/handlers, and match/contextual keywords.

`tree-sitter.json` records the schema-supported `highlights`, `locals`, and
`tags`; folds, indents, and textobjects are discovered by filename in Neovim
and Helix. The Zed extension has its own copies under `zed/languages/fixed/`
because Zed's capture set differs from Neovim/Helix.

## Smoke tests

```sh
# Pygments (no install required)
PYTHONPATH=editors/pygments python3 -c \
  'from pygments import highlight; from pygments.formatters import Terminal256Formatter; \
   from fixed_pygments.lexer import FixedLexer; \
   print(highlight(open("examples/01_basics.fixed").read(), FixedLexer(), Terminal256Formatter()))'

# Pygments as a registered plugin
python3 -m venv --system-site-packages /tmp/fixed-venv
/tmp/fixed-venv/bin/pip install --no-deps -e editors/pygments
/tmp/fixed-venv/bin/python -m pygments -l fixed -f html -O nowrap examples/01_basics.fixed

# highlight.js
npm install --no-save highlight.js
node -e 'const h=require("highlight.js"); h.registerLanguage("fixed", require("./editors/highlightjs/fixed.js")); console.log(h.highlight(require("fs").readFileSync("examples/01_basics.fixed","utf8"),{language:"fixed"}).value)'

# TextMate (Shiki is a TextMate-compatible renderer)
npm install --no-save shiki
node --input-type=module -e '
import fs from "node:fs";
import { createHighlighter } from "shiki";
const g = JSON.parse(fs.readFileSync("editors/vscode/syntaxes/fixed.tmLanguage.json", "utf8"));
const { name, scopeName, ...rest } = g;
const h = await createHighlighter({ themes: ["github-dark"], langs: [{ name: "fixed", scopeName, ...rest }] });
console.log(h.codeToHtml(fs.readFileSync("examples/01_basics.fixed", "utf8"), { lang: "fixed", theme: "github-dark" }));
'

# tree-sitter
cd editors/tree-sitter-fixed
npx tree-sitter-cli generate
npx tree-sitter-cli test
npx tree-sitter-cli parse ../../examples/01_basics.fixed
npx tree-sitter-cli query queries/highlights.scm ../../examples/01_basics.fixed
```

`tree-sitter highlight` and `tree-sitter tags` additionally need the grammar
directory in `~/.config/tree-sitter/config.json` (`parser-directories`).
`pygmentize -l fixed` works after the editable install above; the lexer is also
importable directly through `PYTHONPATH`.

## Known limits

All five highlighters are lexical. Fixed's `is`, `of`, `with`, `as`, `extends`,
`using` and `Self` are contextual; the TextMate, Pygments, highlight.js and
Laika grammars scope them as keywords wherever the reserved token appears.
`assume` is recognized only before `prop`, and `strict` only inside
`fip(...)`. The tree-sitter grammar encodes the contexts in the grammar, so
those words remain identifiers outside them.

The tree-sitter grammar is tuned for highlighting, not conformance. All 11
`examples/*.fixed` parse with zero `ERROR`/`MISSING` nodes, and the corpus tests
cover the main declaration, effect, handler, and pattern forms. The real parser
remains the Scala compiler.
