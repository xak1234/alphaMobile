#!/usr/bin/env python3
"""Fetch the real offline speech model and verify its pinned SHA-256."""
import hashlib
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path

URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    target = ROOT / "android/app/src/main/assets/model-en-us"
    with tempfile.TemporaryDirectory(prefix="alphamobile-speech-") as temp:
        archive = Path(temp) / "speech.zip"
        with urllib.request.urlopen(URL, timeout=120) as source, archive.open("wb") as output:
            shutil.copyfileobj(source, output)
        with archive.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != SHA256:
            raise RuntimeError("Speech archive checksum mismatch")
        with zipfile.ZipFile(archive) as bundle:
            for item in bundle.infolist():
                if Path(item.filename).is_absolute() or ".." in Path(item.filename).parts:
                    raise RuntimeError("Unexpected path in speech archive")
            bundle.extractall(temp)
        model = Path(temp) / "vosk-model-small-en-us-0.15"
        if not (model / "am/final.mdl").is_file():
            raise RuntimeError("Archive does not contain the acoustic model")
        shutil.copytree(model, target, dirs_exist_ok=True)
        (target / "uuid").write_text(SHA256[:32], encoding="utf-8")
    print("Offline speech model ready; SHA-256 verified.")


if __name__ == "__main__":
    main()
