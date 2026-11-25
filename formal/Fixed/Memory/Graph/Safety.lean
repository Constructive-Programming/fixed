import Fixed.Memory.Graph.Invariants

namespace Fixed.Memory.Graph

/- Releasing a list consumes exactly its root occurrences. Keeping the consumed
   list in the statement handles duplicate fields without separate cases. -/
theorem release_count (h : Release s p t) (q : Addr) :
    t.roots.count q + [p].count q = s.roots.count q := by
  refine Release.rec
    (motive_1 := fun s p t _ => ∀ q, t.roots.count q + [p].count q = s.roots.count q)
    (motive_2 := fun s ps t _ => ∀ q, t.roots.count q + ps.count q = s.roots.count q)
    ?_ ?_ ?_ ?_ h q
  · intro p fs s hp _ _ q
    have hp' : 0 < s.roots.count p := List.count_pos_iff.mpr hp
    have hle : [p].count q ≤ s.roots.count q := by
      by_cases h : p = q
      · subst q; simpa using hp'
      · simp [h]
    simpa only [removeRoot, List.count_erase, List.count_cons, List.count_nil, Nat.zero_add] using
      Nat.sub_add_cancel hle
  · intro p fs s t hp _ _ tail ih q
    have ih := ih q
    simp only [unlink, List.count_append, List.count_erase] at ih
    have hp' : 0 < s.roots.count p := List.count_pos_iff.mpr hp
    have hle : [p].count q ≤ s.roots.count q := by
      by_cases h : p = q
      · subst q; simpa using hp'
      · simp [h]
    simp only [List.count_cons, List.count_nil, Nat.zero_add] at hle ⊢
    omega
  · intro s q; simp
  · intro s p u ps t head tail ihh iht q
    have ihh := ihh q
    have iht := iht q
    simp only [List.count_cons, List.count_nil, Nat.zero_add] at *
    omega

theorem releases_count (h : Releases s ps t) (q : Addr) :
    t.roots.count q + ps.count q = s.roots.count q := by
  refine Releases.rec
    (motive_1 := fun s p t _ => ∀ q, t.roots.count q + [p].count q = s.roots.count q)
    (motive_2 := fun s ps t _ => ∀ q, t.roots.count q + ps.count q = s.roots.count q)
    ?_ ?_ ?_ ?_ h q
  · intro p fs s hp _ _ q
    have hp' : 0 < s.roots.count p := List.count_pos_iff.mpr hp
    have hle : [p].count q ≤ s.roots.count q := by
      by_cases h : p = q
      · subst q; simpa using hp'
      · simp [h]
    simpa only [removeRoot, List.count_erase, List.count_cons, List.count_nil, Nat.zero_add] using
      Nat.sub_add_cancel hle
  · intro p fs s t hp _ _ tail ih q
    have ih := ih q
    simp only [unlink, List.count_append, List.count_erase] at ih
    have hp' : 0 < s.roots.count p := List.count_pos_iff.mpr hp
    have hle : [p].count q ≤ s.roots.count q := by
      by_cases h : p = q
      · subst q; simpa using hp'
      · simp [h]
    simp only [List.count_cons, List.count_nil, Nat.zero_add] at hle ⊢
    omega
  · intro s q; simp
  · intro s p u ps t head tail ihh iht q
    have ihh := ihh q
    have iht := iht q
    simp only [List.count_cons, List.count_nil, Nat.zero_add] at *
    omega

theorem release_roots (h : Release s p t) :
    t.roots.count q = (s.roots.erase p).count q := by
  have hc := release_count h q
  simp only [List.count_cons, List.count_nil, Nat.zero_add] at hc
  simp only [List.count_erase]
  omega

/-- A release consumes one root occurrence and leaves all others in place. -/
theorem release_root_mem (h : Release s p t) (hq : q ∈ s.roots.erase p) : q ∈ t.roots := by
  rw [← List.count_pos_iff, release_roots h]
  exact List.count_pos_iff.mpr hq

theorem release_valid (v : Valid s) (h : Release s p t) : Valid t := by
  refine Release.rec
    (motive_1 := fun s _ t _ => Valid s → Valid t)
    (motive_2 := fun s _ t _ => Valid s → Valid t) ?_ ?_ ?_ ?_ h v
  · intro _ _ _ _ _ support v; exact removeRoot_valid v support
  · intro _ _ _ _ _ hc last _ ih v; exact ih (unlink_valid v hc last)
  · intro _ v; exact v
  · intro _ _ _ _ _ _ _ ihh iht v; exact iht (ihh v)

