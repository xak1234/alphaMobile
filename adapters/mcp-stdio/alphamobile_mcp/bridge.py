"""Stdio MCP bridge for Claude/desktop clients; credentials stay in a local file."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import os
import ssl
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx
from mcp import ClientSession, types
from mcp.client.streamable_http import streamable_http_client
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server


async def serve() -> None:
    config = json.loads(Path(os.environ["ALPHAMOBILE_CONNECTION_FILE"]).read_text())
    url = urlsplit(config["url"])
    if not url.hostname or (url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"})) or url.username or url.password or url.query or url.fragment:
        raise ValueError("Connection must use HTTPS, or HTTP loopback, without URL credentials")
    if not isinstance(config.get("token"), str) or not config["token"]:
        raise ValueError("Connection file needs its provisioned agent token")
    context = ssl.create_default_context(cafile=config.get("ca_file"))
    async with httpx.AsyncClient(verify=context, headers={"Authorization": "Bearer " + config["token"]}, timeout=180) as http:
        async def presence() -> None:
            first = True
            while True:
                try:
                    response = await http.post(config["url"].rstrip("/") + "/api/agent/presence",
                                               json={"online": True, "connect": first}, timeout=10)
                    response.raise_for_status()
                    first = False
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(10)

        heartbeat = asyncio.create_task(presence())
        try:
            await run_bridge(http, config)
        finally:
            heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await heartbeat
            with contextlib.suppress(httpx.HTTPError):
                await http.post(config["url"].rstrip("/") + "/api/agent/presence", json={"online": False}, timeout=3)


async def run_bridge(http: httpx.AsyncClient, config: dict[str, Any]) -> None:
    async with streamable_http_client(config["url"].rstrip("/") + "/mcp", http_client=http) as (read, write, _):
        async with ClientSession(read, write) as upstream:
            initial = await upstream.initialize()
            server = Server("alphaMobile", version="0.2.0", instructions=initial.instructions)

            @server.list_tools()  # type: ignore[no-untyped-call, untyped-decorator]
            async def list_tools() -> list[types.Tool]:
                return (await upstream.list_tools()).tools

            @server.call_tool()  # type: ignore[untyped-decorator]
            async def call_tool(name: str, arguments: dict[str, Any]) -> types.CallToolResult:
                return await upstream.call_tool(name, arguments)

            async with stdio_server() as (stdin, stdout):
                await server.run(stdin, stdout, server.create_initialization_options())

def main() -> None:
    parser = argparse.ArgumentParser(description="Connect a stdio MCP client to alphaMobile")
    parser.add_argument("--connection", type=Path, default=os.environ.get("ALPHAMOBILE_CONNECTION_FILE"))
    args = parser.parse_args()
    if args.connection is None:
        parser.error("Set ALPHAMOBILE_CONNECTION_FILE or pass --connection")
    os.environ["ALPHAMOBILE_CONNECTION_FILE"] = str(args.connection.resolve())
    asyncio.run(serve())


if __name__ == "__main__":
    main()
