param(
    [string]$OutputPath = (Join-Path $PSScriptRoot "benchmark-data/catalog-100k.m3u")
)

$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $absoluteOutput
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
$writer = [System.IO.StreamWriter]::new($absoluteOutput, $false, $utf8WithoutBom, 1MB)
$bullet = [char]0x2022
$enDash = [char]0x2013
$cafe = "Caf$([char]0x00E9)"
$world = -join [char[]](0x4E16, 0x754C)
$japanese = -join [char[]](0x65E5, 0x672C, 0x8A9E)
try {
    $writer.WriteLine('#EXTM3U')

    for ($index = 0; $index -lt 15000; $index++) {
        $group = "Live $bullet $cafe $($index % 40)"
        $writer.WriteLine("#EXTINF:-1 tvg-id=`"live-$index`" tvg-name=`"Live $index`" tvg-logo=`"https://images.example.invalid/live/$index.jpg`" group-title=`"$group`",Live $index")
        $writer.WriteLine("https://streams.example.invalid/live/$index.ts")
    }

    for ($index = 0; $index -lt 45000; $index++) {
        $group = "Movies $enDash $world $($index % 60)"
        $writer.WriteLine("#EXTINF:-1 tvg-name=`"Movie $index`" tvg-logo=`"https://images.example.invalid/movies/$index.jpg`" group-title=`"$group`",Movie $index")
        $writer.WriteLine("https://streams.example.invalid/movies/$index.mp4")
    }

    for ($index = 0; $index -lt 40000; $index++) {
        $show = $index % 1000
        $episode = [Math]::Floor($index / 1000) + 1
        $group = "Series $enDash $japanese $($show % 50)"
        $writer.WriteLine("#EXTINF:-1 tvg-name=`"Show $show S01E$episode`" tvg-logo=`"https://images.example.invalid/series/$show.jpg`" group-title=`"$group`",Show $show S01E$episode")
        $writer.WriteLine("https://streams.example.invalid/series/$show/$index.mp4")
    }
} finally {
    $writer.Dispose()
}

$lineCount = 1 + (100000 * 2)
$sizeMiB = [Math]::Round((Get-Item -LiteralPath $absoluteOutput).Length / 1MB, 2)
Write-Host "Created $absoluteOutput"
Write-Host "Entries: 100000 (live=15000, movies=45000, series episodes=40000)"
Write-Host "Lines: $lineCount; size: $sizeMiB MiB"
