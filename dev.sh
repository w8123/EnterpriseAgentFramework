#!/usr/bin/env bash
# ReachAI 本地开发一条命令启动（Linux / macOS / Windows Git Bash）。
#
#   ./dev.sh up [--build] [--no-front]   启动 MySQL（Docker）、5 个服务和管理端
#   ./dev.sh down [--all]                停止服务；--all 连 Docker 里的 MySQL 一起停
#   ./dev.sh status                      查看各服务状态
#   ./dev.sh logs <model|knowledge|capability|runtime|control|front>
#
# 第一次运行会从 reachai.env.example 生成 reachai.env，并随机生成数据库密码。
# 空库自动建表、密钥自动生成，都由服务启动时自己完成，这个脚本只负责按顺序拉起进程。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$ROOT/reachai.env"
STATE="$ROOT/.reachai"
LOGS="$STATE/logs"
PIDS="$STATE/run"
SERVICES=(model knowledge capability runtime control)

port_of() {
  case "$1" in
    model) echo 18601 ;; knowledge) echo 18602 ;; control) echo 18603 ;;
    runtime) echo 18604 ;; capability) echo 18605 ;; front) echo 5200 ;;
  esac
}

health_url() {
  if [ "$1" = knowledge ]; then echo "http://127.0.0.1:18602/ai/actuator/health"
  else echo "http://127.0.0.1:$(port_of "$1")/actuator/health"; fi
}

say() { printf '%s\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

random_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
  else LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 32; fi
}

set_config_value() {
  local key="$1" value="$2"
  if grep -q "^$key=" "$CONFIG"; then
    sed -i.bak "s|^$key=.*|$key=$value|" "$CONFIG" && rm -f "$CONFIG.bak"
  else
    printf '%s=%s\n' "$key" "$value" >>"$CONFIG"
  fi
}

ensure_config() {
  if [ ! -f "$CONFIG" ]; then
    cp "$ROOT/reachai.env.example" "$CONFIG"
    say "已生成配置文件 reachai.env"
  fi
  # shellcheck disable=SC1090
  set -a; . "$CONFIG"; set +a
  if [ -z "${AI_MYSQL_PASSWORD:-}" ] && uses_bundled_mysql; then
    set_config_value AI_MYSQL_PASSWORD "$(random_secret)"
    set_config_value REACHAI_MYSQL_ROOT_PASSWORD "$(random_secret)"
    say "已为本机 MySQL 生成数据库密码（写在 reachai.env）"
    set -a; . "$CONFIG"; set +a
  fi
  [ -n "${AI_MYSQL_PASSWORD:-}" ] || fail "请在 reachai.env 里填写 AI_MYSQL_PASSWORD（你自己的 MySQL 的密码）"
  export REACHAI_HOME="${REACHAI_HOME:-$STATE}" REACHAI_CONFIG_FILE="$CONFIG"
}

uses_bundled_mysql() {
  case "${AI_MYSQL_HOST:-localhost}" in localhost|127.0.0.1) return 0 ;; *) return 1 ;; esac
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

compose() { docker compose --env-file "$CONFIG" -f "$ROOT/deploy/docker-compose.infra.yml" "$@"; }

ensure_mysql() {
  local port="${AI_MYSQL_PORT:-3306}"
  if ! uses_bundled_mysql; then
    say "使用你配置的 MySQL：${AI_MYSQL_HOST}:${port}"
    return
  fi
  if port_open "$port" && ! compose ps --status running mysql 2>/dev/null | grep -q mysql; then
    say "本机 ${port} 端口已有 MySQL，直接使用（数据库 ${AI_MYSQL_DATABASE:-reach_ai} 需已存在）"
    return
  fi
  command -v docker >/dev/null 2>&1 || fail "没有找到 Docker。请安装 Docker，或在 reachai.env 里填写你自己的 MySQL 地址和密码"
  say "启动 MySQL（Docker）..."
  compose up -d mysql >/dev/null
  local waited=0
  until compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot "-p${REACHAI_MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; do
    waited=$((waited + 2)); [ "$waited" -le 120 ] || fail "MySQL 两分钟内没有就绪，请查看：docker compose -f deploy/docker-compose.infra.yml logs mysql"
    sleep 2
  done
  say "MySQL 已就绪"
}

jar_of() { echo "$ROOT/reachai-$1-service/target/reachai-$1-service-1.0.0-SNAPSHOT.jar"; }

ensure_build() {
  local missing=0
  for service in "${SERVICES[@]}"; do [ -f "$(jar_of "$service")" ] || missing=1; done
  if [ "$missing" = 1 ] || [ "${BUILD:-0}" = 1 ]; then
    command -v mvn >/dev/null 2>&1 || fail "没有找到 Maven（mvn），无法编译。请安装 JDK 17 和 Maven"
    say "编译后端（第一次需要几分钟）..."
    (cd "$ROOT" && mvn -q -B -DskipTests install) || fail "编译失败，请查看上面的 Maven 输出"
  fi
}

