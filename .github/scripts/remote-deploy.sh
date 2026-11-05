#!/usr/bin/env bash
# EC2 에서 실행되는 백엔드 배포 스크립트 (GitHub Actions 가 ssh 로 stdin 에 흘려 넣는다).
# 서버 구성: systemd 서비스 ant-backend, /opt/antlab/ant-backend/{app.jar, app.env, config/}.
#  - 런타임 설정은 서버의 app.env 와 config/application*.yml 이 담당한다 (jar 에는 설정이 없다).
#  - 새 jar(app.jar.new)로 교체하고, 헬스 체크에 실패하면 이전 jar 로 되돌린다.
set -euo pipefail

APP=/opt/antlab/ant-backend
SERVICE=ant-backend
HEALTH_URL="http://127.0.0.1:8080/api/health"   # 인증 없이 열려 있는 기동 확인용 엔드포인트

[ -f "$APP/app.jar.new" ] || { echo "!! $APP/app.jar.new not found"; exit 2; }
ls "$APP"/config/application*.yml >/dev/null 2>&1 || { echo "!! $APP/config/application*.yml missing (runtime config)"; exit 2; }
[ -f "$APP/app.env" ] || { echo "!! $APP/app.env missing"; exit 2; }

wait_healthy() {
  local i code
  # 메모리가 빠듯한 서버라 기동이 느리다. 최대 약 5분 대기한다.
  for i in $(seq 1 60); do
    code="$(curl -s -m 20 -o /dev/null -w '%{http_code}' "$HEALTH_URL" || true)"
    if [ "$code" = "200" ]; then echo ">> healthy after ~$((i * 5))s"; return 0; fi
    sleep 5
  done
  return 1
}

echo ">> previous jar: $(ls -l --time-style=long-iso "$APP/app.jar" | awk '{print $6, $7, $5" bytes"}')"
cp -f "$APP/app.jar" "$APP/app.jar.prev"
mv -f "$APP/app.jar.new" "$APP/app.jar"

echo ">> restarting $SERVICE"
sudo systemctl restart "$SERVICE"

if wait_healthy; then exit 0; fi

echo "!! health check failed. recent logs:"
sudo journalctl -u "$SERVICE" -n 60 --no-pager | cut -c1-220 || true

echo "!! rolling back to previous jar"
cp -f "$APP/app.jar.prev" "$APP/app.jar"
sudo systemctl restart "$SERVICE"
if wait_healthy; then echo ">> rollback OK (service is back on the previous jar)"; else echo "!! rollback ALSO unhealthy - manual check needed"; fi
exit 1
