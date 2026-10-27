# ReachAI Agent Rules

进入仓库先读本文件，再从[文档入口](docs/README.md)选择当前任务相关主题。人和 AI 共用一套文档，不另存项目记忆、历史方案或执行提示词。

## 工作边界

- 以当前代码、SQL、配置、接口与实际运行结果为事实源；文档中的目标不等于已实现或已验收。
- 只修改当前任务相关内容，保留其他未提交工作；常规实现自主推进，改变已确认产品边界时先明确方案与影响。
- 产品、用户和核心对象以[产品说明](docs/product.md)为准。Capability 是业务资产统称，Tool 是调用投影，Workflow 是 GraphSpec；Skill 仅指标准包或协议字段。
- 不把文档、旧会话或未启动待办当作执行授权。不因整理代码或文档自动提交、推送、部署、改库或调用真实写业务。
- 搜索优先 `rg` / `rg --files`，中文文件使用 UTF-8。

## 架构与数据

- `reachai-control-service` 是公共 API、身份与治理入口；`reachai-runtime-service` 执行 Agent / Workflow。
- `reachai-capability-service` 拥有项目来源、业务方法/API；`reachai-knowledge-service` 拥有知识与检索投影；`reachai-model-service` 统一模型调用。
- 同库不共享表所有权。跨服务通过 internal API、显式 client 或服务自有 read model，不访问对方 Mapper、Entity 或 SQL 表。
- schema、索引、种子和字段语义变更同步 `sql/initV2.sql`；已有环境需要时新增 upgrade SQL 并更新 `sql/README.md`，执行后回读验证。
- 精确依据：[架构](docs/architecture.md)、[表归属](docs/contracts/tables.md)、[内部 API](docs/contracts/internal-apis.md)、[公共路由](docs/contracts/public-routes.md)。

## 产品实现

- 业务能力改动同时服务 Java 开发者与 Agent / Workflow 实施人员，按[U1–U4 验收](docs/status.md#业务能力验收)关联具体任务。
- GraphSpec 保存执行语义，Canvas 只保存布局；Handler、发布校验、Studio 与 AI mutation 保持一致。
- 调用身份只能来自受验证链路；ACL、来源契约、版本 pin 与副作用保护不由浏览器或模型字段绕过。
- 前端先核对路由、MainLayout、共享组件和状态管理；复用公共 liquid-glass 入口与主题 token，规则见[DESIGN](ai-admin-front/DESIGN.md)。
- `eaf.*`、`X-EAF-*`、Java/Maven/协议技术身份不随品牌文案顺手改名。
- 随产品分发的内置 Skill 内容变更必须升级版本和摘要，见[Skill 版本规则](docs/agent.md#内置包与来源)。

## Windows 与 UTF-8

含中文的 Python、Node、JSON、SQL 或请求体优先写入 UTF-8 文件再执行。通过 PowerShell 管道传给原生程序时先设置：

```powershell
$utf8 = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = $utf8
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
```

API 或数据库写入中文后必须回读；出现连续 `?` 视为失败，不继续验收。

## 验证与文档

- 后端运行相关模块的目标测试或编译；前端运行相关行为测试与 `npm run build`，需要时验证真实浏览器。
- 具体错误须复现或对照同一错误签名后才能声明修复。夹具、构建、制品、运行与业务验收分别说明边界。
- 文档只保留当前核心内容；同一事实维护一次，重复、过时和历史记录直接删除，不留归档。必要的未验条件统一写入 [status](docs/status.md)。
- 移动、合并或删除文档同步引用和检查器，运行 `node scripts/check-documentation.mjs`、相关检查器测试与 `git diff --check`。

构建、排错和常用命令见[开发](docs/development.md)，环境操作见[运维](docs/operations.md)。
