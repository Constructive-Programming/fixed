import Fixed.Core

namespace Fixed

/-- Annotation-directed checker for the raw core. No source-language inference is assumed. -/
def infer (Γ : Ctx) : Term → Option Ty
  | .var n => Γ n
  | .unit => some .unit
  | .bool _ => some .bool
  | .lam a e => (infer (extend Γ a) e).map (Ty.arr a)
  | .app f x => match infer Γ f, infer Γ x with
    | some (.arr a b), some a' => if a = a' then some b else none
    | _, _ => none
  | .ite c t f => match infer Γ c, infer Γ t, infer Γ f with
    | some .bool, some a, some b => if a = b then some a else none
    | _, _, _ => none
  | .absurd a e => match infer Γ e with
    | some .empty => some a
    | _ => none

theorem infer_complete (h : HasType Γ e a) : infer Γ e = some a := by
  induction h with
  | var h => exact h
  | unit => rfl
  | bool => rfl
  | lam _ ih => simp [infer, ih]
  | app _ _ ihf ihx => simp [infer, ihf, ihx]
  | ite _ _ _ ihc iht ihf => simp [infer, ihc, iht, ihf]
  | absurd _ ih => simp [infer, ih]

theorem infer_sound (h : infer Γ e = some a) : HasType Γ e a := by
  induction e generalizing Γ a with
  | var n => exact .var h
  | unit => cases h; exact .unit
  | bool b => cases h; exact .bool
  | lam t e ih =>
    cases hb : infer (extend Γ t) e with
    | none => simp [infer, hb] at h
    | some b =>
      simp [infer, hb, Option.map] at h
      subst a
      exact .lam (ih hb)
  | app f x ihf ihx =>
    simp only [infer] at h
    split at h
    next t b t' hf hx =>
      split at h
      next he =>
        cases h
        subst t'
        exact .app (ihf hf) (ihx hx)
      next => contradiction
    next => contradiction
  | ite c t f ihc iht ihf =>
    simp only [infer] at h
    split at h
    next ta tb hc ht hf =>
      split at h
      next he =>
        cases h
        subst tb
        exact .ite (ihc hc) (iht ht) (ihf hf)
      next => contradiction
    next => contradiction
  | absurd t e ih =>
    simp only [infer] at h
    split at h
    next he => cases h; exact .absurd (ih he)
    next => contradiction

theorem infer_iff : infer Γ e = some a ↔ HasType Γ e a :=
  ⟨infer_sound, infer_complete⟩

theorem accepted_safe (h : infer closed e = some a) (s : Steps e e') :
    Value e' ∨ ∃ e'', Step e' e'' := soundness (infer_sound h) s

theorem rejects_empty : infer closed e ≠ some .empty :=
  fun h => consistency (infer_sound h)

end Fixed
