param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$eqProject = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$eqDistribution = (Resolve-Path -LiteralPath (Join-Path $eqProject 'desktopApp/build/compose/binaries/main/app/SyncTone')).Path
$eqProbe = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $eqProbe) { throw 'Use a new isolated probe directory.' }
$eqApplication = Join-Path $eqProbe 'SyncTone'
New-Item -ItemType Directory -Path $eqApplication | Out-Null
Get-ChildItem -LiteralPath $eqDistribution -File -Recurse | ForEach-Object {
    $eqRelative = $_.FullName.Substring($eqDistribution.Length + 1)
    if ($eqRelative -match '^(data|portable)\\') { return }
    $eqTarget = Join-Path $eqApplication $eqRelative
    New-Item -ItemType Directory -Path (Split-Path -Parent $eqTarget) -Force | Out-Null
    if ($eqRelative -eq 'app\SyncTone.cfg') { Copy-Item -LiteralPath $_.FullName -Destination $eqTarget }
    else { New-Item -ItemType HardLink -Path $eqTarget -Target $_.FullName | Out-Null }
}
$eqConfig = Join-Path $eqApplication 'app/SyncTone.cfg'
$eqClasses = Join-Path $eqProject 'desktopApp/build/classes/kotlin/desktop/test'
$eqText = [IO.File]::ReadAllText($eqConfig).Replace('app.mainclass=dev.salt.desktop.MainKt', "app.mainclass=dev.salt.desktop.EqualizerPackageSmokeKt`napp.classpath=$eqClasses")
[IO.File]::WriteAllText($eqConfig, $eqText, [Text.UTF8Encoding]::new($false))
$eqPreviousOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = '-Dsynctone.eq.probe.root="' + $eqProbe + '" -Dsynctone.eq.probe.reference="' + (Join-Path $eqProject 'app/src/main/assets/reference.mp3') + '"'
    $eqProcess = Start-Process -FilePath (Join-Path $eqApplication 'SyncTone.exe') -WorkingDirectory $eqProbe -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $eqProbe 'stdout.log') -RedirectStandardError (Join-Path $eqProbe 'stderr.log')
    if (!$eqProcess.WaitForExit(30000)) { Stop-Process -Id $eqProcess.Id -Force; throw 'Packaged EQ probe timed out.' }
    if ($eqProcess.ExitCode -ne 0) { Get-Content -LiteralPath (Join-Path $eqProbe 'stderr.log') -Tail 30; throw 'Packaged EQ probe failed.' }
    Get-Content -LiteralPath (Join-Path $eqProbe 'verification.json')
} finally { $env:JAVA_TOOL_OPTIONS = $eqPreviousOptions }
