#!/usr/bin/env bash
# =============================================================================
#  install-hooks — one-time setup after clone, and the repair tool thereafter.
#
#  Three things, each of which fails SILENTLY when missing:
#
#    1. core.hooksPath -> .githooks   Without it the pre-push branch protection
#       simply never runs; git gives no hint that a versioned hook exists.
#    2. origin/HEAD                   The hook resolves the protected branch
#       from it. Unset (some tools clone that way), the hook falls back to
#       guessing "main master".
#    3. index exec bits               A hook or .sh committed 100644 works on
#       Windows (everything runs via `bash foo.sh`) and breaks on Linux/macOS:
#       "Permission denied" for scripts, and for the hook — silence. The
#       verify_script_modes check in both test tiers reports this; this script
#       is the repair it points at. Fixes are STAGED, so they must be
#       committed to stick.
#
#  Safe to re-run at any time; when everything is already right it changes
#  nothing.
# =============================================================================
set -uo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[1/3] core.hooksPath -> .githooks"
git config core.hooksPath .githooks

echo "[2/3] origin/HEAD (the pre-push hook resolves the protected branch from it)"
if git symbolic-ref -q refs/remotes/origin/HEAD >/dev/null; then
    echo "      already set: $(git symbolic-ref --short refs/remotes/origin/HEAD)"
elif git remote set-head origin -a >/dev/null 2>&1; then
    echo "      set: $(git symbolic-ref --short refs/remotes/origin/HEAD)"
else
    echo "      could not reach origin — the hook will fall back to 'main master'."
fi

echo "[3/3] index exec bits (100755 for .githooks/* and every tracked .sh)"
fixed=0
while IFS=$'\t' read -r meta path; do
    case "$path" in
        *.sh|.githooks/*) ;;
        *) continue ;;
    esac
    mode="${meta%% *}"
    if [[ "$mode" != "100755" ]]; then
        git update-index --chmod=+x "$path"
        echo "      fixed: $path ($mode -> 100755)"
        fixed=1
    fi
done < <(git ls-files -s -- .githooks scripts .claude/hooks)
if [[ $fixed -eq 1 ]]; then
    echo "      NOTE: mode fixes are STAGED — commit them or they do not stick."
else
    echo "      all correct."
fi
echo "Done."
