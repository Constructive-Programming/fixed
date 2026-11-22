/- A deliberately small, extrinsically typed, total core of Fixed.
   De Bruijn indices; left-to-right call-by-value reduction. -/
namespace Fixed

inductive Ty where
  | unit | bool | empty
  | arr : Ty → Ty → Ty
  deriving DecidableEq, Repr

inductive Term where
  | var : Nat → Term
  | unit : Term
  | bool : Bool → Term
  | lam : Ty → Term → Term
  | app : Term → Term → Term
  | ite : Term → Term → Term → Term
  | absurd : Ty → Term → Term
  deriving DecidableEq, Repr

abbrev Ctx := Nat → Option Ty

def extend (Γ : Ctx) (a : Ty) : Ctx
  | 0 => some a
  | n + 1 => Γ n

def closed : Ctx := fun _ => none

inductive HasType : Ctx → Term → Ty → Prop where
  | var : Γ n = some a → HasType Γ (.var n) a
  | unit : HasType Γ .unit .unit
  | bool : HasType Γ (.bool b) .bool
  | lam : HasType (extend Γ a) e b → HasType Γ (.lam a e) (.arr a b)
  | app : HasType Γ f (.arr a b) → HasType Γ x a → HasType Γ (.app f x) b
  | ite : HasType Γ c .bool → HasType Γ t a → HasType Γ f a → HasType Γ (.ite c t f) a
  | absurd : HasType Γ e .empty → HasType Γ (.absurd a e) a

def liftRen (ρ : Nat → Nat) : Nat → Nat
  | 0 => 0
  | n + 1 => ρ n + 1

def rename (ρ : Nat → Nat) : Term → Term
  | .var n => .var (ρ n)
  | .unit => .unit
  | .bool b => .bool b
  | .lam a e => .lam a (rename (liftRen ρ) e)
  | .app f x => .app (rename ρ f) (rename ρ x)
  | .ite c t f => .ite (rename ρ c) (rename ρ t) (rename ρ f)
  | .absurd a e => .absurd a (rename ρ e)

theorem rename_typing (h : HasType Γ e a)
    (hr : ∀ n t, Γ n = some t → Δ (ρ n) = some t) :
    HasType Δ (rename ρ e) a := by
  induction h generalizing Δ ρ with
  | var h => exact .var (hr _ _ h)
  | unit => exact .unit
  | bool => exact .bool
  | lam h ih =>
    apply HasType.lam
    apply ih
    intro n t hn
    cases n with
    | zero => exact hn
    | succ n => exact hr n t hn
  | app _ _ ihf ihx => exact .app (ihf hr) (ihx hr)
  | ite _ _ _ ihc iht ihf => exact .ite (ihc hr) (iht hr) (ihf hr)
  | absurd _ ih => exact .absurd (ih hr)

def liftSub (σ : Nat → Term) : Nat → Term
  | 0 => .var 0
  | n + 1 => rename Nat.succ (σ n)

def subst (σ : Nat → Term) : Term → Term
  | .var n => σ n
  | .unit => .unit
  | .bool b => .bool b
  | .lam a e => .lam a (subst (liftSub σ) e)
  | .app f x => .app (subst σ f) (subst σ x)
  | .ite c t f => .ite (subst σ c) (subst σ t) (subst σ f)
  | .absurd a e => .absurd a (subst σ e)

theorem substitution (h : HasType Γ e a)
    (hs : ∀ n t, Γ n = some t → HasType Δ (σ n) t) :
    HasType Δ (subst σ e) a := by
  induction h generalizing Δ σ with
  | var h => exact hs _ _ h
  | unit => exact .unit
  | bool => exact .bool
  | lam h ih =>
    apply HasType.lam
    apply ih
    intro n t hn
    cases n with
    | zero =>
      simp only [extend, Option.some.injEq] at hn
      subst t
      exact .var rfl
    | succ n => exact rename_typing (hs n t hn) (fun _ _ h => h)
  | app _ _ ihf ihx => exact .app (ihf hs) (ihx hs)
  | ite _ _ _ ihc iht ihf => exact .ite (ihc hs) (iht hs) (ihf hs)
  | absurd _ ih => exact .absurd (ih hs)

def single (v : Term) : Nat → Term
  | 0 => v
  | n + 1 => .var n

theorem beta_typing (h : HasType (extend Γ a) e b) (hv : HasType Γ v a) :
    HasType Γ (subst (single v) e) b := by
  apply substitution h
  intro n t hn
  cases n with
  | zero =>
    simp only [extend, Option.some.injEq] at hn
    subst t
    exact hv
  | succ n => exact .var hn

inductive Value : Term → Prop where
  | unit : Value .unit
  | bool : Value (.bool b)
  | lam : Value (.lam a e)

