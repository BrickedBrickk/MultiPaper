param([Parameter(Mandatory)][string]$FixtureRoot, [ValidateSet('Main','Ender')][string]$InventoryKind='Main', [switch]$SimultaneousViewers, [string]$PaperclipSha256='6C65DDC42663B9924566B568153AEA6EF77B63648566850C3170B392AE0CBC4F', [string]$MasterSha256='E50B82443F1A1303258B5013DB156F9094CDAA372B043A25A0862462057233DE')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $FixtureRoot).Path
if(!(Test-Path -LiteralPath (Join-Path $root '.multipaper-disposable'))){throw 'Fixture is not marked .multipaper-disposable'}
if(!(Test-Path -LiteralPath (Join-Path $root 'backend-a\server.jar'))){throw 'Fixture backend is missing'}
$java='C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe'
$node='node.exe'
$nodeModules="$env:TEMP\mp-26.3-protocol-probe-deps\node_modules"
$mcData="$env:TEMP\minecraft-data-pr1301-current-924e26d6\data\pc\26.3"
$logs=Join-Path $root ('rc5-gate-runtime-logs-reusable-' + (Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Path $logs -ErrorAction SilentlyContinue | Out-Null
Set-Content -LiteralPath (Join-Path $logs 'stage.txt') -Value 'starting'
foreach($port in @(33151,33152,33153,33154,33161,33162)){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Port $port is already in use"}}
foreach($f in @('backend-a\server.jar','backend-b\server.jar')){if((Get-FileHash -LiteralPath (Join-Path $root $f)).Hash -ne $PaperclipSha256){throw "RC5 Paperclip mismatch: $f"}}
if((Get-FileHash -LiteralPath (Join-Path $root 'master\master.jar')).Hash -ne $MasterSha256){throw 'RC5 Master mismatch'}
foreach($b in @('backend-a','backend-b')){if((Get-FileHash -LiteralPath (Join-Path $root "$b\plugins\InvSee++.jar")).Hash -ne '01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9'){throw 'InvSee artifact mismatch'}}
function Read-Rcon($s){$h=[byte[]]::new(4);$o=0;while($o-lt 4){$n=$s.Read($h,$o,4-$o);if(!$n){throw 'RCON closed'};$o+=$n};$l=[BitConverter]::ToInt32($h,0);$b=[byte[]]::new($l);$o=0;while($o-lt $l){$n=$s.Read($b,$o,$l-$o);if(!$n){throw 'RCON closed'};$o+=$n};[pscustomobject]@{Id=[BitConverter]::ToInt32($b,0);Text=[Text.Encoding]::UTF8.GetString($b,8,$l-10)}}
function Write-Rcon($s,[int]$id,[int]$type,[string]$txt){$x=[Text.Encoding]::UTF8.GetBytes($txt);$l=10+$x.Length;$p=[byte[]]::new(4+$l);[BitConverter]::GetBytes($l).CopyTo($p,0);[BitConverter]::GetBytes($id).CopyTo($p,4);[BitConverter]::GetBytes($type).CopyTo($p,8);$x.CopyTo($p,12);$s.Write($p,0,$p.Length);$s.Flush()}
function Rcon([int]$port,[string]$pw,[string]$cmd){$c=[Net.Sockets.TcpClient]::new();$c.ReceiveTimeout=12000;try{$c.Connect('127.0.0.1',$port);$s=$c.GetStream();$s.ReadTimeout=12000;$id=Get-Random -Minimum 10000 -Maximum 1800000000;Write-Rcon $s $id 3 $pw;$a=Read-Rcon $s;if($a.Id-ne $id){throw "RCON auth failed $port"};Write-Rcon $s ($id+1) 2 $cmd;$r=Read-Rcon $s;if($r.Id-ne ($id+1)){throw 'RCON response ID mismatch'};Add-Content -LiteralPath (Join-Path $logs 'rcon.txt') -Value "$port> $cmd`n$($r.Text)";return $r.Text}finally{$c.Dispose()}}
function Wait-Log([string]$p,[string]$pat,[int]$sec=120){$end=[DateTime]::UtcNow.AddSeconds($sec);do{if((Test-Path $p)-and(Select-String -LiteralPath $p -Pattern $pat -Quiet -ErrorAction SilentlyContinue)){return};Start-Sleep -Milliseconds 400}while([DateTime]::UtcNow-lt $end);throw "Timeout waiting for $pat in $p"}
$pwA=[regex]::Match([IO.File]::ReadAllText((Join-Path $root 'backend-a\server.properties')),'(?m)^rcon.password=(.*)$').Groups[1].Value.Trim()
$pwB=[regex]::Match([IO.File]::ReadAllText((Join-Path $root 'backend-b\server.properties')),'(?m)^rcon.password=(.*)$').Groups[1].Value.Trim()
$owned=[Collections.Generic.List[Diagnostics.Process]]::new();$master=$null;$probe=$null
try{
  $psi=[Diagnostics.ProcessStartInfo]::new();$psi.FileName=$java;$psi.WorkingDirectory=Join-Path $root 'master';$psi.Arguments='-jar master.jar 33151 33152';$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
  $master=[Diagnostics.Process]::new();$master.StartInfo=$psi;[void]$master.Start();$owned.Add($master);$mo=[IO.File]::Create((Join-Path $logs 'master.out'));$me=[IO.File]::Create((Join-Path $logs 'master.err'));$mt1=$master.StandardOutput.BaseStream.CopyToAsync($mo);$mt2=$master.StandardError.BaseStream.CopyToAsync($me);Start-Sleep -Seconds 2
  foreach($b in @('backend-a','backend-b')){$out=Join-Path $logs "$b.out";$err=Join-Path $logs "$b.err";$pr=Start-Process -FilePath $java -WorkingDirectory (Join-Path $root $b) -ArgumentList @('-jar','server.jar','--nogui') -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru;$owned.Add($pr);Wait-Log $out 'Done \(' 180}
  $null=Rcon 33161 $pwA 'op RC5GateA';$null=Rcon 33162 $pwB 'op RC5GateB'
  $null=Rcon 33161 $pwA 'clear RC5GateA';$null=Rcon 33162 $pwB 'clear RC5GateB';$null=Rcon 33162 $pwB 'clear RC5GateTarget'
  Add-Content -LiteralPath (Join-Path $logs 'stage.txt') -Value 'operators granted'
  $env:NODE_PATH=$nodeModules;$env:MULTIPAPER_MC_DATA_26_3=$mcData
  $probeFile=Join-Path $PSScriptRoot 'test-fixtures\invsee-remote-click-probe\concurrent-viewers-protocol-valid.js';$probeLog=Join-Path $logs 'probe.out';$probeErr=Join-Path $logs 'probe.err'
  $clickScenario=if($SimultaneousViewers){'simultaneous'}else{'single'}
  $psi2=[Diagnostics.ProcessStartInfo]::new();$psi2.FileName=(Get-Command $node).Source;$psi2.WorkingDirectory=Split-Path -Parent $probeFile;$psi2.Arguments=('concurrent-viewers-protocol-valid.js 127.0.0.1 33153 33154 RC5GateA RC5GateB RC5GateTarget race-a race-b diamond 16 ' + $clickScenario + ' ' + $InventoryKind.ToLowerInvariant());$psi2.UseShellExecute=$false;$psi2.CreateNoWindow=$true;$psi2.RedirectStandardInput=$true;$psi2.RedirectStandardOutput=$true;$psi2.RedirectStandardError=$true
$probe=[Diagnostics.Process]::new();$probe.StartInfo=$psi2;[void]$probe.Start();$owned.Add($probe)
  [IO.File]::WriteAllText($probeLog,'');[IO.File]::WriteAllText($probeErr,'')
  $null=Register-ObjectEvent -InputObject $probe -EventName OutputDataReceived -MessageData $probeLog -Action { if($EventArgs.Data -ne $null){[IO.File]::AppendAllText($Event.MessageData,$EventArgs.Data+[Environment]::NewLine)} }
  $null=Register-ObjectEvent -InputObject $probe -EventName ErrorDataReceived -MessageData $probeErr -Action { if($EventArgs.Data -ne $null){[IO.File]::AppendAllText($Event.MessageData,$EventArgs.Data+[Environment]::NewLine)} }
  $probe.BeginOutputReadLine();$probe.BeginErrorReadLine()
  Wait-Log $probeLog 'READY t' 90;Wait-Log $probeLog 'READY a' 30;Wait-Log $probeLog 'READY b' 30
  Add-Content -LiteralPath (Join-Path $logs 'stage.txt') -Value 'probe ready'
  if($InventoryKind -eq 'Main'){$null=Rcon 33162 $pwB 'clear RC5GateTarget';$seedCommand='give RC5GateTarget minecraft:diamond 16';$targetData='Inventory'}else{$seedCommand='item replace entity RC5GateTarget enderchest.0 with minecraft:diamond 16';$targetData='EnderItems'};$seed=Rcon 33162 $pwB $seedCommand;Start-Sleep -Seconds 2;$seed=Rcon 33162 $pwB "data get entity RC5GateTarget $targetData";if($seed -notmatch 'minecraft:diamond.*count: 16'){throw "Target $InventoryKind seed missing exact diamond stack: $seed"};Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value "SEED $seed"
  $probe.StandardInput.WriteLine('OPEN');$probe.StandardInput.Flush();Start-Sleep -Seconds 5
  $probe.StandardInput.WriteLine('CLICK');$probe.StandardInput.Flush();Start-Sleep -Seconds 4
  $targetAfterClick=Rcon 33162 $pwB "data get entity RC5GateTarget $targetData"
  Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value ('TARGET_AFTER_REMOTE_CLICK ' + $targetAfterClick)
  if(-not $SimultaneousViewers){if($targetAfterClick -notmatch 'minecraft:diamond.*count: 16'){throw 'Remote click changed authoritative target inventory'}}
  $probe.StandardInput.WriteLine('CLOSE');$probe.StandardInput.Flush();Start-Sleep -Seconds 2
  if($SimultaneousViewers -and $InventoryKind -eq 'Main'){
    $targetAfterClose=Rcon 33162 $pwB 'data get entity RC5GateTarget Inventory'
    $viewerAAfterClose=Rcon 33161 $pwA 'data get entity RC5GateA Inventory'
    $viewerBAfterClose=Rcon 33162 $pwB 'data get entity RC5GateB Inventory'
    function Count-Diamonds([string]$value){$count=0;foreach($match in [regex]::Matches($value,'id:\s*"minecraft:diamond"[^}]*count:\s*(\d+)')){$count += [int]$match.Groups[1].Value};return $count}
    $targetCount=Count-Diamonds $targetAfterClose;$viewerACount=Count-Diamonds $viewerAAfterClose;$viewerBCount=Count-Diamonds $viewerBAfterClose
    Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value @("SIMULTANEOUS_AFTER_CLOSE_TARGET=$targetCount","SIMULTANEOUS_AFTER_CLOSE_VIEWER_A=$viewerACount","SIMULTANEOUS_AFTER_CLOSE_VIEWER_B=$viewerBCount","SIMULTANEOUS_AFTER_CLOSE_TOTAL=$($targetCount+$viewerACount+$viewerBCount)")
    if($targetCount -ne 0 -or $viewerACount -ne 0 -or $viewerBCount -ne 16){throw "Simultaneous click item totals wrong: target=$targetCount A=$viewerACount B=$viewerBCount; fixture evidence retained at $logs"}
  }
  $probe.StandardInput.WriteLine('QUIT');$probe.StandardInput.Flush();if(!$probe.WaitForExit(15000)){throw 'Probe did not exit'}
  $null=Rcon 33161 $pwA 'save-all flush';$null=Rcon 33162 $pwB 'save-all flush';Start-Sleep -Seconds 2
  Wait-Log $probeLog 'SYSTEM_CHAT a .*temporarily disabled for item safety' 10
  $probeText=[IO.File]::ReadAllText($probeLog)
  if($probeText -notmatch 'PROTOCOL_PREDICTED_(PICKUP|SIMULTANEOUS_PICKUPS)_SENT'){throw 'Remote click was not sent'}
  if($probeText -notmatch 'SYSTEM_CHAT a .*temporarily disabled for item safety'){throw 'Remote inventory safety denial was not observed by viewer A'}
  if($SimultaneousViewers -and $probeText -notmatch 'PROTOCOL_PREDICTED_SIMULTANEOUS_PICKUPS_SENT'){throw 'Both viewers did not send their predicted clicks'}
  $resyncDeadline=[DateTime]::UtcNow.AddSeconds(10);$resyncPassed=$false;$carriedLines=@();$cursorLines=@()
  do {
    $probeText=[IO.File]::ReadAllText($probeLog)
    $cursorLines=@($probeText -split [Environment]::NewLine | Where-Object {$_ -match '^CURSOR a '})
    $carriedLines=@($probeText -split [Environment]::NewLine | Where-Object {$_ -match '^CARRIED a state=\d+ count=\d+$'})
      $itemsLines=@($probeText -split [Environment]::NewLine | Where-Object {$_ -match '^ITEMS a state=\d+'})
    if($carriedLines.Count -ge 2 -and $itemsLines.Count -ge 2){
      $latestCarried=$carriedLines[-1];$latestItems=$itemsLines[-1]
      $carriedMatch=[regex]::Match($latestCarried,'^CARRIED a state=(\d+) count=0$')
      $itemsMatch=[regex]::Match($latestItems,'^ITEMS a state=(\d+) ')
      if($carriedMatch.Success -and $itemsMatch.Success -and $carriedMatch.Groups[1].Value -eq $itemsMatch.Groups[1].Value){$resyncPassed=$true;break}
    }
    Start-Sleep -Milliseconds 100
  } while([DateTime]::UtcNow -lt $resyncDeadline)
  Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value ('CURSOR_CORRECTIONS ' + $cursorLines.Count)
  $cursorLines | Add-Content -LiteralPath (Join-Path $logs 'assertions.txt')
  Add-Content -LiteralPath (Join-Path $logs 'CARRIED_STATES.txt') -Value ($carriedLines -join [Environment]::NewLine)
  Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value ('CARRIED_STATES ' + ($carriedLines -join '; '))
  if(!$resyncPassed){throw 'Latest client resync did not report restored menu contents and an empty carried stack at the same state ID'}
  if($SimultaneousViewers -and $InventoryKind -eq 'Main'){
    $aLines=@([IO.File]::ReadAllLines($probeLog) | Where-Object {$_ -match '^CARRIED a state=\d+ count=0$'})
    $ownerSlotCorrection=@([IO.File]::ReadAllLines($probeLog) | Where-Object {$_ -match '^SLOT a state=\d+ slot=0 empty$'})
    if($aLines.Count -lt 1 -or $ownerSlotCorrection.Count -lt 1){throw 'Remote viewer A did not retain an empty cursor and receive the target-owner slot correction'}
  }
  if($SimultaneousViewers){$result="PASS: exact RC5 simultaneous InvSee pickup race completed with the remote click denied; closed-inventory total remained 16. Logs: $logs"}else{$result="PASS: exact RC5 remote InvSee $InventoryKind click was denied; target retained 16 diamonds and client received the safety message. Logs: $logs"}
  Set-Content -LiteralPath (Join-Path $logs 'RESULT.txt') -Value $result;Write-Output $result
}finally{
  if($probe -and !$probe.HasExited){try{$probe.StandardInput.WriteLine('QUIT');$probe.StandardInput.Flush();$probe.WaitForExit(3000)}catch{try{Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue}catch{}}}
  try{$null=Rcon 33161 $pwA 'stop'}catch{};try{$null=Rcon 33162 $pwB 'stop'}catch{}
  foreach($pr in $owned){if($pr -and !$pr.HasExited){try{$pr.WaitForExit(30000)}catch{}}}
  if($master -and !$master.HasExited){try{$master.StandardInput.WriteLine('shutdown');$master.StandardInput.Flush();$master.WaitForExit(30000)}catch{try{Stop-Process -Id $master.Id -Force -ErrorAction SilentlyContinue}catch{}}}
  if($mo){$mo.Dispose()};if($me){$me.Dispose()};if($po){$po.Dispose()};if($pe){$pe.Dispose()}
  foreach($port in @(33151,33152,33153,33154,33161,33162)){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){Write-Warning "A listener remains on test port $port"}}
}
