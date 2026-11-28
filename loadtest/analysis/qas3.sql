-- QAS 3 판정(docs/measurement/plan-stage10.md 4절). classify.sql 뒤, checks.sql 앞에 붙여 쓴다.
--   { echo "SET @run = 'r1', @warmup_marker = 'q3-%-warmup-%';"; cat classify.sql qas3.sql checks.sql; } | mysql ...
-- 판정: 계획한 폭주 주문(기본 30,000건) 중 제출부터 2초 안에 체결 알림을 받지 못한 비율 < 0.1%(실패 29건 이하).
-- 미전송(계획 - 보낸 수)은 원인이 앱 응답 지연이면 실패로 세고, 생성기면 회차 무효, 판단할 수 없으면 판정 보류(plan 1.7).
-- 원인 판정 근거(생성기 CPU, k6 VU 사용량, 응답 시간 추이, 앱 요청 처리 시간)는 이 SQL 밖에서 확인한다.

SELECT '3. 판정: 2초 안 체결 알림' AS section,
       r.planned,
       COUNT(*) AS sent,
       r.planned - COUNT(*) AS unsent,
       SUM(q.cls = 'A' AND q.latency_ms <= 2000) AS within_2s,
       COUNT(*) - SUM(q.cls = 'A' AND q.latency_ms <= 2000) AS failed_among_sent,
       r.planned - SUM(q.cls = 'A' AND q.latency_ms <= 2000) AS failed_if_unsent_counted,
       ROUND(100 * (r.planned - SUM(q.cls = 'A' AND q.latency_ms <= 2000)) / r.planned, 4) AS failed_pct_if_unsent_counted,
       CASE
         WHEN r.planned - COUNT(*) > 0 THEN '미전송 있음: 원인 판정 필요(앱 원인이면 failed_if_unsent_counted로 판정, 생성기면 무효, 모르면 보류)'
         WHEN COUNT(*) - SUM(q.cls = 'A' AND q.latency_ms <= 2000) <= FLOOR(r.planned * 0.001 - 1e-9) THEN '충족(실패 < 0.1%)'
         ELSE '미충족'
       END AS verdict_before_validity
FROM measure.orders_cls q JOIN measure.k6_runs r ON r.run = @run
WHERE q.judged
GROUP BY r.planned;

SELECT '4. 시나리오별' AS section,
       IF(client_order_id LIKE 'q3-%-hot-%', 'hot(SYM01)', 'others(9종목)') AS scenario,
       COUNT(*) AS sent,
       SUM(cls = 'A' AND latency_ms <= 2000) AS within_2s,
       SUM(cls = 'A' AND latency_ms > 2000) AS filled_late,
       SUM(cls <> 'A') AS not_a,
       MAX(CASE WHEN cls = 'A' THEN latency_ms END) AS max_success_latency_ms
FROM measure.orders_cls WHERE judged GROUP BY scenario ORDER BY scenario;

-- 백분위는 최근접 순위(nearest-rank)
SELECT '5. 성공(A) 지연 백분위 ms' AS section,
       IF(client_order_id LIKE 'q3-%-hot-%', 'hot(SYM01)', 'others(9종목)') AS scenario, COUNT(*) AS n,
       MAX(CASE WHEN rn = CEIL(n * 0.50) THEN latency_ms END) AS p50,
       MAX(CASE WHEN rn = CEIL(n * 0.99) THEN latency_ms END) AS p99,
       MAX(CASE WHEN rn = CEIL(n * 0.999) THEN latency_ms END) AS p999
FROM (SELECT client_order_id, latency_ms,
             ROW_NUMBER() OVER (PARTITION BY client_order_id LIKE 'q3-%-hot-%' ORDER BY latency_ms) rn,
             COUNT(*) OVER (PARTITION BY client_order_id LIKE 'q3-%-hot-%') n
      FROM measure.orders_cls WHERE judged AND cls = 'A') x
GROUP BY scenario ORDER BY scenario;
