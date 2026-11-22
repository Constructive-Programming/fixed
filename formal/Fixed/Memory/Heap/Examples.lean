import Fixed.Memory.Heap.Accounting
import Fixed.Memory.Combined

namespace Fixed.Memory.Heap.Examples

-- Duplicating shares one object, and dropping one alias retains it.
example : (run initial [.alloc 0, .dup 0 0, .drop 0]).map
    (fun s => (s.heap 0, s.owners 0, s.owners 1, ownershipCount s 0)) =
    some (true, none, some ⟨0, 0⟩, 1) := by decide

-- Dropping the last alias reclaims storage.
example : (run initial [.alloc 0, .dup 0 0, .drop 0, .drop 1]).map
    (fun s => (s.heap 0, ownershipCount s 0)) = some (false, 0) := by decide

-- Inner alias cleanup retains an object also owned by the caller.
example : (run initial [.alloc 0, .enter, .dup 0 1, .leave]).map
    (fun s => (s.depth, s.heap 0, s.owners 0, s.owners 1)) =
    some (0, true, some ⟨0, 0⟩, none) := by decide

-- Several obligations to one object are released separately and exactly once.
example : (run initial [.enter, .alloc 1, .dup 0 1]).map
    (fun s => (dropList s 1, released s (outer s),
      (reset s).heap 0, ownershipCount (reset s) 0)) =
    some ([0, 1], [0, 1], false, 0) := by decide

-- Returning ownership moves the existing ticket to the caller's drop list.
example : (run initial [.enter, .alloc 1, .move 0 0]).map
    (fun s => (dropList s 0, dropList s 1, released s (outer s), (reset s).heap 0)) =
    some ([0], [], [], true) := by decide

-- Moving ownership into a callee changes which frame releases it.
example : (run initial [.alloc 0, .enter, .move 0 1, .leave]).map
    (fun s => (s.heap 0, s.owners 0)) = some (false, none) := by decide

-- Dup into an outer scope also permits survival, without moving the inner ticket.
example : (run initial [.enter, .alloc 1, .dup 0 0, .leave]).map
    (fun s => (s.heap 0, s.owners 0, s.owners 1)) =
    some (true, none, some ⟨0, 0⟩) := by decide

-- Borrows create no ownership, and end on scope exit.
example : (run initial [.alloc 0, .enter, .borrow 0 1, .borrow 0 1]).map
    (fun s => (ownershipCount s 0, s.borrows.length)) = some (1, 2) := by decide
example : (run initial [.alloc 0, .enter, .borrow 0 1, .leave, .drop 0]).map
    (fun s => (s.heap 0, s.borrows)) = some (false, []) := by decide

-- An outer borrow survives inner cleanup and continues blocking explicit drop.
example : (run initial [.alloc 0, .borrow 0 0, .enter, .alloc 1, .leave]).map
    (fun s => (s.heap 0, s.heap 1, s.borrows)) =
    some (true, false, [⟨0, 0⟩]) := by decide

-- Borrow and its owner may both end at the same reset.
example : (run initial [.enter, .alloc 1, .borrow 0 1, .leave]).map
    (fun s => (s.heap 0, s.owners 0, s.borrows)) = some (false, none, []) := by decide

-- Shortening ownership is safe if it still covers every borrow.
example : (run initial [.alloc 0, .enter, .borrow 0 1, .move 0 1, .leave]).map
    (fun s => s.heap 0) = some false := by decide

-- A borrow may not outlive its owning ticket, even with another longer-lived owner.
example : (run initial [.enter, .alloc 1, .borrow 0 0]).isNone = true := by decide
example : (run initial [.enter, .alloc 1, .dup 0 0, .borrow 0 0]).isNone = true := by decide

-- An active borrow blocks dropping its ticket, including when other aliases exist.
example : (run initial [.alloc 0, .borrow 0 0, .drop 0]).isNone = true := by decide
example : (run initial [.alloc 0, .dup 0 0, .borrow 0 0, .drop 0]).isNone = true := by decide
example : (run initial [.alloc 0, .borrow 0 0, .enter, .move 0 1]).isNone = true := by decide

