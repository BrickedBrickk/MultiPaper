[CmdletBinding()]
param(
    [string] $RconHost1 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort1,
    [string] $RemoteHost1 = '100.78.108.117',
    [string] $RemoteUser1 = 'crafty1',
    [Parameter(Mandatory = $true)] [int] $ProcessId1,
    [string] $RconHost2 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort2,
    [string] $RemoteHost2 = '100.107.178.51',
    [string] $RemoteUser2 = 'crafty2',
    [Parameter(Mandatory = $true)] [int] $ProcessId2,
    [string] $MasterHost = '100.78.108.117',
    [string] $MasterUser = 'crafty1',
    [int] $MasterProcessId = 0,
    [string] $Tag1 = 'bench_a',
    [string] $Tag2 = 'bench_b',
    [ValidateRange(1, 10000)] [int] $ExpectedZombieCount = 16,
    [int] $ExpectedZombieCount1 = -1,
    [int] $ExpectedZombieCount2 = -1,
    [ValidateRange(1, 1000)] [int] $SampleCount = 9,
    [ValidateRange(1, 3600)] [int] $IntervalSeconds = 10,
    [switch] $AllowPlayers,
    [Parameter(Mandatory = $true)] [string] $OutputPath
)

$ErrorActionPreference = 'Stop'
$RconPassword1 = $env:MULTIPAPER_RCON_PASSWORD1
$RconPassword2 = $env:MULTIPAPER_RCON_PASSWORD2
if ([string]::IsNullOrWhiteSpace($RconPassword1) -or [string]::IsNullOrWhiteSpace($RconPassword2)) {
    throw 'Set MULTIPAPER_RCON_PASSWORD1 and MULTIPAPER_RCON_PASSWORD2 before collecting samples.'
}
foreach ($tag in @($Tag1, $Tag2)) {
    if ($tag -notmatch '^[A-Za-z0-9_]{1,32}$') { throw "Invalid zombie tag '$tag'." }
}
if ($ExpectedZombieCount1 -lt 0) { $ExpectedZombieCount1 = $ExpectedZombieCount }
if ($ExpectedZombieCount2 -lt 0) { $ExpectedZombieCount2 = $ExpectedZombieCount }
if ($ExpectedZombieCount1 -lt 1 -or $ExpectedZombieCount1 -gt 10000) { throw 'ExpectedZombieCount1 must be between 1 and 10000.' }
if ($ExpectedZombieCount2 -lt 1 -or $ExpectedZombieCount2 -gt 10000) { throw 'ExpectedZombieCount2 must be between 1 and 10000.' }
$RconScript = Join-Path $PSScriptRoot '..\Send-RconCommand.ps1'
if (-not (Test-Path -LiteralPath $RconScript -PathType Leaf)) { throw "RCON helper not found: $RconScript" }

function Get-RconText([string] $HostName, [int] $Port, [string] $Password, [string] $Command) {
    return (& $RconScript -Server $HostName -Port $Port -Password $Password -Command $Command | Out-String).Trim()
}

function Remove-ColorCodes([string] $Text) {
    $pattern = '[' + [char]0x15 + [char]0xA7 + '][0-9a-fk-or]'
    return [regex]::Replace($Text, $pattern, '')
}

