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
