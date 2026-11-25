import Fixed.Memory.Resource.Safety

namespace Fixed.Memory.Resource

theorem mem_refs_of_slot (slots : List Slot) (i : Nat) (slot : Slot)
    (h : slots[i]? = some slot) (hp : p ∈ Slot.refs slot) : p ∈ Frame.refs ⟨slots⟩ := by
  induction slots generalizing i with
  | nil => simp at h
  | cons a slots ih =>
    cases i with
    | zero =>
      cases h
      exact List.mem_append_left _ hp
    | succ i =>
      exact List.mem_append_right _ (ih i h)

theorem root_of_slot (v : Valid s) (hf : s.frames = f :: outer) (i : Nat)
    (h : f.slots[i]? = some slot) (hp : p ∈ Slot.refs slot) : p ∈ s.rc.roots := by
  have hmem : p ∈ frameRoots s.frames := by
    rw [hf, frameRoots_cons]
    exact List.mem_append_left _ (mem_refs_of_slot f.slots i slot h hp)
  rw [← List.count_pos_iff, v.roots p]
  exact List.count_pos_iff.mpr hmem

theorem root_of_any_slot (v : Valid s) (hf : f ∈ s.frames) (i : Nat)
    (h : f.slots[i]? = some slot) (hp : p ∈ Slot.refs slot) : p ∈ s.rc.roots := by
  have hmem : p ∈ frameRoots s.frames :=
    List.mem_flatMap.mpr ⟨f, hf, mem_refs_of_slot f.slots i slot h hp⟩
  rw [← List.count_pos_iff, v.roots p]
  exact List.count_pos_iff.mpr hmem

theorem consume_valid (v : Valid s) (hf : s.frames = f :: outer)
    (h : f.slots[i]? = some (some value)) : Valid (consume s i value) := by
  have hs := frameRefs_set f.slots i (some value) none h
  constructor
  · exact v.rc
  · simp [consume, hf]
  · intro q
    have hr := v.roots q
    have hs := hs q
    rw [hf, frameRoots_cons, List.count_append, Frame.refs] at hr
    simp only [consume, hf, frameRoots_cons, Frame.refs, List.flatMap_cons,
      List.count_append, Slot.refs, List.count_nil, Nat.zero_add] at hs ⊢
    omega
  · exact v.objects
  · exact v.complete
  · simpa [consume, hf] using v.borrows

theorem erase_immediate_valid (v : Valid s) (hf : s.frames = f :: outer)
    (h : f.slots[i]? = some slot) (hn : Slot.refs slot = []) : Valid (erase s i s.rc) := by
  have hs := frameRefs_set f.slots i slot none h
  constructor
  · exact v.rc
  · simp [erase, hf]
  · intro q
    have hr := v.roots q
    have hs := hs q
    rw [hn] at hs
    rw [hf, frameRoots_cons, List.count_append, Frame.refs] at hr
    simp only [erase, hf, frameRoots_cons, Frame.refs, List.flatMap_cons,
      List.count_append, Slot.refs, List.count_nil, Nat.zero_add] at hs ⊢
    omega
  · intro p o ho
    have ho' : s.objects p = some o := by
      by_cases hc : (s.rc.cells p).isSome
      · simpa [erase, syncObjects, hc] using ho
      · simp [erase, syncObjects, hc] at ho
    exact v.objects p o ho'
  · intro p c hc
    obtain ⟨o, ho⟩ := v.complete p c hc
    refine ⟨o, ?_⟩
    change s.rc.cells p = some c at hc
    change syncObjects s s.rc p = some o
    simp [syncObjects, hc, ho]
  · simpa [erase, hf] using v.borrows

theorem erase_ref_valid (v : Valid s) (hf : s.frames = f :: outer) (value : Value)
    (h : f.slots[i]? = some (some value)) (hv : value.refs = [p])
    (hr : Graph.Rc.releaseFuel s.rc.next s.rc p = some rc)
    (hbl : borrowsLive rc s.borrows = true) : Valid (erase s i rc) := by
  have hs := frameRefs_set f.slots i (some value) none h
  have hrel := release_roots v.rc hr
  constructor
  · exact release_valid v.rc hr
  · simp [erase, hf]
  · intro q
    have hr' := v.roots q
    have hs := hs q
    have hrel := hrel q
    rw [hf, frameRoots_cons, List.count_append, Frame.refs] at hr'
    simp only [erase, hf, frameRoots_cons, Frame.refs, List.flatMap_cons,
      List.count_append, Slot.refs, hv, List.count_cons, List.count_nil,
      Nat.zero_add] at hs ⊢
    simp only [List.count_cons, List.count_nil, Nat.zero_add] at hrel
    omega
  · exact (sync_objects v (release_subheap v.rc hr)).1
  · exact (sync_objects v (release_subheap v.rc hr)).2
  · intro b hb
    exact ⟨by simpa [erase, hf] using (v.borrows b hb).1,
      borrowsLive_iff.mp hbl b (by simpa [erase] using hb)⟩

