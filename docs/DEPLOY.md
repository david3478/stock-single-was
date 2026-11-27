# 배포 절차 (EC2 + RDS, 수동)

ADR 0011(구성)과 ADR 0012(사양)를 따른다. EC2 한 대에서 Nginx(프론트) 컨테이너와 앱 컨테이너를 `deploy/compose.yml`로 실행하고, DB는 RDS MySQL이다.
HTTP(80)는 내 IP에서만 연다. 앱과 RDS는 외부에서 접속할 수 없다.

```
브라우저 ──80──▶ [EC2 m7i.large] front(Nginx) ── /api/* ──▶ app(Spring) ──3306──▶ [RDS db.m7g.large] MySQL 8.4
                  └────────────────────── 같은 AZ (ap-northeast-2a) ──────────────────────┘
```

모든 명령은 로컬 Git Bash에서 AWS CLI로 실행한다. EC2 안의 명령도 SSM으로 보낸다(SSH 포트를 열지 않는다).
2026-11-27에 이 문서의 명령 그대로 배포해 확인했다(9단계 결과 참고).

## 0. 준비물과 상태 파일

- Docker Desktop, AWS CLI v2. `aws sts get-caller-identity`가 내 계정을 출력해야 한다.
- 셸 변수는 명령 사이에 남지 않으므로 만든 자원 ID를 상태 파일에 모은다. 비밀번호도 들어가므로 저장소 밖에 둔다.

```bash
ST=~/stock-single-was-aws.env      # 저장소 밖. git에 올리지 않는다
export AWS_PAGER="" AWS_REGION=ap-northeast-2
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
VPC=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
SUBNET=$(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC Name=availability-zone,Values=ap-northeast-2a \
  Name=default-for-az,Values=true --query 'Subnets[0].SubnetId' --output text)
MY_IP=$(curl -s https://checkip.amazonaws.com)
printf 'export AWS_PAGER="" AWS_REGION=ap-northeast-2\nACCOUNT=%s\nREGISTRY=%s.dkr.ecr.ap-northeast-2.amazonaws.com\nVPC=%s\nSUBNET=%s\nMY_IP=%s\n' \
  $ACCOUNT $ACCOUNT $VPC $SUBNET $MY_IP > $ST
```

이후 단계는 새 터미널에서도 `. $ST`로 변수를 불러와 이어 간다.
Git Bash는 `/aws/...` 같은 인자를 Windows 경로로 바꾸므로, 그런 인자가 있는 명령 앞에는 `MSYS_NO_PATHCONV=1`을 붙인다.

## 1. ECR 저장소와 IAM 역할 (한 번만)

```bash
. $ST
aws ecr create-repository --repository-name stock-single-was        # 앱
aws ecr create-repository --repository-name stock-single-was-front  # 프론트(Nginx)

# EC2 역할: SSM 접속과 ECR 이미지 받기만
aws iam create-role --role-name stock-single-was-ec2 --assume-role-policy-document \
  '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name stock-single-was-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam attach-role-policy --role-name stock-single-was-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
aws iam create-instance-profile --instance-profile-name stock-single-was-ec2
aws iam add-role-to-instance-profile --instance-profile-name stock-single-was-ec2 --role-name stock-single-was-ec2
```

## 2. 보안 그룹 (한 번만)

EC2는 내 IP의 80만, RDS는 EC2 보안 그룹의 3306만 받는다.

```bash
. $ST
EC2_SG=$(aws ec2 create-security-group --group-name stock-single-was-ec2 --description "stock-single-was app EC2" \
  --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $EC2_SG \
  --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=$MY_IP/32,Description=my-ip}]"
RDS_SG=$(aws ec2 create-security-group --group-name stock-single-was-rds --description "stock-single-was RDS" \
  --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $RDS_SG \
  --ip-permissions "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=$EC2_SG,Description=app-ec2}]"
printf 'EC2_SG=%s\nRDS_SG=%s\n' $EC2_SG $RDS_SG >> $ST
```

