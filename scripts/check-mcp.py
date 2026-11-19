#!/usr/bin/env python3
"""Exercise a deployed alphaMobile MCP endpoint using its real protocol client."""
import argparse
import asyncio
import json
import ssl
import uuid
from pathlib import Path
from typing import Any

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def check(args: argparse.Namespace) -> None:
    connection = json.loads(Path(args.connection).read_text())
    tls = ssl.create_default_context(cafile=connection.get("ca_file"))
    async with (
        httpx.AsyncClient(verify=tls, headers={"Authorization": "Bearer " + connection["token"]}, timeout=180) as http,
        streamable_http_client(connection["url"].rstrip("/") + "/mcp", http_client=http) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        if args.operation == "list":
            tools = (await session.list_tools()).tools
            print(json.dumps({"tools": [t.name for t in tools]}))
            return
        parameters: dict[str, Any]
        if args.operation == "call":
            name = "call_user"
            parameters = {"device_id": args.device, "reason": "alphaMobile installation check",
                          "context": "This is a real installation check requested by the operator. Explain that the connection works. No user action or deployment should be requested.",
                          "questions": [], "ring_seconds": 180, "request_key": args.run_key}
        elif args.operation == "result":
            name = "get_call_result"
            parameters = {"call_id": args.call_id}
        else:
            task = await session.call_tool("report_scheduled_task", {"device_id": args.device,
                "task_key": "installation-check", "title": "alphaMobile installation check",
                "schedule": "Manual check during installation", "enabled": False,
                "description": "Actual MCP protocol and result publication check; no recurring job."})
            if task.isError or task.structuredContent is None:
                raise RuntimeError(str(task.content))
            name = "report_task_result"
            parameters = {"task_id": task.structuredContent["id"], "run_key": args.run_key,
                          "status": "completed", "summary": "MCP connection and task publication succeeded",
                          "output": "Initialised an authenticated MCP session and published this check through the live report_scheduled_task tool."}
        result = await session.call_tool(name, parameters)
        if result.isError:
            raise RuntimeError(str(result.content))
        print(json.dumps(result.structuredContent))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--connection", required=True)
    parser.add_argument("operation", choices=["list", "call", "result", "task"])
    parser.add_argument("--device")
    parser.add_argument("--call-id")
    parser.add_argument("--run-key", default=None)
    args = parser.parse_args()
    if args.operation in {"call", "task"} and not args.device:
        parser.error("--device is required for this operation")
    if args.operation == "result" and not args.call_id:
        parser.error("--call-id is required for this operation")
    if args.run_key is None:
        args.run_key = "installation-" + uuid.uuid4().hex
    asyncio.run(check(args))


if __name__ == "__main__":
    main()
