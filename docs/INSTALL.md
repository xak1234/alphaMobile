# Install your own alphaMobile

## Requirements

- An Android 8+ ARM64 or x86_64 device. Nothing Phone (2a) motivated the original design; other handsets require their own voice/background-permission checks.
- A relay host with Python 3.11+, a few hundred MB of disk, and a stable private network connection. No GPU or model is needed by the relay.
- An MCP-capable agent/client you control. A connected but inactive model cannot answer calls or messages.
- HTTPS connectivity from phone and agent to the relay. Tailscale on all relevant computers/devices is the documented path; another private network and trusted TLS proxy also works.

The supplied APK bundles an English speech recognizer. Android supplies text-to-speech; install an offline English voice in Android's TTS settings if necessary. No audio recordings are uploaded by alphaMobile; recognized text is sent to the agent.

## 1. Prepare private HTTPS

Install and authenticate Tailscale using its [official installation guide](https://tailscale.com/download). Connect the phone, relay and remote agent computer to your tailnet. Follow the Tailscale [HTTPS certificate](https://tailscale.com/docs/how-to/set-up-https-certificates) and [Serve](https://tailscale.com/docs/reference/tailscale-cli/serve) prerequisites. Your tailnet administrator may need to enable HTTPS certificates and authorize Serve.

Find your relay's MagicDNS HTTPS name in Tailscale. Use that full name throughout configuration, for example `https://my-box.example-tailnet.ts.net`. The examples below must be replaced with your own hostname. Tailnet access rules must permit your devices to reach the relay's HTTPS service. Do not expose the relay directly to the public Internet or use Funnel for this private setup.

## 2. Install the relay on Linux

Debian/Ubuntu prerequisites:

```bash
sudo apt-get update
sudo apt-get install python3 python3-venv git
git clone https://github.com/xak1234/alphaMobile.git
cd alphaMobile
python3 scripts/install-relay.py --url https://YOUR-BOX.YOUR-TAILNET.ts.net --service
```

This runs as your normal user, installs a non-editable Python package into `~/.local/share/alphamobile/.venv`, and creates:

| Path under `~/.local/share/alphamobile` | Purpose |
| --- | --- |
| `config.json` | Absolute state directory, loopback bind/port, accepted MCP hostnames and public URL |
| `state/alphaMobile.sqlite3` | Private credential hashes, calls, text and task results |
| `state/releases/` | Operator-published APKs and latest release metadata |
| `.venv/` | Installed relay and pinned dependencies |

The installer creates a systemd **user** service at `~/.config/systemd/user/alphamobile.service`. To keep it running after logout and start it at boot, enable lingering for your account:

```bash
sudo loginctl enable-linger "$USER"
systemctl --user status alphamobile.service
journalctl --user -u alphamobile.service -n 50
```

Do not run multiple workers or multiple relay processes against the same state directory. The store uses SQLite and the long-poll wake signal is process-local.

If an existing service belongs to another installation, the installer refuses to overwrite it. Matching reruns update the installed package and restart the managed service while retaining state/configuration. Changing URLs or ports requires an explicit configuration edit; see [operations](OPERATIONS.md).

### Without systemd; Windows or macOS

Omit `--service`. Use Python 3.11+ (`py -3` on Windows if needed):

```bash
python3 scripts/install-relay.py --url https://YOUR-BOX.YOUR-TAILNET.ts.net
```

Run the foreground command printed by the installer. It uses the generated `--config` file. On Windows the executable is `.venv\Scripts\alphamobile.exe`; on macOS/Linux it is `.venv/bin/alphamobile`. The terminal/process must stay running. The installer does not create a Windows Service, launchd job or firewall rule. For always-on operation, the tested service path is Linux/systemd.

### Manual package installation

```bash
python3 -m venv .venv
.venv/bin/python -m pip install ./backend
export ALPHAMOBILE_STATE="$PWD/state"
export ALPHAMOBILE_HOSTS=localhost,127.0.0.1,YOUR-BOX.YOUR-TAILNET.ts.net
.venv/bin/alphamobile serve
```

Use restrictive permissions for state. `ALPHAMOBILE_HOSTS` configures the MCP transport host checks. The relay listens on `127.0.0.1:8793` by default, behind your HTTPS proxy. All provisioning commands must use the **same** `--config`, `--state`, or `ALPHAMOBILE_STATE` as the running relay.

## 3. Forward HTTPS and check it

With the relay running:

```bash
sudo tailscale serve --bg http://127.0.0.1:8793
tailscale serve status
curl https://YOUR-BOX.YOUR-TAILNET.ts.net/health
```

Expected health JSON includes `name: alphaMobile`, `version: 0.2.0`, `status: ok`. Use the HTTPS URL without port 8793 when Serve is on its default port 443. The API and MCP endpoint share that base URL. If Serve already hosts another application, configure a separate HTTPS port rather than replacing its existing service, and include that port in the phone/agent URL.

The generic APK trusts Android's system certificate authorities. A self-signed certificate is not accepted automatically; use [custom endpoint trust](BUILD.md) for a private CA. Never disable certificate or hostname checks.

## 4. Install and pair Android

Download `alphaMobile-0.2.0.apk` from [Releases](https://github.com/xak1234/alphaMobile/releases). Allow installation from the downloading app when Android asks. Keep the same signing key for future updates.

Generate a keyword on your relay:

```bash
~/.local/share/alphamobile/.venv/bin/alphamobile \
  --config ~/.local/share/alphamobile/config.json pair
```

The generated four-word keyword expires after 24 hours and works once. To choose your own, add `--keyword YOUR-CHOSEN-WORDS`; that replaces any unconsumed pairing keyword. Run the command again to pair another device.

In the app, open **Settings → Your box**, enter the relay's HTTPS address and pairing keyword, then **Pair with my box**. No API token or signing code is entered on the phone.

Allow notifications. Enable **Become available** for background calls and message pings. For reliable ringing, inspect these Android settings from alphaMobile's Settings page:

- App notifications and incoming-call/message channel sound.
- Full-screen incoming calls for a ringing screen over the lock screen, where Android offers it.
- Unrestricted battery use for alphaMobile and a working Tailscale connection.
- Microphone permission when answering a voice call; it is not required for typed messages.

Quiet mode suppresses alerts. Android volume, silent mode and Do Not Disturb still apply. Force-stopping the app stops reception; reopen and become available after a force-stop/reboot.

## 5. Add an agent

Follow [MCP.md](MCP.md) to generate a private agent connection file and configure either Streamable HTTP or the independent stdio bridge. Different agents should have separate credentials. `list_devices` identifies your paired phone; call it only when you intend to test ringing.

Schedule/results display does not grant access to an agent's internal scheduler. The scheduling agent must publish its actual schedules and actual run results through the provided MCP tools.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Health URL unreachable | Relay service, Tailscale login/connectivity, Serve status and tailnet access rules |
| Pairing rejected | Same state/config file as the service, correct keyword, expiry and whether it was already consumed |
| TLS failure | Correct HTTPS hostname, public CA, correct device clock; rebuild for an intentionally private CA |
| MCP invalid host | Actual HTTPS hostname appears in `config.json` `allowed_hosts`, then restart the relay |
| Agent linked but no reply/callback | The agent must poll and answer; a transport connection alone does not run a model |
| No ringing | Availability, Quiet mode, channel sound, notification/full-screen permissions, Android battery/DND controls |
| Tasks empty or stale | Agent's actual task source and reporting loop; see task-sync tools in MCP.md |
| Update not offered | Published higher version code on your paired relay; valid APK metadata and same certificate |
| App not installed | Existing app signed with a different key, insufficient space, unsupported ABI; keep data/backups before replacing an installation |

See [OPERATIONS.md](OPERATIONS.md) for backup, revocation, upgrades and uninstalling without deleting data.