theorem enter_valid (v : Valid s) : Valid (enter s) := by
  constructor
  · exact v.rc
  · simp [enter, Frame.empty]
  · intro q
    simpa [enter, frameRoots, Frame.empty, Frame.refs] using v.roots q
  · exact v.objects
  · exact v.complete
  · intro b hb
    obtain ⟨hd, hc⟩ := v.borrows b hb
    exact ⟨Nat.lt.step hd, hc⟩

theorem leave_valid (v : Valid s) (hf : s.frames = f :: outer) (ho : 0 < outer.length)
    (hr : releaseList s.rc f.refs = some rc)
    (hbl : borrowsLive rc (s.borrows.filter (fun b => b.frame < depth s)) = true) :
    Valid (leave s outer rc) := by
  have hrel := releaseList_roots v.rc hr
  constructor
  · exact releaseList_valid v.rc hr
  · simpa [leave] using ho
  · intro q
    have hr' := v.roots q
    have hrel := hrel q
    rw [hf, frameRoots_cons, List.count_append] at hr'
    change rc.roots.count q = (frameRoots outer).count q
    omega
  · exact (sync_objects v (releaseList_subheap v.rc hr)).1
  · exact (sync_objects v (releaseList_subheap v.rc hr)).2
  · intro b hb
    have hb' : b ∈ s.borrows.filter (fun b => b.frame < depth s) := by
      simpa [leave] using hb
    have hmem := List.mem_filter.mp hb'
    have hframe : b.frame < outer.length := by
      have hd : b.frame < depth s := by simpa using hmem.2
      simpa only [depth, hf, List.length_cons, Nat.add_sub_cancel] using hd
    exact ⟨hframe, borrowsLive_iff.mp hbl b hb'⟩

theorem alloc_old_cell (v : Valid s) (fields : List Addr) (hc : s.rc.cells p = some c) :
    ∃ d, (Graph.Rc.alloc s.rc fields).cells p = some d ∧ d.fields = c.fields := by
  have hf : p < s.rc.next := v.rc.graph.fresh p c.fields (by simp [Graph.Rc.erase, hc])
  exact ⟨{ c with count := c.count + fields.count p }, by
    simp [Graph.Rc.alloc, Nat.ne_of_lt hf, hc], rfl⟩

theorem alloc_valid (v : Valid s)
    (hf : ∀ p ∈ o.refs, p ∈ s.rc.roots) : Valid (alloc s o) := by
  have hrc : Graph.Rc.Valid (Graph.Rc.alloc s.rc o.refs) := Graph.Rc.alloc_valid v.rc hf
  cases hframes : s.frames with
  | nil =>
    have hfv := v.frames
    simp [hframes] at hfv
  | cons f outer =>
    constructor
    · exact hrc
    · simp [alloc, hframes]
    · intro q
      have hr := v.roots q
      rw [hframes, frameRoots_cons, List.count_append, Frame.refs] at hr
      simp only [alloc, hframes, Graph.Rc.alloc, frameRoots_cons, Frame.refs,
        List.flatMap_cons, Slot.refs, Value.refs, List.count_cons, List.count_nil,
        Nat.zero_add, List.count_append]
      omega
    · intro p obj ho
      by_cases h : p = s.rc.next
      · subst p
        have ho' : o = obj := by simpa [alloc] using ho
        subst obj
        exact ⟨⟨1, o.refs⟩, by simp [alloc, Graph.Rc.alloc], rfl⟩
      · have hold : s.objects p = some obj := by simpa [alloc, h] using ho
        obtain ⟨c, hc, hfields⟩ := v.objects p obj hold
        obtain ⟨d, hd, hdf⟩ := alloc_old_cell v o.refs hc
        exact ⟨d, hd, hdf.trans hfields⟩
    · intro p c hc
      change (Graph.Rc.alloc s.rc o.refs).cells p = some c at hc
      by_cases h : p = s.rc.next
      · subst p
        exact ⟨o, by simp [alloc]⟩
      · cases hold : s.rc.cells p with
        | none => simp [Graph.Rc.alloc, h, hold] at hc
        | some cold =>
          obtain ⟨obj, ho⟩ := v.complete p cold hold
          exact ⟨obj, by simp [alloc, h, ho]⟩
    · intro b hb
      obtain ⟨hd, c, hc⟩ := v.borrows b hb
      refine ⟨by simpa [alloc, hframes] using hd, ?_⟩
      obtain ⟨d, hd', _⟩ := alloc_old_cell v o.refs hc
      exact ⟨d, hd'⟩

