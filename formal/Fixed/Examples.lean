import Fixed.Checker
import Fixed.Quantity

namespace Fixed

def identity : Term := .lam .bool (.var 0)

example : infer closed identity = some (.arr .bool .bool) := rfl
example : infer closed (.app identity (.bool true)) = some .bool := rfl
example : Step (.app identity (.bool true)) (.bool true) := .beta .bool

-- Ill-typed application, inconsistent branches, and unbound variables are rejected.
example : infer closed (.app (.bool true) .unit) = none := rfl
example : infer closed (.ite (.bool true) .unit (.bool false)) = none := rfl
example : infer closed (.var 0) = none := rfl

-- Empty elimination is valid under an empty assumption, not from the closed context.
example : infer closed (.lam .empty (.absurd .bool (.var 0))) =
    some (.arr .empty .bool) := rfl
example : infer closed (.absurd .bool .unit) = none := rfl

-- Substitution under two binders must shift a free variable rather than capture it.
example : subst (single (.var 0)) (.lam .unit (.lam .bool (.var 2))) =
    .lam .unit (.lam .bool (.var 2)) := rfl

-- Reusable functions are allowed: Fixed's core does not assume BendTT affinity.
example : infer closed
    (.lam (.arr .bool .bool) (.app (.var 0) (.app (.var 0) (.bool true)))) =
    some (.arr (.arr .bool .bool) .bool) := rfl

example : Quantity.add .one .one = .many := rfl
example : Quantity.join .one .one = .one := rfl

end Fixed
