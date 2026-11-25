{%
laika.excludeFromNavigation = true
%}

# Editor support

Fixed ships syntax highlighting and language support for VS Code, Zed,
tree-sitter editors, Pygments, highlight.js, Shiki, and the Laika site.
The source artifacts live in
[`editors/`](https://github.com/Constructive-Programming/fixed/tree/main/editors).

## VS Code

The extension in
[`editors/vscode`](https://github.com/Constructive-Programming/fixed/tree/main/editors/vscode)
provides the `.fixed` language mode, TextMate highlighting, bracket matching,
comment toggling, and indentation.

```sh
cd editors/vscode
npx @vscode/vsce package
code --install-extension fixed-0.1.0.vsix
```

Once published, install `constructive-programming.fixed` from the VS Code
Marketplace or Open VSX.

## Zed

The extension in
[`editors/zed`](https://github.com/Constructive-Programming/fixed/tree/main/editors/zed)
uses the tree-sitter grammar and adds highlighting, indentation, bracket
matching, text objects, and outline entries.

For local development, use `zed: install dev extension` and select
`editors/zed`. Once published, install **Fixed** from the Zed extensions page.

## Neovim and Helix

The tree-sitter grammar and queries live in
[`editors/tree-sitter-fixed`](https://github.com/Constructive-Programming/fixed/tree/main/editors/tree-sitter-fixed).
Neovim support is being prepared for `nvim-treesitter`; Helix support is a
`languages.toml` plus `runtime/queries/fixed/` addition. Both use the same
grammar and query set. The grammar parses all `examples/*.fixed` with zero
`ERROR`/`MISSING` nodes.

## Pygments

Install the lexer as a local plugin:

```sh
python -m pip install -e editors/pygments
pygmentize -l fixed -f html -O nowrap examples/01_basics.fixed
```

The lexer is registered as `fixed` for Sphinx, MkDocs, Jupyter, and any other
Pygments consumer.

## highlight.js and Shiki

The `highlight.js` definition is in
[`editors/highlightjs/fixed.js`](https://github.com/Constructive-Programming/fixed/blob/main/editors/highlightjs/fixed.js):

```js
const hljs = require("highlight.js");
hljs.registerLanguage("fixed", require("./editors/highlightjs/fixed.js"));
```

Shiki can load the TextMate grammar directly:

```js
const grammar = JSON.parse(
  fs.readFileSync("editors/vscode/syntaxes/fixed.tmLanguage.json", "utf8"),
);
const highlighter = await createHighlighter({
  langs: [{ name: "fixed", ...grammar }],
  themes: ["github-dark"],
});
```

## Laika

The site itself uses `project/FixedSyntax.scala` through
`SyntaxHighlighting.withSyntax(FixedSyntax)`. No separate install is needed;
`sbt docs/tlSite` builds the highlighted documentation.

## Upstream status

| Target | Status |
|---|---|
| VS Code Marketplace / Open VSX | Extension packaged; publish pending. |
| Zed extension registry | Extension scaffolded; registry PR pending. |
| nvim-treesitter | Parser and queries ready; PR pending. |
| Helix | Queries ready; PR pending. |
| Pygments core | Local lexer; core PR deferred by the community-size expectation. |
| highlight.js | Local module; listing PR pending. |
| Shiki bundled | TextMate grammar ready; bundled-registry PR deferred by the popularity gate. |
| GitHub Linguist | Grammar ready; PR deferred until the usage threshold is met. |
