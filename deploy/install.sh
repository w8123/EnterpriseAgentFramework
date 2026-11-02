#!/usr/bin/env bash
# ReachAI 一条命令安装（Linux / macOS，基于 Docker）。
#
#   curl -fsSL https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.sh | bash
#   curl -fsSL .../install.sh | bash -s -- --server --url https://reachai.example.com
#
# 两种安装方式，用的是同一批镜像：
#   --local   （默认）装在自己电脑上试用：只监听本机，能访问本机上的业务系统
#   --server  装在服务器上给团队用：对外监听，按服务器内存设置 JVM
#
# 其他选项：
#   --dir <目录>          安装目录（默认：root 用户 /opt/reachai，其他用户 ~/reachai）
#   --port <端口>         访问端口（默认 8080）
#   --url <地址>          浏览器访问 ReachAI 的地址，配了域名就写域名（默认 http://<本机>:<端口>）
#   --with-milvus         同时安装 Milvus（知识库向量检索，多占约 1.5G 内存）
#   --external-db         使用自己的 MySQL（安装时会问地址和账号）
#   --version <版本>      镜像版本（默认 dev）
#   --registry <地址>     镜像仓库（默认 ghcr.io/w8123，国内慢时可换成加速地址）
#   --build               不下载镜像，用当前源码目录现场构建（需要在仓库里运行）
#   --no-pull             不下载镜像，直接用本机已有的镜像（离线安装：先 docker load 导入镜像）
#   --yes                 不提问，全部用默认值
#   --uninstall           卸载（等于 reachai uninstall，可以再带 --keep-data / --purge / --yes / --dir）
#
# 重复执行是安全的：已经装过时保留 reachai.env 和数据，只更新镜像并重启。
# 装好后用 reachai 命令管理：reachai status / doctor / logs / upgrade / backup。

set -euo pipefail

REACHAI_SOURCE_URL="${REACHAI_SOURCE_URL:-https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy}"

say() { printf '%s\n' "$*"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# 通过 curl | bash 运行时标准输入是脚本本身，提问要从终端读
ask() {
  local prompt="$1" default="$2" answer=""
  if [ "$ASSUME_YES" = 1 ] || [ ! -r /dev/tty ]; then printf '%s' "$default"; return; fi
  printf '%s [%s]: ' "$prompt" "$default" >/dev/tty
  read -r answer </dev/tty || true
  printf '%s' "${answer:-$default}"
}

confirm() {
  local answer
  answer="$(ask "$1 (y/n)" "$2")"
  case "$answer" in y|Y|yes|YES|是) return 0 ;; *) return 1 ;; esac
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
  else LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c 32; fi
}

set_env() {
  local key="$1" value="$2" file="$INSTALL_DIR/reachai.env"
  if grep -q "^$key=" "$file"; then
    # 用 awk 替换，值里有 / & 之类的字符也不会出错
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k {print k "=" v; next} {print}' "$file" >"$file.tmp" && mv "$file.tmp" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >>"$file"
  fi
}

get_env() { grep -E "^$1=" "$INSTALL_DIR/reachai.env" 2>/dev/null | tail -n1 | cut -d= -f2- || true; }

parse_args() {
  MODE=local; INSTALL_DIR=""; PORT=8080; PUBLIC_URL=""; WITH_MILVUS=0; EXTERNAL_DB=0
  VERSION="${REACHAI_VERSION:-dev}"; REGISTRY="${REACHAI_IMAGE_REGISTRY:-ghcr.io/w8123}"; BUILD=0; NO_PULL=0; ASSUME_YES=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --local) MODE=local ;;
      --server) MODE=server ;;
      --dir) INSTALL_DIR="$2"; shift ;;
      --port) PORT="$2"; shift ;;
      --url) PUBLIC_URL="${2%/}"; shift ;;
      --with-milvus) WITH_MILVUS=1 ;;
      --external-db) EXTERNAL_DB=1 ;;
      --version) VERSION="$2"; shift ;;
      --registry) REGISTRY="${2%/}"; shift ;;
      --build) BUILD=1 ;;
      --no-pull) NO_PULL=1 ;;
      --yes|-y) ASSUME_YES=1 ;;
      -h|--help) sed -n '2,25p' "$0" 2>/dev/null || say "用法见 deploy/install.sh 开头的说明"; exit 0 ;;
      *) fail "不认识的参数：$1（--help 查看用法）" ;;
    esac
    shift
  done
  if [ -z "$INSTALL_DIR" ]; then
    if [ "$(id -u)" = 0 ]; then INSTALL_DIR=/opt/reachai; else INSTALL_DIR="$HOME/reachai"; fi
  fi
}

