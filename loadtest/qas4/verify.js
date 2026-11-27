// QAS 4 판정: orders.js가 남긴 접수 확정 주문이 재시작 후 DB에 모두 있는지 API로 대조한다.
// 계좌별 주문 내역(최신순)을 접수 확정 주문 중 가장 작은 번호까지 읽는다.
// 상태와 무관하게 주문이 있으면 유실이 아니다. 복구 후 체결된 주문을 유실로 오판하지 않는다(가이드 QAS 4).
// RECEIVED로 남은 주문은 따로 센다. 재시작 후 처리가 끝났다면 0이어야 한다.
//   k6 run -e BASE_URL=http://<앱 EC2 사설 IP> -e ACKS=/scripts/acks.log verify.js
import http from 'k6/http';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL;
const PAGE = 100;

const acks = new SharedArray('acks', () =>
  open(__ENV.ACKS)
    .split('\n')
    .filter((line) => line.includes('ACK '))
    .map((line) => {
      const [, id, account, clientOrderId] = line.slice(line.indexOf('ACK ')).trim().split(' ');
      return { id: Number(id), account: Number(account), clientOrderId };
    }),
);

export const options = { vus: 1, iterations: 1 };

export default function () {
  const byAccount = new Map();
  for (const a of acks) {
    if (!byAccount.has(a.account)) byAccount.set(a.account, new Set());
    byAccount.get(a.account).add(a.id);
  }

  let found = 0;
  let stillReceived = 0;
  const missing = [];
  for (const [account, ids] of byAccount) {
    const minId = Math.min(...ids);
    const seen = new Map();
    let beforeId = null;
    for (;;) {
      const url = `${BASE}/api/accounts/${account}/orders?size=${PAGE}` + (beforeId ? `&beforeId=${beforeId}` : '');
      const res = http.get(url, { timeout: '10s' });
      if (res.status !== 200) throw new Error(`history ${account} -> ${res.status}`);
      const page = res.json();
      for (const o of page) seen.set(o.orderId, o.status);
      if (page.length < PAGE || page[page.length - 1].orderId <= minId) break;
      beforeId = page[page.length - 1].orderId;
    }
    for (const id of ids) {
      if (!seen.has(id)) missing.push(id);
      else {
        found++;
        if (seen.get(id) === 'RECEIVED') stillReceived++;
      }
    }
  }

  const duplicates = acks.length - new Set(acks.map((a) => a.id)).size;
  const result = { acked: acks.length, found, missing: missing.length, missingIds: missing.slice(0, 20),
    stillReceived, duplicateAcks: duplicates, pass: missing.length <= 1 };
  console.log(`QAS4_RESULT ${JSON.stringify(result)}`);
}
