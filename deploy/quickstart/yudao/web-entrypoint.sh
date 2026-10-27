#!/bin/sh
# 把前端包里的占位符换成浏览器能访问到的 ReachAI 地址（默认 http://localhost:18080）。
set -e
url="${REACHAI_PUBLIC_URL:-http://localhost:18080}"
url="${url%/}"
grep -rl "__REACHAI_PUBLIC_URL__" /usr/share/nginx/html 2>/dev/null | while read -r f; do
  sed -i "s#__REACHAI_PUBLIC_URL__#${url}#g" "$f"
done