check_docker() {
  step "检查 Docker"
  if ! command -v docker >/dev/null 2>&1; then
    case "$(uname -s)" in
      Linux)
        warn "没有找到 Docker。"
        if [ "$(id -u)" = 0 ] && confirm "要现在自动安装 Docker 吗（使用 Docker 官方安装脚本）" y; then
          curl -fsSL https://get.docker.com | sh || fail "Docker 安装失败。国内服务器可参考 https://developer.aliyun.com/mirror/docker-ce 手动安装后重新运行"
          systemctl enable --now docker >/dev/null 2>&1 || true
        else
          fail "请先安装 Docker：https://docs.docker.com/engine/install/ ，然后重新运行这条命令"
        fi
        ;;
      Darwin) fail "请先安装并打开 Docker Desktop：https://www.docker.com/products/docker-desktop/ ，然后重新运行这条命令" ;;
      *) fail "请先安装 Docker，然后重新运行这条命令" ;;
    esac
  fi
  if ! docker info >/dev/null 2>&1; then
    if [ "$(uname -s)" = Linux ] && [ "$(id -u)" = 0 ]; then systemctl start docker >/dev/null 2>&1 || true; sleep 2; fi
    docker info >/dev/null 2>&1 || fail "Docker 没有在运行，或当前用户没有权限。请启动 Docker（Linux 可用 sudo systemctl start docker），或用 sudo 运行这条命令"
  fi
  docker compose version >/dev/null 2>&1 || fail "缺少 docker compose（v2）。请升级 Docker，或安装 docker-compose-plugin"
  local compose_version
  compose_version="$(docker compose version --short 2>/dev/null | sed 's/^v//')"
  case "$compose_version" in
    1.*|2.[0-9].*|2.1[0-9].*|2.2[0-3].*) fail "docker compose 版本太旧（$compose_version），需要 2.24 或更新，请升级 Docker" ;;
  esac
  say "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)，compose $compose_version"
}

