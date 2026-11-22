import Fixed.Memory.Resource.Lowering

namespace Fixed.Memory.Resource

/-- Every application in the supported erasure fragment reveals its function
    statically. Indirect calls need quantity-polymorphic function types and are
    rejected rather than guessed at. -/
def DirectCalls : QTerm → Prop
  | .var _ | .unit | .bool _ => True
  | .lam _ _ body => DirectCalls body
  | .app (.lam _ _ body) x => DirectCalls body ∧ DirectCalls x
  | .app _ _ => False
  | .ite c t f => DirectCalls c ∧ DirectCalls t ∧ DirectCalls f
  | .absurd _ body => DirectCalls body

def directCalls : QTerm → Bool
  | .var _ | .unit | .bool _ => true
  | .lam _ _ body => directCalls body
  | .app (.lam _ _ body) x => directCalls body && directCalls x
  | .app _ _ => false
  | .ite c t f => directCalls c && directCalls t && directCalls f
  | .absurd _ body => directCalls body

theorem directCalls_sound (h : directCalls e = true) : DirectCalls e := by
  induction e with
  | var n | unit | bool _ => trivial
  | lam q a body ih => exact ih h
  | app f x ihf ihx =>
    cases f with
    | lam q a body =>
      simp only [directCalls, Bool.and_eq_true] at h
      exact ⟨ihf h.1, ihx h.2⟩
    | var n | unit | bool _ | app _ _ | ite _ _ _ | absurd _ _ => contradiction
  | ite c t f ihc iht ihf =>
    simp only [directCalls, Bool.and_eq_true] at h
    exact ⟨ihc h.1.1, iht h.1.2, ihf h.2⟩
  | absurd a body ih => exact ih h

/-- Pure quantity-zero beta reduction. The usage proof is the Q6.1 evidence
    that the argument cannot be observed by the body, including through a
    reusable returned closure. -/
inductive QZeroStep : QTerm → QTerm → Prop where
  | beta (hz : usage body 0 = .zero) (hc : Fixed.ClosedAt 0 (eraseQ body)) :
      QZeroStep (.app (.lam .zero a body) arg) body
  | lam : QZeroStep body body' → QZeroStep (.lam q a body) (.lam q a body')
  | appLeft : QZeroStep f f' → QZeroStep (.app f x) (.app f' x)
  | appRight : QZeroStep x x' → QZeroStep (.app f x) (.app f x')
  | iteCond : QZeroStep c c' → QZeroStep (.ite c t f) (.ite c' t f)
  | iteThen : QZeroStep t t' → QZeroStep (.ite c t f) (.ite c t' f)
  | iteElse : QZeroStep f f' → QZeroStep (.ite c t f) (.ite c t f')
  | absurd : QZeroStep body body' → QZeroStep (.absurd a body) (.absurd a body')

inductive QZeroSteps : QTerm → QTerm → Prop where
  | refl : QZeroSteps e e
  | next : QZeroStep e e' → QZeroSteps e' e'' → QZeroSteps e e''

namespace QZeroSteps

theorem trans (h₁ : QZeroSteps a b) (h₂ : QZeroSteps b c) : QZeroSteps a c := by
  induction h₁ with
  | refl => exact h₂
  | next step _ ih => exact .next step (ih h₂)

theorem lam (h : QZeroSteps body body') : QZeroSteps (.lam q a body) (.lam q a body') := by
  induction h with
  | refl => exact .refl
  | next step _ ih => exact .next step.lam ih

theorem appLeft (h : QZeroSteps f f') : QZeroSteps (.app f x) (.app f' x) := by
  induction h with
  | refl => exact .refl
  | next step _ ih => exact .next step.appLeft ih

theorem appRight (h : QZeroSteps x x') : QZeroSteps (.app f x) (.app f x') := by
  induction h with
  | refl => exact .refl
  | next step _ ih => exact .next step.appRight ih

theorem app (hf : QZeroSteps f f') (hx : QZeroSteps x x') : QZeroSteps (.app f x) (.app f' x') :=
  hf.appLeft.trans hx.appRight

