# ADR 0006. 금액은 원 단위 정수, 보유는 총보유량과 예약 수량으로 저장

- 상태: 채택 (2026-11-19)
- 관련: IMPLEMENTATION_GUIDE §3, EA 매매 DB 관계도

## 맥락

EA 초기 설계는 가격·금액을 `BigDecimal` / `DECIMAL(19,2)`로, 보유 종목을 `매도가능수량` + `예약수량`으로 그렸다.
인계서 §3은 "가격·금액·수량은 정수로 다루고 곱셈 오버플로를 검사한다", "`quantity`는 총보유량, `reserved_quantity`는 예약 수량"이라고 정했다. 둘이 충돌한다.

## 결정

1. 가격·현금은 원 단위 `long` / `BIGINT`, 수량은 `int` / `INT`. 소수 단위는 쓰지 않는다.
2. 곱셈·덧셈·뺄셈은 `Math.*Exact`로 계산해 오버플로면 예외를 던진다. 접수 단계에서는 `AMOUNT_OUT_OF_RANGE`로 거절한다.
3. `accounts.cash_balance`는 사용 가능 현금, `reserved_cash`는 매수 접수가 묶어 둔 현금이다. 총현금은 둘의 합이다.
4. `holdings.quantity`는 총보유량, `reserved_quantity`는 매도 접수가 묶어 둔 수량이다. 매도 가능 수량은 `quantity - reserved_quantity`로 계산하고 저장하지 않는다.
5. 매수 정산은 예약 현금을 풀고 지정가와 체결가의 차액만 사용 가능 현금으로 돌려준다. 접수 때 이미 옮긴 금액을 정산에서 다시 빼지 않는다.

## 근거

- 원화 주가에는 소수점이 없다. `BigDecimal`은 `equals`와 scale 비교에서 실수하기 쉽고, 정수 범위 검사보다 오버플로 전략이 복잡하다.
- 총보유량을 저장하면 `reserved_quantity <= quantity`를 CHECK로 강제할 수 있다. 매도가능수량을 저장하면 두 값의 관계를 DB가 검사할 수 없다.

## 결과와 비용

- 다른 통화나 소수 단위 시장을 지원하려면 다시 설계해야 한다.
- EA 매매 DB 관계도와 클래스의 금액 타입은 이 결정에 맞춰 고친다.

## 검증

- `SettlementCalculatorTest`: int 범위를 넘는 금액, long 오버플로, 매수자 총현금이 체결대금만큼만 줄어드는지.
- `MatchConfirmServiceTest`: 지정가보다 싸게 체결되면 차액 환불, 보유 행이 없던 매수자는 보유 행 생성.

## 재검토 조건

소수 단위 가격(해외 주식 등)이나 다른 통화를 지원할 때.
