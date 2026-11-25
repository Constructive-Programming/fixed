import Fixed.Memory.Resource.Preservation
import Fixed.Quantity

namespace Fixed.Memory.Resource

mutual
  def eraseValue : Value → Option Fixed.Term
    | .unit => some .unit
    | .bool b => some (.bool b)
    | .ref _ => none
    | .closure _ a body => (eraseTerm body).map (.lam a)
  def eraseTerm : RuntimeTerm → Option Fixed.Term
    | .value v => eraseValue v
    | .var n => some (.var n)
    | .unit => some .unit
    | .bool b => some (.bool b)
    | .lam a body => (eraseTerm body).map (.lam a)
    | .app f x => (eraseTerm f).bind fun f => (eraseTerm x).map fun x => .app f x
    | .ite c t f => (eraseTerm c).bind fun c => (eraseTerm t).bind fun t =>
        (eraseTerm f).map fun f => .ite c t f
    | .absurd a body => (eraseTerm body).map (.absurd a)
end

def ofCore : Fixed.Term → RuntimeTerm
  | .var n => .var n
  | .unit => .unit
  | .bool b => .bool b
  | .lam a body => .lam a (ofCore body)
  | .app f x => .app (ofCore f) (ofCore x)
  | .ite c t f => .ite (ofCore c) (ofCore t) (ofCore f)
  | .absurd a body => .absurd a (ofCore body)

def occurrences (n : Nat) : RuntimeTerm → Nat
  | .value _ => 0
  | .var m => if n = m then 1 else 0
  | .unit | .bool _ => 0
  | .lam _ body => occurrences (n + 1) body
  | .app f x => occurrences n f + occurrences n x
  | .ite c t f => occurrences n c + occurrences n t + occurrences n f
  | .absurd _ body => occurrences n body

def replace (n : Nat) (v : Value) : RuntimeTerm → RuntimeTerm
  | .value w => .value w
  | .var m => if n = m then .value v else .var m
  | .unit => .unit
  | .bool b => .bool b
  | .lam a body => .lam a (replace (n + 1) v body)
  | .app f x => .app (replace n v f) (replace n v x)
  | .ite c t f => .ite (replace n v c) (replace n v t) (replace n v f)
  | .absurd a body => .absurd a (replace n v body)

def sourceValue : RuntimeTerm → Bool
  | .value _ | .unit | .bool _ | .lam _ _ => true
  | _ => false

def reframe (s : State) (e : RuntimeTerm) : State :=
  { s with frames := replaceCurrent s.frames (e.values.map some) }

def literalState (s : State) (v : Value) : State := reframe (dup s v) (.value v)

def dupAll (rc : Graph.Rc.State) (ps : List Addr) : Graph.Rc.State :=
  ps.foldl (fun t p => Graph.Rc.dup t p) rc

def releaseValue (rc : Graph.Rc.State) (v : Value) : Option Graph.Rc.State :=
  match v.addr with
  | some p => Graph.Rc.releaseFuel rc.next rc p
  | none => some rc

def captureState (s : State) (a : Fixed.Ty) (body : RuntimeTerm)
    (rc : Graph.Rc.State) : State :=
  let base := alloc s ⟨[], some (a, body)⟩
  reframe { base with rc := rc, objects := syncObjects base rc }
    (.value (.closure s.rc.next a body))

def betaState (s : State) (body : RuntimeTerm) (v : Value) (rc : Graph.Rc.State) : State :=
  reframe { s with rc := rc, objects := syncObjects s rc } (replace 0 v body)

def selectState (s : State) (target : RuntimeTerm) (rc : Graph.Rc.State) : State :=
  reframe { s with rc := rc, objects := syncObjects s rc } target

/-- One checked small step. Literal and closure allocation stutter under erasure;
    every other rule mirrors `Fixed.Step`. -/
def executeStep (s : State) : RuntimeTerm → Option (State × RuntimeTerm)
  | .value _ | .var _ => none
  | .unit => some (literalState s .unit, .value .unit)
  | .bool b => some (literalState s (.bool b), .value (.bool b))
  | .lam a body =>
    let rc := Graph.Rc.alloc s.rc body.refs
    (releaseList rc body.refs).map fun rc => (captureState s a body rc, .value (.closure s.rc.next a body))
  | .app f x =>
    match f with
    | .value (.closure p _ body) =>
      match x with
      | .value v =>
        let rc := dupAll s.rc body.refs
        let rc := dupAll rc ((List.replicate (occurrences 0 body).pred v.refs).flatten)
        (Graph.Rc.releaseFuel rc.next rc p).bind fun rc =>
          (if occurrences 0 body = 0 then releaseValue rc v else some rc).map fun rc =>
            (betaState s body v rc, replace 0 v body)
      | _ => (executeStep s x).map fun (t, x) => (reframe t (.app f x), .app f x)
    | _ =>
      match executeStep s f with
      | some (t, f) => some (reframe t (.app f x), .app f x)
      | none =>
        if sourceValue f then (executeStep s x).map fun (t, x) => (reframe t (.app f x), .app f x)
        else none
  | .ite c t f =>
    match c with
    | .value (.bool true) => (releaseList s.rc f.refs).map fun rc => (selectState s t rc, t)
    | .value (.bool false) => (releaseList s.rc t.refs).map fun rc => (selectState s f rc, f)
    | _ => (executeStep s c).map fun (u, c) => (reframe u (.ite c t f), .ite c t f)
  | .absurd a body => (executeStep s body).map fun (t, body) =>
      (reframe t (.absurd a body), .absurd a body)

def runSteps (s : State) (e : RuntimeTerm) : Nat → State × RuntimeTerm
  | 0 => (s, e)
  | fuel + 1 =>
    match executeStep s e with
    | none => (s, e)
    | some (t, e') => runSteps (reframe t e') e' fuel

def KnownClosure (s : State) (v : Value) : Prop :=
  ∀ p a body, v = .closure p a body →
    ∃ o, s.objects p = some o ∧ o.code = some (a, body)

structure EffectValid (s : State) : Prop where
  rc : Graph.Rc.Valid s.rc
  frames : 0 < s.frames.length
  objects : ∀ p o, s.objects p = some o → ∃ c, s.rc.cells p = some c ∧ c.fields = o.refs
  complete : ∀ p c, s.rc.cells p = some c → ∃ o, s.objects p = some o
  borrows : s.borrows = []
  objectClosures : ∀ p o, s.objects p = some o → ∀ a body, o.code = some (a, body) →
    ∀ v ∈ body.values, KnownClosure s v

structure TermValid (s : State) (e : RuntimeTerm) : Prop where
  effect : EffectValid s
  live : ∀ q, e.refs.count q ≤ s.rc.roots.count q
  closures : ∀ v ∈ e.values, KnownClosure s v

structure ConfigValid (s : State) (e : RuntimeTerm) : Prop where
  effect : EffectValid s
  roots : ∀ p, s.rc.roots.count p = (frameRoots s.frames).count p
  current : ∃ f outer, s.frames = f :: outer ∧ f.slots = e.values.map some
  closures : ∀ v ∈ e.values, KnownClosure s v

end Fixed.Memory.Resource