theorem ite (hc : QZeroSteps c c') (ht : QZeroSteps t t') (hf : QZeroSteps f f') :
    QZeroSteps (.ite c t f) (.ite c' t' f') := by
  induction hc with
  | refl =>
    induction ht with
    | refl =>
      induction hf with
      | refl => exact .refl
      | next step _ ih => exact .next step.iteElse ih
    | next step _ ih => exact .next step.iteThen ih
  | next step _ ih => exact .next step.iteCond ih

theorem absurd (h : QZeroSteps body body') : QZeroSteps (.absurd a body) (.absurd a body') := by
  induction h with
  | refl => exact .refl
  | next step _ ih => exact .next step.absurd ih

end QZeroSteps

def closedQAt (n : Nat) : QTerm → Bool
  | .var m => decide (m < n)
  | .unit | .bool _ => true
  | .lam _ _ body => closedQAt (n + 1) body
  | .app f x => closedQAt n f && closedQAt n x
  | .ite c t f => closedQAt n c && closedQAt n t && closedQAt n f
  | .absurd _ body => closedQAt n body

theorem closedQAt_sound (h : closedQAt n e = true) : Fixed.ClosedAt n (eraseQ e) := by
  induction e generalizing n with
  | var m => simpa [eraseQ, Fixed.ClosedAt] using (of_decide_eq_true h : m < n)
  | unit | bool _ => trivial
  | lam q a body ih => exact ih h
  | app f x ihf ihx =>
    simp only [closedQAt, Bool.and_eq_true] at h
    exact ⟨ihf h.1, ihx h.2⟩
  | ite c t f ihc iht ihf =>
    simp only [closedQAt, Bool.and_eq_true] at h
    exact ⟨ihc h.1.1, iht h.1.2, ihf h.2⟩
  | absurd a body ih => exact ih h

/-- Erase pure calls whose argument binder is quantity zero. -/
def eraseZero : QTerm → QTerm
  | .var n => .var n
  | .unit => .unit
  | .bool b => .bool b
  | .lam q a body => .lam q a (eraseZero body)
  | .app (.lam .zero a body) x =>
    if closedQAt 0 body then eraseZero body
    else .app (.lam .zero a (eraseZero body)) (eraseZero x)
  | .app f x => .app (eraseZero f) (eraseZero x)
  | .ite c t f => .ite (eraseZero c) (eraseZero t) (eraseZero f)
  | .absurd a body => .absurd a (eraseZero body)

namespace Quantity

theorem le_zero (h : Fixed.Quantity.Le q .zero) : q = .zero := by
  simpa [Fixed.Quantity.Le, Fixed.Quantity.join_zero] using h

theorem add_eq_zero_iff (a b : Fixed.Quantity) :
    Fixed.Quantity.add a b = .zero ↔ a = .zero ∧ b = .zero := by
  cases a <;> cases b <;> simp [Fixed.Quantity.add]

theorem join_eq_zero_iff (a b : Fixed.Quantity) :
    Fixed.Quantity.join a b = .zero ↔ a = .zero ∧ b = .zero := by
  cases a <;> cases b <;> simp [Fixed.Quantity.join]

theorem mul_eq_zero_iff (a b : Fixed.Quantity) :
    Fixed.Quantity.mul a b = .zero ↔ a = .zero ∨ b = .zero := by
  cases a <;> cases b <;> simp [Fixed.Quantity.mul]

end Quantity

def qsize : QTerm → Nat
  | .var _ | .unit | .bool _ => 1
  | .lam _ _ body => 1 + qsize body
  | .app f x => 1 + qsize f + qsize x
  | .ite c t f => 1 + qsize c + qsize t + qsize f
  | .absurd _ body => 1 + qsize body

theorem freeVars_bound (hm : m ∈ freeVars e) (hc : Fixed.ClosedAt n (eraseQ e)) : m < n := by
  induction e generalizing n m with
  | var k =>
    simp only [freeVars, List.mem_singleton] at hm
    subst m
    exact hc
  | unit | bool _ => contradiction
  | lam q a body ih =>
    simp only [freeVars, List.mem_filterMap] at hm
    obtain ⟨k, hk, heq⟩ := hm
    split at heq
    · contradiction
    · have hm' : k - 1 = m := Option.some.inj heq
      have hlt := ih (n := n + 1) (m := k) hk hc
      omega
  | app f x ihf ihx =>
    simp only [freeVars, List.mem_append] at hm
    exact hm.elim (fun h => ihf h hc.1) (fun h => ihx h hc.2)
  | ite c t f ihc iht ihf =>
    simp only [freeVars, List.mem_append] at hm
    rcases hm with (hc' | ht) | hf
    · exact ihc hc' hc.1
    · exact iht ht hc.2.1
    · exact ihf hf hc.2.2
  | absurd a body ih => exact ih hm hc

theorem closedAt_of_bound (h : ∀ m, m ∈ freeVars e → m < n) : Fixed.ClosedAt n (eraseQ e) := by
  induction e generalizing n with
  | var m =>
    simp only [eraseQ, Fixed.ClosedAt]
    exact h m (by simp [freeVars])
  | unit | bool _ => trivial
  | lam q a body ih =>
    apply ih
    intro m hm
    by_cases hz : m = 0
    · subst m; omega
    · have hmem : m - 1 ∈ freeVars (.lam q a body) := by
        simp only [freeVars, List.mem_filterMap]
        exact ⟨m, hm, by simp [hz]⟩
      have hlt := h (m - 1) hmem
      omega
  | app f x ihf ihx =>
    exact ⟨ihf (fun m hm => h m (by simp [freeVars, hm])),
      ihx (fun m hm => h m (by simp [freeVars, hm]))⟩
  | ite c t f ihc iht ihf =>
    refine ⟨ihc (fun m hm => h m (by simp [freeVars, hm])),
      iht (fun m hm => h m (by simp [freeVars, hm])),
      ihf (fun m hm => h m (by simp [freeVars, hm]))⟩
  | absurd a body ih => exact ih h

theorem freeVars_nil_of_closed (hc : Fixed.ClosedAt 0 (eraseQ e)) : freeVars e = [] := by
  cases hf : freeVars e with
  | nil => rfl
  | cons m ms =>
    have hm : m ∈ freeVars e := by simp [hf]
    have hlt := freeVars_bound hm hc
    omega

theorem closed_of_freeVars_nil (hf : freeVars e = []) : Fixed.ClosedAt 0 (eraseQ e) := by
  apply closedAt_of_bound (n := 0)
  intro m hm
  simp [hf] at hm

theorem mem_usageIn_ne_zero (hr : result ≠ .zero) (hm : n ∈ freeVars e) :
    usageIn ctx result e n ≠ .zero := by
  induction e generalizing ctx result n with
  | var m =>
    simp only [freeVars, List.mem_singleton] at hm
    subst m
    simp [usageIn, Quantity.var]
  | unit | bool _ => contradiction
  | lam q a body ih =>
    simp only [freeVars, List.mem_filterMap] at hm
    obtain ⟨k, hk, heq⟩ := hm
    split at heq
    · contradiction
    · have hm' : k - 1 = n := Option.some.inj heq
      have hne := ih (n := k) (ctx := .value) hr hk
      have hki : n + 1 = k := by omega
      cases ctx with
      | value =>
        simp only [usageIn]
        rw [hki]
        intro hz
        rw [Quantity.mul_eq_zero_iff] at hz
        exact hz.elim (fun h => hr h) (fun h => hne h)
      | call =>
        simp only [usageIn]
        rw [hki]
        exact hne
  | app f x ihf ihx =>
    simp only [freeVars, List.mem_append] at hm
    simp only [usageIn]
    intro hz
    rw [Quantity.add_eq_zero_iff] at hz
    exact hm.elim (fun h => ihf hr h hz.1) (fun h => ihx (by decide) h hz.2)
  | ite c t f ihc iht ihf =>
    simp only [freeVars, List.mem_append] at hm
    cases ctx with
    | value =>
      simp only [usageIn]
      intro hz
      rw [Quantity.add_eq_zero_iff, Quantity.join_eq_zero_iff] at hz
      rcases hm with (hc | ht) | hf
      · exact ihc (by decide) hc hz.1
      · exact iht hr ht hz.2.1
      · exact ihf hr hf hz.2.2
    | call =>
      simp only [usageIn]
      intro hz
      rw [Quantity.add_eq_zero_iff, Quantity.join_eq_zero_iff] at hz
      rcases hm with (hc | ht) | hf
      · exact ihc (by decide) hc hz.1
      · exact iht hr ht hz.2.1
      · exact ihf hr hf hz.2.2
  | absurd a body ih =>
    simp only [usageIn]
    exact ih (ctx := .value) (result := .one) (by decide) hm

theorem mem_usage_ne_zero (hm : n ∈ freeVars e) : usage e n ≠ .zero :=
  mem_usageIn_ne_zero (by decide) hm

theorem closed_of_closed_succ_usage_zero (hc : Fixed.ClosedAt 1 (eraseQ e))
    (hz : usage e 0 = .zero) : Fixed.ClosedAt 0 (eraseQ e) := by
  apply closed_of_freeVars_nil
  cases hf : freeVars e with
  | nil => rfl
  | cons m ms =>
    have hm : m ∈ freeVars e := by simp [hf]
    have hlt := freeVars_bound hm hc
    have hm0 : m = 0 := by omega
    subst m
    exact (mem_usage_ne_zero hm hz).elim

theorem usage_eq_zero_of_closed (hc : Fixed.ClosedAt 0 (eraseQ e)) : usage e n = .zero := by
  apply usage_eq_zero_of_not_free
  simp [freeVars_nil_of_closed hc]

theorem eraseZero_closed (h : LocalUsageOK e) (hc : Fixed.ClosedAt n (eraseQ e)) :
    Fixed.ClosedAt n (eraseQ (eraseZero e)) := by
  generalize hq : qsize e = k
  induction k using Nat.strongRecOn generalizing e n with
  | ind k ih =>
    subst k
    cases e with
    | var m | unit | bool _ => exact hc
    | lam q a body => exact ih (qsize body) (by simp only [qsize]; omega) (e := body) h.2 hc rfl
    | app f x =>
      have hf := ih (qsize f) (by simp only [qsize]; omega) (e := f) h.1 hc.1 rfl
      have hx := ih (qsize x) (by simp only [qsize]; omega) (e := x) h.2 hc.2 rfl
      cases f with
      | lam q a body =>
        cases q with
        | zero =>
          by_cases hb : closedQAt 0 body = true
          · simp only [eraseZero, hb, if_true]
            exact (ih (qsize body) (by simp only [qsize]; omega) (e := body) h.1.2 (closedQAt_sound hb) rfl).weaken
              (Nat.zero_le n)
          · simp only [eraseZero, hb, if_false]
            exact ⟨hf, hx⟩
        | one | many => exact ⟨hf, hx⟩
      | var m | unit | bool _ | app _ _ | ite _ _ _ | absurd _ _ => exact ⟨hf, hx⟩
    | ite c t f =>
      exact ⟨ih (qsize c) (by simp only [qsize]; omega) (e := c) h.1 hc.1 rfl,
        ih (qsize t) (by simp only [qsize]; omega) (e := t) h.2.1 hc.2.1 rfl,
        ih (qsize f) (by simp only [qsize]; omega) (e := f) h.2.2 hc.2.2 rfl⟩
    | absurd a body => exact ih (qsize body) (by simp only [qsize]; omega) (e := body) h hc rfl

theorem eraseZero_correct (h : LocalUsageOK e) : QZeroSteps e (eraseZero e) := by
  induction e with
  | var n | unit | bool _ => exact .refl
  | lam q a body ih => exact (ih h.2).lam
  | app f x ihf ihx =>
    cases f with
    | lam q a body =>
      cases q with
      | zero =>
        by_cases hb : closedQAt 0 body = true
        · have hz : usage (eraseZero body) 0 = .zero :=
            usage_eq_zero_of_closed (eraseZero_closed h.1.2 (closedQAt_sound hb))
          simp only [eraseZero, hb, if_true]
          exact ((ihf h.1).app (ihx h.2)).trans (.next (.beta hz (eraseZero_closed h.1.2 (closedQAt_sound hb))) .refl)
        · simp only [eraseZero, hb, if_false]
          exact (ihf h.1).app (ihx h.2)
      | one | many => exact (ihf h.1).app (ihx h.2)
    | var n | unit | bool _ | app _ _ | ite _ _ _ | absurd _ _ =>
      exact (ihf h.1).app (ihx h.2)
  | ite c t f ihc iht ihf => exact (ihc h.1).ite (iht h.2.1) (ihf h.2.2)
  | absurd a body ih => exact (ih h).absurd
/-- Checked Q6.1 lowering for closed direct-call terms. It is intentionally
    separate from the reference interpreter's `lower`. -/
def eraseLower (e : QTerm) : Option (Fixed.Ty × RuntimeTerm) :=
  (Fixed.infer (eraseQCtx closedQ) (eraseQ e)).bind fun a =>
    if closedQAt 0 e && checkUsage closedQ e && directCalls e then
      some (a, lowerTerm (eraseZero e))
    else none

theorem eraseLower_sound (h : eraseLower e = some (a, r)) :
    Fixed.HasType (eraseQCtx closedQ) (eraseQ e) a ∧ UsageBounded closedQ e ∧ DirectCalls e ∧
      Fixed.ClosedAt 0 (eraseQ e) ∧ Fixed.ClosedAt 0 (eraseQ (eraseZero e)) ∧
      QZeroSteps e (eraseZero e) ∧ r = lowerTerm (eraseZero e) := by
  simp only [eraseLower] at h
  cases hi : Fixed.infer (eraseQCtx closedQ) (eraseQ e) with
  | none => simp [hi] at h
  | some a' =>
    simp only [hi, Option.bind] at h
    by_cases hc : closedQAt 0 e = true
    · simp only [hc, Bool.true_and] at h
      by_cases hu : checkUsage closedQ e = true
      · simp only [hu, Bool.true_and] at h
        by_cases hd : directCalls e = true
        · simp only [hd, if_true, Option.some.injEq, Prod.mk.injEq] at h
          cases h.1
          cases h.2
          have hclosed := closedQAt_sound hc
          exact ⟨Fixed.infer_sound hi, checkUsage_sound hu, directCalls_sound hd, hclosed,
            eraseZero_closed (checkUsage_sound hu).binders hclosed,
            eraseZero_correct (checkUsage_sound hu).binders, rfl⟩
        · simp [hd] at h
      · simp [hu] at h
    · simp [hc] at h

theorem eraseLower_runSteps_config (h : eraseLower e = some (a, r)) (fuel : Nat) :
    r = lowerTerm (eraseZero e) ∧
      ConfigValid (runSteps initial r fuel).1 (runSteps initial r fuel).2 := by
  obtain ⟨_, _, _, _, _, _, rfl⟩ := eraseLower_sound h
  exact ⟨rfl, ofCore_runSteps_config (eraseQ (eraseZero e)) fuel⟩

theorem eraseLower_runSteps_erasure (h : eraseLower e = some (a, r)) (fuel : Nat) :
    r = lowerTerm (eraseZero e) ∧ QZeroSteps e (eraseZero e) ∧
      ∃ c', ErasureValid (runSteps initial r fuel).2 c' ∧ Fixed.Steps (eraseQ (eraseZero e)) c' := by
  obtain ⟨_, _, _, _, hc, hz, rfl⟩ := eraseLower_sound h
  exact ⟨rfl, hz, ofCore_runSteps_erasure hc fuel⟩

end Fixed.Memory.Resource
