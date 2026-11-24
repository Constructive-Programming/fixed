# fixed-highlightjs

highlight.js language definition for the
[Fixed programming language](https://fixed.constructive.dev).

## Use

```js
const hljs = require("highlight.js");
hljs.registerLanguage("fixed", require("./editors/highlightjs/fixed.js"));

const html = hljs.highlight(source, { language: "fixed" }).value;
```

In a browser, load highlight.js first, then `fixed.js`, then call
`hljs.registerLanguage("fixed", fixed)`.

## Status

The definition is local to this repository. The upstream route is a
third-party grammar repository plus an entry in highlight.js's
`SUPPORTED_LANGUAGES.md`; that PR is pending.

## Links

- [Editor support](../README.md)
- [Repository](https://github.com/Constructive-Programming/fixed)
- [highlight.js third-party guide](https://github.com/highlightjs/highlight.js/blob/main/extra/3RD_PARTY_QUICK_START.md)
