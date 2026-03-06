#!/usr/bin/env bash

# Строгий режим bash: скрипт прервется при любой ошибке, использовании необъявленной переменной или сбое в пайплайне
set -euo pipefail

# ANSI Цвета для красоты в терминале 🎨
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
CYAN='\033[1;36m'
NC='\033[0m' # No Color

# Конфигурация
DB_CONTAINER="shortener-db"
NUM_ROWS=50000
CODES_FILE="shortcodes.txt"

echo -e "${CYAN}🚀 Подготовка стенда НИРС${NC}"
echo "----------------------------------------------"

# 1. Проверка доступности контейнера БД
if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
  echo -e "${RED}❌ Ошибка: Контейнер '$DB_CONTAINER' не запущен! Убедитесь, что docker compose up выполнен.${NC}"
  exit 1
fi
echo -e "${GREEN}✅ Контейнер БД ($DB_CONTAINER) в сети.${NC}"

# 2. Генерация тестовых данных (Seed)
echo -e "${YELLOW}📦 Генерация $NUM_ROWS сидов в базу PostgreSQL (идемпотентно)...${NC}"
docker exec -i "$DB_CONTAINER" psql -U postgres -d shortener -v num_rows="$NUM_ROWS" -q -A -f - < seed.sql > "$CODES_FILE"

# Проверка, что файл не пустой
if [ ! -s "$CODES_FILE" ]; then
    echo -e "${RED}❌ Ошибка генерации: файл $CODES_FILE пуст.${NC}"
    exit 1
fi

ACTUAL_ROWS=$(wc -l < "$CODES_FILE" | tr -d ' ')
echo -e "${GREEN}✅ Сгенерировано кодов: $ACTUAL_ROWS${NC}"
echo ""

# 3. Интерактивное меню выбора сценария НИРС
echo -e "${CYAN}==============================================${NC}"
echo -e "${GREEN}ВЫБЕРИТЕ СЦЕНАРИЙ ИЗ МЕТОДИКИ НИРС:${NC}"
echo "1 - Базовый (Номинальный - проверка работы в норме)"
echo "2 - Поиск пропускной способности (Capacity - до отказа)"
echo "3 - Стресс-тест (Спайк - резкий кратный скачок нагрузки)"
echo "4 - Долговременный (Soak/Endurance - на 1 час)"
echo -e "${CYAN}==============================================${NC}"

# Чтение пользовательского ввода с валидацией
while true; do
    read -p "Введите номер (1-4) и нажмите Enter: " MENU_CHOICE
    case $MENU_CHOICE in
        1) SCENARIO="nominal"; break;;
        2) SCENARIO="capacity"; break;;
        3) SCENARIO="stress"; break;;
        4) SCENARIO="soak"; break;;
        *) echo -e "${RED}⚠️ Ошибка: Выберите цифру от 1 до 4.${NC}";;
    esac
done

echo ""
echo -e "${RED}🔥 Начинаю бомбардировку! Запуск сценария: [ ${YELLOW}${SCENARIO^^} ${RED}]...${NC}"

# 4. Настройка переменных среды и запуск k6
export K6_WEB_DASHBOARD=true
export K6_WEB_DASHBOARD_EXPORT="report_${SCENARIO}.html"
export BASE_URL="http://localhost:8080/api/v1"
export SCENARIO=$SCENARIO

# Запуск!
k6 run methodology_test.js

echo ""
echo -e "${GREEN}🎉 Тест '$SCENARIO' успешно завершен.${NC}"
echo -e "📄 Обязательно приложите HTML отчет к НИРС: ${YELLOW}report_${SCENARIO}.html${NC}"
