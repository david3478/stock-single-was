// QAS 4 준비: 서로 체결되지 않는 대기 주문을 TOTAL건(기본 10만) 쌓는다.
// 매수는 900원, 매도는 1,100원이라 체결 상대가 없어 모두 OPEN으로 남는다.
// 계좌 i % 1000 + 1, 종목 SYM01~SYM10을 1,000건씩 돌아가며 쓴다. 데모 seed(계좌 1,000개, 계좌당 1억 원, 종목당 1,000주) 기준이다.
//   k6 run -e BASE_URL=http://<앱 EC2 사설 IP> fill.js
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const TOTAL = Number(__ENV.TOTAL || 100000);
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, timeout: '10s' };
const failed = new Counter('fill_failed');

export const options = {
  scenarios: {
    fill: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 50), iterations: TOTAL, maxDuration: '30m' },
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const buy = i % 2 === 0;
  const order = {
    clientOrderId: `fill-${i}`,
    symbolCode: 'SYM' + String((Math.floor(i / 1000) % 10) + 1).padStart(2, '0'),
    side: buy ? 'BUY' : 'SELL',
    limitPrice: buy ? 900 : 1100,
    quantity: 1,
  };
  const res = http.post(`${BASE}/api/accounts/${(i % 1000) + 1}/orders`, JSON.stringify(order), JSON_HEADERS);
  if (!check(res, { accepted: (r) => r.status === 202 })) {
    failed.add(1);
  }
}
