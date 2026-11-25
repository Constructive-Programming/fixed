import Fixed.Memory.Heap.Preservation

namespace Fixed.Memory.Heap

/-- Every modeled access is either ownership or a borrow tied to that owner. -/
inductive Accessible (s : State) : Addr → Prop where
  | owner : s.owners k = some o → Accessible s o.target
  | borrow : b ∈ s.borrows → s.owners b.ticket = some o → Accessible s o.target

theorem no_dangling (v : Valid s) (h : Accessible s p) : s.heap p = true := by
  cases h with
  | owner ho => exact owner_live v ho
  | borrow _ ho => exact owner_live v ho

/-- Borrow validity includes existence of the ticket, not merely a conditional
    claim about its target if it happened to exist. -/
theorem borrow_live (v : Valid s) (hb : b ∈ s.borrows) :
    ∃ o, s.owners b.ticket = some o ∧ o.frame ≤ b.frame ∧ s.heap o.target = true := by
  obtain ⟨_, o, ho, hf⟩ := v.borrowed b hb
  exact ⟨o, ho, hf, owner_live v ho⟩

theorem accepted_no_dangling (h : run initial ops = some s) (ha : Accessible s p) :
    s.heap p = true := no_dangling (run_preserves initial_valid h) ha

theorem collect_live_iff (v : Valid s) : (collect s keep bs depth).heap p = true ↔
    ∃ k o, s.owners k = some o ∧ keep k = true ∧ o.target = p := by
  constructor
  · intro h
    obtain ⟨k, o, _, ho, ht⟩ := supported_iff.mp (Bool.and_eq_true_iff.mp h).2
    obtain ⟨old, hk⟩ := retained_iff.mp ho
    exact ⟨k, o, old, hk, ht⟩
  · rintro ⟨k, o, ho, hk, ht⟩
    exact Bool.and_eq_true_iff.mpr ⟨(v.owned p).mpr ⟨k, o, ho, ht⟩,
      supported_iff.mpr ⟨k, o, v.fresh k o ho, retained_iff.mpr ⟨ho, hk⟩, ht⟩⟩

/-- The heap object survives exactly when another ticket still owns it. -/
theorem drop_live_iff (v : Valid s) : (drop s k).heap p = true ↔
    ∃ j o, s.owners j = some o ∧ j ≠ k ∧ o.target = p := by
  simpa [drop] using (collect_live_iff (keep := fun j => j != k) (bs := s.borrows)
    (depth := s.depth) (p := p) v)

theorem last_owner_freed (v : Valid s)
    (last : ∀ j o, s.owners j = some o → o.target = p → j = k) :
    (drop s k).heap p = false := by
  cases he : (drop s k).heap p with
  | false => rfl
  | true =>
    obtain ⟨j, o, ho, hj, ht⟩ := (drop_live_iff v).mp he
    exact False.elim (hj (last j o ho ht))

theorem reset_live_iff (v : Valid s) : (reset s).heap p = true ↔
    ∃ k o, s.owners k = some o ∧ o.frame < s.depth ∧ o.target = p := by
  rw [reset, collect_live_iff v]
  constructor
  · rintro ⟨k, o, ho, hk, ht⟩
    exact ⟨k, o, ho, by simpa [outer, ho] using hk, ht⟩
  · rintro ⟨k, o, ho, hf, ht⟩
    exact ⟨k, o, ho, by simp [outer, ho, hf], ht⟩

theorem reset_borrow_live (v : Valid s) (hb : b ∈ (reset s).borrows) :
    ∃ o, s.owners b.ticket = some o ∧ (reset s).owners b.ticket = some o ∧
      o.frame < s.depth ∧ (reset s).heap o.target = true := by
  obtain ⟨o, ho, _, hl⟩ := borrow_live (reset_valid v) hb
  obtain ⟨old, hf⟩ := reset_owner_iff.mp ho
  exact ⟨o, old, ho, hf, hl⟩

theorem released_nodup : (released s keep).Nodup :=
  (List.nodup_range (n := s.nextTicket)).filter _

theorem released_iff (v : Valid s) : k ∈ released s keep ↔
    ∃ o, s.owners k = some o ∧ keep k = false := by
  simp only [released, List.mem_filter, List.mem_range, Bool.and_eq_true,
    Bool.not_eq_true]
  constructor
  · rintro ⟨_, ho, hk⟩
    cases h : s.owners k with
    | none => simp [h] at ho
    | some o => exact ⟨o, rfl, by simpa using hk⟩
  · rintro ⟨o, ho, hk⟩
    exact ⟨v.fresh k o ho, by simp [ho], by simp [hk]⟩

/-- Exactly one receipt for each obligation of the closing frame, and none
    for obligations transferred to an outer frame or already dropped. -/
