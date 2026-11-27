# EA 설계 다이어그램과 구현 대조

`images/`의 그림은 구현을 시작하기 전에 Enterprise Architect로 그린 **초기 설계**다. 구현하면서 바꾼 부분이 있고, 바꾼 이유는 ADR에 있다.
아래 표는 그림마다 실제 코드와 다른 점을 적는다(2026-11-27, 9단계 기준). 그림을 고쳐 다시 내보내면 해당 줄을 지운다.

## 구현과 같은 그림

| 그림 | 내용 | 실제 코드 | 비고 |
|---|---|---|---|
| [web-files](images/web-files.png) | 화면 파일 구조 | `frontend/public/` | 같다 |
| [symbol-add](images/symbol-add.png) | 실행 중 종목 추가 | `symbol` 패키지(`SymbolController`, `SymbolRegistrationService`, `SymbolDao.insertPreparing`·`activate`) | 그림대로 준비중등록 → 새종목준비 → 활성화. 엔진은 없을 때만 만든다(`prepareIfAbsent`). 요청에 종목 이름을 함께 받는다(ADR 0014) |
| [front-gateway](images/front-gateway.png) | Nginx 역방향 프록시 | `frontend/nginx.conf`, `frontend/Dockerfile` | 그림 메모의 "도입할 때의 구성"을 ADR 0011에서 도입했다 |
| [notification](images/notification.png) | 알림 관리 | `notification` 패키지 | 이름만 영어로 바꿨다: 알림전송Service → `NotificationPushService`(전송예약 → `signal`), SSE연결관리 → `SseEmitterRegistry`, 알림기록기 → `NotificationWriter`. 묶음 없는 "번호이후조회"는 쓰지 않아 만들지 않았다 |
| [query](images/query.png) | 조회 관리 | `query` 패키지 | 데모 화면용 종목 목록·호가창 조회를 더했다. 묶음 없는 "주문내역조회"는 만들지 않았다 |
| [configuration](images/configuration.png) | 설정 4종 | `global/config/*Properties` | 체결설정에 넘침 처리값(`drainBatchSize`, `pollTimeout`, `autoStart`), 복구설정에 감시 주기(`monitorInterval`)를 더했다 |

## 구현하며 바꾼 그림

| 그림 | 그림의 설계 | 실제 구현 | 근거 |
|---|---|---|---|
| [matching](images/matching.png) | 종목체결워커가 계좌관리Service를 직접 불러 정산하고, 대기주문목록이 `체결상대찾기`로 상대를 고른다 | 워커가 호가창을 바꾸지 않고 **체결 계획**(`MatchPlan`)을 만들고, **체결확정Service**(`MatchConfirmService`)가 주문·체결·정산·알림을 한 트랜잭션(T2)으로 커밋한 뒤에만 호가창에 반영한다. T2가 실패하면 계획을 버리면 되므로 호가창이 DB와 어긋나지 않는다 | ADR 0007 |
| [recovery](images/recovery.png) | 서버기동리스너 → 주문복구Service가 종목별로 대기 주문을 복원한다 | `SymbolPreparer` 하나가 기동·종목 추가·복구의 공통 경로다. 대기 주문을 복원하고 대기열을 넘침 상태로 시작해 RECEIVED를 DB에서 처리한다. 체결감시(`MatchingMonitor`)는 그림에 없다 | ADR 0008, 0013 |
| [db-erd](images/db-erd.png) | 금액 `DECIMAL(19,2)`, 보유는 매도가능수량·예약수량 | 금액은 원 단위 `BIGINT`, 보유는 **총보유량(`quantity`) + 예약수량**이고 매도 가능 수량은 그 차이로 계산한다. 테이블·관계·알림의 체결번호 NULL 허용·처리순번은 그림과 같다 | ADR 0006 |
| [order-intake](images/order-intake.png) | 지정가 `BigDecimal`, 주문DAO의 미처리주문묶음조회가 마지막 주문번호 커서로 이어 읽는다 | 지정가는 원 단위 `long`. 처리 전 주문은 커서 없이 매번 앞에서부터 읽는다(늦게 커밋된 작은 번호를 건너뛰지 않기 위해) | ADR 0006, 0002, 0005 |
| [account](images/account.png) | 금액 `BigDecimal`, 정산은 체결결과 하나를 받는다 | 금액은 `long`. 정산은 `settle(종목, 체결)`이고 체결확정Service가 T2 안에서 부른다. 계좌 잠금은 T2 시작 때 계좌번호 오름차순으로 먼저 건다 | ADR 0006, 0009 |
| [data-accessor](images/data-accessor.png) | DAO 4종과 메서드 | 위 주문DAO 차이. 잔여주문묶음조회는 상태 하나씩 읽는다(`findRestingAfter(종목, 상태, 마지막번호, 건수)`). 묶음 없는 조회 메서드는 만들지 않았다. 체결 저장은 `TradeDao`로 따로 있다 | ADR 0002, 0013 |
| [module-view](images/module-view.png) | WAS 안에 모듈 10개(Data Accessor, 계좌·종목·주문 관리, 체결 처리, Controller, 복구·알림·조회 관리, 주문 접수) | 도메인 패키지 7개(`account`, `order`, `symbol`, `matching`, `notification`, `query`, `global`). 각 패키지 안을 `controller`/`service`/`dao`/`dto`/`entity`로 나눈다. 주문 접수는 `order`, 복구는 `matching`에 있다 | CLAUDE.md 패키지 규칙 |
