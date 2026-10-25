param(
    [Parameter(Mandatory)][string]$FixtureRoot,
    [string]$ExpectedPaperclipSha256 = 'DC4A57CF66E022AEDD4FD592F788F0D349E535C63527C14309976B9D859DF15F',
    [string]$ExpectedMasterSha256 = 'E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7',
    [string]$JavaExecutable = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [int]$MasterPort = 32651,
    [int]$ProxyPort = 32652,
    [int]$BackendARconPort = 32661,
    [int]$BackendBRconPort = 32662,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$FixtureRoot = (Resolve-Path -LiteralPath $FixtureRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $FixtureRoot '.multipaper-disposable'))) {
    throw 'Refusing to run without the disposable-fixture marker.'
}
foreach ($relative in @('backend-a\server.jar', 'backend-b\server.jar')) {
    if ((Get-FileHash (Join-Path $FixtureRoot $relative) -Algorithm SHA256).Hash -ne $ExpectedPaperclipSha256) {
        throw "Paperclip hash mismatch: $relative"
    }
}
if ((Get-FileHash (Join-Path $FixtureRoot 'master\master.jar') -Algorithm SHA256).Hash -ne $ExpectedMasterSha256) {
    throw 'Master hash mismatch.'
}

function Get-Property([string]$Path, [string]$Name) {
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name in fixture server.properties." }
    return $line.Substring($Name.Length + 1)
}

