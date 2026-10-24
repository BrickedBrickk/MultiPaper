param(
    [Parameter(Mandatory=$true)][int]$Server1RconPort,
    [Parameter(Mandatory=$true)][int]$Server2RconPort,
    [Parameter(Mandatory=$true)][string]$RconPassword,
    [int]$X = 16005,
    [int]$Z = 16005,
    [int]$HoldSeconds = 300,
    [int]$UnloadTimeoutSeconds = 180,
    [int]$TimeoutSeconds = 20
)

# Disposable two-backend probe. Builds a bubble-column/trapdoor pearl chamber,
# confirms stable replication, removes explicit force-load tickets, verifies the
# active pearl keeps its chunk ticking, then removes the pearl and confirms the
# chamber chunk can unload and reload with its block state intact.
$ErrorActionPreference = 'Stop'
if ($HoldSeconds -lt 30 -or $UnloadTimeoutSeconds -lt 30 -or $TimeoutSeconds -lt 2) {
    throw 'HoldSeconds and UnloadTimeoutSeconds must be at least 30; TimeoutSeconds must be at least 2.'
}

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
    return [pscustomobject]@{
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
            if ($reply.Id -ne $packet.Id) { throw "Unexpected RCON response id $($reply.Id) on port ${Port}." }
            if ($packet.Type -eq 2) { return $reply.Text }
        }
    } finally {
        $client.Dispose()
    }
}

