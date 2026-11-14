#!/usr/bin/env python3
"""
build.py — compile CloudStream providers into .cs3 packages and generate repo metadata.

Single source of truth for everything build-related. Used identically by local runs and
by the GitHub Actions workflow, so "it works on my machine" == "it works in CI".

Subcommands
    build     compile + dex + package every enabled provider, then write metadata
    verify    validate an existing builds/ directory (hashes, sizes, schema)
    list      print the providers that would be built

Design notes
------------
* Providers are discovered from config/sites.json (not hardcoded here), so adding a
  provider requires no change to this file.
* Each provider is built in isolation. One failing provider never blocks the others,
  and never destroys its own previously published .cs3 (see merge_previous()).
* Versions live in config/versions.json and only increment when that provider's source
  bytes actually change. This survives commit squashing/rebasing, which
  "git rev-list --count" does not.
* Archives are written with fixed timestamps so identical sources produce an
  identical .cs3 byte-for-byte (reproducible builds).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROVIDERS_DIR = ROOT / "providers"
CONFIG_DIR = ROOT / "config"
BUILDS_DIR = ROOT / "builds"
WORK_DIR = ROOT / ".work"
DEPS_DIR = Path(os.environ.get("DEPS_DIR", ROOT / ".build-deps"))

MIN_API = 21
JVM_TARGET = 11

# Host-provided at runtime; must NOT be bundled into the .cs3.
HOST_PROVIDED = ["cloudstream.jar", "jsoup.jar", "nicehttp.jar", "jspecify.jar", "android.jar"]

# Fixed zip timestamp -> reproducible archives.
ZIP_DATE = (1980, 1, 1, 0, 0, 0)


# --------------------------------------------------------------------------- utils ---

def log(msg: str) -> None:
    print(f"\033[1;34m==>\033[0m {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"\033[1;33m[WARN]\033[0m {msg}", flush=True)


def err(msg: str) -> None:
    print(f"\033[1;31m[ERR]\033[0m {msg}", file=sys.stderr, flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 256), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path: Path, default=None):
    if not path.exists():
        return default
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def save_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def run(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, cwd=str(cwd or ROOT), capture_output=True, text=True
    )


# ------------------------------------------------------------------------- config ---

def load_sites() -> dict:
    sites = load_json(CONFIG_DIR / "sites.json")
    if sites is None:
        raise SystemExit("config/sites.json is missing")
    return sites


def enabled_sites(sites: dict, only: str | None = None) -> list[dict]:
    out = []
    for site in sites.get("sites", []):
        if not site.get("enabled", True):
            continue
        if only and site["providerName"] != only:
            continue
        if not (PROVIDERS_DIR / site["providerName"]).is_dir():
            warn(f"{site['providerName']}: enabled in sites.json but no provider directory, skipping")
            continue
        out.append(site)
    return out


def provider_sources(name: str) -> list[Path]:
    src = PROVIDERS_DIR / name / "src"
    if not src.is_dir():
        return []
    return sorted(p for p in src.rglob("*.kt"))


def source_hash(name: str) -> str:
    """Hash of the provider's Kotlin sources (path + content), order-independent."""
    h = hashlib.sha256()
    for path in provider_sources(name):
        rel = path.relative_to(PROVIDERS_DIR / name).as_posix()
        h.update(rel.encode())
        h.update(b"\0")
        h.update(path.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def next_version(name: str, versions: dict) -> tuple[int, bool]:
    """Return (version, changed). Bumps only when sources actually changed."""
    cur_hash = source_hash(name)
    entry = versions.get(name) or {}
    prev_hash = entry.get("sourceHash")
    prev_version = int(entry.get("version", 0))

    if prev_hash is None:
        return (1, True)          # first ever build
    if prev_hash == cur_hash:
        return (prev_version, False)  # unchanged -> keep version
    return (prev_version + 1, True)


# ---------------------------------------------------------------------- packaging ---

def parse_plugin_class(name: str) -> str:
    """
    Find the @CloudstreamPlugin class so manifest.json points at it.

    Both the package and the annotated class are read from source, so a provider can
    be renamed or moved without touching the build tooling.
    """
    for path in provider_sources(name):
        text = path.read_text(encoding="utf-8", errors="ignore")
        pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        match = re.search(
            r"@CloudstreamPlugin\b[\s\S]{0,200}?\bclass\s+(\w+)", text
        )
        if match and pkg:
            return f"{pkg.group(1)}.{match.group(1)}"
    raise RuntimeError(
        f"{name}: no @CloudstreamPlugin class found. "
        "Add `@CloudstreamPlugin class XPlugin : BasePlugin()` to the provider."
    )


def compile_provider(name: str, out_dir: Path) -> Path:
    """Compile all provider sources to .class files."""
    sources = provider_sources(name)
    if not sources:
        raise RuntimeError(f"{name}: no Kotlin sources under providers/{name}/src")

    kotlinc = DEPS_DIR / "kotlinc" / "bin" / "kotlinc"
    if not kotlinc.exists():
        raise RuntimeError("kotlinc missing - run tools/fetch-deps.sh")

    classpath = ":".join(
        str(DEPS_DIR / jar) for jar in HOST_PROVIDED
    ) + ":" + str(DEPS_DIR / "kotlinc" / "lib" / "kotlin-stdlib.jar")

    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(kotlinc), *[str(p) for p in sources],
        "-classpath", classpath,
        "-d", str(out_dir),
        "-jvm-target", str(JVM_TARGET),
        "-nowarn",
        # CloudStream providers reference release-only APIs.
        "-opt-in=com.lagradost.cloudstream3.Prerelease",
    ]
    res = run(cmd)
    if res.returncode != 0:
        raise RuntimeError(f"{name}: kotlinc failed\n{res.stdout}\n{res.stderr}")
    return out_dir


