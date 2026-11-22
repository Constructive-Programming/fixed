import Fixed.Memory.Resource.Erasure

namespace Fixed.Memory.Resource.Examples

def unusedClosureArg : QTerm :=
  .app (.lam .zero (.arr .bool .bool) (.bool true)) (.lam .one .bool (.var 0))

-- The reference interpreter allocates both the function and the unused argument.
example : (runSteps initial (lowerTerm unusedClosureArg) 2).1.rc.next = 2 := by decide

-- Q6.1 erasure removes the pure argument call before execution.
example : eraseZero unusedClosureArg = .bool true := by decide
example : eraseLower unusedClosureArg = some (.bool, lowerTerm (eraseZero unusedClosureArg)) := by decide
example : (runSteps initial (lowerTerm (eraseZero unusedClosureArg)) 1).1.rc.next = 0 := by decide
example : (runSteps initial (lowerTerm (eraseZero unusedClosureArg)) 1).2 = .value (.bool true) := by decide

example : QZeroSteps unusedClosureArg (.bool true) :=
  .next (.beta (by decide) (closedQAt_sound rfl)) .refl

def openZeroRedex : QTerm :=
  .lam .one .bool (.app (.lam .zero .unit (.var 1)) .unit)

-- A redex whose body captures an outer variable is not erased or index-shifted.
example : eraseZero openZeroRedex = openZeroRedex := by decide

-- Indirect application is outside the checked direct-call erasure fragment.
example : eraseLower
    (.app (.ite (.bool true) (.lam .one .bool (.var 0)) (.lam .one .bool (.var 0))) (.bool true)) =
    none := by decide

end Fixed.Memory.Resource.Examples
