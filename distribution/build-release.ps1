[CmdletBinding()]
param(
    [string]$BaseZip = (Join-Path $env:USERPROFILE 'Downloads\GregTech.Odyssey-0.5.6-beta-all-locales.zip'),
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ConfigPath = Join-Path $PSScriptRoot 'pack-config.json'
$Config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $RepoRoot 'dist'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

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
        $java = Join-Path $candidate 'bin\java.exe'
        if (Test-Path -LiteralPath $java -PathType Leaf) {
            $productVersion = (Get-Item -LiteralPath $java).VersionInfo.ProductVersion
            if ($productVersion -match '^17(?:\.|$)') {
                return $candidate
            }
        }
    }
    throw 'JDK 17 was not found. Install Eclipse Temurin 17 JDK and rerun.'
}

function Invoke-GradleBuild {
    param([string]$ProjectDirectory)
    $wrapper = Join-Path $ProjectDirectory 'gradlew.bat'
    Assert-File $wrapper 'Gradle wrapper'
    Push-Location $ProjectDirectory
    try {
        & $wrapper clean build --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed in $ProjectDirectory with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-RelativeArtifactPath {
    param([string]$RelativePath)
    return Join-Path $RepoRoot ($RelativePath -replace '/', '\')
}

Assert-File $BaseZip 'base modpack'
$baseHash = (Get-FileHash -LiteralPath $BaseZip -Algorithm SHA256).Hash
if ($baseHash -ne $Config.base.sha256) {
    throw "Base archive SHA-256 mismatch. Expected $($Config.base.sha256), got $baseHash"
}

$env:JAVA_HOME = Find-Java17
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"

Write-Host 'Building local Forge fixes...'
Invoke-GradleBuild (Join-Path $RepoRoot 'gto-terminal-fix')
Invoke-GradleBuild (Join-Path $RepoRoot 'gto-farming-fix')

Write-Host 'Building Russian resource pack...'
Push-Location (Join-Path $RepoRoot 'gto-russian-fixes')
try {
    & python '.\tools\build_pack.py'
    if ($LASTEXITCODE -ne 0) {
        throw "Russian resource pack build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$artifactRows = @()
foreach ($artifact in $Config.customArtifacts) {
    $source = Get-RelativeArtifactPath $artifact.buildGlob
    Assert-File $source $artifact.name
    $artifactRows += [pscustomobject]@{
        name = $artifact.name
        version = $artifact.version
        archivePath = $artifact.archivePath
        sides = $artifact.sides
        sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        source = $source
    }
}

$staging = Join-Path $OutputDirectory ('.staging-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null

try {
    Write-Host 'Expanding verified base archive...'
    Expand-Archive -LiteralPath $BaseZip -DestinationPath $staging -Force

    $manifestPath = Join-Path $staging 'manifest.json'
    Assert-File $manifestPath 'CurseForge manifest'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

    if ($manifest.name -ne $Config.base.name -or $manifest.version -ne $Config.base.version) {
        throw "Unexpected base manifest identity: $($manifest.name) $($manifest.version)"
    }
    if ($manifest.minecraft.version -ne '1.20.1' -or $manifest.minecraft.modLoaders[0].id -ne 'forge-47.4.20') {
        throw 'Unexpected Minecraft or Forge version in base manifest.'
    }

    $manifest.name = $Config.packageName
    $manifest.version = $Config.manifestVersion
    $manifest.author = $Config.author

    foreach ($projectId in $Config.forceRequiredProjectIds) {
        $entry = @($manifest.files | Where-Object projectID -eq $projectId)
        if ($entry.Count -ne 1) {
            throw "Expected exactly one base manifest entry for project $projectId"
        }
        $entry[0].required = $true
    }

    foreach ($addition in $Config.curseForgeAdditions) {
        $existing = @($manifest.files | Where-Object projectID -eq $addition.projectID)
        if ($existing.Count -gt 1) {
            throw "Duplicate project $($addition.projectID) in base manifest"
        }
        if ($existing.Count -eq 1) {
            if ($existing[0].fileID -ne $addition.fileID) {
                throw "Project $($addition.projectID) already uses unexpected file $($existing[0].fileID)"
            }
            $existing[0].required = [bool]$addition.required
        }
        else {
            $manifest.files += [pscustomobject]@{
                projectID = [int]$addition.projectID
                fileID = [int]$addition.fileID
                required = [bool]$addition.required
            }
        }
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText(
        $manifestPath,
        ($manifest | ConvertTo-Json -Depth 20) + "`n",
        $utf8NoBom
    )

    $modListPath = Join-Path $staging 'modlist.html'
    $modList = Get-Content -LiteralPath $modListPath -Raw -Encoding UTF8
    $extraListItems = @(
        '<li><a href="https://www.curseforge.com/minecraft/mc-mods/treechop">HT''s TreeChop</a></li>',
        '<li><a href="https://www.curseforge.com/minecraft/mc-mods/pandas-falling-trees">Panda''s Falling Trees</a></li>',
        '<li><a href="https://www.curseforge.com/minecraft/shaders/complementary-reimagined">Complementary Reimagined</a></li>',
        '<li><a href="https://www.curseforge.com/minecraft/mc-mods/euphoria-patches">Euphoria Patches</a></li>',
        '<li>GTO Simple Crafting Terminal Fix 1.0.0 (local)</li>',
        '<li>GTO Farming Fix 1.0.1 (local)</li>',
        '<li>GTO Russian Fixes 1.0.0 (local)</li>'
    ) -join "`n"
    if ($modList -notmatch '</ul>') {
        throw 'Base modlist.html does not contain a closing list tag.'
    }
    $modList = $modList -replace '</ul>', "$extraListItems`n</ul>"
    [IO.File]::WriteAllText($modListPath, $modList, $utf8NoBom)

    $stagingOverrides = Join-Path $staging 'overrides'
    New-Item -ItemType Directory -Path $stagingOverrides -Force | Out-Null
    $configuredOverrides = Join-Path $PSScriptRoot 'overrides'
    Copy-Item -Path (Join-Path $configuredOverrides '*') -Destination $stagingOverrides -Recurse -Force

    foreach ($artifact in $artifactRows) {
        $destination = Join-Path $staging ($artifact.archivePath -replace '/', '\')
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $artifact.source -Destination $destination -Force
    }

    $readmeSource = Join-Path $PSScriptRoot 'README-FIRST.md'
    $aiSource = Join-Path $PSScriptRoot 'INSTALL-AI.json'
    $clientLockSource = Join-Path $PSScriptRoot $Config.clientLock.fileName
    $clientVerifierSource = Join-Path $PSScriptRoot $Config.clientLock.verificationScript
    Assert-File $clientLockSource 'client mod lock'
    Assert-File $clientVerifierSource 'client verification script'
    Copy-Item -LiteralPath $readmeSource -Destination (Join-Path $staging 'README-FIRST.md') -Force
    Copy-Item -LiteralPath $aiSource -Destination (Join-Path $staging 'INSTALL-AI.json') -Force
    Copy-Item -LiteralPath $clientLockSource -Destination (Join-Path $staging $Config.clientLock.fileName) -Force
    Copy-Item -LiteralPath $clientVerifierSource -Destination (Join-Path $staging $Config.clientLock.verificationScript) -Force
    Copy-Item -LiteralPath $readmeSource -Destination (Join-Path $stagingOverrides 'README-FIRST.md') -Force
    Copy-Item -LiteralPath $aiSource -Destination (Join-Path $stagingOverrides 'INSTALL-AI.json') -Force
    Copy-Item -LiteralPath $clientLockSource -Destination (Join-Path $stagingOverrides $Config.clientLock.fileName) -Force
    Copy-Item -LiteralPath $clientVerifierSource -Destination (Join-Path $stagingOverrides $Config.clientLock.verificationScript) -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'CHANGELOG.md') -Destination (Join-Path $staging 'CHANGELOG.md') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'THIRD-PARTY.md') -Destination (Join-Path $staging 'THIRD-PARTY.md') -Force

    $releaseManifest = [ordered]@{
        schema = 'gto-friends-release-manifest/v1'
        package = [ordered]@{
            name = $Config.packageName
            version = $Config.packageVersion
            baseVersion = $Config.base.version
            minecraft = '1.20.1'
            forge = '47.4.20'
        }
        baseArchive = [ordered]@{
            fileName = $Config.base.fileName
            sha256 = $baseHash
        }
        curseForgeAdditions = $Config.curseForgeAdditions
        customArtifacts = @($artifactRows | ForEach-Object {
            [ordered]@{
                name = $_.name
                version = $_.version
                archivePath = $_.archivePath
                sides = $_.sides
                sha256 = $_.sha256
            }
        })
        exactClientLock = [ordered]@{
            fileName = $Config.clientLock.fileName
            verificationScript = $Config.clientLock.verificationScript
            expectedModFiles = [int]$Config.clientLock.expectedModFiles
            expectedResourcePacks = [int]$Config.clientLock.expectedResourcePacks
            sha256 = (Get-FileHash -LiteralPath $clientLockSource -Algorithm SHA256).Hash
        }
        privacy = [ordered]@{
            packagingMode = 'verified base plus explicit allowlist'
            personalInstanceRead = $false
            forbiddenPrefixes = $Config.forbiddenArchivePrefixes
            forbiddenFiles = $Config.forbiddenArchiveFiles
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $staging 'RELEASE-MANIFEST.json'),
        ($releaseManifest | ConvertTo-Json -Depth 20) + "`n",
        $utf8NoBom
    )

    $outputName = "GregTech-Odyssey-Friends-0.5.6-v$($Config.packageVersion).zip"
    $outputPath = Join-Path $OutputDirectory $outputName
    if (Test-Path -LiteralPath $outputPath) {
        Remove-Item -LiteralPath $outputPath -Force
    }
    Write-Host "Compressing $outputName..."
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outputStream = [IO.File]::Open($outputPath, [IO.FileMode]::CreateNew)
    try {
        $outputZip = New-Object IO.Compression.ZipArchive(
            $outputStream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $fixedZipTime = [DateTimeOffset]::new(
                1980,
                1,
                1,
                0,
                0,
                0,
                [TimeSpan]::Zero
            )
            foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File | Sort-Object FullName) {
                $relativeName = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
                $zipEntry = $outputZip.CreateEntry(
                    $relativeName,
                    [IO.Compression.CompressionLevel]::Optimal
                )
                $zipEntry.LastWriteTime = $fixedZipTime
                $sourceStream = [IO.File]::OpenRead($file.FullName)
                $entryStream = $zipEntry.Open()
                try {
                    $sourceStream.CopyTo($entryStream)
                }
                finally {
                    $entryStream.Dispose()
                    $sourceStream.Dispose()
                }
            }
        }
        finally {
            $outputZip.Dispose()
        }
    }
    finally {
        $outputStream.Dispose()
    }

    $outputHash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
    $checksumPath = "$outputPath.sha256"
    [IO.File]::WriteAllText(
        $checksumPath,
        "$outputHash  $outputName`n",
        $utf8NoBom
    )

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
