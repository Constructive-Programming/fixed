import Fixed.Memory.Resource.Core

namespace Fixed.Memory.Resource

theorem runtime_ind (motive : RuntimeTerm → Prop)
    (value : ∀ v, motive (.value v)) (var : ∀ n, motive (.var n))
    (unit : motive .unit) (bool : ∀ b, motive (.bool b))
    (lam : ∀ a body, motive body → motive (.lam a body))
    (app : ∀ f x, motive f → motive x → motive (.app f x))
    (ite : ∀ c t f, motive c → motive t → motive f → motive (.ite c t f))
    (absurd : ∀ a body, motive body → motive (.absurd a body)) (e : RuntimeTerm) : motive e := by
  refine RuntimeTerm.rec (motive_1 := fun _ => True) (motive_2 := motive)
    (by trivial) (by intro; trivial) (by intro; trivial) (by intros; trivial)
    (by intro v _; exact value v) (by intro n; exact var n)
    unit bool lam app ite absurd e

theorem replace_refs (e : RuntimeTerm) (n : Nat) (v : Value) (q : Addr) :
    (replace n v e).refs.count q = e.refs.count q + occurrences n e * v.refs.count q := by
  induction e using runtime_ind generalizing n with
  | value w => simp [replace, occurrences, RuntimeTerm.refs]
  | var m =>
    by_cases h : n = m <;> simp [replace, occurrences, RuntimeTerm.refs, h]
  | unit | bool b => simp [replace, occurrences, RuntimeTerm.refs]
  | lam a body ih => simpa [replace, occurrences, RuntimeTerm.refs] using ih (n + 1)
  | app f x ihf ihx =>
    simp only [replace, occurrences, RuntimeTerm.refs, List.count_append, ihf, ihx, Nat.add_mul]
    omega
  | ite c t f ihc iht ihf =>
    simp only [replace, occurrences, RuntimeTerm.refs, List.count_append, ihc, iht, ihf,
      Nat.add_mul]
    omega
  | absurd a body ih => simpa [replace, occurrences, RuntimeTerm.refs] using ih n

theorem dupAll_roots (rc : Graph.Rc.State) (ps : List Addr) (q : Addr) :
    (dupAll rc ps).roots.count q = rc.roots.count q + ps.count q := by
  induction ps generalizing rc with
  | nil => rfl
  | cons p ps ih =>
    simp only [dupAll, List.foldl_cons]
    change (dupAll (Graph.Rc.dup rc p) ps).roots.count q = _
    rw [ih]
    simp [Graph.Rc.dup, List.count_cons, Nat.add_assoc]
    omega

theorem dupAll_cells (rc : Graph.Rc.State) (ps : List Addr) (hc : rc.cells q = some c) :
    ∃ d, (dupAll rc ps).cells q = some d ∧ d.fields = c.fields := by
  induction ps generalizing rc c with
  | nil => exact ⟨c, hc, rfl⟩
  | cons p ps ih =>
    have hd : ∃ d, (Graph.Rc.dup rc p).cells q = some d ∧ d.fields = c.fields := by
      by_cases h : q = p
      · subst q
        exact ⟨{ c with count := c.count + 1 }, by simp [Graph.Rc.dup, hc], rfl⟩
      · exact ⟨c, by simp [Graph.Rc.dup, h, hc], rfl⟩
    obtain ⟨d, hd, hdf⟩ := hd
    obtain ⟨u, hu, huf⟩ := ih (Graph.Rc.dup rc p) hd
    exact ⟨u, hu, huf.trans hdf⟩

theorem dupAll_valid (v : Graph.Rc.Valid rc)
    (live : ∀ p ∈ ps, ∃ c, rc.cells p = some c) : Graph.Rc.Valid (dupAll rc ps) := by
  induction ps generalizing rc with
  | nil => exact v
  | cons p ps ih =>
    obtain ⟨c, hc⟩ := live p (by simp)
    apply ih (Graph.Rc.dup_live_valid v hc)
    intro q hq
    obtain ⟨d, hd, _⟩ := dupAll_cells rc [p] (live q (by simp [hq])).choose_spec
    exact ⟨d, hd⟩

theorem releaseValue_valid (v : Graph.Rc.Valid rc) (h : releaseValue rc value = some t) :
    Graph.Rc.Valid t := by
  cases ha : value.addr with
  | none =>
    simp only [releaseValue, ha] at h
    cases h; exact v
  | some p =>
    have hr : Graph.Rc.releaseFuel rc.next rc p = some t := by
      simpa [releaseValue, ha] using h
    exact release_valid v hr

theorem releaseValue_roots (v : Graph.Rc.Valid rc) (h : releaseValue rc value = some t)
    (q : Addr) : t.roots.count q + value.refs.count q = rc.roots.count q := by
  cases ha : value.addr with
  | none =>
    have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
    simp only [releaseValue, ha] at h
    cases h
    simp [hv]
  | some p =>
    have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
    have hr : Graph.Rc.releaseFuel rc.next rc p = some t := by
      simpa [releaseValue, ha] using h
    simpa [hv] using release_roots v hr q

