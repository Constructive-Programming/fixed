package fixed.backend

import scala.annotation.tailrec
import scala.collection.mutable

import fixed.CompilationUnit
import fixed.ast.{Tree, Trees}
import fixed.backend.Atom.Var
import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.typer.Members
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Typed tree to Core (slice §4.1–§4.3), in continuation-passing style: `expr(t, env)(k)` lowers `t`
  * and hands its atom to `k`, which builds the rest of the function. Starts at `main` and lowers
  * the functions it reaches. Constructs beyond the current milestone are `B005`.
  */
final class Lower(unit: CompilationUnit):
  private type Env = Map[String, Var]
  private type K = Atom => Expr

  private val ids = Iterator.from(1)
  private val problems = mutable.ArrayBuffer.empty[Diagnostic]
  private val symbols = unit.symbols

  private val decls: Map[String, Trees.FnDecl] =
    unit.tree.toList
      .flatMap { case Trees.CompilationUnit(items, _) => items; case _ => Nil }
      .collect { case f: Trees.FnDecl => f.name -> f }
      .toMap

  /** Every function reachable from `main`, or the problems found. */
  def program: Either[List[Diagnostic], List[Fn]] =
    if !decls.contains("main") then Left(List(problem(DiagCode.B003, Span.NoSpan, "no `main` function")))
    else
      val fns = drain(List("main"), Map.empty)
      if problems.isEmpty then Right(fns.values.toList.sortBy(_.name)) else Left(problems.toList)

  @tailrec private def drain(todo: List[String], done: Map[String, Fn]): Map[String, Fn] =
    todo match
      case Nil                           => done
      case n :: rest if done.contains(n) => drain(rest, done)
      case n :: rest                     =>
        val fn = function(decls(n))
        drain(Core.callees(fn.body).toList.sorted ++ rest, done.updated(n, fn))

  private def function(decl: Trees.FnDecl): Fn =
    val id = symbols.scope(decl.name)
    val (paramTypes, ret) = symbols(id).info match
      case Type.ArrowType(lhs, r, _) => (Members.params(lhs), r)
      case other                     => (Nil, other)
    if symbols.typeParamsOf(id).nonEmpty then
      unsupported(decl.span, s"generic function `${decl.name}` (arrives in 4.0.M3)")
    val params = decl.params.zip(paramTypes).map((p, t) => fresh(p.name, ctype(t, p.span)))
    val env = decl.params.map(_.name).zip(params).toMap
    val body = decl.body.fold(Expr.Ret(Atom.UnitC))(b => expr(b, env)(Expr.Ret(_)))
    Fn(decl.name, params, ctype(ret, decl.span), body)

  private def expr(t: Tree, env: Env)(k: K): Expr =
    t match
      case l: Trees.IntLit =>
        ctype(typeOf(l), l.span) match
          case f: CType.Float => k(Atom.FloatC(BigDecimal(l.value), f))
          case c              => k(Atom.IntC(l.value, c))
      case l: Trees.FloatLit       => k(Atom.FloatC(l.value, ctype(typeOf(l), l.span)))
      case Trees.BoolLit(v, _)     => k(Atom.BoolC(v))
      case Trees.CharLit(c, _)     => k(Atom.IntC(BigInt(c.toInt), CType.Int(32, signed = false)))
      case Trees.StringLit(s, _)   => k(Atom.StrC(s))
      case Trees.UnitLit(_)        => k(Atom.UnitC)
      case Trees.Ident(name, span) => env.get(name).fold(unsupported(span, s"`$name` as a value", k))(k)
      case Trees.Block(stmts, _)   => block(stmts, env)(k)
      case b: Trees.BinOp          => binOp(b, env)(k)
      case u: Trees.UnaryOp        => unaryOp(u, env)(k)
      case i: Trees.IfExpr         =>
        expr(i.cond, env)(c => bind(Rhs.If(c, tail(i.thenBranch, env), tail(i.elseBranch, env)), i)(k))
      case a @ Trees.Apply(Trees.Ident(f, _), args, span) if !env.contains(f) && isFn(f) =>
        if decls.contains(f) then all(args, env)(as => bind(Rhs.Call(f, as, runtime = false), a)(k))
        else unsupported(span, s"call of `$f`, which has no body", k)
      case m: Trees.MethodCall => method(m, env)(k)
      case s: Trees.StaticCall => static(s, env)(k)
      case other               => unsupported(other.span, other.getClass.getSimpleName, k)

  private def tail(t: Tree, env: Env): Expr = expr(t, env)(Expr.Ret(_))

  private def block(stmts: List[Tree], env: Env)(k: K): Expr =
    stmts match
      case Nil                        => k(Atom.UnitC)
      case (l: Trees.LetExpr) :: rest =>
        l.pattern match
          case Trees.BinderPat(name, _) =>
            expr(l.init, env) {
              case v: Var => block(rest, env.updated(name, v))(k)
              case c      =>
                val x = fresh(name, c.tpe)
                Expr.Let(x, Rhs.Move(c), block(rest, env.updated(name, x))(k))
            }
          case Trees.WildcardPat(_) => expr(l.init, env)(_ => block(rest, env)(k))
          case other                => unsupported(other.span, s"pattern ${other.getClass.getSimpleName}", k)
      case last :: Nil => expr(last, env)(k)
      case e :: rest   => expr(e, env)(_ => block(rest, env)(k))

  private def binOp(b: Trees.BinOp, env: Env)(k: K): Expr =
    import Trees.BinOperator.*
    b.op match
      case And =>
        expr(b.lhs, env)(l => bind(Rhs.If(l, tail(b.rhs, env), Expr.Ret(Atom.BoolC(false))), b)(k))
      case Or =>
        expr(b.lhs, env)(l => bind(Rhs.If(l, Expr.Ret(Atom.BoolC(true)), tail(b.rhs, env)), b)(k))
      case op =>
        expr(b.lhs, env)(l =>
          expr(b.rhs, env)(r =>
            (op, l.tpe) match
              case (Eq, CType.Boxed)  => bind(Rhs.Call("fx_str_eq", List(l, r), runtime = true), b)(k)
              case (Neq, CType.Boxed) =>
                val eq = fresh("eq", CType.Bool)
                Expr.Let(
                  eq,
                  Rhs.Call("fx_str_eq", List(l, r), runtime = true),
                  bind(Rhs.Prim(PrimOp.Not, List(eq)), b)(k)
                )
              case (_, CType.Boxed) => unsupported(b.span, s"`${op.symbol}` on strings", k)
              case _                => bind(Rhs.Prim(Lower.prim(op), List(l, r)), b)(k)
          )
        )

  private def unaryOp(u: Trees.UnaryOp, env: Env)(k: K): Expr =
    val op = u.op match
      case Trees.UnaryOperator.Neg => PrimOp.Neg
      case Trees.UnaryOperator.Not => PrimOp.Not
    expr(u.operand, env)(a => bind(Rhs.Prim(op, List(a)), u)(k))

  /** Built-in members of scalars and `String`. ponytail: matched by name and receiver type; user
    * members arrive with the resolution table in 4.0.M3.
    */
  private def method(m: Trees.MethodCall, env: Env)(k: K): Expr =
    (m.method, m.args.getOrElse(Nil)) match
      case ("concat", List(arg)) =>
        expr(m.receiver, env)(r => expr(arg, env)(a => runtime("fx_str_concat", List(r, a), m)(k)))
      case ("show", Nil) => expr(m.receiver, env)(r => show(r, typeOf(m.receiver), m)(k))
      case _             => unsupported(m.span, s"member `${m.method}`", k)

  private def show(r: Atom, receiver: Type, m: Tree)(k: K): Expr =
    def widened(name: String, to: CType) =
      if r.tpe == to then runtime(name, List(r), m)(k)
      else
        val w = fresh("w", to)
        Expr.Let(w, Rhs.Prim(PrimOp.Widen, List(r)), runtime(name, List(w), m)(k))
    r.tpe match
      case _ if receiver == Type.PrimType("char") => runtime("fx_show_char", List(r), m)(k)
      case CType.Int(_, true)                     => widened("fx_show_i64", CType.Int(64, signed = true))
      case CType.Int(_, false)                    => widened("fx_show_u64", CType.Int(64, signed = false))
      case CType.Float(_)                         => widened("fx_show_f64", CType.Float(64))
      case CType.Bool                             => runtime("fx_show_bool", List(r), m)(k)
      case CType.Unit                             => k(Atom.StrC("()"))
      case CType.Boxed                            => k(r)

  /** Runtime effects (Rule E5.4). ponytail: matched by name; the resolution table (4.0.M3) checks
    * that the qualifier is `std.io`'s `Console`.
    */
  private def static(s: Trees.StaticCall, env: Env)(k: K): Expr =
    (s.qualifier, s.method, s.args.getOrElse(Nil)) match
      case ("Console", "print_line", List(arg)) => expr(arg, env)(a => runtime("fx_print_line", List(a), s)(k))
      case ("Console", "read_line", Nil)        => runtime("fx_read_line", Nil, s)(k)
      case _                                    => unsupported(s.span, s"`${s.qualifier}.${s.method}`", k)

  private def runtime(name: String, args: List[Atom], t: Tree)(k: K): Expr =
    bind(Rhs.Call(name, args, runtime = true), t)(k)

  private def all(ts: List[Tree], env: Env)(k: List[Atom] => Expr): Expr =
    ts match
      case Nil       => k(Nil)
      case t :: rest => expr(t, env)(a => all(rest, env)(as => k(a :: as)))

  /** Bind `rhs` to a fresh variable of `t`'s type and continue with it. */
  private def bind(rhs: Rhs, t: Tree)(k: K): Expr =
    val x = fresh("t", ctype(typeOf(t), t.span))
    Expr.Let(x, rhs, k(x))

  private def fresh(base: String, t: CType): Var = Var(s"$base.${ids.next()}", t)

  private def typeOf(t: Tree): Type = unit.typeOf(t).getOrElse(Type.NeverType)

  private def isFn(name: String): Boolean = symbols.scope.get(name).exists(symbols(_).kind == SymKind.Fn)

  private def ctype(t: Type, span: Span): CType =
    Lower.scalar(t).getOrElse(t match
      case Type.TypeRef(id, Nil) if isString(id) => CType.Boxed
      case _                                     =>
        problems += problem(DiagCode.B005, span, s"values of type ${symbols.render(t)} (arrive in 4.0.M3)")
        CType.Unit)

  private def isString(id: SymbolId): Boolean =
    symbols(id).name == "String" && symbols(id).owner == SymbolId.Prelude

  private def unsupported(span: Span, what: String): Unit =
    problems += problem(DiagCode.B005, span, s"$what is not supported by the backend slice yet")

  private def unsupported(span: Span, what: String, k: K): Expr =
    unsupported(span, what)
    k(Atom.UnitC)

  private def problem(code: DiagCode, span: Span, message: String): Diagnostic =
    Diagnostic(Severity.Error, code, span, message)

