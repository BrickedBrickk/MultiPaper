param(
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [Parameter(Mandatory = $true)][string]$RconPassword,
    [int]$ProxyPort = 29822,
    [int]$BackendAPort = 29823,
    [int]$BackendBPort = 29824,
    [int]$BackendARconPort = 29831,
    [int]$BackendBRconPort = 29832,
    [ValidateRange(2, 20)][int]$ClientCount = 16,
    [ValidateRange(30, 600)][int]$DurationSeconds = 60
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') {
    throw "Refusing entity persistence test outside a named disposable fixture: $root"
}

$properties = Get-Content -LiteralPath (Join-Path $root 'backend-a/server.properties') -Raw
$worldMatch = [regex]::Match($properties, '(?m)^level-name=(.+)$')
if (-not $worldMatch.Success) { throw 'Could not read fixture world name.' }
$world = $worldMatch.Groups[1].Value.Trim()
$metadata = Get-Content -LiteralPath (Join-Path $root 'fixture-meta.json') -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath (Join-Path $root 'backend-a/server.jar') -Algorithm SHA256).Hash -ne $metadata.paperclip -or
    (Get-FileHash -LiteralPath (Join-Path $root 'master/master.jar') -Algorithm SHA256).Hash -ne $metadata.master) {
    throw 'Fixture JAR hashes do not match fixture metadata.'
}

$readinessDeadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $rconReady = @($BackendARconPort, $BackendBRconPort | ForEach-Object {
        Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue | Select-Object -First 1
    }).Count -eq 2
    $proxyReady = (Get-NetTCPConnection -State Listen -LocalPort $ProxyPort -ErrorAction SilentlyContinue | Select-Object -First 1) -ne $null
    if (-not ($rconReady -and $proxyReady)) { Start-Sleep -Seconds 1 }
} while (-not ($rconReady -and $proxyReady) -and [DateTime]::UtcNow -lt $readinessDeadline)
if (-not ($rconReady -and $proxyReady)) { throw 'Disposable fixture RCON/proxy listeners did not become ready.' }

& (Join-Path $PSScriptRoot 'Test-ProtocolCapacity.ps1') `
    -FixtureRoot $root -RconPassword $RconPassword `
    -ProxyPort $ProxyPort -BackendARconPort $BackendARconPort -BackendBRconPort $BackendBRconPort `
    -ClientCount $ClientCount -DurationSeconds $DurationSeconds -MoveClients -UsernamePrefix 'Ent'

$rcon = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'
foreach ($backend in @(
        @{ Name = 'backend-a'; Rcon = $BackendARconPort; Game = $BackendAPort },
        @{ Name = 'backend-b'; Rcon = $BackendBRconPort; Game = $BackendBPort })) {
    $rconListener = Get-NetTCPConnection -State Listen -LocalPort $backend.Rcon -ErrorAction SilentlyContinue | Select-Object -First 1
    $gameListener = Get-NetTCPConnection -State Listen -LocalPort $backend.Game -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $rconListener -or -not $gameListener -or $rconListener.OwningProcess -ne $gameListener.OwningProcess) {
        throw "$($backend.Name) RCON/game listeners are not owned by the same disposable server process."
    }
    $backendProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($gameListener.OwningProcess)"
    if (-not $backendProcess -or $backendProcess.CommandLine -notmatch '(?i)server\.jar\s+nogui') {
        throw "$($backend.Name) listener is not owned by a Paperclip server process."
    }
    & $rcon -Server 127.0.0.1 -Port $backend.Rcon -Password $RconPassword -Command 'stop' | Out-Null
    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ((Get-Process -Id $backendProcess.ProcessId -ErrorAction SilentlyContinue) -and
        [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Seconds 1 }
    if (Get-Process -Id $backendProcess.ProcessId -ErrorAction SilentlyContinue) {
        throw "$($backend.Name) process did not exit after its save/stop command. Keep Master online and inspect its logs."
    }
    if ((Get-NetTCPConnection -State Listen -LocalPort $backend.Game -ErrorAction SilentlyContinue) -or
        (Get-NetTCPConnection -State Listen -LocalPort $backend.Rcon -ErrorAction SilentlyContinue)) {
        throw "$($backend.Name) game or RCON listener remained open after its process exited."
    }
}

$masterListener = Get-NetTCPConnection -State Listen -LocalPort $metadata.masterPort -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $masterListener) { throw 'Fixture Master is not listening after backend shutdown.' }
$masterProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($masterListener.OwningProcess)"
$masterJarHash = (Get-FileHash -LiteralPath (Join-Path $root 'master/master.jar') -Algorithm SHA256).Hash
if (-not $masterProcess -or $masterProcess.CommandLine -notmatch '(?i)master\.jar' -or
    $masterProcess.CommandLine -notmatch "\b$($metadata.masterPort)\b" -or $masterJarHash -ne $metadata.master) {
    throw 'Master port is not owned by this disposable fixture.'
}
Stop-Process -Id $masterListener.OwningProcess -Force

$scanner = Join-Path $PSScriptRoot 'Scan-McaEntityUuidDuplicates.py'
$python = (Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $python) { $python = 'C:\Users\elibo\AppData\Local\Programs\Python\Python313\python.exe' }
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw 'Python 3 is required for the read-only MCA UUID scan.' }
$entityDirectories = @{
    overworld = Join-Path (Join-Path $root 'master' $world) 'entities'
    nether = Join-Path (Join-Path (Join-Path $root 'master' "${world}_nether") 'DIM-1') 'entities'
    end = Join-Path (Join-Path (Join-Path $root 'master' "${world}_the_end") 'DIM1') 'entities'
}
$scans = @{}
$duplicateCount = 0
$recordCount = 0
$uniqueCount = 0
foreach ($dimension in $entityDirectories.Keys) {
    $directory = $entityDirectories[$dimension]
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
    $scans[$dimension] = (& $python $scanner $directory | Out-String) | ConvertFrom-Json
    $duplicateCount += @($scans[$dimension].duplicateUuids.PSObject.Properties).Count
    $recordCount += [int]$scans[$dimension].entityRecords
    $uniqueCount += [int]$scans[$dimension].uniqueUuids
}
Write-Output "Saved entity records=$recordCount unique UUIDs=$uniqueCount duplicate UUIDs=$duplicateCount"
Write-Output "MCA scan: $(Join-Path $root 'entity-integrity-scan.json')"
$scans | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $root 'entity-integrity-scan.json')
if ($duplicateCount -gt 0) { throw "Persisted entity UUID duplicates remain: $duplicateCount. See entity-integrity-scan.json." }
Write-Output 'PASS: orderly two-backend shutdown left no duplicate entity UUIDs in Master storage.'
