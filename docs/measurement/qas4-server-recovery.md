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
    # k6 컨테이너는 root가 아니므로 결과 파일(acks.log)을 쓰려면 폴더 쓰기 권한이 필요하다
    echo 'chmod +x kill-and-restart.sh; chmod 0777 /opt/qas4'
    echo 'for i in grafana/k6:2.3.0 alpine:3.20 mysql:8.4; do docker pull -q $i >/dev/null 2>&1 || true; done; ls'; } > put-qas4.sh
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
# ssm_run은 스크립트를 표준 입력으로 bash에 넘긴다. 파일을 넘기지 않는 docker run -i는 남은 스크립트를 먹으므로 </dev/null을 준다
q() { docker run --rm -e MYSQL_PWD="$DB_PASSWORD" mysql:8.4 mysql -h "$DB_HOST" -u "$DB_USER" stock_single_was "$@" </dev/null; }
for i in $(seq 1 120); do [ "$(q -N -e "SELECT COUNT(*) FROM orders WHERE status='RECEIVED'")" = 0 ] && break; sleep 2; done
docker run --rm -i -e MYSQL_PWD="$DB_PASSWORD" mysql:8.4 mysql -h "$DB_HOST" -u "$DB_USER" stock_single_was < /opt/qas4/db-check.sql
EOF
ssm_run dbcheck.sh
```

### 5. 주문을 넣는 도중 강제 종료

생성기에서 `orders.js`를 시작하고(180초, 초당 200건), 60초 뒤 앱 EC2에서 강제 종료한다. 생성기 CPU를 1초 간격으로 기록한다.

**60초 대기는 앱 EC2 안에서 한다.** 로컬 PC에서 `sleep 60` 뒤에 종료 명령을 보내면, PC가 잠시 멈췄을 때 부하가 끝난 뒤에 종료되어
측정이 무효가 된다(2026-11-27 2차 실행에서 45분 늦게 실행됨). 두 명령을 연달아 보내고 결과는 나중에 받는다.

```bash
cat > orders.sh <<EOF
cd /opt/qas4; rm -f acks.log orders.out cpu.log
nohup sar -u 1 200 > cpu.log 2>&1 &
nohup docker run --rm --name qas4-orders --network host -v /opt/qas4:/scripts grafana/k6:2.3.0 run -q \
  -e BASE_URL=http://$APP_PRIVATE_IP -e RATE=200 -e DURATION=180s -e RUN=aws1 \
  --log-output=file=/scripts/acks.log --log-format=raw /scripts/orders.js > orders.out 2>&1 &
sleep 2; docker ps --format '{{.Names}}' | grep -q qas4-orders && echo started || cat orders.out
EOF
printf 'sleep 60\ncd /opt/stock && STACK_DIR=/opt/stock bash /opt/qas4/kill-and-restart.sh 2>&1\n' > kill.sh
send() { MSYS_NO_PATHCONV=1 aws ssm send-command --instance-ids $1 --document-name AWS-RunShellScript --timeout-seconds 900 \
  --parameters "{\"commands\":[\"echo $(base64 -w0 "$2") | base64 -d | bash\"],\"executionTimeout\":[\"900\"]}" \
  --query Command.CommandId --output text; }
