param(
    [int]$Server1RconPort = 26010,
    [int]$Server2RconPort = 26011,
    [int]$TestX = 1000,
    [int]$TestY = 102,
    [int]$TestZ = 1000,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
    [string]$Server1RconPassword,
    [string]$Server2RconPassword,
    [string]$PlayerName,
    [switch]$SkipContainerCheck,
    [switch]$SkipRedstoneAndPistonCheck,
    [switch]$SkipPlayerAndEntityChecks,
    [string]$Server1LogPath,
    [string]$Server2LogPath,
    [int]$TimeoutSeconds = 10
)

# Run this only against disposable worlds: it writes marker blocks, a chest and shulker,
# a piston circuit, and tagged pigs, then removes the entities. Marker blocks
# are intentionally left in place.

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Server1RconPassword)) { $Server1RconPassword = $RconPassword }
if ([string]::IsNullOrWhiteSpace($Server2RconPassword)) { $Server2RconPassword = $RconPassword }
if ([string]::IsNullOrWhiteSpace($Server1RconPassword) -or [string]::IsNullOrWhiteSpace($Server2RconPassword)) {
    throw 'Pass -RconPassword (or both per-server passwords) or set MULTIPAPER_RCON_PASSWORD.'
}

if ([string]::IsNullOrWhiteSpace($Server1LogPath) -ne [string]::IsNullOrWhiteSpace($Server2LogPath)) {
    throw 'Pass both -Server1LogPath and -Server2LogPath to check duplicate entity UUID warnings.'
}

foreach ($logPath in @($Server1LogPath, $Server2LogPath)) {
    if (-not [string]::IsNullOrWhiteSpace($logPath) -and -not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
        throw "Server log not found: $logPath"
    }
}

function Get-DuplicateEntityUuidWarningCount([string]$LogPath) {
    if ([string]::IsNullOrWhiteSpace($LogPath)) { return 0 }
    return (Select-String -LiteralPath $LogPath -SimpleMatch -Pattern 'Entity uuid already exists' | Measure-Object).Count
}

function Assert-NoNewDuplicateEntityUuidWarnings([int[]]$Before) {
    if ([string]::IsNullOrWhiteSpace($Server1LogPath)) { return }
    $after = @(
        (Get-DuplicateEntityUuidWarningCount $Server1LogPath),
        (Get-DuplicateEntityUuidWarningCount $Server2LogPath)
    )
    if ($after[0] -gt $Before[0] -or $after[1] -gt $Before[1]) {
        throw "Duplicate entity UUID warnings increased during the probe: Server 1 $($Before[0]) -> $($after[0]); Server 2 $($Before[1]) -> $($after[1])."
    }
    Write-Output 'PASS: no new duplicate entity UUID warnings on either backend.'
}

$duplicateUuidWarningsBefore = @(
    (Get-DuplicateEntityUuidWarningCount $Server1LogPath),
    (Get-DuplicateEntityUuidWarningCount $Server2LogPath)
)

function Read-RconPacket([System.IO.Stream]$Stream) {
    $lengthBytes = [byte[]]::new(4)
    $read = 0
    while ($read -lt 4) {
        $n = $Stream.Read($lengthBytes, $read, 4 - $read)
        if ($n -eq 0) { throw 'RCON connection closed while reading packet length.' }
        $read += $n
    }
    $length = [BitConverter]::ToInt32($lengthBytes, 0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length: $length" }
    $body = [byte[]]::new($length)
    $read = 0
    while ($read -lt $length) {
        $n = $Stream.Read($body, $read, $length - $read)
        if ($n -eq 0) { throw 'RCON connection closed while reading packet body.' }
        $read += $n
    }
    [pscustomobject]@{
        Id = [BitConverter]::ToInt32($body, 0)
        Type = [BitConverter]::ToInt32($body, 4)
        Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10)
    }
}

function Send-Rcon([int]$Port, [string]$Command) {
    $password = if ($Port -eq $Server1RconPort) { $Server1RconPassword } else { $Server2RconPassword }
    $client = [System.Net.Sockets.TcpClient]::new()
    $client.ReceiveTimeout = $TimeoutSeconds * 1000
    $client.SendTimeout = $TimeoutSeconds * 1000
    try {
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $authId = Get-Random -Minimum 10000 -Maximum 2000000000
        $commandId = $authId + 1
        foreach ($packet in @(
            @{ Id = $authId; Type = 3; Text = $password },
            @{ Id = $commandId; Type = 2; Text = $Command }
        )) {
            $payload = [Text.Encoding]::UTF8.GetBytes([string]$packet.Text)
            $bodyLength = 4 + 4 + $payload.Length + 2
            $buffer = [byte[]]::new(4 + $bodyLength)
            [BitConverter]::GetBytes($bodyLength).CopyTo($buffer, 0)
            [BitConverter]::GetBytes([int]$packet.Id).CopyTo($buffer, 4)
            [BitConverter]::GetBytes([int]$packet.Type).CopyTo($buffer, 8)
            $payload.CopyTo($buffer, 12)
            $stream.Write($buffer, 0, $buffer.Length)
            $reply = Read-RconPacket $stream
            if ($packet.Type -eq 3 -and $reply.Id -ne $authId) {
                throw "RCON authentication failed on port ${Port}."
            }
            if ($packet.Type -eq 2 -and $reply.Id -ne $commandId) {
                throw "Unexpected RCON response id $($reply.Id) on port ${Port}."
            }
            if ($packet.Type -eq 2) { return $reply.Text }
        }
    } finally {
        $client.Dispose()
    }
}

