# AI Coding

AI Coding 将接入和改造目标交给外部编码工具，并在平台中保留任务、交付物与真实验收。页面识别、只读分析、代码实施和业务验收分别建模，不把提交文字或任务完成消息当作上线证据。

## 入口和身份

| 通道 | 身份与用途 |
| --- | --- |
| 管理端任务控制台 | 平台会话与项目权限，创建任务、查看进度和验收 |
| 一次性交接 | 激活 handoff 后获得任务级凭据，限定当前任务与能力 |
| 项目级 API | `X-ReachAI-AiCoding-Key`，读取 manifest、操作项目内 Agent / Workflow |

平台会话、项目 Key 与 Task Token 不互相替代。Key 不进入 URL query、生成代码、浏览器运行时代码或 Artifact；完整端点从 `/api/ai-coding/projects/{projectId}/manifest` 发现。

## 任务协议

任务内核由 Control 的 [aicoding](../reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/)实现，任务种类通过 Provider 注册；页面工作台、项目接入和托管执行分别接入，不能把每种任务复制成另一套状态机。

| 状态事实 | 说明 |
| --- | --- |
| 连接 | 客户端是否在线、会话是否可通信 |
| 技术交付 | 文件、构建、测试、事件和 Artifact 是否完整 |
| 业务验收 | 目标服务、SDK 回调、页面动作和真实业务结果是否符合要求 |

事件、问题与 Artifact 绑定任务和发送身份，遵守幂等与顺序约束。重连或迟到回传不能覆盖终态，也不能把其他任务的证据移入本任务。未完成验收时任务保留明确缺口，不用新的会话授权或执行提示词绕过。

## 页面改造

页面扫描识别路由、组件与接口，不凭名称推断价值。分析结果区分 `confirmedFact`、`technicalInference`、`openQuestion`，给出代码引用、实施参考与验收标准。资源绑定必须关联真实项目和页面，发布与回放能回到同一业务对象。

## Workflow API

| 操作 | 入口 |
| --- | --- |
| 创建工作副本 | `POST /api/workflows/ai-coding/workflows` |
| 上下文 | `GET /api/workflows/{workflowId}/ai-coding/context` |
| 校验 / Patch / 调试 / 发布 | `/api/workflows/{workflowId}/ai-coding/{validate|patch|run|publish}` |
| 页面助手资源与安全测试 | `/api/workflows/{workflowId}/ai-coding/page-assistant/**` |

先读取上下文与当前修订，再生成结构化 Patch，经服务端 mutation 和 GraphSpec 校验后保存；dry-run 不写入。资源绑定、项目范围和权限由服务端复核，不能直接写 Canvas 冒充流程。发布仍执行 [Workflow 发布契约](workflow.md)。

## Agent API

项目 manifest 提供 Agent 创建、上下文、身份更新、配置草稿、发布和绑定入口。身份更新不顺带修改运行配置；草稿携带上下文给出的基准版本，发布时重新证明 Workflow 与 Skill。

新建 Agent 固定在真实项目中，默认 STANDARD。可绑定的 Skill 只来自可见 PUBLISHED 精确版本，默认 `scriptPolicy=DENY`；添加与移除绑定不跳过平台治理，也不删除 Skill 包。

## Managed Executor

托管执行将任务排队、租约、审批、取消、Artifact 和事件留在 Runtime 控制面；Worker 调用 Codex app-server，在受限制的 Workspace / 沙箱内工作。Control 负责产品任务投影和用户权限，两套状态不能混为“已验收”。

执行配置必须明确 Profile、网络、凭据 Broker、资源、时限与留存；无人值守环境不继承用户电脑或平台主密钥。部署与攻击测试按[运维](operations.md)完成，冻结方向不因源码存在而自动放量。

平台分发的 onboarding、Workflow 与 Agent Skill 包是可执行交付物，按[版本规则](agent.md#内置包与来源)维护，不能当作普通说明文档随意改字节。
