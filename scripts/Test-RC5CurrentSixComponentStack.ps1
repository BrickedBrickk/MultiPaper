param(
    [Parameter(Mandatory)][string]$FixtureRoot,
    [int]$MasterPort = 32651,
    [int]$ProxyPort = 32652,
    [int]$BackendARconPort = 32661,
    [int]$BackendBRconPort = 32662,
    [int]$GeyserBedrockPort = 32672,
    [string]$ExpectedPaperclipSha256 = '99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85',
    [string]$ExpectedMasterSha256 = '1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E',
    [string]$ExpectedBackendA = 'smoke-a',
    [string]$ExpectedBackendB = 'smoke-b',
    [switch]$RunVanillaMechanics,
    [switch]$ChestPersistenceProbe,
    [switch]$ExerciseLateShutdownChestWrite,
    [string]$JavaExecutable = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [string]$NodeExecutable = 'node',
    [string]$NodeModules = "$env:TEMP\mp-26.3-protocol-probe-deps\node_modules",
    [string]$MinecraftData26_3 = "$env:TEMP\minecraft-data-pr1301-current-924e26d6\data\pc\26.3",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$FixtureRoot = (Resolve-Path -LiteralPath $FixtureRoot).Path
$marker = Join-Path $FixtureRoot '.multipaper-disposable'
if (-not (Test-Path -LiteralPath $marker)) { throw 'Refusing to run without the disposable-fixture marker.' }
if ((Get-FileHash (Join-Path $FixtureRoot 'backend-a\server.jar') -Algorithm SHA256).Hash -ne $ExpectedPaperclipSha256) { throw 'Backend A Paperclip SHA-256 mismatch.' }
if ((Get-FileHash (Join-Path $FixtureRoot 'backend-b\server.jar') -Algorithm SHA256).Hash -ne $ExpectedPaperclipSha256) { throw 'Backend B Paperclip SHA-256 mismatch.' }
if ((Get-FileHash (Join-Path $FixtureRoot 'master\master.jar') -Algorithm SHA256).Hash -ne $ExpectedMasterSha256) { throw 'Master SHA-256 mismatch.' }
foreach ($name in @('floodgate-spigot.jar', 'ViaVersion-5.12.2.jar', 'ViaBackwards-5.12.2.jar', 'InvSee++.jar', 'grimac-bukkit-2.3.75-1299a2a.jar')) {
    foreach ($backend in @('backend-a', 'backend-b')) {
        if (-not (Test-Path -LiteralPath (Join-Path $FixtureRoot "$backend\plugins\$name"))) { throw "Missing plugin $name in $backend." }
    }
}
$expectedPluginHashes = @{
    'floodgate-spigot.jar' = '21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E'
    'ViaVersion-5.12.2.jar' = '24E4876FEFE73E08A4C87CD6C457EE0916E62DD69656631CAF41E26F909D504B'
    'ViaBackwards-5.12.2.jar' = '9A148A80EA22074F4BD1626CDBE254A4CD998BF44E7CEAFF4E285A1B6B35DA42'
    'InvSee++.jar' = '01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9'
    'grimac-bukkit-2.3.75-1299a2a.jar' = '080E01C5545695B7E755F705D73DA49C5EAE9B86D67F1425F88F2C7B414F8DA9'
}
foreach ($backend in @('backend-a', 'backend-b')) {
    foreach ($name in $expectedPluginHashes.Keys) {
        $path = Join-Path $FixtureRoot "$backend\plugins\$name"
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expectedPluginHashes[$name]) { throw "Official plugin hash mismatch: $backend/$name" }
    }
}
$geyserJar = Join-Path $FixtureRoot 'geyser\Geyser-Standalone.jar'
$geyserConfig = Join-Path $FixtureRoot 'geyser\config.yml'
if (-not (Test-Path -LiteralPath $geyserJar) -or -not (Test-Path -LiteralPath $geyserConfig)) { throw 'Geyser Standalone is missing from the disposable fixture.' }
if ((Get-FileHash -LiteralPath $geyserJar -Algorithm SHA256).Hash -ne '1DD1DE0D56AD5F50D31FEFF2E18F6F71DE50BC142853131D473BFD71D9E3809F') { throw 'Geyser Standalone does not match official build 1276.' }
$geyserText = Get-Content -LiteralPath $geyserConfig -Raw
if ($geyserText -notmatch '(?m)^\s+auth-type: floodgate\s*$') { throw 'Geyser Standalone is not configured for Floodgate auth.' }
if ($geyserText -notmatch "(?m)^\s+port: $ProxyPort\s*$") { throw 'Geyser remote port does not match the disposable Master player-proxy port.' }
if ($geyserText -notmatch '(?ms)^bedrock:.*?^\s+address: 127\.0\.0\.1\s*$') { throw 'Geyser Bedrock listener must be loopback-only in this disposable test.' }
$keyPaths = @(
    (Join-Path $FixtureRoot 'backend-a\plugins\floodgate\key.pem'),
    (Join-Path $FixtureRoot 'backend-b\plugins\floodgate\key.pem'),
    (Join-Path $FixtureRoot 'geyser\key.pem')
)
if ($keyPaths.Where({ -not (Test-Path -LiteralPath $_) }).Count) { throw 'A disposable Floodgate key file is missing.' }
$keyHashes = @($keyPaths | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } | Select-Object -Unique)
if ($keyHashes.Count -ne 1) { throw 'Disposable Floodgate key files do not match.' }
if (-not (Test-Path -LiteralPath (Join-Path $NodeModules 'minecraft-protocol'))) { throw 'NodeModules must contain minecraft-protocol.' }
if (-not (Test-Path -LiteralPath (Join-Path $MinecraftData26_3 'protocol.json')) -or -not (Test-Path -LiteralPath (Join-Path $MinecraftData26_3 'proto.yml'))) { throw 'MinecraftData26_3 must contain protocol.json and proto.yml.' }

