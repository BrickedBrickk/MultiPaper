param(
    [Parameter(Mandatory)][string]$PlayerName,
    [Parameter(Mandatory=$true)][switch]$DisposableFixture,
    [int]$Server1RconPort = 31931,
    [int]$Server2RconPort = 31932,
    [Parameter(Mandatory)][string]$Server1PropertiesPath,
    [Parameter(Mandatory)][string]$Server2PropertiesPath,
    [string]$Server1RconPassword,
    [string]$Server2RconPassword,
    [int]$TimeoutSeconds = 15
)

# Validate a connected disposable player moving between the Overworld and Nether
# while both MultiPaper backends read the player's dimension and position.
# This exercises server-side dimension transfer/ownership replication; it does not
# claim that a real player activated a portal or validate portal frame generation.
$ErrorActionPreference = 'Stop'

function Get-RconPassword([string]$Path) {
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match '^rcon\.password=' } | Select-Object -First 1
    if (-not $line) { throw "No RCON password is configured in $Path" }
    return $line.Substring('rcon.password='.Length)
}
if (-not $Server1RconPassword) { $Server1RconPassword = Get-RconPassword $Server1PropertiesPath }
if (-not $Server2RconPassword) { $Server2RconPassword = Get-RconPassword $Server2PropertiesPath }
if (-not $Server1RconPassword -or -not $Server2RconPassword) { throw 'Both disposable backend RCON passwords must be non-empty.' }

function Read-RconPacket([System.IO.Stream]$Stream) {
    $header = [byte[]]::new(4); $offset = 0
    while ($offset -lt 4) { $n=$Stream.Read($header,$offset,4-$offset); if ($n -eq 0) { throw 'RCON closed during packet header.' }; $offset += $n }
    $length=[BitConverter]::ToInt32($header,0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length $length" }
    $body=[byte[]]::new($length); $offset=0
    while ($offset -lt $length) { $n=$Stream.Read($body,$offset,$length-$offset); if ($n -eq 0) { throw 'RCON closed during packet body.' }; $offset += $n }
    [pscustomobject]@{ Id=[BitConverter]::ToInt32($body,0); Type=[BitConverter]::ToInt32($body,4); Text=[Text.Encoding]::UTF8.GetString($body,8,$length-10) }
}
function Write-RconPacket([System.IO.Stream]$Stream,[int]$Id,[int]$Type,[string]$Text) {
    $payload=[Text.Encoding]::UTF8.GetBytes($Text); $length=4+4+$payload.Length+2
    $packet=[byte[]]::new(4+$length); [BitConverter]::GetBytes($length).CopyTo($packet,0); [BitConverter]::GetBytes($Id).CopyTo($packet,4); [BitConverter]::GetBytes($Type).CopyTo($packet,8); $payload.CopyTo($packet,12)
    $Stream.Write($packet,0,$packet.Length); $Stream.Flush()
}
function Invoke-Rcon([int]$Port,[string]$Command) {
    $password=if($Port -eq $Server1RconPort){$Server1RconPassword}else{$Server2RconPassword}
    $client=[Net.Sockets.TcpClient]::new(); $client.ReceiveTimeout=5000; $client.SendTimeout=5000
    try {
        $client.Connect('127.0.0.1',$Port); $stream=$client.GetStream(); $authId=Get-Random -Minimum 10000 -Maximum 1900000000
        Write-RconPacket $stream $authId 3 $password; $auth=Read-RconPacket $stream
        if ($auth.Id -ne $authId) { throw "RCON authentication failed on port $Port" }
        $commandId=$authId+1; Write-RconPacket $stream $commandId 2 $Command; $response=Read-RconPacket $stream
        if ($response.Id -ne $commandId) { throw "Unexpected RCON response ID on port $Port" }
        return $response.Text
    } finally { $client.Dispose() }
}
function Assert-Dimension([int]$Port,[string]$Dimension) {
    $response=Invoke-Rcon $Port "execute as $PlayerName at @s if dimension $Dimension run help"
    if ($response -notmatch 'Index \(') { throw "Player $PlayerName was not confirmed in $Dimension on backend RCON port $Port. Response: $response" }
}
function Get-PlayerPosition([int]$Port) {
    $response=Invoke-Rcon $Port "data get entity $PlayerName Pos"
    $match=[regex]::Match($response,'\[([^\]]+)\]')
    if (-not $match.Success) { throw "Could not read player position on backend RCON port ${Port}: $response" }
    return $match.Value
}
function Wait-Dimension([string]$Dimension) {
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try { Assert-Dimension $Server1RconPort $Dimension; Assert-Dimension $Server2RconPort $Dimension; return }
        catch { Start-Sleep -Milliseconds 200 }
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Dimension $Server1RconPort $Dimension
    Assert-Dimension $Server2RconPort $Dimension
}

Assert-Dimension $Server1RconPort 'minecraft:overworld'
Assert-Dimension $Server2RconPort 'minecraft:overworld'
$initialPosition=Get-PlayerPosition $Server1RconPort
$initialPositionPeer=Get-PlayerPosition $Server2RconPort
if ($initialPosition -cne $initialPositionPeer) { throw "Initial player positions differ: $initialPosition vs $initialPositionPeer" }

$netherX=1200+(Get-Random -Minimum 0 -Maximum 200); $netherZ=1200+(Get-Random -Minimum 0 -Maximum 200)
Invoke-Rcon $Server1RconPort "execute in minecraft:the_nether run tp $PlayerName $netherX 80 $netherZ" | Out-Null
Wait-Dimension 'minecraft:the_nether'
$netherPosition=Get-PlayerPosition $Server1RconPort; $netherPositionPeer=Get-PlayerPosition $Server2RconPort
if ($netherPosition -cne $netherPositionPeer) { throw "Nether player positions differ: $netherPosition vs $netherPositionPeer" }
Write-Output "PASS: $PlayerName transferred to the Nether and both backends reported $netherPosition."

Invoke-Rcon $Server1RconPort "execute in minecraft:overworld run tp $PlayerName 1200 90 1200" | Out-Null
Wait-Dimension 'minecraft:overworld'
$overworldPosition=Get-PlayerPosition $Server1RconPort; $overworldPositionPeer=Get-PlayerPosition $Server2RconPort
if ($overworldPosition -cne $overworldPositionPeer) { throw "Overworld player positions differ: $overworldPosition vs $overworldPositionPeer" }
Write-Output "PASS: $PlayerName returned to the Overworld and both backends reported $overworldPosition."

