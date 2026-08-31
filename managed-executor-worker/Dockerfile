FROM node:20.12.2-bookworm-slim AS build

WORKDIR /build
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci --ignore-scripts
COPY src ./src
RUN npm run build

FROM node:20.12.2-bookworm-slim AS runtime

ARG CODEX_VERSION=0.144.3
LABEL org.opencontainers.image.title="ReachAI Managed Executor Worker" \
      org.opencontainers.image.description="Isolated Codex app-server adapter and evidence collector" \
      ai.reach.executor.codex-version="${CODEX_VERSION}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && npm install --global --ignore-scripts "@openai/codex@${CODEX_VERSION}" \
    && npm cache clean --force \
    && groupadd --gid 65532 reachai \
    && useradd --uid 65532 --gid 65532 --create-home --home-dir /home/reachai reachai

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts \
    && npm cache clean --force
COPY --from=build /build/dist ./dist

ENV NODE_ENV=production \
    HOME=/home/reachai \
    CODEX_HOME=/run/reachai/codex-home

USER 65532:65532
ENTRYPOINT ["node", "/app/dist/src/remote-cli.js"]
