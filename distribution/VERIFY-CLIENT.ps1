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
