# Fixed: Memory Layouts, Structures, and Value Flow

This document provides a set of architectural Mermaid diagrams illustrating how runtime values are represented, allocated, passed, and reclaimed in **Fixed**.

These diagrams reflect the design specified in:
- [`spec/memory_management.md`](../spec/memory_management.md) (Lifetime classes, regions, Goose-style data stacks, Perceus heap, destinations, FBIP reuse)
- [`spec/quantities.md`](../spec/quantities.md) (Quantitative Type Theory: demands, exclusivity, borrowing)
- [`spec/effects.md`](../spec/effects.md) (Evidence-passing vectors and fiber stacks)

---

## 1. The Two-Axis Placement & Management Matrix

In Fixed, **ownership** (deciding reference-counting traffic and in-place reuse) is strictly decoupled from **lifetime class** (deciding physical storage location). 

Ownership is governed by three fundamental interaction modes, presented in increasing order of commitment: **Lend $\to$ Give $\to$ Hold**.

Source: [`01-quantity-lifetime-matrix.mmd`](01-quantity-lifetime-matrix.mmd)

```mermaid
flowchart TD
    %% =========================================================================
    %% PART 1: FRONT-END ANALYSIS GUIDANCE (HOW & WHY DECISIONS ARE MADE)
    %% =========================================================================
    subgraph FrontEnd["Front-End Compiler Pipeline & Analyses"]
        direction TB
        Source["Capability Source Code\ne.g., 'fn sum(c: is Folding of Numeric)'"]
        
        QTT["1. Quantitative Type Theory (M10)\nComputes Demands & Ownership Protocol:\n• none: Erased (Type/proof level only)\n• read: LEND (Caller Lends -> Callee Peeks)\n• one: GIVE (Caller Gives -> Callee Takes)\n• many: HOLD (Caller Shares -> Callee Holds)"]
        
        Escape["2. Escape & Churn Analysis (M10/Rule MM5.2)\nDetermines Extent & Lifetime:\n• Scope-bounded LIFO extent\n• Cohesive Group (single-root tree)\n• Non-garbage-free churn / unbounded escape"]

        Source --> QTT
        Source --> Escape
    end

    %% =========================================================================
    %% PART 2: PLACEMENT DECISION TREE (RULE MM5.3 PRIORITY ORDER)
    %% =========================================================================
    subgraph DecisionTree["Placement Decision Engine (Rule MM5.3 Priority Order)"]
        direction TB

        CheckNone{"Is Demand 'none'?"}
        ActErased["Class: Erased\nWhy: Pure type witness / proof token\nAction: Emitted as 0 runtime bytes"]

        CheckConst{"Compile-time\nConstant?"}
        ActStatic["Class: Static\nWhy: Immutable global data\nAction: Baked in rodata, persistent, no RC"]

        CheckScope{"All uses inside\nScope 's'?"}
        CheckDest{"Escapes ONLY into\nCaller Result?"}
        ActDest["Class: Dest(p)\nWhy: Destination-Passing Style\nAction: Callee writes straight into caller memory"]
        ActFrame["Class: Frame(s)\nWhy: LIFO temporary\nAction: Scratch stack bump alloc, O(1) reset"]

        CheckCohesive{"Is Cohesive Group\n(Rule MM5.4)?"}
        ActRegion["Class: Region(r)\nWhy: Syntax trees / DAGs with single root\nAction: 1 refcount per region, no per-node headers"]

        ActHeap["Class: Perceus Heap\nWhy: Long-lived churn (loop accumulator) or escape\nAction: Individual cell RC headers + FBIP reuse"]

        CheckNone -- Yes --> ActErased
        CheckNone -- No --> CheckConst
        CheckConst -- Yes --> ActStatic
        CheckConst -- No --> CheckScope
        CheckScope -- Yes --> CheckDest
        CheckDest -- Yes --> ActDest
        CheckDest -- No --> ActFrame
        CheckScope -- No --> CheckCohesive
        CheckCohesive -- Yes --> ActRegion
        CheckCohesive -- No --> ActHeap
    end

    QTT -.->|Demands: none vs read / one / many| CheckNone
    Escape -.->|Liveness & Scopes| CheckScope
    Escape -.->|Cohesive Detection| CheckCohesive
    Escape -.->|Churn Detection| ActHeap

    %% =========================================================================
    %% PART 3: THE 2-AXIS INTERACTION MATRIX (ORDERED: LEND -> GIVE -> HOLD)
    %% =========================================================================
    subgraph MatrixGrid["Runtime Execution: Ownership × Lifetime Class Matrix (Rule MM3.2)"]
        direction TB

        subgraph ColLend["1. LEND (Demand 'read')\nCaller: Lends (Lent) | Callee: Peeks (Peekable)"]
            direction TB
            L_Universal["Uniform Calling Convention (Rule MM7.2):\n• Caller: Retains ownership; passes Lent reference\n• Callee: Peeks read-only; cannot mutate or retain\n• Cell Header: Ignored\n• RC Ops: Exactly 0 across all classes\n• Stack Drop List: Bypassed completely (Rule MM13.3)"]
        end

        subgraph ColGive["2. GIVE (Demand 'one')\nCaller: Gives (Given) | Callee: Takes (Taken)"]
            direction TB
            G_Frame["Frame(s) / Dest(p):\n• Caller: Surrenders value (Given)\n• Callee: Takes sole ownership (Taken)\n• RC Ops: 0\n• In-Place Reuse: Unconditional (FBIP)\n• Reclamation: O(1) bump reset on scope exit"]
            G_Region["Region(r):\n• Caller: Surrenders unique handle\n• Callee: Takes sole ownership in region\n• RC Ops: 1 region ref released when consumed\n• In-Place Reuse: Unconditional within region\n• Reclamation: Freed when region count = 0"]
            G_Heap["Perceus Heap:\n• Caller: Moves unique cell (Given)\n• Callee: Takes sole ownership (Taken)\n• RC Ops: 0 (No dup/drop calls!)\n• In-Place Reuse: Unconditional (FBIP)\n• Reclamation: Immediate reuse or free at last use"]
        end

        subgraph ColHold["3. HOLD (Demand 'many')\nCaller: Shares (Shared) | Callee: Holds (Held)"]
            direction TB
            H_Frame["Frame(s) / Dest(p):\n• Caller: Retains alias (Shared)\n• Callee: Holds reference within scope (Held)\n• RC Ops: 0 (No RC traffic on data stack)\n• In-Place Reuse: Disabled (cannot mutate alias)\n• Reclamation: O(1) bump reset on scope exit"]
            H_Region["Region(r):\n• Caller: Retains alias\n• Callee: Holds counted region handle (Held)\n• RC Ops: Region-level dup/drop\n• In-Place Reuse: Disabled\n• Reclamation: Bulk free when region count = 0"]
            H_Heap["Perceus Heap:\n• Caller: Retains alias (Shared)\n• Callee: Holds counted reference (Held)\n• RC Ops: Standard Perceus dup/drop per cell\n• In-Place Reuse: Dynamic (After runtime rc == 1 test)\n• Reclamation: Freed when cell rc hits 0"]
        end
    end

    ActFrame -.-> L_Universal
    ActFrame -.-> G_Frame
    ActFrame -.-> H_Frame
    ActDest -.-> L_Universal
    ActDest -.-> G_Frame
    ActDest -.-> H_Frame
    ActRegion -.-> L_Universal
    ActRegion -.-> G_Region
    ActRegion -.-> H_Region
    ActHeap -.-> L_Universal
    ActHeap -.-> G_Heap
    ActHeap -.-> H_Heap
```

