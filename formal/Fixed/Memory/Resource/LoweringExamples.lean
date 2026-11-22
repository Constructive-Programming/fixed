import Fixed.Memory.Resource.Lowering

namespace Fixed.Memory.Resource.Examples

def identityQ : QTerm := .lam .one .bool (.var 0)

def loweredIdentity : RuntimeTerm := lowerTerm (.app identityQ (.bool true))

example : lower closedQ (.app identityQ (.bool true)) = some (.bool, loweredIdentity) := by decide

example : (runSteps initial loweredIdentity 3).2 = .value (.bool true) := by decide

def reuse (q : Fixed.Quantity) : QTerm :=
  .lam q (.arr .bool .bool) (.app (.var 0) (.app (.var 0) (.bool true)))

-- Two sequential uses exceed a `one` binder but are accepted by `many`.
example : lower closedQ (reuse .one) = none := by decide
example : (lower closedQ (reuse .many)).map Prod.fst = some (.arr (.arr .bool .bool) .bool) := by decide

-- Exclusive branches join rather than add their usages.
example : (lower closedQ
    (.lam .one .bool (.ite (.bool true) (.var 0) (.var 0)))).map Prod.fst =
    some (.arr .bool .bool) := by decide

def boolOne : QCtx
  | 0 => some (.bool, .one)
  | _ => none

-- Free variables use the quantity stored in the external context.
example : lower boolOne (.var 0) = some (.bool, lowerTerm (.var 0)) := by decide
example : lower closedQ (.var 0) = none := by decide

def boolMany : QCtx
  | 0 => some (.bool, .many)
  | _ => none

def returnedCapture : QTerm := .lam .zero .unit (.var 1)

-- Q5.7: a reusable returned closure scales its capture to `many`.
example : lower boolOne returnedCapture = none := by decide
example : (lower boolMany returnedCapture).map Prod.fst = some (.arr .unit .bool) := by decide

-- A closure invoked once does not multiply its captured binding.
example : lower boolOne (.app returnedCapture .unit) =
    some (.bool, lowerTerm (.app returnedCapture .unit)) := by decide

end Fixed.Memory.Resource.Examples
