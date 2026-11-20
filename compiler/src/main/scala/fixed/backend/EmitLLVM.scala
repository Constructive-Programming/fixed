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
  private val statics = mutable.LinkedHashMap.empty[String, String]
  private val entries = mutable.Set.empty[String]

  def program(fns: List[Fn]): String =
    entries ++= Core.closureEntries(fns)
    val bodies = fns.map(f => function(f, slots = entries(f.name))).mkString("\n")
    val globals = strings.map((s, name) => stringGlobal(name, s)).mkString + statics.values.mkString
    val decls = (EmitLLVM.always ++ declared.values).mkString("\n")
    s"$globals\n$decls\n\n$bodies"

  /** Emit `fn`. When `slots` is set, `fn` is a closure's code: every parameter after the closure,
    * and the result, is a 64-bit slot (Rule CM3.3(3)), so it narrows its parameters on entry and
    * widens its result.
    */
  private def function(fn: Fn, slots: Boolean): String =
    out.clear()
    line("entry:")
    val inSlot = (t: CType) => slots && !fillsSlot(t)
    val params =
      fn.params.map(p => if inSlot(p.ctype) then s"i64 %${p.name}.s" else s"${EmitLLVM.llType(p.ctype)} %${p.name}")
    fn.params.filter(p => inSlot(p.ctype)).foreach(p => narrow(p, s"%${p.name}.s"))
    val (result, _) = value(fn.body, "entry")
    val ret = zeroIfUnit(result, fn.ret)
    val retType = if inSlot(fn.ret) then "i64" else EmitLLVM.llType(fn.ret)
    line(s"  ret ${if slots then widen(ret, fn.ret, "ret") else s"$retType $ret"}")
    s"define $retType ${EmitLLVM.user(fn.name)}(${params.mkString(", ")}) {\n$out}\n"

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
      case Rhs.Catch(f, args)    => call(x, f, args, runtime = false); label
      case Rhs.If(c, t, f)       =>
        val n = ids.next()
        line(s"  br i1 ${operand(c)}, label %then.$n, label %else.$n")
        join(x, n, List(s"then.$n" -> t, s"else.$n" -> f))
      case Rhs.Switch(v, cases) =>
        val n = ids.next()
        line(s"  %tagp.$n = getelementptr inbounds i8, ptr ${operand(v)}, i64 8")
        line(s"  %tag.$n = load i32, ptr %tagp.$n")
        val labelled = cases.zipWithIndex.map { case ((tag, c), i) => (tag, s"case.$n.$i", c) }
        val branches = labelled.init.map((tag, l, _) => s"i32 $tag, label %$l").mkString(" ")
        line(s"  switch i32 %tag.$n, label %${labelled.last._2} [ $branches ]")
        join(x, n, labelled.map((_, l, c) => l -> c))
      case Rhs.Con(tag, Nil) =>
        val name = s"nullary.$tag"
        statics(name) =
          s"@$name = private unnamed_addr constant <{ i64, i32, i32 }> <{ i64 -1, i32 $tag, i32 0 }>, align 8\n"
        aliases(x.name) = s"@$name"
        label
      case Rhs.Con(tag, fields) =>
        val types = fields.map(_.tpe)
        allocate(x, tag, Layout.refs(types), fields.size)
        store(x, fields.zip(Layout.slots(types)))
        label
      case Rhs.Reuse(token, tag, fields, _) =>
        val types = fields.map(_.tpe)
        val size = 8 * fields.size
        line(
          s"  %${x.name} = call ptr @fx_alloc_at(ptr ${operand(token)}, i32 $tag, i32 ${Layout.refs(types)}, i64 $size)"
        )
        store(x, fields.zip(Layout.slots(types)))
        label
      case Rhs.Field(obj, slot) =>
        line(s"  %${x.name}.p = getelementptr inbounds i8, ptr ${operand(obj)}, i64 ${16 + 8 * slot}")
        load(x)
        label
      case Rhs.Closure(code, Nil, exclusive) =>
        val name = s"closure.$code"
        statics(name) = s"@\"$name\" = private unnamed_addr constant <{ i64, i32, i32, ptr, ptr }> " +
          s"<{ i64 -1, i32 ${Layout.closureTag}, i32 0, ptr ${EmitLLVM.user(orCode(exclusive, code))}, " +
          s"ptr ${EmitLLVM.user(code)} }>, align 8\n"
        aliases(x.name) = s"@\"$name\""
        label
      case Rhs.Closure(code, captures, exclusive) =>
        val types = captures.map(_.tpe)
        allocate(
          x,
          Layout.closureTag,
          Layout.refs(types, first = Layout.closureCode),
          captures.size + Layout.closureCode
        )
        line(s"  store ptr ${EmitLLVM.user(orCode(exclusive, code))}, ptr %${x.name}.0")
        line(s"  %${x.name}.code1 = getelementptr inbounds i8, ptr %${x.name}, i64 24")
        line(s"  store ptr ${EmitLLVM.user(code)}, ptr %${x.name}.code1")
        store(x, captures.zip(Layout.slots(types, first = Layout.closureCode)))
        label
      case Rhs.CallClosure(c, args, exclusive) =>
        val n = ids.next()
        line(s"  %code.$n.p = getelementptr inbounds i8, ptr ${operand(c)}, i64 ${if exclusive then 16 else 24}")
        line(s"  %code.$n = load ptr, ptr %code.$n.p")
        val argList = (s"ptr ${operand(c)}" :: args.zipWithIndex.map((a, i) => widen(operand(a), a.tpe, s"$n.$i")))
          .mkString(", ")
        if fillsSlot(x.ctype) then line(s"  %${x.name} = call ${EmitLLVM.llType(x.ctype)} %code.$n($argList)")
        else
          line(s"  %${x.name}.s = call i64 %code.$n($argList)")
          narrow(x, s"%${x.name}.s")
        label

  /** A closure's exclusive entry: `code` itself where no exclusive version was built. */
  private def orCode(exclusive: String, code: String): String = if exclusive.isEmpty then code else exclusive

  /** Emit each branch in its own block and join their results with a phi; the join block. */
  private def join(x: Var, n: Int, branches: List[(String, Expr)]): String =
    val results = branches.map { (l, e) =>
      line(s"$l:")
      val (v, end) = value(e, l)
      line(s"  br label %join.$n")
      s"[ ${zeroIfUnit(v, x.ctype)}, %$end ]"
    }
    line(s"join.$n:")
    line(s"  %${x.name} = phi ${EmitLLVM.llType(x.ctype)} ${results.mkString(", ")}")
    s"join.$n"

  private def allocate(x: Var, tag: Int, refs: Int, slots: Int): Unit =
    line(s"  %${x.name} = call ptr @fx_alloc(i32 $tag, i32 $refs, i64 ${8 * slots})")
    line(s"  %${x.name}.0 = getelementptr inbounds i8, ptr %${x.name}, i64 16")

  private def store(x: Var, fields: List[(Atom, Int)]): Unit =
    fields.foreach { (a, slot) =>
      val n = ids.next()
      line(s"  %st.$n = getelementptr inbounds i8, ptr %${x.name}, i64 ${16 + 8 * slot}")
      line(s"  store ${widen(operand(a), a.tpe, n.toString)}, ptr %st.$n")
    }

  /** The value `v` of type `t` in its canonical slot form (Rule CM3.3), as a typed operand: a
    * narrower integer sign- or zero-extended, `bool` 0 or 1, a float its bits (`f32` zero-extended),
    * `()` 0. `n` makes the names of the instructions it emits unique.
    */
  private def widen(v: String, t: CType, n: String): String =
    val ty = EmitLLVM.llType(t)
    t match
      case t if fillsSlot(t) => s"$ty $v"
      case CType.Unit        => "i64 0"
      case CType.Float(64)   =>
        line(s"  %wd.$n = bitcast double $v to i64")
        s"i64 %wd.$n"
      case CType.Float(_) =>
        line(s"  %wb.$n = bitcast float $v to i32")
        line(s"  %wd.$n = zext i32 %wb.$n to i64")
        s"i64 %wd.$n"
      case t =>
        val ext = t match
          case CType.Int(_, true) => "sext"
          case _                  => "zext"
        line(s"  %wd.$n = $ext $ty $v to i64")
        s"i64 %wd.$n"

  /** Load `x` from the slot at `%x.p`, narrowing it unless its type fills the slot (Rule CM3.3). */
  private def load(x: Var): Unit =
    val (ty, v) = (EmitLLVM.llType(x.ctype), s"%${x.name}")
    if fillsSlot(x.ctype) then line(s"  $v = load $ty, ptr $v.p")
    else
      line(s"  $v.s = load i64, ptr $v.p")
      narrow(x, s"$v.s")

  /** Define `x` from `slot`, an `i64` in canonical slot form (Rule CM3.3), narrowed to `x`'s type,
    * which must not fill the slot.
    */
  private def narrow(x: Var, slot: String): Unit =
    val v = s"%${x.name}"
    x.ctype match
      case CType.Float(64) => line(s"  $v = bitcast i64 $slot to double")
      case CType.Float(_)  =>
        line(s"  $v.b = trunc i64 $slot to i32")
        line(s"  $v = bitcast i32 $v.b to float")
      case t => line(s"  $v = trunc i64 $slot to ${EmitLLVM.llType(t)}")

  /** Whether a value of type `t` is its own slot form: a reference, or a 64-bit integer. */
  private def fillsSlot(t: CType): Boolean =
    t match
      case CType.Boxed | CType.Int(64, _) => true
      case _                              => false

  private def call(x: Var, f: String, args: List[Atom], runtime: Boolean): Unit =
    require(runtime || !entries(f), s"closure code $f takes slots and must be called through its closure")
    val argList = typed(args)
    if !runtime then line(s"  %${x.name} = call ${EmitLLVM.llType(x.ctype)} ${EmitLLVM.user(f)}($argList)")
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

  /** A unit operand used at another type is dead code after an abort or `!`: a zero of that type. */
  private def zeroIfUnit(v: String, t: CType): String =
    if v != "0" then v
    else
      t match
        case CType.Boxed    => "null"
        case CType.Float(_) => "0.0"
        case CType.Bool     => "false"
        case _              => v

  private def typed(as: List[Atom]): String = as.map(a => s"${EmitLLVM.llType(a.tpe)} ${operand(a)}").mkString(", ")

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
    "declare void @fx_panic_div() noreturn",
    "declare ptr @fx_alloc(i32, i32, i64)",
    "declare ptr @fx_alloc_at(ptr, i32, i32, i64)"
  )

  /** A user function's LLVM name, quoted: instance names carry type arguments. */
  def user(name: String): String = s"@\"fx_u_$name\""

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
