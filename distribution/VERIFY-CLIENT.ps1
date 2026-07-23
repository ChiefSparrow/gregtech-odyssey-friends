[CmdletBinding()]
param(
    [string]$MinecraftDirectory,
    [string]$LockPath,
    [switch]$AllowExtraMods
)

$ErrorActionPreference = 'Stop'
if (-not $MinecraftDirectory) {
    $MinecraftDirectory = $PSScriptRoot
}
if (-not $LockPath) {
    $LockPath = Join-Path $PSScriptRoot 'CLIENT-MOD-LOCK.json'
}

if (-not (Test-Path -LiteralPath $MinecraftDirectory -PathType Container)) {
    throw "Minecraft directory not found: $MinecraftDirectory"
}
if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) {
    throw "Client lock file not found: $LockPath"
}

$MinecraftDirectory = (Resolve-Path -LiteralPath $MinecraftDirectory).Path
$LockPath = (Resolve-Path -LiteralPath $LockPath).Path
$lock = Get-Content -LiteralPath $LockPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($lock.schema -ne 'gto-friends-client-lock/v1') {
    throw "Unsupported client lock schema: $($lock.schema)"
}

$errors = New-Object Collections.Generic.List[string]
$expectedModPaths = @{}
$checked = 0

foreach ($entry in $lock.files) {
    $relativeWindowsPath = $entry.path.Replace('/', '\')
    $absolutePath = Join-Path $MinecraftDirectory $relativeWindowsPath

    if ($entry.kind -eq 'mod') {
        $expectedModPaths[$entry.path.ToLowerInvariant()] = $true
    }

    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        $errors.Add("MISSING: $($entry.path)")
        continue
    }

    $file = Get-Item -LiteralPath $absolutePath
    if ([long]$file.Length -ne [long]$entry.size) {
        $errors.Add("SIZE: $($entry.path) expected $($entry.size), got $($file.Length)")
        continue
    }

    $actualHash = (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash
    if ($actualHash -ne $entry.sha256) {
        $errors.Add("SHA256: $($entry.path) expected $($entry.sha256), got $actualHash")
        continue
    }

    $checked++
}

$modsDirectory = Join-Path $MinecraftDirectory 'mods'
if (-not (Test-Path -LiteralPath $modsDirectory -PathType Container)) {
    $errors.Add('MISSING: mods/')
}
elseif (-not $AllowExtraMods) {
    foreach ($file in Get-ChildItem -LiteralPath $modsDirectory -File) {
        $relative = ('mods/' + $file.Name).ToLowerInvariant()
        if (-not $expectedModPaths.ContainsKey($relative)) {
            $errors.Add("EXTRA: mods/$($file.Name)")
        }
    }
}

$gtoConfigPath = Join-Path $MinecraftDirectory 'config\gtocore.yaml'
if (-not (Test-Path -LiteralPath $gtoConfigPath -PathType Leaf)) {
    $errors.Add('MISSING: config/gtocore.yaml')
}
else {
    $gtoConfig = Get-Content -LiteralPath $gtoConfigPath -Raw -Encoding UTF8
    $gtoConfig = $gtoConfig.Replace("`r`n", "`n").Replace("`r", "`n")
    $gtoLines = $gtoConfig.Split("`n")
    $directChildIndentation = $null
    $gamePlaySections = 0
    $inGamePlay = $false
    foreach ($line in $gtoLines) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $indentation = ([regex]::Match($line, '^[ \t]*')).Length
        if ($indentation -eq 0) {
            $inGamePlay = $trimmed -eq 'gamePlay:'
            if ($inGamePlay) {
                $gamePlaySections++
            }
        }
        elseif (
            $inGamePlay -and
            ($null -eq $directChildIndentation -or $indentation -lt $directChildIndentation)
        ) {
            $directChildIndentation = $indentation
        }
    }
    $easyMatches = 0
    $wrongGamePlayDifficulty = $false
    $inGamePlay = $false
    foreach ($line in $gtoLines) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $indentation = ([regex]::Match($line, '^[ \t]*')).Length
        if ($indentation -eq 0) {
            $inGamePlay = $trimmed -eq 'gamePlay:'
            continue
        }
        if (
            $inGamePlay -and
            $indentation -eq $directChildIndentation -and
            $trimmed -match '^difficulty[ \t]*:'
        ) {
            $value = ($trimmed -replace '^difficulty[ \t]*:[ \t]*', '') -replace '[ \t]*#.*$', ''
            if ($value.Trim() -eq 'Easy') {
                $easyMatches++
            }
            else {
                $wrongGamePlayDifficulty = $true
            }
        }
    }
    if (
        $gamePlaySections -ne 1 -or
        $easyMatches -ne 1 -or
        $wrongGamePlayDifficulty
    ) {
        $errors.Add('CONFIG: GTO pack mode must be Easy')
    }
}

$defaultOptionsPath = Join-Path $MinecraftDirectory 'config\defaultoptions-common.toml'
if (-not (Test-Path -LiteralPath $defaultOptionsPath -PathType Leaf)) {
    $errors.Add('MISSING: config/defaultoptions-common.toml')
}
else {
    $defaultOptions = Get-Content -LiteralPath $defaultOptionsPath -Raw -Encoding UTF8
    $defaultOptions = $defaultOptions.Replace("`r`n", "`n").Replace("`r", "`n")
    $normalLines = [regex]::Matches(
        $defaultOptions,
        '(?m)^[ \t]*defaultDifficulty[ \t]*=[ \t]*"NORMAL"[ \t]*(?:#.*)?$'
    )
    $allDifficultyLines = [regex]::Matches(
        $defaultOptions,
        '(?m)^[ \t]*defaultDifficulty[ \t]*='
    )
    $unlockedLines = [regex]::Matches(
        $defaultOptions,
        '(?m)^[ \t]*lockDifficulty[ \t]*=[ \t]*false[ \t]*(?:#.*)?$'
    )
    $allLockLines = [regex]::Matches(
        $defaultOptions,
        '(?m)^[ \t]*lockDifficulty[ \t]*='
    )
    if (
        $allDifficultyLines.Count -ne 1 -or
        $normalLines.Count -ne 1 -or
        $allLockLines.Count -ne 1 -or
        $unlockedLines.Count -ne 1
    ) {
        $errors.Add('CONFIG: vanilla difficulty default must be NORMAL and unlocked')
    }
}

if ($errors.Count -gt 0) {
    Write-Host 'CLIENT VERIFICATION FAILED' -ForegroundColor Red
    foreach ($message in $errors) {
        Write-Host " - $message" -ForegroundColor Red
    }
    Write-Host "Problems: $($errors.Count)"
    exit 1
}

Write-Host 'CLIENT VERIFICATION PASSED' -ForegroundColor Green
Write-Host "Package: $($lock.package.name) $($lock.package.version)"
Write-Host "Verified files: $checked"
Write-Host "Mods: $($lock.counts.mods) (enabled and disabled state is exact)"
exit 0
