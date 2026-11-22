package fixed.prover

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.core.Phase
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** What the static prover made of one prop: its qualified name, where it is declared, the result, the key of its line
  * in `props.lock`, and whether the result was read from there instead of proved. A prop outside the fragment is
  * `Open` with the reason and no step; a `Refuted` one is `E102`.
  */
final case class PropVerdict(name: String, span: Span, result: Result, key: String, recorded: Boolean)

/** The static prover's place in the package compile (`spec/properties.md` Rule P7.1, step 1): right after the typer,
  * every prop of the unit whose body lies in the fragment goes to the prover, unless `props.lock` records a result for
  * its key, the prover's version and the budget (Rule P7.1.b).
  *
  * The fragment: a body that is a chain of `forall`s over binders of type `i8`…`i64`, `u8`…`u64` or `bool`, each with
  * an optional `suchThat`, around an expression built from those binders, integer and `bool` literals, `+`, `-`, `*`
  * (one side constant), unary `-` on a signed type, comparisons, `&&`, `||`, `!`, `implies`. The integers are
  * mathematical; every `+`, `-`, `*` and negation must stay in the range of its type wherever it is evaluated (Rule
  * P4.2): `&&` and `||` evaluate their right operand only when it decides, `implies` evaluates both (Rule P6.2), and the
  * body only under its `suchThat`. The typer does not type prop bodies yet, so this phase checks the types it relies on:
  * the operands of an operation or comparison have one type, a literal fits it, and a comparison of literals alone is
  * outside the fragment. Anything else (`Self`, a field, a call, `match`, `/`) leaves the prop open.
  */
