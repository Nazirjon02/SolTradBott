@echo off
chcp 65001 >nul
title DRX Server
echo ============================================================
echo   DRX Trading Bot - Server
echo ============================================================
echo.

REM Проверяем что .env существует
if not exist ".env" (
    echo [!] Файл .env не найден.
    echo [!] Запусти setup.bat или скопируй .env.example в .env.
    echo.
    pause
    exit /b 1
)

REM Проверяем Java
where java >nul 2>nul
if errorlevel 1 (
    echo [!] Java не найдена в PATH.
    echo [!] Установи JDK 17+ с https://adoptium.net
    pause
    exit /b 1
)

echo [OK] .env найден, Java найдена.
echo.
echo Запускаю сервер... (Ctrl+C чтобы остановить)
echo После "Responding at..." сервер готов.
echo Управление: Telegram (если задан TG_BOT_TOKEN)
echo            или curl http://localhost:8080/health
echo ============================================================
echo.

REM Запускаем сервер. .env подхватится автоматически из текущей папки.
call gradlew.bat :server:run --console=plain

echo.
echo Сервер остановлен.
pause
