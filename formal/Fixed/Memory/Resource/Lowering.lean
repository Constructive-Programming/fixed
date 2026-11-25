import Fixed.Memory.Resource.CoreErasure
import Fixed.Checker

namespace Fixed.Memory.Resource

/-- Core terms with quantities on lambda binders. Erasure removes exactly these
    annotations; free-variable quantities live in `QCtx`. -/
inductive QTerm where
  | var : Nat → QTerm
  | unit
  | bool : Bool → QTerm
  | lam : Fixed.Quantity → Fixed.Ty → QTerm → QTerm
  | app : QTerm → QTerm → QTerm
  | ite : QTerm → QTerm → QTerm → QTerm
  | absurd : Fixed.Ty → QTerm → QTerm
  deriving DecidableEq, Repr

abbrev QCtx := Nat → Option (Fixed.Ty × Fixed.Quantity)

def closedQ : QCtx := fun _ => none

def eraseQCtx (Γ : QCtx) : Fixed.Ctx :=
  fun n => (Γ n).map Prod.fst

def eraseQ : QTerm → Fixed.Term
  | .var n => .var n
  | .unit => .unit
  | .bool b => .bool b
  | .lam _ a body => .lam a (eraseQ body)
  | .app f x => .app (eraseQ f) (eraseQ x)
  | .ite c t f => .ite (eraseQ c) (eraseQ t) (eraseQ f)
  | .absurd a body => .absurd a (eraseQ body)

def lowerTerm (e : QTerm) : RuntimeTerm := ofCore (eraseQ e)

namespace Quantity

def ofNat : Nat → Fixed.Quantity
  | 0 => .zero
  | 1 => .one
  | _ => .many

def var (n m : Nat) : Fixed.Quantity := if n = m then .one else .zero

instance decLe (a b : Fixed.Quantity) : Decidable (Fixed.Quantity.Le a b) := by
  unfold Fixed.Quantity.Le
  infer_instance

theorem zero_le (q : Fixed.Quantity) : Fixed.Quantity.Le .zero q := by
  cases q <;> rfl

end Quantity

/-- `value` is the quantity of an expression's result. `call` is used for an
    expression applied once, while retaining the result quantity of that call. -/
inductive UseContext where
  | value | call

/-- Q5.7 capture usage. A returned closure scales captured uses by its result
    quantity; an immediately called closure runs once. Unknown arguments are
    conservatively treated as reusable by their callee. -/
def usageIn (ctx : UseContext) (result : Fixed.Quantity) (e : QTerm) (n : Nat) : Fixed.Quantity :=
  match ctx, e with
  | _, .var m => Quantity.var n m
  | _, .unit | _, .bool _ => .zero
  | .value, .lam _ _ body => Fixed.Quantity.mul result (usageIn .value result body (n + 1))
  | .call, .lam _ _ body => usageIn .value result body (n + 1)
  | _, .app f x => Fixed.Quantity.add (usageIn .call result f n) (usageIn .value .many x n)
  | .value, .ite c t f => Fixed.Quantity.add (usageIn .value .one c n)
      (Fixed.Quantity.join (usageIn .value result t n) (usageIn .value result f n))
  | .call, .ite c t f => Fixed.Quantity.add (usageIn .value .one c n)
      (Fixed.Quantity.join (usageIn .call result t n) (usageIn .call result f n))
  | _, .absurd _ body => usageIn .value .one body n

/-- Quantity usage at a free de Bruijn index. A top-level result is reusable. -/
def usage (e : QTerm) (n : Nat) : Fixed.Quantity := usageIn .value .many e n

def LocalUsageOK (e : QTerm) : Prop :=
  match e with
  | .var _ | .unit | .bool _ => True
  | .lam q _ body => Fixed.Quantity.Le (usage body 0) q ∧ LocalUsageOK body
  | .app f x => LocalUsageOK f ∧ LocalUsageOK x
  | .ite c t f => LocalUsageOK c ∧ LocalUsageOK t ∧ LocalUsageOK f
  | .absurd _ body => LocalUsageOK body

def quantityAt (Γ : QCtx) (n : Nat) : Fixed.Quantity :=
  match Γ n with
  | some (_, q) => q
  | none => .zero

structure UsageBounded (Γ : QCtx) (e : QTerm) : Prop where
  binders : LocalUsageOK e
  context : ∀ n, Fixed.Quantity.Le (usage e n) (quantityAt Γ n)

def freeVars : QTerm → List Nat
  | .var n => [n]
  | .unit | .bool _ => []
  | .lam _ _ body => (freeVars body).filterMap fun m => if m = 0 then none else some (m - 1)
  | .app f x => freeVars f ++ freeVars x
  | .ite c t f => freeVars c ++ freeVars t ++ freeVars f
  | .absurd _ body => freeVars body

