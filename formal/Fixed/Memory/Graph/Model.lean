import Fixed.Memory.Heap.Accounting

/- Immutable heap DAGs with owned fields (PR3.2, PR7.1). Roots and fields
   are multisets of owning references: repeated addresses are distinct edges.
   This extends the ownership idea, not the opaque Heap.State representation;
   scope/borrow composition and concrete reference-count headers remain separate. -/
namespace Fixed.Memory.Graph

structure State where
  next : Addr
  cells : Addr → Option (List Addr)
  roots : List Addr

def initial : State := ⟨0, fun _ => none, []⟩

/-- Each root occurrence and each field occurrence owns one reference. -/
def Incoming (s : State) (p : Addr) : Prop :=
  p ∈ s.roots ∨ ∃ parent fields, s.cells parent = some fields ∧ p ∈ fields

structure Valid (s : State) : Prop where
  fresh : ∀ p fields, s.cells p = some fields → p < s.next
  edges : ∀ p fields, s.cells p = some fields → ∀ q ∈ fields,
    q < p ∧ ∃ children, s.cells q = some children
  roots : ∀ p ∈ s.roots, ∃ fields, s.cells p = some fields
  owned : ∀ p fields, s.cells p = some fields → Incoming s p

theorem initial_valid : Valid initial := by
  constructor <;> simp [initial]

/-- Executable support query. Multiplicity is preserved by the lists even
    though reclamation only needs to know whether any reference remains. -/
def hasIncoming (s : State) (p : Addr) : Bool :=
  s.roots.contains p || (List.range s.next).any
    (fun parent => (s.cells parent).any (fun fields => fields.contains p))

theorem hasIncoming_iff (fresh : ∀ q fields, s.cells q = some fields → q < s.next) :
    hasIncoming s p = true ↔ Incoming s p := by
  simp only [hasIncoming, Bool.or_eq_true, List.contains_iff, List.any_eq_true,
    List.mem_range, Heap.option_any_iff]
  constructor
  · rintro (hp | ⟨q, _, fields, hq, hp⟩)
    · exact Or.inl hp
    · exact Or.inr ⟨q, fields, hq, by simpa using hp⟩
  · rintro (hp | ⟨q, fields, hq, hp⟩)
    · exact Or.inl hp
    · exact Or.inr ⟨q, fresh q fields hq, fields, hq, by simpa using hp⟩

/-- Construction duplicates one owned reference per field occurrence, keeping
    the supplied roots. All children must already be rooted, completed objects. -/
def alloc (s : State) (fields : List Addr) : State :=
  { next := s.next + 1
    cells := fun p => if p = s.next then some fields else s.cells p
    roots := s.next :: s.roots }

def dup (s : State) (p : Addr) : State := { s with roots := p :: s.roots }

def removeRoot (s : State) (p : Addr) : State := { s with roots := s.roots.erase p }

/-- Last-owner teardown transfers the outgoing edge obligations to temporary
    roots before deleting the parent. Recursive cleanup drains this prefix. -/
def unlink (s : State) (p : Addr) (fields : List Addr) : State :=
  { s with
    cells := fun q => if q = p then none else s.cells q
    roots := fields ++ s.roots.erase p }

/- Declarative recursive drop semantics, independent of the depth budget. -/
mutual
  inductive Release : State → Addr → State → Prop where
    | shared : p ∈ s.roots → s.cells p = some fields → Incoming (removeRoot s p) p →
        Release s p (removeRoot s p)
    | last : p ∈ s.roots → s.cells p = some fields → ¬ Incoming (removeRoot s p) p →
        Releases (unlink s p fields) fields t → Release s p t
  inductive Releases : State → List Addr → State → Prop where
    | nil : Releases s [] s
    | cons : Release s p u → Releases u ps t → Releases s (p :: ps) t
end

/-- Fuel bounds recursive depth, not the number of edges. The verified wrapper
    supplies next; release_total proves that budget suffices for every valid DAG. -/
def releaseFuel : Nat → State → Addr → Option State
  | 0, _, _ => none
  | fuel + 1, s, p =>
    if p ∈ s.roots then
      match s.cells p with
      | none => none
      | some fields =>
        if hasIncoming (removeRoot s p) p then some (removeRoot s p)
        else fields.foldlM (fun t q => releaseFuel fuel t q) (unlink s p fields)
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

end Fixed.Memory.Graph
