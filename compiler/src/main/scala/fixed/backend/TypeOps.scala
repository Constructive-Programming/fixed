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
  def solve(params: Set[SymbolId], declared: Type, concrete: Type, acc: Map[SymbolId, Type]): Map[SymbolId, Type] =
    declared match
      case Type.TypeRef(p, Nil) if params(p)         => if acc.contains(p) then acc else acc.updated(p, concrete)
      case _ if declared.ordinal == concrete.ordinal =>
        declared.children.zip(concrete.children).foldLeft(acc)((a, dc) => solve(params, dc._1, dc._2, a))
      case _ => acc

  /** `t` with each type that is a key of `by` replaced by its value. */
  def replaceAll(t: Type, by: Map[Type, Type]): Type =
    if by.isEmpty then t else by.getOrElse(t, t.mapChildren(replaceAll(_, by)))

  /** Whether `t` mentions data type `data` anywhere. */
  def mentions(t: Type, data: SymbolId): Boolean = isSelf(t, data) || t.children.exists(mentions(_, data))

  /** `t` with every occurrence of data type `data` replaced by `by`. */
  def replace(t: Type, data: SymbolId, by: Type): Type =
    if isSelf(t, data) then by else t.mapChildren(replace(_, data, by))

  def isSelf(t: Type, data: SymbolId): Boolean =
    t match
      case Type.TypeRef(`data`, _) => true
      case _                       => false
