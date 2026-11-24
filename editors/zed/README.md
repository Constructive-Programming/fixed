# Fixed for Zed

Tree-sitter language support for the
[Fixed programming language](https://fixed.constructive.dev).

## Install

From the Zed extensions page, search for **Fixed** once the extension is in
the registry.

For local development, open the command palette, run
`zed: install dev extension`, and select this directory. Zed builds the
extension host automatically.

The extension pins the grammar to the commit that contains it:

```toml
[grammars.fixed]
repository = "https://github.com/Constructive-Programming/fixed"
commit = "5b57ded78ef78cfdcab1c330753ca9d4481a27c5"
path = "editors/tree-sitter-fixed"
```

Bump the SHA when the grammar changes; the registry and users then get a
reproducible grammar.

## What it provides

- Tree-sitter syntax highlighting (`highlights.scm`).
- Indentation for blocks, `data`, `match`, handlers, and brackets.
- Bracket matching.
- Function/class text objects and code outline entries.
- `//` line comments and `.fixed` file association.

## Files

| File | Purpose |
|---|---|
| `extension.toml` | Extension manifest and grammar source. |
| `languages/fixed/config.toml` | Language name, suffix, comments, indentation, brackets. |
| `languages/fixed/highlights.scm` | Syntax highlighting. |
| `languages/fixed/indents.scm` | Indentation ranges. |
| `languages/fixed/brackets.scm` | Bracket pairs. |
| `languages/fixed/textobjects.scm` | Function/class/comment text objects. |
| `languages/fixed/outline.scm` | Outline symbols. |

## Publishing

Open a PR to [`zed-industries/extensions`](https://github.com/zed-industries/extensions)
that adds this directory as a submodule and an entry to `extensions.toml`.
Pin `commit` to a SHA first. The extension repository must be public.

## Links

- [Language documentation](https://fixed.constructive.dev)
- [Repository](https://github.com/Constructive-Programming/fixed)
- [Editor artifacts](../README.md)
- [Zed language extension docs](https://zed.dev/docs/extensions/languages)