## 3. RDS 만들기 (한 번만, 약 10분)

비밀번호는 무작위로 만들어 상태 파일과 EC2의 `.env`에만 둔다.

```bash
. $ST
DB_PASSWORD=$(openssl rand -base64 36 | tr -dc 'A-Za-z0-9' | head -c 24)
printf 'DB_PASSWORD=%s\n' "$DB_PASSWORD" >> $ST
aws rds create-db-instance --db-instance-identifier stock-single-was \
  --engine mysql --engine-version 8.4.13 --db-instance-class db.m7g.large \
  --allocated-storage 20 --storage-type gp3 \
  --master-username ssw --master-user-password "$DB_PASSWORD" --db-name stock_single_was \
  --vpc-security-group-ids $RDS_SG --availability-zone ap-northeast-2a \
  --no-publicly-accessible --no-multi-az --backup-retention-period 0 --no-deletion-protection
```

- 데모·측정용이라 자동 백업(0일)과 삭제 보호를 끈다. 데이터는 seed로 다시 만든다.
- 파라미터 그룹은 기본값이다. 확인값: 격리 수준 `REPEATABLE-READ`, 버퍼 풀 5.25GB, 시간대 UTC.

## 4. EC2 만들기 (한 번만)

부팅 스크립트가 Docker와 Compose 플러그인을 설치한다. RDS와 같은 AZ의 서브넷에 둔다.

```bash
. $ST
AMI=$(MSYS_NO_PATHCONV=1 aws ssm get-parameter \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)
cat > user-data.sh <<'EOF'
#!/bin/bash
dnf install -y docker
systemctl enable --now docker
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
EOF
INSTANCE=$(aws ec2 run-instances --image-id $AMI --instance-type m7i.large --subnet-id $SUBNET \
  --iam-instance-profile Name=stock-single-was-ec2 --security-group-ids $EC2_SG \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3}' \
  --metadata-options HttpTokens=required --user-data "file://$(cygpath -m user-data.sh)" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=stock-single-was}]' \
  --query 'Instances[0].InstanceId' --output text)
printf 'INSTANCE=%s\n' $INSTANCE >> $ST

aws ec2 wait instance-status-ok --instance-ids $INSTANCE
aws rds wait db-instance-available --db-instance-identifier stock-single-was
DB_HOST=$(aws rds describe-db-instances --db-instance-identifier stock-single-was \
  --query 'DBInstances[0].Endpoint.Address' --output text)
PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $INSTANCE \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
printf 'DB_HOST=%s\nPUBLIC_IP=%s\n' $DB_HOST $PUBLIC_IP >> $ST
```

- `m7i.large`는 x86이다. 로컬에서 만든 이미지가 x86용이라 ARM 인스턴스에서는 실행되지 않는다.
- 키 페어 없음. 접속은 SSM으로만 한다. SSM 에이전트가 `Online`이 되기까지 1~2분 걸린다.

## 5. SSM 실행 도우미

EC2에서 실행할 스크립트를 base64로 묶어 보내고 끝날 때까지 기다린다. 따옴표가 깨지지 않는다.

```bash
ssm_run() {
  local b64 id status
  b64=$(base64 -w0 "$1")
  id=$(MSYS_NO_PATHCONV=1 aws ssm send-command --instance-ids $INSTANCE --document-name AWS-RunShellScript \
    --timeout-seconds 600 --parameters "{\"commands\":[\"echo $b64 | base64 -d | bash\"],\"executionTimeout\":[\"600\"]}" \
    --query Command.CommandId --output text) || return 1
  while :; do
    status=$(aws ssm get-command-invocation --command-id $id --instance-id $INSTANCE --query Status --output text 2>/dev/null)
    case "$status" in Pending|InProgress|Delayed|"") sleep 3;; *) break;; esac
  done
  aws ssm get-command-invocation --command-id $id --instance-id $INSTANCE \
    --query '[StandardOutputContent,StandardErrorContent]' --output text
  echo "[ssm status: $status]"; [ "$status" = Success ]
}
```

