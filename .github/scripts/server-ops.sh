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

install_certbot() {
  if command -v certbot >/dev/null 2>&1; then return 0; fi
  echo ">> installing certbot"
  if command -v dnf >/dev/null 2>&1 && sudo dnf install -y certbot python3-certbot-nginx >/dev/null 2>&1; then return 0; fi
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -y >/dev/null && sudo apt-get install -y certbot python3-certbot-nginx >/dev/null && return 0
  fi
  echo ">> falling back to a pip-installed certbot"
  sudo dnf install -y python3 python3-pip augeas-libs >/dev/null 2>&1 || true
  sudo python3 -m venv /opt/certbot
  sudo /opt/certbot/bin/pip install --quiet --upgrade pip
  sudo /opt/certbot/bin/pip install --quiet certbot certbot-nginx
  sudo ln -sf /opt/certbot/bin/certbot /usr/local/bin/certbot
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

  # 설정 백업(되돌리기 용)
  backup="/etc/nginx/backup-$(date +%Y%m%d%H%M%S)"
  sudo mkdir -p "$backup" && sudo cp -a /etc/nginx/nginx.conf /etc/nginx/conf.d "$backup"/ 2>/dev/null || true
  echo ">> nginx config backed up"

  email_args=(--register-unsafely-without-email)
  [ -n "$EMAIL" ] && email_args=(-m "$EMAIL")
  sudo certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos "${email_args[@]}" --redirect

  sudo nginx -t
  sudo systemctl reload nginx
  echo ">> verifying"
  curl -fsS -m 10 -o /dev/null -w 'https status: %{http_code}\n' "https://${DOMAIN}/" || echo "!! https check failed"
  curl -sS -m 10 -o /dev/null -w 'http  status: %{http_code} -> %{redirect_url}\n' "http://${DOMAIN}/" || true
}

case "$TASK" in
  status) status ;;
  https) https ;;
  *) echo "unknown task: $TASK"; exit 2 ;;
esac
