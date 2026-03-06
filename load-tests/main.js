import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

// Загружаем коды для чтения в общую память всех потоков (быстро и без дубликатов)
const shortCodes = new SharedArray('shortcodes', function () {
    return open('./shortcodes.txt').split('\n').filter(c => c.trim().length > 0);
});

export const options = {
    // Отключаем загрузку тела ответа, нам важны только заголовки и статус
    discardResponseBodies: true,

    scenarios: {
        // СЦЕНАРИЙ 1: Чтение (GET) — Редиректы по коротким ссылкам
        read_redirects: {
            executor: 'ramping-arrival-rate',
            startRate: 50,                // Стартуем с 50 RPS
            timeUnit: '1s',               // Измеряем рэйт в секундах
            preAllocatedVUs: 50,          // Базовый пул потоков
            maxVUs: 500,                  // Максимум потоков, если сервер начнет тормозить
            stages: [
                { duration: '1m', target: 500 },  // Прогрев: плавный рост с 50 до 500 RPS за 1 минуту
                { duration: '5m', target: 500 },  // Полка: Ровно 500 RPS в течение 5 минут
                { duration: '1m', target: 0 },    // Спад: плавное завершение теста
            ],
            exec: 'readRedirect',
        },

        // СЦЕНАРИЙ 2: Запись (POST) — Создание новых коротких ссылок (10% от чтения)
        write_links: {
            executor: 'ramping-arrival-rate',
            startRate: 5,
            timeUnit: '1s',
            preAllocatedVUs: 20,
            maxVUs: 100,
            stages: [
                { duration: '1m', target: 50 },   // Прогрев: плавный рост до 50 RPS
                { duration: '5m', target: 50 },   // Полка: Ровно 50 RPS в течение 5 минут
                { duration: '1m', target: 0 },    // Спад
            ],
            exec: 'writeLink',
        },
    },

    // Жесткие лимиты на завал теста (SLA)
    thresholds: {
        'http_req_failed': ['rate<0.01'],                 // Ошибок (не 2xx/3xx) должно быть меньше 1%
        'http_req_duration{scenario:read_redirects}': ['p(95)<150'],  // 95% чтений (из Redis) быстрее 150 мс
        'http_req_duration{scenario:write_links}': ['p(95)<250'],    // 95% записей (в БД + Redis) быстрее 250 мс
    },
};

// Функция для воркеров из пула `read_redirects`
export function readRedirect() {
    // Случайный выбор кода из 미리 сгенерированного файла
    const randomCode = shortCodes[Math.floor(Math.random() * shortCodes.length)];

    const res = http.get(`${BASE_URL}/${randomCode}`, {
        redirects: 0, // Нам не нужно реально переходить на example.com! Только проверить 302 от нашего API.
        tags: { type: 'redirect', scenario: 'read_redirects' } // Теги для красивых графиков в Grafana/Отчете
    });

    check(res, {
        'GET status is 302': (r) => r.status === 302,
    });
}

// Функция для воркеров из пула `write_links`
export function writeLink() {
    // Идеальная формула уникальности без затрат CPU (Вместо Math.random строк)
    // ID потока + Глобальный номер итерации гарантируют 100% уникальность URL
    const traceId = `${exec.vu.idInInstance}-${exec.scenario.iterationInTest}`;
    const url = `https://example.com/loadtest/article/${traceId}`;

    const payload = JSON.stringify({ url });
    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: { type: 'shorten', scenario: 'write_links' }
    };

    const res = http.post(`${BASE_URL}/shorten`, payload, params);

    check(res, {
        'POST status is 201': (r) => r.status === 201,
    });
}
