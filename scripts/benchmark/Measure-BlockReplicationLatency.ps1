[CmdletBinding()]
param(
    [string] $RconHost1 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort1,
    [string] $RconHost2 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort2,
    [ValidateRange(1, 500)] [int] $SamplesPerDirection = 20,
    [int] $X = 1400,
    [int] $Y = 200,
    [int] $Z = 1400,
    [ValidateRange(1, 60)] [int] $TimeoutSeconds = 10,
    [Parameter(Mandatory = $true)] [string] $OutputPath
)

$ErrorActionPreference = 'Stop'
$password1 = $env:MULTIPAPER_RCON_PASSWORD1
$password2 = $env:MULTIPAPER_RCON_PASSWORD2
if ([string]::IsNullOrWhiteSpace($password1) -or [string]::IsNullOrWhiteSpace($password2)) {
    throw 'Set MULTIPAPER_RCON_PASSWORD1 and MULTIPAPER_RCON_PASSWORD2 before measuring.'
}
$rconScript = Join-Path $PSScriptRoot '..\Send-RconCommand.ps1'
if (-not (Test-Path -LiteralPath $rconScript -PathType Leaf)) { throw "RCON helper not found: $rconScript" }

function Invoke-Rcon([int] $Port, [string] $Command) {
    $hostName = if ($Port -eq $RconPort1) { $RconHost1 } elseif ($Port -eq $RconPort2) { $RconHost2 } else { throw "Unknown RCON port $Port" }
    $password = if ($Port -eq $RconPort1) { $password1 } else { $password2 }
    return (& $rconScript -Server $hostName -Port $Port -Password $password -Command $Command | Out-String).Trim()
}

function Wait-ForBlock([int] $Port, [string] $Block, [DateTime] $Deadline) {
    $command = "execute if block $X $Y $Z minecraft:$Block run help"
    do {
        $text = Invoke-Rcon $Port $command
        if ($text -match 'Index \(\d+/\d+\)') { return }
        Start-Sleep -Milliseconds 5
    } while ([DateTime]::UtcNow -lt $Deadline)
    throw "Timed out waiting for minecraft:$Block to appear at $X $Y $Z on RCON port $Port. Last response: $text"
}

$rows = [Collections.Generic.List[object]]::new()
$chunkLoaded = "execute if loaded $X $Y $Z run help"
try {
    Invoke-Rcon $RconPort1 "forceload add $X $Z" | Out-Null
    Invoke-Rcon $RconPort2 "forceload add $X $Z" | Out-Null
    $loadDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    foreach ($port in @($RconPort1, $RconPort2)) {
        do {
            $loaded = Invoke-Rcon $port $chunkLoaded
            if ($loaded -match 'Index \(\d+/\d+\)') { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $loadDeadline)
        if ($loaded -notmatch 'Index \(\d+/\d+\)') { throw "Test chunk did not load on port $port." }
    }

    # Alternate block states so every trial requires a newly replicated change.
    for ($i = 0; $i -lt (2 * $SamplesPerDirection); $i++) {
        $sourcePort = if (($i % 2) -eq 0) { $RconPort1 } else { $RconPort2 }
        $observerPort = if (($i % 2) -eq 0) { $RconPort2 } else { $RconPort1 }
        $direction = if (($i % 2) -eq 0) { 'Backend1-to-Backend2' } else { 'Backend2-to-Backend1' }
        $block = if (($i % 2) -eq 0) { 'gold_block' } else { 'iron_block' }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        Invoke-Rcon $sourcePort "setblock $X $Y $Z minecraft:$block" | Out-Null
        Wait-ForBlock $observerPort $block ([DateTime]::UtcNow.AddSeconds($TimeoutSeconds))
        $timer.Stop()
        $rows.Add([pscustomobject]@{
            Sample = $rows.Count + 1
            Direction = $direction
            Block = "minecraft:$block"
            EndToEndRconMs = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
        })
    }
} finally {
    try { Invoke-Rcon $RconPort1 "setblock $X $Y $Z minecraft:air" | Out-Null } catch { }
    try { Invoke-Rcon $RconPort1 "forceload remove $X $Z" | Out-Null } catch { }
    try { Invoke-Rcon $RconPort2 "forceload remove $X $Z" | Out-Null } catch { }
}

$parent = Split-Path -Parent $OutputPath
if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation
foreach ($direction in @('Backend1-to-Backend2', 'Backend2-to-Backend1')) {
    $values = @($rows | Where-Object Direction -eq $direction | ForEach-Object EndToEndRconMs | Sort-Object { [double]$_ })
    $median = if ($values.Count % 2) { [double]$values[[int]($values.Count / 2)] } else { ([double]$values[$values.Count / 2 - 1] + [double]$values[$values.Count / 2]) / 2 }
    $p95 = [double]$values[[Math]::Min($values.Count - 1, [Math]::Ceiling($values.Count * .95) - 1)]
    [pscustomobject]@{
        Direction = $direction
        Samples = $values.Count
        MedianMs = [math]::Round($median, 3)
        P95Ms = [math]::Round($p95, 3)
        MaxMs = [math]::Round([double]$values[-1], 3)
    }
}
Write-Output "CSV: $((Resolve-Path -LiteralPath $OutputPath).Path)"