memory_mb() {
  if [ -r /proc/meminfo ]; then awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo
  elif command -v sysctl >/dev/null 2>&1; then echo $(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
  else echo 4096; fi
}

# 来源：在仓库里运行时直接用旁边的文件，否则从 GitHub 下载
SCRIPT_DIR=""
fetch() {
  local name="$1" target="$2"
  if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/$name" ]; then cp "$SCRIPT_DIR/$name" "$target"; return; fi
  curl -fsSL "$REACHAI_SOURCE_URL/$name" -o "$target.download" || fail "下载 $name 失败，请检查网络能否访问 $REACHAI_SOURCE_URL"
  mv "$target.download" "$target"
}

prepare_files() {
  step "准备安装目录 $INSTALL_DIR"
  mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/backups"
  fetch docker-compose.yml "$INSTALL_DIR/docker-compose.yml"
  fetch milvus-embedEtcd.yaml "$INSTALL_DIR/milvus-embedEtcd.yaml"
  fetch reachai-ctl.sh "$INSTALL_DIR/reachai"
  chmod +x "$INSTALL_DIR/reachai"
  if [ -f "$INSTALL_DIR/reachai.env" ]; then
    FIRST_INSTALL=0
    say "已经装过，保留现有配置 reachai.env 和数据"
  else
    FIRST_INSTALL=1
    fetch reachai.env.example "$INSTALL_DIR/reachai.env"
    chmod 600 "$INSTALL_DIR/reachai.env"
  fi
}

default_host() {
  if [ "$MODE" = local ]; then echo localhost; return; fi
  local ip=""
  ip="$(hostname -I 2>/dev/null | awk '{print $1}')" || true
  echo "${ip:-localhost}"
}

configure() {
  [ "$FIRST_INSTALL" = 1 ] || { configure_existing; return; }
  step "生成配置"
  local admin_password url
  if [ "$MODE" = server ]; then
    PORT="$(ask "访问端口" "$PORT")"
    [ -n "$PUBLIC_URL" ] || PUBLIC_URL="$(ask "浏览器访问 ReachAI 的地址（有域名就填域名）" "http://$(default_host):$PORT")"
  fi
  url="${PUBLIC_URL:-http://$(default_host):$PORT}"
  admin_password="$(random_secret | cut -c1-16)"
  set_env REACHAI_BOOTSTRAP_ADMIN_PASSWORD "$admin_password"
  set_env AI_MYSQL_PASSWORD "$(random_secret)"
  set_env REACHAI_MYSQL_ROOT_PASSWORD "$(random_secret)"
  set_env REACHAI_PUBLIC_URL "$url"
  set_env REACHAI_PORT "$PORT"
  set_env REACHAI_VERSION "$VERSION"
  set_env REACHAI_IMAGE_REGISTRY "$REGISTRY"
  set_env REACHAI_INSTALL_MODE "$MODE"
  if [ "$MODE" = local ]; then
    set_env REACHAI_BIND 127.0.0.1
    # 本机试用时业务系统通常也在本机或内网
    set_env RUNTIME_HTTP_EGRESS_ALLOW_PRIVATE true
  else
    set_env REACHAI_BIND 0.0.0.0
  fi

  local profiles=mysql
  if [ "$EXTERNAL_DB" = 1 ]; then
    profiles=""
    say "使用你自己的 MySQL（库要提前建好，空库即可，表会自动建）："
    set_env AI_MYSQL_HOST "$(ask "  MySQL 地址（同一台机器上的 MySQL 填 host.docker.internal）" "host.docker.internal")"
    set_env AI_MYSQL_PORT "$(ask "  端口" 3306)"
    set_env AI_MYSQL_DATABASE "$(ask "  数据库名" reach_ai)"
    set_env AI_MYSQL_USER "$(ask "  用户名" reach_ai)"
    local db_password
    db_password="$(ask "  密码" "")"
    [ -n "$db_password" ] || fail "需要填写 MySQL 密码"
    set_env AI_MYSQL_PASSWORD "$db_password"
  fi
  if [ "$WITH_MILVUS" = 0 ] && [ "$ASSUME_YES" = 0 ] && [ "$(memory_mb)" -ge 7000 ]; then
    confirm "要同时安装 Milvus 吗？知识库的向量检索需要它（以后也可以再装）" n && WITH_MILVUS=1
  fi
  [ "$WITH_MILVUS" = 1 ] && profiles="${profiles:+$profiles,}milvus"
  set_env COMPOSE_PROFILES "$profiles"
  tune_memory
}

configure_existing() {
  # 重复安装：只更新显式传入的版本和镜像仓库，其余保持不变
  [ -n "${VERSION_FROM_ARGS:-}" ] && set_env REACHAI_VERSION "$VERSION"
  if [ "$WITH_MILVUS" = 1 ]; then
    local profiles; profiles="$(get_env COMPOSE_PROFILES)"
    case ",$profiles," in *,milvus,*) ;; *) set_env COMPOSE_PROFILES "${profiles:+$profiles,}milvus" ;; esac
  fi
  return 0
}

