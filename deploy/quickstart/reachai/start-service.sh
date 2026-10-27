#!/bin/sh
# 用法：start-service.sh <model|knowledge|capability|runtime|control>
# JVM 内存可用 JAVA_OPTS 覆盖。
set -e
svc="$1"
jar="/app/reachai-${svc}-service.jar"
[ -f "$jar" ] || { echo "unknown service: $svc" >&2; exit 2; }
exec java ${JAVA_OPTS:--Xms128m -Xmx512m -XX:+UseSerialGC} -Duser.timezone=Asia/Shanghai -jar "$jar"
