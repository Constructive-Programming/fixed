package fixed.parsing

import fixed.util.Span

/** Token kinds emitted by the Scanner.
  *
  * Reserved keywords each have a dedicated kind so the parser can dispatch
  * by `case`. Identifiers and literals carry their text in the Token's
  * `lexeme` field.
  *
  * See `spec/syntax_grammar.ebnf` v0.4.5 §"KEYWORDS" for the canonical list.
  */
enum TokenKind:
  // ---- Reserved words (alphabetical) ----
  case KwAs, KwCap, KwData, KwDo, KwEffect, KwElse, KwExtends, KwFalse,
    KwFn, KwForall, KwHandle, KwIf, KwImplies, KwImpossible, KwIn, KwIs,
    KwLet, KwLinear, KwMatch, KwMod, KwOf, KwPart, KwProp, KwPub,
    KwResume, KwReturn, KwSatisfies, KwSelf, KwSelfLower, KwSuchThat,
    KwTrue, KwType, KwUnreachable, KwUse, KwWith

  // ---- Identifiers ----
  case LowerIdent // [a-z_][a-zA-Z_0-9]*
  case UpperIdent // [A-Z][a-zA-Z_0-9]*

  // ---- Literals ----
  case IntLit
  case FloatLit
  case StringLit
  case CharLit
  // Bool literals are KwTrue / KwFalse — no separate BoolLit kind.

  // ---- Punctuation / structural ----
  case LParen, RParen, LBrace, RBrace, LBracket, RBracket
  case Comma, Colon, Semicolon, Dot

  // ---- Operators ----
  case Eq // =
  case Arrow // ->
  case FatArrow // =>
  case BackArrow // <- (bind in `do:`)
  case Pipe // |   (or-pattern separator)
  case PipeForward // |>
  case Plus, Minus, Star, Slash, Percent
  case Lt, Le, Gt, Ge, EqEq, Neq
  case AndAnd, OrOr
  case Bang
  case Underscore
  case At // @ — reserved; not yet used

  // ---- Synthetic (scanner-emitted, not literal source text) ----
  case Newline // logical newline (between same-indent lines)
  case Indent // entering a more-indented block
  case Dedent // closing one indent level
  case Eof
  case Error // lex error — accompanied by a Reporter entry
  case NoStart // sentinel: scanner has not yet emitted a token

end TokenKind

/** A set of token kinds as a 128-bit mask over `TokenKind.ordinal` (79 kinds).
  * Membership is a shift and an AND — no hashing, no allocation.
  */
final case class KindSet(lo: Long, hi: Long):
  def contains(k: TokenKind): Boolean =
    val o = k.ordinal
    if o < 64 then (lo & (1L << o)) != 0L else (hi & (1L << (o - 64))) != 0L

  def +(k: TokenKind): KindSet =
    val o = k.ordinal
    if o < 64 then KindSet(lo | (1L << o), hi) else KindSet(lo, hi | (1L << (o - 64)))

object KindSet:
  val empty: KindSet = KindSet(0L, 0L)
  def apply(kinds: TokenKind*): KindSet = kinds.foldLeft(empty)(_ + _)

/** A single token. Each case is one token kind; only identifiers and
  * literals (`LowerIdent`, `UpperIdent`, `IntLit`, `FloatLit`, `StringLit`,
  * `CharLit`) carry their source text.
  *
  * `span`, `kind` and `lexeme` live in the base class: every case fixes its
  * own `TokenKind`, and cases without text fix `lexeme` to `""` (callers
  * cannot give them one). Hot-path reads stay plain field loads instead of
  * calls dispatched over every token case.
  * `TokenSuite` pins that each case reports its own kind.
  */