object Props extends Phase:
  val name: String = "props"

  def run(unit: CompilationUnit): CompilationUnit =
    val lock = PropsLock.recorded(unit)
    val checked = unit.tree.toList.flatMap(t => declared(items(t))).map { (n, p) =>
      val ob = obligation(p.body)
      val key = ob.fold(_ => "-", o => PropsLock.key(o._1))
      val known = lock.get(n).collect { case (`key`, r) => r }
      val result = ob.fold(Result.Open(_, 0), (o, _) => known.getOrElse(Prover.prove(o, unit.proverSteps)))
      (PropVerdict(n, p.span, result, key, ob.isRight && known.isDefined), ob)
    }
    unit.copy(props = checked.map(_._1)).report(checked.flatMap(failure))

  /** `body` as an obligation, paired with its range goals alone (each operation evaluated stays in its type's range);
    * `Left` with the reason outside the fragment.
    */
  def obligation(body: Tree): Either[String, (Obligation, Formula)] =
    prop(body, Nil).map((vars, full, ranges) => (Obligation(vars, Nil, full), ranges))

  private def items(t: Tree): List[Tree] = t match
    case Trees.CompilationUnit(items, _) => items
    case _                               => Nil

  /** The props declared in `items`, with names qualified by their owner. */
  private def declared(items: List[Tree]): List[(String, Trees.PropDecl)] =
    def props(owner: String, members: List[Tree]) = members.collect { case p: Trees.PropDecl =>
      s"$owner.${p.name}" -> p
    }
    items.flatMap {
      case d: Trees.DataDecl      => props(d.name, d.props)
      case c: Trees.CapDecl       => props(c.name, c.body)
      case s: Trees.SatisfiesDecl => props(s"${s.typeName}.${capName(s.capRef)}", s.mappings)
      case f: Trees.FnDecl => props(f.name, f.body.toList.flatMap { case Trees.Block(ss, _) => ss; case b => List(b) })
      case _               => Nil
    }

  private def capName(t: Tree): String = t match
    case r: Trees.NamedRef => r.name
    case _                 => "?"

  /** `E102` for a refuted prop, with its counterexample (Rule P8.4), and whether an operation leaves its range. */
  private def failure(v: PropVerdict, ob: Either[String, (Obligation, Formula)]): Option[Diagnostic] =
    (v.result, ob) match
      case (Result.Refuted(values, _), Right((o, ranges))) =>
        val shown = o.vars.map((n, t) => s"$n = ${if t == VarType.Bool then values(n) == 1 else values(n)}")
        val why = if Obligation.eval(ranges, values) then "" else " (an integer operation leaves the range of its type)"
        val msg = s"prop failed: ${v.name}; counterexample: ${shown.mkString(", ")}$why"
        Some(Diagnostic(Severity.Error, DiagCode.E102, v.span, msg))
      case _ => None

  /** The binders in scope, the obligation's goal, and its range goals alone. */
  private type Prop = (List[(String, VarType)], Formula, Formula)

  private def prop(e: Tree, scope: List[(String, VarType)]): Either[String, Prop] = e match
    case Trees.ForallExpr(binders, suchThat, body, _) =>
      for
        bound <- Prover.all(binders.map(binder))
        inner = scope ++ bound
        (vars, full, ranges) <- prop(body, inner)
        guarded <- suchThat.fold(Right((vars, full, ranges))) { c =>
          cond(c, inner).map { g =>
            (
              vars,
              Formula.And(g.wd, Formula.Implies(g.value, full)),
              Formula.And(g.wd, Formula.Implies(g.value, ranges))
            )
          }
        }
      yield guarded
    case _ => cond(e, scope).map(c => (scope, Formula.And(c.wd, c.value), c.wd))

  private def binder(p: Trees.FnParam): Either[String, (String, VarType)] = p match
    case Trees.FnParam(n, Trees.PrimitiveType(t, _), None, _) => VarType.named(t).map(n -> _).toRight(s"binder $n: $t")
    case Trees.FnParam(n, _, _, _)                            => Left(s"binder $n of a type outside the fragment")

  /** A `bool` expression: its value, and the condition that every operation it evaluates stays in range. */
  final private case class Cond(value: Formula, wd: Formula)

  /** An integer expression: its value, and the condition that every operation it evaluates stays in range. */
  final private case class Num(value: Term, wd: Formula)

  private val ok = Formula.Lit(true)

  private def cond(e: Tree, scope: List[(String, VarType)]): Either[String, Cond] =
    import Trees.BinOperator as B
    def join(a: Tree, b: Tree)(value: (Formula, Formula) => Formula)(wd: (Cond, Cond) => Formula) =
      for x <- cond(a, scope); y <- cond(b, scope) yield Cond(value(x.value, y.value), wd(x, y))
    def eager(x: Cond, y: Cond) = Formula.And(x.wd, y.wd)
    def lazily(when: Formula => Formula)(x: Cond, y: Cond) = Formula.And(x.wd, Formula.Implies(when(x.value), y.wd))

    e match
      case Trees.BoolLit(b, _)                                    => Right(Cond(Formula.Lit(b), ok))
      case Trees.Ident(n, _) if scope.contains(n -> VarType.Bool) => Right(Cond(Formula.BoolVar(n), ok))
      case Trees.UnaryOp(Trees.UnaryOperator.Not, a, _) => cond(a, scope).map(c => Cond(Formula.Not(c.value), c.wd))
      case Trees.BinOp(B.And, a, b, _)                  => join(a, b)(Formula.And(_, _))(lazily(identity))
      case Trees.BinOp(B.Or, a, b, _)                   => join(a, b)(Formula.Or(_, _))(lazily(Formula.Not(_)))
      case Trees.Implies(a, b, _)                       => join(a, b)(Formula.Implies(_, _))(eager)
      case Trees.BinOp(op @ (B.Eq | B.Neq), a, b, _) if isBool(a, scope) || isBool(b, scope) =>
        join(a, b)((p, q) => if op == B.Eq then Formula.Iff(p, q) else Formula.Not(Formula.Iff(p, q)))(eager)
      case Trees.BinOp(op, a, b, _) if comparison.contains(op) =>
        for
          t <- unify(intType(a, scope), intType(b, scope))
          typ <- t.toRight("a comparison of literals alone")
          x <- num(a, typ, scope)
          y <- num(b, typ, scope)
        yield Cond(Formula.Cmp(comparison(op), x.value, y.value), Formula.And(x.wd, y.wd))
      case _ => Left(outside(e))

  private def outside(e: Tree): String = e match
    case Trees.Ident(n, _) => s"`$n` is outside the fragment"
    case _                 => s"${e.getClass.getSimpleName} is outside the fragment"

  private val comparison: Map[Trees.BinOperator, CmpOp] =
    import Trees.BinOperator as B
    Map(B.Eq -> CmpOp.Eq, B.Neq -> CmpOp.Neq, B.Lt -> CmpOp.Lt, B.Le -> CmpOp.Le, B.Gt -> CmpOp.Gt, B.Ge -> CmpOp.Ge)

  private def isBool(e: Tree, scope: List[(String, VarType)]): Boolean = e match
    case _: Trees.BoolLit | _: Trees.Implies => true
    case Trees.Ident(n, _)                   => scope.contains(n -> VarType.Bool)
    case Trees.UnaryOp(op, _, _)             => op == Trees.UnaryOperator.Not
    case Trees.BinOp(op, _, _, _)            =>
      op == Trees.BinOperator.And || op == Trees.BinOperator.Or || comparison.contains(op)
    case _ => false

  /** The integer type of `e` where a binder fixes it, `None` for literals alone. */
  private def intType(e: Tree, scope: List[(String, VarType)]): Either[String, Option[VarType]] = e match
    case Trees.Ident(n, _) =>
      scope.collectFirst { case (`n`, t) if t != VarType.Bool => t }.map(Some(_)).toRight(outside(e))
    case Trees.BinOp(_, a, b, _) => unify(intType(a, scope), intType(b, scope))
    case Trees.UnaryOp(_, a, _)  => intType(a, scope)
    case _                       => Right(None)

  private def unify(
      a: Either[String, Option[VarType]],
      b: Either[String, Option[VarType]]
  ): Either[String, Option[VarType]] =
    for
      x <- a
      y <- b
      t <- (x, y) match
        case (Some(s), Some(t)) if s != t => Left(s"operands of types ${s.spelling} and ${t.spelling}")
        case _                            => Right(x.orElse(y))
    yield t

  private def num(e: Tree, t: VarType, scope: List[(String, VarType)]): Either[String, Num] =
    import Trees.BinOperator as B
    def literal(v: BigInt) =
      Either.cond(t.contains(v), Num(Term.Const(v), ok), s"literal $v does not fit ${t.spelling}")
    def inRange(v: Term) =
      Formula.And(Formula.Cmp(CmpOp.Ge, v, Term.Const(t.lo)), Formula.Cmp(CmpOp.Le, v, Term.Const(t.hi)))

    /** An operation on constants alone is evaluated by the compiler and never overflows at run time (Rule E3.6.d,
      * item 1); one that does not fit is `T025`, which leaves the prop open here.
      */
    def op(a: Tree, b: Tree)(f: (Term, Term) => Term) =
      for
        x <- num(a, t, scope)
        y <- num(b, t, scope)
        v = f(x.value, y.value)
        r <- Obligation.constant(v).fold(Right(Num(v, Formula.And(Formula.And(x.wd, y.wd), inRange(v)))))(literal)
      yield r
    e match
      case Trees.IntLit(v, _)                                            => literal(v)
      case Trees.UnaryOp(Trees.UnaryOperator.Neg, Trees.IntLit(v, _), _) => literal(-v)
      case Trees.Ident(n, _) if scope.contains(n -> t)                   => Right(Num(Term.Var(n), ok))
      case Trees.BinOp(B.Add, a, b, _)                                   => op(a, b)(Term.Add(_, _))
      case Trees.BinOp(B.Sub, a, b, _)                                   => op(a, b)(Term.Sub(_, _))
      case Trees.BinOp(B.Mul, a, b, _)                                   => op(a, b)(Term.Mul(_, _))
      case Trees.UnaryOp(Trees.UnaryOperator.Neg, a, _) if t.lo < 0      =>
        op(Trees.IntLit(0, e.span), a)(Term.Sub(_, _))
      case _ => Left(outside(e))
