# MarsTV 100k device benchmark

The initial Pro launch baseline is an Android TV device or emulator with approximately 2 GB RAM. The former 1 GB Fire TV Stick Lite requirement is waived; the 100,000-entry workload and all timing, memory, cancellation, and `largeHeap` criteria still apply.

This workflow collects the evidence required by Section 7 of the MarsTV Pro PRD. Passing on a desktop JVM does not replace the physical-device run.

## Prepare the fixture

From the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\benchmark\generate-100k-fixture.ps1
py -m http.server 8080 --directory .\tools\benchmark\benchmark-data
```

Use `http://<computer-lan-ip>:8080/catalog-100k.m3u` as the M3U URL. The computer and test device must be on the same network.

## Capture a run

Install a signed, minified release APK. Clear app data before each cold run, then start capture in a separate terminal:

```powershell
adb -s <serial> shell pm clear tv.mars.app
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\benchmark\capture-device-benchmark.ps1 -Serial <serial> -OutputDirectory .\tools\benchmark\results\android-tv-2gb-run-1
```

In MarsTV, connect the fixture account. For cancellation runs, press **Cancel import** after parsing has begun. The capture records these log markers without account identifiers or URLs:

- `catalog_start`
- `catalog_complete durationMs=...`
- `catalog_cancel_requested`
- `catalog_cancel_stopped cancelLatencyMs=...`

Run at least three cold imports and one cancellation. Record the worst passing result, not only the fastest result.

## Pass criteria

- First usable category data: no more than 10 seconds.
- Complete import: no more than 180 seconds.
- Peak Java/Kotlin heap: below 192 MiB and below 60% of the runtime heap limit.
- Settled Java/Kotlin heap after forced test GC: below 128 MiB.
- Cancellation acknowledged by the UI: no more than 2 seconds.
- Import job fully stopped: no more than 5 seconds after the cancel request.
- No crash, ANR, process restart, partial generation activation, or resumed database growth after cancellation.

`dumpsys meminfo` is sampled evidence, not a forced-GC mechanism. Use Android Studio's Memory Profiler or benchmark-only instrumentation for the final Java/Kotlin heap and forced-GC readings.
