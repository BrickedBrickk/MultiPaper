param(
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
    [int]$ProxyPort = 29822,
    [int]$BackendARconPort = 29831,
    [int]$BackendBRconPort = 29832,
    [ValidateRange(1, 20)][int]$ClientCount = 20,
    [ValidateRange(10, 1800)][int]$DurationSeconds = 60,
    [ValidateRange(0, 60)][int]$StartupDelaySeconds = 30,
    [switch]$MoveClients,
    [switch]$UseStrictSshTunnels,
    [string]$ProxyHost = '127.0.0.1',
    [string]$ProxySshHost,
    [string]$BackendASshHost,
    [string]$BackendBSshHost,
    [string]$BackendALogSshPath,
    [string]$BackendBLogSshPath,
    [string]$UsernamePrefix = 'Cap',
    [int]$X = 150,
    [int]$Y = 63,
    [int]$Z = 140
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RconPassword)) { throw 'Provide -RconPassword or MULTIPAPER_RCON_PASSWORD.' }
if ($UsernamePrefix -notmatch '^[A-Za-z0-9_]{1,6}$') { throw 'UsernamePrefix must be 1-6 alphanumeric/underscore characters.' }
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$fixtureMarker = Join-Path $root '.multipaper-disposable'
$fixtureHasExplicitMarker = Test-Path -LiteralPath $fixtureMarker -PathType Leaf
if ((-not $root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
     (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') -and -not $fixtureHasExplicitMarker) {
    throw "Refusing to run outside a disposable MultiPaper fixture. Use a multipaper-26.3-* directory under the temp root, or add an explicit .multipaper-disposable marker to a verified disposable fixture: $root"
}
$rconScript = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'
$node = (Get-Command node.exe -ErrorAction Stop).Source
$dataDir = $env:MULTIPAPER_MC_DATA_26_3
if (-not $dataDir) { $dataDir = Join-Path $env:TEMP 'minecraft-data-pr1301/data/pc/26.3' }
if (-not (Test-Path -LiteralPath (Join-Path $dataDir 'protocol.json'))) { throw "Minecraft 26.3 protocol data not found: $dataDir" }
$properties = Get-Content -LiteralPath (Join-Path $root 'backend-a/server.properties') -Raw
$worldMatch = [regex]::Match($properties, '(?m)^level-name=(.+)$')
if (-not $worldMatch.Success) { throw 'Could not read disposable world name.' }
$world = $worldMatch.Groups[1].Value.Trim()
$runId = [Guid]::NewGuid().ToString('N')
$effectiveUsernamePrefix = $UsernamePrefix.Substring(0, [Math]::Min(3, $UsernamePrefix.Length)) + $runId.Substring(0, 3)
$stdout = Join-Path $root "capacity-$runId.out"
$stderr = Join-Path $root "capacity-$runId.err"
$tickSamples = Join-Path $root "capacity-$runId-ticks.txt"
$clientProcess = $null
$warningPatterns = @{
    movement = 'moved too quickly|moved wrongly'
    duplicate = 'entity uuid already exists|uuid of added entity already exists|duplicate entity uuid|duplicate UUID'
    missing = 'requested entity .*but that entity (?:doesn''t exist|does not exist)'
    unsubscribed = 'requested entity .*not in a chunk that server is subscribed to'
    watchdog = 'Can.t keep up|watchdog'
}
$warningSamples = @{
    duplicate = 'Entity uuid already exists: bat-uuid'
    missing = "backend-b requested entity bat-uuid, but that entity doesn't exist"
    unsubscribed = 'backend-b requested entity bat-uuid, but that entity is not in a chunk that server is subscribed to'
}
foreach ($key in @('duplicate', 'missing', 'unsubscribed')) {
    if ($warningSamples[$key] -notmatch $warningPatterns[$key]) {
        throw "Log warning classifier '$key' failed its regression sample."
    }
}
$logPaths = @((Join-Path $root 'backend-a/logs/latest.log'), (Join-Path $root 'backend-b/logs/latest.log'))
$warningBaseline = @{}
foreach ($name in @('backend-a', 'backend-b')) {
    $path = Join-Path $root "$name/logs/latest.log"
    foreach ($key in $warningPatterns.Keys) {
        $warningBaseline["$name/$key"] = if (Test-Path -LiteralPath $path) { (Select-String -LiteralPath $path -Pattern $warningPatterns[$key]).Count } else { 0 }
    }
}

function Invoke-Rcon([int]$port, [string]$command) {
    $result = & $rconScript -Server 127.0.0.1 -Port $port -Password $RconPassword -Command $command
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "RCON command failed on $port`: $command" }
    return ($result | Out-String).Trim()
}

function Assert-StrictSshTunnel([int]$port, [string]$sshHost, [string]$remotePort) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { throw "Strict SSH tunnel is not listening on local port $port." }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    $forward = "-L $($port):127.0.0.1:$($remotePort)"
    if (-not $process -or $process.ExecutablePath -notmatch '(?i)\\ssh\.exe$' -or
        $process.CommandLine -notmatch '(?i)StrictHostKeyChecking=yes' -or
        $process.CommandLine -notlike "*$sshHost*" -or
        $process.CommandLine -notlike "*$forward*") {
        throw "Local port $port is not owned by the expected strict-verified SSH tunnel to $sshHost port $remotePort."
    }
}

