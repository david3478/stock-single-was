-- QAS 5 판정(docs/measurement/plan-stage10.md 5절). 실행 전에 SET @run = '<run>'; 을 붙인다.
--   { echo "SET @run = 'r1';"; cat loadtest/analysis/qas5.sql; } | mysql ...
-- 수집기(--cycles)는 회차마다 모든 연결을 끊고(gen이 1 늘어남) Last-Event-ID = L로 다시 붙는다. 서버는 밀린 알림 뒤 ":replayed R"을 보낸다.
-- 같은 계좌의 알림 번호 순서는 커밋 순서와 같으므로(ADR 0003) (L, R]은 그 계좌에 단절부터 재구독까지 커밋된 알림 전부다. 시계에 의존하지 않는다.
-- 작업 테이블은 measure.q5_*(일반 테이블, 실행할 때마다 다시 만든다).

-- 회차 구간(plan 5.2): L = 끊기 직전 그 연결이 실제로 받은 마지막 알림 번호(gen-1의 disconnect 표시값), R = 재연결(gen)의 replayed 값.
-- 재연결 때 보낸 Last-Event-ID(gen의 첫 request 값)는 L과 같아야 하며, 다르면 1절에 따로 센다.
-- 보낸 값을 L로 쓰면 클라이언트가 번호를 잘못 보내 빠진 알림이 분모에서 함께 빠지므로 쓰지 않는다.
DROP TABLE IF EXISTS measure.q5_windows;
CREATE TABLE measure.q5_windows AS
SELECT req.account_id, req.gen, CAST(dis.value AS UNSIGNED) AS l_id, CAST(req.value AS UNSIGNED) AS sent_last_event_id,
       CAST(rep.value AS UNSIGNED) AS r_id, dis.ms AS disconnect_ms, req.ms AS request_ms, rep.ms AS replayed_ms
FROM (SELECT account_id, gen, value, ms,
             ROW_NUMBER() OVER (PARTITION BY account_id, gen ORDER BY ms) AS rn
      FROM measure.sse_markers WHERE run = @run AND kind = 'request' AND gen >= 2) req
LEFT JOIN measure.sse_markers dis
  ON dis.run = @run AND dis.account_id = req.account_id AND dis.gen = req.gen - 1 AND dis.kind = 'disconnect'
LEFT JOIN (SELECT account_id, gen, MIN(value) AS value, MIN(ms) AS ms
           FROM measure.sse_markers WHERE run = @run AND kind = 'replayed' AND gen >= 2 GROUP BY account_id, gen) rep
  ON rep.account_id = req.account_id AND rep.gen = req.gen
WHERE req.rn = 1;

-- 수집기가 받은 알림(계좌, 알림 번호)과 받은 횟수
DROP TABLE IF EXISTS measure.q5_received;
CREATE TABLE measure.q5_received (PRIMARY KEY (account_id, event_id)) AS
SELECT account_id, event_id, COUNT(*) AS receptions FROM measure.sse_events WHERE run = @run GROUP BY account_id, event_id;

-- 단절 구간의 수신 대상 체결 알림(분모). 한 회차에 놓친 알림 뒤로 그 계좌가 아무것도 받지 못하면 다음 회차의 L이 그대로라
-- 구간이 겹친다(겹친 부분은 놓친 알림뿐). 같은 알림을 두 번 세지 않도록 처음 걸린 회차 하나에만 넣는다
DROP TABLE IF EXISTS measure.q5_targets;
CREATE TABLE measure.q5_targets AS
SELECT MIN(w.gen) AS gen, n.account_id, n.id AS event_id, COALESCE(MAX(r.receptions), 0) AS receptions
FROM measure.q5_windows w
JOIN stock_single_was.notifications n
  ON n.account_id = w.account_id AND n.id > w.l_id AND n.id <= w.r_id AND n.trade_id IS NOT NULL
LEFT JOIN measure.q5_received r ON r.account_id = n.account_id AND r.event_id = n.id
GROUP BY n.account_id, n.id;

SELECT '1. 회차 구간' AS section,
       (SELECT COUNT(DISTINCT gen) FROM measure.q5_windows) AS cycles,
       (SELECT COUNT(*) FROM measure.q5_windows) AS windows,
       (SELECT COUNT(*) FROM measure.q5_windows WHERE l_id IS NULL) AS windows_without_disconnect,
       (SELECT COUNT(*) FROM measure.q5_windows WHERE r_id IS NULL) AS windows_without_replayed,
       (SELECT COUNT(*) FROM measure.q5_windows WHERE r_id < l_id) AS windows_r_below_l,
       (SELECT COUNT(*) FROM measure.q5_windows WHERE NOT (sent_last_event_id <=> l_id)) AS sent_id_differs_from_l;

