// 화면 그리기. 서버 데이터는 textContent로만 넣는다(HTML로 해석하지 않는다).

const STATUS_LABEL = {
  RECEIVED: '접수됨',
  OPEN: '대기',
  PARTIALLY_FILLED: '부분 체결',
  FILLED: '체결 완료',
};

const ERROR_LABEL = {
  INSUFFICIENT_CASH: '주문 가능 현금이 부족합니다.',
  INSUFFICIENT_QUANTITY: '매도 가능 수량이 부족합니다.',
  SYMBOL_NOT_TRADABLE: '거래할 수 없는 종목입니다.',
  ACCOUNT_NOT_FOUND: '없는 계좌입니다.',
  AMOUNT_OUT_OF_RANGE: '주문 금액이 너무 큽니다.',
  REQUEST_ID_CONFLICT: '같은 요청번호로 다른 주문을 보냈습니다.',
  INVALID_REQUEST: '입력값을 확인하세요.',
};

const won = (n) => Number(n).toLocaleString('ko-KR');

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function row(cells, className) {
  const tr = el('tr', undefined, className);
  cells.forEach((c) => tr.append(el('td', c)));
  return tr;
}

export function renderSymbols(select, symbols, selected) {
  select.replaceChildren(
    ...symbols.map((s) => {
      const option = el('option', `${s.code} ${s.name}`);
      option.value = s.code;
      option.disabled = s.status !== 'ACTIVE';
      option.selected = s.code === selected;
      return option;
    }),
  );
}

export function renderBalance(target, balance) {
  target.replaceChildren(
    row(['주문 가능 현금', won(balance.availableCash) + '원']),
    row(['주문에 묶인 현금', won(balance.reservedCash) + '원']),
    row(['총현금', won(balance.availableCash + balance.reservedCash) + '원']),
  );
}

export function renderHoldings(target, holdings) {
  if (holdings.length === 0) {
    target.replaceChildren(row(['보유 종목 없음', '', '', '']));
    return;
  }
  target.replaceChildren(
    ...holdings.map((h) => row([h.symbolCode, won(h.quantity), won(h.reservedQuantity), won(h.sellableQuantity)])),
  );
}

export function renderOrders(target, orders) {
  if (orders.length === 0) {
    target.replaceChildren(row(['주문 없음', '', '', '', '', '']));
    return;
  }
  target.replaceChildren(
    ...orders.map((o) =>
      row(
        [
          o.orderId,
          o.symbolCode,
          o.side === 'BUY' ? '매수' : '매도',
          won(o.limitPrice),
          `${won(o.quantity - o.remaining)} / ${won(o.quantity)}`,
          STATUS_LABEL[o.status] ?? o.status,
        ],
        'status-' + o.status.toLowerCase() + ' side-' + o.side.toLowerCase(),
      ),
    ),
  );
}

/** 매도 호가를 위(높은 가격 → 낮은 가격), 매수 호가를 아래(높은 가격 → 낮은 가격)에 그린다. 가격을 누르면 onPick. */
export function renderOrderBook(target, book, onPick) {
  const line = (level, side) => {
    const tr = row(
      side === 'SELL' ? [won(level.quantity), won(level.price), ''] : ['', won(level.price), won(level.quantity)],
      'book-' + side.toLowerCase(),
    );
    tr.title = `${level.orderCount}건`;
    tr.addEventListener('click', () => onPick(level.price));
    return tr;
  };
  const asks = [...book.asks].reverse().map((l) => line(l, 'SELL'));
  const bids = book.bids.map((l) => line(l, 'BUY'));
  const empty = (text) => row(['', text, ''], 'book-empty');
  target.replaceChildren(
    ...(asks.length ? asks : [empty('매도 호가 없음')]),
    ...(bids.length ? bids : [empty('매수 호가 없음')]),
  );
}

export function showMessage(target, text, kind) {
  target.textContent = text;
  target.className = 'message ' + kind;
}

export function errorText(error) {
  return ERROR_LABEL[error.code] ?? `${error.code}: ${error.message}`;
}