foreach ($port in @($MasterPort, $ProxyPort, $BackendARconPort, $BackendBRconPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "TCP port $port already has a listener." }
}
if (Get-NetUDPEndpoint -LocalPort $GeyserBedrockPort -ErrorAction SilentlyContinue) { throw "UDP port $GeyserBedrockPort already has a listener." }

function Get-Property([string]$Path, [string]$Name) {
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name in a disposable backend server.properties." }
    return $line.Substring($Name.Length + 1)
}

function Read-RconPacket($stream) {
    $header = [byte[]]::new(4); $offset = 0
    while ($offset -lt 4) { $read = $stream.Read($header, $offset, 4 - $offset); if (-not $read) { throw 'RCON closed while reading a header.' }; $offset += $read }
    $length = [BitConverter]::ToInt32($header, 0); $body = [byte[]]::new($length); $offset = 0
    while ($offset -lt $length) { $read = $stream.Read($body, $offset, $length - $offset); if (-not $read) { throw 'RCON closed while reading a response.' }; $offset += $read }
    return [pscustomobject]@{ Id = [BitConverter]::ToInt32($body, 0); Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10) }
}

function Write-RconPacket($stream, [int]$Id, [int]$Type, [string]$Text) {
    $payload = [Text.Encoding]::UTF8.GetBytes($Text); $length = 10 + $payload.Length; $packet = [byte[]]::new(4 + $length)
    [BitConverter]::GetBytes($length).CopyTo($packet, 0); [BitConverter]::GetBytes($Id).CopyTo($packet, 4); [BitConverter]::GetBytes($Type).CopyTo($packet, 8); $payload.CopyTo($packet, 12)
    $stream.Write($packet, 0, $packet.Length); $stream.Flush()
}

function Invoke-Rcon([int]$Port, [string]$Password, [string]$Command) {
    $client = [Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout = 10000; $client.SendTimeout = 10000
    try {
        $client.Connect('127.0.0.1', $Port); $stream = $client.GetStream(); $stream.ReadTimeout = 10000; $stream.WriteTimeout = 10000
        $id = Get-Random -Minimum 10000 -Maximum 1800000000; Write-RconPacket $stream $id 3 $Password
        $auth = Read-RconPacket $stream; if ($auth.Id -ne $id) { throw "RCON authentication failed on loopback port $Port." }
        Write-RconPacket $stream ($id + 1) 2 $Command; $reply = Read-RconPacket $stream
        if ($reply.Id -ne ($id + 1)) { throw "Unexpected RCON response on loopback port $Port." }
        return $reply.Text
    } finally { $client.Dispose() }
}

function Get-ChestItems([int]$Port, [string]$Password, [int]$X, [int]$Y, [int]$Z, [string]$Label, [int]$ExpectedCount) {
    Invoke-Rcon $Port $Password "forceload add $X $Z" | Out-Null
    $reply = ''
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $reply = Invoke-Rcon $Port $Password "data get block $X $Y $Z Items"
        if ($reply -notmatch 'not loaded') { break }
        Start-Sleep -Milliseconds 500
    }
    if ($reply -notmatch 'minecraft:diamond' -or $reply -notmatch "count:\s*$ExpectedCount\b") {
        throw "$Label chest inventory was not exactly diamond x${ExpectedCount}: $reply"
    }
    return $reply
}

