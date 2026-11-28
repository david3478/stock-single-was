-- QAS 6 판정(IMPLEMENTATION_GUIDE §10 QAS 6). 기존 종목 부하(existing-load.js)를 load.sh로 적재한 뒤 실행한다.
--   { echo "SET @run = 'r1', @reg_start_ms = <first_request_ms>, @reg_end_ms = <last_response_ms>, @prefix = 'NEW';"; cat qas6.sql; } | mysql ...
-- @reg_*는 register.sh의 register-summary.txt 값이다. 관찰 종료(부하를 멈춘 뒤 기존 종목 RECEIVED가 0이 되거나 대기 상한)에 실행한다.
-- 재시작 여부(restart_same)와 종목 추가 시간(total_ms, 종목별 register.csv)은 register.sh 출력으로 판정한다.

DROP TABLE IF EXISTS measure.q6_orders;
CREATE TABLE measure.q6_orders AS
SELECT k.seq, k.account_id, k.client_order_id, k.symbol_code, k.sent_ms, k.http_status, k.duration_ms,
       CASE WHEN k.sent_ms < @reg_start_ms THEN '1.추가 전' WHEN k.sent_ms <= @reg_end_ms THEN '2.추가 중' ELSE '3.추가 후' END AS phase,
       o.id AS order_id, o.status AS db_status
FROM measure.k6_orders k
LEFT JOIN stock_single_was.orders o ON o.account_id = k.account_id AND o.client_order_id = k.client_order_id
WHERE k.run = @run;

-- 2번(중단 없음): 예상하지 못한 오류·시간 초과. 시험 주문은 현금·보유가 충분하므로 202가 아닌 응답은 모두 예상하지 못한 오류다
SELECT '1. 구간별 기존 종목 주문' AS section, phase, COUNT(*) AS sent,
       SUM(http_status = 202) AS accepted,
       SUM(http_status BETWEEN 400 AND 499) AS unexpected_4xx,
       SUM(http_status >= 500) AS unexpected_5xx,
       SUM(http_status = 0) AS timeout_or_conn_error,
       ROUND(MAX(duration_ms)) AS max_response_ms
FROM measure.q6_orders GROUP BY phase ORDER BY phase;

SELECT '2. 오류 응답 상세' AS section, phase, http_status, COUNT(*) AS orders
FROM measure.q6_orders WHERE http_status <> 202 GROUP BY phase, http_status ORDER BY phase, http_status;

-- 접수된 주문이 관찰 종료까지 처리됐는지. OPEN·PARTIALLY_FILLED(상대가 없어 대기)와 FILLED는 처리된 것, RECEIVED는 따로 센다
SELECT '3. 접수 주문 처리 상태(관찰 종료 시점)' AS section, phase,
       SUM(db_status IN ('FILLED', 'OPEN', 'PARTIALLY_FILLED')) AS processed,
       SUM(db_status = 'RECEIVED') AS still_received,
       SUM(http_status = 202 AND order_id IS NULL) AS accepted_but_missing_in_db,
       SUM(http_status <> 202 AND order_id IS NOT NULL) AS error_response_but_committed
FROM measure.q6_orders GROUP BY phase ORDER BY phase;

SELECT '4. 기존 종목 RECEIVED 전체(이 run 밖 포함)' AS section, COUNT(*) AS received
FROM stock_single_was.orders WHERE status = 'RECEIVED' AND symbol_code NOT LIKE CONCAT(@prefix, '%');

-- 3번(종목 추가)·4번(신규 종목 체결)의 DB 확인
SELECT '5. 신규 종목' AS section, s.code, s.status,
       (SELECT COUNT(*) FROM stock_single_was.trades t JOIN stock_single_was.orders b ON b.id = t.buy_order_id
         WHERE b.symbol_code = s.code) AS trades
FROM stock_single_was.symbols s WHERE s.code LIKE CONCAT(@prefix, '%') ORDER BY s.code;
