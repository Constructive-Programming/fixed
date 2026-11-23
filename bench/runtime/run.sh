#!/usr/bin/env bash
# Build the runtime benchmarks, run each one RUNS times, check each result against
# bench/runtime/<program>.expected, and write docs/baselines/runtime-<date>-<mode>.md
# (docs/plans/phase-4.0-backend-slice.md §4.9). NOTE, if set, replaces the description paragraph.
# MODE is the build mode (spec/compilation_model.md Rule CM5.7): default, no-specialise, or
# no-ownership-clones (type-axis clones only, `--no-ownership-clones`, to compare the two axes).
# B, if set, is the clone budget of Rule CM5.3 (`--budget B`); the report name then ends in -B<n>.
# Usage: [NOTE=...] [MODE=...] [B=...] bench/runtime/run.sh [RUNS]   (default 5). Needs sbt and clang on PATH.
set -euo pipefail
cd "$(dirname "$0")/../.."

runs=${1:-5}
out=target/bench-runtime
mkdir -p "$out"
progs=(bintrees nat_loop list_pipeline records fail_chain shared_map fresh_map acc_loop points temps scratch_tree)
mode=${MODE:-default}
case $mode in
  default) flag="" ;;
  no-specialise) flag="--no-specialise" ;;
  no-ownership-clones) flag="--no-ownership-clones" ;;
  *) echo "MODE must be default, no-specialise or no-ownership-clones" >&2; exit 2 ;;
esac
if [ -n "${B:-}" ]; then
  [[ $B =~ ^[0-9]+$ ]] || { echo "B must be a non-negative integer" >&2; exit 2; }
  flag="$flag --budget $B"; mode="$mode-B$B"
fi

cmds=()
for p in "${progs[@]}"; do cmds+=("compiler/run ship --stats $flag bench/runtime/$p.fixed -o $out/$p-$mode"); done
sbt -warn "${cmds[@]}"

field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }

report=docs/baselines/runtime-$(date +%F)-$mode.md
{
  echo "# Runtime baseline, $(date +%F), mode $mode"
  echo
  echo "${NOTE:-Build mode \`$mode\`. Phase 4.0 backend slice (\`docs/plans/phase-4.0-backend-slice.md\` §4.9): everything boxed and reference-counted, no fusion, no reuse, no borrowing. Later milestones report against these numbers.}"
  echo
  echo "- Commit: \`$(git rev-parse --short HEAD)\`$(git diff --quiet HEAD -- compiler bench || echo ' (with local changes)')"
  echo "- Machine: $(uname -srm), $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ *//')"
  echo "- Toolchain: $(clang --version | head -1), \`clang -O2\`"
  echo "- Wall time: best of $runs runs, measured by the runtime from \`main\` start to end (no process start-up)."
  echo "- Counts are deterministic: every run gives the same allocations, reuses, \`dup\`s and \`drop\`s."
  echo "- Reuses: without a test (exclusive) / \`rc == 1\` test passed / test failed (the cell is dropped, a fresh one allocated)."
  echo
  echo "| Program | Size | Result | Wall (ms) | Peak RSS (KiB) | Allocations | Reuses (excl / pass / fail) | Frees | dups | drops | Witness calls |"
  echo "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"
  for p in "${progs[@]}"; do
    best=""; result=""; bin="$out/$p-$mode"
    for _ in $(seq "$runs"); do
      stats=$( { result=$(FIXED_RT_STATS=1 "$bin" 2>"$bin.stats"); echo "$result" > "$bin.out"; } ; cat "$bin.stats")
      wall=$(echo "$stats" | field wall_ns)
      if [ -z "$best" ] || [ "$wall" -lt "$(echo "$best" | field wall_ns)" ]; then best=$stats; fi
    done
    cmp -s "$bin.out" "bench/runtime/$p.expected" || { echo "$p: result differs from bench/runtime/$p.expected" >&2; exit 1; }
    size=$(sed -n 's|^// Size: ||p' "bench/runtime/$p.fixed")
    printf '| `%s` | %s | %s | %.1f | %s | %s | %s | %s | %s | %s | %s |\n' "$p" "$size" "$(cat "$bin.out")" \
      "$(echo "scale=3; $(echo "$best" | field wall_ns) / 1000000" | bc)" "$(echo "$best" | field peak_rss_kb)" \
      "$(echo "$best" | field allocs)" \
      "$(echo "$best" | field reuse_excl) / $(echo "$best" | field reuse_test_pass) / $(echo "$best" | field reuse_test_fail)" \
      "$(echo "$best" | field frees)" \
      "$(echo "$best" | field dups)" "$(echo "$best" | field drops)" "$(echo "$best" | field wops)"
  done
} > "$report"
echo "wrote $report"
