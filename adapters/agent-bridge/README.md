# alphaMobile always-on agent bridge

`alphamobile-agent` keeps an agent permanently connected to your alphaMobile relay, so the phone gets an answer without a desktop chat window open. It is the counterpart to `alphamobile-mcp`:

| Package | Use it when |
| --- | --- |
| `alphamobile-mcp` (`adapters/mcp-stdio`) | You want a desktop MCP client (Claude Desktop, Claude Code, Cursor…) to use the phone tools while that session is open. |
| `alphamobile-agent` (this package) | You want the phone answered 24/7 by a model or a command-line agent running on a server or always-on PC. |

The bridge runs one loop against the relay's MCP endpoint:

- sends a presence heartbeat every ten seconds, so the phone shows the agent as online;
- long-polls `get_pending_messages` and answers each text chat or call question with `send_message(final=true)`;
- sends one progress message ("Still working on that.") if an answer takes longer than `progress_after` seconds;
- answers callback requests from the phone with `answer_callback`, skipping requests older than `callback_max_age`;
- completes task-inventory requests with `available=false`, since the bridge has no scheduler of its own (jobs report their own runs; see [MCP.md](../../docs/MCP.md)).

Answers come from one of two backends, chosen per channel:

- `openai`: any OpenAI-compatible `/v1/chat/completions` endpoint (llama.cpp `llama-server`, Ollama, LM Studio, vLLM, llama-swap, LiteLLM, DeepSeek, OpenRouter…).
- `command`: any CLI that reads a prompt on stdin and prints the answer, such as `claude -p`. The command runs once per question with your user's permissions and its own tool settings.

An optional `[voice]` backend lets calls use a faster model than text chats. Replies are cleaned of markdown and reasoning blocks before they reach the phone, because calls are spoken by text-to-speech. Only the phone owner's messages are presented to the model as instructions; call context and earlier replies are labelled as data.

## Install

Python 3.11+ on the machine that will run the agent. This can be the relay host.

```bash
python3 -m venv ~/.local/share/alphamobile-agent
~/.local/share/alphamobile-agent/bin/pip install \
  "git+https://github.com/xak1234/alphaMobile.git#subdirectory=adapters/agent-bridge"
```

Provision a dedicated agent credential on the relay host. The name is what the phone shows ("Checking with Claude"):

```bash
~/.local/share/alphamobile/.venv/bin/alphamobile --config ~/.local/share/alphamobile/config.json \
  agent Claude --url https://YOUR-BOX.YOUR-TAILNET.ts.net \
  --output ~/.config/alphamobile/claude-agent.connection.json
```

Copy that private file to the agent machine if it is a different computer (keep it `chmod 600`). Use a separate credential from any desktop `alphamobile-mcp` client, so the two do not pick up each other's questions.

## Configure

Start from an example in [`examples/`](examples/) and save it as `~/.config/alphamobile/agent.toml`:

- [`claude-code.toml`](examples/claude-code.toml): Claude via the Claude Code CLI (`claude -p`), with a no-tools fast path for calls.
- [`local-openai.toml`](examples/local-openai.toml): a local model behind Ollama or llama.cpp.
- [`hosted-api.toml`](examples/hosted-api.toml): a hosted OpenAI-compatible API with the key read from an environment variable.

| Setting | Default | Meaning |
| --- | --- | --- |
| `name` | agent name from the relay | Name the model is told it has. |
| `connection` | `ALPHAMOBILE_CONNECTION_FILE` | Connection file from `alphamobile agent --output`. Relative paths are relative to the TOML file. |
| `instructions` | empty | Extra system instructions (owner context, tone, what not to do). |
| `[text]` / `[voice]` | `[voice]` falls back to `[text]` | `type = "openai"` with `base_url`, `model`, optional `api_key_env`, `max_tokens`, `temperature`; or `type = "command"` with `argv`, optional `cwd`. Both take `timeout` (seconds). |
| `[behaviour] progress_after` | `15` | Seconds before a progress message; `0` disables it. |
| `[behaviour] max_parallel` | `2` | Questions answered at the same time. |
| `[behaviour] history` | `14` | Earlier messages in the conversation passed to the model. |
| `[behaviour] answer_callbacks` | `true` | Ring back when the phone asks for a callback. |
| `[behaviour] callback_max_age` | `1800` | Ignore callback requests older than this many seconds. |
| `[behaviour] complete_task_sync` | `true` | Reply to task-inventory requests with "no scheduler" instead of leaving them pending. |

API keys never go in the TOML file: set `api_key_env` to the name of an environment variable and provide it through the service's `EnvironmentFile`.

Check the connection without answering anything:

```bash
~/.local/share/alphamobile-agent/bin/alphamobile-agent --config ~/.config/alphamobile/agent.toml --check
```

It prints the relay URL, your paired devices and the configured backends.

## Run as a service

```bash
mkdir -p ~/.config/systemd/user
cp examples/alphamobile-agent.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now alphamobile-agent
loginctl enable-linger "$USER"     # keep it running after logout and across reboots
journalctl --user -u alphamobile-agent -f
```

On the phone, open Messages, start a new message to the agent and send a question. The log shows `answered <id> (chat) in N s`. For Windows or macOS, run the same command under Task Scheduler or launchd; the bridge reconnects automatically if the relay restarts.

## Security notes

- The bridge only makes outbound HTTPS (or loopback HTTP) connections; nothing listens on a port.
- A `command` backend runs with your user's permissions. Limit the CLI's tools to what you would let anyone holding your phone trigger, and treat email or web content the agent reads as untrusted data.
- The connection file is a bearer credential for the relay. Revoke it with `alphamobile revoke <agent-id>` if the machine is lost.
