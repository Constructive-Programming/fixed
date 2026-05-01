package fixed.parsing

import fixed.util.{Reporter, SourceFile}
import munit.FunSuite

class ScannerSuite extends FunSuite:

  private def scan(input: String): (Seq[Token], Reporter) =
    val src = SourceFile.fromString("<test>", input)
    val rep = new Reporter(src)
    val toks = Scanner.tokenize(src, rep)
    (toks, rep)

  private def kinds(input: String): Seq[TokenKind] =
    scan(input)._1.map(_.kind)

  // ---- Trivial cases ----

  test("empty input yields just EOF"):
    assertEquals(kinds(""), Seq(TokenKind.Eof))

  test("single keyword"):
    assertEquals(kinds("fn"), Seq(TokenKind.KwFn, TokenKind.Eof))

  test("ident vs keyword distinction"):
    val ks = kinds("fn foo bar")
    assertEquals(ks, Seq(TokenKind.KwFn, TokenKind.LowerIdent, TokenKind.LowerIdent, TokenKind.Eof))

  test("Self vs self"):
    val ks = kinds("Self self")
    assertEquals(ks, Seq(TokenKind.KwSelf, TokenKind.KwSelfLower, TokenKind.Eof))

  test("Part as a keyword"):
    val ks = kinds("Part Other")
    assertEquals(ks, Seq(TokenKind.KwPart, TokenKind.UpperIdent, TokenKind.Eof))

  // ---- Punctuation and operators ----

  test("simple punctuation"):
    val ks = kinds("( ) [ ] , : ;")
    assertEquals(
      ks,
      Seq(TokenKind.LParen, TokenKind.RParen, TokenKind.LBracket, TokenKind.RBracket,
          TokenKind.Comma, TokenKind.Colon, TokenKind.Semicolon, TokenKind.Eof)
    )

  test("multi-char operators"):
    val ks = kinds("-> => == != <= >= |> && || <-")
    assertEquals(
      ks,
      Seq(TokenKind.Arrow, TokenKind.FatArrow, TokenKind.EqEq, TokenKind.Neq,
          TokenKind.Le, TokenKind.Ge, TokenKind.PipeForward, TokenKind.AndAnd,
          TokenKind.OrOr, TokenKind.BackArrow, TokenKind.Eof)
    )

  test("single-char operators"):
    val ks = kinds("+ - * / % < > = . | !")
    assertEquals(
      ks,
      Seq(TokenKind.Plus, TokenKind.Minus, TokenKind.Star, TokenKind.Slash,
          TokenKind.Percent, TokenKind.Lt, TokenKind.Gt, TokenKind.Eq,
          TokenKind.Dot, TokenKind.Pipe, TokenKind.Bang, TokenKind.Eof)
    )

  // ---- Identifiers ----

  test("lower vs upper ident"):
    val (toks, _) = scan("foo Bar baz_42 _under")
    assertEquals(toks.map(_.kind), Seq(
      TokenKind.LowerIdent, TokenKind.UpperIdent, TokenKind.LowerIdent,
      TokenKind.LowerIdent, TokenKind.Eof
    ))
    assertEquals(toks.take(4).map(_.lexeme), Seq("foo", "Bar", "baz_42", "_under"))

  // ---- Literals ----

  test("integer literal"):
    val (toks, _) = scan("42 0 1024")
    assertEquals(toks.take(3).map(_.kind), Seq(TokenKind.IntLit, TokenKind.IntLit, TokenKind.IntLit))
    assertEquals(toks.take(3).map(_.lexeme), Seq("42", "0", "1024"))

  test("float literal"):
    val (toks, _) = scan("3.14 0.0")
    assertEquals(toks.take(2).map(_.kind), Seq(TokenKind.FloatLit, TokenKind.FloatLit))
    assertEquals(toks.take(2).map(_.lexeme), Seq("3.14", "0.0"))

  test("integer-dot-method (no float)"):
    // `5.foo` should NOT lex as a float; it's `5`, `.`, `foo`.
    val ks = kinds("5.foo")
    assertEquals(ks, Seq(TokenKind.IntLit, TokenKind.Dot, TokenKind.LowerIdent, TokenKind.Eof))

  test("string literal"):
    val (toks, _) = scan(""""hello"""")
    assertEquals(toks.head.kind, TokenKind.StringLit)
    assertEquals(toks.head.lexeme, "hello")

  test("string literal with escapes"):
    val (toks, _) = scan(""""a\nb\tc\\d\"e"""")
    assertEquals(toks.head.kind, TokenKind.StringLit)
    assertEquals(toks.head.lexeme, "a\nb\tc\\d\"e")

  test("char literal"):
    val (toks, _) = scan("'x'")
    assertEquals(toks.head.kind, TokenKind.CharLit)
    assertEquals(toks.head.lexeme, "x")

  test("bool tokens are keywords"):
    val ks = kinds("true false")
    assertEquals(ks, Seq(TokenKind.KwTrue, TokenKind.KwFalse, TokenKind.Eof))

  // ---- Comments ----

  test("line comment is skipped"):
    val ks = kinds("foo // this is ignored\nbar")
    assertEquals(ks, Seq(TokenKind.LowerIdent, TokenKind.Newline, TokenKind.LowerIdent, TokenKind.Eof))

  test("comment-only line still produces no extra newlines beyond the structural one"):
    val (toks, _) = scan("foo\n// just a comment\nbar")
    val ks = toks.map(_.kind)
    // Expectation: foo NEWLINE bar EOF (the comment line produces no token).
    assertEquals(ks, Seq(TokenKind.LowerIdent, TokenKind.Newline, TokenKind.LowerIdent, TokenKind.Eof))

  // ---- Indentation ----

  test("simple block: colon opens, newline closes"):
    val input =
      """fn f:
        |  x
        |""".stripMargin
    val ks = kinds(input)
    // fn f : INDENT x DEDENT EOF — note no NEWLINE between INDENT and x.
    assertEquals(
      ks,
      Seq(TokenKind.KwFn, TokenKind.LowerIdent, TokenKind.Colon,
          TokenKind.Indent, TokenKind.LowerIdent, TokenKind.Dedent, TokenKind.Eof)
    )

  test("nested blocks"):
    val input =
      """fn f:
        |  if x:
        |    a
        |  b
        |""".stripMargin
    val ks = kinds(input)
    // Expected sequence (stylized):
    //   fn f : INDENT if x : INDENT a DEDENT NEWLINE b DEDENT EOF
    assertEquals(
      ks,
      Seq(
        TokenKind.KwFn, TokenKind.LowerIdent, TokenKind.Colon,
        TokenKind.Indent,
        TokenKind.KwIf, TokenKind.LowerIdent, TokenKind.Colon,
        TokenKind.Indent,
        TokenKind.LowerIdent,
        TokenKind.Dedent,
        TokenKind.Newline,
        TokenKind.LowerIdent,
        TokenKind.Dedent,
        TokenKind.Eof
      )
    )

  test("trailing-continuation suppresses newline"):
    // Line ending in `+` should fold into the next physical line (no NEWLINE).
    val input =
      """a +
        |b
        |""".stripMargin
    val ks = kinds(input)
    assertEquals(
      ks,
      Seq(TokenKind.LowerIdent, TokenKind.Plus, TokenKind.LowerIdent, TokenKind.Eof)
    )

  test("leading-continuation suppresses newline"):
    // Next line starting with `with` continues the previous logical line.
    val input =
      """fn f
        |  with C
        |""".stripMargin
    val ks = kinds(input)
    // No NEWLINE between `f` and `with`; no INDENT either (leading-continuation
    // takes precedence over the apparent indent).
    assertEquals(
      ks,
      Seq(TokenKind.KwFn, TokenKind.LowerIdent, TokenKind.KwWith, TokenKind.UpperIdent, TokenKind.Eof)
    )

  test("brackets suppress off-side rule"):
    val input =
      """f(
        |  x,
        |  y,
        |)
        |""".stripMargin
    val ks = kinds(input)
    // Inside ( ... ) indentation is suppressed entirely — no INDENT/DEDENT/NEWLINE.
    assertEquals(
      ks,
      Seq(
        TokenKind.LowerIdent, TokenKind.LParen,
        TokenKind.LowerIdent, TokenKind.Comma,
        TokenKind.LowerIdent, TokenKind.Comma,
        TokenKind.RParen, TokenKind.Eof
      )
    )

  test("dual-mode `=` opens an indented body"):
    val input =
      """fn f =
        |  x
        |""".stripMargin
    val ks = kinds(input)
    // fn f = INDENT x DEDENT EOF (no NEWLINE between `=` and INDENT).
    assertEquals(
      ks,
      Seq(TokenKind.KwFn, TokenKind.LowerIdent, TokenKind.Eq,
          TokenKind.Indent, TokenKind.LowerIdent, TokenKind.Dedent, TokenKind.Eof)
    )

  // ---- Error handling ----

  test("unterminated string emits an error"):
    val (_, rep) = scan("\"open")
    assert(rep.hasErrors)

  test("tab character emits an error"):
    val (_, rep) = scan("\tfoo")
    assert(rep.hasErrors)

  test("stray `&` errors"):
    val (_, rep) = scan("a & b")
    assert(rep.hasErrors)

  // ---- Example corpus integration ----

  // Each example file in examples/*.fixed should scan to a token stream
  // with balanced INDENT/DEDENT and no scanner errors. (Parser-level
  // validation is Phase 2.M3+; this just exercises the scanner.)
  private val exampleNames = Seq(
    "01_basics", "02_collections", "03_option_result", "04_json",
    "05_phantom_types", "06_functor_monad", "07_recursive_data",
    "08_effects_handlers", "09_interpreter", "10_geometry", "11_properties"
  )

  for name <- exampleNames do
    test(s"scan example: $name"):
      val path = s"examples/$name.fixed"
      val src = SourceFile.fromPath(path)
      val rep = new Reporter(src)
      val toks = Scanner.tokenize(src, rep)

      val errors = rep.diagnostics.filter(_.isError)
      val errorReport =
        if errors.isEmpty then ""
        else "\n" + errors.map(rep.format).mkString("\n")
      assert(errors.isEmpty, s"$name produced ${errors.size} scanner errors:$errorReport")

      // Indent/dedent balance: total INDENTs must equal total DEDENTs.
      val indents = toks.count(_.kind == TokenKind.Indent)
      val dedents = toks.count(_.kind == TokenKind.Dedent)
      assertEquals(
        indents, dedents,
        s"$name has unbalanced INDENT/DEDENT: $indents vs $dedents"
      )

      // Last token must be EOF (and only one).
      assertEquals(toks.last.kind, TokenKind.Eof, s"$name does not end in EOF")
      assertEquals(toks.count(_.kind == TokenKind.Eof), 1, s"$name has multiple EOFs")

end ScannerSuite
