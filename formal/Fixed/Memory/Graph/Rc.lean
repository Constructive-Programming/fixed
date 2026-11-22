import Fixed.Memory.Graph.Safety

/- Concrete reference-count headers for the immutable graph (PR3.1, PR3.2).
   Counts are exact owned-reference multiplicities, not ticket-ledger sizes.
   The erasure to Graph.State forgets only the independently stored counts. -/
namespace Fixed.Memory.Graph.Rc

structure Cell where
  count : Nat
  fields : List Addr
  deriving DecidableEq, Repr

structure State where
  next : Addr
  cells : Addr → Option Cell
  roots : List Addr

def initial : State := ⟨0, fun _ => none, []⟩

def erase (s : State) : Graph.State :=
  ⟨s.next, fun p => (s.cells p).map Cell.fields, s.roots⟩

def edgeCount (cells : Addr → Option Cell) : List Addr → Addr → Nat
  | [], _ => 0
  | parent :: parents, p =>
    ((cells parent).elim 0 (fun c => c.fields.count p)) + edgeCount cells parents p

/-- Exact incoming owned references. This query specifies header correctness;
    concrete execution never scans the heap to decide whether to free a cell. -/
def references (s : State) (p : Addr) : Nat :=
  s.roots.count p + edgeCount s.cells (List.range s.next) p

structure Valid (s : State) : Prop where
  graph : Graph.Valid (erase s)
  headers : ∀ p c, s.cells p = some c → c.count = references s p

theorem initial_valid : Valid initial := by
  constructor
  · exact Graph.initial_valid
  · intro p c h; contradiction

def removeRoot (s : State) (p : Addr) : State :=
  { s with roots := s.roots.erase p }

def decrement (s : State) (p : Addr) : State :=
  { s with cells := fun q => (s.cells q).map fun c =>
    if q = p then { c with count := c.count - 1 } else c }

/-- Construction increments every child once per field occurrence. -/
def alloc (s : State) (fields : List Addr) : State :=
  { next := s.next + 1
    cells := fun p =>
      if p = s.next then some ⟨1, fields⟩
      else (s.cells p).map fun c => { c with count := c.count + fields.count p }
    roots := s.next :: s.roots }

def dup (s : State) (p : Addr) : State :=
  { s with
    cells := fun q => (s.cells q).map fun c =>
      if q = p then { c with count := c.count + 1 } else c
    roots := p :: s.roots }

/-- Removing the final parent moves each outgoing edge to a temporary root.
    Child counts are unchanged because each edge is replaced by one root. -/
def unlink (s : State) (p : Addr) (fields : List Addr) : State :=
  { s with
    cells := fun q => if q = p then none else s.cells q
    roots := fields ++ s.roots.erase p }

theorem erase_removeRoot (s : State) (p : Addr) :
    erase (removeRoot s p) = Graph.removeRoot (erase s) p := rfl

theorem erase_decrement (s : State) (p : Addr) :
    erase (decrement s p) = erase s := by
  cases s with
  | mk next cells roots =>
    simp only [erase, decrement]
    congr
    funext q
    cases cells q <;> by_cases h : q = p <;> simp [h]

theorem erase_unlink (s : State) (p : Addr) (fields : List Addr) :
    erase (unlink s p fields) = Graph.unlink (erase s) p fields := by
  cases s with
  | mk next cells roots =>
    simp only [erase, unlink, Graph.unlink]
    congr
    funext q
    by_cases h : q = p <;> simp [h]

