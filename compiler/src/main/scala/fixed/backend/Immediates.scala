package fixed.backend

import scala.collection.mutable

import fixed.core.{SymKind, SymbolId, Type}

/** Which data types are immediates (`spec/compilation_model.md` Rule CM3.5, provisional): a type
  * whose fields hold no reference and whose tag (one byte when it has more than one variant) plus
  * the fields of its largest variant, each at its rest width (Rule CM3.6), fit 8 bytes. Built only
  * for a type whose field layout does not depend on its type arguments: a field whose declared type
  * mentions a type parameter makes the type a cell here, because a baseline version holding
  * `Cell of T` would need `T`'s width, which the witnesses `imm` and `ref` do not carry (the witness
  * family `imm1` to `imm8` and the accessors of Rule CM3.7 are not built). So the classification is
  * one per data type, whatever its arguments, and a clone and a baseline version agree on it
  * (Rule CM3.4).
  */
private[backend] trait Immediates:
  this: Lower =>

  /** Each data type classified so far: its layout if it is an immediate, and why. */
  private val classified = mutable.LinkedHashMap.empty[SymbolId, (Option[CType.Imm], String)]

  /** The types being classified: a cell to the types they reach, which makes every recursive type one. */
  private val classifying = mutable.Set.empty[SymbolId]

  /** The data types classified, by name, each with its layout if it is an immediate and why. */
  def immediates: List[(String, Option[CType.Imm], String)] =
    classified.toList.map { case (d, (imm, why)) => (symbols(d).name, imm, why) }.sortBy(_._1)

  /** The immediate layout of `t`, if `t` is a data type that is an immediate. */
  protected def immediate(t: Type): Option[CType.Imm] =
    t match
      case Type.TypeRef(d, _) if symbols(d).kind == SymKind.Data && !isStringType(t) => classify(d)._1
      case _                                                                         => None

  /** `t` as a data type with its arguments if its values are cells: not an immediate. */
  protected def cellData(t: Type): Option[(SymbolId, List[Type])] = dataOf(t).filter(_ => immediate(t).isEmpty)

  private def classify(d: SymbolId): (Option[CType.Imm], String) =
    if classifying(d) then (None, "recursive")
    else
      classified.getOrElse(
        d, {
          classifying += d
          val c = layout(d)
          classifying -= d
          classified(d) = c
          c
        }
      )

  private def layout(d: SymbolId): (Option[CType.Imm], String) =
    val variants = symbols.variantsOf(d).toList
    val own = symbols.typeParamsOf(d).toSet
    def mentions(t: Type, p: SymbolId => Boolean): Boolean =
      t match
        case Type.TypeRef(s, _) if p(s) => true
        case Type.SelfType(_)           => p(d)
        case _                          => t.children.exists(mentions(_, p))
    val fields = variants.map(v => symbols.fieldsOf(v.id).toList.zip(fieldTypes(d, Nil, v.id)))

    /** A field's rest width in bytes, or why it has none. */
    def width(name: String, declared: Type, t: Type): Either[String, Int] =
      if mentions(declared, _ == d) || dataOf(t).exists((e, _) => classifying(e)) then Left("recursive")
      else if mentions(declared, own) then Left(s"field `$name` takes its layout from a type argument (not built)")
      else Scalars.scalar(t).orElse(immediate(t)).flatMap(Layout.width).toRight(s"field `$name` holds a reference")
    if variants.isEmpty then (None, "no variants")
    else if variants.size > 256 then (None, "more than 256 variants: the tag is one byte")
    else
      val tag = if variants.size > 1 then 1 else 0
      val widths = fields.map(_.map((f, t) => width(f.name, f.info, t)))
      val whys = widths.flatten.collect { case Left(why) => why }
      whys.find(_ == "recursive").orElse(whys.headOption) match
        case Some(why) => (None, why)
        case None      =>
          val size = tag + widths.map(_.map(_.getOrElse(0)).sum).max
          def bytes(n: Int) = if n == 1 then "1 byte" else s"$n bytes"
          if size > 8 then (None, s"${bytes(size)}, more than 8")
          else
            val w = List(1, 2, 4, 8).find(_ >= size).getOrElse(8)
            (Some(CType.Imm(w, tag == 1)), s"${bytes(size)}: tag $tag, fields ${size - tag}")
