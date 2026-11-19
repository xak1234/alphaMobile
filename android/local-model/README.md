# Optional local GGUF runtime

This Android library is excluded by default. Enable it with `-PalphaLocalModel=true` after running the native build script. It preserves the pinned CPU llama.cpp implementation as an independent developer module; the communications app does not invoke it to answer agent questions.

Build commands and exact SDK/NDK/CMake requirements are in [BUILD.md](../../docs/BUILD.md). The library requires both selected ABI executables in `src/main/jniLibs`; missing assets fail the build. No weights are included. Import only a GGUF supported by the pinned llama.cpp revision, under its model licence, and small enough for the phone's RAM/storage.

Public class `org.alphamobile.localmodel.LocalModel`:

| Method | Behaviour |
| --- | --- |
| `LocalModel(Context)` | Retains application context; does not start a model |
| `model()` | Returns the app-private `assistant.gguf` file |
| `importFile(Uri)` | Copies a real selected content URI, checks GGUF magic/free space, replaces the model after successful copy |
| `reply(JSONArray, BooleanSupplier)` | Starts the bundled engine if needed and returns a model response for the supplied chat messages; supplier cancels startup/work when false |
| `close()` | Stops the owned native process |

Invoke file/model operations on a background worker, serialize imports/replies for each instance and call `close()` when the owner ends. The engine listens only on `127.0.0.1:18089` with an app-generated bearer secret. The wrapper rejects redirects, bounds response size and enforces that loopback origin. The app's network policy must allow loopback HTTP; it must never permit cleartext relay traffic on a network.

The caller owns context, consent, model selection and UI. This module is not wired to a hidden model picker and is not a fallback when an agent is unavailable. Startup/inference performance depends on device/model; no fixed response-time claim is made.