function Copy-StrictSshLog([string]$remotePath, [string]$destination) {
    if ([string]::IsNullOrWhiteSpace($remotePath)) { return }
    $scp = (Get-Command scp.exe -ErrorAction Stop).Source
    & $scp -o BatchMode=yes -o StrictHostKeyChecking=yes -- $remotePath $destination
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch backend log using strict SSH: $remotePath" }
}

function Get-EntityPosition([int]$port, [string]$username) {
    $reply = Invoke-Rcon $port "data get entity $username Pos"
    $match = [regex]::Match($reply, 'following entity data: \[([-+]?\d+(?:\.\d+)?)[dDfF]?,\s*([-+]?\d+(?:\.\d+)?)[dDfF]?,\s*([-+]?\d+(?:\.\d+)?)[dDfF]?\]')
    if (-not $match.Success) { return $null }
    return [pscustomobject]@{
        X = [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        Y = [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        Z = [double]::Parse($match.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
}

try {
    Write-Output "Disposable capacity fixture: world=$world spawn=($X,$Y,$Z) clients=$ClientCount moving=$([bool]$MoveClients) usernamePrefix=$effectiveUsernamePrefix."
    $platformMinX = if ($MoveClients) { $X - 60 } else { $X - 6 }
    $platformMaxX = if ($MoveClients) { $X + 60 } else { $X + 6 }
    if ($UseStrictSshTunnels) {
        foreach ($required in @($ProxySshHost, $BackendASshHost, $BackendBSshHost)) {
            if ([string]::IsNullOrWhiteSpace($required)) { throw 'Strict SSH tunnel mode requires ProxySshHost, BackendASshHost, and BackendBSshHost.' }
        }
        Assert-StrictSshTunnel $ProxyPort $ProxySshHost $ProxyPort
        Assert-StrictSshTunnel $BackendARconPort $BackendASshHost $BackendARconPort
        Assert-StrictSshTunnel $BackendBRconPort $BackendBSshHost $BackendBRconPort
        Copy-StrictSshLog $BackendALogSshPath (Join-Path $root 'backend-a/logs/latest.log')
        Copy-StrictSshLog $BackendBLogSshPath (Join-Path $root 'backend-b/logs/latest.log')
        foreach ($name in @('backend-a', 'backend-b')) {
            $path = Join-Path $root "$name/logs/latest.log"
            foreach ($key in $warningPatterns.Keys) {
                $warningBaseline["$name/$key"] = if (Test-Path -LiteralPath $path) { (Select-String -LiteralPath $path -Pattern $warningPatterns[$key]).Count } else { 0 }
            }
        }
    } else {
        foreach ($port in @($BackendARconPort, $BackendBRconPort)) {
            $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $listener) { throw "Disposable backend RCON is not listening on $port." }
            $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
            if (-not $process -or $process.CommandLine -notmatch '(?i)server\.jar') { throw "Port $port is not owned by the expected disposable backend." }
        }
        $proxyListener = Get-NetTCPConnection -State Listen -LocalPort $ProxyPort -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $proxyListener) { throw "Disposable MultiPaper player proxy is not listening on $ProxyPort." }
        $proxyProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($proxyListener.OwningProcess)"
        if (-not $proxyProcess -or $proxyProcess.CommandLine -notmatch '(?i)master\.jar') { throw "Port $ProxyPort is not owned by the fixture Master." }
    }

    foreach ($port in @($BackendARconPort, $BackendBRconPort)) {
        Invoke-Rcon $port "setworldspawn $X $Y $Z" | Out-Null
        Invoke-Rcon $port "fill $platformMinX $($Y - 1) $($Z - 4) $platformMaxX $($Y - 1) $($Z + 2 * $ClientCount + 4) minecraft:air" | Out-Null
        # The login spawn position is one block below the setworldspawn Y coordinate.
        # Build the standing surface below the player's feet, not inside the player box.
        Invoke-Rcon $port "fill $platformMinX $($Y - 2) $($Z - 4) $platformMaxX $($Y - 2) $($Z + 2 * $ClientCount + 4) minecraft:stone" | Out-Null
    }

    $env:MULTIPAPER_MC_DATA_26_3 = $dataDir
    if ($MoveClients) {
        Remove-Item Env:MULTIPAPER_BOT_STATIONARY -ErrorAction SilentlyContinue
        $env:MULTIPAPER_BOT_MIN_X = [string]($X - 54)
        $env:MULTIPAPER_BOT_MAX_X = [string]($X + 54)
        if (-not $env:MULTIPAPER_BOT_MOVEMENT_STEP) { $env:MULTIPAPER_BOT_MOVEMENT_STEP = '0.12' }
        $env:MULTIPAPER_BOT_SKIP_INITIAL_POSITION = '1'
    } else {
        $env:MULTIPAPER_BOT_STATIONARY = '1'
    }
    $env:NODE_PATH = if ($env:NODE_PATH) { $env:NODE_PATH } else { Join-Path $env:USERPROFILE 'node_modules' }
    $arguments = @('scripts/Run-ProtocolMovingClients.js', $ProxyHost, "$ProxyPort", "$ClientCount", "$DurationSeconds", $effectiveUsernamePrefix, "$StartupDelaySeconds")
    $clientProcess = Start-Process -FilePath $node -WorkingDirectory (Split-Path $PSScriptRoot -Parent) -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $stagedClients = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    do {
        if ($clientProcess.HasExited) { throw "Protocol clients exited before all were ready. See $stdout and $stderr." }
        $readyEvents = if (Test-Path -LiteralPath $stdout) { Select-String -LiteralPath $stdout -Pattern '"event":"ready"' } else { @() }
        foreach ($line in $readyEvents) {
            $ready = $line.Line | ConvertFrom-Json
            if ($stagedClients.Add($ready.username)) {
                $initialPositionPattern = '"event":"initial_position_sent".*"username":"' + [regex]::Escape($ready.username) + '"'
                $positionDeadline = [DateTime]::UtcNow.AddSeconds(10)
                while (-not (Select-String -LiteralPath $stdout -Pattern $initialPositionPattern -Quiet)) {
                    if ($clientProcess.HasExited) { throw "Protocol client ended before reporting its initial position: $($ready.username). See $stdout and $stderr." }
                    if ([DateTime]::UtcNow -ge $positionDeadline) { throw "Client did not send its initial position after login: $($ready.username). See $stdout and $stderr." }
                    Start-Sleep -Milliseconds 50
                }
                # Give the backend a tick to validate the login position before
                # moving this test client to its isolated lane.
                Start-Sleep -Milliseconds 250
                $clientSuffix = [regex]::Match($ready.username, '^' + [regex]::Escape($effectiveUsernamePrefix) + '(\d+)$')
                if (-not $clientSuffix.Success) { throw "Could not derive the lane index from client username $($ready.username)." }
                $clientIndex = [int]$clientSuffix.Groups[1].Value - 1
                $laneZ = $Z + 2 * $clientIndex
                # Move far enough from the initial spawn for the protocol client
                # to report a server position correction (>0.5 block).
                $stageX = $X + 1.25
                $reply = Invoke-Rcon $BackendARconPort "tp $($ready.username) $stageX $($Y - 1) $($laneZ + 0.5)"
                if ($reply -match 'No entity was found|Could not find') {
                    $reply = Invoke-Rcon $BackendBRconPort "tp $($ready.username) $stageX $($Y - 1) $($laneZ + 0.5)"
                }
                if ($reply -notmatch [regex]::Escape($ready.username)) { throw "Could not stage $($ready.username) away from the login point: $reply" }
                $stagedPositionPattern = '"event":"server_position_update".*"username":"' + [regex]::Escape($ready.username) + '".*"position":\{"x":' + [regex]::Escape([string]$stageX) + ',"y":' + [regex]::Escape([string]($Y - 1)) + ',"z":' + [regex]::Escape([string]($laneZ + 0.5)) + '\}'
                $stagedPositionDeadline = [DateTime]::UtcNow.AddSeconds(10)
                while (-not (Select-String -LiteralPath $stdout -Pattern $stagedPositionPattern -Quiet)) {
                    if ($clientProcess.HasExited) { throw "Protocol client ended before accepting its staged position: $($ready.username). See $stdout and $stderr." }
                    if ([DateTime]::UtcNow -ge $stagedPositionDeadline) { throw "Client did not receive the staged position for $($ready.username). See $stdout and $stderr." }
                    Start-Sleep -Milliseconds 50
                }
            }
        }
        $readyCount = $readyEvents.Count
        if ($readyCount -ge $ClientCount) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($readyCount -lt $ClientCount) { throw "Only $readyCount/$ClientCount clients became ready. See $stdout and $stderr." }

    Start-Sleep -Seconds 3
    $positions = for ($i = 0; $i -lt $ClientCount; $i++) {
        $username = '{0}{1:D2}' -f $effectiveUsernamePrefix, ($i + 1)
        $replyA = Invoke-Rcon $BackendARconPort "data get entity $username Pos"
        $replyB = Invoke-Rcon $BackendBRconPort "data get entity $username Pos"
        if ($replyA -notmatch 'following entity data: \[' -and $replyB -notmatch 'following entity data: \[') {
            throw "Could not read position for $username from either backend after its staging teleport: A='$replyA' B='$replyB'"
        }
        "$username A='$replyA' B='$replyB'"
    }
    Write-Output "Validated RCON entity positions for all $ClientCount connected clients on at least one backend (staged during login)."
    $movementBaselines = @{}
    if ($MoveClients) {
        for ($i = 0; $i -lt $ClientCount; $i++) {
            $username = '{0}{1:D2}' -f $effectiveUsernamePrefix, ($i + 1)
            $movementBaselines[$username] = @{}
            foreach ($port in @($BackendARconPort, $BackendBRconPort)) {
                $movementBaselines[$username][$port] = Get-EntityPosition $port $username
            }
        }
    }
    $movementWarningsBeforeSoakByBackend = @{}

    $endDeadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds + 60)
    $soakStarted = $false
    $nextTickSampleUtc = [DateTime]::UtcNow
    $nextPositionSampleUtc = [DateTime]::UtcNow.AddSeconds(5)
    $movementObserved = @{}
    $loadStartedPattern = if ($MoveClients) { '"event":"movement_started"' } else { '"event":"stationary_load_started"' }
    $sampleKind = if ($MoveClients) { 'moving' } else { 'stationary' }
    Set-Content -LiteralPath $tickSamples -Value "Disposable two-backend $sampleKind capacity samples; clients=$ClientCount duration=$DurationSeconds; UTC=$([DateTime]::UtcNow.ToString('o'))"
    do {
        if ($clientProcess.HasExited) { break }
        if (-not $soakStarted -and (Select-String -LiteralPath $stdout -Pattern $loadStartedPattern -Quiet)) {
            if ($UseStrictSshTunnels) {
                Copy-StrictSshLog $BackendALogSshPath (Join-Path $root 'backend-a/logs/latest.log')
                Copy-StrictSshLog $BackendBLogSshPath (Join-Path $root 'backend-b/logs/latest.log')
            }
            $index = 0
            foreach ($path in $logPaths) {
                $backendName = if ($index -eq 0) { 'backend-a' } else { 'backend-b' }
                $index++
                $movementWarningsBeforeSoakByBackend[$backendName] = if (Test-Path -LiteralPath $path) { (Select-String -LiteralPath $path -Pattern $warningPatterns.movement).Count } else { 0 }
            }
            $soakStarted = $true
            $nextTickSampleUtc = [DateTime]::UtcNow
            $nextPositionSampleUtc = [DateTime]::UtcNow.AddSeconds(5)
        }
        if ($soakStarted -and [DateTime]::UtcNow -ge $nextTickSampleUtc) {
            foreach ($port in @($BackendARconPort, $BackendBRconPort)) {
                $tick = Invoke-Rcon $port 'tick query'
                $tps = Invoke-Rcon $port 'tps'
                Add-Content -LiteralPath $tickSamples -Value "UTC=$([DateTime]::UtcNow.ToString('o')) rcon=$port`n$tick`n$tps"
            }
            $nextTickSampleUtc = [DateTime]::UtcNow.AddSeconds(10)
        }
        if ($MoveClients -and $soakStarted -and [DateTime]::UtcNow -ge $nextPositionSampleUtc) {
            foreach ($username in $movementBaselines.Keys) {
                foreach ($port in @($BackendARconPort, $BackendBRconPort)) {
                    $baseline = $movementBaselines[$username][$port]
                    $current = Get-EntityPosition $port $username
                    if ($baseline -and $current) {
                        $distance = [Math]::Sqrt([Math]::Pow($current.X - $baseline.X, 2) + [Math]::Pow($current.Z - $baseline.Z, 2))
                        if ($distance -ge 1.0) { $movementObserved[$username] = $true }
                        Add-Content -LiteralPath $tickSamples -Value "UTC=$([DateTime]::UtcNow.ToString('o')) position rcon=$port player=$username baseline=($($baseline.X),$($baseline.Y),$($baseline.Z)) current=($($current.X),$($current.Y),$($current.Z))"
                    }
                }
            }
            $nextPositionSampleUtc = [DateTime]::UtcNow.AddSeconds(5)
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $endDeadline)
    if (-not $clientProcess.HasExited) { Stop-Process -Id $clientProcess.Id -Force; throw 'Protocol capacity run exceeded its duration timeout.' }
    $clientProcess.WaitForExit()
    if ($clientProcess.ExitCode -ne 0) { throw "Protocol capacity client process exited $($clientProcess.ExitCode). See $stdout and $stderr." }

    Copy-StrictSshLog $BackendALogSshPath (Join-Path $root 'backend-a/logs/latest.log')
    Copy-StrictSshLog $BackendBLogSshPath (Join-Path $root 'backend-b/logs/latest.log')

    $summaryLine = Get-Content -LiteralPath $stdout | Where-Object { $_ -match '^\{"event":"summary"' } | Select-Object -Last 1
    if (-not $summaryLine) { throw "Capacity harness did not emit a summary. See $stdout and $stderr." }
    $summary = $summaryLine | ConvertFrom-Json
    if ($summary.ready -ne $ClientCount -or $summary.protocolDisconnects -ne 0 -or $summary.unexpectedSocketEnds -ne 0 -or $summary.errors.Count -ne 0) {
        throw "Capacity run failed client integrity: $($summaryLine)"
    }
    if ($MoveClients) {
        $nonMoving = @($movementBaselines.Keys | Where-Object { -not $movementObserved.ContainsKey($_) })
        if ($nonMoving.Count -gt 0) { throw "Clients sent movement packets but neither backend reported authoritative movement of at least one block for: $($nonMoving -join ', '). See $tickSamples." }
    }
    $movementWarnings = 0
    $duplicateWarnings = 0
    $missingEntityWarnings = 0
    $unsubscribedEntityWarnings = 0
    $watchdogWarnings = 0
    $index = 0
    foreach ($path in $logPaths) {
        $backendName = if ($index -eq 0) { 'backend-a' } else { 'backend-b' }
        $index++
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $movementWarnings += [Math]::Max(0, (Select-String -LiteralPath $path -Pattern $warningPatterns.movement).Count - $movementWarningsBeforeSoakByBackend[$backendName])
        $duplicateWarnings += [Math]::Max(0, (Select-String -LiteralPath $path -Pattern $warningPatterns.duplicate).Count - $warningBaseline["$backendName/duplicate"])
        $missingEntityWarnings += [Math]::Max(0, (Select-String -LiteralPath $path -Pattern $warningPatterns.missing).Count - $warningBaseline["$backendName/missing"])
        $unsubscribedEntityWarnings += [Math]::Max(0, (Select-String -LiteralPath $path -Pattern $warningPatterns.unsubscribed).Count - $warningBaseline["$backendName/unsubscribed"])
        $watchdogWarnings += [Math]::Max(0, (Select-String -LiteralPath $path -Pattern $warningPatterns.watchdog).Count - $warningBaseline["$backendName/watchdog"])
    }
    Write-Output "PASS: $($summary.ready)/$ClientCount offline protocol clients stayed connected for $($summary.durationSeconds)s; disconnects=$($summary.protocolDisconnects), unexpectedEnds=$($summary.unexpectedSocketEnds), clientErrors=$($summary.errors.Count)."
    $initialLoginMovementWarnings = [Math]::Max(0,
        $movementWarningsBeforeSoakByBackend['backend-a'] - $warningBaseline['backend-a/movement'] +
        $movementWarningsBeforeSoakByBackend['backend-b'] - $warningBaseline['backend-b/movement'])
    Write-Output "Server log totals: movementRejectsDuringSoak=$movementWarnings duplicateEntityWarnings=$duplicateWarnings missingEntityRequestWarnings=$missingEntityWarnings unsubscribedEntityRequestWarnings=$unsubscribedEntityWarnings watchdogOrTickOverrun=$watchdogWarnings; initialLoginMovementRejects=$initialLoginMovementWarnings."
    Write-Output "Transcript: $stdout"
    Write-Output "Tick samples: $tickSamples"
    if ($movementWarnings -gt 0 -or $duplicateWarnings -gt 0 -or $missingEntityWarnings -gt 0 -or $unsubscribedEntityWarnings -gt 0 -or $watchdogWarnings -gt 0) { throw 'Capacity run had server-side integrity or movement warnings.' }
} finally {
    if ($clientProcess -and -not $clientProcess.HasExited) { Stop-Process -Id $clientProcess.Id -Force }
    Remove-Item Env:MULTIPAPER_BOT_STATIONARY -ErrorAction SilentlyContinue
    Remove-Item Env:MULTIPAPER_BOT_MIN_X -ErrorAction SilentlyContinue
    Remove-Item Env:MULTIPAPER_BOT_MAX_X -ErrorAction SilentlyContinue
    Remove-Item Env:MULTIPAPER_BOT_MOVEMENT_STEP -ErrorAction SilentlyContinue
    Remove-Item Env:MULTIPAPER_BOT_SKIP_INITIAL_POSITION -ErrorAction SilentlyContinue
}
