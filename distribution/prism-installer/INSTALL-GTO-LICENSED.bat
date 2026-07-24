@echo off
setlocal EnableExtensions DisableDelayedExpansion
title GregTech Odyssey Friends - Licensed Installer

set "INSTALLER=%~dp0GTO-Licensed-Prism-Installer.jar"
set "JAVA=%~dp0BOOTSTRAP-JAVA\bin\javaw.exe"
set "_JAVA_OPTIONS="
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "CLASSPATH="
set "PRISMLAUNCHER_DATA_DIR="
if not exist "%INSTALLER%" (
  echo.
  echo ERROR: GTO-Licensed-Prism-Installer.jar was not found next to this file.
  echo Extract the complete downloaded ZIP, then run this file again.
  echo.
  pause
  exit /b 1
)

if not exist "%JAVA%" (
  echo.
  echo ERROR: The bundled Java 21 runtime was not found.
  echo Extract the complete downloaded ZIP, then run this file again.
  echo.
  pause
  exit /b 2
)

start "" /wait "%JAVA%" -jar "%INSTALLER%"
if errorlevel 1 (
  echo.
  echo The installer exited with an error.
  echo Read README-LICENSED.txt or contact the modpack owner.
  echo.
  pause
  exit /b 3
)

endlocal
