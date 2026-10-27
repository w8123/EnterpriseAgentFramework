#!/bin/sh
# 首次启动时等待 reachai-init 签发接入令牌（写在共享卷里），注册成功后凭据保存在 /app/data。
set -e
mkdir -p /app/data /app/logs
if [ ! -s /app/data/reachai-credentials.properties ]; then
  echo "[yudao] 等待 ReachAI 接入令牌 ..."
  i=0
  while [ ! -s /shared/yudao-enrollment-token ]; do
    i=$((i+1)); [ $i -gt 600 ] && { echo "[yudao] 10 分钟内没有拿到接入令牌，请查看 reachai-init 日志" >&2; exit 1; }
    sleep 1
  done
  export REACHAI_REGISTRY_ENROLLMENT_TOKEN="$(cat /shared/yudao-enrollment-token)"
fi
exec java ${JAVA_OPTS:--Xms128m -Xmx640m -XX:+UseSerialGC} -Duser.timezone=Asia/Shanghai \
  -jar /app/yudao-server.jar \
  --spring.profiles.active=local,quickstart \
  --spring.config.additional-location=optional:file:/app/config/