theorem releases_valid (v : Valid s) (h : Releases s ps t) : Valid t := by
  refine Releases.rec
    (motive_1 := fun s _ t _ => Valid s → Valid t)
    (motive_2 := fun s _ t _ => Valid s → Valid t) ?_ ?_ ?_ ?_ h v
  · intro _ _ _ _ _ support v; exact removeRoot_valid v support
  · intro _ _ _ _ _ hc last _ ih v; exact ih (unlink_valid v hc last)
  · intro _ v; exact v
  · intro _ _ _ _ _ _ _ ihh iht v; exact iht (ihh v)

theorem release_subheap (h : Release s p t) : Subheap s t := by
  refine Release.rec
    (motive_1 := fun s _ t _ => Subheap s t)
    (motive_2 := fun s _ t _ => Subheap s t) ?_ ?_ ?_ ?_ h
  · intro _ _ _ _ _ _; exact removeRoot_subheap
  · intro _ _ _ _ _ _ _ _ ih; exact unlink_subheap.trans ih
  · intro _; exact .refl _
  · intro _ _ _ _ _ _ _ ihh iht; exact ihh.trans iht

theorem releases_subheap (h : Releases s ps t) : Subheap s t := by
  refine Releases.rec
    (motive_1 := fun s _ t _ => Subheap s t)
    (motive_2 := fun s _ t _ => Subheap s t) ?_ ?_ ?_ ?_ h
  · intro _ _ _ _ _ _; exact removeRoot_subheap
  · intro _ _ _ _ _ _ _ _ ih; exact unlink_subheap.trans ih
  · intro _; exact .refl _
  · intro _ _ _ _ _ _ _ ihh iht; exact ihh.trans iht

theorem releasesFuel_sound
    (sound : ∀ {s p t}, Valid s → releaseFuel fuel s p = some t → Release s p t)
    (v : Valid s) (h : ps.foldlM (fun u q => releaseFuel fuel u q) s = some t) :
    Releases s ps t := by
  induction ps generalizing s with
  | nil => cases h; exact .nil
  | cons p ps ih =>
    rw [List.foldlM_cons] at h
    cases hp : releaseFuel fuel s p with
    | none => simp [hp] at h
    | some u =>
      simp only [hp, Option.bind_some] at h
      have head := sound v hp
      exact .cons head (ih (release_valid v head) h)

theorem releaseFuel_sound (v : Valid s) (h : releaseFuel fuel s p = some t) : Release s p t := by
  induction fuel generalizing s p t with
  | zero => simp [releaseFuel] at h
  | succ fuel ih =>
    simp only [releaseFuel] at h
    split at h
    next hp =>
      split at h
      next => contradiction
      next fs hc =>
        split at h
        next hi => cases h; exact .shared hp hc ((hasIncoming_iff (s := removeRoot s p) (p := p) (fun q fs hq => v.fresh q fs hq)).mp hi)
        next hi =>
          have last : ¬ Incoming (removeRoot s p) p := fun hi' => by
            have := (hasIncoming_iff (s := removeRoot s p) (p := p)
              (fun q fs hq => v.fresh q fs hq)).mpr hi'
            simp [hi] at this
          exact .last hp hc last (releasesFuel_sound (@ih) (unlink_valid v hc last) h)
    next => contradiction

theorem releasesFuel_total {fuel : Nat} {ps : List Addr}
    (total : ∀ {s p}, Valid s → p < fuel → p ∈ s.roots →
      ∃ t, releaseFuel fuel s p = some t)
    (v : Valid s) (bounds : ∀ p ∈ ps, p < fuel)
    (counts : ∀ q, ps.count q ≤ s.roots.count q) :
    ∃ t, ps.foldlM (fun u q => releaseFuel fuel u q) s = some t := by
  induction ps generalizing s with
  | nil => exact ⟨s, rfl⟩
  | cons p ps ih =>
    have hc := counts p
    simp only [List.count_cons, List.count_nil, Nat.zero_add, beq_self_eq_true,
      if_true] at hc
    have hp : p ∈ s.roots := List.count_pos_iff.mp (by omega)
    obtain ⟨u, hu⟩ := total v (bounds p (by simp)) hp
    have head := releaseFuel_sound v hu
    have tailCounts : ∀ q, ps.count q ≤ u.roots.count q := by
      intro q
      have hc := counts q
      have hr := release_count head q
      simp only [List.count_cons, List.count_nil, Nat.zero_add] at hc hr
      omega
    obtain ⟨t, ht⟩ := ih (release_valid v head)
      (fun q hq => bounds q (by simp [hq])) tailCounts
    exact ⟨t, by rw [List.foldlM_cons, hu]; exact ht⟩

