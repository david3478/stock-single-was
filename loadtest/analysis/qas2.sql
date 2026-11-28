-- QAS 2 대조·판정(docs/measurement/plan-stage10.md 1.5~1.7, 3절). 실행 전에 SET @run = '<run>'; 을 붙인다.
--   { echo "SET @run = 'r1';"; cat loadtest/analysis/qas2.sql; } | mysql ...
-- 앱 스키마는 stock_single_was, 기록은 measure(load.sh로 적재). 측정의 부하·관찰이 끝난 뒤에만 실행한다.
-- 미전송(F)은 DB와 기록에 없으므로 k6 요약의 dropped_iterations와 계획 수로 따로 집계한다(plan 1.7).

-- 작업 테이블. MySQL 임시 테이블은 한 쿼리에서 두 번 참조할 수 없어 일반 테이블로 둔다(실행할 때마다 다시 만든다)
DROP TABLE IF EXISTS measure.q2;
CREATE TABLE measure.q2 AS
SELECT k.seq, k.account_id, k.client_order_id, k.symbol_code, k.sent_ms, k.http_status,
       (k.sent_ms >= r.start_ms + r.warmup_s * 1000) AS judged,
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

-- 분류(plan 1.5). X는 202를 받았는데 DB에 없는 주문으로, 있어서는 안 되는 경우다
ALTER TABLE measure.q2 ADD COLUMN cls CHAR(1), ADD COLUMN latency_ms BIGINT;
UPDATE measure.q2 SET
  cls = CASE
    WHEN order_id IS NULL AND http_status = 202 THEN 'X'
    WHEN order_id IS NULL THEN 'E'
    WHEN db_fills > 1 THEN 'G'
    WHEN db_status <> 'FILLED' THEN 'C'
    WHEN http_status <> 202 THEN 'D'
    WHEN fill_recv_ms IS NULL THEN 'B'
    ELSE 'A' END,
  latency_ms = fill_recv_ms - sent_ms;

SELECT '1. 주문 수' AS section, SUM(judged) AS judged, SUM(NOT judged) AS warmup, COUNT(*) AS sent_total FROM measure.q2;

SELECT '2. 분류(판정 대상)' AS section, cls, COUNT(*) AS orders FROM measure.q2 WHERE judged GROUP BY cls ORDER BY cls;

SELECT '3. 판정: 300ms 안 체결 알림 비율' AS section,
       SUM(cls = 'A' AND latency_ms <= 300) AS within_300ms, SUM(judged) AS judged,
       ROUND(100 * SUM(cls = 'A' AND latency_ms <= 300) / SUM(judged), 4) AS pct,
       IF(SUM(cls = 'A' AND latency_ms <= 300) / SUM(judged) >= 0.99, 'P99<=300ms 충족', 'P99<=300ms 미충족') AS verdict_before_f_and_validity
FROM measure.q2 WHERE judged;

-- 백분위는 최근접 순위(nearest-rank): 정렬한 n개 중 CEIL(n × p)번째 값
SELECT '4. 성공(A) 지연 백분위 ms' AS section, COUNT(*) AS n,
       MAX(CASE WHEN rn = CEIL(n * 0.50) THEN latency_ms END) AS p50,
       MAX(CASE WHEN rn = CEIL(n * 0.95) THEN latency_ms END) AS p95,
       MAX(CASE WHEN rn = CEIL(n * 0.99) THEN latency_ms END) AS p99,
       MAX(latency_ms) AS max_ms, MIN(latency_ms) AS min_ms
FROM (SELECT latency_ms, ROW_NUMBER() OVER (ORDER BY latency_ms) rn, COUNT(*) OVER () n
      FROM measure.q2 WHERE judged AND cls = 'A') x;

SELECT '5. HTTP 실패지만 커밋됨(D) 세부' AS section, http_status, db_status, (fill_recv_ms IS NOT NULL) AS alert_received, COUNT(*) AS orders
FROM measure.q2 WHERE judged AND cls = 'D' GROUP BY http_status, db_status, alert_received;

SELECT '6. 수 구분(plan 1.5)' AS section,
       (SELECT COUNT(*) FROM measure.q2 WHERE db_fills > 1) AS orders_with_multiple_db_fills,
       (SELECT COUNT(*) FROM measure.q2 WHERE fill_receptions > 1) AS orders_with_multiple_fill_receptions,
       (SELECT COUNT(*) FROM (SELECT account_id, event_id FROM measure.sse_events WHERE run = @run
                               GROUP BY account_id, event_id HAVING COUNT(*) > 1) d) AS event_ids_received_more_than_once;

-- 7. C 원인 판정(plan 1.6): 그 주문을 처리하던 순간 남은 사전 매도 = 앞선 순번의 사전 매도 수 - 앞선 순번 매수가 가져간 체결 수
SELECT '7. 즉시 체결 안 됨(C) 원인' AS section, cause, COUNT(*) AS orders FROM (
  SELECT CASE
      WHEN q.order_seq IS NULL THEN '미처리(RECEIVED, 처리순번 없음)'
      WHEN (SELECT COUNT(*) FROM stock_single_was.orders s
             WHERE s.symbol_code = q.symbol_code AND s.side = 'SELL' AND s.client_order_id LIKE 'pre-%' AND s.seq < q.order_seq)
         - (SELECT COUNT(*) FROM stock_single_was.trades t JOIN stock_single_was.orders b ON b.id = t.buy_order_id
             WHERE b.symbol_code = q.symbol_code AND b.seq < q.order_seq) <= 0 THEN '시험 준비 오류: 남은 사전 매도 없음'
      ELSE '시스템 동작 문제: 남은 매도가 있는데 체결 안 됨'
    END AS cause
  FROM measure.q2 q WHERE q.cls = 'C') c
GROUP BY cause;

SELECT '8. 시험 구성 위반(있으면 준비 오류)' AS section,
       (SELECT COUNT(*) FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND (limit_price <> 1000 OR qty <> 1)) AS pre_sells_not_1000_1,
       (SELECT COUNT(*) FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND account_id BETWEEN 1 AND 500) AS pre_sells_from_buyer_accounts,
       (SELECT COUNT(DISTINCT account_id) FROM measure.q2) AS buyer_accounts_used,
       (SELECT COUNT(*) FROM measure.q2 WHERE account_id NOT BETWEEN 1 AND 500) AS orders_from_non_buyer_accounts;

SELECT '9. 측정 끝 남은 사전 매도' AS section, symbol_code, COUNT(*) AS open_pre_sells
FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND status = 'OPEN' GROUP BY symbol_code ORDER BY symbol_code;

SELECT '10. 수집기 오류 표시' AS section, kind, LEFT(value, 40) AS reason, COUNT(*) AS n
FROM measure.sse_markers WHERE run = @run AND kind = 'error' GROUP BY kind, reason;
