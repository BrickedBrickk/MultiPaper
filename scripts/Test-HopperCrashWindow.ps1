param(
    [Parameter(Mandatory = $true)]
    [string]$FixtureRoot,
    [Parameter(Mandatory = $true)]
    [string]$RconPassword,
    [string]$WorldName = 'fresh_release_smoke_world',
    [int]$BackendARconPort = 26511,
    [int]$BackendBRconPort = 26512,
    [int]$SourceX = 2047,
    [int]$Y = 80,
    [int]$Z = 50000,
    [int]$ItemCount = 8,
    [int]$TimeoutSeconds = 30,
    [string]$JavaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [string]$ExpectedPaperclipSha256 = 'A664E4F2956DC2BBE1B36E926E91B963ED873C6216F2A4FBA9F0D8973E0CCD68',
    [string]$ExpectedMasterSha256 = 'EE82CDDE9E6D6AB24E69BBFA19AF9AB4249B442B2E585743C7A9F8F292FAFDB2',
    [int]$BackendAGamePort = 26501,
    [string]$ProxyGateFile,
    [string]$ProxyMarkerFile,
    [string]$ProxyConfigBackup,
    [switch]$QueryOnly,
    [switch]$SaveAndStop,
    [switch]$CrashAndRecover,
    [switch]$ProxyCrashAndRecover
)

