param(
    [Parameter(Mandatory = $true)][string]$BaselineDirectory,
    [Parameter(Mandatory = $true)][string]$CandidateDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$taskBaseline = (Resolve-Path -LiteralPath $BaselineDirectory).Path
$taskCandidate = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$taskResults = foreach ($taskMode in @('mini', 'full')) {
    foreach ($taskTheme in @('light', 'dark')) {
        $taskName = "seek-$taskMode-$taskTheme.png"
        $taskOld = [Drawing.Bitmap]::new((Join-Path $taskBaseline $taskName))
        $taskNew = [Drawing.Bitmap]::new((Join-Path $taskCandidate $taskName))
        try {
            if ($taskOld.Width -ne 1280 -or $taskNew.Width -ne 1280 -or $taskOld.Height -ne 820 -or $taskNew.Height -ne 820) {
                throw 'Use the 1280x820 ProgressSnapshotSmoke fixtures.'
            }
            $taskLeft = if ($taskMode -eq 'mini') { 452 } else { 50 }
            $taskRight = if ($taskMode -eq 'mini') { 1034 } else { 1230 }
            $taskTop = if ($taskMode -eq 'mini') { 757 } else { 694 }
            $taskChanged = 0
            for ($taskY = $taskTop; $taskY -lt $taskTop + 32; $taskY++) {
                for ($taskX = $taskLeft; $taskX -lt $taskRight; $taskX++) {
                    if ($taskOld.GetPixel($taskX, $taskY).ToArgb() -ne $taskNew.GetPixel($taskX, $taskY).ToArgb()) { $taskChanged++ }
                }
            }
            [pscustomobject]@{ Image = $taskName; ChangedPixels = $taskChanged; Region = @($taskLeft, $taskTop, $taskRight, ($taskTop + 32)) }
        } finally { $taskOld.Dispose(); $taskNew.Dispose() }
    }
}
$taskResults | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskCandidate 'verification.json') -Encoding utf8
$taskResults | ConvertTo-Json -Depth 4
if (@($taskResults | Where-Object { $_.ChangedPixels -ne 0 }).Count) { throw 'Playback progress changed.' }
