[CmdletBinding()]
param(
    [string] $RconHost1 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort1,
    [Parameter(Mandatory = $true)] [int] $ProcessId1,
    [string[]] $ExpectedPlayers1 = @(),
    [int] $ExpectedZombieCount1 = -1,
    [string] $ZombieTag1 = '',
    [string] $RconHost2 = '127.0.0.1',
    [int] $RconPort2 = 0,
    [int] $ProcessId2 = 0,
    [string[]] $ExpectedPlayers2 = @(),
    [int] $ExpectedZombieCount2 = -1,
    [string] $ZombieTag2 = '',
    [int] $MasterProcessId = 0,
    [ValidatePattern('^[A-Za-z]:?$')] [string] $DiskDriveLetter = 'C',
    [ValidateRange(1, 10000)] [int] $SampleCount = 9,
    [ValidateRange(1, 3600)] [int] $IntervalSeconds = 10,
    [Parameter(Mandatory = $true)] [string] $OutputPath
)

$ErrorActionPreference = 'Stop'
$RconPassword = $env:MULTIPAPER_RCON_PASSWORD
if ([string]::IsNullOrWhiteSpace($RconPassword)) {
    throw 'Set MULTIPAPER_RCON_PASSWORD to the disposable benchmark RCON password before running this collector.'
}
if (($RconPort2 -gt 0) -xor ($ProcessId2 -gt 0)) {
    throw 'Provide both RconPort2 and ProcessId2 for a second backend.'
}
if (($ExpectedZombieCount1 -ge 0 -or -not [string]::IsNullOrWhiteSpace($ZombieTag1)) -and $ExpectedZombieCount1 -lt 0) {
    throw 'ExpectedZombieCount1 must be set when ZombieTag1 is provided.'
}
if (($ExpectedZombieCount2 -ge 0 -or -not [string]::IsNullOrWhiteSpace($ZombieTag2)) -and $ExpectedZombieCount2 -lt 0) {
    throw 'ExpectedZombieCount2 must be set when ZombieTag2 is provided.'
}
foreach ($tag in @($ZombieTag1, $ZombieTag2)) {
    if ($tag -and $tag -notmatch '^[A-Za-z0-9_]{1,32}$') {
        throw "Invalid zombie tag '$tag'; use only letters, digits, and underscores."
    }
}

function Read-Exact([System.Net.Sockets.NetworkStream] $Stream, [int] $Count) {
    $buffer = [byte[]]::new($Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw 'RCON connection closed mid-packet.' }
        $offset += $read
    }
    return ,$buffer
}

function Send-RconPacket([int] $PacketId, [int] $PacketType, [string] $Body, [System.Net.Sockets.NetworkStream] $Stream) {
    $payload = [Text.Encoding]::UTF8.GetBytes($Body)
    $packet = [byte[]]::new(14 + $payload.Length)
    [BitConverter]::GetBytes([int]($packet.Length - 4)).CopyTo($packet, 0)
    [BitConverter]::GetBytes($PacketId).CopyTo($packet, 4)
    [BitConverter]::GetBytes($PacketType).CopyTo($packet, 8)
    $payload.CopyTo($packet, 12)
    $Stream.Write($packet, 0, $packet.Length)

    $header = Read-Exact $Stream 4
    $length = [BitConverter]::ToInt32($header, 0)
    if ($length -lt 10) { throw "Invalid RCON packet length: $length" }
    $response = Read-Exact $Stream $length
    [pscustomobject] @{
        Id = [BitConverter]::ToInt32($response, 0)
        Text = [Text.Encoding]::UTF8.GetString($response, 8, $length - 10)
    }
}

function Get-RconText([string] $HostName, [int] $Port, [string] $Command) {
    $client = [Net.Sockets.TcpClient]::new($HostName, $Port)
    try {
        $stream = $client.GetStream()
        $auth = Send-RconPacket 1 3 $RconPassword $stream
        if ($auth.Id -eq -1) { throw "RCON authentication failed for ${HostName}:$Port." }
        $reply = Send-RconPacket 2 2 $Command $stream
        return $reply.Text
    } finally {
        $client.Close()
    }
}

function Remove-ColorCodes([string] $Text) {
    $pattern = '[' + [char]0x15 + [char]0xA7 + '][0-9a-fk-or]'
    return [regex]::Replace($Text, $pattern, '')
}

