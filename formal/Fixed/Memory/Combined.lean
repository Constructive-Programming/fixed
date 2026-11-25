import Fixed.Memory.Heap.Safety

/- Synchronized frame and heap scopes. The stores remain disjoint: heap fields
   and pointer-bearing mixed aggregates are not modeled by this product. -/
namespace Fixed.Memory.Combined

structure State where
  frames : Fixed.Memory.State
  heap : Heap.State

def initial : State := ⟨Fixed.Memory.initial, Heap.initial⟩

structure Valid (s : State) : Prop where
  frames : Fixed.Memory.Valid s.frames
  heap : Heap.Valid s.heap
  synced : s.frames.depth = s.heap.depth

theorem initial_valid : Valid initial :=
  ⟨Fixed.Memory.initial_valid, Heap.initial_valid, rfl⟩

inductive Op where
  | enter
  | frame (op : Fixed.Memory.Op)
  | heap (op : Heap.Op)
  | leave
  deriving Repr

def enter (s : State) : State := ⟨Fixed.Memory.enter s.frames, Heap.enter s.heap⟩

/-- Atomic scope exit: end local borrows, discharge heap obligations, reclaim
    frame storage. This specifies the final state, not instruction scheduling. -/
def reset (s : State) : State := ⟨Fixed.Memory.reset s.frames, Heap.reset s.heap⟩

inductive Step : State → Op → State → Prop where
  | enter : Step s .enter (enter s)
  | frame : Fixed.Memory.Step s.frames op t → t.depth = s.frames.depth →
      Step s (.frame op) ⟨t, s.heap⟩
  | heap : Heap.Step s.heap op t → t.depth = s.heap.depth →
      Step s (.heap op) ⟨s.frames, t⟩
  | leave : 0 < s.frames.depth → 0 < s.heap.depth → Step s .leave (reset s)

/-- Component operations must leave scope depth unchanged. All scope changes
    use the paired operations, so callers cannot reset only one store. -/
def execute (s : State) : Op → Option State
  | .enter => some (enter s)
  | .frame op => (Fixed.Memory.execute s.frames op).bind (fun t =>
      if t.depth = s.frames.depth then some ⟨t, s.heap⟩ else none)
  | .heap op => (Heap.execute s.heap op).bind (fun t =>
      if t.depth = s.heap.depth then some ⟨s.frames, t⟩ else none)
  | .leave => if 0 < s.frames.depth ∧ 0 < s.heap.depth then some (reset s) else none

theorem execute_sound (h : execute s op = some t) : Step s op t := by
  cases op with
  | enter => cases h; exact .enter
  | frame op =>
    cases he : Fixed.Memory.execute s.frames op with
    | none => simp [execute, he] at h
    | some u =>
      simp only [execute, he, Option.bind] at h
      split at h
      next hd => cases h; exact .frame (Fixed.Memory.execute_sound he) hd
      next => contradiction
  | heap op =>
    cases he : Heap.execute s.heap op with
    | none => simp [execute, he] at h
    | some u =>
      simp only [execute, he, Option.bind] at h
      split at h
      next hd => cases h; exact .heap (Heap.execute_sound he) hd
      next => contradiction
  | leave =>
    simp only [execute] at h
    split at h
    next hd => cases h; exact .leave hd.1 hd.2
    next => contradiction

theorem execute_complete (h : Step s op t) : execute s op = some t := by
  cases h with
  | enter => rfl
  | frame hs hd => simp [execute, Fixed.Memory.execute_complete hs, hd]
  | heap hs hd => simp [execute, Heap.execute_complete hs, hd]
  | leave hf hh => simp [execute, hf, hh]

theorem execute_iff : execute s op = some t ↔ Step s op t :=
  ⟨execute_sound, execute_complete⟩

theorem preservation (v : Valid s) (h : Step s op t) : Valid t := by
  cases h with
  | enter => exact ⟨Fixed.Memory.preservation v.frames .enter,
      Heap.preservation v.heap .enter, by simpa [enter, Fixed.Memory.enter, Heap.enter] using v.synced⟩
  | frame hs hd => exact ⟨Fixed.Memory.preservation v.frames hs, v.heap, hd.trans v.synced⟩
  | heap hs hd => exact ⟨v.frames, Heap.preservation v.heap hs, v.synced.trans hd.symm⟩
  | leave hf _ => exact ⟨Fixed.Memory.reset_valid v.frames hf, Heap.reset_valid v.heap,
      by simpa [reset, Fixed.Memory.reset, Heap.reset, Heap.collect] using congrArg (fun n => n - 1) v.synced⟩

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
      exact ih (preservation v (execute_sound he)) h

/-- Both classes of modeled pointers remain live in every accepted mixed trace. -/
theorem accepted_no_dangling (h : run initial ops = some s) :
    (∀ p, Fixed.Memory.Reachable s.frames p →
      ∃ c, s.frames.cells p = some c ∧ c.frame ≤ s.frames.depth) ∧
    (∀ p, Heap.Accessible s.heap p → s.heap.heap p = true) ∧
    (∀ b ∈ s.heap.borrows, ∃ o, s.heap.owners b.ticket = some o ∧
      o.frame ≤ b.frame ∧ s.heap.heap o.target = true) := by
  have v := run_preserves initial_valid h
  exact ⟨fun _ hp => Fixed.Memory.no_dangling v.frames hp,
    fun _ hp => Heap.no_dangling v.heap hp, fun _ hb => Heap.borrow_live v.heap hb⟩

end Fixed.Memory.Combined
