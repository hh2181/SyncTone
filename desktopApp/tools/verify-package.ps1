param([string]$OutputDirectory, [string]$DistributionDirectory)
$ErrorActionPreference = 'Stop'
$taskProject = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskDistribution = if ($DistributionDirectory) { (Resolve-Path -LiteralPath $DistributionDirectory).Path }
    else { Join-Path $taskProject 'desktopApp/build/compose/binaries/main/app/SyncTone' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $taskProject ('desktopApp/build/reports/portable-package-' + [guid]::NewGuid()) }
$taskProbe = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskProbe) { throw 'Use a new probe output directory.' }
New-Item -ItemType Directory -Path $taskProbe | Out-Null
$taskApplication = Join-Path $taskProbe 'SyncTone'
New-Item -ItemType Directory -Path $taskApplication | Out-Null
Get-ChildItem -LiteralPath $taskDistribution -File -Recurse | ForEach-Object {
    $taskRelative = $_.FullName.Substring($taskDistribution.Length + 1)
    if ($taskRelative -match '^(data|portable)\\') { return }
    $taskTarget = Join-Path $taskApplication $taskRelative
    New-Item -ItemType Directory -Path (Split-Path -Parent $taskTarget) -Force | Out-Null
    if ($taskRelative -eq 'app\SyncTone.cfg') {
        Copy-Item -LiteralPath $_.FullName -Destination $taskTarget
    } else {
        try { New-Item -ItemType HardLink -Path $taskTarget -Target $_.FullName -ErrorAction Stop | Out-Null }
        catch { Copy-Item -LiteralPath $_.FullName -Destination $taskTarget }
    }
}
$taskConfig = Join-Path $taskApplication 'app/SyncTone.cfg'
$taskClasses = Join-Path $taskProject 'desktopApp/build/classes/kotlin/desktop/test'
$taskConfigLines = [IO.File]::ReadAllLines($taskConfig) | ForEach-Object {
    $_.Replace('app.mainclass=dev.salt.desktop.MainKt', "app.mainclass=dev.salt.desktop.LibraryPackageSmokeKt`napp.classpath=$taskClasses")
}
[IO.File]::WriteAllLines($taskConfig, $taskConfigLines, [Text.UTF8Encoding]::new($false))
$taskMusic = New-Item -ItemType Directory -Path (Join-Path $taskProbe 'music')
$taskReference = Join-Path $taskProject 'app/src/main/assets/reference.mp3'
Copy-Item -LiteralPath $taskReference -Destination (Join-Path $taskMusic 'song.mp3')
Copy-Item -LiteralPath $taskReference -Destination (Join-Path $taskMusic 'duplicate.mp3')
Copy-Item -LiteralPath (Join-Path $taskProject 'desktopApp/build/reports/playback-smoke/测试 FLAC.flac') -Destination (Join-Path $taskMusic 'short.flac')
[IO.File]::WriteAllText((Join-Path $taskMusic 'broken.flac'), 'broken test fixture')
$taskBlocked = New-Item -ItemType Directory -Path (Join-Path $taskProbe 'blocked')
[IO.File]::WriteAllText((Join-Path $taskBlocked 'SyncTone'), 'test fixture: forbid AppData storage')
[IO.File]::WriteAllText((Join-Path $taskBlocked 'vlc'), 'test fixture: forbid VLC AppData storage')
$taskOldLocal = $env:LOCALAPPDATA
$taskOldRoaming = $env:APPDATA
$taskOldOptions = $env:JAVA_TOOL_OPTIONS

function Get-StorageSnapshot {
    foreach ($taskRoot in @((Join-Path $taskOldLocal 'SyncTone'), (Join-Path $taskOldRoaming 'SyncTone'), (Join-Path $taskOldRoaming 'vlc'))) {
        if (Test-Path -LiteralPath $taskRoot) {
            Get-Item -LiteralPath $taskRoot | ForEach-Object { "DIR $($_.FullName) $($_.LastWriteTimeUtc.Ticks)" }
            Get-ChildItem -LiteralPath $taskRoot -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
                "$($_.FullName) $($_.Length) $($_.LastWriteTimeUtc.Ticks) $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
            }
        } else { "ABSENT $taskRoot" }
    }
}

