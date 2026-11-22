package fixed.backend

import fixed.ast.Trees
import fixed.qtt.HandlerKind
import fixed.util.SourceFile
import fixed.{CompilationUnit, Compiler}

/** Escape sets, lifetime classes, cohesive groups, garbage-free carry, handler kinds and fork
  * facts (`docs/plans/phase-3.4-qtt.md` 3.4.M4, `spec/memory_management.md` §5, §14.1, §20).
  */
class MemorySuite extends munit.FunSuite:

  private val header = "use std.io.Console\n\ndata P:\n    P(a: u64, b: u64)\n\n"

  private def unit(code: String): CompilationUnit =
    val u = Compiler.compile(CompilationUnit(SourceFile.fromString("<unit>", code)))
    assert(!u.hasErrors, u.diagnostics)
    u

  private def analysed(code: String, body: String): Analysed =
    val u = unit(code + s"fn main() -> () with Console =\n    Console.printLine(($body).show())\n")
    Backend.analyse(u).fold(ds => fail(s"lowering failed: $ds"), identity)

  /** The classes of `fn`'s allocations, in order. */
  private def classes(a: Analysed, fn: String): List[String] =
    a.memory.allocations.find(_._1.startsWith(fn)).fold(fail(s"no $fn"))(_._2.map(_.cls.show))

  test("a value used only here is Frame; a returned one is Dest; one stored into a returned one too"):
    val code = header + "fn local(x: u64) -> u64 =\n    let p = P.P(x, 1)\n    p.a + p.b\n\n" +
      "fn make(x: u64) -> P = P.P(x, x)\n\n" +
      "data Q:\n    Q(p: P)\n\nfn wrap(x: u64) -> Q = Q.Q(P.P(x, x))\n\n" +
      "fn get(q: Q) -> u64 =\n    match q:\n        Q.Q(p) => p.a\n\n"
    val a = analysed(code, "local(1) + make(2).a + get(wrap(3))")
    assertEquals(classes(a, "local"), List("Frame(local)"))
    assertEquals(classes(a, "make"), List("Dest(result)"))
    assertEquals(classes(a, "wrap"), List("Dest(result)", "Dest(result)"))
    val inner = a.memory.allocations("wrap").find(_.cls == LifetimeClass.Dest).get
    assert(inner.escapes.exists { case _: Escape.Field => true; case _ => false } || inner.escapes(Escape.Result))

  test("a value passed to a closure the compiler cannot see is Heap, with the reason"):
    val code = header + "fn apply(f: P -> u64, x: u64) -> u64 = f(P.P(x, x))\n\n"
    val a = analysed(code, "apply((p) -> p.a, 1)")
    val alloc = a.memory.allocations("apply").head
    assertEquals(alloc.cls, LifetimeClass.Heap)
    assert(alloc.reasons.exists(_.contains("closure")), alloc.reasons)
    assertEquals(a.memory.escapes("apply"), List(ParamEscape.None, ParamEscape.None))

  test("a list literal kept beyond the call is one cohesive group: Region of its root"):
    val code = "use std.io.Console\n\nfn keep(f: (List of u64) -> u64) -> u64 = f([1, 2, 3])\n\n"
    val a = analysed(code, "keep((xs) -> xs.length())")
    val cls = a.memory.allocations("keep").map(_.cls)
    assert(cls.nonEmpty && cls.forall { case _: LifetimeClass.Region => true; case _ => false }, cls)
    assertEquals(cls.distinct.size, 1)

  test("a carried accumulator: garbage-free when the step keeps it, long-lived when it drops it"):
    val code = "use std.io.Console\n\nfn upto(n: u64) -> List of u64 =\n" +
      "    List.unfold(0, (i) ->\n        if i >= n:\n            List.Nil\n        else:\n" +
      "            List.Cons(i, i + 1)\n    )\n\n" +
      "fn grow(n: u64) -> u64 = upto(n).fold(List.Nil, (acc, x) -> List.Cons(x, acc)).length()\n\n" +
      "fn churn(n: u64) -> u64 = upto(n).fold(List.Cons(0, List.Nil), (acc, x) -> List.Cons(x, List.Nil)).length()\n\n"
    val a = analysed(code, "grow(3) + churn(3)")
    val carried = (fn: String) =>
      a.memory.allocations(fn).filter(_.escapes.exists { case _: Escape.Carried => true; case _ => false })
    assert(carried("churn").forall(_.cls == LifetimeClass.Heap), carried("churn"))
    assert(carried("churn").nonEmpty)
    assert(carried("churn").head.reasons.exists(_.contains("garbage-free")), carried("churn"))

  test("a tree fold with pure steps is a fork site; a sequence fold and an effectful step are not"):
    val code = "use std.io.Console\n\ndata Tree:\n    Leaf\n    Node(left: Tree, right: Tree)\n\n" +
      "fn make(d: u64) -> Tree =\n    Tree.unfold(d, (k) ->\n        if k == 0:\n            Tree.Leaf\n" +
      "        else:\n            Tree.Node(k - 1, k - 1)\n    )\n\n" +
      "fn count(t: Tree) -> u64 = t.fold(() -> 1, (l, r) -> l + r + 1)\n\n" +
      "fn sum(xs: List of u64) -> u64 = xs.fold(0, (a, x) -> a + x)\n\n"
    val a = analysed(code, "count(make(3)) + sum([1, 2])")
    val fact = (fn: String) => a.memory.forks.find(_._1.startsWith(fn)).flatMap(_._2.headOption).getOrElse(fail(fn))
    assert(fact("count").admissible, fact("count"))
    assert(fact("make").admissible, fact("make"))
    assert(!fact("sum").admissible && fact("sum").reason.contains("associative"), fact("sum"))

  test("handler kinds from clause shapes (Rule MM14.1)"):
    val ask = "use std.io.Console\n\neffect Ask:\n    fn ask -> u64\n\neffect Stop:\n    fn stop -> !\n\n"
    val kinds = (body: String) =>
      val u = unit(ask + s"fn f(c: bool) -> u64 =\n    handle Ask.ask + 1:\n        $body\n")
      Trees.subtrees(u.tree.get).collect { case h: Trees.HandleExpr => u.quantities.get.handler(h) }.flatten
    assertEquals(kinds("Ask.ask => resume(1)"), List(HandlerKind.TailResumptive))
    assertEquals(kinds("Ask.ask => resume(1) + 1"), List(HandlerKind.CapturingLocal))
    assertEquals(kinds("Ask.ask => if c: resume(1) else: 7"), List(HandlerKind.TailResumptive))
    val stop = unit(ask + "fn g() -> u64 =\n    handle Stop.stop:\n        Stop.stop => 0\n")
    assertEquals(
      Trees.subtrees(stop.tree.get).collect { case h: Trees.HandleExpr => stop.quantities.get.handler(h) }.flatten,
      List(HandlerKind.Abortive)
    )

  test("every run example and benchmark gets classes, escapes and fork facts"):
    val files = List("01_basics", "03_option_result", "04_json", "05_phantom_types").map(n => s"examples/$n.fixed") ++
      List("bintrees", "nat_loop", "list_pipeline", "records", "fail_chain").map(n => s"bench/runtime/$n.fixed")
    files.foreach { f =>
      val u = unit(java.nio.file.Files.readString(java.nio.file.Path.of(f)))
      val a = Backend.analyse(u).fold(ds => fail(s"$f: $ds"), identity)
      assertEquals(a.memory.allocations.keySet, a.fns.map(_.name).toSet, f)
    }

  test("an allocation in the prelude's code is shown at its position in the prelude's file"):
    val u = unit("use std.io.Console\nfn main() -> () with Console = Console.printLine(repeatStr(\"ab\", 3))\n")
    val report = MemoryReport.text(u, Backend.analyse(u).fold(ds => fail(s"lowering failed: $ds"), identity))
    val prelude = scala.io.Source.fromResource("stdlib/prelude.fixed").getLines().toList
    val body = prelude.indexWhere(_.startsWith("fn repeatStr(")) + 2
    assert(body > 1, "the prelude has repeatStr")
    assert(report.contains(s"at: stdlib/prelude.fixed:$body:5"), report)

  test("the report gives each data type its layout: an immediate's width, or why it is a cell (Rule CM3.5)"):
    val src = java.nio.file.Files.readString(java.nio.file.Path.of("compiler/src/test/fixed/modes/small_values.fixed"))
    val u = unit(src)
    val report = MemoryReport.json(u, Backend.analyse(u).fold(ds => fail(s"lowering failed: $ds"), identity))
    val layouts = List(
      "Pair32" -> ("imm8", "8 bytes: tag 0, fields 8"),
      "Wide" -> ("cell", "9 bytes, more than 8"),
      "Signed" -> ("imm8", "7 bytes: tag 0, fields 7"),
      "Mixed" -> ("imm8", "7 bytes: tag 0, fields 7"),
      "Inner" -> ("imm8", "8 bytes: tag 0, fields 8"),
      "Choice" -> ("imm4", "3 bytes: tag 1, fields 2"),
      "Cell" -> ("cell", "field `v` takes its layout from a type argument (not built)"),
      "List" -> ("cell", "recursive")
    )
    val edges = "use std.io.Console\n\ndata Named:\n    Named(s: String, n: u8)\n\n" +
      "data Wide:\n    Wide(x: u64, y: u8)\n\ndata Holder:\n    Holder(w: Wide)\n\n" +
      "data A:\n    A(b: B)\n\ndata B:\n    End\n    More(a: A)\n\n" +
      "data Full:\n    Seven(a: u32, b: u16, c: u8)\n    Other\n\n" +
      "data Over:\n    Eight(a: u64)\n    None\n\n" +
      "fn main() -> () with Console =\n    Console.printLine(Named.Named(\"a\", 1).show())\n" +
      "    Console.printLine(Holder.Holder(Wide.Wide(1, 2)).show())\n" +
      "    Console.printLine(A.A(B.End).show())\n    Console.printLine(Full.Other.show())\n" +
      "    Console.printLine(Over.None.show())\n"
    val e = unit(edges)
    val more = MemoryReport.json(e, Backend.analyse(e).fold(ds => fail(s"lowering failed: $ds"), identity))
    val edgeLayouts = List(
      "Named" -> ("cell", "field `s` holds a reference"),
      "Holder" -> ("cell", "field `w` holds a reference"),
      "A" -> ("cell", "recursive"),
      "B" -> ("cell", "field `a` holds a reference"),
      "Full" -> ("imm8", "8 bytes: tag 1, fields 7"),
      "Over" -> ("cell", "9 bytes, more than 8")
    )
    edgeLayouts.foreach { case (t, (layout, why)) =>
      val entry = s"""{"type":"$t","layout":"$layout","why":"$why"}"""
      assert(more.contains(entry), s"$entry: ${more.drop(more.indexOf("\"data\""))}")
    }
    layouts.foreach { case (t, (layout, why)) =>
      val entry = s"""{"type":"$t","layout":"$layout","why":"$why"}"""
      assert(report.contains(entry), s"$entry: ${report.drop(report.indexOf("\"data\""))}")
    }