# 按机器内存设置 5 个服务的堆大小。默认值适合 4G 内存的机器。
tune_memory() {
  local mem; mem="$(memory_mb)"
  local model knowledge capability runtime control
  if [ "$mem" -lt 3500 ]; then
    warn "这台机器只有 ${mem}MB 内存，ReachAI 建议至少 4G。会按最省内存的方式启动，可能偏慢。"
    model=256 knowledge=384 capability=256 runtime=448 control=448
  elif [ "$mem" -lt 7000 ]; then
    model=384 knowledge=512 capability=384 runtime=640 control=640
  elif [ "$mem" -lt 14000 ]; then
    model=512 knowledge=768 capability=512 runtime=1024 control=1024
  else
    model=768 knowledge=1024 capability=768 runtime=1536 control=1536
  fi
  local gc="-XX:+UseSerialGC"
  [ "$mem" -ge 7000 ] && gc="-XX:+UseG1GC"
  set_env REACHAI_JAVA_OPTS_MODEL "-Xms96m -Xmx${model}m $gc"
  set_env REACHAI_JAVA_OPTS_KNOWLEDGE "-Xms128m -Xmx${knowledge}m $gc"
  set_env REACHAI_JAVA_OPTS_CAPABILITY "-Xms96m -Xmx${capability}m $gc"
  set_env REACHAI_JAVA_OPTS_RUNTIME "-Xms128m -Xmx${runtime}m $gc"
  set_env REACHAI_JAVA_OPTS_CONTROL "-Xms128m -Xmx${control}m $gc"
  if [ "$WITH_MILVUS" = 1 ] && [ "$mem" -lt 7000 ]; then
    warn "Milvus 需要约 1.5G 内存，这台机器内存偏小，知识库检索可能很慢。"
  fi
  say "机器内存 ${mem}MB，已按这个大小设置各服务内存"
}

compose() { (cd "$INSTALL_DIR" && docker compose --env-file reachai.env "$@"); }

images() {
  local registry version
  registry="$(get_env REACHAI_IMAGE_REGISTRY)"; version="$(get_env REACHAI_VERSION)"
  if [ "$BUILD" = 1 ]; then
    step "用源码构建镜像（第一次需要 10 分钟左右）"
    local repo="${SCRIPT_DIR%/deploy}"
    [ -n "$SCRIPT_DIR" ] && [ -f "$repo/pom.xml" ] || fail "--build 需要在仓库里运行：bash deploy/install.sh --build"
    docker build -f "$repo/deploy/docker/Dockerfile" --target server -t "${registry:-ghcr.io/w8123}/reachai-server:${version:-dev}" "$repo"
    docker build -f "$repo/deploy/docker/Dockerfile" --target web -t "${registry:-ghcr.io/w8123}/reachai-web:${version:-dev}" "$repo"
    return
  fi
  if [ "$NO_PULL" = 1 ]; then
    docker image inspect "${registry:-ghcr.io/w8123}/reachai-server:${version:-dev}" >/dev/null 2>&1 \
      || fail "本机没有镜像 ${registry:-ghcr.io/w8123}/reachai-server:${version:-dev}，去掉 --no-pull 让它自动下载"
    return
  fi
  step "下载镜像（第一次需要几分钟）"
  if ! compose pull --quiet; then
    warn "镜像下载失败。可能原因：网络访问不了 ${registry}，或镜像还没公开。"
    if [ -n "$SCRIPT_DIR" ] && [ -f "${SCRIPT_DIR%/deploy}/pom.xml" ] && confirm "要改用当前源码现场构建镜像吗" y; then
      BUILD=1; images; return
    fi
    fail "可以用 --registry 换一个镜像地址，或在源码目录里运行 bash deploy/install.sh --build"
  fi
}

start() {
  step "启动 ReachAI（第一次启动会自动建表，约 1～2 分钟）"
  compose up -d --remove-orphans --wait --wait-timeout 600 || {
    warn "有服务没有按时就绪，最近的日志："
    compose ps
    compose logs --tail 30 reachai-control reachai-runtime 2>/dev/null || true
    fail "可以运行 $INSTALL_DIR/reachai doctor 查看原因"
  }
}

