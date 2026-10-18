param(
    [string]$Host2 = '100.78.108.117',
    [int]$Port = 25565
)

function Join-Bytes {
    param([object[]]$Parts)
    $out = New-Object System.Collections.Generic.List[byte]
    foreach ($p in $Parts) {
        if ($p -is [byte[]]) { foreach ($b in $p) { $out.Add($b) } }
        else { $out.Add([byte]$p) }
    }
    return ,$out.ToArray()
}

function Write-VarInt([int]$v) {
    $out = New-Object System.Collections.Generic.List[byte]
    do {
        $b = $v -band 0x7F
        $v = $v -shr 7
        if ($v -ne 0) { $b = $b -bor 0x80 }
        $out.Add([byte]$b)
    } while ($v -ne 0)
    return ,$out.ToArray()
}

function Read-VarInt($stream) {
    $i = 0; $d = 0
    while ($true) {
        $b = $stream.ReadByte()
        if ($b -eq -1) { throw 'eof reading varint' }
        $d += ($b -band 0x7F) -shl (7 * $i)
        $i++
        if (($b -band 0x80) -eq 0) { break }
    }
    return $d
}

$s = New-Object System.Net.Sockets.TcpClient
$s.Connect($Host2, $Port)
$st = $s.GetStream()
$st.ReadTimeout = 8000

$hostBytes = [System.Text.Encoding]::UTF8.GetBytes('localhost')
$addrBytes = [System.Text.Encoding]::ASCII.GetBytes("$Host2`0$Port")

# handshake: packetId=0, proto=777 (26.3), host, port(ushort BE), next=1(status)
$portBytes = [BitConverter]::GetBytes([uint16]$Port)
if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($portBytes) }
$payload = Join-Bytes @(
    (Write-VarInt 0),
    (Write-VarInt 777),
    (Write-VarInt $hostBytes.Length), $hostBytes,
    $portBytes,
    (Write-VarInt 1)
)
$frame = Join-Bytes @((Write-VarInt $payload.Length), $payload)
$st.Write($frame, 0, $frame.Length)
$st.Flush()

# status request: just packet id 0, no fields
$reqFrame = Join-Bytes @((Write-VarInt 1), [byte[]]@(0))
$st.Write($reqFrame, 0, $reqFrame.Length)
$st.Flush()

$frameLen = Read-VarInt $st       # response frame length
$null = Read-VarInt $st           # packet id (0 = status response)
$strLen = Read-VarInt $st         # JSON string length
$buf = New-Object byte[] $strLen
$off = 0
while ($off -lt $strLen) {
    $n = $st.Read($buf, $off, $strLen - $off)
    if ($n -le 0) { break }
    $off += $n
}
$s.Close()
$json = [System.Text.Encoding]::UTF8.GetString($buf) | ConvertFrom-Json
[pscustomobject]@{
    Host       = "${Host2}:$Port"
    Version    = $json.version.name
    Protocol   = $json.version.protocol
    Players    = "$($json.players.online)/$($json.players.max)"
    Motd       = ($json.description | ConvertTo-Json -Depth 5 -Compress)
    Sample     = (@($json.players.sample | ForEach-Object name) -join ', ')
} | Format-List
