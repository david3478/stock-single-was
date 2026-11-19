-- 초기 스키마. MySQL 8.4 / InnoDB.
-- 가격·금액은 원 단위 정수(BIGINT), 수량은 INT. 애플리케이션은 Math.*Exact로 오버플로를 검사한다.
-- DATETIME(6)은 UTC로 해석한다(JVM -Duser.timezone=UTC, 연결 connectionTimeZone=UTC).

CREATE TABLE symbols (
  code   VARCHAR(20)  NOT NULL PRIMARY KEY,
  name   VARCHAR(100) NOT NULL,
  status VARCHAR(20)  NOT NULL,
  CONSTRAINT ck_symbols_status CHECK (status IN ('PREPARING', 'ACTIVE'))
) ENGINE=InnoDB;

-- cash_balance: 사용 가능 현금. reserved_cash: 접수된 매수 주문이 묶어 둔 현금. 총현금 = 둘의 합.
CREATE TABLE accounts (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cash_balance  BIGINT NOT NULL,
  reserved_cash BIGINT NOT NULL,
  CONSTRAINT ck_accounts_cash CHECK (cash_balance >= 0 AND reserved_cash >= 0)
) ENGINE=InnoDB;

-- quantity: 총보유량. reserved_quantity: 접수된 매도 주문이 묶어 둔 수량.
-- 매도 가능 수량 = quantity - reserved_quantity (저장하지 않는다).
CREATE TABLE holdings (
  account_id        BIGINT      NOT NULL,
  symbol_code       VARCHAR(20) NOT NULL,
  quantity          INT         NOT NULL,
  reserved_quantity INT         NOT NULL,
  PRIMARY KEY (account_id, symbol_code),
  KEY ix_holdings_symbol (symbol_code),
  CONSTRAINT fk_holdings_account FOREIGN KEY (account_id) REFERENCES accounts (id),
  CONSTRAINT fk_holdings_symbol  FOREIGN KEY (symbol_code) REFERENCES symbols (code),
  CONSTRAINT ck_holdings_quantity CHECK
    (quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= quantity)
) ENGINE=InnoDB;

-- status별 seq·remaining 조건은 복구 분기의 전제다(ADR 0001, 0002).
-- seq: 종목 워커가 처음 처리할 때 부여하는 종목별 순번. RECEIVED는 NULL.
CREATE TABLE orders (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id      BIGINT       NOT NULL,
  symbol_code     VARCHAR(20)  NOT NULL,
  client_order_id VARCHAR(100) NOT NULL,
  side            VARCHAR(4)   NOT NULL,
  limit_price     BIGINT       NOT NULL,
  qty             INT          NOT NULL,
  remaining       INT          NOT NULL,
  status          VARCHAR(24)  NOT NULL,
  seq             BIGINT       NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_orders_request (account_id, client_order_id),
  UNIQUE KEY uq_orders_sequence (symbol_code, seq),
  KEY ix_orders_recovery (symbol_code, status, id),
  KEY ix_orders_account (account_id, id),
  CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES accounts (id),
  CONSTRAINT fk_orders_symbol  FOREIGN KEY (symbol_code) REFERENCES symbols (code),
  CONSTRAINT ck_orders_side CHECK (side IN ('BUY', 'SELL')),
  CONSTRAINT ck_orders_values CHECK (limit_price > 0 AND qty > 0 AND remaining BETWEEN 0 AND qty),
  CONSTRAINT ck_orders_state CHECK (
       (status = 'RECEIVED'         AND seq IS NULL                 AND remaining = qty)
    OR (status = 'OPEN'             AND seq IS NOT NULL AND seq > 0 AND remaining = qty)
    OR (status = 'PARTIALLY_FILLED' AND seq IS NOT NULL AND seq > 0 AND remaining > 0 AND remaining < qty)
    OR (status = 'FILLED'           AND seq IS NOT NULL AND seq > 0 AND remaining = 0)
  )
) ENGINE=InnoDB;

CREATE TABLE trades (
  id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  buy_order_id  BIGINT      NOT NULL,
  sell_order_id BIGINT      NOT NULL,
  price         BIGINT      NOT NULL,
  quantity      INT         NOT NULL,
  executed_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_trades_buy (buy_order_id),
  KEY ix_trades_sell (sell_order_id),
  CONSTRAINT fk_trades_buy  FOREIGN KEY (buy_order_id) REFERENCES orders (id),
  CONSTRAINT fk_trades_sell FOREIGN KEY (sell_order_id) REFERENCES orders (id),
  CONSTRAINT ck_trades_values CHECK (price > 0 AND quantity > 0 AND buy_order_id <> sell_order_id)
) ENGINE=InnoDB;

-- 알림 한 행 = 한 계좌가 받을 처리 결과 하나.
-- 체결은 체결 1건당 양쪽 계좌에 1행씩(trade_id 있음), 대기 등록은 trade_id 없음.
-- 같은 계좌의 ID 발급 순서와 커밋 순서는 INSERT 전 계좌 잠금으로 맞춘다(ADR 0003).
CREATE TABLE notifications (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT      NOT NULL,
  order_id   BIGINT      NOT NULL,
  trade_id   BIGINT      NULL,
  type       VARCHAR(30) NOT NULL,
  payload    JSON        NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_notifications_account (account_id, id),
  KEY ix_notifications_order (order_id),
  KEY ix_notifications_trade (trade_id),
  CONSTRAINT fk_notifications_account FOREIGN KEY (account_id) REFERENCES accounts (id),
  CONSTRAINT fk_notifications_order   FOREIGN KEY (order_id) REFERENCES orders (id),
  CONSTRAINT fk_notifications_trade   FOREIGN KEY (trade_id) REFERENCES trades (id),
  CONSTRAINT ck_notifications_type CHECK
    (type IN ('ORDER_OPEN', 'ORDER_PARTIALLY_FILLED', 'ORDER_FILLED')),
  CONSTRAINT ck_notifications_trade CHECK ((type = 'ORDER_OPEN') = (trade_id IS NULL))
) ENGINE=InnoDB;

-- DB 행 제약으로 강제할 수 없어 애플리케이션과 테스트가 지키는 조건:
-- * 체결의 두 주문은 같은 종목이고 BUY/SELL 역할과 지정가 조건을 만족한다.
-- * notifications.account_id는 notifications.order_id의 소유 계좌다.
-- * T1(예약+주문 저장), T2(주문 갱신+체결+정산+알림)는 각각 원자적이다.
-- * 잠금 순서는 계좌 행 -> 보유 행. orders INSERT의 FK 검사가 계좌 행에 S 잠금을 걸기 때문에
--   매도 T1도 보유 행을 갱신하기 전에 계좌 행 잠금을 먼저 얻어야 T2와 교차 대기가 생기지 않는다.
-- * 현재 범위에서는 주문 이력을 삭제하지 않는다. 삭제하면 종목별 MAX(seq) 복원이 깨진다.