theorem usageIn_eq_zero_of_not_free (hn : n ∉ freeVars e) : usageIn ctx result e n = .zero := by
  induction e generalizing ctx result n with
  | var m =>
    by_cases h : n = m
    · subst m; simp [freeVars] at hn
    · simp [usageIn, Quantity.var, h]
  | unit | bool _ => cases ctx <;> rfl
  | lam q a body ih =>
    have hm : n + 1 ∉ freeVars body := by
      intro hm
      apply hn
      simp only [freeVars, List.mem_filterMap]
      exact ⟨n + 1, hm, by simp⟩
    cases ctx <;> simp [usageIn, ih hm, Fixed.Quantity.mul_zero]
  | app f x ihf ihx =>
    simp only [freeVars, List.mem_append] at hn
    simp [usageIn, Fixed.Quantity.add, ihf (by simpa using fun h => hn (Or.inl h)),
      ihx (by simpa using fun h => hn (Or.inr h))]
  | ite c t f ihc iht ihf =>
    simp only [freeVars, List.mem_append] at hn
    have hc : ∀ ctx result, usageIn ctx result c n = .zero :=
      fun _ _ => ihc (by simpa using fun h => hn (Or.inl (Or.inl h)))
    have ht : ∀ ctx result, usageIn ctx result t n = .zero :=
      fun _ _ => iht (by simpa using fun h => hn (Or.inl (Or.inr h)))
    have hf : ∀ ctx result, usageIn ctx result f n = .zero :=
      fun _ _ => ihf (by simpa using fun h => hn (Or.inr h))
    cases ctx <;> simp [usageIn, hc, ht, hf, Fixed.Quantity.join, Fixed.Quantity.add]
  | absurd a body ih => simp [usageIn, ih (by simpa [freeVars] using hn)]

theorem usage_eq_zero_of_not_free (hn : n ∉ freeVars e) : usage e n = .zero :=
  usageIn_eq_zero_of_not_free hn

def localUsageOK : QTerm → Bool
  | .var _ | .unit | .bool _ => true
  | .lam q _ body => decide (Fixed.Quantity.Le (usage body 0) q) && localUsageOK body
  | .app f x => localUsageOK f && localUsageOK x
  | .ite c t f => localUsageOK c && localUsageOK t && localUsageOK f
  | .absurd _ body => localUsageOK body

theorem localUsageOK_sound (h : localUsageOK e = true) : LocalUsageOK e := by
  induction e with
  | var n | unit | bool _ => trivial
  | lam q a body ih =>
    simp only [localUsageOK, Bool.and_eq_true, decide_eq_true_eq] at h
    exact ⟨h.1, ih h.2⟩
  | app f x ihf ihx =>
    simp only [localUsageOK, Bool.and_eq_true] at h
    exact ⟨ihf h.1, ihx h.2⟩
  | ite c t f ihc iht ihf =>
    simp only [localUsageOK, Bool.and_eq_true] at h
    exact ⟨ihc h.1.1, iht h.1.2, ihf h.2⟩
  | absurd a body ih => exact ih h

def checkUsage (Γ : QCtx) (e : QTerm) : Bool :=
  localUsageOK e && (freeVars e).all fun n =>
    match Γ n with
    | some (_, q) => decide (Fixed.Quantity.Le (usage e n) q)
    | none => false

theorem checkUsage_sound (h : checkUsage Γ e = true) : UsageBounded Γ e := by
  simp only [checkUsage, Bool.and_eq_true] at h
  refine ⟨localUsageOK_sound h.1, ?_⟩
  intro n
  by_cases hn : n ∈ freeVars e
  · have hall := List.all_eq_true.mp h.2 n hn
    cases hΓ : Γ n with
    | none => simp [hΓ] at hall
    | some binding =>
      simp only [hΓ, decide_eq_true_eq] at hall
      simpa [quantityAt, hΓ] using hall
  · rw [usage_eq_zero_of_not_free hn]
    exact Quantity.zero_le _

/-- Type-check after erasing quantities and require both local binder bounds and
    external context bounds. Successful results execute in the resource machine. -/
def lower (Γ : QCtx) (e : QTerm) : Option (Fixed.Ty × RuntimeTerm) :=
  (Fixed.infer (eraseQCtx Γ) (eraseQ e)).bind fun a =>
    if checkUsage Γ e then some (a, lowerTerm e) else none

theorem lower_sound (h : lower Γ e = some (a, r)) :
    Fixed.HasType (eraseQCtx Γ) (eraseQ e) a ∧ UsageBounded Γ e ∧ r = lowerTerm e := by
  simp only [lower] at h
  cases hi : Fixed.infer (eraseQCtx Γ) (eraseQ e) with
  | none => simp [hi] at h
  | some a' =>
    simp only [hi, Option.bind] at h
    by_cases hu : checkUsage Γ e = true
    · simp only [hu, if_true, Option.some.injEq, Prod.mk.injEq] at h
      cases h.1
      cases h.2
      exact ⟨Fixed.infer_sound hi, checkUsage_sound hu, rfl⟩
    · simp [hu] at h

theorem lower_runSteps_config (h : lower closedQ e = some (a, r)) (fuel : Nat) :
    r = lowerTerm e ∧ ConfigValid (runSteps initial r fuel).1 (runSteps initial r fuel).2 := by
  obtain ⟨_, _, rfl⟩ := lower_sound h
  exact ⟨rfl, ofCore_runSteps_config (eraseQ e) fuel⟩

theorem lower_runSteps_erasure (h : lower closedQ e = some (a, r))
    (hc : Fixed.ClosedAt 0 (eraseQ e)) (fuel : Nat) :
    r = lowerTerm e ∧ ∃ c', ErasureValid (runSteps initial r fuel).2 c' ∧
      Fixed.Steps (eraseQ e) c' := by
  obtain ⟨_, _, rfl⟩ := lower_sound h
  exact ⟨rfl, ofCore_runSteps_erasure hc fuel⟩

end Fixed.Memory.Resource
