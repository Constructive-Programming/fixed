import Fixed.Memory.Resource.CoreErasure

namespace Fixed.Memory.Resource

structure TypedTerm (s : State) (e : RuntimeTerm) (c : Fixed.Term) (a : Fixed.Ty) : Prop where
  resource : TermValid s e
  erasure : ErasureValid e c
  typing : Fixed.HasType Fixed.closed c a

structure TypedConfig (s : State) (e : RuntimeTerm) (c : Fixed.Term) (a : Fixed.Ty) : Prop where
  resource : ConfigValid s e
  erasure : ErasureValid e c
  typing : Fixed.HasType Fixed.closed c a

theorem cell_of_root (v : Graph.Rc.Valid rc) (hp : p ∈ rc.roots) : ∃ c, rc.cells p = some c := by
  obtain ⟨fields, hc⟩ := v.graph.roots p hp
  cases hcell : rc.cells p with
  | none => simp [Graph.Rc.erase, hcell] at hc
  | some c => exact ⟨c, rfl⟩

theorem releaseFuel_valid (v : Graph.Rc.Valid s) (h : Graph.Rc.releaseFuel fuel s p = some t) :
    Graph.Rc.Valid t := (Graph.Rc.releaseFuel_refines v h).2

theorem releaseFuel_roots (v : Graph.Rc.Valid s) (h : Graph.Rc.releaseFuel fuel s p = some t)
    (q : Addr) : t.roots.count q + [p].count q = s.roots.count q :=
  Graph.release_count (Graph.releaseFuel_sound v.graph (Graph.Rc.releaseFuel_refines v h).1) q

theorem releaseFold_total {fuel : Nat}
    (single : ∀ {s p}, Graph.Rc.Valid s → p < fuel → p ∈ s.roots →
      ∃ t, Graph.Rc.releaseFuel fuel s p = some t)
    (v : Graph.Rc.Valid s) (hf : ∀ q ∈ ps, q < fuel) (hp : ∀ q, ps.count q ≤ s.roots.count q) :
    ∃ t, (ps : List Addr).foldlM (fun u q => Graph.Rc.releaseFuel fuel u q) s = some t := by
  induction ps generalizing s with
  | nil => exact ⟨s, rfl⟩
  | cons p ps ih =>
    have hroot : p ∈ s.roots := by
      have hbound := hp p
      rw [← List.count_pos_iff]
      simp only [List.count_cons_self] at hbound ⊢
      omega
    obtain ⟨u, hu⟩ := single v (hf p (by simp)) hroot
    have vu : Graph.Rc.Valid u := releaseFuel_valid v hu
    have hrest : ∀ q, ps.count q ≤ u.roots.count q := by
      intro q
      have hbound := hp q
      have hrel := releaseFuel_roots v hu q
      by_cases hpq : p = q
      · subst q
        simp only [List.count_cons_self, List.count_nil] at hbound hrel
        omega
      · rw [List.count_cons_of_ne hpq] at hbound hrel
        simp only [List.count_nil] at hrel
        omega
    obtain ⟨t, ht⟩ := ih vu (fun q hq => hf q (by simp [hq])) hrest
    exact ⟨t, by simp [List.foldlM_cons, hu, ht, Option.some_bind]⟩

