# Release validation — 0.2.0

Checks performed on 19 November 2026 against the generic public source, using isolated state and an Android API 36 x86_64 emulator.

| Check | Observed result |
| --- | --- |
| Python relay tests, including the actual signed 0.2.0 APK | **35 passed** |
| Ruff on relay, bridge and scripts | **All checks passed** |
| Strict mypy on relay and bridge | **No issues in 8 source files** |
| Independent relay/bridge wheel and sdist builds; Twine metadata | Passed; MIT licence present in both wheels |
| Fresh non-editable relay installation into an empty venv | Passed; health reports 0.2.0 |
| Installer rerun | Retained config, paired device and private credential file |
| Generated systemd user service | Syntax verified; started a separately installed relay, returned healthy, then stopped/removed test service |
| Standalone bridge in a different fresh venv | Forwarded initialization instructions and all fourteen MCP tools |
| Real MCP/HTTP round trip | Single-use pairing, typed question/reply, saved message, task/result, ringing call and cancellation passed |
| Standard Android debug, test and release builds | Passed without the optional local-model dependency |
| Android release lint | 0 errors; 15 warnings from existing UI/compatibility code |
| Optional local-model library build/lint | Passed using previously built binaries for the unchanged pinned native source; native compilation/inference was not rerun for this package |
| Fresh Android emulator launch/settings | Passed, including theme and persisted switches |
| Android real relay pairing/availability | Passed five online/offline cycles; no voice resources started merely by becoming available |
| Incoming call UI | Automatic ringing screen, foreground call notification, sound configured and decline clearing alert passed |
| Keyboard/composer | Actual Android keyboard open; complete composer and draft remain above it |
| Release signing/alignment/package | APK signature v2/v3 verified; alignment checked; package org.alphamobile.app, code 10, minSdk 26, targetSdk 36 |
| Generic configuration | Empty compiled default backend; no deployment CA or optional GGUF executable in the standard APK |
| Public source | Fresh history, private deployment data excluded; source/credential audit and documentation links checked |

Signed APK: `alphaMobile-0.2.0.apk`, **49,953,590 bytes**.

SHA-256: `a8542a52fedabc908418d5c348edb3c45ce302d511310634bba3f022255aa573`.

Signing certificate SHA-256: `62a2173a286731739c8028732bb618c3ef6587e53df2cbe78f4fbf0acc9672b1`.

## Reproduce

Follow [CONTRIBUTING](../CONTRIBUTING.md) for Python/package checks and [BUILD](BUILD.md) for Android. Set `ALPHAMOBILE_TEST_APK` to the built signed APK for all 35 tests; otherwise two APK-dependent tests explicitly skip. The repository workflow builds its own real debug-signed APK before running the suite.

The installer checks used fresh environments, not production credentials/databases. The systemd test used a separate test unit/listener. Android checks used an isolated relay and emulator identity. No everyday phone or existing private relay was upgraded for this public release.

## Limits

The full regression suite covers durable calls/messages/tasks and update access control. This release's emulator checks do not establish physical handset echo cancellation, microphone/TTS quality, roaming, lock-screen delivery under every Android vendor, or battery restrictions. The previously tested interface was retained, but the generic 0.2.0 APK has not been retested on every supported Android version or on a physical handset in this packaging pass. Windows/macOS foreground instructions are provided; the fresh installer/service execution checks were on Linux. Existing deprecation/lint warnings are recorded rather than suppressed. GitHub Actions execution status is visible on the repository.
