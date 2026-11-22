package fixed.typer

import fixed.ast.{Tree, Trees}
import fixed.core.{Denotation, SymKind, SymbolId, Type, render}
import fixed.util.{DiagCode, Span}

/** Declaration-level capability checks (`docs/plans/phase-3.1-capability-closure.md` §3.2, §3.5),
  * run after body typing: `satisfies` blocks against their capability, and inherited-member
  * conflicts between a capability's parents (Rule 6.2).
  */
object Declarations:

  def check(s: TyperState, items: List[Tree]): TyperState =
    items.foldLeft(s) {
      case (si, d: Trees.SatisfiesDecl) => satisfiesBodies(satisfies(si, d), d)
      case (si, d: Trees.CapDecl)       =>
        si.symbols.scope.get(d.name).fold(si)(cap => capBodies(diamond(si, cap, d.span), cap, d))
      case (si, _) => si
    }

  /** `T satisfies C`: every mapping names a constructor requirement `C` itself declares (Rule
    * 8.1.a) at most once, a constructor mapping's variant fields fit the requirement's
    * parameters, and every such requirement is mapped or `impossible` (Rule 8.1.b). Requirements
    * inherited through `extends` are covered by their own satisfactions or by derivation.
    */
  private def satisfies(s: TyperState, d: Trees.SatisfiesDecl): TyperState =
    d.capRef match
      case Trees.CapRef(capName, _, _) =>
        s.symbols.scope.get(capName).filter(s.symbols(_).kind == SymKind.Cap) match
          case None      => s
          case Some(cap) => mappings(s, d, cap)
      case _ => s

  private def mappings(s: TyperState, d: Trees.SatisfiesDecl, cap: SymbolId): TyperState =
    val requirements = s.symbols.membersOf(cap).filter(_.kind == SymKind.StaticMethod).map(m => m.name -> m).toMap
    val mapped = d.mappings.collect { case m: Trees.SatisfiesMapping => m }
    val capName = s.symbols(cap).name
    val (s1, _) = mapped.foldLeft((s, Set.empty[String])) { case ((si, seen), m) =>
      val name = m.capCtor
      if !requirements.contains(name) then
        (si.error(DiagCode.T014, m.span, s"$capName has no constructor requirement $name"), seen)
      else if seen(name) then (si.error(DiagCode.T014, m.span, s"$name is mapped more than once"), seen)
      else
        val si1 = m match
          case Trees.ConstructorMapping(variant, _, span) =>
            constructor(si, d.typeName, variant, requirements(name), span)
          case _ => si
        (si1, seen + name)
    }
    val written = d.mappings.collect { case fn: Trees.StaticMethod if fn.body.isDefined => fn.name }
    val unmapped = requirements.values.filterNot(m =>
      mapped.exists(_.capCtor == m.name) || written.contains(m.name) || s.symbols.hasDefault(m.id)
    )
    val missing = unmapped.map(_.name).toList.sorted ++ undefined(s1, d, cap)
    if missing.isEmpty then s1
    else
      s1.error(
        DiagCode.T010,
        d.span,
        s"${d.typeName} does not satisfy $capName: missing ${missing.mkString(", ")}",
        s.symbols.dataNamed(d.typeName).flatMap(id => Members.notFunctor(s.symbols, Type.TypeRef(id, Nil), cap))
      )

  /** Whether the compiler provides capability `cap` for the primitive type or `String` named `t`
    * (`spec/stdlib.md` §4): `Show`, `Eq`, `Ord` and `Clone` for all of them, `Numeric` for the
    * numeric types, `Concat` for `String`, `Boolean` for `bool`.
    */
  private def provided(t: String, cap: String): Boolean =
    cap match
      case "Show" | "Eq" | "Ord" | "Clone" => true
      case "Numeric"                       => !Set("String", "bool", "char")(t)
      case "Concat"                        => t == "String"
      case "Boolean"                       => t == "bool"
      case _                               => false

  /** Members of `cap` and of what it `extends`, but not its own constructor requirements (mapped
    * instead), that `d` leaves without a body, a default or a derivation (P6 D17). A member a
    * nearer capability redeclares (`Optional.fold` over `Folding.fold`) is that one. A capability
    * the type satisfies in a declaration of its own is checked there.
    */
  private def undefined(s: TyperState, d: Trees.SatisfiesDecl, cap: SymbolId): List[String] =
    val data = s.symbols.dataNamed(d.typeName)
    val bodies = d.mappings.collect { case fn: Trees.FnLike if fn.body.isDefined => fn.name }.toSet
    val self = data.fold(Type.PrimType(d.typeName))(Type.TypeRef(_, Nil))
    val own = s.symbols.satisfactionsOf(self).collect { case Type.CapRef(c, _) => c }.toSet
    val builtin = data.isEmpty || data == s.symbols.prelude.get("String")
    def needed(k: SymbolId) = (k == cap || !own(k)) && !(builtin && provided(d.typeName, s.symbols.preludeName(k)))
    def defined(m: Denotation) = bodies(m.name) || s.symbols.hasDefault(m.id) ||
      data.exists(Satisfaction.derives(s, _, m))
    MemberTables.table(s.symbols, cap).values.flatMap(_.values.map(_.head)).toList
      .filter(e => needed(e.cap) && (e.member.kind == SymKind.Method || e.cap != cap))
      .map(_.member).filterNot(defined).map(_.name).distinct.sorted

  /** `Variant as req`: the variant's fields unify with the requirement's parameters, `Self` being
    * the satisfying data type and `Part` its element type.
    */
  private def constructor(s: TyperState, typeName: String, variant: String, req: Denotation, span: Span)
      : TyperState =
    s.symbols.dataNamed(typeName) match
      case None       => s
      case Some(data) =>
        s.symbols.variantsOf(data).find(_.name == variant) match
          case None    => s.error(DiagCode.T001, span, s"not found: variant $typeName.$variant")
          case Some(v) =>
            val (s1, args) = Members.freshArgs(s, data)
            val self = Type.TypeRef(data, args)
            val (s2, sig) =
              Members.instantiate(
                s1,
                req.info,
                Members.Subst(
                  scala.collection.immutable.IntMap.empty,
                  Set(req.id, req.owner),
                  Some(self),
                  args.lastOption
                )
              )
            val (s3, fields) = Members.fieldTypes(s2, data, args, v.id)
            val params = Members.paramTypes(sig)

            /** Rule 8.1.a: trailing fields with a default may be absent from the requirement. */
            val omitted = s3.symbols.fieldsOf(v.id).drop(params.size).toList
            if params.size > fields.size || !omitted.forall(f => s3.symbols.hasDefault(f.id)) then
              s3.error(
                DiagCode.T010,
                span,
                s"$typeName.$variant has ${fields.size} field(s), ${req.name} takes ${params.size}" +
                  (if params.size < fields.size then " (the extra fields need defaults)" else "")
              )
            else
              params.zip(fields).foldLeft(s3) { case (si, (p, f)) =>
                si.unifier.unify(p, f, Members.isNumeric(si, _)) match
                  /** A field may refine an `is ...` parameter (Rule 8.1.a): it must satisfy the bound. */
                  case Right(u) =>
                    u.obligations.foldLeft(si.copy(unifier = u.copy(obligations = Nil))) { case (sj, (value, bound)) =>
                      Satisfaction.discharge(sj, value, bound, span)
                    }
                  case Left(_) =>
                    val r = si.symbols.render
                    si.error(
                      DiagCode.T010,
                      span,
                      s"$typeName.$variant field ${r(si.unifier.zonk(f))} does not fit ${req.name}'s ${r(si.unifier.zonk(p))}"
                    )
              }

  /** Rule 6.1.1 phase 1: a capability's default method bodies, typed against an abstract `Self` —
    * the capability's own anonymous bound, so only what the capability promises is available.
    */
  private def capBodies(s: TyperState, cap: SymbolId, d: Trees.CapDecl): TyperState =
    val params = s.symbols.typeParamsOf(cap)
    val self = Type.IsBound(List(Type.CapRef(cap, params.map(Type.TypeRef(_, Nil)))))
    withBodies(s, d.body) { (si, fn, kind) =>
      si.symbols.membersOf(cap).find(m => m.kind == kind && m.name == fn.name).fold(si) { m =>
        val sub = Members.Subst(Members.Subst.of(Nil, Nil), Set.empty, Some(self), None)
        memberBody(si, fn, m, sub, cap, params, Option.when(kind == SymKind.Method)(self))
      }
    }

  /** `satisfies` method bodies (§8.3): each is typed against the capability member it implements,
    * with `Self` the satisfying type and `Part` its element type.
    */
  private def satisfiesBodies(s: TyperState, d: Trees.SatisfiesDecl): TyperState =
    val target = d.capRef match
      case Trees.CapRef(name, _, _) => s.symbols.scope.get(name).filter(s.symbols(_).kind == SymKind.Cap)
      case _                        => None
    target.fold(s) { cap =>
      /** Rigid, like a capability's default body: the body is compiled once for every argument. */
      val self = s.symbols.dataNamed(d.typeName) match
        case Some(data) => Type.TypeRef(data, s.symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil)))
        case None       => Type.PrimType(d.typeName)
      val part = self match
        case Type.TypeRef(_, args) => args.lastOption
        case _                     => None
      withBodies(s, d.mappings) { (si, fn, kind) =>
        si.members.lookup(cap, kind, fn.name).headOption match
          case None    => si.error(DiagCode.T014, fn.span, s"${si.symbols(cap).name} has no member ${fn.name}")
          case Some(e) =>
            val sub = Members.Subst(Members.Subst.of(Nil, Nil), Set(e.cap), Some(self), part)
            val si1 = fn.params.foldLeft(si)((sj, p) => sj.reservedName(p.name, p.span))
            memberBody(si1, fn, e.member, sub, cap, Nil, Option.when(kind == SymKind.Method)(self))
      }
    }

  /** A body of capability member `m`, typed against its signature under `sub`. The member's own
    * type parameters stay rigid; their bounds, instantiated under the same `sub` (`Self`, `Part`,
    * the capability's arguments), hold while the body is typed: for member lookup and satisfaction
    * (`TyperState.rigidBounds`) and for unification (`Unifier.paramBounds`). The signature and the
    * bounds are instantiated as one tuple so that both get the same fresh variables.
    */
  private def memberBody(
      s: TyperState,
      fn: Trees.FnLike,
      m: Denotation,
      sub: Members.Subst,
      cap: SymbolId,
      scoped: List[SymbolId],
      self: Option[Type]
  ): TyperState =
    val params = s.symbols.typeParamsOf(m.id)
    val bounds = params.map(p => Type.IsBound(Members.boundsOf(s.symbols, p)))
    val (s1, Type.TupleType(sig :: instantiated)) =
      Members.instantiate(s, Type.TupleType(m.info :: bounds), sub): @unchecked
    val rigid = scala.collection.immutable.IntMap.from(params.zip(instantiated).collect { case (p, Type.IsBound(bs)) =>
      p.toInt -> bs
    })
    val declared = s1.unifier.paramBounds
    val inBody = s1.unifier.copy(paramBounds = p => rigid.getOrElse(p.toInt, declared(p)))
    val typed = method(s1.copy(rigidBounds = rigid, unifier = inBody), fn, sig, cap, scoped ++ params, self)
    typed.copy(rigidBounds = rigid.empty, unifier = typed.unifier.copy(paramBounds = declared))

  /** Fold `f` over the methods in `items` that have a body, with their member kind. */
  private def withBodies(s: TyperState, items: List[Tree])(f: (TyperState, Trees.FnLike, SymKind) => TyperState)
      : TyperState =
    items.foldLeft(s) {
      case (si, fn: Trees.StaticMethod) if fn.body.isDefined => f(si, fn, SymKind.StaticMethod)
      case (si, fn: Trees.FnLike) if fn.body.isDefined       => f(si, fn, SymKind.Method)
      case (si, _)                                           => si
    }

  /** Type `fn`'s body against `sig`: its parameters take `sig`'s parameter types by position. */
  private def method(
      s: TyperState,
      fn: Trees.FnLike,
      sig: Type,
      owner: SymbolId,
      scoped: List[SymbolId],
      self: Option[Type]
  ): TyperState =
    sig match
      case Type.ArrowType(lhs, ret, fx) =>
        val params = Members.params(lhs)
        if params.size != fn.params.size then
          s.error(
            DiagCode.T010,
            fn.span,
            s"${fn.name} takes ${params.size} parameter(s), this definition ${fn.params.size}"
          )
        else
          val locals = fn.params.map(_.name).zip(params) ++ self.map("self" -> _)
          fn.body.fold(s)(Typer.typedMethodBody(
            s,
            _,
            owner,
            locals,
            scoped.map(p => s.symbols(p).name -> p),
            ret,
            fx,
            fn.name
          ))
      case _ => s

  /** Rule 6.2: a member name a capability inherits from two parents, without declaring it itself,
    * must have compatible signatures (v1: the same parameter count).
    */
  private def diamond(s: TyperState, cap: SymbolId, span: Span): TyperState =
    val own = s.symbols.membersOf(cap).map(_.name).toSet
    val inherited = s.members.closure(cap).toList.filterNot(_ == cap.toInt).flatMap { c =>
      s.symbols.membersOf(SymbolId(c)).filter(m => m.kind == SymKind.Method && !own(m.name)).toList
    }
    inherited.groupBy(_.name).foldLeft(s) { case (si, (name, ms)) =>
      val arities = ms.map(m => Members.arity(m.info)).distinct
      if arities.size <= 1 then si
      else
        si.error(
          DiagCode.T013,
          span,
          s"$name is inherited with incompatible signatures; declare it in ${si.symbols(cap).name}"
        )
    }
