param(
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [int]$BackendARconPort = 29831,
    [int]$BackendBRconPort = 29832,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
    [string]$JavaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [ValidateRange(1, 10)][int]$OwnershipTransferCount = 4,
    [int]$X = 23000,
    [int]$Y = 102,
    [int]$Z = 23000
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RconPassword)) { throw 'Provide -RconPassword or MULTIPAPER_RCON_PASSWORD.' }
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') {
    throw "Refusing to run outside a disposable MultiPaper fixture: $root"
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }

$rconScript = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'
$crashedBackend = $null
$tag = 'mp_entity_crash_' + [Guid]::NewGuid().ToString('N')
$entityCountObjective = 'ec' + [Guid]::NewGuid().ToString('N').Substring(0, 12)

function Invoke-Rcon([int]$port, [string]$command) {
    $result = & $rconScript -Server 127.0.0.1 -Port $port -Password $RconPassword -Command $command
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "RCON command failed on ${port}: $command" }
    return ($result | Out-String).Trim()
}

function Get-BackendConfig([string]$name) {
    $backendRoot = Join-Path $root $name
    $propertiesPath = Join-Path $backendRoot 'server.properties'
    $properties = Get-Content -LiteralPath $propertiesPath -Raw
    $levelMatch = [regex]::Match($properties, '(?m)^level-name=(.+)$')
    $gamePortMatch = [regex]::Match($properties, '(?m)^server-port=(\d+)\r?$')
    $rconPortMatch = [regex]::Match($properties, '(?m)^rcon\.port=(\d+)\r?$')
    $serverNameMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $backendRoot 'multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)')
    if (-not $levelMatch.Success -or -not $gamePortMatch.Success -or -not $rconPortMatch.Success -or -not $serverNameMatch.Success) {
        throw "Could not read world, ports, or MultiPaper name from $backendRoot."
    }
    return [pscustomobject]@{
        Name = $name
        Root = $backendRoot
        World = $levelMatch.Groups[1].Value.Trim()
        GamePort = [int]$gamePortMatch.Groups[1].Value
        RconPort = [int]$rconPortMatch.Groups[1].Value
        ServerName = $serverNameMatch.Groups[1].Value
    }
}

function Wait-Listener([int]$port, [int]$seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        Start-Sleep -Milliseconds 500
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    } while (-not $listener -and [DateTime]::UtcNow -lt $deadline)
    if (-not $listener) { throw "Timed out waiting for disposable port $port." }
}

