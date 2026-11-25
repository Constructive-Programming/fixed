import Fixed.Memory.Frames

namespace Fixed.Memory

/-- Fail at the first rejected operation. Allocation returns its identity through
    the pre-operation next counter; this API is an abstract command interpreter. -/
def run (s : State) : List Op → Option State
  | [] => some s
  | op :: ops => (execute s op).bind (fun t => run t ops)

theorem run_preserves (v : Valid s) (h : run s ops = some t) : Valid t := by
  induction ops generalizing s with
  | nil => cases h; exact v
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      exact ih (accepted_preserves v he) h

/-- Roots represent all pointer-bearing external holders, including abstract
    results and captures. This relation follows zero or more object fields. -/
inductive Reachable (s : State) : Addr → Prop where
  | root : r ∈ s.roots → Reachable s r.target
  | field : Reachable s p → s.cells p = some c →
      q ∈ c.fields.getD [] → Reachable s q

theorem ref_exists (h : RefSafe s f p) : ∃ c, s.cells p = some c ∧ c.frame ≤ f := by
  cases hp : s.cells p with
  | none => simp [RefSafe, hp] at h
  | some c => exact ⟨c, rfl, by simpa [RefSafe, hp] using h⟩

/-- Every pointer reachable from a modeled root designates a live object. -/
theorem no_dangling (v : Valid s) (h : Reachable s p) :
    ∃ c, s.cells p = some c ∧ c.frame ≤ s.depth := by
  induction h with
  | root hr =>
    obtain ⟨c, hc, _⟩ := ref_exists (v.roots _ hr).2
    exact ⟨c, hc, v.live _ _ hc⟩
  | field _ hp hq _ =>
    obtain ⟨c, hc, _⟩ := ref_exists (v.edges _ _ hp _ hq)
    exact ⟨c, hc, v.live _ _ hc⟩

/-- Exact reclamation: outer cells are unchanged, inner-frame cells disappear. -/
theorem reset_lookup_iff :
    (reset s).cells p = some c ↔ s.cells p = some c ∧ c.frame < s.depth := by
  simp [reset, Option.filter_eq_some]

theorem reset_removes (hp : s.cells p = some c) (hf : c.frame = s.depth) :
    (reset s).cells p = none := by
  simp [reset, hp, hf, Option.filter]

/-- The concrete first memory-safety milestone. No pointer reachable from a
    surviving root designates a cell reclaimed by the reset. -/
theorem reset_no_dangling (v : Valid s) (hd : 0 < s.depth)
    (h : Reachable (reset s) p) :
    ∃ c, (reset s).cells p = some c ∧ s.cells p = some c ∧ c.frame < s.depth := by
  obtain ⟨c, hc, _⟩ := no_dangling (reset_valid v hd) h
  exact ⟨c, hc, (reset_lookup_iff.mp hc)⟩

/-- Safety for every successfully checked finite program from the empty state. -/
theorem accepted_no_dangling (h : run initial ops = some s) (hr : Reachable s p) :
    ∃ c, s.cells p = some c ∧ c.frame ≤ s.depth :=
  no_dangling (run_preserves initial_valid h) hr

theorem step_next_mono (h : Step s op t) : s.next ≤ t.next := by
  cases h <;> simp [enter, install, addRoot, reset]
  exact Nat.le_max_left _ _

theorem run_next_mono (h : run s ops = some t) : s.next ≤ t.next := by
  induction ops generalizing s with
  | nil => cases h; exact Nat.le_refl _
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      exact Nat.le_trans (step_next_mono (execute_sound he)) (ih h)

/-- Once a logical address below next is absent, no accepted operation can
    recreate it. This distinguishes frame-depth reuse from address reuse. -/
theorem absent_stays_absent (hn : p < s.next) (hp : s.cells p = none)
    (h : Step s op t) : t.cells p = none := by
  cases h with
  | enter => exact hp
  | reserve _ _ =>
    have hne : p ≠ s.next := Nat.ne_of_lt hn
    simpa [install, hne] using hp
  | init hi _ =>
    rename_i fields f q hs
    have hne : p ≠ q := by intro he; subst p; rw [hp] at hi; contradiction
    simpa [install, hne] using hp
  | root _ _ => exact hp
  | leave _ => simp [reset, hp, Option.filter]

theorem run_absent_stays_absent (hn : p < s.next) (hp : s.cells p = none)
    (h : run s ops = some t) : t.cells p = none := by
  induction ops generalizing s with
  | nil => cases h; exact hp
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      have step := execute_sound he
      exact ih (Nat.lt_of_lt_of_le hn (step_next_mono step))
        (absent_stays_absent hn hp step) h

end Fixed.Memory
