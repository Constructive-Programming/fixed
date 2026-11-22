import Fixed.Memory.Graph.RcRefinement
import Fixed.Core

namespace Fixed.Memory.Resource

mutual
  inductive Value where
    | unit
    | bool (value : Bool)
    | ref (target : Addr)
    | closure (target : Addr) (arg : Fixed.Ty) (body : RuntimeTerm)
    deriving DecidableEq, Repr
  inductive RuntimeTerm where
    | value (value : Value)
    | var (index : Nat)
    | unit
    | bool (value : Bool)
    | lam (arg : Fixed.Ty) (body : RuntimeTerm)
    | app (fn arg : RuntimeTerm)
    | ite (cond thenBranch elseBranch : RuntimeTerm)
    | absurd (type : Fixed.Ty) (body : RuntimeTerm)
    deriving DecidableEq, Repr
end

def Value.addr : Value → Option Addr
  | .ref p | .closure p _ _ => some p
  | _ => none

def Value.refs : Value → List Addr
  | .ref p | .closure p _ _ => [p]
  | _ => []

def RuntimeTerm.refs : RuntimeTerm → List Addr
  | .value v => v.refs
  | .var _ | .unit | .bool _ => []
  | .lam _ body => body.refs
  | .app f x => f.refs ++ x.refs
  | .ite c t f => c.refs ++ t.refs ++ f.refs
  | .absurd _ body => body.refs

def RuntimeTerm.values : RuntimeTerm → List Value
  | .value v => [v]
  | .var _ | .unit | .bool _ => []
  | .lam _ body => body.values
  | .app f x => f.values ++ x.values
  | .ite c t f => c.values ++ t.values ++ f.values
  | .absurd _ body => body.values

/-- Heap payloads contain immediate values, owned references, and optional
    closure code. Environment and code references both contribute RC edges. -/
structure Object where
  values : List Value
  code : Option (Fixed.Ty × RuntimeTerm)
  deriving DecidableEq, Repr

def Object.refs (o : Object) : List Addr :=
  o.values.filterMap Value.addr ++ (o.code.map (fun code => code.2.refs)).getD []

abbrev Slot := Option Value

def Slot.refs : Slot → List Addr
  | some v => v.refs
  | none => []

structure Frame where
  slots : List Slot
  deriving Repr

def Frame.refs (f : Frame) : List Addr := f.slots.flatMap Slot.refs

def Frame.empty : Frame := ⟨[]⟩

structure Borrow where
  frame : Nat
  target : Addr
  deriving DecidableEq, Repr

structure State where
  frames : List Frame
  rc : Graph.Rc.State
  objects : Addr → Option Object
  borrows : List Borrow

def initial : State := ⟨[Frame.empty], Graph.Rc.initial, fun _ => none, []⟩

def depth (s : State) : Nat := s.frames.length - 1

def frameRoots (frames : List Frame) : List Addr := frames.flatMap Frame.refs

def syncObjects (s : State) (rc : Graph.Rc.State) : Addr → Option Object :=
  fun p => if (rc.cells p).isSome then s.objects p else none

structure Valid (s : State) : Prop where
  rc : Graph.Rc.Valid s.rc
  frames : 0 < s.frames.length
  roots : ∀ p, s.rc.roots.count p = (frameRoots s.frames).count p
  objects : ∀ p o, s.objects p = some o →
    ∃ c, s.rc.cells p = some c ∧ c.fields = o.refs
  complete : ∀ p c, s.rc.cells p = some c → ∃ o, s.objects p = some o
  borrows : ∀ b ∈ s.borrows,
    b.frame < s.frames.length ∧ ∃ c, s.rc.cells b.target = some c

theorem initial_valid : Valid initial := by
  constructor
  · exact Graph.Rc.initial_valid
  · simp [initial, Frame.empty]
  · intro p; rfl
  · intro p o h; contradiction
  · intro p c h; contradiction
  · intro b h; contradiction

def releaseList (rc : Graph.Rc.State) (ps : List Addr) : Option Graph.Rc.State :=
  ps.foldlM (fun t p => Graph.Rc.releaseFuel rc.next t p) rc

def borrowsLive (rc : Graph.Rc.State) (bs : List Borrow) : Bool :=
  bs.all (fun b => (rc.cells b.target).isSome)

