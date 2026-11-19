package fixed.backend

import scala.collection.mutable

import fixed.backend.Atom.Var

/** Core to textual LLVM IR (slice §4.1). Opaque pointers only; no target triple, so `clang` uses its
  * host default. User functions are `@fx_u_<name>`; the C runtime (`runtime/fixed_rt.c`) defines
  * `main`, which calls `@fx_u_main`. A unit value is an `i8` 0.
  */
final class EmitLLVM:
  private val out = new StringBuilder
  private val ids = Iterator.from(1)
  private val aliases = mutable.Map.empty[String, String]
  private val strings = mutable.LinkedHashMap.empty[String, String]
  private val declared = mutable.LinkedHashMap.empty[String, String]

  def program(fns: List[Fn]): String =
    val bodies = fns.map(function).mkString("\n")
    val globals = strings.map((s, name) => stringGlobal(name, s)).mkString
    val decls = (EmitLLVM.always ++ declared.values).mkString("\n")
    s"$globals\n$decls\n\n$bodies"

  private def function(fn: Fn): String =
    out.clear()
    val params = fn.params.map(p => s"${EmitLLVM.llType(p.ctype)} %${p.name}").mkString(", ")
    line("entry:")
    val (result, _) = value(fn.body, "entry")
    line(s"  ret ${EmitLLVM.llType(fn.ret)} $result")
    s"define ${EmitLLVM.llType(fn.ret)} @fx_u_${fn.name}($params) {\n$out}\n"

  /** Emit `e`, starting in block `label`; its result operand and the block it ends in. */
  private def value(e: Expr, label: String): (String, String) =
    e match
      case Expr.Ret(a)          => (operand(a), label)
      case Expr.Dup(x, body)    => line(s"  call void @fx_dup(ptr ${operand(x)})"); value(body, label)
      case Expr.Drop(x, body)   => line(s"  call void @fx_drop(ptr ${operand(x)})"); value(body, label)
      case Expr.Let(x, r, body) => value(body, rhs(x, r, label))

  /** Emit `x = r`; the block emission continues in. */
  private def rhs(x: Var, r: Rhs, label: String): String =
    r match
      case Rhs.Move(a)           => aliases(x.name) = operand(a); label
      case Rhs.Prim(op, args)    => prim(x, op, args, label)
      case Rhs.Call(f, args, rt) => call(x, f, args, rt); label
      case Rhs.If(c, t, f)       =>
        val n = ids.next()
        line(s"  br i1 ${operand(c)}, label %then.$n, label %else.$n")
        line(s"then.$n:")
        val (tv, tl) = value(t, s"then.$n")
        line(s"  br label %join.$n")
        line(s"else.$n:")
        val (ev, el) = value(f, s"else.$n")
        line(s"  br label %join.$n")
        line(s"join.$n:")
        line(s"  %${x.name} = phi ${EmitLLVM.llType(x.ctype)} [ $tv, %$tl ], [ $ev, %$el ]")
        s"join.$n"

  private def call(x: Var, f: String, args: List[Atom], runtime: Boolean): Unit =
    val argList = args.map(a => s"${EmitLLVM.llType(a.tpe)} ${operand(a)}").mkString(", ")
    if !runtime then line(s"  %${x.name} = call ${EmitLLVM.llType(x.ctype)} @fx_u_$f($argList)")
    else
      val ret = if x.ctype == CType.Unit then "void" else EmitLLVM.llType(x.ctype)
      declared(f) = s"declare $ret @$f(${args.map(a => EmitLLVM.llType(a.tpe)).mkString(", ")})"
      if ret == "void" then
        line(s"  call void @$f($argList)")
        aliases(x.name) = "0"
      else line(s"  %${x.name} = call $ret @$f($argList)")

  private def prim(x: Var, op: PrimOp, args: List[Atom], label: String): String =
    val t = args.head.tpe
    val ty = EmitLLVM.llType(t)
    val ops = args.map(operand).mkString(", ")
    def emit(instr: String): String =
      line(s"  %${x.name} = $instr")
      label
    (op, t) match
      case (PrimOp.Div | PrimOp.Rem, CType.Int(_, signed)) =>
        val n = ids.next()
        line(s"  %zero.$n = icmp eq $ty ${operand(args(1))}, 0")
        line(s"  br i1 %zero.$n, label %divzero.$n, label %div.$n")
        line(s"divzero.$n:")
        line("  call void @fx_panic_div()")
        line("  unreachable")
        line(s"div.$n:")
        val name = if op == PrimOp.Div then "div" else "rem"
        line(s"  %${x.name} = ${if signed then "s" else "u"}$name $ty $ops")
        s"div.$n"
      case (PrimOp.Neg, CType.Float(_))    => emit(s"fneg $ty $ops")
      case (PrimOp.Neg, _)                 => emit(s"sub $ty 0, $ops")
      case (PrimOp.Not, _)                 => emit(s"xor i1 $ops, true")
      case (PrimOp.Widen, CType.Float(_))  => emit(s"fpext $ty $ops to ${EmitLLVM.llType(x.ctype)}")
      case (PrimOp.Widen, CType.Int(_, s)) =>
        emit(s"${if s then "sext" else "zext"} $ty $ops to ${EmitLLVM.llType(x.ctype)}")
      case (PrimOp.Eq | PrimOp.Ne, CType.Unit) => emit(s"icmp ${if op == PrimOp.Eq then "eq" else "ne"} i8 0, 0")
      case _                                   => emit(s"${EmitLLVM.instruction(op, t)} $ty $ops")

  private def operand(a: Atom): String =
    a match
      case Var(name, _)                     => aliases.getOrElse(name, s"%$name")
      case Atom.IntC(v, CType.Int(bits, _)) => EmitLLVM.twosComplement(v, bits).toString
      case Atom.IntC(v, _)                  => v.toString
      case Atom.FloatC(v, CType.Float(32))  => EmitLLVM.hexDouble(v.toFloat.toDouble)
      case Atom.FloatC(v, _)                => EmitLLVM.hexDouble(v.toDouble)
      case Atom.BoolC(b)                    => b.toString
      case Atom.UnitC                       => "0"
      case Atom.StrC(s)                     => "@" + strings.getOrElseUpdate(s, s"str.${strings.size + 1}")

  /** A string constant: a static heap object (`rc = -1`, never counted or freed). Layout as `fx_string`. */
  private def stringGlobal(name: String, s: String): String =
    val bytes = s.getBytes("UTF-8")
    val body =
      bytes.map(b => if b >= 0x20 && b < 0x7f && b != '"' && b != '\\' then b.toChar.toString else f"\\$b%02X").mkString
    s"@$name = private unnamed_addr constant <{ i64, i32, i32, i64, [${bytes.length + 1} x i8] }> " +
      s"<{ i64 -1, i32 0, i32 0, i64 ${bytes.length}, [${bytes.length + 1} x i8] c\"$body\\00\" }>, align 8\n"

  private def line(s: String): Unit =
    val _ = out.append(s).append('\n')

