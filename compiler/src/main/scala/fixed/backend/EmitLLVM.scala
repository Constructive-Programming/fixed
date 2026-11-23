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
  private val signatures = mutable.Map.empty[String, Fn]

  /** The jumps back to the `loop` block of the function being emitted: each jumping block's label
    * and the arguments it passes to the `loop` block's phis.
    */
  private val jumps = mutable.ListBuffer.empty[(String, List[String])]

  def program(fns: List[Fn]): String =
    entries ++= Core.indirectEntries(fns)
    signatures ++= fns.map(f => f.name -> f)
    val bodies = fns.map(f => function(f, slots = entries(f.name))).mkString("\n")
    val globals = strings.map((s, name) => stringGlobal(name, s)).mkString + statics.values.mkString
    val decls = (EmitLLVM.always ++ declared.values).mkString("\n")
    s"$globals\n$decls\n\n$bodies"

  /** Emit `fn`. When `slots` is set, `fn` is a closure's code or a table entry: every parameter
    * after the closure or table, and the result, is an `i64` slot (Rule CM3.3(3)), so it narrows
    * its parameters on entry and widens its result. Otherwise a call of `fn` in tail position in
    * its own body is a jump to the `loop` block, whose phis take the new parameters: the native
    * stack stays bounded however often `fn` calls itself so (a derived `unfold` or `map` of linear
    * data, `Generated.loop`, and a derived `fold` or `para` of it, `Generated.foldDown`).
    */
  private def function(fn: Fn, slots: Boolean): String =
    out.clear()
    aliases.clear()
    jumps.clear()
    line("entry:")
    val inSlot = fn.params.zipWithIndex.map((p, i) => p -> (slots && i > 0)).toMap
    fn.params.filter(inSlot).foreach(p => narrow(p, s"%${p.name}.s"))
    if !slots then line("  br label %loop\nloop:")
    val loopAt = out.length
    val (result, end) = value(fn.body, if slots then "entry" else "loop", Option.when(!slots)(fn))
    val ret = zeroIfUnit(result, fn.ret)
    val retType = if slots then "i64" else EmitLLVM.llType(fn.ret)
    if end.nonEmpty then line(s"  ret ${if slots then slotted(ret, fn.ret, "ret") else s"$retType $ret"}")
    val renamed = if jumps.isEmpty then "" else ".in"
    val params = fn.params.map(p =>
      if inSlot(p) then s"i64 %${p.name}.s" else s"${EmitLLVM.llType(p.ctype)} %${p.name}$renamed"
    )
    val phis = fn.params.zipWithIndex.map { (p, i) =>
      val from = jumps.map((l, as) => s"[ ${as(i)}, %$l ]").mkString(", ")
      s"  %${p.name} = phi ${EmitLLVM.llType(p.ctype)} [ %${p.name}.in, %entry ], $from\n"
    }
    if jumps.nonEmpty then out.insert(loopAt, phis.mkString)
    s"define $retType ${EmitLLVM.user(fn.name)}(${params.mkString(", ")}) {\n$out}\n"

  /** Emit `e`, starting in block `label`; its result operand and the block it ends in, or an empty
    * block where it ends in a jump back to `loop`. `tail`: the function, if `e`'s result is its
    * result and its self tail calls are jumps.
    */
  private def value(e: Expr, label: String, tail: Option[Fn]): (String, String) =
    e match
      case Expr.Ret(a)        => (if a.tpe == CType.Unit then "0" else operand(a), label)
      case Expr.Dup(x, body)  => count(x, "dup", EmitLLVM.witnessDup); value(body, label, tail)
      case Expr.Drop(x, body) => count(x, "drop", EmitLLVM.witnessDrop); value(body, label, tail)

      /** A self call whose arguments are not in its parameters' form stays a call. */
      case Expr.Let(x, Rhs.Call(f, args, false), Expr.Ret(y))
          if y == x && tail.exists(fn => fn.name == f && fn.params.map(_.ctype) == args.map(_.tpe)) =>
        jumps += label -> args.map(a => zeroIfUnit(operand(a), a.tpe))
        line("  br label %loop")
        ("", "")
      case Expr.Let(x, r, body) => value(body, rhs(x, r, label, tail.filter(_ => body == Expr.Ret(x))), tail)

  /** `dup` or `drop` `x`: a reference directly, a slot through its witness's entry at `offset`
    * (Rule CM4.1), an indirect call.
    */
  private def count(x: Var, op: String, offset: Int): Unit =
    Core.witnessOf(x) match
      case None    => line(s"  call void @fx_$op(ptr ${operand(x)})")
      case Some(w) =>
        val n = ids.next()
        line(s"  %wop.$n.p = getelementptr inbounds i8, ptr ${operand(w)}, i64 $offset")
        line(s"  %wop.$n = load ptr, ptr %wop.$n.p")
        line(s"  call void %wop.$n(i64 ${operand(x)})")

  /** Emit `x = r`; the block emission continues in (empty where every branch of `r` jumped back to
    * `loop`). `tail`: as for [[value]], for the branches of `r`.
    */
  private def rhs(x: Var, r: Rhs, label: String, tail: Option[Fn]): String =
    r match
      case Rhs.Move(a)           => move(x, a); label
      case Rhs.Prim(op, args)    => prim(x, op, args, label)
      case Rhs.Call(f, args, rt) => call(x, f, args, rt); label
      case Rhs.Catch(f, args)    => call(x, f, args, runtime = false); label
      case Rhs.If(c, t, f)       =>
        val n = ids.next()
        line(s"  br i1 ${operand(c)}, label %then.$n, label %else.$n")
        join(x, n, List(s"then.$n" -> t, s"else.$n" -> f), tail)

      /** A type with no variant has no value (Rule 7.1.a): the switch is never reached. */
      case Rhs.Switch(_, Nil) =>
        val n = ids.next()
        line("  unreachable")
        line(s"dead.$n:")
        aliases(x.name) = "poison"
        s"dead.$n"
      case Rhs.Switch(v, cases) =>
        val n = ids.next()

        /** Pointer or immediate: the scrutinee's Core type. An immediate's tag is its low byte, or
          * 0 with one variant (Rule CM3.5); a cell's is the `i32` after its count.
          */
        val tag = v.tpe match
          case CType.Imm(_, true) =>
            line(s"  %tagb.$n = trunc i64 ${operand(v)} to i8")
            line(s"  %tag.$n = zext i8 %tagb.$n to i32")
            s"%tag.$n"
          case CType.Imm(_, false) => "0"
          case _                   =>
            line(s"  %tagp.$n = getelementptr inbounds i8, ptr ${operand(v)}, i64 8")
            line(s"  %tag.$n = load i32, ptr %tagp.$n")
            s"%tag.$n"
        val labelled = cases.zipWithIndex.map { case ((tag, c), i) => (tag, s"case.$n.$i", c) }
        val branches = labelled.init.map((tag, l, _) => s"i32 $tag, label %$l").mkString(" ")
        line(s"  switch i32 $tag, label %${labelled.last._2} [ $branches ]")
        join(x, n, labelled.map((_, l, c) => l -> c), tail)
      case Rhs.Con(tag, fields) if x.ctype.immediate => pack(x, tag, fields); label
      case Rhs.Con(tag, Nil)                         =>
        val name = s"nullary.$tag"
        statics(name) =
          s"@$name = private unnamed_addr constant <{ i64, i32, i32 }> <{ i64 -1, i32 $tag, i32 0 }>, align 8\n"
        aliases(x.name) = s"@$name"
        label
      case Rhs.Con(tag, fields) =>
        val types = fields.map(_.tpe)
        allocate(x, tag, refs(types, 0), fields.size)
        store(x, fields.zip(Layout.slots(types)))
        label
      case Rhs.Reuse(token, tag, fields, _) =>
        val types = fields.map(_.tpe)
        val size = 8 * fields.size
        val map = refs(types, 0)
        line(s"  %${x.name} = call ptr @fx_alloc_at(ptr ${operand(token)}, i32 $tag, i32 $map, i64 $size)")
        store(x, fields.zip(Layout.slots(types)))
        label
      case Rhs.Field(obj, slot) =>
        /** Pointer or immediate: `obj`'s Core type. An immediate's field is at bit offset `slot`. */
        obj.tpe match
          case _: CType.Imm => unpack(x, operand(obj), slot)
          case _            =>
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
        allocate(x, Layout.closureTag, refs(types, Layout.closureCode), captures.size + Layout.closureCode)
        line(s"  store ptr ${EmitLLVM.user(orCode(exclusive, code))}, ptr %${x.name}.0")
        line(s"  %${x.name}.code1 = getelementptr inbounds i8, ptr %${x.name}, i64 24")
        line(s"  store ptr ${EmitLLVM.user(code)}, ptr %${x.name}.code1")
        store(x, captures.zip(Layout.slots(types, first = Layout.closureCode)))
        label
      case Rhs.CallClosure(c, args, exclusive) =>
        indirect(x, c, if exclusive then 16 else 24, args)
        label
      case Rhs.CallTable(t, entry, args, exclusive) =>
        indirect(x, t, 16 * entry + (if exclusive then 0 else 8), args)
        label

  /** Call the code pointer at `offset` in `self` (a closure or a table) with `self` and `args` as
    * `i64` slots, and narrow the slot it returns into `x` (Rule CM3.3(3)).
    */
  private def indirect(x: Var, self: Atom, offset: Int, args: List[Atom]): Unit =
    val n = ids.next()
    line(s"  %code.$n.p = getelementptr inbounds i8, ptr ${operand(self)}, i64 $offset")
    line(s"  %code.$n = load ptr, ptr %code.$n.p")
    val argList = (s"ptr ${operand(self)}" :: args.zipWithIndex.map((a, i) => slotted(operand(a), a.tpe, s"$n.$i")))
      .mkString(", ")
    line(s"  %${x.name}.s = call i64 %code.$n($argList)")
    narrow(x, s"%${x.name}.s")

  /** `x = a`: an alias, or a conversion where exactly one of them is a slot (Rule CM3.3). */
  private def move(x: Var, a: Atom): Unit =
    (x.ctype, a.tpe) match
      case (_: CType.Slot, _: CType.Slot) => aliases(x.name) = operand(a)
      case (_: CType.Slot, from) => aliases(x.name) = slotted(operand(a), from, s"mv.${ids.next()}").stripPrefix("i64 ")
      case (_, _: CType.Slot)    => narrow(x, operand(a))
      case _                     => aliases(x.name) = operand(a)

  /** `v` of type `t` as an `i64` operand in slot form: a pointer as its address, else [[widen]]. */
  private def slotted(v: String, t: CType, n: String): String =
    t match
      case CType.Boxed | CType.Witness | CType.Table =>
        line(s"  %wd.$n = ptrtoint ptr $v to i64")
        s"i64 %wd.$n"
      case _ => widen(v, t, n)

  /** The reference map of a cell whose slots from `first` hold `types`, as an `i32` operand: the
    * static bits of its references, or'ed at run time with the `kind` (at offset 0) of each slot
    * field's witness, shifted to that field's bit (Rules CM3.4, PR3.1).
    */
  private def refs(types: List[CType], first: Int): String =
    types.zip(Layout.slots(types, first)).foldLeft(Layout.refs(types, first).toString) {
      case (map, (CType.Slot(w), slot)) =>
        val n = ids.next()
        line(s"  %kind.$n.w = load i64, ptr ${operand(Var(w, CType.Witness))}")
        line(s"  %kind.$n.b = trunc i64 %kind.$n.w to i32")
        line(s"  %kind.$n.s = shl i32 %kind.$n.b, $slot")
        line(s"  %kind.$n = or i32 $map, %kind.$n.s")
        s"%kind.$n"
      case (map, _) => map
    }

  /** A closure's exclusive entry: `code` itself where no exclusive version was built. */
  private def orCode(exclusive: String, code: String): String = if exclusive.isEmpty then code else exclusive

  /** Emit each branch in its own block and join their results with a phi; the join block, or an
    * empty one where every branch jumped back to `loop`.
    */
  private def join(x: Var, n: Int, branches: List[(String, Expr)], tail: Option[Fn]): String =
    val results = branches.flatMap { (l, e) =>
      line(s"$l:")
      val (v, end) = value(e, l, tail)
      Option.when(end.nonEmpty) {
        line(s"  br label %join.$n")
        s"[ ${zeroIfUnit(v, x.ctype)}, %$end ]"
      }
    }
    if results.isEmpty then ""
    else
      line(s"join.$n:")
      line(s"  %${x.name} = phi ${EmitLLVM.llType(x.ctype)} ${results.mkString(", ")}")
      s"join.$n"

  /** `x`, an immediate, built from variant `tag`'s `fields` (Rule CM3.5): the tag in the low byte if
    * `x`'s type is tagged, then each field zero-extended from its own type (an immediate field is
    * already zero above its width) and shifted to its offset ([[Layout.offsets]]), all or'ed. Every
    * bit above the fields stays zero.
    */
  private def pack(x: Var, tag: Int, fields: List[Atom]): Unit =
    val tagged = x.ctype match
      case CType.Imm(_, t) => t
      case _               => false
    val placed = fields.zip(Layout.offsets(fields.map(_.tpe), tagged)).filter(_._1.tpe != CType.Unit)
    val parts = placed.map { (a, at) =>
      val n = ids.next()
      val bits = a.tpe match
        case CType.Int(64, _) | _: CType.Imm => operand(a)
        case CType.Float(64)                 =>
          line(s"  %pk.$n.b = bitcast double ${operand(a)} to i64")
          s"%pk.$n.b"
        case CType.Float(_) =>
          line(s"  %pk.$n.b = bitcast float ${operand(a)} to i32")
          line(s"  %pk.$n.z = zext i32 %pk.$n.b to i64")
          s"%pk.$n.z"
        case t =>
          line(s"  %pk.$n.z = zext ${EmitLLVM.llType(t)} ${operand(a)} to i64")
          s"%pk.$n.z"
      if at == 0 then bits
      else
        line(s"  %pk.$n = shl i64 $bits, $at")
        s"%pk.$n"
    }
    val all = Option.when(tagged && tag != 0)(tag.toString).toList ++ parts
    aliases(x.name) = all.reduceOption { (a, b) =>
      val n = ids.next()
      line(s"  %pk.$n = or i64 $a, $b")
      s"%pk.$n"
    }.getOrElse("0")

  /** `x`, the field at bit offset `at` of the immediate `v` (Rule CM3.5): shifted down, then narrowed
    * to `x`'s type, a narrower immediate masked to its width.
    */
  private def unpack(x: Var, v: String, at: Int): Unit =
    val n = ids.next()
    val shifted =
      if at == 0 then v
      else
        line(s"  %up.$n = lshr i64 $v, $at")
        s"%up.$n"
    x.ctype match
      case CType.Imm(w, _) if w < 8 =>
        line(s"  %${x.name} = and i64 $shifted, ${(BigInt(1) << (8 * w)) - 1}")
      case _ => narrow(x, shifted)

  private def allocate(x: Var, tag: Int, refs: String, slots: Int): Unit =
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

  /** Define `x` from `slot`, an `i64` in canonical slot form (Rule CM3.3), narrowed to `x`'s type. */
  private def narrow(x: Var, slot: String): Unit =
    val v = s"%${x.name}"
    x.ctype match
      case CType.Int(64, _) | _: CType.Slot | _: CType.Imm => aliases(x.name) = slot
      case CType.Boxed | CType.Witness | CType.Table       => line(s"  $v = inttoptr i64 $slot to ptr")
      case CType.Float(64)                                 => line(s"  $v = bitcast i64 $slot to double")
      case CType.Float(_)                                  =>
        line(s"  $v.b = trunc i64 $slot to i32")
        line(s"  $v = bitcast i32 $v.b to float")
      case t => line(s"  $v = trunc i64 $slot to ${EmitLLVM.llType(t)}")

  /** Whether a value of type `t` is its own slot form: a reference, a 64-bit integer, a slot, or
    * a pointer to a witness or table.
    */
  private def fillsSlot(t: CType): Boolean =
    t match
      case CType.Boxed | CType.Int(64, _) | _: CType.Slot | _: CType.Imm | CType.Witness | CType.Table => true
      case _                                                                                           => false

  private def call(x: Var, f: String, args: List[Atom], runtime: Boolean): Unit =
    require(runtime || !entries(f), s"$f takes slots and must be called through its closure or table")
    if !runtime then direct(x, f, args)
    else
      val params = runtimeParams(f)
      val argList = args.zip(params).map(argument).mkString(", ")
      val ret = if x.ctype == CType.Unit then "void" else EmitLLVM.llType(x.ctype)
      declared(f) = s"declare $ret @$f(${params.map(parameter).mkString(", ")})"
      if ret == "void" then
        line(s"  call void @$f($argList)")
        aliases(x.name) = "0"
      else line(s"  %${x.name} = call $ret @$f($argList)")

  /** A call of user function `f`. A baseline version takes and returns a value of parameter type
    * as a slot (Rule CM3.3(1)): an argument of known type is widened into it, and a result narrowed
    * out of it, as for a closure.
    */
  private def direct(x: Var, f: String, args: List[Atom]): Unit =
    lazy val n = ids.next()
    val params = signatures.get(f).fold(args.map(_.tpe))(_.params.map(_.ctype))
    require(params.size == args.size, s"$f takes ${params.size} arguments, called with ${args.size}")
    val argList = args.zip(params).zipWithIndex.map { case ((a, p), i) =>
      if p.slot then slotted(operand(a), a.tpe, s"$n.$i")
      else if a.tpe == CType.Unit then argument(a, p)
      else s"${EmitLLVM.llType(a.tpe)} ${operand(a)}"
    }
    val ret = signatures.get(f).fold(x.ctype)(_.ret)
    if ret.slot then
      line(s"  %${x.name}.s = call i64 ${EmitLLVM.user(f)}(${argList.mkString(", ")})")
      narrow(x, s"%${x.name}.s")
    else line(s"  %${x.name} = call ${EmitLLVM.llType(x.ctype)} ${EmitLLVM.user(f)}(${argList.mkString(", ")})")

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
      case (PrimOp.Neg, CType.Float(_))   => emit(s"fneg $ty $ops")
      case (PrimOp.Neg, _)                => emit(s"sub $ty 0, $ops")
      case (PrimOp.Not, _)                => emit(s"xor i1 $ops, true")
      case (PrimOp.Widen, CType.Float(_)) => emit(s"fpext $ty $ops to ${EmitLLVM.llType(x.ctype)}")
      case (PrimOp.Convert, _)            =>
        val to = EmitLLVM.llType(x.ctype)
        emit(x.ctype match { case CType.Int(_, _) => s"trunc i64 $ops to $to"; case _ => s"sitofp i64 $ops to $to" })
      case (PrimOp.Widen, CType.Int(_, s)) =>
        emit(s"${if s then "sext" else "zext"} $ty $ops to ${EmitLLVM.llType(x.ctype)}")
      case (PrimOp.Eq | PrimOp.Ne, CType.Unit) => emit(s"icmp ${if op == PrimOp.Eq then "eq" else "ne"} i8 0, 0")
      case _                                   => emit(s"${EmitLLVM.instruction(op, t)} $ty $ops")

  /** A unit operand used at another type is dead code after an abort or a call of a `-> !`
    * function (`value` returns every unit result as `0`): a zero of that type.
    */
  private def zeroIfUnit(v: String, t: CType): String =
    if v != "0" then v
    else
      t match
        case CType.Boxed | CType.Witness | CType.Table => "null"
        case CType.Float(_)                            => "0.0"
        case CType.Bool                                => "false"
        case _                                         => v

  /** `a` as an argument of type `t`, with its type: a unit where `t` is not one is dead code after
    * an abort or a call of a `-> !` function, passed as the zero of `t`, as [[zeroIfUnit]] makes it
    * for a join or a return (whose unit operand is `0`, where an argument's is a variable).
    */
  private def argument(a: Atom, t: CType): String =
    s"${parameter(t)} ${if a.tpe == CType.Unit then zeroIfUnit("0", t) else operand(a)}"

  /** A parameter of type `t` as a runtime function declares it. The C ABI has the caller extend a
    * `bool` argument (`zeroext`); without it the callee may read the register's other bits, which
    * an `i1` narrowed from an immediate's bits ([[unpack]]) leaves holding its other fields.
    */
  private def parameter(t: CType): String =
    if t == CType.Bool then "i1 zeroext" else EmitLLVM.llType(t)

  /** The parameter types of each runtime function: at each position, the first type that is not a
    * unit among its calls' arguments there. Read once `program` has filled `signatures`.
    */
  private lazy val runtimeParams: Map[String, List[CType]] =
    (for
      fn <- signatures.values.toList
      case (_, Rhs.Call(f, as, true)) <- Core.lets(fn.body)
    yield f -> as.map(_.tpe)).groupMapReduce(_._1)(_._2) { (a, b) =>
      require(a.size == b.size, "a runtime function called with different numbers of arguments")
      a.zip(b).map((x, y) => if x == CType.Unit then y else x)
    }

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
      case Atom.StaticWitness(name)         =>
        declared(s"fx_w_$name") = s"@fx_w_$name = external constant ${EmitLLVM.witnessRecord}"
        s"@fx_w_$name"
      case Atom.StaticTable(key, es) =>
        val name = s"@\"fx_t_$key\""
        val code = (f: String) => if f.isEmpty then "null" else EmitLLVM.user(f)
        val ptrs = es.map((sh, ex) => s"{ ptr, ptr } { ptr ${code(orCode(ex, sh))}, ptr ${code(sh)} }")
        statics(name) = s"$name = private unnamed_addr constant [${es.size} x { ptr, ptr }] [${ptrs.mkString(", ")}]\n"
        name
      case Atom.DynTable(layout, captures) =>
        declared("fx_table") = "declare ptr @fx_table(ptr, i64, i64, ...)"
        val args =
          (s"ptr ${operand(layout)}" :: s"i64 ${layout.entries.size}" :: s"i64 ${captures.size}" ::
            captures.map(c => s"ptr ${operand(c)}")).mkString(", ")
        val n = ids.next()
        line(s"  %dt.$n = call ptr (ptr, i64, i64, ...) @fx_table($args)")
        s"%dt.$n"

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

  /** The runtime's value witness record `fx_witness`: `kind`, then `dup`, `drop` and `copy` code at 8, 16 and 24. */
  private val witnessRecord = "{ i64, ptr, ptr, ptr }"
  private val (witnessDup, witnessDrop) = (8, 16)

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
      case CType.Int(bits, _)                        => s"i$bits"
      case CType.Float(32)                           => "float"
      case CType.Float(_)                            => "double"
      case CType.Bool                                => "i1"
      case CType.Unit                                => "i8"
      case CType.Slot(_) | _: CType.Imm              => "i64"
      case CType.Boxed | CType.Witness | CType.Table => "ptr"

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
