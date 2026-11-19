# 프로젝트 작업 안내

작업 전에 `docs/IMPLEMENTATION_GUIDE.md`를 읽고 따른다. 확정된 설계 결정은 `docs/adr/`에 있다.
현재 저장소는 EA 설계(모의 주식 매매 시스템 메인 아키텍처)를 구현하는 새 Spring 프로젝트다.
`C:/dev/stock-prototype`은 비교 및 참고 자료로만 사용하고 수정하지 않는다.

## 작업 규칙

- 기능마다 실패하는 테스트를 먼저 작성하고, 구현해 통과시킨 뒤 리팩터링한다.
- 진행 단계와 체크리스트는 `docs/IMPLEMENTATION_PLAN.md`를 따른다. 한 번에 한 항목씩 진행한다.
- 기본 브랜치는 `main`, 작업 브랜치는 `{type}/{short-slug}`(예: `feat/symbol-worker`), PR 대상은 `main`이다.
- 커밋 메시지는 `.github/commit_template.txt`, PR 본문은 `.github/pull_request_template.md` 양식을 따른다.
- 커밋, push, PR 생성은 사용자가 요청할 때만 실행한다.
- ADR과 다른 구현이 필요하면 구현 전에 사용자에게 알리고 새 ADR로 남긴다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.

## 실행

- 로컬 DB: `docker compose up -d mysql` (DB `stock_single_was`, 포트 3307. 프로토타입 DB와 분리)
- 테스트: `.\gradlew test` (Testcontainers MySQL 8.4를 사용하므로 Docker가 실행 중이어야 한다)
- 로컬 실행: `.\gradlew bootRun --args='--spring.profiles.active=local'`
