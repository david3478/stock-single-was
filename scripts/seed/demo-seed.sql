-- 데모용 데이터 초기화 + seed. 기존 주문·체결·알림·계좌를 모두 지운다.
-- Flyway 마이그레이션이 아니며 배포 때 자동 실행하지 않는다. 대상 DB를 확인하고 직접 실행한다.
--
-- 로컬:
--   docker compose exec -T mysql mysql --default-character-set=utf8mb4 -ussw -pssw stock_single_was < scripts/seed/demo-seed.sql
--
-- 결과: 종목 10개(SYM01~SYM10, ACTIVE), 계좌 1,000개(id 1~1000),
--       계좌마다 현금 1억 원, 종목마다 1,000주 보유.
-- 서버가 떠 있는 상태에서 실행하면 메모리 호가창과 DB가 어긋난다. 서버를 멈춘 뒤 실행한다.

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE notifications;
TRUNCATE TABLE trades;
TRUNCATE TABLE orders;
TRUNCATE TABLE holdings;
TRUNCATE TABLE accounts;
TRUNCATE TABLE symbols;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO symbols (code, name, status) VALUES
  ('SYM01', '데모종목01', 'ACTIVE'),
  ('SYM02', '데모종목02', 'ACTIVE'),
  ('SYM03', '데모종목03', 'ACTIVE'),
  ('SYM04', '데모종목04', 'ACTIVE'),
  ('SYM05', '데모종목05', 'ACTIVE'),
  ('SYM06', '데모종목06', 'ACTIVE'),
  ('SYM07', '데모종목07', 'ACTIVE'),
  ('SYM08', '데모종목08', 'ACTIVE'),
  ('SYM09', '데모종목09', 'ACTIVE'),
  ('SYM10', '데모종목10', 'ACTIVE');

INSERT INTO accounts (id, cash_balance, reserved_cash)
WITH RECURSIVE n (id) AS (
  SELECT 1 UNION ALL SELECT id + 1 FROM n WHERE id < 1000
)
SELECT id, 100000000, 0 FROM n;

INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity)
SELECT a.id, s.code, 1000, 0
FROM accounts a CROSS JOIN symbols s;
