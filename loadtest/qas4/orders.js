// QAS 4 본 실행: 일정한 유입으로 주문을 보내는 동안 앱을 강제 종료하고 다시 띄운다.
// 접수 확정(202)을 받은 주문만 "ACK <주문번호> <계좌> <요청번호>" 한 줄로 남긴다. 이 목록이 유실 판정의 기준이다.
// 1,000원 매수·매도라 서로 체결되므로 체결 확정(T2) 도중의 종료도 함께 일어난다.
// fill.js의 대기 주문(900원 매수, 1,100원 매도)과는 체결되지 않는다.
//   k6 run -e BASE_URL=http://<앱 EC2 사설 IP> --log-output=file=acks.log --log-format=raw orders.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN || 'r1';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, timeout: '5s' };
const acked = new Counter('orders_acked');
const notAcked = new Counter('orders_not_acked');

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '120s',
      // 앱이 죽어 있는 동안 요청이 시간 초과(5초)까지 VU를 붙잡으므로 유입의 6배를 미리 만든다
      preAllocatedVUs: Math.max(100, Number(__ENV.RATE || 200) * 6),
      maxVUs: Math.max(1000, Number(__ENV.RATE || 200) * 12),
    },
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const account = ((i * 7) % 1000) + 1;
  const clientOrderId = `kill-${RUN}-${i}`;
  const order = {
    clientOrderId,
    symbolCode: 'SYM' + String((i % 10) + 1).padStart(2, '0'),
    // 종목은 i % 10, 매수·매도는 종목마다 번갈아(i / 10의 짝홀). i % 2로 정하면 종목마다 한쪽만 생겨 체결이 일어나지 않는다
    side: Math.floor(i / 10) % 2 === 0 ? 'BUY' : 'SELL',
    limitPrice: 1000,
    quantity: 1,
  };
  const res = http.post(`${BASE}/api/accounts/${account}/orders`, JSON.stringify(order), JSON_HEADERS);
  if (res.status === 202) {
    acked.add(1);
    console.log(`ACK ${res.json('orderId')} ${account} ${clientOrderId}`);
  } else {
    // 앱이 죽어 있는 동안의 502, 연결 오류, 시간 초과. 접수 확정을 받지 못했으므로 유실 판정 대상이 아니다
    notAcked.add(1);
  }
}