enum Token(val span: Span, val kind: TokenKind, val lexeme: String):

  case KwAs(at: Span) extends Token(at, TokenKind.KwAs, "")
  case KwCap(at: Span) extends Token(at, TokenKind.KwCap, "")
  case KwData(at: Span) extends Token(at, TokenKind.KwData, "")
  case KwDo(at: Span) extends Token(at, TokenKind.KwDo, "")
  case KwEffect(at: Span) extends Token(at, TokenKind.KwEffect, "")
  case KwElse(at: Span) extends Token(at, TokenKind.KwElse, "")
  case KwExtends(at: Span) extends Token(at, TokenKind.KwExtends, "")
  case KwFalse(at: Span) extends Token(at, TokenKind.KwFalse, "")
  case KwFn(at: Span) extends Token(at, TokenKind.KwFn, "")
  case KwForall(at: Span) extends Token(at, TokenKind.KwForall, "")
  case KwHandle(at: Span) extends Token(at, TokenKind.KwHandle, "")
  case KwIf(at: Span) extends Token(at, TokenKind.KwIf, "")
  case KwImplies(at: Span) extends Token(at, TokenKind.KwImplies, "")
  case KwImpossible(at: Span) extends Token(at, TokenKind.KwImpossible, "")
  case KwIn(at: Span) extends Token(at, TokenKind.KwIn, "")
  case KwIs(at: Span) extends Token(at, TokenKind.KwIs, "")
  case KwLet(at: Span) extends Token(at, TokenKind.KwLet, "")
  case KwLinear(at: Span) extends Token(at, TokenKind.KwLinear, "")
  case KwMatch(at: Span) extends Token(at, TokenKind.KwMatch, "")
  case KwMod(at: Span) extends Token(at, TokenKind.KwMod, "")
  case KwOf(at: Span) extends Token(at, TokenKind.KwOf, "")
  case KwPart(at: Span) extends Token(at, TokenKind.KwPart, "")
  case KwProp(at: Span) extends Token(at, TokenKind.KwProp, "")
  case KwPub(at: Span) extends Token(at, TokenKind.KwPub, "")
  case KwResume(at: Span) extends Token(at, TokenKind.KwResume, "")
  case KwReturn(at: Span) extends Token(at, TokenKind.KwReturn, "")
  case KwSatisfies(at: Span) extends Token(at, TokenKind.KwSatisfies, "")
  case KwSelf(at: Span) extends Token(at, TokenKind.KwSelf, "")
  case KwSelfLower(at: Span) extends Token(at, TokenKind.KwSelfLower, "")
  case KwSuchThat(at: Span) extends Token(at, TokenKind.KwSuchThat, "")
  case KwTrue(at: Span) extends Token(at, TokenKind.KwTrue, "")
  case KwType(at: Span) extends Token(at, TokenKind.KwType, "")
  case KwUnreachable(at: Span) extends Token(at, TokenKind.KwUnreachable, "")
  case KwUse(at: Span) extends Token(at, TokenKind.KwUse, "")
  case KwWith(at: Span) extends Token(at, TokenKind.KwWith, "")
  case LowerIdent(at: Span, text: String) extends Token(at, TokenKind.LowerIdent, text)
  case UpperIdent(at: Span, text: String) extends Token(at, TokenKind.UpperIdent, text)
  case IntLit(at: Span, text: String) extends Token(at, TokenKind.IntLit, text)
  case FloatLit(at: Span, text: String) extends Token(at, TokenKind.FloatLit, text)
  case StringLit(at: Span, text: String) extends Token(at, TokenKind.StringLit, text)
  case CharLit(at: Span, text: String) extends Token(at, TokenKind.CharLit, text)
  case LParen(at: Span) extends Token(at, TokenKind.LParen, "")
  case RParen(at: Span) extends Token(at, TokenKind.RParen, "")
  case LBrace(at: Span) extends Token(at, TokenKind.LBrace, "")
  case RBrace(at: Span) extends Token(at, TokenKind.RBrace, "")
  case LBracket(at: Span) extends Token(at, TokenKind.LBracket, "")
  case RBracket(at: Span) extends Token(at, TokenKind.RBracket, "")
  case Comma(at: Span) extends Token(at, TokenKind.Comma, "")
  case Colon(at: Span) extends Token(at, TokenKind.Colon, "")
  case Semicolon(at: Span) extends Token(at, TokenKind.Semicolon, "")
  case Dot(at: Span) extends Token(at, TokenKind.Dot, "")
  case Eq(at: Span) extends Token(at, TokenKind.Eq, "")
  case Arrow(at: Span) extends Token(at, TokenKind.Arrow, "")
  case FatArrow(at: Span) extends Token(at, TokenKind.FatArrow, "")
  case BackArrow(at: Span) extends Token(at, TokenKind.BackArrow, "")
  case Pipe(at: Span) extends Token(at, TokenKind.Pipe, "")
  case PipeForward(at: Span) extends Token(at, TokenKind.PipeForward, "")
  case Plus(at: Span) extends Token(at, TokenKind.Plus, "")
  case Minus(at: Span) extends Token(at, TokenKind.Minus, "")
  case Star(at: Span) extends Token(at, TokenKind.Star, "")
  case Slash(at: Span) extends Token(at, TokenKind.Slash, "")
  case Percent(at: Span) extends Token(at, TokenKind.Percent, "")
  case Lt(at: Span) extends Token(at, TokenKind.Lt, "")
  case Le(at: Span) extends Token(at, TokenKind.Le, "")
  case Gt(at: Span) extends Token(at, TokenKind.Gt, "")
  case Ge(at: Span) extends Token(at, TokenKind.Ge, "")
  case EqEq(at: Span) extends Token(at, TokenKind.EqEq, "")
  case Neq(at: Span) extends Token(at, TokenKind.Neq, "")
  case AndAnd(at: Span) extends Token(at, TokenKind.AndAnd, "")
  case OrOr(at: Span) extends Token(at, TokenKind.OrOr, "")
  case Bang(at: Span) extends Token(at, TokenKind.Bang, "")
  case Underscore(at: Span) extends Token(at, TokenKind.Underscore, "")
  case At(at: Span) extends Token(at, TokenKind.At, "")
  case Newline(at: Span) extends Token(at, TokenKind.Newline, "")
  case Indent(at: Span) extends Token(at, TokenKind.Indent, "")
  case Dedent(at: Span) extends Token(at, TokenKind.Dedent, "")
  case Eof(at: Span) extends Token(at, TokenKind.Eof, "")
  case Error(at: Span) extends Token(at, TokenKind.Error, "")

