param(
    [Parameter(Mandatory = $true)]
    [string]$FixtureRoot,
    [string]$JavaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe',
    [string]$PythonPath = 'C:\Users\elibo\AppData\Local\Programs\Python\Python311\python.exe',
    [string]$WorldName = 'fresh_release_smoke_world',
    [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'
$resolvedFixture = (Resolve-Path -LiteralPath $FixtureRoot).Path
$WorldName = $WorldName.Trim()
if ($WorldName -notmatch '^[A-Za-z0-9_-]+$') { throw "Invalid disposable world name: $WorldName" }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $resolvedFixture) -notmatch '^multipaper-26\.3-') {
    throw "Refusing to run destructive crash test outside a disposable MultiPaper temp fixture: $resolvedFixture"
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Java executable not found: $JavaPath" }
if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf)) { throw "Python executable not found: $PythonPath" }

$tag = 'hopper-deterministic-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$gateFile = Join-Path $resolvedFixture "$tag-gate.json"
$markerFile = Join-Path $resolvedFixture "$tag-cut.json"
$configPath = Join-Path $resolvedFixture 'backend-a\multipaper.yml'
$backendAProperties = Join-Path $resolvedFixture 'backend-a\server.properties'
$backendBProperties = Join-Path $resolvedFixture 'backend-b\server.properties'
$configBackup = Join-Path $resolvedFixture "$tag-multipaper.yml.backup"
$backendAPidFile = Join-Path $resolvedFixture "$tag-backend-a.pid"
$masterWorld = Join-Path $resolvedFixture "master\$WorldName\region"
$proxyScript = Join-Path (Split-Path -Parent $PSCommandPath) 'Test-MasterWriteCrashProxy.py'
$probeScript = Join-Path (Split-Path -Parent $PSCommandPath) 'Test-HopperCrashWindow.ps1'
$masterPid = $null
$backendAPid = $null
$backendBPid = $null
$proxyPid = $null

function Wait-Listener([int]$Port, [int]$Seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 500
        $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    } while (-not $listener -and [DateTime]::UtcNow -lt $deadline)
    if (-not $listener) { throw "Timed out waiting for disposable listener on port $Port." }
    return $listener
}

function Stop-DisposableProcess([int]$ProcessId, [string]$ExpectedPattern) {
    if ($ProcessId -le 0) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -match $ExpectedPattern) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Stop-ListenerProcess([int]$Port, [string]$ExpectedPattern) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { Stop-DisposableProcess $listener.OwningProcess $ExpectedPattern }
}

