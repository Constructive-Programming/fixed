package fixed.parsing

import fixed.ast.{Tree, Trees}
import fixed.util.SourceFile
import munit.FunSuite

/** Parser: declarations (data, cap, type alias, match, satisfies, effect). */
class ParserDeclSuite extends FunSuite with ParserTestSupport:

  test("M4.6: simple effect with one op"):
    val src = SourceFile.fromString(
      "<test>",
      """effect Fail of E:
        |    fn fail(error: E) -> !
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.isLinear, false)
        assertEquals(e.name, "Fail")
        assertEquals(e.ofParams.length, 1)
        assertEquals(e.members.length, 1)
        e.members.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "fail")
          case other                    => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected EffectDecl, got $other")

  test("M4.6: linear effect"):
    val src = SourceFile.fromString(
      "<test>",
      """linear effect File:
        |    fn open(path: String) -> ()
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.isLinear, true)
      case other => fail(s"expected linear EffectDecl, got $other")

  test("M4.6: effect with no params, parameterless op"):
    val src = SourceFile.fromString(
      "<test>",
      """effect Channel of A:
        |    fn send(value: A) -> ()
        |    fn receive -> A
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(e: Trees.EffectDecl), _) =>
        assertEquals(e.members.length, 2)
        e.members.last match
          case im: Trees.InstanceMethod =>
            assertEquals(im.name, "receive")
            assertEquals(im.params, Nil)
          case other => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected EffectDecl, got $other")

  test("M4.5: simple satisfies with constructor mappings"):
    val src = SourceFile.fromString(
      "<test>",
      """Maybe satisfies Optional:
        |    Just as some
        |    Nothing as none
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.typeName, "Maybe")
        s.capRef match
          case Trees.CapRef("Optional", _, _) => ()
          case other                          => fail(s"expected CapRef('Optional'), got $other")
        assertEquals(s.mappings.length, 2)
        assertEquals(
          s.mappings.collect { case Trees.ConstructorMapping(v, c, _) => (v, c) },
          List(("Just", "some"), ("Nothing", "none"))
        )
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: satisfies with `Self as` and `impossible as`"):
    val src = SourceFile.fromString(
      "<test>",
      """u64 satisfies Optional of Self:
        |    Self as some
        |    impossible as none
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.typeName, "u64")
        val selfs = s.mappings.collect { case Trees.SelfMapping(c, _) => c }
        val impossibles = s.mappings.collect { case Trees.ImpossibleMapping(c, _) => c }
        assertEquals(selfs, List("some"))
        assertEquals(impossibles, List("none"))
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: satisfies with method override"):
    val src = SourceFile.fromString(
      "<test>",
      """Maybe satisfies Optional:
        |    fn isDefined = match self:
        |        Maybe.Nothing => false
        |        _ => true
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.mappings.length, 1)
        s.mappings.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "isDefined")
          case other                    => fail(s"expected InstanceMethod, got $other")
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.5: empty satisfies (no body)"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "Maybe satisfies Optional\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(s: Trees.SatisfiesDecl), _) =>
        assertEquals(s.mappings, Nil)
      case other => fail(s"expected SatisfiesDecl, got $other")

  test("M4.4: simple match on data variants"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North => 1
        |    Direction.South => 2
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(fn: Trees.FnDecl), _) =>
        fn.body match
          case Some(m: Trees.MatchExpr) =>
            assertEquals(m.arms.length, 2)
            assertEquals(
              m.arms.map(_.pattern).collect {
                case Trees.DataVariantPat(Some(q), v, _, _) => s"$q.$v"
              },
              List("Direction.North", "Direction.South")
            )
          case other => fail(s"expected MatchExpr body, got $other")
      case other => fail(s"expected FnDecl, got $other")

  test("M4.4: wildcard arm + constructor arm"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Maybe of u64) -> u64 = match x:
        |    Maybe.Just(v) => v
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        m.arms.last.pattern match
          case _: Trees.WildcardPat => ()
          case other                => fail(s"expected wildcard last arm, got $other")
      case other => fail(s"expected FnDecl with MatchExpr body, got $other")

  test("M4.4: or-pattern flattens left-associatively"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North | Direction.South | Direction.East => 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        /** First arm should be OrPat(OrPat(N, S), E). */
        m.arms.head.pattern match
          case Trees.OrPat(Trees.OrPat(_, _, _), _, _) => ()
          case other                                   => fail(s"expected nested OrPat, got $other")
      case other => fail(s"expected MatchExpr, got $other")

  test("M4.4: guard clause"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: u64) -> u64 = match x:
        |    n if n == 0 => 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        assert(m.arms.head.guard.isDefined, "expected guard on first arm")
      case other => fail(s"expected MatchExpr, got $other")

  test("M4.4: arm with multi-line block body"):
    val src = SourceFile.fromString(
      "<test>",
      """fn f(x: Direction) -> u64 = match x:
        |    Direction.North =>
        |        let y = 1
        |        y + 1
        |    _ => 0
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.FnDecl(_, _, _, _, _, Some(m: Trees.MatchExpr), _)), _) =>
        m.arms.head.body match
          case Trees.Block(stmts, _) => assert(stmts.length >= 2)
          case other                 => fail(s"expected Block body for first arm, got $other")
      case other => fail(s"expected MatchExpr, got $other")

  test("M4.3: simple chain alias"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "type Collection = Sequencing + Functor + Folding\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(t: Trees.TypeAlias), _) =>
        assertEquals(t.name, "Collection")
        assertEquals(t.valueParams, Nil)
        t.rhs match
          case Trees.IsBound(caps, _) =>
            assertEquals(
              caps.collect { case Trees.CapRef(n, _, _) => n },
              List("Sequencing", "Functor", "Folding")
            )
          case other => fail(s"expected IsBound chain, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  test("M4.3: single-cap alias is unwrapped"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "type Foo = Functor\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(Trees.TypeAlias(_, Nil, rhs, _)), _) =>
        rhs match
          case Trees.CapRef("Functor", _, _) => ()
          case other                         => fail(s"expected single CapRef rhs, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  test("M4.3: parameterised alias with refinement call"):
    val pr = Parser.parse(SourceFile.fromString(
      "<test>",
      "type PortRange(min: u16, max: u16) = u16 + between(min, max)\n"
    ))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(t: Trees.TypeAlias), _) =>
        assertEquals(t.valueParams.map(_.name), List("min", "max"))
        t.rhs match
          case Trees.IsBound(caps, _) =>
            assertEquals(caps.length, 2)
            caps(1) match
              case Trees.RefinementCall("between", args, _) => assertEquals(args.length, 2)
              case other                                    => fail(s"expected RefinementCall, got $other")
          case other => fail(s"expected IsBound chain, got $other")
      case other => fail(s"expected TypeAlias, got $other")

  test("M4.2: cap with instance and static methods"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sequencing:
        |    fn head -> is Optional of Part
        |    fn tail -> Self
        |    Self.fn cons(h: Part, t: Self) -> Self
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.name, "Sequencing")
        assertEquals(c.body.length, 3)
        c.body.head match
          case im: Trees.InstanceMethod => assertEquals(im.name, "head")
          case other                    => fail(s"expected InstanceMethod, got $other")
        c.body.last match
          case sm: Trees.StaticMethod =>
            assertEquals(sm.name, "cons")
            assertEquals(sm.params.length, 2)
          case other => fail(s"expected StaticMethod, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: cap extends another cap"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Optional extends Functor + Folding:
        |    fn isDefined -> Boolean
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.extendsList.length, 2)
        assertEquals(
          c.extendsList.collect { case Trees.CapRef(n, _, _) => n },
          List("Functor", "Folding")
        )
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: cap of-clause with multiple type params"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sized of (Part, Size is Numeric):
        |    fn size -> Size
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.ofParams.length, 2)
        c.ofParams(1) match
          case Trees.NamedAlias("Size", _, _) => ()
          case other                          => fail(s"expected NamedAlias for Size, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.2: empty cap (no `:` body)"):
    val pr = Parser.parse(SourceFile.fromString("<test>", "cap Locked\n"))
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.name, "Locked")
        assertEquals(c.body, Nil)
      case other => fail(s"expected single empty CapDecl, got $other")

  test("M4.2: prop declaration in cap body"):
    val src = SourceFile.fromString(
      "<test>",
      """cap Sorted:
        |    prop sorted: true
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(c: Trees.CapDecl), _) =>
        assertEquals(c.body.length, 1)
        c.body.head match
          case p: Trees.PropDecl => assertEquals(p.name, "sorted")
          case other             => fail(s"expected PropDecl, got $other")
      case other => fail(s"expected single CapDecl, got $other")

  test("M4.1: simple unit-only enum"):
    val src = SourceFile.fromString(
      "<test>",
      """data Direction:
        |    North
        |    South
        |    East
        |    West
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Direction")
        assertEquals(d.ofParams, Nil)
        val vs = d.variants
        assertEquals(vs.map(_.name), List("North", "South", "East", "West"))
        assert(vs.forall(_.fields.isEmpty))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: mixed-variants with fields and capability constraints"):
    val src = SourceFile.fromString(
      "<test>",
      """data Color:
        |    Red
        |    Green
        |    RGB(red: N is Numeric, green: N, blue: N)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        val vs = d.variants
        assertEquals(vs.map(_.name), List("Red", "Green", "RGB"))
        val rgb = vs.find(_.name == "RGB").get
        assertEquals(rgb.fields.map(_.name), List("red", "green", "blue"))

        /** The first field's type carries the `is Numeric` named alias. */
        rgb.fields.head.typeAnn match
          case Trees.NamedAlias("N", caps, _) =>
            assert(caps.exists {
              case Trees.CapRef("Numeric", _, _) => true
              case _                             => false
            })
          case other => fail(s"expected NamedAlias for first field, got $other")
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with single type param"):
    val src = SourceFile.fromString(
      "<test>",
      """data Maybe of A:
        |    Just(value: A)
        |    Nothing
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Maybe")
        assertEquals(d.ofParams.length, 1)
        d.ofParams.head match
          case Trees.TypeRef("A", None, _) => ()
          case other                       => fail(s"expected ofParam=TypeRef('A'), got $other")
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with paren tuple of type params"):
    val src = SourceFile.fromString(
      "<test>",
      """data Tagged of (Tag, Value):
        |    Tagged(value: Value)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.ofParams.length, 2)
        assertEquals(d.ofParams.collect { case Trees.TypeRef(n, _, _) => n }, List("Tag", "Value"))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: single-ctor sugar"):
    val src = SourceFile.fromString(
      "<test>",
      "data Config(host: String, port: u16, debug: bool)\n"
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.name, "Config")
        val vs = d.variants
        assertEquals(vs.length, 1)
        val v = vs.head
        assertEquals(v.name, "Config")
        assertEquals(v.fields.map(_.name), List("host", "port", "debug"))
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: of-clause with OfValueParam (named value param + default)"):
    val src = SourceFile.fromString(
      "<test>",
      """data Bounded of (N is Numeric, lo: N = 0, hi: N = 10):
        |    Bounded(value: N)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        assertEquals(d.ofParams.length, 3)

        /** First is a NamedAlias type expression (`N is Numeric`); the
          * other two are FnParam (lowered OfValueParam).
          */
        d.ofParams.head match
          case _: Trees.NamedAlias => ()
          case other               => fail(s"expected NamedAlias, got $other")
        d.ofParams.tail.foreach {
          case fp: Trees.FnParam => assert(fp.default.isDefined, "ofValueParam should carry default")
          case other             => fail(s"expected FnParam, got $other")
        }
      case other => fail(s"expected single DataDecl, got $other")

  test("M4.1: recursive references in fields"):
    val src = SourceFile.fromString(
      "<test>",
      """data Nat:
        |    Zero
        |    Succ(pred: Nat)
        |""".stripMargin
    )
    val pr = Parser.parse(src)
    assert(!pr.hasErrors, pr.diagnostics.toString)
    pr.tree match
      case Trees.CompilationUnit(List(d: Trees.DataDecl), _) =>
        val succ = d.variants.find(_.name == "Succ").get
        succ.fields.head.typeAnn match
          case Trees.TypeRef("Nat", None, _) => ()
          case other                         => fail(s"expected TypeRef('Nat'), got $other")
      case other => fail(s"expected single DataDecl, got $other")