object Token:
  /** Build the token of `kind`. `lexeme` is kept only by kinds that carry text. */
  def of(kind: TokenKind, span: Span, lexeme: String = ""): Token = kind match
    case TokenKind.KwAs          => KwAs(span)
    case TokenKind.KwCap         => KwCap(span)
    case TokenKind.KwData        => KwData(span)
    case TokenKind.KwDo          => KwDo(span)
    case TokenKind.KwEffect      => KwEffect(span)
    case TokenKind.KwElse        => KwElse(span)
    case TokenKind.KwExtends     => KwExtends(span)
    case TokenKind.KwFalse       => KwFalse(span)
    case TokenKind.KwFn          => KwFn(span)
    case TokenKind.KwForall      => KwForall(span)
    case TokenKind.KwHandle      => KwHandle(span)
    case TokenKind.KwIf          => KwIf(span)
    case TokenKind.KwImplies     => KwImplies(span)
    case TokenKind.KwImpossible  => KwImpossible(span)
    case TokenKind.KwIn          => KwIn(span)
    case TokenKind.KwIs          => KwIs(span)
    case TokenKind.KwLet         => KwLet(span)
    case TokenKind.KwLinear      => KwLinear(span)
    case TokenKind.KwMatch       => KwMatch(span)
    case TokenKind.KwMod         => KwMod(span)
    case TokenKind.KwOf          => KwOf(span)
    case TokenKind.KwPart        => KwPart(span)
    case TokenKind.KwProp        => KwProp(span)
    case TokenKind.KwPub         => KwPub(span)
    case TokenKind.KwResume      => KwResume(span)
    case TokenKind.KwReturn      => KwReturn(span)
    case TokenKind.KwSatisfies   => KwSatisfies(span)
    case TokenKind.KwSelf        => KwSelf(span)
    case TokenKind.KwSelfLower   => KwSelfLower(span)
    case TokenKind.KwSuchThat    => KwSuchThat(span)
    case TokenKind.KwTrue        => KwTrue(span)
    case TokenKind.KwType        => KwType(span)
    case TokenKind.KwUnreachable => KwUnreachable(span)
    case TokenKind.KwUse         => KwUse(span)
    case TokenKind.KwWith        => KwWith(span)
    case TokenKind.LowerIdent    => LowerIdent(span, lexeme)
    case TokenKind.UpperIdent    => UpperIdent(span, lexeme)
    case TokenKind.IntLit        => IntLit(span, lexeme)
    case TokenKind.FloatLit      => FloatLit(span, lexeme)
    case TokenKind.StringLit     => StringLit(span, lexeme)
    case TokenKind.CharLit       => CharLit(span, lexeme)
    case TokenKind.LParen        => LParen(span)
    case TokenKind.RParen        => RParen(span)
    case TokenKind.LBrace        => LBrace(span)
    case TokenKind.RBrace        => RBrace(span)
    case TokenKind.LBracket      => LBracket(span)
    case TokenKind.RBracket      => RBracket(span)
    case TokenKind.Comma         => Comma(span)
    case TokenKind.Colon         => Colon(span)
    case TokenKind.Semicolon     => Semicolon(span)
    case TokenKind.Dot           => Dot(span)
    case TokenKind.Eq            => Eq(span)
    case TokenKind.Arrow         => Arrow(span)
    case TokenKind.FatArrow      => FatArrow(span)
    case TokenKind.BackArrow     => BackArrow(span)
    case TokenKind.Pipe          => Pipe(span)
    case TokenKind.PipeForward   => PipeForward(span)
    case TokenKind.Plus          => Plus(span)
    case TokenKind.Minus         => Minus(span)
    case TokenKind.Star          => Star(span)
    case TokenKind.Slash         => Slash(span)
    case TokenKind.Percent       => Percent(span)
    case TokenKind.Lt            => Lt(span)
    case TokenKind.Le            => Le(span)
    case TokenKind.Gt            => Gt(span)
    case TokenKind.Ge            => Ge(span)
    case TokenKind.EqEq          => EqEq(span)
    case TokenKind.Neq           => Neq(span)
    case TokenKind.AndAnd        => AndAnd(span)
    case TokenKind.OrOr          => OrOr(span)
    case TokenKind.Bang          => Bang(span)
    case TokenKind.Underscore    => Underscore(span)
    case TokenKind.At            => At(span)
    case TokenKind.Newline       => Newline(span)
    case TokenKind.Indent        => Indent(span)
    case TokenKind.Dedent        => Dedent(span)
    case TokenKind.Eof           => Eof(span)
    case TokenKind.Error         => Error(span)
    // `NoStart` tags scanner state before the first emit and is never a
    // token; asking for one is a scanner bug, surfaced as a lex error.
    case TokenKind.NoStart => Error(span)

