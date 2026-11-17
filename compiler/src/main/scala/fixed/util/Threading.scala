package fixed.util

/** Map over a list while threading a state through each step, in order. */
def mapAccum[S, A, B](state: S, as: List[A])(f: (S, A) => (S, B)): (S, List[B]) =
  val (out, acc) = as.foldLeft((state, List.empty[B])) { case ((s0, acc0), a) =>
    val (s1, b) = f(s0, a)
    (s1, b :: acc0)
  }
  (out, acc.reverse)