object EmitLLVM:

  private val always = List(
    "declare void @fx_dup(ptr)",
    "declare void @fx_drop(ptr)",
    "declare void @fx_panic_div() noreturn"
  )

  def llType(t: CType): String =
    t match
      case CType.Int(bits, _) => s"i$bits"
      case CType.Float(32)    => "float"
      case CType.Float(_)     => "double"
      case CType.Bool         => "i1"
      case CType.Unit         => "i8"
      case CType.Boxed        => "ptr"

  private def instruction(op: PrimOp, t: CType): String =
    (op, t) match
      case (PrimOp.Add, CType.Float(_)) => "fadd"
      case (PrimOp.Sub, CType.Float(_)) => "fsub"
      case (PrimOp.Mul, CType.Float(_)) => "fmul"
      case (PrimOp.Div, CType.Float(_)) => "fdiv"
      case (PrimOp.Rem, CType.Float(_)) => "frem"
      case (PrimOp.Add, _)              => "add"
      case (PrimOp.Sub, _)              => "sub"
      case (PrimOp.Mul, _)              => "mul"
      case (cmp, CType.Float(_))        => "fcmp " + floatCmp(cmp)
      case (cmp, CType.Int(_, signed))  => "icmp " + intCmp(cmp, signed)
      case (cmp, _)                     => "icmp " + intCmp(cmp, signed = false)

  private def floatCmp(op: PrimOp): String =
    op match
      case PrimOp.Eq => "oeq"
      case PrimOp.Ne => "une"
      case PrimOp.Lt => "olt"
      case PrimOp.Le => "ole"
      case PrimOp.Gt => "ogt"
      case _         => "oge"

  private def intCmp(op: PrimOp, signed: Boolean): String =
    val s = if signed then "s" else "u"
    op match
      case PrimOp.Eq => "eq"
      case PrimOp.Ne => "ne"
      case PrimOp.Lt => s + "lt"
      case PrimOp.Le => s + "le"
      case PrimOp.Gt => s + "gt"
      case _         => s + "ge"

  /** `v` as the signed decimal LLVM expects for an `i<bits>` constant (wrapping, slice §7.6). */
  def twosComplement(v: BigInt, bits: Int): BigInt =
    val m = v.mod(BigInt(1) << bits)
    if m >= (BigInt(1) << (bits - 1)) then m - (BigInt(1) << bits) else m

  /** LLVM's exact hexadecimal form of a floating-point constant. */
  def hexDouble(d: Double): String = f"0x${java.lang.Double.doubleToRawLongBits(d)}%016X"
