package fixed.backend

import java.nio.file.Path

import fixed.ast.Trees
import fixed.backend.Atom.{IntC, StaticTable, StaticWitness, StrC, Var}
import fixed.util.DiagCode

/** Witnesses and capability tables (`spec/compilation_model.md` §4) on hand-built Core, through
  * every backend pass, `clang` and a run with the leak check and `FX_CHECK_EXCL`. `Lower` builds
  * none of these constructs yet (4.1.M5).
  */
class WitnessSuite extends munit.FunSuite:

  private val dir = Path.of("target", "witness-tests")
  private val i64 = CType.Int(64, signed = true)
  private val u8 = CType.Int(8, signed = false)
  private val u64 = CType.Int(64, signed = false)

  private def v(name: String, t: CType): Var = Var(name, t)
  private def slot(name: String, w: String): Var = Var(name, CType.Slot(w))

  /** `let`s in order, then `ret`. */
  private def block(lets: (Var, Rhs)*)(ret: Atom): Expr =
    lets.foldRight(Expr.Ret(ret): Expr)((l, e) => Expr.Let(l._1, l._2, e))

  private def rt(f: String, as: Atom*): Rhs = Rhs.Call(f, as.toList, runtime = true)
  private def call(f: String, as: Atom*): Rhs = Rhs.Call(f, as.toList, runtime = false)
  private def fresh(s: String): Rhs = rt("fx_str_concat", StrC(s.take(1)), StrC(s.drop(1)))

  /** A function generic in one parameter: its witness `w`, then a value `x` of that type. */
  private def generic(name: String, ret: CType)(body: Expr): Fn =
    Fn(name, List(v("w", CType.Witness), slot("x", "w")), ret, body)

  /** `x` itself. */
  private val id = generic("id", CType.Slot("w"))(Expr.Ret(slot("x", "w")))

  /** `x` stored in a cell beside a static string, read back, and the cell dropped. */
  private val roundtrip = generic("roundtrip", CType.Slot("w"))(
    block(
      v("c", CType.Boxed) -> Rhs.Con(0, List(StrC("k"), slot("x", "w"))),
      slot("y", "w") -> Rhs.Field(v("c", CType.Boxed), 1)
    )(
      slot("y", "w")
    )
  )

  /** `x` stored twice in a cell that is then dropped: one witness `dup`, and a reference map with a
    * bit per slot from the witness. With the wrong witness a reference leaks.
    */
  private val pair = generic("pair", CType.Unit)(block(v("c", CType.Boxed) ->
    Rhs.Con(0, List(slot("x", "w"), slot("x", "w"))))(Atom.UnitC))

  /** `main` from `lets`, each `print` printing a string variable. */
  private def main(lets: (Var, Rhs)*): Fn = Fn("main", Nil, CType.Unit, block(lets*)(Atom.UnitC))

  private def print(s: Var): (Var, Rhs) = v(s"p.${s.name}", CType.Unit) -> rt("fx_print_line", s)

  /** Emit, ship and run `fns`; standard output and the counters. */
  private def run(name: String, fns: List[Fn]): (String, Map[String, Long], String) =
    val ir = Backend.emit(Backend.analyseCore(fns)).fold(ds => fail(s"$ds"), identity)
    val exe = Backend.ship(ir, dir.resolve(name), checks = true).fold(d => fail(d.message), identity)
    val (code, out, err) = Native.run(exe)
    assertEquals(code, 0, err)
    (out, Native.counters(err), ir)

  private def leakFree(counts: Map[String, Long]): Unit =
    assert(counts.contains("allocs"), counts)
    assertEquals(counts.get("allocs"), counts.get("frees"), counts)

  private val wi = v("wi", CType.Witness)
  private val wr = v("wr", CType.Witness)
  private val witnesses = List(wi -> Rhs.Move(StaticWitness("imm")), wr -> Rhs.Move(StaticWitness("ref")))

  test("an identity and a cell round trip on a slot, with the immediate and the reference witness"):
    val (out, counts, _) = run(
      "slots",
      List(
        id,
        roundtrip,
        main(
          witnesses ++ List(
            slot("a", "wi") -> Rhs.Move(IntC(-5, CType.Int(8, signed = true))),
            slot("b", "wi") -> call("id", wi, slot("a", "wi")),
            slot("c", "wi") -> call("roundtrip", wi, slot("b", "wi")),
            v("n", CType.Int(8, signed = true)) -> Rhs.Move(slot("c", "wi")),
            v("m", i64) -> Rhs.Prim(PrimOp.Widen, List(v("n", CType.Int(8, signed = true)))),
            v("s", CType.Boxed) -> rt("fx_show_i64", v("m", i64)),
            print(v("s", CType.Boxed)),
            v("h", CType.Boxed) -> fresh("hello"),
            slot("r", "wr") -> Rhs.Move(v("h", CType.Boxed)),
            slot("r2", "wr") -> call("id", wr, slot("r", "wr")),
            slot("r3", "wr") -> call("roundtrip", wr, slot("r2", "wr")),
            v("t", CType.Boxed) -> Rhs.Move(slot("r3", "wr")),
            print(v("t", CType.Boxed))
          )*
        )
      )
    )
    assertEquals(out, "-5\nhello\n")
    leakFree(counts)
    assertEquals(counts("dups"), 1L, "the read-back reference's one `dup`; ignoring the witness frees it early")

  /** `pair` called on a fresh string with witness `w`. */
  private def pairs(w: Var) =
    List(
      pair,
      main(witnesses ++ List(
        v("h", CType.Boxed) -> fresh("hello"),
        slot("r", w.name) -> Rhs.Move(v("h", CType.Boxed)),
        v("u", CType.Unit) -> call("pair", w, slot("r", w.name)),
        slot("i", "wi") -> Rhs.Move(IntC(7, i64)),
        v("u2", CType.Unit) -> call("pair", wi, slot("i", "wi"))
      )*)
    )

  test("a cell whose reference map comes from the witness at run time is freed whole"):
    val (_, counts, ir) = run("pair", pairs(wr))
    leakFree(counts)
    assert(ir.contains("shl i32 %kind."), ir)
    assertEquals(counts("dups"), 1L, counts)

  test("the same cell built with the immediate witness for a reference leaks it: the witness decides"):
    val (_, counts, _) = run("pair_wrong", pairs(wi))
    assertEquals(counts("allocs") - counts("frees"), 1L, counts)

  test("a slot its callee does not use is dropped by the caller through its witness"):
    val discard = generic("discard", CType.Unit)(Expr.Ret(Atom.UnitC))
    val (_, counts, ir) = run(
      "discard",
      List(
        discard,
        main(
          witnesses ++ List(
            v("h", CType.Boxed) -> fresh("hello"),
            slot("r", "wr") -> Rhs.Move(v("h", CType.Boxed)),
            v("u", CType.Unit) -> call("discard", wr, slot("r", "wr"))
          )*
        )
      )
    )
    leakFree(counts)
    assert(ir.contains("getelementptr inbounds i8, ptr @fx_w_ref, i64 16"), ir)

  test("a closure that captures a witness and a slot reads both back and frees the slot with the closure"):
    val c = v("c", CType.Boxed)
    val code = Fn(
      "code",
      List(c),
      CType.Slot("cw"),
      block(v("cw", CType.Witness) -> Rhs.Field(c, 2), slot("y", "cw") -> Rhs.Field(c, 3))(slot("y", "cw"))
    )
    val k = v("k", CType.Boxed)
    val (out, counts, _) = run(
      "capture",
      List(
        code,
        main(
          witnesses ++ List(
            v("h", CType.Boxed) -> fresh("hello"),
            slot("r", "wr") -> Rhs.Move(v("h", CType.Boxed)),
            k -> Rhs.Closure("code", List(wr, slot("r", "wr"))),
            slot("y", "wr") -> Rhs.CallClosure(k, Nil),
            v("t", CType.Boxed) -> Rhs.Move(slot("y", "wr")),
            print(v("t", CType.Boxed))
          )*
        )
      )
    )
    assertEquals(out, "hello\n")
    leakFree(counts)
    assertEquals(counts("dups"), 1L, counts)

  /** One-entry table whose shared and exclusive entries append different words. */
  private val table = StaticTable("demo", List(("shared_entry", "exclusive_entry")))

  private def entry(name: String, word: String): Fn =
    Fn(
      name,
      List(v("t", CType.Table), v("s", CType.Boxed)),
      CType.Boxed,
      block(v("r", CType.Boxed) -> rt("fx_str_concat", v("s", CType.Boxed), StrC(word)))(v("r", CType.Boxed))
    )

  test("a table entry is called through its exclusive pointer for an owned value and its shared one otherwise"):
    val (a, b, ra, rb) = (v("a", CType.Boxed), v("b", CType.Boxed), v("ra", CType.Boxed), v("rb", CType.Boxed))
    val fns = List(
      entry("shared_entry", " shared"),
      entry("exclusive_entry", " exclusive"),
      main(
        a -> fresh("x1"),
        ra -> Rhs.CallTable(table, 0, List(a)),
        print(ra),
        b -> fresh("y2"),
        rb -> Rhs.CallTable(table, 0, List(b)),
        print(rb),
        print(b)
      )
    )
    val (out, counts, ir) = run("table", fns)
    assertEquals(out, "x1 exclusive\ny2 shared\ny2\n")
    leakFree(counts)
    assert(ir.contains("@\"fx_t_demo\" = private unnamed_addr constant [1 x { ptr, ptr }]"), ir)
    assertEquals("call i64 %[\\w.]+\\(ptr @\"fx_t_demo\"".r.findAllIn(ir).size, 2, ir)
    assert("call \\S+ @\"fx_u_(shared|exclusive)_entry\"".r.findFirstIn(ir).isEmpty, ir)

  test("a fip fn that drops a value of parameter type, or calls through a table, breaks its contract (Rule CM4.7)"):
    val discard = generic("discard", CType.Unit)(Expr.Ret(Atom.UnitC))
    val viaTable = Fn(
      "via",
      List(v("s", CType.Boxed)),
      CType.Boxed,
      block(v("r", CType.Boxed) -> Rhs.CallTable(table, 0, List(v("s", CType.Boxed))))(v("r", CType.Boxed))
    )
    val fns = List(
      discard,
      viaTable,
      entry("shared_entry", "s"),
      entry("exclusive_entry", "x"),
      main(
        witnesses ++ List(
          slot("i", "wi") -> Rhs.Move(IntC(1, i64)),
          v("u", CType.Unit) -> call("discard", wi, slot("i", "wi")),
          v("h", CType.Boxed) -> fresh("ab"),
          v("k", CType.Boxed) -> call("via", v("h", CType.Boxed))
        )*
      )
    )
    val ds = FipCheck(Backend.analyseCore(fns, List("discard", "via").map(_ -> Trees.Fip.Plain).toMap))
    assert(ds.forall(_.code == DiagCode.E110), ds)
    val messages = ds.map(_.message)
    assert(messages.exists(m => m.contains("`discard` duplicates or drops `x`")), messages)
    assert(messages.exists(m => m.contains("`via` calls a member through a capability table")), messages)
    val moves = main(witnesses ++
      List(slot("i", "wi") -> Rhs.Move(IntC(1, i64)), slot("j", "wi") -> call("id", wi, slot("i", "wi")))*)
    assert(FipCheck(Backend.analyseCore(List(id, moves), Map("id" -> Trees.Fip.Plain))).isEmpty)

  /** A unit whose prelude gives the capabilities and `List`, `Maybe`; `Res` maps its variants to
    * `Result`'s constructors in the other order, so its `fold` entry reorders the callbacks.
    */
  private lazy val unit =
    val src = "data Res of (E, A):\n    Bad(error: E)\n    Good(value: A)\n\n" +
      "Res satisfies Result:\n    Good as ok\n    Bad as err\n\n" +
      "data Cmp:\n    Gt\n    Lt\n    Same\n\nCmp satisfies Ordering:\n    Lt as lt\n    Same as eq\n    Gt as gt\n\n" +
      "fn main() -> () = ()\n"
    val u = fixed.Compiler.compile(fixed.CompilationUnit(fixed.util.SourceFile.fromString("tables.fixed", src)))
    assert(!u.hasErrors, u.diagnostics)
    u

  private def symbols = unit.symbols
  private def data(name: String, args: fixed.core.Type*) = fixed.core.Type.TypeRef(symbols.scope(name), args.toList)
  private def prim(name: String) = fixed.core.Type.PrimType(name)
  private def at(cap: String, member: String): Int =
    val i = Tables.members(symbols, symbols.scope(cap)).indexOf(member)
    assert(i >= 0, s"$cap has no member $member")
    i

  private def tableOf(lower: Lower, t: fixed.core.Type, cap: String): StaticTable =
    lower.table(t, symbols.scope(cap)).fold(fail(_), identity)

  /** Closure code `name`: the closure, then `params`; `lets`, then `result`. */
  private def code(name: String, params: List[Var], ret: CType)(lets: (Var, Rhs)*)(result: Atom): Fn =
    Fn(name, v("env", CType.Boxed) :: params, ret, block(lets*)(result))

  /** Emit, ship and run `main` with the closure code `extra` and everything `lower` built. */
  private def runWith(name: String, lower: Lower, extra: List[Fn], lets: (Var, Rhs)*) =
    run(name, main(witnesses ++ lets*) :: extra ++ lower.functions)

  test("Numeric.add and Show.show through tables at u8, i64 and f64; a u8 add wraps as the direct one does"):
    val lower = Lower(unit)
    val cases = List(
      ("u8", Atom.IntC(200, u8), Atom.IntC(100, u8)),
      ("i64", Atom.IntC(-7, i64), Atom.IntC(3, i64)),
      ("f64", Atom.FloatC(BigDecimal("1.5"), CType.Float(64)), Atom.FloatC(BigDecimal("0.25"), CType.Float(64)))
    )
    val lets = cases.flatMap { (t, a, b) =>
      val (num, shows) = (tableOf(lower, prim(t), "Numeric"), tableOf(lower, prim(t), "Show"))
      val (viaTable, direct) = (v(s"t.$t", a.tpe), v(s"d.$t", a.tpe))
      val (s1, s2) = (v(s"s1.$t", CType.Boxed), v(s"s2.$t", CType.Boxed))
      List(
        viaTable -> Rhs.CallTable(num, at("Numeric", "add"), List(a, b)),
        direct -> Rhs.Prim(PrimOp.Add, List(a, b)),
        s1 -> Rhs.CallTable(shows, at("Show", "show"), List(viaTable)),
        print(s1),
        s2 -> Rhs.CallTable(shows, at("Show", "show"), List(direct)),
        print(s2)
      )
    }
    val (out, counts, ir) = runWith("numeric", lower, Nil, lets*)
    assertEquals(out, "44\n44\n-4\n-4\n1.75\n1.75\n")
    leakFree(counts)
    assert("call \\S+ @\"fx_u_u8\\.add\"".r.findFirstIn(ir).isEmpty, ir)
    assert(ir.contains("@\"fx_t_u8:Numeric\" = private unnamed_addr constant [11 x { ptr, ptr }]"), ir)

  test("Eq, lt, le, fromI64 and negate at primitive types, and String's Eq, Concat and Show, through tables"):
    val lower = Lower(unit)
    val bools = tableOf(lower, prim("bool"), "Show")
    val strings = (cap: String) => tableOf(lower, data("String"), cap)
    val checks = List[(String, CType, String, List[Atom])](
      ("u8", CType.Bool, "eq", List(Atom.IntC(3, u8), Atom.IntC(3, u8))),
      ("u8", CType.Bool, "lt", List(Atom.IntC(200, u8), Atom.IntC(100, u8))),
      (
        "i8",
        CType.Bool,
        "lt",
        List(Atom.IntC(-56, CType.Int(8, signed = true)), Atom.IntC(100, CType.Int(8, signed = true)))
      ),
      (
        "f64",
        CType.Bool,
        "le",
        List(Atom.FloatC(BigDecimal("2.5"), CType.Float(64)), Atom.FloatC(BigDecimal("2.5"), CType.Float(64)))
      ),
      ("u8", u8, "fromI64", List(Atom.IntC(300, i64))),
      ("i64", i64, "negate", List(Atom.IntC(7, i64)))
    )
    val lets = checks.zipWithIndex.flatMap { case ((t, rt, member, args), i) =>
      val (r, s) = (v(s"r$i", rt), v(s"s$i", CType.Boxed))
      val shows = if rt == CType.Bool then bools else tableOf(lower, prim(t), "Show")
      List(
        r -> Rhs.CallTable(tableOf(lower, prim(t), "Numeric"), at("Numeric", member), args),
        s -> Rhs.CallTable(shows, at("Show", "show"), List(r)),
        print(s)
      )
    }
    val (a, b, c, eq, eqs, cd, cds) = (
      v("a", CType.Boxed),
      v("b", CType.Boxed),
      v("c", CType.Boxed),
      v("eq", CType.Bool),
      v("eqs", CType.Boxed),
      v("cd", CType.Boxed),
      v("cds", CType.Boxed)
    )
    val text = List(
      a -> fresh("ab"),
      b -> fresh("ab"),
      eq -> Rhs.CallTable(strings("Eq"), at("Eq", "eq"), List(a, b)),
      eqs -> Rhs.CallTable(bools, at("Show", "show"), List(eq)),
      print(eqs),
      c -> fresh("cd"),
      cd -> Rhs.CallTable(strings("Concat"), at("Concat", "concat"), List(Atom.StrC("ab"), c)),
      cds -> Rhs.CallTable(strings("Show"), at("Show", "show"), List(cd)),
      print(cds)
    )
    val (out, counts, _) = runWith("compare", lower, Nil, lets ++ text*)
    assertEquals(out, "true\nfalse\ntrue\ntrue\n44\n-7\ntrue\nabcd\n")
    leakFree(counts)

  /** `[1, 2, 3]` of `u64` in `xs`: `Cons` (tag 0) cells from the last back to the static `Nil` (tag 1). */
  private def numbers(xs: Var): List[(Var, Rhs)] =
    val cells = List(v("c3", CType.Boxed), v("c2", CType.Boxed), xs)
    (v("nil", CType.Boxed) -> Rhs.Con(1, Nil)) :: List(3, 2, 1).zip(cells).zip(v("nil", CType.Boxed) :: cells).map {
      case ((n, cell), next) => cell -> Rhs.Con(0, List(Atom.IntC(n, u64), next))
    }

  test("fold, length, map and find on List of u64 through its tables, with immediate and reference results"):
    val lower = Lower(unit)
    val list = data("List", prim("u64"))
    val (folding, seq, functor) =
      (tableOf(lower, list, "Folding"), tableOf(lower, list, "Sequencing"), tableOf(lower, list, "Functor"))
    val optional = tableOf(lower, data("Maybe", prim("u64")), "Optional")
    val showU64 = tableOf(lower, prim("u64"), "Show")
    val (acc, x, s) = (v("acc", u64), v("x", u64), v("s", CType.Boxed))
    val closures = List(
      code("sum", List(acc, x), u64)(v("r", u64) -> Rhs.Prim(PrimOp.Add, List(acc, x)))(v("r", u64)),
      code("digits", List(v("acc", CType.Boxed), x), CType.Boxed)(
        s -> Rhs.CallTable(showU64, at("Show", "show"), List(x)),
        v("r", CType.Boxed) -> rt("fx_str_concat", v("acc", CType.Boxed), s)
      )(v("r", CType.Boxed)),
      code("double", List(x), u64)(v("r", u64) -> Rhs.Prim(PrimOp.Mul, List(x, Atom.IntC(2, u64))))(v("r", u64)),
      code(
        "two",
        List(x),
        CType.Bool
      )(v("r", CType.Bool) -> Rhs.Prim(PrimOp.Eq, List(x, Atom.IntC(2, u64))))(v("r", CType.Bool)),
      code("some", List(x), CType.Boxed)(s -> Rhs.CallTable(showU64, at("Show", "show"), List(x)))(s),
      code("none", Nil, CType.Boxed)()(Atom.StrC("none"))
    )
    val closure = (n: String) => v(s"k.$n", CType.Boxed) -> Rhs.Closure(n, Nil)
    val shown = (r: Var) =>
      List(
        v(s"s.${r.name}", CType.Boxed) -> Rhs.CallTable(showU64, at("Show", "show"), List(r)),
        print(v(s"s.${r.name}", CType.Boxed))
      )
    val (xs, total, text, n, doubled, found, label) =
      (
        v("xs", CType.Boxed),
        v("total", u64),
        v("text", CType.Boxed),
        v("n", u64),
        v("doubled", CType.Boxed),
        v("found", CType.Boxed),
        v("label", CType.Boxed)
      )
    val lets = numbers(xs) ++ closures.map(f => closure(f.name)) ++ List(
      total -> Rhs.CallTable(folding, at("Folding", "fold"), List(wi, xs, Atom.IntC(0, u64), v("k.sum", CType.Boxed))),
      text -> Rhs.CallTable(folding, at("Folding", "fold"), List(wr, xs, Atom.StrC(">"), v("k.digits", CType.Boxed))),
      print(text),
      n -> Rhs.CallTable(seq, at("Sequencing", "length"), List(xs)),
      doubled -> Rhs.CallTable(functor, at("Functor", "map"), List(wi, xs, v("k.double", CType.Boxed))),
      v("total2", u64) ->
        Rhs.CallTable(folding, at("Folding", "fold"), List(wi, doubled, Atom.IntC(0, u64), v("k.sum", CType.Boxed))),
      found -> Rhs.CallTable(folding, at("Folding", "find"), List(xs, v("k.two", CType.Boxed))),
      label -> Rhs.CallTable(
        optional,
        at("Optional", "fold"),
        List(wr, found, v("k.some", CType.Boxed), v("k.none", CType.Boxed))
      ),
      print(label)
    ) ++ shown(total) ++ shown(n) ++ shown(v("total2", u64))
    val (out, counts, ir) = runWith("lists", lower, closures, lets*)
    assertEquals(out, ">123\n2\n6\n3\n12\n")
    leakFree(counts)
    assertEquals("define i64 @\"fx_u_List of A\\.foldLeft\\[R\\]\"".r.findAllIn(ir).size, 1, ir)
    assert(ir.contains("call i64 @\"fx_u_List of A.foldLeft[R]\"(ptr @fx_w_imm, ptr %"), ir)

  test("a List of String folds through the same polymorphic member, with the reference witness for its elements"):
    val lower = Lower(unit)
    val folding = tableOf(lower, data("List", data("String")), "Folding")
    val (acc, x) = (v("acc", CType.Boxed), v("x", CType.Boxed))
    val join =
      code("join", List(acc, x), CType.Boxed)(v("r", CType.Boxed) -> rt("fx_str_concat", acc, x))(v("r", CType.Boxed))
    val (a, b, xs, nil, text) =
      (v("a", CType.Boxed), v("b", CType.Boxed), v("xs", CType.Boxed), v("nil", CType.Boxed), v("text", CType.Boxed))
    val (out, counts, ir) = runWith(
      "strings",
      lower,
      List(join),
      nil -> Rhs.Con(1, Nil),
      a -> fresh("de"),
      v("c1", CType.Boxed) -> Rhs.Con(0, List(a, nil)),
      b -> fresh("bc"),
      xs -> Rhs.Con(0, List(b, v("c1", CType.Boxed))),
      v("k", CType.Boxed) -> Rhs.Closure("join", Nil),
      text -> Rhs.CallTable(folding, at("Folding", "fold"), List(wr, xs, Atom.StrC("a"), v("k", CType.Boxed))),
      print(text)
    )
    assertEquals(out, "abcde\n")
    leakFree(counts)
    assert(ir.contains("call i64 @\"fx_u_List of A.foldLeft[R]\"(ptr @fx_w_ref, ptr %"), ir)

  test("a sum capability's fold entry reorders the callbacks to the data type's variant order"):
    val lower = Lower(unit)
    val res = tableOf(lower, data("Res", data("String"), prim("u64")), "Result")
    val showU64 = tableOf(lower, prim("u64"), "Show")
    val closures = List(
      code("ok", List(v("x", u64)), CType.Boxed)(v("s", CType.Boxed) ->
        Rhs.CallTable(showU64, at("Show", "show"), List(v("x", u64))))(v("s", CType.Boxed)),
      code("err", List(v("e", CType.Boxed)), CType.Boxed)()(v("e", CType.Boxed))
    )
    val folded = (value: Var) =>
      List(
        v(s"f.${value.name}", CType.Boxed) ->
          Rhs.CallTable(res, at("Result", "fold"), List(wr, value, v("k.ok", CType.Boxed), v("k.err", CType.Boxed))),
        print(v(s"f.${value.name}", CType.Boxed))
      )
    val (good, bad, e) = (v("good", CType.Boxed), v("bad", CType.Boxed), v("e", CType.Boxed))
    val lets = List(
      v("k.ok", CType.Boxed) -> Rhs.Closure("ok", Nil),
      v("k.err", CType.Boxed) -> Rhs.Closure("err", Nil),
      good -> Rhs.CallTable(res, at("Result", "ok"), List(Atom.IntC(5, u64))),
      e -> fresh("oops"),
      bad -> Rhs.CallTable(res, at("Result", "err"), List(e))
    ) ++ folded(good) ++ folded(bad)
    val (out, counts, _) = runWith("reorder", lower, closures, lets*)
    assertEquals(out, "5\noops\n")
    leakFree(counts)

  test("a three-way reordering: Cmp's variants Gt, Lt, Same against Ordering's lt, eq, gt"):
    val lower = Lower(unit)
    val ordering = tableOf(lower, data("Cmp"), "Ordering")
    val words = List("lt", "eq", "gt")
    val closures = words.map(w => code(s"say_$w", Nil, CType.Boxed)()(Atom.StrC(w)))
    val lets = words.map(w => v(s"k.$w", CType.Boxed) -> Rhs.Closure(s"say_$w", Nil)) ++ words.flatMap { w =>
      val (value, said) = (v(s"v.$w", CType.Boxed), v(s"s.$w", CType.Boxed))
      List(
        value -> Rhs.CallTable(ordering, at("Ordering", w), Nil),
        said -> Rhs.CallTable(ordering, at("Ordering", "fold"), wr :: value :: words.map(x => v(s"k.$x", CType.Boxed))),
        print(said)
      )
    }
    val (out, counts, _) = runWith("cycle", lower, closures, lets*)
    assertEquals(out, "lt\neq\ngt\n")
    leakFree(counts)

  test("the canonical member order of a table is frozen; a member the type does not define is an error"):
    val order = (cap: String) => Tables.members(symbols, symbols.scope(cap))
    assertEquals(
      order("Numeric"),
      List("fromI64", "add", "sub", "mul", "div", "rem", "negate", "eq", "compare", "lt", "le")
    )
    assertEquals(order("Ord"), List("compare", "eq", "lt", "le"))
    assertEquals(order("Optional"), List("some", "none", "fold", "map", "foldRight", "find"))
    assertEquals(order("Folding"), List("fold", "foldRight", "find"))
    assertEquals(order("Show"), List("show"))
    assertEquals(
      Lower(unit).table(prim("bool"), symbols.scope("Numeric")),
      Left("`bool` does not define `Numeric.fromI64`")
    )
    assertEquals(
      Lower(unit).table(data("List", prim("u64")), symbols.scope("Numeric")),
      Left("`List of u64` does not define `Numeric.fromI64`")
    )
    val a = fixed.core.Type.TypeRef(symbols.typeParamsOf(symbols.scope("List")).head, Nil)
    assert(Lower(unit).table(data("List", a), symbols.scope("Folding")).left.exists(_.contains("no static table")))
