# ReachAI 管理端

`ai-admin-front` 是 Vue 3 + TypeScript + Element Plus + Vite 的 ReachAI 工作台型管理端。它负责平台配置、项目接入、Agent/Workflow 设计、运行治理、知识/模型管理和开放协议操作，不拥有后端业务事实。

## 后端入口

| 浏览器路径 | 本地目标 | 边界 |
| --- | --- | --- |
| `/api/**` | `reachai-control-service:18603` | 平台公共 API/BFF；Runtime/Capability 管理面也经 Control |
| `/ai/**` | `reachai-knowledge-service:18602` | Knowledge context path `/ai` |
| `/model/templates*`、`/model/instances*`、`/model/chat*` | `reachai-model-service:18601` | Model Gateway；Vite 同时避免与 SPA `/model/instances` 路由冲突 |

前端不得直连 `reachai-runtime-service:18604` 或 `reachai-capability-service:18605`。需要新增管理能力时，先确认 owning service 和 public route，再在 Control 保持平台会话/RBAC 边界。

## 本地开发

推荐先按顺序启动 Model、Knowledge、Capability、Runtime、Control，再启动前端：

```powershell
Set-Location ai-admin-front
npm ci
npm run dev
```

Vite 默认端口为 `5200`。生产构建：

```powershell
npm run build
```

构建输出位于 `dist/`。本地开源模式默认登录账号 `admin / admin123`；仅用于开发体验，生产必须在 Control 侧关闭 LOCAL/bootstrap。

## API Client

`src/api/request.ts` 维护三个明确 client：

| Client | Base URL | 用途 |
| --- | --- | --- |
| 默认 `textRequest` | `/ai` | Knowledge、文件、检索、RAG 等直接 Knowledge API |
| `controlRequest` | 当前站点根 | `/api/**` Control 公共 API/BFF |
| `modelRequest` | `/model` | 模型模板、实例和调试接口 |

平台会话只由 Control client 的会话协议处理。Embed Token、MCP Key、A2A Principal、项目签名和 AI Coding Task Token 是不同凭证域，不能复用平台 Bearer。

## 产品区域

| 区域 | 典型路由 | 后端 owner |
| --- | --- | --- |
| Agent / Workflow | `/agent/**`、`/workflows/**` | Runtime，经 Control |
| Agent Skill | `/skills` | Control Skill Center；部署态验收仍按专题证据矩阵 |
| RunOps / Agent Eval | `/runops/**`、`/agent/:id/evals` | Runtime，经 Control |
| 项目与页面工作台 | `/registry/**`、`/scan-project/**` | Control 编排，Capability/Runtime 协作 |
| Knowledge / Business Index | `/knowledge/**`、`/retrieval`、`/biz-index/**` | Knowledge；受保护入口经 Control |
| Model Center | `/model/instances/**`、`/model/playground` | Model |
| API 市场 | `/api-market` | Capability，经 Control |
| MCP / A2A Hub | `/mcp/**`、`/a2a-hub/**` | Control，执行协作 Runtime |
| 身份、ACL、Context | `/settings/**`、`/context/**` | Control |

路由存在不等于目标环境功能已上线。A2A Hub、Agent Skill、API Market、EvalOps 等快速演进能力必须对照专题状态、SQL、运行制品和真实业务流。

## 目录约定

- `src/api/`：按后端公共契约拆分的 API client；
- `src/types/`：前端 DTO/视图模型，不应复制后端 owner 规则；
- `src/views/`：页面 Shell 与产品工作区；复杂页面优先拆 composable 和子组件；
- `src/conversation/`：Agent、Workflow、Embed 共享的对话内核与展示组件；
- `src/sdk/`：Embed Chat / Page Bridge SDK；
- `src/components/common/`：共享工作台组件与菜单模型；
- `src/styles/`：主题和设计 Token，页面避免硬编码主题色。

## 常用验证

```powershell
npm run build
npm run test:workflow
npm run test:conversation
npm run test:model
npm run test:knowledge
npm run test:api-market
npm run test:skill
```

只运行与本次变更相关的测试；涉及共享路由、API client、对话内核或主题时扩大回归范围。浏览器验收至少核对首屏、加载/空/错状态、关键操作、Network 请求目标、控制台错误和暗/亮主题。

仓库级边界检查：

```powershell
node scripts/check-frontend-public-api-routes.mjs
node scripts/check-physical-service-route-contracts.mjs
```

## 进一步阅读

- [ReachAI 文档中心](../docs/README.md)
- [五服务边界与本地启动](../docs/architecture/service-boundaries.md)
- [前端相关计划与验收](../docs/plans/README.md)
- [嵌入式对话与页面动作](../docs/reference/嵌入式对话与页面动作.md)
