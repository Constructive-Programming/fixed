#!/usr/bin/env bash
# Build the runtime benchmarks, run each one RUNS times, and write docs/baselines/runtime-<date>.md
# (docs/plans/phase-4.0-backend-slice.md §4.9). NOTE, if set, replaces the description paragraph.
# Usage: [NOTE=...] bench/runtime/run.sh [RUNS]   (default 5). Needs sbt and clang on PATH.
set -euo pipefail
cd "$(dirname "$0")/../.."

runs=${1:-5}
out=target/bench-runtime
mkdir -p "$out"
progs=(bintrees nat_loop list_pipeline records fail_chain)

cmds=()
for p in "${progs[@]}"; do cmds+=("compiler/run ship bench/runtime/$p.fixed -o $out/$p"); done
sbt -warn "${cmds[@]}"

field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }

report=docs/baselines/runtime-$(date +%F).md
{
  echo "# Runtime baseline, $(date +%F)"
  echo
  echo "${NOTE:-Phase 4.0 backend slice (\`docs/plans/phase-4.0-backend-slice.md\` §4.9): everything boxed and reference-counted, no fusion, no reuse, no borrowing. Later milestones report against these numbers.}"
  echo
  echo "- Commit: \`$(git rev-parse --short HEAD)\`$(git diff --quiet HEAD -- compiler bench || echo ' (with local changes)')"
  echo "- Machine: $(uname -srm), $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ *//')"
  echo "- Toolchain: $(clang --version | head -1), \`clang -O2\`"
  echo "- Wall time: best of $runs runs, measured by the runtime from \`main\` start to end (no process start-up)."
  echo "- Counts are deterministic: every run gives the same allocations, reuses, \`dup\`s and \`drop\`s."
  echo
  echo "| Program | Size | Result | Wall (ms) | Peak RSS (KiB) | Allocations | Reuses | Frees | dups | drops |"
  echo "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"
  for p in "${progs[@]}"; do
    best=""; result=""
    for _ in $(seq "$runs"); do
      stats=$( { result=$(FIXED_RT_STATS=1 "$out/$p" 2>"$out/$p.stats"); echo "$result" > "$out/$p.out"; } ; cat "$out/$p.stats")
      wall=$(echo "$stats" | field wall_ns)
      if [ -z "$best" ] || [ "$wall" -lt "$(echo "$best" | field wall_ns)" ]; then best=$stats; fi
    done
    size=$(sed -n 's|^// Size: ||p' "bench/runtime/$p.fixed")
    printf '| `%s` | %s | %s | %.1f | %s | %s | %s | %s | %s | %s |\n' "$p" "$size" "$(cat "$out/$p.out")" \
      "$(echo "scale=3; $(echo "$best" | field wall_ns) / 1000000" | bc)" "$(echo "$best" | field peak_rss_kb)" \
      "$(echo "$best" | field allocs)" "$(echo "$best" | field reuses)" "$(echo "$best" | field frees)" \
      "$(echo "$best" | field dups)" "$(echo "$best" | field drops)"
  done
} > "$report"
echo "wrote $report"
