#!/usr/bin/env bash
# EC2 에서 실행되는 운영 작업 스크립트 (server-ops 워크플로우가 ssh 로 stdin 에 흘려 넣는다).
# 환경변수: TASK (status|https), DOMAIN, EMAIL, HOST_IP
# 공개 저장소의 로그에 남으므로 비밀 값과 내부 설정 전문은 출력하지 않는다.
set -euo pipefail

: "${TASK:?TASK is required}"
DOMAIN="${DOMAIN:-}"
EMAIL="${EMAIL:-}"
HOST_IP="${HOST_IP:-}"

section() { echo; echo "== $1"; }

# nginx 설정에서 구조만 요약해서 보여준다(주소·포트·경로 규칙. 헤더 값이나 토큰은 제외).
nginx_summary() {
  sudo nginx -T 2>/dev/null | grep -E '^\s*(# configuration file|server\s*\{|listen|server_name|root|location|proxy_pass|return|ssl_certificate|add_header|include|client_max_body_size|proxy_read_timeout|proxy_buffering)\b' \
    | sed -E 's/^\s+//' | grep -viE 'password|secret|token|key=' || true
}

# 80 번 포트를 받는 server 블록 전문. proxy 헤더(X-Forwarded-*)가 제대로 있는지 확인하는 용도.
nginx_site80() {
  sudo nginx -T 2>/dev/null | awk '
    /^[ \t]*server[ \t]*\{/ { inblk=1; depth=0; buf="" }
    inblk {
      buf = buf $0 "\n"
      n = gsub(/\{/, "{"); m = gsub(/\}/, "}"); depth += n - m
      if (depth == 0) { if (buf ~ /listen[ \t]+80/) printf "%s", buf; inblk=0 }
    }' | sed -E 's/^\s+/  /' | grep -viE 'password|secret|token' || true
}
status() {
  section "os"
  (. /etc/os-release && echo "$PRETTY_NAME") || true
  uname -m
  section "services"
  for s in nginx ant-backend ant-prediction; do
    printf '%s: %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null || echo not-found)"
  done
  section "listening ports (tcp)"
  ss -ltnH | awk '{print $4}' | sed -E 's/.*:([0-9]+)$/\1/' | sort -un | tr '\n' ' '; echo
  section "app.env variable names"
  if [ -f /opt/antlab/ant-backend/app.env ]; then
    sudo sed -E 's/^\s*#.*//; /^\s*$/d; s/=.*//' /opt/antlab/ant-backend/app.env | sort | tr '\n' ' '; echo
    echo "profiles: $(sudo grep -E '^SPRING_PROFILES_ACTIVE=' /opt/antlab/ant-backend/app.env | cut -d= -f2 || true)"
  fi
  section "backend config files"
  ls /opt/antlab/ant-backend/config 2>/dev/null | tr '\n' ' '; echo
  section "nginx structure"
  nginx_summary
  section "site :80 (full block)"
  nginx_site80
  section "certbot"
  (command -v certbot && certbot --version 2>&1) || echo "not installed"
  sudo ls /etc/letsencrypt/live 2>/dev/null || true
  section "disk / memory"
  df -h / | tail -1
  free -m | awk 'NR==2{print "mem used/total MB: " $3 "/" $2}'
}

# 배포 직후 점검. 공개 로그이므로 로그 내용은 출력하지 않고 개수와 결과만 보여준다.
check() {
  section "backend"
  printf 'service: %s\n' "$(systemctl is-active ant-backend)"
  printf 'health: %s\n' "$(curl -s -m 5 http://127.0.0.1:8080/api/health || echo down)"
  since="30 min ago"
  printf 'ERROR lines (last 30 min): %s\n' "$(sudo journalctl -u ant-backend --since "$since" --no-pager 2>/dev/null | grep -c ' ERROR ' || true)"
  echo "account key migration:"
  sudo journalctl -u ant-backend --since "$since" --no-pager 2>/dev/null | grep -oE '계좌 키 암호화 마이그레이션: 변환 [0-9]+건, 실패 [0-9]+건' | tail -1 || echo "  (none: nothing to migrate, or already migrated)"
  echo "crypto key present: $(sudo grep -c '^ANT_CRYPTO_KEY=' /opt/antlab/ant-backend/app.env || true)"
  echo "app.env TZ / profile / hardened:"
  sudo grep -E '^(TZ|SPRING_PROFILES_ACTIVE|ANT_HARDENED)' /opt/antlab/ant-backend/app.env | cut -c1-80 || true
  echo "ERROR messages (count, digits/emails masked):"
  sudo journalctl -u ant-backend --since "$since" --no-pager 2>/dev/null | grep ' ERROR ' | sed -E 's/^.* ERROR //; s/\x1b\[[0-9;]*m//g; s/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/EMAIL/g; s/[0-9]+/N/g' | cut -c1-140 | sort | uniq -c | sort -rn | head -6
  section "prediction"
  printf 'service: %s  health: %s\n' "$(systemctl is-active ant-prediction)" "$(curl -s -m 5 http://127.0.0.1:8000/health || echo down)"
  section "nginx"
  printf 'service: %s\n' "$(systemctl is-active nginx)"
  sudo nginx -t 2>&1 | tail -1
  section "certificate"
  sudo ls /etc/letsencrypt/live 2>/dev/null | tr '\n' ' '; echo
  sudo systemctl list-timers certbot-renew.timer --no-pager 2>/dev/null | head -3
}
install_certbot() {
  if command -v certbot >/dev/null 2>&1; then return 0; fi
  echo ">> installing certbot (python venv)"
  sudo dnf install -y python3 python3-pip >/dev/null 2>&1 || true
  sudo python3 -m venv /opt/certbot
  sudo /opt/certbot/bin/pip install --quiet --upgrade pip
  sudo /opt/certbot/bin/pip install --quiet certbot
  sudo ln -sf /opt/certbot/bin/certbot /usr/local/bin/certbot
  certbot --version
}

# 80/443 용 nginx 설정을 만든다. 기존 80 번 기본 서버(IP 접속)는 건드리지 않는다.
write_nginx_conf() {
  local domain="$1" conf="/etc/nginx/conf.d/ant-https.conf"
  sudo tee "$conf" >/dev/null <<EOF
# 이 파일은 server-ops(https) 작업이 만든다. 직접 고치지 말고 스크립트를 고쳐서 다시 실행한다.
limit_req_zone \$binary_remote_addr zone=ant_auth:10m rate=10r/m;

# 도메인으로 들어온 HTTP 는 HTTPS 로 보낸다. 인증서 갱신용 확인 경로만 예외.
server {
  listen 80;
  server_name ${domain};
  location /.well-known/acme-challenge/ { root /opt/antlab/ant-frontend; }
  location / { return 301 https://\$host\$request_uri; }
}

server {
  listen 443 ssl;
  http2 on;
  server_name ${domain};

  ssl_certificate     /etc/letsencrypt/live/${domain}/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/${domain}/privkey.pem;
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_prefer_server_ciphers off;
  ssl_session_cache shared:ANT_SSL:10m;
  ssl_session_timeout 1d;
  ssl_session_tickets off;

  root /opt/antlab/ant-frontend;
  index index.html;
  client_max_body_size 10m;
  server_tokens off;

  # 로그인·가입·인증번호처럼 무차별 대입 대상이 되는 경로는 IP 당 분당 10건으로 제한한다(순간 20건까지 허용).
  location ~ ^/api/(auth/(login|signup|refresh|step-up)|email/) {
    limit_req zone=ant_auth burst=20 nodelay;
    limit_req_status 429;
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header Connection '';
    proxy_read_timeout 60s;
  }

  location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header Connection '';
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
  }

  # 정적 화면. 보안 헤더는 여기서 붙인다(API 응답의 헤더는 백엔드가 붙인다).
  location / {
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "geolocation=(), microphone=(), camera=(), payment=()" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; manifest-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" always;
    try_files \$uri \$uri/ /index.html;
  }
}
EOF
}

install_renew_timer() {
  sudo tee /etc/systemd/system/certbot-renew.service >/dev/null <<'EOF'
[Unit]
Description=Renew Let's Encrypt certificates
[Service]
Type=oneshot
ExecStart=/usr/local/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
EOF
  sudo tee /etc/systemd/system/certbot-renew.timer >/dev/null <<'EOF'
[Unit]
Description=Twice-daily certificate renewal check
[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=1800
Persistent=true
[Install]
WantedBy=timers.target
EOF
  sudo systemctl daemon-reload
  sudo systemctl enable --now certbot-renew.timer >/dev/null
  echo ">> renewal timer enabled"
}

https() {
  if [ -z "$DOMAIN" ]; then
    [ -n "$HOST_IP" ] || { echo "!! DOMAIN or HOST_IP is required"; exit 2; }
    DOMAIN="$(echo "$HOST_IP" | tr '.' '-').sslip.io"
  fi
  [[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "!! invalid domain"; exit 2; }
  [ -z "$EMAIL" ] || [[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$ ]] || { echo "!! invalid email"; exit 2; }
  echo ">> domain: $DOMAIN"

  resolved="$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)"
  mine="$(curl -fsS -m 5 https://checkip.amazonaws.com | tr -d '[:space:]')"
  echo ">> dns -> ${resolved:-unresolved}, this server -> $mine"
  [ "$resolved" = "$mine" ] || { echo "!! the domain does not point to this server yet"; exit 3; }

  install_certbot
  sudo nginx -t

  email_args=(--register-unsafely-without-email)
  [ -n "$EMAIL" ] && email_args=(-m "$EMAIL")
  sudo certbot certonly --webroot -w /opt/antlab/ant-frontend -d "$DOMAIN" --non-interactive --agree-tos "${email_args[@]}"

  conf=/etc/nginx/conf.d/ant-https.conf
  [ -f "$conf" ] && sudo cp -a "$conf" "$conf.bak"
  write_nginx_conf "$DOMAIN"
  if ! sudo nginx -t; then
    echo "!! nginx test failed, rolling back"
    if [ -f "$conf.bak" ]; then sudo mv -f "$conf.bak" "$conf"; else sudo rm -f "$conf"; fi
    sudo nginx -t && sudo systemctl reload nginx
    exit 4
  fi
  sudo systemctl reload nginx
  install_renew_timer

  echo ">> verifying"
  curl -sS -m 10 -o /dev/null -w 'https status: %{http_code}\n' "https://${DOMAIN}/" || echo "!! https check failed"
  curl -sS -m 10 -o /dev/null -w 'http  status: %{http_code} -> %{redirect_url}\n' "http://${DOMAIN}/" || true
  curl -sS -m 10 -o /dev/null -w 'ip http status (unchanged): %{http_code}\n' "http://127.0.0.1/" || true
  curl -sSI -m 10 "https://${DOMAIN}/" | grep -iE '^(strict-transport|x-frame|content-security|x-content)' | cut -c1-90 || true
}
case "$TASK" in
  status) status ;;
  check) check ;;
  https) https ;;
  *) echo "unknown task: $TASK"; exit 2 ;;
esac
