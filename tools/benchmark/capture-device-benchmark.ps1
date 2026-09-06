param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "results"),
    [ValidateRange(1, 30)]
    [int]$IntervalSeconds = 1
)

$absoluteOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $absoluteOutput | Out-Null
$metadataPath = Join-Path $absoluteOutput "device.txt"
$memoryPath = Join-Path $absoluteOutput "meminfo.txt"
$logcatPath = Join-Path $absoluteOutput "catalog-events.txt"

function Invoke-DeviceAdb {
    param([string[]]$Arguments)
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
}

$state = Invoke-DeviceAdb @('get-state')
if (($state | Out-String).Trim() -ne 'device') { throw "Device $Serial is not ready" }

@(
    "captured_at=$(Get-Date -Format o)"
    "serial=$Serial"
    "model=$((Invoke-DeviceAdb @('shell', 'getprop', 'ro.product.model') | Out-String).Trim())"
    "build_fingerprint=$((Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.fingerprint') | Out-String).Trim())"
    "os_release=$((Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.version.release') | Out-String).Trim())"
    "heap_size=$((Invoke-DeviceAdb @('shell', 'getprop', 'dalvik.vm.heapsize') | Out-String).Trim())"
    "heap_growth_limit=$((Invoke-DeviceAdb @('shell', 'getprop', 'dalvik.vm.heapgrowthlimit') | Out-String).Trim())"
    "mem_total=$((Invoke-DeviceAdb @('shell', 'sh', '-c', 'grep MemTotal /proc/meminfo') | Out-String).Trim())"
    "data_free=$((Invoke-DeviceAdb @('shell', 'df', '-k', '/data') | Out-String).Trim())"
) | Set-Content -LiteralPath $metadataPath -Encoding utf8

Invoke-DeviceAdb @('logcat', '-c') | Out-Null
Write-Host "Capturing once every $IntervalSeconds second(s). Press Ctrl+C after import and settled-memory checks."
Write-Host "Output: $absoluteOutput"

try {
    while ($true) {
        "===== $(Get-Date -Format o) =====" | Add-Content -LiteralPath $memoryPath -Encoding utf8
        Invoke-DeviceAdb @('shell', 'dumpsys', 'meminfo', '-d', 'tv.mars.app') |
            Add-Content -LiteralPath $memoryPath -Encoding utf8
        Invoke-DeviceAdb @('logcat', '-d', '-v', 'brief', 'MarsCatalogMetrics:I', '*:S') |
            Set-Content -LiteralPath $logcatPath -Encoding utf8
        Start-Sleep -Seconds $IntervalSeconds
    }
} finally {
    Invoke-DeviceAdb @('logcat', '-d', '-v', 'brief', 'MarsCatalogMetrics:I', '*:S') |
        Set-Content -LiteralPath $logcatPath -Encoding utf8
    Write-Host "Capture stopped. Metadata, memory samples, and catalog timing events are in $absoluteOutput"
}
