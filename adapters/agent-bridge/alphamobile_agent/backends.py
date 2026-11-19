"""Answer backends: an OpenAI-compatible endpoint, or any command-line agent."""
from __future__ import annotations

import asyncio
import contextlib
import os
from typing import Any

import httpx

from .config import BackendConfig


class BackendError(RuntimeError):
    """The backend did not produce an answer; the message is safe to log (no prompt or key)."""


async def ask_openai(cfg: BackendConfig, messages: list[dict[str, str]]) -> str:
    headers = {"Content-Type": "application/json"}
    if cfg.api_key_env:
        key = os.environ.get(cfg.api_key_env, "")
        if not key:
            raise BackendError(f"environment variable {cfg.api_key_env} is not set")
        headers["Authorization"] = "Bearer " + key
    body: dict[str, Any] = {"model": cfg.model, "messages": messages,
                            "max_tokens": cfg.max_tokens, "temperature": cfg.temperature}
    try:
        async with httpx.AsyncClient(timeout=cfg.timeout) as client:
            response = await client.post(cfg.base_url + "/chat/completions", json=body, headers=headers)
    except httpx.HTTPError as error:
        raise BackendError(f"{cfg.base_url} unreachable: {error.__class__.__name__}") from error
    if response.status_code != 200:
        raise BackendError(f"{cfg.base_url} returned HTTP {response.status_code}")
    try:
        content = response.json()["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as error:
        raise BackendError("unexpected chat completion response") from error
    return str(content or "")


async def ask_command(cfg: BackendConfig, prompt: str) -> str:
    try:
        process = await asyncio.create_subprocess_exec(
            *cfg.argv, cwd=cfg.cwd, stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    except OSError as error:
        raise BackendError(f"cannot start {cfg.argv[0]}: {error.strerror}") from error
    try:
        out, err = await asyncio.wait_for(process.communicate(prompt.encode()), cfg.timeout)
    except TimeoutError as error:
        with contextlib.suppress(ProcessLookupError):
            process.kill()
        await process.wait()
        raise BackendError(f"{cfg.argv[0]} timed out after {cfg.timeout:g} s") from error
    if process.returncode != 0:
        tail = err.decode(errors="replace").strip().splitlines()[-1:] or ["no error output"]
        raise BackendError(f"{cfg.argv[0]} exited {process.returncode}: {tail[0][:300]}")
    return out.decode(errors="replace")