function Assert-Contains([string]$Actual, [string]$Expected, [string]$Message) {
    if ($Actual.IndexOf($Expected, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

function Assert-NotContains([string]$Actual, [string]$Unexpected, [string]$Message) {
    if ($Actual.IndexOf($Unexpected, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "$Message Did not expect '$Unexpected', got '$Actual'."
    }
}

function Get-BlockItemCount([string]$BlockData, [string]$ItemId) {
    $escapedItemId = [regex]::Escape($ItemId)
    $countMatch = [regex]::Match($BlockData, "id: `"$escapedItemId`", count: (\d+)")
    if (-not $countMatch.Success) { return 0 }
    return [int]$countMatch.Groups[1].Value
}

function Test-StickyPistonPull([int]$ControllerPort, [int]$ObserverPort, [int]$PistonX, [int]$PowerX, [int]$PayloadX, [int]$DestinationX, [int]$CircuitY, [int]$CircuitZ, [string]$Direction) {
    foreach ($clearX in $PowerX..$DestinationX) {
        Send-Rcon $ControllerPort "setblock $clearX $CircuitY $CircuitZ minecraft:air" | Out-Null
    }
    $extendedCommand = "execute if block $PistonX $CircuitY $CircuitZ minecraft:sticky_piston[facing=east,extended=true] run help"
    $retractedCommand = "execute if block $PistonX $CircuitY $CircuitZ minecraft:sticky_piston[facing=east,extended=false] run help"
    $destinationCommand = "execute if block $DestinationX $CircuitY $CircuitZ minecraft:redstone_block run help"
    $payloadCommand = "execute if block $PayloadX $CircuitY $CircuitZ minecraft:redstone_block run help"
    $itemCommand = "execute positioned $PayloadX $CircuitY $CircuitZ if entity @e[type=minecraft:item,distance=..6] run help"
    Send-Rcon $ControllerPort "setblock $PayloadX $CircuitY $CircuitZ minecraft:redstone_block" | Out-Null
    Send-Rcon $ControllerPort "setblock $PistonX $CircuitY $CircuitZ minecraft:sticky_piston[facing=east]" | Out-Null
    Send-Rcon $ControllerPort "setblock $PowerX $CircuitY $CircuitZ minecraft:redstone_block" | Out-Null

    $extendDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $extendedController = Send-Rcon $ControllerPort $extendedCommand
        $extendedObserver = Send-Rcon $ObserverPort $extendedCommand
        $destinationController = Send-Rcon $ControllerPort $destinationCommand
        $destinationObserver = Send-Rcon $ObserverPort $destinationCommand
        if ($extendedController -match 'Index \(\d+/\d+\)' -and $extendedObserver -match 'Index \(\d+/\d+\)' -and
            $destinationController -match 'Index \(\d+/\d+\)' -and $destinationObserver -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $extendDeadline)
    Assert-Contains $extendedController 'Index (' "$Direction sticky piston did not extend after receiving redstone power."
    Assert-Contains $extendedObserver 'Index (' "The other backend did not observe the $Direction sticky piston extension."
    Assert-Contains $destinationController 'Index (' "$Direction sticky piston did not push the redstone block."
    Assert-Contains $destinationObserver 'Index (' "The other backend did not observe the redstone block pushed by $Direction."

    Send-Rcon $ControllerPort "setblock $PowerX $CircuitY $CircuitZ minecraft:air" | Out-Null
    $retractDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $retractedController = Send-Rcon $ControllerPort $retractedCommand
        $retractedObserver = Send-Rcon $ObserverPort $retractedCommand
        $payloadController = Send-Rcon $ControllerPort $payloadCommand
        $payloadObserver = Send-Rcon $ObserverPort $payloadCommand
        if ($retractedController -match 'Index \(\d+/\d+\)' -and $retractedObserver -match 'Index \(\d+/\d+\)' -and
            $payloadController -match 'Index \(\d+/\d+\)' -and $payloadObserver -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $retractDeadline)
    Assert-Contains $retractedController 'Index (' "$Direction sticky piston did not retract after power was removed."
    Assert-Contains $retractedObserver 'Index (' "The other backend did not observe the $Direction sticky piston retraction."
    Assert-Contains $payloadController 'Index (' "$Direction sticky piston did not pull the redstone block back."
    Assert-Contains $payloadObserver 'Index (' "The other backend did not observe the redstone block pulled by $Direction."
    Assert-NotContains (Send-Rcon $ControllerPort $itemCommand) 'Index (' "$Direction sticky piston dropped the pulled redstone block as an item."
    Assert-NotContains (Send-Rcon $ObserverPort $itemCommand) 'Index (' "The other backend saw the redstone block pulled by $Direction dropped as an item."
    Write-Output "PASS: $Direction sticky piston pulls the redstone block back; both backends retain it without an item drop."
}

$tag = 'multipaper_regression_' + [Guid]::NewGuid().ToString('N')
Write-Output "Probe entity tag: $tag"
$x = $TestX
$y = $TestY
$z = $TestZ
$block1X = ([Math]::Floor($x / 16) * 16) + 15
$block2X = $block1X + 4096
$block2Z = $z + 4096
$containerX = $block1X - 3
$block1 = "execute if block $block1X $y $z minecraft:emerald_block run help"
$block2 = "execute if block $block2X $y $block2Z minecraft:diamond_block run help"
$testChunkCoordinates = @{}
for ($testBlockX = [Math]::Min($x, $containerX); $testBlockX -le $x + 5; $testBlockX++) {
    for ($testBlockZ = $z; $testBlockZ -le $z + 8; $testBlockZ++) {
        $chunkX = [Math]::Floor($testBlockX / 16)
        $chunkZ = [Math]::Floor($testBlockZ / 16)
        $chunkKey = "$chunkX,$chunkZ"
        if (-not $testChunkCoordinates.ContainsKey($chunkKey)) {
            $testChunkCoordinates[$chunkKey] = [pscustomobject]@{ X = $testBlockX; Z = $testBlockZ }
        }
    }
}
$block2ChunkKey = "$([Math]::Floor($block2X / 16)),$([Math]::Floor($block2Z / 16))"
if (-not $testChunkCoordinates.ContainsKey($block2ChunkKey)) {
    $testChunkCoordinates[$block2ChunkKey] = [pscustomobject]@{ X = $block2X; Z = $block2Z }
}
$baseChunkLoads = @($testChunkCoordinates.Values)
$hopperSourceX = $null
$hopperDestinationX = $null
$hopperY = 300
$hopperZ = $null
$hopperMiddleZ = $null
$hopperSecondZ = $null
$ownerRemovalX = $null
$ownerRemovalZ = $null
$remoteRemovalX = $null
$remoteRemovalZ = $null
$dimensionX = $null
$dimensionZ = $null
$baseChunkOwners = @{}
$block1ChunkKey = "$([Math]::Floor($block1X / 16)),$([Math]::Floor($z / 16))"
$baseChunkOwners[$block1ChunkKey] = $Server1RconPort
$baseChunkOwners[$block2ChunkKey] = $Server2RconPort

try {
    foreach ($chunk in $baseChunkLoads) {
        $chunkKey = "$([Math]::Floor($chunk.X / 16)),$([Math]::Floor($chunk.Z / 16))"
        if (-not $baseChunkOwners.ContainsKey($chunkKey)) { $baseChunkOwners[$chunkKey] = $Server1RconPort }
        $ownerPort = $baseChunkOwners[$chunkKey]
        $ownerName = if ($ownerPort -eq $Server1RconPort) { 'Backend 1' } else { 'Backend 2' }
        Send-Rcon $ownerPort "forceload add $($chunk.X) $($chunk.Z)" | Out-Null
        $loadedCommand = "execute if loaded $($chunk.X) $y $($chunk.Z) run help"
        $loadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $loaded = Send-Rcon $ownerPort $loadedCommand
            if ($loaded -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $loadDeadline)
        Assert-Contains $loaded 'Index (' "Test chunk $chunkKey did not load on its designated owner $ownerName after forceload."
    }
    Send-Rcon $Server1RconPort "setblock $block1X $y $z minecraft:air" | Out-Null
    Send-Rcon $Server2RconPort "setblock $block2X $y $block2Z minecraft:air" | Out-Null
    Assert-Contains (Send-Rcon $Server1RconPort "setblock $block1X $y $z minecraft:emerald_block") 'Changed the block' 'Backend 1 could not write its owned block marker.'
    Assert-Contains (Send-Rcon $Server2RconPort "setblock $block2X $y $block2Z minecraft:diamond_block") 'Changed the block' 'Backend 2 could not write its owned block marker.'
    Send-Rcon $Server2RconPort "forceload add $block1X $z" | Out-Null
    Send-Rcon $Server1RconPort "forceload add $block2X $block2Z" | Out-Null
    $blockDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $block1On1 = Send-Rcon $Server1RconPort $block1
        $block1On2 = Send-Rcon $Server2RconPort $block1
        $block2On2 = Send-Rcon $Server2RconPort $block2
        $block2On1 = Send-Rcon $Server1RconPort $block2
        if ($block1On1 -match 'Index \(\d+/\d+\)' -and $block1On2 -match 'Index \(\d+/\d+\)' -and
            $block2On2 -match 'Index \(\d+/\d+\)' -and $block2On1 -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $blockDeadline)
    Assert-Contains $block1On1 'Index (' 'Server 1 could not read its block marker.'
    Assert-Contains $block1On2 'Index (' 'Server 2 could not read Server 1 block changes after requesting its marker chunk.'
    Assert-Contains $block2On2 'Index (' 'Server 2 could not read its block marker.'
    Assert-Contains $block2On1 'Index (' 'Server 1 could not read Server 2 block changes after requesting its marker chunk.'
    Write-Output 'PASS: both backends read the other backend''s marker after requesting its chunk.'

    $containerCommand = "data get block $containerX $y $z Items"
    $shulkerX = $null
    if ($SkipContainerCheck) {
        Write-Output 'SKIP: chest and shulker block entity contents checks disabled by -SkipContainerCheck.'
    } else {
        $shulkerX = $containerX + 1
        $shulkerCommand = "data get block $shulkerX $y $z Items"
        Send-Rcon $Server1RconPort "setblock $containerX $y $z minecraft:air" | Out-Null
        Send-Rcon $Server1RconPort "setblock $containerX $y $z minecraft:chest" | Out-Null
        Send-Rcon $Server1RconPort "item replace block $containerX $y $z container.0 with minecraft:diamond 5" | Out-Null
        Assert-Contains (Send-Rcon $Server1RconPort $containerCommand) 'minecraft:diamond' 'Server 1 could not read the container marker item.'
        $containerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $containerItems2 = ''
        do {
            $containerItems2 = Send-Rcon $Server2RconPort $containerCommand
            if ($containerItems2.IndexOf('minecraft:diamond', [StringComparison]::OrdinalIgnoreCase) -ge 0) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $containerDeadline)
        Assert-Contains $containerItems2 'minecraft:diamond' 'Server 2 could not read the chest block entity contents after requesting the marker chunk.'
        Write-Output 'PASS: Server 2 read the chest block entity contents after requesting the marker chunk.'

        Send-Rcon $Server1RconPort "setblock $shulkerX $y $z minecraft:air" | Out-Null
        Send-Rcon $Server1RconPort "setblock $shulkerX $y $z minecraft:shulker_box" | Out-Null
        Send-Rcon $Server1RconPort "item replace block $shulkerX $y $z container.0 with minecraft:emerald 7" | Out-Null
        Assert-Contains (Send-Rcon $Server1RconPort $shulkerCommand) 'minecraft:emerald' 'Server 1 could not read the shulker marker item.'
        Send-Rcon $Server2RconPort "forceload add $shulkerX $z" | Out-Null
        $shulkerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $shulkerItems2 = ''
        do {
            $shulkerItems2 = Send-Rcon $Server2RconPort $shulkerCommand
            if ($shulkerItems2.IndexOf('minecraft:emerald', [StringComparison]::OrdinalIgnoreCase) -ge 0) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $shulkerDeadline)
        Assert-Contains $shulkerItems2 'minecraft:emerald' 'Server 2 could not read the shulker block entity contents after requesting its marker chunk.'
        Write-Output 'PASS: Server 2 read the shulker block entity contents after requesting the marker chunk.'
    }

    $hopperSourceX = ([Math]::Floor(($x + 1024) / 16) * 16) + 15
    $hopperDestinationX = $hopperSourceX + 1
    $hopperZ = ([Math]::Floor(($z + 1024) / 16) * 16) + 5
    $hopperMiddleZ = $hopperZ + 1
    $hopperSecondZ = $hopperZ + 2
    $hopperSourceLoadCommand = "execute if loaded $hopperSourceX $hopperY $hopperZ run help"
    $hopperDestinationLoadCommand = "execute if loaded $hopperDestinationX $hopperY $hopperZ run help"
    Send-Rcon $Server1RconPort "forceload add $hopperSourceX $hopperZ" | Out-Null
    $hopperSourceDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $hopperSourceLoaded = Send-Rcon $Server1RconPort $hopperSourceLoadCommand
        if ($hopperSourceLoaded -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $hopperSourceDeadline)
    Assert-Contains $hopperSourceLoaded 'Index (' 'Backend 1 did not locally load the cross-chunk hopper source.'
    $hopperDestinationLoaded = Send-Rcon $Server1RconPort $hopperDestinationLoadCommand
    if ($hopperDestinationLoaded -notmatch 'Index \(\d+/\d+\)') {
        Send-Rcon $Server1RconPort "forceload add $hopperDestinationX $hopperZ" | Out-Null
    }
    $hopperDestinationDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $hopperDestinationLoaded = Send-Rcon $Server1RconPort $hopperDestinationLoadCommand
        if ($hopperDestinationLoaded -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $hopperDestinationDeadline)
    Assert-Contains $hopperDestinationLoaded 'Index (' 'Backend 1 did not locally load the cross-chunk hopper destination.'
    $hopperControllerPort = $Server1RconPort
    $hopperObserverPort = $Server2RconPort
    # Keep both sides of this cross-chunk redstone mechanism on one owner. The
    # safety chunk lock can pull the adjacent destination into the source owner.
    Start-Sleep -Seconds 2
    foreach ($zPos in @($hopperZ, $hopperMiddleZ, $hopperSecondZ)) {
        Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $zPos minecraft:air" | Out-Null
        Send-Rcon $hopperControllerPort "setblock $hopperDestinationX $hopperY $zPos minecraft:air" | Out-Null
    }
    Assert-Contains (Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $hopperZ minecraft:hopper[facing=east,enabled=true]") 'Changed the block' 'Could not place the cross-chunk diamond hopper.'
    Assert-Contains (Send-Rcon $hopperControllerPort "setblock $hopperDestinationX $hopperY $hopperZ minecraft:chest") 'Changed the block' 'Could not place the cross-chunk diamond destination chest.'
    Assert-Contains (Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $hopperSecondZ minecraft:hopper[facing=east,enabled=true]") 'Changed the block' 'Could not place the cross-chunk iron hopper.'
    Assert-Contains (Send-Rcon $hopperControllerPort "setblock $hopperDestinationX $hopperY $hopperSecondZ minecraft:chest") 'Changed the block' 'Could not place the cross-chunk iron destination chest.'
    Send-Rcon $hopperControllerPort "item replace block $hopperSourceX $hopperY $hopperZ container.0 with minecraft:diamond 8" | Out-Null
    Send-Rcon $hopperControllerPort "item replace block $hopperSourceX $hopperY $hopperSecondZ container.0 with minecraft:iron_ingot 8" | Out-Null
    $hopperSourceCommand = "data get block $hopperSourceX $hopperY {0} Items"
    $hopperDestinationCommand = "data get block $hopperDestinationX $hopperY {0} Items"
    $hopperDeadline = [DateTime]::UtcNow.AddSeconds([Math]::Max($TimeoutSeconds, 20))
    do {
        $hopperCounts = @{}
        foreach ($zPos in @($hopperZ, $hopperSecondZ)) {
            $sourceData = Send-Rcon $hopperControllerPort ($hopperSourceCommand -f $zPos)
            $destinationData = Send-Rcon $hopperControllerPort ($hopperDestinationCommand -f $zPos)
            $sourceItem = if ($zPos -eq $hopperZ) { 'minecraft:diamond' } else { 'minecraft:iron_ingot' }
            $hopperCounts["source-$sourceItem"] = Get-BlockItemCount $sourceData $sourceItem
            $hopperCounts["destination-$sourceItem"] = Get-BlockItemCount $destinationData $sourceItem
        }
        if ($hopperCounts['source-minecraft:diamond'] -eq 0 -and $hopperCounts['destination-minecraft:diamond'] -eq 8 -and
            $hopperCounts['source-minecraft:iron_ingot'] -eq 0 -and $hopperCounts['destination-minecraft:iron_ingot'] -eq 8) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $hopperDeadline)
    foreach ($itemId in @('minecraft:diamond', 'minecraft:iron_ingot')) {
        $sourceCount = $hopperCounts["source-$itemId"]
        $destinationCount = $hopperCounts["destination-$itemId"]
        if ($sourceCount -ne 0 -or $destinationCount -ne 8) {
            throw "Cross-chunk hopper transfer for $itemId did not finish with exact conservation on Backend 1. Source/destination=$sourceCount/$destinationCount."
        }
    }
    Send-Rcon $hopperControllerPort "forceload remove $hopperSourceX $hopperZ" | Out-Null
    Send-Rcon $hopperControllerPort "forceload remove $hopperDestinationX $hopperZ" | Out-Null
    Send-Rcon $hopperObserverPort "forceload add $hopperSourceX $hopperZ" | Out-Null
    Send-Rcon $hopperObserverPort "forceload add $hopperDestinationX $hopperZ" | Out-Null
    $hopperPeerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $hopperSourceLoadedOnPeer = Send-Rcon $hopperObserverPort $hopperSourceLoadCommand
        $hopperDestinationLoadedOnPeer = Send-Rcon $hopperObserverPort $hopperDestinationLoadCommand
        if ($hopperSourceLoadedOnPeer -match 'Index \(\d+/\d+\)' -and $hopperDestinationLoadedOnPeer -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $hopperPeerDeadline)
    Assert-Contains $hopperSourceLoadedOnPeer 'Index (' 'Backend 2 did not load the hopper source after owner handoff.'
    Assert-Contains $hopperDestinationLoadedOnPeer 'Index (' 'Backend 2 did not load the hopper destination after owner handoff.'
    foreach ($zPos in @($hopperZ, $hopperSecondZ)) {
        $sourceItem = if ($zPos -eq $hopperZ) { 'minecraft:diamond' } else { 'minecraft:iron_ingot' }
        $sourceCount = Get-BlockItemCount (Send-Rcon $hopperObserverPort ($hopperSourceCommand -f $zPos)) $sourceItem
        $destinationCount = Get-BlockItemCount (Send-Rcon $hopperObserverPort ($hopperDestinationCommand -f $zPos)) $sourceItem
        if ($sourceCount + $destinationCount -ne 8) {
            throw "Cross-chunk hopper item count for $sourceItem changed after ownership handoff to Backend 2. Source/destination=$sourceCount/$destinationCount."
        }
    }
    Write-Output 'PASS: two same-owner hoppers transferred 8 diamonds and 8 iron ingots across a chunk boundary with exact conservation; counts persisted after transfer to Backend 2.'

    $circuitX = $x + 2
    $circuitY = $y + 1
    $circuitZ = $z + 1
    $stickyPistonX = $x + 1
    $stickyPowerX = $x
    $stickyPayloadX = $x + 2
    $stickyDestinationX = $x + 3
    $stickyCircuitZ = $z + 4
    $reverseStickyCircuitX = $x + 4096
    $reverseStickyPowerX = $reverseStickyCircuitX
    $reverseStickyPistonX = $reverseStickyCircuitX + 1
    $reverseStickyPayloadX = $reverseStickyCircuitX + 2
    $reverseStickyDestinationX = $reverseStickyCircuitX + 3
    $reverseStickyCircuitY = 300
    $reverseStickyCircuitZ = $z + 4096
    $pistonX = $circuitX + 1
    $movedBlockX = $circuitX + 2
    $destinationX = $circuitX + 3
    if ($SkipRedstoneAndPistonCheck) {
        Write-Output 'SKIP: redstone and piston checks disabled by -SkipRedstoneAndPistonCheck.'
    } else {
        $baseCircuitLoadedCommand = "execute if loaded $pistonX $circuitY $circuitZ run help"
        $baseCircuitLoaded1 = Send-Rcon $Server1RconPort $baseCircuitLoadedCommand
        $baseCircuitLoaded2 = Send-Rcon $Server2RconPort $baseCircuitLoadedCommand
        if ($baseCircuitLoaded1 -match 'Index \(\d+/\d+\)') {
            $baseCircuitControllerPort = $Server1RconPort
            $baseCircuitObserverPort = $Server2RconPort
            $baseCircuitControllerName = 'Backend 1'
            $baseCircuitObserverName = 'Backend 2'
        } elseif ($baseCircuitLoaded2 -match 'Index \(\d+/\d+\)') {
            $baseCircuitControllerPort = $Server2RconPort
            $baseCircuitObserverPort = $Server1RconPort
            $baseCircuitControllerName = 'Backend 2'
            $baseCircuitObserverName = 'Backend 1'
        } else {
            throw 'Neither backend locally owns the base piston test chunk.'
        }
        Write-Output "PASS: $baseCircuitControllerName owns the base redstone test chunk."
        $pistonCommand = "execute if block $pistonX $circuitY $circuitZ minecraft:piston[facing=east,extended=true] run help"
        $retractedPistonCommand = "execute if block $pistonX $circuitY $circuitZ minecraft:piston[facing=east,extended=false] run help"
        $movedBlockCommand = "execute if block $destinationX $circuitY $circuitZ minecraft:stone run help"
        $retractedBlockCommand = "execute if block $destinationX $circuitY $circuitZ minecraft:stone run help"
        foreach ($clearX in $circuitX..$destinationX) {
            Send-Rcon $baseCircuitControllerPort "setblock $clearX $circuitY $circuitZ minecraft:air" | Out-Null
        }
        Send-Rcon $baseCircuitControllerPort "setblock $movedBlockX $circuitY $circuitZ minecraft:stone" | Out-Null
        Send-Rcon $baseCircuitControllerPort "setblock $pistonX $circuitY $circuitZ minecraft:piston[facing=east]" | Out-Null
        Send-Rcon $baseCircuitControllerPort "setblock $circuitX $circuitY $circuitZ minecraft:redstone_block" | Out-Null
        $circuitDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $pistonState1 = Send-Rcon $baseCircuitControllerPort $pistonCommand
            $pistonState2 = Send-Rcon $baseCircuitObserverPort $pistonCommand
            $movedBlock1 = Send-Rcon $baseCircuitControllerPort $movedBlockCommand
            $movedBlock2 = Send-Rcon $baseCircuitObserverPort $movedBlockCommand
            if ($pistonState1 -match 'Index \(\d+/\d+\)' -and $pistonState2 -match 'Index \(\d+/\d+\)' -and
                $movedBlock1 -match 'Index \(\d+/\d+\)' -and $movedBlock2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $circuitDeadline)
        Assert-Contains $pistonState1 'Index (' "$baseCircuitControllerName piston did not extend after receiving redstone power."
        Assert-Contains $pistonState2 'Index (' 'The peer did not observe the powered piston state.'
        Assert-Contains $movedBlock1 'Index (' "$baseCircuitControllerName piston did not move the stone block."
        Assert-Contains $movedBlock2 'Index (' 'The peer did not observe the piston-moved stone block.'
        Write-Output "PASS: redstone-powered piston extension and block movement replicate from $baseCircuitControllerName."

        Send-Rcon $baseCircuitControllerPort "setblock $circuitX $circuitY $circuitZ minecraft:air" | Out-Null
        $retractDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $pistonState1 = Send-Rcon $baseCircuitControllerPort $retractedPistonCommand
            $pistonState2 = Send-Rcon $baseCircuitObserverPort $retractedPistonCommand
            $retractedBlock1 = Send-Rcon $baseCircuitControllerPort $retractedBlockCommand
            $retractedBlock2 = Send-Rcon $baseCircuitObserverPort $retractedBlockCommand
            if ($pistonState1 -match 'Index \(\d+/\d+\)' -and $pistonState2 -match 'Index \(\d+/\d+\)' -and
                $retractedBlock1 -match 'Index \(\d+/\d+\)' -and $retractedBlock2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $retractDeadline)
        Assert-Contains $pistonState1 'Index (' "$baseCircuitControllerName piston did not retract after redstone power was removed."
        Assert-Contains $pistonState2 'Index (' 'The peer did not observe the retracted piston state.'
        Assert-Contains $retractedBlock1 'Index (' "$baseCircuitControllerName did not keep the moved stone at its destination after piston retraction."
        Assert-Contains $retractedBlock2 'Index (' 'The peer did not observe the piston-moved stone after retraction.'
        Write-Output 'PASS: redstone power removal retracts the piston and both servers retain the moved stone at its destination.'

        # Exercise the observer's scheduled pulse, not just static block replication.
        # All blocks stay inside the already loaded base chunk while both backends read it.
        $observerX = $x + 2
        $observerZ = $z + 6
        $observedBlockZ = $z + 7
        $observerTrueCommand = "execute if block $observerX $circuitY $observerZ minecraft:observer[facing=south,powered=true] run help"
        $observerFalseCommand = "execute if block $observerX $circuitY $observerZ minecraft:observer[facing=south,powered=false] run help"
        Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observedBlockZ minecraft:air" | Out-Null
        Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observerZ minecraft:observer[facing=south,powered=false]" | Out-Null
        $observerReadyDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $observerLowController = Send-Rcon $baseCircuitControllerPort $observerFalseCommand
            $observerLowPeer = Send-Rcon $baseCircuitObserverPort $observerFalseCommand
            if ($observerLowController -match 'Index \(\d+/\d+\)' -and $observerLowPeer -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 25
        } while ([DateTime]::UtcNow -lt $observerReadyDeadline)
        Assert-Contains $observerLowController 'Index (' "$baseCircuitControllerName observer did not begin unpowered."
        Assert-Contains $observerLowPeer 'Index (' 'The peer did not observe the unpowered observer state.'

        $observerRiseStart = [DateTime]::UtcNow
        Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observedBlockZ minecraft:stone" | Out-Null
        $observerPulseDeadline = $observerRiseStart.AddSeconds(2)
        do {
            $observerHighController = Send-Rcon $baseCircuitControllerPort $observerTrueCommand
            if ($observerHighController -match 'Index \(\d+/\d+\)') {
                $observerHighPeer = Send-Rcon $baseCircuitObserverPort $observerTrueCommand
                if ($observerHighPeer -match 'Index \(\d+/\d+\)') { break }
            }
            Start-Sleep -Milliseconds 20
        } while ([DateTime]::UtcNow -lt $observerPulseDeadline)
        $observerRiseMilliseconds = [int]([DateTime]::UtcNow - $observerRiseStart).TotalMilliseconds
        Assert-Contains $observerHighController 'Index (' "$baseCircuitControllerName observer did not pulse after its input block changed."
        Assert-Contains $observerHighPeer 'Index (' 'The peer did not receive the observer powered-state pulse.'
        if ($observerRiseMilliseconds -gt 1000) { throw "Observer pulse arrived too late ($observerRiseMilliseconds ms)." }

        $observerFallStart = [DateTime]::UtcNow
        $observerFallDeadline = $observerFallStart.AddSeconds(2)
        do {
            $observerResetController = Send-Rcon $baseCircuitControllerPort $observerFalseCommand
            $observerResetPeer = Send-Rcon $baseCircuitObserverPort $observerFalseCommand
            if ($observerResetController -match 'Index \(\d+/\d+\)' -and $observerResetPeer -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 20
        } while ([DateTime]::UtcNow -lt $observerFallDeadline)
        $observerFallMilliseconds = [int]([DateTime]::UtcNow - $observerFallStart).TotalMilliseconds
        Assert-Contains $observerResetController 'Index (' "$baseCircuitControllerName observer did not reset after its pulse."
        Assert-Contains $observerResetPeer 'Index (' 'The peer did not receive the observer reset state.'
        if ($observerFallMilliseconds -gt 1000) { throw "Observer reset took too long ($observerFallMilliseconds ms)." }
        Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observerZ minecraft:air" | Out-Null
        Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observedBlockZ minecraft:air" | Out-Null
        Write-Output "PASS: observer pulse and reset replicated to both backends (rise observed in ${observerRiseMilliseconds} ms; reset in ${observerFallMilliseconds} ms)."

        Test-StickyPistonPull -ControllerPort $baseCircuitControllerPort -ObserverPort $baseCircuitObserverPort -PistonX $stickyPistonX -PowerX $stickyPowerX -PayloadX $stickyPayloadX -DestinationX $stickyDestinationX -CircuitY $circuitY -CircuitZ $stickyCircuitZ -Direction $baseCircuitControllerName
        Send-Rcon $baseCircuitObserverPort "forceload add $reverseStickyCircuitX $reverseStickyCircuitZ" | Out-Null
        $reverseLoadCommand = "execute if loaded $reverseStickyCircuitX $reverseStickyCircuitY $reverseStickyCircuitZ run help"
        $reverseLoadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $reverseChunkLoaded = Send-Rcon $baseCircuitObserverPort $reverseLoadCommand
            if ($reverseChunkLoaded -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $reverseLoadDeadline)
        Assert-Contains $reverseChunkLoaded 'Index (' "$baseCircuitObserverName sticky-piston test chunk did not become locally loaded."
        Write-Output "PASS: $baseCircuitObserverName sticky-piston circuit runs in a locally owned chunk."
        Send-Rcon $baseCircuitControllerPort "forceload add $reverseStickyCircuitX $reverseStickyCircuitZ" | Out-Null
        Test-StickyPistonPull -ControllerPort $baseCircuitObserverPort -ObserverPort $baseCircuitControllerPort -PistonX $reverseStickyPistonX -PowerX $reverseStickyPowerX -PayloadX $reverseStickyPayloadX -DestinationX $reverseStickyDestinationX -CircuitY $reverseStickyCircuitY -CircuitZ $reverseStickyCircuitZ -Direction 'peer backend'
    }

    if ($SkipPlayerAndEntityChecks) {
        Write-Output 'SKIP: player inventory, entity subscription, and dimension checks disabled by -SkipPlayerAndEntityChecks.'
    } else {
    if ([string]::IsNullOrWhiteSpace($PlayerName)) {
        Assert-NoNewDuplicateEntityUuidWarnings $duplicateUuidWarningsBefore
        Write-Output 'SKIP: player inventory replication requires -PlayerName.'
    }

    if (-not [string]::IsNullOrWhiteSpace($PlayerName)) {
        $playerList1 = Send-Rcon $Server1RconPort 'list'
        $playerList2 = Send-Rcon $Server2RconPort 'list'
        Assert-Contains $playerList1 $PlayerName 'The requested inventory test player is not online on Server 1.'
        Assert-Contains $playerList2 $PlayerName 'The requested inventory test player is not online on Server 2.'
        Send-Rcon $Server1RconPort "give $PlayerName minecraft:cookie 1" | Out-Null
        $inventoryCommand = "data get entity $PlayerName Inventory[{id:`"minecraft:cookie`"}]"
        Assert-Contains (Send-Rcon $Server1RconPort $inventoryCommand) 'minecraft:cookie' 'Server 1 did not see the test item in the player inventory.'
        Assert-Contains (Send-Rcon $Server2RconPort $inventoryCommand) 'minecraft:cookie' 'Server 2 did not receive the player inventory update.'
        Write-Output "PASS: player inventory change replicated for $PlayerName."
        Send-Rcon $Server1RconPort "tp $PlayerName $($x + 0.5) $($y + 1) $($z + 0.5)" | Out-Null
        Start-Sleep -Seconds 1
    }

    Send-Rcon $Server1RconPort "summon minecraft:pig $($x + 0.5) $y $($z + 0.5) {Tags:[`"$tag`"],NoGravity:1b}" | Out-Null
    $uuidCommand = "data get entity @e[tag=$tag,limit=1] UUID"
    $uuid1 = Send-Rcon $Server1RconPort $uuidCommand
    $uuid2 = Send-Rcon $Server2RconPort $uuidCommand
    $uuidMatch1 = [regex]::Match($uuid1, '\[I;[^\]]+\]')
    $uuidMatch2 = [regex]::Match($uuid2, '\[I;[^\]]+\]')
    if (-not $uuidMatch1.Success) { throw "Server 1 did not report the tagged mob UUID: $uuid1" }
    if (-not $uuidMatch2.Success -or $uuidMatch1.Value -cne $uuidMatch2.Value) {
        throw "Entity subscription UUID differs: Server 1 '$($uuidMatch1.Value)', Server 2 '$uuid2'."
    }
    Write-Output "PASS: spawned entity subscription matches UUID $($uuidMatch1.Value)."

    $entityTargetBlockX = $x + 32
    $entityTargetBlockZ = $z + 1
    $targetX = $entityTargetBlockX + 0.5
    $targetZ = $entityTargetBlockZ + 0.5
    Send-Rcon $Server1RconPort "forceload add $entityTargetBlockX $entityTargetBlockZ" | Out-Null
    Send-Rcon $Server2RconPort "forceload add $entityTargetBlockX $entityTargetBlockZ" | Out-Null
    $entityChunkLoadCommand = "execute if loaded $entityTargetBlockX $y $entityTargetBlockZ run help"
    $entityChunkLoadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $entityChunkLoaded1 = Send-Rcon $Server1RconPort $entityChunkLoadCommand
        $entityChunkLoaded2 = Send-Rcon $Server2RconPort $entityChunkLoadCommand
        if ($entityChunkLoaded1 -match 'Index \(\d+/\d+\)' -and $entityChunkLoaded2 -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $entityChunkLoadDeadline)
    Assert-Contains $entityChunkLoaded1 'Index (' 'The entity teleport target chunk did not load on backend 1.'
    Assert-Contains $entityChunkLoaded2 'Index (' 'The entity teleport target chunk did not load on backend 2.'
    # Let both chunk subscribers register before moving the entity into this chunk.
    Start-Sleep -Seconds 2

    if (-not [string]::IsNullOrWhiteSpace($PlayerName)) {
        Send-Rcon $Server1RconPort "tp $PlayerName $targetX $y $targetZ" | Out-Null
        $playerPositionCommand = "data get entity $PlayerName Pos"
        $playerPositionDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $playerPosition1 = Send-Rcon $Server1RconPort $playerPositionCommand
            $playerPosition2 = Send-Rcon $Server2RconPort $playerPositionCommand
            $playerPositionMatch1 = [regex]::Match($playerPosition1, '\[([^\]]+)\]')
            $playerPositionMatch2 = [regex]::Match($playerPosition2, '\[([^\]]+)\]')
            if ($playerPositionMatch1.Success -and $playerPositionMatch2.Success -and
                $playerPositionMatch1.Value -ceq $playerPositionMatch2.Value -and
                $playerPositionMatch1.Value.Contains("$targetX`d")) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $playerPositionDeadline)
        if (-not $playerPositionMatch1.Success -or -not $playerPositionMatch2.Success) {
            throw "Could not read mirrored player positions: Server 1 '$playerPosition1', Server 2 '$playerPosition2'."
        }
        Assert-Contains $playerPositionMatch1.Value "$targetX`d" 'The player did not reach the cross-chunk test position on Server 1.'
        if ($playerPositionMatch1.Value -cne $playerPositionMatch2.Value) {
            throw "Player positions differ after crossing chunks: Server 1 '$($playerPositionMatch1.Value)', Server 2 '$($playerPositionMatch2.Value)' ."
        }
        Write-Output "PASS: player position replicated across chunks as '$($playerPositionMatch2.Value)'."
    }

    Send-Rcon $Server1RconPort "tp @e[tag=$tag,limit=1] $targetX $y $targetZ" | Out-Null
    $posCommand = "data get entity @e[tag=$tag,limit=1] Pos"
    $positionDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $pos1 = Send-Rcon $Server1RconPort $posCommand
        $pos2 = Send-Rcon $Server2RconPort $posCommand
        $positionMatch1 = [regex]::Match($pos1, '\[([^\]]+)\]')
        $positionMatch2 = [regex]::Match($pos2, '\[([^\]]+)\]')
        if ($positionMatch1.Success -and $positionMatch2.Success -and
            $positionMatch1.Value -ceq $positionMatch2.Value -and
            $positionMatch1.Value.Contains("$targetX`d")) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $positionDeadline)
    if (-not $positionMatch1.Success -or -not $positionMatch2.Success) {
        throw "Could not read entity positions: Server 1 '$pos1', Server 2 '$pos2'."
    }
    Assert-Contains $positionMatch1.Value "$targetX`d" 'Server 1 entity did not reach the target position.'
    if ($positionMatch1.Value -cne $positionMatch2.Value) {
        throw "Entity positions differ: Server 1 '$($positionMatch1.Value)', Server 2 '$($positionMatch2.Value)'."
    }
    Write-Output "PASS: entity teleport position replicated as '$($positionMatch2.Value)'."

    # Use a non-living entity for deterministic removal. A living mob can
    # remain in its dying state because only its owner ticks it, so the
    # command's success message does not prove its removal hook ran.
    $ownerRemovalTag = $tag + '_owner_remove'
    $ownerRemovalOffset = 4096 + (Get-Random -Minimum 0 -Maximum 256) * 16
    $ownerRemovalX = $x + $ownerRemovalOffset
    $ownerRemovalZ = $z + $ownerRemovalOffset
    $ownerRemovalChunkLoadCommand = "execute if loaded $ownerRemovalX $y $ownerRemovalZ run help"
    Send-Rcon $Server1RconPort "forceload add $ownerRemovalX $ownerRemovalZ" | Out-Null
    $ownerRemovalOwnerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $ownerRemovalOwnerLoaded = Send-Rcon $Server1RconPort $ownerRemovalChunkLoadCommand
        if ($ownerRemovalOwnerLoaded -match 'Index \(\d+\/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $ownerRemovalOwnerDeadline)
    Assert-Contains $ownerRemovalOwnerLoaded 'Index (' 'Backend 1 did not load the owner-removal chunk first.'
    Send-Rcon $Server2RconPort "forceload add $ownerRemovalX $ownerRemovalZ" | Out-Null
    $ownerRemovalSubscriberDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $ownerRemovalSubscriberLoaded = Send-Rcon $Server2RconPort $ownerRemovalChunkLoadCommand
        if ($ownerRemovalSubscriberLoaded -match 'Index \(\d+\/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $ownerRemovalSubscriberDeadline)
    Assert-Contains $ownerRemovalSubscriberLoaded 'Index (' 'Backend 2 did not load the owner-removal subscriber chunk.'
    Start-Sleep -Seconds 1
    Send-Rcon $Server1RconPort "summon minecraft:area_effect_cloud $($ownerRemovalX + 0.5) $y $($ownerRemovalZ + 0.5) {Duration:1000000,Radius:0f,WaitTime:0,Tags:[`"$ownerRemovalTag`"]}" | Out-Null
    $ownerRemovalUuidCommand = "data get entity @e[tag=$ownerRemovalTag,limit=1] UUID"
    $ownerRemovalUuidDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $ownerRemovalUuid1 = Send-Rcon $Server1RconPort $ownerRemovalUuidCommand
        $ownerRemovalUuid2 = Send-Rcon $Server2RconPort $ownerRemovalUuidCommand
        $ownerRemovalMatch1 = [regex]::Match($ownerRemovalUuid1, '\[I;[^\]]+\]')
        $ownerRemovalMatch2 = [regex]::Match($ownerRemovalUuid2, '\[I;[^\]]+\]')
        if ($ownerRemovalMatch1.Success -and $ownerRemovalMatch1.Value -ceq $ownerRemovalMatch2.Value) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $ownerRemovalUuidDeadline)
    if (-not $ownerRemovalMatch1.Success -or -not $ownerRemovalMatch2.Success -or
        $ownerRemovalMatch1.Value -cne $ownerRemovalMatch2.Value) {
        throw "The owner-removal probe did not reach both backends: owner '$ownerRemovalUuid1', subscriber '$ownerRemovalUuid2'."
    }
    $ownerProbeKill = Send-Rcon $Server1RconPort "kill @e[tag=$ownerRemovalTag]"
    Write-Output "Owner-side entity-removal kill response: $ownerProbeKill"
    Assert-Contains $ownerProbeKill 'Killed' 'Backend 1 did not execute the owner-side entity removal probe.'
    $removalDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $removedEntity = Send-Rcon $Server2RconPort $ownerRemovalUuidCommand
        if ($removedEntity.IndexOf('No entity was found', [StringComparison]::OrdinalIgnoreCase) -ge 0) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $removalDeadline)
    Assert-Contains $removedEntity 'No entity was found' 'Owner-side entity removal did not reach Backend 2.'
    Write-Output 'PASS: owner-side entity removal replicated to Backend 2.'

    # Exercise a removal issued by a non-owner backend. Give Server 2 the
    # chunk first, then subscribe Server 1 before creating the entity.
    $remoteRemovalTag = $tag + '_remote_remove'
    $remoteRemovalOffset = 8192 + (Get-Random -Minimum 0 -Maximum 256) * 16
    $remoteRemovalX = $x + $remoteRemovalOffset
    $remoteRemovalZ = $z + $remoteRemovalOffset
    Write-Output "Non-owner entity-removal probe chunk: $([Math]::Floor($remoteRemovalX / 16)),$([Math]::Floor($remoteRemovalZ / 16))"
    $remoteRemovalChunkLoadCommand = "execute if loaded $remoteRemovalX $y $remoteRemovalZ run help"
    Send-Rcon $Server2RconPort "forceload add $remoteRemovalX $remoteRemovalZ" | Out-Null
    $remoteRemovalOwnerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $remoteRemovalOwnerLoaded = Send-Rcon $Server2RconPort $remoteRemovalChunkLoadCommand
        if ($remoteRemovalOwnerLoaded -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $remoteRemovalOwnerDeadline)
    Assert-Contains $remoteRemovalOwnerLoaded 'Index (' 'Server 2 did not load the remote-removal owner chunk.'
    Send-Rcon $Server1RconPort "forceload add $remoteRemovalX $remoteRemovalZ" | Out-Null
    $remoteRemovalSubscriberDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $remoteRemovalSubscriberLoaded = Send-Rcon $Server1RconPort $remoteRemovalChunkLoadCommand
        if ($remoteRemovalSubscriberLoaded -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $remoteRemovalSubscriberDeadline)
    Assert-Contains $remoteRemovalSubscriberLoaded 'Index (' 'Server 1 did not load the remote-removal subscriber chunk.'
    Start-Sleep -Seconds 1

    # Use a non-living entity: /kill finalizes its removal immediately. A
    # living mob can remain in its dying state on a non-owner mirror because
    # only the owner ticks it, which would test death ticking instead of the
    # removal request/fanout path.
    Send-Rcon $Server2RconPort "summon minecraft:area_effect_cloud $($remoteRemovalX + 0.5) $y $($remoteRemovalZ + 0.5) {Duration:1000000,Radius:0f,WaitTime:0,Tags:[`"$remoteRemovalTag`"]}" | Out-Null
    $remoteRemovalUuidCommand = "data get entity @e[tag=$remoteRemovalTag,limit=1] UUID"
    $remoteRemovalUuidDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $remoteRemovalUuidOwner = Send-Rcon $Server2RconPort $remoteRemovalUuidCommand
        $remoteRemovalUuidSubscriber = Send-Rcon $Server1RconPort $remoteRemovalUuidCommand
        $remoteRemovalUuidMatchOwner = [regex]::Match($remoteRemovalUuidOwner, '\[I;[^\]]+\]')
        $remoteRemovalUuidMatchSubscriber = [regex]::Match($remoteRemovalUuidSubscriber, '\[I;[^\]]+\]')
        if ($remoteRemovalUuidMatchOwner.Success -and $remoteRemovalUuidMatchOwner.Value -ceq $remoteRemovalUuidMatchSubscriber.Value) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $remoteRemovalUuidDeadline)
    if (-not $remoteRemovalUuidMatchOwner.Success -or -not $remoteRemovalUuidMatchSubscriber.Success -or
        $remoteRemovalUuidMatchOwner.Value -cne $remoteRemovalUuidMatchSubscriber.Value) {
        throw "The entity did not reach both backends before the non-owner removal probe: owner '$remoteRemovalUuidOwner', subscriber '$remoteRemovalUuidSubscriber'."
    }

    $remoteRemovalKill = Send-Rcon $Server1RconPort "kill @e[tag=$remoteRemovalTag]"
    Write-Output "Non-owner entity-removal kill response: $remoteRemovalKill"
    Assert-Contains $remoteRemovalKill 'Killed' 'Server 1 did not execute the non-owner entity removal probe; its copy may have unloaded before the kill command.'
    $remoteRemovalDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $remoteRemovalResult = Send-Rcon $Server2RconPort $remoteRemovalUuidCommand
        if ($remoteRemovalResult.IndexOf('No entity was found', [StringComparison]::OrdinalIgnoreCase) -ge 0) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $remoteRemovalDeadline)
    Assert-Contains $remoteRemovalResult 'No entity was found' 'Entity removal from a non-owner backend did not reach the owner.'
    Write-Output 'PASS: non-owner entity removal reached Backend 2 and disappeared from its authoritative entity list.'

    $dimensionTag = $tag + '_nether'
    $dimensionX = 4096 + (Get-Random -Minimum 0 -Maximum 256) * 16
    $dimensionZ = 4096 + (Get-Random -Minimum 0 -Maximum 256) * 16
    $dimensionChunkLoadCommand = "execute in minecraft:the_nether if loaded $dimensionX 80 $dimensionZ run help"
    Send-Rcon $Server1RconPort "execute in minecraft:the_nether run forceload add $dimensionX $dimensionZ" | Out-Null
    $dimensionOwnerDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $dimensionOwnerLoaded = Send-Rcon $Server1RconPort $dimensionChunkLoadCommand
        if ($dimensionOwnerLoaded -match 'Index \(\d+\/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $dimensionOwnerDeadline)
    Assert-Contains $dimensionOwnerLoaded 'Index (' 'Backend 1 did not load the Nether entity owner chunk first.'
    Send-Rcon $Server2RconPort "execute in minecraft:the_nether run forceload add $dimensionX $dimensionZ" | Out-Null
    $dimensionSubscriberDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $dimensionSubscriberLoaded = Send-Rcon $Server2RconPort $dimensionChunkLoadCommand
        if ($dimensionSubscriberLoaded -match 'Index \(\d+\/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $dimensionSubscriberDeadline)
    Assert-Contains $dimensionSubscriberLoaded 'Index (' 'Backend 2 did not load the Nether entity subscriber chunk.'
    Start-Sleep -Seconds 1
    Send-Rcon $Server1RconPort "execute in minecraft:the_nether run summon minecraft:area_effect_cloud $($dimensionX + 0.5) 80 $($dimensionZ + 0.5) {Duration:1000000,Radius:0f,WaitTime:0,Tags:[`"$dimensionTag`"]}" | Out-Null
    Start-Sleep -Seconds 1
    $dimensionUuidCommand = "execute in minecraft:the_nether as @e[tag=$dimensionTag,limit=1] run data get entity @s UUID"
    $dimensionUuid1 = [regex]::Match((Send-Rcon $Server1RconPort $dimensionUuidCommand), '\[I;[^\]]+\]')
    $dimensionUuid2 = [regex]::Match((Send-Rcon $Server2RconPort $dimensionUuidCommand), '\[I;[^\]]+\]')
    if (-not $dimensionUuid1.Success -or -not $dimensionUuid2.Success -or $dimensionUuid1.Value -cne $dimensionUuid2.Value) {
        throw "Dimension transfer did not replicate: Server 1 '$($dimensionUuid1.Value)', Server 2 '$($dimensionUuid2.Value)'."
    }
    Write-Output "PASS: entity dimension transfer replicated with UUID $($dimensionUuid1.Value)."
    Send-Rcon $Server1RconPort "execute in minecraft:the_nether as @e[tag=$dimensionTag] run kill @s" | Out-Null
    $dimensionRemovalDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $dimensionRemoval = Send-Rcon $Server2RconPort $dimensionUuidCommand
        if (-not [regex]::IsMatch($dimensionRemoval, '\[I;[^\]]+\]')) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $dimensionRemovalDeadline)
    if ([regex]::IsMatch($dimensionRemoval, '\[I;[^\]]+\]')) {
        throw "Entity removal in the Nether did not reach Server 2. Remaining UUID response: '$dimensionRemoval'."
    }
    Write-Output 'PASS: Nether entity removal replicated to Server 2.'

    if (-not [string]::IsNullOrWhiteSpace($PlayerName)) {
        $playerInNetherCommand = "execute as $PlayerName at @s if dimension minecraft:the_nether run help"
        Send-Rcon $Server1RconPort "execute in minecraft:the_nether run tp $PlayerName $($dimensionX + 0.5) 80 $($dimensionZ + 0.5)" | Out-Null
        $playerDimensionDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $playerNether1 = Send-Rcon $Server1RconPort $playerInNetherCommand
            $playerNether2 = Send-Rcon $Server2RconPort $playerInNetherCommand
            if ($playerNether1 -match 'Index \(\d+/\d+\)' -and $playerNether2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $playerDimensionDeadline)
        Assert-Contains $playerNether1 'Index (' 'Server 1 did not move the test player to the Nether.'
        Assert-Contains $playerNether2 'Index (' "Server 2 did not receive the player's Nether dimension transfer."
        $netherPlayerPosition1 = Send-Rcon $Server1RconPort $playerPositionCommand
        $netherPlayerPosition2 = Send-Rcon $Server2RconPort $playerPositionCommand
        $netherPlayerMatch1 = [regex]::Match($netherPlayerPosition1, '\[([^\]]+)\]')
        $netherPlayerMatch2 = [regex]::Match($netherPlayerPosition2, '\[([^\]]+)\]')
        if (-not $netherPlayerMatch1.Success -or -not $netherPlayerMatch2.Success -or
            $netherPlayerMatch1.Value -cne $netherPlayerMatch2.Value) {
            throw "Player positions differ in the Nether: Server 1 '$netherPlayerPosition1', Server 2 '$netherPlayerPosition2'."
        }
        Write-Output "PASS: player Nether transfer and position replicated as '$($netherPlayerMatch2.Value)'."

        $playerInOverworldCommand = "execute as $PlayerName at @s if dimension minecraft:overworld run help"
        Send-Rcon $Server1RconPort "execute in minecraft:overworld run tp $PlayerName $targetX $($y + 1) $targetZ" | Out-Null
        $returnDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $playerOverworld1 = Send-Rcon $Server1RconPort $playerInOverworldCommand
            $playerOverworld2 = Send-Rcon $Server2RconPort $playerInOverworldCommand
            if ($playerOverworld1 -match 'Index \(\d+/\d+\)' -and $playerOverworld2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $returnDeadline)
        Assert-Contains $playerOverworld1 'Index (' 'Server 1 did not return the test player to the Overworld.'
        Assert-Contains $playerOverworld2 'Index (' "Server 2 did not receive the player's return to the Overworld."
        $overworldPlayerPosition1 = Send-Rcon $Server1RconPort $playerPositionCommand
        $overworldPlayerPosition2 = Send-Rcon $Server2RconPort $playerPositionCommand
        $overworldPlayerMatch1 = [regex]::Match($overworldPlayerPosition1, '\[([^\]]+)\]')
        $overworldPlayerMatch2 = [regex]::Match($overworldPlayerPosition2, '\[([^\]]+)\]')
        if (-not $overworldPlayerMatch1.Success -or -not $overworldPlayerMatch2.Success -or
            $overworldPlayerMatch1.Value -cne $overworldPlayerMatch2.Value) {
            throw "Player positions differ after returning to the Overworld: Server 1 '$overworldPlayerPosition1', Server 2 '$overworldPlayerPosition2'."
        }
        Write-Output "PASS: player returned to the Overworld on both backends at '$($overworldPlayerMatch2.Value)'."
    }

    }

    Send-Rcon $Server1RconPort "execute in minecraft:the_nether run forceload remove $dimensionX $dimensionZ" | Out-Null
    Send-Rcon $Server2RconPort "execute in minecraft:the_nether run forceload remove $dimensionX $dimensionZ" | Out-Null

    Assert-NoNewDuplicateEntityUuidWarnings $duplicateUuidWarningsBefore
} finally {
    try { Send-Rcon $Server1RconPort "kill @e[tag=$tag]" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "kill @e[tag=${tag}_owner_remove]" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "execute in minecraft:the_nether as @e[tag=${tag}_nether] run kill @s" | Out-Null } catch { }
    if (-not [string]::IsNullOrWhiteSpace($PlayerName)) {
        try { Send-Rcon $Server1RconPort "execute in minecraft:overworld run tp $PlayerName $($x + 0.5) $($y + 1) $($z + 0.5)" | Out-Null } catch { }
    }
    if ($null -ne $dimensionX) {
        try { Send-Rcon $Server1RconPort "execute in minecraft:the_nether run kill @e[tag=${tag}_nether]" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "execute in minecraft:the_nether run kill @e[tag=${tag}_nether]" | Out-Null } catch { }
        try { Send-Rcon $Server1RconPort "execute in minecraft:the_nether run forceload remove $dimensionX $dimensionZ" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "execute in minecraft:the_nether run forceload remove $dimensionX $dimensionZ" | Out-Null } catch { }
    }
    if ($null -ne $ownerRemovalX) {
        try { Send-Rcon $Server1RconPort "kill @e[tag=${tag}_owner_remove]" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "kill @e[tag=${tag}_owner_remove]" | Out-Null } catch { }
        try { Send-Rcon $Server1RconPort "forceload remove $ownerRemovalX $ownerRemovalZ" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $ownerRemovalX $ownerRemovalZ" | Out-Null } catch { }
    }
    if ($null -ne $remoteRemovalX) {
        try { Send-Rcon $Server1RconPort "kill @e[tag=${tag}_remote_remove]" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "kill @e[tag=${tag}_remote_remove]" | Out-Null } catch { }
        try { Send-Rcon $Server1RconPort "forceload remove $remoteRemovalX $remoteRemovalZ" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $remoteRemovalX $remoteRemovalZ" | Out-Null } catch { }
    }
    try { Send-Rcon $Server1RconPort "fill $stickyPowerX $circuitY $stickyCircuitZ $stickyDestinationX $circuitY $stickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "fill $stickyPowerX $circuitY $stickyCircuitZ $stickyDestinationX $circuitY $stickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "fill $reverseStickyPowerX $reverseStickyCircuitY $reverseStickyCircuitZ $reverseStickyDestinationX $reverseStickyCircuitY $reverseStickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "fill $reverseStickyPowerX $reverseStickyCircuitY $reverseStickyCircuitZ $reverseStickyDestinationX $reverseStickyCircuitY $reverseStickyCircuitZ minecraft:air" | Out-Null } catch { }
    if ($null -ne $baseCircuitControllerPort -and $null -ne $observerX) {
        try { Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observerZ minecraft:air" | Out-Null } catch { }
        try { Send-Rcon $baseCircuitControllerPort "setblock $observerX $circuitY $observedBlockZ minecraft:air" | Out-Null } catch { }
    }
    if ($null -ne $hopperSourceX) { try { Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $hopperZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperDestinationX) { try { Send-Rcon $hopperObserverPort "setblock $hopperDestinationX $hopperY $hopperZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperSourceX) { try { Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $hopperSecondZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperDestinationX) { try { Send-Rcon $hopperObserverPort "setblock $hopperDestinationX $hopperY $hopperSecondZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperSourceX) { try { Send-Rcon $hopperControllerPort "setblock $hopperSourceX $hopperY $hopperMiddleZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperDestinationX) { try { Send-Rcon $hopperObserverPort "setblock $hopperDestinationX $hopperY $hopperMiddleZ minecraft:air" | Out-Null } catch { } }
    if ($null -ne $hopperSourceX) { try { Send-Rcon $Server1RconPort "forceload remove $hopperSourceX $hopperZ" | Out-Null } catch { } }
    if ($null -ne $hopperSourceX) { try { Send-Rcon $Server2RconPort "forceload remove $hopperSourceX $hopperZ" | Out-Null } catch { } }
    if ($null -ne $hopperDestinationX) { try { Send-Rcon $Server1RconPort "forceload remove $hopperDestinationX $hopperZ" | Out-Null } catch { } }
    if ($null -ne $hopperDestinationX) { try { Send-Rcon $Server2RconPort "forceload remove $hopperDestinationX $hopperZ" | Out-Null } catch { } }
    if ($null -ne $baseCircuitObserverPort) { try { Send-Rcon $baseCircuitObserverPort "forceload remove $reverseStickyCircuitX $reverseStickyCircuitZ" | Out-Null } catch { } }
    if ($null -ne $baseCircuitControllerPort) { try { Send-Rcon $baseCircuitControllerPort "forceload remove $reverseStickyCircuitX $reverseStickyCircuitZ" | Out-Null } catch { } }
    try { Send-Rcon $Server1RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    if ($null -ne $shulkerX) {
        try { Send-Rcon $Server1RconPort "setblock $shulkerX $y $z minecraft:air" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "setblock $shulkerX $y $z minecraft:air" | Out-Null } catch { }
        try { Send-Rcon $Server1RconPort "forceload remove $shulkerX $z" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $shulkerX $z" | Out-Null } catch { }
    }
    foreach ($chunk in $baseChunkLoads) {
        try { Send-Rcon $Server1RconPort "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
    }
}