theorem dup_valid (v : Valid s) (hf : s.frames = f :: outer) (value : Value)
    (hr : ∀ p ∈ value.refs, p ∈ s.rc.roots) : Valid (dup s value) := by
  cases ha : value.addr with
  | none =>
    have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
    constructor
    · simpa [dup, ha] using v.rc
    · simp [dup, hf]
    · intro q
      have hroots := v.roots q
      simpa [dup, ha, hf, hv, frameRoots, Frame.refs, Slot.refs,
        List.count_append] using hroots
    · simpa [dup, ha] using v.objects
    · simpa [dup, ha] using v.complete
    · simpa [dup, ha, hf] using v.borrows
  | some p =>
    have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
    have hp : p ∈ s.rc.roots := hr p (by simp [hv])
    have hrc : Graph.Rc.Valid (Graph.Rc.dup s.rc p) := Graph.Rc.dup_valid v.rc hp
    have oldCell : ∀ q c, s.rc.cells q = some c →
        ∃ d, (Graph.Rc.dup s.rc p).cells q = some d ∧ d.fields = c.fields := by
      intro q c hc
      by_cases h : q = p
      · subst q
        exact ⟨{ c with count := c.count + 1 }, by simp [Graph.Rc.dup, hc], rfl⟩
      · exact ⟨c, by simp [Graph.Rc.dup, h, hc], rfl⟩
    constructor
    · simpa [dup, ha] using hrc
    · simp [dup, hf]
    · intro q
      have hroots := v.roots q
      rw [hf, frameRoots_cons, List.count_append, Frame.refs] at hroots
      simp only [dup, ha, hv, hf, Graph.Rc.dup, frameRoots_cons, Frame.refs, List.flatMap_cons,
        Slot.refs, List.count_cons, List.count_nil, Nat.zero_add, List.count_append]
      omega
    · intro q obj ho
      obtain ⟨c, hc, hfields⟩ := v.objects q obj ho
      obtain ⟨d, hd, hdf⟩ := oldCell q c hc
      exact ⟨d, by simpa [dup, ha] using hd, hdf.trans hfields⟩
    · intro q c hc
      change (dup s value).rc.cells q = some c at hc
      simp only [dup, ha] at hc
      cases hold : s.rc.cells q with
      | none => simp [Graph.Rc.dup, hold] at hc
      | some cold =>
        obtain ⟨obj, ho⟩ := v.complete q cold hold
        exact ⟨obj, ho⟩
    · intro b hb
      obtain ⟨hd, c, hc⟩ := v.borrows b (by simpa [dup] using hb)
      obtain ⟨d, hd', _⟩ := oldCell b.target c hc
      exact ⟨by simpa [dup, hf] using hd, d, by simpa [dup, ha] using hd'⟩

theorem borrow_valid (v : Valid s) (hf : s.frames = f :: outer) (i : Nat)
    (h : f.slots[i]? = some (some value)) (ha : value.addr = some p) : Valid (borrow s p) := by
  have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
  have hp : p ∈ s.rc.roots := root_of_slot v hf i h (by simp [Slot.refs, hv])
  obtain ⟨fields, hc⟩ := v.rc.graph.roots p hp
  have live : ∃ c, s.rc.cells p = some c := by
    cases hcell : s.rc.cells p with
    | none => simp [Graph.Rc.erase, hcell] at hc
    | some c => exact ⟨c, rfl⟩
  constructor
  · exact v.rc
  · simpa [borrow] using v.frames
  · exact v.roots
  · exact v.objects
  · exact v.complete
  · intro b hb
    rcases List.mem_cons.mp hb with rfl | hb
    · refine ⟨?_, live⟩
      change depth s < s.frames.length
      simp only [depth]
      have hf' := v.frames
      omega
    · exact v.borrows b hb

