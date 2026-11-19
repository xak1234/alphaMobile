# Changelog

## 0.2.0 — 2026-11-19

First generic public distribution under MIT, based on the 0.1.8 application.

- Separate installable relay and stdio MCP bridge packages.
- Linux relay installer, persistent private configuration and optional systemd user service; foreground setup on Windows/macOS.
- Safe creation of private agent connection files without printing tokens or overwriting credentials.
- Optional local GGUF Android module; standard app builds without the native engine/toolchain.
- Generic pairing and server configuration; no private deployment address/certificate in the official APK.
- Full install, agent setup, build, backup, upgrade, security and contributor documentation; screenshots and automated checks.
- Existing dark-blue dashboard, compact expandable history, task results, typed chats, unread alerts, voice conversation and connected app updates retained.

### Compatibility

Android package `org.alphamobile.app`; version code 10. Python 3.11+. No private development history or runtime data is included. The relay does not run Claude Code or wake inactive agents. The standalone bridge replaces the bridge previously shipped inside the private relay package.
