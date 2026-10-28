@echo off
title WoWChat Discord Bot
pushd "%~dp0"

echo.
echo  ============================================
echo   WoWChat Discord Bot
echo  ============================================
echo.

:: ── Java check ───────────────────────────────────────────────────────────────
call :find_java
if defined WOWCHAT_JAVA goto java_ok

echo  [!] Java not found. Installing...
echo.

:: Try winget via full path (Windows 10/11, avoids PATH lookup issues)
set "WINGET=%LOCALAPPDATA%\Microsoft\WindowsApps\winget.exe"
if exist "%WINGET%" (
    "%WINGET%" install --id EclipseAdoptium.Temurin.8.JRE --silent --accept-package-agreements --accept-source-agreements
    call :find_java
)
if defined WOWCHAT_JAVA goto java_installed
if exist "%WINGET%" (
    echo.
    echo  [!] winget install failed, trying PowerShell download...
    echo.
)

:: Fallback: download and install the Java 8 runtime via PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $d=$env:TEMP+'\java_setup.msi'; Write-Host '  Downloading Java...'; (New-Object Net.WebClient).DownloadFile('https://api.adoptium.net/v3/installer/latest/8/ga/windows/x64/jre/hotspot/normal/eclipse',$d); Write-Host '  Installing (a UAC prompt may appear)...'; Start-Process msiexec -ArgumentList @('/i',$d,'/quiet','/norestart') -Wait -Verb RunAs"
call :find_java
if defined WOWCHAT_JAVA goto java_installed

echo.
echo  [!] Automatic install failed.
echo      Please install Java manually from https://adoptium.net/
echo      Then run this file again.
echo.
pause
exit /b 1

:java_installed
echo.
echo  Java installed successfully.
echo.

:java_ok

:: ── Config check ──────────────────────────────────────────────────────────────
if not exist "wowchat.conf" (
    echo  [!] wowchat.conf not found.
    echo.
    echo      It should be in the same folder as this file.
    echo.
    pause
    exit /b 1
)

:check_config
findstr /R /C:"=.*FILL_ME_IN" wowchat.conf >nul 2>&1
if %errorlevel% equ 0 (
    echo  [!] wowchat.conf is not configured yet.
    echo.
    echo      Opening it for you now. Fill in every FILL_ME_IN value, save the file,
    echo      then press any key here to continue.
    echo.
    echo        Discord bot token:  from discord.com/developers ^> your app ^> Bot
    echo        WoW server:         realmlist, realm name and game version
    echo        WoW account:        account, password and the bot's character
    echo        Guild chat channel: right-click the Discord channel ^> Copy Channel ID
    echo.
    start notepad wowchat.conf
    pause
    goto check_config
)

:: ── Start ─────────────────────────────────────────────────────────────────────
:: Runs in the system tray by default. Use "Start WoWChat.bat" console to run in this window instead.
if /i not "%~1"=="console" if exist "lib\wowchat_tray.ps1" (
    echo  Starting bot in the system tray...
    echo  Right-click the tray icon to view the log, restart, or stop the bot.
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lib\wowchat_tray.ps1" -Detach
    exit /b
)

echo  Starting bot... ^(Ctrl+C to stop^)
echo.
"%WOWCHAT_JAVA%" -Dfile.encoding=UTF-8 -jar lib\watchdog.jar lib\wowchat.jar wowchat.conf
set "JAVA_EXIT=%errorlevel%"
echo.
echo  ============================================
if %JAVA_EXIT% neq 0 (
    echo  [!] Bot stopped with error code %JAVA_EXIT%.
    echo      Check the output above for details.
) else (
    echo  Bot stopped cleanly.
)
echo  ============================================
echo.
pause
exit /b

:: ── Helpers ───────────────────────────────────────────────────────────────────
:: Sets WOWCHAT_JAVA to a java.exe: PATH first, then the usual install folders
:: (a fresh install isn't on this window's PATH yet)
:find_java
set "WOWCHAT_JAVA="
for /f "delims=" %%J in ('where java 2^>nul') do if not defined WOWCHAT_JAVA set "WOWCHAT_JAVA=%%J"
if defined WOWCHAT_JAVA exit /b
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Java\*" "%ProgramFiles%\Microsoft\jdk*" "%ProgramFiles%\Zulu\*") do (
    if not defined WOWCHAT_JAVA if exist "%%D\bin\java.exe" set "WOWCHAT_JAVA=%%D\bin\java.exe"
)
exit /b