theorem releaseFuel_total {fuel : Nat} (v : Valid s) (bound : p < fuel) (hp : p ∈ s.roots) :
    ∃ t, releaseFuel fuel s p = some t := by
  induction fuel generalizing s p with
  | zero => omega
  | succ fuel ih =>
    obtain ⟨fs, hc⟩ := v.roots p hp
    by_cases hi : Incoming (removeRoot s p) p
    · have support : hasIncoming (removeRoot s p) p = true :=
        (hasIncoming_iff (s := removeRoot s p) (p := p)
          (fun q fs hq => v.fresh q fs hq)).mpr hi
      exact ⟨removeRoot s p, by simp [releaseFuel, hp, hc, support]⟩
    · have unsupported : hasIncoming (removeRoot s p) p = false := by
        cases h : hasIncoming (removeRoot s p) p with
        | false => rfl
        | true => exact False.elim (hi ((hasIncoming_iff (s := removeRoot s p) (p := p)
          (fun q fs hq => v.fresh q fs hq)).mp h))
      have vu := unlink_valid v hc hi
      have bounds : ∀ q ∈ fs, q < fuel := by
        intro q hq
        have hlt := (v.edges p fs hc q hq).1
        exact Nat.lt_of_lt_of_le hlt (Nat.lt_succ_iff.mp bound)
      have counts : ∀ q, fs.count q ≤ (unlink s p fs).roots.count q := by
        intro q
        simp only [unlink, List.count_append]
        omega
      obtain ⟨t, ht⟩ := releasesFuel_total (@ih) vu bounds counts
      exact ⟨t, by simp [releaseFuel, hp, hc, unsupported, ht]⟩

/-- The depth budget used by `execute` suffices for every valid rooted DAG. -/
theorem release_total (v : Valid s) (hp : p ∈ s.roots) :
    ∃ t, releaseFuel s.next s p = some t := by
  obtain ⟨fs, hc⟩ := v.roots p hp
  exact releaseFuel_total v (v.fresh p fs hc) hp

theorem release_deterministic (h : Release s p t) : ∀ {u}, Release s p u → t = u := by
  refine Release.rec
    (motive_1 := fun s p t _ => ∀ {u}, Release s p u → t = u)
    (motive_2 := fun s ps t _ => ∀ {u}, Releases s ps u → t = u) ?_ ?_ ?_ ?_ h
  · intro p fs s hp hc support u h
    cases h with
    | shared => rfl
    | last _ _ last _ => exact False.elim (last support)
  · intro p fs s t hp hc last tail ih u h
    cases h with
    | shared _ _ support => exact False.elim (last support)
    | last _ hc' _ tail' =>
      cases hc.symm.trans hc'
      exact ih tail'
  · intro s u h; cases h; rfl
  · intro s p u ps t head tail ihh iht v h
    cases h with
    | cons head' tail' =>
      have he := ihh head'
      subst u
      exact iht tail'

theorem releases_deterministic (h : Releases s ps t) : ∀ {u}, Releases s ps u → t = u := by
  refine Releases.rec
    (motive_1 := fun s p t _ => ∀ {u}, Release s p u → t = u)
    (motive_2 := fun s ps t _ => ∀ {u}, Releases s ps u → t = u) ?_ ?_ ?_ ?_ h
  · intro p fs s hp hc support u h
    cases h with
    | shared => rfl
    | last _ _ last _ => exact False.elim (last support)
  · intro p fs s t hp hc last tail ih u h
    cases h with
    | shared _ _ support => exact False.elim (last support)
    | last _ hc' _ tail' =>
      cases hc.symm.trans hc'
      exact ih tail'
  · intro s u h; cases h; rfl
  · intro s p u ps t head tail ihh iht v h
    cases h with
    | cons head' tail' =>
      have he := ihh head'
      subst u
      exact iht tail'

theorem accepted_preserves (v : Valid s) (h : execute s op = some t) : Valid t := by
  cases op with
  | alloc fs =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact alloc_valid v hc.2
    next => contradiction
  | dup p =>
    simp only [execute] at h
    split at h
    next hp => cases h; exact dup_valid v hp
    next => contradiction
  | drop p =>
    simp only [execute] at h
    split at h
    next hp => exact release_valid v (releaseFuel_sound v h)
    next => contradiction

theorem run_preserves (v : Valid s) (h : run s ops = some t) : Valid t := by
  induction ops generalizing s with
  | nil => cases h; exact v
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      exact ih (accepted_preserves v he) h

theorem execute_drop_iff (v : Valid s) : execute s (.drop p) = some t ↔ Release s p t := by
  constructor
  · intro h
    simp only [execute] at h
    split at h
    next _ => exact releaseFuel_sound v h
    next => contradiction
  · intro h
    have hp : p ∈ s.roots := by cases h <;> assumption
    obtain ⟨u, hu⟩ := release_total v hp
    have hr := releaseFuel_sound v hu
    have he := release_deterministic hr h
    subst u
    simp [execute, hp, hu]

theorem accepted_no_dangling (h : run initial ops = some s) (hp : p ∈ s.roots) :
    ∃ fs, s.cells p = some fs :=
  (run_preserves initial_valid h).roots p hp

end Fixed.Memory.Graph
