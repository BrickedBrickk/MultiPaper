param(
    [Parameter(Mandatory = $true)][string]$TemplateRoot,
    [Parameter(Mandatory = $true)][string]$PaperclipJar,
    [Parameter(Mandatory = $true)][string]$MasterJar,
    [string]$FixtureRoot = (Join-Path ([IO.Path]::GetTempPath()) ('multipaper-26.3-peer-start-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))),
    [int]$MasterPort = 31121,
    [int]$BackendAPort = 31123,
    [int]$BackendBPort = 31124,
    [int]$BackendARconPort = 31131,
    [int]$BackendBRconPort = 31132,
    [string]$JavaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$template = (Resolve-Path -LiteralPath $TemplateRoot).Path
$paperclip = (Resolve-Path -LiteralPath $PaperclipJar).Path
$masterArtifact = (Resolve-Path -LiteralPath $MasterJar).Path
$root = [IO.Path]::GetFullPath($FixtureRoot)
$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $root.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $root) -notmatch '^multipaper-26\.3-') {
    throw "Refusing to create the disposable fixture outside a named temp directory: $root"
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }
if (Test-Path -LiteralPath $root) { throw "Fixture already exists: $root" }

$password = [Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $root,(Join-Path $root 'master') | Out-Null
foreach ($name in 'backend-a', 'backend-b') {
    $backend = Join-Path $root $name
    Copy-Item -LiteralPath (Join-Path $template $name) -Destination $backend -Recurse
    $propertiesPath = Join-Path $backend 'server.properties'
    $properties = Get-Content -LiteralPath $propertiesPath -Raw
    $levelMatch = [regex]::Match($properties, '(?m)^level-name=(.+)$')
    if (-not $levelMatch.Success) { throw "Missing level-name in $propertiesPath" }
    $oldLevel = $levelMatch.Groups[1].Value.Trim()
    $backendPrefix = [IO.Path]::GetFullPath($backend).TrimEnd('\') + '\'
    foreach ($world in @($oldLevel, "${oldLevel}_nether", "${oldLevel}_the_end")) {
        $oldWorldPath = [IO.Path]::GetFullPath((Join-Path $backend $world))
        if ($oldWorldPath.StartsWith($backendPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $oldWorldPath)) {
            Remove-Item -LiteralPath $oldWorldPath -Recurse -Force
        }
    }
    $isA = $name -eq 'backend-a'
    $gamePort = if ($isA) { $BackendAPort } else { $BackendBPort }
    $rconPort = if ($isA) { $BackendARconPort } else { $BackendBRconPort }
    $properties = [regex]::Replace($properties, '(?m)^level-name=.*$', 'level-name=peerStartRace')
    $properties = [regex]::Replace($properties, '(?m)^server-port=.*$', "server-port=$gamePort")
    $properties = [regex]::Replace($properties, '(?m)^rcon.port=.*$', "rcon.port=$rconPort")
    $properties = [regex]::Replace($properties, '(?m)^rcon.password=.*$', "rcon.password=$password")
    Set-Content -LiteralPath $propertiesPath -Value $properties -NoNewline
    Copy-Item -LiteralPath $paperclip -Destination (Join-Path $backend 'server.jar') -Force

    $multiPaperPath = Join-Path $backend 'multipaper.yml'
    $multiPaper = Get-Content -LiteralPath $multiPaperPath -Raw
    $multiPaper = [regex]::Replace($multiPaper, '(?m)^  master-address:.*$', "  master-address: 127.0.0.1:$MasterPort")
    $peerName = if ($isA) { 'peerfix-a' } else { 'peerfix-b' }
    $multiPaper = [regex]::Replace($multiPaper, '(?m)^  my-name:.*$', "  my-name: $peerName")
    Set-Content -LiteralPath $multiPaperPath -Value $multiPaper -NoNewline
}
Copy-Item -LiteralPath $masterArtifact -Destination (Join-Path $root 'master\master.jar')
@{
    root = $root
    paperclip = (Get-FileHash -LiteralPath $paperclip -Algorithm SHA256).Hash
    master = (Get-FileHash -LiteralPath $masterArtifact -Algorithm SHA256).Hash
    rconPassword = $password
    masterPort = $MasterPort
    backendAPort = $BackendAPort
    backendBPort = $BackendBPort
    backendARconPort = $BackendARconPort
    backendBRconPort = $BackendBRconPort
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'fixture-meta.json')

$masterProcess = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $root 'master') -ArgumentList @('-jar', 'master.jar', "$MasterPort", "$($MasterPort + 1)") -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'master.out') -RedirectStandardError (Join-Path $root 'master.err') -PassThru
$backendA = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $root 'backend-a') -ArgumentList @('-jar', 'server.jar', 'nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'backend-a.out') -RedirectStandardError (Join-Path $root 'backend-a.err') -PassThru
$backendB = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $root 'backend-b') -ArgumentList @('-jar', 'server.jar', 'nogui') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'backend-b.out') -RedirectStandardError (Join-Path $root 'backend-b.err') -PassThru
$deadline = [DateTime]::UtcNow.AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $MasterPort,$BackendAPort,$BackendBPort -ErrorAction SilentlyContinue
    $ready = @($listeners | Select-Object -ExpandProperty LocalPort -Unique).Count -eq 3
    $alive = -not $masterProcess.HasExited -and -not $backendA.HasExited -and -not $backendB.HasExited
} while (-not ($ready -and $alive) -and [DateTime]::UtcNow -lt $deadline)
if (-not ($ready -and $alive)) { throw "Fixture failed startup; inspect logs under $root" }
Write-Output "FixtureRoot=$root"
Write-Output "MasterPid=$($masterProcess.Id) BackendAPid=$($backendA.Id) BackendBPid=$($backendB.Id)"
Write-Output "PaperclipSHA256=$((Get-FileHash -LiteralPath $paperclip -Algorithm SHA256).Hash)"
Write-Output "MasterSHA256=$((Get-FileHash -LiteralPath $masterArtifact -Algorithm SHA256).Hash)"
