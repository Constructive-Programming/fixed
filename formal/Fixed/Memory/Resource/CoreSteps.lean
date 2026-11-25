import Fixed.Memory.Resource.CoreSafety

namespace Fixed.Memory.Resource

structure StepEffect (s : State) (e : RuntimeTerm) (t : State) (e' : RuntimeTerm) : Prop where
  effect : EffectValid t
  outer : t.frames.tail = s.frames.tail
  roots : ∀ q, t.rc.roots.count q + e.refs.count q = s.rc.roots.count q + e'.refs.count q
  migrate : ∀ w, KnownClosure s w → LiveValue t.rc w → KnownClosure t w
  known : ∀ w ∈ e'.values, KnownClosure t w

theorem live_of_roots (v : Graph.Rc.Valid rc) (hv : ∀ p ∈ w.refs, p ∈ rc.roots) :
    LiveValue rc w := by
  intro p ha
  have hw : w.refs = [p] := by cases w <;> simp_all [Value.addr, Value.refs]
  have hp : p ∈ rc.roots := hv p (by simp [hw])
  obtain ⟨fields, hc⟩ := v.graph.roots p hp
  cases hcell : rc.cells p with
  | none => simp [Graph.Rc.erase, hcell] at hc
  | some c => exact ⟨c, rfl⟩

theorem dupAll_subheap (rc : Graph.Rc.State) (ps : List Addr) :
    Graph.Subheap (Graph.Rc.erase rc) (Graph.Rc.erase (dupAll rc ps)) := by
  induction ps generalizing rc with
  | nil => exact .refl _
  | cons p ps ih =>
    have hd : Graph.Subheap (Graph.Rc.erase rc) (Graph.Rc.erase (Graph.Rc.dup rc p)) := by
      refine ⟨rfl, ?_⟩
      intro q fs h
      cases hc : rc.cells q with
      | none => simp [Graph.Rc.erase, Graph.Rc.dup, hc] at h
      | some c =>
        by_cases hq : q = p
        · subst q
          simp [Graph.Rc.erase, Graph.Rc.dup, hc] at h
          simp [Graph.Rc.erase, hc, h]
        · simp [Graph.Rc.erase, Graph.Rc.dup, hq, hc] at h
          simp [Graph.Rc.erase, hc, h]
    exact hd.trans (ih (Graph.Rc.dup rc p))

theorem replace_values (e : RuntimeTerm) (n : Nat) (v : Value) (hv : w ∈ (replace n v e).values) :
    w ∈ e.values ∨ (w = v ∧ 0 < occurrences n e) := by
  induction e using runtime_ind generalizing n with
  | value u => exact Or.inl hv
  | var m =>
    by_cases h : n = m
    · subst m
      simp only [replace, if_pos, RuntimeTerm.values, List.mem_singleton] at hv
      exact Or.inr ⟨hv, by simp [occurrences]⟩
    · simp [replace, occurrences, h, RuntimeTerm.values] at hv
  | unit | bool b => exact Or.inl hv
  | lam a body ih =>
    have ih := ih (n + 1) (by simpa [replace, RuntimeTerm.values] using hv)
    exact ih.elim (fun h => Or.inl h) (fun h => Or.inr ⟨h.1, h.2⟩)
  | app f x ihf ihx =>
    simp only [replace, RuntimeTerm.values, List.mem_append] at hv
    rcases hv with hf | hx
    · rcases ihf n hf with hf | ⟨rfl, ho⟩
      · exact Or.inl (by simp [RuntimeTerm.values, hf])
      · exact Or.inr ⟨rfl, by simp [occurrences]; omega⟩
    · rcases ihx n hx with hx | ⟨rfl, ho⟩
      · exact Or.inl (by simp [RuntimeTerm.values, hx])
      · exact Or.inr ⟨rfl, by simp [occurrences]; omega⟩
  | ite c t f ihc iht ihf =>
    simp only [replace, RuntimeTerm.values, List.mem_append] at hv
    rcases hv with (hc | ht) | hf
    · rcases ihc n hc with hc | ⟨rfl, ho⟩
      · exact Or.inl (by simp [RuntimeTerm.values, hc])
      · exact Or.inr ⟨rfl, by simp [occurrences]; omega⟩
    · rcases iht n ht with ht | ⟨rfl, ho⟩
      · exact Or.inl (by simp [RuntimeTerm.values, ht])
      · exact Or.inr ⟨rfl, by simp [occurrences]; omega⟩
    · rcases ihf n hf with hf | ⟨rfl, ho⟩
      · exact Or.inl (by simp [RuntimeTerm.values, hf])
      · exact Or.inr ⟨rfl, by simp [occurrences]; omega⟩
  | absurd a body ih =>
    exact ih n (by simpa [replace, RuntimeTerm.values] using hv)

theorem root_of_config (v : TermValid s e) (hp : p ∈ e.refs) : p ∈ s.rc.roots := by
  have hbound := v.live p
  have hpos : 0 < e.refs.count p := List.count_pos_iff.mpr hp
  rw [← List.count_pos_iff]
  omega

theorem live_of_object_field (v : EffectValid s) (ho : s.objects p = some o)
    (hq : q ∈ o.refs) : ∃ c, s.rc.cells q = some c := by
  obtain ⟨c, hc, hfields⟩ := v.objects p o ho
  obtain ⟨_, children, hcell⟩ := v.rc.graph.edges p o.refs (by simp [Graph.Rc.erase, hc, hfields]) q hq
  cases hq : s.rc.cells q with
  | none => simp [Graph.Rc.erase, hq] at hcell
  | some d => exact ⟨d, rfl⟩

theorem knownClosure_alloc (v : EffectValid s) (o : Object) (hv : KnownClosure s w) :
    KnownClosure (alloc s o) w := by
  intro p a body h
  cases w with
  | unit | bool b | ref q => cases h
  | closure q a' body' =>
    cases h
    obtain ⟨obj, ho, hc⟩ := hv p a body rfl
    obtain ⟨c, hcell, _⟩ := v.objects p obj ho
    have hf : p < s.rc.next := v.rc.graph.fresh p c.fields (by simp [Graph.Rc.erase, hcell])
    exact ⟨obj, by simp [alloc, Nat.ne_of_lt hf, hcell, ho], hc⟩

theorem alloc_effect (v : EffectValid s)
    (live : ∀ p ∈ o.refs, p ∈ s.rc.roots)
    (known : ∀ a body, o.code = some (a, body) → ∀ w ∈ body.values, KnownClosure s w) :
    EffectValid (alloc s o) := by
  have hrc : Graph.Rc.Valid (Graph.Rc.alloc s.rc o.refs) := Graph.Rc.alloc_valid v.rc live
  have oldCell : ∀ p c, s.rc.cells p = some c →
      ∃ d, (Graph.Rc.alloc s.rc o.refs).cells p = some d ∧ d.fields = c.fields := by
    intro p c hc
    have hf : p < s.rc.next := v.rc.graph.fresh p c.fields (by simp [Graph.Rc.erase, hc])
    exact ⟨{ c with count := c.count + o.refs.count p }, by
      simp [Graph.Rc.alloc, Nat.ne_of_lt hf, hc], rfl⟩
  cases hframes : s.frames with
  | nil =>
    have hv := v.frames
    simp [hframes] at hv
  | cons f outer =>
    refine ⟨hrc, by simp [alloc, hframes], ?_, ?_, v.borrows, ?_⟩
    · intro p obj ho
      by_cases h : p = s.rc.next
      · subst p
        have ho' : o = obj := by simpa [alloc] using ho
        subst obj
        exact ⟨⟨1, o.refs⟩, by simp [alloc, Graph.Rc.alloc], rfl⟩
      · have hold : s.objects p = some obj := by simpa [alloc, h] using ho
        obtain ⟨c, hc, hfields⟩ := v.objects p obj hold
        obtain ⟨d, hd, hdf⟩ := oldCell p c hc
        exact ⟨d, hd, hdf.trans hfields⟩
    · intro p c hc
      change (Graph.Rc.alloc s.rc o.refs).cells p = some c at hc
      by_cases h : p = s.rc.next
      · subst p
        exact ⟨o, by simp [alloc]⟩
      · cases hold : s.rc.cells p with
        | none => simp [Graph.Rc.alloc, h, hold] at hc
        | some cold =>
          obtain ⟨obj, ho⟩ := v.complete p cold hold
          exact ⟨obj, by simp [alloc, h, ho]⟩
    · intro p obj ho a body hcode w hw
      by_cases h : p = s.rc.next
      · subst p
        have ho' : o = obj := by simpa [alloc] using ho
        subst obj
        exact knownClosure_alloc v o (known a body hcode w hw)
      · have hold : s.objects p = some obj := by simpa [alloc, h] using ho
        exact knownClosure_alloc v o (v.objectClosures p obj hold a body hcode w hw)

theorem root_of_effect (vs : TermValid s e) (t : State) (e' : RuntimeTerm)
    (heffect : ∀ q, t.rc.roots.count q + e.refs.count q = s.rc.roots.count q + e'.refs.count q)
    (hq : q ∈ e'.refs) : q ∈ t.rc.roots := by
  have he := heffect q
  have hp : 0 < e'.refs.count q := List.count_pos_iff.mpr hq
  have hl : e.refs.count q ≤ s.rc.roots.count q := vs.live q
  rw [← List.count_pos_iff]
  omega

theorem alloc_tail (s : State) (o : Object) : (alloc s o).frames.tail = s.frames.tail := by
  cases hf : s.frames <;> simp [alloc, hf]

theorem capture_step (vs : TermValid s (.lam a body))
    (hr : releaseList (Graph.Rc.alloc s.rc body.refs) body.refs = some rc) :
    StepEffect s (.lam a body) (captureState s a body rc) (.value (.closure s.rc.next a body)) := by
  let o : Object := ⟨[], some (a, body)⟩
  have live : ∀ p ∈ body.refs, p ∈ s.rc.roots := fun p hp => root_of_config vs hp
  have base : EffectValid (alloc s o) := alloc_effect vs.effect live (by
    intro a' body' hc w hw
    have he : a = a' ∧ body = body' := by simpa [o] using hc
    cases he.1
    cases he.2
    exact vs.closures w hw)
  have vrc : Graph.Rc.Valid (Graph.Rc.alloc s.rc body.refs) := Graph.Rc.alloc_valid vs.effect.rc live
  have final : EffectValid { alloc s o with rc := rc, objects := syncObjects (alloc s o) rc } :=
    sync_effect base (releaseList_valid vrc hr) (releaseList_subheap vrc hr)
  have hrel := releaseList_roots vrc hr
  refine ⟨reframe_effect final, ?_, ?_, ?_, ?_⟩
  · exact (reframe_tail { alloc s o with rc := rc, objects := syncObjects (alloc s o) rc }
      (.value (.closure s.rc.next a body))).trans (alloc_tail s o)
  · intro q
    have hrel := hrel q
    change rc.roots.count q + body.refs.count q = s.rc.roots.count q + [s.rc.next].count q
    simp only [Graph.Rc.alloc, List.count_cons, List.count_nil, Nat.zero_add] at hrel ⊢
    omega
  · intro w hw hl
    exact knownClosure_sync (knownClosure_alloc vs.effect o hw) hl
  · intro w hw
    simp only [RuntimeTerm.values, List.mem_singleton] at hw
    subst w
    apply knownClosure_sync (s := alloc s o) (rc := rc)
    · intro p a' body' h
      cases h
      exact ⟨o, by simp [alloc, o], by simp [o]⟩
    · apply live_of_roots (releaseList_valid vrc hr)
      intro p hp
      simp only [Value.refs, List.mem_singleton] at hp
      subst p
      apply root_of_effect vs (captureState s a body rc) (.value (.closure s.rc.next a body))
      · intro q
        have hrel := hrel q
        change rc.roots.count q + body.refs.count q = s.rc.roots.count q + [s.rc.next].count q
        simp only [Graph.Rc.alloc, List.count_cons, List.count_nil, Nat.zero_add] at hrel ⊢
        omega
      · simp [RuntimeTerm.refs, Value.refs]

theorem mem_flatten_replicate (xs : List Addr) (n : Nat) (hq : q ∈ (List.replicate n xs).flatten) :
    q ∈ xs := by
  induction n with
  | zero => contradiction
  | succ n ih =>
    simp only [List.replicate_succ, List.flatten_cons, List.mem_append] at hq
    exact hq.elim id (fun h => ih h)

theorem count_flatten_replicate (xs : List Addr) (n : Nat) (q : Addr) :
    (List.replicate n xs).flatten.count q = n * xs.count q := by
  induction n with
  | zero => simp
  | succ n ih =>
    simp [List.replicate_succ, List.count_append, ih, Nat.succ_mul, Nat.add_comm]

theorem beta_step (vs : TermValid s (.app (.value (.closure p a body)) (.value v)))
    (hr3 : Graph.Rc.releaseFuel
      (dupAll (dupAll s.rc body.refs) ((List.replicate (occurrences 0 body - 1) v.refs).flatten)).next
      (dupAll (dupAll s.rc body.refs) ((List.replicate (occurrences 0 body - 1) v.refs).flatten)) p = some rc3)
    (hr4 : (if occurrences 0 body = 0 then releaseValue rc3 v else some rc3) = some rc4) :
    StepEffect s (.app (.value (.closure p a body)) (.value v))
      (betaState s body v rc4) (replace 0 v body) := by
  obtain ⟨o, ho, hcode⟩ := vs.closures (.closure p a body) (by simp [RuntimeTerm.values]) p a body rfl
  have bodyLive : ∀ q ∈ body.refs, ∃ c, s.rc.cells q = some c := by
    intro q hq
    apply live_of_object_field vs.effect ho
    simp only [Object.refs, hcode, Option.map_some, Option.getD_some, List.mem_append]
    exact Or.inr hq
  have sourceLive : ∀ q ∈ [p] ++ v.refs, ∃ c, s.rc.cells q = some c := by
    intro q hq
    have hroot : q ∈ s.rc.roots := root_of_config vs (by simpa [RuntimeTerm.refs, Value.refs] using hq)
    obtain ⟨fields, hc⟩ := vs.effect.rc.graph.roots q hroot
    cases hcell : s.rc.cells q with
    | none => simp [Graph.Rc.erase, hcell] at hc
    | some c => exact ⟨c, rfl⟩
  have rc1valid : Graph.Rc.Valid (dupAll s.rc body.refs) := dupAll_valid vs.effect.rc bodyLive
  have rc2valid : Graph.Rc.Valid
      (dupAll (dupAll s.rc body.refs) ((List.replicate (occurrences 0 body - 1) v.refs).flatten)) := by
    apply dupAll_valid rc1valid
    intro q hq
    have hv : q ∈ v.refs := mem_flatten_replicate _ _ hq
    obtain ⟨c, hc⟩ := sourceLive q (by simp [hv])
    obtain ⟨d, hd, _⟩ := dupAll_cells s.rc body.refs hc
    exact ⟨d, hd⟩
  have closureLive2 : ∃ c, (dupAll (dupAll s.rc body.refs)
      ((List.replicate (occurrences 0 body - 1) v.refs).flatten)).cells p = some c := by
    obtain ⟨c, hc⟩ := sourceLive p (by simp)
    obtain ⟨d, hd, _⟩ := dupAll_cells s.rc body.refs hc
    obtain ⟨u, hu, _⟩ := dupAll_cells (dupAll s.rc body.refs) _ hd
    exact ⟨u, hu⟩
  have rc3valid : Graph.Rc.Valid rc3 := release_valid rc2valid hr3
  have rc4valid : Graph.Rc.Valid rc4 := by
    by_cases ho : occurrences 0 body = 0
    · exact releaseValue_valid rc3valid (by simpa [ho] using hr4)
    · simp only [ho, if_false] at hr4
      cases hr4
      exact rc3valid
  have sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc4) := by
    have s12 := (dupAll_subheap s.rc body.refs).trans
      (dupAll_subheap (dupAll s.rc body.refs) ((List.replicate (occurrences 0 body - 1) v.refs).flatten))
    have s123 := s12.trans (release_subheap rc2valid hr3)
    by_cases ho : occurrences 0 body = 0
    · exact s123.trans (releaseValue_subheap rc3valid (by simpa [ho] using hr4))
    · simp only [ho, if_false] at hr4
      cases hr4
      exact s123
  have rootEffect : ∀ q, rc4.roots.count q +
      (RuntimeTerm.app (.value (.closure p a body)) (.value v)).refs.count q =
      s.rc.roots.count q + (replace 0 v body).refs.count q := by
    intro q
    have h1 := dupAll_roots s.rc body.refs q
    have h2 := dupAll_roots (dupAll s.rc body.refs)
      ((List.replicate (occurrences 0 body - 1) v.refs).flatten) q
    have h3 := release_roots rc2valid hr3 q
    have hrep := replace_refs body 0 v q
    have hcopies := count_flatten_replicate v.refs (occurrences 0 body - 1) q
    rw [hcopies] at h2
    rw [hrep] at ⊢
    change rc4.roots.count q + ([p] ++ v.refs).count q = _
    by_cases ho : occurrences 0 body = 0
    · have h4 := releaseValue_roots rc3valid (by simpa [ho] using hr4) q
      simp only [List.count_append, List.count_cons, List.count_nil, Nat.zero_add,
        ho, Nat.pred_zero, Nat.zero_mul] at h1 h2 h3 h4 hrep hcopies ⊢
      omega
    · simp only [ho, if_false] at hr4
      cases hr4
      simp only [List.count_append, List.count_cons, List.count_nil, Nat.zero_add] at h1 h2 h3 ⊢
      have hpos : 0 < occurrences 0 body := Nat.pos_of_ne_zero ho
      have hpred : (occurrences 0 body).pred + 1 = occurrences 0 body :=
        Nat.succ_pred_eq_of_pos hpos
      have hmul : occurrences 0 body * v.refs.count q =
          (occurrences 0 body).pred * v.refs.count q + v.refs.count q := by
        calc
          occurrences 0 body * v.refs.count q =
              ((occurrences 0 body).pred + 1) * v.refs.count q :=
            congrArg (fun n => n * v.refs.count q) hpred.symm
          _ = (occurrences 0 body).pred * v.refs.count q + 1 * v.refs.count q :=
            Nat.add_mul _ _ _
          _ = (occurrences 0 body).pred * v.refs.count q + v.refs.count q := by simp
      simp only [Nat.pred_eq_sub_one] at h2 hmul hrep hcopies
      omega
  refine ⟨reframe_effect (sync_effect vs.effect rc4valid sub), reframe_tail _ _, rootEffect, ?_, ?_⟩
  · intro w hw hl
    exact knownClosure_sync hw hl
  · intro w hw
    apply knownClosure_sync (rc := rc4)
    · rcases replace_values body 0 v hw with hbody | ⟨rfl, harg⟩
      · exact vs.effect.objectClosures p o ho a body hcode w hbody
      · exact vs.closures w (by simp [RuntimeTerm.values])
    · apply live_of_roots rc4valid
      intro q hq
      apply root_of_effect vs (betaState s body v rc4) (replace 0 v body) rootEffect
      exact mem_refs_of_values (replace 0 v body) w q hw (by
        have hwrefs : w.refs = [q] := by cases w <;> simp_all [Value.addr, Value.refs]
        simp [hwrefs])

theorem literal_effect (v : EffectValid s) (value : Value) (ha : value.addr = none) :
    EffectValid (dup s value) := by
  have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
  cases hf : s.frames with
  | nil =>
    have hvf := v.frames
    simp [hf] at hvf
  | cons f outer =>
    exact ⟨by simpa [dup, ha] using v.rc, by simp [dup, hf],
      by simpa [dup, ha] using v.objects, by simpa [dup, ha] using v.complete,
      by simpa [dup] using v.borrows, by simpa [dup, ha] using v.objectClosures⟩

theorem dup_tail (s : State) (v : Value) : (dup s v).frames.tail = s.frames.tail := by
  cases hf : s.frames <;> simp [dup, hf]

theorem literal_step (vs : TermValid s e) (value : Value) (ha : value.addr = none)
    (he : e.refs = []) : StepEffect s e (literalState s value) (.value value) := by
  have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
  refine ⟨reframe_effect (literal_effect vs.effect value ha),
    (reframe_tail (dup s value) (.value value)).trans (dup_tail s value), ?_, ?_, ?_⟩
  · intro q
    simp [literalState, reframe, dup, ha, he, hv, RuntimeTerm.refs]
  · intro w hw hl
    exact hw
  · intro w hw
    simp only [RuntimeTerm.values, List.mem_singleton] at hw
    subst w
    intro p a body h
    cases value <;> simp_all [Value.addr]

theorem select_step (vs : TermValid s (.ite (.value (.bool b)) taken dropped))
    (hr : releaseList s.rc dropped.refs = some rc) :
    StepEffect s (.ite (.value (.bool b)) taken dropped) (selectState s taken rc) taken := by
  have vrc : Graph.Rc.Valid rc := releaseList_valid vs.effect.rc hr
  have sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc) :=
    releaseList_subheap vs.effect.rc hr
  have rootEffect : ∀ q, rc.roots.count q +
      (RuntimeTerm.ite (.value (.bool b)) taken dropped).refs.count q =
      s.rc.roots.count q + taken.refs.count q := by
    intro q
    have hrel := releaseList_roots vs.effect.rc hr q
    simp only [RuntimeTerm.refs, Value.refs, List.append_nil, List.count_append,
      List.count_nil, Nat.zero_add] at hrel ⊢
    omega
  refine ⟨reframe_effect (sync_effect vs.effect vrc sub), reframe_tail _ _, rootEffect, ?_, ?_⟩
  · intro w hw hl
    exact knownClosure_sync hw hl
  · intro w hw
    apply knownClosure_sync (vs.closures w (by
      simp only [RuntimeTerm.values, List.mem_append, List.mem_singleton]
      exact Or.inl (Or.inr hw)))
    apply live_of_roots vrc
    intro q hq
    apply root_of_effect vs (selectState s taken rc) taken rootEffect
    exact mem_refs_of_values taken w q hw (by
      have hwrefs : w.refs = [q] := by cases w <;> simp_all [Value.addr, Value.refs]
      simp [hwrefs])

theorem selectFalse_step (vs : TermValid s (.ite (.value (.bool b)) dropped taken))
    (hr : releaseList s.rc dropped.refs = some rc) :
    StepEffect s (.ite (.value (.bool b)) dropped taken) (selectState s taken rc) taken := by
  have vrc : Graph.Rc.Valid rc := releaseList_valid vs.effect.rc hr
  have sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc) :=
    releaseList_subheap vs.effect.rc hr
  have rootEffect : ∀ q, rc.roots.count q +
      (RuntimeTerm.ite (.value (.bool b)) dropped taken).refs.count q =
      s.rc.roots.count q + taken.refs.count q := by
    intro q
    have hrel := releaseList_roots vs.effect.rc hr q
    simp only [RuntimeTerm.refs, Value.refs, List.append_nil, List.count_append,
      List.count_nil, Nat.zero_add] at hrel ⊢
    omega
  refine ⟨reframe_effect (sync_effect vs.effect vrc sub), reframe_tail _ _, rootEffect, ?_, ?_⟩
  · intro w hw hl
    exact knownClosure_sync hw hl
  · intro w hw
    apply knownClosure_sync (vs.closures w (by
      simp only [RuntimeTerm.values, List.mem_append, List.mem_singleton]
      exact Or.inr hw))
    apply live_of_roots vrc
    intro q hq
    apply root_of_effect vs (selectState s taken rc) taken rootEffect
    exact mem_refs_of_values taken w q hw (by
      have hwrefs : w.refs = [q] := by cases w <;> simp_all [Value.addr, Value.refs]
      simp [hwrefs])

theorem migrate_context (vs : TermValid s e) (h : StepEffect s f t f') (e' : RuntimeTerm)
    (heffect : ∀ q, t.rc.roots.count q + e.refs.count q = s.rc.roots.count q + e'.refs.count q)
    (hv : w ∈ e'.values) (hk : KnownClosure s w) : KnownClosure t w := by
  apply h.migrate w hk
  apply live_of_roots h.effect.rc
  intro q hq
  apply root_of_effect vs _ _ heffect
  exact mem_refs_of_values e' w q hv (by
    have hwrefs : w.refs = [q] := by cases w <;> simp_all [Value.addr, Value.refs]
    simp [hwrefs])

theorem absurd_step (_vs : TermValid s (.absurd a body)) (h : StepEffect s body t body') :
    StepEffect s (.absurd a body) (reframe t (.absurd a body')) (.absurd a body') := by
  have rootEffect : ∀ q, (reframe t (.absurd a body')).rc.roots.count q +
      (RuntimeTerm.absurd a body).refs.count q = s.rc.roots.count q + body'.refs.count q := by
    simpa [reframe, RuntimeTerm.refs] using h.roots
  exact ⟨reframe_effect h.effect, (reframe_tail _ _).trans h.outer, rootEffect,
    h.migrate, by simpa [RuntimeTerm.values] using h.known⟩

theorem appLeft_step (vs : TermValid s (.app f x)) (h : StepEffect s f t f') :
    StepEffect s (.app f x) (reframe t (.app f' x)) (.app f' x) := by
  have rootEffect : ∀ q, (reframe t (.app f' x)).rc.roots.count q +
      (RuntimeTerm.app f x).refs.count q = s.rc.roots.count q + (RuntimeTerm.app f' x).refs.count q := by
    intro q
    have hr := h.roots q
    simp only [reframe, RuntimeTerm.refs, List.count_append] at hr ⊢
    omega
  refine ⟨reframe_effect h.effect, (reframe_tail _ _).trans h.outer, rootEffect, h.migrate, ?_⟩
  intro w hw
  simp only [RuntimeTerm.values, List.mem_append] at hw
  rcases hw with hf | hx
  · simpa [reframe] using h.known w hf
  · simpa [reframe] using (migrate_context (w := w) vs h _ rootEffect
      (by simp [RuntimeTerm.values, hx]) (vs.closures w (by simp [RuntimeTerm.values, hx])))

theorem appRight_step (vs : TermValid s (.app f x)) (h : StepEffect s x t x') :
    StepEffect s (.app f x) (reframe t (.app f x')) (.app f x') := by
  have rootEffect : ∀ q, (reframe t (.app f x')).rc.roots.count q +
      (RuntimeTerm.app f x).refs.count q = s.rc.roots.count q + (RuntimeTerm.app f x').refs.count q := by
    intro q
    have hr := h.roots q
    simp only [reframe, RuntimeTerm.refs, List.count_append] at hr ⊢
    omega
  refine ⟨reframe_effect h.effect, (reframe_tail _ _).trans h.outer, rootEffect, h.migrate, ?_⟩
  intro w hw
  simp only [RuntimeTerm.values, List.mem_append] at hw
  rcases hw with hf | hx
  · simpa [reframe] using (migrate_context (w := w) vs h _ rootEffect
      (by simp [RuntimeTerm.values, hf]) (vs.closures w (by simp [RuntimeTerm.values, hf])))
  · simpa [reframe] using h.known w hx

theorem iteCond_step (vs : TermValid s (.ite c t f)) (h : StepEffect s c u c') :
    StepEffect s (.ite c t f) (reframe u (.ite c' t f)) (.ite c' t f) := by
  have rootEffect : ∀ q, (reframe u (.ite c' t f)).rc.roots.count q +
      (RuntimeTerm.ite c t f).refs.count q = s.rc.roots.count q + (RuntimeTerm.ite c' t f).refs.count q := by
    intro q
    have hr := h.roots q
    simp only [reframe, RuntimeTerm.refs, List.count_append] at hr ⊢
    omega
  refine ⟨reframe_effect h.effect, (reframe_tail _ _).trans h.outer, rootEffect, h.migrate, ?_⟩
  intro w hw
  simp only [RuntimeTerm.values, List.mem_append] at hw
  rcases hw with (hc | ht) | hf
  · simpa [reframe] using h.known w hc
  · simpa [reframe] using (migrate_context (w := w) vs h _ rootEffect
      (by simp [RuntimeTerm.values, ht]) (vs.closures w (by simp [RuntimeTerm.values, ht])))
  · simpa [reframe] using (migrate_context (w := w) vs h _ rootEffect
      (by simp [RuntimeTerm.values, hf]) (vs.closures w (by simp [RuntimeTerm.values, hf])))

theorem executeStep_appRight (vs : TermValid s (.app f x))
    (ihx : ∀ {s t : State} {e' : RuntimeTerm}, TermValid s x →
      executeStep s x = some (t, e') → StepEffect s x t e')
    (h : Option.map (fun x => (reframe x.fst (f.app x.snd), f.app x.snd))
      (executeStep s x) = some (t, e')) :
    StepEffect s (.app f x) t e' := by
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
    exact appRight_step vs (ihx (term_appRight vs) (hx.trans hu.1))

theorem executeStep_appGeneral (vs : TermValid s (.app f x))
    (ihf : ∀ {s t : State} {e' : RuntimeTerm}, TermValid s f →
      executeStep s f = some (t, e') → StepEffect s f t e')
    (ihx : ∀ {s t : State} {e' : RuntimeTerm}, TermValid s x →
      executeStep s x = some (t, e') → StepEffect s x t e')
    (h : (match executeStep s f with
      | some (t, f) => some (reframe t (f.app x), f.app x)
      | none =>
        if sourceValue f = true then
          Option.map (fun x => (reframe x.fst (f.app x.snd), f.app x.snd)) (executeStep s x)
        else none) = some (t, e')) :
    StepEffect s (.app f x) t e' := by
  cases hf : executeStep s f with
  | some u =>
    simp only [hf] at h
    cases u
    cases h
    exact appLeft_step vs (ihf (term_appLeft vs) hf)
  | none =>
    simp only [hf] at h
    by_cases hv : sourceValue f = true
    · simp only [hv, if_true] at h
      exact executeStep_appRight vs ihx h
    · simp [hv] at h

theorem executeStep_iteGeneral (vs : TermValid s (.ite c thenBranch elseBranch))
    (ihc : ∀ {s t : State} {e' : RuntimeTerm}, TermValid s c →
      executeStep s c = some (t, e') → StepEffect s c t e')
    (h : Option.map
      (fun x => (reframe x.fst (x.snd.ite thenBranch elseBranch), x.snd.ite thenBranch elseBranch))
      (executeStep s c) = some (t, e')) :
    StepEffect s (.ite c thenBranch elseBranch) t e' := by
  cases hc : executeStep s c with
  | none => simp [hc] at h
  | some u =>
    simp only [hc] at h
    let ⟨u, hu⟩ := Option.map_eq_some.mp h
    have hs := congrArg Prod.fst hu.2
    have he := congrArg Prod.snd hu.2
    simp at hs he
    cases u
    subst t <;> subst e'
    exact iteCond_step vs (ihc (term_iteCond vs) (hc.trans hu.1))

theorem executeStep_absurdGeneral (vs : TermValid s (.absurd a body))
    (ih : ∀ {s t : State} {e' : RuntimeTerm}, TermValid s body →
      executeStep s body = some (t, e') → StepEffect s body t e')
    (h : Option.map (fun x => (reframe x.fst (.absurd a x.snd), .absurd a x.snd))
      (executeStep s body) = some (t, e')) :
    StepEffect s (.absurd a body) t e' := by
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
    exact absurd_step vs (ih (term_absurd vs) (hb.trans hu.1))

theorem executeStep_effect (vs : TermValid s e) (h : executeStep s e = some (t, e')) :
    StepEffect s e t e' := by
  induction e using runtime_ind generalizing s t e' with
  | value v | var n => contradiction
  | unit =>
    cases h
    exact literal_step vs .unit rfl rfl
  | bool b =>
    cases h
    exact literal_step vs (.bool b) rfl rfl
  | lam a body ih =>
    cases hr : releaseList (Graph.Rc.alloc s.rc body.refs) body.refs with
    | none => simp [executeStep, hr] at h
    | some rc =>
      unfold executeStep at h
      let ⟨a, ha⟩ := Option.map_eq_some.mp h
      have hs := congrArg Prod.fst ha.2
      have he := congrArg Prod.snd ha.2
      simp at hs he
      subst t <;> subst e'
      exact capture_step vs ha.1
  | app f x ihf ihx =>
    cases f with
    | value v =>
      cases v with
      | closure p a body =>
        cases x with
        | value v =>
          unfold executeStep at h
          cases hr3 : Graph.Rc.releaseFuel
              (dupAll (dupAll s.rc body.refs)
                ((List.replicate (occurrences 0 body - 1) v.refs).flatten)).next
              (dupAll (dupAll s.rc body.refs)
                ((List.replicate (occurrences 0 body - 1) v.refs).flatten)) p with
          | none => simp [hr3] at h
          | some rc3 =>
            by_cases ho : occurrences 0 body = 0
            · cases hr4 : releaseValue rc3 v with
              | none =>
                simp only [ho, Nat.zero_sub, Nat.pred_zero, List.replicate_zero, List.flatten_nil,
                  if_true] at hr3 h
                simp_all only [Option.some_bind, Option.map_none, Option.bind_none]
                change (none : Option (State × RuntimeTerm)) = some (t, e') at h
                cases h
              | some rc4 =>
                let ⟨a, ha⟩ := Option.bind_eq_some.mp h
                let ⟨b, hb⟩ := Option.map_eq_some.mp ha.2
                have hs := congrArg Prod.fst hb.2
                have he := congrArg Prod.snd hb.2
                simp at hs he
                subst t <;> subst e'
                exact beta_step vs ha.1 hb.1
            · let ⟨a, ha⟩ := Option.bind_eq_some.mp h
              let ⟨b, hb⟩ := Option.map_eq_some.mp ha.2
              have hs := congrArg Prod.fst hb.2
              have he := congrArg Prod.snd hb.2
              simp at hs he
              subst t <;> subst e'
              exact beta_step vs ha.1 hb.1
        | _ => exact executeStep_appRight vs ihx h
      | _ => exact executeStep_appGeneral vs ihf ihx h
    | _ => exact executeStep_appGeneral vs ihf ihx h
  | ite c thenBranch elseBranch ihc iht ihf =>
    cases c with
    | value v =>
      cases v with
      | bool b =>
        cases b with
        | true =>
          cases hr : releaseList s.rc elseBranch.refs with
          | none => simp [executeStep, hr] at h
          | some rc =>
            unfold executeStep at h
            let ⟨a, ha⟩ := Option.map_eq_some.mp h
            have hs := congrArg Prod.fst ha.2
            have he := congrArg Prod.snd ha.2
            simp at hs he
            subst t <;> subst e'
            exact select_step vs ha.1
        | false =>
          cases hr : releaseList s.rc thenBranch.refs with
          | none => simp [executeStep, hr] at h
          | some rc =>
            unfold executeStep at h
            let ⟨a, ha⟩ := Option.map_eq_some.mp h
            have hs := congrArg Prod.fst ha.2
            have he := congrArg Prod.snd ha.2
            simp at hs he
            subst t <;> subst e'
            exact selectFalse_step vs ha.1
      | _ => exact executeStep_iteGeneral vs ihc h
    | _ => exact executeStep_iteGeneral vs ihc h
  | absurd a body ih => exact executeStep_absurdGeneral vs ih h
  all_goals simp_all [executeStep]

theorem executeStep_config (vs : ConfigValid s e) (h : executeStep s e = some (t, e')) :
    ConfigValid (reframe t e') e' := by
  have step := executeStep_effect (config_term vs) h
  exact config_from_effect vs step.effect step.outer step.roots step.known

theorem runSteps_config (vs : ConfigValid s e) (fuel : Nat) :
    ConfigValid (runSteps s e fuel).1 (runSteps s e fuel).2 := by
  induction fuel generalizing s e with
  | zero => exact vs
  | succ fuel ih =>
    cases h : executeStep s e with
    | none => simpa [runSteps, h] using vs
    | some u =>
      cases u with
      | mk t e' =>
        have next : ConfigValid (reframe t e') e' := executeStep_config vs h
        simpa [runSteps, h] using ih next

end Fixed.Memory.Resource
