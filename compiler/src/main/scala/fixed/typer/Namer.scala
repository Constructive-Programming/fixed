package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.*
import fixed.util.{DiagCode, Diagnostic, Severity, Span, mapAccum}

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

  /** Index a compilation unit against the stdlib: its symbol table (with the final top-level
    * scope) and the Namer's diagnostics, in source order. The prelude's declarations are in scope
    * everywhere; a stdlib module's (`std.io`) only through `use`.
    */
  def index(unit: Tree): (SymbolTable, List[Diagnostic]) =
    val items = unit match
      case Trees.CompilationUnit(items, _) => items
      case other                           => List(other)
    val (entered, decls) = mapAccum(stdlib.copy(owner = SymbolId.Root), items)(enterTopLevel)
    val typed = typeAll(entered, decls.flatten)
    (typed.table.copy(scope = typed.global), typed.diags.reverse)

  /** The stdlib, indexed once: prelude names bound, modules recorded for `use`. Pure, so shared. */
  private lazy val stdlib: Ctx =
    val (builtin, stringId) = Ctx.empty(SymbolId.Prelude).enter("String", SymKind.Data, Type.UnitType, Span.NoSpan)
    val withString = builtin.bind("String", stringId)
    val (preluded, preludeDecls) = mapAccum(withString, Stdlib.prelude)(enterTopLevel)
    val (withModules, moduleDecls) = mapAccum(preluded, Stdlib.modules.toList) { case (c, (module, decls)) =>
      val (c1, entered) = c.nested(SymbolId.Prelude)(mapAccum(_, decls)(enterTopLevel))
      val names = entered.flatten.collect { case (_, id) if id != SymbolId.Root => c1.table(id).name -> id }.toMap
      (c1.copy(modules = c1.modules.updated(module, names)), entered.flatten)
    }
    typeAll(withModules, preludeDecls.flatten ++ moduleDecls.flatten)

  /** Translate a type annotation met inside a body (a lambda parameter's) in `frames`; type
    * variables it introduces are owned by `owner`.
    */
  def translate(table: SymbolTable, frames: List[Map[String, SymbolId]], owner: SymbolId, tree: Tree)
      : (SymbolTable, Type, List[Diagnostic]) =
    val (c, t) = typeOf(Ctx.at(table, frames, owner), tree)
    (c.table, t, c.diags.reverse)

  /** Enter a `fn` declared inside a body and type its signature in `frames`. */
  def nestedFn(table: SymbolTable, frames: List[Map[String, SymbolId]], owner: SymbolId, fn: Trees.FnDecl)
      : (SymbolTable, SymbolId, List[Diagnostic]) =
    val (c, id) = member(Ctx.at(table, frames, owner), fn, SymKind.Fn)
    (c.table, id, c.diags.reverse)

  private def typeAll(c: Ctx, decls: List[(Tree, SymbolId)]): Ctx =
    decls.foldLeft(c) { case (ci, (tree, id)) => typeDecl(ci, tree, id) }

  /** Threaded Namer state. `table` and `diags` (newest first) are global; `frames`, `owner` and
    * `sigLocals` (type variables introduced by the signature being typed) are lexical. `frames` is
    * the scope innermost first: a signature binds into its own small frame instead of copying the
    * large global one.
    */
  final private case class Ctx(
      table: SymbolTable,
      diags: List[Diagnostic],
      frames: List[Map[String, SymbolId]],
      owner: SymbolId,
      sigLocals: Set[String],
      modules: Map[String, Map[String, SymbolId]]
  ):
    def enter(name: String, kind: SymKind, info: Type, span: Span): (Ctx, SymbolId) =
      val (t, id) = table.enter(name, owner, kind, info, span)
      (copy(table = t), id)

    def bind(name: String, id: SymbolId): Ctx = copy(frames = frames.head.updated(name, id) :: frames.tail)

    def lookup(name: String): Option[SymbolId] =
      @scala.annotation.tailrec
      def loop(fs: List[Map[String, SymbolId]]): Option[SymbolId] =
        if fs.isEmpty then None
        else
          val found = fs.head.get(name)
          if found.isDefined then found else loop(fs.tail)
      loop(frames)

    /** The outermost frame: the top-level scope. */
    def global: Map[String, SymbolId] = frames.last

    def error(code: DiagCode, span: Span, message: String): Ctx =
      copy(diags = Diagnostic(Severity.Error, code, span, message) :: diags)

    /** Run `f` in a fresh lexical frame owned by `owner`; keep its symbols and diagnostics, drop its bindings. */
    def nested[A](owner: SymbolId)(f: Ctx => (Ctx, A)): (Ctx, A) =
      val (inner, a) = f(copy(frames = Map.empty :: frames, owner = owner, sigLocals = Set.empty))
      (copy(table = inner.table, diags = inner.diags), a)

  private object Ctx:
    def empty(owner: SymbolId): Ctx = Ctx(SymbolTable.empty, Nil, List(Map.empty), owner, Set.empty, Map.empty)

    /** A frame over an existing table and scope, for signatures met inside bodies. */
    def at(table: SymbolTable, frames: List[Map[String, SymbolId]], owner: SymbolId): Ctx =
      Ctx(table, Nil, Map.empty :: frames, owner, Set.empty, Map.empty)

  /** Enter a top-level item's name(s); returns the declarations pass 2 must type. */
  private def enterTopLevel(c: Ctx, item: Tree): (Ctx, List[(Tree, SymbolId)]) =
    item match
      case Trees.UseDecl(path, selectors, None, span) =>
        val (modulePath, names) =
          if selectors.nonEmpty then (path, selectors) else (path.dropRight(1), path.takeRight(1))
        val module = c.modules.getOrElse(modulePath.mkString("."), Map.empty)
        val c1 = names.foldLeft(c) { (ci, name) =>
          module.get(name) match
            case Some(id) => ci.bind(name, id)
            case None     =>
              val (ci1, id) = ci.enter(name, SymKind.Import, Type.UnitType, span)
              ci1.bind(name, id)
        }
        (c1, Nil)
      case t: Trees.SatisfiesDecl => (c, List(t -> SymbolId.Root))
      case t: Trees.FnDecl        => declare(c, t, t.name, SymKind.Fn)
      case t: Trees.CapDecl       => declare(c, t, t.name, SymKind.Cap)
      case t: Trees.DataDecl      => declare(c, t, t.name, SymKind.Data)
      case t: Trees.EffectDecl    => declare(c, t, t.name, SymKind.Effect)
      case t: Trees.TypeAlias     => declare(c, t, t.name, SymKind.TypeAlias)

      /** ponytail: `mod` declarations are not indexed yet. */
      case _ => (c, Nil)

  private def declare(c: Ctx, tree: Tree, name: String, kind: SymKind): (Ctx, List[(Tree, SymbolId)]) =
    val c1 = c.lookup(name).filter(c.table(_).owner == SymbolId.Root) match
      case Some(_) => c.error(DiagCode.T003, tree.span, s"duplicate definition: $name")
      case None    => c
    val (c2, id) = c1.enter(name, kind, Type.UnitType, tree.span)
    (c2.bind(name, id), List(tree -> id))

  private def typeDecl(c: Ctx, tree: Tree, id: SymbolId): Ctx =
    val (c1, info) = c.nested(id) { ci =>
      tree match
        case Trees.SatisfiesDecl(typeName, capRef, mappings, _) =>
          val (ci1, cap) = capBound(ci, capRef)
          val withCtors = cap match
            case Some(Type.CapRef(c, _)) =>
              mappings.foldLeft(ci1.table.satisfy(typeName, cap.get)) {
                case (t, Trees.ConstructorMapping(variant, ctor, _)) => t.mapConstructor(c, ctor, typeName, variant)
                case (t, _)                                          => t
              }
            case Some(other) => ci1.table.satisfy(typeName, other)
            case None        => ci1.table
          (ci1.copy(table = withCtors), Type.UnitType)
        case t: Trees.FnDecl                         => fnSignature(ci, t, anonymousBounds = true)
        case t: Trees.CapDecl                        => capSignature(ci, t)
        case t: Trees.DataDecl                       => dataSignature(ci, t, id)
        case t: Trees.EffectDecl                     => effectSignature(ci, t, id)
        case Trees.TypeAlias(_, valueParams, rhs, _) =>
          val (ci1, _) = mapAccum(ci, valueParams)(valueParam(_, _))
          typeOf(ci1, rhs)
        case _ => (ci, Type.UnitType)
    }
    if id == SymbolId.Root then c1 else c1.copy(table = c1.table.refine(id, info))

  /** `(params) -> ret with fx`. Parameters become `ValueParam` symbols in scope for the rest of the
    * signature (refinement calls such as `between(lo, hi)` refer to them).
    */
  private def fnSignature(c: Ctx, fn: Trees.FnLike, anonymousBounds: Boolean): (Ctx, Type) =
    val c0 = fn.typeParamsHint.foldLeft(c)((ci, name) => introduce(ci, name, Nil, fn.span)._1)
    val (c1, params) = mapAccum(c0, fn.params)(valueParam(_, _, anonymousBounds))
    val (c2, ret) = fn.returnType.fold((c1, Type.UnitType))(typeOf(c1, _))
    arrow(c2, Type.TupleType(params), ret, fn.withClause)

  /** Each anonymous bound `is C` in a parameter type of a top-level `fn`, a capability method, a
    * static method or an effect operation becomes a type parameter of its own, named as written
    * (`spec/type_system.md` §5.1). Two places keep the bound as written for now. A nested `fn`
    * would become generic, and the backend cannot compile a generic nested function. A bound
    * inside a function-typed parameter (`f: (is C) -> u64`, not matched below) would be a type the
    * caller picks, and when a lambda without annotations leaves it unsolved, nothing reports it.
    */
  private def anonymous(c: Ctx, t: Type, span: Span): (Ctx, Type) =
    t match
      case Type.IsBound(bs) =>
        val (c1, inner) = mapAccum(c, bs)(anonymous(_, _, span))
        val (c2, id) = c1.enter(c1.table.render(Type.IsBound(inner)), SymKind.TypeParam, Type.IsBound(inner), span)
        (c2, Type.TypeRef(id, Nil))
      case _: Type.TypeRef | _: Type.CapRef | _: Type.TupleType =>
        val (c1, parts) = mapAccum(c, t.children)(anonymous(_, _, span))
        val it = parts.iterator
        (c1, t.mapChildren(_ => it.next()))
      case other => (c, other)

  /** `lhs -> rhs [with row]`. */
  private def arrow(c: Ctx, lhs: Type, rhs: Type, withClause: Option[Tree]): (Ctx, Type) =
    val (c1, fx) = withClause.fold((c, EffectRow.empty))(effects(c, _))
    (c1, Type.ArrowType(lhs, rhs, fx))

  private def valueParam(c: Ctx, p: Trees.FnParam, anonymousBounds: Boolean = false): (Ctx, Type) =
    val (c0, written) = typeOf(c, p.typeAnn)
    val (c1, t) = if anonymousBounds then anonymous(c0, written, p.span) else (c0, written)
    val (c2, id) = c1.enter(p.name, SymKind.ValueParam, t, p.span)
    (c2.bind(p.name, id), t)

  /** A cap's info is its `extends` chain; its methods are entered as members. */
  private def capSignature(c: Ctx, cap: Trees.CapDecl): (Ctx, Type) =
    val (c1, _) = ofParams(c, cap.ofParams)
    val (c2, _) = mapAccum(c1, cap.valueParams)(valueParam(_, _))
    val (c3, supers) = mapAccum(c2, cap.extendsList)(capBound)
    val c4 = cap.body.foldLeft(c3) { (ci, item) =>
      item match
        case m: Trees.InstanceMethod => withDefault(member(ci, m, SymKind.Method), m)
        case m: Trees.StaticMethod   => withDefault(member(ci, m, SymKind.StaticMethod), m)
        case _                       => ci
    }
    (c4, Type.IsBound(supers.flatten))

  /** A capability member with a body is a default: a satisfaction need not define it (Rule 6.1). */
  private def withDefault(entered: (Ctx, SymbolId), m: Trees.FnLike): Ctx =
    val (c, id) = entered
    if m.body.isDefined then c.copy(table = c.table.markDefaulted(id)) else c

  /** A data type's info is `TypeRef(self, typeParams)`; each variant is a constructor. */
  private def dataSignature(c: Ctx, data: Trees.DataDecl, id: SymbolId): (Ctx, Type) =
    val (c1, params) = ofParams(c, data.ofParams)
    val self = Type.TypeRef(id, params)
    val c2 = data.variants.foldLeft(c1) { (ci, v) =>
      val (ci1, vid) = ci.enter(v.name, SymKind.Variant, Type.UnitType, v.span)
      val (ci2, info) = ci1.nested(vid) { cv =>
        val (cv1, fields) = mapAccum(cv, v.fields) { (cf, f) =>
          val (cf1, t) = typeOf(cf, f.typeAnn)
          val (cf2, fid) = cf1.enter(f.name, SymKind.Field, t, f.span)
          (if f.default.isDefined then cf2.copy(table = cf2.table.markDefaulted(fid)) else cf2, t)
        }
        (cv1, if fields.isEmpty then self else Type.ArrowType(Type.TupleType(fields), self, EffectRow.empty))
      }
      ci2.copy(table = ci2.table.refine(vid, info))
    }
    (c2, self)

  private def effectSignature(c: Ctx, eff: Trees.EffectDecl, id: SymbolId): (Ctx, Type) =
    val (c1, params) = ofParams(c, eff.ofParams)
    val c2 = eff.members.foldLeft(c1) { (ci, m) =>
      m match
        case op: Trees.FnLike => member(ci, op, SymKind.Operation)._1
        case _                => ci
    }
    (c2, Type.TypeRef(id, params))

  /** Enter a method, operation or nested `fn` and type its signature in its own frame. */
  private def member(c: Ctx, fn: Trees.FnLike, kind: SymKind): (Ctx, SymbolId) =
    val (c1, id) = c.enter(fn.name, kind, Type.UnitType, fn.span)
    val (c2, info) = c1.nested(id)(fnSignature(_, fn, anonymousBounds = kind != SymKind.Fn))
    (c2.copy(table = c2.table.refine(id, info)), id)

  /** An `of (...)` declaration list: bare names and `N is C` introduce type variables, value
    * parameters become `ValueParam`s. Returns the type parameters, in order.
    */
  private def ofParams(c: Ctx, params: List[Tree]): (Ctx, List[Type]) =
    val (c1, ts) = mapAccum(c, params) { (ci, p) =>
      p match
        case fp: Trees.FnParam                                          => (valueParam(ci, fp)._1, None)
        case Trees.TypeRef(name, None, span) if ci.lookup(name).isEmpty =>
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
        c1.lookup(name) match
          case Some(id)               => (c1, reference(c1, id, args))
          case None if name == "Part" => (c1, Type.PartType)
          case None                   =>
            val (c2, id) = introduce(c1, name, Nil, span)
            (c2, Type.TypeRef(id, args))
      case Trees.NamedAlias(name, caps, span) =>
        val (c1, bounds) = mapAccum(c, caps)(capBound)
        val (c2, id) = introduce(c1, name, bounds.flatten, span)
        (c2, Type.TypeRef(id, Nil))
      case Trees.IsBound(caps, _) =>
        val (c1, bounds) = mapAccum(c, caps)(capBound)
        (c1, Type.IsBound(bounds.flatten))
      case Trees.ArrowType(lhs, rhs, withClause, _) =>
        val (c1, l) = typeOf(c, lhs)
        val (c2, r) = typeOf(c1, rhs)
        val params = (lhs, l) match
          case (_: Trees.TupleArrowLhs, Type.TupleType(ts)) => ts
          case (_: Trees.UnitType, _)                       => Nil
          case (_, single)                                  => List(single)
        arrow(c2, Type.TupleType(params), r, withClause)
      case Trees.TupleArrowLhs(types, _) =>
        val (c1, ts) = mapAccum(c, types)(typeOf)
        (c1, Type.TupleType(ts))
      case Trees.CapType(spec, _) =>
        spec match
          case None                               => (c, Type.CapType(None))
          case Some(Trees.CapReturnSpec.OfArg(a)) =>
            val (c1, t) = typeOf(c, a)
            (c1, Type.CapType(Some(t)))
          case Some(Trees.CapReturnSpec.Extends(caps)) =>
            val (c1, bounds) = mapAccum(c, caps)(capBound)
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
      case Some(Trees.TupleArrowLhs(types, _)) => mapAccum(c, types)(typeOf)
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
        val (c1, terms) = mapAccum(c, args)(symbolicTerm)
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
    val (c1, resolved) = mapAccum(c, bounds) {
      case (ci, Trees.EffectBound(name, ofArg, span)) =>
        ci.lookup(name).filter(ci.table(_).kind != SymKind.TypeParam) match
          case Some(_) =>
            val (ci1, args) = typeArgs(ci, ofArg)
            resolve(ci1, name, span, "effect")(EffectBound(_, args))
          case None =>
            /** A row variable (`spec/effects.md` §8.1), introduced at first use like a type variable. */
            val (ci1, id) = ci.lookup(name).fold(introduce(ci, name, Nil, span))((ci, _))
            val ci2 =
              if ofArg.isDefined then ci1.error(DiagCode.T005, span, s"row variable $name takes no arguments") else ci1
            (ci2, Some(EffectBound(id, List(Type.TypeRef(id, Nil)))))
      case (ci, other) => (ci.error(DiagCode.T005, other.span, "not an effect"), None)
    }
    (c1, EffectRow(resolved.flatten))

  private def effectList(row: Tree): List[Tree] =
    row match
      case Trees.EffectRow(effects, _) => effects
      case single                      => List(single)

  private def resolve[A](c: Ctx, name: String, span: Span, what: String)(f: SymbolId => A): (Ctx, Option[A]) =
    c.lookup(name) match
      case Some(id) => (c, Some(f(id)))
      case None     => (c.error(DiagCode.T001, span, s"not found: $what $name"), None)

  /** Bind type variable `name` with `bounds`. Re-stating a variable of the current signature
    * (`-> N is positive()`) adds the bounds to it; re-binding an outer or global name is an error.
    */
  private def introduce(c: Ctx, name: String, bounds: List[Type], span: Span): (Ctx, SymbolId) =
    (c.lookup(name), c.sigLocals(name)) match
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
