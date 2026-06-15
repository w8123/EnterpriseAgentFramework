# ai-admin-front — AI 平台统一管理前端

基于 **Vue 3 + TypeScript + Element Plus + Vite 6** 构建的企业级 **AI 平台管理控制台**，对接单仓内的 **ai-skills-service**（RAG / 知识库）、**ai-agent-service**（Agent / 对话）、**ai-model-service**（模型网关），用于日常运维、配置与联调。

---

## 一、功能概览

| 功能域 | 模块 | 说明 |
|--------|------|------|
| **概览** | Dashboard | 统计卡片（Agent / 知识库 / Tool / Provider 数量）、各服务 Actuator 健康探测、最近 Agent 与知识库快览 |
| **Agent** | Agent 管理 | 列表、筛选（agentKind / 项目 / 状态）、启停、新建/编辑/删除；对接 `/api/agents`（`AgentEntry`） |
| | Agent 编辑 | 身份与策略：name、keySlug、agentKind、visibility、systemPrompt、modelInstanceId、allowedRoles、entryConfig；跳转 Workflow 绑定与画布 |
| | Workflow 管理 | 列表、新建/编辑、版本发布；对接 `/api/workflows` |
| | Workflow Studio | 唯一 GraphSpec / canvas_json 编辑界面（`/workflows/:id/studio`） |
| | Agent 绑定 | Agent → Workflow 路由 CRUD（`/agents/:id/bindings`） |
| | Agent 调试台 | 轻量对话、SSE 流式对话、完整 Agent 执行；会话 ID 展示与清除会话 |
| **知识管理** | 知识库管理 | 知识库 CRUD、详情、Chunk 策略 |
| | 文件入库 | 拖拽上传、切分预览、Pipeline 入库 |
| | 文件详情 | Chunk 列表 |
| | 检索测试 | 向量检索与相似度展示 |
| | 业务索引 | 业务索引 CRUD、数据 upsert、语义搜索 |
| **模型** | Provider 管理 | 列表、连通性测试（`/model/providers`、`/model/providers/test?name=`） |
| | 模型调试台 | 选择 Provider/Model，同步或 SSE 流式对话，Token 用量展示 |
| **Tool** | Tool 管理 | 列表、参数 Schema 展开、测试弹窗（依赖后端 `GET/POST /api/tools` 等接口） |
| **扫描项目** | 扫描项目列表 | 创建项目、填写项目名/域名/磁盘路径/扫描方式，触发扫描或重新扫描 |
| | 扫描项目详情 | 查看项目扫描结果、编辑参数、测试工具、启用/禁用动态 Tool |

> **说明**：Tool 列表与测试需 **ai-agent-service** 暴露 REST（如 `GET /api/tools`、`POST /api/tools/{name}/test`）；未实现时页面为空或请求失败属预期。会话列表、历史记录等高级能力同样依赖后端扩展接口。

---

## 二、技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vue 3 | ^3.5 | 核心框架（Composition API） |
| TypeScript | ~5.6 | 类型安全 |
| Vite | ^6.0 | 构建与开发服务器 |
| Element Plus | ^2.9 | UI 组件库 |
| @element-plus/icons-vue | ^2.3 | 图标 |
| Vue Router | ^4.5 | 路由 |
| Pinia | ^2.3 | 状态管理 |
| Axios | ^1.7 | HTTP（多后端实例） |
| SASS | ^1.83 | 样式 |

流式对话通过 **fetch + ReadableStream** 消费 `text/event-stream`，`src/composables/useSSE.ts` 会解析 SSE 协议（提取 `data:` 行内容），避免把 `event:`、`data:` 等协议原文显示在界面上。

---

## 三、架构与多后端对接

前端按服务拆分 **三个 Axios 实例**（见 `src/api/request.ts`）：

| 实例 | baseURL | 典型路径 | 后端服务（默认端口） |
|------|---------|----------|----------------------|
| `textRequest`（默认导出） | `/ai` | `/knowledge/*`、`/file/*`、`/retrieval/*`、`/biz-index/*` | ai-skills-service `:18602`（context-path `/ai`） |
| `agentRequest` | `''`（站点根） | `/api/agents`、`/api/workflows`、`/api/chat/*`、`/api/tools/*` | ai-agent-service `:18603` |
| `modelRequest` | `/model` | `/model/chat`、`/model/providers` 等 | ai-model-service `:18601` |

开发环境下 **Vite 代理**（`vite.config.ts`）：

- `/ai` → `http://localhost:18602`
- `/api/agents`、`/api/workflows`、`/api/chat`、`/api/tools` → `http://localhost:18603`
- `/model` → `http://localhost:18601`

生产部署时，需在 **Nginx（或网关）** 上为上述路径分别反代到对应服务，并保留较长 `proxy_read_timeout`（大文件入库、流式对话）。

---

## 四、项目结构（节选）

