import Fixed.Examples
import Fixed.Memory.Examples
import Fixed.Memory.Heap.Examples
import Fixed.Memory.Graph.Examples
import Fixed.Memory.Graph.RcExamples
import Fixed.Memory.Resource.Examples
import Fixed.Memory.Resource.LoweringExamples
import Fixed.Memory.Resource.ErasureExamples
import Fixed.Memory.Resource.ProgressExamples

/-- info: 'Fixed.soundness' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.soundness
/-- info: 'Fixed.consistency' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.consistency
/-- info: 'Fixed.infer_iff' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.infer_iff
/-- info: 'Fixed.accepted_safe' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.accepted_safe
/-- info: 'Fixed.rejects_empty' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.rejects_empty
/-- info: 'Fixed.Quantity.mul_add' does not depend on any axioms -/
#guard_msgs in
#print axioms Fixed.Quantity.mul_add
/-- info: 'Fixed.Quantity.mul_mono' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.Quantity.mul_mono

/-- info: 'Fixed.Memory.execute_iff' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.execute_iff

/-- info: 'Fixed.Memory.accepted_preserves' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.accepted_preserves

/-- info: 'Fixed.Memory.reset_lookup_iff' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.Memory.reset_lookup_iff

/-- info: 'Fixed.Memory.reset_no_dangling' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.reset_no_dangling

/-- info: 'Fixed.Memory.accepted_no_dangling' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.accepted_no_dangling

/-- info: 'Fixed.Memory.run_absent_stays_absent' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.run_absent_stays_absent

/-- info: 'Fixed.Memory.Heap.execute_iff' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.execute_iff

/-- info: 'Fixed.Memory.Heap.accepted_preserves' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.accepted_preserves

/-- info: 'Fixed.Memory.Heap.accepted_no_dangling' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.accepted_no_dangling

/-- info: 'Fixed.Memory.Heap.reset_borrow_live' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.reset_borrow_live

/-- info: 'Fixed.Memory.Heap.drop_live_iff' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.drop_live_iff

/-- info: 'Fixed.Memory.Heap.reset_live_iff' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.reset_live_iff

/-- info: 'Fixed.Memory.Heap.reset_exactly_once' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.reset_exactly_once

/-- info: 'Fixed.Memory.Heap.transfer_dropList' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.transfer_dropList

/-- info: 'Fixed.Memory.Heap.addOwner_count' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.addOwner_count

/-- info: 'Fixed.Memory.Heap.transfer_count' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.transfer_count

/-- info: 'Fixed.Memory.Heap.borrow_count' does not depend on any axioms -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.borrow_count

/-- info: 'Fixed.Memory.Heap.drop_count' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.drop_count

/-- info: 'Fixed.Memory.Heap.collect_balance' depends on axioms: [propext] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.collect_balance

/-- info: 'Fixed.Memory.Heap.no_double_release' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.no_double_release

/-- info: 'Fixed.Memory.Heap.run_heap_absent_stays_absent' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Heap.run_heap_absent_stays_absent

/-- info: 'Fixed.Memory.Combined.execute_iff' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Combined.execute_iff

/-- info: 'Fixed.Memory.Combined.accepted_no_dangling' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Combined.accepted_no_dangling

/-- info: 'Fixed.Memory.Graph.release_total' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.release_total

/-- info: 'Fixed.Memory.Graph.execute_drop_iff' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.execute_drop_iff

/-- info: 'Fixed.Memory.Graph.accepted_no_dangling' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.accepted_no_dangling

/-- info: 'Fixed.Memory.Graph.Rc.alloc_valid' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.Rc.alloc_valid

/-- info: 'Fixed.Memory.Graph.Rc.accepted_refines' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.Rc.accepted_refines

/-- info: 'Fixed.Memory.Graph.Rc.accepted_release' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.Rc.accepted_release

/-- info: 'Fixed.Memory.Graph.Rc.accepted_no_dangling' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Graph.Rc.accepted_no_dangling

/-- info: 'Fixed.Memory.Resource.executeStep_config' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.executeStep_config

/-- info: 'Fixed.Memory.Resource.runSteps_config' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.runSteps_config

/-- info: 'Fixed.Memory.Resource.executeStep_erasure' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.executeStep_erasure

/-- info: 'Fixed.Memory.Resource.runSteps_erasure' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.runSteps_erasure

/-- info: 'Fixed.Memory.Resource.ofCore_runSteps_config' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.ofCore_runSteps_config

/-- info: 'Fixed.Memory.Resource.lower_sound' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.lower_sound

/-- info: 'Fixed.Memory.Resource.lower_runSteps_config' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.lower_runSteps_config

/-- info: 'Fixed.Memory.Resource.lower_runSteps_erasure' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.lower_runSteps_erasure

/-- info: 'Fixed.Memory.Resource.eraseLower_sound' depends on axioms: [propext, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.eraseLower_sound

/-- info: 'Fixed.Memory.Resource.eraseLower_runSteps_erasure' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.eraseLower_runSteps_erasure

/-- info: 'Fixed.Memory.Resource.executeStep_progress' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.executeStep_progress

/-- info: 'Fixed.Memory.Resource.runChecked_good' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.runChecked_good

/-- info: 'Fixed.Memory.Resource.runChecked_ne_rejected' depends on axioms: [propext, Classical.choice, Quot.sound] -/
#guard_msgs in
#print axioms Fixed.Memory.Resource.runChecked_ne_rejected
