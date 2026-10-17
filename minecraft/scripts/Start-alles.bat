@echo off
title Start alles: MPServerSMP, Velocity en website
rem Een klik voor alles:
rem   1. sluit draaiende servers en de website netjes af
rem   2. maakt een backup (zip) van de hele hoofdmap
rem   3. start MPServerSMP, Velocity en de website als tabbladen in een venster
rem Werkt vanaf elke stationsletter: alles staat naast dit bestand.

rem ============================================================rem  Stap 1: servers en website afsluiten

rem ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Resources\stop-servers.ps1"
if errorlevel 1 (
  echo.
  echo Afsluiten is niet gelukt. Er wordt GEEN backup gemaakt en niets gestart.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Resources\stop-website.ps1"
echo.

rem ============================================================
rem  Stap 2: backup van de hele hoofdmap
rem  Komt in \Backups\<naam van de hoofdmap> op dezelfde schijf.
rem  De BlueMap-kaartbestanden en de map Backups gaan niet mee.
rem  KEEP = hoeveel backups bewaard blijven.
rem ============================================================
set "KEEP=10"
set "SRV=%~dp0Velocity MPServer SMP"
for %%I in ("%~dp0.") do set "MSPNAME=%%~nxI"
for %%I in ("%~dp0..") do set "PARENT=%%~fI"
if "%PARENT:~-1%"=="\" set "PARENT=%PARENT%."
set "DEST=%~d0\Backups\%MSPNAME%"
if not exist "%DEST%" mkdir "%DEST%"
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HH-mm"') do set "STAMP=%%T"
set "ZIP=%DEST%\MPServerSMP-backup_%STAMP%.zip"

echo Backup maken van "%MSPNAME%" naar "%ZIP%" ...
tar -a -c -f "%ZIP%" --exclude "*/MPServerSMP/bluemap" --exclude "%MSPNAME%/Backups" -C "%PARENT%" "%MSPNAME%"
if errorlevel 1 (
  echo LET OP: de backup gaf een waarschuwing. Draait er nog een server? Zet die eerst uit.
) else (
  echo Backup klaar.
)
powershell -NoProfile -Command "Get-ChildItem -LiteralPath '%DEST%' -Filter 'MPServerSMP-backup_*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -Skip %KEEP% | Remove-Item -Force"
echo.

rem ============================================================
rem  Eigen plugins bijwerken: de nieuwste jars uit Resources\eigen-plugins
rem  gaan naar MPServerSMP\plugins (de servers staan nu uit).
rem ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Resources\eigen-plugins-bijwerken.ps1"
echo.

rem ============================================================
rem  Stap 3: alles starten
rem  Met Windows Terminal komen alle drie als tabbladen in EEN venster
rem  (venster MPServerSMP). Zonder Windows Terminal krijgt elk een eigen venster.
rem ============================================================
where wt >nul 2>nul
if errorlevel 1 goto losse_vensters

echo MPServerSMP starten...
wt -w MPServerSMP new-tab --title "MPServerSMP" --suppressApplicationTitle -d "%SRV%\MPServerSMP" cmd /c Start.bat
timeout /t 15 /nobreak >nul
echo Velocity starten...
wt -w MPServerSMP new-tab --title "Velocity" --suppressApplicationTitle -d "%SRV%\Velocity" cmd /c Start.bat
timeout /t 2 /nobreak >nul
echo Website starten...
wt -w MPServerSMP new-tab --title "Website" --suppressApplicationTitle -d "%~dp0." cmd /c Start-website.bat
goto klaar

:losse_vensters
echo Windows Terminal niet gevonden: elk programma krijgt een eigen venster.
echo MPServerSMP starten...
start "MPServerSMP" /D "%SRV%\MPServerSMP" cmd /c Start.bat
timeout /t 15 /nobreak >nul
echo Velocity starten...
start "Velocity" /D "%SRV%\Velocity" cmd /c Start.bat
echo Website starten...
start "MPServer SMP website" /D "%~dp0" cmd /c "%~dp0Start-website.bat"

:klaar
echo.
echo Alles is gestart. Dit venster sluit over 10 seconden.
timeout /t 10 >nul
