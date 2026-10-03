// On-sale stampede: many users hit a fresh show at once, mostly for the same few hot seats.
// Run through scripts/burst.sh; settings come from environment variables (see the constants below).
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEATS = parseInt(__ENV.SEATS || '100', 10);
const USERS = parseInt(__ENV.USERS || '2000', 10);
const REQUESTS_PER_USER = parseInt(__ENV.REQUESTS_PER_USER || '10', 10);
const HOT_SEATS = 5;
const SEAT_LIMIT_PER_USER = 4;
const ADMIN = { Authorization: 'Bearer admin:burst-admin' };

const created = new Counter('reserve_201');
const conflicts = new Counter('reserve_409');
const unexpectedClientErrors = new Counter('reserve_other_4xx');
const serverErrors = new Counter('reserve_5xx');
const networkErrors = new Counter('reserve_network_errors');
const seatsSold = new Counter('seats_sold');
const limitViolations = new Counter('limit_violations');
const finalTotal = new Counter('final_total');
const finalAvailable = new Counter('final_available');
const finalHeld = new Counter('final_held');
const finalConfirmed = new Counter('final_confirmed');

export const options = {
  scenarios: {
    stampede: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: REQUESTS_PER_USER,
      maxDuration: '10m',
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const seats = Array.from({ length: SEATS }, (_, i) => `A${i + 1}`);
  const response = http.post(
    `${BASE_URL}/shows`,
    JSON.stringify({ name: `burst-${Date.now()}`, seats, price_paise: 25000 }),
    { headers: { ...ADMIN, 'Content-Type': 'application/json' } });
  if (response.status !== 201) {
    throw new Error(`could not create the show: ${response.status} ${response.body}`);
  }
  return { showId: response.json('id'), seats };
}

// Each virtual user is one buyer, so its own responses are enough to check the per-user limit.
let seatsHeldByThisUser = 0;

export default function (data) {
  const userId = `u${exec.vu.idInTest}`;
  const response = http.post(
    `${BASE_URL}/shows/${data.showId}/reserve`,
    JSON.stringify({
      seats: pickSeats(data.seats),
      idempotency_key: `${userId}-${exec.vu.iterationInScenario}`,
    }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer user:${userId}` },
      tags: { name: 'reserve' },
      timeout: '120s',
    });
  record(response);
}

// Half the requests go for a few hot seats; 15% ask for two seats, in random order.
function pickSeats(allSeats) {
  const one = () => (Math.random() < 0.5
    ? allSeats[Math.floor(Math.random() * HOT_SEATS)]
    : allSeats[Math.floor(Math.random() * allSeats.length)]);
  const first = one();
  if (Math.random() >= 0.15) {
    return [first];
  }
  let second = one();
  while (second === first) {
    second = allSeats[Math.floor(Math.random() * allSeats.length)];
  }
  return Math.random() < 0.5 ? [first, second] : [second, first];
}

function record(response) {
  if (response.status === 201) {
    created.add(1);
    const count = response.json('seats').length;
    seatsSold.add(count);
    seatsHeldByThisUser += count;
    if (seatsHeldByThisUser > SEAT_LIMIT_PER_USER) {
      limitViolations.add(1);
    }
  } else if (response.status === 409) {
    conflicts.add(1);
  } else if (response.status >= 500) {
    serverErrors.add(1);
  } else if (response.status === 0) {
    networkErrors.add(1);
  } else {
    unexpectedClientErrors.add(1);
  }
}

export function teardown(data) {
  const response = http.get(`${BASE_URL}/shows/${data.showId}`, { headers: ADMIN });
  if (response.status !== 200) {
    return;
  }
  finalTotal.add(response.json('total_seats'));
  finalAvailable.add(response.json('available'));
  finalHeld.add(response.json('held'));
  finalConfirmed.add(response.json('confirmed'));
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const latency = data.metrics['http_req_duration{name:reserve}'] || data.metrics.http_req_duration;
  const expectedRequests = USERS * REQUESTS_PER_USER;
  const answered = count('reserve_201') + count('reserve_409') + count('reserve_other_4xx')
    + count('reserve_5xx') + count('reserve_network_errors');

  const checks = [
    ['no 5xx responses', count('reserve_5xx') === 0, `${count('reserve_5xx')} found`],
    ['no network errors or timeouts', count('reserve_network_errors') === 0,
      `${count('reserve_network_errors')} found`],
    ['no unexpected 4xx (only 201 and 409 expected)', count('reserve_other_4xx') === 0,
      `${count('reserve_other_4xx')} found`],
    ['every request was answered', answered === expectedRequests, `${answered} of ${expectedRequests}`],
    ['final state fetched', count('final_total') > 0, `total=${count('final_total')}`],
    ['available + held + confirmed == total',
      count('final_available') + count('final_held') + count('final_confirmed') === count('final_total'),
      `${count('final_available')} + ${count('final_held')} + ${count('final_confirmed')} vs ${count('final_total')}`],
    ['no double-sell (seats claimed by 201s == seats held)',
      count('seats_sold') === count('final_held') + count('final_confirmed'),
      `201s claimed ${count('seats_sold')}, show holds ${count('final_held') + count('final_confirmed')}`],
    [`no user holds more than ${SEAT_LIMIT_PER_USER} seats`, count('limit_violations') === 0,
      `${count('limit_violations')} violations`],
  ];
  const passed = checks.every(([, ok]) => ok);

  const lines = [
    '',
    `Burst against ${BASE_URL}: ${USERS} users x ${REQUESTS_PER_USER} requests, ${SEATS} seats`,
    '',
    'Outcomes',
    `  201 created        ${count('reserve_201')}`,
    `  409 declined       ${count('reserve_409')}`,
    `  other 4xx          ${count('reserve_other_4xx')}`,
    `  5xx                ${count('reserve_5xx')}`,
    `  network errors     ${count('reserve_network_errors')}`,
    '',
    'Latency (reserve)  ' + ['med', 'p(95)', 'p(99)', 'max']
      .map((stat) => `${stat}=${latency ? latency.values[stat].toFixed(0) : '?'}ms`).join('  '),
    `Duration           ${(data.state.testRunDurationMs / 1000).toFixed(1)}s`,
    '',
    'Final state',
    `  total ${count('final_total')}, available ${count('final_available')}, `
      + `held ${count('final_held')}, confirmed ${count('final_confirmed')}`,
    `  seats never sold: ${count('final_available')} (informational)`,
    '',
    'Checks',
    ...checks.map(([name, ok, detail]) => `  ${ok ? 'PASS' : 'FAIL'}  ${name}  (${detail})`),
    '',
    `RESULT: ${passed ? 'PASS' : 'FAIL'}`,
    '',
  ];
  return { stdout: lines.join('\n') };
}
