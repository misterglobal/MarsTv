# Home clock: first development-loop trial

## Outcome

Show current local time at the bottom-right of the shared home/selection screen for Free and Pro users identically.

## Acceptance criteria

- Updates while the home screen is visible, using device locale, timezone and 12/24-hour preference.
- Reserves footer space within safe screen insets on TV and mobile.
- Does not take D-pad focus or obscure navigation, guide rows or catalog items.
- Has no entitlement parameter or conditional license gate.
- Does not appear over fullscreen playback, account setup or separate overlays.
- PR checks compile debug and instrumentation APKs, run unit tests and lint, and publish the debug APK without production signing secrets.

## Architecture

MarsTvRoot.kt routes accounts, overlays and playback. HomeShell is the shared Compose home container; it has side navigation on TV/wide screens and bottom navigation on narrow screens. Add a footer after either layout, with weighted destination content to reserve its height. HomeClock wraps the platform TextClock; Android owns clock ticks, time changes, format selection and attachment lifecycle. No custom timer or licensing dependency is needed.

## Verification

Run Gradle assembleDebug, testDebugUnitTest, lintDebug and assembleDebugAndroidTest. Instrumentation verifies the rendered native clock and that it cannot take remote focus. Manually verify minute rollover, background/resume, timezone and 12/24-hour changes, TV/mobile layouts, and matching Free/Pro placement. Do not claim manual/device checks completed without a device.

## Scope

Clock, PR validation, feature intake and reusable local Codex development skill. No backend, billing/licensing, signing-key, database, merge, staging or production changes. Future staging/release automation requires a separately defined environment and authorization.
