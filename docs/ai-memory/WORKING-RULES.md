# ReachAI Working Rules For AI Agents

## First Steps

- 先读根目录 `AGENTS.md`。
- 再读本目录的 `PROJECT-MEMORY.md`、`DECISIONS.md` 和 `KNOWN-PITFALLS.md`。
- 对具体任务，先用 `rg` 查真实代码、接口、SQL 和文档，不要凭记忆修改。
- 如果记忆和当前代码冲突，以当前代码为准，并更新记忆。

## Change Scope

- 允许修改前端、后端、SQL、文档和构建配置。
- 改动必须聚焦当前任务，不要混入无关格式化、迁移或重构。
- 发现用户已有未提交改动时，保留并绕开；不要回滚。
- 如果同一文件中已有用户改动，先理解再追加自己的改动。

## Compatibility Policy

- 本项目默认不为旧数据做复杂兼容。
- 当前正确设计优先，允许重命名、删字段、调结构、改种子数据。
- 如果 SQL 具有破坏性，必须在当次 upgrade SQL 注释或最终说明中写清影响。
- 新业务接入协议、SDK 契约、Maven artifact 使用 `reachai.*`、`X-ReachAI-*`、`Reach*`、`reachai-*`。
- 历史 `eaf.*`、`X-EAF-*`、`Eaf*` 属于兼容敏感技术身份，不要顺手替换。

## SQL Change Policy

任何数据库变化必须先完成：

1. 修改 `sql/initV2.sql`，保证全新环境直接可用。
2. 如果已有开发/测试库需要升级，新增当次 `sql/upgrade-YYYYMMDD-short-name.sql`，并在确认合入新基线后按规则清理历史 upgrade；当前 V2 新库重建场景不要求旧数据迁移。
3. 更新 `sql/README.md` 或相关文档。
4. 检查实体、Mapper、前端类型和 API DTO 是否同步。
5. 更新 `docs/architecture/service-table-ownership.md`，确保 `sql/initV2.sql` 中新增或保留的 `CREATE TABLE` 有唯一 owning service。
6. 运行 `node scripts/check-service-table-ownership.test.mjs` 和 `node scripts/check-service-table-ownership.mjs`。

不再新增或依赖：

- `ai-agent-service/sql`
- `ai-model-service/sql`
- `ai-skills-service/sql`

## Backend Rules

- Spring Boot / Java 17 是主线。
- 当前后端主路径是 `reachai-control-service`、`reachai-runtime-service`、`reachai-capability-service`、`reachai-knowledge-service`、`reachai-model-service`。
- 第一阶段保持同一个 MySQL 库，不拆库；当前新库基线为 `sql/initV2.sql`，旧 `sql/init.sql` 已退场。
- `docs/architecture/service-table-ownership.md` 是同库阶段的表所有权矩阵；跨服务直接读写表默认违规。
- 直接表访问包括 `@TableName`、MyBatis 注解 SQL、MyBatis XML SQL 和 JdbcTemplate SQL。确需临时兼容访问时，必须写入矩阵的 `Additional direct access`，说明访问服务和理由。
- 服务间协作优先使用 owning service 的 internal API、显式 client 或服务自有 read model；不要跨服务 import 对方 Mapper/Entity。
- `reachai-control-service` 是公共 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service` 承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和 Runtime 内部 API。
- `reachai-capability-service` 承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service` 承接 Knowledge / Retrieval，不再叫技能服务。
- `reachai-model-service` 承接 Model Gateway。
- 旧 `ai-agent-service` module 已删除，不再作为 Maven、IDEA、本地启动或部署单元存在。
- 新增或修改 `/internal/runtime/**`、`/internal/capability/**`、`/internal/control/**` 时，必须同步 `docs/architecture/internal-api-contracts.md` 并运行 `node scripts/check-internal-api-contracts.mjs`。
- 具体错误优先从 stack trace、Controller、Service、Mapper、Entity、SQL 表结构链路查起。

## Frontend Rules

- Vue 3 + Element Plus + Vite 是主线。
- 管理端是工作台产品，优先密度、稳定布局和重复操作效率。
- 主题、色彩、暗色/亮色优先改 CSS 变量和共享主题，不要页面级硬编码。
- Workflow Studio 改动要关注画布、配置面板、AI 预览/应用、发布校验、调试会话和 Runtime 合同。
- Registry/Scan 大页已拆出 `components/page-assistant/`、`components/scan-project/`、registry/scan composables 和 viewModel；继续改页面前先复用这些边界，不要把逻辑重新塞回主 `.vue`。
- 改路由、侧边栏、项目范围选择时先看 `MainLayout.vue`、`ProjectSelector.vue`、router 和 project store。
- 前端代理保持 `/api -> 18603`、`/ai -> 18602`、`/model -> 18601`；不要直接依赖 Runtime 或 Capability 内部端口。

## MCP Tool Rules

- 浏览器调试、截图、DOM 快照、console 或 network 观察，优先使用 Playwright MCP。
- 实时 MySQL schema 或少量诊断数据查询，优先使用 `dbhub_ai_mysql`。
- `dbhub_ai_mysql` 只用于只读诊断；不要通过 AI 工具执行 DDL/DML、迁移、批量导出或敏感数据读取。
- 具体环境变量和密码只放本机，不进仓库。

## Documentation Rules

- `README.md` 是对外入口，强调 ReachAI 的产品定位和完整链路。
- `docs/README.md` 是内部知识库入口。
- `docs/ai-memory/` 是给 AI 工具看的上下文，不要写成营销文案。
- 新增系统文档按产品能力组织，不按历史阶段组织。
- 文档必须指向真实代码、接口、SQL 表或页面，不要写只存在于规划里的能力。

## Naming Rules

- 默认使用 `Capability / 能力` 描述产品能力。
- `reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再在产品、文档或 UI 中描述成“技能服务”。
- `Skill` 多为历史代码、legacy SQL 或内部旧命名。
- 不要盲目全局替换 `Skill`，尤其不要自动重命名 SQL 表、字段、API 路径或 SDK 契约。

## Verification Policy

- 后端改动：跑目标模块测试或编译。
- 前端改动：在 `ai-admin-front` 跑 `npm run build`；类型风险高时先跑 `npx vue-tsc --noEmit`。
- SQL 改动：检查 `sql/initV2.sql`；如果当前任务新增 upgrade SQL，也检查该 upgrade SQL。有 MySQL 环境时执行验证。
- 文档/规则改动：跑 `git diff --check`，再用 `rg` 检查关键路径。
- 路线A后端拆分相关改动优先跑：
  - `node scripts/check-backend-boundary-naming.mjs`
  - `node scripts/check-frontend-public-api-routes.test.mjs`
  - `node scripts/check-frontend-public-api-routes.mjs`
  - `node scripts/check-physical-service-route-contracts.test.mjs`
  - `node scripts/check-physical-service-route-contracts.mjs`
  - `node scripts/check-backend-domain-dependencies.test.mjs`
  - `node scripts/check-backend-domain-dependencies.mjs`
  - `node scripts/check-service-table-ownership.test.mjs`
  - `node scripts/check-service-table-ownership.mjs`
  - `node scripts/check-internal-api-contracts.test.mjs`
  - `node scripts/check-internal-api-contracts.mjs`
  - `node scripts/check-physical-service-smoke.test.mjs`
  - 五服务 Maven compile
- 不能执行某项验证时，最终说明必须明确说没跑以及原因。
