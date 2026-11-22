import Fixed.Memory.Safety

/- An abstract ownership protocol for opaque heap objects (MM4.2, PR3.3,
   PR3.4, PR8.2). Tickets identify drop obligations, not machine pointers.
   Heap liveness is stored independently of the ownership ledger. Collection
   scans that ledger; no claim about concrete reference-count headers is made. -/
namespace Fixed.Memory.Heap

abbrev Ticket := Nat

structure Owner where
  frame : Frame
  target : Addr
  deriving DecidableEq, Repr

structure Borrow where
  frame : Frame
  ticket : Ticket
  deriving DecidableEq, Repr

structure State where
  depth : Frame
  nextAddr : Addr
  nextTicket : Ticket
  heap : Addr → Bool
  owners : Ticket → Option Owner
  borrows : List Borrow

def initial : State := ⟨0, 0, 0, fun _ => false, fun _ => none, []⟩

def Backed (s : State) (b : Borrow) : Prop :=
  ∃ o, s.owners b.ticket = some o ∧ o.frame ≤ b.frame

structure Valid (s : State) : Prop where
  fresh : ∀ k o, s.owners k = some o → k < s.nextTicket
  placed : ∀ k o, s.owners k = some o → o.frame ≤ s.depth
  allocated : ∀ p, s.heap p = true → p < s.nextAddr
  owned : ∀ p, s.heap p = true ↔ ∃ k o, s.owners k = some o ∧ o.target = p
  borrowed : ∀ b ∈ s.borrows, b.frame ≤ s.depth ∧ Backed s b

theorem initial_valid : Valid initial := by
  constructor <;> simp [initial]

/-- One fresh ticket, automatically registered on its owning frame's drop list. -/
def addOwner (s : State) (f : Frame) (p : Addr) (bound : Addr) : State :=
  { s with
    nextAddr := bound
    nextTicket := s.nextTicket + 1
    heap := fun q => if q = p then true else s.heap q
    owners := fun k => if k = s.nextTicket then some ⟨f, p⟩ else s.owners k }

def transfer (s : State) (k : Ticket) (o : Owner) (f : Frame) : State :=
  { s with owners := fun j => if j = k then some { o with frame := f } else s.owners j }

def enter (s : State) : State := { s with depth := s.depth + 1 }

def addBorrow (s : State) (b : Borrow) : State := { s with borrows := b :: s.borrows }

/-- Finite ledger query, used only by the abstract collector. -/
def supported (owners : Ticket → Option Owner) (bound : Ticket) (p : Addr) : Bool :=
  (List.range bound).any (fun k => (owners k).any (fun o => o.target == p))

theorem option_any_iff (x : Option α) (f : α → Bool) :
    x.any f = true ↔ ∃ a, x = some a ∧ f a = true := by
  cases x <;> simp [Option.any]

theorem supported_iff : supported owners bound p = true ↔
    ∃ k o, k < bound ∧ owners k = some o ∧ o.target = p := by
  simp only [supported, List.any_eq_true, List.mem_range, option_any_iff,
    beq_iff_eq]
  constructor
  · rintro ⟨k, hk, o, ho, hp⟩
    exact ⟨k, o, hk, ho, hp⟩
  · rintro ⟨k, o, hk, ho, hp⟩
    exact ⟨k, hk, o, ho, hp⟩

def retained (s : State) (keep : Ticket → Bool) : Ticket → Option Owner :=
  fun k => if keep k then s.owners k else none

/-- Remove obligations and free exactly the objects with no remaining owner.
    Surviving borrows and the new depth are supplied by drop/reset. -/
def collect (s : State) (keep : Ticket → Bool) (bs : List Borrow) (depth : Frame) : State :=
  { s with
    depth := depth
    owners := retained s keep
    heap := fun p => s.heap p && supported (retained s keep) s.nextTicket p
    borrows := bs }

def drop (s : State) (k : Ticket) : State :=
  collect s (fun j => j != k) s.borrows s.depth

def outer (s : State) (k : Ticket) : Bool :=
  (s.owners k).any (fun o => o.frame < s.depth)

def reset (s : State) : State :=
  collect s (outer s) (s.borrows.filter (fun b => b.frame < s.depth)) (s.depth - 1)

/-- Observable cleanup receipts: one entry per released ownership obligation. -/
def released (s : State) (keep : Ticket → Bool) : List Ticket :=
  (List.range s.nextTicket).filter (fun k => (s.owners k).isSome && !keep k)

def dropList (s : State) (f : Frame) : List Ticket :=
  (List.range s.nextTicket).filter (fun k => (s.owners k).any (fun o => o.frame == f))

