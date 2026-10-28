param(
    [string]$TemplateRoot = 'D:\multipaper-tests\system-temp\rc5-invsee-probe-runner-20261027',
    [string]$FixtureRoot = ('D:\multipaper-tests\system-temp\rc5-boat-passenger-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$Paperclip = 'C:\Users\elibo\AppData\Local\Temp\multipaper-rc5-boatlog-20261027\multipaper-server\build\libs\multipaper-paperclip-26.3-RC4.jar',
    [string]$MasterJar = 'C:\Users\elibo\AppData\Local\Temp\multipaper-rc5-boatlog-20261027\MultiPaper-Master\build\libs\multipaper-master-3.0.0.jar',
    [string]$PaperclipSha256 = 'D8C015EFE458CADA31113C606AB2541AF809A932ADBA514D267B55DD66B6393E',
    [string]$MasterSha256 = 'E122A819950C50FF504CDC6600D6E5C4970D9D25F45AC19ED974F9D194134B7',
    [string]$Java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [int]$TimeoutSeconds = 180,
    [switch]$LeashOnly
)

$ErrorActionPreference = 'Stop'
$paperHash = $PaperclipSha256
$masterHash = $MasterSha256
$ports = @(33451,33452,33453,33454,33461,33462)
$root = [IO.Path]::GetFullPath($FixtureRoot)
$tempBase = [IO.Path]::GetFullPath('D:\multipaper-tests\system-temp').TrimEnd('\') + '\'
if (-not $root.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture must remain under D:\multipaper-tests\system-temp.' }
if (Test-Path -LiteralPath $root) { throw "Fixture already exists: $root" }
if (-not (Test-Path -LiteralPath (Join-Path $TemplateRoot '.multipaper-disposable'))) { throw 'Template is not marked disposable.' }
if ((Get-FileHash -LiteralPath $Paperclip -Algorithm SHA256).Hash -ne $paperHash) { throw 'Boat-fix Paperclip hash mismatch.' }
if ((Get-FileHash -LiteralPath $MasterJar -Algorithm SHA256).Hash -ne $masterHash) { throw 'Master hash mismatch.' }
if (-not (Test-Path -LiteralPath $Java)) { throw 'Java 25 executable not found.' }
foreach ($port in $ports) { if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already listening." } }

New-Item -ItemType Directory -Path $root | Out-Null
Set-Content -LiteralPath (Join-Path $root '.multipaper-disposable') -Value 'Fresh isolated RC5 boat/passenger runtime validation.'
foreach ($component in @('master','backend-a','backend-b')) { New-Item -ItemType Directory -Path (Join-Path $root $component) | Out-Null }
Copy-Item -LiteralPath $MasterJar -Destination (Join-Path $root 'master\master.jar')
Copy-Item -LiteralPath $Paperclip -Destination (Join-Path $root 'backend-a\server.jar')
Copy-Item -LiteralPath $Paperclip -Destination (Join-Path $root 'backend-b\server.jar')
foreach ($backend in @('backend-a','backend-b')) {
    $source = Join-Path $TemplateRoot $backend
    $dest = Join-Path $root $backend
    foreach ($name in @('eula.txt','server.properties','multipaper.yml','spigot.yml','bukkit.yml','purpur.yml','commands.yml')) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination $dest }
    foreach ($name in @('plugins','config','libraries','versions','cache')) { $path=Join-Path $source $name; if (Test-Path $path) { Copy-Item -LiteralPath $path -Destination $dest -Recurse } }
}
foreach ($port in $ports) { if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port became occupied before startup." } }

$world = 'rc5boat' + (Get-Date -Format 'MMddHHmmss')
foreach ($backend in @('backend-a','backend-b')) {
    $index = if ($backend -eq 'backend-a') { 0 } else { 1 }
    $propertiesPath = Join-Path $root "$backend\server.properties"
    $properties = Get-Content -LiteralPath $propertiesPath -Raw
    foreach ($entry in @(@('level-name',$world),@('server-port',[string](33453+$index)),@('rcon.port',[string](33461+$index)),@('server-ip','127.0.0.1'))) {
        $pattern = '(?m)^' + [regex]::Escape($entry[0]) + '=.*$'
        if (-not [regex]::IsMatch($properties,$pattern)) { throw "Missing property $($entry[0]) in $propertiesPath" }
        $properties = [regex]::Replace($properties,$pattern,[string]$entry[0]+'='+[string]$entry[1])
    }
    Set-Content -LiteralPath $propertiesPath -Value $properties -NoNewline
    $multiPath = Join-Path $root "$backend\multipaper.yml"
    $multi = Get-Content -LiteralPath $multiPath -Raw
    $multi = [regex]::Replace($multi,'(?m)^([ \t]*master-address:[ \t]*).*$', '${1}127.0.0.1:33451')
    $multi = [regex]::Replace($multi,'(?m)^([ \t]*my-name:[ \t]*).*$', ('${1}' + $(if($index -eq 0){'boat-a'}else{'boat-b'})))
    Set-Content -LiteralPath $multiPath -Value $multi -NoNewline
    Copy-Item -LiteralPath $Paperclip -Destination (Join-Path $root "$backend\server.jar") -Force
}
$passwords = @{}
foreach ($backend in @('backend-a','backend-b')) {
    $props = Get-Content (Join-Path $root "$backend\server.properties")
    $passwords[$backend] = (($props | Where-Object { $_ -like 'rcon.password=*' } | Select-Object -First 1) -split '=',2)[1]
}
$logs = Join-Path $root 'run-logs'; New-Item -ItemType Directory -Path $logs | Out-Null
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$transcript = Join-Path $logs 'rcon-transcript.txt'

function Read-RconPacket($stream) {
    $header=[byte[]]::new(4); $offset=0
    while($offset -lt 4){$n=$stream.Read($header,$offset,4-$offset);if(-not $n){throw 'RCON closed.'};$offset+=$n}
    $length=[BitConverter]::ToInt32($header,0); if($length -lt 10 -or $length -gt 4110){throw "Invalid RCON packet length $length"}
    $body=[byte[]]::new($length);$offset=0;while($offset -lt $length){$n=$stream.Read($body,$offset,$length-$offset);if(-not $n){throw 'RCON closed.'};$offset+=$n}
    [pscustomobject]@{Id=[BitConverter]::ToInt32($body,0);Text=[Text.Encoding]::UTF8.GetString($body,8,$length-10)}
}
function Write-RconPacket($stream,[int]$id,[int]$type,[string]$text){$bytes=[Text.Encoding]::UTF8.GetBytes($text);$length=10+$bytes.Length;$packet=[byte[]]::new(4+$length);[BitConverter]::GetBytes($length).CopyTo($packet,0);[BitConverter]::GetBytes($id).CopyTo($packet,4);[BitConverter]::GetBytes($type).CopyTo($packet,8);$bytes.CopyTo($packet,12);$stream.Write($packet,0,$packet.Length);$stream.Flush()}
function Invoke-Rcon([int]$port,[string]$password,[string]$command){$client=[Net.Sockets.TcpClient]::new();$client.ReceiveTimeout=10000;try{$client.Connect('127.0.0.1',$port);$stream=$client.GetStream();$stream.ReadTimeout=10000;$id=Get-Random -Minimum 10000 -Maximum 1800000000;Write-RconPacket $stream $id 3 $password;$auth=Read-RconPacket $stream;if($auth.Id -ne $id){throw "RCON auth failed on $port"};Write-RconPacket $stream ($id+1) 2 $command;$reply=Read-RconPacket $stream;if($reply.Id -ne ($id+1)){throw "RCON response mismatch on $port"};Add-Content -LiteralPath $transcript -Value ("$port> $command`n$($reply.Text)");$reply.Text}finally{$client.Dispose()}}
function Wait-Ready([string]$log,[Diagnostics.Process]$process,[string]$label){$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds);do{if($process.HasExited){throw "$label exited before readiness."};if((Test-Path $log) -and (Select-String -LiteralPath $log -Pattern 'Done \(' -Quiet)){return};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline);throw "$label did not become ready."}
function Assert-Command([string]$value,[string]$pattern,[string]$label){if($value -notmatch $pattern){throw "$label expected /$pattern/, got: $value"};$value}
function Assert-LeashState([string]$state,[string]$wolfUuid,[string]$label){$match=[regex]::Match($state,'RC5_LEASH_STATE wolf=([^ ]+) leashed=(true|false) holder=([^ ]+) holderType=([^ ]+) knots=([^\r\n]+)');if(-not $match.Success){throw "$label returned malformed state: $state"};if($match.Groups[1].Value -ne $wolfUuid -or $match.Groups[2].Value -ne 'true' -or $match.Groups[4].Value -ne 'LEASH_KNOT' -or $match.Groups[5].Value -ne ($match.Groups[3].Value+'@84,80,84')){throw "$label expected wolf $wolfUuid tethered to exactly its local fence knot at 84,80,84, got: $state"};$state}
function Get-EntityCount([int]$rconPort,[string]$password,[string]$type,[string]$fakeName){$null=Invoke-Rcon $rconPort $password 'scoreboard objectives add rc5boat dummy';$null=Invoke-Rcon $rconPort $password "scoreboard players set $fakeName rc5boat 0";$null=Invoke-Rcon $rconPort $password "execute as @e[type=minecraft:$type] run scoreboard players add $fakeName rc5boat 1";Invoke-Rcon $rconPort $password "scoreboard players get $fakeName rc5boat"}

$probeBuild=Join-Path $root 'boat-owner-probe-build';$probeClasses=Join-Path $probeBuild 'classes';New-Item -ItemType Directory -Path $probeClasses | Out-Null
$probeSource=Join-Path $probeBuild 'BoatOwnerProbePlugin.java';Copy-Item -LiteralPath 'scripts\test-fixtures\boat-owner-probe\src\main\java\rc5\boat\BoatOwnerProbePlugin.java' -Destination $probeSource
$backendLibs=Join-Path $root 'backend-a';$libraryJars=@(Get-ChildItem -LiteralPath (Join-Path $backendLibs 'libraries') -Filter '*.jar' -File -Recurse | ForEach-Object { $_.FullName.Replace('\','/') })
$classPath=@((Join-Path $backendLibs 'versions\26.3\multipaper-26.3.jar').Replace('\','/'))+$libraryJars
$argsFile=Join-Path $probeBuild 'compile.args';@('-encoding','UTF-8','-classpath',($classPath -join ';'),'-d',$probeClasses.Replace('\','/'),$probeSource.Replace('\','/')) | Set-Content -LiteralPath $argsFile -Encoding Ascii
$javac=Join-Path ([IO.Path]::GetDirectoryName($Java)) 'javac.exe';$jarTool=Join-Path ([IO.Path]::GetDirectoryName($Java)) 'jar.exe'
& $javac ('@'+$argsFile);if($LASTEXITCODE -ne 0){throw "Boat owner probe compilation failed ($LASTEXITCODE). Evidence retained at $root"}
Copy-Item -LiteralPath 'scripts\test-fixtures\boat-owner-probe\plugin.yml' -Destination (Join-Path $probeClasses 'plugin.yml')
$probeJar=Join-Path $probeBuild 'RC5BoatOwnerProbe.jar';& $jarTool 'cf' $probeJar '-C' $probeClasses '.';if($LASTEXITCODE -ne 0){throw "Boat owner probe JAR creation failed ($LASTEXITCODE). Evidence retained at $root"}
foreach($backend in @('backend-a','backend-b')){Copy-Item -LiteralPath $probeJar -Destination (Join-Path $root "$backend\plugins\RC5BoatOwnerProbe.jar")}

$result = 'FAILED'; $failure = $null
try {
    $master=Start-Process -FilePath $Java -WorkingDirectory (Join-Path $root 'master') -ArgumentList @('-jar','master.jar','33451','33452') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'master.out') -RedirectStandardError (Join-Path $logs 'master.err') -PassThru;$processes.Add($master)
    Start-Sleep -Seconds 2
    $a=Start-Process -FilePath $Java -WorkingDirectory (Join-Path $root 'backend-a') -ArgumentList @('-jar','server.jar','--nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'backend-a.out') -RedirectStandardError (Join-Path $logs 'backend-a.err') -PassThru;$processes.Add($a)
    Wait-Ready (Join-Path $logs 'backend-a.out') $a 'Backend A'
    $null=Invoke-Rcon 33461 $passwords['backend-a'] 'forceload add 80 80'
    $ownerDeadline=[DateTime]::UtcNow.AddSeconds(30);$ownerA=''
    do{$ownerA=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5boatowner 5 5';if($ownerA -match 'RC5_BOAT_OWNER=boat-a'){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $ownerDeadline)
    Assert-Command $ownerA 'RC5_BOAT_OWNER=boat-a' 'Initial chunk owner Backend A' | Out-Null
    $b=Start-Process -FilePath $Java -WorkingDirectory (Join-Path $root 'backend-b') -ArgumentList @('-jar','server.jar','--nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'backend-b.out') -RedirectStandardError (Join-Path $logs 'backend-b.err') -PassThru;$processes.Add($b)
    Wait-Ready (Join-Path $logs 'backend-b.out') $b 'Backend B'
    $null=Invoke-Rcon 33462 $passwords['backend-b'] 'forceload add 80 80'
    Start-Sleep -Seconds 5
    $ownerBeforeTransfer=Invoke-Rcon 33462 $passwords['backend-b'] 'rc5boatowner 5 5'
    Assert-Command $ownerBeforeTransfer 'RC5_BOAT_OWNER=boat-a' 'Pre-transfer owner remains Backend A' | Out-Null
    if($LeashOnly){
        $leashSetup=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5leashprobe setup'
        Assert-Command $leashSetup 'RC5_LEASH_SETUP .* attached=true' 'Owner attaches a wolf to a fence knot' | Out-Null
        Start-Sleep -Seconds 2
        $leashOwnerBefore=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5leashprobe state'
        $leashPeerBefore=Invoke-Rcon 33462 $passwords['backend-b'] 'rc5leashprobe state'
        $leashWolfUuid=[regex]::Match($leashOwnerBefore,'wolf=([^ ]+)').Groups[1].Value
        if(-not $leashWolfUuid){throw "Owner leash wolf UUID was missing: $leashOwnerBefore"}
        Assert-LeashState $leashOwnerBefore $leashWolfUuid 'Initial owner leash state' | Out-Null
        Assert-LeashState $leashPeerBefore $leashWolfUuid 'Initial peer leash state' | Out-Null
        $null=Invoke-Rcon 33461 $passwords['backend-a'] 'forceload remove 80 80'
        $transferDeadline=[DateTime]::UtcNow.AddSeconds(30);$ownerAfterTransfer=''
        do{$ownerAfterTransfer=Invoke-Rcon 33462 $passwords['backend-b'] 'rc5boatowner 5 5';if($ownerAfterTransfer -match 'RC5_BOAT_OWNER=boat-b'){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $transferDeadline)
        Assert-Command $ownerAfterTransfer 'RC5_BOAT_OWNER=boat-b' 'Leash chunk ownership transfers A→B' | Out-Null
        $null=Invoke-Rcon 33461 $passwords['backend-a'] 'forceload add 80 80'
        $observerDeadline=[DateTime]::UtcNow.AddSeconds(30);$observerOwner=''
        do{$observerOwner=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5boatowner 5 5';if($observerOwner -match 'RC5_BOAT_OWNER=boat-b'){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $observerDeadline)
        Assert-Command $observerOwner 'RC5_BOAT_OWNER=boat-b' 'Backend A reloads as a non-owner observer' | Out-Null
        Start-Sleep -Seconds 2
        $leashOwnerAfter=Invoke-Rcon 33462 $passwords['backend-b'] 'rc5leashprobe state'
        $leashPeerAfter=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5leashprobe state'
        Assert-LeashState $leashOwnerAfter $leashWolfUuid 'New-owner leash state after transfer' | Out-Null
        Assert-LeashState $leashPeerAfter $leashWolfUuid 'Former-owner leash mirror after transfer' | Out-Null
        $result='PASS: wolf remained leashed to one local knot at the same fence block on both backends before and after A→B ownership transfer.'
    } else {
    Assert-Command (Invoke-Rcon 33461 $passwords['backend-a'] 'summon minecraft:oak_boat 82 80 82') 'Summoned new Oak Boat' 'Boat spawn'
    Assert-Command (Invoke-Rcon 33461 $passwords['backend-a'] 'summon minecraft:pig 82 81 82') 'Summoned new Pig' 'Passenger spawn'
    Assert-Command (Invoke-Rcon 33461 $passwords['backend-a'] 'ride @e[type=minecraft:pig,limit=1] mount @e[type=minecraft:oak_boat,limit=1]') 'started riding|mounted' 'Passenger mount'
    Start-Sleep -Seconds 2
    $aMounted=Invoke-Rcon 33461 $passwords['backend-a'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    $bMounted=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    Assert-Command $aMounted '\[I;' 'Owner boat with pig passenger' | Out-Null
    Assert-Command $bMounted '\[I;' 'Peer boat with pig passenger' | Out-Null
    if ($aMounted -ne $bMounted) { throw "Boat UUID differs across backends: owner=$aMounted peer=$bMounted" }
    $aBoatCount=Get-EntityCount 33461 $passwords['backend-a'] 'oak_boat' 'RC5BoatCountA'
    $bBoatCount=Get-EntityCount 33462 $passwords['backend-b'] 'oak_boat' 'RC5BoatCountB'
    $aPigCount=Get-EntityCount 33461 $passwords['backend-a'] 'pig' 'RC5PigCountA'
    $bPigCount=Get-EntityCount 33462 $passwords['backend-b'] 'pig' 'RC5PigCountB'
    Assert-Command $aBoatCount 'has 1 ' 'Owner boat count' | Out-Null
    Assert-Command $bBoatCount 'has 1 ' 'Peer boat count' | Out-Null
    Assert-Command $aPigCount 'has 1 ' 'Owner pig count' | Out-Null
    Assert-Command $bPigCount 'has 1 ' 'Peer pig count' | Out-Null
    $aPigUuid=Invoke-Rcon 33461 $passwords['backend-a'] 'data get entity @e[type=minecraft:pig,limit=1] UUID'
    $bPigUuid=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:pig,limit=1] UUID'
    if ($aPigUuid -ne $bPigUuid) { throw "Passenger UUID differs across backends: owner=$aPigUuid peer=$bPigUuid" }
    $move=Invoke-Rcon 33461 $passwords['backend-a'] 'tp @e[type=minecraft:oak_boat,limit=1] 84 80 84'
    Assert-Command $move 'Teleported' 'Boat movement'
    Start-Sleep -Seconds 2
    $peerPosition=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:oak_boat,limit=1] Pos'
    $peerMounted=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    Assert-Command $peerPosition '84' 'Peer boat position' | Out-Null
    Assert-Command $peerMounted '\[I;' 'Peer passenger after movement' | Out-Null
    if ($peerMounted -ne $aMounted) { throw "Boat/passenger relation did not retain the same boat UUID after movement: owner=$aMounted peer=$peerMounted" }
    $dismount=Invoke-Rcon 33461 $passwords['backend-a'] 'ride @e[type=minecraft:pig,limit=1] dismount'
    Assert-Command $dismount 'Stopped riding|dismounted' 'Pig dismount on owner' | Out-Null
    Start-Sleep -Seconds 2
    foreach($backend in @(@(33461,'backend-a'),@(33462,'backend-b'))){
        $unlinked=Invoke-Rcon $backend[0] $passwords[$backend[1]] 'data get entity @e[type=minecraft:oak_boat,limit=1] Passengers'
        if($unlinked -match 'minecraft:pig'){throw "Pig still appears as boat passenger on $($backend[1]): $unlinked"}
    }
    Assert-Command (Invoke-Rcon 33461 $passwords['backend-a'] 'ride @e[type=minecraft:pig,limit=1] mount @e[type=minecraft:oak_boat,limit=1]') 'started riding|mounted' 'Passenger remount'
    Start-Sleep -Seconds 2
    $remountedA=Invoke-Rcon 33461 $passwords['backend-a'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    $remountedB=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    if($remountedA -ne $remountedB){throw "Remounted boat UUID differs across backends: A=$remountedA B=$remountedB"}
    $null=Invoke-Rcon 33461 $passwords['backend-a'] 'forceload remove 80 80'
    $transferDeadline=[DateTime]::UtcNow.AddSeconds(30);$ownerAfterTransfer=''
    do{$ownerAfterTransfer=Invoke-Rcon 33462 $passwords['backend-b'] 'rc5boatowner 5 5';if($ownerAfterTransfer -match 'RC5_BOAT_OWNER=boat-b'){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $transferDeadline)
    Assert-Command $ownerAfterTransfer 'RC5_BOAT_OWNER=boat-b' 'Chunk ownership transfer to Backend B' | Out-Null
    $transferred=Invoke-Rcon 33462 $passwords['backend-b'] 'data get entity @e[type=minecraft:oak_boat,nbt={Passengers:[{id:"minecraft:pig"}]},limit=1] UUID'
    if($transferred -ne $remountedA){throw "Boat/passenger UUID changed across ownership transfer: before=$remountedA after=$transferred"}
    Assert-Command (Invoke-Rcon 33462 $passwords['backend-b'] 'tp @e[type=minecraft:oak_boat,limit=1] 85 80 85') 'Teleported' 'Post-transfer boat movement on new owner' | Out-Null
    Start-Sleep -Seconds 2
    $null=Invoke-Rcon 33461 $passwords['backend-a'] 'forceload add 80 80'
    $observerOwnerDeadline=[DateTime]::UtcNow.AddSeconds(30);$observerOwner=''
    do{$observerOwner=Invoke-Rcon 33461 $passwords['backend-a'] 'rc5boatowner 5 5';if($observerOwner -match 'RC5_BOAT_OWNER=boat-b'){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $observerOwnerDeadline)
    Assert-Command $observerOwner 'RC5_BOAT_OWNER=boat-b' 'Backend A observes Backend B ownership without taking control' | Out-Null
    $postTransferPosition=Invoke-Rcon 33461 $passwords['backend-a'] 'data get entity @e[type=minecraft:oak_boat,limit=1] Pos'
    Assert-Command $postTransferPosition '85' 'Peer sees post-transfer movement' | Out-Null
    $result='PASS: both backends saw one matching boat/pig, mount-dismount-remount, and the same passenger relation and movement after chunk ownership transferred A→B.'
    }
} catch { $failure=$_.Exception.Message }
finally {
    foreach($entry in @(@(33461,'backend-a'),@(33462,'backend-b'))){try{$null=Invoke-Rcon $entry[0] $passwords[$entry[1]] 'save-all flush';$null=Invoke-Rcon $entry[0] $passwords[$entry[1]] 'stop'}catch{}}
    $deadline=[DateTime]::UtcNow.AddSeconds(15);do{$running=@($processes | Where-Object {-not $_.HasExited});if($running.Count -eq 0){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline)
    foreach($process in $processes){if(-not $process.HasExited){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue}}
    $portDeadline=[DateTime]::UtcNow.AddSeconds(10)
    do { $masterAlive=@(Get-NetTCPConnection -State Listen -LocalPort 33451,33452,33453,33454,33461,33462 -ErrorAction SilentlyContinue); if(-not $masterAlive.Count){break}; Start-Sleep -Milliseconds 500 } while([DateTime]::UtcNow -lt $portDeadline)
    if($masterAlive.Count){$result += ' WARNING: one or more disposable ports remain open.'}
}
@(
    "Fixture: $root",
    "World: $world",
    "Paperclip SHA-256: $paperHash",
    "Master SHA-256: $masterHash",
    "Result: $result",
    "Failure: $failure",
    "Logs: $logs"
) | Set-Content -LiteralPath (Join-Path $root 'RESULT.txt')
if($failure){throw "Boat passenger runtime failed: $failure. Evidence retained at $root"}
if($masterAlive.Count){throw "Disposable listeners remain after shutdown. Inspect $root"}
Get-Content (Join-Path $root 'RESULT.txt')
