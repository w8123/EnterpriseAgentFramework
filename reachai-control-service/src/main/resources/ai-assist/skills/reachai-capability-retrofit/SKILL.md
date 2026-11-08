---
name: reachai-capability-retrofit
description: Retrofit an already-connected business system so ReachAI can operate more of it. Inventory business operations that fit the developer's goal, let the developer pick which ones to open, declare the picked ones with the ReachAI SDK (Java, C#, Python, Node.js or Go), trigger one audited SDK sync, and report. Use when asked to "let AI do X in our system", open more business methods to ReachAI, or run a ReachAI CAPABILITY_RETROFIT task.
---

# ReachAI 业务能力改造

## 你在做什么

业务系统已经接入 ReachAI（装了 SDK、能心跳）。开发者想让 AI 多办几件事，比如“查应收款”“新建退货单”。你的工作是：读代码 → 列候选 → 等开发者勾选 → 给选中的方法加能力声明 → 编译测试 → 触发一次 SDK 同步 → 交报告。能力进入 ReachAI 后，由人在“能力变化评审”里接受，再发布给员工的 AI 工具。你不能替人评审或发布。

所有写进代码和 ReachAI 的名称、说明默认用清晰的简体中文。`name`、路径、枚举等技术标识保持英文。

## 任务通道

这个 Skill 总在一个 `CAPABILITY_RETROFIT` 任务里使用，任务有两种接法，选你手上有的那种：

1. **平台 MCP（推荐）**：开发者的 AI 工具已经用自己的身份连上 ReachAI 平台 MCP。
   - 没有任务时用 `reachai_retrofit_start`（projectCode、objective）新建，它会把任务直接领取到当前连接。
   - 读上下文：`reachai_ai_coding_task_context`；进度：`reachai_ai_coding_task_event`；提问/读回答：`reachai_retrofit_propose`、`reachai_ai_coding_task_questions`；SDK 同步：`reachai_ai_coding_task_verify`（verificationKey=SDK_SYNC）；交报告：`reachai_ai_coding_task_artifact`。
2. **一次性交接**：页面给了激活码和 PowerShell/Bootstrap。激活后用任务令牌调用 `/api/ai-coding/tasks/{taskId}/**`（context、events、questions、verifications/SDK_SYNC、artifacts）。

不管哪种，先读任务上下文（`context`），里面有项目、目标、语言、候选清单格式、声明写法、报告契约和 Skill 地址。不要猜接口和字段。不打印、不提交任何密钥、激活码、任务令牌。

## 步骤

### 1. 盘点（只读）

- 看清语言、框架、模块结构，找出已有的 ReachAI 能力声明，已经开放的不要再列。
- 按目标找业务方法。优先 Service 层（已有事务和权限），没有合适的再看 Controller。
- 每个候选写清：`candidateKey`（稳定标识，如 `erp.receivable.page`）、`title`（中文名）、`description`、`sideEffect`（READ / WRITE / IRREVERSIBLE）、`sourceFile`、`symbol`（类#方法）、对应 HTTP 方法和路径（有的话）、`reason`（为什么适合）、`risk`（风险提示）、`recommended`。
- 一次最多 20 个；目标很大时先给最有用的 20 个，在 summary 里说明还有哪些可以下一批做。
- 不要列：登录/注册/密码、文件上传下载、导入导出、批量删除、系统配置、定时任务、框架或第三方组件自带的接口、只给管理员用的危险操作。
- 会改数据的必须标 WRITE；删除、作废、付款、发货等无法撤回的标 IRREVERSIBLE。拿不准就标高一级。

### 2. 提交候选清单，等开发者勾选

- MCP：调用 `reachai_retrofit_propose`（taskId、summary、candidates），它会生成规范的问题。
- HTTP：`POST <taskRoot>/questions`，`questionId` 以 `retrofit-select-` 开头，`title` 为「请勾选要开放给 AI 的业务能力」，`body` 是 `reachai.capability-retrofit-candidates.v1` JSON 字符串，`options` 为全部 candidateKey。
- 然后等待。开发者可能在 ReachAI 页面勾选，也可能直接在你的对话里说“开 1、3”。只有开发者明确说了，你才能代为回答这个问题（`reachai_ai_coding_question_answer`，answer 写 `{"schema":"reachai.capability-retrofit-selection.v1","selected":[...],"note":""}`）。不要自己替开发者决定。
- 读到回答后写一条 `RESUMED` 事件再继续。没被勾选的候选一行代码都不要动。

### 3. 实施

- 按 `references/declarations.md` 里对应语言的写法加声明。参数用业务语言写说明；出参 DTO 字段可补 `@ReachOutput`（Java）等元数据。
- 写操作和需要知道当前员工的操作，设 `businessIdentityRequired = true`，让调用沿用业务系统自己的登录和数据权限。
- 只加声明和必要的元数据，不改业务逻辑、事务、权限、表结构，不调整无关格式和依赖版本。
- 跑最小的编译和相关测试；用新代码重启业务服务。

### 4. SDK 同步

- 心跳在线后，触发一次 `SDK_SYNC`（MCP：`reachai_ai_coding_task_verify`；HTTP：`POST <taskRoot>/verifications/SDK_SYNC`）。
- 失败时区分：服务不可达/超时、业务登录或 CSRF 拦截（401/403）、路由或 context-path 不对（404/405）、签名被拒。按原因修，不要关闭签名校验。
- 同步成功只表示能力进了“待评审”，不代表已上线。

### 5. 交报告

提交一份 `reachai.capability-retrofit-report.v1`（契约在任务上下文里）：

- `selectionQuestionId`：步骤 2 的问题 ID。
- `capabilities[]`：每个被勾选的候选一行，`name` 写 SDK 注册时的能力名，`status` 为 DECLARED 或 SKIPPED（写明原因）。
- `changedFiles`、`tests`（真实命令和结果）、`sdkSync`（PASS / FAIL / NOT_RUN 和说明）、`remainingQuestions`。
- 不编造测试结果。没跑就写 NOT_RUN。

## 不改代码的选项

开发者明确要求不改业务代码，或系统语言没有 ReachAI SDK 时：按选中的候选生成 OpenAPI 3 文档（`docs/reachai/openapi.yaml`，只含选中的接口，中文 summary/description，写操作加 `x-reachai-side-effect: WRITE`），请开发者在 ReachAI「业务能力 → HTTP API → 导入 OpenAPI」导入并接受。报告 `mode` 写 `OPENAPI_IMPORT`，`name` 写导入后的 keySlug，`sdkSync.status` 写 `NOT_RUN`。

## 结束时告诉开发者

- 改了哪些文件，声明了哪些能力（中文名 + name + 读/写）。
- 测试和 SDK 同步结果。
- 下一步：到 ReachAI 项目「用 AI 改造」页面（或「业务能力 → 接入与变更」）接受新能力，再点“发布给员工 AI 工具”。写操作员工调用时会走审批。