theorem reset_exactly_once (v : Valid s) :
    (released s (outer s)).Nodup ∧
      ∀ k, k ∈ released s (outer s) ↔ ∃ o, s.owners k = some o ∧ o.frame = s.depth := by
  refine ⟨released_nodup, ?_⟩
  intro k
  rw [released_iff v]
  constructor
  · rintro ⟨o, ho, hk⟩
    have hn : ¬ o.frame < s.depth := by simpa [outer, ho] using hk
    exact ⟨o, ho, Nat.le_antisymm (v.placed k o ho) (Nat.le_of_not_lt hn)⟩
  · rintro ⟨o, ho, hf⟩
    exact ⟨o, ho, by simp [outer, ho, hf]⟩

/-- Moving preserves the ticket and target while changing its responsible scope. -/
theorem transfer_ticket :
    (transfer s k o f).owners k = some ⟨f, o.target⟩ ∧
    (∀ j, j ≠ k → (transfer s k o f).owners j = s.owners j) ∧
    (transfer s k o f).heap = s.heap := by
  refine ⟨by simp [transfer], ?_, rfl⟩
  intro j hj
  simp [transfer, hj]

/-- The frame drop-list membership follows the moved obligation. -/
theorem transfer_dropList (v : Valid s) (ho : s.owners k = some o) :
    k ∈ dropList (transfer s k o f) g ↔ f = g := by
  simp [dropList, transfer, v.fresh k o ho]

/-- Borrowing changes neither heap liveness nor the ownership ledger. -/
theorem borrow_no_ownership :
    (addBorrow s b).owners = s.owners ∧ (addBorrow s b).heap = s.heap := ⟨rfl, rfl⟩

theorem step_next_mono (h : Step s op t) : s.nextTicket ≤ t.nextTicket := by
  cases h <;> simp [enter, addOwner, transfer, addBorrow, drop, reset, collect]

theorem absent_stays_absent (hn : k < s.nextTicket) (hk : s.owners k = none)
    (h : Step s op t) : t.owners k = none := by
  cases h with
  | enter => exact hk
  | alloc _ _ _ => simpa [addOwner, Nat.ne_of_lt hn] using hk
  | dup _ _ _ => simpa [addOwner, Nat.ne_of_lt hn] using hk
  | move ho _ _ =>
    rename_i j o f
    have hne : k ≠ j := by intro he; subst j; rw [hk] at ho; contradiction
    simpa [transfer, hne] using hk
  | borrow _ _ _ => exact hk
  | drop _ _ => simp [drop, collect, retained, hk]
  | leave _ => simp [reset, collect, retained, hk]

theorem run_absent_stays_absent (hn : k < s.nextTicket) (hk : s.owners k = none)
    (h : run s ops = some t) : t.owners k = none := by
  induction ops generalizing s with
  | nil => cases h; exact hk
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      have hs := execute_sound he
      exact ih (Nat.lt_of_lt_of_le hn (step_next_mono hs)) (absent_stays_absent hn hk hs) h

/-- A reset receipt can never name an ownership obligation again, even after
    entering a new frame at the same depth and allocating further tickets. -/
theorem released_never_revives (v : Valid s) (hk : k ∈ released s (outer s))
    (h : run (reset s) ops = some t) : t.owners k = none := by
  obtain ⟨o, ho, hf⟩ := (reset_exactly_once v).2 k |>.mp hk
  apply run_absent_stays_absent (s := reset s) (v.fresh k o ho) _ h
  simp [reset, collect, retained, outer, ho, hf]

theorem step_addr_mono (h : Step s op t) : s.nextAddr ≤ t.nextAddr := by
  cases h <;> simp [enter, addOwner, transfer, addBorrow, drop, reset, collect]

theorem heap_absent_stays_absent (v : Valid s) (hn : p < s.nextAddr)
    (hp : s.heap p = false) (h : Step s op t) : t.heap p = false := by
  cases h with
  | enter => exact hp
  | alloc _ _ _ => simpa [addOwner, Nat.ne_of_lt hn] using hp
  | dup ho _ _ =>
    have hl := owner_live v ho
    simp only [addOwner]
    split
    next he => rw [he] at hp; rw [hp] at hl; contradiction
    next => exact hp
  | move _ _ _ => exact hp
  | borrow _ _ _ => exact hp
  | drop _ _ => simp [drop, collect, hp]
  | leave _ => simp [reset, collect, hp]

/-- Reclaimed logical object identities, as well as tickets, cannot be reused. -/
theorem run_heap_absent_stays_absent (v : Valid s) (hn : p < s.nextAddr)
    (hp : s.heap p = false) (h : run s ops = some t) : t.heap p = false := by
  induction ops generalizing s with
  | nil => cases h; exact hp
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      have hs := execute_sound he
      exact ih (preservation v hs) (Nat.lt_of_lt_of_le hn (step_addr_mono hs))
        (heap_absent_stays_absent v hn hp hs) h

end Fixed.Memory.Heap
