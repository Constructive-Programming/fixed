import Std

/- Abstract frame memory for spec/memory_management.md MM4.1 and MM5.1.
   Addresses are fresh logical identities, not machine pointers. Frames are
   nested depths: smaller depths outlive larger depths. Frame 0 is permanent
   for this machine's run, not static storage in the source language. -/
namespace Fixed.Memory

abbrev Addr := Nat
abbrev Frame := Nat

structure Cell where
  frame : Frame
  /-- None is reserved storage; some fields is a completed, immutable object. -/
  fields : Option (List Addr)
  deriving DecidableEq, Repr

structure Root where
  frame : Frame
  target : Addr
  deriving DecidableEq, Repr

structure State where
  depth : Frame
  next : Addr
  cells : Addr → Option Cell
  roots : List Root

def initial : State := ⟨0, 0, fun _ => none, []⟩

/-- The referent exists and outlives the holder. -/
def RefSafe (s : State) (holder : Frame) (p : Addr) : Prop :=
  match s.cells p with
  | none => False
  | some c => c.frame ≤ holder

instance (s : State) (holder : Frame) (p : Addr) : Decidable (RefSafe s holder p) := by
  unfold RefSafe
  split <;> infer_instance

structure Valid (s : State) : Prop where
  fresh : ∀ p c, s.cells p = some c → p < s.next
  live : ∀ p c, s.cells p = some c → c.frame ≤ s.depth
  edges : ∀ p c, s.cells p = some c → ∀ q ∈ c.fields.getD [], RefSafe s c.frame q
  roots : ∀ r ∈ s.roots, r.frame ≤ s.depth ∧ RefSafe s r.frame r.target

theorem initial_valid : Valid initial := by
  constructor <;> simp [initial]

def install (s : State) (p : Addr) (c : Cell) : State :=
  { s with cells := fun q => if q = p then some c else s.cells q
           next := max s.next (p + 1) }

def enter (s : State) : State := { s with depth := s.depth + 1 }

def addRoot (s : State) (r : Root) : State := { s with roots := r :: s.roots }

/-- Reclaim the innermost frame and its roots. Guarded by depth > 0 in Step. -/
def reset (s : State) : State :=
  { s with
    depth := s.depth - 1
    cells := fun p => (s.cells p).filter (fun c => c.frame < s.depth)
    roots := s.roots.filter (fun r => r.frame < s.depth) }

/-- Local operations; init is write-once, not arbitrary mutation.
    reserve may target an outer active frame (an abstract destination). -/
inductive Op where
  | enter
  | reserve (frame : Frame)
  | init (addr : Addr) (fields : List Addr)
  | root (frame : Frame) (addr : Addr)
  | leave
  deriving Repr

/-- Operational semantics with local premises, independent of the global Valid invariant. -/
inductive Step : State → Op → State → Prop where
  | enter : Step s .enter (enter s)
  | reserve : f ≤ s.depth → s.cells s.next = none →
      Step s (.reserve f) (install s s.next ⟨f, none⟩)
  | init : s.cells p = some ⟨f, none⟩ →
      (∀ q ∈ fields, RefSafe s f q) →
      Step s (.init p fields) (install s p ⟨f, some fields⟩)
  | root : f ≤ s.depth → RefSafe s f p →
      Step s (.root f p) (addRoot s ⟨f, p⟩)
  | leave : 0 < s.depth → Step s .leave (reset s)

/-- Executable checker: checks local guards, never scans for the global invariant. -/
def execute (s : State) : Op → Option State
  | .enter => some (enter s)
  | .reserve f =>
    if f ≤ s.depth ∧ s.cells s.next = none then
      some (install s s.next ⟨f, none⟩) else none
  | .init p fields =>
    match s.cells p with
    | some ⟨f, none⟩ =>
      if ∀ q ∈ fields, RefSafe s f q then
        some (install s p ⟨f, some fields⟩) else none
    | _ => none
  | .root f p =>
    if f ≤ s.depth ∧ RefSafe s f p then some (addRoot s ⟨f, p⟩) else none
  | .leave => if 0 < s.depth then some (reset s) else none

theorem execute_sound (h : execute s op = some t) : Step s op t := by
  cases op with
  | enter => cases h; exact .enter
  | reserve f =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact .reserve hc.1 hc.2
    next => contradiction
  | init p fields =>
    simp only [execute] at h
    split at h
    next f hp =>
      split at h
      next hc => cases h; exact .init hp hc
      next => contradiction
    next => contradiction
  | root f p =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact .root hc.1 hc.2
    next => contradiction
  | leave =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact .leave hc
    next => contradiction

theorem execute_complete (h : Step s op t) : execute s op = some t := by
  cases h with
  | enter => rfl
  | reserve hf hn => simp [execute, hf, hn]
  | init hp hs => simp [execute, hp]; exact hs
  | root hf hp => simp [execute, hf, hp]
  | leave hd => simp [execute, hd]

