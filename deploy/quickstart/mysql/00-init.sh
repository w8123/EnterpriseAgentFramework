#!/bin/bash
# MySQL 首次启动时执行（数据卷为空时）：建 reach_ai 和 yudao 两个库及各自账号。
# 由官方入口脚本 source 执行，沿用它的 set -e；不要 set -u（会破坏入口脚本）。
# 显式指定 utf8mb4：镜像里的 mysql 客户端默认不是 utf8mb4，中文种子会乱码。

echo "[quickstart] 初始化 reach_ai"
docker_process_sql --default-character-set=utf8mb4 < /docker-entrypoint-initdb.d/reachai/initV2.sql

echo "[quickstart] 初始化 yudao"
docker_process_sql --default-character-set=utf8mb4 <<SQL
CREATE DATABASE IF NOT EXISTS yudao DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'reach_ai'@'%' IDENTIFIED BY '${REACHAI_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON reach_ai.* TO 'reach_ai'@'%';
CREATE USER IF NOT EXISTS 'yudao'@'%' IDENTIFIED BY '${YUDAO_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON yudao.* TO 'yudao'@'%';
FLUSH PRIVILEGES;
SQL
for f in /docker-entrypoint-initdb.d/yudao/*.sql; do
  echo "[quickstart] yudao <- $(basename "$f")"
  docker_process_sql --default-character-set=utf8mb4 --database=yudao < "$f"
done
echo "[quickstart] 数据库初始化完成"
