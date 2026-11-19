"""Run the always-on bridge against a real relay (uvicorn + SQLite) and a stub model endpoint."""
from __future__ import annotations

import asyncio
import json
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import httpx
import pytest
import uvicorn
from alphamobile.app import create_app
from alphamobile.store import Store
from alphamobile_agent import prompt
from alphamobile_agent.bridge import serve
from alphamobile_agent.config import ConfigError, load_config


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


class Relay:
    def __init__(self, state: Path) -> None:
        self.store = Store(state / "alphaMobile.sqlite3")
        self.agent = self.store.credential("agent", "Home agent")
        self.device = self.store.pair(self.store.pair_code(), "Test phone")
        self.port = free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        self.server = uvicorn.Server(uvicorn.Config(create_app(state), host="127.0.0.1", port=self.port,
                                                    log_level="warning", access_log=False))
        self.thread = threading.Thread(target=self.server.run, daemon=True)

    def __enter__(self) -> Relay:
        self.thread.start()
        deadline = time.time() + 10
        while not self.server.started:
            assert time.time() < deadline, "relay did not start"
            time.sleep(0.05)
        return self

    def __exit__(self, *_: object) -> None:
        self.server.should_exit = True
        self.thread.join(5)

    def phone(self, method: str, path: str, **kwargs: Any) -> Any:
        headers = {"Authorization": "Bearer " + self.device["token"]}
        response = httpx.request(method, self.url + path, headers=headers, timeout=10, **kwargs)
        response.raise_for_status()
        return response.json()


