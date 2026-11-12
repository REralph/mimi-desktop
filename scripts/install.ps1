# Builds a standalone Mimi (no Gradle, no console needed to run it), installs it for the
# current user, and puts shortcuts on the Desktop and in the Start menu. Run it again any time to
# update: it replaces the installed copy and keeps the shortcuts. Your pairing and phone data
# (%APPDATA%\a2-desktop) are never touched.
#
#   install.bat            build + install
#   install.bat -NoBuild   re-install the last build without rebuilding
param([switch]$NoBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appName = 'Mimi'
$oldName = 'a2 Desktop' # what the app was called before it became Mimi: cleaned up below
$installDir = Join-Path $env:LOCALAPPDATA "Programs\$appName"
$built = Join-Path $root "build\compose\binaries\main\app\$appName"

if (-not $NoBuild) {
    Write-Host "Building $appName (first time takes a few minutes)..."
    & (Join-Path $root 'gradlew.bat') -p $root createDistributable --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'The build failed - nothing was installed.' }
}
if (-not (Test-Path (Join-Path $built "$appName.exe"))) { throw "No build found at $built" }

# The app used to be called "a2 Desktop": close it, and remove its old install folder and shortcuts, so
# there aren't two of them. (Your pairing and phone data live in %APPDATA%\a2-desktop and are not touched.)
$oldProcesses = Get-Process -Name $oldName -ErrorAction SilentlyContinue
if ($oldProcesses) {
    $oldProcesses | Stop-Process -Force
    $oldProcesses | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue # its files stay locked until it has really exited
}
$oldInstall = Join-Path $env:LOCALAPPDATA "Programs\$oldName"
if (Test-Path $oldInstall) {
    for ($try = 1; $try -le 5 -and (Test-Path $oldInstall); $try++) {
        try { Remove-Item -Recurse -Force $oldInstall -ErrorAction Stop } catch { Start-Sleep -Seconds 2 }
    }
    if (Test-Path $oldInstall) { throw "Couldn't remove the old '$oldName' install at $oldInstall - close it and run this again." }
    Write-Host "Removed the old '$oldName' install."
}
foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $oldLink = Join-Path $folder "$oldName.lnk"
    if (Test-Path $oldLink) { Remove-Item -Force $oldLink; Write-Host "Removed the old shortcut $oldLink" }
}

# An update can't overwrite a running program, so close it first.
$running = Get-Process -Name $appName -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Closing the running $appName to update it..."
    $running | Stop-Process -Force
    Start-Sleep -Seconds 2
}

Write-Host "Installing to $installDir ..."
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
robocopy $built $installDir /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "Copying the files failed (robocopy exit code $LASTEXITCODE)." }

$exe = Join-Path $installDir "$appName.exe"
$shell = New-Object -ComObject WScript.Shell
foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $link = $shell.CreateShortcut((Join-Path $folder "$appName.lnk"))
    $link.TargetPath = $exe
    $link.WorkingDirectory = $installDir
    $link.IconLocation = "$exe,0"
    $link.Description = 'Desktop companion to the Mimi phone app'
    $link.Save()
    Write-Host "Shortcut: $($link.FullName)"
}
Write-Host "Done. Open '$appName' from your Desktop."
$global:LASTEXITCODE = 0
