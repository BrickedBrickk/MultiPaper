param(
    [string]$TemplateRoot = 'D:\multipaper-tests\system-temp\rc5-chest-attribution-runtime-20261027',
    [string]$PreparedMasterWorldRoot = '',
    [string]$FixtureRoot = ('D:\multipaper-tests\system-temp\rc5-chest-master-fence-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$Paperclip = (Join-Path $PSScriptRoot '..\multipaper-server\build\libs\multipaper-paperclip-26.3-RC5.jar'),
    [string]$ApiJar = (Join-Path $PSScriptRoot '..\multipaper-api\build\libs\multipaper-api-26.3-RC5.jar'),
    [string]$MasterJar = (Join-Path $PSScriptRoot '..\MultiPaper-Master\build\libs\multipaper-master-3.0.0.jar'),
    [ValidateSet('REJECTED','ACCEPTED')][string]$ExpectedStaleWrite = 'REJECTED',
    [switch]$TestAfterFinalUnsubscribe,
    [ValidateSet('REJECTED','ACCEPTED')][string]$ExpectedFinalUnsubscribeWrite = 'REJECTED',
    [switch]$ReverseOwnershipOrder,
    [ValidateRange(0,3000)][int]$SaveBarrierWarmupMilliseconds = 150,
    [ValidateRange(0,3000)][int]$ReadRaceWriteDelayMilliseconds = 300,
    [ValidateRange(1,512)][int]$ReadRaceCount = 4,
    [string[]]$MasterJavaOptions = @(),
    [string[]]$BackendJavaOptions = @(),
    [string]$Java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$paperHashes = @(
    '6C65DDC42663B9924566B568153AEA6EF77B63648566850C3170B392AE0CBC4F', # clean current candidate used for Crafty1-scale no-holder stress
    'F5462FDA66732D7226920B04B89F8DB45D4125746B51F4BD90BDB3D8A46167B9', # clean RC5 candidate before persistent InvSee click gate
    '391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349', # persistent-input InvSee gate candidate
    '99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85', # current reproducible session-registration candidate
    '20024BC1C50AF5DDB1248AD2C79426A892A219AE69AE7E1770395123171B936A', # exact reproducible entity-getter-normalized candidate used in click-containment runtime
    '4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F', # exact normal Paperclip packaged in blocked review bundle v43
    '8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692'  # current packaged Ender Chest revision-fence candidate; chest race rerun documented for exact pair
)
$masterHash = (Get-FileHash -LiteralPath $MasterJar -Algorithm SHA256).Hash
$fencedMasterHash = 'BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C'
$currentSourceMasterHash = '1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E'
$activeChunkWriteFenceMasterHash = 'E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E' # current clean-built Master with accepted async chunk-write disconnect drain fence
$entityGetterNormalizedMasterHash = '8EB978519B900CEABDD67C278FCD1A0C16D2DF538879DFA509305EEB437E54B3' # exact reproducible pair used for the isolated InvSee click-containment run
$finalUnsubscribeFenceMasterHash = '2BE4B169A570543A5AFBCC7B80767336F27ACE8AFAD74EAF5E855853CD3D35DE'
$finalUnsubscribeBaselineMasterHash = 'E50B82443F1A1303258B5013DB156F9094CDAA372B043A25A0862462057233DE'
$baselineMasterHash = 'E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7'
$ports = @(33651,33652,33653,33654,33661,33662)
$root = [IO.Path]::GetFullPath($FixtureRoot)
$tempBase = [IO.Path]::GetFullPath('D:\multipaper-tests\system-temp').TrimEnd('\') + '\'
if (-not $root.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture must remain under D:\multipaper-tests\system-temp.' }
if (Test-Path -LiteralPath $root) { throw "Fixture already exists: $root" }
if (-not (Test-Path -LiteralPath (Join-Path $TemplateRoot '.multipaper-disposable'))) { throw 'Template is not marked disposable.' }
$preparedWorldName = $null
if ($PreparedMasterWorldRoot) {
    $preparedRoot = [IO.Path]::GetFullPath($PreparedMasterWorldRoot)
    $preparedTempBase = [IO.Path]::GetFullPath('D:\multipaper-tests\system-temp').TrimEnd('\') + '\'
    if (-not $preparedRoot.StartsWith($preparedTempBase, [StringComparison]::OrdinalIgnoreCase)) { throw 'Prepared world root must remain under D:\multipaper-tests\system-temp.' }
    if ($preparedRoot -eq $root) { throw 'Prepared world source and destination fixture must be different paths.' }
    if (-not (Test-Path -LiteralPath (Join-Path $preparedRoot '.multipaper-disposable'))) { throw 'Prepared world root is not marked disposable.' }
    $preparedProperties = Join-Path $preparedRoot 'backend-a\server.properties'
    if (-not (Test-Path -LiteralPath $preparedProperties)) { throw 'Prepared world backend properties are missing.' }
    $preparedWorldName = ((Get-Content -LiteralPath $preparedProperties | Where-Object { $_ -match '^level-name=' } | Select-Object -First 1) -replace '^level-name=', '')
    if (-not $preparedWorldName) { throw 'Prepared world name is missing.' }
    foreach ($suffix in @('', '_nether', '_the_end')) {
        $preparedDimension = Join-Path $preparedRoot ('master\' + $preparedWorldName + $suffix)
        if (-not (Test-Path -LiteralPath $preparedDimension)) { throw "Prepared Master dimension is missing: $preparedDimension" }
        $requiredDimensionPath = if ($suffix -eq '_nether') { 'DIM-1' } elseif ($suffix -eq '_the_end') { 'DIM1' } else { 'level.dat' }
        if (-not (Test-Path -LiteralPath (Join-Path $preparedDimension $requiredDimensionPath))) { throw "Prepared dimension data is missing: $preparedDimension\$requiredDimensionPath" }
    }
}
$paperclipHash = (Get-FileHash -LiteralPath $Paperclip -Algorithm SHA256).Hash
if ($paperclipHash -notin $paperHashes) { throw "Unrecognized Paperclip artifact: $paperclipHash" }
$paperHash = $paperclipHash
if ($TestAfterFinalUnsubscribe) {
    $expectedGapMasterHash = if ($ExpectedFinalUnsubscribeWrite -eq 'REJECTED') { $finalUnsubscribeFenceMasterHash } else { $finalUnsubscribeBaselineMasterHash }
    if ($masterHash -ne $expectedGapMasterHash) { throw "Final-unsubscribe gap test with ExpectedFinalUnsubscribeWrite=$ExpectedFinalUnsubscribeWrite requires Master $expectedGapMasterHash, got $masterHash." }
    if ($ExpectedStaleWrite -ne 'REJECTED') { throw 'Final-unsubscribe gap test requires ExpectedStaleWrite=REJECTED.' }
} elseif ($masterHash -notin @($fencedMasterHash,$currentSourceMasterHash,$activeChunkWriteFenceMasterHash,$entityGetterNormalizedMasterHash,$baselineMasterHash)) { throw "Unrecognized Master artifact: $masterHash" }
if (-not $TestAfterFinalUnsubscribe -and (($ExpectedStaleWrite -eq 'REJECTED' -and $masterHash -notin @($fencedMasterHash,$currentSourceMasterHash,$activeChunkWriteFenceMasterHash,$entityGetterNormalizedMasterHash)) -or ($ExpectedStaleWrite -eq 'ACCEPTED' -and $masterHash -ne $baselineMasterHash))) { throw "ExpectedStaleWrite=$ExpectedStaleWrite is incompatible with Master $masterHash." }
if (-not (Test-Path -LiteralPath $Java)) { throw 'Java 25 executable not found.' }
if (-not (Test-Path -LiteralPath $ApiJar)) { throw "RC5 API jar not found: $ApiJar" }
foreach ($port in $ports) { if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already listening." } }

function Copy-FixtureTree([string]$Source,[string]$Destination) {
    if (-not (Test-Path -LiteralPath $Destination)) { New-Item -ItemType Directory -Path $Destination | Out-Null }
    & robocopy $Source $Destination /E /MT:16 /R:1 /W:1 /COPY:DAT /DCOPY:DAT /NFL /NDL /NJH /NJS
    $copyExitCode = $LASTEXITCODE
    if ($copyExitCode -gt 7) { throw "Fixture copy failed with robocopy exit code $copyExitCode ($Source -> $Destination)." }
}

New-Item -ItemType Directory -Path $root | Out-Null
Set-Content -LiteralPath (Join-Path $root '.multipaper-disposable') -Value 'Fresh isolated RC5 stale-owner chest write and restart test. Synthetic blocks/items only.'
foreach ($component in @('master','backend-a','backend-b','run-logs')) { New-Item -ItemType Directory -Path (Join-Path $root $component) | Out-Null }
Copy-Item -LiteralPath $MasterJar -Destination (Join-Path $root 'master\master.jar')
Copy-Item -LiteralPath $Paperclip -Destination (Join-Path $root 'backend-a\server.jar')
Copy-Item -LiteralPath $Paperclip -Destination (Join-Path $root 'backend-b\server.jar')
foreach ($backend in @('backend-a','backend-b')) {
    $templateBackend=Join-Path $TemplateRoot 'backend-a'
    foreach ($name in @('eula.txt','server.properties','multipaper.yml','spigot.yml','bukkit.yml','purpur.yml','commands.yml')) { Copy-Item -LiteralPath (Join-Path $templateBackend $name) -Destination (Join-Path $root "$backend\$name") }
    foreach ($name in @('plugins','config','libraries','versions','cache')) {
        $path=Join-Path $templateBackend $name
        if (Test-Path $path) { Copy-FixtureTree $path (Join-Path (Join-Path $root $backend) $name) }
    }
}

$javaDir = Split-Path -Parent $Java
$javac = Join-Path $javaDir 'javac.exe'
$jarTool = Join-Path $javaDir 'jar.exe'
foreach ($tool in @($javac,$jarTool)) { if (-not (Test-Path -LiteralPath $tool)) { throw "Java 25 tool missing: $tool" } }
$pluginBuild = Join-Path $root 'stale-writer-plugin-build'
$pluginClasses = Join-Path $pluginBuild 'classes'
New-Item -ItemType Directory -Path $pluginClasses | Out-Null
$backendLibraries = @(Get-ChildItem -LiteralPath (Join-Path $root 'backend-a\libraries') -Filter '*.jar' -File -Recurse | ForEach-Object { $_.FullName })
$pluginClassPath = @(
    (Join-Path $root 'backend-a\versions\26.3\multipaper-26.3.jar'),
    (Resolve-Path $ApiJar).Path
) + $backendLibraries
$pluginSource = (Resolve-Path 'scripts\test-fixtures\chest-stale-writer\src\main\java\rc5\chest\RC5StaleChestWriter.java').Path
$compileArgs = Join-Path $pluginBuild 'compile.args'
$pluginClassPathForArgFile = @($pluginClassPath | ForEach-Object { $_.Replace('\','/') }) -join ';'
@('-encoding','UTF-8','-classpath',('"' + $pluginClassPathForArgFile + '"'),'-d',('"' + $pluginClasses.Replace('\','/') + '"'),('"' + $pluginSource.Replace('\','/') + '"')) | Set-Content -LiteralPath $compileArgs -Encoding Ascii
& $javac ('@' + $compileArgs)
if ($LASTEXITCODE -ne 0) { throw "Stale-writer plugin compilation failed ($LASTEXITCODE); retained at $pluginBuild" }
Copy-Item -LiteralPath 'scripts\test-fixtures\chest-stale-writer\plugin.yml' -Destination (Join-Path $pluginClasses 'plugin.yml')
$staleWriterPlugin = Join-Path $pluginBuild 'RC5StaleChestWriter.jar'
& $jarTool 'cf' $staleWriterPlugin '-C' $pluginClasses '.'
if ($LASTEXITCODE -ne 0) { throw "Stale-writer plugin packaging failed ($LASTEXITCODE); retained at $pluginBuild" }
foreach ($backend in @('backend-a','backend-b')) { Copy-Item -LiteralPath $staleWriterPlugin -Destination (Join-Path $root "$backend\plugins\RC5StaleChestWriter.jar") }

$world = 'rc5nh' + (Get-Date -Format 'MMddHHmmss')
if ($preparedWorldName) {
    foreach ($suffix in @('', '_nether', '_the_end')) {
        $sourceDimension = Join-Path $preparedRoot ('master\' + $preparedWorldName + $suffix)
        foreach ($component in @('master','backend-a','backend-b')) {
            $destinationDimension = Join-Path $root ($component + '\' + $world + $suffix)
            New-Item -ItemType Directory -Path $destinationDimension | Out-Null
            foreach ($file in @('level.dat','level.dat_old','uid.dat')) {
                $sourceFile = Join-Path $sourceDimension $file
                if (Test-Path -LiteralPath $sourceFile) { Copy-Item -LiteralPath $sourceFile -Destination $destinationDimension }
            }
            $directoriesToCopy = @('region','entities','poi','data','dimensions')
            if ($suffix -eq '_nether') { $directoriesToCopy += 'DIM-1' }
            if ($suffix -eq '_the_end') { $directoriesToCopy += 'DIM1' }
            foreach ($directory in $directoriesToCopy) {
                $sourceDirectory = Join-Path $sourceDimension $directory
                if (Test-Path -LiteralPath $sourceDirectory) {
                    Copy-FixtureTree $sourceDirectory (Join-Path $destinationDimension $directory)
                }
            }
        }
    }
    Set-Content -LiteralPath (Join-Path $root 'PREPARED-WORLD-SOURCE.txt') -Value @(
        "Source root: $preparedRoot",
        "Source world: $preparedWorldName",
        'Copied only Master dimension metadata and region/entities/poi/data into fresh fixture worlds.',
        'No playerdata, advancements, stats, configuration, or live-server data was copied.'
    )
}
foreach ($backend in @('backend-a','backend-b')) {
    $index = if ($backend -eq 'backend-a') { 0 } else { 1 }
    $propertiesPath = Join-Path $root "$backend\server.properties"
    $properties = Get-Content -LiteralPath $propertiesPath -Raw
    foreach ($entry in @(@('level-name',$world),@('server-port',[string](33653+$index)),@('rcon.port',[string](33661+$index)),@('server-ip','127.0.0.1'))) {
        $pattern = '(?m)^' + [regex]::Escape($entry[0]) + '=.*$'
        if (-not [regex]::IsMatch($properties,$pattern)) { throw "Missing property $($entry[0]) in $propertiesPath" }
        $properties = [regex]::Replace($properties,$pattern,[string]$entry[0]+'='+[string]$entry[1])
    }
    Set-Content -LiteralPath $propertiesPath -Value $properties -NoNewline
    $multiPath = Join-Path $root "$backend\multipaper.yml"
    $multi = Get-Content -LiteralPath $multiPath -Raw
    $multi = [regex]::Replace($multi,'(?m)^([ \t]*master-address:[ \t]*).*$', '${1}127.0.0.1:33651')
    $multi = [regex]::Replace($multi,'(?m)^([ \t]*my-name:[ \t]*).*$', ('${1}' + $(if($index -eq 0){'noholder-a'}else{'noholder-b'})))
    Set-Content -LiteralPath $multiPath -Value $multi -NoNewline
}
$passwords = @{}
foreach ($backend in @('backend-a','backend-b')) {
    $props = Get-Content (Join-Path $root "$backend\server.properties")
    $passwords[$backend] = (($props | Where-Object { $_ -like 'rcon.password=*' } | Select-Object -First 1) -split '=',2)[1]
}
$oldOwnerBackend = if ($ReverseOwnershipOrder) { 'backend-b' } else { 'backend-a' }
$newOwnerBackend = if ($ReverseOwnershipOrder) { 'backend-a' } else { 'backend-b' }
$oldOwnerPort = if ($oldOwnerBackend -eq 'backend-a') { 33661 } else { 33662 }
$newOwnerPort = if ($newOwnerBackend -eq 'backend-a') { 33661 } else { 33662 }
$logs = Join-Path $root 'run-logs'; $transcript = Join-Path $logs 'rcon-transcript.txt'
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()

function Read-RconPacket($stream) { $h=[byte[]]::new(4);$o=0;while($o -lt 4){$n=$stream.Read($h,$o,4-$o);if(-not $n){throw 'RCON closed.'};$o+=$n};$len=[BitConverter]::ToInt32($h,0);if($len -lt 10 -or $len -gt 4110){throw "Invalid RCON length $len"};$b=[byte[]]::new($len);$o=0;while($o -lt $len){$n=$stream.Read($b,$o,$len-$o);if(-not $n){throw 'RCON closed.'};$o+=$n};[pscustomobject]@{Id=[BitConverter]::ToInt32($b,0);Text=[Text.Encoding]::UTF8.GetString($b,8,$len-10)} }
function Write-RconPacket($stream,[int]$id,[int]$type,[string]$text) { $bytes=[Text.Encoding]::UTF8.GetBytes($text);$len=10+$bytes.Length;$p=[byte[]]::new(4+$len);[BitConverter]::GetBytes($len).CopyTo($p,0);[BitConverter]::GetBytes($id).CopyTo($p,4);[BitConverter]::GetBytes($type).CopyTo($p,8);$bytes.CopyTo($p,12);$stream.Write($p,0,$p.Length);$stream.Flush() }
function Invoke-Rcon([int]$port,[string]$password,[string]$command) { $client=[Net.Sockets.TcpClient]::new();$client.ReceiveTimeout=12000;try{$client.Connect('127.0.0.1',$port);$stream=$client.GetStream();$stream.ReadTimeout=12000;$id=Get-Random -Minimum 10000 -Maximum 1800000000;Write-RconPacket $stream $id 3 $password;$auth=Read-RconPacket $stream;if($auth.Id -ne $id){throw "RCON auth failed on $port"};Write-RconPacket $stream ($id+1) 2 $command;$reply=Read-RconPacket $stream;if($reply.Id -ne ($id+1)){throw "RCON response mismatch on $port"};Add-Content -LiteralPath $transcript -Value ("$port> $command`n$($reply.Text)");$reply.Text}finally{$client.Dispose()} }
function Wait-Ready([string]$log,[Diagnostics.Process]$process,[string]$label) { $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds);do{if($process.HasExited){throw "$label exited before readiness."};if((Test-Path $log) -and (Select-String -LiteralPath $log -Pattern 'Done \(' -Quiet)){return};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline);throw "$label did not become ready." }
function Assert-Match([string]$text,[string]$pattern,[string]$label) { if($text -notmatch $pattern){throw "$label expected /$pattern/; got: $text"};$text }
function Assert-ExactChestItems([string]$text,[string]$itemId,[int]$count,[string]$label) {
    $match = [regex]::Match($text, '(?s)following block data: (?<items>\[\{[^\r\n]*\}\])\s*$')
    $expected = '[{Slot: 0b, id: "' + $itemId + '", count: ' + $count + '}]'
    if (-not $match.Success -or $match.Groups['items'].Value -cne $expected) {
        throw "$label expected exact Items NBT $expected; got: $text"
    }
    $text
}
function Start-Backend([string]$name) { $javaArgs=@($BackendJavaOptions)+@('-jar','server.jar','--nogui');$p=Start-Process -FilePath $Java -WorkingDirectory (Join-Path $root $name) -ArgumentList $javaArgs -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs "$name.out") -RedirectStandardError (Join-Path $logs "$name.err") -PassThru;$processes.Add($p);Wait-Ready (Join-Path $logs "$name.out") $p $name;$p }

$result='FAILED';$failure=$null;$master=$null;$a=$null;$b=$null;$chestA=$null;$chestB=$null;$staleCapture=$null;$staleWrite=$null;$finalUnsubscribeStaleWrite=$null;$masterForcedStop=$false;$newOwnerCrashInjected=$false
try {
    $masterInfo=[Diagnostics.ProcessStartInfo]::new();$masterInfo.FileName=$Java;$masterInfo.WorkingDirectory=Join-Path $root 'master';$masterInfo.Arguments=((@($MasterJavaOptions)+@('-jar','master.jar','33651','33652')) -join ' ');$masterInfo.UseShellExecute=$false;$masterInfo.CreateNoWindow=$true;$masterInfo.RedirectStandardInput=$true;$masterInfo.RedirectStandardOutput=$true;$masterInfo.RedirectStandardError=$true
    $master=[Diagnostics.Process]::new();$master.StartInfo=$masterInfo;$masterOut=[IO.File]::Create((Join-Path $logs 'master.out'));$masterErr=[IO.File]::Create((Join-Path $logs 'master.err'));[void]$master.Start();$processes.Add($master)
    $masterOutTask=$master.StandardOutput.BaseStream.CopyToAsync($masterOut);$masterErrTask=$master.StandardError.BaseStream.CopyToAsync($masterErr)
    Start-Sleep -Seconds 2
    $oldOwnerProcess=Start-Backend $oldOwnerBackend
    if ($oldOwnerBackend -eq 'backend-a') { $a=$oldOwnerProcess } else { $b=$oldOwnerProcess }
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'forceload add 512 512'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'setblock 512 80 512 minecraft:chest'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'item replace block 512 80 512 container.0 with minecraft:diamond 32'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'setblock 514 80 512 minecraft:chest[facing=north,type=left]'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'setblock 515 80 512 minecraft:chest[facing=north,type=right]'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'item replace block 514 80 512 container.0 with minecraft:emerald 17'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'item replace block 515 80 512 container.0 with minecraft:gold_ingot 23'
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'save-all flush'
    $chestA=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'data get block 512 80 512 Items'
    Assert-ExactChestItems $chestA 'minecraft:diamond' 32 'Initial single chest' | Out-Null
    $staleCapture=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'rc5stalechest capture'
    Assert-Match $staleCapture 'RC5_STALE_CAPTURE=OK' 'Former owner captured a chest-bearing snapshot' | Out-Null
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'forceload remove 512 512'
    Start-Sleep -Seconds 8
    if ($TestAfterFinalUnsubscribe) {
        $finalUnsubscribeStaleWrite=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'rc5stalechest gapwrite'
        Assert-Match $finalUnsubscribeStaleWrite ("RC5_GAP_STALE_WRITE=" + $ExpectedFinalUnsubscribeWrite + ' holder=null master-ping=passed') 'Former-owner stale write after actual last unsubscribe' | Out-Null
    }
    $newOwnerProcess=Start-Backend $newOwnerBackend
    if ($newOwnerBackend -eq 'backend-a') { $a=$newOwnerProcess } else { $b=$newOwnerProcess }
    # This first region load occurs on a backend with no prior local holder/owner.
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'forceload add 512 512'
    Start-Sleep -Seconds 2
    $chestB=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 512 80 512 Items'
    Assert-ExactChestItems $chestB 'minecraft:diamond' 32 'New owner single chest after actual old-owner unload/handoff' | Out-Null
    $left=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 514 80 512 Items'
    $right=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 515 80 512 Items'
    Assert-ExactChestItems $left 'minecraft:emerald' 17 'Double chest left half after handoff' | Out-Null
    Assert-ExactChestItems $right 'minecraft:gold_ingot' 23 'Double chest right half after handoff' | Out-Null
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'item replace block 512 80 512 container.0 with minecraft:diamond 17'
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'save-all flush'
    $newerChest=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 512 80 512 Items'
    Assert-ExactChestItems $newerChest 'minecraft:diamond' 17 'New owner saved newer chest contents' | Out-Null
    $fallbackRace=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] "rc5stalechest readrace $SaveBarrierWarmupMilliseconds $ReadRaceWriteDelayMilliseconds $ReadRaceCount"
    $expectedRaceCount = if($ExpectedStaleWrite -eq 'REJECTED'){'17'}else{'32'}
    Assert-Match $fallbackRace ("RC5_READRACE=PASS holder=null reads=$ReadRaceCount workers=$([Math]::Min($ReadRaceCount,32)) staleWrite=" + $ExpectedStaleWrite + " diamond=" + $expectedRaceCount) 'No-holder concurrent reads during delayed stale-owner write' | Out-Null
    $staleWrite=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'rc5stalechest write'
    Assert-Match $staleWrite ("RC5_STALE_WRITE=" + $ExpectedStaleWrite) "Former owner stale snapshot $ExpectedStaleWrite" | Out-Null
    if ($ExpectedStaleWrite -eq 'REJECTED') {
        $afterStale=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 512 80 512 Items'
        Assert-ExactChestItems $afterStale 'minecraft:diamond' 17 'New owner chest after stale snapshot rejection' | Out-Null
    }
    $null=Invoke-Rcon $oldOwnerPort $passwords[$oldOwnerBackend] 'stop'
    $deadline=[DateTime]::UtcNow.AddSeconds(120);while(-not $oldOwnerProcess.HasExited -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 250};if(-not $oldOwnerProcess.HasExited){throw 'Former owner did not stop gracefully after stale-write injection.'}
    if ($ExpectedStaleWrite -eq 'ACCEPTED') {
        # Preserve the stale Master snapshot by simulating the new owner failing before it can
        # rewrite its newer in-memory chunk. This process is disposable and owned here.
        $newOwnerCrashInjected=$true
        Stop-Process -Id $newOwnerProcess.Id -Force
        if (-not $newOwnerProcess.WaitForExit(30000)) { throw 'Disposable new owner did not exit after the baseline crash injection.' }
    } else {
        $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'save-all flush'
        $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'stop'
        $deadline=[DateTime]::UtcNow.AddSeconds(120);while(-not $newOwnerProcess.HasExited -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 250};if(-not $newOwnerProcess.HasExited){throw 'New owner did not stop gracefully within 120 seconds.'}
    }
    $newOwnerProcess=Start-Backend $newOwnerBackend
    if ($newOwnerBackend -eq 'backend-a') { $a=$newOwnerProcess } else { $b=$newOwnerProcess }
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'forceload add 512 512'
    Start-Sleep -Seconds 2
    $afterRestart=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 512 80 512 Items'
    # In the baseline control, crash B before its newer in-memory copy can rewrite
    # the stale Master snapshot. The fenced candidate follows graceful shutdown.
    $expectedChestCount = if($ExpectedStaleWrite -eq 'REJECTED'){'17'}else{'32'}
    Assert-ExactChestItems $afterRestart 'minecraft:diamond' ([int]$expectedChestCount) 'Single chest after new-owner restart' | Out-Null
    $leftAfter=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 514 80 512 Items'
    $rightAfter=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 515 80 512 Items'
    Assert-ExactChestItems $leftAfter 'minecraft:emerald' 17 'Double chest left half after restart' | Out-Null
    Assert-ExactChestItems $rightAfter 'minecraft:gold_ingot' 23 'Double chest right half after restart' | Out-Null
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'save-all flush'
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'stop'
    $deadline=[DateTime]::UtcNow.AddSeconds(120);while(-not $newOwnerProcess.HasExited -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 250};if(-not $newOwnerProcess.HasExited){throw 'New owner did not stop gracefully before Master restart.'}
    $master.StandardInput.WriteLine('exit');$master.StandardInput.Flush();if(-not $master.WaitForExit(120000)){throw 'Master did not stop gracefully before its persistence restart.'}
    $masterOut.Dispose();$masterErr.Dispose();$master=$null

    $masterInfo=[Diagnostics.ProcessStartInfo]::new();$masterInfo.FileName=$Java;$masterInfo.WorkingDirectory=Join-Path $root 'master';$masterInfo.Arguments=((@($MasterJavaOptions)+@('-jar','master.jar','33651','33652')) -join ' ');$masterInfo.UseShellExecute=$false;$masterInfo.CreateNoWindow=$true;$masterInfo.RedirectStandardInput=$true;$masterInfo.RedirectStandardOutput=$true;$masterInfo.RedirectStandardError=$true
    $master=[Diagnostics.Process]::new();$master.StartInfo=$masterInfo;$masterOut=[IO.File]::Create((Join-Path $logs 'master-restart.out'));$masterErr=[IO.File]::Create((Join-Path $logs 'master-restart.err'));[void]$master.Start();$processes.Add($master)
    $masterOutTask2=$master.StandardOutput.BaseStream.CopyToAsync($masterOut);$masterErrTask2=$master.StandardError.BaseStream.CopyToAsync($masterErr)
    Start-Sleep -Seconds 2
    if($master.HasExited){throw 'Disposable Master exited during persistence restart.'}
    $newOwnerProcess=Start-Backend $newOwnerBackend
    if ($newOwnerBackend -eq 'backend-a') { $a=$newOwnerProcess } else { $b=$newOwnerProcess }
    $null=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'forceload add 512 512';Start-Sleep -Seconds 2
    $afterMasterRestart=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 512 80 512 Items'
    $leftMasterRestart=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 514 80 512 Items'
    $rightMasterRestart=Invoke-Rcon $newOwnerPort $passwords[$newOwnerBackend] 'data get block 515 80 512 Items'
    Assert-ExactChestItems $afterMasterRestart 'minecraft:diamond' ([int]$expectedChestCount) 'Single chest after Master restart' | Out-Null
    Assert-ExactChestItems $leftMasterRestart 'minecraft:emerald' 17 'Double chest left half after Master restart' | Out-Null
    Assert-ExactChestItems $rightMasterRestart 'minecraft:gold_ingot' 23 'Double chest right half after Master restart' | Out-Null
    $result="PASS: $oldOwnerBackend former-owner stale snapshot was $ExpectedStaleWrite; exact chest contents survived handoff, $newOwnerBackend restart and graceful Master restart."
} catch { $failure=$_.Exception.Message }
finally {
    foreach($entry in @(@(33661,'backend-a',$a),@(33662,'backend-b',$b))){if($entry[2] -and -not $entry[2].HasExited){try{$null=Invoke-Rcon $entry[0] $passwords[$entry[1]] 'save-all flush';$null=Invoke-Rcon $entry[0] $passwords[$entry[1]] 'stop'}catch{}}}
    $backendProcesses=@($processes | Where-Object {$_.Id -ne $master.Id})
    $deadline=[DateTime]::UtcNow.AddSeconds(120);do{$running=@($backendProcesses | Where-Object {-not $_.HasExited});if(-not $running.Count){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline)
    foreach($proc in $backendProcesses){if(-not $proc.HasExited){Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue}}
    if($master -and -not $master.HasExited){try{$master.StandardInput.WriteLine('exit');$master.StandardInput.Flush();$null=$master.WaitForExit(120000)}catch{}}
    if($master -and -not $master.HasExited){$masterForcedStop=$true;Stop-Process -Id $master.Id -Force -ErrorAction SilentlyContinue}
    if($masterOut){$masterOut.Dispose()};if($masterErr){$masterErr.Dispose()}
    $masterAlive=@(Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue)
}
$logHits=@();foreach($log in @((Join-Path $logs 'backend-a.out'),(Join-Path $logs 'backend-b.out'))){if(Test-Path $log){$logHits += Select-String -LiteralPath $log -Pattern 'has no chunk holder for reading chunk' | ForEach-Object {$_.Line}}}
$targetHit=@($logHits | Where-Object {$_ -match ',region,32,32,'}).Count -gt 0
if(-not $failure -and -not $targetHit){if($ExpectedStaleWrite -eq 'REJECTED'){$result='PASS: stale former-owner write rejected; exact chest contents survived handoff and both restart boundaries; no-holder branch not observed.'}else{$result='PASS: baseline accepted the delayed former-owner write; after disposable new-owner crash/restart, it loaded the stale 32-item snapshot; no-holder branch not observed.'}}
if($masterForcedStop){$result='INCOMPLETE: disposable Master required force stop';if(-not $failure){$failure='Master did not complete graceful exit within 120 seconds.'}}
$summary=@("Fixture: $root","World: $world","Paperclip SHA-256: $paperHash","Master SHA-256: $masterHash","Master Java options: $($MasterJavaOptions -join ' ')","Backend Java options: $($BackendJavaOptions -join ' ')","Ownership order reversed: $ReverseOwnershipOrder","Old owner backend: $oldOwnerBackend","New owner backend: $newOwnerBackend","Expected former-owner write: $ExpectedStaleWrite","Final-unsubscribe gap test: $TestAfterFinalUnsubscribe","Expected stale write after final unsubscribe: $ExpectedFinalUnsubscribeWrite","Stale write after final unsubscribe: $finalUnsubscribeStaleWrite","Save barrier warmup milliseconds: $SaveBarrierWarmupMilliseconds","Read race write delay milliseconds: $ReadRaceWriteDelayMilliseconds","Read race count: $ReadRaceCount","Stale snapshot capture: $staleCapture","Stale snapshot write: $staleWrite","Disposable new-owner crash injected: $newOwnerCrashInjected","No-holder fallback warnings: $($logHits.Count)","Target chunk (32,32) warning observed: $targetHit","Initial new-owner chest result: $chestB","After new-owner restart: $afterRestart","After Master restart: $afterMasterRestart","After Master restart double chest left: $leftMasterRestart","After Master restart double chest right: $rightMasterRestart","Master force-stopped: $masterForcedStop","Result: $result","Failure: $failure","Logs: $logs")
$summary | Set-Content -LiteralPath (Join-Path $root 'RESULT.txt')
Get-Content -LiteralPath (Join-Path $root 'RESULT.txt')
if($masterAlive.Count){throw 'One or more disposable listeners remain; inspect fixture logs.'}
if($failure){throw "Isolated chest fallback test failed: $failure. Evidence retained at $root"}
