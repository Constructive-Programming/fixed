package fixed.backend

import fixed.CompilationUnit
import fixed.util.Diagnostic

/** A call site's request for a clone (Rule CM5.4): the function holding the site, the baseline
  * version asked for, and the key, the text of the known type arguments.
  */
final case class Request(owner: String, of: String, key: String)

/** The type axis of the specialiser (`spec/compilation_model.md` Rules CM5.1, CM5.3 to CM5.5;
  * plan §4, 4.1.M8). A clone is the function lowered again at its key's types; a call site whose
  * type arguments are all known requests it and calls it if chosen ([[Lower.specialised]]).
  * Lowering is one pass and the choice needs every request first, so each round of Rule CM5.4
  * lowers the whole program with the clones chosen so far: round 0 with none, so every request
  * comes from a baseline version. Each round ranks, per function in name order, the keys requested
  * and not yet chosen ([[Versions.rank]]); `weight` sums `8^d` over the requesting sites. The rounds
  * stop after one that chooses nothing. Clones requested by a `fip fn` body, by the clones it
  * forces and by their closures are mandatory (Rule CM5.5): chosen whatever the budget, uncharged.
  * A closure that a clone builds is lowered with it, at its types, and is not charged.
  */
object Specialise:

  /** The last round's lowering, its functions, and per function its candidates and charges. */
  final case class Result(
      lower: Lower,
      fns: List[Fn],
      candidates: Map[String, List[Candidate]],
      spent: Map[String, Int]
  )

  def apply(unit: CompilationUnit, budget: Int): Either[List[Diagnostic], Result] =
    round(unit, budget, Set.empty, Set.empty)

  /** One round, with the keys `chosen` so far, among them the mandatory ones `forced`. */
  private def round(
      unit: CompilationUnit,
      budget: Int,
      chosen: Set[(String, String)],
      forced: Set[(String, String)]
  ): Either[List[Diagnostic], Result] =
    val lower = Lower(unit, chosen)
    lower.program.flatMap { fns =>
      val byName = fns.map(f => f.name -> f).toMap
      val depth = Versions.depths(fns)
      val creator = fns.flatMap(f => built(f).map(_ -> f.name)).toMap
      def mandatory(f: String): Boolean =
        lower.fips.contains(f) || lower.typeClones.get(f).exists(forced) || creator.get(f).exists(mandatory)

      /** The function whose clone `f`'s code is part of, if any. */
      def cloneOf(f: String): Option[String] =
        lower.typeClones.get(f).map(_._1).orElse(creator.get(f).flatMap(cloneOf))
      val requests = lower.requests.toList

      /** A mandatory clone's request for a clone of its own function is not mandatory, so that
        * polymorphic recursion does not force clones without end. ponytail: mutual polymorphic
        * recursion below a `fip fn` still does; bound the chain's depth if a program needs it.
        */
      val force = forced ++ requests.collect {
        case r if mandatory(r.owner) && !cloneOf(r.owner).contains(r.of) => (r.of, r.key)
      }
      val weights =
        requests.groupMapReduce(r => (r.of, r.key))(r => Versions.weight(depth.getOrElse(r.owner, 0)))(_ + _)
      val families = weights.keys.map(_._1).toList.distinct.sorted
      val size = families.map(f => f -> Core.lets(byName(f).body).size).toMap
      val removals = families.map(f => f -> removed(byName(f), byName)).toMap
      val spent = families.map(f => f -> chosen.count(k => k._1 == f && !force(k)) * size(f)).toMap
      val decided = families.map { f =>
        val open = weights.keys.filter(k => k._1 == f && !chosen(k) && !force(k)).toList
        f -> Versions.rank(open, _ => removals(f), weights, _._2, size(f), spent(f), budget)
      }
      val next = decided.flatMap(_._2._1).toSet ++ (force -- chosen)
      if next.nonEmpty then round(unit, budget, chosen ++ next, force)
      else
        val candidates = decided.map { (f, choice) =>
          val keys = weights.keys.filter(_._1 == f).toList.sortBy(_._2)
          f -> keys.map(k => Candidate(k._2, removals(f), weights(k), choice._2.getOrElse(k, ""), charged = !force(k)))
        }
        Right(Result(lower, fns, candidates.toMap, spent))
    }

  /** The closures and `handle` subjects whose code `f` builds. */
  private def built(f: Fn): List[String] =
    Core.lets(f.body).collect { case (_, Rhs.Closure(c, _, _)) => c; case (_, Rhs.Catch(c, _)) => c }

  /** Rule CM5.4's `removed` for a key of `f`, which fixes every type argument: in `f`'s baseline
    * version and the closures it builds, the calls through a table parameter, the witness calls
    * (slot `dup`s and `drop`s, as `FipCheck` counts them) and the calls that pass a witness or
    * table parameter on, which under the key request a clone of their callee.
    */
  private def removed(f: Fn, byName: Map[String, Fn]): Int =
    val hidden = (a: Atom) =>
      a match
        case Atom.Var(_, CType.Witness | CType.Table) => true
        case _: Atom.DynTable                         => true
        case _                                        => false
    val sites = Core.lets(f.body).count {
      case (_, Rhs.CallTable(t, _, _, _)) => hidden(t)
      case (_, Rhs.Call(_, as, false))    => as.exists(hidden)
      case _                              => false
    }
    sites + Core.witnessOps(Rc(f).body).size + built(f).flatMap(byName.get).map(removed(_, byName)).sum
