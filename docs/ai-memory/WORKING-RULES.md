# ReachAI Working Rules For AI Agents

## First Steps

- 先读根目录 `AGENTS.md`。
- 再读本目录的 `PROJECT-MEMORY.md`、`DECISIONS.md` 和 `KNOWN-PITFALLS.md`。
- 对具体任务，先用 `rg` 找真实代码、接口、SQL 和文档，不要凭记忆改。
- 如果记忆和当前代码冲突，以当前代码为准，并更新记忆。

## Change Scope

- 允许修改前端、后端、SQL、文档和构建配置。
- 改动必须聚焦当前任务，不要混入无关格式化、迁移或重构。
- 发现用户已有未提交改动时，保留并绕开；不要回滚。
- 如果同一文件中已有用户改动，先理解再追加自己的改动。

## Compatibility Policy

- 本项目默认不为旧数据做复杂兼容。
- 当前正确设计优先，允许重命名、删字段、调整结构、改种子数据。
- 如果 SQL 具有破坏性，必须在 upgrade SQL 注释和最终说明中明确写出影响。
- 新业务接入协议、SDK 契约、Maven artifact 使用 `reachai.*`、`X-ReachAI-*`、`Reach*`、`reachai-*`。历史 `eaf.*`、`X-EAF-*`、`Eaf*` 属于旧兼容边界；旧 `ai-spring-boot-starter` 已退役。

## SQL Change Policy

任何数据库变化必须同时完成：

1. 修改 `sql/init.sql`，保证新环境直接可用。
2. 新增 `sql/upgrade-YYYYMMDD-short-name.sql`，保证已有开发/测试库能升级。
3. 更新 `sql/README.md` 或相关文档。
4. 检查实体、Mapper、前端类型和 API DTO 是否同步。

不再新增或依赖：

- `ai-agent-service/sql`
- `ai-model-service/sql`
- `ai-skills-service/sql`

升级 SQL 命名示例：

- `sql/upgrade-20260528-agent-studio-node-contract.sql`
- `sql/upgrade-20260528-embed-chat-audit.sql`

## Backend Rules

- Spring Boot / Java 17 是主线。
- 后端模块多服务聚合在根 `pom.xml` 下，优先跑目标模块测试或编译。
- 具体错误优先从 stack trace、Controller、Service、Mapper、Entity、SQL 表结构链路查起。
- 不要因为前端报错就先改前端；先确认响应 envelope、接口路径和后端 owner。
- MyBatis 字段缺失或 SQLSyntaxErrorException 通常要同时查实体、Mapper XML/注解、`sql/init.sql` 和升级 SQL。

## Frontend Rules

- Vue 3 + Element Plus + Vite 是主线。
- 管理端是工作台产品，优先密度、稳定布局和重复操作效率。
- 主题、色彩、暗色/亮色优先改 CSS 变量和共享主题，不要页面级硬编码。
- Workflow Studio 改动要关注画布、配置面板、AI 预览/应用、发布校验、调试会话和 Runtime 合同。
- 改路由、侧边栏、项目范围选择时先看 `MainLayout.vue`、`ProjectSelector.vue`、router 和 project store。

## MCP Tool Rules

- 浏览器调试、截图、DOM 快照、console 或 network 观察，优先使用 Playwright MCP。
- 实时 MySQL schema 或少量诊断数据查询，优先使用 `dbhub_ai_mysql`。
- `dbhub_ai_mysql` 只用于只读诊断；不要通过 AI 工具执行 DDL/DML、迁移、批量导出或敏感数据读取。
- MCP 配置示例在 `.cursor/mcp.json`；数据库配置模板在 `.cursor/dbhub-ai-mysql.toml`；具体环境变量和密码只放本机，不进仓库。

## Documentation Rules

- `README.md` 是对外入口，强调 ReachAI 的产品定位和完整链路。
- `docs/README.md` 是内部知识库入口。
- `docs/ai-memory/` 是给 AI 工具看的上下文，不要写成营销文案。
- 新增系统文档按产品能力组织，不按历史阶段组织。
- 文档必须指向真实代码、接口、SQL 表或页面，不要写只存在于规划里的能力。

## Naming Rules

- 默认使用 `Capability / 能力` 描述产品能力。
- 当前后端逻辑域按 Model Gateway、Knowledge / Retrieval、Capability Catalog、Runtime Host、Platform Control 组织；现有三服务名只是部署单元。
- `ai-skills-service` 是当前 Knowledge / Retrieval 部署单元，不再在产品、文档或 UI 中描述成“技能服务”。
- `Skill` 多为历史代码、legacy SQL 或内部旧命名。
- 不要盲目全局替换 `Skill`，尤其不要自动重命名 SQL 表、字段、API 路径或 SDK 契约。
- 如果做命名迁移，应分阶段：用户可见文案 -> 前端类型/目录 -> 后端类/包 -> SQL/API，且每阶段都要验证。

## Verification Policy

- 后端改动：跑目标模块测试或编译。
- 前端改动：在 `ai-admin-front` 跑 `npm run build`；类型风险高时先跑 `npx vue-tsc --noEmit`。
- SQL 改动：检查 `sql/init.sql` 和 upgrade SQL；有 MySQL 环境时执行。
- 文档/规则改动：跑 `git diff --check`，再用 `rg` 检查关键路径。
- 不能执行某项验证时，最终说明必须明确说没跑以及原因。
