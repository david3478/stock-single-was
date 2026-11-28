// QAS 5 체결 알림용 부하(docs/measurement/plan-stage10.md 5절). 측정 대상 매수 1주(지정가 1,100원)를 일정 유입으로 보낸다.
// 사전 매도(scripts/seed/pre-sells.sql, 계좌 501~1000)와 체결되어 매수자·매도자에게 체결 알림이 1건씩 생긴다.
// 단절·재연결은 SSE 수집기(--cycles)가 하고, 이 스크립트는 알림이 계속 생기게 하는 역할이다. 기록 형식은 QAS 2와 같다.
//   k6 run -e BASE_URL=http://<앱 사설 IP> -e RUN=r1 -e RATE=1000 -e DURATION=300s --log-output=file=/out/k6-orders.log --log-format=raw orders.js
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
    orders: {
      executor: 'constant-arrival-rate', exec: 'placeBuy', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.ceil(RATE / 5)), maxVUs: Math.max(200, RATE * 2),
    },
  },
};

export function setup() {
  console.log(`START ${Date.now()} 0 ${DURATION}`);
}

export function placeBuy() {
  const i = exec.scenario.iterationInTest;
  const account = (i % 500) + 1;
  const symbol = SYMBOLS[i % SYMBOLS.length];
  const clientOrderId = `q5-${RUN}-${i}`;
  const body = JSON.stringify({ clientOrderId, symbolCode: symbol, side: 'BUY', limitPrice: 1100, quantity: 1 });
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