function Invoke-PackageProbe([string]$taskExe, [string]$taskLogName) {
    $taskProcess = Start-Process -FilePath $taskExe -WorkingDirectory $taskProbe -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskProbe "$taskLogName-stdout.log") -RedirectStandardError (Join-Path $taskProbe "$taskLogName-stderr.log")
    if (!$taskProcess.WaitForExit(90000)) {
        & taskkill.exe /PID $taskProcess.Id /T /F | Out-Null
        throw "Packaged probe timed out. Logs: $taskProbe"
    }
    if ($taskProcess.ExitCode -ne 0) {
        Get-Content -LiteralPath (Join-Path $taskProbe "$taskLogName-stderr.log") -Tail 40
        throw "Packaged probe failed. Logs: $taskProbe"
    }
}

$taskBefore = @(Get-StorageSnapshot)
try {
    $env:LOCALAPPDATA = $taskBlocked.FullName
    $env:APPDATA = $taskBlocked.FullName
    $env:JAVA_TOOL_OPTIONS = '-Dsynctone.probe.root="' + $taskProbe + '"'
    Invoke-PackageProbe (Join-Path $taskApplication 'SyncTone.exe') 'initial'
    if (!(Test-Path -LiteralPath (Join-Path $taskProbe 'verification.json'))) { throw 'Missing packaged verification result.' }
    # Close the first process, move the whole app and start a fresh JVM from a different working directory.
    $taskMoved = Join-Path $taskProbe 'Moved SyncTone'
    foreach ($taskMoveTarget in @($taskApplication, $taskMoved)) {
        if (![IO.Path]::GetFullPath($taskMoveTarget).StartsWith($taskProbe + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Package move must stay inside the isolated probe directory.'
        }
    }
    Move-Item -LiteralPath $taskApplication -Destination $taskMoved
    $taskMovedConfig = Join-Path $taskMoved 'app/SyncTone.cfg'
    $taskRestoreConfig = [IO.File]::ReadAllText($taskMovedConfig).Replace('dev.salt.desktop.LibraryPackageSmokeKt', 'dev.salt.desktop.PortableRestoreSmokeKt')
    [IO.File]::WriteAllText($taskMovedConfig, $taskRestoreConfig, [Text.UTF8Encoding]::new($false))
    Invoke-PackageProbe (Join-Path $taskMoved 'SyncTone.exe') 'relocated'
    if (!(Test-Path -LiteralPath (Join-Path $taskProbe 'relocation-verification.json'))) { throw 'Missing relocation result.' }
    if (($taskBefore -join "`n") -ne (@(Get-StorageSnapshot) -join "`n")) { throw 'Application data was changed outside the portable folder.' }
    if ((Get-ChildItem -LiteralPath $taskBlocked -Recurse -Force).Count -ne 2) { throw 'Unexpected AppData files.' }
    [IO.File]::WriteAllText((Join-Path $taskProbe 'storage-verification.json'), '{"passed":true,"appDataUnchanged":true,"roamingUnchanged":true,"vlcUnchanged":true,"portableTemp":true,"relocation":true}')
    Get-Content -LiteralPath (Join-Path $taskProbe 'verification.json')
    Get-Content -LiteralPath (Join-Path $taskProbe 'core-verification.json')
    Get-Content -LiteralPath (Join-Path $taskProbe 'relocation-verification.json')
    Get-Content -LiteralPath (Join-Path $taskProbe 'storage-verification.json')
    Write-Output "Probe evidence: $taskProbe"
} finally {
    $env:LOCALAPPDATA = $taskOldLocal
    $env:APPDATA = $taskOldRoaming
    $env:JAVA_TOOL_OPTIONS = $taskOldOptions
}