SSM 명령 내용은 계정의 명령 기록에 남는다. 7단계의 `.env`(DB 비밀번호 포함)도 그렇다. 계정 밖으로 나가지는 않지만,
여러 사람이 쓰는 계정이면 Parameter Store(SecureString)로 바꾼다.

## 6. 이미지 빌드와 ECR 업로드 (배포할 때마다)

```bash
. $ST
SHA=$(git rev-parse --short HEAD)
aws ecr get-login-password | docker login --username AWS --password-stdin $REGISTRY
docker build -t $REGISTRY/stock-single-was:$SHA .
docker build -t $REGISTRY/stock-single-was-front:$SHA frontend
docker push $REGISTRY/stock-single-was:$SHA
docker push $REGISTRY/stock-single-was-front:$SHA
printf 'SHA=%s\n' $SHA >> $ST
```

## 7. EC2에서 실행 (처음)

배포 파일은 로컬 저장소에서 보낸다(아직 `main`에 없는 버전도 배포할 수 있다).

```bash
. $ST
{ echo 'set -e; mkdir -p /opt/stock; cd /opt/stock'
  echo "echo $(base64 -w0 deploy/compose.yml) | base64 -d > compose.yml"
  echo "echo $(base64 -w0 scripts/seed/demo-seed.sql) | base64 -d > demo-seed.sql"; } > put-files.sh
ssm_run put-files.sh

ENVB64=$(printf 'APP_IMAGE=%s/stock-single-was:%s\nFRONT_IMAGE=%s/stock-single-was-front:%s\nHTTP_PORT=80\nDB_HOST=%s\nDB_USER=ssw\nDB_PASSWORD=%s\n' \
  $REGISTRY $SHA $REGISTRY $SHA $DB_HOST "$DB_PASSWORD" | base64 -w0)
cat > up.sh <<EOF
set -e; cd /opt/stock; umask 077
echo $ENVB64 | base64 -d > .env
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin $REGISTRY >/dev/null
docker compose up -d
for i in \$(seq 1 60); do [ "\$(docker inspect -f '{{.State.Health.Status}}' \$(docker compose ps -q app))" = healthy ] && break; sleep 3; done
docker compose ps
EOF
ssm_run up.sh
```

앱이 처음 뜨면서 Flyway가 RDS에 테이블을 만든다.

## 8. 데모 데이터 넣기 (처음, 또는 초기화할 때)

seed는 주문·체결·계좌를 모두 지우고 다시 만든다. 앱은 기동할 때 있는 종목의 엔진만 만들기 때문에 seed 후 앱을 다시 띄운다.
EC2에는 mysql 클라이언트가 없으므로 mysql 이미지를 클라이언트로 쓴다.

```bash
cat > seed.sh <<'EOF'
set -e; cd /opt/stock
set -a; . ./.env; set +a
docker compose stop app
docker run --rm -i -e MYSQL_PWD="$DB_PASSWORD" mysql:8.4 mysql --default-character-set=utf8mb4 \
  -h "$DB_HOST" -u "$DB_USER" stock_single_was < demo-seed.sql
docker compose start app
for i in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q app))" = healthy ] && break; sleep 3; done
echo "engines ready: $(docker compose logs app 2>&1 | grep -c ' ready:')"
EOF
ssm_run seed.sh
```

`engines ready: 10`(종목 엔진 10개)이면 된다.

## 9. 확인

```bash
. $ST
curl -s -o /dev/null -w '%{http_code}\n' http://$PUBLIC_IP/                  # 200
curl -s -o /dev/null -w '%{http_code}\n' http://$PUBLIC_IP/api/symbols       # 200
curl -s -o /dev/null -w '%{http_code}\n' http://$PUBLIC_IP/actuator/health   # 404 (외부 비공개)
```

브라우저에서 `http://$PUBLIC_IP/`를 창 두 개로 열어 계좌 1, 계좌 2로 둔다. 계좌 2에서 SYM01 1,000원 1주 매도,
계좌 1에서 같은 가격 매수 → 두 창의 실시간 알림에 체결이 바로 뜬다.

