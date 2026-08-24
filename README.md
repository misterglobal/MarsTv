# MarsTV

MarsTV is a provider-neutral IPTV player for Android TV, Google TV, Fire TV, Android phones, and Android tablets. Users connect subscriptions they are authorized to access. The project does not include channels, playlists, credentials, or a content service.

## Version 0.1 scope

- One APK with remote-first TV navigation and touch-friendly mobile layouts
- Xtream login using server URL, username, and password
- Optional branded login using username and password only
- Full M3U URL login
- Multiple saved accounts and account switching
- Cable-style live guide with source-supported catch-up
- Movies, series, seasons, and episodes
- Search, favourites, history, and continue watching
- Multiple local profiles
- PIN-restricted categories selected per profile
- Encrypted local storage for accounts and profile state
- Automatic XMLTV loading from the Xtream endpoint or an M3U `x-tvg-url`/`url-tvg` declaration
- HTTP and HTTPS source support

The first version intentionally excludes casting, picture-in-picture, external players, cloud sync, billing, licensing, and downloads.

## Build an APK on Windows

1. Install the current stable Android Studio with Android SDK 36 and JDK 17.
2. Start Android Studio once and let it install any missing SDK packages.
3. Extract this project and run `build-apk.bat` from the `MarsTV` folder. The script can detect Android Studio's bundled JDK and the default Windows SDK location.
4. You can then open the `MarsTV` folder in Android Studio for editing and future builds.
5. The debug APK will be written to `app\build\outputs\apk\debug\app-debug.apk`.

The build script downloads Gradle 8.13 from the official Gradle distribution service, verifies its published SHA-256 checksum, creates the standard Gradle wrapper, and runs `assembleDebug`.

## Enable username/password-only MarsTV login

The branded login hides a fixed Xtream server URL from the sign-in screen. It does not hardcode a customer's username or password.

1. Copy `providers.properties.example` to `providers.properties`.
2. Replace the example value:

```properties
MARSTV_PRIVATE_PORTAL_URL=https://your-authorized-provider.example
```

3. Rebuild the APK.

You can also define `MARSTV_PRIVATE_PORTAL_URL` in your user-level Gradle properties instead. `providers.properties` is excluded from Git.

## Install on a device

Enable installation from unknown sources on the Android device, then transfer and open `app-debug.apk`. For Android TV or Fire TV, ADB can also be used:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Source compatibility

Xtream accounts provide the most complete experience because the API exposes separate live, VOD, series, episode, category, catch-up, and EPG data.

Plain M3U playlists vary widely. MarsTV reads standard tags including `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `x-tvg-url`, `catchup`, `catchup-source`, and `catchup-days`. Movie detection and episode grouping are best-effort when a playlist does not provide explicit metadata. If a provider supplies both Xtream credentials and an M3U URL, use Xtream for better series and guide results.

Catch-up URL formats are not fully standardized. MarsTV supports the common Xtream timeshift path and common M3U catch-up placeholders. A specific provider may require an adapter if it uses a custom replay format.

## Security notes

- Account and profile state is encrypted with an AES-GCM key held by Android Keystore.
- URLs and credentials are not written to application logs.
- HTTP is enabled because it was requested for compatibility. HTTP sends credentials and stream traffic without transport encryption. Use HTTPS whenever the source supports it.
- Deleting app data removes local accounts, profiles, favourites, and history.
- Before public distribution, add a privacy policy, release signing, dependency scanning, and licensing controls.

## Architecture

- Kotlin and Jetpack Compose for adaptive TV/mobile UI
- Media3 ExoPlayer for playback
- OkHttp for source requests and Coil for artwork
- DataStore plus Android Keystore for encrypted local state
- Streaming M3U and XMLTV parsers
- Separate Xtream and M3U data adapters behind `IptvRepository`

Main source packages:

- `core`: account, channel, programme, catalog, profile, and playback models
- `data/network`: Xtream, M3U, XMLTV, and network clients
- `data/local`: encrypted device state
- `data/repository`: source normalization and catch-up URL generation
- `ui`: state management, responsive navigation, guide, catalogs, profiles, settings, and player

## Release signing

The supplied build is a debug APK. Before distributing a production APK, create a private Android signing key and configure a `release` signing block. Never store the keystore password directly in the project.