$ErrorActionPreference = 'Stop'
$resolvedFixture = (Resolve-Path -LiteralPath $FixtureRoot).Path
$WorldName = $WorldName.Trim()
if ($WorldName -notmatch '^[A-Za-z0-9_-]+$') { throw "Invalid disposable world name: $WorldName" }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $resolvedFixture) -notmatch '^multipaper-26\.3-') {
    throw "Refusing to run destructive crash probe outside a disposable MultiPaper temp fixture: $resolvedFixture"
}
if ($SourceX % 16 -ne 15) {
    throw 'SourceX must be the last block of a chunk so the destination is in the adjacent chunk.'
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }
$serverJarPath = Join-Path $resolvedFixture 'backend-a\server.jar'
$masterJarPath = Join-Path $resolvedFixture 'master\master.jar'
$paperclipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $serverJarPath).Hash
$masterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $masterJarPath).Hash
if ($paperclipHash -ne $ExpectedPaperclipSha256 -or $masterHash -ne $ExpectedMasterSha256) {
    throw "Fixture JARs do not match the expected pair. Paperclip=$paperclipHash Master=$masterHash"
}
if ($ProxyCrashAndRecover) {
    foreach ($file in @($ProxyGateFile, $ProxyMarkerFile, $ProxyConfigBackup)) {
        if ([string]::IsNullOrWhiteSpace($file)) { throw 'Proxy crash mode requires gate, marker, and config backup paths.' }
        $fullPath = [IO.Path]::GetFullPath($file)
        if (-not $fullPath.StartsWith($resolvedFixture.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Proxy crash mode files must stay inside the disposable fixture: $fullPath"
        }
    }
    if (-not (Test-Path -LiteralPath $ProxyConfigBackup -PathType Leaf)) { throw "Missing disposable config backup: $ProxyConfigBackup" }
    if (Test-Path -LiteralPath $ProxyMarkerFile) { throw "Refusing to overwrite existing proxy marker: $ProxyMarkerFile" }
    if (Test-Path -LiteralPath $ProxyGateFile) { throw "Refusing to overwrite existing proxy gate: $ProxyGateFile" }
}

$passwordBytes = [Text.Encoding]::UTF8.GetBytes($RconPassword)
function Read-RconPacket([IO.Stream]$Stream) {
    $lengthBytes = [byte[]]::new(4)
    $offset = 0
    while ($offset -lt 4) {
        $read = $Stream.Read($lengthBytes, $offset, 4 - $offset)
        if ($read -eq 0) { throw 'RCON connection closed while reading packet length.' }
        $offset += $read
    }

    $length = [BitConverter]::ToInt32($lengthBytes, 0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length $length." }
    $body = [byte[]]::new($length)
    $offset = 0
    while ($offset -lt $length) {
        $read = $Stream.Read($body, $offset, $length - $offset)
        if ($read -eq 0) { throw 'RCON connection closed while reading packet body.' }
        $offset += $read
    }

    [pscustomobject]@{
        Id = [BitConverter]::ToInt32($body, 0)
        Type = [BitConverter]::ToInt32($body, 4)
        Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10)
    }
}

function Send-Rcon([int]$Port, [string]$Command) {
    $client = [Net.Sockets.TcpClient]::new()
    $client.ReceiveTimeout = 10000
    $client.SendTimeout = 10000
    try {
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $requestId = Get-Random -Minimum 10000 -Maximum 1000000000
        foreach ($packet in @(
            @{ Id = $requestId; Type = 3; Bytes = $script:passwordBytes },
            @{ Id = $requestId + 1; Type = 2; Bytes = [Text.Encoding]::UTF8.GetBytes($Command) }
        )) {
            $bodyLength = 10 + $packet.Bytes.Length
            $buffer = [byte[]]::new(4 + $bodyLength)
            [BitConverter]::GetBytes($bodyLength).CopyTo($buffer, 0)
            [BitConverter]::GetBytes([int]$packet.Id).CopyTo($buffer, 4)
            [BitConverter]::GetBytes([int]$packet.Type).CopyTo($buffer, 8)
            $packet.Bytes.CopyTo($buffer, 12)
            $stream.Write($buffer, 0, $buffer.Length)
            $reply = Read-RconPacket $stream
            if ($reply.Id -ne $packet.Id) { throw "Unexpected RCON reply id on port $Port." }
            if ($packet.Type -eq 3 -and $reply.Id -eq -1) { throw "RCON authentication failed on port $Port." }
            if ($packet.Type -eq 2) { return $reply.Text }
        }
    } finally {
        $client.Dispose()
    }
}

function Get-ItemCount([string]$BlockData, [string]$ItemId) {
    $escaped = [regex]::Escape($ItemId)
    $match = [regex]::Match($BlockData, "id: `"$escaped`", count: (\d+)")
    if (-not $match.Success) { return 0 }
    return [int]$match.Groups[1].Value
}

$destinationX = $SourceX + 1
$hopperPowerZ = $Z - 1
$sourceChunkX = [int][Math]::Floor($SourceX / 16.0)
$destinationChunkX = [int][Math]::Floor($destinationX / 16.0)
$chunkZ = [int][Math]::Floor($Z / 16.0)
$sourceRegionX = [int][Math]::Floor($sourceChunkX / 32.0)
$destinationRegionX = [int][Math]::Floor($destinationChunkX / 32.0)
$regionZ = [int][Math]::Floor($chunkZ / 32.0)
if ($sourceRegionX -eq $destinationRegionX) {
    throw 'The adjacent source/destination chunks must be in different region files for this crash cut.'
}

$worldName = $WorldName
$masterWorld = Join-Path $resolvedFixture "master\$worldName\region"
$sourceRegion = Join-Path $masterWorld "r.$sourceRegionX.$regionZ.mca"
$destinationRegion = Join-Path $masterWorld "r.$destinationRegionX.$regionZ.mca"
$sourceCommand = "data get block $SourceX $Y $Z Items"
$destinationCommand = "data get block $destinationX $Y $Z Items"

if ($QueryOnly) {
    Write-Output "Backend A source: $(Send-Rcon $BackendARconPort $sourceCommand)"
    Write-Output "Backend A destination: $(Send-Rcon $BackendARconPort $destinationCommand)"
    Write-Output "Backend B source: $(Send-Rcon $BackendBRconPort $sourceCommand)"
    Write-Output "Backend B destination: $(Send-Rcon $BackendBRconPort $destinationCommand)"
    exit 0
}
if ($SaveAndStop) {
    Write-Output "Backend A save: $(Send-Rcon $BackendARconPort 'save-all flush')"
    Write-Output "Backend B save: $(Send-Rcon $BackendBRconPort 'save-all flush')"
    Write-Output "Backend A stop: $(Send-Rcon $BackendARconPort 'stop')"
    Write-Output "Backend B stop: $(Send-Rcon $BackendBRconPort 'stop')"
    exit 0
}

Send-Rcon $BackendARconPort "forceload add $SourceX $Z" | Out-Null
Send-Rcon $BackendBRconPort "forceload add $destinationX $Z" | Out-Null
Send-Rcon $BackendARconPort "setblock $SourceX $Y $hopperPowerZ minecraft:redstone_block" | Out-Null
Send-Rcon $BackendARconPort "setblock $SourceX $Y $Z minecraft:hopper[facing=east,enabled=false]" | Out-Null
Send-Rcon $BackendBRconPort "setblock $destinationX $Y $Z minecraft:chest" | Out-Null
# Keep the destination chunk loaded as a subscriber on A while B remains its owner.
Send-Rcon $BackendARconPort "forceload add $destinationX $Z" | Out-Null
Send-Rcon $BackendARconPort "item replace block $SourceX $Y $Z container.0 with minecraft:diamond $ItemCount" | Out-Null
Send-Rcon $BackendBRconPort "item replace block $destinationX $Y $Z container.0 with minecraft:air" | Out-Null
Send-Rcon $BackendARconPort 'save-all flush' | Out-Null
Send-Rcon $BackendBRconPort 'save-all flush' | Out-Null
Start-Sleep -Seconds 2

$sourceFileBefore = if (Test-Path -LiteralPath $sourceRegion) { (Get-Item -LiteralPath $sourceRegion).LastWriteTimeUtc } else { [DateTime]::MinValue }
$destinationFileBefore = if (Test-Path -LiteralPath $destinationRegion) { (Get-Item -LiteralPath $destinationRegion).LastWriteTimeUtc } else { [DateTime]::MinValue }
$initialSource = Send-Rcon $BackendARconPort $sourceCommand
$initialDestination = Send-Rcon $BackendBRconPort $destinationCommand
if ((Get-ItemCount $initialSource 'minecraft:diamond') -ne $ItemCount -or (Get-ItemCount $initialDestination 'minecraft:diamond') -ne 0) {
    throw "Initial inventory state is not $ItemCount/0. Source='$initialSource'; destination='$initialDestination'."
}
Send-Rcon $BackendARconPort "setblock $SourceX $Y $hopperPowerZ minecraft:air" | Out-Null
Send-Rcon $BackendARconPort "setblock $SourceX $Y $Z minecraft:hopper[facing=east,enabled=true]" | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 100
    $sourceData = Send-Rcon $BackendARconPort $sourceCommand
    $destinationData = Send-Rcon $BackendARconPort $destinationCommand
    $sourceCount = Get-ItemCount $sourceData 'minecraft:diamond'
    $destinationCount = Get-ItemCount $destinationData 'minecraft:diamond'
} while (($sourceCount -ne 0 -or $destinationCount -ne $ItemCount) -and [DateTime]::UtcNow -lt $deadline)
if ($sourceCount -ne 0 -or $destinationCount -ne $ItemCount) {
    throw "Transfer did not complete with exact conservation. Source=$sourceCount destination=$destinationCount."
}

Write-Output "Transfer completed: source=$sourceCount destination=$destinationCount."
Write-Output "Source chunk $sourceChunkX,$chunkZ is in r.$sourceRegionX.$regionZ.mca; destination chunk $destinationChunkX,$chunkZ is in r.$destinationRegionX.$regionZ.mca."
Write-Output "Region mtimes before chunk unload: source=$sourceFileBefore destination=$destinationFileBefore"
if ($ProxyCrashAndRecover) {
    $sourceFileTime = (Get-Item -LiteralPath $sourceRegion).LastWriteTimeUtc.ToFileTimeUtc()
    $destinationFileTime = (Get-Item -LiteralPath $destinationRegion).LastWriteTimeUtc.ToFileTimeUtc()
    $unixEpochFileTime = 116444736000000000L
    $gate = @{
        source_mtime_ns = [long](($sourceFileTime - $unixEpochFileTime) * 100L)
        destination_mtime_ns = [long](($destinationFileTime - $unixEpochFileTime) * 100L)
    } | ConvertTo-Json
    Set-Content -LiteralPath $ProxyGateFile -Value $gate -Encoding ascii
    $parsedGate = $gate | ConvertFrom-Json
    Write-Output "Armed Master write proxy after transfer: source=$($parsedGate.source_mtime_ns) destination=$($parsedGate.destination_mtime_ns)."
}
Send-Rcon $BackendARconPort "forceload remove $SourceX $Z" | Out-Null
Send-Rcon $BackendARconPort "forceload remove $destinationX $Z" | Out-Null
Send-Rcon $BackendBRconPort "forceload remove $destinationX $Z" | Out-Null
Send-Rcon $BackendARconPort "setblock $SourceX $Y $hopperPowerZ minecraft:air" | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 100
    $sourceFileAfter = if (Test-Path -LiteralPath $sourceRegion) { (Get-Item -LiteralPath $sourceRegion).LastWriteTimeUtc } else { [DateTime]::MinValue }
    $destinationFileAfter = if (Test-Path -LiteralPath $destinationRegion) { (Get-Item -LiteralPath $destinationRegion).LastWriteTimeUtc } else { [DateTime]::MinValue }
} while (($sourceFileAfter -le $sourceFileBefore -and $destinationFileAfter -le $destinationFileBefore) -and [DateTime]::UtcNow -lt $deadline)
if ($sourceFileAfter -le $sourceFileBefore -and $destinationFileAfter -le $destinationFileBefore) {
    throw 'Neither region file changed after removing all force-load tickets; no crash cut was established.'
}
Write-Output "Region mtimes after first observed chunk save: source=$sourceFileAfter destination=$destinationFileAfter"
if ($ProxyCrashAndRecover) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-Path -LiteralPath $ProxyMarkerFile) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 10 }
    if (-not (Test-Path -LiteralPath $ProxyMarkerFile)) { throw 'The Master write proxy did not establish the withheld second-write crash cut.' }
    $proxyEvent = Get-Content -LiteralPath $ProxyMarkerFile -Raw | ConvertFrom-Json
    Write-Output "Proxy cut: first persisted=$($proxyEvent.first_persisted.chunk) second dropped=$($proxyEvent.second_dropped.chunk)."
    if ($proxyEvent.first_persisted.chunk -eq $proxyEvent.second_dropped.chunk) { throw 'Proxy reported the same chunk as both persisted and dropped.' }
}
if (-not $CrashAndRecover) {
    Write-Output 'CRASH CUT READY: use -CrashAndRecover to stop Backend A at this point, restart it, and verify recovery.'
    exit 0
}

if (($sourceFileAfter -gt $sourceFileBefore) -eq ($destinationFileAfter -gt $destinationFileBefore)) {
    throw 'Both region files changed before the probe observed the first save; this run did not create an asymmetric persistence cut.'
}

$rconListener = Get-NetTCPConnection -State Listen -LocalPort $BackendARconPort -ErrorAction SilentlyContinue | Select-Object -First 1
$gameListener = Get-NetTCPConnection -State Listen -LocalPort $BackendAGamePort -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ProxyCrashAndRecover) {
    $stopDeadline = [DateTime]::UtcNow.AddSeconds(10)
    while (($rconListener -or $gameListener) -and [DateTime]::UtcNow -lt $stopDeadline) {
        Start-Sleep -Milliseconds 100
        $rconListener = Get-NetTCPConnection -State Listen -LocalPort $BackendARconPort -ErrorAction SilentlyContinue | Select-Object -First 1
        $gameListener = Get-NetTCPConnection -State Listen -LocalPort $BackendAGamePort -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($rconListener -or $gameListener) { throw 'Backend A is still listening after the proxy reported a forced stop.' }
    Copy-Item -LiteralPath $ProxyConfigBackup -Destination (Join-Path $resolvedFixture 'backend-a\multipaper.yml') -Force
    $proxyDeadline = [DateTime]::UtcNow.AddSeconds(10)
    while ((Get-NetTCPConnection -State Listen -LocalPort 26498 -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $proxyDeadline) { Start-Sleep -Milliseconds 100 }
} else {
    if (-not $rconListener -or -not $gameListener -or $rconListener.OwningProcess -ne $gameListener.OwningProcess) {
        throw 'Backend A RCON and game ports do not belong to the same process.'
    }
    $backendAProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($gameListener.OwningProcess)"
    if ($backendAProcess.Name -ne 'java.exe' -or $backendAProcess.CommandLine -notmatch '-jar server.jar nogui') {
        throw "Refusing to crash unexpected Backend A process: $($backendAProcess.CommandLine)"
    }
    Write-Output "Stopping disposable Backend A PID $($backendAProcess.ProcessId) after the first region write."
    Stop-Process -Id $backendAProcess.ProcessId -Force
    Start-Sleep -Milliseconds 300
}
$destinationFileAfterCrash = if (Test-Path -LiteralPath $destinationRegion) { (Get-Item -LiteralPath $destinationRegion).LastWriteTimeUtc } else { [DateTime]::MinValue }
Write-Output "Destination region timestamp after the process stopped: $destinationFileAfterCrash"
$pendingTransactions = Join-Path $resolvedFixture "hopper-crashcut-pending-transactions"
$transactionDirectory = Join-Path (Split-Path -Parent $masterWorld) 'multipaper-container-transactions'
if (Test-Path -LiteralPath $transactionDirectory) {
    Copy-Item -LiteralPath $transactionDirectory -Destination $pendingTransactions -Recurse -Force
    Write-Output "Captured pending transaction files in $pendingTransactions"
}

$restartLog = Join-Path $resolvedFixture 'hopper-crashcut-recovered-a.out.log'
$restartErrorLog = Join-Path $resolvedFixture 'hopper-crashcut-recovered-a.err.log'
$null = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $resolvedFixture 'backend-a') -ArgumentList @('-jar', 'server.jar', 'nogui') -WindowStyle Hidden -RedirectStandardOutput $restartLog -RedirectStandardError $restartErrorLog -PassThru
$deadline = [DateTime]::UtcNow.AddMinutes(3)
do {
    Start-Sleep -Seconds 1
    $serverReady = Select-String -LiteralPath $restartLog -SimpleMatch 'Done (' -Quiet -ErrorAction SilentlyContinue
} while (-not $serverReady -and [DateTime]::UtcNow -lt $deadline)
if (-not $serverReady) { throw 'Backend A failed to reach Done after the crash restart.' }

Send-Rcon $BackendARconPort "forceload add $SourceX $Z" | Out-Null
Send-Rcon $BackendARconPort "forceload add $destinationX $Z" | Out-Null
Send-Rcon $BackendBRconPort "forceload add $destinationX $Z" | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 100
    $sourceData = Send-Rcon $BackendARconPort $sourceCommand
    # Destination remains owned by Backend B; read its authoritative inventory after A restarts.
    $destinationData = Send-Rcon $BackendBRconPort $destinationCommand
    $sourceCount = Get-ItemCount $sourceData 'minecraft:diamond'
    $destinationCount = Get-ItemCount $destinationData 'minecraft:diamond'
} while (($sourceCount + $destinationCount -ne $ItemCount) -and [DateTime]::UtcNow -lt $deadline)
Write-Output "Recovered inventory counts on chunk owners: source(A)=$sourceCount destination(B)=$destinationCount total=$($sourceCount + $destinationCount)."
if ($sourceCount + $destinationCount -ne $ItemCount) {
    throw "Hopper crash recovery changed the item total from $ItemCount to $($sourceCount + $destinationCount). Source='$sourceData'; destination='$destinationData'."
}
Write-Output 'PASS: item count was conserved across the observed asymmetric region-save crash cut.'
Send-Rcon $BackendARconPort "forceload remove $SourceX $Z" | Out-Null
Send-Rcon $BackendARconPort "forceload remove $destinationX $Z" | Out-Null
Send-Rcon $BackendARconPort 'save-all flush' | Out-Null
Send-Rcon $BackendBRconPort 'save-all flush' | Out-Null
Send-Rcon $BackendARconPort 'stop' | Out-Null
Send-Rcon $BackendBRconPort 'stop' | Out-Null
Write-Output 'Both disposable backends were saved and stopped after recovery verification.'