theorem execute_iff : execute s op = some t ↔ Step s op t :=
  ⟨execute_sound, execute_complete⟩

private theorem ref_install (h : RefSafe s f q)
    (same : ∀ old, s.cells p = some old → old.frame = c.frame) :
    RefSafe (install s p c) f q := by
  unfold RefSafe at *
  by_cases hqp : q = p
  · subst q
    cases hp : s.cells p with
    | none => simp [hp] at h
    | some old =>
      have he := same old hp
      simpa [install, hp, he] using h
  · simpa [install, hqp] using h

private theorem install_valid (v : Valid s)
    (live : c.frame ≤ s.depth)
    (edges : ∀ q ∈ c.fields.getD [], RefSafe s c.frame q)
    (same : ∀ old, s.cells p = some old → old.frame = c.frame) :
    Valid (install s p c) := by
  constructor
  · intro q obj h
    by_cases hqp : q = p
    · subst q
      exact Nat.lt_of_lt_of_le (Nat.lt_succ_self p) (Nat.le_max_right _ _)
    · have ho : s.cells q = some obj := by simpa [install, hqp] using h
      have hn := v.fresh q obj ho
      exact Nat.lt_of_lt_of_le hn (Nat.le_max_left _ _)
  · intro q obj h
    by_cases hqp : q = p
    · subst q
      simp [install] at h
      subst obj
      exact live
    · exact v.live q obj (by simpa [install, hqp] using h)
  · intro q obj h r hr
    apply ref_install (s := s) (same := same)
    by_cases hqp : q = p
    · subst q
      simp [install] at h
      subst obj
      exact edges r hr
    · exact v.edges q obj (by simpa [install, hqp] using h) r hr
  · intro r hr
    obtain ⟨hl, hs⟩ := v.roots r hr
    exact ⟨hl, ref_install hs same⟩

private theorem ref_reset (hs : RefSafe s f q) (hf : f < s.depth) :
    RefSafe (reset s) f q := by
  unfold RefSafe at *
  cases hq : s.cells q with
  | none => simp [hq] at hs
  | some c =>
    have hc : c.frame ≤ f := by simpa [hq] using hs
    have hd : c.frame < s.depth := Nat.lt_of_le_of_lt hc hf
    simpa [reset, Option.filter, hq, hd] using hc

/-- The core reclamation lemma: surviving holders cannot target reclaimed storage. -/
theorem reset_valid (v : Valid s) (_hd : 0 < s.depth) : Valid (reset s) := by
  have retained : ∀ p c, (reset s).cells p = some c →
      s.cells p = some c ∧ c.frame < s.depth := by
    intro p c h
    cases hp : s.cells p with
    | none => simp [reset, hp, Option.filter] at h
    | some old =>
      by_cases hf : old.frame < s.depth
      · simp [reset, hp, Option.filter, hf] at h
        subst c
        exact ⟨rfl, hf⟩
      · simp [reset, hp, Option.filter, hf] at h
  constructor
  · intro p c h
    exact v.fresh p c (retained p c h).1
  · intro p c h
    have hc := (retained p c h).2
    exact Nat.le_sub_one_of_lt hc
  · intro p c h q hq
    obtain ⟨hp, hf⟩ := retained p c h
    exact ref_reset (v.edges p c hp q hq) hf
  · intro r hr
    have hm : r ∈ s.roots ∧ r.frame < s.depth := by
      simpa [reset] using hr
    obtain ⟨_, hs⟩ := v.roots r hm.1
    constructor
    · exact Nat.le_sub_one_of_lt hm.2
    · exact ref_reset hs hm.2

theorem preservation (v : Valid s) (h : Step s op t) : Valid t := by
  cases h with
  | enter =>
    constructor
    · exact v.fresh
    · intro p c hp
      have hc := v.live p c hp
      exact Nat.le_step hc
    · exact v.edges
    · intro r hr
      obtain ⟨hf, hs⟩ := v.roots r hr
      exact ⟨Nat.le_step hf, hs⟩
  | reserve hf hn =>
    apply install_valid v hf
    · simp
    · intro old ho; simp [hn] at ho
  | init hp hs =>
    apply install_valid v
    · have hl := v.live _ _ hp; exact hl
    · exact hs
    · intro old ho
      rw [hp] at ho
      cases ho
      rfl
  | root hf hp =>
    constructor
    · exact v.fresh
    · exact v.live
    · exact v.edges
    · intro r hr
      simp only [addRoot, List.mem_cons] at hr
      rcases hr with rfl | hr
      · exact ⟨hf, hp⟩
      · exact v.roots r hr
  | leave hd => exact reset_valid v hd

/-- Every accepted operation preserves the lifetime and reference invariants. -/
theorem accepted_preserves (v : Valid s) (h : execute s op = some t) : Valid t :=
  preservation v (execute_sound h)

end Fixed.Memory
