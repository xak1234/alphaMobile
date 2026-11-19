"""Operator configuration and private connection-file creation."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


def endpoint(value: str) -> str:
    parsed = urlsplit(value)
    local = parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    if not parsed.hostname or (parsed.scheme != "https" and not local):
        raise ValueError("Use an HTTPS relay URL (HTTP is allowed only on loopback)")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Relay URL must not contain credentials, query or fragment")
    try:
        _ = parsed.port
    except ValueError as error:
        raise ValueError("Invalid relay port") from error
    return value.rstrip("/")


def private_json(path: Path, value: dict[str, Any]) -> None:
    """Create once, mode 0600 on POSIX; never overwrite an operator's secrets."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def load_config(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Relay config must be a JSON object")
    if not isinstance(value.get("state"), str) or not Path(value["state"]).is_absolute():
        raise ValueError("Relay config state must be an absolute directory")
    hosts = value.get("allowed_hosts")
    if not isinstance(hosts, list) or not hosts or any(not isinstance(h, str) or not h or any(c in h for c in "\n\r/* ,") for h in hosts):
        raise ValueError("allowed_hosts must contain explicit hostnames")
    port = value.get("port", 8793)
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
        raise ValueError("Relay port must be between 1 and 65535")
    host = value.get("host", "127.0.0.1")
    if not isinstance(host, str) or not host:
        raise ValueError("Relay host must be a string")
    return value
