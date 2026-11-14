#!/usr/bin/env python3
"""
summary.py — render the build report that the CI workflow appends to the job summary.

Kept as a separate script rather than an inline heredoc inside build.yml: an indented
heredoc inside a YAML block scalar is fragile (and silently changes meaning when the
surrounding indentation is edited), so the workflow just calls this file.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILDS = ROOT / "builds"


def main() -> int:
    plugins_path = BUILDS / "plugins.json"
    if not plugins_path.exists():
        print("_No plugins.json produced._")
        return 0

    entries = json.loads(plugins_path.read_text(encoding="utf-8"))

    print("### CloudStream plugins")
    print()
    print(f"{len(entries)} plugin(s) published.")
    print()
    print("| Plugin | Version | Size | Language | Types | SHA-256 |")
    print("| --- | --- | --- | --- | --- | --- |")
    for entry in entries:
        sha = entry.get("fileHash", "")
        short = sha[:23] + "…" if len(sha) > 24 else sha
        types = ", ".join(entry.get("tvTypes") or [])
        size_kib = entry.get("fileSize", 0) / 1024
        print(
            f"| {entry.get('name', '?')} ({entry.get('internalName', '?')}) "
            f"| v{entry.get('version')} | {size_kib:.1f} KiB "
            f"| {entry.get('language', '')} | {types} | `{short}` |"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
