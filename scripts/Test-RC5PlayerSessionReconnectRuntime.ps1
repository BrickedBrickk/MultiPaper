param(
    [Parameter(Mandatory)][string]$FixtureRoot,
    [string]$PaperclipSha256 = '99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85',
    [string]$MasterSha256 = '93FF3AB7E6F2D1DC7192070E90E54F8DD334D3247E74A22F72223B0CAF3E257C',
    [string]$Username = 'RC5Reconnect'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $FixtureRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $root '.multipaper-disposable'))) { throw 'Fixture is not marked .multipaper-disposable' }
$java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe'
$node = (Get-Command node.exe -ErrorAction Stop).Source
$nodeModules = "$env:TEMP\mp-26.3-protocol-probe-deps\node_modules"
$mcData = "$env:TEMP\minecraft-data-pr1301-current-924e26d6\data\pc\26.3"
$probeFile = Join-Path $PSScriptRoot 'test-fixtures\session-registration-runtime\reconnect-owner-transfer.js'
$logs = Join-Path $root ('session-reconnect-runtime-' + (Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Path $logs | Out-Null

foreach ($file in @('backend-a\server.jar', 'backend-b\server.jar')) {
    if ((Get-FileHash -LiteralPath (Join-Path $root $file) -Algorithm SHA256).Hash -ne $PaperclipSha256) { throw "Paperclip hash mismatch: $file" }
}
if ((Get-FileHash -LiteralPath (Join-Path $root 'master\master.jar') -Algorithm SHA256).Hash -ne $MasterSha256) { throw 'Master hash mismatch' }
foreach ($port in @(33151, 33152, 33153, 33154, 33161, 33162)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Fixture port already listening: $port" }
}
if (-not (Test-Path -LiteralPath (Join-Path $nodeModules 'minecraft-protocol'))) { throw 'Pinned minecraft-protocol dependency is missing' }
if (-not (Test-Path -LiteralPath (Join-Path $mcData 'protocol.json'))) { throw 'Pinned Minecraft 26.3 protocol data is missing' }

function Read-Rcon($stream) {
    $header = [byte[]]::new(4); $offset = 0
    while ($offset -lt 4) { $count = $stream.Read($header, $offset, 4 - $offset); if (-not $count) { throw 'RCON closed reading header' }; $offset += $count }
    $length = [BitConverter]::ToInt32($header, 0); $body = [byte[]]::new($length); $offset = 0
    while ($offset -lt $length) { $count = $stream.Read($body, $offset, $length - $offset); if (-not $count) { throw 'RCON closed reading body' }; $offset += $count }
    return [pscustomobject]@{ Id = [BitConverter]::ToInt32($body, 0); Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10) }
}
function Write-Rcon($stream, [int]$id, [int]$type, [string]$text) {
    $payload = [Text.Encoding]::UTF8.GetBytes($text); $length = 10 + $payload.Length; $packet = [byte[]]::new(4 + $length)
    [BitConverter]::GetBytes($length).CopyTo($packet, 0); [BitConverter]::GetBytes($id).CopyTo($packet, 4); [BitConverter]::GetBytes($type).CopyTo($packet, 8); $payload.CopyTo($packet, 12)
    $stream.Write($packet, 0, $packet.Length); $stream.Flush()
}
function Stop-Backend([int]$port, [string]$password) {
    $client = [Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout = 10000
    try {
        $client.Connect('127.0.0.1', $port); $stream = $client.GetStream(); $stream.ReadTimeout = 10000
        $id = Get-Random -Minimum 10000 -Maximum 1800000000; Write-Rcon $stream $id 3 $password
        $auth = Read-Rcon $stream; if ($auth.Id -ne $id) { throw "RCON authentication failed on $port" }
        Write-Rcon $stream ($id + 1) 2 'stop'; $reply = Read-Rcon $stream
        if ($reply.Id -ne ($id + 1)) { throw "RCON response mismatch on $port" }
    } finally { $client.Dispose() }
}
function Wait-Log([string]$path, [string]$pattern, [int]$seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        if ((Test-Path -LiteralPath $path) -and (Select-String -LiteralPath $path -Pattern $pattern -Quiet -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for '$pattern' in $path"
}

$rconA = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'backend-a\server.properties')), '(?m)^rcon.password=(.*)$').Groups[1].Value.Trim()
$rconB = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'backend-b\server.properties')), '(?m)^rcon.password=(.*)$').Groups[1].Value.Trim()
$owned = [Collections.Generic.List[Diagnostics.Process]]::new(); $master = $null; $probeProcess = $null; $masterOut = $null; $masterErr = $null
$failure = $null
try {
    $masterInfo = [Diagnostics.ProcessStartInfo]::new(); $masterInfo.FileName = $java; $masterInfo.WorkingDirectory = Join-Path $root 'master'; $masterInfo.Arguments = '-jar master.jar 33151 33152'; $masterInfo.UseShellExecute = $false; $masterInfo.CreateNoWindow = $true; $masterInfo.RedirectStandardInput = $true; $masterInfo.RedirectStandardOutput = $true; $masterInfo.RedirectStandardError = $true
    $master = [Diagnostics.Process]::new(); $master.StartInfo = $masterInfo; [void]$master.Start(); $owned.Add($master)
    $masterOut = [IO.File]::Create((Join-Path $logs 'master.out')); $masterErr = [IO.File]::Create((Join-Path $logs 'master.err'))
    $stdoutTask = $master.StandardOutput.BaseStream.CopyToAsync($masterOut); $stderrTask = $master.StandardError.BaseStream.CopyToAsync($masterErr)
    Start-Sleep -Seconds 2

    foreach ($backend in @('backend-a', 'backend-b')) {
        $stdout = Join-Path $logs "$backend.out"; $stderr = Join-Path $logs "$backend.err"
        $process = Start-Process -FilePath $java -WorkingDirectory (Join-Path $root $backend) -ArgumentList @('-jar', 'server.jar', '--nogui') -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $owned.Add($process); Wait-Log $stdout 'Done \(' 180
    }

    $env:NODE_PATH = $nodeModules; $env:MULTIPAPER_MC_DATA_26_3 = $mcData
    $nodeOut = Join-Path $logs 'probe.out'; $nodeErr = Join-Path $logs 'probe.err'
    $probeProcess = Start-Process -FilePath $node -WorkingDirectory (Split-Path -Parent $probeFile) -ArgumentList @((Split-Path -Leaf $probeFile), '127.0.0.1', '33153', '33154', $Username) -WindowStyle Hidden -RedirectStandardOutput $nodeOut -RedirectStandardError $nodeErr -PassThru
    $owned.Add($probeProcess)
    if (-not $probeProcess.WaitForExit(90000)) { throw 'Session reconnect protocol probe timed out' }
    if ($probeProcess.ExitCode -ne 0) { throw "Session reconnect protocol probe exited $($probeProcess.ExitCode)" }
    $nodeText = Get-Content -LiteralPath $nodeOut -Raw
    foreach ($stage in @('A_INITIAL', 'A_RECONNECT', 'B_OWNER_TRANSFER')) {
        if ($nodeText -notmatch "LOGIN_READY label=$stage") { throw "Protocol probe missing stage $stage" }
    }
    if ($nodeText -notmatch 'SESSION_RECONNECT_SEQUENCE_COMPLETE') { throw 'Protocol probe did not complete all stages' }
    if (Test-Path -LiteralPath $nodeErr) {
        $nodeErrors = Get-Content -LiteralPath $nodeErr -Raw
        $unexpectedNodeErrors = $nodeErrors -replace '(?s)\(node:\d+\) \[DEP0040\] DeprecationWarning: The `punycode` module is deprecated\.\s*Please use a userland alternative instead\.\s*\(Use `node --trace-deprecation \.\.\.` to show where the warning was created\)\s*', ''
        if (-not [string]::IsNullOrWhiteSpace($unexpectedNodeErrors)) { throw "Protocol probe emitted errors: $unexpectedNodeErrors" }
    }
    Stop-Backend 33161 $rconA; Stop-Backend 33162 $rconB
    foreach ($process in $owned) { if ($process -and -not $process.HasExited) { [void]$process.WaitForExit(30000) } }
    if ($master -and -not $master.HasExited) { $master.StandardInput.WriteLine('shutdown'); $master.StandardInput.Flush(); [void]$master.WaitForExit(30000) }
} catch {
    $failure = $_
} finally {
    if ($probeProcess -and -not $probeProcess.HasExited) { try { Stop-Process -Id $probeProcess.Id -Force -ErrorAction SilentlyContinue } catch { } }
    try { Stop-Backend 33161 $rconA } catch { }
    try { Stop-Backend 33162 $rconB } catch { }
    foreach ($process in $owned) {
        if ($process -and -not $process.HasExited) {
            try { if (-not $process.WaitForExit(30000)) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } } catch { }
        }
    }
    if ($master -and -not $master.HasExited) {
        try { $master.StandardInput.WriteLine('shutdown'); $master.StandardInput.Flush(); if (-not $master.WaitForExit(30000)) { Stop-Process -Id $master.Id -Force -ErrorAction SilentlyContinue } } catch { }
    }
    if ($masterOut) { $masterOut.Dispose() }; if ($masterErr) { $masterErr.Dispose() }
}
if ($failure) { throw $failure }

