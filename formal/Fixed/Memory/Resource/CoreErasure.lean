import Fixed.Memory.Resource.CoreSteps

namespace Fixed

/-- Variables below the supplied depth are bound. -/
def ClosedAt (n : Nat) : Term → Prop
  | .var m => m < n
  | .unit | .bool _ => True
  | .lam _ body => ClosedAt (n + 1) body
  | .app f x => ClosedAt n f ∧ ClosedAt n x
  | .ite c t f => ClosedAt n c ∧ ClosedAt n t ∧ ClosedAt n f
  | .absurd _ body => ClosedAt n body

theorem ClosedAt.weaken {e : Term} (h : ClosedAt n e) (hn : n ≤ m) : ClosedAt m e := by
  induction e generalizing n m with
  | var k => exact Nat.lt_of_lt_of_le h hn
  | unit | bool _ => trivial
  | lam _ body ih => exact ih h (Nat.add_le_add_right hn 1)
  | app f x ihf ihx => exact ⟨ihf h.1 hn, ihx h.2 hn⟩
  | ite c t f ihc iht ihf => exact ⟨ihc h.1 hn, iht h.2.1 hn, ihf h.2.2 hn⟩
  | absurd _ body ih => exact ih h hn

theorem rename_closed (h : ClosedAt n e) (hρ : ∀ m, m < n → ρ m = m) : rename ρ e = e := by
  induction e generalizing n ρ with
  | var m => simp [rename, hρ m h]
  | unit | bool _ => rfl
  | lam a body ih =>
    simp only [rename]
    congr 1
    apply ih h
    intro m hm
    cases m with
    | zero => rfl
    | succ m => simp [liftRen, hρ m (Nat.lt_of_succ_lt_succ hm)]
  | app f x ihf ihx => simp [rename, ihf h.1 hρ, ihx h.2 hρ]
  | ite c t f ihc iht ihf => simp [rename, ihc h.1 hρ, iht h.2.1 hρ, ihf h.2.2 hρ]
  | absurd a body ih => simp [rename, ih h hρ]

theorem rename_closed0 (h : ClosedAt 0 e) : rename ρ e = e :=
  rename_closed h (by intro m hm; omega)

theorem subst_closed (h : ClosedAt n e) (hσ : ∀ m, m < n → σ m = .var m) : subst σ e = e := by
  induction e generalizing n σ with
  | var m => simp [subst, hσ m h]
  | unit | bool _ => rfl
  | lam a body ih =>
    simp only [subst]
    congr 1
    apply ih h
    intro m hm
    cases m with
    | zero => rfl
    | succ m => simp [liftSub, hσ m (Nat.lt_of_succ_lt_succ hm), rename]
  | app f x ihf ihx => simp [subst, ihf h.1 hσ, ihx h.2 hσ]
  | ite c t f ihc iht ihf => simp [subst, ihc h.1 hσ, iht h.2.1 hσ, ihf h.2.2 hσ]
  | absurd a body ih => simp [subst, ih h hσ]

theorem subst_closed0 (h : ClosedAt 0 e) : subst σ e = e :=
  subst_closed h (by intro m hm; omega)

/-- Substitution for the variable at exactly `n`; unlike `single`, this is
    explicit about the binding depth used by runtime replacement. -/
def singleAt (n : Nat) (v : Term) (m : Nat) : Term :=
  if m < n then .var m else if n = m then v else .var (m - 1)

theorem singleAt_zero (v : Term) : singleAt 0 v = single v := by
  funext m
  cases m <;> simp [singleAt, single]