SELECT '2. 판정: 단절 구간 체결 알림' AS section,
       COUNT(*) AS denominator,
       SUM(receptions = 0) AS missed,
       SUM(receptions > 1) AS duplicated,
       ROUND(100 * SUM(receptions = 0) / NULLIF(COUNT(*), 0), 6) AS missed_pct,
       CASE
         WHEN COUNT(*) < 100000 THEN '규모 미달(분모 10만 미만): 합격으로 표시하지 않음'
         WHEN SUM(receptions = 0) / COUNT(*) <= 0.00001 THEN '충족(누락률 <= 0.001%)'
         ELSE '미충족'
       END AS verdict
FROM measure.q5_targets;

SELECT '3. 회차별' AS section, t.gen - 1 AS cycle, COUNT(*) AS denominator, SUM(t.receptions = 0) AS missed,
       SUM(t.receptions > 1) AS duplicated, COUNT(DISTINCT t.account_id) AS accounts_with_events,
       (SELECT ROUND(AVG(w.request_ms - w.disconnect_ms)) FROM measure.q5_windows w WHERE w.gen = t.gen) AS avg_down_ms,
       (SELECT MAX(w.replayed_ms - w.request_ms) FROM measure.q5_windows w WHERE w.gen = t.gen) AS max_reconnect_to_replayed_ms
FROM measure.q5_targets t GROUP BY t.gen ORDER BY t.gen;

-- 재연결 전후 전체: 수집기가 연결한 계좌의, 첫 연결 이후 모든 알림(구간 밖 포함). 기준 대상은 아니지만 숨기지 않는다
SELECT '4. 재연결 전후 전체(첫 연결 이후 모든 알림)' AS section,
       COUNT(*) AS notifications, SUM(r.receptions IS NULL) AS missed, SUM(r.receptions > 1) AS duplicated
FROM stock_single_was.notifications n
JOIN (SELECT account_id, MIN(CAST(value AS UNSIGNED)) AS first_r FROM measure.sse_markers
      WHERE run = @run AND kind = 'replayed' AND gen = 1 GROUP BY account_id) s ON s.account_id = n.account_id
LEFT JOIN measure.q5_received r ON r.account_id = n.account_id AND r.event_id = n.id
WHERE n.id > s.first_r;

-- 알림 생성: 체결 1건마다 매수 계좌와 매도 계좌에 체결 알림이 1행씩 있어야 한다(ADR 0009)
SELECT '5. 알림 생성(trades 기준)' AS section,
       COUNT(*) AS expected_pairs,
       SUM(cnt = 0) AS not_generated,
       SUM(cnt > 1) AS generated_more_than_once
FROM (SELECT t.id, b.account_id, (SELECT COUNT(*) FROM stock_single_was.notifications n
                                   WHERE n.trade_id = t.id AND n.account_id = b.account_id) AS cnt
      FROM stock_single_was.trades t JOIN stock_single_was.orders b ON b.id = t.buy_order_id
      UNION ALL
      SELECT t.id, s.account_id, (SELECT COUNT(*) FROM stock_single_was.notifications n
                                   WHERE n.trade_id = t.id AND n.account_id = s.account_id)
      FROM stock_single_was.trades t JOIN stock_single_was.orders s ON s.id = t.sell_order_id) p;

-- 참고(판정에 쓰지 않음): DB 시각 created_at이 생성기가 잰 단절~재연결 사이인 체결 알림 수. 두 시계 차이와 INSERT·커밋 시차가 섞인다
SELECT '6. 참고: created_at 기준 구간' AS section, w.gen - 1 AS cycle, COUNT(*) AS by_created_at
FROM measure.q5_windows w
JOIN stock_single_was.notifications n
  ON n.account_id = w.account_id AND n.trade_id IS NOT NULL
 AND n.created_at >= FROM_UNIXTIME(w.disconnect_ms / 1000) AND n.created_at < FROM_UNIXTIME(w.request_ms / 1000)
GROUP BY w.gen ORDER BY w.gen;

SELECT '7. 수집기 오류 표시' AS section, LEFT(value, 40) AS reason, COUNT(*) AS n
FROM measure.sse_markers WHERE run = @run AND kind = 'error' GROUP BY reason;
