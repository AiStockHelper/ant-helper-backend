#!/usr/bin/env bash
# EC2 에서 실행되는 배포 스크립트 (GitHub Actions 가 ssh 로 stdin 에 흘려 넣는다).
# 필수 환경변수: IMAGE (예: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/ant-helper-backend:<sha>)
# ECR 로그인은 워크플로우가 먼저 해 둔다. ~/ant-helper/incoming/config/ 의 설정 파일을 반영한 뒤
# 컨테이너를 교체하고, 헬스 체크에 실패하면 이전 이미지로 되돌린다.
set -euo pipefail

: "${IMAGE:?IMAGE is required}"

NAME=ant-helper-backend
APP_DIR="$HOME/ant-helper"
INCOMING="$APP_DIR/incoming"
PORT=8080
# /api-docs 는 인증 없이 열려 있는 엔드포인트라 기동 확인용으로 쓴다.
HEALTH_URL="http://localhost:${PORT}/api-docs"
D="sudo docker"

mkdir -p "$APP_DIR/config" "$APP_DIR/log"

if ! command -v docker >/dev/null 2>&1; then
  echo ">> installing docker"
  sudo apt-get update -y
  sudo apt-get install -y docker.io curl
  sudo systemctl enable --now docker
fi

# 예전 방식(docker run backend_container / nohup java -jar)으로 떠 있는 것이 8080 을 잡고 있으면 정리한다.
$D rm -f backend_container >/dev/null 2>&1 || true
pkill -f 'backend-0.0.1-SNAPSHOT.jar|ant-helper-backend-1.0-SNAPSHOT.jar' || true

# 설정 교체 (롤백용으로 이전 설정을 보관)
mkdir -p "$APP_DIR/config.prev"
cp -f "$APP_DIR"/config/*.yml "$APP_DIR/config.prev/" 2>/dev/null || true
install -m 600 "$INCOMING"/config/*.yml "$APP_DIR/config/"
rm -rf "$INCOMING"

run_container() {
  # host 네트워크: 설정의 localhost(MySQL/Redis/예측 서버) 의미가 기존 java -jar 때와 같다.
  $D run -d --name "$NAME" --restart unless-stopped --network host \
    --user "$(id -u):$(id -g)" \
    -v "$APP_DIR/config:/app/config:ro" \
    -v "$APP_DIR/log:/app/log" \
    "$1" >/dev/null
}

wait_healthy() {
  for i in $(seq 1 36); do
    if curl -fsS -o /dev/null "$HEALTH_URL"; then
      echo ">> healthy after $((i * 5))s"
      return 0
    fi
    sleep 5
  done
  return 1
}

PREV_IMAGE="$($D inspect -f '{{.Config.Image}}' "$NAME" 2>/dev/null || true)"

echo ">> pulling $IMAGE"
$D pull "$IMAGE"

$D rm -f "$NAME" >/dev/null 2>&1 || true
echo ">> starting $IMAGE"
run_container "$IMAGE"

if wait_healthy; then
  $D image prune -af --filter "until=168h" >/dev/null 2>&1 || true
  exit 0
fi

echo "!! health check failed ($HEALTH_URL). recent logs:"
$D logs --tail 80 "$NAME" || true

if [ -n "$PREV_IMAGE" ] && [ "$PREV_IMAGE" != "$IMAGE" ]; then
  echo "!! rolling back to $PREV_IMAGE"
  $D rm -f "$NAME" >/dev/null 2>&1 || true
  cp -f "$APP_DIR"/config.prev/*.yml "$APP_DIR/config/" 2>/dev/null || true
  run_container "$PREV_IMAGE"
fi
exit 1
