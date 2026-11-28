-- 측정 기록 대조용 스키마(docs/measurement/plan-stage10.md 6.3). 운영 테이블과 섞이지 않게 별도 스키마 measure에 둔다.
-- 측정의 부하와 관찰이 모두 끝난 뒤에만 적재·대조한다. 여러 회차는 run으로 구분한다.
CREATE DATABASE IF NOT EXISTS measure DEFAULT CHARACTER SET utf8mb4;

-- k6 실행 정보: 시작 시각과 예열 길이(판정 대상 = 보낸 시각 >= start_ms + warmup_s * 1000)
CREATE TABLE IF NOT EXISTS measure.k6_runs (
  run       VARCHAR(40) NOT NULL PRIMARY KEY,
  start_ms  BIGINT      NOT NULL,
  warmup_s  INT         NOT NULL
);

-- k6가 보낸 측정 대상 주문(주문마다 한 줄). http_status 0 = 연결 실패·시간 초과
CREATE TABLE IF NOT EXISTS measure.k6_orders (
  run             VARCHAR(40)  NOT NULL,
  seq             BIGINT       NOT NULL,
  account_id      BIGINT       NOT NULL,
  client_order_id VARCHAR(100) NOT NULL,
  symbol_code     VARCHAR(20)  NOT NULL,
  sent_ms         BIGINT       NOT NULL,
  http_status     INT          NOT NULL,
  resp_order_id   BIGINT       NULL,
  duration_ms     DOUBLE       NOT NULL,
  PRIMARY KEY (run, seq),
  KEY ix_k6_orders_key (account_id, client_order_id)
);

-- SSE 수집기가 받은 알림
CREATE TABLE IF NOT EXISTS measure.sse_events (
  run        VARCHAR(40) NOT NULL,
  account_id BIGINT      NOT NULL,
  gen        INT         NOT NULL,
  event_id   BIGINT      NOT NULL,
  type       VARCHAR(30) NOT NULL,
  order_id   BIGINT      NULL,
  trade_id   BIGINT      NULL,
  recv_ms    BIGINT      NOT NULL,
  KEY ix_sse_events_order (run, account_id, order_id),
  KEY ix_sse_events_id (run, account_id, event_id)
);

-- SSE 수집기 표시(request, connected, replayed, disconnect, closed, error)
CREATE TABLE IF NOT EXISTS measure.sse_markers (
  run        VARCHAR(40)  NOT NULL,
  account_id BIGINT       NOT NULL,
  gen        INT          NOT NULL,
  kind       VARCHAR(20)  NOT NULL,
  value      VARCHAR(200) NULL,
  ms         BIGINT       NOT NULL,
  KEY ix_sse_markers (run, account_id, gen, kind)
);
