# ReachAI 一键体验镜像：从源码构建 5 个后端服务和管理台前端。
# 构建上下文是仓库根目录（见 deploy/quickstart/docker-compose.yml）。

FROM maven:3.9-eclipse-temurin-17 AS backend
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
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
 && mkdir -p /out \
 && for s in model knowledge capability runtime control; do \
      cp reachai-$s-service/target/reachai-$s-service-1.0.0-SNAPSHOT.jar /out/reachai-$s-service.jar; \
    done

FROM node:22-bookworm-slim AS frontend
WORKDIR /src/ai-admin-front
COPY ai-admin-front/package.json ai-admin-front/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY ai-admin-front ./
# SDK 包会带上 Control 里的嵌入对话速查文档
COPY reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references /src/reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/references
RUN npm run build

FROM eclipse-temurin:17-jre AS server
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=backend /out/ /app/
COPY deploy/quickstart/reachai/start-service.sh /app/start-service.sh
RUN chmod +x /app/start-service.sh && mkdir -p /app/data
ENTRYPOINT ["/app/start-service.sh"]

FROM nginx:1.27-alpine AS web
COPY --from=frontend /src/ai-admin-front/dist /usr/share/nginx/html
COPY deploy/quickstart/reachai/nginx.conf /etc/nginx/conf.d/default.conf
