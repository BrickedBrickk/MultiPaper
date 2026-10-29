param(
    [Parameter(Mandatory)][string]$FixtureRoot,
    [Parameter(Mandatory)][string]$Archive,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedArchiveSha256,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedMasterSha256,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedPaperclipSha256,
    [Parameter(Mandatory)][string]$PreparedServerJar,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedPreparedServerSha256,
    [string]$TemplateRoot = 'D:\multipaper-tests\system-temp\rc5-v43-chest-418-e9-273-AtoB-20261029',
    [string]$Java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [string]$Node = 'node',
    [string]$NodeModules = "$env:TEMP\mp-26.3-protocol-probe-deps\node_modules",
    [string]$MinecraftData = "$env:TEMP\minecraft-data-pr1301-current-924e26d6\data\pc\26.3",
    [int]$MasterPort = 33851,
    [int]$ProxyPort = 33852,
    [int]$OwnerPort = 33853,
    [int]$PeerPort = 33854,
    [int]$OwnerRconPort = 33861,
    [int]$PeerRconPort = 33862,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = [IO.Path]::GetFullPath($FixtureRoot)
$fixtureBase = [IO.Path]::GetFullPath('D:\multipaper-tests\system-temp').TrimEnd('\') + '\'
if (-not $root.StartsWith($fixtureBase, [StringComparison]::OrdinalIgnoreCase)) { throw 'FixtureRoot must be under D:\multipaper-tests\system-temp.' }
if (Test-Path -LiteralPath $root) { throw "Refusing to reuse fixture path: $root" }
if (-not (Test-Path -LiteralPath (Join-Path $TemplateRoot '.multipaper-disposable'))) { throw 'Template root lacks the disposable marker.' }
if (-not (Test-Path -LiteralPath $Java) -or -not (Test-Path -LiteralPath (Join-Path (Split-Path $Java) 'javac.exe'))) { throw 'Temurin Java 25 runtime/toolchain is unavailable.' }
if (-not (Test-Path -LiteralPath (Join-Path $NodeModules 'minecraft-protocol'))) { throw 'minecraft-protocol dependencies are unavailable.' }
if (-not (Test-Path -LiteralPath (Join-Path $MinecraftData 'protocol.json'))) { throw 'Minecraft 26.3 protocol data is unavailable.' }
$Archive = (Resolve-Path -LiteralPath $Archive).Path
$archiveHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash
if ($archiveHash -ne $ExpectedArchiveSha256) { throw "Unexpected candidate archive SHA-256: $archiveHash" }

$expectedMaster = $ExpectedMasterSha256
$expectedPaper = $ExpectedPaperclipSha256
$ports = @($MasterPort, $ProxyPort, $OwnerPort, $PeerPort, $OwnerRconPort, $PeerRconPort)
if ($ports.Count -ne ($ports | Select-Object -Unique).Count) { throw 'Fixture ports must be unique.' }
foreach ($port in $ports) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port already has a listener; refusing to start." }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[void](New-Item -ItemType Directory -Path $root)
Set-Content -LiteralPath (Join-Path $root '.multipaper-disposable') -Value 'Synthetic single-player disconnect/leave-message capture. No live accounts or worlds.' -Encoding ascii
foreach ($component in @('master', 'backend-a', 'backend-b', 'logs')) { [void](New-Item -ItemType Directory -Path (Join-Path $root $component)) }

$masterJar = Join-Path $root 'master\master.jar'
$paperA = Join-Path $root 'backend-a\server.jar'
$paperB = Join-Path $root 'backend-b\server.jar'
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    foreach ($entry in @(
        @{ Name = 'bin/multipaper-master-3.0.0.jar'; Destination = $masterJar; Hash = $expectedMaster },
        @{ Name = 'bin/multipaper-paperclip-26.3-RC5.jar'; Destination = $paperA; Hash = $expectedPaper }
    )) {
        $zipEntry = $zip.GetEntry($entry.Name)
        if ($null -eq $zipEntry) { throw "Package member missing: $($entry.Name)" }
        $input = $zipEntry.Open()
        $output = [IO.File]::Create($entry.Destination)
        try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        if ((Get-FileHash -LiteralPath $entry.Destination -Algorithm SHA256).Hash -ne $entry.Hash) { throw "Extracted artifact hash mismatch: $($entry.Name)" }
    }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath $paperA -Destination $paperB

function Copy-TemplateTree([string]$Source, [string]$Destination) {
    [void](New-Item -ItemType Directory -Force -Path $Destination)
    & robocopy $Source $Destination /E /MT:8 /R:1 /W:1 /COPY:DAT /DCOPY:DAT /NFL /NDL /NJH /NJS
    if ($LASTEXITCODE -gt 7) { throw "Fixture dependency copy failed with robocopy exit $LASTEXITCODE." }
}

$sourceBackend = Join-Path $TemplateRoot 'backend-a'
$worldName = 'rc5leave' + (Get-Date -Format 'MMddHHmmss')
$ownerPassword = [Guid]::NewGuid().ToString('N')
$peerPassword = [Guid]::NewGuid().ToString('N')
foreach ($backend in @('backend-a', 'backend-b')) {
    $destination = Join-Path $root $backend
    foreach ($name in @('eula.txt', 'server.properties', 'multipaper.yml', 'spigot.yml', 'bukkit.yml', 'purpur.yml', 'commands.yml')) {
        Copy-Item -LiteralPath (Join-Path $sourceBackend $name) -Destination (Join-Path $destination $name)
    }
    foreach ($name in @('.paper', 'cache', 'config', 'libraries', 'versions')) {
        $source = Join-Path $sourceBackend $name
        if (Test-Path -LiteralPath $source -PathType Container) { Copy-TemplateTree $source (Join-Path $destination $name) }
    }
}

function Set-Property([string]$Path, [string]$Name, [string]$Value) {
    $text = Get-Content -LiteralPath $Path -Raw
    $pattern = '(?m)^' + [regex]::Escape($Name) + '=.*$'
    if ([regex]::IsMatch($text, $pattern)) {
        $text = [regex]::Replace($text, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) "$Name=$Value" }, 1)
    } else {
        $text = $text.TrimEnd() + "`r`n$Name=$Value`r`n"
    }
    Set-Content -LiteralPath $Path -Value $text -NoNewline
}

