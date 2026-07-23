[CmdletBinding()]
param(
    [string]$ClientArchive,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$InstallerRoot = Join-Path $PSScriptRoot 'tlauncher-installer'
$PackConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') -Raw -Encoding UTF8 |
    ConvertFrom-Json

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

function Find-Java17 {
    $candidates = @()
    $adoptiumRoot = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path -LiteralPath $adoptiumRoot) {
        $candidates += Get-ChildItem -LiteralPath $adoptiumRoot -Directory |
            Where-Object Name -Like 'jdk-17*' |
            Sort-Object Name -Descending |
            Select-Object -ExpandProperty FullName
    }
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }
    foreach ($candidate in $candidates) {
        $javac = Join-Path $candidate 'bin\javac.exe'
        if (Test-Path -LiteralPath $javac -PathType Leaf) {
            return $candidate
        }
    }
    throw 'JDK 17 was not found.'
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
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
    $fixedTime = [DateTimeOffset]::new(
        1980,
        1,
        1,
        0,
        0,
        0,
        [TimeSpan]::Zero
    )
    $files = @(
        Get-ChildItem -LiteralPath $SourceDirectory -Recurse -File |
            ForEach-Object {
                $relative = $_.FullName.Substring($SourceDirectory.Length + 1).Replace('\', '/')
                [pscustomobject]@{
                    File = $_
                    Relative = $relative
                    Priority = if ($ManifestFirst -and $relative -eq 'META-INF/MANIFEST.MF') {
                        0
                    }
                    else {
                        1
                    }
                }
            } |
            Sort-Object Priority, Relative
    )

    $outputStream = [IO.File]::Open($OutputPath, [IO.FileMode]::CreateNew)
    try {
        $archive = New-Object IO.Compression.ZipArchive(
            $outputStream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            foreach ($row in $files) {
                $entry = $archive.CreateEntry(
                    $row.Relative,
                    [IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = $fixedTime
                $input = [IO.File]::OpenRead($row.File.FullName)
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

Assert-File $ClientArchive 'verified client release'
Assert-File "$ClientArchive.sha256" 'client release checksum'
$expectedClientHash = ((Get-Content -LiteralPath "$ClientArchive.sha256" -Raw).Trim() -split '\s+')[0]
$actualClientHash = (Get-FileHash -LiteralPath $ClientArchive -Algorithm SHA256).Hash
if ($expectedClientHash -ne $actualClientHash) {
    throw "Client release SHA-256 mismatch: $actualClientHash"
}

$downloadsPath = Join-Path $InstallerRoot 'downloads.tsv'
$sourcePath = Join-Path $InstallerRoot 'src\GtoTLauncherInstaller.java'
$batchPath = Join-Path $InstallerRoot 'INSTALL-GTO-TLAUNCHER.bat'
$readmePath = Join-Path $InstallerRoot 'README-TLAUNCHER.txt'
$clientLockPath = Join-Path $PSScriptRoot 'CLIENT-MOD-LOCK.json'
foreach ($required in @(
    $downloadsPath,
    $sourcePath,
    $batchPath,
    $readmePath,
    $clientLockPath
)) {
    Assert-File $required 'installer source'
}

$staging = Join-Path $OutputDirectory ('.tlauncher-staging-' + [Guid]::NewGuid().ToString('N'))
$expandedClient = Join-Path $staging 'client'
$classes = Join-Path $staging 'classes'
$jarRoot = Join-Path $staging 'jar'
$payload = Join-Path $jarRoot 'payload'
$packageRoot = Join-Path $staging 'package'
New-Item -ItemType Directory -Path $expandedClient, $classes, $jarRoot, $payload, $packageRoot -Force |
    Out-Null

try {
    Write-Host 'Expanding verified client release...'
    Expand-Archive -LiteralPath $ClientArchive -DestinationPath $expandedClient -Force
    $overrides = Join-Path $expandedClient 'overrides'
    if (-not (Test-Path -LiteralPath $overrides -PathType Container)) {
        throw 'Client release does not contain overrides/.'
    }
    Copy-Item -Path (Join-Path $overrides '*') -Destination $payload -Recurse -Force

    foreach ($prismOnly in @(
        'README-FIRST.md',
        'INSTALL-AI.json',
        'CLIENT-MOD-LOCK.json',
        'VERIFY-CLIENT.ps1'
    )) {
        $path = Join-Path $payload $prismOnly
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    Copy-Item -LiteralPath $readmePath -Destination (Join-Path $payload 'README-TLAUNCHER.txt') -Force

    $lock = Get-Content -LiteralPath $clientLockPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($lock.schema -ne 'gto-friends-client-lock/v1' -or $lock.files.Count -ne 178) {
        throw 'Unexpected client lock.'
    }
    $omittedDisabledThemes = @(
        'mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled',
        'mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled'
    )
    $tlauncherLockFiles = @(
        $lock.files | Where-Object path -NotIn $omittedDisabledThemes
    )
    $tlauncherLockFiles += [pscustomobject]@{
        path = 'shaderpacks/ComplementaryReimagined_r5.6.1.zip'
        size = 500696
        sha256 = '33153747D25FBEE470ACB42CCF4F26B03CC551BB900D4C6D3389B5500DD84839'
    }
    if ($tlauncherLockFiles.Count -ne 177) {
        throw "Unexpected TLauncher client lock size: $($tlauncherLockFiles.Count)"
    }

    $clientLockTsv = @('path' + "`t" + 'size' + "`t" + 'sha256')
    $clientLockTsv += @(
        $tlauncherLockFiles |
            Sort-Object path |
            ForEach-Object {
                "$($_.path)`t$($_.size)`t$($_.sha256)"
            }
    )
    Write-Utf8NoBom (Join-Path $jarRoot 'client-lock.tsv') (($clientLockTsv -join "`n") + "`n")
    Copy-Item -LiteralPath $downloadsPath -Destination (Join-Path $jarRoot 'downloads.tsv') -Force

    $javaHome = Find-Java17
    $javac = Join-Path $javaHome 'bin\javac.exe'
    Write-Host 'Compiling transparent Java installer...'
    & $javac --release 8 -encoding UTF-8 -d $classes $sourcePath
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }
    Copy-Item -Path (Join-Path $classes '*') -Destination $jarRoot -Recurse -Force

    $metaInf = Join-Path $jarRoot 'META-INF'
    New-Item -ItemType Directory -Path $metaInf -Force | Out-Null
    $manifest = @(
        'Manifest-Version: 1.0',
        'Main-Class: GtoTLauncherInstaller',
        'Implementation-Title: GTO Friends TLauncher Installer',
        "Implementation-Version: $($PackConfig.packageVersion)",
        'Implementation-Vendor: ChiefSparrow',
        ''
    ) -join "`r`n"
    Write-Utf8NoBom (Join-Path $metaInf 'MANIFEST.MF') $manifest

    $jarOutput = Join-Path $packageRoot 'GTO-TLauncher-Installer.jar'
    Write-Host 'Packing deterministic installer JAR...'
    New-DeterministicZip -SourceDirectory $jarRoot -OutputPath $jarOutput -ManifestFirst

    Copy-Item -LiteralPath $batchPath -Destination (Join-Path $packageRoot 'INSTALL-GTO-TLAUNCHER.bat')
    Copy-Item -LiteralPath $readmePath -Destination (Join-Path $packageRoot 'README-TLAUNCHER.txt')
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'THIRD-PARTY.md') -Destination $packageRoot

    $sourceDirectory = Join-Path $packageRoot 'SOURCE'
    New-Item -ItemType Directory -Path $sourceDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourcePath -Destination $sourceDirectory
    Copy-Item -LiteralPath $downloadsPath -Destination $sourceDirectory
    Copy-Item -LiteralPath (Join-Path $InstallerRoot 'generate_download_manifest.py') -Destination $sourceDirectory

    $jarHash = (Get-FileHash -LiteralPath $jarOutput -Algorithm SHA256).Hash
    $downloadsHash = (Get-FileHash -LiteralPath $downloadsPath -Algorithm SHA256).Hash
    $checksums = @(
        "$jarHash  GTO-TLauncher-Installer.jar",
        "$downloadsHash  SOURCE/downloads.tsv"
    ) -join "`n"
    Write-Utf8NoBom (Join-Path $packageRoot 'SHA256SUMS.txt') ($checksums + "`n")

    $outputName = "GTO-Friends-TLauncher-Installer-v$($PackConfig.packageVersion).zip"
    $outputPath = Join-Path $OutputDirectory $outputName
    Write-Host "Packing $outputName..."
    New-DeterministicZip -SourceDirectory $packageRoot -OutputPath $outputPath

    $outputHash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
    Write-Utf8NoBom "$outputPath.sha256" "$outputHash  $outputName`n"
    Write-Host "Built: $outputPath"
    Write-Host "SHA-256: $outputHash"
}
finally {
    if (Test-Path -LiteralPath $staging) {
        $resolvedStaging = (Resolve-Path -LiteralPath $staging).Path
        if (-not $resolvedStaging.StartsWith($OutputDirectory, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unsafe staging path: $resolvedStaging"
        }
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}