function Get-TpsSample([string] $HostName, [int] $Port) {
    $text = Remove-ColorCodes (Get-RconText $HostName $Port 'tps')
    $line = [regex]::Match($text, 'TPS from last[^:]*:\s*([\d., ]+)')
    if (-not $line.Success) { throw "Could not parse TPS output from ${HostName}:$Port`: $text" }
    $values = [regex]::Matches($line.Groups[1].Value, '[\d]+(?:\.[\d]+)?')
    if ($values.Count -lt 3) { throw "TPS output contained fewer than three values from ${HostName}:$Port`: $text" }
    $index = if ($line.Value -match 'last\s+5s\s*,\s*1m') { 1 } else { 0 }
    [double]::Parse($values[$index].Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-MsptSample([string] $HostName, [int] $Port) {
    $text = Remove-ColorCodes (Get-RconText $HostName $Port 'mspt')
    $triplets = [regex]::Matches($text, '([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)')
    if ($triplets.Count -lt 3) { throw "Could not parse three MSPT windows from ${HostName}:$Port`: $text" }
    $numbers = @()
    foreach ($triplet in $triplets[0..2]) {
        $numbers += ,@(
            [double]::Parse($triplet.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($triplet.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($triplet.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
        )
    }
    [pscustomobject] @{
        Avg5s = $numbers[0][0]; Min5s = $numbers[0][1]; Max5s = $numbers[0][2]
        Avg10s = $numbers[1][0]; Min10s = $numbers[1][1]; Max10s = $numbers[1][2]
        Avg1m = $numbers[2][0]; Min1m = $numbers[2][1]; Max1m = $numbers[2][2]
    }
}

function Get-PlayerList([string] $HostName, [int] $Port, [string[]] $ExpectedPlayers) {
    $text = (Get-RconText $HostName $Port 'list').Trim()
    foreach ($name in $ExpectedPlayers) {
        if ($text -notmatch [regex]::Escape($name)) {
            throw "Expected player '$name' was not online on ${HostName}:$Port`: $text"
        }
    }
    return $text
}

function Get-ZombieCount([string] $HostName, [int] $Port, [string] $Tag, [int] $Expected) {
    if ($Expected -lt 0) { return $null }
    $holder = 'bench_count'
    $null = Get-RconText $HostName $Port 'scoreboard objectives add bench dummy'
    $null = Get-RconText $HostName $Port "scoreboard players set $holder bench 0"
    $selector = if ($Tag) { "@e[type=minecraft:zombie,tag=$Tag]" } else { '@e[type=minecraft:zombie]' }
    $null = Get-RconText $HostName $Port "execute as $selector run scoreboard players add $holder bench 1"
    $text = Get-RconText $HostName $Port "scoreboard players get $holder bench"
    $match = [regex]::Match($text, "$holder has (\d+)")
    if (-not $match.Success) { throw "Could not read zombie count from ${HostName}:$Port`: $text" }
    $count = [int]$match.Groups[1].Value
    if ($count -ne $Expected) { throw "Expected $Expected zombies on ${HostName}:$Port, found $count." }
    return $count
}

function Get-DiskSample([string] $DriveLetter) {
    $drive = [regex]::Escape($DriveLetter.TrimEnd(':').ToLowerInvariant())
    $sample = Get-Counter -Counter '\PhysicalDisk(*)\Avg. Disk sec/Read', '\PhysicalDisk(*)\Avg. Disk sec/Write', '\PhysicalDisk(*)\Current Disk Queue Length' -ErrorAction Stop
    $read = $sample.CounterSamples | Where-Object { $_.Path -match '\\physicaldisk\([^)]*\)\\avg\. disk sec/read$' -and $_.InstanceName -match "(^|\s)$drive`:$" } | Select-Object -First 1
    $write = $sample.CounterSamples | Where-Object { $_.Path -match '\\physicaldisk\([^)]*\)\\avg\. disk sec/write$' -and $_.InstanceName -match "(^|\s)$drive`:$" } | Select-Object -First 1
    $queue = $sample.CounterSamples | Where-Object { $_.Path -match '\\physicaldisk\([^)]*\)\\current disk queue length$' -and $_.InstanceName -match "(^|\s)$drive`:$" } | Select-Object -First 1
    if ($null -eq $read -or $null -eq $write -or $null -eq $queue) {
        throw "Could not find PhysicalDisk read/write/queue counters for drive $DriveLetter`:"
    }
    [pscustomobject] @{
        ReadLatencyMs = [math]::Round([double]$read.CookedValue * 1000, 3)
        WriteLatencyMs = [math]::Round([double]$write.CookedValue * 1000, 3)
        QueueLength = [math]::Round([double]$queue.CookedValue, 3)
    }
}

$processIds = [ordered]@{ S1 = $ProcessId1 }
if ($ProcessId2 -gt 0) { $processIds.S2 = $ProcessId2 }
if ($MasterProcessId -gt 0) { $processIds.Master = $MasterProcessId }
$previousCpu = @{}
foreach ($name in $processIds.Keys) {
    $process = Get-Process -Id $processIds[$name] -ErrorAction Stop
    if ($null -eq $process.CPU) { throw "CPU time unavailable for process $($processIds[$name])." }
    $previousCpu[$name] = [double]$process.CPU
}

$rows = [System.Collections.Generic.List[object]]::new()
$start = Get-Date
$previousAt = $start
for ($sample = 1; $sample -le $SampleCount; $sample++) {
    Start-Sleep -Seconds $IntervalSeconds
    $now = Get-Date
    $interval = ($now - $previousAt).TotalSeconds
    $list1 = Get-PlayerList $RconHost1 $RconPort1 $ExpectedPlayers1
    $tps1 = Get-TpsSample $RconHost1 $RconPort1
    $mspt1 = Get-MsptSample $RconHost1 $RconPort1
    $zombies1 = Get-ZombieCount $RconHost1 $RconPort1 $ZombieTag1 $ExpectedZombieCount1

    $list2 = $null; $tps2 = $null; $mspt2 = $null; $zombies2 = $null
    if ($RconPort2 -gt 0) {
        $list2 = Get-PlayerList $RconHost2 $RconPort2 $ExpectedPlayers2
        $tps2 = Get-TpsSample $RconHost2 $RconPort2
        $mspt2 = Get-MsptSample $RconHost2 $RconPort2
        $zombies2 = Get-ZombieCount $RconHost2 $RconPort2 $ZombieTag2 $ExpectedZombieCount2
    }

    $cpu = @{}; $workingSet = 0
    foreach ($name in $processIds.Keys) {
        $process = Get-Process -Id $processIds[$name] -ErrorAction Stop
        $cpu[$name] = [math]::Round((([double]$process.CPU - $previousCpu[$name]) / $interval) * 100, 2)
        $previousCpu[$name] = [double]$process.CPU
        $workingSet += $process.WorkingSet64
    }

    $row = [ordered]@{
        ElapsedSeconds = [math]::Round(($now - $start).TotalSeconds, 1)
        TpsS1 = $tps1; MsptS1_5sAvg = $mspt1.Avg5s; MsptS1_10sAvg = $mspt1.Avg10s; MsptS1_10sMax = $mspt1.Max10s; MsptS1_1mAvg = $mspt1.Avg1m
        CpuS1PercentOfOneCore = $cpu.S1; ZombieCountS1 = $zombies1; PlayersS1 = $list1
    }
    if ($RconPort2 -gt 0) {
        $row.TpsS2 = $tps2; $row.MsptS2_5sAvg = $mspt2.Avg5s; $row.MsptS2_10sAvg = $mspt2.Avg10s; $row.MsptS2_10sMax = $mspt2.Max10s; $row.MsptS2_1mAvg = $mspt2.Avg1m
        $row.CpuS2PercentOfOneCore = $cpu.S2; $row.ZombieCountS2 = $zombies2; $row.PlayersS2 = $list2
    }
    if ($MasterProcessId -gt 0) { $row.CpuMasterPercentOfOneCore = $cpu.Master }
    $disk = Get-DiskSample $DiskDriveLetter
    $row.DiskDrive = $DiskDriveLetter.TrimEnd(':').ToUpperInvariant()
    $row.DiskReadLatencyMs = $disk.ReadLatencyMs
    $row.DiskWriteLatencyMs = $disk.WriteLatencyMs
    $row.DiskQueueLength = $disk.QueueLength
    $row.CpuTotalPercentOfOneCore = [math]::Round(($cpu.Values | Measure-Object -Sum).Sum, 2)
    $row.TotalWorkingSetGiB = [math]::Round($workingSet / 1GB, 2)
    $rows.Add([pscustomobject]$row)
    $previousAt = $now
}

$absoluteOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($absoluteOutputPath)
if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
$rows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath $absoluteOutputPath
Write-Output "Wrote $($rows.Count) validated samples to $absoluteOutputPath"
