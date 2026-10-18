param(
    [Parameter(Mandatory=$true)][string]$Server,
    [Parameter(Mandatory=$true)][int]$Port,
    [Parameter(Mandatory=$true)][string]$Password,
    [Parameter(Mandatory=$true)][string]$Command
)

# Source RCON is length-prefixed with int32 LE (not varint)
function New-RconPacket([int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $payloadLen = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$payloadLen)
    $bw.Write([int]$id)
    $bw.Write([int]$type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    return ,$ms.ToArray()
}

function Read-RconPacket($stream) {
    $lenBuf = New-Object byte[] 4
    $off = 0
    while ($off -lt 4) {
        $n = $stream.Read($lenBuf, $off, 4 - $off)
        if ($n -le 0) { throw 'RCON connection closed while reading packet length.' }
        $off += $n
    }
    $len = [BitConverter]::ToInt32($lenBuf, 0)
    if ($len -lt 4 -or $len -gt 4110) { throw "Bad RCON packet length $len" }
    $buf = New-Object byte[] $len
    $off = 0
    while ($off -lt $len) {
        $n = $stream.Read($buf, $off, $len - $off)
        if ($n -le 0) { throw 'RCON connection closed while reading packet body.' }
        $off += $n
    }
    $id = [BitConverter]::ToInt32($buf, 0)
    $type = [BitConverter]::ToInt32($buf, 4)
    $bodyLen = $len - 10
    $body = if ($bodyLen -gt 0) { [System.Text.Encoding]::UTF8.GetString($buf, 8, $bodyLen) } else { '' }
    return ,@($id, $type, $body)
}

$c = New-Object System.Net.Sockets.TcpClient
$c.Connect($Server, $Port)
$st = $c.GetStream()
$st.ReadTimeout = 10000

$auth = New-RconPacket 1 3 $Password
$st.Write($auth, 0, $auth.Length); $st.Flush()
$resp = Read-RconPacket $st
if ($resp[0] -eq -1) { $c.Close(); throw "RCON auth failed on ${Server}:$Port" }

$cmd = New-RconPacket 2 2 $Command
$st.Write($cmd, 0, $cmd.Length); $st.Flush()
$resp = Read-RconPacket $st
# some servers send an empty follow-up packet for multi-packet replies; drain non-blocking
$c.Close()
Write-Output $resp[2]
