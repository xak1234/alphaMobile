# Build Android

The standard app requires JDK 17, Android SDK platform 36, build-tools 35.0.0, Python 3.11+ and internet access for pinned dependencies/speech assets. It does **not** require an NDK, CMake, GPU or GGUF model. The Gradle 8.13 wrapper and Android Gradle plugin 8.13.2 are included/configured.

Install Android Studio's SDK/command-line tools, then set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK directory. Accept the SDK licences interactively with `sdkmanager --licenses`. Install these components:

```bash
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
python3 scripts/prepare-speech.py
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

On Windows use `python` and `gradlew.bat`. Run from the repository root before `cd android`. The speech script downloads the actual English Vosk model, checks its pinned SHA-256 and writes generated assets. It never accepts a different archive silently. Generated model files and build outputs are ignored by Git.

Outputs:

- `android/app/build/outputs/apk/debug/app-debug.apk`: automatically debug-signed, suitable for isolated development.
- `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: instrumentation runner and real Vosk audio fixture.
- `android/app/build/reports/lint-results-release.html`: Android lint report.

Install a debug build with `adb install -r .../app-debug.apk`. Debug and official release keys differ: use a separate emulator/device or uninstall first (uninstalling loses local pairing/settings). Never replace a user's installation with a differently signed build.

## Build and sign a release

```bash
cd android
./gradlew :app:assembleRelease :app:lintRelease
```

Normal version: **0.2.0 / code 10**, package `org.alphamobile.app`, minimum Android API 26. The unsigned output is `app/build/outputs/apk/release/app-release-unsigned.apk`. The standard APK contains Vosk native libraries for ARM64 and x86_64, but no GGUF executable. It has an empty default relay address and trusts normal system HTTPS certificates.

For your own distribution create a signing key once using JDK `keytool`; keep that key and password outside this repository and backed up securely. For example, from a private directory:

```bash
keytool -genkeypair -keystore release.p12 -storetype PKCS12 -alias release -keyalg RSA -keysize 3072 -validity 10000
```

Use the Android SDK tools on your PATH. These commands prompt for passwords; do not put them into shell history:

```bash
zipalign -f -P 16 4 app-release-unsigned.apk alphaMobile-aligned.apk
apksigner sign --ks /private/path/release.p12 --ks-key-alias release --out alphaMobile-0.2.0.apk alphaMobile-aligned.apk
apksigner verify --verbose --print-certs alphaMobile-0.2.0.apk
zipalign -c -P 16 4 alphaMobile-0.2.0.apk
aapt dump badging alphaMobile-0.2.0.apk
```

Check the actual package/version/certificate before release. Future updates need this same certificate and a larger version code. The maintainers' signing key is not distributed. A fork's own key cannot update an installation of the official APK in place. Publish the APK through [your relay's update feed](UPDATES.md), or distribute it directly.

To set your next release version, use `-PalphaVersionName=0.2.1 -PalphaVersionCode=11` on the Gradle build. Keep documentation, Python versions and release notes aligned when releasing all components together.

## Optional local-model module

See [the module guide](../android/local-model/README.md). Only developers adding on-phone inference need this:

```bash
sdkmanager 'ndk;28.2.13676358' 'cmake;3.31.6'
python3 scripts/build-native.py --sdk "$ANDROID_HOME"
cd android
./gradlew :app:assembleDebug -PalphaLocalModel=true
```

The build script checks out the exact `LLAMA_COMMIT`, refuses a different/modified tracked checkout, builds real CPU executables for both ABIs and retains dependency notices. No GGUF weights are supplied. A build limited to one ABI must use the matching `--abis arm64-v8a` and `-PalphaAbis=arm64-v8a`. This module has no user-facing model picker or agent-call fallback in the standard app.

## Custom private certificate authority

Prefer Tailscale Serve or a publicly trusted HTTPS certificate. If your deployment requires a private CA, supply only its **public certificate**, never its private key:

```bash
./gradlew :app:assembleRelease -PalphaBackendUrl=https://relay.example.org -PalphaCaFile=/private/path/public-ca.pem
```

The generated trust rule is restricted to the supplied hostname. Such a deployment build embeds your endpoint and public CA, so it is different from the generic official APK. Keep deployment outputs out of Git. Do not add a trust-all certificate handler or expose unencrypted relay traffic on a network.

## Tests

[CONTRIBUTING](../CONTRIBUTING.md) covers Python checks and packaging. The custom Android runner operates real UI/services; inspect its documented arguments in `SmokeInstrumentation.java` before running against an isolated relay. Never point destructive test operations at an everyday phone or production history. Emulator tests cannot establish physical microphone quality, echo cancellation, battery behaviour or real-world roaming reliability.

Official references: [Android app signing](https://developer.android.com/studio/publish/app-signing), [command-line tools](https://developer.android.com/tools), [Android lint](https://developer.android.com/studio/write/lint).