class StubModel:
    """Minimal OpenAI-compatible /v1/chat/completions that records requests."""

    def __init__(self, answer: str) -> None:
        self.requests: list[dict[str, Any]] = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                stub.requests.append({"path": self.path, "auth": self.headers.get("Authorization"), **body})
                data = json.dumps({"choices": [{"message": {"role": "assistant", "content": answer}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_: Any) -> None:
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()


def write_config(tmp_path: Path, relay: Relay, backend: str, progress_after: float = 0) -> Path:
    (tmp_path / "agent.connection.json").write_text(json.dumps({"url": relay.url, "token": relay.agent["token"]}))
    config = tmp_path / "agent.toml"
    config.write_text(f'name = "Home agent"\nconnection = "agent.connection.json"\n'
                      f'instructions = "The owner lives in Leeds."\n{backend}\n'
                      f"[behaviour]\nprogress_after = {progress_after}\n")
    return config


def run_until(config_path: Path, done: Any, timeout: float = 20) -> None:
    async def main() -> None:
        stop = asyncio.Event()
        task = asyncio.create_task(serve(load_config(config_path), stop))
        deadline = time.monotonic() + timeout
        while not await asyncio.to_thread(done):
            assert time.monotonic() < deadline, "bridge did not answer in time"
            assert not task.done(), task.exception()
            await asyncio.sleep(0.2)
        stop.set()
        await asyncio.wait_for(task, 15)
    asyncio.run(main())


def ask(relay: Relay, text: str, qid: str = "question-1") -> str:
    chat = relay.phone("POST", "/api/chats", json={"agent_id": relay.agent["id"]})
    relay.phone("POST", f"/api/calls/{chat['id']}/turn", json={"id": qid, "role": "user", "text": text})
    return str(chat["id"])


def answered(relay: Relay, call_id: str) -> bool:
    call = relay.phone("GET", f"/api/calls/{call_id}")
    return not call["awaiting_agent"] and any(m["role"] == "agent" for m in call["messages"])


def test_openai_backend_answers_text_chat_and_task_sync(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_KEY", "secret-for-test")
    model = StubModel("**Sunny** in Leeds.\n\n- dry all day")
    try:
        with Relay(tmp_path) as relay:
            relay.phone("POST", "/api/connect", json={})  # queues a task-sync request
            call_id = ask(relay, "What is the weather like?")
            backend = f'[text]\ntype = "openai"\nbase_url = "{model.url}"\nmodel = "stub"\napi_key_env = "STUB_KEY"'
            run_until(write_config(tmp_path, relay, backend), lambda: answered(relay, call_id))
            call = relay.phone("GET", f"/api/calls/{call_id}")
            reply = [m for m in call["messages"] if m["role"] == "agent"][-1]
            assert reply["text"] == "Sunny in Leeds.\n\ndry all day"
            assert reply["reply_to"] == "question-1" and reply["is_final"]
            sent = model.requests[0]
            assert sent["path"] == "/v1/chat/completions" and sent["auth"] == "Bearer secret-for-test"
            assert sent["messages"][0]["role"] == "system" and "Leeds" in sent["messages"][0]["content"]
            assert sent["messages"][-1] == {"role": "user", "content": "What is the weather like?"}
            sync = relay.store.task_sync_requests(agent_id=relay.agent["id"])
            assert sync and sync[0]["state"] != "pending"
            assert "no scheduler" in sync[0]["note"]
            assert not relay.phone("GET", "/api/connection")["ai_connected"]  # offline after stop
    finally:
        model.close()


def test_command_backend_receives_prompt_on_stdin(tmp_path: Path) -> None:
    script = tmp_path / "agent_cli.py"
    script.write_text("import sys\nprompt = sys.stdin.read()\n"
                      "print('Heard: ' + prompt.split('answer this): ')[1].strip())\n")
    with Relay(tmp_path) as relay:
        call_id = ask(relay, "ping")
        backend = f'[text]\ntype = "command"\nargv = {json.dumps([sys.executable, str(script)])}\ntimeout = 20'
        run_until(write_config(tmp_path, relay, backend), lambda: answered(relay, call_id))
        reply = [m for m in relay.phone("GET", f"/api/calls/{call_id}")["messages"] if m["role"] == "agent"]
        assert reply[-1]["text"] == "Heard: ping"


def test_voice_call_uses_voice_backend_and_sends_progress(tmp_path: Path) -> None:
    slow = tmp_path / "slow.py"
    slow.write_text("import sys, time\nsys.stdin.read()\ntime.sleep(1.5)\nprint('Spoken answer.')\n")
    with Relay(tmp_path) as relay:
        call = relay.store.create_call(relay.agent, relay.device["device_id"], "Build finished",
                                       "Tests passed", ["Publish?"], 90, "voice-test")
        relay.phone("POST", f"/api/calls/{call['id']}/state", json={"state": "active"})
        relay.phone("POST", f"/api/calls/{call['id']}/turn",
                    json={"id": "voice-question", "role": "user", "text": "Yes, how long will it take?"})
        backend = ('[text]\ntype = "command"\nargv = ["false"]\n'
                   f'[voice]\ntype = "command"\nargv = {json.dumps([sys.executable, str(slow)])}')
        run_until(write_config(tmp_path, relay, backend, progress_after=0.3),
                  lambda: answered(relay, call["id"]) and len(agent_replies(relay, call["id"])) >= 2)
        replies = agent_replies(relay, call["id"])
        assert [r["text"] for r in replies] == ["Still working on that.", "Spoken answer."]
        assert [r["is_final"] for r in replies] == [0, 1]


def agent_replies(relay: Relay, call_id: str) -> list[dict[str, Any]]:
    return [m for m in relay.phone("GET", f"/api/calls/{call_id}")["messages"] if m["role"] == "agent"]


def test_failed_backend_still_resolves_the_question(tmp_path: Path) -> None:
    with Relay(tmp_path) as relay:
        call_id = ask(relay, "anything")
        backend = f'[text]\ntype = "command"\nargv = {json.dumps([sys.executable, "-c", "import sys; sys.exit(3)"])}'
        run_until(write_config(tmp_path, relay, backend), lambda: answered(relay, call_id))
        reply = [m for m in relay.phone("GET", f"/api/calls/{call_id}")["messages"] if m["role"] == "agent"]
        assert reply[-1]["text"].startswith("Sorry, I could not get an answer")


def test_prompt_treats_only_owner_lines_as_instructions() -> None:
    call = {"channel": "voice", "reason": "Build finished", "context": "Tests passed",
            "messages": [{"id": "s", "role": "status", "text": "Checking with Home agent"},
                         {"id": "a", "role": "agent", "text": "**Done**"},
                         {"id": "q", "role": "user", "text": "Ship it?"}]}
    text = prompt.text_prompt(call, {"id": "q", "text": "Ship it?"}, "Home agent", "", 10)
    assert "Checking with" not in text and "ASSISTANT: **Done**" in text
    assert text.rstrip().endswith("OWNER (latest message, answer this): Ship it?")
    assert "spoken aloud" in text and "Build finished" in text
    assert prompt.clean("<think>hidden</think>## Title\n`x` [site](https://e.x)") == "Title\nx site (https://e.x)"


def test_config_rejects_unsafe_connection_and_bad_backend(tmp_path: Path) -> None:
    conn = tmp_path / "c.json"
    conn.write_text(json.dumps({"url": "http://relay.example", "token": "t"}))
    cfg = tmp_path / "a.toml"
    cfg.write_text(f'connection = "{conn}"\n[text]\ntype = "openai"\nbase_url = "http://x/v1"\nmodel = "m"\n')
    with pytest.raises(ConfigError, match="HTTPS"):
        load_config(cfg)
    conn.write_text(json.dumps({"url": "https://relay.example", "token": "t"}))
    cfg.write_text(f'connection = "{conn}"\n[text]\ntype = "shell"\n')
    with pytest.raises(ConfigError, match="type must be"):
        load_config(cfg)
    cfg.write_text(f'connection = "{conn}"\n[text]\ntype = "command"\nargv = ["claude", "-p"]\n')
    loaded = load_config(cfg)
    assert loaded.voice == loaded.text and loaded.connection.url == "https://relay.example"
