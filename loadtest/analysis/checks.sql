-- QAS 2·3 공통 점검(docs/measurement/plan-stage10.md 1.5~1.6). classify.sql과 판정 SQL 뒤에 붙여 쓴다.

SELECT '6. 수 구분(plan 1.5)' AS section,
       (SELECT COUNT(*) FROM measure.orders_cls WHERE db_fills > 1) AS orders_with_multiple_db_fills,
       (SELECT COUNT(*) FROM measure.orders_cls WHERE fill_receptions > 1) AS orders_with_multiple_fill_receptions,
       (SELECT COUNT(*) FROM (SELECT account_id, event_id FROM measure.sse_events WHERE run = @run
                               GROUP BY account_id, event_id HAVING COUNT(*) > 1) d) AS event_ids_received_more_than_once;

-- C 원인 판정(plan 1.6): 그 주문을 처리하던 순간 남은 사전 매도 = 앞선 순번의 사전 매도 수 - 앞선 순번 매수가 가져간 체결 수
SELECT '7. 즉시 체결 안 됨(C) 원인' AS section, cause, COUNT(*) AS orders FROM (
  SELECT CASE
      WHEN q.order_seq IS NULL THEN '미처리(RECEIVED, 처리순번 없음)'
      WHEN (SELECT COUNT(*) FROM stock_single_was.orders s
             WHERE s.symbol_code = q.symbol_code AND s.side = 'SELL' AND s.client_order_id LIKE 'pre-%' AND s.seq < q.order_seq)
         - (SELECT COUNT(*) FROM stock_single_was.trades t JOIN stock_single_was.orders b ON b.id = t.buy_order_id
             WHERE b.symbol_code = q.symbol_code AND b.seq < q.order_seq) <= 0 THEN '시험 준비 오류: 남은 사전 매도 없음'
      ELSE '시스템 동작 문제: 남은 매도가 있는데 체결 안 됨'
    END AS cause
  FROM measure.orders_cls q WHERE q.cls = 'C') c
GROUP BY cause;

SELECT '8. 시험 구성 위반(있으면 준비 오류)' AS section,
       (SELECT COUNT(*) FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND (limit_price <> 1000 OR qty <> 1)) AS pre_sells_not_1000_1,
       (SELECT COUNT(*) FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND account_id BETWEEN 1 AND 500) AS pre_sells_from_buyer_accounts,
       (SELECT COUNT(DISTINCT account_id) FROM measure.orders_cls) AS buyer_accounts_used,
       (SELECT COUNT(*) FROM measure.orders_cls WHERE account_id NOT BETWEEN 1 AND 500) AS orders_from_non_buyer_accounts;

SELECT '9. 측정 끝 남은 사전 매도' AS section, symbol_code, COUNT(*) AS open_pre_sells
FROM stock_single_was.orders WHERE client_order_id LIKE 'pre-%' AND status = 'OPEN' GROUP BY symbol_code ORDER BY symbol_code;

SELECT '10. 수집기 오류 표시' AS section, kind, LEFT(value, 40) AS reason, COUNT(*) AS n
FROM measure.sse_markers WHERE run = @run AND kind = 'error' GROUP BY kind, reason;
