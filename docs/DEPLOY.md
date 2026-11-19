# 첫 배포 절차 (EC2 한 대, 수동)

ADR 0010의 구성이다. EC2 한 대에서 앱 컨테이너와 MySQL 컨테이너를 `deploy/compose.yml`로 실행하고, HTTP(80)는 내 IP에서만 연다.
처음에는 손으로 배포하고, 성공한 절차를 나중에 GitHub Actions로 옮긴다.

리전은 서울(`ap-northeast-2`) 기준이다.

## 0. 준비물

- 로컬: Docker Desktop, AWS CLI v2. `aws sts get-caller-identity`가 내 계정을 출력해야 한다.
  출력되지 않으면 `aws configure`(액세스 키) 또는 `aws configure sso`로 먼저 로그인한다.
- 이 저장소가 `main`에 머지된 상태. EC2에서 `deploy/compose.yml`과 seed 스크립트를 GitHub에서 내려받는다.

## 1. ECR 저장소 만들기 (한 번만)

```bash
aws ecr create-repository --repository-name stock-single-was --region ap-northeast-2
```

## 2. EC2용 IAM 역할 만들기 (한 번만)

콘솔 IAM → 역할 → 역할 생성

- 신뢰할 수 있는 엔터티: AWS 서비스 → EC2
- 권한 정책: `AmazonSSMManagedInstanceCore`(SSM 접속), `AmazonEC2ContainerRegistryReadOnly`(ECR에서 이미지 받기)
- 이름: `stock-single-was-ec2`

## 3. EC2 만들기

콘솔 EC2 → 인스턴스 시작

| 항목 | 값 |
|---|---|
| AMI | Amazon Linux 2023 (x86_64) |
| 인스턴스 유형 | `t3.small` (앱 + MySQL에 2GB 필요). ARM인 `t4g`는 쓰지 않는다. 로컬에서 만든 이미지가 x86용이다 |
| 키 페어 | 없음(SSM으로 접속) |
| 보안 그룹 인바운드 | HTTP 80, 소스 **내 IP**. SSH(22)는 열지 않는다 |
| 스토리지 | 20GB gp3 |
| 고급 세부 정보 → IAM 인스턴스 프로파일 | `stock-single-was-ec2` |

## 4. EC2 접속과 Docker 설치 (한 번만)

콘솔 EC2 → 인스턴스 선택 → 연결 → **Session Manager** → 연결. 접속 사용자는 `ssm-user`이므로 `sudo`를 붙인다.

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -sSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
sudo docker compose version
```

## 5. 이미지 빌드와 ECR 업로드 (로컬, 배포할 때마다)

Git Bash 기준이다.

```bash
SHA=$(git rev-parse --short HEAD)
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.ap-northeast-2.amazonaws.com

aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin $REGISTRY
docker build -t $REGISTRY/stock-single-was:$SHA .
docker push $REGISTRY/stock-single-was:$SHA
echo $REGISTRY/stock-single-was:$SHA   # 6단계의 APP_IMAGE 값
```

## 6. EC2에서 실행 (처음)

```bash
sudo mkdir -p /opt/stock && cd /opt/stock
sudo curl -sSLO https://raw.githubusercontent.com/david3478/stock-single-was/main/deploy/compose.yml
sudo curl -sSLO https://raw.githubusercontent.com/david3478/stock-single-was/main/scripts/seed/demo-seed.sql
sudo vi .env   # deploy/.env.example을 보고 APP_IMAGE, DB_USER, DB_PASSWORD, MYSQL_ROOT_PASSWORD를 채운다
sudo chmod 600 .env

REGISTRY=$(sudo grep APP_IMAGE .env | cut -d= -f2 | cut -d/ -f1)
aws ecr get-login-password --region ap-northeast-2 | sudo docker login --username AWS --password-stdin $REGISTRY

sudo docker compose up -d
sudo docker compose logs app | grep -E "Successfully applied|Started"   # 테이블 생성과 기동 확인
```

## 7. 데모 데이터 넣기 (처음, 또는 초기화할 때)

seed는 주문·체결·계좌를 모두 지우고 다시 만든다. 앱은 기동할 때 있는 종목의 엔진만 만들기 때문에, seed 후에는 앱을 재시작한다.

```bash
cd /opt/stock
sudo docker compose stop app
sudo sh -c '. ./.env && docker compose exec -T mysql mysql --default-character-set=utf8mb4 \
  -u"$DB_USER" -p"$DB_PASSWORD" stock_single_was < demo-seed.sql'
sudo docker compose start app
sudo docker compose logs app | grep "ready"   # symbol SYM01 ready ... 10개
```

## 8. 확인

브라우저에서 `http://<EC2 퍼블릭 IP>/`

1. 계좌 1, SYM01에서 1,000원 5주 **매도** → 내 주문이 `대기`, 호가창에 매도 1,000원 5주
2. 계좌를 2로 바꾸고 1,100원 5주 **매수** → `체결 완료`, 계좌 2 현금은 5,000원만 줄어듦(차액 환불)
3. 휴대폰 데이터 등 다른 IP에서는 접속되지 않아야 한다

## 9. 다시 배포할 때

로컬에서 5단계를 다시 하고, EC2에서 `.env`의 `APP_IMAGE`만 새 태그로 바꾼다.

```bash
cd /opt/stock
sudo vi .env
sudo docker compose up -d
```

DB 데이터는 `mysql-data` 볼륨에 남는다. 앱은 다시 뜰 때 DB의 대기 주문을 호가창에 복원한다(ADR 0008).

## 비용과 정리

- 쓰지 않을 때는 인스턴스를 **중지**한다. 중지 후 다시 시작하면 퍼블릭 IP가 바뀐다.
- 내 IP가 바뀌면(와이파이 변경 등) 보안 그룹 인바운드 규칙을 다시 내 IP로 고친다.
- 다 쓰면 인스턴스를 **종료**하고 ECR 이미지를 지운다. 종료하면 DB 데이터도 사라진다.
