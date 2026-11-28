-- 측정 대상 주문 분류(docs/measurement/plan-stage10.md 1.5). QAS 2·3 판정 SQL 앞에 붙여 쓴다.
--   { echo "SET @run = 'r1';"; cat classify.sql qas2.sql checks.sql; } | mysql ...
-- 결과 테이블 measure.orders_cls. MySQL 임시 테이블은 한 쿼리에서 두 번 참조할 수 없어 일반 테이블로 두고 실행할 때마다 다시 만든다.
-- judged = 보낸 시각이 시작 + 예열 이후인 주문. latency_ms = 그 주문의 첫 체결 알림을 받은 시각 - 보낸 시각(같은 생성기 벽시계)

DROP TABLE IF EXISTS measure.orders_cls;
CREATE TABLE measure.orders_cls AS
SELECT k.seq, k.account_id, k.client_order_id, k.symbol_code, k.sent_ms, k.http_status,
       -- @warmup_marker가 있으면 그 이름(clientOrderId LIKE)의 주문만 예열로 빼고(QAS 3), 없으면 보낸 시각으로 가른다(QAS 2)
       IF(@warmup_marker IS NULL, k.sent_ms >= r.start_ms + r.warmup_s * 1000, k.client_order_id NOT LIKE @warmup_marker) AS judged,
       o.id AS order_id, o.status AS db_status, o.seq AS order_seq,
       (SELECT COUNT(*) FROM stock_single_was.trades t WHERE t.buy_order_id = o.id) AS db_fills,
       (SELECT MIN(e.recv_ms) FROM measure.sse_events e
         WHERE e.run = k.run AND e.account_id = k.account_id AND e.order_id = o.id AND e.trade_id IS NOT NULL) AS fill_recv_ms,
       (SELECT COUNT(*) FROM measure.sse_events e
         WHERE e.run = k.run AND e.account_id = k.account_id AND e.order_id = o.id AND e.trade_id IS NOT NULL) AS fill_receptions
FROM measure.k6_orders k
JOIN measure.k6_runs r ON r.run = k.run
LEFT JOIN stock_single_was.orders o ON o.account_id = k.account_id AND o.client_order_id = k.client_order_id
WHERE k.run = @run;

-- 분류. X는 202를 받았는데 DB에 없는 주문으로, 있어서는 안 되는 경우다. F(미전송)는 기록에 없어 k6 요약으로 센다
ALTER TABLE measure.orders_cls ADD COLUMN cls CHAR(1), ADD COLUMN latency_ms BIGINT;
UPDATE measure.orders_cls SET
  cls = CASE
    WHEN order_id IS NULL AND http_status = 202 THEN 'X'
    WHEN order_id IS NULL THEN 'E'
    WHEN db_fills > 1 THEN 'G'
    WHEN db_status <> 'FILLED' THEN 'C'
    WHEN http_status <> 202 THEN 'D'
    WHEN fill_recv_ms IS NULL THEN 'B'
    ELSE 'A' END,
  latency_ms = fill_recv_ms - sent_ms;

SELECT '1. 주문 수' AS section, SUM(judged) AS judged, SUM(NOT judged) AS warmup, COUNT(*) AS sent_total FROM measure.orders_cls;

SELECT '2. 분류(판정 대상)' AS section, cls, COUNT(*) AS orders FROM measure.orders_cls WHERE judged GROUP BY cls ORDER BY cls;
