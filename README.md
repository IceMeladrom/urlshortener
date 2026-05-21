# URL Shortener

Сервис сокращения ссылок на Java 21 и Spring Boot 4.

Проект использует PostgreSQL как основное хранилище, Redis для быстрого чтения и временного накопления переходов, Prometheus и Grafana для наблюдения, k6 для нагрузочных испытаний.

## Быстрый запуск

```powershell
docker compose up -d --build
```

После запуска:

- приложение: `http://localhost:8080`;
- состояние: `http://localhost:8080/actuator/health`;
- Prometheus: `http://localhost:9090`;
- Grafana: `http://localhost:3000`.

Учетная запись Grafana: `admin / admin`.

## API

Создание короткой ссылки:

```http
POST /api/v1/shorten
Content-Type: application/json
```

```json
{
  "url": "example.com"
}
```

Переход по короткой ссылке:

```http
GET /api/v1/{shortCode}
```

## Документация

Полное описание проекта находится в [docs/PROJECT_DOCUMENTATION.md](docs/PROJECT_DOCUMENTATION.md).