theorem releaseFuel_total {fuel : Nat} (v : Graph.Rc.Valid s) (bound : p < fuel)
    (hp : p ∈ s.roots) : ∃ t, Graph.Rc.releaseFuel fuel s p = some t := by
  induction fuel generalizing s p with
  | zero => omega
  | succ fuel ih =>
    simp only [Graph.Rc.releaseFuel]
    split
    · obtain ⟨c, hc⟩ := cell_of_root v hp
      simp only [hc]
      by_cases shared : 1 < c.count
      · exact ⟨Graph.Rc.decrement (Graph.Rc.removeRoot s p) p, by simp [shared]⟩
      · have noSupport : ¬ Graph.Incoming (Graph.removeRoot (Graph.Rc.erase s) p) p :=
          fun support => shared ((Graph.Rc.shared_iff v hp hc).mpr support)
        have vu := Graph.Rc.unlink_valid v hp hc noSupport
        have hf : ∀ q ∈ c.fields, q < fuel := by
          intro q hq
          obtain ⟨hqp, children, hcell⟩ := v.graph.edges p c.fields (by simp [Graph.Rc.erase, hc]) q hq
          exact Nat.lt_of_lt_of_le hqp (Nat.le_of_lt_succ bound)
        have hroots : ∀ q, c.fields.count q ≤ (Graph.Rc.unlink s p c.fields).roots.count q := by
          intro q
          simp [Graph.Rc.unlink, List.count_append]
        simp only [shared, if_false] at *
        exact releaseFold_total (@ih) vu hf hroots
    · contradiction

theorem release_next_total (v : Graph.Rc.Valid s) (hp : p ∈ s.roots) :
    ∃ t, Graph.Rc.releaseFuel s.next s p = some t := by
  obtain ⟨c, hc⟩ := cell_of_root v hp
  exact releaseFuel_total v (v.graph.fresh p c.fields (by simp [Graph.Rc.erase, hc])) hp

theorem releaseList_total (v : Graph.Rc.Valid rc) (hp : ∀ q, ps.count q ≤ rc.roots.count q) :
    ∃ t, releaseList rc ps = some t := by
  induction ps generalizing rc with
  | nil => exact ⟨rc, rfl⟩
  | cons p ps ih =>
    obtain ⟨u, hu⟩ := release_next_total (p := p) v (by
      have hbound := hp p
      rw [← List.count_pos_iff]
      simp only [List.count_cons_self] at hbound ⊢
      omega)
    have vu : Graph.Rc.Valid u := release_valid v hu
    have hrest : ∀ q, ps.count q ≤ u.roots.count q := by
      intro q
      have hbound := hp q
      have hrel := releaseFuel_roots v hu q
      by_cases hpq : p = q
      · subst q
        simp only [List.count_cons_self, List.count_nil] at hbound hrel
        omega
      · rw [List.count_cons_of_ne hpq] at hbound hrel
        simp only [List.count_nil] at hrel
        omega
    obtain ⟨t, ht⟩ := ih vu hrest
    have hn : u.next = rc.next := (release_subheap v hu).next
    simp only [releaseList, List.foldlM_cons] at ht ⊢
    rw [hu]
    refine ⟨t, ?_⟩
    change List.foldlM (fun t p => Graph.Rc.releaseFuel rc.next t p) u ps = some t
    rw [hn] at ht
    exact ht

theorem capture_total (vs : TermValid s (.lam a body)) :
    ∃ t e', executeStep s (.lam a body) = some (t, e') := by
  have live : ∀ p ∈ body.refs, p ∈ s.rc.roots := fun p hp => root_of_config vs hp
  have vrc : Graph.Rc.Valid (Graph.Rc.alloc s.rc body.refs) :=
    Graph.Rc.alloc_valid vs.effect.rc live
  have hroots : ∀ q, body.refs.count q ≤ (Graph.Rc.alloc s.rc body.refs).roots.count q := by
    intro q
    have h := vs.live q
    change body.refs.count q ≤ s.rc.roots.count q at h
    change body.refs.count q ≤ (s.rc.next :: s.rc.roots).count q
    by_cases hn : s.rc.next = q
    · subst q
      have hs : (s.rc.next :: s.rc.roots).count s.rc.next = s.rc.roots.count s.rc.next + 1 :=
        List.count_cons_self
      exact hs.symm ▸ Nat.le_succ_of_le h
    · exact (List.count_cons_of_ne hn).symm ▸ h
  obtain ⟨rc, hr⟩ := releaseList_total vrc hroots
  exact ⟨captureState s a body rc, .value (.closure s.rc.next a body), by simp [executeStep, hr]⟩

