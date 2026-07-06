@echo off
chcp 65001 >nul
title DRX Desktop UI
echo ============================================================
echo   DRX Trading Bot - Desktop UI
echo ============================================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [!] Java не найдена. Установи JDK 17+ с https://adoptium.net
    pause
    exit /b 1
)

echo [OK] Запускаю Desktop-приложение...
echo Чтобы делить БД с сервером - задай одинаковый DB_PATH в .env.
echo ============================================================
echo.

call gradlew.bat :desktopApp:run --console=plain

pause
