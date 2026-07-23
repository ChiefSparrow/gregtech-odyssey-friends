@echo off
setlocal EnableExtensions DisableDelayedExpansion
title GregTech Odyssey Friends - TLauncher Installer

set "INSTALLER=%~dp0GTO-TLauncher-Installer.jar"
if not exist "%INSTALLER%" (
  echo.
  echo ERROR: GTO-TLauncher-Installer.jar was not found next to this file.
  echo Extract the complete downloaded ZIP, then run this file again.
  echo.
  pause
  exit /b 1
)

set "JAVA="
for /f "delims=" %%J in ('where javaw.exe 2^>nul') do if not defined JAVA set "JAVA=%%J"

if not defined JAVA if exist "%APPDATA%\.minecraft\runtime" (
  for /r "%APPDATA%\.minecraft\runtime" %%J in (javaw.exe) do if exist "%%~fJ" if not defined JAVA set "JAVA=%%~fJ"
)
if not defined JAVA if exist "%APPDATA%\.tlauncher" (
  for /r "%APPDATA%\.tlauncher" %%J in (javaw.exe) do if exist "%%~fJ" if not defined JAVA set "JAVA=%%~fJ"
)
if not defined JAVA if exist "%LOCALAPPDATA%\TLauncher" (
  for /r "%LOCALAPPDATA%\TLauncher" %%J in (javaw.exe) do if exist "%%~fJ" if not defined JAVA set "JAVA=%%~fJ"
)

if not defined JAVA (
  echo.
  echo Java was not found.
  echo Install TLauncher and start any Minecraft version through it once.
  echo Close the game and TLauncher, then run this file again.
  echo.
  pause
  exit /b 2
)

start "" /wait "%JAVA%" -jar "%INSTALLER%"
if errorlevel 1 (
  echo.
  echo The installer exited with an error.
  echo Read README-TLAUNCHER.txt or contact the modpack owner.
  echo.
  pause
  exit /b 3
)

endlocal
