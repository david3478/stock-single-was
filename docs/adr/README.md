# 아키텍처 결정 기록 (ADR)

구현 전에 확정한 설계 결정이다. 결정을 바꿀 때는 기존 ADR을 수정하지 않고 새 ADR을 추가해 이전 ADR을 `대체됨`으로 표시한다.

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-time-priority-by-processing-sequence.md) | 시간 우선 기준은 종목 워커의 처리 순번 | 채택 |
| [0002](0002-unprocessed-order-recovery-order.md) | 재기동 시 처리 전 주문은 주문 ID 순으로 복원 | 채택 |
| [0003](0003-notification-id-and-account-lock.md) | 알림은 전역 AUTO_INCREMENT + INSERT 전 계좌 잠금 | 채택 |
| [0004](0004-no-admission-rejection-under-burst.md) | 순간 폭주에서 접수 단계 혼잡 거절을 하지 않는다 | 채택 |
| [0005](0005-delivery-failure-handling-and-monitor.md) | 대기열 전달 실패는 즉시 처리하고, 주기 감시는 상태 불일치만 본다 | 채택 (③·감시 자동 복구는 0013으로 보류) |
| [0006](0006-integer-money-and-holding-quantity.md) | 금액은 원 단위 정수, 보유는 총보유량과 예약 수량으로 저장 | 채택 |
| [0007](0007-match-plan-and-t2-confirm-service.md) | 체결은 불변 계획 → T2 확정 서비스 → 커밋 후 호가창 반영 | 채택 |
| [0008](0008-symbol-engine-starts-in-overflow-mode.md) | 종목 엔진은 DB에서 복원하고 넘침 상태로 시작한다 | 채택 |
| [0009](0009-lock-order-and-notification-unit.md) | 잠금 순서는 계좌 행 → 보유 행, 알림은 체결 1건당 계좌별 1행 | 채택 (0003 ⑤ 보완) |
| [0010](0010-first-deploy-single-ec2.md) | 첫 배포는 EC2 한 대에 앱과 MySQL을 함께, 내 IP만 접속 허용 | 대체됨 (0011) |
| [0011](0011-front-nginx-app-rds-split.md) | 프론트는 Nginx 컨테이너, 앱은 API만, DB는 RDS로 분리 | 채택 |
| [0012](0012-measurement-environment-spec.md) | 측정 환경 사양: 앱 m7i.large, RDS db.m7g.large, 같은 AZ, 측정할 때만 켜는 부하 생성기 | 채택 |
| [0013](0013-recovery-scope-monitor-warn-only.md) | 7단계는 기동 복구와 감시 경고까지, 종목 단위 자동 복구는 향후 과제 | 채택 |
| [0014](0014-runtime-symbol-registration.md) | 실행 중 종목 추가: 준비중 등록 → 엔진 준비 → 활성화, 같은 요청 재시도로 완료 | 채택 |

양식: 상태 / 맥락 / 결정 / 근거 / 결과와 비용 / 검증 / 재검토 조건