$backendNames = @{ 'backend-a' = 'leave-a'; 'backend-b' = 'leave-b' }
$backendPorts = @{ 'backend-a' = $OwnerPort; 'backend-b' = $PeerPort }
$rconPorts = @{ 'backend-a' = $OwnerRconPort; 'backend-b' = $PeerRconPort }
$rconPasswords = @{ 'backend-a' = $ownerPassword; 'backend-b' = $peerPassword }
foreach ($backend in @('backend-a', 'backend-b')) {
    $directory = Join-Path $root $backend
    $properties = Join-Path $directory 'server.properties'
    foreach ($entry in @(
        @('server-ip', '127.0.0.1'), @('server-port', [string]$backendPorts[$backend]),
        @('level-name', $worldName), @('online-mode', 'false'), @('enforce-secure-profile', 'false'),
        @('enable-rcon', 'true'), @('rcon.ip', '127.0.0.1'), @('rcon.port', [string]$rconPorts[$backend]),
        @('rcon.password', [string]$rconPasswords[$backend]), @('view-distance', '4'), @('simulation-distance', '4')
    )) { Set-Property $properties $entry[0] $entry[1] }

    $multiPath = Join-Path $directory 'multipaper.yml'
    $multi = Get-Content -LiteralPath $multiPath -Raw
    $multi = [regex]::Replace($multi, '(?m)^([ \t]*master-address:[ \t]*).*$ ', '${1}127.0.0.1:' + $MasterPort)
    # The source file's line has no trailing space; keep the expression strict and deterministic.
    $multi = [regex]::Replace($multi, '(?m)^([ \t]*master-address:[ \t]*).*$' , ('${1}127.0.0.1:' + $MasterPort))
    $multi = [regex]::Replace($multi, '(?m)^([ \t]*my-name:[ \t]*).*$', ('${1}' + $backendNames[$backend]))
    Set-Content -LiteralPath $multiPath -Value $multi -NoNewline
}

$templateServerHash = (Get-FileHash -LiteralPath (Join-Path $sourceBackend 'versions\26.3\multipaper-26.3.jar') -Algorithm SHA256).Hash
$preparedServerPath = Join-Path $root 'backend-a\versions\26.3\multipaper-26.3.jar'
if ($PreparedServerJar) {
    $PreparedServerJar = (Resolve-Path -LiteralPath $PreparedServerJar).Path
    $preparedServerHash = (Get-FileHash -LiteralPath $PreparedServerJar -Algorithm SHA256).Hash
    if (-not $ExpectedPreparedServerSha256 -or $preparedServerHash -ne $ExpectedPreparedServerSha256) { throw "Unexpected prepared server JAR SHA-256: $preparedServerHash" }
    foreach ($backend in @('backend-a', 'backend-b')) {
        Copy-Item -LiteralPath $PreparedServerJar -Destination (Join-Path $root "$backend\versions\26.3\multipaper-26.3.jar")
    }
} else {
    $preparedServerHash = (Get-FileHash -LiteralPath $preparedServerPath -Algorithm SHA256).Hash
    if ($preparedServerHash -ne $templateServerHash) { throw 'Prepared Paper server cache did not copy byte-exactly.' }
}

