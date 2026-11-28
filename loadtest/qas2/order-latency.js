// QAS 2 주문 처리 시간(docs/measurement/plan-stage10.md 3절). 이전 번호 체계의 qas1.js에 해당한다.
// 측정 대상 매수 1주(지정가 1,100원) 초당 ORDER_RATE건, 10종목 균등 + 계좌 조회 초당 9,000건(잔고 4,000 / 보유 3,000 / 주문 내역 2,000).
// 사전 매도는 scripts/seed/pre-sells.sql로 미리 넣어 둔다(1,000원 1주씩, 계좌 501~1000). 매수 지정가가 모든 매도보다 높아 남은 매도가 있으면 반드시 체결된다.
// SSE 수집기(loadtest/tools/SseCollector.java)가 매수 계좌 1~500에 연결해 READY가 된 뒤 실행한다.
//
// 기록(--log-output=file=... --log-format=raw): 주문마다 한 줄
//   ORD <순번> <accountId> <clientOrderId> <symbol> <보낸 시각 epoch ms> <HTTP 상태, 연결 실패면 0> <응답의 orderId 또는 -> <k6 응답 시간 ms>
//   START <시작 시각 epoch ms> <예열 초> <전체 길이>
// 보낸 시각은 같은 생성기의 벽시계(Date.now())다. 수집기의 받은 시각(System.currentTimeMillis())과 같은 기준이다.
// 판정 대상은 보낸 시각이 START + 예열 이후인 주문이다. 대조·판정은 측정 뒤 분석에서 한다(이 스크립트는 기록만 한다).
//
//   k6 run -e BASE_URL=http://<앱 사설 IP> -e RUN=r1 --log-output=file=/out/k6-orders.log --log-format=raw order-latency.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN || 'r1';
const ORDER_RATE = Number(__ENV.ORDER_RATE || 1000);
const BALANCE_RATE = Number(__ENV.BALANCE_RATE || 4000);
const HOLDINGS_RATE = Number(__ENV.HOLDINGS_RATE || 3000);
const HISTORY_RATE = Number(__ENV.HISTORY_RATE || 2000);
const WARMUP_S = Number(__ENV.WARMUP_S || 15);
const MEASURE_S = Number(__ENV.MEASURE_S || 60);
const DURATION = `${WARMUP_S + MEASURE_S}s`;
const SYMBOLS = Array.from({ length: 10 }, (_, i) => 'SYM' + String(i + 1).padStart(2, '0'));
const ORDER_PARAMS = { headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { name: 'order' } };

const ordersNot202 = new Counter('orders_not_202');
const queriesNot200 = new Counter('queries_not_200');

function arrival(exec, rate) {
  return {
    executor: 'constant-arrival-rate', exec, rate, timeUnit: '1s', duration: DURATION,
    // 응답이 늦어지면 VU가 응답을 기다리며 묶인다. 미전송 원인 판정(plan 1.7)을 위해 최대 VU를 넉넉히 두고 사용량을 기록한다
    preAllocatedVUs: Math.max(50, Math.ceil(rate / 5)), maxVUs: Math.max(500, rate * 3),
  };
}

export const options = {
  scenarios: {
    orders: arrival('placeBuy', ORDER_RATE),
    balance: arrival('balance', BALANCE_RATE),
    holdings: arrival('holdings', HOLDINGS_RATE),
    history: arrival('history', HISTORY_RATE),
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  console.log(`START ${Date.now()} ${WARMUP_S} ${DURATION}`);
}

export function placeBuy() {
  const i = exec.scenario.iterationInTest;
  const account = (i % 500) + 1;
  const symbol = SYMBOLS[i % SYMBOLS.length];
  const clientOrderId = `q2-${RUN}-${i}`;
  const body = JSON.stringify({ clientOrderId, symbolCode: symbol, side: 'BUY', limitPrice: 1100, quantity: 1 });
  const sentMs = Date.now();
  const res = http.post(`${BASE}/api/accounts/${account}/orders`, body, ORDER_PARAMS);
  let orderId = '-';
  if (res.status === 202) {
    try {
      orderId = res.json('orderId');
    } catch (e) {
      orderId = '-';
    }
  } else {
    ordersNot202.add(1);
  }
  console.log(`ORD ${i} ${account} ${clientOrderId} ${symbol} ${sentMs} ${res.status} ${orderId} ${res.timings.duration.toFixed(1)}`);
}

function query(path, kind) {
  const i = exec.scenario.iterationInTest;
  const account = 1001 + (i % 1000);
  const res = http.get(`${BASE}/api/accounts/${account}/${path}`, { timeout: '5s', tags: { name: kind } });
  if (res.status !== 200) queriesNot200.add(1, { kind });
}

export function balance() {
  query('balance', 'balance');
}

export function holdings() {
  query('holdings', 'holdings');
}

export function history() {
  query('orders?size=20', 'history');
}
