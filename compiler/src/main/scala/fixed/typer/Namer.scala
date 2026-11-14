package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.*
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** The signature pass (`docs/plans/phase-3-typer.md` §4.2, §5.1). Enters one symbol per top-level
  * declaration, per member (variant, method, effect operation), per parameter and per type
  * variable, and types every signature. Bodies are never visited.
  *
  * Pass 1 enters the prelude, the imports and every top-level name, so signatures may refer to
  * declarations in any order; pass 2 types each signature against that scope. Type variables
  * follow `spec/type_system.md` Rule 4.1: an unbound `UPPER_IDENT` in a signature introduces one,
  * scoped to that signature (or to the whole `cap` / `data` / `effect` body for `of` parameters).
  */
object Namer:

  /** Standard-library names in scope everywhere (`spec/stdlib.md` §2, §5; `spec/effects.md` §2). */
  val prelude: List[(String, SymKind)] =
    List("String", "List", "Json", "Tuple", "Nat").map(_ -> SymKind.Data) ++
      List(
        "Optional",
        "Result",
        "Pair",
        "Ordering",
        "Boolean",
        "Empty",
        "Eq",
        "Ord",
        "Numeric",
        "Show",
        "Clone",
        "Sequencing",
        "Folding",
        "Functor",
        "Filtering",
        "Sized"
      ).map(_ -> SymKind.Cap) :+
      ("Fail" -> SymKind.Effect)

  /** Index a compilation unit: its symbol table and the Namer's diagnostics, in source order. */
  def index(unit: Tree): (SymbolTable, List[Diagnostic]) =
    val items = unit match
      case Trees.CompilationUnit(items, _) => items
      case other                           => List(other)
    val withPrelude = prelude.foldLeft(Ctx.empty(SymbolId.Prelude)) { case (c, (name, kind)) =>
      val (c1, id) = c.enter(name, kind, Type.UnitType, Span.NoSpan)
      c1.bind(name, id)
    }
    val (entered, decls) = mapS(withPrelude.copy(owner = SymbolId.Root), items)(enterTopLevel)
    val typed = decls.flatten.foldLeft(entered) { case (c, (tree, id)) => typeDecl(c, tree, id) }
    (typed.table, typed.diags.reverse)

  /** Threaded Namer state. `table` and `diags` (newest first) are global; `scope`, `owner` and
    * `sigLocals` (type variables introduced by the signature being typed) are lexical.
    */
  final private case class Ctx(
      table: SymbolTable,
      diags: List[Diagnostic],
      scope: Map[String, SymbolId],
      owner: SymbolId,
      sigLocals: Set[String]
  ):
    def enter(name: String, kind: SymKind, info: Type, span: Span): (Ctx, SymbolId) =
      val (t, id) = table.enter(name, owner, kind, info, span)
      (copy(table = t), id)

    def bind(name: String, id: SymbolId): Ctx = copy(scope = scope.updated(name, id))

    def error(code: DiagCode, span: Span, message: String): Ctx =
      copy(diags = Diagnostic(Severity.Error, code, span, message) :: diags)

    /** Run `f` in a fresh lexical frame owned by `owner`; keep its symbols and diagnostics, drop its bindings. */
    def nested[A](owner: SymbolId)(f: Ctx => (Ctx, A)): (Ctx, A) =
      val (inner, a) = f(copy(owner = owner, sigLocals = Set.empty))
      (copy(table = inner.table, diags = inner.diags), a)

  private object Ctx:
    def empty(owner: SymbolId): Ctx = Ctx(SymbolTable.empty, Nil, Map.empty, owner, Set.empty)

  /** Map over `as`, threading the context. */
  private def mapS[A, B](c: Ctx, as: List[A])(f: (Ctx, A) => (Ctx, B)): (Ctx, List[B]) =
    val (out, acc) = as.foldLeft((c, List.empty[B])) { case ((c0, acc0), a) =>
      val (c1, b) = f(c0, a)
      (c1, b :: acc0)
    }
    (out, acc.reverse)

  /** Enter a top-level item's name(s); returns the declarations pass 2 must type. */
  private def enterTopLevel(c: Ctx, item: Tree): (Ctx, List[(Tree, SymbolId)]) =
    item match
      case Trees.UseDecl(path, selectors, None, span) =>
        val names = if selectors.nonEmpty then selectors else path.lastOption.toList
        val c1 = names.foldLeft(c) { (ci, name) =>
          val (ci1, id) = ci.enter(name, SymKind.Import, Type.UnitType, span)
          ci1.bind(name, id)
        }
        (c1, Nil)
      case t: Trees.FnDecl     => declare(c, t, t.name, SymKind.Fn)
      case t: Trees.CapDecl    => declare(c, t, t.name, SymKind.Cap)
      case t: Trees.DataDecl   => declare(c, t, t.name, SymKind.Data)
      case t: Trees.EffectDecl => declare(c, t, t.name, SymKind.Effect)
      case t: Trees.TypeAlias  => declare(c, t, t.name, SymKind.TypeAlias)

      /** ponytail: `satisfies` (M6) and `mod` declarations are not indexed yet. */
      case _ => (c, Nil)

  private def declare(c: Ctx, tree: Tree, name: String, kind: SymKind): (Ctx, List[(Tree, SymbolId)]) =
    val c1 = c.scope.get(name).filter(c.table(_).owner == SymbolId.Root) match
      case Some(_) => c.error(DiagCode.T003, tree.span, s"duplicate definition: $name")
      case None    => c
    val (c2, id) = c1.enter(name, kind, Type.UnitType, tree.span)
    (c2.bind(name, id), List(tree -> id))

  private def typeDecl(c: Ctx, tree: Tree, id: SymbolId): Ctx =
    val (c1, info) = c.nested(id) { ci =>
      tree match
        case t: Trees.FnDecl                         => fnSignature(ci, t)
        case t: Trees.CapDecl                        => capSignature(ci, t)
        case t: Trees.DataDecl                       => dataSignature(ci, t, id)
        case t: Trees.EffectDecl                     => effectSignature(ci, t, id)
        case Trees.TypeAlias(_, valueParams, rhs, _) =>
          val (ci1, _) = mapS(ci, valueParams)(valueParam)
          typeOf(ci1, rhs)
        case _ => (ci, Type.UnitType)
    }
    c1.copy(table = c1.table.refine(id, info))

  /** `(params) -> ret with fx`. Parameters become `ValueParam` symbols in scope for the rest of the
    * signature (refinement calls such as `between(lo, hi)` refer to them).
    */
  private def fnSignature(c: Ctx, fn: Trees.FnLike): (Ctx, Type) =
    val c0 = fn.typeParamsHint.foldLeft(c)((ci, name) => introduce(ci, name, Nil, fn.span)._1)
    val (c1, params) = mapS(c0, fn.params)(valueParam)
    val (c2, ret) = fn.returnType.fold((c1, Type.UnitType))(typeOf(c1, _))
    arrow(c2, product(params), ret, fn.withClause)

  /** `lhs -> rhs [with row]`. */
  private def arrow(c: Ctx, lhs: Type, rhs: Type, withClause: Option[Tree]): (Ctx, Type) =
    val (c1, fx) = withClause.fold((c, EffectRow.empty))(effects(c, _))
    (c1, Type.ArrowType(lhs, rhs, fx))

  private def valueParam(c: Ctx, p: Trees.FnParam): (Ctx, Type) =
    val (c1, t) = typeOf(c, p.typeAnn)
    val (c2, id) = c1.enter(p.name, SymKind.ValueParam, t, p.span)
    (c2.bind(p.name, id), t)

  /** A cap's info is its `extends` chain; its methods are entered as members. */
  private def capSignature(c: Ctx, cap: Trees.CapDecl): (Ctx, Type) =
    val (c1, _) = ofParams(c, cap.ofParams)
    val (c2, _) = mapS(c1, cap.valueParams)(valueParam)
    val (c3, supers) = mapS(c2, cap.extendsList)(capBound)
    val c4 = cap.body.foldLeft(c3) { (ci, item) =>
      item match
        case m: Trees.InstanceMethod => member(ci, m, SymKind.Method)
        case m: Trees.StaticMethod   => member(ci, m, SymKind.StaticMethod)
        case _                       => ci
    }
    (c4, Type.IsBound(supers.flatten))

  /** A data type's info is `TypeRef(self, typeParams)`; each variant is a constructor. */
  private def dataSignature(c: Ctx, data: Trees.DataDecl, id: SymbolId): (Ctx, Type) =
    val (c1, params) = ofParams(c, data.ofParams)
    val self = Type.TypeRef(id, params)
    val c2 = data.variants.foldLeft(c1) { (ci, v) =>
      val (ci1, vid) = ci.enter(v.name, SymKind.Variant, Type.UnitType, v.span)
      val (ci2, info) = ci1.nested(vid) { cv =>
        val (cv1, fields) = mapS(cv, v.fields)((cf, f) => typeOf(cf, f.typeAnn))
        (cv1, if fields.isEmpty then self else Type.ArrowType(product(fields), self, EffectRow.empty))
      }
      ci2.copy(table = ci2.table.refine(vid, info))
    }
    (c2, self)

  private def effectSignature(c: Ctx, eff: Trees.EffectDecl, id: SymbolId): (Ctx, Type) =
    val (c1, params) = ofParams(c, eff.ofParams)
    val c2 = eff.members.foldLeft(c1) { (ci, m) =>
      m match
        case op: Trees.FnLike => member(ci, op, SymKind.Operation)
        case _                => ci
    }
    (c2, Type.TypeRef(id, params))

  /** Enter a method or operation and type its signature in its own frame. */
  private def member(c: Ctx, fn: Trees.FnLike, kind: SymKind): Ctx =
    val (c1, id) = c.enter(fn.name, kind, Type.UnitType, fn.span)
    val (c2, info) = c1.nested(id)(fnSignature(_, fn))
    c2.copy(table = c2.table.refine(id, info))

  /** An `of (...)` declaration list: bare names and `N is C` introduce type variables, value
    * parameters become `ValueParam`s. Returns the type parameters, in order.
    */
  private def ofParams(c: Ctx, params: List[Tree]): (Ctx, List[Type]) =
    val (c1, ts) = mapS(c, params) { (ci, p) =>
      p match
        case fp: Trees.FnParam                                           => (valueParam(ci, fp)._1, None)
        case Trees.TypeRef(name, None, span) if !ci.scope.contains(name) =>
          val (ci1, id) = introduce(ci, name, Nil, span)
          (ci1, Some(Type.TypeRef(id, Nil)))
        case other =>
          val (ci1, t) = typeOf(ci, other)
          (ci1, Some(t))
    }
    (c1, ts.flatten)

  private def typeOf(c: Ctx, tree: Tree): (Ctx, Type) =
    tree match
      case Trees.PrimitiveType(name, _) => (c, Type.PrimType(name))
      case Trees.UnitType(_)            => (c, Type.UnitType)
      case Trees.NeverType(_)           => (c, Type.NeverType)
      case Trees.SelfType(of, _)        =>
        val (c1, args) = typeArgs(c, of)
        (c1, Type.SelfType(args.headOption))
      case Trees.TypeRef(name, ofArg, span) =>
        val (c1, args) = typeArgs(c, ofArg)
        c1.scope.get(name) match
          case Some(id)               => (c1, reference(c1, id, args))
          case None if name == "Part" => (c1, Type.PartType)
          case None                   =>
            val (c2, id) = introduce(c1, name, Nil, span)
            (c2, Type.TypeRef(id, args))
      case Trees.NamedAlias(name, caps, span) =>
        val (c1, bounds) = mapS(c, caps)(capBound)
        val (c2, id) = introduce(c1, name, bounds.flatten, span)
        (c2, Type.TypeRef(id, Nil))
      case Trees.IsBound(caps, _) =>
        val (c1, bounds) = mapS(c, caps)(capBound)
        (c1, Type.IsBound(bounds.flatten))
      case Trees.ArrowType(lhs, rhs, withClause, _) =>
        val (c1, l) = typeOf(c, lhs)
        val (c2, r) = typeOf(c1, rhs)
        arrow(c2, l, r, withClause)
      case Trees.TupleArrowLhs(types, _) =>
        val (c1, ts) = mapS(c, types)(typeOf)
        (c1, Type.TupleType(ts))
      case Trees.CapType(spec, _) =>
        spec match
          case None                               => (c, Type.CapType(None))
          case Some(Trees.CapReturnSpec.OfArg(a)) =>
            val (c1, t) = typeOf(c, a)
            (c1, Type.CapType(Some(t)))
          case Some(Trees.CapReturnSpec.Extends(caps)) =>
            val (c1, bounds) = mapS(c, caps)(capBound)
            (c1, Type.CapType(Some(Type.IsBound(bounds.flatten))))
      case Trees.ParenTypeApp(inner, ofArg, span) =>
        val (c1, t) = typeOf(c, inner)
        val (c2, args) = typeArgs(c1, Some(ofArg))
        t match
          case Type.TypeRef(id, Nil) => (c2, Type.TypeRef(id, args))
          case Type.CapRef(id, Nil)  => (c2, Type.CapRef(id, args))
          case _                     => (c2.error(DiagCode.T005, span, "type application to a non-name type"), t)
      case _: Trees.IntLit | _: Trees.FloatLit | _: Trees.StringLit | _: Trees.CharLit | _: Trees.BoolLit =>
        val (c1, term) = symbolicTerm(c, tree)
        (c1, term.fold(Type.NeverType)(Type.ValueArg(_)))

      /** Gap nodes were already reported by the parser. */
      case _: Trees.Error | _: Trees.Missing => (c, Type.NeverType)
      case other                             =>
        (c.error(DiagCode.T005, other.span, "not supported in type position yet"), Type.NeverType)

  /** `of A` / `of (A, B)` / `of ()` as an argument list. */
  private def typeArgs(c: Ctx, ofArg: Option[Tree]): (Ctx, List[Type]) =
    ofArg match
      case None                                => (c, Nil)
      case Some(Trees.TupleArrowLhs(types, _)) => mapS(c, types)(typeOf)
      case Some(t)                             =>
        val (c1, ty) = typeOf(c, t)
        (c1, List(ty))

  /** A resolved name: a cap becomes `CapRef`, anything else `TypeRef`. */
  private def reference(c: Ctx, id: SymbolId, args: List[Type]): Type =
    if c.table(id).kind == SymKind.Cap then Type.CapRef(id, args) else Type.TypeRef(id, args)

  /** One entry of an `is` / `extends` / alias chain: a capability, a refinement call, a primitive or
    * a named type. Returns `None` (after reporting) when unresolvable.
    */
  private def capBound(c: Ctx, tree: Tree): (Ctx, Option[Type]) =
    tree match
      case Trees.CapRef(name, ofArg, span) =>
        val (c1, args) = typeArgs(c, ofArg)
        resolve(c1, name, span, "capability")(reference(c1, _, args))
      case Trees.RefinementCall(name, args, span) =>
        val (c1, terms) = mapS(c, args)(symbolicTerm)
        resolve(c1, name, span, "capability generator")(Type.RefinementType(_, terms.flatten))
      case Trees.PrimitiveType(name, _) => (c, Some(Type.PrimType(name)))
      case other                        =>
        (c.error(DiagCode.T005, other.span, "not a capability bound"), None)

  /** A refinement-call argument as a symbolic term. */
  private def symbolicTerm(c: Ctx, tree: Tree): (Ctx, Option[SymbolicTerm]) =
    tree match
      case Trees.IntLit(v, _)                                            => (c, Some(SymbolicTerm.LitInt(v)))
      case Trees.UnaryOp(Trees.UnaryOperator.Neg, Trees.IntLit(v, _), _) => (c, Some(SymbolicTerm.LitInt(-v)))
      case Trees.FloatLit(v, _)                                          => (c, Some(SymbolicTerm.LitFloat(v)))
      case Trees.BoolLit(v, _)                                           => (c, Some(SymbolicTerm.LitBool(v)))
      case Trees.StringLit(v, _)                                         => (c, Some(SymbolicTerm.LitString(v)))
      case Trees.CharLit(v, _)                                           => (c, Some(SymbolicTerm.LitChar(v)))
      case Trees.Ident(name, span) => resolve(c, name, span, "value")(SymbolicTerm.BoundVar(_))
      case other                   =>
        (c.error(DiagCode.T005, other.span, "refinement argument must be a literal or a name"), None)

  /** A `with` clause: its effect row. */
  private def effects(c: Ctx, tree: Tree): (Ctx, EffectRow) =
    val bounds = tree match
      case Trees.WithClause(row, _) => effectList(row)
      case other                    => effectList(other)
    val (c1, resolved) = mapS(c, bounds) {
      case (ci, Trees.EffectBound(name, ofArg, span)) =>
        val (ci1, args) = typeArgs(ci, ofArg)
        resolve(ci1, name, span, "effect")(EffectBound(_, args))
      case (ci, other) => (ci.error(DiagCode.T005, other.span, "not an effect"), None)
    }
    (c1, EffectRow(resolved.flatten))

  private def effectList(row: Tree): List[Tree] =
    row match
      case Trees.EffectRow(effects, _) => effects
      case single                      => List(single)

  private def resolve[A](c: Ctx, name: String, span: Span, what: String)(f: SymbolId => A): (Ctx, Option[A]) =
    c.scope.get(name) match
      case Some(id) => (c, Some(f(id)))
      case None     => (c.error(DiagCode.T001, span, s"not found: $what $name"), None)

  /** Bind type variable `name` with `bounds`. Re-stating a variable of the current signature
    * (`-> N is positive()`) adds the bounds to it; re-binding an outer or global name is an error.
    */
  private def introduce(c: Ctx, name: String, bounds: List[Type], span: Span): (Ctx, SymbolId) =
    (c.scope.get(name), c.sigLocals(name)) match
      case (Some(id), true) =>
        val prev = c.table(id).info match
          case Type.IsBound(caps) => caps
          case _                  => Nil
        (c.copy(table = c.table.refine(id, Type.IsBound(prev ++ bounds))), id)
      case (bound, _) =>
        val c1 =
          if bound.isDefined then c.error(DiagCode.T004, span, s"$name is already bound in an enclosing scope") else c
        val (c2, id) = c1.enter(name, SymKind.TypeParam, Type.IsBound(bounds), span)
        (c2.bind(name, id).copy(sigLocals = c2.sigLocals + name), id)

  /** Parameter or field list as one arrow left-hand side. */
  private def product(ts: List[Type]): Type =
    ts match
      case Nil      => Type.UnitType
      case t :: Nil => t
      case _        => Type.TupleType(ts)
