# QAS 4 서버 복구 측정 절차

## 목표와 판정

가이드 QAS 4: 미체결 주문 10만 건이 있는 상태에서 WAS를 강제 종료한다(DB는 살아 있음).

| 항목 | 기준 |
|---|---|
| 접수 확정 주문 유실 | **1건 이하** |
| 복구 시간 | 기록(목표값 없음): 재기동 시작부터 거래 재개까지 |

용어:

- **접수 확정 주문**: 클라이언트가 `202`와 주문번호를 받은 주문. 응답을 받지 못한 주문은 대상이 아니다.
- **유실**: 접수 확정 주문이 재시작 후 DB에 없는 것. 상태는 보지 않는다. 복구 후 체결된 주문을 유실로 오판하지 않는다(가이드 QAS 4).
- **재기동 시작**: 새 앱 컨테이너의 `StartedAt`.
- **거래 재개**: 재기동 뒤 넣은 확인 주문(1원 매수, 체결 상대 없음)이 처리되어 `OPEN`이 된 시각.

함께 확인하는 것(하나라도 어긋나면 불합격):

- 재시작 후 처리가 끝났을 때 `RECEIVED`로 남은 주문 0건.
- `db-check.sql`의 불변식: 현금 총량, 종목별 보유 총량, 계좌별 예약 현금, 계좌·종목별 예약 수량, 주문별 체결 수량. 이중 체결·이중 정산·예약 누수가 있으면 깨진다.

## 강제 종료 방식

`kill-and-restart.sh`는 기본으로 앱 컨테이너의 Java 프로세스에 SIGKILL을 보낸다(`KILL_MODE=pid`).
실제 프로세스 장애(OOM, JVM 종료)와 같아서 재시작 정책(`restart: unless-stopped`)이 자동으로 다시 띄운다.

`docker kill`(`KILL_MODE=docker`)은 Docker가 "사용자가 멈춤"으로 처리해 **재시작 정책이 적용되지 않는다**(로컬에서 확인, 재시작 횟수 0).
이 방식으로는 사람이 다시 띄우기 전까지 서버가 죽어 있다. 장애 재현에는 쓰지 않는다.

## 환경 (ADR 0012)

| 역할 | 사양 |
|---|---|
| 앱 EC2 | `m7i.large`, `ap-northeast-2a`, Nginx + 앱 컨테이너 |
| RDS | `db.m7g.large`, MySQL 8.4, `ap-northeast-2a` |
| 부하 생성기 | `c7i.xlarge`, `ap-northeast-2a`, k6 `grafana/k6:2.3.0` 컨테이너. 앱 EC2의 **사설 IP**로 요청 |

QAS 4는 처리량 측정이 아니라(유입 초당 200건) ADR 0012의 보정 실행은 하지 않는다. 생성기 CPU만 함께 기록한다.

## 스크립트 (`loadtest/qas4/`)

| 파일 | 실행 위치 | 하는 일 |
|---|---|---|
| `fill.js` | 생성기 | 체결되지 않는 대기 주문 `TOTAL`건(기본 10만). 매수 900원, 매도 1,100원 |
| `orders.js` | 생성기 | 1,000원 매수·매도를 일정 유입으로 보낸다(서로 체결됨). 접수 확정마다 `ACK <주문번호> <계좌> <요청번호>`를 남긴다 |
| `kill-and-restart.sh` | 앱 EC2 | 강제 종료, 재시작 대기, readiness, 확인 주문으로 거래 재개 시각, 기동 로그 요약 |
| `verify.js` | 생성기 | `ACK` 목록을 계좌별 주문 내역 API와 대조해 유실·미처리·중복을 센다 |
| `db-check.sql` | 앱 EC2 | 상태별 건수와 불변식 |

## 절차

`docs/DEPLOY.md`의 상태 파일(`$ST`)과 `ssm_run`을 쓴다. 앱 EC2용 `ssm_run`은 `$INSTANCE`, 생성기용은 `$LOADGEN`을 대상으로 한다.

### 1. 자원 켜기와 배포

DEPLOY.md의 "다시 켤 때"로 앱 EC2와 RDS를 켜고, 측정할 커밋의 이미지로 "다시 배포할 때"를 따른다.
측정 기록에 앱·프론트 이미지 digest를 남긴다.

