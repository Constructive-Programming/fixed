/*
 * highlight.js language definition for Fixed.
 *
 * Usage:
 *   const hljs = require("highlight.js");
 *   hljs.registerLanguage("fixed", require("./fixed.js"));
 *   hljs.highlight(code, { language: "fixed" }).value;
 *
 * Browser:
 *   <script src="highlight.min.js"></script>
 *   <script src="fixed.js"></script>
 *   <script>hljs.registerLanguage("fixed", fixed);</script>
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.fixed = factory();
  }
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  return function fixed(hljs) {
    // Fixed v0.4.16 has 35 reserved words. `assume` and `strict` are
    // contextual and are handled by the declaration/`fip` modes below.
    const KEYWORDS = {
      keyword:
        "if else match handle do let resume return " +
        "forall suchThat implies is of with as extends in mod " +
        "impossible unreachable",
      type:
        "fn cap data effect type prop satisfies use using pub linear fip",
      literal: "true false",
      built_in: "Self",
    };

    const FN_DECLARATION = {
      begin: [/\bfn\b/, /\s+/, /[a-z_][A-Za-z0-9_]*/],
      beginScope: { 1: "keyword", 3: "title.function" },
      relevance: 0,
    };

    const TYPE_DECLARATION = {
      begin: [/\b(cap|data|effect|type)\b/, /\s+/, /[A-Z][A-Za-z0-9_]*/],
      beginScope: { 1: "keyword", 3: "title.class" },
      relevance: 0,
    };

    const PROP_DECLARATION = {
      begin: [/\bprop\b/, /\s+/, /[a-z_][A-Za-z0-9_]*/],
      beginScope: { 1: "keyword", 3: "title.function" },
      relevance: 0,
    };

    const SATISFIES = {
      begin: [
        /\b[A-Z][A-Za-z0-9_]*\b/,
        /\s+/,
        /\bsatisfies\b/,
        /\s+/,
        /[A-Z][A-Za-z0-9_]*/,
      ],
      beginScope: { 1: "title.class", 3: "keyword", 5: "title.class" },
      relevance: 0,
    };

    const USE_PATH = {
      begin: [/\buse\b/, /\s+/, /[a-z_][A-Za-z0-9_.]*/],
      beginScope: { 1: "keyword", 3: "title.class" },
      relevance: 0,
    };

    const SELF_FN = {
      begin: [
        /\bSelf\b/,
        /\s*\.\s*/,
        /\bfn\b/,
        /\s+/,
        /[a-z_][A-Za-z0-9_]*/,
      ],
      beginScope: { 1: "built_in", 3: "keyword", 5: "title.function" },
      relevance: 0,
    };

    const FIP_STRICT = {
      begin: [/\bfip\b/, /\s*\(\s*/, /strict/, /\s*\)/],
      beginScope: { 1: "keyword", 3: "keyword" },
      relevance: 0,
    };

    const FIP_N = {
      begin: [/\bfip\b/, /\s*\(\s*/, /\d[\d_]*/, /\s*\)/],
      beginScope: { 1: "keyword", 3: "number" },
      relevance: 0,
    };

    const ASSUME_PROP = {
      begin: [/\bassume\b/, /\s+/, /\bprop\b/, /\s+/, /[a-z_][A-Za-z0-9_]*/],
      beginScope: { 1: "keyword", 3: "keyword", 5: "title.function" },
      relevance: 0,
    };

    const FUNCTION_CALL = {
      className: "title.function.invoke",
      begin:
        /\b(?!(?:if|else|match|handle|do|let|resume|return|forall|suchThat|implies|is|of|with|as|extends|in|mod|impossible|unreachable|fn|cap|data|effect|type|prop|satisfies|use|using|pub|linear|fip|true|false|Self)\b)[a-z_][A-Za-z0-9_]*(?=\s*\()/,
      relevance: 0,
    };

    const PROPERTY = {
      begin: [/\./, /\s*/, /[a-z_][A-Za-z0-9_]*/],
      beginScope: { 1: "punctuation", 3: "property" },
      relevance: 0,
    };

    return {
      name: "Fixed",
      aliases: ["fixed"],
      keywords: KEYWORDS,
      contains: [
        hljs.COMMENT("//", "$"),
        hljs.QUOTE_STRING_MODE,
        {
          className: "string",
          begin: /'(?:[^'\\]|\\.)'/,
        },
        {
          className: "number",
          begin: /\b\d[\d_]*(?:\.\d[\d_]*(?:[eE][+-]?\d+)?)?\b/,
        },
        SELF_FN,
        TYPE_DECLARATION,
        ASSUME_PROP,
        PROP_DECLARATION,
        SATISFIES,
        USE_PATH,
        FN_DECLARATION,
        FIP_STRICT,
        FIP_N,
        PROPERTY,
        FUNCTION_CALL,
      ],
    };
  };
});
