package fixed.parsing

import fixed.util.Span
import munit.FunSuite

class TokenSuite extends FunSuite:

  private val sp = Span(3, 7)
  private val carriesText =
    Set(
      TokenKind.LowerIdent,
      TokenKind.UpperIdent,
      TokenKind.IntLit,
      TokenKind.FloatLit,
      TokenKind.StringLit,
      TokenKind.CharLit
    )

  test("Token cases mirror TokenKind: of(k).kind == k for every real kind"):
    TokenKind.values.filterNot(_ == TokenKind.NoStart).foreach { k =>
      val t = Token.of(k, sp, "txt")
      assertEquals(t.kind, k)
      assertEquals(t.span, sp)
      assertEquals(t.lexeme, if carriesText(k) then "txt" else "")
    }

  test("KindSet membership matches Set for every kind, across both 64-bit halves"):
    val members = TokenKind.values.zipWithIndex.collect { case (k, i) if i % 3 == 0 => k }.toSet
    val ks = KindSet(members.toSeq*)
    TokenKind.values.foreach(k => assertEquals(ks.contains(k), members(k), k.toString))
    assert(TokenKind.values.length > 64, "test should cover the high word")