theorem releaseValue_subheap (v : Graph.Rc.Valid rc) (h : releaseValue rc value = some t) :
    Graph.Subheap (Graph.Rc.erase rc) (Graph.Rc.erase t) := by
  cases ha : value.addr with
  | none =>
    simp only [releaseValue, ha] at h
    cases h; exact .refl _
  | some p =>
    have hr : Graph.Rc.releaseFuel rc.next rc p = some t := by
      simpa [releaseValue, ha] using h
    exact release_subheap v hr

theorem values_frameRefs (e : RuntimeTerm) :
    Frame.refs ⟨e.values.map some⟩ = e.refs := by
  induction e using runtime_ind with
  | value v => simp [RuntimeTerm.values, RuntimeTerm.refs, Frame.refs, Slot.refs]
  | var n | unit | bool b => rfl
  | lam a body ih => simpa [RuntimeTerm.values, RuntimeTerm.refs, Frame.refs] using ih
  | app f x ihf ihx =>
    simp only [RuntimeTerm.values, RuntimeTerm.refs, List.map_append, Frame.refs,
      List.flatMap_append] at ihf ihx ⊢
    rw [ihf, ihx]
  | ite c t f ihc iht ihf =>
    simp only [RuntimeTerm.values, RuntimeTerm.refs, List.map_append, Frame.refs,
      List.flatMap_append] at ihc iht ihf ⊢
    rw [ihc, iht, ihf]
  | absurd a body ih => simpa [RuntimeTerm.values, RuntimeTerm.refs, Frame.refs] using ih

theorem config_resource (v : ConfigValid s e) : Valid s := by
  exact ⟨v.effect.rc, v.effect.frames, v.roots, v.effect.objects, v.effect.complete,
    by intro b hb; simp [v.effect.borrows] at hb⟩

theorem mem_refs_of_values (e : RuntimeTerm) (w : Value) (q : Addr)
    (hv : w ∈ e.values) (hq : q ∈ w.refs) : q ∈ e.refs := by
  induction e using runtime_ind with
  | value v =>
    simp only [RuntimeTerm.values, List.mem_singleton] at hv
    subst v
    exact hq
  | var n | unit | bool b => contradiction
  | lam a body ih =>
    exact ih (by simpa [RuntimeTerm.values] using hv)
  | app f x ihf ihx =>
    simp only [RuntimeTerm.values, List.mem_append] at hv
    simp only [RuntimeTerm.refs, List.mem_append]
    exact hv.elim (fun h => Or.inl (ihf h)) (fun h => Or.inr (ihx h))
  | ite c t f ihc iht ihf =>
    simp only [RuntimeTerm.values, List.mem_append] at hv
    simp only [RuntimeTerm.refs, List.mem_append]
    rcases hv with (hc | ht) | hf
    · exact Or.inl (Or.inl (ihc hc))
    · exact Or.inl (Or.inr (iht ht))
    · exact Or.inr (ihf hf)
  | absurd a body ih => exact ih (by simpa [RuntimeTerm.values] using hv)

def LiveValue (rc : Graph.Rc.State) (v : Value) : Prop :=
  ∀ p, v.addr = some p → ∃ c, rc.cells p = some c

theorem knownClosure_sync (hv : KnownClosure s v) (hl : LiveValue rc v) :
    KnownClosure { s with objects := syncObjects s rc } v := by
  intro p a body h
  obtain ⟨o, ho, hc⟩ := hv p a body h
  obtain ⟨c, hcell⟩ := hl p (by simp [h, Value.addr])
  exact ⟨o, by simp [syncObjects, hcell, ho], hc⟩

theorem config_term (v : ConfigValid s e) : TermValid s e := by
  refine ⟨v.effect, ?_, v.closures⟩
  intro q
  obtain ⟨f, outer, hf, slots⟩ := v.current
  have hv : f.refs = e.refs := by simpa [Frame.refs, slots] using values_frameRefs e
  have hr := v.roots q
  rw [hf, frameRoots_cons, hv, List.count_append] at hr
  omega

theorem term_appLeft (v : TermValid s (.app f x)) : TermValid s f :=
  ⟨v.effect, by
      intro q
      have h := v.live q
      simp only [RuntimeTerm.refs, List.count_append] at h ⊢
      omega,
    fun w hw => v.closures w (by simp [RuntimeTerm.values, hw])⟩

theorem term_appRight (v : TermValid s (.app f x)) : TermValid s x :=
  ⟨v.effect, by
      intro q
      have h := v.live q
      simp only [RuntimeTerm.refs, List.count_append] at h ⊢
      omega,
    fun w hw => v.closures w (by simp [RuntimeTerm.values, hw])⟩

theorem term_iteCond (v : TermValid s (.ite c t f)) : TermValid s c :=
  ⟨v.effect, by
      intro q
      have h := v.live q
      simp only [RuntimeTerm.refs, List.count_append] at h ⊢
      omega,
    fun w hw => v.closures w (by simp [RuntimeTerm.values, hw])⟩

