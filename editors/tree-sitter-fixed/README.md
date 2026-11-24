# tree-sitter-fixed

Tree-sitter grammar and editor queries for the
[Fixed programming language](https://fixed.constructive.dev).

The grammar is tuned for editor highlighting and navigation, not for
conformance. The Scala compiler remains the reference parser. All
`examples/*.fixed` parse with zero `ERROR`/`MISSING` nodes.

## Use

### Zed

The Zed extension in [`../zed`](../zed) loads this directory as its grammar:

```toml
[grammars.fixed]
repository = "https://github.com/Constructive-Programming/fixed"
commit = "main"
path = "editors/tree-sitter-fixed"
```

### Neovim

Once the `nvim-treesitter` parser PR lands:

```vim
:TSInstall fixed
```

Until then, add a local parser config pointing at this directory; see
[`nvim-treesitter` Adding parsers](https://github.com/nvim-treesitter/nvim-treesitter#adding-parsers).

### Helix

Add a `[[grammar]]` entry for this repository and a `[[language]]` entry for
`fixed`; copy the queries from [`queries/`](queries) into
`runtime/queries/fixed/`. See
[Adding languages to Helix](https://docs.helix-editor.com/guides/adding_languages.html).

### Emacs 29+

Point `treesit-language-source-alist` at the grammar source and add
`(fixed "https://github.com/Constructive-Programming/fixed" "editors/tree-sitter-fixed")`.
The exact source tuple depends on your Emacs tree-sitter loader.

## Queries

| File | Purpose |
|---|---|
| `queries/highlights.scm` | Syntax highlighting. |
| `queries/locals.scm` | Scopes and definitions. |
| `queries/folds.scm` | Fold regions. |
| `queries/indents.scm` | Indentation. |
| `queries/textobjects.scm` | Text objects. |
| `queries/tags.scm` | Symbol tags. |

Zed-specific queries live in [`../zed/languages/fixed`](../zed/languages/fixed).

## Development

```sh
cd editors/tree-sitter-fixed
npx tree-sitter-cli generate
npx tree-sitter-cli test
npx tree-sitter-cli parse ../../examples/01_basics.fixed
npx tree-sitter-cli query queries/highlights.scm ../../examples/01_basics.fixed
```

`tree-sitter highlight` and `tree-sitter tags` additionally need this directory
in `~/.config/tree-sitter/config.json` (`parser-directories`).

## Links

- [Editor support](../README.md)
- [Repository](https://github.com/Constructive-Programming/fixed)
