#requires -Version 7
[CmdletBinding()]
param(
    [string]$Adb = 'adb',
    [int]$ServerPort = 5037,
    [string]$Serial,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\analysis\device')
)
$ErrorActionPreference = 'Stop'
$env:ANDROID_ADB_SERVER_PORT = "$ServerPort"
$package = 'com.coloros.ocrscanner'
if (-not $Serial) {
    $devices = @(& $Adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Exactly one authorized ADB device is required, or pass -Serial.' }
    $Serial = $devices[0]
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$apkPaths = @(& $Adb -s $Serial shell pm path $package)
if ($LASTEXITCODE -ne 0 -or -not ($apkPaths -match '^package:')) { throw 'Scanner package not found.' }
$apkPaths | Set-Content (Join-Path $OutputDirectory 'apk-paths.txt') -Encoding utf8
foreach ($entry in $apkPaths) {
    if ($entry -notmatch '^package:(.+)$') { continue }
    $remote = $Matches[1].Trim()
    $file = ($remote -split '/')[-1]
    & $Adb -s $Serial pull $remote (Join-Path $OutputDirectory $file)
    if ($LASTEXITCODE -ne 0) { throw "APK pull failed: $file" }
}
& $Adb -s $Serial shell dumpsys package $package | Set-Content (Join-Path $OutputDirectory 'package.txt') -Encoding utf8
& $Adb -s $Serial shell getprop ro.build.fingerprint | Set-Content (Join-Path $OutputDirectory 'build-fingerprint.txt') -Encoding utf8
Get-ChildItem -LiteralPath $OutputDirectory -Filter '*.apk' | Get-FileHash -Algorithm SHA256 |
    Select-Object Hash,Path | ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'apk-sha256.json') -Encoding utf8
