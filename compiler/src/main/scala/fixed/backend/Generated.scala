package fixed.backend

import fixed.backend.Atom.Var
import fixed.core.{SymbolId, SymbolTable, Type, render}
import fixed.util.Span

/** Members the compiler derives or generates as Core functions: Rule 7.4.a `fold` (through functors
  * per Rule 7.4.a.1) and Rule 7.4.g `para`, Rule 7.4.b `unfold`, Rule 7.4.c `map`, and for list-shaped data
  * `Folding.fold`, `length` and `find`: one baseline version per data type, at its own parameters,
  * and clones at known arguments ([[member]]). The derived `show`, `eq` and `compare` are built at known
  * arguments, and at a data type's own parameters for a table built at run time.
  * Arguments that are type parameters make a polymorphic member with hidden witness parameters
  * ([[hidden]]).
  */
private[backend] trait Generated:
  protected type K = Atom => Expr
  protected type VarK = Var => Expr

  /** A field transform for [[rebuild]]: for a field that changes, how to bind its replacement. */
  protected type Transform = (Type, Var) => Option[K => Expr]

  protected def symbols: SymbolTable
  protected def instance(name: String)(build: => Fn): String
  protected def fresh(base: String, t: CType): Var
  protected def ctype(t: Type, span: Span): CType
  protected def isStringType(t: Type): Boolean
  protected def fieldTypes(data: SymbolId, args: List[Type], variant: SymbolId): List[Type]
  protected def dataOf(t: Type): Option[(SymbolId, List[Type])]

  /** The baseline versions built, the members still built per instance with the reason, and the
    * clones built with their baseline version and key (`Lower`).
    */
  def baselines: collection.mutable.Set[String]
  def instances: collection.mutable.Map[String, String]
  def typeClones: collection.mutable.Map[String, (String, String)]

  /** The key of the clone a call of baseline version `of` at `types` calls, if any (`Lower.specialised`). */
  protected def specialised(of: String, types: List[Type]): Option[String]

  /** `body`, or the first problem it reported, everything it built discarded (`Lower.attempt`). */
  protected def attempt[A](body: => A): Either[String, A]

  /** Report `what` as a problem of the code being lowered (`B005`). */
  protected def report(what: String): Unit

  /** The anonymous bound each implicit type parameter of a baseline version stands for (`Lower.baselineOf`). */
  protected def implicitBounds: collection.Map[Type, Type]

  /** `v`, a value of type `t`, shown: `k` gets the `String` ([[Satisfied]]). */
  protected def showValue(t: Type, v: Atom)(k: K): Expr

  /** Whether `a` and `b`, values of type `t`, are equal: `k` gets the `bool` ([[Satisfied]]). */
  protected def equalValues(t: Type, a: Atom, b: Atom)(k: K): Expr

  /** How `a` compares with `b`, values of type `t`: `k` gets the prelude's `Order` ([[Satisfied]]). */
  protected def compareValues(t: Type, a: Atom, b: Atom)(k: K): Expr

  /** The hidden parameters of type parameter `p`: its witness, then its tables (`Tables.hiddenOf`). */
  protected def hiddenOf(p: Type): List[Var]

  /** The hidden arguments of function `f`, built at `own` and called at `args` (`Tables.hiddenArgsOf`). */
  protected def hiddenArgsOf(f: String, own: List[Type], args: List[Type]): List[Atom]

  /** The function built under `name` (`Lower.functionNamed`). */
  protected def functionNamed(name: String): Fn

  /** The leading hidden parameters (witnesses and tables) of function `f`; `None` while `f` is a
    * generated member still being built, which has no parameters yet (a function lowered from
    * source has its signature from the start, `Lower.code`).
    */
  protected def hiddenParams(f: String): Option[List[Var]] =
    Option(functionNamed(f).params).filter(_.nonEmpty).map(_.takeWhile(p =>
      p.ctype == CType.Witness || p.ctype == CType.Table
    ))

  /** Record `x`'s typer type for `Demands`; returns `x`. */
  protected def typed(x: Var, t: Type, span: Span): Var

  /** A closure over `body` taking `params` (see `Lower.closure`). */
  protected def closure(params: List[Var], body: => Expr, ret: CType)(k: VarK): Expr

  /** Rule 7.4.a: `x.fold(onV1, ..., onVn)`, recursive fields folded first; Rule 7.4.a.1: a field
    * `F of ... Self` is mapped through `F`'s derived `map`, so the callback receives `F of ... R`.
    * Linear data folds in two loops ([[foldDown]]). With `para`, Rule 7.4.g: each field `fold`
    * would fold is passed twice: first as it is, then folded.
    */
  protected def derivedFold(data: SymbolId, args: List[Type], r: Type, span: Span, para: Boolean = false): String =
    val name = generatedName(data, args, if para then "para" else "fold", shown(r))
    val ws = hidden(args :+ r)
    instance(name) {
      val x = typed(fresh("x", CType.Boxed), Type.TypeRef(data, args), span)
      val callbacks = foldCallbacks(data)
      val rt = ctype(r, span)
      if linear(data) then
        Fn(
          name,
          ws ++ (x :: callbacks),
          rt,
          enterLoop(data, foldDown(data, args, r, ws, span, para), ws ++ (x :: callbacks), rt)
        )
      else
        val recurse =
          recursive(data, t => Part(t, r, v => bound(Rhs.Call(name, ws ++ (v :: callbacks), runtime = false), rt)))
        val branches = perVariant(data, args, x, span) { (tag, loaded) =>
          rebuild(loaded, fieldTransform(data, tag, loaded, recurse, span), keep = para)(as =>
            result(Rhs.CallClosure(callbacks(tag), as), rt)
          )
        }
        Fn(name, ws ++ (x :: callbacks), rt, switch(x, branches, rt))
    }

  /** A fresh callback parameter per variant of `data`, for its `fold`. */
  private def foldCallbacks(data: SymbolId): List[Var] =
    symbols.variantsOf(data).toList.map(v => fresh(s"on${v.name}", CType.Boxed))

  /** Rule 7.4.a for linear data ([[linear]]) as two loops, so the native stack does not grow with
    * the data. `foldDown(x, callbacks, rev)` moves the cells of `x` onto `rev`, which starts as a
    * fresh cell of the first variant without fields, each rebuilt by `Rhs.Con` with its `Self`
    * field pointing back: the ownership passes reuse a cell after an `rc == 1` test and else
    * allocate one, so from the first shared cell on the value is copied and the original is left
    * unchanged. At the original last cell, a variant without a `Self` field, it applies that
    * variant's callback and hands the result to `foldUp`, which walks the reversed cells from the
    * original last to the first, applying each variant's callback to the value folded so far: the
    * order of the recursive form. Each step is a self call in tail position, which `EmitLLVM`
    * makes a jump.
    *
    * Rule 7.4.g (`para`): a cell's callback also needs its original rest, so the cells are not
    * reversed. `paraDown` pushes each original cell onto a stack of two-field frames (the cell,
    * the frames below), one allocation per cell; the frame's reference keeps the cell, and so its
    * rest, alive until `paraUp` pops it and applies the callback to the cell's fields, its rest as
    * it is and the value folded so far. The original is never written, so sharing does not matter.
    */
  private def foldDown(data: SymbolId, args: List[Type], r: Type, ws: List[Var], span: Span, para: Boolean): String =
    val t = Type.TypeRef(data, args)
    val rt = ctype(r, span)
    val member = if para then "para" else "fold"
    val (down, up) =
      (generatedName(data, args, s"${member}Down", shown(r)), generatedName(data, args, s"${member}Up", shown(r)))
    val bottom = emptyTag(data)

    /** A frame's tag differs from that of the empty cell `enterLoop` passes, the bottom of the
      * stack, so `paraUp` can tell them apart; it may equal another variant's tag, which is harmless
      * because frames have no typer type.
      */
    val frameTag = if bottom == 0 then 1 else 0
    val List(cellSlot, belowSlot) = Layout.slots(List(CType.Boxed, CType.Boxed)): @unchecked

    /** The reversed cells are of type `t`; `para`'s stack of frames is of no typer type. */
    def reversed() = if para then fresh("rev", CType.Boxed) else typed(fresh("rev", CType.Boxed), t, span)
    instance(up) {
      val (rev, acc, cbs) = (reversed(), fresh("acc", rt), foldCallbacks(data))

      /** Applies the callback of `cell`'s variant, then continues with the next reversed cell (`fold`)
        * or with the frame `below` (`para`).
        */
      def step(cell: Var, below: Option[Var]) = perVariant(data, args, cell, span) { (tag, loaded) =>
        val i = recursiveField(data, tag)
        if i < 0 then Expr.Ret(acc)
        else
          val next = fresh("acc", rt)
          val as = loaded.zipWithIndex.flatMap((f, j) =>
            if j != i then List(f._2) else if para then List(f._2, acc) else List(acc)
          )
          val call = Rhs.Call(up, ws ++ (below.getOrElse(loaded(i)._2) :: next :: cbs), runtime = false)
          Expr.Let(next, Rhs.CallClosure(cbs(tag), as), result(call, rt))
      }
      val body =
        if para then
          val (cell, below) = (typed(fresh("cell", CType.Boxed), t, span), fresh("below", CType.Boxed))
          val pop = Expr.Let(
            cell,
            Rhs.Field(rev, cellSlot),
            Expr.Let(below, Rhs.Field(rev, belowSlot), switch(cell, step(cell, Some(below)), rt))
          )
          switch(rev, List(bottom -> Expr.Ret(acc), frameTag -> pop), rt)
        else switch(rev, step(rev, None), rt)
      Fn(up, ws ++ (rev :: acc :: cbs), rt, body)
    }
    instance(down) {
      val (x, cbs, rev) = (typed(fresh("x", CType.Boxed), t, span), foldCallbacks(data), reversed())
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        val i = recursiveField(data, tag)
        if i < 0 then
          val acc = fresh("acc", rt)
          val start = Rhs.Call(up, ws ++ (rev :: acc :: cbs), runtime = false)
          Expr.Let(acc, Rhs.CallClosure(cbs(tag), loaded.map(_._2)), result(start, rt))
        else
          val cell = if para then fresh("frame", CType.Boxed) else typed(fresh("cell", CType.Boxed), t, span)
          val built = if para then Rhs.Con(frameTag, List(x, rev))
          else Rhs.Con(tag, loaded.zipWithIndex.map((f, j) => if j == i then rev else f._2))
          Expr.Let(cell, built, result(Rhs.Call(down, ws ++ (loaded(i)._2 :: cbs) :+ cell, runtime = false), rt))
      }
      Fn(down, ws ++ (x :: cbs) :+ rev, rt, switch(x, branches, rt))
    }

  /** Rule 7.4.b: `D.unfold(seed, step)`. The step returns one layer whose recursive fields hold
    * seeds, also inside the last argument of a data type (`List of S` for `List of Self`); each is unfolded in turn.
    * (The layer is a separate allocation: fusion is Phase 4.)
    */
  protected def derivedUnfold(data: SymbolId, args: List[Type], seed: Type, span: Span): String =
    val seedType = ctype(seed, span)
    val at = if seed == Generated.R then "R" else seedType.toString
    val name = generatedName(data, args, "unfold", at)
    val ws = hidden(args :+ seed)
    instance(name) {
      val s = fresh("seed", seedType)
      val step = fresh("step", CType.Boxed)
      val layer = fresh("layer", CType.Boxed)
      val recurse =
        recursive(
          data,
          t => Part(seed, t, v => bound(Rhs.Call(name, ws ++ List(v, step), runtime = false), CType.Boxed))
        )
      if linear(data) then
        val go = generatedName(data, args, "unfoldLoop", at)
        instance(go) {
          val acc = typed(fresh("acc", CType.Boxed), Type.TypeRef(data, args), span)
          val body = loopBody(data, args, args, layer, Some(seedType), (_, _) => None, acc, go, ws, step, span)
          Fn(go, ws ++ List(s, step, acc), CType.Boxed, Expr.Let(layer, Rhs.CallClosure(step, List(s)), body))
        }
        Fn(name, ws ++ List(s, step), CType.Boxed, enterLoop(data, go, ws ++ List(s, step)))
      else
        val branches = perVariant(data, args, layer, span, Some(seedType)) { (tag, loaded) =>
          construction(tag, loaded, fieldTransform(data, tag, loaded, recurse, span), Type.TypeRef(data, args))
        }
        Fn(
          name,
          ws ++ List(s, step),
          CType.Boxed,
          Expr.Let(layer, Rhs.CallClosure(step, List(s)), switch(layer, branches, CType.Boxed))
        )
    }

  /** Rule 7.4.c: `x.map(f)` over the rightmost type parameter. Elements and recursive fields are
    * mapped, also inside the last argument of a data type (`List of A`, `List of Self`).
    */
  protected def derivedMap(data: SymbolId, args: List[Type], newElem: Type, span: Span): String =
    val name = generatedName(data, args, "map", shown(newElem))
    val ws = hidden(args :+ newElem)
    instance(name) {
      val (x, f) = (typed(fresh("x", CType.Boxed), Type.TypeRef(data, args), span), fresh("f", CType.Boxed))
      val elemParam = symbols.typeParamsOf(data).lastOption.map(Type.TypeRef(_, Nil))
      val newSelf = selfType(data, args.dropRight(1) :+ newElem)
      val recurse =
        recursive(
          data,
          t => Part(t, newSelf, v => bound(Rhs.Call(name, ws ++ List(v, f), runtime = false), CType.Boxed))
        )
      val leaf: Leaf = (d, t) =>
        if elemParam.contains(d) then
          Some(Part(t, newElem, v => bound(Rhs.CallClosure(f, List(v)), ctype(newElem, span))))
        else recurse(d, t)
      val outArgs = args.dropRight(1) :+ newElem
      val out = Type.TypeRef(data, outArgs)
      if linear(data) then
        val go = generatedName(data, args, "mapLoop", shown(newElem))
        instance(go) {
          val acc = typed(fresh("acc", CType.Boxed), out, span)
          Fn(go, ws ++ List(x, f, acc), CType.Boxed, loopBody(data, args, outArgs, x, None, leaf, acc, go, ws, f, span))
        }
        Fn(name, ws ++ List(x, f), CType.Boxed, enterLoop(data, go, ws ++ List(x, f)))
      else
        val branches = perVariant(data, args, x, span) { (tag, loaded) =>
          construction(tag, loaded, fieldTransform(data, tag, loaded, leaf, span), out)
        }
        Fn(name, ws ++ List(x, f), CType.Boxed, switch(x, branches, CType.Boxed))
    }

  /** Whether `data`'s `unfold` and `map` run as a loop ([[loopStep]]): it has a variant without
    * fields and one with a field of type `Self`, each variant's field of type `Self` is its last
    * field (so the elements before it are produced first, as in the recursive form), and no other
    * field mentions `data`. A list is linear; a tree, a rose tree and a snoc list are not.
    */
  private def linear(data: SymbolId): Boolean =
    def mentions(t: Type): Boolean =
      t match
        case Type.TypeRef(`data`, _) => true
        case _                       => t.children.exists(mentions)
    val variants = symbols.variantsOf(data).toList.map(v => symbols.fieldsOf(v.id).map(_.info).toList)
    variants.exists(_.isEmpty) && variants.exists(_.contains(declaredSelf(data))) &&
    variants.forall(fs =>
      fs.dropRight(1).forall(!mentions(_)) && fs.lastOption.forall(f => f == declaredSelf(data) || !mentions(f))
    )

  /** The body of loop `go` over the variants of `obj` (of `data` at `args`) that builds data at
    * `outArgs` ([[loopStep]]), calling `go(ws, rest, closure, cell)` after each cell.
    */
  private def loopBody(
      data: SymbolId,
      args: List[Type],
      outArgs: List[Type],
      obj: Var,
      seed: Option[CType],
      leaf: Leaf,
      acc: Var,
      go: String,
      ws: List[Var],
      closure: Var,
      span: Span
  ): Expr =
    val branches = perVariant(data, args, obj, span, seed) { (tag, loaded) =>
      loopStep(data, outArgs, tag, loaded, leaf, acc, span)((rest, cell) =>
        result(Rhs.Call(go, ws ++ List(rest, closure, cell), runtime = false), CType.Boxed)
      )
    }
    switch(obj, branches, CType.Boxed)

  /** The entry of a linear member of result type `rt`: `go(args, acc)`, the cells built so far
    * starting as the variant without fields.
    */
  private def enterLoop(data: SymbolId, go: String, args: List[Atom], rt: CType = CType.Boxed): Expr =
    val empty = fresh("empty", CType.Boxed)
    Expr.Let(empty, Rhs.Con(emptyTag(data), Nil), result(Rhs.Call(go, args :+ empty, runtime = false), rt))

  /** The tag of `data`'s first variant without fields. */
  private def emptyTag(data: SymbolId): Int =
    symbols.variantsOf(data).toList.indexWhere(v => symbols.fieldsOf(v.id).isEmpty)

  /** One step of a loop that reads its input in order and builds linear data `data` at `args` in
    * reverse: variant `tag` rebuilt from `loaded` (fields transformed by `leaf`). A variant with a
    * recursive field becomes a cell that holds `into` in that field, and the loop goes on with
    * `next(field, cell)`; another is the last cell, and [[reverseOnto]] puts `into`, the cells
    * built so far, back in order in front of it. Every call is a self call in tail position, which
    * `EmitLLVM` emits as a jump (EmitSuite checks that the loops of a list have no self call left).
    */
  private def loopStep(
      data: SymbolId,
      args: List[Type],
      tag: Int,
      loaded: List[(Type, Var)],
      leaf: Leaf,
      into: Var,
      span: Span
  )(next: (Var, Var) => Expr): Expr =
    val rec = recursiveField(data, tag)
    val fields = fieldTransform(data, tag, loaded, leaf, span)
    val transform: Transform = (ft, v) => if rec >= 0 && v == loaded(rec)._2 then Some(_(into)) else fields(ft, v)
    rebuild(loaded, transform) { as =>
      val cell = typed(fresh("cell", CType.Boxed), Type.TypeRef(data, args), span)
      Expr.Let(
        cell,
        Rhs.Con(tag, as),
        if rec >= 0 then next(loaded(rec)._2, cell)
        else
          result(
            Rhs.Call(reverseOnto(data, args, span), hidden(args) ++ List(into, cell), runtime = false),
            CType.Boxed
          )
      )
    }

  /** The index of variant `tag`'s field of type `Self`, or -1. */
  private def recursiveField(data: SymbolId, tag: Int): Int =
    symbols.fieldsOf(symbols.variantsOf(data).toList(tag).id).indexWhere(_.info == declaredSelf(data))

  /** `reverseOnto(acc, done)`: the cells of linear data `acc`, built in reverse by [[loop]], put in
    * front of `done` in order; a loop too.
    */
  private def reverseOnto(data: SymbolId, args: List[Type], span: Span): String =
    val name = generatedName(data, args, "reverseOnto", "")
    val ws = hidden(args)
    instance(name) {
      val t = Type.TypeRef(data, args)
      val (acc, done) = (typed(fresh("acc", CType.Boxed), t, span), typed(fresh("done", CType.Boxed), t, span))
      val branches = perVariant(data, args, acc, span) { (tag, loaded) =>
        if recursiveField(data, tag) < 0 then Expr.Ret(done)
        else
          loopStep(data, args, tag, loaded, (_, _) => None, done, span)((rest, cell) =>
            result(Rhs.Call(name, ws ++ List(rest, cell), runtime = false), CType.Boxed)
          )
      }
      Fn(name, ws ++ List(acc, done), CType.Boxed, switch(acc, branches, CType.Boxed))
    }

  /** Rule 7.4 derived `show` (`spec/stdlib.md` §4.10): a variant without fields shows its name,
    * another `Name(f1, f2, ...)`, each field shown by [[showValue]]. At arguments that mention type
    * parameters it takes their hidden parameters, their `Show` tables among them, which its name
    * lists: they depend on the bound sets in scope (`Tables.assumed`).
    * ponytail: each concatenation copies the string so far, quadratic in the size of the value;
    * collect the parts and join them once if large values are shown.
    */
  protected def derivedShow(data: SymbolId, args: List[Type]): String =
    val concat = (a: Atom, b: Atom) => bound(Rhs.Call("fx_str_concat", List(a, b), runtime = true), CType.Boxed)
    def fields(fs: List[(Type, Var)], acc: Atom, first: Boolean): Expr =
      fs match
        case Nil             => concat(acc, Atom.StrC(")"))(Expr.Ret(_))
        case (ft, v) :: rest =>
          val sep: K => Expr = if first then _(acc) else concat(acc, Atom.StrC(", "))
          sep(a => showValue(ft, v)(s => concat(a, s)(fields(rest, _, first = false))))
    unary(data, args, "show") { (tag, loaded) =>
      val ctor = symbols.variantsOf(data).toList(tag).name
      if loaded.isEmpty then Expr.Ret(Atom.StrC(ctor)) else fields(loaded, Atom.StrC(s"$ctor("), first = true)
    }

  /** A derived member `name` of one value of `data` at `args`, with a boxed result: `body` of each
    * variant's fields.
    */
  private def unary(data: SymbolId, args: List[Type], name: String)(
      body: (Int, List[(Type, Var)]) => Expr
  ): String =
    derivedMember(data, args, name) { (member, ws, t) =>
      val x = typed(fresh("x", CType.Boxed), t, Span.NoSpan)
      Fn(member, ws :+ x, CType.Boxed, switch(x, perVariant(data, args, x, Span.NoSpan)(body), CType.Boxed))
    }

  /** Derived member `name` of `data` at `args`, built once by `build` from its name, the hidden
    * parameters of the type parameters the arguments mention, and the type.
    */
  private def derivedMember(data: SymbolId, args: List[Type], name: String)(build: (String, List[Var], Type) => Fn)
      : String =
    val t = Type.TypeRef(data, args)
    val ws = typeParamsIn(t).flatMap(hiddenOf)
    val member = generatedName(data, args, name, ws.map(_.name).mkString(", "))
    instance(member)(build(member, ws, t))

  /** Rule 7.4 derived `eq` (`spec/stdlib.md` §4.7, component-wise): the same variant, and each pair
    * of fields equal by [[equalValues]]; hidden parameters as for [[derivedShow]].
    */
  protected def derivedEq(data: SymbolId, args: List[Type]): String =
    pairwise(data, args, "eq", CType.Bool)(
      _.foldRight(Expr.Ret(Atom.BoolC(true)): Expr) { case (((ft, x), (_, y)), rest) =>
        equalValues(ft, x, y)(r => result(Rhs.If(r, rest, Expr.Ret(Atom.BoolC(false))), CType.Bool))
      },
      (_, _) => Expr.Ret(Atom.BoolC(false))
    )

  /** Rule 7.4 derived `compare` (`spec/stdlib.md` §4.8): variants in the order declared, then the
    * fields of one variant left to right, the first that differs deciding ([[compareValues]]); the
    * last field's comparison is the result, so a list compares in a loop. The result is the
    * prelude's `Order`. Hidden parameters as for [[derivedShow]].
    */
  protected def derivedCompare(data: SymbolId, args: List[Type]): String =
    def fields(pairs: List[((Type, Var), (Type, Var))]): Expr =
      pairs match
        case Nil                       => order("Equal")(Expr.Ret(_))
        case List(((ft, x), (_, y)))   => compareValues(ft, x, y)(Expr.Ret(_))
        case ((ft, x), (_, y)) :: rest =>
          compareValues(ft, x, y) { c =>
            val cases = orderVariants.map(v => if v == "Equal" then fields(rest) else Expr.Ret(c)).zipWithIndex
            result(Rhs.Switch(c, cases.map(_.swap)), CType.Boxed)
          }
    pairwise(data, args, "compare", CType.Boxed)(
      fields,
      (tag, other) => order(if tag < other then "Less" else "Greater")(Expr.Ret(_))
    )

  /** A derived member `name` of two values `a` and `b` of `data` at `args`: for the same variant,
    * `same` of their pairs of fields; for variants `tag` and `other` that differ, `differ`.
    * ponytail: the variants are compared by a switch in a switch, quadratic in their number;
    * compare tags once Core can read one.
    */
  private def pairwise(data: SymbolId, args: List[Type], name: String, rt: CType)(
      same: List[((Type, Var), (Type, Var))] => Expr,
      differ: (Int, Int) => Expr
  ): String =
    val span = Span.NoSpan
    derivedMember(data, args, name) { (member, ws, t) =>
      val (a, b) = (typed(fresh("a", CType.Boxed), t, span), typed(fresh("b", CType.Boxed), t, span))
      val branches = perVariant(data, args, a, span) { (tag, as) =>
        val inner = perVariant(data, args, b, span) { (other, bs) =>
          if other == tag then same(as.zip(bs)) else differ(tag, other)
        }
        switch(b, inner, rt)
      }
      Fn(member, ws ++ List(a, b), rt, switch(a, branches, rt))
    }

  /** `k` given the prelude's `Order` variant `v`. */
  protected def order(v: String)(k: K): Expr =
    val o = fresh("o", CType.Boxed)
    Expr.Let(o, Rhs.Con(orderVariants.indexOf(v), Nil), k(o))

  /** The names of the prelude's `Order` variants, in tag order. */
  protected def orderVariants: List[String] =
    symbols.prelude.get("Order").toList.flatMap(symbols.variantsOf(_).map(_.name))

  /** A part of a field that a generated member rebuilds: its type before and after, and how to
    * rebuild a value of it.
    */
  final private case class Part(in: Type, out: Type, build: Atom => K => Expr)

  /** The part a generated member rebuilds at a declared type, given that type and its instantiation, if any. */
  private type Leaf = (Type, Type) => Option[Part]

  /** The [[Leaf]] of a recursive field (one of declared type `Self`, Rule 7.4.a): `at` its instantiated type. */
  private def recursive(data: SymbolId, at: Type => Part): Leaf =
    (d, t) => Option.when(d == declaredSelf(data))(at(t))

  /** The field transform of variant `tag`'s fields `loaded`: each field rebuilt where `leaf` applies, directly or inside a data type ([[part]]). */
  private def fieldTransform(data: SymbolId, tag: Int, loaded: List[(Type, Var)], leaf: Leaf, span: Span): Transform =
    val declared = symbols.fieldsOf(symbols.variantsOf(data).toList(tag).id).map(_.info).toList
    (t, v) => part(declared(loaded.indexWhere(_._2 == v)), t, leaf, span).map(_.build(v))

  /** What rebuilds a value of declared type `d`, instantiated as `t`: `leaf` where it applies;
    * else, for `F of (..., X)` that a derived member maps through (`Members.through`, which the
    * typer's field types follow) and a part in `X`, `F`'s derived `map` with a closure that rebuilds
    * each `X` (Rule 7.4.a.1 for `fold`); else nothing, and the value is kept, as the typer types it.
    */
  private def part(d: Type, t: Type, leaf: Leaf, span: Span): Option[Part] =
    leaf(d, t).orElse((d, t) match
      case (Type.TypeRef(_, ds), Type.TypeRef(f, ts)) if fixed.typer.Members.through(symbols, d).isDefined =>
        part(ds.last, ts.last, leaf, span).map { e =>
          Part(
            Type.TypeRef(f, ts.init :+ e.in),
            Type.TypeRef(f, ts.init :+ e.out),
            v =>
              k =>
                val param = fresh("e", ctype(e.in, span))
                closure(List(param), e.build(param)(Expr.Ret(_)), ctype(e.out, span)) { c =>
                  val (map, ws) = member(f, ts.init :+ e.in, Some(e.out))((a, b) => derivedMap(f, a, b, span))
                  bound(Rhs.Call(map, ws ++ List(v, c), runtime = false), CType.Boxed)(k)
                }
          )
        }
      case _ => None)

  /** `Folding.fold(init, f)` on a list-shaped data type: a left fold. ponytail: generated here until
    * `satisfies` bodies in Fixed are lowered; spec §7.4 does not derive it. Same for `length` and
    * `find` below.
    */
  protected def foldLeft(data: SymbolId, args: List[Type], accType: Type, span: Span): String =
    val name = generatedName(data, args, "foldLeft", shown(accType))
    val ws = hidden(args :+ accType)
    instance(name) {
      val rt = ctype(accType, span)
      val (xs, init, f) = (listParam(data, args, span), fresh("init", rt), fresh("f", CType.Boxed))
      val branches = listBranches(data, args, xs, span, Expr.Ret(init)) { (elem, rest) =>
        val acc = fresh("acc", rt)
        Expr.Let(
          acc,
          Rhs.CallClosure(f, List(init, elem)),
          result(Rhs.Call(name, ws ++ List(rest, acc, f), runtime = false), rt)
        )
      }
      Fn(name, ws ++ List(xs, init, f), rt, switch(xs, branches, rt))
    }

  /** The generated `length` of list-shaped data: a loop (`lengthLoop`, a self call in tail position
    * that `EmitLLVM` emits as a jump) that counts the cells into an accumulator.
    */
  protected def length(data: SymbolId, args: List[Type], resultType: Type, span: Span): String =
    val name = generatedName(data, args, "length", symbols.render(resultType))
    val ws = hidden(args)
    val rt = ctype(resultType, span)
    val go = generatedName(data, args, "lengthLoop", symbols.render(resultType))
    instance(go) {
      val (xs, acc) = (listParam(data, args, span), fresh("acc", rt))
      val branches = listBranches(data, args, xs, span, Expr.Ret(acc)) { (_, rest) =>
        val n = fresh("n", rt)
        Expr.Let(
          n,
          Rhs.Prim(PrimOp.Add, List(acc, Atom.IntC(1, rt))),
          result(Rhs.Call(go, ws ++ List(rest, n), runtime = false), rt)
        )
      }
      Fn(go, ws ++ List(xs, acc), rt, switch(xs, branches, rt))
    }
    instance(name) {
      val xs = listParam(data, args, span)
      Fn(name, ws :+ xs, rt, result(Rhs.Call(go, ws ++ List(xs, Atom.IntC(0, rt)), runtime = false), rt))
    }

  /** `find(p)`: the first element satisfying `p`, built with the constructors that `optional`'s
    * `some` and `none` map to on the result's representing data type.
    */
  protected def find(data: SymbolId, args: List[Type], optional: SymbolId, resultType: Type, span: Span): String =
    val name = generatedName(data, args, "find", symbols.render(resultType))
    val ws = hidden(args)
    instance(name) {
      val (xs, p) = (listParam(data, args, span), fresh("p", CType.Boxed))
      val tag = (ctor: String) => capVariant(optional, ctor, resultType).getOrElse(0)
      val none = fresh("none", CType.Boxed)
      val branches = listBranches(data, args, xs, span, Expr.Let(none, Rhs.Con(tag("none"), Nil), Expr.Ret(none))) {
        (elem, rest) =>
          val (ok, some) = (fresh("ok", CType.Bool), fresh("some", CType.Boxed))
          val found = Expr.Let(some, Rhs.Con(tag("some"), List(elem)), Expr.Ret(some))
          Expr.Let(
            ok,
            Rhs.CallClosure(p, List(elem)),
            result(
              Rhs.If(ok, found, result(Rhs.Call(name, ws ++ List(rest, p), runtime = false), CType.Boxed)),
              CType.Boxed
            )
          )
      }
      Fn(name, ws ++ List(xs, p), CType.Boxed, switch(xs, branches, CType.Boxed))
    }

  /** The witness parameter of type parameter `p` (`spec/compilation_model.md` Rule CM4.3). */
  protected def witnessOf(p: SymbolId): Var =
    Var(s"w.${symbols(p).name.filter(_.isLetterOrDigit)}.${p.toInt}", CType.Witness)

  /** The witness parameter of a type parameter: a declared one, [[Generated.R]] or an implicit one. */
  protected def witnessVar(t: Type): Var =
    t match
      case Type.TypeRef(p, Nil)                          => witnessOf(p)
      case Type.TypeVar(n) if implicitBounds.contains(t) => Var(s"w.is.${-n}", CType.Witness)
      case _                                             => Var("w.R", CType.Witness)

  /** The witness of type `t` where the code being lowered runs: the witness parameter of a type
    * parameter, else the static witness of a known type (every value that is not an immediate is on
    * the heap).
    */
  protected def witnessAtom(t: Type): Atom =
    if isTypeParam(t) then witnessVar(t)
    else Atom.StaticWitness(if ctype(t, Span.NoSpan).counted then "ref" else "imm")

  /** The hidden witness parameters of a generated member at `types`: one per type parameter they
    * mention, in order of first occurrence. Empty for a member at concrete types.
    */
  protected def hidden(types: List[Type]): List[Var] = types.flatMap(typeParamsIn).distinct.map(witnessVar)

  /** The type parameters `t` mentions, in order of first occurrence, [[Generated.R]] included. */
  protected def typeParamsIn(t: Type): List[Type] =
    if isTypeParam(t) then List(t) else t.children.flatMap(typeParamsIn).distinct

  /** Whether `t` is a type parameter: a declared one, [[Generated.R]] or an implicit one. */
  protected def isTypeParam(t: Type): Boolean =
    t match
      case Type.TypeRef(p, Nil) => symbols(p).kind == fixed.core.SymKind.TypeParam
      case _                    => t == Generated.R || implicitBounds.contains(t)

  /** Generated member `gen(args, r)` of `data`, with the hidden arguments to call it with. Its
    * baseline version is built once, at `data`'s own parameters and [[Generated.R]], and takes the
    * witness of each of `args` and of `r`, and a `satisfies` body also their tables ([[hiddenArgsOf]]); the
    * clone for known `args` and `r`, if chosen, and an instance where the baseline version cannot be
    * built, are built at `args` and `r` and take the hidden parameters of the type parameters they
    * mention, which the caller has under the same names.
    */
  protected def member(data: SymbolId, args: List[Type], r: Option[Type])(
      gen: (List[Type], Type) => String
  ): (String, List[Atom]) =
    lazy val perInstance =
      val f = gen(args, r.getOrElse(Type.UnitType))
      (f, hiddenParams(f).getOrElse(hidden(args ++ r)))
    val own = symbols.typeParamsOf(data).map(Type.TypeRef(_, Nil))
    if own.sizeIs != args.size then
      report(
        s"a generated member of `${symbols(data).name}` at ${args.size} type arguments, where it declares ${own.size}"
      )
      ("", Nil)
    else
      attempt(gen(own, Generated.R)) match
        case Right(name) =>
          baselines += name
          (if nonRegular(data) then None else specialised(name, args ++ r)) match
            case Some(key) =>
              typeClones(perInstance._1) = (name, key)
              perInstance
            case None => (name, hiddenArgsOf(name, own ++ r.map(_ => Generated.R), args ++ r))
        case Left(why) =>
          val (name, ws) = perInstance
          if ws.isEmpty && !nonRegular(data) then instances(name) = why else report(why)
          (name, ws)

  private def listParam(data: SymbolId, args: List[Type], span: Span): Var =
    typed(fresh("xs", CType.Boxed), Type.TypeRef(data, args), span)

  /** A copy of a value of `data` at `args` (`spec/quantities.md` Rule Q9.3): the cell is copied,
    * and so is every field at a part key in `parts` (keyed by `key` of the owning type); the other
    * fields are shared. Recursive fields share the copy function.
    */
  protected def copyOf(
      data: SymbolId,
      args: List[Type],
      parts: Set[(String, Int)],
      key: Type => String,
      span: Span
  ): String =
    val t = Type.TypeRef(data, args)
    val name = generatedName(data, args, "copy", parts.toList.sorted.map((k, s) => s"$k#$s").mkString(";"))
    instance(name) {
      val x = typed(fresh("x", CType.Boxed), t, span)
      val branches = perVariant(data, args, x, span) { (tag, loaded) =>
        val slots = Layout.slots(loaded.map((ft, _) => ctype(ft, span)))
        val copyField = (ft: Type, v: Var) =>
          dataOf(ft).filter(_ => parts((key(t), slots(loaded.indexWhere(_._2 == v))))).map((d, a) =>
            bound(Rhs.Call(copyOf(d, a, parts, key, span), List(v), runtime = false), CType.Boxed)
          )
        construction(tag, loaded, copyField, t)
      }
      Fn(name, List(x), CType.Boxed, switch(x, branches, CType.Boxed))
    }

  /** Branches over a list-shaped value: `empty` for the variant without fields, `cell(elem, rest)` for the other. */
  private def listBranches(data: SymbolId, args: List[Type], xs: Var, span: Span, empty: Expr)(
      cell: (Var, Var) => Expr
  ): List[(Int, Expr)] =
    val self = selfType(data, args)
    perVariant(data, args, xs, span) { (_, loaded) =>
      (loaded.find(_._1 != self), loaded.find(_._1 == self)) match
        case (Some((_, elem)), Some((_, rest))) => cell(elem, rest)
        case _                                  => empty
    }

  /** The variant tag that capability constructor `ctor` maps to on the data type representing `t`. */
  protected def capVariant(cap: SymbolId, ctor: String, t: Type): Option[Int] =
    dataOf(t).flatMap((d, _) =>
      symbols.constructorsOf(cap, ctor).find(_._1 == d).map(c =>
        symbols.variantsOf(d).indexWhere(_.name == c._2)
      )
    )

  /** Rebuild a variant's fields: each `transform`ed field is replaced by what its builder binds;
    * with `keep` (`para`), the original field stays and its rebuilt value follows it.
    */
  private def rebuild(loaded: List[(Type, Var)], transform: Transform, keep: Boolean = false)(
      finish: List[Atom] => Expr
  ): Expr =
    loaded match
      case Nil            => finish(Nil)
      case (t, v) :: rest =>
        val next = (a: List[Atom]) => rebuild(rest, transform, keep)(as => finish(a ++ as))
        transform(t, v).fold(next(List(v)))(_(a => next(if keep then List(v, a) else List(a))))

  /** Variant `tag` of type `t` rebuilt from `loaded`, its fields transformed; the cell is typed, so
    * that its ownership is keyed by its type (`Versions.construct`).
    */
  private def construction(tag: Int, loaded: List[(Type, Var)], transform: Transform, t: Type): Expr =
    rebuild(loaded, transform) { as =>
      val res = typed(fresh("r", CType.Boxed), t, Span.NoSpan)
      Expr.Let(res, Rhs.Con(tag, as), Expr.Ret(res))
    }

  /** Bind `rhs` and continue with the result: how a [[Part]] or a field transform rebuilds a value. */
  private def bound(rhs: Rhs, t: CType): K => Expr =
    k => { val y = fresh("y", t); Expr.Let(y, rhs, k(y)) }

  private def result(rhs: Rhs, rt: CType): Expr =
    val res = fresh("r", rt)
    Expr.Let(res, rhs, Expr.Ret(res))

  private def generatedName(data: SymbolId, args: List[Type], member: String, at: String): String =
    s"${symbols.render(Type.TypeRef(data, args))}.$member[$at]"

  /** `t` as a generated member's name shows it. */
  protected def shown(t: Type): String = if t == Generated.R then "R" else symbols.render(t)

  /** One `Switch` branch per variant of `data`: `obj`'s fields loaded, then `body(tag, fields)`.
    * Inside an unfold layer, recursive fields hold a seed of type `seed`.
    */
  private def perVariant(data: SymbolId, args: List[Type], obj: Var, span: Span, seed: Option[CType] = None)(
      body: (Int, List[(Type, Var)]) => Expr
  ): List[(Int, Expr)] =
    val self = selfType(data, args)
    symbols.variantsOf(data).toList.zipWithIndex.map { (v, tag) =>
      val types = fieldTypes(data, args, v.id)
      val cts = types.map(t => seed.filter(_ => t == self).getOrElse(ctype(t, span)))
      val vars = cts.map(fresh("f", _))
      val loads = vars.zip(Layout.slots(cts))
      (tag, loads.foldRight(body(tag, types.zip(vars)))((l, acc) => Expr.Let(l._1, Rhs.Field(obj, l._2), acc)))
    }

  private def switch(obj: Var, branches: List[(Int, Expr)], rt: CType): Expr =
    val res = fresh("res", rt)
    Expr.Let(res, Rhs.Switch(obj, branches), Expr.Ret(res))

  /** Whether a field of `data` mentions it at other arguments (`M of (B, A)` in `M of (A, B)`): at
    * known arguments it can have the instance's own type, which [[selfType]] cannot tell apart, so
    * `data`'s members are built at its own parameters only.
    */
  private def nonRegular(data: SymbolId): Boolean =
    def other(t: Type): Boolean =
      t match
        case Type.TypeRef(`data`, _) => t != declaredSelf(data)
        case _                       => t.children.exists(other)
    symbols.variantsOf(data).exists(v => symbols.fieldsOf(v.id).exists(f => other(f.info)))

  private def declaredSelf(data: SymbolId): Type = fixed.typer.Members.declaredSelf(symbols, data)

  /** `Self` at `args` as the instance's field types show it: the type of a recursive field. */
  private def selfType(data: SymbolId, args: List[Type]): Type =
    symbols.variantsOf(data).iterator.flatMap(v => symbols.fieldsOf(v.id).map(_.info).zip(fieldTypes(data, args, v.id)))
      .collectFirst { case (d, t) if d == declaredSelf(data) => t }.getOrElse(Type.TypeRef(data, args))

  /** Every variant has no fields, or one recursive field and one element field. */
  protected def listShaped(data: SymbolId): Boolean =
    symbols.variantsOf(data).forall { v =>
      val fs = symbols.fieldsOf(v.id).toList
      fs.isEmpty || (fs.size == 2 && fs.count(_.info == declaredSelf(data)) == 1)
    }

object Generated:

  /** The type parameter of a generated member built once that is the member's own: the result of a
    * `fold`, the new element of a `map`, the seed of an `unfold`. The typer gives none of them a
    * symbol, so this reserved type variable stands for each, and its witness parameter is `w.R`.
    * The variables below it, from -2 down, are the implicit type parameters of baseline versions.
    */
  val R: Type = Type.TypeVar(-1)
