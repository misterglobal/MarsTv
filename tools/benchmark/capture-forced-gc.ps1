param(
    [string]$Serial = "emulator-5554",
    [string]$OutputPath = (Join-Path $PSScriptRoot "results/forced-gc.txt")
)

$adb = Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe"
if (!(Test-Path -LiteralPath $adb)) {
    $adb = (Get-Command adb -ErrorAction Stop).Source
}

$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $absoluteOutput
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

function Invoke-DeviceAdb {
    param([string[]]$Arguments)
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
}

if (((Invoke-DeviceAdb @('get-state') | Out-String).Trim()) -ne 'device') {
    throw "Device $Serial is not ready"
}

$pidValue = (Invoke-DeviceAdb @('shell', 'pidof', 'tv.mars.app') | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($pidValue)) {
    throw "MarsTV is not running. Open the populated catalog before capturing forced-GC evidence."
}

Invoke-DeviceAdb @('logcat', '-c') | Out-Null
$before = Invoke-DeviceAdb @('shell', 'dumpsys', 'meminfo', '-d', 'tv.mars.app')
Invoke-DeviceAdb @(
    'shell', 'am', 'broadcast', '-a', 'tv.mars.app.benchmark.FORCE_GC',
    '-n', 'tv.mars.app/tv.mars.app.benchmark.ForceGcReceiver'
) | Out-Null

$deadline = (Get-Date).AddSeconds(15)
$event = ""
do {
    Start-Sleep -Milliseconds 500
    $event = (Invoke-DeviceAdb @('logcat', '-d', '-v', 'brief', 'MarsCatalogMetrics:I', '*:S') |
        Where-Object { $_ -match 'forced_gc_complete' } |
        Select-Object -Last 1)
} while ([string]::IsNullOrWhiteSpace($event) -and (Get-Date) -lt $deadline)

if ([string]::IsNullOrWhiteSpace($event)) { throw "Timed out waiting for forced-GC evidence" }
$after = Invoke-DeviceAdb @('shell', 'dumpsys', 'meminfo', '-d', 'tv.mars.app')

@(
    "captured_at=$(Get-Date -Format o)"
    "serial=$Serial"
    "pid=$pidValue"
    "event=$event"
    "===== BEFORE ====="
    $before
    "===== AFTER ====="
    $after
) | Set-Content -LiteralPath $absoluteOutput -Encoding utf8

Write-Host $event
Write-Host "Evidence: $absoluteOutput"
