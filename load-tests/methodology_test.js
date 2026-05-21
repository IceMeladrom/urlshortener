import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = normalizeBaseUrl(__ENV.BASE_URL || 'http://localhost:8080/api/v1');
const TEST_TYPE = __ENV.SCENARIO || 'nominal';
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '1m';
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 500);
const CODES_FILE = __ENV.CODES_FILE || './shortcodes.txt';
const POPULAR_CODES_FILE = __ENV.POPULAR_CODES_FILE || './shortcodes_popular.txt';

const shortCodes = new SharedArray('shortcodes', function () {
    return open(CODES_FILE).split('\n').map(c => c.trim()).filter(Boolean);
});

const popularCodes = new SharedArray('popular-shortcodes', function () {
    try {
        return open(POPULAR_CODES_FILE).split('\n').map(c => c.trim()).filter(Boolean);
    } catch (e) {
        return [];
    }
});

if (shortCodes.length === 0) {
    throw new Error(`Файл ${CODES_FILE} пуст`);
}

let readStages = [];
let writeStages = [];
let maxVUsRead = 100;
let maxVUsWrite = 20;
let includeWrites = true;
let readExec = 'readRedirect';
let thresholds = {
    'http_req_failed': ['rate<0.05'],
};

if (TEST_TYPE === 'warmup') {
    readStages = [
        { duration: WARMUP_DURATION, target: WARMUP_RATE },
        { duration: '10s', target: 0 },
    ];
    includeWrites = false;
    maxVUsRead = Math.max(100, WARMUP_RATE);
    readExec = 'readWarmup';
    thresholds = {
        'http_req_failed': ['rate<0.05'],
    };
} else if (TEST_TYPE === 'nominal') {
    readStages = [
        { duration: '30s', target: 300 },
        { duration: '3m', target: 300 },
        { duration: '30s', target: 0 },
    ];
    writeStages = [
        { duration: '30s', target: 30 },
        { duration: '3m', target: 30 },
        { duration: '30s', target: 0 },
    ];
    maxVUsRead = 300;
    maxVUsWrite = 50;
    thresholds = {
        'http_req_failed': ['rate<0.001'],
        'http_req_duration{scenario:read_redirects}': ['p(95)<30'],
        'http_req_duration{scenario:write_links}': ['p(95)<150'],
    };
} else if (TEST_TYPE === 'capacity') {
    readStages = [
        { duration: '10m', target: 2500 },
    ];
    writeStages = [
        { duration: '10m', target: 250 },
    ];
    maxVUsRead = 1000;
    maxVUsWrite = 250;
} else if (TEST_TYPE === 'stress') {
    readStages = [
        { duration: '10s', target: 100 },
        { duration: '5s', target: 800 },
        { duration: '2m', target: 800 },
        { duration: '30s', target: 0 },
    ];
    writeStages = [
        { duration: '10s', target: 10 },
        { duration: '5s', target: 80 },
        { duration: '2m', target: 80 },
        { duration: '30s', target: 0 },
    ];
    maxVUsRead = 800;
    maxVUsWrite = 100;
} else if (TEST_TYPE === 'soak') {
    readStages = [
        { duration: '2m', target: 150 },
        { duration: '60m', target: 150 },
        { duration: '2m', target: 0 },
    ];
    writeStages = [
        { duration: '2m', target: 15 },
        { duration: '60m', target: 15 },
        { duration: '2m', target: 0 },
    ];
    maxVUsRead = 150;
    maxVUsWrite = 30;
} else if (TEST_TYPE === 'failover') {
    readStages = [
        { duration: '30s', target: 300 },
        { duration: '6m', target: 300 },
        { duration: '30s', target: 0 },
    ];
    writeStages = [
        { duration: '30s', target: 30 },
        { duration: '6m', target: 30 },
        { duration: '30s', target: 0 },
    ];
    maxVUsRead = 500;
    maxVUsWrite = 80;
    thresholds = {
        'http_req_failed': ['rate<0.05'],
    };
} else {
    throw new Error(`Неизвестный сценарий: ${TEST_TYPE}`);
}

const scenarios = {
    read_redirects: {
        executor: 'ramping-arrival-rate',
        startRate: 1,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: maxVUsRead,
        stages: readStages,
        exec: readExec,
    },
};

if (includeWrites) {
    scenarios.write_links = {
        executor: 'ramping-arrival-rate',
        startRate: 1,
        timeUnit: '1s',
        preAllocatedVUs: 10,
        maxVUs: maxVUsWrite,
        stages: writeStages,
        exec: 'writeLink',
    };
}

export const options = {
    discardResponseBodies: true,
    scenarios,
    thresholds,
};

export function readRedirect() {
    const code = chooseCode();
    const res = http.get(`${BASE_URL}/${code}`, {
        redirects: 0,
        tags: { type: 'redirect' },
    });
    check(res, { 'GET 302': (r) => r.status === 302 });
}

export function readWarmup() {
    const source = popularCodes.length > 0 ? popularCodes : shortCodes;
    const code = source[exec.scenario.iterationInTest % source.length];
    const res = http.get(`${BASE_URL}/${code}`, {
        redirects: 0,
        tags: { type: 'warmup' },
    });
    check(res, { 'GET 302': (r) => r.status === 302 });
}

export function writeLink() {
    const traceId = `${exec.vu.idInInstance}-${exec.scenario.iterationInTest}-${Date.now()}`;
    const url = `examples.com/loadtest/${TEST_TYPE}/id/${traceId}`;
    const res = http.post(`${BASE_URL}/shorten`, JSON.stringify({ url }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { type: 'shorten' },
    });
    check(res, { 'POST 201': (r) => r.status === 201 });
}

function chooseCode() {
    if (popularCodes.length > 0 && Math.random() < 0.8) {
        return popularCodes[Math.floor(Math.random() * popularCodes.length)];
    }
    return shortCodes[Math.floor(Math.random() * shortCodes.length)];
}

function normalizeBaseUrl(rawUrl) {
    const trimmed = rawUrl.trim().replace(/\/+$/, '');
    if (trimmed.endsWith('/api/v1')) {
        return trimmed;
    }
    return `${trimmed}/api/v1`;
}
