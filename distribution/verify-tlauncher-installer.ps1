[CmdletBinding()]
param(
    [string]$ArchivePath
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') -Raw -Encoding UTF8 |
    ConvertFrom-Json

if (-not $ArchivePath) {
    $ArchivePath = Join-Path $RepoRoot (
        "dist\GTO-Friends-TLauncher-Installer-v$($Config.packageVersion).zip"
    )
}
if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
    throw "TLauncher installer archive not found: $ArchivePath"
}
$ArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Entry {
    param([IO.Compression.ZipArchive]$Archive, [string]$Name)
    $matches = @($Archive.Entries | Where-Object FullName -eq $Name)
    if ($matches.Count -ne 1) {
        throw "Expected one entry '$Name', found $($matches.Count)"
    }
    return $matches[0]
}

function Read-EntryText {
    param([IO.Compression.ZipArchiveEntry]$Entry)
    $stream = $Entry.Open()
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

function Read-EntryBytes {
    param([IO.Compression.ZipArchiveEntry]$Entry)
    $stream = $Entry.Open()
    $memory = New-Object IO.MemoryStream
    try {
        $stream.CopyTo($memory)
        return ,$memory.ToArray()
    }
    finally {
        $memory.Dispose()
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

function ConvertTo-WindowsBatchText {
    param([string]$Text)

    if ($Text -match '[^\x09\x0A\x0D\x20-\x7E]') {
        throw 'Launcher BAT source must be ASCII-only.'
    }
    $Text = $Text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd("`n")
    return $Text.Replace("`n", "`r`n") + "`r`n"
}

$checksumPath = "$ArchivePath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Outer checksum file not found: $checksumPath"
}
$expectedOuterHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
$actualOuterHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
if ($actualOuterHash -ne $expectedOuterHash) {
    throw "Outer installer SHA-256 mismatch: $actualOuterHash"
}

$outer = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
try {
    foreach ($required in @(
        'GTO-TLauncher-Installer.jar',
        'INSTALL-GTO-TLAUNCHER.bat',
        'README-TLAUNCHER.txt',
        'SHA256SUMS.txt',
        'THIRD-PARTY.md',
        'SOURCE/GtoTLauncherInstaller.java',
        'SOURCE/downloads.tsv',
        'SOURCE/generate_download_manifest.py'
    )) {
        [void](Get-Entry $outer $required)
    }
    if ($outer.Entries.Count -ne 8) {
        throw "Unexpected outer archive entries: $($outer.Entries.Count)"
    }

    $batchEntry = Get-Entry $outer 'INSTALL-GTO-TLAUNCHER.bat'
    $batchBytes = Read-EntryBytes $batchEntry
    if ($batchBytes.Count -eq 0 -or $batchBytes[0] -ne [byte][char]'@') {
        throw 'Packaged launcher BAT must begin with @ and have no BOM.'
    }
    for ($byteIndex = 0; $byteIndex -lt $batchBytes.Count; $byteIndex++) {
        $value = $batchBytes[$byteIndex]
        if ($value -gt 0x7F) {
            throw "Packaged launcher BAT contains a non-ASCII byte at $byteIndex."
        }
        if ($value -eq 0x0A -and (
            $byteIndex -eq 0 -or $batchBytes[$byteIndex - 1] -ne 0x0D
        )) {
            throw "Packaged launcher BAT contains a bare LF at $byteIndex."
        }
        if ($value -eq 0x0D -and (
            $byteIndex + 1 -ge $batchBytes.Count -or
            $batchBytes[$byteIndex + 1] -ne 0x0A
        )) {
            throw "Packaged launcher BAT contains a bare CR at $byteIndex."
        }
    }
    $batchText = [Text.Encoding]::ASCII.GetString($batchBytes)
    $repositoryBatch = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot 'tlauncher-installer\INSTALL-GTO-TLAUNCHER.bat'
    ) -Raw -Encoding UTF8
    $expectedBatch = ConvertTo-WindowsBatchText $repositoryBatch
    if ($batchText -cne $expectedBatch) {
        throw 'Packaged launcher BAT differs from repository source.'
    }
    if (
        $batchText -match '[^\x09\x0A\x0D\x20-\x7E]' -or
        $batchText -match '(?<!\r)\n' -or
        -not $batchText.EndsWith("`r`n", [StringComparison]::Ordinal)
    ) {
        throw 'Packaged launcher BAT must be ASCII-only with CRLF line endings.'
    }
    if (
        [regex]::Matches($batchText, 'if exist "%%~fJ"').Count -ne 3 -or
        $batchText -notmatch '(?m)^start "" /wait "%JAVA%" -jar "%INSTALLER%"\r?$'
    ) {
        throw 'Launcher BAT does not safely locate and start Java.'
    }
    foreach ($forbiddenPattern in @(
        '(?i)\bpowershell(?:\.exe)?\b',
        '(?i)\bexecutionpolicy\b',
        '(?i)\brunas\b',
        '(?i)\bnet\s+session\b',
        '(?i)\breg(?:\.exe)?\s+(?:add|delete|copy|load|restore|save)\b'
    )) {
        if ($batchText -match $forbiddenPattern) {
            throw "Unsafe launcher BAT pattern found: $forbiddenPattern"
        }
    }

    $smokeRoot = Join-Path $env:TEMP (
        'GTO Installer BAT Smoke Кириллица ! ' + [Guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $smokeRoot | Out-Null
    try {
        $smokeBatch = Join-Path $smokeRoot 'INSTALL-GTO-TLAUNCHER.bat'
        [IO.File]::WriteAllBytes($smokeBatch, $batchBytes)
        Push-Location $smokeRoot
        try {
            $smokeOutput = @(
                & $env:ComSpec /d /c 'INSTALL-GTO-TLAUNCHER.bat < nul' 2>&1
            ) -join "`n"
            $smokeExitCode = $LASTEXITCODE
        }
        finally {
            Pop-Location
        }
        if (
            $smokeExitCode -ne 1 -or
            $smokeOutput -notmatch 'ERROR: GTO-TLauncher-Installer\.jar was not found' -or
            $smokeOutput -match '(?i)not recognized as an internal or external command' -or
            $smokeOutput -match '(?i)syntax of the command is incorrect'
        ) {
            throw "Launcher BAT smoke test failed (exit $smokeExitCode):`n$smokeOutput"
        }

        [IO.File]::WriteAllBytes(
            (Join-Path $smokeRoot 'GTO-TLauncher-Installer.jar'),
            [byte[]]@()
        )
        $emptyAppData = Join-Path $smokeRoot 'Empty AppData'
        $emptyLocalAppData = Join-Path $smokeRoot 'Empty LocalAppData'
        New-Item -ItemType Directory -Path $emptyAppData, $emptyLocalAppData |
            Out-Null
        $oldPath = $env:Path
        $oldAppData = $env:APPDATA
        $oldLocalAppData = $env:LOCALAPPDATA
        try {
            $env:Path = "$env:SystemRoot\System32;$env:SystemRoot"
            $env:APPDATA = $emptyAppData
            $env:LOCALAPPDATA = $emptyLocalAppData
            Push-Location $smokeRoot
            try {
                $javaSmokeOutput = @(
                    & $env:ComSpec /d /c 'INSTALL-GTO-TLAUNCHER.bat < nul' 2>&1
                ) -join "`n"
                $javaSmokeExitCode = $LASTEXITCODE
            }
            finally {
                Pop-Location
            }
        }
        finally {
            $env:Path = $oldPath
            $env:APPDATA = $oldAppData
            $env:LOCALAPPDATA = $oldLocalAppData
        }
        if (
            $javaSmokeExitCode -ne 2 -or
            $javaSmokeOutput -notmatch 'Java was not found' -or
            $javaSmokeOutput -match '(?i)not recognized as an internal or external command' -or
            $javaSmokeOutput -match '(?i)syntax of the command is incorrect'
        ) {
            throw "Launcher Java-detection smoke test failed " +
                "(exit $javaSmokeExitCode):`n$javaSmokeOutput"
        }
    }
    finally {
        if (Test-Path -LiteralPath $smokeRoot) {
            $resolvedSmokeRoot = (Resolve-Path -LiteralPath $smokeRoot).Path
            $resolvedTemp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
            if (-not ($resolvedSmokeRoot + '\').StartsWith(
                $resolvedTemp,
                [StringComparison]::OrdinalIgnoreCase
            )) {
                throw "Refusing to remove unsafe smoke-test path: $resolvedSmokeRoot"
            }
            Remove-Item -LiteralPath $resolvedSmokeRoot -Recurse -Force
        }
    }

    $sourceEntry = Get-Entry $outer 'SOURCE/GtoTLauncherInstaller.java'
    $repositorySource = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot 'tlauncher-installer\src\GtoTLauncherInstaller.java'
    ) -Raw -Encoding UTF8
    if ((Read-EntryText $sourceEntry) -ne $repositorySource) {
        throw 'Packaged Java source differs from repository source.'
    }

    $checksums = Read-EntryText (Get-Entry $outer 'SHA256SUMS.txt')
    $jarEntry = Get-Entry $outer 'GTO-TLauncher-Installer.jar'
    $jarHash = Get-EntryHash $jarEntry
    if ($checksums -notmatch [regex]::Escape("$jarHash  GTO-TLauncher-Installer.jar")) {
        throw 'Installer JAR does not match SHA256SUMS.txt.'
    }
    $sourceDownloadsEntry = Get-Entry $outer 'SOURCE/downloads.tsv'
    $sourceDownloadsHash = Get-EntryHash $sourceDownloadsEntry
    if ($checksums -notmatch [regex]::Escape("$sourceDownloadsHash  SOURCE/downloads.tsv")) {
        throw 'Source downloads.tsv does not match SHA256SUMS.txt.'
    }

    $jarStream = $jarEntry.Open()
    $jarMemory = New-Object IO.MemoryStream
    try {
        $jarStream.CopyTo($jarMemory)
    }
    finally {
        $jarStream.Dispose()
    }
    $jarMemory.Position = 0
    $jar = New-Object IO.Compression.ZipArchive(
        $jarMemory,
        [IO.Compression.ZipArchiveMode]::Read,
        $false
    )
    try {
        foreach ($required in @(
            'META-INF/MANIFEST.MF',
            'GtoTLauncherInstaller.class',
            'downloads.tsv',
            'client-lock.tsv',
            'payload/README-TLAUNCHER.txt',
            'payload/mods/gto-terminal-fix-1.0.0.jar',
            'payload/mods/gto-farming-fix-1.0.1.jar',
            'payload/mods/gtocore-forge-1.20.1-0.5.6-beta.jar',
            'payload/mods/gtonativelib-1.0.jar'
        )) {
            [void](Get-Entry $jar $required)
        }

        $manifest = Read-EntryText (Get-Entry $jar 'META-INF/MANIFEST.MF')
        if ($manifest -notmatch '(?m)^Main-Class: GtoTLauncherInstaller\r?$') {
            throw 'Installer JAR has no correct Main-Class.'
        }

        $classEntry = Get-Entry $jar 'GtoTLauncherInstaller.class'
        $classStream = $classEntry.Open()
        try {
            $header = New-Object byte[] 8
            if ($classStream.Read($header, 0, 8) -ne 8) {
                throw 'Installer class header is truncated.'
            }
        }
        finally {
            $classStream.Dispose()
        }
        $majorVersion = ([int]$header[6] -shl 8) + [int]$header[7]
        if ($majorVersion -ne 52) {
            throw "Installer is not Java 8 compatible. Class major: $majorVersion"
        }

        $downloadsText = Read-EntryText (Get-Entry $jar 'downloads.tsv')
        $sourceDownloadsText = Read-EntryText $sourceDownloadsEntry
        if ($downloadsText -ne $sourceDownloadsText) {
            throw 'Embedded and source downloads.tsv copies differ.'
        }
        $downloads = @($downloadsText | ConvertFrom-Csv -Delimiter "`t")
        if ($downloads.Count -ne 171) {
            throw "Expected 171 downloads, found $($downloads.Count)"
        }
        $duplicateDownloads = @(
            $downloads | Group-Object destination | Where-Object Count -ne 1
        )
        if ($duplicateDownloads.Count -gt 0) {
            throw "Duplicate download destinations: $($duplicateDownloads.Name -join ', ')"
        }
        foreach ($download in $downloads) {
            if (
                $download.destination -notmatch '^(?:mods|shaderpacks)/[^/\\:]+$' -or
                $download.url -notmatch '^https://edge\.forgecdn\.net/files/' -or
                $download.sha256 -notmatch '^[A-F0-9]{64}$' -or
                [long]$download.size -le 0
            ) {
                throw "Invalid download row: $($download.destination)"
            }
        }
        $remoteMods = @($downloads | Where-Object destination -Like 'mods/*')
        if ($remoteMods.Count -ne 170) {
            throw "Expected 170 remote mods, found $($remoteMods.Count)"
        }

        $lockText = Read-EntryText (Get-Entry $jar 'client-lock.tsv')
        $lock = @($lockText | ConvertFrom-Csv -Delimiter "`t")
        if ($lock.Count -ne 177) {
            throw "Expected 177 locked files, found $($lock.Count)"
        }
        if (@($lock | Group-Object path | Where-Object Count -ne 1).Count -gt 0) {
            throw 'Duplicate paths in embedded client lock.'
        }
        $lockedMods = @($lock | Where-Object path -Like 'mods/*')
        if ($lockedMods.Count -ne 174) {
            throw "Expected 174 TLauncher mod files, found $($lockedMods.Count)"
        }
        foreach ($removedTheme in @(
            'mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled',
            'mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled'
        )) {
            if ($lock.path -contains $removedTheme) {
                throw "Unused disabled theme remained in TLauncher lock: $removedTheme"
            }
        }
        if ($lock.path -notcontains 'shaderpacks/ComplementaryReimagined_r5.6.1.zip') {
            throw 'Complementary shader is absent from TLauncher lock.'
        }

        $embeddedMods = @(
            $jar.Entries |
                Where-Object {
                    $_.FullName.StartsWith('payload/mods/') -and
                    -not $_.FullName.EndsWith('/')
                }
        )
        if ($embeddedMods.Count -ne 4) {
            throw "Only 4 local mod files may be embedded, found $($embeddedMods.Count)"
        }

        $verifiedEmbedded = 0
        foreach ($lockedFile in $lock) {
            $payloadPath = 'payload/' + $lockedFile.path
            $matches = @($jar.Entries | Where-Object FullName -eq $payloadPath)
            if ($matches.Count -eq 0) {
                continue
            }
            if ($matches.Count -ne 1) {
                throw "Duplicate payload path: $payloadPath"
            }
            $actualHash = Get-EntryHash $matches[0]
            if (
                $actualHash -ne $lockedFile.sha256 -or
                [long]$matches[0].Length -ne [long]$lockedFile.size
            ) {
                throw "Embedded payload does not match lock: $payloadPath"
            }
            $verifiedEmbedded++
        }
        if ($verifiedEmbedded -lt 6) {
            throw "Expected at least 6 locked payload files, found $verifiedEmbedded"
        }

        foreach ($forbidden in @(
            'payload/saves/',
            'payload/logs/',
            'payload/crash-reports/',
            'payload/screenshots/',
            'payload/backups/',
            'payload/xaero/',
            'payload/XaeroWaypoints/',
            'payload/XaeroWorldMap/'
        )) {
            if (@($jar.Entries | Where-Object {
                $_.FullName.StartsWith($forbidden, [StringComparison]::OrdinalIgnoreCase)
            }).Count -gt 0) {
                throw "Private payload prefix found: $forbidden"
            }
        }
        foreach ($forbidden in @(
            'payload/servers.dat',
            'payload/options.txt',
            'payload/usercache.json',
            'payload/usernamecache.json'
        )) {
            if (@($jar.Entries | Where-Object FullName -eq $forbidden).Count -gt 0) {
                throw "Private payload file found: $forbidden"
            }
        }

        Write-Host 'TLAUNCHER INSTALLER VERIFICATION PASSED'
        Write-Host "Archive: $ArchivePath"
        Write-Host "Outer entries: $($outer.Entries.Count)"
        Write-Host "JAR entries: $($jar.Entries.Count)"
        Write-Host "Downloads: $($downloads.Count)"
        Write-Host "Locked files: $($lock.Count)"
        Write-Host "Embedded local mods: $($embeddedMods.Count)"
        Write-Host "Java class major: $majorVersion"
        Write-Host "SHA-256: $actualOuterHash"
    }
    finally {
        $jar.Dispose()
        $jarMemory.Dispose()
    }
}
finally {
    $outer.Dispose()
}
