#!/usr/bin/env sh
# Claude Code PreToolUse hook (matcher: Bash) — see CLAUDE.md.
#
# Reminds Claude — by holding the commit — when:
#   1. Code is staged but no doc root (docs/, spec/, site/ when present) is,
#      and the commit message does not carry the acknowledgment "Docs: none".
#   2. Some but not all existing doc roots are staged, and the message does
#      not carry the acknowledgment "Tandem: ok".
#
# Exit 2 feeds stderr back to Claude as the block reason (Claude Code hook
# contract); exit 0 lets the command proceed.
set -eu

input=$(cat)

# tool_input.command from the hook's JSON payload.
if command -v jq >/dev/null 2>&1; then
  cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
else
  cmd=$(printf '%s' "$input" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi

# Only git commit invocations matter.
printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+commit\b' || exit 0

root=$(git rev-parse --show-toplevel 2>/dev/null || printf .)
cd "$root"

# Staged paths. `git commit -a` stages only after this hook runs, so fall
# back to the tracked worktree diff when the index is empty.
staged=$(git diff --cached --name-only)
if [ -z "$staged" ] && printf '%s' "$cmd" | grep -Eq '(^|[[:space:]])-a([[:space:]]|$)|(^|[[:space:]])--all([[:space:]]|$)'; then
  staged=$(git diff --name-only)
fi
[ -n "$staged" ] || exit 0

# Doc roots that exist in this repo.
doc_roots=
for r in docs spec site; do
  [ -d "$r" ] && doc_roots="$doc_roots $r"
done

code_touched=$(printf '%s' "$staged" | grep -E '^(compiler|scalafix|bench|examples)/|^build\.sbt$|^project/' || true)
doc_touched=$(printf '%s' "$staged" | grep -E '^(docs|spec|site)/' || true)

reasons=

# 1. Code without any documentation/specification update.
if [ -n "$code_touched" ] && [ -z "$doc_touched" ] \
   && ! printf '%s' "$cmd" | grep -qi 'docs:[[:space:]]*none'; then
  reasons="$reasons
- code changes are staged but nothing under docs/, spec/ or site/ is included"
fi

# 2. Doc roots not updated in tandem.
touched_roots=
missing_roots=
for r in $doc_roots; do
  if printf '%s' "$staged" | grep -q "^$r/"; then
    touched_roots="$touched_roots $r"
  else
    missing_roots="$missing_roots $r"
  fi
done
if [ -n "${touched_roots# }" ] && [ -n "${missing_roots# }" ] \
   && ! printf '%s' "$cmd" | grep -qi 'tandem:[[:space:]]*ok'; then
  reasons="$reasons
- doc roots are not updated in tandem: touched[$touched_roots ] missing[$missing_roots ]"
fi

if [ -n "$reasons" ]; then
  {
    echo "doc-sync: commit held so documentation and specifications stay in alignment (CLAUDE.md)"
    echo "$reasons"
    echo
    echo "Per CLAUDE.md every change must keep docs/, spec/ and site/ (the Laika"
    echo "sources for fixed.constructive.dev) in alignment. Either stage the missing"
    echo "updates now, or — only after verifying no update is needed — acknowledge it"
    echo "in the commit message with \"Docs: none\" (no doc update needed) or"
    echo "\"Tandem: ok\" (partial doc-root update is intentional) and retry the same"
    echo "command."
  } >&2
  exit 2
fi
exit 0
