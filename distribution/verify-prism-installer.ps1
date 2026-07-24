[CmdletBinding()]
param(
    [string]$ArchivePath
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$InstallerRoot = Join-Path $PSScriptRoot 'prism-installer'
$Config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') -Raw -Encoding UTF8 |
    ConvertFrom-Json
$ExpectedRuntimeFiles = 315
$ExpectedPrismFiles = 75
$ExpectedPrismExeSize = 20796176L
$ExpectedPrismExeHash = '4A074C611AFE0219A4AB478EF95429575550B9059202AE2A7E625C116A699CCA'
$ExpectedBootstrapLockHash =
    'E90F43E00F4A366209F5B715B625BF823402B00B469CCFB65BA9CF42819A3606'

if (-not $ArchivePath) {
    $ArchivePath = Join-Path $RepoRoot (
        "dist\GTO-Friends-Licensed-Prism-Installer-v$($Config.packageVersion).zip"
    )
}
if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
    throw "Licensed Prism installer archive not found: $ArchivePath"
}
$ArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Entry {
    param([IO.Compression.ZipArchive]$Archive, [string]$Name)

    $matches = @($Archive.Entries | Where-Object FullName -CEQ $Name)
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

function Assert-AsciiCrlfBatch {
    param(
        [byte[]]$Bytes,
        [string]$Purpose
    )

    if ($Bytes.Count -eq 0 -or $Bytes[0] -ne [byte][char]'@') {
        throw "$Purpose must begin with @ and have no BOM."
    }
    for ($index = 0; $index -lt $Bytes.Count; $index++) {
        $value = $Bytes[$index]
        if ($value -gt 0x7F) {
            throw "$Purpose contains a non-ASCII byte at $index."
        }
        if ($value -eq 0x0A -and (
            $index -eq 0 -or $Bytes[$index - 1] -ne 0x0D
        )) {
            throw "$Purpose contains a bare LF at $index."
        }
        if ($value -eq 0x0D -and (
            $index + 1 -ge $Bytes.Count -or
            $Bytes[$index + 1] -ne 0x0A
        )) {
            throw "$Purpose contains a bare CR at $index."
        }
    }
    $text = [Text.Encoding]::ASCII.GetString($Bytes)
    if (-not $text.EndsWith("`r`n", [StringComparison]::Ordinal)) {
        throw "$Purpose must end with CRLF."
    }
    return $text
}

function Assert-SafeArchiveEntries {
    param(
        [IO.Compression.ZipArchive]$Archive,
        [string]$Purpose
    )

    $seen = New-Object 'Collections.Generic.HashSet[string]' (
        [StringComparer]::OrdinalIgnoreCase
    )
    foreach ($entry in $Archive.Entries) {
        $name = $entry.FullName
        if (
            [string]::IsNullOrWhiteSpace($name) -or
            $name.Contains('\') -or
            $name.StartsWith('/') -or
            $name.Contains([char]0) -or
            $name.Contains(':') -or
            $entry.Name.Length -eq 0
        ) {
            throw "$Purpose contains an unsafe or non-file entry: $name"
        }
        $segments = $name.Split('/')
        if (
            $segments -contains '' -or
            $segments -contains '.' -or
            $segments -contains '..'
        ) {
            throw "$Purpose contains an unsafe path: $name"
        }
        if (-not $seen.Add($name)) {
            throw "$Purpose contains a duplicate case-insensitive path: $name"
        }
        $unixType = (($entry.ExternalAttributes -shr 16) -band 0xF000)
        if ($unixType -eq 0xA000) {
            throw "$Purpose contains a symbolic-link entry: $name"
        }
        if (
            $entry.LastWriteTime.Year -ne 1980 -or
            $entry.LastWriteTime.Month -ne 1 -or
            $entry.LastWriteTime.Day -ne 1
        ) {
            throw "$Purpose is not deterministic; timestamp differs for $name."
        }
    }
}

function Assert-DeterministicEntryOrder {
    param(
        [IO.Compression.ZipArchive]$Archive,
        [string]$Purpose,
        [switch]$ManifestFirst
    )

    [string[]]$actual = @($Archive.Entries | ForEach-Object FullName)
    [string[]]$expected = [string[]]$actual.Clone()
    [Array]::Sort($expected, [StringComparer]::Ordinal)
    if ($ManifestFirst -and $expected -contains 'META-INF/MANIFEST.MF') {
        $expected = @(
            'META-INF/MANIFEST.MF'
            $expected | Where-Object { $_ -cne 'META-INF/MANIFEST.MF' }
        )
    }
    for ($index = 0; $index -lt $actual.Count; $index++) {
        if ($actual[$index] -cne $expected[$index]) {
            throw (
                "$Purpose entries are not in deterministic ordinal order at " +
                "$index`: $($actual[$index])"
            )
        }
    }
}

function ConvertFrom-LockTsv {
    param(
        [string]$Text,
        [string]$Purpose,
        [int]$ExpectedCount
    )

    if (-not $Text.EndsWith("`n", [StringComparison]::Ordinal)) {
        throw "$Purpose must end with LF."
    }
    $rows = @($Text | ConvertFrom-Csv -Delimiter "`t")
    if ($rows.Count -ne $ExpectedCount) {
        throw "$Purpose expected $ExpectedCount rows, found $($rows.Count)."
    }
    if (@($rows | Group-Object path | Where-Object Count -ne 1).Count -gt 0) {
        throw "$Purpose contains duplicate paths."
    }
    [string[]]$actualPaths = @($rows | ForEach-Object path)
    [string[]]$sortedPaths = @($actualPaths)
    [Array]::Sort($sortedPaths, [StringComparer]::Ordinal)
    for ($index = 0; $index -lt $actualPaths.Count; $index++) {
        $row = $rows[$index]
        if ($actualPaths[$index] -cne $sortedPaths[$index]) {
            throw "$Purpose paths are not in ordinal order."
        }
        if (
            [string]::IsNullOrWhiteSpace($row.path) -or
            $row.path.Contains('\') -or
            $row.path.StartsWith('/') -or
            $row.path.Contains(':') -or
            $row.path.Split('/') -contains '..' -or
            $row.sha256 -cnotmatch '^[A-F0-9]{64}$' -or
            [long]$row.size -lt 0
        ) {
            throw "$Purpose contains an invalid row: $($row.path)"
        }
    }
    return ,$rows
}

function Get-ExpectedInstalledClientLockText {
    $jsonPath = Join-Path $PSScriptRoot 'CLIENT-MOD-LOCK.json'
    $sourceLock = Get-Content -LiteralPath $jsonPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if (
        $sourceLock.schema -ne 'gto-friends-client-lock/v1' -or
        $sourceLock.files.Count -ne 178
    ) {
        throw 'Repository CLIENT-MOD-LOCK.json is unexpected.'
    }
    $omitted = @(
        'mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled',
        'mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled'
    )
    $files = @($sourceLock.files | Where-Object path -NotIn $omitted)
    $files += [pscustomobject]@{
        path = 'shaderpacks/ComplementaryReimagined_r5.6.1.zip'
        size = 500696
        sha256 = '33153747D25FBEE470ACB42CCF4F26B03CC551BB900D4C6D3389B5500DD84839'
    }
    if ($files.Count -ne 177) {
        throw "Expected 177 transformed repository lock files, found $($files.Count)."
    }
    [string[]]$paths = @($files | ForEach-Object path)
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $lines = New-Object 'Collections.Generic.List[string]'
    $lines.Add("path`tsize`tsha256")
    foreach ($path in $paths) {
        $entry = @($files | Where-Object path -CEQ $path)
        if ($entry.Count -ne 1) {
            throw "Duplicate transformed repository lock path: $path"
        }
        $lines.Add(
            "$($entry[0].path)`t$($entry[0].size)`t$($entry[0].sha256)"
        )
    }
    return ($lines -join "`n") + "`n"
}

function Copy-EntryToFile {
    param(
        [IO.Compression.ZipArchiveEntry]$Entry,
        [string]$Path
    )

    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    $input = $Entry.Open()
    $output = [IO.File]::Open($Path, [IO.FileMode]::CreateNew)
    try {
        $input.CopyTo($output)
    }
    finally {
        $output.Dispose()
        $input.Dispose()
    }
}

function Invoke-CapturedProcess {
    param(
        [string]$FileName,
        [string]$Arguments,
        [string]$WorkingDirectory,
        [int]$TimeoutMilliseconds = 60000
    )

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FileName
    $startInfo.Arguments = $Arguments
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Failed to start process: $FileName"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            $process.Kill()
            throw "Process timed out: $FileName $Arguments"
        }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut = $stdout
            StdErr = $stderr
            Combined = $stdout + "`n" + $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

$checksumPath = "$ArchivePath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Outer checksum file not found: $checksumPath"
}
$outerChecksumText = Get-Content -LiteralPath $checksumPath -Raw
$expectedOutputName =
    "GTO-Friends-Licensed-Prism-Installer-v$($Config.packageVersion).zip"
if ($outerChecksumText -cnotmatch (
    '^(?<hash>[A-F0-9]{64})  ' +
    [regex]::Escape($expectedOutputName) +
    '\n$'
)) {
    throw 'Outer checksum sidecar has an unexpected format or filename.'
}
$expectedOuterHash = $Matches.hash
$actualOuterHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
if ($actualOuterHash -cne $expectedOuterHash) {
    throw "Outer installer SHA-256 mismatch: $actualOuterHash"
}

$outer = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
try {
    Assert-SafeArchiveEntries $outer 'Outer installer ZIP'
    Assert-DeterministicEntryOrder $outer 'Outer installer ZIP'

    $requiredOuterEntries = @(
        'GTO-Licensed-Prism-Installer.jar',
        'INSTALL-GTO-LICENSED.bat',
        'README-LICENSED.txt',
        'SHA256SUMS.txt',
        'THIRD-PARTY.md',
        'SOURCE/GtoPrismInstaller.java',
        'SOURCE/downloads.tsv',
        'SOURCE/client-lock.tsv',
        'SOURCE/prism-lock.tsv',
        'SOURCE/bootstrap-java-lock.tsv',
        'SOURCE/build-prism-installer.ps1',
        'SOURCE/PROVENANCE.txt'
    )
    foreach ($required in $requiredOuterEntries) {
        [void](Get-Entry $outer $required)
    }
    if ($outer.Entries.Count -ne ($ExpectedRuntimeFiles + $requiredOuterEntries.Count)) {
        throw "Unexpected outer archive entry count: $($outer.Entries.Count)"
    }
    foreach ($entry in $outer.Entries) {
        if (
            $entry.FullName -notlike 'BOOTSTRAP-JAVA/*' -and
            $requiredOuterEntries -cnotcontains $entry.FullName
        ) {
            throw "Unexpected outer archive entry: $($entry.FullName)"
        }
        if (
            $entry.FullName -match '(?i)(?:^|/)(?:accounts\.json|saves|logs|' +
                'crash-reports|screenshots|backups|usercache\.json|' +
                'usernamecache\.json|launcher_profiles\.json)(?:/|$)'
        ) {
            throw "Private data path leaked into installer: $($entry.FullName)"
        }
    }

    $checksumsText = Read-EntryText (Get-Entry $outer 'SHA256SUMS.txt')
    if (
        $checksumsText.Contains("`r") -or
        -not $checksumsText.EndsWith("`n", [StringComparison]::Ordinal)
    ) {
        throw 'SHA256SUMS.txt must use LF and end with a newline.'
    }
    $checksumRows = @{}
    $checksumPathOrder = New-Object 'Collections.Generic.List[string]'
    foreach ($line in $checksumsText.TrimEnd("`n").Split("`n")) {
        if ($line -cnotmatch '^(?<hash>[A-F0-9]{64})  (?<path>[^\\].+)$') {
            throw "Invalid SHA256SUMS.txt line: $line"
        }
        if ($checksumRows.ContainsKey($Matches.path)) {
            throw "Duplicate SHA256SUMS.txt path: $($Matches.path)"
        }
        $checksumRows[$Matches.path] = $Matches.hash
        $checksumPathOrder.Add($Matches.path)
    }
    [string[]]$expectedChecksumOrder = @($checksumPathOrder)
    [Array]::Sort($expectedChecksumOrder, [StringComparer]::Ordinal)
    for ($index = 0; $index -lt $checksumPathOrder.Count; $index++) {
        if ($checksumPathOrder[$index] -cne $expectedChecksumOrder[$index]) {
            throw 'SHA256SUMS.txt paths are not in deterministic ordinal order.'
        }
    }
    $hashableEntries = @($outer.Entries | Where-Object FullName -CNE 'SHA256SUMS.txt')
    if ($checksumRows.Count -ne $hashableEntries.Count) {
        throw 'SHA256SUMS.txt does not cover exactly every other outer entry.'
    }
    foreach ($entry in $hashableEntries) {
        if (-not $checksumRows.ContainsKey($entry.FullName)) {
            throw "SHA256SUMS.txt misses $($entry.FullName)."
        }
        $hash = Get-EntryHash $entry
        if ($hash -cne $checksumRows[$entry.FullName]) {
            throw "SHA256SUMS.txt mismatch for $($entry.FullName)."
        }
    }

    $installBatchEntry = Get-Entry $outer 'INSTALL-GTO-LICENSED.bat'
    $installBatchBytes = Read-EntryBytes $installBatchEntry
    $installBatchText = Assert-AsciiCrlfBatch $installBatchBytes 'Packaged installer BAT'
    $repositoryInstallBatch = Get-Content -LiteralPath (
        Join-Path $InstallerRoot 'INSTALL-GTO-LICENSED.bat'
    ) -Raw -Encoding UTF8
    if ($installBatchText -cne (ConvertTo-WindowsBatchText $repositoryInstallBatch)) {
        throw 'Packaged installer BAT differs from repository source.'
    }
    if (
        $installBatchText -notmatch [regex]::Escape(
            'set "JAVA=%~dp0BOOTSTRAP-JAVA\bin\javaw.exe"'
        ) -or
        $installBatchText -notmatch (
            '(?m)^start "" /wait "%JAVA%" -jar "%INSTALLER%"\r?$'
        )
    ) {
        throw 'Installer BAT does not exclusively start the bundled Java runtime.'
    }
    foreach ($forbiddenPattern in @(
        '(?i)\bpowershell(?:\.exe)?\b',
        '(?i)\bexecutionpolicy\b',
        '(?i)\brunas\b',
        '(?i)\bnet\s+session\b',
        '(?i)\breg(?:\.exe)?\s+(?:add|delete|copy|load|restore|save)\b',
        '(?i)\bjava(?:w)?\.exe\b(?!")'
    )) {
        if ($installBatchText -match $forbiddenPattern) {
            throw "Unsafe installer BAT pattern found: $forbiddenPattern"
        }
    }

    $packagedReadme = Read-EntryText (Get-Entry $outer 'README-LICENSED.txt')
    $repositoryReadme = Get-Content -LiteralPath (
        Join-Path $InstallerRoot 'README-LICENSED.txt'
    ) -Raw -Encoding UTF8
    if (
        $packagedReadme -cne $repositoryReadme -or
        $packagedReadme -notmatch 'Prism Launcher 11\.0\.3' -or
        $packagedReadme -notmatch 'Java 21' -or
        $packagedReadme -notmatch 'GTO Easy' -or
        $packagedReadme -notmatch 'Normal' -or
        $packagedReadme -notmatch [regex]::Escape($Config.packageVersion)
    ) {
        throw 'Packaged licensed README is stale or incomplete.'
    }

    $sourceEntry = Get-Entry $outer 'SOURCE/GtoPrismInstaller.java'
    $repositorySource = Get-Content -LiteralPath (
        Join-Path $InstallerRoot 'src\GtoPrismInstaller.java'
    ) -Raw -Encoding UTF8
    if ((Read-EntryText $sourceEntry) -cne $repositorySource) {
        throw 'Packaged Java source differs from repository source.'
    }
    $packagedBuildScript = Read-EntryText (
        Get-Entry $outer 'SOURCE/build-prism-installer.ps1'
    )
    $repositoryBuildScript = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot 'build-prism-installer.ps1'
    ) -Raw -Encoding UTF8
    if ($packagedBuildScript -cne $repositoryBuildScript) {
        throw 'Packaged build script differs from repository source.'
    }

    $canonicalClientArchive = Join-Path $RepoRoot (
        "dist\GregTech-Odyssey-Friends-0.5.6-v$($Config.packageVersion).zip"
    )
    if (-not (Test-Path -LiteralPath $canonicalClientArchive -PathType Leaf)) {
        throw "Canonical client archive is unavailable for provenance: $canonicalClientArchive"
    }
    $canonicalClientFile = Get-Item -LiteralPath $canonicalClientArchive
    $canonicalClientHash = (
        Get-FileHash -LiteralPath $canonicalClientArchive -Algorithm SHA256
    ).Hash
    if (
        $canonicalClientFile.Length -ne 69961102 -or
        $canonicalClientHash -cne (
            '8E72B022E52D64D5B5DF08F7A282F9A2F1A9CB239825F2E7FA7A3E2DB0599571'
        )
    ) {
        throw 'Canonical client archive is not the pinned Friends Edition 1.0.4 release.'
    }
    $expectedProvenance = @(
        'schema=gto-friends-licensed-installer-provenance/v1',
        "package-version=$($Config.packageVersion)",
        "client-archive=$($canonicalClientFile.Name)",
        "client-archive-size=$($canonicalClientFile.Length)",
        "client-archive-sha256=$canonicalClientHash",
        'prism-version=11.0.3',
        'prism-url=https://github.com/PrismLauncher/PrismLauncher/releases/download/11.0.3/PrismLauncher-Windows-MinGW-w64-Portable-11.0.3.zip',
        'prism-size=43902886',
        'prism-sha256=7E27AEDD92EABB0699792B5F6305DB6635290D83652CBD73742C70350E42B7F8',
        'temurin-version=21.0.11+10',
        'temurin-url=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jre_x64_windows_hotspot_21.0.11_10.zip',
        'temurin-archive-size=49005708',
        'temurin-archive-sha256=BE26677AAA20B39A62EDCAAB4C8857A8B76673B0F45ABC0B6143B142B62717E4',
        'temurin-file-count=315',
        "temurin-lock-sha256=$ExpectedBootstrapLockHash"
    )
    $expectedProvenanceText = ($expectedProvenance -join "`n") + "`n"
    $packagedProvenance = Read-EntryText (
        Get-Entry $outer 'SOURCE/PROVENANCE.txt'
    )
    if ($packagedProvenance -cne $expectedProvenanceText) {
        throw 'Packaged dependency provenance is stale or unexpected.'
    }
    foreach ($requiredSourceText in @(
        'https://github.com/PrismLauncher/PrismLauncher/releases/',
        'PrismLauncher-Windows-MinGW-w64-Portable-',
        'private static final String PRISM_VERSION = "11.0.3";',
        '7E27AEDD92EABB0699792B5F6305DB6635290D83652CBD73742C70350E42B7F8',
        '43902886',
        'GTO-Friends',
        'UserData',
        'prismlauncher.cfg',
        'prismlauncher_update.cfg',
        'auto_check=false',
        'IgnoreJavaWizard=true',
        'AutomaticJavaDownload=false',
        'AutomaticJavaSwitch=false',
        'SelectedInstance=',
        'bootstrap-java-lock.tsv',
        '"OverrideJavaLocation", "true"',
        '"IgnoreJavaCompatibility", "true"',
        '"ManagedPack", "false"',
        '1.20.1',
        '47.4.20',
        'prepareConfigUpdates',
        'requireGtoEasy',
        'requireVanillaNormalDefaults',
        'guardExistingWorld',
        'verifyLockedDirectory(root, lock, "UserData")',
        'rejectUnsafeLinks',
        'channel.tryLock()',
        'writeRecoveryMarker',
        '--self-check'
    )) {
        if (-not $repositorySource.Contains($requiredSourceText)) {
            throw "Java source misses licensed installer invariant: $requiredSourceText"
        }
    }
    $expectedVersionConstant =
        'private static final String PACK_VERSION = "' + $Config.packageVersion + '";'
    if (-not $repositorySource.Contains($expectedVersionConstant)) {
        throw 'Java installer PACK_VERSION differs from pack-config.json.'
    }
    if (
        [regex]::Matches(
            $repositorySource,
            '\.resolve\(\s*"accounts(?:\.json)?"\s*\)'
        ).Count -ne 1 -or
        [regex]::Matches(
            $repositorySource,
            [regex]::Escape('writeEmptyAccountsForClean(userData(staging));')
        ).Count -ne 1 -or
        -not $repositorySource.Contains(
            'Path destination = data.resolve("accounts.json");'
        ) -or
        -not $repositorySource.Contains(
            '"{\"accounts\":[],\"formatVersion\":3}\n"'
        ) -or
        -not $repositorySource.Contains('StandardOpenOption.CREATE_NEW')
    ) {
        throw (
            'Clean install must create exactly one new empty Prism accounts file ' +
            'without inspecting an existing account.'
        )
    }
    foreach ($forbiddenAccountsPattern in @(
        '(?s)Files\.(?:exists|read[A-Za-z0-9_]*|copy|move)\s*\([^;]*accounts',
        '(?i)(?:hash|digest|checksum)[A-Za-z0-9_]*\s*\([^)]*accounts',
        '(?i)accounts(?:\.json)?[^;\r\n]*(?:hash|digest|checksum)'
    )) {
        if ($repositorySource -match $forbiddenAccountsPattern) {
            throw "Java installer may inspect or mutate existing accounts data: " +
                $forbiddenAccountsPattern
        }
    }
    $updateMethodStart = $repositorySource.IndexOf(
        'private static void updateInstall(',
        [StringComparison]::Ordinal
    )
    $updateMethodEnd = $repositorySource.IndexOf(
        'private static ',
        $updateMethodStart + 20,
        [StringComparison]::Ordinal
    )
    if ($updateMethodStart -lt 0 -or $updateMethodEnd -le $updateMethodStart) {
        throw 'Could not inspect updateInstall implementation.'
    }
    $updateMethod = $repositorySource.Substring(
        $updateMethodStart,
        $updateMethodEnd - $updateMethodStart
    )
    $verifyPrismAt = $updateMethod.IndexOf(
        'verifyPrismDistribution(prismRoot(target));',
        [StringComparison]::Ordinal
    )
    $stagingAt = $updateMethod.IndexOf(
        'createStaging(',
        [StringComparison]::Ordinal
    )
    if (
        $verifyPrismAt -lt 0 -or
        $stagingAt -lt 0 -or
        $verifyPrismAt -gt $stagingAt -or
        $updateMethod.Contains('installPrism(') -or
        $updateMethod -match '(?i)accounts(?:\.json)?'
    ) {
        throw (
            'Update must abort on a Prism mismatch before staging, never downgrade ' +
            'Prism, and never access accounts.'
        )
    }

    $runtimeLockEntry = Get-Entry $outer 'SOURCE/bootstrap-java-lock.tsv'
    if ((Get-EntryHash $runtimeLockEntry) -cne $ExpectedBootstrapLockHash) {
        throw 'Bootstrap Java lock is not the pinned canonical Temurin 21 lock.'
    }
    $runtimeLockText = Read-EntryText $runtimeLockEntry
    $runtimeRows = ConvertFrom-LockTsv (
        $runtimeLockText
    ) 'Bootstrap Java lock' $ExpectedRuntimeFiles
    $javaBaseLicense = @(
        $runtimeRows | Where-Object path -CEQ 'legal/java.base/LICENSE'
    )
    if (
        $javaBaseLicense.Count -ne 1 -or
        [long]$javaBaseLicense[0].size -ne 19274 -or
        $javaBaseLicense[0].sha256 -cne (
            '4B9ABEBC4338048A7C2DC184E9F800DEB349366BDF28EB23C2677A77B4C87726'
        )
    ) {
        throw 'Bundled Temurin java.base LICENSE is absent or changed.'
    }
    foreach ($row in $runtimeRows) {
        $entry = Get-Entry $outer ('BOOTSTRAP-JAVA/' + $row.path)
        if (
            [long]$entry.Length -ne [long]$row.size -or
            (Get-EntryHash $entry) -cne $row.sha256
        ) {
            throw "Bundled Java file does not match lock: $($row.path)"
        }
    }
    $runtimeRelease = Read-EntryText (Get-Entry $outer 'BOOTSTRAP-JAVA/release')
    if (
        $runtimeRelease -notmatch '(?m)^JAVA_VERSION="21\.0\.11"\r?$' -or
        $runtimeRelease -notmatch '(?m)^OS_ARCH="x86_64"\r?$' -or
        $runtimeRelease -notmatch '(?m)^OS_NAME="Windows"\r?$' -or
        $runtimeRelease -notmatch '(?m)^IMAGE_TYPE="JRE"\r?$'
    ) {
        throw 'Bundled Java release metadata is not Temurin 21 Windows x64 JRE.'
    }

    $prismLockText = Read-EntryText (Get-Entry $outer 'SOURCE/prism-lock.tsv')
    $repositoryPrismLockText = Get-Content -LiteralPath (
        Join-Path $InstallerRoot 'prism-lock.tsv'
    ) -Raw -Encoding UTF8
    if ($prismLockText -cne $repositoryPrismLockText) {
        throw 'Packaged Prism lock differs from the reviewed repository lock.'
    }
    $prismRows = ConvertFrom-LockTsv $prismLockText 'Prism launcher lock' $ExpectedPrismFiles
    $prismExeRow = @($prismRows | Where-Object path -CEQ 'prismlauncher.exe')
    if (
        $prismExeRow.Count -ne 1 -or
        [long]$prismExeRow[0].size -ne $ExpectedPrismExeSize -or
        $prismExeRow[0].sha256 -cne $ExpectedPrismExeHash -or
        @($prismRows | Where-Object path -CEQ 'portable.txt').Count -ne 1
    ) {
        throw 'Prism lock is not the pinned official 11.0.3 MinGW portable build.'
    }

    $jarEntry = Get-Entry $outer 'GTO-Licensed-Prism-Installer.jar'
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
        Assert-SafeArchiveEntries $jar 'Installer JAR'
        Assert-DeterministicEntryOrder $jar 'Installer JAR' -ManifestFirst
        foreach ($required in @(
            'META-INF/MANIFEST.MF',
            'GtoPrismInstaller.class',
            'downloads.tsv',
            'client-lock.tsv',
            'prism-lock.tsv',
            'bootstrap-java-lock.tsv',
            'PLAY-GTO-LICENSED.bat',
            'README-LICENSED.txt',
            'payload/config/gtocore.yaml',
            'payload/config/defaultoptions-common.toml',
            'payload/mods/gto-terminal-fix-1.0.0.jar',
            'payload/mods/gto-farming-fix-1.0.1.jar',
            'payload/mods/gtocore-forge-1.20.1-0.5.6-beta.jar',
            'payload/mods/gtonativelib-1.0.jar'
        )) {
            [void](Get-Entry $jar $required)
        }
        foreach ($entry in $jar.Entries) {
            if (
                $entry.FullName -match '(?i)(?:^|/)(?:accounts\.json|saves|logs|' +
                    'crash-reports|screenshots|backups|usercache\.json|' +
                    'usernamecache\.json|launcher_profiles\.json)(?:/|$)'
            ) {
                throw "Private data leaked into installer JAR: $($entry.FullName)"
            }
        }

        $manifest = Read-EntryText (Get-Entry $jar 'META-INF/MANIFEST.MF')
        $implementationVersionPattern =
            '(?m)^Implementation-Version: ' +
            [regex]::Escape($Config.packageVersion) +
            '\r?$'
        if (
            $manifest -notmatch '(?m)^Main-Class: GtoPrismInstaller\r?$' -or
            $manifest -notmatch $implementationVersionPattern
        ) {
            throw 'Installer JAR manifest is stale or invalid.'
        }

        $classEntry = Get-Entry $jar 'GtoPrismInstaller.class'
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
        if (
            $header[0] -ne 0xCA -or
            $header[1] -ne 0xFE -or
            $header[2] -ne 0xBA -or
            $header[3] -ne 0xBE
        ) {
            throw 'Installer class magic is invalid.'
        }
        $classMajor = ([int]$header[6] -shl 8) + [int]$header[7]
        if ($classMajor -ne 52) {
            throw "Installer is not Java 8 bytecode. Class major: $classMajor"
        }

        if (
            (Read-EntryText (Get-Entry $jar 'README-LICENSED.txt')) -cne $packagedReadme -or
            (Read-EntryText (Get-Entry $jar 'prism-lock.tsv')) -cne $prismLockText -or
            (Read-EntryText (Get-Entry $jar 'bootstrap-java-lock.tsv')) -cne $runtimeLockText
        ) {
            throw 'Embedded metadata differs from the auditable outer copies.'
        }

        $repositoryPlayBatch = Get-Content -LiteralPath (
            Join-Path $InstallerRoot 'PLAY-GTO-LICENSED.bat'
        ) -Raw -Encoding UTF8
        $playBatchBytes = Read-EntryBytes (Get-Entry $jar 'PLAY-GTO-LICENSED.bat')
        $playBatchText = Assert-AsciiCrlfBatch $playBatchBytes 'Embedded PLAY BAT'
        if (
            $playBatchText -cne (ConvertTo-WindowsBatchText $repositoryPlayBatch) -or
            $playBatchText -notmatch [regex]::Escape(
                'PrismLauncher\prismlauncher.exe'
            )
        ) {
            throw 'Embedded PLAY BAT is stale or invalid.'
        }

        $downloadsText = Read-EntryText (Get-Entry $jar 'downloads.tsv')
        $sourceDownloadsText = Read-EntryText (Get-Entry $outer 'SOURCE/downloads.tsv')
        $repositoryDownloadsText = Get-Content -LiteralPath (
            Join-Path $PSScriptRoot 'tlauncher-installer\downloads.tsv'
        ) -Raw -Encoding UTF8
        if (
            $downloadsText -cne $sourceDownloadsText -or
            $downloadsText -cne $repositoryDownloadsText
        ) {
            throw 'Embedded, source, and repository downloads.tsv copies differ.'
        }
        $downloads = @($downloadsText | ConvertFrom-Csv -Delimiter "`t")
        if ($downloads.Count -ne 171) {
            throw "Expected 171 downloads, found $($downloads.Count)."
        }
        if (@($downloads | Group-Object destination | Where-Object Count -ne 1).Count -gt 0) {
            throw 'Duplicate download destinations.'
        }
        foreach ($download in $downloads) {
            if (
                $download.destination -notmatch '^(?:mods|shaderpacks)/[^/\\:]+$' -or
                $download.url -notmatch '^https://edge\.forgecdn\.net/files/' -or
                $download.sha256 -cnotmatch '^[A-F0-9]{64}$' -or
                [long]$download.size -le 0
            ) {
                throw "Invalid download row: $($download.destination)"
            }
        }

        $clientLockText = Read-EntryText (Get-Entry $jar 'client-lock.tsv')
        $sourceClientLockText = Read-EntryText (Get-Entry $outer 'SOURCE/client-lock.tsv')
        if ($clientLockText -cne $sourceClientLockText) {
            throw 'Embedded and source client locks differ.'
        }
        $expectedClientLockText = Get-ExpectedInstalledClientLockText
        if ($clientLockText -cne $expectedClientLockText) {
            throw 'Installed client lock is not the canonical repository transformation.'
        }
        $clientRows = ConvertFrom-LockTsv $clientLockText 'Installed client lock' 177
        $lockedMods = @($clientRows | Where-Object path -Like 'mods/*')
        if ($lockedMods.Count -ne 174) {
            throw "Expected 174 installed mod files, found $($lockedMods.Count)."
        }
        foreach ($removedTheme in @(
            'mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled',
            'mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled'
        )) {
            if ($clientRows.path -contains $removedTheme) {
                throw "Disabled AE theme remained in installed lock: $removedTheme"
            }
        }
        if ($clientRows.path -notcontains 'shaderpacks/ComplementaryReimagined_r5.6.1.zip') {
            throw 'Complementary shader is absent from installed client lock.'
        }
        foreach ($download in $downloads) {
            $locked = @($clientRows | Where-Object path -CEQ $download.destination)
            if (
                $locked.Count -ne 1 -or
                [long]$locked[0].size -ne [long]$download.size -or
                $locked[0].sha256 -cne $download.sha256
            ) {
                throw "Download does not match installed client lock: $($download.destination)"
            }
        }

        $gtoConfig = Read-EntryText (Get-Entry $jar 'payload/config/gtocore.yaml')
        $gtoConfig = $gtoConfig.Replace("`r`n", "`n").Replace("`r", "`n")
        if ($gtoConfig -cne "gamePlay:`n  difficulty: Easy`n") {
            throw 'Payload does not contain the minimal GTO Easy preset.'
        }
        $defaultOptions = Read-EntryText (
            Get-Entry $jar 'payload/config/defaultoptions-common.toml'
        )
        $defaultOptions = $defaultOptions.Replace("`r`n", "`n").Replace("`r", "`n")
        if (
            [regex]::Matches(
                $defaultOptions,
                '(?m)^[ \t]*defaultDifficulty[ \t]*='
            ).Count -ne 1 -or
            [regex]::Matches(
                $defaultOptions,
                '(?m)^[ \t]*defaultDifficulty[ \t]*=[ \t]*"NORMAL"[ \t]*(?:#.*)?$'
            ).Count -ne 1 -or
            [regex]::Matches(
                $defaultOptions,
                '(?m)^[ \t]*lockDifficulty[ \t]*='
            ).Count -ne 1 -or
            [regex]::Matches(
                $defaultOptions,
                '(?m)^[ \t]*lockDifficulty[ \t]*=[ \t]*false[ \t]*(?:#.*)?$'
            ).Count -ne 1
        ) {
            throw 'Payload vanilla difficulty is not NORMAL and unlocked.'
        }

        $embeddedMods = @(
            $jar.Entries |
                Where-Object {
                    $_.FullName.StartsWith(
                        'payload/mods/',
                        [StringComparison]::Ordinal
                    )
                }
        )
        if ($embeddedMods.Count -ne 4) {
            throw "Only 4 local mod files may be embedded, found $($embeddedMods.Count)."
        }
        foreach ($entry in $embeddedMods) {
            $lockPath = $entry.FullName.Substring('payload/'.Length)
            $locked = @($clientRows | Where-Object path -CEQ $lockPath)
            if (
                $locked.Count -ne 1 -or
                [long]$entry.Length -ne [long]$locked[0].size -or
                (Get-EntryHash $entry) -cne $locked[0].sha256
            ) {
                throw "Embedded mod does not match installed lock: $lockPath"
            }
        }
        $verifiedEmbedded = 0
        foreach ($lockedFile in $clientRows) {
            $matches = @(
                $jar.Entries |
                    Where-Object FullName -CEQ ('payload/' + $lockedFile.path)
            )
            if ($matches.Count -eq 0) {
                continue
            }
            if (
                $matches.Count -ne 1 -or
                [long]$matches[0].Length -ne [long]$lockedFile.size -or
                (Get-EntryHash $matches[0]) -cne $lockedFile.sha256
            ) {
                throw "Embedded payload does not match lock: $($lockedFile.path)"
            }
            $verifiedEmbedded++
        }
        if ($verifiedEmbedded -lt 6) {
            throw "Expected at least 6 locked embedded files, found $verifiedEmbedded."
        }
    }
    finally {
        $jar.Dispose()
        $jarMemory.Dispose()
    }

    $smokeRoot = Join-Path $env:TEMP (
        'GTO-Prism-Verify-' + [Guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $smokeRoot | Out-Null
    try {
        $runtimeRoot = Join-Path $smokeRoot 'BOOTSTRAP-JAVA'
        foreach ($row in $runtimeRows) {
            $destination = Join-Path $runtimeRoot ($row.path.Replace('/', '\'))
            Copy-EntryToFile (
                Get-Entry $outer ('BOOTSTRAP-JAVA/' + $row.path)
            ) $destination
        }
        $jarPath = Join-Path $smokeRoot 'GTO-Licensed-Prism-Installer.jar'
        Copy-EntryToFile $jarEntry $jarPath

        $javaPath = Join-Path $runtimeRoot 'bin\java.exe'
        $javawPath = Join-Path $runtimeRoot 'bin\javaw.exe'
        foreach ($signedJava in @($javaPath, $javawPath)) {
            $signature = Get-AuthenticodeSignature -LiteralPath $signedJava
            if (
                $signature.Status.ToString() -ne 'Valid' -or
                -not $signature.SignerCertificate -or
                $signature.SignerCertificate.Subject -notmatch 'Eclipse\.org Foundation'
            ) {
                throw "Bundled Java signature is invalid: $signedJava"
            }
        }
        $javaVersion = Invoke-CapturedProcess (
            $javaPath
        ) '-XshowSettings:properties -version' $smokeRoot
        if (
            $javaVersion.ExitCode -ne 0 -or
            $javaVersion.Combined -notmatch 'version "21\.0\.11"' -or
            $javaVersion.Combined -notmatch '64-Bit Server VM' -or
            $javaVersion.Combined -notmatch '(?m)^\s*os\.arch\s*=\s*amd64\s*$'
        ) {
            throw "Bundled Java 21 x64 smoke test failed:`n$($javaVersion.Combined)"
        }

        $selfCheck = Invoke-CapturedProcess (
            $javaPath
        ) ('-jar "' + $jarPath.Replace('"', '\"') + '" --self-check') $smokeRoot
        if (
            $selfCheck.ExitCode -ne 0 -or
            $selfCheck.Combined -notmatch 'SELF-CHECK PASSED'
        ) {
            throw "Installer dry self-check failed:`n$($selfCheck.Combined)"
        }

        $batchSmoke = Join-Path $smokeRoot 'batch-smoke'
        New-Item -ItemType Directory -Path $batchSmoke | Out-Null
        $batchPath = Join-Path $batchSmoke 'INSTALL-GTO-LICENSED.bat'
        [IO.File]::WriteAllBytes($batchPath, $installBatchBytes)
        Push-Location $batchSmoke
        try {
            $missingJarOutput = @(
                & $env:ComSpec /d /c 'INSTALL-GTO-LICENSED.bat < nul' 2>&1
            ) -join "`n"
            $missingJarExit = $LASTEXITCODE
        }
        finally {
            Pop-Location
        }
        if (
            $missingJarExit -ne 1 -or
            $missingJarOutput -notmatch 'GTO-Licensed-Prism-Installer\.jar was not found' -or
            $missingJarOutput -match '(?i)not recognized as an internal or external command' -or
            $missingJarOutput -match '(?i)syntax of the command is incorrect'
        ) {
            throw "Installer BAT missing-JAR smoke test failed:`n$missingJarOutput"
        }
        [IO.File]::WriteAllBytes(
            (Join-Path $batchSmoke 'GTO-Licensed-Prism-Installer.jar'),
            [byte[]]@()
        )
        Push-Location $batchSmoke
        try {
            $missingJavaOutput = @(
                & $env:ComSpec /d /c 'INSTALL-GTO-LICENSED.bat < nul' 2>&1
            ) -join "`n"
            $missingJavaExit = $LASTEXITCODE
        }
        finally {
            Pop-Location
        }
        if (
            $missingJavaExit -ne 2 -or
            $missingJavaOutput -notmatch 'bundled Java 21 runtime was not found' -or
            $missingJavaOutput -match '(?i)not recognized as an internal or external command' -or
            $missingJavaOutput -match '(?i)syntax of the command is incorrect'
        ) {
            throw "Installer BAT missing-Java smoke test failed:`n$missingJavaOutput"
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

    Write-Host 'LICENSED PRISM INSTALLER VERIFICATION PASSED'
    Write-Host "Archive: $ArchivePath"
    Write-Host "Outer entries: $($outer.Entries.Count)"
    Write-Host "Bootstrap Java files: $($runtimeRows.Count)"
    Write-Host "Prism lock files: $($prismRows.Count)"
    Write-Host 'Downloads: 171'
    Write-Host 'Installed client lock: 177'
    Write-Host 'Java: Temurin 21.0.11 Windows x64'
    Write-Host "SHA-256: $actualOuterHash"
}
finally {
    $outer.Dispose()
}
