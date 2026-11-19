# Components and data flow

| Component | Responsibility | Interface |
| --- | --- | --- |
| Android app | Pairing, availability, ringing, Vosk transcription, Android TTS, message/task history and updates | Device-authenticated HTTPS `/api/*` |
| Python relay | Durable SQLite data, credentials, call state, questions/answers, task reports, update files | FastAPI REST and Streamable HTTP `/mcp` |
| Stdio bridge | Connect a desktop MCP client to a remote relay and maintain presence | MCP stdio in; authenticated HTTPS out |
| Agent | Model reasoning, tools, answers, real schedule inventory/results | Fourteen MCP tools |
| Optional local-model library | Developer API for a pinned on-phone llama.cpp CPU process | App-private model plus authenticated loopback HTTP |

The standard app and relay do not load a generative model. Agents own reasoning and scheduler access. The speech recognizer is bundled on the phone; Android supplies installed text-to-speech voices. Calls are application conversations, not cellular/SIP/PSTN calls.

An agent rings a paired available device. The phone reads the supplied question, transcribes the user and persists that question to the relay. It says it is checking with the agent and waits. The agent reads pending questions, uses its own context/tools, then sends an answer or sentence chunks. Every real answer is also saved for Messages. A callback is a durable request that an active agent must consume; it cannot launch a closed desktop client.

The Android foreground service keeps availability/ringing alive while Android permits it. Foreground pages refresh at five-second intervals; call long-poll requests return early on new data. The stdio bridge heartbeats every ten seconds; presence expires after 35 seconds. These are presence and transport mechanisms, not a scheduler or autonomous agent worker.

## Persistence and boundaries

The operator owns `config.json`, `state/alphaMobile.sqlite3` and `state/releases/`. SQLite retains devices, credential hashes, calls, messages, task inventories/runs and callbacks. Raw credentials are issued once and stored privately by each client. The Android app stores its pairing credential/settings privately and fetches history from the relay. Android backup of private app data is disabled by the manifest/rules.

A relay is a single trusted owner/team boundary, not a multi-tenant hosted SaaS. Agents can discover paired devices to address them. Calls, pending questions, task records and callbacks are checked against the originating agent. Do not give unrelated untrusted parties credentials to the same relay. Transport uses HTTPS, but the relay itself can read conversation data.

## Operator configuration

`alphamobile --config /absolute/path/config.json serve` reads:

```json
{
  "state": "/home/operator/.local/share/alphamobile/state",
  "host": "127.0.0.1",
  "port": 8793,
  "allowed_hosts": ["localhost", "127.0.0.1", "YOUR-BOX.YOUR-TAILNET.ts.net"],
  "public_url": "https://YOUR-BOX.YOUR-TAILNET.ts.net"
}
```

These are example paths/hostnames: the installer generates your real values. `state` must be absolute. The listener stays on loopback behind a private HTTPS proxy. `allowed_hosts` restricts MCP host headers and should list explicit hostnames, never `*`. `public_url` records installation intent; the app still pairs using the entered HTTPS URL. `--state` explicitly overrides configuration; without a config, `ALPHAMOBILE_STATE` and `ALPHAMOBILE_HOSTS` support manual installations.

The stdio bridge uses a different private JSON file with `url`, `token` and optional `ca_file`. Keep the relay configuration and client credentials separate. The bridge is independently installable and does not depend on the relay Python package.

## Extension points

Add an MCP-capable agent without changing Android. Publish external scheduler records using stable task/run IDs without modifying the database schema. Use the existing stdio adapter or direct Streamable HTTP according to client support. The GGUF module is optional and has no automatic route into agent calls; integrations must explicitly supply context and decide when to invoke it.