function Wait-Loaded([int]$port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $command = "execute if loaded $X $Y $Z run help"
    do {
        if ((Invoke-Rcon $port $command) -match 'Help:') { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Test chunk did not load on disposable RCON port $port."
}

function Get-OwnerPort([int]$chunkX, [int]$chunkZ) {
    $stateA = Invoke-Rcon $BackendA.RconPort "ticklockprobe state $($BackendA.World) $chunkX $chunkZ"
    $stateB = Invoke-Rcon $BackendB.RconPort "ticklockprobe state $($BackendB.World) $chunkX $chunkZ"
    $ownerA = $stateA -match 'ownerIsMe=true'
    $ownerB = $stateB -match 'ownerIsMe=true'
    if ($ownerA -eq $ownerB) { throw "Expected exactly one chunk owner. A='$stateA' B='$stateB'" }
    if ($ownerA) { return $BackendA.RconPort }
    return $BackendB.RconPort
}

function Wait-Owner([int]$targetPort, [int]$chunkX, [int]$chunkZ) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        try { $owner = Get-OwnerPort $chunkX $chunkZ } catch { $owner = 0 }
        if ($owner -eq $targetPort) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Chunk ownership did not transfer to backend RCON port $targetPort."
}

function Wait-EntitySubscriptions([int]$chunkX, [int]$chunkZ) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $expected = 'entitiesReady=true entitySubscribers=1 currentChunk=true entityChunk=true'
    do {
        $stateA = Invoke-Rcon $BackendA.RconPort "ticklockprobe entitydebug $($BackendA.World) $chunkX $chunkZ"
        $stateB = Invoke-Rcon $BackendB.RconPort "ticklockprobe entitydebug $($BackendB.World) $chunkX $chunkZ"
        if ($stateA -match [regex]::Escape($expected) -and $stateB -match [regex]::Escape($expected)) {
            Write-Output "Entity readiness confirmed on both backends: A='$stateA' B='$stateB'."
            return
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Entity chunk subscriptions did not become ready before spawn. A='$stateA' B='$stateB'"
}

function Get-TaggedEntityCount([int]$port) {
    Invoke-Rcon $port "scoreboard objectives add $entityCountObjective dummy" | Out-Null
    Invoke-Rcon $port "scoreboard players set #entityCount $entityCountObjective 0" | Out-Null
    Invoke-Rcon $port "execute positioned $X $Y $Z as @e[tag=$tag,distance=..4] run scoreboard players add #entityCount $entityCountObjective 1" | Out-Null
    $reply = Invoke-Rcon $port "scoreboard players get #entityCount $entityCountObjective"
    $match = [regex]::Match($reply, 'has (\d+) \[')
    if (-not $match.Success) { throw "Could not count tag '$tag' on port ${port}: $reply" }
    return [int]$match.Groups[1].Value
}

function Assert-Count([int]$port, [int]$expected, [string]$context) {
    $actual = Get-TaggedEntityCount $port
    if ($actual -ne $expected) { throw "$context expected $expected tagged entities on port ${port}, found $actual." }
}

function Get-EntityUuid([int]$port) {
    $reply = Invoke-Rcon $port "data get entity @e[tag=$tag,limit=1] UUID"
    $match = [regex]::Match($reply, '\[I;[^\]]+\]')
    if (-not $match.Success) { throw "Entity UUID not visible on port ${port}: $reply" }
    return $match.Value
}

function Stop-DisposableBackend([int]$gamePort) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $gamePort -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { throw "Backend game port $gamePort is not listening before crash injection." }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    $expectedJava = (Resolve-Path -LiteralPath $JavaPath).Path
    if (-not $process -or $process.CommandLine -notmatch '(?i)server\.jar' -or $process.ExecutablePath -ne $expectedJava) {
        throw "Refusing to stop unverified process on disposable game port $gamePort."
    }
    Stop-Process -Id $process.ProcessId -Force
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        $listener = Get-NetTCPConnection -State Listen -LocalPort $gamePort -ErrorAction SilentlyContinue
    } while ($listener -and [DateTime]::UtcNow -lt $deadline)
    if ($listener) { throw "Disposable backend on $gamePort did not stop." }
}

function Start-DisposableBackend($backend) {
    $arguments = @('-jar', 'server.jar', 'nogui')
    Start-Process -FilePath $JavaPath -WorkingDirectory $backend.Root -ArgumentList $arguments -WindowStyle Hidden -PassThru | Out-Null
    Wait-Listener $backend.GamePort 90
    Wait-Listener $backend.RconPort 90
    Start-Sleep -Seconds 2
}

function Transfer-Ownership([int]$currentOwnerPort, [int]$targetPort, [int]$chunkX, [int]$chunkZ) {
    $target = if ($targetPort -eq $BackendA.RconPort) { $BackendA } else { $BackendB }
    $current = if ($currentOwnerPort -eq $BackendA.RconPort) { $BackendA } else { $BackendB }
    Invoke-Rcon $targetPort "ticklockprobe lock $($target.World) $chunkX $chunkZ" | Out-Null
    Invoke-Rcon $currentOwnerPort "ticklockprobe unlock $($current.World) $chunkX $chunkZ" | Out-Null
    Wait-Owner $targetPort $chunkX $chunkZ
}

$BackendA = Get-BackendConfig 'backend-a'
$BackendB = Get-BackendConfig 'backend-b'
if ($BackendA.World -ne $BackendB.World) { throw "Backends are configured for different worlds: '$($BackendA.World)' and '$($BackendB.World)'." }
if ($BackendA.RconPort -ne $BackendARconPort -or $BackendB.RconPort -ne $BackendBRconPort) {
    throw "Configured disposable RCON ports do not match the requested fixture ports."
}
$chunkX = [Math]::Floor($X / 16)
$chunkZ = [Math]::Floor($Z / 16)
$expectedUuid = $null