theorem term_iteThen (v : TermValid s (.ite c t f)) : TermValid s t :=
  ⟨v.effect, by
      intro q
      have h := v.live q
      simp only [RuntimeTerm.refs, List.count_append] at h ⊢
      omega,
    fun w hw => v.closures w (by simp [RuntimeTerm.values, hw])⟩

theorem term_iteElse (v : TermValid s (.ite c t f)) : TermValid s f :=
  ⟨v.effect, by
      intro q
      have h := v.live q
      simp only [RuntimeTerm.refs, List.count_append] at h ⊢
      omega,
    fun w hw => v.closures w (by simp [RuntimeTerm.values, hw])⟩

theorem term_absurd (v : TermValid s (.absurd a body)) : TermValid s body :=
  ⟨v.effect, v.live, v.closures⟩

theorem initial_effect : EffectValid initial := by
  exact ⟨Graph.Rc.initial_valid, by simp [initial, Frame.empty],
    by intro p o h; contradiction, by intro p c h; contradiction, rfl,
    by intro p o h; contradiction⟩

theorem ofCore_values (e : Fixed.Term) : (ofCore e).values = [] := by
  induction e <;> simp [ofCore, RuntimeTerm.values, *]

theorem initial_config (e : Fixed.Term) : ConfigValid initial (ofCore e) := by
  exact ⟨initial_effect, by intro p; rfl, ⟨Frame.empty, [], rfl, by simp [Frame.empty, ofCore_values]⟩,
    by simp [ofCore_values]⟩

theorem reframe_effect (v : EffectValid s) : EffectValid (reframe s e) := by
  cases hf : s.frames with
  | nil =>
    have hv := v.frames
    simp [hf] at hv
  | cons f outer => exact ⟨v.rc, by simp [reframe, replaceCurrent, hf], v.objects, v.complete,
    v.borrows, v.objectClosures⟩

theorem sync_effect (v : EffectValid s) (vrc : Graph.Rc.Valid rc)
    (sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc)) :
    EffectValid { s with rc := rc, objects := syncObjects s rc } := by
  refine ⟨vrc, v.frames, (sync_objects_generic v.objects v.complete sub).1,
    (sync_objects_generic v.objects v.complete sub).2, v.borrows, ?_⟩
  intro p o ho a body hcode w hw
  have hold : s.objects p = some o := by
    by_cases hl : (rc.cells p).isSome
    · simpa [syncObjects, hl] using ho
    · simp [syncObjects, hl] at ho
  apply knownClosure_sync (v.objectClosures p o hold a body hcode w hw)
  intro q ha
  have hwrefs : w.refs = [q] := by cases w <;> simp_all [Value.addr, Value.refs]
  have hqbody : q ∈ body.refs := mem_refs_of_values body w q hw (by simp [hwrefs])
  have hqrefs : q ∈ o.refs := by
    simp only [Object.refs, hcode, Option.map_some, Option.getD_some, List.mem_append]
    exact Or.inr hqbody
  obtain ⟨c, hcell, hfields⟩ := (sync_objects_generic v.objects v.complete sub).1 p o ho
  obtain ⟨hlt, children, hc⟩ := vrc.graph.edges p o.refs (by simp [Graph.Rc.erase, hcell, hfields]) q hqrefs
  cases hq : rc.cells q with
  | none => simp [Graph.Rc.erase, hq] at hc
  | some d => exact ⟨d, rfl⟩

theorem reframe_tail (s : State) (e : RuntimeTerm) : (reframe s e).frames.tail = s.frames.tail := by
  cases hf : s.frames <;> simp [reframe, replaceCurrent, hf]

theorem config_from_effect (vs : ConfigValid s e) (vt : EffectValid t)
    (hsame : t.frames.tail = s.frames.tail)
    (heffect : ∀ q, t.rc.roots.count q + e.refs.count q = s.rc.roots.count q + e'.refs.count q)
    (known : ∀ w ∈ e'.values, KnownClosure t w) : ConfigValid (reframe t e') e' := by
  obtain ⟨f, outer, hf, slots⟩ := vs.current
  have ht := vt.frames
  cases htframes : t.frames with
  | nil => simp [htframes] at ht
  | cons g outer' =>
    have hout : outer' = outer := by
      have hs := hsame
      simp [htframes, hf] at hs
      exact hs
    subst outer'
    refine ⟨reframe_effect vt, ?_, ?_, known⟩
    · intro q
      have hr := vs.roots q
      have he := heffect q
      have hv : f.refs = e.refs := by simpa [Frame.refs, slots] using values_frameRefs e
      rw [hf, frameRoots_cons, hv, List.count_append] at hr
      simp only [reframe, replaceCurrent, htframes, frameRoots_cons, values_frameRefs,
        List.count_append]
      omega
    · exact ⟨⟨e'.values.map some⟩, outer, by simp [reframe, replaceCurrent, htframes], rfl⟩

end Fixed.Memory.Resource
