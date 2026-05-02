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
  case LowerIdent       // [a-z_][a-zA-Z_0-9]*
  case UpperIdent       // [A-Z][a-zA-Z_0-9]*

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
  case Eq                // =
  case Arrow             // ->
  case FatArrow          // =>
  case BackArrow         // <- (bind in `do:`)
  case Pipe              // |   (or-pattern separator)
  case PipeForward       // |>
  case Plus, Minus, Star, Slash, Percent
  case Lt, Le, Gt, Ge, EqEq, Neq
  case AndAnd, OrOr
  case Bang
  case Underscore
  case At                // @ — reserved; not yet used

  // ---- Synthetic (scanner-emitted, not literal source text) ----
  case Newline           // logical newline (between same-indent lines)
  case Indent            // entering a more-indented block
  case Dedent            // closing one indent level
  case Eof
  case Error             // lex error — accompanied by a Reporter entry
  case NoStart           // sentinel: scanner has not yet emitted a token

end TokenKind

/** A single token: kind, source span, and (for ident/literal kinds) the
  * raw lexeme as it appeared in source. Punctuation/operator/keyword
  * tokens have an empty lexeme; consumers should use the kind directly.
  */
final case class Token(kind: TokenKind, span: Span, lexeme: String = "")

object Tokens:

  /** Mapping from keyword text to its TokenKind. Identifier scanning hits
    * this table to decide if a `LowerIdent`/`UpperIdent` is actually a
    * keyword.
    */
  val keywords: Map[String, TokenKind] = Map(
    "as"          -> TokenKind.KwAs,
    "cap"         -> TokenKind.KwCap,
    "data"        -> TokenKind.KwData,
    "do"          -> TokenKind.KwDo,
    "effect"      -> TokenKind.KwEffect,
    "else"        -> TokenKind.KwElse,
    "extends"     -> TokenKind.KwExtends,
    "false"       -> TokenKind.KwFalse,
    "fn"          -> TokenKind.KwFn,
    "forall"      -> TokenKind.KwForall,
    "handle"      -> TokenKind.KwHandle,
    "if"          -> TokenKind.KwIf,
    "implies"     -> TokenKind.KwImplies,
    "impossible"  -> TokenKind.KwImpossible,
    "in"          -> TokenKind.KwIn,
    "is"          -> TokenKind.KwIs,
    "let"         -> TokenKind.KwLet,
    "linear"      -> TokenKind.KwLinear,
    "match"       -> TokenKind.KwMatch,
    "mod"         -> TokenKind.KwMod,
    "of"          -> TokenKind.KwOf,
    "Part"        -> TokenKind.KwPart,
    "prop"        -> TokenKind.KwProp,
    "pub"         -> TokenKind.KwPub,
    "resume"      -> TokenKind.KwResume,
    "return"      -> TokenKind.KwReturn,
    "satisfies"   -> TokenKind.KwSatisfies,
    "Self"        -> TokenKind.KwSelf,
    "self"        -> TokenKind.KwSelfLower,
    "suchThat"    -> TokenKind.KwSuchThat,
    "true"        -> TokenKind.KwTrue,
    "type"        -> TokenKind.KwType,
    "unreachable" -> TokenKind.KwUnreachable,
    "use"         -> TokenKind.KwUse,
    "with"        -> TokenKind.KwWith
  )

  /** Token kinds that, when appearing at the end of a logical line, suppress
    * the next physical newline (trailing line-continuation per
    * `spec/syntax_grammar.ebnf` lines 186–195).
    */
  val trailingContinuationKinds: Set[TokenKind] = Set(
    TokenKind.Plus,         TokenKind.Minus,    TokenKind.Star,
    TokenKind.Slash,        TokenKind.Percent,  TokenKind.AndAnd,
    TokenKind.OrOr,         TokenKind.EqEq,     TokenKind.Neq,
    TokenKind.Lt,           TokenKind.Gt,       TokenKind.Le,
    TokenKind.Ge,           TokenKind.PipeForward,
    TokenKind.Arrow,        TokenKind.FatArrow, TokenKind.Eq,
    TokenKind.BackArrow,    TokenKind.Dot,      TokenKind.KwIs,
    TokenKind.KwExtends,    TokenKind.KwImplies
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
  val leadingContinuationKinds: Set[TokenKind] = Set(
    TokenKind.KwWith,
    TokenKind.KwExtends,
    TokenKind.Arrow,
    TokenKind.Dot,
    TokenKind.PipeForward,
    TokenKind.Plus
  )

end Tokens
