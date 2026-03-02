// stress.js
import http from 'k6/http';
import { check } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { SharedArray } from 'k6/data';
import { BASE_URL, SHORTCODES_FILE } from './config.js';

const codes = new SharedArray('shortcodes', function () {
  return open(SHORTCODES_FILE).split('\n').filter(c => c);
});

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
      stages: [
        { target: 3000, duration: '10s' },  // резкий скачок до 3000 RPS
        { target: 3000, duration: '1m' },   // удержание
        { target: 100, duration: '10s' },   // спад
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'], // допускаем немного больше ошибок во время пика
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  // Смешанная нагрузка 10/90
  if (Math.random() < 0.1) {
    const url = `https://example.com/${randomString(10)}`;
    const res = http.post(`${BASE_URL}/shorten`, JSON.stringify({ url }), {
      headers: { 'Content-Type': 'application/json' },
    });
    check(res, { 'POST status 200': (r) => r.status === 200 });
  } else {
    const code = codes[Math.floor(Math.random() * codes.length)];
    const res = http.get(`${BASE_URL}/${code}`);
    check(res, { 'GET status 302': (r) => r.status === 302 });
  }
}