---

### Caller vs. Callee Ownership Naming

When values cross function call boundaries, the ownership contract is symmetrical yet distinct from the caller's and callee's perspectives:

| Ownership Mode | Demand | Caller Perspective (Action $\to$ State) | Callee Perspective (Action $\to$ State) | Mechanical Consequence |
| :--- | :--- | :--- | :--- | :--- |
| **1. Lend** | `read` | **Lend** $\implies$ Value is **Lent**<br>*(Caller retains ownership; hands over temporary view)* | **Peek** $\implies$ Value is **Peekable**<br>*(Callee observes read-only; cannot mutate or retain)* | **$0$ RC ops** guaranteed across all lifetime classes. Bypasses frame drop lists entirely. |
| **2. Give** | `one` | **Give** $\implies$ Value is **Given**<br>*(Caller relinquishes and moves its unique ownership)* | **Take** $\implies$ Value is **Taken**<br>*(Callee assumes sole ownership; authorized for FBIP)* | **$0$ RC ops**. Callee can destructively mutate and overwrite memory in-place. |
| **3. Hold** | `many` | **Share** $\implies$ Value is **Shared**<br>*(Caller retains an ongoing reference alongside callee)* | **Hold** $\implies$ Value is **Held**<br>*(Callee counts/retains its reference alongside aliases)* | **Perceus RC traffic** (`dup`/`drop`). In-place mutation guarded by runtime `rc == 1` tests. |

