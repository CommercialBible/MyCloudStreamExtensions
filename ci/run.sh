#!/usr/bin/env bash
#
# ci/run.sh — the entire CI pipeline, as a normal script.
#
# Why this file exists: GitHub refuses to let a GitHub App installation token create
# or update .github/workflows/*.yml without the `workflows` permission. Keeping the
# logic here means it is version-controlled, reviewable and *runnable locally exactly
# as CI runs it*, and the workflow file itself is a thin, rarely-changing trigger.
#
# .github/workflows/build.yml is 20 lines and calls this script. Nothing else in the
# pipeline lives in YAML.
#
# Environment:
#   GITHUB_REPOSITORY        "owner/repo"            (set by Actions)
#   GITHUB_SERVER_URL        "https://github.com"    (set by Actions)
#   GITHUB_SHA               commit sha              (set by Actions)
#   GITHUB_REF_NAME          branch name             (set by Actions)
#   GITHUB_STEP_SUMMARY      summary file path       (set by Actions)
#   ONLY                     build one provider      (optional)
#   STRICT                   "true" to fail on any provider error (optional)
#   SKIP_PUBLISH             "true" to build+verify only (optional)
#   PUBLISH_REMOTE           override git remote for the builds branch (optional)
#   DEPS_DIR                 toolchain cache dir     (default .build-deps)
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

say() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }

REPO_SLUG="${GITHUB_REPOSITORY:-CommercialBible/MyCloudStreamExtensions}"
SERVER="${GITHUB_SERVER_URL:-https://github.com}"
REPO_URL="${SERVER}/${REPO_SLUG}"

# ---------------------------------------------------------------- 1. toolchain ----
say "fetching pinned dependencies"
./tools/fetch-deps.sh

# ---------------------------------------------------------------- 2. build ------
say "building providers"
BUILD_ARGS=(--repo-url "$REPO_URL")
[ -n "${ONLY:-}" ] && BUILD_ARGS+=(--only "$ONLY")
[ "${STRICT:-false}" = "true" ] && BUILD_ARGS+=(--strict)
python3 tools/build.py build "${BUILD_ARGS[@]}"

# ---------------------------------------------------------------- 3. verify -----
# Gate before publishing: a broken artifact must never reach the builds branch.
say "verifying artifacts"
python3 tools/build.py verify

# ---------------------------------------------------------------- 4. summary ----
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  python3 tools/summary.py >> "$GITHUB_STEP_SUMMARY"
fi

# ---------------------------------------------------------------- 5. publish ----
if [ "${SKIP_PUBLISH:-false}" = "true" ]; then
  say "SKIP_PUBLISH=true - not publishing"
  exit 0
fi

say "publishing to the builds branch"
./tools/publish-builds.sh \
  builds "${PUBLISH_REMOTE:-$REPO_URL.git}" \
  builds "build: publish plugins @ ${GITHUB_SHA:0:7}"

# ------------------------------------------------------- 6. version ledger ------
# The ledger must live on main so the next run can bump versions correctly.
# "[skip ci]" prevents this commit from triggering another build.
if git rev-parse --git-dir >/dev/null 2>&1 && [ -n "${GITHUB_REF_NAME:-}" ]; then
  if ! git diff --quiet -- config/versions.json; then
    say "recording version ledger"
    git config user.name  'github-actions[bot]'
    git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
    git add config/versions.json
    git commit -q -m "chore(build): record plugin versions [skip ci]"
    git pull --rebase --autostash -q origin "$GITHUB_REF_NAME" || true
    git push origin "HEAD:$GITHUB_REF_NAME"
  else
    say "version ledger unchanged"
  fi
fi

say "done"
