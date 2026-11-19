#!/usr/bin/env python3
"""Reject private runtime material and recognizable credentials from public source."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".git", ".venv", "venv", "build", "dist", "vendor", ".native", ".gradle", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "model-en-us", "jniLibs"}
PRIVATE_SUFFIXES = {".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".db", ".sqlite3", ".apk", ".gguf", ".env"}
PRIVATE_NAMES = {"config.json", "connection.json", "local.properties", "signing.properties", "server.env"}
PATTERNS = [
    re.compile(rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{40,}\b"),
    re.compile(rb"\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,}\b"),
]


def source_paths() -> list[Path]:
    if (ROOT / ".git").exists():
        paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
        return [ROOT / p for p in paths if p]
    return [p for p in ROOT.rglob("*") if p.is_file() and not any(part in EXCLUDED or part.endswith(".egg-info") for part in p.relative_to(ROOT).parts)]


def main() -> None:
    failures: list[str] = []
    paths = source_paths()
    for path in paths:
        relative = path.relative_to(ROOT)
        if path.is_symlink():
            failures.append(f"{relative}: symlink requires manual review")
            continue
        if path.suffix.lower() in PRIVATE_SUFFIXES or path.name in PRIVATE_NAMES or path.name.endswith(".connection.json") or "state" in relative.parts or any(part.startswith(".env") for part in relative.parts):
            failures.append(f"{relative}: private/generated file")
        data = path.read_bytes()
        if any(pattern.search(data) for pattern in PATTERNS):
            failures.append(f"{relative}: recognizable credential/private key")
    if failures:
        raise SystemExit("Public audit failed:\n" + "\n".join(failures))
    print(f"Public audit passed: {len(paths)} source files checked. Review images and custom configuration separately.")


if __name__ == "__main__":
    main()
