@echo off
:: Включаем поддержку UTF-8 для красивых эмодзи и русского языка в консоли
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: Конфигурация
set DB_CONTAINER=shortener-db
set NUM_ROWS=50000
set CODES_FILE=shortcodes.txt

echo 🚀 Старт нагрузочного тестирования URL Shortener

:: 1. Проверяем, жив ли контейнер БД
docker ps --format "{{.Names}}" | findstr /R /C:"^%DB_CONTAINER%$" >nul
if errorlevel 1 (
    echo ❌ Ошибка: Контейнер '%DB_CONTAINER%' не запущен! Убедись, что docker-compose up выполнен.
    exit /b 1
)

echo 📦 1. Генерация %NUM_ROWS% сидов в базу PostgreSQL...
:: Запускаем SQL скрипт и перехватываем вывод в файл (работает нативно в Windows CMD)
docker exec -i %DB_CONTAINER% psql -U postgres -d shortener -v num_rows=%NUM_ROWS% -q -A -f - < seed.sql > %CODES_FILE%

:: Подсчитываем количество строк в файле
for /f %%A in ('find /c /v "" ^< %CODES_FILE%') do set ACTUAL_ROWS=%%A
echo ✅ Сгенерировано и сохранено кодов: !ACTUAL_ROWS!

if "!ACTUAL_ROWS!"=="0" (
    echo ❌ Ошибка генерации кодов. Файл пуст. Завершение.
    exit /b 1
)

echo 🔥 2. Запуск k6 (Warmup + Load)...
:: Устанавливаем переменные среды локально для этой сессии
set K6_WEB_DASHBOARD=true
set K6_WEB_DASHBOARD_EXPORT=report.html
set BASE_URL=http://localhost:8080/api/v1

:: Запускаем сам k6
k6 run main.js

echo 🎉 Тестирование завершено! Отчет сохранен в report.html.
pause
