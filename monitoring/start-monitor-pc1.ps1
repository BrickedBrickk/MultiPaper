param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MonitorArgs = @()
)

$ErrorActionPreference = 'Stop'
$monitor = Join-Path $PSScriptRoot '..\scripts\multipaper-beta-monitor.py'
$config = [IO.Path]::GetFullPath($ConfigPath)
$python = Get-Command python -ErrorAction Stop

if (-not (Test-Path -LiteralPath $monitor -PathType Leaf)) { throw "Monitor script not found: $monitor" }
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { throw "Private monitor config not found: $config" }
$version = & $python.Source --version 2>&1
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'Python 3\.(1[0-9]|[2-9][0-9])') {
    throw "Python 3.10 or newer is required; found: $version"
}

& $python.Source $monitor --config $config @MonitorArgs
exit $LASTEXITCODE
