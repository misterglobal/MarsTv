# Direct APK updates

The direct APK checks the signed manifest at `https://marstv.online/api/v1/releases/direct-stable` on foreground startup, subject to a six-hour interval, and through a network-constrained WorkManager job every six hours. Android may delay background jobs. Settings offers a manual check, limited to one per minute, and notification permission on Android 13+. Updates are available in Free and Pro.

Optional releases appear in Settings; recommended/critical releases can show a dismissible dialog outside playback. Notifications open the update screen, never install an APK. Notification availability depends on device settings. No Firebase/Google Play services are required. This is periodic discovery, not instant server push.

Users choose Download and install, wait for verification, then choose Install update. If needed, Android opens the per-app installation permission screen; the user returns and selects Install update again. Android's installer requires confirmation. Cancellation or server failure leaves the installed application and local player data untouched. No silent installation or backend-outage lockout is implemented. Priority/minimum-supported metadata is informational in this first release; it does not block playback or enforce deferral limits.

## Signing and trust

- Keep the APK production signing key unchanged and increase `versionCode` for every release. Version 0.3.0 / code 3 introduces the updater; existing 0.2.0 installs need that one manual update.
- The separate P-256 manifest public key is tracked in `app/update-public-key.txt`; `UPDATE_KEY_ID` defaults to `release-2026-01`. Local `providers.properties` may override it using `MARSTV_UPDATE_PUBLIC_KEY_FILE` or `MARSTV_UPDATE_PUBLIC_KEY_PEM` and `MARSTV_UPDATE_KEY_ID`.
- The corresponding private manifest key was generated locally under `.artifacts/release-keys/release-private.pem`. **Back it up securely. Never upload it to cPanel, commit it, or confuse it with the entitlement or APK signing key.** A clean checkout contains only the public key. Losing the private manifest key prevents publishing updates trusted by these installs.
- The client validates ES256, key ID, token type, issuer, audience, package, channel, certificate, size and URL before using the payload. APKs are restricted to HTTPS files directly under `marstv.online/downloads/`, without redirects or query strings.
- Downloads stream into app-private cache, with a 250 MiB maximum and signed size/hash enforcement. Before installation, the client verifies the archive package/version/minimum Android version and compares the APK signer with its installed signing certificate. The manifest's certificate must match that same signer. Android verifies package signatures again during installation.
- The signed offer is fetched again before downloading and before installation. Removing the manifest pauses updates. Serving an older manifest never causes a downgrade. Pausing cannot retract an installation already handed to Android.
- Failed downloads are deleted. Cached APKs are not trusted across process restarts, and old cached files are removed after one day when the manager initializes. FileProvider exposes only the private updates directory.

## Build and prepare a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Configure `keystore.properties` using the existing release key, then run `./gradlew.bat :app:assembleRelease`. The output is `app/build/outputs/apk/release/app-release.apk`.
3. Put release notes in a UTF-8 text file, one item per line.
4. Run the generator below (Python 3.11+ with `cryptography`). It calls Android's `apksigner` to verify the APK signature, reads actual package/version metadata with `aapt2`, hashes the exact APK, and signs the manifest. It rejects debuggable APKs and non-increasing versions.

```powershell
python tools/release_manifest.py sign `
  --apk app/build/outputs/apk/release/app-release.apk `
  --build-tools "$env:LOCALAPPDATA/Android/Sdk/build-tools/36.0.0" `
  --java 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe' `
  --private-key .artifacts/release-keys/release-private.pem `
  --url https://marstv.online/downloads/MarsTV-0.3.1.apk `
  --notes .artifacts/release-notes-0.3.1.txt `
  --previous-version-code 3 `
  --output .artifacts/direct-stable.jws
```

The companion JSON is for reviewing release metadata. Only the compact `.jws` file is consumed by clients. Do not edit it after signing. `--previous-version-code` must reflect the last published release; the tool does not query the production server.

## Publish through cPanel

1. Deploy `public/releases.php` and the changed `public/.htaccess` (plus `router.php` if using PHP's local server).
2. Upload the APK to the site's public `downloads/` directory using the filename in the signed manifest. Check its uploaded SHA-256 against the generator output before offering it.
3. Create `storage/releases/` inside the backend, outside the public document root. Upload the `.jws` under a temporary name, then rename it to `direct-stable.jws` as the final publication step. No release signing secret belongs on the server.
4. Set `MARSTV_APK_URL`, `MARSTV_APK_VERSION` and `MARSTV_APK_SHA256` in the host's private `.env` to keep `/download` consistent with the release.
5. Check that `/api/v1/releases/direct-stable` returns HTTP 200 with `Content-Type: application/jose`. Removing/renaming the private `.jws` file makes the endpoint return 404 and pauses new downloads/install starts.

No database migration or cron job is needed for updates. Keep the transfer cleanup cron unchanged. A future Play variant must disable this direct updater and its installation permission in favour of Play-managed updates.

## Acceptance before public rollout

Run JVM verification/download tests, producer signature tests, Android lint and the signed release build. Then install 0.3.0 over an existing production-signed 0.2.0 on a test device, retaining local data and Pro. Publish a higher-version test release and verify manual/background discovery, notification navigation, checksum rejection, cancellation, install-permission return flow, installer approval and retained local data. Verify on Android TV/Google TV, Fire TV and a phone; background scheduling and installer settings vary by device. A build without a connected device cannot establish those acceptance results.

## 0.3.1 validation

The signed 0.3.1 (version code 4) build passed 58 JVM tests and release lint checks,
and installed over 0.3.0 on the test emulator without clearing app data. The movie
fallback restored 244,068 movies, and the user confirmed direct Xtream login after
correcting a username typo. Cached lineups no longer show the loading overlay during
refresh; newly committed catalogs clear it before programme-guide fetching completes.
The changed loading behaviour still needs explicit user confirmation.

These checks do not establish signed-manifest download and Android-installer
acceptance: the emulator upgrade used ADB. Complete the update-flow acceptance
checks above before public rollout.
