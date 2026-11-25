import Fixed.Memory.Heap.Model

namespace Fixed.Memory.Heap

theorem owner_live (v : Valid s) (h : s.owners k = some o) : s.heap o.target = true :=
  (v.owned o.target).mpr ⟨k, o, h, rfl⟩

private theorem addOwner_valid (v : Valid s) (hf : f ≤ s.depth)
    (hp : p < bound)
    (hb : s.nextAddr ≤ bound) : Valid (addOwner s f p bound) := by
  have lookup : ∀ k o, (addOwner s f p bound).owners k = some o →
      (k = s.nextTicket ∧ o = ⟨f, p⟩) ∨ (k ≠ s.nextTicket ∧ s.owners k = some o) := by
    intro k o h
    by_cases hk : k = s.nextTicket
    · simp [addOwner, hk] at h
      exact Or.inl ⟨hk, h.symm⟩
    · exact Or.inr ⟨hk, by simpa [addOwner, hk] using h⟩
  have old : ∀ k o, s.owners k = some o → (addOwner s f p bound).owners k = some o := by
    intro k o ho
    have hk := Nat.ne_of_lt (v.fresh k o ho)
    simpa [addOwner, hk] using ho
  constructor
  · intro k o ho
    rcases lookup k o ho with ⟨rfl, _⟩ | ⟨_, ho⟩
    · exact Nat.lt_succ_self _
    · exact Nat.lt.step (v.fresh k o ho)
  · intro k o ho
    rcases lookup k o ho with ⟨_, rfl⟩ | ⟨_, ho⟩
    · exact hf
    · exact v.placed k o ho
  · intro q hq
    by_cases hqp : q = p
    · subst q; exact hp
    · exact Nat.lt_of_lt_of_le (v.allocated q (by simpa [addOwner, hqp] using hq)) hb
  · intro q
    constructor
    · intro hq
      by_cases hqp : q = p
      · subst q
        exact ⟨s.nextTicket, ⟨f, p⟩, by simp [addOwner], rfl⟩
      · obtain ⟨k, o, ho, ht⟩ := (v.owned q).mp (by simpa [addOwner, hqp] using hq)
        exact ⟨k, o, old k o ho, ht⟩
    · rintro ⟨k, o, ho, ht⟩
      rcases lookup k o ho with ⟨_, rfl⟩ | ⟨_, ho⟩
      · simp_all [addOwner]
      · have hl : s.heap q = true := (v.owned q).mpr ⟨k, o, ho, ht⟩
        simp [addOwner, hl]
  · intro b hmem
    obtain ⟨hd, o, ho, hf⟩ := v.borrowed b hmem
    exact ⟨hd, o, old b.ticket o ho, hf⟩

private theorem transfer_valid (v : Valid s) (ho : s.owners k = some o)
    (hf : f ≤ s.depth) (safe : MoveSafe s k f) : Valid (transfer s k o f) := by
  constructor
  · intro j x hx
    by_cases hj : j = k
    · subst j; exact v.fresh k o ho
    · exact v.fresh j x (by simpa [transfer, hj] using hx)
  · intro j x hx
    by_cases hj : j = k
    · subst j
      simp [transfer] at hx
      subst x
      exact hf
    · exact v.placed j x (by simpa [transfer, hj] using hx)
  · exact v.allocated
  · intro p
    constructor
    · intro hp
      obtain ⟨j, x, hx, ht⟩ := (v.owned p).mp hp
      by_cases hj : j = k
      · subst j
        rw [ho] at hx
        cases hx
        exact ⟨k, { o with frame := f }, by simp [transfer], ht⟩
      · exact ⟨j, x, by simpa [transfer, hj] using hx, ht⟩
    · rintro ⟨j, x, hx, ht⟩
      by_cases hj : j = k
      · subst j
        simp [transfer] at hx
        subst x
        exact (v.owned p).mpr ⟨k, o, ho, ht⟩
      · exact (v.owned p).mpr ⟨j, x, by simpa [transfer, hj] using hx, ht⟩
  · intro b hm
    obtain ⟨hd, x, hx, hxf⟩ := v.borrowed b hm
    refine ⟨hd, ?_⟩
    by_cases hk : b.ticket = k
    · exact ⟨{ o with frame := f }, by simp [transfer, hk], safe b hm hk⟩
    · exact ⟨x, by simpa [transfer, hk] using hx, hxf⟩

