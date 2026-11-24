package fixed.build

import cats.data.NonEmptyList
import laika.api.bundle.SyntaxHighlighter
import laika.parse.builders._
import laika.parse.code.common._
import laika.parse.code.syntax._
import laika.parse.code.{CodeCategory, CodeSpanParser}
import laika.parse.text.CharGroup

/** Laika syntax highlighter for Fixed.
  *
  * Laika 1.3 ships a fixed registry of highlighters and has no highlight.js
  * integration, so the docs site registers this object with
  * `SyntaxHighlighting.withSyntax(FixedSyntax)` in `build.sbt`.
  *
  * This is lexical, not a parser: `is`, `of`, `with`, `as`, `using` are
  * contextual in Fixed, so they are scoped as keywords wherever they appear.
  * The contextual words `assume` and `strict` are deliberately not in the
  * keyword set; only `assume prop` is recognised.
  */
object FixedSyntax extends SyntaxHighlighter {

  val language: NonEmptyList[String] = NonEmptyList.of("fixed")

  private val comment: CodeSpanParser = Comment.singleLine("//")

  private val escapes: CodeSpanParser =
    StringLiteral.Escape.unicode ++ StringLiteral.Escape.char

  private val stringLiteral: CodeSpanParser =
    StringLiteral.singleLine('"').embed(escapes)

  private val charLiteral: CodeSpanParser =
    CharLiteral.standard.embed(escapes)

  private val numberLiteral: CodeSpanParser =
    NumberLiteral.decimalFloat.withUnderscores ++
      NumberLiteral.decimalInt.withUnderscores

  private val keywords: CodeSpanParser =
    Keywords(CodeCategory.BooleanLiteral)("true", "false") ++
      Keywords(CodeCategory.Keyword)(
        "if",
        "else",
        "match",
        "handle",
        "do",
        "let",
        "resume",
        "return",
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
        "Self"
      )

  private val identifier: Identifier.IdParser =
    Identifier.alphaNum
      .withIdStartChars('_')
      .withCategoryChooser(Identifier.upperCaseTypeName)

  private val declaration: CodeSpanParser = CodeSpanParser {
    val kw = literal("fn").asCode(CodeCategory.Keyword)
    val name = identifier.withCategory(CodeCategory.DeclarationName)
    (kw ~ ws.asCode() ~ name).map { case a ~ b ~ c => Seq(a, b, c) }
  }

  private val typeDeclaration: CodeSpanParser = CodeSpanParser {
    val kw = (literal("cap") | literal("data") | literal("effect") | literal("type"))
      .asCode(CodeCategory.Keyword)
    val name = identifier.withCategory(CodeCategory.TypeName)
    (kw ~ ws.asCode() ~ name).map { case a ~ b ~ c => Seq(a, b, c) }
  }

  private val propDeclaration: CodeSpanParser = CodeSpanParser {
    val kw = literal("prop").asCode(CodeCategory.Keyword)
    val name = identifier.withCategory(CodeCategory.DeclarationName)
    (kw ~ ws.asCode() ~ name).map { case a ~ b ~ c => Seq(a, b, c) }
  }

  private val assumeProp: CodeSpanParser = CodeSpanParser {
    val assume = literal("assume").asCode(CodeCategory.Keyword)
    val prop = literal("prop").asCode(CodeCategory.Keyword)
    val name = identifier.withCategory(CodeCategory.DeclarationName)
    (assume ~ ws.asCode() ~ prop ~ ws.asCode() ~ name)
      .map { case a ~ b ~ c ~ d ~ e => Seq(a, b, c, d, e) }
  }

  private val satisfies: CodeSpanParser = CodeSpanParser {
    val subject = identifier.withCategory(CodeCategory.TypeName)
    val kw = literal("satisfies").asCode(CodeCategory.Keyword)
    val cap = identifier.withCategory(CodeCategory.TypeName)
    (subject ~ ws.asCode() ~ kw ~ ws.asCode() ~ cap)
      .map { case a ~ b ~ c ~ d ~ e => Seq(a, b, c, d, e) }
  }

  private val usePath: CodeSpanParser = CodeSpanParser {
    val kw = literal("use").asCode(CodeCategory.Keyword)
    val path = someOf(CharGroup.alphaNum.add('_').add('.'))
      .source
      .asCode(CodeCategory.Identifier)
    (kw ~ ws.asCode() ~ path).map { case a ~ b ~ c => Seq(a, b, c) }
  }

  val spanParsers: Seq[CodeSpanParser] = Seq(
    comment,
    charLiteral,
    stringLiteral,
    assumeProp,
    declaration,
    typeDeclaration,
    propDeclaration,
    satisfies,
    usePath,
    keywords,
    numberLiteral,
    identifier
  )
}
