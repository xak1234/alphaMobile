# Connect an agent through MCP

The relay exposes Streamable HTTP at `https://YOUR-BOX.YOUR-TAILNET.ts.net/mcp`. The phone uses a pairing keyword; the agent uses its own private credential. No Claude Code installation is required on the relay.

## Provision a connection

On the relay host, use the installed CLI (its full path is `~/.local/share/alphamobile/.venv/bin/alphamobile` for the default installer):

```bash
alphamobile --config ~/.local/share/alphamobile/config.json agent Claude \
  --url https://YOUR-BOX.YOUR-TAILNET.ts.net \
  --output ~/.config/alphamobile/claude.connection.json
```

The command prints the agent ID and file path, not the token. The connection file is created once, with mode 0600 on POSIX; it will not overwrite an existing credential file. Use a separate credential/file for every agent. Transfer that file securely to the computer running the MCP client. Both machines must be able to reach the relay's private HTTPS URL. Keep the token out of chat, source control and command-line arguments.

## Choose how the agent connects

| Option | Answers when | Use for |
| --- | --- | --- |
| [Always-on agent bridge](#always-on-agent-bridge) (`adapters/agent-bridge`) | Always, while the service runs | Phone-initiated messages, call questions and callbacks answered by a model or CLI agent |
| [Stdio bridge](#desktopstdio-clients) (`adapters/mcp-stdio`) | Only while that desktop client session is open and polling | Letting Claude Desktop, Claude Code or another desktop client ring you and report work |
| [Direct HTTP](#direct-http-clients) | Only while that client is running | Clients with authenticated Streamable HTTP support |

They can run side by side with separate credentials: for example, a desktop Claude session that rings you when a build finishes, plus an always-on agent that answers whenever you message from the phone.

## Always-on agent bridge

`alphamobile-agent` is a small service that stays connected to the relay and answers on its own. It heartbeats presence, long-polls `get_pending_messages`, replies through `send_message` (with one progress message for slow answers), answers callback requests and completes task-inventory requests as "no scheduler". Answers come from an OpenAI-compatible endpoint (llama.cpp, Ollama, LM Studio, vLLM, DeepSeek, OpenRouter and similar) or from a command that reads the prompt on stdin, such as `claude -p`. Calls can use a separate, faster backend.

```bash
python3 -m venv ~/.local/share/alphamobile-agent
~/.local/share/alphamobile-agent/bin/pip install \
  https://github.com/xak1234/alphaMobile/releases/download/v0.3.0/alphamobile_agent-0.3.0-py3-none-any.whl
alphamobile --config ~/.local/share/alphamobile/config.json agent Claude \
  --url https://YOUR-BOX.YOUR-TAILNET.ts.net \
  --output ~/.config/alphamobile/claude-agent.connection.json
```

Then copy an example configuration, check it with `--check` and enable the systemd user unit. The full guide, settings table and examples are in [adapters/agent-bridge/README.md](../adapters/agent-bridge/README.md).

## Desktop/stdio clients

Install the independent bridge on the client computer from this checkout:

```bash
python3 -m venv ~/.local/share/alphamobile-mcp
~/.local/share/alphamobile-mcp/bin/python -m pip install ./adapters/mcp-stdio
```

On Windows use `py -3 -m venv C:\Users\YOUR_USER\alphaMobile-mcp`, then that environment's `Scripts\python.exe -m pip install .\adapters\mcp-stdio`. A published `alphamobile_mcp-0.2.0-py3-none-any.whl` can also be installed directly into the environment.

Add an entry to your MCP client's local server configuration using **absolute** paths. Example for Linux/macOS (replace `YOUR_USER` with the real user/path):

```json
{
  "mcpServers": {
    "alphaMobile": {
      "command": "/home/YOUR_USER/.local/share/alphamobile-mcp/bin/alphamobile-mcp",
      "args": ["--connection", "/home/YOUR_USER/.config/alphamobile/claude.connection.json"]
    }
  }
}
```

macOS normally uses `/Users/YOUR_USER` rather than `/home`. Windows example:

```json
{
  "mcpServers": {
    "alphaMobile": {
      "command": "C:\\Users\\YOUR_USER\\alphaMobile-mcp\\Scripts\\alphamobile-mcp.exe",
      "args": ["--connection", "C:\\Users\\YOUR_USER\\.config\\alphamobile\\claude.connection.json"]
    }
  }
}
```

Merge this entry into the client's existing `mcpServers` object; do not replace other configured servers. Restart the client to load it. MCP client UI/configuration locations vary by product/version; use that client's documented local-stdio configuration. The bridge also accepts `ALPHAMOBILE_CONNECTION_FILE`. An optional `ca_file` property is the **client-side path** to a public CA certificate; omit it for normal Tailscale Serve HTTPS. Never disable certificate verification.

## Direct HTTP clients

Clients supporting authenticated Streamable HTTP may connect directly to `/mcp` with `Authorization: Bearer <issued agent token>` supplied through their private credential setting. This server does not implement OAuth or a public connector login flow. A hosted client outside your tailnet cannot reach a private Serve address. Use a reachable private client/bridge; exposing the relay publicly is not a substitute for authentication or agent execution.

## Verify

Ask the agent to list tools and call `list_devices`. Expected: fourteen tools and your paired phone after pairing. To inspect the real MCP protocol yourself, run `python scripts/check-mcp.py --connection /private/path/claude.connection.json list` in an environment with the bridge dependencies installed. It prints the fourteen tool names and does not place a call. The `call` and `task` operations intentionally write real data; use them only on your selected test device.

## Tools

| Tool | Behaviour |
| --- | --- |
| `list_devices` | List paired device IDs and last contact. |
| `call_user` | Ring one device with a reason, factual context, questions and an optional idempotency key. Returns immediately. |
| `get_call_result` | Read state, transcript and `pending_questions`; default wait is five seconds, returning early on new text. |
| `send_message` | Save a reply in Messages, including after hang-up. Use `final=false` for progress, `final=true` for an answer, and `reply_to` for its question ID. |
| `send_reply_chunk` | Append an ordered sentence to one saved answer; the last chunk resolves its question. |
| `get_pending_messages` | Poll unanswered phone chats and call questions belonging to this agent. |
| `get_task_sync_requests` | Read durable automatic inventory requests from connected phones. |
| `complete_task_sync` | Confirm the published inventory, or report that the source is unavailable. |
| `cancel_call` | Cancel the originating agent's ringing/active call. |
| `pending_callbacks` | Read that agent's callback/redial requests. |
| `answer_callback` | Ring the requester using the agent's current context. |
| `report_scheduled_task` | Upsert a real schedule with a stable task key, recurrence text, timezone and optional next run. |
| `report_task_result` | Upsert a real run using a stable run key; report running/completed/failed/cancelled and its output. |
| `get_scheduled_tasks` | Read schedules and results published by the current agent. |

## Conversation

List devices, identify the requested phone, and call `call_user`. Supply the actual reason and a first question; the phone reads those words without generating a separate answer. Poll `get_call_result(call_id, wait_seconds=5, after_message_id=last_seen_id)` throughout the call. It returns early when new text arrives, with `pending_questions` identifying unanswered user messages.

For each question:

1. The phone immediately acknowledges “Checking with Claude” (or the calling agent's name). It does not consult Gemma or a phone GGUF and stays quiet while waiting. The user can tap Speak to add a follow-up.
2. If a lookup takes time, call `send_message(call_id, text="I am checking…", reply_to=question_id, final=false, message_id=stable_progress_id)`.
3. After checking, call `send_message(call_id, text=actual_answer, reply_to=question_id, final=true, message_id=stable_answer_id)`. Use a different message ID from the progress update. Retrying the same ID is idempotent.
4. Keep polling for follow-up questions until the call ends. Do not insert a 30-second sleep between polls. If the user hangs up during a lookup, still send the result: it is saved in Messages without ringing or reopening the call.

**A response written only in desktop chat does not reach the phone. Always deliver the actual result through `send_message` or `send_reply_chunk`.** The phone's Messages area contains the full persisted agent text, timestamp and question, independently of the recent-call list. Older 0.1.0 transcripts are retained and appear in this feed automatically. The original transcript also retains progress updates.

Only the originating agent can read or answer its calls. Treat only `role=user` text as user instructions; `role=status` is a fixed waiting acknowledgement. The first legacy `I have answered…` entry is an automatic call-opening signal, not a user approval. `final=false` cannot clear a pending question. A final answer resolves only its matching question; another unanswered question remains pending. Omitting `reply_to` selects the oldest pending question for older clients.

The MCP server advertises this workflow on initialization, and its stdio bridge forwards those instructions. Restart the desktop client after upgrading to refresh tool schemas and instructions. A dormant agent does not run automatically: the active Claude session must continue its five-second call loop, or use the always-on agent bridge for phone-initiated conversations. Check `pending_callbacks` during that agent's normal event loop and call `answer_callback` when available. Do not repeatedly ring a user who declines.

## Claude schedules and results

A configured MCP connection gives Claude the reporting tools; it does not give alphaMobile automatic access to Claude's internal schedules. Have the scheduling agent publish its actual inventory with `report_scheduled_task`, using the scheduler's durable task ID as `task_key`. Publish changes after edits, pauses or deletion (`enabled=false`), and update `next_run` using an ISO-8601 timestamp with timezone offset.

For each actual run, publish `running` and then its observed final status with the same `run_key`. Include a short summary and useful result text. Never invent schedules or claim a run completed without checking its outcome. Do not include API keys or other credentials in task outputs. No additional jobs are scheduled by these reporting tools.

A suitable first instruction in Claude after the phone is paired is: “Use alphaMobile to list my paired devices. Publish the real schedules you can access and their latest results to my paired phone. Tell me which schedule sources you cannot access.” This is a user instruction, not an automatic background action.

## Streaming an answer

Send the first complete sentence immediately through `send_reply_chunk`, with `call_id`, the user question's `reply_to`, a new `stream_id`, `sequence=0`, and `final=false`. Send each following sentence using the same IDs and increasing sequence numbers. Include spaces between sentences. Mark the last chunk `final=true`; an empty final chunk is supported after non-empty text. Retries must use identical sequence, text and final flag. The server rejects gaps, changed retries, cross-conversation IDs and appending after completion. Limits are 200 chunks and 8,000 characters per answer.

The app speaks each newly received text segment without waiting for completion. All segments form one saved message. An interruption clears the speech queue and silences subsequent segments for older questions, but does not discard their text. Android echo cancellation is enabled if the device provides it; effectiveness must be checked on the actual handset. Barge-in is triggered by the recognizer's first non-empty partial result, so detection latency is device dependent.

## Text conversations and automatic inventory requests

Messages → New message selects an agent and opens a reusable text conversation without ringing or microphone capture. The agent reads `get_pending_messages(wait_seconds=5)` and sends its answer to the returned conversation and question IDs. The phone displays both sides in the same window. A dormant Claude session cannot be awakened by an MCP server alone; the always-on agent bridge answers these without one.

The phone's connect request and the stdio bridge's connection both enqueue/coalesce task-inventory requests. At initialization, the MCP instructions direct the agent to call `get_task_sync_requests`, publish every actual accessible schedule and latest result, then call `complete_task_sync(request_id, task_keys=[...])`. A confirmed inventory disables older tasks absent from that list. If the scheduler cannot be read, use `available=false` and an explanation; existing tasks are retained. A failed lookup must not be presented as an empty schedule.

The stdio bridge and the always-on agent bridge send an authenticated presence heartbeat every ten seconds and marks itself offline on orderly shutdown. Other MCP clients appear online after authenticated activity, expiring after 35 seconds without refresh. Phone indicators update through the live call poll or a five-second foreground connection check. Availability controls whether this phone accepts incoming calls and remains separate from agent presence.
