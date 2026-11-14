package fixed.core

/** The expected type of an expression being typed (plan §3.4); call and member prototypes join at M5. */
enum Proto:
  case ExpectedType(t: Type)
  case Wildcard
