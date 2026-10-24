param(
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [int]$BackendARconPort = 29231,
    [int]$BackendBRconPort = 29232,
    [string]$RconPassword = $env:MULTIPAPER_RCON_PASSWORD,
    [string]$JavaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [ValidateRange(1, 10)][int]$RepeatCount = 3,
    [bool]$RestartMasterDuringFirstCycle = $true,
    [ValidateRange(0, 120000)][int]$AckDelayMillis = 0,
    [ValidateRange(0, 120000)][int]$HandoffSendDelayMillis = 0,
    [switch]$CrashSenderAfterJournal,
    [switch]$RestartSenderBeforeReceiverReplay,
    [switch]$VerifyReturnHandoffHasNoDuplicateTicks,
    [switch]$CrashReceiverAfterJournal,
    [int]$X = 16005,
    [int]$Y = 80,
    [int]$Z = 16005
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RconPassword)) { throw 'Provide -RconPassword or MULTIPAPER_RCON_PASSWORD.' }
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') {
    throw "Refusing to run outside a disposable MultiPaper fixture: $root"
}
$rcon = Join-Path $PSScriptRoot 'Send-RconCommand.ps1'
function Invoke-Rcon([int]$port, [string]$command) {
    $result = & $rcon -Server 127.0.0.1 -Port $port -Password $RconPassword -Command $command
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "RCON command failed on $port`: $command" }
    return ($result | Out-String).Trim()
}
function Assert-Contains([string]$text, [string]$needle, [string]$message) {
    if ($text -notmatch [regex]::Escape($needle)) { throw "$message`nReply: $text" }
}
function Wait-Listener([int]$port, [int]$seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do { Start-Sleep -Milliseconds 500; $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1 } while (-not $listener -and [DateTime]::UtcNow -lt $deadline)
    if (-not $listener) { throw "Timed out waiting for port $port." }
}
function Wait-ChunkLoaded([int]$port, [int]$seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        if ((Invoke-Rcon $port $loaded) -match 'Help:') { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Chunk did not load on RCON port $port."
}
function Wait-Path([string[]]$paths, [DateTime]$notBeforeUtc, [int]$seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        foreach ($path in $paths) {
            if ((Test-Path -LiteralPath $path -PathType Leaf) -and (Get-Item -LiteralPath $path).Length -gt 0 -and (Get-Item -LiteralPath $path).LastWriteTimeUtc -ge $notBeforeUtc) { return }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Durable tick handoff journal was not written to any expected Master path: $($paths -join '; ')"
}
function Wait-ChunkUnloaded([int]$port, [int]$seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do { if ((Invoke-Rcon $port $loaded) -notmatch 'Help:') { return }; Start-Sleep -Milliseconds 500 } while ([DateTime]::UtcNow -lt $deadline)
    throw "Receiver chunk did not unload on RCON port $port."
}
function Restart-DisposableMaster([int]$port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { throw "Disposable Master is not listening on $port before restart." }
    $masterProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if (-not $masterProcess -or $masterProcess.CommandLine -notmatch '(?i)master\.jar') {
        throw "Refusing to stop port $port because its process is not the expected disposable Master JAR."
    }
    Stop-Process -Id $masterProcess.ProcessId -Force
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do { Start-Sleep -Milliseconds 250; $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue } while ($listener -and [DateTime]::UtcNow -lt $deadline)
    if ($listener) { throw "Disposable Master listener $port did not close." }
    $masterRoot = Join-Path $root 'master'
    $masterProcess = Start-Process -FilePath $JavaPath -WorkingDirectory $masterRoot -ArgumentList @('-jar', 'master.jar', "$port", "$($port + 1)") -RedirectStandardOutput (Join-Path $root 'master-restart.out') -RedirectStandardError (Join-Path $root 'master-restart.err') -WindowStyle Hidden -PassThru
    Wait-Listener $port 60
    Start-Sleep -Seconds 5
}

$owner = $BackendARconPort
$receiver = $BackendBRconPort
$ownerGame = $BackendARconPort - 8
$receiverGame = $BackendBRconPort - 8
$crashedSenderRoot = $null
$crashedSenderGame = 0
$ownerName = [regex]::Match((Get-Content (Join-Path $root 'backend-a/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
$receiverName = [regex]::Match((Get-Content (Join-Path $root 'backend-b/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
if (-not $ownerName -or -not $receiverName) { throw 'Could not read backend names for deterministic ownership assertions.' }
$head = "execute if block $($X + 1) $Y $Z minecraft:piston_head run help"
$pushedBlock = "execute if block $($X + 2) $Y $Z minecraft:stone run help"
$torchX = $X - 1
$supportY = $Y - 1
$loaded = "execute if loaded $X $Y $Z run help"
$properties = Get-Content (Join-Path $root 'backend-a/server.properties') -Raw
if ($properties -notmatch '(?m)^level-name=(.+)$') { throw 'Could not read the disposable backend world name.' }
$worldName = $Matches[1].Trim()
$chunkX = [math]::Floor($X / 16)
$chunkZ = [math]::Floor($Z / 16)
$journalPaths = @(
    (Join-Path $root "master/$worldName/dimensions/minecraft/overworld/data/multipaper/tick-handoffs/$($chunkX)_$($chunkZ).dat"),
    (Join-Path $root "master/$worldName/data/multipaper/tick-handoffs/$($chunkX)_$($chunkZ).dat")
)

try {
    Invoke-Rcon $owner "forceload add $X $Z" | Out-Null
    Wait-ChunkLoaded $owner
    Invoke-Rcon $receiver "forceload add $X $Z" | Out-Null
    Wait-ChunkLoaded $receiver
    $ownerStateA = Invoke-Rcon $BackendARconPort "ticklockprobe state $worldName $chunkX $chunkZ"
    $ownerStateB = Invoke-Rcon $BackendBRconPort "ticklockprobe state $worldName $chunkX $chunkZ"
    if ($ownerStateA -match 'ownerIsMe=true') {
        $owner = $BackendARconPort; $receiver = $BackendBRconPort
        $ownerGame = $BackendARconPort - 8; $receiverGame = $BackendBRconPort - 8
        $ownerName = [regex]::Match((Get-Content (Join-Path $root 'backend-a/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
        $receiverName = [regex]::Match((Get-Content (Join-Path $root 'backend-b/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
    } elseif ($ownerStateB -match 'ownerIsMe=true') {
        $owner = $BackendBRconPort; $receiver = $BackendARconPort
        $ownerGame = $BackendBRconPort - 8; $receiverGame = $BackendARconPort - 8
        $ownerName = [regex]::Match((Get-Content (Join-Path $root 'backend-b/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
        $receiverName = [regex]::Match((Get-Content (Join-Path $root 'backend-a/multipaper.yml') -Raw), '(?m)^\s*my-name:\s*(\S+)').Groups[1].Value
    } else {
        throw "Neither fixture backend has authoritative chunk ownership. A=$ownerStateA B=$ownerStateB"
    }
    if ($AckDelayMillis -gt 0) {
        $receiverListener = Get-NetTCPConnection -State Listen -LocalPort $receiverGame -ErrorAction SilentlyContinue | Select-Object -First 1
        $receiverProcess = if ($receiverListener) { Get-CimInstance Win32_Process -Filter "ProcessId = $($receiverListener.OwningProcess)" } else { $null }
        $requiredAckDelay = "-Dmultipaper.test.tickAckDelayMillis=$AckDelayMillis"
        if (-not $receiverProcess -or $receiverProcess.CommandLine -notlike "*$requiredAckDelay*") {
            throw "The receiver JVM must be started with $requiredAckDelay before running this delayed-ACK test."
        }
    }
    if ($HandoffSendDelayMillis -gt 0) {
        $requiredSendDelay = "-Dmultipaper.test.tickHandoffSendDelayMillis=$HandoffSendDelayMillis"
        foreach ($gamePort in @(($BackendARconPort - 8), ($BackendBRconPort - 8))) {
            $backendListener = Get-NetTCPConnection -State Listen -LocalPort $gamePort -ErrorAction SilentlyContinue | Select-Object -First 1
            $backendProcess = if ($backendListener) { Get-CimInstance Win32_Process -Filter "ProcessId = $($backendListener.OwningProcess)" } else { $null }
            if (-not $backendProcess -or $backendProcess.CommandLine -notlike "*$requiredSendDelay*") {
                throw "Both backend JVMs must be started with $requiredSendDelay before running this delayed-delivery test. Missing on game port $gamePort."
            }
        }
    }
    foreach ($position in @("$torchX $Y $Z", "$torchX $supportY $Z", "$X $Y $Z", "$($X + 1) $Y $Z", "$($X + 2) $Y $Z")) {
        Invoke-Rcon $owner "setblock $position minecraft:air" | Out-Null
    }
    Invoke-Rcon $owner "setblock $torchX $supportY $Z minecraft:stone" | Out-Null
    Invoke-Rcon $owner "setblock $X $supportY $Z minecraft:stone" | Out-Null
    $initialPiston = Invoke-Rcon $owner "setblock $X $Y $Z minecraft:sticky_piston[facing=east]"
    if ($initialPiston -notmatch 'Changed') { throw "Could not place the piston on authoritative owner $ownerName. Reply: $initialPiston" }
    Invoke-Rcon $owner "setblock $($X + 1) $Y $Z minecraft:stone" | Out-Null
    Invoke-Rcon $owner "setblock $torchX $Y $Z minecraft:redstone_torch" | Out-Null
    Start-Sleep -Seconds 2
    Assert-Contains (Invoke-Rcon $owner $head) 'Help:' 'Owner did not extend the piston before freezing.'
    Assert-Contains (Invoke-Rcon $owner $pushedBlock) 'Help:' 'Vanilla piston did not move the test block into its extended position.'
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }
    for ($iteration = 1; $iteration -le $RepeatCount; $iteration++) {
        if ($iteration -gt 1) {
            $tmp = $owner; $owner = $receiver; $receiver = $tmp
            $tmp = $ownerGame; $ownerGame = $receiverGame; $receiverGame = $tmp
            $tmp = $ownerName; $ownerName = $receiverName; $receiverName = $tmp
            Invoke-Rcon $owner "setblock $torchX $supportY $Z minecraft:stone" | Out-Null
            Start-Sleep -Seconds 1
            Start-Sleep -Seconds 2
            Assert-Contains (Invoke-Rcon $owner $head) 'Help:' "Owner did not re-extend the piston before iteration $iteration."
            Assert-Contains (Invoke-Rcon $owner $pushedBlock) 'Help:' "Owner did not push the test block before iteration $iteration."
        }

        Invoke-Rcon $BackendARconPort "ticklockprobe pistonwatch reset $worldName $X $Y $Z" | Out-Null
        Invoke-Rcon $BackendBRconPort "ticklockprobe pistonwatch reset $worldName $X $Y $Z" | Out-Null
        Invoke-Rcon $owner 'tick freeze' | Out-Null
        Invoke-Rcon $receiver 'tick freeze' | Out-Null
        Invoke-Rcon $receiver "forceload add $X $Z" | Out-Null
        Wait-ChunkLoaded $receiver
        Assert-Contains (Invoke-Rcon $receiver "ticklockprobe lock $worldName $chunkX $chunkZ") 'submitted' 'Could not request deterministic receiver ownership for the handoff.'
        Start-Sleep -Seconds 2
        Assert-Contains (Invoke-Rcon $owner "ticklockprobe state $worldName $chunkX $chunkZ") "owner=$ownerName" 'The designated sender did not own the chunk before handoff.'
        Invoke-Rcon $owner "setblock $torchX $supportY $Z minecraft:redstone_block" | Out-Null
        Assert-Contains (Invoke-Rcon $owner "ticklockprobe scheduletorch $worldName $torchX $Y $Z 40") 'scheduled redstone torch tick' 'Could not create the deterministic vanilla redstone-torch tick for handoff.'
        Start-Sleep -Milliseconds 500
        $pendingTicks = Invoke-Rcon $owner "ticklockprobe count $worldName $torchX $Y $Z"
        Write-Output "Sender state before handoff: $pendingTicks"
        if ($pendingTicks -notmatch 'queued=true|inFlight=true') {
            throw "Regression setup did not leave a scheduled tick pending before handoff. State: $pendingTicks"
        }
        Assert-Contains (Invoke-Rcon $owner "execute if block $torchX $Y $Z minecraft:redstone_torch run help") 'Help:' 'Redstone torch did not remain lit while its off-tick was frozen.'
        $handoffRequestedUtc = [DateTime]::UtcNow
        $unlockReply = Invoke-Rcon $owner "ticklockprobe unlock $worldName $chunkX $chunkZ"
        Write-Output "Sender unlock: $unlockReply"
        Assert-Contains $unlockReply 'submitted' 'Could not release the sender chunk lock.'
        Invoke-Rcon $owner "forceload remove $X $Z" | Out-Null
        Wait-Path $journalPaths $handoffRequestedUtc
        if ($AckDelayMillis -gt 0) {
            Start-Sleep -Seconds 2
            $outboxState = Invoke-Rcon $owner "ticklockprobe state $worldName $chunkX $chunkZ"
            if ($outboxState -notmatch 'outboxPending=[1-9]') {
                throw "Expected sender outbox to remain pending while ACK is delayed $AckDelayMillis ms. State: $outboxState"
            }
            Write-Output "Sender retained its durable outbox while ACK was delayed: $outboxState"
            $receiverTickState = Invoke-Rcon $receiver "ticklockprobe count $worldName $torchX $Y $Z"
            if ($receiverTickState -notmatch 'pendingCount=1(?:\D|$)') {
                throw "Repeated transfer/retry did not retain exactly one scheduled redstone-torch tick on the receiver. State: $receiverTickState"
            }
            Write-Output "PASS: repeated transfer retries retained the one vanilla redstone-torch tick at $torchX,$Y,$Z."
        }
        if ($CrashSenderAfterJournal -and $iteration -eq 1) {
            # With a send delay configured, this crash happens after the sender's Master journal
            # write but before delivery. The new owner must recover the tick from shared storage.
            if ($HandoffSendDelayMillis -eq 0) { Start-Sleep -Seconds 2 }
            $senderGame = $ownerGame
            $senderListener = Get-NetTCPConnection -State Listen -LocalPort $senderGame -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $senderListener) { throw "Sender disappeared before the crash test on port $senderGame." }
            $senderProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($senderListener.OwningProcess)"
            $expectedJava = (Resolve-Path -LiteralPath $JavaPath).Path
            if (-not $senderProcess -or $senderProcess.CommandLine -notmatch '(?i)server\.jar' -or $senderProcess.ExecutablePath -ne $expectedJava) {
                throw "Refusing to crash a process that is not this disposable sender: $senderGame"
            }
            Stop-Process -Id $senderProcess.ProcessId -Force
            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            do { Start-Sleep -Milliseconds 250; $senderListener = Get-NetTCPConnection -State Listen -LocalPort $senderGame -ErrorAction SilentlyContinue } while ($senderListener -and [DateTime]::UtcNow -lt $deadline)
            if ($senderListener) { throw "Disposable sender did not stop on port $senderGame." }
            $crashedSenderGame = $senderGame
            $crashedSenderRoot = Join-Path $root $(if ($owner -eq $BackendARconPort) { 'backend-a' } else { 'backend-b' })
            Write-Output 'PASS: sender process crashed after its durable journal write; recovery continues without the sender.'
            if ($RestartSenderBeforeReceiverReplay) {
                $senderArgs = @()
                if ($AckDelayMillis -gt 0) { $senderArgs += "-Dmultipaper.test.tickAckDelayMillis=$AckDelayMillis" }
                if ($HandoffSendDelayMillis -gt 0) { $senderArgs += "-Dmultipaper.test.tickHandoffSendDelayMillis=$HandoffSendDelayMillis" }
                $senderArgs += @('-jar', 'server.jar', 'nogui')
                Start-Process -FilePath $JavaPath -WorkingDirectory $crashedSenderRoot -ArgumentList $senderArgs -WindowStyle Hidden -PassThru | Out-Null
                Wait-Listener $crashedSenderGame 90
                $crashedSenderRoot = $null
                Assert-Contains (Invoke-Rcon $receiver $head) 'Help:' 'Sender restart changed the receiver piston before its crash recovery cycle.'
                Assert-Contains (Invoke-Rcon $receiver $pushedBlock) 'Help:' 'Sender restart lost the pushed block before receiver replay.'
                Write-Output 'Sender restarted before receiver replay; pre-replay piston and pushed block remain intact.'
            }
        } elseif ($AckDelayMillis -gt 0 -and -not $CrashReceiverAfterJournal) {
            $ackDeadline = [DateTime]::UtcNow.AddMilliseconds($AckDelayMillis + $HandoffSendDelayMillis + 15000)
            do {
                Start-Sleep -Milliseconds 500
                $outboxState = Invoke-Rcon $owner "ticklockprobe state $worldName $chunkX $chunkZ"
            } while ($outboxState -notmatch 'outboxPending=0' -and [DateTime]::UtcNow -lt $ackDeadline)
            if ($outboxState -notmatch 'outboxPending=0') {
                throw "Sender outbox did not clear after delayed ACK. State: $outboxState"
            }
            Write-Output 'PASS: delayed acknowledgement cleared the sender outbox after idempotent retry.'
        }
        if (-not ($CrashReceiverAfterJournal -and $iteration -eq 1)) {
            Invoke-Rcon $receiver "forceload remove $X $Z" | Out-Null
        }
        # Ownership and subscriber tickets can keep the receiver chunk resident after
        # forceload removal. Stopping the disposable backend below forces a real unload.

        if ($CrashReceiverAfterJournal -and $iteration -eq 1) {
            $receiverListener = Get-NetTCPConnection -State Listen -LocalPort $receiverGame -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $receiverListener) { throw "Receiver disappeared before the crash test on port $receiverGame." }
            $receiverProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($receiverListener.OwningProcess)"
            $expectedJava = (Resolve-Path -LiteralPath $JavaPath).Path
            if (-not $receiverProcess -or $receiverProcess.CommandLine -notmatch '(?i)server\.jar' -or $receiverProcess.ExecutablePath -ne $expectedJava) {
                throw "Refusing to crash a process that is not the disposable receiver: $receiverGame"
            }
            Stop-Process -Id $receiverProcess.ProcessId -Force
            Write-Output 'PASS: receiver process crashed before scheduled-tick replay.'
        } else {
            Invoke-Rcon $receiver 'save-all flush' | Out-Null
            Invoke-Rcon $receiver 'stop' | Out-Null
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do { Start-Sleep -Milliseconds 500; $listener = Get-NetTCPConnection -State Listen -LocalPort $receiverGame -ErrorAction SilentlyContinue } while ($listener -and [DateTime]::UtcNow -lt $deadline)
        if ($listener) { throw "Receiver did not stop before recovery in iteration $iteration." }
        if ($iteration -eq 1 -and $RestartMasterDuringFirstCycle) { Restart-DisposableMaster ($BackendARconPort - 10) }
        $receiverRoot = Join-Path $root $(if ($receiver -eq $BackendARconPort) { 'backend-a' } else { 'backend-b' })
        $receiverArgs = @()
        if ($AckDelayMillis -gt 0) { $receiverArgs += "-Dmultipaper.test.tickAckDelayMillis=$AckDelayMillis" }
        if ($HandoffSendDelayMillis -gt 0) { $receiverArgs += "-Dmultipaper.test.tickHandoffSendDelayMillis=$HandoffSendDelayMillis" }
        $receiverArgs += @('-jar', 'server.jar', 'nogui')
        $receiverProcess = Start-Process -FilePath $JavaPath -WorkingDirectory $receiverRoot -ArgumentList $receiverArgs -WindowStyle Hidden -PassThru
        Wait-Listener $receiverGame 90
        Invoke-Rcon $receiver 'tick freeze' | Out-Null
        Invoke-Rcon $receiver "forceload add $X $Z" | Out-Null
        Wait-ChunkLoaded $receiver
        Start-Sleep -Seconds 2
        $headAfterReceiverStart = Invoke-Rcon $receiver $head
        if ($headAfterReceiverStart -match 'Help:') {
            Assert-Contains (Invoke-Rcon $receiver $pushedBlock) 'Help:' "Receiver lost the pushed block before scheduled-tick replay in iteration $iteration."
        } else {
            # A due vanilla torch tick may run during backend startup before RCON opens.
            # Accept that ordering only if its final sticky-piston result is already durable.
            Assert-Contains (Invoke-Rcon $receiver "execute if block $($X + 1) $Y $Z minecraft:stone run help") 'Help:' "Receiver replayed during startup but failed to restore the sticky-piston target in iteration $iteration."
            Write-Output "Receiver completed the due tick during startup in iteration $iteration; final block state is intact."
        }
        Invoke-Rcon $receiver 'tick unfreeze' | Out-Null
        Start-Sleep -Seconds 3
        if ((Invoke-Rcon $receiver $head) -match 'Help:') { throw "Piston remained extended after replay in iteration $iteration." }
        Assert-Contains (Invoke-Rcon $receiver "execute if block $($X + 1) $Y $Z minecraft:stone run help") 'Help:' "Sticky piston replay failed to restore the target block in iteration $iteration."
        $pistonCountA = Invoke-Rcon $BackendARconPort "ticklockprobe pistonwatch count $worldName $X $Y $Z"
        $pistonCountB = Invoke-Rcon $BackendBRconPort "ticklockprobe pistonwatch count $worldName $X $Y $Z"
        $countA = [int]([regex]::Match($pistonCountA, 'pistonRetractCount=(\d+)').Groups[1].Value)
        $countB = [int]([regex]::Match($pistonCountB, 'pistonRetractCount=(\d+)').Groups[1].Value)
        if (($countA + $countB) -ne 1) {
            throw "Expected exactly one vanilla piston retract event after scheduled-tick replay in iteration $iteration. A=$pistonCountA B=$pistonCountB"
        }
        Write-Output "PASS: scheduled-tick replay produced exactly one vanilla piston retract event in iteration $iteration."
        if ($CrashReceiverAfterJournal -and $iteration -eq 1 -and $AckDelayMillis -gt 0) {
            # Receiver recovery may complete from the shared journal before a sender
            # retry arrives. Include both test delays because the retry timer starts
            # after this receiver restart, then its ACK is delayed again.
            $ackDeadline = [DateTime]::UtcNow.AddMilliseconds($AckDelayMillis + $HandoffSendDelayMillis + 15000)
            do {
                Start-Sleep -Milliseconds 500
                $outboxState = Invoke-Rcon $owner "ticklockprobe state $worldName $chunkX $chunkZ"
            } while ($outboxState -notmatch 'outboxPending=0' -and [DateTime]::UtcNow -lt $ackDeadline)
            if ($outboxState -notmatch 'outboxPending=0') {
                throw "Sender outbox did not clear after receiver crash recovery. State: $outboxState"
            }
            $receiverTickState = Invoke-Rcon $receiver "ticklockprobe count $worldName $torchX $Y $Z"
            if ($receiverTickState -notmatch 'pendingCount=0(?:\D|$)') {
                throw "A retry after receiver recovery queued a duplicate tick. State: $receiverTickState"
            }
            Write-Output 'PASS: receiver crash recovery replayed once and eventually acknowledged the sender outbox.'
        }
        if ($CrashSenderAfterJournal -and $iteration -eq 1 -and $crashedSenderRoot) {
            $senderArgs = @()
            if ($AckDelayMillis -gt 0) { $senderArgs += "-Dmultipaper.test.tickAckDelayMillis=$AckDelayMillis" }
            if ($HandoffSendDelayMillis -gt 0) { $senderArgs += "-Dmultipaper.test.tickHandoffSendDelayMillis=$HandoffSendDelayMillis" }
            $senderArgs += @('-jar', 'server.jar', 'nogui')
            Start-Process -FilePath $JavaPath -WorkingDirectory $crashedSenderRoot -ArgumentList $senderArgs -WindowStyle Hidden -PassThru | Out-Null
            Wait-Listener $crashedSenderGame 90
            $crashedSenderRoot = $null
            Start-Sleep -Seconds 2
            Assert-Contains (Invoke-Rcon $receiver "execute if block $($X + 1) $Y $Z minecraft:stone run help") 'Help:' 'Sender restart overwrote the receiver replay result.'
            Write-Output 'PASS: sender restart preserved the receiver replay result.'
        }
        if ($VerifyReturnHandoffHasNoDuplicateTicks -and $CrashSenderAfterJournal -and $RestartSenderBeforeReceiverReplay -and $iteration -eq 1) {
            $sourceRcon = $crashedSenderGame + 8
            Invoke-Rcon $sourceRcon 'tick freeze' | Out-Null
            Invoke-Rcon $receiver 'tick freeze' | Out-Null
            Invoke-Rcon $sourceRcon "forceload add $X $Z" | Out-Null
            Wait-ChunkLoaded $sourceRcon
            Assert-Contains (Invoke-Rcon $sourceRcon "ticklockprobe lock $worldName $chunkX $chunkZ") 'submitted' 'Could not request return ownership after sender recovery.'
            Assert-Contains (Invoke-Rcon $receiver "ticklockprobe unlock $worldName $chunkX $chunkZ") 'submitted' 'Could not release the receiver lock for the return ownership check.'
            $returnDeadline = [DateTime]::UtcNow.AddSeconds(30)
            do {
                Start-Sleep -Milliseconds 250
                $returnedState = Invoke-Rcon $sourceRcon "ticklockprobe state $worldName $chunkX $chunkZ"
            } while ($returnedState -notmatch 'ownerIsMe=true' -and [DateTime]::UtcNow -lt $returnDeadline)
            Assert-Contains $returnedState 'ownerIsMe=true' 'The restarted sender did not regain chunk ownership for the stale-tick check.'
            $sourceTicks = Invoke-Rcon $sourceRcon "ticklockprobe count $worldName $torchX $Y $Z"
            $receiverTicks = Invoke-Rcon $receiver "ticklockprobe count $worldName $torchX $Y $Z"
            $sourcePending = [int]([regex]::Match($sourceTicks, 'pendingCount=(\d+)').Groups[1].Value)
            $receiverPending = [int]([regex]::Match($receiverTicks, 'pendingCount=(\d+)').Groups[1].Value)
            if (($sourcePending + $receiverPending) -ne 0) {
                throw "A completed transfer reappeared during return ownership handoff. Source=$sourceTicks Receiver=$receiverTicks"
            }
            Write-Output 'PASS: returning ownership after sender restart did not resurrect a completed scheduled tick.'
        }
        Write-Output "PASS: durable receiver restart/reload replay iteration $iteration/$RepeatCount recovered the piston state."
    }
}
finally {
    foreach ($port in @($owner, $receiver)) {
        try { Invoke-Rcon $port 'tick unfreeze' | Out-Null } catch { }
        try { Invoke-Rcon $port "forceload remove $X $Z" | Out-Null } catch { }
    }
    if ($crashedSenderRoot) {
        try {
            $senderArgs = @()
            if ($AckDelayMillis -gt 0) { $senderArgs += "-Dmultipaper.test.tickAckDelayMillis=$AckDelayMillis" }
            $senderArgs += @('-jar', 'server.jar', 'nogui')
            Start-Process -FilePath $JavaPath -WorkingDirectory $crashedSenderRoot -ArgumentList $senderArgs -WindowStyle Hidden -PassThru | Out-Null
        } catch { }
    }
}
