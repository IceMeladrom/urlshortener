// not-found.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { BASE_URL } from './config.js';

export const options = {
  stages: [
    { duration: '2m', target: 500 },
    { duration: '5m', target: 1000 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'], // ожидаем, что 404 не считаются failed (по умолчанию k6 считает ошибкой только 5xx)
    http_req_duration: ['p(95)<300'],
  },
};

export default function () {
  // Генерируем случайный 7-символьный код
  const code = randomString(7, '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ');
  const res = http.get(`${BASE_URL}/${code}`);
  // 404 - это нормальный ответ для несуществующего кода, не считаем ошибкой
  check(res, { 'status is 404': (r) => r.status === 404 });
  sleep(0.5);
}