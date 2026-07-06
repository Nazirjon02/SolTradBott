@echo off
chcp 65001 >nul
title DRX Setup
echo ============================================================
echo   DRX Trading Bot - Первоначальная настройка
echo ============================================================
echo.

REM Создаём .env из шаблона, если ещё не существует
if exist ".env" (
    echo [!] .env уже существует. Открыть для редактирования? [Y/N]
    set /p EDIT_ENV=
    if /i "%EDIT_ENV%"=="Y" notepad .env
    goto :CHECK_JAVA
)

if not exist ".env.example" (
    echo [!] Шаблон .env.example не найден. Проверь что проект скачан полностью.
    pause
    exit /b 1
)

copy ".env.example" ".env" >nul
echo [OK] .env создан из шаблона.
echo.
echo Сейчас откроется Блокнот - заполни:
echo   - TG_BOT_TOKEN        (Telegram @BotFather -^> /newbot)
echo   - TG_CHAT_ID          (Telegram @userinfobot)
echo   - DEMO_MODE=true      (оставь true, пока не уверен!)
echo.
echo SOLANA_WALLET_SEED нужен только для REAL-режима.
echo После заполнения - сохрани файл (Ctrl+S) и закрой Блокнот.
echo.
pause
notepad .env

:CHECK_JAVA
echo.
echo Проверяю Java...
where java >nul 2>nul
if errorlevel 1 (
    echo [!] Java НЕ найдена в PATH.
    echo [!] Установи JDK 17+ с https://adoptium.net и перезапусти этот скрипт.
    pause
    exit /b 1
)
java -version 2>&1 | findstr /R "version"
echo.

echo ============================================================
echo Настройка завершена.
echo.
echo Что дальше:
echo   1. Запустить бот:        дабл-клик по start.bat
echo   2. Запустить Desktop UI: дабл-клик по desktop.bat
echo   3. Поменять настройки:   notepad .env
echo ============================================================
pause