def dex_provider(name: str, classes_dir: Path, out_dir: Path) -> Path:
    """Convert .class files to classes.dex with D8."""
    r8 = DEPS_DIR / "r8.jar"
    if not r8.exists():
        raise RuntimeError("r8.jar missing - run tools/fetch-deps.sh")

    class_files = sorted(classes_dir.rglob("*.class"))
    if not class_files:
        raise RuntimeError(f"{name}: kotlinc produced no classes")

    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "java", "-cp", str(r8), "com.android.tools.r8.D8",
        "--release", "--min-api", str(MIN_API),
    ]
    for jar in HOST_PROVIDED:
        cmd += ["--lib", str(DEPS_DIR / jar)]
    cmd += ["--lib", str(DEPS_DIR / "kotlinc" / "lib" / "kotlin-stdlib.jar")]
    cmd += ["--output", str(out_dir)]
    cmd += [str(p) for p in class_files]

    res = run(cmd)
    if res.returncode != 0:
        raise RuntimeError(f"{name}: D8 failed\n{res.stdout}\n{res.stderr}")

    dex = out_dir / "classes.dex"
    if not dex.exists():
        raise RuntimeError(f"{name}: D8 produced no classes.dex")
    return dex


def write_cs3(
    name: str, plugin_class: str, version: int, dex: Path, dest: Path, language: str
) -> Path:
    """
    Package a .cs3 (a zip holding manifest.json + classes.dex).

    Entries are written in a fixed order with a fixed timestamp so the same inputs
    always give the same bytes -> stable hashes in plugins.json.
    """
    manifest = {
        "pluginClassName": plugin_class,
        "name": name,
        "version": version,
        "requiresResources": False,
    }

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".cs3.tmp")

    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo("manifest.json", date_time=ZIP_DATE)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        zf.writestr(info, json.dumps(manifest, separators=(",", ":")))

        info = zipfile.ZipInfo("classes.dex", date_time=ZIP_DATE)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        zf.writestr(info, dex.read_bytes())

    tmp.replace(dest)
    return dest


# ------------------------------------------------------------------------ metadata ---

def plugin_entry(site: dict, version: int, cs3: Path, repo_url: str) -> dict:
    """One object in plugins.json, matching the schema CloudStream expects."""
    return {
        "url": f"{repo_url}/raw/builds/{cs3.name}",
        "status": int(site.get("status", 1)),
        "version": int(version),
        "name": site.get("displayName") or site["providerName"],
        "internalName": site["providerName"],
        "authors": site.get("authors") or ["CommercialBible"],
        "description": site.get("description", ""),
        "fileSize": cs3.stat().st_size,
        "repositoryUrl": repo_url,
        "language": site.get("language", "en"),
        "tvTypes": site.get("tvTypes") or ["Movie"],
        "iconUrl": site.get("iconUrl", ""),
        "apiVersion": int(site.get("apiVersion", 1)),
        "fileHash": f"sha256-{sha256_file(cs3)}",
    }


def repo_json(sites: dict, repo_url: str, branch: str = "builds") -> dict:
    meta = sites.get("repository", {})
    return {
        "name": meta.get("name", "My CloudStream Repo"),
        "iconUrl": meta.get("iconUrl", ""),
        "description": meta.get("description", ""),
        "manifestVersion": int(meta.get("manifestVersion", 1)),
        "pluginLists": [f"{repo_url}/raw/{branch}/plugins.json"],
    }


