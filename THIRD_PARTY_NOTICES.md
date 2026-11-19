# Third-party software

alphaMobile's own source is MIT licensed. This does not relicense dependencies, model weights or Android system voices.

- Optional local-model module only: llama.cpp / GGML, pinned by `LLAMA_COMMIT`: MIT, with upstream third-party notices. The Android launcher pattern and initial portable native build configuration were adapted from Grant Wilson's MIT-licensed Franks Local AI project.
- Vosk Android 0.3.75: Apache-2.0. JNA 5.18.1 is used under its Apache-2.0 option.
- Bundled speech model: Alpha Cephei `vosk-model-small-en-us-0.15`, Apache-2.0. Its archive SHA-256 is pinned by `scripts/prepare-speech.py`.
- AndroidX Core 1.17.0 and its AndroidX/Kotlin runtime dependencies retain their Apache-2.0 licences. Core provides the restricted APK FileProvider. The Apache-2.0 text is included in the offline licence viewer.
- Optional local-model module: Android NDK runtime/toolchain notices are retained in that module's `assets/notices` directory and are packaged when it is enabled.
- Optional native server dependencies include cpp-httplib, nlohmann/json and hash implementations; upstream licence texts are retained.
- Backend: FastAPI, Uvicorn, HTTPX, Pydantic and MCP Python SDK retain their upstream licences.
- Gradle wrapper/build tooling retain their upstream licences and are not licensed as application code by this project.

The app's Settings → Open-source licences reads the bundled licence files offline. No language-model GGUF weights or Android TTS voice data are redistributed with the APK. Those have their own provider licences.

Upstream sources: https://github.com/ggml-org/llama.cpp · https://github.com/alphacep/vosk-api · https://alphacephei.com/vosk/models · https://github.com/java-native-access/jna · https://github.com/modelcontextprotocol/python-sdk

## Speech test fixture

`android/app/src/androidTest/assets/speech-test.wav` is the Vosk project's `python/example/test.wav` sample, used only in the instrumentation test APK, under Vosk's Apache-2.0 licence. Upstream: https://github.com/alphacep/vosk-api/blob/master/python/example/test.wav . The production APK does not include this fixture.
