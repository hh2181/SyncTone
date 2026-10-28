param([string]$JdkHome)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (!$JdkHome) {
    $taskCandidates = @(Get-ChildItem -LiteralPath 'C:/Program Files/Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue)
    if ($taskCandidates.Count -gt 0) { $JdkHome = $taskCandidates[0].FullName }
    else { $JdkHome = $env:JAVA_HOME }
}
if (!$JdkHome -or !(Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
    throw 'Specify a JDK 17 installation using -JdkHome.'
}
$taskPreviousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkHome
    Push-Location $taskRoot
    try {
        & ./gradlew.bat :desktopApp:run --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Desktop exited with code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $taskPreviousJava }
