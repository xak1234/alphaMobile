"""Always-on alphaMobile agent: answers phone messages and calls without a desktop session."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import signal
import ssl
import time
from pathlib import Path
from typing import Any

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from . import __version__, prompt
from .backends import BackendError, ask_command, ask_openai
from .config import AgentConfig, BackendConfig, ConfigError, load_config

log = logging.getLogger("alphamobile-agent")
NO_SCHEDULER = ("This always-on bridge has no scheduler of its own; scheduled jobs report "
                "their own runs to alphaMobile.")


class ToolError(RuntimeError):
    pass


def tool_data(result: Any) -> Any:
    """Decode a relay tool result (structured content or JSON text)."""
    if getattr(result, "isError", False):
        text = " ".join(getattr(c, "text", "") for c in result.content).strip()
        raise ToolError(text or "tool call failed")
    structured = getattr(result, "structuredContent", None)
    if isinstance(structured, dict):
        return structured["result"] if set(structured) == {"result"} else structured
    for content in result.content:
        body = getattr(content, "text", None)
        if isinstance(body, str):
            try:
                return json.loads(body)
            except ValueError:
                return body
    return None


class Agent:
    def __init__(self, config: AgentConfig, session: ClientSession) -> None:
        self.config = config
        self.session = session
        self.in_flight: set[str] = set()
        self.seen_callbacks: set[str] = set()
        self.tasks: set[asyncio.Task[None]] = set()
        self.limit = asyncio.Semaphore(config.behaviour.max_parallel)

    async def call(self, name: str, **arguments: Any) -> Any:
        return tool_data(await self.session.call_tool(name, arguments))

    async def run(self, stop: asyncio.Event) -> None:
        last_housekeeping = 0.0
        while not stop.is_set():
            calls = await self.call("get_pending_messages", wait_seconds=5)
            for call in calls or []:
                for question in call.get("pending_questions", []):
                    if question["id"] not in self.in_flight:
                        self.in_flight.add(question["id"])
                        task = asyncio.create_task(self.answer(call, question))
                        self.tasks.add(task)
                        task.add_done_callback(self.tasks.discard)
            if time.monotonic() - last_housekeeping >= 3:
                last_housekeeping = time.monotonic()
                await self.housekeeping()

    async def housekeeping(self) -> None:
        behaviour = self.config.behaviour
        if behaviour.answer_callbacks:
            for callback in await self.call("pending_callbacks") or []:
                if callback["id"] in self.seen_callbacks:
                    continue
                self.seen_callbacks.add(callback["id"])
                age = time.time() - float(callback.get("created") or 0)
                if age > behaviour.callback_max_age:
                    log.info("skipping callback %s: %.0f min old", callback["id"], age / 60)
                    continue
                log.info("answering callback %s", callback["id"])
                await self.call("answer_callback", callback_id=callback["id"],
                                context="You asked me to call you back.",
                                questions=["You asked me to call back. What can I help with?"])
        if behaviour.complete_task_sync:
            for request in await self.call("get_task_sync_requests") or []:
                await self.call("complete_task_sync", request_id=request["id"], task_keys=[],
                                available=False, note=NO_SCHEDULER)

    async def answer(self, call: dict[str, Any], question: dict[str, Any]) -> None:
        qid = question["id"]
        behaviour = self.config.behaviour
        backend = self.config.voice if prompt.is_voice(call) else self.config.text

        async def progress() -> None:
            await asyncio.sleep(behaviour.progress_after)
            await self.call("send_message", call_id=call["id"], text=behaviour.progress_text,
                            reply_to=qid, final=False, message_id=f"{qid}-progress")

        try:
            async with self.limit:
                started = time.monotonic()
                notice = asyncio.create_task(progress()) if behaviour.progress_after > 0 else None
                try:
                    text = prompt.clean(await self.ask(backend, call, question))
                except BackendError as error:
                    log.warning("backend failed for %s: %s", qid, error)
                    text = "Sorry, I could not get an answer just now. Please try again shortly."
                finally:
                    if notice is not None:
                        notice.cancel()
                        with contextlib.suppress(asyncio.CancelledError, Exception):
                            await notice
                await self.call("send_message", call_id=call["id"],
                                text=(text or "I do not have an answer for that.")[:8000],
                                reply_to=qid, final=True, message_id=f"{qid}-answer")
                log.info("answered %s (%s) in %.1f s", qid, call.get("channel", "voice"),
                         time.monotonic() - started)
        except Exception:
            log.exception("could not deliver answer for %s", qid)
            self.in_flight.discard(qid)  # retry on a later poll

    async def ask(self, backend: BackendConfig, call: dict[str, Any], question: dict[str, Any]) -> str:
        cfg = self.config
        if backend.type == "openai":
            return await ask_openai(backend, prompt.chat_messages(
                call, question, cfg.name, cfg.instructions, cfg.behaviour.history))
        return await ask_command(backend, prompt.text_prompt(
            call, question, cfg.name, cfg.instructions, cfg.behaviour.history))


async def presence(http: httpx.AsyncClient, url: str) -> None:
    first = True
    while True:
        with contextlib.suppress(httpx.HTTPError):
            response = await http.post(url + "/api/agent/presence",
                                       json={"online": True, "connect": first}, timeout=10)
            response.raise_for_status()
            first = False
        await asyncio.sleep(10)


async def serve(config: AgentConfig, stop: asyncio.Event) -> None:
    conn = config.connection
    context = ssl.create_default_context(cafile=conn.ca_file)
    backoff = 2.0
    async with httpx.AsyncClient(verify=context, timeout=60,
                                 headers={"Authorization": "Bearer " + conn.token}) as http:
        heartbeat = asyncio.create_task(presence(http, conn.url))
        try:
            while not stop.is_set():
                try:
                    async with streamable_http_client(conn.url + "/mcp", http_client=http) as (read, write, _):
                        async with ClientSession(read, write) as session:
                            await session.initialize()
                            log.info("connected to %s", conn.url)
                            backoff = 2.0
                            agent = Agent(config, session)
                            runner = asyncio.create_task(agent.run(stop))
                            waiter = asyncio.create_task(stop.wait())
                            done, _pending = await asyncio.wait({runner, waiter},
                                                                return_when=asyncio.FIRST_COMPLETED)
                            waiter.cancel()
                            if runner in done:
                                runner.result()
                            runner.cancel()
                            with contextlib.suppress(asyncio.CancelledError):
                                await runner
                            for task in list(agent.tasks):
                                task.cancel()
                except (httpx.HTTPError, ToolError, OSError, ExceptionGroup) as error:
                    log.warning("relay connection lost (%s); retrying in %.0f s", error, backoff)
                    with contextlib.suppress(TimeoutError):
                        await asyncio.wait_for(stop.wait(), backoff)
                    backoff = min(backoff * 2, 60)
        finally:
            heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await heartbeat
            with contextlib.suppress(httpx.HTTPError):
                await http.post(conn.url + "/api/agent/presence", json={"online": False}, timeout=3)


async def check(config: AgentConfig) -> None:
    """Connect once and print the paired devices, without answering anything."""
    conn = config.connection
    context = ssl.create_default_context(cafile=conn.ca_file)
    async with httpx.AsyncClient(verify=context, timeout=30,
                                 headers={"Authorization": "Bearer " + conn.token}) as http:
        async with streamable_http_client(conn.url + "/mcp", http_client=http) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                devices = tool_data(await session.call_tool("list_devices", {}))
    print(json.dumps({"relay": conn.url, "devices": devices,
                      "text_backend": config.text.type, "voice_backend": config.voice.type}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="alphamobile-agent",
        description="Always-on alphaMobile agent: answer phone messages and calls from a model or CLI agent")
    parser.add_argument("--config", type=Path, required=True, help="Path to the agent TOML file")
    parser.add_argument("--check", action="store_true", help="Connect once, list devices and exit")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    for noisy in ("httpx", "mcp"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    try:
        config = load_config(args.config)
    except ConfigError as error:
        parser.error(str(error))
    if args.check:
        asyncio.run(check(config))
        return

    async def run() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            with contextlib.suppress(NotImplementedError):
                loop.add_signal_handler(sig, stop.set)
        await serve(config, stop)

    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(run())


if __name__ == "__main__":
    main()