theorem liftSub_singleAt (hv : ClosedAt 0 v) :
    liftSub (singleAt n v) = singleAt (n + 1) v := by
  funext m
  cases m with
  | zero => simp [liftSub, singleAt]
  | succ m =>
    by_cases hlt : m < n
    · have hlt' : m + 1 < n + 1 := by omega
      have hne : n ≠ m := by omega
      simp [liftSub, singleAt, hlt, hlt', hne, rename]
    · by_cases heq : n = m
      · subst m
        simp [liftSub, singleAt, rename_closed0 hv]
      · have hgt : n < m := by omega
        have hlt' : ¬m + 1 < n + 1 := by omega
        have heq' : n + 1 ≠ m + 1 := by omega
        have hm : m - 1 + 1 = m := by omega
        simp [liftSub, singleAt, hlt, hlt', heq, heq', rename, hm]

theorem closed_subst_singleAt (hv : ClosedAt 0 v) (he : ClosedAt (n + 1) e) :
    ClosedAt n (subst (singleAt n v) e) := by
  induction e generalizing n with
  | var m =>
    simp only [ClosedAt] at he
    by_cases h : n = m
    · subst m
      simp [subst, singleAt]
      exact hv.weaken (Nat.zero_le n)
    · by_cases hlt : m < n
      · simp [subst, singleAt, h, hlt, ClosedAt, hlt]
      · have : n < m := by omega
        omega
  | unit | bool _ => trivial
  | lam a body ih =>
    simp only [subst, ClosedAt] at he ⊢
    rw [liftSub_singleAt hv]
    exact ih he
  | app f x ihf ihx =>
    exact ⟨ihf he.1, ihx he.2⟩
  | ite c t f ihc iht ihf =>
    exact ⟨ihc he.1, iht he.2.1, ihf he.2.2⟩
  | absurd a body ih => exact ih he

end Fixed

namespace Fixed.Memory.Resource

mutual
  def ClosedValue : Value → Prop
    | .unit | .bool _ | .ref _ => True
    | .closure _ _ body =>
      (∀ c, eraseTerm body = some c → Fixed.ClosedAt 1 c) ∧ ClosedValues body

  def ClosedValues : RuntimeTerm → Prop
    | .value v => ClosedValue v
    | .var _ | .unit | .bool _ => True
    | .lam _ body => ClosedValues body
    | .app f x => ClosedValues f ∧ ClosedValues x
    | .ite c t f => ClosedValues c ∧ ClosedValues t ∧ ClosedValues f
    | .absurd _ body => ClosedValues body
end

theorem closedValue_erase (hv : ClosedValue v) (he : eraseValue v = some c) :
    Fixed.ClosedAt 0 c := by
  cases v with
  | unit | bool _ => cases he; trivial
  | ref p => simp [eraseValue] at he
  | closure p a body =>
    simp only [eraseValue] at he
    let ⟨cbody, hb⟩ := Option.map_eq_some.mp he
    cases hb.2
    exact hv.1 cbody hb.1

theorem replace_closedValues (hv : ClosedValue v) (he : ClosedValues e) :
    ClosedValues (replace n v e) := by
  induction e using runtime_ind generalizing n with
  | value w => exact he
  | var m => by_cases h : n = m <;> simp [replace, h, ClosedValues, hv]
  | unit | bool _ => trivial
  | lam a body ih => exact ih he
  | app f x ihf ihx => exact ⟨ihf he.1, ihx he.2⟩
  | ite c t f ihc iht ihf => exact ⟨ihc he.1, iht he.2.1, ihf he.2.2⟩
  | absurd a body ih => exact ih he

theorem erase_replace (v : Value) (cv : Fixed.Term) (hv : eraseValue v = some cv)
    (hcv : Fixed.ClosedAt 0 cv) (he : eraseTerm e = some c)
    (hc : Fixed.ClosedAt (n + 1) c) (hvals : ClosedValues e) :
    eraseTerm (replace n v e) = some (Fixed.subst (Fixed.singleAt n cv) c) := by
  induction e using runtime_ind generalizing n c with
  | value w =>
    simp only [replace]
    rw [he, Fixed.subst_closed0 (closedValue_erase hvals he)]
  | var m =>
    simp only [eraseTerm, Option.some.injEq] at he
    subst c
    simp only [Fixed.ClosedAt] at hc
    by_cases h : n = m
    · subst m
      simp [replace, eraseTerm, Fixed.subst, Fixed.singleAt, hv]
    · by_cases hlt : m < n
      · simp [replace, eraseTerm, Fixed.subst, Fixed.singleAt, h, hlt]
      · have : n < m := by omega
        omega
  | unit | bool _ => cases he; rfl
  | lam a body ih =>
    simp only [eraseTerm] at he
    let ⟨cbody, hbody⟩ := Option.map_eq_some.mp he
    cases hbody.2
    simp only [Fixed.ClosedAt] at hc
    simp only [replace, eraseTerm]
    rw [ih hbody.1 hc hvals]
    simp [Fixed.subst, Fixed.liftSub_singleAt hcv]
  | app f x ihf ihx =>
    simp only [eraseTerm] at he
    let ⟨cf, hf⟩ := Option.bind_eq_some.mp he
    let ⟨cx, hx⟩ := Option.map_eq_some.mp hf.2
    cases hx.2
    simp only [Fixed.ClosedAt] at hc
    simp only [replace, eraseTerm]
    rw [ihf hf.1 hc.1 hvals.1, ihx hx.1 hc.2 hvals.2]
    rfl
  | ite c t f ihc iht ihf =>
    simp only [eraseTerm] at he
    let ⟨cc, hcc⟩ := Option.bind_eq_some.mp he
    let ⟨ct, hct⟩ := Option.bind_eq_some.mp hcc.2
    let ⟨cf, hcf⟩ := Option.map_eq_some.mp hct.2
    cases hcf.2
    simp only [Fixed.ClosedAt] at hc
    simp only [replace, eraseTerm]
    rw [ihc hcc.1 hc.1 hvals.1, iht hct.1 hc.2.1 hvals.2.1,
      ihf hcf.1 hc.2.2 hvals.2.2]
    rfl
  | absurd a body ih =>
    simp only [eraseTerm] at he
    let ⟨cbody, hbody⟩ := Option.map_eq_some.mp he
    cases hbody.2
    simp only [Fixed.ClosedAt] at hc
    simp only [replace, eraseTerm]
    rw [ih hbody.1 hc hvals]
    rfl

theorem eraseValue_value (h : eraseValue v = some c) : Fixed.Value c := by
  cases v with
  | unit => cases h; exact .unit
  | bool b => cases h; exact .bool
  | ref p => simp [eraseValue] at h
  | closure p a body =>
    simp only [eraseValue] at h
    let ⟨body', hb⟩ := Option.map_eq_some.mp h
    cases hb.2
    exact .lam

theorem closedValue_unit : ClosedValue .unit := trivial

theorem closedValue_bool (b : Bool) : ClosedValue (.bool b) := trivial

theorem closedValue_closure (he : eraseTerm body = some c)
    (hc : Fixed.ClosedAt 1 c) (hvals : ClosedValues body) : ClosedValue (.closure p a body) := by
  refine ⟨?_, hvals⟩
  intro c' h'
  have hc' : c' = c := Option.some.inj (h'.symm.trans he)
  subst c'
  exact hc

structure ErasureValid (e : RuntimeTerm) (c : Fixed.Term) : Prop where
  erase : eraseTerm e = some c
  closed : Fixed.ClosedAt 0 c
  values : ClosedValues e

theorem erase_ofCore (e : Fixed.Term) : eraseTerm (ofCore e) = some e := by
  induction e with
  | var n | unit | bool _ => rfl
  | lam a body ih => simp [ofCore, eraseTerm, ih]
  | app f x ihf ihx => simp [ofCore, eraseTerm, ihf, ihx]
  | ite c t f ihc iht ihf => simp [ofCore, eraseTerm, ihc, iht, ihf]
  | absurd a body ih => simp [ofCore, eraseTerm, ih]

theorem ofCore_closedValues (e : Fixed.Term) : ClosedValues (ofCore e) := by
  induction e with
  | var n | unit | bool _ => trivial
  | lam a body ih => exact ih
  | app f x ihf ihx => exact ⟨ihf, ihx⟩
  | ite c t f ihc iht ihf => exact ⟨ihc, iht, ihf⟩
  | absurd a body ih => exact ih

theorem ofCore_erasureValid (hc : Fixed.ClosedAt 0 e) : ErasureValid (ofCore e) e :=
  ⟨erase_ofCore e, hc, ofCore_closedValues e⟩

theorem erasureValid_value (he : eraseValue v = some c) (hc : Fixed.ClosedAt 0 c)
    (hv : ClosedValue v) : ErasureValid (.value v) c :=
  ⟨he, hc, hv⟩

theorem erasureValid_app (hf : eraseTerm f = some cf) (hx : eraseTerm x = some cx)
    (hc : Fixed.ClosedAt 0 cf ∧ Fixed.ClosedAt 0 cx)
    (hv : ClosedValues f ∧ ClosedValues x) : ErasureValid (.app f x) (.app cf cx) := by
  refine ⟨?_, hc, hv⟩
  simp [eraseTerm, hf, hx]

theorem erasureValid_ite (hc : eraseTerm c = some cc) (ht : eraseTerm t = some ct)
    (hf : eraseTerm f = some cf)
    (hclosed : Fixed.ClosedAt 0 cc ∧ Fixed.ClosedAt 0 ct ∧ Fixed.ClosedAt 0 cf)
    (hv : ClosedValues c ∧ ClosedValues t ∧ ClosedValues f) :
    ErasureValid (.ite c t f) (.ite cc ct cf) := by
  refine ⟨?_, hclosed, hv⟩
  simp [eraseTerm, hc, ht, hf]

theorem erasureValid_absurd (he : eraseTerm body = some c) (hc : Fixed.ClosedAt 0 c)
    (hv : ClosedValues body) : ErasureValid (.absurd a body) (.absurd a c) := by
  refine ⟨?_, hc, hv⟩
  simp [eraseTerm, he]

theorem sourceValue_erasure (hs : sourceValue e = true) (he : eraseTerm e = some c) :
    Fixed.Value c := by
  cases e with
  | value v => exact eraseValue_value he
  | var n | app _ _ | ite _ _ _ | absurd _ _ => simp [sourceValue] at hs
  | unit => cases he; exact .unit
  | bool b => cases he; exact .bool
  | lam a body =>
    simp only [eraseTerm] at he
    let ⟨body', hb⟩ := Option.map_eq_some.mp he
    cases hb.2
    exact .lam

theorem executeStep_appRight_erasure (vf : eraseTerm f = some cf) (fc : Fixed.ClosedAt 0 cf)
    (fvals : ClosedValues f) (fvalue : Fixed.Value cf)
    (ihx : ∀ {s t : State} {e' : RuntimeTerm}, ErasureValid x cx →
      executeStep s x = some (t, e') →
      ∃ cx', ErasureValid e' cx' ∧ (cx = cx' ∨ Fixed.Step cx cx'))
    (vx : ErasureValid x cx)
    (h : Option.map (fun x => (reframe x.fst (f.app x.snd), f.app x.snd))
      (executeStep s x) = some (t, e')) :
    ∃ c', ErasureValid e' c' ∧ (Fixed.Term.app cf cx = c' ∨ Fixed.Step (.app cf cx) c') := by
  cases hx : executeStep s x with
  | none => simp [hx] at h
  | some u =>
    simp only [hx] at h
    let ⟨u, hu⟩ := Option.map_eq_some.mp h
    have hs := congrArg Prod.fst hu.2
    have he := congrArg Prod.snd hu.2
    simp at hs he
    cases u
    subst t <;> subst e'
    obtain ⟨cx', vx', rel⟩ := ihx vx (hx.trans hu.1)
    refine ⟨.app cf cx', erasureValid_app vf vx'.erase ⟨fc, vx'.closed⟩ ⟨fvals, vx'.values⟩, ?_⟩
    cases rel with
    | inl h => cases h; exact Or.inl rfl
    | inr h => exact Or.inr (.appRight fvalue h)

theorem executeStep_appGeneral_erasure (vf : eraseTerm f = some cf) (fc : Fixed.ClosedAt 0 cf)
    (fvals : ClosedValues f) (vx : ErasureValid x cx)
    (ihf : ∀ {s t : State} {e' : RuntimeTerm}, ErasureValid f cf →
      executeStep s f = some (t, e') →
      ∃ cf', ErasureValid e' cf' ∧ (cf = cf' ∨ Fixed.Step cf cf'))
    (ihx : ∀ {s t : State} {e' : RuntimeTerm}, ErasureValid x cx →
      executeStep s x = some (t, e') →
      ∃ cx', ErasureValid e' cx' ∧ (cx = cx' ∨ Fixed.Step cx cx'))
    (h : (match executeStep s f with
      | some (t, f) => some (reframe t (f.app x), f.app x)
      | none =>
        if sourceValue f = true then
          Option.map (fun x => (reframe x.fst (f.app x.snd), f.app x.snd)) (executeStep s x)
        else none) = some (t, e')) :
    ∃ c', ErasureValid e' c' ∧ (Fixed.Term.app cf cx = c' ∨ Fixed.Step (.app cf cx) c') := by
  cases hf : executeStep s f with
  | some u =>
    simp only [hf] at h
    cases u
    cases h
    obtain ⟨cf', vf', rel⟩ := ihf ⟨vf, fc, fvals⟩ hf
    refine ⟨.app cf' cx, erasureValid_app vf'.erase vx.erase ⟨vf'.closed, vx.closed⟩
      ⟨vf'.values, vx.values⟩, ?_⟩
    cases rel with
    | inl h => cases h; exact Or.inl rfl
    | inr h => exact Or.inr (.appLeft h)
  | none =>
    simp only [hf] at h
    by_cases hv : sourceValue f = true
    · simp only [hv, if_true] at h
      exact executeStep_appRight_erasure vf fc fvals (sourceValue_erasure hv vf) ihx vx h
    · simp [hv] at h

theorem executeStep_iteGeneral_erasure (vc : eraseTerm c = some cc)
    (vt : eraseTerm thenBranch = some ct) (vf : eraseTerm elseBranch = some cf)
    (hc : Fixed.ClosedAt 0 cc) (ht : Fixed.ClosedAt 0 ct) (hf : Fixed.ClosedAt 0 cf)
    (hvals : ClosedValues c ∧ ClosedValues thenBranch ∧ ClosedValues elseBranch)
    (ihc : ∀ {s t : State} {e' : RuntimeTerm}, ErasureValid c cc →
      executeStep s c = some (t, e') →
      ∃ cc', ErasureValid e' cc' ∧ (cc = cc' ∨ Fixed.Step cc cc'))
    (h : Option.map (fun x =>
      (reframe x.fst (x.snd.ite thenBranch elseBranch), x.snd.ite thenBranch elseBranch))
      (executeStep s c) = some (t, e')) :
    ∃ c', ErasureValid e' c' ∧ (Fixed.Term.ite cc ct cf = c' ∨ Fixed.Step (.ite cc ct cf) c') := by
  cases hcstep : executeStep s c with
  | none => simp [hcstep] at h
  | some u =>
    simp only [hcstep] at h
    let ⟨u, hu⟩ := Option.map_eq_some.mp h
    have hs := congrArg Prod.fst hu.2
    have he := congrArg Prod.snd hu.2
    simp at hs he
    cases u
    subst t <;> subst e'
    obtain ⟨cc', vc', rel⟩ := ihc ⟨vc, hc, hvals.1⟩ (hcstep.trans hu.1)
    refine ⟨.ite cc' ct cf, erasureValid_ite vc'.erase vt vf
      ⟨vc'.closed, ht, hf⟩ ⟨vc'.values, hvals.2⟩, ?_⟩
    cases rel with
    | inl h => cases h; exact Or.inl rfl
    | inr h => exact Or.inr (.ifCond h)

theorem executeStep_absurdGeneral_erasure (vb : ErasureValid body c)
    (ih : ∀ {s t : State} {e' : RuntimeTerm}, ErasureValid body c →
      executeStep s body = some (t, e') →
      ∃ c', ErasureValid e' c' ∧ (c = c' ∨ Fixed.Step c c'))
    (h : Option.map (fun x => (reframe x.fst (.absurd a x.snd), .absurd a x.snd))
      (executeStep s body) = some (t, e')) :
    ∃ c', ErasureValid e' c' ∧ (Fixed.Term.absurd a c = c' ∨ Fixed.Step (.absurd a c) c') := by
  cases hb : executeStep s body with
  | none => simp [hb] at h
  | some u =>
    simp only [hb] at h
    let ⟨u, hu⟩ := Option.map_eq_some.mp h
    have hs := congrArg Prod.fst hu.2
    have he := congrArg Prod.snd hu.2
    simp at hs he
    cases u
    subst t <;> subst e'
    obtain ⟨c', vb', rel⟩ := ih vb (hb.trans hu.1)
    refine ⟨.absurd a c', erasureValid_absurd vb'.erase vb'.closed vb'.values, ?_⟩
    cases rel with
    | inl h => cases h; exact Or.inl rfl
    | inr h => exact Or.inr (.absurd h)

theorem executeStep_erasure (v : ErasureValid e c) (h : executeStep s e = some (t, e')) :
    ∃ c', ErasureValid e' c' ∧ (c = c' ∨ Fixed.Step c c') := by
  induction e using runtime_ind generalizing s t e' c with
  | value v | var n => contradiction
  | unit =>
    cases h
    have he := v.erase
    cases he
    exact ⟨.unit, erasureValid_value rfl v.closed closedValue_unit, Or.inl rfl⟩
  | bool b =>
    cases h
    have he := v.erase
    cases he
    exact ⟨.bool b, erasureValid_value rfl v.closed (closedValue_bool b), Or.inl rfl⟩
  | lam a body ih =>
    have he := v.erase
    simp only [eraseTerm] at he
    let ⟨cbody, hb⟩ := Option.map_eq_some.mp he
    cases hb.2
    cases hr : releaseList (Graph.Rc.alloc s.rc body.refs) body.refs with
    | none => simp [executeStep, hr] at h
    | some rc =>
      unfold executeStep at h
      let ⟨rc', ha⟩ := Option.map_eq_some.mp h
      have hs := congrArg Prod.fst ha.2
      have he' := congrArg Prod.snd ha.2
      simp at hs he'
      subst t <;> subst e'
      refine ⟨.lam a cbody, erasureValid_value ?_ v.closed
        (closedValue_closure hb.1 v.closed v.values), Or.inl rfl⟩
      simp [eraseValue, hb.1]
  | app f x ihf ihx =>
    have he := v.erase
    simp only [eraseTerm] at he
    let ⟨cf, hf, hrest⟩ := Option.bind_eq_some.mp he
    let ⟨cx, hx, hxeq⟩ := Option.map_eq_some.mp hrest
    cases hxeq
    cases f with
    | value w =>
      cases w with
      | closure p a body =>
        cases x with
        | value arg =>
          let ⟨cbody, hb⟩ := Option.map_eq_some.mp hf
          cases hb.2
          unfold executeStep at h
          cases hr3 : Graph.Rc.releaseFuel
              (dupAll (dupAll s.rc body.refs)
                ((List.replicate (occurrences 0 body - 1) arg.refs).flatten)).next
              (dupAll (dupAll s.rc body.refs)
                ((List.replicate (occurrences 0 body - 1) arg.refs).flatten)) p with
          | none => simp [hr3] at h
          | some rc3 =>
            by_cases ho : occurrences 0 body = 0
            · cases hr4 : releaseValue rc3 arg with
              | none =>
                simp only [ho, Nat.zero_sub, Nat.pred_zero, List.replicate_zero, List.flatten_nil,
                  if_true] at hr3 h
                simp_all only [Option.some_bind, Option.map_none, Option.bind_none]
                change (none : Option (State × RuntimeTerm)) = some (t, e') at h
                cases h
              | some rc4 =>
                let ⟨rc', ha⟩ := Option.bind_eq_some.mp h
                let ⟨b, hb'⟩ := Option.map_eq_some.mp ha.2
                have hs := congrArg Prod.fst hb'.2
                have he' := congrArg Prod.snd hb'.2
                simp at hs he'
                subst t <;> subst e'
                have hrep := erase_replace arg cx hx v.closed.2 hb.1 v.closed.1 v.values.1.2
                rw [Fixed.singleAt_zero] at hrep
                have hc := Fixed.closed_subst_singleAt (n := 0) v.closed.2 v.closed.1
                rw [Fixed.singleAt_zero] at hc
                exact ⟨Fixed.subst (Fixed.single cx) cbody,
                  ⟨hrep, hc, replace_closedValues v.values.2 v.values.1.2⟩,
                  Or.inr (.beta (eraseValue_value hx))⟩
            · let ⟨rc', ha⟩ := Option.bind_eq_some.mp h
              let ⟨b, hb'⟩ := Option.map_eq_some.mp ha.2
              have hs := congrArg Prod.fst hb'.2
              have he' := congrArg Prod.snd hb'.2
              simp at hs he'
              subst t <;> subst e'
              have hrep := erase_replace arg cx hx v.closed.2 hb.1 v.closed.1 v.values.1.2
              rw [Fixed.singleAt_zero] at hrep
              have hc := Fixed.closed_subst_singleAt (n := 0) v.closed.2 v.closed.1
              rw [Fixed.singleAt_zero] at hc
              exact ⟨Fixed.subst (Fixed.single cx) cbody,
                ⟨hrep, hc, replace_closedValues v.values.2 v.values.1.2⟩,
                Or.inr (.beta (eraseValue_value hx))⟩
        | _ =>
          exact executeStep_appRight_erasure (s := s) hf v.closed.1 v.values.1 (eraseValue_value hf) ihx
            ⟨hx, v.closed.2, v.values.2⟩ h
      | _ =>
        exact executeStep_appGeneral_erasure (s := s) hf v.closed.1 v.values.1
          ⟨hx, v.closed.2, v.values.2⟩ ihf ihx h
    | _ =>
      exact executeStep_appGeneral_erasure (s := s) hf v.closed.1 v.values.1
        ⟨hx, v.closed.2, v.values.2⟩ ihf ihx h
  | ite c thenBranch elseBranch ihc iht ihf =>
    have he := v.erase
    simp only [eraseTerm] at he
    let ⟨cc, hcc⟩ := Option.bind_eq_some.mp he
    let ⟨ct, hct⟩ := Option.bind_eq_some.mp hcc.2
    let ⟨cf, hcf⟩ := Option.map_eq_some.mp hct.2
    cases hcf.2
    cases c with
    | value w =>
      cases w with
      | bool b =>
        cases b with
        | true =>
          cases hr : releaseList s.rc elseBranch.refs with
          | none => simp [executeStep, hr] at h
          | some rc =>
            unfold executeStep at h
            let ⟨a, ha⟩ := Option.map_eq_some.mp h
            have hs := congrArg Prod.fst ha.2
            have he' := congrArg Prod.snd ha.2
            simp at hs he'
            subst t <;> subst e'
            exact ⟨ct, ⟨hct.1, v.closed.2.1, v.values.2.1⟩, Or.inr (by
              cases hcc.1
              exact .ifTrue)⟩
        | false =>
          cases hr : releaseList s.rc thenBranch.refs with
          | none => simp [executeStep, hr] at h
          | some rc =>
            unfold executeStep at h
            let ⟨a, ha⟩ := Option.map_eq_some.mp h
            have hs := congrArg Prod.fst ha.2
            have he' := congrArg Prod.snd ha.2
            simp at hs he'
            subst t <;> subst e'
            exact ⟨cf, ⟨hcf.1, v.closed.2.2, v.values.2.2⟩, Or.inr (by
              cases hcc.1
              exact .ifFalse)⟩
      | _ =>
        exact executeStep_iteGeneral_erasure (s := s) hcc.1 hct.1 hcf.1 v.closed.1 v.closed.2.1
          v.closed.2.2 v.values ihc h
    | _ =>
      exact executeStep_iteGeneral_erasure (s := s) hcc.1 hct.1 hcf.1 v.closed.1 v.closed.2.1
        v.closed.2.2 v.values ihc h
  | absurd a body ih =>
    have he := v.erase
    simp only [eraseTerm] at he
    let ⟨cbody, hb⟩ := Option.map_eq_some.mp he
    cases hb.2
    exact executeStep_absurdGeneral_erasure (s := s) ⟨hb.1, v.closed, v.values⟩ ih h

theorem runSteps_erasure (v : ErasureValid e c) (fuel : Nat) :
    ∃ c', ErasureValid (runSteps s e fuel).2 c' ∧ Fixed.Steps c c' := by
  induction fuel generalizing s e c with
  | zero => exact ⟨c, v, .refl⟩
  | succ fuel ih =>
    cases h : executeStep s e with
    | none =>
      refine ⟨c, ?_, .refl⟩
      simpa [runSteps, h] using v
    | some u =>
      cases u with
      | mk t e' =>
        obtain ⟨c1, v1, rel⟩ := executeStep_erasure v h
        obtain ⟨c2, v2, steps⟩ := ih (s := reframe t e') v1
        refine ⟨c2, ?_, ?_⟩
        · simpa [runSteps, h] using v2
        · cases rel with
          | inl heq => cases heq; exact steps
          | inr step => exact .next step steps

theorem ofCore_runSteps_erasure (hc : Fixed.ClosedAt 0 e) (fuel : Nat) :
    ∃ c', ErasureValid (runSteps initial (ofCore e) fuel).2 c' ∧ Fixed.Steps e c' :=
  runSteps_erasure (ofCore_erasureValid hc) fuel

theorem ofCore_runSteps_config (e : Fixed.Term) (fuel : Nat) :
    ConfigValid (runSteps initial (ofCore e) fuel).1 (runSteps initial (ofCore e) fuel).2 :=
  runSteps_config (initial_config e) fuel

end Fixed.Memory.Resource
