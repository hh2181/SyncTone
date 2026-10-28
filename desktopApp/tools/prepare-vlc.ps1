$ErrorActionPreference = 'Stop'
$env:PSModulePath = (Join-Path $PSHOME 'Modules') + [IO.Path]::PathSeparator + $env:PSModulePath
$taskVersion = '3.0.24'
$taskChecksum = 'fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRuntime = Join-Path $taskRoot 'runtime/windows-x64/vlc'
$taskCache = Join-Path $taskRoot 'build/downloads'
$taskArchive = Join-Path $taskCache "vlc-$taskVersion-win64.zip"
$taskBase = "https://download.videolan.org/pub/videolan/vlc/$taskVersion/win64"

New-Item -ItemType Directory -Force -Path $taskCache | Out-Null
if ((Test-Path -LiteralPath (Join-Path $taskRuntime 'libvlc.dll')) -and
    (Test-Path -LiteralPath (Join-Path $taskRuntime 'COPYING.txt')) -and
    (Test-Path -LiteralPath (Join-Path $taskRuntime 'runtime.sha256')) -and
    ((Get-Content -LiteralPath (Join-Path $taskRuntime 'runtime.sha256') -Raw).Trim() -eq $taskChecksum)) {
    Write-Output "VLC $taskVersion runtime ready."
    exit 0
}
if (!(Test-Path -LiteralPath $taskArchive) -or
    (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskChecksum) {
    Invoke-WebRequest "$taskBase/vlc-$taskVersion-win64.zip" -OutFile $taskArchive -UseBasicParsing
}
if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskChecksum) {
    throw 'VLC SHA-256 verification failed.'
}
$taskExtract = Join-Path $taskCache 'extracted'
Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskExtract -Force
$taskSource = Join-Path $taskExtract "vlc-$taskVersion"
New-Item -ItemType Directory -Force -Path $taskRuntime | Out-Null
Get-ChildItem -LiteralPath $taskSource -Filter '*.dll' | Copy-Item -Destination $taskRuntime -Force
foreach ($taskItem in @('plugins', 'lua', 'locale', 'COPYING.txt', 'THANKS.txt', 'AUTHORS.txt', 'NEWS.txt', 'README.txt')) {
    $taskPath = Join-Path $taskSource $taskItem
    if (Test-Path -LiteralPath $taskPath) { Copy-Item -LiteralPath $taskPath -Destination $taskRuntime -Recurse -Force }
}
$taskChecksum | Set-Content -LiteralPath (Join-Path $taskRuntime 'runtime.sha256') -Encoding ascii
Write-Output "VLC $taskVersion verified and prepared at $taskRuntime"