theorem accepted_preserves (v : Valid s) (h : execute s op = some t) : Valid t := by
  cases op with
  | enter => cases h; exact enter_valid v
  | literal value =>
    cases hf : s.frames with
    | nil =>
      have hfv := v.frames
      simp [hf] at hfv
    | cons f outer =>
      cases ha : value.addr with
      | some p => simp [execute, ha] at h
      | none =>
        have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
        simp only [execute, ha] at h
        cases h
        exact dup_valid v hf _ (by simp [hv])
  | alloc o =>
    simp only [execute] at h
    split at h
    next hc => cases h; exact alloc_valid v hc.2
    next => contradiction
  | dup i =>
    cases hf : s.frames with
    | nil => simp [execute, hf] at h
    | cons f outer =>
      cases hi : f.slots[i]? with
      | none => simp [execute, hf, hi] at h
      | some slot =>
        cases slot with
        | none => simp [execute, hf, hi] at h
        | some value =>
          cases ha : value.addr with
          | none =>
            have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
            simp only [execute, hf, hi, ha] at h
            cases h
            exact dup_valid v hf _ (by simp [hv])
          | some p =>
            have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
            by_cases hp : p ∈ s.rc.roots
            · simp only [execute, hf, hi, ha, hp, if_true] at h
              cases h
              apply dup_valid v hf _
              intro q hq
              simp only [hv, List.mem_singleton] at hq
              subst q
              exact hp
            · simp [execute, hf, hi, ha, hp] at h
  | consume i =>
    cases hf : s.frames with
    | nil => simp [execute, hf] at h
    | cons f outer =>
      cases hi : f.slots[i]? with
      | none => simp [execute, hf, hi] at h
      | some slot =>
        cases slot with
        | none => simp [execute, hf, hi] at h
        | some value =>
          simp only [execute, hf, hi] at h
          cases h
          exact consume_valid v hf hi
  | erase i =>
    cases hf : s.frames with
    | nil => simp [execute, hf] at h
    | cons f outer =>
      cases hi : f.slots[i]? with
      | none => simp [execute, hf, hi] at h
      | some slot =>
        cases slot with
        | none =>
          simp only [execute, hf, hi] at h
          cases h
          exact erase_immediate_valid v hf hi rfl
        | some value =>
          cases ha : value.addr with
          | none =>
            have hv : value.refs = [] := by cases value <;> simp_all [Value.addr, Value.refs]
            simp only [execute, hf, hi, ha] at h
            cases h
            exact erase_immediate_valid v hf hi (by simp [Slot.refs, hv])
          | some p =>
            have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
            cases hr : Graph.Rc.releaseFuel s.rc.next s.rc p with
            | none => simp [execute, hf, hi, ha, hr] at h
            | some rc =>
              simp only [execute, hf, hi, ha, hr, Option.bind_some] at h
              change (if borrowsLive rc s.borrows then some (erase s i rc) else none) = some t at h
              split at h
              next hb => cases h; exact erase_ref_valid v hf value hi hv hr hb
              next => contradiction
  | borrow i =>
    cases hf : s.frames with
    | nil => simp [execute, hf] at h
    | cons f outer =>
      cases hi : f.slots[i]? with
      | none => simp [execute, hf, hi] at h
      | some slot =>
        cases slot with
        | none => simp [execute, hf, hi] at h
        | some value =>
          cases ha : value.addr with
          | none => simp [execute, hf, hi, ha] at h
          | some p =>
            simp only [execute, hf, hi, ha] at h
            cases h
            exact borrow_valid v hf i hi ha
  | leave =>
    cases hf : s.frames with
    | nil => simp [execute, hf] at h
    | cons f outer =>
      cases outer with
      | nil => simp [execute, hf] at h
      | cons f' outer' =>
        have ho : 0 < (f' :: outer').length := by simp
        simp only [execute, hf, ho, if_true] at h
        cases hr : releaseList s.rc f.refs with
        | none => simp [hr] at h
        | some rc =>
          simp only [hr, Option.bind_some] at h
          change (if borrowsLive rc (s.borrows.filter (fun b => b.frame < depth s)) then
            some (leave s (f' :: outer') rc) else none) = some t at h
          split at h
          next hb => cases h; exact leave_valid v hf ho hr hb
          next => contradiction

theorem run_preserves (v : Valid s) (h : run s ops = some t) : Valid t := by
  induction ops generalizing s with
  | nil => cases h; exact v
  | cons op ops ih =>
    cases he : execute s op with
    | none => simp [run, he] at h
    | some u =>
      simp only [run, he, Option.bind_some] at h
      exact ih (accepted_preserves v he) h

theorem frame_ref_live (v : Valid s) (hf : f ∈ s.frames) (i : Nat)
    (h : f.slots[i]? = some (some value)) (ha : value.addr = some p) :
    ∃ c, s.rc.cells p = some c := by
  have hv : value.refs = [p] := by cases value <;> simp_all [Value.addr, Value.refs]
  have hp : p ∈ s.rc.roots := root_of_any_slot v hf i h (by simp [Slot.refs, hv])
  obtain ⟨fields, hc⟩ := v.rc.graph.roots p hp
  cases hcell : s.rc.cells p with
  | none => simp [Graph.Rc.erase, hcell] at hc
  | some c => exact ⟨c, rfl⟩

theorem accepted_no_dangling (h : run initial ops = some s) :
    (∀ f ∈ s.frames, ∀ (i : Nat) (value : Value) (p : Addr),
      f.slots[i]? = some (some value) → value.addr = some p →
      ∃ c, s.rc.cells p = some c) ∧
    (∀ b ∈ s.borrows, b.frame < s.frames.length ∧ ∃ c, s.rc.cells b.target = some c) := by
  have v := run_preserves initial_valid h
  exact ⟨fun f hf i value p hi ha => frame_ref_live v hf i hi ha, v.borrows⟩

end Fixed.Memory.Resource
