import Fixed.Memory.Heap.Safety

namespace Fixed.Memory.Heap

/-- Specification-level ownership count, computed from tickets. This is not
    an independently maintained runtime reference-count header. -/
def ownerTickets (s : State) (p : Addr) : List Ticket :=
  (List.range s.nextTicket).filter (fun k => (s.owners k).any (fun o => o.target == p))

def ownershipCount (s : State) (p : Addr) : Nat := (ownerTickets s p).length

theorem ownerTickets_iff (v : Valid s) : k ∈ ownerTickets s p ↔
    ∃ o, s.owners k = some o ∧ o.target = p := by
  simp only [ownerTickets, List.mem_filter, List.mem_range, option_any_iff, beq_iff_eq]
  constructor
  · rintro ⟨_, o, ho, hp⟩; exact ⟨o, ho, hp⟩
  · rintro ⟨o, ho, hp⟩; exact ⟨v.fresh k o ho, o, ho, hp⟩

theorem ownerTickets_nodup : (ownerTickets s p).Nodup :=
  (List.nodup_range (n := s.nextTicket)).filter _

theorem collect_tickets : ownerTickets (collect s keep bs depth) p =
    (ownerTickets s p).filter keep := by
  simp only [ownerTickets, collect, List.filter_filter]
  apply List.filter_congr
  intro k _
  cases hk : keep k <;> simp [retained, hk, Bool.and_comm]

/-- A move changes placement, preserving every object's ownership count. -/
theorem transfer_count (ho : s.owners k = some o) :
    ownershipCount (transfer s k o f) p = ownershipCount s p := by
  unfold ownershipCount
  congr 1
  simp only [ownerTickets, transfer]
  apply List.filter_congr
  intro j _
  by_cases hj : j = k
  · subst j; simp [ho]
  · simp [hj]

theorem borrow_count : ownershipCount (addBorrow s b) p = ownershipCount s p := rfl

/-- Allocation/dup creates one obligation for its target, and none for other objects. -/
theorem addOwner_count : ownershipCount (addOwner s f p bound) q =
    ownershipCount s q + (if p = q then 1 else 0) := by
  unfold ownershipCount ownerTickets
  simp only [addOwner, List.range_succ, List.filter_append, List.length_append]
  have old : (List.range s.nextTicket).filter
      (fun k => (if k = s.nextTicket then some (Owner.mk f p) else s.owners k).any
        (fun o => o.target == q)) =
      (List.range s.nextTicket).filter (fun k => (s.owners k).any (fun o => o.target == q)) := by
    apply List.filter_congr
    intro k hk
    simp [Nat.ne_of_lt (List.mem_range.mp hk)]
  rw [old]
  by_cases hp : p = q <;> simp [hp]

private theorem partition_length (xs : List Ticket) (keep : Ticket → Bool) :
    (xs.filter keep).length + (xs.filter (fun k => !keep k)).length = xs.length := by
  induction xs with
  | nil => rfl
  | cons k xs ih =>
    cases hk : keep k <;> simp [hk]
    · simpa [Nat.add_assoc] using congrArg (fun n => n + 1) ih
    · rw [Nat.add_right_comm, ih]

/-- Per-object conservation: retained obligations plus released obligations
    equal the original count, including when several tickets alias one object. -/
theorem collect_balance : ownershipCount (collect s keep bs depth) p +
    ((ownerTickets s p).filter (fun k => !keep k)).length = ownershipCount s p := by
  unfold ownershipCount
  rw [collect_tickets]
  exact partition_length _ _

theorem drop_count (v : Valid s) (ho : s.owners k = some o) :
    ownershipCount (drop s k) o.target = ownershipCount s o.target - 1 := by
  unfold ownershipCount drop
  rw [collect_tickets, ← ownerTickets_nodup.erase_eq_filter]
  exact List.length_erase_of_mem ((ownerTickets_iff v).mpr ⟨o, ho, rfl⟩)

/-- A released ticket stays absent after any checked continuation, for either
    explicit drop or frame cleanup. The continuation may allocate new tickets. -/
theorem collected_never_revives (v : Valid s) (hk : k ∈ released s keep)
    (h : run (collect s keep bs depth) ops = some t) : t.owners k = none := by
  obtain ⟨o, ho, hkeep⟩ := (released_iff v).mp hk
  apply run_absent_stays_absent (s := collect s keep bs depth) (v.fresh k o ho) _ h
  simp [collect, retained, hkeep]

/-- No subsequent cleanup can issue a second receipt for a released ticket. -/
theorem no_double_release (v : Valid s) (hk : k ∈ released s keep)
    (h : run (collect s keep bs depth) ops = some t) : k ∉ released t later := by
  have absent := collected_never_revives v hk h
  simp [released, absent]

end Fixed.Memory.Heap
