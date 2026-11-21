package fixed.typer

import scala.collection.mutable

import fixed.ast.{Tree, Trees}
import fixed.core.{SymKind, SymbolId, Type, render}
import fixed.util.{DiagCode, Diagnostic, Severity, Span}

/** Rule 7.4.h: a named function that can reach itself in the static call graph of named functions
  * is recursive, and is accepted only when the recursion is structural or the function has
  * `Diverge` in its row; otherwise it is `T027`. The functions are the top-level and nested `fn`s,
  * the `satisfies` bodies and the default bodies of capabilities, the prelude's among them. An edge
  * is a name that refers to a function (a call or a value, also inside a lambda or a nested `fn`),
  * a member call or a comparison (`==`, `!=`, `<`, `<=`, `>`, `>=`) on a receiver of a known type that reaches a body,
  * and the declaration of a nested `fn` by its enclosing function. A call through a capability
  * table (a receiver of an abstract type) or through a closure value is not an edge. The derived
  * members (`fold`, `para`, `unfold`, `map`, `show`, ...) are not named functions of the program:
  * their recursion is the data's own structure (Rule 7.4).
  */
object Totality:

  private enum Node:
    case Fn(id: SymbolId)
    case Body(head: Type, member: SymbolId)
    case Default(member: SymbolId)

  /** A function's code: its node, the name the diagnostic shows, its declaration, and whether it is
    * the prelude's.
    */
  final private case class Code(node: Node, name: String, fn: Trees.FnLike, prelude: Boolean):
    /** Its parameters by position: `self` first in the body of an instance member. */
    def params: List[String] =
      fn match
        case _: Trees.InstanceMethod => "self" :: fn.params.map(_.name)
        case _                       => fn.params.map(_.name)

    /** The symbol whose signature gives its row. */
    def sig: SymbolId =
      node match
        case Node.Fn(id)     => id
        case Node.Body(_, m) => m
        case Node.Default(m) => m

  /** What a local name holds: parameter `i` of the function being walked, or a strict sub-value of
    * it (a variable a pattern binds inside a `match` on one of those), or anything else.
    */
  private enum Origin:
    case Arg(i: Int, strict: Boolean)
    case Local

  /** The origin of each local name in scope. */
  private type Env = Map[String, Origin]

  /** A use of function `to` at `span`; `args` are the trees passed to it (the receiver first), and
    * `descent` maps an argument position to the caller's parameter it is a strict sub-value of.
    * `call` is false only for the declaration of a nested `fn` by its enclosing function.
    */
  final private case class Edge(to: Node, span: Span, args: List[Tree], descent: Map[Int, Int], call: Boolean)

  def check(tree: Tree, s: TyperState): List[Diagnostic] =
    val graph = Graph(s)
    val items = tree match
      case Trees.CompilationUnit(items, _) => items
      case _                               => Nil
    graph.declare(items, prelude = false)
    graph.declare(Stdlib.prelude, prelude = true)

    /** The declared functions first: walking one appends its nested `fn`s to `codes`, and
      * `sequence` walks those itself.
      */
    val declared = graph.codes.toList
    declared.foreach(graph.walkCode)
    graph.report

  final private class Graph(s: TyperState):
    private val symbols = s.symbols
    val codes = mutable.ArrayBuffer.empty[Code]
    private val edges = mutable.Map.empty[Node, Vector[Edge]].withDefaultValue(Vector.empty)

    /** The declared functions once [[declare]] has run; nested ones are never looked up in it. */
    private lazy val known = codes.map(_.node).toSet

    /** The functions `items` declare, in the program's scope or the prelude's. */
    def declare(items: List[Tree], prelude: Boolean): Unit =
      val visible = symbols.scopeFor(prelude)
      items.foreach {
        case f: Trees.FnDecl if f.body.isDefined =>
          visible.get(f.name).filter(symbols(_).kind == SymKind.Fn).foreach(id =>
            codes += Code(Node.Fn(id), f.name, f, prelude)
          )
        case Trees.SatisfiesDecl(typeName, Trees.CapRef(capName, _, _), methods, _) =>
          val head = dataType(visible, typeName).getOrElse(Type.PrimType(typeName))
          for
            cap <- visible.get(capName).filter(symbols(_).kind == SymKind.Cap).toList
            case fn: Trees.FnLike <- methods if fn.body.isDefined
            entry <-
            (s.members.lookup(cap, SymKind.Method, fn.name) ++
              s.members.lookup(cap, SymKind.StaticMethod, fn.name)).headOption
          do codes += Code(Node.Body(head, entry.member.id), s"${symbols.render(head)}.${fn.name}", fn, prelude)
        case Trees.CapDecl(name, _, _, _, members, _) =>
          for
            cap <- visible.get(name).filter(symbols(_).kind == SymKind.Cap).toList
            case fn: Trees.FnLike <- members if fn.body.isDefined
            m <- symbols.membersOf(cap).find(m => m.name == fn.name && m.kind != SymKind.Variant)
          do codes += Code(Node.Default(m.id), s"$name.${fn.name}", fn, prelude)
        case _ => ()
      }

    def walkCode(c: Code): Unit =
      c.fn.body.foreach(walk(_, c, arguments(c), Map.empty))

    private def arguments(c: Code): Env =
      c.params.zipWithIndex.map((p, i) => p -> Origin.Arg(i, strict = false)).toMap

    private def edge(from: Code, to: Node, span: Span, args: List[Tree], env: Env, call: Boolean = true): Unit =
      val descent = args.zipWithIndex.flatMap {
        case (Trees.Ident(n, _), j) => env.get(n).collect { case Origin.Arg(i, true) => j -> i }
        case _                      => None
      }.toMap
      edges(from.node) = edges(from.node) :+ Edge(to, span, args, descent, call)

    /** The names pattern `p` binds when it matches a value of origin `of`: a binder inside a
      * variant, tuple or record pattern holds a strict sub-value of an argument, one at the top
      * holds the value itself.
      */
    private def bindings(p: Tree, of: Origin, inside: Boolean = false): Env =
      val sub = of match
        case a: Origin.Arg => a.copy(strict = true)
        case Origin.Local  => Origin.Local
      p match
        case Trees.BinderPat(n, _)                   => Map(n -> (if inside then sub else of))
        case Trees.DataVariantPat(_, _, fields, _)   => fields.toList.flatten.flatMap(bindings(_, of, true)).toMap
        case Trees.TuplePat(es, _)                   => es.flatMap(bindings(_, of, true)).toMap
        case Trees.StructDestructurePat(_, fs, _, _) => fs.flatMap(f => bindings(f._2, of, true)).toMap
        case Trees.OrPat(l, r, _)                    =>
          /** A name is a strict sub-value only if both sides bind it as one. */
          val right = bindings(r, of, inside)
          bindings(l, of, inside).map((n, o) => n -> (if right.get(n).contains(o) then o else Origin.Local))
        case Trees.GuardedPat(q, _, _) => bindings(q, of, inside)
        case other                     => locals(other)

    private def locals(p: Tree): Env =
      Trees.subtrees(p).collect {
        case Trees.BinderPat(n, _) => n -> Origin.Local
      }.toMap

    /** The function the name `name` refers to where `env` locals and `nested` functions are in scope. */
    private def named(name: String, c: Code, env: Env, nested: Map[String, Node]): Option[Node] =
      if env.contains(name) then None
      else
        nested.get(name).orElse(
          symbols.scopeFor(c.prelude).get(name).filter(symbols(_).kind == SymKind.Fn).map(Node.Fn(_))
        )

    /** The body a call of capability member `member` on a value of type `t` runs, if `t` is known. */
    private def reached(t: Option[Type], member: SymbolId): Option[Node] =
      t.collect {
        case Type.TypeRef(id, _) if symbols(id).kind == SymKind.Data => Type.TypeRef(id, Nil)
        case p: Type.PrimType                                        => p
      }.flatMap(head => List(Node.Body(head, member), Node.Default(member)).find(known))

    /** The data type `name` names in `visible`, at no arguments. */
    private def dataType(visible: Map[String, SymbolId], name: String): Option[Type] =
      visible.get(name).collect { case id if symbols(id).kind == SymKind.Data => Type.TypeRef(id, Nil) }

    /** The body static member `method` of the type `qualifier` names in `c` runs: found, as the
      * typer finds it, among the capabilities that type satisfies; their `satisfies` body for the
      * type, else the capability's default body.
      */
    private def static(c: Code, qualifier: String, method: String): Option[Node] =
      val head = (qualifier, c.node) match
        case ("Self", Node.Body(h, _)) => Some(h)
        case _                         => dataType(symbols.scopeFor(c.prelude), qualifier)
      head.flatMap { h =>
        val members = Members.expandAliases(symbols, symbols.satisfactionsOf(h)).collect { case Type.CapRef(cap, _) =>
          s.members.lookup(cap, SymKind.StaticMethod, method).map(_.member.id)
        }.flatten
        members.flatMap(m => List(Node.Body(h, m), Node.Default(m))).find(known)
      }

    private def preludeMember(cap: String, member: String): Option[SymbolId] =
      symbols.prelude.get(cap).flatMap(c => s.members.lookup(c, SymKind.Method, member).headOption).map(_.member.id)

    private def walk(t: Tree, c: Code, env: Env, nested: Map[String, Node]): Unit =
      def go(x: Tree) = walk(x, c, env, nested)

      /** `f` applied to `args` (before its own, for a static call): an edge when `f` names a function. */
      def applied(f: Tree, args: List[Tree]): Unit =
        f match
          case Trees.Ident(name, span) => named(name, c, env, nested).foreach(edge(c, _, span, args, env))
          case sc: Trees.StaticCall    =>
            val all = args ++ sc.args.getOrElse(Nil)
            static(c, sc.qualifier, sc.method).foreach(edge(c, _, sc.span, all, env))
            sc.args.toList.flatten.foreach(go)
          case other => go(other)
      t match
        case _: Trees.Ident | _: Trees.StaticCall => applied(t, Nil)
        case Trees.Apply(f, args, _)              =>
          applied(f, args)
          args.foreach(go)
        case Trees.Pipe(lhs, rhs, _) =>
          /** `x |> f(a)` is `f(x, a)`, and `x |> f` is `f(x)`. */
          rhs match
            case Trees.Apply(f, args, _) =>
              applied(f, lhs :: args)
              args.foreach(go)
            case other => applied(other, List(lhs))
          go(lhs)
        case Trees.Block(stmts, _)         => sequence(stmts, c, env, nested)
        case Trees.DoExpr(stmts, _)        => sequence(stmts, c, env, nested)
        case Trees.LambdaExpr(ps, body, _) =>
          ps.flatMap(_.default).foreach(go)
          walk(body, c, env ++ ps.map(_.name -> Origin.Local), nested)
        case Trees.MatchExpr(scrutinee, cases, _) =>
          go(scrutinee)
          val of = scrutinee match
            case Trees.Ident(n, _) => env.getOrElse(n, Origin.Local)
            case _                 => Origin.Local
          cases.foreach { k =>
            val inner = env ++ bindings(k.pattern, of)
            k.guard.foreach(walk(_, c, inner, nested))
            walk(k.body, c, inner, nested)
          }
        case Trees.OperationClause(_, _, ps, body, _) => walk(body, c, env ++ ps.flatMap(locals), nested)
        case Trees.ReturnClause(p, body, _)           => walk(body, c, env ++ locals(p), nested)
        case m: Trees.MethodCall                      =>
          s.types.resolution(m).foreach {
            case Resolution.CapMember(_, member) =>
              reached(s.typeOf(m.receiver), member).foreach(
                edge(c, _, m.span, m.receiver :: m.args.getOrElse(Nil), env)
              )
            case _ => ()
          }
          Trees.children(m).foreach(go)
        case b @ Trees.BinOp(op, lhs, rhs, _) =>
          import Trees.BinOperator.*
          val member = op match
            case Eq | Neq          => preludeMember("Eq", "eq")
            case Lt | Le | Gt | Ge => preludeMember("Ord", "compare")
            case _                 => None

          /** `l > r` and `l >= r` compare `r` with `l`. */
          val operands = if op == Gt || op == Ge then List(rhs, lhs) else List(lhs, rhs)
          member.flatMap(reached(s.typeOf(operands.head), _)).foreach(edge(c, _, b.span, operands, env))
          go(lhs)
          go(rhs)
        case _ => Trees.children(t).foreach(go)

    /** The statements of a block in order: a `let` binds for the statements after it, and a
      * nested `fn` is a function of its own that its enclosing function reaches.
      */
    private def sequence(stmts: List[Tree], c: Code, env: Env, nested: Map[String, Node]): Unit =
      val _ = stmts.foldLeft((env, nested)) { case ((e, n), st) =>
        st match
          case Trees.LetExpr(p, init, _) =>
            walk(init, c, e, n)
            (e ++ locals(p), n)
          case Trees.DoBind(p, x, _) =>
            walk(x, c, e, n)
            (e ++ locals(p), n)
          case f: Trees.FnDecl =>
            s.types.resolution(f) match
              case Some(Resolution.NestedFn(id)) =>
                val inner = Code(Node.Fn(id), f.name, f, c.prelude)
                val (e1, n1) = (e - f.name, n + (f.name -> inner.node))
                codes += inner
                edge(c, inner.node, f.span, Nil, e, call = false)

                /** The enclosing function's parameters are plain captures here. */
                val captured = e1.view.mapValues(_ => Origin.Local).toMap
                f.body.foreach(walk(_, inner, captured ++ arguments(inner), n1))
                (e1, n1)
              case _ => (e, n)
          case other =>
            walk(other, c, e, n)
            (e, n)
      }

    private def reach(from: Node): Set[Node] =
      val seen = mutable.Set.empty[Node]
      def visit(n: Node): Unit = edges(n).foreach(e => if seen.add(e.to) then visit(e.to))
      visit(from)
      seen.toSet

    /** Whether `c`'s row has the prelude's `Diverge`. */
    private def diverges(c: Code): Boolean =
      symbols(c.sig).info match
        case Type.ArrowType(_, _, row) => row.effects.exists(e => symbols.prelude.get("Diverge").contains(e.sym))
        case _                         => false

    /** Whether a position of each function of `cycle` decreases along every edge between them: each
      * call passes, in the callee's position, a strict sub-value of the caller's position.
      * ponytail: tries every assignment of positions, at most 4096; a larger cycle counts as not
      * structural.
      */
    private def structural(cycle: List[Code]): Boolean =
      val inCycle = cycle.map(_.node).toSet
      def ok(pos: Map[Node, Int]): Boolean =
        cycle.forall(c =>
          edges(c.node).filter(e => inCycle(e.to)).forall(e => e.descent.get(pos(e.to)).contains(pos(c.node)))
        )
      val choices = cycle.foldLeft(List(Map.empty[Node, Int])) { (acc, c) =>
        if acc.sizeIs > 4096 then Nil else acc.flatMap(m => c.params.indices.map(i => m + (c.node -> i)))
      }
      choices.exists(ok)

    def report: List[Diagnostic] =
      val reaches = codes.map(c => c.node -> reach(c.node)).toMap
      val done = mutable.Set.empty[Node]
      codes.toList.flatMap { c =>
        if done(c.node) || !reaches(c.node)(c.node) then None
        else
          val cycle = codes.filter(o => reaches(c.node)(o.node) && reaches(o.node)(c.node)).toList
          done ++= cycle.map(_.node)
          if cycle.forall(diverges) || structural(cycle) then None
          else
            val inCycle = cycle.map(_.node).toSet
            val calls = edges(c.node).filter(e => inCycle(e.to))
            val names = cycle.map(o => s"`${o.name}`")
            val message =
              if cycle.sizeIs == 1 then s"${names.head} calls itself"
              else s"${names.init.mkString(", ")} and ${names.last} call each other"
            val at = calls.find(_.call).getOrElse(calls.head).span
            Some(Diagnostic(Severity.Error, DiagCode.T027, at, message, Some(Shape(s, c, calls).hint)))
      }

  /** The ways out of `T027` for code `c` (Rule 7.4.h), the one that fits the shape of its recursive
    * `calls` first: `fold` when every call is on a value reached from a parameter through a pattern
    * or a lambda of a member call on one (`ks.map((k) -> f(k))`) and only the call's result uses it;
    * `para` when the body also uses that value; a `match` on the parameter when a call passes a
    * value computed from a parameter or recurses inside a callback of the parameter's derived
    * `fold`; `unfold` when the function builds data; `with Diverge` otherwise.
    */
  final private class Shape(s: TyperState, c: Code, calls: Vector[Edge]):
    private val symbols = s.symbols
    private val body = c.fn.body.toList.flatMap(Trees.subtrees)
    private val params = c.params.toSet

    /** Each binder inside a pattern of a `match` on a parameter, with that `match`. */
    private val direct: Map[String, Trees.MatchExpr] =
      body.collect { case m @ Trees.MatchExpr(Trees.Ident(p, _), _, _) if params(p) => m }
        .flatMap(m =>
          m.cases.flatMap(k =>
            k.pattern match
              case _: Trees.BinderPat => Nil
              case p                  => Trees.subtrees(p).collect { case Trees.BinderPat(n, _) => n -> m }
          )
        ).toMap

    /** Each parameter of a lambda passed to a member of a binder of [[direct]], with that binder. */
    private val through: Map[String, String] =
      body.collect {
        case Trees.MethodCall(Trees.Ident(f, _), _, Some(args), _) if direct.contains(f) =>
          args.collect { case l: Trees.LambdaExpr => l.params.map(_.name -> f) }.flatten
      }.flatten.toMap

    /** [[direct]] and [[through]], each with its `match`. */
    private val fields: Map[String, Trees.MatchExpr] = direct ++ through.view.mapValues(direct).toMap

    /** The field each call recurses on, if every call has one. */
    private val recursedOn: Option[List[String]] =
      /** A field passed back in the position of the parameter its `match` is on. */
      val on = calls.map(_.args.zipWithIndex.collectFirst {
        case (Trees.Ident(n, _), j) if fields.get(n).exists(m => scrutinee(m) == c.params.lift(j)) => n
      })
      Option.when(calls.nonEmpty && on.forall(_.isDefined))(on.flatten.toList)

    private def scrutinee(m: Trees.MatchExpr): Option[String] =
      m.scrutinee match
        case Trees.Ident(p, _) => Some(p)
        case _                 => None

    private val (fold, para, matching, unfold, diverge) =
      ("`fold`", "`para`", "a `match` on the parameter", "`unfold`", "`with Diverge`")

    /** The way out that fits first, then the others. */
    def hint: String =
      val (first, text) = recursedOn match
        case Some(on) =>
          val uses = (n: String) => body.count { case Trees.Ident(`n`, _) => true; case _ => false }
          val asArgs = (n: String) => calls.count(_.args.exists { case Trees.Ident(`n`, _) => true; case _ => false })
          val usesField = on.distinct.exists(n => uses(n) > asArgs(n) || through.get(n).exists(uses(_) > 1))
          (if usesField then para else fold, sketch(fields(on.head), usesField))
        case None if calls.forall(_.args.exists { case Trees.Ident(n, _) => fields.contains(n); case _ => false }) =>
          (
            diverge,
            s"`${c.fn.name}` passes the parts it matched in other positions than the parameters they come from: " +
              "pass each part back in its own parameter's position, or declare `with Diverge`"
          )
        case None if calls.exists(e => computed(e) || unfolded(e)) =>
          (
            matching,
            s"`${c.fn.name}` recurses on a value it does not take from a parameter by a pattern: match on the " +
              "parameter and pass the variable the pattern binds, which makes the recursion structural"
          )
        case None =>
          built match
            case Some(d) =>
              val name = symbols(d).name
              (
                unfold,
                s"`${c.fn.name}` builds a `$name` from a seed: write it with `unfold`, whose step returns one " +
                  s"layer with the next seed in each recursive field: `$name.unfold(seed, (s) -> …)`"
              )
            case None =>
              val where = c.node match
                case Node.Fn(_) => "in its row (and its callers')"
                case _          =>
                  "in the row of a function that does the recursion (a capability member's row is the capability's)"
              (
                diverge,
                s"`${c.fn.name}` does not recurse on a part of a parameter: declare `with Diverge` $where if it " +
                  "may not return, or count down a `Nat` matched by its pattern"
              )
      s"$text. Other ways out: ${List(fold, para, matching, unfold, diverge).filterNot(_ == first).mkString(", ")}"

    /** Whether call `e` passes a member of a parameter or of a value matched from one (`t.rest`, `xs.tail()`). */
    private def computed(e: Edge): Boolean =
      e.args.exists {
        case Trees.MethodCall(Trees.Ident(p, _), _, _, _) => params(p) || fields.contains(p)
        case _                                            => false
      }

    /** Whether call `e` is inside a callback of a parameter's derived `fold` or `para`. */
    private def unfolded(e: Edge): Boolean =
      body.exists {
        case m @ Trees.MethodCall(Trees.Ident(p, _), _, Some(args), _) =>
          params(p) && s.types.resolution(m).exists { case _: Resolution.DerivedFold => true; case _ => false } &&
          args.exists { case l: Trees.LambdaExpr => l.span.contains(e.span.start); case _ => false }
        case _ => false
      }

    /** The recursive data type `c` returns, if it is one. */
    private def built: Option[SymbolId] =
      symbols(c.sig).info match
        case Type.ArrowType(_, Type.TypeRef(d, _), _) if symbols(d).kind == SymKind.Data =>
          Option.when(symbols.variantsOf(d).exists(v => symbols.fieldsOf(v.id).exists(recursive(d, _))))(d)
        case _ => None

    /** Whether field `f` of `d` is one its derived `fold` folds. */
    private def recursive(d: SymbolId, f: fixed.core.Denotation): Boolean =
      Members.foldedSelf(symbols, d, f.info, Type.UnitType) != f.info

    /** `p.fold(…)` or `p.para(…)` for the `match` on parameter `p`: one callback per variant, with
      * the case's binder names where it has them.
      */
    private def sketch(m: Trees.MatchExpr, keep: Boolean): String =
      val Trees.Ident(p, _) = m.scrutinee: @unchecked
      val member = if keep then "para" else "fold"
      val callbacks = s.typeOf(m.scrutinee) match
        case Some(Type.TypeRef(d, _)) if symbols(d).kind == SymKind.Data =>
          symbols.variantsOf(d).toList.map { v =>
            val written = m.cases.collectFirst {
              case Trees.MatchCase(Trees.DataVariantPat(_, v.name, Some(ps), _), _, _, _) => ps
            }
            val fs = symbols.fieldsOf(v.id).toList.zipWithIndex.flatMap { (f, i) =>
              val n = written.flatMap(_.lift(i)).collect { case Trees.BinderPat(b, _) => b }.getOrElse(f.name)
              if keep && recursive(d, f) then List(n, s"${n}Folded") else List(n)
            }
            s"(${fs.mkString(", ")}) -> …"
          }
        case _ => List("…")
      val why =
        if keep then
          "the body also uses the value it recurses on, and `para` passes each recursive field as it is and " +
            "then folded"
        else "every recursive call is on a field of the matched value and only its result is used"
      s"$why: write `${c.fn.name}` with `$member`, each recursive field folded by the callbacks: " +
        s"`$p.$member(${callbacks.mkString(", ")})`"
