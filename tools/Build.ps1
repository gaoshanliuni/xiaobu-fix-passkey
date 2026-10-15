#requires -Version 7
[CmdletBinding()]
param([string]$JavaHome, [switch]$Release)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# Use a long-form Windows temp path: some JDK builds fail AF_UNIX connection with 8.3 TEMP aliases.
$socketTemp = Join-Path $project '.gradle\socket-temp'
New-Item -ItemType Directory -Force -Path $socketTemp | Out-Null
$oldOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "$oldOptions -Djdk.net.unixdomain.tmpdir=`"$($socketTemp.Replace('\','/'))`"".Trim()
Push-Location $project
try {
    $variant = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
    & .\gradlew.bat --no-daemon $variant
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
} finally {
    Pop-Location
    $env:JAVA_TOOL_OPTIONS = $oldOptions
}
