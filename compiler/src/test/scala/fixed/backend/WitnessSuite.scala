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
