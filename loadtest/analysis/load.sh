#!/bin/bash
# k6 주문 기록과 SSE 수집기 기록을 measure 스키마에 넣는다(묶음 INSERT. LOAD DATA LOCAL INFILE 서버 설정에 의존하지 않는다).
#   MYSQL="mysql -h HOST -u USER -pPASS" loadtest/analysis/load.sh <run> <k6 로그> <수집기 OUT 폴더>
# 같은 run을 다시 넣으면 먼저 지우고 넣는다. 측정의 부하·관찰이 끝난 뒤에만 실행한다.
set -euo pipefail
RUN=$1; K6LOG=$2; COLL=$3
: "${MYSQL:?MYSQL command is required}"
here=$(dirname "$0")
BATCH=1000

$MYSQL < "$here/schema.sql"

{
  echo "DELETE FROM measure.k6_runs WHERE run='$RUN'; DELETE FROM measure.k6_orders WHERE run='$RUN';"
  echo "DELETE FROM measure.sse_events WHERE run='$RUN'; DELETE FROM measure.sse_markers WHERE run='$RUN';"

  awk -v run="$RUN" '/^START /{printf "INSERT INTO measure.k6_runs VALUES (\x27%s\x27,%s,%s);\n", run, $2, $3}' "$K6LOG"

  # ORD <순번> <계좌> <clientOrderId> <종목> <보낸 ms> <HTTP> <주문번호 또는 -> <응답 ms>
  awk -v run="$RUN" -v b=$BATCH '/^ORD /{
      if (n % b == 0) { if (n) print ";"; printf "INSERT INTO measure.k6_orders VALUES" } else printf ","
      printf "(\x27%s\x27,%s,%s,\x27%s\x27,\x27%s\x27,%s,%s,%s,%s)", run, $2, $3, $4, $5, $6, $7, ($8=="-"?"NULL":$8), $9
      n++ } END { if (n) print ";" }' "$K6LOG"

  # account,gen,event_id,type,order_id,trade_id,recv_ms
  awk -F, -v run="$RUN" -v b=$BATCH 'NR>1{
      if (n % b == 0) { if (n) print ";"; printf "INSERT INTO measure.sse_events VALUES" } else printf ","
      printf "(\x27%s\x27,%s,%s,%s,\x27%s\x27,%s,%s,%s)", run, $1, $2, $3, $4, ($5==""?"NULL":$5), ($6==""?"NULL":$6), $7
      n++ } END { if (n) print ";" }' "$COLL/events.csv"

  # account,gen,kind,value,ms (value에는 쉼표가 없다. 수집기가 ;로 바꾼다)
  awk -F, -v run="$RUN" -v b=$BATCH 'NR>1{
      v=$4; gsub(/\x27/, "", v)
      if (n % b == 0) { if (n) print ";"; printf "INSERT INTO measure.sse_markers VALUES" } else printf ","
      printf "(\x27%s\x27,%s,%s,\x27%s\x27,%s,%s)", run, $1, $2, $3, (v==""?"NULL":"\x27" v "\x27"), $5
      n++ } END { if (n) print ";" }' "$COLL/markers.csv"
} | $MYSQL

$MYSQL -N -e "SELECT 'k6_orders', COUNT(*) FROM measure.k6_orders WHERE run='$RUN'
  UNION ALL SELECT 'sse_events', COUNT(*) FROM measure.sse_events WHERE run='$RUN'
  UNION ALL SELECT 'sse_markers', COUNT(*) FROM measure.sse_markers WHERE run='$RUN'"
