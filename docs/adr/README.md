# 아키텍처 결정 기록 (ADR)

구현 전에 확정한 설계 결정이다. 결정을 바꿀 때는 기존 ADR을 수정하지 않고 새 ADR을 추가해 이전 ADR을 `대체됨`으로 표시한다.

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-time-priority-by-processing-sequence.md) | 시간 우선 기준은 종목 워커의 처리 순번 | 채택 |
| [0002](0002-unprocessed-order-recovery-order.md) | 재기동 시 처리 전 주문은 주문 ID 순으로 복원 | 채택 |
| [0003](0003-notification-id-and-account-lock.md) | 알림은 전역 AUTO_INCREMENT + INSERT 전 계좌 잠금 | 채택 |
| [0004](0004-no-admission-rejection-under-burst.md) | 순간 폭주에서 접수 단계 혼잡 거절을 하지 않는다 | 채택 |
| [0005](0005-delivery-failure-handling-and-monitor.md) | 대기열 전달 실패는 즉시 처리하고, 주기 감시는 상태 불일치만 본다 | 채택 |

양식: 상태 / 맥락 / 결정 / 근거 / 결과와 비용 / 검증 / 재검토 조건
