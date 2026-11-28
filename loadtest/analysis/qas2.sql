-- QAS 2 판정(docs/measurement/plan-stage10.md 3절). classify.sql 뒤, checks.sql 앞에 붙여 쓴다.
--   { echo "SET @run = 'r1';"; cat classify.sql qas2.sql checks.sql; } | mysql ...
-- 미전송(F)은 기록에 없으므로 k6 요약의 dropped_iterations와 계획 수로 따로 집계하고 원인을 판정한다(plan 1.7).

SELECT '3. 판정: 300ms 안 체결 알림 비율' AS section,
       SUM(cls = 'A' AND latency_ms <= 300) AS within_300ms, SUM(judged) AS judged,
       ROUND(100 * SUM(cls = 'A' AND latency_ms <= 300) / SUM(judged), 4) AS pct,
       IF(SUM(cls = 'A' AND latency_ms <= 300) / SUM(judged) >= 0.99, 'P99<=300ms 충족', 'P99<=300ms 미충족') AS verdict_before_f_and_validity
FROM measure.orders_cls WHERE judged;

-- 백분위는 최근접 순위(nearest-rank): 정렬한 n개 중 CEIL(n × p)번째 값
SELECT '4. 성공(A) 지연 백분위 ms' AS section, COUNT(*) AS n,
       MAX(CASE WHEN rn = CEIL(n * 0.50) THEN latency_ms END) AS p50,
       MAX(CASE WHEN rn = CEIL(n * 0.95) THEN latency_ms END) AS p95,
       MAX(CASE WHEN rn = CEIL(n * 0.99) THEN latency_ms END) AS p99,
       MAX(latency_ms) AS max_ms, MIN(latency_ms) AS min_ms
FROM (SELECT latency_ms, ROW_NUMBER() OVER (ORDER BY latency_ms) rn, COUNT(*) OVER () n
      FROM measure.orders_cls WHERE judged AND cls = 'A') x;

SELECT '5. HTTP 실패지만 커밋됨(D) 세부' AS section, http_status, db_status, (fill_recv_ms IS NOT NULL) AS alert_received, COUNT(*) AS orders
FROM measure.orders_cls WHERE judged AND cls = 'D' GROUP BY http_status, db_status, alert_received;
