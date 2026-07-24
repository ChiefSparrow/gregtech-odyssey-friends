@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "PRISM=%~dp0PrismLauncher\prismlauncher.exe"
set "USERDATA=%~dp0PrismLauncher\UserData"
set "INSTANCE=%USERDATA%\instances\GTO-Friends\instance.cfg"
set "PRISMLAUNCHER_DATA_DIR="
set "_JAVA_OPTIONS="
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "CLASSPATH="
if not exist "%PRISM%" (
  echo Prism Launcher was not found. Run the licensed GTO installer again.
  pause
  exit /b 1
)
if not exist "%INSTANCE%" (
  echo The GTO Friends instance was not found. Run the licensed GTO installer again.
  pause
  exit /b 2
)
start "" /D "%~dp0PrismLauncher" "%PRISM%" --show GTO-Friends
endlocal
