package fixed.backend

import fixed.ast.{Tree, Trees}
import fixed.core.Type
import fixed.typer.Patterns

/** `match` (`spec/pattern_matching.md` §3–§4): the cases are tried in order, and a case is taken
  * when its pattern matches. A literal matches a value equal to it by the type's equality
  * ([[equalValues]]); a variant pattern on a data value is decided by a `Switch` on the variant,
  * whose branch tries the cases that cover that variant in order.
  */
private[backend] trait Cases extends Tables:
  this: Lower =>

  /** `match` on a data value: one `Switch` branch per variant, which tries the cases covering it;
    * on any other value, the cases in order (an `If` on `true` joins their results into one value).
    */
  private[backend] def matchExpr(m: Trees.MatchExpr, sc: Scope)(k: K): Expr =
    val t = typeOf(m.scrutinee, sc)
    val rt = ctype(typeOf(m, sc), m.span)
    if m.cases.exists(_.guard.isDefined) then unsupported(m.span, "`match` with guards", k)
    else
      expr(m.scrutinee, sc) { s =>
        dataOf(t) match
          case Some((data, dargs)) =>
            val branches = symbols.variantsOf(data).toList.zipWithIndex.map { (v, tag) =>
              val variant = fieldTypes(data, dargs, v.id)
              tag -> tryCases(m.cases.filter(c => Lower.covers(c.pattern, v.name)), s, t, variant, rt, sc)
            }
            bind(Rhs.Switch(s, branches), m, sc)(k)
          case None =>
            bind(Rhs.If(Atom.BoolC(true), tryCases(m.cases, s, t, Nil, rt, sc), Expr.Ret(Atom.UnitC)), m, sc)(k)
      }

  /** Take the first of `cases` whose pattern matches `s`, of type `t`; `variant` holds the field
    * types of the variant a variant pattern meets. The `Nil` arm follows the last case, which the
    * typer's exhaustiveness check makes unreachable.
    */
  private def tryCases(
      cases: List[Trees.MatchCase],
      s: Atom,
      t: Type,
      variant: List[Type],
      rt: CType,
      sc: Scope
  ): Expr =
    cases match
      case Nil       => Expr.Ret(Atom.UnitC)
      case c :: rest =>
        val (test, body) = c.pattern match
          case Trees.DataVariantPat(_, _, pats, _) =>
            val parts = pats.getOrElse(Nil).zip(variant).zip(Layout.slots(variant.map(ctype(_, c.span))))
            val bound = parts.map { case ((p, ft), _) => tested(p) -> ft }
            (
              allOf(parts.map { case ((p, ft), slot) => field(s, p, ft, slot, sc) }),
              () => fields(s, bound, parts.map(_._2), sc)(tail(c.body, _))
            )
          case p => (matches(p, s, t, sc), () => bindPattern(tested(p), s, t, sc)(tail(c.body, _)))
        test {
          case Atom.BoolC(true) => body()
          case flag             =>
            val out = fresh("case", rt)
            Expr.Let(out, Rhs.If(flag, body(), tryCases(rest, s, t, variant, rt, sc)), Expr.Ret(out))
        }

  /** `p` with its literals, which [[matches]] has tested, replaced by `_` for [[bindPattern]]. */
  private def tested(p: Tree): Tree =
    p match
      case Trees.LiteralPat(_, span) => Trees.WildcardPat(span)
      case Trees.TuplePat(ps, span)  => Trees.TuplePat(ps.map(tested), span)
      case other                     => other

  /** `k` given whether `a`, of type `t`, equals the literals of `p` at its top or in its tuples:
    * `true` without a test where there are none. A literal under an or-pattern or a nested variant
    * pattern is not tested here: [[bindPattern]] reports those patterns as `B005`.
    */
  private def matches(p: Tree, a: Atom, t: Type, sc: Scope)(k: K): Expr =
    p match
      case Trees.LiteralPat(l: Trees.FloatLit, _) => unsupported(l.span, "a float literal pattern", k)
      case Trees.LiteralPat(l, _)                 => expr(l, sc)(v => equalValues(t, a, v)(k))
      case Trees.TuplePat(ps, _)                  =>
        val types = dataOf(t).fold(Nil)(_._2)
        val slots = Layout.slots(types.map(ctype(_, p.span)))
        allOf(ps.zip(types).zip(slots).map { case ((q, ft), slot) => field(a, q, ft, slot, sc) })(k)
      case _ => k(Atom.BoolC(true))

  /** [[matches]] for the field at `slot` of `obj`, of type `ft`, met by pattern `p`; loaded only if `p` holds a literal. */
  private def field(obj: Atom, p: Tree, ft: Type, slot: Int, sc: Scope)(k: K): Expr =
    if !Patterns.hasLiteral(p) then k(Atom.BoolC(true))
    else loadField(obj, slot, ft, p)(matches(p, _, ft, sc)(k))

  /** `body` given the field at `slot` of `obj`, of type `ft`, loaded for pattern `p`. */
  private[backend] def loadField(obj: Atom, slot: Int, ft: Type, p: Tree)(body: Atom.Var => Expr): Expr =
    val x = typed(fresh("f", ctype(ft, p.span)), ft, p.span)
    Expr.Let(x, Rhs.Field(obj, slot), body(x))

  /** `k` given the conjunction of `tests`, each tried only if those before it held. */
  private def allOf(tests: List[K => Expr])(k: K): Expr =
    tests match
      case Nil          => k(Atom.BoolC(true))
      case test :: rest =>
        test {
          case Atom.BoolC(true) => allOf(rest)(k)
          case flag             =>
            val r = fresh("all", CType.Bool)
            Expr.Let(r, Rhs.If(flag, allOf(rest)(Expr.Ret(_)), Expr.Ret(Atom.BoolC(false))), k(r))
        }
