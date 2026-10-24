param(
    [Parameter(Mandatory = $true)][string]$PaperApiJar,
    [Parameter(Mandatory = $true)][string]$OutputJar,
    [string]$JavacPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javac.exe'
)

$ErrorActionPreference = 'Stop'
$paperApi = (Resolve-Path -LiteralPath $PaperApiJar).Path
$javac = (Resolve-Path -LiteralPath $JavacPath).Path
$runtimeLibraries = Join-Path $PSScriptRoot '..\..\..\libraries'
$runtimeLibraries = [IO.Path]::GetFullPath($runtimeLibraries)
$classpath = @($paperApi) + @(Get-ChildItem -LiteralPath $runtimeLibraries -Filter '*.jar' -File -Recurse | ForEach-Object FullName)
$sourceRoot = Join-Path $PSScriptRoot 'src/main/java'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('scheduled-tick-lock-probe-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $tempRoot 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
try {
    & $javac -encoding UTF-8 -cp ($classpath -join [IO.Path]::PathSeparator) -d $classes (Join-Path $sourceRoot 'probe/ScheduledTickLockProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'javac failed to compile the disposable lock probe.' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin.yml') -Destination $classes
    $output = [IO.Path]::GetFullPath($OutputJar)
    New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
    $temporaryZip = Join-Path $tempRoot 'probe.zip'
    Compress-Archive -Path (Join-Path $classes '*') -DestinationPath $temporaryZip -Force
    Move-Item -LiteralPath $temporaryZip -Destination $output -Force
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
