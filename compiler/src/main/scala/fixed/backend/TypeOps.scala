package fixed.backend

import fixed.core.{SymbolId, Type}
import fixed.typer.Members

/** Operations on typer types that lowering and the generated members share. */
object TypeOps:

  /** The parameter types and the result type of a declared function type. */
  def signature(info: Type): (List[Type], Type) =
    info match
      case Type.ArrowType(lhs, r, _) => (Members.params(lhs), r)
      case other                     => (Nil, other)

  /** Whether declared parameter type `t` is an anonymous bound (`is Folding of String`). */
  def anonymous(t: Type): Boolean =
    t match
      case _: Type.IsBound | _: Type.CapRef => true
      case _                                => false

  /** Replace type parameters by their concrete types. */
  def subst(t: Type, sigma: Map[SymbolId, Type]): Type =
    t match
      case Type.TypeRef(p, Nil) if sigma.contains(p) => sigma(p)
      case other                                     => other.mapChildren(subst(_, sigma))

  /** Match a declared signature against a concrete type, solving the declared type parameters. */
  def solve(
      params: Set[SymbolId],
      declared: Type,
      concrete: Type,
      acc: Map[SymbolId, Type],
      boundsOf: SymbolId => List[Type] = _ => Nil
  ): Map[SymbolId, Type] =
    def step(a: Map[SymbolId, Type], dc: (Type, Type)) = solve(params, dc._1, dc._2, a, boundsOf)
    declared match
      case Type.TypeRef(p, Nil) if params(p) =>
        /** A parameter's bound arguments (`C is Folding of A`) are the type's element arguments. */
        val elements = (boundsOf(p), concrete) match
          case (List(Type.CapRef(_, as)), Type.TypeRef(_, cs)) if as.nonEmpty && cs.size >= as.size =>
            as.zip(cs.takeRight(as.size))
          case _ => Nil
        val withP = if acc.contains(p) then acc else acc.updated(p, concrete)
        elements.foldLeft(withP)(step)
      case _ if declared.ordinal == concrete.ordinal =>
        declared.children.zip(concrete.children).foldLeft(acc)(step)
      case _ => acc

  /** `t` with each type that is a key of `by` replaced by its value. */
  def replaceAll(t: Type, by: Map[Type, Type]): Type =
    if by.isEmpty then t else by.getOrElse(t, t.mapChildren(replaceAll(_, by)))

  /** Polymorphic recursion, which would lower an endless chain of clones or instances. A call of a function with
    * type parameters `params` and signature `info` has the type `written` in its caller, and `frame` holds each type
    * parameter of the enclosing clones and instances as the type it derives from. Returns `frame` with each of
    * `params` as the type it derives from, or as itself where its argument mentions no type parameter (`generic`),
    * and whether one of them, as derived or as written, nests a parameter of `params` (`N of A` calling itself at
    * `List of A`; as written also where `frame` has replaced the parameter by what an outer clone derives it from). A chain that
    * passes known types (`f` calling `g(7)`, `g` calling `f([b])`) ends, and is not taken for one.
    */
  def derived(
      params: List[SymbolId],
      info: Type,
      written: Option[Type],
      frame: Map[SymbolId, Type],
      generic: Type => Boolean
  ): (Map[SymbolId, Type], Boolean) =
    val source = written.fold(Map.empty[SymbolId, Type])(solve(params.toSet, info, _, Map.empty))
    val own = params.map(Type.TypeRef(_, Nil))
    val types =
      params.zip(own).map((p, self) => p -> source.get(p).map(subst(_, frame)).filter(generic).getOrElse(self))
    val nests = (t: Type) => own.exists(q => t != q && mentions(t, q))
    (frame ++ types, types.exists((_, t) => nests(t)) || source.values.exists(nests))

  /** Whether `t` mentions type `self` anywhere. */
  def mentions(t: Type, self: Type): Boolean = t == self || t.children.exists(mentions(_, self))