$events = @([regex]::Matches([IO.File]::ReadAllText((Join-Path $logs 'master.out')), 'RC5_SESSION_REGISTRATION_RESULT backend=(\S+) player=(\S+) session=(\S+) accepted=(true|false)') | ForEach-Object {
    [pscustomobject]@{ Backend = $_.Groups[1].Value; Player = $_.Groups[2].Value; Session = $_.Groups[3].Value; Accepted = $_.Groups[4].Value }
})
if ($events.Count -ne 3) { throw "Expected 3 registration records, found $($events.Count); evidence retained in $logs" }
if (@($events | Where-Object { $_.Accepted -ne 'true' }).Count -ne 0) { throw "A registration was rejected; evidence retained in $logs" }
if (@($events.Player | Select-Object -Unique).Count -ne 1) { throw "Reconnect did not preserve the offline player UUID; evidence retained in $logs" }
if (@($events.Session | Select-Object -Unique).Count -ne 3) { throw "Expected three unique login session epochs; evidence retained in $logs" }
if (($events.Backend -join ',') -ne 'race-a,race-a,race-b') { throw "Unexpected owner registration order: $($events.Backend -join ','); evidence retained in $logs" }

$result = "PASS: one player session registered on A, reconnected on A, then re-registered after owner transfer to B; three accepted epochs. Logs: $logs"
Set-Content -LiteralPath (Join-Path $logs 'RESULT.txt') -Value $result
foreach ($port in @(33151, 33152, 33153, 33154, 33161, 33162)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Fixture listener remained on $port; evidence retained in $logs" }
}
Write-Output $result