running() { [ -f "$PIDS/$1.pid" ] && kill -0 "$(cat "$PIDS/$1.pid")" 2>/dev/null; }

start_service() {
  local service="$1"
  if running "$service"; then say "  $service 已在运行"; return; fi
  if port_open "$(port_of "$service")"; then fail "端口 $(port_of "$service") 已被占用，$service 无法启动。请先停掉占用它的程序"; fi
  # shellcheck disable=SC2086
  nohup java ${REACHAI_JAVA_OPTS:--Xmx512m} -jar "$(jar_of "$service")" >>"$LOGS/$service.log" 2>&1 </dev/null &
  echo $! >"$PIDS/$service.pid"
}

wait_healthy() {
  local service="$1" waited=0
  until curl -fsS -m 2 "$(health_url "$service")" >/dev/null 2>&1; do
    if ! running "$service"; then
      say "$service 启动失败，日志最后几行："; tail -n 30 "$LOGS/$service.log" >&2; exit 1
    fi
    waited=$((waited + 2)); [ "$waited" -le 240 ] || fail "$service 4 分钟内没有就绪，请查看 .reachai/logs/$service.log"
    sleep 2
  done
  say "  $service 已就绪（端口 $(port_of "$service")）"
}

start_front() {
  [ "${NO_FRONT:-0}" = 1 ] && return
  command -v npm >/dev/null 2>&1 || { say "没有找到 Node.js / npm，跳过管理端。安装 Node.js 20+ 后重新运行即可"; return; }
  if running front; then say "  管理端已在运行"; return; fi
  if [ ! -d "$ROOT/ai-admin-front/node_modules" ]; then
    say "安装管理端依赖（第一次需要一两分钟）..."
    (cd "$ROOT/ai-admin-front" && npm ci --no-audit --no-fund >"$LOGS/front-install.log" 2>&1) \
      || fail "npm ci 失败，请查看 .reachai/logs/front-install.log"
  fi
  if [ ! -f "$ROOT/ai-admin-front/dist-sdk/embed-chat/reachai-chat-embed.umd.js" ] || [ ! -f "$ROOT/ai-admin-front/dist-sdk/embed-chat/style.css" ]; then
    say "构建嵌入对话 SDK..."
    (cd "$ROOT/ai-admin-front" && npm run build:sdk >"$LOGS/front-sdk-build.log" 2>&1) \
      || fail "嵌入对话 SDK 构建失败，请查看 .reachai/logs/front-sdk-build.log"
  fi
  # 直接运行 vite，记下的就是它自己的进程号，down 时能停干净。
  (cd "$ROOT/ai-admin-front" || exit 1
   nohup node node_modules/vite/bin/vite.js --host 127.0.0.1 >>"$LOGS/front.log" 2>&1 </dev/null &
   echo $! >"$PIDS/front.pid")
  local waited=0
  until port_open "$(port_of front)"; do
    running front || { say "管理端启动失败，日志最后几行："; tail -n 30 "$LOGS/front.log" >&2; exit 1; }
    waited=$((waited + 1)); [ "$waited" -le 60 ] || fail "管理端一分钟内没有就绪，请查看 .reachai/logs/front.log"
    sleep 1
  done
  say "  管理端已就绪（端口 $(port_of front)）"
}

up() {
  command -v java >/dev/null 2>&1 || fail "没有找到 Java。请安装 JDK 17"
  mkdir -p "$LOGS" "$PIDS"
  ensure_config
  ensure_mysql
  ensure_build
  say "启动服务..."
  for service in "${SERVICES[@]}"; do start_service "$service"; done
  for service in "${SERVICES[@]}"; do wait_healthy "$service"; done
  start_front
  say ""
  say "ReachAI 已启动：http://localhost:5200  账号 admin，密码见 reachai.env 的 REACHAI_BOOTSTRAP_ADMIN_PASSWORD（默认 admin123）"
  say "日志在 .reachai/logs，停止用 ./dev.sh down"
}

down() {
  for service in front "${SERVICES[@]}"; do
    if running "$service"; then kill "$(cat "$PIDS/$service.pid")" 2>/dev/null || true; say "已停止 $service"; fi
    rm -f "$PIDS/$service.pid"
  done
  if [ "${ALL:-0}" = 1 ] && [ -f "$CONFIG" ]; then compose stop mysql >/dev/null 2>&1 || true; say "已停止 MySQL"; fi
}

status() {
  for service in "${SERVICES[@]}" front; do
    if running "$service"; then
      if [ "$service" = front ] || curl -fsS -m 2 "$(health_url "$service")" >/dev/null 2>&1; then state="运行中"; else state="启动中或异常"; fi
    else state="未运行"; fi
    printf '  %-11s %-6s %s\n' "$service" "$(port_of "$service")" "$state"
  done
}

command="${1:-up}"; shift || true
for argument in "$@"; do
  case "$argument" in
    --build) BUILD=1 ;; --no-front) NO_FRONT=1 ;; --all) ALL=1 ;;
  esac
done
case "$command" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) tail -n 200 -f "$LOGS/${1:?用法：./dev.sh logs <服务名>}.log" ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
