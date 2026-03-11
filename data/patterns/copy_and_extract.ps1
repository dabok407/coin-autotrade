$ErrorActionPreference = "Stop"

# Find the zip file by searching D: drive
$found = Get-ChildItem -Path "D:\" -Filter "upload_file.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($found) {
    Write-Output "Found: $($found.FullName) ($($found.Length) bytes)"
    $dstDir = "C:\workspace\upbit-autotrade-java8\upbit-autotrade\data\patterns"
    $dstZip = Join-Path $dstDir "patterns.zip"
    $extractDir = Join-Path $dstDir "extracted"

    Copy-Item -LiteralPath $found.FullName -Destination $dstZip -Force
    Write-Output "Copied OK: $((Get-Item $dstZip).Length) bytes"

    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -LiteralPath $dstZip -DestinationPath $extractDir -Force
    Write-Output "Extracted OK"

    Get-ChildItem -Path $extractDir -Recurse | ForEach-Object { Write-Output "$($_.FullName) ($($_.Length) bytes)" }
} else {
    Write-Output "upload_file.zip NOT FOUND on D:"
}
Write-Output "DONE"
