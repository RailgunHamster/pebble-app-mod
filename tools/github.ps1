[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApiPath,
    [ValidateSet('GET', 'POST', 'PATCH', 'DELETE')][string]$Method = 'GET',
    [string]$JsonFile,
    [ValidateSet('application/vnd.github+json', 'application/octet-stream')][string]$Accept = 'application/vnd.github+json',
    [string]$OutFile
)
$ErrorActionPreference = 'Stop'
if ($ApiPath -match '^[a-z]+:' -or $ApiPath.Contains('..')) { throw 'Use a GitHub API-relative path' }
$credential = @{}
$inputText = "protocol=https`nhost=github.com`nusername=RailgunHamster`n`n"
$entries = $inputText | git -c credential.interactive=never credential fill 2>$null
if ($LASTEXITCODE -ne 0) { throw 'No noninteractive GitHub credential is available' }
foreach ($entry in $entries) {
    $key, $value = $entry -split '=', 2
    $credential[$key] = $value
}
if (-not $credential.password) { throw 'GitHub credential has no access token' }
$request = @{
    Uri = 'https://api.github.com/' + $ApiPath.TrimStart('/')
    Method = $Method
    Headers = @{
        Authorization = 'Bearer ' + $credential.password
        Accept = $Accept
        'X-GitHub-Api-Version' = '2022-11-28'
    }
}
$proxy = (& git config --get http.proxy 2>$null | Out-String).Trim()
if ($proxy) { $request.Proxy = $proxy }
if ($JsonFile) {
    $request.Body = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $JsonFile))
    $request.ContentType = 'application/json'
}
try {
    if ($OutFile) {
        Invoke-WebRequest @request -OutFile $OutFile
        Write-Output 'Downloaded GitHub response'
        return
    }
    $response = Invoke-RestMethod @request
    $response | ConvertTo-Json -Depth 30
}
finally {
    $request.Headers.Remove('Authorization')
    $credential.Clear()
}