install_command() {
  local target=""
  if [ -w /usr/local/bin ]; then target=/usr/local/bin/reachai
  elif [ -d "$HOME/.local/bin" ] || mkdir -p "$HOME/.local/bin" 2>/dev/null; then target="$HOME/.local/bin/reachai"; fi
  if [ -n "$target" ]; then
    ln -sf "$INSTALL_DIR/reachai" "$target"
    REACHAI_CMD=reachai
    case ":$PATH:" in *":$(dirname "$target"):"*) ;; *) REACHAI_CMD="$INSTALL_DIR/reachai" ;; esac
  else
    REACHAI_CMD="$INSTALL_DIR/reachai"
  fi
}

summary() {
  local url password
  url="$(get_env REACHAI_PUBLIC_URL)"
  password="$(get_env REACHAI_BOOTSTRAP_ADMIN_PASSWORD)"
  printf '\n\033[32m%s\033[0m\n' "ReachAI 已经装好"
  say "  访问地址：$url"
  say "  管理员：  admin"
  if [ "$FIRST_INSTALL" = 1 ]; then
    say "  初始密码：$password   （也保存在 $INSTALL_DIR/reachai.env，建议登录后修改）"
  else
    say "  密码：    沿用原来的密码"
  fi
  say ""
  say "下一步：登录后打开「开始使用」，按提示接入模型、接入业务系统、发布给员工。"
  say ""
  say "常用命令："
  say "  $REACHAI_CMD status     查看各服务状态"
  say "  $REACHAI_CMD doctor     体检，出问题时先跑它"
  say "  $REACHAI_CMD logs       查看日志"
  say "  $REACHAI_CMD backup     备份数据库和密钥"
  say "  $REACHAI_CMD upgrade    升级到最新版本"
  say "  $REACHAI_CMD uninstall  卸载（会问要不要保留数据）"
  if [ "$MODE" = server ]; then
    say ""
    say "对外使用前：给 ReachAI 配好域名和 HTTPS（Nginx / 宝塔反向代理到 127.0.0.1:$(get_env REACHAI_PORT)），"
    say "并把 reachai.env 里的 REACHAI_PUBLIC_URL 改成 https 地址后运行 $REACHAI_CMD restart。"
  fi
  say "数据都在 $INSTALL_DIR（data/ 是密钥和文件，mysql/ 是数据库），请定期运行 backup。"
}

# 卸载交给安装目录里的 reachai 命令，这里只负责找到安装目录
uninstall() {
  local dir="" args=() prev=""
  for arg in "$@"; do
    if [ "$prev" = --dir ]; then dir="$arg"
    elif [ "$arg" != --uninstall ] && [ "$arg" != --dir ]; then args+=("$arg"); fi
    prev="$arg"
  done
  if [ -z "$dir" ]; then
    for candidate in /opt/reachai "$HOME/reachai"; do [ -f "$candidate/reachai" ] && { dir="$candidate"; break; }; done
  fi
  [ -n "$dir" ] && [ -f "$dir/reachai" ] || fail "没找到 ReachAI 安装目录。装在别的地方时加上 --dir <安装目录>"
  REACHAI_DIR="$dir" exec bash "$dir/reachai" uninstall ${args[@]+"${args[@]}"}
}

main() {
  for arg in "$@"; do [ "$arg" = --uninstall ] && uninstall "$@"; done
  case "${BASH_SOURCE[0]:-}" in
    */*) [ -f "${BASH_SOURCE[0]}" ] && SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" ;;
    install.sh) SCRIPT_DIR="$(pwd)" ;;
  esac
  for arg in "$@"; do [ "$arg" = --version ] && VERSION_FROM_ARGS=1; done
  parse_args "$@"
  say "ReachAI 安装（$([ "$MODE" = server ] && echo 服务器模式 || echo 本机试用模式)）"
  check_docker
  prepare_files
  configure
  images
  start
  install_command
  summary
}

main "$@"
