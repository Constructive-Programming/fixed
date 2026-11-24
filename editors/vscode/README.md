# Fixed for VS Code

Syntax highlighting, bracket matching, and `//` comments for the
[Fixed programming language](https://fixed.constructive.dev).

## Install

From a checkout:

```sh
cd editors/vscode
npx @vscode/vsce package
code --install-extension fixed-0.1.0.vsix
```

Once published, install `constructive-programming.fixed` from the VS Code
Marketplace or Open VSX.

## What it provides

- `.fixed` file association and language mode.
- TextMate grammar for Fixed (`source.fixed`).
- Bracket matching, auto-closing pairs, and comment toggling.
- Indentation rules for blocks, `data`, `match`, and handlers.

The grammar is shared with the Shiki, GitHub Linguist, and TextMate
distributions. Source of truth:
[`syntaxes/fixed.tmLanguage.json`](syntaxes/fixed.tmLanguage.json).

## Development

```sh
cd editors/vscode
npx @vscode/vsce package --out /tmp/fixed.vsix
code --install-extension /tmp/fixed.vsix
```

Reload the window after installing. Open any `examples/*.fixed` file to
verify highlighting.

## Publishing

The `publisher` field is `constructive-programming`. To publish:

```sh
npx @vscode/vsce login constructive-programming
npx @vscode/vsce publish
```

## Links

- [Language documentation](https://fixed.constructive.dev)
- [Repository](https://github.com/Constructive-Programming/fixed)
- [Editor artifacts](../README.md)
