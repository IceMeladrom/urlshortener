// soak.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { SharedArray } from 'k6/data';
import { BASE_URL, SHORTCODES_FILE } from './config.js';

const codes = new SharedArray('shortcodes', function () {
  return open(SHORTCODES_FILE).split('\n').filter(c => c);
});

export const options = {
  stages: [
    { duration: '10m', target: 1500 },  // разгон до 1500 RPS
    { duration: '8h', target: 1500 },   // 8 часов удержания
    { duration: '10m', target: 0 },     // спад
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
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
  sleep(0.5);
}