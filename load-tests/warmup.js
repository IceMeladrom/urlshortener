import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const codes = new SharedArray('shortcodes', function () {
  return open('./shortcodes.txt').split('\n').filter(c => c);
});

export const options = {
  // Длительность задаётся через аргумент командной строки --duration
  vus: 10,
};

export default function () {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`http://localhost:8080/api/v1/${code}`);
  check(res, { 'status is 302': (r) => r.status === 302 });
  sleep(0.1); // для контроля RPS
}