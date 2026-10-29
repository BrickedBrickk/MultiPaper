param(
    [Parameter(Mandatory = $true)] [string] $PriorArchive,
    [Parameter(Mandatory = $true)] [string] $BuildOutputDirectory,
    [Parameter(Mandatory = $true)] [string] $OutputArchive
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$prior = (Resolve-Path -LiteralPath $PriorArchive).Path
$build = (Resolve-Path -LiteralPath $BuildOutputDirectory).Path
$output = [System.IO.Path]::GetFullPath($OutputArchive)
$payload = "$output.payload"

if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite archive: $output" }
if (Test-Path -LiteralPath $payload) { throw "Refusing to reuse payload directory: $payload" }
if (-not $output.StartsWith((Join-Path $repo 'release-staging'), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output archive must stay under release-staging.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($prior, $payload)

$replacements = @{
    'bin/multipaper-master-3.0.0.jar' = Join-Path $build 'multipaper-master-3.0.0.jar'
    'bin/multipaper-paperclip-26.3-RC5.jar' = Join-Path $build 'multipaper-paperclip-26.3-RC5.jar'
    'docs/RC5_BUG_TRACKER.md' = Join-Path $repo 'docs/RC5_BUG_TRACKER.md'
    'docs/RC5_COMPATIBILITY_MATRIX.md' = Join-Path $repo 'docs/RC5_COMPATIBILITY_MATRIX.md'
    'docs/RC5_MANUAL_UPDATE_PLAN.md' = Join-Path $repo 'docs/RC5_MANUAL_UPDATE_PLAN.md'
    'monitoring/README.md' = Join-Path $repo 'monitoring/README.md'
    'monitoring/BETA-TEST-PLAN.md' = Join-Path $repo 'monitoring/BETA-TEST-PLAN.md'
    'monitoring/MANUAL-CRAFTY-TOPOLOGY.md' = Join-Path $repo 'monitoring/MANUAL-CRAFTY-TOPOLOGY.md'
    'monitoring/NETWORK-ROUTES.md' = Join-Path $repo 'monitoring/NETWORK-ROUTES.md'
    'monitoring/start-monitor-pc1.ps1' = Join-Path $repo 'monitoring/start-monitor-pc1.ps1'
    'scripts/multipaper-beta-monitor.py' = Join-Path $repo 'scripts/multipaper-beta-monitor.py'
}
foreach ($entry in $replacements.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) { throw "Missing replacement input: $($entry.Value)" }
    $destination = Join-Path $payload ($entry.Key.Replace('/', '\'))
    $parent = Split-Path -Parent $destination
    [void](New-Item -ItemType Directory -Force -Path $parent)
    Copy-Item -LiteralPath $entry.Value -Destination $destination
}

$evidence = Join-Path $payload 'evidence'
$oldManifest = Join-Path $evidence 'SOURCE-SHA256.txt'
if (Test-Path -LiteralPath $oldManifest) {
    Move-Item -LiteralPath $oldManifest -Destination (Join-Path $evidence 'SOURCE-SHA256-v23.txt')
}
$reports = @(
    'rc5-ender-chest-revision-fence-20261029.md',
    'rc5-crafty1-upload-recheck-20261029.md',
    'rc5-crafty1-noholder-current-source-recheck-20261029.md',
    'rc5-packaged-pair-chest-race-20261029.md',
    'rc5-session-epoch-master-protocol-rerun-20261029.md',
    'rc5-session-registration-runtime-20261029.md',
    'rc5-session-sender-bytecode-provenance-20261029.md',
    'rc5-official-component-availability-20261029.md'
)
foreach ($report in $reports) {
    $source = Join-Path $repo "performance-results\$report"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing evidence report: $source" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $evidence $report)
}

$provenance = @'
RC5 blocked review package artifact provenance

Branch: feature/port-26.3
Source checkout HEAD at packaging: dd4756dcc7262225e5cd54411eae08274c77c605
The candidate contains working-tree source changes; this is not an immutable clean Git tag.
Build verification: evidence/rc5-ender-chest-revision-fence-20261029.md
Build method: Temurin Java 25.0.3+9 / Gradle 9.8.0; two sequential clean offline builds, no build cache, no daemon, one worker; 10,785 tests each, 0 failures/errors, 88 skipped; all nine JARs byte-identical.
SOURCE-SHA256-v23.txt is historical and describes the earlier v23 Paperclip, not this candidate.

Packaged Master SHA-256: 1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E
Packaged Paperclip SHA-256: 8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692
Release state: BLOCKED / NOT FOR INSTALLATION OR DISTRIBUTION.
'@
Set-Content -LiteralPath (Join-Path $evidence 'BUILD-ARTIFACT-PROVENANCE.txt') -Value $provenance -Encoding utf8

$readmePath = Join-Path $payload 'README-BLOCKED.txt'
$readme = Get-Content -Raw -LiteralPath $readmePath
$readme = $readme.Replace(
    'Paperclip SHA-256: 99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85',
    'Paperclip SHA-256: 8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692')
$readme = $readme.Replace(
    'Build provenance base: 0cab1559ca21d8cc4f43bd8302e7df0215ec3386 plus the exact current-source inputs listed in evidence/rc5-current-source-clean-builds-20261028.md. The source overlay is not a clean committed release tag.',
    'Build provenance: see evidence/BUILD-ARTIFACT-PROVENANCE.txt and evidence/rc5-ender-chest-revision-fence-20261029.md. The older v23 source-input manifest is retained under a distinct historical filename and does not describe these JARs.')
$readme = $readme.Replace(
    'Chest ownership/no-holder regression schedules passed in both A-to-B and B-to-A directions on this exact pair; this is not a diagnosis or recovery of live empty-chest reports.',
    'Chest ownership/no-holder regression schedules passed in both A-to-B and B-to-A directions on the prior Paperclip 99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85; they do not certify the newly packaged Paperclip. These results are not a diagnosis or recovery of live empty-chest reports.')
$readme = $readme.Replace(
    'Chest ownership/no-holder regression schedules passed in both A-to-B and B-to-A directions on the prior Paperclip 99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85; they do not certify the newly packaged Paperclip. These results are not a diagnosis or recovery of live empty-chest reports. Read the evidence files and compatibility matrix for all limits.',
    'The exact packaged Paperclip/Master pair passed the 273-read stale-owner chest regression in both A-to-B and B-to-A directions, retaining exact single/double chest contents through backend and graceful Master restarts. See evidence/rc5-packaged-pair-chest-race-20261029.md. This does not diagnose or recover live empty-chest reports. Read the evidence files and compatibility matrix for all limits.')
$readme = $readme.Replace(
    'Earlier 273-read chest runtime reports used Paperclip 99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85 and do not certify the newly packaged Paperclip. See evidence/rc5-ender-chest-revision-fence-20261029.md.',
    'The exact packaged Paperclip/Master pair subsequently passed 273-read stale-owner chest schedules in both A-to-B and B-to-A directions, with exact single/double chest contents through backend and graceful Master restarts. See evidence/rc5-packaged-pair-chest-race-20261029.md. This does not diagnose or recover live empty-chest reports. The new Ender Chest revision fence still lacks a paired runtime that deliberately reorders updates; see evidence/rc5-ender-chest-revision-fence-20261029.md.')
if ($readme -notmatch 'Latest candidate note \(2026-10-29\)') {
    $readme += @'

Latest candidate note (2026-10-29): this package carries Paperclip 8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692, which adds Ender Chest mirror revision fencing. The focused suite passed 20/20 and two clean Java 25 builds matched; no paired runtime deliberately reordered these updates. Earlier 273-read chest runtime reports used Paperclip 99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85 and do not certify the newly packaged Paperclip. See evidence/rc5-ender-chest-revision-fence-20261029.md. The newly referenced Crafty1 attachment was unavailable for fresh hashing; see evidence/rc5-crafty1-upload-recheck-20261029.md. The package remains blocked and must not be installed or distributed.
'@
}
Set-Content -LiteralPath $readmePath -Value $readme -Encoding utf8

$manifestPath = Join-Path $payload 'SHA256SUMS.txt'
$manifestSidecar = "$manifestPath.sha256"
$manifest = Get-ChildItem -LiteralPath $payload -Recurse -File |
    Where-Object { $_.FullName -ne $manifestPath -and $_.FullName -ne $manifestSidecar } |
    Sort-Object { $_.FullName.Substring($payload.Length + 1).Replace('\', '/') } |
    ForEach-Object {
        $relative = $_.FullName.Substring($payload.Length + 1).Replace('\', '/')
        $digest = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        '{0}  {1}' -f $digest, $relative
    }
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding ascii
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $manifestSidecar -Value ("{0}  SHA256SUMS.txt" -f $manifestHash) -Encoding ascii

[System.IO.Compression.ZipFile]::CreateFromDirectory($payload, $output, [System.IO.Compression.CompressionLevel]::Optimal, $false)
$archiveHash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$archiveSidecar = "$output.sha256"
Set-Content -LiteralPath $archiveSidecar -Value ("{0}  {1}" -f $archiveHash, (Split-Path -Leaf $output)) -Encoding ascii

[pscustomobject]@{
    Archive = $output
    Bytes = (Get-Item -LiteralPath $output).Length
    SHA256 = $archiveHash
    ManifestSHA256 = $manifestHash
    PayloadFiles = $manifest.Count
    PayloadDirectory = $payload
}