O=$(send $LOADGEN orders.sh); K=$(send $INSTANCE kill.sh)
# 약 2분 뒤: 복구 시간 출력을 측정 기록에 남긴다
aws ssm get-command-invocation --command-id $K --instance-id $INSTANCE --query '[Status,StandardOutputContent]' --output text
```

생성기에는 `sysstat`(sar)이 필요하다. 생성기 부팅 스크립트에 `dnf install -y docker sysstat`을 넣는다.

### 6. 대조와 점검

`orders.js`가 끝나고(시작 후 약 3분) 앱 EC2에서 RECEIVED가 0이 된 뒤 실행한다.

```bash
cat > verify.sh <<EOF
cd /opt/qas4
while docker ps --format '{{.Names}}' | grep -q qas4-orders; do sleep 3; done
grep -E 'orders_acked|orders_not_acked|dropped_iterations|iterations\.\.' orders.out
awk '\$NF ~ /^[0-9.]+\$/ && \$2=="all" {u=100-\$NF; n++; s+=u; if (u>m) m=u; if (u>=80) h++} END {printf "cpu samples=%d avg=%.1f%% max=%.1f%% >=80%%=%d\n", n, s/n, m, h}' cpu.log
$K6 --log-format=raw -e BASE_URL=http://$APP_PRIVATE_IP -e ACKS=/scripts/acks.log /scripts/verify.js 2>&1 | grep QAS4_RESULT
EOF
INSTANCE=$LOADGEN ssm_run verify.sh
ssm_run dbcheck.sh
```

`orders_not_acked`, `dropped_iterations`는 0이면 k6 요약에 나오지 않는다.

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

## 측정 결과 1차 (2026-11-27, AWS)

### 테스트 환경

| 항목 | 값 |
|---|---|
| 앱 EC2 | `m7i.large`, `ap-northeast-2a`, Amazon Linux 2023, Nginx + 앱 컨테이너 |
| DB | RDS `db.m7g.large`, MySQL 8.4.13, `ap-northeast-2a`, Single-AZ. `REPEATABLE-READ`, 버퍼 풀 5.25GB, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1` |
| 부하 생성기 | `c7i.xlarge`, `ap-northeast-2a`, `grafana/k6:2.3.0`, 앱 EC2 사설 IP로 요청 |
| 코드·이미지 | 커밋 `40ac717`. 앱 `sha256:c6c38fb2c18a110e11a9eb638b3689f9d81df16bea2df93650c7bdfc78b043be`, 프론트 `sha256:8a39e9499d3d22ff61e25d74dbfccc50cb3cdee106e22b010745f7b8ec33a2b9` |
| JVM | `-Xmx4g -Duser.timezone=UTC` |

### 스크립트(케이스)

데모 seed → `fill.js` 100,000건(처리 후 OPEN 100,000, 불변식 일치) → `orders.js` 초당 200건 180초 →
시작 60초 뒤 `kill-and-restart.sh`(`KILL_MODE=pid`) → `verify.js` → `db-check.sql`.

### 결과

| 항목 | 값 |
|---|---|
| 보낸 주문 / 미전송 | 36,001건(초당 200.0건) / 0 |
| 접수 확정 / 확정 못 받음 | 34,576 / 1,425 |
| **유실** | **0** |
| 처리 후 RECEIVED 잔류 / 중복 | 0 / 0 |
| 불변식(`db-check.sql` 3~7) | 모두 일치 |
| 이번 실행 주문의 DB 저장 | 34,577건(FILLED 34,571, OPEN 6). 확정보다 1건 많다: 커밋 후 응답 전에 종료된 주문(커밋 결과 불명, 같은 요청번호로 재요청하면 그 주문을 돌려받는다) |
| 강제 종료 → 재기동 시작 | 0.77초(재시작 정책 자동 기동) |
| 재기동 시작 → readiness | 14.63초 |
| **재기동 시작 → 거래 재개(복구 시간)** | **17.73초** |
| Spring 기동 / 엔진 복원(10종목, 대기 주문 100,006건) | 5.95초 / 7.31초 |
| 생성기 CPU(1초 샘플 180개) | 평균 4.6%, 최대 25.7%, 80% 이상 0회 |
| **판정** | **합격**(유실 0 ≤ 1) |

참고: 같은 환경에서 부하 없이 강제 종료했을 때(측정 무효였던 2차 실행) 엔진 복원 1.41초, 복구 시간 8.07초였다.
부하 중 엔진 복원이 5배 느린 원인은 확인하지 않았다. 재기동 중에도 접수(T1)를 받아 복원 조회와 같은 RDS를 쓰는 것이 후보다.

무효 실행: 1차는 k6가 결과 파일 쓰기 권한이 없어 시작하지 못했고, 2차는 로컬 PC의 대기가 멈춰 부하가 끝난 뒤 종료됐다.
두 원인은 위 절차에 반영했다.

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

AWS 측정 결과는 위 "측정 결과 1차"에 있다.
