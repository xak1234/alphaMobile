"""Private, immutable APK releases. Publishing is an administrator CLI operation."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any

MAX_APK = 512 * 1024 * 1024


class Releases:
    def __init__(self, state: Path):
        self.root = state / "releases"

    def apk(self, digest: str) -> Path:
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise FileNotFoundError("Release not found")
        path = self.root / (digest + ".apk")
        if not path.is_file() or path.is_symlink():
            raise FileNotFoundError("Release not found")
        return path

    def latest(self) -> dict[str, Any]:
        manifest = self.root / "latest.json"
        if not manifest.exists():
            return {"available": False}
        result: dict[str, Any] = json.loads(manifest.read_text())
        path = self.apk(result["sha256"])
        if path.stat().st_size != result["size"]:
            raise ValueError("Release file is incomplete")
        return result

    def publish(self, source: Path, version_name: str, version_code: int,
                min_sdk: int, notes: str) -> dict[str, Any]:
        if not re.fullmatch(r"[A-Za-z0-9.+_-]{1,40}", version_name):
            raise ValueError("Invalid version name")
        if not 1 <= version_code <= 2100000000 or not 26 <= min_sdk <= 100:
            raise ValueError("Invalid version code or minimum SDK")
        if len(notes) > 8000 or not 0 < source.stat().st_size <= MAX_APK:
            raise ValueError("Release notes or APK size exceeds limit")
        with zipfile.ZipFile(source) as archive:
            if not {"AndroidManifest.xml", "classes.dex"} <= set(archive.namelist()):
                raise ValueError("Not an Android application APK")
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Hash the bytes actually copied, then publish the pointer last. Readers never
        # see a partial APK, and existing download URLs remain valid after publication.
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.root, delete=False) as output:
                temporary = Path(output.name)
                digest = hashlib.sha256()
                size = 0
                with source.open("rb") as incoming:
                    while chunk := incoming.read(1024 * 1024):
                        size += len(chunk)
                        if size > MAX_APK:
                            raise ValueError("APK exceeds limit")
                        output.write(chunk)
                        digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
            sha = digest.hexdigest()
            previous = self.latest()
            if previous.get("available") and (
                version_code < previous["version_code"] or
                (version_code == previous["version_code"] and sha != previous["sha256"])
            ):
                raise ValueError("Version code must increase for a different APK")
            os.replace(temporary, self.root / (sha + ".apk"))
            temporary = None
            result = {"available": True, "version_name": version_name,
                      "version_code": version_code, "min_sdk": min_sdk,
                      "size": size, "sha256": sha, "release_notes": notes,
                      "published_at": time.time(), "apk_path": "/api/updates/apk/" + sha}
            with tempfile.NamedTemporaryFile(mode="w", dir=self.root, delete=False) as manifest:
                temporary = Path(manifest.name)
                json.dump(result, manifest)
                manifest.flush()
                os.fsync(manifest.fileno())
            os.replace(temporary, self.root / "latest.json")
            temporary = None
            return result
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish an already signed alphaMobile APK")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--state", type=Path, default=Path("state"))
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--min-sdk", default=26, type=int)
    parser.add_argument("--notes-file", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(Releases(args.state).publish(args.apk, args.version_name,
                     args.version_code, args.min_sdk, args.notes_file.read_text()), indent=2))


if __name__ == "__main__":
    main()
