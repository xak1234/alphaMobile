# Operate, back up and upgrade

The Linux installer uses a user service called `alphamobile.service`. Run service commands as the installing user:

```bash
systemctl --user status alphamobile.service
journalctl --user -u alphamobile.service -n 100 --no-pager
systemctl --user restart alphamobile.service
```

Use `sudo loginctl enable-linger "$USER"` if the server must run after logout and at boot. Tailscale also needs to remain running. Do not run two relay processes against the same listener. Logs may contain failure details; inspect/redact them before sharing.

## Back up before upgrades

Stop the service, then copy the complete installation's `state` directory and `config.json` to private backup storage. Copy each client connection file separately and retain your Android signing key/password if you distribute your own build. Protect backups like your conversations and credentials. The `.venv` can be recreated and need not be backed up.

```bash
systemctl --user stop alphamobile.service
umask 077
mkdir -p "$HOME/alphamobile-backups"
tar -czf "$HOME/alphamobile-backups/relay-$(date +%Y%m%d-%H%M%S).tar.gz" \
  -C "$HOME/.local/share/alphamobile" config.json state
systemctl --user start alphamobile.service
```

Stopping first makes the SQLite database/WAL and release pointer consistent. If you require online backups, use Python/SQLite's actual backup API for the database rather than copying a live `.sqlite3` file alone. Test restoration into a separate directory/listener. A restored copy must not run simultaneously as a second live relay for the same phones.

## Upgrade the relay

Read release notes and take a backup. From your clean source checkout, select the intended tagged release, stop the service and rerun the installer with exactly your existing URL and port:

```bash
git fetch --tags
git checkout v0.2.0
systemctl --user stop alphamobile.service
python3 scripts/install-relay.py --url https://YOUR-BOX.YOUR-TAILNET.ts.net --service
```

The installer replaces the installed Python package while preserving the same config/state. It refuses a conflicting URL/port or a different existing service definition. If package installation fails, inspect the error before starting the service. For a wheel installation instead, activate its environment and run `python -m pip install --upgrade /path/to/alphamobile-0.2.0-py3-none-any.whl`.

Upgrade each stdio bridge environment separately, then restart its MCP client. Existing phone credentials remain valid. Backend, bridge and Android versions are separately installed; upgrading the relay does not replace the Android APK. [Publish app updates](UPDATES.md) through the relay or install a matching signed release directly.

To roll back, stop the service, reinstall the previous package/source and restore that release's database/config backup together. Do not assume future schema changes are backward compatible. Keep the original backup intact until the restored service and device connection have been verified.

## Credentials and pairing

Run `alphamobile --config ... devices` to list paired devices. Provisioned agent IDs are printed when their connection file is created; retain the ID with your private operational notes. Revoke a lost device or compromised agent with:

```bash
alphamobile --config /absolute/path/config.json revoke ID
```

After revocation, create a new agent credential or generate a fresh one-use phone keyword and pair again. A new custom pairing keyword replaces the previous outstanding one. Pairing expires after 24 hours and is consumed once. Do not publish keywords or connection JSON. On Windows, restrict client files to your user account using Windows file permissions; POSIX mode bits alone do not establish a Windows ACL.

## Remove the installation

First stop/disable the service:

```bash
systemctl --user disable --now alphamobile.service
rm "$HOME/.config/systemd/user/alphamobile.service"
systemctl --user daemon-reload
```

Then remove the installation directory only if you intentionally want to delete its history and credentials, and have retained any required backup. Remove your corresponding HTTPS proxy route without resetting unrelated Tailscale Serve routes. Uninstalling Android removes its local settings; relay data remains until the operator deletes it.
