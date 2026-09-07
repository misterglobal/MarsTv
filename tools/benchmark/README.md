# MarsTV 100k device benchmark

The initial Pro launch baseline is an Android TV device or emulator with approximately 2 GB RAM. The former 1 GB Fire TV Stick Lite requirement is waived; the 100,000-entry workload and all timing, memory, and `largeHeap` criteria still apply.

This workflow collects the evidence required by Section 7 of the MarsTV Pro PRD. Passing on a desktop JVM does not replace the physical-device run.

## Prepare the fixture

From the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\benchmark\generate-100k-fixture.ps1
py -m http.server 8080 --directory .\tools\benchmark\benchmark-data
```

Use `http://<computer-lan-ip>:8080/catalog-100k.m3u` as the M3U URL. The computer and test device must be on the same network.

For an emulator, prefer an ADB reverse tunnel instead of the `10.0.2.2` NAT route when large
responses are truncated:

```powershell
adb -s <serial> reverse tcp:8080 tcp:8080
```

Then use `http://127.0.0.1:8080/catalog-100k.m3u` in MarsTV.

## Capture a run

Install a signed, minified release APK. Clear app data before each cold run, then start capture in a separate terminal:

```powershell
adb -s <serial> shell pm clear tv.mars.app
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\benchmark\capture-device-benchmark.ps1 -Serial <serial> -OutputDirectory .\tools\benchmark\results\android-tv-2gb-run-1
```

The default five-second sampling interval limits the garbage-collection pressure caused by
`dumpsys meminfo`. Use `-PreserveLogcat` when another capture already cleared logcat for the run.

In MarsTV, connect the fixture account. Imports do not expose a user cancel action. The capture records these log markers without account identifiers or URLs:

- `catalog_start`
- `catalog_first_usable durationMs=...`
- `catalog_complete durationMs=...`

Run at least three cold imports. Record the worst passing result, not only the fastest result.

## Pass criteria

- First usable category data: no more than 10 seconds.
- Complete import: no more than 180 seconds.
- Peak Java/Kotlin heap: below 192 MiB and below 60% of the runtime heap limit.
- Settled Java/Kotlin heap after forced test GC: below 128 MiB.
- No crash, ANR, or process restart.
- The first committed catalog page becomes usable while the remaining import continues.

`dumpsys meminfo` is sampled evidence, not a forced-GC mechanism. Use Android Studio's Memory Profiler or benchmark-only instrumentation for the final Java/Kotlin heap and forced-GC readings.

## Latest reference runs

On 2026-09-07, the minified release benchmark build completed three cold runs on the 2 GB
Android 11 x86 TV emulator through an ADB reverse tunnel. The worst passing measurements were:

- First usable page: 473 ms.
- Complete 100,000-entry import: 67,098 ms.
- Counts: 15,000 live, 45,000 movies, 40,000 series episodes, 0 unclassified.
- Peak sampled Java heap PSS: 14,012 KiB; peak Dalvik allocation: 7,599 KiB.
- Peak total process PSS: 78,295 KiB; settled total process PSS: 60,667 KiB.
- No crash, ANR, process restart, or `largeHeap` flag.

The three-run timing and sampled-memory gate passes. Final forced-GC evidence, production
signing, and release-over-release installation evidence remain required.

## Real-provider stress reference

On 2026-09-07, the streaming Xtream import was also exercised against a real provider catalog
on the same emulator. This is a stress reference rather than the standardized 100,000-entry
release gate; no provider credentials or URLs are retained in the results.

- First usable page: 5,336 ms.
- Catalog size: 39,840 live channels, 243,972 movies, and 52,604 series (336,416 objects).
- Catalog commit: approximately 407 seconds; complete workflow including the optional EPG:
  425,109 ms.
- Catalog-phase peak sampled Java heap PSS: 43,136 KiB; peak total process PSS: 135,732 KiB.
- Whole-workflow peak sampled Java heap PSS: 55,056 KiB; peak total process PSS: 140,716 KiB.
- Settled sampled Java heap PSS: 18,892 KiB; settled total process PSS: 92,112 KiB.
- No OOM, crash, or process restart.

The catalog committed before the EPG import completed. Compared with the previous eager Xtream
path, which approached 189 MiB of Java heap PSS before exposing its first page, the streaming
path exposed usable data promptly and held catalog-phase Java heap near 42 MiB.
