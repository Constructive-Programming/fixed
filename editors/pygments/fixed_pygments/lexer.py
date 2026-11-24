"""Pygments lexer for the Fixed programming language.

Fixed is the capability-level language specified in ``spec/syntax_grammar.ebnf``.
The lexer is deliberately lexical: Fixed has contextual keywords (``is``,
``of``, ``with``, ``as``, ``using``), so this file scopes them as keywords
wherever they occur rather than attempting a parse.
"""

from pygments.lexer import RegexLexer, bygroups, words
from pygments.token import (
    Comment,
    Keyword,
    Name,
    Number,
    Operator,
    Punctuation,
    String,
    Whitespace,
)

__all__ = ["FixedLexer"]


class FixedLexer(RegexLexer):
    """Lexer for the Fixed language."""

    name = "Fixed"
    aliases = ["fixed"]
    filenames = ["*.fixed"]
    mimetypes = ["text/x-fixed"]

    tokens = {
        "root": [
            (r"//.*$", Comment.Single),

            # Declarations: keep the declared name out of the generic
            # identifier rule so editors can colour it as a definition.
            (
                r"\b(fn)(\s+)([a-z_][A-Za-z0-9_]*)",
                bygroups(Keyword.Declaration, Whitespace, Name.Function),
            ),
            (
                r"\b(Self)(\s*\.\s*)(fn)(\s+)([a-z_][A-Za-z0-9_]*)",
                bygroups(
                    Name.Builtin.Pseudo,
                    Punctuation,
                    Keyword.Declaration,
                    Whitespace,
                    Name.Function,
                ),
            ),
            (
                r"\b(cap|data|effect|type)(\s+)([A-Z][A-Za-z0-9_]*)",
                bygroups(Keyword.Declaration, Whitespace, Name.Class),
            ),
            (
                r"\b(prop)(\s+)([a-z_][A-Za-z0-9_]*)",
                bygroups(Keyword.Declaration, Whitespace, Name.Function),
            ),
            (
                r"\b(assume)(\s+)(prop)(\s+)([a-z_][A-Za-z0-9_]*)",
                bygroups(
                    Keyword,
                    Whitespace,
                    Keyword.Declaration,
                    Whitespace,
                    Name.Function,
                ),
            ),
            (
                r"\b([A-Z][A-Za-z0-9_]*|impossible)(\s+)(satisfies)"
                r"(\s+)([A-Z][A-Za-z0-9_]*)",
                bygroups(
                    Name.Class,
                    Whitespace,
                    Keyword,
                    Whitespace,
                    Name.Class,
                ),
            ),
            (r"\b(satisfies)\b", Keyword),
            (
                r"\b(fip)(\s*)(\()(\s*)(strict)(\s*)(\))",
                bygroups(
                    Keyword.Declaration,
                    Whitespace,
                    Punctuation,
                    Whitespace,
                    Keyword.Constant,
                    Whitespace,
                    Punctuation,
                ),
            ),
            (
                r"\b(fip)(\s*)(\()(\s*)(\d[\d_]*)(\s*)(\))",
                bygroups(
                    Keyword.Declaration,
                    Whitespace,
                    Punctuation,
                    Whitespace,
                    Number.Integer,
                    Whitespace,
                    Punctuation,
                ),
            ),
            (
                r"\b(use)(\s+)([a-z_][A-Za-z0-9_.]*)",
                bygroups(Keyword.Namespace, Whitespace, Name.Namespace),
            ),
            (r"\b(using)\b", Keyword.Namespace),

            # Literals.
            (r'"', String.Double, "string"),
            (r"'", String.Char, "char"),
            (
                r"\b\d[\d_]*\.\d[\d_]*(?:[eE][+-]?\d+)?\b",
                Number.Float,
            ),
            (r"\b\d[\d_]*\b", Number.Integer),

            # Reserved words. 35 in v0.4.16, plus the contextual
            # ``assume``/``strict`` handled above.
            (
                words(
                    (
                        "if",
                        "else",
                        "match",
                        "handle",
                        "do",
                        "let",
                        "resume",
                        "return",
                    ),
                    suffix=r"\b",
                ),
                Keyword,
            ),
            (
                words(
                    (
                        "fn",
                        "cap",
                        "data",
                        "effect",
                        "type",
                        "prop",
                        "satisfies",
                        "use",
                        "using",
                        "pub",
                        "linear",
                        "fip",
                    ),
                    suffix=r"\b",
                ),
                Keyword.Declaration,
            ),
            (
                words(
                    (
                        "forall",
                        "suchThat",
                        "implies",
                        "is",
                        "of",
                        "with",
                        "as",
                        "extends",
                        "in",
                        "mod",
                        "impossible",
                        "unreachable",
                    ),
                    suffix=r"\b",
                ),
                Keyword,
            ),
            (r"\b(true|false)\b", Keyword.Constant),
            (r"\bSelf\b", Name.Builtin.Pseudo),

            # Names.
            (
                r"\b([a-z_][A-Za-z0-9_]*)(\s*)(:)",
                bygroups(Name.Variable, Whitespace, Punctuation),
            ),
            (
                r"(\.)(\s*)([A-Z][A-Za-z0-9_]*)",
                bygroups(Punctuation, Whitespace, Name.Class),
            ),
            (
                r"(\.)(\s*)([a-z_][A-Za-z0-9_]*)",
                bygroups(Punctuation, Whitespace, Name.Attribute),
            ),
            (r"\b([a-z_][A-Za-z0-9_]*)(?=\s*\()", Name.Function),
            (r"\b[A-Z][A-Za-z0-9_]*\b", Name.Class),

            # Operators and punctuation.
            (r"->|=>|\|>|<-", Operator),
            (r"==|!=|<=|>=|&&|\|\|", Operator),
            (r"[+\-*/%<>=!|]", Operator),
            (r"[(){}\[\],.:;]", Punctuation),
            (r"\s+", Whitespace),
        ],
        "string": [
            (r'[^"\\]+', String.Double),
            (r"\\(?:u\{[0-9A-Fa-f]+\}|.)", String.Escape),
            (r'"', String.Double, "#pop"),
        ],
        "char": [
            (r"[^'\\]", String.Char),
            (r"\\(?:u\{[0-9A-Fa-f]+\}|.)", String.Escape),
            (r"'", String.Char, "#pop"),
        ],
    }
