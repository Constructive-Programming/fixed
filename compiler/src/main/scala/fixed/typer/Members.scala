package fixed.typer

import fixed.core.{SymKind, SymbolId, Type}

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
    def all(ts: List[Type], s0: TyperState, m0: Map[SymbolId, Type]) =
      ts.foldLeft((s0, m0, List.empty[Type])) { case ((si, mi, acc), ti) =>
        val (si1, mi1, ti1) = go(si, ti, sub, mi)
        (si1, mi1, ti1 :: acc)
      } match
        case (s1, m1, acc) => (s1, m1, acc.reverse)
    def rebuild(parts: List[Type])(mk: List[Type] => Type) =
      val (s1, m1, out) = all(parts, s, m)
      (s1, m1, mk(out))
    t match
      case Type.TypeRef(id, Nil) if m.contains(id) => (s, m, m(id))
      case Type.TypeRef(id, Nil)
          if s.symbols(id).kind == SymKind.TypeParam && sub.freshOwners(s.symbols(id).owner) =>
        val (u, v) = s.unifier.fresh(numeric = isNumeric(s, t), default = None)
        (s.copy(unifier = u), m.updated(id, v), v)
      case Type.TypeRef(id, args)    => rebuild(args)(Type.TypeRef(id, _))
      case Type.CapRef(id, args)     => rebuild(args)(Type.CapRef(id, _))
      case Type.ArrowType(l, r, fx)  => rebuild(List(l, r))(xs => Type.ArrowType(xs.head, xs(1), fx))
      case Type.TupleType(ts)        => rebuild(ts)(Type.TupleType(_))
      case Type.IsBound(bs)          => rebuild(bs)(Type.IsBound(_))
      case Type.CapType(r)           => rebuild(r.toList)(xs => Type.CapType(xs.headOption))
      case Type.Layer(d, args, seed) => rebuild(args :+ seed)(xs => Type.Layer(d, xs.init, xs.last))
      case Type.SelfType(of)         => rebuild(of.toList)(xs => selfOf(sub.self.getOrElse(t), xs.headOption))
      case Type.PartType             => (s, m, sub.part.getOrElse(t))
      case other                     => (s, m, other)

  /** `Self of B`: the receiver with its element (last) argument replaced. */
  private def selfOf(self: Type, of: Option[Type]): Type =
    (self, of) match
      case (Type.TypeRef(id, args), Some(b)) if args.nonEmpty => Type.TypeRef(id, args.init :+ b)
      case _                                                  => self

  /** Type variables `owner` declared (`of` parameters), in declaration order. */
  def typeParamsOf(s: TyperState, owner: SymbolId): List[SymbolId] =
    s.symbols.membersOf(owner).filter(_.kind == SymKind.TypeParam).map(_.id).toList

  /** The instantiated type of instance member `name` on `recv`, if any. */
  def instance(s: TyperState, recv: Type, name: String): Option[(TyperState, Type)] =
    s.unifier.resolve(recv) match
      case Type.TupleType(ts) => s.symbols.scope.get("Tuple").flatMap(t => instance(s, Type.TypeRef(t, ts), name))
      case t @ Type.TypeRef(id, args) =>
        val d = s.symbols(id)
        d.kind match
          case SymKind.Data =>
            field(s, id, args, name)
              .orElse(capMember(s, s.symbols.satisfies.getOrElse(d.name, Nil), t, name, Set.empty))
              .orElse(Option.when(name == "fold")(derivedFold(s, id, args)).flatten)
          case SymKind.TypeParam =>
            val bounds = d.info match
              case Type.IsBound(bs) => bs
              case _                => Nil
            capMember(s, bounds, t, name, Set.empty)
          case _ => None
      case t @ Type.PrimType(n) =>
        val numeric = if isNumeric(s, t) then s.symbols.scope.get("Numeric").map(Type.CapRef(_, Nil)).toList else Nil
        capMember(s, s.symbols.satisfies.getOrElse(n, Nil) ++ numeric, t, name, Set.empty)
      case t @ Type.CapRef(_, _) => capMember(s, List(t), t, name, Set.empty)
      case Type.IsBound(bs)      => capMember(s, bs, recv, name, Set.empty)
      case _                     => None

  /** A field of a single-variant data type, as a zero-argument member. */
  private def field(s: TyperState, data: SymbolId, args: List[Type], name: String): Option[(TyperState, Type)] =
    s.symbols.membersOf(data).filter(_.kind == SymKind.Variant).toList match
      case variant :: Nil =>
        s.symbols.membersOf(variant.id).find(f => f.kind == SymKind.Field && f.name == name).map { f =>
          val (s1, t) = instantiate(s, f.info, Subst(typeParamsOf(s, data).zip(args).toMap, Set.empty))
          (s1, Type.ArrowType(Type.UnitType, t, fixed.core.EffectRow.empty))
        }
      case _ => None

  /** Method `name` in `caps` or their `extends` chains, instantiated for receiver `recv`. */
  private def capMember(
      s: TyperState,
      caps: List[Type],
      recv: Type,
      name: String,
      seen: Set[SymbolId]
  ): Option[(TyperState, Type)] =
    caps.iterator
      .collect { case Type.CapRef(c, capArgs) if !seen(c) => (c, capArgs) }
      .map { case (c, capArgs) =>
        val recvArgs = recv match
          case Type.TypeRef(_, as) => as
          case _                   => Nil
        val params = typeParamsOf(s, c)
        val args = if capArgs.nonEmpty then capArgs else recvArgs.takeRight(params.size)
        val part = capArgs.lastOption.orElse(recvArgs.lastOption).orElse(Some(recv))
        s.symbols.membersOf(c).find(m => m.kind == SymKind.Method && m.name == name) match
          case Some(m) =>
            Some(instantiate(s, m.info, Subst(params.zip(args).toMap, Set(m.id), Some(recv), part)))
          case None =>
            val supers = s.symbols(c).info match
              case Type.IsBound(bs) => bs
              case _                => Nil
            capMember(s, supers, recv, name, seen + c)
      }
      .collectFirst { case Some(found) => found }

  /** `fold` derived from a data type's variants (a catamorphism): one function per variant, its
    * fields with each recursive occurrence of the type replaced by the result type `R`.
    */
  private def derivedFold(s: TyperState, data: SymbolId, args: List[Type]): Option[(TyperState, Type)] =
    val variants = s.symbols.membersOf(data).filter(_.kind == SymKind.Variant).toList
    Option.when(variants.nonEmpty) {
      val (u, r) = s.unifier.fresh(numeric = false, default = None)
      val (s1, cases) = variants.foldLeft((s.copy(unifier = u), List.empty[Type])) { case ((si, acc), v) =>
        val (si1, fields) = fieldTypes(si, data, args, v.id)
        (si1, Type.ArrowType(product(fields.map(replaceSelf(_, data, r))), r, fixed.core.EffectRow.empty) :: acc)
      }
      (s1, Type.ArrowType(product(cases.reverse), r, fixed.core.EffectRow.empty))
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
        s.symbols.membersOf(owner).find(m => m.kind == SymKind.Variant && m.name == name) match
          case Some(v)                  => Some(construct(s, owner, v.id, layer))
          case None if name == "unfold" => Some(derivedUnfold(s, owner))
          case None                     => None
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
    val (s1, args) = freshArgs(s, data)
    val (u, seed) = s1.unifier.fresh(numeric = false, default = None)
    val step = Type.ArrowType(seed, Type.Layer(data, args, seed), fixed.core.EffectRow.empty)
    (
      s1.copy(unifier = u),
      Type.ArrowType(Type.TupleType(List(seed, step)), Type.TypeRef(data, args), fixed.core.EffectRow.empty)
    )

  private def freshArgs(s: TyperState, data: SymbolId): (TyperState, List[Type]) =
    typeParamsOf(s, data).foldLeft((s, List.empty[Type])) { case ((si, acc), p) =>
      val (u, v) = si.unifier.fresh(numeric = isNumeric(si, Type.TypeRef(p, Nil)), default = None)
      (si.copy(unifier = u), acc :+ v)
    }

  private def fieldTypes(s: TyperState, data: SymbolId, args: List[Type], variant: SymbolId): (TyperState, List[Type]) =
    val sub = Subst(typeParamsOf(s, data).zip(args).toMap, Set(variant))
    s.symbols.membersOf(variant).filter(_.kind == SymKind.Field).foldLeft((s, List.empty[Type])) {
      case ((si, acc), f) =>
        val (si1, t) = instantiate(si, f.info, sub)
        (si1, acc :+ t)
    }

  private def replaceSelf(t: Type, data: SymbolId, by: Type): Type =
    t match
      case Type.TypeRef(`data`, _)  => by
      case Type.TypeRef(id, args)   => Type.TypeRef(id, args.map(replaceSelf(_, data, by)))
      case Type.ArrowType(l, r, fx) => Type.ArrowType(replaceSelf(l, data, by), replaceSelf(r, data, by), fx)
      case Type.TupleType(ts)       => Type.TupleType(ts.map(replaceSelf(_, data, by)))
      case other                    => other

  /** An arrow's left-hand side as its parameter list. */
  def params(lhs: Type): List[Type] =
    lhs match
      case Type.UnitType      => Nil
      case Type.TupleType(ts) => ts
      case single             => List(single)

  /** A parameter or field list as one arrow left-hand side. */
  def product(ts: List[Type]): Type =
    ts match
      case Nil      => Type.UnitType
      case t :: Nil => t
      case _        => Type.TupleType(ts)
