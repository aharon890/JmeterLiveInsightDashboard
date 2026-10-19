#!/usr/bin/env bash
# =============================================================================
#  MERGE GATE — merge the PR only on a full green regression of what merges.
#
#  This repo runs no CI. Nothing on the server re-checks a branch, so the only
#  thing between a broken change and master is a local run — and a gate you have
#  to remember is not a gate. This makes the regression tier a precondition of
#  merging rather than a habit.
#
#      ./scripts/merge-pr.sh              # infer the PR from the current branch
#      ./scripts/merge-pr.sh 11           # explicit PR number
#      ./scripts/merge-pr.sh --squash     # pass through a merge strategy
#
#  It refuses to merge unless:
#    - the working tree is clean          (you tested what is committed)
#    - local HEAD == the PR's head        (you tested what will merge)
#    - the branch already contains master (you tested the MERGE RESULT, not just
#                                          the branch — master may have moved)
#    - a fully green regression is recorded for this exact content
#
#  The last one is usually already true: the regression you ran before pushing
#  (the push gate demands one) recorded HEAD's tree in
#  test-results/.regression-green, and the checks above make that tree the merge
#  result, so it is reused rather than run twice. Otherwise this runs the tier
#  itself, with Docker required so no layer can skip.
#
#  Requires the GitHub CLI (`gh auth login` once).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

PR_NUMBER=""
MERGE_FLAGS=()
for arg in "$@"; do
    case "$arg" in
        --squash|--rebase|--merge|--delete-branch|--admin) MERGE_FLAGS+=("$arg") ;;
        [0-9]*)                                           PR_NUMBER="$arg" ;;
        -h|--help)                                        sed -n '2,28p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done
