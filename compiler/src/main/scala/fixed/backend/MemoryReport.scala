package fixed.backend

import fixed.CompilationUnit
import fixed.ast.Trees
import fixed.util.Span

/** `fixed compile --explain-memory [--format json]` (`spec/memory_management.md` §19): per function,
  * its `fip` contract, each parameter's demand and escape, each allocation's class with its escapes
  * and reasons, fork facts and copy sites; then each `handle`'s kind. Positions are `line:column`,
  * after `path:` in a library file (the prelude's code).
  * Every function except a clone and a `fip` shared entry also gives its baseline size and its
  * clone candidates on both axes, built or not (`spec/compilation_model.md` Rule CM5.8).
  */
object MemoryReport:

  /** One reported item: its fields in order, values already rendered (JSON strings or nested). */
  private type Fields = List[(String, Value)]

  private enum Value:
    case Str(s: String)
    case Num(n: BigDecimal)
    case Bool(b: Boolean)
    case Arr(vs: List[Value])
    case Obj(fs: Fields)

  def text(unit: CompilationUnit, a: Analysed): String = render(model(unit, a), 0)

  def json(unit: CompilationUnit, a: Analysed): String = toJson(model(unit, a))

  private def model(unit: CompilationUnit, a: Analysed): Value =
    val at = (sp: Span) =>
      if sp == Span.NoSpan then Value.Str("-")
      else
        val (l, c) = sp.file.getOrElse(unit.source).lineColumn(sp.start)
        Value.Str(sp.file.fold("")(_.path + ":") + s"$l:$c")
    val fns = a.fns.map { f =>
      val demands = a.demands.params.getOrElse(f.instance, Nil)
      val escapes = a.memory.escapes.getOrElse(f.name, Nil)
      val params = f.params.zipWithIndex.map { (p, i) =>
        val n = demands.lift(i).getOrElse(Need.zero)
        Value.Obj(
          List(
            "name" -> Value.Str(p.name),
            "demand" -> Value.Str(n.root.show),
            "parts" -> Value.Obj(n.parts.toList.sortBy(_._1).map((k, d) => s"${k._1}#${k._2}" -> Value.Str(d.show))),
            "escape" -> Value.Str(escapes.lift(i).fold("none")(escape))
          )
        )
      }
      val allocations = a.memory.allocations.getOrElse(f.name, Nil).map { x =>
        Value.Obj(
          List(
            "name" -> Value.Str(x.name),
            "at" -> at(x.span),
            "class" -> Value.Str(x.cls.show),
            "escapes" -> Value.Arr(x.escapes.toList.map(show).sorted.map(Value.Str(_))),
            "reasons" -> Value.Arr(x.reasons.map(Value.Str(_)))
          )
        )
      }
      val forks = a.memory.forks.getOrElse(f.name, Nil).map { k =>
        Value.Obj(
          List(
            "loop" -> Value.Str(k.loop),
            "at" -> at(k.span),
            "fork" -> Value.Bool(k.admissible),
            "reason" -> Value.Str(k.reason)
          )
        )
      }
      val copies = Core.lets(f.body).collect {
        case (x, Rhs.Call(g, _, false)) if g.contains(".copy[") =>
          Value.Obj(List("copy" -> Value.Str(g), "into" -> Value.Str(x.name)))
      }
      Value.Obj(
        List(
          "function" -> Value.Str(f.name),
          "fip" ->
            Value.Str(a.fips.get(f.name).fold("none")(l => if l == Trees.Fip.Strict then "fip(strict)" else "fip")),
          "params" -> Value.Arr(params),
          "allocations" -> Value.Arr(allocations),
          "forks" -> Value.Arr(forks),
          "copies" -> Value.Arr(copies)
        ) ++
          (if f.name == f.instance && !a.typeClones.contains(f.name) then
             specialisation(f, a.candidates.getOrElse(f.name, Nil))
           else Nil)
      )
    }
    val handlers = unit.tree.toList.flatMap(Trees.subtrees).collect { case h: Trees.HandleExpr =>
      unit.quantities.flatMap(_.handler(h)).map(k => Value.Obj(List("at" -> at(h.span), "kind" -> Value.Str(kind(k)))))
    }.flatten
    Value.Obj(List("functions" -> Value.Arr(fns), "handlers" -> Value.Arr(handlers)))

  /** Rule CM5.8: the baseline size, the clones built and the candidates not built. A version has
    * as many `let`s as the baseline it was walked from, so `f`'s own body gives the size. A body
    * with no `let` (its only site a witness `drop`) has its score divided by 1 instead of 0.
    */
  private def specialisation(f: Fn, candidates: List[Candidate]): Fields =
    val size = Core.lets(f.body).size
    def item(c: Candidate, last: (String, Value)) =
      val score = BigDecimal(c.removed * c.weight) / size.max(1)
      Value.Obj(
        List(
          "key" -> Value.Str(c.key),
          "removed" -> Value.Num(c.removed),
          "weight" -> Value.Num(c.weight),
          "score" -> Value.Num(score.setScale(4, BigDecimal.RoundingMode.HALF_UP)),
          last
        )
      )
    val (built, rejected) = candidates.partition(_.reason.isEmpty)
    List(
      "baseline_size" -> Value.Num(size),
      "clones" -> Value.Arr(built.map(c => item(c, "charge" -> Value.Num(if c.charged then size else 0)))),
      "candidates" -> Value.Arr(rejected.map(c => item(c, "reason" -> Value.Str(c.reason))))
    )

  private def kind(k: fixed.qtt.HandlerKind): String =
    k match
      case fixed.qtt.HandlerKind.TailResumptive    => "tail-resumptive"
      case fixed.qtt.HandlerKind.Abortive          => "abortive"
      case fixed.qtt.HandlerKind.CapturingLocal    => "capturing, local"
      case fixed.qtt.HandlerKind.CapturingEscaping => "capturing, escaping"

  private def escape(e: ParamEscape): String =
    e match
      case ParamEscape.None       => "none"
      case ParamEscape.IntoResult => "into-result"
      case ParamEscape.Unbounded  => "unbounded"

  private def show(e: Escape): String =
    e match
      case Escape.Result        => "result"
      case Escape.Field(b)      => s"field($b)"
      case Escape.Closure(c)    => s"closure($c)"
      case Escape.Carried(l)    => s"carried($l)"
      case Escape.LaneShared(k) => s"lane-shared($k)"
      case Escape.Unbounded(_)  => "unbounded"

  /** Indented text: an object's fields one per line, an array's items as `-` entries. */
  private def render(v: Value, depth: Int): String =
    val pad = "  " * depth
    v match
      case Value.Obj(fs) =>
        fs.map {
          case (k, Value.Arr(Nil))                         => s"$pad$k: -"
          case (k, Value.Obj(Nil))                         => s"$pad$k: -"
          case (k, nested @ (_: Value.Arr | _: Value.Obj)) => s"$pad$k:\n${render(nested, depth + 1)}"
          case (k, leaf)                                   => s"$pad$k: ${render(leaf, 0)}"
        }.mkString("\n")
      case Value.Arr(vs) => vs.map(x => s"$pad-\n${render(x, depth + 1)}").mkString("\n")
      case Value.Str(s)  => s
      case Value.Num(n)  => n.bigDecimal.stripTrailingZeros.toPlainString
      case Value.Bool(b) => if b then "yes" else "no"

  private def toJson(v: Value): String =
    v match
      case Value.Obj(fs) => fs.map((k, x) => s"${quote(k)}:${toJson(x)}").mkString("{", ",", "}")
      case Value.Arr(vs) => vs.map(toJson).mkString("[", ",", "]")
      case Value.Str(s)  => quote(s)
      case Value.Num(n)  => n.bigDecimal.stripTrailingZeros.toPlainString
      case Value.Bool(b) => b.toString

  private def quote(s: String): String =
    val escaped = s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }
    s"\"$escaped\""