def merge_previous(
    new_entries: list[dict], built_names: set[str], configured: set[str]
) -> list[dict]:
    """
    Keep last-known-good metadata for providers that failed to build.

    A failed build must never delete or downgrade a previously published release, so
    stale-but-working entries are carried over untouched.

    The distinction that matters: a provider that is still *configured* but failed to
    build is carried over (last-known-good), while a provider that is no longer
    configured at all - disabled with "enabled": false, or deleted from sites.json - is
    dropped. Otherwise "enabled": false would silently keep publishing a plugin.
    """
    prev = load_json(BUILDS_DIR / "plugins.json", default=[]) or []

    carried = []
    dropped = []
    for entry in prev:
        name = entry.get("internalName")
        if name in built_names:
            continue                      # freshly built this run
        if name not in configured:
            dropped.append(entry)         # disabled or removed from sites.json
            continue
        carried.append(entry)             # configured but failed -> keep last good

    for entry in dropped:
        warn(f"unpublished {entry.get('internalName')} (no longer enabled in sites.json)")

    for entry in carried:
        cs3 = BUILDS_DIR / entry["url"].rsplit("/", 1)[-1]
        if not cs3.exists():
            warn(f"carrying over metadata for {entry.get('internalName')} but {cs3.name} is missing")
        else:
            warn(f"kept previous release for {entry.get('internalName')} v{entry.get('version')}")

    merged = new_entries + carried
    merged.sort(key=lambda e: e.get("internalName", ""))
    return merged


def prune_orphan_artifacts(entries: list[dict]) -> None:
    """
    Delete .cs3 files in builds/ that no published entry references.

    The builds branch mirrors builds/ exactly, so a stale package left behind would keep
    being downloadable even after its provider was unpublished.
    """
    keep = {entry["url"].rsplit("/", 1)[-1] for entry in entries}
    for cs3 in BUILDS_DIR.glob("*.cs3"):
        if cs3.name not in keep:
            cs3.unlink()
            warn(f"removed orphaned artifact {cs3.name}")


# --------------------------------------------------------------------------- verbs ---

def cmd_list(args) -> int:
    sites = load_sites()
    versions = load_json(CONFIG_DIR / "versions.json", default={}) or {}
    for site in enabled_sites(sites, args.only):
        name = site["providerName"]
        version, changed = next_version(name, versions)
        flag = "changed" if changed else "unchanged"
        print(f"{name:24} v{version:<4} {flag:10} {site.get('baseUrl')}")
    return 0


def cmd_build(args) -> int:
    sites = load_sites()
    versions = load_json(CONFIG_DIR / "versions.json", default={}) or {}

    repo_url = args.repo_url or os.environ.get("GITHUB_REPOSITORY")
    if repo_url and not repo_url.startswith("http"):
        repo_url = f"https://github.com/{repo_url}"
    repo_url = repo_url or "https://github.com/CommercialBible/MyCloudStreamExtensions"

    targets = enabled_sites(sites, args.only)
    if not targets:
        err("no enabled providers to build")
        return 1

    BUILDS_DIR.mkdir(parents=True, exist_ok=True)
    WORK_DIR.mkdir(parents=True, exist_ok=True)

    built: list[dict] = []
    built_names: set[str] = set()
    failures: list[str] = []

    for site in targets:
        name = site["providerName"]
        log(f"building {name}")
        try:
            plugin_class = parse_plugin_class(name)
            version, changed = next_version(name, versions)
            log(f"  version {version} (source {'changed' if changed else 'unchanged'})")

            pkg_root = WORK_DIR / name
            if pkg_root.exists():
                shutil.rmtree(pkg_root)
            classes = compile_provider(name, pkg_root / "classes")
            dex = dex_provider(name, classes, pkg_root / "dex")
            cs3 = write_cs3(
                name, plugin_class, version, dex,
                BUILDS_DIR / f"{name}.cs3", site.get("language", "en"),
            )

            entry = plugin_entry(site, version, cs3, repo_url)
            built.append(entry)
            built_names.add(name)

            if changed:
                versions[name] = {"version": version, "sourceHash": source_hash(name)}
            elif name not in versions:
                versions[name] = {"version": version, "sourceHash": source_hash(name)}

            size_kb = cs3.stat().st_size / 1024
            log(f"  -> {cs3.name}  v{version}  {size_kb:.1f} KiB")

        except Exception as exc:  # noqa: BLE001 - one provider must not sink the rest
            err(f"{name}: {exc}")
            failures.append(name)

    # Never publish a half-empty repo: if nothing built, leave builds/ untouched.
    if not built:
        err("all providers failed - not publishing (previous release preserved)")
        return 1

    # Providers still enabled in sites.json. Used by merge_previous to tell three cases
    # apart: built this run, enabled but failed (carry last good), and no longer wanted
    # (disabled or deleted -> drop). Disabled entries must be excluded here, otherwise
    # "enabled": false would keep republishing the plugin forever.
    configured = {
        s["providerName"]
        for s in sites.get("sites", [])
        if s.get("providerName") and s.get("enabled", True)
    }

    merged = merge_previous(built, built_names, configured)
    prune_orphan_artifacts(merged)

    save_json(BUILDS_DIR / "plugins.json", merged)
    save_json(BUILDS_DIR / "repo.json", repo_json(sites, repo_url))
    save_json(CONFIG_DIR / "versions.json", dict(sorted(versions.items())))

    log(f"published {len(merged)} plugin entries ({len(built)} built this run)")
    if failures:
        warn(f"failed providers (previous release kept): {', '.join(failures)}")
        # Distinguish "partial success" for CI while still publishing good output.
        return 0 if not args.strict else 1
    return 0


