# Contributing

Keep the relay, stdio adapter and Android app independently installable. Preserve existing call/message semantics and credential ownership checks. Never add a generated model answer as a fallback for an unanswered agent question. Keep device strings, hostnames, personal task data, certificates and credentials out of public fixtures/docs.

## Python checks

From the repository root, with Python 3.11+:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e './backend[dev]' -e ./adapters/mcp-stdio -e ./adapters/agent-bridge build twine
.venv/bin/ruff check --config backend/pyproject.toml backend adapters scripts
.venv/bin/mypy --config-file backend/pyproject.toml backend/alphamobile adapters/mcp-stdio/alphamobile_mcp adapters/agent-bridge/alphamobile_agent
.venv/bin/python -m pytest backend/tests adapters/agent-bridge/tests -q
.venv/bin/python -m build backend
.venv/bin/python -m build adapters/mcp-stdio
.venv/bin/python -m build adapters/agent-bridge
.venv/bin/python -m twine check backend/dist/* adapters/mcp-stdio/dist/* adapters/agent-bridge/dist/*
python3 scripts/audit-public.py
```

Expected lint/type results: `All checks passed!` and `Success: no issues found`. Tests exercise actual SQLite stores, CLI processes and FastAPI requests; two APK-feed tests skip until `ALPHAMOBILE_TEST_APK` names a real APK. Build Android using [BUILD.md](docs/BUILD.md), then rerun the suite with the actual artifact. Tests do not need a live production relay or physical phone.

The CI workflow builds the standard Android app, runs Android lint, then feeds its real debug-signed APK into the Python tests. Release signing remains private and is performed separately. Do not commit build outputs or test state. `audit-public.py` checks staged/tracked files when in Git, or the clean source tree before first publication.

## Review and releases

Use focused commits and describe the user-visible behaviour and checks run. Test changes at their boundary: real database for persistence, actual protocol clients for MCP, actual Android builds/lint for Java. Document physical-device limitations rather than claiming emulator audio behaviour establishes handset reliability.

For a release, update default Android version name/code, relay and bridge package versions, release docs and changelog. Run checks, sign/verify the generic APK without deployment properties, scan the final source and inspect archive contents, then tag the audited commit. Provide APK/wheels and SHA-256 checksums in GitHub Releases. The MIT licence applies to your contributions; preserve third-party notices and model licences.