def updateFrames (s : State) (frames : List Frame) : State := { s with frames := frames }

def replaceCurrent (frames : List Frame) (slots : List Slot) : List Frame :=
  match frames with
  | [] => []
  | _ :: outer => ⟨slots⟩ :: outer

def alloc (s : State) (o : Object) : State :=
  let rc := Graph.Rc.alloc s.rc o.refs
  let frames := match s.frames with
    | [] => []
    | f :: outer => ⟨some (.ref s.rc.next) :: f.slots⟩ :: outer
  { s with
    frames := frames
    rc := rc
    objects := fun p => if p = s.rc.next then some o else s.objects p }

def dup (s : State) (v : Value) : State :=
  let rc := match v.addr with
    | some p => Graph.Rc.dup s.rc p
    | none => s.rc
  let frames := match s.frames with
    | [] => []
    | f :: outer => ⟨some v :: f.slots⟩ :: outer
  { s with frames := frames, rc := rc }

/-- Move a slot's value to a fresh owned result slot. Root occurrences and RC
    headers do not change: this is ownership movement, not duplication. -/
def consume (s : State) (i : Nat) (v : Value) : State :=
  let frames := match s.frames with
    | [] => []
    | f :: outer => ⟨some v :: f.slots.set i none⟩ :: outer
  { s with frames := frames }

def erase (s : State) (i : Nat) (rc : Graph.Rc.State) : State :=
  let frames := match s.frames with
    | [] => []
    | f :: outer => ⟨f.slots.set i none⟩ :: outer
  { s with
    frames := frames
    rc := rc
    objects := syncObjects s rc }

def borrow (s : State) (p : Addr) : State :=
  { s with borrows := ⟨depth s, p⟩ :: s.borrows }

def enter (s : State) : State := { s with frames := Frame.empty :: s.frames }

def leave (s : State) (outer : List Frame) (rc : Graph.Rc.State) : State :=
  { s with
    frames := outer
    rc := rc
    objects := syncObjects s rc
    borrows := s.borrows.filter (fun b => b.frame < depth s) }

inductive Op where
  | enter
  | literal (value : Value)
  | alloc (object : Object)
  | dup (slot : Nat)
  | consume (slot : Nat)
  | erase (slot : Nat)
  | borrow (slot : Nat)
  | leave
  deriving Repr

def execute (s : State) : Op → Option State
  | .enter => some (enter s)
  | .literal v =>
    match v.addr with
    | some _ => none
    | none => some (dup s v)
  | .alloc o =>
    if s.rc.cells s.rc.next = none ∧ (∀ p ∈ o.refs, p ∈ s.rc.roots) then
      some (alloc s o) else none
  | .dup i =>
    match s.frames with
    | [] => none
    | f :: _ =>
      match f.slots[i]? with
      | some (some v) =>
        match v.addr with
        | some p => if p ∈ s.rc.roots then some (dup s v) else none
        | none => some (dup s v)
      | _ => none
  | .consume i =>
    match s.frames with
    | [] => none
    | f :: _ =>
      match f.slots[i]? with
      | some (some v) => some (consume s i v)
      | _ => none
  | .erase i =>
    match s.frames with
    | [] => none
    | f :: _ =>
      match f.slots[i]? with
      | some (some v) =>
        match v.addr with
        | some p =>
          (Graph.Rc.releaseFuel s.rc.next s.rc p).bind (fun rc =>
            if borrowsLive rc s.borrows then some (erase s i rc) else none)
        | none => some (erase s i s.rc)
      | some none => some (erase s i s.rc)
      | none => none
  | .borrow i =>
    match s.frames with
    | [] => none
    | f :: _ =>
      match f.slots[i]? with
      | some (some v) =>
        match v.addr with
        | some p => some (borrow s p)
        | none => none
      | _ => none
  | .leave =>
    match s.frames with
    | [] => none
    | f :: outer =>
      if 0 < outer.length then
        (releaseList s.rc f.refs).bind (fun rc =>
          let bs := s.borrows.filter (fun b => b.frame < depth s)
          if borrowsLive rc bs then some (leave s outer rc) else none)
      else none

def run (s : State) : List Op → Option State
  | [] => some s
  | op :: ops => (execute s op).bind (fun t => run t ops)

end Fixed.Memory.Resource
