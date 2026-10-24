param(
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [Parameter(Mandatory = $true)][string]$PlayerName,
    [Parameter(Mandatory = $true)][string]$RconPassword,
    [int]$BackendARconPort = 29831,
    [int]$BackendBRconPort = 29832,
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') {
    throw "Refusing live-update probe outside a named disposable MultiPaper fixture: $root"
}
if ($PlayerName -notmatch '^[A-Za-z0-9_]{1,16}$') { throw 'PlayerName must be a Minecraft username.' }

$rcon = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'
$properties = Get-Content -LiteralPath (Join-Path $root 'backend-a/server.properties') -Raw
$worldMatch = [regex]::Match($properties, '(?m)^level-name=(.+)$')
if (-not $worldMatch.Success) { throw 'Could not read the disposable world name.' }
$world = $worldMatch.Groups[1].Value.Trim()

function Invoke-Rcon([int]$port, [string]$command) {
    $result = & $rcon -Server 127.0.0.1 -Port $port -Password $RconPassword -Command $command
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "RCON command failed on ${port}: $command" }
    return ($result | Out-String).Trim()
}

function Get-Position([int]$port) {
    $reply = Invoke-Rcon $port "data get entity $PlayerName Pos"
    $match = [regex]::Match($reply, '\[(-?\d+(?:\.\d+)?)d, (-?\d+(?:\.\d+)?)d, (-?\d+(?:\.\d+)?)d\]')
    if (-not $match.Success) { throw "Player $PlayerName is not visible on RCON port ${port}: $reply" }
    return [pscustomobject]@{
        X = [double]$match.Groups[1].Value
        Y = [double]$match.Groups[2].Value
        Z = [double]$match.Groups[3].Value
    }
}

function Get-ChunkState([int]$port, [int]$chunkX, [int]$chunkZ) {
    return Invoke-Rcon $port "ticklockprobe state $world $chunkX $chunkZ"
}

function Wait-Owner([int]$expectedPort, [int]$chunkX, [int]$chunkZ) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $stateA = Get-ChunkState $BackendARconPort $chunkX $chunkZ
        $stateB = Get-ChunkState $BackendBRconPort $chunkX $chunkZ
        $ownerA = $stateA -match 'ownerIsMe=true'
        $ownerB = $stateB -match 'ownerIsMe=true'
        $expectedOwner = if ($expectedPort -eq $BackendARconPort) { $ownerA -and -not $ownerB } else { $ownerB -and -not $ownerA }
        if ($expectedOwner -and $stateA -match 'subscribers=1' -and $stateB -match 'subscribers=1') { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Owner/subscriber state not ready. A='$stateA' B='$stateB'"
}