inductive Step : Term → Term → Prop where
  | beta : Value v → Step (.app (.lam a e) v) (subst (single v) e)
  | appLeft : Step f f' → Step (.app f x) (.app f' x)
  | appRight : Value f → Step x x' → Step (.app f x) (.app f x')
  | ifTrue : Step (.ite (.bool true) t f) t
  | ifFalse : Step (.ite (.bool false) t f) f
  | ifCond : Step c c' → Step (.ite c t f) (.ite c' t f)
  | absurd : Step e e' → Step (.absurd a e) (.absurd a e')

theorem preservation (h : HasType Γ e a) (s : Step e e') : HasType Γ e' a := by
  induction s generalizing a with
  | beta _ => cases h with | app hf hx => cases hf with | lam hb => exact beta_typing hb hx
  | appLeft _ ih => cases h with | app hf hx => exact .app (ih hf) hx
  | appRight _ _ ih => cases h with | app hf hx => exact .app hf (ih hx)
  | ifTrue => cases h with | ite _ ht _ => exact ht
  | ifFalse => cases h with | ite _ _ hf => exact hf
  | ifCond _ ih => cases h with | ite hc ht hf => exact .ite (ih hc) ht hf
  | absurd _ ih => cases h with | absurd he => exact .absurd (ih he)

theorem canonical_arr (h : HasType Γ e (.arr a b)) (v : Value e) :
    ∃ body, e = .lam a body := by
  cases v <;> cases h
  exact ⟨_, rfl⟩

theorem canonical_bool (h : HasType Γ e .bool) (v : Value e) :
    ∃ b, e = .bool b := by
  cases v <;> cases h
  exact ⟨_, rfl⟩

theorem no_empty_value (h : HasType Γ e .empty) (v : Value e) : False := by
  cases v <;> cases h

theorem progress (h : HasType closed e a) : Value e ∨ ∃ e', Step e e' := by
  generalize hg : closed = Γ at h
  induction h with
  | var hv => rw [← hg] at hv; cases hv
  | unit => exact .inl .unit
  | bool => exact .inl .bool
  | lam => exact .inl .lam
  | app hf hx ihf ihx =>
    rcases ihf hg with vf | ⟨f', sf⟩
    · rcases ihx hg with vx | ⟨x', sx⟩
      · obtain ⟨body, rfl⟩ := canonical_arr hf vf
        exact .inr ⟨_, .beta vx⟩
      · exact .inr ⟨_, .appRight vf sx⟩
    · exact .inr ⟨_, .appLeft sf⟩
  | ite hc ht hf ihc _ _ =>
    rcases ihc hg with vc | ⟨c', sc⟩
    · obtain ⟨b, rfl⟩ := canonical_bool hc vc
      cases b
      · exact .inr ⟨_, .ifFalse⟩
      · exact .inr ⟨_, .ifTrue⟩
    · exact .inr ⟨_, .ifCond sc⟩
  | absurd he ih =>
    rcases ih hg with ve | ⟨e', se⟩
    · exact (no_empty_value he ve).elim
    · exact .inr ⟨_, .absurd se⟩

inductive Steps : Term → Term → Prop where
  | refl : Steps e e
  | next : Step e e' → Steps e' e'' → Steps e e''

theorem preservation_many (h : HasType Γ e a) (s : Steps e e') : HasType Γ e' a := by
  induction s with
  | refl => exact h
  | next s _ ih => exact ih (preservation h s)

/-- Every finitely reachable state of a closed typed term is a value or can step. -/
theorem soundness (h : HasType closed e a) (s : Steps e e') :
    Value e' ∨ ∃ e'', Step e' e'' := progress (preservation_many h s)

/-- A propositional model, used for consistency, not an evaluator or normalization proof. -/
def Meaning : Ty → Prop
  | .unit => True
  | .bool => True
  | .empty => False
  | .arr a b => Meaning a → Meaning b

theorem logical_soundness (h : HasType Γ e a)
    (env : ∀ n t, Γ n = some t → Meaning t) : Meaning a := by
  induction h with
  | var h => exact env _ _ h
  | unit => trivial
  | bool => trivial
  | lam _ ih =>
    intro ha
    apply ih
    intro n t hn
    cases n with
    | zero =>
      simp only [extend, Option.some.injEq] at hn
      subst t
      exact ha
    | succ n => exact env n t hn
  | app _ _ ihf ihx => exact ihf env (ihx env)
  | ite _ _ _ _ iht _ => exact iht env
  | absurd _ ih => exact (ih env).elim

/-- No closed term of the total core inhabits the empty type. -/
theorem consistency : ¬ HasType closed e .empty := by
  intro h
  exact logical_soundness h (by intro n t hn; cases hn)

end Fixed
