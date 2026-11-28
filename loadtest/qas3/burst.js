// QAS 3 순간 주문 폭주(docs/measurement/plan-stage10.md 4절). 이전 번호 체계의 qas2.js에 해당한다.
// 예열: 10종목에 초당 WARMUP_RATE건, WARMUP_S초. 폭주: 10초 동안 SYM01 초당 2,000건 + 나머지 9종목 합계 초당 1,000건.
// 측정 대상은 즉시 체결 가능한 매수 1주(지정가 1,100원). 사전 매도는 scripts/seed/pre-sells.sql로 넣는다(SYM01 25,000, 나머지 1,500).
// SSE 수집기가 매수 계좌 1~500에 연결해 READY가 된 뒤 실행한다. 판정은 분석(loadtest/analysis/qas3.sql)에서 한다.
//
// 기록 형식은 QAS 2와 같다(ORD/START 줄, loadtest/analysis/load.sh로 적재).
// 판정 대상은 폭주 시나리오(hot, others)의 주문이고, clientOrderId의 시나리오 이름으로 구분한다(q3-<run>-<시나리오>-<순번>).
// 계획 수는 HOT_RATE × BURST_S + OTHER_RATE × BURST_S(기본 30,000)이고, 보낸 수와의 차이가 미전송이다.
//
//   k6 run -e BASE_URL=http://<앱 사설 IP> -e RUN=r1 --log-output=file=/out/k6-orders.log --log-format=raw burst.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN || 'r1';
const HOT_RATE = Number(__ENV.HOT_RATE || 2000);
const OTHER_RATE = Number(__ENV.OTHER_RATE || 1000);
const BURST_S = Number(__ENV.BURST_S || 10);
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 100);
const WARMUP_S = Number(__ENV.WARMUP_S || 15);
const SYMBOLS = Array.from({ length: 10 }, (_, i) => 'SYM' + String(i + 1).padStart(2, '0'));
const OTHERS = SYMBOLS.slice(1);
const PARAMS = { headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { name: 'order' } };

const ordersNot202 = new Counter('orders_not_202');

function arrival(execName, rate, duration, startTime) {
  return {
    executor: 'constant-arrival-rate', exec: execName, rate, timeUnit: '1s', duration, startTime,
    // 최대 VU는 평균 응답 2초까지 버티는 수준. 그보다 느려져 VU가 모자라면 미전송(앱 응답 지연 원인)으로 센다(plan 1.7)
    preAllocatedVUs: Math.max(50, Math.ceil(rate / 5)), maxVUs: Math.max(200, rate * 2),
  };
}

export const options = {
  scenarios: {
    warmup: arrival('warmup', WARMUP_RATE, `${WARMUP_S}s`, '0s'),
    hot: arrival('hot', HOT_RATE, `${BURST_S}s`, `${WARMUP_S}s`),
    others: arrival('others', OTHER_RATE, `${BURST_S}s`, `${WARMUP_S}s`),
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  console.log(`START ${Date.now()} ${WARMUP_S} ${WARMUP_S + BURST_S}s`);
  console.log(`PLANNED ${HOT_RATE * BURST_S + OTHER_RATE * BURST_S} hot=${HOT_RATE * BURST_S} others=${OTHER_RATE * BURST_S}`);
}

function placeBuy(scenario, symbol, accountOffset) {
  const i = exec.scenario.iterationInTest;
  const account = ((i + accountOffset) % 500) + 1;
  const clientOrderId = `q3-${RUN}-${scenario}-${i}`;
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
  console.log(`ORD ${scenario === 'hot' ? 1e9 + i : scenario === 'others' ? 2e9 + i : i} ${account} ${clientOrderId} ${symbol} ${sentMs} ${res.status} ${orderId} ${res.timings.duration.toFixed(1)}`);
}

export function warmup() {
  placeBuy('warmup', SYMBOLS[exec.scenario.iterationInTest % SYMBOLS.length], 0);
}

export function hot() {
  placeBuy('hot', 'SYM01', 0);
}

export function others() {
  placeBuy('others', OTHERS[exec.scenario.iterationInTest % OTHERS.length], 250);
}
