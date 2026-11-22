import Fixed.Memory.Resource.Lowering

/- Generated-fragment contract: `FormalExportSuite` checks these definitions
   against the actual Scala compiler's typed, quantity-annotated AST export. -/
namespace Fixed.Memory.Resource.Integration

def compilerIdentity : QTerm :=
  (.app (.lam .one .bool (.var 0)) (.bool true))

def compilerCapture : QTerm :=
  (.app (.app (.lam .many .bool (.lam .zero .unit (.var 1))) (.bool false)) .unit)

def compilerReuse : QTerm :=
  (.app (.lam .many (.arr .bool .bool) (.app (.var 0) (.app (.var 0) (.bool true)))) (.lam .one .bool (.var 0)))

def compilerIf : QTerm :=
  (.ite (.bool true) (.app (.lam .one .bool (.var 0)) (.bool false)) (.bool true))

example : lower closedQ compilerIdentity = some (.bool, lowerTerm compilerIdentity) := by decide
example : (runSteps initial (lowerTerm compilerIdentity) 3).2 = .value (.bool true) := by decide

example : lower closedQ compilerCapture = some (.bool, lowerTerm compilerCapture) := by decide
example : (runSteps initial (lowerTerm compilerCapture) 6).2 = .value (.bool false) := by decide

example : (lower closedQ compilerReuse).map Prod.fst = some .bool := by decide
example : (runSteps initial (lowerTerm compilerReuse) 6).2 = .value (.bool true) := by decide

example : lower closedQ compilerIf = some (.bool, lowerTerm compilerIf) := by decide
example : (runSteps initial (lowerTerm compilerIf) 5).2 = .value (.bool false) := by decide

end Fixed.Memory.Resource.Integration
