import Fixed.Memory.Graph.Rc

namespace Fixed.Memory.Graph.Rc

theorem edgeCount_congr_mem
    (h : ∀ p ∈ parents, (cells p).map Cell.fields = (cells' p).map Cell.fields) :
    edgeCount cells parents q = edgeCount cells' parents q := by
  induction parents with
  | nil => simp [edgeCount]
  | cons parent parents ih =>
    have hp := h parent (by simp)
    have tail : ∀ p ∈ parents, (cells p).map Cell.fields = (cells' p).map Cell.fields :=
      fun p hp => h p (by simp [hp])
    cases hc : cells parent <;> cases hd : cells' parent <;>
      simp [edgeCount, hc, hd] at hp ⊢ <;> simp [edgeCount, hc, hd, hp, ih tail]

theorem edgeCount_congr
    (h : ∀ p, (cells p).map Cell.fields = (cells' p).map Cell.fields) :
    edgeCount cells parents q = edgeCount cells' parents q :=
  edgeCount_congr_mem (fun p _ => h p)

theorem edgeCount_append (cells : Addr → Option Cell) (ps qs : List Addr) (p : Addr) :
    edgeCount cells (ps ++ qs) p = edgeCount cells ps p + edgeCount cells qs p := by
  induction ps with
  | nil => simp [edgeCount]
  | cons parent parents ih => simp [edgeCount, ih, Nat.add_assoc]

theorem count_eq_one_of_nodup {ps : List Addr} {p : Addr}
    (nodup : ps.Nodup) (hp : p ∈ ps) : ps.count p = 1 := by
  induction ps with
  | nil => contradiction
  | cons q qs ih =>
    have hn := List.nodup_cons.mp nodup
    by_cases h : q = p
    · subst q
      have hz : qs.count p = 0 := List.count_eq_zero.mpr hn.1
      simp [List.count_cons, hz]
    · have hp' : p ∈ qs := by
        rcases List.mem_cons.mp hp with heq | hp'
        · exact False.elim (h heq.symm)
        · exact hp'
      simp [List.count_cons, h, ih hn.2 hp']

theorem edgeCount_remove (hc : cells p = some c) :
    edgeCount (fun q => if q = p then none else cells q) parents q +
      parents.count p * c.fields.count q = edgeCount cells parents q := by
  induction parents with
  | nil => simp [edgeCount]
  | cons parent parents ih =>
    by_cases h : parent = p
    · subst parent
      simp [edgeCount, hc, List.count_cons, Nat.add_mul] at *
      omega
    · simp [edgeCount, h, List.count_cons] at *
      omega

theorem references_decrement (s : State) (p q : Addr) :
    references (decrement s p) q = references s q := by
  simp only [references, decrement]
  congr 1
  apply edgeCount_congr
  intro parent
  cases s.cells parent <;> by_cases h : parent = p <;> simp [h]

theorem references_unlink_removeRoot (v : Valid s) (hc : s.cells p = some c) :
    references (unlink s p c.fields) q = references (removeRoot s p) q := by
  have he := edgeCount_remove (cells := s.cells) (parents := List.range s.next)
    (q := q) hc
  have hfresh : p < s.next := v.graph.fresh p c.fields (by simp [erase, hc])
  have hcount : (List.range s.next).count p = 1 :=
    count_eq_one_of_nodup List.nodup_range (List.mem_range.mpr hfresh)
  rw [hcount, Nat.one_mul] at he
  simp only [references, unlink, removeRoot, List.count_append]
  omega

theorem references_unlink (v : Valid s) (hp : p ∈ s.roots)
    (hc : s.cells p = some c) (hne : q ≠ p) :
    references (unlink s p c.fields) q = references s q := by
  rw [references_unlink_removeRoot v hc, references_removeRoot hp]
  simp [List.count_cons, hne, Ne.symm hne]

theorem shared_valid (v : Valid s) (hp : p ∈ s.roots) (hc : s.cells p = some c)
    (support : Graph.Incoming (Graph.removeRoot (erase s) p) p) :
    Valid (decrement (removeRoot s p) p) := by
  constructor
  · rw [erase_decrement, erase_removeRoot]
    exact Graph.removeRoot_valid v.graph support
  · intro q d hq
    by_cases h : q = p
    · subst q
      have hd : d = { c with count := c.count - 1 } := by
        have heq := hq
        simp [decrement, removeRoot, hc] at heq
        exact heq.symm
      subst d
      simp only [Cell.count]
      rw [references_decrement, references_removeRoot hp, v.headers p c hc]
      simp
    · have old : s.cells q = some d := by
        have heq := hq
        simp [decrement, removeRoot, h] at heq
        exact heq
      rw [references_decrement, v.headers q d old, references_removeRoot hp]
      simp [List.count_cons, h, Ne.symm h]

theorem unlink_valid (v : Valid s) (hp : p ∈ s.roots) (hc : s.cells p = some c)
    (last : ¬ Graph.Incoming (Graph.removeRoot (erase s) p) p) :
    Valid (unlink s p c.fields) := by
  constructor
  · rw [erase_unlink]
    exact Graph.unlink_valid v.graph (by simp [erase, hc]) last
  · intro q d hq
    have hne : q ≠ p := by
      intro h
      subst q
      simp [unlink] at hq
    have old : s.cells q = some d := by
      simpa [unlink, hne] using hq
    rw [v.headers q d old, references_unlink v hp hc hne]

theorem erase_alloc (s : State) (fields : List Addr) :
    erase (alloc s fields) = Graph.alloc (erase s) fields := by
  cases s with
  | mk next cells roots =>
    simp only [erase, alloc, Graph.alloc]
    congr
    funext q
    by_cases h : q = next
    · subst q; simp [alloc]
    · cases cells q <;> simp [alloc, h]

theorem erase_dup (s : State) (p : Addr) : erase (dup s p) = Graph.dup (erase s) p := by
  cases s with
  | mk next cells roots =>
    simp only [erase, dup, Graph.dup]
    congr
    funext q
    cases cells q <;> by_cases h : q = p <;> simp [h]

theorem dup_edges (s : State) (p q : Addr) :
    edgeCount (dup s p).cells (List.range (dup s p).next) q =
      edgeCount s.cells (List.range s.next) q := by
  apply edgeCount_congr
  intro parent
  cases hc : s.cells parent with
  | none => simp [dup, hc]
  | some c =>
    by_cases h : parent = p
    · subst parent; simp [dup, hc]
    · simp [dup, hc, h]

theorem references_dup (s : State) (p q : Addr) :
    references (dup s p) q = references s q + [p].count q := by
  simp only [references]
  rw [dup_edges]
  simp only [dup, List.count_cons, List.count_nil, Nat.zero_add, beq_iff_eq]
  omega

theorem dup_valid (v : Valid s) (hp : p ∈ s.roots) : Valid (dup s p) := by
  constructor
  · rw [erase_dup]
    exact Graph.dup_valid v.graph hp
  · intro q d hq
    by_cases h : q = p
    · subst q
      cases hc : s.cells p with
      | none => simp [dup, hc] at hq
      | some c =>
        have hd : d = { c with count := c.count + 1 } := by
          have heq := hq
          simp [dup, hc] at heq
          exact heq.symm
        subst d
        simp only [Cell.count]
        rw [references_dup, v.headers p c hc]
        simp
    · have old : s.cells q = some d := by
        have heq := hq
        simp [dup, h] at heq
        exact heq
      rw [references_dup, v.headers q d old]
      simp [List.count_cons, h, Ne.symm h]

theorem dup_live_valid (v : Valid s) (hc : s.cells p = some c) : Valid (dup s p) := by
  constructor
  · rw [erase_dup]
    exact Graph.dup_live_valid v.graph ⟨c.fields, by simp [erase, hc]⟩
  · intro q d hq
    by_cases h : q = p
    · subst q
      have hd : d = { c with count := c.count + 1 } := by
        have heq := hq
        simp [dup, hc] at heq
        exact heq.symm
      subst d
      simp only [Cell.count]
      rw [references_dup, v.headers p c hc]
      simp
    · have old : s.cells q = some d := by
        have heq := hq
        simp [dup, h] at heq
        exact heq
      rw [references_dup, v.headers q d old]
      simp [List.count_cons, h, Ne.symm h]

theorem alloc_old_edges (s : State) (fields : List Addr) (q : Addr) :
    edgeCount (alloc s fields).cells (List.range s.next) q =
      edgeCount s.cells (List.range s.next) q := by
  apply edgeCount_congr_mem
  intro parent hp
  have hne : parent ≠ s.next := Nat.ne_of_lt (List.mem_range.mp hp)
  cases hc : s.cells parent <;> simp [alloc, hne, hc]

theorem alloc_edges (s : State) (fields : List Addr) (q : Addr) :
    edgeCount (alloc s fields).cells (List.range (alloc s fields).next) q =
      edgeCount s.cells (List.range s.next) q + fields.count q := by
  change edgeCount (alloc s fields).cells (List.range (s.next + 1)) q = _
  rw [← Nat.succ_eq_add_one, List.range_succ, edgeCount_append, alloc_old_edges]
  simp [edgeCount, alloc]

theorem references_alloc (s : State) (fields : List Addr) (q : Addr) :
    references (alloc s fields) q = references s q + fields.count q + [s.next].count q := by
  simp only [references]
  rw [alloc_edges]
  simp only [alloc, List.count_cons, List.count_nil, Nat.zero_add]
  omega

theorem references_next_eq_zero (v : Valid s) : references s s.next = 0 := by
  have no : ¬ Graph.Incoming (erase s) s.next := by
    rintro (hr | ⟨parent, fields, hc, hm⟩)
    · obtain ⟨fields, hc⟩ := v.graph.roots s.next hr
      exact Nat.lt_irrefl _ (v.graph.fresh s.next fields hc)
    · have hlt := (v.graph.edges parent fields hc s.next hm).1
      have hp := v.graph.fresh parent fields hc
      exact Nat.lt_asymm hlt hp
  apply Nat.eq_zero_of_not_pos
  intro pos
  exact no ((references_pos_iff
    (fun q d hq => v.graph.fresh q d.fields (by simp [erase, hq]))).mp pos)

theorem alloc_valid (v : Valid s) (hf : ∀ q ∈ fields, q ∈ s.roots) : Valid (alloc s fields) := by
  have hnext : references s s.next = 0 := references_next_eq_zero v
  have hfields : fields.count s.next = 0 := List.count_eq_zero.mpr (fun hm => by
    obtain ⟨children, hc⟩ := v.graph.roots s.next (hf s.next hm)
    exact Nat.lt_irrefl _ (v.graph.fresh s.next children hc))
  constructor
  · rw [erase_alloc]
    exact Graph.alloc_valid v.graph hf
  · intro q d hq
    by_cases h : q = s.next
    · subst q
      have hd : d = ⟨1, fields⟩ := by
        have heq := hq
        simp [alloc] at heq
        exact heq.symm
      subst d
      simp only [Cell.count]
      rw [references_alloc, hnext, hfields]
      simp
    · cases hc : s.cells q with
      | none => simp [alloc, h, hc] at hq
      | some c =>
        have hd : d = { c with count := c.count + fields.count q } := by
          have heq := hq
          simp [alloc, h, hc] at heq
          exact heq.symm
        subst d
        have hfresh : q < s.next := v.graph.fresh q c.fields (by simp [erase, hc])
        have hn : s.next ≠ q := Ne.symm (Nat.ne_of_lt hfresh)
        simp only [Cell.count]
        rw [references_alloc, v.headers q c hc]
        simp [List.count_cons, hn]

/-- Sequential concrete cleanup. A count above one is a shared decrement;
    a count of one unlinks the cell and drains its fields from left to right. -/
def releaseFuel : Nat → State → Addr → Option State
  | 0, _, _ => none
  | fuel + 1, s, p =>
    if p ∈ s.roots then
      match s.cells p with
      | none => none
      | some c =>
        if 1 < c.count then some (decrement (removeRoot s p) p)
        else c.fields.foldlM (fun t q => releaseFuel fuel t q) (unlink s p c.fields)
    else none

inductive Op where
  | alloc (fields : List Addr)
  | dup (addr : Addr)
  | drop (addr : Addr)
  deriving Repr

def execute (s : State) : Op → Option State
  | .alloc fields =>
    if s.cells s.next = none ∧ (∀ q ∈ fields, q ∈ s.roots) then some (alloc s fields) else none
  | .dup p => if p ∈ s.roots then some (dup s p) else none
  | .drop p => if p ∈ s.roots then releaseFuel s.next s p else none

def run (s : State) : List Op → Option State
  | [] => some s
  | op :: ops => (execute s op).bind (fun t => run t ops)

def eraseOp : Op → Graph.Op
  | .alloc fields => .alloc fields
  | .dup p => .dup p
  | .drop p => .drop p

theorem releases_refine {fuel : Nat} {ps : List Addr}
    (single : ∀ {s p t}, Valid s → releaseFuel fuel s p = some t →
      Graph.releaseFuel fuel (erase s) p = some (erase t) ∧ Valid t)
    (v : Valid s) (h : ps.foldlM (fun u q => releaseFuel fuel u q) s = some t) :
    ps.foldlM (fun u q => Graph.releaseFuel fuel u q) (erase s) = some (erase t) ∧ Valid t := by
  induction ps generalizing s with
  | nil => cases h; exact ⟨rfl, v⟩
  | cons p ps ih =>
    rw [List.foldlM_cons] at h
    cases hp : releaseFuel fuel s p with
    | none => simp [hp] at h
    | some u =>
      simp only [hp, Option.bind_some] at h
      have hu := single v hp
      obtain ⟨ht, vt⟩ := ih hu.2 h
      exact ⟨by rw [List.foldlM_cons, hu.1]; exact ht, vt⟩

theorem releaseFuel_refines (v : Valid s) (h : releaseFuel fuel s p = some t) :
    Graph.releaseFuel fuel (erase s) p = some (erase t) ∧ Valid t := by
  induction fuel generalizing s p t with
  | zero => simp [releaseFuel] at h
  | succ fuel ih =>
    simp only [releaseFuel] at h
    split at h
    next hp =>
      split at h
      next => contradiction
      next c hc =>
        have hgc : (erase s).cells p = some c.fields := by simp [erase, hc]
        split at h
        next shared =>
          cases h
          have support : Graph.Incoming (Graph.removeRoot (erase s) p) p :=
            (shared_iff v hp hc).mp shared
          have incoming : Graph.hasIncoming (Graph.removeRoot (erase s) p) p = true :=
            (Graph.hasIncoming_iff (s := Graph.removeRoot (erase s) p) (p := p)
              (fun q fs hq => v.graph.fresh q fs hq)).mpr support
          have hgp : p ∈ (erase s).roots := hp
          exact ⟨by simp [Graph.releaseFuel, hgp, hgc, incoming,
            erase_decrement, erase_removeRoot], shared_valid v hp hc support⟩
        next last =>
          have noSupport : ¬ Graph.Incoming (Graph.removeRoot (erase s) p) p :=
            fun support => last ((shared_iff v hp hc).mpr support)
          have incoming : Graph.hasIncoming (Graph.removeRoot (erase s) p) p = false := by
            cases hi : Graph.hasIncoming (Graph.removeRoot (erase s) p) p with
            | false => rfl
            | true => exact False.elim (noSupport ((Graph.hasIncoming_iff
              (s := Graph.removeRoot (erase s) p) (p := p)
              (fun q fs hq => v.graph.fresh q fs hq)).mp hi))
          obtain ⟨ht, vt⟩ := releases_refine (@ih) (unlink_valid v hp hc noSupport) h
          rw [erase_unlink] at ht
          have hgp : p ∈ (erase s).roots := hp
          exact ⟨by simp [Graph.releaseFuel, hgp, hgc, incoming, ht], vt⟩
    next => contradiction

theorem execute_refines (v : Valid s) (h : execute s op = some t) :
    Graph.execute (erase s) (eraseOp op) = some (erase t) ∧ Valid t := by
  cases op with
  | alloc fields =>
    simp only [execute] at h
    split at h
    next hc =>
      cases h
      have hnone : (erase s).cells s.next = none := by simp [erase, hc.1]
      have hgf : ∀ q ∈ fields, q ∈ (erase s).roots := hc.2
      have hguard : (erase s).cells (erase s).next = none ∧
          ∀ q ∈ fields, q ∈ (erase s).roots := ⟨hnone, hgf⟩
      exact ⟨by simp only [Graph.execute, eraseOp, if_pos hguard, erase_alloc],
        alloc_valid v hc.2⟩
    next => contradiction
  | dup p =>
    simp only [execute] at h
    split at h
    next hp =>
      cases h
      have hgp : p ∈ (erase s).roots := hp
      exact ⟨by simp [Graph.execute, eraseOp, hgp, erase_dup], dup_valid v hp⟩
    next => contradiction
  | drop p =>
    simp only [execute] at h
    split at h
    next hp =>
      have hr := releaseFuel_refines v h
      have hgp : p ∈ (erase s).roots := hp
      refine ⟨?_, hr.2⟩
      simp only [Graph.execute, eraseOp, if_pos hgp]
      exact hr.1
    next => contradiction

theorem run_refines (v : Valid s) (h : run s ops = some t) :
    Graph.run (erase s) (ops.map eraseOp) = some (erase t) ∧ Valid t := by
  induction ops generalizing s with
  | nil => cases h; exact ⟨rfl, v⟩
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      have hu := execute_refines v he
      obtain ⟨ht, vt⟩ := ih hu.2 h
      exact ⟨by simp [Graph.run, hu.1, ht], vt⟩

/-- Concrete execution refines abstract owned-edge cleanup, and every accepted
    final cell still carries the exact number of incoming owned references. -/
theorem accepted_refines (h : run initial ops = some t) :
    Graph.run Graph.initial (ops.map eraseOp) = some (erase t) ∧ Valid t := by
  exact run_refines initial_valid h

theorem accepted_release (v : Valid s) (h : execute s (.drop p) = some t) :
    Graph.Release (erase s) p (erase t) := by
  have hr := (execute_refines v h).1
  simp only [Graph.execute, eraseOp] at hr
  split at hr
  next => exact Graph.releaseFuel_sound v.graph hr
  next => contradiction

theorem accepted_no_dangling (h : run initial ops = some s) (hp : p ∈ s.roots) :
    ∃ c, s.cells p = some c := by
  obtain ⟨fs, hc⟩ := (accepted_refines h).2.graph.roots p hp
  cases hcell : s.cells p with
  | none => simp [erase, hcell] at hc
  | some c => exact ⟨c, rfl⟩

end Fixed.Memory.Graph.Rc
