#!/usr/bin/env bash
#
# test-publish.sh — exercise tools/publish-builds.sh against a throwaway bare repo.
#
# Publishing is the only step that can destroy a working release, and it cannot be
# tested by "reading the YAML carefully". This runs the real script against a real git
# remote and asserts the four behaviours that matter:
#
#   1. first publish creates the branch
#   2. republishing identical artifacts pushes nothing (no empty commits)
#   3. changed artifacts update the branch and the old file is gone
#   4. an empty source directory is refused outright
#
# Usage: ./tools/test-publish.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
PUBLISH="$ROOT/tools/publish-builds.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass() { printf '  \033[1;32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; exit 1; }

echo "== publish-builds.sh =="

# --- setup: bare remote + an artifact directory -------------------------------------
git init -q --bare "$TMP/remote.git"
mkdir -p "$TMP/artifacts/icons"
echo '{"plugins":["a"]}' > "$TMP/artifacts/plugins.json"
echo 'cs3-a' > "$TMP/artifacts/AProvider.cs3"
echo 'icon' > "$TMP/artifacts/icons/AProvider.png"

count_commits() {
  git --git-dir="$TMP/remote.git" rev-list --count "$1" 2>/dev/null || echo 0
}

# --- 1. first publish creates the branch --------------------------------------------
"$PUBLISH" "$TMP/artifacts" "$TMP/remote.git" builds "test: first" >/dev/null
git --git-dir="$TMP/remote.git" rev-parse --verify builds >/dev/null 2>&1 \
  || fail "branch not created on first publish"
files=$(git --git-dir="$TMP/remote.git" ls-tree -r --name-only builds | sort | tr '\n' ' ')
[ "$files" = "AProvider.cs3 icons/AProvider.png plugins.json " ] \
  || fail "unexpected tree contents: $files"
pass "first publish creates branch with all files (incl. subdirectories)"

# --- 2. identical artifacts push nothing --------------------------------------------
before=$(count_commits builds)
"$PUBLISH" "$TMP/artifacts" "$TMP/remote.git" builds "test: noop" >/dev/null
after=$(count_commits builds)
[ "$before" = "$after" ] || fail "identical publish created a commit ($before -> $after)"
pass "identical artifacts produce no commit (no churn for CloudStream)"

# --- 3. changed artifacts update the branch, stale files removed --------------------
echo '{"plugins":["a","b"]}' > "$TMP/artifacts/plugins.json"
rm -f "$TMP/artifacts/AProvider.cs3"
echo 'cs3-b' > "$TMP/artifacts/BProvider.cs3"
"$PUBLISH" "$TMP/artifacts" "$TMP/remote.git" builds "test: update" >/dev/null
before=$after
after=$(count_commits builds)
[ "$after" -gt "$before" ] || fail "changed artifacts did not produce a commit"

files=$(git --git-dir="$TMP/remote.git" ls-tree -r --name-only builds | sort | tr '\n' ' ')
[ "$files" = "BProvider.cs3 icons/AProvider.png plugins.json " ] \
  || fail "update did not mirror exactly: $files"
git --git-dir="$TMP/remote.git" show builds:plugins.json | grep -q '"b"' \
  || fail "plugins.json content not updated"
pass "update mirrors exactly: stale .cs3 removed, new file added"

# --- 4. empty source directory is refused -------------------------------------------
mkdir -p "$TMP/empty"
if "$PUBLISH" "$TMP/empty" "$TMP/remote.git" builds "test: empty" >/dev/null 2>&1; then
  fail "empty artifact directory was published"
fi
final=$(count_commits builds)
[ "$final" = "$after" ] || fail "refused publish still mutated the branch"
pass "empty artifact directory refused without touching the branch"

# --- 5. a corrupt publish attempt leaves the live branch usable ----------------------
git --git-dir="$TMP/remote.git" show builds:plugins.json >/dev/null \
  || fail "branch unreadable after refused publish"
pass "branch still readable after a refused publish"

echo "  all publish tests passed"
