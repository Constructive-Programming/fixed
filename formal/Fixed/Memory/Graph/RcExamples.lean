import Fixed.Memory.Graph.RcRefinement

namespace Fixed.Memory.Graph.Rc.Examples

-- A duplicated root increments the header; the first drop only decrements it.
example : (run initial [.alloc [], .dup 0, .drop 0]).map
    (fun s => (s.roots, (s.cells 0).map Cell.count, references s 0)) =
    some ([0], some 1, 1) := by decide

-- Duplicate constructor fields increment the child once per occurrence.
example : (run initial [.alloc [], .alloc [0, 0]]).map
    (fun s => ((s.cells 0).map Cell.count, (s.cells 1).map Cell.count,
      references s 0, references s 1)) = some (some 3, some 1, 3, 1) := by decide

-- Recursive cleanup drains both duplicate field edges without prematurely freeing the child.
example : (run initial [.alloc [], .alloc [0], .alloc [1, 1], .drop 2]).map
    (fun s => (s.roots, (s.cells 0).map Cell.count, (s.cells 1).map Cell.count,
      s.cells 2)) = some ([1, 0], some 2, some 1, none) := by decide

-- Dropping the remaining parent releases its child edge but retains the original grandchild root.
example : (run initial [.alloc [], .alloc [0], .alloc [1, 1], .drop 2, .drop 1]).map
    (fun s => (s.roots, (s.cells 0).map Cell.count, s.cells 1, s.cells 2)) =
    some ([0], some 1, none, none) := by decide

-- The last root drop recursively frees the remaining object.
example : (run initial [.alloc [], .alloc [0], .drop 1, .drop 0]).map
    (fun s => (s.roots, s.cells 0)) = some ([], none) := by decide

-- Every reachable header equals the independently computed incoming multiplicity.
example : (run initial [.alloc [], .dup 0, .alloc [0, 0]]).map
    (fun s => ((s.cells 0).map Cell.count, references s 0,
      (s.cells 1).map Cell.count, references s 1)) =
    some (some 4, 4, some 1, 1) := by decide

-- Missing and stale roots are rejected.
example : (run initial [.drop 0]).isNone = true := by decide
example : (run initial [.alloc [], .drop 0, .drop 0]).isNone = true := by decide
example : (run initial [.alloc [0]]).isNone = true := by decide

end Fixed.Memory.Graph.Rc.Examples
