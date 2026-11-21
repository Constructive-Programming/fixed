package fixed.typer

import scala.collection.immutable.IntMap

import fixed.core.{EffectBound, EffectRow, SymKind, SymbolId, SymbolTable, Type}
import fixed.util.mapAccum

/** Signature lookup for the typer: instantiation of generic signatures, instance members (data
  * fields, capability methods reached through `satisfies` or a type variable's bound, and the
  * derived `fold`), and static members (effect operations, data constructors, derived `unfold`).
  *
  * Every result is an instantiated type: type variables the signature introduced become fresh
  * unification variables, `Self` becomes the receiver and `Part` its element type.
  */
object Members:

  /** Whether operation `op` of effect `effect` is abortive: its result is `!` (Rule E3.3). */
  def abortive(symbols: SymbolTable, effect: SymbolId, op: String): Boolean =
    symbols.membersOf(effect).find(_.name == op).exists(_.info match
      case Type.ArrowType(_, Type.NeverType, _) => true
      case _                                    => false)

  /** A member found by lookup, instantiated only when a call uses it; `origin` says where it came
    * from, for the backend (`Resolution`).
    */
  final case class Candidate(origin: Resolution, run: () => (TyperState, Type)):
    def apply(): (TyperState, Type) = run()

  private val numericPrims = Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64")

  /** A primitive numeric type, or a type variable whose bound reaches `Numeric` (directly or
    * through `extends`).
    */
  def isNumeric(s: TyperState, t: Type): Boolean =
    t match
      case Type.PrimType(n)                                               => numericPrims(n)
      case Type.TypeRef(id, _) if s.symbols(id).kind == SymKind.TypeParam =>
        s.symbols.scope.get("Numeric").exists(numeric => Satisfaction.boundsReach(s, boundsOf(s, id), numeric))
      case _ => false

  /** How to rewrite a signature: explicit replacements, owners whose type variables become fresh,
    * and what `Self` / `Part` stand for.
    */
  final case class Subst(
      mapping: IntMap[Type],
      freshOwners: Set[SymbolId],
      self: Option[Type] = None,
      part: Option[Type] = None
  )

  object Subst:
    /** `params` replaced positionally by `args`. */
    def of(params: List[SymbolId], args: List[Type]): IntMap[Type] =
      IntMap.from(params.iterator.zip(args).map((p, a) => p.toInt -> a))

  /** Rewrite `t` under `sub`, allocating one fresh variable per instantiated type variable. */
  def instantiate(s: TyperState, t: Type, sub: Subst): (TyperState, Type) =
    val (s1, _, out) = go(s, t, sub, sub.mapping)
    (s1, out)

  private def rebuildAll(s: TyperState, ts: List[Type], sub: Subst, m: IntMap[Type])
      : (TyperState, IntMap[Type], List[Type]) =
    val ((s1, m1), out) = mapAccum((s, m), ts) { case ((si, mi), ti) =>
      val (si1, mi1, ti1) = go(si, ti, sub, mi)
      ((si1, mi1), ti1)
    }
    (s1, m1, out)

  private def go(s: TyperState, t: Type, sub: Subst, m: IntMap[Type]): (TyperState, IntMap[Type], Type) =
    def rebuild(parts: List[Type])(mk: List[Type] => Type) =
      val (s1, m1, out) = rebuildAll(s, parts, sub, m)
      (s1, m1, mk(out))
    t match
      case Type.TypeRef(id, Nil) if m.contains(id.toInt)  => (s, m, m(id.toInt))
      case Type.TypeRef(id, args) if m.contains(id.toInt) =>
        rebuild(args)(Unifier.applyHead(s.unifier.resolve(m(id.toInt)), _))
      case Type.TypeRef(id, args)
          if s.symbols(id).kind == SymKind.TypeParam && sub.freshOwners(s.symbols(id).owner) =>
        /** The variable first, so that its bounds, instantiated under the same mapping, may name it. */
        val numeric = isNumeric(s, Type.TypeRef(id, Nil)) && !anonymousParam(s, id)
        val (u, v) = s.unifier.fresh(numeric = numeric, default = None)
        val (s1, m1, bounds) = rebuildAll(s.copy(unifier = u), boundsOf(s, id), sub, m.updated(id.toInt, v))
        val withBounds = v match
          case Type.TypeVar(n) if bounds.nonEmpty =>
            s1.copy(unifier = s1.unifier.copy(varBounds = s1.unifier.varBounds.updated(n, bounds)))
          case _ => s1
        go(withBounds, Type.TypeRef(id, args), sub, m1)
      case Type.TypeRef(id, args)   => rebuild(args)(Type.TypeRef(id, _))
      case Type.CapRef(id, args)    => rebuild(args)(Type.CapRef(id, _))
      case Type.ArrowType(l, r, fx) =>
        rebuild(l :: r :: fx.args)(xs => Type.ArrowType(xs.head, xs(1), fx.withArgs(xs.drop(2))))
      case Type.TupleType(ts)        => rebuild(ts)(Type.TupleType(_))
      case Type.IsBound(bs)          => rebuild(bs)(Type.IsBound(_))
      case Type.CapType(r)           => rebuild(r.toList)(xs => Type.CapType(xs.headOption))
      case Type.Layer(d, args, seed) => rebuild(args :+ seed)(xs => Type.Layer(d, xs.init, xs.last))
      case Type.SelfType(of)         => rebuild(of.toList)(xs => selfOf(sub.self.getOrElse(t), xs.headOption))
      case Type.PartType             =>
        sub.part.orElse(m.get(PartKey)) match
          case Some(p) => (s, m, p)
          case None    =>
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

  /** A type parameter the Namer made for an anonymous bound `is C` (named as written). */
  def anonymousParam(s: TyperState, id: SymbolId): Boolean = s.symbols(id).name.startsWith("is ")

  /** Memo key for the fresh variable standing for an unknown `Part`. */
  private val PartKey: Int = -1

  /** `Self of B`: the receiver with its element (last) argument replaced. */
  private def selfOf(self: Type, of: Option[Type]): Type =
    (self, of) match
      case (Type.TypeRef(id, args), Some(b)) => Type.TypeRef(id, args.dropRight(1) :+ b)
      case _                                 => self

  /** Type variables `owner` declared (`of` parameters), in declaration order. */
  def typeParamsOf(s: TyperState, owner: SymbolId): List[SymbolId] =
    s.symbols.typeParamsOf(owner)

  /** Capabilities every concrete type has: auto-derived per `spec/stdlib.md` §4.7, §4.10, §4.11.
    * Whether a representation can actually derive them is checked at representation selection
    * (M11).
    */
  private val autoDerived = List("Show", "Eq", "Clone")

  /** A value of type `t` is used through a witness and its bound set only (a type parameter, or an
    * anonymous `is ...` bound), so it has no auto-derived member (P6 D10, Rule CM4.3).
    */
  def abstractType(s: TyperState, t: Type): Boolean =
    t match
      case Type.TypeRef(id, _)              => s.symbols(id).kind == SymKind.TypeParam
      case _: Type.IsBound | _: Type.CapRef => true
      case _                                => false

  /** The capability among [[autoDerived]] that declares `member`: what an abstract receiver lacks. */
  def autoDerivedCap(s: TyperState, member: String): Option[SymbolId] =
    autoDerived.flatMap(s.symbols.scope.get).find(s.symbols.membersOf(_).exists(_.name == member))

  /** Every instance member `name` on `recv`, best first: a data type's own field, then its
    * capabilities, then its derived `fold`. Calls with several candidates pick by argument shape.
    * `resolved` is the receiver's type with its variable already resolved.
    */
  def candidates(s: TyperState, resolved: Type, name: String): List[Candidate] =
    val own: List[Candidate] = resolved match
      case Type.TupleType(ts) =>
        s.symbols.scope.get("Tuple").toList.flatMap(t => candidates(s, Type.TypeRef(t, ts), name))
      case t @ Type.TypeRef(id, args) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.Data =>
            field(s, id, args, name).toList ++
              capMember(s, s.symbols.satisfies.getOrElse(d.name, Nil), t, name) ++
              Option.when(name == "fold" && s.symbols.variantsOf(id).nonEmpty)(
                Candidate(Resolution.DerivedFold(id), () => derivedFold(s, id, args))
              )
          case SymKind.TypeParam => capMember(s, boundsOf(s, id), t, name)
          case _                 => Nil
      case t @ Type.PrimType(n) =>
        val numeric = if isNumeric(s, t) then s.symbols.scope.get("Numeric").map(Type.CapRef(_, Nil)).toList else Nil
        capMember(s, s.symbols.satisfies.getOrElse(n, Nil) ++ numeric, t, name)
      case t @ Type.CapRef(_, _)  => capMember(s, List(t), t, name)
      case Type.IsBound(bs)       => capMember(s, bs, resolved, name)
      case Type.TypeVar(id)       => capMember(s, s.unifier.boundsOf(id), resolved, name)
      case Type.AppliedVar(id, _) => capMember(s, s.unifier.boundsOf(id), resolved, name)
      case _                      => Nil
    val data = resolved match
      case Type.TypeRef(id, _) => s.symbols(id).kind == SymKind.Data
      case _: Type.TupleType   => true
      case _                   => false
    val fallback =
      autoDerived.flatMap(s.symbols.scope.get).filterNot(c => data && Satisfaction.onRequest(s, c))
    if own.nonEmpty || abstractType(s, resolved) then own
    else capMember(s, fallback.map(Type.CapRef(_, Nil)), resolved, name)

  /** A field of a single-variant data type, as a zero-argument member. */
  private def field(s: TyperState, data: SymbolId, args: List[Type], name: String): Option[Candidate] =
    s.symbols.variantsOf(data).toList match
      case variant :: Nil =>
        val fields = s.symbols.fieldsOf(variant.id).toList
        fields.find(_.name == name).map { f =>
          val (s1, t) = instantiate(s, f.info, Subst(Subst.of(typeParamsOf(s, data), args), Set.empty))
          val found = (s1, Type.ArrowType(Type.TupleType(Nil), t, EffectRow.empty))
          Candidate(Resolution.Field(variant.id, fields.indexOf(f)), () => found)
        }
      case _ => None

  /** Member `name` of `kind` in `caps` or their `extends` chains, instantiated for receiver `recv`:
    * one table lookup per capability (see [[MemberTables]]).
    */
  private def capMember(
      s: TyperState,
      caps: List[Type],
      recv: Type,
      name: String,
      kind: SymKind = SymKind.Method,
      partOverride: Option[Type] = None
  ): List[Candidate] =
    val recvArgs = recv match
      case Type.TypeRef(_, as) => as
      case _                   => Nil
    expandAliases(s.symbols, caps).flatMap {
      case Type.CapRef(root, rootArgs) =>
        s.members.lookup(root, kind, name).map { e =>
          val capArgs = e.written.getOrElse(rootArgs)
          val params = typeParamsOf(s, e.cap)
          val args = if capArgs.nonEmpty then capArgs else recvArgs.takeRight(params.size)
          val part = partOverride.orElse(capArgs.lastOption).orElse(recvArgs.lastOption)
          Candidate(
            Resolution.CapMember(e.cap, e.member.id),
            () =>
              instantiate(s, e.member.info, Subst(Subst.of(params, args), Set(e.member.id, e.cap), Some(recv), part))
          )
        }
      case _ => Nil
    }

  /** Replace type-alias references in a bound chain by their constituents; arguments given to
    * the alias pass to constituents that take none (`ArrayLike of A` is `Sequencing of A + ...`).
    */
  def expandAliases(symbols: SymbolTable, bounds: List[Type]): List[Type] =
    bounds.flatMap {
      case Type.TypeRef(id, args) if symbols(id).kind == SymKind.TypeAlias =>
        symbols(id).info match
          case Type.IsBound(parts) =>
            expandAliases(
              symbols,
              parts.map {
                case Type.CapRef(c, Nil) if args.nonEmpty => Type.CapRef(c, args)
                case other                                => other
              }
            )
          case _ => Nil
      case other => List(other)
    }

  /** A type variable's bound chain, aliases expanded. */
  def boundsOf(s: TyperState, param: SymbolId): List[Type] = boundsOf(s.symbols, param)

  def boundsOf(symbols: SymbolTable, param: SymbolId): List[Type] =
    symbols(param).info match
      case Type.IsBound(bs) if symbols(param).kind == SymKind.TypeParam => expandAliases(symbols, bs)
      case _                                                            => Nil

  /** `fold` derived from a data type's variants (a catamorphism): one function per variant, its
    * fields with each recursive occurrence of the type (`Self` as declared) replaced by the result type `R`.
    */
  private def derivedFold(s: TyperState, data: SymbolId, args: List[Type]): (TyperState, Type) =
    val (s0, r) = s.fresh
    val (s1, cases) = mapAccum(s0, s.symbols.variantsOf(data).toList) { (si, v) =>
      val (si1, fields) = fieldTypes(si, data, args, v.id, replaceSelf(_, declaredSelf(s.symbols, data), r))
      (si1, Type.ArrowType(Type.TupleType(fields), r, EffectRow.empty))
    }
    (s1, Type.ArrowType(Type.TupleType(cases), r, EffectRow.empty))

  /** The static member `name` of the type, effect or import `owner`, instantiated. `pt` decides
    * whether a constructor builds the data type or one layer of it (inside a derived `unfold`).
    */
  def static(s: TyperState, owner: SymbolId, name: String, layer: Option[Type.Layer]): Option[(TyperState, Type)] =
    val d = s.symbols(owner)
    d.kind match
      case SymKind.Effect =>
        s.symbols.membersOf(owner).find(m => m.kind == SymKind.Operation && m.name == name).map { op =>
          operation(s, owner, op.info, op.id)
        }
      case SymKind.Data =>
        s.symbols.variantsOf(owner).find(_.name == name) match
          case Some(v)                  => Some(construct(s, owner, v.id, layer))
          case None if name == "unfold" => Some(derivedUnfold(s, owner))
          case None                     =>
            val (s1, args) = freshArgs(s, owner)
            val self = Type.TypeRef(owner, args)
            capMember(s1, s1.symbols.satisfies.getOrElse(d.name, Nil), self, name, SymKind.StaticMethod)
              .headOption
              .map(_())
      case SymKind.TypeParam =>
        /** A type constructor variable: `M.pure(x)` is `M of typeof(x)`, whatever `M`'s bound says. */
        val (s1, part) = s.fresh
        val self = Type.TypeRef(owner, List(part))
        capMember(s1, boundsOf(s, owner), self, name, SymKind.StaticMethod, Some(part))
          .headOption
          .map(_())
      case SymKind.Cap =>
        val (s1, args) = freshArgs(s, owner) match
          case (si, Nil) =>
            val (si1, part) = si.fresh
            (si1, List(part))
          case other => other
        val self = Type.IsBound(List(Type.CapRef(owner, args)))
        capMember(
          s1,
          List(Type.CapRef(owner, args)),
          self,
          name,
          SymKind.StaticMethod
        ).headOption.map(_())
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
    val (result, declared) = layer match
      case Some(Type.Layer(`data`, _, seed)) =>
        (Type.Layer(data, args, seed), replaceSelf(_, declaredSelf(s.symbols, data), seed))
      case _ => (Type.TypeRef(data, args), identity[Type])
    val (s2, fields) = fieldTypes(s1, data, args, variant, declared)
    (s2, if fields.isEmpty then result else Type.ArrowType(Type.TupleType(fields), result, EffectRow.empty))

  /** `unfold(seed: S, step: S -> layer of D over S) -> D`, derived from the variants (an anamorphism). */
  private def derivedUnfold(s: TyperState, data: SymbolId): (TyperState, Type) =
    val (withParams, params) = freshArgs(s, data)
    val (done, seed) = withParams.fresh
    val step = Type.ArrowType(Type.TupleType(List(seed)), Type.Layer(data, params, seed), EffectRow.empty)
    (done, Type.ArrowType(Type.TupleType(List(seed, step)), Type.TypeRef(data, params), EffectRow.empty))

  /** An effect operation's signature, instantiated. Its row also names the effect itself, with the
    * fresh `of` arguments, so calling the operation performs the effect (`spec/effects.md` §3.2).
    */
  private def operation(s: TyperState, effect: SymbolId, info: Type, op: SymbolId): (TyperState, Type) =
    val (s1, args) = freshArgs(s, effect)
    instantiate(s1, info, Subst(Subst.of(typeParamsOf(s1, effect), args), Set(op))) match
      case (s2, Type.ArrowType(l, r, fx)) =>
        (s2, Type.ArrowType(l, r, EffectRow(fx.effects :+ EffectBound(effect, args))))
      case other => other

  /** Fresh variables for the `of` parameters of `owner` (a data type, an effect or a capability),
    * each keeping its declared bound, which every type it is later solved to must satisfy.
    */
  def freshArgs(s: TyperState, owner: SymbolId): (TyperState, List[Type]) =
    val (s1, _, args) =
      rebuildAll(s, typeParamsOf(s, owner).map(Type.TypeRef(_, Nil)), Subst(IntMap.empty, Set(owner)), IntMap.empty)
    (s1, args)

  /** The field types of `variant` at `args`, each declared type first passed through `declared`. */
  def fieldTypes(
      s: TyperState,
      data: SymbolId,
      args: List[Type],
      variant: SymbolId,
      declared: Type => Type = identity
  ): (TyperState, List[Type]) =
    val sub = Subst(Subst.of(typeParamsOf(s, data), args), Set(variant))
    mapAccum(s, s.symbols.fieldsOf(variant).toList)((si, f) => instantiate(si, declared(f.info), sub))

  /** `Self` as declared: `data` at its own type parameters. Only a field of this type is recursive
    * (Rule 7.4.a): at `Tree of Tree of u64` a field `value: Tree of u64` is an element, and so is a
    * field `M of (B, A)` in `M of (A, B)`. Decided before instantiation, where they differ.
    */
  def declaredSelf(symbols: SymbolTable, data: SymbolId): Type =
    Type.TypeRef(data, symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil)))

  /** `t` with each occurrence of `self` replaced by `by`. */
  private def replaceSelf(t: Type, self: Type, by: Type): Type =
    if t == self then by else t.mapChildren(replaceSelf(_, self, by))

  /** An arrow's left-hand side is always its parameter list, a `TupleType` of any arity, so a
    * single `()` parameter and no parameters stay distinct.
    */
  /** A signature's parameter count (0 for a non-function). */
  def arity(t: Type): Int = paramTypes(t).size

  /** A signature's parameter types (none for a non-function). */
  def paramTypes(t: Type): List[Type] =
    t match
      case Type.ArrowType(lhs, _, _) => params(lhs)
      case _                         => Nil

  def params(lhs: Type): List[Type] =
    lhs match
      case Type.TupleType(ts) => ts
      case single             => List(single)
