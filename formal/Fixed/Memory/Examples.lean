import Fixed.Memory.Safety

namespace Fixed.Memory.Examples

-- The outer cell and root survive; inner cell and root are reclaimed.
def nested : List Op :=
  [.reserve 0, .init 0 [], .root 0 0, .enter,
   .reserve 1, .init 1 [0], .root 1 1, .leave]

example : (run initial nested).map (fun s =>
    (s.depth, s.next, s.cells 0, s.cells 1, s.roots)) =
    some (0, 2, some ⟨0, some []⟩, none, [⟨0, 0⟩]) := by decide

-- Older objects may reference younger objects in the SAME frame (MM4.1).
example : (run initial [.enter, .reserve 1, .reserve 1, .init 1 [],
    .init 0 [1], .root 1 0]).isSome = true := by decide

-- Outer-frame allocation is an abstract destination supplied by a caller.
example : (run initial [.enter, .reserve 0, .init 0 [], .root 0 0, .leave]).map
    (fun s => s.cells 0) = some (some ⟨0, some []⟩) := by decide

-- Returning an inner-frame pointer to an outer-frame root is rejected.
example : (run initial [.enter, .reserve 1, .root 0 0]).isNone = true := by decide

-- The same escape through a field (e.g. an escaping closure capture) is rejected.
example : (run initial [.reserve 0, .enter, .reserve 1, .init 0 [1]]).isNone = true := by decide

-- Reject fabricated pointers, inactive frames, and closing the base frame.
example : (run initial [.reserve 0, .init 0 [99]]).isNone = true := by decide
example : (run initial [.root 0 99]).isNone = true := by decide
example : (run initial [.reserve 1]).isNone = true := by decide
example : (run initial [.leave]).isNone = true := by decide

-- Construction is write-once; initialization of absent storage is rejected.
example : (run initial [.reserve 0, .init 0 [], .init 0 []]).isNone = true := by decide
example : (run initial [.init 0 []]).isNone = true := by decide

-- A reused frame depth must not revive an old address.
example : (run initial [.enter, .reserve 1, .leave, .enter, .reserve 1]).map
    (fun s => (s.next, s.cells 0, s.cells 1)) =
    some (2, none, some ⟨1, none⟩) := by decide
example : (run initial [.enter, .reserve 1, .leave, .enter, .reserve 1,
    .root 1 0]).isNone = true := by decide
example : (run initial [.enter, .reserve 1, .leave, .init 0 []]).isNone = true := by decide

-- Empty inner frames can close; references across multiple nested frames survive.
example : (run initial [.reserve 0, .init 0 [], .enter, .enter,
    .root 2 0, .leave, .leave, .root 0 0]).isSome = true := by decide

end Fixed.Memory.Examples
