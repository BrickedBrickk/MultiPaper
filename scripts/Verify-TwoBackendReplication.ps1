param(
    [int]$Server1RconPort = 26010,
    [int]$Server2RconPort = 26011,
    [int]$TestX = -183,
    [int]$TestY = 102,
    [int]$TestZ = 71,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
    [string]$Server1RconPassword,
    [string]$Server2RconPassword,
    [string]$PlayerName,
    [switch]$SkipContainerCheck,
    [switch]$SkipRedstoneAndPistonCheck,
    [string]$Server1LogPath,
    [string]$Server2LogPath,
    [int]$TimeoutSeconds = 10
)

# Run this only against disposable worlds: it writes marker blocks, a chest,
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
$x = $TestX
$y = $TestY
$z = $TestZ
$block1 = "execute if block $($x + 2) $y $z minecraft:emerald_block run help"
$block2 = "execute if block $($x + 3) $y $z minecraft:diamond_block run help"
$testChunkCoordinates = @{}
for ($testBlockX = $x; $testBlockX -le $x + 5; $testBlockX++) {
    for ($testBlockZ = $z; $testBlockZ -le $z + 8; $testBlockZ++) {
        $chunkX = [Math]::Floor($testBlockX / 16)
        $chunkZ = [Math]::Floor($testBlockZ / 16)
        $chunkKey = "$chunkX,$chunkZ"
        if (-not $testChunkCoordinates.ContainsKey($chunkKey)) {
            $testChunkCoordinates[$chunkKey] = [pscustomobject]@{ X = $testBlockX; Z = $testBlockZ }
        }
    }
}
$baseChunkLoads = @($testChunkCoordinates.Values)

