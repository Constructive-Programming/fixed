package fixed.core

/** The expected type of an expression being typed (plan §3.4); call and member prototypes join at M5. */
enum Proto:
  case ExpectedType(t: Type)
  case Wildcard

  /** The expected type, if any. */
  def expected: Option[Type] =
    this match
      case ExpectedType(t) => Some(t)
      case Wildcard        => None

  /** The expected type, else `inferred`: what an expression checked against this prototype has. */
  def orElse(inferred: => Type): Type = expected.getOrElse(inferred)

  /** This prototype, or `fallback` as the expectation when there is none. */
  def orExpect(fallback: => Option[Type]): Proto = expected.orElse(fallback).fold(Wildcard)(ExpectedType(_))
