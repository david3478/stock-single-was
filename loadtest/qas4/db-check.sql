-- QAS 4 DB 점검. 재시작 후 처리가 끝난 뒤 실행한다. 데모 seed 기준(계좌 1,000개 × 1억 원, 종목 10개 × 계좌당 1,000주).
-- 아래 불변식이 하나라도 깨지면 이중 체결·이중 정산·예약 누수가 있다는 뜻이다. 모든 diff 열이 0이어야 한다.

-- 1. 주문 상태별 건수. fill-: 대기 주문 10만 건, kill-: 강제 종료 중 보낸 주문, probe-: 거래 재개 확인 주문
SELECT SUBSTRING_INDEX(client_order_id, '-', 1) AS kind, status, COUNT(*) AS orders
FROM orders GROUP BY kind, status ORDER BY kind, status;

-- 2. 아직 처리되지 않은 주문. 재시작 후 처리가 끝났으면 0
SELECT COUNT(*) AS still_received FROM orders WHERE status = 'RECEIVED';

-- 3. 현금 총량 보존: 체결은 계좌 사이에서 현금을 옮길 뿐이다
SELECT SUM(cash_balance + reserved_cash) - 1000 * 100000000 AS cash_total_diff FROM accounts;

-- 4. 종목별 보유 총량 보존
SELECT symbol_code, SUM(quantity) - 1000 * 1000 AS holding_total_diff
FROM holdings GROUP BY symbol_code HAVING holding_total_diff <> 0;

-- 5. 예약 현금 = 대기 중인 매수의 남은 수량 × 지정가 (계좌별로 어긋난 곳만)
SELECT a.id, a.reserved_cash - COALESCE(o.need, 0) AS reserved_cash_diff
FROM accounts a
LEFT JOIN (SELECT account_id, SUM(remaining * limit_price) AS need FROM orders
           WHERE side = 'BUY' AND status IN ('RECEIVED', 'OPEN', 'PARTIALLY_FILLED') GROUP BY account_id) o
  ON o.account_id = a.id
WHERE a.reserved_cash <> COALESCE(o.need, 0);

-- 6. 예약 수량 = 대기 중인 매도의 남은 수량 (계좌·종목별로 어긋난 곳만)
SELECT h.account_id, h.symbol_code, h.reserved_quantity - COALESCE(o.need, 0) AS reserved_qty_diff
FROM holdings h
LEFT JOIN (SELECT account_id, symbol_code, SUM(remaining) AS need FROM orders
           WHERE side = 'SELL' AND status IN ('RECEIVED', 'OPEN', 'PARTIALLY_FILLED') GROUP BY account_id, symbol_code) o
  ON o.account_id = h.account_id AND o.symbol_code = h.symbol_code
WHERE h.reserved_quantity <> COALESCE(o.need, 0);

-- 7. 주문별 체결 수량 = 주문 수량 - 남은 수량 (어긋난 주문만)
SELECT o.id, (o.qty - o.remaining) - COALESCE(t.filled, 0) AS filled_diff
FROM orders o
LEFT JOIN (SELECT order_id, SUM(quantity) AS filled FROM (
             SELECT buy_order_id AS order_id, quantity FROM trades
             UNION ALL SELECT sell_order_id, quantity FROM trades) x GROUP BY order_id) t
  ON t.order_id = o.id
WHERE (o.qty - o.remaining) <> COALESCE(t.filled, 0);
