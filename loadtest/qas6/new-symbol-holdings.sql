-- QAS 6 시험 데이터(운영자 작업이 아님): 신규 종목이 모두 ACTIVE가 된 뒤 매도 계좌 501~1000에 신규 종목 보유 1,000주씩을 넣는다.
-- holdings가 symbols를 외래 키로 참조하므로 종목 행이 생긴 뒤에만 넣을 수 있다(IMPLEMENTATION_GUIDE §10 QAS 6 4번).
-- 앱 실행 중에 넣어도 된다: 보유 수량은 DB에만 있고 매도 접수(T1)의 조건부 UPDATE가 DB에서 읽는다.
--   { echo "SET @prefix = 'NEW';"; cat new-symbol-holdings.sql; } | mysql ...
-- 시작·끝 시각은 실행하는 쪽에서 기록한다(운영자 작업 시간과 따로).
INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity)
SELECT a.id, s.code, 1000, 0
FROM accounts a JOIN symbols s ON s.code LIKE CONCAT(@prefix, '%') AND s.status = 'ACTIVE'
WHERE a.id BETWEEN 501 AND 1000
ON DUPLICATE KEY UPDATE quantity = quantity;

SELECT symbol_code, COUNT(*) AS seller_accounts, SUM(quantity) AS shares
FROM holdings WHERE symbol_code LIKE CONCAT(@prefix, '%') GROUP BY symbol_code ORDER BY symbol_code;