function Get-TickSample([string] $HostName, [int] $Port, [string] $Password) {
    $tpsText = Remove-ColorCodes (Get-RconText $HostName $Port $Password 'tps')
    $msptText = Remove-ColorCodes (Get-RconText $HostName $Port $Password 'mspt')
    $tpsValues = [regex]::Matches($tpsText, '[\d]+(?:\.[\d]+)?')
    $triplets = [regex]::Matches($msptText, '([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)')
    if ($tpsValues.Count -lt 4 -or $triplets.Count -lt 3) { throw "Could not parse TPS/MSPT from ${HostName}:$Port." }
    [pscustomobject]@{
        Tps1m = [double]::Parse($tpsValues[$tpsValues.Count - 3].Value, [Globalization.CultureInfo]::InvariantCulture)
        Mspt5sAvg = [double]::Parse($triplets[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        Mspt10sAvg = [double]::Parse($triplets[1].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        Mspt10sMax = [double]::Parse($triplets[1].Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
        Mspt1mAvg = [double]::Parse($triplets[2].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        Mspt1mMax = [double]::Parse($triplets[2].Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
}

function Get-ZombieCount([string] $HostName, [int] $Port, [string] $Password, [string] $Tag, [string] $Holder) {
    $null = Get-RconText $HostName $Port $Password "scoreboard players set $Holder bench 0"
    $null = Get-RconText $HostName $Port $Password "execute as @e[type=minecraft:zombie,tag=$Tag] run scoreboard players add $Holder bench 1"
    $result = Get-RconText $HostName $Port $Password "scoreboard players get $Holder bench"
    $match = [regex]::Match($result, "$Holder has (\d+)")
    if (-not $match.Success) { throw "Could not count zombies tagged '$Tag' on ${HostName}:$Port`: $result" }
    return [int]$match.Groups[1].Value
}

function Get-RemoteProcess([string] $HostName, [string] $User, [int] $TargetProcessId) {
    $lines = @(ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=8 "$User@$HostName" "getconf CLK_TCK; cat /proc/$TargetProcessId/stat /proc/$TargetProcessId/status")
    if ($LASTEXITCODE -ne 0) { throw "Could not read process $TargetProcessId on $HostName over strict-host-key SSH." }
    $clockTicks = [double]$lines[0]
    $statLine = $lines | Where-Object { $_ -match '^\s*\d+\s+\(' } | Select-Object -First 1
    $statFields = ($statLine -replace '^\d+\s+\(.*\)\s+\S+\s+', '') -split '\s+'
    if ($statFields.Count -lt 13) { throw "Unexpected /proc stat for PID $TargetProcessId on $HostName." }
    $rss = [regex]::Match(($lines -join "`n"), 'VmRSS:\s+(\d+)\s+kB')
    if (-not $rss.Success -or $clockTicks -le 0) { throw "Could not read RSS/clock ticks for PID $TargetProcessId on $HostName." }
    [pscustomobject]@{
        CpuTicks = [long]$statFields[10] + [long]$statFields[11]
        ClockTicks = $clockTicks
        RssGiB = [math]::Round(([long]$rss.Groups[1].Value / 1MB), 3)
    }
}

function Assert-PlayerState([string] $HostName, [int] $Port, [string] $Password) {
    $players = Get-RconText $HostName $Port $Password 'list'
    if (-not $AllowPlayers -and $players -notmatch '^There are 0 of ') {
        throw "Expected an empty disposable benchmark server at ${HostName}:$Port, got: $players"
    }
    return $players
}

$process = @{
    B1 = Get-RemoteProcess $RemoteHost1 $RemoteUser1 $ProcessId1
    B2 = Get-RemoteProcess $RemoteHost2 $RemoteUser2 $ProcessId2
}
if ($MasterProcessId -gt 0) { $process.Master = Get-RemoteProcess $MasterHost $MasterUser $MasterProcessId }
$previous = $process
$previousAt = [DateTime]::UtcNow
$startedAt = $previousAt
$rows = [System.Collections.Generic.List[object]]::new()

for ($sample = 1; $sample -le $SampleCount; $sample++) {
    Start-Sleep -Seconds $IntervalSeconds
    $players1 = Assert-PlayerState $RconHost1 $RconPort1 $RconPassword1
    $players2 = Assert-PlayerState $RconHost2 $RconPort2 $RconPassword2
    $tick1 = Get-TickSample $RconHost1 $RconPort1 $RconPassword1
    $tick2 = Get-TickSample $RconHost2 $RconPort2 $RconPassword2
    $zombies1a = Get-ZombieCount $RconHost1 $RconPort1 $RconPassword1 $Tag1 'bench_a'
    $zombies1b = Get-ZombieCount $RconHost1 $RconPort1 $RconPassword1 $Tag2 'bench_b'
    $zombies2a = Get-ZombieCount $RconHost2 $RconPort2 $RconPassword2 $Tag1 'bench_a'
    $zombies2b = Get-ZombieCount $RconHost2 $RconPort2 $RconPassword2 $Tag2 'bench_b'
    if ($zombies1a -ne $ExpectedZombieCount1 -or $zombies2a -ne $ExpectedZombieCount1 -or
        $zombies1b -ne $ExpectedZombieCount2 -or $zombies2b -ne $ExpectedZombieCount2) {
        throw "Expected tag counts $ExpectedZombieCount1/$ExpectedZombieCount2 in both backend views; observed counts $zombies1a/$zombies1b and $zombies2a/$zombies2b."
    }

    $now = [DateTime]::UtcNow
    $elapsed = ($now - $previousAt).TotalSeconds
    $current = @{
        B1 = Get-RemoteProcess $RemoteHost1 $RemoteUser1 $ProcessId1
        B2 = Get-RemoteProcess $RemoteHost2 $RemoteUser2 $ProcessId2
    }
    if ($MasterProcessId -gt 0) { $current.Master = Get-RemoteProcess $MasterHost $MasterUser $MasterProcessId }
    $row = [ordered]@{
        Sample = $sample
        ElapsedSeconds = [math]::Round(($now - $startedAt).TotalSeconds, 1)
        IntervalSeconds = [math]::Round($elapsed, 1)
        Tps1mB1 = $tick1.Tps1m; Mspt5sAvgB1 = $tick1.Mspt5sAvg; Mspt10sAvgB1 = $tick1.Mspt10sAvg; Mspt10sMaxB1 = $tick1.Mspt10sMax; Mspt1mAvgB1 = $tick1.Mspt1mAvg; Mspt1mMaxB1 = $tick1.Mspt1mMax
        Tps1mB2 = $tick2.Tps1m; Mspt5sAvgB2 = $tick2.Mspt5sAvg; Mspt10sAvgB2 = $tick2.Mspt10sAvg; Mspt10sMaxB2 = $tick2.Mspt10sMax; Mspt1mAvgB2 = $tick2.Mspt1mAvg; Mspt1mMaxB2 = $tick2.Mspt1mMax
        CpuOneCorePctB1 = [math]::Round((($current.B1.CpuTicks - $previous.B1.CpuTicks) / $current.B1.ClockTicks) / $elapsed * 100, 2)
        CpuOneCorePctB2 = [math]::Round((($current.B2.CpuTicks - $previous.B2.CpuTicks) / $current.B2.ClockTicks) / $elapsed * 100, 2)
        RssGiBB1 = $current.B1.RssGiB; RssGiBB2 = $current.B2.RssGiB
        ZombiesB1ViewA = $zombies1a; ZombiesB1ViewB = $zombies1b; ZombiesB2ViewA = $zombies2a; ZombiesB2ViewB = $zombies2b
        PlayersB1 = $players1; PlayersB2 = $players2
    }
    if ($MasterProcessId -gt 0) {
        $row.CpuOneCorePctMaster = [math]::Round((($current.Master.CpuTicks - $previous.Master.CpuTicks) / $current.Master.ClockTicks) / $elapsed * 100, 2)
        $row.RssGiBMaster = $current.Master.RssGiB
    }
    $rows.Add([pscustomobject]$row)
    [pscustomobject]$row | Format-List
    $previous = $current
    $previousAt = $now
}

$absoluteOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($absoluteOutputPath)
if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
$rows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath $absoluteOutputPath
Write-Output "Wrote $($rows.Count) validated samples to $absoluteOutputPath"