$logRoot = Join-Path $root 'logs'
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$backendProcesses = @{}
$master = $null
$masterOut = $null
$masterErr = $null
$masterOutTask = $null
$masterErrTask = $null
$oldNodePath = $env:NODE_PATH
$oldMcData = $env:MULTIPAPER_MC_DATA_26_3

function Start-MasterProcess {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Java
    $info.WorkingDirectory = Join-Path $root 'master'
    $info.Arguments = "-jar master.jar $MasterPort $ProxyPort"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $proc = [Diagnostics.Process]::new()
    $proc.StartInfo = $info
    if (-not $proc.Start()) { throw 'Master process failed to start.' }
    $processes.Add($proc)
    $script:masterOut = [IO.File]::Create((Join-Path $logRoot 'master.out'))
    $script:masterErr = [IO.File]::Create((Join-Path $logRoot 'master.err'))
    $script:masterOutTask = $proc.StandardOutput.BaseStream.CopyToAsync($script:masterOut)
    $script:masterErrTask = $proc.StandardError.BaseStream.CopyToAsync($script:masterErr)
    return $proc
}

function Start-Backend([string]$Name) {
    $output = Join-Path $logRoot "$Name.out"
    $error = Join-Path $logRoot "$Name.err"
    $proc = Start-Process -FilePath $Java -WorkingDirectory (Join-Path $root $Name) -ArgumentList @('-jar', 'server.jar', '--nogui') `
        -WindowStyle Hidden -RedirectStandardOutput $output -RedirectStandardError $error -PassThru
    $processes.Add($proc)
    $backendProcesses[$Name] = $proc
    return $proc
}

function Wait-ForLog([string]$Path, [string]$Pattern, [Diagnostics.Process]$Process, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) { throw "$Name exited before readiness." }
        if ((Test-Path -LiteralPath $Path) -and (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name did not become ready within $TimeoutSeconds seconds."
}

function Read-RconPacket($Stream) {
    $header = [byte[]]::new(4); $offset = 0
    while ($offset -lt 4) { $read = $Stream.Read($header, $offset, 4 - $offset); if (-not $read) { throw 'RCON closed while reading header.' }; $offset += $read }
    $length = [BitConverter]::ToInt32($header, 0)
    if ($length -lt 10 -or $length -gt 4110) { throw "Invalid RCON response length $length." }
    $body = [byte[]]::new($length); $offset = 0
    while ($offset -lt $length) { $read = $Stream.Read($body, $offset, $length - $offset); if (-not $read) { throw 'RCON closed while reading body.' }; $offset += $read }
    return [pscustomobject]@{ Id = [BitConverter]::ToInt32($body, 0); Text = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10) }
}

function Write-RconPacket($Stream, [int]$Id, [int]$Type, [string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text); $length = 10 + $bytes.Length; $packet = [byte[]]::new(4 + $length)
    [BitConverter]::GetBytes($length).CopyTo($packet, 0); [BitConverter]::GetBytes($Id).CopyTo($packet, 4); [BitConverter]::GetBytes($Type).CopyTo($packet, 8)
    $bytes.CopyTo($packet, 12); $Stream.Write($packet, 0, $packet.Length); $Stream.Flush()
}

function Invoke-Rcon([string]$Backend, [string]$Command) {
    $client = [Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout = 10000; $client.SendTimeout = 10000
    try {
        $client.Connect('127.0.0.1', $rconPorts[$Backend]); $stream = $client.GetStream(); $stream.ReadTimeout = 10000; $stream.WriteTimeout = 10000
        $id = Get-Random -Minimum 10000 -Maximum 1800000000
        Write-RconPacket $stream $id 3 $rconPasswords[$Backend]
        $auth = Read-RconPacket $stream
        if ($auth.Id -ne $id) { throw "RCON authentication rejected for disposable $Backend." }
        Write-RconPacket $stream ($id + 1) 2 $Command
        $reply = Read-RconPacket $stream
        if ($reply.Id -ne ($id + 1)) { throw 'Unexpected RCON command response.' }
        return $reply.Text
    } finally { $client.Dispose() }
}

function Stop-Backend([string]$Name) {
    $proc = $backendProcesses[$Name]
    if ($null -eq $proc -or $proc.HasExited) { return }
    try { [void](Invoke-Rcon $Name 'stop') } catch { }
    if (-not $proc.WaitForExit(60000)) { throw "Disposable $Name failed to stop gracefully within 60 seconds." }
}

$resultCode = 1
try {
    $master = Start-MasterProcess
    $masterDeadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        if ($master.HasExited) { throw 'Disposable Master exited before backends started.' }
        $probe = [Net.Sockets.TcpClient]::new()
        try { $probe.Connect('127.0.0.1', $MasterPort); $masterReady = $true } catch { $masterReady = $false } finally { $probe.Dispose() }
        if (-not $masterReady) { Start-Sleep -Milliseconds 250 }
    } while (-not $masterReady -and [DateTime]::UtcNow -lt $masterDeadline)
    if (-not $masterReady) { throw 'Disposable Master listener did not become ready.' }

    $owner = Start-Backend 'backend-a'
    Wait-ForLog (Join-Path $logRoot 'backend-a.out') 'Done \(' $owner 'Backend A'
    $peer = Start-Backend 'backend-b'
    Wait-ForLog (Join-Path $logRoot 'backend-b.out') 'Done \(' $peer 'Backend B'
    Start-Sleep -Seconds 3

    $env:NODE_PATH = $NodeModules
    $env:MULTIPAPER_MC_DATA_26_3 = $MinecraftData
    $capture = Join-Path $PSScriptRoot 'test-fixtures\duplicate-leave\capture-single-disconnect.js'
    $captureJson = Join-Path $root 'leave-capture.json'
    & $Node $capture '127.0.0.1' "$OwnerPort" "$PeerPort" $captureJson
    $resultCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $captureJson)) { throw 'The Node probe did not create its machine-readable result.' }
    $result = Get-Content -LiteralPath $captureJson -Raw | ConvertFrom-Json
    $result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $root 'leave-capture-readable.json') -Encoding utf8
    if ($result.clientsReady -ne 4) { throw 'Not all four synthetic client sessions reached play state.' }
    if ($result.firstDisconnect.ownerObserverMessages -ne 1 -or $result.firstDisconnect.peerObserverMessages -ne 1 -or $result.secondDisconnect.ownerObserverMessages -ne 1 -or $result.secondDisconnect.peerObserverMessages -ne 1) { $resultCode = 1 }
} finally {
    foreach ($backend in @('backend-a', 'backend-b')) {
        try { Stop-Backend $backend } catch { Write-Warning $_ }
    }
    if ($null -ne $master -and -not $master.HasExited) {
        try { $master.StandardInput.WriteLine('exit'); $master.StandardInput.Flush() } catch { }
        if (-not $master.WaitForExit(60000)) {
            Write-Warning 'Disposable Master did not exit gracefully; stopping only its exact started process.'
            try { $master.Kill($true); [void]$master.WaitForExit(10000) } catch { }
        }
    }
    foreach ($proc in $processes) {
        try { if (-not $proc.HasExited) { $proc.Kill($true); [void]$proc.WaitForExit(10000) } } catch { }
    }
    if ($null -ne $masterOut) { $masterOut.Dispose() }
    if ($null -ne $masterErr) { $masterErr.Dispose() }
    if ($null -ne $masterOutTask) { try { $masterOutTask.Wait(10000) | Out-Null } catch { } }
    if ($null -ne $masterErrTask) { try { $masterErrTask.Wait(10000) | Out-Null } catch { } }
    $env:NODE_PATH = $oldNodePath
    $env:MULTIPAPER_MC_DATA_26_3 = $oldMcData
}

$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
if ($listeners.Count) {
    $listeners | Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table | Out-String | Write-Output
    throw 'Disposable leave-message test listener remained after cleanup.'
}
$remaining = @($processes | Where-Object { -not $_.HasExited })
if ($remaining.Count) { throw 'A disposable process started by this runner remains alive.' }

$metadata = [ordered]@{
    ArchiveSha256 = $archiveHash
    MasterSha256 = (Get-FileHash -LiteralPath $masterJar -Algorithm SHA256).Hash
    PaperclipSha256 = (Get-FileHash -LiteralPath $paperA -Algorithm SHA256).Hash
    NestedServerSha256 = $preparedServerHash
    WorldName = $worldName
    PortsCheckedClosed = $ports
    Result = if ($resultCode -eq 0) { 'PASS' } else { 'FAIL' }
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'RUN-METADATA.json') -Encoding utf8
Write-Output ("Fixture retained: {0}" -f $root)
Write-Output ("RESULT={0}" -f $metadata.Result)
if ($resultCode -ne 0) { exit 1 }
