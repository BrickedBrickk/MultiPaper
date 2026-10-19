param(
    [int]$Server1RconPort = 26010,
    [int]$Server2RconPort = 26011,
    [Parameter(Mandatory=$true)][string]$Server1RconPassword,
    [Parameter(Mandatory=$true)][string]$Server2RconPassword,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'

function Send-Rcon([int]$Port, [string]$Command) {
    $password = if ($Port -eq $Server1RconPort) { $Server1RconPassword } else { $Server2RconPassword }
    $result = & $helper -Server 127.0.0.1 -Port $Port -Password $password -Command $Command
    return ($result -join '')
}

function Assert-Present([string]$Actual, [string]$Description) {
    if ($Actual -notmatch 'Index \(\d+/\d+\)') {
        throw "$Description was not visible on both backends. RCON returned '$Actual'."
    }
}

$random = [Random]::new()
$cases = @(
    [pscustomobject]@{ Name='Overworld, Backend 1 owner'; Dimension='minecraft:overworld'; SourcePort=$Server1RconPort; ChunkX=$random.Next(80, 900); ChunkZ=-$random.Next(80, 900); Y=300; Block='minecraft:emerald_block' },
    [pscustomobject]@{ Name='Overworld, Backend 2 owner'; Dimension='minecraft:overworld'; SourcePort=$Server2RconPort; ChunkX=-$random.Next(80, 900); ChunkZ=$random.Next(80, 900); Y=300; Block='minecraft:diamond_block' },
    [pscustomobject]@{ Name='Nether, Backend 2 owner'; Dimension='minecraft:the_nether'; SourcePort=$Server2RconPort; ChunkX=$random.Next(80, 900); ChunkZ=-$random.Next(80, 900); Y=200; Block='minecraft:emerald_block' }
)

try {
    foreach ($case in $cases) {
        $x = $case.ChunkX * 16 + 5
        $z = $case.ChunkZ * 16 + 8
        $observerPort = if ($case.SourcePort -eq $Server1RconPort) { $Server2RconPort } else { $Server1RconPort }
        $loadCommand = "execute in $($case.Dimension) if loaded $x $($case.Y) $z run help"
        $blockCommand = "execute in $($case.Dimension) if block $x $($case.Y) $z $($case.Block) run help"
        $airCommand = "execute in $($case.Dimension) if block $x $($case.Y) $z minecraft:air run help"
        $forceloadAdd = "execute in $($case.Dimension) run forceload add $x $z"
        $forceloadRemove = "execute in $($case.Dimension) run forceload remove $x $z"
        Write-Output ('Testing {0} at {1}, chunk [{2},{3}], marker [{4},{5},{6}] ({7}).' -f $case.Name,$case.Dimension,$case.ChunkX,$case.ChunkZ,$x,$case.Y,$z,$case.Block)

        Send-Rcon $case.SourcePort $forceloadAdd | Out-Null
        $sourceDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $sourceLoaded = Send-Rcon $case.SourcePort $loadCommand
            if ($sourceLoaded -match 'Index \(') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $sourceDeadline)
        Assert-Present $sourceLoaded "$($case.Name) source chunk load"

        Send-Rcon $observerPort $forceloadAdd | Out-Null
        $loadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $sourceLoaded = Send-Rcon $case.SourcePort $loadCommand
            $observerLoaded = Send-Rcon $observerPort $loadCommand
            if ($sourceLoaded -match 'Index \(' -and $observerLoaded -match 'Index \(') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $loadDeadline)
        Assert-Present $sourceLoaded "$($case.Name) source load"
        Assert-Present $observerLoaded "$($case.Name) observer load"

        # A chunk becomes queryable before its Master/peer subscriber handshake finishes.
        # Let both backends finish registering as subscribers before testing block updates.
        Start-Sleep -Seconds 2

        Send-Rcon $case.SourcePort "execute in $($case.Dimension) run setblock $x $($case.Y) $z minecraft:air" | Out-Null
        $airDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $sourceAir = Send-Rcon $case.SourcePort $airCommand
            $observerAir = Send-Rcon $observerPort $airCommand
            if ($sourceAir -match 'Index \(' -and $observerAir -match 'Index \(') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $airDeadline)
        Assert-Present $sourceAir "$($case.Name) source marker reset"
        Assert-Present $observerAir "$($case.Name) observer marker reset"

        $placeResult = Send-Rcon $case.SourcePort "execute in $($case.Dimension) run setblock $x $($case.Y) $z $($case.Block)"
        Write-Output ("Placement response from source backend: '{0}'." -f $placeResult)

        $replicationDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $sourceBlock = Send-Rcon $case.SourcePort $blockCommand
            $observerBlock = Send-Rcon $observerPort $blockCommand
            if ($sourceBlock -match 'Index \(' -and $observerBlock -match 'Index \(') { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $replicationDeadline)
        Assert-Present $sourceBlock "$($case.Name) source marker"
        Assert-Present $observerBlock "$($case.Name) replicated marker on peer backend"
        Write-Output "PASS: $($case.Name) block update replicated from its owner to the peer backend."

        Send-Rcon $case.SourcePort "execute in $($case.Dimension) run setblock $x $($case.Y) $z minecraft:air" | Out-Null
        Send-Rcon $case.SourcePort $forceloadRemove | Out-Null
        Send-Rcon $observerPort $forceloadRemove | Out-Null
    }
} finally {
    foreach ($case in $cases) {
        $x = $case.ChunkX * 16 + 5
        $z = $case.ChunkZ * 16 + 8
        $observerPort = if ($case.SourcePort -eq $Server1RconPort) { $Server2RconPort } else { $Server1RconPort }
        $forceloadRemove = "execute in $($case.Dimension) run forceload remove $x $z"
        try { Send-Rcon $case.SourcePort "execute in $($case.Dimension) run setblock $x $($case.Y) $z minecraft:air" | Out-Null } catch { }
        try { Send-Rcon $case.SourcePort $forceloadRemove | Out-Null } catch { }
        try { Send-Rcon $observerPort $forceloadRemove | Out-Null } catch { }
    }
}