object Tokens:

  /** Mapping from keyword text to its TokenKind. Identifier scanning hits
    * this table to decide if a `LowerIdent`/`UpperIdent` is actually a
    * keyword.
    */
  /** Keyword spelling -> token constructor, so the scanner builds keyword
    * tokens directly rather than dispatching through `Token.of`.
    */
  val keywordTokens: Map[String, Span => Token] = Map(
    "as" -> (Token.KwAs(_)),
    "cap" -> (Token.KwCap(_)),
    "data" -> (Token.KwData(_)),
    "do" -> (Token.KwDo(_)),
    "effect" -> (Token.KwEffect(_)),
    "else" -> (Token.KwElse(_)),
    "extends" -> (Token.KwExtends(_)),
    "false" -> (Token.KwFalse(_)),
    "fn" -> (Token.KwFn(_)),
    "forall" -> (Token.KwForall(_)),
    "handle" -> (Token.KwHandle(_)),
    "if" -> (Token.KwIf(_)),
    "implies" -> (Token.KwImplies(_)),
    "impossible" -> (Token.KwImpossible(_)),
    "in" -> (Token.KwIn(_)),
    "is" -> (Token.KwIs(_)),
    "let" -> (Token.KwLet(_)),
    "linear" -> (Token.KwLinear(_)),
    "match" -> (Token.KwMatch(_)),
    "mod" -> (Token.KwMod(_)),
    "of" -> (Token.KwOf(_)),
    "Part" -> (Token.KwPart(_)),
    "prop" -> (Token.KwProp(_)),
    "pub" -> (Token.KwPub(_)),
    "resume" -> (Token.KwResume(_)),
    "return" -> (Token.KwReturn(_)),
    "satisfies" -> (Token.KwSatisfies(_)),
    "Self" -> (Token.KwSelf(_)),
    "self" -> (Token.KwSelfLower(_)),
    "suchThat" -> (Token.KwSuchThat(_)),
    "true" -> (Token.KwTrue(_)),
    "type" -> (Token.KwType(_)),
    "unreachable" -> (Token.KwUnreachable(_)),
    "use" -> (Token.KwUse(_)),
    "with" -> (Token.KwWith(_))
  )

  /** A reserved word: its spelling, kind and token constructor. */
  final case class Keyword(spelling: String, kind: TokenKind, make: Span => Token)

  // Keywords bucketed by first (ASCII) character, so a lookup compares a
  // handful of candidates in place instead of slicing and hashing a String.
  private val keywordsByFirstChar: Array[List[Keyword]] =
    val all = keywordTokens.toList.map((w, mk) => Keyword(w, mk(Span(0, 0)).kind, mk))
    val grouped = all.groupBy(_.spelling.charAt(0))
    Array.tabulate(128)(c => grouped.getOrElse(c.toChar, Nil))

  /** The keyword spelled by `content[start, end)`, if any — without allocating the substring. */
  def keywordAt(content: String, start: Int, end: Int): Option[Keyword] =
    val first = content.charAt(start)
    val len = end - start
    if first >= 128 then None
    else
      keywordsByFirstChar(first).find(k => k.spelling.length == len && content.regionMatches(start, k.spelling, 0, len))

  /** Token kinds that, when appearing at the end of a logical line, suppress
    * the next physical newline (trailing line-continuation per
    * `spec/syntax_grammar.ebnf` lines 186–195).
    */
  val trailingContinuationKinds: KindSet = KindSet(
    TokenKind.Plus,
    TokenKind.Minus,
    TokenKind.Star,
    TokenKind.Slash,
    TokenKind.Percent,
    TokenKind.AndAnd,
    TokenKind.OrOr,
    TokenKind.EqEq,
    TokenKind.Neq,
    TokenKind.Lt,
    TokenKind.Gt,
    TokenKind.Le,
    TokenKind.Ge,
    TokenKind.PipeForward,
    TokenKind.Arrow,
    TokenKind.FatArrow,
    TokenKind.Eq,
    TokenKind.BackArrow,
    TokenKind.Dot,
    TokenKind.KwIs,
    TokenKind.KwExtends,
    TokenKind.KwImplies
  )

  /** Token kinds that, when appearing at the *start* of a physical line,
    * cause that line to be treated as a continuation of the previous
    * logical line.
    *
    * `Dot`, `PipeForward`, and `Plus` are also in
    * `trailingContinuationKinds` so chained calls / pipelines /
    * arithmetic sums can be split with the operator at either end-of-
    * line or (more idiomatically) start-of-line. (Cap composition
    * `Functor + Folding` only appears in `is`-bound and `extends`
    * positions, so leading `+` in expression context is unambiguous.)
    */
  val leadingContinuationKinds: KindSet = KindSet(
    TokenKind.KwWith,
    TokenKind.KwExtends,
    TokenKind.Arrow,
    TokenKind.Dot,
    TokenKind.PipeForward,
    TokenKind.Plus
  )

end Tokens
