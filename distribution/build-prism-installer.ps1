[CmdletBinding()]
param(
    [string]$ClientArchive,
    [string]$TemurinArchive,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$InstallerRoot = Join-Path $PSScriptRoot 'prism-installer'
$PackConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') -Raw -Encoding UTF8 |
    ConvertFrom-Json

$TemurinFileName = 'OpenJDK21U-jre_x64_windows_hotspot_21.0.11_10.zip'
$TemurinUrl =
    'https://github.com/adoptium/temurin21-binaries/releases/download/' +
    'jdk-21.0.11%2B10/OpenJDK21U-jre_x64_windows_hotspot_21.0.11_10.zip'
$TemurinSize = 49005708L
$TemurinSha256 = 'BE26677AAA20B39A62EDCAAB4C8857A8B76673B0F45ABC0B6143B142B62717E4'
$TemurinLockSha256 = 'E90F43E00F4A366209F5B715B625BF823402B00B469CCFB65BA9CF42819A3606'
$TemurinRoot = 'jdk-21.0.11+10-jre'
$TemurinFileCount = 315
$PrismUrl =
    'https://github.com/PrismLauncher/PrismLauncher/releases/download/11.0.3/' +
    'PrismLauncher-Windows-MinGW-w64-Portable-11.0.3.zip'
$PrismSize = 43902886L
$PrismSha256 = '7E27AEDD92EABB0699792B5F6305DB6635290D83652CBD73742C70350E42B7F8'
$ExpectedClientArchiveSize = 69961102L
$ExpectedClientArchiveSha256 =
    '8E72B022E52D64D5B5DF08F7A282F9A2F1A9CB239825F2E7FA7A3E2DB0599571'

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $RepoRoot 'dist'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

if (-not $ClientArchive) {
    $ClientArchive = Join-Path $OutputDirectory (
        "GregTech-Odyssey-Friends-0.5.6-v$($PackConfig.packageVersion).zip"
    )
}

function Assert-File {
    param([string]$Path, [string]$Purpose)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing $Purpose file: $Path"
    }
}

function Assert-FileIdentity {
    param(
        [string]$Path,
        [long]$ExpectedSize,
        [string]$ExpectedSha256,
        [string]$Purpose
    )

    Assert-File $Path $Purpose
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -ne $ExpectedSize) {
        throw "$Purpose size mismatch. Expected $ExpectedSize, got $($file.Length): $Path"
    }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -cne $ExpectedSha256) {
        throw "$Purpose SHA-256 mismatch. Expected $ExpectedSha256, got $actualHash"
    }
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)

    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Write-AsciiBatch {
    param([string]$SourcePath, [string]$DestinationPath)

    $text = [IO.File]::ReadAllText($SourcePath, [Text.Encoding]::UTF8)
    if ($text -match '[^\x09\x0A\x0D\x20-\x7E]') {
        throw "Launcher BAT must be ASCII-only: $SourcePath"
    }
    $text = $text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd("`n")
    $text = $text.Replace("`n", "`r`n") + "`r`n"
    [IO.File]::WriteAllText($DestinationPath, $text, [Text.Encoding]::ASCII)
}

function Get-OrdinalRelativeFiles {
    param([string]$Root)

    [string[]]$paths = @(
        Get-ChildItem -LiteralPath $Root -Recurse -File |
            ForEach-Object {
                $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
            }
    )
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    return $paths
}

function New-DeterministicZip {
    param(
        [string]$SourceDirectory,
        [string]$OutputPath,
        [switch]$ManifestFirst
    )

    if (Test-Path -LiteralPath $OutputPath) {
        Remove-Item -LiteralPath $OutputPath -Force
    }

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fixedTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    [string[]]$relativePaths = Get-OrdinalRelativeFiles $SourceDirectory
    if ($ManifestFirst -and $relativePaths -contains 'META-INF/MANIFEST.MF') {
        $relativePaths = @(
            'META-INF/MANIFEST.MF'
            $relativePaths | Where-Object { $_ -cne 'META-INF/MANIFEST.MF' }
        )
    }

    $outputStream = [IO.File]::Open($OutputPath, [IO.FileMode]::CreateNew)
    try {
        $archive = New-Object IO.Compression.ZipArchive(
            $outputStream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            foreach ($relative in $relativePaths) {
                $entry = $archive.CreateEntry(
                    $relative,
                    [IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = $fixedTime
                $sourcePath = Join-Path $SourceDirectory ($relative.Replace('/', '\'))
                $input = [IO.File]::OpenRead($sourcePath)
                $output = $entry.Open()
                try {
                    $input.CopyTo($output)
                }
                finally {
                    $output.Dispose()
                    $input.Dispose()
                }
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $outputStream.Dispose()
    }
}

function Find-Javac {
    $candidates = New-Object 'Collections.Generic.List[string]'
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\javac.exe'))
    }
    foreach ($root in @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java')
    )) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            foreach ($directory in Get-ChildItem -LiteralPath $root -Directory |
                Where-Object Name -Like 'jdk-*' |
                Sort-Object Name -Descending) {
                $candidates.Add((Join-Path $directory.FullName 'bin\javac.exe'))
            }
        }
    }
    $pathJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $candidates.Add($pathJavac.Source)
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            continue
        }
        $version = (Get-Item -LiteralPath $candidate).VersionInfo.ProductVersion
        if ($version -match '^(?:1\.)?(?<major>\d+)') {
            $major = [int]$Matches.major
            if ($major -ge 17) {
                return $candidate
            }
        }
    }
    throw 'JDK 17 or newer was not found. Install an Eclipse Temurin JDK and rerun.'
}