function Wait-Loaded([int]$Port, [string]$Label, [bool]$Expected) {
    $command = "execute if loaded $X 80 $Z run help"
    $deadline = [DateTime]::UtcNow.AddSeconds($UnloadTimeoutSeconds)
    do {
        $reply = Send-Rcon $Port $command
        $isLoaded = $reply -match 'Index \(\d+/\d+\)'
        if ($isLoaded -eq $Expected) { return $reply }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    $state = if ($Expected) { 'loaded' } else { 'unloaded' }
    throw "$Label chunk did not become $state within $UnloadTimeoutSeconds seconds; last command reply: '$reply'."
}

function Read-Pearl([int]$Port, [string]$Command, [string]$Label) {
    $reply = Send-Rcon $Port $Command
    $match = [regex]::Match($reply, '\[I;\s*-?\d+,\s*-?\d+,\s*-?\d+,\s*-?\d+\]')
    if (-not $match.Success) { throw "$Label did not report the test pearl UUID: '$reply'." }
    return $match.Value
}

function Read-PearlY([int]$Port, [string]$Command, [string]$Label) {
    $reply = Send-Rcon $Port $Command
    $match = [regex]::Match($reply, '\[(-?\d+(?:\.\d+)?)[dD]?,\s*(-?\d+(?:\.\d+)?)[dD]?,\s*(-?\d+(?:\.\d+)?)[dD]?\]')
    if (-not $match.Success) { throw "$Label did not report a readable pearl position: '$reply'." }
    return [double]::Parse($match.Groups[2].Value,[Globalization.CultureInfo]::InvariantCulture)
}

$zTop = 90
$chamberRadius = 2
$minX = $X - $chamberRadius
$maxX = $X + $chamberRadius
$minZ = $Z - $chamberRadius
$maxZ = $Z + $chamberRadius
$tag = 'stasis_chunk_reload_' + [Guid]::NewGuid().ToString('N')
$uuidCommand = "data get entity @e[type=minecraft:ender_pearl,tag=$tag,limit=1] UUID"
$posCommand = "data get entity @e[type=minecraft:ender_pearl,tag=$tag,limit=1] Pos"
$ticketsAdded = $false
$pearlSpawned = $false

try {
    Send-Rcon $Server2RconPort "forceload add $X $Z" | Out-Null
    Send-Rcon $Server1RconPort "forceload add $X $Z" | Out-Null
    $ticketsAdded = $true
    Wait-Loaded $Server2RconPort 'Backend 2' $true | Out-Null
    Wait-Loaded $Server1RconPort 'Backend 1' $true | Out-Null

    Send-Rcon $Server2RconPort "fill $minX 59 $minZ $maxX 59 $maxZ minecraft:soul_sand" | Out-Null
    Send-Rcon $Server2RconPort "fill $minX 60 $minZ $maxX 89 $maxZ minecraft:water" | Out-Null
    Send-Rcon $Server2RconPort "fill $minX 60 $minZ $maxX 60 $maxZ minecraft:kelp" | Out-Null
    Send-Rcon $Server2RconPort "fill $minX 60 $minZ $maxX 60 $maxZ minecraft:water" | Out-Null
    Send-Rcon $Server2RconPort "fill $minX $zTop $minZ $maxX $zTop $maxZ minecraft:oak_trapdoor[half=top,waterlogged=true]" | Out-Null
    $bubbleCommand = "execute if block $X 80 $Z minecraft:bubble_column run help"
    $bubbleDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $bubble = Send-Rcon $Server2RconPort $bubbleCommand
        if ($bubble -match 'Index \(\d+/\d+\)') { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $bubbleDeadline)
    if ($bubble -notmatch 'Index \(\d+/\d+\)') { throw "Test chamber did not form a bubble column within $TimeoutSeconds seconds: '$bubble'." }

    Send-Rcon $Server2RconPort "summon minecraft:ender_pearl $($X + 0.5) 80.5 $($Z + 0.5) {Motion:[0.0d,0.0d,0.0d],Tags:[`"$tag`"]}" | Out-Null
    $pearlSpawned = $true
    $uuid2 = Read-Pearl $Server2RconPort $uuidCommand 'Backend 2'
    $spawnDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try { $uuid1 = Read-Pearl $Server1RconPort $uuidCommand 'Backend 1'; break }
        catch { Start-Sleep -Milliseconds 250 }
    } while ([DateTime]::UtcNow -lt $spawnDeadline)
    if (-not $uuid1 -or $uuid1 -cne $uuid2) { throw "Initial pearl UUID did not replicate: Backend 1 '$uuid1'; Backend 2 '$uuid2'." }
    $stasisUuid = $uuid2
    Write-Output "PEARL_UUID $uuid2"
    Write-Output 'PASS: the same stasis pearl UUID is visible on both loaded backends.'

    $settleDeadline = [DateTime]::UtcNow.AddSeconds($UnloadTimeoutSeconds)
    do {
        $pearlY = Read-PearlY $Server2RconPort $posCommand 'Backend 2'
        if ($pearlY -ge 89.0 -and $pearlY -le 91.0) { break }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $settleDeadline)
    if ($pearlY -lt 89.0 -or $pearlY -gt 91.0) { throw "Pearl did not settle in the trapdoor pocket; final y=$pearlY." }
    Write-Output "PEARL_SETTLED_Y $pearlY"

    $holdDeadline = [DateTime]::UtcNow.AddSeconds($HoldSeconds)
    $samples = 0
    while ([DateTime]::UtcNow -lt $holdDeadline) {
        $uuid2 = Read-Pearl $Server2RconPort $uuidCommand 'Backend 2'
        $uuid1 = Read-Pearl $Server1RconPort $uuidCommand 'Backend 1'
        if ($uuid1 -cne $uuid2 -or $uuid2 -cne $uuid1) { throw "Pearl UUID diverged during hold: Backend 1 '$uuid1'; Backend 2 '$uuid2'." }
        $pearlY = Read-PearlY $Server2RconPort $posCommand 'Backend 2'
        if ($pearlY -lt 89.0 -or $pearlY -gt 91.0) { throw "Pearl left the stasis pocket during hold at y=$pearlY." }
        $samples++
        Start-Sleep -Seconds 5
    }
    Write-Output "PASS: pearl remained in the stasis pocket and replicated for $HoldSeconds seconds ($samples samples)."

    Send-Rcon $Server1RconPort "forceload remove $X $Z" | Out-Null
    Send-Rcon $Server2RconPort "forceload remove $X $Z" | Out-Null
    $ticketsAdded = $false
    Wait-Loaded $Server2RconPort 'Backend 2 with active pearl' $true | Out-Null
    Wait-Loaded $Server1RconPort 'Backend 1 with active pearl' $true | Out-Null
    Start-Sleep -Seconds 10
    $uuid1 = Read-Pearl $Server1RconPort $uuidCommand 'Backend 1 after force-load removal'
    $uuid2 = Read-Pearl $Server2RconPort $uuidCommand 'Backend 2 after force-load removal'
    if ($uuid1 -cne $stasisUuid -or $uuid2 -cne $stasisUuid) {
        throw "Pearl disappeared or changed after explicit force-load tickets were removed. Expected '$stasisUuid'; Backend 1 '$uuid1'; Backend 2 '$uuid2'."
    }
    Wait-Loaded $Server2RconPort 'Backend 2 with active pearl' $true | Out-Null
    Wait-Loaded $Server1RconPort 'Backend 1 with active pearl' $true | Out-Null
    Write-Output 'PASS: the active pearl retained its ticking chunk after both explicit force-load tickets were removed.'

    Send-Rcon $Server2RconPort "kill @e[type=minecraft:ender_pearl,tag=$tag]" | Out-Null
    $pearlSpawned = $false
    Wait-Loaded $Server2RconPort 'Backend 2 after pearl removal' $false | Out-Null
    Wait-Loaded $Server1RconPort 'Backend 1 after pearl removal' $false | Out-Null
    Write-Output 'PASS: after pearl removal, both backends confirmed the chunk unloaded.'

    Send-Rcon $Server2RconPort "forceload add $X $Z" | Out-Null
    Send-Rcon $Server1RconPort "forceload add $X $Z" | Out-Null
    $ticketsAdded = $true
    Wait-Loaded $Server2RconPort 'Backend 2 after reload' $true | Out-Null
    Wait-Loaded $Server1RconPort 'Backend 1 after reload' $true | Out-Null
    $reloadedBubble1 = Send-Rcon $Server1RconPort "execute if block $X 80 $Z minecraft:bubble_column run help"
    $reloadedBubble2 = Send-Rcon $Server2RconPort "execute if block $X 80 $Z minecraft:bubble_column run help"
    if ($reloadedBubble1 -notmatch 'Index \(\d+/\d+\)' -or $reloadedBubble2 -notmatch 'Index \(\d+/\d+\)') {
        throw "Bubble column block state did not persist across chunk unload/reload: Backend 1 '$reloadedBubble1'; Backend 2 '$reloadedBubble2'."
    }
    $reloadedPearl1 = Send-Rcon $Server1RconPort $uuidCommand
    $reloadedPearl2 = Send-Rcon $Server2RconPort $uuidCommand
    if ($reloadedPearl1 -match '\[I;' -or $reloadedPearl2 -match '\[I;') {
        throw "Removed pearl unexpectedly returned after chunk reload: Backend 1 '$reloadedPearl1'; Backend 2 '$reloadedPearl2'."
    }
    Write-Output 'PASS: after a confirmed unload/reload, the chamber blocks persisted and the removed pearl did not reappear.'
} finally {
    if ($pearlSpawned) {
        try { Send-Rcon $Server2RconPort "kill @e[type=minecraft:ender_pearl,tag=$tag]" | Out-Null } catch { }
        try { Send-Rcon $Server1RconPort "kill @e[type=minecraft:ender_pearl,tag=$tag]" | Out-Null } catch { }
    }
    if ($ticketsAdded) {
        try { Send-Rcon $Server1RconPort "forceload remove $X $Z" | Out-Null } catch { }
        try { Send-Rcon $Server2RconPort "forceload remove $X $Z" | Out-Null } catch { }
    }
    try { Send-Rcon $Server2RconPort "fill $minX 59 $minZ $maxX $zTop $maxZ minecraft:air" | Out-Null } catch { }
    try { Send-Rcon $Server1RconPort "fill $minX 59 $minZ $maxX $zTop $maxZ minecraft:air" | Out-Null } catch { }
}
