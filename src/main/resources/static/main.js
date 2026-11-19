import { config } from './config.js';
import { api, newClientOrderId } from './api.js';
import * as view from './view.js';

const $ = (id) => document.getElementById(id);

const state = {
  accountId: config.defaultAccountId,
  symbolCode: null,
};

async function refresh() {
  const tasks = [
    api.balance(state.accountId).then((b) => view.renderBalance($('balance'), b)),
    api.holdings(state.accountId).then((h) => view.renderHoldings($('holdings'), h)),
    api.orders(state.accountId).then((o) => view.renderOrders($('orders'), o)),
  ];
  if (state.symbolCode) {
    tasks.push(
      api.orderBook(state.symbolCode).then((b) =>
        view.renderOrderBook($('orderbook'), b, (price) => ($('price').value = price)),
      ),
    );
  }
  const results = await Promise.allSettled(tasks);
  const failed = results.find((r) => r.status === 'rejected');
  $('connection').textContent = failed ? '조회 실패: ' + view.errorText(failed.reason) : '1초마다 갱신 중';
  $('connection').className = failed ? 'connection error' : 'connection';
}

async function placeOrder(event) {
  event.preventDefault();
  const side = event.submitter?.value ?? 'BUY';
  const order = {
    clientOrderId: newClientOrderId(),
    symbolCode: state.symbolCode,
    side,
    limitPrice: Number($('price').value),
    quantity: Number($('quantity').value),
  };
  try {
    const { orderId } = await api.placeOrder(state.accountId, order);
    view.showMessage($('message'), `${side === 'BUY' ? '매수' : '매도'} 주문 접수 (주문번호 ${orderId})`, 'ok');
    refresh();
  } catch (error) {
    view.showMessage($('message'), view.errorText(error), 'error');
  }
}

async function start() {
  $('account').value = state.accountId;
  $('account').addEventListener('change', () => {
    state.accountId = Number($('account').value);
    refresh();
  });
  $('symbol').addEventListener('change', () => {
    state.symbolCode = $('symbol').value;
    refresh();
  });
  $('order-form').addEventListener('submit', placeOrder);

  const symbols = await api.symbols();
  state.symbolCode = symbols.find((s) => s.status === 'ACTIVE')?.code ?? null;
  view.renderSymbols($('symbol'), symbols, state.symbolCode);

  await refresh();
  setInterval(refresh, config.pollIntervalMs);
}

start().catch((error) => view.showMessage($('message'), view.errorText(error), 'error'));
