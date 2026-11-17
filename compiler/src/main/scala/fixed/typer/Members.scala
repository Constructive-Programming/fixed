package fixed.typer

import fixed.core.{SymKind, SymbolId, Type}
import fixed.util.mapAccum

/** Signature lookup for the typer: instantiation of generic signatures, instance members (data
  * fields, capability methods reached through `satisfies` or a type variable's bound, and the
  * derived `fold`), and static members (effect operations, data constructors, derived `unfold`).
  *
  * Every result is an instantiated type: type variables the signature introduced become fresh
  * unification variables, `Self` becomes the receiver and `Part` its element type.
  */
object Members:

  private val numericPrims = Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64")

  /** A primitive numeric type, or a type variable whose bound names `Numeric`. Transitive
    * `extends Numeric` joins with capability closure (M6).
    */
  def isNumeric(s: TyperState, t: Type): Boolean =
    t match
      case Type.PrimType(n)    => numericPrims(n)
      case Type.TypeRef(id, _) =>
        s.symbols(id).info match
          case Type.IsBound(bs) => bs.exists(isCap(s, _, "Numeric"))
          case _                => false
      case _ => false

  private def isCap(s: TyperState, t: Type, name: String): Boolean =
    t match
      case Type.CapRef(c, _) => s.symbols(c).name == name
      case _                 => false

  /** How to rewrite a signature: explicit replacements, owners whose type variables become fresh,
    * and what `Self` / `Part` stand for.
    */
  final case class Subst(
      mapping: Map[SymbolId, Type],
      freshOwners: Set[SymbolId],
      self: Option[Type] = None,
      part: Option[Type] = None
  )

  /** Rewrite `t` under `sub`, allocating one fresh variable per instantiated type variable. */
  def instantiate(s: TyperState, t: Type, sub: Subst): (TyperState, Type) =
    val (s1, m, out) = go(s, t, sub, sub.mapping)
    val _ = m
    (s1, out)

  private def go(s: TyperState, t: Type, sub: Subst, m: Map[SymbolId, Type]): (TyperState, Map[SymbolId, Type], Type) =
    def rebuild(parts: List[Type])(mk: List[Type] => Type) =
      val ((s1, m1), out) = mapAccum((s, m), parts) { case ((si, mi), ti) =>
        val (si1, mi1, ti1) = go(si, ti, sub, mi)
        ((si1, mi1), ti1)
      }
      (s1, m1, mk(out))
    t match
      case Type.TypeRef(id, Nil) if m.contains(id)  => (s, m, m(id))
      case Type.TypeRef(id, args) if m.contains(id) => rebuild(args)(Unifier.applyHead(s.unifier.resolve(m(id)), _))
      case Type.TypeRef(id, args)
          if s.symbols(id).kind == SymKind.TypeParam && sub.freshOwners(s.symbols(id).owner) =>
        val (u, v) =
          s.unifier.fresh(numeric = isNumeric(s, Type.TypeRef(id, Nil)), default = None, bounds = boundsOf(s, id))
        go(s.copy(unifier = u), Type.TypeRef(id, args), sub, m.updated(id, v))
      case Type.TypeRef(id, args)    => rebuild(args)(Type.TypeRef(id, _))
      case Type.CapRef(id, args)     => rebuild(args)(Type.CapRef(id, _))
      case Type.ArrowType(l, r, fx)  => rebuild(List(l, r))(xs => Type.ArrowType(xs.head, xs(1), fx))
      case Type.TupleType(ts)        => rebuild(ts)(Type.TupleType(_))
      case Type.IsBound(bs)          => rebuild(bs)(Type.IsBound(_))
      case Type.CapType(r)           => rebuild(r.toList)(xs => Type.CapType(xs.headOption))
      case Type.Layer(d, args, seed) => rebuild(args :+ seed)(xs => Type.Layer(d, xs.init, xs.last))
      case Type.SelfType(of)         => rebuild(of.toList)(xs => selfOf(sub.self.getOrElse(t), xs.headOption))
      case Type.PartType             =>
        (sub.part, m.get(PartKey)) match
          case (Some(p), _)    => (s, m, p)
          case (None, Some(p)) => (s, m, p)
          case (None, None)    =>
            val (s1, v) = s.fresh
            (s1, m.updated(PartKey, v), v)
      case other => (s, m, other)

  /** The element type of a container type: its last argument, or its bound's (`M is Monad of A`). */
  def elementOf(s: TyperState, t: Type): Option[Type] =
    s.unifier.resolve(t) match
      case Type.TypeRef(_, args) if args.nonEmpty                           => args.lastOption
      case Type.TypeRef(id, Nil) if s.symbols(id).kind == SymKind.TypeParam => boundElement(boundsOf(s, id))
      case Type.CapRef(_, args) if args.nonEmpty                            => args.lastOption
      case Type.IsBound(bs)                                                 => boundElement(bs)
      case Type.TypeVar(id)                                                 => boundElement(s.unifier.boundsOf(id))
      case Type.AppliedVar(_, args)                                         => args.lastOption
      case _                                                                => None

  private def boundElement(bounds: List[Type]): Option[Type] =
    bounds.collectFirst { case Type.CapRef(_, args) if args.nonEmpty => args.last }

  /** Memo key for the fresh variable standing for an unknown `Part`. */
  private val PartKey: SymbolId = SymbolId(-1)

  /** `Self of B`: the receiver with its element (last) argument replaced. */
  private def selfOf(self: Type, of: Option[Type]): Type =
    (self, of) match
      case (Type.TypeRef(id, args), Some(b)) if args.nonEmpty => Type.TypeRef(id, args.init :+ b)
      case (Type.TypeRef(id, Nil), Some(b))                   => Type.TypeRef(id, List(b))
      case _                                                  => self

  /** Type variables `owner` declared (`of` parameters), in declaration order. */
  def typeParamsOf(s: TyperState, owner: SymbolId): List[SymbolId] =
    s.typeParams.getOrElse(owner.toInt, Nil)

  /** Capabilities every type has: auto-derived per `spec/stdlib.md` §4.7, §4.10, §4.11. Whether a
    * representation can actually derive them is checked at representation selection (M11).
    */
  private val autoDerived = List("Show", "Eq", "Clone")

  /** The instantiated type of instance member `name` on `recv`, if any. */
  def instance(s: TyperState, recv: Type, name: String): Option[(TyperState, Type)] =
    candidates(s, recv, name).headOption

  /** Every instance member `name` on `recv`, best first: a data type's own field, then its
    * capabilities, then its derived `fold`. Calls with several candidates pick by argument shape.
    */
  def candidates(s: TyperState, recv: Type, name: String): LazyList[(TyperState, Type)] =
    val resolved = s.unifier.resolve(recv)
    val auto = autoDerived.flatMap(s.symbols.scope.get).map(Type.CapRef(_, Nil))
    val own: LazyList[(TyperState, Type)] = resolved match
      case Type.TupleType(ts) =>
        LazyList.from(s.symbols.scope.get("Tuple")).flatMap(t => candidates(s, Type.TypeRef(t, ts), name))
      case t @ Type.TypeRef(id, args) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.Data =>
            LazyList.from(field(s, id, args, name)) #:::
              capMember(s, s.symbols.satisfies.getOrElse(d.name, Nil), t, name, Set.empty) #:::
              LazyList.from(Option.when(name == "fold")(derivedFold(s, id, args)).flatten)
          case SymKind.TypeParam => capMember(s, boundsOf(s, id), t, name, Set.empty)
          case _                 => LazyList.empty
      case t @ Type.PrimType(n) =>
        val numeric = if isNumeric(s, t) then s.symbols.scope.get("Numeric").map(Type.CapRef(_, Nil)).toList else Nil
        capMember(s, s.symbols.satisfies.getOrElse(n, Nil) ++ numeric, t, name, Set.empty)
      case t @ Type.CapRef(_, _)  => capMember(s, List(t), t, name, Set.empty)
      case Type.IsBound(bs)       => capMember(s, bs, resolved, name, Set.empty)
      case Type.TypeVar(id)       => capMember(s, s.unifier.boundsOf(id), resolved, name, Set.empty)
      case Type.AppliedVar(id, _) => capMember(s, s.unifier.boundsOf(id), resolved, name, Set.empty)
      case _                      => LazyList.empty
    if own.nonEmpty then own else capMember(s, auto, resolved, name, Set.empty)

  /** A field of a single-variant data type, as a zero-argument member. */
  private def field(s: TyperState, data: SymbolId, args: List[Type], name: String): Option[(TyperState, Type)] =
    s.symbols.variantsOf(data).toList match
      case variant :: Nil =>
        s.symbols.fieldsOf(variant.id).find(_.name == name).map { f =>
          val (s1, t) = instantiate(s, f.info, Subst(typeParamsOf(s, data).zip(args).toMap, Set.empty))
          (s1, Type.ArrowType(Type.TupleType(Nil), t, fixed.core.EffectRow.empty))
        }
      case _ => None

  /** Method `name` in `caps` or their `extends` chains, instantiated for receiver `recv`. */
  private def capMember(
      s: TyperState,
      caps: List[Type],
      recv: Type,
      name: String,
      seen: Set[SymbolId],
      kind: SymKind = SymKind.Method,
      partOverride: Option[Type] = None
  ): LazyList[(TyperState, Type)] =
    LazyList
      .from(expandAliases(s, caps))
      .collect { case Type.CapRef(c, capArgs) if !seen(c) => (c, capArgs) }
      .flatMap { case (c, capArgs) =>
        val recvArgs = recv match
          case Type.TypeRef(_, as) => as
          case _                   => Nil
        val params = typeParamsOf(s, c)
        val args = if capArgs.nonEmpty then capArgs else recvArgs.takeRight(params.size)
        val part = partOverride.orElse(capArgs.lastOption).orElse(recvArgs.lastOption)
        val own = LazyList.from(s.symbols.membersOf(c).find(m => m.kind == kind && m.name == name)).map { m =>
          instantiate(s, m.info, Subst(params.zip(args).toMap, Set(m.id, c), Some(recv), part))
        }
        lazy val supers = s.symbols(c).info match
          case Type.IsBound(bs) => bs
          case _                => Nil
        own #::: capMember(s, supers, recv, name, seen + c, kind, partOverride)
      }

  /** Replace type-alias references in a bound chain by their constituents; arguments given to
    * the alias pass to constituents that take none (`ArrayLike of A` is `Sequencing of A + ...`).
    */
  private def expandAliases(s: TyperState, bounds: List[Type]): List[Type] =
    bounds.flatMap {
      case Type.TypeRef(id, args) if s.symbols(id).kind == SymKind.TypeAlias =>
        s.symbols(id).info match
          case Type.IsBound(parts) =>
            expandAliases(
              s,
              parts.map {
                case Type.CapRef(c, Nil) if args.nonEmpty => Type.CapRef(c, args)
                case other                                => other
              }
            )
          case _ => Nil
      case other => List(other)
    }

  /** A type variable's bound chain, aliases expanded. */
  private def boundsOf(s: TyperState, param: SymbolId): List[Type] =
    s.symbols(param).info match
      case Type.IsBound(bs) => expandAliases(s, bs)
      case _                => Nil

  /** `fold` derived from a data type's variants (a catamorphism): one function per variant, its
    * fields with each recursive occurrence of the type replaced by the result type `R`.
    */
  private def derivedFold(s: TyperState, data: SymbolId, args: List[Type]): Option[(TyperState, Type)] =
    val variants = s.symbols.variantsOf(data).toList
    Option.when(variants.nonEmpty) {
      val (s0, r) = s.fresh
      val (s1, cases) = mapAccum(s0, variants) { (si, v) =>
        val (si1, fields) = fieldTypes(si, data, args, v.id)
        (si1, Type.ArrowType(product(fields.map(replaceSelf(_, data, r))), r, fixed.core.EffectRow.empty))
      }
      (s1, Type.ArrowType(product(cases), r, fixed.core.EffectRow.empty))
    }

  /** The static member `name` of the type, effect or import `owner`, instantiated. `pt` decides
    * whether a constructor builds the data type or one layer of it (inside a derived `unfold`).
    */
  def static(s: TyperState, owner: SymbolId, name: String, layer: Option[Type.Layer]): Option[(TyperState, Type)] =
    val d = s.symbols(owner)
    d.kind match
      case SymKind.Effect =>
        s.symbols.membersOf(owner).find(m => m.kind == SymKind.Operation && m.name == name).map { op =>
          instantiate(s, op.info, Subst(Map.empty, Set(owner, op.id)))
        }
      case SymKind.Data =>
        s.symbols.variantsOf(owner).find(_.name == name) match
          case Some(v)                  => Some(construct(s, owner, v.id, layer))
          case None if name == "unfold" => Some(derivedUnfold(s, owner))
          case None                     =>
            val (s1, args) = freshArgs(s, owner)
            val self = Type.TypeRef(owner, args)
            capMember(s1, s1.symbols.satisfies.getOrElse(d.name, Nil), self, name, Set.empty, SymKind.StaticMethod)
              .headOption
      case SymKind.TypeParam =>
        /** A type constructor variable: `M.pure(x)` is `M of typeof(x)`, whatever `M`'s bound says. */
        val (s1, part) = s.fresh
        val self = Type.TypeRef(owner, List(part))
        capMember(s1, boundsOf(s, owner), self, name, Set.empty, SymKind.StaticMethod, Some(part))
          .headOption
      case SymKind.Cap =>
        val (s1, args) = freshArgs(s, owner) match
          case (si, Nil) =>
            val (si1, part) = si.fresh
            (si1, List(part))
          case other => other
        val self = Type.IsBound(List(Type.CapRef(owner, args)))
        capMember(s1, List(Type.CapRef(owner, args)), self, name, Set.empty, SymKind.StaticMethod).headOption
      case _ => None

  /** A constructor: `fields -> D` (or `D` for a unit variant); within an `unfold` step, the layer
    * form whose recursive fields hold the seed.
    */
  private def construct(
      s: TyperState,
      data: SymbolId,
      variant: SymbolId,
      layer: Option[Type.Layer]
  ): (TyperState, Type) =
    val (s1, args) = freshArgs(s, data)
    val (s2, fields) = fieldTypes(s1, data, args, variant)
    val (result, fieldsOut) = layer match
      case Some(Type.Layer(`data`, _, seed)) => (Type.Layer(data, args, seed), fields.map(replaceSelf(_, data, seed)))
      case _                                 => (Type.TypeRef(data, args), fields)
    (s2, if fields.isEmpty then result else Type.ArrowType(product(fieldsOut), result, fixed.core.EffectRow.empty))

  /** `unfold(seed: S, step: S -> layer of D over S) -> D`, derived from the variants (an anamorphism). */
  private def derivedUnfold(s: TyperState, data: SymbolId): (TyperState, Type) =
    val (withParams, params) = freshArgs(s, data)
    val (done, seed) = withParams.fresh
    val step = Type.ArrowType(Type.TupleType(List(seed)), Type.Layer(data, params, seed), fixed.core.EffectRow.empty)
    (done, Type.ArrowType(Type.TupleType(List(seed, step)), Type.TypeRef(data, params), fixed.core.EffectRow.empty))

  def freshArgs(s: TyperState, data: SymbolId): (TyperState, List[Type]) =
    mapAccum(s, typeParamsOf(s, data)) { (si, p) =>
      val (u, v) = si.unifier.fresh(numeric = isNumeric(si, Type.TypeRef(p, Nil)), default = None)
      (si.copy(unifier = u), v)
    }

  def fieldTypes(s: TyperState, data: SymbolId, args: List[Type], variant: SymbolId): (TyperState, List[Type]) =
    val sub = Subst(typeParamsOf(s, data).zip(args).toMap, Set(variant))
    mapAccum(s, s.symbols.fieldsOf(variant).toList)((si, f) => instantiate(si, f.info, sub))

  private def replaceSelf(t: Type, data: SymbolId, by: Type): Type =
    t match
      case Type.TypeRef(`data`, _)  => by
      case Type.TypeRef(id, args)   => Type.TypeRef(id, args.map(replaceSelf(_, data, by)))
      case Type.ArrowType(l, r, fx) => Type.ArrowType(replaceSelf(l, data, by), replaceSelf(r, data, by), fx)
      case Type.TupleType(ts)       => Type.TupleType(ts.map(replaceSelf(_, data, by)))
      case other                    => other

  /** An arrow's left-hand side is always its parameter list, a `TupleType` of any arity, so a
    * single `()` parameter and no parameters stay distinct.
    */
  def params(lhs: Type): List[Type] =
    lhs match
      case Type.TupleType(ts) => ts
      case single             => List(single)

  /** A parameter list as an arrow's left-hand side. */
  def product(ts: List[Type]): Type = Type.TupleType(ts)