if (Test-Path -LiteralPath $configBackup) { throw "Refusing to overwrite existing backup $configBackup" }
foreach ($propertiesPath in @($backendAProperties, $backendBProperties)) {
    $levelNameLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^level-name=' } | Select-Object -First 1
    if ($levelNameLine -ne "level-name=$WorldName") {
        throw "Both disposable backends must use level-name=$WorldName before this crash test; '$propertiesPath' has '$levelNameLine'."
    }
}
Copy-Item -LiteralPath $configPath -Destination $configBackup
try {
    $configText = [IO.File]::ReadAllText($configPath)
    if ($configText -notmatch '(?m)^\s*master-address:\s*127\.0\.0\.1:26499\s*$') {
        throw 'Disposable Backend A config is not using the expected local Master address.'
    }
    $configText = [regex]::Replace($configText, '(?m)^\s*master-address:\s*127\.0\.0\.1:26499\s*$', '  master-address: 127.0.0.1:26498', 1)
    [IO.File]::WriteAllText($configPath, $configText, [Text.UTF8Encoding]::new($false))

    $masterOut = Join-Path $resolvedFixture "$tag-master.out.log"
    $masterErr = Join-Path $resolvedFixture "$tag-master.err.log"
    $master = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $resolvedFixture 'master') -ArgumentList @('-jar', 'master.jar', '26499', '26500') -WindowStyle Hidden -RedirectStandardOutput $masterOut -RedirectStandardError $masterErr -PassThru
    $masterPid = $master.Id
    Wait-Listener 26499 | Out-Null

    $backendBOut = Join-Path $resolvedFixture "$tag-backend-b.out.log"
    $backendBErr = Join-Path $resolvedFixture "$tag-backend-b.err.log"
    $backendB = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $resolvedFixture 'backend-b') -ArgumentList @('-jar', 'server.jar', 'nogui') -WindowStyle Hidden -RedirectStandardOutput $backendBOut -RedirectStandardError $backendBErr -PassThru
    $backendBPid = $backendB.Id
    Wait-Listener 26512 180 | Out-Null

    $pythonArgs = @(
        'Test-MasterWriteCrashProxy.py',
        '--world', $WorldName,
        '--source-cx', '127',
        '--destination-cx', '128',
        '--cz', '3125',
        '--source-region', (Join-Path $masterWorld 'r.3.97.mca'),
        '--destination-region', (Join-Path $masterWorld 'r.4.97.mca'),
        '--gate-file', $gateFile,
        '--marker-file', $markerFile,
        '--backend-pid', '0',
        '--backend-pid-file', $backendAPidFile
    )
    $proxyOut = Join-Path $resolvedFixture "$tag-proxy.out.log"
    $proxyErr = Join-Path $resolvedFixture "$tag-proxy.err.log"
    $proxy = Start-Process -FilePath $PythonPath -WorkingDirectory (Split-Path -Parent $proxyScript) -ArgumentList $pythonArgs -WindowStyle Hidden -RedirectStandardOutput $proxyOut -RedirectStandardError $proxyErr -PassThru
    $proxyPid = $proxy.Id
    Wait-Listener 26498 30 | Out-Null

    $backendAOut = Join-Path $resolvedFixture "$tag-backend-a.out.log"
    $backendAErr = Join-Path $resolvedFixture "$tag-backend-a.err.log"
    $backendA = Start-Process -FilePath $JavaPath -WorkingDirectory (Join-Path $resolvedFixture 'backend-a') -ArgumentList @('-jar', 'server.jar', 'nogui') -WindowStyle Hidden -RedirectStandardOutput $backendAOut -RedirectStandardError $backendAErr -PassThru
    $backendAPid = $backendA.Id
    $aGame = Wait-Listener 26501 180
    $aRcon = Wait-Listener 26511 30
    if ($aGame.OwningProcess -ne $aRcon.OwningProcess) { throw 'Backend A game and RCON ports have different owners.' }
    $backendAPid = $aGame.OwningProcess
    Set-Content -LiteralPath $backendAPidFile -Value $backendAPid -Encoding ascii

    $connectionDeadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 250
        $connected = Select-String -LiteralPath $backendAOut -SimpleMatch 'Connected to /127.0.0.1:26498' -Quiet -ErrorAction SilentlyContinue
    } while (-not $connected -and [DateTime]::UtcNow -lt $connectionDeadline)
    if (-not $connected) { throw 'Backend A did not connect through the deterministic write proxy.' }

    $password = (Get-Content -LiteralPath (Join-Path $resolvedFixture 'rcon-password.txt') -Raw).Trim()
    $masterJar = Join-Path $resolvedFixture 'master\master.jar'
    $serverJarA = Join-Path $resolvedFixture 'backend-a\server.jar'
    $serverJarB = Join-Path $resolvedFixture 'backend-b\server.jar'
    $paperclipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $serverJarA).Hash
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $serverJarB).Hash -ne $paperclipHash) {
        throw 'Disposable backends are not using the same Paperclip JAR.'
    }
    $masterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $masterJar).Hash
    & $probeScript -FixtureRoot $resolvedFixture -RconPassword $password -WorldName $WorldName -TimeoutSeconds $TimeoutSeconds -ProxyGateFile $gateFile -ProxyMarkerFile $markerFile -ProxyConfigBackup $configBackup -ExpectedPaperclipSha256 $paperclipHash -ExpectedMasterSha256 $masterHash -CrashAndRecover -ProxyCrashAndRecover
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Crash probe exited with code $LASTEXITCODE." }
    Write-Output "Deterministic proxy event: $(Get-Content -LiteralPath $markerFile -Raw)"
} finally {
    if (Test-Path -LiteralPath $configBackup) {
        Copy-Item -LiteralPath $configBackup -Destination $configPath -Force
    }
    Stop-DisposableProcess $proxyPid 'Test-MasterWriteCrashProxy.py'
    Stop-DisposableProcess $backendAPid 'server.jar nogui'
    Stop-DisposableProcess $backendBPid 'server.jar nogui'
    Stop-DisposableProcess $masterPid 'master.jar 26499 26500'
    Stop-ListenerProcess 26498 'Test-MasterWriteCrashProxy.py'
    Stop-ListenerProcess 26501 'server.jar nogui'
    Stop-ListenerProcess 26511 'server.jar nogui'
    Stop-ListenerProcess 26502 'server.jar nogui'
    Stop-ListenerProcess 26512 'server.jar nogui'
    Stop-ListenerProcess 26499 'master.jar 26499 26500'
}

$remaining = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in 26498,26499,26500,26501,26502,26511,26512
if ($remaining) { $remaining | Select-Object LocalPort,OwningProcess; throw 'Disposable crash-test listeners remain after cleanup.' }
Write-Output 'Disposable deterministic crash test stopped; all test ports are closed.'
