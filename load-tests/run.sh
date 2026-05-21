#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"

DB_CONTAINER="${DB_CONTAINER:-shortener-db}"
REDIS_CONTAINER="${REDIS_CONTAINER:-shortener-redis}"
NUM_ROWS="${NUM_ROWS:-100000}"
CODES_FILE="${CODES_FILE:-shortcodes.txt}"
POPULAR_CODES_FILE="${POPULAR_CODES_FILE:-shortcodes_popular.txt}"
POPULAR_PERCENT="${POPULAR_PERCENT:-20}"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
SCENARIO="${SCENARIO:-}"
PREPARE_DATA="${PREPARE_DATA:-true}"
FAILOVER_CONTROL="${FAILOVER_CONTROL:-true}"
FAILOVER_STOP_AFTER="${FAILOVER_STOP_AFTER:-120}"
FAILOVER_DOWN_SECONDS="${FAILOVER_DOWN_SECONDS:-180}"
WARMUP_DURATION="${WARMUP_DURATION:-1m}"
WARMUP_RATE="${WARMUP_RATE:-500}"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 не найден в PATH"
  exit 1
fi

if [ "$PREPARE_DATA" = "true" ]; then
  if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
    echo "Контейнер PostgreSQL '$DB_CONTAINER' не запущен"
    exit 1
  fi

  if ! docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER"; then
    echo "Контейнер Redis '$REDIS_CONTAINER' не запущен"
    exit 1
  fi

  echo "Очищаю Redis"
  docker exec "$REDIS_CONTAINER" redis-cli FLUSHALL >/dev/null

  echo "Создаю $NUM_ROWS тестовых ссылок"
  docker exec -i "$DB_CONTAINER" psql -U postgres -d shortener -v num_rows="$NUM_ROWS" -q -A -f - < seed.sql > "$CODES_FILE"

  if [ ! -s "$CODES_FILE" ]; then
    echo "Файл $CODES_FILE пуст"
    exit 1
  fi

  POPULAR_ROWS=$(( NUM_ROWS * POPULAR_PERCENT / 100 ))
  if [ "$POPULAR_ROWS" -lt 1 ]; then
    POPULAR_ROWS=1
  fi

  echo "Выбираю случайные популярные ссылки: $POPULAR_ROWS"
  awk 'BEGIN { srand() } { print rand() "\t" $0 }' "$CODES_FILE" \
    | sort -n \
    | head -n "$POPULAR_ROWS" \
    | cut -f2- > "$POPULAR_CODES_FILE"

  echo "Прогреваю популярные ссылки"
  BASE_URL="$BASE_URL" \
  SCENARIO="warmup" \
  WARMUP_DURATION="$WARMUP_DURATION" \
  WARMUP_RATE="$WARMUP_RATE" \
  K6_WEB_DASHBOARD=false \
  k6 run --summary-export "summary_warmup.json" methodology_test.js

  docker exec "$REDIS_CONTAINER" redis-cli DEL links:clicks:buffer links:expiry:buffer >/dev/null
fi

if [ -z "$SCENARIO" ]; then
  echo "Выберите сценарий:"
  echo "1 - номинальная нагрузка"
  echo "2 - поиск предельной пропускной способности"
  echo "3 - резкий скачок нагрузки"
  echo "4 - длительная нагрузка"
  echo "5 - отказ Redis"
  read -r -p "Номер сценария: " MENU_CHOICE
  case "$MENU_CHOICE" in
    1) SCENARIO="nominal" ;;
    2) SCENARIO="capacity" ;;
    3) SCENARIO="stress" ;;
    4) SCENARIO="soak" ;;
    5) SCENARIO="failover" ;;
    *) echo "Неизвестный сценарий"; exit 1 ;;
  esac
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
HTML_REPORT="report_${SCENARIO}_${STAMP}.html"
JSON_REPORT="summary_${SCENARIO}_${STAMP}.json"
FAILOVER_PID=""

cleanup() {
  if [ -n "$FAILOVER_PID" ]; then
    kill "$FAILOVER_PID" >/dev/null 2>&1 || true
    wait "$FAILOVER_PID" >/dev/null 2>&1 || true
  fi
  if [ "$SCENARIO" = "failover" ] && [ "$FAILOVER_CONTROL" = "true" ]; then
    docker start "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ "$SCENARIO" = "failover" ] && [ "$FAILOVER_CONTROL" = "true" ]; then
  (
    sleep "$FAILOVER_STOP_AFTER"
    echo "Останавливаю Redis"
    docker stop "$REDIS_CONTAINER" >/dev/null
    sleep "$FAILOVER_DOWN_SECONDS"
    echo "Запускаю Redis"
    docker start "$REDIS_CONTAINER" >/dev/null
  ) &
  FAILOVER_PID="$!"
elif [ "$SCENARIO" = "failover" ]; then
  echo "Управление Redis отключено. Остановите и запустите Redis вручную во время теста."
fi

echo "Запускаю сценарий: $SCENARIO"
echo "Адрес приложения: $BASE_URL"

BASE_URL="$BASE_URL" \
SCENARIO="$SCENARIO" \
K6_WEB_DASHBOARD=true \
K6_WEB_DASHBOARD_EXPORT="$HTML_REPORT" \
k6 run --summary-export "$JSON_REPORT" methodology_test.js

echo "Отчёт страницы: $HTML_REPORT"
echo "Сводка: $JSON_REPORT"
