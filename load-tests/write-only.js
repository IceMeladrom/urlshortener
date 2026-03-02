// write-only.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { BASE_URL } from './config.js';

export const options = {
  stages: [
    { duration: '2m', target: 500 },
    { duration: '3m', target: 800 },
    { duration: '5m', target: 1000 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'], // запись обычно медленнее
  },
};

export default function () {
  const url = `https://example.com/${randomString(10)}`;
  const payload = JSON.stringify({ url });
  const res = http.post(`${BASE_URL}/shorten`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'POST status 200': (r) => r.status === 200 });
  sleep(1); // меньше запросов, так как запись тяжелее
}