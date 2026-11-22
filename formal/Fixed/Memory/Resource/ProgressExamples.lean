import Fixed.Memory.Resource.Progress
import Fixed.Memory.Resource.LoweringExamples

namespace Fixed.Memory.Resource.Examples

example : runChecked initial loweredIdentity 0 = .timeout initial loweredIdentity := by rfl

example : OutcomeGood (runChecked initial loweredIdentity 3) := by trivial

def identityTyped : TypedConfig initial loweredIdentity (eraseQ (.app identityQ (.bool true))) .bool := by
  refine ⟨initial_config _, ofCore_erasureValid ?_, Fixed.infer_sound (by decide)⟩
  change Fixed.ClosedAt 1 (Fixed.Term.var 0) ∧ Fixed.ClosedAt 0 (Fixed.Term.bool true)
  exact ⟨Nat.zero_lt_succ 0, trivial⟩

example : OutcomeGood (runChecked initial loweredIdentity 0) := runChecked_good identityTyped 0
example : OutcomeGood (runChecked initial loweredIdentity 3) := runChecked_good identityTyped 3

end Fixed.Memory.Resource.Examples
