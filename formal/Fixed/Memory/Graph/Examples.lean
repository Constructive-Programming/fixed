import Fixed.Memory.Graph.Safety

namespace Fixed.Memory.Graph.Examples

-- Duplicate owned fields are distinct references; cleanup retains the child root.
example : (run initial [.alloc [], .alloc [0], .alloc [1, 1], .drop 2]).map
    (fun s => (s.roots, s.cells 0, s.cells 1, s.cells 2)) =
    some ([1, 0], some [], some [0], none) := by decide

-- Once the duplicate parent is gone, dropping its child recursively releases the grandchild's
-- incoming edge but keeps the original child root and the shared grandchild alive.
example : (run initial [.alloc [], .alloc [0], .alloc [1, 1], .drop 2, .drop 1]).map
    (fun s => (s.roots, s.cells 0, s.cells 1, s.cells 2)) =
    some ([0], some [], none, none) := by decide

-- A final root drop completes the recursive teardown.
example : (run initial [.alloc [], .alloc [0], .alloc [1, 1],
    .drop 2, .drop 1, .drop 0]).map (fun s => (s.roots, s.cells 0)) =
    some ([], none) := by decide

-- Field edges and root aliases share ownership; neither path frees the child prematurely.
example : (run initial [.alloc [], .dup 0, .alloc [0], .drop 1, .drop 0]).map
    (fun s => (s.roots, s.cells 0, s.cells 1)) = some ([0], some [], none) := by decide

-- A branching parent drops fields from left to right and reclaims both child subtrees.
example : (run initial [.alloc [], .alloc [], .alloc [0, 1], .alloc [2], .drop 3, .drop 2]).map
    (fun s => (s.roots, s.cells 0, s.cells 1, s.cells 2, s.cells 3)) =
    some ([1, 0], some [], some [], none, none) := by decide

-- Construction rejects unrooted fields, and release rejects stale or missing roots.
example : (run initial [.alloc [0]]).isNone = true := by decide
example : (run initial [.alloc [], .drop 1]).isNone = true := by decide
example : (run initial [.alloc [], .drop 0, .drop 0]).isNone = true := by decide
example : (run initial [.alloc [], .drop 0, .alloc [0]]).isNone = true := by decide

-- Executable cleanup agrees with the declarative recursive relation.
example : (execute (alloc (alloc initial []) [0]) (.drop 1)).map
    (fun s => (s.roots, s.cells 0, s.cells 1)) = some ([0], some [], none) := by decide

end Fixed.Memory.Graph.Examples
