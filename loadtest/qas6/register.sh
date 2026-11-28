#!/bin/bash
# QAS 6 운영자 작업: 신규 종목을 한 종목씩 연달아 등록한다(앞 요청의 응답을 받은 뒤 다음 요청, IMPLEMENTATION_GUIDE §10).
# 등록 API는 종목이 ACTIVE가 된 뒤 응답한다(ADR 0014). 끝나면 종목 목록 조회로 신규 종목이 모두 ACTIVE인지 확인한다.
#   BASE_URL=http://<앱> OUT=/out [PREFIX=NEW COUNT=10] [RESTART_CHECK='docker inspect ...'] loadtest/qas6/register.sh
# RESTART_CHECK: 앱 컨테이너 재시작 횟수와 시작 시각을 출력하는 명령. 등록 전후에 실행해 비교한다(같아야 한다).
#   로컬: docker inspect -f '{{.RestartCount}} {{.State.StartedAt}}' <앱 컨테이너>
#   AWS: 앱 EC2에서 같은 명령을 SSM으로 실행하고 결과를 기록한다(이 스크립트 밖).
# 출력: $OUT/register.csv(code,start_ms,end_ms,http,elapsed_ms), $OUT/register-summary.txt
set -euo pipefail
: "${BASE_URL:?BASE_URL is required}"
: "${OUT:?OUT is required}"
PREFIX=${PREFIX:-NEW}
COUNT=${COUNT:-10}
mkdir -p "$OUT"
now_ms() { date +%s%3N; }

restart_state() {
  if [ -n "${RESTART_CHECK:-}" ]; then bash -c "$RESTART_CHECK"; else echo "not-checked"; fi
}

before=$(restart_state)
echo "code,start_ms,end_ms,http,elapsed_ms" > "$OUT/register.csv"
first_ms=$(now_ms)
for n in $(seq 1 "$COUNT"); do
  code=$(printf '%s%02d' "$PREFIX" "$n")
  s=$(now_ms)
  # 종목명은 영문: 클라이언트 명령줄 인코딩(예: Windows)에 따라 한글이 깨져 400이 나는 것을 피한다.
  # curl이 실패해도(연결 실패·시간 초과) -w가 000을 출력하므로 종료 코드는 무시한다
  http=$(curl -s -o "$OUT/register-$code.json" -w '%{http_code}' -m 120 -X POST "$BASE_URL/api/symbols" \
    -H 'Content-Type: application/json' -d "{\"code\":\"$code\",\"name\":\"New Symbol $(printf '%02d' "$n")\"}") || true
  e=$(now_ms)
  echo "$code,$s,$e,$http,$((e - s))" >> "$OUT/register.csv"
  echo "REGISTER $code http=$http elapsed_ms=$((e - s))"
done
last_ms=$(now_ms)

# 신규 종목이 모두 ACTIVE인지 종목 목록으로 확인한다
list=$(curl -s -m 10 "$BASE_URL/api/symbols")
active=0
for n in $(seq 1 "$COUNT"); do
  code=$(printf '%s%02d' "$PREFIX" "$n")
  if echo "$list" | grep -q "\"code\":\"$code\",\"name\":\"[^\"]*\",\"status\":\"ACTIVE\""; then active=$((active + 1)); fi
done
after=$(restart_state)

{
  echo "first_request_ms=$first_ms"
  echo "last_response_ms=$last_ms"
  echo "total_ms=$((last_ms - first_ms))"
  echo "registered_ok=$(awk -F, 'NR>1 && ($4==201 || $4==200)' "$OUT/register.csv" | wc -l)/$COUNT"
  echo "active_in_list=$active/$COUNT"
  echo "restart_before=$before"
  echo "restart_after=$after"
  [ "$before" = "$after" ] && echo "restart_same=yes" || echo "restart_same=NO"
} | tee "$OUT/register-summary.txt"
