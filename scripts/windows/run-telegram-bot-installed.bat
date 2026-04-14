@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM Run from installed app folder, for example:
REM C:\Program Files\tj.khujand.solana.trading.bot\run-telegram-bot-installed.bat
REM Simplest: first run may open Notepad with %USERPROFILE%\.soltradbot\telegram-bot.properties — fill token, save, run this file again.

set "APP_HOME=%~dp0"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"

set "JAVA_EXE=%APP_HOME%\runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
    set "JAVA_EXE=java"
)

set "USER_BOT_CONFIG=%USERPROFILE%\.soltradbot\telegram-bot.properties"

REM Token: TELEGRAM_BOT_TOKEN env, OR telegram-bot.properties (here or in %USER_BOT_CONFIG%), OR Filters screen in desktop app + save.

if not defined TELEGRAM_BOT_TOKEN (
    if not exist "%APP_HOME%\telegram-bot.properties" (
        if not exist "%USER_BOT_CONFIG%" (
            mkdir "%USERPROFILE%\.soltradbot" 2>nul
            if exist "%APP_HOME%\telegram-bot.properties.example" (
                copy /y "%APP_HOME%\telegram-bot.properties.example" "%USER_BOT_CONFIG%" >nul
            ) else (
                (
                    echo # Paste token from @BotFather and your Telegram IDs
                    echo telegram.bot.token=
                    echo telegram.admin.chat.id=
                    echo telegram.admin.user.id=
                ) > "%USER_BOT_CONFIG%"
            )
            echo.
            echo First-time setup: edit config file and save, then run this script again.
            echo File: "%USER_BOT_CONFIG%"
            echo.
            notepad "%USER_BOT_CONFIG%"
            echo.
            pause
            exit /b 0
        )
    )
)

if not exist "%APP_HOME%\app\*.jar" (
    echo [ERROR] Could not find JAR files in "%APP_HOME%\app".
    echo Make sure this script is in installed app root folder.
    pause
    exit /b 1
)

echo Starting Telegram bot...
echo App home: "%APP_HOME%"
echo.

cd /d "%APP_HOME%"

"%JAVA_EXE%" -cp "%APP_HOME%\app\*" tj.khujand.solana.trading.bot.TelegramBotMainKt
set "BOT_EXIT=%ERRORLEVEL%"

echo.
echo Telegram bot stopped. Exit code: %BOT_EXIT%
pause
exit /b %BOT_EXIT%
