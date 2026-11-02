# ReachAI 镜像：从源码构建 5 个后端服务（server）和管理台前端（web）。
# 构建上下文是仓库根目录：
#   docker build -f deploy/docker/Dockerfile --target server -t reachai-server .
#   docker build -f deploy/docker/Dockerfile --target web    -t reachai-web .
# GitHub Actions（.github/workflows/images.yml）用它发布 ghcr.io/w8123/reachai-server 和 reachai-web。

# jar 和前端静态文件与 CPU 架构无关，在构建机本身的架构上编译，多架构镜像不用模拟编译
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /src
COPY pom.xml ./
COPY ai-common ai-common
COPY ai-runtime-contract ai-runtime-contract
COPY reachai-capability-sdk reachai-capability-sdk
COPY reachai-spring-boot2-starter reachai-spring-boot2-starter
COPY reachai-model-service reachai-model-service
COPY reachai-knowledge-service reachai-knowledge-service
COPY reachai-capability-service reachai-capability-service
COPY reachai-runtime-service reachai-runtime-service
COPY reachai-control-service reachai-control-service
# 空库首次启动自动建表用的 SQL，由 ai-common 打进 jar
COPY sql/initV2.sql sql/initV2.sql
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
 && mkdir -p /out \
 && for s in model knowledge capability runtime control; do \
      cp reachai-$s-service/target/reachai-$s-service-1.0.0-SNAPSHOT.jar /out/reachai-$s-service.jar; \
    done

FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS frontend
WORKDIR /src/ai-admin-front
COPY ai-admin-front/package.json ai-admin-front/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY ai-admin-front ./
# SDK 包会带上 Control 里的嵌入对话速查文档
COPY reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references /src/reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references
RUN npm run build

FROM eclipse-temurin:17-jre AS server
ARG REACHAI_VERSION=dev
LABEL org.opencontainers.image.title="ReachAI server" \
      org.opencontainers.image.source="https://github.com/w8123/EnterpriseAgentFramework-dev" \
      org.opencontainers.image.version="${REACHAI_VERSION}"
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=backend /out/ /app/
COPY deploy/docker/start-service.sh /app/start-service.sh
# 5 个服务共用 /app/data：首次启动生成的密钥 secrets.properties、知识库原件、Skill 产物都在这里
ENV REACHAI_HOME=/app/data REACHAI_VERSION=${REACHAI_VERSION}
RUN chmod +x /app/start-service.sh && mkdir -p /app/data
VOLUME ["/app/data"]
ENTRYPOINT ["/app/start-service.sh"]

FROM nginx:1.27-alpine AS web
ARG REACHAI_VERSION=dev
LABEL org.opencontainers.image.title="ReachAI web" \
      org.opencontainers.image.source="https://github.com/w8123/EnterpriseAgentFramework-dev" \
      org.opencontainers.image.version="${REACHAI_VERSION}"
COPY --from=frontend /src/ai-admin-front/dist /usr/share/nginx/html
COPY deploy/docker/nginx.conf /etc/nginx/templates/default.conf.template
# nginx 官方镜像启动时把模板里的 ${REACHAI_CONTROL_UPSTREAM} 换成实际地址
ENV REACHAI_CONTROL_UPSTREAM=reachai-control:18603 NGINX_ENVSUBST_FILTER=^REACHAI_