function Save-PinnedFile {
    param([string]$Url, [string]$Destination)

    Write-Host "Downloading pinned dependency: $Url"
    $previousProgress = $ProgressPreference
    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
    }
    finally {
        $ProgressPreference = $previousProgress
    }
}

function Expand-PinnedTemurin {
    param([string]$ArchivePath, [string]$Destination)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $destinationRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $fileCount = 0
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if (
                [string]::IsNullOrWhiteSpace($name) -or
                $name.Contains('\') -or
                $name.StartsWith('/') -or
                $name.Contains([char]0) -or
                $name -notlike "$TemurinRoot/*"
            ) {
                throw "Unsafe or unexpected Temurin ZIP path: $name"
            }

            $relative = $name.Substring($TemurinRoot.Length + 1)
            if (-not $relative -or $name.EndsWith('/')) {
                continue
            }
            $segments = $relative.Split('/')
            if (
                $segments -contains '' -or
                $segments -contains '.' -or
                $segments -contains '..' -or
                $relative.Contains(':')
            ) {
                throw "Unsafe Temurin ZIP path: $name"
            }
            $unixType = (($entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($unixType -eq 0xA000) {
                throw "Temurin ZIP contains a symbolic link: $name"
            }

            $outputPath = [IO.Path]::GetFullPath(
                (Join-Path $Destination ($relative.Replace('/', '\')))
            )
            if (-not $outputPath.StartsWith(
                $destinationRoot,
                [StringComparison]::OrdinalIgnoreCase
            )) {
                throw "Temurin ZIP path escapes destination: $name"
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force |
                Out-Null
            $input = $entry.Open()
            $output = [IO.File]::Open($outputPath, [IO.FileMode]::CreateNew)
            try {
                $input.CopyTo($output)
            }
            finally {
                $output.Dispose()
                $input.Dispose()
            }
            $fileCount++
        }
        if ($fileCount -ne $TemurinFileCount) {
            throw "Expected $TemurinFileCount Temurin files, extracted $fileCount."
        }
    }
    finally {
        $zip.Dispose()
    }
}

function New-FileLockTsv {
    param([string]$Root, [string]$OutputPath)

    $rows = New-Object 'Collections.Generic.List[string]'
    $rows.Add("path`tsize`tsha256")
    foreach ($relative in Get-OrdinalRelativeFiles $Root) {
        $path = Join-Path $Root ($relative.Replace('/', '\'))
        $file = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        $rows.Add("$relative`t$($file.Length)`t$hash")
    }
    Write-Utf8NoBom $OutputPath (($rows -join "`n") + "`n")
}

Assert-File $ClientArchive 'verified client release'
Assert-File "$ClientArchive.sha256" 'client release checksum'
$expectedClientHash = ((Get-Content -LiteralPath "$ClientArchive.sha256" -Raw).Trim() -split '\s+')[0]
$actualClientHash = (Get-FileHash -LiteralPath $ClientArchive -Algorithm SHA256).Hash
if ($expectedClientHash -cne $actualClientHash) {
    throw "Client release SHA-256 mismatch: $actualClientHash"
}
if (
    $PackConfig.packageVersion -ne '1.0.4' -or
    (Get-Item -LiteralPath $ClientArchive).Length -ne $ExpectedClientArchiveSize -or
    $actualClientHash -cne $ExpectedClientArchiveSha256
) {
    throw (
        'Client release is not the independently pinned Friends Edition 1.0.4 ' +
        "archive: $ClientArchive"
    )
}

$downloadsPath = Join-Path $PSScriptRoot 'tlauncher-installer\downloads.tsv'
$sourcePath = Join-Path $InstallerRoot 'src\GtoPrismInstaller.java'
$installBatchPath = Join-Path $InstallerRoot 'INSTALL-GTO-LICENSED.bat'
$playBatchPath = Join-Path $InstallerRoot 'PLAY-GTO-LICENSED.bat'
$readmePath = Join-Path $InstallerRoot 'README-LICENSED.txt'
$prismLockPath = Join-Path $InstallerRoot 'prism-lock.tsv'
$clientLockPath = Join-Path $PSScriptRoot 'CLIENT-MOD-LOCK.json'
foreach ($required in @(
    $downloadsPath,
    $sourcePath,
    $installBatchPath,
    $playBatchPath,
    $readmePath,
    $prismLockPath,
    $clientLockPath,
    (Join-Path $RepoRoot 'THIRD-PARTY.md')
)) {
    Assert-File $required 'installer source'
}

$staging = Join-Path $OutputDirectory ('.prism-staging-' + [Guid]::NewGuid().ToString('N'))
$expandedClient = Join-Path $staging 'client'
$classes = Join-Path $staging 'classes'
$jarRoot = Join-Path $staging 'jar'
$payload = Join-Path $jarRoot 'payload'
$packageRoot = Join-Path $staging 'package'
$bootstrapJava = Join-Path $packageRoot 'BOOTSTRAP-JAVA'
$sourceDirectory = Join-Path $packageRoot 'SOURCE'
$downloadedTemurin = Join-Path $staging $TemurinFileName
$pendingOutput = $null
$pendingChecksum = $null
$outputBackup = $null
New-Item -ItemType Directory -Path (
    $expandedClient,
    $classes,
    $jarRoot,
    $payload,
    $packageRoot,
    $sourceDirectory
) -Force | Out-Null

try {
    if (-not $TemurinArchive) {
        $temurinCandidates = @(
            (Join-Path $env:TEMP $TemurinFileName),
            (Join-Path $OutputDirectory $TemurinFileName),
            (Join-Path $env:USERPROFILE "Downloads\$TemurinFileName")
        )
        $TemurinArchive = $temurinCandidates |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if (-not $TemurinArchive) {
            Save-PinnedFile $TemurinUrl $downloadedTemurin
            $TemurinArchive = $downloadedTemurin
        }
    }
    Assert-FileIdentity $TemurinArchive $TemurinSize $TemurinSha256 'Temurin 21 runtime'

    Write-Host 'Expanding pinned Temurin 21 bootstrap runtime...'
    Expand-PinnedTemurin $TemurinArchive $bootstrapJava
    $releaseText = Get-Content -LiteralPath (Join-Path $bootstrapJava 'release') -Raw
    if (
        $releaseText -notmatch '(?m)^JAVA_VERSION="21\.0\.11"\r?$' -or
        $releaseText -notmatch '(?m)^OS_ARCH="x86_64"\r?$' -or
        $releaseText -notmatch '(?m)^OS_NAME="Windows"\r?$' -or
        $releaseText -notmatch '(?m)^IMAGE_TYPE="JRE"\r?$'
    ) {
        throw 'Pinned Temurin runtime is not the expected Java 21 Windows x64 JRE.'
    }
    $javawPath = Join-Path $bootstrapJava 'bin\javaw.exe'
    $javaSignature = Get-AuthenticodeSignature -LiteralPath $javawPath
    if (
        $javaSignature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
        -not $javaSignature.SignerCertificate -or
        $javaSignature.SignerCertificate.Subject -notmatch 'Eclipse\.org Foundation'
    ) {
        throw 'Pinned Temurin javaw.exe does not have the expected valid signature.'
    }
    $javaRuntimeLockPath = Join-Path $staging 'bootstrap-java-lock.tsv'
    New-FileLockTsv $bootstrapJava $javaRuntimeLockPath
    $javaRuntimeLockHash = (
        Get-FileHash -LiteralPath $javaRuntimeLockPath -Algorithm SHA256
    ).Hash
    if ($javaRuntimeLockHash -cne $TemurinLockSha256) {
        throw (
            'Generated bootstrap Java lock differs from the pinned canonical lock. ' +
            "Expected $TemurinLockSha256, got $javaRuntimeLockHash"
        )
    }
    $javaRuntimeRows = @(
        Get-Content -LiteralPath $javaRuntimeLockPath -Raw -Encoding UTF8 |
            ConvertFrom-Csv -Delimiter "`t"
    )
    if ($javaRuntimeRows.Count -ne $TemurinFileCount) {
        throw "Unexpected Java runtime lock size: $($javaRuntimeRows.Count)"
    }

    Write-Host 'Expanding verified client release...'
    Expand-Archive -LiteralPath $ClientArchive -DestinationPath $expandedClient -Force
    $overrides = Join-Path $expandedClient 'overrides'
    if (-not (Test-Path -LiteralPath $overrides -PathType Container)) {
        throw 'Client release does not contain overrides/.'
    }
    Copy-Item -Path (Join-Path $overrides '*') -Destination $payload -Recurse -Force
    foreach ($packagingOnly in @(
        'README-FIRST.md',
        'INSTALL-AI.json',
        'CLIENT-MOD-LOCK.json',
        'VERIFY-CLIENT.ps1'
    )) {
        $path = Join-Path $payload $packagingOnly
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }

    $lock = Get-Content -LiteralPath $clientLockPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($lock.schema -ne 'gto-friends-client-lock/v1' -or $lock.files.Count -ne 178) {
        throw 'Unexpected client lock.'
    }
    $omittedDisabledThemes = @(
        'mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled',
        'mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled'
    )
    $installedLockFiles = @(
        $lock.files | Where-Object path -NotIn $omittedDisabledThemes
    )
    $installedLockFiles += [pscustomobject]@{
        path = 'shaderpacks/ComplementaryReimagined_r5.6.1.zip'
        size = 500696
        sha256 = '33153747D25FBEE470ACB42CCF4F26B03CC551BB900D4C6D3389B5500DD84839'
    }
    if ($installedLockFiles.Count -ne 177) {
        throw "Unexpected installed client lock size: $($installedLockFiles.Count)"
    }
    $clientLockTsv = @("path`tsize`tsha256")
    [string[]]$installedPaths = @($installedLockFiles | ForEach-Object path)
    [Array]::Sort($installedPaths, [StringComparer]::Ordinal)
    foreach ($installedPath in $installedPaths) {
        $installedEntry = @(
            $installedLockFiles | Where-Object path -CEQ $installedPath
        )
        if ($installedEntry.Count -ne 1) {
            throw "Duplicate installed client lock path: $installedPath"
        }
        $clientLockTsv += (
            "$($installedEntry[0].path)`t" +
            "$($installedEntry[0].size)`t" +
            "$($installedEntry[0].sha256)"
        )
    }
    Write-Utf8NoBom (Join-Path $jarRoot 'client-lock.tsv') (($clientLockTsv -join "`n") + "`n")

    Copy-Item -LiteralPath $downloadsPath -Destination (Join-Path $jarRoot 'downloads.tsv')
    Copy-Item -LiteralPath $prismLockPath -Destination (Join-Path $jarRoot 'prism-lock.tsv')
    Copy-Item -LiteralPath $javaRuntimeLockPath -Destination (
        Join-Path $jarRoot 'bootstrap-java-lock.tsv'
    )
    Write-AsciiBatch $playBatchPath (Join-Path $jarRoot 'PLAY-GTO-LICENSED.bat')
    Copy-Item -LiteralPath $readmePath -Destination (Join-Path $jarRoot 'README-LICENSED.txt')

    $javac = Find-Javac
    Write-Host 'Compiling transparent licensed Prism installer...'
    & $javac --release 8 -encoding UTF-8 -d $classes $sourcePath
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }
    Copy-Item -Path (Join-Path $classes '*') -Destination $jarRoot -Recurse -Force

    $metaInf = Join-Path $jarRoot 'META-INF'
    New-Item -ItemType Directory -Path $metaInf -Force | Out-Null
    $manifest = @(
        'Manifest-Version: 1.0',
        'Main-Class: GtoPrismInstaller',
        'Implementation-Title: GTO Friends Licensed Prism Installer',
        "Implementation-Version: $($PackConfig.packageVersion)",
        'Implementation-Vendor: ChiefSparrow',
        ''
    ) -join "`r`n"
    Write-Utf8NoBom (Join-Path $metaInf 'MANIFEST.MF') $manifest

    $jarOutput = Join-Path $packageRoot 'GTO-Licensed-Prism-Installer.jar'
    Write-Host 'Packing deterministic installer JAR...'
    New-DeterministicZip -SourceDirectory $jarRoot -OutputPath $jarOutput -ManifestFirst

    Write-AsciiBatch $installBatchPath (Join-Path $packageRoot 'INSTALL-GTO-LICENSED.bat')
    Copy-Item -LiteralPath $readmePath -Destination (Join-Path $packageRoot 'README-LICENSED.txt')
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'THIRD-PARTY.md') -Destination $packageRoot

    Copy-Item -LiteralPath $sourcePath -Destination $sourceDirectory
    Copy-Item -LiteralPath $downloadsPath -Destination $sourceDirectory
    Copy-Item -LiteralPath (Join-Path $jarRoot 'client-lock.tsv') -Destination $sourceDirectory
    Copy-Item -LiteralPath $prismLockPath -Destination $sourceDirectory
    Copy-Item -LiteralPath $javaRuntimeLockPath -Destination $sourceDirectory
    Copy-Item -LiteralPath $PSCommandPath -Destination $sourceDirectory
    $provenance = @(
        'schema=gto-friends-licensed-installer-provenance/v1',
        "package-version=$($PackConfig.packageVersion)",
        "client-archive=$(Split-Path -Leaf $ClientArchive)",
        "client-archive-size=$((Get-Item -LiteralPath $ClientArchive).Length)",
        "client-archive-sha256=$actualClientHash",
        'prism-version=11.0.3',
        "prism-url=$PrismUrl",
        "prism-size=$PrismSize",
        "prism-sha256=$PrismSha256",
        'temurin-version=21.0.11+10',
        "temurin-url=$TemurinUrl",
        "temurin-archive-size=$TemurinSize",
        "temurin-archive-sha256=$TemurinSha256",
        "temurin-file-count=$TemurinFileCount",
        "temurin-lock-sha256=$TemurinLockSha256"
    )
    Write-Utf8NoBom (Join-Path $sourceDirectory 'PROVENANCE.txt') (
        ($provenance -join "`n") + "`n"
    )

    $checksumRows = New-Object 'Collections.Generic.List[string]'
    foreach ($relative in Get-OrdinalRelativeFiles $packageRoot) {
        if ($relative -ceq 'SHA256SUMS.txt') {
            continue
        }
        $path = Join-Path $packageRoot ($relative.Replace('/', '\'))
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        $checksumRows.Add("$hash  $relative")
    }
    Write-Utf8NoBom (Join-Path $packageRoot 'SHA256SUMS.txt') (
        ($checksumRows -join "`n") + "`n"
    )

    $outputName = "GTO-Friends-Licensed-Prism-Installer-v$($PackConfig.packageVersion).zip"
    $outputPath = Join-Path $OutputDirectory $outputName
    $pendingOutput = Join-Path $OutputDirectory (
        ".$outputName.pending-" + [Guid]::NewGuid().ToString('N')
    )
    $pendingChecksum = "$pendingOutput.sha256"
    $outputBackup = Join-Path $OutputDirectory (
        ".$outputName.previous-" + [Guid]::NewGuid().ToString('N')
    )
    Write-Host "Packing $outputName..."
    New-DeterministicZip -SourceDirectory $packageRoot -OutputPath $pendingOutput

    $outputHash = (Get-FileHash -LiteralPath $pendingOutput -Algorithm SHA256).Hash
    Write-Utf8NoBom $pendingChecksum "$outputHash  $outputName`n"

    $finalChecksum = "$outputPath.sha256"
    if (Test-Path -LiteralPath $finalChecksum -PathType Leaf) {
        Remove-Item -LiteralPath $finalChecksum -Force
    }
    if (Test-Path -LiteralPath $outputPath -PathType Leaf) {
        [IO.File]::Replace($pendingOutput, $outputPath, $outputBackup, $true)
        Remove-Item -LiteralPath $outputBackup -Force
    }
    else {
        [IO.File]::Move($pendingOutput, $outputPath)
    }
    [IO.File]::Move($pendingChecksum, $finalChecksum)
    Write-Host "Built: $outputPath"
    Write-Host "SHA-256: $outputHash"
}
finally {
    foreach ($temporaryOutput in @(
        $pendingOutput,
        $pendingChecksum,
        $outputBackup
    )) {
        if (
            $temporaryOutput -and
            (Test-Path -LiteralPath $temporaryOutput -PathType Leaf)
        ) {
            $resolvedTemporary = (Resolve-Path -LiteralPath $temporaryOutput).Path
            $outputRoot = $OutputDirectory.TrimEnd('\') + '\'
            if (-not $resolvedTemporary.StartsWith(
                $outputRoot,
                [StringComparison]::OrdinalIgnoreCase
            )) {
                throw "Refusing to remove unsafe temporary output: $resolvedTemporary"
            }
            Remove-Item -LiteralPath $resolvedTemporary -Force
        }
    }
    if (Test-Path -LiteralPath $staging) {
        $resolvedStaging = (Resolve-Path -LiteralPath $staging).Path
        $outputRoot = $OutputDirectory.TrimEnd('\') + '\'
        if (-not ($resolvedStaging + '\').StartsWith(
            $outputRoot,
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing to remove unsafe staging path: $resolvedStaging"
        }
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}
