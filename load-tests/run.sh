#!/usr/bin/env bash

# Включаем строгий режим (падает при любой ошибке)
set -euo pipefail

# Конфигурация
DB_CONTAINER="shortener-db"
NUM_ROWS=50000
CODES_FILE="shortcodes.txt"

echo "🚀 Старт нагрузочного тестирования URL Shortener"

# 1. Проверяем, жив ли контейнер БД
if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
  echo "❌ Ошибка: Контейнер '$DB_CONTAINER' не запущен!"
  exit 1
fi

echo "📦 1. Генерация $NUM_ROWS сидов в базу PostgreSQL..."

# Исполняем SQL внутри контейнера и направляем STDOUT напрямую в наш файл
# Никаких временных файлов внутри Docker!
docker exec -i "$DB_CONTAINER" psql -U postgres -d shortener -v num_rows="$NUM_ROWS" -f - -q -A < seed.sql > "$CODES_FILE"

# Проверка, что файл не пустой
ACTUAL_ROWS=$(wc -l < "$CODES_FILE" | tr -d ' ')
echo "✅ Сгенерировано и сохранено кодов: $ACTUAL_ROWS"

if [ "$ACTUAL_ROWS" -eq 0 ]; then
    echo "❌ Ошибка генерации кодов. Завершение."
    exit 1
fi

echo "🔥 2. Запуск k6 (Warmup + Load)..."
# Передаем переменные среды в k6
K6_WEB_DASHBOARD=true \
K6_WEB_DASHBOARD_EXPORT="report.html" \
BASE_URL="http://localhost:8080/api/v1" \
k6 run main.js

echo "🎉 Тестирование завершено! Отчет сохранен в report.html."
