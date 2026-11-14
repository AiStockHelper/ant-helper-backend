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
# 보안 필수 설정을 서버에 보장한다. 값은 이 서버 안에서만 만들어지고 로그에는 이름만 남는다.
#  - ANT_HARDENED : 어떤 프로필로 떠 있든 운영 보안 모드(Swagger 비활성, CORS https 한정 등)를 강제한다.
#  - ANT_CRYPTO_KEY : 증권사 앱키·시크릿을 암호화하는 마스터 키(32바이트). 한 번 만들어진 뒤에는 절대 바꾸지 말 것.
#    잃어버리면 저장된 계좌 키를 복호화할 수 없으므로 app.env 를 백업해 둔다.
# 예전 배포가 개행 없이 덧붙여 앞 줄 값에 붙어 버린 경우를 고친다(예: TZ=Asia/SeoulANT_HARDENED=true -> TZ=Asia/Seoul).
if sudo grep -qE '^[A-Za-z_][A-Za-z_0-9]*=.+ANT_HARDENED=true$' "$APP/app.env"; then
  sudo sed -i -E 's/^([A-Za-z_][A-Za-z_0-9]*=.*)ANT_HARDENED=true$/\1/' "$APP/app.env"
  echo ">> app.env: repaired a merged ANT_HARDENED suffix"
fi
ensure_env() {
  local name="$1" value="$2"
  if sudo grep -q "^${name}=" "$APP/app.env"; then return 0; fi
  # 마지막 줄 끝에 개행이 없으면 먼저 넣는다(없으면 앞 줄 값에 붙어 버린다).
  if [ -n "$(sudo tail -c1 "$APP/app.env")" ]; then echo | sudo tee -a "$APP/app.env" >/dev/null; fi
  printf '%s=%s\n' "$name" "$value" | sudo tee -a "$APP/app.env" >/dev/null
  echo ">> app.env: ${name} added"
}
ensure_env ANT_HARDENED true
ensure_env ANT_CRYPTO_KEY "$(openssl rand -base64 32)"
sudo chmod 600 "$APP/app.env" || true

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