### 2. 부하 생성기 만들기 (측정할 때만)

```bash
. $ST
export MSYS_NO_PATHCONV=1
LOADGEN_SG=$(aws ec2 create-security-group --group-name stock-single-was-loadgen \
  --description "stock-single-was load generator" --vpc-id $VPC --query GroupId --output text)
# 앱 EC2는 생성기에서 오는 80도 받는다(ADR 0012)
aws ec2 authorize-security-group-ingress --group-id $EC2_SG \
  --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,UserIdGroupPairs=[{GroupId=$LOADGEN_SG,Description=loadgen}]"
LOADGEN=$(aws ec2 run-instances --image-id $AMI --instance-type c7i.xlarge --subnet-id $SUBNET \
  --iam-instance-profile Name=stock-single-was-ec2 --security-group-ids $LOADGEN_SG \
  --metadata-options HttpTokens=required --user-data "file://$(cygpath -m user-data.sh)" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=stock-single-was-loadgen}]' \
  --query 'Instances[0].InstanceId' --output text)
APP_PRIVATE_IP=$(aws ec2 describe-instances --instance-ids $INSTANCE \
  --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)
printf 'LOADGEN_SG=%s\nLOADGEN=%s\nAPP_PRIVATE_IP=%s\n' $LOADGEN_SG $LOADGEN $APP_PRIVATE_IP >> $ST
aws ec2 wait instance-status-ok --instance-ids $LOADGEN
```

`user-data.sh`는 DEPLOY.md 4단계와 같다(Docker 설치). 생성기는 SSM 접속만 쓰므로 같은 EC2 역할을 쓴다.

### 3. 스크립트 보내기

```bash
. $ST
put_scripts() {   # $1: 대상 인스턴스
  { echo 'set -e; mkdir -p /opt/qas4; cd /opt/qas4'
    for f in fill.js orders.js verify.js kill-and-restart.sh db-check.sql; do
      echo "echo $(base64 -w0 loadtest/qas4/$f) | base64 -d > $f"; done
    echo 'chmod +x kill-and-restart.sh; docker pull -q grafana/k6:2.3.0 >/dev/null 2>&1 || true; ls'; } > put-qas4.sh
  INSTANCE=$1 ssm_run put-qas4.sh
}
put_scripts $LOADGEN
put_scripts $INSTANCE
```

### 4. 초기화와 대기 주문 10만 건

DEPLOY.md 8단계(seed)로 데이터를 초기화한 뒤 생성기에서 쌓는다.

```bash
K6="docker run --rm --network host -v /opt/qas4:/scripts grafana/k6:2.3.0 run -q"
echo "$K6 -e BASE_URL=http://$APP_PRIVATE_IP -e TOTAL=100000 /scripts/fill.js 2>&1 | grep -E 'checks_failed|iterations'" > fill.sh
INSTANCE=$LOADGEN ssm_run fill.sh
```

`checks_failed 0.00%`여야 한다. 앱 EC2에서 처리가 끝났는지(RECEIVED 0)와 `fill OPEN 100000`을 확인한다.

```bash
cat > dbcheck.sh <<'EOF'
cd /opt/stock; set -a; . ./.env; set +a
docker run --rm -i -e MYSQL_PWD="$DB_PASSWORD" mysql:8.4 mysql -h "$DB_HOST" -u "$DB_USER" stock_single_was < /opt/qas4/db-check.sql
EOF
ssm_run dbcheck.sh
```

### 5. 주문을 넣는 도중 강제 종료

생성기에서 `orders.js`를 백그라운드로 시작하고(180초, 초당 200건), 60초 뒤 앱 EC2에서 강제 종료한다.
생성기의 CPU를 1초 간격으로 함께 기록한다.

```bash
cat > orders.sh <<EOF
cd /opt/qas4; rm -f acks.log
nohup sh -c "sar -u 1 190 > cpu.log 2>&1 & $K6 -e BASE_URL=http://$APP_PRIVATE_IP -e RATE=200 -e DURATION=180s -e RUN=aws1 \
  --log-output=file=/scripts/acks.log --log-format=raw /scripts/orders.js > orders.out 2>&1" >/dev/null 2>&1 &
echo started
EOF
INSTANCE=$LOADGEN ssm_run orders.sh
sleep 60
echo 'cd /opt/stock && STACK_DIR=/opt/stock bash /opt/qas4/kill-and-restart.sh' > kill.sh
ssm_run kill.sh          # 복구 시간 출력을 측정 기록에 남긴다
```

