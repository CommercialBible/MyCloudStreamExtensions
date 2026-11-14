#!/usr/bin/env bash
#
# publish-builds.sh — publish a build output directory to a git branch.
#
# Extracted from build.yml so it can be run and tested locally. Publishing is the one
# step that can destroy a working release if it goes wrong, so it is kept out of inline
# YAML and exercised by tools/test-publish.sh.
#
# Usage:
#   ./tools/publish-builds.sh <src-dir> <remote-url> [branch] [message]
#
# Behaviour:
#   * works whether or not the target branch already exists
#   * builds the commit in an isolated temp repo, so the caller's working tree,
#     branch and index are never touched
#   * if the content is byte-identical to what is already published, the branch is
#     NOT pushed (no empty commits, no churn)
#
set -euo pipefail

SRC_DIR="${1:?usage: publish-builds.sh <src-dir> <remote-url> [branch] [message]}"
REMOTE_URL="${2:?usage: publish-builds.sh <src-dir> <remote-url> [branch] [message]}"
BRANCH="${3:-builds}"
MESSAGE="${4:-build: publish plugins}"

if [ ! -d "$SRC_DIR" ]; then
  echo "publish: source directory '$SRC_DIR' does not exist" >&2
  exit 1
fi

# Guard against the worst failure mode: publishing an empty artifact set over a
# working one because the build silently produced nothing.
if [ -z "$(find "$SRC_DIR" -type f -print -quit)" ]; then
  echo "publish: refusing to publish '$SRC_DIR' because it contains no files" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/artifacts"
cp -a "$SRC_DIR/." "$WORK/artifacts/"

git init -q "$WORK/repo"
cd "$WORK/repo"
git config user.name  "${GIT_AUTHOR_NAME:-github-actions[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
git remote add origin "$REMOTE_URL"

# ls-remote exits 2 when no ref matches, so this is a clean existence test.
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "publish: branch '$BRANCH' exists - updating it"
  # Full (not shallow) fetch: publishing from a shallow clone is restricted, and the
  # branch only ever holds small artifacts so the history stays tiny.
  git fetch -q origin "$BRANCH"
  git checkout -q -B "$BRANCH" FETCH_HEAD
  find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
else
  echo "publish: branch '$BRANCH' does not exist - creating it"
  git checkout -q --orphan "$BRANCH"
fi

cp -a "$WORK/artifacts/." .
git add -A

if git diff --cached --quiet; then
  echo "publish: artifacts are identical to what is already published - nothing to do"
  exit 0
fi

git commit -q -m "$MESSAGE"
git push -q origin "HEAD:$BRANCH"
echo "publish: pushed $(git rev-list --count FETCH_HEAD..HEAD 2>/dev/null || echo 1) commit(s) to '$BRANCH'"