function Assert-PeerBlock([int]$port, [int]$x, [int]$y, [int]$z, [string]$block, [string]$context) {
    $command = "execute if block $x $y $z $block run help"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $reply = Invoke-Rcon $port $command
        if ($reply -match 'Help:') { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "${context}: peer did not observe $block at $x,$y,$z. Reply='$reply'"
}

function Assert-PeerChest([int]$port, [int]$x, [int]$y, [int]$z, [string]$context) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $block = Invoke-Rcon $port "execute if block $x $y $z minecraft:chest run help"
        $items = Invoke-Rcon $port "data get block $x $y $z Items"
        if ($block -match 'Help:' -and $items -match 'minecraft:diamond' -and $items -match 'count: 5') { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "${context}: peer chest state did not converge. Block='$block' Items='$items'"
}

$ports = @($BackendARconPort, $BackendBRconPort)
$initialOwner = $null
$ticketAdded = @()
try {
    foreach ($port in $ports) {
        $list = Invoke-Rcon $port 'list'
        if ($list -notmatch [regex]::Escape($PlayerName)) { throw "Player $PlayerName must be online on both backends before this live-chunk probe." }
    }
    $posA = Get-Position $BackendARconPort
    $posB = Get-Position $BackendBRconPort
    $chunkX = [Math]::Floor($posA.X / 16)
    $chunkZ = [Math]::Floor($posA.Z / 16)
    if ([Math]::Floor($posB.X / 16) -ne $chunkX -or [Math]::Floor($posB.Z / 16) -ne $chunkZ) {
        throw "Player chunk differs between backends: A=($($posA.X),$($posA.Z)) B=($($posB.X),$($posB.Z))."
    }

    $ticketX = $chunkX * 16 + 4
    $ticketZ = $chunkZ * 16 + 4
    foreach ($port in $ports) {
        Invoke-Rcon $port "forceload add $ticketX $ticketZ" | Out-Null
        $ticketAdded += $port
    }
    $stateDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $initialStateA = Get-ChunkState $BackendARconPort $chunkX $chunkZ
        $initialStateB = Get-ChunkState $BackendBRconPort $chunkX $chunkZ
        $ownerA = $initialStateA -match 'ownerIsMe=true'
        $ownerB = $initialStateB -match 'ownerIsMe=true'
        if (($ownerA -xor $ownerB) -and $initialStateA -match 'subscribers=1' -and $initialStateB -match 'subscribers=1') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $stateDeadline)
    if (-not (($ownerA -xor $ownerB) -and $initialStateA -match 'subscribers=1' -and $initialStateB -match 'subscribers=1')) {
        throw "Could not establish a unique owner and peer subscriber in player chunk $chunkX,$chunkZ. A='$initialStateA' B='$initialStateB'"
    }
    $initialOwner = if ($ownerA) { $BackendARconPort } else { $BackendBRconPort }

    $y = [Math]::Clamp([Math]::Floor($posA.Y) + 20, -60, 310)
    $markerX = $ticketX
    $chestX = $ticketX + 1
    $markerZ = $ticketZ
    $markerY = $y
    $chestY = $y
    foreach ($targetPort in @($BackendARconPort, $BackendBRconPort)) {
        $currentOwner = if ($targetPort -eq $BackendARconPort) { $BackendBRconPort } else { $BackendARconPort }
        if ($targetPort -ne $initialOwner) {
            Invoke-Rcon $targetPort "ticklockprobe lock $world $chunkX $chunkZ" | Out-Null
            Invoke-Rcon $currentOwner "ticklockprobe unlock $world $chunkX $chunkZ" | Out-Null
            Wait-Owner $targetPort $chunkX $chunkZ
        }
        $peerPort = if ($targetPort -eq $BackendARconPort) { $BackendBRconPort } else { $BackendARconPort }
        $marker = if ($targetPort -eq $BackendARconPort) { 'minecraft:emerald_block' } else { 'minecraft:gold_block' }
        Invoke-Rcon $targetPort "setblock $markerX $markerY $markerZ $marker" | Out-Null
        Assert-PeerBlock $peerPort $markerX $markerY $markerZ $marker "Owner port $targetPort marker update"
        Invoke-Rcon $targetPort "setblock $chestX $chestY $markerZ minecraft:chest" | Out-Null
        Invoke-Rcon $targetPort "item replace block $chestX $chestY $markerZ container.0 with minecraft:diamond 5" | Out-Null
        Assert-PeerChest $peerPort $chestX $chestY $markerZ "Owner port $targetPort chest update"
        Write-Output "PASS: owner port $targetPort replicated a live block update and chest contents to subscribed peer $peerPort while $PlayerName occupied chunk $chunkX,$chunkZ."

        Invoke-Rcon $targetPort "setblock $markerX $markerY $markerZ minecraft:air" | Out-Null
        Invoke-Rcon $targetPort "setblock $chestX $chestY $markerZ minecraft:air" | Out-Null
    }
} finally {
    if ($null -ne $initialOwner) {
        $currentOwner = if ($initialOwner -eq $BackendARconPort) { $BackendBRconPort } else { $BackendARconPort }
        try {
            Invoke-Rcon $initialOwner "ticklockprobe lock $world $chunkX $chunkZ" | Out-Null
            Invoke-Rcon $currentOwner "ticklockprobe unlock $world $chunkX $chunkZ" | Out-Null
        } catch { }
    }
    foreach ($port in $ticketAdded) {
        try { Invoke-Rcon $port "forceload remove $ticketX $ticketZ" | Out-Null } catch { }
    }
}
