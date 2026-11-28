-- 사전 매도(시험 데이터, docs/measurement/plan-stage10.md 2.2). 종목 하나에 1,000원 1주 매도를 @n건 넣는다.
-- 측정 대상이 아니라 시험 데이터이므로 주문 API를 거치지 않고 DB에 OPEN으로 직접 넣는다.
-- 앱을 멈춘 상태에서 실행하고, 끝나면 앱을 다시 띄운다. 기동 복구가 DB의 대기 주문으로 호가창을 만든다(ADR 0008, 0013).
-- 실행 예(종목마다 한 번):
--   { echo "SET @sym = 'SYM01', @n = 9000;"; cat scripts/seed/pre-sells.sql; } | mysql ... stock_single_was
--
-- 매도 계좌는 501~1000을 돌아가며 쓴다(매수 계좌 1~500과 겹치지 않아 자기 체결이 없다).
-- 처리순번(seq)은 그 종목의 기존 최댓값 다음부터 붙인다. 주문번호 순서와 처리순번 순서가 같다.
-- 사전 매도의 대기 등록 알림(ORDER_OPEN)은 만들지 않는다. 측정 대상이 아니다.
-- 보유의 예약 수량은 더하지 않고 그 종목의 대기 매도 남은 수량 합으로 다시 계산한다. 여러 번 실행해도 이중 예약이 없다.

SET SESSION cte_max_recursion_depth = 100000;
SET @seq_base = (SELECT COALESCE(MAX(seq), 0) FROM orders WHERE symbol_code = @sym);
SET @no_base = (SELECT COUNT(*) FROM orders WHERE symbol_code = @sym AND client_order_id LIKE 'pre-%');

INSERT INTO orders (account_id, symbol_code, client_order_id, side, limit_price, qty, remaining, status, seq)
WITH RECURSIVE n (i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @n
)
SELECT 501 + MOD(i - 1, 500), @sym, CONCAT('pre-', @sym, '-', @no_base + i), 'SELL', 1000, 1, 1, 'OPEN', @seq_base + i
FROM n;

UPDATE holdings h
SET h.reserved_quantity = (
  SELECT COALESCE(SUM(o.remaining), 0) FROM orders o
  WHERE o.account_id = h.account_id AND o.symbol_code = h.symbol_code
    AND o.side = 'SELL' AND o.status IN ('RECEIVED', 'OPEN', 'PARTIALLY_FILLED'))
WHERE h.symbol_code = @sym;

SELECT @sym AS symbol, COUNT(*) AS open_sells, MAX(seq) AS max_seq
FROM orders WHERE symbol_code = @sym AND side = 'SELL' AND status = 'OPEN';