try {
    foreach ($chunk in $baseChunkLoads) {
        Send-Rcon $Server1RconPort "forceload add $($chunk.X) $($chunk.Z)" | Out-Null
        Send-Rcon $Server2RconPort "forceload add $($chunk.X) $($chunk.Z)" | Out-Null
    }
    $loadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $allChunksLoaded = $true
        foreach ($chunk in $baseChunkLoads) {
            $loadedCommand = "execute if loaded $($chunk.X) $y $($chunk.Z) run help"
            $loaded1 = Send-Rcon $Server1RconPort $loadedCommand
            $loaded2 = Send-Rcon $Server2RconPort $loadedCommand
            if ($loaded1 -notmatch 'Index \(\d+/\d+\)' -or $loaded2 -notmatch 'Index \(\d+/\d+\)') {
                $allChunksLoaded = $false
                break
            }
        }
        if ($allChunksLoaded) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $loadDeadline)
    if (-not $allChunksLoaded) {
        throw 'One or more test chunks did not load on both backends after forceload.'
    }
    Send-Rcon $Server1RconPort "setblock $($x + 2) $y $z minecraft:emerald_block" | Out-Null
    Send-Rcon $Server2RconPort "setblock $($x + 3) $y $z minecraft:diamond_block" | Out-Null
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
    Assert-Contains $block1On2 'Index (' 'Server 2 did not receive Server 1 block changes.'
    Assert-Contains $block2On2 'Index (' 'Server 2 could not read its block marker.'
    Assert-Contains $block2On1 'Index (' 'Server 1 did not receive Server 2 block changes.'
    Write-Output 'PASS: block changes replicate in both directions.'

    $containerX = $x + 4
    $containerCommand = "data get block $containerX $y $z Items"
    if ($SkipContainerCheck) {
        Write-Output 'SKIP: chest block entity contents check disabled by -SkipContainerCheck.'
    } else {
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
        Assert-Contains $containerItems2 'minecraft:diamond' 'Server 2 did not receive the chest block entity contents from Server 1.'
        Write-Output 'PASS: chest block entity contents replicate to Server 2.'
    }

    $circuitX = $x + 2
    $circuitY = $y + 1
    $circuitZ = $z + 1
    $stickyPistonX = $x + 1
    $stickyPowerX = $x
    $stickyPayloadX = $x + 2
    $stickyDestinationX = $x + 3
    $stickyCircuitZ = $z + 4
    $pistonX = $circuitX + 1
    $movedBlockX = $circuitX + 2
    $destinationX = $circuitX + 3
    if ($SkipRedstoneAndPistonCheck) {
        Write-Output 'SKIP: redstone and piston checks disabled by -SkipRedstoneAndPistonCheck.'
    } else {
        $pistonCommand = "execute if block $pistonX $circuitY $circuitZ minecraft:piston[facing=east,extended=true] run help"
        $retractedPistonCommand = "execute if block $pistonX $circuitY $circuitZ minecraft:piston[facing=east,extended=false] run help"
        $movedBlockCommand = "execute if block $destinationX $circuitY $circuitZ minecraft:stone run help"
        $retractedBlockCommand = "execute if block $destinationX $circuitY $circuitZ minecraft:stone run help"
        foreach ($clearX in $circuitX..$destinationX) {
            Send-Rcon $Server1RconPort "setblock $clearX $circuitY $circuitZ minecraft:air" | Out-Null
        }
        Send-Rcon $Server1RconPort "setblock $movedBlockX $circuitY $circuitZ minecraft:stone" | Out-Null
        Send-Rcon $Server1RconPort "setblock $pistonX $circuitY $circuitZ minecraft:piston[facing=east]" | Out-Null
        Send-Rcon $Server1RconPort "setblock $circuitX $circuitY $circuitZ minecraft:redstone_block" | Out-Null
        $circuitDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $pistonState1 = Send-Rcon $Server1RconPort $pistonCommand
            $pistonState2 = Send-Rcon $Server2RconPort $pistonCommand
            $movedBlock1 = Send-Rcon $Server1RconPort $movedBlockCommand
            $movedBlock2 = Send-Rcon $Server2RconPort $movedBlockCommand
            if ($pistonState1 -match 'Index \(\d+/\d+\)' -and $pistonState2 -match 'Index \(\d+/\d+\)' -and
                $movedBlock1 -match 'Index \(\d+/\d+\)' -and $movedBlock2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $circuitDeadline)
        Assert-Contains $pistonState1 'Index (' 'Server 1 piston did not extend after receiving redstone power.'
        Assert-Contains $pistonState2 'Index (' 'Server 2 did not observe the powered piston state.'
        Assert-Contains $movedBlock1 'Index (' 'Server 1 piston did not move the stone block.'
        Assert-Contains $movedBlock2 'Index (' 'Server 2 did not observe the piston-moved stone block.'
        Write-Output 'PASS: redstone-powered piston extension and block movement replicate to Server 2.'

        Send-Rcon $Server1RconPort "setblock $circuitX $circuitY $circuitZ minecraft:air" | Out-Null
        $retractDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $pistonState1 = Send-Rcon $Server1RconPort $retractedPistonCommand
            $pistonState2 = Send-Rcon $Server2RconPort $retractedPistonCommand
            $retractedBlock1 = Send-Rcon $Server1RconPort $retractedBlockCommand
            $retractedBlock2 = Send-Rcon $Server2RconPort $retractedBlockCommand
            if ($pistonState1 -match 'Index \(\d+/\d+\)' -and $pistonState2 -match 'Index \(\d+/\d+\)' -and
                $retractedBlock1 -match 'Index \(\d+/\d+\)' -and $retractedBlock2 -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $retractDeadline)
        Assert-Contains $pistonState1 'Index (' 'Server 1 piston did not retract after redstone power was removed.'
        Assert-Contains $pistonState2 'Index (' 'Server 2 did not observe the retracted piston state.'
        Assert-Contains $retractedBlock1 'Index (' 'Server 1 did not keep the moved stone at its destination after piston retraction.'
        Assert-Contains $retractedBlock2 'Index (' 'Server 2 did not observe the moved stone at its destination after piston retraction.'
        Write-Output 'PASS: redstone power removal retracts the piston and both servers retain the moved stone at its destination.'

        Test-StickyPistonPull -ControllerPort $Server1RconPort -ObserverPort $Server2RconPort -PistonX $stickyPistonX -PowerX $stickyPowerX -PayloadX $stickyPayloadX -DestinationX $stickyDestinationX -CircuitY $circuitY -CircuitZ $stickyCircuitZ -Direction 'Backend 1'
        $reverseStickyCircuitZ = $z + 8
        Test-StickyPistonPull -ControllerPort $Server2RconPort -ObserverPort $Server1RconPort -PistonX $stickyPistonX -PowerX $stickyPowerX -PayloadX $stickyPayloadX -DestinationX $stickyDestinationX -CircuitY $circuitY -CircuitZ $reverseStickyCircuitZ -Direction 'Backend 2'
    }

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

    Send-Rcon $Server1RconPort "kill @e[tag=$tag]" | Out-Null
    $removalDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $removedEntity = Send-Rcon $Server2RconPort $uuidCommand
        if ($removedEntity.IndexOf('No entity was found', [StringComparison]::OrdinalIgnoreCase) -ge 0) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $removalDeadline)
    Assert-Contains $removedEntity 'No entity was found' 'Entity removal did not reach Server 2.'
    Write-Output 'PASS: entity removal replicated to Server 2.'

    $dimensionTag = $tag + '_nether'
    Send-Rcon $Server1RconPort 'execute in minecraft:the_nether run forceload add 0 0' | Out-Null
    Send-Rcon $Server2RconPort 'execute in minecraft:the_nether run forceload add 0 0' | Out-Null
    Start-Sleep -Seconds 1
    Send-Rcon $Server1RconPort "execute in minecraft:the_nether run summon minecraft:pig 0 80 0 {Tags:[`"$dimensionTag`"],NoGravity:1b}" | Out-Null
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
        Send-Rcon $Server1RconPort "execute in minecraft:the_nether run tp $PlayerName 0 80 0" | Out-Null
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

    Send-Rcon $Server1RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null

    Assert-NoNewDuplicateEntityUuidWarnings $duplicateUuidWarningsBefore
} finally {
    try { Send-Rcon $Server1RconPort "kill @e[tag=$tag]" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "execute in minecraft:the_nether as @e[tag=${tag}_nether] run kill @s" | Out-Null } catch { }
    if (-not [string]::IsNullOrWhiteSpace($PlayerName)) {
        try { Send-Rcon $Server1RconPort "execute in minecraft:overworld run tp $PlayerName $($x + 0.5) $($y + 1) $($z + 0.5)" | Out-Null } catch { }
    }
    try { Send-Rcon $Server1RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "fill $stickyPowerX $circuitY $stickyCircuitZ $stickyDestinationX $circuitY $stickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "fill $stickyPowerX $circuitY $stickyCircuitZ $stickyDestinationX $circuitY $stickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "fill $stickyPowerX $circuitY $reverseStickyCircuitZ $stickyDestinationX $circuitY $reverseStickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "fill $stickyPowerX $circuitY $reverseStickyCircuitZ $stickyDestinationX $circuitY $reverseStickyCircuitZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    foreach ($chunk in $baseChunkLoads) {
        try { Send-Rcon $Server1RconPort "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
    }
}
