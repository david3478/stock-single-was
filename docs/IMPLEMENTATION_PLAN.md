# 구현 계획

`IMPLEMENTATION_GUIDE.md` §11을 단계별로 나눈 체크리스트다. 각 항목은 실패하는 테스트를 먼저 쓴다.

진행 순서: 1 → 2 → 3 → 4 → 5 → 첫 배포(8단계의 최소 버전: Dockerfile, EC2 수동 배포) → 6 → 7 → 8 나머지 → 9 → 10.

PR 구성: 1~4단계는 PR 하나(`feat/order-matching`), 5단계와 첫 배포는 PR 하나(`feat/query-first-deploy`). 그 뒤는 단계별로 정한다.
커밋은 기능(`feat:`)과 테스트(`test:`)를 나눈다. 모든 커밋에서 빌드와 테스트가 통과해야 하므로 기능 커밋 다음에 그 테스트 커밋을 둔다.
동작하는 접수·체결을 먼저 보여주기 위한 순서다. 7단계(복구) 전에는 재시작하면 메모리 호가창이 비므로, 첫 배포 환경은 재배포할 때마다 데모 데이터를 초기화한다.

## 0. 초기 설정
- [x] Git 저장소, CLAUDE.md, ADR 0001~0005
- [x] 이슈·커밋·PR 템플릿
- [x] 의존성(webmvc, jdbc, validation, actuator, flyway, mysql, prometheus, testcontainers)
- [x] 로컬 MySQL Compose(프로토타입과 DB 이름·포트·볼륨 분리), local 프로필
- [x] Testcontainers MySQL 통합 테스트 기반 클래스와 연결 확인 테스트
- [x] PR CI(GitHub Actions, 테스트 실행)

## 1. 스키마와 seed
- [x] `V1__init.sql`: symbols(status), accounts, holdings, orders(RECEIVED, seq), trades, notifications(trade_id NULL 허용, type), CHECK 제약, 인덱스
- [x] 시험 계좌·종목 seed(명시적 실행만, 배포 시 자동 실행 금지)

## 2. 순수 도메인 (DB 없음)
- [x] `OrderBook`: 가격-시간(seq) 우선, 부분 체결, 잔량 갱신
- [x] `OrderBook.plan`: 호가창을 바꾸지 않고 `MatchPlan` 생성, 커밋 후 `apply` (별도 Matcher 클래스 대신 EA의 대기주문목록.체결계획생성/계획반영에 맞춤)
- [x] `SettlementCalculator`: 예약, 차액 환불, 이중 차감 없음, 오버플로 검사

## 3. 주문 접수 T1
- [x] `POST /api/accounts/{accountId}/orders`: 조건부 UPDATE 예약 + `RECEIVED` 저장 + 커밋 후 대기열 전달
- [x] 잔고·수량 부족 거절, 같은 계좌 동시 매도 1건만 성공
- [x] clientOrderId 재요청 시 기존 주문 반환
- [ ] 커밋 결과 불명 처리(ADR 0005 ②)

## 4. 종목 워커와 T2
- [ ] `SymbolEngine`(대기열 + 워커 + 호가창), 계획 → 커밋 → 메모리 반영
- [ ] 즉시 체결, 상대 없음 → 대기 등록, 중복 전달 무시
- [ ] T2 실패 시 호가창 불변, 데드락 재시도
- [ ] `NotificationWriter`: 계좌 ID 오름차순 잠금 후 INSERT(ADR 0003)
- [ ] 대기열 넘침 처리(ADR 0005 ①)

## 5. 조회
- [ ] 계좌·보유·주문 조회 API

## 6. SSE 알림
- [ ] `SseHub`: 계좌별 연결, Last-Event-ID 재전송, 연결별 송신 직렬화, 별도 송신 실행 자원
- [ ] ADR 0003 검증 테스트 3종(결정적 재현, 스트레스, SSE 끝단)

## 7. 복구와 감시
- [ ] `RecoveryManager`: 잔여 주문 (price, seq) 복원, RECEIVED ID 순 복원, nextSeq 복원, 완료 전 readiness DOWN
- [ ] 종목 단위 복구(ADR 0005 ③)
- [ ] 상태 불일치 감시와 메트릭(ADR 0005)
- [ ] 장애 테스트: T1 후 전달 전 종료, T2 커밋 전후 종료

## 8. 실행 경로와 배포
- [ ] HTTP → 워커 → DB → SSE 전 구간 테스트 (첫 실행 가능 버전 완료 기준)
- [ ] Dockerfile, ECR + SSM 배포, readiness·smoke 확인

## 9. 실행 중 종목 추가 (QAS 6)
- [ ] PREPARING → 엔진 준비 → ACTIVE, 실패와 재시도

## 10. 측정
- [ ] 부하 도구(SSE 결과 수신까지 측정), 단일 WAS 기준선, 병목 ADR
