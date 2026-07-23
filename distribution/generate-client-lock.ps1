[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MinecraftDirectory,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pack-config.json') `
    -Raw -Encoding UTF8 |
    ConvertFrom-Json
if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot 'CLIENT-MOD-LOCK.json'
}
$MinecraftDirectory = (Resolve-Path -LiteralPath $MinecraftDirectory).Path
$modsDirectory = Join-Path $MinecraftDirectory 'mods'
if (-not (Test-Path -LiteralPath $modsDirectory -PathType Container)) {
    throw "Minecraft mods directory not found: $modsDirectory"
}

$trackedFiles = @(
    Get-ChildItem -LiteralPath $modsDirectory -File |
        Sort-Object Name |
        ForEach-Object {
            [pscustomobject]@{
                file = $_
                relativePath = 'mods/' + $_.Name
                kind = 'mod'
                state = if ($_.Name.EndsWith('.disabled', [StringComparison]::OrdinalIgnoreCase)) {
                    'disabled'
                }
                else {
                    'enabled'
                }
            }
        }
)

foreach ($resourceName in @(
    'z-gto-lang-all-locales-gto-0.5.6-r3.zip',
    'z-gto-russian-fixes-0.5.6-v1.0.0.zip'
)) {
    $resourcePath = Join-Path $MinecraftDirectory "config\openloader\resources\$resourceName"
    if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) {
        throw "Required OpenLoader resource pack not found: $resourcePath"
    }
    $trackedFiles += [pscustomobject]@{
        file = Get-Item -LiteralPath $resourcePath
        relativePath = "config/openloader/resources/$resourceName"
        kind = 'resource-pack'
        state = 'enabled'
    }
}

$locallyBuiltFiles = @(
    [pscustomobject]@{
        relativePath = 'mods/gto-terminal-fix-1.0.0.jar'
        source = Join-Path $RepoRoot 'gto-terminal-fix\build\libs\gto_terminal_fix-1.0.0.jar'
    },
    [pscustomobject]@{
        relativePath = 'mods/gto-farming-fix-1.0.1.jar'
        source = Join-Path $RepoRoot 'gto-farming-fix\build\libs\gto_farming_fix-1.0.1.jar'
    },
    [pscustomobject]@{
        relativePath = 'config/openloader/resources/z-gto-russian-fixes-0.5.6-v1.0.0.zip'
        source = Join-Path $RepoRoot 'gto-russian-fixes\dist\z-gto-russian-fixes-0.5.6-v1.0.0.zip'
    }
)
foreach ($localFile in $locallyBuiltFiles) {
    if (-not (Test-Path -LiteralPath $localFile.source -PathType Leaf)) {
        throw "Build local artifacts before generating the lock: $($localFile.source)"
    }
    $tracked = @($trackedFiles | Where-Object relativePath -eq $localFile.relativePath)
    if ($tracked.Count -ne 1) {
        throw "Expected one client entry for local artifact: $($localFile.relativePath)"
    }
    $tracked[0].file = Get-Item -LiteralPath $localFile.source
}

$entries = @(
    $trackedFiles |
        Sort-Object relativePath |
        ForEach-Object {
            [ordered]@{
                path = $_.relativePath
                kind = $_.kind
                state = $_.state
                size = [long]$_.file.Length
                sha256 = (Get-FileHash -LiteralPath $_.file.FullName -Algorithm SHA256).Hash
            }
        }
)

$lock = [ordered]@{
    schema = 'gto-friends-client-lock/v1'
    package = [ordered]@{
        name = 'GregTech Odyssey — Friends Edition'
        version = [string]$Config.packageVersion
        minecraft = '1.20.1'
        forge = '47.4.20'
    }
    policy = [ordered]@{
        curseForgeFilesArePinnedByProjectAndFileId = $true
        hashesAreUppercaseSha256 = $true
        extraFilesInModsAreRejected = $true
        disabledFileSuffixIsSignificant = $true
    }
    counts = [ordered]@{
        mods = @($entries | Where-Object kind -eq 'mod').Count
        resourcePacks = @($entries | Where-Object kind -eq 'resource-pack').Count
        total = $entries.Count
    }
    files = $entries
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText(
    $OutputPath,
    ($lock | ConvertTo-Json -Depth 10) + "`n",
    $utf8NoBom
)

Write-Host "Client lock written: $OutputPath"
Write-Host "Tracked mods: $($lock.counts.mods)"
Write-Host "Tracked resource packs: $($lock.counts.resourcePacks)"
