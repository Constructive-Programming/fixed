import Fixed.Memory.Resource.CoreErasure

namespace Fixed.Memory.Resource.Examples

def leaf : Object := ⟨[], none⟩
def parent (p : Addr) : Object := ⟨[.ref p], none⟩
def closure (body : RuntimeTerm) (env : List Value) : Object :=
  ⟨env, some (.unit, body)⟩

-- Literal frame storage and erasure do not allocate or change RC headers.
example : (run initial [.literal .unit, .literal (.bool true), .erase 0, .erase 1]).map
    (fun s => (s.frames.head?.map Frame.slots, s.rc.next, s.rc.roots)) =
    some (some [none, none], 0, []) := by decide

-- Allocation roots an object in the current frame; duplication increments its header.
example : (run initial [.alloc leaf, .dup 0]).map
    (fun s => (s.frames.head?.map Frame.slots, (s.rc.cells 0).map Graph.Rc.Cell.count)) =
    some (some [some (.ref 0), some (.ref 0)], some 2) := by decide

-- Consuming moves an owned slot without changing the count; both aliases remain owned.
example : (run initial [.alloc leaf, .dup 0, .consume 1]).map
    (fun s => (s.frames.head?.map Frame.slots, (s.rc.cells 0).map Graph.Rc.Cell.count)) =
    some (some [some (.ref 0), some (.ref 0), none], some 2) := by decide

-- Heap fields recursively drop, while the child's independent frame root survives.
example : (run initial [.alloc leaf, .alloc (parent 0), .erase 0]).map
    (fun s => (s.frames.head?.map Frame.slots, (s.rc.cells 0).map Graph.Rc.Cell.count,
      s.rc.cells 1)) = some (some [none, some (.ref 0)], some 1, none) := by decide

-- Scope exit drains exactly the inner frame's roots and retains the outer object.
example : (run initial [.alloc leaf, .enter, .alloc leaf, .leave]).map
    (fun s => (s.frames.length, s.frames.head?.map Frame.slots,
      (s.rc.cells 0).map Graph.Rc.Cell.count, s.rc.roots)) =
    some (1, some [some (.ref 0)], some 1, [0]) := by decide

-- An outer borrow survives inner scope exit and remains live.
example : (run initial [.alloc leaf, .borrow 0, .enter, .leave]).map
    (fun s => (s.borrows, (s.rc.cells 0).map Graph.Rc.Cell.count)) =
    some ([⟨0, 0⟩], some 1) := by decide

-- Explicitly releasing a still-borrowed object is rejected.
example : (run initial [.alloc leaf, .borrow 0, .erase 0]).isNone = true := by decide

-- Closure metadata survives allocation and points at the same owned environment.
example : (run initial [.alloc leaf, .alloc (closure (.var 0) [.ref 0])]).map
    (fun s => (s.objects 1, (s.rc.cells 1).map Graph.Rc.Cell.fields)) =
    some (some (closure (.var 0) [.ref 0]), some [0]) := by decide

-- Base-scope exit, stale slots, and unrooted heap fields are rejected.
example : (run initial [.leave]).isNone = true := by decide
example : (run initial [.erase 0]).isNone = true := by decide
example : (run initial [.alloc (parent 0)]).isNone = true := by decide

-- Core evaluation allocates a closure and a literal, then performs beta reduction.
example : (runSteps initial (ofCore (.app (.lam .bool (.var 0)) (.bool true))) 3).2 =
    .value (.bool true) := by decide

-- Substitution under a binder inserts a closed runtime value without capture.
example : eraseTerm (runSteps initial
    (ofCore (.app (.lam .bool (.lam .unit (.var 1))) (.bool false))) 3).2 =
    some (.lam .unit (.bool false)) := by decide

-- A further capture step turns the substituted lambda into a closure value.
example : eraseTerm (runSteps initial
    (ofCore (.app (.lam .bool (.lam .unit (.var 1))) (.bool false))) 4).2 =
    some (.lam .unit (.bool false)) := by decide

-- Branch selection erases to the corresponding core small step.
example : eraseTerm (runSteps initial
    (ofCore (.ite (.bool true) (.lam .unit .unit) (.lam .bool (.var 0)))) 3).2 =
    some (.lam .unit .unit) := by decide

end Fixed.Memory.Resource.Examples
