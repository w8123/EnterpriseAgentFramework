#!/usr/bin/env bash
# reachai：ReachAI 服务器运维命令（install.sh 安装时放到安装目录，并链接到 /usr/local/bin/reachai）。
#
#   reachai status                 各服务是否正常
#   reachai doctor                 体检：Docker、端口、磁盘、内存、各服务、数据库，给出怎么修
#   reachai logs [服务] [-f]        看日志；服务：model knowledge capability runtime control web mysql milvus
#   reachai restart [服务]          重启全部或某个服务（改了 reachai.env 后用它生效）
#   reachai stop / start           停止 / 启动
#   reachai backup                 备份数据库、密钥和配置到 backups/
#   reachai restore <备份文件>      从备份恢复（会先停服务；加 --yes 不再确认）
#   reachai upgrade [版本]          备份 → 下载新镜像 → 重启 → 体检
#   reachai version                当前版本
#   reachai uninstall              卸载：停服务、删容器和镜像、删 reachai 命令，并问你要不要保留数据
#                    --keep-data   保留数据（数据库、密钥、文件、备份），以后重新运行 install.sh 可以接着用
#                    --purge       连数据和安装目录一起删除，什么都不留（删除前可以先导出一份备份）
#                    --yes         不提问（和 --keep-data 或 --purge 一起用）
#
# 安装目录默认是这个脚本所在目录，也可以用 REACHAI_DIR 指定。
set -euo pipefail

