[CmdletBinding()]
param(
    [Parameter(Mandatory)][long]$RunId,
    [switch]$Publish,
    [string]$SshHost = 'tc',
    [string]$PrivateKey = (Join-Path $PSScriptRoot '..\..\pebble-signing\apk-transfer-private.pem'),
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
if ($SshHost -notmatch '^[A-Za-z0-9._-]+$') { throw 'Use a configured SSH host alias' }
$repo = 'repos/RailgunHamster/pebble-app-mod'
function GitHub([string]$Path) {
    & (Join-Path $PSScriptRoot 'github.ps1') -ApiPath $Path | ConvertFrom-Json
}
$run = GitHub "$repo/actions/runs/$RunId"
$artifact = (GitHub "$repo/actions/runs/$RunId/artifacts").artifacts |
    Where-Object name -eq "private-apk-$($run.run_number)" | Select-Object -First 1
if (-not $artifact) { throw 'Encrypted APK artifact is not ready yet' }
$local = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.local\transfer-$RunId"))
New-Item -ItemType Directory -Path $local -Force | Out-Null
$archivePath = Join-Path $local 'artifact.zip'
if (-not (Test-Path -LiteralPath $archivePath)) {
    & (Join-Path $PSScriptRoot 'github.ps1') -ApiPath "$repo/actions/artifacts/$($artifact.id)/zip" `
        -Accept application/octet-stream -OutFile ($archivePath + '.download') | Out-Null
    Move-Item -LiteralPath ($archivePath + '.download') -Destination $archivePath -Force
}
if ($artifact.digest -and ('sha256:' + (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLower()) -ne $artifact.digest) {
    throw 'Encrypted artifact digest mismatch'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if ($archive.Entries.Count -ne 2) { throw 'Unexpected artifact members' }
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notin @('envelope.json', 'apk.encrypted') -or $entry.Length -gt 410MB) {
            throw 'Unexpected encrypted artifact contents'
        }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $local $entry.FullName), $true)
    }
} finally { $archive.Dispose() }
$apk = Join-Path $local 'pebble-claw.apk'
$metadata = & $Python -X utf8 (Join-Path $PSScriptRoot 'private-build.py') decrypt $local $PrivateKey $apk | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $metadata.build -ne $run.run_number) { throw 'Private APK authentication failed' }
$metadataFile = Join-Path $local 'metadata.json'
$metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataFile -Encoding utf8NoBOM
if ($Publish) {
    $remote = "/home/ubuntu/.cache/pebble-mod-transfer/$RunId"
    & ssh -n -o BatchMode=yes $SshHost "umask 077; mkdir -p '$remote'"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare the private SSH staging directory' }
    & scp -B $apk $metadataFile (Join-Path $PSScriptRoot 'publish-private-apk.py') "${SshHost}:$remote/"
    if ($LASTEXITCODE -ne 0) { throw 'Private APK copy failed' }
    & ssh -n -o BatchMode=yes $SshHost "sudo -n python3 '$remote/publish-private-apk.py' '$remote/pebble-claw.apk' '$remote/metadata.json' /opt/clawfeed/app"
    if ($LASTEXITCODE -ne 0) { throw 'Private APK publication failed' }
}
Write-Output "Verified APK: $apk"
