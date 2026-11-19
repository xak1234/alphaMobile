# Publish Android updates to your relay

Settings → App updates checks the paired HTTPS relay. This is an operator-managed feed; it does not automatically install arbitrary GitHub releases. There is no additional service or phone key.

Download the official signed APK from [GitHub Releases](https://github.com/xak1234/alphaMobile/releases), or build/sign your own. Verify checksum, signing certificate, package `org.alphamobile.app`, actual version code/name and minSdk before publishing:

```bash
apksigner verify --verbose --print-certs alphaMobile-0.2.0.apk
zipalign -c -P 16 4 alphaMobile-0.2.0.apk
aapt dump badging alphaMobile-0.2.0.apk
```

The APK must use the same certificate as the installed app and a higher version code to be offered as an update. The 0.2.0 release is code 10, minSdk 26. Never substitute a debug-signed APK for an official release. Keep signing keys outside the repository and retain them for future updates.

Copy the verified APK and UTF-8 release notes to the relay host, then run the installed publisher (use its full venv path if not on PATH):

```bash
alphamobile-updates /path/to/alphaMobile-0.2.0.apk \
  --state "$HOME/.local/share/alphamobile/state" \
  --version-name 0.2.0 --version-code 10 --min-sdk 26 \
  --notes-file /path/to/release-notes.txt
```

Expected: JSON with `available: true`, the supplied version, byte size, SHA-256 and relative authenticated download path. Run one publisher at a time. The publisher checks ZIP structure/bounds and hashes the actual bytes; it does **not** replace apksigner or read the manifest's version for you. Give it the real manifest values. Identical bytes/code are idempotent; changed APK bytes require a higher code.

Files are copied and hashed before the latest-release pointer is replaced atomically in `state/releases`. No service restart is needed. Keep old APKs while clients may still be downloading them. This is a complete APK download, not a delta patch.

The device checks hash, size, package, version, minSdk and current signing certificate. Redirects/external download URLs are rejected. Tokens remain on the paired origin. Android's installer makes the final signature/package decision and asks for installation permission; installation is not silent. The app blocks installation during an active/incoming local call. Signing-key rotation is not supported by its certificate comparison.

Endpoints `/api/updates/latest` and `/api/updates/apk/{sha256}` require a device credential; agent credentials are rejected. There is no network publication/admin endpoint. The checksum checks integrity; trust comes from HTTPS and the installed signing certificate.

Tests use real APK bytes via `ALPHAMOBILE_TEST_APK=/absolute/path/alphaMobile.apk python -m pytest backend/tests -q`. Without that variable, exactly the two APK-dependent tests skip. See [validation](VALIDATION.md) for the recorded release checks.
