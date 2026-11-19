"""Configuration for the always-on agent bridge (TOML file + connection file)."""
from __future__ import annotations

import json
import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


class ConfigError(ValueError):
    """Raised for a missing or invalid setting, with a message naming the setting."""


@dataclass(frozen=True)
class Connection:
    url: str
    token: str
    ca_file: str | None = None


@dataclass(frozen=True)
class BackendConfig:
    type: str  # "openai" or "command"
    timeout: float = 180.0
    # openai: any OpenAI-compatible /v1/chat/completions endpoint
    base_url: str = ""
    model: str = ""
    api_key_env: str = ""
    max_tokens: int = 800
    temperature: float = 0.4
    # command: argv run once per question; the prompt is written to stdin, stdout is the answer
    argv: tuple[str, ...] = ()
    cwd: str | None = None


@dataclass(frozen=True)
class Behaviour:
    progress_after: float = 15.0
    progress_text: str = "Still working on that."
    max_parallel: int = 2
    history: int = 14
    answer_callbacks: bool = True
    callback_max_age: float = 1800.0
    complete_task_sync: bool = True


@dataclass(frozen=True)
class AgentConfig:
    connection: Connection
    text: BackendConfig
    voice: BackendConfig
    name: str = ""
    instructions: str = ""
    behaviour: Behaviour = field(default_factory=Behaviour)


def load_connection(path: Path) -> Connection:
    try:
        data = json.loads(path.read_text())
    except OSError as error:
        raise ConfigError(f"cannot read connection file {path}: {error.strerror}") from error
    except ValueError as error:
        raise ConfigError(f"connection file {path} is not valid JSON") from error
    url = data.get("url")
    token = data.get("token")
    if not isinstance(url, str) or not url:
        raise ConfigError("connection file needs a url")
    parts = urlsplit(url)
    loopback = parts.hostname in {"localhost", "127.0.0.1", "::1"}
    if (not parts.hostname or (parts.scheme != "https" and not (parts.scheme == "http" and loopback))
            or parts.username or parts.password or parts.query or parts.fragment):
        raise ConfigError("connection url must use HTTPS, or HTTP loopback, without credentials")
    if not isinstance(token, str) or not token:
        raise ConfigError("connection file needs its provisioned agent token")
    ca_file = data.get("ca_file")
    if ca_file is not None and not isinstance(ca_file, str):
        raise ConfigError("connection ca_file must be a path")
    return Connection(url.rstrip("/"), token, ca_file)


def _backend(raw: Any, section: str) -> BackendConfig:
    if not isinstance(raw, dict):
        raise ConfigError(f"[{section}] must be a table")
    kind = raw.get("type")
    timeout = float(raw.get("timeout", 180))
    if timeout <= 0:
        raise ConfigError(f"[{section}] timeout must be positive")
    if kind == "openai":
        base_url, model = raw.get("base_url"), raw.get("model")
        if not isinstance(base_url, str) or not base_url.startswith(("http://", "https://")):
            raise ConfigError(f"[{section}] base_url must be an http(s) URL ending in /v1")
        if not isinstance(model, str) or not model:
            raise ConfigError(f"[{section}] model is required")
        key_env = raw.get("api_key_env", "")
        if not isinstance(key_env, str):
            raise ConfigError(f"[{section}] api_key_env must be an environment variable name")
        return BackendConfig("openai", timeout, base_url.rstrip("/"), model, key_env,
                             int(raw.get("max_tokens", 800)), float(raw.get("temperature", 0.4)))
    if kind == "command":
        argv = raw.get("argv")
        if not isinstance(argv, list) or not argv or not all(isinstance(a, str) and a for a in argv):
            raise ConfigError(f"[{section}] argv must be a non-empty list of strings")
        cwd = raw.get("cwd")
        if cwd is not None and not isinstance(cwd, str):
            raise ConfigError(f"[{section}] cwd must be a path")
        return BackendConfig("command", timeout, argv=tuple(argv),
                             cwd=os.path.expanduser(cwd) if cwd else None)
    raise ConfigError(f'[{section}] type must be "openai" or "command"')


def load_config(path: Path) -> AgentConfig:
    try:
        raw = tomllib.loads(path.read_text())
    except OSError as error:
        raise ConfigError(f"cannot read config {path}: {error.strerror}") from error
    except tomllib.TOMLDecodeError as error:
        raise ConfigError(f"config {path} is not valid TOML: {error}") from error
    conn = raw.get("connection") or os.environ.get("ALPHAMOBILE_CONNECTION_FILE")
    if not isinstance(conn, str) or not conn:
        raise ConfigError("set connection = \"/path/to/agent.connection.json\" "
                          "(or ALPHAMOBILE_CONNECTION_FILE)")
    conn_path = Path(os.path.expanduser(conn))
    if not conn_path.is_absolute():
        conn_path = (path.parent / conn_path).resolve()
    if "text" not in raw:
        raise ConfigError("a [text] backend is required")
    text = _backend(raw["text"], "text")
    voice = _backend(raw["voice"], "voice") if "voice" in raw else text
    b = raw.get("behaviour", {})
    if not isinstance(b, dict):
        raise ConfigError("[behaviour] must be a table")
    behaviour = Behaviour(
        progress_after=float(b.get("progress_after", 15)),
        progress_text=str(b.get("progress_text", "Still working on that.")),
        max_parallel=max(1, int(b.get("max_parallel", 2))),
        history=max(0, int(b.get("history", 14))),
        answer_callbacks=bool(b.get("answer_callbacks", True)),
        callback_max_age=float(b.get("callback_max_age", 1800)),
        complete_task_sync=bool(b.get("complete_task_sync", True)),
    )
    name, instructions = raw.get("name", ""), raw.get("instructions", "")
    if not isinstance(name, str) or not isinstance(instructions, str):
        raise ConfigError("name and instructions must be strings")
    return AgentConfig(load_connection(conn_path), text, voice, name, instructions, behaviour)