```
ai-admin-front/
├── index.html
├── vite.config.ts
├── tsconfig.json
├── tsconfig.node.json
├── package.json
├── .env.development
└── src/
    ├── main.ts
    ├── App.vue
    ├── api/
    │   ├── request.ts      # textRequest + agentRequest + modelRequest
    │   ├── knowledge.ts
    │   ├── import.ts
    │   ├── bizIndex.ts
    │   ├── agent.ts          # AgentEntry 重导出 + 调试/网关 helper
    │   ├── workflow.ts       # /api/agents、/api/workflows、Studio、绑定
    │   ├── chat.ts
    │   ├── model.ts
    │   └── tool.ts
    ├── composables/
    │   └── useSSE.ts       # SSE 解析与流式状态
    ├── components/       # 知识库相关通用组件
    ├── views/
    │   ├── layout/MainLayout.vue
    │   ├── dashboard/Dashboard.vue
    │   ├── agent/AgentList.vue | AgentEdit.vue | AgentDebug.vue | AgentWorkflowBindings.vue
    │   ├── workflow/WorkflowList.vue | WorkflowStudio.vue | WorkflowVersions.vue
    │   ├── model/ModelProvider.vue | ModelPlayground.vue
    │   ├── tool/ToolList.vue
    │   ├── Knowledge*.vue | FileDetail.vue | RetrievalTest.vue
    │   └── BizIndex*.vue
    ├── router/index.ts
    ├── store/              # knowledge、import、bizIndex、agent、app
    ├── types/              # knowledge、import、bizIndex、agent（含 TRIGGER_MODES）、chat、model、tool
    ├── styles/index.scss
    └── utils/index.ts
```

---

## 五、页面与路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `/` | — | 重定向至 `/dashboard` |
| `/dashboard` | `Dashboard.vue` | 概览与快捷入口 |
| `/agent` | `AgentList.vue` | Agent 列表（`AgentEntry`） |
| `/agent/new/edit` | `AgentEdit.vue` | 新建 Agent |
| `/agent/:id/edit` | `AgentEdit.vue` | 编辑 Agent 身份与策略 |
| `/agent/:id/debug` | `AgentDebug.vue` | 调试台 |
| `/agent/:id/studio` | `AgentStudioCompatibility.vue` | 兼容跳转 → 绑定 Workflow Studio |
| `/agent/:id/versions` | `AgentVersions.vue` | 已迁移提示，引导 Workflow 版本 |
| `/agents/:agentId/bindings` | `AgentWorkflowBindings.vue` | Agent → Workflow 绑定 |
| `/workflows` | `WorkflowList.vue` | Workflow 列表 |
| `/workflows/:workflowId/studio` | `WorkflowStudio.vue` | Workflow Studio（唯一画布） |
| `/workflows/:workflowId/versions` | `WorkflowVersions.vue` | Workflow 版本与发布 |
| `/knowledge` | `KnowledgeList.vue` | 知识库列表 |
| `/knowledge/import` | `KnowledgeImport.vue` | 文件入库 |
| `/knowledge/:code` | `KnowledgeDetail.vue` | 知识库详情 |
| `/knowledge/:code/file/:fileId` | `FileDetail.vue` | 文件 Chunk |
| `/retrieval` | `RetrievalTest.vue` | 检索测试 |
| `/biz-index` | `BizIndexList.vue` | 业务索引 |
| `/biz-index/:code` | `BizIndexDetail.vue` | 索引详情 |
| `/model` | `ModelProvider.vue` | Provider 管理 |
| `/model/playground` | `ModelPlayground.vue` | 模型调试台 |
| `/tool` | `ToolList.vue` | Tool 管理 |
| `/scan-project` | `ScanProjectList.vue` | 扫描项目列表 |
| `/scan-project/:id` | `ScanProjectDetail.vue` | 扫描项目详情 |

侧栏采用分组菜单：概览、Agent、知识管理（子菜单）、模型管理（子菜单）、Tool、扫描项目。

---

## 六、API 接口层摘要

**知识库 / 入库 / 检索 / 业务索引**（`textRequest`，前缀 `/ai`）：与历史文档一致，见 `src/api/knowledge.ts`、`import.ts`、`bizIndex.ts`。

**Agent / Workflow**（`agentRequest` + `workflow.ts`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/agents` | AgentEntry 列表 |
| GET | `/api/agents/{id}` | AgentEntry 详情 |
| POST | `/api/agents` | 创建 Agent |
| PUT | `/api/agents/{id}` | 更新 Agent |
| DELETE | `/api/agents/{id}` | 删除 Agent |
| GET | `/api/workflows` | Workflow 列表 |
| GET | `/api/workflows/{id}` | Workflow 详情 |
| GET/PUT | `/api/workflows/{id}/studio` | Workflow Studio 状态读写 |
| GET/POST | `/api/agents/{id}/workflow-bindings` | 绑定列表 / 新建 |
| PUT/DELETE | `/api/agents/{id}/workflow-bindings/{bindingId}` | 更新 / 删除绑定 |
| POST | `/api/agents/{id}/workflow-bindings/resolve-preview` | 绑定解析预览 |
| POST | `/api/v1/agents/{key}/chat` | Gateway 对话（Entry + binding + Workflow） |
| POST | `/api/agent/execute` | 执行（简要响应） |
| POST | `/api/agent/execute/detailed` | 执行（含步骤等） |

> **已退役**：`/api/agent/definitions` 及 Agent Studio 画布编辑路由不再使用；GraphSpec / canvas_json 统一归属 Workflow API。

**对话**（`fetch`，便于 SSE）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 同步对话 |
| POST | `/api/chat/stream` | SSE 流式 |
| DELETE | `/api/chat/session/{sessionId}` | 清除会话 |

**模型**（`modelRequest` 或 `fetch` 流式）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/model/providers` | Provider 列表 |
| POST | `/model/providers/test?name=` | 连通性测试（推荐） |
| POST | `/model/providers/{name}/test` | 连通性测试（Path，兼容旧调用） |
| POST | `/model/chat` | 同步对话 |
| POST | `/model/chat/stream` | SSE 流式 |

