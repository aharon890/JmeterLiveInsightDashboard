#!/usr/bin/env bash
# =============================================================================
#  pre-push-gate — Claude Code PreToolUse hook (matcher: Bash), wired in
#  .claude/settings.json.
#
#  Enforces what the run-tests skill instructs: a feature/fix must have a fully
#  green regression run against the exact commit being pushed BEFORE `git push`
#  uploads it. There is no CI — if that run did not happen locally, it never
#  happens anywhere — so the gate sits on the push, the last moment it can.
#
#  scripts/test-regression.sh records `git rev-parse HEAD^{tree}` in
#  test-results/.regression-green only when every layer PASSes on a clean,
#  committed tree (scripts/merge-pr.sh trusts the same marker). A run with a
#  SKIP writes nothing, because a skipped layer is a gap, not a pass. This hook
#  lets `git push` through only when that recorded hash matches the tree of
#  current HEAD. Tree, not commit sha, deliberately: a merge commit whose tree
#  is identical to the tested branch head is the same content under a new sha
#  and must stay green, while any actual content change still invalidates the
#  marker.
#
#  Deliberately NOT gated (they upload no new commits):
#    - branch deletions:  --delete / -d / ":ref" push refspecs
#    - tag-only pushes:   --tags
#  Escape hatch, explicit on the command line like ALLOW_PUSH_TO_PROTECTED in
#  .githooks/pre-push:   PUSH_GATE_BYPASS=1 git push ...
#  It is for the human operator; Claude must not reach for it on its own.
#
#  Hook contract: exit 0 allows the tool call, exit 2 blocks it and feeds
#  stderr back to the model. Anything unparseable exits 0 — this gate must
#  never break unrelated Bash commands.
# =============================================================================
set -uo pipefail

payload=$(cat 2>/dev/null | tr -d '\n\r') || exit 0
[[ -n "$payload" ]] || exit 0

# Pull tool_input.command out of the hook JSON without a jq dependency: find
# the first "command" key, then copy characters up to the first unescaped
# quote. Escapes are dropped (\" -> ", \n -> n) — good enough for matching.
cmd=$(printf '%s' "$payload" | awk '{
    if (!match($0, /"command"[[:space:]]*:[[:space:]]*"/)) exit
    s = substr($0, RSTART + RLENGTH)
    out = ""; esc = 0
    for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (esc)            { out = out c; esc = 0 }
        else if (c == "\\") { esc = 1 }
        else if (c == "\"") { break }
        else                { out = out c }
    }
    print out
}')
[[ -n "$cmd" ]] || exit 0

# Only gate commands that actually invoke `git push` (tolerating `git -C dir`),
# including inside chains (`... && git push`). `git commit -m "push it"` does
# not match: the word after `git` must be `push` itself.
if ! printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]({])git[[:space:]]+(-C[[:space:]]+[^[:space:]]+[[:space:]]+)?push([[:space:]]|$)'; then
    exit 0
fi

case "$cmd" in *PUSH_GATE_BYPASS=1*) exit 0 ;; esac

# Deletions and tag-only pushes carry no commits to verify.
if printf '%s' "$cmd" | grep -qE -- '--delete|(^|[[:space:]])-d([[:space:]]|$)|--tags|[[:space:]]:[^[:space:]]'; then
    exit 0
fi

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[[ -n "$root" ]] || exit 0
head_tree=$(git -C "$root" rev-parse 'HEAD^{tree}' 2>/dev/null) || exit 0
[[ -n "$head_tree" ]] || exit 0

marker="$root/test-results/.regression-green"
if [[ -f "$marker" ]]; then
    green_tree=$(tr -d '[:space:]' < "$marker" 2>/dev/null)
    [[ "$green_tree" == "$head_tree" ]] && exit 0
fi

{
    echo "PUSH GATE: no fully green regression run is recorded for the content at"
    echo "HEAD (tree ${head_tree:0:12})."
    echo ""
    echo "There is no CI — nothing re-checks this push later. Run the regression"
    echo "tier first (the run-tests skill covers this):"
    echo ""
    echo "    ./scripts/test-regression.sh"
    echo ""
    echo "A fully green run (no FAIL, no SKIP) records this content in"
    echo "test-results/.regression-green and the push will then go through."
    echo "If regression already ran but the content changed since (new commit,"
    echo "further edits), re-run it — the recorded run vouches for something else."
    echo "Do not use PUSH_GATE_BYPASS=1 unless the user explicitly asks for it."
} >&2
exit 2
