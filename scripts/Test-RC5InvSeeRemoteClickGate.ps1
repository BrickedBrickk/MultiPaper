param([Parameter(Mandatory)][string]$FixtureRoot, [string]$PaperclipSha256='391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349', [string]$MasterSha256='BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C')
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
  Add-Content -LiteralPath (Join-Path $logs 'stage.txt') -Value 'operators granted'
  $env:NODE_PATH=$nodeModules;$env:MULTIPAPER_MC_DATA_26_3=$mcData
  $probeFile=Join-Path $PSScriptRoot 'test-fixtures\invsee-remote-click-probe\concurrent-viewers-protocol-valid.js';$probeLog=Join-Path $logs 'probe.out';$probeErr=Join-Path $logs 'probe.err'
  $psi2=[Diagnostics.ProcessStartInfo]::new();$psi2.FileName=(Get-Command $node).Source;$psi2.WorkingDirectory=Split-Path -Parent $probeFile;$psi2.Arguments='concurrent-viewers-protocol-valid.js 127.0.0.1 33153 33154 RC5GateA RC5GateB RC5GateTarget race-a race-b diamond 16';$psi2.UseShellExecute=$false;$psi2.CreateNoWindow=$true;$psi2.RedirectStandardInput=$true;$psi2.RedirectStandardOutput=$true;$psi2.RedirectStandardError=$true
$probe=[Diagnostics.Process]::new();$probe.StartInfo=$psi2;[void]$probe.Start();$owned.Add($probe)
  [IO.File]::WriteAllText($probeLog,'');[IO.File]::WriteAllText($probeErr,'')
  $null=Register-ObjectEvent -InputObject $probe -EventName OutputDataReceived -MessageData $probeLog -Action { if($EventArgs.Data -ne $null){[IO.File]::AppendAllText($Event.MessageData,$EventArgs.Data+[Environment]::NewLine)} }
  $null=Register-ObjectEvent -InputObject $probe -EventName ErrorDataReceived -MessageData $probeErr -Action { if($EventArgs.Data -ne $null){[IO.File]::AppendAllText($Event.MessageData,$EventArgs.Data+[Environment]::NewLine)} }
  $probe.BeginOutputReadLine();$probe.BeginErrorReadLine()
  Wait-Log $probeLog 'READY t' 90;Wait-Log $probeLog 'READY a' 30;Wait-Log $probeLog 'READY b' 30
  Add-Content -LiteralPath (Join-Path $logs 'stage.txt') -Value 'probe ready'
  $null=Rcon 33162 $pwB 'clear RC5GateTarget';$null=Rcon 33162 $pwB 'give RC5GateTarget minecraft:diamond 16';Start-Sleep -Seconds 2;$seed=Rcon 33162 $pwB 'data get entity RC5GateTarget Inventory';if($seed -notmatch 'minecraft:diamond'){throw "Target seed missing diamond: $seed"};Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value "SEED $seed"
  $probe.StandardInput.WriteLine('OPEN');$probe.StandardInput.Flush();Start-Sleep -Seconds 5
  $probe.StandardInput.WriteLine('CLICK');$probe.StandardInput.Flush();Start-Sleep -Seconds 4
  $targetAfterClick=Rcon 33162 $pwB 'data get entity RC5GateTarget Inventory'
  Add-Content -LiteralPath (Join-Path $logs 'assertions.txt') -Value ('TARGET_AFTER_REMOTE_CLICK ' + $targetAfterClick)
  if($targetAfterClick -notmatch 'minecraft:diamond.*count: 16'){throw 'Remote click changed authoritative target inventory'}
  $probe.StandardInput.WriteLine('CLOSE');$probe.StandardInput.Flush();Start-Sleep -Seconds 2;$probe.StandardInput.WriteLine('QUIT');$probe.StandardInput.Flush();if(!$probe.WaitForExit(15000)){throw 'Probe did not exit'}
  $null=Rcon 33161 $pwA 'save-all flush';$null=Rcon 33162 $pwB 'save-all flush';Start-Sleep -Seconds 2
  $probeText=[IO.File]::ReadAllText($probeLog)
  if($probeText -notmatch 'PROTOCOL_PREDICTED_PICKUP_SENT'){throw 'Remote click was not sent'}
  if($probeText -notmatch 'SYSTEM_CHAT .*temporarily disabled for item safety'){throw 'Remote inventory safety denial was not observed by the client'}
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
  $result="PASS: exact RC5 remote InvSee click was denied; target retained 16 diamonds and client received the safety message. Logs: $logs"
  Set-Content -LiteralPath (Join-Path $logs 'RESULT.txt') -Value $result;Write-Output $result
}finally{
  if($probe -and !$probe.HasExited){try{$probe.StandardInput.WriteLine('QUIT');$probe.StandardInput.Flush();$probe.WaitForExit(3000)}catch{try{Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue}catch{}}}
  try{$null=Rcon 33161 $pwA 'stop'}catch{};try{$null=Rcon 33162 $pwB 'stop'}catch{}
  foreach($pr in $owned){if($pr -and !$pr.HasExited){try{$pr.WaitForExit(30000)}catch{}}}
  if($master -and !$master.HasExited){try{$master.StandardInput.WriteLine('shutdown');$master.StandardInput.Flush();$master.WaitForExit(30000)}catch{try{Stop-Process -Id $master.Id -Force -ErrorAction SilentlyContinue}catch{}}}
  if($mo){$mo.Dispose()};if($me){$me.Dispose()};if($po){$po.Dispose()};if($pe){$pe.Dispose()}
  foreach($port in @(33151,33152,33153,33154,33161,33162)){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){Write-Warning "A listener remains on test port $port"}}
}