theorem retained_iff : retained s keep k = some o ↔ s.owners k = some o ∧ keep k = true := by
  simp [retained, and_comm]

/-- Collection preserves validity when retained owners and borrows fit the new scope.
    No reference to a removed ticket is permitted among the retained borrows. -/
theorem collect_valid (v : Valid s)
    (placed : ∀ k o, s.owners k = some o → keep k = true → o.frame ≤ depth)
    (borrowed : ∀ b ∈ bs, b.frame ≤ depth ∧
      ∃ o, s.owners b.ticket = some o ∧ keep b.ticket = true ∧ o.frame ≤ b.frame) :
    Valid (collect s keep bs depth) := by
  constructor
  · intro k o ho
    exact v.fresh k o (retained_iff.mp ho).1
  · intro k o ho
    obtain ⟨ho, hk⟩ := retained_iff.mp ho
    exact placed k o ho hk
  · intro p hp
    exact v.allocated p (Bool.and_eq_true_iff.mp hp).1
  · intro p
    constructor
    · intro hp
      obtain ⟨k, o, _, ho, ht⟩ := supported_iff.mp (Bool.and_eq_true_iff.mp hp).2
      exact ⟨k, o, ho, ht⟩
    · rintro ⟨k, o, ho, ht⟩
      have old := (retained_iff.mp ho).1
      exact Bool.and_eq_true_iff.mpr ⟨(v.owned p).mpr ⟨k, o, old, ht⟩,
        supported_iff.mpr ⟨k, o, v.fresh k o old, ho, ht⟩⟩
  · intro b hm
    obtain ⟨hd, o, ho, hk, hf⟩ := borrowed b hm
    exact ⟨hd, o, retained_iff.mpr ⟨ho, hk⟩, hf⟩

theorem reset_owner_iff : (reset s).owners k = some o ↔
    s.owners k = some o ∧ o.frame < s.depth := by
  simp only [reset, collect, retained_iff, outer]
  constructor
  · rintro ⟨ho, hf⟩
    exact ⟨ho, by simpa [ho] using hf⟩
  · rintro ⟨ho, hf⟩
    exact ⟨ho, by simp [ho, hf]⟩

theorem reset_valid (v : Valid s) : Valid (reset s) := by
  apply collect_valid v
  · intro k o ho hk
    have hf : o.frame < s.depth := by simpa [outer, ho] using hk
    exact Nat.le_sub_one_of_lt hf
  · intro b hm
    have hm' : b ∈ s.borrows ∧ b.frame < s.depth := by simpa using hm
    obtain ⟨_, o, ho, hf⟩ := v.borrowed b hm'.1
    have holt := Nat.lt_of_le_of_lt hf hm'.2
    exact ⟨Nat.le_sub_one_of_lt hm'.2, o, ho, by simp [outer, ho, holt], hf⟩

theorem preservation (v : Valid s) (h : Step s op t) : Valid t := by
  cases h with
  | enter =>
    constructor
    · exact v.fresh
    · intro k o ho; exact Nat.le_step (v.placed k o ho)
    · exact v.allocated
    · exact v.owned
    · intro b hm
      obtain ⟨hd, hb⟩ := v.borrowed b hm
      exact ⟨Nat.le_step hd, hb⟩
  | alloc hf _ _ =>
    exact addOwner_valid v hf (Nat.lt_succ_self _) (Nat.le_succ _)
  | dup ho hf _ =>
    exact addOwner_valid v hf (v.allocated _ (owner_live v ho)) (Nat.le_refl _)
  | move ho hf hb => exact transfer_valid v ho hf hb
  | borrow ho hf hd =>
    constructor
    · exact v.fresh
    · exact v.placed
    · exact v.allocated
    · exact v.owned
    · intro b hm
      simp only [addBorrow, List.mem_cons] at hm
      rcases hm with rfl | hm
      · exact ⟨hd, _, ho, hf⟩
      · exact v.borrowed b hm
  | drop _ hb =>
    apply collect_valid v
    · intro k o ho _; exact v.placed k o ho
    · intro b hm
      obtain ⟨hd, o, ho, hf⟩ := v.borrowed b hm
      exact ⟨hd, o, ho, by simpa using hb b hm, hf⟩
  | leave _ => exact reset_valid v

theorem accepted_preserves (v : Valid s) (h : execute s op = some t) : Valid t :=
  preservation v (execute_sound h)

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

end Fixed.Memory.Heap
