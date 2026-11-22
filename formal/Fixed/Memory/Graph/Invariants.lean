import Fixed.Memory.Graph.Model

namespace Fixed.Memory.Graph

/-- Cleanup only removes objects; surviving objects retain their exact fields. -/
structure Subheap (s t : State) : Prop where
  next : t.next = s.next
  cells : ∀ p fields, t.cells p = some fields → s.cells p = some fields

theorem Subheap.refl (s : State) : Subheap s s := ⟨rfl, fun _ _ h => h⟩

theorem Subheap.trans (h : Subheap s t) (g : Subheap t u) : Subheap s u :=
  ⟨g.next.trans h.next, fun p fs hp => h.cells p fs (g.cells p fs hp)⟩

theorem removeRoot_subheap : Subheap s (removeRoot s p) := ⟨rfl, fun _ _ h => h⟩

theorem unlink_lookup : (unlink s p fs).cells q = some children ↔
    q ≠ p ∧ s.cells q = some children := by
  simp [unlink]

theorem unlink_subheap : Subheap s (unlink s p fs) :=
  ⟨rfl, fun _ _ h => (unlink_lookup.mp h).2⟩

theorem alloc_valid (v : Valid s) (hf : ∀ q ∈ fs, q ∈ s.roots) : Valid (alloc s fs) := by
  have old : ∀ p fields, s.cells p = some fields → (alloc s fs).cells p = some fields := by
    intro p fields hp
    simp [alloc, Nat.ne_of_lt (v.fresh p fields hp), hp]
  have lookup : ∀ p fields, (alloc s fs).cells p = some fields →
      (p = s.next ∧ fields = fs) ∨ (p ≠ s.next ∧ s.cells p = some fields) := by
    intro p fields hp
    by_cases he : p = s.next
    · exact Or.inl ⟨he, by simpa [alloc, he] using hp.symm⟩
    · exact Or.inr ⟨he, by simpa [alloc, he] using hp⟩
  constructor
  · intro p fields hp
    rcases lookup p fields hp with ⟨rfl, _⟩ | ⟨_, hp⟩
    · exact Nat.lt_succ_self _
    · exact Nat.lt.step (v.fresh p fields hp)
  · intro p fields hp q hq
    rcases lookup p fields hp with ⟨rfl, rfl⟩ | ⟨_, hp⟩
    · obtain ⟨children, hc⟩ := v.roots q (hf q hq)
      exact ⟨v.fresh q children hc, children, old q children hc⟩
    · obtain ⟨hlt, children, hc⟩ := v.edges p fields hp q hq
      exact ⟨hlt, children, old q children hc⟩
  · intro p hp
    simp only [alloc, List.mem_cons] at hp
    rcases hp with rfl | hp
    · exact ⟨fs, by simp [alloc]⟩
    · obtain ⟨fields, hc⟩ := v.roots p hp
      exact ⟨fields, old p fields hc⟩
  · intro p fields hp
    rcases lookup p fields hp with ⟨rfl, _⟩ | ⟨_, hp⟩
    · exact Or.inl (by simp [alloc])
    · rcases v.owned p fields hp with hr | ⟨q, children, hq, hm⟩
      · exact Or.inl (by simp [alloc, hr])
      · exact Or.inr ⟨q, children, old q children hq, hm⟩

theorem dup_valid (v : Valid s) (hp : p ∈ s.roots) : Valid (dup s p) := by
  constructor
  · exact v.fresh
  · exact v.edges
  · intro q hq
    simp only [dup, List.mem_cons] at hq
    rcases hq with rfl | hq
    · exact v.roots _ hp
    · exact v.roots q hq
  · intro q fs hq
    rcases v.owned q fs hq with hr | he
    · exact Or.inl (by simp [dup, hr])
    · exact Or.inr he

theorem dup_live_valid (v : Valid s) (hp : ∃ fields, s.cells p = some fields) :
    Valid (dup s p) := by
  constructor
  · exact v.fresh
  · exact v.edges
  · intro q hq
    simp only [dup, List.mem_cons] at hq
    rcases hq with rfl | hq
    · exact hp
    · exact v.roots q hq
  · intro q fs hq
    rcases v.owned q fs hq with hr | he
    · exact Or.inl (by simp [dup, hr])
    · exact Or.inr he

theorem removeRoot_valid (v : Valid s) (support : Incoming (removeRoot s p) p) :
    Valid (removeRoot s p) := by
  constructor
  · exact v.fresh
  · exact v.edges
  · intro q hq; exact v.roots q (List.mem_of_mem_erase hq)
  · intro q fs hq
    by_cases he : q = p
    · subst q; exact support
    · rcases v.owned q fs hq with hr | he'
      · exact Or.inl ((List.mem_erase_of_ne he).mpr hr)
      · exact Or.inr he'

/-- The critical transfer lemma: removing a last-owned parent replaces all its
    outgoing edge obligations with temporary roots, including duplicates. -/
theorem unlink_valid (v : Valid s) (hp : s.cells p = some fs)
    (last : ¬ Incoming (removeRoot s p) p) : Valid (unlink s p fs) := by
  have noRoot : p ∉ s.roots.erase p := fun h => last (Or.inl h)
  have noEdge : ∀ q children, s.cells q = some children → p ∉ children := by
    intro q children hq hm
    exact last (Or.inr ⟨q, children, hq, hm⟩)
  constructor
  · intro q children hq
    exact v.fresh q children (unlink_lookup.mp hq).2
  · intro q children hq r hr
    have old := (unlink_lookup.mp hq).2
    obtain ⟨hlt, sub, hs⟩ := v.edges q children old r hr
    have hne : r ≠ p := by intro he; subst r; exact noEdge q children old hr
    exact ⟨hlt, sub, unlink_lookup.mpr ⟨hne, hs⟩⟩
  · intro q hq
    simp only [unlink, List.mem_append] at hq
    rcases hq with hf | hr
    · obtain ⟨hlt, children, hc⟩ := v.edges p fs hp q hf
      exact ⟨children, unlink_lookup.mpr ⟨Nat.ne_of_lt hlt, hc⟩⟩
    · obtain ⟨children, hc⟩ := v.roots q (List.mem_of_mem_erase hr)
      have hne : q ≠ p := by intro he; subst q; exact noRoot hr
      exact ⟨children, unlink_lookup.mpr ⟨hne, hc⟩⟩
  · intro q children hq
    obtain ⟨hne, old⟩ := unlink_lookup.mp hq
    rcases v.owned q children old with hr | ⟨parent, fields, hparent, hm⟩
    · exact Or.inl (List.mem_append_right _ ((List.mem_erase_of_ne hne).mpr hr))
    · by_cases he : parent = p
      · subst parent
        rw [hp] at hparent
        cases hparent
        exact Or.inl (List.mem_append_left _ hm)
      · exact Or.inr ⟨parent, fields, unlink_lookup.mpr ⟨he, hparent⟩, hm⟩

end Fixed.Memory.Graph