resolve_dir() {
  local source="${BASH_SOURCE[0]}"
  while [ -L "$source" ]; do
    local dir; dir="$(cd "$(dirname "$source")" && pwd)"
    source="$(readlink "$source")"
    case "$source" in /*) ;; *) source="$dir/$source" ;; esac
  done
  cd "$(dirname "$source")" && pwd
}

DIR="${REACHAI_DIR:-$(resolve_dir)}"
ENV_FILE="$DIR/reachai.env"
SERVICES=(model knowledge capability runtime control)

say() { printf '%s\n' "$*"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$*"; PROBLEMS=$((PROBLEMS + 1)); }
note() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# 安装到一半失败时也可能没有 reachai.env，这时仍然允许卸载
[ -f "$ENV_FILE" ] || [ "${1:-}" = uninstall ] || fail "在 $DIR 没找到 reachai.env。请在安装目录里运行，或设置 REACHAI_DIR=<安装目录>"

get_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true; }
compose() { (cd "$DIR" && docker compose --env-file reachai.env "$@"); }

service_name() {
  case "${1:-}" in
    "") echo "" ;;
    model|knowledge|capability|runtime|control|web) echo "reachai-$1" ;;
    mysql|milvus|redis|reachai-*) echo "$1" ;;
    *) fail "不认识的服务：$1（可选：model knowledge capability runtime control web mysql milvus）" ;;
  esac
}

uses_bundled_mysql() { case ",$(get_env COMPOSE_PROFILES)," in *,mysql,*) return 0 ;; *) return 1 ;; esac; }

cmd_status() {
  compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
}

cmd_doctor() {
  PROBLEMS=0
  say "ReachAI 体检（$DIR）"
  say ""
  say "运行环境"
  if docker info >/dev/null 2>&1; then ok "Docker 正在运行"; else bad "Docker 没有运行：Linux 用 sudo systemctl start docker，Windows / macOS 打开 Docker Desktop"; fi
  local mem disk
  if [ -r /proc/meminfo ]; then
    mem="$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)"
    if [ "$mem" -lt 300 ]; then bad "可用内存只剩 ${mem}MB：服务可能被系统杀掉。可以关掉 Milvus，或给机器加内存 / 开 swap"
    else ok "可用内存 ${mem}MB"; fi
  fi
  disk="$(df -Pm "$DIR" | awk 'NR==2 {print $4}')"
  if [ "${disk:-0}" -lt 2048 ]; then bad "安装目录所在磁盘只剩 ${disk}MB：请清理磁盘（docker system prune 可清掉旧镜像）"
  else ok "磁盘剩余 $((disk / 1024))GB"; fi

  say ""
  say "服务"
  local service state health
  for service in mysql "${SERVICES[@]/#/reachai-}" reachai-web milvus; do
    if [ "$service" = mysql ] && ! uses_bundled_mysql; then continue; fi
    if [ "$service" = milvus ] && ! case ",$(get_env COMPOSE_PROFILES)," in *,milvus,*) true ;; *) false ;; esac; then continue; fi
    state="$(compose ps -a --format '{{.State}} {{.Health}}' "$service" 2>/dev/null | head -n1)"
    health="${state#* }"; state="${state%% *}"
    if [ -z "$state" ]; then bad "$service 没有创建：运行 reachai start"
    elif [ "$state" != running ]; then bad "$service 没有运行（$state）：运行 reachai logs ${service#reachai-} 看原因，再 reachai restart ${service#reachai-}"
    elif [ "$health" = unhealthy ]; then bad "$service 运行中但健康检查失败：运行 reachai logs ${service#reachai-}"
    elif [ "$health" = starting ]; then note "$service 正在启动，稍等一两分钟再看"
    else ok "$service 正常"; fi
  done
  local oom
  oom="$(compose ps -a -q 2>/dev/null | xargs -r docker inspect --format '{{.Name}} {{.State.OOMKilled}}' 2>/dev/null \
    | awk '$2=="true" {sub("^/", "", $1); print $1}' | tr '\n' ' ')"
  [ -z "$oom" ] || bad "这些服务曾因内存不够被杀掉：$oom 可以在 reachai.env 调小 REACHAI_JAVA_OPTS_*，或给机器加内存"

  say ""
  say "访问"
  local port url
  port="$(get_env REACHAI_PORT)"; port="${port:-8080}"
  url="$(get_env REACHAI_PUBLIC_URL)"
  if curl -fsS -m 5 -o /dev/null "http://127.0.0.1:$port/"; then ok "本机 http://127.0.0.1:$port 可以打开"
  else bad "本机 http://127.0.0.1:$port 打不开：检查 reachai-web 是否运行，端口是否被别的程序占用"; fi
  case "$url" in
    ""|http://localhost*|http://127.0.0.1*) note "REACHAI_PUBLIC_URL 是 ${url:-空}，只有本机能用。给团队用时改成域名，再 reachai restart" ;;
    http://*) note "REACHAI_PUBLIC_URL 是 http 地址（$url），对外使用建议配 HTTPS"; curl -fsS -m 5 -o /dev/null "$url/" && ok "$url 可以打开" || note "从这台机器打不开 $url（域名解析或防火墙），从浏览器再确认一下" ;;
    https://*) curl -fsS -m 8 -o /dev/null "$url/" && ok "$url 可以打开" || bad "打不开 $url：检查域名解析、证书和反向代理是否指向 127.0.0.1:$port" ;;
  esac

  say ""
  say "数据"
  if [ -f "$DIR/data/secrets.properties" ]; then ok "密钥文件 data/secrets.properties 存在（请和数据库一起备份）"
  else note "还没有 data/secrets.properties：首次启动成功后会自动生成"; fi
  local latest
  latest="$(ls -1t "$DIR"/backups/reachai-*.tar.gz 2>/dev/null | head -n1 || true)"
  if [ -z "$latest" ]; then note "还没有备份：运行 reachai backup"
  else ok "最近一次备份：$(basename "$latest")"; fi

  say ""
  if [ "$PROBLEMS" = 0 ]; then printf '\033[32m%s\033[0m\n' "没有发现问题。更详细的检查在管理台「开始使用 → 系统体检」。"
  else printf '\033[31m%s\033[0m\n' "发现 $PROBLEMS 个问题，按上面的提示处理。处理不了时把这段输出和 reachai logs 发给我们。"; return 1; fi
}

cmd_logs() {
  local follow="" service=""
  for arg in "$@"; do
    case "$arg" in -f|--follow) follow=--follow ;; *) service="$(service_name "$arg")" ;; esac
  done
  # shellcheck disable=SC2086
  compose logs --tail 200 $follow $service
}

cmd_backup() {
  local stamp work archive
  stamp="$(date +%Y%m%d-%H%M%S)"
  work="$(mktemp -d)"
  archive="$DIR/backups/reachai-$stamp.tar.gz"
  mkdir -p "$DIR/backups"
  say "备份数据库..."
  if uses_bundled_mysql; then
    compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --default-character-set=utf8mb4 --databases "$MYSQL_DATABASE"' >"$work/reach_ai.sql" \
      || { rm -rf "$work"; fail "数据库备份失败：确认 mysql 服务正在运行（reachai status）"; }
  else
    say "  用的是你自己的 MySQL，请用你平时的方式备份数据库 $(get_env AI_MYSQL_DATABASE)。这里只备份密钥和配置。"
  fi
  cp "$ENV_FILE" "$work/reachai.env"
  [ -f "$DIR/data/secrets.properties" ] && cp "$DIR/data/secrets.properties" "$work/secrets.properties"
  tar -czf "$archive" -C "$work" . -C "$DIR" data/knowledge-artifacts data/skill-artifacts 2>/dev/null \
    || tar -czf "$archive" -C "$work" .
  rm -rf "$work"
  chmod 600 "$archive"
  say "已备份到 $archive"
  say "里面有数据库密码和密钥，请放到安全的地方；建议另外拷一份到别的机器。"
  # 只保留最近 10 份
  ls -1t "$DIR"/backups/reachai-*.tar.gz 2>/dev/null | tail -n +11 | xargs -r rm -f
}

cmd_restore() {
  local archive="${1:-}" work
  [ -f "$archive" ] || fail "用法：reachai restore <备份文件> [--yes]（备份在 $DIR/backups）"
  if [ "${2:-}" != --yes ]; then
    printf '恢复会覆盖当前的数据库和密钥，继续吗？(y/n) '
    local answer; read -r answer </dev/tty || answer=n
    case "$answer" in y|Y|yes) ;; *) say "已取消"; return ;; esac
  fi
  work="$(mktemp -d)"
  tar -xzf "$archive" -C "$work"
  compose stop "${SERVICES[@]/#/reachai-}" reachai-web
  [ -f "$work/secrets.properties" ] && mkdir -p "$DIR/data" && cp "$work/secrets.properties" "$DIR/data/secrets.properties"
  [ -d "$work/data" ] && cp -R "$work/data/." "$DIR/data/"
  if [ -f "$work/reach_ai.sql" ] && uses_bundled_mysql; then
    say "恢复数据库..."
    compose up -d --wait mysql
    compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4' <"$work/reach_ai.sql"
  fi
  rm -rf "$work"
  compose up -d --wait
  say "已恢复。reachai.env 没有覆盖，备份里的那份可以手动对照。"
}

cmd_upgrade() {
  local version="${1:-}"
  if [ -n "$version" ]; then
    sed -i.bak "s|^REACHAI_VERSION=.*|REACHAI_VERSION=$version|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
  fi
  say "1/4 先备份"
  cmd_backup
  say "2/4 下载新镜像"
  compose pull --quiet || fail "下载新镜像失败：检查网络能否访问 $(get_env REACHAI_IMAGE_REGISTRY)，或在 reachai.env 换 REACHAI_IMAGE_REGISTRY。服务没有改动，还在运行。"
  say "3/4 重启服务"
  compose up -d --remove-orphans --wait --wait-timeout 600
  say "4/4 体检"
  cmd_doctor || true
  say ""
  say "升级完成。如果这个版本附带数据库升级脚本（见发布说明），按手册执行；有问题可用 reachai restore 回到升级前。"
}

cmd_version() {
  say "镜像版本：$(get_env REACHAI_VERSION)"
  docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{.Created}}' \
    "$(get_env REACHAI_IMAGE_REGISTRY)/reachai-server:$(get_env REACHAI_VERSION)" 2>/dev/null | sed 's/^/镜像构建：/' || true
}

# ---------- 卸载 ----------

project_name() { local name; name="$(get_env REACHAI_PROJECT_NAME)"; echo "${name:-reachai}"; }
image_registry() { local registry; registry="$(get_env REACHAI_IMAGE_REGISTRY)"; echo "${registry:-ghcr.io/w8123}"; }

# 数据目录可能被 reachai.env 改到安装目录外面，卸载时要一起处理
data_paths() {
  local key value
  for key in REACHAI_DATA_DIR:data REACHAI_MYSQL_DATA_DIR:mysql REACHAI_MILVUS_DATA_DIR:milvus REACHAI_REDIS_DATA_DIR:redis; do
    value="$(get_env "${key%%:*}")"
    value="${value:-./${key#*:}}"
    case "$value" in /*) ;; *) value="$DIR/${value#./}" ;; esac
    [ -e "$value" ] && echo "$value"
  done
  [ -d "$DIR/backups" ] && echo "$DIR/backups"
  return 0
}

# 在源码仓库的 deploy/ 目录里装过时，只删生成的文件，不能把源码删了
is_source_checkout() { [ -e "$DIR/../.git" ] && [ -f "$DIR/docker/Dockerfile" ]; }

safe_to_delete() {
  local path; path="$(cd "$1" 2>/dev/null && pwd -P)" || return 1
  case "$path" in /|/root|/home|/usr|/usr/*|/etc|/etc/*|/var|/opt|/bin|/sbin|/lib*|/boot|/proc|/sys|/dev) return 1 ;; esac
  [ "$path" != "$(cd "$HOME" && pwd -P)" ]
}

# MySQL / Milvus 写出来的文件属于容器里的用户，普通用户删不掉，借一个容器用 root 删
remove_path() {
  local path="$1"
  [ -e "$path" ] || return 0
  rm -rf "$path" 2>/dev/null && return 0
  local image
  image="$(docker image ls -q "$(image_registry)/reachai-server" | head -n1)"
  [ -n "$image" ] || image="$(docker image ls -q "mysql" | head -n1)"
  if [ -n "$image" ] && [ -d "$path" ]; then
    docker run --rm --user 0:0 --entrypoint sh -v "$path:/target" "$image" -c 'rm -rf /target/* /target/.[!.]* /target/..?*' >/dev/null 2>&1 || true
  fi
  rm -rf "$path" 2>/dev/null || { note "删不掉 $path（权限不够），请手动执行：sudo rm -rf $path"; return 1; }
}

remove_command_links() {
  local link
  for link in /usr/local/bin/reachai "$HOME/.local/bin/reachai"; do
    [ -L "$link" ] || continue
    # 只删指向这次安装的链接，别人的同名命令不动
    [ "$(readlink "$link")" = "$DIR/reachai" ] || continue
    rm -f "$link" 2>/dev/null && ok "已删除命令 $link" || note "删不掉 $link，请手动执行：sudo rm -f $link"
  done
}

remove_images() {
  local purge="$1" ids image
  ids="$(docker image ls -q "$(image_registry)/reachai-server"; docker image ls -q "$(image_registry)/reachai-web")"
  if [ -n "$ids" ]; then
    # shellcheck disable=SC2086
    docker image rm -f $(echo "$ids" | sort -u) >/dev/null 2>&1 && ok "已删除 ReachAI 镜像" || note "有 ReachAI 镜像没删掉，可以稍后运行 docker image prune"
  fi
  [ "$purge" = 1 ] || return 0
  # 只删这次安装用到的第三方镜像，且没有别的容器在用（docker image rm 不加 -f 会拒绝删正在用的）
  local profiles=",$(get_env COMPOSE_PROFILES),"
  for image in mysql:8.0 milvusdb/milvus:v2.4.4 redis:7-alpine; do
    case "$image" in
      mysql:*) [[ "$profiles" == *,mysql,* ]] || continue ;;
      milvusdb/*) [[ "$profiles" == *,milvus,* ]] || continue ;;
      redis:*) [[ "$profiles" == *,redis,* ]] || continue ;;
    esac
    docker image inspect "$image" >/dev/null 2>&1 || continue
    docker image rm "$image" >/dev/null 2>&1 && ok "已删除镜像 $image" || note "镜像 $image 还有别的容器在用，保留"
  done
}

stop_and_remove_containers() {
  local project; project="$(project_name)"
  if [ -f "$ENV_FILE" ] && [ -f "$DIR/docker-compose.yml" ]; then
    # 打开所有可选组件，确保 Milvus / Redis 也一起停掉
    (cd "$DIR" && COMPOSE_PROFILES=mysql,milvus,redis docker compose --env-file reachai.env down --remove-orphans --volumes --timeout 30) >/dev/null 2>&1 || true
  fi
  # 兜底：按项目标签清理，配置文件丢了或 compose 失败时也能删干净
  local ids
  ids="$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
  # shellcheck disable=SC2086
  [ -z "$ids" ] || docker rm -f $ids >/dev/null 2>&1 || true
  ids="$(docker network ls -q --filter "label=com.docker.compose.project=$project")"
  # shellcheck disable=SC2086
  [ -z "$ids" ] || docker network rm $ids >/dev/null 2>&1 || true
  ids="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
  # shellcheck disable=SC2086
  [ -z "$ids" ] || docker volume rm $ids >/dev/null 2>&1 || true
  if [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$project")" ]; then
    bad "还有容器没删掉，运行 docker ps -a 查看"
  else
    ok "已停止并删除所有容器和网络"
  fi
}

# 删除前导出一份备份到安装目录外面，免得后悔
export_final_backup() {
  if ! uses_bundled_mysql; then
    note "用的是你自己的 MySQL，数据库不在这里，不受卸载影响；只导出密钥和配置"
  elif ! docker ps -q --filter "label=com.docker.compose.project=$(project_name)" --filter "label=com.docker.compose.service=mysql" | grep -q .; then
    (cd "$DIR" && docker compose --env-file reachai.env up -d --wait mysql) >/dev/null 2>&1 || true
  fi
  local before after target
  before="$(ls -1t "$DIR"/backups/reachai-*.tar.gz 2>/dev/null | head -n1 || true)"
  cmd_backup >/dev/null || { bad "备份失败，已取消卸载，什么都没删"; exit 1; }
  after="$(ls -1t "$DIR"/backups/reachai-*.tar.gz | head -n1)"
  [ "$after" != "$before" ] || { bad "备份失败，已取消卸载，什么都没删"; exit 1; }
  target="$(dirname "$DIR")/$(basename "$after")"
  [ -w "$(dirname "$DIR")" ] || target="$HOME/$(basename "$after")"
  mv "$after" "$target"
  ok "已导出最后一份备份：$target（里面有密码和密钥，不需要了就删掉）"
}

cmd_uninstall() {
  PROBLEMS=0
  local mode="" yes=0 backup=""
  for arg in "$@"; do
    case "$arg" in
      --purge) mode=purge ;;
      --keep-data) mode=keep ;;
      --yes|-y) yes=1 ;;
      --backup) backup=1 ;;
      --no-backup) backup=0 ;;
      *) fail "不认识的选项：$arg（可用：--keep-data --purge --yes）" ;;
    esac
  done
  docker info >/dev/null 2>&1 || fail "Docker 没有运行，没法删除容器。请先启动 Docker（Linux 用 sudo systemctl start docker）"
  if [ "$yes" = 1 ] && [ -z "$mode" ]; then fail "--yes 需要和 --keep-data 或 --purge 一起用，说清楚要不要保留数据"; fi

  say "卸载 ReachAI（$DIR）"
  say ""
  say "会删除："
  say "  - 所有 ReachAI 容器、网络和镜像"
  say "  - reachai 命令"
  if [ -z "$mode" ]; then
    say ""
    say "你的数据（数据库、密钥、上传的文件、备份）在："
    data_paths | sed 's/^/  /'
    printf '\n要保留这些数据吗？保留的话以后重新运行 install.sh 可以接着用。(y=保留 / n=全部删除) [y]: '
    local answer; read -r answer </dev/tty || answer=y
    case "${answer:-y}" in n|N|no|NO|否) mode=purge ;; *) mode=keep ;; esac
  fi
  if [ "$mode" = purge ]; then
    say ""
    say "还会删除（无法恢复）："
    data_paths | sed 's/^/  - /'
    if is_source_checkout; then say "  - $DIR 里生成的 reachai.env 和 reachai（这是源码目录，其余文件保留）"
    else say "  - 整个安装目录 $DIR"; fi
    if [ "$yes" = 0 ]; then
      printf '\n确认全部删除请输入 delete： '
      local confirm; read -r confirm </dev/tty || confirm=""
      [ "$confirm" = delete ] || { say "已取消，什么都没删"; return; }
      if [ -z "$backup" ] && [ -f "$ENV_FILE" ] && uses_bundled_mysql; then
        printf '删除前先导出一份备份到 %s 吗？(y/n) [y]: ' "$(dirname "$DIR")"
        local answer; read -r answer </dev/tty || answer=y
        case "${answer:-y}" in n|N|no|NO|否) backup=0 ;; *) backup=1 ;; esac
      fi
    fi
  fi

  say ""
  [ "$backup" = 1 ] && [ "$mode" = purge ] && [ -f "$ENV_FILE" ] && export_final_backup
  stop_and_remove_containers
  if [ "$mode" = purge ]; then
    local path
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      if safe_to_delete "$path"; then remove_path "$path" && ok "已删除 $path"
      else note "$path 看起来是系统目录，没有删除，请自己确认后处理"; fi
    done < <(data_paths)
  fi
  remove_images "$([ "$mode" = purge ] && echo 1 || echo 0)"
  remove_command_links
  if [ "$mode" = purge ]; then
    if is_source_checkout; then
      rm -f "$DIR/reachai.env" "$DIR/reachai"
      ok "已删除 $DIR 里生成的文件"
    elif safe_to_delete "$DIR"; then
      remove_path "$DIR" && ok "已删除安装目录 $DIR"
    else
      note "$DIR 看起来是系统目录，没有删除"
    fi
  fi

  say ""
  if [ "$mode" = purge ]; then
    printf '\033[32m%s\033[0m\n' "ReachAI 已经卸载干净。"
  else
    printf '\033[32m%s\033[0m\n' "ReachAI 已经卸载，数据保留在 $DIR。"
    say "以后想接着用：重新运行 install.sh --dir $DIR；想彻底删掉：$DIR/reachai uninstall --purge"
  fi
  say "Docker 本身没有卸载（别的程序可能也在用）。不需要的话按 Docker 官方文档卸载。"
}

case "${1:-help}" in
  status) cmd_status ;;
  doctor) cmd_doctor ;;
  logs) shift; cmd_logs "$@" ;;
  # 重新创建容器，这样改过的 reachai.env 也会生效
  restart) shift; compose up -d --force-recreate --wait $(service_name "${1:-}") ;;
  stop) compose stop ;;
  start) compose up -d --wait ;;
  backup) cmd_backup ;;
  restore) shift; cmd_restore "$@" ;;
  upgrade) shift; cmd_upgrade "$@" ;;
  version) cmd_version ;;
  uninstall) shift; cmd_uninstall "$@" ;;
  help|-h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}" ;;
  *) fail "不认识的命令：$1（reachai help 查看全部命令）" ;;
esac
