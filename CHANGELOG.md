# Changelog

## 0.3.0 — 2026-11-19

- New optional always-on agent bridge, `alphamobile-agent` (`adapters/agent-bridge`). It keeps an agent online and answers phone messages, call questions and callback requests without a desktop session, using an OpenAI-compatible endpoint (local or hosted) or a CLI agent such as `claude -p`, with an optional faster backend for voice calls. Includes example configurations, a systemd user unit, and tests against the real relay.
- Documentation explains when to use the always-on bridge, the stdio bridge or direct HTTP.
- CI lints, type-checks, tests and builds the new package.

### Compatibility

Only the new `alphamobile-agent` 0.3.0 package is new. The Android app (0.2.0, version code 10, same signed APK), relay (`alphamobile` 0.2.0) and stdio bridge (`alphamobile_mcp` 0.2.0) are unchanged and are re-published with this release as-is. No relay upgrade is needed to use the agent bridge.

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
