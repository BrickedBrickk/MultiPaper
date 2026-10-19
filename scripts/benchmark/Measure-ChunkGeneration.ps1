[CmdletBinding()]
param(
    [string] $RconHost1 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort1,
    [string] $RconHost2 = '127.0.0.1',
    [Parameter(Mandatory = $true)] [int] $RconPort2,
    [ValidateRange(2, 16)] [int] $ChunksPerSide = 4,
    [int] $OriginX = 10000,
    [int] $OriginZ = 10000,
    [ValidateRange(1, 300)] [int] $TimeoutSeconds = 90,
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

$chunks = [Collections.Generic.List[object]]::new()
for ($cx = 0; $cx -lt $ChunksPerSide; $cx++) {
    for ($cz = 0; $cz -lt $ChunksPerSide; $cz++) {
        $x = $OriginX + 8 + 16 * $cx
        $z = $OriginZ + 8 + 16 * $cz
        $chunks.Add([pscustomobject]@{ X = $x; Z = $z })
    }
}
$timer = [Diagnostics.Stopwatch]::StartNew()
$loaded1 = $false
$loaded2 = $false
try {
    foreach ($chunk in $chunks) {
        Invoke-Rcon $RconPort1 "forceload add $($chunk.X) $($chunk.Z)" | Out-Null
        Invoke-Rcon $RconPort2 "forceload add $($chunk.X) $($chunk.Z)" | Out-Null
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $loaded1 = $true
        $loaded2 = $true
        foreach ($chunk in $chunks) {
            $check = "execute if loaded $($chunk.X) 80 $($chunk.Z) run help"
            if ((Invoke-Rcon $RconPort1 $check) -notmatch 'Index \(\d+/\d+\)') { $loaded1 = $false }
            if ((Invoke-Rcon $RconPort2 $check) -notmatch 'Index \(\d+/\d+\)') { $loaded2 = $false }
        }
        if (-not ($loaded1 -and $loaded2)) { Start-Sleep -Milliseconds 100 }
    } while (-not ($loaded1 -and $loaded2) -and [DateTime]::UtcNow -lt $deadline)
    if (-not ($loaded1 -and $loaded2)) {
        throw "Timed out after $TimeoutSeconds seconds. All loaded on B1=$loaded1, B2=$loaded2."
    }
    $timer.Stop()
    $seconds = $timer.Elapsed.TotalSeconds
    $result = [pscustomobject]@{
        ChunkCount = $chunks.Count
        ChunksPerSide = $ChunksPerSide
        EndToEndSeconds = [math]::Round($seconds, 3)
        EffectiveChunksPerSecond = [math]::Round($chunks.Count / $seconds, 3)
        BothBackendsLoadedAllChunks = $true
        RconPollIntervalMs = 100
    }
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $result | Export-Csv -LiteralPath $OutputPath -NoTypeInformation
    $result
} finally {
    foreach ($chunk in $chunks) {
        try { Invoke-Rcon $RconPort1 "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
        try { Invoke-Rcon $RconPort2 "forceload remove $($chunk.X) $($chunk.Z)" | Out-Null } catch { }
    }
}
