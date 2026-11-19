#!/usr/bin/env python3
"""Install the relay in a private venv; optionally register a Linux user service."""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import venv
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def systemd_quote(value: str) -> str:
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'


def service_text(prefix: Path) -> str:
    executable = prefix / ".venv/bin/alphamobile"
    config = prefix / "config.json"
    return ("# Managed by alphaMobile installer\n[Unit]\nDescription=alphaMobile private agent relay\n"
            "After=network-online.target\nWants=network-online.target\n\n[Service]\nType=simple\n"
            f"ExecStart={systemd_quote(str(executable))} --config {systemd_quote(str(config))} serve\n"
            "Restart=on-failure\nRestartSec=3\nUMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\n"
            "\n[Install]\nWantedBy=default.target\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path, default=Path.home() / ".local/share/alphamobile")
    parser.add_argument("--url", required=True, help="Your HTTPS relay URL, normally Tailscale Serve")
    parser.add_argument("--port", type=int, default=8793)
    parser.add_argument("--service", action="store_true", help="Install and start a Linux systemd user service")
    args = parser.parse_args()
    url = urlsplit(args.url)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in {"", "/"}:
        parser.error("--url must be an HTTPS origin, without credentials or a path")
    try:
        _ = url.port
    except ValueError:
        parser.error("--url contains an invalid port")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    prefix = args.prefix.expanduser().resolve()
    if any(c in str(prefix) for c in "\n\r\x00"):
        parser.error("Unsupported newline or null character in installation path")
    if args.service and platform.system() != "Linux":
        parser.error("--service requires Linux systemd; use the foreground command on this OS")
    unit = Path.home() / ".config/systemd/user/alphamobile.service"
    expected = service_text(prefix)
    if args.service and unit.exists() and unit.read_text() != expected:
        parser.error("An existing different alphaMobile service is present; preserve it before choosing this installation")
    prefix.mkdir(mode=0o700, parents=True, exist_ok=True)
    (prefix / "state").mkdir(mode=0o700, exist_ok=True)
    config = prefix / "config.json"
    if config.exists():
        existing = json.loads(config.read_text())
        if not isinstance(existing, dict):
            parser.error("Existing configuration must be a JSON object")
        if existing.get("public_url") != args.url.rstrip("/") or existing.get("port") != args.port:
            parser.error("Existing configuration differs. Reuse its URL/port, or explicitly edit config.json first")
    else:
        value = {"state": str(prefix / "state"), "host": "127.0.0.1", "port": args.port,
                 "allowed_hosts": ["localhost", "127.0.0.1", url.hostname], "public_url": args.url.rstrip("/")}
        descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
    environment = prefix / ".venv"
    venv.EnvBuilder(with_pip=True).create(environment)
    binary = environment / ("Scripts" if os.name == "nt" else "bin")
    python = binary / ("python.exe" if os.name == "nt" else "python")
    subprocess.run([str(python), "-m", "pip", "install", "--upgrade", str(ROOT / "backend")], check=True)
    command = [str(binary / ("alphamobile.exe" if os.name == "nt" else "alphamobile")), "--config", str(config)]
    if args.service:
        unit.parent.mkdir(parents=True, exist_ok=True)
        unit.write_text(expected)
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
        subprocess.run(["systemctl", "--user", "enable", "--now", "alphamobile.service"], check=True)
        subprocess.run(["systemctl", "--user", "restart", "alphamobile.service"], check=True)
    print("Relay installed. Configuration and existing state were preserved.")
    print("Foreground command:", subprocess.list2cmdline(command + ["serve"]))
    print("Create a phone pairing keyword:", subprocess.list2cmdline(command + ["pair"]))
    print("Configure HTTPS on your private network before pairing the phone.")


if __name__ == "__main__":
    if sys.version_info < (3, 11):  # noqa: UP036 - installer may be launched outside the package venv
        raise SystemExit("Python 3.11 or later is required")
    main()
