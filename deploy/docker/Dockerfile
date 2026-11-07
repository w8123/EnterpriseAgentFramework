# ReachAI 镜像：从源码构建 5 个后端服务（server）和管理台前端（web）。
# 构建上下文是仓库根目录：
#   docker build -f deploy/docker/Dockerfile --target server -t reachai-server .
#   docker build -f deploy/docker/Dockerfile --target web    -t reachai-web .
# GitHub Actions（.github/workflows/images.yml）用它发布 ghcr.io/w8123/reachai-server 和 reachai-web。

# C# SDK 作为业务系统的 NuGet 下载制品随 Control 发布。
FROM --platform=$BUILDPLATFORM mcr.microsoft.com/dotnet/sdk:10.0.403 AS dotnet-sdk
WORKDIR /src/reachai-dotnet
COPY reachai-dotnet/global.json ./global.json
COPY reachai-dotnet/ReachAI.AspNetCore ReachAI.AspNetCore
RUN dotnet pack ReachAI.AspNetCore/ReachAI.AspNetCore.csproj -c Release -o /out \
    -p:Deterministic=true -p:DeterministicTimestamp=2026-01-01T00:00:00Z \
    -p:SuppressImplicitGitSourceLink=true -p:EnableSourceControlManagerQueries=false \
    -p:IncludeSourceRevisionInInformationalVersion=false -p:DebugType=none -p:DebugSymbols=false \
    -p:PathMap=/src/reachai-dotnet/ReachAI.AspNetCore=/_/src

FROM node:22-bookworm-slim AS dotnet-sdk-package
WORKDIR /package
COPY scripts/canonicalize-nuget.mjs ./canonicalize-nuget.mjs
COPY --from=dotnet-sdk /out/ReachAI.AspNetCore.1.0.0.nupkg ./original.nupkg
RUN node canonicalize-nuget.mjs original.nupkg ReachAI.AspNetCore.1.0.0.nupkg

FROM --platform=$BUILDPLATFORM python:3.12-slim AS python-sdk-package
WORKDIR /src
COPY reachai-python/pyproject.toml reachai-python/pyproject.toml
COPY reachai-python/src reachai-python/src
COPY scripts/pack-python-sdk-artifacts.py scripts/pack-python-sdk-artifacts.py
RUN python -m pip install 'setuptools==80.9.0' wheel \
 && python scripts/pack-python-sdk-artifacts.py

FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS node-sdk-package
WORKDIR /src
COPY reachai-node/package.json reachai-node/package-lock.json reachai-node/tsconfig.json reachai-node/README.md reachai-node/LICENSE reachai-node/
COPY reachai-node/src reachai-node/src
COPY scripts/pack-node-sdk-artifacts.mjs scripts/pack-node-sdk-artifacts.mjs
RUN node scripts/pack-node-sdk-artifacts.mjs

FROM --platform=$BUILDPLATFORM python:3.12-slim AS go-sdk-package
WORKDIR /src
COPY reachai-go/ reachai-go/
COPY scripts/pack-go-sdk-artifacts.py scripts/pack-go-sdk-artifacts.py
RUN python scripts/pack-go-sdk-artifacts.py

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
COPY --from=dotnet-sdk-package /package/ReachAI.AspNetCore.1.0.0.nupkg reachai-control-service/src/main/resources/ai-assist/artifacts/dotnet-sdk/ReachAI.AspNetCore.1.0.0.nupkg
COPY --from=python-sdk-package /src/reachai-control-service/src/main/resources/ai-assist/artifacts/python-sdk/ reachai-control-service/src/main/resources/ai-assist/artifacts/python-sdk/
COPY --from=node-sdk-package /src/reachai-control-service/src/main/resources/ai-assist/artifacts/node-sdk/ reachai-control-service/src/main/resources/ai-assist/artifacts/node-sdk/
COPY --from=go-sdk-package /src/reachai-control-service/src/main/resources/ai-assist/artifacts/go-sdk/ reachai-control-service/src/main/resources/ai-assist/artifacts/go-sdk/
RUN cd reachai-control-service/src/main/resources/ai-assist/artifacts/dotnet-sdk \
 && sha256sum ReachAI.AspNetCore.1.0.0.nupkg | cut -d ' ' -f 1 > ReachAI.AspNetCore.1.0.0.nupkg.sha256
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