def cmd_verify(args) -> int:
    builds = Path(args.dir).resolve()
    problems: list[str] = []

    plugins_path = builds / "plugins.json"
    repo_path = builds / "repo.json"
    if not plugins_path.exists():
        problems.append("plugins.json missing")
    if not repo_path.exists():
        problems.append("repo.json missing")
    if problems:
        for p in problems:
            err(p)
        return 1

    plugins = load_json(plugins_path) or []
    repo = load_json(repo_path) or {}

    if not isinstance(plugins, list) or not plugins:
        problems.append("plugins.json is not a non-empty array")
    if repo.get("manifestVersion") != 1:
        problems.append("repo.json manifestVersion must be 1")
    if not repo.get("pluginLists"):
        problems.append("repo.json has no pluginLists")

    required = {
        "url", "status", "version", "name", "internalName", "authors",
        "description", "fileSize", "repositoryUrl", "language", "tvTypes",
        "iconUrl", "apiVersion", "fileHash",
    }

    for entry in plugins:
        label = entry.get("internalName", "<unknown>")
        missing = required - set(entry)
        if missing:
            problems.append(f"{label}: missing keys {sorted(missing)}")
            continue

        cs3 = builds / entry["url"].rsplit("/", 1)[-1]
        if not cs3.exists():
            problems.append(f"{label}: {cs3.name} not found in {builds}")
            continue

        actual_size = cs3.stat().st_size
        if actual_size != entry["fileSize"]:
            problems.append(f"{label}: fileSize {entry['fileSize']} != actual {actual_size}")

        actual_hash = f"sha256-{sha256_file(cs3)}"
        if actual_hash != entry["fileHash"]:
            problems.append(f"{label}: hash mismatch\n  expected {entry['fileHash']}\n  actual   {actual_hash}")

        # Structural check of the .cs3 itself.
        try:
            with zipfile.ZipFile(cs3) as zf:
                names = zf.namelist()
                if "classes.dex" not in names:
                    problems.append(f"{label}: .cs3 has no classes.dex")
                if "manifest.json" not in names:
                    problems.append(f"{label}: .cs3 has no manifest.json")
                else:
                    manifest = json.loads(zf.read("manifest.json"))
                    if manifest.get("version") != entry["version"]:
                        problems.append(
                            f"{label}: manifest version {manifest.get('version')} "
                            f"!= plugins.json {entry['version']}"
                        )
                    if manifest.get("name") != entry["internalName"]:
                        problems.append(
                            f"{label}: manifest name {manifest.get('name')!r} "
                            f"!= internalName {entry['internalName']!r}"
                        )
        except zipfile.BadZipFile:
            problems.append(f"{label}: {cs3.name} is not a valid zip/.cs3")

    if problems:
        for p in problems:
            err(p)
        return 1

    log(f"verify OK: {len(plugins)} providers, all hashes and sizes match")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="CloudStream provider build tool")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_build = sub.add_parser("build")
    p_build.add_argument("--only", help="build a single provider by internalName")
    p_build.add_argument("--repo-url", help="repository URL, e.g. https://github.com/user/repo")
    p_build.add_argument("--strict", action="store_true", help="exit non-zero if any provider fails")
    p_build.set_defaults(func=cmd_build)

    p_verify = sub.add_parser("verify")
    p_verify.add_argument("--dir", default=str(BUILDS_DIR))
    p_verify.set_defaults(func=cmd_verify)

    p_list = sub.add_parser("list")
    p_list.add_argument("--only")
    p_list.set_defaults(func=cmd_list)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
