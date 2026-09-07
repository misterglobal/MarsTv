param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE "AndroidKeys\MarsTV-release.jks")
)

$absoluteKeystore = [System.IO.Path]::GetFullPath($KeystorePath)
if (!(Test-Path -LiteralPath $absoluteKeystore -PathType Leaf)) {
    throw "Keystore not found: $absoluteKeystore"
}

$alias = Read-Host "Key alias"
if ([string]::IsNullOrWhiteSpace($alias)) { throw "Key alias is required" }
$storePasswordSecure = Read-Host "Keystore password" -AsSecureString
$keyPasswordSecure = Read-Host "Key password (usually the same as the keystore password)" -AsSecureString

$storePassword = [System.Net.NetworkCredential]::new('', $storePasswordSecure).Password
$keyPassword = [System.Net.NetworkCredential]::new('', $keyPasswordSecure).Password
if ([string]::IsNullOrEmpty($storePassword) -or [string]::IsNullOrEmpty($keyPassword)) {
    throw "Both passwords are required"
}

function Convert-ToJavaPropertyValue {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace(':', '\:').Replace('=', '\=')
}

$outputPath = Join-Path (Split-Path -Parent $PSScriptRoot) "keystore.properties"
$lines = @(
    "storeFile=$(Convert-ToJavaPropertyValue ($absoluteKeystore.Replace('\', '/')))"
    "storePassword=$(Convert-ToJavaPropertyValue $storePassword)"
    "keyAlias=$(Convert-ToJavaPropertyValue $alias)"
    "keyPassword=$(Convert-ToJavaPropertyValue $keyPassword)"
)
[System.IO.File]::WriteAllLines($outputPath, $lines, [System.Text.UTF8Encoding]::new($false))

$storePassword = $null
$keyPassword = $null
Write-Host "Created Git-ignored signing configuration: $outputPath"
Write-Host "You can now run .\gradlew.bat :app:assembleRelease"