theorem select_total (vs : TermValid s (.ite (.value (.bool b)) taken dropped)) :
    ∃ t e', executeStep s (.ite (.value (.bool b)) taken dropped) = some (t, e') := by
  cases b with
  | true =>
    have live : ∀ q, dropped.refs.count q ≤ s.rc.roots.count q := by
      intro q
      have h := vs.live q
      simp only [RuntimeTerm.refs, Value.refs, List.append_nil, List.count_append,
        List.count_nil, Nat.zero_add] at h
      omega
    obtain ⟨rc, hr⟩ := releaseList_total vs.effect.rc live
    exact ⟨selectState s taken rc, taken, by simp [executeStep, hr]⟩
  | false =>
    have live : ∀ q, taken.refs.count q ≤ s.rc.roots.count q := by
      intro q
      have h := vs.live q
      simp only [RuntimeTerm.refs, Value.refs, List.append_nil, List.count_append,
        List.count_nil, Nat.zero_add] at h
      omega
    obtain ⟨rc, hr⟩ := releaseList_total vs.effect.rc live
    exact ⟨selectState s dropped rc, dropped, by simp [executeStep, hr]⟩

theorem beta_total (vs : TermValid s (.app (.value (.closure p a body)) (.value v))) :
    ∃ t e', executeStep s (.app (.value (.closure p a body)) (.value v)) = some (t, e') := by
  obtain ⟨o, ho, hcode⟩ := vs.closures (.closure p a body) (by simp [RuntimeTerm.values]) p a body rfl
  have bodyLive : ∀ q ∈ body.refs, ∃ c, s.rc.cells q = some c := by
    intro q hq
    apply live_of_object_field vs.effect ho
    simp only [Object.refs, hcode, Option.map_some, Option.getD_some, List.mem_append]
    exact Or.inr hq
  have sourceLive : ∀ q ∈ [p] ++ v.refs, ∃ c, s.rc.cells q = some c := by
    intro q hq
    have hroot : q ∈ s.rc.roots := root_of_config vs (by simpa [RuntimeTerm.refs, Value.refs] using hq)
    exact cell_of_root vs.effect.rc hroot
  have rc1valid : Graph.Rc.Valid (dupAll s.rc body.refs) := dupAll_valid vs.effect.rc bodyLive
  have rc2valid : Graph.Rc.Valid
      (dupAll (dupAll s.rc body.refs) ((List.replicate (occurrences 0 body - 1) v.refs).flatten)) := by
    apply dupAll_valid rc1valid
    intro q hq
    have hv : q ∈ v.refs := mem_flatten_replicate _ _ hq
    obtain ⟨c, hc⟩ := sourceLive q (by simp [hv])
    obtain ⟨d, hd, _⟩ := dupAll_cells s.rc body.refs hc
    exact ⟨d, hd⟩
  have closureRoot : p ∈ (dupAll (dupAll s.rc body.refs)
      ((List.replicate (occurrences 0 body - 1) v.refs).flatten)).roots := by
    have hroot : p ∈ s.rc.roots := root_of_config vs (by simp [RuntimeTerm.refs, Value.refs])
    have hpos : 0 < s.rc.roots.count p := List.count_pos_iff.mpr hroot
    have h1 := dupAll_roots s.rc body.refs p
    have h2 := dupAll_roots (dupAll s.rc body.refs)
      ((List.replicate (occurrences 0 body - 1) v.refs).flatten) p
    rw [← List.count_pos_iff]
    omega
  obtain ⟨rc3, hr3⟩ := release_next_total rc2valid closureRoot
  have rc3valid : Graph.Rc.Valid rc3 := release_valid rc2valid hr3
  have argRoots : ∀ q ∈ v.refs, q ∈ rc3.roots := by
    intro q hq
    have h1 := dupAll_roots s.rc body.refs q
    have h2 := dupAll_roots (dupAll s.rc body.refs)
      ((List.replicate (occurrences 0 body - 1) v.refs).flatten) q
    have h3 := release_roots rc2valid hr3 q
    have hbound := vs.live q
    by_cases hp : p = q
    · subst q
      have hcount : 2 ≤ ([p] ++ v.refs).count p := by
        have hsource : ([p] ++ v.refs).count p = v.refs.count p + 1 := by
          rw [List.singleton_append, List.count_cons_self]
        rw [hsource]
        have hp' : 0 < v.refs.count p := List.count_pos_iff.mpr hq
        omega
      change ([p] ++ v.refs).count p ≤ s.rc.roots.count p at hbound
      have hpos : 0 < s.rc.roots.count p := by omega
      have hpp : (if (p == p) = true then 1 else 0) = 1 := if_pos (beq_self_eq_true p)
      generalize hD : (if (p == p) = true then 1 else 0) = d at hbound h3
      rw [hpp] at hD
      have hsingle : [p].count p = 1 := List.count_cons_self
      rw [hsingle] at h3
      rw [← List.count_pos_iff]
      omega
    · have hroot : q ∈ s.rc.roots := root_of_config vs (by
        simp only [RuntimeTerm.refs, Value.refs, List.mem_append, List.mem_singleton]
        exact Or.inr hq)
      have hpos : 0 < s.rc.roots.count q := List.count_pos_iff.mpr hroot
      change ([p] ++ v.refs).count q ≤ s.rc.roots.count q at hbound
      have hpq : (if (p == q) = true then 1 else 0) = 0 :=
        if_neg (fun h => hp (LawfulBEq.eq_of_beq h))
      generalize hD : (if (p == q) = true then 1 else 0) = d at hbound h3
      rw [hpq] at hD
      have hsingle : [p].count q = 0 := List.count_cons_of_ne hp
      rw [hsingle] at h3
      rw [← List.count_pos_iff]
      omega
  have hstep : ∃ rc4, (if occurrences 0 body = 0 then releaseValue rc3 v else some rc3) = some rc4 := by
    by_cases ho : occurrences 0 body = 0
    · cases ha : v.addr with
      | none => exact ⟨rc3, by simp [releaseValue, ha, ho]⟩
      | some q =>
        have hq : q ∈ v.refs := by cases v <;> simp_all [Value.addr, Value.refs]
        obtain ⟨rc4, hr4⟩ := release_next_total rc3valid (argRoots q hq)
        exact ⟨rc4, by simp [ho, releaseValue, ha, hr4]⟩
    · exact ⟨rc3, by simp [ho]⟩
  obtain ⟨rc4, hr4⟩ := hstep
  exact ⟨betaState s body v rc4, replace 0 v body, by simp [executeStep, hr3, hr4]⟩

