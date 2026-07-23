@echo off
setlocal EnableExtensions
chcp 65001 >nul
title GregTech Odyssey Friends - TLauncher Installer

set "INSTALLER=%~dp0GTO-TLauncher-Installer.jar"
if not exist "%INSTALLER%" (
  echo.
  echo ОШИБКА: рядом с этим файлом нет GTO-TLauncher-Installer.jar
  echo Полностью распакуйте скачанный ZIP и запустите файл снова.
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
  echo Java не найдена.
  echo Установите TLauncher, запустите через него любую версию Minecraft один раз,
  echo закройте игру и повторно откройте этот файл.
  echo.
  pause
  exit /b 2
)

start "" /wait "%JAVA%" -jar "%INSTALLER%"
if errorlevel 1 (
  echo.
  echo Установщик завершился с ошибкой.
  echo Откройте README-TLAUNCHER.txt или сообщите владельцу сборки.
  echo.
  pause
  exit /b 3
)

endlocal
