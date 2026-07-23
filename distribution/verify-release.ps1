[CmdletBinding()]
param(
    [string]$ArchivePath
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') -Raw -Encoding UTF8 | ConvertFrom-Json

if (-not $ArchivePath) {
    $latest = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'dist') -File -Filter 'GregTech-Odyssey-Friends-0.5.6-v*.zip' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw 'No release archive found in dist/.'
    }
    $ArchivePath = $latest.FullName
}
if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
    throw "Release archive not found: $ArchivePath"
}
$ArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)

function Get-Entry {
    param([string]$Name)
    $matches = @($zip.Entries | Where-Object FullName -eq $Name)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one archive entry '$Name', found $($matches.Count)"
    }
    return $matches[0]
}

function Read-EntryText {
    param([string]$Name)
    $entry = Get-Entry $Name
    $stream = $entry.Open()
    try {
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true)
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Get-EntryHash {
    param([IO.Compression.ZipArchiveEntry]$Entry)
    $stream = $Entry.Open()
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
        }
        finally {
            $sha.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Assert-NestedZipContains {
    param(
        [string]$OuterEntryName,
        [string[]]$RequiredNames,
        [string]$TextEntry,
        [string]$TextPattern
    )
    $outer = Get-Entry $OuterEntryName
    $outerStream = $outer.Open()
    $memory = New-Object IO.MemoryStream
    try {
        $outerStream.CopyTo($memory)
    }
    finally {
        $outerStream.Dispose()
    }
    $memory.Position = 0
    $nested = New-Object IO.Compression.ZipArchive($memory, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        foreach ($requiredName in $RequiredNames) {
            if (-not $nested.GetEntry($requiredName)) {
                throw "$OuterEntryName is missing nested entry $requiredName"
            }
        }
        if ($TextEntry) {
            $textZipEntry = $nested.GetEntry($TextEntry)
            if (-not $textZipEntry) {
                throw "$OuterEntryName is missing nested text entry $TextEntry"
            }
            $nestedStream = $textZipEntry.Open()
            try {
                $reader = New-Object IO.StreamReader($nestedStream, [Text.Encoding]::UTF8, $true)
                try {
                    $text = $reader.ReadToEnd()
                }
                finally {
                    $reader.Dispose()
                }
            }
            finally {
                $nestedStream.Dispose()
            }
            if ($text -notmatch $TextPattern) {
                throw "$OuterEntryName nested entry $TextEntry does not match '$TextPattern'"
            }
        }
    }
    finally {
        $nested.Dispose()
        $memory.Dispose()
    }
}

try {
    $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
    foreach ($requiredRoot in @(
        'manifest.json',
        'modlist.html',
        'README-FIRST.md',
        'INSTALL-AI.json',
        'CLIENT-MOD-LOCK.json',
        'VERIFY-CLIENT.ps1',
        'RELEASE-MANIFEST.json',
        'CHANGELOG.md',
        'THIRD-PARTY.md',
        'overrides/README-FIRST.md',
        'overrides/INSTALL-AI.json',
        'overrides/CLIENT-MOD-LOCK.json',
        'overrides/VERIFY-CLIENT.ps1'
    )) {
        [void](Get-Entry $requiredRoot)
    }

    foreach ($prefix in $Config.forbiddenArchivePrefixes) {
        $found = @($entryNames | Where-Object { $_.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) })
        if ($found.Count -gt 0) {
            throw "Forbidden private prefix found: $prefix"
        }
    }
    foreach ($file in $Config.forbiddenArchiveFiles) {
        if ($entryNames -contains $file) {
            throw "Forbidden private file found: $file"
        }
    }

    $manifest = Read-EntryText 'manifest.json' | ConvertFrom-Json
    if ($manifest.name -ne $Config.packageName -or $manifest.version -ne $Config.manifestVersion) {
        throw "Manifest identity mismatch: $($manifest.name) $($manifest.version)"
    }
    if ($manifest.minecraft.version -ne '1.20.1' -or $manifest.minecraft.modLoaders[0].id -ne 'forge-47.4.20') {
        throw 'Minecraft or Forge version mismatch.'
    }
    $duplicates = @($manifest.files | Group-Object projectID | Where-Object Count -ne 1)
    if ($duplicates.Count -gt 0) {
        throw "Duplicate CurseForge project IDs: $($duplicates.Name -join ', ')"
    }
    foreach ($addition in $Config.curseForgeAdditions) {
        $found = @($manifest.files | Where-Object {
            $_.projectID -eq $addition.projectID -and
            $_.fileID -eq $addition.fileID -and
            $_.required -eq $true
        })
        if ($found.Count -ne 1) {
            throw "Missing required CurseForge addition $($addition.name)"
        }
    }
    foreach ($projectId in $Config.forceRequiredProjectIds) {
        $found = @($manifest.files | Where-Object { $_.projectID -eq $projectId -and $_.required -eq $true })
        if ($found.Count -ne 1) {
            throw "Project $projectId is not required in the release manifest."
        }
    }

    $releaseManifest = Read-EntryText 'RELEASE-MANIFEST.json' | ConvertFrom-Json
    $clientLock = Read-EntryText 'CLIENT-MOD-LOCK.json' | ConvertFrom-Json
    if ($clientLock.schema -ne 'gto-friends-client-lock/v1') {
        throw "Unexpected client lock schema: $($clientLock.schema)"
    }
    if ($clientLock.package.version -ne $Config.packageVersion) {
        throw "Client lock package version mismatch: $($clientLock.package.version)"
    }
    if (
        [int]$clientLock.counts.mods -ne [int]$Config.clientLock.expectedModFiles -or
        [int]$clientLock.counts.resourcePacks -ne [int]$Config.clientLock.expectedResourcePacks
    ) {
        throw 'Client lock file counts do not match pack-config.json.'
    }
    $lockPaths = @($clientLock.files | ForEach-Object path)
    if (@($lockPaths | Group-Object | Where-Object Count -ne 1).Count -gt 0) {
        throw 'Duplicate paths found in client lock.'
    }
    foreach ($lockedFile in $clientLock.files) {
        if ($lockedFile.sha256 -notmatch '^[A-F0-9]{64}$' -or [long]$lockedFile.size -le 0) {
            throw "Invalid client lock entry: $($lockedFile.path)"
        }
    }
    $rootLockHash = Get-EntryHash (Get-Entry 'CLIENT-MOD-LOCK.json')
    $overrideLockHash = Get-EntryHash (Get-Entry 'overrides/CLIENT-MOD-LOCK.json')
    if ($rootLockHash -ne $overrideLockHash -or $rootLockHash -ne $releaseManifest.exactClientLock.sha256) {
        throw 'Client lock copies or release-manifest hash do not match.'
    }
    $embeddedLockedFiles = 0
    foreach ($lockedFile in $clientLock.files) {
        $embeddedPath = 'overrides/' + $lockedFile.path
        if ($entryNames -contains $embeddedPath) {
            $embeddedHash = Get-EntryHash (Get-Entry $embeddedPath)
            if ($embeddedHash -ne $lockedFile.sha256) {
                throw "Embedded file does not match client lock: $embeddedPath"
            }
            $embeddedLockedFiles++
        }
    }
    if ($embeddedLockedFiles -lt 6) {
        throw "Expected at least 6 embedded client-lock files, found $embeddedLockedFiles"
    }
    foreach ($artifact in $releaseManifest.customArtifacts) {
        $entry = Get-Entry $artifact.archivePath
        $actual = Get-EntryHash $entry
        if ($actual -ne $artifact.sha256) {
            throw "Artifact hash mismatch for $($artifact.archivePath)"
        }
    }

    Assert-NestedZipContains `
        'overrides/mods/gto-terminal-fix-1.0.0.jar' `
        @('META-INF/mods.toml', 'META-INF/coremods.json', 'coremods/gto_terminal_fix.js') `
        'META-INF/mods.toml' `
        'modId="gto_terminal_fix"'
    Assert-NestedZipContains `
        'overrides/mods/gto-farming-fix-1.0.1.jar' `
        @('META-INF/mods.toml', 'META-INF/coremods.json', 'coremods/gto_farming_fix.js') `
        'META-INF/mods.toml' `
        'modId="gto_farming_fix"'
    Assert-NestedZipContains `
        'overrides/config/openloader/resources/z-gto-russian-fixes-0.5.6-v1.0.0.zip' `
        @('pack.mcmeta', 'assets/gtceu/lang/ru_ru.json', 'assets/xaeroworldmap/lang/ru_ru.json') `
        'pack.mcmeta' `
        'GTO 0\.5\.6'

    foreach ($sharedConfig in @(
        'overrides/config/treechop-common.toml',
        'overrides/config/treechop-client.toml',
        'overrides/config/fallingtrees_common.json',
        'overrides/config/fallingtrees_client.json',
        'overrides/config/oculus.properties',
        'overrides/shaderpacks/ComplementaryReimagined_r5.6.1 + EuphoriaPatches_1.7.7.txt'
    )) {
        [void](Get-Entry $sharedConfig)
    }

    $checksumPath = "$ArchivePath.sha256"
    if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
        throw "Checksum file not found: $checksumPath"
    }
    $expectedArchiveHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
    $actualArchiveHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
    if ($actualArchiveHash -ne $expectedArchiveHash) {
        throw 'Outer release archive SHA-256 mismatch.'
    }

    $requiredCount = @($manifest.files | Where-Object required).Count
    $optionalCount = @($manifest.files | Where-Object { -not $_.required }).Count
    Write-Host 'VERIFICATION PASSED'
    Write-Host "Archive: $ArchivePath"
    Write-Host "Entries: $($zip.Entries.Count)"
    Write-Host "CurseForge dependencies: $($manifest.files.Count) ($requiredCount required, $optionalCount optional)"
    Write-Host "SHA-256: $actualArchiveHash"
}
finally {
    $zip.Dispose()
}
