import Fixed.Memory.Resource.Model

namespace Fixed.Memory.Resource

theorem frameRoots_cons (f : Frame) (outer : List Frame) :
    frameRoots (f :: outer) = f.refs ++ frameRoots outer := rfl

theorem frameRefs_set (slots : List Slot) (i : Nat) (old new : Slot)
    (h : slots[i]? = some old) (q : Addr) :
    (Frame.refs ⟨slots.set i new⟩).count q + (Slot.refs old).count q =
      (Frame.refs ⟨slots⟩).count q + (Slot.refs new).count q := by
  induction slots generalizing i with
  | nil => simp at h
  | cons slot slots ih =>
    cases i with
    | zero =>
      cases h
      simp [Frame.refs, List.count_append]
      omega
    | succ i =>
      simp only [List.getElem?_cons_succ, List.set_cons_succ] at h ⊢
      have ih := ih i h
      simp only [Frame.refs, List.flatMap_cons, List.count_append] at ih ⊢
      omega

theorem releaseList_refines (v : Graph.Rc.Valid rc) (h : releaseList rc ps = some t) :
    ps.foldlM (fun u q => Graph.releaseFuel rc.next u q) (Graph.Rc.erase rc) =
      some (Graph.Rc.erase t) ∧ Graph.Rc.Valid t := by
  exact Graph.Rc.releases_refine (fun v h => Graph.Rc.releaseFuel_refines v h) v h

theorem releaseList_relation (v : Graph.Rc.Valid rc) (h : releaseList rc ps = some t) :
    Graph.Releases (Graph.Rc.erase rc) ps (Graph.Rc.erase t) := by
  apply Graph.releasesFuel_sound (fuel := rc.next)
    (fun v h => Graph.releaseFuel_sound v h) v.graph (releaseList_refines v h).1

theorem releaseList_valid (v : Graph.Rc.Valid rc) (h : releaseList rc ps = some t) :
    Graph.Rc.Valid t := (releaseList_refines v h).2

theorem releaseList_roots (v : Graph.Rc.Valid rc) (h : releaseList rc ps = some t) (q : Addr) :
    t.roots.count q + ps.count q = rc.roots.count q :=
  Graph.releases_count (releaseList_relation v h) q

theorem releaseList_subheap (v : Graph.Rc.Valid rc) (h : releaseList rc ps = some t) :
    Graph.Subheap (Graph.Rc.erase rc) (Graph.Rc.erase t) :=
  Graph.releases_subheap (releaseList_relation v h)

theorem release_relation (v : Graph.Rc.Valid rc) (h : Graph.Rc.releaseFuel rc.next rc p = some t) :
    Graph.Release (Graph.Rc.erase rc) p (Graph.Rc.erase t) :=
  Graph.releaseFuel_sound v.graph (Graph.Rc.releaseFuel_refines v h).1

theorem release_valid (v : Graph.Rc.Valid rc) (h : Graph.Rc.releaseFuel rc.next rc p = some t) :
    Graph.Rc.Valid t := (Graph.Rc.releaseFuel_refines v h).2

theorem release_roots (v : Graph.Rc.Valid rc) (h : Graph.Rc.releaseFuel rc.next rc p = some t)
    (q : Addr) : t.roots.count q + [p].count q = rc.roots.count q :=
  Graph.release_count (release_relation v h) q

theorem release_subheap (v : Graph.Rc.Valid rc) (h : Graph.Rc.releaseFuel rc.next rc p = some t) :
    Graph.Subheap (Graph.Rc.erase rc) (Graph.Rc.erase t) :=
  Graph.release_subheap (release_relation v h)

theorem borrowsLive_iff : borrowsLive rc bs = true ↔
    ∀ b ∈ bs, ∃ c, rc.cells b.target = some c := by
  simp [borrowsLive, List.all_eq_true, Option.isSome_iff_exists]

theorem sync_objects_generic
    (hobjects : ∀ p o, s.objects p = some o → ∃ c, s.rc.cells p = some c ∧ c.fields = o.refs)
    (hcomplete : ∀ p c, s.rc.cells p = some c → ∃ o, s.objects p = some o)
    (sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc)) :
    (∀ p o, syncObjects s rc p = some o → ∃ c, rc.cells p = some c ∧ c.fields = o.refs) ∧
      (∀ p c, rc.cells p = some c → ∃ o, syncObjects s rc p = some o) := by
  constructor
  · intro p o ho
    have hl : (rc.cells p).isSome = true := by
      by_cases h : (rc.cells p).isSome
      · exact h
      · simp [syncObjects, h] at ho
    have hold : s.objects p = some o := by
      by_cases h : (rc.cells p).isSome
      · simpa [syncObjects, h] using ho
      · simp [syncObjects, h] at ho
    obtain ⟨cold, hcold, hfields⟩ := hobjects p o hold
    cases hc : rc.cells p with
    | none => simp [hc] at hl
    | some c =>
      refine ⟨c, rfl, ?_⟩
      have hgnew : (Graph.Rc.erase rc).cells p = some c.fields := by
        simp [Graph.Rc.erase, hc]
      have hgold := sub.cells p c.fields hgnew
      simp [Graph.Rc.erase, hcold] at hgold
      exact hgold.symm.trans hfields
  · intro p c hc
    have hgnew : (Graph.Rc.erase rc).cells p = some c.fields := by
      simp [Graph.Rc.erase, hc]
    have hgold := sub.cells p c.fields hgnew
    cases hold : s.rc.cells p with
    | none => simp [Graph.Rc.erase, hold] at hgold
    | some cold =>
      obtain ⟨o, ho⟩ := hcomplete p cold hold
      refine ⟨o, ?_⟩
      simp [syncObjects, hc, ho]

theorem sync_objects (v : Valid s)
    (sub : Graph.Subheap (Graph.Rc.erase s.rc) (Graph.Rc.erase rc)) :
    (∀ p o, syncObjects s rc p = some o → ∃ c, rc.cells p = some c ∧ c.fields = o.refs) ∧
      (∀ p c, rc.cells p = some c → ∃ o, syncObjects s rc p = some o) :=
  sync_objects_generic v.objects v.complete sub

end Fixed.Memory.Resource