**Tool**（`agentRequest`，需后端实现）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tools` | 已注册工具列表 |
| GET | `/api/tools/{name}` | 工具详情 |
| POST | `/api/tools/{name}/test` | 手动测试执行 |

**扫描项目**（`agentRequest`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/scan-projects` | 扫描项目列表 |
| POST | `/api/scan-projects` | 创建扫描项目 |
| GET | `/api/scan-projects/{id}` | 项目详情 |
| PUT | `/api/scan-projects/{id}` | 更新项目信息 |
| DELETE | `/api/scan-projects/{id}` | 删除项目 |
| POST | `/api/scan-projects/{id}/scan` | 首次扫描 |
| POST | `/api/scan-projects/{id}/rescan` | 重新扫描 |
| GET | `/api/scan-projects/{id}/tools` | 查看该项目下的扫描工具 |

说明：以上扫描项目接口由 `ai-agent-service` 提供，扫描执行阶段会通过 Feign 远程调用 `ai-skills-service` 的 `/ai/scanner/openapi` 与 `/ai/scanner/controller`。

---

## 七、快速启动

### 前置条件

- Node.js >= 18
- 按需启动后端：
  - **仅知识库功能**：`ai-skills-service`（默认 `http://localhost:18602`）
  - **Agent / 调试台**：`ai-agent-service`（`:18603`）
  - **模型调试台 / Provider**：`ai-model-service`（`:18601`），并配置有效 **DashScope API Key**（环境变量 `DASHSCOPE_API_KEY` 或 `application.yml`）

### 安装与开发

```bash
cd ai-admin-front
npm install
npm run dev
```

浏览器访问 [http://localhost:3000](http://localhost:3000)（默认端口 3000）。

### 生产构建

```bash
npm run build
```

产物在 `dist/`。部署静态资源 + 按第三节配置多路径反向代理。

---

## 八、环境变量与代理调整

`.env.development` 示例：

```env
VITE_API_BASE_URL=/ai
```

修改后端地址时，编辑 `vite.config.ts` 中 `server.proxy` 的 `target` 即可。

---

## 九、Nginx 生产部署参考

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /var/www/ai-admin-front/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /ai/ {
        proxy_pass http://localhost:18602;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }

    location /api/ {
        proxy_pass http://localhost:18603;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }

    location /model/ {
        proxy_pass http://localhost:18601;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
        proxy_buffering off;   # 流式响应建议关闭缓冲
    }
}
```

---

## 十、注意事项

1. **知识库上传**：支持 `.doc`、`.docx`、`.pdf`，单文件大小以后端限制为准（常见 50MB）。
2. **模型调用 401**：多为 `ai-model-service` 未配置或配置了失效的 DashScope Key，与前端无关。
3. **流式输出**：依赖后端返回标准 SSE；前端已剥离 `data:` 前缀，仅拼接正文。
4. **Tool 页为空**：请先在后端实现并注册 `/api/tools` 等接口。
5. **扫描项目路径**：`scanPath` 需要对实际执行扫描的 `ai-skills-service` 进程可访问（通常与 `ai-agent-service` 同机部署），不是浏览器本地路径。
6. **Agent / Workflow 模型**：Agent 管理只改 `/api/agents`；画布编辑只走 `/workflows/:id/studio`，不要在 Agent 编辑页维护 GraphSpec。

---

## 十一、产品模型（Agent / Workflow 解耦）

| 对象 | API / 表 | 职责 |
|------|----------|------|
| **Agent** | `/api/agents` → `ai_agent` | 身份、入口、人设、权限、模型偏好、Workflow 路由策略 |
| **Workflow** | `/api/workflows` → `ai_workflow` | GraphSpec、canvas_json、节点、调试、版本、发布 |
| **Binding** | `/api/agents/{id}/workflow-bindings` | 按 PAGE / ROUTE / ACTION / INTENT 等解析目标 Workflow |

前端类型：`AgentEntry`（`types/workflow.ts`）、`WorkflowDefinition`；`types/agent.ts` 中的 `WorkflowCanvasSource` 仅供画布互操作过渡，不是管理主类型。

更多平台背景见仓库根目录 [`docs/03-Agent-Studio与Runtime.md`](../docs/03-Agent-Studio与Runtime.md)、[`AGENTS.md`](../AGENTS.md)。