function Start-LoggedJava([string]$WorkingDirectory, [string[]]$Arguments, [string]$Stdout, [string]$Stderr) {
    return Start-Process -FilePath $JavaExecutable -WorkingDirectory $WorkingDirectory -ArgumentList $Arguments `
        -WindowStyle Hidden -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru
}

function Wait-Log([string]$Path, [string]$Pattern, [Diagnostics.Process]$Process, [string]$Label) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) { throw "$Label exited before readiness." }
        if ((Test-Path -LiteralPath $Path) -and (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Label did not reach readiness."
}

function Read-RconPacket($Stream) {
    $header = [byte[]]::new(4); $offset = 0
    while ($offset -lt 4) { $n = $Stream.Read($header, $offset, 4 - $offset); if (-not $n) { throw 'RCON closed reading header.' }; $offset += $n }
    $length = [BitConverter]::ToInt32($header, 0); $body = [byte[]]::new($length); $offset = 0
    while ($offset -lt $length) { $n = $Stream.Read($body, $offset, $length - $offset); if (-not $n) { throw 'RCON closed reading body.' }; $offset += $n }
    return [pscustomobject]@{ Id = [BitConverter]::ToInt32($body, 0); Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10) }
}

function Write-RconPacket($Stream, [int]$Id, [int]$Type, [string]$Text) {
    $payload = [Text.Encoding]::UTF8.GetBytes($Text); $length = 10 + $payload.Length; $packet = [byte[]]::new(4 + $length)
    [BitConverter]::GetBytes($length).CopyTo($packet, 0); [BitConverter]::GetBytes($Id).CopyTo($packet, 4); [BitConverter]::GetBytes($Type).CopyTo($packet, 8); $payload.CopyTo($packet, 12)
    $Stream.Write($packet, 0, $packet.Length); $Stream.Flush()
}

function Invoke-Rcon([int]$Port, [string]$Password, [string]$Command) {
    $client = [Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout = 10000; $client.SendTimeout = 10000
    try {
        $client.Connect('127.0.0.1', $Port); $stream = $client.GetStream(); $stream.ReadTimeout = 10000; $stream.WriteTimeout = 10000
        $id = Get-Random -Minimum 10000 -Maximum 1800000000; Write-RconPacket $stream $id 3 $Password
        if ((Read-RconPacket $stream).Id -ne $id) { throw "RCON authentication failed on $Port." }
        Write-RconPacket $stream ($id + 1) 2 $Command; $reply = Read-RconPacket $stream
        if ($reply.Id -ne ($id + 1)) { throw "Unexpected RCON response on $Port." }
        return $reply.Text
    } finally { $client.Dispose() }
}

function Add-VarInt([Collections.Generic.List[byte]]$Bytes, [int]$Value) {
    do {
        $part = $Value -band 0x7f
        $Value = $Value -shr 7
        if ($Value -ne 0) { $part = $part -bor 0x80 }
        $Bytes.Add([byte]$part)
    } while ($Value -ne 0)
}

function New-LoginHandshake([int]$Port) {
    $body = [Collections.Generic.List[byte]]::new()
    Add-VarInt $body 0
    Add-VarInt $body 777
    $hostname = [Text.Encoding]::UTF8.GetBytes('fragment.test')
    Add-VarInt $body $hostname.Length
    $body.AddRange($hostname)
    $body.Add([byte](($Port -shr 8) -band 0xff)); $body.Add([byte]($Port -band 0xff)); $body.Add([byte]2)
    $packet = [Collections.Generic.List[byte]]::new()
    Add-VarInt $packet $body.Count
    $packet.AddRange($body)
    return ,$packet.ToArray()
}

function Test-Handshake([byte[]]$Packet, [int]$SplitAt) {
    $client = [Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout = 2500; $client.SendTimeout = 5000
    try {
        $client.Connect('127.0.0.1', $ProxyPort)
        $stream = $client.GetStream()
        if ($SplitAt -gt 0) {
            $stream.Write($Packet, 0, $SplitAt); $stream.Flush()
            Start-Sleep -Milliseconds 150
            $stream.Write($Packet, $SplitAt, $Packet.Length - $SplitAt); $stream.Flush()
        } else {
            $stream.Write($Packet, 0, $Packet.Length); $stream.Flush()
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(3)
        do {
            if ($client.Client.Poll(100000, [Net.Sockets.SelectMode]::SelectRead)) {
                if ($client.Available -eq 0) { return $true }
                return $true # A login disconnect response also means the handshake was rejected.
            }
        } while ([DateTime]::UtcNow -lt $deadline)
        return $false # No response: backend accepted the handshake and awaits Login Start.
    } finally { $client.Dispose() }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logRoot = Join-Path $FixtureRoot "proxy-handshake-fragmentation-$stamp"
New-Item -ItemType Directory -Path $logRoot | Out-Null
$paths = @(
    (Join-Path $FixtureRoot 'backend-a\server.properties'), (Join-Path $FixtureRoot 'backend-b\server.properties'),
    (Join-Path $FixtureRoot 'backend-a\spigot.yml'), (Join-Path $FixtureRoot 'backend-b\spigot.yml')
)
$originals = @{}; foreach ($path in $paths) { $originals[$path] = [IO.File]::ReadAllText($path) }
$master = $null; $backendA = $null; $backendB = $null
$passwordA = Get-Property $paths[0] 'rcon.password'; $passwordB = Get-Property $paths[1] 'rcon.password'

try {
    foreach ($backend in @('backend-a', 'backend-b')) {
        $properties = Join-Path $FixtureRoot "$backend\server.properties"
        $text = [IO.File]::ReadAllText($properties) -replace '(?m)^level-name=.*$', 'level-name=proxyfragment_20261025'
        [IO.File]::WriteAllText($properties, $text)
        $spigot = Join-Path $FixtureRoot "$backend\spigot.yml"
        $text = [IO.File]::ReadAllText($spigot) -replace '(?m)^  bungeecord:.*$', '  bungeecord: true'
        [IO.File]::WriteAllText($spigot, $text)
    }

    foreach ($port in @($MasterPort, $ProxyPort, $BackendARconPort, $BackendBRconPort, 32653, 32654)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "TCP port $port already has a listener." }
    }
    $master = Start-LoggedJava (Join-Path $FixtureRoot 'master') @('-jar', 'master.jar', "$MasterPort", "$ProxyPort") (Join-Path $logRoot 'master.out') (Join-Path $logRoot 'master.err')
    Wait-Log (Join-Path $logRoot 'master.out') '\[ProxyServer\] Listening' $master 'Master proxy'
    $backendA = Start-LoggedJava (Join-Path $FixtureRoot 'backend-a') @('-jar', 'server.jar', '--nogui') (Join-Path $logRoot 'backend-a.out') (Join-Path $logRoot 'backend-a.err')
    $backendB = Start-LoggedJava (Join-Path $FixtureRoot 'backend-b') @('-jar', 'server.jar', '--nogui') (Join-Path $logRoot 'backend-b.out') (Join-Path $logRoot 'backend-b.err')
    Wait-Log (Join-Path $logRoot 'backend-a.out') 'Done \(' $backendA 'Backend A'
    Wait-Log (Join-Path $logRoot 'backend-b.out') 'Done \(' $backendB 'Backend B'
    Wait-Log (Join-Path $logRoot 'master.out') 'has started, announcing connected servers' $master 'Backend registration'
    Start-Sleep -Seconds 2

    $packet = New-LoginHandshake $ProxyPort
    $controlClosed = Test-Handshake $packet 0
    if ($controlClosed) { throw 'Unfragmented control handshake was rejected; fixture forwarding setup is invalid.' }
    $failedSplits = [Collections.Generic.List[int]]::new()
    foreach ($splitAt in @(1, 2, 4, ($packet.Length - 1))) {
        if (Test-Handshake $packet $splitAt) { $failedSplits.Add($splitAt) }
    }
    $masterErrors = Get-Content (Join-Path $logRoot 'master.err') -Raw
    if ($failedSplits.Count -gt 0 -or $masterErrors -match 'BufferUnderflowException') {
        throw "Fragmented handshake was rejected at split positions $($failedSplits -join ',') or the proxy parser underflowed. Logs: $logRoot"
    }
    Write-Output "PASS: unfragmented control and TCP splits at 1, 2, 4, and $($packet.Length - 1) bytes remained connected with Bungee forwarding enabled. Logs: $logRoot"
} finally {
    try { Invoke-Rcon $BackendARconPort $passwordA 'save-all flush' | Out-Null } catch { }
    try { Invoke-Rcon $BackendBRconPort $passwordB 'save-all flush' | Out-Null } catch { }
    try { Invoke-Rcon $BackendARconPort $passwordA 'stop' | Out-Null } catch { }
    try { Invoke-Rcon $BackendBRconPort $passwordB 'stop' | Out-Null } catch { }
    foreach ($process in @($backendA, $backendB)) { if ($process -and -not $process.HasExited) { try { $process.WaitForExit(30000) } catch { } } }
    if ($master -and -not $master.HasExited) { try { Stop-Process -Id $master.Id -Force -ErrorAction SilentlyContinue } catch { } }
    foreach ($process in @($backendA, $backendB, $master)) {
        if ($process -and -not $process.HasExited) { try { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } catch { } }
    }
    foreach ($path in $paths) { if ($originals.ContainsKey($path)) { [IO.File]::WriteAllText($path, $originals[$path]) } }
}
