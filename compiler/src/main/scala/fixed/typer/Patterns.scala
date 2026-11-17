package fixed.typer

import fixed.ast.Trees
import fixed.core.{Context, Proto, SymKind, Type}
import fixed.util.DiagCode

/** Pattern binding and `match` typing (`spec/pattern_matching.md` §3–§6). Exhaustiveness is the v1
  * top-level check (plan §5.5.1): every variant of a data scrutinee is named by an unguarded case,
  * `true` and `false` both appear for `bool`, otherwise a catch-all case is required.
  */
object Patterns:

  /** Bind `pat` against the scrutinee type `t`: the state and the context extended by its binders. */
  def bind(s: TyperState, pat: fixed.ast.Tree, t: Type)(using ctx: Context): (TyperState, Context) =
    pat match
      case Trees.WildcardPat(_)        => (s.record(pat, t), ctx)
      case Trees.BinderPat(name, span) =>
        val (table, id) = s.symbols.enter(name, ctx.owner, SymKind.Local, t, span)
        (s.copy(symbols = table).record(pat, t), ctx.bind(name, id))
      case Trees.LiteralPat(value, _)  => (Typer.typedExpr(s, value, Proto.ExpectedType(t))._1.record(pat, t), ctx)
      case Trees.TuplePat(elems, span) =>
        val (s1, vars) = s.freshVars(elems.size)
        val tuple = Type.TupleType(vars)
        val s2 = Typer.unify(s1, span, tuple, t).record(pat, tuple)
        bindAll(s2, elems.zip(vars))
      case p: Trees.DataVariantPat                               => variant(s, p, t)
      case Trees.StructDestructurePat(typeName, fields, _, span) =>
        ctx.scope.get(typeName).filter(s.symbols(_).kind == SymKind.Data) match
          case None       => (s.error(DiagCode.T001, span, s"not found: data $typeName"), ctx)
          case Some(data) =>
            val (s1, args) = Members.freshArgs(s, data)
            val s2 = Typer.unify(s1, span, t, Type.TypeRef(data, args))
            val variants = s2.symbols.variantsOf(data)
            val known = variants.headOption.toList.flatMap(v => s2.symbols.fieldsOf(v.id))
            val (s3, types) =
              variants.headOption.fold((s2, List.empty[Type]))(v => Members.fieldTypes(s2, data, args, v.id))
            val byName = known.map(_.name).zip(types).toMap
            fields.foldLeft((s3.record(pat, t), ctx)) { case ((si, ci), (name, p)) =>
              byName.get(name) match
                case Some(ft) => bind(si, p, ft)(using ci)
                case None     => (si.error(DiagCode.T001, p.span, s"not found: field $name of $typeName"), ci)
            }
      case Trees.OrPat(left, right, _) =>
        /** ponytail: both alternatives must bind the same names (Rule M5.5.b); not checked yet. */
        val (s1, c1) = bind(s, left, t)
        (bind(s1, right, t)._1.record(pat, t), c1)
      case Trees.GuardedPat(p, cond, _) =>
        val (s1, c1) = bind(s, p, t)
        (Typer.typedExpr(s1, cond, Proto.ExpectedType(Typer.bool))(using c1)._1, c1)
      case _: Trees.Error | _: Trees.Missing => (s, ctx)
      case other                             => (s.unsupported(other.span, "this pattern"), ctx)

  private def bindAll(s: TyperState, pairs: List[(fixed.ast.Tree, Type)])(using ctx: Context): (TyperState, Context) =
    pairs.foldLeft((s, ctx)) { case ((si, ci), (p, ty)) => bind(si, p, ty)(using ci) }

  /** `D.V(p1, ...)` / `V(...)` / `D.V`: the variant's fields bind against its instantiated field types. */
  private def variant(s: TyperState, p: Trees.DataVariantPat, t: Type)(using ctx: Context): (TyperState, Context) =
    val fromScrutinee = s.unifier.resolve(t) match
      case Type.TypeRef(id, _) if s.symbols(id).kind == SymKind.Data => Some(id)
      case _                                                         => None
    val data = p.qualifier.flatMap(ctx.scope.get).filter(s.symbols(_).kind == SymKind.Data).orElse(fromScrutinee)
    data.flatMap(d =>
      s.symbols.variantsOf(d).find(_.name == p.variant).map(d -> _)
    ) match
      case None =>
        (s.error(DiagCode.T001, p.span, s"not found: variant ${p.qualifier.fold("")(_ + ".")}${p.variant}"), ctx)
      case Some((d, v)) =>
        val (s1, args) = Members.freshArgs(s, d)
        val s2 = Typer.unify(s1, p.span, t, Type.TypeRef(d, args)).record(p, t)
        val (s3, fieldTypes) = Members.fieldTypes(s2, d, args, v.id)
        val pats = p.fields.getOrElse(Nil)

        /** On an arity mismatch, extra sub-patterns still bind (to fresh types) so their names resolve. */
        val (s4, extra) = s3.freshVars((pats.size - fieldTypes.size).max(0))
        val s5 =
          if p.fields.isDefined && pats.size != fieldTypes.size then
            s4.error(DiagCode.T006, p.span, s"${v.name} has ${fieldTypes.size} field(s), the pattern ${pats.size}")
          else s4
        bindAll(s5, pats.zip(fieldTypes ++ extra))

  /** `match scrutinee: cases` — each case binds its pattern, checks its guard, and its body meets
    * the prototype (or the first body's type).
    */
  def typedMatch(s: TyperState, m: Trees.MatchExpr, pt: Proto)(using ctx: Context): (TyperState, Type) =
    val (s1, scrutinee) = Typer.typedExpr(s, m.scrutinee, Proto.Wildcard)
    val (s2, result) = m.cases.foldLeft((s1, Option.empty[Type])) { case ((si, first), c) =>
      val (si1, ci) = bind(si, c.pattern, scrutinee)
      val si2 = c.guard.fold(si1)(g => Typer.typedExpr(si1, g, Proto.ExpectedType(Typer.bool))(using ci)._1)
      val expect = pt.orExpect(first)
      val (si3, bodyType) = Typer.typedExpr(si2, c.body, expect)(using ci)
      (si3.record(c, bodyType), first.orElse(Some(bodyType)))
    }

    /** Bodies were checked against an expected type already; report each mismatch once. */
    val typed = pt.orElse(result.getOrElse(Type.NeverType))
    (exhaustive(s2, m, scrutinee), typed)

  private def exhaustive(s: TyperState, m: Trees.MatchExpr, scrutinee: Type): TyperState =
    val unguarded = m.cases.filter(_.guard.isEmpty).map(_.pattern)
    val alternatives = unguarded.flatMap(flatten)
    val catchAll = alternatives.exists {
      case _: Trees.WildcardPat | _: Trees.BinderPat => true
      case _                                         => false
    }
    lazy val named = alternatives.collect { case Trees.DataVariantPat(_, v, _, _) => v }.toSet
    s.unifier.resolve(scrutinee) match
      case _ if catchAll                                           => s
      case Type.TypeRef(d, _) if s.symbols(d).kind == SymKind.Data =>
        val missing = s.symbols.variantsOf(d).filterNot(v => named(v.name))
        if missing.isEmpty then s
        else
          s.error(
            DiagCode.T020,
            m.span,
            missing.map(v => s"${s.symbols(d).name}.${v.name}").mkString("missing case ", ", ", "")
          )
      case Type.PrimType("bool") =>
        val lits = alternatives.collect { case Trees.LiteralPat(Trees.BoolLit(b, _), _) => b }.toSet
        if lits == Set(true, false) then s
        else s.error(DiagCode.T020, m.span, "missing case: a bool match needs true and false")
      case _: Type.TypeVar | _: Type.IsBound | _: Type.CapRef | Type.NeverType => s
      case _ => s.error(DiagCode.T020, m.span, "missing case _: this scrutinee type needs a catch-all case")

  private def flatten(p: fixed.ast.Tree): List[fixed.ast.Tree] =
    p match
      case Trees.OrPat(l, r, _) => flatten(l) ++ flatten(r)
      case other                => List(other)

  /** Type a nested `fn` declaration met in a block: signature via the Namer, then its body. */
  def nestedFn(s: TyperState, fn: Trees.FnDecl)(using ctx: Context): (TyperState, Context) =
    val (table, id, diags) = Namer.nestedFn(s.symbols, ctx.scope, ctx.owner, fn)
    val s1 = s.copy(symbols = table).report(diags)
    val inner = ctx.bind(fn.name, id)
    (Typer.typedFnBody(s1, id, fn)(using inner), inner)