theorem executeStep_progress (vs : TypedTerm s e c a) :
    (∃ v, e = .value v) ∨ ∃ t e', executeStep s e = some (t, e') := by
  induction e using runtime_ind generalizing s c a with
  | value v => exact .inl ⟨v, rfl⟩
  | var n =>
    have he := vs.erasure.erase
    cases he
    have hc := vs.erasure.closed
    simp [Fixed.ClosedAt] at hc
  | unit => exact .inr ⟨literalState s .unit, .value .unit, rfl⟩
  | bool b => exact .inr ⟨literalState s (.bool b), .value (.bool b), rfl⟩
  | lam a body ih => exact .inr (capture_total vs.resource)
  | app f x ihf ihx =>
    have he := vs.erasure.erase
    simp only [eraseTerm] at he
    let ⟨cf, hf, hrest⟩ := Option.bind_eq_some.mp he
    let ⟨cx, hx, hxeq⟩ := Option.map_eq_some.mp hrest
    cases hxeq
    have ht := vs.typing
    cases ht with
    | app htf htx =>
      have vf : ErasureValid f cf := ⟨hf, vs.erasure.closed.1, vs.erasure.values.1⟩
      have vx : ErasureValid x cx := ⟨hx, vs.erasure.closed.2, vs.erasure.values.2⟩
      rcases ihf ⟨term_appLeft vs.resource, vf, htf⟩ with ⟨v, rfl⟩ | ⟨t, f', hs⟩
      · cases v with
        | closure p a body =>
          rcases ihx ⟨term_appRight vs.resource, vx, htx⟩ with ⟨v, rfl⟩ | ⟨u, x', hs⟩
          · exact .inr (Fixed.Memory.Resource.beta_total (p := p) (a := a) (body := body) (v := v) vs.resource)
          · exact .inr ⟨reframe u ((RuntimeTerm.value (.closure p a body)).app x'),
              (RuntimeTerm.value (.closure p a body)).app x', by
              cases x with
              | value v => cases hs
              | _ =>
                unfold executeStep at ⊢
                rw [hs]
                rfl⟩
        | unit =>
          change (some Fixed.Term.unit = some cf) at hf
          cases hf
          cases htf
        | bool b =>
          change (some (.bool b) = some cf) at hf
          cases hf
          cases htf
        | ref p =>
          change (none : Option Fixed.Term) = some cf at hf
          cases hf
      · exact .inr ⟨reframe t (f'.app x), f'.app x, by
          cases f with
          | value v => cases hs
          | _ =>
            unfold executeStep at ⊢
            rw [hs]⟩
  | ite c t f ihc iht ihf =>
    have he := vs.erasure.erase
    simp only [eraseTerm] at he
    let ⟨cc, hcc, hrest⟩ := Option.bind_eq_some.mp he
    let ⟨ct, hct, hrest'⟩ := Option.bind_eq_some.mp hrest
    let ⟨cf, hcf, hfeq⟩ := Option.map_eq_some.mp hrest'
    cases hfeq
    have ht := vs.typing
    cases ht with
    | ite hcType htType hfType =>
      have vc : ErasureValid c cc := ⟨hcc, vs.erasure.closed.1, vs.erasure.values.1⟩
      rcases ihc ⟨term_iteCond vs.resource, vc, hcType⟩ with ⟨v, rfl⟩ | ⟨u, c', hs⟩
      · cases v with
        | bool b => exact .inr (select_total vs.resource)
        | unit =>
          change (some Fixed.Term.unit = some cc) at hcc
          cases hcc
          cases hcType
        | ref p =>
          change (none : Option Fixed.Term) = some cc at hcc
          cases hcc
        | closure p a body =>
          simp only [eraseTerm, eraseValue] at hcc
          let ⟨cbody, hb⟩ := Option.map_eq_some.mp hcc
          cases hb.2
          cases hcType
      · exact .inr ⟨reframe u (c'.ite t f), c'.ite t f, by
          cases c with
          | value v => cases hs
          | _ =>
            unfold executeStep at ⊢
            rw [hs]
            rfl⟩
  | absurd a body ih =>
    have he := vs.erasure.erase
    simp only [eraseTerm] at he
    let ⟨cbody, hb, hbeq⟩ := Option.map_eq_some.mp he
    cases hbeq
    have ht := vs.typing
    cases ht with
    | absurd hbody =>
      rcases ih ⟨term_absurd vs.resource, ⟨hb, vs.erasure.closed, vs.erasure.values⟩, hbody⟩ with
        ⟨v, rfl⟩ | ⟨u, body', hs⟩
      · exact (Fixed.no_empty_value hbody (eraseValue_value hb)).elim
      · exact .inr ⟨reframe u (.absurd a body'), .absurd a body', by
          unfold executeStep at ⊢
          rw [hs]
          rfl⟩

def resourceFailure (s : State) (e : RuntimeTerm) : Bool :=
  match e with
  | .value _ | .var _ | .unit | .bool _ => false
  | .lam _ body => (releaseList (Graph.Rc.alloc s.rc body.refs) body.refs).isNone
  | .app f x =>
    match f with
    | .value (.closure p a body) =>
      match x with
      | .value v =>
        let rc := dupAll s.rc body.refs
        let rc := dupAll rc ((List.replicate (occurrences 0 body - 1) v.refs).flatten)
        match Graph.Rc.releaseFuel rc.next rc p with
        | none => true
        | some rc => (if occurrences 0 body = 0 then releaseValue rc v else some rc).isNone
      | _ => resourceFailure s x
    | _ =>
      match executeStep s f with
      | some _ => false
      | none => if sourceValue f then resourceFailure s x else false
  | .ite c t f =>
    match c with
    | .value (.bool true) => (releaseList s.rc f.refs).isNone
    | .value (.bool false) => (releaseList s.rc t.refs).isNone
    | _ => resourceFailure s c
  | .absurd _ body => resourceFailure s body

inductive StepOutcome where
  | timeout (s : State) (e : RuntimeTerm)
  | value (s : State) (v : Value)
  | step (s : State) (e : RuntimeTerm)
  | stuck (s : State) (e : RuntimeTerm)
  | rejected (s : State) (e : RuntimeTerm)

def checkedStep (s : State) (e : RuntimeTerm) : StepOutcome :=
  match e with
  | .value v => .value s v
  | _ =>
    match executeStep s e with
    | some (t, e') => .step t e'
    | none => if resourceFailure s e then .rejected s e else .stuck s e

def runChecked (s : State) (e : RuntimeTerm) : Nat → StepOutcome
  | 0 => .timeout s e
  | fuel + 1 =>
    match checkedStep s e with
    | .value s' v => .value s' v
    | .step t e' => runChecked (reframe t e') e' fuel
    | .stuck s' e' => .stuck s' e'
    | .rejected s' e' => .rejected s' e'
    | .timeout s' e' => .timeout s' e'

def OutcomeGood : StepOutcome → Prop
  | .timeout _ _ | .value _ _ => True
  | .step _ _ | .stuck _ _ | .rejected _ _ => False

theorem runChecked_good (vs : TypedConfig s e c a) (fuel : Nat) :
    OutcomeGood (runChecked s e fuel) := by
  induction fuel generalizing s e c a with
  | zero => trivial
  | succ fuel ih =>
    have hp := executeStep_progress ⟨config_term vs.resource, vs.erasure, vs.typing⟩
    cases h : executeStep s e with
    | none =>
      rcases hp with ⟨v, rfl⟩ | ⟨t, e', h'⟩
      · simp [runChecked, checkedStep, h, OutcomeGood]
      · exact Option.noConfusion (h'.symm.trans h)
    | some u =>
      cases u with
      | mk t e' =>
        have nextResource := executeStep_config vs.resource h
        obtain ⟨c1, ve, rel⟩ := executeStep_erasure vs.erasure h
        have nextTyping : Fixed.HasType Fixed.closed c1 a := by
          cases rel with
          | inl heq => cases heq; exact vs.typing
          | inr step => exact Fixed.preservation vs.typing step
        have next : TypedConfig (reframe t e') e' c1 a := ⟨nextResource, ve, nextTyping⟩
        cases e <;> simpa [runChecked, checkedStep, h, OutcomeGood] using ih next

theorem runChecked_ne_rejected (vs : TypedConfig s e c a) (fuel : Nat) :
    ∀ s' e', runChecked s e fuel ≠ .rejected s' e' := by
  intro s' e' h
  have good := runChecked_good vs fuel
  rw [h] at good
  exact good

theorem runChecked_ne_stuck (vs : TypedConfig s e c a) (fuel : Nat) :
    ∀ s' e', runChecked s e fuel ≠ .stuck s' e' := by
  intro s' e' h
  have good := runChecked_good vs fuel
  rw [h] at good
  exact good

end Fixed.Memory.Resource