theorem edgeCount_pos_iff : 0 < edgeCount cells parents p ↔
    ∃ parent ∈ parents, ∃ c, cells parent = some c ∧ p ∈ c.fields := by
  induction parents with
  | nil => simp [edgeCount]
  | cons parent parents ih =>
    simp only [edgeCount, List.mem_cons]
    cases hc : cells parent with
    | none =>
      simp only [Option.elim_none, Nat.zero_add]
      rw [ih]
      constructor
      · rintro ⟨q, hq, c, hcells, hp⟩; exact ⟨q, Or.inr hq, c, hcells, hp⟩
      · rintro ⟨q, hq, c, hcells, hp⟩
        exact ⟨q, hq.resolve_left (fun h => by subst q; simp [hc] at hcells), c, hcells, hp⟩
    | some c =>
      simp only [Option.elim_some, hc, Option.some.injEq]
      constructor
      · intro h
        by_cases hp : p ∈ c.fields
        · exact ⟨parent, Or.inl rfl, c, hc, hp⟩
        · have hpos : 0 < edgeCount cells parents p := by
            have hzero : c.fields.count p = 0 := List.count_eq_zero.mpr hp
            omega
          obtain ⟨q, hq, d, hcells, hp⟩ := ih.mp hpos
          exact ⟨q, Or.inr hq, d, hcells, hp⟩
      · rintro ⟨q, hq, d, hcells, hp⟩
        rcases hq with rfl | hq
        · cases hc.symm.trans hcells
          have : 0 < c.fields.count p := List.count_pos_iff.mpr hp
          omega
        · have hpos : 0 < edgeCount cells parents p := ih.mpr ⟨q, hq, d, hcells, hp⟩
          omega

theorem references_pos_iff (fresh : ∀ p c, s.cells p = some c → p < s.next) :
    0 < references s p ↔ Graph.Incoming (erase s) p := by
  constructor
  · intro h
    have hc : p ∈ s.roots ∨ 0 < edgeCount s.cells (List.range s.next) p := by
      by_cases hp : p ∈ s.roots
      · exact Or.inl hp
      · exact Or.inr (by
          have hz : s.roots.count p = 0 := List.count_eq_zero.mpr hp
          simpa only [references, hz, Nat.zero_add] using h)
    rcases hc with hp | hp
    · exact Or.inl hp
    · obtain ⟨parent, _, c, hc, hp⟩ := edgeCount_pos_iff.mp hp
      exact Or.inr ⟨parent, c.fields, by simp [erase, hc], hp⟩
  · rintro (hp | ⟨parent, fields, hc, hp⟩)
    · have hr : 0 < s.roots.count p := List.count_pos_iff.mpr hp
      simp only [references]
      omega
    · cases hcell : s.cells parent with
      | none => simp [erase, hcell] at hc
      | some c =>
        have hfields : c.fields = fields := by
          have heq := hc
          simp [erase, hcell] at heq
          exact heq
        have he : 0 < edgeCount s.cells (List.range s.next) p := edgeCount_pos_iff.mpr
          ⟨parent, List.mem_range.mpr (fresh parent c hcell), c, hcell,
            by simpa [hfields] using hp⟩
        simp only [references]
        omega

theorem references_removeRoot (hp : p ∈ s.roots) :
    references (removeRoot s p) q = references s q - [p].count q := by
  have hp' : 0 < s.roots.count p := List.count_pos_iff.mpr hp
  have hle : [p].count q ≤ s.roots.count q := by
    by_cases h : p = q
    · subst q; simpa using hp'
    · simp [h]
  simp only [references, removeRoot, List.count_erase, List.count_cons,
    List.count_nil, Nat.zero_add] at hle ⊢
  omega

theorem headers_positive (v : Valid s) (hc : s.cells p = some c) : 0 < c.count := by
  rw [v.headers p c hc, references_pos_iff
    (fun q d hq => v.graph.fresh q d.fields (by simp [erase, hq]))]
  exact v.graph.owned p c.fields (by simp [erase, hc])

theorem shared_iff (v : Valid s) (hp : p ∈ s.roots) (hc : s.cells p = some c) :
    1 < c.count ↔ Graph.Incoming (Graph.removeRoot (erase s) p) p := by
  rw [← erase_removeRoot, ← references_pos_iff (s := removeRoot s p)
    (fun q d hq => v.graph.fresh q d.fields (by
      change (erase s).cells q = some d.fields
      change s.cells q = some d at hq
      simp [erase, hq]))]
  rw [references_removeRoot hp, v.headers p c hc]
  have hpos : 0 < references s p := by
    rw [references_pos_iff
      (fun q d hq => v.graph.fresh q d.fields (by simp [erase, hq]))]
    exact Or.inl hp
  have hle : [p].count p ≤ references s p := by
    have hr : 0 < s.roots.count p := List.count_pos_iff.mpr hp
    simp only [references, List.count_cons, List.count_nil, beq_self_eq_true, if_true]
    omega
  simp only [List.count_cons, List.count_nil, beq_self_eq_true, if_true]
  omega

end Fixed.Memory.Graph.Rc