# Default to a merge commit, matching the history this repo already has.
[[ ${#MERGE_FLAGS[@]} -eq 0 ]] && MERGE_FLAGS=(--merge)

print_banner "Merge Gate — Live Insight Dashboard"

cd "$PROJECT_DIR"

die() { echo "" >&2; echo "  BLOCKED: $1" >&2; echo "" >&2; exit 1; }

command -v gh >/dev/null 2>&1 || die "the GitHub CLI (gh) is not installed."
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"

branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "master" || "$branch" == "main" ]] && \
    die "you are on '$branch'. Check out the PR's branch first."

# --- Preconditions: make sure what we test is what merges --------------------

# Uncommitted work is not in the PR, so testing with it present proves nothing
# about what would actually land.
if [[ -n "$(git status --porcelain)" ]]; then
    echo "Uncommitted changes:"
    git status --short
    die "the working tree is not clean. Commit or stash first."
fi

echo "[1/6] Fetching origin ..."
git fetch -q origin || die "git fetch failed."

if [[ -z "$PR_NUMBER" ]]; then
    PR_NUMBER="$(gh pr list --head "$branch" --state open --json number -q '.[0].number' 2>/dev/null)"
    [[ -n "$PR_NUMBER" && "$PR_NUMBER" != "null" ]] || \
        die "no open PR found for branch '$branch'. Pass the number explicitly."
fi

# One field per call via gh's own -q. Parsing a JSON blob with sed breaks the
# first time a title contains a quote or a comma.
pr_field() { gh pr view "$PR_NUMBER" --json "$1" -q ".$1" 2>/dev/null; }

pr_state="$(pr_field state)"  || die "cannot read PR #$PR_NUMBER."
pr_head="$(pr_field headRefOid)"
pr_base="$(pr_field baseRefName)"
pr_title="$(pr_field title)"

echo "      PR #$PR_NUMBER  ($branch -> $pr_base)"
echo "      $pr_title"

[[ "$pr_state" == "OPEN" ]] || die "PR #$PR_NUMBER is $pr_state, not OPEN."

# Testing a different commit than the one GitHub will merge is the classic way a
# green gate ships a broken tree.
local_head="$(git rev-parse HEAD)"
if [[ "$local_head" != "$pr_head" ]]; then
    echo "      local HEAD : $local_head"
    echo "      PR head    : $pr_head"
    die "local HEAD is not the PR's head commit. Push or pull so they match."
fi

# The branch must already contain the base, or the tree we test is not the tree
# that results from merging — and any interaction with what landed on master
# since is untested, which is precisely what a pre-merge gate exists to catch.
echo "[2/6] Checking the branch is up to date with $pr_base ..."
behind="$(git rev-list --count "HEAD..origin/$pr_base")"
if [[ "$behind" -gt 0 ]]; then
    echo "      origin/$pr_base has $behind commit(s) not in this branch:"
    git log --oneline "HEAD..origin/$pr_base" | head -5 | sed 's/^/        /'
    die "merge origin/$pr_base into this branch first, then re-run:
             git merge origin/$pr_base
         Testing without it verifies the branch, not the merge result."
fi
echo "      up to date."

# --- The gate itself ---------------------------------------------------------
# A green run already recorded for this exact content is the gate passed. The
# tier records a tree only when every layer passed, nothing skipped, on a clean
# tree that was committed; the checks above make HEAD's tree the merge result.
head_tree="$(git rev-parse 'HEAD^{tree}')"
green_tree="$(tr -d '[:space:]' < "$PROJECT_DIR/test-results/.regression-green" 2>/dev/null)"
if [[ -n "$green_tree" && "$green_tree" == "$head_tree" ]]; then
    echo "[3/6] A fully green regression is already recorded for this exact content"
    echo "      (tree ${head_tree:0:12}) — not running it twice."
    echo "[4/6] (reused)"
    echo "[5/6] (a run with a skipped layer is never recorded)"
else
    # The container layer is the only one regression will skip rather than fail,
    # and a skip is not a pass. Refuse now rather than after two minutes of
    # testing. Also clear SKIP_DOCKER so an exported value cannot quietly hollow
    # out the gate.
    echo "[3/6] Checking Docker (the container layer must not skip) ..."
    unset SKIP_DOCKER
    if ! docker info >/dev/null 2>&1; then
        die "Docker is not running. The Docker E2E is the only layer that exercises
         real JMeter and the real jar, and with no CI a skip here means it never
         runs at all. Start Docker Desktop and re-run."
    fi
    echo "      running."

    echo "[4/6] Running the regression tier (no green run recorded for this content) ..."
    echo ""
    mkdir -p "$RESULTS_DIR"
    gate_log="$RESULTS_DIR/merge-gate.log"
    # pipefail is on, so this reflects the script's status, not tee's.
    if ! bash "$PROJECT_DIR/scripts/test-regression.sh" 2>&1 | tee "$gate_log"; then
        die "regression failed. Nothing was merged. Details: $gate_log"
    fi

    # Belt and braces on the skip check: test-regression.sh exits 0 with skipped
    # layers by design — reasonable while iterating, not acceptable as the last
    # check before master. $SKIP cannot be read directly here (the tier runs in
    # its own process), so detect it from the recorded output.
    echo ""
    echo "[5/6] Confirming no layer was skipped ..."
    if grep -qE '\[SKIP\]' "$gate_log"; then
        grep -E '\[SKIP\]' "$gate_log" | sed 's/^/      /'
        die "regression passed but skipped a layer — not full coverage.
         With no CI, a skipped layer here is a layer that never runs."
    fi
    echo "      all layers ran."
fi

# --- Merge -------------------------------------------------------------------
echo "[6/6] Merging PR #$PR_NUMBER (${MERGE_FLAGS[*]}) ..."
if ! gh pr merge "$PR_NUMBER" "${MERGE_FLAGS[@]}"; then
    die "gh pr merge failed. The regression passed, so this is a merge problem,
         not a test problem."
fi

echo ""
echo "==============================================================="
echo "  MERGED: PR #$PR_NUMBER -> $pr_base"
echo "  Gated on a full local regression with every layer run."
echo "==============================================================="
echo ""
echo "Update your local $pr_base:"
echo "  git switch $pr_base && git pull"
