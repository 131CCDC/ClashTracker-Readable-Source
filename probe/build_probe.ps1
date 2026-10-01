param(
    [ValidateSet('Stable', 'Experimental')][string]$Profile = 'Stable',
    [string]$NdkRoot = $env:ANDROID_NDK_HOME,
    # 1 (default) builds the probe with the battle-log trace hooks compiled in.
    # 0 builds the baseline the trace build is measured against; the two differ
    # only by this flag, so an A/B performance comparison is honest.
    [ValidateSet(0, 1)][int]$Trace = 1,
    [string]$OutputName = 'libscid_sdk.so'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'probe_artifacts.ps1')

if (!$NdkRoot) { throw 'Supply -NdkRoot or ANDROID_NDK_HOME (tested with NDK r27c).' }
$ndkClang = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang++.cmd'
if (!(Test-Path -LiteralPath $ndkClang)) { throw "Compiler missing: $ndkClang" }
$origins = if ($Profile -eq 'Experimental') { 1 } else { 0 }
$artifactProfile = if ($origins) { 'experimental' } else { 'stable-candidate' }
$outputDir = Join-Path $PSScriptRoot "artifacts/candidates/$artifactProfile"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$output = Join-Path $outputDir $OutputName
$sourceHashes = @(Get-ProbeSourceHashes)
$compilerArgs = @(
    '-std=c++17', '-O2', '-fPIC', '-fvisibility=hidden', '-shared',
    '-Wl,-z,max-page-size=16384', "-DCR_EXPERIMENTAL_PRODUCER_ORIGINS=$origins",
    "-DCR_BATTLELOG_TRACE=$Trace",
    '-o', $output,
    (Join-Path $PSScriptRoot 'nulls_probe.cpp'),
    (Join-Path $PSScriptRoot 'card_selection_arm64.S'),
    (Join-Path $PSScriptRoot 'spawn_relations_arm64.S'),
    (Join-Path $PSScriptRoot 'attack_start_arm64.S'),
    (Join-Path $PSScriptRoot 'heal_events_arm64.S'), '-llog', '-ldl'
)
& $ndkClang @compilerArgs
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
if (($sourceHashes | ConvertTo-Json -Depth 4 -Compress) -ne
    (@(Get-ProbeSourceHashes) | ConvertTo-Json -Depth 4 -Compress)) {
    throw 'Probe sources changed during compilation; candidate is not deployable. Rebuild it.'
}
$manifest = [ordered]@{
    schema = 'nulls-probe-build.v1'
    profile = $artifactProfile
    validation = 'compiled_not_live_validated'
    producer_origins = [bool]$origins
    battlelog_trace = [bool]$Trace
    sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    built_utc = [DateTime]::UtcNow.ToString('o')
    compiler = $ndkClang
    compiler_args = $compilerArgs
    source_files = $sourceHashes
}
$manifestName = if ($OutputName -eq 'libscid_sdk.so') { 'build.json' } else { "$OutputName.build.json" }
[IO.File]::WriteAllText((Join-Path $outputDir $manifestName),
    ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "Built $artifactProfile candidate (trace=$Trace): $output"
Write-Output "SHA256 $($manifest.sha256)"
Write-Output 'Compiled only; not live validated. Default deployment still uses the pinned historical stable binary.'
