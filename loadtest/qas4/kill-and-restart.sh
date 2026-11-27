#!/bin/bash
# QAS 4: 앱을 강제 종료(SIGKILL)하고 다시 뜰 때까지 복구 시간을 잰다. 앱 EC2의 /opt/stock에서 실행한다.
#   KILL_MODE=pid    (기본) 컨테이너의 Java 프로세스에 SIGKILL을 보낸다. 실제 프로세스 장애와 같아서
#                    재시작 정책(restart: unless-stopped)이 자동으로 다시 띄운다. 그 시간까지 복구 시간에 들어간다.
#   KILL_MODE=docker docker kill. Docker는 이를 사용자가 멈춘 것으로 보고 자동 재시작하지 않으므로
#                    DOWN_SECONDS(기본 5초) 뒤에 이 스크립트가 직접 띄운다.
#   STACK_DIR, HTTP: compose 폴더와 Nginx 주소. 로컬 검증 때만 바꾼다(COMPOSE_FILE로 compose.local-db.yml도 지정).
# 출력 시각은 모두 epoch 초. 재기동 시작은 컨테이너의 새 StartedAt, 거래 재개는 재기동 뒤 넣은 확인 주문이 처리된 시각이다.
set -euo pipefail
cd "${STACK_DIR:-/opt/stock}"
HTTP=${HTTP:-http://127.0.0.1}
KILL_MODE=${KILL_MODE:-pid}
DOWN_SECONDS=${DOWN_SECONDS:-5}
PROBE_ACCOUNT=${PROBE_ACCOUNT:-1000}

app=$(docker compose ps -q app)
front=$(docker compose ps -q front)
epoch() { date -d "$1" +%s.%N; }
now() { date +%s.%N; }
started_before=$(docker inspect -f '{{.State.StartedAt}}' "$app")
[ "$KILL_MODE" = pid ] && docker pull -q alpine:3.20 >/dev/null   # 첫 실행의 이미지 받는 시간이 측정에 섞이지 않게 미리 받는다

t_kill=$(now)
if [ "$KILL_MODE" = pid ]; then
  # Docker Desktop에서도 동작하도록 호스트 PID 네임스페이스의 컨테이너로 보낸다(EC2에서는 sudo kill -9와 같다)
  docker run --rm --pid=host alpine:3.20 kill -9 "$(docker inspect -f '{{.State.Pid}}' "$app")"
  until [ "$(docker inspect -f '{{.State.StartedAt}}' "$app")" != "$started_before" ]; do sleep 0.1; done
  echo "after kill: restarted by restart policy, restartCount=$(docker inspect -f '{{.RestartCount}}' "$app")"
else
  docker kill -s KILL "$app" >/dev/null
  sleep 1
  echo "after kill: state=$(docker inspect -f '{{.State.Status}}' "$app") restartCount=$(docker inspect -f '{{.RestartCount}}' "$app")"
  sleep "$DOWN_SECONDS"
  docker compose up -d app >/dev/null 2>&1
  app=$(docker compose ps -q app)
fi
t_start=$(epoch "$(docker inspect -f '{{.State.StartedAt}}' "$app")")

until docker exec "$front" wget -q -O - http://app:8080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; do
  sleep 0.2
done
t_ready=$(now)

# 거래 재개 확인: 체결 상대가 없는 1원 매수를 넣고 처리(OPEN)될 때까지 기다린다
probe_id=$(curl -s -X POST "$HTTP/api/accounts/$PROBE_ACCOUNT/orders" -H 'Content-Type: application/json' \
  -d "{\"clientOrderId\":\"probe-$(date +%s%N)\",\"symbolCode\":\"SYM10\",\"side\":\"BUY\",\"limitPrice\":1,\"quantity\":1}" \
  | sed -n 's/.*"orderId":\([0-9]*\).*/\1/p')
[ -n "$probe_id" ] || { echo "probe order was not accepted"; exit 1; }
until curl -s "$HTTP/api/accounts/$PROBE_ACCOUNT/orders?size=5" | grep -q "\"orderId\":$probe_id,[^}]*\"status\":\"OPEN\""; do
  sleep 0.2
done
t_trade=$(now)

logs=$(docker compose logs app --since "$(date -u -d "@${t_start%.*}" +%Y-%m-%dT%H:%M:%SZ)" 2>&1)
echo "kill_at=$t_kill start_at=$t_start ready_at=$t_ready trade_at=$t_trade probe_order=$probe_id"
awk -v k="$t_kill" -v s="$t_start" -v r="$t_ready" -v t="$t_trade" 'BEGIN {
  printf "down(kill->start)=%.2fs  start->ready=%.2fs  start->trade(복구 시간)=%.2fs\n", s-k, r-s, t-s }'
echo "$logs" | grep -E "Started StockSingleWasApplication|startup recovery done" | sed 's/.*--- \[[^]]*\] \[ *[^]]*\] //' | cut -c1-160
echo "restored resting orders: $(echo "$logs" | grep -o 'ready: [0-9]* resting' | awk '{s+=$2} END {print s}')"