object Lower:

  private val ints =
    Map("i8" -> 8, "i16" -> 16, "i32" -> 32, "i64" -> 64, "u8" -> 8, "u16" -> 16, "u32" -> 32, "u64" -> 64)

  /** The Core type of a scalar typer type. */
  def scalar(t: Type): Option[CType] =
    t match
      case Type.PrimType(n) if ints.contains(n) => Some(CType.Int(ints(n), signed = n.startsWith("i")))
      case Type.PrimType("f32")                 => Some(CType.Float(32))
      case Type.PrimType("f64")                 => Some(CType.Float(64))
      case Type.PrimType("bool")                => Some(CType.Bool)
      case Type.PrimType("char")                => Some(CType.Int(32, signed = false))
      case Type.PrimType("String")              => Some(CType.Boxed)
      case Type.UnitType | Type.NeverType       => Some(CType.Unit)
      case _                                    => None

  private def prim(op: Trees.BinOperator): PrimOp =
    import Trees.BinOperator.*
    op match
      case Add      => PrimOp.Add
      case Sub      => PrimOp.Sub
      case Mul      => PrimOp.Mul
      case Div      => PrimOp.Div
      case Mod      => PrimOp.Rem
      case Eq       => PrimOp.Eq
      case Neq      => PrimOp.Ne
      case Lt       => PrimOp.Lt
      case Le       => PrimOp.Le
      case Gt       => PrimOp.Gt
      case Ge       => PrimOp.Ge
      case And | Or => PrimOp.Eq
