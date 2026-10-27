# 芋道 ERP 演示系统：固定上游版本，打上 ReachAI 接入补丁后从源码构建。
# 构建上下文是仓库根目录（见 deploy/quickstart/docker-compose.yml）。

ARG YUDAO_BACKEND_REPO=https://github.com/YunaiV/ruoyi-vue-pro.git
ARG YUDAO_BACKEND_COMMIT=ac022b15a094cf9cf82903d429b9729e72309da5
ARG YUDAO_FRONTEND_REPO=https://github.com/yudaocode/yudao-ui-admin-vue3.git
ARG YUDAO_FRONTEND_COMMIT=050bac1eccfbe7393a1568d614151cc99501b949

# ---------- 芋道后端源码 + ReachAI 接入补丁 ----------
FROM alpine/git:2.47.2 AS source
ARG YUDAO_BACKEND_REPO
ARG YUDAO_BACKEND_COMMIT
WORKDIR /yudao
RUN git init -q . && git remote add origin "$YUDAO_BACKEND_REPO" \
 && git fetch -q --depth 1 origin "$YUDAO_BACKEND_COMMIT" && git checkout -q FETCH_HEAD
COPY docs/guides/yudao/yudao-reachai-integration.patch /tmp/reachai.patch
RUN git apply /tmp/reachai.patch && rm -rf .git

# ---------- 后端：先装 ReachAI Starter，再构建芋道 ----------
FROM maven:3.9-eclipse-temurin-17 AS backend
ENV LANG=C.UTF-8
WORKDIR /reachai
COPY pom.xml ./
COPY ai-common/pom.xml ai-common/pom.xml
COPY ai-runtime-contract/pom.xml ai-runtime-contract/pom.xml
COPY reachai-model-service/pom.xml reachai-model-service/pom.xml
COPY reachai-knowledge-service/pom.xml reachai-knowledge-service/pom.xml
COPY reachai-capability-service/pom.xml reachai-capability-service/pom.xml
COPY reachai-runtime-service/pom.xml reachai-runtime-service/pom.xml
COPY reachai-control-service/pom.xml reachai-control-service/pom.xml
COPY reachai-capability-sdk reachai-capability-sdk
COPY reachai-spring-boot2-starter reachai-spring-boot2-starter
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl reachai-spring-boot2-starter -am install -DskipTests
COPY --from=source /yudao /yudao
WORKDIR /yudao
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests -pl yudao-server -am \
 && cp yudao-server/target/yudao-server.jar /yudao-server.jar

# ---------- 前端：打上对话入口补丁 ----------
FROM node:22-bookworm-slim AS frontend
ARG YUDAO_FRONTEND_REPO
ARG YUDAO_FRONTEND_COMMIT
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/* \
 && npm install -g pnpm@9.15.9
WORKDIR /yudao-ui
RUN git init -q . && git remote add origin "$YUDAO_FRONTEND_REPO" \
 && git fetch -q --depth 1 origin "$YUDAO_FRONTEND_COMMIT" && git checkout -q FETCH_HEAD
COPY docs/guides/yudao/yudao-ui-reachai-chat.patch /tmp/reachai-ui.patch
COPY deploy/quickstart/yudao/env.prod /tmp/env.prod
RUN git apply /tmp/reachai-ui.patch && cp /tmp/env.prod .env.prod
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile && pnpm run build:prod

# ---------- 运行镜像 ----------
FROM eclipse-temurin:17-jre AS server
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=backend /yudao-server.jar /app/yudao-server.jar
COPY deploy/quickstart/yudao/application-quickstart.yaml /app/config/application-quickstart.yaml
COPY deploy/quickstart/yudao/start-yudao.sh /app/start-yudao.sh
RUN chmod +x /app/start-yudao.sh
EXPOSE 48080
ENTRYPOINT ["/app/start-yudao.sh"]

FROM nginx:1.27-alpine AS web
COPY --from=frontend /yudao-ui/dist-prod /usr/share/nginx/html
COPY deploy/quickstart/yudao/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/quickstart/yudao/web-entrypoint.sh /docker-entrypoint.d/40-reachai-base-url.sh
RUN chmod +x /docker-entrypoint.d/40-reachai-base-url.sh

# 演示数据库：ReachAI 全新库 + 芋道上游系统表 + ERP 表结构 + 演示数据
FROM mysql:8.0 AS mysql
COPY --from=source /yudao/sql/mysql/ruoyi-vue-pro.sql /docker-entrypoint-initdb.d/yudao/01-ruoyi-vue-pro.sql
COPY deploy/quickstart/yudao/02-erp-schema.sql /docker-entrypoint-initdb.d/yudao/02-erp-schema.sql
COPY deploy/quickstart/yudao/03-erp-demo-data.sql /docker-entrypoint-initdb.d/yudao/03-erp-demo-data.sql
COPY sql/initV2.sql /docker-entrypoint-initdb.d/reachai/initV2.sql
# 不能有执行权限：入口脚本只 source 不可执行的 .sh，这样才能用 docker_process_sql
COPY --chmod=0644 deploy/quickstart/mysql/00-init.sh /docker-entrypoint-initdb.d/00-init.sh
