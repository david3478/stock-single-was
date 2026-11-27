import { config } from './config.js';
import { api, newClientOrderId } from './api.js';
import { subscribeNotifications } from './notifications.js';
import * as view from './view.js';

const $ = (id) => document.getElementById(id);

const CONNECTION_LABEL = {
  open: '실시간 연결됨',
  reconnecting: '재연결 중…',
  closed: '알림 연결 실패',
};

const state = {
  accountId: config.defaultAccountId,
  symbolCode: null,
  unsubscribe: null,
  accountRefreshTimer: null,
};

// 잔고·보유·내 주문. 알림을 받았을 때와 연결·재연결됐을 때 읽는다.
async function refreshAccount() {
  const results = await Promise.allSettled([
    api.balance(state.accountId).then((b) => view.renderBalance($('balance'), b)),
    api.holdings(state.accountId).then((h) => view.renderHoldings($('holdings'), h)),
    api.orders(state.accountId).then((o) => view.renderOrders($('orders'), o)),
  ]);
  const failed = results.find((r) => r.status === 'rejected');
  if (failed) {
    view.showMessage($('message'), '조회 실패: ' + view.errorText(failed.reason), 'error');
  }
}

// 체결이 몰리면 알림이 연달아 오므로 짧은 시간 안의 알림은 한 번의 조회로 묶는다.
function scheduleAccountRefresh() {
  if (state.accountRefreshTimer !== null) return;
  state.accountRefreshTimer = setTimeout(() => {
    state.accountRefreshTimer = null;
    refreshAccount();
  }, config.accountRefreshDelayMs);
}

// 호가창은 다른 계좌의 주문으로도 바뀌어 내 계좌 알림으로는 알 수 없으므로 주기적으로 읽는다.
async function refreshOrderBook() {
  if (!state.symbolCode) return;
  try {
    const book = await api.orderBook(state.symbolCode);
    view.renderOrderBook($('orderbook'), book, (price) => ($('price').value = price));
  } catch (error) {
    view.showMessage($('message'), '호가창 조회 실패: ' + view.errorText(error), 'error');
  }
}

function connectNotifications() {
  state.unsubscribe?.();
  view.clearNotifications($('notifications'));
  state.unsubscribe = subscribeNotifications(state.accountId, {
    onNotification: (n) => {
      view.addNotification($('notifications'), n, config.notificationListSize);
      scheduleAccountRefresh();
    },
    onStatus: (status) => {
      view.showConnection($('connection'), CONNECTION_LABEL[status], status !== 'open');
      // 끊긴 동안의 알림은 Last-Event-ID로 다시 오지만, 화면 값은 연결될 때 한 번 맞춰 둔다
      if (status === 'open') scheduleAccountRefresh();
    },
  });
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
    refreshAccount();
    refreshOrderBook();
  } catch (error) {
    view.showMessage($('message'), view.errorText(error), 'error');
  }
}

async function start() {
  $('account').value = state.accountId;
  $('account').addEventListener('change', () => {
    state.accountId = Number($('account').value);
    connectNotifications();
    refreshAccount();
  });
  $('symbol').addEventListener('change', () => {
    state.symbolCode = $('symbol').value;
    refreshOrderBook();
  });
  $('order-form').addEventListener('submit', placeOrder);

  const symbols = await api.symbols();
  state.symbolCode = symbols.find((s) => s.status === 'ACTIVE')?.code ?? null;
  view.renderSymbols($('symbol'), symbols, state.symbolCode);

  connectNotifications();
  await Promise.all([refreshAccount(), refreshOrderBook()]);
  setInterval(refreshOrderBook, config.orderBookPollMs);
}

start().catch((error) => view.showMessage($('message'), view.errorText(error), 'error'));
