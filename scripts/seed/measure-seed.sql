-- 측정용 데이터 초기화 + seed(docs/measurement/plan-stage10.md 2.1). 기존 주문·체결·알림·계좌·종목을 모두 지운다.
-- Flyway 마이그레이션이 아니며 배포 때 자동 실행하지 않는다. 대상 DB를 확인하고 직접 실행한다.
-- 서버가 떠 있는 상태에서 실행하면 메모리 호가창과 DB가 어긋난다. 서버를 멈춘 뒤 실행하고, 실행 후 서버를 다시 띄운다.
--
-- 결과: 종목 10개(SYM01~SYM10, ACTIVE), 계좌 2,000개(id 1~2000), 계좌마다 현금 1억 원, 종목마다 1,000주 보유.
-- 쓰임(시험별 사용 계좌는 결과에 따로 기록한다):
--   1~500     매수 계좌(측정 대상 주문)
--   501~1000  매도 계좌(사전 반대 주문)
--   1001~2000 계좌 조회(QAS 2)
-- 물량 확인(설계값 기준): 매수 계좌는 QAS 5에서 계좌당 약 440건 × 1,100원 ≈ 48만 원으로 현금 1억 원 안이다.
--   매도 계좌는 QAS 5에서 계좌·종목당 66주, QAS 3 SYM01에서 계좌당 50주로 보유 1,000주 안이다.

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE notifications;
TRUNCATE TABLE trades;
TRUNCATE TABLE orders;
TRUNCATE TABLE holdings;
TRUNCATE TABLE accounts;
TRUNCATE TABLE symbols;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO symbols (code, name, status) VALUES
  ('SYM01', '측정종목01', 'ACTIVE'),
  ('SYM02', '측정종목02', 'ACTIVE'),
  ('SYM03', '측정종목03', 'ACTIVE'),
  ('SYM04', '측정종목04', 'ACTIVE'),
  ('SYM05', '측정종목05', 'ACTIVE'),
  ('SYM06', '측정종목06', 'ACTIVE'),
  ('SYM07', '측정종목07', 'ACTIVE'),
  ('SYM08', '측정종목08', 'ACTIVE'),
  ('SYM09', '측정종목09', 'ACTIVE'),
  ('SYM10', '측정종목10', 'ACTIVE');

-- 재귀 CTE 기본 깊이(1,000)로는 계좌 2,000개를 만들 수 없다
SET SESSION cte_max_recursion_depth = 10000;

INSERT INTO accounts (id, cash_balance, reserved_cash)
WITH RECURSIVE n (id) AS (
  SELECT 1 UNION ALL SELECT id + 1 FROM n WHERE id < 2000
)
SELECT id, 100000000, 0 FROM n;

INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity)
SELECT a.id, s.code, 1000, 0
FROM accounts a CROSS JOIN symbols s;