`sar`가 없으면 생성기에 `sudo dnf install -y sysstat`을 먼저 한다.

### 6. 대조와 점검

`orders.js`가 끝나고(시작 후 약 3분) 앱 EC2에서 RECEIVED가 0이 된 뒤 실행한다.

```bash
echo "grep -E 'orders_acked|orders_not_acked|dropped' /opt/qas4/orders.out; awk 'NR>3 && \$NF ~ /^[0-9.]+\$/ {u=100-\$NF; if (u>m) m=u} END {printf \"loadgen cpu max %.1f%%\n\", m}' /opt/qas4/cpu.log; $K6 --log-format=raw -e BASE_URL=http://$APP_PRIVATE_IP -e ACKS=/scripts/acks.log /scripts/verify.js 2>&1 | grep QAS4_RESULT" > verify.sh
INSTANCE=$LOADGEN ssm_run verify.sh
ssm_run dbcheck.sh
```

### 7. 정리

```bash
aws ec2 terminate-instances --instance-ids $LOADGEN
aws ec2 wait instance-terminated --instance-ids $LOADGEN
aws ec2 revoke-security-group-ingress --group-id $EC2_SG \
  --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,UserIdGroupPairs=[{GroupId=$LOADGEN_SG}]"
aws ec2 delete-security-group --group-id $LOADGEN_SG
```

앱 EC2와 RDS는 DEPLOY.md대로 중지한다.

## 기록 양식

| 항목 | 값 |
|---|---|
| 일시, 커밋, 앱·프론트 이미지 digest | |
| 대기 주문 수(측정 직전 OPEN) | |
| 유입 / 강제 종료 시점 | 초당 200건 / 시작 60초 후, `KILL_MODE=pid` |
| 접수 확정 / 확정 못 받음 / 미전송(`dropped_iterations`) | |
| **유실(`missing`)** | |
| 처리 후 RECEIVED 잔류 | |
| 불변식(`db-check.sql` 3~7) | |
| 강제 종료 → 재기동 시작 | |
| 재기동 시작 → readiness | |
| **재기동 시작 → 거래 재개(복구 시간)** | |
| Spring 기동 / 엔진 복원(종목 10개) | |
| 생성기 CPU 최대 | |
| 판정 | |

## 로컬 검증 (2026-11-27, 절차 확인용. QAS 4 결과가 아니다)

Docker Desktop에서 `deploy/compose.yml` + `compose.local-db.yml`, 대기 주문 2,000건, 유입 초당 50건, 40초 중 10초에 강제 종료.

| 항목 | `KILL_MODE=docker` | `KILL_MODE=pid` |
|---|---|---|
| 접수 확정 / 확정 못 받음 | 1,150 / 850 | 1,653 / 348 |
| 유실 / RECEIVED 잔류 / 중복 | 0 / 0 / 0 | 0 / 0 / 0 |
| 불변식 | 모두 일치 | 모두 일치 |
| 재시작 | 재시작 정책 미적용, 스크립트가 5초 후 기동 | 재시작 정책이 자동 기동 |
| 재기동 시작 → 거래 재개 | 12.23초 | 8.02초(스크립트 수정 후 재측정 6.27초) |
| 엔진 복원(종목 10개) | 905ms | 311ms |

검증 중 고친 것:

- `orders.js`가 종목마다 매수나 매도 한쪽만 만들어 체결이 일어나지 않았다(`db-check.sql`의 kill 주문이 모두 OPEN). 종목 안에서 매수·매도를 번갈아 보내게 고쳤다.
- 앱이 죽어 있는 동안 요청이 5초 시간 초과까지 VU를 붙잡아 미전송이 생겼다. 미리 만드는 VU를 유입의 6배로 늘렸다.
- 처음 쓰는 이미지를 받는 시간이 "강제 종료 → 재기동 시작"에 섞여(6.2초) 미리 받게 했다(0.7초).

AWS에서는 아직 실행하지 않았다.
