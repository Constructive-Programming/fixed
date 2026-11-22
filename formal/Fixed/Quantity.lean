/- The finite algebra of spec/quantities.md §3 and fixed/qtt/Quantity.scala.
   This module does NOT yet prove quantity inference or erasure correct. -/
namespace Fixed

inductive Quantity where
  | zero | one | many
  deriving DecidableEq, Repr

namespace Quantity

def add : Quantity → Quantity → Quantity
  | .zero, b => b
  | a, .zero => a
  | _, _ => .many

def mul : Quantity → Quantity → Quantity
  | .zero, _ | _, .zero => .zero
  | .one, b => b
  | a, .one => a
  | .many, .many => .many

def join : Quantity → Quantity → Quantity
  | .many, _ | _, .many => .many
  | .one, _ | _, .one => .one
  | .zero, .zero => .zero

theorem add_comm (a b : Quantity) : add a b = add b a := by cases a <;> cases b <;> rfl
theorem add_assoc (a b c : Quantity) : add (add a b) c = add a (add b c) := by
  cases a <;> cases b <;> cases c <;> rfl
theorem add_zero (a : Quantity) : add a .zero = a := by cases a <;> rfl
theorem mul_comm (a b : Quantity) : mul a b = mul b a := by cases a <;> cases b <;> rfl
theorem mul_assoc (a b c : Quantity) : mul (mul a b) c = mul a (mul b c) := by
  cases a <;> cases b <;> cases c <;> rfl
theorem mul_one (a : Quantity) : mul a .one = a := by cases a <;> rfl
theorem mul_zero (a : Quantity) : mul a .zero = .zero := by cases a <;> rfl
theorem mul_add (a b c : Quantity) : mul a (add b c) = add (mul a b) (mul a c) := by
  cases a <;> cases b <;> cases c <;> rfl
theorem join_comm (a b : Quantity) : join a b = join b a := by cases a <;> cases b <;> rfl
theorem join_assoc (a b c : Quantity) : join (join a b) c = join a (join b c) := by
  cases a <;> cases b <;> cases c <;> rfl
theorem join_self (a : Quantity) : join a a = a := by cases a <;> rfl
theorem join_zero (a : Quantity) : join a .zero = a := by cases a <;> rfl

/-- Usage ordering; exclusive branches use join, sequential uses use add. -/
def Le (a b : Quantity) : Prop := join a b = b

theorem le_refl (a : Quantity) : Le a a := join_self a
theorem le_antisymm (h : Le a b) (k : Le b a) : a = b := by
  unfold Le at *
  rw [join_comm] at k
  exact k.symm.trans h
theorem le_trans (h : Le a b) (k : Le b c) : Le a c := by
  cases a <;> cases b <;> cases c <;> simp_all [Le, join]
theorem join_le_iff (a b c : Quantity) : Le (join a b) c ↔ Le a c ∧ Le b c := by
  cases a <;> cases b <;> cases c <;> simp [Le, join]
theorem add_mono (h : Le a b) (k : Le c d) : Le (add a c) (add b d) := by
  cases a <;> cases b <;> cases c <;> cases d <;> simp_all [Le, add, join]
theorem mul_mono (h : Le a b) (k : Le c d) : Le (mul a c) (mul b d) := by
  cases a <;> cases b <;> cases c <;> cases d <;> simp_all [Le, mul, join]

end Quantity
end Fixed
