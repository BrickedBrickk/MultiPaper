param(
    [Parameter(Mandatory = $true)] [string] $PriorArchive,
    [Parameter(Mandatory = $true)] [string] $BuildRoot,
    [Parameter(Mandatory = $true)] [string] $OutputArchive,
    [Parameter(Mandatory = $true)] [string] $ProductSourceCommit
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$prior = (Resolve-Path -LiteralPath $PriorArchive).Path
$build = (Resolve-Path -LiteralPath $BuildRoot).Path
$output = [System.IO.Path]::GetFullPath($OutputArchive)
$payload = "$output.payload"
$expected = @{
    Master = 'E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E'
    Paperclip = '4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F'
}

if (-not $output.StartsWith((Join-Path $repo 'release-staging'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must stay under release-staging.' }
if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite archive: $output" }
if (Test-Path -LiteralPath $payload) { throw "Refusing to reuse payload directory: $payload" }
if ($ProductSourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'ProductSourceCommit must be a full commit SHA.' }

$masterJar = Join-Path $build 'MultiPaper-Master\build\libs\multipaper-master-3.0.0.jar'
$paperclipJar = Join-Path $build 'multipaper-server\build\libs\multipaper-paperclip-26.3-RC5.jar'
foreach ($item in @(@{ Path = $masterJar; Hash = $expected.Master }, @{ Path = $paperclipJar; Hash = $expected.Paperclip })) {
    if (-not (Test-Path -LiteralPath $item.Path -PathType Leaf)) { throw "Missing build artifact: $($item.Path)" }
    $actual = (Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash
    if ($actual -ne $item.Hash) { throw "Artifact hash mismatch at $($item.Path): $actual" }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($prior, $payload)
$replacements = @{
    'bin/multipaper-master-3.0.0.jar' = $masterJar
    'bin/multipaper-paperclip-26.3-RC5.jar' = $paperclipJar
    'docs/RC5_BUG_TRACKER.md' = Join-Path $repo 'docs\RC5_BUG_TRACKER.md'
    'docs/RC5_COMPATIBILITY_MATRIX.md' = Join-Path $repo 'docs\RC5_COMPATIBILITY_MATRIX.md'
    'docs/RC5_MANUAL_UPDATE_PLAN.md' = Join-Path $repo 'docs\RC5_MANUAL_UPDATE_PLAN.md'
    'monitoring/README.md' = Join-Path $repo 'monitoring\README.md'
    'monitoring/BETA-TEST-PLAN.md' = Join-Path $repo 'monitoring\BETA-TEST-PLAN.md'
    'monitoring/MANUAL-CRAFTY-TOPOLOGY.md' = Join-Path $repo 'monitoring\MANUAL-CRAFTY-TOPOLOGY.md'
    'monitoring/NETWORK-ROUTES.md' = Join-Path $repo 'monitoring\NETWORK-ROUTES.md'
    'monitoring/start-monitor-pc1.ps1' = Join-Path $repo 'monitoring\start-monitor-pc1.ps1'
    'scripts/multipaper-beta-monitor.py' = Join-Path $repo 'scripts\multipaper-beta-monitor.py'
}
foreach ($entry in $replacements.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) { throw "Missing package input: $($entry.Value)" }
    $destination = Join-Path $payload ($entry.Key.Replace('/', '\'))
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination))
    Copy-Item -LiteralPath $entry.Value -Destination $destination -Force
}

$reportNames = @(
    'rc5-player-session-registration-readiness-20261029.md',
    'rc5-session-registration-ack-readiness-runtime-20261029.md',
    'rc5-monitor-readable-summary-20261029.md',
    'rc5-crafty1-upload-evidence-recheck-20261028.md',
    'rc5-packaged-pair-chest-race-20261029.md',
    'rc5-current-sixcomponent-shutdown-write-rejections-20261029.md'
)
$evidence = Join-Path $payload 'evidence'
foreach ($name in $reportNames) {
    $source = Join-Path $repo "performance-results\$name"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing evidence report: $source" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $evidence $name) -Force
}

$checkoutHead = (& git -C $repo rev-parse HEAD).Trim()
$provenance = @"
RC5 blocked review package provenance

Packaging checkout HEAD: $checkoutHead
Product source commit: $ProductSourceCommit (base 04724a62e188d5f99c20435a6a47427d09de5d1e plus the exact implementation/test overlay committed as f59ae3833b8faa4344c407a04beba98978f79bea)
Build: Temurin Java 25.0.3+9 / Gradle 9.8.0; two sequential clean full builds, offline, no build cache, no daemon, one worker.
Tests: 10,794 passed, 0 failures, 0 errors, 88 skipped in each build; all nine JARs byte-identical across the two runs.
Build evidence: evidence/rc5-player-session-registration-readiness-20261029.md
Master SHA-256: $($expected.Master)
Paperclip SHA-256: $($expected.Paperclip)
Runtime evidence limitation: the paired session-ACK runtime used a diagnostic Paperclip and is not evidence from the normal Paperclip packaged here. See evidence/rc5-session-registration-ack-readiness-runtime-20261029.md.
Release state: BLOCKED / NOT FOR INSTALLATION OR DISTRIBUTION.
"@
Set-Content -LiteralPath (Join-Path $evidence 'BUILD-ARTIFACT-PROVENANCE.txt') -Value $provenance -Encoding utf8

$readme = @"
MultiPaper 26.3 RC5 — BLOCKED REVIEW PACKAGE

NOT FOR INSTALLATION OR DISTRIBUTION. This is a local review package only. The live RC4 SMP must remain unchanged.

Packaged binaries (Java 25):
- Master SHA-256: $($expected.Master)
- Paperclip SHA-256: $($expected.Paperclip)
- Product source commit: $ProductSourceCommit

Build verification: two clean Java 25 full builds each passed 10,794 tests (0 failures/errors, 88 skipped); all nine JARs matched byte-for-byte. See evidence/rc5-player-session-registration-readiness-20261029.md.

Open release gates:
- RC5-01 chest integrity remains P0 TESTING. Isolated no-holder/stale-owner chest fixtures pass on the earlier packaged pair; the live chest incident remains unattributed and no recovery is established.
- RC5-02 InvSee++ cross-owner item conservation remains P0 BLOCKED. Remote editing remains fail-closed; no functional transaction fix is included.
- The paired session-registration ACK runtime used a diagnostic Paperclip, not this package's normal Paperclip. No equivalent runtime claim is made for the packaged pair.
- The latest referenced Crafty1 log bytes were unavailable for fresh hashing; see the tracker and evidence/rc5-crafty1-upload-evidence-recheck-20261028.md.
- Actual target Crafty monitoring overhead, exact six-component authenticated Java/Bedrock login, old-client movement, Playit routes and intended-hardware capacity remain pending as documented in the compatibility matrix.

This archive is not a release candidate for installation. Do not mix it with RC4, distribute it, deploy it, or advertise it as stable. Use docs/RC5_MANUAL_UPDATE_PLAN.md only after all gates pass and the operator schedules maintenance. No live service or world data is included.
"@
Set-Content -LiteralPath (Join-Path $payload 'README-BLOCKED.txt') -Value $readme -Encoding utf8

$manifestPath = Join-Path $payload 'SHA256SUMS.txt'
$manifestSidecar = "$manifestPath.sha256"
$manifest = Get-ChildItem -LiteralPath $payload -Recurse -File |
    Where-Object { $_.FullName -ne $manifestPath -and $_.FullName -ne $manifestSidecar } |
    Sort-Object { $_.FullName.Substring($payload.Length + 1).Replace('\', '/') } |
    ForEach-Object {
        $relative = $_.FullName.Substring($payload.Length + 1).Replace('\', '/')
        '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
    }
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding ascii
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $manifestSidecar -Value ("{0}  SHA256SUMS.txt" -f $manifestHash) -Encoding ascii

[System.IO.Compression.ZipFile]::CreateFromDirectory($payload, $output, [System.IO.Compression.CompressionLevel]::Optimal, $false)
$archiveHash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$output.sha256" -Value ("{0}  {1}" -f $archiveHash, (Split-Path -Leaf $output)) -Encoding ascii

[pscustomobject]@{
    Archive = $output
    Bytes = (Get-Item -LiteralPath $output).Length
    SHA256 = $archiveHash
    ManifestSHA256 = $manifestHash
    PayloadFiles = $manifest.Count
    ProductSourceCommit = $ProductSourceCommit
}
