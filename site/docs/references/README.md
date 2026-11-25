# Overview

Fixed is built from a small set of ideas, and it is honest about which parts
of them the compiler implements. These pages collect the assumptions, the
limits and the sources behind the design.

- [Constructive](constructive.md) — the consultancy behind the project.
- [eo](eo.md) — the sister project: existential optics for Scala 3.
- [Assumptions and limits](assumptions.md) — what every rule assumes, what
  the compiler does not build yet, and where the performance numbers stop.
- [Bibliography](bibliography.md) — the papers, posts and projects the
  language draws on.

The normative language definition is the [specification](../type_system.md),
served straight from `spec/` and kept in lockstep with the compiler. The pages
on this site that are not in `spec/` are guides; where a guide and the
specification disagree, the specification wins.
