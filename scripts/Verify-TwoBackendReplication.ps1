param(
    [int]$Server1RconPort = 26010,
    [int]$Server2RconPort = 26011,
    [int]$TestX = -194,
    [int]$TestY = 102,
    [int]$TestZ = 71,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
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

if ([string]::IsNullOrWhiteSpace($RconPassword)) {
    throw 'Pass -RconPassword or set MULTIPAPER_RCON_PASSWORD.'
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
    $client = [System.Net.Sockets.TcpClient]::new()
    $client.ReceiveTimeout = $TimeoutSeconds * 1000
    $client.SendTimeout = $TimeoutSeconds * 1000
    try {
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $authId = Get-Random -Minimum 10000 -Maximum 2000000000
        $commandId = $authId + 1
        foreach ($packet in @(
            @{ Id = $authId; Type = 3; Text = $RconPassword },
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

$tag = 'multipaper_regression_' + [Guid]::NewGuid().ToString('N')
$x = $TestX
$y = $TestY
$z = $TestZ
$block1 = "execute if block $($x + 2) $y $z minecraft:emerald_block run help"
$block2 = "execute if block $($x + 3) $y $z minecraft:diamond_block run help"

try {
    Send-Rcon $Server1RconPort "forceload add $x $z" | Out-Null
    Send-Rcon $Server2RconPort "forceload add $x $z" | Out-Null
    $loadedCommand = "execute if loaded $x $y $z run help"
    $loadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $loaded1 = Send-Rcon $Server1RconPort $loadedCommand
        $loaded2 = Send-Rcon $Server2RconPort $loadedCommand
        if ($loaded1 -match 'Index \(\d+/\d+\)' -and $loaded2 -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $loadDeadline)
    Assert-Contains $loaded1 'Index (' 'Server 1 did not load the test chunk after forceload.'
    Assert-Contains $loaded2 'Index (' 'Server 2 did not load the test chunk after forceload.'
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
    Start-Sleep -Seconds 2
    $dimensionRemoval = Send-Rcon $Server2RconPort $dimensionUuidCommand
    if ([regex]::IsMatch($dimensionRemoval, '\[I;[^\]]+\]')) {
        throw "Entity removal in the Nether did not reach Server 2. Remaining UUID response: '$dimensionRemoval'."
    }
    Write-Output 'PASS: Nether entity removal replicated to Server 2.'
    Send-Rcon $Server1RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null

    Assert-NoNewDuplicateEntityUuidWarnings $duplicateUuidWarningsBefore
} finally {
    try { Send-Rcon $Server1RconPort "kill @e[tag=$tag]" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "execute in minecraft:the_nether as @e[tag=${tag}_nether] run kill @s" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort 'execute in minecraft:the_nether run forceload remove 0 0' | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "forceload remove $entityTargetBlockX $entityTargetBlockZ" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "forceload remove $x $z" | Out-Null } catch { }
    try { Send-Rcon $Server2RconPort "forceload remove $x $z" | Out-Null } catch { }
}
