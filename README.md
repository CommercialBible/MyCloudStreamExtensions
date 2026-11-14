# MyCloudStreamExtensions

A personal, self-hosted [CloudStream 3](https://github.com/recloudstream/cloudstream) extension
repository with automated builds.

**Repository URL to add inside CloudStream:**

```
https://github.com/CommercialBible/MyCloudStreamExtensions/raw/builds/repo.json
```

Every push to `main` compiles the providers, packages them as `.cs3` plugins, recomputes
SHA-256 hashes and file sizes, and publishes the result to the `builds` branch. The URL
above never changes.

---

## Install

1. CloudStream → **Settings → Extensions → Add repository**
2. Paste the URL above
3. Open the repository and install the plugins you want

Updates arrive through CloudStream's normal extension-update flow. A provider's version
only increments when its own Kotlin sources change, so unrelated edits never re-download
every plugin.

---

## Providers

| Plugin | Site | Language | Types | Verification |
| --- | --- | --- | --- | --- |
| `ServerOneProvider` | `http://103.203.93.2/` | hi | Movie, TvSeries | **Live-verified** |

Each provider is independent: its own package, its own source directory, its own build.
One provider failing to compile never blocks or removes another.

### Honest verification status

"Verified" means checked against real markup; it does not mean every page type was seen.

**ServerOneProvider — fully verified against live HTML.**
Home page, sections, pagination, category filters, search, detail pages and playback were
all exercised. Search uses `GET /movies?search=` because the site's own `/search_movies`
AJAX endpoint returns HTTP 500. A resolved playback URL returned `HTTP 206
video/x-matroska`.

### Removed providers

`CTGMoviesProvider` and `KhulnaPlexProvider` were removed at the owner's request. Anyone
who already installed them keeps the plugin until they uninstall it — CloudStream does not
delete installed plugins when they disappear from a repository.

---

## How the repository is built

```
config/sites.json ──► tools/build.py ──► .cs3 + plugins.json + repo.json ──► builds branch
        ▲                    ▲
        │                    └── tools/fetch-deps.sh (pinned toolchain)
   add a site here
```

No Gradle and no Android SDK are required. The pipeline is:

1. `kotlinc` compiles the provider's Kotlin against the CloudStream API
2. R8/D8 converts the resulting classes to `classes.dex`
3. The dex and a generated `manifest.json` are zipped into `<Provider>.cs3`
4. `builds/plugins.json` and `builds/repo.json` are regenerated with fresh hashes and sizes

Archives are written with fixed timestamps and entries in a fixed order, so identical
sources produce a byte-identical `.cs3`.

### Repository layout

```
providers/<Name>/src/main/kotlin/...   one directory per provider
providers/_template/                   copy-me starter (never built)
config/sites.json                      the source of truth: what gets built
config/versions.json                   version ledger (version + source hash)
ci/run.sh                              the whole CI pipeline (deps → build → verify → publish)
tools/build.py                         build / verify / list
tools/fetch-deps.sh                    downloads the pinned toolchain
tools/publish-builds.sh                publishes to the builds branch (tested)
tools/test-publish.sh                  tests for the publish script
tools/summary.py                       renders the CI job summary
.github/workflows/build.yml            thin trigger that calls ci/run.sh
builds/                                generated output (mirrored to the builds branch)
```

### ⚠️ One manual step: committing the workflow file

`.github/workflows/build.yml` is kept separate on purpose. GitHub refuses to let a
**GitHub App installation token** create or update anything under `.github/workflows/`
unless it holds the `workflows` permission — the push fails with:

```
refusing to allow a GitHub App to create or update workflow
`.github/workflows/build.yml` without `workflows` permission
```

Because of that, the CI pipeline does **not** live in YAML. It lives in `ci/run.sh`,
which is a normal script that pushes and runs fine. The workflow file is a ~20-line
trigger whose only job is to call it.

So automation is enabled by a one-time human action. Either:

- **GitHub web UI** → *Add file → Create new file* → path `.github/workflows/build.yml`
  → paste the contents from your local copy → commit, **or**
- push it from a machine authenticated as your own user account (not a GitHub App):
  `git push origin main`

Once that file exists, every later change to a provider, `ci/`, or `sites.json`
publishes itself with no further manual steps. Nothing else in the pipeline depends on
it — you can verify that by running the pipeline by hand:

```bash
SKIP_PUBLISH=true ./ci/run.sh      # deps → build → verify, exactly as CI runs it
```

### Adding a new provider

Adding a site requires **no change to any existing provider** and no change to the
workflow. The full procedure — including the verification checklist — is in
[docs/ADDING_A_PROVIDER.md](docs/ADDING_A_PROVIDER.md).

In short: copy `providers/_template/`, rename the package/classes, add an entry to
`config/sites.json`, push. The workflow detects the new provider from `sites.json`, builds
it, adds it to `plugins.json` with its own hash and size, and publishes it. Existing
plugins are untouched and keep their versions.

---

## Local development

```bash
./tools/fetch-deps.sh                  # one-time, ~200 MB, cached by CI

python3 tools/build.py list            # what would be built
python3 tools/build.py build \
  --repo-url https://github.com/CommercialBible/MyCloudStreamExtensions
python3 tools/build.py build --only ServerOneProvider     # build one provider
python3 tools/build.py verify          # check hashes, sizes and manifests
```

Requires JDK 21, Python 3.10+ and `unzip`.

### How versions are managed

`config/versions.json` stores, per provider, a version number and a hash of that
provider's Kotlin sources.

- sources unchanged → version unchanged, artifact byte-identical
- sources changed → version incremented by one

Hashing the content (rather than counting commits) means squashing, rebasing and reverting
all behave correctly.

### How a failed build is handled

`tools/build.py` treats each provider independently:

- a provider that fails to compile is reported, and its **previous entry is carried
  forward untouched** — the published `.cs3` is never replaced with a broken one
- if **every** provider fails, nothing is published at all and the job exits non-zero
- CI publishes only after the build **and** `verify` both succeed, so a failure anywhere
  can never overwrite a working release

---

## Security notes

- No credentials, tokens or cookies are stored in this repository. The workflow uses only
  the automatic `GITHUB_TOKEN` with `contents: write` — no custom secrets are required.
- No DRM, authentication gate, paywall or anti-bot protection is bypassed by any provider.
  Providers only read markup and media that the site itself serves publicly.
- A site behind an account wall is not logged into on your behalf: the provider reports
  that nothing is available rather than injecting credentials.

---

## License

MIT — see [LICENSE](LICENSE).
