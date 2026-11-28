// QAS 6 신규 종목 체결 검증(시간 목표 없음, IMPLEMENTATION_GUIDE §10 QAS 6 4번).
// new-symbol-holdings.sql로 매도 물량을 넣은 뒤 한 번 실행한다. 신규 종목마다 매도 1주(계좌 501+k)와 매수 1주(계좌 1+k)를 1,000원에 내고,
// 두 주문이 FILLED가 될 때까지 주문 내역을 조회한다(최대 WAIT_S초). 판정은 체결 여부이고, 걸린 시간은 참고로만 기록한다.
//   k6 run -e BASE_URL=http://<앱> -e RUN=r1 [-e PREFIX=NEW -e COUNT=10 -e WAIT_S=30] verify-new.js
import http from 'k6/http';
import { sleep } from 'k6';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN || 'r1';
const PREFIX = __ENV.PREFIX || 'NEW';
const COUNT = Number(__ENV.COUNT || 10);
const WAIT_S = Number(__ENV.WAIT_S || 30);
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, timeout: '10s' };

export const options = { vus: 1, iterations: 1 };

function place(account, symbol, side, tag) {
  const clientOrderId = `q6v-${RUN}-${symbol}-${tag}`;
  const res = http.post(`${BASE}/api/accounts/${account}/orders`,
    JSON.stringify({ clientOrderId, symbolCode: symbol, side, limitPrice: 1000, quantity: 1 }), JSON_HEADERS);
  return { status: res.status, orderId: res.status === 202 ? res.json('orderId') : null };
}

function statusOf(account, orderId) {
  const res = http.get(`${BASE}/api/accounts/${account}/orders?size=50`, { timeout: '10s' });
  if (res.status !== 200) return `http_${res.status}`;
  const found = res.json().find((o) => o.orderId === orderId);
  return found ? found.status : 'not_found';
}

export default function () {
  let filled = 0;
  for (let k = 0; k < COUNT; k++) {
    const symbol = PREFIX + String(k + 1).padStart(2, '0');
    const seller = 501 + k;
    const buyer = 1 + k;
    const start = Date.now();
    const sell = place(seller, symbol, 'SELL', 'sell');
    const buy = place(buyer, symbol, 'BUY', 'buy');
    let sellStatus = '-';
    let buyStatus = '-';
    if (sell.orderId && buy.orderId) {
      const deadline = start + WAIT_S * 1000;
      while (Date.now() < deadline) {
        sellStatus = statusOf(seller, sell.orderId);
        buyStatus = statusOf(buyer, buy.orderId);
        if (sellStatus === 'FILLED' && buyStatus === 'FILLED') break;
        sleep(0.2);
      }
    }
    const ok = sellStatus === 'FILLED' && buyStatus === 'FILLED';
    if (ok) filled++;
    console.log(`VERIFY ${symbol} sell_http=${sell.status} buy_http=${buy.status} sell=${sellStatus} buy=${buyStatus} `
      + `filled=${ok} elapsed_ms=${Date.now() - start} sell_order=${sell.orderId} buy_order=${buy.orderId}`);
  }
  console.log(`VERIFY_SUMMARY filled=${filled}/${COUNT}`);
}
