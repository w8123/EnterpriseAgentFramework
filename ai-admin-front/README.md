# ReachAI Admin Frontend

`ai-admin-front` is the Vue 3 + TypeScript + Element Plus + Vite workspace console for ReachAI. It is a workbench-style admin UI for platform operation, registry management, workflow/runtime governance, knowledge retrieval, model gateway configuration, and public capability onboarding.

## Backend Topology

The frontend is aligned with the current five-service backend topology:

| Frontend path | Owning backend | Default local target |
| --- | --- | --- |
| `/api/**` | `reachai-control-service` as Platform Control public API/BFF | `http://localhost:18603` |
| `/ai/**` | `reachai-knowledge-service` as Knowledge / Retrieval | `http://localhost:18602` with backend context path `/ai` |
| `/model/**` | `reachai-model-service` as Model Gateway | `http://localhost:18601` |
| Runtime health and capability health | Aggregated by `reachai-control-service` | `GET /api/internal-services/health` |

`reachai-runtime-service` and `reachai-capability-service` are backend deployment units, but the admin frontend should use the Control public API for their public surfaces. Frontend does not call reachai-runtime-service:18604 or reachai-capability-service:18605 directly.

The current backend deployment units are:

- `reachai-control-service`: Platform Control public API/BFF, `/api/**`, `/embed/**`, SDK registry compatibility entry.
- `reachai-runtime-service`: Runtime Host for Agent, Workflow, GraphSpec execution, Trace, RunOps, debug, and runtime internal APIs.
- `reachai-capability-service`: Capability Catalog for SDK registration, snapshots, diff/review/apply, scan project catalog, and capability assets.
- `reachai-knowledge-service`: Knowledge / Retrieval for knowledge bases, files, chunks, RAG, vector retrieval, business index, and scanner implementation.
- `reachai-model-service`: Model Gateway for model instances, chat, embedding, rerank, and OpenAI-compatible proxy.

## Local Development

Start the backend services you need first. For the full console, the recommended local order is:

1. `reachai-model-service` on `18601`
2. `reachai-knowledge-service` on `18602` with context path `/ai`
3. `reachai-capability-service` on `18605`
4. `reachai-runtime-service` on `18604`
5. `reachai-control-service` on `18603`

Then start the frontend:

```bash
cd ai-admin-front
npm install
npm run dev
```

Vite dev server runs on http://localhost:5200.

For production builds:

```bash
npm run build
```

The generated files are written to `dist/`.

## API Clients

The frontend keeps separate API clients in `src/api/request.ts`:

| Client | Base URL | Purpose |
| --- | --- | --- |
| `textRequest` | `/ai` | Knowledge bases, file import, retrieval test, RAG, scanner-backed knowledge utilities, and business index APIs. |
| `controlRequest` | site root | Platform Control public API/BFF paths such as `/api/agents`, `/api/workflows`, `/api/tools`, `/api/scan-projects`, and `/api/internal-services/health`. |
| `modelRequest` | `/model` | Model Gateway paths such as `/model/providers`, `/model/instances`, and `/model/chat`. |

The Vite proxy in `vite.config.ts` maps those paths to the local backend ports listed above. If a route is not implemented, it should be implemented in the owning service or removed from the UI path; the frontend should not depend on a hidden legacy backend fallback.

## Main Product Areas

| Area | Typical routes | Backend surface |
| --- | --- | --- |
| Overview | `/dashboard` | Aggregated health and quick metrics from Control, Knowledge, and Model. |
| Registry center | `/registry/**`, `/scan-project/**` | Control public APIs backed by Capability Catalog ownership. |
| Workflow and Runtime | `/workflows/**`, `/agents/**`, runtime debug and RunOps pages | Control public APIs backed by Runtime Host ownership. |
| Knowledge retrieval | `/knowledge/**`, `/retrieval`, `/biz-index/**` | Knowledge / Retrieval service through `/ai/**`. |
| Model management | `/model`, `/model/playground` | Model Gateway through `/model/**`. |
| Governance and open protocol | MCP, A2A, Tool ACL, embed operations | Control public APIs. |

## Production Reverse Proxy

A production gateway or Nginx layer should route by path:

```nginx
location /ai/ {
    proxy_pass http://localhost:18602;
    proxy_read_timeout 300s;
}

location /api/ {
    proxy_pass http://localhost:18603;
    proxy_read_timeout 300s;
}

location /model/ {
    proxy_pass http://localhost:18601;
    proxy_read_timeout 300s;
    proxy_buffering off;
}
```

Long-running import, scanner, chat stream, and model stream paths should keep a longer read timeout.

## Verification

From the repository root, useful checks are:

```bash
node scripts/check-backend-boundary-naming.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-physical-service-smoke.mjs
```

Run the smoke script only after all five backend services are already running locally.