/-- A move may shorten ownership only while it still covers every active borrow. -/
def MoveSafe (s : State) (k : Ticket) (f : Frame) : Prop :=
  ∀ b ∈ s.borrows, b.ticket = k → f ≤ b.frame

instance : Decidable (MoveSafe s k f) := inferInstanceAs
  (Decidable (∀ b ∈ s.borrows, b.ticket = k → f ≤ b.frame))

def Unborrowed (s : State) (k : Ticket) : Prop :=
  ∀ b ∈ s.borrows, b.ticket ≠ k

instance : Decidable (Unborrowed s k) := inferInstanceAs
  (Decidable (∀ b ∈ s.borrows, b.ticket ≠ k))

inductive Op where
  | enter
  | alloc (frame : Frame)
  | dup (ticket : Ticket) (frame : Frame)
  | move (ticket : Ticket) (frame : Frame)
  | borrow (ticket : Ticket) (frame : Frame)
  | drop (ticket : Ticket)
  | leave
  deriving Repr

/-- Local operational rules, independent of the global Valid invariant. -/
inductive Step : State → Op → State → Prop where
  | enter : Step s .enter (enter s)
  | alloc : f ≤ s.depth → s.heap s.nextAddr = false → s.owners s.nextTicket = none →
      Step s (.alloc f) (addOwner s f s.nextAddr (s.nextAddr + 1))
  | dup : s.owners k = some o → f ≤ s.depth → s.owners s.nextTicket = none →
      Step s (.dup k f) (addOwner s f o.target s.nextAddr)
  | move : s.owners k = some o → f ≤ s.depth → MoveSafe s k f →
      Step s (.move k f) (transfer s k o f)
  | borrow : s.owners k = some o → o.frame ≤ f → f ≤ s.depth →
      Step s (.borrow k f) (addBorrow s ⟨f, k⟩)
  | drop : s.owners k = some o → Unborrowed s k → Step s (.drop k) (drop s k)
  | leave : 0 < s.depth → Step s .leave (reset s)

def execute (s : State) : Op → Option State
  | .enter => some (enter s)
  | .alloc f =>
    if f ≤ s.depth ∧ s.heap s.nextAddr = false ∧ s.owners s.nextTicket = none then
      some (addOwner s f s.nextAddr (s.nextAddr + 1)) else none
  | .dup k f =>
    match s.owners k with
    | none => none
    | some o => if f ≤ s.depth ∧ s.owners s.nextTicket = none then
        some (addOwner s f o.target s.nextAddr) else none
  | .move k f =>
    match s.owners k with
    | none => none
    | some o => if f ≤ s.depth ∧ MoveSafe s k f then some (transfer s k o f) else none
  | .borrow k f =>
    match s.owners k with
    | none => none
    | some o => if o.frame ≤ f ∧ f ≤ s.depth then some (addBorrow s ⟨f, k⟩) else none
  | .drop k =>
    match s.owners k with
    | none => none
    | some _ => if Unborrowed s k then some (drop s k) else none
  | .leave => if 0 < s.depth then some (reset s) else none

theorem execute_sound (h : execute s op = some t) : Step s op t := by
  cases op with
  | enter => cases h; exact .enter
  | alloc f =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact .alloc hc.1 hc.2.1 hc.2.2
    next => contradiction
  | dup k f =>
    simp only [execute] at h
    split at h
    next => contradiction
    next o ho =>
      split at h
      next hc => cases h; exact .dup ho hc.1 hc.2
      next => contradiction
  | move k f =>
    simp only [execute] at h
    split at h
    next => contradiction
    next o ho =>
      split at h
      next hc => cases h; exact .move ho hc.1 hc.2
      next => contradiction
  | borrow k f =>
    simp only [execute] at h
    split at h
    next => contradiction
    next o ho =>
      split at h
      next hc => cases h; exact .borrow ho hc.1 hc.2
      next => contradiction
  | drop k =>
    simp only [execute] at h
    split at h
    next => contradiction
    next o ho =>
      split at h
      next hc => cases h; exact .drop ho hc
      next => contradiction
  | leave =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact .leave hc
    next => contradiction

theorem execute_complete (h : Step s op t) : execute s op = some t := by
  cases h with
  | enter => rfl
  | alloc hf hp hk => simp [execute, hf, hp, hk]
  | dup ho hf hk => simp [execute, ho, hf, hk]
  | move ho hf hb => simp [execute, ho, hf, hb]
  | borrow ho hf hd => simp [execute, ho, hf, hd]
  | drop ho hb => simp [execute, ho, hb]
  | leave hd => simp [execute, hd]

theorem execute_iff : execute s op = some t ↔ Step s op t :=
  ⟨execute_sound, execute_complete⟩

end Fixed.Memory.Heap
