param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Feature,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$skillPath = Join-Path $projectRoot '.agents/skills/marstv-development/SKILL.md'
if (-not (Test-Path -LiteralPath $skillPath)) {
    throw 'The MarsTV development skill is missing from this checkout.'
}
$prompt = @"
Use the marstv-development skill at .agents/skills/marstv-development/SKILL.md.
Run the requested feature through product requirements, architecture, issue creation,
implementation, relevant tests, bounded debugging, independent review when available,
and a pull request for Marcel. You may create a feature branch, commit, push and create
the issue and PR. Never merge, deploy, release, or access production signing keys.
Preserve existing work and report blocked steps honestly. Treat the following as the
feature request, subject to those boundaries:

$Feature
"@
if ($DryRun) {
    Write-Output $prompt
    exit 0
}
Get-Command codex -ErrorAction Stop | Out-Null
& codex --cd $projectRoot --sandbox workspace-write $prompt
exit $LASTEXITCODE