---

### How and Why Front-End Analysis Guides Placement Decisions

In **Fixed**, the programmer writes code against **abstract capabilities** (`is Folding of Numeric`). The compiler selects both the data representation and the physical memory placement, guided by two core front-end analyses:

#### 1. Quantitative Type Theory (QTT) Demand Inference (`spec/quantities.md`)
QTT analyses track how many times every variable and witness is consumed across the call graph:
- **Demand `none` (Erased):** The value exists purely at the type or proof level (e.g., capability witnesses, type equality tokens).  
  *Why:* Emitting runtime memory for erased tokens would introduce senseless memory bloat. The compiler eliminates them completely ($0$ bytes).
- **Demand `read` (Lend $\to$ Peek):** The callee inspects or reads the value without capturing it or keeping it alive past the function return.  
  *Why:* Callee code does not increment or decrement reference counts. The caller passes a raw pointer, avoiding all atomic or non-atomic RC traffic.
- **Demand `one` (Give $\to$ Take):** The value has a single, unique owner who consumes it.  
  *Why:* Enables **guaranteed compile-time Functional But In-Place (FBIP) mutation**. When a constructor replaces an old value, the compiler overwrites the dead cell directly without allocating new memory or running an `rc == 1` test.
- **Demand `many` (Share $\to$ Hold):** The value is held by many owners concurrently.  
  *Why:* The runtime must emit reference-count tracking (`dup` / `drop`) and guard in-place mutations behind dynamic `rc == 1` checks.

#### 2. Escape & Extent Analysis (`spec/memory_management.md` §5)
This analysis determines the dynamic lifetime of each value relative to language execution scopes:
- **Scope-Bounded LIFO Extent $\implies$ `Frame(s)` (Scratch Stack):**  
  *How:* The compiler verifies that all uses of an allocation occur within lexical scope $s$ (e.g., a function body, a fold iteration, or an effect handler body).  
  *Why:* Traditional heaps suffer from allocation fragmentation and costly deallocation. Fixed's `Frame(s)` places values on a contiguous, headerless scratch data stack. Allocation is a simple pointer bump, and reclamation is an instant $O(1)$ mark/reset at scope exit.
- **Result-Bounded Escape $\implies$ `Dest(p)` (Destination-Passing):**  
  *How:* The value escapes the current scope, but *only* as the return value flowing into the caller.  
  *Why:* Allocating a return buffer in the callee and copying it back to the caller causes redundant memory moves. Instead, the caller passes a hidden pointer to its own destination memory, and the callee writes directly into it.
- **Cohesive Grouping $\implies$ `Region(r)` (Counted Regions):**  
  *How:* Detects structures (like syntax trees, graph nodes, or ASTs generated by an `unfold`) created within one dynamic extent, rooted in a single handle, where internal elements are dropped together (Rule MM5.4).  
  *Why:* If a 10,000-node AST were placed on the general heap, every node would require an $8-16$ byte reference-counting header, wasting memory. In a `Region(r)`, **only one descriptor and one reference count** exist for the entire tree. Interior pointers need no individual headers, and the entire tree is reclaimed in one stroke when the root count hits zero.
- **Long-Lived Churn & Unbounded Escape $\implies$ `Heap` (Perceus Heap):**  
  *How:* Identifies loop accumulators in `fold`/`unfold` that are *not* garbage-free (Rule MM5.2b), or values that escape into closures/fibers beyond any identifiable scope.  
  *Why (The MLKit Problem):* In early region-based systems (like MLKit), accumulating values across recursive loops in a region leaked memory until the loop completed, exhausting memory. Fixed identifies non-garbage-free churn and routes those carried values to the `Heap`, allowing Perceus to free each iteration's dead version immediately as it dies.

---

## 2. Runtime Memory Topography: Stacks and Regions

