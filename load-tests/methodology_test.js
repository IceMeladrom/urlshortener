// load-tests/methodology_test.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const TEST_TYPE = __ENV.SCENARIO || 'nominal'; // nominal, capacity, stress, soak

const shortCodes = new SharedArray('shortcodes', function () {
    return open('./shortcodes.txt').split('\n').filter(c => c.trim().length > 0);
});

// Настройка сценариев в зависимости от типа теста
let readStages, writeStages, maxVUsRead, maxVUsWrite;

if (TEST_TYPE === 'nominal') {
    // 1. Базовый сценарий (300 RPS read, 30 RPS write)
    readStages = [
        { duration: '30s', target: 300 }, // Разгон
        { duration: '3m', target: 300 },  // Полка
        { duration: '30s', target: 0 },   // Спад
    ];
    writeStages = [
        { duration: '30s', target: 30 },
        { duration: '3m', target: 30 },
        { duration: '30s', target: 0 }
    ];
    maxVUsRead = 300; maxVUsWrite = 50;

} else if (TEST_TYPE === 'capacity') {
    // 2. Сценарий максимальной пропускной способности
    readStages = [
        { duration: '10m', target: 2500 }, // Плавно растем до отказа
    ];
    writeStages = [
        { duration: '10m', target: 250 },
    ];
    maxVUsRead = 1000;
    maxVUsWrite = 250;

} else if (TEST_TYPE === 'stress') {
    // 3. Стресс-сценарий (Резкий мощный спайк)
    readStages = [
        { duration: '10s', target: 100 },  // Чуть прогрели
        { duration: '5s', target: 800 },   // Спайк х2.5 от номинала!
        { duration: '2m', target: 800 },   // Держим спайк
        { duration: '30s', target: 0 },
    ];
    writeStages = [
        { duration: '10s', target:10 },
        { duration: '5s', target:80 },
        { duration: '2m', target:80 },
        { duration: '30s', target:0 }
    ];
    maxVUsRead = 800; maxVUsWrite = 100;

} else if (TEST_TYPE === 'soak') {
    // 4. Долговременный сценарий (Умеренная нагрузка на час)
    readStages = [
        { duration: '2m', target: 150 },
        { duration: '60m', target: 150 }, // Долгая полка
        { duration: '2m', target: 0 },
    ];
    writeStages = [
        { duration: '2m', target: 15 },
        { duration: '60m', target: 15 },
        { duration: '2m', target: 0 }
    ];
    maxVUsRead = 150; maxVUsWrite = 30;
}

export const options = {
    discardResponseBodies: true,
    scenarios: {
        read_redirects: {
            executor: 'ramping-arrival-rate',
            startRate: 1, timeUnit: '1s',
            preAllocatedVUs: 50, maxVUs: maxVUsRead,
            stages: readStages,
            exec: 'readRedirect',
        },
        write_links: {
            executor: 'ramping-arrival-rate',
            startRate: 1, timeUnit: '1s',
            preAllocatedVUs: 10, maxVUs: maxVUsWrite,
            stages: writeStages,
            exec: 'writeLink',
        },
    },
    thresholds: {
        'http_req_failed': ['rate<0.01'],
        'http_req_duration{scenario:read_redirects}': ['p(95)<50'], // SLO
        'http_req_duration{scenario:write_links}': ['p(95)<150'],   // SLO
    },
};

export function readRedirect() {
    const randomCode = shortCodes[Math.floor(Math.random() * shortCodes.length)];
    const res = http.get(`${BASE_URL}/${randomCode}`, {
        redirects: 0,
        tags: { type: 'redirect', scenario: 'read_redirects' }
    });
    check(res, { 'GET status 302': (r) => r.status === 302 });
}

export function writeLink() {
    const traceId = `${exec.vu.idInInstance}-${exec.scenario.iterationInTest}-${Date.now()}`;
    const url = `https://examples.com/loadtest/${TEST_TYPE}/id/${traceId}`;
    const res = http.post(`${BASE_URL}/shorten`, JSON.stringify({ url }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { type: 'shorten', scenario: 'write_links' }
    });
    check(res, { 'POST status 201': (r) => r.status === 201 || r.status === 400 });
}