function Wait-ForLog([string]$Path, [string]$Pattern, [Diagnostics.Process]$Process, [string]$Label) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) { throw "$Label exited before reaching readiness." }
        if ((Test-Path -LiteralPath $Path) -and (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Label did not reach readiness before timeout."
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logRoot = Join-Path $FixtureRoot "six-component-smoke-$stamp"
New-Item -ItemType Directory -Path $logRoot | Out-Null
$owned = [Collections.Generic.List[Diagnostics.Process]]::new()
$master = $null; $backendA = $null; $backendB = $null; $backendRestart = $null; $geyser = $null
$oldNodePath = $env:NODE_PATH; $oldMcData = $env:MULTIPAPER_MC_DATA_26_3
$passwordA = Get-Property (Join-Path $FixtureRoot 'backend-a\server.properties') 'rcon.password'
$passwordB = Get-Property (Join-Path $FixtureRoot 'backend-b\server.properties') 'rcon.password'

try {
    $master = Start-Process -FilePath $JavaExecutable -WorkingDirectory (Join-Path $FixtureRoot 'master') -ArgumentList @('-jar', 'master.jar', "$MasterPort", "$ProxyPort") -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'master.out') -RedirectStandardError (Join-Path $logRoot 'master.err') -PassThru
    $owned.Add($master)
    $backendA = Start-Process -FilePath $JavaExecutable -WorkingDirectory (Join-Path $FixtureRoot 'backend-a') -ArgumentList @('-jar', 'server.jar', '--nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'backend-a.out') -RedirectStandardError (Join-Path $logRoot 'backend-a.err') -PassThru
    $owned.Add($backendA)
    $backendB = Start-Process -FilePath $JavaExecutable -WorkingDirectory (Join-Path $FixtureRoot 'backend-b') -ArgumentList @('-jar', 'server.jar', '--nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'backend-b.out') -RedirectStandardError (Join-Path $logRoot 'backend-b.err') -PassThru
    $owned.Add($backendB)
    Wait-ForLog (Join-Path $logRoot 'backend-a.out') 'Done \(' $backendA 'Backend A'
    Wait-ForLog (Join-Path $logRoot 'backend-b.out') 'Done \(' $backendB 'Backend B'

    foreach ($entry in @(@($BackendARconPort, $passwordA), @($BackendBRconPort, $passwordB))) {
        $pluginReply = (Invoke-Rcon ([int]$entry[0]) ([string]$entry[1]) 'plugins') -replace '§.', ''
        foreach ($plugin in @(@('Floodgate', 'Floodgate'), @('ViaVersion', 'ViaVersion'), @('ViaBackwards', 'ViaBackwards'), @('InvSee++', 'InvSeePlusPlus'), @('GrimAC', 'GrimAC'))) {
            if ($pluginReply -notmatch [regex]::Escape($plugin[1])) { throw "Expected plugin $($plugin[0]) is absent from /plugins on backend RCON port $($entry[0])." }
        }
    }

    $geyser = Start-Process -FilePath $JavaExecutable -WorkingDirectory (Join-Path $FixtureRoot 'geyser') -ArgumentList @('-jar', 'Geyser-Standalone.jar') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'geyser.out') -RedirectStandardError (Join-Path $logRoot 'geyser.err') -PassThru
    $owned.Add($geyser)
    Wait-ForLog (Join-Path $logRoot 'geyser.out') 'Done \(' $geyser 'Geyser Standalone'
    $udp = Get-NetUDPEndpoint -LocalPort $GeyserBedrockPort -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $geyser.Id }
    if (-not $udp) { throw 'Geyser reached Done but did not bind its configured loopback Bedrock UDP port.' }

    Invoke-Rcon $BackendARconPort $passwordA 'op StackSmoke' | Out-Null
    Invoke-Rcon $BackendBRconPort $passwordB 'op StackSmoke' | Out-Null
    $env:NODE_PATH = $NodeModules; $env:MULTIPAPER_MC_DATA_26_3 = $MinecraftData26_3
    $probe = Join-Path $repo 'scripts\Run-ProxyCommandProbe.js'
    $probeOutput = (& $NodeExecutable $probe '127.0.0.1' "$ProxyPort" 'StackSmoke' '26.3' $ExpectedBackendA $ExpectedBackendB 2>&1 | Out-String)
    $probeExitCode = $LASTEXITCODE
    $probeOutput
    if ($probeExitCode -ne 0) { throw "Exact-pair proxy command/chunk smoke failed with exit code $probeExitCode." }

    $status = (& (Join-Path $repo 'scripts\Probe-ServerStatus.ps1') -Host2 '127.0.0.1' -Port $ProxyPort | Out-String)
    if ($status -notmatch 'Protocol\s*:\s*777' -or $status -notmatch 'MultiPaper 26\.3') { throw "Proxy server-list status ping failed: $status" }
    $status

    $chestX = -40; $chestY = 80; $chestZ = -216; $chestCount = 17
    if ($ChestPersistenceProbe) {
        $positionMatch = [regex]::Match($probeOutput, 'PLAYER_READY\s+(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)')
        if (-not $positionMatch.Success) { throw "Could not derive a loaded test chunk from proxy probe output: $probeOutput" }
        $chestX = [Math]::Floor([double]::Parse($positionMatch.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)) + 3
        $chestY = [Math]::Floor([double]::Parse($positionMatch.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)) - 1
        $chestZ = [Math]::Floor([double]::Parse($positionMatch.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture))
        $setChestReply = Invoke-Rcon $BackendARconPort $passwordA "setblock $chestX $chestY $chestZ minecraft:chest replace"
        $setItemsReply = Invoke-Rcon $BackendARconPort $passwordA "item replace block $chestX $chestY $chestZ container.0 with minecraft:diamond $chestCount"
        $seedRead = Invoke-Rcon $BackendARconPort $passwordA "data get block $chestX $chestY $chestZ Items"
        if ($seedRead -notmatch 'minecraft:diamond' -or $seedRead -notmatch "count:\s*$chestCount\b") {
            throw "Chest seed failed at $chestX,$chestY,$chestZ. setblock=$setChestReply item=$setItemsReply read=$seedRead"
        }
        Write-Output "CHEST_SEEDED x=$chestX y=$chestY z=$chestZ minecraft:diamond count=$chestCount"
    }

    # A startup/login smoke is not a clean persistence result if Moonrise reports
    # a Master-rejected chunk write during shutdown. Stop one backend fully before
    # the other so an intentional simultaneous teardown does not create a test-only
    # ownership race. Keep Master up until both backend saves have completed.
    Invoke-Rcon $BackendARconPort $passwordA 'save-all flush' | Out-Null
    if ($ExerciseLateShutdownChestWrite) {
        if (-not $ChestPersistenceProbe) { throw 'Late-shutdown chest write probe requires -ChestPersistenceProbe.' }
        $lateShutdownChestCount = $chestCount + 12
        $lateMutationReply = Invoke-Rcon $BackendARconPort $passwordA "item replace block $chestX $chestY $chestZ container.0 with minecraft:diamond $lateShutdownChestCount"
        $lateMutationRead = Invoke-Rcon $BackendARconPort $passwordA "data get block $chestX $chestY $chestZ Items"
        if ($lateMutationRead -notmatch 'minecraft:diamond' -or $lateMutationRead -notmatch "count:\s*$lateShutdownChestCount\b") {
            throw "Late-shutdown chest mutation failed at $chestX,$chestY,$chestZ. command=$lateMutationReply read=$lateMutationRead"
        }
        Write-Output "CHEST_MUTATED_AFTER_FLUSH x=$chestX y=$chestY z=$chestZ minecraft:diamond count=$lateShutdownChestCount"
        $chestCount = $lateShutdownChestCount
    }
    Invoke-Rcon $BackendARconPort $passwordA 'stop' | Out-Null
    if (-not $backendA.WaitForExit(60000)) { throw "Disposable backend PID $($backendA.Id) did not stop within 60 seconds." }
    if ($ChestPersistenceProbe) {
        [void](Get-ChestItems $BackendBRconPort $passwordB $chestX $chestY $chestZ 'After owner A stopped (read by B)' $chestCount)
        Write-Output 'CHEST_PERSISTED after owner A stopped and read by backend B'
    }
    Invoke-Rcon $BackendBRconPort $passwordB 'save-all flush' | Out-Null
    Invoke-Rcon $BackendBRconPort $passwordB 'stop' | Out-Null
    if (-not $backendB.WaitForExit(60000)) { throw "Disposable backend PID $($backendB.Id) did not stop within 60 seconds." }
    if ($ChestPersistenceProbe) {
        $restartLog = Join-Path $logRoot 'backend-b-restart.out'
        $backendRestart = Start-Process -FilePath $JavaExecutable -WorkingDirectory (Join-Path $FixtureRoot 'backend-b') -ArgumentList @('-jar', 'server.jar', '--nogui') -WindowStyle Hidden -RedirectStandardOutput $restartLog -RedirectStandardError (Join-Path $logRoot 'backend-b-restart.err') -PassThru
        $owned.Add($backendRestart)
        Wait-ForLog $restartLog 'Done \(' $backendRestart 'Backend B restart'
        [void](Get-ChestItems $BackendBRconPort $passwordB $chestX $chestY $chestZ 'After Backend B restart' $chestCount)
        Write-Output 'CHEST_PERSISTED after backend B restart'
        Invoke-Rcon $BackendBRconPort $passwordB 'stop' | Out-Null
        if (-not $backendRestart.WaitForExit(60000)) { throw "Disposable backend PID $($backendRestart.Id) did not stop within 60 seconds." }
    }
    $persistenceErrors = @(
        (Select-String -LiteralPath (Join-Path $logRoot 'backend-a.out') -Pattern 'Failed to write chunk data|Master rejected persistence' -ErrorAction SilentlyContinue),
        (Select-String -LiteralPath (Join-Path $logRoot 'backend-b.out') -Pattern 'Failed to write chunk data|Master rejected persistence' -ErrorAction SilentlyContinue)
    ) | Where-Object { $_ }
    if ($persistenceErrors.Count) {
        throw "Disposable stack did not pass persistence-log gate: $($persistenceErrors.Count) Master-rejected chunk write log lines. Logs: $logRoot"
    }

    if ($RunVanillaMechanics) {
        $worldName = Get-Property (Join-Path $FixtureRoot 'backend-a\server.properties') 'level-name'
        & (Join-Path $repo 'scripts\Verify-TwoBackendReplication.ps1') `
            -Server1RconPort $BackendARconPort -Server2RconPort $BackendBRconPort `
            -Server1RconPassword $passwordA -Server2RconPassword $passwordB `
            -WorldName $worldName -TestX 1000 -TestY 102 -TestZ 1000 `
            -Server1LogPath (Join-Path $FixtureRoot 'backend-a\logs\latest.log') `
            -Server2LogPath (Join-Path $FixtureRoot 'backend-b\logs\latest.log')
    }
    $mechanicsLabel = if ($RunVanillaMechanics) { 'and two-backend vanilla mechanics' } else { '' }
    Write-Output "PASS: current clean-built Paperclip/Master pair, six hash-pinned current official plugins, all five backend plugins on both owners, Geyser Standalone startup/loopback bind, proxy login/chunks/commands, and protocol-777 status ping $mechanicsLabel. Logs: $logRoot"
} finally {
    try { Invoke-Rcon $BackendARconPort $passwordA 'save-all flush' | Out-Null } catch { }
    try { Invoke-Rcon $BackendBRconPort $passwordB 'save-all flush' | Out-Null } catch { }
    try { Invoke-Rcon $BackendARconPort $passwordA 'stop' | Out-Null } catch { }
    try { Invoke-Rcon $BackendBRconPort $passwordB 'stop' | Out-Null } catch { }
    foreach ($process in @($backendA, $backendB)) { if ($process -and -not $process.HasExited) { try { $process.WaitForExit(30000) } catch { } } }
    if ($geyser -and -not $geyser.HasExited) { try { Stop-Process -Id $geyser.Id -Force -ErrorAction SilentlyContinue } catch { } }
    foreach ($process in $owned) { if ($process -and -not $process.HasExited) { try { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } catch { } } }
    if ($oldNodePath) { $env:NODE_PATH = $oldNodePath } else { Remove-Item Env:NODE_PATH -ErrorAction SilentlyContinue }
    if ($oldMcData) { $env:MULTIPAPER_MC_DATA_26_3 = $oldMcData } else { Remove-Item Env:MULTIPAPER_MC_DATA_26_3 -ErrorAction SilentlyContinue }
}
