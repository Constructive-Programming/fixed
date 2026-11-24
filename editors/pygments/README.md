# fixed-pygments

Pygments lexer for the [Fixed programming language](https://fixed.constructive.dev).

## Install

```sh
python -m pip install -e editors/pygments
```

This registers the `fixed` lexer through the `pygments.lexers` entry point.

## Use

```sh
pygmentize -l fixed -f html -O nowrap examples/01_basics.fixed
```

```python
from pygments import highlight
from pygments.formatters import HtmlFormatter
from fixed_pygments.lexer import FixedLexer

html = highlight(source, FixedLexer(), HtmlFormatter())
```

The lexer is also importable without installation:

```sh
PYTHONPATH=editors/pygments python3 -c \
  'from pygments import highlight; from pygments.formatters import Terminal256Formatter; \
   from fixed_pygments.lexer import FixedLexer; \
   print(highlight(open("examples/01_basics.fixed").read(), FixedLexer(), Terminal256Formatter()))'
```

## Use with Sphinx, MkDocs, Jupyter

Any tool that uses Pygments can select the lexer as `fixed` after the package
is installed. For Sphinx, add `fixed` to the `highlight_language` config or use
a `.. code-block:: fixed` directive.

## Status

The lexer is local to this repository. A core Pygments PR is deferred until
the language has a larger user base; Pygments expects new languages to have a
reasonably sized community. The lexer works as a local plugin now.

## Links

- [Editor support](../README.md)
- [Repository](https://github.com/Constructive-Programming/fixed)
- [Pygments lexer development](https://pygments.org/docs/lexerdevelopment/)