-- Unborrowed aliases can still be released while the borrowed owner remains live.
example : (run initial [.alloc 0, .dup 0 0, .borrow 0 0, .drop 1]).map
    (fun s => (s.heap 0, ownershipCount s 0)) = some (true, 1) := by decide

-- Reject missing/stale tickets, double drop, inactive scopes, and base reset.
example : (run initial [.dup 99 0]).isNone = true := by decide
example : (run initial [.move 99 0]).isNone = true := by decide
example : (run initial [.borrow 99 0]).isNone = true := by decide
example : (run initial [.drop 99]).isNone = true := by decide
example : (run initial [.alloc 0, .drop 0, .drop 0]).isNone = true := by decide
example : (run initial [.alloc 1]).isNone = true := by decide
example : (run initial [.alloc 0, .dup 0 1]).isNone = true := by decide
example : (run initial [.alloc 0, .move 0 1]).isNone = true := by decide
example : (run initial [.alloc 0, .borrow 0 1]).isNone = true := by decide
example : (run initial [.leave]).isNone = true := by decide

-- Both address and ticket identities stay fresh across reuse of a frame depth.
example : (run initial [.enter, .alloc 1, .leave, .enter, .alloc 1]).map
    (fun s => ((s.nextAddr, s.nextTicket), (s.heap 0, s.heap 1), (s.owners 0, s.owners 1))) =
    some ((2, 2), (false, true), (none, some ⟨1, 1⟩)) := by decide
example : (run initial [.enter, .alloc 1, .leave, .enter, .alloc 1, .dup 0 1]).isNone = true := by decide

-- An explicit earlier drop is absent from the later frame cleanup receipts.
example : (run initial [.enter, .alloc 1, .dup 0 1, .drop 0]).map
    (fun s => released s (outer s)) = some [1] := by decide

-- Empty nested scopes have no cleanup obligations.
example : (run initial [.enter, .enter, .leave, .leave]).map
    (fun s => (s.depth, s.nextTicket, released s (outer s))) = some (0, 0, []) := by decide

end Fixed.Memory.Heap.Examples

namespace Fixed.Memory.Combined.Examples

-- The same scope exit reclaims frame cells and discharges heap obligations.
def mixed : List Op := [.frame (.reserve 0), .frame (.init 0 []), .frame (.root 0 0),
    .heap (.alloc 0), .enter, .frame (.reserve 1), .frame (.init 1 [0]),
    .frame (.root 1 1), .heap (.dup 0 1), .heap (.alloc 1),
    .heap (.borrow 0 1), .leave]

example : (run initial mixed).map
    (fun s => (s.frames.depth, s.heap.depth, s.frames.cells 1, s.frames.roots)) =
    some (0, 0, none, [⟨0, 0⟩]) := by decide
example : (run initial mixed).map
    (fun s => (s.heap.heap 0, s.heap.heap 1, s.heap.owners 1, s.heap.borrows)) =
    some (true, false, none, []) := by decide

-- Caller destinations and transferred heap results survive the same exit.
example : (run initial [.enter, .frame (.reserve 0), .frame (.init 0 []),
    .heap (.alloc 1), .heap (.move 0 0), .leave]).map
    (fun s => (s.frames.cells 0, s.heap.heap 0, s.heap.owners 0)) =
    some (some ⟨0, some []⟩, true, some ⟨0, 0⟩) := by decide

-- Component commands cannot change scope independently.
example : (run initial [.frame .enter]).isNone = true := by decide
example : (run initial [.heap .enter]).isNone = true := by decide
example : (run initial [.enter, .frame .leave]).isNone = true := by decide
example : (run initial [.enter, .heap .leave]).isNone = true := by decide
example : (run initial [.leave]).isNone = true := by decide

end Fixed.Memory.Combined.Examples
