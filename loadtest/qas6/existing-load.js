// QAS 6 기존 종목 운영 부하(IMPLEMENTATION_GUIDE §10 QAS 6, plan-stage10.md). 기존 10종목에 초당 RATE건(기본 1,000)을 보낸다.
// 이 부하가 도는 동안 register.sh로 신규 10종목을 추가하고, 추가 전·중·후의 예상하지 못한 오류(4xx·5xx)와 시간 초과를 본다.
// 시험 주문은 현금·보유가 측정 내내 충분하다: 매수는 계좌 1~500이 1,000원에 1주, 매도는 계좌 501~1000이 1,000원에 1주
// (계좌당 초당 약 1건, 300초면 계좌당 300주·30만 원 정도라 measure-seed의 1억 원·종목당 1,000주로 충분). 그래서 오류는 모두 예상하지 못한 오류다.
// 기록 형식은 QAS 2와 같다(loadtest/analysis/load.sh로 적재, clientOrderId q6-<run>-<순번>).
//   k6 run -e BASE_URL=http://<앱 사설 IP> -e RUN=r1 -e RATE=1000 -e DURATION=300s --log-output=file=/out/k6-orders.log --log-format=raw existing-load.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN || 'r1';
const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '300s';
const SYMBOLS = Array.from({ length: 10 }, (_, i) => 'SYM' + String(i + 1).padStart(2, '0'));
const PARAMS = { headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { name: 'order' } };
const ordersNot202 = new Counter('orders_not_202');

export const options = {
  scenarios: {
    existing: {
      executor: 'constant-arrival-rate', exec: 'place', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.ceil(RATE / 5)), maxVUs: Math.max(200, RATE * 2),
    },
  },
};

export function setup() {
  console.log(`START ${Date.now()} 0 ${DURATION}`);
}

export function place() {
  const i = exec.scenario.iterationInTest;
  const buy = i % 2 === 0;
  const account = buy ? ((i / 2) % 500) + 1 : (((i - 1) / 2) % 500) + 501;
  const symbol = SYMBOLS[Math.floor(i / 2) % SYMBOLS.length];
  const clientOrderId = `q6-${RUN}-${i}`;
  const body = JSON.stringify({ clientOrderId, symbolCode: symbol, side: buy ? 'BUY' : 'SELL', limitPrice: 1000, quantity: 1 });
  const sentMs = Date.now();
  const res = http.post(`${BASE}/api/accounts/${account}/orders`, body, PARAMS);
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
