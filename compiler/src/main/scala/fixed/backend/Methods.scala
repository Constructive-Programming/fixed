package fixed.backend

import fixed.ast.{Tree, Trees}
import fixed.core.*
import fixed.typer.Resolution
import fixed.util.Span

/** Calls with a receiver or a qualifier. `x.m(…)` ([[method]]) is a `builtin` intrinsic, a field
  * read, a capability's table entry, a `satisfies` body, a generated member, a scalar primitive or
  * a runtime function; `Q.m(…)` ([[static]]) is a constructor, a capability constructor, an
  * effect operation ([[Handlers]]) or a `std.io` call.
  */
private[backend] trait Methods:
  this: Lower =>

  private[backend] def method(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val recvType = typeOf(m.receiver, sc)
    val args = m.args.getOrElse(Nil)
    (m.receiver, unit.resolutionOf(m)) match
      case (Trees.Ident("builtin", _), _) if !sc.env.contains("builtin") => intrinsic(m, sc)(k)
      case (_, None) if productField(recvType, m.method).isDefined       => fieldRead(recvType, m, sc)(k)
      case (_, resolution) => resolved(m, recvType, args, resolution, sc)(k)

  private def resolved(
      m: Trees.MethodCall,
      recvType: Type,
      args: List[Tree],
      resolution: Option[Resolution],
      sc: Scope
  )(
      k: K
  ): Expr =
    lazy val listLike = dataOf(recvType).exists((d, _) => listShaped(d))

    /** Whether the receiver is a scalar, a `String` or a data value, whose `show` and `compare` the backend builds. */
    lazy val known = Scalars.scalar(recvType).isDefined || isStringType(recvType) || dataOf(recvType).isDefined
    resolution match
      case Some(Resolution.Field(variant, index)) =>
        dataOf(recvType) match
          case Some((data, dargs)) =>
            val at = Layout.fieldsOf(ctype(recvType, m.span), fieldTypes(data, dargs, variant).map(ctype(_, m.span)))
            expr(m.receiver, sc)(r => bind(Rhs.Field(r, at(index)), m, sc)(k))
          case None => unsupported(m.span, s"field `${m.method}` of ${symbols.render(recvType)}", k)
      case Some(Resolution.DerivedFold(_, para)) =>
        generatedCall(recvType, m, sc, Some(typeOf(m, sc)))(derivedFold(_, _, _, m.span, para))(k)
      case Some(Resolution.CapMember(_, member)) if isTypeParam(recvType) =>
        tableMethod(m, member, recvType, args, sc)(k)
      case Some(Resolution.CapMember(_, member)) if hasBody(recvType, member, fromPrelude = false) =>
        bodyCall(m, member, recvType, sc)(k)
      case Some(Resolution.CapMember(cap, member)) =>
        (symbols.preludeName(cap), m.method, args) match
          case ("Concat", "concat", List(arg)) =>
            expr(m.receiver, sc)(r => expr(arg, sc)(a => runtime("fx_str_concat", List(r, a), m, sc)(k)))
          case ("Show", "show", Nil) if known         => expr(m.receiver, sc)(r => showValue(recvType, r)(k))
          case ("Clone", "clone", Nil) if known       => expr(m.receiver, sc)(k)
          case ("Ord", "compare", List(arg)) if known =>
            expr(m.receiver, sc)(r => expr(arg, sc)(a => compareValues(recvType, r, a)(k)))
          case ("Eq", "eq", List(arg)) if dataOf(recvType).isDefined || isStringType(recvType) =>
            expr(m.receiver, sc)(r =>
              expr(arg, sc)(a =>
                equality(
                  recvType,
                  r,
                  a
                ).fold(unsupported(m.span, s"`eq` on ${symbols.render(recvType)}", k))(bind(_, m, sc)(k))
              )
            )
          case ("Numeric", "negate", Nil) if Scalars.scalar(recvType).isDefined =>
            expr(m.receiver, sc)(r => bind(Rhs.Prim(PrimOp.Neg, List(r)), m, sc)(k))
          case (c, name, List(arg)) if Scalars.scalar(recvType).isDefined && Tables.scalarBinary.contains((c, name)) =>
            val op = Tables.scalarBinary((c, name))
            expr(m.receiver, sc)(r => expr(arg, sc)(a => bind(Rhs.Prim(op, List(r, a)), m, sc)(k)))
          case ("Folding", "fold", List(_, _)) if listLike =>
            generatedCall(recvType, m, sc, Some(typeOf(m, sc)))(foldLeft(_, _, _, m.span))(k)
          case (_, "fold", _) if mappedFold(cap, recvType, args.size).isDefined =>
            val order = mappedFold(cap, recvType, args.size)
            generatedCall(recvType, m, sc, Some(typeOf(m, sc)), order)(derivedFold(_, _, _, m.span))(k)
          case ("Functor", "map", List(_)) if dataOf(recvType).isDefined =>
            val newElem = dataOf(typeOf(m, sc)).flatMap(_._2.lastOption).getOrElse(Type.UnitType)
            generatedCall(recvType, m, sc, Some(newElem))(derivedMap(_, _, _, m.span))(k)
          case ("Sequencing", "length", Nil) if listLike =>
            generatedCall(recvType, m, sc)((d, a, _) => length(d, a, typeOf(m, sc), m.span))(k)
          case ("Folding", "find", List(_)) if listLike =>
            capOf(m) match
              case Some(opt) =>
                val dargs = dataOf(recvType).fold(Nil)(_._2)
                generatedCall(recvType, m, sc)((d, a, _) =>
                  find(d, a, opt, TypeOps.replaceAll(typeOf(m, sc), dargs.zip(a).toMap), m.span)
                )(k)
              case None =>
                unsupported(m.span, s"`find` returning ${symbols.render(unit.typeOf(m).getOrElse(Type.NeverType))}", k)
          case (_, name, Nil) if productField(recvType, name).isDefined => fieldRead(recvType, m, sc)(k)
          case _ if hasBody(recvType, member, fromPrelude = true)       => bodyCall(m, member, recvType, sc)(k)
          case (c, name, _)                                             =>
            unsupported(m.span, s"member $c.$name on ${symbols.render(recvType)}", k)
      case None | Some(_: Resolution.NestedFn) => unsupported(m.span, s"member `${m.method}`", k)

  /** `m`, resolved to capability member `member`, on a receiver of type parameter `t`, through the
    * table of its bound set that holds the member: the witness of each of the member's own type
    * parameters, solved from the argument and result types, then the receiver and the arguments
    * (Rule CM4.3). A member type parameter the types do not solve is `B005`.
    */
  private def tableMethod(m: Trees.MethodCall, member: SymbolId, t: Type, args: List[Tree], sc: Scope)(k: K): Expr =
    val d = symbols(member)
    val own = symbols.typeParamsOf(member)
    val solved = ownTypeArgs(member, args.map(typeOf(_, sc)), typeOf(m, sc))
    own.find(!solved.contains(_)) match
      case Some(q) => unsupported(m.span, s"`${m.method}` whose `${symbols(q).name}` the call does not determine", k)
      case None    =>
        val witnesses = own.map(solved andThen witnessAtom)
        expr(m.receiver, sc)(r =>
          all(args, sc)(as => tableCall(t, d.owner, m.method, witnesses ++ (r :: as), m, sc)(k))
        )

  /** `m`, a call of capability `member` on `recvType`, through its `satisfies` body. */
  private def bodyCall(m: Trees.MethodCall, member: SymbolId, recvType: Type, sc: Scope)(k: K): Expr =
    val (argTypes, args) = (m.args.getOrElse(Nil).map(typeOf(_, sc)), m.args.getOrElse(Nil))
    directBody(recvType, member, argTypes, typeOf(m, sc)) match
      case Some((f, ws)) =>
        expr(m.receiver, sc)(rv => all(args, sc)(as => bind(Rhs.Call(f, ws ++ (rv :: as), runtime = false), m, sc)(k)))
      case None =>
        generatedCall(recvType, m, sc, ownTypeArg(member, argTypes, typeOf(m, sc)))(dataBody(member))(k)

  private def fieldRead(recvType: Type, m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    expr(m.receiver, sc)(r => bind(Rhs.Field(r, productField(recvType, m.method).get), m, sc)(k))

  /** The slot of field `name` of an auto-flat-struct or single-variant data value. */
  private def productField(t: Type, name: String): Option[Int] =
    t match
      case Type.CapRef(_, _) => productFields(t).map(_._2.indexWhere(_._1 == name)).filter(_ >= 0)
      case _                 =>
        dataOf(t).flatMap { (data, args) =>
          symbols.variantsOf(data).toList match
            case List(v) =>
              val names = symbols.fieldsOf(v.id).map(_.name).toList
              val types = fieldTypes(data, args, v.id)
              val at = Layout.fieldsOf(ctype(t, Span.NoSpan), types.map(ctype(_, Span.NoSpan)))
              Option(names.indexOf(name)).filter(_ >= 0).map(at)
            case _ => None
        }

  /** `builtin.parseU64(s)`: the C side parses; the result is built with the constructors that
    * `ok` and `err` of the result's capability (a `Result`) map to on the representing data type.
    */
  private def intrinsic(m: Trees.MethodCall, sc: Scope)(k: K): Expr =
    val result = typeOf(m, sc)
    (m.method, m.args.getOrElse(Nil), dataOf(result), capOf(m)) match
      case ("parseU64", List(arg), Some(_), Some(cap)) =>
        val tagOf = (ctor: String) => capVariant(cap, ctor, result).getOrElse(0)
        expr(arg, sc) { a =>
          val (v, ok) = (fresh("n", CType.Int(64, signed = false)), fresh("ok", CType.Bool))
          val (yes, no) = (fresh("yes", ctype(result, m.span)), fresh("no", ctype(result, m.span)))
          Expr.Let(
            v,
            Rhs.Call("fx_parse_u64", List(a), runtime = true),
            Expr.Let(
              ok,
              Rhs.Call("fx_parse_ok", Nil, runtime = true),
              bind(
                Rhs.If(
                  ok,
                  Expr.Let(yes, Rhs.Con(tagOf("ok"), List(v)), Expr.Ret(yes)),
                  Expr.Let(no, Rhs.Con(tagOf("err"), List(Atom.StrC("not a number"))), Expr.Ret(no))
                ),
                m,
                sc
              )(k)
            )
          )
        }
      case _ => unsupported(m.span, s"`builtin.${m.method}`", k)

  /** Call the member `gen` generates for the receiver's data type ([[member]]), whose own type
    * argument is `r` if it has one: its witnesses, the receiver, then the arguments, in the order
    * `order` gives (the argument handling each variant) if any.
    */
  private def generatedCall(
      recvType: Type,
      m: Trees.MethodCall,
      sc: Scope,
      r: Option[Type] = None,
      order: Option[List[Int]] = None
  )(gen: (SymbolId, List[Type], Type) => String)(k: K): Expr =
    dataOf(recvType) match
      case Some((data, dargs)) =>
        val (f, ws) = member(data, dargs, r)(gen(data, _, _))
        expr(m.receiver, sc)(rv =>
          all(m.args.getOrElse(Nil), sc)(as =>
            bind(Rhs.Call(f, ws ++ (rv :: order.fold(as)(_.map(as(_)))), runtime = false), m, sc)(k)
          )
        )
      case None => unsupported(m.span, s"`${m.method}` on ${symbols.render(recvType)}", k)

  /** Data constructors, derived `unfold`, capability constructors resolved by the typer (through the
    * table of a type parameter when the value is one, Rule CM4.5), and `std.io`'s `Console`.
    */
  private[backend] def static(s: Trees.StaticCall, sc: Scope)(k: K): Expr =
    val args = s.args.getOrElse(Nil)
    lazy val result = typeOf(s, sc)
    val owner = globals.get(s.qualifier)
    val resolved = unit.typing.flatMap(_.constructors.find(_._1 eq s)).map((_, d, v) => (d, v))
    (owner.exists(symbols.stdIo("Console").contains), s.method, args) match
      case (true, "printLine", List(arg)) => expr(arg, sc)(a => runtime("fx_print_line", List(a), s, sc)(k))
      case (true, "readLine", Nil)        => runtime("fx_read_line", Nil, s, sc)(k)
      case _                              =>
        owner.filter(symbols(_).kind == SymKind.Data).map(d => d -> s.method).orElse(resolved) match
          case Some((data, "unfold")) if !hasVariant(data, "unfold") =>
            val seed = args.headOption.fold(Type.UnitType)(typeOf(_, sc))
            all(args, sc) { as =>
              val (f, ws) = member(data, dataOf(typeOf(s, sc)).fold(Nil)(_._2), Some(seed))(
                derivedUnfold(data, _, _, s.span)
              )
              bind(Rhs.Call(f, ws ++ as, runtime = false), s, sc)(k)
            }
          case Some((data, variant)) if hasVariant(data, variant) =>
            val tag = symbols.variantsOf(data).indexWhere(_.name == variant)
            construct(tag, args, s, sc)(k)
          case _ =>
            owner.map(symbols(_)) match
              case Some(cap) if cap.kind == SymKind.Cap && isTypeParam(result) =>
                all(args, sc)(as => tableCall(result, cap.id, s.method, as, s, sc)(k))
              case Some(cap) if cap.kind == SymKind.Cap && capVariant(cap.id, s.method, result).isDefined =>
                construct(capVariant(cap.id, s.method, result).getOrElse(0), args, s, sc)(k)
              case Some(cap) if represent.constructor(cap.id).exists(_.name == s.method) =>
                construct(0, args, s, sc)(k)
              case Some(effect) if effect.kind == SymKind.Effect => operation(s, effect.id, args, sc)(k)

              /** `N.fromI64(n)`, `N is Numeric`: a new scalar (a `u64` is unsigned), a conversion, or its table. */
              case None if s.method == "fromI64" && unit.typeOf(s).exists(numericParam) =>
                expr(args.head, sc)(n =>
                  ctype(result, s.span) match
                    case CType.Int(64, _)              => bind(Rhs.Move(n), s, sc)(k)
                    case _: CType.Int | _: CType.Float => bind(Rhs.Prim(PrimOp.Convert, List(n)), s, sc)(k)
                    case _ => tableCall(result, symbols.prelude("Numeric"), "fromI64", List(n), s, sc)(k)
                )
              case _ => unsupported(s.span, s"`${s.qualifier}.${s.method}`", k)

  private def construct(tag: Int, args: List[Tree], t: Tree, sc: Scope)(k: K): Expr =
    all(args, sc)(as => bind(Rhs.Con(tag, as), t, sc)(k))

  private def hasVariant(data: SymbolId, name: String): Boolean = symbols.variantsOf(data).exists(_.name == name)

  private def runtime(name: String, args: List[Atom], t: Tree, sc: Scope)(k: K): Expr =
    bind(Rhs.Call(name, args, runtime = true), t, sc)(k)