Fixed structures memory into chunked regions ($4\text{ KiB} \to 8\text{ KiB} \to 16\text{ KiB}$ exponential doubling) without large address-space reservations:
- **Scratch Stack**: Headerless, strictly LIFO per scope. Instant bump-pointer reset at scope exit.
- **Growable Stacks**: Dedicated to values under construction (contiguous arrays / packed blobs) so loop scratch doesn't block contiguous growth.
- **Counted Regions**: One descriptor and count per cohesive group (e.g. syntax trees). Interior pointers need no individual headers.
- **Perceus Heap**: Header per cell with RC for long-lived shared churn.

Source: [`02-runtime-memory-topography.mmd`](02-runtime-memory-topography.mmd)

```mermaid
flowchart TB
    subgraph ExecutionLane["Thread / Fiber / Lane Execution Context"]
        direction TB

        subgraph ScratchStack["1. Scratch Data Stack (LIFO per Scope)"]
            OuterFrame["Frame(outer_scope)\nHeaderless values, no RC operations"]
            ScopeMark["Mark (chunk_id, offset)"]
            InnerFrame["Frame(step / inner_scope)\nPer-step temporaries in fold/unfold"]
            ScopeExit["Scope Exit -> Instant Bump-Pointer Reset\n(No per-value free; drop list walked only for owned heap refs)"]
            OuterFrame --- ScopeMark
            ScopeMark --- InnerFrame
            InnerFrame -.->|LIFO Reset| ScopeMark
        end

        subgraph GrowableStack["2. Growable Stacks (N Stacks)"]
            GrowableVal["Contiguous Value Under Construction\n(Contiguous Array or Packed Blob)"]
            NoteGrow["Separated from scratch stack!\nPrevents loop scratch from blocking contiguous growth.\nDoubles chunk and relocates if capacity exceeded."]
        end

        subgraph CountedRegion["3. Counted Regions: Region(r)"]
            RegHeader["Region Descriptor\n[ Total Refcount | Outset Edges {q1, q2} ]"]
            C1["Chunk 0 (4 KiB)"]
            C2["Chunk 1 (8 KiB)"]
            C3["Chunk 2 (16 KiB)"]
            RegHeader --> C1
            C1 --> C2
            C2 --> C3
            NoteCR["No headers on individual cells!\nInterior pointers are masked to find descriptor.\nEntire tree freed in one stroke when count reaches 0."]
        end

        subgraph PerceusHeap["4. Perceus Heap"]
            HeapCell1["Heap Cell [Header: RC, Tag] | Payload"]
            HeapCell2["Heap Cell [Header: RC, Tag] | Payload"]
            ReusePool["Reused in-place via FBIP"]
        end
    end
```

---

## 3. Destination-Passing Style (`Dest(p)`)

Callers reserve a destination hole and pass a hidden pointer down the call chain. Constructors in tail position write directly into caller memory (Tail Modulo Constructor), eliminating intermediate allocations.

Source: [`03-destination-passing-style.mmd`](03-destination-passing-style.mmd)

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Caller Function
    participant Callee as Callee (e.g., map / unfold)
    participant Dest as Destination Buffer (Caller Stack / Region)

    Caller->>Caller: Allocates destination hole in its current Frame/Region
    Caller->>Callee: Passes hidden pointer (dest_ptr) as argument
    Note over Callee,Dest: Tail Modulo Constructor (TMC)
    Callee->>Dest: Writes constructor tag and value directly into *dest_ptr
    opt Recursive Step
        Callee->>Callee: Passes (dest_ptr + offset) into recursive child call
    end
    Callee-->>Caller: Returns void / finished pointer (Zero copying)
```

---

## 4. Functional But In-Place (FBIP) & Perceus Reuse

Data transformations recycle dead memory cells without calling allocators:
- **Taken (Demand 'one' / Give)**: In-place mutation is guaranteed at compile time without runtime checks.
- **Held (Demand 'many' / Hold)**: Runtime `rc == 1` test unlocks in-place mutation; branches to fresh allocation only when truly shared.

Source: [`04-fbip-perceus-reuse.mmd`](04-fbip-perceus-reuse.mmd)

```mermaid
flowchart TD
    Op["Operation on data cell: Cons(x, xs)"]
    DemandCheck{"Is value exclusively owned?\n(Compile-time Demand Analysis)"}

    Unconditional["Unconditional In-Place Reuse\nNo runtime checks needed!"]
    RuntimeTest{"Runtime Check:\nis rc == 1?"}

    InPlace["Mutate Cell In-Place (FBIP)\nOld memory cell recycled into new cell\nZERO malloc / ZERO free"]
    FreshAlloc["Allocate fresh cell\nDecrement RC on old cell (Perceus drop)"]

    Op --> DemandCheck
    DemandCheck -- "Taken (Demand 'one' / Give)" --> Unconditional
    DemandCheck -- "Held (Demand 'many' / Hold)" --> RuntimeTest

    Unconditional --> InPlace
    RuntimeTest -- "Yes (rc == 1)" --> InPlace
    RuntimeTest -- "No (rc > 1)" --> FreshAlloc