2026-11-27 결과:

| 확인 | 결과 |
|---|---|
| EC2·RDS AZ | 둘 다 `ap-northeast-2a`, RDS 퍼블릭 접근 꺼짐 |
| `/`, `/notifications.js`, `/api/symbols` | 200 |
| `/actuator/health`, `/nginx.conf` | 404 |
| 내 PC에서 SSE 구독 후 매도·매수 | 매수 요청 후 약 140ms에 `ORDER_FILLED` 수신 |
| 없는 계좌 SSE 구독 | 404 |
| 앱 JVM 최대 힙 | 4.0 GiB |
| 등록하지 않은 IP(EC2 자신의 공인 IP)에서 접속 | 5초 시간 초과로 차단 |

## 10. 다시 배포할 때

6단계로 새 태그를 올린 뒤, 바뀐 쪽의 이미지만 교체한다.

```bash
. $ST
# 화면만 바꿨을 때: 체결 엔진은 재시작하지 않는다
printf "cd /opt/stock && sed -i 's|^FRONT_IMAGE=.*|FRONT_IMAGE=%s/stock-single-was-front:%s|' .env && docker compose up -d front\n" $REGISTRY $SHA > redeploy.sh
# 앱을 바꿨을 때: 앱이 다시 뜨면서 DB의 대기 주문을 호가창에 복원한다(ADR 0008)
# printf "cd /opt/stock && sed -i 's|^APP_IMAGE=.*|APP_IMAGE=%s/stock-single-was:%s|' .env && docker compose up -d app\n" $REGISTRY $SHA > redeploy.sh
ssm_run redeploy.sh
```

## 비용, 중지, 정리

요금은 ADR 0012 참고(앱 + RDS 켜 두면 시간당 약 $0.36).

```bash
. $ST
# 쉴 때: 중지. EC2는 다시 켜면 퍼블릭 IP가 바뀐다. RDS는 중지해도 7일 뒤 자동으로 다시 켜진다
aws ec2 stop-instances --instance-ids $INSTANCE
aws rds stop-db-instance --db-instance-identifier stock-single-was

# 다시 켤 때
aws rds start-db-instance --db-instance-identifier stock-single-was
aws ec2 start-instances --instance-ids $INSTANCE
aws rds wait db-instance-available --db-instance-identifier stock-single-was
aws ec2 wait instance-status-ok --instance-ids $INSTANCE
aws ec2 describe-instances --instance-ids $INSTANCE --query 'Reservations[0].Instances[0].PublicIpAddress' --output text

# 내 IP가 바뀌었을 때: 80 규칙을 새 IP로 바꾼다
NEW_IP=$(curl -s https://checkip.amazonaws.com)
aws ec2 revoke-security-group-ingress --group-id $EC2_SG --protocol tcp --port 80 --cidr $MY_IP/32
aws ec2 authorize-security-group-ingress --group-id $EC2_SG --protocol tcp --port 80 --cidr $NEW_IP/32
sed -i "s/^MY_IP=.*/MY_IP=$NEW_IP/" $ST
```

컨테이너는 `restart: unless-stopped`라 EC2를 다시 켜면 자동으로 뜬다. 앱은 DB의 대기 주문을 호가창에 복원한다.

다 쓰면 지운다. RDS 스냅샷을 남기려면 `--skip-final-snapshot` 대신 `--final-db-snapshot-identifier <이름>`을 준다.

```bash
. $ST
aws rds delete-db-instance --db-instance-identifier stock-single-was --skip-final-snapshot
aws ec2 terminate-instances --instance-ids $INSTANCE
aws rds wait db-instance-deleted --db-instance-identifier stock-single-was
aws ec2 wait instance-terminated --instance-ids $INSTANCE
aws ec2 delete-security-group --group-id $RDS_SG
aws ec2 delete-security-group --group-id $EC2_SG
```
