@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM Run from installed app folder, for example:
REM C:\Program Files\tj.khujand.solana.trading.bot\run-telegram-bot-installed.bat

set "APP_HOME=%~dp0"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"

set "JAVA_EXE=%APP_HOME%\runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
    set "JAVA_EXE=java"
)

if "%TELEGRAM_BOT_TOKEN%"=="" (
    echo [ERROR] TELEGRAM_BOT_TOKEN is not set.
    echo.
    echo PowerShell example:
    echo   $env:TELEGRAM_BOT_TOKEN="YOUR_TOKEN"
    echo   $env:TELEGRAM_ADMIN_CHAT_ID="7629981910"
    echo   $env:TELEGRAM_ADMIN_USER_ID="7629981910"
    echo.
    echo Then run this file again.
    pause
    exit /b 1
)

set "APP_JARS="
for %%f in ("%APP_HOME%\app\*.jar") do (
    if defined APP_JARS (
        set "APP_JARS=!APP_JARS!;%%~ff"
    ) else (
        set "APP_JARS=%%~ff"
    )
)

if not defined APP_JARS (
    echo [ERROR] Could not find JAR files in "%APP_HOME%\app".
    echo Make sure this script is in installed app root folder.
    pause
    exit /b 1
)

echo Starting Telegram bot...
echo App home: "%APP_HOME%"
echo.

"%JAVA_EXE%" -cp "%APP_JARS%" tj.khujand.solana.trading.bot.TelegramBotMainKt
set "BOT_EXIT=%ERRORLEVEL%"

echo.
echo Telegram bot stopped. Exit code: %BOT_EXIT%
pause
exit /b %BOT_EXIT%