```

---

## 5. Region-Containment Invariant & Evacuation

Fixed enforces Rule MM4.1: $\text{lifetime}(\text{Target}) \supseteq \text{lifetime}(\text{Source})$. If an inner value escapes into a longer-lived context, the compiler automatically inserts an **evacuation** (deep copy) into the outer region or heap.

Source: [`05-region-containment-evacuation.mmd`](05-region-containment-evacuation.mmd)

```mermaid
flowchart TD
    subgraph Hierarchy["Lifetime Hierarchy (Durable -> Transient)"]
        direction TB
        Static["Static / Persistent Data (Infinite Lifetime)"]
        Heap["Perceus Heap (Unbounded Lifetime)"]
        Region["Counted Region(r) (Lifespan of Root Handle)"]
        FrameOuter["Frame(outer_scope)"]
        FrameInner["Frame(step / inner_scope)"]
    end

    Static -.->|Can target| Static
    Heap -->|Valid| Static
    Heap -->|Counted Outset Edge| Region
    Region -->|Valid| Heap
    Region -->|Valid| Static
    FrameOuter -->|Valid| Region
    FrameOuter -->|Valid| Heap
    FrameOuter -->|Valid| Static
    FrameInner -->|Valid| FrameOuter

    subgraph Evacuation["Evacuation Mechanism (Rule MM5.5)"]
        InnerVal["Value in Frame(inner)"]
        EscapeAttempt["Escapes to Frame(outer) or Heap"]
        CopiedVal["Evacuated: Compiler inserts deep copy\ninto target longer-lived region"]
        InnerVal --> EscapeAttempt
        EscapeAttempt --> CopiedVal
    end
```

---

## 6. Algebraic Effects: Evidence Vectors and Fiber Stacks

Effects compile to hidden evidence vectors. Tail-resuming handlers execute directly on the current scratch stack. Small chunked fiber stacks ($4\text{ KiB}$) are created only when non-tail continuations escape.

Source: [`06-effects-evidence-fibers.mmd`](06-effects-evidence-fibers.mmd)

```mermaid
flowchart LR
    subgraph Call["Function with Effect 'with Console, State'"]
        HiddenArg["Hidden Argument: evidence_vector"]
    end

    subgraph EvVec["Evidence Vector (Stack-Allocated Array)"]
        E0["Slot 0: Console -> [ Handler VTable* | Env* ]"]
        E1["Slot 1: State   -> [ Handler VTable* | Env* ]"]
    end

    subgraph Dispatch["Execution Path"]
        TailRes["Tail-Resume / Abort:\nExecutes in-place on current scratch stack"]
        FiberStack["Escaping Continuation:\nAllocates small 4 KiB chunked Fiber Stack"]
    end

    Call --> HiddenArg
    HiddenArg --> EvVec
    EvVec --> TailRes
    EvVec --> FiberStack
```

---

## 7. End-to-End Value Lifecycle in Fixed

Source: [`07-value-lifecycle-journey.mmd`](07-value-lifecycle-journey.mmd)

```mermaid
flowchart TD
    S1["1. Programmer writes Capability Code\nnumbers.fold(0, (acc, x) -> acc + x)"]
    S2["2. Compiler Representation Selection (M11)\nSelects unboxed scalar, contiguous array, or linked chunk"]
    S3["3. QTT Analysis (M10)\nComputes demands: accumulator is Given/Taken, x is Lent/Peekable"]
    S4["4. Lifetime Class Assignment (Rule MM5.3)\n- Step temporaries -> Frame(step)\n- Accumulator -> Reused In-Place (FBIP)\n- Result -> Dest(caller)"]
    S5["5. Execution on Data Stacks\nValues written directly to bump-allocated scratch or growable stack"]
    S6["6. End of Step\nScratch stack mark restored (O(1) pop); zero destructor overhead"]
    S7["7. Final Value Returned\nZero copies: sits directly in caller's destination slot"]

    S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7
```