try {
    Invoke-Rcon $BackendA.RconPort "forceload add $X $Z" | Out-Null
    Wait-Loaded $BackendA.RconPort
    Invoke-Rcon $BackendB.RconPort "forceload add $X $Z" | Out-Null
    Wait-Loaded $BackendB.RconPort
    Wait-EntitySubscriptions $chunkX $chunkZ

    $ownerPort = Get-OwnerPort $chunkX $chunkZ
    Invoke-Rcon $ownerPort "summon minecraft:pig $($X + 0.5) $Y $($Z + 0.5) {Tags:[`"$tag`"],NoAI:1b,PersistenceRequired:1b}" | Out-Null
    Start-Sleep -Seconds 1
    Assert-Count $BackendA.RconPort 1 'Initial spawn replication'
    Assert-Count $BackendB.RconPort 1 'Initial spawn replication'
    $expectedUuid = Get-EntityUuid $ownerPort
    if ((Get-EntityUuid $BackendA.RconPort) -ne $expectedUuid -or (Get-EntityUuid $BackendB.RconPort) -ne $expectedUuid) {
        throw 'The two backends do not hold the same UUID after spawn.'
    }
    Write-Output "PASS: spawned one persistent entity with UUID $expectedUuid on both backends."

    for ($transfer = 1; $transfer -le $OwnershipTransferCount; $transfer++) {
        $targetPort = if ($ownerPort -eq $BackendA.RconPort) { $BackendB.RconPort } else { $BackendA.RconPort }
        Transfer-Ownership $ownerPort $targetPort $chunkX $chunkZ
        $ownerPort = $targetPort
        foreach ($port in @($BackendA.RconPort, $BackendB.RconPort)) {
            Assert-Count $port 1 "Ownership transfer $transfer"
            if ((Get-EntityUuid $port) -ne $expectedUuid) { throw "Entity UUID changed on port $port during ownership transfer $transfer." }
        }
        Write-Output "PASS: ownership transfer $transfer preserved exactly one entity with UUID $expectedUuid."
    }

    $crashedBackend = if ($ownerPort -eq $BackendA.RconPort) { $BackendA } else { $BackendB }
    $survivingBackend = if ($ownerPort -eq $BackendA.RconPort) { $BackendB } else { $BackendA }
    Stop-DisposableBackend $crashedBackend.GamePort
    Assert-Count $survivingBackend.RconPort 1 'Surviving replica after unflushed owner crash'
    if ((Get-EntityUuid $survivingBackend.RconPort) -ne $expectedUuid) { throw 'Surviving replica changed UUID after the owner crash.' }
    Write-Output "PASS: after the owner's hard crash, the surviving backend retained UUID $expectedUuid exactly once."

    Start-DisposableBackend $crashedBackend
    $restartedBackend = $crashedBackend
    $crashedBackend = $null
    Invoke-Rcon $restartedBackend.RconPort "forceload add $X $Z" | Out-Null
    Wait-Loaded $restartedBackend.RconPort
    foreach ($port in @($BackendA.RconPort, $BackendB.RconPort)) {
        Assert-Count $port 1 'Entity recovery after backend restart and chunk reload'
        if ((Get-EntityUuid $port) -ne $expectedUuid) { throw "Recovered entity UUID changed on port $port." }
    }
    Write-Output "PASS: backend restart and chunk reload restored the same UUID exactly once on both backends."

    $ownerPort = Get-OwnerPort $chunkX $chunkZ
    $removalPort = if ($ownerPort -eq $BackendA.RconPort) { $BackendB.RconPort } else { $BackendA.RconPort }
    Invoke-Rcon $removalPort "kill @e[tag=$tag]" | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $countA = Get-TaggedEntityCount $BackendA.RconPort
        $countB = Get-TaggedEntityCount $BackendB.RconPort
        if ($countA -eq 0 -and $countB -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($countA -ne 0 -or $countB -ne 0) { throw "Entity removal left ghosts: Backend A=$countA Backend B=$countB." }
    Write-Output 'PASS: non-owner removal cleared the entity from both backends after recovery.'
} finally {
    if ($null -ne $crashedBackend) {
        try { Start-DisposableBackend $crashedBackend } catch { }
    }
    foreach ($backend in @($BackendA, $BackendB)) {
        try { Invoke-Rcon $backend.RconPort "kill @e[tag=$tag]" | Out-Null } catch { }
        try { Invoke-Rcon $backend.RconPort "forceload remove $X $Z" | Out-Null } catch { }
    }
}
